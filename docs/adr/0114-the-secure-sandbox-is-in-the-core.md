# ADR-0114: The secure sandbox is in the core; elasticity stays paid

- Status: Accepted
- Date: 2026-11-17; split from the first draft of [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (2026-11-14) after a review
- Decided by: the owner, 2026-11-19: the split as decided on 2026-11-14, and the three open questions answered as this draft proposed them ("Questions the owner answered"); ready VMs are off unless an operator turns them on
- Built: decisions 2 and 3 (the code's cut, the pool cut in two, the hello's gate with `sandbox.elastic`), of decision 4 that ready VMs are off by default, and the chart's runner pod with the `node` driver (`92da902`), which has not run against a node yet (ADR-0112's node agent makes VMs since `52ee70e`). The public pricing page is not in this repository
- Sources: [ADR-0077](0077-licence-editions-and-seats.md) (the editions, `ee/`, decisions 1 and 4), [ADR-0095](0095-sandbox-bridge.md) to [ADR-0099](0099-pool-settings.md), [ADR-0100](0100-elastic-runners-in-pro.md) (elastic runners are paid), [ADR-0101](0101-closing-the-elastic-runners-gaps.md) (the runner pod of the chart), [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (the control host, the node agent), [ADR-0113](0113-levels-of-isolation-and-placement.md), [ADR-0115](0115-sandbox-vms-in-a-kubernetes-cluster.md); [CONVENTIONS.md](../CONVENTIONS.md) §14.10, §15.24; the Kubernetes runner analysis, D9 and D10 (an operator can bring a driver)
- Amends: [ADR-0100](0100-elastic-runners-in-pro.md) (what `ELASTIC_RUNNERS` holds, what is under `ee/runner/`, the hello's gate), [ADR-0077](0077-licence-editions-and-seats.md) (decision 4's row for elastic runners), [ADR-0095](0095-sandbox-bridge.md) and [ADR-0096](0096-a-vm-per-run.md) (their code is in the core again; the provider drivers stay paid), [ADR-0098](0098-ready-vms.md) (the pool is cut in two: a VM for a run in the core, ready VMs paid)
- Changes: the runner host's code that moves from `ee/runner/` to `runner/` (decision 2) with its manifests and licences; the pool's cut; the server's gate at the hello (`RunnerWebSocketHandler`) and `Feature.ELASTIC_RUNNERS`'s description; `ModularityTest`'s list of gates; `THIRD-PARTY-LICENSES.txt` and the licences in the release archives; the Ansible role's defaults; the Helm chart's runner pod; the public pricing page; CONVENTIONS §15.24

## Context

- Elastic runners are Pro's and Enterprise's (ADR-0100): the sandbox bridge, a VM per run with its driver protocol, DigitalOcean's driver and the VM image's recipe, ready VMs and their schedule, and session bundles. Their code is under `ee/`, the runner host's under `ee/runner/`, compiled into the one binary.
- Community keeps runner hosts with Docker: a container per run, or a Kata VM as a Docker runtime. ADR-0112 removes Kata and makes the VM a Firecracker VM that the runner host reaches over the bridge, through the VM executor and a driver. All three are paid code today. Without a change, Community would be left with containers alone, which ADR-0112 says are not for hostile input.
- The server's one gate for the runner host's paid code is at the hello: a host that lists the feature `sandbox.bridge` or `sandbox.pool` is refused without `ELASTIC_RUNNERS` (`RunnerWebSocketHandler`). Every host that runs agents over the bridge lists `sandbox.bridge`, whatever its driver.
- The driver protocol is open by decision: an operator can bring a driver of their own (ADR-0096; D9, D10 of the analysis).
- The VM executor creates every VM through the pool, the cold ones too (`pool.rs`, ADR-0098): the pool is where a run gets its VM, and ready VMs are one of its settings.
- ADR-0100 rejected "keep the runner host's code MIT and gate the server only", because a paid feature's code would then be free to copy and to run against any server.

## Decision

**What keeps a hostile agent in is free, on one machine, on two and on twenty. What a company pays for is elasticity: sandboxes that are made and removed with the load, a start without the wait, and runner hosts that are interchangeable.**

1. **The split.**

   | In the core (Community) | Pro and Enterprise (`ELASTIC_RUNNERS`) |
   |---|---|
   | The sandbox bridge (ADR-0095) | Drivers for a VM per run at a provider (DigitalOcean's today): machines that come with a run and go with it |
   | The VM executor (ADR-0096) with the `node` driver, and the driver protocol | Ready VMs and their schedules (ADR-0098, ADR-0099): a run that starts at once |
   | The node agent (ADR-0112), on the runner host's machine or on nodes of its own, as many as a company sets up | The cluster's own forms of ADR-0115: a pod per run, KubeVirt, the node agent as a DaemonSet. Sandboxes that a cluster places and scales |
   | The VM image and its recipe | Session bundles (ADR-0097): a turn resumes on any runner host, so that hosts can come and go |
   | The levels, the hardening mark, and an agent's choice of isolation (ADR-0113) | |
   | The runner host as a pod of the chart, with nodes outside the cluster (ADR-0101, decision 8; ADR-0115's first form) | |

   Community starts every run cold, on the nodes it has. A conversation there resumes on the runner host that holds its session, which with the nodes of one control host is every node.

2. **Where the code is cut.** The runner host stays one binary (ADR-0100, decision 3).

   | Code today, under `ee/runner/` | After |
   |---|---|
   | `tinfirm-bridge` (the crate) | Core: `runner/crates/tinfirm-bridge`, MIT |
   | `tinfirm-runner-host/src/bridge.rs`, `sandbox_files.rs`, `takeback.rs` | Core |
   | `vm.rs` (the executor, the driver protocol), `vm_boot.rs` | Core |
   | `images/vm/` (the VM image's recipe) | Core: Community needs the image. The Packer template for DigitalOcean stays paid, with the driver |
   | `pool.rs` | Cut in two. Core: a VM created for a run, claimed, and deleted after it, which is what the VM executor needs for a cold start. Paid: ready VMs, the target, the schedule (`sandbox.pool`) |
   | `digitalocean.rs` | Paid, as it is |
   | `session_bundles.rs` | Paid, as it is |
   | The tests `bridge_runs.rs`, `vm_runs.rs` | Core. `pool_runs.rs` is cut with the pool |

   New code of ADR-0112 (the `node` driver, `tinfirm-node-agent`) is in the core. The drivers of ADR-0115 are under `ee/runner/`.

3. **The server's gate.**

   - `sandbox.bridge` no longer needs a licence: a Community server accepts such a hello.
   - `sandbox.pool` and the session bundles' endpoints stay gated, as ADR-0100 decided.
   - A paid driver is gated by a feature of its own, `sandbox.elastic`, which a host lists when its VM driver is one of the binary's paid ones: a provider's, or one of ADR-0115's. The server refuses such a hello without `ELASTIC_RUNNERS`, with `EDITION_REQUIRED`, as it refuses `sandbox.bridge` today.
   - **What this gate is, and is not.** It holds for Tinfirm's own paid drivers in Tinfirm's own binary. It does not stop a driver that an operator wrote for the open driver protocol: such a host lists no paid feature, and the server cannot tell it from the `node` driver. That is ADR-0096's decision (an operator can bring a driver), and this ADR keeps it: what is paid is Tinfirm's drivers, their guards and their support, not the right to make VMs elsewhere. The owner confirmed on 2026-11-19 that this gate is enough.

4. **The defaults follow.** The Ansible role keeps the bridge on by default, which a Community server now accepts. Ready VMs are off unless an operator turns them on: `run --pool-max-ready` and the chart's `runner.pool.maxReady` default to 0 (the owner, 2026-11-19). The chart's runner pod works in Community with the `node` driver (planned: it waits for ADR-0112's node agent to run VMs).

## Consequences

- **Community gets a VM sandbox** on its own machines, with the same code a paying company runs. A Community server no longer refuses a host with the bridge.
- **Less is paid for.** The bridge, the VM executor, the driver protocol and the VM image leave `ELASTIC_RUNNERS`. The provider drivers, ready VMs, session bundles and the cluster's forms stay in it.
- **The bridge and the VM executor are MIT code again,** free to copy and to run against any server: what ADR-0100 chose to avoid, taken back on purpose for these parts, because a sandbox for hostile input should not need a licence.
- **The line around the paid drivers is thin.** With the driver protocol open and the executor free, a driver for a provider is a few hundred lines that anyone may write. DigitalOcean's driver is some 1,700 lines with its tests, much of it its guards (ADR-0096; threat model 6.16, R57): that, the conformance test and support are what a company buys.
- **The pool's cut is real work,** not a move of files: today no VM exists outside the pool.
- `ModularityTest`'s list of the core's gates changes: the hello's limit names other features.
- The release archives keep both licences: the binary still holds paid code.
- The public pricing page changes its row for elastic runners.

## Alternatives considered

- **Leave the bridge and the VM executor paid, and give Community containers alone.** Rejected by the owner: Community must have a sandbox that is safe to recommend for hostile input (ADR-0112, "The forces").
- **Close the driver protocol:** only the binary's built-in drivers. It would make the gate of decision 3 complete. Not proposed: ADR-0096 decided that an operator can bring a driver for an in-house cloud, and the on-premises hypervisors of ADR-0112 (libvirt, Proxmox, vSphere) are meant to come that way.
- **Gate by counting:** Community may have so many nodes or VMs. Rejected by the owner: the secure sandbox is free on as many nodes as a company sets up.
- **Keep the whole pool paid and give the core a second, simple path to a VM.** Two paths to create, claim and delete a VM, where the guards of one must be kept in step with the other. The cut of one pool is less code to get wrong.
- **Session bundles in the core.** They serve hosts that come and go, which is elasticity. Kept paid, as ADR-0100 decided.

## The owner's decisions so far

Decided by the owner, 2026-11-11 to 2026-11-14, and on 2026-11-19 where the date says so.

- Editions: the secure sandbox is free, on as many nodes as a company sets up; elasticity is paid: a provider's VMs per run, ready VMs, the Kubernetes forms and session bundles (decision 1).
- Community starts every run cold; ready VMs stay paid (decision 1).
- 2026-11-19: ready VMs are off unless an operator turns them on (`--pool-max-ready` defaults to 0, decision 4); the three open questions answered as proposed (below).

## Questions the owner answered

1. **The paid drivers' gate:** the feature `sandbox.elastic`, which only Tinfirm's own paid drivers list, is enough; the driver protocol stays open (decision 3). The owner, 2026-11-19.
2. **The cluster's forms and the free one:** the runner pod with nodes outside the cluster is free; the three forms in which the cluster itself runs the VMs are paid (decision 1). The owner, 2026-11-19.
3. **The VM image's recipe for DigitalOcean:** the Packer template is paid with the driver; the image's recipe is free (decision 2). The owner, 2026-11-19.
