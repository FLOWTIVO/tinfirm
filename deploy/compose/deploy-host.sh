#!/usr/bin/env bash
# Deploys the Compose stack to one Linux host over SSH (single-host installs, e.g. a small VM
# behind a reverse proxy or a tunnel). Builds the server image and the model port's image
# locally for the host's architecture, ships them with `docker save | ssh docker load`,
# copies deploy/compose/, and restarts the stack.
#
#   deploy/compose/deploy-host.sh <user@host> [public-url] [http-port]
#
# First run on a host: generates <remote>/deploy/compose/.env with random secrets (mode 600).
# Later runs keep that .env, switch TINFIRM_SERVER_IMAGE and TINFIRM_MODEL_API_IMAGE to the
# new images, and add LiteLLM's secrets if they are missing. Expose only the server's
# http-port: LiteLLM stays on the host's loopback, and runner hosts reach only the server
# (docs/operations/litellm.md).
# Requires on the host: Docker Engine + Compose v2, passwordless sudo for docker.
# To deploy every commit on main from then on, without this script: auto-deploy/ and
# docs/operations/auto-deploy.md. Pause its timer before a manual deploy with this script.
set -euo pipefail

TARGET="${1:?usage: deploy-host.sh <user@host> [public-url] [http-port]}"
PUBLIC_URL="${2:-}"
HTTP_PORT="${3:-8080}"
REMOTE_DIR="${REMOTE_DIR:-tinfirm}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SSH=(ssh -o BatchMode=yes "$TARGET")

arch="$("${SSH[@]}" uname -m)"
case "$arch" in
  x86_64) platform=linux/amd64 ;;
  aarch64 | arm64) platform=linux/arm64 ;;
  *) echo "unsupported architecture: $arch" >&2; exit 1 ;;
esac

revision="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
git -C "$ROOT" diff --quiet 2>/dev/null || revision="${revision}-dirty"
suffix="$(date -u +%Y%m%d%H%M)-${revision}"
tag="tinfirm-server:$suffix"
model_tag="tinfirm-model-api:$suffix"

echo "==> building $tag for $platform"
docker buildx build --platform "$platform" --build-arg REVISION="$revision" -t "$tag" --load "$ROOT"
echo "==> building $model_tag for $platform"
docker buildx build --platform "$platform" --build-arg REVISION="$revision" -f "$ROOT/model-api/Dockerfile" -t "$model_tag" --load "$ROOT"

echo "==> shipping the images to $TARGET"
docker save --platform "$platform" "$tag" "$model_tag" | gzip -1 | "${SSH[@]}" 'gunzip | sudo -n docker load'

echo "==> copying deploy/compose"
"${SSH[@]}" "mkdir -p ~/$REMOTE_DIR/deploy/compose && chmod 700 ~/$REMOTE_DIR"
rsync -a --exclude .env "$ROOT/deploy/compose/" "$TARGET:$REMOTE_DIR/deploy/compose/"

echo "==> configuring and restarting"
"${SSH[@]}" TAG="$tag" MODEL_TAG="$model_tag" PUBLIC_URL="$PUBLIC_URL" HTTP_PORT="$HTTP_PORT" REMOTE_DIR="$REMOTE_DIR" bash -s <<'REMOTE'
set -euo pipefail
cd ~/"$REMOTE_DIR"/deploy/compose
set_image() { # key value: replaces the line in .env, or appends it to a .env without one
  if grep -q "^$1=" .env; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    [ -z "$(tail -c 1 .env)" ] || echo >> .env
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
}
if [ ! -f .env ]; then
  [ -n "$PUBLIC_URL" ] || { echo "first deployment needs the public URL argument" >&2; exit 1; }
  umask 077
  hex() { openssl rand -hex "$1"; }
  cat > .env <<ENV
# Tinfirm single-host deployment on $(hostname), generated $(date -u +%FT%TZ). Keep secret (mode 600).
POSTGRES_PASSWORD=$(hex 24)
TINFIRM_OWNER_PASSWORD=$(hex 24)
TINFIRM_APP_PASSWORD=$(hex 24)
TINFIRM_HTTP_BIND=0.0.0.0
TINFIRM_HTTP_HOST_PORT=$HTTP_PORT
TINFIRM_DB_HOST_PORT=5432
TINFIRM_PUBLIC_URL=$PUBLIC_URL
TINFIRM_VAULT_KEYS=prod1:$(openssl rand -base64 32)
TINFIRM_VAULT_ACTIVE_KEY=prod1
TINFIRM_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
TINFIRM_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)
TINFIRM_COMPANY_NAME=My Company
TINFIRM_SERVER_IMAGE=$TAG
TINFIRM_MODEL_API_IMAGE=$MODEL_TAG
TINFIRM_SERVER_MEM_LIMIT=1g
TINFIRM_JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError
KEYCLOAK_ADMIN_PASSWORD=$(hex 16)
TINFIRM_OIDC_CLIENT_SECRET=$(hex 16)
TINFIRM_OIDC_TEST_USER_PASSWORD=$(hex 16)
ENV
  chmod 600 .env
  echo "generated .env (edit TINFIRM_BOOTSTRAP_* and TINFIRM_COMPANY_* before the first start if needed)"
else
  set_image TINFIRM_SERVER_IMAGE "$TAG"
  set_image TINFIRM_MODEL_API_IMAGE "$MODEL_TAG"
fi
# LiteLLM's secrets (docs/operations/litellm.md), for a new .env and for a kept one that
# lacks them. Added only when missing: a new salt key would make the stored provider keys
# unreadable.
add_secret() { # key value
  grep -q "^$1=" .env && return 0
  [ -z "$(tail -c 1 .env)" ] || echo >> .env
  printf '%s=%s\n' "$1" "$2" >> .env
  echo "added $1 to .env"
}
add_secret LITELLM_MASTER_KEY "sk-$(openssl rand -hex 32)"
add_secret LITELLM_SALT_KEY "$(openssl rand -hex 32)"
add_secret LITELLM_DB_PASSWORD "$(openssl rand -hex 24)"
# The company of compose.yaml (key `main`, ADR-0075) is named by TINFIRM_COMPANY_NAME. A .env
# may name it TINFIRM_BOOTSTRAP_COMPANY_NAME, which the server does not read: carry
# the name over once, so that the server adopts that company by its name instead of creating
# a second one.
if ! grep -q '^TINFIRM_COMPANY_NAME=' .env && grep -q '^TINFIRM_BOOTSTRAP_COMPANY_NAME=' .env; then
  add_secret TINFIRM_COMPANY_NAME "$(sed -n 's/^TINFIRM_BOOTSTRAP_COMPANY_NAME=//p' .env | tail -n 1)"
fi
sudo -n docker compose -f compose.yaml --env-file .env up -d --no-build --remove-orphans
for _ in $(seq 1 60); do
  port="$(grep '^TINFIRM_HTTP_HOST_PORT=' .env | cut -d= -f2)"
  if curl -fs -o /dev/null "http://127.0.0.1:${port}/api/system/info"; then echo "server is up on port ${port}"; exit 0; fi
  sleep 3
done
echo "server did not become ready; see: sudo docker logs tinfirm-server-1" >&2
exit 1
REMOTE

echo "==> done: $tag, $model_tag"
