# DEV Redis → Valkey clean replacement

## Scope and approval

This runbook is for an explicitly identified, disposable DEV environment whose
owner has approved discarding its Redis data. It performs a clean replacement:
Redis keys, sessions, OAuth state, rate-limit counters, and blacklist records are
discarded. It does not migrate or reverse-migrate data.

Do not apply it to Production or a shared environment. A DEV label alone does not
prove that an endpoint is disposable. If an external user, shared token consumer,
or unlisted Redis client may be affected, stop and mark the reset **BLOCKED**.
Do not delete a service, volume, or keyspace while the environment identity or
consumer list is uncertain.

AWS V1 deployment policy is unchanged. Upstash Redis PAYG remains only an AWS V2
candidate; this runbook does not select or deploy it and makes no compatibility
or scale-to-zero claim. No query-data cache is introduced. Redis key contracts,
including raw JWT keys, remain unchanged.

## Redis clients and key contracts

Inventory the actual DEV deployment before stopping anything. Record the cloud
account/project, region, cluster or Compose project, endpoint, port, database,
volume identifiers, and every process that reads or writes the endpoint. Include
Gateway, Platform Core Identity when enabled, the selected Auth upstream, Carelog
services, workers, scheduled jobs, admin scripts, and external integrations. The
inventory must show that each client is DEV-only and will be stopped or redirected.

The repository currently defines these contracts:

| Client | Key contract | Operations |
| --- | --- | --- |
| Platform Identity blacklist writer | `blacklist:<raw JWT>` → `1` | Write with the token's remaining validity as TTL. |
| Gateway blacklist reader | Same `blacklist:<raw JWT>` key | Reject a request when the key exists. The raw JWT is not hashed. |
| Platform Identity OAuth state store | `oauth:state:<state>` | Store serialized state with TTL; consume atomically with `GETDEL`. A second consume must be empty. |
| Gateway OAuth rate limiter | `request_rate_limiter.{<routeId>.<clientKey>}.tokens` and `.timestamp` | Spring Cloud Gateway Lua script updates token-bucket state. |

In the local Compose definition, Gateway and Identity use host `redis`, port
`6379`, and the default Redis database `0` on the `platform` network. The
`valkey` service provides the `redis` network alias to preserve the existing
client DNS contract. No separate Redis server or host port is introduced. The
configured `platform:gateway:` and `platform:identity:` prefixes must remain
consistent with the deployed settings, but they do not change the fixed
blacklist, OAuth-state, or rate-limiter key contracts above. Verify the effective
values in the target environment; do not assume Compose defaults match it.

`PLATFORM_GATEWAY_AUTH_URI` may route authentication to Carelog or Platform Core.
Identify the active issuer and every JWT validator from effective configuration,
not from defaults. Carelog is outside this Compose project, so its clients and
Redis use must be inventoried separately.

## Entry gates

Before the reset, an operator and the DEV owner must verify and record all of the
following:

1. The endpoint, host, account, project/namespace, Redis database, Compose
   project, and volume IDs identify the approved disposable DEV environment.
2. No Production service, shared test environment, external user, or shared JWT
   consumer points to the endpoint. If any one of these checks is uncertain,
   stop and mark **BLOCKED**.
3. Every Redis writer and consumer is listed, including non-Compose clients. All
   writers can be paused and all consumers can be kept from receiving traffic.
4. The active JWT issuer, Gateway verifier, and any other verifier are identified.
   Confirm algorithm, issuer/audience rules, and key selection. If Platform Core
   Identity issues HS256 tokens, its
   `PLATFORM_IDENTITY_JWT_SECRET_KEY` and every accepting verifier, including
   `PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET`, must use the same key bytes. If
   Carelog or another service issues tokens, include that issuer and every
   validator in the same check.
5. A coordinated DEV-only signing-key rotation is prepared. The old key must be
   removed from every accepted validation path so tokens issued before the reset
   cannot be reused. Do not print or log secret values or JWTs. If a validator is
   external, shared, or cannot be updated consistently, do not reset; mark it
   **BLOCKED**.

Keep the public ingress and all Redis writers paused through the clean reset.
Outstanding OAuth callbacks will fail after their state is discarded and must
restart authorization.

