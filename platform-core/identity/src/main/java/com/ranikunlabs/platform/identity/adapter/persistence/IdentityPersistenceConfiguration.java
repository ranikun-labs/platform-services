package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    DataSource dataSource(IdentityRuntimeProperties properties, Environment environment) {
        validateRuntimeOnly(properties, environment);
        return dataSource(properties);
    }

    static void validateRuntimeOnly(IdentityRuntimeProperties properties, Environment environment) {
        if (!environment.getProperty("platform.identity.runtime-only", Boolean.class, false)) {
            return;
        }
        if (!Boolean.FALSE.equals(environment.getProperty("spring.flyway.enabled", Boolean.class))) {
            throw new IllegalStateException("Identity runtime-only mode requires spring.flyway.enabled=false");
        }
        if (properties.flywayDatasource() != null && properties.flywayDatasource().hasAnyValue()) {
            throw new IllegalStateException("Identity runtime-only mode forbids migration datasource credentials");
        }
    }

    DataSource dataSource(IdentityRuntimeProperties properties) {
        IdentityRuntimeProperties.Datasource datasource = properties.datasource();
        return DataSourceBuilder.create()
                .type(InstrumentedHikariDataSource.class)
                .url(datasource.url())
                .username(datasource.username())
                .password(datasource.password())
                .build();
    }

    @Bean
    FlywayConfigurationCustomizer identityFlywayLocation(
            IdentityRuntimeProperties properties,
            Environment environment) {
        return configuration -> {
            configuration
                    .locations("classpath:db/migration")
                    .schemas(IdentityDatabaseSchema.NAME)
                    .defaultSchema(IdentityDatabaseSchema.NAME)
                    // Schema provisioning belongs to the database owner. Flyway must not need
                    // CREATE privilege on the application database or create service schemas.
                    .createSchemas(false);

            IdentityRuntimeProperties.FlywayDatasource migrationDatasource = properties.flywayDatasource();
            if (migrationDatasource != null && migrationDatasource.hasAnyValue()) {
                if (!migrationDatasource.isComplete()) {
                    throw new IllegalStateException(
                            "Identity Flyway datasource requires URL, username, and password together");
                }
                configuration.dataSource(
                        migrationDatasource.url(),
                        migrationDatasource.username(),
                        migrationDatasource.password());
            } else if (isProduction(environment)) {
                throw new IllegalStateException(
                        "Identity Flyway datasource URL, username, and password are required in production");
            }
        };
    }

    private static boolean isProduction(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("prod", "production"))
                || isProductionMarker(environment.getProperty("PLATFORM_ENVIRONMENT"))
                || isProductionMarker(environment.getProperty("platform.metrics.environment"));
    }

    private static boolean isProductionMarker(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "prod".equalsIgnoreCase(normalized) || "production".equalsIgnoreCase(normalized);
    }

    @Bean
    FlywayMigrationStrategy identityFlywayMigrationStrategy(IdentityRuntimeProperties properties) {
        return flyway -> {
            new IdentityLegacySchemaGuard().migrate(flyway);
            IdentityRuntimeSchemaPrivileges.reconcile(
                    flyway.getConfiguration().getDataSource(), properties.datasource().username());
        };
    }

    @Bean
    HibernatePropertiesCustomizer identityHibernateSchemaValidation() {
        return properties -> properties.put("hibernate.hbm2ddl.auto", "validate");
    }
}
