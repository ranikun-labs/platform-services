package com.ranikunlabs.platform.identity.adapter.jdbc;

import java.sql.SQLException;
import java.util.Objects;

/** Safe, source-scoped failure for an offline inspection operation. */
public final class SourceInspectionException extends RuntimeException {

    private final String logicalName;
    private final String operation;
    private final String sqlState;
    private final String category;

    public SourceInspectionException(String logicalName, Throwable failure) {
        this(logicalName, "inspect source", failure);
    }

    public SourceInspectionException(String logicalName, String operation, Throwable failure) {
        super(safeMessage(logicalName, operation, failure));
        this.logicalName = requireText(logicalName, "logicalName");
        this.operation = requireText(operation, "operation");
        this.sqlState = failure instanceof SQLException sqlException
                ? safeSqlState(sqlException.getSQLState())
                : "NOT_SQLSTATE";
        this.category = failure instanceof SQLException sqlException
                ? safeCategory(sqlException.getSQLState())
                : "NON_SQL_FAILURE";
    }

    public String logicalName() {
        return logicalName;
    }

    public String operation() {
        return operation;
    }

    public String sqlState() {
        return sqlState;
    }

    public String category() {
        return category;
    }

    private static String safeMessage(String logicalName, String operation, Throwable failure) {
        String safeLogicalName = requireText(logicalName, "logicalName");
        String safeOperation = requireText(operation, "operation");
        String sqlState = failure instanceof SQLException sqlException
                ? safeSqlState(sqlException.getSQLState())
                : "NOT_SQLSTATE";
        String category = failure instanceof SQLException sqlException
                ? safeCategory(sqlException.getSQLState())
                : "NON_SQL_FAILURE";
        return "Unable to inspect configured source '" + safeLogicalName + "' during "
                + safeOperation + " (sqlState=" + sqlState + ", category=" + category + ")";
    }

    private static String safeSqlState(String value) {
        return value != null && value.matches("[0-9A-Z]{5}") ? value : "UNKNOWN";
    }

    private static String safeCategory(String sqlState) {
        String safeState = safeSqlState(sqlState);
        if (safeState.startsWith("08")) {
            return "CONNECTION_FAILURE";
        }
        if (safeState.startsWith("28")) {
            return "AUTHORIZATION_FAILURE";
        }
        if (safeState.startsWith("3D")) {
            return "DATABASE_FAILURE";
        }
        if (safeState.startsWith("42")) {
            return "STATEMENT_FAILURE";
        }
        return "SQL_FAILURE";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
