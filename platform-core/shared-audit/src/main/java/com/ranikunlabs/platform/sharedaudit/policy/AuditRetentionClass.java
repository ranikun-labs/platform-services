package com.ranikunlabs.platform.sharedaudit.policy;

/** Retention class, without asserting a concrete duration. */
public enum AuditRetentionClass {
    SECURITY_LONG,
    SECURITY_SHORT,
    BUSINESS_LONG,
    INFORMATIONAL_SHORT
}
