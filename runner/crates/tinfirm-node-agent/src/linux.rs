//! The Linux backend: a Firecracker VM under its jailer, one jail per VM (ADR-0112 decision 4).
//!
//! **The planning half.** Each function of the first part turns a [`VmPlan`] into the data
//! the backend acts on: the jailer's argument vector, the VMM's configuration, the
//! device-mapper snapshot's table, the network's commands, the firewall's rules, the user-data
//! disk, and what is read back after each step. They are pure, and their tests run anywhere.
//!
//! **The execution half.** [`LinuxBackend`] runs them as root: each command as an argument
//! vector without a shell, each result checked, and what a silent failure would hide read
//! back. Its tests need KVM, Firecracker and root (`tests/linux_kvm.rs`).
//!
//! Every value in what these functions produce comes from the helper's configuration, the
//! slot and the serial. Nothing comes from a caller: no id, no name, no label, no user data.

use std::io::{Read, Write};
use std::net::Ipv4Addr;
use std::os::unix::fs::{DirBuilderExt, FileTypeExt, MetadataExt, OpenOptionsExt};
use std::os::unix::net::UnixStream;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::backend::{Backend, BackendError, Checked, NodeTable, Present};
use crate::bounding;
use crate::hardening::{self, Hardening};
use crate::plan::{Firecracker, HelperConfig, Names, Template, VmPlan};
use crate::spool::MAX_USER_DATA;

/// The VMM's API socket, inside its chroot.
pub const API_SOCKET: &str = "/run/firecracker.socket";
/// The VMM's configuration, inside its chroot.
pub const VM_CONFIG: &str = "/vm.json";
/// The guest kernel, the root disk and the user-data disk, inside the chroot.
pub const KERNEL: &str = "/vmlinux";
pub const ROOT_DISK: &str = "/root.disk";
pub const USER_DATA_DISK: &str = "/userdata.disk";
/// Firecracker's own log (`--log-path`), in the jail: its messages, and nothing of the guest's.
pub const VMM_LOG: &str = "/firecracker.log";
/// The files and directories a jail's own file system may hold: the jailer's and the helper's
/// are about twenty.
pub const JAIL_INODES: u64 = 1024;
/// The chunk of a VM's snapshot, in 512-byte sectors (4 KiB).
pub const SNAPSHOT_CHUNK_SECTORS: u64 = 8;
/// What the kernel keeps in memory for each chunk of a snapshot that was written, in bytes:
/// measured on a node at about 150 (docs/reviews/2026-11-19-node-agent-execution), and no
/// cgroup is charged for it.
pub const SNAPSHOT_CHUNK_MEMORY: u64 = 160;
/// The interfaces inside a VM's network namespace: each namespace has its own, so the names
/// are fixed.
pub const NS_VETH: &str = "veth0";
pub const NS_TAP: &str = "tap0";
/// What the VMM's process may use beyond the guest's memory, in MiB.
pub const VMM_OVERHEAD_MIB: u64 = 128;
/// The user-data disk's header: a magic and the length, little-endian. `vm-boot` in the guest
/// reads this layout (`runner/crates/tinfirm-runner-host/src/vm_boot.rs`, `read_disk`); the two
/// crates must not link each other, so the constants are written twice, and a test on each side
/// checks the same fixture bytes.
pub const USER_DATA_MAGIC: &[u8; 8] = b"TFUDATA1";
/// The user-data disk is padded with zeros to a multiple of this.
pub const DISK_BLOCK: usize = 4096;
/// The guest's device of the user-data disk: Firecracker attaches the drives as virtio block
/// devices in the order of its configuration, the root disk first (`/dev/vda`), so the
/// user-data disk is the second.
pub const USER_DATA_DEVICE: &str = "/dev/vdb";
/// The kernel command line's entry that tells `vm-boot` where the user data is
/// (`vm_boot.rs`, `CMDLINE_KEY`: `tinfirm.user-data=disk:/dev/vdb`).
pub const USER_DATA_CMDLINE: &str = "tinfirm.user-data=disk:/dev/vdb";
/// The most a VMM may write to a regular file (the jailer's `--resource-limit fsize`). Its
/// only regular file is the console file, which is its standard output: the guest's serial
/// console and Firecracker's own messages. A guest that writes more to its serial port ends
/// its own VMM (Firecracker exits on `SIGXFSZ`), and fills no disk of the node.
pub const CONSOLE_MAX_BYTES: u64 = 64 * 1024 * 1024;

/// The memory, in MiB, that the kernel may come to hold for one VM's snapshot once the guest
/// has written all of it: [`SNAPSHOT_CHUNK_MEMORY`] for each chunk.
pub fn snapshot_memory_mib(template: &Template) -> u64 {
    let chunks = snapshot_bytes(template) / (SNAPSHOT_CHUNK_SECTORS * 512);
    (chunks * SNAPSHOT_CHUNK_MEMORY).div_ceil(1024 * 1024)
}

/// The memory of the node, in MiB, that one VM of `template` may take: the guest's, the VMM's
/// own, its jail's file system (memory, since it is a tmpfs) and its snapshot's bookkeeping.
pub fn vm_memory_mib(template: &Template, firecracker: &Firecracker) -> u64 {
    u64::from(template.memory_mib)
        + VMM_OVERHEAD_MIB
        + u64::from(firecracker.jail_mib)
        + snapshot_memory_mib(template)
}

/// The options of a jail's own file system: a tmpfs of `jailMib` with [`JAIL_INODES`] inodes.
pub fn jail_mount_options(firecracker: &Firecracker) -> String {
    format!(
        "size={}m,nr_inodes={JAIL_INODES},mode=0700,uid=0,gid=0",
        firecracker.jail_mib
    )
}

/// The value of the VM's `io.max` for its root disk (`major:minor`), from the template's
/// `ioMax`; `None` when the template sets no limit. The helper writes it into the VM's cgroup
/// itself, once the jailer has made the cgroup: the jailer takes no cgroup value with a `=`
/// in it.
pub fn io_max(template: &Template, disk: (u32, u32)) -> Option<String> {
    let io = template.io_max.as_ref()?;
    let mut words = vec![format!("{}:{}", disk.0, disk.1)];
    for (key, value) in [
        ("rbps", io.read_bytes_per_second),
        ("wbps", io.write_bytes_per_second),
        ("riops", io.reads_per_second),
        ("wiops", io.writes_per_second),
    ] {
        if let Some(value) = value {
            words.push(format!("{key}={value}"));
        }
    }
    (words.len() > 1).then(|| words.join(" "))
}

/// The jailer's argument vector: the VMM as the slot's own user and group, in a chroot of its
/// own, in the VM's network namespace and a new PID namespace, under a cgroup with the
/// template's limits. The jailer makes no limit on its own; these are all it gets.
///
/// Not `--daemonize`: the helper starts the jailer in a process group of its own, and the
/// jailer's first process ends once the VMM runs in its new PID namespace, which tells the
/// helper how the jailer fared.
///
/// Firecracker writes its own messages to [`VMM_LOG`] in the jail (`--log-path`), so its
/// standard output carries the guest's serial console alone.
pub fn jailer_argv(plan: &VmPlan) -> Vec<String> {
    let t = &plan.template;
    let fc = &plan.firecracker;
    let memory = (u64::from(t.memory_mib) + VMM_OVERHEAD_MIB) * 1024 * 1024;
    let jail = plan.names.jail();
    let mut argv = vec![
        fc.jailer.display().to_string(),
        "--id".into(),
        jail.clone(),
        "--exec-file".into(),
        fc.firecracker.display().to_string(),
        "--uid".into(),
        plan.uid.to_string(),
        "--gid".into(),
        plan.gid.to_string(),
        "--chroot-base-dir".into(),
        fc.chroot_base.display().to_string(),
        "--netns".into(),
        format!("/var/run/netns/{jail}"),
        "--new-pid-ns".into(),
        "--cgroup-version".into(),
        "2".into(),
        "--parent-cgroup".into(),
        fc.cgroup_parent.clone(),
        "--cgroup".into(),
        format!("cpu.max={} 100000", u64::from(t.vcpus) * 100_000),
        "--cgroup".into(),
        format!("memory.max={memory}"),
        "--cgroup".into(),
        "memory.swap.max=0".into(),
        "--cgroup".into(),
        format!("pids.max={}", t.pids_max),
        "--resource-limit".into(),
        format!("fsize={CONSOLE_MAX_BYTES}"),
    ];
    argv.extend(
        [
            "--",
            "--api-sock",
            API_SOCKET,
            "--config-file",
            VM_CONFIG,
            "--log-path",
            VMM_LOG,
            "--level",
            "Info",
            "--show-level",
        ]
        .map(String::from),
    );
    argv
}

/// The guest's MAC address: locally administered, from its IPv4 address.
pub fn guest_mac(guest: Ipv4Addr) -> String {
    let o = guest.octets();
    format!("06:00:{:02x}:{:02x}:{:02x}:{:02x}", o[0], o[1], o[2], o[3])
}

/// The VMM's configuration file (`--config-file`), with paths inside the chroot. The guest's
/// address comes on its kernel's command line, so the guest needs no DHCP, and so does where
/// `vm-boot` finds the user data ([`USER_DATA_CMDLINE`]).
pub fn firecracker_config(plan: &VmPlan) -> Value {
    json!({
        "boot-source": {
            "kernel_image_path": KERNEL,
            "boot_args": boot_args(plan),
        },
        "drives": [
            {
                "drive_id": "root",
                "path_on_host": ROOT_DISK,
                "is_root_device": true,
                "is_read_only": false,
            },
            {
                "drive_id": "userdata",
                "path_on_host": USER_DATA_DISK,
                "is_root_device": false,
                "is_read_only": true,
            },
        ],
        "machine-config": {
            "vcpu_count": plan.template.vcpus,
            "mem_size_mib": plan.template.memory_mib,
            "smt": false,
        },
        "network-interfaces": [
            {
                "iface_id": "eth0",
                "host_dev_name": NS_TAP,
                "guest_mac": guest_mac(plan.addresses.guest),
            },
        ],
    })
}

/// The guest kernel's command line. The address is the kernel's own `ip=` (the guest kernel
/// must have `CONFIG_IP_PNP`, as Kata's has): client, no server, the tap device as gateway, a
/// /30, the device, no autoconfiguration. With `guestConsole` the kernel and the guest's
/// journal write to the serial console; without it the guest has no serial port to write to.
pub fn boot_args(plan: &VmPlan) -> String {
    let a = &plan.addresses;
    let console = if plan.firecracker.guest_console {
        "console=ttyS0 systemd.show_status=false systemd.journald.forward_to_console=1"
    } else {
        "8250.nr_uarts=0 quiet"
    };
    format!(
        "{console} reboot=k panic=1 pci=off ip={}::{}:255.255.255.252::eth0:off {USER_DATA_CMDLINE}",
        a.guest, a.tap
    )
}

/// The device-mapper table of a VM's root disk: a snapshot of its own (`cow`) over the shared,
/// read-only image (`origin`, `sectors` long), not kept after the VM (`N`), in chunks of
/// [`SNAPSHOT_CHUNK_SECTORS`].
pub fn snapshot_table(sectors: u64, origin: &str, cow: &str) -> String {
    format!("0 {sectors} snapshot {origin} {cow} N {SNAPSHOT_CHUNK_SECTORS}")
}

/// `dmsetup create` for a VM's snapshot. `--noudevsync`: the helper takes the device's numbers
/// from `dmsetup info` and makes its node in the jail itself, so it needs none of udev's
/// links under `/dev/mapper`, and does not wait for a udev that is stopped or slow.
pub fn snapshot_create_argv(device: &str, table: &str) -> Vec<String> {
    [
        "dmsetup",
        "create",
        "--noudevsync",
        device,
        "--table",
        table,
    ]
    .iter()
    .map(|w| w.to_string())
    .collect()
}

/// What a file system must have free for one more snapshot of `wanted` bytes: the snapshot,
/// and what the snapshots that are there (each `(size, allocated)`) may still grow by. A
/// snapshot file is sparse, so nothing else keeps one guest that fills its disk from taking
/// the room that another VM's writes need. `None` when there is room; the problem otherwise.
pub fn disk_room_problem(free: u64, others: &[(u64, u64)], wanted: u64) -> Option<String> {
    let promised: u64 = others
        .iter()
        .map(|(size, allocated)| size.saturating_sub(*allocated))
        .sum();
    let needed = wanted.saturating_add(promised);
    (free < needed).then(|| {
        format!(
            "the work directory's file system has {} MiB free, and {} MiB are needed: {} MiB for this snapshot and {} MiB that the snapshots of {} other VM(s) may still grow by",
            free >> 20,
            needed >> 20,
            wanted >> 20,
            promised >> 20,
            others.len()
        )
    })
}

/// The tables of `nft list tables`, by family and name (`inet tinfirm_00000000002a`).
pub fn tables_of(listing: &str) -> std::collections::BTreeSet<String> {
    listing
        .lines()
        .filter_map(|line| line.trim().strip_prefix("table "))
        .map(|table| table.trim().to_string())
        .collect()
}

/// The snapshot file's size in bytes.
pub fn snapshot_bytes(template: &Template) -> u64 {
    u64::from(template.snapshot_mib) * 1024 * 1024
}

/// The sysctls of a VM's namespace, set first, before any interface exists in it: no IPv6 on
/// any interface, so nothing there has an address of IPv6, link-local included.
pub const NS_SYSCTLS: [&str; 3] = [
    "net.ipv6.conf.all.disable_ipv6=1",
    "net.ipv6.conf.default.disable_ipv6=1",
    "net.ipv4.ip_forward=1",
];

