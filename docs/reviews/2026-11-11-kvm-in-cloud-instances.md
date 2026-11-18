# KVM in a cloud's instances: where a sandbox node can run

- Date: 2026-11-11
- For: [ADR-0112](../adr/0112-no-credential-on-a-sandbox-machine.md), decision 9. A sandbox node runs Firecracker VMs and needs KVM. As a cloud's instance it needs bare metal, or an instance type with nested virtualisation.
- Source: each provider's own documentation, as read on 2026-11-11. These lists change; read the provider's page before choosing an instance type.

| Provider | KVM in an instance |
|---|---|
| AWS | On these Intel instance types, turned on per instance: M7i, M7i-flex, M8i, M8id, M8i-flex; C7i, C7i-flex, C8i, C8id, C8i-flex; R7i, R7iz, R8i, R8id, R8i-flex, X8i; I7i, I7ie. KVM and Hyper-V are the supported hypervisors in the instance. No AMD or Graviton type is listed; for those, bare-metal instances only |
| Google Cloud | On Intel machine types, and on N4D among the AMD ones, with Linux KVM as the only supported hypervisor in the VM. Not on E2, on memory-optimised VMs, on Arm, or on other AMD VMs. Google names a loss of 10 % or more for CPU-bound work, and possibly more for input and output |
| Azure | On the sizes whose page says "Nested Virtualization: Supported". Read for ADR-0112: Dsv5, Dsv6 and Esv5 (Intel), Dasv5 and Dasv6 (AMD). On a D4s_v6 a jailed Firecracker VM with Docker in the guest ran as on bare metal (an experiment that is not in the repository). AKS's own sandboxed pods run on Microsoft's hypervisor, and their workers have no `/dev/kvm` |
| DigitalOcean | None (the [Kubernetes runner analysis](2026-10-15-kubernetes-runner-analysis.md), §6): a VM per run only |

None of the three large clouds gives KVM in an ARM instance, so a sandbox node on ARM takes bare-metal hardware.
