package com.ranikunlabs.platform.gateway.config

import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class FinanceRouteConfiguration(
    private val environment: Environment,
    private val config: GatewayConfig
) {
    @Bean
    fun financeRouteLocator(builder: RouteLocatorBuilder): RouteLocator {
        val uri = environment.getProperty("PLATFORM_GATEWAY_FINANCE_BE_URI")?.trim().orEmpty()
        require(uri.isNotEmpty() || (!config.financeTrustedContext.enabled && !config.financeReference.enabled)) {
            "PLATFORM_GATEWAY_FINANCE_BE_URI is required when Finance routing is enabled."
        }
        val routes = builder.routes()
        if (uri.isNotEmpty()) {
            routes.route("finance-be") { route ->
                route.order(-5).path("/api/finance/**")
                    .filters { it.stripPrefix(1) }
                    .uri(uri)
            }
        }
        return routes.build()
    }
}
