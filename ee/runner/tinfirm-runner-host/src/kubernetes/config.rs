//! The pod driver's configuration (ADR-0115 decision 2): a JSON file that the chart renders
//! into a ConfigMap. Part of elastic runners, under the Tinfirm Enterprise licence
//! (`ee/LICENSE`).
//!
//! ```json
//! {
//!   "isolationStatement": "the workers for runs hold nothing else",
//!   "namespace": "tinfirm-runs",
//!   "runServiceAccount": "tinfirm-run",
//!   "image": "registry.example.com/tinfirm/vm-pod@sha256:<64 hex digits>",
//!   "templates": {"sandbox-4c8g": {"vcpus": 4, "memoryMib": 8192, "diskMib": 32768}},
//!   "tap": {"mode": "multus", "networkAttachment": "tinfirm-tap", "cidr": "172.30.0.0/30"},
//!   "devices": {"kvm": "devices.example.com/kvm", "tun": "devices.example.com/tun"},
//!   "nodeSelector": {"tinfirm.flowtivo.com/kvm": "true"},
//!   "tolerations": [{"key": "tinfirm.flowtivo.com/kvm", "operator": "Exists", "effect": "NoSchedule"}],
//!   "disk": {"variant": "copy", "volume": "emptyDir"},
//!   "bridgePort": 7443,
//!   "runner": {"namespace": "tinfirm", "labels": {"app.kubernetes.io/component": "runner"}}
//! }
//! ```
//!
//! Every field is checked when the file is loaded, and an error names the field. The runner
//! pod's own node is not here: the chart cannot know it when it renders the file. The driver
//! takes it from the downward API (`TINFIRM_NODE_NAME`, super::NODE_NAME_ENV).

use std::collections::BTreeMap;
use std::net::Ipv4Addr;
use std::path::Path;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

use super::api::{dns_label, dns_subdomain, label_key, label_name, label_value};

/// The operator's statement that the workers for runs hold nothing else (ADR-0115 decisions 1
/// and 6), which `isolationStatement` must carry word for word.
pub const ISOLATION_STATEMENT: &str = "the workers for runs hold nothing else";
/// The largest configuration file.
const MAX_FILE: u64 = 64 * 1024;
/// The most templates, tolerations and selector entries: bounds, not limits anyone reaches.
const MAX_TEMPLATES: usize = 32;
const MAX_TOLERATIONS: usize = 16;
const MAX_LABELS: usize = 16;
/// Firecracker's limit of vCPUs.
const MAX_VCPUS: u32 = 32;
const MIN_MEMORY_MIB: u32 = 128;
const MAX_MEMORY_MIB: u32 = 256 * 1024;
const MIN_DISK_MIB: u32 = 1024;
const MAX_DISK_MIB: u32 = 1024 * 1024;

/// How the pod gets the VM's tap device.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum TapMode {
    /// The network plugin hands it over (Multus with the CNI `tap` plugin): the pod has no
    /// capability and passes Pod Security's `restricted` level.
    Multus,
    /// An init container with `NET_ADMIN` in the pod's own network namespace makes it.
    Init,
}

/// How a run gets its writable root disk. Only `copy` is built.
///
/// `clone` is not: `FICLONE` needs the image and the disk on one file system, and answers
/// `EXDEV` between the container's root, where the image is, and a volume. How the image would
/// reach the disk's file system is not decided (ADR-0115 decision 2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DiskVariant {
    /// A sparse copy of the image in the pod's own image to the disk volume.
    Copy,
    /// A clone of the image on the disk's file system. Not built yet: refused.
    Clone,
    /// A small empty disk over the read-only image. Not built yet: refused.
    Overlay,
}

impl DiskVariant {
    pub fn as_str(self) -> &'static str {
        match self {
            DiskVariant::Copy => "copy",
            DiskVariant::Clone => "clone",
            DiskVariant::Overlay => "overlay",
        }
    }
}

