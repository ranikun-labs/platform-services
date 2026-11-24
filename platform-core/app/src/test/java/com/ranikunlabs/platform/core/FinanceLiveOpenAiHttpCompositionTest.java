package com.ranikunlabs.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.ranikunlabs.platform.sharedai.adapters.model.OpenAiProviderConfiguration;
import com.ranikunlabs.platform.sharedai.adapters.model.OpenAiProviderProperties;
import com.ranikunlabs.platform.sharedai.adapters.model.SpringAiModelGateway;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelRequest;
import com.ranikunlabs.platform.sharedai.ports.ProductFact;
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실모델 화면 검수 전용 루프백 구성. 기본 OFF이며 SHARED_AI_LIVE_FINANCE_HTTP=true일 때만 실행된다.
 * 기존 LiveOpenAiSmokeTest·mock 구성 테스트와 활성화 변수와 마커 변수를 공유하지 않는다(실행 단위 호출 1회 보장).
 * HTTP 연동은 FinanceSpringAiHttpCompositionTest와 같고, ChatModel만 운영 빌더(maxRetries=0)로 만든 실모델이다.
 * Finance BE 한 프로세스가 루프백으로 소비하는 동안 열어 두고, 종료 시 Provider 호출이 정확히 1회인지 확인한다.
 */
@EnabledIfEnvironmentVariable(named = "SHARED_AI_LIVE_FINANCE_HTTP", matches = "(?i)true")
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
class FinanceLiveOpenAiHttpCompositionTest {
    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private ProductToolPort productTool;

    @MockitoBean
    private ModelGateway gateway;

    @Test
    void servesOneLiveOpenAiInterpretationToLoopbackFinanceConsumer() throws Exception {
        String model = requiredEnv("SHARED_AI_OPENAI_MODEL");
        OpenAiProviderProperties properties = new OpenAiProviderProperties();
        properties.setApiKey(requiredEnv("OPENAI_API_KEY"));
        properties.setModel(model);
        properties.setMaxOutputTokens(Integer.parseInt(requiredEnv("SHARED_AI_OPENAI_MAX_OUTPUT_TOKENS")));
        properties.setTimeout(Duration.ofSeconds(30));
        StaticListableBeanFactory observations = new StaticListableBeanFactory();
        observations.addBean("observationRegistry", ObservationRegistry.NOOP);
        // 운영 Provider 빌더를 그대로 재사용한다(자동 재시도 0회, 출력 토큰 상한, 타임아웃).
        OpenAiChatModel live = new OpenAiProviderConfiguration()
                .openAiChatModel(properties, observations.getBeanProvider(ObservationRegistry.class));

        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        List<String> usage = new CopyOnWriteArrayList<>();
        // 나머지 메서드는 실모델에 위임하고, call(Prompt)만 횟수를 센다.
        ChatModel counted = mock(ChatModel.class, delegatesTo(live));
        doAnswer(invocation -> {
            // 두 번째 호출은 Provider로 보내지 않고 실패시킨다(추가 과금 방지).
            if (attempts.incrementAndGet() > 1) {
                throw new IllegalStateException("live inspection allows exactly one provider call");
            }
            providerCalls.incrementAndGet();
            ChatResponse response = live.call(invocation.getArgument(0, Prompt.class));
            var u = response.getMetadata().getUsage();
            usage.add("promptTokens=" + u.getPromptTokens() + " completionTokens=" + u.getCompletionTokens());
            return response;
        }).when(counted).call(any(Prompt.class));
        var realGateway = new SpringAiModelGateway(ChatClient.builder(counted).build(), "openai", model, null);
        List<String> references = new CopyOnWriteArrayList<>();
        List<String> outcomes = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            try {
                var result = realGateway.interpret(invocation.getArgument(0, ModelRequest.class));
                outcomes.add("gateway=success interpretationChars=" + result.getInterpretation().length());
                return result;
            } catch (RuntimeException failure) {
                // 실패 분류만 남긴다. 메시지에는 Provider 응답이 섞일 수 있어 기록하지 않는다.
                outcomes.add("gateway=failure type=" + failure.getClass().getSimpleName());
                throw failure;
            }
        }).when(gateway).interpret(any(ModelRequest.class));
        doAnswer(invocation -> {
            ExecutionContext context = invocation.getArgument(0);
            // 증거 연결용 참조만 남긴다. 질문 원문과 비밀값은 기록하지 않는다.
            references.add("requestReference=" + context.getRequestReference()
                    + " executionReference=" + context.getExecutionReference());
            return new ProductFact(context.getExecutionReference(), context.getScope(), "fact:fixture-1",
                    "test:tool", "count", "3");
        }).when(productTool).readFact(any(ExecutionContext.class));

        // Finance BE와의 루프백 랑데부. 실모델 모드에서는 필수다.
        Path ready = Path.of(requiredEnv("FINANCE_LIVE_READY_FILE")).toAbsolutePath().normalize();
        Path done = Path.of(requiredEnv("FINANCE_LIVE_DONE_FILE")).toAbsolutePath().normalize();
        assertThat(ready).isNotEqualTo(done);
        assertThat(Files.exists(ready)).as("ready marker must be fresh").isFalse();
        assertThat(Files.exists(done)).as("completion marker must be fresh").isFalse();
        Files.writeString(ready, "http://127.0.0.1:" + port);
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (!Files.exists(done) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        System.out.println("LIVE_EVIDENCE " + references + " attempts=" + attempts.get()
                + " providerCalls=" + providerCalls.get() + " producer=openai:model:" + model
                + " outcomes=" + outcomes + " usage=" + usage);
        assertThat(Files.exists(done)).as("Finance consumer completion marker before timeout").isTrue();
        assertThat(references).hasSize(1);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(providerCalls.get()).isEqualTo(1);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        assertThat(value).as(name + " must be set for live inspection").isNotBlank();
        return value;
    }
}
