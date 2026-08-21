#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
perf_dir=$(cd "$script_dir/.." && pwd)
result_dir=$(mktemp -d)
server_log=$(mktemp)
mock_port=$(python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)
server_pid=

cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$result_dir"
  rm -f "$server_log"
}
trap cleanup EXIT

MOCK_GITHUB_PORT="$mock_port" MOCK_GITHUB_DELAY_MS=10 \
  python3 "$perf_dir/scripts/mock_github_server.py" >"$server_log" 2>&1 &
server_pid=$!

for _ in {1..30}; do
  if kill -0 "$server_pid" 2>/dev/null \
    && curl --fail --silent "http://127.0.0.1:${mock_port}/health" >/dev/null; then
    break
  fi
  sleep 0.1
done

if ! kill -0 "$server_pid" 2>/dev/null; then
  cat "$server_log" >&2
  exit 1
fi
curl --fail --silent "http://127.0.0.1:${mock_port}/health" >/dev/null

MOCK_GITHUB_BASE_URL="http://127.0.0.1:${mock_port}" RESULT_DIR="$result_dir" \
  bash "$perf_dir/scripts/measure.sh" baseline
MOCK_GITHUB_BASE_URL="http://127.0.0.1:${mock_port}" RESULT_DIR="$result_dir" \
  bash "$perf_dir/scripts/measure.sh" optimized

python3 - "$result_dir/baseline.json" "$result_dir/optimized.json" <<'PY'
import json
import sys

baseline, optimized = (json.load(open(path, encoding="utf-8")) for path in sys.argv[1:])

assert len(baseline["durations_ms"]) == 30
assert len(optimized["durations_ms"]) == 30
assert set(baseline["requests_per_iteration"]) == {2}
assert set(optimized["requests_per_iteration"]) == {1}
assert min(baseline["durations_ms"]) >= 15
assert min(optimized["durations_ms"]) >= 8
PY
