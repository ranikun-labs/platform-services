package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.EvidenceId
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.core.ModelInterpretationEvidence
import com.ranikunlabs.platform.sharedai.core.StructuredProductFactEvidence
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SharedAiExecutionSubmissionTest {
    private val seed = ExecutionContextSeed(
        ExecutionScope("finance", "tenant:trusted-1", setOf("question:1", "checklist:1")),
        "principal:trusted-1",
        "request:trusted-1",
        "correlation:trusted-1",
        "authority:opaque-1",
    )
    private val clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `issues execution reference and dispatches explicit skill with versioned context and correlated evidence`() {
        val productToolContexts = mutableListOf<ExecutionContext>()
        val modelRequests = mutableListOf<ModelRequest>()
        var reviewHandlerCalls = 0
        val financeHandler = financeHandler(
            ProductToolPort { context ->
                productToolContexts += context
                fact(context)
            },
            ModelGateway { request ->
                modelRequests += request
                modelResult(request)
            },
        )
        val reviewHandler = object : SkillHandler {
            override fun skillId() = SkillId.REVIEW_KNOWLEDGE

            override fun execute(command: ExecutionCommand): ExecutionResult {
                reviewHandlerCalls += 1
                return ExecutionResult.TechnicalFailure(
                    command.context.executionReference,
                    TechnicalFailureCategory.MODEL_FAILURE,
                )
            }
        }
        val service = service(SkillRegistry(listOf(reviewHandler, financeHandler)))

        val result = service.submit(seed, SkillId.FINANCE_GENERAL_ANALYSIS, "formatted Finance query")
            as ExecutionResult.Success

        val expectedContext = issuedContext("shared-ai:execution-1")
        assertThat(productToolContexts).containsExactly(expectedContext)
        assertThat(modelRequests).hasSize(1)
        assertThat(modelRequests.single().query).isEqualTo("formatted Finance query")
        assertThat(modelRequests.single().executionReference).isEqualTo("shared-ai:execution-1")
        assertThat(result.executionReference).isEqualTo("shared-ai:execution-1")
        assertThat(result.evidence.context).isEqualTo(expectedContext)
        assertThat(result.evidence.context.contextVersion).isEqualTo(ExecutionContext.CURRENT_CONTEXT_VERSION)
        assertThat(result.evidence.context.authorityReference).isEqualTo("authority:opaque-1")
        assertThat(result.evidence.items).allMatch {
            it.executionReference == result.executionReference && it.scopeReference == seed.scope
        }
        assertThat(reviewHandlerCalls).isZero()
    }

    @Test
    fun `reexecutes repeated submissions and issues a new reference for each`() {
        var productToolCalls = 0
        var modelCalls = 0
        var referencesIssued = 0
        val handler = financeHandler(
            ProductToolPort {
                productToolCalls += 1
                fact(it)
            },
            ModelGateway { request ->
                modelCalls += 1
                modelResult(request)
            },
        )
        val service = SharedAiExecutionService(
            SkillRegistry(listOf(handler)),
            ExecutionReferenceGenerator { "shared-ai:repeated-${++referencesIssued}" },
        )

        val first = service.submit(seed, SkillId.FINANCE_GENERAL_ANALYSIS, "same query") as ExecutionResult.Success
        val second = service.submit(seed, SkillId.FINANCE_GENERAL_ANALYSIS, "same query") as ExecutionResult.Success

        assertThat(first.executionReference).isEqualTo("shared-ai:repeated-1")
        assertThat(second.executionReference).isEqualTo("shared-ai:repeated-2")
        assertThat(productToolCalls).isEqualTo(2)
        assertThat(modelCalls).isEqualTo(2)
        assertThat(referencesIssued).isEqualTo(2)
    }

    @Test
    fun `rejects a Product context bound to another product before invoking the Finance skill`() {
        var productToolCalls = 0
        val handler = financeHandler(
            ProductToolPort {
                productToolCalls += 1
                fact(it)
            },
            ModelGateway { modelResult(it) },
        )
        val service = service(SkillRegistry(listOf(handler)))
        val otherProductSeed = seed.copy(
            scope = ExecutionScope("carelog", seed.scope.tenantReference, seed.scope.resourceReferences),
        )

        val result = service.submit(otherProductSeed, SkillId.FINANCE_GENERAL_ANALYSIS, "query")

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("shared-ai:execution-1", TechnicalFailureCategory.CORRELATION_FAILURE),
        )
        assertThat(productToolCalls).isZero()
    }

    @Test
    fun `returns unsupported skill failure with a Shared AI issued execution reference`() {
        val service = service(SkillRegistry(emptyList()))

        val result = service.submit(seed, SkillId("finance.unknown@1"), "query")

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("shared-ai:execution-1", TechnicalFailureCategory.UNSUPPORTED_SKILL),
        )
    }

    @Test
    fun `fails closed when a handler returns another execution reference`() {
        val handler = object : ProductBoundSkillHandler {
            override fun skillId() = SkillId.FINANCE_GENERAL_ANALYSIS

            override fun requiredProductReference() = "finance"

            override fun execute(command: ExecutionCommand) =
                ExecutionResult.TechnicalFailure("foreign-execution", TechnicalFailureCategory.MODEL_FAILURE)
        }
        val service = service(SkillRegistry(listOf(handler)))

        val result = service.submit(seed, SkillId.FINANCE_GENERAL_ANALYSIS, "query")

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("shared-ai:execution-1", TechnicalFailureCategory.CORRELATION_FAILURE),
        )
    }

    @Test
    fun `fails closed when a handler returns evidence with another context version`() {
        val handler = object : ProductBoundSkillHandler {
            override fun skillId() = SkillId.FINANCE_GENERAL_ANALYSIS

            override fun requiredProductReference() = "finance"

            override fun execute(command: ExecutionCommand): ExecutionResult {
                val mismatchedContext = command.context.copy(contextVersion = "future")
                return ExecutionResult.Success(
                    command.context.executionReference,
                    "interpretation",
                    evidence(mismatchedContext),
                    emptyList(),
                )
            }
        }
        val service = service(SkillRegistry(listOf(handler)))

        val result = service.submit(seed, SkillId.FINANCE_GENERAL_ANALYSIS, "query")

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("shared-ai:execution-1", TechnicalFailureCategory.CORRELATION_FAILURE),
        )
    }

    @Test
    fun `rejects unsupported context version on the compatibility command path`() {
        val handler = financeHandler(
            ProductToolPort { fact(it) },
            ModelGateway { modelResult(it) },
        )
        val service = service(SkillRegistry(listOf(handler)))
        val unsupportedContext = issuedContext("caller-execution").copy(contextVersion = "future")

        val result = service.execute(
            ExecutionCommand(unsupportedContext, SkillId.FINANCE_GENERAL_ANALYSIS, "query"),
        )

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("caller-execution", TechnicalFailureCategory.CORRELATION_FAILURE),
        )
    }

    @Test
    fun `rejects missing principal context at seed construction`() {
        assertThatThrownBy {
            ExecutionContextSeed(seed.scope, " ", seed.requestReference, seed.correlationReference)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("principalReference")
    }

    private fun service(registry: SkillRegistry) =
        SharedAiExecutionService(registry, ExecutionReferenceGenerator { "shared-ai:execution-1" })

    private fun financeHandler(productTool: ProductToolPort, modelGateway: ModelGateway) =
        FinanceGeneralAnalysisSkillHandler(
            productTool,
            modelGateway,
            NoOpTelemetry,
            PortResultCorrelationValidator(),
            clock,
        )

    private fun issuedContext(executionReference: String) = ExecutionContext(
        scope = seed.scope,
        principalReference = seed.principalReference,
        requestReference = seed.requestReference,
        executionReference = executionReference,
        correlationReference = seed.correlationReference,
        contextVersion = ExecutionContext.CURRENT_CONTEXT_VERSION,
        authorityReference = seed.authorityReference,
    )

    private fun fact(context: ExecutionContext) =
        ProductFact(
            context.executionReference,
            context.scope,
            "fixture:product-fact",
            "fixture:product-tool",
            "fixture-fact",
            "present",
        )

    private fun modelResult(request: ModelRequest) =
        ModelResult(
            request.executionReference,
            request.scope,
            "fixture:model-result",
            "fixture:model",
            "opaque interpretation",
        )

    private fun evidence(context: ExecutionContext): Evidence {
        val factId = EvidenceId("${context.executionReference}:fact")
        return Evidence(
            context,
            listOf(
                StructuredProductFactEvidence(
                    factId,
                    setOf("fixture:product-fact"),
                    "fixture:product-tool",
                    clock.instant(),
                    context.executionReference,
                    context.scope,
                    Evidence.CURRENT_SCHEMA_VERSION,
                ),
                ModelInterpretationEvidence(
                    EvidenceId("${context.executionReference}:model"),
                    setOf(factId.value),
                    "fixture:model",
                    clock.instant(),
                    context.executionReference,
                    context.scope,
                    Evidence.CURRENT_SCHEMA_VERSION,
                ),
            ),
        )
    }

    private object NoOpTelemetry : ExecutionTelemetry {
        override fun record(context: ExecutionContext, skillId: String, measurement: ExecutionMeasurement) = Unit
    }
}
