# Ranikun Labs Shared Platform

This repository contains two independently buildable and deployable runtime
boundaries:

- `gateway-app`: the Spring Cloud Gateway / WebFlux public-ingress process on
  port `8080`.
- `platform-core`: the Spring MVC modular-monolith process on port `8081`.

Platform Core hosts the logical modules `identity` and `shared-ai`. Identity
remains a Java library/logical module and owns framework-neutral Identity
contracts plus its relational persistence: the `platform_accounts`,
`password_credentials`, `external_identities`, `refresh_token`, and
`product_clients` tables, their Flyway schema/migration, JPA repositories, and
persistence adapters. Its extracted password-authentication and token-lifecycle
capabilities include credential authentication, `PlatformAccount` status
validation, JWT access/refresh issuance, refresh-session persistence/rotation,
Redis token blacklisting using `blacklist:<rawToken>`, password-account
registration persistence, and password updates. Identity auth composition is
opt-in: `platform.identity.auth.enabled=false` remains foundation mode without
the Identity Auth runtime. The current OAuth provider runtime is the ordinary
Kakao Login flow described below; no other provider is activated by this
module. Shared AI is a Kotlin library module hosted by the app, not another
server.

## Build structure

The root build is an aggregate-only Gradle composite. Gateway and Platform Core
each own their plugin and dependency baseline, so Platform Core can evolve
without forcing a Spring Cloud migration on Gateway.

```text
platform-services/
├── gateway-app/                 independent Gradle build and WebFlux runtime
└── platform-core/               independent multi-project Gradle build
    ├── app/                     Java 21, only executable Spring MVC host
    ├── identity/                Java 21 library/logical module
    └── shared-ai/               Kotlin/JVM 21 library/logical module
```

The repository wrapper remains the single developer entry point:

```sh
./gradlew build                  # aggregate Gateway + Platform Core build
./gradlew test                   # aggregate tests
./gradlew bootJar                # both executable runtime artifacts
```

Each runtime is also independently buildable:

```sh
./gradlew -p gateway-app clean test bootJar
./gradlew -p platform-core clean build
./gradlew -p platform-core :app:bootJar
```

The Platform Core dependency direction is `app -> {identity, shared-ai}`. The
`verifyModuleBoundaries` task, included in Platform Core `check`, verifies that
the app owns the Spring Boot plugin, feature modules do not, and no feature
module directly depends on another Platform Core project.

Platform Core uses Java 21, Spring Boot 4.1.1, Kotlin 2.3.21, Spring AI 2.0.1,
and Gradle 8.14.3. Gateway remains independently governed on JVM target 17,
Spring Boot 3.4.5, and Spring Cloud 2024.0.1. For local IDE setup, use an
Eclipse Temurin 21 Project SDK; Gradle toolchains continue to compile Gateway
for Java/Kotlin 17.

Shared AI contains one explicit `review-knowledge` foundation Skill and a
deterministic execution slice through Product Tool, Retriever, and ModelGateway
ports. Its in-memory Product Tool, Retriever, and ModelGateway are fixtures.
The Spring AI `ChatClient` adapter is present behind the application-owned
ModelGateway, but no live provider is configured or required by tests.

## Runtime configuration

Gateway configuration remains under `PLATFORM_GATEWAY_*`. The Platform Core
host owns `PLATFORM_CORE_PORT`; hosted Identity configuration remains under
`PLATFORM_IDENTITY_*`. Required values have no usable application defaults and
fail startup when absent.

Run the artifacts independently:

```sh
java -jar gateway-app/build/libs/gateway-app-*.jar
java -jar platform-core/app/build/libs/platform-core-app-*.jar
```

Readiness endpoints are `GET /actuator/health/readiness` on each process.
Readiness proves only that the corresponding host can receive requests; it does
not claim Product reachability or database/Redis business connectivity.

## Shared Identity OAuth / Kakao

Identity exposes a provider-neutral OAuth boundary with a production Kakao REST
Login adapter. Its HTTP contract is:

- `POST /api/v1/auth/oauth/kakao/authorization`
- `POST /api/v1/auth/oauth/kakao/exchange`

The browser callback is the configured product-client redirect, not the
Platform exchange endpoint. The current WEB Carelog integration boundary is:

`http://localhost:5173/oauth/kakao/callback`

The callback is resolved for the `carelog-web` product client through
`oauth.provider-callbacks.kakao.carelog-web`. The existing resolver also
retains its `oauth.redirect-uris.kakao.WEB` fallback. The Shared Identity
namespace is `oauth.kakao.*`; legacy `carelog.auth.oauth.kakao.*` references
are not the canonical configuration namespace and have no compatibility alias
in this slice.

