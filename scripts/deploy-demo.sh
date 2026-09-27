#!/usr/bin/env bash
# Replace the existing loopback demo container, restoring its exact runtime data and limits.
set -Eeuo pipefail

log() { printf '[deploy-demo] %s\n' "$*"; }
fail() { printf '[deploy-demo] ERROR: %s\n' "$*" >&2; exit 1; }
docker_cmd() { sudo docker "$@"; }

BUILD_NUMBER="${1:-}"
[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || fail 'pass the Jenkins BUILD_NUMBER as the only argument'
IMAGE="cc-agent-java:${BUILD_NUMBER}"
BACKUP_IMAGE='cc-agent-java:previous'
HOST_PORT='18102'
WORKSPACE_VOLUME='cc-agent-java-workspace'
WORKSPACE_MARKER='.cc-agent-java-workspace-migration-v1'

docker_cmd image inspect "$IMAGE" >/dev/null 2>&1 || fail "runtime image $IMAGE is missing"
mapfile -t running_ids < <(docker_cmd ps -q --filter "publish=${HOST_PORT}")
[[ "${#running_ids[@]}" -eq 1 ]] || fail "expected one running container on 127.0.0.1:${HOST_PORT}; found ${#running_ids[@]}"

old_id="${running_ids[0]}"
old_name="$(docker_cmd inspect --format '{{.Name}}' "$old_id" | sed 's#^/##')"
[[ "$old_name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || fail 'existing container name is not safe to reuse'
old_config_image="$(docker_cmd inspect --format '{{.Config.Image}}' "$old_id")"
[[ "$old_config_image" == cc-agent-java:* ]] || fail "container on port ${HOST_PORT} is not a cc-agent-java image"
port_bindings="$(docker_cmd inspect --format '{{range .HostConfig.PortBindings}}{{range .}}{{printf "%s:%s\n" .HostIp .HostPort}}{{end}}{{end}}' "$old_id")"
grep -Fxq "127.0.0.1:${HOST_PORT}" <<<"$port_bindings" || fail "existing container is not bound to loopback port ${HOST_PORT}"
rollback_name="${old_name}-rollback-${BUILD_NUMBER}"
docker_cmd inspect "$rollback_name" >/dev/null 2>&1 && fail "rollback container $rollback_name already exists"

old_image_id="$(docker_cmd inspect --format '{{.Image}}' "$old_id")"
memory_bytes="$(docker_cmd inspect --format '{{.HostConfig.Memory}}' "$old_id")"
memory_swap_bytes="$(docker_cmd inspect --format '{{.HostConfig.MemorySwap}}' "$old_id")"
nano_cpus="$(docker_cmd inspect --format '{{.HostConfig.NanoCpus}}' "$old_id")"
cpu_quota="$(docker_cmd inspect --format '{{.HostConfig.CpuQuota}}' "$old_id")"
cpu_period="$(docker_cmd inspect --format '{{.HostConfig.CpuPeriod}}' "$old_id")"
cpuset_cpus="$(docker_cmd inspect --format '{{.HostConfig.CpusetCpus}}' "$old_id")"
network_mode="$(docker_cmd inspect --format '{{.HostConfig.NetworkMode}}' "$old_id")"
read_only_rootfs="$(docker_cmd inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$old_id")"
restart_policy="$(docker_cmd inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$old_id")"
workspace_mount="$(docker_cmd inspect --format '{{range .Mounts}}{{if eq .Destination "/app/workspace"}}yes{{end}}{{end}}' "$old_id")"

# Preserve environment values without printing them. This file is private to this deploy process
# and is removed on success, failure, or interruption.
umask 077
env_file="$(mktemp "${TMPDIR:-/tmp}/cc-agent-java-env.XXXXXX")"
old_renamed=false
old_stopped=false
new_started=false
deployment_ok=false
volume_created=false
workspace_migration_needed=false
on_exit() {
    rc=$?
    rm -f "$env_file"
    if [[ "$rc" -ne 0 && "$deployment_ok" != true ]]; then
        if [[ "$old_stopped" == true || "$old_renamed" == true ]]; then
            log 'deployment did not pass smoke checks; restoring the previous container'
            if [[ "$new_started" == true ]]; then
                docker_cmd rm -f "$old_name" >/dev/null 2>&1 || true
            fi
            if [[ "$old_renamed" == true ]] && docker_cmd inspect "$rollback_name" >/dev/null 2>&1; then
                docker_cmd rename "$rollback_name" "$old_name" || true
                docker_cmd start "$old_name" >/dev/null || true
            elif [[ "$old_stopped" == true ]]; then
                docker_cmd start "$old_name" >/dev/null || true
            fi
        fi
        if [[ "$volume_created" == true ]]; then
            docker_cmd volume rm "$WORKSPACE_VOLUME" >/dev/null 2>&1 || true
        fi
    fi
    exit "$rc"
}
trap on_exit EXIT

docker_cmd inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$old_id" > "$env_file"

# A pre-existing volume is trusted only when it carries the marker for this exact source container.
# An unmarked volume is never cleared or overwritten; it needs operator review instead.
if [[ "$workspace_mount" != yes ]]; then
    expected_marker="cc-agent-java-workspace-v1 source-container=${old_id}"
    if docker_cmd volume inspect "$WORKSPACE_VOLUME" >/dev/null 2>&1; then
        existing_marker="$(docker_cmd run --rm --user 0:0 \
            --network none \
            --mount "type=volume,source=${WORKSPACE_VOLUME},target=/migration,readonly" \
            --entrypoint sh "$old_config_image" -c "cat /migration/${WORKSPACE_MARKER}" 2>/dev/null || true)"
        [[ "$existing_marker" == "$expected_marker" ]] || fail "volume $WORKSPACE_VOLUME exists without a matching migration marker; refusing to overwrite it"
    else
        docker_cmd volume create "$WORKSPACE_VOLUME" >/dev/null
        volume_created=true
        workspace_migration_needed=true
    fi
fi

docker_cmd image tag "$old_image_id" "$BACKUP_IMAGE"
old_stopped=true
docker_cmd stop --time 20 "$old_name" >/dev/null

if [[ "$workspace_migration_needed" == true ]]; then
    # Stream the stopped container's workspace archive directly into the named volume. No host
    # directory or ordinary temporary data copy is used. The original container layer is untouched.
    docker_cmd cp -a "$old_id:/app/workspace/." - |
        docker_cmd run --rm -i --user 0:0 \
            --network none \
            --mount "type=volume,source=${WORKSPACE_VOLUME},target=/migration" \
            --entrypoint sh "$old_config_image" -c 'tar -xpf - -C /migration && chown 10001:10001 /migration'
    printf '%s\n' "$expected_marker" |
        docker_cmd run --rm -i --user 0:0 \
            --network none \
            --mount "type=volume,source=${WORKSPACE_VOLUME},target=/migration" \
            --entrypoint sh "$old_config_image" -c "cat > /migration/${WORKSPACE_MARKER}"
fi

docker_cmd rename "$old_name" "$rollback_name"
old_renamed=true

run_args=(
    --detach
    --name "$old_name"
    --tmpfs /tmp:size=64m
    --cap-drop ALL
    --security-opt no-new-privileges
    --publish "127.0.0.1:${HOST_PORT}:8080"
    --env-file "$env_file"
)
if [[ "$workspace_mount" == yes ]]; then
    run_args+=(--volumes-from "$rollback_name")
else
    run_args+=(--mount "type=volume,source=${WORKSPACE_VOLUME},target=/app/workspace")
    # Preserve any other existing mounts, such as a separate log directory.
    run_args+=(--volumes-from "$rollback_name")
fi
run_args+=(--restart "${restart_policy:-unless-stopped}")
if [[ "$read_only_rootfs" == true ]]; then
    run_args+=(--read-only)
fi

# Reuse the previous container's cgroup limits rather than guessing a new server budget.
if [[ "$memory_bytes" =~ ^[0-9]+$ ]] && (( memory_bytes > 0 )); then
    run_args+=(--memory "$memory_bytes")
    if [[ "$memory_swap_bytes" =~ ^[0-9]+$ ]] && (( memory_swap_bytes > 0 )); then
        run_args+=(--memory-swap "$memory_swap_bytes")
    fi
fi
if [[ "$nano_cpus" =~ ^[0-9]+$ ]] && (( nano_cpus > 0 )); then
    cpu_limit="$(awk -v n="$nano_cpus" 'BEGIN { printf "%.3f", n / 1000000000 }')"
    run_args+=(--cpus "$cpu_limit")
elif [[ "$cpu_quota" =~ ^[0-9]+$ ]] && (( cpu_quota > 0 )) &&
     [[ "$cpu_period" =~ ^[0-9]+$ ]] && (( cpu_period > 0 )); then
    run_args+=(--cpu-quota "$cpu_quota" --cpu-period "$cpu_period")
fi
if [[ -n "$cpuset_cpus" ]]; then
    run_args+=(--cpuset-cpus "$cpuset_cpus")
fi
if [[ -n "$network_mode" && "$network_mode" != 'default' && "$network_mode" != 'bridge' ]]; then
    run_args+=(--network "$network_mode")
fi

docker_cmd run "${run_args[@]}" "$IMAGE" >/dev/null
new_started=true

http_status() {
    # Return only the status code; never capture or print response bodies or headers.
    local status
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --connect-timeout 1 --max-time 3 "http://127.0.0.1:${HOST_PORT}$1" 2>/dev/null || true)"
    if [[ "$status" =~ ^[0-9]{3}$ ]]; then
        printf '%s' "$status"
    else
        printf '000'
    fi
}

