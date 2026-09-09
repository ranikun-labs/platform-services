package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only Flyway history facts and their bounded profile classification. */
public record FlywayHistoryInspection(
        String schema,
        String historyTable,
        boolean historyTablePresent,
        HistoryState state,
        List<Entry> entries,
        List<HistoryProfile.MigrationExpectation> missingMigrations,
        List<Entry> unexpectedEntries
) {

    public FlywayHistoryInspection {
        schema = Objects.requireNonNull(schema, "schema must not be null");
        historyTable = Objects.requireNonNull(historyTable, "historyTable must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        missingMigrations = List.copyOf(
                Objects.requireNonNull(missingMigrations, "missingMigrations must not be null"));
        unexpectedEntries = List.copyOf(
                Objects.requireNonNull(unexpectedEntries, "unexpectedEntries must not be null"));
    }

    public static FlywayHistoryInspection assess(
            HistoryProfile profile,
            boolean historyTablePresent,
            List<Entry> entries
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        List<Entry> actualEntries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        if (!historyTablePresent) {
            if (!actualEntries.isEmpty()) {
                throw new IllegalArgumentException("history entries require a present history table");
            }
            return new FlywayHistoryInspection(
                    profile.schema(),
                    profile.historyTable(),
                    false,
                    HistoryState.ABSENT,
                    actualEntries,
                    profile.expectedMigrations(),
                    List.of()
            );
        }

        Map<String, HistoryProfile.MigrationExpectation> expectedByVersion = new HashMap<>();
        for (HistoryProfile.MigrationExpectation expected : profile.expectedMigrations()) {
            expectedByVersion.put(expected.version(), expected);
        }

        List<Entry> migrationEntries = actualEntries.stream()
                .filter(entry -> !isSchemaCreationMarker(profile, entry))
                .toList();
        Set<String> matchedVersions = new HashSet<>();
        List<Entry> unexpectedEntries = new ArrayList<>();
        for (Entry entry : actualEntries) {
            if (isSchemaCreationMarker(profile, entry)) {
                continue;
            }
            HistoryProfile.MigrationExpectation expected = expectedByVersion.get(entry.version());
            boolean matches = expected != null
                    && entry.success()
                    && expected.description().equals(entry.description())
                    && expected.script().equals(entry.script())
                    && !matchedVersions.contains(entry.version());
            if (!matches) {
                unexpectedEntries.add(entry);
            } else {
                matchedVersions.add(entry.version());
            }
        }

        List<HistoryProfile.MigrationExpectation> missingMigrations = profile.expectedMigrations().stream()
                .filter(expected -> !matchedVersions.contains(expected.version()))
                .toList();

        boolean expectedOrder = migrationEntries.size() == profile.expectedMigrations().size()
                && migrationEntries.stream().map(Entry::version).toList().equals(
                profile.expectedMigrations().stream()
                        .map(HistoryProfile.MigrationExpectation::version)
                        .toList());

        HistoryState state;
        if (!unexpectedEntries.isEmpty()) {
            state = HistoryState.UNEXPECTED;
        } else if (!missingMigrations.isEmpty() || !expectedOrder) {
            state = HistoryState.PARTIAL;
        } else {
            state = HistoryState.EXPECTED;
        }
        return new FlywayHistoryInspection(
                profile.schema(),
                profile.historyTable(),
                true,
                state,
                actualEntries,
                missingMigrations,
                unexpectedEntries
        );
    }

    private static boolean isSchemaCreationMarker(HistoryProfile profile, Entry entry) {
        return entry.installedRank() == 0
                && entry.version() == null
                && entry.success()
                && "<< Flyway Schema Creation >>".equals(entry.description())
                && (profile.schema().equals(entry.script())
                || ('"' + profile.schema() + '"').equals(entry.script()));
    }

    public enum HistoryState {
        ABSENT,
        EXPECTED,
        PARTIAL,
        UNEXPECTED
    }

    /** Non-sensitive subset of one Flyway history row. */
    public record Entry(
            int installedRank,
            String version,
            String description,
            String type,
            String script,
            Integer checksum,
            boolean success
    ) {

        public Entry {
            if (installedRank < 0) {
                throw new IllegalArgumentException("installedRank must not be negative");
            }
            description = requireText(description, "description");
            type = requireText(type, "type");
            script = requireText(script, "script");
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
