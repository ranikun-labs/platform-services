package com.ranikunlabs.platform.gateway.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest

class AuthPublicRequestMatcherTest {

    private val matcher = AuthPublicRequestMatcher()

    @ParameterizedTest
    @CsvSource(
        "POST, /api/v1/auth/login",
        "POST, /api/v1/auth/refresh",
        "OPTIONS, /api/v1/auth/login",
        "OPTIONS, /api/v1/auth/refresh"
    )
    fun `matches exact Identity Auth endpoints and required preflight`(method: HttpMethod, path: String) {
        assertThat(matcher.matches(request(method, path))).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "GET, /api/v1/auth/login",
        "PUT, /api/v1/auth/refresh",
        "POST, /api/v1/auth/login-extra",
        "POST, /api/v1/auth/login/foo",
        "POST, /api/v1/auth/refresh-extra",
        "POST, /api/v1/auth/refresh/foo",
        "POST, /api/v1/auth/logout"
    )
    fun `does not match non exact or protected Identity Auth requests`(method: HttpMethod, path: String) {
        assertThat(matcher.matches(request(method, path))).isFalse()
    }

    @Test
    fun `does not treat a similarly prefixed path as public`() {
        assertThat(matcher.matches(request(HttpMethod.POST, "/api/v1/authentication/login"))).isFalse()
    }

    private fun request(method: HttpMethod, path: String) =
        MockServerHttpRequest.method(method, path).build()
}
