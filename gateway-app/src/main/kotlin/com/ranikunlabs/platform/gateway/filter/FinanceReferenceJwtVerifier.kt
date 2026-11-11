package com.ranikunlabs.platform.gateway.filter

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ranikunlabs.platform.gateway.config.FinanceReferenceJwtConfig
import com.ranikunlabs.platform.gateway.config.FinanceReferenceProfileGuard
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import java.util.Date

/** Verifies the isolated access-only Finance reference token format. */
class FinanceReferenceJwtVerifier(
    gatewayConfig: GatewayConfig,
    private val clock: Clock = Clock.systemUTC()
) {
    private val config: FinanceReferenceJwtConfig = gatewayConfig.financeReference
    private val key = if (config.enabled) {
        com.ranikunlabs.platform.gateway.config.GatewayJwtKeyFactory.create(config.jwt.verificationKey)
    } else {
        null
    }

    fun verifyAndGetClaims(token: String): Claims {
        if (!config.enabled || key == null || token.toByteArray(StandardCharsets.UTF_8).size > MAX_TOKEN_BYTES) {
            invalid()
        }
        val parts = token.split('.')
        if (parts.size != 3 || parts.any(String::isEmpty)) invalid()

        val header = parseJsonSegment(parts[0])
        val payload = parseJsonSegment(parts[1])
        if (!header.path("alg").isTextual || header.path("alg").asText() != "HS256") invalid()
        if (payload.fieldNames().asSequence().toSet() != REQUIRED_CLAIMS) invalid()

        val issuer = payload.get("iss")
        val audience = payload.get("aud")
        val purpose = payload.get("token_use")
        val subject = payload.get("sub")
        if (!issuer.isExactText(FinanceReferenceProfileGuard.EXPECTED_ISSUER)
                || !issuer.isExactText(config.issuer)
                || !audience.isExactText(FinanceReferenceProfileGuard.EXPECTED_AUDIENCE)
                || !audience.isExactText(config.audience)
                || !purpose.isExactText(FinanceReferenceProfileGuard.EXPECTED_PURPOSE)
                || !purpose.isExactText(config.purpose)) invalid()

        val subjectValue = subject?.takeIf(JsonNode::isTextual)?.asText() ?: invalid()
        if (subjectValue.isBlank() || subjectValue.utf8Length() !in 1..MAX_SUBJECT_BYTES) invalid()

        val issuedAt = payload.get("iat").numericDate() ?: invalid()
        val expiration = payload.get("exp").numericDate() ?: invalid()
        val now = clock.instant().epochSecond
        if (issuedAt > now + config.clockSkewSeconds || expiration <= now || expiration <= issuedAt) invalid()
        val lifetime = try {
            Math.subtractExact(expiration, issuedAt)
        } catch (_: ArithmeticException) {
            invalid()
        }
        if (lifetime > config.maxTokenLifetimeSeconds) invalid()

        val verified = try {
            Jwts.parser()
                .verifyWith(key)
                .clock { Date.from(clock.instant()) }
                .clockSkewSeconds(0)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (_: RuntimeException) {
            invalid()
        }
        if (verified.subject != subjectValue
                || verified.issuer != config.issuer
                || verified["token_use"] != config.purpose) invalid()
        return verified
    }

    private fun parseJsonSegment(segment: String): ObjectNode {
        val bytes = try {
            Base64.getUrlDecoder().decode(segment)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        return try {
            strictJson.readTree(bytes) as? ObjectNode ?: invalid()
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun JsonNode?.isExactText(expected: String): Boolean =
        this != null && isTextual && asText() == expected

    private fun JsonNode?.numericDate(): Long? =
        this?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

    private fun String.utf8Length(): Int? = try {
        val encoded: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(this))
        encoded.remaining()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun invalid(): Nothing = throw MalformedJwtException("Invalid Finance reference access token.")

    private companion object {
        const val MAX_TOKEN_BYTES = 8192
        const val MAX_SUBJECT_BYTES = 256
        val REQUIRED_CLAIMS = setOf("iss", "aud", "token_use", "sub", "iat", "exp")
        val strictJson = ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
    }
}
