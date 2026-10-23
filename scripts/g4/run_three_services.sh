#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/../.."
COMPOSE=(docker compose --project-name "g4-disposable-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}" -f docker-compose.identity-e2e.yml -f docker-compose.g4-e2e.yml)
cleanup() {
  code=$?
  if ((code != 0)); then
    "${COMPOSE[@]}" ps --all || true
    "${COMPOSE[@]}" logs --tail=90 --no-color carelog-be platform-core-app gateway-app | sed -E 's/(password|secret|token)=([^[:space:]]+)/\1=[REDACTED]/Ig' || true
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
