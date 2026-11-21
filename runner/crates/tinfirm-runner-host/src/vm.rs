//! The VM executor (`run --executor vm`, ADR-0096): a fresh VM per run, from the operator's
//! template, that is deleted when the run ends and never reused. A driver, a program of its
//! own, creates and deletes the VMs at the provider ([`Driver`], the driver protocol); the
//! runner host holds no cloud credential.
//!
//! The VM's firewall lets nothing out, so the host dials in: the sandbox's bridge listens on
//! [`VmOptions::port`] and the host connects to the VM's address, as the TLS server still
//! (ADR-0095). Before the agent starts, the bridge probes from inside what the VM must not
//! reach ([`default_probe`]); a VM that reaches any of it is deleted and its run fails.
//!
//! What the VM gets at its creation is its user data ([`UserData`]): the bridge's settings with
//! the VM's one-time ticket and the host's pin, the image to run, and the sandbox's own
//! settings. Anything in the VM can read it from the metadata service, so it holds no
//! credential: those travel over the bridge, in `start`. It names no run either: every VM is
//! created for whichever run claims it (crate::pool), and is deleted after that run.
//!
//! The executor and the driver protocol are in the core (ADR-0114): an operator can bring a
//! driver of their own. The drivers built into this binary for a provider are paid
//! (DigitalOcean's, crate::digitalocean): a host with one lists the feature `sandbox.elastic`
//! ([`DriverCommand::elastic`]). The driver for sandbox nodes (crate::node) is this binary's
//! too, and in the core ([`Builtin::Node`]).

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tinfirm_bridge::control::ProbeCheck;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin};
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

/// The version of the driver protocol that this host speaks (`hello.protocol`).
pub const DRIVER_PROTOCOL: u32 = 1;
/// The version of the user data's format (`UserData::tinfirm`).
pub const USER_DATA_VERSION: u32 = 1;
/// Where the sandbox's bridge listens in the VM, by default.
pub const DEFAULT_PORT: u16 = 7443;
/// The label of the runner host whose VM it is (its id at the server).
pub const HOST_LABEL: &str = "tinfirm-host";
/// The label of a VM's own id at this host, which names no run (crate::pool).
pub const VM_LABEL: &str = "tinfirm-vm";
/// The label of the run a VM was for, on the VMs of runner hosts before ADR-0098: their
/// leftovers are still recognised by it.
pub const RUN_LABEL: &str = "tinfirm-run";
/// `--vm-driver digitalocean`: this binary's own driver for DigitalOcean (`vm-driver
/// digitalocean`, crate::digitalocean), which is paid (ADR-0114).
pub const BUILTIN_DIGITALOCEAN: &str = "digitalocean";

/// How long one call of the driver may take, by method.
const HELLO_TIMEOUT: Duration = Duration::from_secs(30);
const VERIFY_TIMEOUT: Duration = Duration::from_secs(120);
const CREATE_TIMEOUT: Duration = Duration::from_secs(180);
const DESCRIBE_TIMEOUT: Duration = Duration::from_secs(60);
const DELETE_TIMEOUT: Duration = Duration::from_secs(120);
const LIST_TIMEOUT: Duration = Duration::from_secs(120);
/// How often a delete is tried at once before the VM is left to its later tries.
const DELETE_ATTEMPTS: u32 = 3;
/// After a delete that failed: the first wait before the host tries it again
/// ([`VmOptions::delete_retry`]). Each later wait is twice the one before, up to
/// [`DELETE_RETRY_MAX`].
const DELETE_RETRY: Duration = Duration::from_secs(15);
const DELETE_RETRY_MAX: Duration = Duration::from_secs(300);
/// How many failed tries of a delete the host warns of (about 40 minutes with the default
/// waits). It goes on trying after them, once per [`DELETE_RETRY_MAX`], and says so once: a
/// node that comes back after hours gets its slot back without a restart of this host.
const DELETE_RETRIES: u32 = 12;
/// How long a run whose VM's bridge was lost waits for the driver's `describe` of the VM.
const LOST_WAIT: Duration = Duration::from_secs(25);
/// The longest line the driver may answer with.
const MAX_LINE: u64 = 4 * 1024 * 1024;
/// While the driver can make no VM, how often the host asks its `verify` again on its own
/// ([`VmOptions::recheck`]).
const RECHECK: Duration = Duration::from_secs(30);
/// A run that comes while the driver can make no VM asks `verify` again first, unless the last
/// answer is this recent: the server dispatches a rejected run again after 10 s, and several
/// runs at once ask once.
const RECHECK_FRESH: Duration = Duration::from_secs(5);
/// The longest a run's admission, or the report of a failed create, waits for a health
/// check ([`VmOptions::check_wait`]); the check goes on by itself, bounded by the driver's own
/// [`VERIFY_TIMEOUT`], and its answer is recorded when it comes.
const CHECK_WAIT: Duration = Duration::from_secs(5);
/// The most of the driver's problems that the host passes on in a rejection: it goes to the
/// server, and is shown as the run's queue reason.
const CAUSE_MAX: usize = 1000;

/// The program that is the driver, with its arguments and the environment it gets in addition
/// to the host's.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DriverCommand {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub env: Vec<(String, String)>,
    /// Which of this binary's own drivers the command is; `None`: the operator's own driver.
    /// The host derives what it reports from it: the feature `sandbox.elastic`
    /// ([`DriverCommand::elastic`]) and its level of isolation (crate::isolation).
    pub builtin: Option<Builtin>,
}

/// A driver built into this binary (`vm-driver <name>`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Builtin {
    /// DigitalOcean's (crate::digitalocean): a provider's VM per run, paid (ADR-0114).
    Digitalocean,
    /// Sandbox nodes (crate::node, ADR-0112), in the core, with the configuration it reads.
    Node { config: PathBuf },
}

impl DriverCommand {
    /// `--vm-driver`: `digitalocean` or `node` for this binary's own drivers, or a command line
    /// of the operator's own driver, split at white space (no quoting).
    pub fn parse(value: &str) -> Result<DriverCommand> {
        let value = value.trim();
        if value == crate::node::BUILTIN_NODE {
            return DriverCommand::node(crate::node::config_path());
        }
        if value == BUILTIN_DIGITALOCEAN {
            return Ok(DriverCommand {
                program: std::env::current_exe().context("cannot find this executable")?,
                args: vec!["vm-driver".into(), BUILTIN_DIGITALOCEAN.into()],
                env: Vec::new(),
                builtin: Some(Builtin::Digitalocean),
            });
        }
        let mut words = value.split_whitespace();
        let program = words
            .next()
            .context("--vm-driver names no program (`digitalocean`, or a command line)")?;
        Ok(DriverCommand {
            program: PathBuf::from(program),
            args: words.map(String::from).collect(),
            env: Vec::new(),
            builtin: None,
        })
    }

    /// The driver for sandbox nodes (crate::node, ADR-0112) with its configuration at
    /// `config`, which it gets as `--config`: the host and the driver read one file.
    pub fn node(config: PathBuf) -> Result<DriverCommand> {
        Ok(DriverCommand {
            program: std::env::current_exe().context("cannot find this executable")?,
            args: vec![
                "vm-driver".into(),
                crate::node::BUILTIN_NODE.into(),
                "--config".into(),
                config.to_string_lossy().into_owned(),
            ],
            env: Vec::new(),
            builtin: Some(Builtin::Node { config }),
        })
    }

    /// One of this binary's paid drivers (ADR-0114), DigitalOcean's today: the host lists the
    /// feature `sandbox.elastic`, which a server refuses without its licence's elastic
    /// runners. The node driver and an operator's own driver are not.
    pub fn elastic(&self) -> bool {
        matches!(self.builtin, Some(Builtin::Digitalocean))
    }
}

/// How the host creates its VMs (`run --executor vm`).
#[derive(Debug, Clone)]
pub struct VmOptions {
    pub driver: DriverCommand,
    /// The driver's name for what a VM is created from: image, size, network and firewall,
    /// as the operator configured it for the driver.
    pub template: String,
    /// Where the sandbox's bridge listens in the VM.
    pub port: u16,
    /// From the create to the bridge's first connection: the provider's boot, the VM's own
    /// start and its dockerd.
    pub start_timeout: Duration,
    /// Between two looks at a VM that is starting.
    pub poll: Duration,
    /// What the VM must not reach, tried from inside before the agent starts.
    pub probe: Vec<ProbeCheck>,
    /// What the host does with a VM whose image another build of the runner host made
    /// (`--vm-image-version`, ADR-0101).
    pub image_version: ImageVersion,
    /// While the driver can make no VM ([`Vms::refusal`]): how often the host asks its `verify`
    /// again on its own. No flag sets it.
    pub recheck: Duration,
    /// The longest a run's admission, or the report of a failed create, waits for the driver's
    /// `verify` in a health check; then it goes on with what is known. No flag sets it.
    pub check_wait: Duration,
    /// After a delete that failed: the first wait before the host tries it again on its own
    /// ([`DELETE_RETRY`]). No flag sets it.
    pub delete_retry: Duration,
    /// Test-only: the driver's "VMs" are processes on this machine, so the sandbox shares this
    /// machine's network and file system as the local test executor's does.
    #[doc(hidden)]
    pub on_this_machine: bool,
}

