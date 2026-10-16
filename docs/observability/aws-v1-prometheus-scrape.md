# AWS V1 Gateway/Core Prometheus scrape contract

## Application endpoints

Gateway serves Actuator on its separate management listener at port `9091` by default. Platform Core uses port `9092`. Both expose `/actuator/prometheus`, health, and info on that listener; `PLATFORM_GATEWAY_MANAGEMENT_PORT` and `PLATFORM_CORE_MANAGEMENT_PORT` may override the container ports. The normal application ports remain unchanged.

Do not publish either management port on a public or VPC-facing host interface, and do not add it to an ingress or security-group allowlist. The application containers keep the listener on their private bridge network without a host port mapping. A request to `/actuator/prometheus` on the normal application port must not return metrics.

## Host-local Alloy target

Run one Alloy agent on each EC2 host. On the Gateway host, discover only the local `gateway-app` container; on the Core host, discover only `platform-core-app`. Resolve the container's private bridge address from local Docker metadata and scrape `http://<container-address>:9091/actuator/prometheus` for Gateway or `http://<container-address>:9092/actuator/prometheus` for Core. Do not scrape a peer EC2's management port. Attach the EC2 host identity as the bounded Prometheus `instance` target label; the application supplies `service` and `environment`.

Docker Compose passes the same `PLATFORM_GATEWAY_MANAGEMENT_PORT` and `PLATFORM_CORE_MANAGEMENT_PORT` values used by each application, defaulting to `9091` and `9092`. Its container health checks call readiness on those internal management listeners. The Compose `ports` entries publish only the application ports; do not publish management ports to the host. PR #27's Valkey service and persistence changes remain independent of this health-check wiring.

## Labels and HTTP latency

The application adds fixed `service` values (`gateway`, `platform-core`) and restricts `environment` to `local`, `test`, `dev`, `staging`, or `prod`; any other input becomes `unknown`. Alloy adds a bounded host-level `instance` label. Request URI/path, URL/query, exception, user/account, tenant, JWT/token, and authorization tags are removed before meters are exported. Do not add identity, request, SQL text, or raw URL labels in Alloy relabeling.

The Boot-managed `http.server.requests` timer keeps request count and duration. Its Prometheus histogram includes configured boundaries at 20 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2 s, 5 s, 10 s, 30 s, 60 s, 120 s, and 300 s. Derive p50/p95/p99 from these bucket samples with Prometheus `histogram_quantile`; they are not precomputed per-process quantiles.

`platform_http_server_errors_total` counts non-timeout request failures observed by the service filter and 5xx responses that are not classified as an actual timeout. `platform_http_server_timeouts_total` counts a request when its exception-cause chain contains a typed timeout (Java `TimeoutException`, `SocketTimeoutException`, `HttpTimeoutException`, or the supported Netty timeout types), or when the Servlet async lifecycle reports a timeout. A 504 status alone is not evidence of an actual timeout. A real timeout 5xx can therefore be absent from `platform_http_server_errors_total`; use the Boot HTTP status meter for the overall 5xx rate:

```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
```

Both platform counters use only the fixed service/environment tags and count each request once.

## JVM, Hikari, and JDBC duration

The Boot-managed Prometheus registry exports JVM memory, GC, thread, and process meters. The Core Identity pool is an `InstrumentedHikariDataSource`, a Hikari subtype, so Boot's Hikari binder continues to publish live pool gauges. The wrapper sits on connections borrowed from that pool; Hikari still owns connection state, transaction reset, close, and eviction.

The Core exports `ranikun_db_query_duration_seconds_bucket`, `_count`, and `_sum`. Timers cover the duration of `Statement.execute`, `executeQuery`, `executeUpdate`, `executeLargeUpdate`, `executeBatch`, and `executeLargeBatch` calls, plus `DatabaseMetaData` methods that return a `ResultSet`. Metadata timing includes any driver work performed inside that call and stops before the application consumes result rows. Preparation, result iteration, commit, and rollback have no SQL duration timer.

The only JDBC-specific labels are bounded `operation` values (`query`, `update`, `batch`, `execute`, `metadata`) and `outcome` values (`success`, `error`). Common labels remain the fixed `service` and allowlisted `environment`; Alloy adds the EC2 `instance` target label. SQL text, parameters, SQLSTATE, driver exception text, URL, account, user, and tenant data are never read into a meter tag.

Connection, Statement, PreparedStatement, CallableStatement, ResultSet, and DatabaseMetaData handles are wrapped. Their standard `unwrap` requests return the observing proxy, and `isWrapperFor` reports only types the proxy itself exposes; a request for a raw driver implementation is rejected to prevent JDBC execution from escaping the timer boundary. `equals` and `hashCode` use proxy identity. Statement and result-set close behavior delegates to Hikari/the JDBC driver, statement close-on-completion is honored, and proxy handle caches are cleared when their statements or connections close. `SQLException` instances pass through unchanged, including SQLSTATE.

The offline source-structure inspector uses a separate `DriverManager` connection and runs outside the hosted Core datasource path. It is not a Core service persistence route and does not emit this application's runtime metric. Production Core JPA, Flyway, direct JDBC, and JDBC metadata calls use the instrumented Hikari bean.

Micrometer publishes the native histogram with configured service-level buckets at 1 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2.5 s, 5 s, 10 s, 30 s, and 60 s. Derive p50/p95/p99 from these buckets with Prometheus `histogram_quantile`; the app does not publish client-side quantiles.

For example, a service-wide p95 query latency by bounded operation is:

```promql
histogram_quantile(
  0.95,
  sum by (le, operation) (
    rate(ranikun_db_query_duration_seconds_bucket{service="platform-core"}[5m])
  )
)
```

## Verification and deployment boundary

Validate real Prometheus responses from both management listeners, histogram buckets, bounded labels, management-port isolation, and the actual Core Hikari pool sample. AWS resources, Grafana Cloud, and production deployment are outside this change.
