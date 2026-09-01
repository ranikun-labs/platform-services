package com.ranikunlabs.platform.sharedai.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionResult;
import com.ranikunlabs.platform.sharedai.ports.ModelRequest;
import com.ranikunlabs.platform.sharedai.ports.ModelResult;
import com.ranikunlabs.platform.sharedai.ports.ModelUsage;
import com.ranikunlabs.platform.sharedai.ports.ProductFact;
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaContractImmutabilityTest {
    private final Instant observedAt = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void executionScopeSnapshotsInputAndRejectsJavaMutation() {
        var input = new HashSet<>(Set.of("resource-a"));
        var scope = new ExecutionScope("product-a", "tenant-a", input);

        input.add("resource-b");

        assertThat(scope.getResourceReferences()).containsExactly("resource-a");
        assertThatThrownBy(() -> scope.getResourceReferences().add("resource-c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceSnapshotsItemsAndSourceReferencesAndRejectsJavaMutation() {
        var scope = scope();
        var context = context(scope);
        var sourceReferences = new HashSet<>(Set.of("tool:fact"));
        var fact = fact(scope, sourceReferences);
        var inputItems = new ArrayList<EvidenceItem>(List.of(fact));
        var evidence = new Evidence(context, inputItems);

        sourceReferences.add("tool:other");
        inputItems.clear();

        assertThat(evidence.getItems()).containsExactly(fact);
        assertThat(fact.getSourceReferences()).containsExactly("tool:fact");
        assertThatThrownBy(() -> evidence.getItems().add(fact))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fact.getSourceReferences().add("tool:other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void modelRequestAndSuccessWarningsAreImmutableToJavaCallers() {
        var scope = scope();
        var fact = new ProductFact(
                "execution-a", scope, "tool:fact", "tool:producer", "count", "3");
        var retrieved = new RetrievedItem(
                "execution-a", scope, "retriever:doc", "retriever:producer", "content");
        var retrievedInput = new ArrayList<>(List.of(retrieved));
        var request = new ModelRequest("execution-a", scope, "query", fact, retrievedInput);
        var evidence = new Evidence(context(scope), List.of(fact(scope, new HashSet<>(Set.of("tool:fact")))));
        var warningsInput = new ArrayList<>(List.of("warning-a"));
        var success = new ExecutionResult.Success("execution-a", "interpretation", evidence, warningsInput);

        retrievedInput.clear();
        warningsInput.add("warning-b");

        assertThat(request.getRetrievedItems()).containsExactly(retrieved);
        assertThat(success.getWarnings()).containsExactly("warning-a");
        assertThatThrownBy(() -> request.getRetrievedItems().add(retrieved))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> success.getWarnings().add("warning-c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void modelResultProvidesJavaConstructionPathWithUnknownUsage() {
        var scope = scope();
        var result = new ModelResult(
                "execution-a", scope, "model:result", "model:producer", "interpretation");

        assertThat(result.getUsage()).isNull();

        var zeroUsage = new ModelUsage(0, 0, 0);
        var resultWithUsage = new ModelResult(
                "execution-a", scope, "model:result", "model:producer", "interpretation", zeroUsage);

        assertThat(resultWithUsage.getUsage()).isSameAs(zeroUsage);
    }

    private ExecutionScope scope() {
        return new ExecutionScope("product-a", "tenant-a", Set.of("resource-a"));
    }

    private ExecutionContext context(ExecutionScope scope) {
        return new ExecutionContext(scope, "principal-a", "request-a", "execution-a", null);
    }

    private StructuredProductFactEvidence fact(ExecutionScope scope, Set<String> sourceReferences) {
        return new StructuredProductFactEvidence(
                new EvidenceId("fact"),
                sourceReferences,
                "tool:producer",
                observedAt,
                "execution-a",
                scope,
                Evidence.CURRENT_SCHEMA_VERSION);
    }
}
