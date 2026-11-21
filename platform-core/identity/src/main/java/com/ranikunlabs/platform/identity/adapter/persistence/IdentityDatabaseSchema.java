package com.ranikunlabs.platform.identity.adapter.persistence;

import java.util.List;
import java.util.Locale;

/** Canonical PostgreSQL schema owned by Shared Identity. */
public final class IdentityDatabaseSchema {

    public static final String NAME = "identity";
    public static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    public static final List<String> LEGACY_TABLES = List.of(
            "platform_accounts",
            "password_credentials",
            "external_identities",
            "refresh_token",
            "product_clients"
    );
    public static final List<String> TABLES = java.util.stream.Stream.concat(
            LEGACY_TABLES.stream(), java.util.stream.Stream.of("carelog_registration_operation")).toList();

    /** The bounded version/description signature used to detect legacy public Identity state. */
    public static boolean isLegacyIdentityHistorySignature(String version, String description) {
        if (version == null || description == null) {
            return false;
        }
        String normalizedDescription = description.trim().toLowerCase(Locale.ROOT);
        return ("1".equals(version) && "identity schema".equals(normalizedDescription))
                || ("2".equals(version) && "refresh session single account".equals(normalizedDescription));
    }

    private IdentityDatabaseSchema() {
    }
}
