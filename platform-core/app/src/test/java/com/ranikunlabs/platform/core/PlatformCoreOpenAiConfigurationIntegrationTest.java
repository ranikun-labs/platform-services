package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

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
class PlatformCoreOpenAiConfigurationIntegrationTest {

    private static final String API_KEY = "configuration-test-api-key";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

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
        int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);

        HttpResponse<String> applicationActuator = get(applicationPort,
                "/actuator/env/platform.shared-ai.openai.api-key");
        assertThat(applicationActuator.statusCode()).isEqualTo(404);

        HttpResponse<String> environmentResponse = get(managementPort,
                "/actuator/env/platform.shared-ai.openai.api-key");
        assertThat(environmentResponse.statusCode()).isEqualTo(200);
        assertThat(environmentResponse.body()).doesNotContain(API_KEY);

        HttpResponse<String> configPropsResponse = get(managementPort, "/actuator/configprops");
        assertThat(configPropsResponse.statusCode()).isEqualTo(200);
        assertThat(configPropsResponse.body()).doesNotContain(API_KEY);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
