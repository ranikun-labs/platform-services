package com.ranikunlabs.platform.sharedai.ports

import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope

data class ProductFact(
    val executionReference: String,
    val scope: ExecutionScope,
    val sourceReference: String,
    val producerReference: String,
    val name: String,
    val value: String,
)

fun interface ProductToolPort {
    fun readFact(context: ExecutionContext): ProductFact
}

data class RetrievedItem(
    val executionReference: String,
    val scope: ExecutionScope,
    val sourceReference: String,
    val producerReference: String,
    val content: String,
)

fun interface RetrieverPort {
    fun retrieve(context: ExecutionContext, query: String): List<RetrievedItem>
}

class ModelRequest(
    val executionReference: String,
    val scope: ExecutionScope,
    val query: String,
    val productFact: ProductFact,
    retrievedItems: List<RetrievedItem>,
) {
    val retrievedItems: List<RetrievedItem> = java.util.List.copyOf(retrievedItems)
}

data class ModelResult(
    val executionReference: String,
    val scope: ExecutionScope,
    val sourceReference: String,
    val producerReference: String,
    val interpretation: String,
    val usage: ModelUsage? = null,
)

fun interface ModelGateway {
    fun interpret(request: ModelRequest): ModelResult
}

data class ModelUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
)

enum class ModelFailureCategory {
    AUTHENTICATION,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    INVALID_REQUEST,
    CANCELLED,
    UNKNOWN_PROVIDER_FAILURE,
}

class ModelGatewayException(
    val category: ModelFailureCategory,
    cause: Throwable? = null,
) : RuntimeException("Model provider call failed: ${category.name}", cause)

data class ModelCompletion(
    val executionReference: String,
    val scope: ExecutionScope,
    val sourceReference: String,
    val producerReference: String,
    val usage: ModelUsage?,
)

sealed interface ModelStreamEvent {
    val executionReference: String

    data class Started(
        override val executionReference: String,
    ) : ModelStreamEvent

    data class ContentDelta(
        override val executionReference: String,
        val text: String,
    ) : ModelStreamEvent {
        init {
            require(text.isNotEmpty()) { "model stream delta must not be empty" }
        }
    }

    data class Completed(
        val completion: ModelCompletion,
    ) : ModelStreamEvent {
        override val executionReference: String = completion.executionReference
    }

    data class Failed(
        override val executionReference: String,
        val category: ModelFailureCategory,
    ) : ModelStreamEvent
}

fun interface ModelStreamObserver {
    fun onEvent(event: ModelStreamEvent)
}

fun interface ModelStreamCancellation {
    fun cancel()
}

fun interface StreamingModelGateway {
    fun stream(request: ModelRequest, observer: ModelStreamObserver): ModelStreamCancellation
}

enum class ExecutionStage {
    PRODUCT_TOOL,
    RETRIEVER,
    MODEL,
    MODEL_FIRST_TOKEN,
    MODEL_STREAM,
    CORRELATION,
    TOTAL,
}

data class ExecutionMeasurement(
    val stage: ExecutionStage,
    val durationNanos: Long,
    val failureCategory: String?,
)

fun interface ExecutionTelemetry {
    fun record(
        context: ExecutionContext,
        skillId: String,
        measurement: ExecutionMeasurement,
    )
}
