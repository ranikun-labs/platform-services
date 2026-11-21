package com.ranikunlabs.platform.gateway.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.server.WebFilter
import java.net.URI

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "platform.gateway", name = ["carelog-registration-enabled"], havingValue = "true")
class CarelogRegistrationCorsConfiguration {
    @Bean
    fun carelogRegistrationCorsFilter(config: GatewayConfig): WebFilter {
        require(config.carelogRegistrationAllowedOrigins.all(::isExactOrigin)) {
            "Carelog registration origins must be exact HTTP(S) origins"
        }
        val cors = CorsConfiguration().apply {
            allowedOrigins = config.carelogRegistrationAllowedOrigins
            allowedMethods = listOf("POST")
            allowedHeaders = listOf("Content-Type", "Idempotency-Key")
        }
        val filter = CorsWebFilter(CorsConfigurationSource { cors })
        return WebFilter { exchange, chain ->
            if (exchange.request.uri.path == "/api/v1/auth/carelog/registration") {
                filter.filter(exchange, chain)
            } else {
                chain.filter(exchange)
            }
        }
    }

    private fun isExactOrigin(value: String): Boolean {
        val uri = try { URI.create(value) } catch (_: IllegalArgumentException) { return false }
        return value == value.trim() && !value.contains('*') &&
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.isNullOrEmpty() && uri.port in -1..65535
    }
}