/// Where the root disk lives.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum DiskVolume {
    /// An `emptyDir` on the worker's own storage, counted as ephemeral storage.
    EmptyDir,
    /// A generic ephemeral volume: a claim made for the pod and deleted with it.
    Ephemeral,
}

/// One size of VM.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Template {
    pub vcpus: u32,
    pub memory_mib: u32,
    pub disk_mib: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Tap {
    pub mode: TapMode,
    /// The NetworkAttachmentDefinition, `name` or `namespace/name` (Multus only), which the
    /// cluster's admin installs: its chain is the `tap` plugin with static IPAM and the
    /// `tuning` plugin that turns the tap's forwarding off (super::spec::MULTUS_ANNOTATION
    /// shows it).
    #[serde(default)]
    pub network_attachment: Option<String>,
    /// The tap's /30: the pod's side has its first address, the guest the second.
    pub cidr: String,
}

impl Tap {
    fn network(&self) -> Option<Ipv4Addr> {
        let (address, prefix) = self.cidr.split_once('/')?;
        let address: Ipv4Addr = address.parse().ok()?;
        (prefix == "30" && u32::from(address) & 3 == 0).then_some(address)
    }

    /// The pod's side of the tap.
    pub fn pod_address(&self) -> Ipv4Addr {
        Ipv4Addr::from(u32::from(self.network().expect("checked on load")) + 1)
    }

    /// The guest's address.
    pub fn guest_address(&self) -> Ipv4Addr {
        Ipv4Addr::from(u32::from(self.network().expect("checked on load")) + 2)
    }
}

/// The device plugin's resource names for the two devices.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Devices {
    pub kvm: String,
    pub tun: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
pub enum TolerationOperator {
    Equal,
    Exists,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
pub enum TaintEffect {
    NoSchedule,
    PreferNoSchedule,
    NoExecute,
}

/// A pod's toleration, as Kubernetes has it.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Toleration {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operator: Option<TolerationOperator>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect: Option<TaintEffect>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub toleration_seconds: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Disk {
    pub variant: DiskVariant,
    pub volume: DiskVolume,
    /// The ephemeral volume's storage class; none takes the cluster's default.
    #[serde(default)]
    pub storage_class_name: Option<String>,
}

/// The runner pod: the run pods' anti-affinity and the allow rule name it.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Runner {
    pub namespace: String,
    /// Labels that select the runner pods and nothing else.
    pub labels: BTreeMap<String, String>,
}

/// The names of the two network policies the chart installs in the runs' namespace.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Policies {
    #[serde(default = "default_deny_name")]
    pub default_deny: String,
    #[serde(default = "allow_runner_name")]
    pub allow_runner: String,
}

impl Default for Policies {
    fn default() -> Self {
        Policies {
            default_deny: default_deny_name(),
            allow_runner: allow_runner_name(),
        }
    }
}

fn default_deny_name() -> String {
    "tinfirm-runs-default-deny".into()
}

fn allow_runner_name() -> String {
    "tinfirm-runs-allow-runner".into()
}

fn default_run_service_account() -> String {
    "tinfirm-run".into()
}

fn default_resource_quota() -> String {
    "tinfirm-runs".into()
}

fn default_bridge_port() -> u16 {
    crate::vm::DEFAULT_PORT
}

/// The driver's configuration, checked.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PodConfig {
    /// [`ISOLATION_STATEMENT`], word for word.
    pub isolation_statement: String,
    /// The runs' namespace.
    pub namespace: String,
    /// The ServiceAccount every run pod runs as, which has no rights.
    #[serde(default = "default_run_service_account")]
    pub run_service_account: String,
    /// The run pod's image, by digest.
    pub image: String,
    pub templates: BTreeMap<String, Template>,
    pub tap: Tap,
    pub devices: Devices,
    /// The workers for runs (at least one label).
    pub node_selector: BTreeMap<String, String>,
    #[serde(default)]
    pub tolerations: Vec<Toleration>,
    pub disk: Disk,
    #[serde(default = "default_bridge_port")]
    pub bridge_port: u16,
    pub runner: Runner,
    #[serde(default)]
    pub policies: Policies,
    /// The runs' namespace's ResourceQuota, which gives the driver its capacity.
    #[serde(default = "default_resource_quota")]
    pub resource_quota: String,
}

