# Issue #24: Gateway-to-Identity E2E

Run the isolated end-to-end trace from the repository root:

```sh
./scripts/test-gateway-identity-e2e.sh
```

The script creates a unique Compose project on an internal-only Docker network.
PostgreSQL has no persistent volume, Valkey runs without persistence, and the
script removes the project and containers on exit. The only OAuth endpoint is a
local synthetic Kakao-compatible fixture; the network has no external route.
The fixture account, password, JWT signing key, Product claims, and provider
credentials are synthetic test values. No production or Kakao credentials are
used.
The test Gateway uses a two-second Redis command timeout so the outage phase
can assert its existing fail-closed 503 contract within a bounded run. The
Identity Redis adapter uses Lettuce's existing command timeout, so the outage
phase allows 75 seconds for its response. The PostgreSQL outage phase allows 75
seconds for the existing JDBC and connection-pool failure handling.

PostgreSQL initializes an empty `identity` schema for the disposable database;
the current Core build then applies its checked-in Identity migrations before
the fixture account is seeded.

The Compose build uses test-only Dockerfiles to build the Gateway and Core
artifacts from the selected source commit. They mount the session CA only during
Gradle dependency resolution and remove temporary trust settings and dependency
caches before the builder stage ends. Production Dockerfiles remain unchanged.
For a constrained environment with these exact source-built images already
available, `PLATFORM_E2E_SKIP_BUILD=1` skips that rebuild; the default always
builds both images from the checkout.

The Python runner emits one redacted JSON result per phase and writes it under
`docs/testing/evidence/`. Results contain check names, status codes, aggregate
counts, and UTC timestamps; they never include tokens, state values, request
bodies, password values, provider credentials, or database contents.

## Cross-container checks

- Gateway routes password login, refresh, logout, and Kakao OAuth calls to the
  Platform Core Identity HTTP adapter.
- Valid access tokens reach Identity; expired, tampered, and revoked access
  tokens are rejected at Gateway. Unauthenticated Gateway traffic returns 401,
  and the internal namespace returns 403.
- Refresh rotates both tokens, rejects reuse of the old refresh token, and a
  simultaneous refresh race has exactly one successful consumer.
- OAuth authorization returns its public URL without exposing internal state or
  verifier fields. The state key has its configured TTL; two simultaneous
  callbacks produce one fake-provider result and one invalid-state response.
  Valkey command statistics must show `GETDEL` use.
- OAuth authorization bursts exercise the Gateway Redis Lua limiter against
  Valkey; command statistics must show `EVAL` or `EVALSHA` use.
- Stopping Valkey produces fail-closed 503 responses from the Gateway limiter
  and Identity state store. Stopping PostgreSQL produces the existing sanitized
  Identity 500 response.

## Existing coverage reused

The suite complements rather than replaces the current tests:

- Gateway route/security tests: `GatewayRoutingHttpIntegrationTest`,
  `JwtUuidHttpIntegrationTest`, `JwtVerifierCharacterizationTest`, and
  `OAuthRedisDownHttpIntegrationTest`.
- Valkey rate-limit contract: `RedisRateLimiterValkeyIntegrationTest`.
- Identity host, migration, refresh, blacklist, and OAuth HTTP paths:
  `PlatformCoreIdentityPersistenceBootIntegrationTest`.
- Refresh race, OAuth state integration, and provider retry:
  `IdentityPersistenceRepositoryTest`, `RedisOAuthStateStoreIntegrationTest`,
  and `KakaoOAuthApiClientRetryTest`.

The E2E additions do not change authentication behavior or edit the Identity
migrations, Core JDBC code, or Gateway Prometheus GC test.