## Clean replacement procedure

1. Capture the entry-gate evidence and exact DEV resource IDs. Confirm that no
   current resource name or endpoint resolves to Production or a shared project.
2. Pause Gateway, Platform Core Identity, the active Auth upstream, Carelog-side
   clients, workers, scheduled jobs, and every other inventoried writer or
   consumer. Disable automatic restarts or scaling that would reconnect them.
   Confirm that Redis writes and client connections have stopped.
3. Apply the new DEV signing key to the issuer and every validator as one
   coordinated configuration change. Keep public traffic fenced until all
   services report the new configuration and reject a previously issued DEV
   token. Never use this key change to affect shared or Production tokens.
4. Reconfirm the endpoint and the exact Redis container/volume IDs. Remove only
   the approved DEV Redis service and its DEV data volume. Do not target a
   default Compose project, wildcard volume name, or resource selected only by
   a generic `redis` label. Do not restore the old volume or copy keys into the
   new store.
5. Prepare the local Compose Valkey service with a newly created `/data` volume.
   Keep the service internal to the `platform` network with no published host
   port. Configure Gateway and Identity to the same Valkey host, port, database,
   and existing key contracts. Do not change AWS deployment policy or add a
   cache feature.
6. Check Valkey `PING`, AOF enabled, `appendfsync everysec`, AOF-only snapshot
   configuration, memory limit, and the attached new volume. `PING` proves only
   process responsiveness; it does not prove old data survived or that the
   blacklist is populated.
7. Start the issuer, validators, Gateway, Identity, and remaining DEV clients
   only after configuration points to the same new store and the old signing key
   is no longer accepted. Keep public ingress fenced until the checks below
   pass.

## Contract checks before traffic resumes

Run the repository's Valkey integration tests and a DEV-only application smoke
test. Use a synthetic account and tokens. Keep JWTs, OAuth state values, and raw
blacklist keys out of logs, shell history, and test output.

1. **OAuth State:** begin a DEV authorization flow. Confirm that a new
   `oauth:state:<state>` record has a positive TTL, that its first consume uses
   `GETDEL` and succeeds, and that a replay is rejected because the key is gone.
2. **Logout blacklist:** issue a new token using the rotated DEV key; call the
   DEV logout path; verify through a non-logging test harness that
   `blacklist:<raw JWT>` exists with value `1` and a positive TTL no greater than
   the token's remaining validity. Send the same token to a protected Gateway
   route and require `401`. Confirm that a token issued before the reset also
   receives `401`.
3. **OAuth rate limiter:** use the DEV OAuth authorization and exchange routes
   or the isolated integration test. Confirm the Gateway Lua script allows the
   configured burst, then denies excess requests, and fails closed on a Valkey
   error.
4. **Restart:** in an isolated test project, write a synthetic key, restart
   Valkey, and confirm it recovers. Forced-kill recovery belongs only to the
   isolated recovery script; never kill an existing DEV or Production service
   as part of that test.
5. Recheck Gateway and Identity effective host, port, database, and key prefix.
   Confirm all inventoried clients point to the same DEV Valkey store and that
   no old Redis endpoint remains active.

Resume public DEV traffic only after all checks pass. A failed check keeps
traffic fenced. For DEV recovery, correct the configuration and repeat the
clean initialization with a new DEV store and the same token-invalidation gates;
there is no data migration or reverse migration. Do not re-enable the old signing
key to make old tokens work.

## Durability and Production boundary

`appendfsync everysec` is a common latency/durability trade-off, not a hard
one-second loss guarantee. Fsync may be delayed; a host crash can lose recent
acknowledged writes; storage or volume failure can lose the complete AOF and
keyspace. The current health check can report a newly empty store as healthy.
After AOF or volume loss, that signal alone must never authorize resuming
protected traffic: an absent blacklist entry can make a revoked but otherwise
valid JWT usable again. Keep traffic fenced until an explicit recovery decision
and the required validation have passed.

Production Redis-to-Valkey migration, reverse rollback, preservation of live JWT
revocations, backup/replication requirements, and traffic recovery after storage
loss require a separate Production approval gate and operational design. None is
implemented by this DEV runbook.
