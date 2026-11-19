# ADR-0113: Levels of isolation: what a host reports, the hardening mark, and where an agent's runs go

- Status: Accepted
- Date: 2026-11-17; split from the first draft of [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (2026-11-14) after a review
- Decided by: the owner, 2026-11-19: the level travels in `hello.isolationTiers`, as the owner decided on 2026-11-14, in place of this draft's feature and object (decision 2, which amends ADR-0109 for this one change); only the company's hosts set the level (decision 4); the draft's four open questions answered ("Questions the owner answered")
- Built: decisions 1 to 4 ([CONVENTIONS.md](../CONVENTIONS.md) §14.10, §15.4 to §15.7, [operations/runner-isolation.md](../operations/runner-isolation.md)); not decision 5's hardening, so no host derives `hardened: yes`
- Sources: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (tiers), [ADR-0025](0025-kata-sandbox-runtime.md), [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md) (protocol 2 and its features), [ADR-0096](0096-a-vm-per-run.md), [ADR-0100](0100-elastic-runners-in-pro.md) (a conversation without a session bundle resumes on its host), [ADR-0109](0109-runner-version-is-the-release-version.md) (compatibility is the protocol version and the features), [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (the separated form, the single machine, the jail, the three paths out of a sandbox); [CONVENTIONS.md](../CONVENTIONS.md) §14.10; the [threat model](../threat-model.md) 5.13, 5.14, 6.1, 9.4, R10, Q21
- Amends: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (three levels of isolation beside the tiers; what a container is for; an agent's choice of isolation in place of a tier per environment); [ADR-0109](0109-runner-version-is-the-release-version.md), for one change alone: `hello.isolationTiers` changes its values within protocol 2, and a server and its hosts with a VM per run or Kata take the release together (decision 2)
- Changes: `protocol/runner-v2.schema.json` and the `tinfirm-protocol` crate (the values of `hello.isolationTiers`, `microvm` removed; the optional `hello.hardened` and `hello.isolationStated`; no new frame, no feature, no protocol 3); the runner host (what it reports, `run --isolation-level` and `--isolation-hardened`); the server (the refusal of `microvm`, what each host reports stored and shown, an agent's choice of isolation, placement); the API and the web app (a host's page, an agent's setting, the warning); the threat model (TB5, 5.13, 5.14, 6.1, 9.4, R10, Q21). Planned with decision 5: the Ansible role (the hardening of a node and of a single machine) and the node agent's helper (the checks of the hardening)

## Context

- ADR-0112 gives a company three places for a sandbox, which differ in what an escape reaches: a VM whose credentials are on another machine, a VM on the machine that holds them, and a container.
- A host reports its tier in `hello.isolationTiers` (`container`, `gvisor` or `microvm`, ADR-0006, ADR-0025). The server validates the values and stores none of them, so it cannot place a run by them (Q21), and 9.4's advice, hostile input on `microvm` hosts, is the operator's to follow by hand.
- The tier names a technology. It does not say what ADR-0112 is about: a Kata host and a host with a VM per run at a provider both report `microvm`, and an escape reaches everything on the first and nothing on the second.
- Compatibility between a server and a runner host is the protocol version and the features, never a version number, and a change that a host of protocol 2 cannot ignore is protocol 3 (ADR-0109). Hosts and server can be upgraded in either order. A release candidate of 1.0.0 with that promise is published.
- A runner host runs one executor (ADR-0096), and `run.assign` names no kind of sandbox.
- Without a session bundle, a conversation's next turn runs on the host that holds its session home (ADR-0100).

## Decision

**A host reports a level of isolation, named for the strongest wall between an agent's code and the credentials, and whether the machine that runs its VMs is hardened. The server stores and shows both, as what the host reports. An agent's runs go to the strongest level its company's hosts have, or to the level chosen for the agent, and never to a weaker one by themselves.**

1. **Three levels.**

   | Level | The wall | Credentials | Other runs | Who has it |
   |---|---|---|---|---|
   | `machine` | Another machine | On another machine | Kept apart by a jail around each VM, or by a machine per run | A VM per run at a provider (ADR-0096); a control host with sandbox nodes (ADR-0112); the forms of ADR-0115 that meet its conditions |
   | `vm-jail` | A jail around each VM | On the same machine, out of a VMM's user's reach | Kept apart by the jail | A runner host and the node agent on one machine (ADR-0112, decision 5) |
   | `container` | The container and the kernel | On the same machine, behind the kernel alone | Not kept apart | A container of the host's Docker, with runc or gVisor |

   - A level says where the credentials are. It does not say that two flaws are needed: ADR-0112's paths B (KVM) and C (the control host's readers) are one flaw each. The host's page shows the paths for its level, in the words of ADR-0112, decision 1.
   - `machine` holds two things of different strength for the other runs: a machine per run, where a run has no neighbour, and a node, where runs share a kernel. The order of decision 4 knows the difference.
   - A host with a `runsc…` runtime is `container`: gVisor narrows the kernel that an agent reaches, and an escape still lands on the machine with the credentials. It reports `gvisor`, which the server places as `container` (decision 2).

2. **How a host reports it: in `hello.isolationTiers`.** The level takes the place of the tier in the field that already carries it, within protocol 2. The owner decided this on 2026-11-14 and kept it on 2026-11-19, over the feature and the object that this draft proposed ("Alternatives considered"). It amends ADR-0109 for this one change.

   - **The values.** `machine`, `vm-jail` or `container`, one value, since a host has one executor. `gvisor` is kept for a host with a `runsc…` runtime, and is placed as `container`. `microvm` is removed. A `hello` with several values is placed by the weakest.
   - **Two optional fields of `hello`:** `hardened` (`yes`, `no`, or `none` where the host runs no machine of its own for its VMs, as with a VM per run at a provider; absent: `no`) and `isolationStated` (true where the level rests on the operator's statement, decision 3; absent: false). The exact shape is the schema's (`protocol/runner-v2.schema.json`, `$defs.Hello`).
   - **Nothing else changes on the wire:** no feature, no object, no new frame, no protocol 3. `run.assign` does not change: the control host is the runner host that the server already knows, and `workspaces[].git` still goes to it and no further.
   - **One refusal, and only that one.** The server refuses a `hello` that carries `microvm`, alone or beside other values, with `error` `UPGRADE_REQUIRED`, whose message names the server's version, and closes the connection with 1008; the host stops and does not reconnect. `microvm` named a Kata host and a VM per run, two levels apart, so the server cannot place such a host. `container` means the same in both vocabularies, so an older Docker host that reports `container` keeps working.
   - **The other way round,** an older server refuses a host of this release that reports `machine` or `vm-jail`, since it validates the values it knows.
   - **So a server and its hosts with a VM per run or Kata take this release together:** the server first, and those hosts at once after it. Docker hosts follow at any time.
   - **What each host reports** is decision 3's table. A Kata host of this release reports `container`: its VMM runs as root on the machine with the credentials (ADR-0112), so an escape through it reaches them. A host with a VM driver of the operator's own reports the level its operator states, and `container` without a statement.
   - **Why the values, and not the draft's feature and object.** The level is what `isolationTiers` was there to say, and one field says it: a host carries no tier beside its level for good, and the server reads no second vocabulary. The refusal of `microvm` means that no older VM or Kata host is silently placed as a container host: it must be upgraded, and its operator sees why. The price is the one release that the server and its VM hosts take together, before 1.0.0 is released.
   - **This amends ADR-0109 for this one change.** ADR-0109's rule stands for every other change: compatibility is the protocol version and its features, and a change that a host of protocol 2 cannot ignore is protocol 3.
   - **One level per host.** A runner host runs one executor, so it has one level. A machine that is to offer VMs and containers runs two runner hosts, each registered on its own (ADR-0112, decision 5). This keeps `run.assign` as it is (confirmed by the owner, 2026-11-19).

3. **Reported, not checked.** The server cannot check a level: a company host is registered by an admin and trusted with its own report (5.13), and a personal host runs only its owner's work (5.14).

   - The server stores what each host reports and shows it as reported ("reports `machine`"), with what an escape reaches on a host of that kind. This closes the storing half of Q21.
   - The runner host derives the level from what it can see, not from a setting, wherever it can. A node that is a VM on the control host's own hardware still reports `machine`; the documentation says what the word assumes.

     | The host | `isolationTiers` | `hardened` |
     |---|---|---|
     | Docker with runc, or with a `kata…` runtime | `container` | `no` |
     | Docker with a `runsc…` runtime | `gvisor`, placed as `container` | `no` |
     | A VM per run with DigitalOcean's driver | `machine` | `none` |
     | The `node` driver, every node over the listener's local socket | `vm-jail` | `no` until decision 5's checks are built |
     | The `node` driver, every node over TLS at an address that is not the host's own | `machine` | `no` until decision 5's checks are built |
     | The `node` driver with sockets and TLS mixed, or a TLS node at the host's own address | The host does not start | |
     | A VM driver of the operator's own, with `run --isolation-level` | The stated level, with `isolationStated` | What `--isolation-hardened` states |
     | A VM driver of the operator's own, without a statement | `container`, which the host says at its start and in `doctor` | `no` |

   - Where a condition cannot be seen from the host (ADR-0115's "the workers hold nothing else"), the driver enforces what it can, and the rest is the operator's statement in the host's configuration (`run --isolation-level`, `--isolation-hardened`). The host's page marks such a level as stated by the operator. A statement never overrides what the host can see: the host refuses it with Docker, with DigitalOcean's driver and with the `node` driver.

4. **Placement.**

   - **No requirement, a choice.** Tinfirm demands no isolation of any agent or company: whoever builds an agent cannot change which runner hosts their company has, so a demand they cannot meet would only stop their agent. What a company's hosts offer is what its agents run on.
   - **The hosts that count** for a run are those that may run it at all, as today: by the paying principal and the workspace's git accounts (5.14: a personal host for its owner's own work, company hosts otherwise), by the credential and by the runtime. The levels order what is left.
   - **Only the company's hosts set the level.** The strongest level is the strongest that the company hosts that count report. A member's own personal host that counts takes their run too where it reports that level or a stronger one, and never where it reports a weaker one. No run waits for a personal host, so a member's laptop never holds their runs back from the company's hosts. Personal hosts set the level only where no company host may run the run: a run paid by a subscription, which only its owner's personal host runs, or a runtime that no company host serves (the owner, 2026-11-19).
   - **An agent's isolation is "automatic" by default:** its runs go to the strongest level among the hosts that count and are registered, whether or not they are connected or have a free slot at that moment. Hosts have no switch to disable them: a host counts from its registration until it is removed. The order is `machine`, `vm-jail`, `container`; within `machine`, a machine per run (`hardened: none`) and a hardened node come before a node that is not hardened; within `vm-jail`, a hardened machine first. A company with one kind of host has nothing to choose, and nothing is asked.
   - **A run waits; it does not go weaker.** When every host of the strongest level is busy or offline, the run waits in the queue as a run without a free host does today. It does not run on a weaker host, for an automatic agent as for a chosen one (confirmed by the owner, 2026-11-19). Otherwise whoever fills the strong hosts' slots, which a hostile input can do, would choose where the next run goes. An admin who removes the last host of a level changes what "strongest" is; that is an admin's act, and it is audited as a change to a host is today.
   - **Where a company has more than one level,** whoever may edit an agent can choose among them for that agent: only what the company's hosts report is shown. An agent with a chosen level runs on such hosts alone, and on a member's own host that reports that level or a stronger one; when no company host reports it any more, Tinfirm says so before the conversation starts (`NO_HOST_FOR_CONVERSATION`, reason `NO_HOST_OF_LEVEL`).
   - **The session's host is a preference.** Without a session bundle a conversation's next turn runs on the host that holds its session home (ADR-0100). For an automatic agent, where that host is of a weaker level than the agent's runs would now get, the turn still runs there while that host takes it, and the conversation's page says so; when it cannot, the turn goes to a host of the level with a fresh session of the runtime. A new conversation goes to the strongest level. Where a session bundle of the conversation is kept (paid, ADR-0114), the turn goes to a free host of the level that restores it, else stays on its session's host, else takes another host of the level.
   - **A chosen level holds for every turn.** Where a level is chosen for an agent and a conversation's session home is on a host of another level, the next turn does not run there. It starts on a host of the chosen level with a fresh session of the runtime: the conversation and its workspace are the server's and the code host's and stay, the runtime's own session files do not come along, and the conversation's page says that the session was started again and why. With a kept session bundle, a free host of the chosen level that restores it comes first, and the session moves with the turn. Changing an agent's level so has a price for its open conversations, and the agent's setting says it before the change is saved.
   - **No host is refused for its level or for its hardening.** Hardening orders hosts of one level, is shown, and is not among the things chosen for an agent.
   - **A warning where hostile input meets a container.** An agent whose runs can start from outside the company and that would run in a container gets a warning on its page. Its runs can start from outside when it has a trigger, live or paused, on a tracker, a chat channel or a webhook (the owner, 2026-11-19). Schedules do not count, nor do members' own starts.
   - **No pools.** Keeping kinds of runs on different nodes does not answer the question that matters, whether one run can reach another of its own kind; the jail and the hardening do. An operator who still wants two sets of nodes that never share runs registers two control hosts, each with its own nodes.

5. **Hardening, a second mark beside the level.** The level says which wall there is. Hardening says how little of the kernel a process finds behind it: for a jailed VMM's user (ADR-0112's path A) there is then little of the kernel to try. It is a property of the machine that runs the VMs.

   - **The jail is never optional** (ADR-0112, decision 4). Hardening adds to it; a host without it is still what its level says.
   - **What hardening adds is machine-wide,** and so it is for a machine that does little else: a sandbox node, a single company machine. The role sets it up on a standard distribution (Debian, Ubuntu, the Red Hat family), and the agent's helper checks each line before the host reports `yes`.
   - **A personal host on its owner's own workstation** reports `vm-jail` and `no`: turning user namespaces, io_uring and module loading off for the whole machine would break other programs there. Its page says what that means: the jail is there, and the kernel offers an escaped process more to try.
   - A real run with the agent's own Docker works under all of the following together, with no denial and the same times to create and delete a VM (evidence not in the repository; ADR-0112, "Evidence").

   What a hardened machine has, beyond the jail:

   - For every user of the machine: no user namespaces (`user.max_user_namespaces=0`), no unprivileged eBPF, and no io_uring (`kernel.io_uring_disabled=2`). Firecracker's filter allows the three io_uring calls for its asynchronous disk engine, so io_uring is off and the VMs' disks stay on the synchronous engine, which is the default.
   - Mandatory access control on the VMM: on a host with AppArmor, one profile for every VM's VMM, which attaches when the jailer starts Firecracker and allows only what it opens (its disks, `/dev/kvm`, `/dev/net/tun`, its API socket); a profile per VM adds nothing, since every VM's files have the same names in its own jail and the users keep the VMs apart. On a host with SELinux, a category per VM as well.
   - The kernel patched, with the distribution's hardening on, and set to panic on an oops, so that an exploit that fails takes the machine down instead of trying again, and the control host sees it.
   - After boot: no module loading (`kernel.modules_disabled=1`, once the agent has loaded what it needs: KVM, veth, nf_tables with its conntrack and NAT modules, device-mapper's snapshot), no kexec (`kernel.kexec_load_disabled=1`), and lockdown at `integrity`, which can be set at run time. All three hold until a reboot.
   - On a sandbox node, no other service: no Docker, no SSH for anything but the operator's own access path, no setuid program that a VMM's user can run. The agent's helper is the only root process of Tinfirm's, with no socket in any chroot and none in a VM's network namespace. On a single machine the runner host is there too, as a user of its own.
   - **A node image is the recommended form:** an image with a read-only, verified root file system that the operator boots nodes from and replaces to upgrade, with nodes re-imaged on a schedule. Then nothing an attacker changed on a node outlasts a reboot, and every node is the image that was tested. A hardened standard distribution is the requirement; the image is what Tinfirm recommends and publishes later (planned).

   Where the list does not fit:

   - **A machine with Docker for containers.** User-namespace remapping and rootless Docker, which ADR-0006 planned for containers, need user namespaces. A hardened machine has none, so the two do not go together on one machine.
   - **A cluster's workers** (ADR-0115). A pod per run needs user namespaces on its worker, so such a worker cannot meet the first line, and a host of that form reports `no` (the owner, 2026-11-19).

## Consequences

- The server can at last place a run by what an escape reaches, and a company sees it for each host. Q21 is closed: the storing by decision 3, and "let an agent require" by the choice of decision 4, which is a requirement for the agent it is set on.
- 9.4 changes from the operator's advice to a default: an agent's runs go to the strongest level without anyone choosing. An agent on a company with containers alone still runs in a container, with a warning.
- **A level is a report.** A host that lies, or an operator who states a condition that does not hold, is shown as it reports. The page says "reports", and the documentation says who is trusted with it (5.13).
- **A member's own host never sets the level while a company host may run the run.** A stronger personal host is one more place its owner's runs can land; a weaker one takes none of them.
- **Waiting in place of a weaker host costs availability.** A company whose one `machine` host is offline has its automatic agents wait, though a `container` host stands free. The queue's message names the reason, and an admin can choose `container` for an agent, or remove the host that is gone.
- **A conversation that began on a weaker host stays there** in Community, for an automatic agent, while that host takes its turns. For an agent with a chosen level it moves, and loses the runtime's session on the way (decision 4).
- **Panic on an oops is a denial of service between runs.** On a hardened node, a guest that can make the kernel oops ends every run on that node, as often as it likes; on a single machine it takes the runner host down too, and the server where it shares the machine. The trade is taken on purpose: a kernel that has oopsed under a hostile guest is not one to keep running. A node that panics more than once is taken out of use until an operator has looked; how the control host does that is to be specified.
- **Hardening is less than its name on some forms:** `none` for a provider's VM per run, where the provider's hypervisor is the boundary and Tinfirm sees no machine; `no` for a cluster's workers with user namespaces, though they may be hardened in every other line.
- Existing agents get the automatic isolation. On a company with one kind of host that is what they have today.
- **A server and its VM hosts are upgraded together** (decision 2): the new server refuses a host with a VM per run or Kata of an older release until it runs this release, and an older server refuses a host of this release that reports `machine` or `vm-jail`. Docker hosts are upgraded at any time. A deployment with DigitalOcean's driver reports `machine` once its hosts run this release. From then on, in a company that also has container hosts, its automatic agents' new conversations go to the `machine` hosts and wait while they are offline or full.

## Alternatives considered

- **A feature `host.isolation` with an optional object `hello.isolation`** (`level`, `hardened`), and `hello.isolationTiers` unchanged: what this draft proposed, and the branch built first. It would have kept ADR-0109's rule: no value removed, hosts and server upgraded in either order, an older host placed as `container` whatever its tier, since a `microvm` host of an older release may be a Kata host. The server would have refused a `hello` whose object and tier disagree. Rejected by the owner on 2026-11-19: it carries the tier beside the level for good, two fields for one fact, and it places an older VM host as a container host without a word, where the refusal of decision 2 has it upgraded.
- **Protocol 3.** ADR-0109's own form for a change that a host of protocol 2 cannot ignore. Not chosen: a host's `container` means the same before and after, so only the hosts that reported `microvm` need to change, and a protocol version would make every host change.
- **A level per run in `run.assign`,** so that one host offers VMs and containers. Rejected: it changes `run.assign` and puts two executors into one host, for a case that two registrations on one machine cover (the owner, 2026-11-19).
- **Fall back to a weaker level when the strongest is busy.** Rejected: the level an agent's runs get would then depend on load, which an attacker can cause.
- **Personal hosts that set the level like the company's.** What the branch built first. Rejected by the owner on 2026-11-19: a member's own host, stronger than the company's, would make that member's runs wait for their laptop while the company's hosts stand free.
- **Require a level per agent, or per company.** Rejected by the owner: whoever builds an agent cannot change their company's hosts.
- **Pools of nodes per kind of work** as the answer to runs that tamper with each other. Rejected by the owner: a run could still reach another run of its own kind.
- **Hardening as a level of its own,** or as a condition of `machine`. Rejected by the owner: a host without it keeps its level, and a personal host need not have it.

## The owner's decisions so far

Decided by the owner, 2026-11-11 to 2026-11-14, and on 2026-11-19 where the date says so.

- The levels are named `machine`, `vm-jail` and `container` (decision 1).
- No isolation is required of an agent or a company. An agent's isolation is automatic, the strongest its company's hosts offer, and can be chosen per agent where the company has more than one kind (decision 4).
- Hardening is a mark of its own beside the level, reported as yes or no; a host without it keeps its level, and a personal host need not have it (decision 5). A third value, `none`, is for a host without a machine of its own.
- The node's operating system: a hardened standard distribution is required, a node image recommended (decision 5).
- The server and its hosts take the release together, with no reading of the old values of `hello.isolationTiers`. The draft proposed otherwise; the owner kept the decision on 2026-11-19, with the refusal of `microvm` alone, and amended ADR-0109 for this one change (decision 2).
- 2026-11-19: one level per host (decision 2); a Kata host reports `container`, and a host with a driver of the operator's own and no statement too (decisions 2 and 3); only the company's hosts set the level, and no run waits for a personal host (decision 4); a run waits rather than go to a weaker host (decision 4); "from outside the company" is a live or paused trigger on a tracker, a chat channel or a webhook (decision 4); a cluster's worker with user namespaces reports `hardened: no` (decision 5).

## To specify and verify during the build

- Decision 5's hardening: the role's settings, the agent helper's checks of each line, and the node image.
- How a control host with several nodes reports `hardened` when its nodes differ: `yes` only when every node checks out, once the checks exist.
- How the control host takes a node that panics again and again out of use (Consequences).

## Questions the owner answered

1. **How the level reaches the server:** new values in `hello.isolationTiers`, a release that the server and its hosts with a VM per run or Kata take together, and an amendment of ADR-0109 for this one change (decision 2). The owner, 2026-11-19.
2. **One level per host:** a machine with VMs and containers is two runner hosts; `run.assign` does not change (decision 2). The owner, 2026-11-19.
3. **Hardening where user namespaces are needed:** a cluster's worker for pods with user namespaces reports `hardened: no` (decision 5). The owner, 2026-11-19.
4. **Waiting in place of a weaker host for an automatic agent:** the run waits, and never goes to a weaker host (decision 4). The owner, 2026-11-19.
