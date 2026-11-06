package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import org.springframework.dao.DataAccessException
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

internal const val VERIFIED_IDENTITY_SUBJECT_ATTRIBUTE =
    "com.ranikunlabs.platform.gateway.verifiedIdentitySubject"

@Component
class JwtGlobalFilter(
    private val jwtVerifier: JwtVerifier,
    private val blacklistService: RedisBlacklistService,
    private val gatewayConfig: GatewayConfig,
    private val authPublicRequestMatcher: AuthPublicRequestMatcher,
    private val oauthPublicRequestMatcher: OAuthPublicRequestMatcher
) : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.uri.path
        val sanitizedExchange = exchange.withoutTrustedHeaders()
        val financePath = FinanceTrustedContextWire.isFinanceJournalNamespace(path)

        if (isInternalPath(path)) {
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            return exchange.response.setComplete()
        }

        if (financePath && !gatewayConfig.financeTrustedContext.enabled) {
            exchange.response.statusCode = HttpStatus.NOT_FOUND
            return exchange.response.setComplete()
        }

        if (!financePath && (authPublicRequestMatcher.matches(request)
            || oauthPublicRequestMatcher.matches(request)
            || gatewayConfig.publicPaths.any(path::startsWith))
        ) {
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
        } catch (exception: IllegalArgumentException) {
            if (!financePath) throw exception
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        if (financePath) {
            return blacklistService.isBlacklisted(rawAccessToken).materialize().flatMap { result ->
                when {
                    result.isOnError && result.throwable is DataAccessException ->
                        identityVerificationUnavailable(exchange)
                    result.isOnError -> Mono.error(result.throwable!!)
                    result.isOnNext -> continueAfterBlacklist(
                        exchange, sanitizedExchange, chain, claims, result.get(), financePath = true
                    )
                    else -> identityVerificationUnavailable(exchange)
                }
            }
        }

        return blacklistService.isBlacklisted(rawAccessToken).flatMap { blacklisted ->
            continueAfterBlacklist(exchange, sanitizedExchange, chain, claims, blacklisted, financePath = false)
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
                headers.remove("X-Service-Id")
                headers.remove("X-Service-Secret")
                headers.keys
                    .filter { name -> name.startsWith("X-Finance-", ignoreCase = true) }
                    .forEach(headers::remove)
            }
        }
        .build()

    private fun ServerWebExchange.withVerifiedIdentitySubject(subject: String?): ServerWebExchange {
        subject?.let { attributes[VERIFIED_IDENTITY_SUBJECT_ATTRIBUTE] = it }
        return this
    }

    private fun continueAfterBlacklist(
        exchange: ServerWebExchange,
        sanitizedExchange: ServerWebExchange,
        chain: GatewayFilterChain,
        claims: io.jsonwebtoken.Claims,
        blacklisted: Boolean,
        financePath: Boolean
    ): Mono<Void> {
        if (blacklisted) {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }
        if (financePath) {
            if (claims.subject.isNullOrBlank() || !hasFinanceAccessMarkers(claims)) {
                exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                return exchange.response.setComplete()
            }
            return chain.filter(sanitizedExchange.withVerifiedIdentitySubject(claims.subject))
        }
        return chain.filter(
            sanitizedExchange.withVerifiedIdentitySubject(claims.subject).withTrustedClaims(
                subject = claims.subject,
                organizationId = claims["organizationId"],
                role = claims["role"]?.toString() ?: "",
                publicId = claims["publicId"],
                internalSecret = gatewayConfig.internalSecret
            )
        )
    }

    private fun identityVerificationUnavailable(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body = "{\"code\":\"identity_verification_unavailable\"}"
            .toByteArray(Charsets.UTF_8)
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body)))
    }

    private fun isInternalPath(path: String): Boolean =
        path == "/api/v1/internal" || path.startsWith("/api/v1/internal/")

    private fun hasFinanceAccessMarkers(claims: Claims): Boolean {
        val organizationId = claims["organizationId"] as? String ?: return false
        val role = claims["role"] as? String ?: return false
        val publicId = claims["publicId"] as? String ?: return false
        return isUuid(organizationId) && role.isNotBlank() && isUuid(publicId)
    }

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

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
