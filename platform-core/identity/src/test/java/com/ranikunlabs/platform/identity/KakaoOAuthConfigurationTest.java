package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthApiClient;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthClientConfig;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthErrorMapper;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProperties;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProviderAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoOAuthConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void noKakaoSettingsKeepProviderAdapterOutOfTheContext() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(KakaoOAuthProviderAdapter.class)
                .doesNotHaveBean(KakaoOAuthApiClient.class));
    }

    @Test
    void completeLegacySettingsRegisterTheProviderAdapter() {
        configured(runner).run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(KakaoOAuthProviderAdapter.class)
                .hasSingleBean(KakaoOAuthApiClient.class));
    }

    @Test
    void partialSettingsFailClosedWithoutAcceptingAProviderHalfConfiguration() {
        runner.withPropertyValues("carelog.auth.oauth.kakao.client-id=id")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void optionalClientSecretAloneFailsClosedWithoutLeakingItsValue() {
        runner.withPropertyValues("carelog.auth.oauth.kakao.client-secret=sensitive-secret-value")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString())
                            .doesNotContain("sensitive-secret-value");
                });
    }

    @Test
    void invalidEndpointIsRejectedByValidatedProperties() {
        configured(runner)
                .withPropertyValues("carelog.auth.oauth.kakao.token-uri=not-a-uri")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultTimeoutsRemainBoundedAndClientSecretRemainsOptional() {
        configured(runner).run(context -> {
            assertThat(context).hasNotFailed();
            KakaoOAuthProperties properties = context.getBean(KakaoOAuthProperties.class);
            assertThat(properties.getClientSecret()).isNull();
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "carelog.auth.oauth.kakao.connect-timeout=0s",
            "carelog.auth.oauth.kakao.connect-timeout=-1s",
            "carelog.auth.oauth.kakao.read-timeout=0s",
            "carelog.auth.oauth.kakao.read-timeout=-1s"
    })
    void zeroOrNegativeTimeoutFailsClosed(String timeoutProperty) {
        configured(runner)
                .withPropertyValues(timeoutProperty)
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner configured(ApplicationContextRunner value) {
        return value.withPropertyValues(
                "carelog.auth.oauth.kakao.client-id=id",
                "carelog.auth.oauth.kakao.authorization-uri=https://authorize.example",
                "carelog.auth.oauth.kakao.token-uri=https://token.example",
                "carelog.auth.oauth.kakao.user-info-uri=https://user.example",
                "oauth.redirect-uris.kakao.WEB=https://web.example/callback",
                "oauth.redirect-uris.kakao.MOBILE=carelog://oauth/callback"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @Import({KakaoOAuthClientConfig.class, KakaoOAuthProviderAdapter.class})
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
