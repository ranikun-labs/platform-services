package com.ranikunlabs.platform.core.sharedai;

import com.ranikunlabs.platform.sharedai.application.ExecutionContextSeed;
import com.ranikunlabs.platform.sharedai.application.ExecutionResult;
import com.ranikunlabs.platform.sharedai.application.ExecutionResult.Success;
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService;
import com.ranikunlabs.platform.sharedai.application.SkillId;
import com.ranikunlabs.platform.sharedai.application.TechnicalFailureCategory;
import com.ranikunlabs.platform.sharedai.core.EvidenceItem;
import com.ranikunlabs.platform.sharedai.core.ExecutionContext;
import com.ranikunlabs.platform.sharedai.core.ExecutionScope;
import com.ranikunlabs.platform.sharedai.ports.ModelUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

/**
 * Local/test-only HTTP adapter for exercising the Shared AI submission contract.
 * It is not an authenticated production endpoint.
 */
@RestController
@RequestMapping("/internal/shared-ai/v1/executions")
@Profile("!prod & !production & (local | test)")
@ConditionalOnExpression(
        "${platform.shared-ai.http.local-test.enabled:false} "
                + "&& ${platform.shared-ai.foundation.enabled:false} "
                + "&& !${platform.shared-ai.spring-ai.enabled:false}")
public class LocalTestSharedAiExecutionController {
    private static final Set<String> REQUEST_FIELDS = Set.of("context", "skillId", "query");
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "scope", "principalReference", "requestReference", "correlationReference", "authorityReference");
    private static final Set<String> SCOPE_FIELDS = Set.of(
            "productReference", "tenantReference", "resourceReferences");

    private final SharedAiExecutionService executionService;

    public LocalTestSharedAiExecutionController(SharedAiExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@RequestBody Map<String, Object> requestBody) {
        final Submission submission;
        try {
            submission = parseSubmission(requestBody);
        } catch (IllegalArgumentException invalidRequest) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        final ExecutionResult result;
        try {
            result = executionService.submit(submission.contextSeed(), submission.skillId(), submission.query());
        } catch (Exception unexpected) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        }

        if (result instanceof Success success) {
            return ResponseEntity.ok(successResponse(success));
        }

        var failure = (ExecutionResult.TechnicalFailure) result;
        return ResponseEntity.ok(new TechnicalFailureResponse(
                "technicalFailure",
                failure.getExecutionReference(),
                failure.getCategory()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ErrorResponse> malformedRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpectedRequestFailure(Exception ignored) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    private static Submission parseSubmission(Map<String, Object> body) {
        Map<String, Object> root = exactObject(body, REQUEST_FIELDS);
        Map<String, Object> context = exactObject(root.get("context"), CONTEXT_FIELDS);
        Map<String, Object> scope = exactObject(context.get("scope"), SCOPE_FIELDS);

        String productReference = requiredText(scope, "productReference");
        String tenantReference = requiredText(scope, "tenantReference");
        List<String> resourceReferences = stringList(scope.get("resourceReferences"));
        if (new TreeSet<>(resourceReferences).size() != resourceReferences.size()) {
            throw new IllegalArgumentException("resourceReferences must be unique");
        }

        var executionScope = new ExecutionScope(
                productReference,
                tenantReference,
                Set.copyOf(resourceReferences));
        var contextSeed = new ExecutionContextSeed(
                executionScope,
                requiredText(context, "principalReference"),
                requiredText(context, "requestReference"),
                optionalText(context, "correlationReference"),
                optionalText(context, "authorityReference"));
        var skillId = new SkillId(requiredText(root, "skillId"));
        String query = requiredText(root, "query");
        return new Submission(contextSeed, skillId, query);
    }

    private static Map<String, Object> exactObject(Object value, Set<String> expectedFields) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        var object = new HashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected string object keys");
            }
            object.put(key, entry.getValue());
        }
        if (!object.keySet().equals(expectedFields)) {
            throw new IllegalArgumentException("Unexpected or missing JSON fields");
        }
        return object;
    }

    private static String requiredText(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be null or a non-blank string");
        }
        return text;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("resourceReferences must be an array");
        }
        var result = new ArrayList<String>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("resourceReferences entries must be non-blank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static SuccessResponse successResponse(Success success) {
        return new SuccessResponse(
                "success",
                success.getExecutionReference(),
                success.getInterpretation(),
                success.getWarnings(),
                usage(success.getUsage()),
                evidence(success.getEvidence().getContext(), success.getEvidence().getItems()));
    }

    private static EvidenceResponse evidence(ExecutionContext context, List<EvidenceItem> items) {
        return new EvidenceResponse(
                context(context),
                items.stream().map(LocalTestSharedAiExecutionController::evidenceItem).toList());
    }

    private static ExecutionContextResponse context(ExecutionContext context) {
        return new ExecutionContextResponse(
                scope(context.getScope()),
                context.getPrincipalReference(),
                context.getRequestReference(),
                context.getExecutionReference(),
                context.getCorrelationReference(),
                context.getContextVersion(),
                context.getAuthorityReference());
    }

    private static EvidenceItemResponse evidenceItem(EvidenceItem item) {
        return new EvidenceItemResponse(
                item.getEvidenceId().getValue(),
                item.getKind(),
                item.getSourceReferences().stream().sorted().toList(),
                item.getProducerReference(),
                item.getObservedAt().toString(),
                item.getExecutionReference(),
                scope(item.getScopeReference()),
                item.getSchemaVersion());
    }

    private static ScopeResponse scope(ExecutionScope scope) {
        return new ScopeResponse(
                scope.getProductReference(),
                scope.getTenantReference(),
                scope.getResourceReferences().stream().sorted().toList());
    }

    private static ModelUsageResponse usage(ModelUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer inputTokens = usage.getInputTokens();
        Integer outputTokens = usage.getOutputTokens();
        Integer totalTokens = usage.getTotalTokens();
        if (isNegative(inputTokens) || isNegative(outputTokens) || isNegative(totalTokens)) {
            throw new IllegalStateException("Model usage counts must be nonnegative.");
        }
        return new ModelUsageResponse(inputTokens, outputTokens, totalTokens);
    }

    private static boolean isNegative(Integer tokenCount) {
        return tokenCount != null && tokenCount < 0;
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(new ErrorResponse(error));
    }

    private record Submission(ExecutionContextSeed contextSeed, SkillId skillId, String query) {
    }

    public record ScopeResponse(String productReference, String tenantReference, List<String> resourceReferences) {
    }

    public record ExecutionContextResponse(
            ScopeResponse scope,
            String principalReference,
            String requestReference,
            String executionReference,
            String correlationReference,
            String contextVersion,
            String authorityReference) {
    }

    public record EvidenceItemResponse(
            String evidenceId,
            com.ranikunlabs.platform.sharedai.core.EvidenceCategory kind,
            List<String> sourceReferences,
            String producerReference,
            String observedAt,
            String executionReference,
            ScopeResponse scopeReference,
            String schemaVersion) {
    }

    public record EvidenceResponse(ExecutionContextResponse context, List<EvidenceItemResponse> items) {
    }

    public record ModelUsageResponse(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    }

    public record SuccessResponse(
            String outcome,
            String executionReference,
            String interpretation,
            List<String> warnings,
            ModelUsageResponse usage,
            EvidenceResponse evidence) {
    }

    public record TechnicalFailureResponse(
            String outcome,
            String executionReference,
            TechnicalFailureCategory category) {
    }

    public record ErrorResponse(String error) {
    }
}
