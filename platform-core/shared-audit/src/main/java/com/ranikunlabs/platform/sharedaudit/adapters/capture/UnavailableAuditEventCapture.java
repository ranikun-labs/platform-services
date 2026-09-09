package com.ranikunlabs.platform.sharedaudit.adapters.capture;

import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.internal.AuditCaptureCapability;
import com.ranikunlabs.platform.sharedaudit.internal.AuditCaptureResult;
import com.ranikunlabs.platform.sharedaudit.internal.AuditEventCapture;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy;

/** Explicit AU-G2 default: no capture mechanism is installed before later gates. */
public final class UnavailableAuditEventCapture implements AuditEventCapture {

    @Override
    public AuditCaptureCapability capability() {
        return AuditCaptureCapability.UNAVAILABLE;
    }

    @Override
    public AuditCaptureResult capture(AuditEvent event, AuditEventPolicy policy) {
        return new AuditCaptureResult.Unavailable();
    }
}
