package com.ranikunlabs.platform.sharedaudit.core;

import java.util.Objects;
import java.util.Set;

/** Immutable actor snapshot owned by the producer. */
public record AuditActor(ActorType actorType, String actorId, Set<String> roles) {

    public AuditActor {
        actorType = Objects.requireNonNull(actorType, "actorType must not be null");
        actorId = optionalText(actorId);
        roles = immutableRoles(roles);

        if (actorType == ActorType.ANONYMOUS && actorId != null) {
            throw new IllegalArgumentException("ANONYMOUS actors must not carry actorId");
        }
        if (actorType != ActorType.ANONYMOUS && actorId == null) {
            throw new IllegalArgumentException("authenticated and system actors require actorId");
        }
    }

    public AuditActor(ActorType actorType, String actorId) {
        this(actorType, actorId, Set.of());
    }

    private static Set<String> immutableRoles(Set<String> roles) {
        Objects.requireNonNull(roles, "roles must not be null");
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("roles must not contain blank values");
            }
        }
        return Set.copyOf(roles);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
