# ADR-0112: No credential on a machine that runs a sandbox: a control host, sandbox nodes and one jailed VM of Tinfirm's own

- Status: Proposed
- Date: 2026-11-14; revised 2026-11-17 after a review: the property is restated with the paths it does not close, and the draft is split. The levels a host reports, the hardening mark and placement are [ADR-0113](0113-levels-of-isolation-and-placement.md); the editions are [ADR-0114](0114-the-secure-sandbox-is-in-the-core.md); Kubernetes is [ADR-0115](0115-sandbox-vms-in-a-kubernetes-cluster.md)
- Decided by: not yet; the owner decides. Nothing here is built. What the owner decided on the way is listed under "The owner's decisions so far"
- Sources: [PLATFORM.md](../PLATFORM.md) §2 (principle 5) and §11.1; [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (tiers), [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0025](0025-kata-sandbox-runtime.md) (Kata, the agent's own Docker), [ADR-0044](0044-sandbox-egress-proxy.md), [ADR-0058](0058-authority-from-the-audience.md), [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md), [ADR-0085](0085-claude-login-on-the-runner-host.md) (a subscription's login file in the sandbox), [ADR-0092](0092-personal-runner-hosts-in-every-company.md), [ADR-0095](0095-sandbox-bridge.md), [ADR-0096](0096-a-vm-per-run.md), [ADR-0098](0098-ready-vms.md), [ADR-0101](0101-closing-the-elastic-runners-gaps.md); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md), contract C1 to C7; the [threat model](../threat-model.md) TB5, TB6, TB7, 5.2, 5.10, 5.13, 5.14, 6.1, 6.2, 6.6, 6.9, 6.15, 6.16, 6.17, 6.20, §6.6 (lifetimes), R10, R12, R22, R24, R25, R26, R27, R28, R29, R34, R36, R44, R55, R58, Q41; Kata Containers 4.3.1, read at its tag on 2026-11-11 (`src/runtime/virtcontainers/clh.go`, `docs/how-to/how-to-run-rootless-vmm.md`); Firecracker 1.14.2 and its jailer; [KVM in a cloud's instances](../reviews/2026-11-11-kvm-in-cloud-instances.md); Jira TIN-42 (short-lived, revocable git tokens)
- Supersedes, when accepted: [ADR-0025](0025-kata-sandbox-runtime.md) (Kata as the `microvm` tier). Decision 2 names what of ADR-0025 stays in force
- Amends, when accepted: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (the `microvm` tier is a jailed Firecracker VM or a VM per run, no longer a Docker runtime; Apple's `container` is not planned by this decision), [ADR-0085](0085-claude-login-on-the-runner-host.md) (a login file reaches a VM over the bridge, which carries its bytes), [ADR-0095](0095-sandbox-bridge.md) (a file kept in step both ways; on a single machine the host's listeners are sockets, not loopback ports), [ADR-0096](0096-a-vm-per-run.md) (a second built-in driver, and `port` in `describe`)
- Changes, when built: a built-in VM driver `node` and a node agent, a binary of its own (`tinfirm-node-agent`), which boots each VM under a jailed VMM; a build of the VM image for a local disk; the driver protocol's `describe` (an optional `port`, which the runner host then dials and keeps in the run's take-back record); the sandbox bridge (a file kept in step both ways); `vm-boot` (the user data from a disk); the runner host's listeners on a single machine; the Ansible role (a control host, a sandbox node, and both on one machine; Kata removed); `run --sandbox-docker` (a VM per run only); the threat model (TB6, TB7, 6.1, 6.2, 6.9, 6.17, R10, R12, R22, R26, R27, R28, R34, R44, R55, R58, §6.6, new rows for the node, the node agent and the paths of decision 1). The runner protocol between the server and the host does not change: a host of this decision reports `microvm`, as a host with a VM per run does today, until ADR-0113 is built

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

- No Kubernetes requirement: Docker Compose and plain Linux hosts must keep working, with PostgreSQL only.
- The agent needs its own Docker in its sandbox, for Testcontainers and the like. A locked-down container inside the VM in place of the VM boundary is therefore no option.
- Git traffic is large; the server holds the runner connections and the relayed model streams in one replica (R29).
- Community must have a sandbox that is safe to recommend for hostile input (ADR-0114).
- A personal host is one machine of one member, often a laptop, and a subscription's login is in the sandbox by design (ADR-0085; R12, R26, R55).
- One way to run a VM is better than two. What is kept must earn its place by more than having been there first.

**The evidence is not in the repository yet.** The Kata findings, the timings, the memory figures and every "works" below come from experiments and a prototype of the node on one machine and between two. "Evidence" at the end lists each claim that rests on them. A report of the method and the numbers goes to `docs/reviews/` before this ADR is accepted (planned).

## Decision

**Tinfirm has two kinds of sandbox: a container for work the company trusts, and a VM for everything else. The VM that Tinfirm runs itself is one thing: a Firecracker VM in a jail of its own, with no root process that its guest can reach in user space. Kata goes. A machine that runs such VMs holds no credential where a second machine is at hand: a company runner host becomes a control host, which holds every credential and runs no agent code, and sandbox nodes, which run the VMs and hold nothing. They speak over the sandbox bridge, which the control host dials. This is ADR-0096's shape with a local hypervisor in the provider's place. A VM per run at a provider (ADR-0096) stays as it is.**

