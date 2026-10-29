package com.ranikunlabs.platform.sharedai

import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryProductToolAdapter
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryRetrieverAdapter
import com.ranikunlabs.platform.sharedai.adapters.model.OpenAiProviderConfiguration
import com.ranikunlabs.platform.sharedai.adapters.model.OpenAiProviderProperties
import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway
import com.ranikunlabs.platform.sharedai.adapters.observability.MicrometerExecutionTelemetry
import com.ranikunlabs.platform.sharedai.application.ExecutionReferenceGenerator
import com.ranikunlabs.platform.sharedai.application.FinanceGeneralAnalysisSkillHandler
import com.ranikunlabs.platform.sharedai.application.PortResultCorrelationValidator
import com.ranikunlabs.platform.sharedai.application.ReviewKnowledgeSkillHandler
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SkillHandler
import com.ranikunlabs.platform.sharedai.application.SkillRegistry
import com.ranikunlabs.platform.sharedai.application.UuidExecutionReferenceGenerator
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@Import(SharedAiRuntimeConfiguration::class, OpenAiProviderConfiguration::class)
class SharedAiModuleConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "\${platform.shared-ai.foundation.enabled:false} || \${platform.shared-ai.spring-ai.enabled:false}",
)
class SharedAiRuntimeConfiguration {
    @Bean
    @Profile("!prod & !production")
    @ConditionalOnProperty(prefix = "platform.shared-ai.foundation", name = ["enabled"], havingValue = "true")
    fun foundationProductTool(): ProductToolPort = InMemoryProductToolAdapter()

    @Bean
    @Profile("!prod & !production")
    @ConditionalOnProperty(prefix = "platform.shared-ai.foundation", name = ["enabled"], havingValue = "true")
    fun foundationRetriever(): RetrieverPort = InMemoryRetrieverAdapter()

    @Bean
    fun modelGateway(
        environment: Environment,
        chatClients: ObjectProvider<ChatClient>,
        providerProperties: ObjectProvider<OpenAiProviderProperties>,
        meterRegistry: MeterRegistry,
    ): ModelGateway {
        val foundationEnabled = environment.getProperty(FOUNDATION_ENABLED, Boolean::class.java, false)
        val springAiEnabled = environment.getProperty(SPRING_AI_ENABLED, Boolean::class.java, false)
        require(foundationEnabled.xor(springAiEnabled)) {
            "Exactly one Shared AI model gateway mode must be enabled"
        }
        if (foundationEnabled) {
            require(
                !environment.acceptsProfiles(Profiles.of("prod")) &&
                    !environment.acceptsProfiles(Profiles.of("production")),
            ) {
                "Shared AI Foundation mode is disabled for prod and production profiles"
            }
            return DeterministicModelGateway()
        }
        val candidates = chatClients.orderedStream().toList()
        if (candidates.size != 1) {
            throw IllegalStateException("Spring AI model gateway mode requires exactly one ChatClient")
        }
        val properties = providerProperties.getIfAvailable()
            ?: throw IllegalStateException("Spring AI model gateway mode requires OpenAI provider configuration")
        return SpringAiModelGateway(candidates.single(), "openai", properties.model, meterRegistry)
    }

    @Bean
    fun executionTelemetry(meterRegistry: MeterRegistry): ExecutionTelemetry =
        MicrometerExecutionTelemetry(meterRegistry)

    @Bean
    fun portResultCorrelationValidator(): PortResultCorrelationValidator = PortResultCorrelationValidator()

    @Bean
    fun executionReferenceGenerator(): ExecutionReferenceGenerator = UuidExecutionReferenceGenerator()

    @Bean("sharedAiEvidenceClock")
    fun sharedAiEvidenceClock(): Clock = Clock.systemUTC()

    @Bean
    fun reviewKnowledgeSkillHandler(
        productTool: ProductToolPort,
        retriever: RetrieverPort,
        modelGateway: ModelGateway,
        telemetry: ExecutionTelemetry,
        correlationValidator: PortResultCorrelationValidator,
        @Qualifier("sharedAiEvidenceClock")
        sharedAiEvidenceClock: Clock,
    ): ReviewKnowledgeSkillHandler =
        ReviewKnowledgeSkillHandler(
            productTool,
            retriever,
            modelGateway,
            telemetry,
            correlationValidator,
            sharedAiEvidenceClock,
        )

    @Bean
    fun financeGeneralAnalysisSkillHandler(
        productTool: ProductToolPort,
        modelGateway: ModelGateway,
        telemetry: ExecutionTelemetry,
        correlationValidator: PortResultCorrelationValidator,
        @Qualifier("sharedAiEvidenceClock")
        sharedAiEvidenceClock: Clock,
    ): FinanceGeneralAnalysisSkillHandler =
        FinanceGeneralAnalysisSkillHandler(
            productTool,
            modelGateway,
            telemetry,
            correlationValidator,
            sharedAiEvidenceClock,
        )

    @Bean
    fun skillRegistry(handlers: List<SkillHandler>): SkillRegistry = SkillRegistry(handlers)

    @Bean
    fun sharedAiExecutionService(
        registry: SkillRegistry,
        executionReferenceGenerator: ExecutionReferenceGenerator,
    ): SharedAiExecutionService = SharedAiExecutionService(registry, executionReferenceGenerator)

    private companion object {
        const val FOUNDATION_ENABLED = "platform.shared-ai.foundation.enabled"
        const val SPRING_AI_ENABLED = "platform.shared-ai.spring-ai.enabled"
    }
}
