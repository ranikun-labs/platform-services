#!/usr/bin/env bash
set -Eeuo pipefail

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

if (($# != 0)); then
  fail "this test does not accept a Compose project or other arguments"
fi
if [[ -n "${COMPOSE_PROJECT_NAME:-}" ]]; then
  fail "COMPOSE_PROJECT_NAME must be unset; the test selects its own isolated project"
fi
if [[ -n "${COMPOSE_FILE:-}" ]]; then
  fail "COMPOSE_FILE must be unset; the test uses this repository's docker-compose.yml"
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/.." && pwd -P)"
compose_file="$repo_root/docker-compose.yml"
[[ -f "$compose_file" ]] || fail "Compose file not found: $compose_file"

project_suffix="$(tr -d '-' </proc/sys/kernel/random/uuid)" || fail "cannot generate a unique test project name"
[[ "$project_suffix" =~ ^[0-9a-f]{32}$ ]] || fail "generated test project name is invalid"
project="platform-valkey-recovery-test-$project_suffix"
expected_container="${project}-valkey-1"
expected_volume="${project}_platform-valkey-data"
expected_network="${project}_platform"

# Pin every Docker operation to the local engine socket. Do not inherit a
# remote Docker context or endpoint for this destructive recovery test.
docker_cli=(env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker --host=unix:///var/run/docker.sock)
compose=("${docker_cli[@]}" compose --project-name "$project" --file "$compose_file")

"${docker_cli[@]}" info --format '{{.ServerVersion}}' >/dev/null \
  || fail "local Docker Engine at /var/run/docker.sock is unavailable"
"${docker_cli[@]}" compose version >/dev/null \
  || fail "Docker Compose plugin is unavailable"

# Compose interpolates required values in unrelated services even though only
# Valkey is started. These process-local placeholders never start those apps.
export PLATFORM_POSTGRES_PASSWORD="${PLATFORM_POSTGRES_PASSWORD:-local-only}"
export PLATFORM_GATEWAY_CARELOG_BE_URI="${PLATFORM_GATEWAY_CARELOG_BE_URI:-http://carelog-be:8080}"
export PLATFORM_GATEWAY_CARELOG_RAG_URI="${PLATFORM_GATEWAY_CARELOG_RAG_URI:-http://carelog-rag:8000}"
export PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET="${PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET:-local-only}"
export PLATFORM_GATEWAY_INTERNAL_SECRET="${PLATFORM_GATEWAY_INTERNAL_SECRET:-local-only}"

assert_project_is_unused() {
  local ids names

  ids="$("${docker_cli[@]}" container ls --all --quiet --filter "label=com.docker.compose.project=$project")" \
    || fail "cannot inspect containers for the test project"
  [[ -z "$ids" ]] || fail "test project already owns containers; refusing to touch them"
  ids="$("${docker_cli[@]}" volume ls --quiet --filter "label=com.docker.compose.project=$project")" \
    || fail "cannot inspect volumes for the test project"
  [[ -z "$ids" ]] || fail "test project already owns volumes; refusing to touch them"
  ids="$("${docker_cli[@]}" network ls --quiet --filter "label=com.docker.compose.project=$project")" \
    || fail "cannot inspect networks for the test project"
  [[ -z "$ids" ]] || fail "test project already owns networks; refusing to touch them"

  names="$("${docker_cli[@]}" container ls --all --format '{{.Names}}')" \
    || fail "cannot inspect container names"
  if printf '%s\n' "$names" | grep -Fxq "$expected_container"; then
    fail "container name collision for the test project"
  fi
  names="$("${docker_cli[@]}" volume ls --format '{{.Name}}')" \
    || fail "cannot inspect volume names"
  if printf '%s\n' "$names" | grep -Fxq "$expected_volume"; then
    fail "volume name collision for the test project"
  fi
  names="$("${docker_cli[@]}" network ls --format '{{.Name}}')" \
    || fail "cannot inspect network names"
  if printf '%s\n' "$names" | grep -Fxq "$expected_network"; then
    fail "network name collision for the test project"
  fi
}

cleanup_enabled=0
cleanup() {
  local exit_status=$?
  local cleanup_failed=0
  local container_cleanup_failed=0
  local ids id labels names name
  trap - EXIT
  trap '' INT TERM HUP

  if ((cleanup_enabled)); then
    # The UUID project was verified empty immediately before `up`. Remove only
    # resources carrying its exact Compose ownership labels and expected role.
    if ! ids="$("${docker_cli[@]}" container ls --all --quiet \
      --filter "label=com.docker.compose.project=$project" \
      --filter 'label=com.docker.compose.service=valkey' 2>/dev/null)"; then
      printf 'CLEANUP FAIL: cannot list test-project containers\n' >&2
      cleanup_failed=1
      container_cleanup_failed=1
      ids=""
    fi
    while IFS= read -r id; do
      [[ -n "$id" ]] || continue
      labels="$("${docker_cli[@]}" container inspect \
        --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' \
        "$id" 2>/dev/null || true)"
      if [[ "$labels" == "$project|valkey" ]]; then
        "${docker_cli[@]}" container rm --force "$id" >/dev/null \
          || { printf 'CLEANUP FAIL: test-owned container %s\n' "$id" >&2; cleanup_failed=1; container_cleanup_failed=1; }
      else
        printf 'CLEANUP SKIP: container %s ownership could not be verified\n' "$id" >&2
        cleanup_failed=1
        container_cleanup_failed=1
      fi
    done <<<"$ids"

    if ((container_cleanup_failed)); then
      printf 'CLEANUP SKIP: volumes and networks remain because container ownership or cleanup failed\n' >&2
      cleanup_failed=1
    else
      if ! names="$("${docker_cli[@]}" volume ls --quiet --filter "label=com.docker.compose.project=$project" 2>/dev/null)"; then
        printf 'CLEANUP FAIL: cannot list test-project volumes\n' >&2
        cleanup_failed=1
        names=""
      fi
      while IFS= read -r name; do
        [[ -n "$name" ]] || continue
        labels="$("${docker_cli[@]}" volume inspect \
          --format '{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.volume"}}' \
          "$name" 2>/dev/null || true)"
        if [[ "$labels" == "$expected_volume|$project|platform-valkey-data" ]]; then
          "${docker_cli[@]}" volume rm "$name" >/dev/null \
            || { printf 'CLEANUP FAIL: test-owned volume %s\n' "$name" >&2; cleanup_failed=1; }
        else
          printf 'CLEANUP SKIP: volume %s ownership could not be verified\n' "$name" >&2
          cleanup_failed=1
        fi
      done <<<"$names"

      if ! ids="$("${docker_cli[@]}" network ls --quiet --filter "label=com.docker.compose.project=$project" 2>/dev/null)"; then
        printf 'CLEANUP FAIL: cannot list test-project networks\n' >&2
        cleanup_failed=1
        ids=""
      fi
      while IFS= read -r id; do
        [[ -n "$id" ]] || continue
        labels="$("${docker_cli[@]}" network inspect \
          --format '{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.network"}}' \
          "$id" 2>/dev/null || true)"
        if [[ "$labels" == "$expected_network|$project|platform" ]]; then
          "${docker_cli[@]}" network rm "$id" >/dev/null \
            || { printf 'CLEANUP FAIL: test-owned network %s\n' "$id" >&2; cleanup_failed=1; }
        else
          printf 'CLEANUP SKIP: network %s ownership could not be verified\n' "$id" >&2
          cleanup_failed=1
        fi
      done <<<"$ids"
    fi
  fi

  if ((cleanup_failed)); then
    printf 'FAIL: one or more test resources could not be safely cleaned up\n' >&2
    ((exit_status != 0)) || exit_status=1
  fi
  exit "$exit_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

