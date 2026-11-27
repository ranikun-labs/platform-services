package com.ranikunlabs.platform.gateway.config

import com.ranikunlabs.platform.gateway.filter.FINANCE_TRUSTED_CONTEXT_PROFILE
import com.ranikunlabs.platform.gateway.filter.FinanceTrustedContextWire
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Locale

@Component
class FinanceReferenceProfileGuard(
    gatewayConfig: GatewayConfig,
    environment: Environment
) {
    init {
        validate(gatewayConfig.financeReference, gatewayConfig.financeTrustedContext, environment)
    }

    companion object {
        const val EXPECTED_ISSUER = "urn:ranikunlabs:identity:finance:reference"
        const val EXPECTED_AUDIENCE = "finance-journal-reference"
        const val EXPECTED_PURPOSE = "access"
        const val EXPECTED_CLOCK_SKEW_SECONDS = 30
        const val EXPECTED_MAX_TOKEN_LIFETIME_SECONDS = 300

        fun validate(
            config: FinanceReferenceJwtConfig,
            trustedContext: FinanceTrustedContextConfig,
            environment: Environment
        ) {
            if (!config.enabled) return

            val referenceProfileActive = environment.activeProfiles.any {
                it.equals(FINANCE_TRUSTED_CONTEXT_PROFILE, ignoreCase = true)
            }
            val productionProfileActive = environment.activeProfiles.any(::isProductionValue)
            val productionMarker = sequenceOf(
                environment.getProperty("PLATFORM_ENVIRONMENT"),
                environment.getProperty("platform.metrics.environment"),
                environment.getProperty("platform.environment")
            ).filterNotNull().any(::isProductionValue)

            require(referenceProfileActive || isExplicitDevEnvironment(environment)) {
                "Finance reference JWT mode requires its explicit fixture profile or an explicit DEV deployment marker."
            }
            require(!productionProfileActive && !productionMarker) {
                "Finance reference JWT mode cannot run with a production marker."
            }
            require(!trustedContext.enabled) {
                "Finance reference JWT mode cannot be combined with Core-marker compatibility mode."
            }
            require(config.jwt.verificationKey.toByteArray(StandardCharsets.UTF_8).size >= 32
                    && !config.jwt.verificationKey.isBlank()
                    && config.issuer == EXPECTED_ISSUER
                    && config.audience == EXPECTED_AUDIENCE
                    && config.purpose == EXPECTED_PURPOSE
                    && config.clockSkewSeconds == EXPECTED_CLOCK_SKEW_SECONDS
                    && config.maxTokenLifetimeSeconds == EXPECTED_MAX_TOKEN_LIFETIME_SECONDS) {
                "Finance reference JWT configuration is incomplete or does not match the fixture contract."
            }
            FinanceTrustedContextWire.validateServiceSecret(trustedContext.serviceSecret)
        }

        private fun isProductionValue(value: String): Boolean {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return normalized == "prod" || normalized == "production"
        }
    }
}

internal fun isExplicitDevEnvironment(environment: Environment): Boolean {
    val markers = listOfNotNull(
        environment.getProperty("PLATFORM_ENVIRONMENT"),
        environment.getProperty("platform.environment")
    )
    return markers.isNotEmpty() && markers.all { it.trim().equals("dev", ignoreCase = true) }
}
