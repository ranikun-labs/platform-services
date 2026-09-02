package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderRegistry;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Proves the Kakao provider is composed only when it is genuinely configured.
 *
 * <p>No test here supplies or requires a real Kakao credential; the values are markers. Absent Kakao
 * configuration must leave the host bootable, and incomplete configuration must stop startup rather
 * than register a provider that cannot complete a real login.
 */
class KakaoOAuthConfigurationTest {

    private static final String CLIENT_ID = "configuration-test-client-id";
    private static final String CLIENT_SECRET = "configuration-test-client-secret";

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
                    "platform.identity.redis-key-prefix=platform:identity:",
                    "jwt.secret-key=kakao-configuration-test-secret-key-0123456789",
                    "jwt.access-token-validity=900000",
                    "jwt.refresh-token-validity=1209600000");

    @Test
    void composesWithoutAnyKakaoConfigurationAndReportsKakaoAsUnsupported() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OAuthProviderPort.class);
            // No production stub stands in for the real provider.
            assertThatThrownBy(() -> context.getBean(OAuthProviderRegistry.class).resolve("kakao"))
                    .isInstanceOfSatisfying(OAuthAuthorizationRequestException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(
                                    OAuthAuthorizationRequestException.Reason.UNSUPPORTED_OAUTH_PROVIDER));
        });
    }

    @Test
    void registersTheRealKakaoProviderWhenConfigured() {
        configured().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OAuthProviderPort.class);
            OAuthProviderPort provider = context.getBean(OAuthProviderRegistry.class).resolve("kakao");
            assertThat(provider.getClass().getSimpleName()).isEqualTo("KakaoOAuthProviderAdapter");
            assertThat(provider.supportsPkce()).isFalse();
            assertThat(provider.requiresNonce()).isFalse();
        });
    }

    /**
     * Any Kakao property is intent to run Kakao. Leaving one of these to disable the integration by
     * accident would turn a typo into a silently missing login, so each must stop startup rather
     * than fall back to "Kakao not configured".
     */
    @Test
    void failsStartupForEveryPartialKakaoConfiguration() {
        assertPartialConfigurationFails("oauth.kakao.client-secret=" + CLIENT_SECRET);
        assertPartialConfigurationFails("oauth.kakao.client-secret-enabled=false");
        assertPartialConfigurationFails("oauth.kakao.authorization-uri=https://kauth.kakao.com/oauth/authorize");
        assertPartialConfigurationFails("oauth.kakao.token-uri=https://kauth.kakao.com/oauth/token");
        assertPartialConfigurationFails("oauth.kakao.user-info-uri=https://kapi.kakao.com/v2/user/me");
        assertPartialConfigurationFails("oauth.kakao.connect-timeout=2s");
        assertPartialConfigurationFails("oauth.kakao.read-timeout=3s");
    }

    /**
     * The module's own endpoint and timeout defaults must not make an unconfigured deployment look
     * operator-configured, so the absence check reads externally supplied properties only.
     */
    @Test
    void treatsThisModulesOwnDefaultsAsAbsenceRatherThanConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OAuthProviderPort.class);
        });
    }

    @Test
    void failsWhenTheClientSecretIsMissingWhileTheApplicationRequiresOne() {
        contextRunner
                .withPropertyValues("oauth.kakao.client-id=" + CLIENT_ID)
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("oauth.kakao.client-secret"));
    }

    @Test
    void composesWithoutASecretOnlyWhenTheApplicationHasClientSecretTurnedOff() {
        contextRunner
                .withPropertyValues(
                        "oauth.kakao.client-id=" + CLIENT_ID,
                        "oauth.kakao.client-secret-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OAuthProviderPort.class);
                });
    }

    /**
     * A blank client id is a misconfiguration, not an opt-out: the operator set the key intending
     * Kakao to work. It stops startup instead of quietly composing a provider that would call Kakao
     * with no application identity, and instead of silently degrading to "Kakao not configured".
     */
    @Test
    void failsWhenTheClientIdIsBlank() {
        contextRunner
                .withPropertyValues(
                        "oauth.kakao.client-id=",
                        "oauth.kakao.client-secret=" + CLIENT_SECRET)
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("clientId"));
    }

    @Test
    void failsWhenAnEndpointIsNotAnAbsoluteHttpUri() {
        configured()
                .withPropertyValues("oauth.kakao.token-uri=kauth.kakao.com/oauth/token")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("absolute http(s) URL"));
    }

    @Test
    void failsWhenATimeoutIsNotPositive() {
        configured()
                .withPropertyValues("oauth.kakao.connect-timeout=0s")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("positive duration"));

        configured()
                .withPropertyValues("oauth.kakao.read-timeout=-1s")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("positive duration"));
    }

    @Test
    void doesNotIntroduceFinanceProductClientsInThisSlice() {
        configured().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().containsProperty("oauth.kakao.finance-client-id"))
                    .isFalse();
        });
    }

    private void assertPartialConfigurationFails(String property) {
        contextRunner.withPropertyValues(property).run(context ->
                assertThat(context).as("partial configuration %s", property).hasFailed());
    }

    private ApplicationContextRunner configured() {
        return contextRunner.withPropertyValues(
                "oauth.kakao.client-id=" + CLIENT_ID,
                "oauth.kakao.client-secret=" + CLIENT_SECRET);
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
