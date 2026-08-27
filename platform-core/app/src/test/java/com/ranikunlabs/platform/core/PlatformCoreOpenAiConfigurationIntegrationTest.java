package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import com.ranikunlabs.platform.sharedai.ports.StreamingModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                "platform.identity.datasource.username=identity",
                "platform.identity.datasource.password=provided-at-runtime",
                "platform.identity.redis-host=redis",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=platform:identity:",
                "platform.shared-ai.openai.enabled=true",
                "platform.shared-ai.openai.api-key=configuration-test-api-key",
                "platform.shared-ai.openai.model=configuration-test-model"
        })
class PlatformCoreOpenAiConfigurationIntegrationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void hostsExactlyOneOpenAiGatewayWhileRemainingServletMvc() {
        assertThat(applicationContext.getBeansOfType(ModelGateway.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(StreamingModelGateway.class)).hasSize(1);
        assertThat(applicationContext.getBean(ModelGateway.class))
                .isSameAs(applicationContext.getBean(StreamingModelGateway.class))
                .isInstanceOf(SpringAiModelGateway.class);
        assertThat(applicationContext.containsBean("openAiChatModel")).isTrue();
        assertThat(applicationContext.getBean("openAiChatModel").getClass().getName())
                .isEqualTo("org.springframework.ai.openai.OpenAiChatModel");
        assertThat(applicationContext.getEnvironment().getProperty("spring.ai.model.chat"))
                .isEqualTo("none");
        assertThat(applicationContext.getEnvironment().getProperty("spring.main.web-application-type"))
                .isEqualTo("servlet");
    }
}