/// The network's commands, in order: the namespace with IPv6 off, a veth from the node into it
/// (IPv6 off on the node's end too, before it is up), and the tap device that the VMM's user
/// may open. Each is an argument vector, run without a shell.
pub fn network_commands(plan: &VmPlan) -> Vec<Vec<String>> {
    let jail = plan.names.jail();
    let veth = plan.names.veth();
    let a = &plan.addresses;
    let cmd = |words: &[&str]| words.iter().map(|w| w.to_string()).collect::<Vec<_>>();
    let mut commands = vec![cmd(&["ip", "netns", "add", &jail])];
    for sysctl in NS_SYSCTLS {
        commands.push(cmd(&["ip", "netns", "exec", &jail, "sysctl", "-w", sysctl]));
    }
    commands.extend([
        cmd(&[
            "ip", "link", "add", &veth, "type", "veth", "peer", "name", NS_VETH, "netns", &jail,
        ]),
        cmd(&[
            "sysctl",
            "-w",
            &format!("net.ipv6.conf.{veth}.disable_ipv6=1"),
        ]),
        cmd(&[
            "ip",
            "addr",
            "add",
            &format!("{}/30", a.veth_node),
            "dev",
            &veth,
        ]),
        cmd(&["ip", "link", "set", &veth, "up"]),
        cmd(&["ip", "-n", &jail, "link", "set", "lo", "up"]),
        cmd(&[
            "ip",
            "-n",
            &jail,
            "addr",
            "add",
            &format!("{}/30", a.veth_vm),
            "dev",
            NS_VETH,
        ]),
        cmd(&["ip", "-n", &jail, "link", "set", NS_VETH, "up"]),
        cmd(&[
            "ip",
            "-n",
            &jail,
            "route",
            "add",
            "default",
            "via",
            &a.veth_node.to_string(),
        ]),
        cmd(&[
            "ip",
            "-n",
            &jail,
            "tuntap",
            "add",
            "dev",
            NS_TAP,
            "mode",
            "tap",
            "user",
            &plan.uid.to_string(),
            "group",
            &plan.gid.to_string(),
        ]),
        cmd(&[
            "ip",
            "-n",
            &jail,
            "addr",
            "add",
            &format!("{}/30", a.tap),
            "dev",
            NS_TAP,
        ]),
        cmd(&["ip", "-n", &jail, "link", "set", NS_TAP, "up"]),
    ]);
    commands
}

/// The node's own table for a VM (`nft -f`), of the `inet` family so that its chains see IPv4
/// and IPv6 alike: the published port, from the control host alone, goes to the VM's
/// namespace, and only a connection that the published port's DNAT made is let through
/// (`ct status dnat`): the namespace's own address is reached by nobody, the control host
/// included. From that namespace only the answers of such connections come back, in either
/// family nothing new leaves it, and it reaches nothing on the node itself. Deleting the table
/// removes all of it.
pub fn node_rules(plan: &VmPlan) -> String {
    let n = &plan.network;
    let a = &plan.addresses;
    let veth = plan.names.veth();
    let bridge = plan.template.bridge_port;
    format!(
        "table inet {table} {{\n\
         \tchain prerouting {{\n\
         \t\ttype nat hook prerouting priority dstnat; policy accept;\n\
         \t\tip saddr {control} ip daddr {node} tcp dport {port} dnat ip to {veth_vm}:{bridge}\n\
         \t}}\n\
         \tchain forward {{\n\
         \t\ttype filter hook forward priority filter; policy accept;\n\
         \t\toifname \"{veth}\" ip saddr {control} ip daddr {veth_vm} tcp dport {bridge} ct state new ct status dnat accept\n\
         \t\toifname \"{veth}\" ct state established,related accept\n\
         \t\toifname \"{veth}\" drop\n\
         \t\tiifname \"{veth}\" ct state established,related accept\n\
         \t\tiifname \"{veth}\" drop\n\
         \t}}\n\
         \tchain input {{\n\
         \t\ttype filter hook input priority filter; policy accept;\n\
         \t\tiifname \"{veth}\" drop\n\
         \t}}\n\
         }}\n",
        table = plan.names.table(),
        control = n.control_host,
        node = n.node_address,
        port = plan.published_port,
        veth_vm = a.veth_vm,
    )
}

/// The table inside the VM's namespace (`ip netns exec … nft -f`), which root alone can change,
/// of the `inet` family: the control host to the guest's bridge port over IPv4, the answers
/// back, and nothing else in either family, neither from the guest nor from the VMM.
pub fn namespace_rules(plan: &VmPlan) -> String {
    let a = &plan.addresses;
    let bridge = plan.template.bridge_port;
    format!(
        "table inet tinfirm {{\n\
         \tchain prerouting {{\n\
         \t\ttype nat hook prerouting priority dstnat; policy accept;\n\
         \t\tiifname \"{NS_VETH}\" ip saddr {control} ip daddr {veth_vm} tcp dport {bridge} dnat ip to {guest}:{bridge}\n\
         \t}}\n\
         \tchain forward {{\n\
         \t\ttype filter hook forward priority filter; policy drop;\n\
         \t\tiifname \"{NS_VETH}\" oifname \"{NS_TAP}\" ip saddr {control} ip daddr {guest} tcp dport {bridge} ct state new accept\n\
         \t\tct state established,related accept\n\
         \t}}\n\
         \tchain input {{\n\
         \t\ttype filter hook input priority filter; policy drop;\n\
         \t}}\n\
         \tchain output {{\n\
         \t\ttype filter hook output priority filter; policy drop;\n\
         \t}}\n\
         }}\n",
        control = plan.network.control_host,
        veth_vm = a.veth_vm,
        guest = a.guest,
    )
}

/// The user-data disk: [`USER_DATA_MAGIC`], the length as 8 bytes little-endian, the bytes as
/// they came, and zeros to a multiple of [`DISK_BLOCK`]. `vm-boot` in the guest reads it.
pub fn user_data_disk(bytes: &[u8]) -> Option<Vec<u8>> {
    if bytes.is_empty() || bytes.len() as u64 > MAX_USER_DATA {
        return None;
    }
    let mut disk = Vec::with_capacity(bytes.len() + 16 + DISK_BLOCK);
    disk.extend_from_slice(USER_DATA_MAGIC);
    disk.extend_from_slice(&(bytes.len() as u64).to_le_bytes());
    disk.extend_from_slice(bytes);
    let padded = disk.len().div_ceil(DISK_BLOCK) * DISK_BLOCK;
    disk.resize(padded, 0);
    Some(disk)
}

// ----- where a VM's things are ---------------------------------------------------------------

/// Where the kernel's objects and the files of one VM are, from the configuration and the
/// VM's names alone: a delete has no more than these.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Places {
    /// The jail's own directory, `<chrootBase>/firecracker/<jail>`. It is made first and
    /// removed last, so a VM that is half made or half removed is still listed.
    pub jail_dir: PathBuf,
    /// The chroot in it, which the jailer hands to the VM's user.
    pub chroot: PathBuf,
    /// The VMM's cgroup, which the jailer makes.
    pub cgroup: PathBuf,
    /// The network namespace's name in the file system.
    pub netns: PathBuf,
    /// The snapshot's own file.
    pub snapshot_file: PathBuf,
    /// The VMM's standard output with `guestConsole`: the guest's serial console. Without
    /// it the VMM's standard output is `/dev/null`, and this file is not made.
    pub console: PathBuf,
    /// The jailer's and the VMM's standard error: what they say when they cannot start.
    pub errors: PathBuf,
}

pub fn places(firecracker: &Firecracker, names: &Names) -> Places {
    let jail = names.jail();
    let jail_dir = firecracker.chroot_base.join("firecracker").join(&jail);
    Places {
        chroot: jail_dir.join("root"),
        jail_dir,
        cgroup: Path::new("/sys/fs/cgroup")
            .join(&firecracker.cgroup_parent)
            .join(&jail),
        netns: Path::new("/var/run/netns").join(&jail),
        snapshot_file: firecracker.work.join(format!("{jail}.snapshot")),
        console: firecracker.work.join(format!("{jail}.console")),
        errors: firecracker.work.join(format!("{jail}.errors")),
    }
}

/// What is read back after the network's commands, each with the value it must print: a
/// setting that did not take (a service manager that makes `/proc/sys` read-only does that
/// without an error) fails the make instead of leaving a VM that nothing reaches.
pub fn network_read_backs(plan: &VmPlan) -> Vec<(Vec<String>, &'static str)> {
    let jail = plan.names.jail();
    let veth = plan.names.veth();
    let cmd = |words: &[&str]| words.iter().map(|w| w.to_string()).collect::<Vec<_>>();
    let mut checks = Vec::new();
    for sysctl in NS_SYSCTLS {
        let key = sysctl.split('=').next().unwrap_or(sysctl);
        checks.push((
            cmd(&["ip", "netns", "exec", &jail, "sysctl", "-n", key]),
            "1",
        ));
    }
    checks.push((
        cmd(&[
            "sysctl",
            "-n",
            &format!("net.ipv6.conf.{veth}.disable_ipv6"),
        ]),
        "1",
    ));
    // The node itself forwards IPv4: the published port is a DNAT into the namespace.
    checks.push((cmd(&["sysctl", "-n", NODE_FORWARDING]), "1"));
    checks
}

/// The node's own firewall table, as `nft list tables` names it (`units/tinfirm-node-agent.nft`).
pub const NODE_TABLE: &str = "inet tinfirm_node";
/// The node's own setting that every VM needs, and that the node's role must set: the helper
/// reads it and never writes it.
pub const NODE_FORWARDING: &str = "net.ipv4.ip_forward";

/// The loop device of `losetup --noheadings --output NAME,RO --associated <image>` that is
/// read-only: the shared image is attached once, for every VM.
pub fn read_only_loop(listing: &str) -> Option<String> {
    listing.lines().find_map(|line| {
        let mut words = line.split_whitespace();
        let (name, read_only) = (words.next()?, words.next()?);
        (read_only == "1" && name.starts_with("/dev/loop")).then(|| name.to_string())
    })
}

/// `major:minor`, as `dmsetup info -c --noheadings -o major,minor` prints it.
pub fn device_numbers(text: &str) -> Option<(u32, u32)> {
    let (major, minor) = text.trim().split_once(':')?;
    Some((major.trim().parse().ok()?, minor.trim().parse().ok()?))
}

/// What a VMM's `/proc/<pid>/status` must say, as problems: the VM's own user and group in
/// all four places, no supplementary group, no effective, permitted, inheritable or ambient
/// capability, a bounding set within the jailer's six, `NoNewPrivs`, and a seccomp filter.
pub fn vmm_status_problems(status: &str, uid: u32, gid: u32) -> Vec<String> {
    let field = |name: &str| {
        status
            .lines()
            .find_map(|line| line.strip_prefix(name)?.strip_prefix(':'))
            .map(|rest| rest.split_whitespace().collect::<Vec<_>>())
    };
    let hex = |name: &str| {
        field(name)
            .and_then(|words| words.first().copied())
            .and_then(|word| u64::from_str_radix(word, 16).ok())
    };
    let mut problems = Vec::new();
    for (name, id) in [("Uid", uid), ("Gid", gid)] {
        let wanted = id.to_string();
        match field(name) {
            Some(words) if words.len() == 4 && words.iter().all(|w| *w == wanted) => {}
            _ => problems.push(format!("its {name} is not {wanted} in all four places")),
        }
    }
    // No supplementary group: the jailer drops them, whatever its starter had.
    match field("Groups") {
        Some(groups) if groups.is_empty() => {}
        _ => problems.push("it has supplementary groups".to_string()),
    }
    for name in ["CapInh", "CapPrm", "CapEff", "CapAmb"] {
        if hex(name) != Some(0) {
            problems.push(format!("its {name} is not empty"));
        }
    }
    match hex("CapBnd") {
        Some(set) if set & !bounding::jailer_bounding_set() == 0 => {}
        _ => problems.push("its bounding set holds more than the jailer's six".to_string()),
    }
    if field("NoNewPrivs").as_deref() != Some(&["1"]) {
        problems.push("NoNewPrivs is not set".to_string());
    }
    if field("Seccomp").as_deref() != Some(&["2"]) {
        problems.push("it has no seccomp filter".to_string());
    }
    problems
}

// ----- running the node's tools --------------------------------------------------------------

/// Where the node's tools are looked for: the system's directories, never the caller's `PATH`.
pub const TOOL_PATH: &str = "/usr/sbin:/usr/bin:/sbin:/bin";
/// The tools the execution runs.
pub const TOOLS: [&str; 7] = [
    "ip", "nft", "sysctl", "losetup", "dmsetup", "blockdev", "mknod",
];
/// The kernel modules a make needs, by their names under `/sys/module`. The helper loads
/// none: the node loads them at boot (`units/tinfirm-node-agent-modules.service`). Each
/// counts when it is loaded or built into the kernel ([`module_present`]). The tun and loop
/// drivers and the device-mapper's core are checked by their devices instead.
pub const MODULES: [&str; 8] = [
    "kvm",
    "dm_snapshot",
    "veth",
    "nf_tables",
    "nft_ct",
    "nft_chain_nat",
    "nft_nat",
    "nf_nat",
];

/// How long a jailer may take until its first process ends, and a VMM until it says it runs.
const START_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a delete waits for a VM's processes to be gone after the kill.
const KILL_TIMEOUT: Duration = Duration::from_secs(5);

/// Why a tool's run failed: it could not be started, or it ended with an error.
#[derive(Debug)]
enum ToolError {
    Start(String),
    /// `shown` is for a message; `said` is what the tool wrote, for the callers that read it.
    Exit {
        shown: String,
        said: String,
    },
}

