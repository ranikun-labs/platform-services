package com.ranikunlabs.platform.sharedaudit.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Producer-side Shared Audit event; transport and persistence metadata are intentionally absent. */
public record AuditEvent(
        AuditEventId eventId,
        AuditEventType eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        AuditActor actor,
        AuditActor originatingActor,
        String product,
        AuditTenant tenant,
        AuditResource resource,
        AuditAction action,
        AuditOutcome outcome,
        String correlationId,
        String causationId,
        String traceId,
        Map<String, String> payload
) {

    public AuditEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        producer = requireText(producer, "producer");
        actor = Objects.requireNonNull(actor, "actor must not be null");
        action = Objects.requireNonNull(action, "action must not be null");
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        product = optionalText(product);
        correlationId = optionalText(correlationId);
        causationId = optionalText(causationId);
        traceId = optionalText(traceId);
        payload = immutablePayload(payload);
    }

    public AuditEvent(
            AuditEventId eventId,
            AuditEventType eventType,
            int schemaVersion,
            Instant occurredAt,
            String producer,
            AuditActor actor,
            AuditAction action,
            AuditOutcome outcome,
            Map<String, String> payload
    ) {
        this(
                eventId,
                eventType,
                schemaVersion,
                occurredAt,
                producer,
                actor,
                null,
                null,
                null,
                null,
                action,
                outcome,
                null,
                null,
                null,
                payload);
    }

    private static Map<String, String> immutablePayload(Map<String, String> payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        Map<String, String> copy = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("payload keys must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("payload values must not be null");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
