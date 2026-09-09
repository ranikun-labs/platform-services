package com.ranikunlabs.platform.sharedaudit.policy;

import com.ranikunlabs.platform.sharedaudit.core.ActorType;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventPolicyRegistryTest {

    private static final List<String> EVENT_TYPES = List.of(
            "identity.authentication.login_succeeded",
            "identity.authentication.login_rejected",
            "identity.authentication.refresh_rejected",
            "identity.authentication.access_token_revoked",
            "identity.authentication.refresh_sessions_revoked",
            "identity.oauth.authentication_succeeded",
            "identity.oauth.authentication_rejected",
            "identity.oauth.principal_verified_unlinked",
            "identity.account.registered",
            "identity.credential.password_changed",
            "identity.authentication.refresh_succeeded",
            "identity.oauth.authorization_started",
            "identity.authorization.product_access_rejected",
            "identity.authentication.bearer_rejected");

    @Test
    void canonicalIdentityCatalogContainsAllFourteenVersionOnePolicies() {
        AuditEventPolicyRegistry registry = AuditEventPolicyRegistry.canonical();

        assertThat(registry.registeredEventTypes()).hasSize(14);
        for (String eventType : EVENT_TYPES) {
            AuditEventPolicyRegistry.PolicyResolution resolution = registry.resolve(
                    new AuditEventType(eventType), 1);
            assertThat(resolution.status()).isEqualTo(
                    AuditEventPolicyRegistry.PolicyResolutionStatus.REGISTERED);
            assertThat(resolution.policy()).isNotNull();
        }
    }

    @Test
    void unknownTypeAndUnsupportedVersionAreDistinguished() {
        AuditEventPolicyRegistry registry = AuditEventPolicyRegistry.canonical();

        assertThat(registry.resolve(new AuditEventType("identity.unknown.fact"), 1).status())
                .isEqualTo(AuditEventPolicyRegistry.PolicyResolutionStatus.UNREGISTERED_EVENT_TYPE);
        assertThat(registry.resolve(
                new AuditEventType("identity.authentication.login_succeeded"), 2).status())
                .isEqualTo(AuditEventPolicyRegistry.PolicyResolutionStatus.UNSUPPORTED_SCHEMA_VERSION);
    }

    @Test
    void policyCarriesPayloadAllowListAndClosedValueSet() {
        AuditEventPolicyRegistry registry = AuditEventPolicyRegistry.canonical();

        AuditEventPolicy login = registry.resolve(
                new AuditEventType("identity.authentication.login_succeeded"), 1).policy();
        assertThat(login.allowedPayloadKeys()).containsExactly("authentication_method");

        AuditEventPolicy bearer = registry.resolve(
                new AuditEventType("identity.authentication.bearer_rejected"), 1).policy();
        assertThat(bearer.allowedPayloadValues().get("reason_code"))
                .containsExactlyInAnyOrder("REVOKED", "REPLAY", "POLICY");
    }

    @Test
    void criticalPolicyMapsDurabilityAndFoundationClassificationToTheSameValue() {
        AuditEventPolicyRegistry registry = AuditEventPolicyRegistry.canonical();

        AuditEventPolicy registration = registry.resolve(
                new AuditEventType("identity.account.registered"), 1).policy();
        assertThat(registration.durabilityClass())
                .isEqualTo(AuditDurabilityClass.SECURITY_CRITICAL);
        assertThat(registration.foundationCriticality())
                .isEqualTo(FoundationCriticality.SECURITY_CRITICAL);
        assertThat(registration.auditInclusion()).isEqualTo(AuditInclusion.MUST_AUDIT);
    }

    @ParameterizedTest
    @MethodSource("canonicalContextExpectations")
    void everyCanonicalIdentityPolicyDeclaresActorResourceAndOriginatingActorRules(
            String eventType,
            Set<ActorType> actorTypes,
            AuditEventPolicy.ResourcePresence resourcePresence,
            Set<String> resourceTypes,
            boolean resourceIdMustMatchActorId,
            AuditEventPolicy.OriginatingActorRule originatingActorRule
    ) {
        AuditEventPolicy policy = AuditEventPolicyRegistry.canonical()
                .resolve(new AuditEventType(eventType), 1)
                .policy();

        assertThat(policy.actorConstraint().allowedActorTypes())
                .containsExactlyInAnyOrderElementsOf(actorTypes);
        assertThat(policy.actorConstraint().anonymousRolesAllowed()).isFalse();
        assertThat(policy.resourceConstraint().presence()).isEqualTo(resourcePresence);
        assertThat(policy.resourceConstraint().allowedResourceTypes())
                .containsExactlyInAnyOrderElementsOf(resourceTypes);
        assertThat(policy.resourceConstraint().resourceIdMustMatchActorId())
                .isEqualTo(resourceIdMustMatchActorId);
        assertThat(policy.originatingActorRule()).isEqualTo(originatingActorRule);
    }

    private static Stream<Arguments> canonicalContextExpectations() {
        return Stream.of(
                context("identity.authentication.login_succeeded",
                        Set.of(ActorType.AUTHENTICATED_USER),
                        AuditEventPolicy.ResourcePresence.REQUIRED, true,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.authentication.login_rejected",
                        Set.of(ActorType.ANONYMOUS),
                        AuditEventPolicy.ResourcePresence.FORBIDDEN, false,
                        AuditEventPolicy.OriginatingActorRule.FORBIDDEN),
                context("identity.authentication.refresh_rejected",
                        Set.of(ActorType.ANONYMOUS),
                        AuditEventPolicy.ResourcePresence.FORBIDDEN, false,
                        AuditEventPolicy.OriginatingActorRule.FORBIDDEN),
                context("identity.authentication.access_token_revoked",
                        Set.of(ActorType.AUTHENTICATED_USER),
                        AuditEventPolicy.ResourcePresence.REQUIRED, true,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.authentication.refresh_sessions_revoked",
                        Set.of(ActorType.AUTHENTICATED_USER),
                        AuditEventPolicy.ResourcePresence.REQUIRED, true,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.oauth.authentication_succeeded",
                        Set.of(ActorType.AUTHENTICATED_USER),
                        AuditEventPolicy.ResourcePresence.REQUIRED, true,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.oauth.authentication_rejected",
                        Set.of(ActorType.ANONYMOUS),
                        AuditEventPolicy.ResourcePresence.FORBIDDEN, false,
                        AuditEventPolicy.OriginatingActorRule.FORBIDDEN),
                context("identity.oauth.principal_verified_unlinked",
                        Set.of(ActorType.ANONYMOUS),
                        AuditEventPolicy.ResourcePresence.FORBIDDEN, false,
                        AuditEventPolicy.OriginatingActorRule.FORBIDDEN),
                context("identity.account.registered",
                        Set.of(ActorType.ANONYMOUS, ActorType.AUTHENTICATED_USER, ActorType.SYSTEM),
                        AuditEventPolicy.ResourcePresence.REQUIRED, false,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.credential.password_changed",
                        Set.of(ActorType.AUTHENTICATED_USER, ActorType.SYSTEM),
                        AuditEventPolicy.ResourcePresence.REQUIRED, false,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.authentication.refresh_succeeded",
                        Set.of(ActorType.AUTHENTICATED_USER),
                        AuditEventPolicy.ResourcePresence.REQUIRED, true,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.oauth.authorization_started",
                        Set.of(ActorType.ANONYMOUS),
                        AuditEventPolicy.ResourcePresence.FORBIDDEN, false,
                        AuditEventPolicy.OriginatingActorRule.FORBIDDEN),
                context("identity.authorization.product_access_rejected",
                        Set.of(ActorType.AUTHENTICATED_USER, ActorType.SYSTEM),
                        AuditEventPolicy.ResourcePresence.OPTIONAL, false,
                        AuditEventPolicy.OriginatingActorRule.TRUSTED_ACTOR_OPTIONAL),
                context("identity.authentication.bearer_rejected",
                        Set.of(ActorType.ANONYMOUS),
                        AuditEventPolicy.ResourcePresence.FORBIDDEN, false,
                        AuditEventPolicy.OriginatingActorRule.FORBIDDEN));
    }

    private static Arguments context(
            String eventType,
            Set<ActorType> actorTypes,
            AuditEventPolicy.ResourcePresence resourcePresence,
            boolean resourceIdMustMatchActorId,
            AuditEventPolicy.OriginatingActorRule originatingActorRule
    ) {
        Set<String> resourceTypes = resourcePresence == AuditEventPolicy.ResourcePresence.FORBIDDEN
                ? Set.of()
                : Set.of("platform_account");
        return Arguments.of(
                eventType,
                actorTypes,
                resourcePresence,
                resourceTypes,
                resourceIdMustMatchActorId,
                originatingActorRule);
    }
}
