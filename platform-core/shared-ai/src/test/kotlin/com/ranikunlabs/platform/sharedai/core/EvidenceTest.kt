package com.ranikunlabs.platform.sharedai.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class EvidenceTest {
    private val scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a"))
    private val context = ExecutionContext(scope, "principal-a", "request-a", "execution-a", null)
    private val observedAt = Instant.parse("2026-08-27T00:00:00Z")

    @Test
    fun `aggregates the canonical reference-first envelope`() {
        val evidence = Evidence(context, listOf(fact(), retrieved(), interpretation()))

        assertThat(evidence.items.map(EvidenceItem::kind)).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.RETRIEVED_CONTENT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(evidence.items).allSatisfy { item ->
            assertThat(item.evidenceId.value).isNotBlank()
            assertThat(item.sourceReferences).isNotEmpty()
            assertThat(item.producerReference).isNotBlank()
            assertThat(item.observedAt).isEqualTo(observedAt)
            assertThat(item.executionReference).isEqualTo("execution-a")
            assertThat(item.scopeReference).isEqualTo(scope)
            assertThat(item.schemaVersion).isEqualTo(Evidence.CURRENT_SCHEMA_VERSION)
        }
    }

    @Test
    fun `canonical Evidence types do not carry ephemeral payload values`() {
        assertThat(StructuredProductFactEvidence::class.java.methods.map { it.name })
            .doesNotContain("getFactName", "getFactValue", "getValue")
        assertThat(RetrievedContentEvidence::class.java.methods.map { it.name })
            .doesNotContain("getContent")
        assertThat(ModelInterpretationEvidence::class.java.methods.map { it.name })
            .doesNotContain("getInterpretation")
    }

    @Test
    fun `rejects mismatched execution correlation`() {
        assertThatThrownBy { Evidence(context, listOf(fact(executionReference = "execution-b"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("executionReference")
    }

    @Test
    fun `rejects evidence that widens scope`() {
        val widened = ExecutionScope("product-a", "tenant-a", setOf("resource-a", "resource-b"))

        assertThatThrownBy { Evidence(context, listOf(fact(scope = widened))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("scope")
    }

    @Test
    fun `rejects a model interpretation without supporting source evidence`() {
        assertThatThrownBy {
            ModelInterpretationEvidence(
                EvidenceId("interpretation"),
                emptySet(),
                "model:fixture",
                observedAt,
                "execution-a",
                scope,
                Evidence.CURRENT_SCHEMA_VERSION,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sourceReferences")
    }

    @Test
    fun `rejects a model interpretation with an unknown support reference`() {
        assertThatThrownBy {
            Evidence(context, listOf(fact(), interpretation(setOf("unknown-evidence"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("same envelope")
    }

    @Test
    fun `rejects cross-execution supporting evidence`() {
        assertThatThrownBy {
            Evidence(
                context,
                listOf(
                    fact(evidenceId = "foreign-fact", executionReference = "execution-b"),
                    interpretation(setOf("foreign-fact")),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("executionReference")
    }

    @Test
    fun `rejects cross-scope supporting evidence`() {
        val widened = ExecutionScope("product-a", "tenant-a", setOf("resource-a", "resource-b"))
        assertThatThrownBy {
            Evidence(
                context,
                listOf(
                    fact(evidenceId = "foreign-fact", scope = widened),
                    interpretation(setOf("foreign-fact")),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("scope")
    }

    private fun fact(
        evidenceId: String = "fact",
        executionReference: String = "execution-a",
        scope: ExecutionScope = this.scope,
    ) = StructuredProductFactEvidence(
        EvidenceId(evidenceId),
        setOf("tool:fact"),
        "tool:product-fact",
        observedAt,
        executionReference,
        scope,
        Evidence.CURRENT_SCHEMA_VERSION,
    )

    private fun retrieved() = RetrievedContentEvidence(
        EvidenceId("retrieved"),
        setOf("retriever:doc"),
        "retriever:knowledge",
        observedAt,
        "execution-a",
        scope,
        Evidence.CURRENT_SCHEMA_VERSION,
    )

    private fun interpretation(sourceReferences: Set<String> = setOf("fact", "retrieved")) =
        ModelInterpretationEvidence(
            EvidenceId("interpretation"),
            sourceReferences,
            "model:fixture",
            observedAt,
            "execution-a",
            scope,
            Evidence.CURRENT_SCHEMA_VERSION,
        )
}