impl VmOptions {
    pub fn new(driver: DriverCommand, template: String) -> VmOptions {
        VmOptions {
            driver,
            template,
            port: DEFAULT_PORT,
            start_timeout: Duration::from_secs(900),
            poll: Duration::from_secs(3),
            probe: default_probe(),
            image_version: ImageVersion::Warn,
            recheck: RECHECK,
            check_wait: CHECK_WAIT,
            delete_retry: DELETE_RETRY,
            on_this_machine: false,
        }
    }
}

/// What the host does with a VM whose image another build of the runner host made: the VM's
/// runner images are then not those this host would build (ADR-0101, as ADR-0062 checks a
/// container's). The host cannot rebuild a VM image; its operator does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageVersion {
    /// The run goes on, and the host warns once for each build it sees.
    Warn,
    /// The VM is deleted, and its run fails with `INFRA`.
    Refuse,
}

/// What is wrong with a VM whose bridge says `reported` was its build, for a host built as
/// `own` ([`crate::images::build_id`]); `None` when nothing is.
pub fn image_mismatch(own: &str, reported: Option<&str>) -> Option<String> {
    match reported {
        Some(build) if build == own => None,
        Some(build) => Some(format!(
            "the VM's image was built by the runner host {build}, and this host is {own}: its runner images are not those this host would build"
        )),
        None => Some(format!(
            "the VM's image was built by a runner host older than this one ({own}), which names no build"
        )),
    }
}

/// What every VM is probed for, on every provider: the internet by address and by name, and
/// the credential paths of the metadata services that hand out one (AWS, GCP, Azure). Any
/// that answers deletes the VM. A metadata service that only serves the user data (as
/// DigitalOcean's does) is no finding: the user data holds no credential.
pub fn default_probe() -> Vec<ProbeCheck> {
    const METADATA: &str = "169.254.169.254:80";
    let http = |path: &str, headers: &[(&str, &str)]| ProbeCheck::Http {
        addr: METADATA.into(),
        path: path.into(),
        headers: headers
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect(),
    };
    vec![
        ProbeCheck::Tcp {
            addr: "1.1.1.1:443".into(),
        },
        ProbeCheck::Tcp {
            addr: "8.8.8.8:53".into(),
        },
        ProbeCheck::Tcp {
            addr: "[2606:4700:4700::1111]:443".into(),
        },
        ProbeCheck::Dns {
            name: "example.com".into(),
        },
        http("/latest/meta-data/iam/security-credentials/", &[]),
        http(
            "/computeMetadata/v1/instance/service-accounts/default/token",
            &[("Metadata-Flavor", "Google")],
        ),
        http(
            "/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://management.azure.com/",
            &[("Metadata", "true")],
        ),
    ]
}

/// One `--vm-probe` check: `tcp:HOST:PORT`, `dns:NAME` or `http://HOST:PORT/PATH`.
pub fn parse_probe(value: &str) -> std::result::Result<ProbeCheck, String> {
    let value = value.trim();
    if let Some(addr) = value.strip_prefix("tcp:") {
        if addr
            .rsplit_once(':')
            .is_none_or(|(host, port)| host.is_empty() || port.parse::<u16>().is_err())
        {
            return Err(format!("`{value}`: tcp:HOST:PORT"));
        }
        return Ok(ProbeCheck::Tcp { addr: addr.into() });
    }
    if let Some(name) = value.strip_prefix("dns:") {
        if name.is_empty() || name.chars().any(|c| c.is_whitespace() || c.is_control()) {
            return Err(format!("`{value}`: dns:NAME"));
        }
        return Ok(ProbeCheck::Dns { name: name.into() });
    }
    if let Some(rest) = value.strip_prefix("http://") {
        let (addr, path) = match rest.find('/') {
            Some(at) => (&rest[..at], &rest[at..]),
            None => (rest, "/"),
        };
        let addr = if addr
            .rsplit_once(':')
            .is_some_and(|(_, p)| p.parse::<u16>().is_ok())
            && !addr.ends_with(']')
        {
            addr.to_string()
        } else {
            format!("{addr}:80")
        };
        if addr.starts_with(':') || path.chars().any(|c| c.is_whitespace() || c.is_control()) {
            return Err(format!("`{value}`: http://HOST[:PORT]/PATH"));
        }
        return Ok(ProbeCheck::Http {
            addr,
            path: path.into(),
            headers: Vec::new(),
        });
    }
    Err(format!(
        "`{value}` is no check: tcp:HOST:PORT, dns:NAME or http://HOST[:PORT]/PATH"
    ))
}

// ----- the driver protocol -------------------------------------------------------------

/// `hello`: who the driver is.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DriverHello {
    pub protocol: u32,
    pub driver: String,
    #[serde(default)]
    pub version: Option<String>,
}

/// `verify`: what breaks the contract of a sandbox VM in the template's setup, and how many
/// VMs the account may still create (`None`: the driver cannot tell).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Verified {
    #[serde(default)]
    pub problems: Vec<String>,
    #[serde(default)]
    pub max_vms: Option<u32>,
    /// What the driver checked, for the log.
    #[serde(default)]
    pub checked: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum VmState {
    /// Created, not yet up: no address to dial yet.
    Starting,
    Running,
    /// Deleted, or never there.
    Gone,
    /// The provider gave up on it.
    Failed,
}

/// A VM as the driver describes it.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Vm {
    pub id: String,
    #[serde(default)]
    pub name: Option<String>,
    pub state: VmState,
    /// Where the host dials the bridge: an IP address or a host name, without the port.
    #[serde(default)]
    pub address: Option<String>,
    /// The port at `address` where the host dials this VM's bridge, when the driver names one:
    /// a node publishes each VM's bridge on a port of its own address (ADR-0112). Without it,
    /// the host dials [`VmOptions::port`]. The bridge in the VM listens on that port either way.
    #[serde(default)]
    pub port: Option<u16>,
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
}

/// Where the host dials a VM's bridge: its address, and the port the driver named, if any.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reach {
    pub address: String,
    pub port: Option<u16>,
}

impl Reach {
    /// `host:port` to dial: the driver's port, or `default` (`--vm-port`) without one.
    pub fn dial(&self, default: u16) -> String {
        crate::git_proxy::host_port(&self.address, self.port.unwrap_or(default))
    }
}

#[derive(Debug, Deserialize)]
struct Created {
    id: String,
}

#[derive(Debug, Deserialize)]
struct Listed {
    #[serde(default)]
    vms: Vec<Vm>,
}

/// What a VM gets at its creation: its user data, which the driver passes on as it is.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UserData {
    /// [`USER_DATA_VERSION`].
    pub tinfirm: u32,
    /// The runner image of the run's runtime, which the VM's image holds.
    pub image: String,
    /// The environment of the sandbox's container: the bridge's settings and the sandbox's
    /// own (crate::bridge::split_env). Never a credential.
    pub env: BTreeMap<String, String>,
}

/// The calls that wait for the driver's answer, shared with the task that reads them.
#[derive(Default)]
struct Calls {
    pending: Mutex<HashMap<u64, oneshot::Sender<Result<Value>>>>,
    /// The driver's stdout ended: it exited, and every call waiting on it failed.
    gone: CancellationToken,
}

/// The driver's process, which ends (kill on drop) with the [`Driver`].
struct Process {
    stdin: tokio::sync::Mutex<ChildStdin>,
    calls: Arc<Calls>,
    _child: Child,
}

/// The driver: one process that the host starts on first use, and again after it exits. It
/// speaks JSON-RPC 2.0 on stdin and stdout, one message per line, and may answer in any
/// order; what it writes to stderr goes to the host's log.
pub struct Driver {
    command: DriverCommand,
    process: tokio::sync::Mutex<Option<Arc<Process>>>,
    next_id: AtomicU64,
}

impl Driver {
    pub fn new(command: DriverCommand) -> Driver {
        Driver {
            command,
            process: tokio::sync::Mutex::new(None),
            next_id: AtomicU64::new(1),
        }
    }

