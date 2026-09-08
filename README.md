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
registration persistence, and password updates. Identity also owns
provider-neutral OAuth Core/State: authorization/callback orchestration, PKCE,
server-side state lifecycle, and the Redis OAuth state store using
`oauth:state:<state>`. The provider-neutral core has PKCE and nonce extension
points; the current Kakao adapter uses ordinary non-OIDC REST Login with both
disabled. The Kakao provider adapter and Auth/OAuth HTTP compatibility layer
are hosted by the Platform Core Spring MVC app. The
compatibility endpoints are available under `/api/v1/auth` when
`platform.identity.auth.enabled=true`; Platform Core hosted Identity
composition is covered by runtime verification. Carelog traffic cutover,
production Product claims integration, and Product enrollment remain outside
this module. A real-provider local Kakao live smoke has been verified at the
approved implementation state; that evidence does not represent production
deployment or Carelog cutover. Identity auth composition is opt-in:
`platform.identity.auth.enabled=false` remains foundation mode without the
Identity Auth runtime. Identity does not have an independent deployable
process in this repository state. Shared AI is a Kotlin library module hosted
by the app, not another server. The Identity HTTP adapter owns its current
Auth/OAuth compatibility endpoints; routing Gateway traffic to Platform Core
is a separate explicit runtime promotion decision, and Product/Carelog must
not add product endpoints to the Identity-owned auth surface.

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
`PLATFORM_IDENTITY_*`. Identity Auth is opt-in. When
`platform.identity.auth.enabled` is absent or `false`, foundation mode can
boot without JWT or Kakao Auth configuration. When it is `true`, the host
binds the JWT and Identity Auth configuration and validates the required
runtime values; missing or invalid JWT settings fail startup, token validity
values must be positive, and no usable JWT secret or OAuth credential is
checked in.

The current Spring property contract is:

```text
jwt.secret-key
jwt.access-token-validity
jwt.refresh-token-validity

carelog.auth.oauth.kakao.client-id
carelog.auth.oauth.kakao.client-secret
carelog.auth.oauth.kakao.authorization-uri
carelog.auth.oauth.kakao.token-uri
carelog.auth.oauth.kakao.user-info-uri
carelog.auth.oauth.kakao.connect-timeout   # default 2s
carelog.auth.oauth.kakao.read-timeout      # default 3s

oauth.redirect-uris.kakao.WEB
oauth.redirect-uris.kakao.MOBILE
oauth.allowed-return-origins
```

Platform Core's `application.yml` maps the JWT values to
`PLATFORM_IDENTITY_JWT_SECRET_KEY`,
`PLATFORM_IDENTITY_JWT_ACCESS_TOKEN_VALIDITY`, and
`PLATFORM_IDENTITY_JWT_REFRESH_TOKEN_VALIDITY`. It maps the Kakao provider
values to `PLATFORM_IDENTITY_KAKAO_CLIENT_ID`,
`PLATFORM_IDENTITY_KAKAO_CLIENT_SECRET`,
`PLATFORM_IDENTITY_KAKAO_AUTHORIZATION_URI`,
`PLATFORM_IDENTITY_KAKAO_TOKEN_URI`, and
`PLATFORM_IDENTITY_KAKAO_USER_INFO_URI`. WEB/MOBILE callbacks map to
`PLATFORM_IDENTITY_OAUTH_KAKAO_WEB_REDIRECT_URI` and
`PLATFORM_IDENTITY_OAUTH_KAKAO_MOBILE_REDIRECT_URI`; allowed return origins
map to `PLATFORM_IDENTITY_OAUTH_ALLOWED_RETURN_ORIGINS`. The current Shared
Identity configuration namespace is `carelog.auth.oauth.kakao.*`; no
`oauth.kakao.*` compatibility alias is provided at this implementation state.

Run the artifacts independently:

```sh
java -jar gateway-app/build/libs/gateway-app-*.jar
java -jar platform-core/app/build/libs/platform-core-app-*.jar
```

Readiness endpoints are `GET /actuator/health/readiness` on each process.
Readiness proves only that the corresponding host can receive requests; it does
not claim Product reachability or database/Redis business connectivity.

## Shared Identity Auth and Kakao Login

When Identity Auth is enabled, Platform Core hosts password login, JWT
access/refresh token handling, refresh-session persistence and rotation,
logout, and bearer-protected Identity endpoints. The same opt-in composition
hosts the current Auth/OAuth compatibility endpoints:

