# Ranikun Labs Shared Platform

This repository contains two independently buildable and deployable runtime
boundaries:

- `gateway-app`: the Spring Cloud Gateway / WebFlux public-ingress process on
  port `8080`.
- `platform-core`: the Spring MVC modular-monolith process on port `8081`.

Platform Core currently hosts one logical module, `identity`. Identity remains
an inert foundation: it owns no Auth/OAuth behavior, persistence, schema,
migration, or independent deployable process in this repository state.

## Build structure

The root build is an aggregate-only Gradle composite. Gateway and Platform Core
each own their plugin and dependency baseline, so Platform Core can evolve
without forcing a Spring Cloud migration on Gateway.

```text
platform-services/
├── gateway-app/                 independent Gradle build and WebFlux runtime
└── platform-core/               independent multi-project Gradle build
    ├── app/                     only executable Spring MVC host
    └── identity/                Java library/logical module
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

The Platform Core dependency direction is `app -> identity`. The
`verifyModuleBoundaries` task, included in Platform Core `check`, verifies that
the app owns the Spring Boot plugin, Identity does not, and the feature-module
project dependencies remain acyclic.

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

PostgreSQL and Redis remain infrastructure topology only. Identity has typed
configuration boundaries but no client, schema, migration, table access, or
business key contract yet.

## Scope boundary

RPL-105 changes only the Platform Core structure and build boundary. They do
not add or change Gateway routing/security behavior, and they do not implement
Carelog Auth/OAuth extraction, Identity business behavior, Shared AI, Spring
AI, RAG, Commerce, Audit, or Product cutover. Those remain separately governed
follow-up work.
