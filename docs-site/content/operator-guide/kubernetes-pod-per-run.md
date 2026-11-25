# Sandbox VMs in your Kubernetes cluster: a pod per run

This page is for a Kubernetes cluster's admin and for the operator of Tinfirm who want the cluster itself to run the agents' sandbox VMs. It helps you decide whether your cluster can run this form, and then set it up. It assumes that the chart's runner pod is or will be installed ([The runner pod](helm.md#the-runner-pod)).

!!! warning "Not ready for production: this form has run in no cluster yet"

    The driver, the program in the run pod and the chart's part are built and tested against a fake Kubernetes API, and the image's recipe builds the run pod's image. Nothing of it has run against a real cluster. One check of the form's largest risk ran on a managed cluster on 2026-11-20, before the driver existed: Firecracker in a pod with a user namespace of its own, as an unprivileged user, booted a guest and reached it over its tap. That check did not try a second worker, a run pod on another worker than the runner pod, a worker's restart or replacement, the guest's way out, the admission policy, or the driver itself ([ADR-0115](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0115-sandbox-vms-in-a-kubernetes-cluster.md), "Shown on a managed cluster"). Use this page to prepare and to test. For production, use [sandbox nodes outside the cluster](sandbox-nodes.md) or DigitalOcean's driver ([With DigitalOcean](helm.md#with-digitalocean)) until then.

## What this form is

The runner pod (`runner.vm.driver: pod`) creates one pod for each run in a namespace of the runs' own. That pod runs one Firecracker VM, the run's sandbox, as an unprivileged user in a user namespace of its own (`hostUsers: false`), with no capability and a read-only root file system. The pod gets `/dev/kvm` and `/dev/net/tun` from a device plugin, and the VM's tap device from Multus or from a short init container. The runner pod reaches the VM through the pod's port, and nothing else reaches the run pods: two network policies and an admission policy that the chart installs see to that. The scheduler counts every VM's memory and CPU, and the cluster's autoscaler can add workers for them.

Choose [sandbox nodes outside the cluster](sandbox-nodes.md) instead when:

