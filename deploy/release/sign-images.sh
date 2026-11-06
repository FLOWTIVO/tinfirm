#!/usr/bin/env bash
# Signs the images and the Helm chart of a release with FLOWTIVO's cosign key pair, and
# attests an SBOM of each image (ADR-0081). release.yml runs it in the protected `release`
# environment, the only place that holds the key: `check-key` before anything is published,
# `sign` and `sign-chart` after the push. Customers verify offline against docs-site/content/cosign.pub
# (docs-site/content/operator-guide/verify-images.md).
#
#   sign-images.sh install <dir> cosign [syft]  the pinned tools into <dir>, put on the
#                                               PATH of the job's later steps
#   sign-images.sh check-key                    the key and its password are set, and the
#                                               key is the one of cosign.pub
#   sign-images.sh sign <outdir> <image:tag>... each image by its digest, every manifest
#                                               of an index too; an SPDX SBOM of each
#                                               platform, attested to the digest; both
#                                               verified against cosign.pub; the SBOMs
#                                               written to <outdir>
#   sign-images.sh sign-chart <chart:version>... each OCI chart by its digest, verified
#                                               against cosign.pub; no SBOM, a chart is
#                                               no image
#
# The tools are pinned here, for both jobs. cosign is its release binary, checked against
# the SHA-256 sums of the release's cosign_checksums.txt (which sigstore/cosign-installer
# v4.1.2 also pins for this version). syft is its binary from anchore's image, pinned by
# digest; the image is pulled once by the runner's daemon. Change a version and its sums
# together.
#
# `sign` and `check-key` need cosign and syft on the PATH, COSIGN_PRIVATE_KEY (the encrypted key, PEM) and
# COSIGN_PASSWORD, and for `sign` a DOCKER_CONFIG logged in to the registry. Nothing goes
# to a transparency log (an empty signing config):
# customers verify without a route to the internet.
set -euo pipefail
export LC_ALL=C

COSIGN_VERSION=3.0.6
COSIGN_SHA256_AMD64=c956e5dfcac53d52bcf058360d579472f0c1d2d9b69f55209e256fe7783f4c74
COSIGN_SHA256_ARM64=bedac92e8c3729864e13d4a17048007cfafa79d5deca993a43a90ffe018ef2b8
SYFT_IMAGE=docker.io/anchore/syft:v1.54.1@sha256:3eb5379ba7b409c3f4069b686110527af0c47df993fa5c10d13e7cf34f49b1aa

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PUBLIC_KEY="$ROOT/docs-site/content/cosign.pub"

die() { echo "::error title=Image signing::$*" >&2; exit 1; }

cmd_install() {
  local dir="${1:?usage: sign-images.sh install <dir> cosign [syft]}" tool arch sum container
  shift
  mkdir -p "$dir/bin"
  for tool in "$@"; do
    case "$tool" in
      cosign)
        case "$(uname -m)" in
          x86_64) arch=amd64; sum="$COSIGN_SHA256_AMD64" ;;
          aarch64) arch=arm64; sum="$COSIGN_SHA256_ARM64" ;;
          *) die "no pinned cosign for $(uname -m)" ;;
        esac
        curl -fsSL --retry 3 --max-time 120 -o "$dir/cosign" \
          "https://github.com/sigstore/cosign/releases/download/v${COSIGN_VERSION}/cosign-linux-${arch}"
        echo "${sum}  $dir/cosign" | sha256sum -c -
        install -m 755 "$dir/cosign" "$dir/bin/cosign"
        "$dir/bin/cosign" version 2>&1 | grep -i gitversion ;;
      syft)
        docker pull -q "$SYFT_IMAGE"
        container="$(docker create "$SYFT_IMAGE")"
        docker cp "$container:/syft" "$dir/bin/syft"
        docker rm "$container" > /dev/null
        "$dir/bin/syft" version | sed -n 2p ;;
      *) die "install: cosign or syft, not $tool" ;;
    esac
  done
  if [ -n "${GITHUB_PATH:-}" ]; then
    echo "$dir/bin" >> "$GITHUB_PATH"
  fi
}

require_key() {
  if [ -z "${COSIGN_PRIVATE_KEY:-}" ] || [ -z "${COSIGN_PASSWORD:-}" ]; then
    die "COSIGN_PRIVATE_KEY and COSIGN_PASSWORD are not both set. They are secrets of the protected 'release' environment only (ADR-0081): create them there (cosign generate-key-pair, offline). No image is published unsigned."
  fi
}

