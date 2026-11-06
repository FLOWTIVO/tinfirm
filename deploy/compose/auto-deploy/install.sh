#!/usr/bin/env bash
# Installs tinfirm-auto-deploy on a single-host Compose install (docs/operations/auto-deploy.md):
#
#   sudo deploy/compose/auto-deploy/install.sh [--user <deploy user>] [--runner-unit <unit>]
#
# Idempotent; run it again after this directory changed. It
#   - installs tinfirm-auto-deploy.sh from this directory as /usr/local/bin/tinfirm-auto-deploy;
#   - installs tinfirm-auto-deploy.service (User= the deploy user, sandboxed: NoNewPrivileges,
#     ProtectSystem=full, PrivateTmp) and .timer, and enables and starts the timer;
#   - creates the deploy user's ~/.config/tinfirm-auto-deploy/env from env.example, unless it
#     exists;
#   - copies the public keys cosign.pub (releases) and cosign-rolling.pub (the rolling
#     prerelease) from this directory into ~/.config/tinfirm-auto-deploy/, where every pass
#     checks the signature of SHA256SUMS against them (printing their fingerprints: compare
#     them with the published ones before you rely on them);
#   - when the runner unit (default tinfirm-runner) exists, installs
#     tinfirm-auto-deploy-reload.path and .service, which reload exactly that unit as root
#     when a deploy asks (the service's NoNewPrivileges rules out sudo), and removes the
#     file /etc/sudoers.d/tinfirm-auto-deploy if there is one;
#   - prints what is left to do by hand: the token, and the optional baseline revision and
#     registry login.
#
# The deploy user defaults to the user who ran sudo. It must be in the docker group.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
user="${SUDO_USER:-}"
unit=tinfirm-runner

usage() { sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
  case "$1" in
    --user) user="${2:?--user needs a user name}"; shift 2 ;;
    --runner-unit) unit="${2:?--runner-unit needs a unit name}"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
unit="${unit%.service}"

