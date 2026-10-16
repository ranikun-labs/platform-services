package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.IdentityAuthConfiguration;
import com.ranikunlabs.platform.identity.IdentityOAuthConfiguration;
import com.ranikunlabs.platform.identity.adapter.http.AuthController;
import com.ranikunlabs.platform.identity.adapter.http.IdentityBearerAuthenticationFilter;
import com.ranikunlabs.platform.identity.adapter.http.IdentityHttpConfiguration;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthClientConfig;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProviderAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import com.ranikunlabs.platform.identity.adapter.persistence.ExternalIdentityLookupJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityPersistenceConfiguration;
import com.ranikunlabs.platform.identity.adapter.persistence.PlatformAccountStatusJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.ProductClientReaderJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.TokenSessionJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.redis.RedisOAuthStateStore;
import com.ranikunlabs.platform.identity.adapter.redis.RedisTokenBlacklistAdapter;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderRegistry;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.IdentityAccount;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the real Platform Core host composes Identity against PostgreSQL and Redis. */
@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "PLATFORM_CORE_PORT=0",
                "PLATFORM_CORE_MANAGEMENT_PORT=0",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "platform.metrics.environment=test",
                "platform.identity.persistence.enabled=true",
                "platform.identity.auth.enabled=true",
                "platform.identity.redis-key-prefix=test:identity:",
                "jwt.secret-key=01234567890123456789012345678901",
                "jwt.access-token-validity=900000",
                "jwt.refresh-token-validity=1209600000",
                "carelog.auth.oauth.kakao.client-id=test-only-kakao-client",
                "carelog.auth.oauth.kakao.client-secret=test-only-kakao-secret",
                "carelog.auth.oauth.kakao.authorization-uri=https://kauth.example.test/oauth/authorize",
                "carelog.auth.oauth.kakao.token-uri=https://kapi.example.test/oauth/token",
                "carelog.auth.oauth.kakao.user-info-uri=https://kapi.example.test/v2/user/me",
                "carelog.auth.oauth.kakao.connect-timeout=100ms",
                "carelog.auth.oauth.kakao.read-timeout=100ms",
                "oauth.redirect-uris.kakao.WEB=https://carelog.example.test/oauth/kakao/callback",
                "oauth.redirect-uris.kakao.MOBILE=https://carelog.example.test/oauth/kakao/callback",
                "oauth.allowed-return-origins=https://carelog.example.test"
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(PlatformCoreIdentityPersistenceBootIntegrationTest.HostedCompositionTestConfiguration.class)
class PlatformCoreIdentityPersistenceBootIntegrationTest {

