package com.ranikunlabs.platform.gateway.filter

import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import com.ranikunlabs.platform.gateway.config.GatewayConfig

/** Exact public boundary for non-OAuth Identity Auth endpoints. */
@Component
class AuthPublicRequestMatcher {

    private var financeReferenceEnabled: Boolean = false

    @Autowired
    constructor(gatewayConfig: GatewayConfig) {
        financeReferenceEnabled = gatewayConfig.financeReference.enabled
    }

    constructor() {}

    fun matches(request: ServerHttpRequest): Boolean =
        (request.method in PUBLIC_METHODS && request.uri.path in IDENTITY_AUTH_PUBLIC_PATHS) ||
            (financeReferenceEnabled && request.method == HttpMethod.POST &&
                request.uri.path == FINANCE_REFERENCE_LOGIN_PATH)

    private companion object {
        val PUBLIC_METHODS = setOf(HttpMethod.POST, HttpMethod.OPTIONS)
        val IDENTITY_AUTH_PUBLIC_PATHS = setOf(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
        )
        const val FINANCE_REFERENCE_LOGIN_PATH = "/api/v1/auth/finance-reference/login"
    }
}