impl std::fmt::Display for ToolError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ToolError::Start(why) | ToolError::Exit { shown: why, .. } => f.write_str(why),
        }
    }
}

/// Text of a tool or a console for a message: printable ASCII, at most 300 bytes.
fn plain(text: &[u8]) -> String {
    text.iter()
        .map(|b| match b {
            b' '..=b'~' => *b as char,
            _ => ' ',
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(300)
        .collect()
}

/// How long one tool may run. The helper does one thing at a time, under its record's lock:
/// a tool that never ends (a `dmsetup` that waits for a udev that is stopped, an `nft` that
/// waits for a lock) would stop every later make, delete, list and check.
const TOOL_TIMEOUT: Duration = Duration::from_secs(10);
/// The most of a tool's output that is kept.
const TOOL_OUTPUT_MAX: u64 = 1024 * 1024;

/// Runs one tool: an argument vector, no shell, an empty environment but for [`TOOL_PATH`],
/// `stdin` on its standard input. Its standard output is returned, trimmed; a failure names
/// the command and what it wrote. A tool that has not ended after [`TOOL_TIMEOUT`] is killed
/// with its whole process group (`ip netns exec` starts a second process), and that is a
/// failure to run it, not an answer.
fn tool(argv: &[String], stdin: Option<&str>) -> Result<String, ToolError> {
    tool_within(argv, stdin, TOOL_TIMEOUT)
}

fn tool_within(argv: &[String], stdin: Option<&str>, limit: Duration) -> Result<String, ToolError> {
    let shown = argv.join(" ");
    let mut child = Command::new(&argv[0])
        .args(&argv[1..])
        .env_clear()
        .env("PATH", TOOL_PATH)
        .stdin(if stdin.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0)
        .spawn()
        .map_err(|e| ToolError::Start(format!("cannot run `{shown}`: {}", e.kind())))?;
    if let (Some(text), Some(mut pipe)) = (stdin, child.stdin.take()) {
        // A tool that ends before it read everything fails by its exit status below.
        let _ = pipe.write_all(text.as_bytes());
    }
    // Both outputs are read beside the wait, so that a tool that writes much cannot block on
    // a full pipe; each reader ends when the tool's last process has closed its end.
    let reader = |pipe: Option<Box<dyn Read + Send>>| {
        std::thread::spawn(move || {
            let mut bytes = Vec::new();
            if let Some(pipe) = pipe {
                let _ = pipe.take(TOOL_OUTPUT_MAX).read_to_end(&mut bytes);
            }
            bytes
        })
    };
    let out = reader(
        child
            .stdout
            .take()
            .map(|p| Box::new(p) as Box<dyn Read + Send>),
    );
    let err = reader(
        child
            .stderr
            .take()
            .map(|p| Box::new(p) as Box<dyn Read + Send>),
    );
    // Most tools end within a millisecond or two: the wait starts short and grows, so that a
    // make of two dozen tools does not spend its time asleep.
    let end = Instant::now() + limit;
    let mut pause = Duration::from_micros(100);
    let status = loop {
        let status = child.try_wait().ok().flatten();
        if status.is_some() || Instant::now() >= end {
            break status;
        }
        std::thread::sleep(pause);
        pause = (pause * 2).min(Duration::from_millis(5));
    };
    if status.is_none() {
        // The whole group: the tool, and what it started.
        let _ = rustix::process::kill_process_group(
            rustix::process::Pid::from_child(&child),
            rustix::process::Signal::KILL,
        );
        let _ = child.kill();
        let _ = child.wait();
    }
    let (said_out, said_err) = (
        out.join().unwrap_or_default(),
        err.join().unwrap_or_default(),
    );
    let Some(status) = status else {
        return Err(ToolError::Start(format!(
            "`{shown}` did not end within {} s and was killed",
            limit.as_secs()
        )));
    };
    if !status.success() {
        let said = if said_err.is_empty() {
            &said_out
        } else {
            &said_err
        };
        return Err(ToolError::Exit {
            shown: format!("`{shown}` failed: {}", plain(said)),
            said: String::from_utf8_lossy(said).into_owned(),
        });
    }
    Ok(String::from_utf8_lossy(&said_out).trim().to_string())
}

fn words(list: &[&str]) -> Vec<String> {
    list.iter().map(|w| w.to_string()).collect()
}

fn failed(why: impl std::fmt::Display) -> BackendError {
    BackendError::Failed(why.to_string())
}

/// Whether something of a VM is on the node. A delete may call a thing gone only when the
/// node said so: a question that could not be asked (a directory that cannot be read, a tool
/// that cannot run, a permission that is missing) is `Unknown`, and the VM stays listed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Presence {
    There,
    Gone,
    Unknown(String),
}

/// A path's presence: there when it can be seen, gone only on "no such file or directory".
pub fn presence(path: &Path) -> Presence {
    match path.symlink_metadata() {
        Ok(_) => Presence::There,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Presence::Gone,
        Err(e) => Presence::Unknown(format!("cannot look at {}: {}", path.display(), e.kind())),
    }
}

/// What `nft list table inet <table>` wrote when it failed says that the table does not
/// exist: its first line is `Error: No such file or directory` (nftables 1.0.9; the tools run
/// with an empty environment, so in the C locale). nft ends with the same status 1 when it may
/// not ask the kernel (`Operation not permitted (you must be root)`, then `netlink: Error:
/// cache initialization failed: Operation not permitted`): that is no answer about the table.
pub fn table_is_gone(said: &str) -> bool {
    said.lines().next().map(str::trim) == Some("Error: No such file or directory")
}

/// The processes of a cgroup; none for a cgroup that is not there. A list that cannot be read
/// is an error: it is no proof that nothing runs.
fn cgroup_pids(cgroup: &Path) -> Result<Vec<u32>, String> {
    let file = cgroup.join("cgroup.procs");
    match std::fs::read_to_string(&file) {
        Ok(text) => Ok(text.lines().filter_map(|l| l.trim().parse().ok()).collect()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(format!("cannot read {}: {}", file.display(), e.kind())),
    }
}

/// The VMM of a cgroup: its process named `firecracker`.
fn vmm_pid(cgroup: &Path) -> Result<Option<u32>, String> {
    Ok(cgroup_pids(cgroup)?.into_iter().find(|pid| {
        std::fs::read_to_string(format!("/proc/{pid}/comm"))
            .is_ok_and(|comm| comm.trim() == "firecracker")
    }))
}

/// For each block device of sysfs whose name starts with `prefix`, the text of its file
/// `attribute`, where it has one. An unattached loop device has no backing file, and a device
/// may go while the list is read: those are skipped. A list or a file that cannot be read for
/// another reason is an error.
fn block_devices(prefix: &str, attribute: &str) -> Result<Vec<(String, String)>, String> {
    let cannot =
        |what: &Path, e: std::io::Error| format!("cannot read {}: {}", what.display(), e.kind());
    let dir = Path::new("/sys/block");
    let mut found = Vec::new();
    for entry in std::fs::read_dir(dir).map_err(|e| cannot(dir, e))? {
        let entry = entry.map_err(|e| cannot(dir, e))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.starts_with(prefix) {
            continue;
        }
        let file = entry.path().join(attribute);
        match std::fs::read_to_string(&file) {
            Ok(text) => found.push((name, text)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(cannot(&file, e)),
        }
    }
    Ok(found)
}

/// A device-mapper device of this name exists (from sysfs, so without a tool).
fn dm_exists(name: &str) -> Result<bool, String> {
    Ok(block_devices("dm-", "dm/name")?
        .iter()
        .any(|(_, found)| found.trim() == name))
}

/// A loop device's backing file, as sysfs names it, is `file`. The kernel prints that path as
/// the reader's mount namespace sees it: a helper whose unit binds its work directory, or one
/// started again after the helper that attached the device, reads another prefix than the
/// path it was attached by, and ` (deleted)` after it once the file is gone. The file's own
/// name holds the VM's serial and is never used twice, so the name is what is compared.
pub fn is_backing_file(backing: &str, file: &Path) -> bool {
    let backing = backing.trim();
    let backing = backing.strip_suffix(" (deleted)").unwrap_or(backing);
    match (Path::new(backing).file_name(), file.file_name()) {
        (Some(found), Some(wanted)) => found == wanted,
        _ => false,
    }
}

/// The loop devices whose backing file is `file` (from sysfs), a deleted one included.
fn loops_of(file: &Path) -> Result<Vec<String>, String> {
    Ok(block_devices("loop", "loop/backing_file")?
        .into_iter()
        .filter(|(_, backing)| is_backing_file(backing, file))
        .map(|(name, _)| format!("/dev/{name}"))
        .collect())
}

fn lock<T>(mutex: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// Waits until `gone` says so, at most `limit`.
fn wait_until(limit: Duration, mut gone: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + limit;
    loop {
        if gone() {
            return true;
        }
        if Instant::now() >= end {
            return false;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// A new file of root's that nobody else can read, then given to the VM's user.
fn put(path: &Path, bytes: &[u8], uid: u32, gid: u32) -> Result<(), BackendError> {
    let mut file = std::fs::File::options()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
        .map_err(|e| failed(format!("cannot write {}: {}", path.display(), e.kind())))?;
    file.write_all(bytes)
        .map_err(|e| failed(format!("cannot write {}: {}", path.display(), e.kind())))?;
    own(path, uid, gid)
}

fn own(path: &Path, uid: u32, gid: u32) -> Result<(), BackendError> {
    std::os::unix::fs::lchown(path, Some(uid), Some(gid)).map_err(|e| {
        failed(format!(
            "cannot give {} to the VM's user: {}",
            path.display(),
            e.kind()
        ))
    })
}

/// The first 64 KiB of a regular file, opened without following a link and without waiting:
/// the VMM's log lies in a directory of the VM's user.
fn first_bytes(path: &Path) -> std::io::Result<Vec<u8>> {
    use std::io::Read;
    let file = std::fs::File::options()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)?;
    if !file.metadata()?.is_file() {
        return Err(std::io::ErrorKind::InvalidInput.into());
    }
    let mut bytes = Vec::new();
    file.take(64 * 1024).read_to_end(&mut bytes)?;
    Ok(bytes)
}

/// What Firecracker writes to its log once the VM of its configuration file is started.
pub const VMM_STARTED: &str = "Successfully started microvm";

/// Why a jailer or a VMM did not start, from a file of their own words (never a guest's): as
/// plain text, the first lines that Firecracker marks as errors, or else the file's end, at
/// most 400 characters. The file is read with a bound and is no link.
fn last_words(path: &Path) -> String {
    let bytes = match first_bytes(path) {
        Ok(bytes) if bytes.is_empty() => return "(nothing)".into(),
        Ok(bytes) => bytes,
        Err(e) => return format!("(cannot be read: {})", e.kind()),
    };
    let text: String = bytes
        .iter()
        .map(|b| match b {
            b' '..=b'~' | b'\n' => *b as char,
            _ => ' ',
        })
        .collect();
    let line = |l: &str| l.split_whitespace().collect::<Vec<_>>().join(" ");
    let errors: Vec<String> = text
        .lines()
        .filter(|l| l.contains(":ERROR]"))
        .map(line)
        .collect();
    if errors.is_empty() {
        let all = line(&text);
        let skip = all.chars().count().saturating_sub(400);
        all.chars().skip(skip).collect()
    } else {
        errors.join(" | ").chars().take(400).collect()
    }
}

fn private_dir(path: &Path) -> Result<(), BackendError> {
    std::fs::DirBuilder::new()
        .mode(0o700)
        .create(path)
        .map_err(|e| failed(format!("cannot make {}: {}", path.display(), e.kind())))
}

/// The state the VMM's API reports (`GET /` on its socket): `Running` once the guest runs.
///
/// The socket lies in a directory of the VM's user. It is asked only during a make, before
/// the make returns: until the guest runs, the one process of that user is the VMM that the
/// helper has just started from root's own binary, so the path is still what the VMM made.
/// Even so nothing is trusted of it: the answer is read with a bound and a timeout, nothing
/// of it is used but whether it says `Running`, and the connection is made on a thread of its
/// own with a deadline, since a connect to a local socket whose queue is full does not end.
fn vmm_runs(socket: &Path) -> bool {
    let (answer, asked) = std::sync::mpsc::channel();
    let socket = socket.to_path_buf();
    std::thread::spawn(move || {
        let _ = answer.send(ask_vmm(&socket));
    });
    asked.recv_timeout(Duration::from_secs(3)).unwrap_or(false)
}

fn ask_vmm(socket: &Path) -> bool {
    let Ok(mut stream) = UnixStream::connect(socket) else {
        return false;
    };
    let limit = Some(Duration::from_secs(2));
    if stream.set_read_timeout(limit).is_err() || stream.set_write_timeout(limit).is_err() {
        return false;
    }
    if stream
        .write_all(b"GET / HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut answer = Vec::new();
    let mut buffer = [0u8; 1024];
    while answer.len() < 4096 {
        match stream.read(&mut buffer) {
            Ok(0) | Err(_) => break,
            Ok(n) => answer.extend_from_slice(&buffer[..n]),
        }
        if answer.ends_with(b"}") {
            break;
        }
    }
    let text = String::from_utf8_lossy(&answer);
    text.starts_with("HTTP/1.1 200") && text.contains("\"state\":\"Running\"")
}

// ----- the backend ---------------------------------------------------------------------------

/// The steps of a make, in their order. A test names one to make the make fail right after it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    /// The jail's own directory: the mark that a VM of this name is on the node.
    Mark,
    Network,
    Firewall,
    Disks,
    Jail,
    Vmm,
}

impl Step {
    pub const ALL: [Step; 6] = [
        Step::Mark,
        Step::Network,
        Step::Firewall,
        Step::Disks,
        Step::Jail,
        Step::Vmm,
    ];
}

/// The Linux backend.
pub struct LinuxBackend {
    config: HelperConfig,
    /// The agent's own binary, which the helper starts again as `start-jailer` to cut the
    /// VMM's bounding set (`src/bounding.rs`).
    starter: PathBuf,
    /// Test-only: a make fails right after this step, as if the step after it had.
    fail_after: Option<Step>,
    /// Test-only: a failed make leaves what it made, as a helper that was killed would.
    leave_failed: bool,
    /// The VMs, by serial, whose last delete failed: the next one does not wait again.
    failed_deletes: std::sync::Mutex<std::collections::BTreeSet<u64>>,
    /// The hardening mark's checker, of the machine's own root, with its cached setuid scan.
    hardening: hardening::Checker,
}

impl LinuxBackend {
    pub fn new(config: HelperConfig) -> LinuxBackend {
        // The VMMs' users and groups: slot N's is `vmUidBase + N` (the configuration's check
        // keeps the last below 2^31).
        let last_vmm = config.vm_uid_base + config.max_vms.saturating_sub(1);
        let hardening = hardening::Checker::new("/").with_vmm_ids(config.vm_uid_base, last_vmm);
        LinuxBackend {
            config,
            // The running helper's own program, whatever has happened to its file since.
            starter: PathBuf::from("/proc/self/exe"),
            fail_after: None,
            leave_failed: false,
            failed_deletes: std::sync::Mutex::new(std::collections::BTreeSet::new()),
            hardening,
        }
    }

    /// Test-only: the agent's binary, where the backend does not run inside it.
    #[doc(hidden)]
    pub fn with_starter(mut self, agent: impl Into<PathBuf>) -> LinuxBackend {
        self.starter = agent.into();
        self
    }

    /// Test-only: every make fails right after `step`; with `leave`, it also leaves what it
    /// made, as a helper that was killed at that point would.
    #[doc(hidden)]
    pub fn failing_after(mut self, step: Step, leave: bool) -> LinuxBackend {
        self.fail_after = Some(step);
        self.leave_failed = leave;
        self
    }

    /// The pids of the VMMs that run: the process named `firecracker` in each jail's cgroup.
    /// A jail of a form the helper does not know has a cgroup that cannot be known, so the
    /// VMMs cannot all be listed while one is there.
    fn vmm_pids(&self) -> Result<Vec<u32>, String> {
        let dir = self.config.firecracker.chroot_base.join("firecracker");
        let entries = match std::fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(format!("cannot read {}: {}", dir.display(), e.kind())),
        };
        let mut pids = Vec::new();
        for entry in entries {
            let entry = entry
                .map_err(|e| format!("cannot read an entry of {}: {}", dir.display(), e.kind()))?;
            let Some(names) = entry.file_name().to_str().and_then(Names::from_jail) else {
                return Err("a jail of a form the helper does not know is on the node".into());
            };
            if let Some(pid) = vmm_pid(&places(&self.config.firecracker, &names).cgroup)? {
                pids.push(pid);
            }
        }
        Ok(pids)
    }

    /// Whether the work directory's file system has room for one more snapshot of `wanted`
    /// bytes beside what the snapshots that are there may still grow by ([`disk_room_problem`]).
    fn room_problem(&self, wanted: u64) -> Option<String> {
        let work = &self.config.firecracker.work;
        let stat = match rustix::fs::statvfs(work) {
            Ok(stat) => stat,
            Err(e) => {
                return Some(format!(
                    "the free space of {} cannot be read: {}",
                    work.display(),
                    std::io::Error::from(e).kind()
                ));
            }
        };
        let free = stat.f_bavail.saturating_mul(stat.f_frsize);
        // A snapshot that cannot be read is not counted as absent: the answer is then that
        // the room is not known, and no VM is made on a guess.
        let unread = |what: String, e: std::io::Error| {
            Some(format!(
                "the snapshots in {} cannot be counted ({what}: {}), so the room for one more is not known",
                work.display(),
                e.kind()
            ))
        };
        let entries = match std::fs::read_dir(work) {
            Ok(entries) => entries,
            Err(e) => return unread("the directory".into(), e),
        };
        let mut others = Vec::new();
        for entry in entries {
            let entry = match entry {
                Ok(entry) => entry,
                Err(e) => return unread("an entry".into(), e),
            };
            let name = entry.file_name().to_string_lossy().into_owned();
            if !name.ends_with(".snapshot") {
                continue;
            }
            match entry.metadata() {
                Ok(meta) => others.push((meta.len(), meta.blocks().saturating_mul(512))),
                // Deleted between the listing and now: that one is gone.
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return unread(name, e),
            }
        }
        disk_room_problem(free, &others, wanted)
    }

    fn done(&self, step: Step) -> Result<(), BackendError> {
        if self.fail_after == Some(step) {
            return Err(failed(format!(
                "the make was made to fail after its step {step:?} (a test)"
            )));
        }
        Ok(())
    }

    /// The make's steps. Whatever it returns, the caller removes what is there on an error.
    fn build(&self, plan: &VmPlan, disk: &[u8], places: &Places) -> Result<(), BackendError> {
        let jail = plan.names.jail();
        let run = |argv: &[String], stdin: Option<&str>| tool(argv, stdin).map_err(failed);

        // The mark: from here on, `list` shows this VM, whatever state it is in.
        let parent = places.jail_dir.parent().unwrap_or(Path::new("/"));
        std::fs::create_dir_all(parent)
            .map_err(|e| failed(format!("cannot make {}: {}", parent.display(), e.kind())))?;
        private_dir(&places.jail_dir)?;
        self.done(Step::Mark)?;

        // The network, and what a silent failure would hide.
        for argv in network_commands(plan) {
            run(&argv, None)?;
        }
        for (argv, wanted) in network_read_backs(plan) {
            let found = run(&argv, None)?;
            if found != wanted {
                return Err(failed(format!(
                    "`{}` says {}, not {wanted}: the setting did not take",
                    argv.join(" "),
                    plain(found.as_bytes())
                )));
            }
        }
        self.done(Step::Network)?;

        // The firewall: the node's table of this VM, and the namespace's.
        run(&words(&["nft", "-f", "-"]), Some(&node_rules(plan)))?;
        run(
            &words(&["ip", "netns", "exec", &jail, "nft", "-f", "-"]),
            Some(&namespace_rules(plan)),
        )?;
        let table = plan.names.table();
        let loaded = run(&words(&["nft", "list", "table", "inet", &table]), None)?;
        if !loaded.contains("dnat ip to") || !loaded.contains(&format!("\"{}\"", plan.names.veth()))
        {
            return Err(failed("the node's table of the VM is not as it was loaded"));
        }
        let loaded = run(
            &words(&[
                "ip", "netns", "exec", &jail, "nft", "list", "table", "inet", "tinfirm",
            ]),
            None,
        )?;
        if loaded.matches("policy drop").count() != 3 {
            return Err(failed(
                "the namespace's table is not as it was loaded: three chains must drop by policy",
            ));
        }
        self.done(Step::Firewall)?;

        // The disks: the shared image read-only, and this VM's snapshot over it.
        let image = plan.template.image.display().to_string();
        let listing = run(
            &words(&[
                "losetup",
                "--noheadings",
                "--output",
                "NAME,RO",
                "--associated",
                &image,
            ]),
            None,
        )?;
        let origin = match read_only_loop(&listing) {
            Some(origin) => origin,
            None => run(
                &words(&["losetup", "--find", "--show", "--read-only", &image]),
                None,
            )?,
        };
        std::fs::File::options()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&places.snapshot_file)
            .and_then(|file| file.set_len(snapshot_bytes(&plan.template)))
            .map_err(|e| {
                failed(format!(
                    "cannot make {}: {}",
                    places.snapshot_file.display(),
                    e.kind()
                ))
            })?;
        let snapshot_file = places.snapshot_file.display().to_string();
        let cow = run(
            &words(&["losetup", "--find", "--show", &snapshot_file]),
            None,
        )?;
        let sectors: u64 = run(&words(&["blockdev", "--getsz", &origin]), None)?
            .parse()
            .map_err(|_| failed("the shared image's size cannot be read"))?;
        let device = plan.names.snapshot();
        run(
            &snapshot_create_argv(&device, &snapshot_table(sectors, &origin, &cow)),
            None,
        )?;
        let numbers = run(
            &words(&[
                "dmsetup",
                "info",
                "-c",
                "--noheadings",
                "-o",
                "major,minor",
                &device,
            ]),
            None,
        )?;
        let (major, minor) = device_numbers(&numbers)
            .ok_or_else(|| failed("the snapshot's device numbers cannot be read"))?;
        self.done(Step::Disks)?;

        // The jail: the chroot is a file system of its own, with a size and a number of
        // inodes, so what the VM's user writes there takes no room from the node or from
        // another VM. It is root's until the jailer hands it to the VM's user.
        let chroot = &places.chroot;
        private_dir(chroot)?;
        rustix::mount::mount(
            "tinfirm-jail",
            chroot,
            "tmpfs",
            rustix::mount::MountFlags::NOSUID,
            std::ffi::CString::new(jail_mount_options(&plan.firecracker))
                .expect("no NUL in it")
                .as_c_str(),
        )
        .map_err(|e| {
            failed(format!(
                "cannot mount the jail's file system on {}: {}",
                chroot.display(),
                std::io::Error::from(e).kind()
            ))
        })?;
        let inside = |name: &str| chroot.join(name.trim_start_matches('/'));
        let kernel = std::fs::read(&plan.template.kernel).map_err(|e| {
            failed(format!(
                "cannot read {}: {}",
                plan.template.kernel.display(),
                e.kind()
            ))
        })?;
        put(&inside(KERNEL), &kernel, plan.uid, plan.gid)?;
        drop(kernel);
        put(&inside(USER_DATA_DISK), disk, plan.uid, plan.gid)?;
        let config = serde_json::to_vec(&firecracker_config(plan))
            .map_err(|_| failed("the VMM's configuration cannot be written"))?;
        put(&inside(VM_CONFIG), &config, plan.uid, plan.gid)?;
        put(&inside(VMM_LOG), b"", plan.uid, plan.gid)?;
        let root_disk = inside(ROOT_DISK);
        run(
            &words(&[
                "mknod",
                "-m",
                "600",
                &root_disk.display().to_string(),
                "b",
                &major.to_string(),
                &minor.to_string(),
            ]),
            None,
        )?;
        own(&root_disk, plan.uid, plan.gid)?;
        self.done(Step::Jail)?;

        // The VMM: the jailer, started with the cut bounding set. Its standard output is the
        // guest's serial console: a file with `guestConsole`, nowhere without it, so that a
        // guest's kernel, which can write to the serial port's address whatever its command
        // line says, writes into nothing. Its standard error is a file of its own, which
        // holds what the jailer or Firecracker say when they cannot start.
        let new_file = |path: &Path| {
            std::fs::File::options()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW)
                .open(path)
                .map_err(|e| failed(format!("cannot make {}: {}", path.display(), e.kind())))
        };
        let console = if plan.firecracker.guest_console {
            Stdio::from(new_file(&places.console)?)
        } else {
            Stdio::null()
        };
        let errors = new_file(&places.errors)?;
        // The helper's own binary again, as `start-jailer`: it cuts its bounding set to the
        // jailer's six capabilities and becomes the jailer.
        let mut child = Command::new(&self.starter)
            .arg(bounding::START_JAILER)
            .args(jailer_argv(plan))
            .env_clear()
            .current_dir("/")
            .stdin(Stdio::null())
            .stdout(console)
            .stderr(errors)
            .process_group(0)
            .spawn()
            .map_err(|e| failed(format!("cannot start the jailer: {}", e.kind())))?;
        // With a new PID namespace the jailer's first process ends when the VMM is started.
        let mut status = None;
        wait_until(START_TIMEOUT, || {
            status = child.try_wait().ok().flatten();
            status.is_some()
        });
        let Some(status) = status else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(failed("the jailer did not finish starting the VMM in time"));
        };
        if !status.success() {
            return Err(failed(format!(
                "the jailer failed ({status}): {}",
                last_words(&places.errors)
            )));
        }
        // The template's limit of the VMM's reads and writes of the root disk. The VMM has
        // run for a moment without it by now, and its guest has not.
        if let Some(limit) = io_max(&plan.template, (major, minor)) {
            let parent = places
                .cgroup
                .parent()
                .unwrap_or(Path::new("/sys/fs/cgroup"));
            // Not an error when it is on already; the write below fails when it is not.
            let _ = std::fs::write(parent.join("cgroup.subtree_control"), "+io");
            std::fs::write(places.cgroup.join("io.max"), &limit).map_err(|e| {
                failed(format!(
                    "cannot set the VM's io.max to `{limit}`: {} (the cgroup controller io must be handed down to {})",
                    e.kind(),
                    parent.display()
                ))
            })?;
        }
        // The jailer's second process execs the VMM in the cgroup; when that fails (a
        // capability the jailer lacks, a configuration Firecracker refuses), the cgroup is
        // empty at once, and waiting longer would only hold the helper.
        let socket = inside(API_SOCKET);
        let mut ended = false;
        let log = inside(VMM_LOG);
        let mut started = false;
        let runs = wait_until(START_TIMEOUT, || {
            // The API is asked only once the log says that the VM is started: a request that
            // arrives while Firecracker gives up on its configuration ends its API thread
            // first, and then the log never says why it gave up.
            started = started
                || first_bytes(&log)
                    .is_ok_and(|bytes| String::from_utf8_lossy(&bytes).contains(VMM_STARTED));
            if started && vmm_runs(&socket) {
                return true;
            }
            ended = cgroup_pids(&places.cgroup).is_ok_and(|pids| pids.is_empty());
            ended
        });
        if ended || !runs {
            // Firecracker's own log and its standard error hold no byte of a guest's serial
            // console, and go into the error as plain text, since both are removed with the VM.
            return Err(failed(format!(
                "{}: Firecracker's log ends with: {}; its standard error: {}",
                if ended {
                    "the VMM ended before its guest ran"
                } else {
                    "the VMM did not report a running guest in time"
                },
                last_words(&log),
                last_words(&places.errors)
            )));
        }
        let pid = vmm_pid(&places.cgroup)
            .map_err(failed)?
            .ok_or_else(|| failed("no VMM runs in the VM's cgroup after its start"))?;
        let status = std::fs::read_to_string(format!("/proc/{pid}/status"))
            .map_err(|e| failed(format!("cannot read the VMM's status: {}", e.kind())))?;
        let problems = vmm_status_problems(&status, plan.uid, plan.gid);
        if !problems.is_empty() {
            return Err(failed(format!(
                "the VMM does not run as it must: {}",
                problems.join("; ")
            )));
        }
        self.done(Step::Vmm)
    }
}

/// A file that root owns and nobody else can write: what the helper runs or hands a VM.
fn root_file(problems: &mut Vec<String>, checked: &mut Vec<String>, what: &str, path: &Path) {
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_file() && meta.uid() == 0 && meta.mode() & 0o022 == 0 => {
            checked.push(format!("{what} {}", path.display()));
        }
        Ok(_) => problems.push(format!(
            "{what} {} is not a regular file of root's that only root can write",
            path.display()
        )),
        Err(e) => problems.push(format!("{what} {}: {}", path.display(), e.kind())),
    }
}

/// A directory that root owns and nobody else can write: where the helper makes its things.
fn root_dir(problems: &mut Vec<String>, checked: &mut Vec<String>, what: &str, path: &Path) {
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_dir() && meta.uid() == 0 && meta.mode() & 0o022 == 0 => {
            checked.push(format!("{what} {}", path.display()));
        }
        Ok(_) => problems.push(format!(
            "{what} {} is not a directory of root's that only root can write",
            path.display()
        )),
        Err(e) => problems.push(format!("{what} {}: {}", path.display(), e.kind())),
    }
}

