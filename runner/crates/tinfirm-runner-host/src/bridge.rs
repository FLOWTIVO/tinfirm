//! The sandbox bridge in the runner host (ADR-0095, `run --sandbox-bridge`): its options, the
//! listener the runs register with, where the host's other listeners go and the tunnels that
//! reach them, which of a run's variables stay in the sandbox's own settings, and the check
//! that the binary mounted into Docker sandboxes runs there.

use std::io::{Read, Seek, SeekFrom};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use tinfirm_bridge::host::{BridgeServer, HostTiming, Registration, TunnelTarget};

use crate::git_proxy::Endpoint;

/// Where the Docker executor mounts the bridge (this host's binary, read-only).
pub const SANDBOX_PATH: &str = "/opt/tinfirm/tinfirm-runner-host";
/// Names another static Linux build of the runner host to mount as the bridge.
pub const BINARY_ENV: &str = "TINFIRM_RUNNER_BRIDGE_BINARY";

#[derive(Debug, Clone)]
pub struct BridgeOptions {
    /// The executable that runs `bridge` in the sandbox: for Docker, mounted at
    /// [`SANDBOX_PATH`]; for the local test executor, started as a process.
    pub binary: PathBuf,
    /// How long a started sandbox has for its bridge to connect: the entrypoint, the agent's
    /// own dockerd and a Kata boot come first.
    pub connect_timeout: Duration,
    pub timing: HostTiming,
}

impl BridgeOptions {
    pub fn new(binary: PathBuf) -> BridgeOptions {
        BridgeOptions {
            binary,
            connect_timeout: Duration::from_secs(180),
            timing: HostTiming::default(),
        }
    }

    /// `TINFIRM_RUNNER_BRIDGE_BINARY`, or this process's own executable.
    pub fn binary_from_env() -> Result<PathBuf> {
        match std::env::var_os(BINARY_ENV).filter(|v| !v.is_empty()) {
            Some(path) => Ok(PathBuf::from(path)),
            None => std::env::current_exe().context("cannot find this executable"),
        }
    }
}

/// The host's bridge listener, and how a sandbox reaches it (the git proxy's endpoint).
#[derive(Clone)]
pub struct BridgeListener {
    pub server: BridgeServer,
    pub sandbox_host: String,
    /// Docker `extra_hosts` the sandbox needs to resolve `sandbox_host`.
    pub extra_hosts: Vec<String>,
}

impl BridgeListener {
    /// `HOST:PORT` that a sandbox connects to.
    pub fn sandbox_addr(&self) -> String {
        crate::git_proxy::host_port(&self.sandbox_host, self.server.local_addr().port())
    }
}

/// Where a sandbox reaches the host's listeners with the bridge: its own loopback, on each
/// listener's port, through a tunnel.
pub const SANDBOX_LOOPBACK: Ipv4Addr = Ipv4Addr::LOCALHOST;
/// Where the listeners bind when the sandbox shares this machine's network.
const SHARED_LOOPBACK: Ipv4Addr = Ipv4Addr::new(127, 0, 0, 2);

/// With the bridge, the git proxy and the platform tools, the model proxy and the egress proxy
/// listen on this machine's loopback, which a sandbox reaches only through its own bridge
/// (R28), and their URLs name the sandbox's loopback on the same ports, where the bridge's
/// tunnels listen. Where the sandbox shares this machine's network (the local test executor,
/// `TINFIRM_RUNNER_SANDBOX_NETWORK=host`), they listen on 127.0.0.2, so that the tunnels can
/// take their ports on 127.0.0.1.
pub fn listeners_endpoint(shares_host_network: bool) -> Endpoint {
    let ip = if shares_host_network {
        SHARED_LOOPBACK
    } else {
        Ipv4Addr::LOCALHOST
    };
    Endpoint {
        bind: SocketAddr::new(IpAddr::V4(ip), 0),
        sandbox_host: SANDBOX_LOOPBACK.to_string(),
        extra_hosts: Vec::new(),
    }
}

/// A run's tunnels: one per listener it uses, at the listener's port on `loopback`, the
/// sandbox's ([`SANDBOX_LOOPBACK`], or a local test executor's run's own).
pub fn tunnels(listeners: &[SocketAddr], loopback: IpAddr) -> Vec<TunnelTarget> {
    let mut out: Vec<TunnelTarget> = Vec::new();
    for &target in listeners {
        if out.iter().all(|t| t.target != target) {
            out.push(TunnelTarget {
                listen: SocketAddr::new(loopback, target.port()),
                target,
            });
        }
    }
    out
}

