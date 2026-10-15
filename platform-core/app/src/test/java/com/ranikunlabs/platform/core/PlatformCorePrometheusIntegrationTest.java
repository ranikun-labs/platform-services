package com.ranikunlabs.platform.core;

import jakarta.servlet.ServletException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "PLATFORM_CORE_PORT=0",
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

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern LABEL = Pattern.compile("([^,=]+)=\"([^\"]*)\"");

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void exposesPrivatePrometheusHistogramAndLiveHikariPoolSamples() throws Exception {
        assertThat(dataSource.getClass().getName()).contains("HikariDataSource");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }

        int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        for (int index = 0; index < 12; index++) {
            HttpResponse<String> response = get(applicationPort,
                    "/metrics-probe/account-" + index
                            + "?user_id=user-secret-" + index
                            + "&tenant_id=tenant-secret-" + index
                            + "&access_token=jwt-secret-" + index);
            assertThat(response.statusCode()).isEqualTo(200);
        }
        assertThat(get(applicationPort, "/metrics-probe/error").statusCode()).isEqualTo(503);
        assertThat(get(applicationPort, "/metrics-probe/timeout").statusCode()).isBetween(500, 599);

        HttpResponse<String> publicPort = get(applicationPort, "/actuator/prometheus");
        assertThat(publicPort.statusCode()).isBetween(400, 499);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("http_server_requests_seconds_bucket")
                .contains("le=\"0.02\"")
                .contains("jvm_memory_used_bytes")
                .contains("jvm_gc_pause_seconds_count")
                .contains("jvm_threads_live_threads")
                .contains("process_cpu_usage")
                .contains("service=\"platform-core\"")
                .contains("environment=\"test\"")
                .contains("hikaricp_connections_active")
                .contains("platform_http_server_errors_total")
                .contains("platform_http_server_timeouts_total")
                .doesNotContain("user-secret-", "tenant-secret-", "jwt-secret-", "/metrics-probe/account-");
        assertThat(scrape.body()).doesNotContain("uri=", "url=", "user_id=", "tenant_id=", "access_token=");

        assertThat(getSuccessfulGetHistogramLabels(scrape.body())).hasSize(1);
        assertSample(scrape.body(), "hikaricp_connections_active", "service=\"platform-core\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_errors_total", "service=\"platform-core\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_timeouts_total", "service=\"platform-core\"", "environment=\"test\"");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
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

    private static void assertSample(String scrape, String name, String... labels) {
        String sample = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metric sample " + name));
        assertThat(sample).isNotEmpty();
    }

    private static void assertCounter(String scrape, String name, String... labels) {
        String sample = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing counter sample " + name));
        assertThat(sample).endsWith(" 1.0");
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
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        @GetMapping("/metrics-probe/timeout")
        ResponseEntity<Void> timedOutRequest() throws ServletException {
            throw new ServletException("synthetic timeout", new SocketTimeoutException("read timeout"));
        }
    }
}
