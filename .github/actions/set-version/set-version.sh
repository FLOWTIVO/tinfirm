#!/usr/bin/env bash
# Set a version in every versioned manifest the caller lists.
#
#   set-version.sh <version> <<'EOF'
#   pom:server/pom.xml:tinfirm-server
#   npm:web
#   cargo:runner
#   EOF
#
# The manifest list comes from the caller (stdin, one entry per line) so this script
# carries no knowledge of the repository's layout. Three kinds:
#
#   pom:<path>:<artifactId>   the <version> that FOLLOWS that <artifactId>
#   npm:<dir>                 package.json + package-lock.json in <dir> (`.` for the root)
#   cargo:<dir>               the workspace's version in <dir>/Cargo.toml + <dir>/Cargo.lock
#
# The pom edit is deliberately NOT `mvn versions:set`: that downloads
# versions-maven-plugin on every release and rewrites more of the file than we want.
# Anchoring on the module's own <artifactId> is what keeps the Spring Boot parent's
# <version> (which appears earlier, inside <parent>) and every dependency version
# untouched. It assumes a single-module pom with no <modules>.
#
# The Cargo edit is deliberately NOT `cargo update --workspace` or `cargo set-version`:
# the release jobs have no Rust toolchain, and an update may resolve against the registry.
# It sets `version` in the workspace's `[workspace.package]` (the crates take it with
# `version.workspace = true`), then the same version in Cargo.lock for every package of
# the workspace: the lock's packages without a `source` (path packages, which are the
# workspace's) that had the old version. A crate of the registry with that same version
# keeps it. A lock left at the old version makes `cargo build --locked` fail ("cannot
# update the lock file ... because --locked was passed"), which is what this keeps in step.
# Cargo takes `1.0.1-SNAPSHOT` as a semver prerelease, so the next-version commit's
# version is the same string for Cargo, Maven and npm.
set -euo pipefail

VERSION="${1:?usage: set-version.sh <version> < manifest-list}"

# Semver, optionally with a prerelease. Rejects a leading `v` on purpose — the tag
# carries the `v`, the manifests do not.
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "error: '$VERSION' is not a semver version (expected 1.2.3 or 1.2.3-beta.4, no leading 'v')" >&2
  exit 1
fi

set_pom_version() {
  local pom="$1" artifact="$2"
  [ -f "$pom" ] || { echo "error: no $pom" >&2; exit 1; }
  VERSION="$VERSION" ARTIFACT="$artifact" awk '
    $0 ~ "<artifactId>" ENVIRON["ARTIFACT"] "</artifactId>" { seen = 1 }
    seen && !done && /<version>/ {
      sub(/<version>[^<]*<\/version>/, "<version>" ENVIRON["VERSION"] "</version>")
      done = 1
    }
    { print }
    END { if (!done) { print "error: no <version> after <artifactId>" ENVIRON["ARTIFACT"] "</artifactId>" > "/dev/stderr"; exit 1 } }
  ' "$pom" > "$pom.tmp"
  mv "$pom.tmp" "$pom"
  echo "  $pom -> $VERSION"
}

set_npm_version() {
  local dir="$1"
  [ -f "$dir/package.json" ] || { echo "error: no $dir/package.json" >&2; exit 1; }
  # `npm version` keeps package.json and package-lock.json in sync, which a sed on
  # package.json alone does not — a stale lock version makes `npm ci` rewrite the lock.
  # --allow-same-version so re-running a release that already set it is not an error.
  # --ignore-scripts: no lifecycle script of the package runs, since the release jobs that
  # call this hold a token that may push past the branch's ruleset (ADR-0106).
  ( cd "$dir" && npm version "$VERSION" --no-git-tag-version --allow-same-version --ignore-scripts > /dev/null )
  echo "  $dir/package.json -> $VERSION"
}