    async fn process(&self) -> Result<Arc<Process>> {
        let mut slot = self.process.lock().await;
        if let Some(process) = slot.as_ref()
            && !process.calls.gone.is_cancelled()
        {
            return Ok(process.clone());
        }
        let mut child = tokio::process::Command::new(&self.command.program)
            .args(&self.command.args)
            .envs(self.command.env.iter().map(|(k, v)| (k, v)))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .with_context(|| {
                format!(
                    "cannot start the VM driver {}",
                    self.command.program.display()
                )
            })?;
        let stdin = child.stdin.take().context("the driver has no stdin")?;
        let stdout = child.stdout.take().context("the driver has no stdout")?;
        let stderr = child.stderr.take().context("the driver has no stderr")?;
        let calls = Arc::new(Calls::default());
        tokio::spawn(read_answers(stdout, calls.clone()));
        let process = Arc::new(Process {
            stdin: tokio::sync::Mutex::new(stdin),
            calls,
            _child: child,
        });
        tokio::spawn(async move {
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                tracing::info!(driver = %line.trim_end(), "VM driver");
            }
        });
        *slot = Some(process.clone());
        Ok(process)
    }

    /// One call; the driver's error is the call's.
    pub async fn call(&self, method: &str, params: Value, timeout: Duration) -> Result<Value> {
        let process = self.process().await?;
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        lock(&process.calls.pending).insert(id, tx);
        let mut line = serde_json::to_vec(&json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        }))
        .expect("requests serialize");
        line.push(b'\n');
        let written = {
            let mut stdin = process.stdin.lock().await;
            match tokio::time::timeout(timeout, async {
                stdin.write_all(&line).await?;
                stdin.flush().await
            })
            .await
            {
                Ok(written) => written.map_err(anyhow::Error::from),
                Err(_) => Err(anyhow!("the driver takes no input")),
            }
        };
        if let Err(e) = written {
            lock(&process.calls.pending).remove(&id);
            process.calls.gone.cancel();
            return Err(e).with_context(|| format!("cannot send `{method}` to the VM driver"));
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(answer)) => answer.with_context(|| format!("the VM driver's `{method}`")),
            Ok(Err(_)) => bail!("the VM driver exited before it answered `{method}`"),
            Err(_) => {
                lock(&process.calls.pending).remove(&id);
                bail!(
                    "the VM driver did not answer `{method}` within {} s",
                    timeout.as_secs()
                )
            }
        }
    }

    pub async fn hello(&self) -> Result<DriverHello> {
        let answer = self
            .call(
                "hello",
                json!({ "protocol": DRIVER_PROTOCOL }),
                HELLO_TIMEOUT,
            )
            .await?;
        let hello: DriverHello =
            serde_json::from_value(answer).context("the VM driver's `hello` is not readable")?;
        if hello.protocol != DRIVER_PROTOCOL {
            bail!(
                "the VM driver `{}` speaks driver protocol {}, this host {DRIVER_PROTOCOL}",
                hello.driver,
                hello.protocol
            );
        }
        Ok(hello)
    }

    pub async fn verify(&self, template: &str) -> Result<Verified> {
        let answer = self
            .call("verify", json!({ "template": template }), VERIFY_TIMEOUT)
            .await?;
        serde_json::from_value(answer).context("the VM driver's `verify` is not readable")
    }

    /// Creates a VM and returns its id.
    pub async fn create(
        &self,
        template: &str,
        name: &str,
        user_data: &UserData,
        labels: &BTreeMap<String, String>,
    ) -> Result<String> {
        let user_data = serde_json::to_string(user_data).expect("user data serializes");
        let answer = self
            .call(
                "create",
                json!({
                    "template": template,
                    "name": name,
                    "userData": user_data,
                    "labels": labels,
                }),
                CREATE_TIMEOUT,
            )
            .await?;
        let created: Created =
            serde_json::from_value(answer).context("the VM driver's `create` is not readable")?;
        if created.id.is_empty() {
            bail!("the VM driver created a VM without an id");
        }
        Ok(created.id)
    }

    pub async fn describe(&self, id: &str) -> Result<Vm> {
        let answer = self
            .call("describe", json!({ "id": id }), DESCRIBE_TIMEOUT)
            .await?;
        let vm: Vm =
            serde_json::from_value(answer).context("the VM driver's `describe` is not readable")?;
        if vm.id != id {
            bail!("the VM driver described another VM than {id}");
        }
        Ok(vm)
    }

    /// Deletes a VM; one that is gone already is no error. The driver refuses a VM that does
    /// not carry each of `labels` (this host's).
    pub async fn delete(&self, id: &str, labels: &BTreeMap<String, String>) -> Result<()> {
        self.call(
            "delete",
            json!({ "id": id, "labels": labels }),
            DELETE_TIMEOUT,
        )
        .await
        .map(|_| ())
    }

    /// The VMs that carry every one of `labels`.
    pub async fn list(&self, labels: &BTreeMap<String, String>) -> Result<Vec<Vm>> {
        let answer = self
            .call("list", json!({ "labels": labels }), LIST_TIMEOUT)
            .await?;
        let listed: Listed =
            serde_json::from_value(answer).context("the VM driver's `list` is not readable")?;
        Ok(listed.vms)
    }
}

async fn read_answers(stdout: tokio::process::ChildStdout, calls: Arc<Calls>) {
    let mut reader = BufReader::new(stdout);
    loop {
        let mut line = Vec::new();
        match (&mut reader)
            .take(MAX_LINE)
            .read_until(b'\n', &mut line)
            .await
        {
            Ok(0) | Err(_) => break,
            Ok(_) if !line.ends_with(b"\n") && line.len() as u64 >= MAX_LINE => {
                tracing::warn!("the VM driver wrote a line over {MAX_LINE} bytes; it is stopped");
                break;
            }
            Ok(_) => {}
        }
        let Ok(message) = serde_json::from_slice::<Value>(&line) else {
            tracing::warn!("the VM driver wrote a line that is not JSON; it is ignored");
            continue;
        };
        let Some(id) = message.get("id").and_then(Value::as_u64) else {
            continue;
        };
        let Some(tx) = lock(&calls.pending).remove(&id) else {
            continue;
        };
        let answer = match (message.get("result"), message.get("error")) {
            (_, Some(error)) => Err(anyhow!(
                "{}",
                error
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("the driver failed without a message")
            )),
            (Some(result), None) => Ok(result.clone()),
            (None, None) => Err(anyhow!("an answer with neither result nor error")),
        };
        let _ = tx.send(answer);
    }
    calls.gone.cancel();
    // Their senders go: each waiting call learns that the driver exited.
    lock(&calls.pending).clear();
}

pub(crate) fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

// ----- the driver's side of the protocol -------------------------------------------------

/// A line that is not JSON (JSON-RPC 2.0's parse error).
pub const PARSE_ERROR: i64 = -32700;
/// JSON that is not a request of the driver protocol (JSON-RPC 2.0's invalid request).
pub const INVALID_REQUEST: i64 = -32600;
/// A method that failed, or that the driver does not have: the message says what.
pub const DRIVER_ERROR: i64 = -32000;
/// The longest request line a driver of this binary reads.
const MAX_REQUEST_LINE: u64 = 1024 * 1024;

/// One line of a driver's input.
#[derive(Debug, Clone, PartialEq)]
pub enum RequestLine {
    /// Empty, or white space only: skipped without an answer.
    Empty,
    /// A request: its `id`, `method` and `params` (`null` when absent).
    Request {
        id: Value,
        method: String,
        params: Value,
    },
    /// Not a request: the error answer it gets.
    Refused(Value),
}

fn error_answer(id: Value, code: i64, message: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": message}})
}

/// What a line of a driver's input is. A request is a JSON object with `jsonrpc` "2.0", a
/// `method` and an `id` (a number or a string): the protocol has no notifications, so a
/// request without an `id` is refused too, and so is a batch.
pub fn read_request(line: &[u8]) -> RequestLine {
    if line.iter().all(u8::is_ascii_whitespace) {
        return RequestLine::Empty;
    }
    let Ok(value) = serde_json::from_slice::<Value>(line) else {
        return RequestLine::Refused(error_answer(
            Value::Null,
            PARSE_ERROR,
            "the line is not JSON: a request is one JSON-RPC 2.0 object on one line",
        ));
    };
    // The id is answered when it is one; a malformed one is answered as null.
    let id = match value.get("id") {
        Some(id @ (Value::Number(_) | Value::String(_))) => id.clone(),
        _ => Value::Null,
    };
    let refuse =
        |message: &str| RequestLine::Refused(error_answer(id.clone(), INVALID_REQUEST, message));
    let Value::Object(request) = &value else {
        return refuse("not a request: a request is a JSON object, and batches are not taken");
    };
    if request.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return refuse("not a request: `jsonrpc` must be \"2.0\"");
    }
    let Some(method) = request.get("method").and_then(Value::as_str) else {
        return refuse("not a request: `method` is missing or not a string");
    };
    if id.is_null() {
        return refuse(
            "not a request: `id` is missing or not a number or a string (the driver protocol has no notifications)",
        );
    }
    RequestLine::Request {
        id,
        method: method.to_string(),
        params: request.get("params").cloned().unwrap_or(Value::Null),
    }
}

