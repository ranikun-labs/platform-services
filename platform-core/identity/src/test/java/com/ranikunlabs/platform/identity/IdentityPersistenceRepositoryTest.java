package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.ExternalIdentityLookupJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.PasswordCredentialUpdateJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.ProductClientReaderJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.TokenSessionJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.ExternalIdentityEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.ProductClientEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.RefreshTokenEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.ExternalIdentityJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.ProductClientJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.RefreshTokenJpaRepository;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.IdentityAccount;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.PasswordCredentialUpdatePort;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenLifecycleException;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountStatus;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountView;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies JPA repository behavior against the migrated Identity schema. */
@SpringBootTest(
        classes = IdentityPersistenceRepositoryTest.TestApplication.class,
        webEnvironment = WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "platform.identity.auth.enabled=true",
                "platform.identity.persistence.enabled=true",
                "platform.identity.redis-host=localhost",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=test:identity:",
                "jwt.secret-key=01234567890123456789012345678901",
                "jwt.access-token-validity=900000",
                "jwt.refresh-token-validity=1209600000"
        }
)
class IdentityPersistenceRepositoryTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCESS_TOKEN_VALIDITY = 900_000L;
    private static final long REFRESH_TOKEN_VALIDITY = 1_209_600_000L;
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    private final PlatformAccountJpaRepository platformAccountRepository;
    private final PasswordCredentialJpaRepository passwordCredentialRepository;
    private final ExternalIdentityJpaRepository externalIdentityRepository;
    private final ProductClientJpaRepository productClientRepository;
    private final RefreshTokenJpaRepository refreshTokenRepository;
    private final ExternalIdentityLookupJpaAdapter externalIdentityLookup;
    private final ProductClientReaderJpaAdapter productClientReader;
    private final TokenSessionJpaAdapter tokenSession;
    private final IdentityAccountRegistrationJpaAdapter accountRegistration;
    private final PasswordCredentialUpdateJpaAdapter passwordCredentialUpdate;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final CRMIdentityProjectionPort crmIdentityProjectionPort;
    private final ApplicationContext applicationContext;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;

    private static final RegistrationRaceGate REGISTRATION_RACE_GATE = new RegistrationRaceGate();

    @Autowired
    IdentityPersistenceRepositoryTest(
            ApplicationContext applicationContext,
            PlatformAccountJpaRepository platformAccountRepository,
            PasswordCredentialJpaRepository passwordCredentialRepository,
            ExternalIdentityJpaRepository externalIdentityRepository,
            ProductClientJpaRepository productClientRepository,
            RefreshTokenJpaRepository refreshTokenRepository,
            ExternalIdentityLookupJpaAdapter externalIdentityLookup,
            ProductClientReaderJpaAdapter productClientReader,
            TokenSessionJpaAdapter tokenSession,
            IdentityAccountRegistrationJpaAdapter accountRegistration,
            PasswordCredentialUpdateJpaAdapter passwordCredentialUpdate,
            PasswordEncoder passwordEncoder,
            AuthService authService,
            CRMIdentityProjectionPort crmIdentityProjectionPort,
            PlatformTransactionManager transactionManager,
            DataSource dataSource
    ) {
        this.applicationContext = applicationContext;
        this.platformAccountRepository = platformAccountRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.productClientRepository = productClientRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.externalIdentityLookup = externalIdentityLookup;
        this.productClientReader = productClientReader;
        this.tokenSession = tokenSession;
        this.accountRegistration = accountRegistration;
        this.passwordCredentialUpdate = passwordCredentialUpdate;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.crmIdentityProjectionPort = crmIdentityProjectionPort;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.identity.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("platform.identity.datasource.username", POSTGRES::getUsername);
        registry.add("platform.identity.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void authAndPersistenceComposeWithRealJpaInfrastructure() {
        assertSingleBean(IdentityAuthConfiguration.class);
        assertSingleBean(IdentityJwtProperties.class);
        assertSingleBean(PasswordEncoder.class);
        assertSingleBean(JwtTokenProvider.class);
        assertSingleBean(TokenProviderPort.class);

        assertSingleBean(DataSource.class);
        assertSingleBean(PlatformAccountJpaRepository.class);
        assertSingleBean(PasswordCredentialJpaRepository.class);
        assertSingleBean(IdentityAccountRegistrationJpaAdapter.class);
        assertSingleBean(IdentityAccountRegistrationPort.class);
        assertSingleBean(PasswordCredentialUpdateJpaAdapter.class);
        assertSingleBean(PasswordCredentialUpdatePort.class);

        assertSingleBean(IdentityCredentialAdapter.class);
        assertSingleBean(CredentialPort.class);
        assertSingleBean(AccountStatusPort.class);
        assertSingleBean(TokenSessionPort.class);
        assertSingleBean(TokenBlacklistPort.class);
        assertSingleBean(AuthTokenIssuanceService.class);
        assertSingleBean(AuthServiceImpl.class);
        assertSingleBean(AuthService.class);
        assertSingleBean(CRMIdentityProjectionPort.class);
        assertSingleBean(StringRedisTemplate.class);

        assertThat(applicationContext.getBean(PlatformAccountJpaRepository.class))
                .isSameAs(platformAccountRepository);
        assertThat(applicationContext.getBean(PasswordCredentialJpaRepository.class))
                .isSameAs(passwordCredentialRepository);
        assertThat(applicationContext.getBean(IdentityAccountRegistrationJpaAdapter.class))
                .isSameAs(accountRegistration);
        assertThat(applicationContext.getBean(PasswordCredentialUpdateJpaAdapter.class))
                .isSameAs(passwordCredentialUpdate);
    }

    @Test
    void passwordAccountRegistrationPersistsTheIdentityOwnedRows() {
        String loginId = unique("registration-login");
        String rawPassword = unique("registration-raw");
        String email = loginId + "@example.com";

        IdentityAccount result = accountRegistration.registerPasswordAccount(loginId, email, rawPassword);

        assertThat(result.accountId()).isNotNull();
        assertThat(result.loginId()).isEqualTo(loginId);
        assertThat(result.encodedPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, result.encodedPasswordHash())).isTrue();
        assertThat(platformAccountRepository.findById(result.accountId()))
                .hasValueSatisfying(account -> {
                    assertThat(account.getId()).isEqualTo(result.accountId());
                    assertThat(account.getStatus()).isEqualTo(com.ranikunlabs.platform.identity.core.AccountStatus.ACTIVE);
                    assertThat(account.getPrimaryEmail()).isEqualTo(email);
                });
        assertThat(passwordCredentialRepository.findById(result.accountId()))
                .hasValueSatisfying(credential -> {
                    assertThat(credential.getAccountId()).isEqualTo(result.accountId());
                    assertThat(credential.getLoginId()).isEqualTo(loginId);
                    assertThat(credential.getPasswordHash()).isEqualTo(result.encodedPasswordHash());
                    assertThat(credential.getPasswordHash()).doesNotContain(rawPassword);
                });
    }

    @Test
    void passwordCredentialUpdatePersistsOnlyTheNewEncodedHash() {
        String loginId = unique("update-login");
        String initialPassword = unique("initial-raw");
        String updatedPassword = unique("updated-raw");
        IdentityAccount registered = accountRegistration.registerPasswordAccount(
                loginId, loginId + "@example.com", initialPassword);
        PasswordCredentialEntity before = passwordCredentialRepository.findById(registered.accountId())
                .orElseThrow();

        var result = passwordCredentialUpdate.updatePassword(registered.accountId(), updatedPassword);

        assertThat(result.encodedPassword()).isNotEqualTo(updatedPassword);
        assertThat(passwordEncoder.matches(updatedPassword, result.encodedPassword())).isTrue();
        PasswordCredentialEntity after = passwordCredentialRepository.findById(registered.accountId())
                .orElseThrow();
        assertThat(after.getAccountId()).isEqualTo(before.getAccountId());
        assertThat(after.getLoginId()).isEqualTo(before.getLoginId());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getPasswordHash()).isEqualTo(result.encodedPassword());
        assertThat(after.getPasswordHash()).isNotEqualTo(before.getPasswordHash());
        assertThat(after.getPasswordHash()).doesNotContain(updatedPassword);
    }

    @Test
    void sequentialDuplicateLoginIdUsesTheTypedFailureContract() {
        String loginId = unique("sequential-login");
        accountRegistration.registerPasswordAccount(loginId, loginId + "@one.example.com", "first-password");

        assertThatThrownBy(() -> accountRegistration.registerPasswordAccount(
                loginId, loginId + "@two.example.com", "second-password"))
                .isInstanceOf(DuplicateLoginIdException.class);
    }

    @Test
    void databaseUniqueViolationUsesTypedFailureAndRollsBackTheLosingAccount() throws Exception {
        String loginId = unique("concurrent-login");
        String firstEmail = loginId + "-one@example.com";
        String secondEmail = loginId + "-two@example.com";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        REGISTRATION_RACE_GATE.arm(loginId);
        try {
            Future<Object> first = executor.submit(() -> registerOrReturnFailure(
                    loginId, firstEmail, "first-concurrent-password"));
            Future<Object> second = executor.submit(() -> registerOrReturnFailure(
                    loginId, secondEmail, "second-concurrent-password"));

            assertThat(REGISTRATION_RACE_GATE.awaitBothPreChecks()).isTrue();
            REGISTRATION_RACE_GATE.release();

            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).allMatch(outcome ->
                    outcome instanceof IdentityAccount || outcome instanceof DuplicateLoginIdException);
            assertThat(outcomes.stream().filter(IdentityAccount.class::isInstance).toList()).hasSize(1);
            assertThat(outcomes.stream().filter(DuplicateLoginIdException.class::isInstance).toList()).hasSize(1);

            IdentityAccount winner = outcomes.stream()
                    .filter(IdentityAccount.class::isInstance)
                    .map(IdentityAccount.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(passwordCredentialRepository.findAll().stream()
                    .filter(credential -> loginId.equals(credential.getLoginId())))
                    .hasSize(1);
            assertThat(platformAccountRepository.findAll().stream()
                    .filter(account -> firstEmail.equals(account.getPrimaryEmail())
                            || secondEmail.equals(account.getPrimaryEmail())))
                    .hasSize(1);
            assertThat(platformAccountRepository.findById(winner.accountId())).isPresent();
        } finally {
            REGISTRATION_RACE_GATE.release();
            REGISTRATION_RACE_GATE.clear();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void platformAccountAndPasswordCredentialCanBeSavedAndRead() {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create("manager@example.com"));

        assertThat(account.getId()).isNotNull();
        assertThat(account.getStatus()).isEqualTo(com.ranikunlabs.platform.identity.core.AccountStatus.ACTIVE);
        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isNotNull();
        assertThat(platformAccountRepository.findById(account.getId()))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(account.getId());
                    assertThat(found.getPrimaryEmail()).isEqualTo("manager@example.com");
                });

        PasswordCredentialEntity credential = passwordCredentialRepository.saveAndFlush(
                PasswordCredentialEntity.create(account.getId(), "manager-login", "encoded-hash"));

        assertThat(passwordCredentialRepository.findByLoginId("manager-login"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getAccountId()).isEqualTo(account.getId());
                    assertThat(found.getPasswordHash()).isEqualTo("encoded-hash");
                });
        assertThat(passwordCredentialRepository.existsByLoginId("manager-login")).isTrue();
    }

    @Test
    void externalIdentityLookupMapsAccountStatusAndUniqueProviderSubject() {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create("oauth@example.com"));
        ExternalIdentityEntity externalIdentity = externalIdentityRepository.saveAndFlush(
                ExternalIdentityEntity.create(account.getId(), "kakao", "subject-1", "oauth@example.com"));

        assertThat(externalIdentityLookup.findByProviderSubject("kakao", "subject-1"))
                .contains(new LinkedAccountView(account.getId(), LinkedAccountStatus.ACTIVE));
        assertThat(externalIdentityRepository.findByProviderAndProviderSubject("kakao", "subject-1"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(externalIdentity.getId());
                    assertThat(found.getAccountId()).isEqualTo(account.getId());
                });

        assertThatThrownBy(() -> externalIdentityRepository.saveAndFlush(
                ExternalIdentityEntity.create(account.getId(), "kakao", "subject-1", "duplicate@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refreshTokenAdapterPersistsAndRotatesAccountSessions() {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create("session@example.com"));
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MICROS);

        tokenSession.replaceForAccount(account.getId(), "refresh-1", expiresAt);
        assertThat(tokenSession.findByToken("refresh-1"))
                .hasValueSatisfying(found -> {
                    assertThat(found.tokenValue()).isEqualTo("refresh-1");
                    assertThat(found.accountId()).isEqualTo(account.getId());
                    assertThat(found.expiresAt().toInstant())
                            .isEqualTo(expiresAt.truncatedTo(ChronoUnit.MICROS).toInstant());
                });

        OffsetDateTime rotatedExpiry = expiresAt.plusDays(1);
        tokenSession.rotate(
                new RefreshSession("refresh-1", account.getId(), expiresAt),
                "refresh-2",
                rotatedExpiry
        );
        assertThat(tokenSession.findByToken("refresh-1")).isEmpty();
        assertThat(tokenSession.findByToken("refresh-2"))
                .hasValueSatisfying(found -> {
                    assertThat(found.tokenValue()).isEqualTo("refresh-2");
                    assertThat(found.accountId()).isEqualTo(account.getId());
                    assertThat(found.expiresAt().toInstant())
                            .isEqualTo(rotatedExpiry.truncatedTo(ChronoUnit.MICROS).toInstant());
                });

        tokenSession.deleteForAccount(account.getId());
        assertThat(tokenSession.findByToken("refresh-2")).isEmpty();
    }

    @Test
    void concurrentLoginsForOneAccountLeaveExactlyOnePersistedSession() throws Exception {
        String loginId = unique("session-login");
        String password = unique("session-password");
        IdentityAccount account = accountRegistration.registerPasswordAccount(
                loginId, loginId + "@example.com", password);
        when(crmIdentityProjectionPort.getIdentityClaims(account.accountId())).thenReturn(claims());

        List<Object> outcomes = runConcurrently(
                () -> authService.login(loginId, password),
                () -> authService.login(loginId, password));

        assertThat(outcomes).allMatch(AuthTokenBundle.class::isInstance);
        List<RefreshTokenEntity> sessions = sessionsFor(account.accountId());
        assertThat(sessions).hasSize(1);
        assertThat(outcomes.stream()
                .map(AuthTokenBundle.class::cast)
                .map(AuthTokenBundle::refreshToken))
                .contains(sessions.getFirst().getRefreshToken());
    }

    @Test
    void concurrentRefreshOfTheSameTokenHasExactlyOneWinner() throws Exception {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create(unique("refresh-race") + "@example.com"));
        String oldToken = seedOlderRefreshSession(account.getId());
        when(crmIdentityProjectionPort.getIdentityClaims(account.getId())).thenReturn(claims());

        List<Object> outcomes = runConcurrently(
                () -> authService.refreshToken(oldToken),
                () -> authService.refreshToken(oldToken));

        assertThat(outcomes.stream().filter(AuthTokenBundle.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(TokenLifecycleException.class::isInstance)).hasSize(1);
        TokenLifecycleException loser = outcomes.stream()
                .filter(TokenLifecycleException.class::isInstance)
                .map(TokenLifecycleException.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(loser.reason()).isEqualTo(TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND);
        assertThat(sessionsFor(account.getId())).hasSize(1);
        assertThat(tokenSession.findByToken(oldToken)).isEmpty();
    }

    @Test
    void rotatedRefreshTokenCannotBeReplayedSequentially() {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create(unique("refresh-replay") + "@example.com"));
        String oldToken = seedOlderRefreshSession(account.getId());
        when(crmIdentityProjectionPort.getIdentityClaims(account.getId())).thenReturn(claims());

        AuthTokenBundle rotated = authService.refreshToken(oldToken);

        assertThat(rotated.refreshToken()).isNotEqualTo(oldToken);
        assertThatThrownBy(() -> authService.refreshToken(oldToken))
                .isInstanceOfSatisfying(TokenLifecycleException.class, failure ->
                        assertThat(failure.reason())
                                .isEqualTo(TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND));
        assertThat(sessionsFor(account.getId())).singleElement()
                .extracting(RefreshTokenEntity::getRefreshToken)
                .isEqualTo(rotated.refreshToken());
    }

    @Test
    void loginAndRefreshForDifferentAccountsProceedConcurrently() throws Exception {
        String loginId = unique("different-account-login");
        String password = unique("different-account-password");
        IdentityAccount loginAccount = accountRegistration.registerPasswordAccount(
                loginId, loginId + "@example.com", password);
        PlatformAccountEntity refreshAccount = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create(unique("different-account-refresh") + "@example.com"));
        String oldToken = seedOlderRefreshSession(refreshAccount.getId());
        when(crmIdentityProjectionPort.getIdentityClaims(loginAccount.accountId())).thenReturn(claims());
        when(crmIdentityProjectionPort.getIdentityClaims(refreshAccount.getId())).thenReturn(claims());

        List<Object> outcomes = runConcurrently(
                () -> authService.login(loginId, password),
                () -> authService.refreshToken(oldToken));

        assertThat(outcomes).allMatch(AuthTokenBundle.class::isInstance);
        assertThat(sessionsFor(loginAccount.accountId())).hasSize(1);
        assertThat(sessionsFor(refreshAccount.getId())).hasSize(1);
    }

    @Test
    void logoutStillDeletesTheAccountRefreshSession() {
        String loginId = unique("logout-session");
        String password = unique("logout-password");
        IdentityAccount account = accountRegistration.registerPasswordAccount(
                loginId, loginId + "@example.com", password);
        when(crmIdentityProjectionPort.getIdentityClaims(account.accountId())).thenReturn(claims());
        AuthTokenBundle tokens = authService.login(loginId, password);

        authService.logout(account.accountId(), tokens.accessToken());

        assertThat(sessionsFor(account.accountId())).isEmpty();
    }

    @Test
    void productClientReaderReturnsSeedAndFailsClosedByCanonicalReason() {
        assertThat(productClientReader.requireEnabled("carelog-web").product()).isEqualTo(Product.CARELOG);
        assertThat(productClientReader.requireEnabled("carelog-mobile").channel())
                .isEqualTo(ProductClientChannel.MOBILE);

        ProductClientEntity disabled = productClientRepository.saveAndFlush(
                ProductClientEntity.create("disabled-client", Product.CARELOG, ProductClientChannel.WEB, false));
        assertThat(disabled.isEnabled()).isFalse();

        assertThatThrownBy(() -> productClientReader.requireEnabled("invalid client"))
                .isInstanceOfSatisfying(ProductClientLookupException.class, error ->
                        assertThat(error.reason())
                                .isEqualTo(ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID));
        assertThatThrownBy(() -> productClientReader.requireEnabled("unknown-client"))
                .isInstanceOfSatisfying(ProductClientLookupException.class, error ->
                        assertThat(error.reason())
                                .isEqualTo(ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT));
        assertThatThrownBy(() -> productClientReader.requireEnabled("disabled-client"))
                .isInstanceOfSatisfying(ProductClientLookupException.class, error ->
                        assertThat(error.reason())
                                .isEqualTo(ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT));
    }

    @Test
    void refreshRowLockBlocksTheCompetingLookupUntilTheFirstTransactionCommits() throws Exception {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create(unique("refresh-lock-proof") + "@example.com"));
        String oldToken = seedOlderRefreshSession(account.getId());
        String rotatedToken = unique("refresh-lock-proof-rotated");
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> inNewTransaction(() -> {
                RefreshTokenEntity locked = refreshTokenRepository.findByRefreshTokenForUpdate(oldToken)
                        .orElseThrow();
                firstLockAcquired.countDown();
                awaitLatch(releaseFirst);
                locked.updateToken(rotatedToken, locked.getTokenExpiresAt());
                refreshTokenRepository.flush();
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                awaitLatch(firstLockAcquired);
                secondAttemptStarted.countDown();
                return inNewTransaction(() ->
                        refreshTokenRepository.findByRefreshTokenForUpdate(oldToken).isPresent());
            });

            assertThat(firstLockAcquired.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAttemptStarted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitRowLockContention()).isTrue();
            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(30, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            shutdown(executor);
        }

        assertThat(tokenSession.findByToken(oldToken)).isEmpty();
        assertThat(sessionsFor(account.getId())).singleElement()
                .extracting(RefreshTokenEntity::getRefreshToken)
                .isEqualTo(rotatedToken);
    }

    @Test
    void accountRowLockSerializesSessionReplacementAndStaysPerAccount() throws Exception {
        PlatformAccountEntity contended = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create(unique("account-lock-proof") + "@example.com"));
        PlatformAccountEntity independent = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create(unique("account-lock-independent") + "@example.com"));
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> first = executor.submit(() -> inNewTransaction(() -> {
                platformAccountRepository.findByIdForSessionReplacement(contended.getId()).orElseThrow();
                firstLockAcquired.countDown();
                awaitLatch(releaseFirst);
                tokenSession.replaceForAccount(
                        contended.getId(), unique("account-lock-first"), OffsetDateTime.now().plusDays(1));
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                awaitLatch(firstLockAcquired);
                secondAttemptStarted.countDown();
                return inNewTransaction(() -> {
                    platformAccountRepository.findByIdForSessionReplacement(contended.getId()).orElseThrow();
                    tokenSession.replaceForAccount(
                            contended.getId(), unique("account-lock-second"), OffsetDateTime.now().plusDays(1));
                    return true;
                });
            });

            assertThat(firstLockAcquired.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAttemptStarted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitRowLockContention()).isTrue();
            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            Future<Boolean> independentLock = executor.submit(() -> inNewTransaction(() -> {
                platformAccountRepository.findByIdForSessionReplacement(independent.getId()).orElseThrow();
                tokenSession.replaceForAccount(
                        independent.getId(), unique("account-lock-other"), OffsetDateTime.now().plusDays(1));
                return true;
            }));
            assertThat(independentLock.get(30, TimeUnit.SECONDS)).isTrue();

            releaseFirst.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            shutdown(executor);
        }

        assertThat(sessionsFor(contended.getId())).hasSize(1);
        assertThat(sessionsFor(independent.getId())).hasSize(1);
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the lock coordination latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating the lock proof", exception);
        }
    }

    /** Confirms with PostgreSQL itself that a backend is blocked behind a row lock. */
    private boolean awaitRowLockContention() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (blockedBackendCount() > 0) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return false;
    }

    private int blockedBackendCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select count(*) from pg_stat_activity"
                             + " where cardinality(pg_blocking_pids(pid)) > 0")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String seedOlderRefreshSession(UUID accountId) {
        Clock earlierClock = Clock.fixed(Instant.now().minus(Duration.ofMinutes(2)), ZoneOffset.UTC);
        JwtTokenProvider earlierProvider = new JwtTokenProvider(
                JWT_SECRET, ACCESS_TOKEN_VALIDITY, REFRESH_TOKEN_VALIDITY, earlierClock);
        String token = earlierProvider.generateRefreshToken(accountId);
        tokenSession.replaceForAccount(accountId, token, earlierProvider.getRefreshTokenExpiryDate());
        return token;
    }

    private CRMIdentityClaims claims() {
        return new CRMIdentityClaims(UUID.randomUUID(), "MEMBER", UUID.randomUUID());
    }

    private List<RefreshTokenEntity> sessionsFor(UUID accountId) {
        return refreshTokenRepository.findAll().stream()
                .filter(session -> accountId.equals(session.getAccountId()))
                .toList();
    }

    @SafeVarargs
    private final List<Object> runConcurrently(Callable<?>... operations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        CyclicBarrier start = new CyclicBarrier(operations.length + 1);
        try {
            List<Future<Object>> futures = java.util.Arrays.stream(operations)
                    .map(operation -> executor.submit(() -> {
                        start.await(30, TimeUnit.SECONDS);
                        try {
                            return operation.call();
                        } catch (Throwable failure) {
                            return failure;
                        }
                    }))
                    .toList();
            start.await(30, TimeUnit.SECONDS);
            java.util.ArrayList<Object> outcomes = new java.util.ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private Object registerOrReturnFailure(String loginId, String email, String rawPassword) {
        try {
            return accountRegistration.registerPasswordAccount(loginId, email, rawPassword);
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class RegistrationRaceGate {

        private volatile String loginId;
        private volatile CountDownLatch observedPreChecks;
        private volatile CountDownLatch release;

        void arm(String loginId) {
            this.loginId = loginId;
            this.observedPreChecks = new CountDownLatch(2);
            this.release = new CountDownLatch(1);
        }

        void afterActualExistsCheck(String requestedLoginId) {
            CountDownLatch observed = observedPreChecks;
            CountDownLatch gateRelease = release;
            if (observed == null || gateRelease == null || !requestedLoginId.equals(loginId)) {
                return;
            }
            observed.countDown();
            try {
                if (!gateRelease.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for registration race release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while coordinating registration race", exception);
            }
        }

        boolean awaitBothPreChecks() throws InterruptedException {
            CountDownLatch observed = observedPreChecks;
            return observed != null && observed.await(30, TimeUnit.SECONDS);
        }

        void release() {
            CountDownLatch gateRelease = release;
            if (gateRelease != null) {
                gateRelease.countDown();
            }
        }

        void clear() {
            loginId = null;
            observedPreChecks = null;
            release = null;
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private void assertSingleBean(Class<?> beanType) {
        assertThat(applicationContext.getBeansOfType(beanType)).hasSize(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({IdentityModuleConfiguration.class, AuthPersistenceTestConfiguration.class})
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthPersistenceTestConfiguration {

        @org.springframework.context.annotation.Bean
        CRMIdentityProjectionPort crmIdentityProjectionPort() {
            return mock(CRMIdentityProjectionPort.class);
        }

        @org.springframework.context.annotation.Bean
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(values);
            return template;
        }

        @org.springframework.context.annotation.Bean
        static BeanPostProcessor registrationRaceRepositoryGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof PasswordCredentialJpaRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            PasswordCredentialJpaRepository.class.getClassLoader(),
                            new Class<?>[]{PasswordCredentialJpaRepository.class},
                            (proxy, method, arguments) -> {
                                Object result = invoke(repository, method, arguments);
                                if ("existsByLoginId".equals(method.getName())
                                        && arguments != null
                                        && arguments.length == 1
                                        && arguments[0] instanceof String requestedLoginId) {
                                    REGISTRATION_RACE_GATE.afterActualExistsCheck(requestedLoginId);
                                }
                                return result;
                            });
                }
            };
        }
    }
}
