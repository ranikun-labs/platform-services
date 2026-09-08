package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
        "PLATFORM_GATEWAY_REDIS_PORT=1",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:39158",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:39159",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39160",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=auth-failure-test-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-auth-failure-test-secret-key"
    ]
)
@AutoConfigureWebTestClient
class GatewayAuthUpstreamFailureHttpIntegrationTest(
    @Autowired private val webTestClient: WebTestClient
) {

    @BeforeEach
    fun resetFixture() {
        carelogInvocations.set(0)
    }

    @Test
    fun `unreachable Auth upstream returns 5xx without falling through to Carelog`() {
        webTestClient.post()
            .uri("/api/v1/auth/login")
            .exchange()
            .expectStatus().is5xxServerError

        assertThat(carelogInvocations).hasValue(0)
    }

    private companion object {
        val carelogInvocations = AtomicInteger()
        val carelogUpstream: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(39159)
            .handle { _, response ->
                carelogInvocations.incrementAndGet()
                response.status(200).sendString(reactor.core.publisher.Mono.just("CARELOG"))
            }
            .bindNow()

        @JvmStatic
        @AfterAll
        fun stopUpstream() {
            carelogUpstream.disposeNow()
        }
    }
}
