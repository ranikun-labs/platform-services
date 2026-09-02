package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves that enabling the Identity Auth runtime without its required production settings stops
 * composition instead of yielding a service that cannot honour authentication correctly.
 *
 * <p>Persistence and Redis collaborators are stubbed so each case isolates one configuration fault.
 * Kakao credentials are deliberately not validated here: the production Kakao provider is still
 * deferred, so requiring them would block a deployment that has no live Kakao integration.
 */
class IdentityAuthConfigurationFailFastTest {

    private static final String VALID_SECRET = "fail-fast-identity-secret-key-0123456789";
    private static final String VALID_CLAIMS_URL = "http://carelog-be:8080";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityModuleConfiguration.class, StubbedCollaborators.class)
            .withPropertyValues(
                    "platform.identity.auth.enabled=true",
                    "platform.identity.persistence.enabled=false",
                    "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                    "platform.identity.datasource.username=identity",
                    "platform.identity.datasource.password=provided-at-runtime",
                    "platform.identity.redis-host=redis",
                    "platform.identity.redis-port=6379",
                    "platform.identity.redis-key-prefix=platform:identity:");

    private ApplicationContextRunner fullyConfigured() {
        return contextRunner.withPropertyValues(
                "jwt.secret-key=" + VALID_SECRET,
                "jwt.access-token-validity=900000",
                "jwt.refresh-token-validity=1209600000",
                "platform.identity.product-claims.base-url=" + VALID_CLAIMS_URL,
                "platform.identity.product-claims.service-token=fail-fast-service-token");
    }

    @Test
    void composesTheAuthRuntimeWhenEveryRequiredSettingIsPresent() {
        fullyConfigured().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthService.class);
            assertThat(context).hasSingleBean(CRMIdentityProjectionPort.class);
        });
    }

    @Test
    void failsWhenTheJwtSecretIsMissing() {
        fullyConfigured()
                .withPropertyValues("jwt.secret-key=")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("secretKey"));
    }

    @Test
    void failsWhenTheJwtSecretIsTooShortToSign() {
        fullyConfigured()
                .withPropertyValues("jwt.secret-key=too-short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsWhenTheProductClaimsBaseUrlIsMissing() {
        // Without a configured claims source there is no CRMIdentityProjectionPort to inject.
        contextRunner
                .withPropertyValues(
                        "jwt.secret-key=" + VALID_SECRET,
                        "jwt.access-token-validity=900000",
                        "jwt.refresh-token-validity=1209600000",
                        "platform.identity.product-claims.service-token=fail-fast-service-token")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("CRMIdentityProjectionPort"));
    }

    @Test
    void failsWhenTheProductClaimsServiceTokenIsMissing() {
        fullyConfigured()
                .withPropertyValues("platform.identity.product-claims.service-token=")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("serviceToken"));
    }

    @Test
    void failsWhenTheProductClaimsBaseUrlIsNotAnAbsoluteHttpUrl() {
        fullyConfigured()
                .withPropertyValues("platform.identity.product-claims.base-url=carelog-be:8080")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("absolute http(s) URL"));
    }

    @Test
    void doesNotRequireKakaoCredentialsBecauseTheProductionProviderIsDeferred() {
        fullyConfigured().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().containsProperty("oauth.kakao.client-id")).isFalse();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubbedCollaborators {

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
