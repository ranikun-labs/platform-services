package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.ExternalIdentityLookupJpaAdapter;
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
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies JPA repository behavior against the migrated Identity schema. */
@SpringBootTest(
        classes = IdentityPersistenceRepositoryTest.TestApplication.class,
        webEnvironment = WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "platform.identity.redis-host=localhost",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=test:identity:"
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

    @Autowired
    IdentityPersistenceRepositoryTest(
            PlatformAccountJpaRepository platformAccountRepository,
            PasswordCredentialJpaRepository passwordCredentialRepository,
            ExternalIdentityJpaRepository externalIdentityRepository,
            ProductClientJpaRepository productClientRepository,
            ExternalIdentityLookupJpaAdapter externalIdentityLookup,
            ProductClientReaderJpaAdapter productClientReader,
            TokenSessionJpaAdapter tokenSession
    ) {
        this.platformAccountRepository = platformAccountRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.productClientRepository = productClientRepository;
        this.externalIdentityLookup = externalIdentityLookup;
        this.productClientReader = productClientReader;
        this.tokenSession = tokenSession;
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(IdentityModuleConfiguration.class)
    static class TestApplication {
    }
}
