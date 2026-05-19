package com.github.liliumsoftware.optimization.routing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RoutingProvider} is the only place in the library where an external unit system is translated into
 * the internal one, so two contracts are load-bearing and easy to break silently: OSRM reports durations in
 * <em>seconds</em> and they must arrive as integral <em>milliseconds</em> (while distances stay raw metres),
 * and coordinates must be emitted as {@code longitude,latitude} — the classic swap that produces plausible
 * but completely wrong routes. Everything is exercised against a loopback {@link HttpServer} standing in for
 * OSRM, so the tests stay hermetic and deterministic.
 */
class RoutingProviderTest {

    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    private final AtomicReference<URI> requestedUri = new AtomicReference<>();
    private final List<String> requestedPaths = Collections.synchronizedList(new ArrayList<>());

    private HttpServer server;
    private RoutingProvider provider;

    /**
     * {@code 10.0 -> "10"}, {@code 10.125 -> "10.125"}: the provider's coordinate format, written out independently.
     */
    private static String plain(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    @BeforeEach
    void startFakeOsrm() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI());
            requestedPaths.add(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(responseDelayMillis.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        provider = new RoutingProvider("http://127.0.0.1:" + server.getAddress().getPort(), JsonMapper.builder()
                .build());
    }

    @AfterEach
    void stopFakeOsrm() {
        provider.close();
        server.stop(0);
    }

    @Test
    void costMatrixConvertsOsrmSecondsToMilliseconds() {
        responseBody.set("{\"durations\":[[0,1.5],[2.25,0]]}");

        int[][] matrix = provider.getCostMatrix(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)));

