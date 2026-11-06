#!/usr/bin/env bash
# tinfirm-auto-deploy: pull-based continuous delivery for a single-host Compose install of
# Tinfirm that follows main. The guide is docs/operations/auto-deploy.md.
#
# For every commit on main that passes CI (.github/workflows/ci.yml), CI publishes
#   - the server image ghcr.io/flowtivo/tinfirm-server:sha-<rev>,
#   - the model port's image ghcr.io/flowtivo/tinfirm-model-api:sha-<rev>, and
#   - the GitHub prerelease `rolling` with the assets REVISION (<rev>), SHA256SUMS,
#     tinfirm-compose-<version>.tar.gz and tinfirm-runner-host-<version>-<target>.tar.gz.
#
# A systemd timer runs this script every 5 minutes as the deploy user. When the REVISION of
# `rolling` is not the deployed revision, it downloads what this host needs (the Compose
# files, and the runner host binary for this machine only if a runner host is installed),
# checks the signature of SHA256SUMS (SHA256SUMS.sig) against the public key install.sh put
# in the settings directory, then each file against SHA256SUMS and REVISION again
# afterwards, pulls the server image and the model port's image by sha-<rev>, backs up the
# database, copies the Compose files (never .env), points TINFIRM_SERVER_IMAGE and
# TINFIRM_MODEL_API_IMAGE at sha-<rev> (and adds LiteLLM's secrets to .env when they are
# missing), restarts the stack, waits until the server answers, and installs the new runner
# host binary (the runner drains; systemd starts it again with the new binary).
# A failed deploy is retried at most 3 times. It never rolls back by itself.
#
# Runner mode (AUTO_DEPLOY_MODE=runner) is for a host that runs only a runner host, while the
# server runs elsewhere (Kubernetes). Each pass asks the server for its revision
# (GET /api/system/info, `commit`) and installs the runner host binary of exactly that
# revision, from the release when it is at that revision or from this host's cache: the
# runner protocol changes in place, so the runner host never runs ahead of its server. It
# follows the server back, too, when that revision is cached here. No Compose files, image,
# backup or .env.
#
#   tinfirm-auto-deploy                       one pass (what the timer runs)
#   tinfirm-auto-deploy deploy <rev>          deploy <rev> now: from the release, or cached
#   tinfirm-auto-deploy status                deployed revision, history, failures, settings
#   tinfirm-auto-deploy mark-deployed <rev>   record <rev> as deployed without deploying it
#   tinfirm-auto-deploy retry                 forget the failures; the next pass tries again
#
# Signed assets (M13 of the security review of 2026-10-27): nothing is installed from a
# SHA256SUMS whose signature does not verify against AUTO_DEPLOY_PUBLIC_KEY (default:
# cosign-rolling.pub for `rolling`, cosign.pub for a versioned release, both in the settings
# directory). A release without SHA256SUMS.sig is not deployed (the next pass looks again,
# for a release whose signing job has not finished), unless
# TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1, which accepts unsigned assets (a build of `rolling`
# made without the rolling key); a signature that does not verify is refused even then.
#
# DRY_RUN=1 downloads and checks as usual, then only prints what it would change: no pull
# (it uses the local image when there is one), no backup, and no change to files, .env,
# containers, the runner binary or the state.
#
# Settings: ~/.config/tinfirm-auto-deploy/env, read by bash (AUTO_DEPLOY_*=value lines; the
# defaults are below). Credentials: ~/.config/tinfirm-auto-deploy/github-token, a
# fine-grained token with read-only access to public repositories, for the release assets
# (without one, the GitHub API allows only 60 requests an hour). The images are public.
set -euo pipefail
export LC_ALL=C
umask 077

# ---- Settings ---------------------------------------------------------------------------
CONFIG_DIR="${AUTO_DEPLOY_CONFIG_DIR:-$HOME/.config/tinfirm-auto-deploy}"
if [ -f "$CONFIG_DIR/env" ]; then
  # shellcheck source=/dev/null
  . "$CONFIG_DIR/env"
fi
# stack: this host runs the Compose stack (and maybe a runner host). runner: only a runner
# host; it follows the revision of the server at AUTO_DEPLOY_SERVER_URL.
: "${AUTO_DEPLOY_MODE:=stack}"
: "${AUTO_DEPLOY_REPO:=FLOWTIVO/tinfirm}"
: "${AUTO_DEPLOY_API:=https://api.github.com}"
: "${AUTO_DEPLOY_RELEASE:=rolling}"                  # a release tag, or `latest`
: "${AUTO_DEPLOY_TOKEN_FILE:=$CONFIG_DIR/github-token}"
: "${AUTO_DEPLOY_SERVER_IMAGE:=ghcr.io/flowtivo/tinfirm-server}"
: "${AUTO_DEPLOY_MODEL_API_IMAGE:=ghcr.io/flowtivo/tinfirm-model-api}"
: "${AUTO_DEPLOY_COMPOSE_DIR:=$HOME/tinfirm/deploy/compose}"
: "${AUTO_DEPLOY_PROJECT:=tinfirm}"
: "${AUTO_DEPLOY_POSTGRES_CONTAINER:=$AUTO_DEPLOY_PROJECT-postgres-1}"
: "${AUTO_DEPLOY_SERVER_CONTAINER:=$AUTO_DEPLOY_PROJECT-server-1}"
: "${AUTO_DEPLOY_DATABASE:=tinfirm}"
: "${AUTO_DEPLOY_BACKUP_DIR:=$HOME/backups}"
: "${AUTO_DEPLOY_BACKUP_KEEP:=10}"
: "${AUTO_DEPLOY_RUNNER_BIN:=$HOME/.local/bin/tinfirm-runner-host}"
: "${AUTO_DEPLOY_RUNNER_UNIT:=tinfirm-runner}"
: "${AUTO_DEPLOY_RUNNER_CONFIG:=$HOME/.config/tinfirm-runner/config.json}"
# Runner mode: the server whose revision the runner host follows. Empty = `serverUrl` of the
# runner host's config.json (AUTO_DEPLOY_RUNNER_CONFIG).
: "${AUTO_DEPLOY_SERVER_URL:=}"
: "${AUTO_DEPLOY_READY_TIMEOUT:=300}"                # seconds
: "${AUTO_DEPLOY_MAX_ATTEMPTS:=3}"
: "${AUTO_DEPLOY_KEEP_REVISIONS:=3}"                 # the deployed one and 2 before it
: "${AUTO_DEPLOY_STATE_DIR:=$HOME/.local/share/tinfirm-auto-deploy}"
# The public key SHA256SUMS.sig must verify against. Empty = cosign-rolling.pub in the
# settings directory for `rolling`, cosign.pub there for a versioned release (install.sh
# puts both).
: "${AUTO_DEPLOY_PUBLIC_KEY:=}"
# 1 = deploy assets whose SHA256SUMS has no signature, or no key to check it with (with a
# warning). A signature that does not verify is refused anyway.
: "${TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED:=0}"
: "${DRY_RUN:=0}"
if [ -z "$AUTO_DEPLOY_PUBLIC_KEY" ]; then
  if [ "$AUTO_DEPLOY_RELEASE" = rolling ]; then
    AUTO_DEPLOY_PUBLIC_KEY="$CONFIG_DIR/cosign-rolling.pub"
  else
    AUTO_DEPLOY_PUBLIC_KEY="$CONFIG_DIR/cosign.pub"
  fi
