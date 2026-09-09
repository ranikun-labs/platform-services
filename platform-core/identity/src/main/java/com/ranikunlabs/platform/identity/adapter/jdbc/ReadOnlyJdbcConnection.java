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

    private ReadOnlyJdbcConnection(Connection connection) {
        this.connection = connection;
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
            return new ReadOnlyJdbcConnection(connection);
        } catch (SQLException failure) {
            closeAfterOpenFailure(connection, failure);
            throw failure;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
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
            throw failure;
        }
    }

    private static void closeAfterOpenFailure(Connection connection, SQLException failure) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
