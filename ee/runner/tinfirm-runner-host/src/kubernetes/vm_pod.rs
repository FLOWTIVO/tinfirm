//! `vm-pod`: the program of a run's pod (ADR-0115 decision 2), which runs the run's VM with
//! Firecracker as the pod's unprivileged user. Part of elastic runners, under the Tinfirm
//! Enterprise licence (`ee/LICENSE`). Its paths, names and environment are
//! [`crate::kubernetes::spec::vm_pod`]'s, which the pod's specification uses too.
//!
//! `vm-pod` (the main container, [`run`]):
//!
//! 1. checks the pod: a user namespace of its own, no effective capability, `/dev/kvm` and
//!    `/dev/net/tun` open, the tap there with its address, and the tap not forwarding;
//! 2. writes the ticket's user data as a user-data disk (`TFUDATA1`, crate::vm_boot) to the
//!    memory volume, never to the disk volume;
//! 3. copies the VM image to the disk volume, sparse, grown to the template's size;
//! 4. writes Firecracker's configuration and starts `firecracker` without the jailer, with its
//!    own seccomp filter, its API socket in the memory volume;
//! 5. relays the pod's address and bridge port to the guest's, refusing a peer from the tap's
//!    /30, and writes the ready file once the guest's bridge accepts;
//! 6. ends with Firecracker, and writes why to the termination log. A SIGTERM ends Firecracker
//!    first.
//!
//! `vm-pod ready` ([`ready`]) is the readiness probe: the ready file is there.
//! `vm-pod tap-setup` ([`tap_setup`]) makes the tap in the init variant (super::tap).
//!
//! Nothing here writes the user data to a log, an error or the termination log.

use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use tokio::net::{TcpListener, TcpStream};

use super::spec::vm_pod::*;
use crate::vm_boot::{DISK_BLOCK, DISK_MAGIC};

/// The kubelet keeps 4096 bytes of a termination message.
const MAX_TERMINATION: usize = 4000;
/// The copy's unit: a part all zeros becomes a hole.
const COPY_CHUNK: usize = 1024 * 1024;
/// How often the ready check dials the guest's bridge, and how long one dial may take.
const READY_POLL: Duration = Duration::from_millis(200);
const READY_DIAL: Duration = Duration::from_secs(1);
/// How long Firecracker has to end after SIGTERM before it is killed.
const STOP_GRACE: Duration = Duration::from_secs(4);

/// A path of [`crate::kubernetes::spec::vm_pod`] under `root` (`/` in a pod).
fn at(root: &Path, path: &str) -> PathBuf {
    root.join(path.trim_start_matches('/'))
}

// ----- the pod's settings ----------------------------------------------------------------

/// The pod's settings, from the environment its specification gives it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PodEnv {
    pub pod_ip: Ipv4Addr,
    /// `multus` or `init`.
    pub tap_mode: String,
    /// The tap's /30.
    pub network: Ipv4Addr,
    pub bridge_port: u16,
    pub vcpus: u32,
    pub memory_mib: u32,
    pub disk_mib: u32,
}

impl PodEnv {
    /// The settings from `var`, which reads the environment.
    pub fn from_vars(var: &dyn Fn(&str) -> Option<String>) -> Result<PodEnv> {
        let get = |name: &str| var(name).with_context(|| format!("{name} is not set"));
        let number = |name: &str| -> Result<u32> {
            get(name)?
                .parse()
                .ok()
                .filter(|n| *n > 0)
                .with_context(|| format!("{name} is not a positive number"))
        };
        let variant = get(ENV_DISK_VARIANT)?;
        if variant != "copy" {
            bail!("{ENV_DISK_VARIANT} is `{variant}`; only `copy` is built");
        }
        let tap_mode = get(ENV_TAP_MODE)?;
        if tap_mode != "multus" && tap_mode != "init" {
            bail!("{ENV_TAP_MODE} is `{tap_mode}`, not `multus` or `init`");
        }
        Ok(PodEnv {
            pod_ip: get(ENV_POD_IP)?
                .parse()
                .with_context(|| format!("{ENV_POD_IP} is not an IPv4 address"))?,
            tap_mode,
            network: tap_network(&get(ENV_TAP_CIDR)?)?,
            bridge_port: u16::try_from(number(ENV_BRIDGE_PORT)?)
                .with_context(|| format!("{ENV_BRIDGE_PORT} is not a port"))?,
            vcpus: number(ENV_VCPUS)?,
            memory_mib: number(ENV_MEMORY_MIB)?,
            disk_mib: number(ENV_DISK_MIB)?,
        })
    }

    /// The pod's side of the tap: the /30's first address.
    pub fn pod_side(&self) -> Ipv4Addr {
        Ipv4Addr::from(u32::from(self.network) + 1)
    }

    /// The guest's address: the /30's second.
    pub fn guest(&self) -> Ipv4Addr {
        Ipv4Addr::from(u32::from(self.network) + 2)
    }
}

/// The network of a `/30` (its first address, aligned).
pub fn tap_network(cidr: &str) -> Result<Ipv4Addr> {
    let network = cidr
        .split_once('/')
        .filter(|(_, prefix)| *prefix == "30")
        .and_then(|(address, _)| address.parse::<Ipv4Addr>().ok())
        .filter(|a| u32::from(*a) & 3 == 0);
    network.with_context(|| format!("{ENV_TAP_CIDR} `{cidr}` is not an aligned IPv4 /30"))
}

// ----- the pod's checks -------------------------------------------------------------------

