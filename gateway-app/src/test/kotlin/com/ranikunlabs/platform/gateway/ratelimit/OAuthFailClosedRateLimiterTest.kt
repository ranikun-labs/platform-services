package com.ranikunlabs.platform.gateway.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class OAuthFailClosedRateLimiterTest {

    @Test
    fun `authorization uses the exact SCG Redis keys`() {
        val fixture = capturingLimiter()

        fixture.limiter
            .isAllowed("kakao-oauth-authorization", "203.0.113.10")
            .block()

        assertThat(fixture.keys).containsExactly(
            listOf(
                "request_rate_limiter.{kakao-oauth-authorization.203.0.113.10}.tokens",
                "request_rate_limiter.{kakao-oauth-authorization.203.0.113.10}.timestamp"
            )
        )
        assertThat(fixture.keys).doesNotContain(
            listOf(
                "request_rate_limiter.{203.0.113.10.kakao-oauth-authorization}.tokens",
                "request_rate_limiter.{203.0.113.10.kakao-oauth-authorization}.timestamp"
            )
        )
    }

    @Test
    fun `exchange uses the exact SCG Redis keys`() {
        val fixture = capturingLimiter()

        fixture.limiter
            .isAllowed("kakao-oauth-exchange", "203.0.113.10")
            .block()

        assertThat(fixture.keys).containsExactly(
            listOf(
                "request_rate_limiter.{kakao-oauth-exchange.203.0.113.10}.tokens",
                "request_rate_limiter.{kakao-oauth-exchange.203.0.113.10}.timestamp"
            )
        )
        assertThat(fixture.keys).doesNotContain(
            listOf(
                "request_rate_limiter.{203.0.113.10.kakao-oauth-exchange}.tokens",
                "request_rate_limiter.{203.0.113.10.kakao-oauth-exchange}.timestamp"
            )
        )
    }

    @Test
    fun `tokens and timestamp share a hash tag while route buckets remain independent`() {
        val fixture = capturingLimiter()

        fixture.limiter.isAllowed("kakao-oauth-authorization", "203.0.113.10").block()
        fixture.limiter.isAllowed("kakao-oauth-exchange", "203.0.113.10").block()

        val authorizationKeys = fixture.keys[0]
        val exchangeKeys = fixture.keys[1]
        assertThat(hashTag(authorizationKeys[0]))
            .isEqualTo("kakao-oauth-authorization.203.0.113.10")
        assertThat(hashTag(exchangeKeys[0]))
            .isEqualTo("kakao-oauth-exchange.203.0.113.10")
        assertThat(hashTag(authorizationKeys[0])).isEqualTo(hashTag(authorizationKeys[1]))
        assertThat(hashTag(exchangeKeys[0])).isEqualTo(hashTag(exchangeKeys[1]))
        assertThat(hashTag(authorizationKeys[0])).isNotEqualTo(hashTag(exchangeKeys[0]))
        assertThat(fixture.keys.flatten()).allMatch { it.startsWith("request_rate_limiter.") }
    }

    @Test
    fun `maps Redis limiter failures to the fail closed signal`() {
        val delegate = mock(RedisRateLimiter::class.java)
        val redisFailure = IllegalStateException("redis unavailable")
        `when`(delegate.isAllowed("kakao-oauth-authorization", "203.0.113.10"))
            .thenReturn(Mono.error(redisFailure))

        val failure = catchThrowable {
            OAuthFailClosedRateLimiter(delegate)
                .isAllowed("kakao-oauth-authorization", "203.0.113.10")
                .block()
        }

        assertThat(failure)
            .isInstanceOf(OAuthRateLimitInfrastructureException::class.java)
            .hasCause(redisFailure)
    }

    private fun capturingLimiter(): CapturedLimiter {
        val delegate = RedisRateLimiter(5, 10)
        delegate.config["kakao-oauth-authorization"] = RedisRateLimiter.Config()
            .setReplenishRate(5)
            .setBurstCapacity(10)
        delegate.config["kakao-oauth-exchange"] = RedisRateLimiter.Config()
            .setReplenishRate(3)
            .setBurstCapacity(6)

        val redisTemplate = mock(ReactiveStringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val script = mock(RedisScript::class.java) as RedisScript<List<Long>>
        val keys = mutableListOf<List<String>>()
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            keys += invocation.getArgument<List<String>>(1)
            Flux.just(listOf(1L, 9L))
        }.`when`(redisTemplate)
            .execute(eq(script), anyList<String>(), anyList<Any>())

        return CapturedLimiter(
            OAuthFailClosedRateLimiter(delegate, redisTemplate, script),
            keys
        )
    }

    private fun hashTag(key: String): String =
        key.substringAfter('{').substringBefore('}')

    private data class CapturedLimiter(
        val limiter: OAuthFailClosedRateLimiter,
        val keys: MutableList<List<String>>
    )
}
