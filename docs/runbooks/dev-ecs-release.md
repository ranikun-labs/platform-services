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
