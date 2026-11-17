#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/qris-payment-gateway
IMAGE=qris-payment-gateway:latest
CONTAINER=ruangpay

if [[ "${EUID}" -ne 0 ]]; then
  echo "Jalankan dengan sudo: sudo bash deploy/oracle-vps.sh"
  exit 1
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io
systemctl enable --now docker

cd "$(dirname "$0")/.."
docker build -t "$IMAGE" .
docker rm -f "$CONTAINER" 2>/dev/null || true
mkdir -p /opt/ruangpay-data
ENV_ARGS=()
if [[ -f /opt/ruangpay.env ]]; then
  chmod 600 /opt/ruangpay.env
  ENV_ARGS=(--env-file /opt/ruangpay.env)
fi
docker run -d \
  --name "$CONTAINER" \
  --restart unless-stopped \
  "${ENV_ARGS[@]}" \
  -e PORT=8080 \
  -v /opt/ruangpay-data:/data \
  -p 80:8080 \
  "$IMAGE"

echo "RuangPay aktif pada port 80."
docker ps --filter "name=$CONTAINER"
