package com.ranikunlabs.platform.gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class CarelogRegistrationCorsConfigurationTest {
    private val filter = CarelogRegistrationCorsConfiguration().carelogRegistrationCorsFilter(
        GatewayConfig(carelogRegistrationAllowedOrigins = listOf("https://carelog.example"))
    )

    @Test
    fun `rejects wildcard and malformed configured origins`() {
        listOf("*", "https://*.example.com", "https://carelog.example/path",
            "https://user@carelog.example", "https://carelog.example?query=1",
            "https://bad host", "http://[::1").forEach { origin ->
            assertThatThrownBy {
                CarelogRegistrationCorsConfiguration().carelogRegistrationCorsFilter(
                    GatewayConfig(carelogRegistrationAllowedOrigins = listOf(origin))
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `allows configured origin and idempotency preflight`() {
        val request = MockServerHttpRequest.options("http://gateway.test/api/v1/auth/carelog/registration")
            .header(HttpHeaders.ORIGIN, "https://carelog.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, Idempotency-Key")
        val exchange = MockServerWebExchange.from(request)
        filter.filter(exchange, WebFilterChain { Mono.empty() }).block()
        assertThat(exchange.response.statusCode).isNull() // Mock response defaults to HTTP 200.
        assertThat(exchange.response.headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
            .isEqualTo("https://carelog.example")
        assertThat(exchange.response.headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
            .containsIgnoringCase("Idempotency-Key")
    }

    @Test
    fun `rejects unapproved origin`() {
        val request = MockServerHttpRequest.options("http://gateway.test/api/v1/auth/carelog/registration")
            .header(HttpHeaders.ORIGIN, "https://elsewhere.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        val exchange = MockServerWebExchange.from(request)
        filter.filter(exchange, WebFilterChain { Mono.empty() }).block()
        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
