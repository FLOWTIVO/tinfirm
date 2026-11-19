//! The helper's configuration and what it derives from it: for each VM, its user and group,
//! its addresses, its published port, its names and its jail directory, as pure functions of
//! the configuration, a slot and a serial. No value a caller gave goes into any of them.
//!
//! - A **slot** (0 to `maxVms - 1`) is held by one live VM at a time. The user, the addresses
//!   and the published port come from it, so no two live VMs share one.
//! - A **serial** is the helper's own counter, never reused (it is kept on disk). Every name
//!   of a VM's kernel objects and its jail directory come from it, so a jail directory is made
//!   for one VM and never used again, even after a delete.

use std::collections::BTreeMap;
use std::net::Ipv4Addr;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use serde::Deserialize;

use crate::id::TemplateName;

/// The most VMs one helper runs at once.
pub const MAX_SLOTS: u32 = 1024;
/// The addresses of one slot: a /29, two /30 links.
pub const ADDRESSES_PER_SLOT: u32 = 8;
/// The lowest first uid of the VMs' users: above every system and login user.
pub const MIN_VM_UID: u32 = 100_000;
/// The highest serial: its names must fit the kernel's 15 bytes for an interface.
pub const MAX_SERIAL: u64 = (1 << 48) - 1;
/// Where the sandbox's bridge listens in the VM, by default (ADR-0096).
pub const DEFAULT_BRIDGE_PORT: u16 = 7443;

/// One template: what a VM is made from. Only the helper's configuration names these values.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Template {
    pub vcpus: u8,
    pub memory_mib: u32,
    /// The size of the VM's own snapshot over the shared image (its writes), in MiB.
    pub snapshot_mib: u32,
    /// The shared, read-only root image.
    pub image: PathBuf,
    /// The guest kernel.
    pub kernel: PathBuf,
    #[serde(default = "default_bridge_port")]
    pub bridge_port: u16,
    /// The VMM's process limit (cgroup `pids.max`).
    #[serde(default = "default_pids")]
    pub pids_max: u32,
}

fn default_bridge_port() -> u16 {
    DEFAULT_BRIDGE_PORT
}

