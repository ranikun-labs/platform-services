package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Expected Flyway history location and canonical migration forms for one inspected source.
 *
 * <p>The checked-in profiles carry the actual Flyway type and checksum for every SQL migration.
 * A three-argument migration expectation remains available for explicitly external profiles, but
 * those expectations deliberately do not claim checksum validation.
 */
public record HistoryProfile(
        String schema,
        String historyTable,
        List<MigrationExpectation> expectedMigrations,
        List<HistoryVariant> alternateVariants
) {

    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    public HistoryProfile(
            String schema,
            String historyTable,
            List<MigrationExpectation> expectedMigrations
    ) {
        this(schema, historyTable, expectedMigrations, List.of());
    }

    public HistoryProfile {
        schema = requireIdentifier(schema, "schema");
        historyTable = requireIdentifier(historyTable, "historyTable");
        expectedMigrations = copyMigrations(expectedMigrations, "expectedMigrations");
        alternateVariants = List.copyOf(
                Objects.requireNonNull(alternateVariants, "alternateVariants must not be null"));
        for (HistoryVariant variant : alternateVariants) {
            Objects.requireNonNull(variant, "alternateVariants must not contain null");
        }
    }

    /** Returns the primary form followed by any explicitly accepted alternate forms. */
    public List<HistoryVariant> acceptedVariants() {
        List<HistoryVariant> variants = new ArrayList<>(alternateVariants.size() + 1);
        variants.add(new HistoryVariant("PRIMARY", expectedMigrations));
        variants.addAll(alternateVariants);
        return List.copyOf(variants);
    }

    /** Exact history profile for the checked-in legacy public Identity migration line. */
    public static HistoryProfile platformLegacy() {
        return new HistoryProfile(
                "public",
                "flyway_schema_history",
                identityMigrations()
        );
    }

    /** Exact history profile for the checked-in canonical Identity schema. */
    public static HistoryProfile canonicalTarget() {
        return new HistoryProfile(
                "identity",
                "flyway_schema_history",
                identityMigrations()
        );
    }

    /**
     * Exact Flyway history line read from the Carelog source migration files.
     *
     * <p>Carelog has two supported histories: a fresh V1 SQL migration or a V1 baseline row when
     * baseline-on-migrate was used for a pre-existing database. No other baseline form is accepted.
     */
    public static HistoryProfile carelogLegacy() {
        List<MigrationExpectation> fresh = carelogFreshMigrations();
        List<MigrationExpectation> baselined = new ArrayList<>();
        baselined.add(MigrationExpectation.baseline("1"));
        baselined.addAll(fresh.subList(1, fresh.size()));
        return new HistoryProfile(
                "public",
                "flyway_schema_history",
                fresh,
                List.of(new HistoryVariant("CARELOG_BASELINED", baselined))
        );
    }

    /**
     * Creates an explicit history profile for a source whose migration files are owned elsewhere.
     * Callers must supply that source's observed migration truth; no Carelog lineage is guessed.
     */
    public static HistoryProfile external(String schema, List<MigrationExpectation> migrations) {
        return new HistoryProfile(schema, "flyway_schema_history", migrations);
    }

    private static List<MigrationExpectation> identityMigrations() {
        return List.of(
                MigrationExpectation.fromScriptName("V1__identity_schema.sql", -1454289175),
                MigrationExpectation.fromScriptName("V2__refresh_session_single_account.sql", 1506915702)
        );
    }

    private static List<MigrationExpectation> carelogFreshMigrations() {
        return List.of(
                MigrationExpectation.fromScriptName("V1__baseline_current_schema.sql", 1489460835),
                MigrationExpectation.fromScriptName("V2__identity_foundation.sql", -609239330),
                MigrationExpectation.fromScriptName("V3__backfill_identity_principals.sql", -1875117566),
                MigrationExpectation.fromScriptName("V4__identity_constraints.sql", -511745124),
                MigrationExpectation.fromScriptName("V5__refresh_token_account_id.sql", 1803198638),
                MigrationExpectation.fromScriptName("V6__product_client_registry.sql", -758176464),
                MigrationExpectation.fromScriptName("V7__customer_product_contract.sql", -577931420),
                MigrationExpectation.fromScriptName("V8__customer_events.sql", 801962331)
        );
    }

    private static List<MigrationExpectation> copyMigrations(
            List<MigrationExpectation> migrations,
            String field
    ) {
        List<MigrationExpectation> copy = List.copyOf(
                Objects.requireNonNull(migrations, field + " must not be null"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        Set<String> versions = new HashSet<>();
        for (MigrationExpectation migration : copy) {
            Objects.requireNonNull(migration, field + " must not contain null");
            if (!versions.add(migration.version())) {
                throw new IllegalArgumentException("duplicate expected migration version: "
                        + migration.version());
            }
        }
        return copy;
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(field + " must be a simple SQL identifier");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** One explicitly accepted ordered Flyway history form. */
    public record HistoryVariant(String name, List<MigrationExpectation> migrations) {

        public HistoryVariant {
            name = requireText(name, "name");
            migrations = copyMigrations(migrations, "migrations");
        }
    }

    /** One exact version/description/script/type/checksum tuple expected in Flyway history. */
    public record MigrationExpectation(
            String version,
            String description,
            String script,
            String type,
            Integer checksum,
            boolean checksumValidated
    ) {

        /** External-profile compatibility constructor; checksum validation is intentionally off. */
        public MigrationExpectation(String version, String description, String script) {
            this(version, description, script, "SQL", null, false);
        }

        /** Exact expected type and checksum constructor. A null checksum is an exact null policy. */
        public MigrationExpectation(
                String version,
                String description,
                String script,
                String type,
                Integer checksum
        ) {
            this(version, description, script, type, checksum, true);
        }

        public MigrationExpectation {
            version = requireText(version, "version");
            description = requireText(description, "description");
            script = requireText(script, "script");
            type = requireText(type, "type");
            if (script.contains("/") || script.contains("\\")) {
                throw new IllegalArgumentException("script must be a Flyway script name or marker");
            }
            if (!checksumValidated && checksum != null) {
                throw new IllegalArgumentException(
                        "an unvalidated checksum expectation must be null");
            }
        }

        public static MigrationExpectation fromScriptName(String script) {
            return parsedScript(script, null, false);
        }

        public static MigrationExpectation fromScriptName(String script, int checksum) {
            return parsedScript(script, checksum, true);
        }

        public static MigrationExpectation baseline(String version) {
            return new MigrationExpectation(
                    version,
                    "<< Flyway Baseline >>",
                    "<< Flyway Baseline >>",
                    "BASELINE",
                    null
            );
        }

        private static MigrationExpectation parsedScript(
                String script,
                Integer checksum,
                boolean checksumValidated
        ) {
            Objects.requireNonNull(script, "script must not be null");
            int separator = script.indexOf("__");
            int extension = script.endsWith(".sql") ? script.length() - ".sql".length() : -1;
            if (!script.startsWith("V") || separator <= 1 || extension <= separator + 2) {
                throw new IllegalArgumentException("not a versioned Flyway SQL script name: " + script);
            }
            String version = script.substring(1, separator);
            String description = script.substring(separator + 2, extension).replace('_', ' ');
            return new MigrationExpectation(
                    version, description, script, "SQL", checksum, checksumValidated);
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
