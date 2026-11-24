package com.ranikunlabs.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "PLATFORM_GATEWAY_PORT=0",
                "PLATFORM_GATEWAY_REDIS_HOST=redis",
                "PLATFORM_GATEWAY_REDIS_PORT=6379",
                "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "PLATFORM_GATEWAY_CARELOG_BE_URI=http://localhost:8080",
                "PLATFORM_GATEWAY_FINANCE_BE_URI=http://localhost:8082",
                "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://localhost:8081",
                "PLATFORM_GATEWAY_INTERNAL_SECRET=readiness-test-secret",
                "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-readiness-test-secret-key-long-enough"
        })
@AutoConfigureWebTestClient
class GatewayReadinessIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void readinessEndpointIsAvailable() {
        webTestClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }
}
