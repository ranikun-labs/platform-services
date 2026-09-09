package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * JDBC session that is opened and marked read-only before any inspection query is run.
 *
 * <p>The transaction-level PostgreSQL guard is intentional. The inspector also contains only
 * metadata/history SELECT statements, so the connection flag is defense in depth rather than the
 * sole read-only guarantee.
 */
public final class ReadOnlyJdbcConnection implements AutoCloseable {

    private final Connection connection;
    private final String logicalName;

    private ReadOnlyJdbcConnection(Connection connection, String logicalName) {
        this.connection = connection;
        this.logicalName = logicalName;
    }

    public static ReadOnlyJdbcConnection open(JdbcSourceConfiguration source) throws SQLException {
        char[] password = source.passwordCopy();
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(
                    source.jdbcUrl(), source.username(), new String(password));
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
            }
            return new ReadOnlyJdbcConnection(connection, source.logicalName());
        } catch (SQLException failure) {
            closeAfterOpenFailure(connection);
            throw new SourceInspectionException(
                    source.logicalName(), "open read-only JDBC session", failure);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public boolean isReadOnly() {
        try {
            return connection.isReadOnly();
        } catch (SQLException failure) {
            throw new SourceInspectionException(logicalName, "read JDBC session mode", failure);
        }
    }

    Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure = rollbackFailure;
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw new SourceInspectionException(logicalName, "close read-only JDBC session", failure);
        }
    }

    private static void closeAfterOpenFailure(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Never attach the driver's URL-bearing diagnostic as a public suppressed cause.
        }
    }
}
