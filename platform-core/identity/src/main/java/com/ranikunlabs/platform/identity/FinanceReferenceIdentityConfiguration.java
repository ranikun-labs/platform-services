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
        return FinanceReferenceConfigurationGuard.validate(feature, jwt, signupEnabled(environment), environment);
    }

    /**
     * Owns its Clock inline instead of exposing a Clock bean, so the Carelog identityAuthClock stays the
     * only Clock bean regardless of which compositions load. The token issuer only needs epoch time.
     */
    @Bean
    FinanceReferenceJwtAccessTokenIssuer financeReferenceJwtAccessTokenIssuer(
            FinanceReferenceJwtProperties properties
    ) {
        return new FinanceReferenceJwtAccessTokenIssuer(properties, Clock.systemUTC());
    }

    @Bean
    FinanceReferenceAccessService financeReferenceAccessService(
            CredentialPort credentials,
            Set<UUID> financeReferenceSelectedAccountIds,
            FinanceReferenceEnrollmentPort financeReferenceEnrollment,
            FinanceReferenceJwtAccessTokenIssuer tokenIssuer,
            Environment environment
    ) {
        return new FinanceReferenceAccessService(
                credentials, financeReferenceSelectedAccountIds, financeReferenceEnrollment, tokenIssuer,
                FinanceReferenceConfigurationGuard.zeroSelectedAccountsAllowed(signupEnabled(environment), environment));
    }

    /** Same property that gates FinanceReferenceSignupHttpConfiguration, so the two cannot disagree. */
    private static boolean signupEnabled(Environment environment) {
        return environment.getProperty("platform.identity.finance-reference.signup.enabled", Boolean.class, false);
    }
}
