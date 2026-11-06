# ADR-0096: A VM per run, through a driver

- Status: Accepted
- Date: 2026-10-16
- Accepted by: the owner, 2026-10-27, with the fixes of the gaps it left open ([ADR-0101](0101-closing-the-elastic-runners-gaps.md))
- Amended by: [ADR-0100](0100-elastic-runners-in-pro.md): A VM per run, with its drivers and the VM image's recipe, is part of elastic runners, Pro's and Enterprise's (`Feature.ELASTIC_RUNNERS`); its code is under `ee/`, the runner host's under `ee/runner/`.
- Amended by: [ADR-0101](0101-closing-the-elastic-runners-gaps.md): The VM image's build is checked against the host's when the VM's bridge connects (`--vm-image-version warn|refuse`), and a restarted host takes back the runs of its VMs, which it holds and dials again.
- Sources: [ADR-0095](0095-sandbox-bridge.md) (the sandbox bridge), [ADR-0006](0006-runner-hosts-and-isolation-tiers.md), [ADR-0025](0025-kata-sandbox-runtime.md), [ADR-0044](0044-sandbox-egress-proxy.md), [ADR-0062](0062-runner-host-rebuilds-its-images.md), [ADR-0064](0064-skills-library.md); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §6.1, §6.2 and §9, and its decisions D1, D2, D4, D8, D9, D10 and D12; [threat model](../threat-model.md) TB6, TB7, 6.15, 6.16, R22, R24, R36, R57
- Changes: a third executor of the runner host, `run --executor vm`, with a driver protocol, a driver for DigitalOcean, `vm-boot` in the VM, and the recipe of the VM's image. The runner protocol between the server and the host does not change.

## Context

- Today a runner host is a machine with Docker, and with Kata for a VM per sandbox. It holds its CPUs and memory whether runs come or not, and Kata needs `/dev/kvm`, so the machine must be bare metal or a VM with nested virtualisation.
- On DigitalOcean, which D1 chose, Droplets have no supported nested virtualisation, so Kata does not run there. A Droplet is a VM of its own, though: one per run gives the agent the boundary that a Kata VM gives today, with DigitalOcean's hypervisor in place of one nested in the runner host's VM.
- The sandbox bridge (ADR-0095) carries the adapter's stdio, the tunnels to the host's listeners and the files, and needs neither Docker nor a shared machine. Only the direction of its connection has to change: a sandbox VM whose firewall lets nothing out cannot dial the host.
- Every provider differs in what it calls a firewall, an identity and a network (the analysis §6.2), so the security has to be checked at run time, the same on every provider, and not trusted from the setup.

## Decision

**The executor.** `run --executor vm --vm-driver COMMAND --vm-template NAME` (`TINFIRM_RUNNER_EXECUTOR`, `_VM_DRIVER`, `_VM_TEMPLATE`).

- For each run (each epoch of a run), the host registers the run with its bridge listener, asks the driver to create a VM from the template with the run's **user data**, waits until the driver reports the VM running with an address, and dials the bridge there (`--vm-port`, 7443). When the run ends, the host takes the work back over the bridge, closes it, and asks the driver to delete the VM. A VM is never reused.
- A run whose VM does not come up and connect within `--vm-start-timeout` (900 s), or that the provider fails, fails with `INFRA`, and its VM is deleted.
- The host needs no Docker. Every agent runs over the bridge, and the egress proxy is on, with no option to turn it off (D4): through its tunnel it is the VM's only way to the network.
- The isolation tier is `microvm`. `--sandbox-docker` needs no Kata here, since the VM is the boundary, and `--sandbox-runtime` is refused: this host runs no Docker runtime.
- The skills reach the VM over the bridge, so the host lists `run.skills` (ADR-0064).
- A Codex ChatGPT login cannot reach a VM: it is a mount, refreshed in place on the host (ADR-0095). Such a run fails.
- `hello` names `docker` as the executor, which is the only one the protocol knows, and each runtime's version as `VM template NAME`: the VM's image holds the runner images.

