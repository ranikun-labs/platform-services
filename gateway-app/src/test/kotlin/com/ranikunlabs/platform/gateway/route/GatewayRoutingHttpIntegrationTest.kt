package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.filter.FinanceTrustedContextGatewayFilter
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import com.ranikunlabs.platform.gateway.ratelimit.OAuthFailClosedRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpMethod
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
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39157",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=http-routing-test-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-http-routing-test-secret-key"
    ]
)
@AutoConfigureWebTestClient
@Import(GatewayRoutingHttpIntegrationTest.TestInfrastructureConfiguration::class)
class GatewayRoutingHttpIntegrationTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @MockBean(name = "oauthFailClosedRateLimiter")
    private lateinit var oauthFailClosedRateLimiter: OAuthFailClosedRateLimiter

    @BeforeEach
    fun resetFixtures() {
        authInvocations.set(0)
        carelogInvocations.set(0)
        authHeaders.set(emptyMap())
        carelogHeaders.set(emptyMap())
        org.mockito.Mockito.`when`(
            oauthFailClosedRateLimiter.isAllowed(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
            )
        ).thenReturn(Mono.just(org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(true, emptyMap())))
    }

    @Test
    fun `default Gateway context does not register the Finance reference filter`() {
        assertThat(applicationContext.getBeansOfType(FinanceTrustedContextGatewayFilter::class.java)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/auth/login",
        "/api/v1/auth/refresh"
    ])
    fun `exact public Auth requests reach Identity and strip service headers`(path: String) {
        webTestClient.post()
            .uri(path)
            .header("X-Forwarded-For", "203.0.113.10")
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
                "X-Service-Id",
                "X-Service-Secret"
            )
    }

    @Test
    fun `logout without a bearer token is rejected before any upstream`() {
        webTestClient.post()
            .uri("/api/v1/auth/logout")
            .exchange()
            .expectStatus().isUnauthorized

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @Test
    fun `authenticated logout reaches Identity with the original bearer header`() {
        val token = validToken()

        webTestClient.post()
            .uri("/api/v1/auth/logout")
            .header("Authorization", "Bearer $token")
            .headers { headers -> spoofedHeaders.forEach { (name, value) -> headers.set(name, value) } }
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("AUTH")

        assertThat(authInvocations).hasValue(1)
        assertThat(carelogInvocations).hasValue(0)
        assertThat(authHeaders.get())
            .containsEntry("Authorization", "Bearer $token")
            .containsEntry("X-Gateway-Secret", internalSecret)
            .doesNotContainKeys("X-Service-Id", "X-Service-Secret")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/auth/oauth/kakao/authorization",
        "/api/v1/auth/oauth/kakao/exchange"
    ])
    fun `Kakao OAuth requests reach Identity and strip service headers`(path: String) {
        webTestClient.post()
            .uri(path)
            .header("X-Forwarded-For", "203.0.113.10")
            .headers { headers -> spoofedHeaders.forEach { (name, value) -> headers.set(name, value) } }
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("AUTH")

        assertThat(authInvocations).hasValue(1)
        assertThat(carelogInvocations).hasValue(0)
        assertThat(authHeaders.get())
            .containsEntry("X-Gateway-Secret", internalSecret)
            .doesNotContainKeys("X-Service-Id", "X-Service-Secret")
    }

    @Test
    fun `unknown Auth namespace request with a bearer token reaches Identity and not Carelog`() {
        webTestClient.post()
            .uri("/api/v1/auth/something")
            .header("Authorization", "Bearer ${validToken()}")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("AUTH")

        assertThat(authInvocations).hasValue(1)
        assertThat(carelogInvocations).hasValue(0)
    }

    @Test
    fun `protected Product traffic reaches Carelog with trusted claims and no service headers`() {
        webTestClient.get()
            .uri("/api/v1/customers/test")
            .header("Authorization", "Bearer ${validToken()}")
            .headers { headers -> spoofedHeaders.forEach { (name, value) -> headers.set(name, value) } }
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
            .doesNotContainKeys("X-Service-Id", "X-Service-Secret")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "POST /api/v1/auth/login-extra",
        "POST /api/v1/auth/login/foo",
        "POST /api/v1/auth/refresh-extra",
        "POST /api/v1/auth/refresh/foo",
        "OPTIONS /api/v1/auth/login-extra",
        "GET /api/v1/auth/login",
        "PUT /api/v1/auth/refresh"
    ])
    fun `non exact Auth variants remain protected`(request: String) {
        val (method, path) = request.split(" ", limit = 2)

        webTestClient.method(HttpMethod.valueOf(method))
            .uri(path)
            .exchange()
            .expectStatus().isUnauthorized

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/internal",
        "/api/v1/internal/identity/claims/9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
    ])
    fun `public internal namespace is forbidden before routing without authentication`(path: String) {
        webTestClient.get()
            .uri(path)
            .header("X-Service-Id", "platform-identity")
            .header("X-Service-Secret", "spoofed-service-secret")
            .exchange()
            .expectStatus().isForbidden

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/internal",
        "/api/v1/internal/identity/claims/9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
    ])
    fun `public internal namespace is forbidden before routing with a valid bearer token`(path: String) {
        webTestClient.get()
            .uri(path)
            .header("Authorization", "Bearer ${validToken()}")
            .header("X-Service-Id", "platform-identity")
            .header("X-Service-Secret", "spoofed-service-secret")
            .exchange()
            .expectStatus().isForbidden

        assertThat(authInvocations).hasValue(0)
        assertThat(carelogInvocations).hasValue(0)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestInfrastructureConfiguration {

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
            "X-Service-Id" to "spoofed-service",
            "X-Service-Secret" to "spoofed-service-secret"
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
                "X-Service-Id",
                "X-Service-Secret"
            ).mapNotNull { name ->
                request.requestHeaders().get(name)?.let { value -> name to value }
            }.toMap()
    }
}