/// The pod has a user namespace of its own: its uid map is not the identity of all uids.
pub fn check_user_namespace(root: &Path) -> Result<()> {
    let path = root.join("proc/self/uid_map");
    let map = std::fs::read_to_string(&path)
        .with_context(|| format!("cannot read {}", path.display()))?;
    let identity = map
        .lines()
        .any(|line| line.split_whitespace().collect::<Vec<_>>() == ["0", "0", "4294967295"]);
    if identity || map.trim().is_empty() {
        bail!(
            "the pod runs in the worker's user namespace: its specification must set hostUsers: false"
        );
    }
    Ok(())
}

/// The main program holds no effective capability (`CapEff` 0).
pub fn check_no_capabilities(root: &Path) -> Result<()> {
    let path = root.join("proc/self/status");
    let status = std::fs::read_to_string(&path)
        .with_context(|| format!("cannot read {}", path.display()))?;
    let effective = status
        .lines()
        .find_map(|line| line.strip_prefix("CapEff:"))
        .map(str::trim)
        .context("the process's status has no CapEff")?;
    if u64::from_str_radix(effective, 16).ok() != Some(0) {
        bail!(
            "vm-pod runs with capabilities ({effective}); its container must drop them all, and only the init variant's init container may hold NET_ADMIN"
        );
    }
    Ok(())
}

/// `/dev/kvm` and `/dev/net/tun` open for reading and writing.
pub fn check_devices(root: &Path) -> Result<()> {
    for (path, resource) in [("dev/kvm", "kvm"), ("dev/net/tun", "tun")] {
        let full = root.join(path);
        if let Err(e) = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&full)
        {
            bail!("{}", device_problem(&format!("/{path}"), resource, &e));
        }
    }
    Ok(())
}

/// Why a device does not open, and who can change it.
fn device_problem(path: &str, resource: &str, error: &std::io::Error) -> String {
    match error.kind() {
        std::io::ErrorKind::NotFound => format!(
            "there is no {path}: the pod did not get the device plugin's {resource} resource (its specification requests it, and the worker's device plugin must offer it)"
        ),
        std::io::ErrorKind::PermissionDenied => format!(
            "{path} is refused to the pod's user ({error}): the cluster's admin sets mode 0666 on the workers' {path} (a udev rule; ADR-0115)"
        ),
        _ => format!("cannot open {path}: {error}"),
    }
}

/// The tap is there, with the pod's side of the /30, and forwards nothing that arrives on it.
pub fn check_tap(root: &Path, env: &PodEnv) -> Result<()> {
    let devices =
        std::fs::read_to_string(root.join("proc/net/dev")).context("cannot read /proc/net/dev")?;
    let there = devices
        .lines()
        .any(|line| line.trim_start().starts_with(&format!("{TAP_DEVICE}:")));
    if !there {
        bail!("{}", tap_missing(&env.tap_mode));
    }
    let trie = std::fs::read_to_string(root.join("proc/net/fib_trie"))
        .context("cannot read /proc/net/fib_trie")?;
    let address = env.pod_side();
    let lines: Vec<&str> = trie.lines().collect();
    let local = lines.windows(2).any(|pair| {
        pair[0].trim() == format!("|-- {address}") && pair[1].contains("/32 host LOCAL")
    });
    if !local {
        bail!(
            "the pod has no address {address}, the tap's side of {}/30: {}",
            env.network,
            tap_missing(&env.tap_mode)
        );
    }
    let who = if env.tap_mode == "init" {
        "the init container (`vm-pod tap-setup`) sets net.ipv4.conf.tap0.forwarding to 0"
    } else {
        "the network attachment must set net.ipv4.conf.tap0.forwarding to 0 (the CNI `tuning` plugin's sysctl, after the `tap` plugin)"
    };
    let v4 = root.join(format!("proc/sys/net/ipv4/conf/{TAP_DEVICE}/forwarding"));
    let v4 =
        std::fs::read_to_string(&v4).with_context(|| format!("cannot read {}", v4.display()))?;
    if v4.trim() != "0" {
        bail!(
            "{TAP_DEVICE} would forward the guest's packets out of the pod (net.ipv4.conf.{TAP_DEVICE}.forwarding is {}): {who}",
            v4.trim()
        );
    }
    let all = ipv6_setting(root, "all/forwarding")?;
    let forced = ipv6_setting(root, &format!("{TAP_DEVICE}/force_forwarding"))?;
    if let Some(setting) = ipv6_forwards(all.as_deref(), forced.as_deref()) {
        let who = if env.tap_mode == "init" {
            "the pod cannot change it, and its init container has no means to: the workers or the cluster's network plugin must leave it 0 in a pod's network namespace"
        } else {
            "the network attachment must set it to 0 (the CNI `tuning` plugin's sysctl, after the `tap` plugin)"
        };
        bail!(
            "{TAP_DEVICE} would forward the guest's IPv6 packets out of the pod ({setting}): {who}"
        );
    }
    Ok(())
}

/// A file of the pod's `/proc/sys/net/ipv6/conf`, trimmed; `None` when there is no such file.
fn ipv6_setting(root: &Path, name: &str) -> Result<Option<String>> {
    let path = root.join("proc/sys/net/ipv6/conf").join(name);
    match std::fs::read_to_string(&path) {
        Ok(text) => Ok(Some(text.trim().to_string())),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e).with_context(|| format!("cannot read {}", path.display())),
    }
}

