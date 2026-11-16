# ADR-0112: No credential on a machine that runs a sandbox: one kind of VM, a control host and sandbox nodes

- Status: Proposed
- Date: 2026-11-14
- Decided by: not yet; the owner decides. Nothing here is built.
- Sources: [PLATFORM.md](../PLATFORM.md) §2 (principle 5) and §11.1; [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (tiers), [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0025](0025-kata-sandbox-runtime.md) (Kata, the agent's own Docker), [ADR-0044](0044-sandbox-egress-proxy.md), [ADR-0058](0058-authority-from-the-audience.md), [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md), [ADR-0085](0085-claude-login-on-the-runner-host.md) (a subscription's login file in the sandbox), [ADR-0092](0092-personal-runner-hosts-in-every-company.md), [ADR-0095](0095-sandbox-bridge.md), [ADR-0096](0096-a-vm-per-run.md), [ADR-0097](0097-session-bundles.md) to [ADR-0101](0101-closing-the-elastic-runners-gaps.md), [ADR-0109](0109-runner-version-is-the-release-version.md); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md), contract C1 to C7; the [threat model](../threat-model.md) TB5, TB6, TB7, 5.2, 5.10, 5.13, 5.14, 6.1, 6.2, 6.6, 6.9, 6.20, §6.6 (lifetimes), R10, R12, R22, R24, R26, R27, R28, R29, R34, R44, R55, Q21, Q41; Kata Containers 4.3.1, read at its tag on 2026-11-11 (`src/runtime/virtcontainers/clh.go`, `docs/how-to/how-to-run-rootless-vmm.md`); Firecracker 1.14.2 and its jailer; Jira TIN-42 (short-lived, revocable git tokens)
- Supersedes, when accepted: [ADR-0025](0025-kata-sandbox-runtime.md) (Kata as the `microvm` tier)
- Amends, when accepted: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (the tiers become three levels of isolation: `machine`, `vm-jail`, `container`; what a container is for; an agent's choice of isolation), [ADR-0085](0085-claude-login-on-the-runner-host.md) (a login file reaches a VM over the bridge), [ADR-0096](0096-a-vm-per-run.md) (a second driver, and `port` in `describe`), [ADR-0100](0100-elastic-runners-in-pro.md) (what of elastic runners is in the core: the bridge and the VM executor)
- Changes, when built: a built-in VM driver `node` and a node agent, a binary of its own (`tinfirm-node-agent`), which boots each VM under a jailed VMM; a build of the VM image for a local disk; the driver protocol's `describe` (an optional `port`, which the runner host then dials); the sandbox bridge (a file kept in step both ways); how a sandbox packs its work for the way back; `protocol/runner-v2.schema.json` (the values of `hello.isolationTiers` and one optional field of `hello` for the hardening; no new frame, no protocol 3); the server (what each host offers stored, an agent's choice of isolation, dispatch to the strongest by default); the bridge, the VM executor, the `node` driver and the agent are in the core, not under `ee/`; the Ansible role (a control host, a sandbox node, and both on one machine; Kata removed); the threat model (TB5 to TB7, 6.1, 6.2, 6.9, R10, R12, R22, R26, R27, R28, R34, R44, R55, §6.6, Q21, new rows for the node)

## Context

**What an escape reaches today.** A sandbox escape lands on a machine. What matters is what else that machine holds.

