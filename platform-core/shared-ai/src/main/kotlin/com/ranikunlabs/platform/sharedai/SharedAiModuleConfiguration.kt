package com.ranikunlabs.platform.sharedai

import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryProductToolAdapter
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryRetrieverAdapter
import com.ranikunlabs.platform.sharedai.adapters.model.OpenAiProviderConfiguration
import com.ranikunlabs.platform.sharedai.adapters.observability.MicrometerExecutionTelemetry
import com.ranikunlabs.platform.sharedai.application.PortResultCorrelationValidator
import com.ranikunlabs.platform.sharedai.application.ReviewKnowledgeExecutionSupport
import com.ranikunlabs.platform.sharedai.application.ReviewKnowledgeSkillHandler
import com.ranikunlabs.platform.sharedai.application.ReviewKnowledgeStreamingSkillHandler
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SharedAiStreamingExecutionService
import com.ranikunlabs.platform.sharedai.application.SkillHandler
import com.ranikunlabs.platform.sharedai.application.SkillRegistry
import com.ranikunlabs.platform.sharedai.application.StreamingSkillHandler
import com.ranikunlabs.platform.sharedai.application.StreamingSkillRegistry
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import com.ranikunlabs.platform.sharedai.ports.StreamingModelGateway
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@Import(
    SharedAiRuntimeConfiguration::class,
    InvalidSharedAiGatewayModeConfiguration::class,
    OpenAiProviderConfiguration::class,
)
class SharedAiModuleConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "(\${platform.shared-ai.foundation.enabled:false} && !\${platform.shared-ai.openai.enabled:false}) || " +
        "(!\${platform.shared-ai.foundation.enabled:false} && \${platform.shared-ai.openai.enabled:false})",
)
class SharedAiRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["platform.shared-ai.foundation.enabled"], havingValue = "true")
    fun foundationModelGateway(): DeterministicModelGateway = DeterministicModelGateway()

    @Bean
    fun foundationProductTool(): ProductToolPort = InMemoryProductToolAdapter()

    @Bean
    fun foundationRetriever(): RetrieverPort = InMemoryRetrieverAdapter()

    @Bean
    fun executionTelemetry(meterRegistry: MeterRegistry): ExecutionTelemetry =
        MicrometerExecutionTelemetry(meterRegistry)

    @Bean
    fun portResultCorrelationValidator(): PortResultCorrelationValidator = PortResultCorrelationValidator()

    @Bean("sharedAiEvidenceClock")
    fun sharedAiEvidenceClock(): Clock = Clock.systemUTC()

    @Bean
    fun reviewKnowledgeExecutionSupport(
        productTool: ProductToolPort,
        retriever: RetrieverPort,
        telemetry: ExecutionTelemetry,
        correlationValidator: PortResultCorrelationValidator,
        @Qualifier("sharedAiEvidenceClock")
        clock: Clock,
    ): ReviewKnowledgeExecutionSupport = ReviewKnowledgeExecutionSupport(
        productTool,
        retriever,
        telemetry,
        correlationValidator,
        clock,
    )

    @Bean
    fun reviewKnowledgeSkillHandler(
        modelGateway: ModelGateway,
        telemetry: ExecutionTelemetry,
        support: ReviewKnowledgeExecutionSupport,
    ): ReviewKnowledgeSkillHandler = ReviewKnowledgeSkillHandler(modelGateway, telemetry, support)

    @Bean
    fun reviewKnowledgeStreamingSkillHandler(
        streamingModelGateway: StreamingModelGateway,
        telemetry: ExecutionTelemetry,
        support: ReviewKnowledgeExecutionSupport,
    ): ReviewKnowledgeStreamingSkillHandler =
        ReviewKnowledgeStreamingSkillHandler(streamingModelGateway, telemetry, support)

    @Bean
    fun skillRegistry(handlers: List<SkillHandler>): SkillRegistry = SkillRegistry(handlers)

    @Bean
    fun streamingSkillRegistry(handlers: List<StreamingSkillHandler>): StreamingSkillRegistry =
        StreamingSkillRegistry(handlers)

    @Bean
    fun sharedAiExecutionService(registry: SkillRegistry): SharedAiExecutionService =
        SharedAiExecutionService(registry)

    @Bean
    fun sharedAiStreamingExecutionService(registry: StreamingSkillRegistry): SharedAiStreamingExecutionService =
        SharedAiStreamingExecutionService(registry)
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "\${platform.shared-ai.foundation.enabled:false} && \${platform.shared-ai.openai.enabled:false}",
)
class InvalidSharedAiGatewayModeConfiguration {
    @Bean
    fun invalidSharedAiGatewayMode(): Any =
        throw IllegalStateException("Exactly one Shared AI model gateway mode may be enabled")
}
