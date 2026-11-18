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
            .single()
            .map { result ->
                val values = result as List<*>
                if (values.size != 2) {
                    throw IllegalStateException("Unexpected Redis rate limiter response")
                }

                val rawAllowed = values[0]
                val rawRemainingTokens = values[1]
                if (rawAllowed !is Long || rawRemainingTokens !is Long) {
                    throw IllegalStateException("Invalid Redis rate limiter response types")
                }

                val allowed = rawAllowed
                val remainingTokens = rawRemainingTokens
                val burstCapacity = config.burstCapacity.toLong()
                val requestedTokens = config.requestedTokens.toLong()
                if (
                    allowed !in 0L..1L ||
                    remainingTokens < 0L ||
                    remainingTokens > burstCapacity ||
                    (allowed == 1L && (
                        requestedTokens > burstCapacity ||
                            remainingTokens > burstCapacity - requestedTokens
                        )) ||
                    (allowed == 0L && remainingTokens >= requestedTokens)
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
