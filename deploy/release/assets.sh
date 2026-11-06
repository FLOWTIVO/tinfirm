#!/usr/bin/env bash
# Builds, packages and publishes the GitHub Release assets of one Tinfirm revision: the
# runner host binaries and deploy/compose (docs/operations/auto-deploy.md). The reusable
# workflow .github/workflows/runner-artifacts.yml runs it for the rolling prerelease
# (every push to main) and for versioned releases; `make release-assets` runs the Linux
# part locally.
#
#   assets.sh version <release-tag>                        rolling-<sha7> for `rolling`, x.y.z for vx.y.z
#   assets.sh subject [<commit>]                           one-line subject (a merge: the PR title)
#   assets.sh runner-linux <target> <version> <outdir>     static musl build in Docker, packaged
#   assets.sh package <target> <version> <binary> <outdir> package a binary built on this host
#   assets.sh compose <version> <outdir>                   deploy/compose of HEAD, never a .env
#   assets.sh sums <dir>                                   REVISION (HEAD) and SHA256SUMS
#   assets.sh sign-sums <dir> <public key>                 SHA256SUMS.sig and SHA256SUMS.sigstore.json
#                                                          (cosign, COSIGN_PRIVATE_KEY/_PASSWORD;
#                                                          the key must be the public key's)
#   assets.sh verify-sums <dir> <public key>               SHA256SUMS.sig against the key (openssl)
#   assets.sh publish <release-tag> <dir>                  upload to the GitHub Release (gh)
#   assets.sh fetch <release-tag> <dir>                    download a versioned release's assets
#                                                          and check them (gh)
#   assets.sh publish-signature <release-tag> <dir>        upload the signature files (gh)
#
# Assets, with the same names in `rolling` and in versioned releases:
#   tinfirm-runner-host-<version>-<target>.tar.gz   .zip for Windows; one directory: the binary,
#                                                    LICENSE, ee/LICENSE and THIRD-PARTY-LICENSES.txt
#   tinfirm-compose-<version>.tar.gz                one directory with deploy/compose and LICENSE
#   REVISION                                         the full commit SHA, one line
#   SHA256SUMS                                       `sha256sum` format, over every other asset
#   SHA256SUMS.sig                                   base64 ECDSA signature of SHA256SUMS
#                                                    (cosign sign-blob): the release key of
#                                                    docs-site/content/cosign.pub for v<version>,
#                                                    the rolling key of deploy/compose/auto-deploy/
#                                                    cosign-rolling.pub for rolling
#   SHA256SUMS.sigstore.json                         the same signature as a cosign bundle
#
# Verify a download, with openssl alone or with cosign (no transparency log):
#   openssl base64 -d -A -in SHA256SUMS.sig -out SHA256SUMS.der
#   openssl dgst -sha256 -verify cosign.pub -signature SHA256SUMS.der SHA256SUMS
#   cosign verify-blob --key cosign.pub --bundle SHA256SUMS.sigstore.json \
#     --insecure-ignore-tlog=true SHA256SUMS
#   sha256sum -c --ignore-missing SHA256SUMS
#
# Runs with bash 3.2 (macOS) and Git Bash (Windows) too. Everything works on HEAD of the
# repository this script is in.
set -euo pipefail
export LC_ALL=C
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

die() { echo "assets.sh: $*" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

abs_dir() { mkdir -p "$1" && (cd "$1" && pwd); }

# Reproducible with GNU tar (Linux, CI): sorted entries, owner root, mtime of the commit.
# bsdtar (macOS) has no --sort, and must leave out the macOS metadata (._* AppleDouble files,
# extended attributes), which GNU tar on Linux would extract as junk files.
tar_gz() {
  local dir="$1" name="$2" dest="$3" epoch
  epoch="$(git -C "$ROOT" log -1 --format=%ct HEAD)"
  case "$(tar --version 2>/dev/null)" in
    *"GNU tar"*)
      tar -C "$dir" --sort=name --owner=0 --group=0 --numeric-owner --mtime="@${epoch}" \
        -cf - "$name" | gzip -n > "$dest" ;;
    *)
      COPYFILE_DISABLE=1 tar -C "$dir" --no-mac-metadata --no-xattrs --no-acls \
        --uid 0 --gid 0 --uname root --gname root -cf - "$name" | gzip -n > "$dest" ;;
  esac
}