/// The loopback address of a local test executor's sandbox over the bridge, one per run: its
/// "sandbox" shares this machine's network, so that runs' tunnels, which take their listeners'
/// ports, would take each other's on one address (Linux, where all of 127.0.0.0/8 is
/// loopback).
pub fn local_sandbox_loopback() -> Ipv4Addr {
    static NEXT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
    let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let low = 1 + n % 250;
    let mid = 1 + (n / 250) % 250;
    let high = 10 + (n / 62_500) % 240;
    Ipv4Addr::new(127, high as u8, mid as u8, low as u8)
}

/// Variables that stay in the sandbox's own settings with the bridge: the image's entrypoint
/// reads them before the bridge starts (the mirrors, the agent's own Docker, and that
/// Docker's proxies, which hold the egress proxy's credential of the run as they do without
/// the bridge). Every other variable of the run, its credentials among them, travels in
/// `start`.
pub fn stays_in_sandbox_settings(key: &str) -> bool {
    key.starts_with("TINFIRM_SANDBOX_")
        || matches!(
            key,
            "HTTP_PROXY" | "HTTPS_PROXY" | "NO_PROXY" | "http_proxy" | "https_proxy" | "no_proxy"
        )
}

/// A run's environment, as the sandbox spec holds it.
pub type Env = Vec<(String, String)>;

/// Splits a run's environment into the sandbox's settings and `start`'s.
pub fn split_env(env: &[(String, String)]) -> (Env, Env) {
    env.iter()
        .cloned()
        .partition(|(key, _)| stays_in_sandbox_settings(key))
}

/// The bridge's own settings: where the host is, the run, its ticket and the pin.
pub fn bootstrap_env(registration: &Registration, addr: &str) -> Vec<(String, String)> {
    vec![
        (tinfirm_bridge::ENV_ADDR.to_string(), addr.to_string()),
        (
            tinfirm_bridge::ENV_RUN.to_string(),
            registration.run_id().to_string(),
        ),
        (
            tinfirm_bridge::ENV_TICKET.to_string(),
            registration.ticket().to_string(),
        ),
        (
            tinfirm_bridge::ENV_PIN.to_string(),
            registration.pin().to_string(),
        ),
    ]
}

const PT_INTERP: u32 = 3;

