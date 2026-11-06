#!/usr/bin/env bash
# Fails when a workflow or an action of this repository calls anything that can move under
# it (M18 of the security review of 2026-10-27, ADR-0106): every `uses:` is a path of this
# repository (`./...`), a third-party action pinned by its 40-character commit SHA, or a
# `docker://` image pinned by digest; and every scanner image of reusable-security.yml is
# pinned by digest. A tag (`@v7`) or a branch (`@main`) fails.
#
# It reads the files as text, so it sees `uses:` in any spelling YAML allows on one line: a
# block step (`- uses: x`), a quoted key or value, a space before the colon, a flow-style
# step (`- { uses: x, with: ... }`). Semgrep's rule against mutable action tags, which the
# security scan runs, checks the same from the parsed YAML.
#
# Run from the repository root: `.github/check-pins.sh` (the helm job in ci.yml, and
# `make deploy-lint`).
set -euo pipefail

files=()
while IFS= read -r f; do files+=("$f"); done < <(
  {
    find .github/workflows -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \)
    find . -type f \( -name action.yml -o -name action.yaml \) \
      -not -path './.git/*' -not -path '*/node_modules/*' -not -path './.ci-actions/*'
  } | sed 's#^\./##' | sort -u
)

bad=0
for f in "${files[@]}"; do
  # Every `uses` key on a line, with its value up to the next comma, brace or comment.
  while IFS= read -r hit; do
    line="${hit%%:*}"
    ref="${hit#*:}"
    ref="$(printf '%s' "$ref" | sed -E "s/^[\"']?uses[\"']?[[:space:]]*:[[:space:]]*//; s/[[:space:]]+\$//; s/^[\"']//; s/[\"']\$//")"
    case "$ref" in
      ./*) continue ;;
      docker://*)
        if printf '%s' "$ref" | grep -Eq '@sha256:[0-9a-f]{64}$'; then continue; fi ;;
      *)
        if printf '%s' "$ref" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$'; then continue; fi ;;
    esac
    echo "::error file=$f,line=$line::not pinned by commit SHA or digest: ${ref:-<empty>}"
    bad=1
  done < <(grep -noE "(^|[[:space:]{,-])[\"']?uses[\"']?[[:space:]]*:[[:space:]]*[^,}#[:space:]]*" "$f" \
             | sed -E 's/^([0-9]+):[[:space:]{,-]*/\1:/' || true)
done

# The scanner images run with the workspace mounted: each by digest, with its tag for people.
f=.github/workflows/reusable-security.yml
while IFS= read -r hit; do
  line="${hit%%:*}"
  if ! printf '%s' "$hit" | grep -Eq '@sha256:[0-9a-f]{64}[[:space:]]*$'; then
    echo "::error file=$f,line=$line::scanner image not pinned by digest: ${hit#*:}"
    bad=1
  fi
done < <(grep -nE '^[[:space:]]+[A-Z0-9_]+_IMAGE:' "$f")

if [ "$bad" -ne 0 ]; then
  echo "Pin each action by its commit SHA with the release in a comment (# vX.Y.Z), and each image by tag@sha256:digest."
  exit 1
fi
echo "Every action and scanner image is pinned (${#files[@]} files)."
