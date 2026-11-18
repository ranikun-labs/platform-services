package com.ranikunlabs.platform.gateway.ratelimit

import java.time.Duration
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.support.ConfigurationService
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.validation.Validator
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/** Exercises Spring Cloud Gateway's shipped RedisRateLimiter Lua script against the V1 Valkey image. */
class RedisRateLimiterValkeyIntegrationTest {

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var rateLimiter: RedisRateLimiter
    private lateinit var failClosedRateLimiter: OAuthFailClosedRateLimiter

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(VALKEY.host, VALKEY.getMappedPort(6379))
        )
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)

        val script = DefaultRedisScript<List<Long>>()
        script.setLocation(ClassPathResource("META-INF/scripts/request_rate_limiter.lua"))
        @Suppress("UNCHECKED_CAST")
        script.setResultType(List::class.java as Class<List<Long>>)

        val configurationService = ConfigurationService(
            DefaultListableBeanFactory(),
            Supplier { DefaultConversionService.getSharedInstance() },
            Supplier<Validator> { null }
        )
        rateLimiter = RedisRateLimiter(redisTemplate, script, configurationService)
        rateLimiter.config[ROUTE_ID] = RedisRateLimiter.Config()
            .setReplenishRate(1)
            .setBurstCapacity(100)
            .setRequestedTokens(50)
        failClosedRateLimiter = OAuthFailClosedRateLimiter(rateLimiter, redisTemplate, script)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `fail closed wrapper accepts the Gateway Lua tuple and denies excess requests`() {
        val first = failClosedRateLimiter.isAllowed(ROUTE_ID, CLIENT_ID).block(Duration.ofSeconds(5))
        val second = failClosedRateLimiter.isAllowed(ROUTE_ID, CLIENT_ID).block(Duration.ofSeconds(5))
        val third = failClosedRateLimiter.isAllowed(ROUTE_ID, CLIENT_ID).block(Duration.ofSeconds(5))

        assertThat(first?.isAllowed).isTrue()
        assertThat(second?.isAllowed).isTrue()
        assertThat(third?.isAllowed).isFalse()
    }

    companion object {
        private const val ROUTE_ID = "valkey-rate-limit-contract"
        private const val CLIENT_ID = "203.0.113.10"

        private val VALKEY: GenericContainer<*> = GenericContainer<Nothing>(
            DockerImageName.parse(
                "valkey/valkey:9.1.2-alpine@sha256:48332870af354a799964c0012ae1194a0bf2bf894eb508f945810596dc2d8d11"
            )
        ).withExposedPorts(6379)

        @JvmStatic
        @BeforeAll
        fun startValkey() {
            VALKEY.start()
        }

        @JvmStatic
        @AfterAll
        fun stopValkey() {
            VALKEY.stop()
        }
    }
}
