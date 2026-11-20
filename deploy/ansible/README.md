# Ansible: Tinfirm runner hosts, control hosts and sandbox nodes

Three playbooks:

| Playbook | Role | What it makes |
|---|---|---|
| `runner-host.yml` | `tinfirm_runner_host` | A runner host whose sandboxes are Kata Containers microVMs of its own Docker (this page, up to [Sandbox nodes and their control host](#sandbox-nodes-and-their-control-host)) |
| `control-host.yml` | `tinfirm_runner_host`, with `tinfirm_runner_host_executor: vm` | A control host: the runner host with `run --executor vm --vm-driver node`, which holds the credentials and runs no agent code ([ADR-0112](../../docs/adr/0112-no-credential-on-a-sandbox-machine.md)) |
| `sandbox-node.yml` | `tinfirm_sandbox_node` | A sandbox node: the node agent, Firecracker, the guest kernel, the VM image and the node's own firewall. It holds no credential |

The last two are not ready for production. The roles ran on machines once (2026-11-20, Ubuntu
24.04), with ten conversations with real runtimes through the node; a node's hardening has not
run on a machine yet, and the published VM image is not built
([below](#sandbox-nodes-and-their-control-host)).

## Runner hosts with Kata Containers

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

### Requirements

| Where | What |
|---|---|
| Control node | ansible-core 2.15 or newer (the role uses only `ansible.builtin`) |
| Target VM | Ubuntu 22.04 or 24.04, Debian 12 or 13, or RHEL, Rocky or AlmaLinux 9 or 10; systemd; sudo for the Ansible user |
| Target VM | Nested virtualisation: `/dev/kvm` exists in the VM. Enable it in the hypervisor (VMware: "Expose hardware assisted virtualization to the guest OS"; Proxmox and KVM: CPU type `host`; Hyper-V: `Set-VMProcessor -ExposeVirtualizationExtensions $true`) |
| Network | The Tinfirm server's URL, Docker's repository, the Kata release, and the mirrors below, or their mirrors |

Docker Engine 23 or newer is required for a shim runtime by path; the role installs the
current one.

### Use

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
| `tinfirm_runner_host_binary_src` / `_binary_url` | — | The runner host binary: a file on the control node, or a release archive to download. Set exactly one. Any release works with a server that speaks its protocol ([runner/README.md](../../runner/README.md#compatibility-with-the-server)). Take the archive of a release, for v1.0.0 `https://github.com/FLOWTIVO/tinfirm/releases/download/v1.0.0/tinfirm-runner-host-1.0.0-x86_64-unknown-linux-musl.tar.gz` (or `aarch64-unknown-linux-musl`), whose signed `SHA256SUMS` the role checks with FLOWTIVO's release key. For development, the `rolling` prerelease's archive, whose `<version>` is `rolling-<short sha>`, with `tinfirm_runner_host_public_key` set to the rolling key ([docs/operations/auto-deploy.md](../../docs/operations/auto-deploy.md#1-what-ci-publishes)) |
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

### Things to know

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

### Checks after the first install

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

## Sandbox nodes and their control host

> **Not ready for production.** The two plays ran on machines once, on 2026-11-20, on one
> distribution: [Test on two machines](#test-on-two-machines) says what ran and what it found. A
> node's hardening has not run on a machine yet, and the published VM image is not built. Use them
> to prepare and to test.

[ADR-0112](../../docs/adr/0112-no-credential-on-a-sandbox-machine.md) splits a runner host with
VMs in two. The **control host** keeps every credential (the host's token, the runs' git and
model tokens, the node driver's client key) and runs no agent code. The **sandbox nodes** run
the VMs, each Firecracker VM under its own jailed VMM, through the node agent
(`tinfirm-node-agent`), and hold no credential. The control host dials each node's agent over TLS,
with pinned certificates both ways, and then each VM's bridge on a port the node publishes.
The node agent's own specification is
[runner/crates/tinfirm-node-agent/README.md](../../runner/crates/tinfirm-node-agent/README.md);
the control host's side is [runner/README.md](../../runner/README.md#sandbox-nodes).

### Who administers a node

A node is a **tier-1 asset**: a lower tier than the control host (tier 0), with the same
administrators (the owner, 2026-11-20). It holds no credential, so whoever takes it over reaches
the runs on it, their files, prompts and tunnels, and what they hand back, but no account.

- Only the people who administer the control host administer its nodes. The role cannot enforce
  that: give SSH keys and sudo on a node to those people alone.
- The node's firewall admits SSH from one management address alone
  (`tinfirm_sandbox_node_management_address`), to one port (`tinfirm_sandbox_node_ssh_port`), and
  the role stops when its own SSH connection comes from another address or to another port.
- Re-image the nodes on a schedule. The role installs everything from its variables and keeps
  nothing on the node that a new image would lose, but the node's TLS identity, which it makes
  again on a new image ([Replace a node](#replace-a-node)).

### What `control-host.yml` does

The role `tinfirm_runner_host` with `tinfirm_runner_host_executor: vm`, which the play sets:

1. checks the target and the nodes' variables. A control host needs no KVM. It refuses a
   machine with Docker, containerd or Kata (installed, not only running), or whose runner host
   user is in the `docker` group, before it changes anything ([A machine with Docker](#a-machine-with-docker));
2. installs `git`, `openssl`, `acl`, `ca-certificates` and `tar`. **No Docker, no Kata and no
   runner image**: with `--executor vm` the host uses no Docker, and the VM image holds the runner
   images (runner/README.md, "A VM per run": "No images on this host", "`--sandbox-runtime` is
   refused");
3. creates the user `tinfirm` (uid 1990, in no group of Docker's), installs `tinfirm-runner-host`
   from the release (checked against the signed `SHA256SUMS`, as above), and logs in;
4. makes the client certificate on the host with `tinfirm-runner-host node identity`:
   `/etc/tinfirm/node-driver/client.crt` (root's, 0644) and `client.key` (the user's, 0600, which
   the driver requires), and shows its fingerprint, which each node pins;
5. writes `/etc/tinfirm/node-driver.json` with the nodes that have a `nodeSha256` now, and names
   the others as waiting. A change of the file reloads the host, which drains. With no node
   pinned at all, it stops a host that runs, removes the file, and starts none: the host is
   installed and logged in, and does not run ([Take a node out](#take-a-node-out));
6. asks every node of that file through the driver, as the runner host's user: `tinfirm-runner-host vm-driver
   node` with a `verify` of `tinfirm_runner_host_vm_template`, which is what `run` asks at its
   start. A node that names a problem fails the play;
7. checks, as the runner host's user, that it is in no `docker` group and that no Docker or
   containerd socket answers it, the rootless one included, which the first checks do not see.
   Either fails the play before the host is started, and stops a host that already runs;
8. installs the unit with `run --executor vm --vm-driver node --vm-template … --vm-port …`, the
   `--vm-probe`, `--egress-allow` and `--sandbox-env` settings and `--sandbox-docker`, sandboxed as
   above, and starts it. The bridge and the egress proxy are always on with `--executor vm`.

The control host still runs the reader of what a sandbox sends back in a confined process, which
needs Linux 5.19 or later with Landlock enabled at boot (runner/README.md, "The reader"); `run`
refuses to start without it.

### What `sandbox-node.yml` does

The role `tinfirm_sandbox_node`:

1. checks the target: Ubuntu, Debian or the RHEL family with systemd, `/dev/kvm`, the addresses
   and ports, the templates, that the play's SSH connection comes from the management address
   to the SSH port,
   and that neither Docker, firewalld, `nftables.service` nor ufw runs (each would drop or flush
   what the VMs need);
2. installs the tools the helper runs (`ip`, `nft`, `losetup`, `dmsetup` and the rest of "What a
   node needs"), only while one is missing, and masks the distribution's `nftables.service`, which
   flushes the whole ruleset with the VMs' tables;
3. creates the listener's user `tinfirm-node-listener` and the agent's directories
   (`/etc/tmpfiles.d/tinfirm-node-agent.conf`);
4. installs `tinfirm-node-agent` from the release archive
   `tinfirm-node-agent-<version>-<target>.tar.gz`, checked against the release's signed
   `SHA256SUMS`, as the runner host's;
5. installs Firecracker and its jailer 1.14.2 from their release, checked against the pinned SHA-256
   of the archive and of each binary;
6. installs the guest kernel, Kata Containers 4.3.1's `vmlinux-6.18.56-208`, taken out of Kata's
   static release (about 1 GB, removed afterwards) and checked against the archive's and the
   kernel's SHA-256 that the node agent's README records;
7. installs the VM image from a file or a URL that you give with its SHA-256 (below);
8. installs the agent's units (copies of `runner/crates/tinfirm-node-agent/units/`, which CI
   compares), the listener's drop-in with `IPAddressAllow=` the control host, the sysctl file
   (`net.ipv4.ip_forward = 1`, `user.max_user_namespaces = 0`), and the node's firewall table with
   this node's values in its `define` lines, checked with `nft -c` before it is written;
9. hardens the node by its switches ([The hardening](#the-hardening)): the hardening's sysctl
   file, AppArmor and the VMMs' profile, `hardening.json` for the unit that sets kexec, lockdown
   and module loading at each boot, and the setuid and setgid bits;
10. makes the node's TLS identity on the node with `tinfirm-node-agent identity`: the key is the
    listener's (0600), the certificate root's (0644). It never leaves the node;
11. writes `helper.json` (root's, 0644, which the helper requires) and `listener.json`
    (`root:tinfirm-node-listener`, 0640) from the variables. `helper.json` says
    `"nodeFirewall": "required"`: the helper makes no VM while the node's firewall table is
    missing. With the AppArmor switch on it says `"vmmProfile": "required"`, and its `hardening`
    names the agent's port, the SSH port and the most days since the boot;
12. starts the modules' unit, the firewall's, the hardening's, the helper and the listener, in
    that order;
13. proves the node: every unit active, both sysctl settings read back, the firewall table
    loaded, and the helper's own `check` of every template, asked on its socket as the listener's
    user, exactly as the listener asks it. A problem fails the play. Then it shows the node's
    fingerprint and the node's entry for the control host's inventory, prints each line of the
    hardening mark, and fails when a line whose switch is on does not hold.

The image, the kernel, Firecracker and the agent are downloaded only while what is installed is
not what the variables name. A second run downloads nothing: once the firewall is loaded, the
node opens no connection to anything, a package mirror included.

### Requirements

| Where | What |
|---|---|
| Control node | ansible-core 2.15 or newer (`ansible.builtin` only). SSH to each node from its management address |
| Control host | Ubuntu, Debian or the RHEL family, systemd, Linux 5.19 or later with Landlock. It reaches the server, the code hosts and the model gateway, as a runner host does, and each node's agent port and published ports. It needs no KVM, no Docker and no inbound port |
| Sandbox node | `/dev/kvm` (on a VM: nested virtualisation, as above), Linux 5.14 or later (`cgroup.kill`), cgroup v2, systemd, an IPv4 address that the control host reaches, memory for `maxVms` VMs of each template and disk room for their snapshots. During its first run only: the release, GitHub (Firecracker, Kata) or your mirrors of them. Only Ubuntu 24.04 on x86-64 has run a node |
| VM image | Built by `runner/images/vm/local-disk.sh` with the control host's own runner host binary, on a machine with Docker that is not a node. Tinfirm does not publish it yet |

### The VM image

Tinfirm does not publish the VM image for a local disk yet (planned: signed, with each release).
Build it, compress it, and give the role the file and its SHA-256:

```sh
sudo runner/images/vm/local-disk.sh /path/to/tinfirm-runner-host ./out   # the control host's binary
zstd -T0 ./out/tinfirm-vm.img -o tinfirm-vm.img.zst                      # 1 to 4 GB of 32 GiB, by the runtimes
sha256sum tinfirm-vm.img.zst
```

A name that ends in `.zst` is unpacked on the node with its holes kept. A file that is not
compressed is copied or downloaded whole, 32 GiB by default. Build the image again after every
upgrade of the control host's binary: the control host warns of a VM of another build
(`--vm-image-version`).

### The inventory

```yaml
tinfirm_control_hosts:
  hosts:
    control-1.internal:
  vars:
    tinfirm_runner_host_server_url: https://tinfirm.internal
    tinfirm_runner_host_token: "{{ vault_tinfirm_runner_token }}"
    tinfirm_runner_host_binary_url: https://nexus.internal/repository/tinfirm/tinfirm-runner-host-<version>-x86_64-unknown-linux-musl.tar.gz
    tinfirm_runner_host_max_concurrent_runs: 4          # at most the nodes' free VMs
    tinfirm_runner_host_vm_template: sandbox-2c4g
    tinfirm_runner_host_sandbox_npm_registry: https://nexus.internal/repository/npm/
    tinfirm_runner_host_nodes:
      - name: node-1
        address: 10.0.0.20:7700
        nodeSha256: ""                                  # the node's play shows it
        templates: [sandbox-2c4g]
        publishedPorts: {first: 20000, last: 20001}

tinfirm_sandbox_nodes:
  hosts:
    node-1.internal:
      tinfirm_sandbox_node_address: 10.0.0.20
  vars:
    tinfirm_sandbox_node_control_host: 10.0.0.5          # as the nodes see its connections
    tinfirm_sandbox_node_management_address: 10.0.0.9    # where this play's SSH comes from
    tinfirm_sandbox_node_client_sha256: ""               # the control host's play shows it
    tinfirm_sandbox_node_binary_url: https://nexus.internal/repository/tinfirm/tinfirm-node-agent-<version>-x86_64-unknown-linux-musl.tar.gz
    tinfirm_sandbox_node_image_src: files/tinfirm-vm.img.zst
    tinfirm_sandbox_node_image_checksum: "sha256:…"
    tinfirm_sandbox_node_max_vms: 2
    tinfirm_sandbox_node_templates:
      sandbox-2c4g: {vcpus: 2, memoryMib: 4096, snapshotMib: 8192}
```

Take the control host's and the nodes' binaries from one release: the driver and the agent
compare their releases when they connect (`peerBuild`, a warning by default).

### A first set-up

The two machines need each other's fingerprint. In this order:

1. **The control host, first time:** `ansible-playbook -i inventory.yml
   deploy/ansible/control-host.yml --ask-vault-pass`. It installs the host, logs it in, makes the
   client certificate and shows **"The control host's client fingerprint"**. With no node pinned
   yet it stops there: the host does not run.
2. **The nodes:** set that fingerprint as `tinfirm_sandbox_node_client_sha256`, and run
   `ansible-playbook -i inventory.yml deploy/ansible/sandbox-node.yml`. Each node ends with the
   helper's check of its templates and shows **"The node's fingerprint, which the control host
   pins"**, with the node's entry for `tinfirm_runner_host_nodes`.
3. **The control host again:** set each node's fingerprint as its `nodeSha256` and run
   `control-host.yml` again. It writes the driver's configuration, asks every node through the
   driver, and starts the host. Its page in Tinfirm shows it **Online**, and its start's log line
   names the isolation level `machine`.
4. **Both again:** a second run of each play reports `changed=0`.

| Value | Made by | Goes into |
|---|---|---|
| The control host's client fingerprint | `control-host.yml` (`tinfirm-runner-host node identity`) | Each node's `tinfirm_sandbox_node_client_sha256`, so `listener.json`'s `tls.clientSha256` |
| A node's fingerprint | `sandbox-node.yml` (`tinfirm-node-agent identity`) | That node's `nodeSha256` in `tinfirm_runner_host_nodes`, so `node-driver.json` |
| The control host's address, as the nodes see it | You | `tinfirm_sandbox_node_control_host`: `helper.json`'s `network.controlHost`, `listener.json`'s `tls.controlHost`, the listener's `IPAddressAllow=`, the firewall's `control_host` |
| A node's address and agent port | You (default: the address of the default route, port 7700) | The node's `address` in `tinfirm_runner_host_nodes`, `HOST:PORT` |
| The templates | The node's `tinfirm_sandbox_node_templates` | The node's `templates` and `tinfirm_runner_host_vm_template` |
| A template's `bridgePort` (default 7443) | The node's template | `tinfirm_runner_host_vm_port` |
| The published ports | `tinfirm_sandbox_node_first_port` and `_max_vms` | The control host's firewall, and the node's `publishedPorts`, which the play lists for it |

`run` refuses to start while the nodes have fewer free VMs than `--max-concurrent-runs`: keep
`tinfirm_runner_host_max_concurrent_runs` at most the sum of the nodes' `tinfirm_sandbox_node_max_vms`.
The play warns when it is above what the nodes have free.

### Take a node out

The driver's configuration names exactly the nodes that have a `nodeSha256` at the last run of
`control-host.yml`. Emptying a node's `nodeSha256`, or taking the node out of
`tinfirm_runner_host_nodes`, takes it out at the next run: the play rewrites the file without it
and reloads the host, which drains. The runs in progress finish, the node's own runs included,
and the host starts again without the node. A node without a pin is named in the play's output as
waiting, and a new node without one waits the same way while the host serves the others. With no
node pinned at all, the play stops the host (SIGTERM, which cancels its runs and pushes their work
in progress) and removes the file.

When the node may have been taken over, do not let its runs finish: after the play, run
`systemctl restart tinfirm-runner-host` on the control host, which cancels them at once.

### Replace a node

A node is re-imaged on a schedule, and replaced at once when it may have been taken over:

1. Empty the node's `nodeSha256` and run `control-host.yml`: the host drains, so that the runs on
   that node finish, and starts again without it ([Take a node out](#take-a-node-out)).
2. Replace the node's image, and run `sandbox-node.yml` on it with the same variables. It makes a
   new TLS identity and shows its new fingerprint: the old key went with the old image.
3. Set the node's **new** fingerprint as its `nodeSha256`, and run `control-host.yml`, which puts
   it back. Had you kept the old pin, the driver would refuse the node, whose certificate is not
   the pinned one: such a node is not counted, and the host runs on the others.

A node that was taken over has handed back work that outlasts it (ADR-0112, Consequences): review
the branches and artifacts of the runs it served, and discard their sessions.

### Replace the control host's certificate

Remove `/etc/tinfirm/node-driver/client.crt` and `client.key` on the control host and run
`control-host.yml`. It makes a new pair and shows the new fingerprint; it fails at its `verify`,
since no node accepts it yet. Set the fingerprint as every node's
`tinfirm_sandbox_node_client_sha256`, run `sandbox-node.yml`, which restarts each listener, then
`control-host.yml`. A listener accepts one client certificate, so no run starts on a node in
between. Do it at once if the key may have leaked.

### A machine with Docker

A control host runs no Docker socket and no hypervisor (ADR-0112 decision 3): its user would
otherwise be root on the machine that holds every run's credentials. `control-host.yml` therefore
refuses, in its first checks and before it changes anything, a machine where Docker, containerd
or Kata is installed, Docker's socket exists, or the runner host's user is in the `docker` group.
It does not remove them itself: a Kata host is not converted into a control host in place.
ADR-0112 decision 7 makes such a machine a sandbox node, with a new image, and puts the control
host on another machine. Use a fresh machine, or remove Docker, containerd and Kata and take the
user out of the `docker` group first.

A runner host of the Docker form on such a machine is left as it is, with its runs: the play
fails before it touches the binary, the unit or the service, and says so when it finds that
host's unit. Drain it (`systemctl reload tinfirm-runner-host`, until it runs again with no run),
stop it, and then re-image the machine.

### Firewalls

- **On the node:** the role's table, `inet tinfirm_node` in
  `/etc/tinfirm/node-agent/firewall.nft`, loaded by `tinfirm-node-agent-firewall.service`. It admits
  the control host to the agent's port and to the published ports, SSH from the management address,
  the DHCP lease (`tinfirm_sandbox_node_dhcp: false` removes it), and drops everything else, every
  connection the node opens included. Root on the node can delete it. The helper then makes no new
  VM, and its `check` names the table as a problem, so the control host places no VM on the node
  until the table is loaded again (`systemctl reload tinfirm-node-agent-firewall.service`). The
  VMs that run go on running.
- **On the control host:** this role sets no firewall; it is yours. It lets the control host reach
  each node's agent port and published ports, and admits nothing from the nodes' network
  (ADR-0112 decision 3).
- **Outside the node:** the network that keeps a node from reaching the server, the model gateway,
  the code hosts, the control host's ports, the other nodes and the metadata services is yours too.
  It is the guard against root on a node, which the node's own table is not.

### The hardening

[ADR-0113](../../docs/adr/0113-levels-of-isolation-and-placement.md) decision 5: a mark beside the
level, `yes` when every line holds on the node. The node agent's helper checks each line in its
`check`, and writes none (the agent's README, "The hardening mark"); the role sets them up, each
by a switch, all on by default, and `tinfirm_sandbox_node_harden: false` turns every one off. A
switch that is off leaves the machine's setting as it is: the line may then fail, the mark says
`no`, and the node runs on. The control host does not report the mark to the server yet (planned).

| Switch (`tinfirm_sandbox_node_harden_…`) | What the role does | What it costs |
|---|---|---|
| (none) | `user.max_user_namespaces = 0`, always (`60-tinfirm-node-agent.conf`): the jail relies on it | No user namespaces on the node |
| `bpf` | `kernel.unprivileged_bpf_disabled = 1` in `/etc/sysctl.d/61-tinfirm-node-hardening.conf` | One-way until a reboot |
| `io_uring` | `kernel.io_uring_disabled = 2`, same file. Refused on a kernel without the setting (older than 6.6: Debian 12's 6.1) | No io_uring on the node |
| `panic_on_oops` | `kernel.panic_on_oops = 1` and `kernel.panic = tinfirm_sandbox_node_kernel_panic` (0: the node stays down) | An oops ends every run on the node |
| `distribution` | `kernel.kptr_restrict`, `kernel.dmesg_restrict`, `kernel.perf_event_paranoid`, `kernel.randomize_va_space`, `fs.protected_symlinks`, `fs.protected_hardlinks` at the mark's values; a value the node has stricter already (Ubuntu's `perf_event_paranoid = 4`) is left out, never lowered | Users cannot read the kernel's log or addresses |
| `mac` | Installs AppArmor, writes the VMMs' profile `/etc/apparmor.d/tinfirm-vmm` (checked with `apparmor_parser -Q -K` first), loads it, and writes `"vmmProfile": "required"`: a make fails for a VMM the profile does not confine. Refused on a node without AppArmor, which a Red Hat family node is | **The profile is unverified** until the round on a real machine: it parses, and has not confined a VMM yet. VMMs that ran before it was loaded stay unconfined, and the mark `no`, until they are deleted. The profile names `/srv/tinfirm-jails` and `/var/lib/tinfirm-node-agent/work`, which the role does not let a site change |
| `modules`, `kexec`, `lockdown` | `/etc/tinfirm/node-agent/hardening.json`, which `tinfirm-node-agent-hardening.service` applies at each boot after the modules' and firewall's units and after kdump: kexec off, lockdown at `integrity`, then module loading off once every module the agent needs is loaded. Lockdown is refused on a kernel without the lockdown LSM | **All three last until a reboot, and only a reboot undoes them.** No module loads after it: a firewall rule or a tool that needs a new one fails. A change of the modules' list while module loading is off stops the play with "reboot the node": the new unit is installed and loads at the next boot |
| `setuid` | Finds the setuid and setgid programs under `/usr`, `/opt`, `/bin` and `/sbin` that others may run, as the helper's scan does, and strips both bits; keeps `sudo` as `4750 root:sudo` (`root:wheel` on the Red Hat family). On Debian and Ubuntu each change is recorded with `dpkg-statoverride`, so that a package upgrade keeps it; on the Red Hat family an upgrade puts the bits back, and the next run of the role removes them again. Refused for a play whose user is neither root nor in that group | Users lose `su`, `passwd`, `chsh`, `chfn`, `newgrp`, `gpasswd`, `mount`, `umount`, `fusermount3`, `pkexec`, `ssh-keysign`, `crontab`, `wall`, snaps (`snap-confine`), and every other such program; `sudo` stays for the admin group alone |

The lines of no switch of their own follow the master switch: `no-docker` (no Docker, containerd or
podman socket), `listening-sockets` (no TCP listener beyond loopback but the agent's port and
`tinfirm_sandbox_node_ssh_port`) and `max-uptime` (at most `tinfirm_sandbox_node_max_uptime_days`
since the boot: re-image or reboot the node before then). A line whose switch is off is printed as
off by the proof, not failed.

**A reboot** keeps the hardening: the sysctl files and the profile are loaded at boot, and the
hardening's unit sets kexec, lockdown and module loading again before the helper starts. Its
failure does not stop the helper: the mark says `no`, and the unit's journal says why.

### Not done by these roles

- **SELinux on a Red Hat family node** (ADR-0113 decision 5): such a node reports `no` for the line
  `vmm-mac`, and the role refuses the AppArmor switch there.
- **The single machine** (ADR-0112 decision 5): the runner host and the node agent on one machine.
  The helper refuses a control host on the node's own machine, and so does the role.
- **`verify` trying the node's own egress** (the server, the model gateway, the code hosts, a
  metadata service). It is planned in the agent: check it from the node yourself (below).
- **An upgrade in place** of a node under running VMs: how the agent and the image of several nodes
  are upgraded is not specified yet. Replace the node's image instead. Change the VM image's
  variables only on a node without VMs: the role replaces the file and restarts the helper, and
  does not check for VMs first.
- **Kata's removal** from the runner hosts of `runner-host.yml`.
- **Another distribution or aarch64**: the role knows the RHEL family's and Debian's package names and
  Firecracker's arm64 checksums, but only Ubuntu 24.04 on x86-64 has run a node, and no arm64 guest
  kernel is pinned.

### Variables of a sandbox node

[`defaults/main.yml`](roles/tinfirm_sandbox_node/defaults/main.yml) documents each one.

| Variable | Default | Meaning |
|---|---|---|
| `tinfirm_sandbox_node_binary_src` / `_binary_url` | none | The node agent: a file on the control node, or the release archive `tinfirm-node-agent-<version>-<target>.tar.gz`. Set exactly one |
| `tinfirm_sandbox_node_release_url`, `_public_key`, `_binary_checksum`, `_allow_unsigned`, `_binary_url_headers` | the archive's directory, `cosign.pub`, none, `false`, `{}` | As the runner host's `tinfirm_runner_host_*` of the same names |
| `tinfirm_sandbox_node_firecracker_version` | `1.14.2` | Firecracker and its jailer. The role knows the checksums of 1.14.2 on x86_64 and aarch64 |
| `tinfirm_sandbox_node_firecracker_download_url` | GitHub's releases | Or a mirror with the same layout |
| `tinfirm_sandbox_node_firecracker_checksums` | `{}` | `{archive, firecracker, jailer}`, each `sha256:<hex>`, for a version the role does not know |
| `tinfirm_sandbox_node_kernel_kata_version` | `4.3.1` | The Kata release the guest kernel comes from |
| `tinfirm_sandbox_node_kernel_download_url`, `_kernel_archive` | GitHub's releases, `kata-static-<version>-<arch>.tar.zst` | Where it is |
| `tinfirm_sandbox_node_kernel_checksums` | `{}` | `{archive, member, kernel}` for another version or architecture |
| `tinfirm_sandbox_node_image_src` / `_image_url` | none | The VM image: a file on the control node, or a URL. Set exactly one; `.zst` is unpacked |
| `tinfirm_sandbox_node_image_checksum` | none | `sha256:<hex>` of the file as given. Required |
| `tinfirm_sandbox_node_address` | the default route's address | The node's IPv4 address as the control host dials it |
| `tinfirm_sandbox_node_control_host` | none | The control host's IPv4 address, as the node sees it. Required |
| `tinfirm_sandbox_node_management_address` | none | The one address admitted to SSH. Required |
| `tinfirm_sandbox_node_ssh_port` | `22` | The node's SSH port, which the firewall admits from the management address (its `ssh_port`). The role does not change sshd's port |
| `tinfirm_sandbox_node_check_ssh_source` | `true` | Stop when the play's SSH comes from another address, or goes to another port than `tinfirm_sandbox_node_ssh_port` |
| `tinfirm_sandbox_node_cloud_init` | `refuse` | `refuse` stops the play on a node whose cloud-init would run at the next start; `disable` writes `/etc/cloud/cloud-init.disabled`. With cloud-init active, a node does not finish its start once the firewall is loaded, since cloud-init waits for the metadata service that the firewall drops |
| `tinfirm_sandbox_node_agent_port` | `7700` | The listener's TLS port |
| `tinfirm_sandbox_node_first_port` | `20000` | The first VM's published port; the last is `first_port + max_vms - 1` |
| `tinfirm_sandbox_node_vm_range` | `10.231.0.0/16` | The VMs' own addresses, which never leave the node |
| `tinfirm_sandbox_node_dhcp` | `true` | Admit the DHCP lease's renewal; `false` for a static address |
| `tinfirm_sandbox_node_client_sha256` | none | The control host's client fingerprint. Required |
| `tinfirm_sandbox_node_peer_build` | `warn` | A driver of another release: `warn` or `refuse` |
| `tinfirm_sandbox_node_max_vms` | `2` | VMs at once, 1 to 1024 |
| `tinfirm_sandbox_node_templates` | `sandbox-2c4g`: 2 vCPUs, 4096 MiB, a snapshot of 8192 MiB | Each with `vcpus`, `memoryMib`, `snapshotMib`, and optionally `bridgePort` and `pidsMax`; the role adds the image and the kernel |
| `tinfirm_sandbox_node_vm_uid_base` | `1100000` | The VMs' users are this plus the slot |
| `tinfirm_sandbox_node_listener_uid` | the system's choice | The listener's uid |
| `tinfirm_sandbox_node_guest_console` | `false` | The guest's serial console in a file on the node; leave it off where runs are real |
| `tinfirm_sandbox_node_cgroup_parent`, `_watch_ms` | `tinfirm-vms`, `1000` | `firecracker.cgroupParent`, the listener's `watchMs` |
| `tinfirm_sandbox_node_name` | the inventory name | The node's name in the entry the play shows |
| `tinfirm_sandbox_node_harden` | `true` | The master switch of the hardening: `false` turns every line off |
| `tinfirm_sandbox_node_harden_bpf`, `_io_uring`, `_mac`, `_panic_on_oops`, `_modules`, `_kexec`, `_lockdown`, `_setuid`, `_distribution` | `true` each | One line of the hardening each ([The hardening](#the-hardening)) |
| `tinfirm_sandbox_node_kernel_panic` | `0` | `kernel.panic`: seconds before a node that panicked reboots; 0, it stays down |
| `tinfirm_sandbox_node_max_uptime_days` | `30` | The most days since the boot before the mark is `no` (`hardening.maxUptimeDays`) |

### Variables of a control host

With `tinfirm_runner_host_executor: vm`, which `control-host.yml` sets, the Docker, Kata and image
variables above have no effect. The binary, login, user, `run` and sandbox settings keep theirs.

| Variable | Default | Meaning |
|---|---|---|
| `tinfirm_runner_host_executor` | `docker` | `vm`: a control host |
| `tinfirm_runner_host_nodes` | `[]` | Each node: `name`, `address` (`HOST:PORT` of its agent), `nodeSha256` (empty until its play shows it; emptied, the node is taken out at the next run), `templates`, and optionally `publishedPorts` `{first, last}`, which the play lists for your firewall. The driver's file names only the nodes with a pin |
| `tinfirm_runner_host_vm_template` | none | `--vm-template`; at least one node with a pin must offer it |
| `tinfirm_runner_host_vm_port` | `7443` | `--vm-port`: the templates' `bridgePort` |
| `tinfirm_runner_host_vm_probe` | `[]` | `--vm-probe`, one per entry |
| `tinfirm_runner_host_node_peer_build` | `warn` | The driver's `peerBuild` |

### Checks

What runs for these plays without a machine (no KVM and no systemd):
`ansible-playbook --syntax-check` of the three playbooks, `ansible-lint --profile production`,
and the templates rendered with example values and checked against the node agent's and the
runner host's READMEs:

```sh
ansible-playbook -i localhost, deploy/ansible/tests/render.yml -e render_dir=/tmp/rendered
python3 -I deploy/ansible/tests/check-rendered.py /tmp/rendered
```

`check-rendered.py` checks `helper.json` and `listener.json` field by field against the agent's
README, `node-driver.json` against the runner host's, the rendered firewall against the units' own
file with the same values, the control host's command line, the units' order and copies, the
firewall with `nft -c -f` where `nft` may check a ruleset, and the hardening's files: all switches
on, each off in turn (its lines alone gone), the master switch off (none), and a node below every
setting. CI's deploy job also parses the VMMs' profile with `apparmor_parser -Q -K`.

On a node, after the play, check its egress yourself, as root: each of these must time out.

```sh
for target in <control host>/22 1.1.1.1/443 8.8.8.8/53 169.254.169.254/80; do
  timeout 3 bash -c "</dev/tcp/$target" 2>/dev/null && echo "REACHED $target" || echo "no answer from $target"
done
```

### Test on two machines

Ran once, on 2026-11-20, at commit `bfa9bfd`, on Ubuntu 24.04 machines: a control host
(2 vCPUs), a node (4 vCPUs, KVM) and a third machine as the management address, in one subnet.
The first runs were those of the runner host tests' stand-in agent, in an image added to the VM
image, with no model. Ten conversations with real runtimes (Claude Code and PI) and a model
followed on the same machines, among them two at once, a cancel, a control host killed during a
run and a grant withdrawn during a run. Three defects of the plays were found and are fixed here:

- After the node's firewall was loaded, the next task failed with "Timeout (12s) waiting for
  privilege escalation prompt". The firewall drops the node's DNS queries, the cloud image's own
  name was in no file, and sudo waited 10 seconds for it at every call. The role now writes the
  name into `/etc/hosts` before the firewall is enabled.
- A run that failed after the sysctl file was written left the settings unapplied for good: the
  handler that applies them runs only when the file changes. The role now applies them whenever
  the node does not have them.
- `control-host.yml` got no answer to its `verify`: Ansible turned the request, a string that
  starts with a brace, into a dictionary, and the driver got text that is not JSON. The task then
  failed in its own message instead of naming the cause. Both are fixed.

The steps, each as it ran:

1. Build the VM image on a machine with Docker that is neither of them, with the control host's
   `tinfirm-runner-host`, and compress it (above). 407 seconds on 4 vCPUs with two runtimes; the
   compressed file was 3.8 GB, not 1 GB.
2. Register a company runner host in Tinfirm and keep its token in Vault.
3. Run `control-host.yml`: it ends with the client fingerprint and "not started". 36 seconds.
4. Set the fingerprint on the node, run `sandbox-node.yml`: every template "no problem", the node's
   fingerprint. On the node: `systemctl is-active tinfirm-node-agent-{modules,firewall,helper,listener}`,
   `nft list table inet tinfirm_node`, `sysctl user.max_user_namespaces` (0). 172 seconds on a
   fresh node, most of it the image.
5. Set the node's fingerprint, run `control-host.yml`: the driver's `verify` with no problem and the
   node's free VMs, then the host **Online** in Tinfirm, its log line with `machine`.
6. Run a conversation on the host, and see a VM made and deleted on the node. Ran with the
   stand-in agent: 9 seconds from the assignment to the push.
7. Run both plays again: `changed=0` on both.
8. Replace the node's image and follow [Replace a node](#replace-a-node). Ran with a new VM in
   the node's place. With the old pin kept, the play fails at its `verify`: "the node's
   certificate does not match its pin".
9. Empty the node's `nodeSha256` and run `control-host.yml`: the play names the node as waiting,
   and with the only node out the host is stopped and `/etc/tinfirm/node-driver.json` is gone.
   Pin it again: the host starts. With one node only, "the host drains and runs again" did not run.
10. Run `control-host.yml` against a machine with Docker installed: it fails in its checks, and
    changes nothing there.

Also ran: the node rebooted (it takes SSH again after 14 seconds, with its four units and its
table), `tinfirm_sandbox_node_cloud_init: refuse` on a cloud image (the play stops before it
changes anything), the node's egress (the four targets time out), and the firewall table deleted
(the helper makes no VM, and `doctor` on the control host names the table).

Not run: the play's SSH from another address than the management address, a wrong image
checksum, a second node, an upgrade of a node, another distribution, and aarch64.

Two things to know that the plays do not change:

- The first `control-host.yml` with a pinned node starts the host and reloads it in the same run
  (the unit is new, and the driver's file changed): the host drains with no run and starts again
  within 5 seconds.
- Where an earlier run left a node with its firewall loaded and its own name in no file, give
  Ansible more time once: `ANSIBLE_TIMEOUT=40 ansible-playbook … sandbox-node.yml`.