assert_project_is_unused
cleanup_enabled=1
"${compose[@]}" up --detach valkey

container_id=""
wait_until_healthy() {
  local attempt running health
  local -a ids=()
  for attempt in $(seq 1 60); do
    mapfile -t ids < <("${compose[@]}" ps --quiet valkey)
    if ((${#ids[@]} == 1)); then
      container_id="${ids[0]}"
      running="$("${docker_cli[@]}" container inspect --format '{{.State.Running}}' "$container_id" 2>/dev/null || true)"
      health="$("${docker_cli[@]}" container inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)"
      if [[ "$running" == "true" && "$health" == "healthy" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  "${compose[@]}" logs valkey >&2 || true
  fail "Valkey did not become healthy in the isolated test project"
}

assert_container_owned() {
  local labels
  labels="$("${docker_cli[@]}" container inspect \
    --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' \
    "$container_id")" || fail "cannot verify the Valkey test container"
  [[ "$labels" == "$project|valkey" ]] || fail "Valkey container ownership label mismatch"
}

valkey() {
  "${compose[@]}" exec -T valkey valkey-cli "$@"
}

wait_until_healthy
assert_container_owned

project_containers="$("${compose[@]}" ps --quiet)"
[[ "$project_containers" == "$container_id" ]] \
  || fail "test project contains an unexpected container"

published_ports="$("${docker_cli[@]}" container inspect \
  --format '{{range $port, $bindings := .NetworkSettings.Ports}}{{if $bindings}}published{{end}}{{end}}' \
  "$container_id")"
[[ -z "$published_ports" ]] || fail "Valkey has a published host port"

volume="$("${docker_cli[@]}" container inspect \
  --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' \
  "$container_id")"
[[ "$volume" == "$expected_volume" ]] || fail "Valkey /data is not using the expected test-owned volume"
volume_labels="$("${docker_cli[@]}" volume inspect \
  --format '{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.volume"}}' \
  "$volume")"
[[ "$volume_labels" == "$expected_volume|$project|platform-valkey-data" ]] \
  || fail "Valkey data volume ownership label mismatch"

network="$("${docker_cli[@]}" container inspect \
  --format '{{range $name, $network := .NetworkSettings.Networks}}{{$name}}{{end}}' \
  "$container_id")"
[[ "$network" == "$expected_network" ]] || fail "Valkey is not on the expected test-only network"
network_labels="$("${docker_cli[@]}" network inspect \
  --format '{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.network"}}' \
  "$network")"
[[ "$network_labels" == "$expected_network|$project|platform" ]] \
  || fail "Valkey network ownership label mismatch"

memory_limit="$("${docker_cli[@]}" container inspect --format '{{.HostConfig.Memory}}' "$container_id")"
[[ "$memory_limit" == "402653184" ]] || fail "expected a 384 MiB container memory limit, got $memory_limit"
restart_policy="$("${docker_cli[@]}" container inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$container_id")"
[[ "$restart_policy" == "unless-stopped" ]] || fail "expected restart policy unless-stopped, got $restart_policy"
[[ "$(valkey --raw CONFIG GET appendonly | tail -n 1)" == "yes" ]] || fail "AOF is not enabled"
[[ "$(valkey --raw CONFIG GET appendfsync | tail -n 1)" == "everysec" ]] || fail "AOF fsync policy is not everysec"
[[ -z "$(valkey --raw CONFIG GET save | sed -n '2p')" ]] || fail "RDB snapshots are enabled; expected AOF-only persistence"
[[ "$(valkey --raw CONFIG GET maxmemory-policy | tail -n 1)" == "noeviction" ]] || fail "unexpected maxmemory policy"
[[ "$(valkey --raw CONFIG GET maxmemory | tail -n 1)" == "268435456" ]] || fail "expected a 256 MiB Valkey maxmemory limit"

