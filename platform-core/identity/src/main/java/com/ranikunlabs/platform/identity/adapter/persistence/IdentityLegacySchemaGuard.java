package com.ranikunlabs.platform.identity.adapter.persistence;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Prevents schema binding from silently forking an existing public Identity database. */
public final class IdentityLegacySchemaGuard {

    private static final String PUBLIC_SCHEMA = "public";
    private static final String LEGACY_HISTORY_TABLE = IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE;

    /**
     * This method is the FlywayMigrationStrategy seam. It must inspect first and invoke Flyway only
     * after the legacy-state check has passed.
     */
    public void migrate(Flyway flyway) {
        try {
            assertNoLegacyIdentityState(flyway.getConfiguration().getDataSource());
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to verify legacy Shared Identity persistence; Identity migration is blocked.",
                    exception);
        }
        flyway.migrate();
    }

    private void assertNoLegacyIdentityState(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!publicIdentityTables(connection).isEmpty() || hasLegacyIdentityHistory(connection)) {
                throw new IdentityLegacySchemaDetectedException();
            }
        }
    }

    private Set<String> publicIdentityTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        String placeholders = String.join(", ", IdentityDatabaseSchema.TABLES.stream()
                .map(table -> "?")
                .toList());
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "AND table_name IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PUBLIC_SCHEMA);
            int parameter = 2;
            for (String table : IdentityDatabaseSchema.TABLES) {
                statement.setString(parameter++, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
        }
        return tables;
    }

    private boolean hasLegacyIdentityHistory(Connection connection) throws SQLException {
        if (!tableExists(connection, PUBLIC_SCHEMA, LEGACY_HISTORY_TABLE)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version, description FROM public.flyway_schema_history")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (isIdentityMigration(result.getString("version"), result.getString("description"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isIdentityMigration(String version, String description) {
        if (version == null || description == null) {
            return false;
        }
        String normalizedDescription = description.trim().toLowerCase(Locale.ROOT);
        return ("1".equals(version) && "identity schema".equals(normalizedDescription))
                || ("2".equals(version) && "refresh session single account".equals(normalizedDescription));
    }

    private boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
