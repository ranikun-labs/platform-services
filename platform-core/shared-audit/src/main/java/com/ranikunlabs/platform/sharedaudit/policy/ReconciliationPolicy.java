package com.ranikunlabs.platform.sharedaudit.policy;

/** Static reconciliation policy classification. */
public enum ReconciliationPolicy {
    PRODUCER_DURABLE_PUBLICATION_STATE,
    STATE_CHECK_ONLY_NO_FACT_RECONSTRUCTION,
    NO_SAFE_RECONSTRUCTION,
    NONE_REQUIRED
}
