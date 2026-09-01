package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import com.ranikunlabs.platform.sharedai.ports.ModelGatewayException
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelStreamEvent
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
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
import reactor.core.publisher.Flux
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpringAiModelGatewayContractTest {
    private val scope = ExecutionScope("product-a", "tenant-a", setOf("resource-a"))

    @Test
    fun `maps neutral request and response including usage`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt::class.java)))
            .thenReturn(response("interpreted", 11, 7, 18))
        val gateway = gateway(chatModel)

        val result = gateway.interpret(request())

        assertThat(result.executionReference).isEqualTo("execution-a")
        assertThat(result.scope).isEqualTo(scope)
        assertThat(result.sourceReference).isEqualTo("bedrock-converse:model:configured-model")
        assertThat(result.producerReference).isEqualTo("bedrock-converse:configured-model")
        assertThat(result.interpretation).isEqualTo("interpreted")
        assertThat(result.usage?.inputTokens).isEqualTo(11)
        assertThat(result.usage?.outputTokens).isEqualTo(7)
        assertThat(result.usage?.totalTokens).isEqualTo(18)

        val prompt = ArgumentCaptor.forClass(Prompt::class.java)
        verify(chatModel).call(prompt.capture())
        val rendered = prompt.value.instructions.joinToString("\n") { it.text.orEmpty() }
        assertThat(rendered)
            .contains("never authority", "Question: review", "Structured fact count: 3", "approved guidance")
    }

    @Test
    fun `maps provider failure without leaking SDK exception`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt::class.java)))
            .thenThrow(ThrottlingException.builder().message("provider detail").statusCode(429).build())

        assertThatThrownBy { gateway(chatModel).interpret(request()) }
            .isInstanceOfSatisfying(ModelGatewayException::class.java) { failure ->
                assertThat(failure.category).isEqualTo(ModelFailureCategory.RATE_LIMITED)
                assertThat(failure.message).doesNotContain("provider detail")
            }
    }

    @Test
    fun `streams started multiple deltas and completed usage in order`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.stream(org.mockito.ArgumentMatchers.any(Prompt::class.java)))
            .thenReturn(
                Flux.just(
                    response("first ", 0, 0, 0),
                    response("second", 11, 7, 18),
                ),
            )
        val events = mutableListOf<ModelStreamEvent>()
        val terminal = CountDownLatch(1)

        gateway(chatModel).stream(request()) {
            events += it
            if (it is ModelStreamEvent.Completed || it is ModelStreamEvent.Failed) terminal.countDown()
        }
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(events.map { it.javaClass.simpleName })
            .containsExactly("Started", "ContentDelta", "ContentDelta", "Completed")
        assertThat(events.filterIsInstance<ModelStreamEvent.ContentDelta>().map { it.text })
            .containsExactly("first ", "second")
        val completion = events.last() as ModelStreamEvent.Completed
        assertThat(completion.completion.usage?.totalTokens).isEqualTo(18)
    }

    @Test
    fun `maps streaming provider failure to one terminal failure`() {
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.stream(org.mockito.ArgumentMatchers.any(Prompt::class.java)))
            .thenReturn(Flux.error(ThrottlingException.builder().statusCode(429).build()))
        val events = mutableListOf<ModelStreamEvent>()
        val terminal = CountDownLatch(1)

        gateway(chatModel).stream(request()) {
            events += it
            if (it is ModelStreamEvent.Completed || it is ModelStreamEvent.Failed) terminal.countDown()
        }
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(events).hasSize(2)
        assertThat(events.first()).isInstanceOf(ModelStreamEvent.Started::class.java)
        assertThat((events.last() as ModelStreamEvent.Failed).category)
            .isEqualTo(ModelFailureCategory.RATE_LIMITED)
    }

    @Test
    fun `cancellation disposes provider stream and prevents terminal delivery`() {
        val cancelled = AtomicBoolean()
        val chatModel = mock(ChatModel::class.java)
        `when`(chatModel.stream(org.mockito.ArgumentMatchers.any(Prompt::class.java)))
            .thenReturn(Flux.never<ChatResponse>().doOnCancel { cancelled.set(true) })
        val events = mutableListOf<ModelStreamEvent>()

        val cancellation = gateway(chatModel).stream(request()) { events += it }
        cancellation.cancel()
        cancellation.cancel()

        assertThat(cancelled).isTrue()
        assertThat(events).containsExactly(ModelStreamEvent.Started("execution-a"))
    }

    private fun gateway(chatModel: ChatModel): SpringAiModelGateway {
        `when`(chatModel.options).thenReturn(ChatOptions.builder().build())
        return SpringAiModelGateway(ChatClient.builder(chatModel).build(), "configured-model")
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