impl PodConfig {
    /// Reads and checks the configuration file.
    pub fn load(path: &Path) -> Result<PodConfig> {
        use std::io::Read;
        let mut text = String::new();
        std::fs::File::open(path)
            .and_then(|file| file.take(MAX_FILE + 1).read_to_string(&mut text))
            .with_context(|| format!("cannot read {}", path.display()))?;
        if text.len() as u64 > MAX_FILE {
            bail!("{} is over {MAX_FILE} bytes", path.display());
        }
        PodConfig::from_json(&text)
            .with_context(|| format!("{} is not a valid pod driver configuration", path.display()))
    }

    /// Parses and checks a configuration.
    pub fn from_json(text: &str) -> Result<PodConfig> {
        let config: PodConfig = serde_json::from_str(text)?;
        config.check()?;
        Ok(config)
    }

    pub fn template(&self, name: &str) -> Result<&Template> {
        self.templates
            .get(name)
            .with_context(|| format!("no template `{name}` in the driver's configuration"))
    }

    fn check(&self) -> Result<()> {
        if self.isolation_statement != ISOLATION_STATEMENT {
            bail!(
                "isolationStatement: set it to \"{ISOLATION_STATEMENT}\" only when the workers \
                 that run the runs' pods run no other workload, not the runner pod either, and \
                 their kubelet's credential and cloud identity open nothing else (ADR-0115 \
                 decision 6). The host then reports its isolation as `machine`, stated by the \
                 operator. Without the statement the driver does not start."
            );
        }
        if !dns_label(&self.namespace) {
            bail!("namespace: not a namespace's name");
        }
        if !dns_subdomain(&self.run_service_account) || self.run_service_account == "default" {
            bail!("runServiceAccount: not a ServiceAccount's name, or `default`");
        }
        if !image_by_digest(&self.image) {
            bail!("image: not an image by digest (`repository@sha256:` and 64 hex digits)");
        }
        if self.templates.is_empty() || self.templates.len() > MAX_TEMPLATES {
            bail!("templates: 1 to {MAX_TEMPLATES} templates");
        }
        for (name, template) in &self.templates {
            if !dns_label(name) || name.len() > 32 {
                bail!("templates: `{name}` is not a name of 1 to 32 characters of a-z, 0-9 and -");
            }
            if !(1..=MAX_VCPUS).contains(&template.vcpus) {
                bail!("templates.{name}.vcpus: 1 to {MAX_VCPUS}");
            }
            if !(MIN_MEMORY_MIB..=MAX_MEMORY_MIB).contains(&template.memory_mib) {
                bail!("templates.{name}.memoryMib: {MIN_MEMORY_MIB} to {MAX_MEMORY_MIB}");
            }
            if !(MIN_DISK_MIB..=MAX_DISK_MIB).contains(&template.disk_mib) {
                bail!("templates.{name}.diskMib: {MIN_DISK_MIB} to {MAX_DISK_MIB}");
            }
        }
        if self.tap.network().is_none() {
            bail!("tap.cidr: an IPv4 network with prefix /30, such as 172.30.0.0/30");
        }
        match (self.tap.mode, &self.tap.network_attachment) {
            (TapMode::Multus, Some(attachment)) if network_attachment_ok(attachment) => {}
            (TapMode::Multus, _) => {
                bail!(
                    "tap.networkAttachment: the NetworkAttachmentDefinition's `name` or `namespace/name`, for mode `multus`"
                )
            }
            (TapMode::Init, None) => {}
            (TapMode::Init, Some(_)) => bail!("tap.networkAttachment: only for mode `multus`"),
        }
        for (field, name) in [
            ("devices.kvm", &self.devices.kvm),
            ("devices.tun", &self.devices.tun),
        ] {
            if !resource_name(name) {
                bail!(
                    "{field}: a device plugin's resource name, such as `devices.example.com/kvm`"
                );
            }
        }
        if self.devices.kvm == self.devices.tun {
            bail!("devices: `kvm` and `tun` are the same resource");
        }
        check_labels("nodeSelector", &self.node_selector)?;
        if self.tolerations.len() > MAX_TOLERATIONS {
            bail!("tolerations: at most {MAX_TOLERATIONS}");
        }
        for (i, toleration) in self.tolerations.iter().enumerate() {
            check_toleration(i, toleration)?;
        }
        self.check_disk()?;
        if self.bridge_port == 0 {
            bail!("bridgePort: must not be 0");
        }
        if !dns_label(&self.runner.namespace) {
            bail!("runner.namespace: not a namespace's name");
        }
        if self.runner.namespace == self.namespace {
            bail!(
                "runner.namespace: the runs' namespace must be another, because it may hold no Secret but the runs' tickets"
            );
        }
        check_labels("runner.labels", &self.runner.labels)?;
        for (field, name) in [
            ("policies.defaultDeny", &self.policies.default_deny),
            ("policies.allowRunner", &self.policies.allow_runner),
            ("resourceQuota", &self.resource_quota),
        ] {
            if !dns_subdomain(name) {
                bail!("{field}: not an object's name");
            }
        }
        if self.policies.default_deny == self.policies.allow_runner {
            bail!("policies: the two policies have the same name");
        }
        Ok(())
    }

