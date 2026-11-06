#!/usr/bin/env bash
# Print the development version that FOLLOWS a released version.
#
#   .github/actions/set-version/next-version.sh 1.0.0-beta.2   -> 1.0.0-beta.3-SNAPSHOT
#   .github/actions/set-version/next-version.sh 1.0.0          -> 1.0.1-SNAPSHOT
#
# Rule: if the released version ends in a numeric prerelease segment, increment that
# segment (a beta line keeps running); otherwise increment the patch. The `-SNAPSHOT`
# suffix marks the manifests as "between releases" — it is valid semver prerelease, so
# npm accepts it as readily as Maven does.
set -euo pipefail

VERSION="${1:?usage: next-version.sh <released-version>}"
VERSION="${VERSION#v}"

if [[ "$VERSION" =~ ^([0-9]+\.[0-9]+\.[0-9]+-.*[.-])([0-9]+)$ ]]; then
  echo "${BASH_REMATCH[1]}$(( BASH_REMATCH[2] + 1 ))-SNAPSHOT"
elif [[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$(( BASH_REMATCH[3] + 1 ))-SNAPSHOT"
else
  echo "error: cannot derive a next version from '$VERSION' — pass next_version explicitly" >&2
  exit 1
fi