/// Serves the driver protocol on `input` and `output` until `input` ends, for this binary's
/// drivers: each request in a task of its own (`handle` with its method and params), each
/// answer on a line of its own, in any order. A line that is not a request gets an error
/// answer ([`PARSE_ERROR`], [`INVALID_REQUEST`]); an empty line none. Fails when the input
/// held such lines and no request, so that a caller that sent only bad lines sees a failure.
pub async fn serve_driver<R, W, F, Fut>(input: R, mut output: W, handle: F) -> Result<()>
where
    R: tokio::io::AsyncRead + Unpin,
    W: tokio::io::AsyncWrite + Unpin + Send + 'static,
    F: Fn(String, Value) -> Fut,
    Fut: std::future::Future<Output = Result<Value>> + Send + 'static,
{
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Value>();
    let writer = tokio::spawn(async move {
        while let Some(answer) = rx.recv().await {
            let mut line = serde_json::to_vec(&answer).expect("answers serialize");
            line.push(b'\n');
            if output.write_all(&line).await.is_err() || output.flush().await.is_err() {
                break;
            }
        }
    });
    let (mut requests, mut refused) = (0u64, 0u64);
    let mut failed = None;
    let mut reader = BufReader::new(input);
    loop {
        let mut line = Vec::new();
        match (&mut reader)
            .take(MAX_REQUEST_LINE + 1)
            .read_until(b'\n', &mut line)
            .await
        {
            Ok(0) => break,
            // Not the input's end: the answers under way go out, then the driver fails.
            Err(e) => {
                failed = Some(e);
                break;
            }
            Ok(_) if line.last() == Some(&b'\n') => {
                line.pop();
            }
            Ok(_) if line.len() as u64 > MAX_REQUEST_LINE => {
                // The rest of the line is still to come: nothing after it can be read as a
                // request.
                refused += 1;
                let _ = tx.send(error_answer(
                    Value::Null,
                    PARSE_ERROR,
                    &format!("a line over {MAX_REQUEST_LINE} bytes; no more input is read"),
                ));
                break;
            }
            Ok(_) => {}
        }
        match read_request(&line) {
            RequestLine::Empty => {}
            RequestLine::Refused(answer) => {
                refused += 1;
                eprintln!(
                    "{}",
                    answer["error"]["message"].as_str().unwrap_or_default()
                );
                let _ = tx.send(answer);
            }
            RequestLine::Request { id, method, params } => {
                requests += 1;
                let (work, tx) = (handle(method.clone(), params), tx.clone());
                tokio::spawn(async move {
                    let answer = match work.await {
                        Ok(result) => json!({"jsonrpc": "2.0", "id": id, "result": result}),
                        Err(e) => {
                            eprintln!("{method} failed: {e:#}");
                            error_answer(id, DRIVER_ERROR, &format!("{e:#}"))
                        }
                    };
                    let _ = tx.send(answer);
                });
            }
        }
    }
    drop(tx);
    let _ = writer.await;
    if let Some(e) = failed {
        return Err(e).context("cannot read the driver's input");
    }
    if refused > 0 && requests == 0 {
        bail!(
            "the input held {refused} line(s) that are no request of the driver protocol, each answered with an error, and no request"
        );
    }
    Ok(())
}

// ----- the executor --------------------------------------------------------------------

/// The VM executor: its options and its driver.
pub struct Vms {
    pub options: VmOptions,
    driver: Driver,
    /// The [`VM_LABEL`]s of this process's VMs that are not deleted yet, from just before
    /// their create: the leftover cleanup never takes one of them.
    live: Mutex<HashSet<String>>,
    /// The VMs whose delete failed, which the host tries again on its own ([`Vms::delete_later`]).
    left: Mutex<Left>,
    /// The builds of VM images that the host has warned of ([`ImageVersion::Warn`]).
    warned: Mutex<HashSet<Option<String>>>,
    /// Set while the driver can make no VM ([`Vms::refusal`]).
    no_vms: Mutex<Option<NoVms>>,
    /// Counts the times the driver started to make no VM: a recheck task ends when its own
    /// time is over.
    no_vms_since: AtomicU64,
    /// The cause while the driver can make no VM, `None` while it can: what the host reports
    /// to the server (`host.health`, ADR-0117, [`crate::health`]).
    no_vms_report: tokio::sync::watch::Sender<Option<String>>,
    /// A health check is under way: the runs that come together ask the driver once.
    checking: std::sync::atomic::AtomicBool,
    /// Counts the VMs the driver made: a check whose `verify` began before one of them was
    /// made has older evidence than that VM, and its "no VM" answer is dropped.
    made: AtomicU64,
    /// Counts the health checks that ended, for those that wait for one.
    checks: tokio::sync::watch::Sender<u64>,
}

/// The VMs whose delete failed, by id, and whether a task tries them again now.
#[derive(Default)]
struct Left {
    vms: HashMap<String, LeftVm>,
    retrying: bool,
}

/// A VM whose delete failed: the host label its delete names, its [`VM_LABEL`], how often it
/// was tried again, and when it is tried next.
struct LeftVm {
    host: String,
    label: Option<String>,
    tries: u32,
    next: tokio::time::Instant,
}

/// What a health check found.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Health {
    /// `verify` answered: the driver can make VMs, or is full.
    Clean,
    /// `verify` answered with problems and no room: the cause.
    NoVms(String),
    /// `verify` failed or did not answer in the driver's own time: this tells nothing.
    Unknown(String),
}

/// Why the driver can make no VM, as its last `verify` found it, and when that was.
struct NoVms {
    cause: String,
    checked: tokio::time::Instant,
}

/// Whether a driver's `verify` says that it can make no VM now: it reports problems and has no
/// room (`maxVms` 0, or none named). A driver with problems and room still makes VMs (one node
/// broken beside a healthy one with free slots), and one with no problem and no room is full,
/// which the host's own slots already bound. The cause is the problems, on one line and cut to
/// [`CAUSE_MAX`].
pub fn no_vms_cause(verified: &Verified) -> Option<String> {
    (!verified.problems.is_empty() && verified.max_vms.unwrap_or(0) == 0)
        .then(|| crate::util::one_line(&verified.problems.join("; "), CAUSE_MAX))
}

impl std::fmt::Debug for Vms {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Vm({})", self.options.template)
    }
}

impl Vms {
    pub fn new(options: VmOptions) -> Arc<Vms> {
        let driver = Driver::new(options.driver.clone());
        Arc::new(Vms {
            options,
            driver,
            live: Mutex::new(HashSet::new()),
            left: Mutex::new(Left::default()),
            warned: Mutex::new(HashSet::new()),
            no_vms: Mutex::new(None),
            no_vms_since: AtomicU64::new(0),
            no_vms_report: tokio::sync::watch::Sender::new(None),
            made: AtomicU64::new(0),
            checking: std::sync::atomic::AtomicBool::new(false),
            checks: tokio::sync::watch::Sender::new(0),
        })
    }

    // ----- whether the driver can make a VM ----------------------------------------------
    //
    // A create that fails may be the run's own (its user data) or the driver's: a node that
    // reports a problem, such as a node whose firewall table is missing, makes no VM for any
    // run. The driver protocol does not say which, so after a failed create the host asks the
    // driver's `verify`. Only an answer with problems and no room puts the host into the state
    // where it takes no new run: it rejects each with the cause (`run.rejected`, which the
    // server shows as the run's queue reason and dispatches again later) instead of failing it.
    // It asks again before a rejection and on its own every `recheck`, and takes runs again
    // once the answer is clean or a create succeeds. A `verify` that fails or does not answer
    // changes nothing either way. No admission and no failed run's report waits longer than
    // `check_wait` for the driver: the check goes on by itself. Each change is logged once.

    /// Asks the driver's `verify`, within the driver's own [`VERIFY_TIMEOUT`].
    async fn health(&self) -> Health {
        match self.driver.verify(&self.options.template).await {
            Ok(verified) => no_vms_cause(&verified).map_or(Health::Clean, Health::NoVms),
            Err(e) => Health::Unknown(format!("{e:#}")),
        }
    }

    /// The driver's state as it changes: the cause while it can make no VM, `None` while it can.
    pub fn no_vms_watch(&self) -> tokio::sync::watch::Receiver<Option<String>> {
        self.no_vms_report.subscribe()
    }

    /// Notes what a health check found, logs a change of the state, and passes the state on to
    /// the host's report: when it is set, when its cause changes, and when it clears.
    fn record(self: &Arc<Vms>, health: Health) {
        self.record_state(health);
        // Under the state's lock, so that two checks that end together publish in order.
        let no_vms = lock(&self.no_vms);
        let now = no_vms.as_ref().map(|known| known.cause.clone());
        self.no_vms_report.send_if_modified(|reported| {
            let changed = *reported != now;
            *reported = now;
            changed
        });
        drop(no_vms);
    }

    fn record_state(self: &Arc<Vms>, health: Health) {
        let mut no_vms = lock(&self.no_vms);
        let template = &self.options.template;
        match (no_vms.as_mut(), health) {
            (None, Health::NoVms(cause)) => {
                tracing::warn!(
                    %template,
                    %cause,
                    "the VM driver can make no VM: this host rejects new runs until the driver's verify is clean again"
                );
                *no_vms = Some(NoVms {
                    cause,
                    checked: tokio::time::Instant::now(),
                });
                drop(no_vms);
                let since = self.no_vms_since.fetch_add(1, Ordering::Relaxed) + 1;
                self.spawn_recheck(since);
            }
            (Some(known), Health::NoVms(cause)) => {
                known.cause = cause;
                known.checked = tokio::time::Instant::now();
            }
            (Some(_), Health::Clean) => {
                *no_vms = None;
                tracing::info!(%template, "the VM driver can make VMs again: this host takes new runs");
            }
            (known, Health::Unknown(why)) => {
                // Not an answer: the state stays as it was, and the next check tries again.
                if let Some(known) = known {
                    known.checked = tokio::time::Instant::now();
                }
                tracing::debug!(%template, error = %why, "the VM driver's verify gave no answer; whether it can make VMs is unchanged");
            }
            (None, Health::Clean) => {}
        }
    }