ready=false
last_health='unknown'
last_page_status='000'
last_api_status='000'
smoke_started=$SECONDS
while (( SECONDS - smoke_started < 120 )); do
    last_health="$(docker_cmd inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$old_name" 2>/dev/null || printf 'unavailable')"
    last_page_status="$(http_status '/agent.html')"
    last_api_status="$(http_status '/api/conversations')"
    if [[ "$last_health" == healthy && "$last_page_status" == 200 && "$last_api_status" == 200 ]]; then
        ready=true
        break
    fi
    sleep 2
done
if [[ "$ready" != true ]]; then
    # Report state metadata and HTTP status codes only. Do not dump app logs, response bodies,
    # headers, or container environment into the Jenkins console.
    state_summary="$(docker_cmd inspect --format 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}' "$old_name" 2>/dev/null || printf 'status=unavailable')"
    smoke_elapsed=$((SECONDS - smoke_started))
    fail "smoke check timed out after ${smoke_elapsed}s: health=${last_health} page_http=${last_page_status} api_http=${last_api_status} ${state_summary}"
fi

docker_cmd rm "$rollback_name" >/dev/null
deployment_ok=true
log "deployed $IMAGE on 127.0.0.1:${HOST_PORT}; previous image retained as $BACKUP_IMAGE"
