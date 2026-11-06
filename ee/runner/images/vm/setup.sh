#!/bin/sh
# Provisions the image of the sandbox VMs (ADR-0096), as digitalocean.pkr.hcl runs it on a
# build VM: Debian with Docker Engine, the runner host's binary, the runner images built from
# its own Dockerfiles (ADR-0062), and the unit that starts the run's container at boot. It
# leaves no way in but the bridge, and nothing that holds a credential: no SSH key or server,
# no provider agent.
#
# Takes /tmp/tinfirm-runner-host (a static Linux release binary) and
# /tmp/tinfirm-vm-boot.service; IMAGE_BUILD_ARGS may hold `--build-arg KEY=VALUE` options for
# `images build` (mirrors; never a credential).
set -eu
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg

# Docker Engine from Docker's own repository.
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
# shellcheck source=/dev/null
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" \
    >/etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin
systemctl enable docker.service

# The runner host's binary: `vm-boot` at boot, and the bridge in the run's container.
install -m 0755 -D /tmp/tinfirm-runner-host /opt/tinfirm/tinfirm-runner-host
/opt/tinfirm/tinfirm-runner-host --version

# The runner images, built here and never pulled at run time: the VM reaches no registry.
# Each runtime's own image, and the multi-runtime image for `run --pool-runtime any` (ADR-0101).
# shellcheck disable=SC2086
for runtime in all multi; do
    TINFIRM_RUNNER_DATA_DIR=/var/lib/tinfirm TINFIRM_RUNNER_CONFIG_DIR=/etc/tinfirm \
        /opt/tinfirm/tinfirm-runner-host images build --runtime "${runtime}" ${IMAGE_BUILD_ARGS:-}
done
docker builder prune --all --force

install -m 0644 /tmp/tinfirm-vm-boot.service /etc/systemd/system/tinfirm-vm-boot.service
systemctl enable tinfirm-vm-boot.service

# No way in but the bridge: no SSH server, no key, no provider agent (DigitalOcean's web
# console and monitoring agents). Disabled, not stopped: the build still runs over SSH.
apt-get purge -y droplet-agent do-agent 2>/dev/null || :
systemctl disable ssh.service ssh.socket 2>/dev/null || :
systemctl mask ssh.service ssh.socket 2>/dev/null || :
rm -f /root/.ssh/authorized_keys /etc/ssh/ssh_host_*

# cloud-init stays for the network setup; the user data is JSON, which it neither runs nor
# keeps a use for.
cloud-init clean --logs 2>/dev/null || :
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/tinfirm-runner-host /tmp/tinfirm-vm-boot.service
