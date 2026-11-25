# The node agent's execution on a real node (2026-11-19)

Evidence for the execution half of the node agent's Linux backend ([ADR-0112](../../adr/0112-no-credential-on-a-sandbox-machine.md) decision 4; [the crate's README](../../../runner/crates/tinfirm-node-agent/README.md)). Every claim below names the command that showed it and the file in this directory that holds its output. What was not tested is at the end.

## What ran, and on what

| | |
|---|---|
| The node | Azure Standard_D4s_v6 (4 vCPUs, 16 GiB, nested virtualization, Xeon Platinum 8573C), 10.0.0.6, no public address. Ubuntu 24.04.5, kernel 7.0.0-1017-azure, systemd 255, nftables 1.0.9, iproute2 6.1.0, device-mapper tools 1.02.185 |
| The control host | 10.0.0.4, in the same subnet |
| A third machine | 10.0.0.5, made for one check and deleted |
| The code | The branch `claude/zen-faraday-irqn49` at `7c3cf12`, with this change. The branch has moved since: from `41567f5` on, the listener's configuration needs `tls.controlHost`, which the listener of these runs did not have yet. Built on the node with rustc 1.100.0: the tests as a debug build, the installed agent as a release build for `x86_64-unknown-linux-musl` |
| Firecracker and the jailer | 1.14.2, the release's binaries, as `/usr/local/bin/firecracker` and `/usr/local/bin/jailer` |
| The guest kernel | Kata Containers 4.3.1's `vmlinux-6.18.56-208`, from `https://github.com/kata-containers/kata-containers/releases/download/4.3.1/kata-static-4.3.1-amd64.tar.zst` (SHA-256 `1463a2b86845f4d3d8e87f2f764ec59856fc0fee3520967a79ade32762be2e97`); the member's SHA-256 is `f87d8359035cb7557b16a319e6431ea09b183e62268aef436bf8dc6530d786b6`. The archive was downloaded on the node and the member compared with the installed file: the same |
| The VM image | Built on the node by `runner/images/vm/local-disk.sh` with `IMAGE_RUNTIMES=pi` and the branch's `tinfirm-runner-host` (musl): 311 s, 2944 MiB used of a sparse 32 GiB ext4 file, Docker 29.10.0 in it, runner images `tinfirm-runner-base:local` and `tinfirm-runner-pi:local`. Docker was stopped on the node afterwards and its firewall tables removed |
| The VMs | 2 vCPUs, 2048 MiB, a snapshot of 4096 MiB (tests) or 8192 MiB (the agent) |
| The user data | The runner host's format, with a ticket and a pin that are no one's: the bridge in the guest listens on 7443 and refuses every host. No server, no login and no credential was involved anywhere |

## The results in short

| Claim | Result | Output |
|---|---|---|
| `cargo test -p tinfirm-node-agent`, without root and without KVM | 65 + 15 passed; the 6 real tests skip and pass | below |
| The real tests, as root on the node | 6 passed | `linux-kvm.out` |
| The whole agent on two machines, under its units, driven by the runner host's own `node` driver over TLS | 6 cycles of create, describe, list, delete; the guest answers each time | `two-machines.out` |
| A third machine gets nothing | Every port refused or silent, the agent's port included, even with the control host's key | `third-address.out` |
| A shell as a VM's VMM user (decision 8, path A) | Every attempt on the other VM, the agent and the node refused; three things to know, below | `attack-vmm-user.out` |
| A shell as the listener's user (decision 8) | Every one of the helper's things refused directly; the helper answered misuse with its fixed refusals | `attack-listener-user.out` |
| A root shell on the node with a canary ticket (path B, the part without a server) | The ticket is where it must be and, on the helper's side, nowhere else; the listener's memory still holds it | `attack-node-root.out` |
| The units' lines, each by a real cycle | The helper survives a restart with a VM running; all eight capabilities are needed; `ProtectKernelTunables` is refused loudly | `units.out` |

## The tests that run anywhere

```
$ cargo test -p tinfirm-node-agent            (as an ordinary user, umask 022)
test result: ok. 65 passed; 0 failed   (the library)
test result: ok. 15 passed; 0 failed   (tests/agent.rs)
test result: ok. 6 passed; 0 failed    (tests/linux_kvm.rs: each prints "skipped: TINFIRM_NODE_KVM_TEST is not 1")
$ cargo clippy -p tinfirm-node-agent --all-targets -- -D warnings      clean
$ cargo fmt --check -p tinfirm-node-agent                              clean
```

