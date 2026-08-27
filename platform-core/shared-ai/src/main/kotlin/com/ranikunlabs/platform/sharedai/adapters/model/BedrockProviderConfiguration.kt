package com.ranikunlabs.platform.sharedai.adapters.model

import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import java.time.Duration

@ConfigurationProperties("platform.shared-ai.bedrock")
class BedrockProviderProperties {
    var enabled: Boolean = false
    var region: String = ""
    var modelId: String = ""
    var maxTokens: Int = 128
    var timeout: Duration = Duration.ofSeconds(30)
    var connectionTimeout: Duration = Duration.ofSeconds(5)
    var readTimeout: Duration = Duration.ofSeconds(30)
    var maxConcurrentRequests: Int = 8

    fun validateForProviderMode() {
        require(region.isNotBlank()) { "Bedrock mode requires platform.shared-ai.bedrock.region" }
        require(modelId.isNotBlank()) { "Bedrock mode requires platform.shared-ai.bedrock.model-id" }
        require(maxTokens > 0) { "Bedrock mode requires a positive max-tokens value" }
        require(!timeout.isZero && !timeout.isNegative) { "Bedrock timeout must be positive" }
        require(!connectionTimeout.isZero && !connectionTimeout.isNegative) {
            "Bedrock connection-timeout must be positive"
        }
        require(!readTimeout.isZero && !readTimeout.isNegative) { "Bedrock read-timeout must be positive" }
        require(maxConcurrentRequests in 1..64) {
            "Bedrock max-concurrent-requests must be between 1 and 64"
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BedrockProviderProperties::class)
@ConditionalOnProperty(name = ["platform.shared-ai.bedrock.enabled"], havingValue = "true")
class BedrockProviderConfiguration {
    @Bean("sharedAiBedrockCredentials")
    fun bedrockCredentials(properties: BedrockProviderProperties): DefaultCredentialsProvider {
        properties.validateForProviderMode()
        return DefaultCredentialsProvider.builder().build()
    }

    @Bean(name = ["sharedAiBedrockRuntimeClient"], destroyMethod = "close")
    fun bedrockRuntimeClient(
        properties: BedrockProviderProperties,
        @Qualifier("sharedAiBedrockCredentials")
        credentials: DefaultCredentialsProvider,
    ): BedrockRuntimeClient {
        val httpClient = ApacheHttpClient.builder()
            .connectionTimeout(properties.connectionTimeout)
            .socketTimeout(properties.readTimeout)
            .maxConnections(properties.maxConcurrentRequests)
        return BedrockRuntimeClient.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials)
            .httpClientBuilder(httpClient)
            .overrideConfiguration {
                it.apiCallTimeout(properties.timeout)
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
            }
            .build()
    }

    @Bean(name = ["sharedAiBedrockRuntimeAsyncClient"], destroyMethod = "close")
    fun bedrockRuntimeAsyncClient(
        properties: BedrockProviderProperties,
        @Qualifier("sharedAiBedrockCredentials")
        credentials: DefaultCredentialsProvider,
    ): BedrockRuntimeAsyncClient {
        val httpClient = NettyNioAsyncHttpClient.builder()
            .tcpKeepAlive(true)
            .connectionTimeout(properties.connectionTimeout)
            .readTimeout(properties.readTimeout)
            .maxConcurrency(properties.maxConcurrentRequests)
        return BedrockRuntimeAsyncClient.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials)
            .httpClientBuilder(httpClient)
            .overrideConfiguration {
                it.apiCallTimeout(properties.timeout)
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
            }
            .build()
    }

    @Bean
    fun bedrockChatModel(
        properties: BedrockProviderProperties,
        @Qualifier("sharedAiBedrockRuntimeClient")
        runtimeClient: BedrockRuntimeClient,
        @Qualifier("sharedAiBedrockRuntimeAsyncClient")
        asyncClient: BedrockRuntimeAsyncClient,
        observationRegistries: ObjectProvider<ObservationRegistry>,
    ): BedrockProxyChatModel = BedrockProxyChatModel.builder()
        .bedrockRuntimeClient(runtimeClient)
        .bedrockRuntimeAsyncClient(asyncClient)
        .options(
            BedrockChatOptions.builder()
                .model(properties.modelId)
                .maxTokens(properties.maxTokens)
                .build(),
        )
        .observationRegistry(observationRegistries.getIfUnique { ObservationRegistry.NOOP })
        .build()

    @Bean
    fun bedrockModelGateway(
        chatModel: BedrockProxyChatModel,
        properties: BedrockProviderProperties,
    ): SpringAiModelGateway = SpringAiModelGateway(
        ChatClient.builder(chatModel).build(),
        properties.modelId,
    )
}
