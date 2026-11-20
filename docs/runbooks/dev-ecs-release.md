# Platform services DEV release candidate

The manual **Platform services DEV release** workflow selects Gateway or Core on
`main`. It checks out the dispatch SHA and runs the selected service's Gradle
tests before calling the shared DEV release workflow with that SHA. Gateway's
canonical image uses `gateway-app` as its context; Core's uses `platform-core`.
The deploy option defaults to false, so the default action is publish only.
Deploy requires explicitly selecting it and the service-specific protected DEV
environment configured by the shared workflow.

This caller is a preparation candidate, not an enabled release path. It depends
on `ranikun-labs/platform-infra/.github/workflows/dev-ecs-release.yml` being
published and reviewed. The caller is pinned to `fdeb22805d9f26779073dc68c6f0a9a166ccb884`; publish and retain that exact commit before enabling releases. Configure and verify the service-specific GitHub
environments, AWS OIDC trust, ECR repositories, and deploy roles described by
the shared workflow before use. No production or automatic deployment is
provided here. A successful local static check does not establish that GitHub,
AWS, deployment, or runtime smoke tests work.

## Redis TLS configuration

For a TLS-enabled managed Valkey endpoint, set
`PLATFORM_GATEWAY_REDIS_TLS_ENABLED=true` on Gateway and
`PLATFORM_IDENTITY_REDIS_TLS_ENABLED=true` on Core Identity, alongside their
existing host and port settings. Both switches default to `false` for existing
installations. TLS uses the clients' standard certificate and hostname
verification; the endpoint must present a certificate trusted by the JVM and
matching its configured host. Managed AWS Valkey connectivity, trust chain,
and application behavior remain to be validated in DEV.

## Redis Cluster configuration

For a Redis Cluster deployment, set `PLATFORM_GATEWAY_REDIS_CLUSTER_NODES` and
`PLATFORM_IDENTITY_REDIS_CLUSTER_NODES` to comma-separated bootstrap addresses,
for example `node-a:6379,node-b:6379`. Set both TLS switches above for an
encrypted cluster. The existing host and port settings remain required for
configuration compatibility. Without cluster nodes each service keeps its
standalone connection path. Every advertised cluster node must be reachable
from the service and present a JVM-trusted certificate matching its advertised
hostname. Test discovery, redirects, GETDEL, blacklist, and rate limiting
against the actual managed cluster before enabling this configuration.

Gateway and Identity must use the same logical cluster for the shared token
blacklist. For password or ACL authentication, inject the Identity secret as
`PLATFORM_IDENTITY_REDIS_PASSWORD` and optionally the ACL username as
`PLATFORM_IDENTITY_REDIS_USERNAME`. Inject the Gateway credentials as Spring
Boot's `SPRING_DATA_REDIS_PASSWORD` and optional `SPRING_DATA_REDIS_USERNAME`.
Use ECS secret references, never literal credentials in task definitions, logs,
or source control. A username requires a nonblank password; an explicitly
blank Identity credential fails startup. A password without a username uses the
default user. When both Identity values are absent, the existing unauthenticated
path is preserved. IAM authentication and automatic token rotation are not
supported by Identity. Credential changes require a controlled task restart.

The cache's eviction and failover behavior is a separate security requirement:
a missing blacklist key can allow an unexpired revoked token through Gateway.
Validate the approved durability and eviction policy for the workload.

The opt-in TLS cluster integration tests use `REDIS_CLUSTER_TLS_TEST_NODES`
and the JVM trust store. For an ACL-protected fixture, also set
`REDIS_CLUSTER_TLS_TEST_USERNAME` and `REDIS_CLUSTER_TLS_TEST_PASSWORD` for
Core, and `SPRING_DATA_REDIS_USERNAME` and `SPRING_DATA_REDIS_PASSWORD` for
Gateway. Use disposable test credentials only. Run `:platform-core:identity:test --tests
'*IdentityRedisConfigurationTest'` and `:gateway-app:test --tests
'*GatewayRedisClusterBootTest'` with a disposable, trusted cluster; without the
variable their network-dependent cases are skipped.
