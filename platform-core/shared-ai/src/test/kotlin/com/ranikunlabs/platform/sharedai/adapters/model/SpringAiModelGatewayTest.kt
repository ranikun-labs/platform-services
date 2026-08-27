package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class SpringAiModelGatewayTest {
    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues("platform.shared-ai.spring-ai.enabled=true")
        .withUserConfiguration(ChatClientFixture::class.java, SpringAiAdapterConfiguration::class.java)

    @Test
    fun `Spring AI adapter resolves and remains behind application ModelGateway`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(SpringAiModelGateway::class.java)
            assertThat(context.getBean(SpringAiModelGateway::class.java)).isInstanceOf(ModelGateway::class.java)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ChatClientFixture {
        @Bean
        fun chatClient(): ChatClient = mock(ChatClient::class.java)
    }
}
