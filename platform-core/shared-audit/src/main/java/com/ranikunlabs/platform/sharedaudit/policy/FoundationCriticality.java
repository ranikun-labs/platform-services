package com.ranikunlabs.platform.sharedaudit.policy;

/** Foundation classification mapped one-to-one from the Shared Audit durability class. */
public enum FoundationCriticality {
    SECURITY_CRITICAL,
    SECURITY_DECISION,
    BUSINESS_CRITICAL,
    INFORMATIONAL
}
