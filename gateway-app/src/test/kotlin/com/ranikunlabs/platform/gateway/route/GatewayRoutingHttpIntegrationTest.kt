package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
        "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:39155",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:39156",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39156",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=http-routing-test-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-http-routing-test-secret-key"
    ]
)
@AutoConfigureWebTestClient
@Import(GatewayRoutingHttpIntegrationTest.StubBlacklistConfiguration::class)
class GatewayRoutingHttpIntegrationTest(
    @Autowired private val webTestClient: WebTestClient
) {

    @BeforeEach
    fun resetFixtures() {
        authInvocations.set(0)
        carelogInvocations.set(0)
        authHeaders.set(emptyMap())
        carelogHeaders.set(emptyMap())
    }

    @Test
    fun `login reaches the Auth upstream and strips spoofed identity headers`() {
        webTestClient.post()
            .uri("/api/v1/auth/login")
            .headers { headers -> spoofedHeaders.forEach { (name, value) -> headers.set(name, value) } }
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("AUTH")

        assertThat(authInvocations).hasValue(1)
        assertThat(carelogInvocations).hasValue(0)
        assertThat(authHeaders.get())
            .containsEntry("X-Gateway-Secret", internalSecret)
            .doesNotContainKeys(
                "X-User-Id",
                "X-Organization-Id",
                "X-Role",
                "X-Public-Id",
                "X-Platform-Service-Token"
            )
    }

    @Test
    fun `protected Product traffic reaches the Carelog upstream with trusted claims`() {
        webTestClient.get()
            .uri("/api/v1/customers/test")
            .headers { headers ->
                headers.set("Authorization", "Bearer ${validToken()}")
                spoofedHeaders.forEach { (name, value) -> headers.set(name, value) }
            }
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("CARELOG")

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(1)
        assertThat(carelogHeaders.get())
            .containsEntry("X-User-Id", accountId)
            .containsEntry("X-Organization-Id", organizationId)
            .containsEntry("X-Role", "MANAGER")
            .containsEntry("X-Public-Id", publicId)
            .containsEntry("X-Gateway-Secret", internalSecret)
            .doesNotContainEntry("X-Platform-Service-Token", spoofedPlatformToken)
    }

    @Test
    fun `internal namespace is rejected before either upstream without authorization`() {
        webTestClient.get()
            .uri("/api/v1/internal/identity/accounts/$accountId/claims")
            .exchange()
            .expectStatus().isNotFound

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @Test
    fun `internal namespace is rejected before either upstream with a valid user token`() {
        webTestClient.get()
            .uri("/api/v1/internal/identity/accounts/$accountId/claims")
            .header("Authorization", "Bearer ${validToken()}")
            .exchange()
            .expectStatus().isNotFound

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @Test
    fun `logout is protected and does not reach an upstream without a bearer token`() {
        webTestClient.post()
            .uri("/api/v1/auth/logout")
            .exchange()
            .expectStatus().isUnauthorized

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @Test
    fun `authenticated logout reaches the Auth upstream with the original bearer header`() {
        val token = validToken()

        webTestClient.post()
            .uri("/api/v1/auth/logout")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("AUTH")

        assertThat(authInvocations).hasValue(1)
        assertThat(carelogInvocations).hasValue(0)
        assertThat(authHeaders.get()).containsEntry("Authorization", "Bearer $token")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class StubBlacklistConfiguration {

        @Bean
        @Primary
        fun stubBlacklistService(): RedisBlacklistService {
            val redisTemplate = org.mockito.Mockito.mock(ReactiveStringRedisTemplate::class.java)
            org.mockito.Mockito.`when`(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(false))
            return RedisBlacklistService(redisTemplate)
        }
    }

    private fun validToken(): String = Jwts.builder()
        .subject(accountId)
        .claim("organizationId", organizationId)
        .claim("role", "MANAGER")
        .claim("publicId", publicId)
        .expiration(Date(4102444800000L))
        .signWith(key)
        .compact()

    private companion object {
        const val accountId = "9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
        const val organizationId = "11111111-1111-1111-1111-111111111111"
        const val publicId = "22222222-2222-2222-2222-222222222222"
        const val internalSecret = "http-routing-test-secret"
        const val spoofedPlatformToken = "spoofed-platform-token"
        val key = Keys.hmacShaKeyFor(
            "platform-gateway-http-routing-test-secret-key"
                .toByteArray(StandardCharsets.UTF_8)
        )
        val spoofedHeaders = mapOf(
            "X-User-Id" to "spoofed-user",
            "X-Organization-Id" to "spoofed-org",
            "X-Role" to "spoofed-role",
            "X-Public-Id" to "spoofed-public",
            "X-Gateway-Secret" to "spoofed-secret",
            "X-Platform-Service-Token" to spoofedPlatformToken
        )
        val authInvocations = AtomicInteger()
        val carelogInvocations = AtomicInteger()
        val authHeaders = AtomicReference<Map<String, String>>(emptyMap())
        val carelogHeaders = AtomicReference<Map<String, String>>(emptyMap())

        val authUpstream: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(39155)
            .handle { request, response ->
                authInvocations.incrementAndGet()
                authHeaders.set(captureHeaders(request))
                response.status(200).sendString(Mono.just("AUTH"))
            }
            .bindNow()

        val carelogUpstream: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(39156)
            .handle { request, response ->
                carelogInvocations.incrementAndGet()
                carelogHeaders.set(captureHeaders(request))
                response.status(200).sendString(Mono.just("CARELOG"))
            }
            .bindNow()

        @JvmStatic
        @AfterAll
        fun stopUpstreams() {
            authUpstream.disposeNow()
            carelogUpstream.disposeNow()
        }

        private fun captureHeaders(request: reactor.netty.http.server.HttpServerRequest): Map<String, String> =
            listOf(
                "Authorization",
                "X-User-Id",
                "X-Organization-Id",
                "X-Role",
                "X-Public-Id",
                "X-Gateway-Secret",
                "X-Platform-Service-Token"
            ).mapNotNull { name ->
                request.requestHeaders().get(name)?.let { value -> name to value }
            }.toMap()
    }
}
