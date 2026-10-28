package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.EvidenceId
import com.ranikunlabs.platform.sharedai.core.ModelInterpretationEvidence
import com.ranikunlabs.platform.sharedai.core.RetrievedContentEvidence
import com.ranikunlabs.platform.sharedai.core.StructuredProductFactEvidence
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionStage
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import java.time.Clock

class ReviewKnowledgeSkillHandler(
    private val productTool: ProductToolPort,
    private val retriever: RetrieverPort,
    private val modelGateway: ModelGateway,
    private val telemetry: ExecutionTelemetry,
    private val correlationValidator: PortResultCorrelationValidator,
    private val clock: Clock,
) : SkillHandler {
    override fun skillId(): SkillId = SkillId.REVIEW_KNOWLEDGE

    override fun execute(command: ExecutionCommand): ExecutionResult {
        val started = System.nanoTime()
        var terminalFailure: TechnicalFailureCategory? = null
        return try {
            val fact = measured(command, ExecutionStage.PRODUCT_TOOL, TechnicalFailureCategory.PRODUCT_TOOL_FAILURE) {
                productTool.readFact(command.context)
            }
            measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
                correlationValidator.validate(command.context, fact)
            }
            val factObservedAt = clock.instant()

            val retrieved = measured(command, ExecutionStage.RETRIEVER, TechnicalFailureCategory.RETRIEVER_FAILURE) {
                java.util.List.copyOf(retriever.retrieve(command.context, command.query))
            }
            measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
                retrieved.forEach { correlationValidator.validate(command.context, it) }
            }
            val retrievalObservedAt = clock.instant()

            val modelResult = measured(command, ExecutionStage.MODEL, TechnicalFailureCategory.MODEL_FAILURE) {
                val result = modelGateway.interpret(
                    ModelRequest(
                        command.context.executionReference,
                        command.context.scope,
                        command.query,
                        fact,
                        retrieved,
                    ),
                )
                require(result.interpretation.isNotBlank()) { "model interpretation must not be blank" }
                result
            }
            measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
                correlationValidator.validate(command.context, modelResult)
            }
            val modelObservedAt = clock.instant()

            val factEvidenceId = evidenceId(command, EvidenceCategory.STRUCTURED_PRODUCT_FACT, 0)
            val retrievedEvidenceIds = retrieved.indices.map { index ->
                evidenceId(command, EvidenceCategory.RETRIEVED_CONTENT, index)
            }
            val sourceEvidenceReferences = java.util.Set.copyOf(
                listOf(factEvidenceId.value) + retrievedEvidenceIds.map(EvidenceId::value),
            )
            val evidenceItems = listOf(
                StructuredProductFactEvidence(
                    factEvidenceId,
                    setOf(fact.sourceReference),
                    fact.producerReference,
                    factObservedAt,
                    fact.executionReference,
                    fact.scope,
                    Evidence.CURRENT_SCHEMA_VERSION,
                ),
            ) + retrieved.mapIndexed { index, item ->
                RetrievedContentEvidence(
                    retrievedEvidenceIds[index],
                    setOf(item.sourceReference),
                    item.producerReference,
                    retrievalObservedAt,
                    item.executionReference,
                    item.scope,
                    Evidence.CURRENT_SCHEMA_VERSION,
                )
            } + ModelInterpretationEvidence(
                evidenceId(command, EvidenceCategory.MODEL_INTERPRETATION, 0),
                sourceEvidenceReferences,
                modelResult.producerReference,
                modelObservedAt,
                modelResult.executionReference,
                modelResult.scope,
                Evidence.CURRENT_SCHEMA_VERSION,
            )
            ExecutionResult.Success(
                command.context.executionReference,
                modelResult.interpretation,
                Evidence(command.context, evidenceItems),
                if (retrieved.isEmpty()) listOf("NO_RETRIEVED_CONTENT") else emptyList(),
                modelResult.usage,
            )
        } catch (failure: StageFailure) {
            terminalFailure = failure.category
            ExecutionResult.TechnicalFailure(command.context.executionReference, failure.category)
        } finally {
            telemetry.record(
                command.context,
                skillId().value,
                ExecutionMeasurement(ExecutionStage.TOTAL, System.nanoTime() - started, terminalFailure?.name),
            )
        }
    }

    private fun evidenceId(
        command: ExecutionCommand,
        category: EvidenceCategory,
        ordinal: Int,
    ): EvidenceId = EvidenceId(
        "${command.context.executionReference}:${category.name.lowercase().replace('_', '-')}:$ordinal",
    )

    private fun <T> measured(
        command: ExecutionCommand,
        stage: ExecutionStage,
        category: TechnicalFailureCategory,
        action: () -> T,
    ): T {
        val started = System.nanoTime()
        var failure: TechnicalFailureCategory? = null
        return try {
            action()
        } catch (exception: Exception) {
            failure = category
            throw StageFailure(category, exception)
        } finally {
            telemetry.record(
                command.context,
                skillId().value,
                ExecutionMeasurement(stage, System.nanoTime() - started, failure?.name),
            )
        }
    }

    private class StageFailure(
        val category: TechnicalFailureCategory,
        cause: Exception,
    ) : RuntimeException(cause)
}
