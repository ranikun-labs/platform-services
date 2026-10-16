#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.identity-e2e.yml"
PROJECT_NAME="platform-issue24-e2e-${UID:-1000}-$$"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE")

cleanup() {
    status=$?
    "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT INT TERM

cd "$ROOT_DIR"
if [[ "${PLATFORM_E2E_SKIP_BUILD:-0}" == "1" ]]; then
    docker image inspect platform-core-identity-e2e:local platform-gateway-identity-e2e:local >/dev/null
else
    "${COMPOSE[@]}" build platform-core-app
    "${COMPOSE[@]}" build gateway-app
fi
"${COMPOSE[@]}" up --detach --wait postgres valkey fake-kakao
"${COMPOSE[@]}" up --detach --wait platform-core-app gateway-app
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-seed
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-runner --phase functional

"${COMPOSE[@]}" stop valkey
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-runner --phase valkey-failure
"${COMPOSE[@]}" start valkey
"${COMPOSE[@]}" up --wait valkey

"${COMPOSE[@]}" stop postgres
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-runner --phase postgres-failure