/// The setting by which the kernel would forward an IPv6 packet that arrives on the tap, as
/// `name is value`, from what `all/forwarding` and the tap's `force_forwarding` read (`None`:
/// no such file); `None` when it forwards none.
///
/// Linux forwards IPv6 by `net.ipv6.conf.all.forwarding` alone. An interface's own `forwarding`
/// only makes it behave as a host or as a router there and turns no forwarding off, unlike
/// IPv4's, which the kernel reads for the interface a packet arrives on. A kernel that has
/// `force_forwarding` also forwards what arrives on an interface where it is on, whatever
/// `all` reads. Without `all/forwarding` the pod has no IPv6.
fn ipv6_forwards(all: Option<&str>, forced: Option<&str>) -> Option<String> {
    let all = all?;
    if all != "0" {
        return Some(format!("net.ipv6.conf.all.forwarding is {all}"));
    }
    forced
        .filter(|forced| *forced != "0")
        .map(|forced| format!("net.ipv6.conf.{TAP_DEVICE}.force_forwarding is {forced}"))
}

fn tap_missing(mode: &str) -> String {
    if mode == "init" {
        format!("the init container `{INIT_CONTAINER}` makes {TAP_DEVICE} with that address")
    } else {
        format!(
            "the network attachment (Multus, the CNI `tap` plugin with static IPAM) gives the pod {TAP_DEVICE} with that address"
        )
    }
}

// ----- the ticket ---------------------------------------------------------------------------

/// A user-data disk: [`DISK_MAGIC`], the length as 8 bytes little-endian, the bytes, and zeros
/// to a multiple of [`DISK_BLOCK`] (crate::vm_boot reads it in the guest).
pub fn user_data_disk(bytes: &[u8]) -> Result<Vec<u8>> {
    if bytes.is_empty() || bytes.len() > super::spec::MAX_USER_DATA {
        bail!(
            "the ticket holds {} bytes, not 1 to {}",
            bytes.len(),
            super::spec::MAX_USER_DATA
        );
    }
    let mut disk = Vec::with_capacity((16 + bytes.len()).div_ceil(DISK_BLOCK) * DISK_BLOCK);
    disk.extend_from_slice(DISK_MAGIC);
    disk.extend_from_slice(&(bytes.len() as u64).to_le_bytes());
    disk.extend_from_slice(bytes);
    disk.resize((16 + bytes.len()).div_ceil(DISK_BLOCK) * DISK_BLOCK, 0);
    Ok(disk)
}

/// Reads the ticket's file and writes the user-data disk to the memory volume.
pub fn write_ticket(root: &Path) -> Result<()> {
    use std::io::{Read, Write};
    use std::os::unix::fs::OpenOptionsExt;
    let path = at(root, &format!("{TICKET_DIR}/{TICKET_KEY}"));
    let mut bytes = Vec::new();
    std::fs::File::open(&path)
        .and_then(|f| {
            f.take(super::spec::MAX_USER_DATA as u64 + 1)
                .read_to_end(&mut bytes)
        })
        .with_context(|| format!("cannot read the ticket {}", path.display()))?;
    let disk = user_data_disk(&bytes)?;
    let out = at(root, USER_DATA_DISK);
    let _ = std::fs::remove_file(&out);
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&out)
        .and_then(|mut f| f.write_all(&disk))
        .with_context(|| format!("cannot write the user-data disk {}", out.display()))
}

// ----- the root disk -------------------------------------------------------------------------

/// Copies `from` to `to`, leaving holes where `from` has a part of zeros, and makes `to`
/// `size` bytes long. The image must not be larger.
pub fn copy_sparse(from: &Path, to: &Path, size: u64) -> Result<()> {
    use std::io::{Read, Seek, SeekFrom, Write};
    let mut source = std::fs::File::open(from)
        .with_context(|| format!("cannot read the VM image {}", from.display()))?;
    let length = source
        .metadata()
        .with_context(|| format!("cannot read the VM image {}", from.display()))?
        .len();
    if length > size {
        bail!(
            "the VM image {} is {} MiB, larger than the template's disk of {} MiB",
            from.display(),
            length >> 20,
            size >> 20
        );
    }
    let fail = |e: std::io::Error| anyhow::anyhow!("cannot write the disk {}: {e}", to.display());
    let mut target = std::fs::File::create(to).map_err(fail)?;
    let mut buffer = vec![0u8; COPY_CHUNK];
    loop {
        let mut filled = 0;
        while filled < buffer.len() {
            let n = source
                .read(&mut buffer[filled..])
                .with_context(|| format!("cannot read the VM image {}", from.display()))?;
            if n == 0 {
                break;
            }
            filled += n;
        }
        if filled == 0 {
            break;
        }
        if buffer[..filled].iter().all(|b| *b == 0) {
            target
                .seek(SeekFrom::Current(filled as i64))
                .map_err(fail)?;
        } else {
            target.write_all(&buffer[..filled]).map_err(fail)?;
        }
    }
    target.set_len(size).map_err(fail)?;
    target.sync_all().map_err(fail)
}

// ----- Firecracker ---------------------------------------------------------------------------

/// The guest's MAC address, from its IPv4 address (as on a node).
pub fn guest_mac(guest: Ipv4Addr) -> String {
    let o = guest.octets();
    format!("06:00:{:02x}:{:02x}:{:02x}:{:02x}", o[0], o[1], o[2], o[3])
}

/// The word on the guest kernel's command line that has the guest grow its root file system
/// to its disk at boot (`runner/images/vm/tinfirm-vm-grow-root.service`): the pod's image
/// holds the VM image shrunk to what it uses, and the pod grows its copy to the template's
/// disk. A sandbox node's VM does not carry the word, and its guest grows nothing.
pub const GROW_ROOT_CMDLINE: &str = "tinfirm.grow-root";

