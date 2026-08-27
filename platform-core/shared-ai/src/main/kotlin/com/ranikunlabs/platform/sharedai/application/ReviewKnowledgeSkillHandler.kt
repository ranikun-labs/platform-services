package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionStage
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway

class ReviewKnowledgeSkillHandler(
    private val modelGateway: ModelGateway,
    private val telemetry: ExecutionTelemetry,
    private val support: ReviewKnowledgeExecutionSupport,
) : SkillHandler {
    override fun skillId(): SkillId = SkillId.REVIEW_KNOWLEDGE

    override fun execute(command: ExecutionCommand): ExecutionResult {
        val started = System.nanoTime()
        var terminalFailure: TechnicalFailureCategory? = null
        return try {
            val prepared = support.prepare(command)
            val modelResult = support.measured(
                command,
                ExecutionStage.MODEL,
                TechnicalFailureCategory.MODEL_FAILURE,
            ) {
                modelGateway.interpret(prepared.modelRequest)
            }
            val evidence = support.validateAndCreateEvidence(command, prepared, modelResult)
            ExecutionResult.Success(
                command.context.executionReference,
                modelResult.interpretation,
                evidence,
                if (prepared.retrievedItems.isEmpty()) listOf("NO_RETRIEVED_CONTENT") else emptyList(),
                modelResult.usage,
            )
        } catch (failure: ExecutionStageFailure) {
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
}
