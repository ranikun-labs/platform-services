#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/../.."
REAL_API_E2E="${G4_CARELOG_REAL_API_E2E:-0}"
if [[ "$REAL_API_E2E" != "0" && "$REAL_API_E2E" != "1" ]]; then
  echo "BLOCKED_ENV: G4_CARELOG_REAL_API_E2E must be 0 or 1" >&2
  exit 2
fi
if [[ "$REAL_API_E2E" == "1" ]]; then
  for required in CARELOG_E2E_DISPOSABLE CARELOG_E2E_GATEWAY_PORT CARELOG_E2E_ACCOUNT_A CARELOG_E2E_PASSWORD_A CARELOG_E2E_FE_DIR; do
    if [[ -z "${!required:-}" ]]; then
      printf 'BLOCKED_ENV: %s is required for Carelog real API E2E\n' "$required" >&2
      exit 2
    fi
  done
  if [[ "$CARELOG_E2E_DISPOSABLE" != "1" ]]; then
    echo "BLOCKED_ENV: Carelog real API E2E requires CARELOG_E2E_DISPOSABLE=1" >&2
    exit 2
  fi
  if ! [[ "$CARELOG_E2E_GATEWAY_PORT" =~ ^[0-9]{1,5}$ ]]; then
    echo "BLOCKED_ENV: CARELOG_E2E_GATEWAY_PORT must be a TCP port" >&2
    exit 2
  fi
  gateway_port_number=$((10#$CARELOG_E2E_GATEWAY_PORT))
  if ((gateway_port_number < 1024 || gateway_port_number > 65535)); then
    echo "BLOCKED_ENV: CARELOG_E2E_GATEWAY_PORT is outside the disposable port range" >&2
    exit 2
  fi
  if [[ ! -d "$CARELOG_E2E_FE_DIR" ]]; then
    echo "BLOCKED_ENV: CARELOG_E2E_FE_DIR is not a directory" >&2
    exit 2
  fi
fi
# Use the CI supplied login for the browser journey; G4-only runs get fresh local credentials.
if [[ -z "${CARELOG_E2E_ACCOUNT_A:-}" ]]; then
  CARELOG_E2E_ACCOUNT_A="g4-a-$(python3 -c 'import secrets; print(secrets.token_hex(8))')@example.invalid"
fi
if [[ -z "${CARELOG_E2E_PASSWORD_A:-}" ]]; then
  CARELOG_E2E_PASSWORD_A="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
fi
G4_E2E_PASSWORD_B="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export CARELOG_E2E_ACCOUNT_A CARELOG_E2E_PASSWORD_A G4_E2E_PASSWORD_B
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  printf '::add-mask::%s\n' "$CARELOG_E2E_ACCOUNT_A"
  printf '::add-mask::%s\n' "$CARELOG_E2E_PASSWORD_A"
  printf '::add-mask::%s\n' "$G4_E2E_PASSWORD_B"
fi

COMPOSE=(docker compose --project-name "g4-disposable-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}" -f docker-compose.identity-e2e.yml -f docker-compose.g4-e2e.yml)
if [[ "$REAL_API_E2E" == "1" ]]; then
  COMPOSE+=(-f docker-compose.carelog-real-api-e2e.yml)
fi
report_gateway_port_diagnostics() {
  local config_json container_id host_bindings published_ports
  config_json="$("${COMPOSE[@]}" config --format json)"
  printf '%s' "$config_json" | python3 -c 'import json,sys; ports=json.load(sys.stdin)["services"]["gateway-app"].get("ports", []); print("Gateway Compose port config:", json.dumps([{k:p.get(k) for k in ("host_ip","published","target","protocol")} for p in ports], sort_keys=True))' >&2
  container_id="$("${COMPOSE[@]}" ps -q gateway-app)"
  if [[ -n "$container_id" ]]; then
    host_bindings="$(docker inspect --format '{{json .HostConfig.PortBindings}}' "$container_id")"
    printf '%s' "$host_bindings" | python3 -c 'import json,sys; data=json.load(sys.stdin) or {}; print("Gateway HostConfig port bindings:", json.dumps(data.get("8080/tcp"), sort_keys=True))' >&2
    published_ports="$(docker inspect --format '{{json .NetworkSettings.Ports}}' "$container_id")"
    printf '%s' "$published_ports" | python3 -c 'import json,sys; data=json.load(sys.stdin) or {}; print("Gateway network published ports:", json.dumps(data.get("8080/tcp"), sort_keys=True))' >&2
  else
    echo "Gateway container ID: unavailable" >&2
  fi
}
assert_gateway_loopback_port() {
  local expected="127.0.0.1:${CARELOG_E2E_GATEWAY_PORT}"
  local actual http_status
  actual="$("${COMPOSE[@]}" port gateway-app 8080 2>/dev/null || true)"
  if [[ "$actual" != "$expected" ]]; then
    echo "BLOCKED_ENV: expected Gateway loopback mapping ${expected}, Compose reports ${actual:-none}" >&2
    report_gateway_port_diagnostics
    return 2
  fi
  http_status="$(curl --connect-timeout 3 --max-time 5 --noproxy '*' -sS -o /dev/null -w '%{http_code}' "http://${expected}/" 2>/dev/null || true)"
  if ! [[ "$http_status" =~ ^[1-5][0-9][0-9]$ ]]; then
    echo "BLOCKED_ENV: Gateway loopback port mapping exists but HTTP connection failed (status: ${http_status:-unreachable})" >&2
    report_gateway_port_diagnostics
    return 2
  fi
  echo "Gateway loopback mapping verified: ${expected} (HTTP ${http_status})"
}
cleanup() {
  code=$?
  if ((code != 0)); then
    "${COMPOSE[@]}" ps --all || true
    if [[ "$REAL_API_E2E" != "1" ]]; then
      "${COMPOSE[@]}" logs --tail=90 --no-color carelog-be platform-core-app gateway-app | sed -E 's/(password|secret|token)=([^[:space:]]+)/\1=[REDACTED]/Ig' || true
    fi
  fi
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "$code"
}
trap cleanup EXIT INT TERM
"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" build carelog-be platform-core-app gateway-app
"${COMPOSE[@]}" up -d --wait postgres valkey fake-kakao carelog-postgres
"${COMPOSE[@]}" up -d --wait carelog-be platform-core-app gateway-app
"${COMPOSE[@]}" run --rm identity-e2e-seed
ready=0
for _ in $(seq 1 50); do
  if "${COMPOSE[@]}" exec -T carelog-postgres psql -U carelog_g4 -d carelog_g4 -Atqc "SELECT count(*) FROM flyway_schema_history WHERE success AND version='8'" 2>/dev/null | grep -qx 1; then
    ready=1; break
  fi
  sleep 3
done
if [[ "$ready" != 1 ]]; then
  echo "BLOCKED_ENV: Carelog migration V8 did not finish"; exit 2
fi
"${COMPOSE[@]}" exec -T carelog-postgres psql -v ON_ERROR_STOP=1 -U carelog_g4 -d carelog_g4 -f /g4/seed-carelog.sql
"${COMPOSE[@]}" run --rm g4-three-service-runner
if [[ "$REAL_API_E2E" == "1" ]]; then
  assert_gateway_loopback_port
  export CARELOG_E2E_GATEWAY_TARGET="http://127.0.0.1:${CARELOG_E2E_GATEWAY_PORT}"
  echo "Running Carelog real API Playwright journey while G4 services are alive"
  (cd "$CARELOG_E2E_FE_DIR" && pnpm exec playwright test --config playwright.real-api.config.ts)
fi