fn default_pids() -> u32 {
    64
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Network {
    /// The VMs' own range, `A.B.C.D/P`: a /29 per slot. It must not hold the node's or the
    /// control host's address.
    pub vm_range: String,
    /// The node's address, on which each VM's bridge is published.
    pub node_address: Ipv4Addr,
    /// The control host: the only source let in, and only to a VM's published port.
    pub control_host: Ipv4Addr,
    /// The published port of slot 0; slot N's is `firstPort + N`.
    pub first_port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Firecracker {
    pub jailer: PathBuf,
    pub firecracker: PathBuf,
    /// The jailer's `--chroot-base-dir`.
    pub chroot_base: PathBuf,
    /// The cgroup under which the jailer makes each VM's own (`--parent-cgroup`).
    pub cgroup_parent: String,
    /// Where the helper keeps each VM's snapshot file, outside every jail.
    pub work: PathBuf,
}

/// The helper's configuration file.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HelperConfig {
    /// The listener's user: the only peer of the helper's socket, and the owner of every user
    /// data file the helper reads.
    pub listener_uid: u32,
    /// The helper's socket.
    pub socket: PathBuf,
    /// The listener's spool directory, where it writes each VM's user data.
    pub spool: PathBuf,
    /// The helper's own directory: its record of the VMs and of the next serial.
    pub state: PathBuf,
    pub max_vms: u32,
    /// The user and group of slot N's VMM are `vmUidBase + N`.
    pub vm_uid_base: u32,
    pub network: Network,
    pub firecracker: Firecracker,
    pub templates: BTreeMap<TemplateName, Template>,
    /// Test-only: the listener may be root, as the tests run.
    #[serde(skip)]
    #[doc(hidden)]
    pub allow_root_listener: bool,
}

impl HelperConfig {
    /// Reads the helper's configuration. The helper is root and runs what this file names, so
    /// the file must be root's or the helper's own user's, and neither its group nor others
    /// may write it ([`check_config_file`]). The check and the read are of the one opened
    /// file, so no other file can take its place between them.
    pub fn load(path: &Path) -> Result<HelperConfig> {
        use std::os::unix::fs::MetadataExt;
        let file =
            std::fs::File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
        let meta = file
            .metadata()
            .with_context(|| format!("cannot read {}", path.display()))?;
        let own = crate::local::own_uid()?;
        if let Err(problem) = check_config_file(
            meta.file_type().is_file(),
            meta.uid(),
            meta.mode() & 0o7777,
            own,
        ) {
            bail!("the helper's configuration {} {problem}", path.display());
        }
        let text = read_bounded_file(file, path, 1024 * 1024)?;
        let config: HelperConfig = serde_json::from_slice(&text)
            .with_context(|| format!("{} is not a valid helper configuration", path.display()))?;
        config.validate()?;
        Ok(config)
    }

    /// Every rule the derivations rely on.
    pub fn validate(&self) -> Result<()> {
        if self.listener_uid == 0 && !self.allow_root_listener {
            bail!("listenerUid is 0: the listener must not be root");
        }
        if self.max_vms == 0 || self.max_vms > MAX_SLOTS {
            bail!("maxVms must be 1 to {MAX_SLOTS}");
        }
        for (name, path) in [
            ("socket", &self.socket),
            ("spool", &self.spool),
            ("state", &self.state),
            ("firecracker.jailer", &self.firecracker.jailer),
            ("firecracker.firecracker", &self.firecracker.firecracker),
            ("firecracker.chrootBase", &self.firecracker.chroot_base),
            ("firecracker.work", &self.firecracker.work),
        ] {
            plain_path(name, path)?;
        }
        // The jailer names the chroot after the VMM's file: chroot_dir() relies on it.
        if self.firecracker.firecracker.file_name() != Some(std::ffi::OsStr::new("firecracker")) {
            bail!("firecracker.firecracker must be a file named `firecracker`");
        }
        let cgroup = &self.firecracker.cgroup_parent;
        if cgroup.is_empty()
            || cgroup.len() > 64
            || !cgroup
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        {
            bail!("firecracker.cgroupParent must be one name of letters, digits, - and _");
        }
        // The users.
        let last_uid = u64::from(self.vm_uid_base) + u64::from(self.max_vms) - 1;
        if self.vm_uid_base < MIN_VM_UID || last_uid >= 1 << 31 {
            bail!("vmUidBase must be at least {MIN_VM_UID}, and vmUidBase + maxVms below 2^31");
        }
        if (u64::from(self.vm_uid_base)..=last_uid).contains(&u64::from(self.listener_uid)) {
            bail!("listenerUid is one of the VMs' users");
        }
        // The ports.
        let last_port = u32::from(self.network.first_port) + self.max_vms - 1;
        if self.network.first_port < 1024 || last_port > 65535 {
            bail!(
                "network.firstPort must be at least 1024, and firstPort + maxVms - 1 at most 65535"
            );
        }
        // The addresses.
        let (base, prefix) = parse_range(&self.network.vm_range)?;
        let size = 1u64 << (32 - prefix);
        if u64::from(self.max_vms) * u64::from(ADDRESSES_PER_SLOT) > size {
            bail!(
                "network.vmRange /{prefix} holds {} slots of {ADDRESSES_PER_SLOT} addresses, fewer than maxVms",
                size / u64::from(ADDRESSES_PER_SLOT)
            );
        }
        // A control host on the node itself would dial the published port from the node's own
        // stack, which the prerouting DNAT never sees; an output-hook DNAT matched on the source
        // alone would admit every local user. That is the single machine's own firewall table
        // (ADR-0112 decision 5), which is planned.
        let (control, node) = (self.network.control_host, self.network.node_address);
        if control == node || control.is_loopback() || node.is_loopback() {
            bail!(
                "network.controlHost is the node itself (its address or loopback): a single machine needs its own firewall table, which is planned (ADR-0112 decision 5)"
            );
        }
        if control.is_unspecified() || node.is_unspecified() {
            bail!("network.controlHost and network.nodeAddress must be addresses, not 0.0.0.0");
        }
        for (name, address) in [("nodeAddress", node), ("controlHost", control)] {
            let a = u64::from(u32::from(address));
            if a >= u64::from(base) && a < u64::from(base) + size {
                bail!("network.{name} is inside network.vmRange");
            }
        }
        // The templates.
        if self.templates.is_empty() {
            bail!("the configuration has no templates");
        }
        for (name, t) in &self.templates {
            if t.vcpus == 0 || t.vcpus > 32 {
                bail!("template `{name}`: vcpus must be 1 to 32");
            }
            if t.memory_mib < 128 || t.memory_mib > 256 * 1024 {
                bail!("template `{name}`: memoryMib must be 128 to 262144");
            }
            if t.snapshot_mib < 64 || t.snapshot_mib > 1024 * 1024 {
                bail!("template `{name}`: snapshotMib must be 64 to 1048576");
            }
            if t.bridge_port == 0 {
                bail!("template `{name}`: bridgePort must not be 0");
            }
            if t.pids_max == 0 || t.pids_max > 4096 {
                bail!("template `{name}`: pidsMax must be 1 to 4096");
            }
            plain_path("image", &t.image).with_context(|| format!("template `{name}`"))?;
            plain_path("kernel", &t.kernel).with_context(|| format!("template `{name}`"))?;
        }
        Ok(())
    }

    /// The VMs' range, checked by [`HelperConfig::validate`].
    pub fn range_base(&self) -> u32 {
        parse_range(&self.network.vm_range)
            .map(|(base, _)| base)
            .unwrap_or(0)
    }
}

/// An absolute path without `..`, and with no byte that a command line or a file system would
/// read in another way.
fn plain_path(name: &str, path: &Path) -> Result<()> {
    let text = path.to_str().unwrap_or("");
    if !path.is_absolute()
        || text.is_empty()
        || text.split('/').any(|part| part == ".." || part == ".")
        || !text
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'-' | b'_' | b'.'))
    {
        bail!("{name} must be an absolute path of letters, digits, /, -, _ and .");
    }
    Ok(())
}

