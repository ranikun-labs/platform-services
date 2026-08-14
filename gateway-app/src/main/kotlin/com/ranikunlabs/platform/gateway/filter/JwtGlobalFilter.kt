package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import io.jsonwebtoken.JwtException
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class JwtGlobalFilter(
    private val jwtVerifier: JwtVerifier,
    private val blacklistService: RedisBlacklistService,
    private val gatewayConfig: GatewayConfig,
    private val oauthPublicRequestMatcher: OAuthPublicRequestMatcher
) : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.uri.path
        val sanitizedExchange = exchange.withoutTrustedHeaders()

        if (oauthPublicRequestMatcher.matches(request) || gatewayConfig.publicPaths.any(path::startsWith)) {
            return chain.filter(sanitizedExchange.withGatewaySecret(gatewayConfig.internalSecret))
        }

        val authorization = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        val rawAccessToken = authorization.removePrefix("Bearer ")
        val claims = try {
            jwtVerifier.verifyAndGetClaims(rawAccessToken)
        } catch (exception: JwtException) {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        return blacklistService.isBlacklisted(rawAccessToken).flatMap { blacklisted ->
            if (blacklisted) {
                exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                exchange.response.setComplete()
            } else {
                chain.filter(
                    sanitizedExchange.withTrustedClaims(
                        subject = claims.subject,
                        organizationId = claims["organizationId"],
                        role = claims["role"]?.toString() ?: "",
                        publicId = claims["publicId"],
                        internalSecret = gatewayConfig.internalSecret
                    )
                )
            }
        }
    }

    override fun getOrder(): Int = -1

    private fun ServerWebExchange.withoutTrustedHeaders(): ServerWebExchange = mutate()
        .request {
            it.headers { headers ->
                headers.remove("X-User-Id")
                headers.remove("X-Organization-Id")
                headers.remove("X-Role")
                headers.remove("X-Public-Id")
                headers.remove("X-Gateway-Secret")
            }
        }
        .build()

    private fun ServerWebExchange.withGatewaySecret(secret: String): ServerWebExchange = mutate()
        .request { it.headers { headers -> headers.set("X-Gateway-Secret", secret) } }
        .build()

    private fun ServerWebExchange.withTrustedClaims(
        subject: String?,
        organizationId: Any?,
        role: String,
        publicId: Any?,
        internalSecret: String
    ): ServerWebExchange = mutate()
        .request {
            it.headers { headers ->
                headers.set("X-User-Id", subject)
                organizationId?.let { value -> headers.set("X-Organization-Id", value.toString()) }
                headers.set("X-Role", role)
                publicId?.let { value -> headers.set("X-Public-Id", value.toString()) }
                headers.set("X-Gateway-Secret", internalSecret)
            }
        }
        .build()
}
