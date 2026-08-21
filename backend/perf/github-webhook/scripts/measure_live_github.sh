#!/usr/bin/env bash
set -euo pipefail

readonly TARGET_REPOSITORY="lunarbae628/devchatGithubApiTest"
readonly REQUIRED_CONFIRMATION="I_UNDERSTAND_THIS_CREATES_WEBHOOKS"
readonly ITERATIONS=50
readonly INTERVAL_SECONDS=3

scenario=${1:-}

if [[ "$scenario" != "baseline" && "$scenario" != "optimized" ]]; then
    echo "usage: $0 <baseline|optimized>" >&2
    exit 2
fi
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "GITHUB_TOKEN must be set" >&2
    exit 2
fi
if [[ -z "${CALLBACK_URL:-}" ]]; then
    echo "CALLBACK_URL must be set" >&2
    exit 2
fi
if [[ "${LIVE_GITHUB_PERF_CONFIRM:-}" != "$REQUIRED_CONFIRMATION" ]]; then
    echo "LIVE_GITHUB_PERF_CONFIRM=$REQUIRED_CONFIRMATION is required" >&2
    exit 2
fi
if [[ "${GITHUB_REPOSITORY:-}" != "$TARGET_REPOSITORY" ]]; then
    echo "this measurement only permits $TARGET_REPOSITORY" >&2
    exit 2
fi

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
results_dir=${RESULTS_DIR:-"$project_root/backend/perf/github-webhook/results"}
curl_bin=${CURL_BIN:-curl}
api_url="https://api.github.com/repos/$TARGET_REPOSITORY"
temp_dir=$(mktemp -d)
current_webhook_id=""
current_callback_url=""
create_elapsed_seconds=""
cleanup_failed_webhook_ids=()
unverified_create_callback_urls=()
durations_ms=()
requests_per_iteration=()
started_at_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
result_path="$results_dir/live-github-$scenario.json"
result_written=false

mkdir -p "$results_dir"

cleanup_current_webhook() {
    if [[ -z "$current_webhook_id" ]]; then
        return
    fi

    if ! "$curl_bin" --fail --silent --show-error \
        --connect-timeout 5 --max-time 20 \
        -X DELETE "$api_url/hooks/$current_webhook_id" \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        -o /dev/null; then
        cleanup_failed_webhook_ids+=("$current_webhook_id")
        current_webhook_id=""
        current_callback_url=""
        return 1
    fi
    current_webhook_id=""
    current_callback_url=""
}

write_result() {
    if [[ "$result_written" == true ]]; then
        return
    fi

    python3 - "$result_path" "$scenario" "$TARGET_REPOSITORY" "$started_at_utc" "$ITERATIONS" "$INTERVAL_SECONDS" \
        "${durations_ms[*]-}" "${requests_per_iteration[*]-}" "${cleanup_failed_webhook_ids[*]-}" \
        "${unverified_create_callback_urls[*]-}" <<'PY'
import json
import sys

result_path, scenario, repository, started_at_utc, planned_iterations, interval, durations, requests, cleanup_failures, unverified_urls = sys.argv[1:]
result = {
    "scenario": scenario,
    "repository": repository,
    "started_at_utc": started_at_utc,
    "planned_iterations": int(planned_iterations),
    "measured_iterations": len(durations.split()),
    "interval_seconds": int(interval),
    "durations_ms": [float(value) for value in durations.split()] if durations else [],
    "requests_per_iteration": [int(value) for value in requests.split()] if requests else [],
    "cleanup_failed_webhook_ids": [int(value) for value in cleanup_failures.split()] if cleanup_failures else [],
    "unverified_create_callback_urls": unverified_urls.split() if unverified_urls else [],
}
with open(result_path, "w") as result_file:
    json.dump(result, result_file, indent=2)
    result_file.write("\n")
PY
    result_written=true
}

cleanup() {
    cleanup_current_webhook || true
    if [[ -n "$current_callback_url" ]]; then
        unverified_create_callback_urls+=("$current_callback_url")
        current_callback_url=""
    fi
    write_result
    rm -rf "$temp_dir"
}
stop_after_interrupt() {
    cleanup
    exit 130
}
trap cleanup EXIT
trap stop_after_interrupt INT TERM

to_millis() {
    python3 - "$@" <<'PY'
import sys

print(round(sum(float(value) for value in sys.argv[1:]) * 1000, 3))
PY
}

create_webhook() {
    local callback_url=$1
    local body_path="$temp_dir/create-response.json"
    local payload
    local elapsed_seconds

    payload=$(CALLBACK_URL="$callback_url" python3 - <<'PY'
import json
import os

print(json.dumps({
    "name": "web",
    "active": True,
    "events": ["issues", "pull_request", "pull_request_review"],
    "config": {
        "url": os.environ["CALLBACK_URL"],
        "content_type": "json",
        "insecure_ssl": "0",
    },
}))
PY
)

    elapsed_seconds=$("$curl_bin" --fail-with-body --silent --show-error \
        --connect-timeout 5 --max-time 20 \
        -X POST "$api_url/hooks" \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        -H "Content-Type: application/json" \
        --data "$payload" \
        -o "$body_path" \
        -w "%{time_total}")

    current_webhook_id=$(python3 - "$body_path" <<'PY'
import json
import sys

with open(sys.argv[1]) as response_file:
    webhook_id = json.load(response_file).get("id")
if not isinstance(webhook_id, int):
    raise SystemExit("GitHub webhook response did not contain a numeric id")
print(webhook_id)
PY
)
    create_elapsed_seconds=$elapsed_seconds
}

for iteration in $(seq 1 "$ITERATIONS"); do
    elapsed_parts=()
    request_count=1
    if [[ "$scenario" == "baseline" ]]; then
        elapsed_parts+=("$("$curl_bin" --fail --silent --show-error \
            --connect-timeout 5 --max-time 20 \
            "$api_url" \
            -H "Authorization: Bearer $GITHUB_TOKEN" \
            -o "$temp_dir/repository-response.json" \
            -w "%{time_total}")")
        request_count=2
    fi

    callback_url="${CALLBACK_URL%/}/$scenario/$iteration"
    current_callback_url="$callback_url"
    create_webhook "$callback_url"
    elapsed_parts+=("$create_elapsed_seconds")
    durations_ms+=("$(to_millis "${elapsed_parts[@]}")")
    requests_per_iteration+=("$request_count")
    if ! cleanup_current_webhook; then
        write_result
        echo "webhook cleanup failed; stopped before the next creation: $result_path" >&2
        exit 1
    fi

    if [[ "$iteration" -lt "$ITERATIONS" ]]; then
        sleep "$INTERVAL_SECONDS"
    fi
done

write_result

echo "$result_path"