    /// Starts a health check unless one is under way, and waits for a check to end, at most
    /// `wait`. The check goes on after the wait, and records its answer when it comes.
    async fn check_within(self: &Arc<Vms>, wait: Duration) {
        let mut ended = self.checks.subscribe();
        let seen = *ended.borrow_and_update();
        if !self.checking.swap(true, Ordering::SeqCst) {
            let vms = self.clone();
            tokio::spawn(async move {
                let made = vms.made.load(Ordering::SeqCst);
                let health = match vms.health().await {
                    // A VM was made while `verify` ran: the driver can make VMs.
                    Health::NoVms(_) if vms.made.load(Ordering::SeqCst) != made => {
                        Health::Unknown("the driver made a VM while its verify ran".into())
                    }
                    health => health,
                };
                vms.record(health);
                vms.checking.store(false, Ordering::SeqCst);
                vms.checks.send_modify(|n| *n += 1);
            });
        }
        let _ = tokio::time::timeout(wait, ended.wait_for(|n| *n > seen)).await;
    }

    /// While the driver can make no VM, asks it again every `recheck`, so that the host takes
    /// runs again soon after it is fixed even when no run comes.
    fn spawn_recheck(self: &Arc<Vms>, since: u64) {
        let (vms, every) = (Arc::downgrade(self), self.options.recheck);
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(every).await;
                let Some(vms) = vms.upgrade() else { return };
                let current = || {
                    lock(&vms.no_vms).is_some() && vms.no_vms_since.load(Ordering::Relaxed) == since
                };
                if !current() {
                    return;
                }
                // Bounded by the driver's own time for `verify`.
                vms.check_within(VERIFY_TIMEOUT + Duration::from_secs(5))
                    .await;
                if !current() {
                    return;
                }
            }
        });
    }

    /// Why the host takes no new run now: its driver can make no VM (a create failed, and the
    /// driver's `verify` then answered with problems and no room). Unless the driver was asked
    /// a moment ago, it is asked again first, for at most `check_wait`; then the answer is what
    /// is known. `None`: the host takes runs, and asks nothing.
    pub async fn refusal(self: &Arc<Vms>) -> Option<String> {
        let fresh = RECHECK_FRESH.min(self.options.recheck);
        let stale = lock(&self.no_vms)
            .as_ref()
            .map(|known| known.checked.elapsed() >= fresh)?;
        if stale {
            self.check_within(self.options.check_wait).await;
        }
        lock(&self.no_vms).as_ref().map(|known| {
            format!(
                "the VM driver can make no VM now ({}); this runner host takes runs again once the driver's verify is clean",
                known.cause
            )
        })
    }

    /// The VM's image against this host's build, once its bridge has connected: an error when
    /// they differ and `--vm-image-version refuse`, a warning (once for each build) otherwise.
    pub fn check_image(&self, vm: &str, reported: Option<&str>) -> Result<()> {
        let own = crate::images::build_id();
        let Some(problem) = image_mismatch(own, reported) else {
            return Ok(());
        };
        match self.options.image_version {
            ImageVersion::Refuse => bail!(
                "{problem}; rebuild the VM image (`{}`) with this host's binary, or start this host with --vm-image-version warn",
                self.options.template
            ),
            ImageVersion::Warn => {
                if lock(&self.warned).insert(reported.map(String::from)) {
                    tracing::warn!(
                        vm,
                        template = %self.options.template,
                        "{problem}; rebuild the VM image with this host's binary (--vm-image-version refuse fails such runs)"
                    );
                }
                Ok(())
            }
        }
    }

    pub fn driver(&self) -> &Driver {
        &self.driver
    }

    /// At `run`'s start: the driver speaks this protocol, the template's setup keeps the
    /// contract, and the host can have as many VMs as its ceiling. The driver's `maxVms` is the
    /// room that is left: the free slots of the nodes (`node`), or what the account's quota
    /// still allows (a cloud's driver). The VMs that already carry this host's label (`own`,
    /// the runner host's id from `state.json`; `None` before its first `welcome`) hold part of
    /// that room for this host: the runs it takes back, its ready VMs, and the leftovers it
    /// deletes after its hello. They count as this host's own, so a host that restarts with its
    /// VMs still there is not refused for the room they hold. Returns the driver's `hello`,
    /// its `verify`, and the number of such VMs.
    pub async fn check(
        &self,
        max_concurrent_runs: u32,
        own: Option<&str>,
    ) -> Result<(DriverHello, Verified, u32)> {
        let hello = self.driver.hello().await?;
        let verified = self.driver.verify(&self.options.template).await?;
        if !verified.problems.is_empty() {
            bail!(
                "the VM template `{}` breaks the contract of a sandbox VM (the VM driver `{}` found: {}); fix the setup, or name another template",
                self.options.template,
                hello.driver,
                verified.problems.join("; ")
            );
        }
        let mut held = 0;
        if let Some(max) = verified.max_vms
            && max < max_concurrent_runs
        {
            // Only now: a host with room enough asks the driver for nothing more.
            let listed = match own {
                Some(host) => Some(self.own_vms(host).await),
                None => None,
            };
            let why = match listed {
                Some(Ok(count)) => {
                    held = count;
                    format!("and this host holds {count} VM(s) of its own")
                }
                Some(Err(e)) => {
                    format!("and this host's own VMs cannot be listed ({e:#})")
                }
                None => "and this host has none of its own yet".to_string(),
            };
            if max.saturating_add(held) < max_concurrent_runs {
                bail!(
                    "--max-concurrent-runs is {max_concurrent_runs}, but the VM driver `{}` has room for {max} more VM(s), {why}; lower --max-concurrent-runs, or give the driver more room (more nodes or slots, or a higher limit of the account)",
                    hello.driver
                );
            }
            tracing::info!(
                driver = %hello.driver,
                room = max,
                own = held,
                max_concurrent_runs,
                "the VM driver has less room than the ceiling, but this host's own VMs hold the rest"
            );
        }
        Ok((hello, verified, held))
    }

    /// How many VMs that are not gone carry this host's label, where the driver's `maxVms`
    /// counts room. With `--vm-driver node` that is on the nodes that offer the template: the
    /// driver's `verify` sums the free slots of those alone, and a VM's id names its node
    /// (`<node>:<id>`), so a VM on a node of other templates is not counted. A node that does
    /// not answer is in neither: `verify` does not count it, and `list` does not list its VMs.
    /// A node that reports a problem refuses the start before this counts.
    async fn own_vms(&self, host: &str) -> Result<u32> {
        let nodes = match &self.options.driver.builtin {
            Some(Builtin::Node { config }) => {
                let config = crate::node::NodeConfig::load(config)?;
                Some(
                    config
                        .nodes
                        .into_iter()
                        .filter(|node| node.templates.contains(&self.options.template))
                        .map(|node| node.name)
                        .collect::<HashSet<String>>(),
                )
            }
            _ => None,
        };
        let labels = BTreeMap::from([(HOST_LABEL.to_string(), host.to_string())]);
        let count = self
            .driver
            .list(&labels)
            .await?
            .iter()
            // A driver that lists more than asked: only this host's count.
            .filter(|vm| vm.labels.get(HOST_LABEL).map(String::as_str) == Some(host))
            .filter(|vm| vm.state != VmState::Gone)
            .filter(|vm| {
                nodes.as_ref().is_none_or(|nodes| {
                    vm.id
                        .split_once(':')
                        .is_some_and(|(node, _)| nodes.contains(node))
                })
            })
            .count();
        Ok(u32::try_from(count).unwrap_or(u32::MAX))
    }

    /// The labels of a VM: the host's, and the VM's own id.
    pub fn labels(host: &str, vm: &str) -> BTreeMap<String, String> {
        BTreeMap::from([
            (HOST_LABEL.to_string(), host.to_string()),
            (VM_LABEL.to_string(), vm.to_string()),
        ])
    }

    /// Creates a VM. The VM it returns is deleted when it is dropped, unless
    /// [`VmGuard::delete`] did that already.
    pub async fn create(
        self: &Arc<Vms>,
        name: &str,
        user_data: &UserData,
        labels: &BTreeMap<String, String>,
    ) -> Result<VmGuard> {
        let label = labels.get(VM_LABEL).cloned();
        if let Some(label) = &label {
            lock(&self.live).insert(label.clone());
        }
        let id = match self
            .driver
            .create(&self.options.template, name, user_data, labels)
            .await
        {
            Ok(id) => id,
            Err(e) => {
                // A create that failed may still have left a VM: the cleanup takes it.
                if let Some(label) = &label {
                    lock(&self.live).remove(label);
                }
                // Before the run fails, for at most `check_wait`: the next run then finds the
                // host's state. A slower check records its answer when it comes.
                self.check_within(self.options.check_wait).await;
                return Err(e);
            }
        };
        // The driver made a VM: it can. Counted first, so that a check under way that answers
        // "no VM" after this drops its answer.
        self.made.fetch_add(1, Ordering::SeqCst);
        if lock(&self.no_vms).is_some() {
            self.record(Health::Clean);
        }
        tracing::info!(vm = %id, name, template = %self.options.template, "sandbox VM created");
        Ok(VmGuard {
            vms: self.clone(),
            id,
            host: labels.get(HOST_LABEL).cloned().unwrap_or_default(),
            label,
            reach: None,
            deleted: false,
        })
    }

    /// A VM of the process this host restarted from, whose run it takes back (ADR-0101): held
    /// from now on, so the leftover cleanup leaves it, and deleted as any other.
    pub fn adopt(
        self: &Arc<Vms>,
        id: &str,
        host: &str,
        label: Option<&str>,
        reach: Reach,
    ) -> VmGuard {
        if let Some(label) = label {
            lock(&self.live).insert(label.to_string());
        }
        VmGuard {
            vms: self.clone(),
            id: id.to_string(),
            host: host.to_string(),
            label: label.map(str::to_string),
            reach: Some(reach),
            deleted: false,
        }
    }

    /// How many of this process's VMs are not deleted yet.
    pub fn live_count(&self) -> usize {
        lock(&self.live).len()
    }

    /// Waits until the VM is up and has an address, until `deadline`, which bounds each of the
    /// driver's answers too: where to dial its bridge, with the port the driver named, if any.
    pub async fn reach(&self, id: &str, deadline: tokio::time::Instant) -> Result<Reach> {
        loop {
            let Ok(described) = tokio::time::timeout_at(deadline, self.driver.describe(id)).await
            else {
                bail!(
                    "the VM {id} did not come up within {} s",
                    self.options.start_timeout.as_secs()
                );
            };
            match described {
                Ok(vm) => match vm.state {
                    VmState::Running => {
                        if vm.port == Some(0) {
                            bail!("the VM driver described the VM {id} with the port 0");
                        }
                        if let Some(address) = vm.address.filter(|a| !a.is_empty()) {
                            return Ok(Reach {
                                address,
                                port: vm.port,
                            });
                        }
                    }
                    VmState::Starting => {}
                    VmState::Gone => bail!("the VM {id} is gone before it came up"),
                    VmState::Failed => bail!("the provider could not start the VM {id}"),
                },
                Err(e) => {
                    tracing::debug!(vm = %id, error = %format!("{e:#}"), "cannot describe the VM yet")
                }
            }
            if tokio::time::Instant::now() + self.options.poll > deadline {
                bail!(
                    "the VM {id} did not come up within {} s",
                    self.options.start_timeout.as_secs()
                );
            }
            tokio::time::sleep(self.options.poll).await;
        }
    }

    /// Resolves once the provider reports the VM gone or failed.
    pub async fn ended(&self, id: &str) -> String {
        loop {
            tokio::time::sleep(self.options.poll).await;
            if let Ok(vm) = self.driver.describe(id).await {
                match vm.state {
                    VmState::Gone => return "the VM is gone".to_string(),
                    VmState::Failed => return "the provider reports the VM failed".to_string(),
                    _ => {}
                }
            }
        }
    }

    /// Whether the VM `id` ended under its run, asked once when the run's bridge session ended
    /// without the adapter's exit status: the cause when the driver describes the VM as gone or
    /// failed (its VMM died, its node rebooted), or cannot describe it within [`LOST_WAIT`] (its
    /// node does not answer). `None` while the driver describes the VM as up.
    pub async fn lost(&self, id: &str) -> Option<String> {
        match tokio::time::timeout(LOST_WAIT, self.driver.describe(id)).await {
            Ok(Ok(vm)) => match vm.state {
                VmState::Gone => Some("the VM is gone".to_string()),
                VmState::Failed => Some("the provider reports the VM failed".to_string()),
                VmState::Starting | VmState::Running => None,
            },
            Ok(Err(e)) => Some(format!("the VM driver cannot describe the VM: {e:#}")),
            Err(_) => Some(format!(
                "the VM driver did not describe the VM within {} s",
                LOST_WAIT.as_secs()
            )),
        }
    }

    /// Deletes the VM `id`; its [`VM_LABEL`] `label` is no longer live then, whatever the
    /// outcome: a VM whose delete failed is tried again later ([`Vms::delete_later`]), and is
    /// the leftover cleanup's too.
    async fn delete_labelled(self: &Arc<Vms>, id: &str, host: &str, label: Option<&str>) -> bool {
        let deleted = self.delete(id, host).await;
        if let Some(label) = label {
            lock(&self.live).remove(label);
        }
        if !deleted {
            self.delete_later(id, host, label);
        }
        deleted
    }

    /// A VM whose delete failed (its node was down, or its driver did not answer): the host
    /// tries it again on its own, first after `delete_retry` and then after twice the wait
    /// before, up to [`DELETE_RETRY_MAX`], until the delete succeeds. One task tries every
    /// such VM, one delete at a time, and ends when none is left. Only VMs whose delete this
    /// process asked for are tried, never one that it holds: a run's, a ready one, or one whose
    /// run it took back.
    fn delete_later(self: &Arc<Vms>, id: &str, host: &str, label: Option<&str>) {
        let mut left = lock(&self.left);
        left.vms.entry(id.to_string()).or_insert_with(|| LeftVm {
            host: host.to_string(),
            label: label.map(str::to_string),
            tries: 0,
            next: tokio::time::Instant::now() + self.options.delete_retry,
        });
        if left.retrying {
            return;
        }
        left.retrying = true;
        drop(left);
        let vms = Arc::downgrade(self);
        tokio::spawn(async move {
            loop {
                let next = {
                    let Some(vms) = vms.upgrade() else { return };
                    let mut left = lock(&vms.left);
                    let Some(next) = left.vms.values().map(|vm| vm.next).min() else {
                        left.retrying = false;
                        return;
                    };
                    next
                };
                tokio::time::sleep_until(next).await;
                let Some(vms) = vms.upgrade() else { return };
                vms.delete_left().await;
            }
        });
    }

    /// Tries the delete of each VM of [`Vms::delete_later`] whose time has come, once.
    async fn delete_left(&self) {
        let now = tokio::time::Instant::now();
        let due: Vec<(String, String, Option<String>)> = lock(&self.left)
            .vms
            .iter()
            .filter(|(_, vm)| vm.next <= now)
            .map(|(id, vm)| (id.clone(), vm.host.clone(), vm.label.clone()))
            .collect();
        for (id, host, label) in due {
            if label.as_ref().is_some_and(|l| lock(&self.live).contains(l)) {
                // Held by this process again: not a leftover.
                lock(&self.left).vms.remove(&id);
                continue;
            }
            let labels = BTreeMap::from([(HOST_LABEL.to_string(), host)]);
            let deleted = self.driver.delete(&id, &labels).await;
            let mut left = lock(&self.left);
            let Some(vm) = left.vms.get_mut(&id) else {
                continue;
            };
            vm.tries = vm.tries.saturating_add(1);
            let tries = vm.tries;
            match deleted {
                Ok(()) => {
                    left.vms.remove(&id);
                    tracing::info!(vm = %id, tries, "sandbox VM deleted, after a delete that had failed");
                }
                Err(e) => {
                    let wait = delete_retry_wait(self.options.delete_retry, tries);
                    vm.next = tokio::time::Instant::now() + wait;
                    let error = format!("{e:#}");
                    match tries.cmp(&DELETE_RETRIES) {
                        std::cmp::Ordering::Less => {
                            tracing::warn!(vm = %id, tries, retry_in_s = wait.as_secs(), %error, "cannot delete the sandbox VM yet");
                        }
                        std::cmp::Ordering::Equal => {
                            tracing::error!(vm = %id, tries, retry_in_s = wait.as_secs(), %error, "the sandbox VM is still left; the host keeps trying its delete, and logs the next tries at the debug level");
                        }
                        std::cmp::Ordering::Greater => {
                            tracing::debug!(vm = %id, tries, %error, "cannot delete the sandbox VM yet");
                        }
                    }
                }
            }
        }
    }

    async fn delete(&self, id: &str, host: &str) -> bool {
        let labels = BTreeMap::from([(HOST_LABEL.to_string(), host.to_string())]);
        for attempt in 1..=DELETE_ATTEMPTS {
            match self.driver.delete(id, &labels).await {
                Ok(()) => {
                    tracing::info!(vm = %id, "sandbox VM deleted");
                    return true;
                }
                Err(e) => {
                    tracing::warn!(vm = %id, attempt, error = %format!("{e:#}"), "cannot delete the sandbox VM");
                    if attempt < DELETE_ATTEMPTS {
                        tokio::time::sleep(Duration::from_secs(2)).await;
                    }
                }
            }
        }
        tracing::error!(
            vm = %id,
            retry_in_s = self.options.delete_retry.as_secs(),
            "the sandbox VM is left; the host tries its delete again later, and deletes it with its leftovers at its next connection"
        );
        false
    }

    /// Deletes this host's VMs that this process does not hold: those whose delete failed,
    /// and those of an earlier process of this host. A VM of a host before ADR-0098, which
    /// has no [`VM_LABEL`], is kept while its run is in `live`. A delete that fails here is
    /// tried again later ([`Vms::delete_later`]).
    pub async fn cleanup_orphans(
        self: &Arc<Vms>,
        host: &str,
        live: &HashSet<Uuid>,
    ) -> Result<usize> {
        let labels = BTreeMap::from([(HOST_LABEL.to_string(), host.to_string())]);
        let mut deleted = 0;
        for vm in self.driver.list(&labels).await? {
            if vm.labels.get(HOST_LABEL).map(String::as_str) != Some(host) {
                // A driver that lists more than asked: not this host's to delete.
                continue;
            }
            if vm.state == VmState::Gone {
                continue;
            }
            let run = vm
                .labels
                .get(RUN_LABEL)
                .and_then(|r| r.parse::<Uuid>().ok());
            let held = match vm.labels.get(VM_LABEL) {
                Some(label) => lock(&self.live).contains(label),
                None => run.is_some_and(|run| live.contains(&run)),
            };
            if held {
                continue;
            }
            tracing::info!(vm = %vm.id, run = ?run, "deleting a leftover sandbox VM");
            if self.delete(&vm.id, host).await {
                deleted += 1;
            } else {
                self.delete_later(&vm.id, host, vm.labels.get(VM_LABEL).map(String::as_str));
            }
        }
        Ok(deleted)
    }
}

