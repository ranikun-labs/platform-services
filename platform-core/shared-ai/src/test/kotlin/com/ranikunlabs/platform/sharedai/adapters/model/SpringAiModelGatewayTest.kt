package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.SharedAiModuleConfiguration
import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryProductToolAdapter
import com.ranikunlabs.platform.sharedai.adapters.fixture.InMemoryRetrieverAdapter
import com.ranikunlabs.platform.sharedai.application.ExecutionReferenceGenerator
import com.ranikunlabs.platform.sharedai.application.FinanceGeneralAnalysisSkillHandler
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SkillId
import com.ranikunlabs.platform.sharedai.application.SkillRegistry
import com.ranikunlabs.platform.sharedai.application.UuidExecutionReferenceGenerator
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.beans.factory.NoSuchBeanDefinitionException
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
            assertThat(context).doesNotHaveBean(OpenAiChatModel::class.java)
            assertThat(context).doesNotHaveBean(ChatClient::class.java)
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
                assertThat(context.getBean(ExecutionReferenceGenerator::class.java))
                    .isInstanceOf(UuidExecutionReferenceGenerator::class.java)
                assertThat(context.getBean(ProductToolPort::class.java)).isInstanceOf(InMemoryProductToolAdapter::class.java)
                assertThat(context.getBean(RetrieverPort::class.java)).isInstanceOf(InMemoryRetrieverAdapter::class.java)
                val registry = context.getBean(SkillRegistry::class.java)
                assertThat(registry.find(SkillId.FINANCE_GENERAL_ANALYSIS))
                    .isInstanceOf(FinanceGeneralAnalysisSkillHandler::class.java)
                assertThat(registry.find(SkillId.REVIEW_KNOWLEDGE)).isNotNull
                assertThat(context).doesNotHaveBean(OpenAiChatModel::class.java)
                assertThat(context).doesNotHaveBean(ChatClient::class.java)
            }
    }

    @Test
    fun `foundation mode fails closed under the prod profile`() = assertFoundationModeRejected("prod")

    @Test
    fun `foundation mode fails closed under the production profile`() = assertFoundationModeRejected("production")

    @Test
    fun `foundation mode fails closed when local is mixed with prod`() =
        assertFoundationModeRejected("local", "prod")

    @Test
    fun `foundation mode fails closed when test is mixed with production`() =
        assertFoundationModeRejected("test", "production")

    @Test
    fun `Spring AI mode remains available under production profiles`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("prod", "production") }
            .withUserConfiguration(ExternalProductPortsFixture::class.java)
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ModelGateway::class.java)).isInstanceOf(SpringAiModelGateway::class.java)
                assertThat(context).doesNotHaveBean(DeterministicModelGateway::class.java)
                assertThat(context).doesNotHaveBean(InMemoryProductToolAdapter::class.java)
                assertThat(context).doesNotHaveBean(InMemoryRetrieverAdapter::class.java)
            }
    }

    @Test
    fun `Spring AI mode hosts one OpenAI-backed SpringAiModelGateway`() {
        contextRunner
            .withUserConfiguration(ExternalProductPortsFixture::class.java)
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
                "platform.shared-ai.openai.max-output-tokens=64",
                "platform.shared-ai.openai.timeout=7s",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ModelGateway::class.java)
                assertThat(context.getBean(ModelGateway::class.java)).isInstanceOf(SpringAiModelGateway::class.java)
                assertThat(context).hasSingleBean(SharedAiExecutionService::class.java)
                assertThat(context).hasSingleBean(OpenAiChatModel::class.java)
                assertThat(context).hasSingleBean(ChatClient::class.java)
                assertThat(context).hasSingleBean(ProductToolPort::class.java)
                assertThat(context).hasSingleBean(RetrieverPort::class.java)
                assertThat(context).doesNotHaveBean(InMemoryProductToolAdapter::class.java)
                assertThat(context).doesNotHaveBean(InMemoryRetrieverAdapter::class.java)

                val options = context.getBean(OpenAiChatModel::class.java).options
                assertThat(options.model).isEqualTo("test-model-id")
                assertThat(options.maxCompletionTokens).isEqualTo(64)
                assertThat(options.timeout).isEqualTo(java.time.Duration.ofSeconds(7))
                assertThat(options.maxRetries).isZero()
            }
    }

    @Test
    fun `Spring AI mode without external product ports fails instead of wiring fixture data`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
                val failure = requireNotNull(context.startupFailure)
                val failureMessages = generateSequence(failure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(failureMessages).contains("ProductToolPort")
            }
    }

    @Test
    fun `OpenAI configuration without the Spring AI mode remains inactive`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(ModelGateway::class.java)
                assertThat(context).doesNotHaveBean(OpenAiChatModel::class.java)
            }
    }

    @Test
    fun `Spring AI mode without an API key fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires platform.shared-ai.openai.api-key")
            }
    }

    @Test
    fun `Spring AI mode with a blank API key fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=   ",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires platform.shared-ai.openai.api-key")
            }
    }

    @Test
    fun `Spring AI mode without a model fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires platform.shared-ai.openai.model")
            }
    }

    @Test
    fun `Spring AI mode with a blank model fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=   ",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires platform.shared-ai.openai.model")
            }
    }

    @Test
    fun `invalid output token limit fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
                "platform.shared-ai.openai.max-output-tokens=0",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI mode requires a positive max-output-tokens value")
            }
    }

    @Test
    fun `invalid timeout fails startup clearly`() {
        contextRunner
            .withPropertyValues(
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
                "platform.shared-ai.openai.timeout=0s",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("OpenAI timeout must be positive")
            }
    }

    @Test
    fun `provider properties diagnostic representation excludes the API key`() {
        val secret = "configuration-test-api-key"
        val properties = OpenAiProviderProperties().apply {
            apiKey = secret
            model = "configuration-test-model"
        }

        assertThat(properties.toString())
            .doesNotContain(secret)
            .contains("apiKeyConfigured=true")
    }

    @Test
    fun `dual gateway selection fails startup instead of creating ambiguity`() {
        contextRunner
            .withUserConfiguration(ChatClientFixture::class.java)
            .withPropertyValues(
                "platform.shared-ai.foundation.enabled=true",
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=test-api-key",
                "platform.shared-ai.openai.model=test-model-id",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Exactly one Shared AI model gateway mode must be enabled")
            }
    }

    private fun assertFoundationModeRejected(vararg profiles: String) {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles(*profiles) }
            .withUserConfiguration(ExternalProductPortsFixture::class.java)
            .withPropertyValues("platform.shared-ai.foundation.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Shared AI Foundation mode is disabled for prod and production profiles")
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
        fun chatClient(): ChatClient = ChatClient.builder(
            org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel::class.java),
        ).build()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ExternalProductPortsFixture {
        @Bean
        fun externalProductToolPort(): ProductToolPort = ProductToolPort {
            throw UnsupportedOperationException("test product tool is not invoked during context startup")
        }

        @Bean
        fun externalRetrieverPort(): RetrieverPort = RetrieverPort { _, _ -> emptyList() }
    }
}
