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
                "platform.gateway.redis-host=redis",
                "platform.gateway.redis-port=6379",
                "platform.gateway.redis-key-prefix=platform:gateway:"
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