    private static final Set<String> IDENTITY_TABLES = Set.of(
            "platform_accounts",
            "password_credentials",
            "external_identities",
            "refresh_token",
            "product_clients"
    );
    private static final Pattern AUTHORIZATION_URL_PATTERN =
            Pattern.compile("\\\"authorizationUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OAuthStateStore oauthStateStore;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenProviderPort tokenProvider;

    @Autowired
    private TokenSessionPort tokenSessionPort;

    @Autowired
    private IdentityAccountRegistrationPort accountRegistrationPort;

    @Autowired
    private ProductClientReader productClientReader;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(new DelegatingFilterProxy("springSecurityFilterChain", webApplicationContext))
                .build();
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
        registry.add("platform.identity.redis-host", REDIS::getHost);
        registry.add("platform.identity.redis-port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    void observesJpaJdbcMetadataAndPreservesPostgresResourceContracts() throws Exception {
        assertThat(dataSource.getClass().getName())
                .isEqualTo("com.ranikunlabs.platform.identity.adapter.persistence.InstrumentedHikariDataSource");

        long queryCountBefore = timerCount("query", "success");
        assertThat(entityManager.createNativeQuery("SELECT 1").getSingleResult().toString()).isEqualTo("1");
        assertThat(timerCount("query", "success")).isGreaterThan(queryCountBefore);

        Connection connection = dataSource.getConnection();
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(connection.equals(connection)).isTrue();
        assertThat(connection.hashCode()).isEqualTo(connection.hashCode());

        DatabaseMetaData metadata = connection.getMetaData();
        assertThat(metadata.isWrapperFor(DatabaseMetaData.class)).isTrue();
        assertThat(metadata.unwrap(DatabaseMetaData.class)).isSameAs(metadata);
        assertThat(metadata.getConnection()).isSameAs(connection);
        long metadataCountBefore = timerCount("metadata", "success");
        try (ResultSet tables = metadata.getTables(null, IdentityDatabaseSchema.NAME, "%", new String[]{"TABLE"})) {
            assertThat(tables.isClosed()).isFalse();
        }
        assertThat(timerCount("metadata", "success")).isGreaterThan(metadataCountBefore);

        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        assertThat(statement.getConnection()).isSameAs(connection);
        ResultSet result = statement.executeQuery("SELECT 1");
        assertThat(statement.isWrapperFor(Statement.class)).isTrue();
        assertThat(statement.unwrap(Statement.class)).isSameAs(statement);
        assertThat(result.getStatement()).isSameAs(statement);
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(1);
        connection.commit();
        result.close();
        assertThat(result.isClosed()).isTrue();
        statement.close();
        assertThat(statement.isClosed()).isTrue();

        Statement closeOnCompletion = connection.createStatement();
        closeOnCompletion.closeOnCompletion();
        ResultSet completionResult = closeOnCompletion.executeQuery("SELECT 1");
        completionResult.close();
        assertThat(closeOnCompletion.isClosed()).isTrue();

        try (PreparedStatement insert = connection.prepareStatement(
                "CREATE TEMP TABLE jdbc_observation_probe (value integer)")) {
            assertThat(insert.execute()).isFalse();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO jdbc_observation_probe (value) VALUES (?)")) {
            insert.setInt(1, 3);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO jdbc_observation_probe (value) VALUES (?)")) {
            insert.setInt(1, 1);
            insert.addBatch();
            insert.setInt(1, 2);
            insert.addBatch();
            assertThat(insert.executeBatch()).containsExactly(1, 1);
        }

        SQLException originalFailure;
        try (PreparedStatement divisionByZero = connection.prepareStatement("SELECT 1 / ?")) {
            divisionByZero.setInt(1, 0);
            originalFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    divisionByZero::executeQuery, SQLException.class);
        }
        assertThat(originalFailure).isNotNull();
        assertThat(originalFailure.getClass().getName()).isEqualTo("org.postgresql.util.PSQLException");
        assertThat(originalFailure.getSQLState()).isEqualTo("22012");

        connection.rollback();
        connection.close();
        assertThat(connection.isClosed()).isTrue();
        try (Connection reused = dataSource.getConnection()) {
            assertThat(reused.getAutoCommit()).isTrue();
        }

        softEvictHikariConnections();
        try (Connection replacement = dataSource.getConnection();
             Statement check = replacement.createStatement();
             ResultSet selected = check.executeQuery("SELECT 1")) {
            assertThat(selected.next()).isTrue();
        }

        String scrape = scrapePrometheus();
        assertThat(scrape)
                .contains("hikaricp_connections_active")
                .contains("ranikun_db_query_duration_seconds_bucket")
                .contains("ranikun_db_query_duration_seconds_count")
                .contains("ranikun_db_query_duration_seconds_sum")
                .contains("service=\"platform-core\"")
                .contains("environment=\"test\"")
                .doesNotContain("SELECT", "sql=", "account_id=", "tenant_id=", "user_id=");
        assertPrometheusSample(scrape, "ranikun_db_query_duration_seconds_bucket",
                "operation=\"query\"", "outcome=\"success\"", "le=\"+Inf\"");
        assertPrometheusSample(scrape, "ranikun_db_query_duration_seconds_sum",
                "operation=\"query\"", "outcome=\"success\"");
        assertPrometheusSample(scrape, "ranikun_db_query_duration_seconds_count",
                "operation=\"query\"", "outcome=\"error\"");
        assertPrometheusSample(scrape, "ranikun_db_query_duration_seconds_count",
                "operation=\"batch\"", "outcome=\"success\"");
        assertPrometheusSample(scrape, "ranikun_db_query_duration_seconds_count",
                "operation=\"update\"", "outcome=\"success\"");
        assertPrometheusSample(scrape, "ranikun_db_query_duration_seconds_count",
                "operation=\"metadata\"", "outcome=\"success\"");
    }

