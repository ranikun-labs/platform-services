package com.ranikunlabs.platform.gateway;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = GatewayApplication.class,
        properties = {
                "server.port=0",
                "PLATFORM_GATEWAY_PORT=0",
                "PLATFORM_GATEWAY_REDIS_HOST=redis",
                "PLATFORM_GATEWAY_REDIS_PORT=6379",
                "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "PLATFORM_GATEWAY_AUTH_URI=http://localhost:8081",
                "PLATFORM_GATEWAY_CARELOG_BE_URI=http://localhost:8080",
                "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://localhost:8081",
                "PLATFORM_GATEWAY_INTERNAL_SECRET=runtime-test-secret",
                "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-runtime-test-secret-key-long-enough"
        })
class GatewayInertRuntimeTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void bootsWithExecutableGatewayRoutesAfterG1RuntimeFoundation() {
        List<Route> routes = routeLocator.getRoutes()
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(routes).extracting(Route::getId)
                .contains("carelog-be", "carelog-rag");
    }
}
