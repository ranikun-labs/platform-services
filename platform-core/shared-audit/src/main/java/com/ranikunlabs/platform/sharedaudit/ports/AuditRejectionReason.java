package com.ranikunlabs.platform.sharedaudit.ports;

/** Deterministic canonical or registry mismatch identified before capture. */
public enum AuditRejectionReason {
    UNREGISTERED_EVENT_TYPE,
    UNSUPPORTED_SCHEMA_VERSION,
    UNCLASSIFIED_EVENT,
    EVENT_POLICY_MISMATCH,
    ACTOR_CONTEXT_MISMATCH,
    RESOURCE_CONTEXT_MISMATCH,
    ORIGINATING_ACTOR_NOT_ALLOWED,
    DISALLOWED_PAYLOAD_KEY,
    PROHIBITED_PAYLOAD_KEY,
    DISALLOWED_PAYLOAD_VALUE
}
