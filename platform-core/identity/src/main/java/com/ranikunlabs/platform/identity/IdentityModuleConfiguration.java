package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.http.security.IdentityAuthSecurityConfiguration;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityPersistenceConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The Identity Auth namespace security chain is composed here rather than under
 * {@link IdentityAuthConfiguration} so that the {@code /api/v1/auth/**} boundary stays closed in
 * foundation mode too, and so hosting Identity never widens or narrows host-wide security.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityRuntimeProperties.class)
@Import({
        IdentityPersistenceConfiguration.class,
        IdentityAuthConfiguration.class,
        IdentityAuthSecurityConfiguration.class
})
public class IdentityModuleConfiguration {
}
