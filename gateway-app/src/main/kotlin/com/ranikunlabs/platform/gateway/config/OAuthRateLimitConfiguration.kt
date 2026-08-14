package com.ranikunlabs.platform.gateway.config

import com.ranikunlabs.platform.gateway.ratelimit.OAuthClientIpKeyResolver
import com.ranikunlabs.platform.gateway.ratelimit.OAuthFailClosedRateLimiter
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

@Configuration(proxyBeanMethods = false)
class OAuthRateLimitConfiguration {

    @Bean("oauthClientIpKeyResolver")
    fun oauthClientIpKeyResolver(gatewayConfig: GatewayConfig): KeyResolver =
        OAuthClientIpKeyResolver(gatewayConfig.oauthRateLimit)

    @Bean("oauthFailClosedRateLimiter")
    @Primary
    fun oauthFailClosedRateLimiter(
        redisRateLimiter: RedisRateLimiter,
        redisTemplate: ReactiveStringRedisTemplate,
        redisScript: RedisScript<List<Long>>
    ): OAuthFailClosedRateLimiter = OAuthFailClosedRateLimiter(redisRateLimiter, redisTemplate, redisScript)
}
