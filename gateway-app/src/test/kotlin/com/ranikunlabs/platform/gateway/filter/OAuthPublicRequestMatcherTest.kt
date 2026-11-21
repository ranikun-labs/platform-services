package com.ranikunlabs.platform.gateway.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import com.ranikunlabs.platform.gateway.config.GatewayConfig

class OAuthPublicRequestMatcherTest {

    private val matcher = OAuthPublicRequestMatcher()

    @Test
    fun `registration is opt in and only exact POST is public`() {
        val path = "/api/v1/auth/carelog/registration"
        val enabled = OAuthPublicRequestMatcher(GatewayConfig(carelogRegistrationEnabled = true))
        assertThat(matcher.matches(request(HttpMethod.POST, path))).isFalse()
        assertThat(enabled.matches(request(HttpMethod.POST, path))).isTrue()
        assertThat(enabled.matches(request(HttpMethod.OPTIONS, path))).isFalse()
        assertThat(enabled.matches(request(HttpMethod.POST, "$path/extra"))).isFalse()
    }

    @Test
    fun `allows only exact authorization POST`() {
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/auth/oauth/kakao/authorization"))).isTrue()
        assertThat(matcher.matches(request(HttpMethod.GET, "/api/v1/auth/oauth/kakao/authorization"))).isFalse()
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/auth/oauth/kakao/authorization/extra"))).isFalse()
    }

    @Test
    fun `allows only exact exchange POST`() {
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/auth/oauth/kakao/exchange"))).isTrue()
        assertThat(matcher.matches(request(HttpMethod.PUT, "/api/v1/auth/oauth/kakao/exchange"))).isFalse()
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/auth/oauth/kakao/link"))).isFalse()
    }

    private fun request(method: HttpMethod, path: String) =
        MockServerHttpRequest.method(method, path).build()
}
