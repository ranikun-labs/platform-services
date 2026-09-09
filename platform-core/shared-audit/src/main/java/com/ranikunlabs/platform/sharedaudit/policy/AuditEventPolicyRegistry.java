package com.ranikunlabs.platform.sharedaudit.policy;

import com.ranikunlabs.platform.sharedaudit.core.AuditAction;
import com.ranikunlabs.platform.sharedaudit.core.ActorType;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import com.ranikunlabs.platform.sharedaudit.core.AuditOutcome;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy.OriginatingActorRule;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy.ResourcePresence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable AU-G1 policy registry keyed by event type and schema version. */
public final class AuditEventPolicyRegistry {

    private final Map<PolicyKey, AuditEventPolicy> policies;
    private final Map<AuditEventType, Set<Integer>> versionsByEventType;

    public AuditEventPolicyRegistry() {
        this(canonicalPolicies());
    }

    private AuditEventPolicyRegistry(Collection<AuditEventPolicy> policies) {
        Map<PolicyKey, AuditEventPolicy> byKey = new LinkedHashMap<>();
        Map<AuditEventType, Set<Integer>> versions = new LinkedHashMap<>();
        for (AuditEventPolicy policy : policies) {
            PolicyKey key = new PolicyKey(policy.eventType(), policy.schemaVersion());
            if (byKey.put(key, policy) != null) {
                throw new IllegalArgumentException("duplicate policy: " + key);
            }
            versions.computeIfAbsent(policy.eventType(), ignored -> new LinkedHashSet<>())
                    .add(policy.schemaVersion());
        }
        this.policies = Map.copyOf(byKey);
        this.versionsByEventType = versions.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    public static AuditEventPolicyRegistry canonical() {
        return new AuditEventPolicyRegistry(canonicalPolicies());
    }

    public PolicyResolution resolve(AuditEventType eventType, int schemaVersion) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (!versionsByEventType.containsKey(eventType)) {
            return new PolicyResolution(PolicyResolutionStatus.UNREGISTERED_EVENT_TYPE, null);
        }
        AuditEventPolicy policy = policies.get(new PolicyKey(eventType, schemaVersion));
        if (policy == null) {
            return new PolicyResolution(PolicyResolutionStatus.UNSUPPORTED_SCHEMA_VERSION, null);
        }
        return new PolicyResolution(PolicyResolutionStatus.REGISTERED, policy);
    }

    public Set<AuditEventType> registeredEventTypes() {
        return Set.copyOf(versionsByEventType.keySet());
    }

    public List<AuditEventPolicy> policies() {
        return List.copyOf(policies.values());
    }

    public enum PolicyResolutionStatus {
        REGISTERED,
        UNREGISTERED_EVENT_TYPE,
        UNSUPPORTED_SCHEMA_VERSION,
        UNCLASSIFIED_EVENT
    }

    public record PolicyResolution(PolicyResolutionStatus status, AuditEventPolicy policy) {
        public PolicyResolution {
            status = Objects.requireNonNull(status, "status must not be null");
            if (status == PolicyResolutionStatus.REGISTERED && policy == null) {
                throw new IllegalArgumentException("registered resolution requires a policy");
            }
            if (status != PolicyResolutionStatus.REGISTERED && policy != null) {
                throw new IllegalArgumentException("non-registered resolution must not carry a policy");
            }
        }
    }

    private record PolicyKey(AuditEventType eventType, int schemaVersion) {
    }

