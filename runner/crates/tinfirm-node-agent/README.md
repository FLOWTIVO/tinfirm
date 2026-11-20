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
| The VMM's capability bounding set, cut to the six capabilities the jailer needs (ADR-0112 decision 4) | Built (`src/bounding.rs`), without unsafe code: the jailer has no argument for the bounding set and leaves it full, so the helper starts its own binary again as the hidden subcommand `start-jailer`, which drops the others from its own bounding set (`prctl(PR_CAPBSET_DROP)` through rustix) and then becomes the jailer. The helper refuses a VMM whose `/proc/<pid>/status` shows more. Below, "The bounding set" |
| The VM image for a local disk | Built: `runner/images/vm/local-disk.sh` runs `setup.sh` without a provider and writes one ext4 file. Run in real guests |
| A single machine: the runner host and the node agent on one machine (ADR-0112 decision 5) | Planned. The local-socket transport is built. The single machine's own firewall table is not (refuse every packet from a VMM's user, and from the listener's user but for its port; check it before each VM). Until it is, the helper refuses a configuration whose control host is the node itself (its address or loopback): the prerouting DNAT never sees a connection that the node's own stack makes, and an output-hook DNAT matched on the source address alone would admit every local user |
| `verify` trying the node's own egress (the server, the model gateway, the code hosts, a metadata service) | Planned: the listener has no network but its sockets, so this needs a design of its own |
| The runner host's `node` driver (`--vm-driver node`), which relays the driver protocol to this listener | Built, in the runner host (`runner/crates/tinfirm-runner-host/src/node.rs`), tested against this agent with its fake backend over TLS and the local socket. VMs were made, described, listed and deleted through it between two machines, driven by a script in the runner host's place (the evidence); no conversation has run through it |
| `vm-boot` reading the user data from the user-data disk | Built, in the runner host (`vm_boot.rs`), tested on files standing in for the device, and run in real guests: it starts the run's container from the disk's user data |
| Metrics for the operator | Planned. The agent logs to stderr (the service manager's journal) |
| The release's signed binary | Built: `tinfirm-node-agent-<version>-<target>.tar.gz` for `x86_64-unknown-linux-musl` and `aarch64-unknown-linux-musl`, static, on each release and on `rolling`, built beside the runner host's (`deploy/release/runner-musl.Dockerfile`) and listed in the release's `SHA256SUMS`, which is signed with the release key. No release has been made with it yet |
| The service units | Built (`units/`): the two services, the unit that loads the kernel modules, the directories and the one sysctl, as they ran on a node. Below, "The units" |
| The Ansible role | Written (`deploy/ansible/roles/tinfirm_sandbox_node`), with copies of `units/` that CI compares with this directory. It has not run on a machine yet |

## The two processes

| | The listener | The helper |
|---|---|---|
| Command | `tinfirm-node-agent listener --config FILE` | `tinfirm-node-agent helper --config FILE` |
| Runs as | An unprivileged user of its own (`tinfirm-node-listener`); it refuses to start as root | root, with no network |
| Takes | The control host's TLS connections, or the runner host's local socket | The listener's connections on its local socket |
| Checks | The client certificate's SHA-256, or the peer's uid; every request's form | The peer's uid on every connection; the id, the template, the request's fields; the spool and the user data file |
| Holds | Each VM's name and labels (its records); each VM's user data from just before its make to just after it; the node's TLS key | Its record of the VMs (id, template, slot, serial) and the next serial |
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
| `network.vmRange` | `A.B.C.D/P`, P from 8 to 29, no bit set below the prefix; room for `maxVms` slots of 8 addresses; holds neither `nodeAddress` nor `controlHost` |
| `network.controlHost`, `network.nodeAddress` | Two different addresses; neither loopback nor `0.0.0.0`. A control host on the node itself is the single machine, which is planned (above). The helper compares the two addresses of its own file and nothing else: a control host at another address of the node itself passes, since the helper cannot know the node's addresses without the network it does not have. Whoever installs the node checks that (the role compares the control host with every IPv4 address of the node) |
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
| `tls`, `socket` | At least one. A separated node has `tls`; `socket` is for the single machine (planned, above) |
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
| TLS, before the handshake | 8 handshakes pending at once (`HANDSHAKE_SLOTS`), each within 3 s (`HANDSHAKE_TIMEOUT`) | A connection from another address than the control host's (`tls.controlHost`) is closed at once and takes no place. A new connection from that address drops the oldest pending handshake. Connections that say nothing therefore cannot keep the control host out: its handshake takes milliseconds, and it gets a place at once. What is left: a peer that sends from the control host's own address (the control host itself, or a machine that can take its address in the node's network) and opens more than 8 connections within those milliseconds, again and again, can still drop the control host's handshake each time. While it does, the runner host neither makes nor deletes a VM on this node, and nothing else is reached. The node's firewall admits only the control host to this port as well (the role's rule for it is planned) |
| TLS, after a handshake with the pinned certificate | 16 served connections (`MAX_CONNECTIONS`) | A 17th authenticated connection is closed after its handshake. Nothing displaces a served connection |
| The local socket | 16 connections of the runner host's uid | A 17th is closed at once; a peer of another uid is closed before it is read |

Refusals (a connection from another address, a failed or late handshake, a dropped pending handshake, a full transport, a local peer of another uid) are counted by kind and logged as one summary at most every 60 s (`REFUSAL_SUMMARY_EVERY`), with kinds and counts only: a peer refused again and again cannot flood the journal.

A request has exactly `jsonrpc` (`"2.0"`), `method`, `params` and `id` (a number, or a string of at most 64 bytes), each once. Any other field is refused. `params` must be a JSON object: absent, an array, a string or `null` is refused with `-32602`. A request without `id` is a notification: the listener takes none and answers none.

**Versioning.** The first request on a connection is `hello`. Every other method before it gets `-32001`. `wire` is this wire's version, 1: another is refused. `build` is each side's build id. The release sets the agent's at compile time (`TINFIRM_BUILD_ID`, from `deploy/release/assets.sh build-id`): the workspace's version for a release (`1.0.0`), and the workspace's version with the asset's after `+` for `rolling` (`1.0.1-SNAPSHOT+rolling-abc1234`). A build without it takes the crate's version. The `node` driver sends the runner host's build id, which carries a digest of its runner images after `+` (`1.0.0+images.1a2b3c4d5e6f`): the check compares releases, what comes before `+` (`release_of`), on both sides. A driver of another release is served with a warning, or refused with `peerBuild: refuse`. The answer carries the agent's build, so the driver applies its own rule (its `peerBuild`, warning by default).

**The methods.** The driver protocol's (`ee/runner/tinfirm-runner-host/src/vm.rs`), with the same names, parameters and results, so that the runner host's `node` driver is a thin relay.

| Method | Parameters | Result |
|---|---|---|
| `hello` | `wire` (1), `build` | `wire` (1), `agent` (`tinfirm-node-agent`), `build` |
| `verify` | `template` | `problems` (what the helper and its backend found; any problem means the node must not be used), `maxVms` (how many more VMs the node may make now), `checked` (for the log) |
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

**Versioning.** Every request carries `v`: 1. Another value, or none, is refused. The listener and the helper come from one binary, so they change together; `v` catches a helper that was not restarted with its listener.

**The requests.** A JSON object with `v`, `op` and exactly the fields of its operation, each once. Any other field is refused.

| `op` | Fields | Answer (`ok`) |
|---|---|---|
| `check` | `template` | `problems`, `checked`, `free` (how many more VMs the node may make now) |
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

`check` (the wire's `verify`) names each of these that is missing, and a control host does not use a node that has a problem. `make` does not run the whole check again: it reads back what a VM cannot do without (the forwarding, the settings of its namespace, its two tables, the room for its snapshot) and fails with the reason.

| What | Why |
|---|---|
| `/dev/kvm`, `/dev/net/tun`, `/dev/mapper/control`, `/dev/loop-control` | KVM, the tap device, the snapshot, the loop devices |
| The tools `ip`, `nft`, `sysctl`, `losetup`, `dmsetup`, `blockdev`, `mknod` | What the execution runs |
| The modules `kvm`, `dm_snapshot`, `veth`, `nf_tables`, `nft_ct`, `nft_chain_nat`, `nft_nat`, `nf_nat` | The helper loads none (`units/tinfirm-node-agent-modules.service` does, at boot). A module counts when `/sys/module/<name>` is there, or when the kernel's list of built-in modules names it: a built-in module has an entry under `/sys/module` only when it has a parameter. The list is read from `/lib/modules/<release>/modules.builtin`, or, since the helper's unit hides `/lib/modules`, from the copy that the modules' unit makes at `/run/tinfirm-node-agent-modules/modules.builtin` |
| `net.ipv4.ip_forward=1` | A published port is a DNAT into the VM's namespace. The helper reads it and never writes it (`units/tinfirm-node-agent.sysctl.conf`). Forwarding makes the node a router for whatever reaches it: the node's own firewall (below) forwards nothing but the control host's connections to the published ports |
| A cgroup v2 hierarchy with the `cpu`, `memory` and `pids` controllers | The VMM's limits |
| `cgroup.kill` (Linux 5.14) | The one way the helper ends a VM's processes: it may not signal another user's process |
| Room in the work directory's file system for `maxVms` snapshots of the template | A snapshot file is sparse: without the room, one guest that fills its disk takes what another VM's writes need. `make` asks the same for one more snapshot beside what those that are there may still grow by |
| Memory for `maxVms` VMs of the template (`memoryMib` + 128 MiB each) within the node's | The VMMs' limits must not add up to more than the node has |
| The jailer, Firecracker, the image and the kernel: regular files of root's that only root can write | The helper runs the first two as root and hands the others to every VM |
| The jails' directory (root's, not writable by others) and the work directory (root's, 0700) | Where the VMs' files are |

Not checked, and the node's to make sure of: **no other firewall that drops forwarded packets** (Docker's `FORWARD` policy does, and so do ufw's and firewalld's defaults; a node keeps none of them, and no Docker after its image is built), and **a guest kernel with `CONFIG_IP_PNP`**, nf_tables, virtio-mmio and no need of an initrd. The kernel that ran is Kata Containers 4.3.1's `vmlinux-6.18.56-208` (`https://github.com/kata-containers/kata-containers/releases/download/4.3.1/kata-static-4.3.1-amd64.tar.zst`, SHA-256 `1463a2b86845f4d3d8e87f2f764ec59856fc0fee3520967a79ade32762be2e97`; the member `opt/kata/share/kata-containers/vmlinux-6.18.56-208`, SHA-256 `f87d8359035cb7557b16a319e6431ea09b183e62268aef436bf8dc6530d786b6`), with Firecracker and its jailer 1.14.2. That kernel is x86-64's; no kernel is pinned for aarch64 yet, though the agent is released for it: nothing ran on an aarch64 node.

**The shared image's loop device.** The image is attached once, read-only, and every VM's snapshot is over that device. A new image must be a new file moved over the old name (`mv`), never written in place: the running VMs keep the old file through the loop device until the last of them is deleted, and the kernel then frees it, while the next make attaches the new file under the same name. An image changed in place changes the disk under every running guest.

### A make

In this order; a make that fails at any step removes what it made, with the steps of a delete, and returns the first error.

1. **The mark** (`places`): the jail's own directory, root's, 0700. A make over a jail that is there is refused.
2. **The network** (`network_commands`): a namespace, with IPv6 off in it (`all` and `default`) before any interface is there, and forwarding on; a veth from the node into it, with IPv6 off on the node's end before it is up; a tap device that the VM's user may open. Then `network_read_backs`: each of those sysctls, and the node's own forwarding, must read `1`. A service manager that makes `/proc/sys` read-only lets `sysctl -w` end without an error in some setups; the make then fails with "the setting did not take" instead of leaving a VM that nothing reaches.
3. **The firewall**, two tables of the `inet` family, each loaded with `nft -f -` and listed again. The node's own table for the VM (`node_rules`): the published port, from the control host alone, is a DNAT to the VM's namespace, and only a connection that this DNAT made is forwarded there (`ct status dnat`): the namespace's own address answers nobody, the control host included. From that namespace only the answers of such connections come back; any other packet from it, of either family, is dropped, to the node and through it. The namespace's table (`namespace_rules`), which root alone can change, with every chain's policy `drop`: the control host to the guest's bridge port over IPv4, the answers back, and nothing else, neither from the guest nor from the VMM, whose own sockets get no packet out.
4. **The disks**: the shared image as a read-only loop device, attached once for all VMs (`read_only_loop`); a sparse snapshot file of `snapshotMib` and its loop device; a device-mapper snapshot over both (`snapshot_table`: not persistent, chunks of 4 KiB). A Docker pull and run written to such a snapshot took 36 s with chunks of 4 KiB and 38 to 40 s with 32 KiB, with the same size on disk and 1 to 3 MiB less kernel memory, so the chunk stays; the kernel's memory for a snapshot's chunks is charged to no cgroup, and `check` counts its measured worst case (160 bytes for each chunk, 320 MiB for 8 GiB written full at random) in the memory a VM may take.
5. **The jail**: the chroot is a tmpfs of its own with a size and a number of inodes (`jail_mount_options`), so the VM's user, who owns it, can fill nothing but it: not the jails' file system, not the work directory with the other VMs' snapshots, and no more memory than `jailMib`. It is root's until the jailer hands it over, with the guest kernel (a copy), the user-data disk (`user_data_disk`), the VMM's configuration (`firecracker_config`), Firecracker's empty log and the device node of the snapshot, each the VM's user's, 0600.
6. **The VMM**: the jailer (`jailer_argv`), started through `start-jailer` with the cut bounding set (below), in a process group of its own. Firecracker writes its own messages to `firecracker.log` in the jail (`--log-path`); its standard output, the guest's serial console, is the console file with `guestConsole` and `/dev/null` without; its standard error is `<jail>.errors`. The jailer's first process ends once the VMM runs in its new PID namespace; the helper then sets the template's `io.max`, if any, and waits, at most 10 s, until Firecracker's log says that the VM is started and its API reports a running guest, and fails at once when the VMM's cgroup is empty before that. The API is not asked earlier: a request that arrives while Firecracker gives up on its configuration ends its API thread first, and the log then never says why. A make that fails here says why in its error: the lines Firecracker marked as errors, and its standard error, as plain text of at most 400 characters. Last, the VMM's `/proc/<pid>/status` must show the VM's user and group in all four places, no capability in any set but the bounding set, a bounding set within the six, `NoNewPrivs` and a seccomp filter (`vmm_status_problems`); a VMM that shows more is removed and the make fails.

The helper overwrites the user data's bytes, and the disk it made of them, before it frees them (`spool::wipe`). The listener does the same with what it held of a `create`: it reads its requests through a reader that keeps no byte of a line it has handed out (`local::WipedLines`), into one buffer per connection that never moves and is overwritten once the line is parsed; a request overwrites the strings of its parameters when it is dropped, and the user data its text as soon as it is in the spool. What is not overwritten is outside the agent's own code: the TLS library's buffers and the JSON parser's scratch space, which their later use overwrites. A root shell on a node never found the ticket in the helper, and found it in the listener in some runs after one further call and in none after three: what stays is in those buffers until they are used again (the evidence, third round). A `create` that the listener refuses for its form is parsed from a copy that is dropped as it is, user data included. The spool file and the user-data disk are removed, not overwritten: the disk is in the jail's own file system, which is memory, and the spool is a directory on the node's disk for the moment between a `create` and its make, so a node whose disk is copied at that moment gives away a ticket, which is used up at the bridge's first connection. A spool on a tmpfs (`spool` in both configuration files) closes that.

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

Every entry under `<chrootBase>/firecracker/`, with its names where its name is of the helper's form, and `alive` while a process named `firecracker` runs in the VM's cgroup and the node's table of the VM is loaded. A flush of the node's whole ruleset (`nft flush ruleset`, which a distribution's `nftables.service` does when it loads its own file) removes that table under a running VM: nothing reaches the VM any more, so it is reported as `failed`, the control host ends its run, and its delete cleans up. The table is not loaded again: the helper would hide that the node's rules were flushed, its own firewall with them. A helper that is started again finds its VMs still running: each VMM is in a cgroup of its own, outside the helper's.

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

`units/tinfirm-node-agent.nft` (ADR-0112 decision 4, "A node reaches nothing of Tinfirm's"). The agent does not load it and works without it; the role loads it (`tinfirm-node-agent-firewall.service`) before the agent starts, with the node's values in the `define` lines at its head.

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
- **A node whose own start needs a network service** (a cloud image whose cloud-init asks a metadata service, an agent of the platform) does not finish its start with this table loaded at boot: on the Azure VM that ran, sshd waits for cloud-init, which waits for the metadata service the table drops. Such a node needs that start-up service switched off in its image, which is the role's to do; it is not solved here.

It is one table (`inet tinfirm_node`), and loading it again replaces that table alone. It never flushes the ruleset: the helper keeps a table per VM (`inet tinfirm_<12 hex digits>`), which a flush would remove under running VMs. A packet must pass the chains of every table on a hook, and a drop in one is final, so this table and a VM's table each admit a bridge's connection on their own terms and neither can widen the other. The helper's own checks read only its own tables.

**Root on the node can delete this table.** It is the node's guard against a VMM or a listener that got out. Against root on a node the guard is the operator's network outside the node, as ADR-0112 says.

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

**Not tested here.** The tests of `tests/linux_kvm.rs` and the scripts of `tests/attack/` need a KVM node and root, so CI does not run them: their last results are in [the evidence](../../../docs/reviews/2026-11-19-node-agent-execution/README.md), which also lists what was not tested at all (a conversation through the agent, the node's hardening, ARM, a guest that floods its console).
