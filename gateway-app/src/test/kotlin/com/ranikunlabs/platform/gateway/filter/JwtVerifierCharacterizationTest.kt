package com.ranikunlabs.platform.gateway.filter

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.security.SignatureException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.Date

class JwtVerifierCharacterizationTest {

    private val secret = "platform-gateway-characterization-test-secret-key-0123456789"
    private val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    private val verifier = JwtVerifier(secret)

    @Test
    fun `valid signed token preserves subject and claims`() {
        val token = Jwts.builder()
            .subject("manager@example.com")
            .claim("organizationId", "11111111-1111-1111-1111-111111111111")
            .claim("role", "MANAGER")
            .claim("publicId", "22222222-2222-2222-2222-222222222222")
            .signWith(key)
            .compact()

        val claims = verifier.verifyAndGetClaims(token)

        assertThat(claims.subject).isEqualTo("manager@example.com")
        assertThat(claims["organizationId"]).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(claims["role"]).isEqualTo("MANAGER")
        assertThat(claims["publicId"]).isEqualTo("22222222-2222-2222-2222-222222222222")
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-jwt", "a.b.c"])
    fun `malformed token throws JwtException`(token: String) {
        assertThat(catchThrowable { verifier.verifyAndGetClaims(token) })
            .isInstanceOf(MalformedJwtException::class.java)
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `expired token throws ExpiredJwtException`() {
        val token = Jwts.builder()
            .subject("manager@example.com")
            .issuedAt(Date(0))
            .expiration(Date(1000))
            .signWith(key)
            .compact()

        assertThat(catchThrowable { verifier.verifyAndGetClaims(token) })
            .isInstanceOf(ExpiredJwtException::class.java)
    }

    @Test
    fun `tampered token throws SignatureException`() {
        val valid = Jwts.builder().subject("manager@example.com").signWith(key).compact()
        val tampered = valid.dropLast(4) + "AAAA"

        assertThat(catchThrowable { verifier.verifyAndGetClaims(tampered) })
            .isInstanceOf(SignatureException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `empty token retains IllegalArgumentException behavior`(token: String) {
        assertThat(catchThrowable { verifier.verifyAndGetClaims(token) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .isNotInstanceOf(JwtException::class.java)
    }
}