Two forms have names in this ADR: **separated** (a control host and sandbox nodes on other machines) and **single machine** (the runner host and the node agent on one). ADR-0113 names what each reports.

1. **The property, and the three paths out.** Tinfirm states the property, tests it and reports it per host:

   > A machine that runs agent code in a VM holds no credential of any principal and no token that authenticates to the server, where a second machine is at hand; on a single machine no jailed VMM's user can read one. An agent that leaves its VM through the VMM is one unprivileged, jailed user: it reads no credential and reaches no other run, not its files, its memory, its bridge and tunnels, or the work it hands back.

   The property is about where the credentials are and what a VMM's user can do. It is not a count of exploits to a credential: three paths lead out of a sandbox, each with one flaw, and the property closes the first only.

   | Path | The flaw | Needs an escape first | Where it lands | Separated | Single machine |
   |---|---|---|---|---|---|
   | A. Through the VMM | Firecracker's device model or API | No | The VMM's jailed user (decision 4) | No credential, no other run. To go on takes a second flaw, of the node's kernel from inside the jail, and then gives root on the node (Consequences) | No credential readable, no other run. The second flaw gives every credential |
   | B. Through KVM | The kernel's own part of the hypervisor, which a guest reaches without leaving its VM | No | Root on the machine that runs the VM | Every run on that node and what it hands back; no credential | Every credential |
   | C. Through the bridge | The control host's code that reads what a sandbox sends: the frame parser, the unpacking of an archive, the fetch of a bundle with git, the git, model and egress proxies, the platform tools' listener | No: the bridge runs as the agent's user in the sandbox (ADR-0095), so the agent speaks to this code directly | The control host's user | Every credential of every run of its nodes, and the host's token | The same |

   - **Path A** is what this decision closes: a flaw of the VMM no longer gives root on a machine with credentials.
   - **Path B** stays. A jail around a process does nothing against a flaw in the kernel under it. On a sandbox node it reaches no credential; on a single machine it reaches all. Only a machine per run (a provider's VM per run) removes it for the other runs too.
   - **Path C** is today's, unchanged in kind: the same code reads the same input on a runner host with the bridge today. This decision does not narrow it, and it concentrates what the path reaches, since one control host holds the credentials of all its nodes' runs. Whether the control host does this work in a process per run that holds no credential is open question 1.
   - **What counts as a credential:** the workspace git tokens; the host's token; the one-run model tokens; the bridge listener's private key and the take-back records (6.20); a VM driver's credential (6.16).
   - **What does not:** a credential that its owner put into the sandbox of their own runs on their own personal host: a subscription's login file (ADR-0085) and a member's own provider key (R25). They stay accepted risks of their owner (R12, R26, R55), and they exist on personal hosts only (decision 6).
   - **The run's own powers are in its VM, and must be:** the one-time ticket, the pin and the session key of a bridge (ADR-0095), and the per-run secrets in the tunnels' URLs. They are its model budget, its platform tools, fetches of its repositories and its egress, while the run lasts. The agent in that VM has them anyway. The property's second sentence protects them from every other run.

2. **Two kinds of sandbox.**

   | Kind | What it is | For |
   |---|---|---|
   | A container | A runc container of the host's Docker, as today | A member's own work on their own personal host, and agents whose input the company trusts. Not hostile input |
   | A VM | A Firecracker VM under a jailed VMM (decision 4), or a VM per run at a provider (ADR-0096), with the agent's own Docker in it | Everything else |

   Kata is removed: a VM is no longer a Docker runtime, and Docker stays on a host for containers alone. What of ADR-0025 stays in force, since a VM per run depends on it:

   | ADR-0025 decided | After this decision |
   |---|---|
   | `run --sandbox-runtime <name>` sets the Docker runtime | Stays for Docker hosts. A `runsc…` runtime still reports `gvisor`. A `kata…` name no longer makes a VM: such a host starts with runc containers alone and says so (decision 7) |
   | `run --sandbox-docker`, only with a `kata…` runtime | Only with `--executor vm`, where the VM is the boundary (ADR-0096). On a Docker host the flag is ignored with the same warning |
   | The sandbox container of the agent's own Docker: root, every capability, never `--privileged`, the loop devices | The Docker form goes with Kata. In a VM, `vm-boot` keeps running the run's container as root and privileged, as ADR-0096 decided |
   | The entrypoint's steps for the agent's own dockerd (cgroups, the loop disk at `/var/lib/docker`, `daemon.json`, the wait) | Stay: they run in the run's container in the VM |
   | `run --sandbox-env`, `images build --build-arg` (mirrors) | Stay |
   | The Ansible role installs Kata | Goes (decision 7) |

3. **The control host** is the runner host with `run --executor vm`, as built.

   - It holds the server connection and the host's token, the git mirrors and clones, the git proxy, the model proxy, the egress proxy, the platform tools' listener, the redaction and the scans, the session homes and the take-back records.
   - It runs no sandbox and needs neither Docker nor KVM (ADR-0096). It can be a small VM, a Compose service or a pod.
   - It opens connections to the nodes. A node never opens one to it: the control host's firewall admits nothing from the nodes' network.
   - It runs apart from the Tinfirm server by default: a machine or a container of its own. It reads what hostile VMs send over the bridge (path C), so a flaw in that code should not land beside the server's database and the model gateway's keys. A small installation may run it as a service of its own on the server's machine; the documentation names the price.
   - It stays a tier-0 asset (R34). It no longer runs agent code, a Docker socket or a hypervisor, and it holds the credentials of every run on all its nodes.

4. **The node agent and the jail.** The node agent is the one new program, and a program of its own: `tinfirm-node-agent`, on the machine that runs the VMs. It creates a VM when the control host asks and deletes it when asked. It holds no credential, has no connection to the server, does not know which run a VM is for, and never speaks with a guest.

   - **A small binary of its own.** The agent is the one part of Tinfirm's that needs root on a machine that runs VMs, so it is built to be small and read in one sitting: it carries the driver protocol's server side, the jail, the network and the disks, and none of the runner host's own code (the server connection, the proxies, git, the unpacking of archives). It comes from the same source tree and the same release as `tinfirm-runner-host`, signed with it, and the two check each other's build when they connect, as a VM image's build is checked today (ADR-0101). The rule: a part gets a binary of its own when it runs on another machine with more privilege than the runner host. The `node` driver, the provider drivers, `vm-boot` and the bridge stay in the runner host's binary: they run beside it as its own user, or inside a guest.
   - **Two processes, and nothing that the network reaches runs as root.** Root is needed to start each VMM as a user of its own, to build its jail, its network and its disk, and to set its limits. A user with those rights given one by one would be root in all but name, and one user for every VMM would take away the wall between two runs. So the agent is split, as OpenSSH is:
     - **the listener,** an unprivileged user: it takes the control host's TLS connection, checks its certificate, and reads the requests;
     - **the helper,** root: it does four things and no others (check the node, make a VM, delete a VM, list the VMs) over a local socket that only the listener's user may open, and whose peer it checks again on every connection. A caller gives it a VM's id of one fixed form and the name of a template from the helper's own configuration, and nothing else: no path, command, address, port or size. The helper works out the VM's user, its networks, its devices and its limits itself. It has no network code.
     - **The one free-form thing, a VM's user data,** the listener writes to a file of its own; the helper opens that one name without following a link, checks on the open file that it is a regular file of the listener's, with one name and of a bounded size, and attaches its bytes to the VM as a read-only disk. It never parses them or uses them as a name.
     - **What the split gives.** A flaw in the TLS or the request parsing gives an unprivileged user on a machine that holds nothing: it cannot become root, read a VM's disk or memory, touch a jail, or make the helper act on a value of its choosing.
     - **What a compromised listener still has.** It makes and deletes VMs, the running ones included, reads the tickets that pass through it, answers the control host falsely, and keeps the node busy. With a ticket and a false `describe` it can pose as the sandbox of every new run on that node: the control host dials the address it is given and accepts whoever presents the ticket. Such a listener then gets those runs' workspaces, prompts, session homes and tunnels, and decides what they hand back, which is what root on the node gets (Consequences), for new runs and without root. Only the control host reaches the listener (below), so this takes a compromised control host, or a node that reaches another node's agent, which the network must refuse.
     - **What it does not give.** The helper is confined by the service manager as far as its work allows (ten capabilities of root's forty-one, no network, a closed list of devices, a read-only system but for its work directory), and the listener fully (no capability, no network but its sockets). Yet what the helper keeps, with device-mapper and the right to become any user, is as good as root for whoever controls its code. So the split's safety rests on the helper's four operations and their checks, not on its confinement, and those checks are what a review and a test must go over: a prototype let an id with a trailing newline through until it was tested.
     - The split costs nothing that shows: two to six milliseconds on the 130 that making a VM takes. A helper that is restarted leaves its VMs running.
   - **Who speaks with it.** The control host alone, over TLS with a pinned client certificate. The `node` driver is the binary's second built-in driver (`--vm-driver node`): the runner host speaks the driver protocol with it on stdin and stdout, as with DigitalOcean's (ADR-0096: `hello`, `verify`, `create`, `describe`, `delete`, `list`), and the driver carries `verify`, `create`, `describe`, `delete` and `list` to the agent's listener. It holds the client key in a file that only the driver reads. The agent creates only from templates in its own configuration file.
   - **A run, step by step.** The server assigns a run to the control host, with its git token, as today. The control host asks the agent for a VM and gives it a one-time ticket and the certificate the VM is to trust, nothing more. The agent builds the jail and starts the VMM in it, in 0.1 to 0.2 s. The VM boots and its bridge waits. The control host dials the bridge, runs ADR-0096's probe, and only then sends the workspace and the run. Model requests, git and the web go back through the bridge to the control host's proxies. At the end the control host takes the work back and has the agent delete the VM.
   - **The jail,** one per VM, so that nothing a guest can attack in user space runs as root:
     - Firecracker under its jailer: a user and group of that VM alone, a chroot that holds only that VM's files, its own network, PID and mount namespaces, a cgroup with limits that the agent gives the jailer (it makes none on its own), the VMM's seccomp filter (its release allows 46 system calls to the VMM's thread, 29 to the API's and 23 to a vCPU's, and traps any other), no capabilities, and a bounding set cut down to the six capabilities the jailer itself needs to build the jail (the jailer leaves it full; an empty one would take a change of the jailer). With no capability held and `no_new_privs`, the VMM can gain none of them;
     - the root disk is one read-only image that every VM shares, with a block-level snapshot of its own per VM (device-mapper), readable and writable by its VMM's user only and deleted with the VM. An overlay inside the guest does not do: the image keeps the runner images in Docker's own directories, and Docker's layers cannot lie on an overlay. A jail's directory is made for one VM and never used again, because its VMM's user owns it;
     - the user data is a small read-only disk of the VM, which `vm-boot` reads as a file, so the agent serves nothing in a VM's network;
     - each VM has an address of its own behind a tap device in its network namespace, and a firewall there, which root alone can change, lets in only the control host to the bridge's port and lets nothing out, neither from the guest nor from the VMM;
     - no shim and no shared file system: the VM has block devices and a network device. The agent's helper sets up the jail and the network as root, and then speaks only with the VMM's API socket. The bridge in the guest speaks only with the control host;
     - what leaves a VM through the VMM is then one unprivileged user in an empty chroot under a seccomp filter (path A). To reach another run, the agent or root it needs a flaw of the machine's kernel that such a process can use;
     - the jail is never optional: every VM on every host has it. What a machine can add around it is ADR-0113's hardening.
   - **The driver protocol** stays version 1. `describe` gains an optional `port`, and the runner host dials it: a node publishes each VM's bridge on a port of the node's own address, which the node's firewall forwards to that VM and opens to the control host alone. The VMs' addresses never leave the node, and nothing has to be routed in the operator's network or a cloud's. This is a change of the binary too, which today dials the one `--vm-port` for every VM, and of the take-back record, which must keep the port for a restarted host (ADR-0101). Without `port` the host dials `--vm-port` at the address `describe` gives, as today: a node can then give each VM an address of the node's own network and forward it, which works with the binary as it is and costs an address per VM.
   - **One image for cloud and node.** The VM image is ADR-0096's in content: the runner images, Docker for the run's container, and `vm-boot`. So a node pulls no runner image, holds no registry credential, and needs no Docker of its own. Its build is new: the recipe today (`ee/runner/images/vm/setup.sh`) is a script that Packer runs over SSH in a provider's VM, with cloud-init for the network. A build for a local disk needs a builder that runs without a provider, the guest's network set without cloud-init, and `vm-boot` reading the user data from a disk; the contents stay the script's. Its guest kernel has what Docker needs in the guest, nf_tables included. The image for a local disk is published, signed, with each release, as the two binaries are, so that every machine runs the image that was tested and an air-gapped site copies one file; the recipe stays for a site that builds its own. It ships the guest kernel that Kata publishes, which every run so far used; Tinfirm builds no kernel of its own, and so takes that kernel's fixes from the Kata project's releases.
   - **The contract C1 to C7 holds for a node's VMs,** and ADR-0096's probe runs unchanged before any file enters a VM: a fresh VM per run, nothing out, in only from the control host to the bridge's port, no secret in the user data. The probe's checks run at once already, each with 3 s (ADR-0096, `probe.rs`), so behind a firewall that drops packets the probe takes about 3 s; that is its floor.
   - **A node reaches nothing of Tinfirm's:** not the server, the model gateway, the code hosts, the control host's ports, another node's agent or bridge ports, or the operator's other networks. A node that is a cloud's instance has no identity there (C2 for the node itself): no instance role, and a metadata service that hands out no credential. The operator's network enforces this outside the node, and the role installs a firewall on the node as well, which catches a wrong setup though root on the node could remove it. `verify` has the agent try these from the node and refuses a node that gets through; this finds a wrong setup, not an attacker.
   - **The agent cleans up after itself and says what it sees.** It deletes a VM's jail, snapshot, namespace and cgroup with the VM. What a delete could not remove, and what an earlier agent left, it removes at its own start and whenever the control host next asks: a VM left behind holds its memory until then. It reports a VMM that died to the control host at once, which ends the run as `INFRA`; without that the run ends only when the bridge times out, some 40 s later and under another error. The driver reconnects to an agent that was restarted.
   - **A node holds, by design:** the VM image, the agent's TLS key (which authenticates the node, nothing else), and while a run lasts its files, its prompt and its bridge.
   - Other hypervisors come as further drivers behind the same contract: libvirt, Proxmox, vSphere. Their VMs are confined from each other by that hypervisor, on its terms; an operator can bring one (D9, D10 of the analysis).

5. **A single machine.** The node agent can run on the runner host's own machine. The credentials are then on the machine that runs the VMs, and only the jail and the kernel stand between.

   - The runner host is an ordinary user of its own, and it speaks with the agent's listener over a local socket that only the runner host's user may open.
   - A jailed VMM's user is refused the runner host's files (0600 in 0700 directories; 5.2, 6.20), its memory, a trace of it and a signal to it, and a VM's network namespace has no route to the machine's loopback.
   - Two things close what is left. The runner host's listeners (the bridge's, the git, model and egress proxies'), which on loopback TCP any local user could connect to, become sockets in a directory of the runner host's user: with a VM per run only the runner host's own process connects to them (ADR-0095). And the agent keeps a firewall table of its own that refuses every packet from a VMM's user, and from its own listener's user but for the listener's port, checks it before each VM, and starts no VM without it.
   - Paths B and C of decision 1 reach every credential here with one flaw. Where the server runs on the same machine too, they reach the database and the vault's keys with it.
   - A runner host runs one executor, as today (ADR-0096). A machine that is to run VMs and containers runs two runner hosts, each registered on its own.

6. **Personal hosts and subscriptions.**

   - A personal host runs only its owner's work with its owner's own git accounts (5.14). On Linux with KVM it can run the node agent beside the runner host, as a single machine; elsewhere it runs containers alone, and its page says so. A Mac is "elsewhere": Apple's `container`, which ADR-0006 planned for the `microvm` tier, is not planned by this decision.
   - The node agent's helper is root, so a personal host with VMs is installed by someone who is root on that machine. The installer for it is planned.
   - **A subscription's login reaches a VM as a file that the bridge keeps in step.** The member signs in on the host with the provider's own program, as today (ADR-0085), and that program's own login file is the member's. A VM shares no file with its host, so the bridge carries that one file into the run's VM and keeps it in step both ways while the run lasts: what the program in the VM refreshes reaches the host's file at once, and so the next run. It lets a Codex login reach a VM, which a mount could not (ADR-0096). The provider's program is run as published.
   - **This is not the mount by other means, and it amends ADR-0085 and R55.** With a mount the kernel shared one file, and the host "neither reads nor writes the tokens" (R55). Now the runner host's process reads the file's bytes, sends them inside the bridge's TLS, and writes back what a sandbox returns. It still does not parse the login, keep a copy beyond the run, or add it to a request. Three things are new, and the threat model names them when this is built:
     - a write path from a sandbox into a file of the host's: the host writes that one file whole, of a bounded size, without following a link, and nothing else;
     - two runs at once on one login each hold a copy. Where the provider rotates a refresh token, one run's refresh can make the other's copy stale before it arrives. What the provider's program then does is to be tested before the build depends on it;
     - whether carrying the file between the member's own host and the member's own VM is within the provider's terms is the provider's to say, as it is for the proxy below (open question 3).
   - The accepted risks of the login in the sandbox stay as they are in kind (R12, R26, R55): the agent can read it, break it or replace it.
   - **Tinfirm does not keep the login on the host and add it to requests,** though that would take it out of the sandbox altogether. Anthropic's published terms for products that run its program say that developers "may not collect, store, or intermediate Claude.ai credentials or session tokens" and that sign-in "must complete through Anthropic's own flow", while an end user may sign in "to the unmodified Claude Code binary with their own Claude subscription, including where a platform hosts" it (code.claude.com, "Legal and compliance", read 2026-11-14).

7. **Migration.**

   - Short-lived, revocable git tokens (TIN-42) are built first: they help every host of every edition at once, and still matter afterwards for the control host.
   - Kata is removed in the release that brings the node agent. A Kata host becomes a single-machine host with the agent by the role, which removes Kata, installs Firecracker, the agent and the VM image, and keeps the host's registration; or it is split: a control host elsewhere and the old machine as a node.
   - A Kata host that takes the new release without that step still starts: it runs containers alone, and says so loudly at its start, in `doctor` and on its page, with the command that converts it. It does not fail, and it does not run a VM.
   - A host that runs containers alone keeps working. `doctor`, the startup log and the host's page say what an escape reaches there.
   - A deployment with DigitalOcean's driver does not change.
   - **What a converted Kata host loses or gains, to be said in the release notes:**

     | A Kata host today | After the conversion |
     |---|---|
     | The workspace is a mount | The workspace travels over the bridge, and its commits back (Consequences, "Files") |
     | The egress proxy is an option (`--egress-proxy`) | Always on, as for every VM per run (ADR-0096): a host that ran without it needs its allowlist |
     | A Codex login is a mount | A file kept in step (decision 6). Until that is built and tested, such a host keeps its Codex runs in containers |
     | Runs on aarch64 | Not tested: every run of the prototype was on x86-64 ("Accepted unknowns"). An aarch64 Kata host stays on containers until it is |
     | Any distribution that Docker and Kata run on | The role's distributions: Debian, Ubuntu, the Red Hat family |

   - Whether the removal waits for the last three rows is open question 2.

8. **Verification.** The property is tested by simulating the escape, not by reviewing the setup.

   - **Path A, on a node.** A test gives itself a shell as a VM's VMM user, inside its jail, while two runs are in progress. It must fail to: read or write the other VM's disk, memory or API socket; trace or signal its VMM; reach its bridge's port or the agent's socket; see its files in any shared directory; or leave the chroot. On a single machine it must also fail to read the runner host's files, trace its process or reach its listeners. The test runs once more with the seccomp filter off, to show that the user, the chroot and the namespaces hold on their own.
   - **Path B, and path A's second flaw.** A test gives itself a root shell on a node while runs are in progress with canary credentials: a git token, the host's token, a one-run model token. It must fail to:
     - find a canary anywhere on the node: its disks, its swap, the memory of every process and of every VM, every environment, the user data, every byte the bridge carried to the node (recorded on the control host's side of the test);
     - open a connection to the control host, on any port, or to another node's agent or bridge ports, the server, the model gateway, the code hosts, the internet by address and by name, or a metadata service;
     - get anything but a refusal from the node agent's port without the control host's client key, and get a `start` for a run from a ticket that was used;
     - push through a run's git tunnel, fetch a repository that is not the run's, or use a tunnel after its run ended;
     - make the control host push to any branch but the run's own, write outside its clone, its session home or `/output`, or skip its checks, by what a node sends back as a run's work;
     - act as the host at the server with anything found on the node.

     The same test asserts what root on a node can do (Consequences): read its runs' files, use their tunnels while they last, and replace the work they hand back. The test records it so that nobody claims more. On a single machine no such test can pass, and the documentation says so.
   - **Path C.** The control host's readers of a sandbox's input (the frames, the archives, the bundle import, the proxies' request parsing) get fuzz tests with a hostile peer, beyond today's tests of truncated and oversized frames (ADR-0095). They show robustness, not absence of flaws.
   - **The agent's two processes.** A shell as the listener's user must fail to do any of the helper's things directly, and to get anything from the helper but its fixed list with values it accepts.

9. **Where it can run.** A machine that runs VMs needs KVM; the control host runs anywhere. No CPU generation is required.

   | Where | What works |
   |---|---|
   | The company's own servers | Any x86-64 machine with virtualisation on (Intel VT-x, AMD-V) |
   | The company's own hypervisor (VMware, Proxmox, OpenStack) | Nodes as VMs with nested virtualisation on; or, with a driver for that hypervisor, a VM per run and no node |
   | A cloud, a VM per run (ADR-0096) | Wherever a driver exists: DigitalOcean today. Nothing is nested, and the provider's hypervisor is the boundary |
   | A cloud, nodes as instances | Instances that give KVM: bare metal, or instance types with nested virtualisation ([KVM in a cloud's instances](../reviews/2026-11-11-kvm-in-cloud-instances.md)) |
   | A laptop or a machine without KVM, a Mac | Containers alone |

   So on a cloud a company is bound to the providers with a driver or with KVM in an instance. The next driver after `node` is one for a large cloud's VM per run; which cloud is open question 4.

## Consequences

- **An escape through the VMM reaches one unprivileged, jailed user** (path A). On a sandbox node that is a machine with no credential and no route to one.
- **Two one-flaw paths stay** (decision 1): KVM (path B), which gives root on the machine that runs the VM, and the control host's own readers (path C), which give every credential and need no escape. Nobody should read this decision as "two exploits to a token".
- **On a single machine the kernel is all that stands between a sandbox and the credentials.** It is what one machine can do. The separated form is the one for a company's hostile input.
- **What root on a node gets** is what the runs on that node may do, while they last: their files and prompts, fetches of their repositories through the git proxy, model requests on their payers' budgets through the model proxy, their platform tools, and the egress allowlist. It holds no token. Root on a node is reached by path B with one flaw, or by path A and a flaw of the kernel from inside the jail.
- **Root on a node also decides what those runs hand back.** The sandbox cannot push (the git proxy refuses it), but the control host fetches each run's commits from the sandbox, checks them and pushes them to the run's branch with the run's git account, and takes back the session home and `/output`. Whoever is root on the node can replace all three for every run on it.
- **What was handed back outlasts the node.** A node where this happened stays compromised until it is rebuilt, and re-imaging on a schedule bounds how long new runs are exposed. It does not undo what left the node: commits pushed under a person's git account, artifacts saved from `/output`, and session homes on the control host, which steer the next turn of their conversations (6.17, R58). After a node is found compromised, the operator needs the list of runs it served, to review their branches and artifacts and to discard their sessions. The control host keeps what that list needs; the tool for it is planned.
- **The kernel is the shared part that is left.** A flaw of KVM itself and a flaw in the small part of the kernel that a jailed VMM may still call cannot be closed in software. ADR-0113's hardening narrows both. Only a machine per run removes the kernel (a provider's VM per run), or hardware that hides a guest from its host, which the owner does not want.
- **One way for Tinfirm to run a VM.** Kata, its shim, virtiofsd and the Docker form of the container that ran the agent's own Docker are gone, with their rows of the threat model. What is new is the node agent, a second binary to build, sign and publish, the VM image for a local disk, and a dependency on the guest kernel that the Kata project publishes.
- **A machine without KVM runs no VM.** That was so with Kata too.
- **Costs,** as measured with a prototype of the node on one machine and between two:
  - **Start.** A sandbox is ready 7 to 8 s after its VM is asked for, on one machine and on two; a Kata host had a running agent after about 9 s, which is a later point than a ready sandbox, so the two figures are not one measure. The whole of a short run takes the same time within a second or two. A VM exists 0.1 to 0.2 s after it is asked for; the wait that follows is Docker starting in the guest and the probe (about 3 s, decision 4). Ready VMs (ADR-0098) take most of the wait away.
  - **Memory.** The same as Kata's: about 1.3 GiB for a sandbox of 6 GiB after a run, about 1 GiB idle, with one VMM in place of a VMM, virtiofsd and a shim. A machine of 11 GiB holds six idle VMs of 4 GiB, or three busy ones. A balloon gives little back. Another runtime in the VM changes little: it started in the same time and held about a tenth more.
  - **Files.** Every workspace travels to the VM and its commits back, where a Kata host used a mount. The bridge is not the limit: it moves 70 to 110 MiB a second in each direction, one core's worth. A small workspace costs nothing that shows, and a large change to a large source tree (15,000 files) comes back in under 20 s. Work of 1 GiB in a hundred thousand small files, the worst case found, comes back in over two minutes, against 19 s by a mount; most of that is git's packing in the sandbox ("To specify and verify during the build").
  - **Failures.** A cancelled run, a killed VMM and a restarted runner host leave nothing behind; a killed node agent leaves its VMs until it is back.
  - **Machines.** For the separated form, a second one at the least.
- **Git bandwidth and the server stay as they are.** Git goes between the control host and the code host; nothing new passes through the server (R29).
- A new listener exists on each node, and a new credential on the control host: the driver's client key. Whoever holds it creates and deletes VMs on its nodes, as a provider's token does (6.16), and can pose as a control host to those nodes' listeners.
- The control host needs no Docker socket, so 6.2 no longer applies to the machine that holds the tokens.
- **The threat model changes:**
  - TB6: for a separated host it is a network boundary between two machines, with the node agent and the bridge as its only crossings. New rows for the node agent, for a compromised listener, for a compromised node and what it hands back, and for the three paths of decision 1.
  - TB7: a VM lets nothing out; the node's own egress is a new row, held by the operator's network.
  - R22 and 6.9 (Kata, and root in its VM) are rewritten: an escape through the VMM reaches a jailed user; a flaw of KVM reaches root on a node without credentials, or on a single machine with them.
  - R27, R44, §6.6: on a separated host the git tokens and the host's token are on the control host only. Their lifetimes do not change: TIN-42 and Q41 stay worth doing, for the control host.
  - R28: closed for every host that runs VMs, where the bridge is the only way.
  - R34: the control host is the tier-0 asset. A node sees the content of its runs and holds no credential: a lower tier, to be named.
  - R36: a restarted control host takes its runs back from its nodes as from a provider's VMs (ADR-0101); a node that restarts loses its runs.
  - 6.17, R58: a session home that a compromised node handed back steers later turns.
  - R12, R26, R55: unchanged in kind; R55's reason is rewritten, since the host now carries the login file (decision 6).
- Until it is built, the only setup with the property is ADR-0096's with a provider. The documentation should say that today.

## Alternatives considered

- **Keep Kata as the `microvm` tier of single-machine hosts,** and the jailed VM for nodes alone. Rejected by the owner: two ways to run a VM, and the kept one is the weaker (a root VMM, a root shim, root virtiofsd), no faster and no lighter. That hosts have it installed is no reason to keep it.
- **Kata with Firecracker in place of a node agent of our own.** It would spare the agent. Rejected: Kata runs Firecracker's jailer as root, so the VMM is root with every capability, and its shim is root as well.
- **A credential gateway as a component of its own:** a proxy outside the sandbox's machine that injects credentials on the wire, with the VM allowed to reach nothing else. The idea is the one taken here. The component is not new for Tinfirm: the runner host's git, model and egress proxies are that gateway, and the bridge makes it the sandbox's only peer. Moving the existing gateway off the sandbox's machine gives the same boundary with Compose and plain hosts.
- **One run per node,** so that no run has a neighbour. Rejected by the owner as the rule: it turns every concurrent run into a machine, and on premises machines are the scarce thing.
- **Confidential VMs** (AMD SEV-SNP, Intel TDX) with an attested bridge key, which would keep a run from root on its own node. Rejected by the owner: it binds companies to recent server hardware and to the clouds that expose it.
- **VMs restored from a snapshot of a booted VM's memory,** in place of ready VMs. Restoring takes milliseconds and a run works on such a VM, but it saves under a second of the eight to a ready sandbox, because the wait is in what starts after the VM exists. It costs a reset of the clock, the address, the entropy and the machine's identity in every restored guest.
- **A single machine for every company host.** Rejected as the end state: the machine's kernel is shared with the credentials. It is what a personal host and a small installation have.
- **A git relay in the server,** so that no runner host holds a git token. Rejected again. Clones and fetches of large repositories would pass through the one server replica that already carries the runner connections and the model streams (R29), and the host's token would still be on the sandbox's machine. Once the control host exists, it is that relay, outside the server.
- **Short-lived and revocable git tokens alone** (TIN-42, Q41). A mitigation: less time, the same place. Kept, beside this decision.
- **Nothing beyond a VM per run at a provider for high-risk work.** It has the property today. Rejected as the whole answer: it needs a cloud account and a provider with a driver, which an on-premises or air-gapped company does not have.
- **A locked-down container inside the VM as the boundary,** in place of the VM. Not possible: the agent needs its own Docker.
- **The subscription's login kept on the host,** with a proxy that adds it to requests, as other sandbox products do. Not taken without the provider's word (decision 6).
- **User-namespace remapping or rootless Docker for containers.** Worth doing for containers (ADR-0006 planned it). It turns root after a kernel bug into an unprivileged user after the same bug, on the machine that still holds everything. Not an answer to this problem.
- **Runner protocol 3,** with the credentials split from `run.assign` into frames for a gateway. Not needed: the control host is the runner host, and the split is below the protocol.

## The owner's decisions so far

Decided by the owner, 2026-11-11 to 2026-11-14. The revision of 2026-11-17 changed none of them; where the review found one in conflict with something else, it is an open question below.

- Kata is dropped; one kind of VM of Tinfirm's own (decision 2).
- The control host runs apart from the server by default (decision 3).
- The node's network: the operator's network and a firewall that the role installs (decision 4).
- A subscription's login stays the member's own file, carried into the VM by the bridge; a login kept on the host waits for the provider's word (decision 6).
- TIN-42 is built before the split (decision 7).
- Kata is removed in the release that brings the node agent, with no release of overlap; a Kata host that is not converted falls back to containers alone and says so (decision 7; see open question 2).
- The VM image for a local disk is published with each release (decision 4).
- The next driver is a cloud's (decision 9).
- The guest kernel is the one Kata publishes; Tinfirm builds none (decision 4).
- The node agent is a small binary of its own, from the same source tree and release, split into an unprivileged listener and a root helper with a fixed list of things to do; the drivers stay in the runner host's binary (decision 4).
- A node reaches its VMs for the control host by a port per VM or by an address per VM; both stay (decision 4).
- A compromised node must not be able to tamper with its other runs after one exploit, without one run per node, without pools and without special hardware. This holds for path A. It does not hold for path B (decision 1); the owner has not yet said whether that is accepted.

## To specify and verify during the build

These are parts of the work, not of the decision. Each is settled when its code exists.

- **Several nodes behind one driver.** The `node` driver's configuration names its nodes: an address, a pinned certificate and the templates of each. To specify: which node a new VM goes to, how the nodes' capacity adds up to `verify`'s `maxVms`, and what happens to the runs and the count when a node is down.
- **The agent's wire.** The requests between the driver and the listener, and between the listener and the helper. The prototype used another transport than TLS with a pinned client certificate, and its helper was not yet a compiled program with a system-call list of its own. The two processes and the helper's four operations ran as a prototype on one machine, under the service manager's confinement.
- **Upgrades.** How the agent and the VM image of several nodes are upgraded and rolled back while runs are in progress, and what the build check does between a new control host and an old node.
- **What the agent reports.** Its log, and its metrics for the operator.
- **The bridge's file in step** (decision 6), and what two runs at once on one login do to each other's refresh.
- **Ready VMs with the `node` driver,** driven by the server's schedule (ADR-0098, ADR-0099; paid, ADR-0114).
- **The sandbox packs alone (planned).** Most of the time to pack a large commit goes to git's own background repack, which starts after the commit and competes with the bundle for the same objects. With no background repack for the run's commits (`gc.auto=0`), packing 1 GiB in a hundred thousand small files went from about 100 s to about 60 s in the prototype. Git's search for deltas and its compression stay: without them a bundle of real source code grew 11 to 219 times.
- **More than a handful of VMs at once** on one machine, on hardware larger than the tests had.
- **The documentation:** the operator's pages for a control host, a node and a single machine, and the release notes of decision 7.

## Accepted unknowns

Not tested, and not needed for this decision. Each is named so that nobody takes it as shown.

- **ARM.** Every run was on x86-64. Firecracker runs on 64-bit ARM, but none of the three large clouds gives KVM in an ARM instance, so a test takes bare-metal ARM hardware.
- **An empty capability bounding set for the VMM,** which takes a change of Firecracker's jailer.
- **A runtime's second turn in a VM,** and the runtimes that were not run.

## Evidence

Claims that rest on experiments or sources outside the repository, and what follows if one is wrong.

| Claim | If it is wrong |
|---|---|
| Kata 4.3.1: the rootless mode moves the VMM alone; with Firecracker the jailer runs as root; no sandbox under Docker without virtiofsd | "Kata is the weaker" loses its ground. "One way to run a VM" still stands |
| As the role installs it, Kata's VMM is root with every capability and no AppArmor profile | The same |
| A jailed Firecracker VM is as fast as Kata and holds the same memory | A cost changes; the decision stands |
| The jail's figures: six capabilities in the bounding set, 46, 29 and 23 system calls, the helper's ten capabilities, two to six milliseconds for the split | The decision stands: the safety rests on the helper's checks and the VMM's user, not on these numbers |
| The node agent's two processes work | Decision 4 stands in shape; its transport and compiled helper are untested |
| The guest kernel that Kata publishes has what Docker needs, nf_tables included | Tinfirm must build a kernel, against the owner's decision |
| The timings, the memory figures and the bridge's throughput in Consequences | Costs change; the decision stands |
| Anthropic's terms as quoted (read 2026-11-14) | Decision 6's last point and open question 3 change |

## Open questions

1. **Path C.** Should the control host read a sandbox's input in a process per run that holds no credential: the bundle import, the unpacking of archives, and, as far as they can be split, the proxies' request parsing? It would make path C a path of two flaws for the credentials. It is the one change that would let this decision say "no credential after one flaw" for a separated host, KVM aside. The review recommends deciding it before acceptance, even if it is built later.
2. **The hard cut for Kata.** Does the removal wait until a Codex login reaches a VM and aarch64 is tested (decision 7's table), or do such hosts run containers in between?
3. **A subscription's login.** Does the provider allow the bridge to carry the login file between a member's host and that member's VM? And does it allow a proxy on the member's own machine to hold the login and add it to requests? If the second, it replaces the file in the VM and ends R12, R26 and R55 there. The same questions go to the provider of a Codex login, whose terms were not read.
4. **The next driver.** A cloud's VM per run comes next. Which cloud is decided when a company needs one; KubeVirt (ADR-0115) and the on-premises hypervisors (libvirt, Proxmox, vSphere) follow on request.
