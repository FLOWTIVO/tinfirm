# ADR-0115: Sandbox VMs in a Kubernetes cluster: a pod per run, and Kata pods are not offered

- Status: Proposed
- Date: 2026-11-17; split from the first draft of [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (2026-11-14) after a review
- Decided by: not yet; the owner decides. The owner answered open question 2 on 2026-11-19: the pod per run, KubeVirt and the node agent as a DaemonSet are to be built in this scope, KubeVirt and the DaemonSet each with a decision record of its own when it is built. Built so far: the chart's part of the first form with sandbox nodes (decision 1, `92da902`), which has not run against a node yet. Nothing else here is built yet. What the owner decided on the way is listed under "The owner's decisions so far"
- Sources: [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) (the Kubernetes executor, not built), [ADR-0095](0095-sandbox-bridge.md), [ADR-0096](0096-a-vm-per-run.md) (the driver protocol), [ADR-0101](0101-closing-the-elastic-runners-gaps.md) (decision 8: the runner host as a pod of the chart), [ADR-0112](0112-no-credential-on-a-sandbox-machine.md) (the property and its three paths, the node agent, the jail), [ADR-0113](0113-levels-of-isolation-and-placement.md) (the levels, hardening), [ADR-0114](0114-the-secure-sandbox-is-in-the-core.md) (what is paid); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §3, §6 and its decision D1; the [threat model](../threat-model.md) 6.15, 6.21
- Changes the analysis' decision D1: on OKD or OpenShift it chose Kata pods on KVM workers. Kata pods are not offered (decision 5)
- Changes, when built: a VM driver for a pod per run, under `ee/runner/`; the Helm chart (the runs' namespace, the driver's service account and its rights, the runs' two network policies, the admission policy for run pods); the operator's documentation for a cluster's admin; the threat model (new rows for a worker that runs VMs, the kubelet's credential and the ticket's Secret). A driver for KubeVirt and the node agent as a DaemonSet are to be built in this scope too, each with a decision record of its own when it is built

## Context

- The runner host runs as a pod of the Helm chart with `--executor vm` (ADR-0101, decision 8): it is the control host of ADR-0112, on general workers, and holds the token, the proxies and the mirrors.
- ADR-0112 runs sandbox VMs on nodes, with a node agent whose helper is root on the node. A cluster's admin wants as little privilege in the cluster as its own features allow, and wants the scheduler to see what runs.
- The analysis of 2026-10-15 planned sandbox pods with a `kata` RuntimeClass (§3) and, for OKD and OpenShift, decided on them (D1). ADR-0112 found Kata's VMM or jailer running as root in every variant.
- The measurements and "works" below come from experiments that are not in the repository (ADR-0112, "Evidence"). The cluster runs were on OKD and on a managed cluster.

## Decision

**Where the VMs run decides what a cluster must allow. Tinfirm recommends the forms in this order. A pod per run is the form for a cluster that is to run the VMs itself.**

1. **The forms.**

   | Where the VMs run | Level (ADR-0113) | What the cluster must allow | Edition (ADR-0114) |
   |---|---|---|---|
   | Outside the cluster: sandbox nodes that are no part of it (ADR-0112), or a provider's VM per run | `machine` | Nothing: the runner pod with `--executor vm` | Nodes: core. A provider's VMs: paid |
   | **A pod per run**, with a user namespace, Firecracker in it as an unprivileged process | `machine`, where the runner pod runs on other workers | Workers with KVM, a KVM device plugin, user namespaces for pods, and a tap device from the network plugin (or one network capability in the pod's own namespace) | Paid |
   | KubeVirt, a VM per run from the cluster, on a cluster with SELinux (OKD, OpenShift) | `machine`, where the workers hold nothing else | The cluster's virtualisation operator, and a VM driver for it | Paid |
   | The node agent on a worker pool of its own, as a DaemonSet | `machine`, where the pool holds nothing else and its credentials open nothing | Workers with KVM, and a pod that is root on the worker in all but name | Paid |

   - The conditions in the level's column are the driver's to enforce where it can: it schedules a run's pod only on workers that do not run the runner pod (a node selector and an anti-affinity that the driver sets, not the operator alone). What it cannot see ("the workers hold nothing else", what a worker's cloud identity may do) the operator states, and the host's page marks the level as stated (ADR-0113, decision 3).
   - ADR-0112's three paths out of a sandbox hold here as there. Path C, the runner pod's own readers of a sandbox's input, is the same in every form.
   - Built, 2026-11-19 (`92da902`): the chart's part of the first form with sandbox nodes. `runner.vm.driver: node` renders the `node` driver's configuration from `runner.vm.node.nodes` (each node's name, address, pinned certificate and templates) into a ConfigMap; the client certificate and key come from a Secret that the operator creates, and an init container copies the key, 0600, into a volume in memory; the pod's NetworkPolicy opens each node's agent port and the published bridge ports (`runner.vm.node.publishedPorts`, `networkPolicy.runner.egress.nodes`). The node agent's execution exists since `52ee70e` (ADR-0112); the chart's pod has not run against a node yet.

2. **A pod per run.**

   - **The pod is the jail.** With `hostUsers: false` the cluster gives each pod a range of the worker's user IDs of its own, so two runs' VMMs are two unprivileged users there, with no capability even in the bounding set, under the runtime's seccomp profile and Firecracker's own, the runtime's AppArmor profile or SELinux, a read-only root file system and the pod's memory limit. A process with one run's user is refused the other's disk, its memory and a signal to it. Firecracker's jailer cannot run in a user namespace and is not needed there. Real conversations with the agent's own Docker ran this way with the real VM image.
   - **The pod passes the `restricted` level of Pod Security** where the cluster's network plugin hands it the VM's tap device (Multus with the `tap` plugin): it then runs as an ordinary user with no capability, no init container, no host path, host network or host PID, and gets `/dev/kvm` and `/dev/net/tun` from a device plugin. Without such a plugin an init container with `NET_ADMIN`, which in a user namespace holds for the pod's own network namespace alone, makes the tap, and the pod passes neither `restricted` nor `baseline`.
   - **On OKD** both pods run under the stock restricted constraint, and each run gets a project of its own, which gives it an SELinux category of its own as a second line behind the user range. The cluster's admin applies, once: a MachineConfig for the workers that run VMs (the runtime gives a device to the pod's user, CRI-O's `device_ownership_from_security_context`, since a user namespace otherwise gets the devices closed; and SELinux lets a container use devices, `container_use_devices`, which holds for the whole worker); the device plugin, which is the one privileged workload; one tap attachment with the container's label; the runner host's namespace; and for its service account the right to ask for projects.
   - **The right to ask for projects needs a check.** The first draft said that with it the runner host "can read no secret and list nothing anywhere". A stock OpenShift gives whoever requests a project the `admin` role in it, which reads that project's secrets. If that holds on the OKD that was tested, the claim needs a project request template that gives the runner host only the rights of the last point below. To verify before this is built (open question 1).
   - **One network policy for all run pods, installed with the chart,** does what the node's firewall does: in only from the runner pod to the bridge's port, also across workers, and nothing out. Without it a run's pod reaches the API server, the kubelets and the cloud's metadata service. The pod's own worker reaches it in any case. It is two objects in the runs' namespace: a default that denies everything, which covers the namespace from its creation, and one rule that admits the runner pod to the bridge's port.
     - **The driver creates no network policy and has no right to.** Network policies add up, so whoever may create one in a namespace can open its pods. The driver reads the namespace's policies at its start, at each check and before a create, and starts no VM while one of the two is missing or changed, or while a third one is there.
     - **What a run may reach outside is not this policy's matter.** A run's pod reaches nothing directly. The egress proxy in the runner pod enforces each run's allowed hosts (`run.assign.allowedHosts`, ADR-0044), as on every other host.
     - **With several runner hosts on one runs' namespace,** each runner pod can connect to every run pod's bridge port. A session still needs that sandbox's own TLS identity and its one-time ticket.
     - Changed by the owner on 2026-11-20. The first text had one policy per run, which the driver made before each pod: every run's policy would have been the same, the driver would have held the right to create policies, and each run would have waited for the network plugin to apply its own.
   - **The disk.** The VM image comes to the worker once, as an image volume or in the pod's image, read-only. Each run's writable part is one of: a clone of the image on a file system that can clone (milliseconds on xfs), a copy where it cannot (seconds to tens of seconds on ext4), or a small empty disk over the read-only image with Docker's own directories on it. No device-mapper there: not in the pod, and not in the guest, whose kernel has no snapshot target.
   - **The scheduler sees the VM:** its memory is the pod's, and a VM that outgrows its limit is stopped alone.
   - **The one-time ticket** travels in a Secret of the run's namespace, which the driver deletes as soon as the VM has read it, some two seconds after the pod starts. Until then whoever may read secrets in that namespace, and root on that worker, can read it; a used ticket opens nothing.
   - **What it costs:** two to three seconds more to a ready sandbox than a VM on a node (9 to 10.5 s against 7 to 8 s); and the device plugin is a privileged pod of its own.
   - **No hardening mark.** A pod sees nothing of its worker's settings, and a worker whose pods use user namespaces cannot have ADR-0113's first line of hardening (no user namespaces). Such a host reports `hardened: no`, whatever else its admin set on the workers (ADR-0113, decision 5, as the owner decided on 2026-11-19).
   - **The runner pod** is an ordinary pod that passes `restricted`: one unprivileged container, no capability, a read-only root file system, no device and no host path, a volume for its mirrors and session homes, and the host's token in a Secret of its own namespace, which whoever may read secrets there holds (6.21). Its network policy lets nothing in, and out only to the run pods' bridge port, DNS and the outside (the server and the code hosts); to name those hosts takes the cluster's own means. It needs no way to the model gateway: a run's model requests go from the runner pod's model proxy to the server's relay (ADR-0059; threat model 5.10), so the policy allows none. In the runs' namespace it may create and delete pods and secrets, read and list pods, read and list the network policies, read the namespace's quota, and nothing else, and it has no right in any other namespace or on the workers. It reads no pod's log: a run pod that fails says why in its termination message, which is part of the pod.
   - **The right to create pods is a way to read secrets, so an admission policy closes it.** The runner pod may not read or list secrets, but whoever may create a pod in a namespace can mount any Secret of that namespace into it, and can name any service account there. RBAC alone is therefore no boundary. The chart installs an admission policy (a ValidatingAdmissionPolicy, or the cluster's own policy engine) for the runs' namespace: a pod there runs as the one fixed service account for runs, which has no rights; it mounts no service account token (`automountServiceAccountToken: false`, and no projected token volume); and it refers to no Secret but the one ticket Secret made for that pod, by its name. The runs' namespace holds no other Secret by design. Without such a policy in force the driver refuses to start.

3. **KubeVirt** gives a VM per run that the scheduler accounts for. It is not ADR-0112's VM: its VMM is QEMU under libvirt, as a provider's VM per run is the provider's. Its VMM runs in an unprivileged pod as a user without capabilities, under a seccomp filter and a profile. Every VM's VMM is the same user; on a cluster with SELinux each VM has a category of its own, and a process in one VM's context is refused another VM's disk, its memory and a signal to it. So between two runs the wall is the SELinux category alone, where a pod per run has a user range and a category. A handler runs on every worker as root and reaches every VM there. A VM is ready in tens of seconds with an untuned image.

4. **The node agent as a DaemonSet** runs the jail of ADR-0112 from a pod, as fast as on a plain machine and with ready VMs as built. It needs eleven capabilities (ADR-0112 counts ten for the helper on a plain machine; the difference is to be explained from the experiment's record), the devices, the cgroup file system writable and no seccomp or AppArmor profile of the runtime, or `privileged`; on OKD the `privileged` constraint. The scheduler does not see its VMs, so the pool is its alone. It is for a cluster whose admins accept that, where the start of a run matters more than the pod's privileges.

5. **Kata pods are not offered,** a cloud's managed ones included: in every variant the VMM or the jailer runs as root on the worker, and the agent's own Docker needs a privileged container. This changes D1 of the analysis for OKD and OpenShift.

6. **A worker that runs VMs holds the kubelet's credential** and the worker's cloud identity. The first reads the secrets and tokens of pods bound to that worker and of no other (NodeRestriction), and a run's pod carries none but the ticket's Secret, for two seconds. ADR-0112's property counts both: the workers for runs are ones where these open nothing, and the runner pod runs elsewhere.

7. **Verification.** Each form gets the tests of ADR-0112, decision 8, when it is built, with a root shell on a worker in the node's place and a shell as a run's VMM user in its pod.

## Consequences

- A company with a cluster and machines outside it needs nothing of this: the runner pod and sandbox nodes are the first form, and they are in the core.
- A pod per run puts no root process of Tinfirm's on a worker. The cluster's admin still runs one privileged workload, the device plugin, and on OKD changes the workers' configuration once.
- A cluster without KVM on its workers runs no sandbox VM in any of these forms: most managed clusters' default workers, and AKS's sandboxed pods' workers, which have no `/dev/kvm`.
- Workers with user namespaces report "not hardened", which places such a host after a hardened node among `machine` hosts (ADR-0113, decision 4).
- The analysis' Kubernetes executor with a `kata` RuntimeClass (§3) is not built. What ADR-0006 lists as "not built: the Kubernetes executor" becomes a VM driver, behind ADR-0096's driver protocol.

## Alternatives considered

- **Kata pods with a RuntimeClass** (the analysis §3, D1). Rejected: decision 5.
- **The node agent as a DaemonSet as the recommended form.** It is the fastest and has ready VMs as built. Not recommended first: a pod that is root on the worker is what a cluster's admin is least likely to allow, and the scheduler does not see its VMs.
- **A privileged pod per run with the jailer in it.** Rejected: every run's pod would be root on its worker, where a user namespace gives the same separation without it.
- **Sandbox nodes that join the cluster as plain workers,** with the node agent installed by the role and not by the cluster. This is the first form with a worker's kubelet added, and it gains nothing: the node then holds a kubelet's credential that it does not need.

## The owner's decisions so far

Decided by the owner, 2026-11-11 to 2026-11-14, and later where a date says so.

- On Kubernetes a pod per run is the recommended form in a cluster, the node agent as a DaemonSet the alternative.
- The Kubernetes forms are paid (ADR-0114). 2026-11-19: the first form, a runner pod with nodes outside the cluster, is free; the three forms in which the cluster runs the VMs are paid (ADR-0114, decision 1).
- 2026-11-19: the pod per run is to be built in this scope, and KubeVirt and the node agent as a DaemonSet too, each with a decision record of its own when it is built. KubeVirt is tested on OKD on Azure VMs.
- 2026-11-19: a worker whose pods use user namespaces reports `hardened: no` (decision 2; ADR-0113, decision 5).
- 2026-11-20: one network policy for all run pods, installed with the chart, in place of one per run that the driver makes; the driver gets no right to create network policies, and a run's allowed hosts stay the egress proxy's to enforce (decision 2).

## To verify during the build

- **A persistent volume for the runner pod.** The chart has one (`runner-pvc.yaml`, ADR-0101). To verify: a runner pod restarted in the middle of a run takes its runs back from run pods as it does from a provider's VMs.
- **The project request on OKD** (decision 2, open question 1).
- **The driver's placement:** that a run's pod never lands on the runner pod's worker.
- **The admission policy** (decision 2): that a pod with another service account, a token volume or another Secret is refused, on Kubernetes and on OKD.

## Accepted unknowns

Not tested, and not needed for this decision. Each is named so that nobody takes it as shown.

- **A cluster whose network plugin allows no second one** beside it for the tap device. The pod then uses the init container with one capability, which is tested.
- **KubeVirt after an escape into its launcher's pod, and with hot-plugged or persistent volumes.** KubeVirt's driver gets a decision record of its own, which covers that.
- **OpenShift itself.** The cluster runs were on OKD and on a managed cluster.
- **What a worker's cloud identity may do** in a given cluster. Decision 6 asks that it opens nothing; whether it does is each cluster's own setup.
- **Why a guest started slower on the one OKD node that was tried** (13 to 16 s to a ready sandbox, against 8 to 10 s elsewhere; that range and decision 2's 9 to 10.5 s are to be reconciled from the experiment's record).

## Questions the owner answered

2. **When this is built:** in this scope. The pod-per-run driver is to be built, and KubeVirt and the node agent as a DaemonSet too, each with a decision record of its own when it is built; KubeVirt is tested on OKD on Azure VMs. The owner, 2026-11-19.

## Open questions

1. **What the right to ask for projects gives on OKD.** Does the runner host's service account get `admin` in each project it requests, and so read its secrets? If so: a project request template with narrower rights, or one namespace for all runs, which gives up the SELinux category per run. To be verified on the cluster.
