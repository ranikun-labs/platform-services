package com.ranikunlabs.platform.gateway.config

import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

object GatewayJwtKeyFactory {

    const val CONFIGURATION_VARIABLE = "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET"

    fun create(secret: String): SecretKey {
        val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
        if (secret.isBlank() || secretBytes.size < MINIMUM_SECRET_BYTES) {
            throw GatewaySecretConfigurationException(CONFIGURATION_VARIABLE)
        }

        return try {
            Keys.hmacShaKeyFor(secretBytes)
        } catch (_: RuntimeException) {
            throw GatewaySecretConfigurationException(CONFIGURATION_VARIABLE)
        }
    }

    private const val MINIMUM_SECRET_BYTES = 32
}

class GatewaySecretConfigurationException(variableName: String) :
    IllegalStateException("Invalid configuration for $variableName: invalid secret")
