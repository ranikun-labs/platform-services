# Shared AI provider and streaming runtime

Shared AI remains an imperative Kotlin/JVM 21 library hosted by the Spring MVC
Platform Core application. The first real provider adapter uses Spring AI
2.0.1 with AWS Bedrock Converse. That provider choice demonstrates the neutral
gateway boundary; it does not make Bedrock the permanent or exclusive
canonical provider.

## Runtime modes

Exactly one execution mode may be active:

| Mode | Configuration | Runtime result |
| --- | --- | --- |
| Disabled | both enable flags false | no Shared AI execution beans |
| Foundation | `SHARED_AI_FOUNDATION_ENABLED=true` | deterministic sync and streaming gateways; no AWS clients or credentials |
| Bedrock | `SHARED_AI_BEDROCK_ENABLED=true` | Spring AI Bedrock Converse sync and streaming gateways |

Bedrock mode additionally requires externally supplied `AWS_REGION` and
`SHARED_AI_BEDROCK_MODEL_ID`. The model ID is runtime configuration and is not
hardcoded. Optional controls are:

- `SHARED_AI_BEDROCK_MAX_TOKENS` (default `128`)
- `SHARED_AI_BEDROCK_TIMEOUT` (default `30s`)
- `SHARED_AI_BEDROCK_CONNECTION_TIMEOUT` (default `5s`)
- `SHARED_AI_BEDROCK_READ_TIMEOUT` (default `30s`)
- `SHARED_AI_BEDROCK_MAX_CONCURRENT_REQUESTS` (default `8`, validated `1..64`)

The adapter uses the AWS SDK default credential provider chain. Local
development can use AWS CLI/profile or environment credentials; deployed
runtimes can use workload-role credentials. Static access keys, secret keys,
and session tokens must not be committed or baked into the image.
SDK retries are disabled for this slice; retry and rate-limit policy remains a
later reliability decision.

## Streaming boundary

Application code owns `StreamingModelGateway`, neutral model stream events, an
observer, and cancellation. The Spring AI adapter alone consumes Reactor
`Flux<ChatResponse>`. Platform Core remains Spring MVC. The MVC `SseEmitter`
projection is test-only and proves named `started`, `delta`, `completed`, and
`error` events; it is not a Finance or Carelog public wire contract.

Delivery is direct and does not introduce an application chunk queue. The AWS
async client has bounded concurrency, and client disconnect/delivery failure
cancels the provider subscription. Streaming deltas are never Evidence. The
application creates one reference-first model-interpretation Evidence item at
successful completion, correlated to the validated Tool/Retriever source
Evidence.

## Opt-in live smoke

Default tests never call AWS. A live smoke makes one small synchronous
inference with max output tokens fixed to `32`:

```powershell
$env:SHARED_AI_LIVE_BEDROCK = 'true'
$env:AWS_REGION = 'your-region'
$env:SHARED_AI_BEDROCK_MODEL_ID = 'your-enabled-model-or-inference-profile-id'
.\gradlew.bat -p platform-core :app:test --tests '*LiveBedrockSmokeTest'
```

Credentials must resolve through the standard chain. The smoke does not print
credentials or model output. Real streaming smoke remains a later reliability
step; streaming behavior is deterministic at the Spring AI adapter boundary in
this slice.

## Deliberate non-scope

Production authorization, Product-owned Tool implementations, Product
semantics, Production RAG/vector ingestion, retries/fallback routing, billing,
and a permanent public SSE API remain future work.
