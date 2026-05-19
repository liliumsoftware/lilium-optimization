package com.github.liliumsoftware.optimization.routing;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Thin HTTP client for an OSRM routing server. Exposes the route geometry ({@code /route/v1}), the all-pairs
 * duration matrix ({@code /table/v1}), and single-trip duration/distance. OSRM returns durations in seconds;
 * this client converts them to integer <strong>milliseconds</strong> (×1000, rounded to the nearest millisecond) to
 * match the library's integral cost model, and formats coordinates as {@code lon,lat} via a per-thread
 * {@link DecimalFormat}.
 *
 * <p>Any non-200 response, unparseable body, or body without the expected {@code routes}/{@code durations}
 * content (the <a href="https://project-osrm.org/docs/v5.24.0/api/">OSRM HTTP API</a> shape) surfaces as
 * {@link RoutingException}; so does a {@code null} duration or distance and a {@code null} cell of the duration table
 * (OSRM's answer for a pair it cannot route between), whose message names the pair. Failures are reported through the
 * exception only, they are not logged as errors. All calls use the {@code driving} profile. Coordinates are always
 * formatted with a {@code '.'} decimal separator regardless of the default locale.
 *
 * <p><strong>Input validation.</strong> Every coordinate is checked before a request is built: a {@link Point} without
 * coordinates, a non-finite longitude or latitude, a longitude outside {@code [-180, 180]} or a latitude outside
 * {@code [-90, 90]} is rejected with {@link IllegalArgumentException} (a problem with the caller's input, not with the
 * routing service), so the formatter's three integer digits can never truncate a bad value into a plausible one.
 *
 * <p><strong>Thread-safety and lifecycle.</strong> One {@link HttpClient} is shared for the provider's lifetime
 * (connection reuse, no per-call thread pools) and {@link HttpClient} is thread-safe, so a single instance may be
 * used from any number of threads. {@link #close()} releases the client; it is only required when the provider
 * owns the client (default constructor), and is a no-op for a client supplied by the caller.
 *
 * <p><strong>Timeouts.</strong> Every request is bounded by {@link #DEFAULT_REQUEST_TIMEOUT} (30 seconds) unless a
 * request timeout is passed to the constructor; {@link #NO_REQUEST_TIMEOUT} switches the bound off, so a request then
 * waits as long as the server takes. A server that does not answer in time surfaces as a {@link RoutingException}
 * caused by {@link java.net.http.HttpTimeoutException}. A connect timeout belongs to the {@link HttpClient}, so
 * configure it on a client you supply.
 */
@Slf4j
public class RoutingProvider implements AutoCloseable {

    /**
     * The request timeout of the constructors that take none: long enough for a large {@code /table} request, short
     * enough that a server that never answers cannot block a planning run forever.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Pass as the request timeout to wait for every answer without a bound.
     */
    public static final Duration NO_REQUEST_TIMEOUT = Duration.ZERO;
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

    /**
     * Creates a provider owning a default {@link HttpClient} whose requests each time out after
     * {@link #DEFAULT_REQUEST_TIMEOUT}; call {@link #close()} when done with it.
     *
     * @param routingServiceUrl base URL of the OSRM server, without a trailing slash
     * @param mapper            Jackson mapper used to parse responses
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper) {
        this(routingServiceUrl, mapper, HttpClient.newHttpClient(), true, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Creates a provider owning a default {@link HttpClient} whose requests each time out after
     * {@code requestTimeout}; call {@link #close()} when done with it.
     *
     * @param routingServiceUrl base URL of the OSRM server, without a trailing slash
     * @param mapper            Jackson mapper used to parse responses
     * @param requestTimeout    longest time to wait for each response, or {@link #NO_REQUEST_TIMEOUT} for no bound
     * @throws IllegalArgumentException if {@code requestTimeout} is negative
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, Duration requestTimeout) {
        this(routingServiceUrl, mapper, HttpClient.newHttpClient(), true, nonNegative(requestTimeout));
    }

    /**
     * Creates a provider that sends requests through the caller's {@link HttpClient} (connect timeout, proxy,
     * executor configured there), each timing out after {@link #DEFAULT_REQUEST_TIMEOUT}. The caller keeps ownership
     * of the client; {@link #close()} will not close it.
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client) {
        this(routingServiceUrl, mapper, client, false, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Like {@link #RoutingProvider(String, JsonMapper, HttpClient)}, with every request timing out after
     * {@code requestTimeout} ({@link #NO_REQUEST_TIMEOUT} for no bound).
     *
     * @throws IllegalArgumentException if {@code requestTimeout} is negative
     */
    public RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client, Duration requestTimeout) {
        this(routingServiceUrl, mapper, client, false, nonNegative(requestTimeout));
    }

    private RoutingProvider(String routingServiceUrl, JsonMapper mapper, HttpClient client, boolean ownsClient,
            Duration requestTimeout) {
        this.routingServiceUrl = Objects.requireNonNull(routingServiceUrl, "routingServiceUrl");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.requestTimeout = requestTimeout;
    }

    private static Duration nonNegative(Duration requestTimeout) {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must not be negative, was " + requestTimeout);
        }
        return requestTimeout;
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
     * @throws RoutingException if the value does not fit a 32-bit matrix cell (about 24.8 days)
     */
    private static int toMilliseconds(double seconds, int row, int column) {
        long millis = Math.round(seconds * 1000);
        if (millis < Integer.MIN_VALUE || millis > Integer.MAX_VALUE) {
            throw new RoutingException("Routing service duration from coordinate " + row + " to coordinate " + column
                    + " (" + seconds + " s) does not fit an int of milliseconds");
        }
        return (int) millis;
    }

    private static String describe(List<Point> coordinates, int index) {
        return index < coordinates.size() ? lonLat(coordinates.get(index), index) : "unknown";
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
     * The driving route geometry from {@code start} to {@code end} ({@code /route/v1/driving}, full GeoJSON overview).
     *
     * @throws IllegalArgumentException if a point is missing its coordinates or they are invalid
     * @throws RoutingException         on a non-200 answer, an unparseable body or a body without a route geometry
     */
    public LineString getRoute(Point start, Point end) throws RoutingException {
        String request = requestUrl("route", Arrays.asList(start, end)) + "?geometries=geojson&overview=full";

        String response = this.sendRequest(request);
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
     * ({@code /table/v1/driving/lon,lat;…}).
     *
     * @throws IllegalArgumentException if a point is missing its coordinates or they are invalid
     * @throws RoutingException         on a non-200 answer, an unparseable body, a body without a durations table, or a
     *                                  {@code null} cell (no route between that pair), naming the pair
     */
    public int[][] getCostMatrix(List<Point> coordinates) throws RoutingException {
        String request = requestUrl("table", coordinates);

        String response = this.sendRequest(request);
        try {
            JsonNode durations = this.mapper.readTree(response).get("durations");
            if (durations == null || !durations.isArray()) {
                throw new RoutingException("Routing service response has no durations table");
            }

            int expected = coordinates.size();
            if (!coordinates.isEmpty() && durations.size() != expected) {
                throw new RoutingException("Routing service durations table has " + durations.size() + " rows for "
                        + expected + " coordinates");
            }
            int[][] distanceMatrix = new int[durations.size()][];
            for (int i = 0; i < durations.size(); i++) {
                JsonNode row = durations.get(i);
                if (row == null || !row.isArray()) {
                    throw new RoutingException("Routing service durations table row " + i + " is not an array");
                }
                if (!coordinates.isEmpty() && row.size() != expected) {
                    throw new RoutingException("Routing service durations table row " + i + " has " + row.size()
                            + " cells for " + expected + " coordinates");
                }
                distanceMatrix[i] = new int[row.size()];
                for (int j = 0; j < row.size(); j++) {
                    JsonNode cell = row.get(j);
                    if (cell == null || cell.isNull()) {
                        throw new RoutingException("Routing service reports no route from coordinate " + i + " ("
                                + describe(coordinates, i) + ") to coordinate " + j + " (" + describe(coordinates, j)
                                + ")");
                    }
                    if (!cell.isNumber()) {
                        throw new RoutingException("Routing service durations table cell " + i + "," + j
                                + " is not a number: " + cell);
                    }
                    distanceMatrix[i][j] = toMilliseconds(cell.asDouble(), i, j);
                }
            }
            return distanceMatrix;
        } catch (JacksonException e) {
            throw new RoutingException("Cost matrix request failed", e);
        }
    }

    /**
     * Driving duration along {@code coordinates} in order, in milliseconds (OSRM seconds × 1000, rounded to the nearest
     * millisecond).
     *
     * @throws IllegalArgumentException if a point is missing its coordinates or they are invalid
     * @throws RoutingException         on a non-200 answer, an unparseable body or a body without a route or with a
     *                                  missing, {@code null} or non-numeric duration
     */
    public long getDuration(List<Point> coordinates) throws RoutingException {
        String request = requestUrl("route", coordinates);

        String response = this.sendRequest(request);
        try {
            return Math.round(field(firstRoute(response), "duration").asDouble() * 1000);
        } catch (JacksonException e) {
            throw new RoutingException("Duration request failed", e);
        }
    }

    /**
     * Driving distance along {@code coordinates} in order, in metres as reported by OSRM (fractions truncated).
     *
     * @throws IllegalArgumentException if a point is missing its coordinates or they are invalid
     * @throws RoutingException         on a non-200 answer, an unparseable body or a body without a route or with a
     *                                  missing, {@code null} or non-numeric distance
     */
    public long getDistance(List<Point> coordinates) throws RoutingException {
        String request = requestUrl("route", coordinates);

        String response = this.sendRequest(request);
        try {
            return field(firstRoute(response), "distance").asLong();
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
    private JsonNode firstRoute(String response) throws RoutingException {
        JsonNode routes = this.mapper.readTree(response).get("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) {
            throw new RoutingException("Routing service response contains no route");
        }
        return routes.get(0);
    }

    /**
     * Closes the owned {@link HttpClient}; no-op when the client was supplied by the caller.
     */
    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    private String sendRequest(String url) throws RoutingException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(new URI(url)).GET();
            if (!requestTimeout.isZero()) {
                builder.timeout(requestTimeout);
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Routing service response {}", response.body());
                throw new RoutingException("Routing service returned status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoutingException("Routing service request interrupted", e);
        } catch (IOException | URISyntaxException e) {
            // Reported once, through the exception; logging it here as well would duplicate it in the caller's log.
            throw new RoutingException("Routing service request failed", e);
        }
    }

}
