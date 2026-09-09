package com.ranikunlabs.platform.sharedaudit.ports;

import java.util.Objects;

/** Truthful producer-facing result; it does not claim transport or persistence completion. */
public sealed interface AuditPublicationResult
        permits AuditPublicationResult.Accepted,
        AuditPublicationResult.Rejected,
        AuditPublicationResult.Failed {

    /** Canonical validation and policy passed, and the selected capture accepted custody only. */
    record Accepted() implements AuditPublicationResult {
    }

    /** The event was deterministically rejected before capture. */
    record Rejected(AuditRejectionReason reason) implements AuditPublicationResult {
        public Rejected {
            reason = Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** The required capture mechanism could not accept the event. */
    record Failed(AuditFailureReason reason) implements AuditPublicationResult {
        public Failed {
            reason = Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
