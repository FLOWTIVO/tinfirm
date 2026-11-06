//! `vm-boot`, in a sandbox VM (ADR-0096): the VM's image starts it once at boot
//! (`ee/runner/images/vm/tinfirm-vm-boot.service`). It reads the VM's user data
//! ([`crate::vm::UserData`]) from the metadata service or a file, and runs the run's container
//! from the image the user data names, which the VM's image holds: this binary, mounted
//! read-only, as `bridge`, listening for the runner host, on the VM's network. The bridge
//! then starts the image's own command (`TINFIRM_BRIDGE_COMMAND`) when the host says so.
//!
//! The user data holds no credential (they come over the bridge), and `vm-boot` takes from it
//! only the image and the variables of the bridge and of the sandbox's own settings.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use http_body_util::{BodyExt, Empty, Limited};
use hyper::body::Bytes;
use hyper::{Request, header};

use crate::sandbox::{AGENT_USER_ENV, DOCKER_ENV, USER, WORKSPACE};
use crate::vm::{USER_DATA_VERSION, UserData};

/// Where DigitalOcean's metadata service serves the user data.
pub const DIGITALOCEAN_USER_DATA: &str = "http://169.254.169.254/metadata/v1/user-data";
/// The name of the run's container in the VM.
pub const CONTAINER: &str = "tinfirm-sandbox";
/// How long the metadata service may take to answer after boot.
const FETCH_WAIT: Duration = Duration::from_secs(180);
const MAX_USER_DATA: usize = 64 * 1024;

/// The user data: from `source`, an `http://` URL (a metadata service) or a file.
pub async fn fetch(source: &str) -> Result<String> {
    if !source.starts_with("http://") {
        return std::fs::read_to_string(source)
            .with_context(|| format!("cannot read the user data from {source}"));
    }
    let url = url::Url::parse(source).context("the user data's URL is not valid")?;
    let deadline = Instant::now() + FETCH_WAIT;
    loop {
        let attempt = async {
            let request = Request::get(crate::attachments::path_and_query(&url))
                .header(header::HOST, crate::attachments::authority(&url))
                .header(header::USER_AGENT, "tinfirm-runner-host vm-boot")
                .body(Empty::<Bytes>::new())?;
            let response = crate::attachments::send(&url, request, Duration::from_secs(10)).await?;
            let status = response.status();
            let body = Limited::new(response.into_body(), MAX_USER_DATA)
                .collect()
                .await
                .map_err(|e| anyhow::anyhow!("{e}"))?
                .to_bytes();
            if !status.is_success() {
                bail!("HTTP {status}");
            }
            String::from_utf8(body.to_vec()).context("the user data is not UTF-8")
        };
        match attempt.await {
            Ok(text) => return Ok(text),
            Err(e) if Instant::now() < deadline => {
                eprintln!("vm-boot: no user data yet from {source} ({e:#}); trying again");
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
            Err(e) => return Err(e).with_context(|| format!("no user data from {source}")),
        }
    }
}

/// Reads and checks the user data: its format, its image's name, and only the variables of
/// the bridge and of the sandbox's own settings.
pub fn parse(text: &str) -> Result<UserData> {
    let data: UserData = serde_json::from_str(text).context("the user data is not Tinfirm's")?;
    if data.tinfirm != USER_DATA_VERSION {
        bail!(
            "the user data has format {}, this binary reads {USER_DATA_VERSION}",
            data.tinfirm
        );
    }
    let image_ok = !data.image.is_empty()
        && data.image.len() <= 255
        && !data.image.starts_with('-')
        && data
            .image
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '/' | ':' | '@'));
    if !image_ok {
        bail!("the user data names no valid image");
    }
    for (key, value) in &data.env {
        let bridge = [
            tinfirm_bridge::ENV_LISTEN,
            tinfirm_bridge::ENV_RUN,
            tinfirm_bridge::ENV_TICKET,
            tinfirm_bridge::ENV_PIN,
        ]
        .contains(&key.as_str());
        if !bridge && !crate::bridge::stays_in_sandbox_settings(key) {
            bail!("the user data sets {key}, which is not the bridge's nor the sandbox's");
        }
        if value.chars().any(|c| c.is_control()) {
            bail!("the user data's {key} holds a control character");
        }
    }
    for key in [
        tinfirm_bridge::ENV_LISTEN,
        tinfirm_bridge::ENV_RUN,
        tinfirm_bridge::ENV_TICKET,
        tinfirm_bridge::ENV_PIN,
    ] {
        if !data.env.contains_key(key) {
            bail!("the user data has no {key}");
        }
    }
    Ok(data)
}

