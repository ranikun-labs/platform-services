#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/test-gateway-identity-e2e.sh"
TEMP_DIR="$(mktemp -d)"
SOURCE_INPUT="$ROOT_DIR/platform-core/app/build.gradle.kts"
SOURCE_BACKUP="$TEMP_DIR/source-build.gradle.kts"
cp "$SOURCE_INPUT" "$SOURCE_BACKUP"
MUTATION_EXPECTED=0
restore_source() {
    if [[ "$MUTATION_EXPECTED" == 1 ]]; then
        cp "$SOURCE_BACKUP" "$SOURCE_INPUT"
    fi
    rm -rf "$TEMP_DIR"
}
trap restore_source EXIT INT TERM
SOURCE_COMMIT="$(git -C "$ROOT_DIR" rev-parse --verify HEAD)"
SOURCE_TREE="$(git -C "$ROOT_DIR" rev-parse --verify 'HEAD^{tree}')"
EXPECTED_INPUT_SHA="$(sha256sum "$SOURCE_INPUT" | awk '{print $1}')"
CORE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
GATEWAY_ID=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
REAL_GIT="$(command -v git)"
CHANGED_TREE=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee

# Compose must parse the build definition before post-build image IDs exist.
env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    PLATFORM_E2E_SOURCE_COMMIT="$SOURCE_COMMIT" \
    PLATFORM_E2E_SOURCE_TREE="$SOURCE_TREE" \
    PLATFORM_E2E_RUN_TOKEN=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    PLATFORM_E2E_IMAGE_TAG="${SOURCE_COMMIT}-config-check" \
    PLATFORM_E2E_EVIDENCE_HOST_PATH="$ROOT_DIR/docs/testing/evidence" \
    CODEX_PROXY_CERT=/dev/null \
    docker compose --project-name image-binding-config-test \
    --file "$ROOT_DIR/docker-compose.identity-e2e.yml" config --quiet

mkdir -p "$TEMP_DIR/bin"
cat > "$TEMP_DIR/bin/od" <<'OD'
#!/usr/bin/env bash
set -eu
counter=0
if [[ -f "${FAKE_OD_STATE:?}" ]]; then
    counter="$(cat "$FAKE_OD_STATE")"
fi
case "$counter" in
    0) byte=11 ;;
    1) byte=22 ;;
    2) byte=33 ;;
    3) byte=44 ;;
    4) byte=55 ;;
    5) byte=66 ;;
    6) byte=77 ;;
    7) byte=88 ;;
    8) byte=99 ;;
    *) exit 7 ;;
esac
printf '%s' "$((counter + 1))" > "$FAKE_OD_STATE"
for _ in {1..16}; do printf '%s ' "$byte"; done
printf '\n'
OD
cat > "$TEMP_DIR/bin/git" <<'GIT'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "${FAKE_GIT_LOG:?}"
case "$*" in
    *"diff --quiet HEAD -- .")
        echo 'simulated checkout advance makes mutable-HEAD cleanliness checks unsafe' >&2
        exit 1
        ;;
    *"ls-tree -r HEAD")
        printf '160000 commit %s\tfake-submodule\n' "${FAKE_CHANGED_TREE:?}"
        ;;
    *"rev-parse --verify HEAD")
        # Simulate a checkout advancing immediately after the captured commit.
        printf '%s\n' "${FAKE_GIT_COMMIT:?}"
        ;;
    *"rev-parse --verify "*)
        last_arg="${!#}"
        if [[ "$last_arg" == "${FAKE_GIT_COMMIT:?}^{tree}" ]]; then
            printf '%s\n' "${FAKE_GIT_TREE:?}"
        elif [[ "$last_arg" == 'HEAD^{tree}' ]]; then
            printf '%s\n' "${FAKE_CHANGED_TREE:?}"
        else
            exec "${FAKE_REAL_GIT:?}" "$@"
        fi
        ;;
    *) exec "${FAKE_REAL_GIT:?}" "$@" ;;
