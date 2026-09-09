package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
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
                    ? historyEntries(connection, profile.historyProfile())
                    : List.of();
            FlywayHistoryInspection history = FlywayHistoryInspection.assess(
                    profile.historyProfile(), historyPresent, entries);
            StructuralInspectionResult.StructuralState state = StructuralInspectionResult.classify(
                    schemaPresent,
                    profile.expectedIdentityTables(),
                    presentExpectedTables,
                    history.state());
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
                    history
            );
        } catch (SQLException failure) {
            throw new SourceInspectionException(source.logicalName(), failure);
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

    private List<FlywayHistoryInspection.Entry> historyEntries(
            Connection connection,
            HistoryProfile profile
    ) throws SQLException {
        String historyTable = quoteIdentifier(profile.schema()) + "." + quoteIdentifier(profile.historyTable());
        String sql = "SELECT installed_rank, version, description, type, script, checksum, success "
                + "FROM " + historyTable + " ORDER BY installed_rank";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<FlywayHistoryInspection.Entry> entries = new java.util.ArrayList<>();
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

    private String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe SQL identifier");
        }
        return '"' + identifier + '"';
    }

    private record DatabaseIdentity(String databaseName, String currentSchema) {
    }
}
