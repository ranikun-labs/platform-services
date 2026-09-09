package com.ranikunlabs.platform.sharedaudit.core;

import java.util.regex.Pattern;

/** Canonical lower-case, dot-namespaced forensic fact name. */
public record AuditEventType(String value) {

    private static final Pattern CANONICAL_NAME = Pattern.compile(
            "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}");

    public AuditEventType {
        if (value == null || !CANONICAL_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "eventType must be lower-case dot-namespaced with at least three segments");
        }
    }
}
