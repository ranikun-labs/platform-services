package com.ranikunlabs.platform.sharedai.adapters.model

import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("platform.shared-ai.openai")
class OpenAiProviderProperties {
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

    override fun toString(): String =
        "OpenAiProviderProperties{" +
            "apiKeyConfigured=${apiKey.isNotBlank()}, " +
            "model='$model', " +
            "maxOutputTokens=$maxOutputTokens, " +
            "timeout=$timeout" +
            "}"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProviderProperties::class)
@ConditionalOnExpression(
    "\${platform.shared-ai.spring-ai.enabled:false} && !\${platform.shared-ai.foundation.enabled:false}",
)
class OpenAiProviderConfiguration {
    @Bean
    fun openAiChatModel(
        properties: OpenAiProviderProperties,
        observationRegistryProvider: ObjectProvider<ObservationRegistry>,
    ): OpenAiChatModel {
        properties.validateForProviderMode()
        val options = OpenAiChatOptions.builder()
            .apiKey(properties.apiKey)
            .model(properties.model)
            .maxCompletionTokens(properties.maxOutputTokens)
            .timeout(properties.timeout)
            .maxRetries(0)
            .build()
        return OpenAiChatModel.builder()
            .options(options)
            .observationRegistry(observationRegistryProvider.getIfUnique { ObservationRegistry.NOOP })
            .build()
    }

    @Bean
    fun openAiChatClient(chatModel: OpenAiChatModel): ChatClient = ChatClient.builder(chatModel).build()
}
