# ADR-0098: Ready VMs: a VM per run that waits for its run

- Status: Accepted
- Date: 2026-10-16
- Accepted by: the owner, 2026-10-27, with the fixes of the gaps it left open ([ADR-0101](0101-closing-the-elastic-runners-gaps.md))
- Amended by: [ADR-0100](0100-elastic-runners-in-pro.md): Ready VMs are part of elastic runners, Pro's and Enterprise's (`Feature.ELASTIC_RUNNERS`); their code is under `ee/`, the runner host's under `ee/runner/`.
- Amended by: [ADR-0101](0101-closing-the-elastic-runners-gaps.md): With `--pool-runtime any` the ready VMs run the image with every runtime (`tinfirm-runner-multi:local`), and the host names the run's adapter in `start`, so one pool serves every runtime.
- Sources: [ADR-0096](0096-a-vm-per-run.md) (a VM per run), [ADR-0095](0095-sandbox-bridge.md), [ADR-0044](0044-sandbox-egress-proxy.md), [ADR-0016](0016-runner-capacity-settings.md); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §6.3, §6.4 and §9 (step 5), and its decisions D5 and D12; [threat model](../threat-model.md) 6.15, 6.16, 6.18
- Changes: the VM executor creates every VM for no run, through a pool that keeps some ready; a VM's labels name the VM, no longer the run; the egress proxy gets listeners that a run binds later. The runner protocol does not change; Tinfirm's count of ready VMs (the analysis §6.4) follows in its own step.

## Context

- With ADR-0096 a run waits for the provider to create and boot its VM, for the VM's own start and its dockerd, and for the bridge's connection and the probe: minutes, before the agent sees its prompt.
- None of that depends on the run. The run's data and credentials reach the VM only over the bridge, after the probe. Only the user data named the run: its bridge id, and the egress proxy listener of the run in the container's settings.
- §6.3 of the analysis proposes ready sandboxes: done to the probe, waiting for a run, never reused after one.

## Decision

**Every VM comes from the pool, for no run.**

- The VM's labels are `tinfirm-host` and `tinfirm-vm`, the VM's own id at this host; `tinfirm-run` is gone. The bridge's id is `vm-<id>`. The user data names no run.
- The VM's egress proxy listener is started with the VM (`EgressProxy::start_slot`): its URL is in the container's settings, so that the agent's own dockerd can use it from the start. Until a run binds its rules to it, it refuses every destination, the host-wide ones included.
- A VM is **ready** once its bridge has connected and its probe has passed. It holds no prompt, file or credential.

**A run claims a VM.**

- The run takes a ready VM of its **shape**, the runner image of its runtime and the sandbox's own settings, which its container started with. Else it waits for the next VM of its shape that comes up: the one its claim starts, or one starting already (a cold start). A run that waits for a VM fails with `INFRA` when that VM cannot be created, or none comes up within `--vm-start-timeout`, as before.
- The run binds its rules to the VM's egress listener and masks the VM's ticket in its log. Then its tunnels, its files and `start` go over the bridge, as before.
- A VM that waited longer than a minute is probed again first: a cheap check that the provider's firewall has not changed since.
- A claimed VM never goes back. It is deleted after its run.
- The run's log says whether it took a ready VM or started cold, and how long it waited.

**How many wait.**

- `target = min(max(scheduled, ⌈busy / headroom⌉), maxReady, pool size − busy)`, where the pool size is the host's effective `maxConcurrentRuns` (ADR-0016) and busy its active runs. The ready and starting VMs of the pool's shape aim at the target.
- `scheduled` is Tinfirm's count; it is 0 until the server sends one (§6.4, the next step). `--pool-headroom` keeps one ready VM per that many busy ones.
- The operator's `--pool-max-ready` (4) bounds both, and 0 turns ready VMs off. `--max-concurrent-runs` bounds the pool size, so `run`'s check of the provider's VM limit at start still covers the ready VMs.
- **Refill:** every claim starts a replacement at once. **Scale down:** ready VMs above the target for `--pool-cool-down` (10 minutes) go, the oldest first. **Recycle:** a ready VM older than `--pool-max-age` (6 hours) goes, and the next pass replaces it.
- **None** while the host drains or stops, and after 15 minutes without the server: no run can arrive then.
- **One shape:** the ready VMs run the image of `--pool-runtime` (Claude Code by default). A run of another runtime starts cold, and when the host's slots are full a ready VM goes to make room, so the host never has more VMs than its pool size.
- **Back-off:** after a create fails, the pool starts none of its own for 30 s, doubled up to 10 minutes; a run that waits still gets its own try.

**The pool's passes** run on the host every 5 seconds and when something changes (a claim, a VM that comes up or fails, a `host.config`, a drain). When the host stops, its ready VMs are deleted. The leftover cleanup deletes every VM of this host that its process does not hold, by `tinfirm-vm`; a VM of an older host, which has only `tinfirm-run`, is kept while its run is live.

## Consequences

- A run that takes a ready VM starts with its file transfer and the adapter's start: seconds instead of minutes. A cold start takes as long as before.
- Ready VMs cost while they wait. The operator's ceilings bound that cost whatever Tinfirm asks for, and the analysis's example is about $35 a month for two `s-4vcpu-8gb` Droplets in working hours.
- The provider shows a VM by its own id, not the run's; this host's log names the VM that each run took.
- The pool serves one runtime. One image with every runtime, which `start` would choose from (the analysis §6.3, "Shapes"), would let it serve all; it is not built. (Since ADR-0101 it is: `--pool-runtime any`.)
- A ready VM's bridge stays connected for hours. A VM whose bridge is gone longer than 30 s leaves the pool at the next pass.
- The tests cover the pool end to end with the fake driver: a run that takes a ready VM and its replacement, a run of another runtime, the ceiling and a lower count, an old VM replaced, a VM probed again at its claim, the egress listener refusing until its run binds it, and the back-off. The local end-to-end tests run with a VM per run through the pool as before. Not checked on a real account, as for ADR-0096.

## Alternatives considered

- **A ready VM per runtime.** Not now: four pools of one count each, or a count split between runtimes, for a choice that one image with every runtime makes once. Most companies use one runtime.
- **The run's egress listener in the VM's settings at claim.** Not possible without restarting the container: its dockerd reads the proxy when it starts, and starting it is what a ready VM saves.
- **A ready VM that goes back to the pool after a run.** Rejected (C1): a VM that ran an agent may hold what that run left.
- **Ready VMs only when Tinfirm asks for them.** The pool would wait for step 6; `--pool-headroom` lets an operator use it now, and both share the operator's ceiling.
