package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.cloud.gateway.route.RouteLocator
import java.net.URI

class GatewayAuthUpstreamConfigurationTest {

    @Test
    fun `changing only the auth upstream configuration changes Identity Auth routing`() {
        assertThat(authRouteUri("http://platform-core.test:8081"))
            .isEqualTo(URI.create("http://platform-core.test:8081"))
        assertThat(authRouteUri("http://carelog.test:8080"))
            .isEqualTo(URI.create("http://carelog.test:8080"))
    }

    private fun authRouteUri(authUri: String): URI {
        val application = SpringApplication(GatewayApplication::class.java).apply {
            webApplicationType = WebApplicationType.REACTIVE
            setRegisterShutdownHook(false)
        }
        val context = application.run(
            "--PLATFORM_GATEWAY_PORT=0",
            "--PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
            "--PLATFORM_GATEWAY_REDIS_PORT=6379",
            "--PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
            "--PLATFORM_GATEWAY_AUTH_URI=$authUri",
            "--PLATFORM_GATEWAY_CARELOG_BE_URI=http://carelog.test:8080",
            "--PLATFORM_GATEWAY_CARELOG_RAG_URI=http://carelog-rag.test:8000",
            "--PLATFORM_GATEWAY_INTERNAL_SECRET=rollback-test-secret",
            "--PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-rollback-test-secret-key-long-enough"
        )

        return try {
            context.getBean(RouteLocator::class.java)
                .routes
                .filter { route -> route.getId() == "platform-identity-auth" }
                .single()
                .block()!!
                .getUri()
        } finally {
            context.close()
        }
    }
}
