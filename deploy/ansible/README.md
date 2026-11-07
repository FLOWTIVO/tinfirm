# Ansible: Tinfirm runner hosts with Kata Containers

The playbook `runner-host.yml` and the role `tinfirm_runner_host` turn a Linux VM with nested
virtualisation into a Tinfirm runner host whose sandboxes are Kata Containers microVMs, with
the agent's own Docker inside ([ADR-0025](../../docs/adr/0025-kata-sandbox-runtime.md)). Each
VM runs several sandboxes; more VMs add capacity.

The role:

1. checks the target: Ubuntu, Debian or the RHEL family on x86_64 or aarch64, and `/dev/kvm`
   (nested virtualisation). Without KVM it stops: Kata needs it;
2. installs Docker Engine from Docker's repository (or a mirror of it), with its signing key
   checked by fingerprint (`tinfirm_runner_host_docker_key_fingerprint`);
3. installs the Kata Containers static release (pinned, checked against its SHA-256, Go
   runtime, Cloud Hypervisor), writes
   `/etc/kata-containers/configuration.toml` from Cloud Hypervisor's configuration, runs
   `kata-runtime check`, and registers the runtime `kata` in `/etc/docker/daemon.json`;
4. creates the runner host's user (uid 1990, not the sandbox's 1000), installs the
   `tinfirm-runner-host` binary (a release archive only against its line of the release's
   `SHA256SUMS`, trusted once `SHA256SUMS.sig` verifies against the public key the role ships),
   and logs in to the server with the host token;
