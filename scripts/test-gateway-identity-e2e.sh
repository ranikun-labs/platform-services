#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.identity-e2e.yml"
PROJECT_NAME="platform-issue24-e2e-${UID:-1000}-$$"
# Identity e2e uses the owner-supplied A login when present and ephemeral B credentials.
if [[ -z "${CARELOG_E2E_ACCOUNT_A:-}" ]]; then
  CARELOG_E2E_ACCOUNT_A="identity-a-$(python3 -c 'import secrets; print(secrets.token_hex(8))')@example.invalid"
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
