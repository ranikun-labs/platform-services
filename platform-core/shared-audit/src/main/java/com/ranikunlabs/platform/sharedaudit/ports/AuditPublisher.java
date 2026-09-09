package com.ranikunlabs.platform.sharedaudit.ports;

import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;

/** Framework- and transport-neutral synchronous producer boundary. */
public interface AuditPublisher {

    AuditPublicationResult publish(AuditEvent event);
}