One thing found on the way, in tests this change does not touch: under a umask of 002 (Ubuntu's for a login user) seven tests of `src/helper.rs` fail at the base commit too, with "the spool's parent has mode 0775". They make their directories with the process's umask. With `umask 022` they pass.

## The real tests (`tests/linux_kvm.rs`)

Run as root on the node with `TINFIRM_NODE_KVM_TEST=1` and the five paths (the file's head). The control host and "another machine" are two network namespaces of the node, each behind a veth (10.250.0.2 and 10.250.0.6), so that the published port is reached through the node's prerouting hook as from another machine.

| Test | What it shows |
|---|---|
| `a_made_vm_runs_jailed_answers_only_its_control_host_and_a_delete_leaves_nothing` | The VMM's status, namespaces, root, cgroup and limits; the jail's files and owners; who gets an answer; the console; nothing left after the delete |
| `a_make_that_fails_at_any_step_leaves_nothing` | A make made to fail after each of its six steps |
| `a_killed_vmm_is_listed_as_not_alive_and_its_delete_cleans_up` | `SIGKILL` to the VMM |
| `a_helper_started_over_what_a_crash_left_removes_it_or_keeps_its_slot` | A jail without a record in each of the six states; one that cannot be removed |
| `two_vms_at_once_share_nothing_and_the_node_holds_no_more_than_max_vms` | Sixteen things that two VMs must not share; `full` and `exists` |
| `a_guest_without_the_console_setting_writes_nothing_to_the_node` | The default: no guest output on the node |

From `linux-kvm.out`:

```
the VMM, pid 65651: Name firecracker; Uid 1300001 1300001 1300001 1300001; Gid 1300001 1300001 1300001 1300001;
  CapEff 0000000000000000; CapPrm 0000000000000000; CapBnd 00000000082000c3; NoNewPrivs 1; Seccomp 2
the control host, the published port: tls alert
the control host, the next port: refused
the control host, the guest's own address: nothing
the control host, the namespace's address: nothing
another machine, the published port: refused
another machine, the guest's own address: nothing
another machine, the namespace's address: nothing
the node itself, the published port: refused
the node itself, the namespace's address: nothing
console: [    1.938756] tinfirm-runner-host[459]: vm-boot: starting tinfirm-sandbox from tinfirm-runner-pi:local for run run-of-the-kvm-test
timings: make 109 ms, guest ready 2455 ms after the make, delete 55 ms
test result: ok. 6 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 67.10s
```

`tls alert` is the bridge's answer to a TLS client without the host's certificate: a TLS server answered. `nothing` is no answer within 3 s.

`CapBnd 00000000082000c3` is the six capabilities (`chown`, `dac_override`, `setgid`, `setuid`, `sys_admin`, `mknod`). Without the cut the jailer leaves `000001ffffffffff`.

The console's lines that show where the user data came from (the same test; the run's name is in the user data and nowhere else):

```
Kernel command line: console=ttyS0 systemd.show_status=false systemd.journald.forward_to_console=1 reboot=k panic=1 pci=off
  ip=10.231.0.6::10.231.0.5:255.255.255.252::eth0:off tinfirm.user-data=disk:/dev/vdb pci=off root=/dev/vda rw ...
virtio_blk virtio1: [vdb] 8 512-byte logical blocks (4.10 kB/4.00 KiB)
IP-Config: Complete:
     device=eth0, hwaddr=06:00:0a:e7:00:06, ipaddr=10.231.0.6, mask=255.255.255.252, gw=10.231.0.5
tinfirm-runner-host[460]: vm-boot: starting tinfirm-sandbox from tinfirm-runner-pi:local for run attack-a
```

## Two machines (`two-machines.out`, `third-address.out`)

On the node: the helper and the listener under the units of `runner/crates/tinfirm-node-agent/units/`, with the configuration of the crate's README (`maxVms` 2, the control host 10.0.0.4, the first port 20000, `guestConsole` on) and a drop-in `IPAddressAllow=10.0.0.4` for the listener. Each side's key and certificate came from `tinfirm-node-agent identity`.

On the control host: `control-host.py` starts `tinfirm-runner-host vm-driver node` with its configuration (the node's address, its pinned certificate, the client certificate and key) and speaks the driver protocol with it, as the runner host does.

```
hello: 2 ms, {'protocol': 1, 'driver': 'node', 'version': '1.0.0-rc.2-SNAPSHOT+images.0872e5e5c5b0'}
verify: 7 ms, problems [], maxVms 2
create: 128 ms, id node-1:vm-a51496f9a9c29a5436c6ae09dcd52c28; describe: 1 ms, running at 10.0.0.6:20000
guest ready: 2484 ms after create (10.0.0.6:20000 answers `tls alert`); next port: `refused`; list: 1 ms, 1 VM
delete: 83 ms; then describe says gone; the port answers `refused`
```

From the third machine, with a VM running (`third-address.out`):

| From | Published port | Next port | Agent's port 7700 | Guest's address | Namespace's address |
|---|---|---|---|---|---|
| The control host | tls alert | refused | tls TLSv1.3 | nothing | nothing |
| The third machine | refused | refused | nothing | nothing | nothing |
| The third machine, with a route to the VMs' range through the node | | | | nothing | nothing |
| The node itself | refused | refused | nothing | nothing | nothing |

With the control host's own certificate and key copied to the third machine for one try, the `node` driver there got no answer from the agent: the listener's unit lets in one address. The one line that `third-address.out` shows for this try is the driver's own answer to `hello` (`"driver":"node"`, the runner host's version), which the driver gives by itself before it dials a node. It is not an answer of the agent's. The call that needs the node got none, and the output does not show that call's error, so this file alone does not prove the refusal: the try is to be run again with the failed call recorded, and with the listener's own check of the source address (`tls.controlHost`, which came after these runs). The key was removed again and the machine deleted.

## Timings

| What | Value | How it was measured |
|---|---|---|
| `create` through the driver over TLS | 121 to 128 ms (five cycles); 2269 ms the first after the agent's services were installed anew, not looked into | `control-host.py`: a monotonic clock around the driver's answer |
| The backend's `make` alone | 109 to 130 ms, debug build | `tests/linux_kvm.rs`, `Instant` around the call |
| Guest ready | 2.4 to 2.8 s after `create` (6.5 s after the slow first one) | From the driver's answer until the published port answers a TLS client hello, tried every 100 ms |
| `describe`, `list` | 1 to 2 ms | `control-host.py` |
| `delete` through the driver | 58 to 89 ms | `control-host.py` |
| The backend's `delete` alone | 55 ms | `tests/linux_kvm.rs` |
| A make that fails because the VMM cannot start | 0.16 s | `units.out`, without `CAP_SETUID` |
| The image's build | 311 s | `tinfirm-vm.json` |

Two of these came from measuring. `dmsetup remove --retry` sleeps 200 ms after a first try that finds the device busy, which is every time right after the VMM is killed: a delete took 245 ms. The helper now tries again itself every 10 ms. And a jailer whose second process cannot exec left the helper waiting 10 s for a VMM that had already ended: it now fails as soon as the VM's cgroup is empty.

## What a real run changed in the planning functions

| Function | Change | What showed it |
|---|---|---|
| `jailer_argv` | No `--daemonize`; `--resource-limit fsize=67108864` | With `--daemonize` the VMM's output goes to `/dev/null`: no word on why a VMM did not start, and no console. The helper keeps the output in a file, and a file needs a bound |
| `node_rules` | The forward rule accepts only `ct status dnat` | `linux-kvm.out`, first run: "the control host, the namespace's address: tls alert". A control host with a route to the VMs' range reached the bridge beside the published port |
| `firecracker_config`, new `boot_args` | Two forms, by the new `firecracker.guestConsole` | The planned arguments had `console=ttyS0` and no journal on it: `vm-boot`'s line never reached the console, and a guest could fill the node's disk through it |
| `MODULES`, the modules' unit | `nft_nat` added | `lsmod` after a run: the DNAT statement loads it, and the confined helper may load nothing |
| `is_backing_file` (new) | A loop device is found by its backing file's name | `units.out`, an earlier run: after a restart of the helper the device showed as `/tfvm-00000000000f-0000.snapshot (deleted)`, the delete did not find it, and it leaked |

What the planning had right, against what was expected to break: the kernel's `ip=` argument works with Kata's kernel (`IP-Config: Complete` above), so the image needs no unit for its address; the snapshot's chunk of 8 sectors works; the jailer accepts a chroot that is made and filled beforehand, which `--config-file` needs.

## The units, line by line (`units.out`, `units.sh`)

`systemd-analyze security`: the listener 1.2, the helper 3.4. In the processes (`attack-listener-user.out`):

```
listener: Uid: 995 ...; CapEff: 0000000000000000; CapBnd: 0000000000000000; NoNewPrivs: 1; Seccomp: 2; Seccomp_filters: 31
helper:   Uid: 0 ...;   CapEff: 00000000082011c3; CapBnd: 00000000082011c3; NoNewPrivs: 1; Seccomp: 2; Seccomp_filters: 26
a VMM:    Uid: 1100000 ...; CapEff: 0000000000000000; CapBnd: 00000000082000c3; NoNewPrivs: 1; Seccomp: 2; Seccomp_filters: 27
```

The helper's unit as written ran a VM at the first try. Each capability was then removed in turn, with a cycle from the control host each time:

| Without | The create's error | Left |
|---|---|---|
| `CAP_CHOWN` | cannot give `.../root/vmlinux` to the VM's user: permission denied | nothing |
| `CAP_DAC_OVERRIDE` | cannot read the user data file: permission denied | nothing |
| `CAP_SETGID`, `CAP_SETUID` | the VMM ended before its guest ran | nothing |
| `CAP_SETPCAP` | cannot start the jailer: permission denied (the cut of the bounding set is refused, so no jailer starts) | nothing |
| `CAP_NET_ADMIN` | `sysctl -w net.ipv4.ip_forward=1` in the namespace: Operation not permitted | nothing |
| `CAP_SYS_ADMIN` | `ip netns add`: mount --make-shared /run/netns failed | nothing |
| `CAP_MKNOD` | `mknod ... root.disk b 252 0`: Operation not permitted | nothing |

The prototype's helper needed ten. This one needs neither `CAP_FOWNER` (it changes no mode after it gave a file away) nor `CAP_KILL` (it ends a VM through `cgroup.kill`).

Which of the surprises of the sandbox rounds still apply:

| Surprise | Now |
|---|---|
| `ProtectKernelTunables` on the helper broke forwarding without an error | Still breaks, and now says so: `create: ERROR after 67 ms: ... sysctl -n net.ipv6.conf.all.disable_ipv6 says 0, not 1: the setting did not take`, nothing left. Left out of the unit |
| The helper's system call filter reaches Firecracker through the jailer | Still true: `@sandbox` and the timers stay allowed. The VMM shows 27 filters |
| `ProtectControlGroups` stops the jailer making the cgroup | Not tried again; left out |
| `PrivateTmp` broke because the work directory was under `/var/tmp` | Holds now: the work directory is under `/var/lib` |
| `PrivateIPC`, `PrivateNetwork` on the helper | Not tried again; left out for the reasons in the unit |
| `ProtectSystem=strict` needs `/run/netns` and `/run/lock` | Still true. `/run/netns` must exist before the helper starts (the tmpfiles file makes it) |
| `ProtectKernelModules` needs the modules loaded before | Still true; `check` names a module that is missing. `nft_nat` was missing from the list |
| The jailer leaves the bounding set full | Cut in the helper's child (`src/bounding.rs`); needs `CAP_SETPCAP` |
| An id with a trailing newline | Does not apply: the id's form is checked on the whole byte string (`helper-misuse.py` tries it) |
| The helper served one connection at a time | Does not apply: 32 at once, 5 s for a request |
| Docker's forward policy drops the bridge's path | Still true: Docker was stopped and its tables removed after the image's build. `check` does not look for it |
| A full flush of the firewall removes the agent's tables | Still true for a VM's two tables. Not tried |

The helper started again while a VM runs: the VMM keeps its pid, the VM is listed `running` again, its port answers, and its delete leaves nothing (`units.out`, part 1).

## Decision 8, path A: a shell as a VM's VMM user (`attack-vmm-user.out`)

Two VMs made by the agent from the control host. `tests/attack/vmm-user.sh` gives itself a shell as the first VM's user, first inside its jail (its chroot and its mount, PID and network namespaces, no capability, `NoNewPrivs`), then outside it in the node's own namespaces.

The shell is a new process, so Firecracker's seccomp filter is not on it: this is decision 8's run "with the seccomp filter off", and every refusal comes from the user, the chroot, the namespaces, the file modes and the firewall alone. A run with the filter on would need Firecracker's own filter loaded into a shell, which was not built.

Refused, inside the jail: the other VM's jail, snapshot file, snapshot device and API socket (no such path); a device node of its disk (`mknod`: Operation not permitted); a signal to its VMM (no such process); the helper's socket, the node's key, the spool, `/etc`, `/home` (no such path); `chroot`, `mount`, a new network or mount namespace; a connection to its own guest's bridge, the other guest, the other VM's namespace, both published ports, the agent's port, the node's SSH port on both of its addresses, the control host, the metadata service, Azure's host agent and the internet (all time out: the namespace's output chain drops).

Refused, outside the jail: the other VM's jail, chroot, disk node, user-data disk, API socket, snapshot file, console file, snapshot device (read and write); its VMM's memory, environment, root and open files; tracing it, signal 0 and `SIGKILL`; the helper's socket and its directory, its record, the spool, the listener's records, the node's key, the listener's configuration; the helper's memory and signals to both processes; a network namespace, a firewall table, reading the firewall, the device-mapper, a loop device, a cgroup, `cgroup.kill` of the other VM, a tap device; the other VM's published port, namespace and guest; the agent's port.

Allowed, by design: its own disk, its own user data and `/dev/kvm` inside its jail; the shared image (0444) and the helper's configuration (0644, no secret); the list of the jails' names, which name no run.

Three things to know:

1. **A new user namespace is allowed** inside the jail (`unshare -U`). The real VMM is refused it twice, by Firecracker's filter and by the helper's unit (`RestrictNamespaces`, inherited); a shell without filters is not. The node's hardening closes it for every user (`user.max_user_namespaces=0`), which is the role's to set and was not set here.
2. **The node's own egress is not the agent's.** As the VMM's user outside the jail, a connection to the control host's SSH port was allowed. The agent filters a VM's namespace, not the node: ADR-0112's "open a connection to the control host, on any port" is the node's and the control host's firewall, which the role is to set (planned).
3. **Even its own chroot has no path from outside**: the jail's directory is root's, 0700.

## Decision 8, the agent's two processes (`attack-listener-user.out`)

`tests/attack/listener-user.sh`, with one VM running, as the listener's user and group with no capability.

Refused directly: a tap device, a firewall table, reading the firewall, the device-mapper, a loop device, a cgroup, `cgroup.kill` of a VM, a network namespace; a VM's jail, disk node, user-data disk, snapshot file, snapshot device, console and API socket; the helper's record and work directory; writing the agent's program, the helper's configuration, its own configuration, the jailer and the shared image; signals to a VMM and to the helper; the helper's and a VMM's memory. Another user gets no connection to the helper's socket; a user of the listener's group that is not the listener gets a connection that the helper closes before it reads a byte.

Inside the listener service's own namespaces it also sees no `/home`, a read-only `/etc` and `/usr`, no `/dev/kvm`, no `/dev/mapper/control` and no block device.

Misuse of the helper's socket (`helper-misuse.py`), each answered with one of the helper's fixed messages:

| Request | Answer |
|---|---|
| Not JSON; a list; a key twice | `bad-request`: not one JSON object with each key once |
| No `v`; another `v` | `bad-request` |
| An unknown operation; an extra field (`path`, `memoryMib`) | `bad-request`: not one of check, make, delete and list with exactly its fields |
| 100,000 bytes; 5,000 bytes without a newline | `bad-request`: the request is over 4096 bytes |
| A connection that sends nothing | `bad-request`: no request in time, after 5.0 s |
| Twelve malformed ids (a number, `../`, `/`, upper case, a non-hex digit, a trailing newline, `; id`, one digit too many or too few, a byte that is not ASCII, empty) | `bad-request`, each |
| A path or an unknown name as template | `bad-request`; `unknown-template` |
| User data: missing, a link to `/etc/shadow`, a link to a readable file, a FIFO, a directory, empty, 65,537 bytes, a sparse 1 TiB, a second name | `user-data`, each with its reason |
| The file swapped with a link to `/etc/shadow` during 30 makes | VMs made from the regular file, the rest refused (missing, empty, a link); never the link's target |
| The id of a VM that runs | `exists` |
| A delete with a path or a jail's name as id | `bad-request` |
| 2000 makes without a file | 2000 refusals in 0.3 s |
| 5 makes with a valid file beside the VM that runs | 1 made, 4 `full` |
| A request beside 40 idle connections | Closed at once (the helper serves 32); answered again once they timed out |

After it: both services active, the VM still running, nothing stray on the node. What the listener keeps by the rules is ADR-0112's list: it can delete the VM that runs (not done), fill the node's slots, and hold the helper's connections for 5 s at a time.

## Decision 8, path B: what runs without a server (`attack-node-root.out`)

Path B proper needs runs with canary credentials and the control host's record of what the bridge carried. That needs a server and was not run. `tests/attack/node-root.sh` shows the part that concerns the agent, with a canary in the ticket's place:

| Where | Canary |
|---|---|
| The VM's user-data disk; the guest's memory; the guest's own disk (its Docker keeps the container's environment) | Found, as it must be: root on a node reads its runs' tickets (ADR-0112, Consequences) |
| The spool, the listener's records, the helper's record, the console file, the rest of the jail, the agent's configuration, the machine's journal, `/run`, `/tmp`, `/var/tmp`, `/dev/shm` | Not found |
| The helper's memory | Not found. It was found in the first run (2 places): the helper now overwrites the bytes before it frees them (`spool::wipe`) |
| **The listener's memory** | **Found (2 places).** The listener frees the user data without overwriting it. Open: `src/listener.rs` is not part of this change |

The agent's port, from the node itself as root without the control host's key: nothing (the listener's unit admits the control host's address alone).

Connections the node itself can open as root: the control host's SSH port, the metadata service and the internet were all reached. Nothing here stops them: that is the node's firewall and the provider's network, which the role is to set (planned). So path B's "must fail to open a connection" is **not met by a node as it stood in this test**.

## Not tested

- **A conversation.** No runner host ran against the agent: that needs the owner's login, which this round was not to use. It would take the runner host on the control host with `--executor vm --vm-driver node`, its login, and an image that holds the runtime's runner image. The VMM's memory under a real run (the limit is the guest's memory and 128 MiB) was therefore not seen either.
- **Path B with canary credentials, and path C.**
- **The node's hardening:** `user.max_user_namespaces=0`, `kernel.modules_disabled=1`, lockdown, the AppArmor profile of the third sandbox round (whose paths would be `/root.disk`, `/userdata.disk`, `/vm.json` now), and a node firewall of the role's.
- **A guest that floods its serial console** up to the 64 MiB bound, and what the listener reports then.
- **A full flush of the node's firewall** while VMs run.
- **More than two VMs at once under the units**, a node at its memory's end, a snapshot that fills up.
- **Another kernel, another distribution, ARM, AMD.** One node, Intel, Ubuntu 24.04.
- **The module check on a kernel with one of the eight modules built in**: it would name the module as missing.
- **`controlHost` in the listener's configuration** (41567f5): this change is on `7c3cf12`, before it.
- **The single machine**, which the helper refuses on purpose.

