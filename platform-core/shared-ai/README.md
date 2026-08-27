# Shared AI provider and streaming runtime

Shared AI remains an imperative Kotlin/JVM 21 library hosted by the Spring MVC
Platform Core application. The first deployment provider adapter uses Spring AI
2.0.1 with the OpenAI API. This implementation demonstrates the neutral gateway
boundary; it does not make OpenAI canonical provider ownership or prevent a
future adapter decision.

## Runtime modes

Exactly one execution mode may be active:

| Mode | Configuration | Runtime result |
| --- | --- | --- |
| Disabled | both enable flags false | no Shared AI execution beans |
| Foundation | `SHARED_AI_FOUNDATION_ENABLED=true` | deterministic sync and streaming gateways; no API key |
| OpenAI | `SHARED_AI_OPENAI_ENABLED=true` | Spring AI OpenAI sync and streaming gateways |

OpenAI mode requires externally supplied `OPENAI_API_KEY` and
`SHARED_AI_OPENAI_MODEL`. The model is runtime configuration and is not
hardcoded. Optional bounded controls are:

- `SHARED_AI_OPENAI_MAX_OUTPUT_TOKENS` (default `128`)
- `SHARED_AI_OPENAI_TIMEOUT` (default `30s`)

The adapter sets the OpenAI client retry count to zero for this slice. Retry,
rate-limit, and fallback policy remain later reliability decisions. The API key
must not be committed, logged, or baked into an image. Foundation mode never
creates an OpenAI client and remains credential-free.

## Streaming boundary

Application code owns `StreamingModelGateway`, neutral model stream events, an
observer, and cancellation. The Spring AI adapter alone consumes Reactor
`Flux<ChatResponse>`. Platform Core remains Spring MVC. The MVC `SseEmitter`
projection is test-only and proves named `started`, `delta`, `completed`, and
`error` events; it is not a Finance or Carelog public wire contract.

Delivery is direct and does not introduce an application chunk queue. Client
disconnect or delivery failure cancels the provider subscription. Streaming
deltas are never Evidence. The application creates one reference-first
model-interpretation Evidence item at successful completion, correlated to the
validated Tool/Retriever source Evidence.

## Opt-in live smoke

Default tests never call the paid OpenAI API. The live smoke makes one bounded
synchronous inference and one bounded streaming inference, each limited to 32
output tokens:

```powershell
$env:SHARED_AI_LIVE_OPENAI = 'true'
$env:OPENAI_API_KEY = 'your-api-key'
$env:SHARED_AI_OPENAI_MODEL = 'your-enabled-model'
.\gradlew.bat -p platform-core :app:test --tests '*LiveOpenAiSmokeTest'
```

The smoke does not print credentials or model output. A successful streaming
smoke requires at least one content delta, exactly one completion, and no
failure event.

## Deployment and non-scope

The provider slice requires only Platform Core/JVM, outbound HTTPS access, and
external OpenAI configuration. It remains suitable for a personal Mac mini,
home server, or the existing Docker image without a cloud-vendor runtime.

Production authorization, Product-owned Tool implementations, Product
semantics, Production RAG/vector ingestion, retries/fallback routing, billing,
and a permanent public SSE API remain future work.
