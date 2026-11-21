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
        boolean dev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(CarelogRegistrationHttpConfiguration::production)
                || production(environment.getProperty("PLATFORM_ENVIRONMENT"))
                || production(environment.getProperty("platform.environment"))
                || production(environment.getProperty("platform.metrics.environment"));
        if (!dev || production) throw new IllegalStateException("Carelog registration requires an explicit nonproduction dev profile");
        return new CarelogRegistrationEnabled();
    }

    private static boolean production(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("production") || normalized.equals("prod");
    }
}

final class CarelogRegistrationEnabled { }
