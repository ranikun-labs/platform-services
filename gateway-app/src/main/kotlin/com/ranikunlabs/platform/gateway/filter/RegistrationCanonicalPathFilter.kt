package com.ranikunlabs.platform.gateway.filter

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/** Reject registration aliases before generic routes can forward them. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RegistrationCanonicalPathFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val raw = exchange.request.uri.rawPath
        var normalized = raw
        repeat(2) {
            normalized = try {
                UriUtils.decode(normalized, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                if (normalized.startsWith(REGISTRATION_PATH)) {
                    exchange.response.statusCode = HttpStatus.BAD_REQUEST
                    return exchange.response.setComplete()
                }
                return chain.filter(exchange)
            }
        }
        val segments = mutableListOf<String>()
        for (segment in normalized.split('/')) {
            val clean = segment.substringBefore(';')
            when (clean) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(clean)
            }
        }
        normalized = "/" + segments.joinToString("/")
        if ((normalized == REGISTRATION_PATH || normalized.startsWith("$REGISTRATION_PATH/")) &&
            raw != REGISTRATION_PATH
        ) {
            exchange.response.statusCode = HttpStatus.NOT_FOUND
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }

    private companion object {
        const val REGISTRATION_PATH = "/api/v1/auth/carelog/registration"
    }
}
