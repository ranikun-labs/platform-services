package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.EvidenceCategory
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelCompletion
import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import com.ranikunlabs.platform.sharedai.ports.ModelStreamCancellation
import com.ranikunlabs.platform.sharedai.ports.ModelStreamEvent
import com.ranikunlabs.platform.sharedai.ports.ModelUsage
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import com.ranikunlabs.platform.sharedai.ports.StreamingModelGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

class ReviewKnowledgeStreamingSkillHandlerTest {
    private val context = ExecutionContext(
        ExecutionScope("product-a", "tenant-a", setOf("resource-a")),
        "principal-a",
        "request-a",
        "execution-a",
        "correlation-a",
    )
    private val clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `streams deltas then creates one final reference-only evidence envelope`() {
        val calls = mutableListOf<String>()
        val events = mutableListOf<ExecutionStreamEvent>()
        val gateway = StreamingModelGateway { request, observer ->
            calls += "model"
            assertThat(request.productFact.value).isEqualTo("3")
            assertThat(request.retrievedItems).hasSize(1)
            observer.onEvent(ModelStreamEvent.Started(request.executionReference))
            observer.onEvent(ModelStreamEvent.ContentDelta(request.executionReference, "first "))
            observer.onEvent(ModelStreamEvent.ContentDelta(request.executionReference, "second"))
            observer.onEvent(ModelStreamEvent.Completed(completion(ModelUsage(11, 7, 18))))
            ModelStreamCancellation {}
        }
        val handler = handler(
            ProductToolPort {
                calls += "tool"
                fact()
            },
            RetrieverPort { _, _ ->
                calls += "retriever"
                listOf(retrieved())
            },
            gateway,
        )

        handler.stream(command()) { events += it }

        assertThat(calls).containsExactly("tool", "retriever", "model")
        assertThat(events.map { it.javaClass.simpleName })
            .containsExactly("Started", "ContentDelta", "ContentDelta", "Completed")
        val completed = events.last() as ExecutionStreamEvent.Completed
        assertThat(completed.usage?.totalTokens).isEqualTo(18)
        assertThat(completed.evidence.items.map { it.kind }).containsExactly(
            EvidenceCategory.STRUCTURED_PRODUCT_FACT,
            EvidenceCategory.RETRIEVED_CONTENT,
            EvidenceCategory.MODEL_INTERPRETATION,
        )
        assertThat(completed.evidence.items).hasSize(3)
    }

    @Test
    fun `invalid retrieval correlation fails before streaming model`() {
        val modelInvoked = AtomicBoolean()
        val events = mutableListOf<ExecutionStreamEvent>()
        val invalid = retrieved(scope = ExecutionScope("product-a", "tenant-b", setOf("resource-a")))
        val handler = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(invalid) },
            StreamingModelGateway { _, _ ->
                modelInvoked.set(true)
                ModelStreamCancellation {}
            },
        )

        handler.stream(command()) { events += it }

        assertThat(modelInvoked).isFalse()
        assertThat(events).containsExactly(
            ExecutionStreamEvent.Failed("execution-a", TechnicalFailureCategory.CORRELATION_FAILURE),
        )
    }

    @Test
    fun `provider failure remains neutral and terminal`() {
        val events = mutableListOf<ExecutionStreamEvent>()
        val handler = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(retrieved()) },
            StreamingModelGateway { request, observer ->
                observer.onEvent(ModelStreamEvent.Started(request.executionReference))
                observer.onEvent(
                    ModelStreamEvent.Failed(request.executionReference, ModelFailureCategory.RATE_LIMITED),
                )
                observer.onEvent(ModelStreamEvent.ContentDelta(request.executionReference, "must-not-escape"))
                ModelStreamCancellation {}
            },
        )

        handler.stream(command()) { events += it }

        assertThat(events).containsExactly(
            ExecutionStreamEvent.Started("execution-a"),
            ExecutionStreamEvent.Failed("execution-a", TechnicalFailureCategory.RATE_LIMITED),
        )
    }

    @Test
    fun `completion is terminal and later provider events are ignored`() {
        val events = mutableListOf<ExecutionStreamEvent>()
        val handler = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(retrieved()) },
            StreamingModelGateway { request, observer ->
                observer.onEvent(ModelStreamEvent.Started(request.executionReference))
                observer.onEvent(ModelStreamEvent.Completed(completion(null)))
                observer.onEvent(ModelStreamEvent.ContentDelta(request.executionReference, "late"))
                observer.onEvent(
                    ModelStreamEvent.Failed(request.executionReference, ModelFailureCategory.TIMEOUT),
                )
                ModelStreamCancellation {}
            },
        )

        handler.stream(command()) { events += it }

        assertThat(events).hasSize(2)
        assertThat(events.first()).isInstanceOf(ExecutionStreamEvent.Started::class.java)
        assertThat(events.last()).isInstanceOf(ExecutionStreamEvent.Completed::class.java)
    }

    @Test
    fun `caller cancellation propagates exactly once to provider`() {
        var cancellations = 0
        val events = mutableListOf<ExecutionStreamEvent>()
        val telemetry = RecordingTelemetry()
        val handler = handler(
            ProductToolPort { fact() },
            RetrieverPort { _, _ -> listOf(retrieved()) },
            StreamingModelGateway { request, observer ->
                observer.onEvent(ModelStreamEvent.Started(request.executionReference))
                ModelStreamCancellation { cancellations++ }
            },
            telemetry,
        )

        val cancellation = handler.stream(command()) { events += it }
        cancellation.cancel()
        cancellation.cancel()

        assertThat(cancellations).isEqualTo(1)
        assertThat(events).containsExactly(ExecutionStreamEvent.Started("execution-a"))
        assertThat(telemetry.measurements.last().failureCategory)
            .isEqualTo(TechnicalFailureCategory.CANCELLED.name)
    }

    private fun handler(
        productTool: ProductToolPort,
        retriever: RetrieverPort,
        gateway: StreamingModelGateway,
        telemetry: ExecutionTelemetry = RecordingTelemetry(),
    ): ReviewKnowledgeStreamingSkillHandler = ReviewKnowledgeStreamingSkillHandler(
        gateway,
        telemetry,
        ReviewKnowledgeExecutionSupport(
            productTool,
            retriever,
            telemetry,
            PortResultCorrelationValidator(),
            clock,
        ),
    )

    private fun command(): ExecutionCommand =
        ExecutionCommand(context, SkillId.REVIEW_KNOWLEDGE, "review")

    private fun fact(): ProductFact =
        ProductFact("execution-a", context.scope, "tool:fact", "tool:producer", "count", "3")

    private fun retrieved(scope: ExecutionScope = context.scope): RetrievedItem = RetrievedItem(
        "execution-a",
        scope,
        "retriever:doc",
        "retriever:producer",
        "approved guidance",
    )

    private fun completion(usage: ModelUsage?): ModelCompletion = ModelCompletion(
        "execution-a",
        context.scope,
        "model:source",
        "model:producer",
        usage,
    )

    private class RecordingTelemetry : ExecutionTelemetry {
        val measurements = mutableListOf<ExecutionMeasurement>()

        override fun record(context: ExecutionContext, skillId: String, measurement: ExecutionMeasurement) {
            measurements += measurement
        }
    }
}