| Tier | The escape | Where it lands | What is there |
|---|---|---|---|
| `container` (runc) | One bug of the host's kernel. The container has uid 1000, no capabilities and `no-new-privileges`, but no user namespace (`sandbox.rs`, 6.1, R10) | Root on the runner host | Everything below |
| `microvm` (Kata 4.3.1 with Cloud Hypervisor) | A bug of KVM, Cloud Hypervisor or virtiofsd (R22). With its own Docker the agent is root in the VM (6.9) | The VMM or virtiofsd, which run as root on the runner host | Everything below |
| A VM per run (ADR-0096; DigitalOcean's driver only) | A bug of the provider's hypervisor | The provider's machine | Nothing of Tinfirm's |

"Everything below" is, on one machine:

- the workspace git tokens of every run in progress, kept for the run's length (R27, R44), in the host's memory and in its take-back records on disk (6.20);
- the host's own token to the server, which has no expiry (5.2, §6.6): whoever holds it is the host, and is assigned further runs with their tokens;
- the one-run model tokens, which work together with the host's token (5.10);
- the Docker socket, which is root on the machine (6.2);
- on a company host, every other run of every principal: its files, its prompt, its sandbox.

**Why the mitigations are not the answer.** Short-lived, revocable git tokens (TIN-42, Q41) shorten how long a stolen token works. They do not take the token, or the host's token that fetches the next one, off the machine the escape lands on.

**Why Kata is not the answer either.** As the Ansible role installs it, Kata's VMM runs as root with every capability and no AppArmor profile, and two virtiofsd processes of each sandbox run as root beside it. Its own seccomp filter filters a root process.

- Its rootless mode works, once one file's mode is changed, and with the agent's own Docker. It moves the VMM alone to a user of its sandbox; the shim and virtiofsd stay root.
- Without virtiofsd a sandbox does not start under Docker, because nothing then carries Docker's root file system into the VM.
- With Firecracker in Cloud Hypervisor's place, Kata runs the jailer as root, so the VMM is root with every capability again.
- So with Kata the last boundary between a hostile guest and everything on the machine is a root process, in every variant. Hardening makes the bug rarer. It does not change what the bug reaches.
- And Kata buys nothing in return: measured on one host with the same agent and task, a jailed Firecracker VM started within a few seconds of Kata's and held the same memory.

**What already has the property.** With a VM per run the runner host is elsewhere: the VM holds no credential (C5), cannot dial out (C3), and the host dials its bridge (ADR-0096). The git proxy, the model proxy and the egress proxy inject and check on the host, and the sandbox reaches them only through its bridge's tunnels (ADR-0095). That is a credential gateway outside the sandbox's machine, already built. It needs a cloud account, and its code is a paid feature (ADR-0100).

**The forces.**

- No Kubernetes requirement: Docker Compose and plain Linux hosts must keep working, with PostgreSQL only. Kubernetes must fit the same decision, with as little privilege in a cluster as the cluster's own features allow.
- The agent needs its own Docker in its sandbox, for Testcontainers and the like. A locked-down container inside the VM in place of the VM boundary is therefore no option.
- Git traffic is large; the server holds the runner connections and the relayed model streams in one replica (R29).
- Community must have a sandbox that is safe to recommend for hostile input.
- A personal host is one machine of one member, often a laptop, and a subscription's login is in the sandbox by design (ADR-0085; R12, R26, R55).
- One way to run a VM is better than two. What is kept must earn its place by more than having been there first.

## Decision

**Tinfirm has two kinds of sandbox: a container for work the company trusts, and a VM for everything else. The VM is one thing everywhere: a Firecracker VM in a jail of its own, with no root process that its guest can reach. Kata goes. A machine that runs such VMs holds no credential where a second machine is at hand: a company runner host becomes a control host, which holds every credential and runs no agent code, and sandbox nodes, which run the VMs and hold nothing. They speak over the sandbox bridge, which the control host dials. This is ADR-0096's shape with a local hypervisor in the provider's place.**

1. **The property.** Tinfirm states it, tests it and reports it per host:

   > No credential of any principal, and no token that authenticates to the server, is readable on a machine where agent code runs, or by any process that an agent's VM can compromise without a second, independent exploit. And what escapes one run's VM reaches no other run without a second, independent exploit: not its files, its memory, its bridge and tunnels, or the work it hands back.

   - It counts: the workspace git tokens; the host's token; the one-run model tokens; the bridge listener's private key and the take-back records (6.20); a VM driver's credential (6.16).
   - It does not count a credential that its owner put into the sandbox of their own runs on their own personal host: a subscription's login file (ADR-0085) and a member's own provider key (R25). They stay accepted risks of their owner (R12, R26, R55), and they exist on personal hosts only (decision 8).
   - The one-time ticket, the pin and the session key of a bridge (ADR-0095), and the per-run secrets in the tunnels' URLs are in the run's VM, and must be. They are the run's own powers while it lasts: its model budget, its platform tools, fetches of its repositories, its egress. The agent in that VM has them anyway. The property's second sentence is what protects them: no other run, and nothing that escaped another run's VM, may reach them.
   - "A second, independent exploit" means, for the credentials, a flaw of the control host's own code or kernel, reached over the bridge: the frame parser, the unpacking of an archive, the fetch of a bundle. For another run on the same machine it means a flaw of that machine's kernel, reached from a VMM's jail.

2. **Two kinds of sandbox.**

   | Kind | What it is | For |
   |---|---|---|
   | A container | A runc container of the host's Docker, as today | A member's own work on their own personal host, and agents whose input the company trusts. Not hostile input |
   | A VM | A Firecracker VM under a jailed VMM (decision 5), with the agent's own Docker in it | Everything else |

   Kata is removed, and the `microvm` tier with it: a VM is no longer a Docker runtime. The agent's own Docker is an ordinary Docker in a VM of its own, so what ADR-0025 did to make it run in a Kata container (every capability, unmasked system paths, a loop disk) goes too. Docker stays on a host for containers alone. The `gvisor` tier that ADR-0006 planned is not built by this decision.

3. **Three levels of isolation,** named for the strongest wall between an agent's code and the credentials. They are what a host reports, what the server shows, and what can be chosen for an agent (decision 7).

   | Level | The wall | Credentials | Other runs | Who has it |
   |---|---|---|---|---|
   | `machine` | Another machine, and a jail around each VM | On another machine | Kept apart | A VM per run at a provider (ADR-0096); a control host with sandbox nodes; the Kubernetes forms of decision 13 |
   | `vm-jail` | A jail around each VM | On the same machine, out of the VMM's reach | Kept apart | A runner host and the node agent on one machine |
   | `container` | The container and the kernel | On the same machine, behind the kernel alone | Not kept apart | A container of the host's Docker |

   - A host reports the levels it offers in `hello.isolationTiers`, as it reports its tiers today. The values become `machine`, `vm-jail` and `container`; `microvm` goes with Kata, and `gvisor` stays reserved. Beside them it reports one more thing, in an optional field of `hello`: whether the machine that runs its VMs is hardened (decision 6). No new frame, no protocol 3. `run.assign` does not change: the control host is the runner host that the server already knows, and `workspaces[].git` still goes to it and no further.
   - The server stores what each host offers (this closes the first half of Q21), shows it, and places runs by it. It cannot check it: a company host is registered by an admin and trusted with its own report (5.13).

4. **The control host** is the runner host with `run --executor vm`, as built.

   - It holds the server connection and the host's token, the git mirrors and clones, the git proxy, the model proxy, the egress proxy, the platform tools' listener, the redaction and the scans, the session homes and the take-back records.
   - It runs no sandbox and needs neither Docker nor KVM (ADR-0096). It can be a small VM, a Compose service or a pod.
   - It opens connections to the nodes. A node never opens one to it: the control host's firewall admits nothing from the nodes' network.
   - It runs apart from the Tinfirm server by default: a machine or a container of its own. It reads what hostile VMs send over the bridge, so a flaw in that code should not land beside the server's database and the model gateway's keys. A small installation may run it as a service of its own on the server's machine; the documentation names the price.
   - It stays a tier-0 asset (R34), and a smaller one than today's runner host: no agent code, no Docker socket, no hypervisor.

5. **The node agent and the jail.** The node agent is the one new program, and a program of its own: `tinfirm-node-agent`, on the machine that runs the VMs. It creates a VM when the control host asks and deletes it when asked. It holds no credential, has no connection to the server, does not know which run a VM is for, and never speaks with a guest.

   - **A small binary of its own.** The agent is the one part of Tinfirm's that needs root on a machine that runs VMs, so it is built to be small and read in one sitting: it carries the driver protocol's server side, the jail, the network and the disks, and none of the runner host's own code (the server connection, the proxies, git, the unpacking of archives). It comes from the same source tree and the same release as `tinfirm-runner-host`, signed with it, and the two check each other's build when they connect, as a VM image's build is checked today (ADR-0101). The rule: a part gets a binary of its own when it runs on another machine with more privilege than the runner host. The `node` driver, the provider drivers, `vm-boot` and the bridge stay in the runner host's binary: they run beside it as its own user, or inside a guest.
   - **Two processes, and nothing that the network reaches runs as root.** Root is needed to start each VMM as a user of its own, to build its jail, its network and its disk, and to set its limits. A user with those rights given one by one would be root in all but name, and one user for every VMM would take away the wall between two runs. So the agent is split, as OpenSSH is:
     - **the listener,** an unprivileged user: it takes the control host's TLS connection, checks its certificate, and reads the requests;
     - **the helper,** root: it does four things and no others (check the node, make a VM, delete a VM, list the VMs) over a local socket that only the listener's user may open, and whose peer it checks again on every connection. A caller gives it a VM's id of one fixed form and the name of a template from the helper's own configuration, and nothing else: no path, command, address, port or size. The helper works out the VM's user, its networks, its devices and its limits itself. It has no network code.
     - **The one free-form thing, a VM's user data,** the listener writes to a file of its own; the helper opens that one name without following a link, checks on the open file that it is a regular file of the listener's, with one name and of a bounded size, and attaches its bytes to the VM as a read-only disk. It never parses them or uses them as a name.
     - **What the split gives.** A flaw in the TLS or the request parsing gives an unprivileged user on a machine that holds nothing: it cannot become root, read a VM's disk or memory, touch a jail, or make the helper act on a value of its choosing. It can still do what the listener may do: make and delete VMs, the running ones included, read the tickets that pass through it, answer the control host falsely, and keep the node busy.
     - **What it does not give.** The helper is confined by the service manager as far as its work allows (ten capabilities of root's forty-one, no network, a closed list of devices, a read-only system but for its work directory), and the listener fully (no capability, no network but its sockets). Yet what the helper keeps, with device-mapper and the right to become any user, is as good as root for whoever controls its code. So the split's safety rests on the helper's four operations and their checks, not on its confinement, and those checks are what a review and a test must go over: a prototype let an id with a trailing newline through until it was tested.
     - The split costs nothing that shows: two to six milliseconds on the 130 that making a VM takes. A helper that is restarted leaves its VMs running.
   - **Who speaks with it.** The control host alone, over TLS with a pinned client certificate, through the driver protocol's five methods (`verify`, `create`, `describe`, `delete`, `list`), as it speaks with DigitalOcean's driver today. `--vm-driver node` is the binary's second built-in driver; it holds the client key in a file that only the driver reads. The agent creates only from templates in its own configuration file.
   - **A run, step by step.** The server assigns a run to the control host, with its git token, as today. The control host asks the agent for a VM and gives it a one-time ticket and the certificate the VM is to trust, nothing more. The agent builds the jail and starts the VMM in it, in 0.1 to 0.2 s. The VM boots and its bridge waits. The control host dials the bridge, runs ADR-0096's probe, and only then sends the workspace and the run. Model requests, git and the web go back through the bridge to the control host's proxies. At the end the control host takes the work back and has the agent delete the VM.
   - **The jail,** one per VM, so that nothing a guest can attack runs as root:
     - Firecracker under its jailer: a user and group of that VM alone, a chroot that holds only that VM's files, its own network, PID and mount namespaces, a cgroup with limits that the agent gives the jailer (it makes none on its own), the VMM's seccomp filter, no capabilities, and a bounding set cut down to the six capabilities the jailer itself needs to build the jail (the jailer leaves it full; an empty one would take a change of the jailer). With no capability held and `no_new_privs`, the VMM can gain none of them;
     - the root disk is one read-only image that every VM shares, with a block-level snapshot of its own per VM (device-mapper), readable and writable by its VMM's user only and deleted with the VM. An overlay inside the guest does not do: the image keeps the runner images in Docker's own directories, and Docker's layers cannot lie on an overlay. A jail's directory is made for one VM and never used again, because its VMM's user owns it;
     - the user data is a small read-only disk of the VM, which `vm-boot` reads as a file, so the agent serves nothing in a VM's network;
     - each VM has an address of its own behind a tap device in its network namespace, and a firewall there, which root alone can change, lets in only the control host to the bridge's port and lets nothing out, neither from the guest nor from the VMM;
     - no shim and no shared file system: the VM has block devices and a network device. The agent's helper sets up the jail and the network as root, and then speaks only with the VMM's API socket. The bridge in the guest speaks only with the control host;
     - what escapes a VM is then one unprivileged user in an empty chroot under a seccomp filter. To reach another run, the agent or root it needs a flaw of the machine's kernel that such a process can use: the second, independent exploit of decision 1.
   - **Where the agent runs** decides the level. On a machine of its own it is a sandbox node, and the host is `machine`. On the runner host's own machine the host is `vm-jail`: the runner host is an ordinary user of its own, and it speaks with the agent's listener over a local socket that only the runner host's user may open. A jailed VMM's user is then refused the runner host's files (0600 in 0700 directories; 5.2, 6.20), its memory, a trace of it and a signal to it, and a VM's network namespace has no route to the machine's loopback. Two things close what is left, the runner host's listeners (the bridge's, the git, model and egress proxies'), which on loopback TCP any local user could connect to: they become sockets in a directory of the runner host's user, since only the runner host's own process ever connects to them; and the agent keeps a firewall table of its own that refuses every packet from a VMM's user, and from its own listener's user but for the listener's port, checks it before each VM, and starts no VM without it.
   - **The driver protocol** stays version 1. `describe` gains an optional `port`, and the runner host dials it: a node publishes each VM's bridge on a port of the node's own address, which the node's firewall forwards to that VM and opens to the control host alone. The VMs' addresses never leave the node, and nothing has to be routed in the operator's network or a cloud's. This is a change of the binary too, which today dials the one `--vm-port` for every VM. Without `port` the host dials `--vm-port` at the address `describe` gives, as today: a node can then give each VM an address of the node's own network and forward it, which works with the binary as it is and costs an address per VM.
   - **One image for cloud and node.** The VM image is ADR-0096's, built for a local disk by the same recipe (`ee/runner/images/vm/setup.sh`): it holds the runner images, Docker for the run's container and for the agent's own Docker, and `vm-boot`. So a node pulls no runner image, holds no registry credential, and needs no Docker of its own. Its guest kernel has what Docker needs in the guest, nf_tables included. The image for a local disk is published, signed, with each release, as the two binaries are, so that every machine runs the image that was tested and an air-gapped site copies one file; the recipe stays for a site that builds its own. It ships the guest kernel that Kata publishes, which every run so far used; Tinfirm builds no kernel of its own.
   - **The contract C1 to C7 holds for a node's VMs,** and ADR-0096's probe runs unchanged before any file enters a VM: a fresh VM per run, nothing out, in only from the control host to the bridge's port, no secret in the user data.
   - **A node reaches nothing of Tinfirm's:** not the server, the model gateway, the code hosts, the control host's ports or the operator's other networks. The operator's network enforces this outside the node, and the role installs a firewall on the node as well, which catches a wrong setup though root on the node could remove it. `verify` has the agent try these from the node and refuses a node that gets through; this finds a wrong setup, not an attacker.
   - **The agent cleans up after itself and says what it sees.** It deletes a VM's jail, snapshot, namespace and cgroup with the VM. What a delete could not remove, and what an earlier agent left, it removes at its own start and whenever the control host next asks: a VM left behind holds its memory until then. It reports a VMM that died to the control host at once, which ends the run as `INFRA`; without that the run ends only when the bridge times out, some 40 s later and under another error. The driver reconnects to an agent that was restarted.
   - **A node holds, by design:** the VM image, the agent's TLS key (which authenticates the node, nothing else), and while a run lasts its files, its prompt and its bridge.
   - Other hypervisors come as further drivers behind the same contract: libvirt, Proxmox, vSphere. Their VMs are confined from each other by that hypervisor, on its terms; an operator can bring one (D9, D10 of the analysis).

6. **Hardening, a second mark beside the level.** The level says which wall there is. Hardening says how little of the kernel an escaped process finds behind it: a jailed process then has no way to root but a flaw of the kernel, and little of the kernel to try. It is a property of the machine that runs the VMs, and a host reports it as yes or no.

   - **The jail is never optional.** Every VM on every host has its own user, its chroot, its namespaces, its limits, no capabilities, `no_new_privs`, the firewall of decision 5 and Firecracker's seccomp filter (its release allows 46 system calls to the VMM's thread, 29 to the API's and 23 to a vCPU's, and traps any other), hardened or not.
   - **What hardening adds is machine-wide,** and so it is for a machine that does little else: a sandbox node, a single company machine, a cluster's workers for runs. The role sets it up on a standard distribution (Debian, Ubuntu, the Red Hat family), and the agent's helper checks each line before the host reports it. In a cluster, where a pod cannot see its worker's settings, the cluster's admin applies them to the workers and states so in the runner host's configuration.
   - **A host without it is still what its level says.** A personal host on its owner's own workstation offers `vm-jail` and reports "not hardened": turning user namespaces, io_uring and module loading off for the whole machine would break other programs there. Its page says what that means: the jail is there, and the kernel offers an escaped process more to try.
   - A real run with the agent's own Docker works under all of the following together, with no denial and the same times to create and delete a VM.

   What a hardened machine has, beyond the jail:

   - For every user of the machine: no user namespaces (`user.max_user_namespaces=0`), no unprivileged eBPF, and no io_uring (`kernel.io_uring_disabled=2`). Firecracker's filter allows the three io_uring calls for its asynchronous disk engine, so io_uring is off and the VMs' disks stay on the synchronous engine, which is the default.
   - Mandatory access control on the VMM: on a host with AppArmor, one profile for every VM's VMM, which attaches when the jailer starts Firecracker and allows only what it opens (its disks, `/dev/kvm`, `/dev/net/tun`, its API socket); a profile per VM adds nothing, since every VM's files have the same names in its own jail and the users keep the VMs apart. On a host with SELinux, a category per VM as well.
   - The kernel patched, with the distribution's hardening on, and set to panic on an oops, so that an exploit that fails takes the machine down instead of trying again, and the control host sees it.
   - After boot: no module loading (`kernel.modules_disabled=1`, once the agent has loaded what it needs: KVM, veth, nf_tables with its conntrack and NAT modules, device-mapper's snapshot), no kexec (`kernel.kexec_load_disabled=1`), and lockdown at `integrity`, which can be set at run time. All three hold until a reboot.
   - On a sandbox node, no other service: no Docker, no SSH for anything but the operator's own access path, no setuid program that a VMM's user can run. The agent's helper is the only root process of Tinfirm's, with no socket in any chroot and none in a VM's network namespace. On a single machine the runner host is there too, as a user of its own, and Docker only if the host also offers `container`.
   - **A node image is the recommended form:** an image with a read-only, verified root file system that the operator boots nodes from and replaces to upgrade, with nodes re-imaged on a schedule. Then nothing an attacker changed outlasts a reboot, and every node is the image that was tested. A hardened standard distribution is the requirement; the image is what Tinfirm recommends and publishes later.

7. **Tiers and placement.**

   - **No requirement, a choice.** Tinfirm demands no isolation of any agent or company: whoever builds an agent cannot change which runner hosts their company has, so a demand they cannot meet would only stop their agent. What a company's hosts offer is what its agents run on.
   - **An agent's isolation is "automatic" by default:** its runs go to the strongest isolation that the company's hosts offer at that moment, in the order `machine`, `vm-jail`, `container`, and among hosts of one level to a hardened one first. A company with one kind of host has nothing to choose, and nothing is asked.
   - **Where a company has more than one kind,** whoever may edit an agent can choose among them for that agent: only what the company's hosts offer is shown. An agent with a chosen isolation runs on such hosts alone; when none is there any more, Tinfirm says so before the conversation starts (as `NO_HOST_FOR_CONVERSATION` does today) and does not fall back to a weaker one by itself.
   - **No host is refused for its level or for its hardening.** The server stores what each host offers and shows it (this closes Q21), with what an escape reaches on a host of that kind. Hardening is shown, and is not among the things chosen for an agent. An agent that reads an inbound source and runs in a container gets a warning.
   - **No pools.** Keeping kinds of runs on different nodes does not answer the question that matters, whether one run can reach another of its own kind; the jail and the hardening do. An operator who still wants two sets of nodes that never share runs registers two control hosts, each with its own nodes.
   - **Ready VMs are how a run starts fast** (ADR-0098, ADR-0099): the pool is the driver's, as for a provider. A VM exists 0.1 to 0.2 s after it is asked for; the wait of about 7 s that follows is Docker starting in the guest and the bridge's probe, and a ready VM has both behind it. The probe's checks that wait on dropped connections run side by side, not one after another.

8. **Personal hosts and subscriptions.**

   - A personal host runs only its owner's work with its owner's own git accounts (5.14). On Linux with KVM it runs the node agent beside the runner host and offers `vm-jail`, hardened or not as its owner's machine allows (decision 6); elsewhere it offers `container` alone, and its page says so.
   - **A subscription's login reaches a VM as a file that the bridge keeps in step.** The member signs in on the host with the provider's own program, as today (ADR-0085), and that program's own login file is the member's. A VM shares no file with its host, so the bridge carries that one file into the run's VM and keeps it in step both ways while the run lasts: what the program in the VM refreshes reaches the host's file at once, and so the next run. This is today's mount by other means, with today's accepted risks (R12, R26, R55) and no new one, and it lets a Codex login reach a VM, which a mount could not (ADR-0096). The provider's program is run as published, and nothing of Tinfirm's reads, rewrites or sends the login.
   - **Tinfirm does not keep the login on the host and add it to requests,** though that would take it out of the sandbox altogether. Anthropic's published terms for products that run its program say that developers "may not collect, store, or intermediate Claude.ai credentials or session tokens" and that sign-in "must complete through Anthropic's own flow", while an end user may sign in "to the unmodified Claude Code binary with their own Claude subscription, including where a platform hosts" it (code.claude.com, "Legal and compliance", read 2026-11-14). Whether a proxy on a member's own machine is within that is the provider's to say (open question 2).

9. **Editions: the secure sandbox is free, elasticity is paid.** This amends ADR-0100.

   | In the core (Community) | Pro and Enterprise (`ELASTIC_RUNNERS`) |
   |---|---|
   | The sandbox bridge | Drivers for a VM per run at a provider (DigitalOcean's today): machines that come with a run and go with it |
   | The VM executor with the `node` driver | Ready VMs and their schedules (ADR-0098, ADR-0099): a run that starts at once |
   | The node agent, on the runner host's machine or on nodes of its own, as many as a company sets up | The Kubernetes forms of decision 13: sandboxes that a cluster places and scales |
   | The levels, the hardening mark, and an agent's choice of isolation | Session bundles (ADR-0097): a turn resumes on any runner host, so that hosts can come and go |

   What keeps a hostile agent in is free, on one machine, on two and on twenty: the same code does it, and a company's own machines are its own. What a company pays for is elasticity: sandboxes that are made and removed with the load, at a provider or in a cluster, a start without the wait, and runner hosts that are interchangeable. Community starts every run cold, on the nodes it has, and a conversation there resumes on the runner host that holds its session, which with the nodes of one control host is every node.

10. **Migration.**

    - Short-lived, revocable git tokens (TIN-42) are built first: they help every host of every edition at once, and still matter afterwards for the control host.
    - Kata is removed in the release that brings the node agent. A Kata host becomes a single-machine host with the agent (`vm-jail`) by the role, which removes Kata, installs Firecracker, the agent and the VM image, and keeps the host's registration; or it is split: a control host elsewhere and the old machine as a node (`machine`).
    - A Kata host that takes the new release without that step still starts: it offers `container` alone, and says so loudly at its start, in `doctor` and on its page, with the command that converts it. It does not fail, and it does not run a VM.
    - The server and its runner hosts take that release together: the values of `hello.isolationTiers` change in it, and a server of the new release does not read the old ones. A host of an older release is refused at its hello with a message that names the release to install.
    - A host that offers `container` alone keeps working. `doctor`, the startup log and the host's page say what an escape reaches there.
    - A deployment with DigitalOcean's driver is `machine` already. It only starts to report it.
    - Existing agents get the automatic isolation, which is what they have today on a company with one kind of host.

11. **Verification.** The property is tested by simulating the escape, not by reviewing the setup. A test gives itself a root shell on a node while runs are in progress with canary credentials: a git token, the host's token, a one-run model token. It must fail to:

    - find a canary anywhere on the node: its disks, its swap, the memory of every process and of every VM, every environment, the user data, every byte the bridge carried to the node (recorded on the control host's side of the test);
    - open a connection to the control host, on any port, or to the server, the model gateway, the code hosts, the internet by address and by name, or a metadata service;
    - get anything but a refusal from the node agent's port without the control host's client key, and get a `start` for a run from a ticket that was used;
    - push through a run's git tunnel, fetch a repository that is not the run's, or use a tunnel after its run ended;
    - make the control host push to any branch but the run's own, write outside its clone, its session home or `/output`, or skip its checks, by what a node sends back as a run's work;
    - act as the host at the server with anything found on the node.

    A second part tests the runs against each other. With two runs on one machine, a shell as the first VM's VMM user, inside its jail, must fail to: read or write the other VM's disk, memory or API socket; trace or signal its VMM; reach its bridge's port or the agent's socket; see its files in any shared directory; or leave the chroot. On a single machine it must also fail to read the runner host's files, trace its process or reach its listeners. The test runs once more with the seccomp filter off, to show that the user, the chroot and the namespaces hold on their own. A third part checks each line of decision 6 from inside a jail.

    The same test asserts what root on a node can do (Consequences): read its runs' files, use their tunnels while they last, and replace the work they hand back. That is the state after two exploits, and the test records it so that nobody claims more.

    Each Kubernetes form of decision 13 gets the same three parts when it is built, with a root shell on a worker in the node's place, and the agent's two processes get a part of their own: a shell as the listener's user must fail to do any of the helper's things directly, and to get anything from the helper but its fixed list with values it accepts.

12. **Where it can run.** A machine that runs VMs needs KVM; the control host runs anywhere. No CPU generation is required.

    | Where | What works |
    |---|---|
    | The company's own servers | Any x86-64 machine with virtualisation on (Intel VT-x, AMD-V) |
    | The company's own hypervisor (VMware, Proxmox, OpenStack) | Nodes as VMs with nested virtualisation on; or, with a driver for that hypervisor, a VM per run and no node |
    | A cloud, a VM per run (ADR-0096) | Wherever a driver exists: DigitalOcean today. Nothing is nested, and the provider's hypervisor is the boundary |
    | A cloud, nodes as instances | Instances that give KVM: bare metal, or instance types with nested virtualisation (below) |
    | A laptop or a machine without KVM | `container` alone |

    Nested virtualisation at the large providers, from each provider's own documentation as read on 2026-11-11:

    | Provider | KVM in an instance |
    |---|---|
    | AWS | On these Intel instance types, turned on per instance: M7i, M7i-flex, M8i, M8id, M8i-flex; C7i, C7i-flex, C8i, C8id, C8i-flex; R7i, R7iz, R8i, R8id, R8i-flex, X8i; I7i, I7ie. KVM and Hyper-V are the supported hypervisors in the instance. No AMD or Graviton type is listed; for those, bare-metal instances only |
    | Google Cloud | On Intel machine types, and on N4D among the AMD ones, with Linux KVM as the only supported hypervisor in the VM. Not on E2, on memory-optimised VMs, on Arm, or on other AMD VMs. Google names a loss of 10 % or more for CPU-bound work, and possibly more for input and output |
    | Azure | On the sizes whose page says "Nested Virtualization: Supported". Read for this ADR: Dsv5, Dsv6 and Esv5 (Intel), Dasv5 and Dasv6 (AMD). On a D4s_v6 a jailed Firecracker VM with Docker in the guest runs as on bare metal. AKS's own sandboxed pods run on Microsoft's hypervisor, and their workers have no `/dev/kvm` |
    | DigitalOcean | None (the analysis, §6): a VM per run only |

    So on a cloud a company is bound to the providers with a driver or with KVM in an instance. The next driver after `node` is one for a large cloud's VM per run; which cloud is open question 1.

13. **Kubernetes.** The runner host is a pod, on general workers, and it is the control host: it holds the token, the proxies and the mirrors. Where the VMs run decides the rest. In the order Tinfirm recommends them:

    | Where the VMs run | Level | What the cluster must allow |
    |---|---|---|
    | Outside the cluster: sandbox nodes that are no part of it, or a provider's VM per run | `machine` | Nothing: the runner pod with `--executor vm` |
    | **A pod per run**, with a user namespace, Firecracker in it as an unprivileged process | `machine`, where the runner pod runs on other workers | Workers with KVM, a KVM device plugin, user namespaces for pods, and a tap device from the network plugin (or one network capability in the pod's own namespace) |
    | KubeVirt, a VM per run from the cluster, on a cluster with SELinux (OKD, OpenShift) | `machine`, where the workers hold nothing else | The cluster's virtualisation operator, and a VM driver for it |
    | The node agent on a worker pool of its own, as a DaemonSet | `machine`, where the pool holds nothing else and its credentials open nothing | Workers with KVM, and a pod that is root on the worker in all but name |

    - **A pod per run is the form for a cluster.** The pod is the jail: with `hostUsers: false` the cluster gives each pod a range of the worker's user IDs of its own, so two runs' VMMs are two unprivileged users there, with no capability even in the bounding set, under the runtime's seccomp profile and Firecracker's own, the runtime's AppArmor profile or SELinux, a read-only root file system and the pod's memory limit. A process with one run's user is refused the other's disk, its memory and a signal to it. Firecracker's jailer cannot run in a user namespace and is not needed there. Real conversations with the agent's own Docker run this way with the real VM image.
      - **The pod passes the `restricted` level of Pod Security** where the cluster's network plugin hands it the VM's tap device (Multus with the `tap` plugin): it then runs as an ordinary user with no capability, no init container, no host path, host network or host PID, and gets `/dev/kvm` and `/dev/net/tun` from a device plugin. Without such a plugin an init container with `NET_ADMIN`, which in a user namespace holds for the pod's own network namespace alone, makes the tap, and the pod passes neither `restricted` nor `baseline`.
      - **On OKD** both pods run under the stock restricted constraint, and each run gets a project of its own, which gives it an SELinux category of its own as a second line behind the user range. The cluster's admin applies, once: a MachineConfig for the workers that run VMs (the runtime gives a device to the pod's user, CRI-O's `device_ownership_from_security_context`, since a user namespace otherwise gets the devices closed; and SELinux lets a container use devices, `container_use_devices`, which holds for the whole worker); the device plugin, which is the one privileged workload; one tap attachment with the container's label; the runner host's namespace; and for its service account the right to ask for projects, with which it can read no secret and list nothing anywhere.
      - **A NetworkPolicy per run, in place before the pod starts,** does what the node's firewall does: in only from the runner pod to the bridge's port, also across workers, and nothing out. Without it a run's pod reaches the API server, the kubelets and the cloud's metadata service. The pod's own worker reaches it in any case. A default that denies everything covers a runs' namespace from its creation, so that no moment is without one.
      - **The disk.** The VM image comes to the worker once, as an image volume or in the pod's image, read-only. Each run's writable part is one of: a clone of the image on a file system that can clone (milliseconds on xfs), a copy where it cannot (seconds to tens of seconds on ext4), or a small empty disk over the read-only image with Docker's own directories on it. No device-mapper there: not in the pod, and not in the guest, whose kernel has no snapshot target.
      - **The scheduler sees the VM:** its memory is the pod's, and a VM that outgrows its limit is stopped alone.
      - **The one-time ticket** travels in a Secret of the run's namespace, which the driver deletes as soon as the VM has read it, some two seconds after the pod starts. Until then whoever may read secrets in that namespace, and root on that worker, can read it; a used ticket opens nothing.
      - **What it costs:** two to three seconds more to a ready sandbox than a VM on a node (9 to 10.5 s against 7 to 8 s); no hardening from the pod: decision 6 is the cluster's to set on its workers, and such a host reports "not hardened" until its admin has; and the device plugin is a privileged pod of its own.
      - **The runner pod** is an ordinary pod that passes `restricted`: one unprivileged container, no capability, a read-only root file system, no device and no host path, a volume for its mirrors and session homes, and the host's login in a Secret of its own namespace, which whoever may read secrets there holds. Its network policy lets nothing in, and out only to the run pods' bridge port, DNS and the outside (the server, the code hosts, the model gateway); to name those hosts takes the cluster's own means. In the runs' namespace it may create and delete pods, secrets and network policies, read a pod and its log, and nothing else: it can neither read nor list secrets, and it has no right in any other namespace or on the workers.
    - **KubeVirt** gives a VM per run that the scheduler accounts for. Its VMM runs in an unprivileged pod as a user without capabilities, under a seccomp filter and a profile. Every VM's VMM is the same user; on a cluster with SELinux each VM has a category of its own, and a process in one VM's context is refused another VM's disk, its memory and a signal to it. A handler runs on every worker as root and reaches every VM there. A VM is ready in tens of seconds with an untuned image.
    - **The node agent as a DaemonSet** runs the jail of decision 5 from a pod, as fast as on a plain machine and with ready VMs as built. It needs eleven capabilities, the devices, the cgroup file system writable and no seccomp or AppArmor profile of the runtime, or `privileged`; on OKD the `privileged` constraint. The scheduler does not see its VMs, so the pool is its alone. It is for a cluster whose admins accept that, where the start of a run matters more than the pod's privileges.
    - **Kata pods are not offered,** a cloud's managed ones included: in every variant the VMM or the jailer runs as root on the worker, and the agent's own Docker needs a privileged container.
    - **A worker that runs VMs holds the kubelet's credential** and the worker's cloud identity. The first reads the secrets and tokens of pods bound to that worker and of no other (NodeRestriction), and a run's pod carries none. The property counts both: the workers for runs are ones where these open nothing, and the runner pod runs elsewhere.

14. **The work's way back.** A sandbox hands its commits back as a git bundle, which the control host fetches into a clone that the agent never touched, with every object checked (ADR-0095). Two things about it, for every run over the bridge:

    - **The fetch does not fail on a large bundle.** It did, every time, when the bundle's pack was larger than git's pack window (1 GiB by default) and the host's clone held the commit's tree only as a loose object: git 2.43 to 2.48 close the bundle's file a second time, which by then is the new pack's, and the next window cannot be mapped ("packfile ... cannot be mapped: Bad file descriptor"). git 2.49 and later do not. The runner host now gives every git command of its own a pack window larger than any bundle it accepts (`core.packedGitWindowSize`, 1 TiB), so that git maps a pack at once, and a test imports a bundle larger than a small window into a fresh clone. This was fixed on its own, ahead of this decision.
    - **The sandbox packs alone.** Most of the time to pack a large commit goes to git's own background repack, which starts after the commit and competes with the bundle for the same objects. The sandbox makes the bundle of a run's new commits with no background repack beside it (`gc.auto=0` for the run's commits): for 1 GiB in a hundred thousand small files that takes the packing from about 100 s to about 60 s. It keeps git's search for deltas and its compression. Turning them off made that case faster still, but a bundle of real source code then grows 11 to 219 times, and its way over the bridge and through the control host's checks takes back what the packing saved.

## Consequences

- An escape from a VM reaches one unprivileged, jailed user. On a sandbox node that is a machine with no credential and no route to one: to reach a token the attacker needs a second flaw, in the control host's bridge code or in the network between the two. To reach another run he needs a second flaw too, in the machine's kernel.
- **On a single machine the kernel is all that stands between the two exploits and the credentials.** `vm-jail` is what one machine can do; `machine` is the level for a company's hostile input, and a company that has both lets an agent's runs go to it.
- **What root on a node still gets** is what the runs on that node may do, while they last: their files and prompts, fetches of their repositories through the git proxy, model requests on their payers' budgets through the model proxy, their platform tools, and the egress allowlist. It holds no token, and nothing when the runs end.
- **Root on a node also decides what those runs hand back.** The sandbox cannot push (the git proxy refuses it), but the control host fetches each run's commits from the sandbox, checks them and pushes them to the run's branch with the run's git account, and takes back the session home and `/output`. Whoever is root on the node can replace all three for every run on it. That takes an escape from a VM and then a flaw of the node's kernel. A node where it happened stays compromised until it is rebuilt; re-imaging on a schedule bounds how long.
- **The kernel is the shared part that is left.** Two ways to root remain, and neither can be closed in software: a flaw of KVM itself, which a guest reaches without leaving its VM first, and a flaw in the small part of the kernel that a jailed VMM may still call. Decision 6 narrows both and turns a failed attempt into a machine that stops. Only a machine per run removes the kernel (a provider's VM per run), or hardware that hides a guest from its host, which the owner does not want.
- **One way to run a VM.** Kata, its shim, virtiofsd and the special container that ran the agent's own Docker are gone, with their rows of the threat model. What is new is the node agent, a second binary to build, sign and publish, and the VM image for a local disk.
- **A machine without KVM runs no VM.** That was so with Kata too.
- **Costs,** as measured with a prototype of the node on one machine and between two:
  - **Start.** A sandbox is ready 7 to 8 s after its VM is asked for, on one machine and on two, and 9 to 10.5 s as a pod; a Kata host had a running agent after about 9 s. The whole of a short run takes the same time within a second or two. Ready VMs take most of the wait away, and they are paid.
  - **Memory.** The same as Kata's: about 1.3 GiB for a sandbox of 6 GiB after a run, about 1 GiB idle, with one VMM in place of a VMM, virtiofsd and a shim. A machine of 11 GiB holds six idle VMs of 4 GiB, or three busy ones. A balloon gives little back. Another runtime in the VM changes little: it started in the same time and held about a tenth more.
  - **Files.** Every workspace travels to the VM and its commits back, where a Kata host used a mount. The bridge is not the limit: it moves 70 to 110 MiB a second in each direction, one core's worth. A small workspace costs nothing that shows, and a large change to a large source tree (15,000 files) comes back in under 20 s. Work of 1 GiB in a hundred thousand small files, the worst case found, comes back in about a minute and a half with the packing of decision 14 (over two minutes without it), against 19 s by a mount.
  - **Failures.** A cancelled run, a killed VMM and a restarted runner host leave nothing behind; a killed node agent leaves its VMs until it is back.
  - **Machines.** For `machine`, a second one at the least, or a container for the control host beside the server.
- **Git bandwidth and the server stay as they are.** Git goes between the control host and the code host; nothing new passes through the server (R29).
- A new listener exists on each node, and a new credential on the control host: the driver's client key. Whoever holds it creates and deletes VMs on its nodes, as a provider's token does (6.16), and nothing more.
- The control host needs no Docker socket, so 6.2 no longer applies to the machine that holds the tokens.
- **Less is paid for.** The bridge and the VM executor leave `ELASTIC_RUNNERS` (ADR-0100); the provider drivers, ready VMs, session bundles and the Kubernetes forms stay in it.
- **The threat model changes:**
  - TB5: the wire is unchanged; `hello` reports the separation.
  - TB6: for a separated host it is a network boundary between two machines, with the node agent and the bridge as its only crossings. New rows for the node agent and for a compromised node.
  - TB7: a VM lets nothing out; the node's own egress is a new row, held by the operator's network.
  - R22 and 6.9 (Kata, and root in its VM) are replaced: an escape reaches a jailed user, on a node without credentials or on a single machine with them behind the kernel.
  - R27, R44, §6.6: the git tokens and the host's token are on the control host only. Their lifetimes do not change: TIN-42 and Q41 stay worth doing, for the control host.
  - R28: closed for every host that runs VMs, where the bridge is the only way.
  - R34: the control host is the tier-0 asset. A node sees the content of its runs and holds no credential: a lower tier, to be named.
  - R10, 6.1, 9.4, Q21: a container is not for hostile input; the server stores what each host offers, sends an agent's runs to the strongest by default, and warns where hostile input meets a container.
  - R12, R26, R55: unchanged in kind; the login file is in the VM by the bridge, not by a mount.
- Until it is built, the only setup with the property is ADR-0096's with a provider. The documentation should say that today.

## Alternatives considered

- **Keep Kata as the `microvm` tier of single-machine hosts,** and the jailed VM for nodes alone. Rejected by the owner: two ways to run a VM, and the kept one is the weaker (a root VMM, a root shim, root virtiofsd), no faster and no lighter. That hosts have it installed is no reason to keep it.
- **Kata with Firecracker in place of a node agent of our own.** It would spare the agent. Rejected: Kata runs Firecracker's jailer as root, so the VMM is root with every capability, and its shim is root as well.
- **A credential gateway as a component of its own:** a proxy outside the sandbox's machine that injects credentials on the wire, with the VM allowed to reach nothing else. The idea is the one taken here. The component is not new for Tinfirm: the runner host's git, model and egress proxies are that gateway, and the bridge makes it the sandbox's only peer. Moving the existing gateway off the sandbox's machine gives the same boundary with Compose and plain hosts.
- **One run per node,** so that no run has a neighbour. Rejected by the owner as the rule: it turns every concurrent run into a machine, and on premises machines are the scarce thing.
- **Confidential VMs** (AMD SEV-SNP, Intel TDX) with an attested bridge key, which would keep a run from root on its own node. Rejected by the owner: it binds companies to recent server hardware and to the clouds that expose it.
- **Pools of nodes per kind of work** as the answer to runs that tamper with each other. Rejected by the owner: a run could still reach another run of its own kind.
- **VMs restored from a snapshot of a booted VM's memory,** in place of ready VMs. Restoring takes milliseconds and a run works on such a VM, but it saves under a second of the eight to a ready sandbox, because the wait is in what starts after the VM exists. It costs a reset of the clock, the address, the entropy and the machine's identity in every restored guest.
- **A single machine for every company host** (`vm-jail` as the whole answer). Rejected as the end state: the machine's kernel is shared with the credentials. It is what a personal host and a small installation have.
- **A git relay in the server,** so that no runner host holds a git token. Rejected again. Clones and fetches of large repositories would pass through the one server replica that already carries the runner connections and the model streams (R29), and the host's token would still be on the sandbox's machine. Once the control host exists, it is that relay, outside the server.
- **Short-lived and revocable git tokens alone** (TIN-42, Q41). A mitigation: less time, the same place. Kept, beside this decision.
- **Nothing beyond a VM per run at a provider for high-risk work.** It has the property today. Rejected as the whole answer: it needs a cloud account and a provider with a driver, which an on-premises or air-gapped company does not have.
- **A locked-down container inside the VM as the boundary,** in place of the VM. Not possible: the agent needs its own Docker.
- **The subscription's login kept on the host,** with a proxy that adds it to requests, as other sandbox products do. Not taken without the provider's word (decision 8).
- **User-namespace remapping or rootless Docker for containers.** Worth doing for that level (ADR-0006 planned it). It turns root after a kernel bug into an unprivileged user after the same bug, on the machine that still holds everything. Not an answer to this problem.
- **Runner protocol 3,** with the credentials split from `run.assign` into frames for a gateway. Not needed: the control host is the runner host, and the split is below the protocol.

## Decided by the owner, 2026-11-11 to 2026-11-14

- Kata is dropped; one kind of VM everywhere (decision 2).
- Editions: the secure sandbox is free, on as many nodes as a company sets up; elasticity is paid: a provider's VMs per run, ready VMs, the Kubernetes forms and session bundles (decision 9).
- No isolation is required of an agent or a company. An agent's isolation is automatic, the strongest its company's hosts offer, and can be chosen per agent where the company has more than one kind (decision 7).
- The control host runs apart from the server by default (decision 4).
- The node's network: the operator's network and a firewall that the role installs (decision 5).
- The node's operating system: a hardened standard distribution is required, a node image recommended (decision 6).
- A subscription's login stays the member's own file, carried into the VM by the bridge; a login kept on the host waits for the provider's word (decision 8).
- On Kubernetes a pod per run is the recommended form in a cluster, the node agent as a DaemonSet the alternative (decision 13).
- TIN-42 is built before the split (decision 10).
- Kata is removed in the release that brings the node agent, with no release of overlap (decision 10).
- The VM image for a local disk is published with each release (decision 5).
- The next driver is a cloud's (decision 12).
- The guest kernel is the one Kata publishes; Tinfirm builds none (decision 5).
- The node agent is a small binary of its own, from the same source tree and release, split into an unprivileged listener and a root helper with a fixed list of things to do; the drivers stay in the runner host's binary (decision 5).
- A node reaches its VMs for the control host by a port per VM or by an address per VM; both stay (decision 5).
- A Kata host that is not converted falls back to `container` alone and says so; the server and its hosts take the release together, with no reading of the old values (decision 10).
- The levels are named `machine`, `vm-jail` and `container` (decision 3).
- Hardening is a mark of its own beside the level, reported as yes or no; a host without it keeps its level, and a personal host need not have it (decision 6).
- Community starts every run cold; ready VMs stay paid (decisions 7 and 9).
- A compromised node must not be able to tamper with its other runs after one exploit, without one run per node, without pools and without special hardware (decisions 1 and 5).

## To verify during the build

These are parts of the work, not of the decision. Each is checked when its code exists.

- **The probe.** Of the 7 to 10 s to a ready sandbox, most is Docker's start in the guest and the probe. A probe that checks side by side should take seconds off; by how much is measured then.
- **The node agent's transport:** TLS with a pinned client certificate between the control host and the agent's listener, where the prototype used another transport, and the helper as a compiled program with a system-call list of its own. The two processes and the helper's four operations ran as a prototype on one machine, under the service manager's confinement.
- **The bridge's file in step** (decision 8), and what two runs at once on one login do to each other's refresh, which today's mount leaves to the provider's program as well.
- **Ready VMs with the `node` driver,** driven by the server's schedule.
- **The pack window** (decision 14): the setting stays while a git older than 2.49 can be the host's.
- **More than a handful of VMs at once** on one machine, on hardware larger than the tests had.
- **A persistent volume for the runner pod,** so that its mirrors and session homes outlast a restart, and a runner pod restarted in the middle of a run.

## Accepted unknowns

Not tested, and not needed for this decision. Each is named so that nobody takes it as shown.

- **A cluster whose network plugin allows no second one** beside it for the tap device. The pod then uses the init container with one capability, which is tested.
- **KubeVirt after an escape into its launcher's pod, and with hot-plugged or persistent volumes.** KubeVirt is a later driver, and its own decision covers that.
- **OpenShift itself.** The cluster runs were on OKD and on a managed cluster.
- **What a worker's cloud identity may do** in a given cluster. Decision 13 asks that it opens nothing; whether it does is each cluster's own setup.
- **ARM.** Every run was on x86-64. Firecracker runs on 64-bit ARM, but none of the three large clouds gives KVM in an ARM instance, so a test takes bare-metal ARM hardware.
- **An empty capability bounding set for the VMM,** which takes a change of Firecracker's jailer.
- **Why a guest started slower on the one OKD node that was tried** (13 to 16 s to a ready sandbox, against 8 to 10 s elsewhere).
- **A runtime's second turn in a VM,** and the runtimes that were not run.

## Open questions

Neither holds the decision back.

1. **The next driver.** A cloud's VM per run comes next. Which cloud is decided when a company needs one; KubeVirt and the on-premises hypervisors (libvirt, Proxmox, vSphere) follow on request.
2. **A subscription's login kept on the host.** Does the provider allow a proxy on the member's own machine to hold the login and add it to requests? If so, it replaces the file in the VM and ends R12, R26 and R55 there. The same question goes to the provider of a Codex login, whose terms were not read.
