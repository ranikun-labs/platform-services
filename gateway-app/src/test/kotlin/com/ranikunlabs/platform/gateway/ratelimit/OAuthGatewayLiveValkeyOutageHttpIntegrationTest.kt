package com.ranikunlabs.platform.gateway.ratelimit

import com.ranikunlabs.platform.gateway.GatewayApplication
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import reactor.netty.http.server.HttpServer

class OAuthGatewayLiveValkeyOutageHttpIntegrationTest {

    @Test
    fun `live Valkey outage returns 503 for both OAuth routes without calling upstream`() {
        val upstreamInvocations = AtomicInteger()
        val upstream = HttpServer.create()
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
        var gatewayContext: ConfigurableApplicationContext? = null

        try {
            valkey.start()
            gatewayContext = SpringApplicationBuilder(GatewayApplication::class.java)
                .web(WebApplicationType.REACTIVE)
                .run(
                    "--PLATFORM_GATEWAY_PORT=0",
                    "--PLATFORM_GATEWAY_MANAGEMENT_PORT=0",
                    "--PLATFORM_GATEWAY_REDIS_HOST=${valkey.host}",
                    "--PLATFORM_GATEWAY_REDIS_PORT=${valkey.getMappedPort(6379)}",
                    "--PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                    "--PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:${upstream.port()}",
                    "--PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:${upstream.port()}",
                    "--PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:${upstream.port()}",
                    "--PLATFORM_GATEWAY_INTERNAL_SECRET=integration-secret",
                    "--PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=" +
                        "platform-gateway-integration-secret-key-long-enough",
                    "--spring.data.redis.connect-timeout=500ms",
                    "--spring.data.redis.timeout=2s",
                    "--server.address=127.0.0.1"
                )
            val webServerContext = gatewayContext as ReactiveWebServerApplicationContext
            val webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:${webServerContext.webServer.port}")
                .responseTimeout(Duration.ofSeconds(5))
                .build()

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
        } finally {
            gatewayContext?.close()
            if (valkey.isRunning) valkey.stop()
            upstream.disposeNow(Duration.ofSeconds(5))
        }
    }

    private companion object {
        const val CLIENT_IP = "203.0.113.10"
        val oauthPaths = listOf(
            "/api/v1/auth/oauth/kakao/authorization",
            "/api/v1/auth/oauth/kakao/exchange"
        )
    }
}
