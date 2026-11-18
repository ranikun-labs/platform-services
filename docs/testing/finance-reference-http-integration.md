# Finance reference HTTP integration probe

This test boots the exact Gateway application and adds a test-only
`RouteLocator` for `/finance/**`. That route points to an in-process synthetic
upstream, not a Finance service. The default `application.yml` remains without
a Finance route or credential, as required by the non-production reference
contract.

The probe verifies the strict reference JWT path over real Gateway HTTP, the
Finance lifecycle route and method boundary, trusted subject/header creation,
removal of caller-supplied identity data, request body preservation, and
rejection before forwarding. It uses public dummy keys and a mocked
non-revoked blacklist. It does not verify Finance receiver behavior, a live
Identity issuer, PostgreSQL, Redis, a browser, production TLS, G4, or deployment.

Run from the repository root:

```sh
./gradlew --offline --no-daemon --console=plain :gateway-app:test \
  --tests com.ranikunlabs.platform.gateway.route.FinanceReferenceHttpIntegrationTest
```

Both Gateway and the synthetic upstream bind ephemeral local HTTP ports. The
test needs a runner that permits local TCP sockets; it makes no external
network calls.
