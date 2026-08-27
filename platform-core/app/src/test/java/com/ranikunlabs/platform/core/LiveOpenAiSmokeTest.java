package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionCommand;
import com.ranikunlabs.platform.sharedai.application.ExecutionResult;
import com.ranikunlabs.platform.sharedai.application.ExecutionStreamEvent;
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService;
import com.ranikunlabs.platform.sharedai.application.SharedAiStreamingExecutionService;
import com.ranikunlabs.platform.sharedai.application.SkillId;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.core.ExecutionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SHARED_AI_LIVE_OPENAI", matches = "(?i)true")
@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                "platform.identity.datasource.username=identity",
                "platform.identity.datasource.password=provided-at-runtime",
                "platform.identity.redis-host=redis",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=platform:identity:",
                "platform.shared-ai.openai.enabled=true",
                "platform.shared-ai.openai.api-key=${OPENAI_API_KEY}",
                "platform.shared-ai.openai.model=${SHARED_AI_OPENAI_MODEL}",
                "platform.shared-ai.openai.max-output-tokens=32",
                "platform.shared-ai.openai.timeout=30s"
        })
class LiveOpenAiSmokeTest {
    @Autowired
    private SharedAiExecutionService executionService;

    @Autowired
    private SharedAiStreamingExecutionService streamingExecutionService;

    @Test
    void performsOneBoundedRealOpenAiInference() {
        var result = executionService.execute(command("rpl-107-live-openai-sync"));

        assertThat(result).isInstanceOfSatisfying(ExecutionResult.Success.class, success -> {
            assertThat(success.getInterpretation()).isNotBlank();
            assertThat(success.getEvidence().getItems().getLast().getProducerReference())
                    .startsWith("openai:");
        });
    }

    @Test
    void performsOneBoundedRealOpenAiStreamingInference() throws InterruptedException {
        var deltaCount = new AtomicInteger();
        var completedCount = new AtomicInteger();
        var failedCount = new AtomicInteger();
        var completion = new AtomicReference<ExecutionStreamEvent.Completed>();
        var terminal = new CountDownLatch(1);

        streamingExecutionService.stream(command("rpl-107-live-openai-stream"), event -> {
            if (event instanceof ExecutionStreamEvent.ContentDelta) {
                deltaCount.incrementAndGet();
            } else if (event instanceof ExecutionStreamEvent.Completed completed) {
                completion.set(completed);
                completedCount.incrementAndGet();
                terminal.countDown();
            } else if (event instanceof ExecutionStreamEvent.Failed) {
                failedCount.incrementAndGet();
                terminal.countDown();
            }
        });

        assertThat(terminal.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(deltaCount).hasValueGreaterThan(0);
        assertThat(completedCount).hasValue(1);
        assertThat(failedCount).hasValue(0);
        assertThat(completion.get().getEvidence().getItems().getLast().getProducerReference())
                .startsWith("openai:");
    }

    private static ExecutionCommand command(String executionReference) {
        var context = new ExecutionContext(
                new ExecutionScope("live-smoke", "local", Set.of()),
                "local-operator",
                executionReference,
                executionReference,
                null);
        return new ExecutionCommand(
                context,
                SkillId.REVIEW_KNOWLEDGE,
                "Reply with one short sentence.");
    }
}
