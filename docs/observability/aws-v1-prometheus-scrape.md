# AWS V1 Gateway/Core Prometheus scrape contract

## Application endpoints

Gateway serves Actuator on its separate management listener at port `9091` by default. Platform Core uses port `9092`. Both expose `/actuator/prometheus`, health, and info on that listener; `PLATFORM_GATEWAY_MANAGEMENT_PORT` and `PLATFORM_CORE_MANAGEMENT_PORT` may override the container ports. The normal application ports remain unchanged.

Do not publish either management port on a public or VPC-facing host interface, and do not add it to an ingress or security-group allowlist. The application containers keep the listener on their private bridge network without a host port mapping. A request to `/actuator/prometheus` on the normal application port must not return metrics.

## Host-local Alloy target

Run one Alloy agent on each EC2 host. On the Gateway host, discover only the local `gateway-app` container; on the Core host, discover only `platform-core-app`. Resolve the container's private bridge address from local Docker metadata and scrape `http://<container-address>:9091/actuator/prometheus` for Gateway or `http://<container-address>:9092/actuator/prometheus` for Core. Do not scrape a peer EC2's management port. Attach the EC2 host identity as the bounded Prometheus `instance` target label; the application supplies `service` and `environment`.

Compose/Docker wiring is intentionally unchanged here because the Valkey PR is still open. The local Docker Compose health checks currently call readiness on the application ports; after these separate management listeners are enabled, the Compose health-check command must target the container-local management port. Keep that health-check request inside the container and do not publish the management port to make it work.

## Labels and HTTP latency

The application adds fixed `service` values (`gateway`, `platform-core`) and restricts `environment` to `local`, `test`, `dev`, `staging`, or `prod`; any other input becomes `unknown`. Alloy adds a bounded host-level `instance` label. Request URI/path, URL/query, exception, user/account, tenant, JWT/token, and authorization tags are removed before meters are exported. Do not add identity, request, SQL text, or raw URL labels in Alloy relabeling.

The Boot-managed `http.server.requests` timer keeps request count and duration. Its Prometheus histogram includes configured boundaries at 20 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2 s, 5 s, 10 s, 30 s, 60 s, 120 s, and 300 s. Derive p50/p95/p99 from these bucket samples with Prometheus `histogram_quantile`; they are not precomputed per-process quantiles.

`platform_http_server_errors_total` counts HTTP 5xx responses and non-timeout request failures. `platform_http_server_timeouts_total` counts a failure only when its exception-cause chain contains a typed timeout (Java timeout/socket timeout or the supported Netty timeout types). A 504 status alone is not evidence of an actual timeout. Both counters use only the fixed service/environment tags.

## JVM, Hikari, and JDBC scope

The Boot-managed Prometheus registry exports JVM memory, GC, thread, and process meters. When the Core Identity datasource is enabled, Boot's Hikari binder exports the live pool gauges; validate them against a started Core pool before treating them as measured values.

Core statement-duration instrumentation remains **UNKNOWN**. The JDBC code inspected for this change uses `DriverManager` and direct `Connection`/`PreparedStatement` calls; it has no existing `JdbcTemplate` observation path. Those calls do not acquire statement-duration observations from adding the Prometheus registry. No datasource wrapper, statement proxy, or SQL interceptor was introduced. Revisit SQL duration only after a supported and safe observation boundary is available.

## Verification and deployment boundary

Validate real Prometheus responses from both management listeners, histogram buckets, bounded labels, management-port isolation, and the actual Core Hikari pool sample. AWS resources, Grafana Cloud, and production deployment are outside this change.
