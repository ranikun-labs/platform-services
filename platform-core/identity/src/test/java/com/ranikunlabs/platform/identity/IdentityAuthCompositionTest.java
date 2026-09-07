package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.PasswordCredentialUpdateJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.adapter.redis.RedisTokenBlacklistAdapter;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.PasswordCredentialUpdatePort;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdentityAuthCompositionTest {

    private static final String VALID_SECRET = "01234567890123456789012345678901";

    private ApplicationContextRunner authContextRunner() {
        return baseContextRunner()
                .withPropertyValues(
                        "platform.identity.auth.enabled=true",
                        "jwt.access-token-validity=900000",
                        "jwt.refresh-token-validity=1209600000");
    }

    private ApplicationContextRunner foundationContextRunner() {
        return baseContextRunner()
                .withPropertyValues("platform.identity.auth.enabled=false");
    }

    private ApplicationContextRunner baseContextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(IdentityModuleConfiguration.class, AuthCompositionTestConfiguration.class)
                .withPropertyValues(
                        "platform.identity.persistence.enabled=false",
                        "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                        "platform.identity.datasource.username=identity",
                        "platform.identity.datasource.password=provided-at-runtime",
                        "platform.identity.redis-host=redis",
                        "platform.identity.redis-port=6379",
                        "platform.identity.redis-key-prefix=platform:identity:");
    }

    @Test
    void composesTheEnabledIdentityAuthBeanGraphWithoutExternalServices() {
        authContextRunner().withPropertyValues("jwt.secret-key=" + VALID_SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdentityAuthConfiguration.class);
            assertThat(context).hasSingleBean(IdentityJwtProperties.class);
            assertThat(context).hasSingleBean(JwtTokenProvider.class);
            assertThat(context).hasSingleBean(TokenProviderPort.class);
            assertThat(context).hasSingleBean(PasswordCredentialJpaRepository.class);
            assertThat(context).hasSingleBean(PlatformAccountJpaRepository.class);
            assertThat(context).hasSingleBean(IdentityCredentialAdapter.class);
            assertThat(context).hasSingleBean(CredentialPort.class);
            assertThat(context).hasSingleBean(IdentityAccountRegistrationJpaAdapter.class);
            assertThat(context).hasSingleBean(IdentityAccountRegistrationPort.class);
            assertThat(context).hasSingleBean(PasswordCredentialUpdateJpaAdapter.class);
            assertThat(context).hasSingleBean(PasswordCredentialUpdatePort.class);
            assertThat(context).hasSingleBean(AccountStatusPort.class);
            assertThat(context).hasSingleBean(TokenSessionPort.class);
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
            assertThat(context).hasSingleBean(RedisTokenBlacklistAdapter.class);
            assertThat(context).hasSingleBean(TokenBlacklistPort.class);
            assertThat(context).hasSingleBean(AuthTokenIssuanceService.class);
            assertThat(context).hasSingleBean(AuthServiceImpl.class);
            assertThat(context).hasSingleBean(AuthService.class);
            assertThat(context).hasSingleBean(CRMIdentityProjectionPort.class);
            assertThat(context).hasSingleBean(OAuthStateStore.class);
            // Proves the production ObjectMapper (Jackson 3) comes from the real OAuth
            // composition, not from a test-only fixture bean masking a missing production bean.
            assertThat(context).hasSingleBean(ObjectMapper.class);

            assertThat(context.getBean(TokenProviderPort.class))
                    .isSameAs(context.getBean(JwtTokenProvider.class));
            assertThat(context.getBean(CredentialPort.class))
                    .isSameAs(context.getBean(IdentityCredentialAdapter.class));
            assertThat(context.getBean(TokenBlacklistPort.class))
                    .isSameAs(context.getBean(RedisTokenBlacklistAdapter.class));
            assertThat(context.getBean(AuthService.class))
                    .isSameAs(context.getBean(AuthServiceImpl.class));
        });
    }

    @Test
    void foundationModeBootsWithoutJwtConfigurationWhenAuthIsDisabled() {
        foundationContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdentityAuthConfiguration.class);
            assertThat(context).doesNotHaveBean(IdentityJwtProperties.class);
            assertThat(context).doesNotHaveBean(JwtTokenProvider.class);
        });
    }

    @Test
    void authEnabledWithoutSecretKeyFailsConfigurationBinding() {
        assertAuthStartupFails(authContextRunner());
    }

    @Test
    void authEnabledWithBlankSecretKeyFailsConfigurationBinding() {
        assertAuthStartupFails(authContextRunner().withPropertyValues("jwt.secret-key="));
    }

    @Test
    void authEnabledWithNonPositiveAccessValidityFailsConfigurationBinding() {
        assertAuthStartupFails(authContextRunner()
                .withPropertyValues("jwt.secret-key=" + VALID_SECRET, "jwt.access-token-validity=0"));
        assertAuthStartupFails(authContextRunner()
                .withPropertyValues("jwt.secret-key=" + VALID_SECRET, "jwt.access-token-validity=-1"));
    }

    @Test
    void authEnabledWithNonPositiveRefreshValidityFailsConfigurationBinding() {
        assertAuthStartupFails(authContextRunner()
                .withPropertyValues("jwt.secret-key=" + VALID_SECRET, "jwt.refresh-token-validity=0"));
        assertAuthStartupFails(authContextRunner()
                .withPropertyValues("jwt.secret-key=" + VALID_SECRET, "jwt.refresh-token-validity=-1"));
    }

    @Test
    void authEnabledWithCryptographicallyShortSecretFailsStartup() {
        assertAuthStartupFails(authContextRunner().withPropertyValues("jwt.secret-key=short"));
    }

    @Test
    void jwtPropertiesRedactSecretInDiagnostics() {
        IdentityJwtProperties properties = new IdentityJwtProperties(VALID_SECRET, 900000, 1209600000);

        assertThat(properties.toString())
                .contains("secretKey=REDACTED")
                .doesNotContain(VALID_SECRET);
    }

    private void assertAuthStartupFails(ApplicationContextRunner runner) {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthCompositionTestConfiguration {

        @Bean
        PasswordCredentialJpaRepository passwordCredentialJpaRepository() {
            return mock(PasswordCredentialJpaRepository.class);
        }

        @Bean
        PlatformAccountJpaRepository platformAccountJpaRepository() {
            return mock(PlatformAccountJpaRepository.class);
        }

        @Bean
        AccountStatusPort accountStatusPort() {
            return mock(AccountStatusPort.class);
        }

        @Bean
        TokenSessionPort tokenSessionPort() {
            return mock(TokenSessionPort.class);
        }

        @Bean
        CRMIdentityProjectionPort crmIdentityProjectionPort() {
            return mock(CRMIdentityProjectionPort.class);
        }

        @Bean
        ExternalIdentityLookupPort externalIdentityLookupPort() {
            return mock(ExternalIdentityLookupPort.class);
        }

        @Bean
        ProductClientReader productClientReader() {
            return mock(ProductClientReader.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
