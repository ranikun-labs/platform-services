package com.ranikunlabs.platform.gateway

import com.ranikunlabs.platform.gateway.config.GatewayConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "GATEWAY_PORT=0",
        "REDIS_HOST=localhost",
        "REDIS_PORT=6379",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_AUTH_URI=http://localhost:8082",
        "CARELOG_BE_URI=http://localhost:8080",
        "CARELOG_RAG_URI=http://localhost:8081",
        "GATEWAY_INTERNAL_SECRET=legacy-internal-secret",
        "JWT_SECRET_KEY=platform-gateway-legacy-secret-key-long-enough"
    ]
)
class GatewayLegacyAliasContextTest(
    @Autowired private val gatewayConfig: GatewayConfig
) {

    @Test
    fun `legacy Carelog values bind through the compatibility aliases`() {
        assertThat(gatewayConfig.internalSecret).isEqualTo("legacy-internal-secret")
        assertThat(gatewayConfig.jwtVerificationSecret)
            .isEqualTo("platform-gateway-legacy-secret-key-long-enough")
    }
}
