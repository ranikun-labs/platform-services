package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Expected structural profile for one source role. */
public record SourceInspectionProfile(
        String name,
        SourceKind sourceKind,
        String schema,
        Set<String> expectedIdentityTables,
        HistoryProfile historyProfile
) {

    public SourceInspectionProfile {
        name = requireText(name, "name");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        schema = requireText(schema, "schema");
        expectedIdentityTables = copyIdentifiers(expectedIdentityTables, "expectedIdentityTables");
        if (expectedIdentityTables.isEmpty()) {
            throw new IllegalArgumentException("expectedIdentityTables must not be empty");
        }
        historyProfile = Objects.requireNonNull(historyProfile, "historyProfile must not be null");
        if (!schema.equals(historyProfile.schema())) {
            throw new IllegalArgumentException("source schema must match history profile schema");
        }
    }

    /** Platform's legacy public schema and the exact checked-in Identity migration line. */
    public static SourceInspectionProfile platformLegacy() {
        return new SourceInspectionProfile(
                "platform-legacy",
                SourceKind.PLATFORM_IDENTITY,
                "public",
                Set.copyOf(IdentityDatabaseSchema.LEGACY_TABLES),
                HistoryProfile.platformLegacy()
        );
    }

    /** Canonical target schema and the exact checked-in Identity migration line. */
    public static SourceInspectionProfile canonicalTarget() {
        return new SourceInspectionProfile(
                "canonical-identity-target",
                SourceKind.TARGET_IDENTITY,
                IdentityDatabaseSchema.NAME,
                identityTables(),
                HistoryProfile.canonicalTarget()
        );
    }

    /**
     * Carelog's schema and migration history are owned outside this repository. This factory
     * requires the exact externally observed history profile and therefore cannot silently bless a
     * guessed Carelog lineage.
     */
    public static SourceInspectionProfile carelogLegacy(HistoryProfile actualHistoryProfile) {
        return carelogLegacy(actualHistoryProfile, carelogIdentityTables());
    }

    /** Carelog legacy profile derived from its checked-in V1 through V8 migration line. */
    public static SourceInspectionProfile carelogLegacy() {
        return carelogLegacy(HistoryProfile.carelogLegacy(), carelogIdentityTables());
    }

    public static SourceInspectionProfile carelogLegacy(
            HistoryProfile actualHistoryProfile,
            Set<String> expectedIdentityTables
    ) {
        Objects.requireNonNull(actualHistoryProfile, "actualHistoryProfile must not be null");
        return new SourceInspectionProfile(
                "carelog-legacy",
                SourceKind.CARELOG_IDENTITY,
                actualHistoryProfile.schema(),
                expectedIdentityTables,
                actualHistoryProfile
        );
    }

    private static Set<String> identityTables() {
        return Set.copyOf(IdentityDatabaseSchema.TABLES);
    }

    private static Set<String> carelogIdentityTables() {
        LinkedHashSet<String> tables = new LinkedHashSet<>(IdentityDatabaseSchema.LEGACY_TABLES);
        tables.add("users");
        return Collections.unmodifiableSet(tables);
    }

    private static Set<String> copyIdentifiers(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            Objects.requireNonNull(value, field + " must not contain null");
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(field + " must contain simple SQL identifiers");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
