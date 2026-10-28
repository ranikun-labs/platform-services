package com.ranikunlabs.platform.sharedai.application

import com.jayway.jsonpath.JsonPath
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.TimeoutException

/**
 * This is a test-only logical consumer contract, not a wire DTO or an auth proof.
 * The current API has no deadline/cancellation fields or persistent duplicate suppression;
 * those capabilities are explicitly NOT_IMPLEMENTED here.
 */
class FinanceGeneralAnalysisConsumerContractTest {
    @Test
    fun `loads the independent fixture and maps context skill and business input into the current command`() {
        val fixture = loadFixture()
        val command = fixture.toCommand()

        assertThat(fixture.fixtureVersion).isEqualTo("finance-general-analysis-test-v1")
        assertThat(fixture.context).isEqualTo(expectedContext())
        assertThat(command.context).isEqualTo(fixture.context)
        assertThat(command.skillId).isEqualTo(SkillId("finance.general-analysis@1"))
        assertThat(command.query).isEqualTo(fixture.sharedAiQuery)
        assertThat(command.query).isEqualTo(canonicalSharedAiQuery(fixture.businessInput))
        assertThat(command.query).doesNotContain(
            fixture.context.scope.productReference,
            fixture.context.scope.tenantReference,
            fixture.context.principalReference,
            fixture.context.requestReference,
            fixture.context.executionReference,
            fixture.context.correlationReference,
        )
    }

