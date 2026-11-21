package com.ranikunlabs.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelRequest;
import com.ranikunlabs.platform.sharedai.ports.ProductFact;
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Loopback-only test composition. It is not a provider, authentication or Finance application test. */
@SpringBootTest(classes = PlatformCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "management.server.port=0",
            "management.server.address=127.0.0.1",
            "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
            "platform.identity.datasource.username=identity",
            "platform.identity.datasource.password=provided-at-runtime",
            "platform.identity.redis-host=redis",
            "platform.identity.redis-port=6379",
            "platform.identity.redis-key-prefix=platform:identity:",
            "platform.identity.auth.enabled=false",
            "platform.identity.persistence.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "platform.shared-ai.foundation.enabled=true",
            "platform.shared-ai.spring-ai.enabled=false",
            "platform.shared-ai.http.local-test.enabled=true"
        })
@ActiveProfiles("test")
class FinanceSpringAiHttpCompositionTest {
    private static final String INTERPRETATION = """
            {"summary":"Review current items.","observations":["Synthetic input."],
            "assumptions":["No external facts."],"nextChecks":["Verify primary sources."],
            "evidenceNote":"Synthetic fixture only."}
            """.strip();
    private static final String REQUEST = """
            {"context":{"scope":{"productReference":"finance","tenantReference":"tenant:fixture-1",
            "resourceReferences":["question:fixture-1"]},"principalReference":"principal:fixture-1",
            "requestReference":"request:fixture-1","correlationReference":"correlation:fixture-1",
            "authorityReference":"authority:fixture-1"},"skillId":"finance.general-analysis@1",
            "query":"synthetic finance question"}
            """;

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private ProductToolPort productTool;

    @MockitoBean
    private ModelGateway gateway;

    @Test
    void executesFinanceSubmissionOverLoopbackThroughSpringAiGateway() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                java.util.List.of(new Generation(new AssistantMessage(INTERPRETATION)))));
        var realGateway = new SpringAiModelGateway(ChatClient.builder(model).build(), "test", "stub", null);
        doAnswer(invocation -> realGateway.interpret(invocation.getArgument(0, ModelRequest.class)))
                .when(gateway).interpret(any(ModelRequest.class));
        doAnswer(invocation -> {
            ExecutionContext context = invocation.getArgument(0);
            return new ProductFact(context.getExecutionReference(), context.getScope(), "fact:fixture-1",
                    "test:tool", "count", "3");
        }).when(productTool).readFact(any(ExecutionContext.class));

        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/internal/shared-ai/v1/executions");
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(JsonPath.<String>read(body, "$.outcome")).isEqualTo("success");
        assertThat(JsonPath.<String>read(body, "$.interpretation")).isEqualTo(INTERPRETATION);
        String reference = JsonPath.read(body, "$.executionReference");
        assertThat(reference).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.evidence.context.executionReference")).isEqualTo(reference);
        assertThat(JsonPath.<String>read(body, "$.evidence.items[0].executionReference")).isEqualTo(reference);
        assertThat(JsonPath.<String>read(body, "$.evidence.items[1].executionReference")).isEqualTo(reference);
        assertThat(JsonPath.<String>read(body, "$.evidence.items[0].kind")).isEqualTo("STRUCTURED_PRODUCT_FACT");
        assertThat(JsonPath.<String>read(body, "$.evidence.items[1].kind")).isEqualTo("MODEL_INTERPRETATION");
        assertThat(JsonPath.<String>read(body, "$.evidence.items[1].sourceReferences[0]"))
                .isEqualTo(JsonPath.<String>read(body, "$.evidence.items[0].evidenceId"));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().toString())
                .contains("synthetic finance question", "Structured fact count: 3");

        HttpResponse<String> invalid = client.send(HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(invalid.body(), "$.error")).isEqualTo("INVALID_REQUEST");

        // Optional cross-process consumer check: explicit files, loopback URL, bounded lifetime.
        String ready = System.getenv("FINANCE_HTTP_READY_FILE");
        String done = System.getenv("FINANCE_HTTP_DONE_FILE");
        if (ready != null || done != null) {
            assertThat(ready).isNotBlank();
            assertThat(done).isNotBlank();
            java.nio.file.Files.writeString(java.nio.file.Path.of(ready), "http://127.0.0.1:" + port);
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(4).toNanos();
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(done)) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(done)))
                    .as("Finance consumer completion marker before timeout").isTrue();
        }
    }
}
