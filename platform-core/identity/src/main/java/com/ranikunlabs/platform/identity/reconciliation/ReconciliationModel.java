package com.ranikunlabs.platform.identity.reconciliation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-neutral contract for a read-only identity reconciliation plan.
 *
 * <p>This contract carries only decision data and opaque correlation values. It does not contain
 * persistence, transport, report, or hashing concerns.
 */
public final class ReconciliationModel {

    private ReconciliationModel() {
    }

    /** The reviewed role of a source in the reconciliation plan. */
    public enum SourceKind {
        PLATFORM_IDENTITY,
        CARELOG_IDENTITY,
        TARGET_IDENTITY
    }

    /** The only supported attestation states for a source. */
    public enum SourceAttestation {
        /** The source is attested and participates in reconciliation. */
        IN_SCOPE,
        /** The source is explicitly excluded from reconciliation. */
        DISPOSABLE,
        /** The source is not trusted for reconciliation and fails closed. */
        UNATTESTED;

        public boolean isInScope() {
            return this == IN_SCOPE;
        }

        public boolean isExplicitlyExcluded() {
            return this == DISPOSABLE;
        }

        public boolean requiresAttestation() {
            return this != DISPOSABLE;
        }

        public boolean isAttested() {
            return this == IN_SCOPE;
        }
    }

    /** The data category used to describe a candidate or conflict. */
    public enum Domain {
        SOURCE,
        ACCOUNT,
        CREDENTIAL,
        OAUTH,
        PRODUCT_CLIENT,
        TARGET
    }

    /** The complete and deliberately small set of candidate outcomes. */
    public enum Decision {
        INSERT,
        IDENTICAL,
        CONFLICT,
        EXCLUDED_SOURCE
    }

    /** Explicit hard-conflict categories recognized by the reconciliation contract. */
    public enum ConflictCode {
        SOURCE_UNATTESTED(Domain.SOURCE),
        UNEXPECTED_SCHEMA_HISTORY(Domain.SOURCE),
        ACCOUNT_SEMANTIC_MISMATCH(Domain.ACCOUNT),
        DUPLICATE_LOGIN_OWNERSHIP(Domain.CREDENTIAL),
        CREDENTIAL_MISMATCH(Domain.CREDENTIAL),
        ORPHAN_CREDENTIAL(Domain.CREDENTIAL),
        OAUTH_OWNERSHIP_COLLISION(Domain.OAUTH),
        ORPHAN_OAUTH_IDENTITY(Domain.OAUTH),
        CARELOG_UUID_INVARIANT_VIOLATION(Domain.ACCOUNT),
        UNEXPECTED_PRODUCT_CLIENT(Domain.PRODUCT_CLIENT),
        TARGET_ROW_UNEXPECTED(Domain.TARGET),
        TARGET_SEMANTIC_MISMATCH(Domain.TARGET);

        private final Domain domain;

        ConflictCode(Domain domain) {
            this.domain = Objects.requireNonNull(domain, "domain must not be null");
        }

        public Domain domain() {
            return domain;
        }
    }

    /**
     * Boundary for a correlation value that has already been sanitized or keyed by an adapter.
     *
     * <p>Slice 2A intentionally does not define how the value is produced. Its {@code toString}
     * method is non-disclosing so an opaque value is not accidentally copied into logs or reports.
     */
    public record OpaqueIdentifier(String sanitizedValue) {

        public OpaqueIdentifier {
            sanitizedValue = requireText(sanitizedValue, "sanitizedValue");
        }

        @Override
        public String toString() {
            return "OpaqueIdentifier";
        }
    }

    /** The exact {@code logicalName} value is the plan's source-uniqueness key. */
    public record SourceIdentity(
            String logicalName,
            SourceKind kind,
            SourceAttestation attestation
    ) {
        public SourceIdentity {
            logicalName = requireText(logicalName, "logicalName");
            kind = Objects.requireNonNull(kind, "kind must not be null");
            attestation = Objects.requireNonNull(attestation, "attestation must not be null");
        }

        public boolean isRequired() {
            return attestation.requiresAttestation();
        }

        public boolean isAttested() {
            return attestation.isAttested();
        }

        public boolean isExplicitlyExcluded() {
            return attestation.isExplicitlyExcluded();
        }
    }

    /** Identity of one candidate, keyed only by an opaque adapter-provided correlation value. */
    public record CandidateIdentity(
            Domain domain,
            OpaqueIdentifier correlationId,
            SourceIdentity source
    ) {
        public CandidateIdentity {
            domain = Objects.requireNonNull(domain, "domain must not be null");
            correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
            source = Objects.requireNonNull(source, "source must not be null");
        }
    }

    /**
     * A structured conflict descriptor whose supplied domain must match the conflict code's
     * canonical domain. Details remain category- and evidence-based; raw login, provider,
     * credential, token, and secret values have no field in this contract.
     */
    public record ConflictDescriptor(
            ConflictCode code,
            Domain domain,
            List<SourceIdentity> sources,
            List<CandidateIdentity> candidates,
            List<OpaqueIdentifier> evidence
    ) {
        public ConflictDescriptor {
            code = Objects.requireNonNull(code, "code must not be null");
            domain = Objects.requireNonNull(domain, "domain must not be null");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
            if (domain != code.domain()) {
                throw new IllegalArgumentException(
                        "conflict code " + code + " requires domain " + code.domain());
            }
            for (CandidateIdentity candidate : candidates) {
                if (candidate.domain() != domain) {
                    throw new IllegalArgumentException("candidate domain must match conflict domain");
                }
            }
        }
    }

