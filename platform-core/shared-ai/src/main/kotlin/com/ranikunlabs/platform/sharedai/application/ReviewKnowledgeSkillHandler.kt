package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
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

class ReviewKnowledgeSkillHandler(
    private val productTool: ProductToolPort,
    private val retriever: RetrieverPort,
    private val modelGateway: ModelGateway,
    private val telemetry: ExecutionTelemetry,
) : SkillHandler {
    override fun skillId(): SkillId = SkillId.REVIEW_KNOWLEDGE

    override fun execute(command: ExecutionCommand): ExecutionResult {
        val started = System.nanoTime()
        return try {
            val fact = measured(command, ExecutionStage.PRODUCT_TOOL, TechnicalFailureCategory.PRODUCT_TOOL_FAILURE) {
                productTool.readFact(command.context)
            }
            val retrieved = measured(command, ExecutionStage.RETRIEVER, TechnicalFailureCategory.RETRIEVER_FAILURE) {
                retriever.retrieve(command.context, command.query)
            }
            val modelResult = measured(command, ExecutionStage.MODEL, TechnicalFailureCategory.MODEL_FAILURE) {
                modelGateway.interpret(
                    ModelRequest(
                        command.context.executionReference,
                        command.context.scope,
                        command.query,
                        fact,
                        retrieved,
                    ),
                )
            }

            val evidenceItems = buildList {
                add(
                    StructuredProductFactEvidence(
                        fact.executionReference,
                        fact.scope,
                        fact.sourceReference,
                        fact.name,
                        fact.value,
                    ),
                )
                retrieved.forEach { item ->
                    add(
                        RetrievedContentEvidence(
                            item.executionReference,
                            item.scope,
                            item.sourceReference,
                            item.content,
                        ),
                    )
                }
                add(
                    ModelInterpretationEvidence(
                        modelResult.executionReference,
                        modelResult.scope,
                        modelResult.sourceReference,
                        modelResult.interpretation,
                        setOf(fact.sourceReference) + retrieved.map { it.sourceReference },
                    ),
                )
            }
            ExecutionResult.Success(
                command.context.executionReference,
                modelResult.interpretation,
                Evidence(command.context, evidenceItems),
                if (retrieved.isEmpty()) listOf("NO_RETRIEVED_CONTENT") else emptyList(),
            )
        } catch (failure: StageFailure) {
            ExecutionResult.TechnicalFailure(command.context.executionReference, failure.category)
        } finally {
            telemetry.record(
                command.context,
                skillId().value,
                ExecutionMeasurement(ExecutionStage.TOTAL, System.nanoTime() - started, null),
            )
        }
    }

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
        } catch (exception: RuntimeException) {
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
        cause: RuntimeException,
    ) : RuntimeException(cause)
}
