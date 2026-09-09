package com.ranikunlabs.platform.sharedaudit.internal;

import com.ranikunlabs.platform.sharedaudit.policy.AuditDurabilityClass;

/** Capture capability declared by the internal mechanism selected by the publisher. */
public enum AuditCaptureCapability {
    UNAVAILABLE {
        @Override
        public boolean supports(AuditDurabilityClass durabilityClass) {
            return false;
        }
    },
    DIRECT_BOUNDED_ACK {
        @Override
        public boolean supports(AuditDurabilityClass durabilityClass) {
            return durabilityClass != AuditDurabilityClass.SECURITY_CRITICAL;
        }
    },
    DURABLE_OUTBOX {
        @Override
        public boolean supports(AuditDurabilityClass durabilityClass) {
            return true;
        }
    };

    public abstract boolean supports(AuditDurabilityClass durabilityClass);
}
