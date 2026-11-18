package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityPersistenceConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityRuntimeProperties.class)
@Import({
        IdentityPersistenceConfiguration.class,
        IdentityAuthConfiguration.class,
        FinanceReferenceIdentityConfiguration.class
})
public class IdentityModuleConfiguration {
}
