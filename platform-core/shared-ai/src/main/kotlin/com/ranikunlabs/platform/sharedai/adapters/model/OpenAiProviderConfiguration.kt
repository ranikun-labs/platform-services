package com.ranikunlabs.platform.sharedai.adapters.model

import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("platform.shared-ai.openai")
class OpenAiProviderProperties {
    var enabled: Boolean = false
    var apiKey: String = ""
    var model: String = ""
    var maxOutputTokens: Int = 128
    var timeout: Duration = Duration.ofSeconds(30)

    fun validateForProviderMode() {
        require(apiKey.isNotBlank()) { "OpenAI mode requires platform.shared-ai.openai.api-key" }
        require(model.isNotBlank()) { "OpenAI mode requires platform.shared-ai.openai.model" }
        require(maxOutputTokens > 0) { "OpenAI mode requires a positive max-output-tokens value" }
        require(!timeout.isZero && !timeout.isNegative) { "OpenAI timeout must be positive" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProviderProperties::class)
@ConditionalOnProperty(name = ["platform.shared-ai.openai.enabled"], havingValue = "true")
class OpenAiProviderConfiguration {
    @Bean
    fun openAiChatModel(
        properties: OpenAiProviderProperties,
        observationRegistries: ObjectProvider<ObservationRegistry>,
    ): OpenAiChatModel {
        properties.validateForProviderMode()
        val options = OpenAiChatOptions.builder()
            .apiKey(properties.apiKey)
            .model(properties.model)
            .maxCompletionTokens(properties.maxOutputTokens)
            .timeout(properties.timeout)
            .maxRetries(0)
            .streamUsage(true)
            .build()
        return OpenAiChatModel.builder()
            .options(options)
            .observationRegistry(observationRegistries.getIfUnique { ObservationRegistry.NOOP })
            .build()
    }

    @Bean
    fun openAiModelGateway(
        chatModel: OpenAiChatModel,
        properties: OpenAiProviderProperties,
    ): SpringAiModelGateway = SpringAiModelGateway(
        ChatClient.builder(chatModel).build(),
        "openai",
        properties.model,
    )
}