## Second round, 2026-11-19 (22:35 to 23:50 UTC)

What the first round left open, on the same node and control host, from the branch at `eb6ea9c` with this round's change. The sections above are the first round's record and are not changed; where this round's result differs, it says so here. New outputs are the `r9-*` files.

**The node in this round**: the agent under its units as before, and now also the node's own firewall table (`units/tinfirm-node-agent.nft`, for the control host and the management address 10.0.0.4, the node 10.0.0.6, the ports 20000 to 20001), `user.max_user_namespaces = 0`, and `tls.controlHost` 10.0.0.4 in the listener's configuration.

| Claim | Result | Output |
|---|---|---|
| The crate's tests under umask 022 and under 002 | 70 + 16 + 2 passed, the 6 real tests skip; the same under both | below |
| The real tests, as root on the node | 6 passed in 76.9 s; make 120 ms, guest ready 2766 ms, delete 83 ms | `r9-linux-kvm.out` |
| No unsafe code; the VMM's bounding set is still the six | `CapBnd 00000000082000c3`, `CapEff 0`, `Groups` empty, under the helper's unit | `r9-bounding-set.out` |
| Six cycles through the driver with the firewall, the sysctl and `tls.controlHost` | create 133 to 141 ms, ready 2.7 to 2.9 s, delete 64 to 86 ms; nothing left | `r9-two-machines.out` |
| The ticket in the listener's memory | Not found any more | `r9-attack-node-root.out` |
| The node's own egress, as root | Every attempt fails | `r9-attack-node-root.out` |
| A new user namespace as a VM's user | Refused | `r9-attack-vmm-user.out` |
| Who is served at the agent's port, and which guard refuses the others | The control host alone; each of three guards refuses the third machine on its own | `r9-control-host.out` |
| A delete that cannot ask the firewall | Fails, names the table, the VM stays listed; succeeds once it can ask | `r9-delete-cannot-ask.out` |
| The release's build of the two binaries at `ae1f509` | Built in 330 s; the version and the archive are as expected | `r9-release-build.out` |

