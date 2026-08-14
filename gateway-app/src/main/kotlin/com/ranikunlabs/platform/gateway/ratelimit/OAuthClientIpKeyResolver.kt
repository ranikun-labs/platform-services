package com.ranikunlabs.platform.gateway.ratelimit

import com.ranikunlabs.platform.gateway.config.OAuthRateLimitConfig
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class OAuthClientIpKeyResolver(
    oauthRateLimitConfig: OAuthRateLimitConfig
) : KeyResolver {

    private val remoteAddressResolver =
        XForwardedRemoteAddressResolver.maxTrustedIndex(oauthRateLimitConfig.trustedProxyHops)

    override fun resolve(exchange: ServerWebExchange): Mono<String> = Mono.defer {
        val address = remoteAddressResolver.resolve(exchange)
        val key = address?.address?.hostAddress ?: address?.hostString
        if (key.isNullOrBlank()) Mono.empty() else Mono.just(key)
    }
}
