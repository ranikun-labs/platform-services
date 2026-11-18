package com.ranikunlabs.platform.gateway.ratelimit

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono

class OAuthFailClosedRateLimiter(
    private val delegate: RedisRateLimiter,
    private val redisTemplate: ReactiveStringRedisTemplate? = null,
    private val redisScript: RedisScript<List<Long>>? = null
) : RateLimiter<RedisRateLimiter.Config> {

    override fun isAllowed(routeId: String, id: String): Mono<RateLimiter.Response> {
        if (redisTemplate == null || redisScript == null) {
            return delegate.isAllowed(routeId, id)
                .onErrorMap { cause -> OAuthRateLimitInfrastructureException(cause) }
        }

        val config = delegate.config[routeId]
            ?: return Mono.error(
                OAuthRateLimitInfrastructureException(
                    IllegalStateException("No Redis rate-limit configuration for route")
                )
            )
        val keys = redisKeys(routeId, id)
        val args = listOf(
            config.replenishRate.toString(),
            config.burstCapacity.toString(),
            "",
            config.requestedTokens.toString()
        )

        return redisTemplate.execute(redisScript, keys, args)
            .reduce(ArrayList<Long>()) { values, result ->
                values.apply { addAll(result) }
            }
            .map { values ->
                if (values.size != 2) {
                    throw IllegalStateException("Unexpected Redis rate limiter response")
                }

                val allowed = values[0]
                val remainingTokens = values[1]
                if (
                    allowed !in 0L..1L ||
                    remainingTokens < 0L ||
                    remainingTokens > config.burstCapacity.toLong()
                ) {
                    throw IllegalStateException("Invalid Redis rate limiter response")
                }

                RateLimiter.Response(
                    allowed == 1L,
                    delegate.getHeaders(config, remainingTokens)
                )
            }
            .onErrorMap { cause ->
                if (cause is OAuthRateLimitInfrastructureException) cause
                else OAuthRateLimitInfrastructureException(cause)
            }
    }

    private fun redisKeys(routeId: String, id: String): List<String> {
        val prefix = "request_rate_limiter.{$routeId.$id}"
        return listOf("$prefix.tokens", "$prefix.timestamp")
    }

    override fun getConfigClass(): Class<RedisRateLimiter.Config> = delegate.configClass

    override fun newConfig(): RedisRateLimiter.Config = delegate.newConfig()

    override fun getConfig(): Map<String, RedisRateLimiter.Config> = delegate.config
}

class OAuthRateLimitInfrastructureException(cause: Throwable) : RuntimeException(cause)
