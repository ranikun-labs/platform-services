package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.SharedAiModuleConfiguration
import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SharedAiStreamingExecutionService
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.StreamingModelGateway
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient

class SharedAiProviderConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SharedAiModuleConfiguration::class.java, MeterRegistryFixture::class.java)

    @Test
    fun `Shared AI disabled registers no execution runtime or provider`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ModelGateway::class.java)
            assertThat(context).doesNotHaveBean(StreamingModelGateway::class.java)
            assertThat(context).doesNotHaveBean(SharedAiExecutionService::class.java)
            assertThat(context).doesNotHaveBean(ChatModel::class.java)
            assertThat(context).doesNotHaveBean(BedrockRuntimeClient::class.java)
        }
    }

    @Test
    fun `foundation mode hosts exactly one deterministic sync and streaming gateway without AWS clients`() {
        contextRunner
            .withPropertyValues("platform.shared-ai.foundation.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ModelGateway::class.java)
                assertThat(context).hasSingleBean(StreamingModelGateway::class.java)
                assertThat(context.getBean(ModelGateway::class.java))
                    .isSameAs(context.getBean(StreamingModelGateway::class.java))
                    .isInstanceOf(DeterministicModelGateway::class.java)
                assertThat(context).hasSingleBean(SharedAiExecutionService::class.java)
                assertThat(context).hasSingleBean(SharedAiStreamingExecutionService::class.java)
                assertThat(context).doesNotHaveBean(BedrockRuntimeClient::class.java)
                assertThat(context).doesNotHaveBean(BedrockRuntimeAsyncClient::class.java)
            }
    }

    @Test
    fun `Bedrock mode hosts exactly one real sync and streaming gateway`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.bedrock.enabled=true",
                "platform.shared-ai.bedrock.region=ap-northeast-2",
                "platform.shared-ai.bedrock.model-id=test-model-id",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ModelGateway::class.java)
                assertThat(context).hasSingleBean(StreamingModelGateway::class.java)
                assertThat(context.getBean(ModelGateway::class.java))
                    .isSameAs(context.getBean(StreamingModelGateway::class.java))
                    .isInstanceOf(SpringAiModelGateway::class.java)
                assertThat(context).hasSingleBean(BedrockRuntimeClient::class.java)
                assertThat(context).hasSingleBean(BedrockRuntimeAsyncClient::class.java)
            }
    }

    @Test
    fun `Bedrock mode without explicit region fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.bedrock.enabled=true",
                "platform.shared-ai.bedrock.model-id=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Bedrock mode requires platform.shared-ai.bedrock.region")
            }
    }

    @Test
    fun `Bedrock mode without explicit model fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.bedrock.enabled=true",
                "platform.shared-ai.bedrock.region=ap-northeast-2",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Bedrock mode requires platform.shared-ai.bedrock.model-id")
            }
    }

    @Test
    fun `dual gateway selection fails startup instead of creating ambiguity`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.foundation.enabled=true",
                "platform.shared-ai.bedrock.enabled=true",
                "platform.shared-ai.bedrock.region=ap-northeast-2",
                "platform.shared-ai.bedrock.model-id=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Exactly one Shared AI model gateway mode may be enabled")
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class MeterRegistryFixture {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
