package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionCommand;
import com.ranikunlabs.platform.sharedai.application.SharedAiStreamingExecutionService;
import com.ranikunlabs.platform.sharedai.application.SkillId;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.core.ExecutionScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
                "platform.shared-ai.foundation.enabled=true"
        })
@AutoConfigureMockMvc
@Import(SharedAiStreamingSseIntegrationTest.TestOnlyStreamingController.class)
class SharedAiStreamingSseIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void projectsTheValidatedFoundationStreamToExplicitMvcSseEvents() throws Exception {
        MvcResult started = mockMvc.perform(post("/internal/shared-ai/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
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
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String wire = completed.getResponse().getContentAsString();

        int startedIndex = wire.indexOf("event:started");
        int firstDeltaIndex = wire.indexOf("event:delta");
        int secondDeltaIndex = wire.indexOf("event:delta", firstDeltaIndex + 1);
        int completedIndex = wire.indexOf("event:completed");
        assertThat(startedIndex).isGreaterThanOrEqualTo(0);
        assertThat(firstDeltaIndex).isGreaterThan(startedIndex);
        assertThat(secondDeltaIndex).isGreaterThan(firstDeltaIndex);
        assertThat(completedIndex).isGreaterThan(secondDeltaIndex);
        assertThat(wire)
                .contains("Reviewed 3 current items", "using 1 knowledge item(s).")
                .contains("STRUCTURED_PRODUCT_FACT", "RETRIEVED_CONTENT", "MODEL_INTERPRETATION");
    }

    @RestController
    @RequestMapping("/internal/shared-ai")
    static class TestOnlyStreamingController {
        private static final long SSE_TIMEOUT_MILLIS = 30_000;
        private final SharedAiStreamingExecutionService executionService;

        TestOnlyStreamingController(SharedAiStreamingExecutionService executionService) {
            this.executionService = executionService;
        }

        @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream(@RequestBody StreamingRequest request) {
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
            var projection = new TestOnlySseProjection(new TestOnlySseProjection.SseEventSender() {
                @Override
                public void send(String eventName, Object data) throws java.io.IOException {
                    emitter.send(SseEmitter.event().name(eventName).data(data));
                }

                @Override
                public void complete() {
                    emitter.complete();
                }

                @Override
                public void completeWithError(Throwable failure) {
                    emitter.completeWithError(failure);
                }
            });
            emitter.onCompletion(projection::cancel);
            emitter.onTimeout(projection::cancel);
            emitter.onError(ignored -> projection.cancel());

            var context = new ExecutionContext(
                    new ExecutionScope(request.productReference(), request.tenantReference(), Set.of()),
                    request.principalReference(),
                    request.requestReference(),
                    request.executionReference(),
                    request.requestReference());
            var cancellation = executionService.stream(
                    new ExecutionCommand(context, new SkillId(request.skillId()), request.query()),
                    projection);
            projection.attach(cancellation);
            return emitter;
        }
    }

    record StreamingRequest(
            String productReference,
            String tenantReference,
            String principalReference,
            String requestReference,
            String executionReference,
            String skillId,
            String query) {
    }
}
