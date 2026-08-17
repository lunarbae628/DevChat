#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
script_path="$project_root/backend/perf/github-webhook/scripts/measure_live_github.sh"
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

fake_curl="$temp_dir/curl"
curl_marker="$temp_dir/curl-called"

cat > "$fake_curl" <<'EOF'
#!/usr/bin/env bash
touch "${CURL_MARKER:?}"
exit 99
EOF
chmod +x "$fake_curl"

assert_rejected_before_http() {
    local name=$1
    local expected_message=$2
    shift 2

    if CURL_BIN="$fake_curl" CURL_MARKER="$curl_marker" "$@" >"$temp_dir/$name.out" 2>"$temp_dir/$name.err"; then
        echo "$name should fail before HTTP" >&2
        exit 1
    fi
    if [[ -e "$curl_marker" ]]; then
        echo "$name called curl before rejecting input" >&2
        exit 1
    fi
    if ! rg --fixed-strings --quiet "$expected_message" "$temp_dir/$name.err"; then
        echo "$name did not explain the rejected guard" >&2
        exit 1
    fi
}

assert_rejected_before_http missing_confirmation LIVE_GITHUB_PERF_CONFIRM \
    env GITHUB_TOKEN=dummy \
        GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest \
        CALLBACK_URL=https://example.com/devchat-github-api-perf \
        bash "$script_path" optimized

assert_rejected_before_http unexpected_repository lunarbae628/devchatGithubApiTest \
    env GITHUB_TOKEN=dummy \
        GITHUB_REPOSITORY=someone/else \
        CALLBACK_URL=https://example.com/devchat-github-api-perf \
        LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS \
        bash "$script_path" optimized

fake_live_curl="$temp_dir/live-curl"
fake_sleep="$temp_dir/sleep"
curl_log="$temp_dir/curl.log"

cat > "$fake_live_curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

method=GET
output_path=""
for ((index = 1; index <= $#; index++)); do
    case "${!index}" in
        -X)
            next=$((index + 1))
            method=${!next}
            ;;
        -o)
            next=$((index + 1))
            output_path=${!next}
            ;;
    esac
done
printf '%s\n' "$method" >> "${CURL_LOG:?}"
if [[ "$method" == "POST" ]]; then
    printf '{"id":42}' > "$output_path"
    printf '0.010'
    exit 0
fi
if [[ "$method" == "DELETE" ]]; then
    exit 1
fi
printf '0.010'
EOF
chmod +x "$fake_live_curl"

cat > "$fake_sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$fake_sleep"

if PATH="$temp_dir:$PATH" CURL_BIN="$fake_live_curl" CURL_LOG="$curl_log" RESULTS_DIR="$temp_dir/results" \
    GITHUB_TOKEN=dummy \
    GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest \
    CALLBACK_URL=https://example.com/devchat-github-api-perf \
    LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS \
    bash "$script_path" optimized; then
    echo "cleanup failure should stop the measurement" >&2
    exit 1
fi
if [[ $(wc -l < "$curl_log") -ne 2 ]]; then
    echo "cleanup failure should not allow another POST" >&2
    exit 1
fi
python3 - "$temp_dir/results/live-github-optimized.json" <<'PY'
import json
import sys

with open(sys.argv[1]) as result_file:
    result = json.load(result_file)

assert result["planned_iterations"] == 50
assert result["measured_iterations"] == 1
assert result["cleanup_failed_webhook_ids"] == [42]
PY

fake_success_curl="$temp_dir/success-curl"
cat > "$fake_success_curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

method=GET
output_path=""
for ((index = 1; index <= $#; index++)); do
    case "${!index}" in
        -X)
            next=$((index + 1))
            method=${!next}
            ;;
        -o)
            next=$((index + 1))
            output_path=${!next}
            ;;
    esac
done
if [[ "$method" == "POST" ]]; then
    printf '{"id":42}' > "$output_path"
    printf '0.010'
    exit 0
fi
if [[ "$method" == "GET" ]]; then
    printf '{}' > "$output_path"
    printf '0.010'
fi
EOF
chmod +x "$fake_success_curl"

PATH="$temp_dir:$PATH" CURL_BIN="$fake_success_curl" RESULTS_DIR="$temp_dir/success-results" \
    GITHUB_TOKEN=dummy \
    GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest \
    CALLBACK_URL=https://example.com/devchat-github-api-perf \
    LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS \
    bash "$script_path" baseline > /dev/null

python3 - "$temp_dir/success-results/live-github-baseline.json" <<'PY'
import json
import sys

with open(sys.argv[1]) as result_file:
    result = json.load(result_file)

assert result["planned_iterations"] == 50
assert result["measured_iterations"] == 50
assert len(result["durations_ms"]) == 50
assert set(result["requests_per_iteration"]) == {2}
assert result["cleanup_failed_webhook_ids"] == []
PY
