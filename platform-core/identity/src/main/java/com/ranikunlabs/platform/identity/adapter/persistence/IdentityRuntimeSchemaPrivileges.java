package com.ranikunlabs.platform.identity.adapter.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Reconciles runtime DML after migrations without exposing Flyway history to the application role. */
final class IdentityRuntimeSchemaPrivileges {

    private static final String RUNTIME_TABLE_PRIVILEGES = "SELECT, INSERT, UPDATE, DELETE";

    private IdentityRuntimeSchemaPrivileges() {
    }

    static void reconcile(DataSource migrationDataSource, String runtimeRole) {
        String quotedRuntimeRole = quoteIdentifier(runtimeRole);
        try (Connection connection = migrationDataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("GRANT USAGE ON SCHEMA " + quoteIdentifier(IdentityDatabaseSchema.NAME)
                        + " TO " + quotedRuntimeRole);
            }
            for (String table : tables(connection)) {
                if (!IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE.equals(table)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("GRANT " + RUNTIME_TABLE_PRIVILEGES + " ON TABLE "
                                + qualifiedIdentityName(table) + " TO " + quotedRuntimeRole);
                    }
                }
            }
            for (String sequence : sequences(connection)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("GRANT USAGE ON SEQUENCE "
                            + qualifiedIdentityName(sequence) + " TO " + quotedRuntimeRole);
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("REVOKE ALL ON TABLE "
                        + qualifiedIdentityName(IdentityDatabaseSchema.FLYWAY_HISTORY_TABLE)
                        + " FROM " + quotedRuntimeRole);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not reconcile Identity runtime schema privileges", exception);
        }
    }

    private static List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = '" + IdentityDatabaseSchema.NAME + "' "
                             + "AND table_type = 'BASE TABLE'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private static List<String> sequences(Connection connection) throws SQLException {
        List<String> sequences = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT sequence_name FROM information_schema.sequences "
                             + "WHERE sequence_schema = '" + IdentityDatabaseSchema.NAME + "'")) {
            while (result.next()) {
                sequences.add(result.getString(1));
            }
        }
        return sequences;
    }

    private static String qualifiedIdentityName(String objectName) {
        return quoteIdentifier(IdentityDatabaseSchema.NAME) + "." + quoteIdentifier(objectName);
    }

    private static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Database role and object identifiers must not be blank");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