fi

ENV_FILE="$AUTO_DEPLOY_COMPOSE_DIR/.env"
STATE_DEPLOYED="$AUTO_DEPLOY_STATE_DIR/deployed"
STATE_HISTORY="$AUTO_DEPLOY_STATE_DIR/history"
STATE_FAILURES="$AUTO_DEPLOY_STATE_DIR/failures"
STATE_RELOAD_PENDING="$AUTO_DEPLOY_STATE_DIR/runner-reload-pending"
# Written to ask the root path unit tinfirm-auto-deploy-reload.path (install.sh) to reload
# the runner host's unit: the service runs with NoNewPrivileges, so sudo cannot.
STATE_RELOAD_REQUEST="$AUTO_DEPLOY_STATE_DIR/runner-reload-request"
STATE_CACHE="$AUTO_DEPLOY_STATE_DIR/revisions"

WORK=""            # temporary directory of this run
DEPLOYING=""       # the revision being deployed; a failure from then on counts against it
STEP=""            # the deploy step, for the failure message
STACK_TOUCHED=0    # compose up ran; on failure, show the containers and the server log
NEED_RUNNER=0
RUNNER_NEW=0
RUNNER_VERSION=""
RUNNER_RESULT="not installed"
BACKUP_RESULT="none"
MUSL_TARGET=""
TMPFILES=()

case "$AUTO_DEPLOY_MODE" in
  stack) NEED_COMPOSE=1 ;;
  runner) NEED_COMPOSE=0 ;;
  *) printf 'ERROR: AUTO_DEPLOY_MODE must be stack or runner, not %s\n' "$AUTO_DEPLOY_MODE" >&2; exit 1 ;;
esac
runner_mode() { [ "$AUTO_DEPLOY_MODE" = runner ]; }

# ---- Output -----------------------------------------------------------------------------
# Under systemd, stdout goes to the journal; a <N> prefix sets the line's priority there, so
# `journalctl -p warning` shows the failures.
P_ERR="" P_WARN=""
if [ -n "${JOURNAL_STREAM:-}" ] && [ "$(stat -L -c '%d:%i' "/proc/$$/fd/1" 2>/dev/null)" = "$JOURNAL_STREAM" ]; then
  P_ERR="<3>" P_WARN="<4>"
fi
log() { printf '%s\n' "$*"; }
warn() { printf '%sWARNING: %s\n' "$P_WARN" "$*"; }
err() { printf '%sERROR: %s\n' "$P_ERR" "$*"; }
die() { err "$*"; exit 1; }

dry_run() { [ "$DRY_RUN" = 1 ]; }

# Runs a command that changes something; under DRY_RUN prints it instead. -q: no stdout.
act() {
  local quiet=0
  if [ "${1:-}" = -q ]; then quiet=1; shift; fi
  if dry_run; then
    log "[dry-run] would run: $(printf '%q ' "$@")"
    return 0
  fi
  if ((quiet)); then "$@" >/dev/null; else "$@"; fi
}

valid_rev() { [[ ${1:-} =~ ^[0-9a-f]{40}$ ]]; }

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# ---- State ------------------------------------------------------------------------------
read_deployed() {
  if [ -f "$STATE_DEPLOYED" ]; then tr -d '[:space:]' < "$STATE_DEPLOYED"; fi
}

# The deployed revision; before the first recorded deploy, the sha-<rev> tag of the image
# the server container runs, if it is one of AUTO_DEPLOY_SERVER_IMAGE.
previous_revision() {
  local deployed image
  deployed="$(read_deployed)"
  if [ -n "$deployed" ]; then echo "$deployed"; return 0; fi
  image="$(docker container inspect -f '{{.Config.Image}}' "$AUTO_DEPLOY_SERVER_CONTAINER" 2>/dev/null || true)"
  case "$image" in "$AUTO_DEPLOY_SERVER_IMAGE:sha-"*) ;; *) return 0 ;; esac
  if valid_rev "${image##*:sha-}"; then echo "${image##*:sha-}"; fi
}

write_state() { # file content
  if dry_run; then log "[dry-run] would write $1: $2"; return 0; fi
  mkdir -p "$AUTO_DEPLOY_STATE_DIR"
  printf '%s\n' "$2" > "$1.tmp"
  mv -f "$1.tmp" "$1"
}

in_history() { [ -f "$STATE_HISTORY" ] && grep -qxF "$1" "$STATE_HISTORY"; }

failures_of() { # rev -> number of failed attempts
  local r="" n=""
  if [ -f "$STATE_FAILURES" ]; then read -r r n < "$STATE_FAILURES" || true; fi
  if [ "$r" = "$1" ] && [[ $n =~ ^[0-9]+$ ]]; then echo "$n"; else echo 0; fi
}

record_success() { # rev [previous revision, recorded first when the history is empty]
  local history=""
  if [ -f "$STATE_HISTORY" ]; then history="$(tail -n 49 "$STATE_HISTORY")"; fi
  if [ -z "$history" ] && [ -n "${2:-}" ] && [ "$2" != "$1" ]; then history="$2"; fi
  write_state "$STATE_DEPLOYED" "$1"
  write_state "$STATE_HISTORY" "${history:+$history
}$1"
  if ! dry_run; then rm -f "$STATE_FAILURES"; fi
}

# The revisions whose images and cached assets stay: <rev>, then the history newest first
# (before the first recorded deploy: the previous revision).
keep_revisions() { # rev [previous revision]
  printf '%s\n' "$1"
  if [ ! -s "$STATE_HISTORY" ]; then
    if [ -n "${2:-}" ] && [ "$2" != "$1" ] && ((AUTO_DEPLOY_KEEP_REVISIONS > 1)); then printf '%s\n' "$2"; fi
  else
    awk -v k="$((AUTO_DEPLOY_KEEP_REVISIONS - 1))" -v skip="$1" '
      { a[NR] = $0 }
      END { for (i = NR; i > 0 && c < k; i--) if (a[i] != skip && !(a[i] in s)) { s[a[i]] = 1; print a[i]; c++ } }
    ' "$STATE_HISTORY"
  fi
}

lock() {
  if dry_run; then return 0; fi
  mkdir -p "$AUTO_DEPLOY_STATE_DIR"
  chmod 700 "$AUTO_DEPLOY_STATE_DIR"
  exec 9>"$AUTO_DEPLOY_STATE_DIR/lock"
  if ! flock -n 9; then
    log "another tinfirm-auto-deploy is running; nothing to do"
    exit 0
  fi
}

# ---- Artifact source: a GitHub Release --------------------------------------------------
# The token is written into a header file in the private work directory and handed to curl
# as -H @file, so it never appears in a command line (ps, /proc/*/cmdline). curl does not
# send it on when the asset download redirects to the storage host.
AUTH_HEADER=""
CURL_PROTO=()
case "$AUTO_DEPLOY_API" in https://*) CURL_PROTO=(--proto "=https" --proto-redir "=https") ;; esac

