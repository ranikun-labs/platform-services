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
            MockServerHttpRequest.method(HttpMethod.POST, "/api/finance/journals/$subjectA").build()
        )
        val captured = mutableListOf<ServerWebExchange>()

        financeFilter.filter(invalidRoute, downstream(captured)).block()

        assertThat(invalidRoute.response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `verified auth forwards retrospective writes only for the exact collection path`() {
        val collection = "/api/finance/journals/$subjectA/retrospectives"
        val detail = "$collection/$subjectA"
        val token = validToken(subjectA)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        for ((method, path) in listOf(HttpMethod.GET to collection, HttpMethod.POST to collection, HttpMethod.GET to detail)) {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer $token").build()
            )
            val forwarded = mutableListOf<ServerWebExchange>()
            val chain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
                financeFilter.filter(verified, downstream(forwarded))
            }
            jwtFilter.filter(exchange, chain).block()
            assertThat(forwarded).hasSize(1)
            assertThat(forwarded.single().request.headers.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
                .isEqualTo(canonicalBase64Url(subjectA))
        }
        assertThat(FinanceTrustedContextWire.isSupportedRoute(HttpMethod.POST, detail, detail)).isFalse()
        assertThat(FinanceTrustedContextWire.isSupportedRoute(HttpMethod.DELETE, detail, detail)).isFalse()
        assertThat(FinanceTrustedContextWire.isSupportedRoute(HttpMethod.GET, detail, "$detail%2F")).isFalse()
    }

    @Test
    fun `only canonical journal detail PATCH reaches Finance after verified authentication`() {
        val path = "/api/finance/journals/$subjectA"
        assertThat(FinanceTrustedContextWire.isSupportedRoute(HttpMethod.PATCH, path, path)).isTrue()
        listOf(
            HttpMethod.DELETE to path,
            HttpMethod.PUT to path,
            HttpMethod.PATCH to "/api/finance/journals",
            HttpMethod.PATCH to "$path/extra",
            HttpMethod.PATCH to "/api/finance/journals/not-a-uuid"
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
            MockServerHttpRequest.get("/api/finance/journals")
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
            MockServerHttpRequest.get("/api/finance/journals")
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
            MockServerHttpRequest.get("/api/finance/journals").build()
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
            MockServerHttpRequest.get("/api/finance/journals")
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

    @Test
    fun `dev profile activates trusted context only with the explicit enable flag`() {
        val devEnvironment = devEnvironment()
        FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, devEnvironment)

        val devFilter = FinanceTrustedContextGatewayFilter(config, devEnvironment)
        val token = validToken(subjectA)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .build()
        )
        val forwarded = mutableListOf<ServerWebExchange>()
        jwtFilter.filter(exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            devFilter.filter(verified, downstream(forwarded))
        }).block()

        assertThat(exchange.response.statusCode).isNull()
        assertThat(forwarded).hasSize(1)
        assertVector(forwardedFinanceHeaders(forwarded.single()), subjectA)
    }

    @Test
    fun `dev profile without the enable flag keeps Finance routes 404 at both gates`() {
        val disabled = GatewayConfig(internalSecret = internalSecret, jwtVerificationSecret = jwtSecret)
        FinanceTrustedContextProfileGuard.validate(disabled.financeTrustedContext, devEnvironment())

        val token = validToken(subjectA)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val disabledJwt = JwtGlobalFilter(
            JwtVerifier(jwtSecret), RedisBlacklistService(redisTemplate), disabled,
            AuthPublicRequestMatcher(), OAuthPublicRequestMatcher()
        )
        val viaJwt = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/finance/journals").header(HttpHeaders.AUTHORIZATION, "Bearer $token").build()
        )
        val forwardedJwt = mutableListOf<ServerWebExchange>()
        disabledJwt.filter(viaJwt, downstream(forwardedJwt)).block()
        assertThat(viaJwt.response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(forwardedJwt).isEmpty()

        // Even if the filter bean is loaded under dev, it must not forward while the flag is off.
        val devFilter = FinanceTrustedContextGatewayFilter(disabled, devEnvironment())
        val viaFilter = MockServerWebExchange.from(MockServerHttpRequest.get("/api/finance/journals").build())
        val forwardedFilter = mutableListOf<ServerWebExchange>()
        devFilter.filter(viaFilter, downstream(forwardedFilter)).block()
        assertThat(viaFilter.response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(forwardedFilter).isEmpty()
    }

    @Test
    fun `trusted context fails startup for enable flag without fixture or dev profile and for production markers`() {
        // Flag without an explicit profile.
        assertThatThrownBy {
            FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, MockEnvironment())
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("explicit fixture profile")

        // Fixture profile without the flag.
        assertThatThrownBy {
            FinanceTrustedContextProfileGuard.validate(FinanceTrustedContextConfig(), fixtureEnvironment())
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("requires")

        // Dev + flag + prod markers fail, for each marker source and the prod profile.
        val prodMarkers = listOf(
            "PLATFORM_ENVIRONMENT" to "prod",
            "platform.metrics.environment" to "production",
            "platform.environment" to " PRoD "
        )
        prodMarkers.forEach { (property, value) ->
            val env = devEnvironment().apply { setProperty(property, value) }
            assertThatThrownBy {
                FinanceTrustedContextProfileGuard.validate(config.financeTrustedContext, env)
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("production marker")
        }
        assertThatThrownBy {
            FinanceTrustedContextProfileGuard.validate(
                config.financeTrustedContext,
                MockEnvironment().apply { setActiveProfiles(FINANCE_DEV_PROFILE, "production") }
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("production marker")

        // Plain production boot without the flag, the dev profile, or the fixture is unaffected.
        FinanceTrustedContextProfileGuard.validate(
            FinanceTrustedContextConfig(),
            MockEnvironment().apply {
                setActiveProfiles("production")
                setProperty("PLATFORM_ENVIRONMENT", "prod")
            }
        )
    }

    @Test
    fun `caller supplied identity and Finance headers are replaced on Finance and Carelog routes`() {
        val forgedHeaders = mapOf(
            "X-User-Id" to "attacker-user",
            "X-Role" to "ADMIN",
            "X-Organization-Id" to "99999999-9999-9999-9999-999999999999",
            "X-Public-Id" to "88888888-8888-8888-8888-888888888888",
            "X-Gateway-Secret" to "forged-gateway-secret",
            "X-Service-Id" to "forged-service",
            "X-Service-Secret" to "forged-service-secret",
            "X-Crm-User-Id" to "forged-crm",
            "X-Identity-User-Id" to "forged-identity",
            "X-Finance-Subject" to canonicalBase64Url("attacker"),
            "X-Finance-Service-Secret" to "forged-finance-secret",
            "X-Finance-Service-Id" to "forged-finance-service",
            "X-Finance-Audience" to "forged-audience",
            "X-Finance-Context-Version" to "9",
            "X-Finance-Extra" to "unexpected",
            "Cookie" to "session=forged"
        )
        val token = validToken(subjectA)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))

        // Finance route: verified subject and configured proof only; no caller identity or cookie survives.
        val financeBuilder = MockServerHttpRequest.get("/api/finance/journals")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        forgedHeaders.forEach { (name, value) -> financeBuilder.header(name, value) }
        val finance = MockServerWebExchange.from(financeBuilder.build())
        val financeForwarded = mutableListOf<ServerWebExchange>()
        jwtFilter.filter(finance, org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            financeFilter.filter(verified, downstream(financeForwarded))
        }).block()
        assertThat(financeForwarded).hasSize(1)
        val financeOut = financeForwarded.single().request.headers
        assertVector(forwardedFinanceHeaders(financeForwarded.single()), subjectA)
        listOf(
            HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE, "X-User-Id", "X-Role", "X-Organization-Id",
            "X-Public-Id", "X-Gateway-Secret", "X-Service-Id", "X-Service-Secret", "X-Crm-User-Id",
            "X-Identity-User-Id", "X-Finance-Extra"
        ).forEach { name -> assertThat(financeOut.getFirst(name)).`as`(name).isNull() }

        // Carelog (non-Finance) route: identity headers are rebuilt from the verified JWT claims only.
        val carelog = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/users/me").apply {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                forgedHeaders.forEach { (name, value) -> header(name, value) }
            }.build()
        )
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val carelogForwarded = mutableListOf<ServerWebExchange>()
        jwtFilter.filter(carelog, downstream(carelogForwarded)).block()
        assertThat(carelogForwarded).hasSize(1)
        val carelogOut = carelogForwarded.single().request.headers
        assertThat(carelogOut.getFirst("X-User-Id")).isEqualTo(subjectA)
        assertThat(carelogOut.getFirst("X-Organization-Id")).isEqualTo(organizationId)
        assertThat(carelogOut.getFirst("X-Role")).isEqualTo("MANAGER")
        assertThat(carelogOut.getFirst("X-Public-Id")).isEqualTo(publicId)
        assertThat(carelogOut.getFirst("X-Gateway-Secret")).isEqualTo(internalSecret)
        listOf("X-Finance-Subject", "X-Finance-Service-Secret", "X-Service-Id", "X-Service-Secret", "X-Finance-Extra")
            .forEach { name -> assertThat(carelogOut.getFirst(name)).`as`(name).isNull() }
    }

    @Test
    fun `deleted list and individual delete restore preserve body and verified identity`() {
        val routes = listOf(
            HttpMethod.GET to "/api/finance/journals/deleted",
            HttpMethod.POST to "/api/finance/journals/$subjectA/delete",
            HttpMethod.POST to "/api/finance/journals/$subjectA/restore"
        )
        routes.forEach { (method, path) ->
            val token = validToken(subjectA)
            `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
            val body = if (method == HttpMethod.POST) "{\"expectedRevision\":7}" else ""
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("X-User-Id", subjectB)
                    .header(FinanceTrustedContextWire.SUBJECT_HEADER, subjectB)
                    .header("X-Finance-Attacker-Extra", "must-be-removed")
                    .body(body)
            )
            val forwarded = mutableListOf<ServerWebExchange>()
            jwtFilter.filter(exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
                financeFilter.filter(verified, downstream(forwarded))
            }).block()

            assertThat(exchange.response.statusCode).isNull()
            assertThat(forwarded).hasSize(1)
            val request = forwarded.single().request
            assertThat(request.method).isEqualTo(method)
            assertThat(request.uri.rawPath).isEqualTo(path)
            val forwardedBody = org.springframework.core.io.buffer.DataBufferUtils.join(request.body)
                .map { buffer ->
                    try { StandardCharsets.UTF_8.decode(buffer.asByteBuffer()).toString() }
                    finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer) }
                }.defaultIfEmpty("").block()
            assertThat(forwardedBody).isEqualTo(body)
            assertThat(request.headers.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
                .isEqualTo(canonicalBase64Url(subjectA))
            assertThat(request.headers).doesNotContainKeys(
                HttpHeaders.AUTHORIZATION, "X-User-Id", "X-Finance-Attacker-Extra"
            )
        }
    }

    @Test
    fun `delete restore and deleted list require authentication before forwarding`() {
        listOf(
            HttpMethod.GET to "/api/finance/journals/deleted",
            HttpMethod.POST to "/api/finance/journals/$subjectA/delete",
            HttpMethod.POST to "/api/finance/journals/$subjectA/restore"
        ).forEach { (method, path) ->
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build())
            val forwarded = mutableListOf<ServerWebExchange>()
            jwtFilter.filter(exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
                financeFilter.filter(verified, downstream(forwarded))
            }).block()
            assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(forwarded).isEmpty()
        }
    }

    @Test
    fun `journal lifecycle routes reject wrong methods noncanonical ids and encoded paths`() {
        val invalid = listOf(
            HttpMethod.POST to "/api/finance/journals/deleted",
            HttpMethod.DELETE to "/api/finance/journals/$subjectA",
            HttpMethod.DELETE to "/api/finance/journals/$subjectA/delete",
            HttpMethod.GET to "/api/finance/journals/$subjectA/delete",
            HttpMethod.PATCH to "/api/finance/journals/$subjectA/restore",
            HttpMethod.PUT to "/api/finance/journals/$subjectA/restore",
            HttpMethod.POST to "/api/finance/journals/not-a-uuid/delete",
            HttpMethod.POST to "/api/finance/journals/${subjectA.uppercase()}/restore",
            HttpMethod.POST to "/api/finance/journals/$subjectA/delete/",
            HttpMethod.POST to "/api/finance/journals/$subjectA/restore/extra",
            HttpMethod.POST to "/api/finance/journals//$subjectA/delete",
            HttpMethod.POST to "/api/finance/journals/deleted/restore",
            HttpMethod.POST to "/api/finance/journals/$subjectA/%64elete",
            HttpMethod.GET to "/api/finance/journals/%64eleted"
        )
        invalid.forEach { (method, path) ->
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, java.net.URI.create(path)).build()
            )
            assertThat(exchange.request.uri.rawPath).isEqualTo(path)
            val forwarded = mutableListOf<ServerWebExchange>()
            financeFilter.filter(exchange, downstream(forwarded)).block()
            assertThat(exchange.response.statusCode).describedAs("$method $path").isEqualTo(HttpStatus.FORBIDDEN)
            assertThat(forwarded).isEmpty()
        }
        assertThat(FinanceTrustedContextWire.isSupportedRoute(
            HttpMethod.POST, "/api/finance/journals/$subjectA/delete", "/api/finance/journals/$subjectA/%64elete"
        )).isFalse()
    }

    @Test
    fun `strict reference protects lifecycle and existing PATCH without Core token fallback`() {
        val referenceKey = fixtureProperties.getProperty("platform.gateway.finance-reference.jwt.verification-key")
        val referenceConfig = config.copy(
            financeTrustedContext = config.financeTrustedContext.copy(enabled = false),
            financeReference = com.ranikunlabs.platform.gateway.config.FinanceReferenceJwtConfig(
                enabled = true,
                jwt = com.ranikunlabs.platform.gateway.config.FinanceReferenceJwtKeyConfig(
                    verificationKey = referenceKey
                ),
                issuer = com.ranikunlabs.platform.gateway.config.FinanceReferenceProfileGuard.EXPECTED_ISSUER,
                audience = com.ranikunlabs.platform.gateway.config.FinanceReferenceProfileGuard.EXPECTED_AUDIENCE,
                purpose = com.ranikunlabs.platform.gateway.config.FinanceReferenceProfileGuard.EXPECTED_PURPOSE,
                clockSkewSeconds = 30,
                maxTokenLifetimeSeconds = 300
            )
        )
        val global = JwtGlobalFilter(
            JwtVerifier(jwtSecret), RedisBlacklistService(redisTemplate), referenceConfig,
            AuthPublicRequestMatcher(), OAuthPublicRequestMatcher()
        )
        val producer = FinanceTrustedContextGatewayFilter(referenceConfig, fixtureEnvironment())
        val issuedAt = java.time.Instant.now().minusSeconds(1)
        val token = Jwts.builder().subject(subjectA).issuer(referenceConfig.financeReference.issuer)
            .audience().single(referenceConfig.financeReference.audience)
            .claim("token_use", referenceConfig.financeReference.purpose)
            .issuedAt(Date.from(issuedAt)).expiration(Date.from(issuedAt.plusSeconds(300)))
            .signWith(Keys.hmacShaKeyFor(referenceKey.toByteArray(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
            .compact()
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val routes = listOf(
            HttpMethod.GET to "/api/finance/journals/deleted",
            HttpMethod.POST to "/api/finance/journals/$subjectA/delete",
            HttpMethod.POST to "/api/finance/journals/$subjectA/restore",
            HttpMethod.PATCH to "/api/finance/journals/$subjectA"
        )
        routes.forEach { (method, path) ->
            val forwarded = mutableListOf<ServerWebExchange>()
            val chain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
                producer.filter(verified, downstream(forwarded))
            }
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, java.net.URI.create(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .header(FinanceTrustedContextWire.SUBJECT_HEADER, subjectB).build()
            )
            global.filter(exchange, chain).block()
            assertThat(exchange.response.statusCode).isNull()
            assertThat(forwarded).hasSize(1)
            assertThat(forwarded.single().request.headers.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
                .isEqualTo(canonicalBase64Url(subjectA))
            assertThat(forwarded.single().request.headers).doesNotContainKey(HttpHeaders.AUTHORIZATION)

            forwarded.clear()
            val coreToken = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, java.net.URI.create(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${validToken(subjectA)}").build()
            )
            global.filter(coreToken, chain).block()
            assertThat(coreToken.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(forwarded).isEmpty()
        }
    }

    private fun capture(verifiedSubject: String, forgedSubject: String): Map<String, String> {
        val token = validToken(verifiedSubject)
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))

        val request = MockServerHttpRequest.post("/api/finance/journals")
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
            MockServerHttpRequest.get("/api/finance/journals")
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

    private fun devEnvironment() = MockEnvironment().apply {
        setActiveProfiles(FINANCE_DEV_PROFILE)
        setProperty("platform.metrics.environment", "dev")
        setProperty("PLATFORM_ENVIRONMENT", "dev")
    }

    private fun forwardedFinanceHeaders(exchange: ServerWebExchange): Map<String, String> {
        val headers = exchange.request.headers
        return listOf(
            FinanceTrustedContextWire.VERSION_HEADER,
            FinanceTrustedContextWire.SERVICE_ID_HEADER,
            FinanceTrustedContextWire.SERVICE_SECRET_HEADER,
            FinanceTrustedContextWire.AUDIENCE_HEADER,
            FinanceTrustedContextWire.SUBJECT_HEADER
        ).associateWith { headers.getFirst(it) ?: "" }
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