fail() { echo "install.sh: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || fail "run it as root: sudo $0"
[ -n "$user" ] && [ "$user" != root ] || fail "name the deploy user with --user <name> (not root)"
[[ $user =~ ^[a-z_][a-z0-9_.-]*$ ]] || fail "unexpected user name '$user'"
[[ $unit =~ ^[A-Za-z0-9@_.:-]+$ ]] || fail "unexpected unit name '$unit'"
id "$user" >/dev/null 2>&1 || fail "no user '$user'"
home="$(getent passwd "$user" | cut -d: -f6)"
[ -d "$home" ] || fail "the home directory of $user ($home) does not exist"

missing=()
for cmd in docker curl jq flock sha256sum tar awk cmp timeout systemctl runuser openssl; do
  command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
done
if ((${#missing[@]} > 0)); then
  fail "missing commands: ${missing[*]} (Ubuntu/Debian: sudo apt-get install -y jq curl util-linux diffutils openssl)"
fi
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 (docker compose) is missing"

echo "==> /usr/local/bin/tinfirm-auto-deploy"
install -m 0755 "$here/tinfirm-auto-deploy.sh" /usr/local/bin/tinfirm-auto-deploy

echo "==> systemd units (User=$user)"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
sed "s|@USER@|$user|g" "$here/tinfirm-auto-deploy.service" > "$tmp"
install -m 0644 "$tmp" /etc/systemd/system/tinfirm-auto-deploy.service
install -m 0644 "$here/tinfirm-auto-deploy.timer" /etc/systemd/system/tinfirm-auto-deploy.timer

echo "==> settings"
config="$home/.config/tinfirm-auto-deploy"
runuser -u "$user" -- mkdir -p "$config"
chmod 700 "$config"
if [ -f "$config/env" ]; then
  echo "    $config/env exists; left as it is"
else
  runuser -u "$user" -- install -m 0600 "$here/env.example" "$config/env"
  if [ "$unit" != tinfirm-runner ]; then
    echo "AUTO_DEPLOY_RUNNER_UNIT=$unit" >> "$config/env"
  fi
  echo "    created $config/env (defaults)"
fi

echo "==> public keys of the signed assets"
for key in cosign.pub cosign-rolling.pub; do
  runuser -u "$user" -- install -m 0644 "$here/$key" "$config/$key"
  if grep -q PLACEHOLDER "$config/$key"; then
    echo "    $config/$key: placeholder (no key; see the file)"
  else
    echo "    $config/$key: sha256 $(openssl pkey -pubin -in "$config/$key" -outform DER | sha256sum | cut -d' ' -f1)"
  fi
done

echo "==> runner host unit $unit"
load="$(systemctl show -p LoadState --value "$unit" 2>/dev/null || true)"
if [ "$load" = loaded ]; then
  systemctl_path="$(command -v systemctl)"
  state_dir="$home/.local/share/tinfirm-auto-deploy"
  runuser -u "$user" -- mkdir -p "$state_dir"
  sed "s|@STATE_DIR@|$state_dir|g" "$here/tinfirm-auto-deploy-reload.path" > "$tmp"
  install -m 0644 "$tmp" /etc/systemd/system/tinfirm-auto-deploy-reload.path
  sed -e "s|@SYSTEMCTL@|$systemctl_path|g" -e "s|@UNIT@|$unit|g" \
    "$here/tinfirm-auto-deploy-reload.service" > "$tmp"
  install -m 0644 "$tmp" /etc/systemd/system/tinfirm-auto-deploy-reload.service
  reload_path=1
  echo "    installed tinfirm-auto-deploy-reload.path: a deploy that installs a new runner host"
  echo "    binary asks it to run 'systemctl reload $unit' as root"
  if [ -f /etc/sudoers.d/tinfirm-auto-deploy ]; then
    rm -f /etc/sudoers.d/tinfirm-auto-deploy
    echo "    removed /etc/sudoers.d/tinfirm-auto-deploy (the service runs with NoNewPrivileges; sudo cannot work there)"
  fi
else
  reload_path=0
  echo "    no unit $unit here: auto-deploy updates the runner host only where its binary exists"
  echo "    (AUTO_DEPLOY_RUNNER_BIN), and restarts nothing. Run install.sh again once the unit exists."
fi

echo "==> timer"
systemctl daemon-reload
if ((reload_path)); then
  systemctl enable --now tinfirm-auto-deploy-reload.path
fi
systemctl enable --now tinfirm-auto-deploy.timer
systemctl list-timers --no-pager tinfirm-auto-deploy.timer || true

warnings=()
if [[ " $(id -nG "$user") " != *" docker "* ]]; then
  warnings+=("$user is not in the docker group, so every pass fails. Add it (Docker access is root-equivalent): sudo usermod -aG docker $user")
fi
if [ ! -s "$home/.config/tinfirm-auto-deploy/github-token" ]; then token_todo=1; else token_todo=0; fi
if grep -qs '"ghcr.io"' "$home/.docker/config.json"; then ghcr_todo=0; else ghcr_todo=1; fi

cat <<EOF

Installed. Every 5 minutes the timer runs tinfirm-auto-deploy as $user.

Left to do, as $user:
  1. Optional, before the token: if the server already runs the revision you want as the
     baseline, record it, so that the first pass does not deploy it again:
       tinfirm-auto-deploy mark-deployed <full 40-character commit SHA>
     Otherwise the first pass deploys the newest rolling build.
EOF
if ((token_todo)); then
  cat <<EOF
  2. The release assets, past the GitHub API's 60 requests an hour without a token:
     a fine-grained personal access token, repository access "Public repositories
     (read-only)". Store it (paste, Enter, Ctrl-D):
       (umask 077; cat > ~/.config/tinfirm-auto-deploy/github-token)
EOF
else
  echo "  2. done: ~/.config/tinfirm-auto-deploy/github-token exists"
fi
if ((ghcr_todo)); then
  cat <<EOF
  3. Only for images in a private package (the default ones are public): log in to
     GHCR with a classic personal access token that has only the read:packages scope
     (GHCR does not accept fine-grained tokens):
       docker login ghcr.io -u <GitHub user name>
EOF
else
  echo "  3. done: $user is logged in to ghcr.io"
fi
if grep -q PLACEHOLDER "$config/cosign-rolling.pub"; then
  cat <<EOF
  4. cosign-rolling.pub is a placeholder (no rolling key): a host that follows rolling
     deploys nothing until you accept unsigned assets in ~/.config/tinfirm-auto-deploy/env:
       TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1
     and remove that line once cosign-rolling.pub holds the rolling key
     (docs/operations/auto-deploy.md, Signed assets).
EOF
fi
cat <<EOF

Watch:  journalctl -u tinfirm-auto-deploy -f      State: tinfirm-auto-deploy status
Pause:  sudo systemctl stop tinfirm-auto-deploy.timer    (resume: start)
EOF
for w in "${warnings[@]}"; do echo "WARNING: $w" >&2; done
