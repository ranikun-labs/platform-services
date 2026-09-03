package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.adapter.oauth.kakao.KakaoOAuthProperties;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderCallbackProperties;
import com.ranikunlabs.platform.identity.application.oauth.OAuthRedirectUriResolver;
import com.ranikunlabs.platform.identity.application.oauth.ReturnToValidator;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves deployment environment names continue to map through Platform Core application.yml. */
@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "PLATFORM_CORE_PORT=0",
                "PLATFORM_IDENTITY_DATASOURCE_URL=jdbc:postgresql://test-only-host:5432/test-only",
                "PLATFORM_IDENTITY_DATASOURCE_USERNAME=test-only-identity",
                "PLATFORM_IDENTITY_DATASOURCE_PASSWORD=test-only-password",
                "PLATFORM_IDENTITY_REDIS_HOST=test-only-redis",
                "PLATFORM_IDENTITY_REDIS_PORT=6379",
                "PLATFORM_IDENTITY_REDIS_KEY_PREFIX=test-only:identity:",
                "PLATFORM_IDENTITY_AUTH_ENABLED=false",
                "platform.identity.persistence.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "PLATFORM_IDENTITY_JWT_SECRET_KEY=test-only-jwt-secret-012345678901234567890123",
                "PLATFORM_IDENTITY_JWT_ACCESS_TOKEN_VALIDITY=900000",
                "PLATFORM_IDENTITY_JWT_REFRESH_TOKEN_VALIDITY=1209600000",
                "PLATFORM_IDENTITY_KAKAO_CLIENT_ID=test-only-kakao-client",
                "PLATFORM_IDENTITY_KAKAO_CLIENT_SECRET=test-only-kakao-secret",
                "PLATFORM_IDENTITY_KAKAO_AUTHORIZATION_URI=https://kauth.example.test/oauth/authorize",
                "PLATFORM_IDENTITY_KAKAO_TOKEN_URI=https://kapi.example.test/oauth/token",
                "PLATFORM_IDENTITY_KAKAO_USER_INFO_URI=https://kapi.example.test/v2/user/me",
                "PLATFORM_IDENTITY_OAUTH_KAKAO_WEB_REDIRECT_URI=https://identity.example.test/oauth/kakao/web",
                "PLATFORM_IDENTITY_OAUTH_KAKAO_MOBILE_REDIRECT_URI=identity://oauth/kakao/mobile",
                "PLATFORM_IDENTITY_OAUTH_ALLOWED_RETURN_ORIGINS=https://identity.example.test"
        })
@Import(PlatformIdentityEnvironmentPropertyMappingTest.MappingTestConfiguration.class)
class PlatformIdentityEnvironmentPropertyMappingTest {

    @Autowired
    private IdentityJwtProperties jwtProperties;

    @Autowired
    private KakaoOAuthProperties kakaoOAuthProperties;

    @Autowired
    private OAuthRedirectUriResolver redirectUriResolver;

    @Autowired
    private ReturnToValidator returnToValidator;

    @Autowired
    private Environment environment;

    @Test
    void bindsPlatformIdentityEnvironmentNamesToEachAuthPropertyFamily() {
        assertThat(jwtProperties.secretKey())
                .isEqualTo("test-only-jwt-secret-012345678901234567890123");
        assertThat(jwtProperties.accessTokenValidity()).isEqualTo(900000);
        assertThat(jwtProperties.refreshTokenValidity()).isEqualTo(1209600000);

        assertThat(kakaoOAuthProperties.getClientId()).isEqualTo("test-only-kakao-client");
        assertThat(kakaoOAuthProperties.getClientSecret()).isEqualTo("test-only-kakao-secret");
        assertThat(kakaoOAuthProperties.getAuthorizationUri())
                .isEqualTo("https://kauth.example.test/oauth/authorize");
        assertThat(kakaoOAuthProperties.getTokenUri())
                .isEqualTo("https://kapi.example.test/oauth/token");
        assertThat(kakaoOAuthProperties.getUserInfoUri())
                .isEqualTo("https://kapi.example.test/v2/user/me");

        assertThat(redirectUriResolver.resolve(
                "kakao",
                new RegisteredProductClient(
                        "carelog-web", Product.CARELOG, ProductClientChannel.WEB)))
                .isEqualTo(URI.create("https://identity.example.test/oauth/kakao/web"));
        assertThat(redirectUriResolver.resolve(
                        "kakao",
                        new RegisteredProductClient(
                                "carelog-mobile", Product.CARELOG, ProductClientChannel.MOBILE)))
                .isEqualTo(URI.create("identity://oauth/kakao/mobile"));
        assertThat(environment.getProperty("oauth.redirect-uris.kakao.WEB"))
                .isEqualTo("https://identity.example.test/oauth/kakao/web");
        assertThat(returnToValidator.validate("https://identity.example.test/home"))
                .isEqualTo("https://identity.example.test/home");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            IdentityJwtProperties.class,
            KakaoOAuthProperties.class,
            OAuthProviderCallbackProperties.class
    })
    static class MappingTestConfiguration {

        @Bean
        OAuthRedirectUriResolver redirectUriResolver(
                OAuthProviderCallbackProperties callbackProperties,
                Environment environment) {
            return new OAuthRedirectUriResolver(callbackProperties, environment);
        }

        @Bean
        ReturnToValidator returnToValidator(Environment environment) {
            return new ReturnToValidator(environment);
        }
    }
}
