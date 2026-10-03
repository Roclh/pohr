#!/usr/bin/env bash
# Pohr node agent — runs on EU node every 5 minutes via cron.
# Polls config from RU bridge, reports health.
set -euo pipefail

STATE_FILE="/etc/pohr/node.json"
CONFIG_FILE="/etc/xray/config.json"
PRIVATE_KEY_FILE="/etc/xray/private.key"
XRAY_BIN="/usr/local/xray/xray"

log() { echo "[pohr-agent] $(date -Iseconds) $*"; }
die() { log "ERROR: $*"; exit 1; }

[ -f "$STATE_FILE" ] || die "State file $STATE_FILE not found — run bootstrap first"
[ -f "$CONFIG_FILE" ] || die "Config $CONFIG_FILE not found"
[ -f "$PRIVATE_KEY_FILE" ] || die "Private key $PRIVATE_KEY_FILE not found"

NODE_ID="$(jq -r '.nodeId' "$STATE_FILE")"
NODE_SECRET="$(jq -r '.nodeSecret' "$STATE_FILE")"
RU_URL="$(jq -r '.ruUrl' "$STATE_FILE")"
[ -n "$NODE_ID" ] && [ "$NODE_ID" != "null" ] || die "nodeId missing in state file"

CONFIG_URL="$RU_URL/api/nodes/$NODE_ID/config"
HEALTH_URL="$RU_URL/api/nodes/$NODE_ID/health"

# --- Считаем текущий hash локального конфига ------------------------------

LOCAL_HASH="$(sha256sum "$CONFIG_FILE" | awk '{print $1}')"

# --- Запрос конфига -------------------------------------------------------

HTTP_CODE="$(curl -s -o /tmp/pohr-config.json -w '%{http_code}' \
    -H "X-Node-Secret: $NODE_SECRET" \
    -H "If-None-Match: $LOCAL_HASH" \
    "$CONFIG_URL" || echo "000")"

case "$HTTP_CODE" in
    304)
        log "Config unchanged (304)"
        ;;
    200)
        log "New config received, applying..."
        PRIVATE_KEY="$(cat "$PRIVATE_KEY_FILE")"
        sed "s/__USE_LOCAL__/${PRIVATE_KEY}/" /tmp/pohr-config.json > /tmp/pohr-config-resolved.json

        if ! "$XRAY_BIN" run -test -c /tmp/pohr-config-resolved.json >/dev/null 2>&1; then
            log "ERROR: New config failed validation, keeping old one"
            rm -f /tmp/pohr-config.json /tmp/pohr-config-resolved.json
        else
            mv /tmp/pohr-config-resolved.json "$CONFIG_FILE"
            chmod 644 "$CONFIG_FILE"
            systemctl restart xray
            sleep 2
            if systemctl is-active --quiet xray; then
                log "Xray restarted successfully"
            else
                log "ERROR: Xray failed to start after config update"
            fi
            rm -f /tmp/pohr-config.json
        fi
        ;;
    401)
        die "Authentication failed (401). Check nodeSecret."
        ;;
    404)
        die "Node not found on RU bridge (404). Maybe re-enroll required."
        ;;
    000)
        log "Cannot reach RU bridge ($CONFIG_URL) — network issue, will retry"
        ;;
    *)
        log "Unexpected HTTP $HTTP_CODE from config endpoint"
        ;;
esac

# --- Сбор статуса ---------------------------------------------------------

if systemctl is-active --quiet xray; then
    XRAY_STATUS="healthy"
else
    XRAY_STATUS="unreachable"
fi

XRAY_VERSION="$("$XRAY_BIN" version 2>/dev/null | head -1 | awk '{print $2}' || echo "unknown")"
CONFIG_HASH="$(sha256sum "$CONFIG_FILE" | awk '{print $1}')"
UPTIME="$(awk '{print int($1)}' /proc/uptime 2>/dev/null || echo 0)"

# --- Отправка health ------------------------------------------------------

HEALTH_BODY="$(jq -n \
    --arg status "$XRAY_STATUS" \
    --arg xrayVersion "$XRAY_VERSION" \
    --arg configHash "$CONFIG_HASH" \
    --argjson uptime "$UPTIME" \
    '{status:$status, xrayVersion:$xrayVersion, configHash:$configHash, uptime:$uptime}')"

HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HEALTH_URL" \
    -H "X-Node-Secret: $NODE_SECRET" \
    -H "Content-Type: application/json" \
    -d "$HEALTH_BODY" || echo "000")"

case "$HEALTH_CODE" in
    200) log "Health reported: $XRAY_STATUS, xray=$XRAY_VERSION" ;;
    401) log "Health: auth failed (401)" ;;
    000) log "Health: cannot reach RU bridge" ;;
    *)   log "Health: unexpected HTTP $HEALTH_CODE" ;;
esac

exit 0