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
//! created for whichever run claims it (crate::pool, ADR-0098), the one waiting now or a later
//! one, and is deleted after that run.

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
/// The label of the run a VM was for, on the VMs of runner hosts before ready VMs (ADR-0098):
/// their leftovers are still recognised by it.
pub const RUN_LABEL: &str = "tinfirm-run";
/// `--vm-driver digitalocean`: this binary's own driver (`vm-driver digitalocean`).
pub const BUILTIN_DIGITALOCEAN: &str = "digitalocean";

/// How long one call of the driver may take, by method.
const HELLO_TIMEOUT: Duration = Duration::from_secs(30);
const VERIFY_TIMEOUT: Duration = Duration::from_secs(120);
const CREATE_TIMEOUT: Duration = Duration::from_secs(180);
const DESCRIBE_TIMEOUT: Duration = Duration::from_secs(60);
const DELETE_TIMEOUT: Duration = Duration::from_secs(120);
const LIST_TIMEOUT: Duration = Duration::from_secs(120);
/// How often a delete is tried before the VM is left to the next cleanup.
const DELETE_ATTEMPTS: u32 = 3;
/// The longest line the driver may answer with.
const MAX_LINE: u64 = 4 * 1024 * 1024;

/// The program that is the driver, with its arguments and the environment it gets in addition
/// to the host's.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DriverCommand {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub env: Vec<(String, String)>,
}

impl DriverCommand {
    /// `--vm-driver`: `digitalocean` for this binary's own driver, or a command line, split at
    /// white space (no quoting).
    pub fn parse(value: &str) -> Result<DriverCommand> {
        let value = value.trim();
        if value == BUILTIN_DIGITALOCEAN {
            return Ok(DriverCommand {
                program: std::env::current_exe().context("cannot find this executable")?,
                args: vec!["vm-driver".into(), BUILTIN_DIGITALOCEAN.into()],
                env: Vec::new(),
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
        })
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
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
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

// ----- the executor --------------------------------------------------------------------

/// The VM executor: its options and its driver.
pub struct Vms {
    pub options: VmOptions,
    driver: Driver,
    /// The [`VM_LABEL`]s of this process's VMs that are not deleted yet, from just before
    /// their create: the leftover cleanup never takes one of them.
    live: Mutex<HashSet<String>>,
    /// The builds of VM images that the host has warned of ([`ImageVersion::Warn`]).
    warned: Mutex<HashSet<Option<String>>>,
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
            warned: Mutex::new(HashSet::new()),
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
    /// contract, and the account may have as many VMs as the host's ceiling. Returns the
    /// driver's `hello` and `verify`.
    pub async fn check(&self, max_concurrent_runs: u32) -> Result<(DriverHello, Verified)> {
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
        if let Some(max) = verified.max_vms
            && max < max_concurrent_runs
        {
            bail!(
                "--max-concurrent-runs is {max_concurrent_runs}, but the provider lets the account have {max} more VM(s) (the VM driver `{}`); lower it, or raise the account's limit",
                hello.driver
            );
        }
        Ok((hello, verified))
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
                return Err(e);
            }
        };
        tracing::info!(vm = %id, name, template = %self.options.template, "sandbox VM created");
        Ok(VmGuard {
            vms: self.clone(),
            id,
            host: labels.get(HOST_LABEL).cloned().unwrap_or_default(),
            label,
            address: None,
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
        address: &str,
    ) -> VmGuard {
        if let Some(label) = label {
            lock(&self.live).insert(label.to_string());
        }
        VmGuard {
            vms: self.clone(),
            id: id.to_string(),
            host: host.to_string(),
            label: label.map(str::to_string),
            address: Some(address.to_string()),
            deleted: false,
        }
    }

    /// How many of this process's VMs are not deleted yet.
    pub fn live_count(&self) -> usize {
        lock(&self.live).len()
    }

    /// Waits until the VM is up and has an address, until `deadline`, which bounds each of the
    /// driver's answers too.
    pub async fn address(&self, id: &str, deadline: tokio::time::Instant) -> Result<String> {
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
                        if let Some(address) = vm.address.filter(|a| !a.is_empty()) {
                            return Ok(address);
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

    /// Deletes the VM `id`; its [`VM_LABEL`] `label` is no longer live then, whatever the
    /// outcome: a VM whose delete failed is the leftover cleanup's.
    async fn delete_labelled(&self, id: &str, host: &str, label: Option<&str>) -> bool {
        let deleted = self.delete(id, host).await;
        if let Some(label) = label {
            lock(&self.live).remove(label);
        }
        deleted
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
        tracing::error!(vm = %id, "the sandbox VM is left; the host deletes it with its leftovers at its next connection");
        false
    }

    /// Deletes this host's VMs that this process does not hold: those whose delete failed,
    /// and those of an earlier process of this host. A VM of a host before ready VMs, which
    /// has no [`VM_LABEL`], is kept while its run is in `live`.
    pub async fn cleanup_orphans(&self, host: &str, live: &HashSet<Uuid>) -> Result<usize> {
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
            }
        }
        Ok(deleted)
    }
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
    address: Option<String>,
    deleted: bool,
}

impl VmGuard {
    pub fn vms(&self) -> &Arc<Vms> {
        &self.vms
    }

    /// The VM's address, once the provider has said ([`VmGuard::set_address`]).
    pub fn address(&self) -> Option<&str> {
        self.address.as_deref()
    }

    pub fn set_address(&mut self, address: &str) {
        self.address = Some(address.to_string());
    }

    /// What a host that restarted needs to take the VM's run back.
    pub fn record(&self) -> Option<crate::takeback::SandboxRecord> {
        Some(crate::takeback::SandboxRecord::Vm {
            id: self.id.clone(),
            host: self.host.clone(),
            label: self.label.clone(),
            address: self.address.clone()?,
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
        // A run's task that ends without the delete (it was aborted), or a ready VM that goes:
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
    fn driver_commands_are_the_builtin_one_or_a_command_line() {
        let builtin = DriverCommand::parse("digitalocean").unwrap();
        assert_eq!(builtin.args, ["vm-driver", "digitalocean"]);
        assert_eq!(builtin.program, std::env::current_exe().unwrap());
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

    /// A driver in `sh`: answers each request after `delay` with its method's line of
    /// `answers`, by request id.
    fn sh_driver(script: &str) -> Driver {
        Driver::new(DriverCommand {
            program: PathBuf::from("sh"),
            args: vec!["-c".into(), script.into()],
            env: Vec::new(),
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
