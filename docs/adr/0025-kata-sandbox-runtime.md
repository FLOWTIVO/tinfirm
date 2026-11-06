# ADR-0025: Kata Containers as the sandbox runtime, with the agent's own Docker inside

- Status: Accepted
- Date: 2026-10-03
- Sources: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md); CONVENTIONS §15.2, §15.3, §16; [threat model](../threat-model.md) §5.6

## Context

- Many projects' builds use Testcontainers, and their agents need `docker compose` and image builds. Without a Docker daemon an agent cannot run the project's own tests, which the prompt asks it to do (CONVENTIONS §15.5).
- The sandbox is a container under runc, tier `container` (ADR-0006): it shares the runner host's kernel, runs as uid 1000 with no capabilities and `no-new-privileges`.
- The container boundary cannot grant Docker safely:
  - the runner host's Docker socket in the sandbox is root on the runner host, with its credentials, its mirrors and every other run's workspace;
  - a Docker daemon inside the sandbox needs every capability and no seccomp profile. Under runc that is root on the host as well.
- Runner hosts can be Linux VMs with nested virtualisation (KVM inside the VM).
- The isolation tier was planned as a setting, with `microvm` (Kata with Cloud Hypervisor) for M3.

## Decision

**The runtime is a setting of the runner host**
- `run --sandbox-runtime <name>` sets the Docker runtime of every sandbox (`HostConfig.runtime`). The default is Docker's default runtime (runc).
- With a Kata Containers runtime (`kata`, Cloud Hypervisor) every sandbox is a microVM with its own kernel. The tier follows the name: `kata…` is `microvm`, `runsc…` is `gvisor`, anything else is `container`.
- `run` refuses to start with a runtime that Docker does not know. The startup log line names the effective runtime and the tier. The protocol does not change yet: `hello` still reports `container`.

**The agent's own Docker, only in a VM**
- `run --sandbox-docker` gives the agent a Docker daemon in its sandbox. The sandbox container then:
  - starts as root, with every capability, no seccomp, AppArmor or SELinux confinement, no masked paths (`systempaths=unconfined`) and a private cgroup namespace;
  - may use the VM's loop devices (device cgroup rules `b 7:*` and `c 10:237`);
  - gets `TINFIRM_SANDBOX_DOCKER=1` and the agent's user, `TINFIRM_SANDBOX_AGENT_USER=1000:1000`.
- **It is never `--privileged`.** Docker puts every host device into the spec of a privileged container, and Kata hot-plugs those block devices into the VM: the agent would get the runner host's disks. `privileged_without_host_devices`, which Kubernetes setups use against this, is an option of containerd's CRI plugin and of CRI-O; Docker has no such option. The settings above are what `--privileged` gives, without the host's devices.
- **Rule:** `run` refuses `--sandbox-docker` unless the runtime's name starts with `kata`. Under runc or gVisor these settings would be root on the host, or not a VM boundary.
- The image's entrypoint (`tinfirm-sandbox-entrypoint`):
  1. makes `/sys` and the cgroup filesystems writable (Docker mounts them read-only in a container that is not privileged) and enables cgroup v2 nesting;
  2. mounts an ext4 loop disk at `/var/lib/docker`: a sparse file in the container's own filesystem, never under `/workspace` or `/home/agent`. Under Kata that filesystem is virtio-fs, which overlayfs cannot use as its upper layer;
  3. writes `daemon.json` with the sandbox's registry mirrors, starts dockerd, and waits a bounded time; on failure it stops with the daemon's log;
  4. sets `TESTCONTAINERS_HOST_OVERRIDE=localhost` and runs the ACP adapter as uid 1000.
- **What stays as it is:** the bind mounts (`/workspace`, `/home/agent`, attachments), the git proxy, the platform MCP endpoint, the redaction, the push from the host, and the resource limits, which now size the VM. Git credentials and the host token never enter the sandbox.

**Mirrors and installation**
- `run --sandbox-env KEY=VALUE` passes `TINFIRM_SANDBOX_*` settings to every sandbox. The entrypoint writes dockerd's registry mirrors and the Maven, npm, pip and uv settings from them. The values are never credentials.
- `images build --build-arg KEY=VALUE` builds the images through the same mirrors.
- An Ansible role (`deploy/ansible`) installs Docker Engine, the pinned Kata static release with Cloud Hypervisor, the `kata` runtime in `daemon.json`, the runner host with its systemd unit, and the images.

## Consequences

- The runner VMs need nested virtualisation. Without `/dev/kvm` Kata cannot run, and the Ansible role stops. The fallback is a dedicated Docker VM per run slot, reset between runs, with the runner host as its controller: two to three weeks of work.
- In its VM the agent is root-equivalent: it controls dockerd. The VM is the boundary to the runner host and to other runs (one VM per run). An escape needs an exploit of KVM, Cloud Hypervisor or virtiofsd, not of the host kernel's system calls.
- The workspace and the session home reach the VM over virtio-fs, so Maven and npm on large workspaces are slower than on a bind mount under runc. If measurements show it matters, the workspace moves onto a disk inside the VM.
- The sandbox's limits now cover dockerd and the agent's containers too. The defaults (4 GiB, 2 CPUs, 1024 pids) may be tight for a JVM build with a database.
- Every run starts with an empty `/var/lib/docker`, so it pulls its images again: a registry mirror is needed in practice. The loop disk uses runner host disk space, up to its size (40 GiB sparse by default), until the run ends.
- Kata's documentation names containerd and CRI-O as container managers, not Docker. Docker 23 and newer run shim v2 runtimes, but the combination needs checking on each kind of VM: networking to the git proxy on the bridge's gateway, the loop disk, and dockerd in the VM (deploy/ansible/README.md, "Checks after the first install").
- A sandbox starts slower: a VM boot, then dockerd.
- The server cannot route runs by tier yet, because `hello` reports `container`.

## Alternatives considered

- **The runner host's Docker socket in the sandbox.** Rejected: root on the runner host.
- **Docker-in-Docker, privileged, under runc.** Rejected: a privileged container shares the host's kernel with every capability, so root in it is root on the host.
- **Rootless Podman or rootless Docker in the sandbox, or Sysbox.** Not chosen: they need user namespaces and a looser seccomp profile in a sandbox that still shares the host's kernel. Sysbox also needs its own runtime installed on the host, and Testcontainers support for rootless engines is partial.
- **Firecracker directly.** Not chosen: the runner host would manage the VMs, their images, networking and the stdio bridge itself, where Kata does that behind the Docker API that the executor already uses. Firecracker also has no virtio-fs, which the bind mounts need.
- **Docker Sandboxes (`sbx`).** Not chosen: Docker's tool runs a coding agent in a sandbox with its own Docker on a developer's machine, with its own agent lifecycle. It would replace the executor instead of plugging into it, and the runner host would lose the stdio attach, the git proxy and the bind mounts it relies on.
- **Kata with `--privileged` and `privileged_without_host_devices`.** Not possible with Docker, which has no such option (see Decision).
