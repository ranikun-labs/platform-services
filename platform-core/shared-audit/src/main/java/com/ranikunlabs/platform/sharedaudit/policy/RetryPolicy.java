package com.ranikunlabs.platform.sharedaudit.policy;

/** Static retry policy classification. */
public enum RetryPolicy {
    DURABLE_UNTIL_POLICY_EXHAUSTION,
    DIRECT_BOUNDED_ACK,
    BEST_EFFORT_BOUNDED
}