Kakao provider properties are source-backed as follows:

| Property | Type | Default / requirement |
| --- | --- | --- |
| `oauth.kakao.client-id` | String | Required when the provider is configured; no default |
| `oauth.kakao.client-secret` | String | Required when `client-secret-enabled=true`; no default |
| `oauth.kakao.client-secret-enabled` | boolean | `true` |
| `oauth.kakao.authorization-uri` | String URI | `https://kauth.kakao.com/oauth/authorize` |
| `oauth.kakao.token-uri` | String URI | `https://kauth.kakao.com/oauth/token` |
| `oauth.kakao.user-info-uri` | String URI | `https://kapi.kakao.com/v2/user/me` |
| `oauth.kakao.connect-timeout` | Duration | `2s` |
| `oauth.kakao.read-timeout` | Duration | `3s` |

Endpoint values must be absolute HTTP(S) URIs and timeout values must be
positive. With no externally supplied `oauth.kakao.*` property, the Kakao
provider bean is absent and foundation mode can boot. Supplying any
`oauth.kakao.*` property expresses intent to enable Kakao, so incomplete or
invalid configuration fails fast rather than silently disabling the provider.
When Client Secret mode is enabled, the secret is supplied externally and is
sent only during the authorization-code exchange; no credential default is
embedded or committed.

The implemented flow is ordinary Kakao Login: non-OIDC, with PKCE off and no
nonce. It does not require an `id_token`, JWKS, issuer validation, or audience
validation in this slice. The authorization request uses the provider's
`client_id`, `redirect_uri`, `response_type=code`, and server-side `state`.

The positive Kakao service user ID is the provider subject. Email and profile
attributes are not account-linking evidence, and the flow does not implicitly
link by email or create an account. If the external identity is not linked,
the exchange returns HTTP `409 OAUTH_ACCOUNT_NOT_LINKED` and leaves onboarding
or linking as a separate product decision.

The accepted live smoke exercised the real Kakao authorization callback,
`/oauth/token`, Client Secret authentication, `/v2/user/me`, and positive
provider-subject resolution. A clean, unlinked local identity returned the
expected HTTP `409 OAUTH_ACCOUNT_NOT_LINKED` without implicit account creation
or linking.

## Local topology

`docker-compose.yml` defines the two application processes, one PostgreSQL
physical instance, and one Redis physical instance. It requires
`PLATFORM_POSTGRES_PASSWORD` and the existing Gateway upstream/security values.

```sh
PLATFORM_POSTGRES_PASSWORD='local-only' \
PLATFORM_GATEWAY_CARELOG_BE_URI='http://carelog-be:8080' \
PLATFORM_GATEWAY_CARELOG_RAG_URI='http://carelog-rag:8000' \
PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET='local-only' \
PLATFORM_GATEWAY_INTERNAL_SECRET='local-only' \
docker compose up --build gateway-app platform-core-app
```

`gateway-app/Dockerfile` and `platform-core/app/Dockerfile` are separate image
targets. Pin or roll back `PLATFORM_GATEWAY_IMAGE` and `PLATFORM_CORE_IMAGE`
independently.

PostgreSQL and Redis remain infrastructure services in this topology. Identity
uses them through its own schema, migrations, persistence adapters, and token
blacklist adapter, while Carelog User/Profile, Customer, Manager,
Organization/Workspace authorization, Product enrollment, and Carelog business
authorization remain outside this module. `PlatformAccount` existence is
distinct from authentication success, Carelog profile/claims existence, and
Carelog authorization success.

## Scope boundary

The Shared AI foundation is not Production AI or Production RAG. It does not
implement a real provider, corpus ingestion, vector lifecycle, Product Tool
integration, production authorization, Finance/Carelog behavior, Commerce, or
Audit. The internal HTTP execution route exists only in the integration-test
boundary; normal runtime does not expose the unauthenticated fixture flow.

Identity's current auth boundary includes the extracted password-authentication
and token-lifecycle capabilities plus the Kakao OAuth flow described above.
Other OAuth providers, OIDC, PKCE runtime behavior, and provider-specific
application flows outside Kakao remain outside this slice. Identity account
registration persistence is not Carelog user/profile enrollment or
authorization. Carelog full production cutover and legacy-auth removal,
RPL-55 migration completion, Finance production consumer integration and
cross-repository E2E, and RPL-107 Bedrock/provider/streaming functionality
remain separate follow-up work.
