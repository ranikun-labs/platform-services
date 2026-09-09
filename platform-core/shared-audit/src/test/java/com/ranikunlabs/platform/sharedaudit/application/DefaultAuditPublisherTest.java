package com.ranikunlabs.platform.sharedaudit.application;

import com.ranikunlabs.platform.sharedaudit.core.AuditAction;
import com.ranikunlabs.platform.sharedaudit.core.AuditActor;
import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventId;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import com.ranikunlabs.platform.sharedaudit.core.AuditResource;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void loginRejectedAuthenticatedContextIsRejectedBeforeCapture() {
        AuditPublicationResult result = publisher.publish(eventWithContext(
                "identity.authentication.login_rejected",
                AuditAction.AUTHENTICATE,
                AuditOutcome.REJECTED,
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                new AuditResource("platform_account", "account-1"),
                null,
                Map.of("rejection_reason", "CREDENTIALS_REJECTED")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.ACTOR_CONTEXT_MISMATCH));
        verify(capture, never()).capture(any(), any());
    }

    @Test
    void anonymousLoginRejectionCannotCarryRolesOrOriginatingAttribution() {
        AuditPublicationResult rolesResult = publisher.publish(eventWithContext(
                "identity.authentication.login_rejected",
                AuditAction.AUTHENTICATE,
                AuditOutcome.REJECTED,
                new AuditActor(ActorType.ANONYMOUS, null, Set.of("UNTRUSTED_CONTEXT")),
                null,
                null,
                Map.of("rejection_reason", "CREDENTIALS_REJECTED")));
        AuditPublicationResult originatingActorResult = publisher.publish(eventWithContext(
                "identity.authentication.login_rejected",
                AuditAction.AUTHENTICATE,
                AuditOutcome.REJECTED,
                new AuditActor(ActorType.ANONYMOUS, null, Set.of()),
                null,
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                Map.of("rejection_reason", "CREDENTIALS_REJECTED")));

        assertThat(rolesResult).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.ACTOR_CONTEXT_MISMATCH));
        assertThat(originatingActorResult).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.ORIGINATING_ACTOR_NOT_ALLOWED));
        verifyNoInteractions(capture);
    }

    @Test
    void accountResourceMustMatchAuthenticatedActorWhenCanonicalPolicyRequiresIt() {
        AuditPublicationResult result = publisher.publish(eventWithContext(
                "identity.authentication.login_succeeded",
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                new AuditResource("platform_account", "account-2"),
                null,
                Map.of("authentication_method", "PASSWORD")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.RESOURCE_CONTEXT_MISMATCH));
        verify(capture, never()).capture(any(), any());
    }

    @Test
    void passwordChangedRequiresCanonicalTargetResourceBeforeCapture() {
        AuditPublicationResult missingResource = publisher.publish(passwordChanged(null));
        AuditPublicationResult wrongResourceType = publisher.publish(passwordChanged(
                new AuditResource("oauth_principal", "account-1")));
        AuditPublicationResult missingResourceId = publisher.publish(passwordChanged(
                new AuditResource("platform_account", null)));

        assertThat(missingResource).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.RESOURCE_CONTEXT_MISMATCH));
        assertThat(wrongResourceType).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.RESOURCE_CONTEXT_MISMATCH));
        assertThat(missingResourceId).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.RESOURCE_CONTEXT_MISMATCH));
        verify(capture, never()).capture(any(), any());
    }

    @Test
    void validPasswordChangedContextReachesSecurityCriticalDurabilityGuard() {
        AuditPublicationResult result = publisher.publish(passwordChanged(
                new AuditResource("platform_account", "account-1")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Failed(
                AuditFailureReason.DURABILITY_MECHANISM_UNAVAILABLE));
        verify(capture, never()).capture(any(), any());
    }

    @Test
    void loginRejectedAcceptsOnlyCanonicalNonEnumeratingReason() {
        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.login_rejected",
                AuditAction.AUTHENTICATE,
                AuditOutcome.REJECTED,
                Map.of("rejection_reason", "CREDENTIALS_REJECTED")));

        assertThat(result).isEqualTo(new AuditPublicationResult.Accepted());
        verify(capture).capture(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_EXISTS", "ACCOUNT_NOT_FOUND", "WRONG_PASSWORD", "NEW_REASON"})
    void loginRejectedEnumerationOrArbitraryReasonIsRejectedBeforeCapture(String reason) {
        AuditPublicationResult result = publisher.publish(event(
                "identity.authentication.login_rejected",
                AuditAction.AUTHENTICATE,
                AuditOutcome.REJECTED,
                Map.of("rejection_reason", reason)));

        assertThat(result).isEqualTo(new AuditPublicationResult.Rejected(
                AuditRejectionReason.DISALLOWED_PAYLOAD_VALUE));
        verifyNoInteractions(capture);
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
        return eventWithContext(
                type,
                version,
                action,
                outcome,
                canonicalActor(type),
                canonicalResource(type),
                null,
                payload);
    }

    private static AuditEvent passwordChanged(AuditResource resource) {
        return eventWithContext(
                "identity.credential.password_changed",
                AuditAction.UPDATE,
                AuditOutcome.SUCCEEDED,
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                resource,
                null,
                Map.of("change_channel", "SELF_SERVICE"));
    }

    private static AuditEvent eventWithContext(
            String type,
            AuditAction action,
            AuditOutcome outcome,
            AuditActor actor,
            AuditResource resource,
            AuditActor originatingActor,
            Map<String, String> payload
    ) {
        return eventWithContext(
                type,
                1,
                action,
                outcome,
                actor,
                resource,
                originatingActor,
                payload);
    }

    private static AuditEvent eventWithContext(
            String type,
            int version,
            AuditAction action,
            AuditOutcome outcome,
            AuditActor actor,
            AuditResource resource,
            AuditActor originatingActor,
            Map<String, String> payload
    ) {
        return new AuditEvent(
                new AuditEventId("event-1"),
                new AuditEventType(type),
                version,
                Instant.parse("2026-09-09T00:00:00Z"),
                "system.shared-identity",
                actor,
                originatingActor,
                null,
                null,
                resource,
                action,
                outcome,
                null,
                null,
                null,
                payload);
    }

    private static AuditActor canonicalActor(String type) {
        return switch (type) {
            case "identity.authentication.login_rejected",
                    "identity.authentication.refresh_rejected",
                    "identity.oauth.authentication_rejected",
                    "identity.oauth.principal_verified_unlinked",
                    "identity.oauth.authorization_started",
                    "identity.authentication.bearer_rejected" ->
                    new AuditActor(ActorType.ANONYMOUS, null, Set.of());
            default -> new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of());
        };
    }

    private static AuditResource canonicalResource(String type) {
        return switch (type) {
            case "identity.authentication.login_rejected",
                    "identity.authentication.refresh_rejected",
                    "identity.oauth.authentication_rejected",
                    "identity.oauth.principal_verified_unlinked",
                    "identity.oauth.authorization_started",
                    "identity.authentication.bearer_rejected" -> null;
            default -> new AuditResource("platform_account", "account-1");
        };
    }
}