auth_header() {
  local token="" mode
  if [ -n "$AUTH_HEADER" ]; then return 0; fi
  [ -r "$AUTO_DEPLOY_TOKEN_FILE" ] ||
    die "no GitHub token in $AUTO_DEPLOY_TOKEN_FILE (docs/operations/auto-deploy.md, section Tokens)"
  mode="$(stat -c %a "$AUTO_DEPLOY_TOKEN_FILE")"
  case "$mode" in *00) ;; *) warn "$AUTO_DEPLOY_TOKEN_FILE has mode $mode; make it 600" ;; esac
  IFS= read -r token < "$AUTO_DEPLOY_TOKEN_FILE" || true
  token="${token//[[:space:]]/}"
  [ -n "$token" ] || die "$AUTO_DEPLOY_TOKEN_FILE is empty"
  AUTH_HEADER="$WORK/auth-header"
  printf 'Authorization: Bearer %s\n' "$token" > "$AUTH_HEADER"
}

api() { # curl arguments...
  auth_header
  curl -fsSL "${CURL_PROTO[@]}" --retry 2 --connect-timeout 10 --max-time 600 \
    -H @"$AUTH_HEADER" -H 'X-GitHub-Api-Version: 2022-11-28' "$@"
}

release_index() { # dest: the release's JSON
  local path="releases/tags/$AUTO_DEPLOY_RELEASE"
  if [ "$AUTO_DEPLOY_RELEASE" = latest ]; then path=releases/latest; fi
  api -H 'Accept: application/vnd.github+json' -o "$1" "$AUTO_DEPLOY_API/repos/$AUTO_DEPLOY_REPO/$path"
}

release_asset() { # index name dest
  local id
  id="$(jq -r --arg n "$2" 'first(.assets[] | select(.name == $n) | .id) // empty' "$1")"
  [ -n "$id" ] || return 1
  api -H 'Accept: application/octet-stream' -o "$3" \
    "$AUTO_DEPLOY_API/repos/$AUTO_DEPLOY_REPO/releases/assets/$id"
}

release_revision() { # index dir -> the release's REVISION, also saved as dir/REVISION
  local rev
  release_asset "$1" REVISION "$2/REVISION" || return 1
  rev="$(tr -d '[:space:]' < "$2/REVISION")"
  valid_rev "$rev" || return 1
  echo "$rev"
}

sum_of() { # SHA256SUMS name -> its sha256
  awk -v n="$2" '{ f = $2; sub(/^\*/, "", f) } f == n { print $1; exit }' "$1"
}

check_sum() { # SHA256SUMS name file
  local want got
  want="$(sum_of "$1" "$2")"
  [ -n "$want" ] || { err "$2 is not listed in SHA256SUMS"; return 1; }
  got="$(sha256_of "$3")"
  [ "$want" = "$got" ] || { err "$2: sha256 is $got, SHA256SUMS says $want"; return 1; }
}

allow_unsigned() { [ "$TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED" = 1 ]; }

# The state of <dir>/SHA256SUMS's signature: ok, missing (no SHA256SUMS.sig), nokey (no
# usable AUTO_DEPLOY_PUBLIC_KEY) or bad. cosign's signature of a blob is the base64 of an
# ECDSA P-256 signature over its SHA-256, in DER, which openssl checks against cosign's
# public key (deploy/release/assets.sh, verify-sums).
signature_state() { # dir
  local key="$AUTO_DEPLOY_PUBLIC_KEY" der="$WORK/SHA256SUMS.der"
  if [ ! -s "$1/SHA256SUMS.sig" ]; then echo missing; return 0; fi
  if [ ! -s "$key" ] || grep -q PLACEHOLDER "$key"; then echo nokey; return 0; fi
  if openssl base64 -d -A -in "$1/SHA256SUMS.sig" -out "$der" 2>/dev/null &&
    openssl dgst -sha256 -verify "$key" -signature "$der" "$1/SHA256SUMS" >/dev/null 2>&1; then
    echo ok
  else
    echo bad
  fi
}

# Before any checksum in <dir>/SHA256SUMS is trusted. 0: signed by the key, or unsigned and
# allowed. 3: not signed (yet): try again later. 1: the signature does not verify.
check_signature() { # dir
  local state
  state="$(signature_state "$1")"
  case "$state" in
    ok) return 0 ;;
    bad) return 1 ;;
    missing)
      if allow_unsigned; then
        warn "release $AUTO_DEPLOY_RELEASE has no SHA256SUMS.sig; deploying unsigned assets because TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1"
        return 0
      fi
      warn "release $AUTO_DEPLOY_RELEASE has no SHA256SUMS.sig, so nothing in it is trusted; the next pass looks again (a release is signed after its assets are up; docs/operations/auto-deploy.md, section Signed assets)"
      return 3 ;;
    nokey)
      if allow_unsigned; then
        warn "no public key at $AUTO_DEPLOY_PUBLIC_KEY, so SHA256SUMS.sig is not checked; deploying because TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1"
        return 0
      fi
      die "no public key at $AUTO_DEPLOY_PUBLIC_KEY to check SHA256SUMS.sig with: run install.sh again, or set AUTO_DEPLOY_PUBLIC_KEY (docs/operations/auto-deploy.md, section Signed assets)" ;;
  esac
}

asset_matching() { # SHA256SUMS regex -> the one asset name that matches
  local names
  names="$(awk '{ f = $2; sub(/^\*/, "", f); print f }' "$1" | grep -E "$2" || true)"
  [ -n "$names" ] && [ "$(printf '%s\n' "$names" | wc -l)" -eq 1 ] || return 1
  printf '%s\n' "$names"
}

# Puts REVISION, SHA256SUMS, the Compose tarball (with NEED_COMPOSE) and (with NEED_RUNNER)
# this machine's runner host tarball of <rev> into <dir>, each checked against SHA256SUMS; sets COMPOSE_ASSET and
# RUNNER_ASSET. Exit status 3: the source does not have <rev> (any more), or is being
# updated; try again later.
COMPOSE_ASSET=""
RUNNER_ASSET=""
fetch_artifacts() { # rev dir
  local rev="$1" dir="$2" cache="$STATE_CACHE/$1"
  mkdir -p "$dir"
  if from_cache "$rev" "$cache" "$dir"; then
    log "using the assets of $rev cached in $cache"
    return 0
  fi
  from_release "$rev" "$dir"
}

from_cache() { # rev cache dir
  local sums="$2/SHA256SUMS"
  [ -f "$sums" ] && [ -f "$2/REVISION" ] || return 1
  [ "$(tr -d '[:space:]' < "$2/REVISION")" = "$1" ] || return 1
  # The cache is checked as the release is: a revision cached without a signature (before
  # signing, or allowed unsigned) is used only while unsigned assets are allowed.
  case "$(signature_state "$2")" in
    ok) ;;
    missing | nokey) allow_unsigned || return 1 ;;
    *) return 1 ;;
  esac
  check_sum "$sums" REVISION "$2/REVISION" >/dev/null || return 1
  if ((NEED_COMPOSE)); then
    COMPOSE_ASSET="$(asset_matching "$sums" '^tinfirm-compose-.+\.tar\.gz$')" || return 1
    [ -f "$2/$COMPOSE_ASSET" ] && check_sum "$sums" "$COMPOSE_ASSET" "$2/$COMPOSE_ASSET" || return 1
  fi
  if ((NEED_RUNNER)); then
    RUNNER_ASSET="$(asset_matching "$sums" "^tinfirm-runner-host-.+-${MUSL_TARGET}\\.tar\\.gz\$")" || return 1
    [ -f "$2/$RUNNER_ASSET" ] && check_sum "$sums" "$RUNNER_ASSET" "$2/$RUNNER_ASSET" || return 1
  fi
  cp -p "$2/REVISION" "$2/SHA256SUMS" ${COMPOSE_ASSET:+"$2/$COMPOSE_ASSET"} ${RUNNER_ASSET:+"$2/$RUNNER_ASSET"} "$3"/
  if [ -f "$2/SHA256SUMS.sig" ]; then cp -p "$2/SHA256SUMS.sig" "$3"/; fi
}