        // 1.5 s -> 1500 ms, 2.25 s -> 2250 ms; row/column order mirrors the coordinate list.
        assertThat(matrix).hasDimensions(2, 2);
        assertThat(matrix[0]).containsExactly(0, 1500);
        assertThat(matrix[1]).containsExactly(2250, 0);
    }

    @Test
    void costMatrixRoundsToTheNearestMillisecond() {
        // seconds × 1000 used to be truncated, and floating-point error made exact values one short: 32.3 s became
        // 32 299 ms. Rounding gives 32 300; 1.9995 s (1999.5 ms) rounds half up to 2000, 0.0019 s to 2.
        responseBody.set("{\"durations\":[[1.9995,0.0019],[32.3,2.01]]}");

        int[][] matrix = provider.getCostMatrix(List.of(new Point(1.0, 2.0), new Point(3.0, 4.0)));

        assertThat(matrix[0]).containsExactly(2000, 2);
        assertThat(matrix[1]).containsExactly(32300, 2010);
    }

    @Test
    void durationRoundsToTheNearestMillisecond() {
        responseBody.set("{\"routes\":[{\"duration\":32.3}]}");

        assertThat(provider.getDuration(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)))).isEqualTo(32300L);
    }

    @Test
    void costMatrixRequestUsesTablePathAndLongitudeLatitudeOrder() {
        responseBody.set("{\"durations\":[[0,0],[0,0]]}");

        provider.getCostMatrix(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)));

        assertThat(requestedUri.get().getPath()).isEqualTo("/table/v1/driving/4.4,52.2;4.9,52.4");
    }

    @Test
    void emptyCoordinateListStripsTheTrailingSlashFromTheTablePath() {
        // An empty coordinate list still sends the request, as the bare profile path "/table/v1/driving"; the
        // server's answer decides, instead of the client failing locally.
        responseBody.set("{\"durations\":[]}");

        int[][] matrix = provider.getCostMatrix(List.of());

        assertThat(requestedUri.get().getPath()).isEqualTo("/table/v1/driving");
        assertThat(matrix).isEmpty();
    }

    @Test
    void anEmptyCoordinateListGivesTheSamePathShapeForEveryCall() {
        // Every call builds its path the same way: an empty coordinate list gives the bare profile path, never a
        // trailing slash.
        responseBody.set("{\"routes\":[{\"duration\":1,\"distance\":2}]}");

        provider.getDuration(List.of());
        assertThat(requestedUri.get().getPath()).isEqualTo("/route/v1/driving");
        provider.getDistance(List.of());
        assertThat(requestedUri.get().getPath()).isEqualTo("/route/v1/driving");
        responseBody.set("{\"durations\":[]}");
        provider.getCostMatrix(List.of());
        assertThat(requestedUri.get().getPath()).isEqualTo("/table/v1/driving");
    }

    @Test
    void durationConvertsOsrmSecondsToMilliseconds() {
        responseBody.set("{\"routes\":[{\"duration\":12.5}]}");

        long duration = provider.getDuration(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)));

        assertThat(duration).isEqualTo(12500L);
    }

    @Test
    void durationRequestUsesDrivingProfileAndLongitudeLatitudeOrder() {
        responseBody.set("{\"routes\":[{\"duration\":1}]}");

        provider.getDuration(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)));

        assertThat(requestedUri.get().getPath()).isEqualTo("/route/v1/driving/4.4,52.2;4.9,52.4");
    }

    @Test
    void distancePassesMetresThroughUnscaled() {
        responseBody.set("{\"routes\":[{\"distance\":345}]}");

        long distance = provider.getDistance(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)));

        assertThat(distance).isEqualTo(345L);
    }

    @Test
    void fractionalDistanceMetresAreTruncated() {
        responseBody.set("{\"routes\":[{\"distance\":345.9}]}");

        long distance = provider.getDistance(List.of(new Point(4.4, 52.2)));

        assertThat(distance).isEqualTo(345L);
    }

    @Test
    void distanceRequestUsesDrivingProfileAndLongitudeLatitudeOrder() {
        responseBody.set("{\"routes\":[{\"distance\":1}]}");

        provider.getDistance(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)));

        assertThat(requestedUri.get().getPath()).isEqualTo("/route/v1/driving/4.4,52.2;4.9,52.4");
    }

    @Test
    void routeGeometryIsDeserializedIntoALineString() {
        responseBody.set("{\"routes\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[1,2],[3,4]]}}]}");

        LineString route = provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0));

        assertThat(route).isExactlyInstanceOf(LineString.class);
        assertThat(route.getCoordinates()).containsExactly(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0));
    }

    @Test
    void routeRequestUsesTheDrivingProfileAndGeoJsonOverview() {
        // The route used to ask for the "car" profile while every other call uses "driving", and requested turn-by-turn
        // steps it never read.
        responseBody.set("{\"routes\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[1,2]]}}]}");

        provider.getRoute(new Point(4.4, 52.2), new Point(4.9, 52.4));

        assertThat(requestedUri.get().getPath()).isEqualTo("/route/v1/driving/4.4,52.2;4.9,52.4");
        assertThat(requestedUri.get().getQuery()).isEqualTo("geometries=geojson&overview=full");
    }

    @Test
    void nonOkStatusBecomesRoutingExceptionMentioningTheStatus() {
        responseStatus.set(500);
        responseBody.set("upstream exploded");

        assertThatThrownBy(() -> provider.getCostMatrix(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> provider.getDuration(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> provider.getDistance(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0)))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("500");
    }

    @Test
    void unparseableBodyBecomesRoutingException() {
        responseBody.set("<html><body>Bad Gateway</body></html>");

        assertThatThrownBy(() -> provider.getCostMatrix(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("Cost matrix request failed");
        assertThatThrownBy(() -> provider.getDuration(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("Duration request failed");
        assertThatThrownBy(() -> provider.getDistance(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("Distance request failed");
        assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0)))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("Routing request failed");
    }

    @Test
    void coordinatesUseADotDecimalSeparatorWhateverTheDefaultLocaleIs() {
        // Regression: the coordinate formatter used the JVM default locale, so under a comma-decimal locale the
        // request became /route/v1/driving/4,5,52,25 - a well-formed URL asking OSRM for the wrong place.
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            responseBody.set("{\"routes\":[{\"duration\":1}]}");

            provider.getDuration(List.of(new Point(4.5, 52.25), new Point(-0.125, 51.5)));

            assertThat(requestedUri.get().getPath()).isEqualTo("/route/v1/driving/4.5,52.25;-0.125,51.5");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void aRequestTimeoutBoundsTheWaitForASlowServer() {
        responseBody.set("{\"routes\":[{\"duration\":1}]}");
        responseDelayMillis.set(1_500);
        try (RoutingProvider bounded = new RoutingProvider(baseUrl(), JsonMapper.builder().build(),
                Duration.ofMillis(200))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> bounded.getDuration(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4))))
                    .isInstanceOf(RoutingException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_200));
        }
    }

    @Test
    void aSlowAnswerWithinTheDefaultTimeoutIsAwaited() {
        responseBody.set("{\"routes\":[{\"duration\":1}]}");
        responseDelayMillis.set(300);

        assertThat(provider.getDuration(List.of(new Point(4.4, 52.2), new Point(4.9, 52.4)))).isEqualTo(1000);
    }

    @Test
    void theConstructorsWithoutATimeoutBoundEveryRequestByTheDefaultTimeout() {
        // Without a timeout a request against a server that never answers blocked forever.
        responseBody.set("{\"routes\":[{\"distance\":7}]}");
        try (HttpClient own = HttpClient.newHttpClient()) {
            RecordingClient recording = new RecordingClient(own);
            new RoutingProvider(baseUrl(), JsonMapper.builder().build(), recording).getDistance(List.of(new Point(1.0,
                    2.0)));
            new RoutingProvider(baseUrl(), JsonMapper.builder().build(), recording, Duration.ofSeconds(5))
                    .getDistance(List.of(new Point(1.0, 2.0)));

            assertThat(RoutingProvider.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
            assertThat(recording.timeouts).containsExactly(Optional.of(Duration.ofSeconds(30)),
                    Optional.of(Duration.ofSeconds(5)));
        }
    }

    @Test
    void invalidCoordinatesAreRejectedBeforeAnyRequestIsSent() {
        // NaN and infinity were formatted into the URL as "NaN" / "∞", and a longitude of 1234.5 was silently
        // truncated to 234.5 by the three-integer-digit formatter.
        responseBody.set("{\"durations\":[[0,0],[0,0]]}");
        List<double[]> invalid = List.of(new double[] {Double.NaN, 35.7}, new double[] {Double.POSITIVE_INFINITY, 35.7},
                new double[] {51.4, Double.NEGATIVE_INFINITY}, new double[] {1234.5, 35.7}, new double[] {180.5, 35.7},
                new double[] {-180.5, 35.7}, new double[] {51.4, 90.5}, new double[] {51.4, -90.5});
        for (double[] lonLat : invalid) {
            Point point = new Point(lonLat[0], lonLat[1]);
            assertThatThrownBy(() -> provider.getCostMatrix(List.of(point)))
                    .as("lon %s, lat %s", lonLat[0], lonLat[1])
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("coordinate");
            assertThatThrownBy(() -> provider.getDuration(List.of(point)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getDistance(List.of(new Point(1.0, 2.0), point)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), point))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requestedUri.get()).as("nothing was sent").isNull();

        provider.getCostMatrix(List.of(new Point(180, 90), new Point(-180, -90)));
        assertThat(requestedUri.get().getPath()).isEqualTo("/table/v1/driving/180,90;-180,-90");
    }

    @Test
    void aPointWithoutCoordinatesIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> provider.getCostMatrix(List.of(new Point(1.0, 2.0), new Point())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no coordinates")
                .hasMessageContaining("1");
        assertThatThrownBy(() -> provider.getRoute(new Point(), new Point(1.0, 2.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no coordinates");
    }

    @Test
    void aNullDurationOrDistanceBecomesRoutingExceptionRatherThanZero() {
        responseBody.set("{\"routes\":[{\"duration\":null,\"distance\":null}]}");

        assertThatThrownBy(() -> provider.getDuration(List.of(new Point(1.0, 2.0), new Point(3.0, 4.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("duration");
        assertThatThrownBy(() -> provider.getDistance(List.of(new Point(1.0, 2.0), new Point(3.0, 4.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("distance");
    }

    @Test
    void anUnreachablePairInTheTableBecomesRoutingExceptionNamingThePair() {
        // OSRM reports a pair it cannot route between as null.
        responseBody.set("{\"code\":\"Ok\",\"durations\":[[0,12.5],[null,0]]}");

        assertThatThrownBy(() -> provider.getCostMatrix(List.of(new Point(51.4, 35.7), new Point(51.5, 35.8))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("from coordinate 1")
                .hasMessageContaining("to coordinate 0")
                .hasMessageContaining("51.5,35.8");
    }

    @Test
    // every constructor call below must throw before a provider exists
    @SuppressWarnings("resource")
    void aNegativeRequestTimeoutIsRejectedAndZeroSwitchesTheBoundOff() {
        JsonMapper mapper = JsonMapper.builder().build();
        assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        try (HttpClient own = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, own, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, (Duration) null))
                .isInstanceOf(NullPointerException.class);
        assertThat(RoutingProvider.NO_REQUEST_TIMEOUT).isEqualTo(Duration.ZERO);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aNullRouteGeometryIsARoutingExceptionNotANullResult() {
        responseBody.set("{\"routes\":[{\"geometry\":null}]}");

        assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0)))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("geometry");
    }

    @Test
    void aDurationsTableOfTheWrongShapeIsRejected() {
        List<Point> points = List.of(new Point(4.4, 52.2), new Point(4.9, 52.4));

        responseBody.set("{\"durations\":[[0,1],[2]]}");
        assertThatThrownBy(() -> provider.getCostMatrix(points)).isInstanceOf(RoutingException.class)
                .hasMessageContaining("row 1 has 1 cells for 2 coordinates");
        responseBody.set("{\"durations\":[[0,1]]}");
        assertThatThrownBy(() -> provider.getCostMatrix(points)).isInstanceOf(RoutingException.class)
                .hasMessageContaining("1 rows for 2 coordinates");
        responseBody.set("{\"durations\":[[0,\"x\"],[1,0]]}");
        assertThatThrownBy(() -> provider.getCostMatrix(points)).isInstanceOf(RoutingException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void noRequestTimeoutSwitchesTheBoundOffAndWaitsForASlowServer() {
        responseBody.set("{\"routes\":[{\"distance\":7}]}");
        responseDelayMillis.set(300);
        try (HttpClient own = HttpClient.newHttpClient()) {
            RecordingClient recording = new RecordingClient(own);
            RoutingProvider unbounded = new RoutingProvider(baseUrl(), JsonMapper.builder().build(), recording,
                    RoutingProvider.NO_REQUEST_TIMEOUT);

            assertThat(unbounded.getDistance(List.of(new Point(1.0, 2.0)))).isEqualTo(7);
            assertThat(recording.timeouts).containsExactly(Optional.empty());
        }
    }

    @Test
    void coordinateFormattingIsSafeAcrossThreads() throws Exception {
        // Regression: a single shared DecimalFormat is not thread-safe; parallel zone groups call the provider.
        // Every request the fake server received must be one of the exact expected URLs, each sent exactly as often
        // as its thread sent it; a garbled coordinate or a lost request fails the comparison.
        responseBody.set("{\"routes\":[{\"duration\":1}]}");
        requestedPaths.clear();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<String> expected = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double lon = 10 + i * 0.125;
            String path = "/route/v1/driving/" + plain(lon) + ",50.5;" + plain(lon + 1) + ",51.5";
            for (int n = 0; n < 20; n++) {
                expected.add(path);
            }
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int n = 0; n < 20; n++) {
                        assertThat(provider.getDuration(List.of(new Point(lon, 50.5), new Point(lon + 1, 51.5))))
                                .isEqualTo(1000);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        assertThat(requestedPaths).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void aBodyWithoutRoutesBecomesRoutingExceptionNotNullPointer() {
        // OSRM answers {"code":"NoRoute", ...} when no route exists; that must surface as the documented exception.
        responseBody.set("{\"code\":\"NoRoute\",\"message\":\"Impossible route between points\"}");

        assertThatThrownBy(() -> provider.getRoute(new Point(1.0, 2.0), new Point(3.0, 4.0)))
                .isInstanceOf(RoutingException.class);
        assertThatThrownBy(() -> provider.getDuration(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class);
        assertThatThrownBy(() -> provider.getDistance(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class);
        assertThatThrownBy(() -> provider.getCostMatrix(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class);

        responseBody.set("{\"routes\":[]}");
        assertThatThrownBy(() -> provider.getDuration(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("no route");

        responseBody.set("{\"routes\":[{\"weight\":1}]}");
        assertThatThrownBy(() -> provider.getDistance(List.of(new Point(1.0, 2.0))))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("distance");
    }

    @Test
    void aCallerSuppliedHttpClientIsUsedAndNotClosedByTheProvider() throws Exception {
        responseBody.set("{\"routes\":[{\"distance\":7}]}");
        try (HttpClient own = HttpClient.newHttpClient()) {
            RoutingProvider shared = new RoutingProvider("http://127.0.0.1:" + server.getAddress().getPort(),
                    JsonMapper.builder().build(), own);

            assertThat(shared.getDistance(List.of(new Point(1.0, 2.0)))).isEqualTo(7);
            shared.close();   // must not close the caller's client ...

            // ... which therefore still works afterwards.
            HttpRequest probe = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/probe")).GET().build();
            assertThat(own.send(probe, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void oneProviderServesManySequentialRequestsOverItsSingleClient() {
        responseBody.set("{\"routes\":[{\"distance\":1}]}");
        for (int i = 0; i < 50; i++) {
            assertThat(provider.getDistance(List.of(new Point(1.0 + i, 2.0)))).isEqualTo(1);
        }
    }

    @Test
    void closingTheOwnedClientTwiceIsHarmless() {
        provider.close();
        provider.close();
    }

    /**
     * Delegates to a real client and records the timeout of every request it is asked to send.
     */
    private static final class RecordingClient extends HttpClient {

        private final HttpClient delegate;
        private final List<Optional<Duration>> timeouts = Collections.synchronizedList(new ArrayList<>());

        private RecordingClient(HttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            timeouts.add(request.timeout());
            return delegate.send(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            timeouts.add(request.timeout());
            return delegate.sendAsync(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            timeouts.add(request.timeout());
            return delegate.sendAsync(request, handler, pushPromiseHandler);
        }
    }

}
