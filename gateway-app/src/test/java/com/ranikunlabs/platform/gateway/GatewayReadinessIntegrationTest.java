package com.ranikunlabs.platform.gateway;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "PLATFORM_GATEWAY_PORT=0",
                "PLATFORM_GATEWAY_REDIS_HOST=redis",
                "PLATFORM_GATEWAY_REDIS_PORT=6379",
                "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "PLATFORM_GATEWAY_AUTH_URI=http://localhost:8082",
                "PLATFORM_GATEWAY_CARELOG_BE_URI=http://localhost:8080",
                "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://localhost:8081",
                "PLATFORM_GATEWAY_INTERNAL_SECRET=readiness-test-secret",
                "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-readiness-test-secret-key-long-enough",
                "management.server.port=0"
        })
class GatewayReadinessIntegrationTest {

    @Autowired
    private Environment environment;

    @Test
    void readinessEndpointIsAvailableOnlyOnTheManagementListener() throws Exception {
        int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> readiness = get(client, managementPort);
        HttpResponse<String> applicationPortProbe = get(client, applicationPort);

        org.assertj.core.api.Assertions.assertThat(readiness.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(readiness.body()).contains("\"status\":\"UP\"");
        org.assertj.core.api.Assertions.assertThat(applicationPortProbe.statusCode()).isBetween(400, 499);
    }

    private static HttpResponse<String> get(HttpClient client, int port) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/actuator/health/readiness")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
