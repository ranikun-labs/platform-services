package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.http.FinanceReferenceAuthConfiguration;
import com.ranikunlabs.platform.identity.adapter.http.FinanceReferenceAuthController;
import com.ranikunlabs.platform.identity.adapter.persistence.FinanceReferenceAccountJpaAdapter;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceEnrollmentPort;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtAccessTokenIssuer;
import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtProperties;
import com.ranikunlabs.platform.identity.application.FinanceReferenceAccessService;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.identity.finance-reference.enabled", havingValue = "true")
@EnableConfigurationProperties({FinanceReferenceProperties.class, FinanceReferenceJwtProperties.class})
@Import({
        FinanceReferenceAuthController.class,
        FinanceReferenceAuthConfiguration.class,
        IdentityCredentialAdapter.class,
        FinanceReferenceAccountJpaAdapter.class,
        FinanceReferenceSignupHttpConfiguration.class
})
public class FinanceReferenceIdentityConfiguration {

    @Bean
    Set<UUID> financeReferenceSelectedAccountIds(
            FinanceReferenceProperties feature,
            FinanceReferenceJwtProperties jwt,
            Environment environment
    ) {
        boolean sharedAuthEnabled = environment.getProperty("platform.identity.auth.enabled", Boolean.class, false);
        return FinanceReferenceConfigurationGuard.validate(feature, jwt, sharedAuthEnabled, environment);
    }

    @Bean
    Clock financeReferenceClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder financeReferencePasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    FinanceReferenceJwtAccessTokenIssuer financeReferenceJwtAccessTokenIssuer(
            FinanceReferenceJwtProperties properties,
            Clock financeReferenceClock
    ) {
        return new FinanceReferenceJwtAccessTokenIssuer(properties, financeReferenceClock);
    }

    @Bean
    FinanceReferenceAccessService financeReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> financeReferenceSelectedAccountIds,
            FinanceReferenceEnrollmentPort financeReferenceEnrollment,
            FinanceReferenceJwtAccessTokenIssuer tokenIssuer
    ) {
        return new FinanceReferenceAccessService(
                credentials, financeReferenceSelectedAccountIds, financeReferenceEnrollment, tokenIssuer);
    }
}