    fn check_disk(&self) -> Result<()> {
        let disk = &self.disk;
        if let Some(class) = &disk.storage_class_name
            && (disk.volume != DiskVolume::Ephemeral || !dns_subdomain(class))
        {
            bail!("disk.storageClassName: a storage class's name, for volume `ephemeral` only");
        }
        match disk.variant {
            DiskVariant::Copy => Ok(()),
            DiskVariant::Clone => bail!(
                "disk.variant: `clone` is not built yet: a clone needs the image and the disk on one file system; how the image reaches that file system is not decided"
            ),
            DiskVariant::Overlay => bail!(
                "disk.variant: `overlay` is not built yet: it needs the guest to put Docker's directories on a disk of their own, which its image does not do yet"
            ),
        }
    }
}

/// `repository@sha256:<64 lower-case hex digits>`, with no tag. The repository is path
/// components of lower-case letters and digits, with `.`, `_` and `-` between them; where it
/// has more than one, the first may be a registry's host with a port.
fn image_by_digest(image: &str) -> bool {
    let Some((repository, digest)) = image.split_once('@') else {
        return false;
    };
    let hex_ok = digest.strip_prefix("sha256:").is_some_and(|hex| {
        hex.len() == 64
            && hex
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    });
    let inner = |b: &u8| b.is_ascii_lowercase() || b.is_ascii_digit();
    let component = |part: &str| {
        let bytes = part.as_bytes();
        bytes.first().is_some_and(inner)
            && bytes.last().is_some_and(inner)
            && bytes
                .iter()
                .all(|b| inner(b) || matches!(b, b'.' | b'_' | b'-'))
    };
    let parts: Vec<&str> = repository.split('/').collect();
    let (first, rest) = parts.split_first().unwrap_or((&"", &[]));
    // A colon is a registry's port, and a registry is never the whole repository: in the
    // last component it would be a tag.
    let first_ok = match first.rsplit_once(':') {
        Some((host, port)) => {
            !rest.is_empty()
                && component(host)
                && (1..=5).contains(&port.len())
                && port.bytes().all(|b| b.is_ascii_digit())
        }
        None => component(first),
    };
    hex_ok && repository.len() <= 255 && first_ok && rest.iter().all(|part| component(part))
}

fn network_attachment_ok(attachment: &str) -> bool {
    match attachment.split_once('/') {
        Some((namespace, name)) => dns_label(namespace) && dns_subdomain(name),
        None => dns_subdomain(attachment),
    }
}

