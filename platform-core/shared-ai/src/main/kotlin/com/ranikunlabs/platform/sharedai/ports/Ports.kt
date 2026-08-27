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
)

fun interface ModelGateway {
    fun interpret(request: ModelRequest): ModelResult
}

enum class ExecutionStage {
    PRODUCT_TOOL,
    RETRIEVER,
    MODEL,
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
