package com.ranikunlabs.platform.sharedai.core

enum class EvidenceCategory {
    STRUCTURED_PRODUCT_FACT,
    RETRIEVED_CONTENT,
    MODEL_INTERPRETATION,
}

sealed interface EvidenceItem {
    val category: EvidenceCategory
    val executionReference: String
    val scope: ExecutionScope
    val sourceReference: String
}

data class StructuredProductFactEvidence(
    override val executionReference: String,
    override val scope: ExecutionScope,
    override val sourceReference: String,
    val factName: String,
    val factValue: String,
) : EvidenceItem {
    override val category = EvidenceCategory.STRUCTURED_PRODUCT_FACT
}

data class RetrievedContentEvidence(
    override val executionReference: String,
    override val scope: ExecutionScope,
    override val sourceReference: String,
    val content: String,
) : EvidenceItem {
    override val category = EvidenceCategory.RETRIEVED_CONTENT
}

data class ModelInterpretationEvidence(
    override val executionReference: String,
    override val scope: ExecutionScope,
    override val sourceReference: String,
    val interpretation: String,
    val inputSourceReferences: Set<String>,
) : EvidenceItem {
    override val category = EvidenceCategory.MODEL_INTERPRETATION
}

class Evidence(
    val context: ExecutionContext,
    items: List<EvidenceItem>,
) {
    val items: List<EvidenceItem> = items.toList()

    init {
        require(this.items.isNotEmpty()) { "evidence must not be empty" }
        this.items.forEach { item ->
            require(item.executionReference == context.executionReference) {
                "evidence executionReference must match the execution context"
            }
            require(context.scope.contains(item.scope)) {
                "evidence scope must be contained by the execution context"
            }
            require(item.sourceReference.isNotBlank()) { "evidence sourceReference must not be blank" }
        }
    }
}
