package com.ranikunlabs.platform.sharedaudit.core;

/** Immutable producer-owned logical occurrence identifier. */
public record AuditEventId(String value) {

    public AuditEventId {
        value = requireText(value, "eventId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
