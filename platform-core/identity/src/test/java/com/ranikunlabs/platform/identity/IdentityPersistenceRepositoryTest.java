package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.CarelogRegistrationStore;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.Outcome;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentUnavailableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.ranikunlabs.platform.identity.adapter.persistence.ExternalIdentityLookupJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.PasswordCredentialUpdateJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.ProductClientReaderJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.TokenSessionJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.ExternalIdentityEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.ProductClientEntity;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
    private final TokenProviderPort tokenProvider;
    private final CRMIdentityProjectionPort crmIdentityProjectionPort;
    private final ApplicationContext applicationContext;
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
            TokenProviderPort tokenProvider,
            CRMIdentityProjectionPort crmIdentityProjectionPort,
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
        this.tokenProvider = tokenProvider;
        this.crmIdentityProjectionPort = crmIdentityProjectionPort;
        this.dataSource = dataSource;
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(IdentityDatabaseSchema.NAME)
                .defaultSchema(IdentityDatabaseSchema.NAME)
                .createSchemas(true)
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
    void passwordAccountRegistrationPersistsTheIdentityOwnedRows() throws SQLException {
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

        assertThat(tableExists(IdentityDatabaseSchema.NAME, "platform_accounts")).isTrue();
        assertThat(tableExists("public", "platform_accounts")).isFalse();
        assertThat(accountRowCount(result.accountId())).isOne();
    }

    @Test
    void carelogRegistrationPersistsPendingRetryAndActivatesOnlyAfterEnrollment() throws Exception {
        var store = registrationStore();
        var key = UUID.randomUUID().toString();
        var login = unique("carelog-registration");
        var calls = new AtomicInteger();
        var coordinator = new CarelogRegistrationCoordinator(store, (account, name) -> {
            if (calls.getAndIncrement() == 0) throw new EnrollmentUnavailableException();
        });
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.PENDING);
        var credential = passwordCredentialRepository.findByLoginId(login).orElseThrow();
        var accountId = credential.getAccountId();
        assertThat(platformAccountRepository.findById(accountId).orElseThrow().getStatus())
                .isEqualTo(com.ranikunlabs.platform.identity.core.AccountStatus.INACTIVE);
        assertThatThrownBy(() -> applicationContext.getBean(CredentialPort.class).authenticate(login, "password"))
                .isInstanceOf(RuntimeException.class);
        assertThat(coordinator.register(key, login, "mail@example.test", "different", "Name"))
                .isEqualTo(Outcome.CONFLICT);
        assertThat(calls).hasValue(1);
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.COMPLETED);
        assertThat(platformAccountRepository.findById(accountId).orElseThrow().getStatus())
                .isEqualTo(com.ranikunlabs.platform.identity.core.AccountStatus.ACTIVE);
        assertThat(applicationContext.getBean(CredentialPort.class).authenticate(login, "password").accountId())
                .isEqualTo(accountId);
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.COMPLETED);
        assertThat(calls).hasValue(2);
        new JdbcTemplate(dataSource).update("update identity.platform_accounts set primary_email = ? where id = ?", "changed@example.test", accountId);
        assertThat(coordinator.register(key, login, "changed@example.test", "password", "Name"))
                .isEqualTo(Outcome.CONFLICT);
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.COMPLETED);
        passwordCredentialUpdate.updatePassword(accountId, "new-password");
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.CONFLICT);
        assertThat(coordinator.register(key, login, "mail@example.test", "new-password", "Name"))
                .isEqualTo(Outcome.CONFLICT);
        assertThat(store.markConflict(key, accountId)).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void carelogRegistrationRawSqlPasswordChangesAdvanceRevisionForOldReplay() throws Exception {
        var key = UUID.randomUUID().toString();
        var login = unique("carelog-revision");
        var coordinator = new CarelogRegistrationCoordinator(registrationStore(), (account, name) -> { });
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name")).isEqualTo(Outcome.COMPLETED);
        var credential = passwordCredentialRepository.findByLoginId(login).orElseThrow();
        long original = credential.getCredentialRevision();
        new JdbcTemplate(dataSource).update("update identity.password_credentials set password_hash = ? where account_id = ?", passwordEncoder.encode("new-password"), credential.getAccountId());
        assertThat(passwordCredentialRepository.findById(credential.getAccountId()).orElseThrow().getCredentialRevision()).isEqualTo(original + 1);
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name")).isEqualTo(Outcome.CONFLICT);
        assertThat(coordinator.register(key, login, "mail@example.test", "new-password", "Name")).isEqualTo(Outcome.CONFLICT);
    }

    @Test
    void carelogRegistrationRejectsPasswordRotationDuringEnrollmentBeforeActivation() {
        var key = UUID.randomUUID().toString();
        var login = unique("carelog-rotation-during-enrollment");
        var coordinator = new CarelogRegistrationCoordinator(registrationStore(),
                (account, name) -> passwordCredentialUpdate.updatePassword(account, "new-password"));
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.CONFLICT);
        var credential = passwordCredentialRepository.findByLoginId(login).orElseThrow();
        assertThat(platformAccountRepository.findById(credential.getAccountId()).orElseThrow().getStatus())
                .isEqualTo(com.ranikunlabs.platform.identity.core.AccountStatus.INACTIVE);
        assertThat(coordinator.register(key, login, "mail@example.test", "password", "Name"))
                .isEqualTo(Outcome.CONFLICT);
        assertThat(coordinator.register(key, login, "mail@example.test", "new-password", "Name"))
                .isEqualTo(Outcome.CONFLICT);
    }

    @Test
    void carelogRegistrationParallelSameKeyCreatesOneInactiveAccount() throws Exception {
        var store = registrationStore();
        var key = UUID.randomUUID().toString();
        var login = unique("carelog-parallel");
        var coordinator = new CarelogRegistrationCoordinator(store, (account, name) -> { throw new EnrollmentUnavailableException(); });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> coordinator.register(key, login, "parallel@example.test", "password", "Name"));
            var second = executor.submit(() -> coordinator.register(key, login, "parallel@example.test", "password", "Name"));
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(Outcome.PENDING);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(Outcome.PENDING);
            var accountId = passwordCredentialRepository.findByLoginId(login).orElseThrow().getAccountId();
            assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from identity.carelog_registration_operation where operation_key = ?", Integer.class, UUID.fromString(key))).isEqualTo(1);
            assertThat(platformAccountRepository.findById(accountId).orElseThrow().getStatus())
                    .isEqualTo(com.ranikunlabs.platform.identity.core.AccountStatus.INACTIVE);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void carelogRegistrationRollsBackAccountAndCredentialWhenOperationInsertFails() {
        var login = unique("carelog-rollback");
        var email = login + "@example.test";
        var failingJdbc = new JdbcTemplate(dataSource) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("insert into identity.carelog_registration_operation")) {
                    throw new org.springframework.dao.DataIntegrityViolationException("synthetic insert failure");
                }
                return super.update(sql, args);
            }
        };
        var store = new CarelogRegistrationStore(platformAccountRepository, passwordCredentialRepository,
                passwordEncoder, failingJdbc, applicationContext.getBean(PlatformTransactionManager.class));
        var coordinator = new CarelogRegistrationCoordinator(store, (account, name) -> { });
        assertThatThrownBy(() -> coordinator.register(UUID.randomUUID().toString(), login, email, "password", "Name"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(passwordCredentialRepository.findByLoginId(login)).isEmpty();
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from identity.platform_accounts where primary_email = ?", Integer.class, email)).isZero();
    }

    @Test
    void carelogRegistrationConcurrentTerminalTransitionsAgreeOnDurableWinner() throws Exception {
        var store = registrationStore();
        var key = UUID.randomUUID().toString();
        var login = unique("carelog-terminal-race");
        var coordinator = new CarelogRegistrationCoordinator(store, (account, name) -> { throw new EnrollmentUnavailableException(); });
        assertThat(coordinator.register(key, login, "race@example.test", "password", "Name")).isEqualTo(Outcome.PENDING);
        var accountId = passwordCredentialRepository.findByLoginId(login).orElseThrow().getAccountId();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var success = executor.submit(() -> store.complete(key, accountId));
            var conflict = executor.submit(() -> store.markConflict(key, accountId));
            var result = success.get(20, TimeUnit.SECONDS);
            assertThat(conflict.get(20, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result).isIn(Outcome.COMPLETED, Outcome.CONFLICT);
            assertThat(registrationStore().complete(key, accountId)).isEqualTo(result);
            assertThat(platformAccountRepository.findById(accountId).orElseThrow().getStatus())
                    .isEqualTo(result == Outcome.COMPLETED
                            ? com.ranikunlabs.platform.identity.core.AccountStatus.ACTIVE
                            : com.ranikunlabs.platform.identity.core.AccountStatus.INACTIVE);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void carelogRegistrationConcurrentDifferentKeysForSameLoginRollsBackLoser() throws Exception {
        var store = registrationStore();
        var login = unique("carelog-login-race");
        var email1 = login + "-one@example.test";
        var email2 = login + "-two@example.test";
        var coordinator = new CarelogRegistrationCoordinator(store, (account, name) -> { throw new EnrollmentUnavailableException(); });
        var gate = new java.util.concurrent.CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { gate.await(); return coordinator.register(UUID.randomUUID().toString(), login, email1, "password", "Name"); });
            var second = executor.submit(() -> { gate.await(); return coordinator.register(UUID.randomUUID().toString(), login, email2, "password", "Name"); });
            gate.countDown();
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Outcome.PENDING, Outcome.CONFLICT);
            assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from identity.password_credentials where login_id = ?", Integer.class, login)).isEqualTo(1);
            assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from identity.platform_accounts where primary_email in (?, ?)", Integer.class, email1, email2)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private CarelogRegistrationStore registrationStore() {
        return new CarelogRegistrationStore(platformAccountRepository, passwordCredentialRepository,
                passwordEncoder, new JdbcTemplate(dataSource), applicationContext.getBean(PlatformTransactionManager.class));
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
    void missingRotationTargetDoesNotExposeTheConsumedRefreshCredential() {
        String staleRefreshToken = "synthetic-stale-refresh-token";

        assertThatThrownBy(() -> tokenSession.rotate(
                new RefreshSession(staleRefreshToken, UUID.randomUUID(), OffsetDateTime.now().plusDays(1)),
                "replacement-token",
                OffsetDateTime.now().plusDays(2)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Refresh session rotation target is no longer available")
                .hasMessageNotContaining(staleRefreshToken);
    }

    @Test
    void concurrentSessionReplacementUsesAccountLockAndLeavesOneSession() throws Exception {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create("replacement-race@example.com"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> first = executor.submit(() -> replaceAfterStart(
                    account.getId(), "replacement-race-1", ready, start));
            Future<Throwable> second = executor.submit(() -> replaceAfterStart(
                    account.getId(), "replacement-race-2", ready, start));

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(refreshTokenRepository.findByAccountId(account.getId()))
                    .hasValueSatisfying(session -> assertThat(session.getAccountId()).isEqualTo(account.getId()));
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentRefreshRotationConsumesTheOriginalCredentialOnlyOnce() throws Exception {
        PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                PlatformAccountEntity.create("rotation-race@example.com"));
        String originalToken = tokenProvider.generateRefreshToken(account.getId());
        tokenSession.replaceForAccount(account.getId(), originalToken, OffsetDateTime.now().plusDays(1));

        CountDownLatch claimsEntered = new CountDownLatch(1);
        CountDownLatch releaseClaims = new CountDownLatch(1);
        AtomicInteger claimsCalls = new AtomicInteger();
        CRMIdentityClaims claims = new CRMIdentityClaims(
                UUID.randomUUID(), "MANAGER", UUID.randomUUID());
        doAnswer(invocation -> {
            if (claimsCalls.incrementAndGet() == 1) {
                claimsEntered.countDown();
            }
            try {
                if (!releaseClaims.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release refresh claims lookup");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while coordinating refresh race", exception);
            }
            return claims;
        }).when(crmIdentityProjectionPort).getIdentityClaims(account.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> refreshOrReturnFailure(originalToken));
            Future<Object> second = executor.submit(() -> refreshOrReturnFailure(originalToken));

            assertThat(claimsEntered.await(30, TimeUnit.SECONDS)).isTrue();
            releaseClaims.countDown();

            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(AuthTokenBundle.class::isInstance).toList()).hasSize(1);
            assertThat(outcomes.stream().filter(TokenLifecycleException.class::isInstance).toList())
                    .singleElement()
                    .satisfies(failure -> assertThat(((TokenLifecycleException) failure).reason())
                            .isEqualTo(TokenLifecycleException.Reason.REFRESH_TOKEN_NOT_FOUND));
            assertThat(claimsCalls).hasValue(1);
            assertThat(tokenSession.findByToken(originalToken)).isEmpty();
            assertThat(refreshTokenRepository.findByAccountId(account.getId()))
                    .hasValueSatisfying(session -> assertThat(session.getRefreshToken())
                            .isNotEqualTo(originalToken));
        } finally {
            releaseClaims.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
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

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private Object registerOrReturnFailure(String loginId, String email, String rawPassword) {
        try {
            return accountRegistration.registerPasswordAccount(loginId, email, rawPassword);
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Throwable replaceAfterStart(
            UUID accountId,
            String token,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start session replacement race");
            }
            tokenSession.replaceForAccount(accountId, token, OffsetDateTime.now().plusDays(1));
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Object refreshOrReturnFailure(String rawRefreshToken) {
        try {
            return authService.refreshToken(rawRefreshToken);
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

    private boolean tableExists(String schema, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private int accountRowCount(UUID accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM identity.platform_accounts WHERE id = ?")) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
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
            return mock(StringRedisTemplate.class);
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
