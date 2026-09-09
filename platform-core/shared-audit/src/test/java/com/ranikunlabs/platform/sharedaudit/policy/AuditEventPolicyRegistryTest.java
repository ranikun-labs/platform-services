package com.ranikunlabs.platform.sharedaudit.policy;

import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
