#!/usr/bin/env bash
set -euo pipefail

# This test exercises the Valkey service declared in docker-compose.yml. The
# placeholders are only used to let Compose interpolate unrelated app services.
export PLATFORM_POSTGRES_PASSWORD="${PLATFORM_POSTGRES_PASSWORD:-local-only}"
export PLATFORM_GATEWAY_CARELOG_BE_URI="${PLATFORM_GATEWAY_CARELOG_BE_URI:-http://carelog-be:8080}"
export PLATFORM_GATEWAY_CARELOG_RAG_URI="${PLATFORM_GATEWAY_CARELOG_RAG_URI:-http://carelog-rag:8000}"
export PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET="${PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET:-local-only}"
export PLATFORM_GATEWAY_INTERNAL_SECRET="${PLATFORM_GATEWAY_INTERNAL_SECRET:-local-only}"

compose=(docker compose -f docker-compose.yml)
key="platform:valkey-recovery:$$"
container=""

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

valkey() {
  "${compose[@]}" exec -T valkey valkey-cli "$@"
}

wait_until_healthy() {
  local attempt
  for attempt in $(seq 1 60); do
    container="$("${compose[@]}" ps -q valkey)"
    if [[ -n "$container" ]] && [[ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || true)" == "true" ]] && [[ "$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)" == "healthy" ]]; then
      return 0
    fi
    sleep 1
  done
  "${compose[@]}" logs valkey >&2
  fail "Valkey did not become healthy"
}

"${compose[@]}" up -d valkey
wait_until_healthy

published_ports="$(docker inspect --format '{{range $port, $bindings := .NetworkSettings.Ports}}{{if $bindings}}published{{end}}{{end}}' "$container")"
[[ -z "$published_ports" ]] || fail "Valkey has a published host port"

volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$container")"
[[ -n "$volume" ]] || fail "Valkey /data is not backed by a named volume"

memory_limit="$(docker inspect --format '{{.HostConfig.Memory}}' "$container")"
[[ "$memory_limit" == "402653184" ]] || fail "Expected a 384 MiB container memory limit, got $memory_limit"
restart_policy="$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$container")"
[[ "$restart_policy" == "unless-stopped" ]] || fail "Expected restart policy unless-stopped, got $restart_policy"
[[ "$(valkey --raw CONFIG GET appendonly | tail -n 1)" == "yes" ]] || fail "AOF is not enabled"
[[ "$(valkey --raw CONFIG GET appendfsync | tail -n 1)" == "everysec" ]] || fail "AOF fsync policy is not everysec"
[[ -z "$(valkey --raw CONFIG GET save | sed -n '2p')" ]] || fail "RDB snapshots are enabled; expected AOF-only persistence"
[[ "$(valkey --raw CONFIG GET maxmemory-policy | tail -n 1)" == "noeviction" ]] || fail "Unexpected maxmemory policy"
[[ "$(valkey --raw CONFIG GET maxmemory | tail -n 1)" == "268435456" ]] || fail "Expected a 256 MiB Valkey maxmemory limit"

valkey SET "$key:restart" expected EX 300 >/dev/null
"${compose[@]}" restart valkey
wait_until_healthy
[[ "$(valkey --raw GET "$key:restart")" == "expected" ]] || fail "Data did not survive a normal restart"

valkey SET "$key:kill" expected EX 300 >/dev/null
# AOF fsyncs every second. Wait for the acknowledged write to cross that
# boundary, then force-stop the container and bring it back through Compose.
sleep 2
docker kill --signal KILL "$container" >/dev/null
[[ "$(docker inspect --format '{{.State.Status}}' "$container")" == "exited" ]] || fail "Forced kill did not stop the Valkey container"
[[ "$(docker inspect --format '{{.State.ExitCode}}' "$container")" == "137" ]] || fail "Forced kill did not produce exit code 137"
"${compose[@]}" up -d valkey
wait_until_healthy
[[ "$(valkey --raw GET "$key:kill")" == "expected" ]] || fail "Data did not recover after a forced kill"

valkey DEL "$key:restart" "$key:kill" >/dev/null
printf 'PASS: Valkey is private, volume-backed, memory-limited, healthy, and recovers after restart and forced kill.\n'