cmd_version() {
  local tag="${1:?usage: assets.sh version <release-tag>}" sha
  case "$tag" in
    rolling)
      sha="$(git -C "$ROOT" rev-parse HEAD)"
      echo "rolling-${sha:0:7}" ;;
    v[0-9]*)
      echo "${tag#v}" ;;
    *)
      die "release tag must be 'rolling' or v<version>, not '$tag'" ;;
  esac
}

cmd_subject() {
  local rev="${1:-HEAD}" subject body line number
  subject="$(git -C "$ROOT" log -1 --format=%s "$rev")"
  # GitHub's merge commit says "Merge pull request #N from <branch>"; the first line of its
  # body is the pull request's title.
  if [[ $subject =~ ^Merge\ pull\ request\ \#([0-9]+)\  ]]; then
    number="${BASH_REMATCH[1]}"
    body="$(git -C "$ROOT" log -1 --format=%b "$rev")"
    while IFS= read -r line; do
      if [[ $line =~ [^[:space:]] ]]; then subject="$line (#$number)"; break; fi
    done <<<"$body"
  fi
  printf '%s\n' "$subject" | tr -d '\000-\011\013-\037\177'
}

cmd_package() {
  local target="${1:?target}" version="${2:?version}" binary="${3:?binary}" outdir="${4:?outdir}"
  local name="tinfirm-runner-host-${version}-${target}" out stage exe=tinfirm-runner-host
  [ -f "$binary" ] || die "no binary at $binary"
  out="$(abs_dir "$outdir")"
  stage="$(mktemp -d)"
  mkdir "$stage/$name"
  case "$target" in *-windows-*) exe=tinfirm-runner-host.exe ;; esac
  cp "$binary" "$stage/$name/$exe"
  chmod 755 "$stage/$name/$exe"
  # The licence and the notices of the crates the binary is built with (ADR-0082), the same
  # for every target: runner/THIRD-PARTY-LICENSES.txt, which CI keeps as Cargo.lock gives it.
  cp "$ROOT/LICENSE" "$ROOT/runner/THIRD-PARTY-LICENSES.txt" "$stage/$name/"
  # The binary holds the paid code of ee/runner (ADR-0100), under ee/LICENSE.
  mkdir "$stage/$name/ee"
  cp "$ROOT/ee/LICENSE" "$stage/$name/ee/LICENSE"
  chmod 644 "$stage/$name/LICENSE" "$stage/$name/THIRD-PARTY-LICENSES.txt" "$stage/$name/ee/LICENSE"
  case "$target" in
    *-windows-*)
      rm -f "$out/$name.zip"
      if command -v 7z >/dev/null 2>&1; then
        (cd "$stage" && 7z a -tzip -bd "$out/$name.zip" "$name" >/dev/null)
      else
        (cd "$stage" && zip -qr "$out/$name.zip" "$name")
      fi
      echo "$out/$name.zip" ;;
    *)
      tar_gz "$stage" "$name" "$out/$name.tar.gz"
      echo "$out/$name.tar.gz" ;;
  esac
  rm -rf "$stage"
}

