#!/usr/bin/env bash
# Pohr EU node bootstrap script
# Usage: curl -sL {{RU_URL}}/api/nodes/bootstrap.sh?token=XXX | sudo bash
set -euo pipefail

ENROLL_TOKEN="{{ENROLL_TOKEN}}"
RU_URL="{{RU_URL}}"
SSH_KEYS_PAYLOAD="{{SSH_KEYS}}"
XRAY_VERSION="{{XRAY_VERSION}}"
NODE_PORT="{{NODE_PORT}}"

LOG_FILE="/var/log/pohr-setup.log"
mkdir -p "$(dirname "$LOG_FILE")"
exec > >(tee -a "$LOG_FILE") 2>&1

log() { echo "[pohr-setup] $*"; }
die() { echo "[pohr-setup] ERROR: $*" >&2; exit 1; }

# --- Проверки --------------------------------------------------------------

[ "$EUID" -eq 0 ] || die "Run as root (use sudo)"

ARCH_RAW="$(uname -m)"
case "$ARCH_RAW" in
    x86_64|amd64) XRAY_ARCH="64" ;;
    aarch64|arm64) XRAY_ARCH="arm64-v8a" ;;
    *) die "Unsupported architecture: $ARCH_RAW" ;;
esac
log "Detected arch: $ARCH_RAW → Xray-linux-${XRAY_ARCH}"

# --- Пакетный менеджер ----------------------------------------------------

if command -v apt-get >/dev/null 2>&1; then
    PKG_INSTALL="apt-get install -y"
    export DEBIAN_FRONTEND=noninteractive
elif command -v dnf >/dev/null 2>&1; then
    PKG_INSTALL="dnf install -y"
elif command -v yum >/dev/null 2>&1; then
    PKG_INSTALL="yum install -y"
else
    die "No supported package manager (apt/dnf/yum)"
fi

ensure_pkg() {
    local cmd="$1"; shift
    if ! command -v "$cmd" >/dev/null 2>&1; then
        log "Installing dependencies for $cmd..."
        $PKG_INSTALL "$@" >/dev/null 2>&1 || die "Failed to install $*"
    fi
}

ensure_pkg curl curl
ensure_pkg unzip unzip
ensure_pkg jq jq
ensure_pkg openssl openssl
log "Dependencies OK"

# --- Полная очистка предыдущей установки ----------------------------------

log "Cleaning up previous xray installation..."
systemctl stop xray 2>/dev/null || true
systemctl disable xray 2>/dev/null || true
rm -f /etc/systemd/system/xray*.service
systemctl daemon-reload
rm -rf /usr/local/xray /opt/xray /etc/xray /var/log/xray
rm -f /usr/local/bin/xray
# Убираем наши старые cron-записи
(crontab -l 2>/dev/null | grep -v 'pohr-agent' || true) | crontab - 2>/dev/null || true
log "Cleanup done"

# --- Firewall: убираем старое правило на порт Xray ------------------------

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw delete allow "${NODE_PORT}/tcp" 2>/dev/null || true
elif command -v iptables >/dev/null 2>&1; then
    iptables -D INPUT -p tcp --dport "$NODE_PORT" -j ACCEPT 2>/dev/null || true
fi

# --- Скачивание Xray ------------------------------------------------------

if [ "$XRAY_VERSION" = "latest" ] || [ -z "$XRAY_VERSION" ]; then
    log "Resolving latest Xray version..."
    XRAY_VERSION="$(curl -fsSL https://api.github.com/repos/XTLS/Xray-core/releases/latest | jq -r '.tag_name' | sed 's/^v//')"
    [ -n "$XRAY_VERSION" ] && [ "$XRAY_VERSION" != "null" ] || die "Cannot resolve latest Xray version"
fi
log "Installing Xray $XRAY_VERSION"

XRAY_URL="https://github.com/XTLS/Xray-core/releases/download/v${XRAY_VERSION}/Xray-linux-${XRAY_ARCH}.zip"
TMP_ZIP="$(mktemp --suffix=.zip)"
curl -fsSL "$XRAY_URL" -o "$TMP_ZIP" || die "Download failed: $XRAY_URL"

mkdir -p /usr/local/xray /etc/xray
unzip -q -o "$TMP_ZIP" -d /usr/local/xray/
rm -f "$TMP_ZIP"
chmod +x /usr/local/xray/xray
/usr/local/xray/xray version | head -1
log "Xray binary installed at /usr/local/xray/xray"

# --- Генерация Reality keypair локально -----------------------------------

log "Generating Reality keypair..."
X25519_OUT="$(/usr/local/xray/xray x25519)"
PRIVATE_KEY="$(echo "$X25519_OUT" | grep -E 'PrivateKey|Private key' | awk '{print $NF}')"
PUBLIC_KEY="$(echo "$X25519_OUT" | grep -E 'Password|Public key' | awk '{print $NF}')"
[ -n "$PRIVATE_KEY" ] && [ -n "$PUBLIC_KEY" ] || die "Failed to parse x25519 output: $X25519_OUT"

SHORT_ID="$(openssl rand -hex 8)"
echo "$PRIVATE_KEY" > /etc/xray/private.key
chmod 600 /etc/xray/private.key
log "Keypair generated (pub=${PUBLIC_KEY:0:16}..., sid=$SHORT_ID)"