/// The wait before the next try of a failed delete that was tried again `tries` times already:
/// `first`, doubled with each try, up to [`DELETE_RETRY_MAX`].
fn delete_retry_wait(first: Duration, tries: u32) -> Duration {
    first
        .saturating_mul(2u32.saturating_pow(tries))
        .min(DELETE_RETRY_MAX)
}

/// A VM: deleted by [`VmGuard::delete`], or when it is dropped.
pub struct VmGuard {
    vms: Arc<Vms>,
    pub id: String,
    /// The host label it was created with, which its delete names.
    host: String,
    /// Its [`VM_LABEL`].
    label: Option<String>,
    /// Where it is reached, once the provider has said.
    reach: Option<Reach>,
    deleted: bool,
}

impl VmGuard {
    pub fn vms(&self) -> &Arc<Vms> {
        &self.vms
    }

    /// The VM's address, once the provider has said ([`VmGuard::set_reach`]).
    pub fn address(&self) -> Option<&str> {
        self.reach.as_ref().map(|r| r.address.as_str())
    }

    /// The port the driver named for the VM's bridge, if any.
    pub fn port(&self) -> Option<u16> {
        self.reach.as_ref().and_then(|r| r.port)
    }

    pub fn set_reach(&mut self, reach: Reach) {
        self.reach = Some(reach);
    }

