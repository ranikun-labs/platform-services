# Shared AI Internal Execute HTTP v1 (Local/Test Only)

This document defines a local/test-only HTTP adapter for exercising the existing
Shared AI submission contract. It is not the canonical production API, an
authentication protocol, or an authorization proof.

The controller lives in `platform-core/app`, the HTTP composition root. The
`shared-ai` module remains a transport-neutral library and owns the execution
service, registered skill handlers, execution context, evidence, and technical
result types.

## Activation

The route is registered only when all of these conditions hold:

- The active Spring profile matches `local` or `test` and includes neither
  `prod` nor `production`.
- `platform.shared-ai.http.local-test.enabled=true` is set explicitly.
- `platform.shared-ai.foundation.enabled=true` is enabled.
- `platform.shared-ai.spring-ai.enabled` is not `true`.

Every condition defaults to the disabled path. When the controller is not
registered, the route returns HTTP 404. The app's normal 404 representation is
not part of this wire contract; clients must use the status code and must not
depend on its body.

The controller uses Foundation-only fixture ports. It does not use a paid
provider or a real Product Tool. Production profiles must not set the local/test
opt-in property. This endpoint has no authentication or authorization; submitted
context values are caller-supplied test data. `authorityReference` is passed as
opaque context and does not authorize access to a protected resource.

## Request

`POST /internal/shared-ai/v1/executions`

Content type: `application/json`.

```json
{
  "context": {
    "scope": {
      "productReference": "finance",
      "tenantReference": "tenant:fixture-1",
      "resourceReferences": ["checklist:fixture-1", "question:fixture-1"]
    },
    "principalReference": "principal:fixture-1",
    "requestReference": "request:fixture-1",
    "correlationReference": "correlation:fixture-1",
    "authorityReference": "authority:fixture-1"
  },
  "skillId": "finance.general-analysis@1",
  "query": "Finance-formatted opaque query"
}
```

The object fields are strict: missing or additional fields are invalid. In
particular, the request must not contain `executionReference` or
`contextVersion`. `correlationReference` and `authorityReference` must be
present and may be `null`; otherwise a non-blank string is required. Scope
resource references are a set represented as an array; duplicates and blank
items are invalid. The query is passed unchanged to the explicitly selected
skill and must be non-blank.

The adapter maps `context` to `ExecutionContextSeed`, `skillId` to `SkillId`,
and invokes `SharedAiExecutionService.submit`. Shared AI issues
`executionReference` and `contextVersion`.

## Success response

HTTP 200:

```json
{
  "outcome": "success",
  "executionReference": "shared-ai-issued-reference",
  "interpretation": "Provider-neutral interpretation string",
  "warnings": [],
  "usage": {
    "inputTokens": 7,
    "outputTokens": 5,
    "totalTokens": 12
  },
  "evidence": {
    "context": {
      "scope": {
        "productReference": "finance",
        "tenantReference": "tenant:fixture-1",
        "resourceReferences": ["checklist:fixture-1", "question:fixture-1"]
      },
      "principalReference": "principal:fixture-1",
      "requestReference": "request:fixture-1",
      "executionReference": "shared-ai-issued-reference",
      "correlationReference": "correlation:fixture-1",
      "contextVersion": "1",
      "authorityReference": "authority:fixture-1"
    },
    "items": [
      {
        "evidenceId": "shared-ai-issued-reference:structured-product-fact:0",
        "kind": "STRUCTURED_PRODUCT_FACT",
        "sourceReferences": ["question:source-fixture-1"],
        "producerReference": "test:product-tool",
        "observedAt": "2026-10-29T00:00:00Z",
        "executionReference": "shared-ai-issued-reference",
        "scopeReference": {
          "productReference": "finance",
          "tenantReference": "tenant:fixture-1",
          "resourceReferences": ["checklist:fixture-1", "question:fixture-1"]
        },
        "schemaVersion": "1"
      },
      {
        "evidenceId": "shared-ai-issued-reference:model-interpretation:0",
        "kind": "MODEL_INTERPRETATION",
        "sourceReferences": ["shared-ai-issued-reference:structured-product-fact:0"],
        "producerReference": "test:model",
        "observedAt": "2026-10-29T00:00:00Z",
        "executionReference": "shared-ai-issued-reference",
        "scopeReference": {
          "productReference": "finance",
          "tenantReference": "tenant:fixture-1",
          "resourceReferences": ["checklist:fixture-1", "question:fixture-1"]
        },
        "schemaVersion": "1"
      }
    ]
  }
}
```

The response maps every current `EvidenceItem` field: `evidenceId`, `kind`,
`sourceReferences`, `producerReference`, `observedAt`, `executionReference`,
`scopeReference`, and `schemaVersion`. Scope and source-reference arrays are
sorted by string value for deterministic JSON. `observedAt` is serialized as an
ISO-8601 UTC string. `usage` is `null` when the model gateway did not return
token usage; otherwise it contains `inputTokens`, `outputTokens`, and
`totalTokens`, each of which may be `null`. Every non-null usage count must be
a nonnegative integer. If any count is negative, the adapter returns the
sanitized HTTP 500 `INTERNAL_ERROR` response and does not serialize the success
body.

## Technical failures and transport errors

Every `ExecutionResult.TechnicalFailure` is returned with HTTP 200:

```json
{
  "outcome": "technicalFailure",
  "executionReference": "shared-ai-issued-reference",
  "category": "MODEL_FAILURE"
}
```

Transport handling:

| Condition | HTTP | Stable response body |
| --- | ---: | --- |
| Malformed JSON, missing/extra fields, invalid values, or unsupported content type | 400 | `{"error":"INVALID_REQUEST"}` |
| Unexpected exception escaping submission or response mapping | 500 | `{"error":"INTERNAL_ERROR"}` |
| Endpoint inactive | 404 | Route is absent; body is framework-generated and unspecified |

Raw exception messages are never returned. An unknown registered skill is an
existing `UNSUPPORTED_SKILL` technical failure and therefore uses the HTTP 200
technical-failure shape.

## Invocation limits

The Finance client uses a 1-second connection timeout, 5-second overall
timeout, and no automatic retry. These are client settings; the current Shared
AI service has no per-request deadline or cancellation contract. A client
timeout does not prove that server-side work stopped.

Each repeated request is a separate execution with a new Shared AI execution
reference. This local/test slice provides no persistence-backed idempotency,
deduplication, or retry behavior. A caller must not infer any of those
properties from `requestReference` or `correlationReference`.