key="platform:valkey-recovery:$project"
valkey SET "$key:restart" expected EX 300 >/dev/null
"${compose[@]}" restart valkey
wait_until_healthy
assert_container_owned
[[ "$(valkey --raw GET "$key:restart")" == "expected" ]] || fail "data did not survive a normal restart"

valkey SET "$key:kill" expected EX 300 >/dev/null
sleep 2
# Suppress automatic restart only on this owned test container so exit 137 is
# observable; Compose then recreates it with the declared restart policy.
"${docker_cli[@]}" container update --restart=no "$container_id" >/dev/null
"${docker_cli[@]}" container kill --signal KILL "$container_id" >/dev/null
[[ "$("${docker_cli[@]}" container inspect --format '{{.State.Status}}' "$container_id")" == "exited" ]] \
  || fail "forced kill did not stop the test container"
[[ "$("${docker_cli[@]}" container inspect --format '{{.State.ExitCode}}' "$container_id")" == "137" ]] \
  || fail "forced kill did not produce exit code 137"
"${compose[@]}" up --detach --force-recreate valkey
wait_until_healthy
assert_container_owned
[[ "$(valkey --raw GET "$key:kill")" == "expected" ]] || fail "data did not recover after a forced kill"

valkey DEL "$key:restart" "$key:kill" >/dev/null
printf 'PASS: isolated Valkey project, private port, owned volume/network, configuration, restart, and forced-kill recovery.\n'
