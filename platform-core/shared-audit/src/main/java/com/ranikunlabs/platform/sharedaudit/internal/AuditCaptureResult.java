package com.ranikunlabs.platform.sharedaudit.internal;

import java.util.Objects;

/** Internal capture outcome mapped by the publisher to the public truthful result. */
public sealed interface AuditCaptureResult
        permits AuditCaptureResult.Captured,
        AuditCaptureResult.Unavailable,
        AuditCaptureResult.Failed {

    record Captured() implements AuditCaptureResult {
    }

    record Unavailable() implements AuditCaptureResult {
    }

    record Failed(String reason) implements AuditCaptureResult {
        public Failed {
            reason = Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
