package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.PasswordCredentialUpdateJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.redis.IdentityRedisConfiguration;
import com.ranikunlabs.platform.identity.adapter.redis.RedisTokenBlacklistAdapter;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.IdentityJwtProperties;
import com.ranikunlabs.platform.identity.adapter.token.JwtTokenProvider;
import com.ranikunlabs.platform.identity.application.AuthServiceImpl;
import com.ranikunlabs.platform.identity.application.AuthTokenIssuanceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

/**
 * Optional runtime composition for the extracted authentication slice.
 *
 * <p>The explicit opt-in keeps the RPL-105 host/readiness foundation bootable until the later
 * HTTP compatibility and Product claims adapter slices are connected. Enabling this configuration
 * requires the canonical {@code jwt.*} values and a Product-owned CRM claims port bean.
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
        AuthServiceImpl.class
})
public class IdentityAuthConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock identityAuthClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    PasswordEncoder identityPasswordEncoder() {
        return new BCryptPasswordEncoder();
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
}
