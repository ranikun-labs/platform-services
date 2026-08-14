package com.ranikunlabs.platform.gateway.filter

import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component

@Component
class OAuthPublicRequestMatcher {

    fun matches(request: ServerHttpRequest): Boolean =
        request.method == HttpMethod.POST && request.uri.path in KAKAO_OAUTH_PATHS

    private companion object {
        val KAKAO_OAUTH_PATHS = setOf(
            "/api/v1/auth/oauth/kakao/authorization",
            "/api/v1/auth/oauth/kakao/exchange"
        )
    }
}
