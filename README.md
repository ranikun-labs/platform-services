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
`oauth:state:<state>`. The accepted Kakao provider adapter and Auth/OAuth HTTP
compatibility layer are hosted by the Platform Core Spring MVC app. The
compatibility endpoints are available under `/api/v1/auth` when
`platform.identity.auth.enabled=true`; Platform Core hosted Identity
composition is covered by runtime verification. Carelog traffic cutover,
production Product claims integration, and Product enrollment remain outside
this module. Live Kakao smoke is `LIVE_KAKAO_SMOKE_DEFERRED_TO_RPL55`, and no
Carelog traffic has been cut over to it. Identity auth composition is opt-in:
`platform.identity.auth.enabled=false` remains foundation mode without the
Identity Auth runtime. Identity does not have an independent deployable
process in this repository state. Shared AI is a Kotlin library module hosted
by the app, not another server. After the auth cutover, Shared Identity owns
the complete public `/api/v1/auth/**` namespace. Product/Carelog must not add
public product endpoints there; unknown auth requests are allowed to reach
Platform Identity and must never fall through to Carelog.

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
fail startup when absent. When Identity Auth is enabled, the host binds the
existing `jwt.*`, `carelog.auth.oauth.kakao.*`, and
`oauth.redirect-uris.kakao.*` property families from environment variables;
JWT credentials and OAuth secrets have no checked-in defaults.

Run the artifacts independently:

```sh
java -jar gateway-app/build/libs/gateway-app-*.jar
java -jar platform-core/app/build/libs/platform-core-app-*.jar
```

Readiness endpoints are `GET /actuator/health/readiness` on each process.
Readiness proves only that the corresponding host can receive requests; it does
not claim Product reachability or database/Redis business connectivity.

## Local topology

`docker-compose.yml` defines the two application processes, one PostgreSQL
physical instance, and one Redis physical instance. It requires
`PLATFORM_POSTGRES_PASSWORD` and the existing Gateway upstream/security values.
The source merge remains DARK by default: when `PLATFORM_GATEWAY_AUTH_URI` is
unset, it follows `PLATFORM_GATEWAY_CARELOG_BE_URI` by construction. Platform
Identity traffic promotion is an explicit runtime configuration action after
live verification: set `PLATFORM_GATEWAY_AUTH_URI` to the Platform Core
address, set `PLATFORM_IDENTITY_AUTH_ENABLED=true`, and provide the complete
JWT/Kakao runtime configuration. Rollback is also a no-rebuild change: either
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

Before promotion, the live compatibility gates must pass: Platform login and
token use, refresh, shared-blacklist logout, Kakao OAuth, public internal
claims returning 403, and the direct Platform-to-Carelog claims call. The
Carelog-to-Platform switch and rollback use the same compiled artifacts; only
the upstream and opt-in runtime configuration change.

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
