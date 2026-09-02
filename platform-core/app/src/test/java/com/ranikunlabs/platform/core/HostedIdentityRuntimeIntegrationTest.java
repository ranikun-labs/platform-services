package com.ranikunlabs.platform.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the extracted Identity Auth stack inside the real executable host.
 *
 * <p>Everything Identity-owned is production wiring: {@link PlatformCoreApplication} composes the
 * bean graph, PostgreSQL and Redis are real containers, and the Product claims dependency is the
 * production {@code CarelogProductClaimsHttpAdapter} — no Identity port is replaced with a test
 * bean. Only the far side of the network boundary is a fixture: a local HTTP server standing in for
 * the Carelog private claims API, so the suite needs no deployed Carelog.
 */
@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "platform.identity.auth.enabled=true",
                "platform.identity.persistence.enabled=true",
                "platform.identity.redis-key-prefix=platform:identity:",
                "jwt.secret-key=hosted-runtime-integration-secret-key-0123456789",
                "jwt.access-token-validity=900000",
                "jwt.refresh-token-validity=1209600000",
                "PLATFORM_CORE_PORT=0"
        })
class HostedIdentityRuntimeIntegrationTest {

    private static final String SERVICE_TOKEN = "hosted-runtime-service-token";
    private static final int REDIS_PORT = 6379;
    private static final String LOGIN_ID = "hosted-runtime-login";
    private static final String PASSWORD = "hosted-runtime-password";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    private static HttpServer carelogClaimsStub;
    private static final List<ClaimsCall> CLAIMS_CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<ClaimsStubResponse> CLAIMS_RESPONSE = new AtomicReference<>();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient httpClient;
    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    HostedIdentityRuntimeIntegrationTest(
            @Value("${local.server.port}") int port,
            DataSource dataSource,
            StringRedisTemplate redisTemplate,
            PasswordEncoder passwordEncoder
    ) {
        this.httpClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    /** Status and body of a real HTTP call, captured without throwing on error statuses. */
    private record HttpOutcome(int status, String body) {
    }

    @BeforeAll
    static void startRuntimeDependencies() throws IOException {
        POSTGRES.start();
        REDIS.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        startCarelogClaimsStub();
    }

    @AfterAll
    static void stopRuntimeDependencies() {
        if (carelogClaimsStub != null) {
            carelogClaimsStub.stop(0);
        }
        REDIS.stop();
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
        registry.add("platform.identity.redis-host", REDIS::getHost);
        registry.add("platform.identity.redis-port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("platform.identity.product-claims.base-url",
                () -> "http://localhost:" + carelogClaimsStub.getAddress().getPort());
        registry.add("platform.identity.product-claims.service-token", () -> SERVICE_TOKEN);
    }

    @BeforeEach
    void resetClaimsFixture() {
        CLAIMS_CALLS.clear();
        CLAIMS_RESPONSE.set(ClaimsStubResponse.of(UUID.randomUUID(), "MANAGER", UUID.randomUUID()));
    }

    // ----- hosted login -----

    @Test
    void hostedLoginReadsProductClaimsOverHttpAndPersistsTheRefreshSession() throws Exception {
        UUID accountId = seedPasswordAccount();
        ClaimsStubResponse claims = CLAIMS_RESPONSE.get();

        HttpOutcome response = postJson("/api/v1/auth/login",
                "{\"userId\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}");

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(200);
        assertThat(body.get("message").asString()).isEqualTo("요청에 성공하였습니다.");

        String accessToken = body.get("data").get("accessToken").asString();
        String refreshToken = body.get("data").get("refreshToken").asString();

        // The production adapter used the private contract, not a Gateway or user credential.
        assertThat(CLAIMS_CALLS).hasSize(1);
        ClaimsCall call = CLAIMS_CALLS.getFirst();
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path())
                .isEqualTo("/api/v1/internal/identity/accounts/" + accountId + "/claims");
        assertThat(call.serviceToken()).isEqualTo(SERVICE_TOKEN);
        assertThat(call.authorization()).isNull();

        JsonNode accessClaims = decodeJwtPayload(accessToken);
        assertThat(accessClaims.get("sub").asString()).isEqualTo(accountId.toString());
        assertThat(accessClaims.get("organizationId").asString())
                .isEqualTo(claims.organizationId().toString());
        assertThat(accessClaims.get("role").asString()).isEqualTo(claims.role());
        assertThat(accessClaims.get("publicId").asString()).isEqualTo(claims.publicId().toString());

        assertThat(persistedRefreshTokens(accountId)).containsExactly(refreshToken);
    }

