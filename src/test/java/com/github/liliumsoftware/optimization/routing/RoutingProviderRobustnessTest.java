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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins how {@link RoutingProvider} fails: constructor validation of the base URL, the mapper, the client and the
 * timeout; a request timeout that bounds the whole exchange including a stalled body; refused connections,
 * interrupted callers and unfollowed redirects; and response values (non-finite, out-of-range or negative numbers,
 * tables of the wrong shape, geometries of the wrong type) that must surface as {@link RoutingException} instead of a
 * wrong cost.
 */
class RoutingProviderRobustnessTest {

    private static final List<Point> TWO_POINTS = List.of(new Point(4.4, 52.2), new Point(4.9, 52.4));

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> location = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    /**
     * Released when a test ends, so a handler stalling on it never outlives the test.
     */
    private final CountDownLatch release = new CountDownLatch(1);

    private HttpServer server;
    private RoutingProvider provider;

    @BeforeEach
    void startFakeOsrm() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (location.get() != null) {
                exchange.getResponseHeaders().add("Location", location.get());
            }
            byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/stall", this::stallAfterHalfTheBody);
        server.start();
        provider = new RoutingProvider(baseUrl(), mapper);
    }

    @AfterEach
    void stopFakeOsrm() {
        release.countDown();
        provider.close();
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Sends a 200 with half a body, then stalls (up to 1.5 s, or until the test ends) before finishing it.
     */
    private void stallAfterHalfTheBody(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);   // 0: chunked, the length is not announced
        try (OutputStream out = exchange.getResponseBody()) {
            out.write("{\"routes\":[{\"dur".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                release.await(1_500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.write("ation\":1}]}".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // the client gave up and closed the connection: exactly what the test expects
        }
    }

    // ---------------------------------------------------------------- construction

    @ParameterizedTest
    @ValueSource(strings = {"localhost:5000", "ftp://h", "http:///x", "mailto:osrm@example.com", "/relative/path",
            "http://exa mple.com"})
    // every constructor call below must throw before a provider exists
    @SuppressWarnings("resource")
    void aBaseUrlNoRequestCouldBeSentToIsRejectedByEveryConstructor(String url) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> new RoutingProvider(url, mapper)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(url);
            assertThatThrownBy(() -> new RoutingProvider(url, mapper, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RoutingProvider(url, mapper, client))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RoutingProvider(url, mapper, client, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aSyntacticallyInvalidBaseUrlKeepsTheParserErrorAsCause() {
        assertThatThrownBy(() -> new RoutingProvider("http://exa mple.com", mapper).close())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid URL")
                .hasCauseInstanceOf(URISyntaxException.class);
    }

    @Test
    void httpAndHttpsBaseUrlsAreAcceptedInAnyCase() {
        for (String url : List.of("http://h", "https://h:8443/osrm", "HTTP://H", "HttpS://h")) {
            try (RoutingProvider accepted = new RoutingProvider(url, mapper)) {
                assertThat(accepted).isNotNull();
            }
        }
    }

    @Test
    // every constructor call below must throw before a provider exists
    @SuppressWarnings("resource")
    void nullArgumentsAreRejectedWithNullPointerException() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> new RoutingProvider(null, mapper)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("mapper");
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), null, Duration.ofSeconds(1)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, (HttpClient) null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("client");
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, null, Duration.ofSeconds(1)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("client");
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, client, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("requestTimeout");
        }
    }

    @Test
    // every constructor call below must throw before a provider exists
    @SuppressWarnings("resource")
    void aNegativeTimeoutIsRejectedEvenByOneNanosecond() {
        assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    // ---------------------------------------------------------------- transport failures

    @Test
    void theRequestTimeoutAlsoBoundsABodyThatStallsAfterTheHeaders() {
        // The headers arrive at once, so HttpRequest.timeout() alone would be satisfied and the caller would wait
        // for the whole 1.5 s stall; the provider's bound covers the exchange up to the last byte of the body.
        try (RoutingProvider bounded = new RoutingProvider(baseUrl() + "/stall", mapper, Duration.ofMillis(200))) {
            long start = System.nanoTime();

            assertThatThrownBy(() -> bounded.getDuration(TWO_POINTS))
                    .isInstanceOf(RoutingException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_200));
        }
    }

    @Test
    void aRefusedConnectionIsARoutingExceptionCausedByTheIoFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (RoutingProvider refused = new RoutingProvider("http://127.0.0.1:" + closedPort, mapper)) {
            assertThatThrownBy(() -> refused.getCostMatrix(TWO_POINTS))
                    .isInstanceOf(RoutingException.class)
                    .hasMessageContaining("failed")
                    .cause().isInstanceOf(IOException.class);
        }
    }

    @Test
    // Thread.interrupted() is called for its side effect
    @SuppressWarnings("ResultOfMethodCallIgnored")
    void anInterruptedCallerGetsARoutingExceptionAndKeepsItsInterruptFlag() {
        responseBody.set("{\"routes\":[{\"duration\":1}]}");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> provider.getDuration(TWO_POINTS))
                    .isInstanceOf(RoutingException.class)
                    .hasMessageContaining("interrupted")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag restored").isTrue();
        } finally {
            Thread.interrupted();   // clear it so later tests on this thread are unaffected
        }
        assertThat(provider.getDuration(TWO_POINTS)).as("the provider still works afterwards").isEqualTo(1000);
    }

    @Test
    void aRedirectIsNotFollowed() {
        responseStatus.set(302);
        location.set(baseUrl() + "/elsewhere");
        responseBody.set("");

        assertThatThrownBy(() -> provider.getDistance(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("302");
        assertThat(requests.get()).as("only the original request was sent").isEqualTo(1);
    }

    // ---------------------------------------------------------------- response values

    @ParameterizedTest
    @ValueSource(strings = {"1e400", "-1e400", "1e300", "9.3e15"})
    void aDurationThatCannotBeLongMillisecondsIsRejected(String seconds) {
        // 1e400 parses to Infinity; 1e300 s and 9.3e15 s are finite but beyond Long.MAX_VALUE (~9.22e18) once
        // multiplied by 1000, so they used to saturate to Long.MAX_VALUE.
        responseBody.set("{\"routes\":[{\"duration\":" + seconds + "}]}");

        assertThatThrownBy(() -> provider.getDuration(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("duration")
                .hasMessageContaining("out of range");
    }

    @Test
    void theLargestDurationThatFitsALongIsReturned() {
        // 9.2e15 s = 9.2e18 ms < Long.MAX_VALUE (9.223e18).
        responseBody.set("{\"routes\":[{\"duration\":9.2e15}]}");

        assertThat(provider.getDuration(TWO_POINTS)).isEqualTo(9_200_000_000_000_000_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e300", "1e400", "-1e19"})
    void aDistanceThatCannotBeALongIsRejected(String metres) {
        responseBody.set("{\"routes\":[{\"distance\":" + metres + "}]}");

        assertThatThrownBy(() -> provider.getDistance(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("distance")
                .hasMessageContaining("out of range");
    }

    @Test
    void aNonNumericDurationOrDistanceIsRejected() {
        responseBody.set("{\"routes\":[{\"duration\":\"12\",\"distance\":true}]}");

        assertThatThrownBy(() -> provider.getDuration(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("duration is not a number");
        assertThatThrownBy(() -> provider.getDistance(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("distance is not a number");
    }

    @Test
    void routesThatAreNotAnArrayCountAsNoRoute() {
        responseBody.set("{\"routes\":{\"duration\":1}}");

        assertThatThrownBy(() -> provider.getDuration(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("no route");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e400", "-1e400", "2147483.648", "-2147483.649", "1e10"})
    void aTableCellThatDoesNotFitAnIntOfMillisecondsIsRejectedNamingThePair(String seconds) {
        // 2 147 483.648 s rounds to 2 147 483 648 ms = Integer.MAX_VALUE + 1; -2 147 483.649 s to MIN_VALUE - 1.
        responseBody.set("{\"durations\":[[0,1],[" + seconds + ",0]]}");

        assertThatThrownBy(() -> provider.getCostMatrix(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("from coordinate 1 to coordinate 0")
                .hasMessageContaining("does not fit an int");
    }

    @Test
    void tableCellsAtTheUpperIntBoundaryAndNegativeZeroAreKept() {
        // 2 147 483.647 s = Integer.MAX_VALUE ms; -0.0 s is not negative and becomes 0 ms.
        responseBody.set("{\"durations\":[[0,2147483.647],[-0.0,0]]}");

        int[][] matrix = provider.getCostMatrix(TWO_POINTS);

        assertThat(matrix[0]).containsExactly(0, Integer.MAX_VALUE);
        assertThat(matrix[1]).containsExactly(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.0001", "-2147483.648"})
    void aNegativeTableCellIsRejectedNamingThePair(String seconds) {
        // Negative cells used to be kept (-2 147 483.648 s even became Integer.MIN_VALUE ms), so the search read them
        // as savings; -0.0001 s would round to 0 ms but is still a negative answer.
        responseBody.set("{\"durations\":[[0,1],[" + seconds + ",0]]}");

        assertThatThrownBy(() -> provider.getCostMatrix(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("from coordinate 1 to coordinate 0")
                .hasMessageContaining("is negative");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1.5", "-0.0001", "-9.2e15"})
    void aNegativeDurationOrDistanceIsRejected(String value) {
        responseBody.set("{\"routes\":[{\"duration\":" + value + ",\"distance\":" + value + "}]}");

        assertThatThrownBy(() -> provider.getDuration(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("duration is negative");
        assertThatThrownBy(() -> provider.getDistance(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("distance is negative");
    }

    @Test
    void aNegativeZeroDurationOrDistanceIsZero() {
        responseBody.set("{\"routes\":[{\"duration\":-0.0,\"distance\":-0.0}]}");

        assertThat(provider.getDuration(TWO_POINTS)).isZero();
        assertThat(provider.getDistance(TWO_POINTS)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"durations\":[[0]]}", "{\"durations\":[[]]}", "{\"durations\":[[0],[0,1]]}"})
    void aNonEmptyTableForNoCoordinatesIsRejected(String body) {
        responseBody.set(body);

        assertThatThrownBy(() -> provider.getCostMatrix(List.of()))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("for 0 coordinates");
    }

    @Test
    void aTableWithoutArraysWhereArraysBelongIsRejected() {
        responseBody.set("{\"durations\":5}");
        assertThatThrownBy(() -> provider.getCostMatrix(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("no durations table");

        responseBody.set("{\"durations\":[[0,1],7]}");
        assertThatThrownBy(() -> provider.getCostMatrix(TWO_POINTS))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("row 1 is not an array");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"type\":\"Polygon\",\"coordinates\":[[[1,2],[3,4],[1,2]]]}",
            "{\"type\":\"Point\",\"coordinates\":[1,2]}", "{\"coordinates\":[[1,2]]}", "\"LineString\""})
    void aRouteGeometryThatIsNotALineStringIsRejected(String geometry) {
        responseBody.set("{\"routes\":[{\"geometry\":" + geometry + "}]}");

        assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0)))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("Routing request failed");
    }

    @Test
    void aRouteWithoutAGeometryKeyIsRejected() {
        responseBody.set("{\"routes\":[{\"duration\":1}]}");

        assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0)))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("no route geometry");
    }

    @Test
    void aNullPointInTheListIsRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.getCostMatrix(Arrays.asList(new Point(1.0, 2.0), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Point 1 has no coordinates");
        assertThatThrownBy(() -> provider.getRoute(null, new Point(1.0, 2.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Point 0");
        assertThat(requests.get()).isZero();
    }

}
