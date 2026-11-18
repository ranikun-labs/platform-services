package com.ranikunlabs.platform.gateway.route

import com.ranikunlabs.platform.gateway.GatewayApplication
import com.ranikunlabs.platform.gateway.blacklist.RedisBlacklistService
import com.ranikunlabs.platform.gateway.filter.FINANCE_TRUSTED_CONTEXT_PROFILE
import com.ranikunlabs.platform.gateway.filter.FinanceTrustedContextWire
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Exercises the real Gateway HTTP/filter chain while keeping the Finance route and
 * upstream strictly in test configuration. No Finance upstream is added to application.yml.
 */
@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "PLATFORM_GATEWAY_PORT=0",
        "PLATFORM_GATEWAY_MANAGEMENT_PORT=0",
        "PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
        "PLATFORM_GATEWAY_REDIS_PORT=1",
        "PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
        "PLATFORM_GATEWAY_AUTH_URI=http://127.0.0.1:39155",
        "PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:39156",
        "PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:39157",
        "PLATFORM_GATEWAY_INTERNAL_SECRET=Gateway-D1-Reference-Public-Dummy-Carelog-Secret-Do-Not-Use-In-Production-2026",
        "PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=Gateway-D1-Reference-Public-Dummy-JWT-Key-Do-Not-Use-In-Production-2026",
        "platform.gateway.finance-trusted-context.enabled=false",
        "platform.gateway.finance-trusted-context.service-secret=Finance-D1-Reference-Public-Dummy-Secret-Do-Not-Use-In-Production-2026",
        "platform.gateway.finance-reference.enabled=true",
        "platform.gateway.finance-reference.jwt.verification-key=Finance-Reference-Test-Only-Public-Dummy-Key-2026-With-HS384-Margin",
        "platform.gateway.finance-reference.issuer=urn:ranikunlabs:identity:finance:reference",
        "platform.gateway.finance-reference.audience=finance-journal-reference",
        "platform.gateway.finance-reference.purpose=access",
        "platform.gateway.finance-reference.clock-skew-seconds=30",
        "platform.gateway.finance-reference.max-token-lifetime-seconds=300"
    ]
)
@ActiveProfiles(FINANCE_TRUSTED_CONTEXT_PROFILE)
@AutoConfigureWebTestClient
@Import(FinanceReferenceHttpIntegrationTest.TestInfrastructureConfiguration::class)
class FinanceReferenceHttpIntegrationTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun resetSyntheticUpstream() {
        SyntheticFinanceUpstream.requests.clear()
    }

    @Test
    fun `strict Finance lifecycle HTTP routes preserve verified identity and request body`() {
        val accessToken = referenceToken(ACCOUNT_A, "access")
        val transitionBody = "{\"expectedRevision\":7}"

        webTestClient.get()
            .uri("/finance/journals/deleted")
            .headers { headers -> headers.setBearerAuth(accessToken) }
            .header(FinanceTrustedContextWire.SUBJECT_HEADER, ACCOUNT_B)
            .header("X-User-Id", ACCOUNT_B)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo(UPSTREAM_RESPONSE)

        listOf("delete", "restore").forEach { operation ->
            webTestClient.post()
                .uri("/finance/journals/$ACCOUNT_A/$operation")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { headers -> headers.setBearerAuth(accessToken) }
                .header(FinanceTrustedContextWire.SUBJECT_HEADER, ACCOUNT_B)
                .header("X-User-Id", ACCOUNT_B)
                .header("X-Finance-Attacker-Extra", "remove-me")
                .header(HttpHeaders.COOKIE, "session=remove-me")
                .bodyValue(transitionBody)
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java).isEqualTo(UPSTREAM_RESPONSE)
        }

        val forwarded = SyntheticFinanceUpstream.requests.toList()
        assertThat(forwarded).hasSize(3)
        assertThat(forwarded.map { it.method to it.path }).containsExactly(
            "GET" to "/finance/journals/deleted",
            "POST" to "/finance/journals/$ACCOUNT_A/delete",
            "POST" to "/finance/journals/$ACCOUNT_A/restore"
        )
        forwarded.forEach { request ->
            assertThat(request.headers.keys).containsExactlyInAnyOrder(
                FinanceTrustedContextWire.VERSION_HEADER,
                FinanceTrustedContextWire.SERVICE_ID_HEADER,
                FinanceTrustedContextWire.SERVICE_SECRET_HEADER,
                FinanceTrustedContextWire.AUDIENCE_HEADER,
                FinanceTrustedContextWire.SUBJECT_HEADER
            )
            assertThat(request.headers)
                .containsEntry(FinanceTrustedContextWire.VERSION_HEADER, listOf("1"))
                .containsEntry(FinanceTrustedContextWire.SERVICE_ID_HEADER, listOf("gateway-app"))
                .containsEntry(
                    FinanceTrustedContextWire.SERVICE_SECRET_HEADER,
                    listOf(FINANCE_SERVICE_DUMMY_SECRET)
                )
                .containsEntry(FinanceTrustedContextWire.AUDIENCE_HEADER, listOf("finance-journal"))
                .containsEntry(FinanceTrustedContextWire.SUBJECT_HEADER, listOf(ENCODED_ACCOUNT_A))
        }
        assertThat(forwarded[0].body).isEmpty()
        assertThat(forwarded[1].body).isEqualTo(transitionBody)
        assertThat(forwarded[2].body).isEqualTo(transitionBody)
    }

    @Test
    fun `missing bearer Core token and unsupported method are rejected before Finance upstream`() {
        webTestClient.get()
            .uri("/finance/journals/deleted")
            .exchange()
            .expectStatus().isUnauthorized

        webTestClient.post()
            .uri("/finance/journals/$ACCOUNT_A/delete")
            .headers { headers -> headers.setBearerAuth(coreAccessToken()) }
            .exchange()
            .expectStatus().isUnauthorized

        webTestClient.method(HttpMethod.DELETE)
            .uri("/finance/journals/$ACCOUNT_A/delete")
            .headers { headers -> headers.setBearerAuth(referenceToken(ACCOUNT_A, "access")) }
            .exchange()
            .expectStatus().isForbidden

        assertThat(SyntheticFinanceUpstream.requests).isEmpty()
    }

    private fun referenceToken(subject: String, purpose: String): String {
        val issuedAt = Instant.now().minusSeconds(1)
        return Jwts.builder()
            .subject(subject)
            .issuer(EXPECTED_ISSUER)
            .audience().single(EXPECTED_AUDIENCE)
            .claim("token_use", purpose)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plusSeconds(300)))
            .signWith(REFERENCE_KEY, Jwts.SIG.HS256)
            .compact()
    }

    private fun coreAccessToken(): String {
        val issuedAt = Instant.now().minusSeconds(1)
        return Jwts.builder()
            .subject(ACCOUNT_A)
            .claim("organizationId", ORGANIZATION_ID)
            .claim("role", "MANAGER")
            .claim("publicId", PUBLIC_ID)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plusSeconds(300)))
            .signWith(CORE_KEY, Jwts.SIG.HS256)
            .compact()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestInfrastructureConfiguration {
        @Bean
        @Primary
        fun stubBlacklistService(): RedisBlacklistService {
            val redisTemplate = org.mockito.Mockito.mock(ReactiveStringRedisTemplate::class.java)
            org.mockito.Mockito.`when`(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(false))
            return RedisBlacklistService(redisTemplate)
        }

        @Bean
        fun syntheticFinanceRouteLocator(builder: RouteLocatorBuilder): RouteLocator =
            builder.routes()
                .route("test-only-finance-reference") { route ->
                    route.path("/finance/**")
                        .uri("http://127.0.0.1:${SyntheticFinanceUpstream.server.port()}")
                }
                .build()
    }

    private data class ForwardedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String
    )

    private object SyntheticFinanceUpstream {
        val requests = ConcurrentLinkedQueue<ForwardedRequest>()
        val server: DisposableServer = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle { request, response ->
                request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("").flatMap { body ->
                    val names = listOf(
                        HttpHeaders.AUTHORIZATION,
                        HttpHeaders.COOKIE,
                        "X-User-Id",
                        "X-Finance-Attacker-Extra",
                        FinanceTrustedContextWire.VERSION_HEADER,
                        FinanceTrustedContextWire.SERVICE_ID_HEADER,
                        FinanceTrustedContextWire.SERVICE_SECRET_HEADER,
                        FinanceTrustedContextWire.AUDIENCE_HEADER,
                        FinanceTrustedContextWire.SUBJECT_HEADER
                    )
                    val headers = names.mapNotNull { name ->
                        request.requestHeaders().getAll(name)
                            .takeIf { it.isNotEmpty() }
                            ?.let { name to it.toList() }
                    }.toMap()
                    requests.add(ForwardedRequest(request.method().name(), request.uri(), headers, body))
                    response.status(200).sendString(Mono.just(UPSTREAM_RESPONSE)).then()
                }
            }
            .bindNow()

        fun stop() {
            server.disposeNow()
        }
    }

    private companion object {
        const val ACCOUNT_A = "9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123"
        const val ACCOUNT_B = "22222222-2222-2222-2222-222222222222"
        const val ORGANIZATION_ID = "11111111-1111-1111-1111-111111111111"
        const val PUBLIC_ID = "33333333-3333-3333-3333-333333333333"
        const val EXPECTED_ISSUER = "urn:ranikunlabs:identity:finance:reference"
        const val EXPECTED_AUDIENCE = "finance-journal-reference"
        const val UPSTREAM_RESPONSE = "synthetic-finance-upstream"
        const val FINANCE_SERVICE_DUMMY_SECRET =
            "Finance-D1-Reference-Public-Dummy-Secret-Do-Not-Use-In-Production-2026"
        const val REFERENCE_DUMMY_KEY =
            "Finance-Reference-Test-Only-Public-Dummy-Key-2026-With-HS384-Margin"
        const val CORE_DUMMY_KEY =
            "Gateway-D1-Reference-Public-Dummy-JWT-Key-Do-Not-Use-In-Production-2026"
        val REFERENCE_KEY = Keys.hmacShaKeyFor(REFERENCE_DUMMY_KEY.toByteArray(StandardCharsets.UTF_8))
        val CORE_KEY = Keys.hmacShaKeyFor(CORE_DUMMY_KEY.toByteArray(StandardCharsets.UTF_8))
        val ENCODED_ACCOUNT_A = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ACCOUNT_A.toByteArray(StandardCharsets.UTF_8))

        @JvmStatic
        @AfterAll
        fun stopSyntheticUpstream() {
            SyntheticFinanceUpstream.stop()
        }
    }
}
