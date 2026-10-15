package com.ranikunlabs.platform.gateway.filter

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.server.handler.ExceptionHandlingWebHandler
import reactor.core.publisher.Mono

/** Counts failed HTTP requests without attaching request-derived values to a metric. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HttpFailureMetricsWebFilter(meterRegistry: MeterRegistry) : WebFilter {

    private val errors = meterRegistry.counter("platform.http.server.errors")
    private val timeouts = meterRegistry.counter("platform.http.server.timeouts")

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        return chain.filter(exchange)
            .doFinally {
                val handledFailure = exchange.getAttribute<Throwable>(
                    ExceptionHandlingWebHandler.HANDLED_WEB_EXCEPTION
                )
                if (handledFailure != null && PlatformTimeoutClassifier.isActualTimeout(handledFailure)) {
                    timeouts.increment()
                } else if (exchange.response.statusCode?.is5xxServerError == true) {
                    errors.increment()
                }
            }
    }
}
