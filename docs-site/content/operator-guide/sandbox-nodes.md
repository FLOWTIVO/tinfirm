# Sandbox nodes on your own machines

This page is for the people who run a company's runner hosts on plain Linux machines and want each agent's sandbox to be a VM on a machine that holds no credential. A **control host** holds the credentials and runs no agent code; its **sandbox nodes**, machines with KVM, run the VMs and hold nothing. Two Ansible plays in `deploy/ansible` of Tinfirm's source install both. On Kubernetes, the chart's runner pod is the control host instead: [With sandbox nodes](helm.md#with-sandbox-nodes).

!!! warning "Not ready for production"

    No conversation has run through a sandbox node, and the two plays have not run on a machine yet. The node agent makes and deletes VMs: that ran on one node with KVM, driven by the runner host over TLS. The node's hardening and the published VM image are planned ([ADR-0112](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0112-no-credential-on-a-sandbox-machine.md)). Use this form to prepare and to test, and a runner host of its own or DigitalOcean's driver for production until then.

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
| The binaries | `tinfirm-runner-host` and `tinfirm-node-agent` from one release, which the roles check against its signed `SHA256SUMS` ([Install and run runner hosts](runner-hosts.md#install)) |
| The VM image | Built by `runner/images/vm/local-disk.sh` of Tinfirm's source with the control host's own binary, on a machine with Docker that is not a node. Tinfirm does not publish it yet |
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

    `tinfirm_sandbox_node_control_host` is the control host's address as the nodes see its connections. Keep `tinfirm_runner_host_max_concurrent_runs` at most the sum of the nodes' `tinfirm_sandbox_node_max_vms`: `run` refuses to start while the nodes have fewer free VMs.

3. **Run the control host's play.** It installs the host without Docker, logs it in, makes its client certificate on the host, and shows its fingerprint. With no node fingerprint yet, it stops before it starts the host:

    ```sh
    ansible-playbook -i inventory.yml deploy/ansible/control-host.yml --ask-vault-pass
    ```

4. **Run the nodes' play** with that fingerprint as `tinfirm_sandbox_node_client_sha256`. Each node ends with the agent's own check of every template, and fails on a problem; then it shows the node's fingerprint and its entry for the control host:

    ```sh
    ansible-playbook -i inventory.yml deploy/ansible/sandbox-node.yml
    ```

5. **Run the control host's play again** with each node's fingerprint as its `nodeSha256`. It asks every node through the driver, as `run` does at its start, and starts the host. The host shows **Online** on its page.

6. **Run both plays again.** A second run changes nothing.

## Replace a node

1. Take the node out of `tinfirm_runner_host_nodes` and run the control host's play: the host drains, the runs on that node finish, and it starts again without the node.
2. Replace the node's image and run the nodes' play on it. It makes a new TLS identity and shows a new fingerprint.
3. Put the node back with its new `nodeSha256` and run the control host's play. Until then, the driver refuses the node, whose certificate is no longer the pinned one, and the host runs on its other nodes.

If a node may have been taken over, review the branches and artifacts of the runs it served and discard their sessions: what it handed back outlasts it.

To replace the control host's certificate, remove `/etc/tinfirm/node-driver/client.crt` and `client.key` on it, run its play, which shows a new fingerprint and then fails because no node accepts it yet, set that fingerprint on every node, run the nodes' play, and run the control host's play again. No run starts on a node in between.

## Firewalls

- **On each node**, the role loads the node's own table, `inet tinfirm_node`. It admits the control host to the agent's port and to the published ports, SSH from the management address, and the node's DHCP lease, and drops everything else, every connection the node itself opens included. Root on the node can remove it.
- **On the control host**, the role sets no firewall. Let it reach each node's agent port (7700) and published ports, and admit nothing from the nodes' network.
- **Around the nodes**, your network keeps them from reaching the server, the model gateway, the code hosts, the control host, the other nodes and a cloud's metadata service. That is the guard against root on a node.

## What the roles do not do yet

- The hardening of a node beyond the jail ([ADR-0113](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0113-levels-of-isolation-and-placement.md), decision 5): kernel lockdown, kernel modules disabled after boot, an AppArmor profile for the VMMs. It is not specified yet, and the host reports its nodes as not hardened.
- A runner host and the node agent on one machine. The node agent refuses a control host on its own machine until that form has its own firewall.
- A check from the node itself that it reaches nothing. Try it yourself after the play, as root on the node: a connection to the control host, to `1.1.1.1:443` and to `169.254.169.254:80` must each time out.
- An upgrade of a node in place while VMs run. Replace its image instead.
