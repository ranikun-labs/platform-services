package com.ranikunlabs.platform.sharedaudit;

import com.ranikunlabs.platform.sharedaudit.core.AuditAction;
import com.ranikunlabs.platform.sharedaudit.core.AuditActor;
import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventId;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import com.ranikunlabs.platform.sharedaudit.core.ActorType;
import com.ranikunlabs.platform.sharedaudit.core.AuditOutcome;
import com.ranikunlabs.platform.sharedaudit.core.AuditResource;
import com.ranikunlabs.platform.sharedaudit.ports.AuditFailureReason;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublicationResult;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SharedAuditModuleConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SharedAuditModuleConfiguration.class);

    @Test
    void disabledByDefaultHasNoActivePublisherContribution() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditPublisher.class);
        });
    }

    @Test
    void enabledCompositionUsesHonestUnavailableCapture() {
        contextRunner.withPropertyValues("platform.shared-audit.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuditPublisher.class);

            AuditPublicationResult result = context.getBean(AuditPublisher.class).publish(event());
            assertThat(result).isEqualTo(new AuditPublicationResult.Failed(
                    AuditFailureReason.CAPTURE_MECHANISM_UNAVAILABLE));
        });
    }

    private static AuditEvent event() {
        return new AuditEvent(
                new AuditEventId("event-1"),
                new AuditEventType("identity.authentication.login_succeeded"),
                1,
                Instant.parse("2026-09-09T00:00:00Z"),
                "system.shared-identity",
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                null,
                null,
                null,
                new AuditResource("platform_account", "account-1"),
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                null,
                Map.of("authentication_method", "PASSWORD"));
    }
}