# --- Внешний IP -----------------------------------------------------------

log "Detecting public IP..."
PUBLIC_IP="$(curl -fsSL https://api.ipify.org || true)"
[ -n "$PUBLIC_IP" ] || die "Cannot determine public IP"
log "Public IP: $PUBLIC_IP"

# --- Enroll на RU-мост ----------------------------------------------------

log "Enrolling with RU bridge at $RU_URL..."
REGISTER_BODY="$(jq -n \
    --arg token "$ENROLL_TOKEN" \
    --arg host "$PUBLIC_IP" \
    --argjson port "$NODE_PORT" \
    --arg publicKey "$PUBLIC_KEY" \
    --arg shortId "$SHORT_ID" \
    --arg xrayVersion "$XRAY_VERSION" \
    '{token:$token, host:$host, port:$port, publicKey:$publicKey, shortId:$shortId, xrayVersion:$xrayVersion, agentVersion:"1.0.0"}')"

REGISTER_RESP="$(curl -fsSL -X POST "$RU_URL/api/nodes/register" \
    -H "Content-Type: application/json" \
    -d "$REGISTER_BODY")" || die "Registration request failed"

NODE_ID="$(echo "$REGISTER_RESP" | jq -r '.nodeId')"
NODE_SECRET="$(echo "$REGISTER_RESP" | jq -r '.nodeSecret')"
[ "$NODE_ID" != "null" ] && [ -n "$NODE_ID" ] || die "Register response missing nodeId: $REGISTER_RESP"
[ "$NODE_SECRET" != "null" ] && [ -n "$NODE_SECRET" ] || die "Register response missing nodeSecret"

# Сохраняем state — понадобится agent'у
mkdir -p /etc/pohr
cat > /etc/pohr/node.json <<EOF
{
  "nodeId": "$NODE_ID",
  "nodeSecret": "$NODE_SECRET",
  "ruUrl": "$RU_URL",
  "nodePort": $NODE_PORT
}
EOF
chmod 600 /etc/pohr/node.json
log "Enrolled as node $NODE_ID"

# --- Сохранение конфига ---------------------------------------------------

echo "$REGISTER_RESP" | jq -r '.config' \
    | sed "s/__USE_LOCAL__/${PRIVATE_KEY}/" \
    > /etc/xray/config.json

/usr/local/xray/xray run -test -c /etc/xray/config.json >/dev/null 2>&1 \
    || die "Config validation failed. Config saved at /etc/xray/config.json"
log "Config written and validated"

# --- Systemd unit ---------------------------------------------------------

log "Creating systemd service..."
cat > /etc/systemd/system/xray.service <<'EOF'
[Unit]
Description=Xray Service
After=network.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/xray/xray run -c /etc/xray/config.json
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable xray >/dev/null 2>&1
systemctl start xray
sleep 2
systemctl is-active --quiet xray || {
    journalctl -u xray -n 50 --no-pager || true
    die "Xray service failed to start"
}
log "Xray service active"

# --- Firewall: открыть порт -----------------------------------------------

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow "${NODE_PORT}/tcp" >/dev/null 2>&1 || true
elif command -v iptables >/dev/null 2>&1; then
    iptables -I INPUT -p tcp --dport "$NODE_PORT" -j ACCEPT 2>/dev/null || true
fi
log "Firewall rule added for port $NODE_PORT"

# --- SSH authorized_keys --------------------------------------------------

if [ -n "$SSH_KEYS_PAYLOAD" ]; then
    log "Adding SSH keys..."
    mkdir -p /root/.ssh
    chmod 700 /root/.ssh
    touch /root/.ssh/authorized_keys
    while IFS= read -r key; do
        [ -z "$key" ] && continue
        grep -qF "$key" /root/.ssh/authorized_keys || echo "$key" >> /root/.ssh/authorized_keys
    done <<EOF
$SSH_KEYS_PAYLOAD
EOF
    chmod 600 /root/.ssh/authorized_keys
    log "SSH keys installed for root"
fi

# --- Установка pohr-agent -------------------------------------------------

log "Installing pohr-agent..."
curl -fsSL "$RU_URL/api/nodes/agent.sh" -o /usr/local/bin/pohr-agent.sh
chmod +x /usr/local/bin/pohr-agent.sh

# Cron: каждые 5 минут
CRON_LINE="*/5 * * * * /usr/local/bin/pohr-agent.sh >> /var/log/pohr-agent.log 2>&1"
(crontab -l 2>/dev/null | grep -v 'pohr-agent' || true; echo "$CRON_LINE") | crontab -
log "pohr-agent installed, will run every 5 minutes"

# --- Первый health push ---------------------------------------------------

log "Sending initial health report..."
/usr/local/bin/pohr-agent.sh || log "Initial agent run failed, but cron will retry"

# --- Финальный health-check -----------------------------------------------

if ss -tlnp 2>/dev/null | grep -q ":${NODE_PORT}"; then
    log "Xray listening on :$NODE_PORT"
else
    log "WARNING: Xray does not appear to listen on :$NODE_PORT"
fi

log "=== Setup complete ==="
log "Node ID:  $NODE_ID"
log "Public:   $PUBLIC_IP:$NODE_PORT"
log "Backup your /etc/xray/private.key file!"
log ""
log "You can now see this node in the Pohr admin panel."