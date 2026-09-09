package com.ranikunlabs.platform.sharedaudit.core;

/** Optional but structurally complete tenant reference. */
public record AuditTenant(String tenantType, String tenantId) {

    public AuditTenant {
        tenantType = requireText(tenantType, "tenantType");
        tenantId = requireText(tenantId, "tenantId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