- your cluster's workers have no KVM, or your cluster cannot give pods a user namespace of their own;
- you cannot give the runs a pool of workers that runs nothing else (below, [The isolation statement](#the-isolation-statement));
- you want the hardening mark: workers whose pods use user namespaces always report `hardened: no`;
- you have machines outside the cluster. That form needs nothing from the cluster, is in every edition, and a node holds no kubelet credential.

This form is part of Pro and Enterprise (elastic runners, [Licence](licence.md)). Without such a key the server refuses the runner host, and the runner pod stops.

## How it works

| | The runner pod | A run pod |
|---|---|---|
| Namespace | The release's (`tinfirm` on this page) | The runs' own (`tinfirm-runs`), which holds nothing else |
| Runs on | General workers | The workers for runs, never the runner pod's worker |
| Holds | The host's token, the runs' git and model tokens, a ServiceAccount token for the driver's rights | No credential. For about two seconds after its start, the run's one-time ticket |
| Runs as | User 10001, no capability, read-only root | User 10001 in a user namespace of its own, no capability, read-only root; in the `init` variant, an init container with `NET_ADMIN` in the pod's own network namespace first |
| Reaches | The server, the code hosts, DNS, the API server, the run pods' port 7443 | Nothing: in from the runner pod to port 7443 alone, nothing out |

For each run the driver creates the pod, then a Secret with the run's one-time ticket, owned by the pod. The pod's program (`tinfirm-runner-host vm-pod`) checks the pod, writes the ticket to the VM's user-data disk, copies the VM image to the pod's disk volume, and starts Firecracker. Once the guest's bridge answers, the pod is ready; the driver then deletes the ticket's Secret and the runner pod dials the bridge through the pod's address. When the run ends, the driver deletes the pod.

## What the cluster must have

| | Requirement |
|---|---|
| Kubernetes | User namespaces for pods (`hostUsers: false`): on by default from 1.33; on 1.30 to 1.32 only with the feature gate `UserNamespacesSupport` on the API server and every kubelet. ValidatingAdmissionPolicy `admissionregistration.k8s.io/v1`, 1.30 or later, unless your own policy engine takes its place (`runner.vm.cluster.admission: external`). The check ran on 1.36. The run pod uses no image volume |
| The workers for runs | x86-64 with KVM: bare metal, or a cloud instance type with nested virtualisation, on a plain node image (not a Kata or sandboxed-pod image). Linux 6.3 or later, since the pod's memory volumes need ID-mapped mounts on tmpfs. A container runtime with user namespaces: containerd 2.0 or later, or CRI-O 1.25 or later, with runc 1.2 or crun 1.9 or later. The check ran on Ubuntu 24.04 with containerd 2.3 |
| The workers' disk | Room for the run pod's image twice (the compressed download and the unpacked image, which keeps no holes) and for every run pod's disk on that worker (`diskMib` each, written sparse) |
| Devices | A device plugin that hands pods `/dev/kvm` and `/dev/net/tun` as countable resources, and `/dev/kvm` open to every user on those workers (below) |
| Network | A network plugin that enforces NetworkPolicy, for ingress and egress (the check ran with Cilium). For the `multus` variant, Multus with the standard CNI plugins `tap`, `static` and `tuning` on the workers |
| A registry | One that the workers for runs pull the run pod's image from without a pull Secret: the admission policy refuses a run pod that names one, since the runs' namespace holds no Secret but the tickets. A registry that the workers' own identity may read, or one inside your network that needs no login |

The run pod passes the `restricted` level of Pod Security in the `multus` variant. In the `init` variant its init container holds `NET_ADMIN`, and the pod passes neither `restricted` nor `baseline`.

## What the cluster's admin applies

Each item says what privilege it needs or adds. Apply them in this order.

1. **A pool of workers for runs**, with a label and a taint. *Privilege: the cluster's or the cloud's admin; nothing inside the cluster.* Use a label and a taint of your own; this page uses `tinfirm.flowtivo.com/kvm=true`, with the effect `NoSchedule`. On a managed cluster, set both on the node pool, so that every new worker gets them; on your own workers:

    ```sh
    kubectl label node worker-kvm-1 tinfirm.flowtivo.com/kvm=true
    kubectl taint node worker-kvm-1 tinfirm.flowtivo.com/kvm=true:NoSchedule
    ```

    The pool runs the run pods and the cluster's own agents on every worker (the network plugin, Multus, the device plugin, a log collector), and no other workload: not the runner pod, not another team's pods. Its workers' cloud identity may pull the run pod's image and do nothing else that matters ([What an escape reaches](#what-an-escape-reaches)).

2. **`/dev/kvm` open to every user of those workers**, mode 0666. *Privilege: root's file permissions on the worker's `/dev` and `/etc/udev/rules.d`, no capability.* A worker's `/dev/kvm` is usually `root:kvm 0660`. A pod with a user namespace sees that owner and group as nobody, and Firecracker is refused (`Permission denied`). containerd's `device_ownership_from_security_context` does not help a pod with a user namespace; the check tried it. What worked is a udev rule that sets the mode, and one `chmod` for the device as it is now, both done by the device plugin's init container (next item), so that a new or replaced worker gets them before the device plugin offers the device.

    What it means: every process on such a worker may open `/dev/kvm` and so start a VM. Pods still get the device only through the device plugin: a container without it has no device node and no permission for it. A flaw in KVM is reachable from any process of the worker that can open the device, which is one more reason the workers hold nothing else. The mode held after a udev event in the check. That it holds after a worker's restart and on a replaced worker is inferred from the rule and the init container, not tried.

3. **The device plugin**, a DaemonSet on the workers for runs. *Privilege: host paths (`/dev`, `/var/lib/kubelet/device-plugins`, `/etc/udev/rules.d`) as root, in a namespace whose Pod Security allows that; no `privileged`, no capability, no ServiceAccount token.* This is the one workload of the form that is privileged on the worker. The manifest below ran in the check with these settings (only the names differ). The upstream manifest of the generic device plugin sets `privileged: true`, which it does not need. Choose a domain of your own for the resource names; this page uses `devices.example.com`, and `count` bounds the run pods per worker:

    ```yaml
    apiVersion: v1
    kind: Namespace
    metadata:
      name: tinfirm-devices
      labels:
        pod-security.kubernetes.io/enforce: privileged
    ---
    apiVersion: apps/v1
    kind: DaemonSet
    metadata:
      name: kvm-device-plugin
      namespace: tinfirm-devices
    spec:
      selector:
        matchLabels: {app: kvm-device-plugin}
      template:
        metadata:
          labels: {app: kvm-device-plugin}
        spec:
          automountServiceAccountToken: false
          nodeSelector:
            tinfirm.flowtivo.com/kvm: "true"
          tolerations:
            - {key: tinfirm.flowtivo.com/kvm, operator: Exists, effect: NoSchedule}
          priorityClassName: system-node-critical
          initContainers:
            # Mode 0666 on /dev/kvm: a udev rule for every later event, and chmod for now.
            - name: kvm-mode
              image: docker.io/library/alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
              command:
                - /bin/sh
                - -c
                - |
                  set -eu
                  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' > /rules/99-tinfirm-kvm.rules
                  chmod 0666 /dev/kvm
              securityContext:
                privileged: false
                allowPrivilegeEscalation: false
                readOnlyRootFilesystem: true
                capabilities: {drop: [ALL]}
              volumeMounts:
                - {name: dev, mountPath: /dev}
                - {name: udev-rules, mountPath: /rules}
          containers:
            - name: plugin
              image: ghcr.io/squat/generic-device-plugin:0.2.0@sha256:66c8d5c270eb2b721f1064c549b9b7898152a6d2f0163380a5d37dc7636c20ff
              args:
                - --domain=devices.example.com
                - --device
                - |
                  name: kvm
                  groups:
                    - count: 100
                      paths:
                        - path: /dev/kvm
                - --device
                - |
                  name: tun
                  groups:
                    - count: 100
                      paths:
                        - path: /dev/net/tun
              resources:
                requests: {cpu: 10m, memory: 20Mi}
                limits: {cpu: 50m, memory: 40Mi}
              securityContext:
                privileged: false
                allowPrivilegeEscalation: false
                readOnlyRootFilesystem: true
                capabilities: {drop: [ALL]}
              volumeMounts:
                - {name: device-plugin, mountPath: /var/lib/kubelet/device-plugins}
                - {name: dev, mountPath: /dev}
          volumes:
            - {name: device-plugin, hostPath: {path: /var/lib/kubelet/device-plugins}}
            - {name: dev, hostPath: {path: /dev}}
            - {name: udev-rules, hostPath: {path: /etc/udev/rules.d}}
    ```

    Check that every worker for runs offers both resources: `kubectl get nodes -l tinfirm.flowtivo.com/kvm=true -o custom-columns='NAME:.metadata.name,KVM:.status.allocatable.devices\.example\.com/kvm,TUN:.status.allocatable.devices\.example\.com/tun'`. The names go into `runner.vm.pod.devices` (below). The check ran on workers with Ubuntu; on a worker system whose `/etc` is read-only, the rule needs another place.

4. **For the `multus` variant only: Multus and the tap attachment.** *Privilege: Multus is a DaemonSet that is `privileged`, with the host's network, its process namespace and its whole file system, and a ClusterRole: by far the most privileged item of this list. The attachment itself is an object of the runs' namespace.* Install Multus with its thick plugin from the project's own manifest, if your cluster has no Multus yet; the check ran 4.3.2, with the image pinned and a node selector. The workers need the CNI plugins `tap`, `static` and `tuning` in `/opt/cni/bin`. Then apply this NetworkAttachmentDefinition in the runs' namespace, after the chart has created it (it is the one `runner.vm.pod.tap` in the chart's `values.yaml` shows, for the default `cidr`):

    ```yaml
    apiVersion: k8s.cni.cncf.io/v1
    kind: NetworkAttachmentDefinition
    metadata: {name: tinfirm-tap, namespace: tinfirm-runs}
    spec:
      config: |
        {"cniVersion": "1.0.0", "name": "tinfirm-tap", "plugins": [
          {"type": "tap", "ipam": {"type": "static", "addresses": [{"address": "172.30.0.1/30"}]}},
          {"type": "tuning", "sysctl": {"net.ipv4.conf.IFNAME.forwarding": "0"}}
        ]}
    ```

    - The `tap` plugin makes the VM's tap with the pod's side of the /30. It gets no `owner` or `group`: the pod has one user.
    - The `tuning` plugin turns the tap's own IPv4 forwarding off, which the pod cannot do itself; the pod's program starts no VM while the tap forwards. It puts the tap's name, `tap0`, in place of `IFNAME`. The check ran the `tap` plugin without `tuning`, so this chain has not run yet.
    - IPv6 has no setting of the tap's own that turns forwarding off: Linux forwards IPv6 by `net.ipv6.conf.all.forwarding` of the pod's network namespace, and an interface's `forwarding` changes nothing of that. It is 0 in a pod unless the workers or the network plugin set it (`net.core.devconf_inherit_init_net` on a worker that routes IPv6, or an option of the plugin), and the pod's program starts no VM while it reads 1. Where it does, add `"net.ipv6.conf.all.forwarding": "0"` to the `sysctl` object; where pods have no IPv6, leave it out, since the attachment then fails on it.
    - Another `cidr` in the chart needs the pod's side of it here (its first address).

    Without Multus, use the `init` variant: the run pod's init container makes the tap itself, with `NET_ADMIN` in the pod's own network namespace and the device plugin's `tun` device. That needs nothing of this item, but the pod then passes no Pod Security level but `privileged`.

5. **The API audit for the runs' namespace.** *Privilege: the API server's audit policy, the cluster's admin.* The ticket's Secret holds the run's one-time ticket for about two seconds. An audit policy that logs Secrets with their body (`Request` or `RequestResponse`) writes the ticket into the audit log. Log the runs' Secrets at `Metadata`, in a rule before your other rules (the first rule that matches decides):

    ```yaml
    apiVersion: audit.k8s.io/v1
    kind: Policy
    omitStages: [RequestReceived]
    rules:
      - level: Metadata
        namespaces: ["tinfirm-runs"]
        resources:
          - group: ""
            resources: ["secrets"]
      # ... your other rules
    ```

    On a managed cluster you do not write this policy: ask what the provider's audit logs keep of a Secret's body. A used ticket opens nothing, but one that is read before its VM uses it lets the reader pose as that sandbox. For the same reason, encrypt Secrets at rest in etcd where the cluster lets you: the ticket stays in etcd's history until it is compacted.

Not needed: any change to the kubelet, to containerd or CRI-O, a capability in the run pod's main container, or a right of the driver outside the runs' namespace.

## Build the run pod's image

Tinfirm publishes no image of a run pod, and no VM image for it to hold. You build both: the VM image with `runner/images/vm/local-disk.sh`, as for sandbox nodes, and the pod's image with `ee/runner/images/vm/pod-image.sh` of Tinfirm's source, on a Linux x86-64 machine with Docker:

```sh
# The release's runner host binary, checked against its signed SHA256SUMS
# (Install and run runner hosts), the same build as the runner pod's image.
sudo runner/images/vm/local-disk.sh ./tinfirm-runner-host ./vm-image
ee/runner/images/vm/pod-image.sh ./tinfirm-runner-host ./vm-image \
  registry.example.com/tinfirm/vm-pod:1.0.0
docker push registry.example.com/tinfirm/vm-pod:1.0.0
```

The script checks every part before it builds, and builds the image from `pod.Dockerfile` beside it:

| Part, in the image | Where it comes from | Size |
|---|---|---|
| `/tinfirm/tinfirm-runner-host` | The binary you give it: it must have `vm-pod` and be the build that the VM image holds | About 27 MiB |
| `/tinfirm/vm/firecracker` | Firecracker 1.14.2's release archive from GitHub, checked against the release's own SHA-256 file, and the binary against the `SHA256SUMS` in the archive. The jailer is left out: it cannot run in a user namespace | 3.3 MiB |
| `/tinfirm/vm/vmlinux` | The guest kernel the sandbox nodes run, `vmlinux-6.18.56-208` from Kata Containers 4.3.1's static release (an archive of about 1 GB), checked against the archive's and the kernel's SHA-256 | 39 MiB |
| `/tinfirm/vm/rootfs.ext4` | `local-disk.sh`'s VM image, checked against the SHA-256 it recorded, with its file system shrunk to what it uses plus 512 MiB (`POD_IMAGE_FREE_MIB`) | What the image uses: 2,944 MiB for one runtime's image (`IMAGE_RUNTIMES=pi`) on the node that ran; more with every runtime |
| `/licenses/` | The runner host's licences and Firecracker's | |

The image has no shell, no package manager and no other tool, and runs as user 10001 on a read-only root file system; the script runs its binary that way once to check it. It is for x86-64 only: no guest kernel is pinned for another architecture.

**Why the file system is shrunk.** `local-disk.sh` writes a sparse file of 32 GiB of which a few GiB are used. An image layer keeps no holes: a worker would unpack that file as 32 GiB of disk. The script stores only what the file system uses, and zeroes its free blocks so that the layer compresses well. Each run pod copies the image to its disk volume, leaving holes where it is empty, and grows the copy to the template's `diskMib`; the guest then grows its file system to that size at boot (`tinfirm-vm-grow-root.service`, which `local-disk.sh` installs). So every template's `diskMib` must be at least the shrunk image's size, which the script prints. The download is the compressed layer; each worker keeps the compressed layer and the unpacked image. How long the copy takes for a real image, and the guest's growing, are measured on no cluster yet: on the managed cluster, a copy of 256 MiB of data took 0.13 s from the page cache.

**The digest.** The admission policy and the driver name the image by digest alone. Take the digest that `docker push` prints on its last line, or `docker inspect --format '{{index .RepoDigests 0}}' registry.example.com/tinfirm/vm-pod:1.0.0` after the push, and set it in the values with the repository (below). Rebuild and push the image for every release, with that release's binary, and set the new digest: the chart's upgrade then changes the driver's configuration and the admission policy together.

## The chart's values

Create the host's token first ([The runner pod](helm.md#the-runner-pod)). The networkPolicy value names the API server's own addresses, which the driver calls: a NetworkPolicy cannot name the `kubernetes` Service, so list them with `kubectl get endpointslices -n default -l kubernetes.io/service-name=kubernetes`.

With Multus (`tap.mode: multus`), the run pods pass `restricted`:

```yaml
runner:
  enabled: true
  company: main
  name: kubernetes
  maxConcurrentRuns: 8
  vm:
    driver: pod
    template: sandbox-4c8g
    cluster:
      isolationStatement: the workers for runs hold nothing else
      namespace: tinfirm-runs
    pod:
      image:
        repository: registry.example.com/tinfirm/vm-pod
        digest: sha256:<the digest docker push printed>
      templates:
        sandbox-4c8g:
          vcpus: 4
          memoryMib: 8192
          diskMib: 32768
      tap:
        mode: multus
        networkAttachment: tinfirm-tap
        cidr: 172.30.0.0/30
      devices:
        kvm: devices.example.com/kvm
        tun: devices.example.com/tun
      nodeSelector:
        tinfirm.flowtivo.com/kvm: "true"
      tolerations:
        - key: tinfirm.flowtivo.com/kvm
          operator: Exists
          effect: NoSchedule
networkPolicy:
  runner:
    egress:
      apiServer:
        to:
          - ipBlock:
              cidr: 10.0.0.10/32   # the API server's address from the endpoint slice
        ports:
          - 6443
```

Without Multus (`tap.mode: init`), the same values with this `tap`, and no NetworkAttachmentDefinition:

```yaml
runner:
  vm:
    pod:
      tap:
        mode: init
        cidr: 172.30.0.0/30
```

| Value | Default | Meaning |
|---|---|---|
| `runner.vm.driver` | `digitalocean` | `pod` for this form |
| `runner.vm.template` | `sandbox` | A key of `runner.vm.pod.templates` |
| `runner.vm.cluster.isolationStatement` | empty | The operator's statement, word for word ([The isolation statement](#the-isolation-statement)). The chart does not render without it |
| `runner.vm.cluster.namespace` | `tinfirm-runs` | The runs' namespace: another than the release's, and none of the cluster's own. One release for each runs' namespace |
| `runner.vm.cluster.createNamespace` | `true` | The chart creates the namespace, with Pod Security `restricted` enforced in the `multus` variant, and only warned and audited in `init`. With `false`, create and label it yourself. Uninstalling the release deletes a namespace the chart created, with its run pods |
| `runner.vm.cluster.runServiceAccount` | `tinfirm-run` | The ServiceAccount of every run pod, with no rights and no token |
| `runner.vm.cluster.admission` | `validatingAdmissionPolicy` | The chart's ValidatingAdmissionPolicy, or `external`: your own engine (Kyverno, Gatekeeper) with the same rules, which the chart then does not render. Either way the driver checks the rules itself (below) |
| `runner.vm.cluster.quota.hard` | empty | The ResourceQuota's `hard`. Empty: `maxConcurrentRuns` plus `pool.maxReady` pods of `runner.vm.template`, with their CPU, memory, ephemeral storage and devices. The driver's room is what the quota still admits |
| `runner.vm.pod.image.repository`, `.digest` | empty | The run pod's image, by digest |
| `runner.vm.pod.templates` | none | Each size's `vcpus` (1 to 32), `memoryMib` (128 to 262144) and `diskMib` (1024 to 1048576, at least the image's root file system). The pod requests the VM's memory and 208 MiB more |
| `runner.vm.pod.tap.mode`, `.networkAttachment`, `.cidr` | `multus`, none, `172.30.0.0/30` | Where the tap comes from; the NetworkAttachmentDefinition, `name` or `namespace/name`, with `multus` only; the tap's /30, with the pod's side first and the guest second |
| `runner.vm.pod.devices.kvm`, `.tun` | none | The device plugin's resource names |
| `runner.vm.pod.nodeSelector`, `.tolerations` | none | The workers for runs (at least one label) and their taints |
| `runner.vm.pod.disk.volume`, `.storageClassName` | `emptyDir`, none | Where each run's disk lives: an `emptyDir` on the worker's disk, or `ephemeral`, a claim made for the pod and deleted with it. `disk.variant` is `copy`, the only one built |
| `runner.vm.port` | `7443` | The run pod's port, which relays to the VM's bridge |
| `networkPolicy.runner.egress.apiServer.to`, `.ports` | none, `443` and `6443` | The API server's own addresses and ports. Required with this driver |

`helm lint` and the install refuse a configuration that the driver would refuse: a missing statement, the release's own namespace for the runs, an image without a digest, a template that is not listed, a `multus` tap without its attachment, missing device names, no worker label, and, with the chart's network policies on, no API server address.

**What the chart installs** for this driver:

- the runs' namespace (unless `createNamespace: false`) and the ServiceAccount `tinfirm-run` in it;
- two network policies in the runs' namespace: `tinfirm-runs-default-deny`, which denies everything in and out for every pod, and `tinfirm-runs-allow-runner`, which admits the runner pod to the run pods' port alone;
- the ResourceQuota `tinfirm-runs`;
- the runner pod's own ServiceAccount, and a Role and RoleBinding in the runs' namespace: pods create, get, list, watch and delete; Secrets create and delete, never read or list; network policies list; the quota read by name. No ClusterRole, and no right in any other namespace;
- the runner pod's ServiceAccount token, through a projected volume that holds only the token and the cluster's CA, at `/etc/tinfirm/kubernetes-api`;
- the admission policy for the runs' namespace, two ValidatingAdmissionPolicies with their bindings: one for pods, which admits only a pod as the driver builds it (its name, the run ServiceAccount, no token, `hostUsers: false`, no host namespace or path, no pull Secret, its own ticket's Secret alone, the image by digest, the run pod's program as each container's command and the driver's environment variables alone, no privilege or added capability but `NET_ADMIN` in the `init` variant's init container, no network attachment of Multus's but the tap's and no other default network, placed by the scheduler on the workers for runs and off the runner pod's worker); one for Secrets, which admits only an immutable ticket owned by its pod;
- the runner pod's network policy, which then also lets it reach the run pods' port and the API server.

**What the chart never installs:** the worker pool and its labels and taints, the mode of `/dev/kvm`, the device plugin, Multus and the NetworkAttachmentDefinition, the audit policy, and the run pod's image. A network policy for the runs' namespace other than its two: the driver reports a third one and starts no VM (below).

## The isolation statement

The driver keeps every run pod off the runner pod's worker: a required anti-affinity against the runner pod and a node affinity that excludes its worker by name, which the admission policy enforces. It cannot see what else runs on the workers for runs, or what their kubelet's credential and cloud identity open. You state that, in the values, word for word:

```yaml
runner:
  vm:
    cluster:
      isolationStatement: the workers for runs hold nothing else
```

Write it only when it is true: the workers that `runner.vm.pod.nodeSelector` selects run the run pods and the cluster's own agents, and no other workload, not the runner pod either; and their kubelet's credential and their cloud identity open nothing else ([What an escape reaches](#what-an-escape-reaches)). Without it the chart does not render and the driver does not start; `run --isolation-level` is refused with this driver, since the statement has its one place here.

The host then reports the isolation level `machine`, hardened `no`, stated by the operator. Its page in Tinfirm shows the level as stated. `hardened: no` is fixed: a worker whose pods use user namespaces cannot have the first line of a hardened machine (no user namespaces), whatever else its admin sets. Placement follows: among `machine` hosts, a VM per run at a provider and a hardened sandbox node come before this host, and a run never goes to a weaker level when this host is busy.

## Check it

**`doctor` in the runner pod.** After the install, run the runner host's own check in its pod, which takes the pod's settings from its environment. Give it the release's `runner.maxConcurrentRuns`:

```sh
kubectl -n tinfirm exec deploy/tinfirm-runner -c runner -- \
  /usr/local/bin/tinfirm-runner-host doctor --max-concurrent-runs 8
```

Its lines for this driver:

| Line | What it says |
|---|---|
| `pod driver configuration` | `ok`: the file the chart rendered (`/etc/tinfirm/pod-driver/pod-driver.json`), the runs' namespace, the templates and the tap's mode. `FAIL` names the field that is wrong |
| `vm driver` | `ok`: the driver answered, the template keeps a sandbox VM's contract, the room the quota leaves, and what it checked (the quota, the two network policies, the run pods, "the admission policy refuses every pod it must"). `FAIL`: the driver's problems, below |
| `isolation` | `machine`, hardened `no`, stated by the operator, and what an escape from a VM reaches |

`run` makes the same check at its start and does not start while it fails: the runner pod's container exits and is started again, and its log says why (`kubectl -n tinfirm logs deploy/tinfirm-runner -c runner`).

**The driver's problems.** The driver checks the runs' namespace at its start, at each check, and before a create. The runner pod does not start while there is a problem. While it runs, the host takes no new run and reports the problem, until a check finds it gone, whenever the driver can make no pod: with a problem of a network policy, of the admission check or of the runner pod's node, for which the driver refuses every pod and reports no room whatever the quota holds; and with a quota that is missing, that it cannot read, or that has no room while no run pod holds it.

| Problem | What it means, and who fixes it |
|---|---|
| `there is no NetworkPolicy tinfirm-runs-default-deny (or -allow-runner) in the runs' namespace` | The chart's policy is missing. The operator: upgrade the release, which installs it again |
| `the NetworkPolicy … is not the one expected: …` | Someone changed it; the text names each field. The operator: upgrade the release |
| `the runs' namespace … holds the NetworkPolicy …, which is not the chart's` | A third policy. Network policies add up, so it may open the run pods. The cluster's admin deletes it |
| `there is no ResourceQuota tinfirm-runs …` | The quota is missing. The operator: upgrade the release |
| `no room in the runs' namespace: the ResourceQuota … holds no more run pods (…), and no run pod holds its room: …` | The quota has no room for one more run pod, and no run pod is what fills it: it is too small for one run pod of the template, or something else in the namespace counts against it. The operator: raise `runner.vm.cluster.quota.hard`. A quota that run pods have filled is no problem: the driver then reports no room and checks the admission policy before the next create, and a runner pod that starts again beside its own run pods starts |
| `the driver may not read …: give its ServiceAccount the right` | The Role or its binding is missing or changed. The operator: upgrade the release |
| `cannot reach the API server …` | The runner pod's network policy does not let it reach the API server. The operator: set `networkPolicy.runner.egress.apiServer` to the API server's own addresses and ports |
| `the run pod tinfirm-vm-… has found no node for … s: …` | The scheduler places no run pod, for 60 seconds now; the scheduler's message says why (no worker with the label, the taint not tolerated, no free `kvm` or `tun`, not enough memory). The cluster's admin: the pool, its labels and taints, the device plugin |
| `the cluster refuses the correct run pod: …` | The admission policy, the namespace's Pod Security, or another engine refuses the pod the driver would create. The message names the rule. Usually the operator: the image's digest or a template that the admission policy does not know yet (upgrade the release); with the `init` variant, a namespace that enforces a Pod Security level |
| `the cluster accepts a run pod with …: the admission policy is missing or incomplete` | The cluster admits a pod it must refuse, such as one with another ServiceAccount, a token, another Secret, a host path, another image or command, or one more network attachment. With `admission: validatingAdmissionPolicy`, the policy is not in the cluster (a cluster older than 1.30, or a `helm template` that did not see the API); with `external`, your engine's rules lack that case. The cluster's admin |
| `the runs' namespace … does not exist` | The operator: `createNamespace: true`, or create it |
| `the runner pod's node is unknown (TINFIRM_NODE_NAME)` | The chart sets it from the downward API; a pod without it is not the chart's. The operator |
| `the pod driver cannot reach the cluster's API: cannot read the token file …` | The pod has no projected token: it is not the chart's runner pod, or the chart's driver is not `pod` |

The admission check is a set of server-side dry runs: the correct pod, which must be admitted, and each pod that must be refused, one deviation each. It needs no right outside the runs' namespace, and works the same with your own engine.

**When a run pod stops.** The pod's program checks the pod before it starts the VM, and writes why it stopped to the pod's termination message. The runner host reports it as the VM's failure in its log; while the pod exists, `kubectl -n tinfirm-runs get pod <name> -o jsonpath='{.status.containerStatuses[0].state.terminated.message}'` shows it.

| The program stops with | Who fixes it |
|---|---|
| `the pod runs in the worker's user namespace: its specification must set hostUsers: false` | The cluster's admin: the cluster does not give the pod a user namespace (the feature gate, the kernel, the runtime) |
| `vm-pod runs with capabilities (…)` | Something changed the pod's capabilities; the admission policy should have refused it. The cluster's admin |
| `there is no /dev/kvm (or /dev/net/tun): the pod did not get the device plugin's … resource` | The cluster's admin: the device plugin on that worker, and the resource names in `runner.vm.pod.devices` (the operator) |
| `/dev/kvm is refused to the pod's user (…): the cluster's admin sets mode 0666 on the workers' /dev/kvm` | The cluster's admin: item 2 above, on that worker |
| `… the network attachment (Multus, the CNI tap plugin with static IPAM) gives the pod tap0 with that address` | With `multus`: the cluster's admin: the NetworkAttachmentDefinition, its name in `tap.networkAttachment` and its address, the first of `tap.cidr` |
| `… the init container tap makes tap0 with that address` | With `init`: the init container did not make the tap; its own message says why |
| `cannot make the tap tap0 (TUNSETIFF): the init container needs NET_ADMIN` | With `init`: the cluster's admin, if another engine strips the capability |
| `tap0 would forward the guest's packets out of the pod (net.ipv4.conf.tap0.forwarding is 1): …` | With `multus`: the cluster's admin adds the `tuning` plugin with that sysctl to the attachment. With `init`: report it; the init container sets it to 0 |
| `tap0 would forward the guest's IPv6 packets out of the pod (net.ipv6.conf.all.forwarding is 1): …` | The cluster's admin. With `multus`: add `"net.ipv6.conf.all.forwarding": "0"` to the `tuning` plugin's sysctl in the attachment. With `init`: the pod cannot change it, so the workers or the network plugin must leave it 0 in a pod's network namespace |
| `the VM image /tinfirm/vm/rootfs.ext4 is … MiB, larger than the template's disk of … MiB` | The operator: a template's `diskMib` at least the image's size |
| `cannot read the VM image …`, `cannot start /tinfirm/vm/firecracker` | The operator: the image is not one `pod-image.sh` built |
| `the VM ended: firecracker exited with …` | The VM stopped by itself; the runner host's log has the run |

## What an escape reaches

An agent that escapes its VM is the unprivileged user of its run's pod, in a user namespace of its own. That user has no capability, runs under the runtime's seccomp profile and Firecracker's own, and has a read-only root file system and the pod's memory limit. Its user IDs on the worker are a range of that pod's alone, so it cannot read another run's disk or memory, or signal its processes. The pod has no ServiceAccount token, and its ticket's Secret is gone two seconds after its start. The two network policies keep it from the API server, the kubelets, the cloud's metadata address and every other pod; only its own worker still reaches it.

A flaw in KVM, or in the kernel's user namespaces, reaches the worker, which holds:

- **the kubelet's credential.** With the NodeRestriction admission plugin, which most clusters run (check that yours does), it reads the Secrets and tokens of the pods bound to that worker and of no other. On a worker for runs, those are the other run pods' tickets, each for its two seconds, and the cluster's own agents' tokens (the network plugin, Multus, the device plugin). Read what those agents may do: Multus's ClusterRole, for example, is the most a takeover of such a worker adds;
- **the worker's cloud identity**, where it has one: whatever your cloud lets that worker do. It must open nothing but the image's registry and the cloud's own node needs;
- **the other runs on that worker**: their prompts, files and what they hand back, which the runner pod then pushes to each run's branch.

That is why the workers hold nothing else, and why the runner pod, with every credential, runs elsewhere. Where these conditions do not hold, do not make the statement: use sandbox nodes outside the cluster, which hold no kubelet credential at all.

## Limits and what is not built

- **The run pod's image is yours to build** with `pod-image.sh`, for every release; Tinfirm publishes none. It is for x86-64.
- **No test on a cluster yet.** The driver, the chart's objects, the admission policy and the pod's program have run against a fake API alone. Not shown: a run end to end, a run pod on another worker than the runner pod, a worker's restart or replacement, the guest's way out (that a guest's packet to an outside address is dropped), the Multus chain with `tuning`, the init container's settings under a real runtime, the admission policy's rules in a real API server, how long the disk's copy and the guest's growing take for a real image, and a runner pod restarted in the middle of runs.
- **OKD and OpenShift are not verified** for this driver. ADR-0115 describes what OKD would need (CRI-O's `device_ownership_from_security_context`, the SELinux boolean `container_use_devices`, the tap's SELinux label, and a project per run); none of it is built or tried with the driver.
- **The disk is a copy of the image**, made at each pod's start (`disk.variant: copy`). A clone and a small disk over the read-only image are not built: no volume of a pod could clone on the managed cluster that was checked.
- **No hardening mark:** the host reports `hardened: no`.
- **Several runner hosts on one runs' namespace** are not supported by the chart: one release for each runs' namespace.
