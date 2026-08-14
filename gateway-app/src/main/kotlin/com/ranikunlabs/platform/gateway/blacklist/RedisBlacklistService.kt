package com.ranikunlabs.platform.gateway.blacklist

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class RedisBlacklistService(
    private val redisTemplate: ReactiveStringRedisTemplate
) {

    fun isBlacklisted(rawAccessToken: String): Mono<Boolean> =
        redisTemplate.hasKey("blacklist:$rawAccessToken")
}
