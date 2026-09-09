package com.ranikunlabs.platform.sharedaudit.application;

import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.core.AuditActor;
import com.ranikunlabs.platform.sharedaudit.core.ActorType;
import com.ranikunlabs.platform.sharedaudit.internal.AuditCaptureCapability;
import com.ranikunlabs.platform.sharedaudit.internal.AuditCaptureResult;
import com.ranikunlabs.platform.sharedaudit.internal.AuditEventCapture;
import com.ranikunlabs.platform.sharedaudit.ports.AuditFailureReason;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublicationResult;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublisher;
import com.ranikunlabs.platform.sharedaudit.ports.AuditRejectionReason;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicyRegistry;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy.OriginatingActorRule;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy.ResourcePresence;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates and captures an event without owning transport, persistence, or transaction timing. */
public final class DefaultAuditPublisher implements AuditPublisher {

    private static final Set<String> PROHIBITED_KEY_FRAGMENTS = Set.of(
            "password",
            "token",
            "jwt",
            "authorization",
            "oauthstate",
            "oauthcode",
            "pkce",
            "nonce",
            "secret",
            "loginid",
            "email",
            "providersubject",
            "credentialfingerprint");

    private final AuditEventPolicyRegistry policyRegistry;
    private final AuditEventCapture capture;

    public DefaultAuditPublisher(AuditEventPolicyRegistry policyRegistry, AuditEventCapture capture) {
        this.policyRegistry = Objects.requireNonNull(policyRegistry, "policyRegistry must not be null");
        this.capture = Objects.requireNonNull(capture, "capture must not be null");
    }

    @Override
    public AuditPublicationResult publish(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        AuditEventPolicyRegistry.PolicyResolution resolution = policyRegistry.resolve(
                event.eventType(), event.schemaVersion());
        if (resolution.status() != AuditEventPolicyRegistry.PolicyResolutionStatus.REGISTERED) {
            return new AuditPublicationResult.Rejected(mapRejection(resolution.status()));
        }

        AuditEventPolicy policy = resolution.policy();
        if (event.action() != policy.action() || event.outcome() != policy.outcome()) {
            return new AuditPublicationResult.Rejected(AuditRejectionReason.EVENT_POLICY_MISMATCH);
        }

        AuditPublicationResult contextValidation = validateContext(event, policy);
        if (contextValidation != null) {
            return contextValidation;
        }

        AuditPublicationResult payloadValidation = validatePayload(event.payload(), policy);
        if (payloadValidation != null) {
            return payloadValidation;
        }

        AuditCaptureCapability capability = Objects.requireNonNull(
                capture.capability(), "capture capability must not be null");
        if (!capability.supports(policy.durabilityClass())) {
            return new AuditPublicationResult.Failed(
                    policy.requiresDurableCapture()
                            ? AuditFailureReason.DURABILITY_MECHANISM_UNAVAILABLE
                            : AuditFailureReason.CAPTURE_MECHANISM_UNAVAILABLE);
        }

        AuditCaptureResult captureResult = Objects.requireNonNull(
                capture.capture(event, policy), "capture result must not be null");
        if (captureResult instanceof AuditCaptureResult.Captured) {
            return new AuditPublicationResult.Accepted();
        }
        if (captureResult instanceof AuditCaptureResult.Unavailable) {
            return new AuditPublicationResult.Failed(
                    policy.requiresDurableCapture()
                            ? AuditFailureReason.DURABILITY_MECHANISM_UNAVAILABLE
                            : AuditFailureReason.CAPTURE_MECHANISM_UNAVAILABLE);
        }
        return new AuditPublicationResult.Failed(AuditFailureReason.CAPTURE_FAILED);
    }

