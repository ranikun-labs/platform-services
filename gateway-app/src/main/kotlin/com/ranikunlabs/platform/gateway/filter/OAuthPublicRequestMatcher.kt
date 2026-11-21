package com.ranikunlabs.platform.gateway.filter

import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import com.ranikunlabs.platform.gateway.config.GatewayConfig

@Component
class OAuthPublicRequestMatcher(private val gatewayConfig: GatewayConfig = GatewayConfig()) {

    fun matches(request: ServerHttpRequest): Boolean =
        request.method == HttpMethod.POST && (request.uri.path in KAKAO_OAUTH_PATHS ||
            (gatewayConfig.carelogRegistrationEnabled && request.uri.path == REGISTRATION_PATH))

    private companion object {
        const val REGISTRATION_PATH = "/api/v1/auth/carelog/registration"
        val KAKAO_OAUTH_PATHS = setOf(
            "/api/v1/auth/oauth/kakao/authorization",
            "/api/v1/auth/oauth/kakao/exchange"
        )
    }
}