```text
POST /api/v1/auth/oauth/kakao/authorization
POST /api/v1/auth/oauth/kakao/exchange
```

Kakao uses the ordinary authorization-code REST Login flow. The current
adapter has PKCE off, OIDC off, and does not use a nonce, `id_token`, JWKS,
issuer, or audience validation. Client Secret transport is supported and the
secret is supplied externally when that mode is used; no credential default is
embedded in the repository. The provider subject is Kakao's positive service
user ID. Email, profile, and display attributes are not account-linking
evidence, and Identity does not perform implicit email-based linking or
implicit Platform account creation.

The WEB callback is configured through `oauth.redirect-uris.kakao.WEB`. The
approved local provider smoke used:

```text
http://localhost:5173/oauth/kakao/callback
```

This is a local verification callback example, not a fixed production URI.
The browser callback receives the provider code/state; it is not the Platform
exchange endpoint. The client integration then calls the Platform exchange
endpoint above with the callback result.

If the provider principal is valid but no external identity is linked to a
Platform account, the current HTTP contract returns HTTP `409` with the
message `연결된 Carelog 계정이 없습니다.`. The response does not contain an
`OAUTH_ACCOUNT_NOT_LINKED` code field. This is an unlinked-account/onboarding
boundary and does not create or link an account implicitly.

The RPL-54 real-provider local live smoke verified the Kakao authorization and
callback round trip, real Kakao token exchange, Client Secret authentication,
real user-info retrieval, and positive provider-subject resolution. It ended
with the expected clean-database HTTP `409` unlinked-account result. This is
local provider evidence only; it does not claim production deployment,
Carelog cutover, or completion of downstream consumer migration.

## Local topology

`docker-compose.yml` defines the two application processes, one PostgreSQL
physical instance, and one Redis physical instance. It requires
`PLATFORM_POSTGRES_PASSWORD` and the existing Gateway upstream/security values.
The source merge remains DARK by default: when `PLATFORM_GATEWAY_AUTH_URI` is
unset, it follows `PLATFORM_GATEWAY_CARELOG_BE_URI` by construction. Platform
Identity traffic promotion is separate from the RPL-54 implementation and
live-provider evidence; it is an explicit runtime configuration action. Set
`PLATFORM_GATEWAY_AUTH_URI` to the Platform Core address, set
`PLATFORM_IDENTITY_AUTH_ENABLED=true`, and provide the complete JWT/Kakao
runtime configuration. Rollback is also a no-rebuild change: either
unset `PLATFORM_GATEWAY_AUTH_URI` so it follows `PLATFORM_GATEWAY_CARELOG_BE_URI`
or explicitly set `PLATFORM_GATEWAY_AUTH_URI` back to the Carelog upstream.

```sh
PLATFORM_POSTGRES_PASSWORD='local-only' \
PLATFORM_GATEWAY_CARELOG_BE_URI='http://carelog-be:8080' \
PLATFORM_GATEWAY_CARELOG_RAG_URI='http://carelog-rag:8000' \
PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET='local-only' \
PLATFORM_GATEWAY_INTERNAL_SECRET='local-only' \
docker compose up --build gateway-app platform-core-app
```

Gateway promotion, Carelog traffic cutover, legacy Auth removal, and any
remaining consumer compatibility or Product claims gates are separate
operational decisions. The Carelog-to-Platform switch and rollback use the
same compiled artifacts; only the upstream and opt-in runtime configuration
change.

`gateway-app/Dockerfile` and `platform-core/app/Dockerfile` are separate image
targets. Pin or roll back `PLATFORM_GATEWAY_IMAGE` and `PLATFORM_CORE_IMAGE`
independently.

PostgreSQL and Redis remain infrastructure services in this topology. Identity
uses them through its own schema, migrations, persistence adapters, token
blacklist adapter, and OAuth state store, while Carelog User/Profile, Customer,
Manager, Organization/Workspace authorization, Product enrollment, and Carelog
business authorization remain outside this module. `PlatformAccount` existence
is distinct from authentication success, Carelog profile/claims existence, and
Carelog authorization success.

## Scope boundary

The Shared AI foundation is not Production AI or Production RAG. It does not
implement a real provider, corpus ingestion, vector lifecycle, Product Tool
integration, production authorization, Finance/Carelog behavior, Commerce, or
Audit. The internal HTTP execution route exists only in the integration-test
boundary; normal runtime does not expose the unauthenticated fixture flow.
