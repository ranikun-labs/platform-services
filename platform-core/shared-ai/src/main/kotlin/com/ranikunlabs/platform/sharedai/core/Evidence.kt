package com.ranikunlabs.platform.sharedai.core

import java.time.Instant

enum class EvidenceCategory {
    STRUCTURED_PRODUCT_FACT,
    RETRIEVED_CONTENT,
    MODEL_INTERPRETATION,
}

data class EvidenceId(val value: String) {
    init {
        require(value.isNotBlank()) { "EvidenceId must not be blank" }
    }
}

sealed interface EvidenceItem {
    val evidenceId: EvidenceId
    val kind: EvidenceCategory
    val sourceReferences: Set<String>
    val producerReference: String
    val observedAt: Instant
    val executionReference: String
    val scopeReference: ExecutionScope
    val schemaVersion: String
}

abstract class ReferenceEvidenceItem protected constructor(
    final override val evidenceId: EvidenceId,
    final override val kind: EvidenceCategory,
    sourceReferences: Set<String>,
    final override val producerReference: String,
    final override val observedAt: Instant,
    final override val executionReference: String,
    final override val scopeReference: ExecutionScope,
    final override val schemaVersion: String,
) : EvidenceItem {
    final override val sourceReferences: Set<String> = java.util.Set.copyOf(sourceReferences)

    init {
        require(this.sourceReferences.isNotEmpty()) { "evidence sourceReferences must not be empty" }
        require(this.sourceReferences.none(String::isBlank)) { "evidence sourceReferences must not contain blank values" }
        require(producerReference.isNotBlank()) { "evidence producerReference must not be blank" }
        require(executionReference.isNotBlank()) { "evidence executionReference must not be blank" }
        require(schemaVersion.isNotBlank()) { "evidence schemaVersion must not be blank" }
    }
}

class StructuredProductFactEvidence(
    evidenceId: EvidenceId,
    sourceReferences: Set<String>,
    producerReference: String,
    observedAt: Instant,
    executionReference: String,
    scopeReference: ExecutionScope,
    schemaVersion: String,
) : ReferenceEvidenceItem(
    evidenceId,
    EvidenceCategory.STRUCTURED_PRODUCT_FACT,
    sourceReferences,
    producerReference,
    observedAt,
    executionReference,
    scopeReference,
    schemaVersion,
)

class RetrievedContentEvidence(
    evidenceId: EvidenceId,
    sourceReferences: Set<String>,
    producerReference: String,
    observedAt: Instant,
    executionReference: String,
    scopeReference: ExecutionScope,
    schemaVersion: String,
) : ReferenceEvidenceItem(
    evidenceId,
    EvidenceCategory.RETRIEVED_CONTENT,
    sourceReferences,
    producerReference,
    observedAt,
    executionReference,
    scopeReference,
    schemaVersion,
)

class ModelInterpretationEvidence(
    evidenceId: EvidenceId,
    sourceReferences: Set<String>,
    producerReference: String,
    observedAt: Instant,
    executionReference: String,
    scopeReference: ExecutionScope,
    schemaVersion: String,
) : ReferenceEvidenceItem(
    evidenceId,
    EvidenceCategory.MODEL_INTERPRETATION,
    sourceReferences,
    producerReference,
    observedAt,
    executionReference,
    scopeReference,
    schemaVersion,
)

class Evidence(
    val context: ExecutionContext,
    items: List<EvidenceItem>,
) {
    val items: List<EvidenceItem> = java.util.List.copyOf(items)

    init {
        require(this.items.isNotEmpty()) { "evidence must not be empty" }
        require(this.items.map(EvidenceItem::evidenceId).distinct().size == this.items.size) {
            "evidence identities must be unique"
        }
        this.items.forEach { item ->
            require(item.executionReference == context.executionReference) {
                "evidence executionReference must match the execution context"
            }
            require(context.scope.contains(item.scopeReference)) {
                "evidence scope must be contained by the execution context"
            }
        }

        val sourceEvidenceReferences = this.items
            .filter { it.kind != EvidenceCategory.MODEL_INTERPRETATION }
            .mapTo(mutableSetOf()) { it.evidenceId.value }
        this.items.filterIsInstance<ModelInterpretationEvidence>().forEach { interpretation ->
            require(interpretation.sourceReferences.all(sourceEvidenceReferences::contains)) {
                "model interpretation sourceReferences must identify source evidence in the same envelope"
            }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = "1"
    }
}
