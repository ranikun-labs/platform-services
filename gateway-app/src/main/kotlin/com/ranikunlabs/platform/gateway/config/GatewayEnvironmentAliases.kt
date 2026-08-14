package com.ranikunlabs.platform.gateway.config

import org.springframework.core.env.ConfigurableEnvironment

object GatewayEnvironmentAliases {

    private data class Alias(
        val canonical: String,
        val legacy: String?,
        val required: Boolean
    )

    private val aliases = listOf(
        Alias("PLATFORM_GATEWAY_PORT", "GATEWAY_PORT", required = true),
        Alias("PLATFORM_GATEWAY_REDIS_HOST", "REDIS_HOST", required = true),
        Alias("PLATFORM_GATEWAY_REDIS_PORT", "REDIS_PORT", required = true),
        Alias("PLATFORM_GATEWAY_REDIS_KEY_PREFIX", null, required = true),
        Alias("PLATFORM_GATEWAY_CARELOG_BE_URI", "CARELOG_BE_URI", required = true),
        Alias("PLATFORM_GATEWAY_CARELOG_RAG_URI", "CARELOG_RAG_URI", required = true),
        Alias("PLATFORM_GATEWAY_INTERNAL_SECRET", "GATEWAY_INTERNAL_SECRET", required = true),
        Alias("PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET", "JWT_SECRET_KEY", required = true),
        Alias(
            "PLATFORM_GATEWAY_OAUTH_RATE_LIMIT_TRUSTED_PROXY_HOPS",
            "OAUTH_RATE_LIMIT_TRUSTED_PROXY_HOPS",
            required = false
        )
    )

    fun resolve(environment: ConfigurableEnvironment): Map<String, String> {
        val resolved = linkedMapOf<String, String>()

        aliases.forEach { alias ->
            val canonicalRaw = rawProperty(environment, alias.canonical)
            val legacyRaw = alias.legacy?.let { rawProperty(environment, it) }
            val selectedRaw = when {
                canonicalRaw != null && legacyRaw != null -> {
                    check(canonicalRaw == legacyRaw) {
                        "CONFIG_ALIAS_CONFLICT: ${alias.canonical} and ${alias.legacy}"
                    }
                    canonicalRaw
                }

                canonicalRaw != null -> canonicalRaw
                legacyRaw != null -> legacyRaw
                alias.required -> error("CONFIG_ALIAS_MISSING: ${alias.canonical}")
                else -> null
            }

            if (selectedRaw != null) {
                resolved[alias.canonical] = environment.resolvePlaceholders(selectedRaw)
            }
        }

        return resolved
    }

    private fun rawProperty(environment: ConfigurableEnvironment, name: String): String? {
        for (propertySource in environment.propertySources) {
            propertySource.getProperty(name)?.let { return it.toString() }
        }
        return null
    }
}
