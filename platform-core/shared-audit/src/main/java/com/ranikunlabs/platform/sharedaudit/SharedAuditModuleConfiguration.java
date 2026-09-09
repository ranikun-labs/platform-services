package com.ranikunlabs.platform.sharedaudit;

import com.ranikunlabs.platform.sharedaudit.adapters.capture.UnavailableAuditEventCapture;
import com.ranikunlabs.platform.sharedaudit.application.DefaultAuditPublisher;
import com.ranikunlabs.platform.sharedaudit.internal.AuditEventCapture;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublisher;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicyRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in hosted composition for the AU-G2 semantic publisher boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.shared-audit.enabled", havingValue = "true")
public class SharedAuditModuleConfiguration {

    @Bean
    AuditEventPolicyRegistry auditEventPolicyRegistry() {
        return AuditEventPolicyRegistry.canonical();
    }

    @Bean
    AuditEventCapture auditEventCapture() {
        return new UnavailableAuditEventCapture();
    }

    @Bean
    AuditPublisher auditPublisher(
            AuditEventPolicyRegistry policyRegistry,
            AuditEventCapture capture
    ) {
        return new DefaultAuditPublisher(policyRegistry, capture);
    }
}