/// Checks that `path` is a static Linux executable for `arch` (Docker's `Architecture`:
/// `x86_64` or `aarch64`), so that it runs in every sandbox of that architecture whatever
/// the image's C library is.
pub fn check_static_linux(path: &Path, arch: &str) -> Result<()> {
    let hint = format!(
        "use a release binary of the runner host (static, musl) for {arch}, or set {BINARY_ENV} to one"
    );
    let machine: u16 = match arch {
        "x86_64" | "amd64" => 62,
        "aarch64" | "arm64" => 183,
        other => bail!("the sandbox bridge supports x86_64 and aarch64 sandboxes, not {other}"),
    };
    let mut file = std::fs::File::open(path)
        .with_context(|| format!("cannot open the bridge binary {}", path.display()))?;
    let mut header = [0u8; 64];
    let elf = file.read_exact(&mut header).is_ok()
        && header.starts_with(b"\x7fELF")
        && header[4] == 2 // 64-bit
        && header[5] == 1; // little endian
    if !elf {
        bail!(
            "{} is not a 64-bit Linux executable; {hint}",
            path.display()
        );
    }
    let u16_at = |at: usize| u16::from_le_bytes([header[at], header[at + 1]]);
    let e_type = u16_at(0x10);
    if e_type != 2 && e_type != 3 {
        bail!("{} is not an executable; {hint}", path.display());
    }
    if u16_at(0x12) != machine {
        bail!(
            "{} is built for another architecture than the sandboxes ({arch}); {hint}",
            path.display()
        );
    }
    let phoff = u64::from_le_bytes(header[0x20..0x28].try_into().expect("8 bytes"));
    let phentsize = usize::from(u16_at(0x36));
    let phnum = usize::from(u16_at(0x38));
    if phentsize < 4 || phnum == 0 || phnum > 256 {
        bail!("{} has no valid program headers; {hint}", path.display());
    }
    let mut table = vec![0u8; phentsize * phnum];
    file.seek(SeekFrom::Start(phoff))
        .and_then(|_| file.read_exact(&mut table))
        .with_context(|| format!("cannot read the program headers of {}", path.display()))?;
    let dynamic = table
        .chunks(phentsize)
        .any(|entry| u32::from_le_bytes(entry[0..4].try_into().expect("4 bytes")) == PT_INTERP);
    if dynamic {
        bail!(
            "{} is linked dynamically, so it may not run in the sandbox's image; {hint}",
            path.display()
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_listeners_go_to_loopback_and_the_sandbox_reaches_them_on_the_same_ports() {
        let own = listeners_endpoint(false);
        assert_eq!(own.bind, "127.0.0.1:0".parse().unwrap());
        assert_eq!(own.sandbox_host, "127.0.0.1");
        assert!(own.extra_hosts.is_empty());
        assert_eq!(
            listeners_endpoint(true).bind,
            "127.0.0.2:0".parse().unwrap()
        );
        let git: SocketAddr = "127.0.0.1:40001".parse().unwrap();
        let model: SocketAddr = "127.0.0.1:40002".parse().unwrap();
        let tunnels = tunnels(&[git, model, git], IpAddr::V4(SANDBOX_LOOPBACK));
        assert_eq!(
            tunnels,
            [
                TunnelTarget {
                    listen: "127.0.0.1:40001".parse().unwrap(),
                    target: git
                },
                TunnelTarget {
                    listen: "127.0.0.1:40002".parse().unwrap(),
                    target: model
                },
            ]
        );
    }

    #[test]
    fn credentials_and_the_run_travel_in_start_and_the_entrypoints_settings_stay() {
        let env: Vec<(String, String)> = [
            ("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat-1"),
            ("ANTHROPIC_BASE_URL", "http://172.17.0.1:9/s"),
            ("TINFIRM_RUN_ID", "r"),
            ("GIT_CONFIG_COUNT", "1"),
            ("JAVA_TOOL_OPTIONS", "-Dhttps.proxyHost=172.17.0.1"),
            ("TINFIRM_SANDBOX_DOCKER", "1"),
            ("TINFIRM_SANDBOX_REGISTRY_MIRRORS", "https://mirror"),
            ("HTTPS_PROXY", "http://run:secret@172.17.0.1:8"),
            ("no_proxy", "172.17.0.1"),
        ]
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
        let (settings, start) = split_env(&env);
        let keys =
            |list: &[(String, String)]| list.iter().map(|(k, _)| k.clone()).collect::<Vec<_>>();
        assert_eq!(
            keys(&settings),
            [
                "TINFIRM_SANDBOX_DOCKER",
                "TINFIRM_SANDBOX_REGISTRY_MIRRORS",
                "HTTPS_PROXY",
                "no_proxy"
            ]
        );
        assert_eq!(
            keys(&start),
            [
                "CLAUDE_CODE_OAUTH_TOKEN",
                "ANTHROPIC_BASE_URL",
                "TINFIRM_RUN_ID",
                "GIT_CONFIG_COUNT",
                "JAVA_TOOL_OPTIONS"
            ]
        );
    }

    /// A minimal 64-bit little-endian ELF header with the given type, machine and program
    /// header types.
    fn elf(e_type: u16, machine: u16, segments: &[u32]) -> Vec<u8> {
        let mut bytes = vec![0u8; 64];
        bytes[..4].copy_from_slice(b"\x7fELF");
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[0x10..0x12].copy_from_slice(&e_type.to_le_bytes());
        bytes[0x12..0x14].copy_from_slice(&machine.to_le_bytes());
        bytes[0x20..0x28].copy_from_slice(&64u64.to_le_bytes());
        bytes[0x36..0x38].copy_from_slice(&56u16.to_le_bytes());
        bytes[0x38..0x3a].copy_from_slice(&(segments.len() as u16).to_le_bytes());
        for kind in segments {
            let mut entry = vec![0u8; 56];
            entry[..4].copy_from_slice(&kind.to_le_bytes());
            bytes.extend(entry);
        }
        bytes
    }

    fn check(bytes: &[u8], arch: &str) -> Result<()> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("bin");
        std::fs::write(&path, bytes).unwrap();
        check_static_linux(&path, arch)
    }

    #[test]
    fn only_a_static_linux_executable_of_the_sandboxes_architecture_is_accepted() {
        // PT_LOAD (1) only: static. static-pie (ET_DYN) too.
        check(&elf(2, 62, &[1, 1]), "x86_64").unwrap();
        check(&elf(3, 183, &[1]), "aarch64").unwrap();
        let refused = [
            (elf(2, 62, &[3, 1]), "x86_64", "linked dynamically"),
            (elf(2, 183, &[1]), "x86_64", "another architecture"),
            (elf(1, 62, &[1]), "x86_64", "not an executable"),
            (
                b"\xcf\xfa\xed\xfe macho".to_vec(),
                "x86_64",
                "not a 64-bit Linux executable",
            ),
            (elf(2, 62, &[1]), "s390x", "not s390x"),
        ];
        for (bytes, arch, needle) in refused {
            let error = check(&bytes, arch).unwrap_err().to_string();
            assert!(error.contains(needle), "{error}");
        }
    }
}
