package com.ranikunlabs.platform.gateway;

import io.netty.handler.timeout.ReadTimeoutException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
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

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern LABEL = Pattern.compile("([^,=]+)=\"([^\"]*)\"");

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
        long initialCollectionCount = totalGcCollectionCount();
        System.gc();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
            if (scrape.statusCode() == 200
                    && totalGcCollectionCount() > initialCollectionCount
                    && hasPositiveGcPauseCount(scrape.body())) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("A JVM GC event and its Prometheus pause metric were not observed within 5 seconds");
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
