package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Expected Flyway history location and exact migration scripts for one inspected source.
 *
 * <p>Checksums are intentionally not part of the expected profile. The inspector reports the
 * stored checksum, but this slice does not guess a checksum or reinterpret a source's history.
 */
public record HistoryProfile(
        String schema,
        String historyTable,
        List<MigrationExpectation> expectedMigrations
) {

    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    public HistoryProfile {
        schema = requireIdentifier(schema, "schema");
        historyTable = requireIdentifier(historyTable, "historyTable");
        expectedMigrations = List.copyOf(
                Objects.requireNonNull(expectedMigrations, "expectedMigrations must not be null"));
        if (expectedMigrations.isEmpty()) {
            throw new IllegalArgumentException("expectedMigrations must not be empty");
        }
        Set<String> versions = new HashSet<>();
        for (MigrationExpectation migration : expectedMigrations) {
            if (!versions.add(migration.version())) {
                throw new IllegalArgumentException("duplicate expected migration version: " + migration.version());
            }
        }
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

    /** Exact Flyway history line read from the Carelog source migration files. */
    public static HistoryProfile carelogLegacy() {
        return new HistoryProfile(
                "public",
                "flyway_schema_history",
                List.of(
                        MigrationExpectation.fromScriptName("V1__baseline_current_schema.sql"),
                        MigrationExpectation.fromScriptName("V2__identity_foundation.sql"),
                        MigrationExpectation.fromScriptName("V3__backfill_identity_principals.sql"),
                        MigrationExpectation.fromScriptName("V4__identity_constraints.sql"),
                        MigrationExpectation.fromScriptName("V5__refresh_token_account_id.sql"),
                        MigrationExpectation.fromScriptName("V6__product_client_registry.sql"),
                        MigrationExpectation.fromScriptName("V7__customer_product_contract.sql"),
                        MigrationExpectation.fromScriptName("V8__customer_events.sql")
                )
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
                MigrationExpectation.fromScriptName("V1__identity_schema.sql"),
                MigrationExpectation.fromScriptName("V2__refresh_session_single_account.sql")
        );
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(field + " must be a simple SQL identifier");
        }
        return value;
    }

    /** One exact version/description/script tuple expected in Flyway history. */
    public record MigrationExpectation(String version, String description, String script) {

        public MigrationExpectation {
            version = requireText(version, "version");
            description = requireText(description, "description");
            script = requireText(script, "script");
            if (script.contains("/") || script.contains("\\")) {
                throw new IllegalArgumentException("script must be a Flyway script name");
            }
        }

        public static MigrationExpectation fromScriptName(String script) {
            Objects.requireNonNull(script, "script must not be null");
            int separator = script.indexOf("__");
            int extension = script.endsWith(".sql") ? script.length() - ".sql".length() : -1;
            if (!script.startsWith("V") || separator <= 1 || extension <= separator + 2) {
                throw new IllegalArgumentException("not a versioned Flyway SQL script name: " + script);
            }
            String version = script.substring(1, separator);
            String description = script.substring(separator + 2, extension).replace('_', ' ');
            return new MigrationExpectation(version, description, script);
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
