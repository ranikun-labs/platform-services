# Shared AI synchronous OpenAI provider

Shared AI is a hosted Kotlin/JVM library. Slice A adds one synchronous OpenAI
adapter behind the existing neutral `ModelGateway`; Product and application
contracts remain provider-neutral.

The implementation follows the canonical Shared AI architecture at
[`2846082f94e3b4bc320b9ccc119433ae8cea632d`](https://github.com/ranikun-labs/shared-ai-architecture/tree/2846082f94e3b4bc320b9ccc119433ae8cea632d),
including ADR-0007's current OpenAI/GPT provider target and the bounded typed
workflow policy that treats LLM output as untrusted and keeps control in code.

## Runtime modes

The existing mode contract is preserved:

| Mode | Properties | Provider behavior |
| --- | --- | --- |
| Disabled | both `platform.shared-ai.foundation.enabled` and `platform.shared-ai.spring-ai.enabled` are false | no Shared AI runtime or provider bean |
| Foundation | `platform.shared-ai.foundation.enabled=true` | deterministic gateway; no OpenAI credentials required |
| Spring AI | `platform.shared-ai.spring-ai.enabled=true` | one manually composed OpenAI `ChatClient` and synchronous gateway |

Foundation and Spring AI modes are mutually exclusive. Spring AI mode requires
the following external configuration:

- `platform.shared-ai.openai.api-key` from `OPENAI_API_KEY`
- `platform.shared-ai.openai.model` from `SHARED_AI_OPENAI_MODEL`
- optional bounded `platform.shared-ai.openai.max-output-tokens` (default `128`)
- optional explicit `platform.shared-ai.openai.timeout` (default `30s`)

The exact GPT model is intentionally deferred and has no production default.
Retries are disabled for this first slice (`maxRetries(0)`). The library uses
the Spring AI `spring-ai-openai` model artifact, not the OpenAI starter; there
is no global `spring.ai.model.*` suppression in the host configuration.

Provider failures are classified only inside the adapter. The application sees
the existing coarse `MODEL_FAILURE` category, while model results retain only
neutral execution/scope/source/producer references and optional token usage.
Input sent to the model remains fenced as untrusted data with
`BEGIN_UNTRUSTED_INPUT` and `END_UNTRUSTED_INPUT` markers.

API keys are never logged or included in provider properties' diagnostic
representation. The host exposes only health/info Actuator endpoints by
default; if environment/configuration diagnostics are deliberately enabled,
their normal value-showing policy must remain non-exposing.

## Opt-in live smoke

Normal tests make no paid provider call. To run the single synchronous smoke
explicitly:

```powershell
$env:SHARED_AI_LIVE_OPENAI = 'true'
$env:OPENAI_API_KEY = 'your-api-key'
$env:SHARED_AI_OPENAI_MODEL = 'your-enabled-model'
.\gradlew.bat -p platform-core :app:test --tests '*LiveOpenAiSmokeTest'
```

The smoke uses one bounded request, 32 output tokens, a 30-second timeout, and
does not log the prompt, response, model output, or credential.

`SYNC_CANCELLATION_SEAM_DEFERRED_TO_EXISTING_CONTRACT_EVOLUTION`: the current
synchronous neutral `ModelGateway` has no cancellation operation, so Slice A
does not invent one. Streaming, cancellation terminal semantics, resource
limits, and usage aggregation remain Slice B work.