    private static List<AuditEventPolicy> canonicalPolicies() {
        return List.of(
                policy(
                        "identity.authentication.login_succeeded",
                        AuditAction.AUTHENTICATE,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.STATE_CHECK_ONLY_NO_FACT_RECONSTRUCTION,
                        Set.of("authentication_method"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER),
                        accountResource(ResourcePresence.REQUIRED, true),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Successful authentication decisions remain attributable",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.authentication.login_rejected",
                        AuditAction.AUTHENTICATE,
                        AuditOutcome.REJECTED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("rejection_reason"),
                        Map.of("rejection_reason", Set.of("CREDENTIALS_REJECTED")),
                        actors(ActorType.ANONYMOUS),
                        noResource(),
                        OriginatingActorRule.FORBIDDEN,
                        "Rejected authentication attempts remain visible without account enumeration",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.authentication.refresh_rejected",
                        AuditAction.REFRESH,
                        AuditOutcome.REJECTED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("rejection_reason"),
                        Map.of(),
                        actors(ActorType.ANONYMOUS),
                        noResource(),
                        OriginatingActorRule.FORBIDDEN,
                        "Refresh rejection decisions remain attributable without exposing credentials",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.authentication.access_token_revoked",
                        AuditAction.REVOKE,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.SIDE_EFFECT_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("revocation_scope"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER),
                        accountResource(ResourcePresence.REQUIRED, true),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "A successful current-access revocation remains forensically visible",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.authentication.refresh_sessions_revoked",
                        AuditAction.REVOKE,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_CRITICAL,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.NONE_AFTER_COMMIT,
                        RetryPolicy.DURABLE_UNTIL_POLICY_EXHAUSTION,
                        ReplayPolicy.DURABLE_SOURCE_SAME_EVENT_ID,
                        ReconciliationPolicy.PRODUCER_DURABLE_PUBLICATION_STATE,
                        Set.of("session_scope"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER),
                        accountResource(ResourcePresence.REQUIRED, true),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Committed refresh-session revocation is never silently lost",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.oauth.authentication_succeeded",
                        AuditAction.AUTHENTICATE,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.STATE_CHECK_ONLY_NO_FACT_RECONSTRUCTION,
                        Set.of("provider_code", "authentication_method"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER),
                        accountResource(ResourcePresence.REQUIRED, true),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Successful OAuth authentication decisions remain attributable",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.oauth.authentication_rejected",
                        AuditAction.AUTHENTICATE,
                        AuditOutcome.REJECTED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("provider_code", "rejection_reason"),
                        Map.of(),
                        actors(ActorType.ANONYMOUS),
                        noResource(),
                        OriginatingActorRule.FORBIDDEN,
                        "OAuth rejection decisions remain visible without principal enumeration",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.oauth.principal_verified_unlinked",
                        AuditAction.VERIFY,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("provider_code", "linkage_status"),
                        Map.of("linkage_status", Set.of("UNLINKED")),
                        actors(ActorType.ANONYMOUS),
                        noResource(),
                        OriginatingActorRule.FORBIDDEN,
                        "Verified external principals lacking an account link remain visible without identifying the principal",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.account.registered",
                        AuditAction.CREATE,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_CRITICAL,
                        AuditRetentionClass.SECURITY_LONG,
                        AcceptableLossWindow.NONE_AFTER_COMMIT,
                        RetryPolicy.DURABLE_UNTIL_POLICY_EXHAUSTION,
                        ReplayPolicy.DURABLE_SOURCE_SAME_EVENT_ID,
                        ReconciliationPolicy.PRODUCER_DURABLE_PUBLICATION_STATE,
                        Set.of("registration_channel"),
                        Map.of(),
                        actors(ActorType.ANONYMOUS, ActorType.AUTHENTICATED_USER, ActorType.SYSTEM),
                        accountResource(ResourcePresence.REQUIRED, false),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Every committed account-lifecycle creation has durable forensic evidence",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109 owner-settled baseline"),
                policy(
                        "identity.credential.password_changed",
                        AuditAction.UPDATE,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.MUST_AUDIT,
                        AuditDurabilityClass.SECURITY_CRITICAL,
                        AuditRetentionClass.SECURITY_LONG,
                        AcceptableLossWindow.NONE_AFTER_COMMIT,
                        RetryPolicy.DURABLE_UNTIL_POLICY_EXHAUSTION,
                        ReplayPolicy.DURABLE_SOURCE_SAME_EVENT_ID,
                        ReconciliationPolicy.PRODUCER_DURABLE_PUBLICATION_STATE,
                        Set.of("change_channel", "change_reason"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER, ActorType.SYSTEM),
                        accountResource(ResourcePresence.REQUIRED, false),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Every committed password mutation has durable forensic evidence",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109 owner-settled baseline"),
                policy(
                        "identity.authentication.refresh_succeeded",
                        AuditAction.REFRESH,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.SHOULD_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.STATE_CHECK_ONLY_NO_FACT_RECONSTRUCTION,
                        Set.of("authentication_method"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER),
                        accountResource(ResourcePresence.REQUIRED, true),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Successful refresh decisions can be investigated without making refresh availability fail-closed",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.oauth.authorization_started",
                        AuditAction.INITIATE_AUTHORIZATION,
                        AuditOutcome.SUCCEEDED,
                        AuditInclusion.SHOULD_AUDIT,
                        AuditDurabilityClass.INFORMATIONAL,
                        AuditRetentionClass.INFORMATIONAL_SHORT,
                        AcceptableLossWindow.BOUNDED_BEST_EFFORT,
                        RetryPolicy.BEST_EFFORT_BOUNDED,
                        ReplayPolicy.NO_PRODUCER_RECOVERY_REQUIRED,
                        ReconciliationPolicy.NONE_REQUIRED,
                        Set.of("provider_code", "client_channel"),
                        Map.of(),
                        actors(ActorType.ANONYMOUS),
                        noResource(),
                        OriginatingActorRule.FORBIDDEN,
                        "High-level authorization initiation can be correlated when available",
                        "Shared Identity Domain Owner",
                        "RPL-109"),
                policy(
                        "identity.authorization.product_access_rejected",
                        AuditAction.AUTHORIZE,
                        AuditOutcome.REJECTED,
                        AuditInclusion.SHOULD_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("reason_code"),
                        Map.of(),
                        actors(ActorType.AUTHENTICATED_USER, ActorType.SYSTEM),
                        accountResource(ResourcePresence.OPTIONAL, false),
                        OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL,
                        "Material authorization denials remain attributable",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"),
                policy(
                        "identity.authentication.bearer_rejected",
                        AuditAction.AUTHENTICATE,
                        AuditOutcome.REJECTED,
                        AuditInclusion.SHOULD_AUDIT,
                        AuditDurabilityClass.SECURITY_DECISION,
                        AuditRetentionClass.SECURITY_SHORT,
                        AcceptableLossWindow.OCCURRENCE_TO_ACK_RESIDUAL,
                        RetryPolicy.DIRECT_BOUNDED_ACK,
                        ReplayPolicy.CAPTURED_EVENT_SAME_EVENT_ID_ONLY,
                        ReconciliationPolicy.NO_SAFE_RECONSTRUCTION,
                        Set.of("reason_code"),
                        Map.of("reason_code", Set.of("REVOKED", "REPLAY", "POLICY")),
                        actors(ActorType.ANONYMOUS),
                        noResource(),
                        OriginatingActorRule.FORBIDDEN,
                        "High-signal bearer abuse or revocation decisions remain visible",
                        "Shared Identity Domain Owner + Architecture/Security Review",
                        "ADR-0021 / DEC-070 / RPL-109"));
    }

    private static AuditEventPolicy policy(
            String eventType,
            AuditAction action,
            AuditOutcome outcome,
            AuditInclusion inclusion,
            AuditDurabilityClass durabilityClass,
            AuditRetentionClass retentionClass,
            AcceptableLossWindow lossWindow,
            RetryPolicy retryPolicy,
            ReplayPolicy replayPolicy,
            ReconciliationPolicy reconciliationPolicy,
            Set<String> allowedPayloadKeys,
            Map<String, Set<String>> allowedPayloadValues,
            AuditEventPolicy.ActorConstraint actorConstraint,
            AuditEventPolicy.ResourceConstraint resourceConstraint,
            OriginatingActorRule originatingActorRule,
            String affectedBusinessInvariant,
            String classificationOwner,
            String approvalReference
    ) {
        return new AuditEventPolicy(
                new AuditEventType(eventType),
                1,
                affectedBusinessInvariant,
                inclusion,
                durabilityClass,
                FoundationCriticality.valueOf(durabilityClass.name()),
                retentionClass,
                lossWindow,
                DuplicateTolerance.IDEMPOTENT_SAME_ID_SAME_CONTENT,
                retryPolicy,
                replayPolicy,
                reconciliationPolicy,
                classificationOwner,
                approvalReference,
                action,
                outcome,
                actorConstraint,
                resourceConstraint,
                originatingActorRule,
                allowedPayloadKeys,
                allowedPayloadValues);
    }

    private static AuditEventPolicy.ActorConstraint actors(ActorType... actorTypes) {
        return new AuditEventPolicy.ActorConstraint(Set.of(actorTypes), false);
    }

    private static AuditEventPolicy.ResourceConstraint accountResource(
            ResourcePresence presence,
            boolean resourceIdMustMatchActorId
    ) {
        return new AuditEventPolicy.ResourceConstraint(
                presence,
                Set.of("platform_account"),
                true,
                resourceIdMustMatchActorId);
    }

    private static AuditEventPolicy.ResourceConstraint noResource() {
        return new AuditEventPolicy.ResourceConstraint(
                ResourcePresence.FORBIDDEN,
                Set.of(),
                false,
                false);
    }
}
