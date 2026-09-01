package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.core.ModelInterpretationEvidence
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionStage
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewKnowledgeSkillHandlerTest {
    private val context = ExecutionContext(
        ExecutionScope("product-a", "tenant-a", setOf("resource-a")),
        "principal-a",
        "request-a",
        "execution-a",
        "correlation-a",
    )
    private val clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `orchestrates explicit ports and creates reference-only evidence`() {
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
        assertThat(result.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.RETRIEVED_CONTENT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        val sourceEvidenceIds = result.evidence.items.dropLast(1).map { it.evidenceId.value }
        val interpretation = result.evidence.items.last() as ModelInterpretationEvidence
        assertThat(interpretation.sourceReferences).containsExactlyInAnyOrderElementsOf(sourceEvidenceIds)
        assertThat(result.evidence.items).allMatch { it.observedAt == clock.instant() }
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `empty retrieval has a deterministic warning and fact support`() {
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> emptyList() },
            ModelGateway { modelResult() },
        ).execute(command()) as ExecutionResult.Success

        assertThat(result.warnings).containsExactly("NO_RETRIEVED_CONTENT")
        assertThat(result.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(result.evidence.items.last().sourceReferences)
            .containsExactly(result.evidence.items.first().evidenceId.value)
    }

    @Test
    fun `rejects Product Tool wrong execution before Retriever or Model`() =
        assertProductResultRejected(fact(executionReference = "execution-b"))

    @Test
    fun `rejects Product Tool wrong product before Retriever or Model`() =
        assertProductResultRejected(fact(scope = ExecutionScope("product-b", "tenant-a", setOf("resource-a"))))

    @Test
    fun `rejects Product Tool wrong tenant before Retriever or Model`() =
        assertProductResultRejected(fact(scope = ExecutionScope("product-a", "tenant-b", setOf("resource-a"))))

    @Test
    fun `rejects Product Tool widened resources before Retriever or Model`() =
        assertProductResultRejected(
            fact(scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a", "resource-b"))),
        )

    @Test
    fun `rejects Retriever wrong execution before Model`() =
        assertRetrievedResultRejected(retrieved(executionReference = "execution-b"))

    @Test
    fun `rejects Retriever wrong product before Model`() =
        assertRetrievedResultRejected(
            retrieved(scope = ExecutionScope("product-b", "tenant-a", setOf("resource-a"))),
        )

    @Test
    fun `rejects Retriever wrong tenant before Model`() =
        assertRetrievedResultRejected(
            retrieved(scope = ExecutionScope("product-a", "tenant-b", setOf("resource-a"))),
        )

    @Test
    fun `rejects Retriever widened resources before Model`() =
        assertRetrievedResultRejected(
            retrieved(scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a", "resource-b"))),
        )

    @Test
    fun `rejects ModelResult wrong execution`() =
        assertModelResultRejected(modelResult(executionReference = "execution-b"))

    @Test
    fun `rejects ModelResult wrong scope`() =
        assertModelResultRejected(
            modelResult(scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a", "resource-b"))),
        )

    @Test
    fun `TOTAL telemetry retains Product Tool failure category`() {
        val telemetry = RecordingTelemetry()
        val result = handler(
            ProductToolPort { throw IllegalStateException("sensitive tool detail") },
            RetrieverPort { _, _ -> emptyList() },
            ModelGateway { modelResult() },
            telemetry,
        ).execute(command())

        assertFailureAndTotal(result, TechnicalFailureCategory.PRODUCT_TOOL_FAILURE, telemetry)
    }

    @Test
    fun `TOTAL telemetry retains Retriever failure category`() {
        val telemetry = RecordingTelemetry()
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> throw IllegalStateException("sensitive retrieval detail") },
            ModelGateway { modelResult() },
            telemetry,
        ).execute(command())

        assertFailureAndTotal(result, TechnicalFailureCategory.RETRIEVER_FAILURE, telemetry)
    }

    @Test
    fun `TOTAL telemetry retains Model failure category`() {
        val telemetry = RecordingTelemetry()
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(retrieved()) },
            ModelGateway { throw IllegalStateException("sensitive model detail") },
            telemetry,
        ).execute(command())

        assertFailureAndTotal(result, TechnicalFailureCategory.MODEL_FAILURE, telemetry)
    }

    private fun assertProductResultRejected(invalidFact: ProductFact) {
        var retrieverInvoked = false
        var modelInvoked = false
        val telemetry = RecordingTelemetry()
        val result = handler(
            ProductToolPort { invalidFact },
            RetrieverPort { _, _ ->
                retrieverInvoked = true
                emptyList()
            },
            ModelGateway {
                modelInvoked = true
                modelResult()
            },
            telemetry,
        ).execute(command())

        assertThat(retrieverInvoked).isFalse()
        assertThat(modelInvoked).isFalse()
        assertFailureAndTotal(result, TechnicalFailureCategory.CORRELATION_FAILURE, telemetry)
    }

    private fun assertRetrievedResultRejected(invalidItem: RetrievedItem) {
        var modelInvoked = false
        val telemetry = RecordingTelemetry()
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(invalidItem) },
            ModelGateway {
                modelInvoked = true
                modelResult()
            },
            telemetry,
        ).execute(command())

        assertThat(modelInvoked).isFalse()
        assertFailureAndTotal(result, TechnicalFailureCategory.CORRELATION_FAILURE, telemetry)
    }

    private fun assertModelResultRejected(invalidResult: ModelResult) {
        val telemetry = RecordingTelemetry()
        val result = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(retrieved()) },
            ModelGateway { invalidResult },
            telemetry,
        ).execute(command())

        assertFailureAndTotal(result, TechnicalFailureCategory.CORRELATION_FAILURE, telemetry)
    }

    private fun assertFailureAndTotal(
        result: ExecutionResult,
        category: TechnicalFailureCategory,
        telemetry: RecordingTelemetry,
    ) {
        assertThat(result).isEqualTo(ExecutionResult.TechnicalFailure("execution-a", category))
        val total = telemetry.measurements.single { it.stage == ExecutionStage.TOTAL }
        assertThat(total.failureCategory).isEqualTo(category.name)
        assertThat(total.toString()).doesNotContain("sensitive", "approved guidance", "reviewed")
    }

    private fun handler(
        productTool: ProductToolPort,
        retriever: RetrieverPort,
        modelGateway: ModelGateway,
        telemetry: ExecutionTelemetry = RecordingTelemetry(),
    ) = ReviewKnowledgeSkillHandler(
        modelGateway,
        telemetry,
        ReviewKnowledgeExecutionSupport(
            productTool,
            retriever,
            telemetry,
            PortResultCorrelationValidator(),
            clock,
        ),
    )

    private fun command() = ExecutionCommand(context, SkillId.REVIEW_KNOWLEDGE, "review the current state")

    private fun fact(
        executionReference: String = "execution-a",
        scope: ExecutionScope = context.scope,
    ) = ProductFact(executionReference, scope, "tool:fact", "tool:product-fact", "count", "3")

    private fun retrieved(
        executionReference: String = "execution-a",
        scope: ExecutionScope = context.scope,
    ) = RetrievedItem(
        executionReference,
        scope,
        "retriever:doc",
        "retriever:knowledge",
        "approved guidance",
    )

    private fun modelResult(
        executionReference: String = "execution-a",
        scope: ExecutionScope = context.scope,
    ) = ModelResult(executionReference, scope, "model:result", "model:fixture", "reviewed")

    private class RecordingTelemetry : ExecutionTelemetry {
        val measurements = mutableListOf<ExecutionMeasurement>()

        override fun record(context: ExecutionContext, skillId: String, measurement: ExecutionMeasurement) {
            measurements += measurement
        }
    }
}
