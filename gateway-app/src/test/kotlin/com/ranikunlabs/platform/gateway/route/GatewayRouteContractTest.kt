package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import com.ranikunlabs.platform.gateway.ratelimit.OAuthFailClosedRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=localhost",
        "PLATFORM_GATEWAY_REDIS_PORT=6379",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_AUTH_URI=http://platform-core.test:8081",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://localhost:8080",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://localhost:8081",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=integration-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-integration-secret-key-long-enough"
    ]
)
class GatewayRouteContractTest(
    @Autowired private val routeLocator: RouteLocator,
    @Autowired private val gatewayConfig: GatewayConfig,
    @Autowired private val oauthFailClosedRateLimiter: OAuthFailClosedRateLimiter
) {

    @Test
    fun `preserves API and RAG routes and places auth routes before Carelog`() {
        val routes = routeLocator.routes.collectList().block()!!

        assertThat(routes.map(Route::getId)).containsSubsequence(
            "kakao-oauth-authorization",
            "kakao-oauth-exchange",
            "platform-identity-auth",
            "carelog-be",
            "carelog-rag"
        )
        assertThat(routes.indexOf(route(routes, "kakao-oauth-authorization")))
            .isLessThan(routes.indexOf(route(routes, "platform-identity-auth")))
        assertThat(routes.indexOf(route(routes, "platform-identity-auth")))
            .isLessThan(routes.indexOf(route(routes, "carelog-be")))
        assertThat(route(routes, "kakao-oauth-authorization").order).isEqualTo(-20)
        assertThat(route(routes, "kakao-oauth-exchange").order).isEqualTo(-20)
        assertThat(route(routes, "platform-identity-auth").order).isEqualTo(-10)
        assertThat(route(routes, "carelog-be").order).isEqualTo(0)
        assertThat(route(routes, "carelog-be").matches(HttpMethod.GET, "/api/v1/patients")).isTrue()
        assertThat(route(routes, "carelog-rag").matches(HttpMethod.POST, "/rag/query")).isTrue()
    }

    @Test
    fun `OAuth routes match only exact POST paths`() {
        val routes = routeLocator.routes.collectList().block()!!
        val authorization = route(routes, "kakao-oauth-authorization")
        val exchange = route(routes, "kakao-oauth-exchange")
        val identityAuth = route(routes, "platform-identity-auth")
        val carelogBe = route(routes, "carelog-be")

        assertThat(authorization.uri).isEqualTo(URI.create("http://platform-core.test:8081"))
        assertThat(exchange.uri).isEqualTo(URI.create("http://platform-core.test:8081"))
        assertThat(identityAuth.uri).isEqualTo(URI.create("http://platform-core.test:8081"))
        assertThat(authorization.matches(HttpMethod.POST, authorizationPath)).isTrue()
        assertThat(exchange.matches(HttpMethod.POST, exchangePath)).isTrue()
        assertThat(authorization.matches(HttpMethod.GET, authorizationPath)).isFalse()
        assertThat(exchange.matches(HttpMethod.PUT, exchangePath)).isFalse()
        assertThat(authorization.matches(HttpMethod.POST, "$authorizationPath/extra")).isFalse()
        assertThat(carelogBe.matches(HttpMethod.POST, authorizationPath)).isTrue()
    }

    @Test
    fun `the complete public Auth namespace selects Identity before the Carelog fallback`() {
        val routes = routeLocator.routes.collectList().block()!!

        assertThat(firstMatching(routes, HttpMethod.POST, "/api/v1/auth/login").id)
            .isEqualTo("platform-identity-auth")
        assertThat(firstMatching(routes, HttpMethod.POST, "/api/v1/auth/refresh").id)
            .isEqualTo("platform-identity-auth")
        assertThat(firstMatching(routes, HttpMethod.POST, "/api/v1/auth/logout").id)
            .isEqualTo("platform-identity-auth")
        assertThat(firstMatching(routes, HttpMethod.POST, "/api/v1/auth/something").id)
            .isEqualTo("platform-identity-auth")
    }

    @Test
    fun `uses the approved independent OAuth bucket limits and one-hop default`() {
        assertThat(gatewayConfig.oauthRateLimit.trustedProxyHops).isEqualTo(1)
        assertThat(gatewayConfig.oauthRateLimit.authorization.replenishRate).isEqualTo(5)
        assertThat(gatewayConfig.oauthRateLimit.authorization.burstCapacity).isEqualTo(10)
        assertThat(gatewayConfig.oauthRateLimit.exchange.replenishRate).isEqualTo(3)
        assertThat(gatewayConfig.oauthRateLimit.exchange.burstCapacity).isEqualTo(6)
        assertThat(oauthFailClosedRateLimiter.config.keys).containsExactlyInAnyOrder(
            "kakao-oauth-authorization",
            "kakao-oauth-exchange"
        )
    }

    private fun route(routes: List<Route>, id: String): Route = routes.single { it.id == id }

    private fun firstMatching(routes: List<Route>, method: HttpMethod, path: String): Route =
        routes.first { it.matches(method, path) }

    private fun Route.matches(method: HttpMethod, path: String): Boolean =
        Mono.from(
            predicate.apply(
                MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build())
            )
        ).block()!!

    private companion object {
        const val authorizationPath = "/api/v1/auth/oauth/kakao/authorization"
        const val exchangePath = "/api/v1/auth/oauth/kakao/exchange"
    }
}
