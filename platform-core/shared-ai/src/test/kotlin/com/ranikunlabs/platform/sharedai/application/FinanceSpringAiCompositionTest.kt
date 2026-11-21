package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway
import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import java.time.Clock

/** Provider-independent composition; this test does not exercise HTTP or authentication. */
class FinanceSpringAiCompositionTest {
    @Test
    fun `finance submission passes selected product fact through Spring AI gateway`() {
        val model = mock(ChatModel::class.java)
        `when`(model.options).thenReturn(ChatOptions.builder().build())
        val interpretation = "synthetic interpretation"
        `when`(model.call(any(Prompt::class.java)))
            .thenReturn(ChatResponse(listOf(Generation(AssistantMessage(interpretation)))))
        val toolContexts = mutableListOf<ExecutionContext>()
        val tool = ProductToolPort { context ->
            toolContexts += context
            ProductFact(context.executionReference, context.scope, "fact:1", "test:tool", "count", "3")
        }
        val telemetry = object : ExecutionTelemetry {
            override fun record(context: ExecutionContext, skillId: String, measurement: ExecutionMeasurement) = Unit
        }
        val gateway = SpringAiModelGateway(ChatClient.builder(model).build(), "test", "stub")
        val handler = FinanceGeneralAnalysisSkillHandler(tool, gateway, telemetry, PortResultCorrelationValidator(), Clock.systemUTC())
        val service = SharedAiExecutionService(SkillRegistry(listOf(handler)), ExecutionReferenceGenerator { "execution:1" })
        val scope = ExecutionScope("finance", "tenant:1", setOf("question:1"))
        val seed = ExecutionContextSeed(scope, "principal:1", "request:1", "correlation:1", "authority:1")

        val result = service.submit(seed, SkillId.FINANCE_GENERAL_ANALYSIS, "synthetic question") as ExecutionResult.Success

        assertThat(toolContexts).hasSize(1)
        assertThat(toolContexts.single().executionReference).isEqualTo("execution:1")
        assertThat(result.interpretation).isEqualTo(interpretation)
        assertThat(result.evidence.context.scope).isEqualTo(scope)
        assertThat(result.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT, EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(result.evidence.items.last().sourceReferences)
            .containsExactly(result.evidence.items.first().evidenceId.value)
        val prompt = ArgumentCaptor.forClass(Prompt::class.java)
        verify(model).call(prompt.capture())
        assertThat(prompt.value.instructions.joinToString("\n") { it.text.orEmpty() })
            .contains("synthetic question", "Structured fact count: 3", "BEGIN_UNTRUSTED_INPUT", "END_UNTRUSTED_INPUT")
    }
}
