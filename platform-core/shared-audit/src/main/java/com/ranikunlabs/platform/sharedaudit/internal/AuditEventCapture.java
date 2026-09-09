package com.ranikunlabs.platform.sharedaudit.internal;

import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy;

/** Internal semantic capture SPI; it is not a Product-facing port. */
public interface AuditEventCapture {

    AuditCaptureCapability capability();

    AuditCaptureResult capture(AuditEvent event, AuditEventPolicy policy);
}