    /// What a host that restarted needs to take the VM's run back: the port too, so that it
    /// dials the VM's own.
    pub fn record(&self) -> Option<crate::takeback::SandboxRecord> {
        let reach = self.reach.clone()?;
        Some(crate::takeback::SandboxRecord::Vm {
            id: self.id.clone(),
            host: self.host.clone(),
            label: self.label.clone(),
            address: reach.address,
            port: reach.port,
        })
    }

    /// Deletes the VM. Marked deleted only once the delete returned: a delete cancelled on the
    /// way (its task aborted) leaves it to `Drop`, which deletes it again.
    pub async fn delete(&mut self) {
        if !self.deleted {
            self.vms
                .delete_labelled(&self.id, &self.host, self.label.as_deref())
                .await;
            self.deleted = true;
        }
    }
}

impl Drop for VmGuard {
    fn drop(&mut self) {
        if self.deleted {
            return;
        }
        // A run's task that ends without the delete (it was aborted), or a VM that no run holds:
        // the VM goes anyway.
        let (vms, id, host, label) = (
            self.vms.clone(),
            self.id.clone(),
            self.host.clone(),
            self.label.take(),
        );
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn(async move {
                vms.delete_labelled(&id, &host, label.as_deref()).await;
            });
        } else {
            if let Some(label) = &label {
                lock(&vms.live).remove(label);
            }
            tracing::error!(vm = %id, "the sandbox VM is left; the host deletes it with its leftovers at its next connection");
        }
    }
}

