package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSourceInspectorIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final JdbcSourceInspector INSPECTOR = new JdbcSourceInspector();

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS identity CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
            for (String table : IdentityDatabaseSchema.TABLES) {
                statement.execute("DROP TABLE IF EXISTS public.\"" + table + "\" CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.users CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.unrelated_table CASCADE");
        }
    }

    @Test
    void expectedPlatformLegacyStructureAndHistoryIsReported() {
        migrate("public", null);

        StructuralInspectionResult result = INSPECTOR.inspect(platformSource(), SourceInspectionProfile.platformLegacy());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(result.history().state()).isEqualTo(FlywayHistoryInspection.HistoryState.EXPECTED);
        assertThat(result.presentExpectedTables()).containsExactlyInAnyOrderElementsOf(IdentityDatabaseSchema.TABLES);
        assertThat(result.databaseName()).isEqualTo(POSTGRES.getDatabaseName());
    }

    @Test
    void partialPlatformHistoryFailsClosedAsPartial() {
        migrate("public", "1");

        StructuralInspectionResult result = INSPECTOR.inspect(platformSource(), SourceInspectionProfile.platformLegacy());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.PARTIAL);
        assertThat(result.history().state()).isEqualTo(FlywayHistoryInspection.HistoryState.PARTIAL);
        assertThat(result.history().missingMigrations())
                .extracting(HistoryProfile.MigrationExpectation::version)
                .containsExactly("2");
    }

    @Test
    void nullInstalledRankInFlywayHistoryIsUnexpected() throws SQLException {
        SourceInspectionProfile profile = SourceInspectionProfile.platformLegacy();
        HistoryProfile.MigrationExpectation migration = profile.historyProfile().expectedMigrations().get(0);
        createPublicHistory(List.of(new HistoryRow(
                null,
                migration.version(),
                migration.description(),
                migration.type(),
                migration.script(),
                migration.checksum(),
                true)));

        StructuralInspectionResult result = INSPECTOR.inspect(platformSource(), profile);

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
        assertThat(result.history().state()).isEqualTo(FlywayHistoryInspection.HistoryState.UNEXPECTED);
    }

    @Test
    void expectedCanonicalTargetStructureIsReportedInIdentitySchema() {
        migrate("identity", null);

        StructuralInspectionResult result = INSPECTOR.inspect(targetSource(), SourceInspectionProfile.canonicalTarget());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(result.profile().schema()).isEqualTo("identity");
        assertThat(result.history().historyTablePresent()).isTrue();
        List<FlywayHistoryInspection.Entry> schemaMarkers = result.history().entries().stream()
                .filter(entry -> "SCHEMA".equals(entry.type()))
                .toList();
        assertThat(schemaMarkers).hasSize(1);
        FlywayHistoryInspection.Entry marker = schemaMarkers.get(0);
        assertThat(marker.installedRank()).isEqualTo(0);
        assertThat(marker.version()).isNull();
        assertThat(marker.description()).isEqualTo("<< Flyway Schema Creation >>");
        assertThat(marker.type()).isEqualTo("SCHEMA");
        assertThat(marker.script()).isEqualTo("\"identity\"");
        assertThat(marker.checksum()).isNull();
        assertThat(marker.success()).isTrue();
    }

    @Test
    void canonicalTargetWithLegacyPublicIdentityFootprintIsUnexpected() {
        migrate("identity", null);
        migrate("public", null);

        StructuralInspectionResult result = INSPECTOR.inspect(
                targetSource(), SourceInspectionProfile.canonicalTarget());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
        assertThat(result.unexpectedIdentityTables()).contains("public.platform_accounts");
        assertThat(result.unexpectedIdentityHistory()).isTrue();
    }

    @Test
    void canonicalTargetWithDriftedPublicSliceOneHistorySignatureIsUnexpected() throws SQLException {
        migrate("identity", null);
        createPublicHistory(List.of(new HistoryRow(
                1, "1", "identity schema", "SQL", "V1__renamed_identity_script.sql", null, true)));

        StructuralInspectionResult result = INSPECTOR.inspect(
                targetSource(), SourceInspectionProfile.canonicalTarget());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
        assertThat(result.unexpectedIdentityHistory()).isTrue();
    }

    @Test
    void canonicalTargetWithUnrelatedPublicFlywayHistoryIsExpected() throws SQLException {
        migrate("identity", null);
        createPublicHistory(List.of(new HistoryRow(
                1, "9", "unrelated application migration", "SQL", "V9__unrelated.sql", 123, true)));

        StructuralInspectionResult result = INSPECTOR.inspect(
                targetSource(), SourceInspectionProfile.canonicalTarget());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(result.unexpectedIdentityHistory()).isFalse();
    }

    @Test
    void unrelatedPublicObjectsDoNotBecomeIdentityStructure() throws SQLException {
        migrate("identity", null);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.unrelated_table (id integer PRIMARY KEY)");
        }

        StructuralInspectionResult result = INSPECTOR.inspect(
                targetSource(), SourceInspectionProfile.canonicalTarget());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(result.presentExpectedTables()).doesNotContain("unrelated_table");
    }

    @Test
    void knownIdentityLikeTableOutsidePlatformProfileIsUnexpected() throws SQLException {
        migrate("public", null);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.users (id integer PRIMARY KEY)");
        }

        StructuralInspectionResult result = INSPECTOR.inspect(
                platformSource(), SourceInspectionProfile.platformLegacy());

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.UNEXPECTED);
        assertThat(result.unexpectedIdentityTables()).contains("public.users");
    }

    @Test
    void carelogProfileUsesCheckedInHistoryWithoutCarelogDependency() throws SQLException {
        SourceInspectionProfile profile = SourceInspectionProfile.carelogLegacy();
        createCarelogLikeStructure(profile);

        StructuralInspectionResult result = INSPECTOR.inspect(carelogSource(), profile);

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(result.history().entries()).hasSize(8);
    }

    @Test
    void carelogBaselineOnMigrateHistoryIsExpected() throws SQLException {
        SourceInspectionProfile profile = SourceInspectionProfile.carelogLegacy();
        createCarelogLikeStructure(
                profile, profile.historyProfile().alternateVariants().get(0).migrations());

        StructuralInspectionResult result = INSPECTOR.inspect(carelogSource(), profile);

        assertThat(result.state()).isEqualTo(StructuralInspectionResult.StructuralState.EXPECTED);
        assertThat(result.history().state()).isEqualTo(FlywayHistoryInspection.HistoryState.EXPECTED);
        assertThat(result.history().entries().get(0).type()).isEqualTo("BASELINE");
    }

    @Test
    void inspectionDoesNotMutateSchemasTablesHistoryOrRows() throws SQLException {
        migrate("public", null);
        Snapshot before = snapshot();

        INSPECTOR.inspect(platformSource(), SourceInspectionProfile.platformLegacy());

        Snapshot after = snapshot();
        assertThat(after).isEqualTo(before);
    }

    private void migrate(String schema, String targetVersion) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .cleanDisabled(false);
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        configuration.load().migrate();
    }

    private void createCarelogLikeStructure(SourceInspectionProfile profile) throws SQLException {
        createCarelogLikeStructure(profile, profile.historyProfile().expectedMigrations());
    }

    private void createPublicHistory(List<HistoryRow> rows) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.flyway_schema_history ("
                    + "installed_rank integer,"
                    + "version varchar(50),"
                    + "description varchar(200),"
                    + "type varchar(20),"
                    + "script varchar(1000),"
                    + "checksum integer,"
                    + "installed_by varchar(100),"
                    + "installed_on timestamp with time zone DEFAULT CURRENT_TIMESTAMP,"
                    + "execution_time integer,"
                    + "success boolean)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO public.flyway_schema_history "
                            + "(installed_rank, version, description, type, script, checksum, installed_by, "
                            + "execution_time, success) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (HistoryRow row : rows) {
                    if (row.installedRank() == null) {
                        insert.setNull(1, java.sql.Types.INTEGER);
                    } else {
                        insert.setInt(1, row.installedRank());
                    }
                    insert.setString(2, row.version());
                    insert.setString(3, row.description());
                    insert.setString(4, row.type());
                    insert.setString(5, row.script());
                    if (row.checksum() == null) {
                        insert.setNull(6, java.sql.Types.INTEGER);
                    } else {
                        insert.setInt(6, row.checksum());
                    }
                    insert.setString(7, "test");
                    insert.setInt(8, 1);
                    insert.setBoolean(9, row.success());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private void createCarelogLikeStructure(
            SourceInspectionProfile profile,
            List<HistoryProfile.MigrationExpectation> migrations
    ) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : profile.expectedIdentityTables()) {
                statement.execute("CREATE TABLE public.\"" + table + "\" (marker integer)");
            }
            statement.execute("CREATE TABLE public.flyway_schema_history ("
                    + "installed_rank integer NOT NULL,"
                    + "version varchar(50),"
                    + "description varchar(200) NOT NULL,"
                    + "type varchar(20) NOT NULL,"
                    + "script varchar(1000) NOT NULL,"
                    + "checksum integer,"
                    + "installed_by varchar(100) NOT NULL,"
                    + "installed_on timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "execution_time integer NOT NULL,"
                    + "success boolean NOT NULL,"
                    + "PRIMARY KEY (installed_rank))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO public.flyway_schema_history "
                            + "(installed_rank, version, description, type, script, checksum, installed_by, "
                            + "execution_time, success) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int installedRank = 1;
                for (HistoryProfile.MigrationExpectation migration : migrations) {
                    insert.setInt(1, installedRank++);
                    insert.setString(2, migration.version());
                    insert.setString(3, migration.description());
                    insert.setString(4, migration.type());
                    insert.setString(5, migration.script());
                    if (migration.checksum() == null) {
                        insert.setNull(6, java.sql.Types.INTEGER);
                    } else {
                        insert.setInt(6, migration.checksum());
                    }
                    insert.setString(7, "test");
                    insert.setInt(8, 1);
                    insert.setBoolean(9, true);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private JdbcSourceConfiguration platformSource() {
        return source("platform", SourceKind.PLATFORM_IDENTITY);
    }

    private JdbcSourceConfiguration carelogSource() {
        return source("carelog", SourceKind.CARELOG_IDENTITY);
    }

    private JdbcSourceConfiguration targetSource() {
        return source("target", SourceKind.TARGET_IDENTITY);
    }

    private JdbcSourceConfiguration source(String name, SourceKind sourceKind) {
        return new JdbcSourceConfiguration(
                name,
                sourceKind,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword().toCharArray());
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Snapshot snapshot() throws SQLException {
        Set<String> schemas = new HashSet<>();
        Set<String> tables = new HashSet<>();
        List<String> history = new ArrayList<>();
        Map<String, Integer> rowCounts = new java.util.LinkedHashMap<>();
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT schema_name FROM information_schema.schemata")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        schemas.add(result.getString(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT table_schema, table_name FROM information_schema.tables "
                            + "WHERE table_type = 'BASE TABLE'")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        tables.add(result.getString(1) + "." + result.getString(2));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT installed_rank, version, description, script, checksum, success "
                            + "FROM public.flyway_schema_history ORDER BY installed_rank")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        history.add(result.getInt(1) + "|" + result.getString(2) + "|"
                                + result.getString(3) + "|" + result.getString(4) + "|"
                                + result.getObject(5) + "|" + result.getBoolean(6));
                    }
                }
            }
            for (String table : IdentityDatabaseSchema.TABLES) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM public.\"" + table + "\"")) {
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        rowCounts.put(table, result.getInt(1));
                    }
                }
            }
        }
        return new Snapshot(schemas, tables, history, rowCounts);
    }

    private record Snapshot(
            Set<String> schemas,
            Set<String> tables,
            List<String> history,
            Map<String, Integer> rowCounts
    ) {
    }

    private record HistoryRow(
            Integer installedRank,
            String version,
            String description,
            String type,
            String script,
            Integer checksum,
            boolean success
    ) {
    }
}