cmd_runner_linux() {
  local target="${1:?target}" version="${2:?version}" outdir="${3:?outdir}" work
  case "$target" in
    x86_64-unknown-linux-musl | aarch64-unknown-linux-musl) ;;
    *) die "runner-linux builds x86_64-unknown-linux-musl or aarch64-unknown-linux-musl, not '$target'" ;;
  esac
  work="$(mktemp -d)"
  # The daemon's own builder (as in ci.yml), so the cache mounts persist between builds.
  # CACHE_SCOPE: the suffix of the Dockerfile's cache ids, `main` from runner-artifacts.yml
  # for main and the release branches, else `pr` (runner-musl.Dockerfile).
  # TINFIRM_RUNNER_BUILD: the asset's version, which `--version` shows next to the
  # workspace's (`0.1.0-SNAPSHOT (rolling-abc1234)`; ADR-0109).
  docker build --progress=plain -f "$ROOT/deploy/release/runner-musl.Dockerfile" --target binary \
    --build-arg "TARGET=$target" --build-arg "CACHE_SCOPE=${CACHE_SCOPE:-pr}" \
    --build-arg "TINFIRM_RUNNER_BUILD=$version" \
    --output "type=local,dest=$work" "$ROOT" >&2
  cmd_package "$target" "$version" "$work/tinfirm-runner-host" "$outdir"
  rm -rf "$work"
}

cmd_compose() {
  local version="${1:?version}" outdir="${2:?outdir}" name out stage listing bad
  name="tinfirm-compose-${version}"
  out="$(abs_dir "$outdir")"
  stage="$(mktemp -d)"
  # Committed files only: a local .env is never committed (.gitignore), so it never gets in.
  git -C "$ROOT" archive --format=tar HEAD deploy/compose LICENSE | tar -x -C "$stage"
  mv "$stage/deploy/compose" "$stage/$name"
  mv "$stage/LICENSE" "$stage/$name/LICENSE"
  tar_gz "$stage" "$name" "$out/$name.tar.gz"
  rm -rf "$stage"

  listing="$(tar -tzf "$out/$name.tar.gz")"
  grep -qx "$name/compose.yaml" <<<"$listing" || die "$name.tar.gz has no compose.yaml"
  grep -qx "$name/LICENSE" <<<"$listing" || die "$name.tar.gz has no LICENSE"
  # .env.example holds the public development defaults; any other .env* is a secret.
  bad="$(grep -E '(^|/)\.env(\..*)?$' <<<"$listing" | grep -vE '(^|/)\.env\.example$' || true)"
  [ -z "$bad" ] || die "$name.tar.gz would contain: $bad"
  echo "$out/$name.tar.gz"
}

cmd_sums() {
  local dir="${1:?dir}"
  [ -d "$dir" ] || die "no directory $dir"
  git -C "$ROOT" rev-parse HEAD > "$dir/REVISION"
  (
    cd "$dir"
    rm -f SHA256SUMS SHA256SUMS.sig SHA256SUMS.sigstore.json
    set -- *
    sha256 "$@" > SHA256SUMS.tmp
    mv SHA256SUMS.tmp SHA256SUMS
  )
  cat "$dir/SHA256SUMS"
}

# The signature files beside SHA256SUMS: not assets of their own, never listed in it.
is_signature() {
  case "$1" in SHA256SUMS.sig | SHA256SUMS.sigstore.json) return 0 ;; *) return 1 ;; esac
}

placeholder_key() { grep -q PLACEHOLDER "$1"; }