set_cargo_version() {
  local dir="${1%/}" manifest lock old
  manifest="$dir/Cargo.toml"
  lock="$dir/Cargo.lock"
  [ -f "$manifest" ] || { echo "error: no $manifest" >&2; exit 1; }
  [ -f "$lock" ] || { echo "error: no $lock" >&2; exit 1; }
  # The first `version = "..."` of [workspace.package], up to the next table.
  old="$(awk '
    /^\[/ { in_pkg = ($0 ~ /^\[workspace\.package\][[:space:]]*$/) ; next }
    in_pkg && /^version[[:space:]]*=[[:space:]]*"[^"]*"/ {
      v = $0; sub(/^version[[:space:]]*=[[:space:]]*"/, "", v); sub(/".*/, "", v); print v; exit
    }
  ' "$manifest")"
  [ -n "$old" ] || { echo "error: no version in [workspace.package] of $manifest" >&2; exit 1; }
  # Cargo.lock first, into a temporary file: each [[package]] block is buffered until its end,
  # then its version line is rewritten when the block has no source and the old version. When
  # the manifest already has the version (a re-run, as npm's --allow-same-version), this checks
  # that the lock has it too, so a lock left stale by an earlier failure is never reported done.
  OLD="$old" VERSION="$VERSION" awk '
    function flush(   i) {
      if (n == 0) return
      if (is_pkg && !has_source && vline && version == ENVIRON["OLD"]) {
        buf[vline] = "version = \"" ENVIRON["VERSION"] "\""
        matched++
      }
      for (i = 1; i <= n; i++) print buf[i]
      n = 0; is_pkg = 0; has_source = 0; vline = 0; version = ""
    }
    /^\[/ { flush(); is_pkg = ($0 == "[[package]]") }
    {
      buf[++n] = $0
      if (is_pkg && $0 ~ /^source = /) has_source = 1
      if (is_pkg && !vline && $0 ~ /^version = "/) {
        vline = n; version = $0; sub(/^version = "/, "", version); sub(/"$/, "", version)
      }
    }
    END {
      flush()
      if (!matched) { print "error: no package of the workspace with version " ENVIRON["OLD"] " in the lock" > "/dev/stderr"; exit 1 }
    }
  ' "$lock" > "$lock.tmp" || { rm -f "$lock.tmp"; exit 1; }
  # Then the manifest, so that a failure above leaves both files as they were.
  VERSION="$VERSION" awk '
    /^\[/ { in_pkg = ($0 ~ /^\[workspace\.package\][[:space:]]*$/) }
    in_pkg && !done && /^version[[:space:]]*=[[:space:]]*"[^"]*"/ {
      sub(/"[^"]*"/, "\"" ENVIRON["VERSION"] "\"")
      done = 1
    }
    { print }
  ' "$manifest" > "$manifest.tmp"
  mv "$lock.tmp" "$lock"
  mv "$manifest.tmp" "$manifest"
  echo "  $manifest, $lock -> $VERSION"
}

seen_any=0
while IFS= read -r line; do
  # Skip blanks and comments so a caller can annotate its manifest list.
  line="${line%%#*}"
  line="$(echo "$line" | xargs)" || true
  [ -n "$line" ] || continue
  seen_any=1
  case "$line" in
    pom:*)
      IFS=':' read -r _ path artifact <<< "$line"
      [ -n "${path:-}" ] && [ -n "${artifact:-}" ] || {
        echo "error: malformed pom entry '$line' (want pom:<path>:<artifactId>)" >&2; exit 1; }
      set_pom_version "$path" "$artifact"
      ;;
    npm:*)
      IFS=':' read -r _ dir <<< "$line"
      [ -n "${dir:-}" ] || { echo "error: malformed npm entry '$line' (want npm:<dir>)" >&2; exit 1; }
      set_npm_version "$dir"
      ;;
    cargo:*)
      IFS=':' read -r _ dir <<< "$line"
      [ -n "${dir:-}" ] || { echo "error: malformed cargo entry '$line' (want cargo:<dir>)" >&2; exit 1; }
      set_cargo_version "$dir"
      ;;
    *)
      echo "error: unknown manifest kind in '$line' (want pom:, npm: or cargo:)" >&2
      exit 1
      ;;
  esac
done

[ "$seen_any" = 1 ] || { echo "error: the manifest list is empty" >&2; exit 1; }
