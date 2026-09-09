package com.ranikunlabs.platform.sharedaudit.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditValueObjectsTest {

    @Test
    void actorEnforcesIntrinsicIdentityInvariantsAndCopiesRoles() {
        Set<String> roles = new HashSet<>(Set.of("MANAGER"));
        AuditActor actor = new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", roles);

        roles.add("ADMIN");

        assertThat(actor.roles()).containsExactly("MANAGER");
        assertThatThrownBy(() -> new AuditActor(ActorType.ANONYMOUS, "submitted-login", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditActor(ActorType.AUTHENTICATED_USER, "", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventTypeEnforcesCanonicalNamespacedSyntax() {
        assertThat(new AuditEventType("identity.authentication.login_succeeded").value())
                .isEqualTo("identity.authentication.login_succeeded");

        assertThatThrownBy(() -> new AuditEventType("Identity.Authentication.Login"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEventType("identity.login"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventCopiesPayloadAndDoesNotExposePersistenceMetadata() {
        Map<String, String> payload = new HashMap<>();
        payload.put("authentication_method", "PASSWORD");

        AuditEvent event = new AuditEvent(
                new AuditEventId("event-1"),
                new AuditEventType("identity.authentication.login_succeeded"),
                1,
                Instant.parse("2026-09-09T00:00:00Z"),
                "system.shared-identity",
                new AuditActor(ActorType.AUTHENTICATED_USER, "account-1", Set.of()),
                AuditAction.AUTHENTICATE,
                AuditOutcome.SUCCEEDED,
                payload);

        payload.put("unexpected", "later");

        assertThat(event.payload()).containsOnlyKeys("authentication_method");
        assertThat(event.payload()).isUnmodifiable();
        assertThat(event).hasFieldOrProperty("occurredAt");
        assertThat(Arrays.stream(AuditEvent.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("recordedAt"))).isTrue();
    }

    @Test
    void tenantAndResourceRequireTheirStructuralFields() {
        assertThatThrownBy(() -> new AuditTenant("organization", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditResource("", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new AuditResource("platform_account", null).resourceId()).isNull();
    }
}