/// An extended resource's name: `domain/name`.
fn resource_name(name: &str) -> bool {
    name.split_once('/')
        .is_some_and(|(prefix, rest)| dns_subdomain(prefix) && label_name(rest))
}

fn check_labels(field: &str, labels: &BTreeMap<String, String>) -> Result<()> {
    if labels.is_empty() || labels.len() > MAX_LABELS {
        bail!("{field}: 1 to {MAX_LABELS} labels");
    }
    for (key, value) in labels {
        if !label_key(key) {
            bail!("{field}: `{key}` is not a label key");
        }
        if !label_value(value) {
            bail!("{field}.{key}: not a label value");
        }
    }
    Ok(())
}

fn check_toleration(i: usize, toleration: &Toleration) -> Result<()> {
    let field = format!("tolerations[{i}]");
    if let Some(key) = &toleration.key
        && !label_key(key)
    {
        bail!("{field}.key: not a label key");
    }
    if let Some(value) = &toleration.value
        && !label_value(value)
    {
        bail!("{field}.value: not a label value");
    }
    match toleration.operator {
        Some(TolerationOperator::Exists)
            if toleration.value.as_deref().is_some_and(|v| !v.is_empty()) =>
        {
            bail!("{field}: operator `Exists` takes no value")
        }
        Some(TolerationOperator::Equal) | None if toleration.key.is_none() => {
            bail!("{field}: operator `Equal` needs a key")
        }
        _ => {}
    }
    if toleration.toleration_seconds.is_some() && toleration.effect != Some(TaintEffect::NoExecute)
    {
        bail!("{field}.tolerationSeconds: only with effect `NoExecute`");
    }
    Ok(())
}

#[cfg(test)]
pub(super) mod tests {
    use super::*;
    use serde_json::{Value, json};

    pub(in crate::kubernetes) const DIGEST: &str =
        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /// A configuration that passes, in the Multus and copy variant.
    pub(in crate::kubernetes) fn good() -> Value {
        json!({
            "isolationStatement": ISOLATION_STATEMENT,
            "namespace": "tinfirm-runs",
            "image": format!("registry.example.com:5000/tinfirm/vm-pod@{DIGEST}"),
            "templates": {"sandbox-4c8g": {"vcpus": 4, "memoryMib": 8192, "diskMib": 32768}},
            "tap": {"mode": "multus", "networkAttachment": "tinfirm-tap", "cidr": "172.30.0.0/30"},
            "devices": {"kvm": "devices.example.com/kvm", "tun": "devices.example.com/tun"},
            "nodeSelector": {"tinfirm.flowtivo.com/kvm": "true"},
            "tolerations": [{"key": "tinfirm.flowtivo.com/kvm", "operator": "Exists", "effect": "NoSchedule"}],
            "disk": {"variant": "copy", "volume": "emptyDir"},
            "runner": {"namespace": "tinfirm", "labels": {
                "app.kubernetes.io/instance": "tinfirm",
                "app.kubernetes.io/component": "runner"
            }}
        })
    }

    pub(in crate::kubernetes) fn config(value: &Value) -> PodConfig {
        PodConfig::from_json(&value.to_string()).unwrap()
    }

    fn error(value: &Value) -> String {
        format!(
            "{:#}",
            PodConfig::from_json(&value.to_string()).unwrap_err()
        )
    }

    #[test]
    fn an_image_is_a_repository_and_a_digest() {
        for image in [
            format!("vm-pod@{DIGEST}"),
            format!("registry.example.com/vm-pod@{DIGEST}"),
            format!("registry.example.com:5000/team/sub/vm_pod.x@{DIGEST}"),
            format!("localhost:5000/vm-pod@{DIGEST}"),
        ] {
            assert!(image_by_digest(&image), "{image}");
        }
    }

