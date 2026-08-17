#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ( "$1" != "baseline" && "$1" != "optimized" ) ]]; then
  echo "usage: $0 {baseline|optimized}" >&2
  exit 2
fi

scenario=$1
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
perf_dir=$(cd "$script_dir/.." && pwd)
base_url=${MOCK_GITHUB_BASE_URL:-http://127.0.0.1:18081}
result_dir=${RESULT_DIR:-"$perf_dir/results"}
readonly WARMUP=5
readonly ITERATIONS=30

if [[ ! "$base_url" =~ ^http://127\.0\.0\.1:[0-9]+$ ]]; then
  echo "MOCK_GITHUB_BASE_URL must be a loopback HTTP URL with a port" >&2
  exit 2
fi

mkdir -p "$result_dir"

request_get() {
  curl --fail --silent --show-error --output /dev/null --write-out '%{time_total}' \
    --header 'Authorization: Bearer benchmark-token' \
    "$base_url/repos/owner/repo"
}

request_post() {
  curl --fail --silent --show-error --output /dev/null --write-out '%{time_total}' \
    --request POST \
    --header 'Authorization: Bearer benchmark-token' \
    --header 'Content-Type: application/json' \
    --data '{"name":"web","active":true,"events":["issues"],"config":{"url":"https://devchat.test/github/7","content_type":"json"}}' \
    "$base_url/repos/owner/repo/hooks"
}

run_once() {
  local get_seconds=
  local post_seconds=
  if [[ "$scenario" == "baseline" ]]; then
    get_seconds=$(request_get)
  fi
  post_seconds=$(request_post)
  python3 - "$get_seconds" "$post_seconds" <<'PY'
import sys
print(round(sum(float(value) for value in sys.argv[1:] if value) * 1000, 3))
PY
}

for _ in $(seq 1 "$WARMUP"); do
  run_once >/dev/null
done

durations=()
requests=()
request_count=1
if [[ "$scenario" == "baseline" ]]; then
  request_count=2
fi

for _ in $(seq 1 "$ITERATIONS"); do
  durations+=("$(run_once)")
  requests+=("$request_count")
done

duration_csv=$(IFS=,; echo "${durations[*]}")
request_csv=$(IFS=,; echo "${requests[*]}")
python3 - "$scenario" "$WARMUP" "$ITERATIONS" "$duration_csv" "$request_csv" \
  >"$result_dir/${scenario}.json" <<'PY'
import json
import sys

scenario, warmup, iterations, duration_csv, request_csv = sys.argv[1:]
print(json.dumps({
    "scenario": scenario,
    "warmup_iterations": int(warmup),
    "measured_iterations": int(iterations),
    "durations_ms": [float(value) for value in duration_csv.split(",")],
    "requests_per_iteration": [int(value) for value in request_csv.split(",")],
}, ensure_ascii=False, indent=2))
PY