/// What the root helper asks of its configuration file: a regular file of root or of the
/// helper's own user (`own`; the same as root where the helper is installed), which neither
/// its group nor others can write. A file that the listener's user could write would hand
/// that user the helper's root, since the file names the programs the helper runs.
pub fn check_config_file(is_file: bool, uid: u32, mode: u32, own: u32) -> Result<(), String> {
    if !is_file {
        return Err("is not a regular file".into());
    }
    if uid != 0 && uid != own {
        return Err(format!(
            "belongs to uid {uid}: it must be root's or the helper's own user's"
        ));
    }
    if mode & 0o022 != 0 {
        return Err(format!(
            "has mode {mode:04o}: neither its group nor others may write it"
        ));
    }
    Ok(())
}

/// Reads a file of at most `limit` bytes.
pub fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>> {
    let file =
        std::fs::File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    read_bounded_file(file, path, limit)
}

/// Reads an opened file of at most `limit` bytes; `path` is for the message.
fn read_bounded_file(file: std::fs::File, path: &Path, limit: u64) -> Result<Vec<u8>> {
    use std::io::Read;
    let mut bytes = Vec::new();
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .with_context(|| format!("cannot read {}", path.display()))?;
    if bytes.len() as u64 > limit {
        bail!("{} is over {limit} bytes", path.display());
    }
    Ok(bytes)
}