cmd_check_key() {
  require_key
  if grep -q PLACEHOLDER "$PUBLIC_KEY"; then
    die "docs-site/content/cosign.pub is a placeholder: commit the public key of the release key pair first (ADR-0081)."
  fi
  local derived
  derived="$(cosign public-key --key env://COSIGN_PRIVATE_KEY)" \
    || die "COSIGN_PRIVATE_KEY does not open with COSIGN_PASSWORD."
  if [ "$(printf '%s\n' "$derived" | tr -d '\r')" != "$(tr -d '\r' < "$PUBLIC_KEY")" ]; then
    die "COSIGN_PRIVATE_KEY is not the key of docs-site/content/cosign.pub: customers would not verify the images."
  fi
  echo "The signing key is the one of docs-site/content/cosign.pub."
}

# The digest a tag names now. The images are signed by it, never by the tag.
digest() {
  docker buildx imagetools inspect "$1" --format '{{.Manifest.Digest}}'
}

# The platforms of an index (os/arch[/variant]), without BuildKit's attestation
# manifests (unknown/unknown); empty for a single manifest.
platforms() {
  local media
  media="$(docker buildx imagetools inspect "$1" --format '{{.Manifest.MediaType}}')"
  case "$media" in
    *index*|*manifest.list*)
      docker buildx imagetools inspect "$1" --format \
        '{{range .Manifest.Manifests}}{{if ne .Platform.OS "unknown"}}{{.Platform.OS}}/{{.Platform.Architecture}}{{with .Platform.Variant}}/{{.}}{{end}}{{"\n"}}{{end}}{{end}}' ;;
  esac
}

# A signing config with no transparency log, no timestamp authority and no certificate:
# the key alone. Removed when the script exits.
signing_config() {
  config="$(mktemp -d)"
  trap 'rm -rf "$config"' EXIT
  cosign signing-config create --out "$config/signing-config.json"
}

cmd_sign_chart() {
  [ "$#" -gt 0 ] || die "usage: sign-images.sh sign-chart <chart:version>..."
  require_key
  signing_config
  local ref digest pinned
  for ref in "$@"; do
    digest="$(digest "$ref")"
    [ -n "$digest" ] || die "$ref names no digest"
    pinned="${ref%:*}@${digest}"
    cosign sign --yes --key env://COSIGN_PRIVATE_KEY \
      --signing-config "$config/signing-config.json" "$pinned"
    cosign verify --key "$PUBLIC_KEY" --insecure-ignore-tlog=true "$pinned" > /dev/null
    echo "::notice title=Signed::${pinned}"
  done
}

cmd_sign() {
  local out="${1:?usage: sign-images.sh sign <outdir> <image:tag>...}"
  shift
  [ "$#" -gt 0 ] || die "no image to sign"
  require_key
  mkdir -p "$out"
  signing_config
  local ref image digest pinned name platform suffix sbom list
  for ref in "$@"; do
    image="${ref%:*}"
    digest="$(digest "$ref")"
    [ -n "$digest" ] || die "$ref names no digest"
    pinned="${image}@${digest}"
    echo "::group::$ref ($digest)"
    cosign sign --yes --recursive --key env://COSIGN_PRIVATE_KEY \
      --signing-config "$config/signing-config.json" "$pinned"
    name="$(basename "$image")"
    list="$(platforms "$pinned")"
    if [ -z "$list" ]; then
      list="-"
    fi
    for platform in $list; do
      suffix=""
      [ "$platform" = "-" ] || suffix="-${platform//\//-}"
      sbom="$out/${name}-${ref##*:}${suffix}.spdx.json"
      if [ "$platform" = "-" ]; then
        syft scan "registry:${pinned}" -q -o "spdx-json=${sbom}"
      else
        syft scan "registry:${pinned}" -q --platform "$platform" -o "spdx-json=${sbom}"
      fi
      cosign attest --yes --key env://COSIGN_PRIVATE_KEY \
        --signing-config "$config/signing-config.json" \
        --type spdxjson --predicate "$sbom" "$pinned"
    done
    # As a customer verifies: the public key alone, no transparency log.
    cosign verify --key "$PUBLIC_KEY" --insecure-ignore-tlog=true "$pinned" > /dev/null
    cosign verify-attestation --key "$PUBLIC_KEY" --insecure-ignore-tlog=true \
      --type spdxjson "$pinned" > /dev/null
    echo "::endgroup::"
    echo "::notice title=Signed::${pinned}"
  done
}

cmd="${1:-}"
shift || true
case "$cmd" in
  install) cmd_install "$@" ;;
  check-key) cmd_check_key "$@" ;;
  sign) cmd_sign "$@" ;;
  sign-chart) cmd_sign_chart "$@" ;;
  *) die "usage: sign-images.sh install <dir> cosign [syft] | check-key | sign <outdir> <image:tag>... | sign-chart <chart:version>..." ;;
esac