/// Where the node's unit keeps a copy of the kernel's list of built-in modules
/// (`units/tinfirm-node-agent-modules.service`): the helper's own unit hides `/lib/modules`
/// (`ProtectKernelModules`), so the helper cannot read the list where the kernel's package
/// put it.
pub const BUILT_IN_COPY: &str = "/run/tinfirm-node-agent-modules/modules.builtin";

/// `MemTotal` of `/proc/meminfo`, in MiB.
pub fn memory_total_mib(meminfo: &str) -> Option<u64> {
    let line = meminfo.lines().find(|line| line.starts_with("MemTotal:"))?;
    let kib: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(kib / 1024)
}

/// The kernel's cgroups have `cgroup.kill` (Linux 5.14): every cgroup but the root has the
/// file, so the helper's own cgroup shows it; a helper in the root cgroup asks the release.
fn cgroup_kill_there() -> bool {
    let own = std::fs::read_to_string("/proc/self/cgroup").unwrap_or_default();
    let path = own
        .lines()
        .find_map(|line| line.strip_prefix("0::"))
        .unwrap_or("/")
        .trim();
    if path != "/" {
        return Path::new("/sys/fs/cgroup")
            .join(path.trim_start_matches('/'))
            .join("cgroup.kill")
            .exists();
    }
    let release = std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
    let mut numbers = release
        .split(|c: char| !c.is_ascii_digit())
        .filter_map(|n| n.parse::<u32>().ok());
    matches!((numbers.next(), numbers.next()), (Some(major), Some(minor)) if (major, minor) >= (5, 14))
}

