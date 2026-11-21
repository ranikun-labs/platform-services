# Carelog registration Gateway

The public registration route is disabled by default. Enable
`PLATFORM_GATEWAY_CARELOG_REGISTRATION_ENABLED` only after the Identity registration
endpoint and the Carelog enrollment contract are deployed and verified.

Configure `platform.gateway.carelog-registration-allowed-origins` with exact
HTTP(S) origins of the Carelog frontend. The list is empty by default and wildcard,
path, query, fragment and user-info values are rejected. The preflight allows POST
with Content-Type and Idempotency-Key. The operation key is passed to Identity.

The per-client limiter uses the existing trusted-proxy hop count. Enable the route
only behind a trusted ingress that removes or overwrites client-supplied
X-Forwarded-For and supplies the verified client address. Confirm
`PLATFORM_GATEWAY_OAUTH_RATE_LIMIT_TRUSTED_PROXY_HOPS` matches the deployed chain.
Direct access around that ingress would allow IP spoofing and must be blocked.
Redis failure denies registration with a 503. The initial rate limit of two
requests per second with a burst of four is a DEV starting value and must be
validated against real registration and retry behavior.
