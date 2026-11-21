package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.ratelimit.OAuthRateLimitInfrastructureException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import com.ranikunlabs.platform.gateway.config.GatewayConfig

class OAuthRateLimitFailureClosedFilterTest {

    private val filter = OAuthRateLimitFailureClosedFilter(OAuthPublicRequestMatcher())

    @Test
    fun `maps registration limiter failure to 503 when enabled`() {
        val enabled = OAuthRateLimitFailureClosedFilter(
            OAuthPublicRequestMatcher(GatewayConfig(carelogRegistrationEnabled = true))
        )
        val exchange = exchange(HttpMethod.POST, "/api/v1/auth/carelog/registration")
        val failure = OAuthRateLimitInfrastructureException(IllegalStateException("redis unavailable"))
        enabled.filter(exchange, GatewayFilterChain { Mono.error(failure) }).block()
        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `maps OAuth Redis limiter failure to 503`() {
        val exchange = exchange(HttpMethod.POST, "/api/v1/auth/oauth/kakao/authorization")
        val failure = OAuthRateLimitInfrastructureException(IllegalStateException("redis unavailable"))

        filter.filter(exchange, GatewayFilterChain { Mono.error(failure) }).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `propagates the same failure for non OAuth requests`() {
        val exchange = exchange(HttpMethod.GET, "/api/v1/patients")
        val failure = OAuthRateLimitInfrastructureException(IllegalStateException("backend failure"))

        val signal = catchThrowable {
            filter.filter(exchange, GatewayFilterChain { Mono.error(failure) }).block()
        }

        assertThat(signal).isSameAs(failure)
    }

    private fun exchange(method: HttpMethod, path: String) =
        MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build())
}
