package com.ranikunlabs.platform.core;

import jakarta.persistence.EntityManager;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "PLATFORM_CORE_PORT=0",
                "PLATFORM_CORE_MANAGEMENT_PORT=0",
                "PLATFORM_IDENTITY_REDIS_HOST=127.0.0.1",
                "PLATFORM_IDENTITY_REDIS_PORT=6379",
                "PLATFORM_IDENTITY_REDIS_KEY_PREFIX=test:identity:",
                "platform.identity.persistence.enabled=false",
                "platform.identity.auth.enabled=false",
                "platform.identity.datasource.url=jdbc:h2:mem:platform_core_metrics;DB_CLOSE_DELAY=-1",
                "platform.identity.datasource.username=identity-test",
                "platform.identity.datasource.password=identity-test-password",
                "spring.datasource.url=jdbc:h2:mem:platform_core_metrics;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.hikari.pool-name=platform-core-metrics-test",
                "spring.datasource.hikari.minimum-idle=1",
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "platform.metrics.environment=test"
        })
@Import(PlatformCorePrometheusIntegrationTest.MetricsProbeConfiguration.class)
class PlatformCorePrometheusIntegrationTest {

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

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Test
    void classifiesSyncAsyncAndTypedTimeoutFailuresExactlyOnce() throws Exception {
        assertThat(dataSource.getClass().getName()).contains("HikariDataSource");
        assertThat(dataSource.getClass().getName())
                .isEqualTo("com.ranikunlabs.platform.identity.adapter.persistence.InstrumentedHikariDataSource");
        long jpaQueryCountBefore = timerCount("query", "success");
        assertThat(entityManager.createNativeQuery("SELECT 1").getSingleResult().toString()).isEqualTo("1");
        assertThat(timerCount("query", "success")).isGreaterThan(jpaQueryCountBefore);

        Connection connection = dataSource.getConnection();
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(connection.isWrapperFor(String.class)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connection.unwrap(String.class))
                .isInstanceOf(SQLException.class);
        Class<?> h2VendorConnection = Class.forName("org.h2.jdbc.JdbcConnection");
        assertThat(connection.isWrapperFor(h2VendorConnection)).isTrue();
        assertThat(h2VendorConnection.isInstance(connection.unwrap(h2VendorConnection))).isTrue();
        assertThat(connection.equals(connection)).isTrue();
        assertThat(connection.hashCode()).isEqualTo(connection.hashCode());
        try (Connection peer = dataSource.getConnection()) {
            assertThat(connection.equals(peer)).isFalse();
            assertThat(peer.equals(connection)).isFalse();
        }

        DatabaseMetaData metadata = connection.getMetaData();
        assertThat(metadata.isWrapperFor(DatabaseMetaData.class)).isTrue();
        assertThat(metadata.unwrap(DatabaseMetaData.class)).isSameAs(metadata);
        assertThat(metadata.getConnection()).isSameAs(connection);
        try (ResultSet tables = metadata.getTables(null, null, "PLATFORM_%", null)) {
            assertThat(tables.isClosed()).isFalse();
        }

        PreparedStatement prepared = connection.prepareStatement("SELECT ?");
        assertThat(prepared.isWrapperFor(Statement.class)).isTrue();
        assertThat(prepared.unwrap(Statement.class)).isSameAs(prepared);
        prepared.setInt(1, 1);
        assertThat(prepared.getConnection()).isSameAs(connection);
        ResultSet result = prepared.executeQuery();
        assertThat(result.isWrapperFor(ResultSet.class)).isTrue();
        assertThat(result.unwrap(ResultSet.class)).isSameAs(result);
        assertThat(result.getStatement()).isSameAs(prepared);
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(1);
        result.close();
        assertThat(result.isClosed()).isTrue();
        prepared.close();
        assertThat(prepared.isClosed()).isTrue();

        Statement closeOnCompletion = connection.createStatement();
        closeOnCompletion.closeOnCompletion();
        ResultSet completionResult = closeOnCompletion.executeQuery("SELECT 1");
        completionResult.close();
        assertThat(closeOnCompletion.isClosed()).isTrue();

        try (PreparedStatement failed = connection.prepareStatement("SELECT 1 / ?")) {
            failed.setInt(1, 0);
            SQLException sqlFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    failed::executeQuery, SQLException.class);
            assertThat(sqlFailure != null).isTrue();
            assertThat(sqlFailure.getSQLState()).isEqualTo("22012");
        }
        connection.setAutoCommit(false);
        connection.rollback();
        connection.close();
        assertThat(connection.isClosed()).isTrue();
        try (Connection pooled = dataSource.getConnection()) {
            assertThat(pooled.getAutoCommit()).isTrue();
        }

