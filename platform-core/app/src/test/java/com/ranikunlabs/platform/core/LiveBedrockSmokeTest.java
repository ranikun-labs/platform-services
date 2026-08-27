package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionCommand;
import com.ranikunlabs.platform.sharedai.application.ExecutionResult;
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService;
import com.ranikunlabs.platform.sharedai.application.SkillId;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.core.ExecutionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SHARED_AI_LIVE_BEDROCK", matches = "(?i)true")
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
                "platform.shared-ai.bedrock.enabled=true",
                "platform.shared-ai.bedrock.region=${AWS_REGION}",
                "platform.shared-ai.bedrock.model-id=${SHARED_AI_BEDROCK_MODEL_ID}",
                "platform.shared-ai.bedrock.max-tokens=32",
                "platform.shared-ai.bedrock.timeout=30s",
                "platform.shared-ai.bedrock.max-concurrent-requests=1"
        })
class LiveBedrockSmokeTest {
    @Autowired
    private SharedAiExecutionService executionService;

    @Test
    void performsOneBoundedRealBedrockConverseInference() {
        var context = new ExecutionContext(
                new ExecutionScope("live-smoke", "local", Set.of()),
                "local-operator",
                "rpl-107-live-smoke",
                "rpl-107-live-smoke",
                null);

        var result = executionService.execute(new ExecutionCommand(
                context,
                SkillId.REVIEW_KNOWLEDGE,
                "Reply with one short sentence."));

        assertThat(result).isInstanceOfSatisfying(ExecutionResult.Success.class, success -> {
            assertThat(success.getInterpretation()).isNotBlank();
            assertThat(success.getEvidence().getItems().getLast().getProducerReference())
                    .startsWith("bedrock-converse:");
        });
    }
}
