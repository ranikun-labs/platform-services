package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import com.ranikunlabs.platform.gateway.ratelimit.OAuthFailClosedRateLimiter
import com.ranikunlabs.platform.gateway.ratelimit.OAuthRateLimitInfrastructureException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(classes = [GatewayApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "logging.level.org.springframework.web.cors=DEBUG",
        "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:39158",
        "PLATFORM_GATEWAY_REDIS_HOST=localhost",
        "PLATFORM_GATEWAY_REDIS_PORT=6379",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://localhost:8080",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://localhost:8081",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=test-secret",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=registration-test-secret-long-enough-123456",
        "PLATFORM_GATEWAY_CARELOG_REGISTRATION_ENABLED=true",
        "platform.gateway.carelog-registration-allowed-origins[0]=https://carelog.example"
    ])
@AutoConfigureWebTestClient
class CarelogRegistrationHttpIntegrationTest {
    @Autowired private lateinit var client: WebTestClient
    @Autowired private lateinit var config: GatewayConfig
    @MockBean(name = "oauthFailClosedRateLimiter") private lateinit var limiter: OAuthFailClosedRateLimiter

    @BeforeEach
    fun setup() {
        assertThat(config.carelogRegistrationEnabled).isTrue()
        assertThat(config.carelogRegistrationAllowedOrigins).containsExactly("https://carelog.example")
        hits.set(0)
        idempotencyKey.set(null)
        Mockito.`when`(limiter.isAllowed(anyString(), anyString()))
            .thenReturn(Mono.just(RateLimiter.Response(true, emptyMap())))
    }

    @Test
    fun `forwards limited signup to Core retaining operation key`() {
        client.post().uri("http://localhost" + path).header("X-Forwarded-For", "203.0.113.10").header("Idempotency-Key", "test-operation")
            .exchange().expectStatus().isOk
        assertThat(hits).hasValue(1)
        assertThat(idempotencyKey.get()).isEqualTo("test-operation")
        Mockito.verify(limiter).isAllowed("carelog-registration", "203.0.113.10")
    }

    @Test
    fun `rejects rejected and unavailable rate limits before upstream`() {
        Mockito.`when`(limiter.isAllowed(anyString(), anyString()))
            .thenReturn(Mono.just(RateLimiter.Response(false, emptyMap())))
        client.post().uri("http://localhost" + path).header("X-Forwarded-For", "203.0.113.10").exchange().expectStatus().isEqualTo(429)
        assertThat(hits).hasValue(0)
        Mockito.`when`(limiter.isAllowed(anyString(), anyString())).thenReturn(
            Mono.error(OAuthRateLimitInfrastructureException(IllegalStateException("unavailable")))
        )
        client.post().uri("http://localhost" + path).header("X-Forwarded-For", "203.0.113.10").exchange().expectStatus().isEqualTo(503)
        assertThat(hits).hasValue(0)
    }

    @Test
    fun `preflight accepts only configured origin and required headers`() {
        client.options().uri("http://localhost" + path).header(HttpHeaders.ORIGIN, "https://carelog.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, Idempotency-Key")
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://carelog.example")
        client.options().uri("http://localhost" + path).header(HttpHeaders.ORIGIN, "https://other.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .exchange().expectStatus().isForbidden
        assertThat(hits).hasValue(0)
    }

    companion object {
        private const val path = "/api/v1/auth/carelog/registration"
        private val hits = AtomicInteger()
        private val idempotencyKey = AtomicReference<String?>()
        private val server = HttpServer.create().host("127.0.0.1").port(39158)
            .handle { req, res ->
                hits.incrementAndGet()
                idempotencyKey.set(req.requestHeaders().get("Idempotency-Key"))
                res.sendString(Mono.just("OK"))
            }.bindNow()

        @JvmStatic @AfterAll
        fun stop() = server.disposeNow()
    }
}
