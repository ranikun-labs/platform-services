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
    public static final String DEV_PROFILE = "dev";
    public static final String ISSUER = "urn:ranikunlabs:identity:finance:reference";
    public static final String AUDIENCE = "finance-journal-reference";
    public static final int ACCESS_TTL_SECONDS = 300;
    public static final String DEV_DUAL_AUTH_PROPERTY =
            "platform.identity.finance-reference.dev-dual-auth.enabled";
    static final String SHARED_AUTH_PROPERTY = "platform.identity.auth.enabled";
    static final String SHARED_AUTH_REJECTION =
            "Finance identity reference cannot share the Carelog auth composition.";

    private FinanceReferenceConfigurationGuard() {
    }

    public static Set<UUID> validate(
            FinanceReferenceProperties feature,
            FinanceReferenceJwtProperties jwt,
            boolean signupEnabled,
            Environment environment
    ) {
        if (productionEnvironment(environment)) {
            throw new IllegalStateException("Finance identity reference cannot start with a production marker.");
        }
        if (!hasProfile(environment, PROFILE)) {
            throw new IllegalStateException("Finance identity reference requires its explicit profile.");
        }
        if (environment.getProperty(DEV_DUAL_AUTH_PROPERTY, Boolean.class, false) && !hasProfile(environment, DEV_PROFILE)) {
            throw new IllegalStateException(
                    "Finance identity reference dev dual-auth requires the dev profile.");
        }
        if (environment.getProperty(SHARED_AUTH_PROPERTY, Boolean.class, false)
                && !devDualAuthActive(environment)) {
            throw new IllegalStateException(SHARED_AUTH_REJECTION);
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

        List<String> configuredIds = feature.selectedAccountIds() == null ? List.of() : feature.selectedAccountIds();
        if (configuredIds.size() > 16) {
            throw new IllegalStateException("Finance identity reference requires 1–16 selected account IDs.");
        }
        if (configuredIds.isEmpty() && !zeroSelectedAccountsAllowed(signupEnabled, environment)) {
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

    /**
     * Explicit DEV-only opt-in for running Finance reference beside the Carelog auth composition.
     * Active only when the flag is true, the {@code dev} profile is present, and no production marker is set.
     */
    public static boolean devDualAuthActive(Environment environment) {
        return environment.getProperty(DEV_DUAL_AUTH_PROPERTY, Boolean.class, false)
                && hasProfile(environment, DEV_PROFILE)
                && !productionEnvironment(environment);
    }

    /**
     * DEV-only. Zero selected IDs is admitted only with signup AND the DEV dual-auth opt-in. This is the
     * fixture-free signup admission path: accounts enter through Finance enrollment instead of the allowlist.
     * It applies whether or not Carelog auth is also composed, so Finance-only use is intentionally allowed here.
     */
    public static boolean zeroSelectedAccountsAllowed(boolean signupEnabled, Environment environment) {
        return signupEnabled && devDualAuthActive(environment);
    }

    private static boolean productionEnvironment(Environment environment) {
        boolean productionProfile = Stream.of(environment.getActiveProfiles())
                .anyMatch(FinanceReferenceConfigurationGuard::isProductionValue);
        boolean productionMarker = Stream.of(
                environment.getProperty("PLATFORM_ENVIRONMENT"),
                environment.getProperty("platform.environment"),
                environment.getProperty("platform.metrics.environment")
        ).filter(value -> value != null).anyMatch(FinanceReferenceConfigurationGuard::isProductionValue);
        return productionProfile || productionMarker;
    }

    private static boolean hasProfile(Environment environment, String profile) {
        return List.of(environment.getActiveProfiles()).stream().anyMatch(profile::equalsIgnoreCase);
    }

    private static boolean isProductionValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
