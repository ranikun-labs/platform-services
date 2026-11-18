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

    @Test
    fun `accepts valid Redis response values at the documented boundaries`() {
        val validResponses = listOf(
            listOf(1L, 9L) to true,
            listOf(1L, 0L) to true,
            listOf(0L, 0L) to false
        )

        validResponses.forEach { (result, isAllowed) ->
            val fixture = capturingLimiter(result)
            val response = fixture.limiter
                .isAllowed("kakao-oauth-authorization", "203.0.113.10")
                .block()!!

            assertThat(response.isAllowed).isEqualTo(isAllowed)
            assertThat(response.headers).containsEntry(
                "X-RateLimit-Remaining",
                result[1].toString()
            )
        }
    }

    @Test
    fun `maps malformed and out of contract Redis script responses to the fail closed signal`() {
        val invalidResponses: List<List<*>> = listOf(
            emptyList(),
            listOf(1L),
            listOf(1L, 9L, 0L),
            listOf(-1L, 0L),
            listOf(2L, 0L),
            listOf(1L, -1L),
            listOf(1L, 11L),
            listOf(1L, 10L),
            listOf(0L, 1L),
            listOf(1.0, 9L),
            listOf(1L, 9.0),
            listOf(1.5, 9L),
            listOf(1L, 9.5),
            listOf(1.toInt(), 9L),
            listOf("1", 9L)
        )

        invalidResponses.forEach { result ->
            val fixture = capturingLimiter(result)
            val failure = catchThrowable {
                fixture.limiter
                    .isAllowed("kakao-oauth-authorization", "203.0.113.10")
                    .block()
            }

            assertThat(failure)
                .describedAs("Redis script result $result")
                .isInstanceOf(OAuthRateLimitInfrastructureException::class.java)
        }
    }

    @Test
    fun `requires exactly one Redis result tuple`() {
        val invalidEmissions = listOf(
            emptyList<List<*>>(),
            listOf(listOf(1L, 9L), listOf(0L, 0L))
        )

        invalidEmissions.forEach { emissions ->
            val fixture = capturingLimiter(emissions = emissions)
            val failure = catchThrowable {
                fixture.limiter
                    .isAllowed("kakao-oauth-authorization", "203.0.113.10")
                    .block()
            }

            assertThat(failure)
                .describedAs("Redis result emissions $emissions")
                .isInstanceOf(OAuthRateLimitInfrastructureException::class.java)
        }
    }

    private fun capturingLimiter(
        result: List<*>? = null,
        emissions: List<List<*>>? = null
    ): CapturedLimiter {
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
            val executedKeys = invocation.getArgument<List<String>>(1)
            keys += executedKeys
            val response = result ?: if (executedKeys.first().contains("kakao-oauth-exchange")) {
                listOf(1L, 5L)
            } else {
                listOf(1L, 9L)
            }
            Flux.fromIterable(emissions ?: listOf(response))
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