/// `A.B.C.D/P`, with P from 8 to 29 and no bit set below the prefix.
pub fn parse_range(text: &str) -> Result<(u32, u32)> {
    let (address, prefix) = text
        .split_once('/')
        .context("network.vmRange is not A.B.C.D/P")?;
    let address: Ipv4Addr = address
        .parse()
        .context("network.vmRange is not A.B.C.D/P")?;
    let prefix: u32 = prefix.parse().context("network.vmRange is not A.B.C.D/P")?;
    if !(8..=29).contains(&prefix) {
        bail!("network.vmRange's prefix must be 8 to 29");
    }
    let base = u32::from(address);
    if base & ((1u32 << (32 - prefix)) - 1) != 0 {
        bail!("network.vmRange has bits set below its prefix");
    }
    Ok((base, prefix))
}

// ----- the derivations ---------------------------------------------------------------------

/// The lowest slot below `max` that no live VM holds.
pub fn free_slot(used: impl IntoIterator<Item = u32>, max: u32) -> Option<u32> {
    let used: std::collections::BTreeSet<u32> = used.into_iter().collect();
    (0..max).find(|slot| !used.contains(slot))
}

/// The user and group of slot `slot`'s VMM.
pub fn vm_uid(base: u32, slot: u32) -> u32 {
    base + slot
}

/// The node's port on which slot `slot`'s bridge is published.
pub fn published_port(first: u16, slot: u32) -> u16 {
    // validate() bounds first + maxVms - 1 by 65535.
    u16::try_from(u32::from(first) + slot).unwrap_or(0)
}

/// The addresses of one slot: two /30 links in its /29.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Addresses {
    /// The veth's end in the node's namespace.
    pub veth_node: Ipv4Addr,
    /// The veth's end in the VM's namespace.
    pub veth_vm: Ipv4Addr,
    /// The tap device, in the VM's namespace.
    pub tap: Ipv4Addr,
    /// The guest.
    pub guest: Ipv4Addr,
}

pub fn addresses(range_base: u32, slot: u32) -> Addresses {
    let at = range_base + slot * ADDRESSES_PER_SLOT;
    Addresses {
        veth_node: Ipv4Addr::from(at + 1),
        veth_vm: Ipv4Addr::from(at + 2),
        tap: Ipv4Addr::from(at + 5),
        guest: Ipv4Addr::from(at + 6),
    }
}

/// The names of one VM's kernel objects and jail: from its serial, which makes them unique for
/// good, and its slot, which the jail's name carries so that a VM left on the node without a
/// record still says which slot it holds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Names {
    pub serial: u64,
    pub slot: u32,
}

impl Names {
    /// The jailer's `--id`, the network namespace and the cgroup: `tfvm-`, the serial in 12 hex
    /// digits, `-` and the slot in 4.
    pub fn jail(&self) -> String {
        format!("tfvm-{:012x}-{:04x}", self.serial, self.slot)
    }

    /// The veth's end in the node's namespace: 15 bytes, the kernel's limit.
    pub fn veth(&self) -> String {
        format!("tfv{:012x}", self.serial)
    }

    /// The device-mapper snapshot.
    pub fn snapshot(&self) -> String {
        format!("tinfirm-{}", self.jail())
    }

    /// The node's firewall table of this VM.
    pub fn table(&self) -> String {
        format!("tinfirm_{:012x}", self.serial)
    }

    /// The names of a jail's name; `None` for any other name.
    pub fn from_jail(name: &str) -> Option<Names> {
        let rest = name.strip_prefix("tfvm-")?;
        let (serial, slot) = rest.split_once('-')?;
        let hex = |text: &str, len: usize| {
            text.len() == len && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        };
        if !hex(serial, 12) || !hex(slot, 4) {
            return None;
        }
        let names = Names {
            serial: u64::from_str_radix(serial, 16).ok()?,
            slot: u32::from_str_radix(slot, 16).ok()?,
        };
        (names.slot < MAX_SLOTS).then_some(names)
    }
}

/// The jailer's chroot of a VM: `<chrootBase>/firecracker/<jail>/root`.
pub fn chroot_dir(base: &Path, names: &Names) -> PathBuf {
    base.join("firecracker").join(names.jail()).join("root")
}

