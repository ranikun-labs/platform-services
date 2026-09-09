package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only JDBC inspector for source identity, structure, and Flyway history.
 *
 * <p>This class has no Spring lifecycle, Flyway mutation API, row reader, planner, or report
 * concern. It can therefore be invoked by a later offline orchestration boundary without
 * starting the normal Identity runtime.
 */
public final class JdbcSourceInspector {

    private static final Set<String> KNOWN_IDENTITY_TABLES = knownIdentityTables();
    private static final Set<String> KNOWN_IDENTITY_SCRIPTS = knownIdentityScripts();

    public StructuralInspectionResult inspect(
            JdbcSourceConfiguration source,
            SourceInspectionProfile profile
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        if (source.sourceKind() != profile.sourceKind()) {
            throw new IllegalArgumentException("source kind must match inspection profile");
        }
        try (ReadOnlyJdbcConnection session = ReadOnlyJdbcConnection.open(source)) {
            Connection connection = session.connection();
            DatabaseIdentity database = databaseIdentity(connection);
            boolean schemaPresent = schemaPresent(connection, profile.schema());
            Set<String> tables = tableNames(connection, profile.schema());
            Set<String> presentExpectedTables = new HashSet<>(tables);
            presentExpectedTables.retainAll(profile.expectedIdentityTables());
            boolean historyPresent = tables.contains(profile.historyProfile().historyTable());
            List<FlywayHistoryInspection.Entry> entries = historyPresent
                    ? historyEntries(connection, profile.schema(), profile.historyProfile().historyTable())
                    : List.of();
            FlywayHistoryInspection history = FlywayHistoryInspection.assess(
                    profile.historyProfile(), historyPresent, entries);
            Set<String> unexpectedIdentityTables = unexpectedIdentityTables(connection, profile);
            boolean unexpectedIdentityHistory = unexpectedIdentityHistory(connection, profile);
            StructuralInspectionResult.StructuralState state = StructuralInspectionResult.classify(
                    schemaPresent,
                    profile.expectedIdentityTables(),
                    presentExpectedTables,
                    history.state(),
                    unexpectedIdentityTables,
                    unexpectedIdentityHistory);
            Set<String> missingExpectedTables = new HashSet<>(profile.expectedIdentityTables());
            missingExpectedTables.removeAll(presentExpectedTables);
            return new StructuralInspectionResult(
                    source.logicalName(),
                    source.sourceKind(),
                    database.databaseName(),
                    database.currentSchema(),
                    profile,
                    schemaPresent,
                    state,
                    presentExpectedTables,
                    missingExpectedTables,
                    unexpectedIdentityTables,
                    unexpectedIdentityHistory,
                    history
            );
        } catch (SQLException failure) {
            throw new SourceInspectionException(
                    source.logicalName(), "read-only structure/history query", failure);
        }
    }

    private DatabaseIdentity databaseIdentity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_database(), current_schema()")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("database identity query returned no row");
                }
                return new DatabaseIdentity(result.getString(1), result.getString(2));
            }
        }
    }

    private boolean schemaPresent(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Set<String> tableNames(Connection connection, String schema) throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE'")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private Set<String> unexpectedIdentityTables(
            Connection connection,
            SourceInspectionProfile profile
    ) throws SQLException {
        Set<String> unexpected = new LinkedHashSet<>();
        String placeholders = String.join(", ", java.util.Collections.nCopies(
                KNOWN_IDENTITY_TABLES.size(), "?"));
        String sql = "SELECT table_schema, table_name FROM information_schema.tables "
                + "WHERE table_type = 'BASE TABLE' "
                + "AND table_schema NOT IN ('pg_catalog', 'information_schema') "
                + "AND table_name IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String table : KNOWN_IDENTITY_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String schema = result.getString(1);
                    String table = result.getString(2);
                    if (!profile.schema().equals(schema)
                            || !profile.expectedIdentityTables().contains(table)) {
                        unexpected.add(schema + "." + table);
                    }
                }
            }
        }
        return Set.copyOf(unexpected);
    }

    private boolean unexpectedIdentityHistory(
            Connection connection,
            SourceInspectionProfile profile
    ) throws SQLException {
        for (String schema : historyTableSchemas(connection, profile.historyProfile().historyTable())) {
            if (profile.schema().equals(schema)) {
                continue;
            }
            for (FlywayHistoryInspection.Entry entry : historyEntries(
                    connection, schema, profile.historyProfile().historyTable())) {
                if (KNOWN_IDENTITY_SCRIPTS.contains(entry.script())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> historyTableSchemas(Connection connection, String historyTable)
            throws SQLException {
        Set<String> schemas = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_schema FROM information_schema.tables "
                        + "WHERE table_name = ? AND table_type = 'BASE TABLE'")) {
            statement.setString(1, historyTable);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    schemas.add(result.getString(1));
                }
            }
        }
        return schemas;
    }

    private List<FlywayHistoryInspection.Entry> historyEntries(
            Connection connection,
            String schema,
            String historyTable
    ) throws SQLException {
        String qualifiedHistoryTable = quoteIdentifier(schema) + "." + quoteIdentifier(historyTable);
        String sql = "SELECT installed_rank, version, description, type, script, checksum, success "
                + "FROM " + qualifiedHistoryTable + " ORDER BY installed_rank";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<FlywayHistoryInspection.Entry> entries = new ArrayList<>();
            while (result.next()) {
                entries.add(new FlywayHistoryInspection.Entry(
                        result.getInt("installed_rank"),
                        result.getString("version"),
                        result.getString("description"),
                        result.getString("type"),
                        result.getString("script"),
                        (Integer) result.getObject("checksum"),
                        result.getBoolean("success")
                ));
            }
            return List.copyOf(entries);
        }
    }

    private static Set<String> knownIdentityTables() {
        LinkedHashSet<String> tables = new LinkedHashSet<>(IdentityDatabaseSchema.TABLES);
        tables.add("users");
        return Set.copyOf(tables);
    }

    private static Set<String> knownIdentityScripts() {
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        addScripts(scripts, HistoryProfile.platformLegacy());
        addScripts(scripts, HistoryProfile.carelogLegacy());
        return Set.copyOf(scripts);
    }

    private static void addScripts(Set<String> scripts, HistoryProfile profile) {
        profile.acceptedVariants().forEach(variant -> variant.migrations().forEach(expectation -> {
            if (expectation.script().startsWith("V")) {
                scripts.add(expectation.script());
            }
        }));
    }

    private String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe SQL identifier");
        }
        return '"' + identifier + '"';
    }

    private record DatabaseIdentity(String databaseName, String currentSchema) {
    }
}
