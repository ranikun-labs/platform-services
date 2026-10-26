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
COMPOSE=(docker compose --project-name "g4-disposable-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}" -f docker-compose.identity-e2e.yml -f docker-compose.g4-e2e.yml)
if [[ "$REAL_API_E2E" == "1" ]]; then
  COMPOSE+=(-f docker-compose.carelog-real-api-e2e.yml)
fi
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
  export CARELOG_E2E_GATEWAY_TARGET="http://127.0.0.1:${CARELOG_E2E_GATEWAY_PORT}"
  echo "Running Carelog real API Playwright journey while G4 services are alive"
  (cd "$CARELOG_E2E_FE_DIR" && pnpm exec playwright test --config playwright.real-api.config.ts)
fi
