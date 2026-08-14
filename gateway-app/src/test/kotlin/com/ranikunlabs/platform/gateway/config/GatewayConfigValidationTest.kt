package com.ranikunlabs.platform.gateway.config

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GatewayConfigValidationTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `rejects non-positive trusted proxy hops`() {
        val config = GatewayConfig(
            internalSecret = "internal-secret",
            jwtVerificationSecret = "jwt-secret-key-that-is-long-enough",
            oauthRateLimit = OAuthRateLimitConfig(trustedProxyHops = 0)
        )

        assertThat(validator.validate(config))
            .anyMatch { violation -> violation.propertyPath.toString() == "oauthRateLimit.trustedProxyHops" }
    }

    @Test
    fun `rejects non-positive OAuth bucket values`() {
        val config = GatewayConfig(
            internalSecret = "internal-secret",
            jwtVerificationSecret = "jwt-secret-key-that-is-long-enough",
            oauthRateLimit = OAuthRateLimitConfig(
                authorization = RateLimitBucketConfig(replenishRate = 0, burstCapacity = 10),
                exchange = RateLimitBucketConfig(replenishRate = 3, burstCapacity = 0)
            )
        )

        assertThat(validator.validate(config).map { violation -> violation.propertyPath.toString() })
            .contains(
                "oauthRateLimit.authorization.replenishRate",
                "oauthRateLimit.exchange.burstCapacity"
            )
    }
}