/// The guest kernel's command line: its address on its own `ip=`, the pod's side as gateway,
/// no serial console, the user-data disk as its second drive, and the word that has the guest
/// grow its root file system ([`GROW_ROOT_CMDLINE`]).
pub fn boot_args(env: &PodEnv) -> String {
    format!(
        "8250.nr_uarts=0 quiet reboot=k panic=1 pci=off ip={}::{}:255.255.255.252::eth0:off {}disk:/dev/vdb {GROW_ROOT_CMDLINE}",
        env.guest(),
        env.pod_side(),
        crate::vm_boot::CMDLINE_KEY
    )
}

/// Firecracker's configuration: the kernel, the root disk and the user-data disk, the template's
/// size, and the tap.
pub fn firecracker_config(env: &PodEnv) -> Value {
    json!({
        "boot-source": {"kernel_image_path": KERNEL, "boot_args": boot_args(env)},
        "drives": [
            {"drive_id": "root", "path_on_host": DISK, "is_root_device": true, "is_read_only": false},
            {"drive_id": "userdata", "path_on_host": USER_DATA_DISK, "is_root_device": false, "is_read_only": true},
        ],
        "machine-config": {"vcpu_count": env.vcpus, "mem_size_mib": env.memory_mib, "smt": false},
        "network-interfaces": [
            {"iface_id": "eth0", "host_dev_name": TAP_DEVICE, "guest_mac": guest_mac(env.guest())},
        ],
    })
}

// ----- the relay and readiness ------------------------------------------------------------

/// Relays each connection to `listener` to `guest`, bytes only. A peer from the tap's /30 (the
/// guest itself) is closed at once.
pub async fn relay(listener: TcpListener, guest: SocketAddr, network: Ipv4Addr) {
    loop {
        let Ok((mut client, peer)) = listener.accept().await else {
            continue;
        };
        if in_tap(peer.ip(), network) {
            eprintln!("vm-pod: refused a connection from {peer}, in the tap's network");
            continue;
        }
        tokio::spawn(async move {
            if let Ok(Ok(mut upstream)) =
                tokio::time::timeout(READY_DIAL * 5, TcpStream::connect(guest)).await
            {
                let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
            }
        });
    }
}

fn in_tap(peer: IpAddr, network: Ipv4Addr) -> bool {
    let peer = match peer {
        IpAddr::V4(v4) => v4,
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => v4,
            None => return false,
        },
    };
    u32::from(peer) & !3 == u32::from(network)
}

/// Writes `ready` once the guest's bridge accepts a TCP connection.
async fn mark_ready(guest: SocketAddr, ready: PathBuf) {
    loop {
        if let Ok(Ok(_)) = tokio::time::timeout(READY_DIAL, TcpStream::connect(guest)).await {
            if let Err(e) = std::fs::write(&ready, b"ready\n") {
                eprintln!("vm-pod: cannot write {}: {e}", ready.display());
            }
            return;
        }
        tokio::time::sleep(READY_POLL).await;
    }
}

// ----- supervision -------------------------------------------------------------------------

/// How a run of `vm-pod` goes: under `root`, with this `firecracker`.
pub struct RunOptions {
    pub root: PathBuf,
    pub firecracker: PathBuf,
    pub env: PodEnv,
}

/// How the VM ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ended {
    pub reason: String,
    pub code: i32,
}

/// Runs the VM until Firecracker ends or `stop` resolves (then Firecracker gets SIGTERM).
pub async fn run_with(options: &RunOptions, stop: impl Future<Output = ()>) -> Result<Ended> {
    let (root, env) = (&options.root, &options.env);
    check_user_namespace(root)?;
    check_no_capabilities(root)?;
    check_devices(root)?;
    check_tap(root, env)?;
    write_ticket(root)?;
    copy_sparse(
        &at(root, IMAGE),
        &at(root, DISK),
        u64::from(env.disk_mib) << 20,
    )?;
    let config = at(root, CONFIG_FILE);
    std::fs::write(&config, firecracker_config(env).to_string())
        .with_context(|| format!("cannot write {}", config.display()))?;
    let socket = at(root, API_SOCKET);
    let _ = std::fs::remove_file(&socket);
    let _ = std::fs::remove_file(at(root, READY_FILE));
    let listener = TcpListener::bind((env.pod_ip, env.bridge_port))
        .await
        .with_context(|| format!("cannot listen on {}:{}", env.pod_ip, env.bridge_port))?;
    let mut child = tokio::process::Command::new(&options.firecracker)
        .arg("--api-sock")
        .arg(&socket)
        .arg("--config-file")
        .arg(&config)
        .stdin(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .with_context(|| format!("cannot start {}", options.firecracker.display()))?;
    let guest = SocketAddr::from((env.guest(), env.bridge_port));
    let relay = tokio::spawn(relay(listener, guest, env.network));
    let ready = tokio::spawn(mark_ready(guest, at(root, READY_FILE)));
    let ended = tokio::select! {
        status = child.wait() => {
            let status = status.context("cannot wait for firecracker")?;
            Ended {
                reason: format!("the VM ended: firecracker exited with {status}"),
                code: if status.success() { 0 } else { 1 },
            }
        }
        () = stop => {
            if let Some(pid) = child.id().and_then(|id| rustix::process::Pid::from_raw(id as i32)) {
                let _ = rustix::process::kill_process(pid, rustix::process::Signal::TERM);
            }
            if tokio::time::timeout(STOP_GRACE, child.wait()).await.is_err() {
                let _ = child.kill().await;
            }
            Ended { reason: "the pod was stopped: firecracker ended on SIGTERM".into(), code: 0 }
        }
    };
    relay.abort();
    ready.abort();
    Ok(ended)
}

/// Writes `reason` to the termination log, cut to what the kubelet keeps.
fn terminate(root: &Path, reason: &str) {
    let text = crate::util::one_line(reason, MAX_TERMINATION);
    eprintln!("vm-pod: {text}");
    let _ = std::fs::write(at(root, TERMINATION_LOG), text);
}

/// `vm-pod`: the main container's program; its exit code.
pub async fn run() -> i32 {
    let root = PathBuf::from("/");
    let env = match PodEnv::from_vars(&|name| std::env::var(name).ok()) {
        Ok(env) => env,
        Err(e) => {
            terminate(&root, &format!("{e:#}"));
            return 1;
        }
    };
    let options = RunOptions {
        root: root.clone(),
        firecracker: PathBuf::from(FIRECRACKER),
        env,
    };
    let stop = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(_) => std::future::pending().await,
        }
    };
    match run_with(&options, stop).await {
        Ok(ended) => {
            terminate(&root, &ended.reason);
            ended.code
        }
        Err(e) => {
            terminate(&root, &format!("{e:#}"));
            1
        }
    }
}

