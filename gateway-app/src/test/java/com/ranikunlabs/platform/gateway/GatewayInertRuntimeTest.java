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
                "platform.gateway.redis-host=redis",
                "platform.gateway.redis-port=6379",
                "platform.gateway.redis-key-prefix=platform:gateway:"
        })
class GatewayInertRuntimeTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void exposesNoExecutableProductRoutesInG1() {
        List<Route> routes = routeLocator.getRoutes()
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(routes).isEmpty();
    }
}
