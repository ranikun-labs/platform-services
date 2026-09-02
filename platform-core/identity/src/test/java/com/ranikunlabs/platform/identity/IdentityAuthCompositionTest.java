package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.http.IdentityAuthController;
import com.ranikunlabs.platform.identity.adapter.http.IdentityAuthExceptionHandler;
import com.ranikunlabs.platform.identity.adapter.http.security.IdentityAuthenticationEntryPoint;
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
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProductClientCompatibilityResolver;
import com.ranikunlabs.platform.identity.application.oauth.OAuthProviderRegistry;
import com.ranikunlabs.platform.identity.application.oauth.OAuthRedirectUriResolver;
import com.ranikunlabs.platform.identity.application.oauth.ReturnToValidator;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.PasswordCredentialUpdatePort;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import com.ranikunlabs.platform.identity.adapter.redis.RedisOAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdentityAuthCompositionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityModuleConfiguration.class, AuthCompositionTestConfiguration.class)
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
                    "jwt.refresh-token-validity=1209600000");

    /** Same module composition hosted in a Servlet context, where the HTTP boundary applies. */
    private final WebApplicationContextRunner servletContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(IdentityModuleConfiguration.class, AuthCompositionTestConfiguration.class)
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
                    "jwt.refresh-token-validity=1209600000");

    @Test
    void composesTheEnabledIdentityAuthBeanGraphWithoutExternalServices() {
        contextRunner.run(context -> {
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
            assertThat(context).hasSingleBean(OAuthProviderRegistry.class);
            assertThat(context).hasSingleBean(OAuthRedirectUriResolver.class);
            assertThat(context).hasSingleBean(OAuthProductClientCompatibilityResolver.class);
            assertThat(context).hasSingleBean(ReturnToValidator.class);
            assertThat(context).hasSingleBean(RedisOAuthStateStore.class);
            assertThat(context).hasSingleBean(OAuthStateStore.class);
            assertThat(context).hasSingleBean(OAuthAuthorizationService.class);
            assertThat(context).hasSingleBean(OAuthLoginService.class);

            assertThat(context.getBean(OAuthStateStore.class))
                    .isSameAs(context.getBean(RedisOAuthStateStore.class));
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
    void doesNotComposeTheHttpBoundaryOutsideAServletContext() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdentityAuthController.class);
            assertThat(context).doesNotHaveBean(IdentityAuthExceptionHandler.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test
    void composesTheAuthHttpBoundaryAndItsNarrowSecurityChainInAServletContext() {
        servletContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdentityAuthController.class);
            assertThat(context).hasSingleBean(IdentityAuthExceptionHandler.class);
            assertThat(context).hasSingleBean(IdentityAuthenticationEntryPoint.class);
            // Exactly one chain: the Identity Auth namespace boundary, with no host-wide chain.
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasSingleBean(AuthService.class);
            assertThat(context).hasSingleBean(OAuthAuthorizationService.class);
            assertThat(context).hasSingleBean(OAuthLoginService.class);
        });
    }

    @Test
    void keepsTheAuthNamespaceClosedWhenTheIdentityAuthRuntimeIsDisabled() {
        servletContextRunner
                .withPropertyValues("platform.identity.auth.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdentityAuthController.class);
                    // The namespace chain still exists, so foundation mode does not leave
                    // /api/v1/auth/** open.
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                });
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
