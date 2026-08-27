package com.ranikunlabs.platform.sharedai.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EvidenceTest {
    private val scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a"))
    private val context = ExecutionContext(scope, "principal-a", "request-a", "execution-a", null)

    @Test
    fun `aggregates typed evidence with compatible correlation`() {
        val evidence = Evidence(
            context,
            listOf(
                StructuredProductFactEvidence("execution-a", scope, "tool:fact", "count", "3"),
                RetrievedContentEvidence("execution-a", scope, "retriever:doc", "guidance"),
                ModelInterpretationEvidence(
                    "execution-a",
                    scope,
                    "model:result",
                    "interpretation",
                    setOf("tool:fact", "retriever:doc"),
                ),
            ),
        )

        assertThat(evidence.items.map(EvidenceItem::category)).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.RETRIEVED_CONTENT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
    }

    @Test
    fun `rejects mismatched execution correlation`() {
        assertThatThrownBy {
            Evidence(
                context,
                listOf(StructuredProductFactEvidence("execution-b", scope, "tool:fact", "count", "3")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("executionReference")
    }

    @Test
    fun `rejects evidence that widens scope`() {
        val widened = ExecutionScope("product-a", "tenant-a", setOf("resource-a", "resource-b"))

        assertThatThrownBy {
            Evidence(
                context,
                listOf(StructuredProductFactEvidence("execution-a", widened, "tool:fact", "count", "3")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("scope")
    }
}