    private long timerCount(String operation, String outcome) {
        Timer timer = meterRegistry.find("ranikun.db.query.duration")
                .tags("operation", operation, "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private void softEvictHikariConnections() throws Exception {
        Object pool = dataSource.getClass().getMethod("getHikariPoolMXBean").invoke(dataSource);
        assertThat(pool).isNotNull();
        Class.forName("com.zaxxer.hikari.HikariPoolMXBean")
                .getMethod("softEvictConnections")
                .invoke(pool);
    }

    private String scrapePrometheus() throws Exception {
        int port = environment.getRequiredProperty("local.management.port", Integer.class);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private static void assertPrometheusSample(String scrape, String name, String... labels) {
        String sample = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metric sample " + name));
        assertThat(Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1))).isPositive();
    }

    @Test
    void realPlatformCoreHostComposesIdentityPersistenceAuthOAuthKakaoAndHttp()
            throws SQLException {
        assertThat(dataSource).isNotNull();
        assertSingleBean(IdentityPersistenceConfiguration.class);
        assertSingleBean(IdentityAuthConfiguration.class);
        assertSingleBean(IdentityOAuthConfiguration.class);
        assertSingleBean(IdentityHttpConfiguration.class);

        assertSingleBean(AuthServiceImpl.class);
        assertSingleBean(AuthService.class);
        assertSingleBean(AuthTokenIssuanceService.class);
        assertSingleBean(JwtTokenProvider.class);
        assertSingleBean(TokenProviderPort.class);
        assertSingleBean(TokenSessionJpaAdapter.class);
        assertSingleBean(TokenSessionPort.class);
        assertSingleBean(RedisTokenBlacklistAdapter.class);
        assertSingleBean(TokenBlacklistPort.class);
        assertSingleBean(IdentityAccountRegistrationJpaAdapter.class);
        assertSingleBean(IdentityCredentialAdapter.class);
        assertSingleBean(ExternalIdentityLookupJpaAdapter.class);
        assertSingleBean(PlatformAccountStatusJpaAdapter.class);
        assertSingleBean(CRMIdentityProjectionPort.class);

        assertSingleBean(OAuthAuthorizationService.class);
        assertSingleBean(OAuthLoginService.class);
        assertSingleBean(OAuthProviderRegistry.class);
        assertSingleBean(RedisOAuthStateStore.class);
        assertSingleBean(OAuthStateStore.class);
        assertSingleBean(KakaoOAuthClientConfig.class);
        assertSingleBean(KakaoOAuthProviderAdapter.class);
        assertSingleBean(ProductClientReaderJpaAdapter.class);
        assertSingleBean(ProductClientReader.class);
        assertSingleBean(RedisConnectionFactory.class);
        assertSingleBean(StringRedisTemplate.class);

        assertSingleBean(AuthController.class);
        assertSingleBean(IdentityBearerAuthenticationFilter.class);
        assertSingleBean(SecurityFilterChain.class);
        assertThat(applicationContext.getBeansOfType(RequestMappingHandlerMapping.class))
                .containsKey("requestMappingHandlerMapping");

        assertThat(applicationContext.getBeansOfType(OAuthProviderPort.class))
                .hasSize(1)
                .allSatisfy((name, provider) -> assertThat(provider)
                        .isInstanceOf(KakaoOAuthProviderAdapter.class));
        assertThat(productClientReader.requireEnabled("carelog-web"))
                .isEqualTo(new RegisteredProductClient("carelog-web", Product.CARELOG,
                        ProductClientChannel.WEB));
        assertThat(productClientReader.requireEnabled("carelog-mobile"))
                .isEqualTo(new RegisteredProductClient("carelog-mobile", Product.CARELOG,
                        ProductClientChannel.MOBILE));

        assertIdentityTablesAndMigration();
        assertPostMapping("/api/v1/auth/login");
        assertPostMapping("/api/v1/auth/logout");
        assertPostMapping("/api/v1/auth/refresh");
        assertPostMapping("/api/v1/auth/oauth/kakao/authorization");
        assertPostMapping("/api/v1/auth/oauth/kakao/exchange");
    }