/// The user data of a VM: its image and the container's environment (`settings`, the
/// sandbox's own, and the bridge's, which listens on `port`).
pub fn user_data(
    image: &str,
    settings: &[(String, String)],
    bootstrap: &[(String, String)],
    port: u16,
) -> UserData {
    let mut env: BTreeMap<String, String> = settings.iter().cloned().collect();
    for (key, value) in bootstrap {
        // The VM listens; it dials nothing.
        if key != tinfirm_bridge::ENV_ADDR {
            env.insert(key.clone(), value.clone());
        }
    }
    env.insert(
        tinfirm_bridge::ENV_LISTEN.to_string(),
        format!("0.0.0.0:{port}"),
    );
    UserData {
        tinfirm: USER_DATA_VERSION,
        image: image.to_string(),
        env,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_failed_delete_is_tried_again_less_and_less_often_and_for_a_bounded_time() {
        let waits: Vec<u64> = (0..DELETE_RETRIES)
            .map(|tries| delete_retry_wait(DELETE_RETRY, tries).as_secs())
            .collect();
        assert_eq!(
            waits,
            [15, 30, 60, 120, 240, 300, 300, 300, 300, 300, 300, 300]
        );
        // About 40 minutes of tries that the host warns of; then one try per 5 minutes.
        assert_eq!(waits.iter().sum::<u64>(), 2565);
        assert_eq!(delete_retry_wait(DELETE_RETRY, u32::MAX), DELETE_RETRY_MAX);
    }

    #[test]
    fn a_vm_image_of_another_build_warns_and_is_refused_only_when_asked() {
        let own = crate::images::build_id();
        assert_eq!(image_mismatch(own, Some(own)), None);
        let other = image_mismatch(own, Some("0.2.0+images.000000000000")).unwrap();
        assert!(
            other.contains("0.2.0+images.000000000000") && other.contains(own),
            "{other}"
        );
        assert!(
            image_mismatch(own, None)
                .unwrap()
                .contains("names no build")
        );

        let template = "tinfirm-sandbox".to_string();
        let mut options = VmOptions::new(DriverCommand::parse("digitalocean").unwrap(), template);
        let warn = Vms::new(options.clone());
        assert!(warn.check_image("vm-1", Some(own)).is_ok());
        assert!(warn.check_image("vm-1", Some("0.2.0")).is_ok());
        assert!(warn.check_image("vm-1", None).is_ok());
        assert_eq!(lock(&warn.warned).len(), 2);
        options.image_version = ImageVersion::Refuse;
        let refuse = Vms::new(options);
        assert!(refuse.check_image("vm-1", Some(own)).is_ok());
        let error = refuse
            .check_image("vm-1", Some("0.2.0"))
            .unwrap_err()
            .to_string();
        assert!(
            error.contains("rebuild the VM image (`tinfirm-sandbox`)"),
            "{error}"
        );
        assert!(refuse.check_image("vm-1", None).is_err());
    }

    #[test]
    fn a_vm_is_dialed_at_the_port_its_driver_names_or_at_the_vm_port() {
        let vm: Vm = serde_json::from_value(json!({
            "id": "vm-1", "state": "running", "address": "10.0.0.20", "port": 20003
        }))
        .unwrap();
        assert_eq!(vm.port, Some(20003));
        let without: Vm = serde_json::from_value(json!({
            "id": "vm-1", "state": "running", "address": "10.0.0.20"
        }))
        .unwrap();
        assert_eq!(without.port, None);
        let reach = |port| Reach {
            address: "10.0.0.20".into(),
            port,
        };
        assert_eq!(reach(Some(20003)).dial(7443), "10.0.0.20:20003");
        assert_eq!(reach(None).dial(7443), "10.0.0.20:7443");
        let v6 = Reach {
            address: "fd00::5".into(),
            port: Some(20003),
        };
        assert_eq!(v6.dial(7443), "[fd00::5]:20003");
        assert!(
            serde_json::from_value::<Vm>(json!({"id": "x", "state": "running", "port": 70000}))
                .is_err()
        );
    }

    #[test]
    fn driver_commands_are_the_builtin_one_or_a_command_line() {
        let builtin = DriverCommand::parse("digitalocean").unwrap();
        assert_eq!(builtin.args, ["vm-driver", "digitalocean"]);
        assert_eq!(builtin.program, std::env::current_exe().unwrap());
        // The driver for sandbox nodes is this binary's too, and in the core: no paid feature.
        assert!(builtin.elastic());
        let node = DriverCommand::parse(" node ").unwrap();
        let config = crate::node::config_path();
        assert_eq!(
            node.args,
            ["vm-driver", "node", "--config", &config.to_string_lossy()]
        );
        assert_eq!(node.program, std::env::current_exe().unwrap());
        assert_eq!(node.builtin, Some(Builtin::Node { config }));
        assert!(!node.elastic());
        let own = DriverCommand::parse("  /opt/drivers/hetzner --project sandboxes ").unwrap();
        assert_eq!(own.program, PathBuf::from("/opt/drivers/hetzner"));
        assert_eq!(own.args, ["--project", "sandboxes"]);
        assert!(DriverCommand::parse("   ").is_err());
    }

    #[test]
    fn probe_checks_parse_and_bad_ones_are_refused() {
        assert_eq!(
            parse_probe("tcp:10.0.0.5:5432").unwrap(),
            ProbeCheck::Tcp {
                addr: "10.0.0.5:5432".into()
            }
        );
        assert_eq!(
            parse_probe("dns:internal.example").unwrap(),
            ProbeCheck::Dns {
                name: "internal.example".into()
            }
        );
        assert_eq!(
            parse_probe("http://10.0.0.9/admin").unwrap(),
            ProbeCheck::Http {
                addr: "10.0.0.9:80".into(),
                path: "/admin".into(),
                headers: Vec::new()
            }
        );
        assert_eq!(
            parse_probe("http://10.0.0.9:8080").unwrap(),
            ProbeCheck::Http {
                addr: "10.0.0.9:8080".into(),
                path: "/".into(),
                headers: Vec::new()
            }
        );
        for bad in [
            "tcp:10.0.0.5",
            "tcp::80",
            "dns:",
            "http://",
            "udp:1.1.1.1:53",
            "x",
        ] {
            assert!(parse_probe(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn the_default_probe_covers_the_internet_and_the_metadata_credentials() {
        let names: Vec<String> = default_probe().iter().map(ProbeCheck::name).collect();
        assert!(names.iter().any(|n| n.contains("1.1.1.1:443")), "{names:?}");
        assert!(names.iter().any(|n| n.contains("example.com")), "{names:?}");
        for path in [
            "security-credentials",
            "service-accounts",
            "identity/oauth2",
        ] {
            assert!(names.iter().any(|n| n.contains(path)), "{names:?}");
        }
    }

    #[test]
    fn user_data_has_the_bridge_listen_and_no_address_to_dial() {
        let data = user_data(
            "tinfirm-runner-claude:local",
            &[(
                "TINFIRM_SANDBOX_NPM_REGISTRY".into(),
                "https://npm.example".into(),
            )],
            &[
                (tinfirm_bridge::ENV_ADDR.into(), "10.0.0.1:4000".into()),
                (tinfirm_bridge::ENV_RUN.into(), "run.1".into()),
                (tinfirm_bridge::ENV_TICKET.into(), "ab".repeat(32)),
                (tinfirm_bridge::ENV_PIN.into(), "cd".repeat(32)),
            ],
            7443,
        );
        assert_eq!(data.tinfirm, USER_DATA_VERSION);
        assert_eq!(data.image, "tinfirm-runner-claude:local");
        assert_eq!(data.env[tinfirm_bridge::ENV_LISTEN], "0.0.0.0:7443");
        assert!(!data.env.contains_key(tinfirm_bridge::ENV_ADDR));
        assert_eq!(data.env[tinfirm_bridge::ENV_RUN], "run.1");
        assert_eq!(
            data.env["TINFIRM_SANDBOX_NPM_REGISTRY"],
            "https://npm.example"
        );
        let json = serde_json::to_string(&data).unwrap();
        assert_eq!(serde_json::from_str::<UserData>(&json).unwrap(), data);
    }

    #[test]
    fn a_driver_makes_no_vm_when_it_reports_problems_and_no_room() {
        let verified = |problems: &[&str], max_vms: Option<u32>| Verified {
            problems: problems.iter().map(|p| p.to_string()).collect(),
            max_vms,
            checked: Vec::new(),
        };
        let table = "node node-1: the node's own firewall table is not loaded";
        assert_eq!(
            no_vms_cause(&verified(&[table], Some(0))).as_deref(),
            Some(table)
        );
        assert!(no_vms_cause(&verified(&[table, "x"], None)).is_some());
        // One node broken beside one with room: VMs are still made.
        assert_eq!(no_vms_cause(&verified(&[table], Some(1))), None);
        // Healthy and full: the host's slots bound that, not this.
        assert_eq!(no_vms_cause(&verified(&[], Some(0))), None);
        assert_eq!(no_vms_cause(&verified(&[], None)), None);
        let long = "y".repeat(10 * CAUSE_MAX);
        let cause = no_vms_cause(&verified(&[&long], Some(0))).unwrap();
        assert!(cause.len() <= CAUSE_MAX, "{}", cause.len());
    }

    #[tokio::test]
    async fn only_an_answer_of_verify_changes_whether_the_host_takes_runs() {
        let mut options = VmOptions::new(
            DriverCommand {
                program: PathBuf::from("sh"),
                args: vec!["-c".into(), "cat > /dev/null".into()],
                env: Vec::new(),
                builtin: None,
            },
            "sandbox".into(),
        );
        options.recheck = Duration::from_secs(3600);
        let vms = Vms::new(options);
        let cause = || lock(&vms.no_vms).as_ref().map(|known| known.cause.clone());
        // No answer: nothing changes, either way.
        vms.record(Health::Unknown("no answer".into()));
        assert_eq!(cause(), None);
        assert!(vms.refusal().await.is_none());
        vms.record(Health::NoVms("the table is missing".into()));
        assert_eq!(cause().as_deref(), Some("the table is missing"));
        vms.record(Health::Unknown("no answer".into()));
        assert_eq!(cause().as_deref(), Some("the table is missing"));
        // A refusal right after a check answers with what is known, without asking.
        let reason = vms.refusal().await.unwrap();
        assert!(reason.contains("the table is missing"), "{reason}");
        vms.record(Health::Clean);
        assert_eq!(cause(), None);
    }

    /// An input that holds `first`, then fails to read.
    struct Breaks(Option<&'static [u8]>);

    impl tokio::io::AsyncRead for Breaks {
        fn poll_read(
            mut self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
            buf: &mut tokio::io::ReadBuf<'_>,
        ) -> std::task::Poll<std::io::Result<()>> {
            std::task::Poll::Ready(match self.0.take() {
                Some(first) => {
                    buf.put_slice(first);
                    Ok(())
                }
                None => Err(std::io::Error::other("the pipe broke")),
            })
        }
    }

    #[tokio::test]
    async fn a_read_error_of_the_input_fails_after_the_answers_under_way() {
        let (mut ours, theirs) = tokio::io::duplex(64 * 1024);
        let input = Breaks(Some(
            b"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\"}\n",
        ));
        let served = serve_driver(input, theirs, |method, _| async move {
            // Slower than the read error: the answer is still under way when it comes.
            tokio::time::sleep(Duration::from_millis(100)).await;
            Ok(json!({ "method": method }))
        })
        .await;
        let error = format!("{:#}", served.unwrap_err());
        assert!(error.contains("the pipe broke"), "{error}");
        let mut out = String::new();
        ours.read_to_string(&mut out).await.unwrap();
        let answer: Value = serde_json::from_str(out.trim()).unwrap();
        assert_eq!(answer["id"], 1);
        assert_eq!(answer["result"]["method"], "hello");
    }

    /// A driver in `sh`: answers each request after `delay` with its method's line of
    /// `answers`, by request id.
    fn sh_driver(script: &str) -> Driver {
        Driver::new(DriverCommand {
            program: PathBuf::from("sh"),
            args: vec!["-c".into(), script.into()],
            env: Vec::new(),
            builtin: None,
        })
    }

    /// Reads requests and answers each with `{"ok":METHOD}`, out of order for `slow`.
    const ECHO: &str = r#"
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  method=$(printf '%s' "$line" | sed -n 's/.*"method":"\([a-z]*\)".*/\1/p')
  case "$method" in
    slow) (sleep 0.3; printf '{"jsonrpc":"2.0","id":%s,"result":{"ok":"slow"}}\n' "$id") & ;;
    fail) printf '{"jsonrpc":"2.0","id":%s,"error":{"code":1,"message":"no such template"}}\n' "$id" ;;
    quit) exit 0 ;;
    *) echo "log line for $method" >&2; printf '{"jsonrpc":"2.0","id":%s,"result":{"ok":"%s"}}\n' "$id" "$method" ;;
  esac
done
"#;

    #[tokio::test]
    async fn the_driver_answers_in_any_order_and_its_errors_are_the_calls() {
        let driver = Arc::new(sh_driver(ECHO));
        let slow = {
            let driver = driver.clone();
            tokio::spawn(
                async move { driver.call("slow", json!({}), Duration::from_secs(5)).await },
            )
        };
        tokio::time::sleep(Duration::from_millis(50)).await;
        let fast = driver
            .call("fast", json!({}), Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(fast, json!({"ok": "fast"}));
        assert_eq!(slow.await.unwrap().unwrap(), json!({"ok": "slow"}));
        let error = driver
            .call("fail", json!({}), Duration::from_secs(5))
            .await
            .unwrap_err();
        assert!(
            format!("{error:#}").contains("no such template"),
            "{error:#}"
        );
    }

    #[tokio::test]
    async fn a_driver_that_exits_fails_its_calls_and_starts_again() {
        let driver = sh_driver(ECHO);
        let error = driver
            .call("quit", json!({}), Duration::from_secs(5))
            .await
            .unwrap_err();
        assert!(format!("{error:#}").contains("exited"), "{error:#}");
        // The next call starts it again.
        let again = driver
            .call("again", json!({}), Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(again, json!({"ok": "again"}));
    }

    #[tokio::test]
    async fn a_driver_that_does_not_answer_times_out() {
        let driver = sh_driver("cat > /dev/null");
        let error = driver
            .call("create", json!({}), Duration::from_millis(200))
            .await
            .unwrap_err();
        assert!(format!("{error:#}").contains("did not answer"), "{error:#}");
    }

    #[tokio::test]
    async fn a_driver_of_another_protocol_is_refused() {
        let driver = sh_driver(
            r#"read -r line; printf '{"jsonrpc":"2.0","id":1,"result":{"protocol":9,"driver":"future"}}\n'; cat >/dev/null"#,
        );
        let error = driver.hello().await.unwrap_err();
        assert!(format!("{error:#}").contains("protocol 9"), "{error:#}");
    }
}
