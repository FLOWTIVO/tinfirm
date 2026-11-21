#!/usr/bin/env bash
# Builds the image of the sandbox VMs (ADR-0096) for a local disk, without a provider: one
# ext4 file that a sandbox node's agent hands to every VM as its shared, read-only root disk
# (ADR-0112 decision 4; runner/crates/tinfirm-node-agent/README.md).
#
# It holds what DigitalOcean's snapshot holds, since it runs setup.sh unchanged: Debian with
# Docker Engine, the runner host's binary, the runner images and tinfirm-vm-boot.service.
# setup.sh runs in a privileged build container whose /var/lib/docker and
# /var/lib/containerd are directories of the image, so the runner images land where the VM's
# Docker reads them. What a provider does for a cloud VM, a local disk gets like this:
#
#   - the address: the node agent passes it on the kernel's command line (`ip=...`), and the
#     guest kernel sets it itself before the first process runs. The image has no cloud-init,
#     no DHCP client and no network manager, and needs none;
#   - the user data: `vm-boot` reads it from the VM's second disk, which the kernel's command
#     line names (`tinfirm.user-data=disk:/dev/vdb`). Nothing of this script is involved;
#   - no way in: no SSH server, no getty on the serial console, no root password, no resolver;
#   - a root file system that grows to its disk at boot where the kernel's command line asks
#     for it (tinfirm-vm-grow-root.service, `tinfirm.grow-root`): a run pod's image
#     (ee/runner/images/vm/pod-image.sh) holds this image shrunk to what it uses, and the pod
#     hands the VM a larger disk. A sandbox node's VM does not ask, and nothing grows there.
#
# Run as root on a Linux x86-64 machine with Docker:
#
#     sudo ./local-disk.sh /path/to/tinfirm-runner-host /var/lib/tinfirm-node-agent/images
#
# The first argument is the static (musl) binary of the runner host that goes into the image;
# the control host must run the same build. The second is where `tinfirm-vm.img` and
# `tinfirm-vm.json` are written: root's, read-only for everyone else. IMAGE_SIZE_MIB is the
# image's size (default 32768; the file is sparse), IMAGE_RUNTIMES and IMAGE_BUILD_ARGS are
# setup.sh's. The build needs the internet for deb.debian.org, download.docker.com, Docker Hub
# and what the runner images' Dockerfiles fetch. A node should not keep Docker after the
# build: its forwarding rules drop what a VM's published port needs.
set -euo pipefail

BINARY="${1:?usage: local-disk.sh RUNNER_HOST_BINARY OUTPUT_DIRECTORY}"
OUT="${2:?usage: local-disk.sh RUNNER_HOST_BINARY OUTPUT_DIRECTORY}"
SIZE_MIB="${IMAGE_SIZE_MIB:-32768}"
HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="$OUT/tinfirm-vm.img"
BASE=tinfirm-vm-local-disk-base
BUILD=tinfirm-vm-local-disk-build

