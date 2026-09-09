package com.ranikunlabs.platform.sharedaudit.application;

import com.ranikunlabs.platform.sharedaudit.core.AuditAction;
import com.ranikunlabs.platform.sharedaudit.core.AuditActor;
import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventId;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import com.ranikunlabs.platform.sharedaudit.core.ActorType;
import com.ranikunlabs.platform.sharedaudit.core.AuditOutcome;
import com.ranikunlabs.platform.sharedaudit.internal.AuditCaptureCapability;
import com.ranikunlabs.platform.sharedaudit.internal.AuditCaptureResult;
import com.ranikunlabs.platform.sharedaudit.internal.AuditEventCapture;
import com.ranikunlabs.platform.sharedaudit.ports.AuditFailureReason;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublicationResult;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublisher;
import com.ranikunlabs.platform.sharedaudit.ports.AuditRejectionReason;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DefaultAuditPublisherTest {

    @Mock
    private AuditEventCapture capture;

    private AuditPublisher publisher;

    @BeforeEach
    void setUp() {
        lenient().when(capture.capability()).thenReturn(AuditCaptureCapability.DIRECT_BOUNDED_ACK);
        lenient().when(capture.capture(any(), any())).thenReturn(new AuditCaptureResult.Captured());
        publisher = new DefaultAuditPublisher(AuditEventPolicyRegistry.canonical(), capture);
    }

    @Test
    void validEventResolvesPolicyAndCapturesExactlyOnce() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.login_succeeded",
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                Map.of("authentication_method", "PASSWORD")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Accepted());
        verify(capture).capture(any(), any());
    }

    @Test
    void rejectedEventNeverInvokesCapture() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.login_succeeded",
                AuditAction.CREATE,
                AuditOutcome.SUCCEEDED,
                Map.of("authentication_method", "PASSWORD")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.EVENT_POLICY_MISMATCH));
        verifyNoInteractions(capture);
    }

    @Test
    void unknownTypeIsRejectedBeforeCapture() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.unknown.fact",
                AuditAction.CREATE,
                AuditOutcome.SUCCEEDED,
                Map.of()));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.UNREGISTERED_EVENT_TYPE));
        verifyNoInteractions(capture);
    }

    @Test
    void unsupportedVersionIsRejectedBeforeCapture() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.login_succeeded",
                2,
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                Map.of("authentication_method", "PASSWORD")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.UNSUPPORTED_SCHEMA_VERSION));
        verifyNoInteractions(capture);
    }

    @Test
    void disallowedAndProhibitedPayloadKeysAreRejected() {
        AuditPublicationResult disallowed = publisher.publish(event(
                "identity.authentication.login_succeeded",
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                Map.of("unregistered_key", "value")));
        AuditPublicationResult prohibited = publisher.publish(event(
                "identity.authentication.login_succeeded",
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                Map.of("Access-Token", "value")));

        assertThat(disallowed).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.DISALLOWED_PAYLOAD_KEY));
        assertThat(prohibited).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.PROHIBITED_PAYLOAD_KEY));
        verifyNoInteractions(capture);
    }

    @Test
    void closedPayloadValueIsRejected() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.bearer_rejected",
                AuditAction.AUTHENTICATE,
                AuditOutcome.REJECTED,
                Map.of("reason_code", "UNKNOWN")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.DISALLOWED_PAYLOAD_VALUE));
        verifyNoInteractions(capture);
    }

    @Test
    void unavailableCaptureProducesFailedWithoutClaimingAcceptance() {
        when(capture.capability()).thenReturn(AuditCaptureCapability.UNAVAILABLE);

        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.login_succeeded",
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                Map.of("authentication_method", "PASSWORD")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Failed(
                AuditFailureReason.CAPTURE_MECHANISM_UNAVAILABLE));
        verify(capture, never()).capture(any(), any());
    }

    @Test
    void securityCriticalCannotBeAcceptedByDirectCapture() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.account.registered",
                AuditAction.CREATE,
                AuditOutcome.SUCCEEDED,
                Map.of("registration_channel", "PASSWORD")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Failed(
                AuditFailureReason.DURABILITY_MECHANISM_UNAVAILABLE));
        verify(capture, never()).capture(any(), any());
    }

    @Test
    void durableCaptureMayAcceptSecurityCriticalEventOnlyWhenItDeclaresDurability() {
        when(capture.capability()).thenReturn(AuditCaptureCapability.DURABLE_OUTBOX);

        AuditPublicationResult result = publisher.publish(event(
                "identity.credential.password_changed",
                AuditAction.UPDATE,
                AuditOutcome.SUCCEEDED,
                Map.of("change_channel", "SELF_SERVICE")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Accepted());
        verify(capture).capture(any(), any());
    }

    private static AuditEvent event(
            String type,
            AuditAction action,
            AuditOutcome outcome,
            Map<String, String> payload
    ) {
        return event(type, 1, action, outcome, payload);
    }

    private static AuditEvent event(
            String type,
            int version,
            AuditAction action,
            AuditOutcome outcome,
            Map<String, String> payload
    ) {
        return new AuditEvent(
                new AuditEventId("event-1"),
                new AuditEventType(type),
                version,
                Instant.parse("2026-09-09T00:00:00Z"),
                "system.shared-identity",
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                action,
                outcome,
                payload);
    }
}