/// A kernel module is there: loaded (an entry under `sys_module`, the node's `/sys/module`),
/// or built into the running kernel (a line of `built_in`, the text of the kernel's
/// `modules.builtin`: one path per line, `kernel/drivers/net/veth.ko`). A built-in module has
/// an entry under `/sys/module` only when it has a parameter, so both are asked. The list
/// spells a module's name with `-` or `_`, and may name a compressed file.
pub fn module_present(name: &str, sys_module: &Path, built_in: &str) -> bool {
    sys_module.join(name).exists()
        || built_in.lines().any(|line| {
            let file = line.trim().rsplit('/').next().unwrap_or("");
            let module = file.split_once(".ko").map_or("", |(module, _)| module);
            !module.is_empty() && module.replace('-', "_") == name
        })
}

/// The kernel's list of built-in modules: the first of `places` that can be read.
fn built_in_modules(places: &[PathBuf]) -> Option<String> {
    places
        .iter()
        .find_map(|place| std::fs::read_to_string(place).ok())
}

impl Backend for LinuxBackend {
    /// What a node needs before its first make, each as a problem that names what is missing.
    fn check(&self, template: &Template) -> Checked {
        let mut problems = Vec::new();
        let mut checked = Vec::new();
        for (device, what) in [
            ("/dev/kvm", "this machine has no KVM"),
            ("/dev/net/tun", "the tun module is missing"),
            ("/dev/mapper/control", "the device-mapper is missing"),
            ("/dev/loop-control", "the loop module is missing"),
        ] {
            match std::fs::metadata(device) {
                Ok(meta) if meta.file_type().is_char_device() => checked.push(device.into()),
                _ => problems.push(format!("{device} is missing: {what}")),
            }
        }
        for name in TOOLS {
            let found = TOOL_PATH
                .split(':')
                .map(|dir| Path::new(dir).join(name))
                .find(|path| path.is_file());
            match found {
                Some(path) => checked.push(format!("the tool {}", path.display())),
                None => problems.push(format!("the tool `{name}` is not in {TOOL_PATH}")),
            }
        }
        // Not a check: what runs, for the operator, who answers for a patched kernel.
        checked.push(hardening::kernel_line(Path::new("/")));
        let release = std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
        let built_in = built_in_modules(&[
            PathBuf::from(format!("/lib/modules/{}/modules.builtin", release.trim())),
            PathBuf::from(BUILT_IN_COPY),
        ]);
        for name in MODULES {
            if module_present(
                name,
                Path::new("/sys/module"),
                built_in.as_deref().unwrap_or(""),
            ) {
                checked.push(format!("the module {name}"));
            } else if built_in.is_some() {
                problems.push(format!(
                    "the kernel module {name} is neither loaded (no /sys/module/{name}) nor built into this kernel: the helper loads none"
                ));
            } else {
                problems.push(format!(
                    "the kernel module {name} is not loaded (no /sys/module/{name}), and the kernel's list of built-in modules cannot be read, neither in /lib/modules nor at {BUILT_IN_COPY}: the helper loads none"
                ));
            }
        }
        match std::fs::read_to_string("/proc/sys/net/ipv4/ip_forward") {
            Ok(value) if value.trim() == "1" => checked.push(format!("{NODE_FORWARDING}=1")),
            _ => problems.push(format!(
                "{NODE_FORWARDING} is not 1: the node does not forward a published port to its VM"
            )),
        }
        if cgroup_kill_there() {
            checked.push("cgroup.kill".into());
        } else {
            problems.push(
                "this kernel's cgroups have no `cgroup.kill` (Linux 5.14): the helper ends a VM's processes through it and in no other way".into(),
            );
        }
        // Every slot at once: the snapshots' room, and the VMMs' memory.
        let all = u64::from(self.config.max_vms);
        if let Some(problem) = self.room_problem(all.saturating_mul(snapshot_bytes(template))) {
            problems.push(format!(
                "for {all} VM(s) of this template at once: {problem}"
            ));
        } else {
            checked.push(format!("room for {all} snapshot(s) of this template"));
        }
        // Each VM's guest and VMM, its jail's file system, and what the kernel holds for a
        // snapshot that is written full, which no cgroup is charged for.
        let wanted_mib = all * vm_memory_mib(template, &self.config.firecracker);
        match memory_total_mib(&std::fs::read_to_string("/proc/meminfo").unwrap_or_default()) {
            Some(total) if total >= wanted_mib => {
                checked.push(format!("memory for {all} VM(s) of this template"));
            }
            Some(total) => problems.push(format!(
                "the node has {total} MiB of memory, and {all} VM(s) of this template may use {wanted_mib} MiB (each its memoryMib, {VMM_OVERHEAD_MIB} MiB for its VMM, its jail's {} MiB and {} MiB for a snapshot written full): lower maxVms, the template's memoryMib or its snapshotMib",
                self.config.firecracker.jail_mib,
                snapshot_memory_mib(template)
            )),
            None => problems.push("the node's memory cannot be read from /proc/meminfo".into()),
        }
        match std::fs::read_to_string("/sys/fs/cgroup/cgroup.controllers") {
            Ok(controllers) => {
                for controller in ["cpu", "memory", "pids"] {
                    if controllers.split_whitespace().any(|c| c == controller) {
                        checked.push(format!("the cgroup controller {controller}"));
                    } else {
                        problems.push(format!(
                            "the cgroup controller {controller} is missing: a VM would run without that limit"
                        ));
                    }
                }
            }
            Err(_) => problems.push(
                "/sys/fs/cgroup is not a cgroup v2 hierarchy: a VM would run without limits".into(),
            ),
        }
        // A template with `ioMax` needs the io controller handed down from the root cgroup,
        // which a service manager does only when some unit asks for it.
        if template.io_max.is_some() {
            let handed = std::fs::read_to_string("/sys/fs/cgroup/cgroup.subtree_control")
                .is_ok_and(|c| c.split_whitespace().any(|c| c == "io"));
            if handed {
                checked.push("the cgroup controller io, for the template's ioMax".into());
            } else {
                problems.push(
                    "the template has an ioMax, and the root cgroup does not hand down the controller io (cgroup.subtree_control): the helper's unit asks for it with IOAccounting=yes".into(),
                );
            }
        }
        let fc = &self.config.firecracker;
        root_file(&mut problems, &mut checked, "the jailer", &fc.jailer);
        root_file(&mut problems, &mut checked, "Firecracker", &fc.firecracker);
        root_file(&mut problems, &mut checked, "the VM image", &template.image);
        root_file(
            &mut problems,
            &mut checked,
            "the guest kernel",
            &template.kernel,
        );
        root_dir(
            &mut problems,
            &mut checked,
            "the jails' directory",
            &fc.chroot_base,
        );
        match std::fs::symlink_metadata(&fc.work) {
            Ok(meta)
                if meta.file_type().is_dir() && meta.uid() == 0 && meta.mode() & 0o077 == 0 =>
            {
                checked.push(format!("the work directory {}", fc.work.display()));
            }
            Ok(_) => problems.push(format!(
                "the work directory {} is not a directory of root's that nobody else can enter",
                fc.work.display()
            )),
            Err(e) => problems.push(format!(
                "the work directory {}: {}",
                fc.work.display(),
                e.kind()
            )),
        }
        // The node's own firewall table is asked by the helper itself (`node_table`), which
        // decides with its configuration whether a missing one is a problem.
        Checked { problems, checked }
    }

    /// `nft list tables`, read for the node's own table. The role loads the table; the
    /// helper never loads it again, which would hide that someone removed it.
    fn node_table(&self) -> NodeTable {
        match tool(&words(&["nft", "list", "tables"]), None) {
            Ok(listing) if tables_of(&listing).contains(NODE_TABLE) => NodeTable::There,
            Ok(_) => NodeTable::Missing,
            Err(e) => NodeTable::Unknown(e.to_string()),
        }
    }

    /// Builds the VM and starts its VMM. A make that fails at any step removes what it made
    /// and returns the first error; what it could not remove is named after it, and stays
    /// listed, so the helper's own delete finds it again.
    fn make(&self, plan: &VmPlan, user_data: &[u8]) -> Result<(), BackendError> {
        let places = places(&plan.firecracker, &plan.names);
        match presence(&places.jail_dir) {
            Presence::Gone => {}
            Presence::There => return Err(failed("the jail exists already")),
            Presence::Unknown(why) => return Err(failed(why)),
        }
        if let Some(problem) = self.room_problem(snapshot_bytes(&plan.template)) {
            return Err(failed(problem));
        }
        // Overwritten when it is dropped: the disk is in the jail by then, or never will be,
        // and no copy of the user data stays here.
        let disk = crate::spool::Wiped::new(
            user_data_disk(user_data)
                .ok_or_else(|| failed("the user data is empty or over its size"))?,
        );
        let built = self.build(plan, &disk, &places);
        drop(disk);
        let Err(error) = built else {
            return Ok(());
        };
        if self.leave_failed {
            return Err(error);
        }
        match self.delete(&plan.names) {
            Ok(()) => Err(error),
            Err(left) => Err(failed(format!("{error}; and {left}"))),
        }
    }