/// Everything about one VM that the backend acts on. It holds no value a caller gave: not
/// even the VM's id.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VmPlan {
    pub slot: u32,
    pub names: Names,
    pub uid: u32,
    pub gid: u32,
    pub addresses: Addresses,
    pub published_port: u16,
    pub chroot: PathBuf,
    /// The snapshot's own file, outside the jail.
    pub snapshot_file: PathBuf,
    pub template: Template,
    pub network: Network,
    pub firecracker: Firecracker,
}

/// The plan of the VM in `slot` with `serial`, from `template`.
pub fn plan(config: &HelperConfig, template: &Template, slot: u32, serial: u64) -> VmPlan {
    let names = Names { serial, slot };
    let uid = vm_uid(config.vm_uid_base, slot);
    VmPlan {
        slot,
        names,
        uid,
        gid: uid,
        addresses: addresses(config.range_base(), slot),
        published_port: published_port(config.network.first_port, slot),
        chroot: chroot_dir(&config.firecracker.chroot_base, &names),
        snapshot_file: config
            .firecracker
            .work
            .join(format!("{}.snapshot", names.jail())),
        template: template.clone(),
        network: config.network.clone(),
        firecracker: config.firecracker.clone(),
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::collections::HashSet;

    pub(crate) fn config() -> HelperConfig {
        serde_json::from_value(config_json()).unwrap()
    }

    pub(crate) fn config_json() -> serde_json::Value {
        serde_json::json!({
            "listenerUid": 991,
            "socket": "/run/tinfirm-node-agent-helper/helper.sock",
            "spool": "/var/lib/tinfirm-node-agent/spool",
            "state": "/var/lib/tinfirm-node-agent/helper",
            "maxVms": 64,
            "vmUidBase": 1_100_000,
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
                    "image": "/var/lib/tinfirm-node-agent/images/vm.img",
                    "kernel": "/var/lib/tinfirm-node-agent/images/vmlinux"
                }
            }
        })
    }

    #[test]
    fn the_example_configuration_is_valid() {
        let config = config();
        config.validate().unwrap();
        let t = &config.templates[&TemplateName::parse(b"sandbox-4c8g").unwrap()];
        assert_eq!(t.bridge_port, DEFAULT_BRIDGE_PORT);
        assert_eq!(t.pids_max, 64);
    }

    #[test]
    fn a_configuration_that_breaks_a_rule_is_refused() {
        type Change = Box<dyn Fn(&mut HelperConfig)>;
        let cases: Vec<(&str, Change)> = vec![
            ("root", Box::new(|c| c.listener_uid = 0)),
            ("maxVms", Box::new(|c| c.max_vms = 0)),
            ("maxVms", Box::new(|c| c.max_vms = MAX_SLOTS + 1)),
            ("absolute", Box::new(|c| c.spool = "spool".into())),
            ("absolute", Box::new(|c| c.state = "/var/../etc".into())),
            ("absolute", Box::new(|c| c.socket = "/run/a b".into())),
            (
                "cgroupParent",
                Box::new(|c| c.firecracker.cgroup_parent = "a/b".into()),
            ),
            ("vmUidBase", Box::new(|c| c.vm_uid_base = 1000)),
            ("vmUidBase", Box::new(|c| c.vm_uid_base = u32::MAX - 10)),
            (
                "one of the VMs' users",
                Box::new(|c| c.listener_uid = 1_100_010),
            ),
            ("firstPort", Box::new(|c| c.network.first_port = 80)),
            ("firstPort", Box::new(|c| c.network.first_port = 65500)),
            (
                "fewer than maxVms",
                Box::new(|c| c.network.vm_range = "10.231.0.0/24".into()),
            ),
            (
                "below its prefix",
                Box::new(|c| c.network.vm_range = "10.231.0.1/16".into()),
            ),
            (
                "prefix",
                Box::new(|c| c.network.vm_range = "10.0.0.0/4".into()),
            ),
            (
                "A.B.C.D/P",
                Box::new(|c| c.network.vm_range = "10.231.0.0".into()),
            ),
            (
                "nodeAddress",
                Box::new(|c| c.network.node_address = "10.231.4.4".parse().unwrap()),
            ),
            (
                "controlHost",
                Box::new(|c| c.network.control_host = "10.231.0.1".parse().unwrap()),
            ),
            ("no templates", Box::new(|c| c.templates.clear())),
            (
                "vcpus",
                Box::new(|c| c.templates.values_mut().for_each(|t| t.vcpus = 0)),
            ),
            (
                "memoryMib",
                Box::new(|c| c.templates.values_mut().for_each(|t| t.memory_mib = 1)),
            ),
            (
                "bridgePort",
                Box::new(|c| c.templates.values_mut().for_each(|t| t.bridge_port = 0)),
            ),
            (
                "image",
                Box::new(|c| {
                    c.templates
                        .values_mut()
                        .for_each(|t| t.image = "vm.img".into())
                }),
            ),
        ];
        for (expected, change) in cases {
            let mut c = config();
            change(&mut c);
            let error = format!("{:#}", c.validate().unwrap_err());
            assert!(error.contains(expected), "{expected}: {error}");
        }
    }

    #[test]
    fn an_unknown_field_in_the_configuration_is_refused() {
        let mut value = config_json();
        value["extra"] = 1.into();
        assert!(serde_json::from_value::<HelperConfig>(value).is_err());
        let mut value = config_json();
        value["network"]["allowOut"] = "0.0.0.0/0".into();
        assert!(serde_json::from_value::<HelperConfig>(value).is_err());
        let template = serde_json::json!({
            "vcpus": 1, "memoryMib": 512, "snapshotMib": 512,
            "image": "/a", "kernel": "/b", "command": "/bin/sh"
        });
        assert!(serde_json::from_value::<Template>(template).is_err());
    }

    #[test]
    fn slots_are_the_lowest_free_ones() {
        assert_eq!(free_slot([], 3), Some(0));
        assert_eq!(free_slot([0, 2], 3), Some(1));
        assert_eq!(free_slot([1, 0, 2], 3), None);
        assert_eq!(free_slot([5], 3), Some(0));
    }

    #[test]
    fn no_two_slots_share_a_user_an_address_or_a_port() {
        let config = config();
        let template = config.templates.values().next().unwrap().clone();
        let (base, prefix) = parse_range(&config.network.vm_range).unwrap();
        let (mut uids, mut ips, mut ports) = (HashSet::new(), HashSet::new(), HashSet::new());
        for slot in 0..config.max_vms {
            let p = plan(&config, &template, slot, u64::from(slot) * 7 + 3);
            assert!(uids.insert(p.uid), "uid {}", p.uid);
            assert_eq!(p.uid, p.gid);
            assert!(p.uid >= config.vm_uid_base && p.uid != config.listener_uid);
            assert!(ports.insert(p.published_port), "port {}", p.published_port);
            assert!(p.published_port >= config.network.first_port);
            for ip in [
                p.addresses.veth_node,
                p.addresses.veth_vm,
                p.addresses.tap,
                p.addresses.guest,
            ] {
                assert!(ips.insert(ip), "address {ip}");
                let a = u32::from(ip);
                assert!(
                    a > base && a < base + (1 << (32 - prefix)),
                    "{ip} out of range"
                );
                assert_ne!(ip, config.network.node_address);
                assert_ne!(ip, config.network.control_host);
            }
            // The two links of a slot are two /30s: neither is a network or broadcast address.
            for ip in [
                p.addresses.veth_node,
                p.addresses.veth_vm,
                p.addresses.tap,
                p.addresses.guest,
            ] {
                let low = u32::from(ip) & 3;
                assert!(low == 1 || low == 2, "{ip}");
            }
        }
        assert_eq!(uids.len() as u32, config.max_vms);
    }

    #[test]
    fn names_and_jail_directories_are_never_shared_and_fit_the_kernel() {
        let config = config();
        let template = config.templates.values().next().unwrap().clone();
        let mut seen = HashSet::new();
        // The same slot again after a delete: a new serial, so new names and a new jail.
        for (serial, slot) in [
            (0, 0),
            (1, 0),
            (2, 5),
            (0xfff, 63),
            (0x1000, 0),
            (0xabcdef, 1),
            (MAX_SERIAL, 1023),
        ] {
            let p = plan(&config, &template, slot, serial);
            assert_eq!(p.names.slot, slot);
            for name in [
                p.names.jail(),
                p.names.veth(),
                p.names.snapshot(),
                p.names.table(),
                p.chroot.display().to_string(),
                p.snapshot_file.display().to_string(),
            ] {
                assert!(seen.insert(name.clone()), "{name}");
            }
            assert!(p.names.veth().len() <= 15, "{}", p.names.veth());
            assert!(p.names.jail().len() <= 64);
            assert_eq!(Names::from_jail(&p.names.jail()), Some(p.names));
            assert!(p.chroot.starts_with("/srv/tinfirm-jails/firecracker/"));
            assert!(p.chroot.ends_with("root"));
        }
        // A leftover's slot is read back from its jail's name.
        assert_eq!(
            Names::from_jail("tfvm-00000000002a-0003"),
            Some(Names {
                serial: 42,
                slot: 3
            })
        );
        for bad in [
            "tfvm-",
            "tfvm-00000000000g-0000",
            "tfvm-000000000000",
            "tfvm-0000000000000-0000",
            "tfvm-000000000000-000",
            "tfvm-000000000000-00000",
            "tfvm-000000000000-0400",
            "x-000000000000-0000",
            "tfvm-00000000000A-0000",
            "tfvm-000000000000-0000\n",
        ] {
            assert_eq!(Names::from_jail(bad), None, "{bad:?}");
        }
    }

    #[test]
    fn a_control_host_on_the_node_itself_is_refused_until_the_single_machines_firewall_is_built() {
        for (control, node) in [
            ("10.0.0.20", "10.0.0.20"),
            ("127.0.0.1", "10.0.0.20"),
            ("10.0.0.5", "127.0.0.1"),
            ("127.0.0.2", "127.0.0.2"),
        ] {
            let mut c = config();
            c.network.control_host = control.parse().unwrap();
            c.network.node_address = node.parse().unwrap();
            let error = format!("{:#}", c.validate().unwrap_err());
            assert!(
                error.contains("ADR-0112 decision 5"),
                "{control} {node}: {error}"
            );
        }
        let mut c = config();
        c.network.node_address = "0.0.0.0".parse().unwrap();
        assert!(c.validate().is_err());
    }

    #[test]
    fn the_configuration_file_must_be_roots_or_the_helpers_own_and_theirs_alone_to_write() {
        // Root's file, or the helper's own user's, that others can at most read.
        assert!(check_config_file(true, 0, 0o644, 0).is_ok());
        assert!(check_config_file(true, 0, 0o600, 4242).is_ok());
        assert!(check_config_file(true, 4242, 0o640, 4242).is_ok());
        // Another user's, the listener's for one.
        assert!(check_config_file(true, 991, 0o644, 0).is_err());
        // One that its group or others can write.
        assert!(check_config_file(true, 0, 0o664, 0).is_err());
        assert!(check_config_file(true, 0, 0o646, 0).is_err());
        // A directory, a device, a FIFO.
        assert!(check_config_file(false, 0, 0o644, 0).is_err());
    }

    #[test]
    fn a_configuration_file_that_others_can_write_is_refused_before_it_is_read() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("helper.json");
        std::fs::write(&path, config_json().to_string()).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(HelperConfig::load(&path).is_ok());
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o666)).unwrap();
        let error = format!("{:#}", HelperConfig::load(&path).unwrap_err());
        assert!(
            error.contains("neither its group nor others may write it"),
            "{error}"
        );
        // A directory in its place.
        let error = format!("{:#}", HelperConfig::load(dir.path()).unwrap_err());
        assert!(error.contains("is not a regular file"), "{error}");
    }
}
