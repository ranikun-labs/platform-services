package com.ranikunlabs.platform.sharedai.application

import com.jayway.jsonpath.JsonPath
import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeoutException

/**
 * Test-only logical consumer fixture contract. The fixture JSON and its query are not operating DTOs
 * or an authentication proof; the production handler receives the query as an opaque string.
 */
class FinanceGeneralAnalysisConsumerContractTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `loads the independent fixture into a product context seed and explicit skill query`() {
        val fixture = loadFixture()

        assertThat(fixture.fixtureVersion).isEqualTo("finance-general-analysis-test-v2")
        assertThat(fixture.contextSeed).isEqualTo(expectedSeed())
        assertThat(fixture.skillId).isEqualTo(SkillId.FINANCE_GENERAL_ANALYSIS)
        assertThat(fixture.sharedAiQuery).isEqualTo(canonicalSharedAiQuery(fixture.businessInput))
        assertThat(fixture.sharedAiQuery).doesNotContain(
            fixture.contextSeed.scope.productReference,
            fixture.contextSeed.scope.tenantReference,
            fixture.contextSeed.principalReference,
            fixture.contextSeed.requestReference,
            requireNotNull(fixture.contextSeed.authorityReference),
            requireNotNull(fixture.contextSeed.correlationReference),
        )
    }

    @Test
    fun `registered skill passes the opaque query through and returns correlated reference only evidence`() {
        val fixture = loadFixture()
        val productToolContexts = mutableListOf<ExecutionContext>()
        val modelRequests = mutableListOf<ModelRequest>()
        val issuedExecutionReference = "shared-ai:execution-1"
        val service = service(
            handler(
                ProductToolPort { context ->
                    productToolContexts += context
                    fact(context)
                },
                ModelGateway { request ->
                    modelRequests += request
                    modelResult(request, "raw interpretation for Finance to validate")
                },
            ),
            ExecutionReferenceGenerator { issuedExecutionReference },
        )

        val result = service.submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery)
            as ExecutionResult.Success

        assertThat(productToolContexts).containsExactly(expectedContext(fixture.contextSeed, issuedExecutionReference))
        assertThat(modelRequests).hasSize(1)
        assertThat(modelRequests.single().executionReference).isEqualTo(issuedExecutionReference)
        assertThat(modelRequests.single().scope).isEqualTo(fixture.contextSeed.scope)
        assertThat(modelRequests.single().query).isEqualTo(fixture.sharedAiQuery)
        assertThat(result.executionReference).isEqualTo(issuedExecutionReference)
        assertThat(result.interpretation).isEqualTo("raw interpretation for Finance to validate")
        assertThat(result.evidence.context)
            .isEqualTo(expectedContext(fixture.contextSeed, issuedExecutionReference))
        assertThat(result.evidence.context.contextVersion).isEqualTo(ExecutionContext.CURRENT_CONTEXT_VERSION)
        assertThat(result.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(result.evidence.items).allMatch {
            it.executionReference == issuedExecutionReference && it.scopeReference == fixture.contextSeed.scope
        }
        assertThat(result.evidence.items.map { it.evidenceId.value }).containsExactly(
            "$issuedExecutionReference:structured-product-fact:0",
            "$issuedExecutionReference:model-interpretation:0",
        )
        assertThat(result.evidence.items.last().sourceReferences)
            .containsExactly(result.evidence.items.first().evidenceId.value)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `fails closed when the fake model returns blank output`() {
        val fixture = loadFixture()
        val service = service(
            handler(
                ProductToolPort { fact(it) },
                ModelGateway { request -> modelResult(request, " \t ") },
            ),
        )

        assertTechnicalFailure(
            service.submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery),
            ISSUED_EXECUTION_REFERENCE,
            TechnicalFailureCategory.MODEL_FAILURE,
        )
    }

    @Test
    fun `fails closed when model output is correlated to another execution or widened scope`() {
        val fixture = loadFixture()
        val invalidResults = listOf<(ModelRequest) -> ModelResult>(
            { request -> modelResult(request).copy(executionReference = "execution:other") },
            { request ->
                modelResult(request).copy(
                    scope = ExecutionScope(
                        fixture.contextSeed.scope.productReference,
                        fixture.contextSeed.scope.tenantReference,
                        fixture.contextSeed.scope.resourceReferences + "resource:unowned",
                    ),
                )
            },
        )

        invalidResults.forEach { invalidResult ->
            val result = service(
                handler(
                    ProductToolPort { fact(it) },
                    ModelGateway(invalidResult),
                ),
            ).submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery)
            assertTechnicalFailure(
                result,
                ISSUED_EXECUTION_REFERENCE,
                TechnicalFailureCategory.CORRELATION_FAILURE,
            )
        }
    }

    @Test
    fun `maps product tool failure to the existing generic technical result`() {
        val fixture = loadFixture()
        val service = service(
            handler(
                ProductToolPort { throw IllegalStateException("synthetic product tool failure") },
                ModelGateway { request -> modelResult(request) },
            ),
        )

        assertTechnicalFailure(
            service.submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery),
            ISSUED_EXECUTION_REFERENCE,
            TechnicalFailureCategory.PRODUCT_TOOL_FAILURE,
        )
    }

    @Test
    fun `maps fake model technical failure and direct timeout to the existing generic result`() {
        val fixture = loadFixture()
        val failures = listOf<() -> ModelResult>(
            { throw IllegalStateException("synthetic model failure") },
            { throw TimeoutException("synthetic timeout") },
        )

        failures.forEach { failure ->
            val result = service(
                handler(
                    ProductToolPort { fact(it) },
                    ModelGateway { failure() },
                ),
            ).submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery)
            assertTechnicalFailure(
                result,
                ISSUED_EXECUTION_REFERENCE,
                TechnicalFailureCategory.MODEL_FAILURE,
            )
        }
    }

    @Test
    fun `reexecutes a repeated submission because persistence backed deduplication is not implemented`() {
        val fixture = loadFixture()
        var productToolCalls = 0
        var modelCalls = 0
        var issuedReferences = 0
        val service = service(
            handler(
                ProductToolPort {
                    productToolCalls += 1
                    fact(it)
                },
                ModelGateway { request ->
                    modelCalls += 1
                    modelResult(request)
                },
            ),
            ExecutionReferenceGenerator { "shared-ai:execution-${++issuedReferences}" },
        )

        val first = service.submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery) as ExecutionResult.Success
        val second = service.submit(fixture.contextSeed, fixture.skillId, fixture.sharedAiQuery) as ExecutionResult.Success

        assertThat(productToolCalls).isEqualTo(2)
        assertThat(modelCalls).isEqualTo(2)
        assertThat(issuedReferences).isEqualTo(2)
        assertThat(first.executionReference).isNotEqualTo(second.executionReference)
        assertThat(first.evidence.items.map { it.evidenceId })
            .doesNotContainAnyElementsOf(second.evidence.items.map { it.evidenceId })
    }

    private fun loadFixture(): LogicalConsumerFixture {
        val bytes = requireNotNull(javaClass.getResourceAsStream(FIXTURE_RESOURCE)).use { it.readBytes() }
        assertThat(bytes).containsExactly(*EXPECTED_FIXTURE_BYTES)
        val json = String(bytes, UTF_8)
        val contextSeed = ExecutionContextSeed(
            scope = ExecutionScope(
                JsonPath.read(json, "$.context.scope.productReference"),
                JsonPath.read(json, "$.context.scope.tenantReference"),
                JsonPath.read<List<String>>(json, "$.context.scope.resourceReferences").toSet(),
            ),
            principalReference = JsonPath.read(json, "$.context.principalReference"),
            requestReference = JsonPath.read(json, "$.context.requestReference"),
            correlationReference = JsonPath.read(json, "$.context.correlationReference"),
            authorityReference = JsonPath.read(json, "$.context.authorityReference"),
        )
        val businessInput = BusinessInput(
            JsonPath.read(json, "$.businessInput.question"),
            JsonPath.read<List<String>>(json, "$.businessInput.checkedChecklistItemIds"),
            JsonPath.read(json, "$.businessInput.lensRef"),
        )
        return LogicalConsumerFixture(
            JsonPath.read(json, "$.fixtureVersion"),
            contextSeed,
            SkillId(JsonPath.read(json, "$.skillId")),
            businessInput,
            JsonPath.read(json, "$.sharedAiQuery"),
        )
    }

    private fun expectedSeed() = ExecutionContextSeed(
        ExecutionScope(
            "finance",
            "tenant:fixture-1",
            setOf("checklist:fixture-1", "question:fixture-1"),
        ),
        "principal:fixture-1",
        "request:fixture-1",
        "correlation:fixture-1",
        "authority:fixture-1",
    )

    private fun expectedContext(seed: ExecutionContextSeed, executionReference: String) = ExecutionContext(
        scope = seed.scope,
        principalReference = seed.principalReference,
        requestReference = seed.requestReference,
        executionReference = executionReference,
        correlationReference = seed.correlationReference,
        contextVersion = ExecutionContext.CURRENT_CONTEXT_VERSION,
        authorityReference = seed.authorityReference,
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

    private fun handler(productTool: ProductToolPort, modelGateway: ModelGateway) =
        FinanceGeneralAnalysisSkillHandler(
            productTool,
            modelGateway,
            NoOpTelemetry,
            PortResultCorrelationValidator(),
            clock,
        )

    private fun service(
        handler: SkillHandler,
        executionReferenceGenerator: ExecutionReferenceGenerator = ExecutionReferenceGenerator {
            ISSUED_EXECUTION_REFERENCE
        },
    ) = SharedAiExecutionService(SkillRegistry(listOf(handler)), executionReferenceGenerator)

    private fun assertTechnicalFailure(
        result: ExecutionResult,
        executionReference: String,
        category: TechnicalFailureCategory,
    ) {
        assertThat(result).isEqualTo(ExecutionResult.TechnicalFailure(executionReference, category))
    }

    private object NoOpTelemetry : ExecutionTelemetry {
        override fun record(context: ExecutionContext, skillId: String, measurement: ExecutionMeasurement) = Unit
    }

    private data class BusinessInput(
        val question: String,
        val checkedChecklistItemIds: List<String>,
        val lensRef: String,
    )

    private data class LogicalConsumerFixture(
        val fixtureVersion: String,
        val contextSeed: ExecutionContextSeed,
        val skillId: SkillId,
        val businessInput: BusinessInput,
        val sharedAiQuery: String,
    )

    private companion object {
        const val FIXTURE_RESOURCE = "/finance-general-analysis-consumer-fixture.json"
        const val ISSUED_EXECUTION_REFERENCE = "shared-ai:execution-1"
        val EXPECTED_FIXTURE_BYTES =
            """
            {
              "fixtureVersion": "finance-general-analysis-test-v2",
              "context": {
                "scope": {
                  "productReference": "finance",
                  "tenantReference": "tenant:fixture-1",
                  "resourceReferences": ["checklist:fixture-1", "question:fixture-1"]
                },
                "principalReference": "principal:fixture-1",
                "requestReference": "request:fixture-1",
                "correlationReference": "correlation:fixture-1",
                "authorityReference": "authority:fixture-1"
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
