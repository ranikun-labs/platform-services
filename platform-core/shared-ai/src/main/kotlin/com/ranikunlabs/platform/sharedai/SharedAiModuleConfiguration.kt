package com.ranikunlabs.platform.sharedai

import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryProductToolAdapter
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryRetrieverAdapter
import com.ranikunlabs.platform.sharedai.adapters.observability.MicrometerExecutionTelemetry
import com.ranikunlabs.platform.sharedai.application.ReviewKnowledgeSkillHandler
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SkillHandler
import com.ranikunlabs.platform.sharedai.application.SkillRegistry
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration(proxyBeanMethods = false)
@Import(SharedAiFoundationConfiguration::class)
class SharedAiModuleConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "platform.shared-ai.foundation", name = ["enabled"], havingValue = "true")
class SharedAiFoundationConfiguration {
    @Bean
    fun foundationProductTool(): ProductToolPort = InMemoryProductToolAdapter()

    @Bean
    fun foundationRetriever(): RetrieverPort = InMemoryRetrieverAdapter()

    @Bean
    fun foundationModelGateway(): ModelGateway = DeterministicModelGateway()

    @Bean
    fun executionTelemetry(meterRegistry: MeterRegistry): ExecutionTelemetry =
        MicrometerExecutionTelemetry(meterRegistry)

    @Bean
    fun reviewKnowledgeSkillHandler(
        productTool: ProductToolPort,
        retriever: RetrieverPort,
        modelGateway: ModelGateway,
        telemetry: ExecutionTelemetry,
    ): ReviewKnowledgeSkillHandler =
        ReviewKnowledgeSkillHandler(productTool, retriever, modelGateway, telemetry)

    @Bean
    fun skillRegistry(handlers: List<SkillHandler>): SkillRegistry = SkillRegistry(handlers)

    @Bean
    fun sharedAiExecutionService(registry: SkillRegistry): SharedAiExecutionService =
        SharedAiExecutionService(registry)
}