```
$ cargo test -p tinfirm-node-agent     (umask 022, and again under umask 002: the same)
test result: ok. 70 passed; 0 failed   (the library)
test result: ok. 16 passed; 0 failed   (tests/agent.rs)
test result: ok. 6 passed; 0 failed    (tests/linux_kvm.rs, skipping)
test result: ok. 2 passed; 0 failed    (tests/start_jailer.rs)
$ cargo clippy -p tinfirm-node-agent --all-targets -- -D warnings      clean
$ cargo fmt --check -p tinfirm-node-agent                              clean
$ grep -rn unsafe runner/crates/tinfirm-node-agent/src                 only the two sentences that say there is none
```

Under umask 002 the first round counted seven failing tests of `src/helper.rs`. That count was short: cargo stops at the first test binary that fails, and thirteen tests of `tests/agent.rs` failed for the same reason behind it. Both fixtures now set the mode of their own directory.

### The bounding set without unsafe code (`r9-bounding-set.out`)

The helper starts its own binary as `start-jailer`, which cuts its bounding set through rustix and becomes the jailer (the crate's README, "The bounding set"). Under the helper's unit, with a VM made from the control host:

```
the helper (pid 20868): CapEff: 00000000082011c3; CapBnd: 00000000082011c3; NoNewPrivs: 1; Seccomp_filters: 26
the VMM (pid 23550): Name: firecracker; Uid: 1100000 (x4); Gid: 1100000 (x4); Groups: (none);
  CapInh, CapPrm, CapEff, CapAmb: 0000000000000000; CapBnd: 00000000082000c3; NoNewPrivs: 1; Seccomp: 2; Seccomp_filters: 27
processes in the helper's own cgroup: 1 (the helper)
a process still named start-jailer: 0
```

Run by hand on the node:

| Who, and what | Answer (status 1 each) |
|---|---|
| An ordinary user, root's jailer, a jail's name | cannot cut the capability bounding set (permission denied): no jailer was started |
| An ordinary user or root, `/bin/sh` as the program | the program is not an absolute path to a file named `jailer` |
| An ordinary user or root, arguments that do not start with `--id` and a jail's name | the arguments do not start with `--id` and a jail's name of the helper's |
| Root, a copy of the jailer that an ordinary user owns | the jailer is not a regular file of root's that only root can write |

### The ticket in the listener's memory (`r9-attack-node-root.out`)

The first round found the canary in the listener's memory in two places. The listener now overwrites what it holds of a `create` (the crate's README, "A make", last paragraph). With a VM made from a canary ticket, and one `list` from the control host after the `create`, as a runner host asks about its VM next:

