#!/usr/bin/env bash
# Builds the image of a run's pod (ADR-0115, decision 2) for the runner host's `pod` driver:
# the release's static runner host binary, Firecracker, the guest kernel and the sandbox VM
# image, in an image with no shell and no tool (pod.Dockerfile, beside this file). Part of
# elastic runners, with the driver, under the Tinfirm Enterprise licence (ee/LICENSE).
# Tinfirm publishes no such image: you build it, push it to a registry your workers pull from,
# and name it by digest in the chart (runner.vm.pod.image). This script pushes nothing.
#
# Run on a Linux x86-64 machine with Docker, from anywhere:
#
#     ee/runner/images/vm/pod-image.sh /path/to/tinfirm-runner-host ./out \
#         registry.example.com/tinfirm/vm-pod:1.0.0
#
# - The first argument is the release's static (musl) binary of the runner host, checked
#   against the release's signed SHA256SUMS. It must be the build that the runner pod runs.
# - The second is the directory where runner/images/vm/local-disk.sh wrote tinfirm-vm.img and
#   tinfirm-vm.json, with that same binary. This script builds no VM image of its own.
# - The third is the image's name and tag.
#
# What it does, each step checked:
#   1. the binary has the run pod's program (`vm-pod`), and is the one the VM image holds;
#   2. the VM image is the one local-disk.sh recorded (its SHA-256), and grows its root file
#      system at boot (tinfirm-vm-grow-root.service);
#   3. Firecracker FIRECRACKER_VERSION, its archive checked against the SHA-256 of the release's
#      own .sha256.txt and its binary against the SHA256SUMS in the archive;
#   4. the guest kernel the sandbox nodes run, from Kata Containers' static release, the
#      archive and the kernel each checked against their SHA-256;
#   5. a copy of the VM image, its free blocks zeroed and its file system shrunk to what it
#      uses plus POD_IMAGE_FREE_MIB (default 512). An image layer keeps no holes: the worker
#      would store a sparse 32 GiB image as 32 GiB. The guest grows the file system back to
#      the template's disk at boot;
#   6. `docker build`, then a run of the image as user 10001 with a read-only root file system.
#
# The pins are the ones the sandbox node's Ansible role uses
# (deploy/ansible/roles/tinfirm_sandbox_node/vars/main.yml). FIRECRACKER_DOWNLOAD_URL and
# KERNEL_DOWNLOAD_URL may name mirrors of GitHub's release downloads; the checksums stay.
# The machine needs about three times the VM image's used size free: the copy, Docker's
# build context and the image.
set -euo pipefail

BINARY="${1:?usage: pod-image.sh RUNNER_HOST_BINARY VM_IMAGE_DIRECTORY IMAGE_NAME:TAG}"
VM_DIR="${2:?usage: pod-image.sh RUNNER_HOST_BINARY VM_IMAGE_DIRECTORY IMAGE_NAME:TAG}"
TAG="${3:?usage: pod-image.sh RUNNER_HOST_BINARY VM_IMAGE_DIRECTORY IMAGE_NAME:TAG}"
FREE_MIB="${POD_IMAGE_FREE_MIB:-512}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"

# Firecracker 1.14.2, x86_64: the archive's SHA-256 from the release's
# firecracker-v1.14.2-x86_64.tgz.sha256.txt, the binary's from the SHA256SUMS inside it.
FIRECRACKER_VERSION=1.14.2
FIRECRACKER_ARCHIVE_SHA256=c9f112a983783f3cf50feea9e69b8ea9eb7475e52159a9585ca9555be630f5a3
FIRECRACKER_SHA256=36bccbe85a347307edfce2148f23191e8be55d08d287d9d3a6dccf037bed087e
FIRECRACKER_DOWNLOAD_URL="${FIRECRACKER_DOWNLOAD_URL:-https://github.com/firecracker-microvm/firecracker/releases/download}"
# The guest kernel: Kata Containers 4.3.1's vmlinux-6.18.56-208, as on the sandbox nodes
# (runner/crates/tinfirm-node-agent/README.md). Kata publishes the archive's checksum; the
# kernel's is the one recorded there.
KATA_VERSION=4.3.1
KATA_ARCHIVE_SHA256=1463a2b86845f4d3d8e87f2f764ec59856fc0fee3520967a79ade32762be2e97
KERNEL_MEMBER=opt/kata/share/kata-containers/vmlinux-6.18.56-208
KERNEL_SHA256=f87d8359035cb7557b16a319e6431ea09b183e62268aef436bf8dc6530d786b6
KERNEL_DOWNLOAD_URL="${KERNEL_DOWNLOAD_URL:-https://github.com/kata-containers/kata-containers/releases/download}"