from_release() { # rev dir
  local rev="$1" dir="$2" index="$WORK/release.json" now sig
  release_index "$index" || die "cannot read release $AUTO_DEPLOY_RELEASE of $AUTO_DEPLOY_REPO"
  now="$(release_revision "$index" "$dir")" || { log "release $AUTO_DEPLOY_RELEASE has no valid REVISION asset right now"; return 3; }
  if [ "$now" != "$rev" ]; then
    log "release $AUTO_DEPLOY_RELEASE is at $now, not $rev, and $rev is not cached here"
    return 3
  fi
  release_asset "$index" SHA256SUMS "$dir/SHA256SUMS" || { log "release $AUTO_DEPLOY_RELEASE has no SHA256SUMS right now"; return 3; }
  # SHA256SUMS lists REVISION too. If they disagree, CI is replacing the assets right now.
  if ! check_sum "$dir/SHA256SUMS" REVISION "$dir/REVISION" >/dev/null; then
    log "release $AUTO_DEPLOY_RELEASE is being updated (REVISION and SHA256SUMS disagree)"
    return 3
  fi
  # The signature, before any checksum in SHA256SUMS is trusted. CI uploads it after
  # SHA256SUMS and before REVISION, so a mismatch while REVISION still names $rev is not an
  # update in progress.
  rm -f "$dir/SHA256SUMS.sig"
  release_asset "$index" SHA256SUMS.sig "$dir/SHA256SUMS.sig" || rm -f "$dir/SHA256SUMS.sig"
  sig=0
  check_signature "$dir" || sig=$?
  case "$sig" in
    0) ;;
    3) return 3 ;;
    *)
      release_index "$index" || die "cannot read release $AUTO_DEPLOY_RELEASE of $AUTO_DEPLOY_REPO"
      now="$(release_revision "$index" "$WORK")" || { log "release $AUTO_DEPLOY_RELEASE changed during the download"; return 3; }
      if [ "$now" != "$rev" ]; then
        log "release $AUTO_DEPLOY_RELEASE moved to $now during the download"
        return 3
      fi
      die "SHA256SUMS of $rev in release $AUTO_DEPLOY_RELEASE is not signed by $AUTO_DEPLOY_PUBLIC_KEY: nothing from it is installed. Someone may have replaced the assets; check the release before anything else (docs/operations/auto-deploy.md, section Signed assets)" ;;
  esac
  if ((NEED_COMPOSE)); then
    COMPOSE_ASSET="$(asset_matching "$dir/SHA256SUMS" '^tinfirm-compose-.+\.tar\.gz$')" ||
      die "SHA256SUMS of $rev lists no single tinfirm-compose-*.tar.gz"
    release_asset "$index" "$COMPOSE_ASSET" "$dir/$COMPOSE_ASSET" || { log "cannot download $COMPOSE_ASSET"; return 3; }
    check_sum "$dir/SHA256SUMS" "$COMPOSE_ASSET" "$dir/$COMPOSE_ASSET" || die "$COMPOSE_ASSET is corrupt"
  fi
  if ((NEED_RUNNER)); then
    RUNNER_ASSET="$(asset_matching "$dir/SHA256SUMS" "^tinfirm-runner-host-.+-${MUSL_TARGET}\\.tar\\.gz\$")" ||
      die "SHA256SUMS of $rev lists no single runner host asset for $MUSL_TARGET"
    release_asset "$index" "$RUNNER_ASSET" "$dir/$RUNNER_ASSET" || { log "cannot download $RUNNER_ASSET"; return 3; }
    check_sum "$dir/SHA256SUMS" "$RUNNER_ASSET" "$dir/$RUNNER_ASSET" || die "$RUNNER_ASSET is corrupt"
  fi
  # The release can move while we download: REVISION again, from a fresh index.
  release_index "$index" || die "cannot read release $AUTO_DEPLOY_RELEASE of $AUTO_DEPLOY_REPO"
  now="$(release_revision "$index" "$WORK")" || { log "release $AUTO_DEPLOY_RELEASE changed during the download"; return 3; }
  if [ "$now" != "$rev" ]; then
    log "release $AUTO_DEPLOY_RELEASE moved to $now during the download"
    return 3
  fi
}

cache_artifacts() { # rev dir
  local cache="$STATE_CACHE/$1" f
  if dry_run; then log "[dry-run] would cache the assets of $1 in $cache"; return 0; fi
  mkdir -p "$cache"
  for f in REVISION SHA256SUMS ${COMPOSE_ASSET:+"$COMPOSE_ASSET"} ${RUNNER_ASSET:+"$RUNNER_ASSET"}; do
    cp -p "$2/$f" "$cache/$f"
  done
  rm -f "$cache/SHA256SUMS.sig"
  if [ -f "$2/SHA256SUMS.sig" ]; then cp -p "$2/SHA256SUMS.sig" "$cache/SHA256SUMS.sig"; fi
}

# ---- Docker -----------------------------------------------------------------------------
label() { # image key
  docker image inspect --format "{{ with index .Config.Labels \"$2\" }}{{ . }}{{ end }}" "$1" 2>/dev/null || true
}

pull() { # ref
  if dry_run; then
    if docker image inspect "$1" >/dev/null 2>&1; then
      log "[dry-run] would pull $1 (using the local copy)"
    else
      log "[dry-run] would pull $1 (not here, so its labels are not checked)"
    fi
    return 0
  fi
  docker pull --quiet "$1" >/dev/null
}

COMPOSE=(docker compose -p "$AUTO_DEPLOY_PROJECT" --project-directory "$AUTO_DEPLOY_COMPOSE_DIR"
  -f "$AUTO_DEPLOY_COMPOSE_DIR/compose.yaml" --env-file "$ENV_FILE")

env_value() { # key -> its value in .env, quotes removed
  local v
  v="$(awk -v k="$1" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2) } END { print v }' "$ENV_FILE")"
  v="${v%$'\r'}"
  v="${v#\"}" v="${v%\"}"
  v="${v#\'}" v="${v%\'}"
  printf '%s' "$v"
}

