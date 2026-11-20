package com.ranikunlabs.platform.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.ranikunlabs.platform.core.sharedai.LocalTestSharedAiExecutionController;
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService;
import com.ranikunlabs.platform.sharedai.application.SkillRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class LocalTestSharedAiExecutionControllerActivationTest {
    private static final String HTTP_OPT_IN = "platform.shared-ai.http.local-test.enabled=true";
    private static final String FOUNDATION = "platform.shared-ai.foundation.enabled=true";
    private static final String SPRING_AI_OFF = "platform.shared-ai.spring-ai.enabled=false";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ControllerTestConfiguration.class);

    @Test
    void isAbsentWithoutLocalOrTestProfileEvenWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(HTTP_OPT_IN, FOUNDATION, SPRING_AI_OFF)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LocalTestSharedAiExecutionController.class));
    }

    @Test
    void isAbsentWithoutExplicitOptIn() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(FOUNDATION, SPRING_AI_OFF)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LocalTestSharedAiExecutionController.class));
    }

    @Test
    void isAbsentWhenSpringAiModeIsSelected() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(
                        HTTP_OPT_IN,
                        "platform.shared-ai.foundation.enabled=false",
                        "platform.shared-ai.spring-ai.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LocalTestSharedAiExecutionController.class));
    }

    @Test
    void isAbsentWhenProductionProfileIsAlsoActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test", "production"))
                .withPropertyValues(HTTP_OPT_IN, FOUNDATION, SPRING_AI_OFF)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LocalTestSharedAiExecutionController.class));
    }

    @Test
    void isAbsentWhenProdProfileIsAlsoActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test", "prod"))
                .withPropertyValues(HTTP_OPT_IN, FOUNDATION, SPRING_AI_OFF)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LocalTestSharedAiExecutionController.class));
    }

    @Test
    void isAvailableOnlyWithLocalTestOptInAndFoundationMode() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(HTTP_OPT_IN, FOUNDATION, SPRING_AI_OFF)
                .run(context -> assertThat(context)
                        .hasSingleBean(LocalTestSharedAiExecutionController.class));
    }

    @Import(LocalTestSharedAiExecutionController.class)
    static class ControllerTestConfiguration {
        @Bean
        SharedAiExecutionService sharedAiExecutionService() {
            return new SharedAiExecutionService(new SkillRegistry(List.of()));
        }
    }
}
