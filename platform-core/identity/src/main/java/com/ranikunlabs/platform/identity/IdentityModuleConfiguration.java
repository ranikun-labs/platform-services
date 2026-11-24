package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityPersistenceConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityRuntimeProperties.class)
@Import({
        IdentityPersistenceConfiguration.class,
        IdentityAuthConfiguration.class,
        CarelogRegistrationCompositionConfiguration.class,
        FinanceReferenceIdentityConfiguration.class
})
public class IdentityModuleConfiguration {

    /**
     * The single credential encoder for every Identity composition (Carelog auth, Finance reference,
     * registration). Defined here, not in a composition, so no load order or conditional decides which
     * PasswordEncoder exists; that keeps bean resolution the same whichever compositions are enabled.
     */
    @Bean
    PasswordEncoder identityPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
