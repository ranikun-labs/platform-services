package com.ranikunlabs.platform.gateway.filter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.config.FinanceTrustedContextConfig
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.env.MockEnvironment
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Date
import java.util.Properties

class FinanceTrustedContextGatewayFilterTest {

    private val fixtureProperties = Properties().apply {
        FinanceTrustedContextGatewayFilterTest::class.java
            .getResourceAsStream("/application-finance-trusted-context-reference.properties")!!
            .use(::load)
    }
    private val serviceSecret = fixtureProperties.getProperty("platform.gateway.finance-trusted-context.service-secret")
    private val jwtSecret = fixtureProperties.getProperty("platform.gateway.jwt-verification-secret")
    private val internalSecret = fixtureProperties.getProperty("platform.gateway.internal-secret")
    private val subjectA = "9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
    private val subjectB = "4b7a6711-4a33-4892-9b8a-8730b83a1533"
    private val organizationId = "11111111-1111-1111-1111-111111111111"
    private val publicId = "22222222-2222-2222-2222-222222222222"

    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var jwtFilter: JwtGlobalFilter
    private lateinit var financeFilter: FinanceTrustedContextGatewayFilter
    private lateinit var config: GatewayConfig

    @BeforeEach
    fun setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        config = GatewayConfig(
            internalSecret = internalSecret,
            jwtVerificationSecret = jwtSecret,
            financeTrustedContext = FinanceTrustedContextConfig(enabled = true, serviceSecret = serviceSecret)
        )
        jwtFilter = JwtGlobalFilter(
            JwtVerifier(jwtSecret),
            RedisBlacklistService(redisTemplate),
            config,
            AuthPublicRequestMatcher(),
            OAuthPublicRequestMatcher()
        )
        financeFilter = FinanceTrustedContextGatewayFilter(config, fixtureEnvironment())
    }

    @Test
    fun `verified JWT subjects produce exact Finance vectors and replace every caller supplied context`() {
        val vectorA = capture(subjectA, forgedSubject = subjectB)
        val vectorB = capture(subjectB, forgedSubject = subjectA)

        assertVector(vectorA, subjectA)
        assertVector(vectorB, subjectB)
        writeVectorArtifact(vectorA, vectorB)
    }

    @Test
    fun `current and legacy refresh JWTs are rejected while opaque access subjects remain supported`() {
        val currentRefresh = refreshToken(subjectA, includeJti = true)
        val legacyRefresh = refreshToken(subjectA, includeJti = false)

        listOf("current" to currentRefresh, "legacy" to legacyRefresh).forEach { (_, token) ->
            assertThat(JwtVerifier(jwtSecret).verifyAndGetClaims(token).subject).isEqualTo(subjectA)
            val (exchange, forwarded) = runFinanceRequest(token)

            assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(forwarded).isEmpty()
        }

        val opaqueSubject = "opaque-subject-fixture"
        val accessToken = validToken(opaqueSubject)
        val (accessExchange, accessForwarded) = runFinanceRequest(accessToken)
        assertThat(accessExchange.response.statusCode).isNull()
        assertThat(accessForwarded).hasSize(1)
        assertThat(accessForwarded.single().request.headers.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
            .isEqualTo(canonicalBase64Url(opaqueSubject))
    }

    @Test
    fun `Finance access marker claims require UUID strings and a nonblank role`() {
        val invalidMarkerTokens = listOf(
            accessToken("opaque-subject-fixture", organizationIdClaim = "not-a-uuid"),
            accessToken("opaque-subject-fixture", roleClaim = " \t "),
            accessToken("opaque-subject-fixture", publicIdClaim = 17)
        )

        invalidMarkerTokens.forEach { token ->
            val (exchange, forwarded) = runFinanceRequest(token)
            assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(forwarded).isEmpty()
        }
    }

    @Test
    fun `malformed Finance route is rejected without forwarding`() {
        val invalidRoute = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/finance/journals/$subjectA").build()
        )
        val captured = mutableListOf<ServerWebExchange>()

        financeFilter.filter(invalidRoute, downstream(captured)).block()

        assertThat(invalidRoute.response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `only canonical journal detail PATCH reaches Finance after verified authentication`() {
        val path = "/finance/journals/$subjectA"
        assertThat(FinanceTrustedContextWire.isSupportedRoute(HttpMethod.PATCH, path, path)).isTrue()
        listOf(
            HttpMethod.DELETE to path,
            HttpMethod.PUT to path,
            HttpMethod.PATCH to "/finance/journals",
            HttpMethod.PATCH to "$path/extra",
            HttpMethod.PATCH to "/finance/journals/not-a-uuid"
        ).forEach { (method, candidate) ->
            assertThat(FinanceTrustedContextWire.isSupportedRoute(method, candidate, candidate)).isFalse()
        }
        assertThat(FinanceTrustedContextWire.isSupportedRoute(HttpMethod.PATCH, path, "$path%2Fextra")).isFalse()

        val token = validToken(subjectA)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.PATCH, path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("X-Finance-Subject", "attacker")
                .build()
        )
        val forwarded = mutableListOf<ServerWebExchange>()
        val chain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            financeFilter.filter(verified, downstream(forwarded))
        }
        jwtFilter.filter(exchange, chain).block()
        assertThat(forwarded).hasSize(1)
        assertThat(forwarded.single().request.headers.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
            .isEqualTo(canonicalBase64Url(subjectA))
        assertThat(forwarded.single().request.headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull()

        val unauthenticated = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.PATCH, path).build())
        val rejected = mutableListOf<ServerWebExchange>()
        financeFilter.filter(unauthenticated, downstream(rejected)).block()
        assertThat(unauthenticated.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(rejected).isEmpty()
    }

    @Test
    fun `empty Bearer and missing stable subject are unauthorized only on the Finance reference route`() {
        val emptyBearer = MockServerWebExchange.from(
            MockServerHttpRequest.get("/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                .build()
        )
        val emptyBearerCapture = mutableListOf<ServerWebExchange>()
        val chain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            financeFilter.filter(verified, downstream(emptyBearerCapture))
        }
        jwtFilter.filter(emptyBearer, chain).block()
        assertThat(emptyBearer.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(emptyBearerCapture).isEmpty()

        val noSubjectToken = Jwts.builder()
            .expiration(Date(4102444800000L))
            .signWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8)))
            .compact()
        val noSubject = MockServerWebExchange.from(
            MockServerHttpRequest.get("/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $noSubjectToken")
                .build()
        )
        `when`(redisTemplate.hasKey("blacklist:$noSubjectToken")).thenReturn(Mono.just(false))
        jwtFilter.filter(noSubject, chain).block()
        assertThat(noSubject.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(emptyBearerCapture).isEmpty()
    }

    @Test
    fun `disabled Finance reference path fails closed before any identity or downstream forwarding`() {
        val disabledConfig = GatewayConfig(
            internalSecret = internalSecret,
            jwtVerificationSecret = jwtSecret
        )
        val disabledFilter = JwtGlobalFilter(
            JwtVerifier(jwtSecret),
            RedisBlacklistService(redisTemplate),
            disabledConfig,
            AuthPublicRequestMatcher(),
            OAuthPublicRequestMatcher()
        )
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/finance/journals").build()
        )
        val captured = mutableListOf<ServerWebExchange>()

        disabledFilter.filter(exchange, downstream(captured)).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `blacklist dependency outage returns 503 and does not forward the fixture request`() {
        val token = validToken(subjectA)
        `when`(redisTemplate.hasKey("blacklist:$token"))
            .thenReturn(Mono.error(DataAccessResourceFailureException("Redis unavailable")))
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .build()
        )
        val captured = mutableListOf<ServerWebExchange>()

        jwtFilter.filter(exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            financeFilter.filter(verified, downstream(captured))
        }).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(exchange.response.getBodyAsString().block())
            .isEqualTo("{\"code\":\"identity_verification_unavailable\"}")
        assertThat(captured).isEmpty()
    }

    @Test
    fun `subject and envelope bounds reject malformed or noncanonical identity bytes`() {
        assertThat(FinanceTrustedContextWire.createHeaders(" \t", serviceSecret)).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("x\r\ny", serviceSecret)).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("x\u0000y", serviceSecret)).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("x".repeat(257), serviceSecret)).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("\uD800", serviceSecret)).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("opaque-A", "x".repeat(2049))).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("opaque-A", "x".repeat(32) + ",bad")).isNull()
        assertThat(FinanceTrustedContextWire.createHeaders("opaque-A", "x".repeat(31) + "é")).isNull()
    }

    @Test
    fun `reference is disabled by default and rejects production markers mixed with its fixture profile`() {
        FinanceTrustedContextProfileGuard.validate(FinanceTrustedContextConfig(), MockEnvironment())

        val productionEnvironment = fixtureEnvironment().apply {
            setProperty("platform.metrics.environment", "production")
        }
        assertThatThrownBy {
            FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, productionEnvironment)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot run with a production marker")

        listOf(
            "PLATFORM_ENVIRONMENT" to " prod ",
            "platform.metrics.environment" to " production ",
            "platform.environment" to " PRoD ",
        ).forEach { (property, value) ->
            val paddedProductionMarker = fixtureEnvironment().apply { setProperty(property, value) }
            assertThatThrownBy {
                FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, paddedProductionMarker)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("cannot run with a production marker")
        }

        val paddedProductionProfile = MockEnvironment().apply {
            setActiveProfiles(FINANCE_TRUSTED_CONTEXT_PROFILE, " prod ")
        }
        assertThatThrownBy {
            FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, paddedProductionProfile)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot run with a production marker")

        val enabledWithoutProfile = MockEnvironment()
        assertThatThrownBy {
            FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, enabledWithoutProfile)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("explicit fixture profile")
    }

    private fun capture(verifiedSubject: String, forgedSubject: String): Map<String, String> {
        val token = validToken(verifiedSubject)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))

        val request = MockServerHttpRequest.post("/finance/journals")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(HttpHeaders.COOKIE, "session=fixture-only")
            .header("X-User-Id", forgedSubject)
            .header("X-Organization-Id", "attacker-org")
            .header("X-Role", "ADMIN")
            .header("X-Public-Id", "attacker-public-id")
            .header("X-Gateway-Secret", "attacker-gateway-secret")
            .header("X-Service-Id", "platform-identity")
            .header("X-Service-Secret", "carelog-only-secret")
            .header(FinanceTrustedContextWire.VERSION_HEADER, "attacker-version-1", "1")
            .header(FinanceTrustedContextWire.SERVICE_ID_HEADER, "attacker-service")
            .header(FinanceTrustedContextWire.SERVICE_SECRET_HEADER, "attacker-proof")
            .header(FinanceTrustedContextWire.AUDIENCE_HEADER, "attacker-audience")
            .header(FinanceTrustedContextWire.SUBJECT_HEADER, forgedSubject)
            .header("X-Finance-Attacker-Extra", "must-be-removed")
            .build()
        val exchange = MockServerWebExchange.from(request)
        val captured = mutableListOf<ServerWebExchange>()
        val authenticatedChain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            financeFilter.filter(verified, downstream(captured))
        }

        jwtFilter.filter(exchange, authenticatedChain).block()

        assertThat(exchange.response.statusCode).isNull()
        assertThat(captured).hasSize(1)
        val forwarded = captured.single().request.headers
        val vector = FinanceTrustedContextWire.let { wire ->
            linkedMapOf(
                wire.VERSION_HEADER to captured.single().request.headers.getFirst(wire.VERSION_HEADER)!!,
                wire.SERVICE_ID_HEADER to captured.single().request.headers.getFirst(wire.SERVICE_ID_HEADER)!!,
                wire.SERVICE_SECRET_HEADER to captured.single().request.headers.getFirst(wire.SERVICE_SECRET_HEADER)!!,
                wire.AUDIENCE_HEADER to captured.single().request.headers.getFirst(wire.AUDIENCE_HEADER)!!,
                wire.SUBJECT_HEADER to captured.single().request.headers.getFirst(wire.SUBJECT_HEADER)!!
            )
        }
        assertThat(forwarded.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
            .isEqualTo(canonicalBase64Url(verifiedSubject))
        assertThat(forwarded.keys.filter { it.startsWith("X-Finance-", ignoreCase = true) })
            .containsExactlyInAnyOrderElementsOf(vector.keys)
        assertThat(forwarded).doesNotContainKeys(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.COOKIE,
            "X-User-Id",
            "X-Organization-Id",
            "X-Role",
            "X-Public-Id",
            "X-Gateway-Secret",
            "X-Service-Id",
            "X-Service-Secret",
            "X-Finance-Attacker-Extra"
        )
        return vector
    }

    private fun runFinanceRequest(token: String): Pair<MockServerWebExchange, MutableList<ServerWebExchange>> {
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .build()
        )
        val forwarded = mutableListOf<ServerWebExchange>()
        val financeChain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            financeFilter.filter(verified, downstream(forwarded))
        }
        jwtFilter.filter(exchange, financeChain).block()
        return exchange to forwarded
    }

    private fun refreshToken(subject: String, includeJti: Boolean): String {
        val builder = Jwts.builder()
            .subject(subject)
            .issuedAt(Date(System.currentTimeMillis() - 60_000L))
            .expiration(Date(4102444800000L))
        if (includeJti) builder.id("33333333-3333-3333-3333-333333333333")
        return builder
            .signWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8)))
            .compact()
    }

    private fun accessToken(
        subject: String,
        organizationIdClaim: Any = organizationId,
        roleClaim: Any = "MANAGER",
        publicIdClaim: Any = publicId
    ): String = Jwts.builder()
        .subject(subject)
        .claim("organizationId", organizationIdClaim)
        .claim("role", roleClaim)
        .claim("publicId", publicIdClaim)
        .issuedAt(Date(System.currentTimeMillis() - 60_000L))
        .expiration(Date(4102444800000L))
        .signWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8)))
        .compact()

    private fun validToken(subject: String): String = Jwts.builder()
        .subject(subject)
        .claim("organizationId", organizationId)
        .claim("role", "MANAGER")
        .claim("publicId", publicId)
        .expiration(Date(4102444800000L))
        .signWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8)))
        .compact()

    private fun assertVector(headers: Map<String, String>, expectedSubject: String) {
        assertThat(headers.keys).containsExactly(
            FinanceTrustedContextWire.VERSION_HEADER,
            FinanceTrustedContextWire.SERVICE_ID_HEADER,
            FinanceTrustedContextWire.SERVICE_SECRET_HEADER,
            FinanceTrustedContextWire.AUDIENCE_HEADER,
            FinanceTrustedContextWire.SUBJECT_HEADER
        )
        assertThat(headers[FinanceTrustedContextWire.VERSION_HEADER]).isEqualTo("1")
        assertThat(headers[FinanceTrustedContextWire.SERVICE_ID_HEADER]).isEqualTo("gateway-app")
        assertThat(headers[FinanceTrustedContextWire.SERVICE_SECRET_HEADER]).isEqualTo(serviceSecret)
        assertThat(headers[FinanceTrustedContextWire.AUDIENCE_HEADER]).isEqualTo("finance-journal")
        assertThat(headers[FinanceTrustedContextWire.SUBJECT_HEADER])
            .isEqualTo(canonicalBase64Url(expectedSubject))
    }

    private fun downstream(captured: MutableList<ServerWebExchange>) =
        org.springframework.cloud.gateway.filter.GatewayFilterChain { forwarded ->
            captured += forwarded
            Mono.empty()
        }

    private fun fixtureEnvironment() = MockEnvironment().apply {
        setActiveProfiles(FINANCE_TRUSTED_CONTEXT_PROFILE)
        setProperty("platform.metrics.environment", "local")
        setProperty("PLATFORM_ENVIRONMENT", "local")
    }

    private fun writeVectorArtifact(vectorA: Map<String, String>, vectorB: Map<String, String>) {
        val output = System.getenv("D1_FIXTURE_VECTORS_PATH")?.takeIf(String::isNotBlank) ?: return
        val artifact = linkedMapOf(
            "contract_sha256" to "3b4138cd0cc57fa56f8607be3fafc4255e5dc70f7c311beb424fa739545e6326",
            "producer_kind" to "REAL_PRODUCER_FIXTURE",
            "generation" to linkedMapOf(
                "gateway_source_sha" to System.getenv("GATEWAY_PRODUCER_SOURCE_SHA"),
                "test_class" to "com.ranikunlabs.platform.gateway.filter.FinanceTrustedContextGatewayFilterTest",
                "verification_boundary" to "Real JwtVerifier signature validation + mocked Redis blacklist=false; no live Identity issuer, account, or Finance upstream."
            ),
            "vectors" to listOf(
                linkedMapOf("id" to "A", "expected_subject" to subjectA, "headers" to vectorA),
                linkedMapOf("id" to "B", "expected_subject" to subjectB, "headers" to vectorB)
            )
        )
        val path = Path.of(output)
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            jacksonObjectMapper().writeValueAsString(artifact) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun canonicalBase64Url(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
