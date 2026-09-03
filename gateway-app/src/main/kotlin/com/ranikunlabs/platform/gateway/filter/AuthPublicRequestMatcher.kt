package com.ranikunlabs.platform.gateway.filter

import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component

/** Exact public boundary for non-OAuth Identity Auth endpoints. */
@Component
class AuthPublicRequestMatcher {

    fun matches(request: ServerHttpRequest): Boolean =
        request.method in PUBLIC_METHODS && request.uri.path in IDENTITY_AUTH_PUBLIC_PATHS

    private companion object {
        val PUBLIC_METHODS = setOf(HttpMethod.POST, HttpMethod.OPTIONS)
        val IDENTITY_AUTH_PUBLIC_PATHS = setOf(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
        )
    }
}
