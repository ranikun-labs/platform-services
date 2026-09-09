package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSourceInspectorTest {

    @Test
    void checkedInIdentityProfilesUseExactMigrationScriptTruth() {
        SourceInspectionProfile platform = SourceInspectionProfile.platformLegacy();
        SourceInspectionProfile target = SourceInspectionProfile.canonicalTarget();

        assertThat(platform.schema()).isEqualTo("public");
        assertThat(platform.historyProfile().expectedMigrations())
                .extracting(HistoryProfile.MigrationExpectation::version,
                        HistoryProfile.MigrationExpectation::description,
                        HistoryProfile.MigrationExpectation::script)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "identity schema", "V1__identity_schema.sql"),
                        org.assertj.core.groups.Tuple.tuple(
                                "2", "refresh session single account", "V2__refresh_session_single_account.sql"));
        assertThat(target.schema()).isEqualTo("identity");
        assertThat(target.sourceKind()).isEqualTo(SourceKind.TARGET_IDENTITY);
    }

    @Test
    void partialHistoryIsClassifiedWithoutOpeningJdbc() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        FlywayHistoryInspection.Entry v1 = new FlywayHistoryInspection.Entry(
                1, "1", "identity schema", "SQL", "V1__identity_schema.sql", 101, true);

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(profile, true, List.of(v1));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.PARTIAL);
        assertThat(result.missingMigrations())
                .extracting(HistoryProfile.MigrationExpectation::version)
                .containsExactly("2");
        assertThat(result.unexpectedEntries()).isEmpty();
    }

    @Test
    void unknownOrFailedHistoryIsUnexpectedAndFailsClosed() {
        HistoryProfile profile = HistoryProfile.platformLegacy();
        FlywayHistoryInspection.Entry unknown = new FlywayHistoryInspection.Entry(
                1, "9", "unrelated migration", "SQL", "V9__unrelated.sql", 909, true);

        FlywayHistoryInspection result = FlywayHistoryInspection.assess(profile, true, List.of(unknown));

        assertThat(result.state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
        assertThat(result.unexpectedEntries()).containsExactly(unknown);
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
                true, expected, Set.of("platform_accounts"), FlywayHistoryInspection.HistoryState.PARTIAL))
                .isEqualTo(StructuralInspectionResult.StructuralState.PARTIAL);
        assertThat(StructuralInspectionResult.classify(
                true, expected, expected, FlywayHistoryInspection.HistoryState.EXPECTED))
                .isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(StructuralInspectionResult.classify(
                true, expected, expected, FlywayHistoryInspection.HistoryState.UNEXPECTED))
                .isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
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
        assertThat(configuration.toString()).doesNotContain("not-for-logs", "jdbc:postgresql", "db-user");
        assertThatThrownBy(() -> new SourceInspectionProfile(
                "bad", SourceKind.CARELOG_IDENTITY, "public", Set.of("table"),
                HistoryProfile.external("identity", actualCarelogHistory.expectedMigrations())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
