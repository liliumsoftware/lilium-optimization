/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.liliumsoftware.optimization.routing;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin HTTP client for an OSRM routing server. Exposes the route geometry ({@code /route/v1}), the all-pairs
 * duration matrix ({@code /table/v1}), and single-trip duration/distance. OSRM returns durations in seconds;
 * this client converts them to integer <strong>milliseconds</strong> (×1000, rounded to the nearest millisecond) to
 * match the library's integral cost model, and formats coordinates as {@code lon,lat} via a per-thread
 * {@link DecimalFormat}. A typical use is a
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation#getCostMatrix(List)} implementation
 * that maps its nodes to {@link Point}s and calls {@link #getCostMatrix(List)}.
 *
 * <p>Any non-200 response, unparseable body, or body without the expected {@code routes}/{@code durations}
 * content (the <a href="https://project-osrm.org/docs/v5.24.0/api/">OSRM HTTP API</a> shape) surfaces as
 * {@link RoutingException}; so does a {@code null} duration or distance and a {@code null} cell of the duration table
 * (OSRM's answer for a pair it cannot route between), whose message names the pair, and so does a negative duration,
 * distance or table cell (no real trip takes negative time or distance, and the search would read it as a saving).
 * Failures are reported through the exception only, they are not logged as errors. All calls use the {@code driving}
 * profile. Coordinates are always formatted with a {@code '.'} decimal separator regardless of the default locale.
 *
 * <p><strong>Input validation.</strong> Every coordinate is checked before a request is built: a {@link Point} without
 * coordinates, a non-finite longitude or latitude, a longitude outside {@code [-180, 180]} or a latitude outside
 * {@code [-90, 90]} is rejected with {@link IllegalArgumentException} (a problem with the caller's input, not with the
 * routing service), so the formatter's three integer digits can never truncate a bad value into a plausible one. The
 * base URL is checked by the constructors (an absolute {@code http}/{@code https} URL with a host).
 *
 * <p><strong>Thread-safety and lifecycle.</strong> One {@link HttpClient} is shared for the provider's lifetime
 * (connection reuse, no per-call thread pools) and {@link HttpClient} is thread-safe, so a single instance may be
 * used from any number of threads. {@link #close()} releases the client; it is only required when the provider
 * owns the client (constructors without an {@link HttpClient} argument), and is a no-op for a client supplied by the
 * caller. The owned client is {@link HttpClient#newHttpClient()}: it does not follow redirects
 * ({@link HttpClient.Redirect#NEVER}), so a redirect answer surfaces as a non-200 {@link RoutingException}, and it
 * has no connect timeout of its own.
 *
 * <p><strong>Timeouts.</strong> Every request is bounded by {@link #DEFAULT_REQUEST_TIMEOUT} (30 seconds) unless a
 * request timeout is passed to the constructor; {@link #NO_REQUEST_TIMEOUT} switches the bound off, so a request then
 * waits as long as the server takes. The bound covers the whole exchange, from sending the request until the last
 * byte of the response body has arrived, so a server that sends its headers and then stalls the body cannot block the
 * caller either. A server that does not answer completely in time surfaces as a {@link RoutingException} caused by
 * {@link HttpTimeoutException}, and the exchange is cancelled. A separate connect timeout belongs to the
 * {@link HttpClient}, so configure it on a client you supply; with {@link #NO_REQUEST_TIMEOUT} and the owned client,
 * nothing bounds a call.
 *
 * <p><strong>Interrupts.</strong> A call blocks the calling thread until the exchange completes. If that thread is
 * interrupted while waiting, the exchange is cancelled, the thread's interrupt flag is restored and a
 * {@link RoutingException} caused by the {@link InterruptedException} is thrown.
 *
 * <p><strong>Response size.</strong> A response body is read into memory before it is parsed, but never more than the
 * provider's response limit: {@link #DEFAULT_MAX_RESPONSE_BYTES} (512 MiB) unless another limit is passed to the
 * constructor, {@link #NO_RESPONSE_LIMIT} for none. The bytes are counted as they arrive; as soon as a body (of any
 * status) exceeds the limit, or announces a larger {@code Content-Length}, the exchange is cancelled and the call
 * fails with a {@link RoutingException} caused by an {@link IOException} whose message names the limit, so an
 * oversized or endless answer can neither exhaust the heap nor be buffered whole. The {@code /table} answer is then
 * parsed as a token stream straight into the {@code int[][]} result, without building a JSON tree, and at most one
 * row and one cell per coordinate are kept, however many the server sends. Bodies are decoded as JSON's UTF-8 (or the
 * UTF-16/32 encoding Jackson detects), whatever charset the {@code Content-Type} header names.
 */
@Slf4j
public class RoutingProvider implements AutoCloseable {

    /**
     * The request timeout of the constructors that take none, 30 seconds. Like any request timeout it bounds each
     * whole exchange, from sending the request until the response body is complete: long enough for a large
     * {@code /table} request, short enough that a server that never answers (or stalls mid-body) cannot block a
     * planning run forever.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Pass as the request timeout to wait for every answer without a bound ({@link Duration#ZERO}).
     */
    public static final Duration NO_REQUEST_TIMEOUT = Duration.ZERO;
    /**
     * The response limit of the constructors that take none: 512 MiB per response body. An OSRM {@code /table} body
     * spends roughly 8 to 12 bytes per cell (a duration with one decimal plus a separator), so this admits a full
     * table of about 6 500 to 8 000 points; that is far above OSRM's own default table limit of 100 points and about
     * as large as a single call can usefully get, since the {@code int[][]} result for 7 000 points alone occupies
     * some 200 MB. Route answers, even with a full geometry, are a few megabytes at most. A server that sends more is
     * either misconfigured or hostile, and is stopped before it can exhaust the heap.
     */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 512L * 1024 * 1024;
    /**
     * Pass as the response limit to read response bodies of any size ({@code 0}).
     */
    public static final long NO_RESPONSE_LIMIT = 0;
    /**
     * Coordinate formatter: up to 15 fraction digits, always a {@code '.'} decimal separator and no grouping,
     * whatever the JVM's default locale is. {@link DecimalFormat} is not thread-safe, so one instance per thread. The
     * three integer digits suffice because coordinates are validated to {@code [-180, 180]} first.
     */
    private static final ThreadLocal<DecimalFormat> DECIMAL_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("0.###############", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setMaximumIntegerDigits(3);
        return format;
    });
    private final String routingServiceUrl;
    private final JsonMapper mapper;
    private final HttpClient client;
    private final boolean ownsClient;
    private final Duration requestTimeout;
    private final long maxResponseBytes;

    /**
     * Creates a provider owning a default {@link HttpClient} whose requests each time out after
     * {@link #DEFAULT_REQUEST_TIMEOUT}, with response bodies limited to {@link #DEFAULT_MAX_RESPONSE_BYTES}; call
     * {@link #close()} when done with it.
     *
     * @param routingServiceUrl base URL of the OSRM server, for example {@code http://localhost:5000}, without a
     *                          trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code routingServiceUrl} is not an absolute {@code http} or {@code https}
     *                                  URL with a host
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper) {
        this(routingServiceUrl, mapper, null, true, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Creates a provider owning a default {@link HttpClient} whose requests each time out after
     * {@code requestTimeout}, with response bodies limited to {@link #DEFAULT_MAX_RESPONSE_BYTES}; call
     * {@link #close()} when done with it.
     *
     * @param routingServiceUrl base URL of the OSRM server, for example {@code http://localhost:5000}, without a
     *                          trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @param requestTimeout    longest time to wait for each complete response, or {@link #NO_REQUEST_TIMEOUT} for no
     *                          bound
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code requestTimeout} is negative, or {@code routingServiceUrl} is not an
     *                                  absolute {@code http} or {@code https} URL with a host
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, Duration requestTimeout) {
        this(routingServiceUrl, mapper, null, true, nonNegative(requestTimeout), DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Creates a provider owning a default {@link HttpClient} whose requests each time out after
     * {@code requestTimeout} and whose response bodies may hold at most {@code maxResponseBytes} bytes; call
     * {@link #close()} when done with it.
     *
     * @param routingServiceUrl base URL of the OSRM server, for example {@code http://localhost:5000}, without a
     *                          trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @param requestTimeout    longest time to wait for each complete response, or {@link #NO_REQUEST_TIMEOUT} for no
     *                          bound
     * @param maxResponseBytes  largest response body accepted, in bytes, or {@link #NO_RESPONSE_LIMIT} for no limit
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code requestTimeout} or {@code maxResponseBytes} is negative, or
     *                                  {@code routingServiceUrl} is not an absolute {@code http} or {@code https} URL
     *                                  with a host
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, Duration requestTimeout,
            long maxResponseBytes) {
        this(routingServiceUrl, mapper, null, true, nonNegative(requestTimeout), nonNegative(maxResponseBytes));
    }

    /**
     * Creates a provider that sends requests through the caller's {@link HttpClient} (connect timeout, proxy,
     * executor configured there), each timing out after {@link #DEFAULT_REQUEST_TIMEOUT}, with response bodies limited
     * to {@link #DEFAULT_MAX_RESPONSE_BYTES}. The caller keeps ownership of the client; {@link #close()} will not close
     * it.
     *
     * @param routingServiceUrl base URL of the OSRM server, for example {@code http://localhost:5000}, without a
     *                          trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @param client            the client every request is sent through; never closed by the provider
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code routingServiceUrl} is not an absolute {@code http} or {@code https}
     *                                  URL with a host
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client) {
        this(routingServiceUrl, mapper, Objects.requireNonNull(client, "client"), false, DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Like {@link #RoutingProvider(String, JsonMapper, HttpClient)}, with every request timing out after
     * {@code requestTimeout} ({@link #NO_REQUEST_TIMEOUT} for no bound) and response bodies limited to
     * {@link #DEFAULT_MAX_RESPONSE_BYTES}.
     *
     * @param routingServiceUrl base URL of the OSRM server, for example {@code http://localhost:5000}, without a
     *                          trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @param client            the client every request is sent through; never closed by the provider
     * @param requestTimeout    longest time to wait for each complete response, or {@link #NO_REQUEST_TIMEOUT} for no
     *                          bound
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code requestTimeout} is negative, or {@code routingServiceUrl} is not an
     *                                  absolute {@code http} or {@code https} URL with a host
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client, Duration requestTimeout) {
        this(routingServiceUrl, mapper, Objects.requireNonNull(client, "client"), false, nonNegative(requestTimeout),
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Like {@link #RoutingProvider(String, JsonMapper, HttpClient, Duration)}, with response bodies limited to
     * {@code maxResponseBytes} bytes ({@link #NO_RESPONSE_LIMIT} for no limit).
     *
     * @param routingServiceUrl base URL of the OSRM server, for example {@code http://localhost:5000}, without a
     *                          trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @param client            the client every request is sent through; never closed by the provider
     * @param requestTimeout    longest time to wait for each complete response, or {@link #NO_REQUEST_TIMEOUT} for no
     *                          bound
     * @param maxResponseBytes  largest response body accepted, in bytes, or {@link #NO_RESPONSE_LIMIT} for no limit
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code requestTimeout} or {@code maxResponseBytes} is negative, or
     *                                  {@code routingServiceUrl} is not an absolute {@code http} or {@code https} URL
     *                                  with a host
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client, Duration requestTimeout,
            long maxResponseBytes) {
        this(routingServiceUrl, mapper, Objects.requireNonNull(client, "client"), false, nonNegative(requestTimeout),
                nonNegative(maxResponseBytes));
    }

    /**
     * Validates every argument first and only then creates the owned client ({@code client == null}), so a rejected
     * argument never leaves an unclosed client (and its selector thread) behind.
     */
    private RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client, boolean ownsClient,
            Duration requestTimeout, long maxResponseBytes) {
        this.routingServiceUrl = requireHttpUrl(routingServiceUrl);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.ownsClient = ownsClient;
        this.client = ownsClient ? HttpClient.newHttpClient() : client;
    }

    /**
     * Fails fast on a base URL no request could ever be sent to, instead of failing every call later.
     */
    private static String requireHttpUrl(String routingServiceUrl) {
        Objects.requireNonNull(routingServiceUrl, "routingServiceUrl");
        URI uri;
        try {
            uri = new URI(routingServiceUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("routingServiceUrl is not a valid URL: " + routingServiceUrl, e);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null) {
            throw new IllegalArgumentException("routingServiceUrl must be an absolute http or https URL with a host,"
                    + " was " + routingServiceUrl);
        }
        return routingServiceUrl;
    }

    private static Duration nonNegative(Duration requestTimeout) {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must not be negative, was " + requestTimeout);
        }
        return requestTimeout;
    }

    private static long nonNegative(long maxResponseBytes) {
        if (maxResponseBytes < 0) {
            throw new IllegalArgumentException("maxResponseBytes must not be negative, was " + maxResponseBytes);
        }
        return maxResponseBytes;
    }

    private static String format(double coordinate) {
        return DECIMAL_FORMAT.get().format(coordinate);
    }

    /**
     * {@code lon,lat} of {@code point}, after checking that it is a valid WGS 84 coordinate.
     *
     * @param position the point's position in the request, for the error message
     * @throws IllegalArgumentException if the point or its coordinates are missing, not finite or out of range
     */
    private static String lonLat(Point point, int position) {
        if (point == null || point.getCoordinates() == null) {
            throw new IllegalArgumentException("Point " + position + " has no coordinates");
        }
        double longitude = point.getCoordinates().getLongitude();
        double latitude = point.getCoordinates().getLatitude();
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180
                || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Point " + position + " has an invalid coordinate (longitude "
                    + longitude + ", latitude " + latitude + "); expected a finite longitude in [-180, 180] and"
                    + " latitude in [-90, 90]");
        }
        return format(longitude) + "," + format(latitude);
    }

    /**
     * {@code lon,lat;lon,lat;…} for {@code coordinates}, each validated by {@link #lonLat}.
     */
    private static String lonLats(List<Point> coordinates) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) {
                path.append(';');
            }
            path.append(lonLat(coordinates.get(i), i));
        }
        return path.toString();
    }

    private static JsonNode field(JsonNode route, String name) throws RoutingException {
        JsonNode value = route.get(name);
        if (value == null || value.isNull()) {
            throw new RoutingException("Routing service response has no route " + name);
        }
        if (!value.isNumber()) {
            throw new RoutingException("Routing service response route " + name + " is not a number: " + value);
        }
        return value;
    }

    /**
     * OSRM seconds as whole milliseconds, rounded to the nearest one.
     *
     * @throws RoutingException if the value is not finite or does not fit a 32-bit matrix cell (about 24.8 days), or
     *                          is negative (checked in that order)
     */
    private static int toMilliseconds(double seconds, int row, int column) {
        long millis = Math.round(seconds * 1000);
        if (!Double.isFinite(seconds) || millis < Integer.MIN_VALUE || millis > Integer.MAX_VALUE) {
            throw new RoutingException("Routing service duration from coordinate " + row + " to coordinate " + column
                    + " (" + seconds + " s) does not fit an int of milliseconds");
        }
        if (seconds < 0) {
            throw new RoutingException("Routing service duration from coordinate " + row + " to coordinate " + column
                    + " (" + seconds + " s) is negative");
        }
        return (int) millis;
    }

    private static String describe(List<Point> coordinates, int index) {
        return index < coordinates.size() ? lonLat(coordinates.get(index), index) : "unknown";
    }

    /**
     * The next token inside a structure the parser has entered, where the end of the input is a syntax error.
     */
    private static JsonToken next(JsonParser parser) {
        JsonToken token = parser.nextToken();
        if (token == null) {
            throw new StreamReadException(parser, "Unexpected end of the response body");
        }
        return token;
    }

    /**
     * Rejects a value that would saturate or wrap when converted to a {@code long}.
     *
     * @throws RoutingException if {@code value} is not finite or outside the {@code long} range
     */
    private static void requireLongRange(double value, String name) throws RoutingException {
        // 2^63 is the first double above Long.MAX_VALUE; -2^63 is Long.MIN_VALUE itself
        if (!Double.isFinite(value) || value >= 0x1p63 || value < -0x1p63) {
            throw new RoutingException("Routing service response route " + name + " is out of range: " + value);
        }
    }

    /**
     * Rejects a negative duration or distance: no trip takes negative time or distance, and a cost model would read it
     * as a saving. Any negative value is rejected, even one that would round to {@code 0}; {@code -0.0} is accepted.
     *
     * @throws RoutingException if {@code value} is negative
     */
    private static void requireNonNegative(double value, String name) throws RoutingException {
        if (value < 0) {
            throw new RoutingException("Routing service response route " + name + " is negative: " + value);
        }
    }

    /**
     * The failure behind an asynchronous exchange, without the {@link ExecutionException} and
     * {@link CompletionException} wrappers.
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * The request URL {@code base/service/v1/driving/lon,lat;…}; for an empty list the bare
     * {@code base/service/v1/driving}, without a trailing slash, for every service alike.
     */
    private String requestUrl(String service, List<Point> coordinates) {
        String path = lonLats(coordinates);
        return this.routingServiceUrl + "/" + service + "/v1/driving" + (path.isEmpty() ? "" : "/" + path);
    }

    /**
     * The driving route geometry from {@code start} to {@code end} ({@code /route/v1/driving}, full GeoJSON overview)
     * of the first route OSRM returns.
     *
     * @param start the first point of the route
     * @param end   the last point of the route
     * @return the route geometry as a new {@link LineString} of {@code [lon, lat]} positions
     * @throws IllegalArgumentException if a point is {@code null}, has no coordinates or has an invalid coordinate
     * @throws RoutingException         if the exchange fails, times out or is interrupted, on a non-200 answer, an
     *                                  unparseable body, or a body without a route geometry or whose geometry is not a
     *                                  GeoJSON {@code LineString}
     */
    public LineString getRoute(Point start, Point end) throws RoutingException {
        String request = requestUrl("route", Arrays.asList(start, end)) + "?geometries=geojson&overview=full";

        byte[] response = this.sendRequest(request);
        try {
            JsonNode geometry = firstRoute(response).get("geometry");
            if (geometry == null || geometry.isNull()) {
                throw new RoutingException("Routing service response has no route geometry");
            }
            return this.mapper.convertValue(geometry, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new RoutingException("Routing request failed", e);
        }
    }

    /**
     * All-pairs travel-time matrix for {@code coordinates}, in milliseconds (OSRM seconds × 1000, rounded to the
     * nearest millisecond, so floating-point error cannot turn 32.3 s into 32 299 ms), row/column aligned to the list
     * ({@code /table/v1/driving/lon,lat;…}). The table must have exactly one row per coordinate and one cell per
     * coordinate in each row; this also holds for an empty list, which sends the bare {@code /table/v1/driving}
     * request and accepts only an empty table. The body is parsed as a token stream straight into the result (see the
     * class description); the checks and their precedence are those of reading the whole document first: a malformed
     * body fails before any table check, a wrong row count before any row's content, and otherwise the first faulty
     * row in order, where a row that is not an array or has the wrong cell count is reported before its first faulty
     * cell. If the body carries {@code durations} more than once, the last occurrence counts.
     *
     * @param coordinates the points, in matrix order; the list is not modified
     * @return a new {@code n × n} matrix whose cell {@code [i][j]} is the duration from point {@code i} to point
     *         {@code j}, for {@code n = coordinates.size()}
     * @throws NullPointerException     if {@code coordinates} is {@code null}
     * @throws IllegalArgumentException if a point is {@code null}, has no coordinates or has an invalid coordinate
     * @throws RoutingException         if the exchange fails, times out, is interrupted or exceeds the response limit,
     *                                  on a non-200 answer, an unparseable body, a body without a durations table, a
     *                                  table of the wrong shape, a {@code null} cell (no route between that pair; the
     *                                  message names the pair), or a cell that is not a number, not finite, does not
     *                                  fit an {@code int} of milliseconds (about 24.8 days) or is negative
     */
    public int[][] getCostMatrix(List<Point> coordinates) throws RoutingException {
        String request = requestUrl("table", coordinates);

        byte[] response = this.sendRequest(request);
        Table table;
        try {
            table = readDurations(response, coordinates);
        } catch (JacksonException e) {
            throw new RoutingException("Cost matrix request failed", e);
        }
        if (table == null) {
            throw new RoutingException("Routing service response has no durations table");
        }
        if (table.error() != null) {
            throw table.error();
        }
        return table.matrix();
    }

    /**
     * Streams the body's root-level {@code durations} member into a table; {@code null} when there is none or the last
     * one is not an array (a body whose root is not an object has none). Like reading a tree, the whole document is
     * parsed, so a syntax error anywhere surfaces as {@link JacksonException}, as do trailing tokens after the root
     * value when the mapper enables {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}.
     */
    private Table readDurations(byte[] body, List<Point> coordinates) {
        Table table = null;
        try (JsonParser parser = this.mapper.createParser(body)) {
            JsonToken root = parser.nextToken();
            if (root == null) {
                return null;   // an empty body: no table
            }
            if (root == JsonToken.START_OBJECT) {
                while (next(parser) == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    JsonToken value = next(parser);
                    if ("durations".equals(name) && value == JsonToken.START_ARRAY) {
                        table = readTable(parser, coordinates);
                    } else {
                        if ("durations".equals(name)) {
                            table = null;   // the last occurrence counts, as in a tree
                        }
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
            if (this.mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) && parser.nextToken() != null) {
                throw new StreamReadException(parser, "Trailing token (" + parser.currentToken()
                        + ") found after the response value");
            }
        }
        return table;
    }

    /**
     * Reads the durations array the parser is positioned on (its {@code START_ARRAY}) up to its {@code END_ARRAY}.
     * Problems are recorded rather than thrown, so that a syntax error later in the body still takes precedence and
     * the row count is checked before any row's content. Rows and cells beyond the expected count are skipped, not
     * stored, as is everything after the first faulty row.
     */
    private Table readTable(JsonParser parser, List<Point> coordinates) {
        int expected = coordinates.size();
        int[][] matrix = new int[expected][];
        RoutingException firstFault = null;
        int rows = 0;
        for (JsonToken token = next(parser); token != JsonToken.END_ARRAY; token = next(parser)) {
            int i = rows++;
            if (firstFault != null || i >= expected) {
                parser.skipChildren();
            } else if (token != JsonToken.START_ARRAY) {
                parser.skipChildren();
                firstFault = new RoutingException("Routing service durations table row " + i + " is not an array");
            } else {
                firstFault = readRow(parser, coordinates, i, matrix);
            }
        }
        if (rows != expected) {
            return new Table(null, new RoutingException("Routing service durations table has " + rows + " rows for "
                    + expected + " coordinates"));
        }
        return firstFault != null ? new Table(null, firstFault) : new Table(matrix, null);
    }

    /**
     * Reads row {@code i} (the parser is on its {@code START_ARRAY}) into {@code matrix[i]} and returns {@code null},
     * or returns the row's fault: a wrong cell count before the first faulty cell.
     */
    private RoutingException readRow(JsonParser parser, List<Point> coordinates, int i, int[][] matrix) {
        int expected = coordinates.size();
        int[] row = new int[expected];
        RoutingException firstFault = null;
        int cells = 0;
        for (JsonToken token = next(parser); token != JsonToken.END_ARRAY; token = next(parser)) {
            int j = cells++;
            if (firstFault != null || j >= expected) {
                parser.skipChildren();
            } else if (token == JsonToken.VALUE_NULL) {
                firstFault = new RoutingException("Routing service reports no route from coordinate " + i + " ("
                        + describe(coordinates, i) + ") to coordinate " + j + " (" + describe(coordinates, j) + ")");
            } else if (token.isNumeric()) {
                try {
                    row[j] = toMilliseconds(parser.getDoubleValue(), i, j);
                } catch (RoutingException e) {
                    firstFault = e;
                }
            } else {
                JsonNode cell = parser.readValueAsTree();   // consumes the value; it is printed as JSON
                firstFault = new RoutingException("Routing service durations table cell " + i + "," + j
                        + " is not a number: " + cell);
            }
        }
        if (cells != expected) {
            return new RoutingException("Routing service durations table row " + i + " has " + cells + " cells for "
                    + expected + " coordinates");
        }
        if (firstFault == null) {
            matrix[i] = row;
        }
        return firstFault;
    }

    /**
     * Driving duration along {@code coordinates} in order, in milliseconds (OSRM seconds × 1000, rounded to the nearest
     * millisecond).
     *
     * @param coordinates the stops to drive through, in order; the list is not modified
     * @return the duration of the first route OSRM returns, in milliseconds
     * @throws NullPointerException     if {@code coordinates} is {@code null}
     * @throws IllegalArgumentException if a point is {@code null}, has no coordinates or has an invalid coordinate
     * @throws RoutingException         if the exchange fails, times out, is interrupted or exceeds the response limit,
     *                                  on a non-200 answer, an unparseable body or a body without a route or with a
     *                                  missing, {@code null}, non-numeric, non-finite, out-of-range ({@code long}
     *                                  milliseconds) or negative duration
     */
    public long getDuration(List<Point> coordinates) throws RoutingException {
        String request = requestUrl("route", coordinates);

        byte[] response = this.sendRequest(request);
        try {
            double millis = field(firstRoute(response), "duration").asDouble() * 1000;
            requireLongRange(millis, "duration");
            requireNonNegative(millis, "duration");
            return Math.round(millis);
        } catch (JacksonException e) {
            throw new RoutingException("Duration request failed", e);
        }
    }

    /**
     * Driving distance along {@code coordinates} in order, in metres as reported by OSRM, rounded to the nearest metre
     * like durations are rounded to the nearest millisecond (12.4 m becomes 12 m, 12.5 m and 12.9 m become 13 m).
     *
     * @param coordinates the stops to drive through, in order; the list is not modified
     * @return the distance of the first route OSRM returns, in whole metres
     * @throws NullPointerException     if {@code coordinates} is {@code null}
     * @throws IllegalArgumentException if a point is {@code null}, has no coordinates or has an invalid coordinate
     * @throws RoutingException         if the exchange fails, times out, is interrupted or exceeds the response limit,
     *                                  on a non-200 answer, an unparseable body or a body without a route or with a
     *                                  missing, {@code null}, non-numeric, non-finite, out-of-range ({@code long}) or
     *                                  negative distance
     */
    public long getDistance(List<Point> coordinates) throws RoutingException {
        String request = requestUrl("route", coordinates);

        byte[] response = this.sendRequest(request);
        try {
            double metres = field(firstRoute(response), "distance").asDouble();
            requireLongRange(metres, "distance");
            requireNonNegative(metres, "distance");
            return Math.round(metres);
        } catch (JacksonException e) {
            throw new RoutingException("Distance request failed", e);
        }
    }

    /**
     * The first element of the body's {@code routes} array.
     *
     * @throws RoutingException if the body carries no {@code routes} array or it is empty, instead of surfacing a
     *                          {@code NullPointerException} (OSRM error answers such as {@code NoRoute} arrive
     *                          with a non-200 status and are rejected earlier)
     */
    private JsonNode firstRoute(byte[] response) throws RoutingException {
        JsonNode routes = this.mapper.readTree(response).get("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) {
            throw new RoutingException("Routing service response contains no route");
        }
        return routes.get(0);
    }

    /**
     * Closes the owned {@link HttpClient} ({@link HttpClient#close()}: waits for requests in flight to complete);
     * no-op when the client was supplied by the caller. Calling it again has no further effect. Once the owned client
     * is closed, every further request fails with a {@link RoutingException}.
     */
    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    /**
     * Sends a GET request and returns the body of a 200 answer. The request's own timeout bounds the wait for the
     * response headers; waiting on the future with the same timeout bounds the whole exchange, body included, which
     * {@link HttpRequest#timeout()} alone does not. The body is read through a {@link LimitedBodySubscriber} unless
     * the response limit is switched off.
     */
    private byte[] sendRequest(String url) throws RoutingException {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(new URI(url)).GET();
            if (!requestTimeout.isZero()) {
                builder.timeout(requestTimeout);
            }
            request = builder.build();
        } catch (URISyntaxException e) {
            throw new RoutingException("Routing service request failed", e);
        }
        long limit = maxResponseBytes;
        HttpResponse.BodyHandler<byte[]> handler = limit == NO_RESPONSE_LIMIT
                ? HttpResponse.BodyHandlers.ofByteArray()
                : info -> new LimitedBodySubscriber(limit, info.headers().firstValueAsLong("Content-Length")
                        .orElse(-1));
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request, handler);
        HttpResponse<byte[]> response;
        try {
            response = requestTimeout.isZero() ? exchange.get()
                    : exchange.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            HttpTimeoutException timeout = new HttpTimeoutException("response not complete within " + requestTimeout);
            timeout.initCause(e);
            throw new RoutingException("Routing service request failed", timeout);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new RoutingException("Routing service request interrupted", e);
        } catch (ExecutionException e) {
            // Reported once, through the exception; logging it here as well would duplicate it in the caller's log.
            throw new RoutingException("Routing service request failed", unwrap(e));
        }
        if (response.statusCode() != 200) {
            if (log.isDebugEnabled()) {
                log.debug("Routing service response {}", new String(response.body(), StandardCharsets.UTF_8));
            }
            throw new RoutingException("Routing service returned status " + response.statusCode());
        }
        return response.body();
    }

    /**
     * The outcome of reading a durations table: the matrix, or the first problem found in it.
     */
    private record Table(int[][] matrix, RoutingException error) {
    }

    /**
     * Collects a response body into a byte array, failing as soon as it exceeds {@code limit} bytes: it counts the
     * bytes of every {@code onNext} and, once the count passes the limit (or right away, when the announced
     * {@code Content-Length} already does), cancels the subscription, drops what it has collected and completes its
     * body exceptionally with an {@link IOException} naming the limit. Reactive-streams signals arrive one at a time,
     * so the plain fields need no synchronization.
     */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private final long announced;
        private Flow.Subscription subscription;
        private long received;
        private boolean done;

        private LimitedBodySubscriber(long limit, long announced) {
            this.limit = limit;
            this.announced = announced;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (announced > limit) {
                fail("Routing service response announces " + announced + " bytes, more than the response limit of "
                        + limit + " bytes");
                return;
            }
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
            }
            if (received > limit) {
                fail("Routing service response exceeds the response limit of " + limit + " bytes");
                return;
            }
            delegate.onNext(items);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!done) {
                done = true;
                delegate.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }

        private void fail(String message) {
            done = true;
            subscription.cancel();
            delegate.onError(new IOException(message));
        }
    }

}
