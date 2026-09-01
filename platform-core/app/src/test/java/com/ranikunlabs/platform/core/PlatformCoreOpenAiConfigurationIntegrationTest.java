package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                "platform.identity.auth.enabled=false",
                "platform.identity.persistence.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=configuration-test-api-key",
                "platform.shared-ai.openai.model=configuration-test-model",
                "platform.shared-ai.openai.max-output-tokens=32",
                "platform.shared-ai.openai.timeout=5s",
                "management.endpoints.web.exposure.include=health,info,env,configprops"
        })
@AutoConfigureMockMvc
class PlatformCoreOpenAiConfigurationIntegrationTest {

    private static final String API_KEY = "configuration-test-api-key";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bootsWithCurrentDbFreeHostContractAndComposesOneSyncProviderGateway() {
        assertThat(applicationContext.getBeansOfType(ModelGateway.class)).hasSize(1);
        assertThat(applicationContext.getBean(ModelGateway.class))
                .isInstanceOf(SpringAiModelGateway.class);
        assertThat(applicationContext.containsBean("openAiChatModel")).isTrue();
        assertThat(applicationContext.getBean("openAiChatModel").getClass().getName())
                .isEqualTo("org.springframework.ai.openai.OpenAiChatModel");
        assertThat(environment.getProperty("spring.ai.model.chat")).isNull();
        assertThat(environment.getProperty("spring.main.web-application-type"))
                .isEqualTo("servlet");
    }

    @Test
    void actuatorEnvironmentAndConfigPropsDoNotExposeTheConfiguredApiKey() throws Exception {
        mockMvc.perform(get("/actuator/env/platform.shared-ai.openai.api-key"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(API_KEY))));

        mockMvc.perform(get("/actuator/configprops"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(API_KEY))));
    }
}