```
where it must not be:
  the listener's spool, its records, the helper's record, the console file, the rest of the jail,
  the agent's configuration, the journal of the whole machine, /run, /tmp, /var/tmp, /dev/shm: not found
  the helper's memory: not found
  the listener's memory: not found
```

What the agent's own code cannot overwrite is the TLS library's read buffer and the JSON parser's scratch space. Neither held the canary in this run. That is one run: a copy there lasts until the library uses the memory again, which the next request on the connection did here.

### The node's firewall and its egress (`r9-attack-node-root.out`, `r9-attack-vmm-user.out`)

The first round's root shell reached the control host's SSH port, the metadata service and the internet. With the node's table loaded:

```
== 3. connections the node itself can open, as root (the node's firewall table: loaded)
  the control host's SSH (10.0.0.4:22), port 443, and the agent's own port number (7700): nothing
  the metadata service (169.254.169.254:80): nothing
  Azure's host agent (168.63.129.16:80): nothing
  the internet by address (1.1.1.1:443), a resolver (8.8.8.8:53): nothing
```

The six cycles ran with it loaded. A port beside a published one is now silent from the control host, where the first round saw `refused`: the table drops what it does not admit. As a VM's user outside its jail, the control host's SSH port is refused too, and inside the jail a new user namespace is refused (`unshare: No space left on device`, the kernel's answer when `user.max_user_namespaces` is 0).

Root on the node can delete the table (`nft delete table inet tinfirm_node`); nothing on the node stops that. Path B's "must fail to open a connection" holds on this node against everything but root that removes the node's own guard, where the operator's network outside the node is what the ADR relies on. No such network guard was in this test.

Not tried: the node across a renewal of its DHCP lease with the table loaded (the table admits it; the lease did not come up for renewal), and a reboot with the firewall's unit enabled.

### The agent's port: who is served, and by which guard (`r9-control-host.out`, `other-machine.py`)

The first round's `third-address.out` shows one line for the third machine, a `hello` answer. That line is the `node` driver's own answer, made without the node; the call that needed the node was not recorded. This round records it. `other-machine.py` makes `hello`, `verify` and `list` through the driver, with the control host's certificate and key copied to the machine that runs it.

A node has three guards in front of the listener's handshake: the node's firewall table, the listener unit's `IPAddressAllow`, and the listener's own `tls.controlHost` check. Each was left alone in turn, the other two set to admit the third machine:

| Guards | The control host | The third machine (10.0.0.5), with the control host's key | The node itself, with the key |
|---|---|---|---|
| All three, as a node has them | `verify` in 5 ms, no problem | `verify` after 20.0 s: "cannot reach the node: no connection in time" | the same, after 20.0 s |
| The listener's `tls.controlHost` alone | served | `verify` after 21 ms: "cannot reach the node: TLS: tls handshake eof" | the same, after 1 ms |
| The unit's `IPAddressAllow` alone | | after 20.0 s: "no connection in time"; no line in the listener's journal | |
| The firewall table alone | | after 20.0 s: "no connection in time"; the table's input counter went up | |

With the listener's check alone, its journal says: `refused: 1 × a connection from another address than the control host's` (the summary is written at most once a minute, so the node's own try is counted in the next one). With all three, the first to refuse is the firewall table; the packets never reach the listener, and its journal has no line.

### A delete that cannot ask (`r9-delete-cannot-ask.out`)

The review bot's finding: any failure of `nft list table` was read as "the table is gone". nft ends with status 1 both for a table that does not exist and when it may not ask the kernel; only its text differs. The delete now reads the text, and every other "is it gone" check has a third answer beside there and gone (the crate's README, "A delete"). With the helper's unit set to lack `CAP_NET_ADMIN` after a VM was made:

```
delete: ERROR after 48 ms: of the VM tfvm-000000000054-0000 this is left: the firewall table tinfirm_000000000054,
  perhaps (`nft list table inet tinfirm_000000000054` failed: Operation not permitted (you must be root) netlink: Error: ...)
then list: the VM, state failed
on the node: the jail and the table inet tinfirm_000000000054 are there
== with CAP_NET_ADMIN again
delete: ({}, 23); then list: no VM; on the node: nothing left
```

### The release's build (`r9-release-build.out`)

At `ae1f509`, in a fresh clone on the node: `deploy/release/assets.sh runner-linux x86_64-unknown-linux-musl rolling-test1234 /tmp/assets` ended with status 0 after 330 s (4 vCPUs, nothing cached).

| | |
|---|---|
| `file tinfirm-node-agent` | ELF 64-bit LSB executable, x86-64, statically linked, stripped |
| `tinfirm-node-agent --version` | `tinfirm-node-agent 1.0.0-rc.2-SNAPSHOT+rolling-test1234`, as `assets.sh build-id rolling-test1234` prints |
| The node agent's archive | `LICENSE`, `THIRD-PARTY-LICENSES.txt`, `tinfirm-node-agent`; no `ee/` |
| The runner host's archive | The same three, and `ee/LICENSE` |
| `tinfirm-runner-host --version` | `tinfirm-runner-host 1.0.0-rc.2-SNAPSHOT (rolling-test1234)` |

One thing about the machine, not the script: the first try ended at once with `unknown flag: --progress`. Ubuntu's `docker.io` package has no BuildKit front end; with `docker-buildx` installed the same command ran. The script needs `docker buildx`, and does not say so.

### Still not tested after this round

A conversation; path B with canary credentials, and path C; `kernel.modules_disabled`, lockdown and the AppArmor profile; a guest that floods its serial port; a flush of the node's whole ruleset under running VMs; a node at its disk's or memory's end; another kernel, distribution or architecture; a kernel that really has one of the eight modules built in (the check is tested on files standing in for one); the single machine.

## The independent review's findings, 2026-11-19 to 20 (23:15 to 00:25 UTC)

A review of the first round's commits (it read the code and ran nothing) found no way to root, to another VM or to a credential, and thirteen points about availability, cleanup and claims. Each was tried on the node first, with the build of this round's first part, and then again with the fix. `r9-review-before.out` and `r9-review-after.out` hold both runs; the other outputs are named below.

| # | Finding | On the node, before | Now | After |
|---|---|---|---|---|
| 1 | A tool that hangs stops the whole helper | Confirmed. With `systemd-udevd` stopped (SIGSTOP), `dmsetup create` waited for udev and never ended: a `list` 4 s later got no answer in 30 s | Every tool has 10 s, then it is killed with its process group. `dmsetup` runs with `--noudevsync`: the helper makes the device's node itself | The same create took 128 ms, the `list` 53 ms |
| 2 | A VM that cannot be removed holds the helper 10 to 20 s at every retry | Confirmed by the code: three waits of 5 s each, at every delete and every sweep | A VM whose last delete failed is not waited for again; the loop device is not waited for while the snapshot's device is there | The real tests' leftover case: the whole file runs in 38 s where it took 77 s |
| 3 | `KillMode=process` keeps a make's tools alive beside the next helper | The reading holds: with a VM running, the helper is the only process of its unit's cgroup (`r9-bounding-set.out`), so the VMMs never needed it | The unit's kill mode is the default again | Four kills of the unit with two VMs running: both VMs run on and are listed. See below for what was not hit |
| 4 | Disk and memory are overcommitted | Confirmed by the code: nothing checked either | `check` asks for room for `maxVms` snapshots and memory for `maxVms` VMMs; `make` asks for room for one more snapshot beside what the others may still grow by | `verify` lists both as checked (`r9-two-machines-final.out`). The measurement, and what is not done, below |
| 5 | `nft flush ruleset` removes a running VM's node table, and `list` still says `running` | Confirmed: the port answers `refused`, the state stays `running` | A VM whose node table is gone is listed as not alive | The state is `failed`; the delete cleans up |
| 6 | A tree that the VM's user builds in its chroot keeps the jail from being removed | Confirmed, sooner than expected: 2046 levels (as deep as a shell's `cd` gets) and the delete ends with "too many open files", every time | A removal that never goes deeper than one level (`src/tree.rs`) | The jail that the old build could not remove went in 68 ms; a new one in 141 ms. A unit test builds 5000 levels (3000 since: a container under AppArmor, as on a CI runner, refuses a path of more than 8192 bytes, so the test could not build its own tree there) |
| 7 | The `kill` fallback can never work without `CAP_KILL` | True by the unit | The fallback and the tool are gone; `check` asks for `cgroup.kill` | `verify` lists it as checked |
| 8 | A failure of `nft` read as "the table is gone" | Above, "A delete that cannot ask" | | |
| 9 | With `guestConsole` off a guest may still write to the emulated COM1 | Not from a process: see below | The README says what holds | |
| 10 | `vmm_runs` connects as root to a path in the VM user's directory | True | The connect has a deadline (a thread of its own, 3 s); a comment says why the path is still the VMM's at that moment | The real tests pass |
| 11 | The VMM's `Groups:` line is not read | True; the line was empty under the unit and through sudo (`r9-bounding-set.out`, `linux-kvm.out`) | A VMM with a supplementary group fails the make | A unit test |
| 12 | Copies of the user data freed without being overwritten on three refusals | True | The bytes are a type that overwrites itself when dropped (`spool::Wiped`), in a buffer that never moves | The crate's tests |
| 13 | Small ones | | A lock on the helper's state directory (a second helper does not start); the README's sentence about `check` and `make`; a note in the listener's unit about its runtime directory; a line in the README that the published ports must avoid the listener's port, which the helper cannot check; the node's firewall forwards nothing but the published ports | A unit test for the lock |

After all of it: the crate's tests under umask 022 and 002 (76 + 16 + 2, the 6 real ones skipping), clippy and fmt clean, the 6 real tests in 38.3 s (`r9-linux-kvm-final.out`: make 127 ms, guest ready 2784 ms, delete 70 ms), and six cycles through the driver (`r9-two-machines-final.out`): create 141 to 149 ms, delete 70 to 110 ms. `describe` and `list` take 5 to 6 ms where they took 1 to 2: a list with a running VM now asks `nft` for the node's tables once.

### Finding 3: what was not hit

The reviewer's worry is a helper stopped in the middle of a make, whose tools then run on beside the next helper. A make takes about 130 ms. The unit was killed four times while VMs were being made, twice over (`r9-review-after.out`); none of the eight kills fell inside a make. The node was clean after each run, and the VMs that ran were listed again. So the change rests on the reading of the cgroups, which holds, and not on a caught race.

### Finding 4: what was measured, and what is left (`r9-snapshot-memory.out`)

A snapshot as the agent makes it, written to in two ways, with the kernel's slab memory before and after (not the VMM's cgroup: the snapshot's bookkeeping is the kernel's own, and whether any of it is charged to a cgroup was not looked at):

| Chunk | Written in order, 3 GiB | Scattered, one 4 KiB write into every second chunk, 500,000 chunks |
|---|---|---|
| 4 KiB (today's) | +74 MiB | +70 MiB, 148 bytes a chunk; a 20 GiB snapshot written to its end this way: about 740 MiB |
| 8 KiB | +71 MiB | +108 MiB, 227 bytes a chunk; about 570 MiB |
| 32 KiB | +73 MiB | the 8 GiB snapshot file was full before the end |
| 64 KiB | +76 MiB | the same |

Written in order, the cost is about 25 MiB for each GiB whatever the chunk: that is the bookkeeping of the snapshot's file, not the table of chunks. Scattered writes add the table: with 4 KiB chunks a guest that touches its whole 20 GiB snapshot in the worst order holds about three quarters of a GiB of kernel memory outside its VMM's limit. A larger chunk lowers the count of chunks a snapshot can hold, and so the bound, and makes each scattered write cost a whole chunk of the snapshot. The chunk is left at 4 KiB in this change: choosing another needs a guest's own writes (a real run with Docker) measured for time and space, which this round has not. Proposed: measure 32 KiB under a real run; until then, a node's `check` could count about 40 MiB of kernel memory for each GiB of `snapshotMib` beside the VMMs' own. Not done either: `io.max` or `io.weight` for a VMM (the template has no field for it), and reserving the snapshot's space instead of checking it.

### Finding 6: what bounds a jail's size

Nothing yet. `fsize` bounds one file of the VMM, not the count of its files, so a process of the VM's user can fill the jails' file system with small files or directories, which stops the next VM's jail from being made (its kernel is copied there). The removal now copes with whatever it finds. Proposed, not built: each jail's root as a small file system of its own (a tmpfs with `size=` and `nr_inodes=`, charged to the VMM's cgroup), or a project quota on the jails' directory.

### Finding 9: the serial port with the console off (`r9-com1.out`)

A VM by hand in the agent's layout, with a program in its disk that writes a marker to port 0x3f8, run as the guest's init:

```
== control, with console=ttyS0
... ioperm: Function not implemented  SCRIPT-ENDED status 1
```

Kata's guest kernel is built without `ioperm` and `iopl`, has no `/dev/port` and loads no module, so a process in the guest, root included, has no way to the port. Code in the guest's kernel has: the VMM emulates the port whatever the kernel's command line says, and copies what is written to its standard output, the console file, up to the 64 MiB bound. The same file holds Firecracker's own log, which goes to standard output too (its standard error stayed empty), and a guest's kernel can make Firecracker write lines there (`vcpu: IO write @ 0x71 failed`). So "nothing of a guest's log is on the node" was too strong; the README now says what holds. Not changed: the VMM's standard output. Sending it to `/dev/null` would lose Firecracker's own messages, which is where a make's failure is explained; giving Firecracker a log file of its own (`--log-path`) and its standard output to `/dev/null` would keep both apart, and is proposed.

## Third round, 2026-11-20 (05:56 to 08:15 UTC)

On the branch at `54663cd`. The node as before (10.0.0.6); the control host was a machine of its own this time (10.0.0.5, a D2s_v6 VM), which drove the node through the runner host's node driver over TLS (`control-host.py`). The agent ran under its units with the node's firewall table, the sysctl file and `tls.controlHost` for 10.0.0.5. The VM image was built again with the branch's runner host (`local-disk.sh`, 313 s).

| Command | Result |
|---|---|
| `cargo test -p tinfirm-node-agent`, umask 022 and umask 002 | 82 + 16 + 2 passed under each, the 8 real tests skip |
| `cargo clippy -p tinfirm-node-agent --all-targets -- -D warnings`, `cargo fmt -p tinfirm-node-agent --check` | clean |
| `tests/linux_kvm.rs` as root on the node (`r10-linux-kvm.out`) | 8 passed in 47.25 s; make 141 ms, guest ready 3054 ms, delete 89 ms |
| `tree::tests` as root on the node | 4 passed, the sticky directory's among them (it skips when not root) |
| Six cycles from the control host, final tree (`r10-two-machines.out`) | create 165 to 173 ms (495 the first), ready 2.9 to 3.2 s, delete 71 to 116 ms; nothing left |
| `shellcheck -S warning` (0.9.0) on the three `r10-*.sh` and the three scripts of `tests/attack/` | clean |

### No conversation ran: the server refused the host's hello (`r10-server-refuses-hello.out`)

The round was to run real conversations from a server of an earlier release through the node agent. The runner host of the branch, started with `run --executor vm --vm-driver node` and a valid login (`doctor` reports that the server accepted the token), verified the node (`node node-1: 2 free` and every check) and was then refused before `welcome`:

```
WARN  Invalid hello message: isolationTiers[0].<list element> must match "container|gvisor|microvm"  code=BAD_MESSAGE
ERROR fatal connection error error=the server refused this runner host's hello (close 1008: Expected a valid hello)
```

The host reports `machine` with this driver, and the server is an earlier release that does not know the value, as `runner/README.md` says under "Compatibility with the server". Nothing was worked around and nothing was changed at the server. So still **no conversation has run through a node**, and everything below was driven by the script in the runner host's place.

### A jail is a file system of its own (`r10-node.out`, `r10-node.sh`)

Chosen over a project quota, which needs a file system set up for it on every node: the helper mounts a tmpfs on each chroot (`size=` from `firecracker.jailMib`, 64 MiB by default; 1024 inodes; `nosuid`) before it puts anything there. A VM's user can then fill its own jail and nothing else: not the jails' file system, and not the work directory, where the other VMs' snapshots are sparse files that a full disk would invalidate (the review's R3). The price is memory, at most `jailMib` for each VM, which `check` counts.

```
in the helper's mount namespace: .../tfvm-...-0000/root rw,nosuid,relatime shared:372 - tmpfs tinfirm-jail rw,size=65536k,nr_inodes=1024,mode=700,inode64
in the node's own namespace the chroot is an empty directory: 0 entries; the user data and the VMM's files are in memory only
-- the VM's user fills it: 22781952 bytes copied; names until none is left: 1007; chmod 1700 on a directory with a file, and on the chroot: done
... the node's work file system changed by 28 KiB; the VMM still runs: 1
-- the next VM beside it: create: 150 ms ... running at 10.0.0.6:20001
delete of the filled, sticky one: delete: ({}, 57)
left: jails 0, VMMs 0, device-mapper 0, ..., jail mounts in the helper's namespace 0, in the node's 0
```

Under the helper's unit the mount is in the helper's own mount namespace, so nothing of a jail, the user-data disk included, is on the node's disk. A helper that is started again does not see the earlier helper's jails as mounts (section 2 of the file): their file systems end with their VMMs, and the delete finds an empty directory and leaves nothing. The same is a real test (`a_vms_user_that_fills_its_jail_takes_nothing_from_the_node_or_the_next_vm`).

### The review's R1: a sticky bit made a jail undeletable (`r10-review-before.out`)

Confirmed on the node with the agent as of `bb0cb70`: after `chmod 1700` on the chroot by the VM's user, every delete failed with `the jail ... (permission denied)`, the VM stayed listed as `failed` with its slot, and only root with every capability could undo it by hand. Two things close it. The jail's own file system is unmounted, whatever is in it. And `remove_tree`, which still removes what a jail's directory holds otherwise, now makes itself the owner of each directory before it empties it, and no longer reads "permission denied" as "a directory". The unit test for it runs only as root, since it needs another user's files: a thread without `CAP_FOWNER` is refused the plain unlink first, and then removes the tree. What else a user can set on its own files was thought through, not tried: the crate's README lists it.

### The removal keeps no list (`src/tree.rs`)

The list of moved directories is gone: the top is read again, a round of 1024 names at a time, until a pass finds nothing, so a directory of millions of directories costs the helper one round of names. The loop has no early end (the review's R4): the test fills the top with more than two rounds of directories left by an earlier attempt, beside one directory that holds ten rounds of directories. Time is bounded by the number of entries, and that by the jail's 1024 inodes where the jail is its own file system.

### The room check fails closed, and what `check` now counts

`room_problem` no longer counts a snapshot it cannot read as absent: a work directory or an entry that cannot be read makes the answer "the room is not known", for `check` and for a make (unit test). `check` counts for each VM its guest's memory, 128 MiB for the VMM, the jail's `jailMib`, and the kernel's memory for a snapshot written full (the review's R2, below).

### Firecracker's log, and what a failed start now says (`r10-linux-kvm.out`)

Firecracker writes its own messages to `firecracker.log` in the jail (`--log-path`). Its standard output carries the guest's serial console alone: a file with `guestConsole`, `/dev/null` without, so code in a guest's kernel that writes to the serial port's address writes into nothing (the second round's finding 9). Its standard error is a file of its own. A make whose VMM ends before its guest runs now says why:

```
the VMM ended before its guest ran: Firecracker's log ends with: ... [tfvm-...:main:ERROR] RunWithApiError error: Failed to build MicroVM from Json: ... Cannot load kernel due to invalid memory configuration or invalid kernel image: Kernel Loader: failed to load ELF kernel image: Kernel Loader: Unable to read elf header | ...; its standard error: Error: RunWithApi(BuildFromJson(StartMicroVM(ConfigureSystem(KernelLoader(Elf(ReadElfHeader))))))
```

Two things were found on the way. Asking Firecracker's API while it gives up on its configuration ends its API thread first (`Firecracker panicked ... VMM disconnected`, then a seccomp kill), and the log then never names the cause: the helper now asks the API only once the log says that the VM is started. And a guest can make the log grow, since Firecracker warns of every access to a port it does not emulate: the log is bounded by the jail's size.

### `io.max` (`r10-io-max.out`, `r10-io-max.sh`)

A template can carry it (`ioMax`), but not through the jailer, which refuses a cgroup value with `=` in it (`Invalid format for cgroups: io.max=252:0 rbps=...`): the helper writes it into the VM's cgroup once the jailer has made it. The root cgroup of the node did not hand down the controller `io`; the helper's unit now asks the service manager for it (`IOAccounting=yes`), and `check` names it when a template has an `ioMax` and the controller is missing.

```
-- no ioMax in the template:  the guest's bridge answers 3614 ms after the create was sent
-- ioMax 4 MiB/s and 200 operations/s each way: io.max of the VM's cgroup: 252:0 rbps=4194304 wbps=4194304 riops=200 wiops=200
   the guest's bridge did not answer within 180 s; io.stat: 252:0 rbytes=180707328 ... rios=44079 ...
-- back to no limit: 3164 ms
```

The limit holds for a real VMM's reads (a guest's boot reads 180 MiB in 44 000 operations, which 200 a second stretch to minutes). Not measured: writes under a limit, and a limit that an ordinary run should have.

### The snapshot's chunk size (`r10-snapshot-chunk.out`, `r10-snapshot-chunk.sh`)

Not measured in a conversation, since none ran. A stand-in on the node: the node's own dockerd with its data on a mounted snapshot of the VM image, where the guest's dockerd has it, pulling `node:22` and running a container that writes 2000 small files and 200 MiB.

| Chunk | All | Snapshot on disk | Kernel's slab, grown by |
|---|---|---|---|
| 4 KiB | 36.5 s, 36.1 s | 1822 MiB, 1821 MiB | 13 MiB, 11 MiB |
| 32 KiB | 38.1 s, 40.3 s | 1827 MiB, 1827 MiB | 10 MiB, 10 MiB |

No clear gain either way, so the default stays 4 KiB. For such writes the kernel's memory is small (about 6 MiB for each GiB written). The second round's worst case stands for writes at random, about 150 bytes for each chunk: `check` counts 160 bytes for every chunk of `snapshotMib` in each VM's memory (R2), 320 MiB for the 8 GiB of the template that ran.

### The node's firewall at boot (`r10-reboot.out`), the review's R5

- The helper's unit now requires the firewall's unit: with a typing mistake in the file (`control_host = 10.0.0.555`) the firewall's unit fails and neither helper nor listener starts. `check` also says whether the table is there (`NOT THERE: the node's own firewall table ...` after a delete by hand), as a line and not as a problem, since the helper works without it.
- The unit's file replaces the table in one transaction, and the unit no longer deletes the table when it stops: a restart leaves no moment without it (`the table before 1, after 1`).
- The unit has no default dependencies and is wanted by `sysinit.target`. A reboot with the four units enabled: the firewall was active 3.6 s after the start, before `network-pre.target` (4.6 s) and the network (4.7 s); the helper at 4.4 s, the listener at 6.0 s; no ordering cycle; a cycle from the control host passed.
- **That reboot needed cloud-init switched off.** With cloud-init as the image has it, the node did not finish its start: the table was loaded at 3.3 s, cloud-init then asked the metadata service ten times in 310 s and failed, nothing more was logged, and the node refused SSH for 21 minutes until it was deleted. A node's image must not need a network service of its platform for its start. The crate's README says so; the role does not handle it yet.

### The review's other findings

- **R6**: a `firecracker.jailer` that is not named `jailer` is refused when the configuration is read (`Error: firecracker.jailer must be a file named \`jailer\``).
- **R7**: parsing a request's parameters by reference, so that no copy is dropped unwiped on a failure, was tried and dropped: it left the user data once more in the listener's memory on every create (`r10-listener-memory.out`: found in two of two runs after one further call). With the code as it was, the ticket was found in the listener's memory in one of three runs after one further call, and not after three calls with either: the second round's "not found" was one run. What stays is outside the agent's code. A `create` refused for its form still drops its copy unwiped. The spool is not on a tmpfs; the README says what that leaves.
- **R8**: the crate's README now says what the code does about the jail's size, the chunk's cost, the removal and the sticky bit.

### The role's author's list

Fixed in the README: one account, not two; the fixed paths; the modes of the image and the kernel; ufw and firewalld beside Docker; updates need their mirror in the firewall's file; how an image is replaced under running VMs; no kernel pinned for aarch64; what the helper checks of the control host's address; the owner of what `identity` writes; the role's state. The firewall's file has a `define ssh_port`. Not built: a `check` subcommand for an operator. It would need the helper's socket opened as the listener's user and the helper's wire spoken from a second place; the role's small client does that today.

### Still not tested after this round

- A conversation through a node: the server must be of the branch's release first.
- The snapshot's chunk size and `io.max` under a real run's writes.
- AppArmor: the node has it enabled (24 profiles enforced), and an unconfined process made 6000 directories below one another without a refusal. No enforcing profile at hand let a test process make directories, and no profile was written, so what a confined helper's `remove_tree` meets was not shown. It names every entry relative to an open directory, by one name, so it passes no long path to the kernel; whether AppArmor's own path of such an entry (which it builds for its check, and which is what a CI container's `mkdir` met at 8192 bytes) refuses the removal under a profile is open, and belongs to the hardening that writes the profile.
- The role: it did not run. Its copies of the units and of the firewall's file were changed with this round's.
- An aarch64 node.
