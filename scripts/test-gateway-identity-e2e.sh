#!/usr/bin/env bash
set -Eeuo pipefail

CHECKOUT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/platform-issue24-e2e.XXXXXXXX")"
SOURCE_ROOT="$TEMP_DIR/source"
COMPOSE_FILE=""
COMPOSE=()
CORE_IMAGE=""
GATEWAY_IMAGE=""

cleanup() {
    local status=$?
    local cleanup_status=0
    local step_status=0
    if [[ -n "$COMPOSE_FILE" ]]; then
        if "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1; then
            :
        else
            step_status=$?
            printf 'E2E cleanup failed: compose down (exit %s)\n' "$step_status" >&2
            cleanup_status=$step_status
        fi
    fi
    if [[ -n "$CORE_IMAGE" && -n "$GATEWAY_IMAGE" ]]; then
        if docker image rm "$CORE_IMAGE" "$GATEWAY_IMAGE" >/dev/null 2>&1; then
            :
        else
            step_status=$?
            printf 'E2E cleanup failed: image removal (exit %s)\n' "$step_status" >&2
            if ((cleanup_status == 0)); then
                cleanup_status=$step_status
            fi
        fi
    fi
    if [[ -d "$SOURCE_ROOT" ]]; then
        if chmod -R u+w "$SOURCE_ROOT" >/dev/null 2>&1; then
            :
        else
            step_status=$?
            printf 'E2E cleanup failed: source permissions (exit %s)\n' "$step_status" >&2
            if ((cleanup_status == 0)); then
                cleanup_status=$step_status
            fi
        fi
    fi
    if rm -rf -- "$TEMP_DIR"; then
        :
    else
        step_status=$?
        printf 'E2E cleanup failed: temporary directory removal (exit %s)\n' "$step_status" >&2
        if ((cleanup_status == 0)); then
            cleanup_status=$step_status
        fi
    fi
    if ((status == 0 && cleanup_status != 0)); then
        status=$cleanup_status
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

RUN_TOKEN="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
if [[ ! "$RUN_TOKEN" =~ ^[0-9a-f]{32}$ ]]; then
    echo "Unable to generate a unique E2E run token" >&2
    exit 2
fi
PROJECT_NAME="platform-issue24-e2e-$RUN_TOKEN"

SOURCE_COMMIT="$(git -C "$CHECKOUT_ROOT" rev-parse --verify HEAD)"
SOURCE_TREE="$(git -C "$CHECKOUT_ROOT" rev-parse --verify "${SOURCE_COMMIT}^{tree}")"
if [[ ! "$SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ || ! "$SOURCE_TREE" =~ ^[0-9a-f]{40}$ ]]; then
    echo "Unable to determine the checked-out source commit and tree" >&2
    exit 2
fi

# Evidence files are output only; source changes remain forbidden.
if ! git -C "$CHECKOUT_ROOT" diff --quiet "$SOURCE_COMMIT" -- .; then
    echo "Refusing to test a dirty source tree; commit or discard source changes first" >&2
    exit 2
fi
UNTRACKED=""
while IFS= read -r path; do
    if [[ "$path" =~ ^docs/testing/evidence/issue-24-gateway-identity-(functional|valkey-failure|postgres-failure)-[0-9a-f]{32}\.json(\.[0-9a-f]{32}\.tmp)?$ ]]; then
        continue
    fi
    UNTRACKED+="$path"$'\n'
done < <(git -C "$CHECKOUT_ROOT" ls-files --others --exclude-standard)
if [[ -n "$UNTRACKED" ]]; then
    echo "Refusing to test with untracked source files" >&2
    exit 2
fi
if git -C "$CHECKOUT_ROOT" ls-tree -r "$SOURCE_COMMIT" | grep '^160000 commit' > "$TEMP_DIR/submodules"; then
    echo "Refusing to build an archive that omits Git submodules" >&2
    exit 2
fi

# Build only from an immutable archive of the captured commit. Changes to the
# live checkout during a long Docker build cannot change these input files.
mkdir -p "$SOURCE_ROOT"
git -C "$CHECKOUT_ROOT" archive --format=tar "$SOURCE_COMMIT" | tar -xf - -C "$SOURCE_ROOT"
chmod -R a-w "$SOURCE_ROOT"
COMPOSE_FILE="$SOURCE_ROOT/docker-compose.identity-e2e.yml"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE")

export PLATFORM_E2E_RUN_TOKEN="$RUN_TOKEN"
export PLATFORM_E2E_EVIDENCE_HOST_PATH="$CHECKOUT_ROOT/docs/testing/evidence"
export PLATFORM_E2E_SOURCE_COMMIT="$SOURCE_COMMIT"
export PLATFORM_E2E_SOURCE_TREE="$SOURCE_TREE"
export PLATFORM_E2E_IMAGE_TAG="${SOURCE_COMMIT}-${RUN_TOKEN}"
CORE_IMAGE="platform-core-identity-e2e:$PLATFORM_E2E_IMAGE_TAG"
GATEWAY_IMAGE="platform-gateway-identity-e2e:$PLATFORM_E2E_IMAGE_TAG"
PREBUILT_CORE_IMAGE="platform-core-identity-e2e:$SOURCE_COMMIT"
PREBUILT_GATEWAY_IMAGE="platform-gateway-identity-e2e:$SOURCE_COMMIT"

inspect_bound_image_id() {
    image="$1"
    metadata="$(docker image inspect --format '{{.Id}}|{{ index .Config.Labels "org.opencontainers.image.revision" }}|{{ index .Config.Labels "com.ranikunlabs.e2e.source-tree" }}' "$image" 2>/dev/null)" || {
        echo "Required source-bound E2E image is unavailable: $image" >&2
        return 1
    }
    IFS='|' read -r image_id actual_commit actual_tree <<< "$metadata"
    if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
        echo "E2E image has no valid immutable image ID: $image" >&2
        return 1
    fi
    if [[ "$actual_commit" != "$SOURCE_COMMIT" || "$actual_tree" != "$SOURCE_TREE" ]]; then
        echo "E2E image source binding does not match the current checkout: $image" >&2
        return 1
    fi
    printf '%s\n' "$image_id"
}

pin_image_to_run_tag() {
    source_image="$1"
    run_image="$2"
    image_id="$(inspect_bound_image_id "$source_image")" || return 1
    # Retag by the immutable image ID, never by resolving a mutable source tag a
    # second time. The random per-run destination is unique across daemon users.
    docker image tag "$image_id" "$run_image" || return 1
    pinned_id="$(inspect_bound_image_id "$run_image")" || return 1
    if [[ "$pinned_id" != "$image_id" ]]; then
        echo "E2E run tag resolved to a different image than the captured ID" >&2
        return 1
    fi
    printf '%s\n' "$image_id"
}

if [[ "${PLATFORM_E2E_SKIP_BUILD:-0}" == "1" ]]; then
    CORE_IMAGE_ID="$(pin_image_to_run_tag "$PREBUILT_CORE_IMAGE" "$CORE_IMAGE")"
    GATEWAY_IMAGE_ID="$(pin_image_to_run_tag "$PREBUILT_GATEWAY_IMAGE" "$GATEWAY_IMAGE")"
else
    "${COMPOSE[@]}" build platform-core-app
    "${COMPOSE[@]}" build gateway-app
    CORE_IMAGE_ID="$(inspect_bound_image_id "$CORE_IMAGE")"
    GATEWAY_IMAGE_ID="$(inspect_bound_image_id "$GATEWAY_IMAGE")"
    # Keep a SHA-tagged cache for a future skip-build invocation. Runtime
    # services still use only the unique per-run tags above.
    docker image tag "$CORE_IMAGE_ID" "$PREBUILT_CORE_IMAGE"
    docker image tag "$GATEWAY_IMAGE_ID" "$PREBUILT_GATEWAY_IMAGE"
fi
export PLATFORM_E2E_CORE_IMAGE_ID="$CORE_IMAGE_ID"
export PLATFORM_E2E_GATEWAY_IMAGE_ID="$GATEWAY_IMAGE_ID"

require_container_image() {
    service="$1"
    expected_image_id="$2"
    container_id="$("${COMPOSE[@]}" ps --quiet "$service")" || return 1
    if [[ -z "$container_id" ]]; then
        echo "E2E service did not create a container: $service" >&2
        return 1
    fi
    actual_image_id="$(docker inspect --format '{{.Image}}' "$container_id")" || return 1
    if [[ "$actual_image_id" != "$expected_image_id" ]]; then
        echo "E2E container image does not match recorded image ID: $service" >&2
        return 1
    fi
}

"${COMPOSE[@]}" up --detach --wait postgres valkey fake-kakao
"${COMPOSE[@]}" up --detach --wait --no-build --pull never platform-core-app gateway-app
require_container_image platform-core-app "$PLATFORM_E2E_CORE_IMAGE_ID"
require_container_image gateway-app "$PLATFORM_E2E_GATEWAY_IMAGE_ID"
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-seed
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-runner --phase functional

"${COMPOSE[@]}" stop valkey
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-runner --phase valkey-failure
"${COMPOSE[@]}" start valkey
"${COMPOSE[@]}" up --wait valkey

"${COMPOSE[@]}" stop postgres
"${COMPOSE[@]}" run --rm --no-deps identity-e2e-runner --phase postgres-failure