note() { printf '>>> %s\n' "$*" >&2; }
die() { printf 'local-disk: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root"
[ "$(uname -m)" = x86_64 ] || die "this builder is for x86_64"
for tool in docker mkfs.ext4 mount umount truncate tar du sha256sum; do
    command -v "$tool" >/dev/null || die "missing tool: $tool"
done
docker info >/dev/null 2>&1 || die "Docker does not answer (docker info)"
[ -x "$BINARY" ] || die "no runner host binary at $BINARY"
[ -f "$HERE/setup.sh" ] && [ -f "$HERE/tinfirm-vm-boot.service" ] && [ -f "$HERE/tinfirm-vm-grow-root.service" ] \
    || die "setup.sh, tinfirm-vm-boot.service and tinfirm-vm-grow-root.service must lie beside this script"
VERSION="$("$BINARY" --version)"
note "the image gets: $VERSION"

WORK="$(mktemp -d /var/tmp/tinfirm-vm-local-disk.XXXXXX)"
MNT="$WORK/mnt"
cleanup() {
    docker rm -f "$BUILD" >/dev/null 2>&1 || :
    if mountpoint -q "$MNT" 2>/dev/null; then umount "$MNT" || umount -l "$MNT" || :; fi
    rm -rf "$WORK"
}
trap cleanup EXIT
docker rm -f "$BUILD" >/dev/null 2>&1 || :
mkdir -p "$WORK/ctx" "$WORK/in" "$MNT" "$OUT"
T0="$(date +%s)"

# ---------------------------------------------------------------- the base system
# What a cloud's Debian image has before setup.sh runs: an init system and the tools setup.sh
# takes for granted. The build container runs no systemd, so nothing starts Docker after its
# packages are installed, and setup.sh needs it for `images build`. The shim stands where
# `systemctl` is: it starts containerd and dockerd with the directories and the socket that
# docker.service uses in the VM.
cat >"$WORK/ctx/systemctl-shim" <<'SHIM'
#!/bin/sh
real=/usr/bin/systemctl
[ -x "$real" ] || real=/bin/systemctl
"$real" "$@"
rc=$?
if [ "${1:-}" = enable ] && [ "${2:-}" = docker.service ] && ! pgrep -x dockerd >/dev/null 2>&1; then
    if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
        mkdir -p /sys/fs/cgroup/init
        xargs -rn1 </sys/fs/cgroup/cgroup.procs >/sys/fs/cgroup/init/cgroup.procs 2>/dev/null || :
        sed -e 's/ / +/g' -e 's/^/+/' </sys/fs/cgroup/cgroup.controllers \
            >/sys/fs/cgroup/cgroup.subtree_control 2>/dev/null || :
    fi
    containerd >/var/log/build-containerd.log 2>&1 &
    n=0
    until [ -S /run/containerd/containerd.sock ] || [ "$n" -ge 150 ]; do sleep 0.2; n=$((n + 1)); done
    dockerd --containerd=/run/containerd/containerd.sock >/var/log/build-dockerd.log 2>&1 &
    n=0
    until docker info >/dev/null 2>&1; do
        if [ "$n" -ge 300 ]; then
            tail -n 30 /var/log/build-dockerd.log >&2
            echo "the build's dockerd did not start" >&2
            exit 1
        fi
        sleep 0.2
        n=$((n + 1))
    done
fi
exit $rc
SHIM

cat >"$WORK/ctx/Dockerfile" <<'DOCKERFILE'
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends systemd systemd-sysv dbus udev kmod procps \
      iproute2 iptables util-linux e2fsprogs ca-certificates \
 && apt-get clean && rm -rf /var/lib/apt/lists/*
COPY systemctl-shim /usr/local/sbin/systemctl
RUN chmod 0755 /usr/local/sbin/systemctl
CMD ["sleep", "infinity"]
DOCKERFILE
note "building the base system"
docker build -q -t "$BASE" "$WORK/ctx" >/dev/null

# ---------------------------------------------------------------- the disk
rm -f "$IMAGE.next"
truncate -s "${SIZE_MIB}M" "$IMAGE.next"
mkfs.ext4 -q -F -L tinfirm-root -m 0 -E lazy_itable_init=1,lazy_journal_init=1 "$IMAGE.next"
mount -o loop "$IMAGE.next" "$MNT"
mkdir -p "$MNT/var/lib/docker" "$MNT/var/lib/containerd"

# ---------------------------------------------------------------- setup.sh, unchanged
install -m 0755 "$BINARY" "$WORK/in/tinfirm-runner-host"
install -m 0644 "$HERE/tinfirm-vm-boot.service" "$WORK/in/tinfirm-vm-boot.service"
install -m 0755 "$HERE/setup.sh" "$WORK/in/setup.sh"
install -m 0644 "$HERE/tinfirm-vm-grow-root.service" "$WORK/in/tinfirm-vm-grow-root.service"

# What a local disk needs beside the recipe; runs in the build container after setup.sh.
cat >"$WORK/in/after-setup.sh" <<'AFTER'
#!/bin/sh
set -eu
# The build's own Docker stops before the image is taken. A process that ended and waits for
# its parent (a zombie) does not count as running.
runs() { ps -C "$1" -o stat= 2>/dev/null | grep -qv '^Z'; }
pkill -TERM -x dockerd 2>/dev/null || :
n=0; while runs dockerd && [ "$n" -lt 300 ]; do sleep 0.2; n=$((n + 1)); done
pkill -TERM -x containerd 2>/dev/null || :
n=0; while runs containerd && [ "$n" -lt 300 ]; do sleep 0.2; n=$((n + 1)); done
if runs dockerd || runs containerd; then
    ps -eo pid,stat,etime,args >&2
    echo "the build's Docker did not stop" >&2
    exit 1
fi
sync

# The guest kernel has set the address before systemd starts, so nothing waits for a network
# manager; no getty, no resolver, no clock service, no package timers: the VM lives for one run.
systemctl mask serial-getty@ttyS0.service getty@tty1.service console-getty.service \
    systemd-networkd.service systemd-networkd-wait-online.service systemd-resolved.service \
    systemd-timesyncd.service apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || :
passwd -l root >/dev/null
# The root file system grows to its disk at boot where the kernel's command line asks for it
# (a run pod's disk is larger than the image it holds).
install -m 0644 /in/tinfirm-vm-grow-root.service /etc/systemd/system/tinfirm-vm-grow-root.service
systemctl enable tinfirm-vm-grow-root.service
rm -f /usr/local/sbin/systemctl /usr/sbin/policy-rc.d
rm -f /var/log/build-dockerd.log /var/log/build-containerd.log
AFTER
chmod 0755 "$WORK/in/after-setup.sh"

note "running setup.sh in the build container (the runner images are built now: this takes long)"
docker run -d --name "$BUILD" --init --privileged --cgroupns private \
    -v "$MNT/var/lib/docker:/var/lib/docker" -v "$MNT/var/lib/containerd:/var/lib/containerd" \
    -v "$WORK/in:/in:ro" "$BASE" >/dev/null
docker exec "$BUILD" sh -c 'cp /in/tinfirm-runner-host /tmp/tinfirm-runner-host && cp /in/tinfirm-vm-boot.service /tmp/tinfirm-vm-boot.service'
docker exec -e "IMAGE_RUNTIMES=${IMAGE_RUNTIMES:-}" -e "IMAGE_BUILD_ARGS=${IMAGE_BUILD_ARGS:-}" "$BUILD" /in/setup.sh
RUNNER_IMAGES="$(docker exec "$BUILD" docker image ls --format '{{.Repository}}:{{.Tag}}' | grep '^tinfirm-runner-' | sort | tr '\n' ' ')"
DOCKER_VERSION="$(docker exec "$BUILD" docker version --format '{{.Server.Version}}')"
docker exec "$BUILD" /in/after-setup.sh

# ---------------------------------------------------------------- the image
note "writing the system into the image"
docker export "$BUILD" | tar -x -C "$MNT" --exclude='./in' --exclude='in' \
    --exclude='./tmp/*' --exclude='tmp/*' --exclude='./run/*' --exclude='run/*'
docker rm -f "$BUILD" >/dev/null
rm -f "$MNT/.dockerenv"
rmdir "$MNT/in" 2>/dev/null || :
echo tinfirm-sandbox >"$MNT/etc/hostname"
: >"$MNT/etc/machine-id"
printf '127.0.0.1 localhost tinfirm-sandbox\n' >"$MNT/etc/hosts"
rm -f "$MNT/etc/resolv.conf"
: >"$MNT/etc/resolv.conf"
[ -x "$MNT/opt/tinfirm/tinfirm-runner-host" ] || die "the image has no /opt/tinfirm/tinfirm-runner-host: setup.sh did not finish"
[ -L "$MNT/etc/systemd/system/multi-user.target.wants/tinfirm-vm-boot.service" ] || die "tinfirm-vm-boot.service is not enabled in the image"
[ -L "$MNT/etc/systemd/system/local-fs.target.wants/tinfirm-vm-grow-root.service" ] || die "tinfirm-vm-grow-root.service is not enabled in the image"
[ ! -e "$MNT/etc/systemd/system/multi-user.target.wants/ssh.service" ] || die "the image has an SSH server enabled"
USED_MIB="$(du -sm "$MNT" | cut -f1)"
umount "$MNT"

# The node agent's helper asks for a regular file of root's that only root can write.
chown root:root "$IMAGE.next"
chmod 0444 "$IMAGE.next"
mv -f "$IMAGE.next" "$IMAGE"
cat >"$OUT/tinfirm-vm.json" <<JSON
{
  "image": "$IMAGE",
  "sha256": "$(sha256sum "$IMAGE" | cut -d' ' -f1)",
  "built": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "runnerHost": "$VERSION",
  "docker": "$DOCKER_VERSION",
  "runnerImages": "$RUNNER_IMAGES",
  "sizeMib": $SIZE_MIB,
  "usedMib": $USED_MIB,
  "buildSeconds": $(( $(date +%s) - T0 ))
}
JSON
chmod 0444 "$OUT/tinfirm-vm.json"
cat "$OUT/tinfirm-vm.json"
note "the image: $IMAGE"