note() { printf '>>> %s\n' "$*" >&2; }
die() { printf 'pod-image: %s\n' "$*" >&2; exit 1; }
sha256() { sha256sum "$1" | cut -d' ' -f1; }
check_sha256() { # FILE EXPECTED WHAT
    local got
    got="$(sha256 "$1")"
    [ "$got" = "$2" ] || die "$3: SHA-256 $got, expected $2"
}
fetch() { # URL FILE
    curl -fsSL --retry 3 --max-time 900 -o "$2" "$1" || die "cannot download $1"
}

[ "$(uname -m)" = x86_64 ] || die "this recipe is for x86_64: no guest kernel is pinned for another architecture"
for tool in docker curl sha256sum tar zstd cp truncate e2fsck resize2fs dumpe2fs debugfs; do
    command -v "$tool" >/dev/null || die "missing tool: $tool"
done
docker info >/dev/null 2>&1 || die "Docker does not answer (docker info)"
case "$FREE_MIB" in '' | *[!0-9]*) die "POD_IMAGE_FREE_MIB must be a number of MiB" ;; esac
[ -x "$BINARY" ] || die "no runner host binary at $BINARY"
IMAGE="$VM_DIR/tinfirm-vm.img"
RECORD="$VM_DIR/tinfirm-vm.json"
[ -f "$IMAGE" ] && [ -f "$RECORD" ] || die "no tinfirm-vm.img and tinfirm-vm.json in $VM_DIR: build them with runner/images/vm/local-disk.sh"
for licence in LICENSE ee/LICENSE runner/THIRD-PARTY-LICENSES.txt; do
    [ -f "$REPO/$licence" ] || die "no $licence in $REPO: run this script from Tinfirm's source"
done

# ---------------------------------------------------------------- 1. the binary
VERSION="$("$BINARY" --version)" || die "$BINARY does not run here"
"$BINARY" vm-pod --help >/dev/null 2>&1 \
    || die "$BINARY has no \`vm-pod\`: take a release with the pod driver"
note "the runner host: $VERSION"

# ---------------------------------------------------------------- 2. the VM image
field() { sed -n "s/^ *\"$1\": *\"\\(.*\\)\",\$/\\1/p" "$RECORD" | head -n 1; }
RECORDED_SHA256="$(field sha256)"
RECORDED_VERSION="$(field runnerHost)"
[ -n "$RECORDED_SHA256" ] || die "$RECORD names no sha256"
[ "$RECORDED_VERSION" = "$VERSION" ] \
    || die "the VM image holds \"$RECORDED_VERSION\", not this binary (\"$VERSION\"): build both from one release"
note "checking the VM image's SHA-256"
check_sha256 "$IMAGE" "$RECORDED_SHA256" "$IMAGE"
debugfs -R "cat /etc/systemd/system/tinfirm-vm-grow-root.service" "$IMAGE" 2>/dev/null | grep -q resize2fs \
    || die "the VM image does not grow its root file system at boot: build it with this source's local-disk.sh"

WORK="$(mktemp -d "${TMPDIR:-/var/tmp}/tinfirm-vm-pod.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
CTX="$WORK/context"
mkdir -p "$WORK/download" "$CTX/licenses/ee" "$CTX/licenses/firecracker"

# ---------------------------------------------------------------- 3. Firecracker
FC="firecracker-v$FIRECRACKER_VERSION-x86_64"
note "Firecracker $FIRECRACKER_VERSION"
fetch "$FIRECRACKER_DOWNLOAD_URL/v$FIRECRACKER_VERSION/$FC.tgz" "$WORK/download/$FC.tgz"
check_sha256 "$WORK/download/$FC.tgz" "$FIRECRACKER_ARCHIVE_SHA256" "$FC.tgz"
DIR="release-v$FIRECRACKER_VERSION-x86_64"
tar -xzf "$WORK/download/$FC.tgz" -C "$WORK/download" "$DIR/$FC" "$DIR/LICENSE" "$DIR/NOTICE" "$DIR/THIRD-PARTY"
check_sha256 "$WORK/download/$DIR/$FC" "$FIRECRACKER_SHA256" "$FC"
install -m 0555 "$WORK/download/$DIR/$FC" "$CTX/firecracker"
for f in LICENSE NOTICE THIRD-PARTY; do
    install -m 0444 "$WORK/download/$DIR/$f" "$CTX/licenses/firecracker/$f"
done

