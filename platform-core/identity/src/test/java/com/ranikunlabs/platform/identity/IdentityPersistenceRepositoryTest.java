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
import com.ranikunlabs.platform.identity.adapter.persistence.repository.ExternalIdentityJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.ProductClientJpaRepository;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.IdentityAccount;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.PasswordCredentialUpdatePort;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final ExternalIdentityLookupJpaAdapter externalIdentityLookup;
    private final ProductClientReaderJpaAdapter productClientReader;
    private final TokenSessionJpaAdapter tokenSession;
    private final IdentityAccountRegistrationJpaAdapter accountRegistration;
    private final PasswordCredentialUpdateJpaAdapter passwordCredentialUpdate;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationContext applicationContext;

    private static final RegistrationRaceGate REGISTRATION_RACE_GATE = new RegistrationRaceGate();

    @Autowired
    IdentityPersistenceRepositoryTest(
            ApplicationContext applicationContext,
            PlatformAccountJpaRepository platformAccountRepository,
            PasswordCredentialJpaRepository passwordCredentialRepository,
            ExternalIdentityJpaRepository externalIdentityRepository,
            ProductClientJpaRepository productClientRepository,
            ExternalIdentityLookupJpaAdapter externalIdentityLookup,
            ProductClientReaderJpaAdapter productClientReader,
            TokenSessionJpaAdapter tokenSession,
            IdentityAccountRegistrationJpaAdapter accountRegistration,
            PasswordCredentialUpdateJpaAdapter passwordCredentialUpdate,
            PasswordEncoder passwordEncoder
    ) {
        this.applicationContext = applicationContext;
        this.platformAccountRepository = platformAccountRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.productClientRepository = productClientRepository;
        this.externalIdentityLookup = externalIdentityLookup;
        this.productClientReader = productClientReader;
        this.tokenSession = tokenSession;
        this.accountRegistration = accountRegistration;
        this.passwordCredentialUpdate = passwordCredentialUpdate;
        this.passwordEncoder = passwordEncoder;
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