    /// Removes everything of the VM named `names`. Every step is tried whatever the one before
    /// did, and each is checked: the delete fails while anything of the VM is left, or while
    /// the node could not be asked whether it is ([`Presence::Unknown`]), and names it. The
    /// jail's directory goes last, and only when nothing else is left, so a VM that could not
    /// be removed is still listed and keeps its slot.
    ///
    /// How each thing is known to be gone:
    ///
    /// | Thing | Gone when | Could not ask when |
    /// |---|---|---|
    /// | The VM's processes | its cgroup's `cgroup.procs` is read and empty, or the cgroup is not there | the file cannot be read |
    /// | The snapshot's device | `/sys/block` is read and no `dm-*` has its name | `/sys/block` or a name in it cannot be read |
    /// | Its loop device | `/sys/block` is read and no `loop*` has its backing file | the same |
    /// | The files, the namespace, the veth, the cgroup, the jail | the path says "no such file or directory" | any other error on the path |
    /// | The node's table | `nft list table` fails with its "No such file or directory" ([`table_is_gone`]) | nft cannot run, or fails with any other text |
    ///
    /// Every process of a VM is in its cgroup: the jailer enters it before it forks, and a
    /// VMM cannot leave it (it may write no other cgroup's `cgroup.procs`). So the delete asks
    /// the cgroup, not the user's process list; and the kernel refuses to remove a cgroup that
    /// still holds a process, which is the second check.
    fn delete(&self, names: &Names) -> Result<(), BackendError> {
        let places = places(&self.config.firecracker, names);
        let mut left: Vec<String> = Vec::new();
        // One thing's state after its step: nothing to say when it is gone.
        let mut check = |what: String, state: Presence| match state {
            Presence::Gone => {}
            Presence::There => left.push(what),
            Presence::Unknown(why) => left.push(format!("{what}, perhaps ({why})")),
        };
        let pids = |cgroup: &Path| match cgroup_pids(cgroup) {
            Ok(pids) if pids.is_empty() => Presence::Gone,
            Ok(_) => Presence::There,
            Err(why) => Presence::Unknown(why),
        };
        let device_state = |device: &str| match dm_exists(device) {
            Ok(false) => Presence::Gone,
            Ok(true) => Presence::There,
            Err(why) => Presence::Unknown(why),
        };
        let loop_state = |file: &Path| match loops_of(file) {
            Ok(found) if found.is_empty() => Presence::Gone,
            Ok(_) => Presence::There,
            Err(why) => Presence::Unknown(why),
        };

        // A VM whose last delete failed is not waited for again: the listener asks for a
        // delete of a VM that cannot be removed about every second, and every make sweeps
        // it, so each step is then tried once, and the answer comes at once.
        let patience = if lock(&self.failed_deletes).contains(&names.serial) {
            Duration::ZERO
        } else {
            KILL_TIMEOUT
        };

        // The VMM and whatever else is in its cgroup, through `cgroup.kill` (`check` asks for
        // it). There is no other way: the helper may not signal another user's process.
        if presence(&places.cgroup) != Presence::Gone {
            let _ = std::fs::write(places.cgroup.join("cgroup.kill"), "1");
            wait_until(patience, || pids(&places.cgroup) != Presence::There);
            check("processes of its cgroup".into(), pids(&places.cgroup));
        }

        // The snapshot's device, then its loop device, then its file.
        let device = names.snapshot();
        if device_state(&device) != Presence::Gone {
            // The VMM's descriptor of the device closes a moment after its process is gone:
            // the first tries may find the device busy. `dmsetup remove --retry` would sleep
            // 200 ms after each; this loop tries again every 10 ms.
            wait_until(patience, || {
                let _ = tool(
                    &words(&["dmsetup", "remove", "--noudevsync", &device]),
                    None,
                );
                device_state(&device) != Presence::There
            });
        }
        check(
            format!("the device-mapper device {device}"),
            device_state(&device),
        );
        for attached in loops_of(&places.snapshot_file).unwrap_or_default() {
            let _ = tool(&words(&["losetup", "--detach", &attached]), None);
        }
        // A loop device is detached once its last user closed it, which may be a moment later.
        // While the snapshot's device is still there it is that user: no waiting for it then.
        if device_state(&device) == Presence::Gone {
            wait_until(patience, || {
                loop_state(&places.snapshot_file) != Presence::There
            });
        }
        check(
            format!("the loop device of {}", places.snapshot_file.display()),
            loop_state(&places.snapshot_file),
        );
        for file in [&places.snapshot_file, &places.console, &places.errors] {
            let _ = std::fs::remove_file(file);
            check(format!("the file {}", file.display()), presence(file));
        }

        // The veth first: removing the node's end removes its peer in the namespace at once,
        // where the namespace's own end would go only a moment after the namespace. Then the
        // namespace, with its tap device and its table.
        let jail = names.jail();
        let veth = names.veth();
        let veth_path = Path::new("/sys/class/net").join(&veth);
        if presence(&veth_path) != Presence::Gone {
            let _ = tool(&words(&["ip", "link", "del", &veth]), None);
        }
        check(format!("the interface {veth}"), presence(&veth_path));
        if presence(&places.netns) != Presence::Gone {
            let _ = tool(&words(&["ip", "netns", "del", &jail]), None);
        }
        check(
            format!("the network namespace {jail}"),
            presence(&places.netns),
        );

        // The node's table of the VM: gone only when nft itself says that it does not exist.
        let table = names.table();
        let listing = words(&["nft", "list", "table", "inet", &table]);
        let table_state = || match tool(&listing, None) {
            Ok(_) => Presence::There,
            Err(ToolError::Exit { said, .. }) if table_is_gone(&said) => Presence::Gone,
            Err(e) => Presence::Unknown(e.to_string()),
        };
        if table_state() == Presence::There {
            let _ = tool(&words(&["nft", "delete", "table", "inet", &table]), None);
        }
        check(format!("the firewall table {table}"), table_state());

        // The cgroup: empty by now, but the kernel may need a moment to let it go.
        if presence(&places.cgroup) != Presence::Gone {
            wait_until(patience, || {
                let _ = std::fs::remove_dir(&places.cgroup);
                presence(&places.cgroup) != Presence::There
            });
        }
        check(
            format!("the cgroup {}", places.cgroup.display()),
            presence(&places.cgroup),
        );

        // The jail's directory: the last, since it is what `list` finds the VM by.
        if left.is_empty() {
            // The jail's own file system goes with everything in it, whatever its user made
            // there. Where it is not mounted (a helper that was started again has a mount
            // namespace of its own, in which the file system of an earlier helper's jail is
            // not: it ended with its VMM), the chroot is an empty directory.
            match rustix::mount::unmount(&places.chroot, rustix::mount::UnmountFlags::DETACH) {
                Ok(()) | Err(rustix::io::Errno::INVAL) | Err(rustix::io::Errno::NOENT) => {}
                Err(e) => left.push(format!(
                    "the jail's file system on {} ({})",
                    places.chroot.display(),
                    std::io::Error::from(e).kind()
                )),
            }
        }
        if left.is_empty() {
            // Not `std::fs::remove_dir_all`: the chroot is the VM's user's, and a tree that
            // user made thousands of levels deep must not end the removal (`crate::tree`).
            match crate::tree::remove_tree(&places.jail_dir) {
                Ok(()) => {}
                Err(e) => left.push(format!(
                    "the jail {} ({})",
                    places.jail_dir.display(),
                    e.kind()
                )),
            }
            if left.is_empty() {
                match presence(&places.jail_dir) {
                    Presence::Gone => {}
                    Presence::There => left.push(format!("the jail {}", places.jail_dir.display())),
                    Presence::Unknown(why) => left.push(format!(
                        "the jail {}, perhaps ({why})",
                        places.jail_dir.display()
                    )),
                }
            }
        }
        if left.is_empty() {
            lock(&self.failed_deletes).remove(&names.serial);
            Ok(())
        } else {
            lock(&self.failed_deletes).insert(names.serial);
            Err(failed(format!(
                "of the VM {jail} this is left: {}",
                left.join(", ")
            )))
        }
    }

    /// Each line of the mark, read under the machine's own root. The VMMs' AppArmor labels are
    /// read through the pids of their jails' cgroups ([`LinuxBackend::vmm_pids`]).
    fn hardening(&self) -> Hardening {
        let vmms = self.vmm_pids();
        self.hardening
            .check(self.config.hardening.as_ref(), &vmms, Instant::now())
    }

    /// Every entry under the chroot base's `firecracker/`, with its names where its name is of
    /// the helper's form, and `alive` while a VMM runs in its cgroup.
    fn list(&self) -> Result<Vec<Present>, BackendError> {
        let dir = self.config.firecracker.chroot_base.join("firecracker");
        let entries = match std::fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => {
                return Err(BackendError::Failed(format!(
                    "cannot read {}: {}",
                    dir.display(),
                    e.kind()
                )));
            }
        };
        // An entry that cannot be read fails the listing: a jail left out of it would give its
        // slot to another VM, and would hide a jail of a form the helper does not know.
        let mut present = Vec::new();
        // Asked once for the whole listing, and only when a VMM runs.
        let mut tables: Option<std::collections::BTreeSet<String>> = None;
        for entry in entries {
            let entry = entry.map_err(|e| {
                BackendError::Failed(format!(
                    "cannot read an entry of {}: {}",
                    dir.display(),
                    e.kind()
                ))
            })?;
            let name = entry.file_name();
            let names = name.to_str().and_then(Names::from_jail);
            // A cgroup that cannot be read fails the listing: "not alive" must be known.
            let mut alive = match names {
                Some(names) => vmm_pid(&places(&self.config.firecracker, &names).cgroup)
                    .map_err(BackendError::Failed)?
                    .is_some(),
                None => false,
            };
            // A VM whose node table is gone (a flush of the node's whole ruleset removes it)
            // still runs, and nothing reaches it: it is reported as not alive, so that the
            // control host ends its run and deletes it, instead of waiting on a dead port.
            if let (true, Some(names)) = (alive, names) {
                if tables.is_none() {
                    let listing = tool(&words(&["nft", "list", "tables"]), None)
                        .map_err(|e| BackendError::Failed(e.to_string()))?;
                    tables = Some(tables_of(&listing));
                }
                alive = tables
                    .as_ref()
                    .is_some_and(|tables| tables.contains(&format!("inet {}", names.table())));
            }
            present.push(Present {
                jail: name.to_string_lossy().into_owned(),
                names,
                alive,
            });
        }
        Ok(present)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plan::{plan, tests::config};

    fn sample(slot: u32, serial: u64) -> VmPlan {
        let config = config();
        let template = config.templates.values().next().unwrap().clone();
        plan(&config, &template, slot, serial)
    }

    fn pairs(argv: &[String]) -> Vec<(String, String)> {
        argv.windows(2)
            .filter(|w| w[0].starts_with("--"))
            .map(|w| (w[0].clone(), w[1].clone()))
            .collect()
    }

    #[test]
    fn the_jailer_runs_the_vmm_as_the_slots_user_in_its_own_jail_with_the_templates_limits() {
        let p = sample(3, 0x2a);
        let argv = jailer_argv(&p);
        assert_eq!(argv[0], "/usr/local/bin/jailer");
        let pairs = pairs(&argv);
        let get = |flag: &str| -> Vec<String> {
            pairs
                .iter()
                .filter(|(f, _)| f == flag)
                .map(|(_, v)| v.clone())
                .collect()
        };
        assert_eq!(get("--id"), ["tfvm-00000000002a-0003"]);
        assert_eq!(get("--uid"), ["1100003"]);
        assert_eq!(get("--gid"), ["1100003"]);
        assert_eq!(get("--exec-file"), ["/usr/local/bin/firecracker"]);
        assert_eq!(get("--chroot-base-dir"), ["/srv/tinfirm-jails"]);
        assert_eq!(get("--netns"), ["/var/run/netns/tfvm-00000000002a-0003"]);
        assert_eq!(get("--parent-cgroup"), ["tinfirm-vms"]);
        assert_eq!(get("--cgroup-version"), ["2"]);
        assert_eq!(
            get("--cgroup"),
            [
                "cpu.max=400000 100000".to_string(),
                format!("memory.max={}", (8192u64 + 128) * 1024 * 1024),
                "memory.swap.max=0".to_string(),
                "pids.max=64".to_string(),
            ]
        );
        assert!(argv.contains(&"--new-pid-ns".to_string()));
        // The VMM's standard output is a regular file of the node: the jailer bounds it.
        assert_eq!(get("--resource-limit"), ["fsize=67108864"]);
        // The helper waits for the jailer's first process and keeps the VMM's output.
        assert!(!argv.contains(&"--daemonize".to_string()));
        // The VMM's own arguments: its API socket and its configuration in the chroot.
        let vmm = &argv[argv.iter().position(|a| a == "--").unwrap() + 1..];
        // Its own messages go to its log in the jail, not to its standard output, which is the
        // guest's serial console.
        assert_eq!(
            vmm,
            [
                "--api-sock",
                API_SOCKET,
                "--config-file",
                VM_CONFIG,
                "--log-path",
                VMM_LOG,
                "--level",
                "Info",
                "--show-level"
            ]
        );
        // The chroot is the one the plan names.
        assert_eq!(
            p.chroot,
            Path::new("/srv/tinfirm-jails/firecracker/tfvm-00000000002a-0003/root")
        );
    }

    #[test]
    fn a_templates_io_limit_is_the_vms_io_max_of_its_root_disk() {
        let mut p = sample(0, 1);
        assert_eq!(io_max(&p.template, (253, 7)), None);
        p.template.io_max = Some(crate::plan::IoMax {
            read_bytes_per_second: Some(104_857_600),
            write_bytes_per_second: None,
            reads_per_second: None,
            writes_per_second: Some(2000),
        });
        assert_eq!(
            io_max(&p.template, (253, 7)).as_deref(),
            Some("253:7 rbps=104857600 wiops=2000")
        );
        // Not through the jailer, which refuses a cgroup value with `=` in it.
        assert!(!jailer_argv(&p).iter().any(|a| a.contains("io.max")));
    }

