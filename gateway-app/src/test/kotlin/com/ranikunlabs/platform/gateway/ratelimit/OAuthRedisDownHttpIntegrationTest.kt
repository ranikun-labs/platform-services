package com.ranikunlabs.platform.gateway.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.beans.factory.annotation.Autowired
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
        "PLATFORM_GATEWAY_REDIS_PORT=1",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:39155",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:39153",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39153",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=integration-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-integration-secret-key-long-enough"
    ]
)
@AutoConfigureWebTestClient
class OAuthRedisDownHttpIntegrationTest(
    @Autowired private val webTestClient: WebTestClient
) {

    @BeforeEach
    fun resetInvocationCount() {
        upstreamInvocations.set(0)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/auth/oauth/kakao/authorization",
        "/api/v1/auth/oauth/kakao/exchange"
    ])
    fun `unreachable Redis returns 503 and does not invoke the OAuth upstream`(path: String) {
        webTestClient.post()
            .uri(path)
            .header("X-Forwarded-For", "203.0.113.10")
            .exchange()
            .expectStatus().isEqualTo(503)

        assertThat(upstreamInvocations).hasValue(0)
    }

    private companion object {
        val upstreamInvocations = AtomicInteger()

        val upstream: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(39153)
            .handle { _, response ->
                upstreamInvocations.incrementAndGet()
                response.status(200).send()
            }
            .bindNow()

        @JvmStatic
        @AfterAll
        fun stopUpstream() {
            upstream.disposeNow()
        }
    }
}