/// `vm-pod ready`: the readiness probe; 0 once the guest's bridge has accepted.
pub fn ready() -> i32 {
    ready_at(Path::new("/"))
}

fn ready_at(root: &Path) -> i32 {
    if at(root, READY_FILE).exists() { 0 } else { 1 }
}

/// `vm-pod tap-setup`: the init variant's init container; its exit code.
pub fn tap_setup() -> i32 {
    let root = Path::new("/");
    let result = std::env::var(ENV_TAP_CIDR)
        .context("TINFIRM_TAP_CIDR is not set")
        .and_then(|cidr| tap_network(&cidr))
        .and_then(|network| {
            super::tap::set_up(
                TAP_DEVICE,
                UID as u32,
                GID as u32,
                Ipv4Addr::from(u32::from(network) + 1),
            )
        });
    match result {
        Ok(()) => 0,
        Err(e) => {
            terminate(root, &format!("{e:#}"));
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn vars(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let map: std::collections::BTreeMap<String, String> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        move |name| map.get(name).cloned()
    }

    fn env_pairs() -> Vec<(&'static str, &'static str)> {
        vec![
            (ENV_POD_IP, "10.42.1.7"),
            (ENV_TAP_MODE, "init"),
            (ENV_TAP_CIDR, "172.30.0.0/30"),
            (ENV_BRIDGE_PORT, "7443"),
            (ENV_VCPUS, "4"),
            (ENV_MEMORY_MIB, "8192"),
            (ENV_DISK_MIB, "32768"),
            (ENV_DISK_VARIANT, "copy"),
        ]
    }

    fn env() -> PodEnv {
        PodEnv::from_vars(&vars(&env_pairs())).unwrap()
    }

    #[test]
    fn the_settings_come_from_the_environment() {
        let env = env();
        assert_eq!(env.pod_side(), Ipv4Addr::new(172, 30, 0, 1));
        assert_eq!(env.guest(), Ipv4Addr::new(172, 30, 0, 2));
        for (name, value, word) in [
            (ENV_TAP_CIDR, "172.30.0.1/30", "aligned IPv4 /30"),
            (ENV_TAP_CIDR, "172.30.0.0/29", "aligned IPv4 /30"),
            (ENV_DISK_VARIANT, "clone", "only `copy` is built"),
            (ENV_TAP_MODE, "bridge", "not `multus` or `init`"),
            (ENV_BRIDGE_PORT, "70000", "not a port"),
            (ENV_VCPUS, "0", "positive"),
            (ENV_POD_IP, "fd00::1", "IPv4"),
        ] {
            let mut pairs = env_pairs();
            pairs.retain(|(n, _)| *n != name);
            pairs.push((name, value));
            let error = format!("{:#}", PodEnv::from_vars(&vars(&pairs)).unwrap_err());
            assert!(error.contains(word), "{name}={value}: {error}");
        }
        let error = format!("{:#}", PodEnv::from_vars(&vars(&[])).unwrap_err());
        assert!(error.contains("is not set"), "{error}");
    }

    #[test]
    fn firecrackers_configuration_and_the_guests_command_line() {
        assert_eq!(
            boot_args(&env()),
            "8250.nr_uarts=0 quiet reboot=k panic=1 pci=off ip=172.30.0.2::172.30.0.1:255.255.255.252::eth0:off tinfirm.user-data=disk:/dev/vdb tinfirm.grow-root"
        );
        assert_eq!(
            crate::vm_boot::source_from_cmdline(&boot_args(&env())).as_deref(),
            Some("disk:/dev/vdb")
        );
        assert_eq!(
            firecracker_config(&env()),
            json!({
                "boot-source": {"kernel_image_path": "/tinfirm/vm/vmlinux", "boot_args": boot_args(&env())},
                "drives": [
                    {"drive_id": "root", "path_on_host": "/tinfirm/disk/rootfs.ext4", "is_root_device": true, "is_read_only": false},
                    {"drive_id": "userdata", "path_on_host": "/tinfirm/run/userdata.disk", "is_root_device": false, "is_read_only": true},
                ],
                "machine-config": {"vcpu_count": 4, "mem_size_mib": 8192, "smt": false},
                "network-interfaces": [
                    {"iface_id": "eth0", "host_dev_name": "tap0", "guest_mac": "06:00:ac:1e:00:02"},
                ],
            })
        );
    }

    #[test]
    fn the_user_data_disk_is_what_the_guest_reads() {
        let user_data = r#"{"tinfirm":1}"#;
        let disk = user_data_disk(user_data.as_bytes()).unwrap();
        assert_eq!(disk.len(), DISK_BLOCK);
        assert_eq!(
            &disk[..29],
            b"TFUDATA1\x0d\x00\x00\x00\x00\x00\x00\x00{\"tinfirm\":1}"
        );
        assert_eq!(crate::vm_boot::read_disk_bytes(&disk).unwrap(), user_data);
        let big = vec![b'x'; super::super::spec::MAX_USER_DATA];
        let disk = user_data_disk(&big).unwrap();
        assert!(disk.len() <= crate::vm_boot::max_disk_len());
        assert_eq!(
            crate::vm_boot::read_disk_bytes(&disk).unwrap().len(),
            big.len()
        );
        assert!(user_data_disk(b"").is_err());
        let error = format!(
            "{:#}",
            user_data_disk(&vec![b'x'; big.len() + 1]).unwrap_err()
        );
        assert!(!error.contains("xxxx"), "{error}");
    }

    /// A pod's tree as `vm-pod` sees it, sound in every check.
    fn pod_tree(env: &PodEnv) -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let write = |path: &str, text: &str| {
            let full = root.join(path);
            std::fs::create_dir_all(full.parent().unwrap()).unwrap();
            std::fs::write(full, text).unwrap();
        };
        write("proc/self/uid_map", "         0 1729953792      65536\n");
        write(
            "proc/self/status",
            "Name:\tvm-pod\nCapInh:\t0000000000000000\nCapEff:\t0000000000000000\n",
        );
        write("dev/kvm", "");
        write("dev/net/tun", "");
        write(
            "proc/net/dev",
            "Inter-|   Receive\n face |bytes\n    lo: 0 0\n  eth0: 0 0\n  tap0: 0 0\n",
        );
        write(
            "proc/net/fib_trie",
            &format!(
                "Local:\n  +-- 0.0.0.0/0 3 0 5\n     |-- {}\n        /32 host LOCAL\n     |-- 10.42.1.7\n        /32 host LOCAL\n",
                env.pod_side()
            ),
        );
        write("proc/sys/net/ipv4/conf/tap0/forwarding", "0\n");
        write("proc/sys/net/ipv6/conf/all/forwarding", "0\n");
        write("proc/sys/net/ipv6/conf/tap0/forwarding", "0\n");
        write(
            "tinfirm/ticket/user-data",
            r#"{"tinfirm":1,"ticket":"TICKET-CANARY-91c2"}"#,
        );
        write("tinfirm/run/.keep", "");
        write("tinfirm/disk/.keep", "");
        write("dev/termination-log", "");
        let mut image = vec![0u8; 3 << 20];
        image[..4].copy_from_slice(b"EXT4");
        image[(2 << 20) + 7] = 1;
        std::fs::create_dir_all(root.join("tinfirm/vm")).unwrap();
        std::fs::write(root.join("tinfirm/vm/rootfs.ext4"), image).unwrap();
        dir
    }

    #[test]
    fn each_check_of_the_pod_says_what_is_wrong_and_who_fixes_it() {
        let env = env();
        let dir = pod_tree(&env);
        let root = dir.path();
        check_user_namespace(root).unwrap();
        check_no_capabilities(root).unwrap();
        check_devices(root).unwrap();
        check_tap(root, &env).unwrap();
        let cases: Vec<(&str, &str, &str)> = vec![
            (
                "proc/self/uid_map",
                "         0          0 4294967295\n",
                "hostUsers: false",
            ),
            (
                "proc/self/status",
                "CapEff:\t0000000000001000\n",
                "must drop them all",
            ),
            (
                "proc/net/dev",
                "    lo: 0 0\n",
                "the init container `tap` makes tap0",
            ),
            (
                "proc/net/fib_trie",
                "Local:\n",
                "the pod has no address 172.30.0.1",
            ),
            (
                "proc/sys/net/ipv4/conf/tap0/forwarding",
                "1\n",
                "`vm-pod tap-setup`) sets net.ipv4.conf.tap0.forwarding to 0",
            ),
            // The tap's own IPv6 `forwarding` reads 0 in both: the kernel does not forward by it.
            (
                "proc/sys/net/ipv6/conf/all/forwarding",
                "1\n",
                "(net.ipv6.conf.all.forwarding is 1): the pod cannot change it",
            ),
            (
                "proc/sys/net/ipv6/conf/tap0/force_forwarding",
                "1\n",
                "(net.ipv6.conf.tap0.force_forwarding is 1)",
            ),
        ];
        for (path, text, word) in cases {
            let dir = pod_tree(&env);
            let root = dir.path();
            std::fs::write(root.join(path), text).unwrap();
            let error = [
                check_user_namespace(root),
                check_no_capabilities(root),
                check_devices(root),
                check_tap(root, &env),
            ]
            .into_iter()
            .find_map(Result::err)
            .map(|e| format!("{e:#}"))
            .unwrap_or_default();
            assert!(error.contains(word), "{path}: {error}");
        }
        // The Multus variant names the network attachment.
        let multus = PodEnv {
            tap_mode: "multus".into(),
            ..env.clone()
        };
        let dir = pod_tree(&multus);
        std::fs::write(
            dir.path().join("proc/sys/net/ipv4/conf/tap0/forwarding"),
            "1",
        )
        .unwrap();
        let error = format!("{:#}", check_tap(dir.path(), &multus).unwrap_err());
        assert!(error.contains("the network attachment must set"), "{error}");
        let dir = pod_tree(&multus);
        std::fs::write(
            dir.path().join("proc/sys/net/ipv6/conf/all/forwarding"),
            "1",
        )
        .unwrap();
        let error = format!("{:#}", check_tap(dir.path(), &multus).unwrap_err());
        assert!(
            error.contains(
                "(net.ipv6.conf.all.forwarding is 1): the network attachment must set it to 0"
            ),
            "{error}"
        );
        // The tap's own IPv6 `forwarding` turns nothing on, and a `force_forwarding` that reads 0
        // nothing either.
        let dir = pod_tree(&env);
        let conf = dir.path().join("proc/sys/net/ipv6/conf");
        std::fs::write(conf.join("tap0/forwarding"), "1\n").unwrap();
        std::fs::write(conf.join("tap0/force_forwarding"), "0\n").unwrap();
        check_tap(dir.path(), &env).unwrap();
        // No IPv6 at all is fine.
        std::fs::remove_dir_all(dir.path().join("proc/sys/net/ipv6")).unwrap();
        check_tap(dir.path(), &env).unwrap();
        // A device that is missing, or refused.
        std::fs::remove_file(dir.path().join("dev/kvm")).unwrap();
        let error = format!("{:#}", check_devices(dir.path()).unwrap_err());
        assert!(
            error.contains("did not get the device plugin's kvm resource"),
            "{error}"
        );
        let refused = device_problem(
            "/dev/kvm",
            "kvm",
            &std::io::Error::from_raw_os_error(libc::EACCES),
        );
        assert!(
            refused.contains("the cluster's admin sets mode 0666 on the workers' /dev/kvm"),
            "{refused}"
        );
    }

    #[test]
    fn ipv6_is_forwarded_by_the_namespaces_setting_not_the_taps() {
        let all = "net.ipv6.conf.all.forwarding";
        let forced = "net.ipv6.conf.tap0.force_forwarding";
        for (all_reads, forced_reads, expected) in [
            // No IPv6 in the pod.
            (None, None, None),
            (None, Some("1"), None),
            // Off, on a kernel without `force_forwarding` and on one with it.
            (Some("0"), None, None),
            (Some("0"), Some("0"), None),
            // On for the whole namespace, whatever the tap's own settings read.
            (Some("1"), None, Some(format!("{all} is 1"))),
            (Some("1"), Some("0"), Some(format!("{all} is 1"))),
            // On for the tap alone.
            (Some("0"), Some("1"), Some(format!("{forced} is 1"))),
            // What is not 0 is not off.
            (Some(""), None, Some(format!("{all} is "))),
            (Some("2"), Some("0"), Some(format!("{all} is 2"))),
        ] {
            assert_eq!(
                ipv6_forwards(all_reads, forced_reads),
                expected,
                "{all_reads:?} {forced_reads:?}"
            );
        }
        // A file that is there and cannot be read is an error, not "no IPv6".
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(ipv6_setting(dir.path(), "all/forwarding").unwrap(), None);
        std::fs::create_dir_all(dir.path().join("proc/sys/net/ipv6/conf/all/forwarding")).unwrap();
        let error = format!(
            "{:#}",
            ipv6_setting(dir.path(), "all/forwarding").unwrap_err()
        );
        assert!(error.contains("cannot read"), "{error}");
    }

    #[test]
    fn the_disk_is_a_sparse_copy_grown_to_the_templates_size() {
        use std::os::unix::fs::MetadataExt;
        let dir = pod_tree(&env());
        let (from, to) = (
            dir.path().join("tinfirm/vm/rootfs.ext4"),
            dir.path().join("tinfirm/disk/rootfs.ext4"),
        );
        copy_sparse(&from, &to, 64 << 20).unwrap();
        let (image, disk) = (std::fs::read(&from).unwrap(), std::fs::read(&to).unwrap());
        assert_eq!(disk.len(), 64 << 20);
        assert_eq!(&disk[..image.len()], &image[..]);
        assert!(disk[image.len()..].iter().all(|b| *b == 0));
        let blocks = std::fs::metadata(&to).unwrap().blocks() * 512;
        assert!(
            blocks < 4 << 20,
            "the copy holds {blocks} bytes, not sparse"
        );
        let error = format!("{:#}", copy_sparse(&from, &to, 1 << 20).unwrap_err());
        assert!(error.contains("larger than the template's disk"), "{error}");
        let error = format!(
            "{:#}",
            copy_sparse(&dir.path().join("none"), &to, 1 << 20).unwrap_err()
        );
        assert!(
            error.contains("cannot read the VM image") && error.contains("none"),
            "{error}"
        );
        let error = format!(
            "{:#}",
            copy_sparse(&from, &dir.path().join("no-dir/disk"), 64 << 20).unwrap_err()
        );
        assert!(
            error.contains("cannot write the disk") && error.contains("no-dir"),
            "{error}"
        );
    }

    #[tokio::test]
    async fn the_relay_copies_bytes_and_refuses_the_taps_network() {
        // The guest's bridge: echoes one line, then closes.
        let guest = TcpListener::bind("127.0.0.2:0").await.unwrap();
        let guest_addr = guest.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut socket, _)) = guest.accept().await {
                let mut buffer = [0u8; 5];
                if socket.read_exact(&mut buffer).await.is_ok() {
                    let _ = socket.write_all(&buffer).await;
                }
            }
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(relay(listener, guest_addr, Ipv4Addr::new(10, 0, 0, 0)));
        let mut client = TcpStream::connect(addr).await.unwrap();
        client.write_all(b"hello").await.unwrap();
        let mut answer = [0u8; 5];
        client.read_exact(&mut answer).await.unwrap();
        assert_eq!(&answer, b"hello");
        // The guest closed its side: so does the relay.
        let mut rest = Vec::new();
        client.read_to_end(&mut rest).await.unwrap();
        assert!(rest.is_empty());

        // A peer from the tap's network (here 127.0.0.0/30, where the client is).
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(relay(listener, guest_addr, Ipv4Addr::new(127, 0, 0, 0)));
        let mut client = TcpStream::connect(addr).await.unwrap();
        let _ = client.write_all(b"hello").await;
        let mut rest = Vec::new();
        let read =
            tokio::time::timeout(Duration::from_secs(5), client.read_to_end(&mut rest)).await;
        assert!(read.is_ok() && rest.is_empty(), "closed without an answer");
        assert!(in_tap(
            "172.30.0.2".parse().unwrap(),
            Ipv4Addr::new(172, 30, 0, 0)
        ));
        assert!(!in_tap(
            "172.30.0.4".parse().unwrap(),
            Ipv4Addr::new(172, 30, 0, 0)
        ));
    }

    /// A stand-in for Firecracker: a script that notes its arguments and then does `body`.
    fn firecracker(dir: &Path, body: &str) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;
        let path = dir.join("firecracker");
        std::fs::write(
            &path,
            format!(
                "#!/bin/sh\necho \"$@\" > \"{}/args\"\n{body}\n",
                dir.display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        path
    }

    /// Options for a run on loopback: the pod at 127.0.0.1, the tap's /30 127.0.0.4, the guest
    /// 127.0.0.6.
    async fn run_options(body: &str) -> (tempfile::TempDir, tempfile::TempDir, RunOptions) {
        let port = TcpListener::bind("127.0.0.1:0")
            .await
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let env = PodEnv {
            pod_ip: Ipv4Addr::LOCALHOST,
            tap_mode: "init".into(),
            network: Ipv4Addr::new(127, 0, 0, 4),
            bridge_port: port,
            vcpus: 1,
            memory_mib: 256,
            disk_mib: 8,
        };
        let tree = pod_tree(&env);
        let scripts = tempfile::tempdir().unwrap();
        let firecracker = firecracker(scripts.path(), body);
        let options = RunOptions {
            root: tree.path().to_path_buf(),
            firecracker,
            env,
        };
        (tree, scripts, options)
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_vm_runs_until_firecracker_ends_and_says_why() {
        let (tree, scripts, options) = run_options("sleep 1; echo 'no KVM' >&2; exit 3").await;
        // The guest's bridge, standing in on loopback.
        let guest = TcpListener::bind((options.env.guest(), options.env.bridge_port))
            .await
            .unwrap();
        let ended = run_with(&options, std::future::pending()).await.unwrap();
        drop(guest);
        assert_eq!(ended.code, 1);
        assert!(
            ended
                .reason
                .contains("firecracker exited with exit status: 3"),
            "{}",
            ended.reason
        );
        let root = tree.path();
        let args = std::fs::read_to_string(scripts.path().join("args")).unwrap();
        assert_eq!(
            args.trim(),
            format!(
                "--api-sock {} --config-file {}",
                at(root, API_SOCKET).display(),
                at(root, CONFIG_FILE).display()
            )
        );
        let config: Value =
            serde_json::from_slice(&std::fs::read(at(root, CONFIG_FILE)).unwrap()).unwrap();
        assert_eq!(config, firecracker_config(&options.env));
        let disk = crate::vm_boot::read_disk(&at(root, USER_DATA_DISK)).unwrap();
        assert!(disk.contains("TICKET-CANARY"));
        assert_eq!(std::fs::metadata(at(root, DISK)).unwrap().len(), 8 << 20);
        // The guest's bridge accepted: the pod is ready.
        assert_eq!(ready_at(root), 0);
        terminate(root, &ended.reason);
        let log = std::fs::read_to_string(at(root, TERMINATION_LOG)).unwrap();
        assert!(
            log.contains("exit status: 3") && !log.contains("TICKET-CANARY"),
            "{log}"
        );
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_sigterm_ends_firecracker_first() {
        let body = "trap 'echo stopped > \"$(dirname \"$0\")/stopped\"; exit 0' TERM\nwhile :; do sleep 0.1; done";
        let (tree, scripts, options) = run_options(body).await;
        let stop = tokio::time::sleep(Duration::from_millis(500));
        let ended = run_with(&options, stop).await.unwrap();
        assert_eq!(ended.code, 0);
        assert!(ended.reason.contains("SIGTERM"), "{}", ended.reason);
        assert!(
            scripts.path().join("stopped").exists(),
            "firecracker got SIGTERM"
        );
        // The guest never answered: not ready.
        assert_eq!(ready_at(tree.path()), 1);
    }

    #[tokio::test]
    async fn a_failed_check_starts_nothing_and_keeps_the_ticket_out_of_its_error() {
        let (tree, scripts, options) = run_options("exit 0").await;
        std::fs::write(
            tree.path().join("proc/sys/net/ipv4/conf/tap0/forwarding"),
            "1",
        )
        .unwrap();
        let error = format!(
            "{:#}",
            run_with(&options, std::future::pending())
                .await
                .unwrap_err()
        );
        assert!(error.contains("would forward"), "{error}");
        assert!(
            !scripts.path().join("args").exists(),
            "firecracker did not start"
        );
        // A ticket too large: its content is in no error.
        let (tree, _scripts, options) = run_options("exit 0").await;
        std::fs::write(
            tree.path().join("tinfirm/ticket/user-data"),
            "TICKET-CANARY-".repeat(10_000),
        )
        .unwrap();
        let error = format!(
            "{:#}",
            run_with(&options, std::future::pending())
                .await
                .unwrap_err()
        );
        assert!(error.contains("the ticket holds"), "{error}");
        assert!(!error.contains("TICKET-CANARY"), "{error}");
        assert!(!at(tree.path(), USER_DATA_DISK).exists());
    }
}