# Signs <dir>/SHA256SUMS with cosign (on the PATH; sign-images.sh install), the key in
# COSIGN_PRIVATE_KEY opened with COSIGN_PASSWORD, which must be the key of <public key>.
# No transparency log, as for the images (sign-images.sh): hosts and users verify
# offline. Writes SHA256SUMS.sigstore.json (cosign's bundle) and SHA256SUMS.sig (the base64
# signature in it, which openssl verifies; python3 reads it out), then verifies both as a
# host would.
cmd_sign_sums() {
  local dir="${1:?usage: assets.sh sign-sums <dir> <public key>}" pub="${2:?public key}" derived config
  [ -s "$dir/SHA256SUMS" ] || die "no $dir/SHA256SUMS; run 'assets.sh sums' first"
  [ -s "$pub" ] || die "no public key $pub"
  if placeholder_key "$pub"; then
    die "$pub is a placeholder, not a public key: commit the public half of the signing key first"
  fi
  if [ -z "${COSIGN_PRIVATE_KEY:-}" ] || [ -z "${COSIGN_PASSWORD:-}" ]; then
    die "COSIGN_PRIVATE_KEY and COSIGN_PASSWORD are not both set"
  fi
  derived="$(cosign public-key --key env://COSIGN_PRIVATE_KEY)" ||
    die "COSIGN_PRIVATE_KEY does not open with COSIGN_PASSWORD"
  if [ "$(printf '%s\n' "$derived" | tr -d '\r')" != "$(tr -d '\r' < "$pub")" ]; then
    die "COSIGN_PRIVATE_KEY is not the key of $pub: hosts would refuse the signature"
  fi
  config="$(mktemp -d)"
  cosign signing-config create --out "$config/signing-config.json"
  rm -f "$dir/SHA256SUMS.sig" "$dir/SHA256SUMS.sigstore.json"
  cosign sign-blob --yes --key env://COSIGN_PRIVATE_KEY \
    --signing-config "$config/signing-config.json" \
    --bundle "$dir/SHA256SUMS.sigstore.json" \
    "$dir/SHA256SUMS" > /dev/null
  rm -rf "$config"
  [ -s "$dir/SHA256SUMS.sigstore.json" ] || die "cosign wrote no bundle"
  chmod 644 "$dir/SHA256SUMS.sigstore.json"
  # cosign 3 writes the signature into the bundle only (--output-signature is ignored with
  # its bundle format): the same base64 signature, taken from it, for openssl.
  python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["messageSignature"]["signature"])' \
    "$dir/SHA256SUMS.sigstore.json" > "$dir/SHA256SUMS.sig"
  cosign verify-blob --key "$pub" --bundle "$dir/SHA256SUMS.sigstore.json" \
    --insecure-ignore-tlog=true "$dir/SHA256SUMS" > /dev/null ||
    die "the bundle does not verify against $pub"
  cmd_verify_sums "$dir" "$pub"
}

# Verifies <dir>/SHA256SUMS.sig against <public key> with openssl alone, as
# tinfirm-auto-deploy does on a host: cosign's signature of a blob is the base64 of an
# ECDSA (P-256, SHA-256) signature in DER.
cmd_verify_sums() {
  local dir="${1:?usage: assets.sh verify-sums <dir> <public key>}" pub="${2:?public key}" der
  [ -s "$dir/SHA256SUMS" ] && [ -s "$dir/SHA256SUMS.sig" ] || die "no SHA256SUMS or SHA256SUMS.sig in $dir"
  der="$(mktemp)"
  if ! openssl base64 -d -A -in "$dir/SHA256SUMS.sig" -out "$der" ||
    ! openssl dgst -sha256 -verify "$pub" -signature "$der" "$dir/SHA256SUMS" > /dev/null; then
    rm -f "$der"
    die "SHA256SUMS.sig in $dir is not a signature of SHA256SUMS by $pub"
  fi
  rm -f "$der"
  echo "SHA256SUMS in $dir is signed by $pub"
}

