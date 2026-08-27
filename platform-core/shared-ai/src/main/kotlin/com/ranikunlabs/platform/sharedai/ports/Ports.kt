package com.ranikunlabs.platform.sharedai.ports

import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope

data class ProductFact(
    val executionReference: String,
    val scope: ExecutionScope,
    val sourceReference: String,
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
    val content: String,
)

fun interface RetrieverPort {
    fun retrieve(context: ExecutionContext, query: String): List<RetrievedItem>
}

data class ModelRequest(
    val executionReference: String,
    val scope: ExecutionScope,
    val query: String,
    val productFact: ProductFact,
    val retrievedItems: List<RetrievedItem>,
)

data class ModelResult(
    val executionReference: String,
    val scope: ExecutionScope,
    val sourceReference: String,
    val interpretation: String,
)

fun interface ModelGateway {
    fun interpret(request: ModelRequest): ModelResult
}

enum class ExecutionStage {
    PRODUCT_TOOL,
    RETRIEVER,
    MODEL,
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