    #[test]
    fn a_vms_memory_counts_its_jail_and_its_snapshot_written_full() {
        let p = sample(0, 1);
        // 20480 MiB in chunks of 4 KiB: 5 242 880 chunks of 160 bytes each.
        assert_eq!(snapshot_memory_mib(&p.template), 800);
        assert_eq!(
            vm_memory_mib(&p.template, &p.firecracker),
            8192 + 128 + 64 + 800
        );
        assert_eq!(
            jail_mount_options(&p.firecracker),
            "size=64m,nr_inodes=1024,mode=0700,uid=0,gid=0"
        );
        assert_eq!(
            snapshot_table(16, "/dev/loop0", "/dev/loop1"),
            "0 16 snapshot /dev/loop0 /dev/loop1 N 8"
        );
    }

    #[test]
    fn the_room_for_a_snapshot_is_not_known_when_the_work_directory_cannot_be_read() {
        let mut config = config();
        let dir = tempfile::tempdir().unwrap();
        // A file where the directory should be: its free space is read, its entries are not.
        let file = dir.path().join("work");
        std::fs::write(&file, b"").unwrap();
        config.firecracker.work = file;
        let problem = LinuxBackend::new(config.clone()).room_problem(1).unwrap();
        assert!(problem.contains("cannot be counted"), "{problem}");
        // A directory that is there, with a snapshot in it, is counted.
        config.firecracker.work = dir.path().to_path_buf();
        let sparse = std::fs::File::create(dir.path().join("x.snapshot")).unwrap();
        sparse.set_len(1 << 42).unwrap();
        let problem = LinuxBackend::new(config).room_problem(1).unwrap();
        assert!(problem.contains("1 other VM(s)"), "{problem}");
    }

    #[test]
    fn the_vmm_gets_the_shared_image_through_its_snapshot_and_the_user_data_read_only() {
        let p = sample(0, 1);
        let c = firecracker_config(&p);
        let drives = c["drives"].as_array().unwrap();
        assert_eq!(drives.len(), 2);
        assert_eq!(drives[0]["path_on_host"], ROOT_DISK);
        assert_eq!(drives[0]["is_root_device"], true);
        assert_eq!(drives[1]["path_on_host"], USER_DATA_DISK);
        assert_eq!(drives[1]["is_read_only"], true);
        assert_eq!(c["machine-config"]["vcpu_count"], 4);
        assert_eq!(c["machine-config"]["mem_size_mib"], 8192);
        assert_eq!(c["network-interfaces"][0]["host_dev_name"], NS_TAP);
        assert_eq!(c["network-interfaces"][0]["guest_mac"], "06:00:0a:e7:00:06");
        assert_eq!(
            c["boot-source"]["boot_args"],
            "8250.nr_uarts=0 quiet reboot=k panic=1 pci=off ip=10.231.0.6::10.231.0.5:255.255.255.252::eth0:off tinfirm.user-data=disk:/dev/vdb"
        );
        // With `guestConsole`, the kernel and the guest's journal write to the serial console.
        let mut loud = p.clone();
        loud.firecracker.guest_console = true;
        assert_eq!(
            boot_args(&loud),
            "console=ttyS0 systemd.show_status=false systemd.journald.forward_to_console=1 reboot=k panic=1 pci=off ip=10.231.0.6::10.231.0.5:255.255.255.252::eth0:off tinfirm.user-data=disk:/dev/vdb"
        );
        // The user-data disk is the second drive, so the guest's /dev/vdb, which the command
        // line names for `vm-boot`.
        assert_eq!(drives[1]["drive_id"], "userdata");
        assert_eq!(
            USER_DATA_CMDLINE,
            format!("tinfirm.user-data=disk:{USER_DATA_DEVICE}")
        );
        // Every path is inside the chroot: none names a file of the node.
        let text = c.to_string();
        assert!(!text.contains("/srv/") && !text.contains("/var/"), "{text}");

        assert_eq!(
            snapshot_table(41_943_040, "/dev/loop0", "/dev/loop7"),
            "0 41943040 snapshot /dev/loop0 /dev/loop7 N 8"
        );
        assert_eq!(snapshot_bytes(&p.template), 20480 * 1024 * 1024);
    }

    #[test]
    fn the_network_is_the_vms_own_namespace_with_a_tap_its_user_may_open() {
        let p = sample(2, 7);
        let commands = network_commands(&p);
        assert_eq!(
            commands[0],
            ["ip", "netns", "add", "tfvm-000000000007-0002"]
        );
        // IPv6 is off in the namespace before any interface is in it, and on the node's end of
        // the veth before it is up: nothing of the VM's has an IPv6 address, link-local included.
        let jail = "tfvm-000000000007-0002";
        let at = |words: &[&str]| {
            let words: Vec<String> = words.iter().map(|w| w.to_string()).collect();
            commands
                .iter()
                .position(|c| *c == words)
                .unwrap_or_else(|| panic!("{words:?}"))
        };
        let all = at(&[
            "ip",
            "netns",
            "exec",
            jail,
            "sysctl",
            "-w",
            "net.ipv6.conf.all.disable_ipv6=1",
        ]);
        let default = at(&[
            "ip",
            "netns",
            "exec",
            jail,
            "sysctl",
            "-w",
            "net.ipv6.conf.default.disable_ipv6=1",
        ]);
        let veth_made = commands
            .iter()
            .position(|c| c.contains(&"veth".to_string()))
            .unwrap();
        assert!(all < veth_made && default < veth_made);
        let node_end = at(&[
            "sysctl",
            "-w",
            "net.ipv6.conf.tfv000000000007.disable_ipv6=1",
        ]);
        let node_up = at(&["ip", "link", "set", "tfv000000000007", "up"]);
        assert!(veth_made < node_end && node_end < node_up);
        let tap = commands
            .iter()
            .find(|c| c.contains(&"tuntap".to_string()))
            .unwrap();
        assert!(tap.windows(2).any(|w| w == ["user", "1100002"]), "{tap:?}");
        assert!(tap.windows(2).any(|w| w == ["group", "1100002"]), "{tap:?}");
        // Every interface name fits the kernel's limit.
        for c in &commands {
            for w in c {
                assert!(!w.contains('\n') && !w.is_empty());
            }
        }
        assert!(
            commands
                .iter()
                .any(|c| c.contains(&"tfv000000000007".to_string()))
        );
        assert!(
            commands
                .iter()
                .any(|c| c.contains(&"10.231.0.17/30".to_string()))
        );
        assert!(
            commands
                .iter()
                .any(|c| c.contains(&"10.231.0.21/30".to_string()))
        );
    }

    #[test]
    fn the_firewall_lets_in_only_the_control_host_to_the_bridges_port_and_lets_nothing_out() {
        let p = sample(1, 9);
        let node = node_rules(&p);
        assert!(
            node.starts_with("table inet tinfirm_000000000009 {"),
            "{node}"
        );
        // The published port goes to this VM, and only from the control host.
        assert!(node.contains(
            "ip saddr 10.0.0.5 ip daddr 10.0.0.20 tcp dport 20001 dnat ip to 10.231.0.10:7443"
        ));
        assert!(node.contains(
            "oifname \"tfv000000000009\" ip saddr 10.0.0.5 ip daddr 10.231.0.10 tcp dport 7443 ct state new ct status dnat accept"
        ));
        // Only a connection that came through the published port: the control host does not
        // reach the namespace's own address, even with a route to it.
        for line in node.lines() {
            if line.contains("ct state new") {
                assert!(line.contains("ct status dnat"), "{line}");
            }
        }
        // From the VM's namespace: answers only, and nothing to the node itself.
        assert!(node.contains("iifname \"tfv000000000009\" ct state established,related accept\n\t\tiifname \"tfv000000000009\" drop"));
        assert!(node.contains("chain input {\n\t\ttype filter hook input priority filter; policy accept;\n\t\tiifname \"tfv000000000009\" drop"));
        // No rule lets a new connection out of the VM's interface.
        for line in node.lines() {
            if line.contains("iifname \"tfv000000000009\"") && line.contains("accept") {
                assert!(line.contains("established,related"), "{line}");
            }
        }

        let ns = namespace_rules(&p);
        assert!(ns.contains("type filter hook forward priority filter; policy drop;"));
        assert!(ns.contains("type filter hook input priority filter; policy drop;"));
        assert!(ns.contains("type filter hook output priority filter; policy drop;"));
        assert!(ns.contains(
            "iifname \"veth0\" oifname \"tap0\" ip saddr 10.0.0.5 ip daddr 10.231.0.14 tcp dport 7443 ct state new accept"
        ));
        assert!(ns.contains("dnat ip to 10.231.0.14:7443"));
        // Both tables are of the inet family: their drops see IPv6 too.
        assert!(ns.starts_with("table inet tinfirm {"), "{ns}");
        for text in [&node, &ns] {
            assert!(!text.contains("table ip "), "{text}");
            assert!(
                !text.contains("ip6 ") && !text.contains("meta nfproto"),
                "{text}"
            );
        }
        // The only accept of a new connection is the control host's, towards the guest.
        let new_accepts: Vec<&str> = ns
            .lines()
            .filter(|l| l.contains("ct state new accept"))
            .collect();
        assert_eq!(new_accepts.len(), 1, "{ns}");
        assert!(!ns.contains("iifname \"tap0\""), "{ns}");
    }

    /// The fixture of the user-data disk's layout, the same bytes as `vm-boot`'s test of
    /// `read_disk_bytes` (`runner/crates/tinfirm-runner-host/src/vm_boot.rs`): the magic, the
    /// length 13 little-endian, `{"tinfirm":1}`, and zeros to 4096 bytes.
    const DISK_FIXTURE_HEAD: &[u8] = b"TFUDATA1\x0d\x00\x00\x00\x00\x00\x00\x00{\"tinfirm\":1}";

