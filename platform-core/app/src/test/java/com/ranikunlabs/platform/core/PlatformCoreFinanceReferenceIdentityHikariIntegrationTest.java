package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.adapter.http.FinanceReferenceAuthController;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.FinanceReferenceAccessService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Temporary combined-tree check for the Finance reference login through the #57 instrumented Hikari datasource.
 * PostgreSQL, the selected account, and the public dummy password are test-only and disposable.
 */
@ActiveProfiles("finance-reference")
@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "PLATFORM_CORE_PORT=0",
                "PLATFORM_CORE_MANAGEMENT_PORT=0",
                "platform.metrics.environment=test",
                "platform.identity.persistence.enabled=true",
                "platform.identity.auth.enabled=false",
                "platform.identity.redis-host=127.0.0.1",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=test:finance-reference:",
                "platform.identity.finance-reference.enabled=true",
                "platform.identity.finance-reference.selected-account-ids[0]=33333333-3333-3333-3333-333333333333",
                "platform.identity.finance-reference.jwt.signing-key=Finance-Reference-Test-Only-Public-Dummy-Key-2026",
                "platform.identity.finance-reference.jwt.issuer=urn:ranikunlabs:identity:finance:reference",
                "platform.identity.finance-reference.jwt.audience=finance-journal-reference",
                "platform.identity.finance-reference.jwt.access-token-ttl-seconds=300"
        }
)
@AutoConfigureMockMvc
class PlatformCoreFinanceReferenceIdentityHikariIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String LOGIN_ID = "synthetic-finance-reference-user";
    private static final String RAW_PASSWORD = "Synthetic-Password123";
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
        try (Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA identity AUTHORIZATION CURRENT_USER");
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void seedOneSyntheticActiveAccount() throws SQLException {
        String passwordHash = passwordEncoder.encode(RAW_PASSWORD);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement account = connection.prepareStatement(
                     "INSERT INTO identity.platform_accounts (id, status, primary_email, created_at, updated_at) "
                             + "VALUES (?, 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
             PreparedStatement credential = connection.prepareStatement(
                     "INSERT INTO identity.password_credentials (account_id, login_id, password_hash, created_at, updated_at) "
                             + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            account.setObject(1, ACCOUNT_ID);
            account.setString(2, "synthetic-finance-reference@example.test");
            account.executeUpdate();
            credential.setObject(1, ACCOUNT_ID);
            credential.setString(2, LOGIN_ID);
            credential.setString(3, passwordHash);
            credential.executeUpdate();
        }
    }

    @AfterAll
    static void stopDisposablePostgres() {
        POSTGRES.stop();
    }

    @Test
    void financeReferenceLoginUsesInstrumentedJpaIdentityAndLeavesCoreAuthDisabled() throws Exception {
        assertThat(environment.getActiveProfiles()).containsExactly("finance-reference");
        assertThat(environment.getProperty("platform.identity.auth.enabled", Boolean.class, false)).isFalse();
        assertThat(dataSource.getClass().getName())
                .isEqualTo("com.ranikunlabs.platform.identity.adapter.persistence.InstrumentedHikariDataSource");
        assertThat(applicationContext.getBeansOfType(FinanceReferenceAccessService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AuthService.class)).isEmpty();
        assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                        && mapping.getPatternValues().contains("/api/v1/auth/login"));

        long beforeLogin = querySuccessCount();
        mockMvc.perform(post(FinanceReferenceAuthController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + LOGIN_ID + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        assertThat(querySuccessCount()).isGreaterThan(beforeLogin);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM identity.refresh_token WHERE account_id = ?")) {
            query.setObject(1, ACCOUNT_ID);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    private long querySuccessCount() {
        Timer timer = meterRegistry.find("ranikun.db.query.duration")
                .tags("operation", "query", "outcome", "success")
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
