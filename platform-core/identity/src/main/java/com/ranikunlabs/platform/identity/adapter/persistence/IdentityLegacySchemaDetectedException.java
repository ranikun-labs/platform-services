package com.ranikunlabs.platform.identity.adapter.persistence;

/** Signals unreconciled pre-Slice 1 Identity persistence in PostgreSQL's public schema. */
public class IdentityLegacySchemaDetectedException extends IllegalStateException {

    public IdentityLegacySchemaDetectedException() {
        super("Legacy Shared Identity persistence was detected in public; automatic identity "
                + "schema binding is blocked. Run the controlled G3 migration/reconciliation before startup.");
    }
}
