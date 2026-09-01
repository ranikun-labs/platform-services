package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthClientConfig;
import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProviderAdapter;
import com.ranikunlabs.platform.identity.adapter.productclaims.CarelogProductClaimsClientConfiguration;
import com.ranikunlabs.platform.identity.adapter.productclaims.CarelogProductClaimsHttpAdapter;
import com.ranikunlabs.platform.identity.adapter.productclaims.CarelogProductClaimsProperties;
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
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CarelogProductClaimsCompositionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityModuleConfiguration.class, Dependencies.class)
            .withPropertyValues(
                    "platform.identity.auth.enabled=true",
                    "platform.identity.persistence.enabled=false",
                    "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                    "platform.identity.datasource.username=identity",
                    "platform.identity.datasource.password=provided-at-runtime",
                    "platform.identity.redis-host=redis",
                    "platform.identity.redis-port=6379",
                    "platform.identity.redis-key-prefix=platform:identity:",
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
                    "platform.identity.product-claims.enabled=true",
                    "platform.identity.product-claims.base-url=http://carelog.example.test:8080",
                    "platform.identity.product-claims.service-secret=platform-identity-service-secret-0123456789");

    @Test
    void enabledAuthCompositionUsesExactlyOneProductionProductClaimsPort() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(CarelogProductClaimsClientConfiguration.class)
                    .hasSingleBean(CarelogProductClaimsProperties.class)
                    .hasSingleBean(CRMIdentityProjectionPort.class);
            assertThat(context).hasSingleBean(KakaoOAuthClientConfig.class)
                    .hasSingleBean(KakaoOAuthProviderAdapter.class);
            assertThat(context.getBeansOfType(RestClient.class)).hasSize(2);
            assertThat(context.getBean(CRMIdentityProjectionPort.class))
                    .isInstanceOf(CarelogProductClaimsHttpAdapter.class);
            assertThat(context.getBeansOfType(CRMIdentityProjectionPort.class))
                    .containsOnlyKeys("carelogProductClaimsProjectionPort");
        });
    }

    @Test
    void enabledAuthWithoutProductClaimsFailsWithAClearCompositionRequirement() {
        runner.withPropertyValues("platform.identity.product-claims.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "Identity Auth requires the Product claims integration when no CRMIdentityProjectionPort is configured; "
                                            + "set platform.identity.product-claims.enabled=true or provide another valid provider");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {

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
