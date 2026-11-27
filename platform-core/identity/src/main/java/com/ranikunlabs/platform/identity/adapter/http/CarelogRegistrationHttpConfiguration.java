package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;

/** Internal DEV-only opt-in. Missing coordinator or a production marker fails startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.identity.carelog-registration.enabled", havingValue = "true")
@Import(CarelogRegistrationController.class)
class CarelogRegistrationHttpConfiguration {
    @Bean
    CarelogRegistrationEnabled carelogRegistrationEnabled(Environment environment,
                                                             CarelogRegistrationCoordinator coordinator) {
        String deployment = environment.getProperty("PLATFORM_ENVIRONMENT");
        String alternate = environment.getProperty("platform.environment");
        boolean explicitDev = isDev(deployment) || isDev(alternate);
        boolean conflictingEnvironment = (deployment != null && !isDev(deployment))
                || (alternate != null && !isDev(alternate));
        boolean dev = explicitDev || Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(CarelogRegistrationHttpConfiguration::production)
                || production(environment.getProperty("PLATFORM_ENVIRONMENT"))
                || production(environment.getProperty("platform.environment"))
                || production(environment.getProperty("platform.metrics.environment"));
        if (!dev || production || conflictingEnvironment) throw new IllegalStateException("Carelog registration requires an explicit nonproduction dev environment or profile");
        return new CarelogRegistrationEnabled();
    }

    private static boolean isDev(String value) {
        return value != null && "dev".equalsIgnoreCase(value.trim());
    }

    private static boolean production(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("production") || normalized.equals("prod");
    }
}

final class CarelogRegistrationEnabled { }
