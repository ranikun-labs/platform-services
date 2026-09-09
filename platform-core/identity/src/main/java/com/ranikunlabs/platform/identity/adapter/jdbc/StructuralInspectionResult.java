package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Structural and Flyway facts returned by a single read-only source inspection. */
public record StructuralInspectionResult(
        String logicalName,
        SourceKind sourceKind,
        String databaseName,
        String currentSchema,
        SourceInspectionProfile profile,
        boolean schemaPresent,
        StructuralState state,
        Set<String> presentExpectedTables,
        Set<String> missingExpectedTables,
        FlywayHistoryInspection history
) {

    public StructuralInspectionResult {
        logicalName = requireText(logicalName, "logicalName");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        databaseName = requireText(databaseName, "databaseName");
        currentSchema = requireText(currentSchema, "currentSchema");
        profile = Objects.requireNonNull(profile, "profile must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        presentExpectedTables = copySet(presentExpectedTables, "presentExpectedTables");
        missingExpectedTables = copySet(missingExpectedTables, "missingExpectedTables");
        history = Objects.requireNonNull(history, "history must not be null");
    }

    /** Pure classification used by the JDBC adapter and its unit tests. */
    public static StructuralState classify(
            boolean schemaPresent,
            Set<String> expectedTables,
            Set<String> presentExpectedTables,
            FlywayHistoryInspection.HistoryState historyState
    ) {
        Objects.requireNonNull(expectedTables, "expectedTables must not be null");
        Objects.requireNonNull(presentExpectedTables, "presentExpectedTables must not be null");
        Objects.requireNonNull(historyState, "historyState must not be null");
        if (expectedTables.isEmpty()) {
            throw new IllegalArgumentException("expectedTables must not be empty");
        }
        if (!expectedTables.containsAll(presentExpectedTables)) {
            throw new IllegalArgumentException("presentExpectedTables must be a subset of expectedTables");
        }
        if (!schemaPresent && (!presentExpectedTables.isEmpty()
                || historyState != FlywayHistoryInspection.HistoryState.ABSENT)) {
            return StructuralState.UNEXPECTED;
        }
        if (presentExpectedTables.isEmpty()
                && historyState == FlywayHistoryInspection.HistoryState.ABSENT) {
            return StructuralState.ABSENT;
        }
        if (historyState == FlywayHistoryInspection.HistoryState.UNEXPECTED) {
            return StructuralState.UNEXPECTED;
        }
        if (presentExpectedTables.containsAll(expectedTables)
                && historyState == FlywayHistoryInspection.HistoryState.EXPECTED) {
            return StructuralState.EXPECTED;
        }
        return StructuralState.PARTIAL;
    }

    public boolean isExpected() {
        return state == StructuralState.EXPECTED;
    }

    private static Set<String> copySet(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public enum StructuralState {
        ABSENT,
        EXPECTED,
        PARTIAL,
        UNEXPECTED
    }
}
