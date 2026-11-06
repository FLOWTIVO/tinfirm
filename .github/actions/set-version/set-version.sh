#!/usr/bin/env bash
# Set a version in every versioned manifest the caller lists.
#
#   set-version.sh <version> <<'EOF'
#   pom:server/pom.xml:tinfirm-server
#   pom:model-api/pom.xml:tinfirm-model-api
#   npm:web
#   EOF
#
# The manifest list comes from the caller (stdin, one entry per line) so this script
# carries no knowledge of the repository's layout. Two kinds:
#
#   pom:<path>:<artifactId>   the <version> that FOLLOWS that <artifactId>
#   npm:<dir>                 package.json + package-lock.json in <dir> (`.` for the root)
#
# The pom edit is deliberately NOT `mvn versions:set`: that downloads
# versions-maven-plugin on every release and rewrites more of the file than we want.
# Anchoring on the module's own <artifactId> is what keeps the Spring Boot parent's
# <version> (which appears earlier, inside <parent>) and every dependency version
# untouched. It assumes a single-module pom with no <modules>.
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
    *)
      echo "error: unknown manifest kind in '$line' (want pom: or npm:)" >&2
      exit 1
      ;;
  esac
done

[ "$seen_any" = 1 ] || { echo "error: the manifest list is empty" >&2; exit 1; }
