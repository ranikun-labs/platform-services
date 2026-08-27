package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.ExecutionContext

data class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank" }
    }

    companion object {
        @JvmField
        val REVIEW_KNOWLEDGE = SkillId("review-knowledge")
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

enum class TechnicalFailureCategory {
    UNSUPPORTED_SKILL,
    PRODUCT_TOOL_FAILURE,
    RETRIEVER_FAILURE,
    MODEL_FAILURE,
}

sealed interface ExecutionResult {
    val executionReference: String

    data class Success(
        override val executionReference: String,
        val interpretation: String,
        val evidence: Evidence,
        val warnings: List<String>,
    ) : ExecutionResult

    data class TechnicalFailure(
        override val executionReference: String,
        val category: TechnicalFailureCategory,
    ) : ExecutionResult
}

interface SkillHandler {
    fun execute(command: ExecutionCommand): ExecutionResult

    fun skillId(): SkillId
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

class SharedAiExecutionService(private val registry: SkillRegistry) {
    fun execute(command: ExecutionCommand): ExecutionResult {
        val handler = registry.find(command.skillId)
            ?: return ExecutionResult.TechnicalFailure(
                command.context.executionReference,
                TechnicalFailureCategory.UNSUPPORTED_SKILL,
            )
        return handler.execute(command)
    }
}
