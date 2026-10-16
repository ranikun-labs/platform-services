package com.ranikunlabs.platform.core;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
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
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final Pattern LABEL = Pattern.compile("([^,=]+)=\"([^\"]*)\"");

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void classifiesSyncAsyncAndTypedTimeoutFailuresExactlyOnce() throws Exception {
        assertThat(dataSource.getClass().getName()).contains("HikariDataSource");
        assertThat(dataSource.getClass().getName())
                .isEqualTo("com.ranikunlabs.platform.identity.adapter.persistence.InstrumentedHikariDataSource");
        Connection connection = dataSource.getConnection();
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(connection.isWrapperFor(String.class)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connection.unwrap(String.class))
                .isInstanceOf(SQLException.class);
        assertThat(connection.equals(connection)).isTrue();
        assertThat(connection.hashCode()).isEqualTo(connection.hashCode());

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
            assertThat(sqlFailure).isNotNull();
            assertThat(sqlFailure.getSQLState()).isEqualTo("22012");
        }
        connection.close();
        assertThat(connection.isClosed()).isTrue();

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
        assertSample(scrape.body(), "ranikun_db_query_duration_seconds_bucket",
                "operation=\"query\"", "outcome=\"success\"");
        assertSample(scrape.body(), "ranikun_db_query_duration_seconds_bucket",
                "operation=\"metadata\"", "outcome=\"success\"");
        assertSample(scrape.body(), "ranikun_db_query_duration_seconds_count",
                "operation=\"query\"", "outcome=\"error\"");
        assertSample(scrape.body(), "ranikun_db_query_duration_seconds_sum",
                "operation=\"query\"", "outcome=\"success\"");
        assertSample(scrape.body(), "ranikun_db_query_duration_seconds_count",
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
                        .timeout(Duration.ofSeconds(10))
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
