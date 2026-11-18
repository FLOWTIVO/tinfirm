//! The Linux backend: a Firecracker VM under its jailer, one jail per VM (ADR-0112 decision 4).
//!
//! **Built:** the planning half. Each function below turns a [`VmPlan`] into the data the
//! backend acts on: the jailer's argument vector, the VMM's configuration, the device-mapper
//! snapshot's table, the network's commands, the firewall's rules and the user-data disk. They
//! are pure, and their tests are what a review reads.
//!
//! **Planned:** the execution. [`LinuxBackend::make`] and [`LinuxBackend::delete`] return
//! [`BackendError::NotBuilt`]: running these as root cannot be tested without KVM and
//! Firecracker, and must be checked against the owner's prototype first.
//!
//! Every value in what these functions produce comes from the helper's configuration, the
//! slot and the serial. Nothing comes from a caller: no id, no name, no label, no user data.

use std::net::Ipv4Addr;
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::path::Path;

use serde_json::{Value, json};

use crate::backend::{Backend, BackendError, Checked, Present};
use crate::plan::{HelperConfig, Names, Template, VmPlan};
use crate::spool::MAX_USER_DATA;

/// The VMM's API socket, inside its chroot.
pub const API_SOCKET: &str = "/run/firecracker.socket";
/// The VMM's configuration, inside its chroot.
pub const VM_CONFIG: &str = "/vm.json";
/// The guest kernel, the root disk and the user-data disk, inside the chroot.
pub const KERNEL: &str = "/vmlinux";
pub const ROOT_DISK: &str = "/root.disk";
pub const USER_DATA_DISK: &str = "/userdata.disk";
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

/// The jailer's argument vector: the VMM as the slot's own user and group, in a chroot of its
/// own, in the VM's network namespace and a new PID namespace, under a cgroup with the
/// template's limits. The jailer makes no limit on its own; these are all it gets.
pub fn jailer_argv(plan: &VmPlan) -> Vec<String> {
    let t = &plan.template;
    let fc = &plan.firecracker;
    let memory = (u64::from(t.memory_mib) + VMM_OVERHEAD_MIB) * 1024 * 1024;
    let jail = plan.names.jail();
    vec![
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
        "--daemonize".into(),
        "--".into(),
        "--api-sock".into(),
        API_SOCKET.into(),
        "--config-file".into(),
        VM_CONFIG.into(),
    ]
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
    let a = &plan.addresses;
    json!({
        "boot-source": {
            "kernel_image_path": KERNEL,
            "boot_args": format!(
                "console=ttyS0 reboot=k panic=1 pci=off ip={}::{}:255.255.255.252::eth0:off {USER_DATA_CMDLINE}",
                a.guest, a.tap
            ),
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
                "guest_mac": guest_mac(a.guest),
            },
        ],
    })
}

/// The device-mapper table of a VM's root disk: a snapshot of its own (`cow`) over the shared,
/// read-only image (`origin`, `sectors` long), not kept after the VM (`N`), in chunks of 4 KiB.
pub fn snapshot_table(sectors: u64, origin: &str, cow: &str) -> String {
    format!("0 {sectors} snapshot {origin} {cow} N 8")
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
/// namespace; from that namespace only the answers of such connections come back, in either
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
         \t\toifname \"{veth}\" ip saddr {control} ip daddr {veth_vm} tcp dport {bridge} ct state new accept\n\
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
/// they came, and zeros to a multiple of [`DISK_BLOCK`]. `vm-boot` reads it as a file (planned).
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

/// The Linux backend.
pub struct LinuxBackend {
    config: HelperConfig,
}

impl LinuxBackend {
    pub fn new(config: HelperConfig) -> LinuxBackend {
        LinuxBackend { config }
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

impl Backend for LinuxBackend {
    fn check(&self, template: &Template) -> Checked {
        let mut problems = vec![
            "the Linux backend makes no VM yet: its execution is planned (ADR-0112)".to_string(),
        ];
        let mut checked = Vec::new();
        match std::fs::metadata("/dev/kvm") {
            Ok(meta) if meta.file_type().is_char_device() => checked.push("/dev/kvm".into()),
            _ => problems.push("/dev/kvm is missing: this machine has no KVM".into()),
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
        Checked { problems, checked }
    }

    fn make(&self, _plan: &VmPlan, _user_data: &[u8]) -> Result<(), BackendError> {
        Err(BackendError::NotBuilt(
            "the Linux backend's execution (jail, network, disks, VMM) is planned",
        ))
    }

    fn delete(&self, _names: &Names) -> Result<(), BackendError> {
        Err(BackendError::NotBuilt(
            "the Linux backend's execution (jail, network, disks, VMM) is planned",
        ))
    }

    /// Every entry under the chroot base's `firecracker/`, with its names where its name is of
    /// the helper's form; none of them runs a VMM, since this backend starts none yet.
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
        Ok(entries
            .flatten()
            .map(|entry| {
                let name = entry.file_name();
                Present {
                    jail: name.to_string_lossy().into_owned(),
                    names: name.to_str().and_then(Names::from_jail),
                    alive: false,
                }
            })
            .collect())
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
        // The VMM's own arguments: its API socket and its configuration in the chroot.
        let vmm = &argv[argv.iter().position(|a| a == "--").unwrap() + 1..];
        assert_eq!(vmm, ["--api-sock", API_SOCKET, "--config-file", VM_CONFIG]);
        // The chroot is the one the plan names.
        assert_eq!(
            p.chroot,
            Path::new("/srv/tinfirm-jails/firecracker/tfvm-00000000002a-0003/root")
        );
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
            "console=ttyS0 reboot=k panic=1 pci=off ip=10.231.0.6::10.231.0.5:255.255.255.252::eth0:off tinfirm.user-data=disk:/dev/vdb"
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
            "oifname \"tfv000000000009\" ip saddr 10.0.0.5 ip daddr 10.231.0.10 tcp dport 7443 ct state new accept"
        ));
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
    fn the_linux_backend_makes_nothing_yet_and_says_so() {
        let mut config = config();
        let dir = tempfile::tempdir().unwrap();
        config.firecracker.chroot_base = dir.path().to_path_buf();
        std::fs::create_dir_all(dir.path().join("firecracker/tfvm-00000000000c-0002/root"))
            .unwrap();
        std::fs::create_dir_all(dir.path().join("firecracker/other")).unwrap();
        let template = config.templates.values().next().unwrap().clone();
        let backend = LinuxBackend::new(config.clone());
        let checked = backend.check(&template);
        assert!(checked.problems[0].contains("planned"), "{checked:?}");
        let p = plan(&config, &template, 0, 1);
        assert!(matches!(
            backend.make(&p, b"{}"),
            Err(BackendError::NotBuilt(_))
        ));
        assert!(matches!(
            backend.delete(&p.names),
            Err(BackendError::NotBuilt(_))
        ));
        let mut listed = backend.list().unwrap();
        listed.sort_by(|a, b| a.jail.cmp(&b.jail));
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
}
