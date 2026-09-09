package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.util.ArrayList;
import java.util.Comparator;
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
            return new FlywayHistoryInspection(
                    profile.schema(),
                    profile.historyTable(),
                    false,
                    actualEntries.isEmpty() ? HistoryState.ABSENT : HistoryState.UNEXPECTED,
                    actualEntries,
                    profile.expectedMigrations(),
                    actualEntries
            );
        }

        List<VariantAssessment> assessments = profile.acceptedVariants().stream()
                .map(variant -> assessVariant(profile, variant, actualEntries))
                .toList();
        VariantAssessment selected = assessments.stream()
                .filter(assessment -> assessment.state() == HistoryState.EXPECTED)
                .findFirst()
                .orElseGet(() -> assessments.stream()
                        .filter(assessment -> assessment.state() == HistoryState.PARTIAL)
                        .findFirst()
                        .orElse(assessments.get(0)));

        return new FlywayHistoryInspection(
                profile.schema(),
                profile.historyTable(),
                true,
                selected.state(),
                actualEntries,
                selected.missingMigrations(),
                selected.unexpectedEntries()
        );
    }

    private static VariantAssessment assessVariant(
            HistoryProfile profile,
            HistoryProfile.HistoryVariant variant,
            List<Entry> actualEntries
    ) {
        Map<String, HistoryProfile.MigrationExpectation> expectedByVersion = new HashMap<>();
        for (HistoryProfile.MigrationExpectation expected : variant.migrations()) {
            expectedByVersion.put(expected.version(), expected);
        }

        List<Entry> migrationEntries = new ArrayList<>();
        List<Entry> unexpectedEntries = new ArrayList<>();
        Set<String> matchedVersions = new HashSet<>();
        Set<Integer> installedRanks = new HashSet<>();
        for (Entry entry : actualEntries) {
            if (isSchemaCreationMarkerCandidate(entry)) {
                if (!isValidSchemaCreationMarker(profile, entry)) {
                    unexpectedEntries.add(entry);
                }
                continue;
            }
            migrationEntries.add(entry);
            if (!installedRanks.add(entry.installedRank())) {
                unexpectedEntries.add(entry);
            }
            HistoryProfile.MigrationExpectation expected = expectedByVersion.get(entry.version());
            boolean matches = expected != null
                    && entry.success()
                    && expected.description().equals(entry.description())
                    && expected.type().equals(entry.type())
                    && expected.script().equals(entry.script())
                    && checksumMatches(expected, entry)
                    && matchedVersions.add(entry.version());
            if (!matches) {
                unexpectedEntries.add(entry);
            }
        }

        List<Entry> orderedMigrations = migrationEntries.stream()
                .sorted(Comparator.comparingInt(Entry::installedRank))
                .toList();
        List<HistoryProfile.MigrationExpectation> missingMigrations = variant.migrations().stream()
                .filter(expected -> !matchedVersions.contains(expected.version()))
                .toList();
        List<String> actualVersions = orderedMigrations.stream().map(Entry::version).toList();
        List<String> expectedVersions = variant.migrations().stream()
                .map(HistoryProfile.MigrationExpectation::version)
                .toList();
        boolean sequentialRanks = sequentialRanks(orderedMigrations);
        boolean exactOrder = actualVersions.equals(expectedVersions)
                && sequentialRanks
                && matchedVersions.size() == variant.migrations().size()
                && unexpectedEntries.isEmpty();
        boolean recognizablePrefix = isPrefix(actualVersions, expectedVersions)
                && sequentialRanks
                && unexpectedEntries.isEmpty();

        HistoryState state;
        if (exactOrder) {
            state = HistoryState.EXPECTED;
        } else if (recognizablePrefix) {
            state = HistoryState.PARTIAL;
        } else {
            state = HistoryState.UNEXPECTED;
            if (unexpectedEntries.isEmpty()) {
                // Ordering/rank contradictions are themselves evidence even when every row tuple
                // individually matches a known migration.
                unexpectedEntries = new ArrayList<>(orderedMigrations);
            }
        }
        return new VariantAssessment(state, missingMigrations, List.copyOf(unexpectedEntries));
    }

    private static boolean checksumMatches(
            HistoryProfile.MigrationExpectation expected,
            Entry actual
    ) {
        return !expected.checksumValidated()
                || Objects.equals(expected.checksum(), actual.checksum());
    }

    private static boolean sequentialRanks(List<Entry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).installedRank() != index + 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrefix(List<String> actual, List<String> expected) {
        if (actual.size() > expected.size()) {
            return false;
        }
        return actual.equals(expected.subList(0, actual.size()));
    }

    private static boolean isSchemaCreationMarkerCandidate(Entry entry) {
        return entry.installedRank() == 0
                && entry.version() == null
                && "<< Flyway Schema Creation >>".equals(entry.description());
    }

    private static boolean isValidSchemaCreationMarker(
            HistoryProfile profile,
            Entry entry
    ) {
        return isSchemaCreationMarkerCandidate(entry)
                && entry.success()
                && "SCHEMA".equals(entry.type())
                && entry.checksum() == null
                && (profile.schema().equals(entry.script())
                || ('"' + profile.schema() + '"').equals(entry.script()));
    }

    private record VariantAssessment(
            HistoryState state,
            List<HistoryProfile.MigrationExpectation> missingMigrations,
            List<Entry> unexpectedEntries
    ) {
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
        // Raw JDBC history values are intentionally tolerated here so assess() can classify
        // malformed rows as UNEXPECTED instead of leaking a constructor NPE/IllegalArgumentException.
    }
}