# ---------------------------------------------------------------- 4. the guest kernel
KATA="kata-static-$KATA_VERSION-amd64.tar.zst"
note "the guest kernel, from Kata Containers $KATA_VERSION (about 1 GB to download)"
fetch "$KERNEL_DOWNLOAD_URL/$KATA_VERSION/$KATA" "$WORK/download/$KATA"
check_sha256 "$WORK/download/$KATA" "$KATA_ARCHIVE_SHA256" "$KATA"
tar --zstd -xf "$WORK/download/$KATA" -C "$WORK/download" "./$KERNEL_MEMBER"
rm -f "$WORK/download/$KATA"
check_sha256 "$WORK/download/$KERNEL_MEMBER" "$KERNEL_SHA256" "$KERNEL_MEMBER"
install -m 0444 "$WORK/download/$KERNEL_MEMBER" "$CTX/vmlinux"

# ---------------------------------------------------------------- 5. the root file system
ROOTFS="$CTX/rootfs.ext4"
note "copying the VM image and shrinking its file system"
cp --sparse=always "$IMAGE" "$ROOTFS"
chmod 0644 "$ROOTFS"
fsck_ok() { # e2fsck's 0 and 1 (errors corrected) are fine
    local rc=0
    e2fsck "$@" "$ROOTFS" >&2 || rc=$?
    [ "$rc" -le 1 ] || die "e2fsck $* found errors it could not correct (exit $rc)"
}
fsck_ok -f -y
resize2fs -M "$ROOTFS" >&2
block() { dumpe2fs -h "$ROOTFS" 2>/dev/null | sed -n "s/^$1: *//p"; }
BLOCK_SIZE="$(block 'Block size')"
MIN_BLOCKS="$(block 'Block count')"
BLOCKS=$((MIN_BLOCKS + FREE_MIB * 1048576 / BLOCK_SIZE))
resize2fs "$ROOTFS" "$BLOCKS" >&2
# The free blocks hold what the build deleted: zero them (holes, here), so that the layer
# compresses well.
fsck_ok -f -y -E discard
truncate -s $((BLOCKS * BLOCK_SIZE)) "$ROOTFS"
fsck_ok -f -n
chmod 0444 "$ROOTFS"
ROOTFS_MIB=$(((BLOCKS * BLOCK_SIZE + 1048575) / 1048576))
ROOTFS_SHA256="$(sha256 "$ROOTFS")"

# ---------------------------------------------------------------- 6. the image
install -m 0555 "$BINARY" "$CTX/tinfirm-runner-host"
install -m 0444 "$REPO/LICENSE" "$CTX/licenses/LICENSE"
install -m 0444 "$REPO/ee/LICENSE" "$CTX/licenses/ee/LICENSE"
install -m 0444 "$REPO/runner/THIRD-PARTY-LICENSES.txt" "$CTX/licenses/THIRD-PARTY-runner.txt"
note "building $TAG"
docker build --platform linux/amd64 -f "$HERE/pod.Dockerfile" -t "$TAG" \
    --build-arg "VERSION=$VERSION" \
    --build-arg "FIRECRACKER_VERSION=$FIRECRACKER_VERSION" \
    --build-arg "KERNEL=kata-$KATA_VERSION/${KERNEL_MEMBER##*/}" \
    --build-arg "ROOTFS_SHA256=$ROOTFS_SHA256" \
    "$CTX" >&2
# As the run pod runs it: user 10001, a read-only root file system, no network.
IN_IMAGE="$(docker run --rm --read-only --user 10001:10001 --network none \
    --entrypoint /tinfirm/tinfirm-runner-host "$TAG" --version)" \
    || die "the image's binary does not run as user 10001 on a read-only root: is it the static release build?"
[ "$IN_IMAGE" = "$VERSION" ] || die "the image's binary says \"$IN_IMAGE\", not \"$VERSION\""

mib() { echo $((($(stat -c %s "$1") + 1048575) / 1048576)); }
cat <<EOF
{
  "image": "$TAG",
  "runnerHost": "$VERSION",
  "firecracker": "$FIRECRACKER_VERSION",
  "kernel": "kata-$KATA_VERSION/${KERNEL_MEMBER##*/}",
  "binaryMib": $(mib "$CTX/tinfirm-runner-host"),
  "firecrackerMib": $(mib "$CTX/firecracker"),
  "kernelMib": $(mib "$CTX/vmlinux"),
  "rootfsMib": $ROOTFS_MIB,
  "rootfsSha256": "$ROOTFS_SHA256",
  "imageBytes": $(docker image inspect --format '{{.Size}}' "$TAG")
}
EOF
note "every template's diskMib must be at least $ROOTFS_MIB"
note "next: docker push $TAG, then set runner.vm.pod.image to the digest the push prints"
