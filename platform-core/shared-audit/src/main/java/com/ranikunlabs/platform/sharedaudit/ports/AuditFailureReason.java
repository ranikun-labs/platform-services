package com.ranikunlabs.platform.sharedaudit.ports;

/** Failure reasons backed by the AU-G2 capture boundary. */
public enum AuditFailureReason {
    CAPTURE_MECHANISM_UNAVAILABLE,
    DURABILITY_MECHANISM_UNAVAILABLE,
    CAPTURE_FAILED
}
