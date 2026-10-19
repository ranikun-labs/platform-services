package com.ranikunlabs.platform.gateway;

import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "PLATFORM_GATEWAY_PORT=0",
                "PLATFORM_GATEWAY_MANAGEMENT_PORT=0",
                "PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
                "PLATFORM_GATEWAY_REDIS_PORT=6379",
                "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:39155",
                "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:39156",
                "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39157",
                "PLATFORM_GATEWAY_INTERNAL_SECRET=metrics-test-secret",
                "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-metrics-test-secret-key",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "platform.metrics.environment=test"
        })
@Import(GatewayPrometheusIntegrationTest.MetricsProbeConfiguration.class)
class GatewayPrometheusIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    private static final Pattern LABEL = Pattern.compile("([^,=]+)=\"([^\"]*)\"");
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GC_OBSERVATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration GC_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration GC_REQUEST_SAFETY_MARGIN = Duration.ofMillis(25);
    // Allow 750 ms for JVM scheduling and test-thread wake-up around the 5 second deadline.
    private static final Duration GC_DEADLINE_SCHEDULING_TOLERANCE = Duration.ofMillis(750);

    @Autowired
    private Environment environment;

    @Test
    void servesBoundedHistogramMetricsOnlyOnManagementListenerAndSeparatesTimeouts() throws Exception {
        int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        assertCounts(managementPort, 0, 0);

        for (int index = 0; index < 12; index++) {
            HttpResponse<String> response = get(applicationPort,
                    "/metrics-probe/account-" + index
                            + "?user_id=user-secret-" + index
                            + "&tenant_id=tenant-secret-" + index
                            + "&access_token=jwt-secret-" + index);
            assertThat(response.statusCode()).isEqualTo(200);
        }
        assertCounts(managementPort, 0, 0);

        assertThat(get(applicationPort, "/metrics-probe/error").statusCode()).isEqualTo(500);
        assertCounts(managementPort, 1, 0);

        assertThat(get(applicationPort, "/metrics-probe/504").statusCode()).isEqualTo(504);
        assertCounts(managementPort, 2, 0);

        assertThat(get(applicationPort, "/metrics-probe/timeout").statusCode()).isBetween(500, 599);
        assertCounts(managementPort, 2, 1);

        assertThat(get(applicationPort, "/metrics-probe/jdk-timeout").statusCode()).isBetween(500, 599);
        assertCounts(managementPort, 2, 2);

        assertThat(get(applicationPort, "/metrics-probe/netty-timeout").statusCode()).isBetween(500, 599);
        assertCounts(managementPort, 2, 3);

        HttpResponse<String> publicPort = get(applicationPort, "/actuator/prometheus");
        assertThat(publicPort.statusCode()).isBetween(400, 499);

        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(200);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).as(scrape.body()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("http_server_requests_seconds_bucket")
                .contains("le=\"0.02\"")
                .contains("jvm_memory_used_bytes")
                .contains("jvm_threads_live_threads")
                .contains("process_cpu_usage")
                .contains("service=\"gateway\"")
                .contains("environment=\"test\"")
                .contains("platform_http_server_errors_total")
                .contains("platform_http_server_timeouts_total")
                .doesNotContain("user-secret-", "tenant-secret-", "jwt-secret-", "/metrics-probe/account-");
        assertThat(scrape.body()).doesNotContain("uri=", "url=", "user_id=", "tenant_id=", "access_token=");
        assertThat(getSuccessfulGetHistogramLabels(scrape.body())).hasSize(1);
        awaitObservedGcMetric(managementPort);
        scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.body()).contains("jvm_gc_pause_seconds_count");
        assertThat(hasPositiveGcPauseCount(scrape.body())).isTrue();
        assertCounter(scrape.body(), "platform_http_server_errors_total", 2,
                "service=\"gateway\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_timeouts_total", 3,
                "service=\"gateway\"", "environment=\"test\"");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return get(URI.create("http://127.0.0.1:" + port + path), HTTP_REQUEST_TIMEOUT);
    }

    private static HttpResponse<String> get(URI uri, Duration timeout) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> getSuccessfulGetHistogramLabels(String scrape) {
        Set<String> labelSets = new TreeSet<>();
        for (String line : scrape.lines().toList()) {
            if (!line.startsWith("http_server_requests_seconds_bucket{")
                    || !line.contains("method=\"GET\"")
                    || !line.contains("status=\"200\"")) {
                continue;
            }
            Matcher matcher = LABEL.matcher(line.substring(line.indexOf('{') + 1, line.indexOf('}')));
            Set<String> labels = new TreeSet<>();
            while (matcher.find()) {
                if (!"le".equals(matcher.group(1))) {
                    labels.add(matcher.group(1) + "=\"" + matcher.group(2) + "\"");
                }
            }
            labelSets.add(String.join(",", labels));
        }
        return labelSets;
    }

    private static void assertCounts(int managementPort, double expectedErrors, double expectedTimeouts)
            throws Exception {
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertCounter(scrape.body(), "platform_http_server_errors_total", expectedErrors,
                "service=\"gateway\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_timeouts_total", expectedTimeouts,
                "service=\"gateway\"", "environment=\"test\"");
    }

    private static void awaitObservedGcMetric(int managementPort) throws Exception {
        awaitObservedGcMetric(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"),
                GC_OBSERVATION_TIMEOUT,
                System::gc);
    }

    private static void awaitObservedGcMetric(
            URI scrapeUri, Duration observationTimeout, Runnable gcRequest) throws Exception {
        long deadline = System.nanoTime() + observationTimeout.toNanos();
        long initialCollectionCount = totalGcCollectionCount();
        gcRequest.run();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            long requestTimeoutNanos = remainingNanos - GC_REQUEST_SAFETY_MARGIN.toNanos();
            if (requestTimeoutNanos <= 0) {
                break;
            }

            // HttpRequest.timeout alone did not bound completion of a stalled response body.
            // Bound the future waiting for the full BodyHandlers.ofString() result as well.
            HttpResponse<String> scrape = getBeforeDeadline(scrapeUri, deadline, requestTimeoutNanos);
            if (scrape.statusCode() != 200) {
                throw new AssertionError("Prometheus scrape returned HTTP " + scrape.statusCode());
            }
            if (totalGcCollectionCount() > initialCollectionCount
                    && hasPositiveGcPauseCount(scrape.body())) {
                assertBeforeDeadline(deadline);
                return;
            }

            remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(GC_POLL_INTERVAL.toNanos(), remainingNanos));
        }
        throw new AssertionError("A JVM GC event and its Prometheus pause metric were not observed within "
                + observationTimeout.toMillis() + " ms");
    }

    private static HttpResponse<String> getBeforeDeadline(
            URI uri, long deadline, long requestTimeoutNanos) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> responseFuture = null;
        HttpResponse<String> response = null;
        Throwable requestFailure = null;
        try {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofNanos(requestTimeoutNanos))
                        .GET().build();
                responseFuture = executor.submit(() -> HTTP.send(request, HttpResponse.BodyHandlers.ofString()));
                long waitNanos = Math.min(requestTimeoutNanos,
                        deadline - System.nanoTime() - GC_REQUEST_SAFETY_MARGIN.toNanos());
                if (waitNanos <= 0) {
                    throw new TimeoutException("GC observation deadline expired before HTTP response wait");
                }
                response = responseFuture.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                if (responseFuture != null) {
                    responseFuture.cancel(true);
                }
                HttpTimeoutException deadlineExceeded = new HttpTimeoutException(
                        "Prometheus response body did not complete before the GC observation deadline");
                deadlineExceeded.initCause(timeout);
                requestFailure = deadlineExceeded;
            } catch (InterruptedException interrupted) {
                if (responseFuture != null) {
                    responseFuture.cancel(true);
                }
                requestFailure = interrupted;
            } catch (ExecutionException execution) {
                Throwable cause = execution.getCause();
                if (cause instanceof Exception || cause instanceof Error) {
                    requestFailure = cause;
                } else {
                    requestFailure = new IOException("Prometheus request failed", cause);
                }
            }
        } finally {
            executor.shutdownNow();
            long cleanupNanos = deadline + GC_DEADLINE_SCHEDULING_TOLERANCE.toNanos() - System.nanoTime();
            if (cleanupNanos > 0) {
                try {
                    executor.awaitTermination(cleanupNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interruptedDuringCleanup) {
                    if (requestFailure != null) {
                        requestFailure.addSuppressed(interruptedDuringCleanup);
                    }
                    requestFailure = interruptedDuringCleanup;
                }
            }
        }

        if (!executor.isTerminated()) {
            IllegalStateException cleanupFailure = new IllegalStateException(
                    "Prometheus HTTP worker did not terminate after request cancellation");
            if (requestFailure != null) {
                cleanupFailure.addSuppressed(requestFailure);
            }
            throw cleanupFailure;
        }
        if (requestFailure instanceof InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (requestFailure instanceof Exception exception) {
            throw exception;
        }
        if (requestFailure instanceof Error error) {
            throw error;
        }
        assertBeforeDeadline(deadline);
        return response;
    }

    private static void assertBeforeDeadline(long deadline) throws HttpTimeoutException {
        if (deadline - System.nanoTime() <= 0) {
            throw new HttpTimeoutException("Prometheus response completed after the GC observation deadline");
        }
    }

    @Test
    void nonResponsivePrometheusServerFailsWithinGcDeadlineAndSchedulingTolerance() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
            Thread nonResponsiveServer = startTestServer(server, acceptedSocket, socket -> {
                readRequestHeaders(socket);
                while (socket.getInputStream().read() != -1) {
                    // Keep the connection open without sending an HTTP response.
                }
            }, "non-responsive-prometheus-test-server");

            long started = System.nanoTime();
            try {
                assertGcObservationTimesOut(server.getLocalPort());
            } finally {
                closeQuietly(acceptedSocket.get());
                server.close();
            }
            nonResponsiveServer.join(1_000);
            assertThat(nonResponsiveServer.isAlive()).isFalse();
            assertElapsedWithinGcTolerance(started, "non-responsive HTTP server");
        }
    }

    @Test
    void partialResponseBodyThatNeverFinishesFailsWithinGcDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
            AtomicBoolean partialBodySent = new AtomicBoolean();
            Thread partialBodyServer = startTestServer(server, acceptedSocket, socket -> {
                readRequestHeaders(socket);
                byte[] partialBody = "# HELP jvm_gc_pause_seconds_count\n".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain; version=0.0.4\r\n"
                        + "Content-Length: 512\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(partialBody);
                socket.getOutputStream().flush();
                partialBodySent.set(true);
                while (socket.getInputStream().read() != -1) {
                    // Keep the response body incomplete until the client cancels or the test closes it.
                }
            }, "partial-body-prometheus-test-server");

            long started = System.nanoTime();
            try {
                assertGcObservationTimesOut(server.getLocalPort());
                assertThat(partialBodySent).isTrue();
            } finally {
                closeQuietly(acceptedSocket.get());
                server.close();
            }
            partialBodyServer.join(1_000);
            assertThat(partialBodyServer.isAlive()).isFalse();
            assertElapsedWithinGcTolerance(started, "partial HTTP response body");
        }
    }

    @Test
    void delayedPositiveSampleAfterDeadlineIsNotAccepted() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
            CountDownLatch latePositiveSampleReleased = new CountDownLatch(1);
            Thread delayedSampleServer = startTestServer(server, acceptedSocket, socket -> {
                readRequestHeaders(socket);
                byte[] body = "# HELP jvm_gc_pause_seconds_count GC pause count\n"
                        .concat("jvm_gc_pause_seconds_count{action=\"end of major GC\"} 1.0\n")
                        .getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain; version=0.0.4\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                TimeUnit.NANOSECONDS.sleep(GC_OBSERVATION_TIMEOUT.toNanos() + TimeUnit.MILLISECONDS.toNanos(100));
                try {
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } finally {
                    latePositiveSampleReleased.countDown();
                }
            }, "delayed-positive-prometheus-test-server");

            long started = System.nanoTime();
            try {
                assertGcObservationTimesOut(server.getLocalPort());
                assertThat(latePositiveSampleReleased.await(750, TimeUnit.MILLISECONDS))
                        .as("server releases a positive sample only after the observation deadline")
                        .isTrue();
            } finally {
                closeQuietly(acceptedSocket.get());
                server.close();
            }
            delayedSampleServer.join(1_000);
            assertThat(delayedSampleServer.isAlive()).isFalse();
            assertElapsedWithinGcTolerance(started, "delayed positive HTTP response");
        }
    }

    private static void assertGcObservationTimesOut(int port) {
        assertThatThrownBy(() -> awaitObservedGcMetric(
                URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"),
                GC_OBSERVATION_TIMEOUT,
                () -> {}))
                .isInstanceOf(HttpTimeoutException.class);
    }

    private static void assertElapsedWithinGcTolerance(long started, String scenario) {
        long allowedNanos = GC_OBSERVATION_TIMEOUT.plus(GC_DEADLINE_SCHEDULING_TOLERANCE).toNanos();
        assertThat(System.nanoTime() - started).as(scenario + " deadline + scheduling tolerance")
                .isLessThanOrEqualTo(allowedNanos);
    }

    private static Thread startTestServer(
            ServerSocket server, AtomicReference<Socket> acceptedSocket, SocketAction action, String name) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                acceptedSocket.set(socket);
                action.run(socket);
            } catch (IOException expectedWhenClientCancels) {
                // The bounded client cancels or the test closes the local connection.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void readRequestHeaders(Socket socket) throws IOException {
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched < terminator.length) {
            int next = socket.getInputStream().read();
            if (next < 0) {
                throw new IOException("Client closed before sending request headers");
            }
            matched = next == terminator[matched] ? matched + 1 : (next == terminator[0] ? 1 : 0);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Local test cleanup.
            }
        }
    }

    @FunctionalInterface
    private interface SocketAction {
        void run(Socket socket) throws IOException, InterruptedException;
    }

    private static long totalGcCollectionCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
                .sum();
    }

    private static boolean hasPositiveGcPauseCount(String scrape) {
        return scrape.lines()
                .filter(line -> line.startsWith("jvm_gc_pause_seconds_count{"))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .mapToDouble(Double::parseDouble)
                .anyMatch(value -> value > 0);
    }

    private static void assertCounter(String scrape, String name, double expectedValue, String... labels) {
        String sample = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing counter sample " + name));
        assertThat(sample).endsWith(" " + expectedValue);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsProbeConfiguration {

        @Bean
        MetricsProbeController metricsProbeController() {
            return new MetricsProbeController();
        }
    }

    @RestController
    static class MetricsProbeController {

        @GetMapping("/metrics-probe/{account}")
        ResponseEntity<Void> successfulRequest() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/metrics-probe/error")
        ResponseEntity<Void> failedRequest() {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        @GetMapping("/metrics-probe/504")
        ResponseEntity<Void> gatewayTimeoutWithoutCause() {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }

        @GetMapping("/metrics-probe/timeout")
        Mono<Void> timedOutRequest() {
            return Mono.error(new java.util.concurrent.TimeoutException("synthetic timeout"));
        }

        @GetMapping("/metrics-probe/jdk-timeout")
        Mono<Void> jdkTimedOutRequest() {
            return Mono.error(new HttpTimeoutException("synthetic JDK HTTP timeout"));
        }

        @GetMapping("/metrics-probe/netty-timeout")
        Mono<Void> nettyTimedOutRequest() {
            return Mono.error(ReadTimeoutException.INSTANCE);
        }
    }
}