    @Test
    fun `invokes the explicit skill with correlated ports and reference-only evidence`() {
        val fixture = loadFixture()
        val command = fixture.toCommand()
        val productToolContexts = mutableListOf<ExecutionContext>()
        val modelRequests = mutableListOf<ModelRequest>()
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort { context ->
                    productToolContexts += context
                    fact(context)
                },
                ModelGateway { request ->
                    modelRequests += request
                    modelResult(request)
                },
            ),
        )

        val result = service.execute(command) as ExecutionResult.Success

        assertThat(productToolContexts).containsExactly(fixture.context)
        assertThat(modelRequests).hasSize(1)
        assertThat(modelRequests.single().executionReference).isEqualTo(fixture.context.executionReference)
        assertThat(modelRequests.single().scope).isEqualTo(fixture.context.scope)
        assertThat(modelRequests.single().query).isEqualTo(fixture.sharedAiQuery)
        assertThat(result.executionReference).isEqualTo(fixture.context.executionReference)
        assertThat(result.interpretation).isEqualTo("Synthetic interpretation for the supplied input.")
        assertThat(result.evidence.context).isEqualTo(fixture.context)
        assertThat(result.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(result.evidence.items).allMatch {
            it.executionReference == fixture.context.executionReference && it.scopeReference == fixture.context.scope
        }
        assertThat(result.evidence.items.map { it.evidenceId.value }).containsExactly(
            "execution:fixture-1:structured-product-fact:0",
            "execution:fixture-1:model-interpretation:0",
        )
    }

    @Test
    fun `fails closed when the fake model returns blank output`() {
        val fixture = loadFixture()
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort { fact(it) },
                ModelGateway { request -> modelResult(request, interpretation = " \t ") },
            ),
        )

        assertTechnicalFailure(
            service.execute(fixture.toCommand()),
            TechnicalFailureCategory.MODEL_FAILURE,
        )
    }

    @Test
    fun `fails closed when evidence context mismatches the command context`() {
        val fixture = loadFixture()
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort { fact(it) },
                ModelGateway { modelResult(it) },
                evidenceContext = { it.copy(correlationReference = "correlation:other") },
            ),
        )

        assertTechnicalFailure(
            service.execute(fixture.toCommand()),
            TechnicalFailureCategory.CORRELATION_FAILURE,
        )
    }

    @Test
    fun `fails closed when the fake model result is correlated to another execution`() {
        val fixture = loadFixture()
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort { fact(it) },
                ModelGateway { request ->
                    modelResult(request).copy(executionReference = "execution:other")
                },
            ),
        )

        assertTechnicalFailure(
            service.execute(fixture.toCommand()),
            TechnicalFailureCategory.CORRELATION_FAILURE,
        )
    }

    @Test
    fun `maps fake model technical failure to the existing generic result`() {
        val fixture = loadFixture()
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort { fact(it) },
                ModelGateway { throw IllegalStateException("synthetic model failure") },
            ),
        )

        assertTechnicalFailure(
            service.execute(fixture.toCommand()),
            TechnicalFailureCategory.MODEL_FAILURE,
        )
    }

    @Test
    fun `maps a direct model timeout to the existing generic result`() {
        val fixture = loadFixture()
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort { fact(it) },
                ModelGateway { throw TimeoutException("synthetic timeout") },
            ),
        )

        assertTechnicalFailure(
            service.execute(fixture.toCommand()),
            TechnicalFailureCategory.MODEL_FAILURE,
        )
    }

    @Test
    fun `reexecutes a duplicate command because persistence backed deduplication is not implemented`() {
        val fixture = loadFixture()
        var productToolCalls = 0
        var modelCalls = 0
        val service = service(
            FinanceGeneralAnalysisTestSkillHandler(
                ProductToolPort {
                    productToolCalls += 1
                    fact(it)
                },
                ModelGateway { request ->
                    modelCalls += 1
                    modelResult(request)
                },
            ),
        )
        val duplicateCommand = fixture.toCommand()

        val first = service.execute(duplicateCommand) as ExecutionResult.Success
        val second = service.execute(duplicateCommand) as ExecutionResult.Success

        assertThat(productToolCalls).isEqualTo(2)
        assertThat(modelCalls).isEqualTo(2)
        assertThat(first.evidence.items.map { it.evidenceId })
            .containsExactlyElementsOf(second.evidence.items.map { it.evidenceId })
    }

    private fun loadFixture(): LogicalConsumerFixture {
        val bytes = requireNotNull(javaClass.getResourceAsStream(FIXTURE_RESOURCE)).use { it.readBytes() }
        assertThat(bytes).containsExactly(*EXPECTED_FIXTURE_BYTES)
        val json = String(bytes, UTF_8)
        val context = ExecutionContext(
            ExecutionScope(
                JsonPath.read(json, "$.context.scope.productReference"),
                JsonPath.read(json, "$.context.scope.tenantReference"),
                JsonPath.read<List<String>>(json, "$.context.scope.resourceReferences").toSet(),
            ),
            JsonPath.read(json, "$.context.principalReference"),
            JsonPath.read(json, "$.context.requestReference"),
            JsonPath.read(json, "$.context.executionReference"),
            JsonPath.read(json, "$.context.correlationReference"),
        )
        val businessInput = BusinessInput(
            JsonPath.read(json, "$.businessInput.question"),
            JsonPath.read<List<String>>(json, "$.businessInput.checkedChecklistItemIds"),
            JsonPath.read(json, "$.businessInput.lensRef"),
        )
        return LogicalConsumerFixture(
            JsonPath.read(json, "$.fixtureVersion"),
            context,
            SkillId(JsonPath.read(json, "$.skillId")),
            businessInput,
            JsonPath.read(json, "$.sharedAiQuery"),
        )
    }

    private fun expectedContext() = ExecutionContext(
        ExecutionScope(
            "finance",
            "tenant:fixture-1",
            setOf("checklist:fixture-1", "question:fixture-1"),
        ),
        "principal:fixture-1",
        "request:fixture-1",
        "execution:fixture-1",
        "correlation:fixture-1",
    )

    private fun canonicalSharedAiQuery(input: BusinessInput): String =
        """{"question":${quoteJson(input.question)},"checkedChecklistItemIds":[${input.checkedChecklistItemIds.joinToString(",") { quoteJson(it) }}],"lensRef":${quoteJson(input.lensRef)}}"""

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u${character.code.toString(16).padStart(4, '0')}")
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun fact(context: ExecutionContext) =
        ProductFact(
            context.executionReference,
            context.scope,
            "fixture:product-tool:fact",
            "fixture:product-tool",
            "fixture-fact",
            "present",
        )

    private fun modelResult(
        request: ModelRequest,
        interpretation: String = "Synthetic interpretation for the supplied input.",
    ) = ModelResult(
        request.executionReference,
        request.scope,
        "fixture:model:interpretation",
        "fixture:model",
        interpretation,
    )

    private fun service(handler: SkillHandler) = SharedAiExecutionService(SkillRegistry(listOf(handler)))

    private fun assertTechnicalFailure(result: ExecutionResult, category: TechnicalFailureCategory) {
        assertThat(result).isEqualTo(ExecutionResult.TechnicalFailure("execution:fixture-1", category))
    }

    private class FinanceGeneralAnalysisTestSkillHandler(
        private val productTool: ProductToolPort,
        private val modelGateway: ModelGateway,
        private val evidenceContext: (ExecutionContext) -> ExecutionContext = { it },
    ) : SkillHandler {
        private val correlationValidator = PortResultCorrelationValidator()

        override fun skillId() = SkillId("finance.general-analysis@1")

        override fun execute(command: ExecutionCommand): ExecutionResult {
            val context = command.context
            val fact = try {
                productTool.readFact(context)
            } catch (_: Exception) {
                return failure(context, TechnicalFailureCategory.PRODUCT_TOOL_FAILURE)
            }
            try {
                correlationValidator.validate(context, fact)
            } catch (_: PortResultValidationException) {
                return failure(context, TechnicalFailureCategory.CORRELATION_FAILURE)
            }

            val modelResult = try {
                modelGateway.interpret(
                    ModelRequest(
                        context.executionReference,
                        context.scope,
                        command.query,
                        fact,
                        emptyList(),
                    ),
                )
            } catch (_: Exception) {
                return failure(context, TechnicalFailureCategory.MODEL_FAILURE)
            }
            try {
                correlationValidator.validate(context, modelResult)
            } catch (_: PortResultValidationException) {
                return failure(context, TechnicalFailureCategory.CORRELATION_FAILURE)
            }

            return ExecutionResult.Success(
                context.executionReference,
                modelResult.interpretation,
                evidence(context, evidenceContext(context), fact, modelResult),
                emptyList(),
            )
        }

        private fun evidence(
            commandContext: ExecutionContext,
            envelopeContext: ExecutionContext,
            fact: ProductFact,
            modelResult: ModelResult,
        ): Evidence {
            val factEvidenceId = EvidenceId("${commandContext.executionReference}:structured-product-fact:0")
            val modelEvidenceId = EvidenceId("${commandContext.executionReference}:model-interpretation:0")
            return Evidence(
                envelopeContext,
                listOf(
                    StructuredProductFactEvidence(
                        factEvidenceId,
                        setOf(fact.sourceReference),
                        fact.producerReference,
                        OBSERVED_AT,
                        fact.executionReference,
                        fact.scope,
                        Evidence.CURRENT_SCHEMA_VERSION,
                    ),
                    ModelInterpretationEvidence(
                        modelEvidenceId,
                        setOf(factEvidenceId.value),
                        modelResult.producerReference,
                        OBSERVED_AT,
                        modelResult.executionReference,
                        modelResult.scope,
                        Evidence.CURRENT_SCHEMA_VERSION,
                    ),
                ),
            )
        }

        private fun failure(context: ExecutionContext, category: TechnicalFailureCategory) =
            ExecutionResult.TechnicalFailure(context.executionReference, category)

        private companion object {
            val OBSERVED_AT: Instant = Instant.parse("2026-10-28T00:00:00Z")
        }
    }

    private data class BusinessInput(
        val question: String,
        val checkedChecklistItemIds: List<String>,
        val lensRef: String,
    )

    private data class LogicalConsumerFixture(
        val fixtureVersion: String,
        val context: ExecutionContext,
        val skillId: SkillId,
        val businessInput: BusinessInput,
        val sharedAiQuery: String,
    ) {
        fun toCommand() = ExecutionCommand(context, skillId, sharedAiQuery)
    }

    private companion object {
        const val FIXTURE_RESOURCE = "/finance-general-analysis-consumer-fixture.json"
        val EXPECTED_FIXTURE_BYTES =
            """
            {
              "fixtureVersion": "finance-general-analysis-test-v1",
              "context": {
                "scope": {
                  "productReference": "finance",
                  "tenantReference": "tenant:fixture-1",
                  "resourceReferences": ["checklist:fixture-1", "question:fixture-1"]
                },
                "principalReference": "principal:fixture-1",
                "requestReference": "request:fixture-1",
                "executionReference": "execution:fixture-1",
                "correlationReference": "correlation:fixture-1"
              },
              "skillId": "finance.general-analysis@1",
              "businessInput": {
                "question": "Review synthetic investment notes for omitted checks.",
                "checkedChecklistItemIds": ["assumptions", "known_facts"],
                "lensRef": "general-analysis"
              },
              "sharedAiQuery": "{\"question\":\"Review synthetic investment notes for omitted checks.\",\"checkedChecklistItemIds\":[\"assumptions\",\"known_facts\"],\"lensRef\":\"general-analysis\"}"
            }
            """.trimIndent().plus("\n").toByteArray(UTF_8)
    }
}
