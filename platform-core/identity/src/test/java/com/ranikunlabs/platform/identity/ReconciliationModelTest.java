package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                ReconciliationModel.ConflictCode.CREDENTIAL_MISMATCH);
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
    void disposableSourceIsCountedAsExplicitlyExcluded() {
        ReconciliationModel.SourceIdentity disposable = source(
                ReconciliationModel.SourceAttestation.DISPOSABLE);
        ReconciliationModel.ReconciliationPlanSummary summary = summary(
                List.of(source(ReconciliationModel.SourceAttestation.IN_SCOPE), disposable),
                List.of(),
                0,
                Map.of(ReconciliationModel.Decision.EXCLUDED_SOURCE, 1)
        );

        assertTrue(disposable.isExplicitlyExcluded());
        assertEquals(1, summary.excludedSourceCount());
        assertEquals(1, summary.decisionCount(ReconciliationModel.Decision.EXCLUDED_SOURCE));
        assertTrue(summary.readiness().isReady());
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
                List.of(conflict(ReconciliationModel.ConflictCode.SOURCE_UNATTESTED)),
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
        return new ReconciliationModel.SourceIdentity(
                "source-" + attestation.name().toLowerCase(),
                ReconciliationModel.SourceKind.PLATFORM_IDENTITY,
                attestation
        );
    }

    private static ReconciliationModel.ConflictDescriptor conflict(
            ReconciliationModel.ConflictCode code
    ) {
        return new ReconciliationModel.ConflictDescriptor(
                code,
                ReconciliationModel.Domain.ACCOUNT,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
