package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionStage
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelStreamEvent
import com.ranikunlabs.platform.sharedai.ports.ModelStreamObserver
import com.ranikunlabs.platform.sharedai.ports.ModelUsage
import com.ranikunlabs.platform.sharedai.ports.StreamingModelGateway
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface ExecutionStreamEvent {
    val executionReference: String

    data class Started(
        override val executionReference: String,
    ) : ExecutionStreamEvent

    data class ContentDelta(
        override val executionReference: String,
        val text: String,
    ) : ExecutionStreamEvent

    class Completed(
        override val executionReference: String,
        val modelSourceReference: String,
        val modelProducerReference: String,
        val usage: ModelUsage?,
        val evidence: Evidence,
        warnings: List<String>,
    ) : ExecutionStreamEvent {
        val warnings: List<String> = java.util.List.copyOf(warnings)
    }

    data class Failed(
        override val executionReference: String,
        val category: TechnicalFailureCategory,
    ) : ExecutionStreamEvent
}

fun interface ExecutionStreamObserver {
    fun onEvent(event: ExecutionStreamEvent)
}

fun interface ExecutionStreamCancellation {
    fun cancel()
}

interface StreamingSkillHandler {
    fun stream(command: ExecutionCommand, observer: ExecutionStreamObserver): ExecutionStreamCancellation

    fun skillId(): SkillId
}

class StreamingSkillRegistry(handlers: List<StreamingSkillHandler>) {
    private val handlersById: Map<SkillId, StreamingSkillHandler>

    init {
        val duplicates = handlers.groupingBy(StreamingSkillHandler::skillId).eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate streaming SkillId registration: $duplicates" }
        handlersById = handlers.associateBy(StreamingSkillHandler::skillId)
    }

    fun find(skillId: SkillId): StreamingSkillHandler? = handlersById[skillId]
}

class SharedAiStreamingExecutionService(private val registry: StreamingSkillRegistry) {
    fun stream(command: ExecutionCommand, observer: ExecutionStreamObserver): ExecutionStreamCancellation {
        val handler = registry.find(command.skillId)
        if (handler == null) {
            observer.onEvent(
                ExecutionStreamEvent.Failed(
                    command.context.executionReference,
                    TechnicalFailureCategory.UNSUPPORTED_SKILL,
                ),
            )
            return ExecutionStreamCancellation {}
        }
        return handler.stream(command, observer)
    }
}

class ReviewKnowledgeStreamingSkillHandler(
    private val streamingModelGateway: StreamingModelGateway,
    private val telemetry: ExecutionTelemetry,
    private val support: ReviewKnowledgeExecutionSupport,
) : StreamingSkillHandler {
    override fun skillId(): SkillId = SkillId.REVIEW_KNOWLEDGE

    override fun stream(
        command: ExecutionCommand,
        observer: ExecutionStreamObserver,
    ): ExecutionStreamCancellation {
        val totalStarted = System.nanoTime()
        val modelStarted = AtomicLong()
        val firstTokenRecorded = AtomicBoolean()
        val terminal = AtomicBoolean()
        val providerCancellation = AtomicReference<com.ranikunlabs.platform.sharedai.ports.ModelStreamCancellation?>()

        fun recordTerminal(category: TechnicalFailureCategory?) {
            telemetry.record(
                command.context,
                skillId().value,
                ExecutionMeasurement(ExecutionStage.TOTAL, System.nanoTime() - totalStarted, category?.name),
            )
        }

        val prepared = try {
            support.prepare(command)
        } catch (failure: ExecutionStageFailure) {
            terminal.set(true)
            observer.onEvent(ExecutionStreamEvent.Failed(command.context.executionReference, failure.category))
            recordTerminal(failure.category)
            return ExecutionStreamCancellation {}
        }

        val gatewayObserver = ModelStreamObserver gatewayObserver@{ event ->
            if (terminal.get()) return@gatewayObserver
            when (event) {
                is ModelStreamEvent.Started -> {
                    modelStarted.compareAndSet(0, System.nanoTime())
                    observer.onEvent(ExecutionStreamEvent.Started(event.executionReference))
                }

                is ModelStreamEvent.ContentDelta -> {
                    val modelStart = modelStarted.get().takeIf { it != 0L } ?: totalStarted
                    if (firstTokenRecorded.compareAndSet(false, true)) {
                        telemetry.record(
                            command.context,
                            skillId().value,
                            ExecutionMeasurement(
                                ExecutionStage.MODEL_FIRST_TOKEN,
                                System.nanoTime() - modelStart,
                                null,
                            ),
                        )
                    }
                    observer.onEvent(ExecutionStreamEvent.ContentDelta(event.executionReference, event.text))
                }

                is ModelStreamEvent.Completed -> {
                    try {
                        val evidence = support.validateAndCreateEvidence(command, prepared, event.completion)
                        if (terminal.compareAndSet(false, true)) {
                            telemetry.record(
                                command.context,
                                skillId().value,
                                ExecutionMeasurement(
                                    ExecutionStage.MODEL_STREAM,
                                    System.nanoTime() - (modelStarted.get().takeIf { it != 0L } ?: totalStarted),
                                    null,
                                ),
                            )
                            observer.onEvent(
                                ExecutionStreamEvent.Completed(
                                    event.executionReference,
                                    event.completion.sourceReference,
                                    event.completion.producerReference,
                                    event.completion.usage,
                                    evidence,
                                    if (prepared.retrievedItems.isEmpty()) {
                                        listOf("NO_RETRIEVED_CONTENT")
                                    } else {
                                        emptyList()
                                    },
                                ),
                            )
                            recordTerminal(null)
                        }
                    } catch (failure: ExecutionStageFailure) {
                        if (terminal.compareAndSet(false, true)) {
                            observer.onEvent(
                                ExecutionStreamEvent.Failed(event.executionReference, failure.category),
                            )
                            recordTerminal(failure.category)
                        }
                    }
                }

                is ModelStreamEvent.Failed -> {
                    val category = event.category.toTechnicalFailureCategory()
                    if (terminal.compareAndSet(false, true)) {
                        telemetry.record(
                            command.context,
                            skillId().value,
                            ExecutionMeasurement(
                                ExecutionStage.MODEL_STREAM,
                                System.nanoTime() - (modelStarted.get().takeIf { it != 0L } ?: totalStarted),
                                category.name,
                            ),
                        )
                        observer.onEvent(ExecutionStreamEvent.Failed(event.executionReference, category))
                        recordTerminal(category)
                    }
                }
            }
        }

        try {
            val cancellation = streamingModelGateway.stream(prepared.modelRequest, gatewayObserver)
            providerCancellation.set(cancellation)
            if (terminal.get()) cancellation.cancel()
        } catch (_: RuntimeException) {
            if (terminal.compareAndSet(false, true)) {
                observer.onEvent(
                    ExecutionStreamEvent.Failed(
                        command.context.executionReference,
                        TechnicalFailureCategory.MODEL_FAILURE,
                    ),
                )
                recordTerminal(TechnicalFailureCategory.MODEL_FAILURE)
            }
        }

        return ExecutionStreamCancellation {
            if (terminal.compareAndSet(false, true)) {
                providerCancellation.get()?.cancel()
                recordTerminal(TechnicalFailureCategory.CANCELLED)
            }
        }
    }
}
