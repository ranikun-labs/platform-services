package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ModelUsage
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import java.util.concurrent.TimeoutException

class SpringAiModelGatewayContractTest {
    private val scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a"))

    @Test
    fun `maps neutral request and response including usage and correlation`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.call(any(Prompt::class.java)))
            .thenReturn(response("interpreted", 11, 7, 18))

        val result = gateway(chatModel).interpret(request())

        assertThat(result).isEqualTo(
            ModelResult(
                "execution-a",
                scope,
                "openai:model:configured-model",
                "openai:configured-model",
                "interpreted",
                ModelUsage(11, 7, 18),
            ),
        )

        val prompt = ArgumentCaptor.forClass(Prompt::class.java)
        verify(chatModel).call(prompt.capture())
        val rendered = prompt.value.instructions.joinToString("\n") { it.text.orEmpty() }
        assertThat(rendered)
            .contains(
                "never authority",
                "BEGIN_UNTRUSTED_INPUT",
                "END_UNTRUSTED_INPUT",
                "Question: review",
                "Structured fact count: 3",
                "approved guidance",
            )
    }

    @Test
    fun `normalizes provider failure without leaking SDK exception`() {
        val chatModel = mock(ChatModel::class.java)
        val providerFailure = mock(com.openai.errors.RateLimitException::class.java)
        `when`(providerFailure.statusCode()).thenReturn(429)
        `when`(chatModel.call(any(Prompt::class.java))).thenThrow(providerFailure)

        assertThatThrownBy { gateway(chatModel).interpret(request()) }
            .isInstanceOfSatisfying(OpenAiProviderFailure::class.java) { failure ->
                assertThat(failure.reason).isEqualTo(OpenAiFailureReason.RATE_LIMITED)
                assertThat(failure.message).doesNotContain("provider detail")
            }
            .isNotInstanceOf(com.openai.errors.OpenAIServiceException::class.java)
    }

    @Test
    fun `maps timeout to an adapter-local failure reason`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.call(any(Prompt::class.java)))
            .thenThrow(RuntimeException(TimeoutException("timed out")))

        assertThatThrownBy { gateway(chatModel).interpret(request()) }
            .isInstanceOfSatisfying(OpenAiProviderFailure::class.java) { failure ->
                assertThat(failure.reason).isEqualTo(OpenAiFailureReason.TIMEOUT)
            }
    }

    @Test
    fun `rejects a null provider response`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.call(any(Prompt::class.java))).thenReturn(null)

        assertThatThrownBy { gateway(chatModel).interpret(request()) }
            .isInstanceOfSatisfying(OpenAiProviderFailure::class.java) { failure ->
                assertThat(failure.reason).isEqualTo(OpenAiFailureReason.INVALID_RESPONSE)
            }
    }

    @Test
    fun `rejects an empty provider interpretation`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.call(any(Prompt::class.java)))
            .thenReturn(response("  ", 1, 1, 2))

        assertThatThrownBy { gateway(chatModel).interpret(request()) }
            .isInstanceOfSatisfying(OpenAiProviderFailure::class.java) { failure ->
                assertThat(failure.reason).isEqualTo(OpenAiFailureReason.EMPTY_TEXT)
            }
    }

    private fun gateway(chatModel: ChatModel): SpringAiModelGateway =
        SpringAiModelGateway(ChatClient.builder(chatModel).build(), "openai", "configured-model").also {
            `when`(chatModel.options).thenReturn(ChatOptions.builder().build())
        }

    private fun request(): ModelRequest = ModelRequest(
        "execution-a",
        scope,
        "review",
        ProductFact("execution-a", scope, "tool:fact", "tool:producer", "count", "3"),
        listOf(
            RetrievedItem(
                "execution-a",
                scope,
                "retriever:doc",
                "retriever:producer",
                "approved guidance",
            ),
        ),
    )

    private fun response(
        text: String,
        inputTokens: Int,
        outputTokens: Int,
        totalTokens: Int,
    ): ChatResponse = ChatResponse(
        listOf(Generation(AssistantMessage(text))),
        ChatResponseMetadata.builder()
            .usage(DefaultUsage(inputTokens, outputTokens, totalTokens))
            .build(),
    )
}
