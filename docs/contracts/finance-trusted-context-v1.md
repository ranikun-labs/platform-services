# Finance trusted context v1 reference

This is a non-production Gateway-to-Finance fixture reference for RPL-51/RPL-34, based on the scoped owner record and D1 candidate SHA-256 `3b4138cd0cc57fa56f8607be3fafc4255e5dc70f7c311beb424fa739545e6326`. It does not define an accepted production authentication relationship.

The Gateway uses only the opaque `sub` returned by the existing `JwtVerifier` after signature verification and a successful Redis blacklist check. On the Finance reference path, it also requires the current Core access-claim shape: `organizationId` and `publicId` must be UUID strings, and `role` must be a nonblank string. These three claims are checked only to reject the current and legacy Core refresh-token shapes; their values do not supply a Finance identity, are not forwarded, and are not used for Finance authorization. The `sub` remains opaque to preserve this D1 contract, so this is not the Core `validateAccessToken` implementation, which also requires a UUID subject. The fixture test uses a public dummy JWT key and mocked `blacklist=false`; it does not prove a real issuer, live account, or successful user login.

For the exact routes `GET /finance/journals`, `POST /finance/journals`, and `GET /finance/journals/{lowercase-canonical-UUID}`, the reference emits exactly one value for each of these headers:

| Header | Value |
| --- | --- |
| `X-Finance-Context-Version` | `1` |
| `X-Finance-Service-Id` | `gateway-app` |
| `X-Finance-Service-Secret` | Fixture-only public dummy from test resources |
| `X-Finance-Audience` | `finance-journal` |
| `X-Finance-Subject` | Stable JWT subject UTF-8 bytes encoded with canonical Base64URL without padding |

Before authentication, the Gateway removes every inbound `X-Finance-*`, existing Carelog identity/service headers, and spoofed gateway credentials. After JWT and blacklist success, only the verified-subject exchange attribute enters the fixture filter. The filter removes the raw bearer token, cookie, Carelog identity projection, and Carelog gateway credential before setting the five Finance headers. It does not call the Identity claims projection endpoint.

The subject is preserved as an opaque string: no trimming, case folding, UUID parsing, or Unicode normalization. The encoded input must be well-formed UTF-8, 1–256 bytes, non-whitespace, and contain no CR, LF, or NUL. Duplicate or comma-joined values are never copied from the request. The total UTF-8 byte length of the five outgoing values must not exceed 2,048 bytes.

The reference filter bean requires the explicit `finance-trusted-context-reference` profile, `platform.gateway.finance-trusted-context.enabled=true`, and a test proof value of at least 32 printable ASCII bytes without commas, matching the receiver's fixture configuration and its rejection of comma-joined values. Its test property file alone carries the public dummy proof and JWT key. Mixing the reference profile with `prod` or `production` profiles/markers, including whitespace-padded markers, fails startup. Main `application.yml` defaults the feature off, contains no Finance route or credential, and the default-context test proves the reference filter is absent. No Finance upstream route is installed by this reference.

Authentication rejection, including a token without the required Core access-claim shape, is 401. Redis `DataAccessException` during blacklist validation is 503 with `identity_verification_unavailable`; other unexpected failures remain errors. Wrong Finance route/method is rejected with 403. The existing Carelog empty-bearer and claim-forwarding behavior remains covered by its characterization tests.

The current and legacy Core refresh-token claim shapes are rejected by the marker check, but the reference does not independently validate issuer, audience, token type, refresh-session state, or live account state, and does not establish that production signing keys are compatible. The service proof is a static bearer credential in this fixture. It is not cryptographically bound to subject, path, method, body, or freshness and cannot detect replay after credential or post-TLS context exposure. This reference does not verify TLS, create or inject a production credential, establish a production receiver or route, accept the residual security risk, or activate the feature in production. A real Gateway/Finance deployment still needs a separate security and deployment decision.
