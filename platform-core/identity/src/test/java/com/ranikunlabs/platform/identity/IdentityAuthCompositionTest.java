package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.redis.RedisTokenBlacklistAdapter;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.TokenBlacklistPort;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    @Test
    void composesTheEnabledIdentityAuthBeanGraphWithoutExternalServices() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdentityAuthConfiguration.class);
            assertThat(context).hasSingleBean(IdentityJwtProperties.class);
            assertThat(context).hasSingleBean(JwtTokenProvider.class);
            assertThat(context).hasSingleBean(TokenProviderPort.class);
            assertThat(context).hasSingleBean(PasswordCredentialJpaRepository.class);
            assertThat(context).hasSingleBean(IdentityCredentialAdapter.class);
            assertThat(context).hasSingleBean(CredentialPort.class);
            assertThat(context).hasSingleBean(AccountStatusPort.class);
            assertThat(context).hasSingleBean(TokenSessionPort.class);
            assertThat(context).hasSingleBean(RedisTokenBlacklistAdapter.class);
            assertThat(context).hasSingleBean(TokenBlacklistPort.class);
            assertThat(context).hasSingleBean(AuthTokenIssuanceService.class);
            assertThat(context).hasSingleBean(AuthServiceImpl.class);
            assertThat(context).hasSingleBean(AuthService.class);
            assertThat(context).hasSingleBean(CRMIdentityProjectionPort.class);

            assertThat(context.getBean(TokenProviderPort.class))
                    .isSameAs(context.getBean(JwtTokenProvider.class));
            assertThat(context.getBean(CredentialPort.class))
                    .isSameAs(context.getBean(IdentityCredentialAdapter.class));
            assertThat(context.getBean(AuthService.class))
                    .isSameAs(context.getBean(AuthServiceImpl.class));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthCompositionTestConfiguration {

        @Bean
        PasswordCredentialJpaRepository passwordCredentialJpaRepository() {
            return mock(PasswordCredentialJpaRepository.class);
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
        RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