esac
GIT
cat > "$TEMP_DIR/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -eu
log() {
    printf '%s|tag=%s|core=%s|gateway=%s|run=%s|source_commit=%s|source_tree=%s\n' "$*" \
        "${PLATFORM_E2E_IMAGE_TAG-}" "${PLATFORM_E2E_CORE_IMAGE_ID-}" \
        "${PLATFORM_E2E_GATEWAY_IMAGE_ID-}" "${PLATFORM_E2E_RUN_TOKEN-}" \
        "${PLATFORM_E2E_SOURCE_COMMIT-}" "${PLATFORM_E2E_SOURCE_TREE-}" \
        >> "${FAKE_DOCKER_LOG:?}"
}
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
    format="$4"
    image="$5"
    if [[ "$format" == *org.opencontainers.image.revision* ]]; then
        if [[ "${FAKE_FAIL_BOUND_IMAGE_INSPECT:-0}" == 1 ]]; then
            echo 'simulated primary image binding failure' >&2
            exit 43
        fi
        if [[ "$image" == platform-core-identity-e2e:* ]]; then
            id="${FAKE_CORE_ID:?}"
        else
            id="${FAKE_GATEWAY_ID:?}"
        fi
        printf '%s|%s|%s\n' "$id" "${FAKE_SOURCE_COMMIT:?}" "${FAKE_SOURCE_TREE:?}"
        exit 0
    fi
    exit 9
fi
if [[ "${1:-}" == image && ( "${2:-}" == tag || "${2:-}" == rm ) ]]; then
    log "$*"
    if [[ "${2:-}" == rm && "${FAKE_FAIL_IMAGE_RM:-0}" == 1 ]]; then
        echo 'simulated image removal failure' >&2
        exit 42
    fi
    exit 0
fi
if [[ "${1:-}" == inspect ]]; then
    if [[ "${4:-}" == core-container ]]; then
        printf '%s\n' "${FAKE_CORE_CONTAINER_ID:-${FAKE_CORE_ID:?}}"
    elif [[ "${4:-}" == gateway-container ]]; then
        printf '%s\n' "${FAKE_GATEWAY_CONTAINER_ID:-${FAKE_GATEWAY_ID:?}}"
    else
        exit 9
    fi
    exit 0
