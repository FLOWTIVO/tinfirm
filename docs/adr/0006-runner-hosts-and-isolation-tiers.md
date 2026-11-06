# ADR-0006: Runner hosts and sandbox isolation tiers

- Status: Accepted (built: company and personal runner hosts on Docker, one sandbox per run, the tiers `container`, `gvisor` and `microvm` as the host's Docker runtime gives them, `container` the default; the egress proxy is opt-in, [ADR-0044](0044-sandbox-egress-proxy.md); not built: the Kubernetes executor, the Docker socket proxy, Apple's `container`, a tier chosen per environment)
- Date: 2026-09-26

## Context

- An agent that runs as the host user acts with the operator's logins and no sandbox.
- Shared working directories destroy work.
- Subscription logins must stay on the owner's machine ([ADR-0007](0007-credentials-stay-in-deployment.md)).
- Many hosts have no KVM, for example Docker Desktop and most cloud VMs. The default setup must run on any Docker or Kubernetes host.

## Decision

- **Runner hosts.** Every run executes on a runner host.
  - A **company** runner host runs API-key runs. It usually sits next to the server.
  - A **personal** runner host belongs to one member and holds that member's subscription credentials. It runs on any machine the member controls.
  - The runner host connects out to the server. Neither the host nor the sandbox needs inbound ports.
  - The server never starts sandboxes. The worker assigns each run to a runner host with a free slot for the run's credential.
- **Executors:** Docker, through a socket proxy that allows only create, start, stop and remove; and Kubernetes pods.
- **One sandbox per run**, with a fresh clone on branch `agent/<TASK-KEY>`. WIP is pushed before teardown. Teardown deletes only the sandbox.
- **Isolation tiers.** Each environment selects a tier:

  | Tier | Technology | Host requirement | Milestone |
  |---|---|---|---|
  | `container` (default) | Rootless or userns-remapped container, seccomp, no capabilities, read-only root, own network namespace | Any Docker Engine, Docker Desktop or Kubernetes node | M1 |
  | `gvisor` | gVisor `runsc` (systrap) | Linux with gVisor, no KVM | M1 |
  | `microvm` | Kata 4 with Cloud Hypervisor; Apple `container` on macOS 26 | Linux with KVM; Apple silicon | M3 |

- **Network.** All egress goes through the egress proxy with a per-environment allowlist. On Kubernetes, a NetworkPolicy allows egress only to the runner host relay and the egress proxy. Tiers map to `runtimeClassName`.
- **Guidance.** Use `gvisor` for agents that read untrusted outside content. Use `microvm` for untrusted repositories or multi-tenant setups.

## Consequences

- The default runs on any Docker or Kubernetes host.
- The `container` tier depends on the host kernel. An escape needs a kernel exploit. The stronger tiers exist for higher risk.
- Docker socket access equals root on the host, even through the proxy. The docs say so.
- The agent can have broad tool permissions inside the sandbox, because the sandbox limits the blast radius. Business actions still go through policy-gated MCP tools.
- Sandbox start adds latency: image pull and clone. Mitigations: prebuilt images, a mirror cache, a warm pool in M3.
- A personal runner host is a single point of failure for its owner's subscription agents. The `runner_unavailable` alert tells the owner after 5 minutes.
- On a Mac, Docker Desktop already puts one VM between all containers and macOS.

## Alternatives considered

- **No sandbox; agents run as the host user**. Rejected.
- **`microvm` as the default.** Not chosen: it needs KVM or Apple silicon, which many hosts do not have.
- **The server starts sandboxes itself.** Rejected. The rule follows from two other decisions: subscription credentials stay on the owner's runner host ([ADR-0007](0007-credentials-stay-in-deployment.md)), and runner hosts need no inbound ports.
- **A shared working directory across runs.** Rejected: it destroys work.
