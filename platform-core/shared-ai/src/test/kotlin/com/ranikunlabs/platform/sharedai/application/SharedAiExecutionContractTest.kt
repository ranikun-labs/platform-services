package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.EvidenceId
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.core.ModelInterpretationEvidence
import com.ranikunlabs.platform.sharedai.core.StructuredProductFactEvidence
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
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
import java.util.concurrent.TimeoutException

class SharedAiExecutionContractTest {
    private val context = ExecutionContext(
        ExecutionScope("consumer-product", "consumer-tenant", setOf("account-1")),
        "consumer-principal",
        "consumer-request",
        "consumer-execution",
        "consumer-correlation",
    )
    private val clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `executes through neutral ports and returns reference-only evidence`() {
        val fakeModel = RecordingFakeModelGateway { request ->
            assertThat(request.executionReference).isEqualTo(context.executionReference)
            assertThat(request.scope).isEqualTo(context.scope)
            assertThat(request.productFact.value).isEqualTo("approved")
            assertThat(request.retrievedItems.map { it.content }).containsExactly("current guidance")
            modelResult()
        }

        val result = service(fakeModel).execute(command()) as ExecutionResult.Success

        assertThat(result.executionReference).isEqualTo(context.executionReference)
        assertThat(result.interpretation).isEqualTo("The supplied fact is consistent with the current guidance.")
        assertThat(result.evidence.context).isEqualTo(context)
        assertThat(result.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.RETRIEVED_CONTENT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(result.evidence.items).allMatch { it.executionReference == context.executionReference }
    }

    @Test
    fun `maps a fake model failure to the existing technical failure result`() {
        val result = service(
            RecordingFakeModelGateway { throw IllegalStateException("model unavailable") },
        ).execute(command())

        assertTechnicalFailure(result, TechnicalFailureCategory.MODEL_FAILURE)
    }

    @Test
    fun `maps a direct fake model timeout to the existing technical failure result`() {
        val result = service(
            RecordingFakeModelGateway { throw TimeoutException("deadline exceeded") },
        ).execute(command())

        assertTechnicalFailure(result, TechnicalFailureCategory.MODEL_FAILURE)
    }

    @Test
    fun `rejects malformed blank model output`() {
        val result = service(
            RecordingFakeModelGateway { modelResult(interpretation = " \t ") },
        ).execute(command())

        assertTechnicalFailure(result, TechnicalFailureCategory.MODEL_FAILURE)
    }

    @Test
    fun `rejects model output correlated to another execution or scope`() {
        val invalidResults = listOf(
            modelResult(executionReference = "another-execution"),
            modelResult(scope = ExecutionScope("another-product", "consumer-tenant", setOf("account-1"))),
        )

        invalidResults.forEach { invalidResult ->
            val result = service(RecordingFakeModelGateway { invalidResult }).execute(command())
            assertTechnicalFailure(result, TechnicalFailureCategory.CORRELATION_FAILURE)
        }
    }

    @Test
    fun `rejects a handler result whose evidence carries another execution context`() {
        val otherContext = context.copy(correlationReference = "another-correlation")
        val handler = object : SkillHandler {
            override fun skillId() = SkillId("consumer-contract")

            override fun execute(command: ExecutionCommand) =
                ExecutionResult.Success(
                    command.context.executionReference,
                    "interpretation",
                    evidence(otherContext),
                    emptyList(),
                )
        }

        val result = SharedAiExecutionService(SkillRegistry(listOf(handler))).execute(command())

        assertTechnicalFailure(result, TechnicalFailureCategory.CORRELATION_FAILURE)
    }

    @Test
    fun `rejects a handler result carrying another execution reference`() {
        val handler = object : SkillHandler {
            override fun skillId() = SkillId("consumer-contract")

            override fun execute(command: ExecutionCommand) =
                ExecutionResult.TechnicalFailure("another-execution", TechnicalFailureCategory.MODEL_FAILURE)
        }

        val result = SharedAiExecutionService(SkillRegistry(listOf(handler))).execute(command())

        assertTechnicalFailure(result, TechnicalFailureCategory.CORRELATION_FAILURE)
    }

    @Test
    fun `permits repeated command invocation and calls every port again`() {
        var productToolCalls = 0
        var retrieverCalls = 0
        val fakeModel = RecordingFakeModelGateway { modelResult() }
        val service = SharedAiExecutionService(
            SkillRegistry(
                listOf(
                    ReviewKnowledgeSkillHandler(
                        ProductToolPort {
                            productToolCalls += 1
                            fact()
                        },
                        RetrieverPort { _, _ ->
                            retrieverCalls += 1
                            listOf(retrieved())
                        },
                        fakeModel,
                        NoOpExecutionTelemetry,
                        PortResultCorrelationValidator(),
                        clock,
                    ),
                ),
            ),
        )

        val repeatedCommand = command()
        val first = service.execute(repeatedCommand) as ExecutionResult.Success
        val second = service.execute(repeatedCommand) as ExecutionResult.Success

        assertThat(productToolCalls).isEqualTo(2)
        assertThat(retrieverCalls).isEqualTo(2)
        assertThat(fakeModel.requests).hasSize(2)
        assertThat(first.evidence.items.map { it.evidenceId })
            .containsExactlyElementsOf(second.evidence.items.map { it.evidenceId })
    }

    private fun service(modelGateway: ModelGateway): SharedAiExecutionService =
        SharedAiExecutionService(
            SkillRegistry(
                listOf(
                    ReviewKnowledgeSkillHandler(
                        ProductToolPort { fact() },
                        RetrieverPort { _, _ -> listOf(retrieved()) },
                        modelGateway,
                        NoOpExecutionTelemetry,
                        PortResultCorrelationValidator(),
                        clock,
                    ),
                ),
            ),
        )

    private fun command() = ExecutionCommand(context, SkillId.REVIEW_KNOWLEDGE, "review current guidance")

    private fun fact() =
        ProductFact(
            context.executionReference,
            context.scope,
            "tool:fact-1",
            "tool:consumer",
            "approval-state",
            "approved",
        )

    private fun retrieved() =
        RetrievedItem(
            context.executionReference,
            context.scope,
            "retriever:document-1",
            "retriever:consumer",
            "current guidance",
        )

    private fun modelResult(
        executionReference: String = context.executionReference,
        scope: ExecutionScope = context.scope,
        interpretation: String = "The supplied fact is consistent with the current guidance.",
    ) = ModelResult(executionReference, scope, "model:interpretation", "model:fake", interpretation)

    private fun evidence(evidenceContext: ExecutionContext): Evidence {
        val factId = EvidenceId("fact-evidence")
        return Evidence(
            evidenceContext,
            listOf(
                StructuredProductFactEvidence(
                    factId,
                    setOf("tool:fact-1"),
                    "tool:consumer",
                    clock.instant(),
                    evidenceContext.executionReference,
                    evidenceContext.scope,
                    Evidence.CURRENT_SCHEMA_VERSION,
                ),
                ModelInterpretationEvidence(
                    EvidenceId("model-evidence"),
                    setOf(factId.value),
                    "model:fake",
                    clock.instant(),
                    evidenceContext.executionReference,
                    evidenceContext.scope,
                    Evidence.CURRENT_SCHEMA_VERSION,
                ),
            ),
        )
    }

    private fun assertTechnicalFailure(result: ExecutionResult, category: TechnicalFailureCategory) {
        assertThat(result)
            .isEqualTo(ExecutionResult.TechnicalFailure(context.executionReference, category))
    }

    private class RecordingFakeModelGateway(
        private val behavior: (ModelRequest) -> ModelResult,
    ) : ModelGateway {
        val requests = mutableListOf<ModelRequest>()

        override fun interpret(request: ModelRequest): ModelResult {
            requests += request
            return behavior(request)
        }
    }

    private object NoOpExecutionTelemetry : com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry {
        override fun record(
            context: ExecutionContext,
            skillId: String,
            measurement: com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement,
        ) = Unit
    }
}