    private AuditPublicationResult validateContext(AuditEvent event, AuditEventPolicy policy) {
        AuditEventPolicy.ActorConstraint actorConstraint = policy.actorConstraint();
        if (!actorConstraint.allowedActorTypes().contains(event.actor().actorType())
                || (event.actor().actorType() == ActorType.ANONYMOUS
                && !actorConstraint.anonymousRolesAllowed()
                && !event.actor().roles().isEmpty())) {
            return new AuditPublicationResult.Rejected(AuditRejectionReason.ACTOR_CONTEXT_MISMATCH);
        }

        AuditEventPolicy.ResourceConstraint resourceConstraint = policy.resourceConstraint();
        if (!validResource(event, resourceConstraint)) {
            return new AuditPublicationResult.Rejected(AuditRejectionReason.RESOURCE_CONTEXT_MISMATCH);
        }

        AuditActor originatingActor = event.originatingActor();
        if (originatingActor != null) {
            if (policy.originatingActorRule() == OriginatingActorRule.FORBIDDEN
                    || event.actor().actorType() == ActorType.ANONYMOUS
                    || originatingActor.actorType() == ActorType.ANONYMOUS) {
                return new AuditPublicationResult.Rejected(AuditRejectionReason.ORIGINATING_ACTOR_NOT_ALLOWED);
            }
            if (policy.originatingActorRule() == OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL
                    && samePrincipal(event.actor(), originatingActor)) {
                return new AuditPublicationResult.Rejected(AuditRejectionReason.ORIGINATING_ACTOR_NOT_ALLOWED);
            }
        }
        return null;
    }

    private static boolean samePrincipal(AuditActor first, AuditActor second) {
        return first.actorType() == second.actorType()
                && Objects.equals(first.actorId(), second.actorId());
    }

    private static boolean validResource(
            AuditEvent event,
            AuditEventPolicy.ResourceConstraint constraint
    ) {
        boolean present = event.resource() != null;
        if (constraint.presence() == ResourcePresence.FORBIDDEN && present) {
            return false;
        }
        if (constraint.presence() == ResourcePresence.REQUIRED && !present) {
            return false;
        }
        if (!present) {
            return true;
        }

        if (!constraint.allowedResourceTypes().contains(event.resource().resourceType())) {
            return false;
        }
        if (constraint.resourceIdRequired() && event.resource().resourceId() == null) {
            return false;
        }
        return !constraint.resourceIdMustMatchActorId()
                || Objects.equals(event.resource().resourceId(), event.actor().actorId());
    }

    private AuditPublicationResult validatePayload(
            Map<String, String> payload,
            AuditEventPolicy policy
    ) {
        for (String key : payload.keySet()) {
            if (isProhibitedPayloadKey(key)) {
                return new AuditPublicationResult.Rejected(AuditRejectionReason.PROHIBITED_PAYLOAD_KEY);
            }
            if (!policy.allowedPayloadKeys().contains(key)) {
                return new AuditPublicationResult.Rejected(AuditRejectionReason.DISALLOWED_PAYLOAD_KEY);
            }
            Set<String> allowedValues = policy.allowedPayloadValues().get(key);
            if (allowedValues != null && !allowedValues.contains(payload.get(key))) {
                return new AuditPublicationResult.Rejected(AuditRejectionReason.DISALLOWED_PAYLOAD_VALUE);
            }
        }
        return null;
    }

    private static AuditRejectionReason mapRejection(
            AuditEventPolicyRegistry.PolicyResolutionStatus status
    ) {
        return switch (status) {
            case UNREGISTERED_EVENT_TYPE -> AuditRejectionReason.UNREGISTERED_EVENT_TYPE;
            case UNSUPPORTED_SCHEMA_VERSION -> AuditRejectionReason.UNSUPPORTED_SCHEMA_VERSION;
            case UNCLASSIFIED_EVENT -> AuditRejectionReason.UNCLASSIFIED_EVENT;
            case REGISTERED -> throw new IllegalArgumentException("registered policy is not a rejection");
        };
    }

    private static boolean isProhibitedPayloadKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return PROHIBITED_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }
}