fi
if [[ "${1:-}" == compose ]]; then
    all="$*"
    compose_file=""
    while (($#)); do
        if [[ "$1" == --file ]]; then
            compose_file="$2"
            shift 2
        else
            shift
        fi
    done
    if [[ "$compose_file" != */source/docker-compose.identity-e2e.yml || ! -r "$compose_file" ]]; then
        echo "Compose did not use a readable immutable source snapshot" >&2
        exit 9
    fi
    if [[ "$all" == *" down --volumes --remove-orphans"* && "${FAKE_FAIL_COMPOSE_DOWN:-0}" == 1 ]]; then
        log "$all"
        echo 'simulated compose down failure' >&2
        exit 41
    fi
    snapshot_root="${compose_file%/docker-compose.identity-e2e.yml}"
    snapshot_input="$snapshot_root/${FAKE_SOURCE_RELATIVE:?}"
    snapshot_sha="$(sha256sum "$snapshot_input" | awk '{print $1}')"
    if [[ "$snapshot_sha" != "${FAKE_EXPECTED_INPUT_SHA:?}" ]]; then
        echo "Build input changed after source snapshot creation" >&2
        exit 9
    fi
    if [[ "$all" == *" build platform-core-app"* && "${FAKE_MUTATE_LIVE_SOURCE:-0}" == 1 \
        && ! -f "${FAKE_MUTATION_FLAG:?}" ]]; then
        printf '\n# simulated concurrent checkout edit\n' >> "${FAKE_LIVE_SOURCE:?}"
        : > "$FAKE_MUTATION_FLAG"
    fi
    log "$all"
    if [[ "$all" == *" ps --quiet platform-core-app"* ]]; then
        printf '%s\n' core-container
    elif [[ "$all" == *" ps --quiet gateway-app"* ]]; then
        printf '%s\n' gateway-container
    fi
    exit 0
fi
exit 8
DOCKER
chmod +x "$TEMP_DIR/bin/od" "$TEMP_DIR/bin/git" "$TEMP_DIR/bin/docker"
export PATH="$TEMP_DIR/bin:$PATH"
export FAKE_OD_STATE="$TEMP_DIR/od-state"
export FAKE_REAL_GIT="$REAL_GIT"
export FAKE_GIT_LOG="$TEMP_DIR/git.log"
export FAKE_GIT_COMMIT="$SOURCE_COMMIT"
export FAKE_GIT_TREE="$SOURCE_TREE"
export FAKE_SOURCE_COMMIT="$SOURCE_COMMIT"
export FAKE_SOURCE_TREE="$SOURCE_TREE"
export FAKE_CHANGED_TREE="$CHANGED_TREE"
export FAKE_CORE_ID="$CORE_ID"
export FAKE_GATEWAY_ID="$GATEWAY_ID"
export FAKE_SOURCE_RELATIVE=platform-core/app/build.gradle.kts
export FAKE_EXPECTED_INPUT_SHA="$EXPECTED_INPUT_SHA"
export FAKE_LIVE_SOURCE="$SOURCE_INPUT"
export FAKE_MUTATION_FLAG="$TEMP_DIR/live-source-mutated"

# Default build path: no post-build IDs are required by Compose. A deterministic
# concurrent edit to the live checkout cannot alter the archived build input.
export FAKE_DOCKER_LOG="$TEMP_DIR/build.log"
export FAKE_MUTATE_LIVE_SOURCE=1
MUTATION_EXPECTED=1
env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    PLATFORM_E2E_SKIP_BUILD=0 "$SCRIPT"
TOKEN_A=11111111111111111111111111111111
[[ -f "$FAKE_MUTATION_FLAG" ]]
[[ "$(sha256sum "$SOURCE_INPUT" | awk '{print $1}')" != "$EXPECTED_INPUT_SHA" ]]
grep -Fq -- "--project-name platform-issue24-e2e-$TOKEN_A" "$FAKE_DOCKER_LOG"
grep -Fq "rev-parse --verify ${SOURCE_COMMIT}^{tree}" "$FAKE_GIT_LOG"
if grep -Fq 'rev-parse --verify HEAD^{tree}' "$FAKE_GIT_LOG"; then
    echo 'source tree was resolved from mutable HEAD instead of the captured commit' >&2
    exit 1
fi
grep -Fq "diff --quiet $SOURCE_COMMIT -- ." "$FAKE_GIT_LOG"
grep -Fq "ls-tree -r $SOURCE_COMMIT" "$FAKE_GIT_LOG"
if grep -Fq 'diff --quiet HEAD -- .' "$FAKE_GIT_LOG" || grep -Fq 'ls-tree -r HEAD' "$FAKE_GIT_LOG"; then
    echo 'source validation used mutable HEAD after commit capture' >&2
    exit 1
fi
grep -Fq "build platform-core-app|tag=${SOURCE_COMMIT}-${TOKEN_A}" "$FAKE_DOCKER_LOG"
grep -Fq "build gateway-app|tag=${SOURCE_COMMIT}-${TOKEN_A}" "$FAKE_DOCKER_LOG"
grep -Fq 'up --detach --wait --no-build --pull never platform-core-app gateway-app' "$FAKE_DOCKER_LOG"
grep -Fq "|core=$CORE_ID|gateway=$GATEWAY_ID|run=$TOKEN_A" "$FAKE_DOCKER_LOG"
grep -Fq "|source_commit=$SOURCE_COMMIT|source_tree=$SOURCE_TREE" "$FAKE_DOCKER_LOG"
cp "$SOURCE_BACKUP" "$SOURCE_INPUT"
MUTATION_EXPECTED=0
unset FAKE_MUTATE_LIVE_SOURCE

echo 'captured-commit tree binding across simulated checkout advance: PASS'
echo 'default build with unset IDs and immutable source snapshot: PASS'

# A second concurrent-shaped invocation receives a distinct daemon-wide token.
export FAKE_DOCKER_LOG="$TEMP_DIR/skip.log"
env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    PLATFORM_E2E_SKIP_BUILD=1 "$SCRIPT"
TOKEN_B=22222222222222222222222222222222
[[ "$TOKEN_A" != "$TOKEN_B" ]]
grep -Fq -- "--project-name platform-issue24-e2e-$TOKEN_B" "$FAKE_DOCKER_LOG"
grep -Fq "image tag $CORE_ID platform-core-identity-e2e:${SOURCE_COMMIT}-${TOKEN_B}" "$FAKE_DOCKER_LOG"
grep -Fq "image tag $GATEWAY_ID platform-gateway-identity-e2e:${SOURCE_COMMIT}-${TOKEN_B}" "$FAKE_DOCKER_LOG"
if grep -Fq ' build ' "$FAKE_DOCKER_LOG"; then
    echo 'skip-build unexpectedly built images' >&2
    exit 1
fi
echo 'unique token and skip-build immutable-ID pinning: PASS'

# Stale source labels must fail before application startup.
export FAKE_DOCKER_LOG="$TEMP_DIR/stale.log"
if env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    FAKE_SOURCE_COMMIT=1111111111111111111111111111111111111111 \
    PLATFORM_E2E_SKIP_BUILD=1 "$SCRIPT" >"$TEMP_DIR/stale.out" 2>&1; then
    echo 'stale skip-build image was accepted' >&2
    exit 1
fi
grep -Fq 'source binding does not match' "$TEMP_DIR/stale.out"
if grep -Fq 'up --detach --wait --no-build --pull never platform-core-app gateway-app' "$FAKE_DOCKER_LOG"; then
    echo 'stale image reached application startup' >&2
    exit 1
fi
echo 'skip-build stale-source rejection: PASS'

# A container whose executed image differs from the recorded ID cannot proceed.
export FAKE_DOCKER_LOG="$TEMP_DIR/container-mismatch.log"
if env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    FAKE_CORE_CONTAINER_ID=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc \
    PLATFORM_E2E_SKIP_BUILD=0 "$SCRIPT" >"$TEMP_DIR/container-mismatch.out" 2>&1; then
    echo 'container image ID mismatch was accepted' >&2
    exit 1
fi
grep -Fq 'container image does not match recorded image ID' "$TEMP_DIR/container-mismatch.out"
if grep -Fq 'run --rm --no-deps identity-e2e-seed' "$FAKE_DOCKER_LOG"; then
    echo 'test phases ran before container image ID verification' >&2
    exit 1
fi
echo 'recorded-versus-executed image identity mismatch rejection: PASS'

# Concurrent phase outputs share one directory but never share paths or temp files;
# a persistence error must reach main as a nonzero exit instead of printing PASS.
PYTHONDONTWRITEBYTECODE=1 python3 - "$ROOT_DIR" <<'PY'
import contextlib
import importlib.util
import io
import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import tempfile

root = Path(sys.argv[1])
module_path = root / 'scripts/e2e/gateway_identity_e2e.py'
os.environ.update({
    'PLATFORM_E2E_RUN_TOKEN': 'f' * 32,
    'PLATFORM_E2E_SOURCE_COMMIT': 'a' * 40,
    'PLATFORM_E2E_SOURCE_TREE': 'b' * 40,
    'PLATFORM_E2E_CORE_IMAGE_ID': 'sha256:' + 'c' * 64,
    'PLATFORM_E2E_GATEWAY_IMAGE_ID': 'sha256:' + 'd' * 64,
    'PLATFORM_E2E_JWT_SECRET': 'synthetic-test-value',
    'PLATFORM_E2E_LOGIN_ID': 'synthetic-user',
    'PLATFORM_E2E_PASSWORD': 'synthetic-password',
    'PLATFORM_E2E_FAKE_KAKAO_CODE': 'synthetic-code',
})
spec = importlib.util.spec_from_file_location('gateway_identity_e2e_test', module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.CHECKS = [{'name': 'test.only', 'result': 'PASS'}]
with tempfile.TemporaryDirectory() as output_dir:
    tokens = [f'{index:032x}' for index in range(1, 17)]
    def write(token):
        return module.write_redacted_evidence('functional', 'PASS', evidence_dir=output_dir, run_token=token)
    concurrent_stdout = io.StringIO()
    with contextlib.redirect_stdout(concurrent_stdout):
        with ThreadPoolExecutor(max_workers=8) as pool:
            paths = list(pool.map(write, tokens))
    assert len(set(paths)) == len(tokens)
    assert len(list(Path(output_dir).glob('*.json'))) == len(tokens)
    for token, filename in zip(tokens, paths):
        evidence = json.loads(Path(filename).read_text())
        assert evidence['runToken'] == token
        assert 'baseCommit' not in evidence

    module.RUN_TOKEN = 'e' * 32
    module.CHECKS = []
    module.check_valkey_failure_closed = lambda: None
    os.environ['PLATFORM_E2E_EVIDENCE_DIR'] = output_dir
    original_replace = module.os.replace
    def fail_replace(_src, _dst):
        raise OSError('synthetic evidence persistence failure')
    module.os.replace = fail_replace
    sys.argv = ['gateway_identity_e2e.py', '--phase', 'valkey-failure']
    stderr = io.StringIO()
    stdout = io.StringIO()
    try:
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = module.main()
    finally:
        module.os.replace = original_replace
    assert result == 3
    assert stdout.getvalue() == ''
    assert 'E2E evidence persistence failed (OSError)' in stderr.getvalue()
    failed_path = Path(output_dir) / ('issue-24-gateway-identity-valkey-failure-' + 'e' * 32 + '.json')
    assert not failed_path.exists()
    assert not Path(str(failed_path) + '.' + 'e' * 32 + '.tmp').exists()
print('concurrent evidence uniqueness and write-failure propagation: PASS')
PY

# Cleanup errors are visible and become the result only when the E2E body passed.
export FAKE_DOCKER_LOG="$TEMP_DIR/cleanup-compose-down.log"
set +e
env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    FAKE_FAIL_COMPOSE_DOWN=1 PLATFORM_E2E_SKIP_BUILD=0 "$SCRIPT" \
    >"$TEMP_DIR/cleanup-compose-down.out" 2>&1
COMPOSE_CLEANUP_STATUS=$?
set -e
[[ "$COMPOSE_CLEANUP_STATUS" == 41 ]]
grep -Fq 'E2E cleanup failed: compose down (exit 41)' "$TEMP_DIR/cleanup-compose-down.out"
if grep -Fq 'E2E cleanup failed: image removal' "$TEMP_DIR/cleanup-compose-down.out"; then
    echo 'compose-down-only failure unexpectedly reported image removal failure' >&2
    exit 1
fi
echo 'compose-down failure is visible and fails an otherwise successful run: PASS'

export FAKE_DOCKER_LOG="$TEMP_DIR/cleanup-image-rm.log"
set +e
env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    FAKE_FAIL_IMAGE_RM=1 PLATFORM_E2E_SKIP_BUILD=0 "$SCRIPT" \
    >"$TEMP_DIR/cleanup-image-rm.out" 2>&1
IMAGE_CLEANUP_STATUS=$?
set -e
[[ "$IMAGE_CLEANUP_STATUS" == 42 ]]
grep -Fq 'E2E cleanup failed: image removal (exit 42)' "$TEMP_DIR/cleanup-image-rm.out"
if grep -Fq 'E2E cleanup failed: compose down' "$TEMP_DIR/cleanup-image-rm.out"; then
    echo 'image-removal-only failure unexpectedly reported compose-down failure' >&2
    exit 1
fi
echo 'image-removal failure is visible and fails an otherwise successful run: PASS'

# A failed primary image check remains primary even when both cleanup actions fail.
export FAKE_DOCKER_LOG="$TEMP_DIR/cleanup-after-primary-failure.log"
set +e
env -u PLATFORM_E2E_CORE_IMAGE_ID -u PLATFORM_E2E_GATEWAY_IMAGE_ID \
    FAKE_FAIL_BOUND_IMAGE_INSPECT=1 FAKE_FAIL_COMPOSE_DOWN=1 FAKE_FAIL_IMAGE_RM=1 \
    PLATFORM_E2E_SKIP_BUILD=0 "$SCRIPT" \
    >"$TEMP_DIR/cleanup-after-primary-failure.out" 2>&1
PRIMARY_STATUS=$?
set -e
[[ "$PRIMARY_STATUS" == 1 ]]
grep -Fq 'Required source-bound E2E image is unavailable' "$TEMP_DIR/cleanup-after-primary-failure.out"
grep -Fq 'E2E cleanup failed: compose down (exit 41)' "$TEMP_DIR/cleanup-after-primary-failure.out"
grep -Fq 'E2E cleanup failed: image removal (exit 42)' "$TEMP_DIR/cleanup-after-primary-failure.out"
echo 'primary image failure remains primary while both cleanup failures are reported: PASS'
