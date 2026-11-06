package com.ranikunlabs.platform.gateway.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "platform.gateway")
data class GatewayConfig(
    val publicPaths: List<String> = emptyList(),
    @field:NotBlank val internalSecret: String = "",
    @field:NotBlank val jwtVerificationSecret: String = "",
    @field:Valid val financeTrustedContext: FinanceTrustedContextConfig = FinanceTrustedContextConfig(),
    @field:Valid val oauthRateLimit: OAuthRateLimitConfig = OAuthRateLimitConfig()
)

data class FinanceTrustedContextConfig(
    val enabled: Boolean = false,
    val serviceSecret: String = ""
)

data class OAuthRateLimitConfig(
    @field:Min(1) val trustedProxyHops: Int = 1,
    @field:Valid val authorization: RateLimitBucketConfig = RateLimitBucketConfig(5, 10),
    @field:Valid val exchange: RateLimitBucketConfig = RateLimitBucketConfig(3, 6)
)

data class RateLimitBucketConfig(
    @field:Min(1) val replenishRate: Int = 1,
    @field:Min(1) val burstCapacity: Int = 1
)