    #[test]
    fn a_good_configuration_loads_with_its_defaults() {
        let config = config(&good());
        assert_eq!(config.run_service_account, "tinfirm-run");
        assert_eq!(config.bridge_port, 7443);
        assert_eq!(config.resource_quota, "tinfirm-runs");
        assert_eq!(config.policies, Policies::default());
        assert_eq!(config.tap.pod_address(), Ipv4Addr::new(172, 30, 0, 1));
        assert_eq!(config.tap.guest_address(), Ipv4Addr::new(172, 30, 0, 2));
        assert_eq!(config.template("sandbox-4c8g").unwrap().vcpus, 4);
        assert!(
            format!("{:#}", config.template("other").unwrap_err()).contains("no template `other`")
        );

        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("pod.json");
        std::fs::write(&path, good().to_string()).unwrap();
        assert_eq!(PodConfig::load(&path).unwrap(), config);
        std::fs::write(&path, " ".repeat(MAX_FILE as usize + 1)).unwrap();
        assert!(PodConfig::load(&path).is_err());
    }

    #[test]
    fn the_isolation_statement_is_required_word_for_word() {
        // A boolean is not accepted: the statement is the sentence itself.
        let mut value = good();
        value["isolationStatement"] = json!(true);
        assert!(PodConfig::from_json(&value.to_string()).is_err());
        value.as_object_mut().unwrap().remove("isolationStatement");
        assert!(error(&value).contains("isolationStatement"));
        for statement in ["yes", "true", "The workers for runs hold nothing else"] {
            value["isolationStatement"] = json!(statement);
            let error = error(&value);
            assert!(error.contains("isolationStatement"), "{statement}: {error}");
        }
        let mut value = good();
        value["isolationStatement"] = json!("no");
        let error = error(&value);
        assert!(error.contains("run no other workload"), "{error}");
        assert!(error.contains(ISOLATION_STATEMENT), "{error}");
    }

