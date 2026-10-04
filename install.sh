#!/usr/bin/env bash
set -euo pipefail

# Pohr RU-bridge installer
# Usage: bash install.sh

IMAGE="${POHR_IMAGE:-roclh/pohr:latest}"
INSTALL_DIR="${POHR_DIR:-/opt/pohr}"
PORT="${POHR_PORT:-8080}"
VPN_PORT="${POHR_VPN_PORT:-8443}"
POHR_TELEMT_PORT=3128

log() { echo -e "\033[1;36m[pohr-install]\033[0m $*"; }
die() { echo -e "\033[1;31m[pohr-install] ERROR:\033[0m $*" >&2; exit 1; }

[ "$EUID" -eq 0 ] || die "Run as root (or use sudo)"

# --- Docker ---
if ! command -v docker >/dev/null 2>&1; then
    log "Docker not found, installing..."
    curl -fsSL https://get.docker.com | sh
fi
systemctl enable docker >/dev/null 2>&1 || true
systemctl start docker

# --- Install dir ---
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

# --- .env ---
if [ ! -f .env ]; then
    HOST_IP="$(hostname -I | awk '{print $1}')"
    cat > .env <<EOF
# Pohr configuration
# URL that clients and EU nodes use to reach this bridge.
# Change to https://pohr.example.com if you have a domain + TLS proxy in front.
POHR_PUBLIC_URL=http://${HOST_IP}:${PORT}

# Port for the Web UI / REST API
POHR_PORT=${PORT}

# Port that Xray on this bridge listens on for VPN clients
POHR_VPN_PORT=${VPN_PORT}

# Multiline list of SSH public keys to install on every enrolled EU node.
# Each key on its own line, empty is fine.
POHR_SSH_AUTHORIZED_KEYS=

# Xray auto-install on first boot (true/false)
XRAY_AUTO_INSTALL=true
EU_XRAY_VERSION=latest
EOF
    log "Created $INSTALL_DIR/.env"
    log "  → Review POHR_PUBLIC_URL and POHR_SSH_AUTHORIZED_KEYS before going live"
else
    log ".env already exists, keeping it"
fi

# --- docker-compose.yml ---
cat > docker-compose.yml <<EOF
services:
  pohr:
    image: ${IMAGE}
    container_name: pohr
    ports:
      - "\${POHR_PORT:-8080}:8080"
      - "\${POHR_VPN_PORT:-8443}:8443"
    volumes:
      - pohr-data:/app/data
      - pohr-xray:/app/xray
      - pohr-scripts:/app/scripts
    env_file:
      - .env
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    restart: unless-stopped

volumes:
  pohr-data:
  pohr-xray:
  pohr-scripts:
EOF

# --- Pull + up ---
log "Pulling ${IMAGE}..."
docker pull "$IMAGE"

log "Starting Pohr..."
docker compose up -d

sleep 4

if docker ps --filter name=pohr --format '{{.Status}}' | grep -q "Up"; then
    HOST_IP="$(hostname -I | awk '{print $1}')"
    echo
    log "=== Pohr is running ==="
    log "Web UI:   http://${HOST_IP}:${PORT}/"
    log "VPN port: ${HOST_IP}:${VPN_PORT}"
    log "Admin:    admin / changeme"
    log "          CHANGE THE PASSWORD IMMEDIATELY after first login"
    echo
    log "Logs:     docker logs -f pohr"
    log "Restart:  cd $INSTALL_DIR && docker compose restart"
    log "Update:   docker pull $IMAGE && docker compose up -d"
else
    echo
    die "Container failed to start. Check logs: docker logs pohr"
fi