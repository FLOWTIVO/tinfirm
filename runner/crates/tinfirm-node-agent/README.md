# tinfirm-node-agent

The node agent of [ADR-0112](../../../docs/adr/0112-no-credential-on-a-sandbox-machine.md) (Proposed). It runs on a sandbox node, and makes and deletes jailed Firecracker VMs when the control host asks. It holds no credential, has no connection to the server, does not know which run a VM is for, and never speaks with a guest.

It is a binary of its own, licensed under MIT like the rest of `runner/` outside `ee/`. It links no other crate of the workspace: it carries the driver protocol's server side, the jail, the network and the disks, and none of the runner host's code.

## What is built and what is planned

| Part | State |
|---|---|
| The listener: TLS with a pinned client certificate, the local socket, the agent's wire, the records of the VMs, the report of a VMM that died | Built, tested |
| The helper: its socket and peer check, its four operations, the checks of the id, the template, the spool and the user data file, the derivations, its record, its sweep of leftovers | Built, tested |
| The fake backend, for the tests | Built |
| The Linux backend's planning half: the jailer's arguments, the VMM's configuration, the snapshot's table, the network's commands, the firewall's rules, the user-data disk, what is read back | Built, tested as pure functions |
| The Linux backend's execution: running those as root, the VMM's start, the removal of a VM's jail, snapshot, namespace, cgroup and tables, the node's check | Built, tested on a KVM node (`tests/linux_kvm.rs`, and [the evidence](../../../docs/reviews/2026-11-19-node-agent-execution/README.md)) |
| The node's own firewall table required (`nodeFirewall`): `check` names a missing table as a problem, and the helper refuses a make while it is missing | Built, tested with the fake backend (`helper.rs`, `tests/agent.rs`). Run on one node on 2026-11-22: with the table deleted `verify` named it, and with `"nodeFirewall": "not-required"` VMs were still made (the outputs are not in this repository; the evidence's round 10 shows the earlier behaviour, a line of what was checked) |
| The VMM's capability bounding set, cut to the six capabilities the jailer needs (ADR-0112 decision 4) | Built (`src/bounding.rs`), without unsafe code: the jailer has no argument for the bounding set and leaves it full, so the helper starts its own binary again as the hidden subcommand `start-jailer`, which drops the others from its own bounding set (`prctl(PR_CAPBSET_DROP)` through rustix) and then becomes the jailer. The helper refuses a VMM whose `/proc/<pid>/status` shows more. Below, "The bounding set" |
| The VM image for a local disk | Built: `runner/images/vm/local-disk.sh` runs `setup.sh` without a provider and writes one ext4 file. Run in real guests |
| A single machine: the runner host and the node agent on one machine (ADR-0112 decision 5) | Set aside on 2026-11-21 and not built (ADR-0112 decision 5). The local-socket transport is built. The single machine's own firewall table is not (refuse every packet from a VMM's user, and from the listener's user but for its port; check it before each VM). The helper refuses a configuration whose control host is the node itself (its address or loopback): the prerouting DNAT never sees a connection that the node's own stack makes, and an output-hook DNAT matched on the source address alone would admit every local user |
| The hardening mark (ADR-0113, decision 5): the helper reads each line in `check`, and `verify` passes it on (`src/hardening.rs`). Below, "The hardening mark" | Built, tested on a fake root and with the fake backend. Run on one node, on 2026-11-22 (Ubuntu 24.04, kernel 7.0; the outputs are not in this repository): all 13 lines read and held with the role's hardening and the profile in enforce mode, and a line set back by hand failed and was named. The role sets the lines up (`deploy/ansible/README.md`, "The hardening"), and the runner host's `node` driver reads the mark into its `hello` ([runner/README.md](../../README.md#isolation-level)); that report reached a server on 2026-11-22, also when a line was set back by hand while the host ran |
| The hardening's tools: `tinfirm-node-agent harden` and its unit, which set kexec, lockdown and module loading at each boot; the VMM's AppArmor profile `tinfirm-vmm`; `vmmProfile`, with which a make fails for a VMM outside the profile. Below, "Hardening at each boot" and "The VMM's profile" | Built, tested without root (the plan of writes, the configuration, the units' text, the make's check through the fake backend), and installed by the role. Run on one node, on 2026-11-22: `harden` set kexec, lockdown and module loading at each boot, and the profile ran in complain and in enforce mode, with VMs made, reached and deleted, and logged nothing. Later that day, with a server, a real conversation, a run of the stand-in agent and a make that failed after its VMM started ran under the profile in enforce mode, with no `DENIED` line |
| `verify` trying the node's own egress (ADR-0112 decision 4): one datagram from the listener towards the control host, a problem when it passes the node's firewall and `nodeFirewall` is `"required"` (the owner, 2026-11-21). Below, "The node's own egress" | Built (`src/egress.rs`), tested with a fake sender and the fake backend, and with a real datagram on loopback. Run on one real node on 2026-11-22, in four cases (below, "The node's own egress"). It tries the control host alone: the server, the model gateway, the code hosts, a metadata service and the internet are not tried from the node, which the listener's unit would refuse anyway |
| The runner host's `node` driver (`--vm-driver node`), which relays the driver protocol to this listener | Built, in the runner host (`runner/crates/tinfirm-runner-host/src/node.rs`), tested against this agent with its fake backend over TLS and the local socket. VMs were made, described, listed and deleted through it between two machines, driven by a script in the runner host's place (the evidence); runs of the runner host tests' stand-in agent went through it on 2026-11-20, and so did conversations with real runtimes (Claude Code and PI, with a real model), with the sandbox's front confined |
| `vm-boot` reading the user data from the user-data disk | Built, in the runner host (`vm_boot.rs`), tested on files standing in for the device, and run in real guests: it starts the run's container from the disk's user data |
| Metrics for the operator | Planned. The agent logs to stderr (the service manager's journal) |
| The release's signed binary | Built: `tinfirm-node-agent-<version>-<target>.tar.gz` for `x86_64-unknown-linux-musl` and `aarch64-unknown-linux-musl`, static, on each release and on `rolling`, built beside the runner host's (`deploy/release/runner-musl.Dockerfile`) and listed in the release's `SHA256SUMS`, which is signed with the release key. No release has been made with it yet |
| The service units | Built (`units/`): the two services, the unit that loads the kernel modules, the node's firewall file and its unit, the directories and the sysctl file with its two settings, as they ran on a node. Below, "The units" |
| The Ansible role | Written (`deploy/ansible/roles/tinfirm_sandbox_node`), with copies of `units/` that CI compares with this directory. It ran on a machine once, on 2026-11-20 (Ubuntu 24.04), with runs of the runner host tests' stand-in agent and conversations with real runtimes (Claude Code and PI) through the node |

## The two processes

| | The listener | The helper |
|---|---|---|
| Command | `tinfirm-node-agent listener --config FILE` | `tinfirm-node-agent helper --config FILE` |
| Runs as | An unprivileged user of its own (`tinfirm-node-listener`); it refuses to start as root | root, with no network |
| Takes | The control host's TLS connections, or the runner host's local socket | The listener's connections on its local socket |
| Checks | The client certificate's SHA-256, or the peer's uid; every request's form | The peer's uid on every connection; the id, the template, the request's fields; the spool and the user data file |
| Holds | Each VM's name and labels (its records); each VM's user data from just before its make to just after it; the node's TLS key | Its record of the VMs (id, template, slot, serial) and the next serial |
| Sends | Its answers on its connections; in each `verify`, one empty UDP datagram towards the control host's port 9, which the node's firewall must refuse (below, "The node's own egress") | Nothing on a network |
| Never | Logs a ticket, user data, or a value the peer sent before its form is checked (a log names a method only as one of the wire's, a refusal by its kind, a driver's build only in printable ASCII); runs a command; touches a jail | Takes a path, command, address, port or size from a caller; parses or logs user data; logs or answers with a byte the peer sent; opens a network socket |

A flaw in the TLS or the request parsing gives an unprivileged user that holds nothing but the node's TLS key. What that user can still do is ADR-0112's "What a compromised listener still has": make and delete VMs, read the tickets that pass through it, and answer falsely.

The helper's safety rests on its four operations and their checks, not on its confinement. They are in `src/helper.rs`, `src/id.rs`, `src/spool.rs`, `src/strict.rs` and `src/plan.rs`, and the backend below them in `src/backend.rs` and `src/linux.rs`. A reviewer of the helper reads those seven files.

## Files and directories

The paths are the defaults of the role. Each is set in a configuration file, but two are fixed in the units: the agent's binary is `/usr/local/bin/tinfirm-node-agent`, and the tools are found in `/usr/sbin`, `/usr/bin`, `/sbin` and `/bin` (the firewall's unit names `/usr/sbin/nft`, the modules' unit `/usr/sbin/modprobe`). The image and the kernel of a template are root's, mode 0444 on the node that ran; `check` asks only that nobody but root can write them. There is one account, `tinfirm-node-listener`: the helper is root, and the VMs' users are numbers (`vmUidBase + slot`) with no account.

| Path | Owner:group | Mode | What |
|---|---|---|---|
| `/etc/tinfirm/node-agent/helper.json` | root:root | 0644 | The helper's configuration. The helper refuses, at its start, a configuration that is not a regular file of root (or of the helper's own user, where a test runs it as another), or that its group or others can write: the file names the programs that the helper runs as root |
| `/etc/tinfirm/node-agent/listener.json` | root:tinfirm-node-listener | 0640 | The listener's configuration |
| `/etc/tinfirm/node-agent/node.crt` | root:root | 0644 | The node's certificate (DER), which the driver pins |
| `/etc/tinfirm/node-agent/node.key` | tinfirm-node-listener | 0600 | Its key (PKCS #8 DER). The listener refuses a key that another user owns or that group or others can read |
| `/run/tinfirm-node-agent-helper/` | root:tinfirm-node-listener | 0750 | The helper's socket directory. The helper refuses one that its group can write or others can enter |
| `/run/tinfirm-node-agent-helper/helper.sock` | root | 0666 | The helper's socket. The directory and the peer check decide who connects |
| `/run/tinfirm-node-agent-listener/` | tinfirm-node-listener:tinfirm-runner | 0750 | With `socket` only: the listener's socket directory, entered by the runner host's group |
| `/run/tinfirm-node-agent-listener/listener.sock` | tinfirm-node-listener | 0666 | The listener's socket. The peer check takes the runner host's uid alone |
| `/var/lib/tinfirm-node-agent/` | root:root | 0755 | The spool's parent. The helper refuses, at its start and before each read, a parent that is not a directory of its own user (root) or that its group or others can write: then only root can rename the spool's entry, and the directory the helper opens a name in is the spool. Its own parents must be root's as well (`/var/lib`, `/var`), which the helper does not check |
| `/var/lib/tinfirm-node-agent/spool/` | tinfirm-node-listener | 0700 | The listener's spool. The helper refuses, at its start and before each read, a spool that is a link or not the listener's 0700 directory. `<id>.userdata`, 0600, is written just before a make and removed just after it, whatever the answer; a listener that stopped between the two leaves it, and the next listener's start removes every entry of the spool |
| `/var/lib/tinfirm-node-agent/listener/` | tinfirm-node-listener | 0700 | The listener's state: `vms/<id>.json`, a record per VM, 0600, written whole as `<id>.json.next` and renamed into place. At its start the listener removes `*.json.next` and moves a record it cannot read aside as `<id>.json.bad` |
| `/var/lib/tinfirm-node-agent/helper/` | root | 0700 | The helper's state: `vms.json`, 0600, written whole and renamed into place. Beside it `lock`, on which one helper holds a lock while it lives: a second helper on the same state does not start, since each would remove the other's VMs as leftovers. The helper refuses a directory that is not its own 0700 one |
| `/srv/tinfirm-jails/` | root:root | 0755 | The jailer's chroot base. `check` asks for a directory of root's that nobody else can write |
| `/srv/tinfirm-jails/firecracker/<jail>/` | root | 0700 | A VM's jail directory: the mark that this VM is on the node. A make creates it first and a delete removes it last, so `list`, which reads this directory, shows a VM in every state in between. Root's alone: even the VM's own user has no path to its chroot from outside |
| `/srv/tinfirm-jails/firecracker/<jail>/root/` | the VM's user | 0700 | The chroot: **a file system of its own** (a tmpfs of `firecracker.jailMib`, 64 MiB by default, with 1024 inodes, `nosuid`), which the helper mounts and the jailer hands to the VM's user. The helper puts `vmlinux`, `root.disk` (the snapshot's device node), `userdata.disk`, `vm.json` and `firecracker.log` there, each the VM's user's, 0600; the jailer adds `firecracker`, `dev/` and `run/`. Under the helper's unit the mount is in the helper's own mount namespace: in the node's, this directory is empty, and nothing of a jail is on a disk |
| `/var/lib/tinfirm-node-agent/work/` | root | 0700 | Each VM's files outside its jail. `check` asks for a directory that only root can enter |
| `/var/lib/tinfirm-node-agent/work/<jail>.snapshot` | root | 0600 | A VM's own snapshot over the shared image: a sparse file of `snapshotMib` |
| `/var/lib/tinfirm-node-agent/work/<jail>.console` | root | 0600 | Only with `guestConsole`: the VMM's standard output, which is the guest's serial console. At most 64 MiB (below). Without `guestConsole` the VMM's standard output is `/dev/null` |
| `/var/lib/tinfirm-node-agent/work/<jail>.errors` | root | 0600 | The jailer's and the VMM's standard error: what they say when they cannot start. Nothing of a guest |
| `/sys/fs/cgroup/<cgroupParent>/<jail>/` | root | | The VMM's cgroup, which the jailer makes, with the template's limits |

## Configuration

Both files are JSON. An unknown field anywhere is refused. Paths are absolute.

### The helper's (`helper.json`)

```json
{
  "listenerUid": 991,
  "socket": "/run/tinfirm-node-agent-helper/helper.sock",
  "spool": "/var/lib/tinfirm-node-agent/spool",
  "state": "/var/lib/tinfirm-node-agent/helper",
  "maxVms": 8,
  "vmUidBase": 1100000,
  "nodeFirewall": "required",
  "vmmProfile": "not-required",
  "network": {
    "vmRange": "10.231.0.0/16",
    "nodeAddress": "10.0.0.20",
    "controlHost": "10.0.0.5",
    "firstPort": 20000
  },
  "firecracker": {
    "jailer": "/usr/local/bin/jailer",
    "firecracker": "/usr/local/bin/firecracker",
    "chrootBase": "/srv/tinfirm-jails",
    "cgroupParent": "tinfirm-vms",
    "work": "/var/lib/tinfirm-node-agent/work",
    "guestConsole": false
  },
  "hardening": {
    "agentPort": 7400,
    "sshPort": 22,
    "maxUptimeDays": 30
  },
  "templates": {
    "sandbox-4c8g": {
      "vcpus": 4,
      "memoryMib": 8192,
      "snapshotMib": 20480,
      "image": "/var/lib/tinfirm-node-agent/images/tinfirm-vm.img",
      "kernel": "/var/lib/tinfirm-node-agent/images/vmlinux",
      "bridgePort": 7443,
      "pidsMax": 64
    }
  }
}
```

| Field | Rule |
|---|---|
| `listenerUid` | Not 0, and not one of the VMs' users |
| `maxVms` | 1 to 1024 |
| `vmUidBase` | At least 100000; `vmUidBase + maxVms` below 2^31 |
| `nodeFirewall` | `"required"` (default) or `"not-required"`, nothing else. `"required"`: the node's own firewall table (`inet tinfirm_node`, below) must be loaded. While it is missing, `check` names it as a problem, so a control host places no VM on the node, and the helper refuses every make, whoever asks. The VMs that run are left as they are. `"not-required"`: for a node that is meant to run without the table, such as a test machine; `check` then says in a line of what was checked whether the table is there, and that this node's configuration does not require it. Tables that cannot be listed are a problem either way |
| `hardening` | Optional, for the hardening mark (below): `agentPort` (the listener's TCP port, `tls.listen`) and `sshPort`, the two ports that may listen beyond loopback, neither 0; `maxUptimeDays`, 1 to 3650. All three or none. Without it, a configuration written before the mark still loads, and the lines `listening-sockets` and `max-uptime` fail and say that `helper.json` does not name them: no default stands in for a bound the operator did not set |
| `vmmProfile` | `"not-required"` (default) or `"required"`, nothing else, in the form of `nodeFirewall`. `"required"`: right after a VMM starts, the helper reads its AppArmor label (`/proc/<pid>/attr/apparmor/current`, or `attr/current`), and a VMM that does not run as `tinfirm-vmm (enforce)`, or whose label cannot be read, fails the make with the label it found or the error's kind; the VM is removed as any failed make's. The profile names the default `firecracker.chrootBase` (`/srv/tinfirm-jails`) and `firecracker.work` (`/var/lib/tinfirm-node-agent/work`), so the configuration is refused when `required` meets another value of either. For a node on which the role loads the profile. `"not-required"`: the make does not ask; the hardening mark's line `vmm-mac` still says whether the VMMs are confined |
| `network.vmRange` | `A.B.C.D/P`, P from 8 to 29, no bit set below the prefix; room for `maxVms` slots of 8 addresses; holds neither `nodeAddress` nor `controlHost` |
| `network.controlHost`, `network.nodeAddress` | Two different addresses; neither loopback nor `0.0.0.0`. A control host on the node itself is the single machine, which is set aside (above). The helper compares the two addresses of its own file and nothing else: a control host at another address of the node itself passes, since the helper cannot know the node's addresses without the network it does not have. Whoever installs the node checks that (the role compares the control host with every IPv4 address of the node) |
| `network.firstPort` | At least 1024; `firstPort + maxVms - 1` at most 65535. The published ports must not hold the listener's own port (`tls.listen`): the helper does not know the listener's configuration, so this is the installation's to keep |
| `firecracker.jailer`, `firecracker.firecracker` | Files named `jailer` and `firecracker`: `start-jailer` starts no file of another name, and the jailer names the chroot after the VMM's file. Checked when the configuration is read |
| `firecracker.cgroupParent` | One name of letters, digits, `-` and `_` |
| `firecracker.guestConsole` | `false` (default): the guest's kernel has no serial driver (`8250.nr_uarts=0`) and no console, and the VMM's standard output, where Firecracker writes what a guest sends to the serial port's address, is `/dev/null`: nothing of a guest reaches a file of the node, whatever code runs in the guest's kernel. `true`: the guest's kernel and its journal write to the serial console, which the helper keeps in `<work>/<jail>.console` until the VM is deleted, for finding out why a guest does not come up. A guest's log may hold what a run printed: leave it off where runs are real |
| `firecracker.jailMib` | The size of each jail's own file system, 8 to 4096, 64 by default. It must hold the guest kernel (41 MiB for the one that ran), the VMM's binary and its log. It is memory, and `check` counts it |
| A template's `ioMax` | Optional: `readBytesPerSecond`, `writeBytesPerSecond`, `readsPerSecond`, `writesPerSecond`, at least one, none of 0. The helper writes them as `io.max` of the VM's cgroup for the VM's root disk. `check` then asks that the root cgroup hands down the controller `io`, which the helper's unit makes the service manager do (`IOAccounting=yes`) |
| A template's `image`, `kernel`, and the two programs | Regular files of root's that only root can write, not links: `check` names each that is not |
| Every path | Absolute, of letters, digits, `/`, `-`, `_` and `.`, without `.` or `..` parts |
| A template's name | The form below |
| `vcpus`, `memoryMib`, `snapshotMib`, `pidsMax` | 1 to 32; 128 to 262144; 64 to 1048576; 1 to 4096 (default 64) |
| `bridgePort` | Where the bridge listens in the guest; not 0 (default 7443) |

### The listener's (`listener.json`)

```json
{
  "helperSocket": "/run/tinfirm-node-agent-helper/helper.sock",
  "spool": "/var/lib/tinfirm-node-agent/spool",
  "state": "/var/lib/tinfirm-node-agent/listener",
  "address": "10.0.0.20",
  "tls": {
    "listen": "10.0.0.20:7700",
    "controlHost": "10.0.0.5",
    "certificate": "/etc/tinfirm/node-agent/node.crt",
    "key": "/etc/tinfirm/node-agent/node.key",
    "clientSha256": "64 hex digits: the SHA-256 of the control host's certificate"
  },
  "socket": {
    "path": "/run/tinfirm-node-agent-listener/listener.sock",
    "peerUid": 990
  },
  "peerBuild": "warn",
  "watchMs": 1000
}
```

| Field | Rule |
|---|---|
| `address` | The node's address as the control host dials it, which `describe` answers: an IP address or a host name |
| `tls`, `socket` | At least one. A separated node has `tls`; `socket` is for the single machine (set aside, above) |
| `tls.controlHost` | The control host's address, as the node sees its connections: the only source the listener takes a TLS connection from. The same address as the helper's `network.controlHost` |
| `tls.clientSha256` | The one client certificate the listener accepts |
| `socket.peerUid` | The runner host's user, the one peer; not 0 |
| `peerBuild` | `warn` (default) or `refuse`: what `hello` does with a driver of another build |
| `watchMs` | How often the listener asks the helper for VMMs that died: 100 to 60000 (default 1000) |

`tinfirm-node-agent identity --cert FILE --key FILE` writes a new key and its self-signed certificate, which must not exist, and prints the certificate's SHA-256: the node's for the driver to pin, or the control host's for `clientSha256`. `tinfirm-node-agent fingerprint --cert FILE` prints the SHA-256 of a certificate. Both files belong to whoever ran the command: an installer that runs it as root gives the node's key to the listener's user afterwards (`chown tinfirm-node-listener`, mode 0600), or the listener cannot read it.

## A VM's id, and a template's name

| Value | Form | Checked |
|---|---|---|
| A VM's id | `vm-` and 32 lower-case hex digits: 35 bytes, nothing before or after | On the whole byte string, after JSON's escapes are read, before anything else uses it. A trailing newline, a NUL, an upper-case digit, a `/`, a byte that is not ASCII, one byte too many or too few: each is refused |
| A template's name | 1 to 63 bytes of `a-z`, `0-9` and `-`, starting with a letter or a digit | The same way; then looked up in the helper's configuration |

The listener makes each id from 16 bytes of the system's secure random source. The helper never uses an id as a name of anything on the node: it uses it to find the user data file and its own record, and that is all.

## Strict JSON, on both wires

Every line of both wires is read as one JSON value in which no object, at any level, names a key twice (`src/strict.rs`). `serde_json` alone keeps the last of two equal keys, so `{"v":2,"v":1,"op":"delete","op":"list"}` would read as a `list`; here it is refused before anything else reads it. Every refusal on either wire is one of a few fixed messages: none holds a byte the peer sent, so neither a log nor an answer does.

## The agent's wire: driver and listener

**Framing.** JSON-RPC 2.0, one message per line (`\n`, a `\r` before it is dropped), UTF-8, on TLS 1.3 or the local socket. A line is at most 512 KiB (`MAX_LINE`); a longer one gets a parse error, and the connection closes. The driver may send requests without waiting; the listener answers each with its `id`, in any order. A connection has at most 64 requests in flight (`MAX_IN_FLIGHT`); more get `-32007`.

**Connections.**

| Transport | Bound | What happens beyond it |
|---|---|---|
| TLS, before the handshake | 8 handshakes pending at once (`HANDSHAKE_SLOTS`), each within 3 s (`HANDSHAKE_TIMEOUT`) | A connection from another address than the control host's (`tls.controlHost`) is closed at once and takes no place. A new connection from that address drops the oldest pending handshake. Connections that say nothing therefore cannot keep the control host out: its handshake takes milliseconds, and it gets a place at once. What is left: a peer that sends from the control host's own address (the control host itself, or a machine that can take its address in the node's network) and opens more than 8 connections within those milliseconds, again and again, can still drop the control host's handshake each time. While it does, the runner host neither makes nor deletes a VM on this node, and nothing else is reached. The node's firewall admits only the control host to this port as well (the firewall's file and the role's `tinfirm_sandbox_node` admit only the control host to this port) |
| TLS, after a handshake with the pinned certificate | 16 served connections (`MAX_CONNECTIONS`) | A 17th authenticated connection is closed after its handshake. Nothing displaces a served connection |
| The local socket | 16 connections of the runner host's uid | A 17th is closed at once; a peer of another uid is closed before it is read |

Refusals (a connection from another address, a failed or late handshake, a dropped pending handshake, a full transport, a local peer of another uid) are counted by kind and logged as one summary at most every 60 s (`REFUSAL_SUMMARY_EVERY`), with kinds and counts only: a peer refused again and again cannot flood the journal.

A request has exactly `jsonrpc` (`"2.0"`), `method`, `params` and `id` (a number, or a string of at most 64 bytes), each once. Any other field is refused. `params` must be a JSON object: absent, an array, a string or `null` is refused with `-32602`. A request without `id` is a notification: the listener takes none and answers none.

**Versioning.** The first request on a connection is `hello`. Every other method before it gets `-32001`. `wire` is this wire's version, 1: another is refused. `build` is each side's build id. The release sets the agent's at compile time (`TINFIRM_BUILD_ID`, from `deploy/release/assets.sh build-id`): the workspace's version for a release (`1.0.0`), and the workspace's version with the asset's after `+` for `rolling` (`1.0.1-SNAPSHOT+rolling-abc1234`). A build without it takes the crate's version. The `node` driver sends the runner host's build id, which carries a digest of its runner images after `+` (`1.0.0+images.1a2b3c4d5e6f`): the check compares releases, what comes before `+` (`release_of`), on both sides. A driver of another release is served with a warning, or refused with `peerBuild: refuse`. The answer carries the agent's build, so the driver applies its own rule (its `peerBuild`, warning by default).

**The methods.** The driver protocol's (`ee/runner/tinfirm-runner-host/src/vm.rs`), with the same names, parameters and results, so that the runner host's `node` driver is a thin relay.

| Method | Parameters | Result |
|---|---|---|
| `hello` | `wire` (1), `build` | `wire` (1), `agent` (`tinfirm-node-agent`), `build` |
| `verify` | `template` | `problems` (what the helper and its backend found, and the listener's try of the node's own egress where it passed a firewall that the node requires; any problem means the node must not be used), `maxVms` (how many more VMs the node may make now), `checked` (for the log; the egress try's line is one of them otherwise), `hardening` (the hardening mark: `{"hardened": "yes" \| "no", "lines": [{"line", "holds", "found"}]}`, below; absent when the helper sent none) |
| `create` | `template`, `name`, `userData`, `labels` | `id` |
| `describe` | `id` | `id`, `name`, `state` (`running`, `failed` or `gone`), `address` (the node's), `port` (the node's port on which this VM's bridge is published), `labels`. For a VM the node does not have: `id` and `state: gone` |
| `delete` | `id`, `labels` | `{}`. A VM that is gone is no error. `labels` must name `tinfirm-host`, not empty, and the VM must carry each of the labels; otherwise `-32002` |
| `list` | `labels` (optional within `params`) | `vms`: each as `describe` gives it, those that carry each of the labels |

| Parameter | Rule |
|---|---|
| `template` | A template's name, above |
| `name` | 1 to 63 bytes of `a-z`, `0-9`, `-` and `.` |
| `userData` | A string of 1 to 64 KiB (as UTF-8): the driver protocol's user data, passed on as it is |
| `labels` | At most 16; keys of 1 to 63 bytes of `a-z`, `0-9` and `-`; values of 1 to 128 bytes of letters, digits, `-`, `_` and `.`. A `create` must name `tinfirm-host` |
| `id` | A VM's id, above |

**The notification.** When the helper reports a VM's VMM dead, the listener sends, on every connection that said `hello`:

```json
{"jsonrpc":"2.0","method":"vmFailed","params":{"id":"vm-…","state":"failed","reason":"the VM's VMM ended"}}
```

From then on `describe` and `list` report that VM as `failed`, until it is deleted.

**The errors.** `error` is `{"code", "message"}`. A message never holds user data, nor a byte of the request.

| Code | When |
|---|---|
| -32700 | The line is not JSON, names a key twice in an object, or is too long |
| -32600 | Not a request of the form above |
| -32601 | No such method |
| -32602 | `params` is not an object, or a parameter is missing, unknown or out of its form |
| -32000 | The helper or its backend failed (the message says what) |
| -32001 | A method before `hello` |
| -32002 | A delete refused: no `tinfirm-host`, or a label the VM does not carry |
| -32003 | Another wire version, or another build with `peerBuild: refuse` |
| -32004 | The backend cannot do it (`not-built`). The Linux backend never answers so; the code stays for a backend that is built in parts |
| -32005 | No such template in the helper's configuration |
| -32006 | The node runs as many VMs as it may |
| -32007 | Too many requests in flight on the connection |

**What a create does.** The listener checks the parameters, makes the id, writes the VM's record to its state directory, writes the user data to `<spool>/<id>.userdata` (created new, 0600, without following a link), asks the helper to make the VM, and removes the spool file, whatever the answer. A failed make removes the record. A record is on disk before its VM exists, so a VM that no record holds is a leftover.

**Leftovers.**

| Who | When | What |
|---|---|---|
| The listener | At its start | Removes every entry of its spool; removes `*.json.next`; moves an unreadable record aside; drops each record whose VM the helper does not have; has the helper delete each VM that no record holds |
| The listener | On each `list` | Has the helper delete each VM that no record holds |
| The helper | At its start, and before each `make` and `check` | Tries to remove each VM that the backend finds and its record does not hold. One it cannot remove keeps its slot, read from its jail's name, and no new VM gets that slot. A jail whose name is of no form the helper knows has a slot that cannot be known: while one is there, the helper hands out no slot at all, and `check` names it |
| The helper | When a make fails | Removes what the make left. If that fails too, the VM stays in its record, `failed`, and holds its slot until a delete succeeds (the listener's next `list` asks for one) |

## The helper's wire: listener and helper

**Framing.** A Unix stream socket. One request line of at most 4096 bytes (`MAX_REQUEST`), within 5 s of the connection (`REQUEST_TIMEOUT`); one answer line of at most 1 MiB; then the helper closes the connection. The helper serves at most 32 connections at once (`MAX_CONNECTIONS`); one more is closed at once, before a byte is read.

**The peer.** On every connection, before it reads a byte, the helper takes the peer's uid from the kernel (`SO_PEERCRED`) and closes the connection unless it is `listenerUid`. A refused peer gets nothing.

**Versioning.** Every request carries `v`: 1. Another value, or none, is refused. The listener and the helper come from one binary, so they change together; `v` catches a helper that was not restarted with its listener. **Upgrading:** the two units restart together. The listener's unit requires the helper's, so a restart of the helper restarts the listener too. A listener that reads an answer it cannot parse says so: "the helper's answer is not readable; the helper may be of another build: restart the listener".

**The requests.** A JSON object with `v`, `op` and exactly the fields of its operation, each once. Any other field is refused.

| `op` | Fields | Answer (`ok`) |
|---|---|---|
| `check` | `template` | `problems`, `checked`, `free` (how many more VMs the node may make now), `hardening` (the mark, as on the agent's wire), `nodeFirewall` (`"required"` or `"not-required"`, the configuration's, for the listener's egress try). The listener reads an answer without `hardening`, and its `verify` then has none; and one without `nodeFirewall`, and its egress try is then a line of `checked` whatever it found |
| `make` | `id`, `template` | `port`: the node's port of this VM's bridge |
| `delete` | `id` | `{}`; a VM the helper does not hold is no error |
| `list` | none | `vms`: each `{id, template, state, port}`, `state` being `running` or `failed`; `free` |

**The answer.** `{"ok": …}`, or `{"error": {"code", "message"}}`, with `code` one of the below. The helper logs a refusal by its code alone.

| Code | When |
|---|---|
| `bad-request` | Not one JSON object, a key twice, another `v`, an unknown or missing field, a malformed id or template, too long, too late |
| `unknown-template` | No such template in the helper's configuration |
| `exists` | `make` of an id the helper holds |
| `full` | Every slot is held |
| `user-data` | The spool or the user data file is refused (below) |
| `not-built` | The backend cannot do it. The Linux backend never answers so |
| `failed` | The backend failed, the record could not be written, or a jail of unknown form blocks every slot |

## What the helper derives

From its configuration, a **slot** (0 to `maxVms - 1`, the lowest one that neither a VM of the record nor a leftover holds) and a **serial** (its own counter, kept in its record, never reused). No value a caller gave goes into any of them, the id included: the backend never sees the id.

| What | From | Value for slot `s`, serial `n` |
|---|---|---|
| The VMM's user and group | slot | `vmUidBase + s` |
| The addresses (a /29 of `vmRange`) | slot | at `vmRange + 8s`: `+1` the veth on the node, `+2` the veth in the VM's namespace, `+5` the tap device, `+6` the guest |
| The published port | slot | `firstPort + s` |
| The jailer's `--id`, the network namespace, the cgroup | serial and slot | `tfvm-`, `n` in 12 hex digits, `-`, `s` in 4 hex digits |
| The veth on the node | serial | `tfv` and `n` in 12 hex digits (15 bytes, the kernel's limit) |
| The device-mapper snapshot | serial and slot | `tinfirm-tfvm-…` |
| The node's firewall table | serial | `tinfirm_` and `n` in 12 hex digits |
| The jail directory | serial and slot | `<chrootBase>/firecracker/tfvm-…/root` |
| The snapshot file | serial and slot | `<work>/tfvm-….snapshot` |
| The limits | template | `cpu.max` of `vcpus × 100000` per 100000, `memory.max` of `memoryMib + 128` MiB, `memory.swap.max` 0, `pids.max` |

So no two live VMs share a user, an address, a port or a name. A jail directory is made for one VM and never used again: a new VM gets a new serial, and so a new directory. A slot, and with it its user, addresses and port, is handed out again only once the backend no longer finds the VM that held it: a VM whose removal failed keeps its slot, whether it is in the record or a leftover. The only places a VM's user can write are its chroot and its cgroup's processes: a delete ends every process of the cgroup and removes the jail directory, and fails while either is left. At each sweep the helper moves the next serial beyond every jail it finds on the node, so a lost record cannot hand out a name that is in use.

## The user data file

Before it opens anything, the helper checks the spool and its parent without following a link: the spool a directory of the listener's with mode 0700, its parent a directory of the helper's own user (root) that neither its group nor others can write. It does so at its start, in `check`, and before each read.

It then opens `<spool>/<id>.userdata`, a name derived from the id and never taken from the caller, with `O_NOFOLLOW | O_NONBLOCK | O_NOCTTY`: a symbolic link fails to open, and a FIFO does not block. Then, on the open file (`fstat`), it checks:

| Check | Refused |
|---|---|
| A regular file | A link, a directory, a FIFO, a socket, a device |
| Owned by `listenerUid` | Any other owner, root included |
| One hard link | A file with a second name, or none |
| 1 to 65536 bytes | An empty or larger file |

Only then does it read the bytes, at most 65537, and refuse them if their count differs from the `fstat`'s. It never parses them or uses them as a name. The Linux backend puts them on a read-only disk of the VM: `TFUDATA1`, the length as 8 bytes little-endian, the bytes, and zeros to a multiple of 4096.

**Where the guest finds it.** The disk is the VM's second drive, after the root disk, so the guest's `/dev/vdb`. The VMM's kernel command line names it: `tinfirm.user-data=disk:/dev/vdb` (`USER_DATA_CMDLINE` in `src/linux.rs`). `vm-boot` in the guest, run with its default `--user-data auto`, takes the source from that entry, and reads the disk: at most one byte more than the largest disk (64 KiB of user data and the header, padded to 4096), refusing a wrong magic, a length of 0, beyond 64 KiB or beyond the disk, and any byte after the user data that is not zero (`runner/crates/tinfirm-runner-host/src/vm_boot.rs`, `read_disk`). Without that entry, `vm-boot` asks DigitalOcean's metadata service, as before: one image serves both. The two crates must not link each other, so the layout's constants are written in both; a test on each side checks the same fixture bytes, so that they cannot drift apart.

## The Linux backend

`src/linux.rs`. The first half plans: pure functions from a `VmPlan` to the data the backend acts on. The second half runs them as root: each command is an argument vector, run without a shell and with no environment but `PATH=/usr/sbin:/usr/bin:/sbin:/bin`; each result is checked; and what a silent failure would hide is read back. No tool may run longer than 10 s: one that does is killed with its process group, and counts as not run. The helper does one thing at a time, so a tool that never ended would stop every operation after it.

### What a node needs

`check` (the wire's `verify`) names each of these that is missing, and a control host does not use a node that has a problem. `make` does not run the whole check again: it asks whether the node's own firewall table is there, reads back what a VM cannot do without (the forwarding, the settings of its namespace, its two tables, the room for its snapshot), and fails with the reason.

| What | Why |
|---|---|
| `/dev/kvm`, `/dev/net/tun`, `/dev/mapper/control`, `/dev/loop-control` | KVM, the tap device, the snapshot, the loop devices |
| The tools `ip`, `nft`, `sysctl`, `losetup`, `dmsetup`, `blockdev`, `mknod` | What the execution runs |
| With `vmmProfile` `"required"`: AppArmor on, and the profile `tinfirm-vmm` loaded in enforce mode | Every make reads the new VMM's label, and fails while the profile does not confine it. Below, "The VMM's profile" |
| The modules `kvm`, `dm_snapshot`, `veth`, `nf_tables`, `nft_ct`, `nft_chain_nat`, `nft_nat`, `nf_nat` | The helper loads none (`units/tinfirm-node-agent-modules.service` does, at boot). A module counts when `/sys/module/<name>` is there, or when the kernel's list of built-in modules names it: a built-in module has an entry under `/sys/module` only when it has a parameter. The list is read from `/lib/modules/<release>/modules.builtin`, or, since the helper's unit hides `/lib/modules`, from the copy that the modules' unit makes at `/run/tinfirm-node-agent-modules/modules.builtin` |
| The node's own firewall table refusing the node's own datagram to the control host, unless `nodeFirewall` is `"not-required"` | The listener's try in `verify` (below, "The node's own egress"): a table that is loaded but lets the node's own packets out is no guard. With `"not-required"` the outcome is a line of what was checked |
| The node's own firewall table, `inet tinfirm_node` in `nft list tables`, unless `nodeFirewall` is `"not-required"` | Without it the node lets out and in whatever its network allows: a process that got out of a VMM, or the listener's user, could open connections from the node. Below, "The node's firewall". With `"not-required"` its absence is a line of what was checked. `nft list tables` failing is a problem either way |
| `net.ipv4.ip_forward=1` | A published port is a DNAT into the VM's namespace. The helper reads it and never writes it (`units/tinfirm-node-agent.sysctl.conf`). Forwarding makes the node a router for whatever reaches it: the node's own firewall (below) forwards nothing but the control host's connections to the published ports |
| A cgroup v2 hierarchy with the `cpu`, `memory` and `pids` controllers | The VMM's limits |
| `cgroup.kill` (Linux 5.14) | The one way the helper ends a VM's processes: it may not signal another user's process |
| Room in the work directory's file system for `maxVms` snapshots of the template | A snapshot file is sparse: without the room, one guest that fills its disk takes what another VM's writes need. `make` asks the same for one more snapshot beside what those that are there may still grow by |
| Memory for `maxVms` VMs of the template (`memoryMib` + 128 MiB each) within the node's | The VMMs' limits must not add up to more than the node has |
| The jailer, Firecracker, the image and the kernel: regular files of root's that only root can write | The helper runs the first two as root and hands the others to every VM |
| The jails' directory (root's, not writable by others) and the work directory (root's, 0700) | Where the VMs' files are |

Not checked, and the node's to make sure of: **no other firewall that drops forwarded packets** (Docker's `FORWARD` policy does, and so do ufw's and firewalld's defaults; a node keeps none of them, and no Docker after its image is built), and **a guest kernel with `CONFIG_IP_PNP`**, nf_tables, virtio-mmio and no need of an initrd. The kernel that ran is Kata Containers 4.3.1's `vmlinux-6.18.56-208` (`https://github.com/kata-containers/kata-containers/releases/download/4.3.1/kata-static-4.3.1-amd64.tar.zst`, SHA-256 `1463a2b86845f4d3d8e87f2f764ec59856fc0fee3520967a79ade32762be2e97`; the member `opt/kata/share/kata-containers/vmlinux-6.18.56-208`, SHA-256 `f87d8359035cb7557b16a319e6431ea09b183e62268aef436bf8dc6530d786b6`), with Firecracker and its jailer 1.14.2. That kernel is x86-64's; no kernel is pinned for aarch64 yet, though the agent is released for it: nothing ran on an aarch64 node.

`check` also puts the kernel's release and the machine's boot time into `checked` (from `/proc/sys/kernel/osrelease` and the `btime` of `/proc/stat`): whether the kernel is patched stays the operator's duty, and these show what runs.

**The shared image's loop device.** The image is attached once, read-only, and every VM's snapshot is over that device. A new image must be a new file moved over the old name (`mv`), never written in place: the running VMs keep the old file through the loop device until the last of them is deleted, and the kernel then frees it, while the next make attaches the new file under the same name. An image changed in place changes the disk under every running guest.

### A make

Before the backend's first step, the helper refuses the make, with nothing made and no slot or serial used, while the node's own firewall table is missing and `nodeFirewall` requires it, or while the node's tables cannot be listed. The backend's steps then come in this order; a make that fails at any step removes what it made, with the steps of a delete, and returns the first error.

1. **The mark** (`places`): the jail's own directory, root's, 0700. A make over a jail that is there is refused.
2. **The network** (`network_commands`): a namespace, with IPv6 off in it (`all` and `default`) before any interface is there, and forwarding on; a veth from the node into it, with IPv6 off on the node's end before it is up; a tap device that the VM's user may open. Then `network_read_backs`: each of those sysctls, and the node's own forwarding, must read `1`. A service manager that makes `/proc/sys` read-only lets `sysctl -w` end without an error in some setups; the make then fails with "the setting did not take" instead of leaving a VM that nothing reaches.
3. **The firewall**, two tables of the `inet` family, each loaded with `nft -f -` and listed again. The node's own table for the VM (`node_rules`): the published port, from the control host alone, is a DNAT to the VM's namespace, and only a connection that this DNAT made is forwarded there (`ct status dnat`): the namespace's own address answers nobody, the control host included. From that namespace only the answers of such connections come back; any other packet from it, of either family, is dropped, to the node and through it. The namespace's table (`namespace_rules`), which root alone can change, with every chain's policy `drop`: the control host to the guest's bridge port over IPv4, the answers back, and nothing else, neither from the guest nor from the VMM, whose own sockets get no packet out.
4. **The disks**: the shared image as a read-only loop device, attached once for all VMs (`read_only_loop`); a sparse snapshot file of `snapshotMib` and its loop device; a device-mapper snapshot over both (`snapshot_table`: not persistent, chunks of 4 KiB). A Docker pull and run written to such a snapshot took 36 s with chunks of 4 KiB and 38 to 40 s with 32 KiB, with the same size on disk and 1 to 3 MiB less kernel memory, so the chunk stays; the kernel's memory for a snapshot's chunks is charged to no cgroup, and `check` counts its measured worst case (160 bytes for each chunk, 320 MiB for 8 GiB written full at random) in the memory a VM may take.
5. **The jail**: the chroot is a tmpfs of its own with a size and a number of inodes (`jail_mount_options`), so the VM's user, who owns it, can fill nothing but it: not the jails' file system, not the work directory with the other VMs' snapshots, and no more memory than `jailMib`. It is root's until the jailer hands it over, with the guest kernel (a copy), the user-data disk (`user_data_disk`), the VMM's configuration (`firecracker_config`), Firecracker's empty log and the device node of the snapshot, each the VM's user's, 0600.
6. **The VMM**: the jailer (`jailer_argv`), started through `start-jailer` with the cut bounding set (below), in a process group of its own. Firecracker writes its own messages to `firecracker.log` in the jail (`--log-path`); its standard output, the guest's serial console, is the console file with `guestConsole` and `/dev/null` without; its standard error is `<jail>.errors`. The jailer's first process ends once the VMM runs in its new PID namespace; the helper then sets the template's `io.max`, if any, and waits, at most 10 s, until Firecracker's log says that the VM is started and its API reports a running guest, and fails at once when the VMM's cgroup is empty before that. The API is not asked earlier: a request that arrives while Firecracker gives up on its configuration ends its API thread first, and the log then never says why. A make that fails here says why in its error: the lines Firecracker marked as errors, and its standard error, as plain text of at most 400 characters. Last, the VMM's `/proc/<pid>/status` must show the VM's user and group in all four places, no capability in any set but the bounding set, a bounding set within the six, `NoNewPrivs` and a seccomp filter (`vmm_status_problems`); a VMM that shows more is removed and the make fails. With `vmmProfile` `"required"`, its AppArmor label must then be `tinfirm-vmm (enforce)` (`hardening::make_label_problem`): the profile attaches when the jailer execs Firecracker, so a VMM that runs without it now never gets it.

The helper overwrites the user data's bytes, and the disk it made of them, before it frees them (`spool::wipe`). The listener does the same with what it held of a `create`: it reads its requests through a reader that keeps no byte of a line it has handed out (`local::WipedLines`), into one buffer per connection that never moves and is overwritten once the line is parsed; a request overwrites the strings of its parameters when it is dropped, and the user data its text as soon as it is in the spool. What the agent's own code cannot overwrite is in the TLS library's buffers and the JSON parser's scratch space. The binary's allocator covers what they free: it overwrites every block with zeros when the block is freed, in the helper and in the listener (`zeroizing-alloc`, 1Password's wrapper of the system allocator; `src/main.rs`). So a copy that a library dropped does not stay in free memory, and neither does the copy of a `create` that the listener refuses for its form, which is parsed from a copy that is dropped as it is, user data included. A buffer that a library keeps and uses again, a connection's read buffer for one, is not freed: it keeps its bytes until they are written over. Before the allocator, a root shell on a node never found the ticket in the helper, and found it in the listener in some runs after one further call and in none after three (the evidence, third round); a later search found it in a block of the listener's heap that `verify` and `list` calls left alone and that the next `create` of the same size took and wrote over. With the allocator, on 2026-11-22, a root shell on a node found the ticket in the listener's memory during the run, right after the run was cancelled and after later `verify` and `list` calls, and not after the next make of the same size: the copy that the TLS library's buffer keeps while it is in use lasts until the next make of that size overwrites it, and the later, smaller calls did not. The spool file and the user-data disk are removed, not overwritten: the disk is in the jail's own file system, which is memory, and the spool is a directory on the node's disk for the moment between a `create` and its make, so a node whose disk is copied at that moment gives away a ticket, which is used up at the bridge's first connection. A spool on a tmpfs (`spool` in both configuration files) closes that.

**The guest's address** is the kernel's own `ip=` argument (`boot_args`): the guest, the tap device as its gateway, a /30, `eth0`, no autoconfiguration. The image needs no cloud-init, no DHCP client and no unit for it.

**The console and the log.** With `guestConsole` the VMM's standard output is a regular file of the node, so the jailer bounds what the VMM may write to a file (`--resource-limit fsize`, 64 MiB): a guest that writes more to its serial port ends its own VMM, which the listener reports as `failed`, and fills no disk of the node. Firecracker's log is in the jail, and a guest can make it grow (Firecracker warns of every access to a port it does not emulate): it grows to the jail's size and no further, at the guest's own cost.

### A delete

Every step is tried whatever the one before did, and each is checked on the node afterwards. The delete fails while anything of the VM is left, and names it, so the helper keeps its record and its slot:

1. The VMM and whatever else is in its cgroup: `cgroup.kill`, then a wait of at most 5 s until the cgroup has no process. A VM whose last delete failed is not waited for again, here or in the steps below: each step is tried once and the answer comes at once, since the listener asks again about every second.
2. The snapshot's device (`dmsetup remove`, tried every 10 ms while the VMM's descriptor of it closes), then the snapshot file's loop device, then the snapshot file, the console file and the errors file. The loop device is found by its backing file's name, which holds the VM's serial: the kernel shows that file's path as the reader's mount namespace sees it, which differs under the helper's unit and after a helper was started again (`is_backing_file`).
3. The node's end of the veth (its peer goes with it), then the namespace, with its tap device and its table.
4. The node's table of the VM. It counts as gone only when `nft list table` fails with its own "No such file or directory" (`table_is_gone`): nft ends with the same status when it may not ask the kernel, and that is no answer.
5. The cgroup.
6. The jail's file system (a lazy unmount: everything its user made goes with it, at once), then the jail's directory, and only when nothing above is left: a VM that could not be removed stays listed. A helper that was started again has a mount namespace of its own, in which an earlier helper's jails are not mounted: such a jail's file system ends with its VMM, and its chroot is an empty directory. Where a jail's directory does hold what a VM's user left (a helper that runs without a mount namespace of its own and was stopped before the unmount), the removal copes with it (`src/tree.rs`): it never goes deeper than one level, where `std`'s ends at the limit of open files; it keeps no list of what it found, so a directory of millions of entries costs it no memory; and it makes itself the owner of every directory before it empties it, since the helper is root without `CAP_FOWNER` and could otherwise remove nothing from a directory on which the user set the sticky bit. Nothing else that a user without capabilities can set on its own files stops root with `CAP_DAC_OVERRIDE` and `CAP_CHOWN`: modes and ACLs do not bind it, the immutable and append-only attributes need `CAP_LINUX_IMMUTABLE`, and a mount needs `CAP_SYS_ADMIN` or a user namespace, which the node's sysctl forbids.

A delete of a VM that is half made, or of one that is gone, succeeds.

**Gone, or could not ask.** Each of these checks has three answers (`Presence`): there, gone, and unknown. A path is gone only on "no such file or directory"; the VM's processes are gone when its cgroup's `cgroup.procs` is read and empty; the snapshot's device and its loop device are gone when `/sys/block` is read and none has the name. A directory that cannot be read, a tool that cannot run, a permission that is missing: each is unknown, the delete fails and names it ("…, perhaps"), and the VM stays listed with its slot. Every process of a VM is in its cgroup (the jailer enters it before it forks, and a VMM may write no other cgroup's `cgroup.procs`), so the delete asks the cgroup and not the user's process list; the kernel refuses to remove a cgroup that still holds a process, which is the second check.

### The list

Every entry under `<chrootBase>/firecracker/`, with its names where its name is of the helper's form, and `alive` while a process named `firecracker` runs in the VM's cgroup and the node's table of the VM is loaded. A flush of the node's whole ruleset (`nft flush ruleset`, which a distribution's `nftables.service` does when it loads its own file) removes that table under a running VM: nothing reaches the VM any more, so it is reported as `failed`, the control host ends its run, and its delete cleans up. The table is not loaded again: the helper would hide that the node's rules were flushed, its own firewall with them. Such a flush removes the node's own table too, so the helper makes no new VM until the table is loaded again, unless `nodeFirewall` is `"not-required"`. A helper that is started again finds its VMs still running: each VMM is in a cgroup of its own, outside the helper's.

### The units

`units/` holds what ran on a node, for the role to install:

| File | What |
|---|---|
| `tinfirm-node-agent-modules.service` | Loads the kernel modules once at boot, and copies the kernel's list of built-in modules to where the helper can read it |
| `tinfirm-node-agent.sysctl.conf` | `net.ipv4.ip_forward = 1`, and `user.max_user_namespaces = 0`: nothing on a node needs a user namespace, and a process that got out of a VMM would use one |
| `tinfirm-node-agent.nft`, `tinfirm-node-agent-firewall.service` | The node's own firewall, and the unit that loads it before the agent starts. Below, "The node's firewall" |
| `tinfirm-node-agent.tmpfiles.conf` | The directories, with the owners and modes the helper and the listener check; `/run/netns`, which the helper's unit must find |
| `tinfirm-node-agent-helper.service` | The helper: eight capabilities (the jailer's six, `CAP_SETPCAP` to cut the bounding set, `CAP_NET_ADMIN`), no IP address family, a read-only file system but for its own places, a closed device list, a system call filter. Its VMs outlive it without `KillMode=process`: each VMM is in a cgroup of its own, outside the unit's |
| `tinfirm-node-agent-listener.service` | The listener: its own user, no capability, no device, its two directories. The role adds `IPAddressAllow=` with the control host's address in a drop-in |
| `tinfirm-node-agent-hardening.service` | A oneshot that runs `tinfirm-node-agent harden` at each boot, after the modules', the firewall's and kdump's units and before the helper. The helper's unit has `Wants=` and `After=` on it, not `Requires=`: a hardening that fails lowers the mark and stops nothing. Only `CAP_SYS_ADMIN` (the kernel asks it for a write of the lockdown level), no network, a read-only file system; `ProtectKernelTunables` and `ProtectKernelModules` are left out, as its comments say why. Below, "Hardening at each boot" |
| `tinfirm-vmm.apparmor` | The VMM's AppArmor profile. Below, "The VMM's profile" |

Each of the helper's eight capabilities was removed in turn on a node: every one is needed, and each failure left nothing of the VM. Four settings are left out of the helper's unit, each for a reason found on a node (`ProtectKernelTunables`, `ProtectControlGroups`, `PrivateNetwork`, `PrivateIPC`); the unit's own comments say why. The confinement narrows a flaw's path; it does not make a helper under an attacker's control harmless: `CAP_SYS_ADMIN` with the device-mapper is root in effect.

### The bounding set

The helper does not start the jailer itself. It starts `/proc/self/exe`, its own program, as `tinfirm-node-agent start-jailer JAILER ARGS…`. That process checks what it was given, drops every capability but `CAP_CHOWN`, `CAP_DAC_OVERRIDE`, `CAP_SETGID`, `CAP_SETUID`, `CAP_SYS_ADMIN` and `CAP_MKNOD` from its own bounding set, and replaces itself with the jailer; the VMM inherits that set (`CapBnd: 00000000082000c3`) and holds none of it. The subcommand runs before the binary starts any other thread: a bounding set is a thread's own.

| `start-jailer` accepts | Anything else |
|---|---|
| `JAILER`: an absolute path to a file named `jailer`, a regular file of root's that neither its group nor others can write, not a link | Refused, nothing is done |
| `ARGS`: the jailer's arguments, starting with `--id` and a jail's name of the helper's form | Refused, nothing is done |

It reads no configuration and no environment, and hands the jailer an empty environment. The binary is not set-uid and has no file capability, so the subcommand has its caller's rights and no more: a caller without `CAP_SETPCAP`, which is every unprivileged user, gets "cannot cut the capability bounding set", status 1, and no jailer is started; root gets the jailer of its own file system with fewer capabilities than root has.

**What the bounding set does not do.** It bounds what an exec of a set-uid program or of a file with capabilities could gain. It does nothing against a new user namespace, in which the kernel gives a process a full set of that namespace's own. That is closed for the VMM by Firecracker's own system call filter and by the helper unit's `RestrictNamespaces`, which the VMM inherits, and for every process of the node by `user.max_user_namespaces = 0` (`units/tinfirm-node-agent.sysctl.conf`).

### The node's firewall

`units/tinfirm-node-agent.nft` (ADR-0112 decision 4, "A node reaches nothing of Tinfirm's"). The agent does not load it; the role loads it (`tinfirm-node-agent-firewall.service`) before the agent starts, with the node's values in the `define` lines at its head. The helper's unit requires that unit, which shows only that the table was loaded once. So the helper asks for the table itself, in `check` and before each make, and by default makes no VM without it (`nodeFirewall`, above). A node that is meant to run without the table says `"nodeFirewall": "not-required"` in `helper.json`; the tests of `tests/linux_kvm.rs` do.

| It admits | |
|---|---|
| To the node | The control host to the agent's port; one management address to SSH; loopback; the answers of connections it admitted; the answers of the node's DHCP lease |
| Through the node | The control host's connections to the published ports, once a VM's own table has turned them to that VM's namespace (`ct status dnat`, with the original address and port), and their answers |
| From the node | Answers; loopback; the renewal of the node's DHCP lease |

It drops everything else, of both families, with a counter per chain: every other packet to the node (another machine at the agent's port, at a published port, at SSH), every other forward (a node forwards IPv4 for its VMs, and would otherwise route for any host of its subnet), and every connection that the node itself opens: to the control host on any port, the server, the model gateway, the code hosts, a resolver, a time server, the internet. The metadata addresses (`169.254.169.254`, `fd00:ec2::254`) are dropped by name, before every other rule, in the output and the forward chain.

What the node needs to stay manageable, and why each is there:

- **SSH from `management`.** The operator's one way in. It may be the control host; then a control host that is taken over reaches the node's SSH port, still not without a key.
- **DHCP** (UDP 68 to 67 and back). A node whose address comes by a lease loses it without the renewal. A node with a static address removes both rules.
- **Nothing else.** No resolver, no time server, no package mirror: the node's clock comes from its hypervisor or the operator opens a time server in the file, and the node is updated by replacing its image. An update in place needs its mirror in the file for that time: a rule added to the loaded table by hand is gone when the unit loads the file again, since the file replaces the whole table.
- **A node whose own start needs a network service** (a cloud image whose cloud-init asks a metadata service, an agent of the platform) does not finish its start with this table loaded at boot: on the Azure VM that ran, sshd waits for cloud-init, which waits for the metadata service the table drops. Such a node needs that start-up service switched off. The role `tinfirm_sandbox_node` refuses a node whose cloud-init would run at its next start and, when told (`tinfirm_sandbox_node_cloud_init: disable`), switches cloud-init off for good; another start-up service of a platform is the operator's to find.

It is one table (`inet tinfirm_node`), and loading it again replaces that table alone. It never flushes the ruleset: the helper keeps a table per VM (`inet tinfirm_<12 hex digits>`), which a flush would remove under running VMs. A packet must pass the chains of every table on a hook, and a drop in one is final, so this table and a VM's table each admit a bridge's connection on their own terms and neither can widen the other. The helper's own checks read only its own tables, and whether this one is in the list of tables.

Each chain counts what it drops. The output chain's counter ("dropped: from the node") grows by one at every `verify`: that is the listener's datagram of "The node's own egress" (below), refused as it should be, and no sign of an attack.

**Root on the node can delete this table.** It is the node's guard against a VMM or a listener that got out. Against root on a node the guard is the operator's network outside the node, as ADR-0112 says. When the table is deleted, or the ruleset flushed, the node takes no new VM: its `check` names the missing table, and the helper refuses a make. The VMs that run are not stopped: their runs go on until they end, without the node's table around them, while each VM's own tables still hold (a flush removes those too, and such a VM is then reported as `failed`). `systemctl reload tinfirm-node-agent-firewall.service` loads the table again, and the next `check` is clean. The check and the make are not one step: a table deleted in between is found at the next `check` or make.

## The node's own egress

ADR-0112 decision 4: `verify` tries the node's own egress, and a node that gets through takes no VM (the owner, 2026-11-21). `src/egress.rs`.

**What is tried.** In each `verify`, after the helper's `check`, the listener sends one empty UDP datagram from a fresh, non-blocking socket to the control host's address of its own configuration (`tls.controlHost`) on port 9, the discard port, and reads what `sendto` answers. Nothing a caller sends chooses the target, and no name is resolved: the node has no resolver.

**Why the listener, and why a datagram.** The helper has no network code. The listener's unit allows it the control host's address alone (`IPAddressAllow=`), so on the node only netfilter can stop that datagram, and the node's own table drops every new flow that the node opens. The kernel answers a drop or a reject of a locally made packet at once, with `EPERM` to `sendto`, and lets a datagram that passed every output hook go with success. A TCP connection could not tell the node's verdict apart: a drop on the node and a drop beyond it are the same silence, and a reject on the node looks like the far side's reset. So the try needs no timeout and no cache, and costs one system call. Measured with UDP and TCP in a network namespace, with an output chain of the file's form (policy `drop`), with a `reject` rule and with none, and then on a node (below).

| `sendto` answers | `nodeFirewall` `"required"` | `"not-required"`, or a helper that does not say |
|---|---|---|
| `EPERM` or `EACCES`: refused on the node | A line of `checked`: "…was refused on the node, as its firewall table drops it" | The same |
| Success: the datagram passed the node's firewall | **A problem**: the control host places no VM on the node until the table drops it again | A line of `checked` that starts with `NOT HELD:` |
| Anything else (no socket, no route, no buffer), or a control host on loopback, which the table admits | A line of `checked`: "…could not be tried: …" | The same |

Each line names its target as `udp:ADDRESS:9`. A listener without `tls` (the single machine, set aside) tries nothing and says so. The helper's `make` does not repeat the try: it has no network. So it is the driver that places no VM on a node with this problem, as it does with every problem; the helper refuses a make itself only while the table is missing.

**On a real node** (2026-11-22; the outputs are not in this repository), in four cases. With the node's table loaded, each `verify` had the line "…was refused on the node, as its firewall table drops it", and the table's counter "dropped: from the node" grew by one per `verify`. With the table deleted, `verify` named the missing table and the datagram that passed. With a table of the right name whose output chain accepts, the helper's check of the name passed and only the try named the problem: a new run waited with `RUNNER_HOST_UNHEALTHY` and that cause, and went on by itself 12 seconds after the real table was loaded again; the control host received one empty datagram on UDP port 9 per `verify` while the table let it pass. With `"nodeFirewall": "not-required"`, a line of `checked` that starts with `NOT HELD:`, and VMs were still made.

**What it shows.** That the node's own network namespace refuses a new flow from the node to its control host. The output chain matches no user, so that holds for root and every other user of that namespace, as long as the loaded table is the node's file. It catches a table that is missing or flushed (which the helper also names), and one that is loaded but does not drop the node's own packets: replaced under its name, or edited.

**What it does not show.** Egress to any other address: the server, the model gateway, the code hosts, another node, a metadata service, the internet (the listener's unit refuses those itself, so a try would prove nothing); a table that treats TCP, or other ports, otherwise than this datagram; IPv6, unless the control host's address is IPv6; a VMM's own egress, which its VM's namespace and the forward chain decide (the helper reads them back at each make); another network namespace on the node with a way out of its own; anything root does after the try; and the network outside the node, which stays the operator's to close. The operator's own check after the play stays (`deploy/ansible/README.md`).

**What it costs.** On a node whose table holds, nothing leaves the node: the datagram is dropped before any interface, and the output chain's counter grows by one. On a node whose table lets it pass, one empty datagram goes to the control host's port 9 at each `verify`, which tells an observer of the wire no more than that the node has lost its guard.

## The hardening mark

ADR-0113, decision 5: a second mark beside the level, `yes` or `no`, for how little of the kernel a process finds behind the jail. `src/hardening.rs` reads each line in `check`, and `verify` passes the answer on as it is:

```json
"hardening": {"hardened": "no",
  "lines": [{"line": "io-uring", "holds": false, "found": "kernel.io_uring_disabled is 0, not 2"}]}
```

- `hardened` is `yes` when every line holds, and `no` otherwise. A reader decides by it, not by the line ids, so a later build can add a line.
- `found` is one sentence of what was read and its value, at most 200 characters. What the machine gave (a value, a path) is escaped and cut.
- A fact that cannot be read (a missing file, one that is not text or too long, a value that is not a number, a listing not of the kernel's form) fails its line and says so.
- **The helper only reads.** It writes no setting; the role sets the lines up (`deploy/ansible/README.md`, "The hardening"). With the role's defaults, which load the VMMs' profile in enforce mode since 2026-11-22, the mark is `yes` when every line holds; with the profile in complain mode it is `no`, for `vmm-mac` alone.
- **A line that does not hold lowers the mark and stops nothing** (the owner, 2026-11-20). It is not among `check`'s problems: the node keeps its slots and makes VMs.
- **The control host reports it.** The runner host's `node` driver reads the mark, and the host sends `hardened` in its `hello` ([runner/README.md](../../README.md#isolation-level)). On one node `verify` and `doctor` showed it, the server stored it, and a line set back by hand while the host ran reached the server by a new hello (2026-11-22).

| Line | What the helper reads | It holds when |
|---|---|---|
| `user-namespaces` | `/proc/sys/user/max_user_namespaces` | 0 |
| `unprivileged-bpf` | `/proc/sys/kernel/unprivileged_bpf_disabled` | 1 or 2 |
| `io-uring` | `/proc/sys/kernel/io_uring_disabled` | 2. A kernel without the file (older than 6.6, such as Debian 12's 6.1) fails the line, which names the kernel's release |
| `vmm-mac` | `/sys/module/apparmor/parameters/enabled`; `/sys/kernel/security/apparmor/profiles`; for each VMM that runs, `/proc/<pid>/attr/apparmor/current`, or `/proc/<pid>/attr/current` on a kernel without the first | AppArmor is on (`Y`), the profile `tinfirm-vmm` is loaded in enforce mode, and every running VMM's label is `tinfirm-vmm (enforce)`. The VMMs are the processes named `firecracker` in the cgroups of the helper's jails; a jail of a form the helper does not know, or a label that cannot be read, fails the line. A machine without AppArmor fails it: the check of SELinux labels is planned (slice S5) |
| `panic-on-oops` | `/proc/sys/kernel/panic_on_oops` | 1 |
| `modules-disabled` | `/proc/sys/kernel/modules_disabled` | 1 |
| `kexec-disabled` | `/proc/sys/kernel/kexec_load_disabled` | 1. A kernel without the file fails the line |
| `lockdown` | `/sys/kernel/security/lockdown` | The level in brackets is `integrity` or `confidentiality`. A kernel without the file has no lockdown, and fails the line |
| `distribution-hardening` | `kernel.kptr_restrict`, `kernel.dmesg_restrict`, `kernel.perf_event_paranoid`, `kernel.randomize_va_space`, `fs.protected_symlinks`, `fs.protected_hardlinks` under `/proc/sys` | At least 1, 1, at least 2, 2, 1 and 1. These narrower facts stand for "the distribution's hardening on" (the owner, 2026-11-20) |
| `no-docker` | `/run/docker.sock`, `/var/run/docker.sock`, `/run/containerd/containerd.sock`, `/run/podman/podman.sock` | None of them is there, of any file type |
| `no-setuid` | Every entry under `/usr` and `/opt`, and under `/bin` and `/sbin` where they are directories and not links into `/usr`; links are not followed, and no other file system is entered (as `find -xdev`) | No regular file with the setuid or setgid bit can be run by others, or by a VMM's user as the file's owner or through the file's group (a VMM's user and group are one number, `vmUidBase` plus its slot). A VMM's user is in no supplementary group (the helper checks each VMM's `Groups`). The scan is cached for 10 minutes, so a file made setuid shows at most 10 minutes later. More than 2,000,000 entries fail the line |
| `listening-sockets` | `/proc/net/tcp` and `/proc/net/tcp6`, of the helper's own network namespace, which is the machine's | No TCP socket in the LISTEN state on an address other than loopback (`127.0.0.0/8`, `::1`, `::ffff:127.0.0.0/104`), but on `hardening.agentPort` and `hardening.sshPort`. A kernel without IPv6 has no `tcp6`, which counts as no IPv6 socket. UDP is not part of the line: a DHCP client's socket would fail every cloud machine |
| `max-uptime` | `/proc/uptime` | At most `hardening.maxUptimeDays` days since the boot (the role's default is 30). It stands for "the kernel patched": a node that was neither re-imaged nor rebooted reports `no` |

Not a line: "the kernel patched" itself (the release and the boot time are in `checked`, above), "no SSH but the operator's path" (the node's firewall admits SSH from the management address only, and `check` requires that table), and "the helper the only root process of Tinfirm's" (it holds by the units and the jail).

### Hardening at each boot (`harden`)

Three lines hold only until a reboot: kexec off, lockdown, module loading off. `tinfirm-node-agent harden`, run by `tinfirm-node-agent-hardening.service` at each boot, sets them again, as `/etc/tinfirm/node-agent/hardening.json` asks. The role writes that file from its switches:

```json
{"modulesDisabled": true, "kexecDisabled": true, "lockdown": "integrity"}
```

Exactly these three fields, each key once; `lockdown` is `"integrity"` or `"none"`. The file must be root's, and neither its group nor others may write it, as `helper.json`.

| Step, in this order | What `harden` does |
|---|---|
| `kexecDisabled` | Writes `1` to `kernel.kexec_load_disabled` unless it is 1. First, and after kdump's unit, so that a crash kernel loaded at boot stays loaded |
| `lockdown` | Writes `integrity` to `/sys/kernel/security/lockdown` only when the level in brackets is `none`: the kernel refuses a write of the level it is at, and a lower one. A kernel without lockdown is a refusal |
| `modulesDisabled` | Writes `1` to `kernel.modules_disabled` unless it is 1, and only when every module the agent needs is loaded or built in: the modules of "What a node needs", `loop` and `tun` (whose device nodes load them when first opened, which fails once loading is off), and `kvm_intel` or `kvm_amd`. Otherwise it leaves loading on and names each missing module. Last, since nothing loads a module after it |

- **Each step prints one line**, what it found and what it did, to the unit's journal. A switch that is `false` leaves its setting as it is and says so.
- **A step that is refused does not stop the others.** `harden` then ends with an error, and the unit fails. The helper runs anyway, and the mark says `no`.
- **Each write is read back.** After writing, `harden` plans again; a setting that does not read as set is an error ("did not take").
- **A second run writes nothing** and ends with success. The plan is a pure function of the configuration and the values read (`harden::steps`), and both reading and writing go through a root path, so that the tests run without root.
- **What it costs.** Once module loading is off, a module that is needed later (a new firewall expression, a tool) cannot be loaded until a reboot. With kexec off, kdump keeps the crash kernel it loaded at boot, and no other can be loaded.

### The VMM's profile

`units/tinfirm-vmm.apparmor`, the profile `tinfirm-vmm` (ADR-0113, decision 5): one profile for every VM's VMM. CI parses it (`apparmor_parser -Q -K`, which checks its syntax and its rules' form). On 2026-11-22 it ran on one node (Ubuntu 24.04, kernel 7.0; the outputs are not in this repository), loaded by the role: in complain mode no `apparmor="ALLOWED"` line for `tinfirm-vmm` over the VMs' whole life (a command run under the profile by hand produced such lines at once, so the logging works), and in enforce mode no `DENIED` line, also with `guestConsole` written through the inherited descriptor; the helper read each VMM's label although its unit has `ProtectProc=invisible`. Also on 2026-11-22, on a node from an Ubuntu 24.04 cloud image, a real conversation, a run of the tests' stand-in agent and a make that failed on purpose after its VMM started ran under it in enforce mode, and the kernel logged no `apparmor="DENIED"` line for `tinfirm-vmm` (the outputs are not in this repository). So the role loads it in enforce mode by default since then, with `vmmProfile` `"required"` (the owner, 2026-11-22). That is one distribution and kernel. On another, load it in complain mode first (`tinfirm_sandbox_node_harden_mac: complain`) and read the `ALLOWED` lines; where VMs stop starting in enforce mode, the kernel's log has `apparmor="DENIED"` lines of `tinfirm-vmm`: set complain mode, and report them.

It attaches by the exec's path: the helper, `start-jailer` and the jailer are unconfined, and the jailer execs `/firecracker` after its `pivot_root` into the jail. It grants no capability and no `/proc` or `/sys` rule. Each rule comes from what `src/linux.rs` hands the VMM:

| Rule | From |
|---|---|
| `/firecracker mr` | Its own binary, the jailer's copy (`--exec-file`) |
| `/vmlinux r`, `/root.disk rw`, `/userdata.disk r` | `firecracker_config`: the kernel, and the two drives with their `is_read_only` |
| `/vm.json r`, `/firecracker.log rw` | `jailer_argv`: `--config-file`, `--log-path` |
| `/run/firecracker.socket rw`, `network unix stream`, `unix (…) type=stream` | `jailer_argv`: `--api-sock`; the helper connects to it (`vmm_runs`) |
| `/dev/kvm rw`, `/dev/net/tun rw`, `/dev/urandom r` | The devices the jailer makes in the jail; the tap device is `firecracker_config`'s `host_dev_name`. `/dev/urandom` stays: whether Firecracker opens it is not known, and no run can show it while the rule allows the open; `/dev/userfaultfd` is left out, as it serves only a snapshot's restore |
| `/dev/null rw`, `<work>/tfvm-*.console w`, `<work>/tfvm-*.errors w` | What it inherits from `build`: standard input, standard output (the console with `guestConsole`) and standard error. They were opened outside the jail, so their paths are disconnected from it (`attach_disconnected`). Inferred, not shown: how AppArmor names those paths |
| `signal (send, receive) peer=tinfirm-vmm`, `signal (receive) peer=unconfined` | Its threads signal each other; the helper ends it |

The paths assume the role's defaults (`chrootBase` `/srv/tinfirm-jails`, `work` `/var/lib/tinfirm-node-agent/work`). A Red Hat family node has SELinux and no AppArmor; its form is planned (slice S5).

## Decisions where the ADR left the wire open

| Decision | Reason |
|---|---|
| The listener makes the VM's id, `vm-` and 128 random bits in lower-case hex | The driver protocol's `create` returns the id, so the agent makes it. One fixed form is what the helper checks. Random ids never repeat and name no run |
| The helper's wire is one request per connection, a line of at most 4 KiB | The peer check runs on every request, and no request can carry user data: it travels as a file |
| `v` in every helper request; `hello` with `wire` and `build` on the driver's wire | The ADR asks the two sides to check each other's build. A helper that was not restarted with its listener is caught by `v` |
| Both wires refuse a key that appears twice, and `params` that is not an object | "Exactly these fields" must hold for what a reviewer reads, not only for what serde keeps |
| A VMM that died is pushed as a `vmFailed` notification on every open connection, and `describe` and `list` say `failed` | "At once" needs a push: the driver need not poll. The driver protocol between the runner host and its driver has no notification, so the `failed` state keeps a thin relay correct without it: the runner host's `ended` already polls `describe` every 3 s and ends the run on `failed`. A driver that relays the notification ends it sooner. A JSON-RPC client that ignores messages without an id, as the runner host's does, is unaffected |
| The listener watches the helper's `list` every `watchMs` (1 s) | The helper has no wire to push on; one request a second on a local socket costs nothing that shows |
| The listener keeps each VM's name and labels in a record of its own; the helper never sees them | The helper takes an id and a template and nothing else. The labels are needed after a listener's restart, for `list` and the runner host's leftover cleanup |
| A `delete` must name `tinfirm-host` and match each label it names | As DigitalOcean's driver does: one host never deletes another host's VM |
| A slot gives the user, the addresses and the port; a serial that is never reused gives every name and the jail directory; the jail's name carries the slot too | The ADR asks for both: nothing shared between live VMs, and a jail directory used once. With the slot in the name, a leftover without a record still says which slot it holds |
| TLS connections take a served place only after the handshake with the pinned certificate; pending handshakes have a small budget of their own, and a new one drops the oldest | An unauthenticated peer must not be able to hold the places the control host needs |
| `describe` answers `address` (the configured node address) and `port` (the slot's published port) | ADR-0112 decision 4, "The driver protocol": a node publishes each VM's bridge on a port of its own address, so the VMs' addresses never leave the node |
| `verify`'s `maxVms` is the node's free slots | The `node` driver adds its nodes' together (ADR-0112, "Several nodes behind one driver"; planned in the driver) |
| Both sockets are 0666 in a directory of 0750, whose group is the one peer's, and every connection's uid is checked | The listener cannot give its socket to a group it is not in without root; the directory keeps others out, and the uid check is the rule |
| Certificates and keys are DER files | The workspace's `rcgen` and `rustls` have no PEM support turned on, and no crate is added for it |
| TLS 1.3 only | As the sandbox bridge's |

## Tests

`cargo test -p tinfirm-node-agent` runs them all, without root where it can. The tests of `tests/linux_kvm.rs` make real VMs: they run only with `TINFIRM_NODE_KVM_TEST=1`, as root, on a machine with `/dev/kvm`, and otherwise say why they skip (the file's head lists what they need and what they change on the machine).

| What | Where |
|---|---|
| Every malformed id is refused (trailing newline, CR LF, leading or trailing space, upper case, `/`, `..`, NUL, too long, too short, empty, not ASCII), in bytes and in JSON | `id.rs` |
| A request with an extra field, a missing field, a key twice at any level, another or no `v`, a malformed id or template is refused; `params` absent, an array, a string or `null` is refused | `strict.rs`, `helper.rs`, `wire.rs`, `tests/agent.rs` |
| A refusal holds no byte of the peer: a field name, an `op`, an id or a `v` with a newline and an escape character, and a method name with them, reach neither the message nor the log | `helper.rs`, `wire.rs` |
| An unknown template is refused by `make`, `check`, `verify` and `create` | `helper.rs`, `tests/agent.rs` |
| The user data file: a link (to a file, dangling, to a directory), a directory, a FIFO, two names, too large, empty, missing, another owner | `spool.rs`, on real files; the owner by `chown` where the test is root, and by the check on made-up facts always, as for the socket and device kinds |
| The spool: a link to a good spool, a spool of another owner, a group-writable parent, and every case of the check on made-up facts | `spool.rs` |
| The helper's configuration file: another user's, one that its group or others can write, and what is not a regular file, on made-up facts and on a real file | `plan.rs` |
| A peer that is not the listener's uid gets nothing from the helper | `helper.rs` (the helper expects another uid than the test's, where the test is root); `tests/agent.rs` runs a real process as uid 65534 with `setpriv` against a helper that expects 4242 and one that expects 65534, where the test runs as root and has `setpriv` and `python3`, and skips otherwise |
| The helper serves at most 32 connections, and refuses one that sends no request within 5 s | `helper.rs` |
| `nodeFirewall`: `"required"` by default, `"not-required"`, any other value refused; the node's own table there, missing or not listed, with either value, as a line of what was checked or a problem; with the table missing and required, `check` and `verify` name it, the helper refuses a make with no slot or serial used, and the VM that runs stays; with `"not-required"` a make goes on | `plan.rs`, `helper.rs` (the fake backend), `tests/agent.rs` |
| The hardening mark on a fake root: every line holding gives `yes`; each line off in turn gives `no` and names that line alone; a kernel without `io_uring_disabled` is named; no AppArmor, with and without SELinux; a VMM unconfined, a label that cannot be read, the older `attr/current`, VMMs that cannot be listed; files missing, not text, too long, a directory, malformed; random content in every file (no panic, every `found` within its bound and on one line); listeners on loopback, IPv4-mapped loopback, the agent's port and SSH are fine, one on another port over IPv4 and IPv6 is not; no `tcp6` is no IPv6; a setuid file that others cannot run is fine, links are not followed, a path is escaped; uptime just under, at and just over the limit; a configuration without `hardening` fails the two lines that need it; the setuid scan walks again only once 10 minutes have passed, on an injected clock | `hardening.rs` |
| `hardening` in `helper.json`: optional, all three fields or none, none of them 0, `maxUptimeDays` 1 to 3650 | `plan.rs` |
| `check` carries the backend's mark, and a line that fails is no problem and stops no make; a check answer is read with or without `hardening`, and with nothing else | `helper.rs` |
| A VMM's label that cannot be read names the error's kind: `/proc/<pid>` not there, an attr file not there, another error; a label other than `tinfirm-vmm (enforce)` is named | `hardening.rs` |
| `vmmProfile`: `"not-required"` by default, `"required"`, any other value refused; carried into each VM's plan | `plan.rs` |
| With `vmmProfile` `"not-required"` an unconfined VMM is made; with `"required"` an unconfined VMM, one in complain mode, and one whose label cannot be read each fail the make, which names what it found, and leave nothing; one under the profile in enforce mode is made | `helper.rs` (the fake backend) |
| `harden`'s plan: a fresh boot gets the three writes in order, a second run none; lockdown written only from `none`; a kernel without lockdown, a missing module (named), a module list that cannot be read, a value that cannot be read, each refused; each switch off leaves its setting; the configuration is exactly its three fields, each once, and a file others can write is refused; the modules read from `/sys/module` and the built-in list; the writes on a temporary root, one that does not take, a setting whose file is not there (never made) | `harden.rs` |
| The hardening's unit runs `harden` on its configuration, after the modules', firewall's and kdump's units, before the helper, without `ProtectKernelTunables` or `ProtectKernelModules`; the helper wants it and does not require it; the profile is `tinfirm-vmm`, attaches through disconnected paths, names each file `src/linux.rs` hands the VMM, and has no capability, `/proc` or `/sys` rule | `tests/units.rs` |
| The egress try: `sendto`'s `EPERM` and `EACCES` are refusals, a count a datagram that passed, other errors (no route, no buffer) unknown and named by their kind; only a datagram that passed on a node that requires its table is a problem, with either other `nodeFirewall` or none it is a line; the target is the listener's `tls.controlHost` on port 9, once, over IPv4 and IPv6; a listener without `tls` tries nothing; the real sender does not try a loopback address; a real datagram to `127.0.0.1:9` is answered at once (what the machine answered is printed, and only a wait fails the test) | `egress.rs` |
| The helper's `check` answer carries `nodeFirewall`, `"required"` and `"not-required"`; one without it is read, one with another value is refused | `helper.rs` |
| `verify` with the egress try refused, passed, unknown and refused again: a line, then a problem with the free slots still counted, then a line, then clean, one datagram per `verify`; passed on a node that does not require its table is a line; passed with a helper answer without `nodeFirewall` is a line; without a fake, the tests' loopback control host is not tried, and `verify` says so | `tests/agent.rs` |
| The helper's `check` and the agent's `verify` carry the mark as it is, a line that fails leaves `problems` empty and the node making VMs; a helper answer without it gives a `verify` without it, not an error | `tests/agent.rs` |
| A failed make whose cleanup fails keeps its slot until a delete succeeds; a leftover that cannot be removed keeps its slot; a jail of unknown form blocks every slot and `check` names it, escaped | `helper.rs` |
| A local peer that is not the runner host's uid gets nothing from the listener | `tests/agent.rs` (the listener expects another uid than the test's) |
| A TLS client with another certificate, or none, gets nothing; the driver refuses a node of another certificate | `tls.rs`, `tests/agent.rs` |
| A TLS connection from another address than the control host's is closed before its handshake, with the control host's certificate too, and more of them than there are handshake places each end within a second | `tests/agent.rs` (from 127.0.0.2), `listener.rs` |
| Idle TCP connections, more than the handshake places and the served places together, do not keep the control host out, and do not displace a served connection | `tests/agent.rs`, and the queue's own test in `listener.rs` |
| At most 16 served connections on each transport, the ones served staying served; at most 64 requests in flight, the next getting `-32007` | `tests/agent.rs` |
| Refusals are logged as one summary at most every 60 s | `listener.rs` |
| The listener refuses to start as root | `tests/agent.rs` (where the test is root) |
| At its start the listener empties its spool, drops a stale record and deletes an unrecorded VM; a `list` deletes an unrecorded VM again; a bad record is moved aside and a half-written one removed | `tests/agent.rs`, `listener.rs` |
| A failed create leaves no record and no spool file, and deletes at once what the helper could not remove | `tests/agent.rs` |
| `hello`, `verify`, `create`, `describe`, `list`, `delete` end to end over TLS and over the local socket, the listener and the helper in one process on the fake backend | `tests/agent.rs` |
| A VMM that dies is pushed as `vmFailed`, and describes as `failed` | `tests/agent.rs` |
| No two slots share a user, an address or a port; names and jail directories never repeat, and fit the kernel's limits; a leftover's slot is read back from its jail's name; a restarted helper never hands out a name in use | `plan.rs`, `helper.rs` |
| A control host on the node itself (its address or loopback) is refused | `plan.rs` |
| The Linux backend's planning: the jailer's arguments, the VMM's configuration, the snapshot table, the network commands with IPv6 off, the `inet` firewall rules (only what the published port's DNAT made is forwarded), the user-data disk, what is read back, a VMM's status, a loop device's backing file across mount namespaces | `linux.rs` |
| The bounding set is the six capabilities; `start-jailer` takes root's own `jailer` and a jail of the helper's form and nothing else, on made-up facts and as a caller runs it by hand; what it execs has the six as its bounding set (as root), or nothing runs (as any other user) | `bounding.rs`, `tests/start_jailer.rs` |
| A table is gone only when nft says that it does not exist (nft's own texts for a missing table, for a caller that may not ask, for a failed delete); a path is gone only on "no such file"; a module counts when it is loaded or built in, on files standing in for `/sys/module` and `modules.builtin` | `linux.rs` |
| The listener's line reader holds no byte of a line it has handed out, and its line's buffer does not move | `local.rs` |
| A made VM: its VMM is the slot's user with no capability, the cut bounding set, `NoNewPrivs` and a seccomp filter, in its own network, PID and mount namespaces, in its cgroup with the template's limits; the jail's files and their owners; the guest answers the control host on the published port, and nobody else anywhere (the next port, the guest's and the namespace's own addresses, another machine, the node itself); the console shows the kernel's address and `vm-boot` starting the run that only the user-data disk names; after the delete nothing is left, each kind looked for by name (processes, cgroup, namespace, veth, table, device-mapper device, its node, loop device, snapshot and console files, jail) | `tests/linux_kvm.rs` |
| A make made to fail after each of its six steps leaves nothing | `tests/linux_kvm.rs` |
| A VMM that was killed is listed as not alive, stays until its delete, and the delete leaves nothing | `tests/linux_kvm.rs` |
| A helper started over a jail without a record, in each of the six states a crash in a make can leave, removes it; one whose snapshot device something holds open stays listed and keeps its slot, a VM made meanwhile gets another slot and a later serial, and the next sweep removes the leftover | `tests/linux_kvm.rs` |
| Two VMs at once share no user, slot, serial, port, address, jail, cgroup, namespace, snapshot, device, table or veth; one's user cannot read the other's jail, disks or snapshot; `maxVms` holds | `tests/linux_kvm.rs` |
| Without `guestConsole` a guest writes nothing to the node | `tests/linux_kvm.rs` |
| ADR-0112 decision 8 on a node: a shell as a VM's VMM user, inside its jail and outside it (`vmm-user.sh`); a shell as the listener's user, and misuse of the helper's socket (`listener-user.sh`, `helper-misuse.py`); a root shell on the node with a canary ticket (`node-root.sh`) | `tests/attack/`, run by hand on a node; their outputs are in the evidence |

**Not tested here.** The tests of `tests/linux_kvm.rs` and the scripts of `tests/attack/` need a KVM node and root, so CI does not run them: their last results are in [the evidence](../../../docs/reviews/2026-11-19-node-agent-execution/README.md), which also lists what was not tested at all (a conversation through the agent, the node's hardening, ARM, a guest that floods its console). The hardening mark's checker, `harden` and the VMM's profile ran on one node on 2026-11-22 ("The hardening mark", "The VMM's profile"), and so did the egress try ("The node's own egress"); the outputs of that round are not in this repository. Later that day, on a hardened node with a server, a real conversation, a run of the stand-in agent and a make that failed after its VMM started ran under the profile, path A's test (with the VMM's seccomp filter off) and the test of the two processes ran, and every case was refused ([ADR-0112](../../../docs/adr/0112-no-credential-on-a-sandbox-machine.md), decision 8, "Run on machines"). Not shown: a second distribution or kernel.
