package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.auditing.DateTimeProvider;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

/**
 * Identity's relational persistence composition. The configuration is imported by the sole Platform
 * Core host; it does not create a second application or deployment unit.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "platform.identity.persistence.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(IdentityRuntimeProperties.class)
@EntityScan(basePackageClasses = PlatformAccountEntity.class)
@EnableJpaRepositories(basePackageClasses = PlatformAccountJpaRepository.class)
@EnableJpaAuditing(dateTimeProviderRef = "identityAuditingDateTimeProvider")
@Import({
        ExternalIdentityLookupJpaAdapter.class,
        ProductClientReaderJpaAdapter.class,
        TokenSessionJpaAdapter.class,
        PlatformAccountStatusJpaAdapter.class
})
public class IdentityPersistenceConfiguration {

    @Bean(name = "identityAuditingDateTimeProvider")
    DateTimeProvider identityAuditingDateTimeProvider() {
        return () -> Optional.<TemporalAccessor>of(OffsetDateTime.now());
    }

    @Bean
    DataSource dataSource(IdentityRuntimeProperties properties) {
        IdentityRuntimeProperties.Datasource datasource = properties.datasource();
        return DataSourceBuilder.create()
                .url(datasource.url())
                .username(datasource.username())
                .password(datasource.password())
                .build();
    }

    @Bean
    FlywayConfigurationCustomizer identityFlywayLocation() {
        return configuration -> configuration.locations("classpath:db/migration");
    }

    @Bean
    HibernatePropertiesCustomizer identityHibernateSchemaValidation() {
        return properties -> properties.put("hibernate.hbm2ddl.auto", "validate");
    }
}