# Downloads every asset of the versioned release <tag> into <dir> (gh: GH_TOKEN, GH_REPO)
# and checks, before anything is signed, that REVISION is the commit this script is at
# (the release tag's checkout) and that SHA256SUMS lists every asset with its sum.
cmd_fetch() {
  local tag="${1:?usage: assets.sh fetch <release-tag> <dir>}" dir="${2:?dir}" name listed rev
  : "${GH_REPO:?GH_REPO is not set}"
  [ "$tag" != rolling ] || die "fetch is for a versioned release; rolling is signed where it is built"
  mkdir -p "$dir"
  gh release download "$tag" --dir "$dir" --clobber
  [ -s "$dir/REVISION" ] && [ -s "$dir/SHA256SUMS" ] || die "release $tag has no REVISION or SHA256SUMS"
  rev="$(tr -d '[:space:]' < "$dir/REVISION")"
  [ "$rev" = "$(git -C "$ROOT" rev-parse HEAD)" ] ||
    die "REVISION of $tag is $rev, not the tag's commit $(git -C "$ROOT" rev-parse HEAD)"
  (cd "$dir" && sha256 -c --quiet SHA256SUMS) || die "SHA256SUMS of $tag does not match its assets"
  listed="$(awk '{ f = $2; sub(/^\*/, "", f); print f }' "$dir/SHA256SUMS")"
  for name in "$dir"/*; do
    name="$(basename "$name")"
    [ "$name" = SHA256SUMS ] && continue
    is_signature "$name" && continue
    grep -qxF "$name" <<<"$listed" || die "$tag has the asset $name, which SHA256SUMS does not list"
  done
  echo "The assets of $tag ($rev) match SHA256SUMS"
}

cmd_publish_signature() {
  local tag="${1:?usage: assets.sh publish-signature <release-tag> <dir>}" dir="${2:?dir}"
  : "${GH_REPO:?GH_REPO is not set}"
  [ -s "$dir/SHA256SUMS.sig" ] && [ -s "$dir/SHA256SUMS.sigstore.json" ] ||
    die "no signature in $dir; run 'assets.sh sign-sums' first"
  gh release upload "$tag" --clobber "$dir/SHA256SUMS.sig" "$dir/SHA256SUMS.sigstore.json"
  echo "Uploaded the signature of SHA256SUMS to $tag"
}

# Uploads <dir> to the GitHub Release <tag> with gh (GH_TOKEN, GH_REPO). A versioned release
# must exist already (.github/workflows/reusable-release.yml creates it). `rolling` is
# created on first use, and its assets are replaced in an order that lets hosts tell a
# half-updated release apart (they check REVISION against SHA256SUMS, and REVISION again
# after their downloads).
cmd_publish() {
  local tag="${1:?release tag}" dir="${2:?dir}" rev short tip title notes name subject run_url server
  local verify_key assets=() signature=()
  : "${GH_REPO:?GH_REPO is not set}"
  server="${GITHUB_SERVER_URL:-https://github.com}"
  [ -s "$dir/REVISION" ] && [ -s "$dir/SHA256SUMS" ] || die "$dir has no REVISION or SHA256SUMS; run 'assets.sh sums' first"
  (cd "$dir" && sha256 -c --quiet SHA256SUMS) || die "SHA256SUMS does not match the files in $dir"
  rev="$(cat "$dir/REVISION")"

  for name in "$dir"/*; do
    case "$(basename "$name")" in
      REVISION | SHA256SUMS) ;;
      SHA256SUMS.sig | SHA256SUMS.sigstore.json) signature+=("$name") ;;
      *) assets+=("$name") ;;
    esac
  done
  if ((${#signature[@]} > 0)); then
    verify_key="$ROOT/deploy/compose/auto-deploy/cosign-rolling.pub"
    [ "$tag" = rolling ] || verify_key="$ROOT/docs-site/content/cosign.pub"
    cmd_verify_sums "$dir" "$verify_key" > /dev/null
  fi

  # A versioned release: REVISION last, so that a host following it (`latest`) finds the
  # other assets once it sees REVISION. Its signature comes later, from release.yml's
  # sign-assets: a signature of an earlier SHA256SUMS is removed first, so that hosts wait
  # for the new one instead of refusing a mismatch.
  if [ "$tag" != rolling ]; then
    gh release view "$tag" >/dev/null || die "no GitHub Release $tag in $GH_REPO; the release did not create it"
    if ((${#signature[@]} == 0)); then
      for name in SHA256SUMS.sig SHA256SUMS.sigstore.json; do
        gh release delete-asset "$tag" "$name" --yes >/dev/null 2>&1 || true
      done
    fi
    gh release upload "$tag" --clobber "${assets[@]}" "$dir/SHA256SUMS"
    if ((${#signature[@]} > 0)); then gh release upload "$tag" --clobber "${signature[@]}"; fi
    gh release upload "$tag" --clobber "$dir/REVISION"
    echo "Uploaded the assets of $rev to $tag"
    return
  fi

  # A re-run of an older run must not move `rolling` back to an older commit.
  tip="$(gh api "repos/$GH_REPO/commits/main" --jq .sha)"
  if [ "$tip" != "$rev" ]; then
    echo "::notice title=rolling not moved::main is at $tip now, not $rev; the run of the newer commit updates rolling."
    return
  fi

  short="${rev:0:7}"
  title="Rolling (main $short)"
  subject="$(cmd_subject "$rev")"
  run_url=""
  if [ -n "${GITHUB_RUN_ID:-}" ]; then run_url="$server/$GH_REPO/actions/runs/$GITHUB_RUN_ID"; fi
  notes="$(cat <<NOTES
Rolling prerelease of \`main\`: every push to main that passes CI replaces it. It is not a
release, has no version and no support; use a versioned release for anything you rely on.

- Commit: [\`$short\`]($server/$GH_REPO/commit/$rev) $subject
- Server image: \`ghcr.io/flowtivo/tinfirm-server:sha-$rev\`
${run_url:+- Build: $run_url
}- Assets: the runner host binaries, \`deploy/compose\` as \`tinfirm-compose-rolling-$short.tar.gz\`,
  \`REVISION\` (the commit), \`SHA256SUMS\` and, when the run signed it, its signature
  \`SHA256SUMS.sig\` by the rolling key (\`deploy/compose/auto-deploy/cosign-rolling.pub\`, not
  the release key). Verify the signature, then the downloads with
  \`sha256sum -c --ignore-missing SHA256SUMS\` ([assets.sh]($server/$GH_REPO/blob/main/deploy/release/assets.sh)).

Hosts that follow this prerelease: [auto-deploy]($server/$GH_REPO/blob/main/docs/operations/auto-deploy.md).
A runner host from a release: [runner/README.md]($server/$GH_REPO/blob/main/runner/README.md#install-from-a-release).
NOTES
)"

  if ! gh release view rolling >/dev/null 2>&1; then
    gh release create rolling --target "$rev" --prerelease --latest=false --title "$title" --notes "$notes"
  fi
  # 1. This revision's assets (new names), then SHA256SUMS: a host that still reads the old
  #    REVISION sees that SHA256SUMS no longer matches it, and tries again at its next pass.
  #    The signature after SHA256SUMS, never before: until REVISION moves, a host that reads
  #    the new SHA256SUMS stops at that mismatch before it checks the signature, and one
  #    that still reads the old SHA256SUMS still finds its old signature.
  #    Unsigned (the run had no rolling key): the previous signature goes first; it would not match.
  if ((${#signature[@]} == 0)); then
    for name in SHA256SUMS.sig SHA256SUMS.sigstore.json; do
      gh release delete-asset rolling "$name" --yes >/dev/null 2>&1 || true
    done
  fi
  gh release upload rolling --clobber "${assets[@]}"
  gh release upload rolling --clobber "$dir/SHA256SUMS"
  if ((${#signature[@]} > 0)); then gh release upload rolling --clobber "${signature[@]}"; fi
  # 2. The tag and the page.
  gh api -X PATCH "repos/$GH_REPO/git/refs/tags/rolling" -f sha="$rev" -F force=true >/dev/null
  gh release edit rolling --prerelease --latest=false --title "$title" --notes "$notes" >/dev/null
  # 3. REVISION last: it is what the hosts follow.
  gh release upload rolling --clobber "$dir/REVISION"
  # 4. The previous revision's assets.
  gh release view rolling --json assets --jq '.assets[].name' | while IFS= read -r name; do
    if [ ! -e "$dir/$name" ]; then gh release delete-asset rolling "$name" --yes; fi
  done
  echo "rolling is now $rev ($subject)"
}

cmd="${1:-}"
[ $# -gt 0 ] && shift
case "$cmd" in
  version) cmd_version "$@" ;;
  subject) cmd_subject "$@" ;;
  runner-linux) cmd_runner_linux "$@" ;;
  package) cmd_package "$@" ;;
  compose) cmd_compose "$@" ;;
  sums) cmd_sums "$@" ;;
  sign-sums) cmd_sign_sums "$@" ;;
  verify-sums) cmd_verify_sums "$@" ;;
  publish) cmd_publish "$@" ;;
  fetch) cmd_fetch "$@" ;;
  publish-signature) cmd_publish_signature "$@" ;;
  *) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
