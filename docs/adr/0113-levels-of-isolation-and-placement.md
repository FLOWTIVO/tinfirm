# ADR-0113: Levels of isolation: what a host reports, the hardening mark, and where an agent's runs go

- Status: Proposed
- Date: 2026-11-17; split from the first draft of [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (2026-11-14) after a review
- Decided by: not yet; the owner decides. Nothing here is built. What the owner decided on the way is listed under "The owner's decisions so far"; three of those decisions conflict with accepted decisions or with each other, and are open questions 1 to 3
- Sources: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (tiers), [ADR-0025](0025-kata-sandbox-runtime.md), [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md) (protocol 2 and its features), [ADR-0096](0096-a-vm-per-run.md), [ADR-0100](0100-elastic-runners-in-pro.md) (a conversation without a session bundle resumes on its host), [ADR-0109](0109-runner-version-is-the-release-version.md) (compatibility is the protocol version and the features), [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (the separated form, the single machine, the jail, the three paths out of a sandbox); [CONVENTIONS.md](../CONVENTIONS.md) §14.10; the [threat model](../threat-model.md) 5.13, 5.14, 6.1, 9.4, R10, Q21
- Amends, when accepted: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (three levels of isolation beside the tiers; what a container is for; an agent's choice of isolation in place of a tier per environment)
- Changes, when built: `protocol/runner-v2.schema.json` and the `tinfirm-protocol` crate (a feature `host.isolation` with one optional object of `hello`; no new frame, no change to `hello.isolationTiers`, no protocol 3); the runner host (what it reports); the server (what each host reports stored and shown, an agent's choice of isolation, placement); the API and the web app (a host's page, an agent's setting, the warning); the Ansible role (the hardening of a node and of a single machine); the node agent's helper (the checks of the hardening); the threat model (TB5, 6.1, 9.4, R10, Q21)

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
   - A host with a `runsc…` runtime is `container`: gVisor narrows the kernel that an agent reaches, and an escape still lands on the machine with the credentials. Its tier stays `gvisor`.

2. **How a host reports it.** This draft proposes an addition to protocol 2, which is not what the owner decided on 2026-11-14 (open question 1).

   - `hello.isolationTiers` does not change: `container`, `gvisor`, `microvm`, as today. A host of ADR-0112 reports `microvm` there.
   - A host that knows its level lists the feature `host.isolation` in `hello.features` and sends one optional object, `hello.isolation`: `level` (`machine`, `vm-jail` or `container`) and `hardened` (`yes`, `no`, or `none` where the host runs no machine of its own for its VMs: a VM per run at a provider). The field's exact shape is the schema's.
   - A host without the feature is shown with its tier and no level, and is placed as the weakest level its tier can mean: `container` for `container` and `gvisor`, `vm-jail` for `microvm`.
   - So no frame is new, no value is removed, and an older host and a newer server work together, in either order of upgrade (ADR-0109).
   - `run.assign` does not change: the control host is the runner host that the server already knows, and `workspaces[].git` still goes to it and no further.
   - **One level per host.** A runner host runs one executor, so it has one level. A machine that is to offer VMs and containers runs two runner hosts, each registered on its own (ADR-0112, decision 5). This keeps `run.assign` as it is (open question 2).

3. **Reported, not checked.** The server cannot check a level: a company host is registered by an admin and trusted with its own report (5.13), and a personal host runs only its owner's work (5.14).

   - The server stores what each host reports and shows it as reported ("reports `machine`"), with what an escape reaches on a host of that kind. This closes the storing half of Q21.
   - The runner host derives the level from what it can see, not from a setting, wherever it can: `container` from the Docker executor; `vm-jail` when the `node` driver speaks to the agent over the local socket; `machine` when the driver is a provider's, or when every node is reached over TLS at an address that is not the host's own. A node that is a VM on the control host's own hardware still reports `machine`; the documentation says what the word assumes.
   - Where a condition cannot be seen from the host (ADR-0115's "the workers hold nothing else"), the driver enforces what it can, and the rest is the operator's statement in the host's configuration. The host's page marks such a level as stated by the operator.

4. **Placement.**

   - **No requirement, a choice.** Tinfirm demands no isolation of any agent or company: whoever builds an agent cannot change which runner hosts their company has, so a demand they cannot meet would only stop their agent. What a company's hosts offer is what its agents run on.
   - **The hosts that count** for a run are those that may run it at all, as today: by the paying principal and the workspace's git accounts (5.14: a personal host for its owner's own work, company hosts otherwise), by the credential and by the runtime. The levels order what is left.
   - **An agent's isolation is "automatic" by default:** its runs go to the strongest level among the hosts that count and are registered and enabled, whether or not they are connected or have a free slot at that moment. The order is `machine`, `vm-jail`, `container`; within `machine`, a machine per run (`hardened: none`) and a hardened node come before a node that is not hardened; within `vm-jail`, a hardened machine first. A company with one kind of host has nothing to choose, and nothing is asked.
   - **A run waits; it does not go weaker.** When every host of the strongest level is busy or offline, the run waits in the queue as a run without a free host does today. It does not run on a weaker host, for an automatic agent as for a chosen one. Otherwise whoever fills the strong hosts' slots, which a hostile input can do, would choose where the next run goes. An admin who removes or disables the last host of a level changes what "strongest" is; that is an admin's act, and it is audited as a change to a host is today.
   - **Where a company has more than one level,** whoever may edit an agent can choose among them for that agent: only what the company's hosts report is shown. An agent with a chosen level runs on such hosts alone; when none is registered any more, Tinfirm says so before the conversation starts (as `NO_HOST_FOR_CONVERSATION` does today).
   - **A conversation stays where its session is.** Without a session bundle a conversation's next turn runs on the host that holds its session home (ADR-0100). Where that host is of a weaker level than the agent's runs would now get, the turn still runs there, and the conversation's page says so. A new conversation goes to the strongest level. With session bundles (paid, ADR-0114) a turn moves to a stronger host.
   - **No host is refused for its level or for its hardening.** Hardening orders hosts of one level, is shown, and is not among the things chosen for an agent.
   - **A warning where hostile input meets a container.** An agent whose runs can start from outside the company and that would run in a container gets a warning on its page. What "from outside the company" is in the server's terms (which triggers and systems) is to be specified with the build.
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
   - **A cluster's workers** (ADR-0115). A pod per run needs user namespaces on its worker, so such a worker cannot meet the first line, and a host of that form reports `no` (open question 3).

## Consequences

- The server can at last place a run by what an escape reaches, and a company sees it for each host. Q21 is closed: the storing by decision 3, and "let an agent require" by the choice of decision 4, which is a requirement for the agent it is set on.
- 9.4 changes from the operator's advice to a default: an agent's runs go to the strongest level without anyone choosing. An agent on a company with containers alone still runs in a container, with a warning.
- **A level is a report.** A host that lies, or an operator who states a condition that does not hold, is shown as it reports. The page says "reports", and the documentation says who is trusted with it (5.13).
- **Waiting in place of a weaker host costs availability.** A company whose one `machine` host is offline has its automatic agents wait, though a `container` host stands free. The queue's message names the reason, and an admin can choose `container` for an agent, or disable the host that is gone.
- **A conversation that began on a weaker host stays there** in Community (decision 4), until it ends.
- **Panic on an oops is a denial of service between runs.** On a hardened node, a guest that can make the kernel oops ends every run on that node, as often as it likes; on a single machine it takes the runner host down too, and the server where it shares the machine. The trade is taken on purpose: a kernel that has oopsed under a hostile guest is not one to keep running. A node that panics more than once is taken out of use until an operator has looked; how the control host does that is to be specified.
- **Hardening is less than its name on some forms:** `none` for a provider's VM per run, where the provider's hypervisor is the boundary and Tinfirm sees no machine; `no` for a cluster's workers with user namespaces, though they may be hardened in every other line.
- Existing agents get the automatic isolation. On a company with one kind of host that is what they have today.
- A deployment with DigitalOcean's driver reports `machine` once its hosts list the feature. Until then it is placed as `vm-jail` (decision 2), which changes nothing while it is the company's only level.

## Alternatives considered

- **New values in `hello.isolationTiers`** (`machine`, `vm-jail`, `container`; `microvm` removed), with no new field and no protocol 3: the first draft, and the owner's decision of 2026-11-14. It needs the server and every host to take one release together, and a newer server to refuse an older host by something other than the protocol. That contradicts ADR-0109 ("a change that a host of protocol 2 cannot ignore is protocol 3", "hosts and server can be upgraded in either order") and the 1.0.0 changelog. The server validates the values today (`RunnerMessages`), so a new host would be refused by an older server as well. Not proposed here; open question 1.
- **Protocol 3.** It is the honest form of the alternative above, and ADR-0109's rule for it. Not proposed: the level is an addition that a host of protocol 2 can leave out, which is what a feature is for.
- **A level per run in `run.assign`,** so that one host offers VMs and containers. Not proposed: it changes `run.assign` and puts two executors into one host, for a case that two registrations on one machine cover (open question 2).
- **Fall back to a weaker level when the strongest is busy.** Rejected: the level an agent's runs get would then depend on load, which an attacker can cause.
- **Require a level per agent, or per company.** Rejected by the owner: whoever builds an agent cannot change their company's hosts.
- **Pools of nodes per kind of work** as the answer to runs that tamper with each other. Rejected by the owner: a run could still reach another run of its own kind.
- **Hardening as a level of its own,** or as a condition of `machine`. Rejected by the owner: a host without it keeps its level, and a personal host need not have it.

## The owner's decisions so far

Decided by the owner, 2026-11-11 to 2026-11-14.

- The levels are named `machine`, `vm-jail` and `container` (decision 1).
- No isolation is required of an agent or a company. An agent's isolation is automatic, the strongest its company's hosts offer, and can be chosen per agent where the company has more than one kind (decision 4).
- Hardening is a mark of its own beside the level, reported as yes or no; a host without it keeps its level, and a personal host need not have it (decision 5). This draft adds a third value, `none`, for a host without a machine of its own.
- The node's operating system: a hardened standard distribution is required, a node image recommended (decision 5).
- The server and its hosts take the release together, with no reading of the old values of `hello.isolationTiers`. This draft proposes otherwise (decision 2, open question 1).

## To specify and verify during the build

- The shape of `hello.isolation` in the schema, and the feature's name.
- What "an agent whose runs can start from outside the company" is in the server's model (decision 4).
- How a control host with several nodes reports `hardened` when its nodes differ: this draft assumes `yes` only when every node checks out.
- How the control host takes a node that panics again and again out of use (Consequences).
- The host's page and the agent's setting in the web app, and the operator's documentation of the levels.

## Open questions

1. **How the level reaches the server.** The owner decided new values in `hello.isolationTiers` and a release that server and hosts take together. That conflicts with ADR-0109 and the 1.0.0 changelog. Three ways out: the feature and the optional object of decision 2 (proposed); protocol 3; or an amendment of ADR-0109 that allows a lock-step release. The owner decides.
2. **One level per host.** Is a machine with VMs and containers two runner hosts (proposed), or does `run.assign` gain the kind of sandbox?
3. **Hardening where user namespaces are needed.** Does a cluster's worker for pods with user namespaces report `no` (proposed), or does the hardening list get a form of its own for such workers?
4. **Waiting in place of a weaker host for an automatic agent.** The first draft said "the strongest … at that moment", which can mean a weaker host when the strong ones are busy. This draft has the run wait. The owner confirms or changes it.
