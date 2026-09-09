package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSourceInspectorTest {

    @Test
    void checkedInIdentityProfilesUseExactTypeAndChecksumTruth() {
        SourceInspectionProfile platform = SourceInspectionProfile.platformLegacy();
        SourceInspectionProfile target = SourceInspectionProfile.canonicalTarget();

        assertThat(platform.schema()).isEqualTo("public");
        assertThat(platform.historyProfile().expectedMigrations())
                .extracting(HistoryProfile.MigrationExpectation::version,
                        HistoryProfile.MigrationExpectation::description,
                        HistoryProfile.MigrationExpectation::script,
                        HistoryProfile.MigrationExpectation::type,
                        HistoryProfile.MigrationExpectation::checksum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "1", "identity schema", "V1__identity_schema.sql", "SQL", -1454289175),
                        org.assertj.core.groups.Tuple.tuple(
                                "2", "refresh session single account",
                                "V2__refresh_session_single_account.sql", "SQL", 1506915702));
        assertThat(target.schema()).isEqualTo("identity");
        assertThat(target.sourceKind()).isEqualTo(SourceKind.TARGET_IDENTITY);
    }

    @Test
    void checksumAndTypeDriftAreUnexpected() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        HistoryProfile.MigrationExpectation v1 = profile.expectedMigrations().get(0);

        FlywayHistoryInspection checksumDrift = FlywayHistoryInspection.assess(
                profile,
                true,
                List.of(entry(v1, 1, v1.type(), v1.checksum() + 1)));
        FlywayHistoryInspection typeDrift = FlywayHistoryInspection.assess(
                profile,
                true,
                List.of(entry(v1, 1, "BASELINE", v1.checksum())));

        assertThat(checksumDrift.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
        assertThat(typeDrift.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
    }

    @Test
    void validPrefixIsPartialWhenItsTupleIsCanonical() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        HistoryProfile.MigrationExpectation v1 = profile.expectedMigrations().get(0);

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(
                profile, true, List.of(entry(v1, 1, v1.type(), v1.checksum())));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.PARTIAL);
        assertThat(result.missingMigrations())
                .extracting(HistoryProfile.MigrationExpectation::version)
                .containsExactly("2");
        assertThat(result.unexpectedEntries()).isEmpty();
    }

    @Test
    void carelogFreshAndBaselineFormsAreBothExpected() {
        HistoryProfile profile = HistoryProfile.carelogLegacy();

        FlywayHistoryInspection fresh = FlywayHistoryInspection.assess(
                profile, true, entries(profile.expectedMigrations()));
        List<HistoryProfile.MigrationExpectation> baselined =
                profile.alternateVariants().get(0).migrations();
        FlywayHistoryInspection baseline = FlywayHistoryInspection.assess(
                profile, true, entries(baselined));

        assertThat(fresh.state()).isEqualTo(FlywayHistoryInspection.HistoryState.EXPECTED);
        assertThat(baseline.state()).isEqualTo(FlywayHistoryInspection.HistoryState.EXPECTED);
        assertThat(baselined.get(0).type()).isEqualTo("BASELINE");
        assertThat(baselined.get(0).checksum()).isNull();
    }

    @Test
    void invalidCarelogBaselineVariantIsUnexpected() {
        HistoryProfile profile = HistoryProfile.carelogLegacy();
        List<HistoryProfile.MigrationExpectation> invalid =
                new ArrayList<>(profile.alternateVariants().get(0).migrations());
        HistoryProfile.MigrationExpectation v2 = invalid.get(1);
        invalid.set(1, new HistoryProfile.MigrationExpectation(
                v2.version(), v2.description(), v2.script(), "BASELINE", v2.checksum()));

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(
                profile, true, entries(invalid));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
    }

    @Test
    void completeOutOfOrderHistoryIsUnexpected() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        HistoryProfile.MigrationExpectation v1 = profile.expectedMigrations().get(0);
        HistoryProfile.MigrationExpectation v2 = profile.expectedMigrations().get(1);

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(
                profile,
                true,
                List.of(entry(v2, 1, v2.type(), v2.checksum()),
                        entry(v1, 2, v1.type(), v1.checksum())));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
        assertThat(result.unexpectedEntries()).isNotEmpty();
    }

    @Test
    void malformedRequiredHistoryValueIsClassifiedSafely() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        HistoryProfile.MigrationExpectation v1 = profile.expectedMigrations().get(0);

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(
                profile,
                true,
                List.of(new FlywayHistoryInspection.Entry(
                        1, v1.version(), null, v1.type(), v1.script(), v1.checksum(), true)));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
    }

    @Test
    void absentHistoryWithRowsIsUnexpectedRatherThanAnException() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        HistoryProfile.MigrationExpectation v1 = profile.expectedMigrations().get(0);

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(
                profile, false, List.of(entry(v1, 1, v1.type(), v1.checksum())));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
    }

    @Test
    void boundedIdentityFootprintForcesUnexpectedClassification() {
        Set<String> expected = Set.of("platform_accounts", "password_credentials");

        assertThat(StructuralInspectionResult.classify(
                true,
                expected,
                expected,
                FlywayHistoryInspection.HistoryState.EXPECTED,
                Set.of("public.users"),
                false))
                .isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
        assertThat(StructuralInspectionResult.classify(
                true,
                expected,
                expected,
                FlywayHistoryInspection.HistoryState.EXPECTED,
                Set.of(),
                true))
                .isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
    }

    @Test
    void structuralClassificationDistinguishesAbsentPartialExpectedAndUnexpected() {
        Set<String> expected = Set.of("platform_accounts", "password_credentials");

        assertThat(StructuralInspectionResult.classify(
                false, expected, Set.of(), FlywayHistoryInspection.HistoryState.ABSENT))
                .isEqualTo(StructuralInspectionResult.StructuralState.ABSENT);
        assertThat(StructuralInspectionResult.classify(
                true, expected, Set.of(), FlywayHistoryInspection.HistoryState.ABSENT))
                .isEqualTo(StructuralInspectionResult.StructuralState.ABSENT);
        assertThat(StructuralInspectionResult.classify(
                true, expected, Set.of("platform_accounts"),
                FlywayHistoryInspection.HistoryState.PARTIAL))
                .isEqualTo(StructuralInspectionResult.StructuralState.PARTIAL);
        assertThat(StructuralInspectionResult.classify(
                true, expected, expected, FlywayHistoryInspection.HistoryState.EXPECTED))
                .isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(StructuralInspectionResult.classify(
                true, expected, expected, FlywayHistoryInspection.HistoryState.UNEXPECTED))
                .isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
    }

    @Test
    void credentialBearingJdbcUrlIsRejectedBeforeConnectionOpening() {
        JdbcSourceConfiguration accepted = configuration("jdbc:postgresql://host/db?sslmode=require");

        assertThat(accepted.toString()).doesNotContain("jdbc:postgresql", "db-user", "top-secret");
        for (String rejectedUrl : List.of(
                "jdbc:postgresql://host/db?password=top-secret",
                "jdbc:postgresql://host/db?PASSWORD=top-secret",
                "jdbc:postgresql://host/db?%70assword=top-secret",
                "jdbc:postgresql://host/db?password%3Dtop-secret",
                "jdbc:postgresql://host/db?user=db-user",
                "jdbc:postgresql://db-user:top-secret@host/db")) {
            assertThatThrownBy(() -> configuration(rejectedUrl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("top-secret")
                    .hasMessageNotContaining("db-user");
        }
    }

    @Test
    void inspectionFailureDoesNotExposeDriverCauseOrJdbcSecret() {
        SQLException driverFailure = new SQLException(
                "connection failed jdbc:postgresql://host/db?password=top-secret", "08001");

        SourceInspectionException exception = new SourceInspectionException(
                "platform", "open read-only JDBC session", driverFailure);

        assertThat(exception.getCause()).isNull();
        assertThat(exception.sqlState()).isEqualTo("08001");
        assertThat(exception.category()).isEqualTo("CONNECTION_FAILURE");
        assertThat(exception).hasMessageNotContaining("top-secret")
                .hasMessageNotContaining("jdbc:postgresql");
        assertThat(exception.toString()).doesNotContain("top-secret", "jdbc:postgresql");
    }

    @Test
    void carelogProfileUsesCheckedInHistoryAndConfigurationDoesNotExposePassword() {
        HistoryProfile actualCarelogHistory = HistoryProfile.carelogLegacy();
        SourceInspectionProfile profile = SourceInspectionProfile.carelogLegacy();
        JdbcSourceConfiguration configuration = new JdbcSourceConfiguration(
                "carelog", SourceKind.CARELOG_IDENTITY, "jdbc:postgresql://host/db", "db-user",
                "not-for-logs".toCharArray());

        assertThat(profile.sourceKind()).isEqualTo(SourceKind.CARELOG_IDENTITY);
        assertThat(profile.expectedIdentityTables()).contains("users");
        assertThat(actualCarelogHistory.expectedMigrations())
                .extracting(HistoryProfile.MigrationExpectation::version)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(actualCarelogHistory.alternateVariants()).hasSize(1);
        assertThat(configuration.toString()).doesNotContain("not-for-logs", "jdbc:postgresql", "db-user");
        assertThatThrownBy(() -> new SourceInspectionProfile(
                "bad", SourceKind.CARELOG_IDENTITY, "public", Set.of("table"),
                HistoryProfile.external("identity", actualCarelogHistory.expectedMigrations())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<FlywayHistoryInspection.Entry> entries(
            List<HistoryProfile.MigrationExpectation> migrations
    ) {
        List<FlywayHistoryInspection.Entry> entries = new ArrayList<>();
        int rank = 1;
        for (HistoryProfile.MigrationExpectation migration : migrations) {
            entries.add(entry(migration, rank++, migration.type(), migration.checksum()));
        }
        return entries;
    }

    private static FlywayHistoryInspection.Entry entry(
            HistoryProfile.MigrationExpectation migration,
            int rank,
            String type,
            Integer checksum
    ) {
        return new FlywayHistoryInspection.Entry(
                rank,
                migration.version(),
                migration.description(),
                type,
                migration.script(),
                checksum,
                true);
    }

    private static JdbcSourceConfiguration configuration(String jdbcUrl) {
        return new JdbcSourceConfiguration(
                "platform", SourceKind.PLATFORM_IDENTITY, jdbcUrl, "db-user", "top-secret".toCharArray());
    }
}
