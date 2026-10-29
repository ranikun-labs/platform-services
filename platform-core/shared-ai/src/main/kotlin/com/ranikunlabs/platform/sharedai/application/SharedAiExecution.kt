package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ModelUsage
import java.util.UUID

data class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank" }
    }

    companion object {
        @JvmField
        val REVIEW_KNOWLEDGE = SkillId("review-knowledge")

        @JvmField
        val FINANCE_GENERAL_ANALYSIS = SkillId("finance.general-analysis@1")
    }
}

data class ExecutionCommand(
    val context: ExecutionContext,
    val skillId: SkillId,
    val query: String,
) {
    init {
        require(query.isNotBlank()) { "query must not be blank" }
    }
}

/**
 * Product-issued context fields accepted at the trusted local invocation boundary.
 * Opaque identifiers preserve scope and correlation; they do not establish authorization by themselves.
 */
data class ExecutionContextSeed(
    val scope: ExecutionScope,
    val principalReference: String,
    val requestReference: String,
    val correlationReference: String? = null,
    val authorityReference: String? = null,
) {
    init {
        require(principalReference.isNotBlank()) { "principalReference must not be blank" }
        require(requestReference.isNotBlank()) { "requestReference must not be blank" }
        require(correlationReference == null || correlationReference.isNotBlank()) {
            "correlationReference must be null or non-blank"
        }
        require(authorityReference == null || authorityReference.isNotBlank()) {
            "authorityReference must be null or non-blank"
        }
    }
}

fun interface ExecutionReferenceGenerator {
    fun generate(): String
}

class UuidExecutionReferenceGenerator : ExecutionReferenceGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}

enum class TechnicalFailureCategory {
    UNSUPPORTED_SKILL,
    PRODUCT_TOOL_FAILURE,
    RETRIEVER_FAILURE,
    MODEL_FAILURE,
    CORRELATION_FAILURE,
}

sealed interface ExecutionResult {
    val executionReference: String

    class Success(
        override val executionReference: String,
        val interpretation: String,
        val evidence: Evidence,
        warnings: List<String>,
        val usage: ModelUsage? = null,
    ) : ExecutionResult {
        val warnings: List<String> = java.util.List.copyOf(warnings)

        constructor(
            executionReference: String,
            interpretation: String,
            evidence: Evidence,
            warnings: List<String>,
        ) : this(executionReference, interpretation, evidence, warnings, null)
    }

    data class TechnicalFailure(
        override val executionReference: String,
        val category: TechnicalFailureCategory,
    ) : ExecutionResult
}

interface SkillHandler {
    fun execute(command: ExecutionCommand): ExecutionResult

    fun skillId(): SkillId
}

/** Registration metadata used to reject a skill/context product mismatch before port invocation. */
interface ProductBoundSkillHandler : SkillHandler {
    fun requiredProductReference(): String
}

class SkillRegistry(handlers: List<SkillHandler>) {
    private val handlersById: Map<SkillId, SkillHandler>

    init {
        val duplicates = handlers.groupingBy(SkillHandler::skillId).eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate SkillId registration: $duplicates" }
        handlersById = handlers.associateBy(SkillHandler::skillId)
    }

    fun find(skillId: SkillId): SkillHandler? = handlersById[skillId]
}

class SharedAiExecutionService(
    private val registry: SkillRegistry,
    private val executionReferenceGenerator: ExecutionReferenceGenerator,
) {
    constructor(registry: SkillRegistry) : this(registry, UuidExecutionReferenceGenerator())

    /** Builds the full context with a Shared AI-issued reference before dispatching the explicit skill. */
    fun submit(seed: ExecutionContextSeed, skillId: SkillId, query: String): ExecutionResult {
        val context = ExecutionContext(
            scope = seed.scope,
            principalReference = seed.principalReference,
            requestReference = seed.requestReference,
            executionReference = executionReferenceGenerator.generate(),
            correlationReference = seed.correlationReference,
            contextVersion = ExecutionContext.CURRENT_CONTEXT_VERSION,
            authorityReference = seed.authorityReference,
        )
        return execute(ExecutionCommand(context, skillId, query))
    }

    /** Compatibility path for an already materialized context; Product callers should use [submit]. */
    fun execute(command: ExecutionCommand): ExecutionResult {
        if (command.context.contextVersion != ExecutionContext.CURRENT_CONTEXT_VERSION) {
            return ExecutionResult.TechnicalFailure(
                command.context.executionReference,
                TechnicalFailureCategory.CORRELATION_FAILURE,
            )
        }
        val handler = registry.find(command.skillId)
            ?: return ExecutionResult.TechnicalFailure(
                command.context.executionReference,
                TechnicalFailureCategory.UNSUPPORTED_SKILL,
            )
        if (
            handler is ProductBoundSkillHandler &&
            handler.requiredProductReference() != command.context.scope.productReference
        ) {
            return correlationFailure(command)
        }
        val result = handler.execute(command)
        if (result.executionReference != command.context.executionReference) {
            return correlationFailure(command)
        }
        if (result is ExecutionResult.Success) {
            if (result.evidence.context != command.context) {
                return correlationFailure(command)
            }
            if (result.interpretation.isBlank()) {
                return ExecutionResult.TechnicalFailure(
                    command.context.executionReference,
                    TechnicalFailureCategory.MODEL_FAILURE,
                )
            }
        }
        return result
    }

    private fun correlationFailure(command: ExecutionCommand) =
        ExecutionResult.TechnicalFailure(
            command.context.executionReference,
            TechnicalFailureCategory.CORRELATION_FAILURE,
        )
}
