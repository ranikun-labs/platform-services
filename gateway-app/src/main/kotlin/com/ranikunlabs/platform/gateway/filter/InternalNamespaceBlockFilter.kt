package com.ranikunlabs.platform.gateway.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/** Keeps the Platform-to-Carelog service namespace out of the public Gateway. */
@Component
class InternalNamespaceBlockFilter : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (!isInternalNamespace(exchange.request.uri.path)) {
            return chain.filter(exchange)
        }

        exchange.response.statusCode = HttpStatus.NOT_FOUND
        return exchange.response.setComplete()
    }

    override fun getOrder(): Int = -100

    private fun isInternalNamespace(path: String): Boolean =
        path == INTERNAL_NAMESPACE || path.startsWith("$INTERNAL_NAMESPACE/")

    private companion object {
        const val INTERNAL_NAMESPACE = "/api/v1/internal"
    }
}
