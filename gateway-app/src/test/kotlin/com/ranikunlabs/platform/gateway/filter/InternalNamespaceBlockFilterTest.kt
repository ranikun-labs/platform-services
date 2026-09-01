package com.ranikunlabs.platform.gateway.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

class InternalNamespaceBlockFilterTest {

    private val filter = InternalNamespaceBlockFilter()

    @Test
    fun `rejects internal claims namespace without authorization and does not invoke downstream`() {
        assertBlocked(
            MockServerHttpRequest.get("/api/v1/internal/identity/accounts/9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123/claims")
                .build()
        )
    }

    @Test
    fun `rejects internal claims namespace even with a bearer token and does not invoke downstream`() {
        assertBlocked(
            MockServerHttpRequest.method(
                HttpMethod.GET,
                "/api/v1/internal/identity/accounts/9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123/claims"
            )
                .header("Authorization", "Bearer valid-user-token")
                .build()
        )
    }

    @Test
    fun `does not block a similarly prefixed public path`() {
        val downstreamInvoked = AtomicBoolean(false)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/internality/identity").build()
        )

        filter.filter(exchange, GatewayFilterChain {
            downstreamInvoked.set(true)
            Mono.empty()
        }).block()

        assertThat(downstreamInvoked).isTrue()
        assertThat(exchange.response.statusCode).isNull()
    }

    @Test
    fun `runs before the JWT filter`() {
        assertThat(filter.order).isLessThan(-1)
    }

    private fun assertBlocked(request: org.springframework.mock.http.server.reactive.MockServerHttpRequest) {
        val downstreamInvoked = AtomicBoolean(false)
        val exchange = MockServerWebExchange.from(request)

        filter.filter(exchange, GatewayFilterChain {
            downstreamInvoked.set(true)
            Mono.empty()
        }).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(downstreamInvoked).isFalse()
    }
}
