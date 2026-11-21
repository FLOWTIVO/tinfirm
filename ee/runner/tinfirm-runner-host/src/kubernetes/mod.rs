//! The pod-per-run VM driver (ADR-0115 decision 2): one pod per run in a namespace of the
//! runs' own, where Firecracker runs the run's VM. Part of elastic runners, under the Tinfirm
//! Enterprise licence (`ee/LICENSE`); the runner host compiles it in
//! (`tinfirm-runner-host/src/lib.rs`).
//!
//! [`api`] is the client of the Kubernetes API that the driver calls; [`config`], the driver's
//! configuration; [`spec`], the objects the driver creates and checks, as pure functions of the
//! configuration; [`pod_driver`], the driver's methods over them; and [`vm_pod`] with [`tap`],
//! the program of a run's pod.
//!
//! The runner host reaches them as its other drivers: `run --executor vm --vm-driver pod`
//! starts `vm-driver pod --config <file>` ([`pod_driver::run`]), a driver of this binary that is
//! paid (crate::vm::DriverCommand::elastic), and reports its level of isolation from the same
//! file (crate::isolation). A run's pod runs `vm-pod`, `vm-pod ready` and `vm-pod tap-setup`,
//! hidden subcommands of the same binary, as [`spec::vm_pod`] names them.

use std::path::PathBuf;

use tinfirm_bridge::control::ProbeCheck;

pub mod api;
pub mod config;
pub mod pod_driver;
pub mod spec;
pub mod tap;
pub mod vm_pod;

/// `--vm-driver pod`: this binary's driver of a pod per run (`vm-driver pod`).
pub const BUILTIN_POD: &str = pod_driver::DRIVER;
/// Where the driver's configuration is when neither `--config` nor
/// `TINFIRM_POD_DRIVER_CONFIG` names it: where the chart mounts it.
pub const DEFAULT_CONFIG: &str = "/etc/tinfirm/pod-driver/pod-driver.json";
/// The variable that names the driver's configuration, which the chart sets.
pub const CONFIG_ENV: &str = "TINFIRM_POD_DRIVER_CONFIG";
/// Where the driver finds its credential for the API server when neither `--kubernetes-api`
/// nor `TINFIRM_KUBERNETES_API_DIR` names it: the chart's projected volume, which holds the
/// runner pod's ServiceAccount token (`token`) and the cluster's CA (`ca.crt`), and no
/// namespace file. Not the kubelet's well-known path: the runner pod mounts no token there.
pub const DEFAULT_API_DIR: &str = "/etc/tinfirm/kubernetes-api";
/// The runner pod's node, from the downward API (`spec.nodeName`), which no run pod may share.
pub const NODE_NAME_ENV: &str = "TINFIRM_NODE_NAME";

/// The configuration `vm-driver pod` reads without `--config`: `TINFIRM_POD_DRIVER_CONFIG`, or
/// [`DEFAULT_CONFIG`]. `--vm-driver pod` names it to the driver, and the host reads the same
/// file for its level of isolation (crate::isolation).
pub fn config_path() -> PathBuf {
    std::env::var_os(CONFIG_ENV)
        .filter(|v| !v.is_empty())
        .map_or_else(|| PathBuf::from(DEFAULT_CONFIG), PathBuf::from)
}

/// What a VM of this driver must not reach, besides the host's default probe: the cluster's
/// API server, at the address the runner pod has for it (`KUBERNETES_SERVICE_HOST` and
/// `_PORT`, which the kubelet gives every pod). The runs' network policy denies it; a VM that
/// reaches it is deleted before any file or credential enters it. Nothing where the runner
/// host does not run in a pod.
pub fn probe() -> Vec<ProbeCheck> {
    let var = |name: &str| std::env::var(name).ok().filter(|v| !v.trim().is_empty());
    api_server_probe(
        var("KUBERNETES_SERVICE_HOST").as_deref(),
        var("KUBERNETES_SERVICE_PORT").as_deref(),
    )
    .into_iter()
    .collect()
}

/// The API server's probe from the service variables' values; an IPv6 address in brackets.
fn api_server_probe(host: Option<&str>, port: Option<&str>) -> Option<ProbeCheck> {
    let host = host?.trim();
    let port: u16 = port?.trim().parse().ok().filter(|p| *p != 0)?;
    let addr = match host.parse::<std::net::IpAddr>() {
        Ok(std::net::IpAddr::V6(ip)) => format!("[{ip}]:{port}"),
        Ok(ip) => format!("{ip}:{port}"),
        Err(_) if api::dns_subdomain(host) => format!("{host}:{port}"),
        Err(_) => return None,
    };
    Some(ProbeCheck::Tcp { addr })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_api_server_is_probed_where_the_pod_has_it() {
        let tcp = |addr: &str| Some(ProbeCheck::Tcp { addr: addr.into() });
        assert_eq!(
            api_server_probe(Some("10.0.0.1"), Some("443")),
            tcp("10.0.0.1:443")
        );
        assert_eq!(
            api_server_probe(Some("fd00::1"), Some("6443")),
            tcp("[fd00::1]:6443")
        );
        assert_eq!(
            api_server_probe(Some("kubernetes.default.svc"), Some("443")),
            tcp("kubernetes.default.svc:443")
        );
        for (host, port) in [
            (None, Some("443")),
            (Some("10.0.0.1"), None),
            (Some("10.0.0.1"), Some("0")),
            (Some("10.0.0.1"), Some("https")),
            (Some("not a host"), Some("443")),
        ] {
            assert_eq!(api_server_probe(host, port), None, "{host:?} {port:?}");
        }
        // Each one a check that the host's parser takes.
        let Some(ProbeCheck::Tcp { addr }) = api_server_probe(Some("fd00::1"), Some("6443")) else {
            unreachable!()
        };
        assert!(crate::vm::parse_probe(&format!("tcp:{addr}")).is_ok());
    }
}