        // Hikari eviction still closes and replenishes physical connections under the observing subtype.
        try {
            Object pool = dataSource.getClass().getMethod("getHikariPoolMXBean").invoke(dataSource);
            Class.forName("com.zaxxer.hikari.HikariPoolMXBean")
                    .getMethod("softEvictConnections")
                    .invoke(pool);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Hikari soft eviction contract was unavailable", failure);
        }
        try (Connection replacement = dataSource.getConnection();
             Statement statement = replacement.createStatement();
             ResultSet selected = statement.executeQuery("SELECT 1")) {
            assertThat(selected.next()).isTrue();
            assertThat(selected.getInt(1)).isEqualTo(1);
        }

        int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        assertCounts(managementPort, 0, 0);

        assertThat(get(applicationPort, "/metrics-probe/success").statusCode()).isEqualTo(200);
        assertCounts(managementPort, 0, 0);

        assertThat(get(applicationPort, "/metrics-probe/error").statusCode()).isEqualTo(500);
        assertCounts(managementPort, 1, 0);

        assertThat(get(applicationPort, "/metrics-probe/504").statusCode()).isEqualTo(504);
        assertCounts(managementPort, 2, 0);

        HttpResponse<String> adviceHandledTimeout = get(applicationPort, "/metrics-probe/advice-socket-timeout");
        assertThat(adviceHandledTimeout.statusCode()).isEqualTo(504);
        assertThat(adviceHandledTimeout.headers().firstValue("X-Metrics-Test-Advice"))
                .contains("handled");
        assertCounts(managementPort, 2, 1);

        assertThat(get(applicationPort, "/metrics-probe/propagated-http-timeout").statusCode())
                .isBetween(500, 599);
        assertCounts(managementPort, 2, 2);

        assertThat(get(applicationPort, "/metrics-probe/propagated-socket-timeout").statusCode())
                .isBetween(500, 599);
        assertCounts(managementPort, 2, 3);

        assertThat(get(applicationPort, "/metrics-probe/async-timeout").statusCode()).isEqualTo(504);
        assertCounts(managementPort, 2, 4);

        assertThat(get(applicationPort, "/metrics-probe/async-complete").statusCode()).isEqualTo(200);
        assertCounts(managementPort, 2, 4);

        // This request takes an async dispatch and then fails in its handler. The filter and
        // AsyncListener must share one request state, so the dispatch cannot count it twice.
        assertThat(get(applicationPort, "/metrics-probe/async-dispatch-error").statusCode())
                .isBetween(500, 599);
        assertCounts(managementPort, 3, 4);

        awaitObservedGcMetric(managementPort);

