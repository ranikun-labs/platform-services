# Ranikun Labs Shared Platform G1

This repository contains the G1 runtime foundation scaffold for the
Gateway/Identity physicalization direction recorded in ADR-0017 / DEC-067 at
canonical Foundation main reference
`69fceb267094756913f400f8b4045590bd0e8ac5`. It provides two independently
buildable and deployable Spring Boot processes:

- `gateway-app`: Spring Cloud Gateway / WebFlux public ingress on port `8080`.
- `platform-core/identity`: Spring MVC Identity runtime host on port `8081`.

The code in this G1 slice intentionally contains no authentication business
logic, JWT contract, OAuth migration, schema migration, or traffic cutover.

## Independent build and run

The Gradle wrapper is the only build entry point required by this repository.

```sh
./gradlew -p gateway-app clean test bootJar
./gradlew -p platform-core/identity clean test bootJar
```

Each module also has its own `settings.gradle.kts`, so its project directory is
an independent build boundary. The root build is only an aggregate convenience;
it does not create a shared runtime or shared process.

```sh
./gradlew clean test
java -jar gateway-app/build/libs/gateway-app-*.jar
java -jar platform-core/identity/build/libs/identity-*.jar
```

## Runtime configuration

Gateway configuration is exclusively under `PLATFORM_GATEWAY_*`. Identity
configuration is exclusively under `PLATFORM_IDENTITY_*`. Required values have
no application defaults and fail startup when absent.

G1 is an inert runtime foundation: `gateway-app` exposes no executable Product
route. `/api/carelog/**`, `/api/identity/**`, and `/api/finance/**` belong to
RPL-53/G2 and are not implemented here.

Readiness endpoints are:

- `GET /actuator/health/readiness` on each process.
- `GET /actuator/health/liveness` on each process.

Gateway does not validate JWTs, create auth context, or implement product
authorization in G1. Those contracts remain follow-up work and are not defined
by this scaffold.

## Local topology

`docker-compose.yml` defines two independent application services, one
PostgreSQL physical instance, and one Redis physical instance. The Compose
file requires `PLATFORM_POSTGRES_PASSWORD` and never stores a secret in the
repository.

```sh
PLATFORM_POSTGRES_PASSWORD='local-only' \
docker compose config
PLATFORM_POSTGRES_PASSWORD='local-only' \
docker compose up --build gateway-app identity
```

`PLATFORM_GATEWAY_PORT` and `PLATFORM_IDENTITY_PORT` are the application and
container listening ports. `PLATFORM_GATEWAY_PUBLISHED_PORT` and
`PLATFORM_IDENTITY_PUBLISHED_PORT` are host-side published ports; Compose maps
each published port to the corresponding configured container port.

PostgreSQL and Redis are infrastructure topology only in G1. Identity has a
typed datasource boundary but no JDBC client, schema, migration, or table
access yet. Gateway and Identity keep separate Redis configuration namespaces
(`platform:gateway:` and `platform:identity:`); no Redis client, business key
contract, or data ownership activation is implemented.

## Deployment and rollback units

`gateway-app/Dockerfile` and `platform-core/identity/Dockerfile` produce
separate image targets. Pin or roll back `PLATFORM_GATEWAY_IMAGE` and
`PLATFORM_IDENTITY_IMAGE` independently. The Compose file does not imply a
traffic cutover or make Carelog an in-repository service. Carelog must be
provided later through the G2 Gateway extraction; it is not wired in G1.

Application readiness is process readiness: it proves that the Spring Boot
process serves its readiness endpoint. It does not claim Carelog reachability
or PostgreSQL/Redis business connectivity; those clients are intentionally not
implemented in G1.

## Deliberate follow-up blockers

The following are not decided or implemented by G1: JWT issuer/audience/JWKS,
signing-key ownership, session/token handoff, Identity schema and migration
ownership, trusted Gateway auth-context contract, and the Carelog extraction
cutover/rollback procedure. No G2 API or data contract is inferred here.
