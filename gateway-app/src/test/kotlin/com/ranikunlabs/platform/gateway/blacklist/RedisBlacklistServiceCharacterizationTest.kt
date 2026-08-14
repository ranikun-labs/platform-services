package com.ranikunlabs.platform.gateway.blacklist

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono

class RedisBlacklistServiceCharacterizationTest {

    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var service: RedisBlacklistService

    @BeforeEach
    fun setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        service = RedisBlacklistService(redisTemplate)
    }

    @Test
    fun `queries exactly blacklist raw token and returns present`() {
        `when`(redisTemplate.hasKey("blacklist:raw-access-token")).thenReturn(Mono.just(true))

        assertThat(service.isBlacklisted("raw-access-token").blockOptional()).contains(true)
        verify(redisTemplate).hasKey("blacklist:raw-access-token")
    }

    @Test
    fun `propagates Redis lookup failure`() {
        val failure = RuntimeException("redis down")
        `when`(redisTemplate.hasKey("blacklist:raw-access-token")).thenReturn(Mono.error(failure))

        assertThatThrownBy { service.isBlacklisted("raw-access-token").block() }
            .isSameAs(failure)
    }
}
