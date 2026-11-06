//! Docker Engine connection and container bookkeeping.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

use anyhow::{Context, Result};
use bollard::Docker;
use bollard::models::NetworkCreateRequest;
use bollard::query_parameters::{ListContainersOptionsBuilder, RemoveContainerOptionsBuilder};
use uuid::Uuid;

pub const RUN_ID_LABEL: &str = "dev.tinfirm.run-id";
pub const RUNNER_HOST_LABEL: &str = "dev.tinfirm.runner-host";
pub const CONTAINER_PREFIX: &str = "tinfirm-run-";

/// Connects to the local Docker Engine: `DOCKER_HOST`, then the usual socket locations
/// (Docker Desktop, Colima, OrbStack, Rancher Desktop).
pub fn connect() -> Result<Docker> {
    if let Ok(host) = std::env::var("DOCKER_HOST")
        && !host.is_empty()
    {
        return Docker::connect_with_defaults()
            .with_context(|| format!("cannot connect to DOCKER_HOST={host}"));
    }
    let home = dirs::home_dir().unwrap_or_default();
    let candidates: Vec<PathBuf> = vec![
        PathBuf::from("/var/run/docker.sock"),
        home.join(".docker/run/docker.sock"),
        home.join(".colima/default/docker.sock"),
        home.join(".orbstack/run/docker.sock"),
        home.join(".rd/docker.sock"),
    ];
    for socket in candidates {
        if socket.exists() {
            return Docker::connect_with_socket(
                &socket.to_string_lossy(),
                120,
                bollard::API_DEFAULT_VERSION,
            )
            .with_context(|| format!("cannot connect to {}", socket.display()));
        }
    }
    Docker::connect_with_local_defaults().context("cannot find a Docker socket; is Docker running?")
}

/// The name of the internal network that sandboxes get with `run --egress-proxy`.
pub const EGRESS_NETWORK: &str = "tinfirm-sandbox-egress";

/// Docker's option that stops containers on one bridge from talking to each other: without it
/// concurrent sandboxes could reach each other's services directly, past the egress proxy.
const NO_ICC: (&str, &str) = ("com.docker.network.bridge.enable_icc", "false");

/// Makes sure that the Docker network `name` exists and is internal (no route out of it),
/// creating it when it does not exist (with no traffic between its containers). Refuses a
/// network that is not internal, or that lets containers talk to each other: a sandbox on it
/// could bypass the egress proxy.
pub async fn ensure_internal_network(docker: &Docker, name: &str) -> Result<()> {
    let existing = match docker.inspect_network(name, None).await {
        Ok(network) => Some(network),
        Err(bollard::errors::Error::DockerResponseServerError {
            status_code: 404, ..
        }) => None,
        Err(e) => {
            return Err(e).with_context(|| format!("cannot inspect the Docker network `{name}`"));
        }
    };
    let network = match existing {
        Some(network) => network,
        None => {
            let request = NetworkCreateRequest {
                name: name.to_string(),
                driver: Some("bridge".into()),
                internal: Some(true),
                options: Some(HashMap::from([(
                    NO_ICC.0.to_string(),
                    NO_ICC.1.to_string(),
                )])),
                labels: Some(HashMap::from([(
                    "dev.tinfirm.purpose".to_string(),
                    "sandbox-egress".to_string(),
                )])),
                ..Default::default()
            };
            match docker.create_network(request).await {
                Ok(_) => {}
                // Another `run` or a restart got there first.
                Err(bollard::errors::Error::DockerResponseServerError {
                    status_code: 409, ..
                }) => {}
                Err(e) => {
                    return Err(e)
                        .with_context(|| format!("cannot create the Docker network `{name}`"));
                }
            }
            docker
                .inspect_network(name, None)
                .await
                .with_context(|| format!("cannot inspect the Docker network `{name}`"))?
        }
    };
    if network.internal != Some(true) {
        anyhow::bail!(
            "the Docker network `{name}` is not internal: a sandbox on it could reach the network without the egress proxy. Remove it (`docker network rm {name}`) so that `run` creates an internal one, or name an internal network"
        );
    }
    let icc_off = network
        .options
        .as_ref()
        .and_then(|options| options.get(NO_ICC.0))
        .is_some_and(|value| value == NO_ICC.1);
    if !icc_off {
        anyhow::bail!(
            "the Docker network `{name}` lets containers talk to each other: concurrent sandboxes could reach each other without the egress proxy. Remove it (`docker network rm {name}`) so that `run` creates one with `{}={}`, or create your own with that option",
            NO_ICC.0,
            NO_ICC.1
        );
    }
    Ok(())
}

pub fn container_name(run_id: Uuid) -> String {
    format!("{CONTAINER_PREFIX}{run_id}")
}

/// Containers of this runner host: name → run id.
pub async fn list_run_containers(
    docker: &Docker,
    runner_host: &str,
) -> Result<HashMap<String, Option<Uuid>>> {
    let mut filters = HashMap::new();
    filters.insert(
        "label".to_string(),
        vec![format!("{RUNNER_HOST_LABEL}={runner_host}")],
    );
    let options = ListContainersOptionsBuilder::default()
        .all(true)
        .filters(&filters)
        .build();
    let containers = docker
        .list_containers(Some(options))
        .await
        .context("cannot list containers")?;
    let mut out = HashMap::new();
    for c in containers {
        let name = c
            .names
            .as_ref()
            .and_then(|n| n.first())
            .map(|n| n.trim_start_matches('/').to_string())
            .or(c.id.clone())
            .unwrap_or_default();
        let run_id = c
            .labels
            .as_ref()
            .and_then(|l| l.get(RUN_ID_LABEL))
            .and_then(|id| Uuid::parse_str(id).ok());
        out.insert(name, run_id);
    }
    Ok(out)
}

pub async fn remove_container(docker: &Docker, name: &str) -> Result<()> {
    match docker
        .remove_container(
            name,
            Some(
                RemoveContainerOptionsBuilder::default()
                    .force(true)
                    .v(true)
                    .build(),
            ),
        )
        .await
    {
        Ok(()) => Ok(()),
        Err(bollard::errors::Error::DockerResponseServerError {
            status_code: 404, ..
        }) => Ok(()),
        Err(e) => Err(e).with_context(|| format!("cannot remove container {name}")),
    }
}

/// Removes this host's containers that belong to no live run (docs/archive/v1/CONVENTIONS.md §15.3 cleanup).
pub async fn cleanup_orphans(
    docker: &Docker,
    runner_host: &str,
    live: &HashSet<Uuid>,
) -> Result<usize> {
    let mut removed = 0;
    for (name, run_id) in list_run_containers(docker, runner_host).await? {
        if run_id.is_some_and(|id| live.contains(&id)) {
            continue;
        }
        tracing::info!(container = %name, "removing orphan run container");
        remove_container(docker, &name).await?;
        removed += 1;
    }
    Ok(removed)
}
