package com.ranikunlabs.platform.sharedaudit.policy;

import com.ranikunlabs.platform.sharedaudit.core.AuditAction;
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
        allowedPayloadKeys = immutableKeys(allowedPayloadKeys);
        allowedPayloadValues = immutableValues(allowedPayloadValues, allowedPayloadKeys);
    }

    public boolean requiresDurableCapture() {
        return durabilityClass == AuditDurabilityClass.SECURITY_CRITICAL;
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
