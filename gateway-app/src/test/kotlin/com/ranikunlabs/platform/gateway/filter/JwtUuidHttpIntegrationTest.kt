package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.GatewayApplication
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
        "PLATFORM_GATEWAY_REDIS_PORT=1",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:39154",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39154",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=http-http-test-internal-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-http-regression-secret-key-0123456789"
    ]
)
@AutoConfigureWebTestClient
@Import(JwtUuidHttpIntegrationTest.StubBlacklistConfiguration::class)
class JwtUuidHttpIntegrationTest(
    @Autowired private val webTestClient: WebTestClient
) {

    @BeforeEach
    fun resetFixture() {
        upstreamInvocations.set(0)
        capturedHeaders.set(emptyMap())
    }

    @Test
    fun `signed UUID subject reaches the Carelog-like upstream as the owned user header`() {
        val token = Jwts.builder()
            .subject(accountId)
            .claim("organizationId", organizationId)
            .claim("role", "MANAGER")
            .claim("publicId", publicId)
            .expiration(Date(4102444800000L))
            .signWith(key)
            .compact()

        webTestClient.get()
            .uri("/api/v1/account/me")
            .header("Authorization", "Bearer $token")
            .header("X-User-Id", "spoofed-user")
            .header("X-Organization-Id", "spoofed-org")
            .header("X-Role", "spoofed-role")
            .header("X-Public-Id", "spoofed-public")
            .header("X-Gateway-Secret", "spoofed-secret")
            .exchange()
            .expectStatus().isOk

        assertThat(upstreamInvocations).hasValue(1)
        assertThat(capturedHeaders.get()).containsEntry("X-User-Id", accountId)
        assertThat(capturedHeaders.get()).containsEntry("X-Organization-Id", organizationId)
        assertThat(capturedHeaders.get()).containsEntry("X-Role", "MANAGER")
        assertThat(capturedHeaders.get()).containsEntry("X-Public-Id", publicId)
        assertThat(capturedHeaders.get()).containsEntry("X-Gateway-Secret", internalSecret)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class StubBlacklistConfiguration {

        @Bean
        @Primary
        fun stubBlacklistService(): RedisBlacklistService {
            val redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
            `when`(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(false))
            return RedisBlacklistService(redisTemplate)
        }
    }

    private companion object {
        const val accountId = "9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
        const val organizationId = "11111111-1111-1111-1111-111111111111"
        const val publicId = "22222222-2222-2222-2222-222222222222"
        const val internalSecret = "http-http-test-internal-secret"
        val key = Keys.hmacShaKeyFor(
            "platform-gateway-http-regression-secret-key-0123456789"
                .toByteArray(StandardCharsets.UTF_8)
        )
        val upstreamInvocations = AtomicInteger()
        val capturedHeaders = AtomicReference<Map<String, String>>(emptyMap())
        val upstream: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(39154)
            .handle { request, response ->
                upstreamInvocations.incrementAndGet()
                capturedHeaders.set(
                    listOf(
                        "X-User-Id",
                        "X-Organization-Id",
                        "X-Role",
                        "X-Public-Id",
                        "X-Gateway-Secret"
                    ).mapNotNull { name -> request.requestHeaders().get(name)?.let { name to it } }.toMap()
                )
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
