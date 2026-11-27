# Sandbox nodes on your own machines

This page is for the people who run a company's runner hosts on plain Linux machines and want each agent's sandbox to be a VM on a machine that holds no credential. A **control host** holds the credentials and runs no agent code; its **sandbox nodes**, machines with KVM, run the VMs and hold nothing. Two Ansible plays in `deploy/ansible` of Tinfirm's source install both. On Kubernetes, the chart's runner pod is the control host instead: [With sandbox nodes](helm.md#with-sandbox-nodes).

!!! warning "Not ready for production"

    The two plays ran on machines once, on 2026-11-20 (Ubuntu 24.04), and ten conversations with real runtimes ran through the node they set up. The role hardens a node by default, and that hardening ran on one node on 2026-11-22, with a real conversation through it ([The node's hardening](#the-nodes-hardening)). Tinfirm publishes no VM image: you build it ([What you need](#what-you-need)). Use this form to prepare and to test, and a runner host of its own or DigitalOcean's driver for production until then.

## How it works

| | Control host | Sandbox node |
|---|---|---|
| Runs | `tinfirm-runner-host run --executor vm --vm-driver node` | `tinfirm-node-agent`: an unprivileged listener and a root helper, which starts each VM's Firecracker under its own jailer |
| Holds | The host's token, the runs' git and model tokens, the node driver's client key | No credential. While a run lasts, its files, its prompt and its bridge |
| Needs | Linux 5.19 or later with Landlock, git. No Docker, no KVM, no runner image | KVM, Linux 5.14 or later, cgroup v2. No Docker |
| Tier | 0, as a company runner host | 1: a lower tier with the same administrators |
| Network | Reaches the server, the code hosts, the model gateway, and each node's ports. Admits nothing from the nodes | Admits the control host to its agent's port and to the ports where it publishes the VMs, and SSH from one management address. Opens no connection |

The control host connects to each node's agent over TLS, with each side's certificate pinned by its SHA-256, and asks for a VM. The node publishes each VM's bridge on a port of its own address and forwards it to that VM alone. The control host then dials the bridge, checks that the VM reaches nothing, and sends the run. The host reports the isolation level `machine`.

## Who administers a node

A node holds no credential, so whoever takes one over reaches the runs on it and what they hand back, which the control host then pushes to each run's branch, but no account. That makes a node a tier-1 asset:

- Only the people who administer the control host administer its nodes. Give SSH keys and sudo on a node to them alone; the role cannot enforce it.
- The node's firewall admits SSH from your one management address. The play stops when its own connection comes from another address.
- Replace each node's image on a schedule. Everything the role installs comes from its variables, and nothing is kept on the node that a new image would lose, but the node's TLS identity, which the role makes again ([Replace a node](#replace-a-node)).

## What you need

| | |
|---|---|
| Ansible | ansible-core 2.15 or newer on the machine you run the plays from |
| Machines | Ubuntu 24.04 on x86-64 is the only system a node has run on. The roles also take Debian and the RHEL family, untested |
| A node that is a VM | Nested virtualisation, so that `/dev/kvm` exists in it. VMware: "Expose hardware assisted virtualization to the guest OS"; Proxmox and KVM: the CPU type `host`. In a cloud: bare metal, or an instance type with nested virtualisation |
| A node from a cloud image | cloud-init must not run at the node's starts. It asks the platform's metadata service, which the node's firewall drops, and the node then does not finish its start. The node's play refuses such a node; with `tinfirm_sandbox_node_cloud_init: disable` it switches cloud-init off for good, and what cloud-init set up at the first start stays. A cloud's own guest agent on the machine is cut off too ([Firewalls](#firewalls)) |
| The binaries | `tinfirm-runner-host` and `tinfirm-node-agent` from one release, which the roles check against its signed `SHA256SUMS` ([Install and run runner hosts](runner-hosts.md#install)) |
| The VM image | Built by `runner/images/vm/local-disk.sh` of Tinfirm's source with the control host's own binary, on a machine with Docker that is not a node. Tinfirm publishes none: built with every runtime, the image holds Claude Code, which Tinfirm does not redistribute ([ADR-0112](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0112-no-credential-on-a-sandbox-machine.md), decision 4) |
| A node's first run | The release, GitHub (Firecracker 1.14.2, Kata Containers 4.3.1 for the guest kernel) or your mirrors of them. After it, the node reaches nothing |

Build and compress the image, and note its SHA-256:

```sh
sudo runner/images/vm/local-disk.sh /path/to/tinfirm-runner-host ./out
zstd -T0 ./out/tinfirm-vm.img -o tinfirm-vm.img.zst
sha256sum tinfirm-vm.img.zst
```

## Set up a control host and its nodes

1. **Register a company runner host** in Tinfirm (**Runner hosts**, **Register a runner host**), and keep its token in Ansible Vault.

2. **Write the inventory**, with a group `tinfirm_control_hosts` and a group `tinfirm_sandbox_nodes`. `deploy/ansible/README.md` has an example and every variable. Leave the two fingerprints empty for now:

    ```yaml
    tinfirm_control_hosts:
      hosts:
        control-1.internal:
      vars:
        tinfirm_runner_host_server_url: https://tinfirm.example.com
        tinfirm_runner_host_token: "{{ vault_tinfirm_runner_token }}"
        tinfirm_runner_host_binary_url: https://github.com/FLOWTIVO/tinfirm/releases/download/v1.0.0/tinfirm-runner-host-1.0.0-x86_64-unknown-linux-musl.tar.gz
        tinfirm_runner_host_max_concurrent_runs: 2
        tinfirm_runner_host_vm_template: sandbox-2c4g
        tinfirm_runner_host_nodes:
          - name: node-1
            address: 10.0.0.20:7700
            nodeSha256: ""
            templates: [sandbox-2c4g]
            publishedPorts: {first: 20000, last: 20001}

    tinfirm_sandbox_nodes:
      hosts:
        node-1.internal:
          tinfirm_sandbox_node_address: 10.0.0.20
      vars:
        tinfirm_sandbox_node_control_host: 10.0.0.5
        tinfirm_sandbox_node_management_address: 10.0.0.9
        tinfirm_sandbox_node_client_sha256: ""
        tinfirm_sandbox_node_binary_url: https://github.com/FLOWTIVO/tinfirm/releases/download/v1.0.0/tinfirm-node-agent-1.0.0-x86_64-unknown-linux-musl.tar.gz
        tinfirm_sandbox_node_image_src: files/tinfirm-vm.img.zst
        tinfirm_sandbox_node_image_checksum: "sha256:…"
        tinfirm_sandbox_node_max_vms: 2
        tinfirm_sandbox_node_templates:
          sandbox-2c4g: {vcpus: 2, memoryMib: 4096, snapshotMib: 8192}
    ```

    `tinfirm_sandbox_node_control_host` is the control host's address as the nodes see its connections. Keep `tinfirm_runner_host_max_concurrent_runs` at most the sum of the nodes' `tinfirm_sandbox_node_max_vms`: `run` refuses to start while the nodes, every one of them answering, have fewer free VMs. While a node does not answer, `run` starts on the others with a warning that names it, and takes only as many runs at once as they have room for until the node answers again: a run beyond that waits in the queue, and the control host's page shows the lower number.

3. **Run the control host's play.** It installs the host without Docker, logs it in, makes its client certificate on the host, and shows its fingerprint. With no node fingerprint yet, it stops before it starts the host. It refuses a machine with Docker, containerd or Kata, or whose service user is in the `docker` group ([A machine with Docker](#a-machine-with-docker)):

    ```sh
    ansible-playbook -i inventory.yml deploy/ansible/control-host.yml --ask-vault-pass
    ```

4. **Run the nodes' play** with that fingerprint as `tinfirm_sandbox_node_client_sha256`. Each node ends with the agent's own check of every template, and fails on a problem; then it shows the node's fingerprint and its entry for the control host:

    ```sh
    ansible-playbook -i inventory.yml deploy/ansible/sandbox-node.yml
    ```

5. **Run the control host's play again** with each node's fingerprint as its `nodeSha256`. It asks every node through the driver, as `run` does at its start, checks that the host's user reaches no Docker socket, and only then starts the host. The host shows **Online** on its page. To check a control host by hand later, run `tinfirm-runner-host doctor --executor vm --vm-driver node --vm-template <template> --max-concurrent-runs <N>` as the service's user, with the service's environment: it checks the driver's configuration and keys, asks every node as `run` does at its start, and does not look for Docker.

6. **Run both plays again.** A second run changes nothing.

## Take a node out

The host's driver uses exactly the nodes that had a `nodeSha256` at the last run of the control host's play. To stop trusting a node, empty its `nodeSha256` (or remove the node) and run the play: it rewrites the driver's file without the node and reloads the host, which drains. The runs in progress finish, those on that node too, and the host starts again without it. A node without a fingerprint is named in the play's output as waiting, and a new node waits the same way while the host serves the others. With no node pinned at all, the play stops the host, which cancels its runs.

When the node may have been taken over, do not let its runs finish: after the play, run `sudo systemctl restart tinfirm-runner-host` on the control host, which cancels them at once.

## Replace a node

1. Empty the node's `nodeSha256` and run the control host's play: the host drains, the runs on that node finish, and it starts again without the node.
2. Replace the node's image and run the nodes' play on it. It makes a new TLS identity and shows a new fingerprint.
3. Set its new fingerprint as its `nodeSha256` and run the control host's play, which puts the node back. With the old fingerprint, the driver would refuse the node, whose certificate is no longer the pinned one, and the host would run on its other nodes.

If a node may have been taken over, review the branches and artifacts of the runs it served and discard their sessions: what it handed back outlasts it.

To replace the control host's certificate, remove `/etc/tinfirm/node-driver/client.crt` and `client.key` on it, run its play, which shows a new fingerprint and then fails because no node accepts it yet, set that fingerprint on every node, run the nodes' play, and run the control host's play again. No run starts on a node in between.

## A machine with Docker

A control host runs no Docker socket and no hypervisor: its user would otherwise be root on the machine that holds every run's credentials. The control host's play refuses a machine where Docker, containerd or Kata is installed, or where the service user is in the `docker` group, before it changes anything, and does not remove them itself. A Kata runner host is not converted into a control host in place: it becomes a sandbox node with a new image, and the control host runs on another machine ([ADR-0112](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0112-no-credential-on-a-sandbox-machine.md), decision 7). The runner host that runs on such a machine keeps running with its runs, since the play stops before it touches it: drain it with `sudo systemctl reload tinfirm-runner-host`, wait until it runs again with no run, stop it, and then replace the machine's image.

## Firewalls

- **On each node**, the role loads the node's own table, `inet tinfirm_node`. It admits the control host to the agent's port and to the published ports, SSH from the management address, and the node's DHCP lease, and drops everything else, every connection the node itself opens included. Root on the node can remove it. It cuts off cloud-init, and a cloud's own guest agent on the machine, which stays active and cannot reach its platform: what the platform does through that agent, such as running a command on the machine or its extensions, is expected to stop working. That was inferred on a node, not tried.
- **On the control host**, the role sets no firewall. Let it reach each node's agent port (7700) and published ports, and admit nothing from the nodes' network.
- **Around the nodes**, your network keeps them from reaching the server, the model gateway, the code hosts, the control host, the other nodes and a cloud's metadata service. That is the guard against root on a node.

## The node's hardening

The nodes' play hardens a node beyond the jail ([ADR-0113](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0113-levels-of-isolation-and-placement.md), decision 5). Each line has a switch of the role, and all are on by default. **This hardening has run on one node only.** On 2026-11-22, on one node (Ubuntu 24.04, kernel 7.0, a cloud's image), the play, the node agent's 13 checks, the unit that sets kexec, lockdown and module loading at each boot, and the VMMs' AppArmor profile in both modes held, and the profile logged nothing over the VMs' life (the outputs are not in Tinfirm's repository). Later that day, with a server and the profile in enforce mode, a real conversation and a run of the tests' stand-in agent went through a hardened VM, and so did a VM start that failed after its VMM started, with no denial logged. The server stored the control host's mark `yes`. A line set back by hand on the node while the host ran reached the server as `no` by a new hello, and `yes` again once it was set back. Not shown: a new VM going to a hardened node first (there was one node), the failing line in the host's warning (the warning carries it in a field, but the round recorded the message alone; `doctor` named the line), and a second distribution or kernel. Three of its settings last until a reboot, so try it first on a node that you can reach without SSH, or leave it out.

- **What it sets:** kernel settings that take BPF and io_uring from the node's users and stop the node at a kernel oops; kexec off, kernel lockdown at `integrity`, and no kernel module loaded after the boot; no set-user-ID program that others may run, except `sudo` for its group; and an AppArmor profile for the VMMs.
- **What it costs:** an oops ends every run on the node, and with the role's default (`tinfirm_sandbox_node_kernel_panic: 0`) the node then stays down until someone restarts it, also on an image that would have rebooted by itself after a panic. After the boot no module loads, so a firewall rule or a tool that needs a new one fails until a reboot.
- **The mark:** the node agent checks each line. The control host reports `hardened: yes` to the server only while every node that offers the template reports it, and a new VM goes to a hardened node first. A line that does not hold lowers the mark and stops nothing: `tinfirm-runner-host doctor` and the host's log name it.
- **With the defaults, a node reports `hardened: yes`** when every line holds. The VMMs' AppArmor profile is loaded in enforce mode by default since 2026-11-22. Before, it was loaded in complain mode, and a node with the defaults reported `hardened: no` for the line `vmm-mac` alone.
- **The evidence, and its limit:** on one node, from an Ubuntu 24.04 cloud image, a real conversation, a run of the tests' stand-in agent and a VM start that failed after its VMM started ran under the profile in enforce mode, and the kernel logged no denial for it. In complain mode, VMs logged nothing that the profile would have refused over their whole life. That is one distribution and one kernel. The role sets the profile up on Ubuntu and Debian with AppArmor on, and has run on Ubuntu 24.04 alone.
- **If VMs stop starting after the change,** look in the node's kernel log for `apparmor="DENIED"` lines of `tinfirm-vmm` (`journalctl -k --grep 'apparmor="DENIED"'`). Set `tinfirm_sandbox_node_harden_mac: complain`, run the play again, and report the lines. The node then reports `hardened: no`, for `vmm-mac`.
- **`complain` stays a value,** for a first run on a distribution or kernel the profile has not run on. The profile then refuses nothing and logs what it would refuse, as `apparmor="ALLOWED"` lines. Set `enforce` once that log is clean.
- **A Red Hat family node is as before.** It has SELinux and no AppArmor, and the role refuses `complain` and `enforce` there: set `tinfirm_sandbox_node_harden_mac: "off"`. The node reports `hardened: no`, since SELinux for the VMMs is not built.
- **To leave it out,** set `tinfirm_sandbox_node_harden: false`. Each line also has a switch of its own.

What each switch does and what it costs is in the role's [README](https://github.com/flowtivo/tinfirm/blob/main/deploy/ansible/README.md#the-hardening).

## What the roles do not do yet

- SELinux for the VMMs on a node of the Red Hat family: such a node reports `hardened: no`.
- A runner host and the node agent on one machine. That form is set aside and not built: the node agent refuses a control host on its own machine, so the control host runs on another machine.
- A full check from the node itself that it reaches nothing. The node agent tries one thing at each `verify`: a datagram from its listener to the control host, which the node's own table must refuse. Where it gets through, the node reports a problem and the control host places no VM on it. This try ran on one real node on 2026-11-22: refused with the node's table loaded; named with the table deleted, and with a table of the right name that lets the datagram pass, where a new run waited until the real table was back and then went on by itself. It does not try the server, the model gateway, the code hosts, a metadata service or the internet, and it does not run as root. So check the rest yourself after the play, as root on the node: a connection to the control host, to `1.1.1.1:443` and to `169.254.169.254:80` must each time out. A refused connection fails the check too, because the refusal came from beyond the node.
- An upgrade of a node in place while VMs run. Replace its image instead.
