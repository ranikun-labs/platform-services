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
import com.ranikunlabs.platform.sharedai.ports.ModelCompletion
import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import com.ranikunlabs.platform.sharedai.ports.ModelGatewayException
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import java.time.Clock
import java.time.Instant

class ReviewKnowledgeExecutionSupport(
    private val productTool: ProductToolPort,
    private val retriever: RetrieverPort,
    private val telemetry: ExecutionTelemetry,
    private val correlationValidator: PortResultCorrelationValidator,
    private val clock: Clock,
) {
    fun prepare(command: ExecutionCommand): PreparedReviewKnowledge {
        val fact = measured(
            command,
            ExecutionStage.PRODUCT_TOOL,
            TechnicalFailureCategory.PRODUCT_TOOL_FAILURE,
        ) {
            productTool.readFact(command.context)
        }
        measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
            correlationValidator.validate(command.context, fact)
        }
        val factObservedAt = clock.instant()

        val retrieved = measured(
            command,
            ExecutionStage.RETRIEVER,
            TechnicalFailureCategory.RETRIEVER_FAILURE,
        ) {
            java.util.List.copyOf(retriever.retrieve(command.context, command.query))
        }
        measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
            retrieved.forEach { correlationValidator.validate(command.context, it) }
        }
        val retrievalObservedAt = clock.instant()

        return PreparedReviewKnowledge(
            ModelRequest(
                command.context.executionReference,
                command.context.scope,
                command.query,
                fact,
                retrieved,
            ),
            fact,
            retrieved,
            factObservedAt,
            retrievalObservedAt,
        )
    }

    fun validateAndCreateEvidence(
        command: ExecutionCommand,
        prepared: PreparedReviewKnowledge,
        result: ModelResult,
    ): Evidence {
        measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
            correlationValidator.validate(command.context, result)
        }
        return createEvidence(
            command,
            prepared,
            result.sourceReference,
            result.producerReference,
            clock.instant(),
        )
    }

    fun validateAndCreateEvidence(
        command: ExecutionCommand,
        prepared: PreparedReviewKnowledge,
        completion: ModelCompletion,
    ): Evidence {
        measured(command, ExecutionStage.CORRELATION, TechnicalFailureCategory.CORRELATION_FAILURE) {
            correlationValidator.validate(command.context, completion)
        }
        return createEvidence(
            command,
            prepared,
            completion.sourceReference,
            completion.producerReference,
            clock.instant(),
        )
    }

    fun <T> measured(
        command: ExecutionCommand,
        stage: ExecutionStage,
        fallbackCategory: TechnicalFailureCategory,
        action: () -> T,
    ): T {
        val started = System.nanoTime()
        var failure: TechnicalFailureCategory? = null
        return try {
            action()
        } catch (exception: ModelGatewayException) {
            failure = exception.category.toTechnicalFailureCategory()
            throw ExecutionStageFailure(failure, exception)
        } catch (exception: RuntimeException) {
            failure = fallbackCategory
            throw ExecutionStageFailure(fallbackCategory, exception)
        } finally {
            telemetry.record(
                command.context,
                SkillId.REVIEW_KNOWLEDGE.value,
                ExecutionMeasurement(stage, System.nanoTime() - started, failure?.name),
            )
        }
    }

    private fun createEvidence(
        command: ExecutionCommand,
        prepared: PreparedReviewKnowledge,
        modelSourceReference: String,
        modelProducerReference: String,
        modelObservedAt: Instant,
    ): Evidence {
        val factEvidenceId = evidenceId(command, EvidenceCategory.STRUCTURED_PRODUCT_FACT, 0)
        val retrievedEvidenceIds = prepared.retrievedItems.indices.map { index ->
            evidenceId(command, EvidenceCategory.RETRIEVED_CONTENT, index)
        }
        val sourceEvidenceReferences = java.util.Set.copyOf(
            listOf(factEvidenceId.value) + retrievedEvidenceIds.map(EvidenceId::value),
        )
        val evidenceItems = listOf(
            StructuredProductFactEvidence(
                factEvidenceId,
                setOf(prepared.fact.sourceReference),
                prepared.fact.producerReference,
                prepared.factObservedAt,
                prepared.fact.executionReference,
                prepared.fact.scope,
                Evidence.CURRENT_SCHEMA_VERSION,
            ),
        ) + prepared.retrievedItems.mapIndexed { index, item ->
            RetrievedContentEvidence(
                retrievedEvidenceIds[index],
                setOf(item.sourceReference),
                item.producerReference,
                prepared.retrievalObservedAt,
                item.executionReference,
                item.scope,
                Evidence.CURRENT_SCHEMA_VERSION,
            )
        } + ModelInterpretationEvidence(
            evidenceId(command, EvidenceCategory.MODEL_INTERPRETATION, 0),
            sourceEvidenceReferences,
            modelProducerReference,
            modelObservedAt,
            command.context.executionReference,
            command.context.scope,
            Evidence.CURRENT_SCHEMA_VERSION,
        )
        require(modelSourceReference.isNotBlank()) { "model source reference must not be blank" }
        return Evidence(command.context, evidenceItems)
    }

    private fun evidenceId(
        command: ExecutionCommand,
        category: EvidenceCategory,
        ordinal: Int,
    ): EvidenceId = EvidenceId(
        "${command.context.executionReference}:${category.name.lowercase().replace('_', '-')}:$ordinal",
    )
}

class PreparedReviewKnowledge(
    val modelRequest: ModelRequest,
    val fact: ProductFact,
    retrievedItems: List<RetrievedItem>,
    val factObservedAt: Instant,
    val retrievalObservedAt: Instant,
) {
    val retrievedItems: List<RetrievedItem> = java.util.List.copyOf(retrievedItems)
}

class ExecutionStageFailure(
    val category: TechnicalFailureCategory,
    cause: RuntimeException,
) : RuntimeException(cause)

fun ModelFailureCategory.toTechnicalFailureCategory(): TechnicalFailureCategory =
    TechnicalFailureCategory.valueOf(name)
