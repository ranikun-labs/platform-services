package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SkillRegistryTest {
    @Test
    fun `finds an explicitly registered skill`() {
        val handler = StubHandler(SkillId.REVIEW_KNOWLEDGE)

        assertThat(SkillRegistry(listOf(handler)).find(SkillId.REVIEW_KNOWLEDGE)).isSameAs(handler)
    }

    @Test
    fun `rejects duplicate skill ids deterministically`() {
        assertThatThrownBy {
            SkillRegistry(listOf(StubHandler(SkillId.REVIEW_KNOWLEDGE), StubHandler(SkillId.REVIEW_KNOWLEDGE)))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate SkillId")
    }

    @Test
    fun `returns typed failure for an unsupported skill`() {
        val service = SharedAiExecutionService(SkillRegistry(emptyList()))

        val result = service.execute(command(SkillId("not-supported")))

        assertThat(result).isEqualTo(
            ExecutionResult.TechnicalFailure("execution-a", TechnicalFailureCategory.UNSUPPORTED_SKILL),
        )
    }

    private fun command(skillId: SkillId) = ExecutionCommand(
        ExecutionContext(
            ExecutionScope("product-a", "tenant-a", emptySet()),
            "principal-a",
            "request-a",
            "execution-a",
            null,
        ),
        skillId,
        "review",
    )

    private class StubHandler(private val id: SkillId) : SkillHandler {
        override fun execute(command: ExecutionCommand): ExecutionResult =
            ExecutionResult.TechnicalFailure(command.context.executionReference, TechnicalFailureCategory.MODEL_FAILURE)

        override fun skillId(): SkillId = id
    }
}
