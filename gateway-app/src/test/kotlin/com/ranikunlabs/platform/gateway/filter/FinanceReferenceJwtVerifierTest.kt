package com.ranikunlabs.platform.gateway.filter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.config.FinanceReferenceJwtConfig
import com.ranikunlabs.platform.gateway.config.FinanceReferenceJwtKeyConfig
import com.ranikunlabs.platform.gateway.config.FinanceReferenceProfileGuard
import com.ranikunlabs.platform.gateway.config.FinanceTrustedContextConfig
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import com.ranikunlabs.platform.gateway.config.GatewayJwtKeyFactory
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FinanceReferenceJwtVerifierTest {

    private val fixtureProperties = java.util.Properties().apply {
        FinanceReferenceJwtVerifierTest::class.java
            .getResourceAsStream("/application-finance-trusted-context-reference.properties")!!
            .use(::load)
    }
    private val proof = fixtureProperties.getProperty("platform.gateway.finance-trusted-context.service-secret")
    private val legacyKey = fixtureProperties.getProperty("platform.gateway.jwt-verification-secret")
    private val internalSecret = fixtureProperties.getProperty("platform.gateway.internal-secret")
    private val referenceKey = fixtureProperties.getProperty(
        "platform.gateway.finance-reference.jwt.verification-key"
    )
    private val issuer = FinanceReferenceProfileGuard.EXPECTED_ISSUER
    private val audience = FinanceReferenceProfileGuard.EXPECTED_AUDIENCE
    private val purpose = FinanceReferenceProfileGuard.EXPECTED_PURPOSE
    private val now = Instant.parse("2026-11-06T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val config = GatewayConfig(
        internalSecret = internalSecret,
        jwtVerificationSecret = legacyKey,
        financeTrustedContext = FinanceTrustedContextConfig(enabled = false, serviceSecret = proof),
        financeReference = FinanceReferenceJwtConfig(
            enabled = true,
            jwt = FinanceReferenceJwtKeyConfig(verificationKey = referenceKey),
            issuer = issuer,
            audience = audience,
            purpose = purpose,
            clockSkewSeconds = 30,
            maxTokenLifetimeSeconds = 300
        )
    )
    private val verifier = FinanceReferenceJwtVerifier(config, clock)

    @Test
    fun `accepts actual reference issuer shape with raw scalar audience and opaque subject`() {
        val token = token(subject = "opaque-reference-subject")
        val rawPayload = jacksonObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split('.')[1]))

        assertThat(rawPayload.get("aud").isTextual).isTrue()
        assertThat(rawPayload.get("aud").asText()).isEqualTo(audience)
        assertThat(verifier.verifyAndGetClaims(token).subject).isEqualTo("opaque-reference-subject")
    }

    @Test
    fun `binds the dedicated verification key only from the contract jwt property path`() {
        val source = MapConfigurationPropertySource(mapOf(
            "platform.gateway.finance-reference.enabled" to "true",
            "platform.gateway.finance-reference.jwt.verification-key" to referenceKey,
            "platform.gateway.finance-reference.issuer" to issuer,
            "platform.gateway.finance-reference.audience" to audience,
            "platform.gateway.finance-reference.purpose" to purpose,
            "platform.gateway.finance-reference.clock-skew-seconds" to "30",
            "platform.gateway.finance-reference.max-token-lifetime-seconds" to "300"
        ))
        val bound = Binder(source)
            .bind("platform.gateway.finance-reference", Bindable.of(FinanceReferenceJwtConfig::class.java))
            .get()

        assertThat(bound.jwt.verificationKey).isEqualTo(referenceKey)
        assertThat(bound.enabled).isTrue()
    }

    @Test
    fun `rejects array audience extra claims wrong algorithm wrong issuer and wrong purpose`() {
        val invalid = listOf(
            token(audienceClaim = listOf(audience)),
            token(extraClaims = mapOf("organizationId" to "11111111-1111-1111-1111-111111111111")),
            token(algorithm = Jwts.SIG.HS384),
            token(issuerClaim = "urn:wrong:issuer"),
            token(purposeClaim = "refresh")
        )

        invalid.forEach { candidate ->
            assertThatThrownBy { verifier.verifyAndGetClaims(candidate) }.isInstanceOf(JwtException::class.java)
        }
    }

    @Test
    fun `rejects missing claims invalid time bounds malformed subject and wrong key`() {
        val invalid = listOf(
            token(includeIssuedAt = false),
            token(includeExpiration = false),
            token(issuedAt = now.plusSeconds(31)),
            token(expiration = now),
            token(issuedAt = now.minusSeconds(1), expiration = now.plusSeconds(301)),
            token(subject = " "),
            token(subject = "x".repeat(257)),
            token(subject = "\uD800"),
            token(signingKey = "Other-Finance-Reference-Test-Key-Only-2026-With-Length")
        )
        invalid.forEach { candidate ->
            assertThatThrownBy { verifier.verifyAndGetClaims(candidate) }.isInstanceOf(JwtException::class.java)
        }
        assertThatThrownBy { verifier.verifyAndGetClaims("a".repeat(8193)) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `rejects duplicate JSON claim keys even when signature is valid`() {
        val payload = """{"iss":"$issuer","iss":"$issuer","aud":"$audience","token_use":"$purpose","sub":"opaque-A","iat":${now.epochSecond},"exp":${now.plusSeconds(300).epochSecond}}"""
        val token = signRawJson(payload)

        assertThatThrownBy { verifier.verifyAndGetClaims(token) }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `only exact POST login is public while strict mode is enabled`() {
        val matcher = AuthPublicRequestMatcher(config)
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/auth/finance-reference/login"))).isTrue()
        assertThat(matcher.matches(request(HttpMethod.OPTIONS, "/api/v1/auth/finance-reference/login"))).isFalse()
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/auth/finance-reference/login/extra"))).isFalse()
        assertThat(AuthPublicRequestMatcher().matches(
            request(HttpMethod.POST, "/api/v1/auth/finance-reference/login"))).isFalse()
    }

    @Test
    fun `strict Finance path rejects Core signed tokens without attempting legacy verification`() {
        val redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        val global = JwtGlobalFilter(
            JwtVerifier(legacyKey), RedisBlacklistService(redisTemplate), config,
            AuthPublicRequestMatcher(config), OAuthPublicRequestMatcher()
        )
        val coreIssuedAt = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val coreToken = Jwts.builder()
            .subject("11111111-1111-1111-1111-111111111111")
            .claim("organizationId", "22222222-2222-2222-2222-222222222222")
            .claim("role", "MANAGER")
            .claim("publicId", "33333333-3333-3333-3333-333333333333")
            .issuedAt(Date.from(coreIssuedAt))
            .expiration(Date.from(coreIssuedAt.plusSeconds(300)))
            .signWith(GatewayJwtKeyFactory.create(legacyKey), Jwts.SIG.HS256)
            .compact()
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $coreToken")
                .build()
        )
        val forwarded = mutableListOf<ServerWebExchange>()

        global.filter(exchange) { forwarded += it; Mono.empty() }.block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(forwarded).isEmpty()
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `valid strict token is blacklisted before forwarding and emits only the existing five headers`() {
        val redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        val global = JwtGlobalFilter(
            JwtVerifier(legacyKey), RedisBlacklistService(redisTemplate), config,
            AuthPublicRequestMatcher(config), OAuthPublicRequestMatcher()
        )
        val finance = FinanceTrustedContextGatewayFilter(config, referenceEnvironment())
        val issuedAt = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val token = token(
            subject = "opaque-reference-user",
            issuedAt = issuedAt,
            expiration = issuedAt.plusSeconds(300)
        )
        `when`(redisTemplate.hasKey("blacklist:$token")).thenReturn(Mono.just(false))
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/finance/journals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header(HttpHeaders.COOKIE, "session=forged")
                .header("X-Finance-Subject", "forged-subject")
                .header("X-User-Id", "forged-user")
                .build()
        )
        val forwarded = mutableListOf<ServerWebExchange>()
        val financeChain = org.springframework.cloud.gateway.filter.GatewayFilterChain { verified ->
            finance.filter(verified) { downstream -> forwarded += downstream; Mono.empty() }
        }

        global.filter(exchange, financeChain).block()

        assertThat(exchange.response.statusCode).isNull()
        assertThat(forwarded).hasSize(1)
        val headers = forwarded.single().request.headers
        assertThat(headers.keys.filter { it.startsWith("X-Finance-") }.toSet()).containsExactlyInAnyOrder(
            FinanceTrustedContextWire.VERSION_HEADER,
            FinanceTrustedContextWire.SERVICE_ID_HEADER,
            FinanceTrustedContextWire.SERVICE_SECRET_HEADER,
            FinanceTrustedContextWire.AUDIENCE_HEADER,
            FinanceTrustedContextWire.SUBJECT_HEADER
        )
        assertThat(headers.getFirst(FinanceTrustedContextWire.SUBJECT_HEADER))
            .isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("opaque-reference-user".toByteArray(StandardCharsets.UTF_8)))
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull()
        assertThat(headers.getFirst(HttpHeaders.COOKIE)).isNull()
        assertThat(headers.getFirst("X-User-Id")).isNull()
        assertThat(headers.getFirst("X-Organization-Id")).isNull()
        assertThat(headers.getFirst("X-Role")).isNull()
        assertThat(headers.getFirst("X-Public-Id")).isNull()
    }

    @Test
    fun `strict blacklist errors stay 503 and invalid tokens stay 401`() {
        val redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        val global = JwtGlobalFilter(
            JwtVerifier(legacyKey), RedisBlacklistService(redisTemplate), config,
            AuthPublicRequestMatcher(config), OAuthPublicRequestMatcher()
        )
        val issuedAt = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val valid = token(
            subject = "opaque-reference-user",
            issuedAt = issuedAt,
            expiration = issuedAt.plusSeconds(300)
        )
        `when`(redisTemplate.hasKey("blacklist:$valid"))
            .thenReturn(Mono.error(DataAccessResourceFailureException("Redis unavailable")))
        val outage = exchange(valid)
        val forwarded = mutableListOf<ServerWebExchange>()
        global.filter(outage) { forwarded += it; Mono.empty() }.block()
        assertThat(outage.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(forwarded).isEmpty()

        val invalid = exchange(token(audienceClaim = listOf(audience)))
        global.filter(invalid) { forwarded += it; Mono.empty() }.block()
        assertThat(invalid.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(forwarded).isEmpty()
    }

    @Test
    fun `reference profile guard fails closed on absent profile production marker and incomplete config`() {
        assertThatThrownBy {
            FinanceReferenceProfileGuard.validate(config.financeReference, config.financeTrustedContext, MockEnvironment())
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("explicit fixture profile")
        assertThatThrownBy {
            FinanceReferenceProfileGuard.validate(
                config.financeReference,
                config.financeTrustedContext,
                MockEnvironment().withProperty("spring.profiles.active", "finance-trusted-context-reference")
                    .withProperty("PLATFORM_ENVIRONMENT", " production ")
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("production marker")
        assertThatThrownBy {
            FinanceReferenceProfileGuard.validate(
                config.financeReference.copy(jwt = FinanceReferenceJwtKeyConfig(verificationKey = "short")),
                config.financeTrustedContext,
                referenceEnvironment()
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("configuration is incomplete")
        assertThatThrownBy {
            FinanceReferenceProfileGuard.validate(
                config.financeReference,
                config.financeTrustedContext.copy(enabled = true),
                referenceEnvironment()
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Core-marker compatibility")
    }

    private fun token(
        subject: String = "opaque-reference-subject",
        signingKey: String = referenceKey,
        algorithm: io.jsonwebtoken.security.SecureDigestAlgorithm<javax.crypto.SecretKey, *> = Jwts.SIG.HS256,
        issuerClaim: String = issuer,
        audienceClaim: Any = audience,
        purposeClaim: String = purpose,
        issuedAt: Instant = now,
        expiration: Instant = now.plusSeconds(300),
        includeIssuedAt: Boolean = true,
        includeExpiration: Boolean = true,
        extraClaims: Map<String, Any> = emptyMap()
    ): String {
        val key = Keys.hmacShaKeyFor(signingKey.toByteArray(StandardCharsets.UTF_8))
        var builder = Jwts.builder().subject(subject).issuer(issuerClaim)
        builder = if (audienceClaim is String) {
            builder.audience().single(audienceClaim)
        } else {
            builder.claim("aud", audienceClaim)
        }
        builder = builder.claim("token_use", purposeClaim)
        if (includeIssuedAt) builder = builder.issuedAt(Date.from(issuedAt))
        if (includeExpiration) builder = builder.expiration(Date.from(expiration))
        extraClaims.forEach { (name, value) -> builder = builder.claim(name, value) }
        return builder.signWith(key, algorithm).compact()
    }

    private fun signRawJson(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\"}".toByteArray(StandardCharsets.UTF_8))
        val body = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val unsigned = "$header.$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(referenceKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$unsigned.${encoder.encodeToString(mac.doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII)))}"
    }

    private fun request(method: HttpMethod, path: String) = MockServerHttpRequest.method(method, path).build()

    private fun exchange(token: String) = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/finance/journals")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
    )

    private fun referenceEnvironment() = MockEnvironment()
        .withProperty("spring.profiles.active", "finance-trusted-context-reference")
}
