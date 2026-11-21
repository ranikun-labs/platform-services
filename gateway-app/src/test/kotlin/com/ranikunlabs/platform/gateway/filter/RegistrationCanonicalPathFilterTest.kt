package com.ranikunlabs.platform.gateway.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.HttpMethod
import java.net.URI
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class RegistrationCanonicalPathFilterTest {
    private val filter = RegistrationCanonicalPathFilter()

    @Test
    fun `rejects encoded and semicolon aliases before route selection`() {
        val aliases = listOf(
            "/api/v1/auth/carelog/%72egistration",
            "/api/v1/auth/carelog/registration;version=1",
            "/api/v1/auth/carelog/registration/",
            "/api/v1/auth/carelog/registration/anything",
            "/api/v1/auth/carelog%2fregistration",
            "/api/v1/auth/carelog/%2572egistration",
            "/api/v1/auth/carelog/another/../registration"
        )
        aliases.forEach { path ->
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, URI.create("http://localhost$path")).build())
            var called = false
            filter.filter(exchange, WebFilterChain { called = true; Mono.empty() }).block()
            assertThat(exchange.response.statusCode).describedAs(path).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(called).describedAs(path).isFalse()
        }
    }

    @Test
    fun `passes canonical registration and unrelated encoded paths`() {
        listOf("/api/v1/auth/carelog/registration", "/api/v1/other/value%25",
            "/api/v1/carelog/value%25", "/api/v1/auth/carelog/profile%25").forEach { path ->
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, URI.create("http://localhost$path")).build())
            var called = false
            filter.filter(exchange, WebFilterChain { called = true; Mono.empty() }).block()
            assertThat(called).describedAs(path).isTrue()
        }
    }
}
