package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.config.FinanceReferenceJwtConfig
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest

class FinanceReferenceSignupPublicPathTest {

    private val enabled = AuthPublicRequestMatcher(
        GatewayConfig(
            internalSecret = "internal",
            jwtVerificationSecret = "jwt",
            financeReference = FinanceReferenceJwtConfig(enabled = true)
        )
    )
    private val disabled = AuthPublicRequestMatcher(
        GatewayConfig(internalSecret = "internal", jwtVerificationSecret = "jwt")
    )

    @ParameterizedTest
    @CsvSource(
        "POST, /api/v1/auth/finance-reference/signup",
        "POST, /api/v1/auth/finance-reference/login"
    )
    fun `finance-reference signup and login are public only when finance-reference is enabled`(method: HttpMethod, path: String) {
        assertThat(enabled.matches(request(method, path))).isTrue()
        assertThat(disabled.matches(request(method, path))).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "GET, /api/v1/auth/finance-reference/signup",
        "PUT, /api/v1/auth/finance-reference/signup",
        "OPTIONS, /api/v1/auth/finance-reference/signup",
        "POST, /api/v1/auth/finance-reference/signup/extra",
        "POST, /api/v1/auth/finance-reference/signup-extra",
        "POST, /api/v1/auth/finance-reference/signup/",
        "POST, /api/v1/auth/finance-reference"
    )
    fun `signup is exact POST only and does not widen the public boundary`(method: HttpMethod, path: String) {
        assertThat(enabled.matches(request(method, path))).isFalse()
    }

    private fun request(method: HttpMethod, path: String) =
        MockServerHttpRequest.method(method, path).build()
}
