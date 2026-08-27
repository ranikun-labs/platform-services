package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReviewKnowledgeSkillHandlerTest {
    private val context = ExecutionContext(
        ExecutionScope("product-a", "tenant-a", setOf("resource-a")),
        "principal-a",
        "request-a",
        "execution-a",
        "correlation-a",
    )

    @Test
    fun `orchestrates tool retriever and model while preserving evidence categories`() {
        val calls = mutableListOf<String>()
        val handler = handler(
            ProductToolPort {
                calls += "tool"
                fact()
            },
            RetrieverPort { _, _ ->
                calls += "retriever"
                listOf(retrieved())
            },
            ModelGateway { request ->
                calls += "model"
                assertThat(request.productFact.value).isEqualTo("3")
                assertThat(request.retrievedItems).hasSize(1)
                modelResult()
            },
        )

        val result = handler.execute(command()) as ExecutionResult.Success

        assertThat(calls).containsExactly("tool", "retriever", "model")
        assertThat(result.evidence.items.map { it.category }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.RETRIEVED_CONTENT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `empty retrieval has a deterministic warning policy`() {
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> emptyList() },
            ModelGateway { modelResult() },
        ).execute(command()) as ExecutionResult.Success

        assertThat(result.warnings).containsExactly("NO_RETRIEVED_CONTENT")
        assertThat(result.evidence.items.map { it.category }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
    }

    @Test
    fun `maps Product Tool technical failures`() {
        val result = handler(
            ProductToolPort { throw IllegalStateException("fixture failure") },
            RetrieverPort { _, _ -> emptyList() },
            ModelGateway { modelResult() },
        ).execute(command())

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("execution-a", TechnicalFailureCategory.PRODUCT_TOOL_FAILURE),
        )
    }

    @Test
    fun `maps ModelGateway technical failures`() {
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(retrieved()) },
            ModelGateway { throw IllegalStateException("fixture failure") },
        ).execute(command())

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("execution-a", TechnicalFailureCategory.MODEL_FAILURE),
        )
    }

    private fun handler(
        productTool: ProductToolPort,
        retriever: RetrieverPort,
        modelGateway: ModelGateway,
    ) = ReviewKnowledgeSkillHandler(productTool, retriever, modelGateway, NoOpTelemetry)

    private fun command() = ExecutionCommand(context, SkillId.REVIEW_KNOWLEDGE, "review the current state")

    private fun fact() = ProductFact("execution-a", context.scope, "tool:fact", "count", "3")

    private fun retrieved() = RetrievedItem("execution-a", context.scope, "retriever:doc", "approved guidance")

    private fun modelResult() = ModelResult("execution-a", context.scope, "model:fixture", "reviewed")

    private object NoOpTelemetry : ExecutionTelemetry {
        override fun record(context: ExecutionContext, skillId: String, measurement: ExecutionMeasurement) = Unit
    }
}
