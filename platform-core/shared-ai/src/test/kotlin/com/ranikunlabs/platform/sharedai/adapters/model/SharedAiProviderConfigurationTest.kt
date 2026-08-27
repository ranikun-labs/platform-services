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
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

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
            assertThat(context).doesNotHaveBean(OpenAiChatModel::class.java)
        }
    }

    @Test
    fun `foundation mode hosts exactly one deterministic sync and streaming gateway without OpenAI`() {
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
                assertThat(context).doesNotHaveBean(OpenAiChatModel::class.java)
            }
    }

    @Test
    fun `OpenAI mode hosts exactly one real sync and streaming gateway`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.openai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ModelGateway::class.java)
                assertThat(context).hasSingleBean(StreamingModelGateway::class.java)
                assertThat(context.getBean(ModelGateway::class.java))
                    .isSameAs(context.getBean(StreamingModelGateway::class.java))
                    .isInstanceOf(SpringAiModelGateway::class.java)
                assertThat(context).hasSingleBean(OpenAiChatModel::class.java)
            }
    }

    @Test
    fun `OpenAI mode without explicit API key fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.openai.enabled=true",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires platform.shared-ai.openai.api-key")
            }
    }

    @Test
    fun `OpenAI mode without explicit model fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.openai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires platform.shared-ai.openai.model")
            }
    }

    @Test
    fun `dual gateway selection fails startup instead of creating ambiguity`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.foundation.enabled=true",
                "platform.shared-ai.openai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
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
