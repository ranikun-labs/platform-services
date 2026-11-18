package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.token.FinanceReferenceJwtProperties;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class FinanceReferenceConfigurationGuard {

    public static final String PROFILE = "finance-reference";
    public static final String ISSUER = "urn:ranikunlabs:identity:finance:reference";
    public static final String AUDIENCE = "finance-journal-reference";
    public static final int ACCESS_TTL_SECONDS = 300;

    private FinanceReferenceConfigurationGuard() {
    }

    public static Set<UUID> validate(
            FinanceReferenceProperties feature,
            FinanceReferenceJwtProperties jwt,
            boolean sharedAuthEnabled,
            Environment environment
    ) {
        boolean productionProfile = Stream.of(environment.getActiveProfiles())
                .anyMatch(FinanceReferenceConfigurationGuard::isProductionValue);
        boolean productionMarker = Stream.of(
                environment.getProperty("PLATFORM_ENVIRONMENT"),
                environment.getProperty("platform.environment"),
                environment.getProperty("platform.metrics.environment")
        ).filter(value -> value != null).anyMatch(FinanceReferenceConfigurationGuard::isProductionValue);
        if (productionProfile || productionMarker) {
            throw new IllegalStateException("Finance identity reference cannot start with a production marker.");
        }
        if (!List.of(environment.getActiveProfiles()).stream().anyMatch(PROFILE::equalsIgnoreCase)) {
            throw new IllegalStateException("Finance identity reference requires its explicit profile.");
        }
        if (sharedAuthEnabled) {
            throw new IllegalStateException("Finance identity reference cannot share the Carelog auth composition.");
        }
        if (feature == null || !feature.enabled() || jwt == null
                || blank(jwt.signingKey()) || blank(jwt.issuer()) || blank(jwt.audience())
                || jwt.accessTokenTtlSeconds() == null) {
            throw new IllegalStateException("Finance identity reference configuration is incomplete.");
        }
        if (jwt.signingKey().getBytes(StandardCharsets.UTF_8).length < 32
                || !ISSUER.equals(jwt.issuer()) || !AUDIENCE.equals(jwt.audience())
                || jwt.accessTokenTtlSeconds() != ACCESS_TTL_SECONDS) {
            throw new IllegalStateException("Finance identity reference JWT settings do not match the fixture contract.");
        }

        List<String> configuredIds = feature.selectedAccountIds();
        if (configuredIds == null || configuredIds.isEmpty() || configuredIds.size() > 16) {
            throw new IllegalStateException("Finance identity reference requires 1–16 selected account IDs.");
        }
        Set<UUID> selected = new LinkedHashSet<>();
        for (String value : configuredIds) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Finance reference account IDs must be canonical UUIDs.");
            }
            try {
                UUID parsed = UUID.fromString(value);
                if (!parsed.toString().equals(value) || !selected.add(parsed)) {
                    throw new IllegalStateException("Finance reference account IDs must be unique canonical UUIDs.");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Finance reference account IDs must be canonical UUIDs.", exception);
            }
        }
        return Set.copyOf(selected);
    }

    private static boolean isProductionValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
