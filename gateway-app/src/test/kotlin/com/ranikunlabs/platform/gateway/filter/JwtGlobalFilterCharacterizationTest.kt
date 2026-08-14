package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.util.Date

class JwtGlobalFilterCharacterizationTest {

    private val secret = "platform-gateway-characterization-test-secret-key-0123456789"
    private val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    private val internalSecret = "test-internal-secret"
    private val organizationId = "11111111-1111-1111-1111-111111111111"
    private val publicId = "22222222-2222-2222-2222-222222222222"
    private val emailSubject = "manager@example.com"

    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var filter: JwtGlobalFilter
    private var capturedExchange: ServerWebExchange? = null
    private val chain = GatewayFilterChain { exchange ->
        capturedExchange = exchange
        Mono.empty()
    }

    @BeforeEach
    fun setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        val config = GatewayConfig(
            publicPaths = listOf(
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/users/managers"
            ),
            internalSecret = internalSecret,
            jwtVerificationSecret = secret
        )
        filter = JwtGlobalFilter(
            JwtVerifier(secret),
            RedisBlacklistService(redisTemplate),
            config,
            OAuthPublicRequestMatcher()
        )
        capturedExchange = null
    }

    @Test
    fun `configured public prefix preserves startsWith behavior and injects only gateway secret`() {
        val exchange = exchange(
            HttpMethod.POST,
            "/api/v1/auth/login-extra",
            mapOf("X-User-Id" to "spoofed-user", "X-Gateway-Secret" to "spoofed-secret")
        )

        filter.filter(exchange, chain).block()

        assertThat(headerOf("X-Gateway-Secret")).isEqualTo(internalSecret)
        assertThat(headerOf("X-User-Id")).isNull()
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `OPTIONS on a configured public prefix retains the current public-path behavior`() {
        val exchange = exchange(HttpMethod.OPTIONS, "/api/v1/auth/login")

        filter.filter(exchange, chain).block()

        assertThat(headerOf("X-Gateway-Secret")).isEqualTo(internalSecret)
        assertThat(exchange.response.statusCode).isNull()
        verifyNoInteractions(redisTemplate)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/auth/oauth/kakao/authorization",
        "/api/v1/auth/oauth/kakao/exchange"
    ])
    fun `exact OAuth POST routes bypass JWT and remove spoofed identity headers`(path: String) {
        val exchange = exchange(
            HttpMethod.POST,
            path,
            mapOf(
                "X-User-Id" to "spoofed-user",
                "X-Organization-Id" to "spoofed-org",
                "X-Role" to "spoofed-role",
                "X-Public-Id" to "spoofed-public",
                "X-Gateway-Secret" to "spoofed-secret"
            )
        )

        filter.filter(exchange, chain).block()

        assertThat(headerOf("X-Gateway-Secret")).isEqualTo(internalSecret)
        assertThat(headerOf("X-User-Id")).isNull()
        assertThat(headerOf("X-Organization-Id")).isNull()
        assertThat(headerOf("X-Role")).isNull()
        assertThat(headerOf("X-Public-Id")).isNull()
        verifyNoInteractions(redisTemplate)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "GET /api/v1/auth/oauth/kakao/authorization",
        "PUT /api/v1/auth/oauth/kakao/exchange",
        "POST /api/v1/auth/oauth/kakao/authorization/extra",
        "POST /api/v1/auth/oauth/kakao/link"
    ])
    fun `non exact OAuth requests remain protected`(request: String) {
        val (method, path) = request.split(" ", limit = 2)
        val exchange = exchange(HttpMethod.valueOf(method), path)

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(capturedExchange).isNull()
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `missing authorization returns unauthorized`() {
        val exchange = exchange(HttpMethod.GET, "/api/v1/patients")

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(capturedExchange).isNull()
    }

    @Test
    fun `OPTIONS on a protected path remains unauthorized without special handling`() {
        val exchange = exchange(HttpMethod.OPTIONS, "/api/v1/patients")

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(capturedExchange).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["Bearer", "bearer valid-token", "NotBearer x", " Bearer x"])
    fun `malformed bearer returns unauthorized`(authorization: String) {
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf("Authorization" to authorization)
        )

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(capturedExchange).isNull()
    }

    @Test
    fun `empty bearer preserves IllegalArgumentException propagation`() {
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf("Authorization" to "Bearer ")
        )

        assertThatThrownBy { filter.filter(exchange, chain) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(capturedExchange).isNull()
    }

    @Test
    fun `blacklisted token returns unauthorized`() {
        val token = validToken(emailSubject)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(true))
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf("Authorization" to "Bearer $token")
        )

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(capturedExchange).isNull()
    }

    @Test
    fun `blacklist failure propagates without downstream`() {
        val token = validToken(emailSubject)
        val failure = RuntimeException("redis down")
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.error(failure))
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf("Authorization" to "Bearer $token")
        )

        assertThatThrownBy { filter.filter(exchange, chain).block() }
            .isSameAs(failure)
        assertThat(capturedExchange).isNull()
    }

    @Test
    fun `invalid JWT returns unauthorized`() {
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf("Authorization" to "Bearer not-a-jwt")
        )

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(capturedExchange).isNull()
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `valid token injects claims and replaces spoofed trusted headers`() {
        val token = validToken(emailSubject)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf(
                "Authorization" to "Bearer $token",
                "X-User-Id" to "attacker",
                "X-Organization-Id" to "evil-org",
                "X-Role" to "ADMIN",
                "X-Public-Id" to "evil-public",
                "X-Gateway-Secret" to "leaked-secret"
            )
        )

        filter.filter(exchange, chain).block()

        assertThat(headerOf("X-User-Id")).isEqualTo(emailSubject)
        assertThat(headerOf("X-Organization-Id")).isEqualTo(organizationId)
        assertThat(headerOf("X-Role")).isEqualTo("MANAGER")
        assertThat(headerOf("X-Public-Id")).isEqualTo(publicId)
        assertThat(headerOf("X-Gateway-Secret")).isEqualTo(internalSecret)
    }

    @Test
    fun `missing optional claims preserve empty role and omitted optional headers`() {
        val token = Jwts.builder()
            .subject(emailSubject)
            .expiration(Date(4102444800000L))
            .signWith(key)
            .compact()
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/patients",
            mapOf("Authorization" to "Bearer $token")
        )

        filter.filter(exchange, chain).block()

        assertThat(headerOf("X-User-Id")).isEqualTo(emailSubject)
        assertThat(headerOf("X-Organization-Id")).isNull()
        assertThat(headerOf("X-Role")).isEqualTo("")
        assertThat(headerOf("X-Public-Id")).isNull()
        assertThat(headerOf("X-Gateway-Secret")).isEqualTo(internalSecret)
    }

    @Test
    fun `UUID accountId subject reaches downstream as X-User-Id`() {
        val accountId = "9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
        val token = validToken(accountId)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val downstreamFixture = GatewayFilterChain { downstreamExchange ->
            assertThat(downstreamExchange.request.headers.getFirst("X-User-Id")).isEqualTo(accountId)
            Mono.empty()
        }
        val exchange = exchange(
            HttpMethod.GET,
            "/api/v1/account/me",
            mapOf("Authorization" to "Bearer $token")
        )

        filter.filter(exchange, downstreamFixture).block()
    }

    private fun validToken(subject: String): String = Jwts.builder()
        .subject(subject)
        .claim("organizationId", organizationId)
        .claim("role", "MANAGER")
        .claim("publicId", publicId)
        .expiration(Date(4102444800000L))
        .signWith(key)
        .compact()

    private fun exchange(
        method: HttpMethod,
        path: String,
        headers: Map<String, String> = emptyMap()
    ): MockServerWebExchange {
        val builder = MockServerHttpRequest.method(method, path)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return MockServerWebExchange.from(builder.build())
    }

    private fun headerOf(name: String): String? =
        capturedExchange!!.request.headers.getFirst(name)
}