/// `docker run` for the run's container.
#[derive(Debug)]
pub struct DockerRun {
    pub args: Vec<String>,
    /// The variables that `docker` passes into the container by name (`--env KEY`), so that
    /// no value is on a command line.
    pub env: Vec<(String, String)>,
}

/// `docker run` for the run's container; `command` is the image's own (`Config.Cmd`), which
/// the bridge starts. The multi-runtime image has none (ADR-0101): the host then names the
/// run's adapter in the bridge's `start`.
pub fn docker_run(data: &UserData, bridge: &Path, command: &[String]) -> Result<DockerRun> {
    let own_docker = data.env.get(DOCKER_ENV).is_some_and(|v| v == "1");
    let mut args: Vec<String> = [
        "run",
        "--rm",
        "--name",
        CONTAINER,
        "--network",
        "host",
        "--init",
        "--pull",
        "never",
        "--workdir",
        WORKSPACE,
    ]
    .iter()
    .map(|a| a.to_string())
    .collect();
    args.push("--volume".into());
    args.push(format!(
        "{}:{}:ro",
        bridge.display(),
        crate::bridge::SANDBOX_PATH
    ));
    if own_docker {
        // The agent's own dockerd, as under Kata (ADR-0025): the VM is the boundary, and the
        // image's entrypoint drops to the agent's user once dockerd runs.
        args.extend(
            ["--privileged", "--cgroupns", "private", "--user", "0:0"]
                .iter()
                .map(|a| a.to_string()),
        );
    } else {
        args.extend(
            [
                "--user",
                USER,
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges:true",
            ]
            .iter()
            .map(|a| a.to_string()),
        );
    }
    let mut env: Vec<(String, String)> = data
        .env
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    if own_docker && !data.env.contains_key(AGENT_USER_ENV) {
        env.push((AGENT_USER_ENV.to_string(), USER.to_string()));
    }
    if !command.is_empty() {
        env.push((
            tinfirm_bridge::ENV_COMMAND.to_string(),
            serde_json::to_string(command).expect("argv serializes"),
        ));
    }
    for (key, _) in &env {
        args.push("--env".into());
        args.push(key.clone());
    }
    args.push(data.image.clone());
    args.push(crate::bridge::SANDBOX_PATH.to_string());
    args.push("bridge".into());
    Ok(DockerRun { args, env })
}

