package com.ranikunlabs.platform.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
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

@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "PLATFORM_GATEWAY_PORT=0",
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

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern LABEL = Pattern.compile("([^,=]+)=\"([^\"]*)\"");

    @Autowired
    private Environment environment;

    @Test
    void servesBoundedHistogramMetricsOnlyOnManagementListenerAndSeparatesTimeouts() throws Exception {
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
                .contains("service=\"gateway\"")
                .contains("environment=\"test\"")
                .contains("platform_http_server_errors_total")
                .contains("platform_http_server_timeouts_total")
                .doesNotContain("user-secret-", "tenant-secret-", "jwt-secret-", "/metrics-probe/account-");

        assertThat(scrape.body()).contains("service=\"gateway\"", "environment=\"test\"");
        assertThat(scrape.body()).contains("platform_http_server_errors_total");
        assertThat(scrape.body()).contains("platform_http_server_timeouts_total");
        assertThat(scrape.body()).contains("http_server_requests_seconds_bucket");
        assertThat(scrape.body()).doesNotContain("uri=", "url=", "user_id=", "tenant_id=", "access_token=");

        Set<String> labelSets = getSuccessfulGetHistogramLabels(scrape.body());
        assertThat(labelSets).hasSize(1);
        assertCounter(scrape.body(), "platform_http_server_errors_total", "service=\"gateway\"", "environment=\"test\"");
        assertCounter(scrape.body(), "platform_http_server_timeouts_total", "service=\"gateway\"", "environment=\"test\"");
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
        Mono<Void> timedOutRequest() {
            return Mono.error(new java.util.concurrent.TimeoutException("synthetic timeout"));
        }
    }
}
