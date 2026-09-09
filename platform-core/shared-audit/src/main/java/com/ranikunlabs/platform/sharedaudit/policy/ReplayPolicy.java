package com.ranikunlabs.platform.sharedaudit.policy;

/** Static replay policy classification. */
public enum ReplayPolicy {
    DURABLE_SOURCE_SAME_EVENT_ID,
    CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
    NO_PRODUCER_RECOVERY_REQUIRED
}
