package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.adapter.http.FinanceReferenceAuthController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finance reference signup to login on the shared Identity store, against a disposable PostgreSQL.
 * Credentials are synthetic test-only values.
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
                "platform.identity.redis-key-prefix=test:finance-signup:",
                "platform.identity.finance-reference.enabled=true",
                "platform.identity.finance-reference.signup.enabled=true",
                "platform.identity.finance-reference.selected-account-ids[0]=33333333-3333-3333-3333-333333333333",
                "platform.identity.finance-reference.jwt.signing-key=Finance-Reference-Test-Only-Public-Dummy-Key-2026",
                "platform.identity.finance-reference.jwt.issuer=urn:ranikunlabs:identity:finance:reference",
                "platform.identity.finance-reference.jwt.audience=finance-journal-reference",
                "platform.identity.finance-reference.jwt.access-token-ttl-seconds=300"
        }
)
@AutoConfigureMockMvc
class PlatformCoreFinanceReferenceSignupIntegrationTest {

    private static final String SIGNUP_PATH = "/api/v1/auth/finance-reference/signup";
    private static final String LOGIN_PATH = FinanceReferenceAuthController.PATH;
    private static final String EMAIL = "dev.user@example.test";
    private static final String PASSWORD = "Synthetic-Pass123";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\"accountId\":\"([0-9a-f-]{36})\"");
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
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearSignupRows() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM identity.external_identities");
            statement.execute("DELETE FROM identity.password_credentials");
            statement.execute("DELETE FROM identity.platform_accounts");
        }
    }

    @AfterAll
    static void stopDisposablePostgres() {
        POSTGRES.stop();
    }

    @Test
    void signupCreatesSharedAccountAndTheSameCredentialLogsInThroughFinanceReference() throws Exception {
        MvcResult signup = mockMvc.perform(signup("Dev.User@Example.test", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.accountId").isNotEmpty())
                .andReturn();
        String body = signup.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", "$2a$", "$2b$", PASSWORD);
        Matcher accountMatcher = ACCOUNT_ID.matcher(body);
        assertThat(accountMatcher.find()).isTrue();
        String accountId = accountMatcher.group(1);

        assertThat(scalar("SELECT password_hash FROM identity.password_credentials WHERE login_id = ?", EMAIL))
                .startsWith("$2")
                .isNotEqualTo(PASSWORD);
        assertThat(scalar("SELECT COUNT(*) FROM identity.external_identities WHERE provider = 'finance-reference' AND provider_subject = ?", accountId))
                .isEqualTo("1");
        MvcResult login = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn();
        String token = login.getResponse().getContentAsString()
                .replaceAll("(?s).*\"accessToken\":\"([^\"]+)\".*", "$1");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("\"sub\":\"" + accountId + "\"").doesNotContain("$2");
    }

    @Test
    void duplicateEmailIsConflictAndLeavesNoOrphanAccount() throws Exception {
        mockMvc.perform(signup(EMAIL, PASSWORD)).andExpect(status().isCreated());

        mockMvc.perform(signup("DEV.USER@example.test", "Another-Pass456"))
                .andExpect(status().isConflict());

        assertThat(scalar("SELECT COUNT(*) FROM identity.platform_accounts WHERE primary_email = ?", EMAIL))
                .isEqualTo("1");
        assertThat(scalar("SELECT COUNT(*) FROM identity.external_identities WHERE provider = 'finance-reference'", null))
                .isEqualTo("1");
    }

    @Test
    void passwordPolicyAndBodyShapeAreRejectedBeforeAnyRowIsWritten() throws Exception {
        String[] invalidBodies = {
                body(EMAIL, "short1"),
                body(EMAIL, "allletterslong"),
                body(EMAIL, "1234567890123"),
                body(EMAIL, PASSWORD + "x".repeat(60)),
                body(EMAIL, EMAIL),
                body("not-an-email", PASSWORD),
                "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\",\"extra\":\"x\"}",
                "{\"email\":\"" + EMAIL + "\",\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}",
        };
        for (String invalid : invalidBodies) {
            MvcResult result = mockMvc.perform(post(SIGNUP_PATH).servletPath(SIGNUP_PATH)
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as("body=%s", invalid).isEqualTo(400);
        }
        assertThat(scalar("SELECT COUNT(*) FROM identity.platform_accounts", null)).isEqualTo("0");
    }

    @Test
    void existingCredentialWithoutFinanceEnrollmentCannotLogIn() throws Exception {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD);
        java.util.UUID accountId = java.util.UUID.fromString("55555555-5555-5555-5555-555555555555");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement account = connection.prepareStatement(
                     "INSERT INTO identity.platform_accounts (id, status, primary_email, created_at, updated_at) "
                             + "VALUES (?, 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
             PreparedStatement credential = connection.prepareStatement(
                     "INSERT INTO identity.password_credentials (account_id, login_id, password_hash, created_at, updated_at) "
                             + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            account.setObject(1, accountId);
            account.setString(2, "not-enrolled@example.test");
            account.executeUpdate();
            credential.setObject(1, accountId);
            credential.setString(2, "not-enrolled@example.test");
            credential.setString(3, hash);
            credential.executeUpdate();
        }

        mockMvc.perform(post(LOGIN_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"not-enrolled@example.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signup(String email, String password) {
        return post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON).content(body(email, password));
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String scalar(String sql, String parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            if (parameter != null) {
                query.setString(1, parameter);
            }
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }
}