    #[test]
    fn the_user_data_disk_is_the_layout_that_vm_boot_reads() {
        let mut fixture = DISK_FIXTURE_HEAD.to_vec();
        fixture.resize(4096, 0);
        assert_eq!(user_data_disk(br#"{"tinfirm":1}"#).unwrap(), fixture);
    }

    #[test]
    fn the_user_data_disk_holds_the_bytes_as_they_came() {
        let data = b"{\"tinfirm\":1}\n\0odd bytes";
        let disk = user_data_disk(data).unwrap();
        assert_eq!(disk.len() % DISK_BLOCK, 0);
        assert_eq!(&disk[..8], USER_DATA_MAGIC);
        assert_eq!(
            u64::from_le_bytes(disk[8..16].try_into().unwrap()),
            data.len() as u64
        );
        assert_eq!(&disk[16..16 + data.len()], data);
        assert!(disk[16 + data.len()..].iter().all(|b| *b == 0));
        assert_eq!(user_data_disk(&[]), None);
        assert_eq!(user_data_disk(&vec![1; MAX_USER_DATA as usize + 1]), None);
        let full = user_data_disk(&vec![1; MAX_USER_DATA as usize]).unwrap();
        assert_eq!(
            full.len(),
            (MAX_USER_DATA as usize + 16).div_ceil(DISK_BLOCK) * DISK_BLOCK
        );
    }

    #[test]
    fn a_vms_places_come_from_its_names_and_the_jail_is_listed_by_its_directory() {
        let mut config = config();
        let dir = tempfile::tempdir().unwrap();
        config.firecracker.chroot_base = dir.path().to_path_buf();
        let p = places(
            &config.firecracker,
            &Names {
                serial: 12,
                slot: 2,
            },
        );
        let jail = dir.path().join("firecracker/tfvm-00000000000c-0002");
        assert_eq!(p.jail_dir, jail);
        assert_eq!(p.chroot, jail.join("root"));
        assert_eq!(
            p.cgroup,
            Path::new("/sys/fs/cgroup/tinfirm-vms/tfvm-00000000000c-0002")
        );
        assert_eq!(p.netns, Path::new("/var/run/netns/tfvm-00000000000c-0002"));
        assert_eq!(
            p.snapshot_file,
            Path::new("/var/lib/tinfirm-node-agent/work/tfvm-00000000000c-0002.snapshot")
        );
        assert_eq!(
            p.console,
            Path::new("/var/lib/tinfirm-node-agent/work/tfvm-00000000000c-0002.console")
        );
        // The plan's own paths are the same ones.
        let template = config.templates.values().next().unwrap().clone();
        let made = plan(&config, &template, 2, 12);
        assert_eq!(made.chroot, p.chroot);
        assert_eq!(made.snapshot_file, p.snapshot_file);

        std::fs::create_dir_all(&p.chroot).unwrap();
        std::fs::create_dir_all(dir.path().join("firecracker/other")).unwrap();
        // A cgroup parent that no machine has: on a node, the agent's own cannot be read by
        // an ordinary user, which the listing takes for "could not ask", and fails.
        config.firecracker.cgroup_parent = "tinfirm-unit-test-no-such-parent".into();
        let backend = LinuxBackend::new(config.clone());
        let mut listed = backend.list().unwrap();
        listed.sort_by(|a, b| a.jail.cmp(&b.jail));
        // No VMM runs in a cgroup of these names: neither is alive.
        assert_eq!(
            listed,
            [
                Present {
                    jail: "other".into(),
                    names: None,
                    alive: false
                },
                Present {
                    jail: "tfvm-00000000000c-0002".into(),
                    names: Some(Names {
                        serial: 12,
                        slot: 2
                    }),
                    alive: false
                },
            ]
        );
    }

    #[test]
    fn a_make_over_a_jail_that_is_there_is_refused_and_removes_nothing() {
        let mut config = config();
        let dir = tempfile::tempdir().unwrap();
        config.firecracker.chroot_base = dir.path().to_path_buf();
        let template = config.templates.values().next().unwrap().clone();
        let p = plan(&config, &template, 0, 1);
        std::fs::create_dir_all(&p.chroot).unwrap();
        let backend = LinuxBackend::new(config);
        assert_eq!(
            backend.make(&p, b"{}"),
            Err(BackendError::Failed("the jail exists already".into()))
        );
        assert!(p.chroot.exists());
        assert!(matches!(
            backend.make(&p, b""),
            Err(BackendError::Failed(_))
        ));
    }

    #[test]
    fn the_check_names_what_a_node_lacks() {
        let mut config = config();
        let dir = tempfile::tempdir().unwrap();
        config.firecracker.chroot_base = dir.path().join("no-such-jails");
        config.firecracker.work = dir.path().join("no-such-work");
        let template = config.templates.values().next().unwrap().clone();
        let checked = LinuxBackend::new(config).check(&template);
        let has = |part: &str| checked.problems.iter().any(|p| p.contains(part));
        // The configuration's own files are there on a node and missing elsewhere; either
        // way each is named, as a problem or as checked.
        for what in [
            "the jailer /usr/local/bin/jailer",
            "the VM image ",
            "the guest kernel ",
        ] {
            assert!(
                has(what) || checked.checked.iter().any(|c| c.contains(what)),
                "{what}: {checked:?}"
            );
        }
        assert!(has("the jails' directory"), "{checked:?}");
        assert!(has("the work directory"), "{checked:?}");
        assert!(!has("planned") && !has("not built"), "{checked:?}");
    }

    #[test]
    fn what_is_read_back_after_the_network_is_every_setting_a_vm_relies_on() {
        let p = sample(2, 7);
        let checks = network_read_backs(&p);
        let jail = "tfvm-000000000007-0002";
        for key in [
            "net.ipv6.conf.all.disable_ipv6",
            "net.ipv6.conf.default.disable_ipv6",
            "net.ipv4.ip_forward",
        ] {
            let argv: Vec<String> = ["ip", "netns", "exec", jail, "sysctl", "-n", key]
                .iter()
                .map(|w| w.to_string())
                .collect();
            assert!(checks.contains(&(argv, "1")), "{key}");
        }
        let node = |key: &str| {
            let argv: Vec<String> = ["sysctl", "-n", key]
                .iter()
                .map(|w| w.to_string())
                .collect();
            checks.contains(&(argv, "1"))
        };
        assert!(node("net.ipv6.conf.tfv000000000007.disable_ipv6"));
        assert!(node("net.ipv4.ip_forward"));
        // Every sysctl that the commands write is read back.
        assert_eq!(checks.len(), NS_SYSCTLS.len() + 2);
    }

    #[test]
    fn the_shared_images_loop_device_and_the_snapshots_numbers_are_read_from_the_tools() {
        assert_eq!(
            read_only_loop("/dev/loop3   0\n/dev/loop0   1\n"),
            Some("/dev/loop0".into())
        );
        assert_eq!(read_only_loop("/dev/loop3 0\n"), None);
        assert_eq!(read_only_loop(""), None);
        assert_eq!(read_only_loop("garbage 1\n"), None);
        assert_eq!(device_numbers("252:0\n"), Some((252, 0)));
        assert_eq!(device_numbers(" 253 : 17 "), Some((253, 17)));
        assert_eq!(device_numbers("252"), None);
        assert_eq!(device_numbers("a:b"), None);
    }

    /// The status of the VMM of the first real run (`/proc/<pid>/status`, the lines that matter).
    const VMM_STATUS: &str = "Name:\tfirecracker\nUid:\t1100000\t1100000\t1100000\t1100000\n\
        Gid:\t1100000\t1100000\t1100000\t1100000\nGroups:\t \nNoNewPrivs:\t1\nSeccomp:\t2\n\
        CapInh:\t0000000000000000\nCapPrm:\t0000000000000000\nCapEff:\t0000000000000000\n\
        CapBnd:\t00000000082000c3\nCapAmb:\t0000000000000000\n";

    #[test]
    fn a_vmm_must_run_as_its_own_user_without_capabilities_and_with_the_cut_bounding_set() {
        assert_eq!(
            vmm_status_problems(VMM_STATUS, 1_100_000, 1_100_000),
            Vec::<String>::new()
        );
        let wrong = |from: &str, to: &str| {
            let status = VMM_STATUS.replace(from, to);
            assert_ne!(status, VMM_STATUS, "{from}");
            vmm_status_problems(&status, 1_100_000, 1_100_000)
        };
        // The jailer's bounding set as it leaves it without the cut: full.
        assert_eq!(
            wrong("00000000082000c3", "000001ffffffffff"),
            ["its bounding set holds more than the jailer's six"]
        );
        assert_eq!(
            wrong("CapEff:\t0000000000000000", "CapEff:\t0000000000200000"),
            ["its CapEff is not empty"]
        );
        assert_eq!(
            wrong("Uid:\t1100000\t1100000", "Uid:\t1100000\t0"),
            ["its Uid is not 1100000 in all four places"]
        );
        assert_eq!(
            wrong("NoNewPrivs:\t1", "NoNewPrivs:\t0"),
            ["NoNewPrivs is not set"]
        );
        assert_eq!(
            wrong("Seccomp:\t2", "Seccomp:\t0"),
            ["it has no seccomp filter"]
        );
        // What a jailer that kept its starter's groups would leave.
        assert_eq!(
            wrong("Groups:\t \n", "Groups:\t4 27 \n"),
            ["it has supplementary groups"]
        );
        // Another slot's user is not this VM's.
        assert_eq!(
            vmm_status_problems(VMM_STATUS, 1_100_001, 1_100_001).len(),
            2
        );
        // A status that cannot be read is no proof of anything.
        assert_eq!(vmm_status_problems("", 1, 1).len(), 10);
    }

    /// The second and third lines are what a node showed after its helper was started again
    /// under its unit: the same file, read through another mount namespace.
    #[test]
    fn a_snapshots_loop_device_is_found_by_its_files_name_whatever_the_mount_namespace() {
        let file = Path::new("/var/lib/tinfirm-node-agent/work/tfvm-00000000000f-0000.snapshot");
        for backing in [
            "/var/lib/tinfirm-node-agent/work/tfvm-00000000000f-0000.snapshot\n",
            "/tfvm-00000000000f-0000.snapshot\n",
            "/tfvm-00000000000f-0000.snapshot (deleted)\n",
        ] {
            assert!(is_backing_file(backing, file), "{backing}");
        }
        for backing in [
            "/var/lib/tinfirm-node-agent/work/tfvm-000000000010-0000.snapshot\n",
            "/var/lib/tinfirm-node-agent/images/tinfirm-vm.img\n",
            "/var/lib/tinfirm-node-agent/work/tfvm-00000000000f-0000.snapshot.old\n",
            "\n",
        ] {
            assert!(!is_backing_file(backing, file), "{backing}");
        }
    }

    /// The texts are nftables 1.0.9's, captured on a node: a table that is not there, an
    /// ordinary user, root without `CAP_NET_ADMIN` (the same words), and a delete of a table
    /// that is not there. All four end with status 1.
    #[test]
    fn a_table_is_gone_only_when_nft_says_that_it_does_not_exist() {
        assert!(table_is_gone(
            "Error: No such file or directory\nlist table inet tinfirm_000000000abc\n                ^^^^^^^^^^^^^^^^^^^^\n"
        ));
        for no_answer in [
            "Operation not permitted (you must be root)\nnetlink: Error: cache initialization failed: Operation not permitted\n",
            "Error: Could not process rule: No such file or directory\ndelete table inet tinfirm_000000000abc\n",
            "netlink: Error: No such file or directory\n",
            "Error: syntax error, unexpected newline\n",
            "",
            "\nError: No such file or directory\n",
        ] {
            assert!(!table_is_gone(no_answer), "{no_answer}");
        }
    }

    #[test]
    fn a_path_is_gone_only_when_the_node_says_no_such_file() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(presence(dir.path()), Presence::There);
        assert_eq!(presence(&dir.path().join("nothing")), Presence::Gone);
        // A dangling link is there: it is the link that a delete must remove.
        std::os::unix::fs::symlink("/nowhere", dir.path().join("link")).unwrap();
        assert_eq!(presence(&dir.path().join("link")), Presence::There);
        // A path below a file cannot be asked for: that is no "gone".
        std::fs::write(dir.path().join("file"), b"").unwrap();
        assert!(matches!(
            presence(&dir.path().join("file/below")),
            Presence::Unknown(_)
        ));
    }

    #[test]
    fn a_module_counts_when_it_is_loaded_or_built_into_the_kernel() {
        // Files standing in for /sys/module and for the kernel's modules.builtin.
        let dir = tempfile::tempdir().unwrap();
        let sys_module = dir.path().join("sys-module");
        std::fs::create_dir_all(sys_module.join("nf_tables")).unwrap();
        let built_in = "kernel/drivers/net/veth.ko\nkernel/drivers/md/dm-snapshot.ko\n\
                        kernel/net/netfilter/nft_ct.ko.zst\nkernel/drivers/net/tun.ko\n";
        std::fs::write(dir.path().join("modules.builtin"), built_in).unwrap();
        // Loaded, with nothing in the list.
        assert!(module_present("nf_tables", &sys_module, ""));
        // Built in, with no entry under /sys/module: a `-` in the file's name, a compressed file.
        assert!(module_present("veth", &sys_module, built_in));
        assert!(module_present("dm_snapshot", &sys_module, built_in));
        assert!(module_present("nft_ct", &sys_module, built_in));
        // Neither.
        assert!(!module_present("nf_nat", &sys_module, built_in));
        assert!(!module_present("kvm", &sys_module, ""));
        // A name that another module's name only holds.
        assert!(!module_present("tu", &sys_module, built_in));
        assert!(!module_present("snapshot", &sys_module, built_in));

        // The list is read from the first place that has it.
        let missing = dir.path().join("no-such/modules.builtin");
        let copy = dir.path().join("modules.builtin");
        assert_eq!(
            built_in_modules(&[missing.clone(), copy]).as_deref(),
            Some(built_in)
        );
        assert_eq!(built_in_modules(&[missing]), None);
    }

    #[test]
    fn a_tool_that_does_not_end_is_killed_with_what_it_started_and_that_is_no_answer() {
        let argv: Vec<String> = ["sh", "-c", "sleep 60 & sleep 60"]
            .iter()
            .map(|w| w.to_string())
            .collect();
        let start = Instant::now();
        let error = tool_within(&argv, None, Duration::from_millis(300)).unwrap_err();
        // Not after 60 s: the second `sleep` held the pipes, and went with the group.
        assert!(
            start.elapsed() < Duration::from_secs(5),
            "{:?}",
            start.elapsed()
        );
        assert!(
            matches!(&error, ToolError::Start(why) if why.contains("did not end within")),
            "{error}"
        );
        // One that ends in time is read as before, its standard input included.
        let cat: Vec<String> = vec!["cat".into()];
        assert_eq!(
            tool_within(&cat, Some("rules\n"), Duration::from_secs(5)).unwrap(),
            "rules"
        );
        let fails: Vec<String> = ["sh", "-c", "echo no >&2; exit 3"]
            .iter()
            .map(|w| w.to_string())
            .collect();
        assert!(matches!(
            tool_within(&fails, None, Duration::from_secs(5)),
            Err(ToolError::Exit { said, .. }) if said == "no\n"
        ));
    }

    #[test]
    fn a_snapshot_needs_its_own_room_and_what_the_others_may_still_grow_by() {
        const GIB: u64 = 1 << 30;
        // An empty work directory: the snapshot's size.
        assert_eq!(disk_room_problem(8 * GIB, &[], 8 * GIB), None);
        assert!(disk_room_problem(8 * GIB - 1, &[], 8 * GIB).is_some());
        // Two snapshots of 8 GiB there, one with 1 GiB written, one full: 7 GiB are promised.
        let others = [(8 * GIB, GIB), (8 * GIB, 8 * GIB)];
        assert_eq!(disk_room_problem(15 * GIB, &others, 8 * GIB), None);
        let problem = disk_room_problem(15 * GIB - 1, &others, 8 * GIB).unwrap();
        assert!(
            problem.contains("15359 MiB free") && problem.contains("15360 MiB are needed"),
            "{problem}"
        );
        assert!(problem.contains("2 other VM(s)"), "{problem}");
        // The command for the snapshot does not wait for udev.
        assert_eq!(
            snapshot_create_argv(
                "tinfirm-tfvm-000000000001-0000",
                "0 8 snapshot /dev/loop0 /dev/loop1 N 8"
            ),
            [
                "dmsetup",
                "create",
                "--noudevsync",
                "tinfirm-tfvm-000000000001-0000",
                "--table",
                "0 8 snapshot /dev/loop0 /dev/loop1 N 8"
            ]
        );
    }

    #[test]
    fn the_nodes_tables_and_its_memory_are_read_from_what_the_node_prints() {
        let tables = tables_of(
            "table ip security\ntable inet tinfirm_node\ntable inet tinfirm_00000000002a\n",
        );
        assert!(tables.contains("inet tinfirm_00000000002a"));
        assert!(tables.contains("inet tinfirm_node"));
        assert!(!tables.contains("inet tinfirm_00000000002b"));
        // Another family's table of the same name is not the VM's.
        assert!(
            !tables_of("table ip tinfirm_00000000002a\n").contains("inet tinfirm_00000000002a")
        );
        assert!(tables_of("").is_empty());
        assert_eq!(
            memory_total_mib("MemTotal:       16375032 kB\nMemFree:  1 kB\n"),
            Some(15991)
        );
        assert_eq!(memory_total_mib("MemFree: 1 kB\n"), None);
    }

    #[test]
    fn a_tools_words_reach_a_message_only_as_plain_text() {
        assert_eq!(
            plain(b"Error:\x1b[31m no\nsuch\ttable\0\xff"),
            "Error: [31m no such table"
        );
        assert_eq!(plain(&[b'a'; 1000]).len(), 300);
    }
}