/// The image's own command, from the VM's Docker; the image must be there (no pull: the VM
/// reaches no registry).
fn image_command(image: &str) -> Result<Vec<String>> {
    let output = std::process::Command::new("docker")
        .args([
            "image",
            "inspect",
            "--format",
            "{{json .Config.Cmd}}",
            image,
        ])
        .output()
        .context("cannot run docker")?;
    if !output.status.success() {
        bail!(
            "the VM's image has no {image}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    let cmd: Option<Vec<String>> = serde_json::from_slice(&output.stdout)
        .with_context(|| format!("cannot read the command of {image}"))?;
    Ok(cmd.unwrap_or_default())
}

/// `vm-boot`: reads the user data and runs the run's container; its exit code is the
/// container's (the bridge's).
pub async fn run(source: &str, bridge: Option<PathBuf>) -> Result<i32> {
    let bridge = match bridge {
        Some(path) => path,
        None => std::env::current_exe().context("cannot find this executable")?,
    };
    let data = parse(&fetch(source).await?)?;
    let command = image_command(&data.image)?;
    let DockerRun { args, env } = docker_run(&data, &bridge, &command)?;
    eprintln!(
        "vm-boot: starting {} from {} for run {}",
        CONTAINER,
        data.image,
        data.env
            .get(tinfirm_bridge::ENV_RUN)
            .map_or("?", String::as_str)
    );
    // A container of an earlier boot would hold the name.
    let _ = std::process::Command::new("docker")
        .args(["rm", "--force", CONTAINER])
        .output();
    let status = tokio::process::Command::new("docker")
        .args(&args)
        .envs(env)
        .status()
        .await
        .context("cannot run docker")?;
    Ok(status.code().unwrap_or(1))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn data(extra: &[(&str, &str)]) -> UserData {
        let mut env = BTreeMap::from([
            (
                tinfirm_bridge::ENV_LISTEN.to_string(),
                "0.0.0.0:7443".to_string(),
            ),
            (tinfirm_bridge::ENV_RUN.to_string(), "run.1".to_string()),
            (tinfirm_bridge::ENV_TICKET.to_string(), "ab".repeat(32)),
            (tinfirm_bridge::ENV_PIN.to_string(), "cd".repeat(32)),
        ]);
        env.extend(extra.iter().map(|(k, v)| (k.to_string(), v.to_string())));
        UserData {
            tinfirm: USER_DATA_VERSION,
            image: "tinfirm-runner-claude:local".into(),
            env,
        }
    }

    #[test]
    fn the_user_data_takes_only_the_bridges_and_the_sandboxs_variables() {
        let text = serde_json::to_string(&data(&[(
            "TINFIRM_SANDBOX_NPM_REGISTRY",
            "https://npm.example",
        )]))
        .unwrap();
        assert_eq!(parse(&text).unwrap().env.len(), 5);
        for (key, value) in [
            ("CLAUDE_CODE_OAUTH_TOKEN", "x"),
            (tinfirm_bridge::ENV_COMMAND, "[\"sh\"]"),
            (tinfirm_bridge::ENV_ADDR, "10.0.0.1:1"),
            ("TINFIRM_SANDBOX_X", "a\nb"),
        ] {
            let text = serde_json::to_string(&data(&[(key, value)])).unwrap();
            assert!(parse(&text).is_err(), "{key}");
        }
        let mut bad = data(&[]);
        bad.image = "--privileged".into();
        assert!(parse(&serde_json::to_string(&bad).unwrap()).is_err());
        let mut later = data(&[]);
        later.tinfirm = 9;
        assert!(parse(&serde_json::to_string(&later).unwrap()).is_err());
        let mut short = data(&[]);
        short.env.remove(tinfirm_bridge::ENV_TICKET);
        assert!(parse(&serde_json::to_string(&short).unwrap()).is_err());
        assert!(parse("#cloud-config\n").is_err());
    }

    #[test]
    fn the_container_runs_the_bridge_confined_and_no_value_is_on_its_command_line() {
        let data = data(&[("HTTPS_PROXY", "http://127.0.0.1:4000")]);
        let command = vec!["claude-agent-acp".to_string()];
        let DockerRun { args, env } = docker_run(
            &data,
            Path::new("/opt/tinfirm/tinfirm-runner-host"),
            &command,
        )
        .unwrap();
        let line = args.join(" ");
        assert!(
            line.starts_with("run --rm --name tinfirm-sandbox --network host --init --pull never"),
            "{line}"
        );
        assert!(
            line.contains("--user 1000:1000 --cap-drop ALL --security-opt no-new-privileges:true"),
            "{line}"
        );
        assert!(!line.contains("--privileged"), "{line}");
        assert!(line.ends_with(&format!(
            "tinfirm-runner-claude:local {} bridge",
            crate::bridge::SANDBOX_PATH
        )));
        assert!(line.contains(&format!(
            "--volume /opt/tinfirm/tinfirm-runner-host:{}:ro",
            crate::bridge::SANDBOX_PATH
        )));
        // Every variable by name only; the ticket's value stays off the command line.
        assert!(!line.contains(&"ab".repeat(32)));
        assert!(line.contains(&format!("--env {}", tinfirm_bridge::ENV_TICKET)));
        let command_env = env
            .iter()
            .find(|(k, _)| k == tinfirm_bridge::ENV_COMMAND)
            .unwrap();
        assert_eq!(command_env.1, r#"["claude-agent-acp"]"#);
        // An image without a command (the multi-runtime image): the host names the adapter.
        let DockerRun { env, .. } = docker_run(&data, Path::new("/b"), &[]).unwrap();
        assert!(!env.iter().any(|(k, _)| k == tinfirm_bridge::ENV_COMMAND));
    }

    #[test]
    fn with_its_own_docker_the_container_starts_as_root_and_privileged() {
        let data = data(&[(DOCKER_ENV, "1")]);
        let DockerRun { args, env } =
            docker_run(&data, Path::new("/b"), &["acp".to_string()]).unwrap();
        let line = args.join(" ");
        assert!(
            line.contains("--privileged --cgroupns private --user 0:0"),
            "{line}"
        );
        assert!(!line.contains("--cap-drop"), "{line}");
        assert!(env.contains(&(AGENT_USER_ENV.to_string(), USER.to_string())));
    }

    #[tokio::test]
    async fn the_user_data_comes_from_a_file_or_the_metadata_service() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("user-data");
        std::fs::write(&file, "{}").unwrap();
        assert_eq!(fetch(file.to_str().unwrap()).await.unwrap(), "{}");
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 1024];
            let _ = socket.read(&mut buf).await;
            let body = r#"{"tinfirm":1}"#;
            let _ = socket
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    )
                    .as_bytes(),
                )
                .await;
        });
        let text = fetch(&format!("http://{addr}/metadata/v1/user-data"))
            .await
            .unwrap();
        assert_eq!(text, r#"{"tinfirm":1}"#);
    }
}
