package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationModelTest {

    @Test
    void attestationSemanticsAreExplicitAndClosed() {
        assertArrayEquals(
                new ReconciliationModel.SourceAttestation[]{
                        ReconciliationModel.SourceAttestation.IN_SCOPE,
                        ReconciliationModel.SourceAttestation.DISPOSABLE,
                        ReconciliationModel.SourceAttestation.UNATTESTED
                },
                ReconciliationModel.SourceAttestation.values()
        );

        assertTrue(ReconciliationModel.SourceAttestation.IN_SCOPE.isInScope());
        assertTrue(ReconciliationModel.SourceAttestation.IN_SCOPE.isAttested());
        assertTrue(ReconciliationModel.SourceAttestation.DISPOSABLE.isExplicitlyExcluded());
        assertFalse(ReconciliationModel.SourceAttestation.DISPOSABLE.isInScope());
        assertFalse(ReconciliationModel.SourceAttestation.UNATTESTED.isAttested());
        assertTrue(ReconciliationModel.SourceAttestation.UNATTESTED.requiresAttestation());
    }

    @Test
    void unresolvedConflictPreventsReadiness() {
        ReconciliationModel.ConflictDescriptor conflict = conflict(
                ReconciliationModel.ConflictCode.CREDENTIAL_MISMATCH,
                ReconciliationModel.Domain.CREDENTIAL);
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(source(ReconciliationModel.SourceAttestation.IN_SCOPE)),
                List.of(conflict),
                0,
                Map.of(ReconciliationModel.Decision.CONFLICT, 1)
        );

        assertFalse(summary.readiness().readyToReconcile());
        assertEquals(List.of("UNRESOLVED_CONFLICT"), summary.readiness().blockingReasons());
    }

    @Test
    void unattestedSourcePreventsReadiness() {
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(
                        source(ReconciliationModel.SourceAttestation.IN_SCOPE),
                        source(ReconciliationModel.SourceAttestation.UNATTESTED)
                ),
                List.of(),
                0,
                Map.of()
        );

        assertFalse(summary.readiness().isReady());
        assertEquals(List.of("REQUIRED_SOURCE_UNATTESTED"), summary.readiness().blockingReasons());
    }

    @Test
    void zeroConflictAttestedPlanCanBeReady() {
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(source(ReconciliationModel.SourceAttestation.IN_SCOPE)),
                List.of(),
                0,
                Map.of(ReconciliationModel.Decision.IDENTICAL, 1)
        );

        assertTrue(summary.readiness().readyToReconcile());
        assertTrue(summary.readiness().blockingReasons().isEmpty());
    }

    @Test
    void disposableSourceIsExplicitlyExcludedWithoutDecisionCountCoupling() {
        ReconciliationModel.SourceIdentity disposable = source(
                ReconciliationModel.SourceAttestation.DISPOSABLE);
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(source(ReconciliationModel.SourceAttestation.IN_SCOPE), disposable),
                List.of(),
                0,
                Map.of()
        );

        assertTrue(disposable.isExplicitlyExcluded());
        assertEquals(1, summary.excludedSourceCount());
        assertEquals(0, summary.decisionCount(ReconciliationModel.Decision.EXCLUDED_SOURCE));
        assertTrue(summary.readiness().isReady());
    }

    @Test
    void conflictDecisionCountMustMatchUnresolvedConflictDescriptors() {
        ReconciliationModel.ConflictDescriptor conflict = conflict(
                ReconciliationModel.ConflictCode.CREDENTIAL_MISMATCH,
                ReconciliationModel.Domain.CREDENTIAL);
        List<ReconciliationModel.SourceIdentity> sources =
                List.of(source(ReconciliationModel.SourceAttestation.IN_SCOPE));

        assertThrows(IllegalArgumentException.class, () -> summary(
                sources,
                List.of(),
                0,
                Map.of(ReconciliationModel.Decision.CONFLICT, 1)
        ));
        assertThrows(IllegalArgumentException.class, () -> summary(
                sources,
                List.of(conflict),
                0,
                Map.of()
        ));

        ReconciliationModel.ReconciliationPlanSummary coherent = summary(
                sources,
                List.of(conflict),
                0,
                Map.of(ReconciliationModel.Decision.CONFLICT, 1)
        );
        assertFalse(coherent.readiness().isReady());
    }

    @Test
    void duplicateLogicalSourceIsRejectedWhetherAttestationAgreesOrContradicts() {
        assertThrows(IllegalArgumentException.class, () -> summary(
                List.of(
                        source("platform", ReconciliationModel.SourceAttestation.IN_SCOPE),
                        source("platform", ReconciliationModel.SourceAttestation.IN_SCOPE)
                ),
                List.of(),
                0,
                Map.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> summary(
                List.of(
                        source("platform", ReconciliationModel.SourceAttestation.IN_SCOPE),
                        source("platform", ReconciliationModel.SourceAttestation.DISPOSABLE)
                ),
                List.of(),
                0,
                Map.of()
        ));
    }

    @Test
    void conflictCodeAndDomainMustAgree() {
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationModel.ConflictDescriptor(
                ReconciliationModel.ConflictCode.CREDENTIAL_MISMATCH,
                ReconciliationModel.Domain.ACCOUNT,
                List.of(),
                List.of(),
                List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationModel.ConflictDescriptor(
                ReconciliationModel.ConflictCode.OAUTH_OWNERSHIP_COLLISION,
                ReconciliationModel.Domain.ACCOUNT,
                List.of(),
                List.of(),
                List.of()
        ));
    }

    @Test
    void candidateDomainMustMatchConflictDomain() {
        ReconciliationModel.SourceIdentity source = source(
                "platform", ReconciliationModel.SourceAttestation.IN_SCOPE);
        ReconciliationModel.CandidateIdentity candidate = new ReconciliationModel.CandidateIdentity(
                ReconciliationModel.Domain.CREDENTIAL,
                new ReconciliationModel.OpaqueIdentifier("candidate-key"),
                source
        );

        assertThrows(IllegalArgumentException.class, () -> new ReconciliationModel.ConflictDescriptor(
                ReconciliationModel.ConflictCode.ACCOUNT_SEMANTIC_MISMATCH,
                ReconciliationModel.Domain.ACCOUNT,
                List.of(),
                List.of(candidate),
                List.of()
        ));
    }

    @Test
    void onlyDisposableSourcesAreNotReady() {
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(source("legacy", ReconciliationModel.SourceAttestation.DISPOSABLE)),
                List.of(),
                0,
                Map.of()
        );

        assertFalse(summary.readiness().isReady());
        assertEquals(List.of("NO_IN_SCOPE_SOURCE"), summary.readiness().blockingReasons());
    }

    @Test
    void unsupportedSourceStatePreventsReadiness() {
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(source(ReconciliationModel.SourceAttestation.IN_SCOPE)),
                List.of(),
                1,
                Map.of()
        );

        assertFalse(summary.readiness().isReady());
        assertEquals(List.of("UNSUPPORTED_SOURCE_STATE"), summary.readiness().blockingReasons());
    }

    @Test
    void decisionTypesAreDeterministicAndDoNotContainTemporalPolicy() {
        assertArrayEquals(
                new ReconciliationModel.Decision[]{
                        ReconciliationModel.Decision.INSERT,
                        ReconciliationModel.Decision.IDENTICAL,
                        ReconciliationModel.Decision.CONFLICT,
                        ReconciliationModel.Decision.EXCLUDED_SOURCE
                },
                ReconciliationModel.Decision.values()
        );

        Set<String> modelNames = Arrays.stream(ReconciliationModel.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(modelNames.stream().anyMatch(name -> name.contains("timestamp")));
        assertFalse(modelNames.stream().anyMatch(name -> name.contains("latest")));
        assertFalse(modelNames.stream().anyMatch(name -> name.contains("preferred")));
    }

    @Test
    void conflictTaxonomyContainsEveryRequiredHardConflict() {
        Set<ReconciliationModel.ConflictCode> codes = Set.of(
                ReconciliationModel.ConflictCode.values());

        assertTrue(codes.containsAll(Set.of(
                ReconciliationModel.ConflictCode.SOURCE_UNATTESTED,
                ReconciliationModel.ConflictCode.UNEXPECTED_SCHEMA_HISTORY,
                ReconciliationModel.ConflictCode.ACCOUNT_SEMANTIC_MISMATCH,
                ReconciliationModel.ConflictCode.DUPLICATE_LOGIN_OWNERSHIP,
                ReconciliationModel.ConflictCode.CREDENTIAL_MISMATCH,
                ReconciliationModel.ConflictCode.ORPHAN_CREDENTIAL,
                ReconciliationModel.ConflictCode.OAUTH_OWNERSHIP_COLLISION,
                ReconciliationModel.ConflictCode.ORPHAN_OAUTH_IDENTITY,
                ReconciliationModel.ConflictCode.CARELOG_UUID_INVARIANT_VIOLATION,
                ReconciliationModel.ConflictCode.UNEXPECTED_PRODUCT_CLIENT,
                ReconciliationModel.ConflictCode.TARGET_ROW_UNEXPECTED,
                ReconciliationModel.ConflictCode.TARGET_SEMANTIC_MISMATCH
        )));
    }

    @Test
    void everyConflictCodeHasOneCanonicalDomain() {
        Map<ReconciliationModel.ConflictCode, ReconciliationModel.Domain> expected = Map.ofEntries(
                Map.entry(ReconciliationModel.ConflictCode.SOURCE_UNATTESTED,
                        ReconciliationModel.Domain.SOURCE),
                Map.entry(ReconciliationModel.ConflictCode.UNEXPECTED_SCHEMA_HISTORY,
                        ReconciliationModel.Domain.SOURCE),
                Map.entry(ReconciliationModel.ConflictCode.ACCOUNT_SEMANTIC_MISMATCH,
                        ReconciliationModel.Domain.ACCOUNT),
                Map.entry(ReconciliationModel.ConflictCode.DUPLICATE_LOGIN_OWNERSHIP,
                        ReconciliationModel.Domain.CREDENTIAL),
                Map.entry(ReconciliationModel.ConflictCode.CREDENTIAL_MISMATCH,
                        ReconciliationModel.Domain.CREDENTIAL),
                Map.entry(ReconciliationModel.ConflictCode.ORPHAN_CREDENTIAL,
                        ReconciliationModel.Domain.CREDENTIAL),
                Map.entry(ReconciliationModel.ConflictCode.OAUTH_OWNERSHIP_COLLISION,
                        ReconciliationModel.Domain.OAUTH),
                Map.entry(ReconciliationModel.ConflictCode.ORPHAN_OAUTH_IDENTITY,
                        ReconciliationModel.Domain.OAUTH),
                Map.entry(ReconciliationModel.ConflictCode.CARELOG_UUID_INVARIANT_VIOLATION,
                        ReconciliationModel.Domain.ACCOUNT),
                Map.entry(ReconciliationModel.ConflictCode.UNEXPECTED_PRODUCT_CLIENT,
                        ReconciliationModel.Domain.PRODUCT_CLIENT),
                Map.entry(ReconciliationModel.ConflictCode.TARGET_ROW_UNEXPECTED,
                        ReconciliationModel.Domain.TARGET),
                Map.entry(ReconciliationModel.ConflictCode.TARGET_SEMANTIC_MISMATCH,
                        ReconciliationModel.Domain.TARGET)
        );

        assertEquals(expected.size(), ReconciliationModel.ConflictCode.values().length);
        expected.forEach((code, domain) -> assertEquals(domain, code.domain()));
    }

    @Test
    void opaqueIdentifierDoesNotExposeItsValueThroughStringOutput() {
        String sensitiveCorrelation = UUID.randomUUID().toString();
        ReconciliationModel.OpaqueIdentifier identifier =
                new ReconciliationModel.OpaqueIdentifier(sensitiveCorrelation);

        assertEquals(sensitiveCorrelation, identifier.sanitizedValue());
        assertEquals("OpaqueIdentifier", identifier.toString());
    }

    @Test
    void readinessBlockersHaveStableOrderingAndDecisionCountsAreImmutable() {
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(
                        source(ReconciliationModel.SourceAttestation.IN_SCOPE),
                        source(ReconciliationModel.SourceAttestation.UNATTESTED)
                ),
                List.of(conflict(
                        ReconciliationModel.ConflictCode.SOURCE_UNATTESTED,
                        ReconciliationModel.Domain.SOURCE)),
                1,
                Map.of(ReconciliationModel.Decision.CONFLICT, 1)
        );

        assertEquals(
                List.of("REQUIRED_SOURCE_UNATTESTED", "UNSUPPORTED_SOURCE_STATE", "UNRESOLVED_CONFLICT"),
                summary.readiness().blockingReasons()
        );
        assertEquals(0, summary.decisionCount(ReconciliationModel.Decision.INSERT));
        assertEquals(1, summary.decisionCount(ReconciliationModel.Decision.CONFLICT));
    }

    private static ReconciliationModel.ReconciliationPlanSummary summary(
            List<ReconciliationModel.SourceIdentity> sources,
            List<ReconciliationModel.ConflictDescriptor> conflicts,
            int unsupportedSourceStateCount,
            Map<ReconciliationModel.Decision, Integer> decisionCounts
    ) {
        return new ReconciliationModel.ReconciliationPlanSummary(
                sources,
                decisionCounts,
                unsupportedSourceStateCount,
                conflicts
        );
    }

    private static ReconciliationModel.SourceIdentity source(
            ReconciliationModel.SourceAttestation attestation
    ) {
        return source(
                "source-" + attestation.name().toLowerCase(Locale.ROOT),
                attestation
        );
    }

    private static ReconciliationModel.SourceIdentity source(
            String logicalName,
            ReconciliationModel.SourceAttestation attestation
    ) {
        return new ReconciliationModel.SourceIdentity(
                logicalName,
                ReconciliationModel.SourceKind.PLATFORM_IDENTITY,
                attestation
        );
    }

    private static ReconciliationModel.ConflictDescriptor conflict(
            ReconciliationModel.ConflictCode code,
            ReconciliationModel.Domain domain
    ) {
        return new ReconciliationModel.ConflictDescriptor(
                code,
                domain,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
