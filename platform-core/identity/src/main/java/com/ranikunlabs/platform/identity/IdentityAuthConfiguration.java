package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.http.IdentityHttpConfiguration;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.PasswordCredentialUpdateJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.productclaims.CarelogProductClaimsClientConfiguration;
import com.ranikunlabs.platform.identity.adapter.redis.IdentityRedisConfiguration;
import com.ranikunlabs.platform.identity.adapter.redis.RedisTokenBlacklistAdapter;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

/**
 * Optional runtime composition for the extracted authentication slice.
 *
 * <p>The explicit opt-in keeps the foundation host/readiness mode bootable while Identity Auth,
 * OAuth, and HTTP runtime composition remain disabled by default. Enabling
 * {@code platform.identity.auth.enabled=true} composes those runtimes and requires the canonical
 * {@code jwt.*} values. When the opt-in Product claims settings are enabled, the composition
 * includes the Platform-owned HTTP adapter for the Carelog-owned claims boundary. Product claims
 * meaning and Carelog traffic cutover remain outside Shared Identity.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.identity.auth.enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityJwtProperties.class)
@Import({
        IdentityCredentialAdapter.class,
        IdentityAccountRegistrationJpaAdapter.class,
        PasswordCredentialUpdateJpaAdapter.class,
        RedisTokenBlacklistAdapter.class,
        IdentityRedisConfiguration.class,
        AuthTokenIssuanceService.class,
        AuthServiceImpl.class,
        CarelogProductClaimsClientConfiguration.class,
        IdentityAuthConfiguration.IdentityProductClaimsRequirementConfiguration.class,
        IdentityOAuthConfiguration.class,
        IdentityHttpConfiguration.class
})
public class IdentityAuthConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock identityAuthClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    JwtTokenProvider jwtTokenProvider(IdentityJwtProperties properties, Clock clock) {
        return new JwtTokenProvider(
                properties.secretKey(),
                properties.accessTokenValidity(),
                properties.refreshTokenValidity(),
                clock
        );
    }

    /** Fails startup clearly when Auth is enabled without a claims provider. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            name = "platform.identity.product-claims.enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    static class IdentityProductClaimsRequirementConfiguration {

        @Bean
        static BeanFactoryPostProcessor identityProductClaimsCompositionGuard() {
            return beanFactory -> {
                if (beanFactory.getBeanNamesForType(CRMIdentityProjectionPort.class, true, false).length == 0) {
                    throw new IllegalStateException(
                            "Identity Auth requires the Product claims integration when no CRMIdentityProjectionPort is configured; "
                                    + "set platform.identity.product-claims.enabled=true or provide another valid provider");
                }
            };
        }
    }
}
