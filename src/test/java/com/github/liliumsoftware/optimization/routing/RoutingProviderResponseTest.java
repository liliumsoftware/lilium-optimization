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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins how {@link RoutingProvider} reads response bodies: the response limit (a body, announced or streamed, larger
 * than the limit fails as a {@link RoutingException} caused by an {@link IOException} naming the limit, without being
 * read to its end), and the streaming {@code /table} parser, which must keep every check, message and precedence of
 * reading the whole document as a tree first.
 */
class RoutingProviderResponseTest {

    private static final List<Point> TWO_POINTS = List.of(new Point(4.4, 52.2), new Point(4.9, 52.4));

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicInteger endlessChunks = new AtomicInteger();
    /**
     * Released when a test ends, so an endless body never outlives the test.
     */
    private final CountDownLatch release = new CountDownLatch(1);

    private HttpServer server;

    @BeforeEach
    void startFakeOsrm() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> send(exchange, false));
        server.createContext("/chunked", exchange -> send(exchange, true));
        server.createContext("/endless", this::sendEndlessBody);
        server.createContext("/announced", this::announceMoreThanTheDefaultLimit);
        server.start();
    }

    @AfterEach
    void stopFakeOsrm() {
        release.countDown();
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Sends {@link #responseBody}, announcing its length or (chunked) not.
     */
    private void send(HttpExchange exchange, boolean chunked) throws IOException {
        byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus.get(), chunked ? 0 : payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            for (byte b : payload) {   // byte by byte, so a chunked body arrives in many pieces
                out.write(b);
                if (chunked) {
                    out.flush();
                }
            }
        }
    }

    /**
     * A chunked body that never ends: 64 KiB chunks until the client goes away or the test ends.
     */
    private void sendEndlessBody(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) ' ');
        try (OutputStream out = exchange.getResponseBody()) {
            out.write('[');
            while (release.getCount() > 0) {
                out.write(chunk);
                out.flush();
                endlessChunks.incrementAndGet();
            }
        } catch (IOException ignored) {
            // the client cancelled the exchange and closed the connection: exactly what the test expects
        }
    }

    /**
     * Announces one byte more than the default limit, then sends a few bytes and gives up.
     */
    private void announceMoreThanTheDefaultLimit(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, RoutingProvider.DEFAULT_MAX_RESPONSE_BYTES + 1);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write("{\"routes\":".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // closing an exchange whose announced body was not sent in full is expected to fail
        }
    }

    // ---------------------------------------------------------------- response limit

    @ParameterizedTest
    @ValueSource(strings = {"", "/chunked"})
    void aBodyLargerThanTheLimitIsRejectedNamingTheLimit(String path) {
        // 40 bytes; announced (Content-Length) or streamed in chunks, it must be stopped at the 39-byte limit.
        responseBody.set("{\"routes\":[{\"duration\":1,\"weight\":123}]}");
        assertThat(responseBody.get()).hasSize(40);
        try (RoutingProvider limited = new RoutingProvider(baseUrl() + path, mapper, Duration.ofSeconds(10), 39)) {
            assertThatThrownBy(() -> limited.getDuration(TWO_POINTS))
                    .isInstanceOf(RoutingException.class)
                    .cause().isInstanceOf(IOException.class)
                    .hasMessageContaining("response limit of 39 bytes");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/chunked"})
    void aBodyOfExactlyTheLimitIsAccepted(String path) {
        responseBody.set("{\"routes\":[{\"duration\":1,\"weight\":123}]}");
        try (RoutingProvider limited = new RoutingProvider(baseUrl() + path, mapper, Duration.ofSeconds(10), 40)) {
            assertThat(limited.getDuration(TWO_POINTS)).isEqualTo(1000);
        }
    }

    @Test
    void theLimitAlsoAppliesToAnErrorAnswer() {
        responseStatus.set(500);
        responseBody.set("x".repeat(1_000));
        try (RoutingProvider limited = new RoutingProvider(baseUrl() + "/chunked", mapper, Duration.ofSeconds(10),
                100)) {
            assertThatThrownBy(() -> limited.getDuration(TWO_POINTS))
                    .isInstanceOf(RoutingException.class)
                    .cause().isInstanceOf(IOException.class)
                    .hasMessageContaining("response limit of 100 bytes");
        }
    }

    @Test
    void anEndlessBodyIsCutOffAtTheLimitInsteadOfBeingReadUntilTheTimeout() {
        // Without a limit the body would be buffered until the 20 s timeout (or the heap) ran out.
        try (HttpClient client = HttpClient.newHttpClient()) {
            RoutingProvider limited = new RoutingProvider(baseUrl() + "/endless", mapper, client,
                    Duration.ofSeconds(20), 1024 * 1024);
            long start = System.nanoTime();

            assertThatThrownBy(() -> limited.getCostMatrix(TWO_POINTS))
                    .isInstanceOf(RoutingException.class)
                    .cause().isInstanceOf(IOException.class)
                    .hasMessageContaining("response limit of 1048576 bytes");

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    void theConstructorsWithoutALimitApplyTheDefaultLimit() {
        // 512 MiB, checked against the announced Content-Length before a byte of the body is buffered.
        assertThat(RoutingProvider.DEFAULT_MAX_RESPONSE_BYTES).isEqualTo(512L * 1024 * 1024);
        try (HttpClient client = HttpClient.newHttpClient();
                RoutingProvider owned = new RoutingProvider(baseUrl() + "/announced", mapper)) {
            List<RoutingProvider> providers = List.of(owned,
                    new RoutingProvider(baseUrl() + "/announced", mapper, Duration.ofSeconds(10)),
                    new RoutingProvider(baseUrl() + "/announced", mapper, client),
                    new RoutingProvider(baseUrl() + "/announced", mapper, client, Duration.ofSeconds(10)));
            for (RoutingProvider provider : providers) {
                assertThatThrownBy(() -> provider.getDuration(TWO_POINTS))
                        .isInstanceOf(RoutingException.class)
                        .cause().isInstanceOf(IOException.class)
                        .hasMessageContaining("536870913 bytes")
                        .hasMessageContaining("response limit of 536870912 bytes");
                provider.close();
            }
        }
    }

    @Test
    void noResponseLimitReadsABodyOfAnySize() {
        responseBody.set("{\"routes\":[{\"duration\":1,\"padding\":\"" + "x".repeat(10_000) + "\"}]}");
        try (RoutingProvider unlimited = new RoutingProvider(baseUrl() + "/chunked", mapper, Duration.ofSeconds(10),
                RoutingProvider.NO_RESPONSE_LIMIT)) {
            assertThat(RoutingProvider.NO_RESPONSE_LIMIT).isZero();
            assertThat(unlimited.getDuration(TWO_POINTS)).isEqualTo(1000);
        }
    }

    @Test
    // every constructor call below must throw before a provider exists
    @SuppressWarnings("resource")
    void aNegativeLimitIsRejectedByBothConstructors() {
        assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, Duration.ofSeconds(1), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResponseBytes must not be negative");
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, client, Duration.ofSeconds(1), -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxResponseBytes must not be negative");
            assertThatThrownBy(() -> new RoutingProvider(baseUrl(), mapper, client, null, 1))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ---------------------------------------------------------------- streaming table parser

    private int[][] table(String body) {
        responseBody.set(body);
        try (RoutingProvider provider = new RoutingProvider(baseUrl(), mapper)) {
            return provider.getCostMatrix(TWO_POINTS);
        }
    }

    @Test
    void membersAroundTheDurationsTableAreSkipped() {
        int[][] matrix = table("{\"code\":\"Ok\",\"sources\":[{\"location\":[4.4,52.2],\"name\":\"a\"}],"
                + "\"durations\":[[0,1.5],[2.25,0]],\"destinations\":[{\"location\":[[1],{\"x\":null}]}]}");

        assertThat(matrix[0]).containsExactly(0, 1500);
        assertThat(matrix[1]).containsExactly(2250, 0);
    }

    @Test
    void theRowCountIsCheckedBeforeAnyRowsContent() {
        assertThatThrownBy(() -> table("{\"durations\":[[0,\"x\"]]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service durations table has 1 rows for 2 coordinates");
        assertThatThrownBy(() -> table("{\"durations\":[[0,null],[1,0],[1,0],[\"x\"]]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service durations table has 4 rows for 2 coordinates");
    }

    @Test
    void aRowsCellCountIsCheckedBeforeItsCells() {
        assertThatThrownBy(() -> table("{\"durations\":[[null,\"x\",1],[1,0]]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service durations table row 0 has 3 cells for 2 coordinates");
    }

    @Test
    void theFirstFaultyRowInOrderIsReported() {
        assertThatThrownBy(() -> table("{\"durations\":[[0,null],5]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service reports no route from coordinate 0 (4.4,52.2) to coordinate 1 (4.9,52.4)");
        assertThatThrownBy(() -> table("{\"durations\":[[0,1],[\"x\",null]]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service durations table cell 1,0 is not a number: \"x\"");
    }

    @Test
    void aNonNumericCellIsPrintedAsJson() {
        assertThatThrownBy(() -> table("{\"durations\":[[0,{\"a\":[1,true]}],[1,0]]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service durations table cell 0,1 is not a number: {\"a\":[1,true]}");
        assertThatThrownBy(() -> table("{\"durations\":[[0,1],[false,0]]}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service durations table cell 1,0 is not a number: false");
    }

    @Test
    void aSyntaxErrorAnywhereTakesPrecedenceOverATableFault() {
        assertThatThrownBy(() -> table("{\"durations\":[[0,null],[1,0]],\"code\":}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Cost matrix request failed")
                .hasCauseInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> table("{\"durations\":[[0,1],[1,0]]"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Cost matrix request failed");
        assertThatThrownBy(() -> table("{\"durations\":[[0,1],[1,0]]} {}"))
                .as("trailing tokens, rejected as by the mapper's default FAIL_ON_TRAILING_TOKENS")
                .isInstanceOf(RoutingException.class)
                .hasMessage("Cost matrix request failed");
    }

    @Test
    void theLastDurationsMemberCounts() {
        int[][] matrix = table("{\"durations\":[[0,null],[0,0]],\"durations\":[[0,1],[2,0]]}");
        assertThat(matrix[0]).containsExactly(0, 1000);
        assertThat(matrix[1]).containsExactly(2000, 0);

        assertThatThrownBy(() -> table("{\"durations\":[[0,1],[2,0]],\"durations\":null}"))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service response has no durations table");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "5", "\"durations\"", "[[0,1],[1,0]]", "{\"table\":{\"durations\":[]}}",
            "{\"durations\":{\"0\":[0,1]}}"})
    void aBodyWithoutARootLevelDurationsArrayHasNoTable(String body) {
        assertThatThrownBy(() -> table(body))
                .isInstanceOf(RoutingException.class)
                .hasMessage("Routing service response has no durations table");
    }

}
