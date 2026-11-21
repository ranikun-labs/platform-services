package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.enrollment.CarelogEnrollmentClientConfiguration;
import com.ranikunlabs.platform.identity.adapter.persistence.CarelogRegistrationStore;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.CarelogEnrollment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Locale;

/** Explicit DEV composition; no fallback stores or synthetic enrollment clients. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.identity.carelog-registration.enabled", havingValue = "true")
@Import(CarelogEnrollmentClientConfiguration.class)
public class CarelogRegistrationCompositionConfiguration {
    @Bean
    CarelogRegistrationStore carelogRegistrationStore(Environment environment,
            PlatformAccountJpaRepository accounts, PasswordCredentialJpaRepository credentials,
            PasswordEncoder passwords, DataSource dataSource, JpaTransactionManager transactionManager) {
        requireDevComposition(environment);
        if (transactionManager.getDataSource() != dataSource) {
            throw new IllegalStateException("Carelog registration requires the Identity JPA datasource and transaction manager");
        }
        return new CarelogRegistrationStore(accounts, credentials, passwords, new JdbcTemplate(dataSource), transactionManager);
    }

    @Bean
    CarelogRegistrationCoordinator carelogRegistrationCoordinator(CarelogRegistrationStore store,
                                                                    CarelogEnrollment enrollment) {
        return new CarelogRegistrationCoordinator(store, enrollment);
    }

    private static void requireDevComposition(Environment environment) {
        boolean dev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(CarelogRegistrationCompositionConfiguration::production)
                || production(environment.getProperty("PLATFORM_ENVIRONMENT"))
                || production(environment.getProperty("platform.environment"))
                || production(environment.getProperty("platform.metrics.environment"));
        if (!dev || production
                || !environment.getProperty("platform.identity.auth.enabled", Boolean.class, false)
                || !environment.getProperty("platform.identity.persistence.enabled", Boolean.class, true)
                || !environment.getProperty("platform.identity.carelog-enrollment.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Carelog registration requires DEV, Identity auth and persistence, and real enrollment");
        }
    }

    private static boolean production(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("production") || normalized.equals("prod");
    }
}
