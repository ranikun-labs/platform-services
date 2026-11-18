package com.ranikunlabs.platform.gateway.ratelimit

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_MANAGEMENT_PORT=0",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=integration-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-integration-secret-key-long-enough",
        "spring.data.redis.connect-timeout=500ms",
        "spring.data.redis.timeout=2s"
    ]
)
@AutoConfigureWebTestClient
class OAuthGatewayLiveValkeyOutageHttpIntegrationTest(
    @Autowired private val webTestClient: WebTestClient
) {

    @Test
    fun `live Valkey outage returns 503 for both OAuth routes without calling upstream`() {
        val ping = valkey.execInContainer("valkey-cli", "PING")
        assertThat(ping.exitCode).isZero()
        assertThat(ping.stdout.trim()).isEqualTo("PONG")

        oauthPaths.forEach { path ->
            webTestClient.post()
                .uri(path)
                .header("X-Forwarded-For", CLIENT_IP)
                .exchange()
                .expectStatus()
                .isOk
        }
        assertThat(upstreamInvocations).hasValue(oauthPaths.size)

        valkey.stop()
        assertThat(valkey.isRunning).isFalse()
        upstreamInvocations.set(0)

        oauthPaths.forEach { path ->
            webTestClient.post()
                .uri(path)
                .header("X-Forwarded-For", CLIENT_IP)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)

            assertThat(upstreamInvocations).`as`("OAuth upstream calls after Valkey stopped for $path")
                .hasValue(0)
        }
    }

    private companion object {
        const val CLIENT_IP = "203.0.113.10"
        val oauthPaths = listOf(
            "/api/v1/auth/oauth/kakao/authorization",
            "/api/v1/auth/oauth/kakao/exchange"
        )

        val upstreamInvocations = AtomicInteger()
        val upstream: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle { _, response ->
                upstreamInvocations.incrementAndGet()
                response.status(HttpStatus.OK.value()).send()
            }
            .bindNow()

        val valkey: GenericContainer<Nothing> = GenericContainer<Nothing>(
            DockerImageName.parse(
                "valkey/valkey:8.1.0-alpine@sha256:93df46243b1d033a2d8f3ffc34122c6e431fbb315c5cff246ce6f77cf5a09f5b"
            )
        ).withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun registerTestEndpoints(registry: DynamicPropertyRegistry) {
            if (!valkey.isRunning) valkey.start()
            registry.add("PLATFORM_GATEWAY_REDIS_HOST") { valkey.host }
            registry.add("PLATFORM_GATEWAY_REDIS_PORT") { valkey.getMappedPort(6379) }
            registry.add("PLATFORM_GATEWAY_AUTH_URI") { "http://127.0.0.1:${upstream.port()}" }
            registry.add("PLATFORM_GATEWAY_CARELOG_BE_URI") { "http://127.0.0.1:${upstream.port()}" }
            registry.add("PLATFORM_GATEWAY_CARELOG_RAG_URI") { "http://127.0.0.1:${upstream.port()}" }
        }

        @JvmStatic
        @AfterAll
        fun stopTestServices() {
            upstream.disposeNow(Duration.ofSeconds(5))
            if (valkey.isRunning) valkey.stop()
        }
    }
}