        HttpResponse<String> applicationPortProbe = get(applicationPort, "/actuator/prometheus");
        assertThat(applicationPortProbe.statusCode()).isBetween(400, 499);

        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(200);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).as(scrape.body()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("http_server_requests_seconds_bucket")
                .contains("le=\"0.02\"")
                .contains("jvm_memory_used_bytes")
                .contains("jvm_gc_pause_seconds_count")
                .contains("jvm_threads_live_threads")
                .contains("process_cpu_usage")
                .contains("ranikun_db_query_duration_seconds_bucket")
                .contains("ranikun_db_query_duration_seconds_count")
                .contains("ranikun_db_query_duration_seconds_sum")
                .contains("service=\"platform-core\"")
                .contains("environment=\"test\"")
                .contains("hikaricp_connections_active")
                .contains("platform_http_server_errors_total")
                .contains("platform_http_server_timeouts_total")

                .doesNotContain("uri=", "url=", "user_id=", "tenant_id=", "access_token=", "SELECT");
        assertThat(hasPositiveGcPauseCount(scrape.body())).isTrue();
        assertPositiveSample(scrape.body(), "ranikun_db_query_duration_seconds_bucket",
                "operation=\"query\"", "outcome=\"success\"", "le=\"+Inf\"");
        assertPositiveSample(scrape.body(), "ranikun_db_query_duration_seconds_bucket",
                "operation=\"metadata\"", "outcome=\"success\"", "le=\"+Inf\"");
        assertPositiveSample(scrape.body(), "ranikun_db_query_duration_seconds_count",
                "operation=\"query\"", "outcome=\"error\"");
        assertPositiveSample(scrape.body(), "ranikun_db_query_duration_seconds_sum",
                "operation=\"query\"", "outcome=\"success\"");
        assertPositiveSample(scrape.body(), "ranikun_db_query_duration_seconds_count",
                "operation=\"query\"", "outcome=\"success\"");
        assertThat(getSuccessfulGetHistogramLabels(scrape.body())).hasSize(1);
        assertSample(scrape.body(), "hikaricp_connections_active",
                "service=\"platform-core\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_errors_total", 3,
                "service=\"platform-core\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_timeouts_total", 4,
                "service=\"platform-core\"", "environment=\"test\"");
        assertThat(scrape.body()).contains("status=\"504\"").contains("status=\"500\"");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(HTTP_REQUEST_TIMEOUT)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertCounts(int managementPort, double expectedErrors, double expectedTimeouts)
            throws Exception {
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertCounter(scrape.body(), "platform_http_server_errors_total", expectedErrors,
                "service=\"platform-core\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_timeouts_total", expectedTimeouts,
                "service=\"platform-core\"", "environment=\"test\"");
    }

    private static void awaitObservedGcMetric(int managementPort) throws Exception {
        long deadline = System.nanoTime() + GC_OBSERVATION_TIMEOUT.toNanos();
        long initialCollectionCount = totalGcCollectionCount();
        System.gc();

        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            long requestTimeoutNanos = remainingNanos - GC_REQUEST_SAFETY_MARGIN.toNanos();
            if (requestTimeoutNanos <= 0) {
                break;
            }

            HttpResponse<String> scrape = getBeforeDeadline(
                    URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"),
                    deadline,
                    requestTimeoutNanos);
            if (scrape.statusCode() != 200) {
                throw new AssertionError("Prometheus scrape returned HTTP " + scrape.statusCode());
            }
            if (totalGcCollectionCount() > initialCollectionCount && hasPositiveGcPauseCount(scrape.body())) {
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
                + GC_OBSERVATION_TIMEOUT.toMillis() + " ms");
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
                    Thread.currentThread().interrupt();
                    requestFailure = preservePrimaryFailure(requestFailure, interruptedDuringCleanup);
                }
            }
        }

        if (!executor.isTerminated()) {
            IllegalStateException cleanupFailure = new IllegalStateException(
                    "Prometheus HTTP worker did not terminate after request cancellation");
            requestFailure = preservePrimaryFailure(requestFailure, cleanupFailure);
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

    private static Throwable preservePrimaryFailure(Throwable primaryFailure, Throwable cleanupFailure) {
        if (primaryFailure == null) {
            return cleanupFailure;
        }
        if (primaryFailure != cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
        return primaryFailure;
    }

    @Test
    void cleanupFailureRemainsSecondaryToPrimaryRequestFailure() {
        IOException requestFailure = new IOException("HTTP request failed");
        IllegalStateException cleanupFailure = new IllegalStateException("worker did not terminate");

        Throwable result = preservePrimaryFailure(requestFailure, cleanupFailure);

        assertThat((Object) result == requestFailure).isTrue();
        assertThat(requestFailure.getSuppressed().length).isEqualTo(1);
        assertThat(requestFailure.getSuppressed()[0] == cleanupFailure).isTrue();
    }

    @Test
    void cleanupFailureRemainsSecondaryToPrimaryAssertionFailure() {
        AssertionError assertionFailure = new AssertionError("HTTP response assertion failed");
        IllegalStateException cleanupFailure = new IllegalStateException("worker did not terminate");

        Throwable result = preservePrimaryFailure(assertionFailure, cleanupFailure);

        assertThat((Object) result == assertionFailure).isTrue();
        assertThat(assertionFailure.getSuppressed().length).isEqualTo(1);
        assertThat(assertionFailure.getSuppressed()[0] == cleanupFailure).isTrue();
    }

    private static void assertBeforeDeadline(long deadline) throws HttpTimeoutException {
        if (deadline - System.nanoTime() <= 0) {
            throw new HttpTimeoutException("Prometheus response completed after the GC observation deadline");
        }
    }

    @Test
    void nonResponsivePrometheusServerFailsWithinGcDeadlineAndCleansUp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
            Thread nonResponsiveServer = startTestServer(server, acceptedSocket, socket -> {
                readRequestHeaders(socket);
                while (socket.getInputStream().read() != -1) {
                    // Keep the connection open without sending an HTTP response.
                }
            }, "core-non-responsive-prometheus-test-server");

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
    void partialResponseBodyThatNeverFinishesFailsWithinGcDeadlineAndCleansUp() throws Exception {
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
            }, "core-partial-body-prometheus-test-server");

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
    void delayedPositiveSampleAfterDeadlineIsNotAcceptedAndCleansUp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
            CountDownLatch latePositiveSampleReleased = new CountDownLatch(1);
            Thread delayedSampleServer = startTestServer(server, acceptedSocket, socket -> {
                readRequestHeaders(socket);
                byte[] body = ("# HELP jvm_gc_pause_seconds_count GC pause count\n"
                        + "jvm_gc_pause_seconds_count{action=\"end of major GC\"} 1.0\n")
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
            }, "core-delayed-positive-prometheus-test-server");

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
        assertThatThrownBy(() -> awaitObservedGcMetric(port))
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

    private long timerCount(String operation, String outcome) {
        var timer = meterRegistry.find("ranikun.db.query.duration")
                .tags("operation", operation, "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
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

    private static void assertSample(String scrape, String name, String... labels) {
        String sample = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metric sample " + name));
        assertThat(sample).isNotEmpty();
    }

    private static void assertPositiveSample(String scrape, String name, String... labels) {
        String sample = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metric sample " + name));
        assertThat(Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1))).isPositive();
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

        @Bean
        HandledSocketTimeoutController handledSocketTimeoutController() {
            return new HandledSocketTimeoutController();
        }

        @Bean
        PropagatedTimeoutController propagatedTimeoutController() {
            return new PropagatedTimeoutController();
        }

        @Bean
        HandledSocketTimeoutAdvice handledSocketTimeoutAdvice() {
            return new HandledSocketTimeoutAdvice();
        }
    }

    @RestController
    static class MetricsProbeController {

        @GetMapping("/metrics-probe/success")
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

        @GetMapping("/metrics-probe/async-timeout")
        void asyncTimeout(HttpServletRequest request, HttpServletResponse response) {
            response.setStatus(HttpStatus.GATEWAY_TIMEOUT.value());
            AsyncContext context = request.startAsync();
            context.setTimeout(60);
            context.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {}

                @Override
                public void onTimeout(AsyncEvent event) {
                    event.getAsyncContext().complete();
                }

                @Override
                public void onError(AsyncEvent event) {}

                @Override
                public void onStartAsync(AsyncEvent event) {}
            });
        }

        @GetMapping("/metrics-probe/async-complete")
        void asyncComplete(HttpServletRequest request, HttpServletResponse response) {
            response.setStatus(HttpStatus.OK.value());
            AsyncContext context = request.startAsync();
            context.setTimeout(5_000);
            context.start(context::complete);
        }

        @GetMapping("/metrics-probe/async-dispatch-error")
        void asyncDispatchError(HttpServletRequest request) {
            AsyncContext context = request.startAsync();
            context.setTimeout(5_000);
            context.start(() -> context.dispatch("/metrics-probe/async-dispatch-error-target"));
        }

        @GetMapping("/metrics-probe/async-dispatch-error-target")
        ResponseEntity<Void> asyncDispatchErrorTarget() {
            throw new IllegalStateException("synthetic asynchronous dispatch failure");
        }
    }

    @RestController
    static class HandledSocketTimeoutController {

        @GetMapping("/metrics-probe/advice-socket-timeout")
        ResponseEntity<Void> handledSocketTimeout() throws SocketTimeoutException {
            throw new SocketTimeoutException("synthetic advice-handled socket timeout");
        }
    }

    @RestControllerAdvice(assignableTypes = HandledSocketTimeoutController.class)
    static class HandledSocketTimeoutAdvice {

        @ExceptionHandler(SocketTimeoutException.class)
        ResponseEntity<Void> socketTimeout(SocketTimeoutException exception) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .header("X-Metrics-Test-Advice", "handled")
                    .build();
        }
    }

    @RestController
    static class PropagatedTimeoutController {

        @GetMapping("/metrics-probe/propagated-http-timeout")
        ResponseEntity<Void> propagatedHttpTimeout() throws HttpTimeoutException {
            throw new HttpTimeoutException("synthetic propagated JDK HTTP timeout");
        }

        @GetMapping("/metrics-probe/propagated-socket-timeout")
        ResponseEntity<Void> propagatedSocketTimeout() throws SocketTimeoutException {
            throw new SocketTimeoutException("synthetic propagated socket timeout");
        }
    }
}
