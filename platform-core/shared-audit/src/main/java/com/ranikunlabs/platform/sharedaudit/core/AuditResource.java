package com.ranikunlabs.platform.sharedaudit.core;

/** Optional safe primary resource reference. */
public record AuditResource(String resourceType, String resourceId) {

    public AuditResource {
        resourceType = requireText(resourceType, "resourceType");
        resourceId = optionalText(resourceId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
