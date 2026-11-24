package com.ranikunlabs.platform.gateway.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import org.springframework.web.server.ServerWebExchange

class UnsafeRequestPathFilterTest {

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/%252e%252e/admin",
        "/api/v1/%252E%252E/admin",
        "/api/finance/journals/%25252e",
        "/api/v1/a%25b",
        "/api/v1/%2e%2e/admin",
        "/api/v1/a;b",
        "/api/v1/a%5cb"
    ])
    fun `rejects percent-encoded percent and other ambiguous path forms`(rawPath: String) {
        assertThat(UnsafeRequestPathFilter.isUnsafe(rawPath = rawPath, decodedPath = "")).isTrue()
    }

    @Test
    fun `double-encoded dot segment is rejected with 400 and never reaches the chain`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/%252e%252e/admin").build())
        var reached = false
        val chain = org.springframework.web.server.WebFilterChain { _: ServerWebExchange ->
            reached = true
            Mono.empty()
        }

        UnsafeRequestPathFilter().filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(reached).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/auth/finance-reference/signup",
        "/api/finance/journals/0f8fad5b-d9cb-469f-a165-70867728950e",
        "/api/v1/users/me"
    ])
    fun `ordinary paths are not rejected`(rawPath: String) {
        assertThat(UnsafeRequestPathFilter.isUnsafe(rawPath = rawPath, decodedPath = rawPath)).isFalse()
    }
}