    @Test
    void hostedAuthHttpUsesRealCredentialJwtRefreshSessionAndBearerSecurity() throws Exception {
        String loginId = "hosted-" + UUID.randomUUID();
        IdentityAccount account = registerAccount(loginId);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + loginId
                                + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();
        AuthTokenBundle httpTokens = new AuthTokenBundle(
                extractJsonString(login.getResponse().getContentAsString(), "accessToken"),
                extractJsonString(login.getResponse().getContentAsString(), "refreshToken"));

        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"unknown-" + UUID.randomUUID()
                                + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + loginId
                                + "\",\"password\":\"WrongPassword!\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(unknown.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString())
                .doesNotContain(loginId, "Password123!", "WrongPassword!");

        waitForDistinctJwtIssuedAt();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + httpTokens.refreshToken() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        assertThat(tokenProvider.validateToken(httpTokens.accessToken())).isTrue();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + httpTokens.accessToken()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(redisTemplate.hasKey("blacklist:" + httpTokens.accessToken())).isTrue();
        assertThat(tokenSessionPort.findByToken(httpTokens.refreshToken())).isEmpty();
        assertThat(tokenProvider.validateToken(httpTokens.accessToken())).isTrue();
        assertThat(account.accountId()).isEqualTo(tokenProvider.getAccountIdFromToken(
                httpTokens.accessToken()));
    }