    @Test
    void hostedLoginWithAWrongPasswordKeepsTheCanonicalUnauthorizedContract() throws Exception {
        seedPasswordAccount();

        HttpOutcome response = postJson("/api/v1/auth/login",
                "{\"userId\":\"" + LOGIN_ID + "\",\"password\":\"wrong-password\"}");

        assertThat(response.status()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).get("message").asString())
                .isEqualTo("아이디 또는 비밀번호가 일치하지 않습니다.");
        assertThat(CLAIMS_CALLS).isEmpty();
    }

    // ----- hosted refresh -----

    @Test
    void hostedRefreshRotatesTheSessionAndRefetchesCurrentProductClaims() throws Exception {
        UUID accountId = seedPasswordAccount();
        String originalRefreshToken = login().refreshToken();

        // The Product's claim snapshot changes between login and refresh.
        ClaimsStubResponse rotatedClaims =
                ClaimsStubResponse.of(UUID.randomUUID(), "MEMBER", UUID.randomUUID());
        CLAIMS_RESPONSE.set(rotatedClaims);
        CLAIMS_CALLS.clear();

        HttpOutcome refreshed = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + originalRefreshToken + "\"}");

        assertThat(refreshed.status()).isEqualTo(200);
        JsonNode data = JSON.readTree(refreshed.body()).get("data");
        String rotatedRefreshToken = data.get("refreshToken").asString();
        assertThat(rotatedRefreshToken).isNotEqualTo(originalRefreshToken);

        assertThat(CLAIMS_CALLS).hasSize(1);
        JsonNode rotatedAccessClaims = decodeJwtPayload(data.get("accessToken").asString());
        assertThat(rotatedAccessClaims.get("role").asString()).isEqualTo("MEMBER");
        assertThat(rotatedAccessClaims.get("organizationId").asString())
                .isEqualTo(rotatedClaims.organizationId().toString());

        assertThat(persistedRefreshTokens(accountId)).containsExactly(rotatedRefreshToken);

        HttpOutcome replayed = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + originalRefreshToken + "\"}");
        assertThat(replayed.status()).isEqualTo(404);
        assertThat(JSON.readTree(replayed.body()).get("message").asString())
                .isEqualTo("Refresh Token을 찾을 수 없습니다.");
    }

    // ----- hosted logout -----

    @Test
    void hostedLogoutBlacklistsInRedisAndDeletesThePostgresSession() throws Exception {
        UUID accountId = seedPasswordAccount();
        IssuedTokens tokens = login();

        HttpOutcome response = postWithBearer(
                "/api/v1/auth/logout", tokens.accessToken());

        assertThat(response.status()).isEqualTo(204);
        String blacklistKey = "blacklist:" + tokens.accessToken();
        assertThat(redisTemplate.hasKey(blacklistKey)).isTrue();
        assertThat(redisTemplate.getExpire(blacklistKey)).isPositive();
        assertThat(persistedRefreshTokens(accountId)).isEmpty();
    }

    @Test
    void hostedLogoutRejectsARefreshTokenPresentedAsABearerCredential() throws Exception {
        UUID accountId = seedPasswordAccount();
        IssuedTokens tokens = login();

        HttpOutcome response = postWithBearer(
                "/api/v1/auth/logout", tokens.refreshToken());

        assertThat(response.status()).isEqualTo(401);
        assertThat(redisTemplate.hasKey("blacklist:" + tokens.refreshToken())).isFalse();
        assertThat(persistedRefreshTokens(accountId)).containsExactly(tokens.refreshToken());
    }

    // ----- Product claims failure semantics -----

    @Test
    void aMissingProductClaimSnapshotKeepsTheCanonicalNotFoundContract() throws Exception {
        seedPasswordAccount();
        CLAIMS_RESPONSE.set(ClaimsStubResponse.status(404, "{\"status\":404}"));

        HttpOutcome response = loginResponse();

        assertThat(response.status()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).get("message").asString())
                .isEqualTo("사용자를 찾을 수 없습니다.");
    }

    @Test
    void aRejectedServiceCredentialFailsClosedWithoutLeakingTheDependency() throws Exception {
        seedPasswordAccount();
        CLAIMS_RESPONSE.set(ClaimsStubResponse.status(401, ""));

        HttpOutcome response = loginResponse();

        assertThat(response.status()).isEqualTo(500);
        String body = response.body();
        assertThat(JSON.readTree(body).get("message").asString())
                .isEqualTo("서버에 예상치 못한 오류가 발생했습니다.");
        assertThat(body).doesNotContain(SERVICE_TOKEN).doesNotContain("internal/identity");
    }

    @Test
    void aProductSideServerErrorIsReportedAsAnUnavailableDependency() throws Exception {
        seedPasswordAccount();
        CLAIMS_RESPONSE.set(ClaimsStubResponse.status(503, "upstream down"));

        HttpOutcome response = loginResponse();

        assertThat(response.status()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).get("message").asString())
                .isEqualTo("제품 정보 조회 서비스를 사용할 수 없습니다.");
    }

    @Test
    void aMalformedClaimsResponseFailsClosedAndIssuesNoTokens() throws Exception {
        UUID accountId = seedPasswordAccount();
        CLAIMS_RESPONSE.set(ClaimsStubResponse.status(200, "{\"organizationId\":\"not-a-uuid\"}"));

        HttpOutcome response = loginResponse();

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("accessToken");
        assertThat(persistedRefreshTokens(accountId)).isEmpty();
    }

    @Test
    void anIncompleteClaimsResponseFailsClosed() throws Exception {
        seedPasswordAccount();
        CLAIMS_RESPONSE.set(ClaimsStubResponse.status(200,
                "{\"organizationId\":\"" + UUID.randomUUID() + "\",\"role\":\"  \",\"publicId\":\""
                        + UUID.randomUUID() + "\"}"));

        assertThat(loginResponse().status()).isEqualTo(500);
    }

    // ----- hosted composition and host security -----

    @Test
    void theHostComposesTheProductionIdentityAuthAndOauthBeanGraph() {
        // Asserted through the running host context rather than a synthetic Identity-only context.
        assertThat(applicationBean("com.ranikunlabs.platform.identity.application.AuthService")).isNotNull();
        assertThat(applicationBean(
                "com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService")).isNotNull();
        assertThat(applicationBean(
                "com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService")).isNotNull();
        assertThat(applicationBean(
                "com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore")).isNotNull();
        assertThat(applicationBean(
                "com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort")).isNotNull();
        assertThat(applicationBean(
                "com.ranikunlabs.platform.identity.adapter.productclaims.CarelogProductClaimsHttpAdapter"))
                .isNotNull();
    }

    @Test
    void theHostKeepsReadinessAndLivenessOpenAndTheAuthNamespaceGoverned() throws Exception {
        assertThat(get("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness").status()).isEqualTo(200);

        // Logout is the only protected endpoint in the namespace; unknown paths under it stay closed.
        assertThat(postWithoutCredentials("/api/v1/auth/logout").status()).isEqualTo(401);
        assertThat(postWithoutCredentials("/api/v1/auth/unknown").status()).isEqualTo(401);
    }

    /**
     * The OAuth HTTP boundary is composed and reachable in the host. This hosted profile supplies no
     * Kakao configuration, so the production Kakao provider is deliberately not registered and
     * authorization must fail as an unsupported provider rather than appear live. Provider
     * composition when Kakao <em>is</em> configured is covered by the Identity module's tests.
     */
    @Test
    void theHostedOauthEndpointIsPublicButHasNoKakaoProviderConfigured() throws Exception {
        HttpOutcome response = postJson("/api/v1/auth/oauth/kakao/authorization",
                "{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}");

        assertThat(response.status()).isNotEqualTo(401);
        assertThat(JSON.readTree(response.body()).get("message").asString())
                .isEqualTo("지원하지 않는 OAuth 제공자입니다.");
    }

    // ----- fixtures -----

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private Object applicationBean(String className) {
        try {
            return applicationContext.getBean(Class.forName(className));
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record IssuedTokens(String accessToken, String refreshToken) {
    }

    private IssuedTokens login() throws Exception {
        HttpOutcome response = loginResponse();
        assertThat(response.status()).isEqualTo(200);
        JsonNode data = JSON.readTree(response.body()).get("data");
        return new IssuedTokens(data.get("accessToken").asString(), data.get("refreshToken").asString());
    }

    private HttpOutcome loginResponse() {
        return postJson("/api/v1/auth/login",
                "{\"userId\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private HttpOutcome postJson(String path, String json) {
        return httpClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange((request, response) -> new HttpOutcome(
                        response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    private HttpOutcome postWithBearer(String path, String token) {
        return httpClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange((request, response) -> new HttpOutcome(
                        response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    private HttpOutcome postWithoutCredentials(String path) {
        return httpClient.post()
                .uri(path)
                .exchange((request, response) -> new HttpOutcome(
                        response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    private HttpOutcome get(String path) {
        return httpClient.get()
                .uri(path)
                .exchange((request, response) -> new HttpOutcome(
                        response.getStatusCode().value(), response.bodyTo(String.class)));
    }

    /** Seeds one active account with a password credential directly in the migrated schema. */
    private UUID seedPasswordAccount() throws Exception {
        UUID accountId = UUID.randomUUID();
        String encodedPassword = passwordEncoder.encode(PASSWORD);
        OffsetDateTime now = OffsetDateTime.now();
        try (Connection connection = dataSource.getConnection()) {
            try (Statement cleanup = connection.createStatement()) {
                cleanup.execute("delete from refresh_token");
                cleanup.execute("delete from password_credentials");
                cleanup.execute("delete from platform_accounts");
            }
            try (PreparedStatement account = connection.prepareStatement("""
                    insert into platform_accounts (id, primary_email, status, created_at, updated_at)
                    values (?, ?, 'ACTIVE', ?, ?)
                    """)) {
                account.setObject(1, accountId);
                account.setString(2, accountId + "@example.com");
                account.setObject(3, now);
                account.setObject(4, now);
                account.executeUpdate();
            }
            try (PreparedStatement credential = connection.prepareStatement("""
                    insert into password_credentials (account_id, login_id, password_hash, created_at, updated_at)
                    values (?, ?, ?, ?, ?)
                    """)) {
                credential.setObject(1, accountId);
                credential.setString(2, LOGIN_ID);
                credential.setString(3, encodedPassword);
                credential.setObject(4, now);
                credential.setObject(5, now);
                credential.executeUpdate();
            }
        }
        return accountId;
    }

    private List<String> persistedRefreshTokens(UUID accountId) throws Exception {
        List<String> tokens = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select refresh_token from refresh_token where account_id = ?")) {
            statement.setObject(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tokens.add(resultSet.getString(1));
                }
            }
        }
        return tokens;
    }

    private static JsonNode decodeJwtPayload(String jwt) {
        String payload = jwt.split("\\.")[1];
        return JSON.readTree(new String(
                Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    // ----- Carelog private claims API fixture -----

    private record ClaimsCall(String method, String path, String serviceToken, String authorization) {
    }

    private record ClaimsStubResponse(int status, String body) {

        static ClaimsStubResponse of(UUID organizationId, String role, UUID publicId) {
            return new ClaimsStubResponse(200, "{\"organizationId\":\"" + organizationId
                    + "\",\"role\":\"" + role + "\",\"publicId\":\"" + publicId + "\"}");
        }

        static ClaimsStubResponse status(int status, String body) {
            return new ClaimsStubResponse(status, body);
        }

        UUID organizationId() {
            return UUID.fromString(JSON.readTree(body).get("organizationId").asString());
        }

        String role() {
            return JSON.readTree(body).get("role").asString();
        }

        UUID publicId() {
            return UUID.fromString(JSON.readTree(body).get("publicId").asString());
        }
    }

    private static void startCarelogClaimsStub() throws IOException {
        carelogClaimsStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        carelogClaimsStub.createContext("/", HostedIdentityRuntimeIntegrationTest::handleClaimsRequest);
        carelogClaimsStub.start();
    }

    private static void handleClaimsRequest(HttpExchange exchange) throws IOException {
        CLAIMS_CALLS.add(new ClaimsCall(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Platform-Service-Token"),
                exchange.getRequestHeaders().getFirst("Authorization")));

        ClaimsStubResponse configured = CLAIMS_RESPONSE.get();
        byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(configured.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
        exchange.close();
    }
}