5. builds the runner images with `images build` and the build arguments of your mirrors;
6. installs the systemd unit `tinfirm-runner-host` with
   `run --sandbox-runtime kata --sandbox-docker --egress-proxy --sandbox-bridge` and the
   `--sandbox-env` mirror settings, sandboxed by systemd (`NoNewPrivileges`,
   `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, writable only its own directories),
   and starts it.

A company runner host receives the git token and the prompt of every conversation it runs:
treat it as a tier-0 asset beside the server (threat model 5.13). The role's defaults are
the hardened ones; each can be turned off, and the [security review of
2026-10-27](../../docs/reviews/2026-10-27-security-review.md) (L27, D7) says why they are
on.

It adds no credentials, and a company runner host needs none. Register the host on the
company's **Runner hosts** page (**Register a runner host**), which makes it a company host:
it runs any conversation paid by a budget and holds no credentials of its own. Each run gets
its model access from the server: the address of the server's model relay and a one-run
token, which the host's model proxy keeps out of the sandbox, while the provider keys stay in
the company's LiteLLM ([docs/operations/litellm.md](../../docs/operations/litellm.md)). A
member's own subscriptions belong on a personal runner host, not on a host this role
installs.

## Requirements

| Where | What |
|---|---|
| Control node | ansible-core 2.15 or newer (the role uses only `ansible.builtin`) |
| Target VM | Ubuntu 22.04 or 24.04, Debian 12 or 13, or RHEL, Rocky or AlmaLinux 9 or 10; systemd; sudo for the Ansible user |
| Target VM | Nested virtualisation: `/dev/kvm` exists in the VM. Enable it in the hypervisor (VMware: "Expose hardware assisted virtualization to the guest OS"; Proxmox and KVM: CPU type `host`; Hyper-V: `Set-VMProcessor -ExposeVirtualizationExtensions $true`) |
| Network | The Tinfirm server's URL, Docker's repository, the Kata release, and the mirrors below, or their mirrors |

Docker Engine 23 or newer is required for a shim runtime by path; the role installs the
current one.

## Use

An inventory, with the secrets in Ansible Vault:

```yaml
tinfirm_runner_hosts:
  hosts:
    runner-1.internal:
  vars:
    tinfirm_runner_host_server_url: https://tinfirm.internal
    tinfirm_runner_host_token: "{{ vault_tinfirm_runner_token }}"   # Runner hosts → Register a runner host
    # Any runner host release (below); <version> as in the release's asset names.
    tinfirm_runner_host_binary_url: https://nexus.internal/repository/tinfirm/tinfirm-runner-host-<version>-x86_64-unknown-linux-musl.tar.gz
    # SHA256SUMS and SHA256SUMS.sig beside it: the role checks the signature, then the archive.
    tinfirm_runner_host_kata_download_url: https://nexus.internal/repository/kata-releases
    tinfirm_runner_host_kata_checksum: "sha256:…"                   # only for a version the role knows no checksum of
    tinfirm_runner_host_max_concurrent_runs: 4
    # This VM's dockerd pulls the images' base image (Docker Hub) through this mirror.
    tinfirm_runner_host_docker_registry_mirrors: [https://nexus.internal:8443]
    # The mirrors inside every sandbox (`run --sandbox-env`).
    tinfirm_runner_host_sandbox_registry_mirrors: [https://nexus.internal:8443]
    tinfirm_runner_host_sandbox_maven_mirror: https://nexus.internal/repository/maven-public/
    tinfirm_runner_host_sandbox_npm_registry: https://nexus.internal/repository/npm/
    tinfirm_runner_host_sandbox_pip_index: https://nexus.internal/repository/pypi/simple
    # The image build on a VM without internet (runner/README.md, "Image build arguments").
    tinfirm_runner_host_images_build_args:
      APT_MIRROR: http://nexus.internal/repository
      DOCKER_APT_URL: https://nexus.internal/repository/docker-debian
      NPM_REGISTRY: https://nexus.internal/repository/npm/
      PIP_INDEX_URL: https://nexus.internal/repository/pypi/simple
      MAVEN_REPOSITORY_URL: https://nexus.internal/repository/maven-central
      GRADLE_DOWNLOAD_URL: https://nexus.internal/repository/gradle-distributions
```

```sh
ansible-playbook -i inventory.yml deploy/ansible/runner-host.yml --ask-vault-pass
```

[`defaults/main.yml`](roles/tinfirm_runner_host/defaults/main.yml) documents every variable.
The main ones:

| Variable | Default | Meaning |
|---|---|---|
| `tinfirm_runner_host_binary_src` / `_binary_url` | — | The runner host binary: a file on the control node, or a release archive to download. Set exactly one. Any release works with a server that speaks its protocol ([runner/README.md](../../runner/README.md#compatibility-with-the-server)). No version is released yet: take the archive of the `rolling` prerelease, whose `<version>` is `rolling-<short sha>` ([docs/operations/auto-deploy.md](../../docs/operations/auto-deploy.md#1-what-ci-publishes)) |
| `tinfirm_runner_host_server_url`, `tinfirm_runner_host_token` | — | Login, done once; `tinfirm_runner_host_relogin: true` repeats it. Use the server's public URL (`TINFIRM_PUBLIC_URL`): the model proxy sends the host's token only to that origin, and the relay refuses the model requests of a host without it |
| `tinfirm_runner_host_kata_version` | `4.3.1` | Kata Containers static release. The role knows the checksum of 4.3.1; another version needs `tinfirm_runner_host_kata_checksum` |
| `tinfirm_runner_host_kata_runtime_name` | `kata` | Docker runtime name; must start with `kata` |
| `tinfirm_runner_host_sandbox_docker` | `true` | `run --sandbox-docker` |
| `tinfirm_runner_host_egress_proxy` | `true` | `run --egress-proxy`: the sandboxes reach only the model, the mirrors of the sandbox settings and `tinfirm_runner_host_egress_allow` (ADR-0044). `false` is open egress (R24) |
| `tinfirm_runner_host_egress_allow` | `[]` | `run --egress-allow`, one per entry |
| `tinfirm_runner_host_sandbox_bridge` | `true` | `run --sandbox-bridge` (ADR-0095): the host's listeners on loopback, the run's credentials out of the container's settings. Needs a static build of the runner host (the release archives are), else `tinfirm_runner_host_bridge_binary`. `false` puts the listeners on Docker's bridge (R28) |
| `tinfirm_runner_host_public_key` | `cosign.pub` (the role's `files/`) | The key the release's `SHA256SUMS.sig` must verify against before any checksum in `SHA256SUMS` is trusted (with `openssl`, as auto-deploy does): FLOWTIVO's release key, a copy of `deploy/compose/auto-deploy/cosign.pub`. For `rolling`, the rolling key `deploy/compose/auto-deploy/cosign-rolling.pub` |
| `tinfirm_runner_host_release_url` | the archive's directory | Where the release's `SHA256SUMS` and `SHA256SUMS.sig` are |
| `tinfirm_runner_host_allow_unsigned` | `false` | `true` installs a release whose `SHA256SUMS.sig` is missing (404) against `tinfirm_runner_host_binary_checksum`, with a warning; a signature that does not verify is refused even then |
| `tinfirm_runner_host_binary_checksum`, `tinfirm_runner_host_kata_checksum` | — | `sha256:<hex>`. The archive's is taken from the signed `SHA256SUMS`; set, it must be that line, and it is required only for an unsigned release. Kata's defaults to the known checksum of the pinned version, and the role stops when there is none |
| `tinfirm_runner_host_docker_key_fingerprint` | Docker's release key | The fingerprint the downloaded repository key must have |
| `tinfirm_runner_host_sandbox_*` | empty | `TINFIRM_SANDBOX_*` mirror settings for every sandbox; never credentials |
| `tinfirm_runner_host_images_build_args` | `{}` | `images build --build-arg`; never credentials |
| `tinfirm_runner_host_user`, `_uid` | `tinfirm`, `1990` | The runner host's user (see below) |

## Things to know

- **No credentials in mirror settings or build arguments.** `--sandbox-env` values reach every
  sandbox unredacted, and build arguments stay in the image's history. The runner host
  refuses credential-like names and URLs with a user name or password. Mirrors that need
  authentication need a credential path that does not exist yet.
- **Firewall for `--egress-proxy`.** With the egress proxy ([ADR-0044](../../docs/adr/0044-sandbox-egress-proxy.md))
  a sandbox cannot leave the machine, but it can still connect to any service listening on the
  sandbox network's gateway address, which is an address of the VM: SSH on port 22 answers from
  a sandbox there. Bind other services to specific interfaces, or drop input from the
  sandbox bridge (`tinfirm-sandbox-egress`) in the host firewall except for the runner host's own
  listeners; the egress listeners' ports are chosen by the system, so a rule needs a fixed range
  first (a possible follow-up). The role does not set such a rule.
- **uid 1990, not 1000.** The sandbox runs the agent as uid 1000. With the sandbox bridge the
  workspaces and homes travel over the bridge, so the runner host's user needs no uid of the
  sandbox's, and the role creates `tinfirm` with uid 1990: a file the agent leaves is never
  the runner host's. Without the bridge (`tinfirm_runner_host_sandbox_bridge: false`) the
  workspaces are bind mounts written as uid 1000, and the role requires
  `tinfirm_runner_host_uid: 1000`. Where the user `tinfirm` already has uid 1000, drain the
  host, then run the role, which changes the uid (and the owner of the files in its home); or
  keep 1000 by setting it.
- **systemd sandboxing.** The unit runs the host with `NoNewPrivileges`,
  `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp` and the kernel protections, writable
  only in its home, config and data directories, which must therefore not be under `/home`,
  `/root` or `/run/user` (the role checks).
- **Docker socket access is root-equivalent** on the VM. The runner host's user is in the
  `docker` group.
- **The role owns `/etc/docker/daemon.json`.** Extra keys go in
  `tinfirm_runner_host_docker_daemon_extra`. A change restarts Docker, which stops every
  running sandbox: drain first (`sudo systemctl reload tinfirm-runner-host` and wait until
  `systemctl status` shows it running again with no active runs).
- **Upgrades** drain: a new binary or a changed unit triggers `systemctl reload`, the active
  runs finish, and systemd starts the new binary (runner/README.md, "Drain and upgrades").
  The role builds the images before the reload; `images build` records the build arguments,
  and `run` rebuilds at its start any image that is still not its binary's
  ([ADR-0062](../../docs/adr/0062-runner-host-rebuilds-its-images.md)).
  A new binary also rebuilds the images (the Dockerfiles are embedded in it).
- **No `privileged_without_host_devices`.** That option belongs to containerd's CRI plugin
  and CRI-O; Docker has none, and a privileged Kata container under Docker would get every
  host block device hot-plugged into its VM. The runner host therefore never starts a
  privileged sandbox (ADR-0025), and the role sets nothing for it in the Kata configuration.
- **The Kata configuration** is `/etc/kata-containers/configuration.toml`, copied from Cloud
  Hypervisor's configuration when Kata is installed or upgraded. Change it with
  `tinfirm_runner_host_kata_config_lines` (for example `default_memory`), not by hand.
- **Firewalls.** The sandboxes reach the runner host's git proxy and platform tools on the
  gateway of Docker's bridge (runner/README.md, "Git in the sandbox"); a run paid by a budget
  also reaches its model proxy, which is always on, on a port of the same address that the
  system picks (runner/README.md, "Model proxy"). With ufw or firewalld, allow traffic from
  `docker0` to the bridge's gateway address on every port: a rule for the git proxy's port
  alone (fixed with `TINFIRM_RUNNER_GIT_PROXY_BIND` in the unit) leaves the model proxy's
  ports closed, and runs paid by a budget fail. Bind this machine's other services to other
  interfaces, so that the sandboxes do not reach them (ADR-0044).
- **Smoke test.** With `tinfirm_runner_host_kata_smoke_test_image` set (an image this VM can
  pull, such as a mirrored `alpine`), the role runs `uname -r` in it under Kata and checks
  that the kernel differs from the VM's.

## Checks after the first install

Docker with Kata is not a combination the Kata project tests (ADR-0025), so check it on
each new kind of VM, in this order:

```sh
docker run --rm --runtime kata alpine uname -r                       # a guest kernel
docker run --rm --runtime kata --cap-add ALL --security-opt seccomp=unconfined \
  --security-opt apparmor=unconfined --security-opt systempaths=unconfined \
  --cgroupns private --device-cgroup-rule 'b 7:* rwm' --device-cgroup-rule 'c 10:237 rwm' \
  -u 0:0 -e TINFIRM_SANDBOX_DOCKER=1 -e TINFIRM_SANDBOX_AGENT_USER=1000:1000 \
  -e TINFIRM_SANDBOX_REGISTRY_MIRRORS=https://nexus.internal:8443 \
  tinfirm-runner-claude:local docker run --rm hello-world               # Docker in the VM
sudo -u tinfirm env TINFIRM_RUNNER_CONFIG_DIR=/var/lib/tinfirm/config \
  TINFIRM_RUNNER_DATA_DIR=/var/lib/tinfirm/data tinfirm-runner-host doctor
journalctl -u tinfirm-runner-host | grep 'runner host starting'     # runtime kata, microvm
```

The second command creates the container the way the runner host does with
`--sandbox-docker`: the entrypoint starts dockerd and runs `docker run hello-world` as the
agent. Then run a real conversation with Testcontainers, check that the agent reaches the git proxy
and the platform tools, and compare the build time with a runc sandbox (virtio-fs costs time
on large workspaces).