    @Test
    void actualServerLogoutAcceptsTokenIssuedByTheActualLoginHttpPath() throws Exception {
        String loginId = "actual-server-" + UUID.randomUUID();
        IdentityAccount account = registerAccount(loginId);
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest loginRequest = HttpRequest.newBuilder(actualServerUri("/api/v1/auth/login"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"userId\":\"" + loginId
                                + "\",\"password\":\"Password123!\"}"))
                .build();
        HttpResponse<String> login = httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(200);
        String accessToken = extractJsonString(login.body(), "accessToken");
        String refreshToken = extractJsonString(login.body(), "refreshToken");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        HttpRequest logoutRequest = HttpRequest.newBuilder(actualServerUri("/api/v1/auth/logout"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> logout = httpClient.send(
                logoutRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(redisTemplate.hasKey("blacklist:" + accessToken)).isTrue();
        assertThat(tokenSessionPort.findByToken(refreshToken)).isEmpty();
        assertThat(account.accountId()).isEqualTo(tokenProvider.getAccountIdFromToken(accessToken));
    }

    private URI actualServerUri(String path) {
        int port = ((WebServerApplicationContext) applicationContext).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @Test
    void hostedOAuthHttpUsesRealProductRegistryKakaoAdapterAndRedisStateWithoutProviderCall()
            throws Exception {
        MvcResult authorization = mockMvc.perform(post("/api/v1/auth/oauth/kakao/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientChannel\":\"WEB\",\"returnTo\":\"/\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.authorizationUrl")
                        .value(org.hamcrest.Matchers.startsWith(
                                "https://kauth.example.test/oauth/authorize")))
                .andExpect(jsonPath("$.data.state").doesNotExist())
                .andExpect(jsonPath("$.data.codeVerifier").doesNotExist())
                .andReturn();

        String authorizationUrl = extractAuthorizationUrl(authorization.getResponse().getContentAsString());
        String state = queryParameter(authorizationUrl, "state");
        assertThat(state).isNotBlank();
        assertThat(redisTemplate.hasKey("oauth:state:" + state)).isTrue();
        assertThat(oauthStateStore.consume(state)).isPresent();
        assertThat(oauthStateStore.consume(state)).isEmpty();

        mockMvc.perform(post("/api/v1/auth/oauth/kakao/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"provider-call-not-made\",\"state\":\"missing-state\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 인증 요청입니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        String probeKey = "hosted:redis:probe:" + UUID.randomUUID();
        redisTemplate.opsForValue().set(probeKey, "ok", Duration.ofSeconds(30));
        assertThat(redisTemplate.opsForValue().get(probeKey)).isEqualTo("ok");
        assertThat(redisTemplate.delete(probeKey)).isTrue();
    }

    @Test
    void hostedAuthServicesUseRealJwtAndRefreshSessionPersistence() throws Exception {
        String loginId = "lifecycle-" + UUID.randomUUID();
        IdentityAccount account = registerAccount(loginId);
        AuthTokenBundle issued = authService.login(loginId, "Password123!");

        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
        assertThat(tokenProvider.validateToken(issued.accessToken())).isTrue();
        assertThat(tokenProvider.getAccountIdFromToken(issued.accessToken())).isEqualTo(account.accountId());
        assertThat(tokenSessionPort.findByToken(issued.refreshToken()))
                .get()
                .satisfies(session -> {
                    assertThat(session.accountId()).isEqualTo(account.accountId());
                    assertThat(session.expiresAt()).isAfter(java.time.OffsetDateTime.now());
                });

        waitForDistinctJwtIssuedAt();
        AuthTokenBundle rotated = authService.refreshToken(issued.refreshToken());
        assertThat(tokenSessionPort.findByToken(issued.refreshToken())).isEmpty();
        assertThat(tokenSessionPort.findByToken(rotated.refreshToken())).isPresent();

        authService.logout(account.accountId(), rotated.accessToken());
        assertThat(redisTemplate.opsForValue().get("blacklist:" + rotated.accessToken()))
                .isEqualTo("1");
        assertThat(redisTemplate.getExpire("blacklist:" + rotated.accessToken()))
                .isPositive();
        assertThat(tokenSessionPort.findByToken(rotated.refreshToken())).isEmpty();
    }

    private IdentityAccount registerAccount(String loginId) {
        return accountRegistrationPort.registerPasswordAccount(
                loginId, loginId + "@example.test", "Password123!");
    }

    private <T> void assertSingleBean(Class<T> type) {
        assertThat(applicationContext.getBeansOfType(type)).hasSize(1);
    }

    private void assertPostMapping(String path) {
        assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
                .anyMatch(mapping -> mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                        && mapping.getPatternValues().contains(path));
    }

    private void assertIdentityTablesAndMigration() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> identityTables = tableNames(connection, IdentityDatabaseSchema.NAME);
            Set<String> expectedTables = new HashSet<>(IDENTITY_TABLES);
            expectedTables.add("flyway_schema_history");
            assertThat(identityTables).containsExactlyInAnyOrderElementsOf(expectedTables);
            assertThat(tableNames(connection, "public"))
                    .doesNotContainAnyElementsOf(IDENTITY_TABLES);
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select count(*) from identity.flyway_schema_history where success = true")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isPositive();
        }
    }

    private Set<String> tableNames(Connection connection, String schema) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select table_name from information_schema.tables "
                        + "where table_schema = ? and table_type = 'BASE TABLE'")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
        }
        return tables;
    }

    private String extractAuthorizationUrl(String responseBody) {
        Matcher matcher = AUTHORIZATION_URL_PATTERN.matcher(responseBody);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String extractJsonString(String responseBody, String fieldName) {
        Matcher matcher = Pattern.compile("\\\"" + fieldName
                + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(responseBody);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void waitForDistinctJwtIssuedAt() throws InterruptedException {
        Thread.sleep(Duration.ofMillis(1100));
    }

    private String queryParameter(String url, String name) {
        URI uri = URI.create(url);
        assertThat(uri.getRawQuery()).isNotBlank();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HostedCompositionTestConfiguration {

        private static final UUID ORGANIZATION_ID =
                UUID.fromString("11111111-1111-1111-1111-111111111111");
        private static final UUID PUBLIC_ID =
                UUID.fromString("22222222-2222-2222-2222-222222222222");

        @Bean
        CRMIdentityProjectionPort crmIdentityProjectionPort() {
            return new CRMIdentityProjectionPort() {
                @Override
                public CRMIdentityClaims getIdentityClaims(UUID accountId) {
                    return claims();
                }

                @Override
                public Optional<CRMIdentityClaims> findIdentityClaims(UUID accountId) {
                    return Optional.of(claims());
                }

                private CRMIdentityClaims claims() {
                    return new CRMIdentityClaims(ORGANIZATION_ID, "MANAGER", PUBLIC_ID);
                }
            };
        }
    }
}
