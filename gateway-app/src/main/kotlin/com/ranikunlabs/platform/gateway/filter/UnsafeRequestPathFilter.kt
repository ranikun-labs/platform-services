package com.ranikunlabs.platform.gateway.filter

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Rejects ambiguous request paths with 400 instead of normalizing them, so routing and
 * authorization always judge the same path. Runs after [RegistrationCanonicalPathFilter],
 * which keeps its existing 404 behavior for registration aliases.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class UnsafeRequestPathFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val uri = exchange.request.uri
        if (isUnsafe(rawPath = uri.rawPath.orEmpty(), decodedPath = uri.path.orEmpty())) {
            exchange.response.statusCode = HttpStatus.BAD_REQUEST
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }

    internal companion object {
        // Encoded dot, slash, backslash and semicolon forms, plus the literal semicolon and backslash.
        private val FORBIDDEN_RAW_MARKERS = listOf("%2e", "%2f", "%5c", "%3b", ";", "\\")

        fun isUnsafe(rawPath: String, decodedPath: String): Boolean {
            val lowered = rawPath.lowercase()
            if (FORBIDDEN_RAW_MARKERS.any(lowered::contains)) return true
            return rawPath.split('/').any(::isDotSegment) || decodedPath.split('/').any(::isDotSegment)
        }

        private fun isDotSegment(segment: String): Boolean = segment == "." || segment == ".."
    }
}