    /** Deterministic readiness result with stable, non-sensitive blocking reason codes. */
    public record Readiness(
            boolean readyToReconcile,
            List<String> blockingReasons
    ) {
        public Readiness {
            blockingReasons = List.copyOf(Objects.requireNonNull(blockingReasons,
                    "blockingReasons must not be null"));
            if (readyToReconcile && !blockingReasons.isEmpty()) {
                throw new IllegalArgumentException("ready reconciliation cannot have blocking reasons");
            }
            if (!readyToReconcile && blockingReasons.isEmpty()) {
                throw new IllegalArgumentException("blocked reconciliation requires a blocking reason");
            }
        }

        public boolean isReady() {
            return readyToReconcile;
        }
    }

    /**
     * Summary inputs required to decide whether a reconciliation plan may proceed.
     *
     * <p>A disposable source is retained in {@code sources} as a source-level exclusion and is
     * never treated as an in-scope source by the readiness calculation. The
     * {@link Decision#EXCLUDED_SOURCE} count is independent candidate/planner accounting; no
     * numerical equality with the number of disposable sources is implied.
     */
    public record ReconciliationPlanSummary(
            List<SourceIdentity> sources,
            Map<Decision, Integer> decisionCounts,
            int unsupportedSourceStateCount,
            List<ConflictDescriptor> unresolvedConflicts
    ) {
        public ReconciliationPlanSummary {
            sources = uniqueSources(sources);
            decisionCounts = immutableDecisionCounts(decisionCounts);
            if (unsupportedSourceStateCount < 0) {
                throw new IllegalArgumentException("unsupportedSourceStateCount must not be negative");
            }
            unresolvedConflicts = List.copyOf(Objects.requireNonNull(unresolvedConflicts,
                    "unresolvedConflicts must not be null"));
            int conflictDecisionCount = decisionCounts.get(Decision.CONFLICT);
            if (conflictDecisionCount != unresolvedConflicts.size()) {
                throw new IllegalArgumentException(
                        "CONFLICT decision count must equal unresolved conflict descriptor count");
            }
        }

        public Readiness readiness() {
            List<String> blockers = new ArrayList<>();

            boolean hasInScopeSource = sources.stream().anyMatch(source -> source.attestation().isInScope());
            if (!hasInScopeSource) {
                blockers.add("NO_IN_SCOPE_SOURCE");
            }

            boolean allRequiredSourcesAttested = sources.stream()
                    .filter(SourceIdentity::isRequired)
                    .allMatch(SourceIdentity::isAttested);
            if (!allRequiredSourcesAttested) {
                blockers.add("REQUIRED_SOURCE_UNATTESTED");
            }

            if (unsupportedSourceStateCount > 0) {
                blockers.add("UNSUPPORTED_SOURCE_STATE");
            }
            if (!unresolvedConflicts.isEmpty()) {
                blockers.add("UNRESOLVED_CONFLICT");
            }
            return new Readiness(blockers.isEmpty(), blockers);
        }

        public int requiredSourceCount() {
            return (int) sources.stream().filter(SourceIdentity::isRequired).count();
        }

        public int attestedSourceCount() {
            return (int) sources.stream().filter(SourceIdentity::isAttested).count();
        }

        public int excludedSourceCount() {
            return (int) sources.stream().filter(SourceIdentity::isExplicitlyExcluded).count();
        }

        public int unresolvedConflictCount() {
            return unresolvedConflicts.size();
        }

        public int decisionCount(Decision decision) {
            return decisionCounts.get(Objects.requireNonNull(decision, "decision must not be null"));
        }
    }

    private static Map<Decision, Integer> immutableDecisionCounts(Map<Decision, Integer> counts) {
        Objects.requireNonNull(counts, "decisionCounts must not be null");
        EnumMap<Decision, Integer> normalized = new EnumMap<>(Decision.class);
        for (Decision decision : Decision.values()) {
            normalized.put(decision, 0);
        }
        counts.forEach((decision, count) -> {
            Objects.requireNonNull(decision, "decisionCounts contains a null decision");
            Objects.requireNonNull(count, "decisionCounts contains a null count");
            if (count < 0) {
                throw new IllegalArgumentException("decision count must not be negative");
            }
            normalized.put(decision, count);
        });
        return Collections.unmodifiableMap(normalized);
    }

    private static List<SourceIdentity> uniqueSources(List<SourceIdentity> sources) {
        List<SourceIdentity> copy = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        Set<String> logicalNames = new HashSet<>();
        for (SourceIdentity source : copy) {
            if (!logicalNames.add(source.logicalName())) {
                throw new IllegalArgumentException(
                        "duplicate logical source: " + source.logicalName());
            }
        }
        return copy;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
