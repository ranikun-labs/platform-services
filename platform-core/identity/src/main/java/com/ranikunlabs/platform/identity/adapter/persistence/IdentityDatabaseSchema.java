package com.ranikunlabs.platform.identity.adapter.persistence;

import java.util.List;

/** Canonical PostgreSQL schema owned by Shared Identity. */
public final class IdentityDatabaseSchema {

    public static final String NAME = "identity";
    public static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    public static final List<String> TABLES = List.of(
            "platform_accounts",
            "password_credentials",
            "external_identities",
            "refresh_token",
            "product_clients"
    );

    private IdentityDatabaseSchema() {
    }
}
