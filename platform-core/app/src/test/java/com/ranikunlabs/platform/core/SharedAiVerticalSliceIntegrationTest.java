package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionCommand;
import com.ranikunlabs.platform.sharedai.application.ExecutionResult;
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService;
import com.ranikunlabs.platform.sharedai.application.SkillId;
import com.ranikunlabs.platform.sharedai.core.EvidenceItem;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.core.ExecutionScope;
import com.ranikunlabs.platform.sharedai.adapters.fixture.DeterministicModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
                "platform.shared-ai.foundation.enabled=true"
        })
@AutoConfigureMockMvc
@Import(SharedAiVerticalSliceIntegrationTest.FoundationExecutionController.class)
class SharedAiVerticalSliceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void executesTheHostedDeterministicVerticalSliceThroughATestOnlyTransportBoundary() throws Exception {
        assertThat(applicationContext.getBeansOfType(ModelGateway.class)).hasSize(1);
        assertThat(applicationContext.getBean(ModelGateway.class)).isInstanceOf(DeterministicModelGateway.class);
        assertThat(applicationContext.containsBean("bedrockChatModel")).isFalse();
        assertThat(applicationContext.containsBean("sharedAiBedrockRuntimeClient")).isFalse();
        mockMvc.perform(post("/internal/shared-ai/execute")
                        .contentType("application/json")
                        .content("""
                                {
                                  "productReference": "product-a",
                                  "tenantReference": "tenant-a",
                                  "principalReference": "principal-a",
                                  "requestReference": "request-a",
                                  "executionReference": "execution-a",
                                  "skillId": "review-knowledge",
                                  "query": "review current knowledge"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionReference").value("execution-a"))
                .andExpect(jsonPath("$.interpretation")
                        .value("Reviewed 3 current items using 1 knowledge item(s)."))
                .andExpect(jsonPath("$.evidenceCategories[0]").value("STRUCTURED_PRODUCT_FACT"))
                .andExpect(jsonPath("$.evidenceCategories[1]").value("RETRIEVED_CONTENT"))
                .andExpect(jsonPath("$.evidenceCategories[2]").value("MODEL_INTERPRETATION"));
    }

    @RestController
    @RequestMapping("/internal/shared-ai")
    static class FoundationExecutionController {
        private final SharedAiExecutionService executionService;

        FoundationExecutionController(SharedAiExecutionService executionService) {
            this.executionService = executionService;
        }

        @PostMapping("/execute")
        ResponseEntity<?> execute(@RequestBody FoundationExecuteRequest request) {
            try {
                var context = new ExecutionContext(
                        new ExecutionScope(request.productReference(), request.tenantReference(), Set.of()),
                        request.principalReference(),
                        request.requestReference(),
                        request.executionReference(),
                        request.requestReference());
                var result = executionService.execute(
                        new ExecutionCommand(context, new SkillId(request.skillId()), request.query()));
                if (result instanceof ExecutionResult.Success success) {
                    List<String> categories = success.getEvidence().getItems().stream()
                            .map(EvidenceItem::getKind)
                            .map(Enum::name)
                            .toList();
                    return ResponseEntity.ok(new FoundationExecuteResponse(
                            success.getExecutionReference(),
                            success.getInterpretation(),
                            categories,
                            success.getWarnings()));
                }
                var failure = (ExecutionResult.TechnicalFailure) result;
                return ResponseEntity.badRequest().body(failure.getCategory().name());
            } catch (IllegalArgumentException invalidRequest) {
                return ResponseEntity.badRequest().body("INVALID_REQUEST");
            }
        }
    }

    record FoundationExecuteRequest(
            String productReference,
            String tenantReference,
            String principalReference,
            String requestReference,
            String executionReference,
            String skillId,
            String query) {
    }

    record FoundationExecuteResponse(
            String executionReference,
            String interpretation,
            List<String> evidenceCategories,
            List<String> warnings) {
    }
}