    /// Each change of a good configuration, and a word that the error must contain.
    #[test]
    fn each_field_is_checked_and_named() {
        let cases: Vec<(&str, Value, &str)> = vec![
            ("/unknown", json!(1), "unknown field"),
            ("/namespace", json!("Runs"), "namespace"),
            ("/runServiceAccount", json!("default"), "runServiceAccount"),
            (
                "/image",
                json!("registry.example.com/tinfirm/vm-pod:1.0"),
                "image",
            ),
            (
                "/image",
                json!(format!("registry.example.com/vm-pod:1.0@{DIGEST}")),
                "image",
            ),
            (
                "/image",
                json!("registry.example.com/vm-pod@sha256:abc"),
                "image",
            ),
            (
                "/image",
                json!(format!("registry.example.com/-invalid/vm-pod@{DIGEST}")),
                "image",
            ),
            (
                "/image",
                json!(format!("registry.example.com/vm-pod-@{DIGEST}")),
                "image",
            ),
            (
                "/image",
                json!(format!("registry.example.com//vm-pod@{DIGEST}")),
                "image",
            ),
            (
                "/image",
                json!(format!("registry.example.com:port/vm-pod@{DIGEST}")),
                "image",
            ),
            (
                "/image",
                json!(format!("registry.example.com/team:5000/vm-pod@{DIGEST}")),
                "image",
            ),
            ("/image", json!(format!("registry:5000@{DIGEST}")), "image"),
            (
                "/image",
                json!(format!("registry.example.com/VM-pod@{DIGEST}")),
                "image",
            ),
            ("/templates", json!({}), "templates"),
            (
                "/templates/Big",
                json!({"vcpus": 1, "memoryMib": 512, "diskMib": 2048}),
                "templates",
            ),
            ("/templates/sandbox-4c8g/vcpus", json!(0), "vcpus"),
            ("/templates/sandbox-4c8g/vcpus", json!(33), "vcpus"),
            ("/templates/sandbox-4c8g/memoryMib", json!(64), "memoryMib"),
            ("/templates/sandbox-4c8g/diskMib", json!(10), "diskMib"),
            ("/templates/sandbox-4c8g/gpus", json!(1), "unknown field"),
            ("/tap/cidr", json!("172.30.0.0/29"), "tap.cidr"),
            ("/tap/cidr", json!("172.30.0.1/30"), "tap.cidr"),
            ("/tap/cidr", json!("fd00::/126"), "tap.cidr"),
            (
                "/tap/networkAttachment",
                json!(null),
                "tap.networkAttachment",
            ),
            (
                "/tap/networkAttachment",
                json!("a/b/c"),
                "tap.networkAttachment",
            ),
            ("/tap/mode", json!("bridge"), "unknown variant"),
            ("/devices/kvm", json!("kvm"), "devices.kvm"),
            ("/devices/tun", json!("devices.example.com/kvm"), "devices"),
            ("/nodeSelector", json!({}), "nodeSelector"),
            ("/nodeSelector", json!({"a b": "c"}), "nodeSelector"),
            ("/nodeSelector", json!({"a": "b,c"}), "nodeSelector.a"),
            (
                "/tolerations/0/value",
                json!("x"),
                "tolerations[0]: operator `Exists` takes no value",
            ),
            (
                "/tolerations/0/tolerationSeconds",
                json!(30),
                "tolerations[0].tolerationSeconds",
            ),
            ("/tolerations/0/effect", json!("Never"), "unknown variant"),
            (
                "/disk/variant",
                json!("overlay"),
                "`overlay` is not built yet",
            ),
            (
                "/disk/variant",
                json!("clone"),
                "`clone` is not built yet: a clone needs the image and the disk on one file system",
            ),
            (
                "/disk/storageClassName",
                json!("fast"),
                "disk.storageClassName",
            ),
            (
                "/disk/dataSource",
                json!({"kind": "VolumeSnapshot", "name": "vm-image"}),
                "unknown field",
            ),
            ("/bridgePort", json!(0), "bridgePort"),
            (
                "/runner/namespace",
                json!("tinfirm-runs"),
                "runner.namespace",
            ),
            ("/runner/labels", json!({}), "runner.labels"),
            (
                "/policies",
                json!({"defaultDeny": "a", "allowRunner": "a"}),
                "policies",
            ),
            ("/resourceQuota", json!("Quota"), "resourceQuota"),
        ];
        for (pointer, replacement, word) in cases {
            let mut value = good();
            let (parent, key) = pointer.rsplit_once('/').unwrap();
            let target = if parent.is_empty() {
                &mut value
            } else {
                value.pointer_mut(parent).unwrap()
            };
            if replacement.is_null() {
                target.as_object_mut().unwrap().remove(key);
            } else if let Some(items) = target.as_array_mut() {
                items[key.parse::<usize>().unwrap()] = replacement.clone();
            } else {
                target[key] = replacement.clone();
            }
            let error = error(&value);
            assert!(error.contains(word), "{pointer} = {replacement}: {error}");
        }
    }

    #[test]
    fn the_variants_load() {
        let mut value = good();
        value["tap"] = json!({"mode": "init", "cidr": "10.255.255.252/30"});
        value["disk"] =
            json!({"variant": "copy", "volume": "ephemeral", "storageClassName": "local-xfs"});
        let config = config(&value);
        assert_eq!(config.tap.mode, TapMode::Init);
        assert_eq!(config.tap.guest_address(), Ipv4Addr::new(10, 255, 255, 254));
        assert_eq!(config.disk.storage_class_name.as_deref(), Some("local-xfs"));
        value["tap"]["networkAttachment"] = json!("tinfirm-tap");
        assert!(error(&value).contains("only for mode `multus`"));
        value["tap"] = json!({"mode": "multus", "networkAttachment": "kube-system/tap", "cidr": "172.30.0.4/30"});
        value["disk"] = json!({"variant": "copy", "volume": "ephemeral"});
        assert_eq!(
            super::tests::config(&value).disk.volume,
            DiskVolume::Ephemeral
        );
    }
}
