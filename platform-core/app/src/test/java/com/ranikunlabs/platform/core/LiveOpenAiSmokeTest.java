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
                "platform.identity.auth.enabled=false",
                "platform.identity.persistence.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "platform.shared-ai.spring-ai.enabled=true",
                "platform.shared-ai.openai.api-key=${OPENAI_API_KEY}",
                "platform.shared-ai.openai.model=${SHARED_AI_OPENAI_MODEL}",
                "platform.shared-ai.openai.max-output-tokens=32",
                "platform.shared-ai.openai.timeout=30s"
        })
class LiveOpenAiSmokeTest {

    @Autowired
    private SharedAiExecutionService executionService;

    @Test
    void performsOneBoundedRealOpenAiSyncInference() {
        var result = executionService.execute(new ExecutionCommand(
                new ExecutionContext(
                        new ExecutionScope("live-smoke", "local", Set.of()),
                        "local-operator",
                        "rpl-107-live-openai-sync",
                        "rpl-107-live-openai-sync",
                        null),
                SkillId.REVIEW_KNOWLEDGE,
                "Reply with one short sentence."));

        assertThat(result).isInstanceOfSatisfying(ExecutionResult.Success.class, success -> {
            assertThat(success.getInterpretation()).isNotBlank();
            assertThat(success.getEvidence().getItems().getLast().getProducerReference())
                    .startsWith("openai:");
        });
    }
}
