package com.ranikunlabs.platform.sharedaudit.policy;

import com.ranikunlabs.platform.sharedaudit.core.AuditAction;
import com.ranikunlabs.platform.sharedaudit.core.ActorType;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import com.ranikunlabs.platform.sharedaudit.core.AuditOutcome;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable canonical static policy metadata for one event type and schema version. */
public record AuditEventPolicy(
        AuditEventType eventType,
        int schemaVersion,
        String affectedBusinessInvariant,
        AuditInclusion auditInclusion,
        AuditDurabilityClass durabilityClass,
        FoundationCriticality foundationCriticality,
        AuditRetentionClass retentionClass,
        AcceptableLossWindow acceptableLossWindow,
        DuplicateTolerance duplicateTolerance,
        RetryPolicy retryPolicy,
        ReplayPolicy replayPolicy,
        ReconciliationPolicy reconciliationPolicy,
        String classificationOwner,
        String approvalReference,
        AuditAction action,
        AuditOutcome outcome,
        ActorConstraint actorConstraint,
        ResourceConstraint resourceConstraint,
        OriginatingActorRule originatingActorRule,
        Set<String> allowedPayloadKeys,
        Map<String, Set<String>> allowedPayloadValues
) {

    public AuditEventPolicy {
        eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        affectedBusinessInvariant = requireText(affectedBusinessInvariant, "affectedBusinessInvariant");
        auditInclusion = Objects.requireNonNull(auditInclusion, "auditInclusion must not be null");
        durabilityClass = Objects.requireNonNull(durabilityClass, "durabilityClass must not be null");
        foundationCriticality = Objects.requireNonNull(
                foundationCriticality, "foundationCriticality must not be null");
        if (FoundationCriticality.valueOf(durabilityClass.name()) != foundationCriticality) {
            throw new IllegalArgumentException("durability and foundation criticality must map one-to-one");
        }
        retentionClass = Objects.requireNonNull(retentionClass, "retentionClass must not be null");
        acceptableLossWindow = Objects.requireNonNull(
                acceptableLossWindow, "acceptableLossWindow must not be null");
        duplicateTolerance = Objects.requireNonNull(duplicateTolerance, "duplicateTolerance must not be null");
        retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        replayPolicy = Objects.requireNonNull(replayPolicy, "replayPolicy must not be null");
        reconciliationPolicy = Objects.requireNonNull(
                reconciliationPolicy, "reconciliationPolicy must not be null");
        classificationOwner = requireText(classificationOwner, "classificationOwner");
        approvalReference = requireText(approvalReference, "approvalReference");
        action = Objects.requireNonNull(action, "action must not be null");
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        actorConstraint = Objects.requireNonNull(actorConstraint, "actorConstraint must not be null");
        resourceConstraint = Objects.requireNonNull(
                resourceConstraint, "resourceConstraint must not be null");
        originatingActorRule = Objects.requireNonNull(
                originatingActorRule, "originatingActorRule must not be null");
        allowedPayloadKeys = immutableKeys(allowedPayloadKeys);
        allowedPayloadValues = immutableValues(allowedPayloadValues, allowedPayloadKeys);
    }

    public boolean requiresDurableCapture() {
        return durabilityClass == AuditDurabilityClass.SECURITY_CRITICAL;
    }

    public enum ResourcePresence {
        FORBIDDEN,
        OPTIONAL,
        REQUIRED
    }

    public enum OriginatingActorRule {
        FORBIDDEN,
        TRUSTED_ACTOR_OPTIONAL
    }

    public record ActorConstraint(Set<ActorType> allowedActorTypes, boolean anonymousRolesAllowed) {

        public ActorConstraint {
            Objects.requireNonNull(allowedActorTypes, "allowedActorTypes must not be null");
            if (allowedActorTypes.isEmpty()) {
                throw new IllegalArgumentException("allowedActorTypes must not be empty");
            }
            for (ActorType actorType : allowedActorTypes) {
                Objects.requireNonNull(actorType, "allowedActorTypes must not contain null");
            }
            if (anonymousRolesAllowed && !allowedActorTypes.contains(ActorType.ANONYMOUS)) {
                throw new IllegalArgumentException(
                        "anonymousRolesAllowed requires ANONYMOUS to be an allowed actor type");
            }
            allowedActorTypes = Set.copyOf(allowedActorTypes);
        }
    }

    public record ResourceConstraint(
            ResourcePresence presence,
            Set<String> allowedResourceTypes,
            boolean resourceIdRequired,
            boolean resourceIdMustMatchActorId
    ) {

        public ResourceConstraint {
            presence = Objects.requireNonNull(presence, "resource presence must not be null");
            allowedResourceTypes = immutableTextSet(allowedResourceTypes, "allowed resource type");
            if (presence == ResourcePresence.FORBIDDEN) {
                if (!allowedResourceTypes.isEmpty() || resourceIdRequired || resourceIdMustMatchActorId) {
                    throw new IllegalArgumentException(
                        "forbidden resources must not declare resource types or identifier constraints");
                }
            } else if (allowedResourceTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "optional and required resources must declare allowed resource types");
            }
            if (resourceIdMustMatchActorId && !resourceIdRequired) {
                throw new IllegalArgumentException(
                        "matching resource identifiers must be required");
            }
        }

        private static Set<String> immutableTextSet(Set<String> values, String field) {
            Objects.requireNonNull(values, field + "s must not be null");
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(field + "s must not contain blank values");
                }
            }
            return Set.copyOf(values);
        }
    }

    private static Set<String> immutableKeys(Set<String> keys) {
        Objects.requireNonNull(keys, "allowedPayloadKeys must not be null");
        for (String key : keys) {
            requireText(key, "allowed payload key");
        }
        return Set.copyOf(keys);
    }

    private static Map<String, Set<String>> immutableValues(
            Map<String, Set<String>> values,
            Set<String> allowedKeys
    ) {
        Objects.requireNonNull(values, "allowedPayloadValues must not be null");
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        values.forEach((key, allowedValues) -> {
            requireText(key, "allowed payload value key");
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("closed value key must be an allowed payload key");
            }
            copy.put(key, Set.copyOf(Objects.requireNonNull(
                    allowedValues, "allowed payload values must not contain null sets")));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
