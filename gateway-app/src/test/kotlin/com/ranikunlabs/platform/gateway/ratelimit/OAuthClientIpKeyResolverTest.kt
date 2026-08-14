package com.ranikunlabs.platform.gateway.ratelimit

import com.ranikunlabs.platform.gateway.config.OAuthRateLimitConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress

class OAuthClientIpKeyResolverTest {

    @Test
    fun `uses the address selected by one trusted proxy hop`() {
        val resolver = OAuthClientIpKeyResolver(OAuthRateLimitConfig(trustedProxyHops = 1))
        val exchange = exchange(xForwardedFor = "198.51.100.17, 10.10.0.8")

        assertThat(resolver.resolve(exchange).block()).isEqualTo("10.10.0.8")
    }

    @Test
    fun `uses the direct remote address without XFF`() {
        val resolver = OAuthClientIpKeyResolver(OAuthRateLimitConfig(trustedProxyHops = 1))
        val exchange = exchange(remoteAddress = InetSocketAddress("203.0.113.20", 443))

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.20")
    }

    private fun exchange(
        xForwardedFor: String? = null,
        remoteAddress: InetSocketAddress? = null
    ): MockServerWebExchange {
        val request = MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/auth/oauth/kakao/authorization")
            .apply {
                xForwardedFor?.let { header("X-Forwarded-For", it) }
                remoteAddress?.let { remoteAddress(it) }
            }
            .build()
        return MockServerWebExchange.from(request)
    }
}
