# tinfirm-node-agent

The node agent of [ADR-0112](../../../docs/adr/0112-no-credential-on-a-sandbox-machine.md) (Proposed). It runs on a sandbox node, and makes and deletes jailed Firecracker VMs when the control host asks. It holds no credential, has no connection to the server, does not know which run a VM is for, and never speaks with a guest.

It is a binary of its own, licensed under MIT like the rest of `runner/` outside `ee/`. It links no other crate of the workspace: it carries the driver protocol's server side, the jail, the network and the disks, and none of the runner host's code.

## What is built and what is planned

| Part | State |
|---|---|
| The listener: TLS with a pinned client certificate, the local socket, the agent's wire, the records of the VMs, the report of a VMM that died | Built, tested |
| The helper: its socket and peer check, its four operations, the checks of the id, the template, the spool and the user data file, the derivations, its record, its sweep of leftovers | Built, tested |
| The fake backend, for the tests | Built |
| The Linux backend's planning half: the jailer's arguments, the VMM's configuration, the snapshot's table, the network's commands, the firewall's rules, the user-data disk | Built, tested as pure functions |
| The Linux backend's execution: running those as root, the VMM's API, the removal of a VM's jail, snapshot, namespace, cgroup and tables | Planned. `make` and `delete` answer `not-built`; `check` reports it as a problem, so `verify` refuses the node |
| The VMM's capability bounding set, cut to the six capabilities the jailer needs (ADR-0112 decision 4) | Planned. No planning function expresses it: the jailer has no argument for the bounding set and leaves it full, so the helper must drop the others from its own bounding set in the process it starts the jailer from, before the exec. That is the execution's work, and it needs a system call that safe Rust's `std` does not offer |
| A single machine: the runner host and the node agent on one machine (ADR-0112 decision 5) | Planned. The local-socket transport is built. The single machine's own firewall table is not (refuse every packet from a VMM's user, and from the listener's user but for its port; check it before each VM). Until it is, the helper refuses a configuration whose control host is the node itself (its address or loopback): the prerouting DNAT never sees a connection that the node's own stack makes, and an output-hook DNAT matched on the source address alone would admit every local user |
| `verify` trying the node's own egress (the server, the model gateway, the code hosts, a metadata service) | Planned: the listener has no network but its sockets, so this needs a design of its own |
| The runner host's `node` driver (`--vm-driver node`), which relays the driver protocol to this listener | Built, in the runner host (`runner/crates/tinfirm-runner-host/src/node.rs`), tested against this agent with its fake backend over TLS and the local socket; no VM has run through it, since this agent's execution is planned |
| `vm-boot` reading the user data from the user-data disk | Built, in the runner host (`vm_boot.rs`), tested on files standing in for the device; not run in a real guest |
| Metrics for the operator | Planned. The agent logs to stderr (the service manager's journal) |
| The service units, the Ansible role, the release's signed binary | Planned |

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

The paths are the defaults the role is to use (planned). Each is set in a configuration file.

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
| `/var/lib/tinfirm-node-agent/helper/` | root | 0700 | The helper's state: `vms.json`, 0600, written whole and renamed into place. The helper refuses a directory that is not its own 0700 one |
| `/srv/tinfirm-jails/firecracker/<jail>/root/` | root, then the VM's user | (planned) | A VM's chroot, made by the jailer for one VM and never used again |
| `/var/lib/tinfirm-node-agent/work/<jail>.snapshot` | root | 0600 (planned) | A VM's own snapshot over the shared image, outside its jail |

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
    "work": "/var/lib/tinfirm-node-agent/work"
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
| `network.controlHost`, `network.nodeAddress` | Two different addresses; neither loopback nor `0.0.0.0`. A control host on the node itself is the single machine, which is planned (above) |
| `network.firstPort` | At least 1024; `firstPort + maxVms - 1` at most 65535 |
| `firecracker.firecracker` | A file named `firecracker`: the jailer names the chroot after it |
| `firecracker.cgroupParent` | One name of letters, digits, `-` and `_` |
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
| `tls.clientSha256` | The one client certificate the listener accepts |
| `socket.peerUid` | The runner host's user, the one peer; not 0 |
| `peerBuild` | `warn` (default) or `refuse`: what `hello` does with a driver of another build |
| `watchMs` | How often the listener asks the helper for VMMs that died: 100 to 60000 (default 1000) |

`tinfirm-node-agent identity --cert FILE --key FILE` writes a new key and its self-signed certificate, which must not exist, and prints the certificate's SHA-256: the node's for the driver to pin, or the control host's for `clientSha256`. `tinfirm-node-agent fingerprint --cert FILE` prints the SHA-256 of a certificate.

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
| TLS, before the handshake | 8 handshakes pending at once (`HANDSHAKE_SLOTS`), each within 3 s (`HANDSHAKE_TIMEOUT`) | A new connection drops the oldest pending handshake. Connections that say nothing therefore cannot keep the control host out: its handshake takes milliseconds, and it gets a place at once. A peer that opens more than 8 connections within those milliseconds, again and again, can still drop the control host's handshake each time: while it does, the runner host neither makes nor deletes a VM on this node, and nothing else is reached. The agent does not filter by source address; the node's firewall must admit only the control host to this port (the role's rule for it is planned) |
| TLS, after a handshake with the pinned certificate | 16 served connections (`MAX_CONNECTIONS`) | A 17th authenticated connection is closed after its handshake. Nothing displaces a served connection |
| The local socket | 16 connections of the runner host's uid | A 17th is closed at once; a peer of another uid is closed before it is read |

Refusals (a failed or late handshake, a dropped pending handshake, a full transport, a local peer of another uid) are counted by kind and logged as one summary at most every 60 s (`REFUSAL_SUMMARY_EVERY`), with kinds and counts only: a peer refused again and again cannot flood the journal.

A request has exactly `jsonrpc` (`"2.0"`), `method`, `params` and `id` (a number, or a string of at most 64 bytes), each once. Any other field is refused. `params` must be a JSON object: absent, an array, a string or `null` is refused with `-32602`. A request without `id` is a notification: the listener takes none and answers none.

**Versioning.** The first request on a connection is `hello`. Every other method before it gets `-32001`. `wire` is this wire's version, 1: another is refused. `build` is each side's build id; the release sets it (`TINFIRM_BUILD_ID` at compile time, planned), else it is the crate's version. The `node` driver sends the runner host's build id, which carries a digest of its runner images after `+` (`1.0.0+images.1a2b3c4d5e6f`): the check compares releases, what comes before `+` (`release_of`), on both sides. A driver of another release is served with a warning, or refused with `peerBuild: refuse`. The answer carries the agent's build, so the driver applies its own rule (its `peerBuild`, warning by default).

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
| -32004 | The backend cannot do it yet (the Linux backend's execution, planned) |
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
| `not-built` | The backend cannot do it yet |
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

So no two live VMs share a user, an address, a port or a name. A jail directory is made for one VM and never used again: a new VM gets a new serial, and so a new directory. A slot, and with it its user, addresses and port, is handed out again only once the backend no longer finds the VM that held it: a VM whose removal failed keeps its slot, whether it is in the record or a leftover. Whether the removal took every file the VM's user owned is the execution's to make sure of (planned). At each sweep the helper moves the next serial beyond every jail it finds on the node, so a lost record cannot hand out a name that is in use.

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

## The Linux backend (planning built, execution planned)

What a make is to do, in order, with the functions that produce each step's data (`src/linux.rs`):

1. The network (`network_commands`): a namespace, with IPv6 off in it (`all` and `default`) before any interface is there; a veth from the node into it, with IPv6 off on the node's end before it is up; and a tap device that the VM's user may open. So nothing of the VM's has an IPv6 address, link-local included. Each command is an argument vector, run without a shell.
2. The firewall, two tables of the `inet` family, so that their chains see IPv4 and IPv6 alike; the accepting rules match IPv4 alone. The node's own table for the VM (`node_rules`): the published port, from the control host alone, goes to the VM's namespace; from that namespace only the answers of such connections come back; any other packet from it, of either family, is dropped, to the node and through it. The namespace's table (`namespace_rules`), which root alone can change, with every chain's policy `drop`: the control host to the guest's bridge port over IPv4, the answers back, and nothing else, of either family, neither from the guest nor from the VMM. These rules filter by address and interface; what a guest sends in answer to the control host's connection still goes back to it, which is the bridge's purpose.
3. The disks: a loop device of the shared, read-only image; a sparse snapshot file of `snapshotMib`; a device-mapper snapshot over both (`snapshot_table`); the user-data disk (`user_data_disk`).
4. The jail: the device nodes of the root disk and the user-data disk, the kernel and the VMM's configuration (`firecracker_config`) in the chroot, owned by the VM's user, 0600.
5. The VMM: the jailer (`jailer_argv`) with the VM's user and group, its chroot, its network namespace, a new PID namespace and the cgroup limits; Firecracker with its API socket and its configuration file. The bounding set is cut in the process that runs the jailer (planned, above).

A delete removes the VMM, the cgroup, the jail directory, the snapshot device, its loop device and file, the namespace and the node's table, all named by the serial and the slot. `list` reports every entry under `<chrootBase>/firecracker/`, with its names where its name is of the helper's form; until the execution is built, none of them runs a VMM.

The execution needs KVM, Firecracker and root, and must be checked against the owner's prototype before it is built.

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

`cargo test -p tinfirm-node-agent` runs them all, without root where it can.

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
| Idle TCP connections, more than the handshake places and the served places together, do not keep the control host out, and do not displace a served connection | `tests/agent.rs`, and the queue's own test in `listener.rs` |
| At most 16 served connections on each transport, the ones served staying served; at most 64 requests in flight, the next getting `-32007` | `tests/agent.rs` |
| Refusals are logged as one summary at most every 60 s | `listener.rs` |
| The listener refuses to start as root | `tests/agent.rs` (where the test is root) |
| At its start the listener empties its spool, drops a stale record and deletes an unrecorded VM; a `list` deletes an unrecorded VM again; a bad record is moved aside and a half-written one removed | `tests/agent.rs`, `listener.rs` |
| A failed create leaves no record and no spool file | `tests/agent.rs` |
| `hello`, `verify`, `create`, `describe`, `list`, `delete` end to end over TLS and over the local socket, the listener and the helper in one process on the fake backend | `tests/agent.rs` |
| A VMM that dies is pushed as `vmFailed`, and describes as `failed` | `tests/agent.rs` |
| No two slots share a user, an address or a port; names and jail directories never repeat, and fit the kernel's limits; a leftover's slot is read back from its jail's name; a restarted helper never hands out a name in use | `plan.rs`, `helper.rs` |
| A control host on the node itself (its address or loopback) is refused | `plan.rs` |
| The Linux backend's planning: the jailer's arguments, the VMM's configuration, the snapshot table, the network commands with IPv6 off, the `inet` firewall rules, the user-data disk | `linux.rs` |

**Not tested here.** Anything of the Linux backend's execution: this container has no KVM and no Firecracker, and the execution is not built. The planned rules and commands are checked as text, not loaded into a kernel. A shell as the listener's user that tries the helper's things directly (ADR-0112 decision 8) needs the service units and a real node; the tests above check each refusal on its own.