set_env_value() { # key value: in .env, atomically, mode 600; the other lines stay as they are
  local key="$1" value="$2" current tmp
  current="$(env_value "$key")"
  if [ "$current" = "$value" ]; then log ".env: $key unchanged"; return 0; fi
  if dry_run; then log "[dry-run] would set $key=$value in .env (now: ${current:-unset})"; return 0; fi
  tmp="$(mktemp "$AUTO_DEPLOY_COMPOSE_DIR/.env.XXXXXX")"
  TMPFILES+=("$tmp")
  awk -v k="$key" -v v="$value" '
    index($0, k "=") == 1 { if (!done) print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' "$ENV_FILE" > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$ENV_FILE"
  log ".env: $key=$value (was ${current:-unset})"
}

random_hex() { # bytes -> that many random bytes as hex
  od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'
}

add_env_secret() { # key value: appended to .env only when the key is missing; never logged
  local key="$1" value="$2" tmp
  if grep -q "^$key=" "$ENV_FILE"; then return 0; fi
  if dry_run; then log "[dry-run] would add a generated $key to .env"; return 0; fi
  tmp="$(mktemp "$AUTO_DEPLOY_COMPOSE_DIR/.env.XXXXXX")"
  TMPFILES+=("$tmp")
  { cat "$ENV_FILE"; [ -z "$(tail -c 1 "$ENV_FILE")" ] || echo; printf '%s=%s\n' "$key" "$value"; } > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$ENV_FILE"
  log ".env: added a generated $key"
}

# LiteLLM's secrets (docs/operations/litellm.md), for a .env without them:
# compose.yaml requires them. Only missing ones are added; a new salt key would make the
# provider keys LiteLLM stored unreadable.
litellm_secrets() {
  add_env_secret LITELLM_MASTER_KEY "sk-$(random_hex 32)"
  add_env_secret LITELLM_SALT_KEY "$(random_hex 32)"
  add_env_secret LITELLM_DB_PASSWORD "$(random_hex 24)"
}

# The company of compose.yaml (key `main`, ADR-0075) is named by TINFIRM_COMPANY_NAME. A .env
# may name it TINFIRM_BOOTSTRAP_COMPANY_NAME, which the server does not read: carry
# the name over once, so that the server adopts that company by its name instead of creating
# a second one named "My Company".
company_name() {
  local old
  if grep -q '^TINFIRM_COMPANY_NAME=' "$ENV_FILE"; then return 0; fi
  old="$(env_value TINFIRM_BOOTSTRAP_COMPANY_NAME)"
  [ -n "$old" ] || return 0
  set_env_value TINFIRM_COMPANY_NAME "$old"
}

# ---- Deploy steps -----------------------------------------------------------------------
preflight() {
  local cmd missing=()
  for cmd in docker curl jq flock sha256sum tar awk cmp timeout od openssl; do
    command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
  done
  ((${#missing[@]} == 0)) || die "missing commands: ${missing[*]}"
  docker info >/dev/null 2>&1 || die "cannot reach Docker as $(id -un); the deploy user must be in the docker group"
  if runner_mode; then
    [ -e "$AUTO_DEPLOY_RUNNER_BIN" ] ||
      die "runner mode, but no runner host binary at $AUTO_DEPLOY_RUNNER_BIN (AUTO_DEPLOY_RUNNER_BIN)"
  else
    [ -f "$ENV_FILE" ] ||
      die "no $ENV_FILE; deploy once with deploy/compose/deploy-host.sh, which generates it"
  fi
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/tinfirm-auto-deploy.XXXXXX")"
}

musl_target() {
  case "$(uname -m)" in
    x86_64 | amd64) echo x86_64-unknown-linux-musl ;;
    aarch64 | arm64) echo aarch64-unknown-linux-musl ;;
    *) return 1 ;;
  esac
}

unpack() { # tarball dest
  mkdir -p "$2"
  tar -xzf "$1" -C "$2" --strip-components=1 --no-same-owner
}

prepare_compose() {
  local src="$WORK/compose" stray
  unpack "$WORK/assets/$COMPOSE_ASSET" "$src"
  [ -f "$src/compose.yaml" ] || die "$COMPOSE_ASSET has no compose.yaml"
  # Never copy a .env over the host's (CI builds the tarball without one; this is a guard).
  stray="$(find "$src" \( -name .env -o \( -name '.env.*' ! -name .env.example \) \) -print)"
  if [ -n "$stray" ]; then
    warn "$COMPOSE_ASSET contains $stray; not copying it"
    find "$src" \( -name .env -o \( -name '.env.*' ! -name .env.example \) \) -exec rm -f {} +
  fi
}

prepare_runner() {
  local new="$WORK/runner/tinfirm-runner-host" new_sum old_sum
  ((NEED_RUNNER)) || return 0
  unpack "$WORK/assets/$RUNNER_ASSET" "$WORK/runner"
  [ -f "$new" ] || die "$RUNNER_ASSET has no tinfirm-runner-host"
  new_sum="$(sha256_of "$new")"
  old_sum="$(sha256_of "$AUTO_DEPLOY_RUNNER_BIN")"
  if [ "$new_sum" = "$old_sum" ]; then return 0; fi
  # Runs here before anything changes: a binary that cannot start never gets installed.
  chmod 755 "$new"
  RUNNER_VERSION="$(timeout 20 "$new" --version 2>&1)" ||
    die "the new runner host binary does not run on this host: $RUNNER_VERSION"
  RUNNER_NEW=1
}

backup() { # deployed revision
  local prev="${1:-none}" file partial running dumps=() i
  if ! docker container inspect "$AUTO_DEPLOY_POSTGRES_CONTAINER" >/dev/null 2>&1; then
    warn "no container $AUTO_DEPLOY_POSTGRES_CONTAINER, so no database to back up (first deployment?)"
    BACKUP_RESULT="none (no database container)"
    return 0
  fi
  running="$(docker container inspect -f '{{.State.Running}}' "$AUTO_DEPLOY_POSTGRES_CONTAINER")"
  [ "$running" = true ] ||
    die "$AUTO_DEPLOY_POSTGRES_CONTAINER is not running; not deploying without a backup"
  file="$AUTO_DEPLOY_BACKUP_DIR/tinfirm-auto-$(date -u +%Y%m%dT%H%M%SZ)-${prev:0:12}.dump"
  if dry_run; then
    log "[dry-run] would back up database $AUTO_DEPLOY_DATABASE to $file"
    BACKUP_RESULT="$file (dry run)"
    return 0
  fi
  mkdir -p "$AUTO_DEPLOY_BACKUP_DIR"
  chmod 700 "$AUTO_DEPLOY_BACKUP_DIR"
  partial="$file.partial"
  TMPFILES+=("$partial")
  docker exec "$AUTO_DEPLOY_POSTGRES_CONTAINER" \
    pg_dump -U postgres -Fc "$AUTO_DEPLOY_DATABASE" > "$partial" || die "pg_dump failed"
  [ -s "$partial" ] || die "pg_dump wrote nothing"
  chmod 600 "$partial"
  mv -f "$partial" "$file"
  BACKUP_RESULT="$file ($(du -h "$file" | cut -f1))"
  # Keep the newest AUTO_DEPLOY_BACKUP_KEEP automatic dumps (the names sort by time). Other
  # files in the directory, such as dumps made by hand, are never touched.
  shopt -s nullglob
  dumps=("$AUTO_DEPLOY_BACKUP_DIR"/tinfirm-auto-*.dump)
  shopt -u nullglob
  for ((i = 0; i < ${#dumps[@]} - AUTO_DEPLOY_BACKUP_KEEP; i++)); do
    rm -f "${dumps[$i]}"
  done
}

install_compose_files() {
  local src="$WORK/compose" f rel changed=()
  while IFS= read -r -d '' f; do
    rel="${f#"$src"/}"
    cmp -s "$f" "$AUTO_DEPLOY_COMPOSE_DIR/$rel" || changed+=("$rel")
  done < <(find "$src" -type f -print0 | sort -z)
  if ((${#changed[@]} == 0)); then
    log "compose files: unchanged"
    return 0
  fi
  log "compose files: ${changed[*]} changed"
  act cp -R --preserve=mode,timestamps "$src/." "$AUTO_DEPLOY_COMPOSE_DIR/"
}

wait_ready() { # expected image
  local image port bind host state restarts0 restarts status deadline
  if dry_run; then log "[dry-run] would wait for the server to answer"; return 0; fi
  image="$(docker container inspect -f '{{.Config.Image}}' "$AUTO_DEPLOY_SERVER_CONTAINER")" ||
    die "no container $AUTO_DEPLOY_SERVER_CONTAINER after compose up"
  [ "$image" = "$1" ] || die "$AUTO_DEPLOY_SERVER_CONTAINER runs $image, not $1"
  restarts0="$(docker container inspect -f '{{.RestartCount}}' "$AUTO_DEPLOY_SERVER_CONTAINER")"
  port="$(env_value TINFIRM_HTTP_HOST_PORT)"
  bind="$(env_value TINFIRM_HTTP_BIND)"
  case "$bind" in "" | 0.0.0.0 | "::" | "[::]") host=127.0.0.1 ;; *) host="$bind" ;; esac
  deadline=$((SECONDS + AUTO_DEPLOY_READY_TIMEOUT))
  while :; do
    if curl -fs -o /dev/null --max-time 5 "http://$host:${port:-8080}/api/system/info"; then
      return 0
    fi
    state="$(docker container inspect -f '{{.State.Status}} {{.RestartCount}}' "$AUTO_DEPLOY_SERVER_CONTAINER" 2>/dev/null || echo "missing 0")"
    status="${state% *}" restarts="${state##* }"
    case "$status" in exited | dead | missing)
      die "the server container is $status" ;;
    esac
    ((restarts == restarts0)) || die "the server container restarted $((restarts - restarts0)) time(s) since the deploy"
    ((SECONDS < deadline)) ||
      die "the server did not answer http://$host:${port:-8080}/api/system/info within ${AUTO_DEPLOY_READY_TIMEOUT}s"
    sleep 3
  done
}

runner_update() {
  local bin="$AUTO_DEPLOY_RUNNER_BIN" unit="$AUTO_DEPLOY_RUNNER_UNIT" load
  ((NEED_RUNNER)) || return 0
  if ((RUNNER_NEW)); then
    # Keep the old binary as .prev (a hard link to it), then swap atomically.
    act ln -f "$bin" "$bin.prev"
    act cp "$WORK/runner/tinfirm-runner-host" "$bin.new"
    act chmod 755 "$bin.new"
    act mv -f "$bin.new" "$bin"
    if ! dry_run; then : > "$STATE_RELOAD_PENDING"; fi
    RUNNER_RESULT="updated to ${RUNNER_VERSION:-the new binary}"
  elif [ -f "$STATE_RELOAD_PENDING" ]; then
    RUNNER_RESULT="unchanged, reload still pending"
  else
    RUNNER_RESULT="unchanged"
    return 0
  fi
  # `reload` drains (ExecReload sends SIGHUP): the active runs finish, `run` exits 0 and
  # systemd (Restart=always) starts the binary that is installed now. stop/restart would
  # cancel the runs.
  load="$(systemctl show -p LoadState --value "$unit" 2>/dev/null || true)"
  if [ "$load" != loaded ]; then
    RUNNER_RESULT="$RUNNER_RESULT; no unit $unit, so restart the runner host by hand"
  elif systemctl is-active --quiet "$unit"; then
    if [ "$(systemctl is-enabled tinfirm-auto-deploy-reload.path 2>/dev/null)" = enabled ]; then
      # The service runs with NoNewPrivileges, so no sudo: the root path unit that
      # install.sh enabled reloads the unit it was installed for when this file changes.
      if dry_run; then
        log "[dry-run] would ask tinfirm-auto-deploy-reload.path to reload $unit"
      else
        mkdir -p "$AUTO_DEPLOY_STATE_DIR"
        date -u +%Y-%m-%dT%H:%M:%SZ > "$STATE_RELOAD_REQUEST"
      fi
      RUNNER_RESULT="$RUNNER_RESULT; reload of $unit requested, it drains and restarts with it"
    else
      # No enabled path unit (install.sh installs it only when the runner unit exists): sudo,
      # which works only where tinfirm-auto-deploy.service runs without NoNewPrivileges.
      act sudo -n systemctl reload "$unit" ||
        die "sudo -n systemctl reload $unit failed; run install.sh again, which sets up tinfirm-auto-deploy-reload.path"
      RUNNER_RESULT="$RUNNER_RESULT; $unit drains and restarts with it"
    fi
  else
    RUNNER_RESULT="$RUNNER_RESULT; $unit is not active, its next start uses it"
  fi
  if ! dry_run; then rm -f "$STATE_RELOAD_PENDING"; fi
}

prune() { # rev [previous revision]
  local keep image tag dir
  keep="$(keep_revisions "$1" "${2:-}")"
  for image in "$AUTO_DEPLOY_SERVER_IMAGE" "$AUTO_DEPLOY_MODEL_API_IMAGE"; do
    while IFS= read -r tag; do
      [[ $tag =~ ^sha-[0-9a-f]{40}$ ]] || continue
      grep -qxF "${tag#sha-}" <<<"$keep" && continue
      act -q docker image rm "$image:$tag" || warn "could not remove $image:$tag"
    done < <(docker image ls --format '{{.Tag}}' "$image")
  done
  if [ -d "$STATE_CACHE" ]; then
    for dir in "$STATE_CACHE"/*/; do
      [ -d "$dir" ] || continue
      dir="${dir%/}"
      grep -qxF "$(basename "$dir")" <<<"$keep" || act rm -rf "$dir"
    done
  fi
}

self_update_notice() {
  local shipped="$WORK/compose/auto-deploy/tinfirm-auto-deploy.sh" me
  me="$(readlink -f "$0")"
  if [ -f "$shipped" ] && ! cmp -s "$shipped" "$me"; then
    warn "this revision ships a different tinfirm-auto-deploy; install it with: sudo $AUTO_DEPLOY_COMPOSE_DIR/auto-deploy/install.sh"
  fi
}

on_exit() {
  local status=$? n
  set +e
  if [ "$status" -ne 0 ] && [ -n "$DEPLOYING" ] && ! dry_run; then
    if ((STACK_TOUCHED)); then
      log "--- containers:"
      "${COMPOSE[@]}" ps 2>&1
      log "--- last lines of $AUTO_DEPLOY_SERVER_CONTAINER:"
      docker logs --tail 60 "$AUTO_DEPLOY_SERVER_CONTAINER" 2>&1
      log "---"
    fi
    n=$(($(failures_of "$DEPLOYING") + 1))
    write_state "$STATE_FAILURES" "$DEPLOYING $n"
    err "DEPLOY OF $DEPLOYING FAILED at step '$STEP' (attempt $n of $AUTO_DEPLOY_MAX_ATTEMPTS). It is not marked deployed."
    if ((STACK_TOUCHED)); then
      err "The stack may run the new server, and its Flyway migrations may have run. Nothing is rolled back automatically; to roll back by hand see docs/operations/auto-deploy.md, section Roll back."
    fi
    if ((n >= AUTO_DEPLOY_MAX_ATTEMPTS)); then
      err "Giving up on $DEPLOYING. Push a fix (a new revision is deployed as usual), or run 'tinfirm-auto-deploy retry'."
    fi
  fi
  for n in "${TMPFILES[@]}"; do rm -f "$n"; done
  if [ -n "$WORK" ]; then rm -rf "$WORK"; fi
  exit "$status"
}
trap on_exit EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

# ---- Runner mode ------------------------------------------------------------------------
server_url() {
  local url="$AUTO_DEPLOY_SERVER_URL"
  if [ -z "$url" ] && [ -f "$AUTO_DEPLOY_RUNNER_CONFIG" ]; then
    url="$(jq -r '.serverUrl // empty' "$AUTO_DEPLOY_RUNNER_CONFIG" 2>/dev/null || true)"
  fi
  printf '%s\n' "${url%/}"
}

# Sets SERVER_REVISION to the full revision the server reports. Status 1, after a warning,
# when there is none. Not for $(...): the warnings must reach the journal.
SERVER_REVISION=""
server_revision() {
  local url info rev
  SERVER_REVISION=""
  url="$(server_url)"
  [ -n "$url" ] ||
    die "runner mode needs the server URL: set AUTO_DEPLOY_SERVER_URL, or log the runner host in ($AUTO_DEPLOY_RUNNER_CONFIG)"
  info="$(curl -fsS --max-time 15 "$url/api/system/info" 2>&1)" ||
    { warn "cannot read $url/api/system/info ($info); the runner host stays as it is"; return 1; }
  rev="$(jq -r '.commit // empty' <<<"$info" 2>/dev/null || true)"
  if ! valid_rev "$rev"; then
    warn "the server at $url reports no full revision (commit: ${rev:-none}); the runner host stays as it is"
    return 1
  fi
  SERVER_REVISION="$rev"
}

# Only the runner host binary of <rev>: nothing of the server is on this host.
deploy_runner_revision() { # rev auto|manual
  local rev="$1" mode="$2" deployed started=$SECONDS status=0
  deployed="$(read_deployed)"
  STEP="download"
  fetch_artifacts "$rev" "$WORK/assets" || status=$?
  case "$status" in
    0) ;;
    3)
      if [ "$mode" = manual ]; then die "the runner host of $rev is not available now (see above)"; fi
      log "not updating the runner host to $rev now: the release has another revision and $rev is not cached here; the next pass looks again"
      exit 0 ;;
    *) exit "$status" ;;
  esac
  log "updating the runner host to $rev (from ${deployed:-an unrecorded revision})"
  DEPLOYING="$rev"
  STEP="unpack"
  prepare_runner
  STEP="runner host"
  runner_update
  STEP="record"
  record_success "$rev" "$deployed"
  cache_artifacts "$rev" "$WORK/assets"
  DEPLOYING=""
  STEP="prune"
  prune "$rev" "$deployed" || warn "pruning failed"
  if dry_run; then
    log "dry run of $rev done; nothing was changed"
  else
    log "runner host at $rev in $((SECONDS - started))s: $RUNNER_RESULT"
  fi
}

# ---- Commands ---------------------------------------------------------------------------
detect_runner() {
  if [ -e "$AUTO_DEPLOY_RUNNER_BIN" ]; then
    if MUSL_TARGET="$(musl_target)"; then
      NEED_RUNNER=1
    elif runner_mode; then
      die "no runner host build for $(uname -m)"
    else
      warn "no runner host build for $(uname -m); the runner step is skipped"
      RUNNER_RESULT="skipped ($(uname -m))"
    fi
  fi
}

deploy_revision() { # rev auto|manual
  local rev="$1" mode="$2" deployed server_ref model_ref subject running started=$SECONDS status=0
  detect_runner
  if runner_mode; then
    deploy_runner_revision "$rev" "$mode"
    return
  fi
  deployed="$(previous_revision)"
  server_ref="$AUTO_DEPLOY_SERVER_IMAGE:sha-$rev"
  model_ref="$AUTO_DEPLOY_MODEL_API_IMAGE:sha-$rev"

  # 1. Everything this host needs, before anything changes.
  STEP="download"
  fetch_artifacts "$rev" "$WORK/assets" || status=$?
  case "$status" in
    0) ;;
    3)
      if [ "$mode" = manual ]; then die "the assets of $rev are not available now (see above)"; fi
      log "not deploying $rev now; the next pass tries again"
      exit 0 ;;
    *) exit "$status" ;;
  esac
  STEP="pull"
  pull "$server_ref" || die "cannot pull $server_ref (network? An AUTO_DEPLOY_SERVER_IMAGE of your own in a private package needs the deploy user's GHCR login: docs/operations/auto-deploy.md, section Tokens)"
  if docker image inspect "$server_ref" >/dev/null 2>&1; then
    [ "$(label "$server_ref" org.opencontainers.image.revision)" = "$rev" ] ||
      die "$server_ref is labelled revision '$(label "$server_ref" org.opencontainers.image.revision)', not $rev"
    subject="$(label "$server_ref" dev.tinfirm.commit-subject)"
  fi
  # The model port's image is published by `publish-services`, which `rolling` does not
  # wait for, so it may not be there yet when REVISION already names $rev. The server's pull
  # just succeeded from the same registry, so a failure here is most likely that: as with the
  # assets, not now, and the next pass tries again. Nothing changed yet, so it is not an
  # attempt.
  if ! pull "$model_ref"; then
    if [ "$mode" = manual ]; then die "cannot pull $model_ref (not published yet? publish-services runs beside rolling; docs/operations/auto-deploy.md, section 1)"; fi
    log "not deploying $rev now: $model_ref is not available yet (publish-services may still run); the next pass tries again"
    exit 0
  fi
  if docker image inspect "$model_ref" >/dev/null 2>&1; then
    [ "$(label "$model_ref" org.opencontainers.image.revision)" = "$rev" ] ||
      die "$model_ref is labelled revision '$(label "$model_ref" org.opencontainers.image.revision)', not $rev"
  fi
  running="$(docker container inspect -f '{{.Config.Image}}' "$AUTO_DEPLOY_SERVER_CONTAINER" 2>/dev/null || true)"
  if [ -n "$running" ] && [ "$running" != "$AUTO_DEPLOY_SERVER_IMAGE:sha-$deployed" ]; then
    running=" (the server runs $running)"
  else
    running=""
  fi
  log "deploying $rev${subject:+ \"$subject\"} over ${deployed:-an unrecorded revision}$running"

  # 2. From here on, a failure counts as an attempt on $rev.
  DEPLOYING="$rev"
  STEP="unpack"
  prepare_compose
  prepare_runner
  STEP="backup"
  backup "$deployed"
  STEP="compose files"
  install_compose_files
  STEP=".env"
  set_env_value TINFIRM_SERVER_IMAGE "$server_ref"
  set_env_value TINFIRM_MODEL_API_IMAGE "$model_ref"
  litellm_secrets
  company_name
  STEP="compose up"
  STACK_TOUCHED=1
  act "${COMPOSE[@]}" up -d --no-build --remove-orphans
  STEP="health check"
  wait_ready "$server_ref"
  STEP="runner host"
  runner_update
  STEP="record"
  record_success "$rev" "$deployed"
  cache_artifacts "$rev" "$WORK/assets"
  DEPLOYING=""

  # 3. Housekeeping; a failure here does not undo the deploy.
  STEP="prune"
  prune "$rev" "$deployed" || warn "pruning failed"
  self_update_notice
  if dry_run; then
    log "dry run of $rev done; nothing was changed"
  else
    log "deployed $rev${subject:+ \"$subject\"} in $((SECONDS - started))s: server $server_ref; model port $model_ref; runner host: $RUNNER_RESULT; backup: $BACKUP_RESULT"
  fi
}

cmd_pass() {
  local rev deployed n
  preflight
  lock
  release_index "$WORK/release.json" ||
    die "cannot read release $AUTO_DEPLOY_RELEASE of $AUTO_DEPLOY_REPO (token, network?)"
  rev="$(release_revision "$WORK/release.json" "$WORK")" || {
    log "release $AUTO_DEPLOY_RELEASE has no valid REVISION asset right now; the next pass looks again"
    exit 0
  }
  deployed="$(read_deployed)"
  if [ "$rev" = "$deployed" ]; then
    if [ -t 1 ] || dry_run; then log "up to date: $rev"; fi
    exit 0
  fi
  if in_history "$rev"; then
    warn "release $AUTO_DEPLOY_RELEASE points at $rev, which was deployed before ($deployed is deployed now). Auto-deploy never goes back; to deploy it anyway: tinfirm-auto-deploy deploy $rev"
    exit 0
  fi
  n="$(failures_of "$rev")"
  if ((n >= AUTO_DEPLOY_MAX_ATTEMPTS)); then
    err "not deploying $rev: it failed $n times. Push a fix, or run 'tinfirm-auto-deploy retry' to try again."
    exit 1
  fi
  deploy_revision "$rev" auto
}

# Runner mode: follow the server's revision, back as well as forward.
cmd_pass_runner() {
  local rev deployed n
  preflight
  lock
  server_revision || exit 0
  rev="$SERVER_REVISION"
  deployed="$(read_deployed)"
  if [ "$rev" = "$deployed" ]; then
    if [ -t 1 ] || dry_run; then log "up to date: the runner host and the server are at $rev"; fi
    exit 0
  fi
  n="$(failures_of "$rev")"
  if ((n >= AUTO_DEPLOY_MAX_ATTEMPTS)); then
    err "not updating the runner host to $rev: it failed $n times. Run 'tinfirm-auto-deploy retry' to try again."
    exit 1
  fi
  deploy_revision "$rev" auto
}

cmd_deploy() { # rev
  valid_rev "${1:-}" || die "usage: tinfirm-auto-deploy deploy <full 40-character revision>"
  preflight
  lock
  deploy_revision "$1" manual
}

cmd_mark_deployed() { # rev
  valid_rev "${1:-}" || die "usage: tinfirm-auto-deploy mark-deployed <full 40-character revision>"
  lock
  record_success "$1"
  log "recorded $1 as deployed; nothing else changed"
}

cmd_retry() {
  lock
  if [ -f "$STATE_FAILURES" ]; then
    log "forgetting: $(cat "$STATE_FAILURES") (revision, failed attempts)"
    if ! dry_run; then rm -f "$STATE_FAILURES"; fi
  else
    log "no failures recorded"
  fi
}

cmd_status() {
  local deployed image unit_state sum=""
  deployed="$(read_deployed)"
  echo "mode:            $AUTO_DEPLOY_MODE"
  echo "deployed:        ${deployed:-none recorded}"
  if runner_mode; then
    if server_revision >/dev/null; then
      echo "server:          $(server_url) reports $SERVER_REVISION"
    else
      echo "server:          $(server_url) reports no full revision"
    fi
  else
    if [ -n "$deployed" ]; then
      echo "  subject:       $(label "$AUTO_DEPLOY_SERVER_IMAGE:sha-$deployed" dev.tinfirm.commit-subject)"
    fi
    image="$(docker container inspect -f '{{.Config.Image}}' "$AUTO_DEPLOY_SERVER_CONTAINER" 2>/dev/null || echo "no container $AUTO_DEPLOY_SERVER_CONTAINER")"
    echo "server runs:     $image"
  fi
  echo "follows:         release $AUTO_DEPLOY_RELEASE of $AUTO_DEPLOY_REPO"
  if allow_unsigned; then
    echo "signature:       optional (TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1), key $AUTO_DEPLOY_PUBLIC_KEY"
  else
    echo "signature:       required, key $AUTO_DEPLOY_PUBLIC_KEY$([ -s "$AUTO_DEPLOY_PUBLIC_KEY" ] && ! grep -q PLACEHOLDER "$AUTO_DEPLOY_PUBLIC_KEY" || echo ' (missing: every pass fails)')"
  fi
  echo "failures:        $(cat "$STATE_FAILURES" 2>/dev/null || echo none)"
  echo "history (newest first):"
  if [ -f "$STATE_HISTORY" ]; then tail -n 5 "$STATE_HISTORY" | awk '{ a[NR] = $0 } END { for (i = NR; i > 0; i--) print "  " a[i] }'; fi
  echo "cached assets:   $(find "$STATE_CACHE" -mindepth 1 -maxdepth 1 -type d -printf '%f ' 2>/dev/null)"
  if [ -e "$AUTO_DEPLOY_RUNNER_BIN" ]; then
    sum="$(sha256_of "$AUTO_DEPLOY_RUNNER_BIN")"
    unit_state="$(systemctl is-active "$AUTO_DEPLOY_RUNNER_UNIT" 2>/dev/null || true)"
    echo "runner host:     $AUTO_DEPLOY_RUNNER_BIN (sha256 ${sum:0:16}…), unit $AUTO_DEPLOY_RUNNER_UNIT ${unit_state:-unknown}"
    if [ -f "$STATE_RELOAD_PENDING" ]; then echo "                 reload pending"; fi
  else
    echo "runner host:     not installed ($AUTO_DEPLOY_RUNNER_BIN)"
  fi
  if ! runner_mode; then
    echo "compose dir:     $AUTO_DEPLOY_COMPOSE_DIR"
    echo "backups:         $(find "$AUTO_DEPLOY_BACKUP_DIR" -maxdepth 1 -name 'tinfirm-auto-*.dump' 2>/dev/null | wc -l) automatic dumps in $AUTO_DEPLOY_BACKUP_DIR"
  fi
  echo "settings:        $CONFIG_DIR/env$([ -f "$CONFIG_DIR/env" ] || echo ' (none; defaults)')"
}

case "${1:-pass}" in
  pass) if runner_mode; then cmd_pass_runner; else cmd_pass; fi ;;
  deploy) cmd_deploy "${2:-}" ;;
  status) cmd_status ;;
  mark-deployed) cmd_mark_deployed "${2:-}" ;;
  retry) cmd_retry ;;
  -h | --help | help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *) die "unknown command '$1'; see tinfirm-auto-deploy help" ;;
esac
