#!/usr/bin/env bash
# Start a server image against PostgreSQL with the Compose file and development defaults, wait until the server
# answers GET /api/system/info, and tear everything down. A startup failure of the packaged image (a class missing
# from the jar, a bean that cannot be created, a migration that fails) shows here and nowhere in the unit tests,
# which run on the test classpath (CONTRIBUTING §5; CI's image job).
#
#   deploy/compose/smoke.sh <image> [timeout-seconds]
#
# Uses its own Compose project (tinfirm-smoke), ports on 127.0.0.1 chosen at random, and a throwaway database
# volume, so it never touches a developer's `make up` stack.
set -euo pipefail

image="${1:?usage: smoke.sh <image> [timeout-seconds]}"
timeout="${2:-180}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project="tinfirm-smoke-$$"
http_port=$((20000 + RANDOM % 20000))
db_port=$((40000 + RANDOM % 20000))
env_file="$(mktemp)"

# shellcheck disable=SC2329  # invoked by the trap below
cleanup() {
  docker compose --project-name "$project" --env-file "$env_file" -f "$here/compose.yaml" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$env_file"
}
trap cleanup EXIT

# The development defaults of .env.example, the image under test, and random local ports.
grep -vE '^\s*(#|$)' "$here/.env.example" > "$env_file"
{
  echo "TINFIRM_SERVER_IMAGE=$image"
  echo "TINFIRM_HTTP_HOST_PORT=$http_port"
  echo "TINFIRM_DB_HOST_PORT=$db_port"
  echo "TINFIRM_PUBLIC_URL=http://localhost:$http_port"
} >> "$env_file"

echo "smoke: starting $image as project $project on 127.0.0.1:$http_port"
docker compose --project-name "$project" --env-file "$env_file" -f "$here/compose.yaml" up -d --no-build postgres server

deadline=$((SECONDS + timeout))
while (( SECONDS < deadline )); do
  if curl -fsS --max-time 5 "http://127.0.0.1:$http_port/api/system/info" >/dev/null 2>&1; then
    echo "smoke: the server answers /api/system/info"
    exit 0
  fi
  state="$(docker compose --project-name "$project" --env-file "$env_file" -f "$here/compose.yaml" ps --format '{{.Service}} {{.State}}' server 2>/dev/null || true)"
  if [[ "$state" == *exited* ]]; then
    echo "smoke: the server container exited" >&2
    break
  fi
  sleep 3
done

echo "smoke: the server did not answer within ${timeout}s; its last log lines:" >&2
docker compose --project-name "$project" --env-file "$env_file" -f "$here/compose.yaml" logs --no-color --tail=60 server >&2 || true
exit 1