**The host dials in.** The VM's firewall lets in only the runner, to the bridge's port, and lets nothing out.

- The bridge listens in the VM (`TINFIRM_BRIDGE_LISTEN`) and waits up to 15 minutes for the host's first connection. The host dials it, and dials again whenever the connection is lost.
- The TLS roles stay as they are: the host is the TLS server, and the bridge accepts only the host's pinned certificate. The ticket and the session key work as in ADR-0095, so a connection that is not the host's opens nothing.

**The user data** is JSON: `{"tinfirm": 1, "image": …, "env": {…}}`.

- `env` holds the bridge's settings (where it listens, the run, the one-time ticket and the pin) and the sandbox's own (`TINFIRM_SANDBOX_*`, and the proxy variables of the agent's own dockerd).
- Anything in the VM can read the user data from the metadata service, so it holds no credential (contract C5). The ticket is used up before the agent starts, and the credentials travel over the bridge, in `start`.

**In the VM**, `tinfirm-runner-host vm-boot` runs once at boot:

- It reads the user data from the metadata service, or from a file on other providers.
- It takes only the image and the variables above, and refuses any other.
- It runs the run's runner image, which the VM's image holds and never pulls, on the VM's network, with the bridge binary mounted read-only. The container runs as the agent's user without capabilities, or, when the agent has its own Docker, as root and privileged, as under Kata (ADR-0025). Every value goes into the container by name, never on a command line.
- The bridge starts the image's own command (`TINFIRM_BRIDGE_COMMAND`, which `vm-boot` reads from the image) when the host's `start` names none.

**The probe.** Before any file or credential goes into the VM, the host asks the bridge to try what the VM must not reach:

- the internet by address (`1.1.1.1:443`, `8.8.8.8:53`, `[2606:4700:4700::1111]:443`) and by name (`example.com`);
- the credential paths of the metadata services that hand one out (AWS, GCP, Azure);
- and what the operator adds with `--vm-probe` (`tcp:HOST:PORT`, `dns:NAME`, `http://HOST[:PORT]/PATH`): a database, another network.

Each check gets 3 s, and they run at once. Any that gets through deletes the VM and fails the run with `INFRA`. The probe is the same on every provider, whatever its setup promises.

**The driver protocol.** A driver is a program of its own (D9). The host starts it on first use and again after it exits, and speaks JSON-RPC 2.0 with it on stdin and stdout, one message per line; the driver may answer in any order. What the driver writes to stderr goes to the host's log.

| Method | Parameters | Result |
|---|---|---|
| `hello` | `protocol` | `protocol` (1), `driver`, `version` |
| `verify` | `template` | `problems` (what breaks the contract C1 to C7 in the template's setup), `maxVms` (how many more VMs the account may have), `checked` (for the log) |
| `create` | `template`, `name`, `userData`, `labels` | `id` |
| `describe` | `id` | `id`, `name`, `state` (`starting`, `running`, `gone` or `failed`), `address`, `labels` |
| `delete` | `id`, `labels` | nothing; a VM that is gone is no error, and one without each of the labels is refused |
| `list` | `labels` | `vms`: those with each of the labels |

- The labels are `tinfirm-host` (the host's id at the server) and `tinfirm-run` (the run).
- At start, `run` calls `hello` and `verify`. It refuses a template with problems, and a `--max-concurrent-runs` above `maxVms` (D12).
- After each connection to the server, the host lists its VMs and deletes those of no live run: the leftovers of a crash, or of a delete that failed. A delete is tried three times, and a run's task that ends without one still deletes its VM.

**DigitalOcean.** `--vm-driver digitalocean` is this binary's own driver, `vm-driver digitalocean`. Its configuration (`TINFIRM_DO_CONFIG`, `/etc/tinfirm/digitalocean.json`) names the API token's file and the templates: a region, a size, a private snapshot, a VPC of the sandboxes' own and a Cloud Firewall on the tag `tinfirm-sandbox`. The driver holds the token; the runner host never sees it. Its guards (D8):

- `verify` checks that the account is active, that the image is private (D2) and in the region, and that the VPC is in the region and not its default. It checks that the firewall is on the tag, alone there, lets in only TCP to the bridge's port and from no "anywhere" (amended 2026-10-26: nor from the sandboxes' own VPC or tags, and a VPC without an IPv4 range fails the check), and lets out nothing but DHCP (UDP 67), and that only to IPv4 link-local addresses, the broadcast address or the VPC's own range: another private range would reach the VPCs peered with the sandboxes', a cluster's among them. It counts the Droplets that are not sandboxes against the account's limit.
- The firewall and the VPC's range are read again before each create, at most once a minute.
- A create uses only a configured template. It gives the Droplet no SSH key, no IPv6, no monitoring, no backups and no DigitalOcean agent, and tags it `tinfirm-sandbox` with its labels.
- A delete reads the Droplet by its id first. It must carry `tinfirm-sandbox` and the host's label, and never a tag of a cluster (`k8s…`). The driver never deletes by tag.
- It lists only by `tinfirm-sandbox`. Every create and delete is logged with the Droplet's id.

**The VM's image.** One recipe, `ee/runner/images/vm`: Debian, Docker Engine, the runner host's static binary, the runner images built in place from its own Dockerfiles, and the unit that runs `vm-boot`. It has no SSH server or key and no provider agent; cloud-init stays only for the network setup. For DigitalOcean, a Packer template builds it as a private snapshot of the team (D2).

## Consequences

- While no run is active, a VM host holds only its own process: the runner pod and the snapshot's storage on DigitalOcean.
- An escape from the agent's container reaches only the VM of its own run: no other run, no mirror, no token of the host's. Root in the VM cannot change the provider's firewall, and the probe checks that firewall on every run.
- A start waits for the provider to create and boot a VM, and for its dockerd. DigitalOcean publishes no figure; it is to be measured, and ready VMs (the analysis §6.3) would hide it.
- The driver's credential can create and delete VMs. On DigitalOcean a token's scopes are per resource type, so the guards above are the driver's code, not the provider's rules, unless the sandboxes run in a DigitalOcean team of their own (threat model R57).
- The VM's image holds its own copy of the bridge. A bridge of another protocol version is refused at `hello`. A check of the image's version, as ADR-0062 does for containers, is not built yet. (Since ADR-0101 the host checks the build of the VM image's binary: it warns, or with `--vm-image-version refuse` deletes the VM.)
- A crash of the host leaves its VMs running until its next connection to the server deletes them, and loses their runs (R36). (Since ADR-0101 the restarted host takes those runs back: it dials the VMs again, saves the work and the session, and then deletes them.)
- The concurrency stays within 1 to 16. D12's higher bound for VM hosts needs the schemas' bound, which step 6 of the analysis changes.
- The tests cover the executor end to end with a fake driver, whose "VMs" are the bridge on loopback addresses of their own, and the local end-to-end tests run a third time with a VM per run (`TINFIRM_TEST_VM_EXECUTOR`). The DigitalOcean driver is tested against a fake API. Not yet checked on a real account: the boot time, the metadata service and DHCP under the firewall, and the probe from a Droplet. The nightly conformance test of D10 follows.

## Alternatives considered

- **The provider SDKs in the runner host.** Rejected (D9): the host would hold the cloud credential, take each SDK's releases, and an operator could not bring a driver for an in-house cloud.
- **GitLab Runner's fleeting plugins.** Not now (D9): a dependency on GitLab's plugin interface, for clouds that Tinfirm has no driver for yet.
- **The VM dials the host, as containers do.** Rejected: the VM would need an outbound rule, and the probe could no longer tell the host's way in from a way out.
- **A cloud-init script as the user data.** Rejected: cloud-init runs whatever such user data says. JSON that `vm-boot` reads, and refuses beyond its few keys, can start nothing else.
- **Kata in a Droplet.** Not possible: no supported nested virtualisation on DigitalOcean.
