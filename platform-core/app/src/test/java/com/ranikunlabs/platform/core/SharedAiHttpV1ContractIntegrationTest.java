package com.ranikunlabs.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ranikunlabs.platform.sharedai.application.ExecutionReferenceGenerator;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.core.ExecutionScope;
import com.ranikunlabs.platform.sharedai.ports.ModelGateway;
import com.ranikunlabs.platform.sharedai.ports.ModelRequest;
import com.ranikunlabs.platform.sharedai.ports.ModelResult;
import com.ranikunlabs.platform.sharedai.ports.ModelUsage;
import com.ranikunlabs.platform.sharedai.ports.ProductFact;
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "server.port=0",
                "management.server.port=0",
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
                "platform.shared-ai.foundation.enabled=true",
                "platform.shared-ai.spring-ai.enabled=false",
                "platform.shared-ai.http.local-test.enabled=true"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SharedAiHttpV1ContractIntegrationTest {
    private static final String ENDPOINT = "/internal/shared-ai/v1/executions";
    private static final String REQUEST = """
            {
              "context": {
                "scope": {
                  "productReference": "finance",
                  "tenantReference": "tenant:fixture-1",
                  "resourceReferences": ["question:fixture-1", "checklist:fixture-1"]
                },
                "principalReference": "principal:fixture-1",
                "requestReference": "request:fixture-1",
                "correlationReference": "correlation:fixture-1",
                "authorityReference": "authority:fixture-1"
              },
              "skillId": "finance.general-analysis@1",
              "query": "Finance General Analysis\\nQuestion: synthetic fixture"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductToolPort productTool;

    @MockitoBean
    private ModelGateway modelGateway;

    @MockitoBean
    private ExecutionReferenceGenerator executionReferenceGenerator;

    @BeforeEach
    void configureDeterministicPorts() throws Exception {
        when(executionReferenceGenerator.generate())
                .thenAnswer(ignored -> "shared-ai:" + UUID.randomUUID());
        stubProductFact();
        stubModelResult();
    }

    @Test
    void submitsExplicitFinanceSkillAndReturnsSharedAiIssuedContextAndCompleteEvidence() throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("success"))
                .andExpect(jsonPath("$.interpretation").value("Synthetic interpretation for Finance to validate."))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.usage.inputTokens").value(7))
                .andExpect(jsonPath("$.usage.outputTokens").value(5))
                .andExpect(jsonPath("$.usage.totalTokens").value(12))
                .andExpect(jsonPath("$.evidence.context.scope.productReference").value("finance"))
                .andExpect(jsonPath("$.evidence.context.scope.tenantReference").value("tenant:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.scope.resourceReferences[0]").value("checklist:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.scope.resourceReferences[1]").value("question:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.principalReference").value("principal:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.requestReference").value("request:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.correlationReference").value("correlation:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.authorityReference").value("authority:fixture-1"))
                .andExpect(jsonPath("$.evidence.context.contextVersion").value("1"))
                .andExpect(jsonPath("$.evidence.items.length()").value(2))
                .andExpect(jsonPath("$.evidence.items[0].evidenceId").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[0].kind").value("STRUCTURED_PRODUCT_FACT"))
                .andExpect(jsonPath("$.evidence.items[0].sourceReferences[0]").value("question:source-fixture-1"))
                .andExpect(jsonPath("$.evidence.items[0].producerReference").value("test:product-tool"))
                .andExpect(jsonPath("$.evidence.items[0].observedAt").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[0].executionReference").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[0].scopeReference.productReference").value("finance"))
                .andExpect(jsonPath("$.evidence.items[0].scopeReference.tenantReference").value("tenant:fixture-1"))
                .andExpect(jsonPath("$.evidence.items[0].scopeReference.resourceReferences[0]")
                        .value("checklist:fixture-1"))
                .andExpect(jsonPath("$.evidence.items[0].scopeReference.resourceReferences[1]")
                        .value("question:fixture-1"))
                .andExpect(jsonPath("$.evidence.items[0].schemaVersion").value("1"))
                .andExpect(jsonPath("$.evidence.items[1].evidenceId").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[1].kind").value("MODEL_INTERPRETATION"))
                .andExpect(jsonPath("$.evidence.items[1].sourceReferences[0]").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[1].producerReference").value("test:model"))
                .andExpect(jsonPath("$.evidence.items[1].observedAt").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[1].executionReference").isNotEmpty())
                .andExpect(jsonPath("$.evidence.items[1].schemaVersion").value("1"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String issuedReference = JsonPath.read(responseBody, "$.executionReference");
        assertThat(issuedReference).startsWith("shared-ai:").isNotBlank();
        assertThat(JsonPath.<String>read(responseBody, "$.evidence.context.executionReference"))
                .isEqualTo(issuedReference);
        assertThat(JsonPath.<String>read(responseBody, "$.evidence.items[0].executionReference"))
                .isEqualTo(issuedReference);
        assertThat(JsonPath.<String>read(responseBody, "$.evidence.items[1].executionReference"))
                .isEqualTo(issuedReference);
        assertThat(JsonPath.<String>read(responseBody, "$.evidence.items[0].evidenceId"))
                .isEqualTo(issuedReference + ":structured-product-fact:0");
        assertThat(JsonPath.<String>read(responseBody, "$.evidence.items[1].evidenceId"))
                .isEqualTo(issuedReference + ":model-interpretation:0");
        assertThat(JsonPath.<String>read(responseBody, "$.evidence.items[1].sourceReferences[0]"))
                .isEqualTo(JsonPath.<String>read(responseBody, "$.evidence.items[0].evidenceId"));
        assertThat(Instant.parse(JsonPath.read(responseBody, "$.evidence.items[0].observedAt"))).isNotNull();

        var capturedContext = org.mockito.ArgumentCaptor.forClass(ExecutionContext.class);
        org.mockito.Mockito.verify(productTool).readFact(capturedContext.capture());
        assertThat(capturedContext.getValue().getAuthorityReference()).isEqualTo("authority:fixture-1");
        assertThat(capturedContext.getValue().getContextVersion()).isEqualTo("1");

        var capturedModelRequest = org.mockito.ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelGateway).interpret(capturedModelRequest.capture());
        assertThat(capturedModelRequest.getValue().getQuery())
                .isEqualTo("Finance General Analysis\nQuestion: synthetic fixture");
        assertThat(capturedModelRequest.getValue().getScope().getResourceReferences())
                .containsExactlyInAnyOrder("question:fixture-1", "checklist:fixture-1");
    }

    @Test
    void returnsStableBadRequestForMalformedOrExtraWireFields() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.TEXT_PLAIN).content(REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        String callerReference = REQUEST.replace(
                "\"requestReference\": \"request:fixture-1\",",
                "\"requestReference\": \"request:fixture-1\",\n"
                        + "                \"executionReference\": \"caller:must-not-be-used\",");
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(callerReference))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        String callerVersion = REQUEST.replace(
                "\"requestReference\": \"request:fixture-1\",",
                "\"requestReference\": \"request:fixture-1\",\n"
                        + "                \"contextVersion\": \"caller:must-not-be-used\",");
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(callerVersion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verifyNoInteractions(productTool, modelGateway);
    }

    @Test
    void mapsAnUnregisteredSkillToTheExistingTechnicalFailureResult() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("finance.general-analysis@1", "finance.unregistered@1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("technicalFailure"))
                .andExpect(jsonPath("$.category").value("UNSUPPORTED_SKILL"))
                .andExpect(jsonPath("$.executionReference").isNotEmpty());

        verifyNoInteractions(productTool, modelGateway);
    }

    @Test
    void preservesNullUsageWhenTheModelGatewayHasNoTokenCounts() throws Exception {
        doAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResult(
                    request.getExecutionReference(),
                    request.getScope(),
                    "model:source-fixture-1",
                    "test:model",
                    "Synthetic interpretation without token counts.");
        }).when(modelGateway).interpret(any(ModelRequest.class));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("success"))
                .andExpect(jsonPath("$.usage").value(nullValue()));
    }

    @Test
    void mapsNegativeTokenUsageCountsToSanitizedInternalError() throws Exception {
        List<ModelUsage> invalidUsages = List.of(
                new ModelUsage(-1, null, null),
                new ModelUsage(null, -1, null),
                new ModelUsage(null, null, -1));
        int[] invocation = {0};
        doAnswer(requestInvocation -> {
            ModelRequest request = requestInvocation.getArgument(0);
            return new ModelResult(
                    request.getExecutionReference(),
                    request.getScope(),
                    "model:source-fixture-1",
                    "test:model",
                    "Synthetic interpretation with invalid usage.",
                    invalidUsages.get(invocation[0]++));
        }).when(modelGateway).interpret(any(ModelRequest.class));

        for (int ignored = 0; ignored < invalidUsages.size(); ignored++) {
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.usage").doesNotExist());
        }
    }

    @Test
    void mapsProductAndModelCorrelationMismatchToGenericTechnicalFailure() throws Exception {
        doAnswer(invocation -> {
            ExecutionContext context = invocation.getArgument(0);
            return new ProductFact(
                    context.getExecutionReference(),
                    new ExecutionScope(
                            "finance",
                            "tenant:fixture-1",
                            Set.of("question:fixture-1", "checklist:fixture-1", "resource:unowned")),
                    "question:source-fixture-1",
                    "test:product-tool",
                    "synthetic-note-count",
                    "3");
        }).when(productTool).readFact(any(ExecutionContext.class));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("technicalFailure"))
                .andExpect(jsonPath("$.category").value("CORRELATION_FAILURE"))
                .andExpect(jsonPath("$.evidence").doesNotExist());

        org.mockito.Mockito.reset(productTool, modelGateway);
        stubProductFact();
        doAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResult(
                    "shared-ai:other-execution",
                    request.getScope(),
                    "model:source-fixture-1",
                    "test:model",
                    "Synthetic interpretation.");
        }).when(modelGateway).interpret(any(ModelRequest.class));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("technicalFailure"))
                .andExpect(jsonPath("$.category").value("CORRELATION_FAILURE"));
    }

    @Test
    void mapsDirectTimeoutToTechnicalFailureAndUnexpectedSubmissionExceptionToSanitizedServerError()
            throws Exception {
        doAnswer(invocation -> {
            throw new TimeoutException("synthetic timeout detail must not be exposed");
        }).when(modelGateway).interpret(any(ModelRequest.class));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("technicalFailure"))
                .andExpect(jsonPath("$.category").value("MODEL_FAILURE"))
                .andExpect(jsonPath("$.error").doesNotExist());
        verify(modelGateway, times(1)).interpret(any(ModelRequest.class));

        when(executionReferenceGenerator.generate())
                .thenThrow(new IllegalStateException("synthetic internal detail must not be exposed"));
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void repeatedRequestsCreateIndependentSubmissionsWithoutRetryOrDeduplication() throws Exception {
        MvcResult first = mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("success"))
                .andReturn();
        MvcResult second = mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("success"))
                .andReturn();

        String firstReference = JsonPath.read(first.getResponse().getContentAsString(), "$.executionReference");
        String secondReference = JsonPath.read(second.getResponse().getContentAsString(), "$.executionReference");
        assertThat(firstReference).isNotEqualTo(secondReference);
        verify(productTool, times(2)).readFact(any(ExecutionContext.class));
        verify(modelGateway, times(2)).interpret(any(ModelRequest.class));
        verify(executionReferenceGenerator, times(2)).generate();
    }

    private void stubProductFact() throws Exception {
        doAnswer(invocation -> {
            ExecutionContext context = invocation.getArgument(0);
            return new ProductFact(
                    context.getExecutionReference(),
                    context.getScope(),
                    "question:source-fixture-1",
                    "test:product-tool",
                    "synthetic-note-count",
                    "3");
        }).when(productTool).readFact(any(ExecutionContext.class));
    }

    private void stubModelResult() throws Exception {
        doAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResult(
                    request.getExecutionReference(),
                    request.getScope(),
                    "model:source-fixture-1",
                    "test:model",
                    "Synthetic interpretation for Finance to validate.",
                    new ModelUsage(7, 5, 12));
        }).when(modelGateway).interpret(any(ModelRequest.class));
    }
}
