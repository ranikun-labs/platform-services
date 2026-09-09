package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;

import java.util.Objects;

/**
 * Connection details for one offline source inspection.
 *
 * <p>The password is retained only as a defensive copy and is never included in the textual
 * representation of this value. This configuration is deliberately not a Spring-managed
 * connection-pool contract so an inspector can be invoked without normal application startup.
 */
public final class JdbcSourceConfiguration {

    private final String logicalName;
    private final SourceKind sourceKind;
    private final String jdbcUrl;
    private final String username;
    private final char[] password;

    public JdbcSourceConfiguration(
            String logicalName,
            SourceKind sourceKind,
            String jdbcUrl,
            String username,
            char[] password
    ) {
        this.logicalName = requireText(logicalName, "logicalName");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        this.jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password must not be null").clone();
    }

    public String logicalName() {
        return logicalName;
    }

    public SourceKind sourceKind() {
        return sourceKind;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String username() {
        return username;
    }

    char[] passwordCopy() {
        return password.clone();
    }

    @Override
    public String toString() {
        return "JdbcSourceConfiguration[logicalName=" + logicalName + ", sourceKind=" + sourceKind + "]";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
