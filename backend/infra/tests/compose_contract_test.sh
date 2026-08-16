#!/usr/bin/env bash
set -euo pipefail

INFRA_DIR=$(cd "$(dirname "$0")/.." && pwd)
CONFIG_JSON=$(docker compose \
  --env-file "$INFRA_DIR/.env.example" \
  --profile blue \
  --profile green \
  -f "$INFRA_DIR/docker-compose.yml" \
  config --format json)

CONFIG_JSON_WITH_DISCORD_WEBHOOK=$(NOTIFICATION_DELIVERY_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/test/token \
  docker compose \
  --env-file "$INFRA_DIR/.env.example" \
  --profile blue \
  --profile green \
  -f "$INFRA_DIR/docker-compose.yml" \
  config --format json)

python3 -c '
import json
import sys

config = json.load(sys.stdin)
services = config["services"]

required = {"devchat-mysql", "devchat-redis", "devchat-app-blue", "devchat-app-green"}
assert required <= set(services), f"서비스 누락: {required - set(services)}"

for color in ("blue", "green"):
    app = services[f"devchat-app-{color}"]
    assert app["container_name"] == f"devchat-app-{color}"
    assert app["profiles"] == [color]
    assert app["mem_limit"] == "1610612736"
    assert app["environment"]["JAVA_OPTS"] == "-Xms256m -Xmx1g"
    assert app["environment"]["NOTIFICATION_DELIVERY_DISCORD_WEBHOOK_URL"] == ""
    assert set(app["networks"].keys()) == {"devchat_internal", "devchat_proxy_net"}

print("Compose Blue/Green 계약 통과")
' <<<"$CONFIG_JSON"

python3 -c '
import json
import sys

services = json.load(sys.stdin)["services"]
for color in ("blue", "green"):
    value = services[f"devchat-app-{color}"]["environment"]["NOTIFICATION_DELIVERY_DISCORD_WEBHOOK_URL"]
    assert value == "https://discord.com/api/webhooks/test/token"

print("Compose Discord webhook 전달 계약 통과")
' <<<"$CONFIG_JSON_WITH_DISCORD_WEBHOOK"
