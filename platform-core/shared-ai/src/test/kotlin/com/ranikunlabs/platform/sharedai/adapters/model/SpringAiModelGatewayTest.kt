package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.SharedAiModuleConfiguration
import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class SpringAiModelGatewayTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SharedAiModuleConfiguration::class.java, MeterRegistryFixture::class.java)

    @Test
    fun `Shared AI disabled registers no execution runtime`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ModelGateway::class.java)
            assertThat(context).doesNotHaveBean(SharedAiExecutionService::class.java)
        }
    }

    @Test
    fun `foundation mode hosts exactly one deterministic ModelGateway`() {
        contextRunner
            .withPropertyValues("platform.shared-ai.foundation.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ModelGateway::class.java)
                assertThat(context.getBean(ModelGateway::class.java)).isInstanceOf(DeterministicModelGateway::class.java)
                assertThat(context).hasSingleBean(SharedAiExecutionService::class.java)
            }
    }

    @Test
    fun `Spring AI mode hosts exactly one SpringAiModelGateway through the real module composition`() {
        contextRunner
            .withUserConfiguration(ChatClientFixture::class.java)
            .withPropertyValues("platform.shared-ai.spring-ai.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ModelGateway::class.java)
                assertThat(context.getBean(ModelGateway::class.java)).isInstanceOf(SpringAiModelGateway::class.java)
                assertThat(context).hasSingleBean(SharedAiExecutionService::class.java)
            }
    }

    @Test
    fun `Spring AI mode without ChatClient fails startup clearly`() {
        contextRunner
            .withPropertyValues("platform.shared-ai.spring-ai.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Spring AI model gateway mode requires exactly one ChatClient")
            }
    }

    @Test
    fun `dual gateway selection fails startup instead of creating ambiguity`() {
        contextRunner
            .withUserConfiguration(ChatClientFixture::class.java)
            .withPropertyValues(
                "platform.shared-ai.foundation.enabled=true",
                "platform.shared-ai.spring-ai.enabled=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Exactly one Shared AI model gateway mode must be enabled")
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class MeterRegistryFixture {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ChatClientFixture {
        @Bean
        fun chatClient(): ChatClient = mock(ChatClient::class.java)
    }
}
