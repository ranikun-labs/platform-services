package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Disposable local infrastructure; proves composition, not a deployed product enrollment. */
@SpringBootTest(classes = PlatformCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "PLATFORM_CORE_PORT=0", "PLATFORM_CORE_MANAGEMENT_PORT=0",
                "platform.metrics.environment=test", "platform.identity.persistence.enabled=true",
                "platform.identity.auth.enabled=true", "platform.identity.carelog-registration.enabled=true",
                "platform.identity.carelog-enrollment.enabled=true", "platform.identity.redis-key-prefix=test:identity:",
                "platform.identity.carelog-enrollment.credential=synthetic-test-only-token-1234567890",
                "jwt.secret-key=01234567890123456789012345678901", "jwt.access-token-validity=900000",
                "jwt.refresh-token-validity=1209600000", "carelog.auth.oauth.kakao.client-id=test-client",
                "carelog.auth.oauth.kakao.client-secret=test-secret", "carelog.auth.oauth.kakao.authorization-uri=https://kauth.example.test/oauth/authorize",
                "carelog.auth.oauth.kakao.token-uri=https://kapi.example.test/oauth/token",
                "carelog.auth.oauth.kakao.user-info-uri=https://kapi.example.test/v2/user/me",
                "oauth.redirect-uris.kakao.WEB=https://carelog.example.test/oauth/kakao/callback",
                "oauth.redirect-uris.kakao.MOBILE=https://carelog.example.test/oauth/kakao/callback",
                "oauth.allowed-return-origins=https://carelog.example.test"})
@ActiveProfiles("dev")
@Import(PlatformCoreCarelogRegistrationHostIntegrationTest.ClaimsStub.class)
class PlatformCoreCarelogRegistrationHostIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final HttpServer ENROLLMENT;
    static {
        try {
            POSTGRES.start();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + IdentityDatabaseSchema.NAME + " AUTHORIZATION CURRENT_USER");
            }
            REDIS.start();
            ENROLLMENT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ENROLLMENT.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                REQUEST_PATH.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                REQUEST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            });
            ENROLLMENT.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
        registry.add("platform.identity.redis-host", REDIS::getHost);
        registry.add("platform.identity.redis-port", () -> REDIS.getMappedPort(6379));
        registry.add("platform.identity.carelog-enrollment.base-url", () -> "http://127.0.0.1:" + ENROLLMENT.getAddress().getPort());
    }
    @Autowired WebApplicationContext context;
    @Autowired DataSource dataSource;
    MockMvc mvc;
    @BeforeEach void setup() {
        REQUESTS.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilter(new DelegatingFilterProxy("springSecurityFilterChain", context)).build();
    }
    @AfterAll static void stop() { ENROLLMENT.stop(0); REDIS.stop(); POSTGRES.stop(); }

    @Test void registrationReplayAndPasswordLoginUseRealHostComposition() throws Exception {
        var manager = context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        assertThat(manager.getClass().getName()).isEqualTo("org.springframework.orm.jpa.JpaTransactionManager");
        assertThat(manager.getClass().getMethod("getDataSource").invoke(manager)).isSameAs(dataSource);
        String key = UUID.randomUUID().toString();
        String login = "test-" + UUID.randomUUID();
        String body = "{\"userId\":\"" + login + "\",\"email\":\"person@example.test\",\"password\":\"test-password\",\"name\":\"Synthetic Name\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/auth/carelog/registration").header("Idempotency-Key", key)
                            .contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-password"))));
        }
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(REQUEST_PATH.get()).matches("PUT /api/v1/internal/v1/identity/carelog/enrollments/[0-9a-f-]{36}");
        assertThat(REQUEST_AUTH.get()).isEqualTo("Bearer synthetic-test-only-token-1234567890");
        assertThat(REQUEST_BODY.get()).contains(login, "person@example.test", "Synthetic Name").doesNotContain("test-password", key);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "select a.status, o.state from identity.platform_accounts a join identity.carelog_registration_operation o on o.account_id=a.id where o.operation_key=?")) {
            statement.setObject(1, UUID.fromString(key));
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("ACTIVE");
                assertThat(result.getString(2)).isEqualTo("COMPLETED");
                assertThat(result.next()).isFalse();
            }
        }
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"userId\":\"" + login + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ClaimsStub {
        @Bean CRMIdentityProjectionPort crmIdentityProjectionPort() {
            return new CRMIdentityProjectionPort() {
                public CRMIdentityClaims getIdentityClaims(UUID accountId) { return new CRMIdentityClaims(UUID.fromString("11111111-1111-4111-8111-111111111111"), "MANAGER", UUID.fromString("22222222-2222-4222-8222-222222222222")); }
                public Optional<CRMIdentityClaims> findIdentityClaims(UUID accountId) { return Optional.of(getIdentityClaims(accountId)); }
            };
        }
    }
}
