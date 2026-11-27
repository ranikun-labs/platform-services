package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI

/**
 * Real Gateway chain with default configuration (finance trusted-context and finance-reference off).
 * Every case here must be decided before any upstream is contacted, so no upstream is needed.
 */
@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
        "PLATFORM_GATEWAY_REDIS_PORT=1",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:1",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:1",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:1",
        "PLATFORM_GATEWAY_FINANCE_BE_URI=http://127.0.0.1:1",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=path-safety-internal-secret-long-enough-for-test",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=path-safety-jwt-secret-long-enough-for-hs256-test"
    ]
)
@AutoConfigureWebTestClient
class GatewayPathSafetyHttpIntegrationTest(@Autowired private val webTestClient: WebTestClient) {

    @ParameterizedTest
    @ValueSource(strings = [
        // encoded slash/dot tricks aimed at re-routing /api/finance into the Identity login path
        "/api/finance/x%2F..%2F..%2Fv1%2Fauth%2Flogin",
        "/api/finance/journals/%2e%2e/%2e%2e/v1/auth/login",
        "/api/finance/journals/%2E%2E/x",
        // literal dot segments
        "/api/finance/journals/../../api/v1/auth/login",
        "/api/v1/auth/./login",
        // encoded backslash
        "/api/finance/journals%5cx",
        "/api/finance/journals%5Cx",
        // semicolon path parameters
        "/api/v1/auth/login;x=1",
        "/api/finance/journals;jsessionid=1"
    ])
    fun `ambiguous paths are rejected with 400 before routing or authentication`(rawPath: String) {
        webTestClient.get()
            .uri(URI.create("http://localhost$rawPath"))
            .exchange()
            .expectStatus().isBadRequest
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/auth/loginX",
        "/api/v1/auth/login/extra",
        "/api/v1/auth/%6Cogin",
        "/api/v1/users/managersX",
        "/api/v1/users/managersX/extra"
    ])
    fun `public boundary is exact or segment-bounded so near-miss paths require authentication`(rawPath: String) {
        webTestClient.method(HttpMethod.POST)
            .uri(URI.create("http://localhost$rawPath"))
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `finance route fails closed with 404 by default even for a request with no credentials`() {
        webTestClient.get()
            .uri(URI.create("http://localhost/api/finance/journals/deleted"))
            .exchange()
            .expectStatus().isNotFound
    }
}
