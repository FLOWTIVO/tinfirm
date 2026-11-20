//! The objects of the pod driver (ADR-0115 decision 2) as pure functions of its configuration:
//! a run's pod and its ticket Secret, their names and labels, the pods of the admission check,
//! the network policies the driver expects to find, and a pod's state in the driver protocol.
//! Part of elastic runners, under the Tinfirm Enterprise licence (`ee/LICENSE`).
//!
//! What runs in the pod (`vm-pod`) is planned. [`vm_pod`] holds every path, name and variable
//! that it and these objects must agree on.

use std::collections::BTreeMap;

use anyhow::{Result, bail};
use base64::Engine as _;
use serde_json::{Value, json};

use super::api::{dns_subdomain, label_value};
use super::config::{DiskVolume, PodConfig, TapMode};

/// What the run's pod and `vm-pod` agree on. `vm-pod` (planned) uses these constants and no
/// literal of its own.
pub mod vm_pod {
    /// The runner host's binary in the run pod's image; `vm-pod` is its subcommand.
    pub const BINARY: &str = "/tinfirm/tinfirm-runner-host";
    pub const COMMAND: &str = "vm-pod";
    /// `vm-pod ready`: the readiness probe, which succeeds once [`READY_FILE`] exists.
    pub const READY: &str = "ready";
    /// `vm-pod tap-setup`: the init container of the init variant, which makes [`TAP_DEVICE`].
    pub const TAP_SETUP: &str = "tap-setup";
    /// The pod's containers.
    pub const CONTAINER: &str = "vm";
    pub const INIT_CONTAINER: &str = "tap";
    /// The VM's kernel and root file system image, in the run pod's image.
    pub const KERNEL: &str = "/tinfirm/vm/vmlinux";
    pub const IMAGE: &str = "/tinfirm/vm/rootfs.ext4";
    /// The ticket Secret's volume, read-only, and the one file in it.
    pub const TICKET_DIR: &str = "/tinfirm/ticket";
    pub const TICKET_KEY: &str = "user-data";
    /// The memory volume: Firecracker's API socket, the user-data disk and the ready file.
    pub const RUN_DIR: &str = "/tinfirm/run";
    pub const API_SOCKET: &str = "/tinfirm/run/firecracker.sock";
    pub const USER_DATA_DISK: &str = "/tinfirm/run/userdata.disk";
    pub const READY_FILE: &str = "/tinfirm/run/ready";
    /// The disk volume, and the run's writable root disk in it: a sparse copy of [`IMAGE`],
    /// grown to the template's size (variant `copy`, the only one built).
    pub const DISK_DIR: &str = "/tinfirm/disk";
    pub const DISK: &str = "/tinfirm/disk/rootfs.ext4";
    /// Where `vm-pod` writes why it ended, which `describe` reports.
    pub const TERMINATION_LOG: &str = "/dev/termination-log";
    pub const TAP_DEVICE: &str = "tap0";
    /// The pod's user and group (inside its user namespace).
    pub const UID: i64 = 10001;
    pub const GID: i64 = 10001;
    /// The environment of the containers.
    pub const ENV_POD_NAME: &str = "TINFIRM_POD_NAME";
    pub const ENV_POD_IP: &str = "TINFIRM_POD_IP";
    pub const ENV_TAP_MODE: &str = "TINFIRM_TAP_MODE";
    pub const ENV_TAP_CIDR: &str = "TINFIRM_TAP_CIDR";
    pub const ENV_BRIDGE_PORT: &str = "TINFIRM_BRIDGE_PORT";
    pub const ENV_VCPUS: &str = "TINFIRM_VCPUS";
    pub const ENV_MEMORY_MIB: &str = "TINFIRM_MEMORY_MIB";
    pub const ENV_DISK_MIB: &str = "TINFIRM_DISK_MIB";
    pub const ENV_DISK_VARIANT: &str = "TINFIRM_DISK_VARIANT";
}

/// The prefix of every Kubernetes label the driver sets: a protocol label `tinfirm-X` is
/// `tinfirm.flowtivo.com/X`. The chart uses no domain of its own for labels.
pub const LABEL_PREFIX: &str = "tinfirm.flowtivo.com/";
/// The label of every run pod, which the allow rule selects.
pub const SANDBOX_LABEL: &str = "tinfirm.flowtivo.com/sandbox";
pub const SANDBOX_VALUE: &str = "vm";
/// A run pod's name is this and 32 hex digits; its ticket Secret's the other, with the same.
pub const POD_PREFIX: &str = "tinfirm-vm-";
pub const TICKET_PREFIX: &str = "tinfirm-ticket-";
/// Multus' annotation that attaches the tap.
pub const MULTUS_ANNOTATION: &str = "k8s.v1.cni.cncf.io/networks";
/// The largest user data: as DigitalOcean's driver allows.
pub const MAX_USER_DATA: usize = 64 * 1024;
/// Memory beside the guest's: Firecracker's own (the node agent's figure), `vm-pod`'s, and the
/// memory volume.
pub const VMM_OVERHEAD_MIB: u32 = 128;
pub const VM_POD_MIB: u32 = 64;
pub const RUN_VOLUME_MIB: u32 = 16;
/// Ephemeral storage beside a disk in an `emptyDir`: the containers' logs.
pub const LOGS_MIB: u32 = 64;
/// The init container's own resources.
const INIT_CPU: &str = "100m";
const INIT_MEMORY: &str = "32Mi";
const GRACE_SECONDS: u32 = 5;
/// The longest termination message a failed state keeps, in characters.
const MAX_MESSAGE: usize = 1024;

/// A VM's id: 32 lower-case hex digits, which name its pod and its ticket Secret.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VmId(String);

impl VmId {
    /// A new random id.
    pub fn random() -> VmId {
        VmId(uuid::Uuid::new_v4().simple().to_string())
    }

    /// The id of a pod's name (`tinfirm-vm-` and 32 hex digits), which is the VM's id in the
    /// driver protocol.
    pub fn parse(pod_name: &str) -> Result<VmId> {
        match pod_name.strip_prefix(POD_PREFIX) {
            Some(hex)
                if hex.len() == 32
                    && hex
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) =>
            {
                Ok(VmId(hex.to_string()))
            }
            _ => bail!("`{pod_name}` is not the id of a run pod"),
        }
    }

    pub fn hex(&self) -> &str {
        &self.0
    }

    pub fn pod_name(&self) -> String {
        format!("{POD_PREFIX}{}", self.0)
    }

    pub fn ticket_name(&self) -> String {
        format!("{TICKET_PREFIX}{}", self.0)
    }
}

/// The Kubernetes labels of the protocol's labels. A key must be `tinfirm-` and a name; a
/// value that Kubernetes would refuse is an error, never cut or changed.
pub fn kubernetes_labels(protocol: &BTreeMap<String, String>) -> Result<BTreeMap<String, String>> {
    let mut labels = BTreeMap::new();
    for (key, value) in protocol {
        let Some(name) = key.strip_prefix("tinfirm-") else {
            bail!("the label `{key}` does not start with `tinfirm-`");
        };
        let mapped = format!("{LABEL_PREFIX}{name}");
        if !super::api::label_key(&mapped) || mapped == SANDBOX_LABEL {
            bail!("the label `{key}` cannot be a Kubernetes label");
        }
        if !label_value(value) {
            bail!(
                "the value of the label `{key}` cannot be a Kubernetes label's (at most 63 letters, digits, `-`, `_` and `.`)"
            );
        }
        labels.insert(mapped, value.clone());
    }
    Ok(labels)
}

/// The protocol's labels of an object's Kubernetes labels; other labels are left out.
pub fn protocol_labels(object: &Value) -> BTreeMap<String, String> {
    object["metadata"]["labels"]
        .as_object()
        .into_iter()
        .flatten()
        .filter(|(key, _)| key.as_str() != SANDBOX_LABEL)
        .filter_map(|(key, value)| {
            let name = key.strip_prefix(LABEL_PREFIX)?;
            Some((format!("tinfirm-{name}"), value.as_str()?.to_string()))
        })
        .collect()
}

/// A run's pod: from the configuration's template `template`, with the protocol's `labels`,
/// kept off `runner_node`, the runner pod's own node.
pub fn pod(
    config: &PodConfig,
    template: &str,
    id: &VmId,
    labels: &BTreeMap<String, String>,
    runner_node: &str,
) -> Result<Value> {
    use vm_pod::*;
    let size = config.template(template)?;
    if !dns_subdomain(runner_node) {
        bail!("`{runner_node}` is not a node's name");
    }
    let mut pod_labels = kubernetes_labels(labels)?;
    pod_labels.insert(SANDBOX_LABEL.into(), SANDBOX_VALUE.into());
    let disk_in_empty_dir = config.disk.volume == DiskVolume::EmptyDir;
    let memory = size.memory_mib + VMM_OVERHEAD_MIB + VM_POD_MIB + RUN_VOLUME_MIB;
    let storage = LOGS_MIB + if disk_in_empty_dir { size.disk_mib } else { 0 };
    let resources = json!({
        "cpu": size.vcpus.to_string(),
        "memory": format!("{memory}Mi"),
        "ephemeral-storage": format!("{storage}Mi"),
        (config.devices.kvm.clone()): "1",
        (config.devices.tun.clone()): "1",
    });
    let tap_mode = match config.tap.mode {
        TapMode::Multus => "multus",
        TapMode::Init => "init",
    };
    let field = |path: &str| json!({"fieldRef": {"apiVersion": "v1", "fieldPath": path}});
    let container = json!({
        "name": CONTAINER,
        "image": config.image,
        "command": [BINARY, COMMAND],
        "env": [
            {"name": ENV_POD_NAME, "valueFrom": field("metadata.name")},
            {"name": ENV_POD_IP, "valueFrom": field("status.podIP")},
            {"name": ENV_TAP_MODE, "value": tap_mode},
            {"name": ENV_TAP_CIDR, "value": config.tap.cidr},
            {"name": ENV_BRIDGE_PORT, "value": config.bridge_port.to_string()},
            {"name": ENV_VCPUS, "value": size.vcpus.to_string()},
            {"name": ENV_MEMORY_MIB, "value": size.memory_mib.to_string()},
            {"name": ENV_DISK_MIB, "value": size.disk_mib.to_string()},
            {"name": ENV_DISK_VARIANT, "value": config.disk.variant.as_str()},
        ],
        "ports": [{"name": "bridge", "containerPort": config.bridge_port, "protocol": "TCP"}],
        "readinessProbe": {
            "exec": {"command": [BINARY, COMMAND, READY]},
            "periodSeconds": 1,
            "timeoutSeconds": 1,
            "successThreshold": 1,
            "failureThreshold": 1,
        },
        "resources": {"requests": resources, "limits": resources},
        "securityContext": {
            "allowPrivilegeEscalation": false,
            "readOnlyRootFilesystem": true,
            "runAsNonRoot": true,
            "capabilities": {"drop": ["ALL"]},
        },
        "terminationMessagePath": TERMINATION_LOG,
        "terminationMessagePolicy": "File",
        "volumeMounts": [
            {"name": "ticket", "mountPath": TICKET_DIR, "readOnly": true},
            {"name": "run", "mountPath": RUN_DIR},
            {"name": "disk", "mountPath": DISK_DIR},
        ],
    });
    let disk_volume = if disk_in_empty_dir {
        json!({"name": "disk", "emptyDir": {"sizeLimit": format!("{}Mi", size.disk_mib)}})
    } else {
        let mut claim = json!({
            "accessModes": ["ReadWriteOnce"],
            "volumeMode": "Filesystem",
            "resources": {"requests": {"storage": format!("{}Mi", size.disk_mib)}},
        });
        if let Some(class) = &config.disk.storage_class_name {
            claim["storageClassName"] = json!(class);
        }
        json!({"name": "disk", "ephemeral": {"volumeClaimTemplate": {
            "metadata": {"labels": pod_labels},
            "spec": claim,
        }}})
    };
    let mut metadata = json!({
        "name": id.pod_name(),
        "namespace": config.namespace,
        "labels": pod_labels,
    });
    let mut spec = json!({
        "serviceAccountName": config.run_service_account,
        "automountServiceAccountToken": false,
        "enableServiceLinks": false,
        "hostUsers": false,
        "restartPolicy": "Never",
        "terminationGracePeriodSeconds": GRACE_SECONDS,
        "securityContext": {
            "runAsNonRoot": true,
            "runAsUser": UID,
            "runAsGroup": GID,
            "fsGroup": GID,
            "seccompProfile": {"type": "RuntimeDefault"},
        },
        "nodeSelector": config.node_selector,
        "tolerations": config.tolerations,
        "affinity": {
            "nodeAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": [
                {"matchFields": [{"key": "metadata.name", "operator": "NotIn", "values": [runner_node]}]}
            ]}},
            "podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [{
                "labelSelector": {"matchLabels": config.runner.labels},
                "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": config.runner.namespace}},
                "topologyKey": "kubernetes.io/hostname",
            }]},
        },
        "containers": [container],
        "volumes": [
            {"name": "ticket", "secret": {
                "secretName": id.ticket_name(),
                "items": [{"key": TICKET_KEY, "path": TICKET_KEY}],
                "defaultMode": 0o440,
            }},
            {"name": "run", "emptyDir": {"medium": "Memory", "sizeLimit": format!("{RUN_VOLUME_MIB}Mi")}},
            disk_volume,
        ],
    });
    match config.tap.mode {
        TapMode::Multus => {
            let attachment = config.tap.network_attachment.as_deref().unwrap_or_default();
            metadata["annotations"] =
                json!({MULTUS_ANNOTATION: format!("{attachment}@{TAP_DEVICE}")});
        }
        TapMode::Init => {
            let init_resources = json!({
                "cpu": INIT_CPU,
                "memory": INIT_MEMORY,
                (config.devices.tun.clone()): "1",
            });
            spec["initContainers"] = json!([{
                "name": INIT_CONTAINER,
                "image": config.image,
                "command": [BINARY, COMMAND, TAP_SETUP],
                "env": [{"name": ENV_TAP_CIDR, "value": config.tap.cidr}],
                "resources": {"requests": init_resources, "limits": init_resources},
                "securityContext": {
                    "runAsUser": 0,
                    "runAsNonRoot": false,
                    "allowPrivilegeEscalation": false,
                    "readOnlyRootFilesystem": true,
                    "capabilities": {"drop": ["ALL"], "add": ["NET_ADMIN"]},
                },
                "terminationMessagePath": TERMINATION_LOG,
                "terminationMessagePolicy": "File",
            }]);
        }
    }
    Ok(json!({"apiVersion": "v1", "kind": "Pod", "metadata": metadata, "spec": spec}))
}

/// The ticket Secret of the pod `id`, whose uid is `pod_uid`: the user data under one key,
/// immutable, and owned by the pod, so that the cluster deletes it with the pod. It sets no
/// `blockOwnerDeletion`, which would need a right on `pods/finalizers`.
pub fn ticket_secret(
    config: &PodConfig,
    id: &VmId,
    labels: &BTreeMap<String, String>,
    user_data: &[u8],
    pod_uid: &str,
) -> Result<Value> {
    if user_data.is_empty() || user_data.len() > MAX_USER_DATA {
        bail!("the user data is empty or over {MAX_USER_DATA} bytes");
    }
    if pod_uid.is_empty() || !pod_uid.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-') {
        bail!("`{pod_uid}` is not a pod's uid");
    }
    Ok(json!({
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {
            "name": id.ticket_name(),
            "namespace": config.namespace,
            "labels": kubernetes_labels(labels)?,
            "ownerReferences": [{
                "apiVersion": "v1",
                "kind": "Pod",
                "name": id.pod_name(),
                "uid": pod_uid,
            }],
        },
        "type": "Opaque",
        "immutable": true,
        "data": {(vm_pod::TICKET_KEY): base64::engine::general_purpose::STANDARD.encode(user_data)},
    }))
}

/// The `spec` of the runs' namespace's default-deny policy: every pod, nothing in or out.
pub fn default_deny_spec() -> Value {
    json!({"podSelector": {}, "policyTypes": ["Ingress", "Egress"]})
}

/// The `spec` of the one allow rule: into run pods, from the runner pods, to the bridge's port.
pub fn allow_runner_spec(config: &PodConfig) -> Value {
    json!({
        "podSelector": {"matchLabels": {SANDBOX_LABEL: SANDBOX_VALUE}},
        "policyTypes": ["Ingress"],
        "ingress": [{
            "from": [{
                "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": config.runner.namespace}},
                "podSelector": {"matchLabels": config.runner.labels},
            }],
            "ports": [{"protocol": "TCP", "port": config.bridge_port}],
        }],
    })
}

/// How a policy as read from the cluster differs from the expected `spec`: one line per
/// path, empty when it is the same.
pub fn policy_differences(expected_spec: &Value, policy: &Value) -> Vec<String> {
    let mut out = Vec::new();
    match policy.get("spec") {
        Some(spec) => differences("spec", expected_spec, spec, &mut out),
        None => out.push("spec: missing".into()),
    }
    out
}

fn differences(path: &str, expected: &Value, actual: &Value, out: &mut Vec<String>) {
    match (expected, actual) {
        (Value::Object(e), Value::Object(a)) => {
            for (key, value) in e {
                match a.get(key) {
                    Some(other) => differences(&format!("{path}.{key}"), value, other, out),
                    None => out.push(format!("{path}.{key}: missing")),
                }
            }
            for key in a.keys().filter(|k| !e.contains_key(*k)) {
                out.push(format!("{path}.{key}: not expected"));
            }
        }
        (Value::Array(e), Value::Array(a)) if e.len() == a.len() => {
            for (i, (value, other)) in e.iter().zip(a).enumerate() {
                differences(&format!("{path}[{i}]"), value, other, out);
            }
        }
        (Value::Array(e), Value::Array(a)) => {
            out.push(format!("{path}: {} items, expected {}", a.len(), e.len()))
        }
        _ if expected != actual => out.push(format!(
            "{path}: {}, expected {expected}",
            bounded(&actual.to_string())
        )),
        _ => {}
    }
}

/// A pod the cluster must refuse, and what in it is wrong.
#[derive(Debug, Clone)]
pub struct RefusedPod {
    /// What differs from the correct pod, for the message.
    pub name: &'static str,
    pub pod: Value,
    /// Kubernetes' own validation refuses this pod already, whatever the admission policy:
    /// its refusal shows nothing of the policy. The driver still expects it refused.
    pub refused_by_validation: bool,
}

/// The admission check: the correct pod, which the cluster must accept, and the pods it must
/// refuse, each one deviation from it. The driver creates each with `dryRun=All`.
pub struct AdmissionBattery {
    pub accepted: Value,
    pub refused: Vec<RefusedPod>,
}

/// The admission check's pods for `template`, from the same arguments as [`pod`].
pub fn admission_battery(
    config: &PodConfig,
    template: &str,
    id: &VmId,
    labels: &BTreeMap<String, String>,
    runner_node: &str,
) -> Result<AdmissionBattery> {
    let accepted = pod(config, template, id, labels, runner_node)?;
    let other_ticket = {
        let mut hex: Vec<char> = id.hex().chars().collect();
        hex[0] = if hex[0] == '0' { '1' } else { '0' };
        VmId(hex.into_iter().collect()).ticket_name()
    };
    let other_image = {
        let (repository, digest) = config
            .image
            .split_once("@sha256:")
            .expect("checked on load");
        let flipped: String = digest
            .chars()
            .enumerate()
            .map(|(i, c)| {
                if i == 0 {
                    if c == '0' { '1' } else { '0' }
                } else {
                    c
                }
            })
            .collect();
        format!("{repository}@sha256:{flipped}")
    };
    let ticket = id.ticket_name();
    let node = runner_node.to_string();
    let ephemeral = config.disk.volume == DiskVolume::Ephemeral;
    let disk_mib = config.template(template)?.disk_mib;
    // A size that no template has.
    let other_size = config
        .templates
        .values()
        .map(|t| t.disk_mib)
        .max()
        .unwrap_or(0)
        + 1024;
    let claim = |change: Change| claim_case(ephemeral, disk_mib, change);
    let cases: Vec<(&'static str, bool, Change)> = vec![
        (
            "another ServiceAccount",
            false,
            Box::new(|p| p["spec"]["serviceAccountName"] = json!("default")),
        ),
        (
            "a ServiceAccount token mounted automatically",
            false,
            Box::new(|p| p["spec"]["automountServiceAccountToken"] = json!(true)),
        ),
        (
            "a projected token volume",
            false,
            Box::new(|p| {
                volumes(p).push(json!({"name": "token", "projected": {"sources": [
                    {"serviceAccountToken": {"path": "token", "expirationSeconds": 3600}}
                ]}}));
            }),
        ),
        (
            "another run's ticket Secret",
            false,
            Box::new(move |p| {
                volumes(p)[0]["secret"]["secretName"] = json!(other_ticket);
            }),
        ),
        (
            "an environment variable from a Secret",
            false,
            Box::new({
                let ticket = ticket.clone();
                move |p| {
                    container(p)["env"].as_array_mut().expect("env").push(json!(
                    {"name": "TICKET", "valueFrom": {"secretKeyRef": {"name": ticket, "key": vm_pod::TICKET_KEY}}}
                ));
                }
            }),
        ),
        (
            "the environment from a Secret",
            false,
            Box::new(move |p| {
                container(p)["envFrom"] = json!([{"secretRef": {"name": ticket}}]);
            }),
        ),
        (
            "the host's user namespace",
            false,
            Box::new(|p| p["spec"]["hostUsers"] = json!(true)),
        ),
        (
            "a hostPath volume",
            false,
            Box::new(|p| {
                volumes(p)
                    .push(json!({"name": "host", "hostPath": {"path": "/", "type": "Directory"}}));
            }),
        ),
        // Kubernetes refuses `hostNetwork` with `hostUsers: false` in its validation.
        (
            "the host's network",
            true,
            Box::new(|p| p["spec"]["hostNetwork"] = json!(true)),
        ),
        // `privileged` with `allowPrivilegeEscalation: false` is refused by validation; the
        // escalation goes with the privilege, so that only the admission policy can refuse it.
        (
            "a privileged container",
            false,
            Box::new(|p| {
                let security = &mut container(p)["securityContext"];
                security["privileged"] = json!(true);
                security["allowPrivilegeEscalation"] = json!(true);
            }),
        ),
        // Not `SYS_ADMIN`: with `allowPrivilegeEscalation: false` validation refuses it.
        (
            "an added capability (SYS_PTRACE)",
            false,
            Box::new(|p| {
                container(p)["securityContext"]["capabilities"]["add"] = json!(["SYS_PTRACE"]);
            }),
        ),
        (
            "NET_ADMIN on the VM's container",
            false,
            Box::new(|p| {
                container(p)["securityContext"]["capabilities"]["add"] = json!(["NET_ADMIN"]);
            }),
        ),
        (
            "another image",
            false,
            Box::new(move |p| container(p)["image"] = json!(other_image)),
        ),
        // A claim with a source would copy any claim or snapshot of the namespace into the pod.
        (
            "an ephemeral volume with a dataSource",
            false,
            Box::new(|p| volumes(p).push(sourced_volume("dataSource"))),
        ),
        (
            "an ephemeral volume with a dataSourceRef",
            false,
            Box::new(|p| volumes(p).push(sourced_volume("dataSourceRef"))),
        ),
        // The claim of the disk, bound in every field that could reach another volume or size.
        (
            "a claim with a volumeName",
            false,
            claim(Box::new(|c| c["volumeName"] = json!("another-volume"))),
        ),
        (
            "a claim with a selector",
            false,
            claim(Box::new(|c| {
                c["selector"] = json!({"matchLabels": {"volume": "another"}});
            })),
        ),
        (
            "a claim of another size",
            false,
            claim(Box::new(move |c| {
                c["resources"]["requests"]["storage"] = json!(format!("{other_size}Mi"));
            })),
        ),
        (
            "a claim of another access mode",
            false,
            claim(Box::new(|c| c["accessModes"] = json!(["ReadWriteMany"]))),
        ),
        (
            "a block claim",
            false,
            claim(Box::new(|c| c["volumeMode"] = json!("Block"))),
        ),
        // Placement: the pod goes only where the scheduler puts it, on the workers for runs,
        // never beside the runner pod.
        (
            "a node's name",
            false,
            Box::new({
                let node = node.clone();
                move |p| p["spec"]["nodeName"] = json!(node)
            }),
        ),
        (
            "another nodeSelector",
            false,
            Box::new({
                let node = node.clone();
                move |p| p["spec"]["nodeSelector"] = json!({"kubernetes.io/hostname": node})
            }),
        ),
        (
            "one more toleration",
            false,
            Box::new(|p| {
                p["spec"]["tolerations"]
                    .as_array_mut()
                    .expect("tolerations")
                    .push(json!({"operator": "Exists"}));
            }),
        ),
        (
            "another scheduler",
            false,
            Box::new(|p| p["spec"]["schedulerName"] = json!("another-scheduler")),
        ),
        (
            "no anti-affinity against the runner pod",
            false,
            Box::new(|p| {
                let affinity = p["spec"]["affinity"].as_object_mut().expect("affinity");
                affinity.remove("podAntiAffinity");
            }),
        ),
        (
            "a node affinity toward a node",
            false,
            Box::new(|p| {
                p["spec"]["affinity"]["nodeAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
                    ["nodeSelectorTerms"][0]["matchFields"][0]["operator"] = json!("In");
            }),
        ),
        (
            "a pod affinity",
            false,
            Box::new(|p| {
                let affinity = &mut p["spec"]["affinity"];
                affinity["podAffinity"] = affinity["podAntiAffinity"].clone();
            }),
        ),
    ];
    let refused = cases
        .into_iter()
        .map(|(name, refused_by_validation, change)| {
            let mut pod = accepted.clone();
            change(&mut pod);
            RefusedPod {
                name,
                pod,
                refused_by_validation,
            }
        })
        .collect();
    Ok(AdmissionBattery { accepted, refused })
}

type Change = Box<dyn Fn(&mut Value)>;

/// A change to the disk's claim: the pod's own claim with an ephemeral disk; else an ephemeral
/// volume added, with the correct claim of `disk_mib` so changed.
fn claim_case(ephemeral: bool, disk_mib: u32, change: Change) -> Change {
    Box::new(move |p| {
        if ephemeral {
            let disk = volumes(p)
                .iter_mut()
                .find(|v| v["name"] == "disk")
                .expect("the disk's volume");
            change(&mut disk["ephemeral"]["volumeClaimTemplate"]["spec"]);
        } else {
            let mut spec = json!({
                "accessModes": ["ReadWriteOnce"],
                "volumeMode": "Filesystem",
                "resources": {"requests": {"storage": format!("{disk_mib}Mi")}},
            });
            change(&mut spec);
            volumes(p).push(
                json!({"name": "claim", "ephemeral": {"volumeClaimTemplate": {"spec": spec}}}),
            );
        }
    })
}

/// An ephemeral volume whose claim is made from another claim of the namespace, by `field`.
fn sourced_volume(field: &str) -> Value {
    let mut spec = json!({
        "accessModes": ["ReadWriteOnce"],
        "resources": {"requests": {"storage": "1Gi"}},
    });
    spec[field] = json!({"kind": "PersistentVolumeClaim", "name": "another-claim"});
    json!({"name": "sourced", "ephemeral": {"volumeClaimTemplate": {"spec": spec}}})
}

fn volumes(pod: &mut Value) -> &mut Vec<Value> {
    pod["spec"]["volumes"].as_array_mut().expect("volumes")
}

fn container(pod: &mut Value) -> &mut Value {
    &mut pod["spec"]["containers"][0]
}

/// A run pod's state in the driver protocol.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PodState {
    /// Being deleted. A pod that is not there at all (404) is gone too; the caller knows that.
    Gone,
    /// Pending, or running but not ready.
    Starting,
    /// Running and ready: the VM read its ticket and its bridge accepts at the pod's address.
    Running { address: String, port: u16 },
    /// Ended. The message is `vm-pod`'s termination message, or the pod's own reason.
    Failed { message: String },
}

/// The state of a run pod as the API returns it.
pub fn pod_state(pod: &Value, bridge_port: u16) -> PodState {
    if !pod["metadata"]["deletionTimestamp"].is_null() {
        return PodState::Gone;
    }
    let status = &pod["status"];
    match status["phase"].as_str().unwrap_or_default() {
        "Failed" | "Succeeded" => PodState::Failed {
            message: failure_message(status),
        },
        "Running" => {
            let ready = status["conditions"]
                .as_array()
                .into_iter()
                .flatten()
                .any(|c| c["type"] == "Ready" && c["status"] == "True");
            match status["podIP"].as_str() {
                Some(ip) if ready && !ip.is_empty() => PodState::Running {
                    address: ip.to_string(),
                    port: bridge_port,
                },
                _ => PodState::Starting,
            }
        }
        _ => PodState::Starting,
    }
}

fn failure_message(status: &Value) -> String {
    let terminated = |list: &Value, name: &str| {
        list.as_array()
            .into_iter()
            .flatten()
            .find(|c| c["name"] == name)
            .and_then(|c| c["state"]["terminated"]["message"].as_str())
            .filter(|m| !m.trim().is_empty())
            .map(str::to_string)
    };
    let message = terminated(&status["containerStatuses"], vm_pod::CONTAINER)
        .or_else(|| terminated(&status["initContainerStatuses"], vm_pod::INIT_CONTAINER))
        .unwrap_or_else(|| {
            let reason = status["reason"].as_str().unwrap_or("no reason");
            let text = status["message"].as_str().unwrap_or("no message");
            format!("the pod ended: {reason}: {text}")
        });
    bounded(message.trim())
}

/// A text without control characters, at most [`MAX_MESSAGE`] characters.
fn bounded(text: &str) -> String {
    let mut out: String = text
        .chars()
        .take(MAX_MESSAGE)
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    if text.chars().count() > MAX_MESSAGE {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::super::config::tests::{DIGEST, config, good};
    use super::*;

    const HOST: &str = "0192f1a4-0000-7000-8000-00000000aaaa";
    const HEX: &str = "00112233445566778899aabbccddeeff";
    const NODE: &str = "worker-1";

    fn id() -> VmId {
        VmId::parse(&format!("{POD_PREFIX}{HEX}")).unwrap()
    }

    fn labels() -> BTreeMap<String, String> {
        BTreeMap::from([
            ("tinfirm-host".to_string(), HOST.to_string()),
            ("tinfirm-vm".to_string(), "pool-7".to_string()),
        ])
    }

    fn init_ephemeral() -> PodConfig {
        let mut value = good();
        value["tap"] = json!({"mode": "init", "cidr": "172.30.0.0/30"});
        value["disk"] =
            json!({"variant": "copy", "volume": "ephemeral", "storageClassName": "local-xfs"});
        config(&value)
    }

    /// Every combination the configuration allows: two tap modes, both volumes, with and
    /// without tolerations.
    fn configurations() -> Vec<PodConfig> {
        let mut out = Vec::new();
        for tap in [
            json!({"mode": "multus", "networkAttachment": "kube-system/tinfirm-tap", "cidr": "172.30.0.0/30"}),
            json!({"mode": "init", "cidr": "10.0.0.4/30"}),
        ] {
            for disk in [
                json!({"variant": "copy", "volume": "emptyDir"}),
                json!({"variant": "copy", "volume": "ephemeral"}),
            ] {
                for tolerations in [json!([]), good()["tolerations"].clone()] {
                    let mut value = good();
                    value["tap"] = tap.clone();
                    value["disk"] = disk.clone();
                    value["tolerations"] = tolerations;
                    out.push(config(&value));
                }
            }
        }
        out
    }

    #[test]
    fn ids_and_names() {
        let id = id();
        assert_eq!(id.pod_name(), format!("tinfirm-vm-{HEX}"));
        assert_eq!(id.ticket_name(), format!("tinfirm-ticket-{HEX}"));
        assert_eq!(
            VmId::parse(&VmId::random().pod_name()).unwrap().hex().len(),
            32
        );
        for bad in [
            "tinfirm-vm-",
            "tinfirm-vm-00112233445566778899AABBCCDDEEFF",
            "tinfirm-ticket-00112233445566778899aabbccddeeff",
            "tinfirm-vm-00112233445566778899aabbccddeeff0",
        ] {
            assert!(VmId::parse(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn labels_map_both_ways_and_a_bad_value_is_an_error() {
        let long = "x".repeat(64);
        let mapped = kubernetes_labels(&labels()).unwrap();
        assert_eq!(
            mapped,
            BTreeMap::from([
                ("tinfirm.flowtivo.com/host".to_string(), HOST.to_string()),
                ("tinfirm.flowtivo.com/vm".to_string(), "pool-7".to_string()),
            ])
        );
        let pod = pod(&config(&good()), "sandbox-4c8g", &id(), &labels(), NODE).unwrap();
        assert_eq!(protocol_labels(&pod), labels());
        for (key, value) in [
            ("other", "x"),
            ("tinfirm-sandbox", "x"),
            ("tinfirm-a b", "x"),
            ("tinfirm-host", "a,b"),
            ("tinfirm-host", long.as_str()),
            ("tinfirm-host", "-x"),
        ] {
            let labels = BTreeMap::from([(key.to_string(), value.to_string())]);
            assert!(kubernetes_labels(&labels).is_err(), "{key}={value}");
        }
    }

    #[test]
    fn the_pod_in_the_multus_and_copy_variant() {
        let pod = pod(&config(&good()), "sandbox-4c8g", &id(), &labels(), NODE).unwrap();
        let resources = json!({"cpu": "4", "memory": "8400Mi", "ephemeral-storage": "32832Mi",
                               "devices.example.com/kvm": "1", "devices.example.com/tun": "1"});
        let field = |path: &str| json!({"fieldRef": {"apiVersion": "v1", "fieldPath": path}});
        let expected = json!({
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {
                "name": format!("tinfirm-vm-{HEX}"),
                "namespace": "tinfirm-runs",
                "labels": {
                    "tinfirm.flowtivo.com/host": HOST,
                    "tinfirm.flowtivo.com/sandbox": "vm",
                    "tinfirm.flowtivo.com/vm": "pool-7",
                },
                "annotations": {"k8s.v1.cni.cncf.io/networks": "tinfirm-tap@tap0"},
            },
            "spec": {
                "serviceAccountName": "tinfirm-run",
                "automountServiceAccountToken": false,
                "enableServiceLinks": false,
                "hostUsers": false,
                "restartPolicy": "Never",
                "terminationGracePeriodSeconds": 5,
                "securityContext": {"runAsNonRoot": true, "runAsUser": 10001, "runAsGroup": 10001,
                                    "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
                "nodeSelector": {"tinfirm.flowtivo.com/kvm": "true"},
                "tolerations": [{"key": "tinfirm.flowtivo.com/kvm", "operator": "Exists", "effect": "NoSchedule"}],
                "affinity": {
                    "nodeAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": [
                        {"matchFields": [{"key": "metadata.name", "operator": "NotIn", "values": ["worker-1"]}]}
                    ]}},
                    "podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [{
                        "labelSelector": {"matchLabels": {"app.kubernetes.io/component": "runner",
                                                          "app.kubernetes.io/instance": "tinfirm"}},
                        "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "tinfirm"}},
                        "topologyKey": "kubernetes.io/hostname",
                    }]},
                },
                "containers": [{
                    "name": "vm",
                    "image": format!("registry.example.com:5000/tinfirm/vm-pod@{DIGEST}"),
                    "command": ["/tinfirm/tinfirm-runner-host", "vm-pod"],
                    "env": [
                        {"name": "TINFIRM_POD_NAME", "valueFrom": field("metadata.name")},
                        {"name": "TINFIRM_POD_IP", "valueFrom": field("status.podIP")},
                        {"name": "TINFIRM_TAP_MODE", "value": "multus"},
                        {"name": "TINFIRM_TAP_CIDR", "value": "172.30.0.0/30"},
                        {"name": "TINFIRM_BRIDGE_PORT", "value": "7443"},
                        {"name": "TINFIRM_VCPUS", "value": "4"},
                        {"name": "TINFIRM_MEMORY_MIB", "value": "8192"},
                        {"name": "TINFIRM_DISK_MIB", "value": "32768"},
                        {"name": "TINFIRM_DISK_VARIANT", "value": "copy"},
                    ],
                    "ports": [{"name": "bridge", "containerPort": 7443, "protocol": "TCP"}],
                    "readinessProbe": {
                        "exec": {"command": ["/tinfirm/tinfirm-runner-host", "vm-pod", "ready"]},
                        "periodSeconds": 1, "timeoutSeconds": 1, "successThreshold": 1, "failureThreshold": 1,
                    },
                    "resources": {"requests": resources, "limits": resources},
                    "securityContext": {"allowPrivilegeEscalation": false, "readOnlyRootFilesystem": true,
                                        "runAsNonRoot": true, "capabilities": {"drop": ["ALL"]}},
                    "terminationMessagePath": "/dev/termination-log",
                    "terminationMessagePolicy": "File",
                    "volumeMounts": [
                        {"name": "ticket", "mountPath": "/tinfirm/ticket", "readOnly": true},
                        {"name": "run", "mountPath": "/tinfirm/run"},
                        {"name": "disk", "mountPath": "/tinfirm/disk"},
                    ],
                }],
                "volumes": [
                    {"name": "ticket", "secret": {"secretName": format!("tinfirm-ticket-{HEX}"),
                        "items": [{"key": "user-data", "path": "user-data"}], "defaultMode": 288}},
                    {"name": "run", "emptyDir": {"medium": "Memory", "sizeLimit": "16Mi"}},
                    {"name": "disk", "emptyDir": {"sizeLimit": "32768Mi"}},
                ],
            },
        });
        assert_eq!(
            pod,
            expected,
            "{}",
            serde_json::to_string_pretty(&pod).unwrap()
        );
    }

    #[test]
    fn the_pod_in_the_init_variant_with_an_ephemeral_disk() {
        let multus = pod(&config(&good()), "sandbox-4c8g", &id(), &labels(), NODE).unwrap();
        let init = pod(&init_ephemeral(), "sandbox-4c8g", &id(), &labels(), NODE).unwrap();
        // What differs from the Multus and copy variant, path by path.
        let mut seen = Vec::new();
        differences("", &multus, &init, &mut seen);
        assert_eq!(
            seen,
            [
                ".metadata.annotations: missing".to_string(),
                ".spec.containers[0].env[2].value: \"init\", expected \"multus\"".to_string(),
                ".spec.containers[0].resources.requests.ephemeral-storage: \"64Mi\", expected \"32832Mi\"".to_string(),
                ".spec.containers[0].resources.limits.ephemeral-storage: \"64Mi\", expected \"32832Mi\"".to_string(),
                ".spec.volumes[2].emptyDir: missing".to_string(),
                ".spec.volumes[2].ephemeral: not expected".to_string(),
                ".spec.initContainers: not expected".to_string(),
            ]
        );
        assert_eq!(
            init["spec"]["initContainers"],
            json!([{
                "name": "tap",
                "image": format!("registry.example.com:5000/tinfirm/vm-pod@{DIGEST}"),
                "command": ["/tinfirm/tinfirm-runner-host", "vm-pod", "tap-setup"],
                "env": [{"name": "TINFIRM_TAP_CIDR", "value": "172.30.0.0/30"}],
                "resources": {
                    "requests": {"cpu": "100m", "memory": "32Mi", "devices.example.com/tun": "1"},
                    "limits": {"cpu": "100m", "memory": "32Mi", "devices.example.com/tun": "1"},
                },
                "securityContext": {"runAsUser": 0, "runAsNonRoot": false, "allowPrivilegeEscalation": false,
                                    "readOnlyRootFilesystem": true,
                                    "capabilities": {"drop": ["ALL"], "add": ["NET_ADMIN"]}},
                "terminationMessagePath": "/dev/termination-log",
                "terminationMessagePolicy": "File",
            }])
        );
        assert_eq!(
            init["spec"]["volumes"][2],
            json!({"name": "disk", "ephemeral": {"volumeClaimTemplate": {
                "metadata": {"labels": {
                    "tinfirm.flowtivo.com/host": HOST,
                    "tinfirm.flowtivo.com/sandbox": "vm",
                    "tinfirm.flowtivo.com/vm": "pool-7",
                }},
                "spec": {
                    "accessModes": ["ReadWriteOnce"],
                    "volumeMode": "Filesystem",
                    "resources": {"requests": {"storage": "32768Mi"}},
                    "storageClassName": "local-xfs",
                },
            }}})
        );
    }

    /// Every container of a pod, init containers included.
    fn containers(pod: &Value) -> Vec<(bool, &Value)> {
        let list = |key: &str, init: bool| {
            pod["spec"][key]
                .as_array()
                .into_iter()
                .flatten()
                .map(move |c| (init, c))
                .collect::<Vec<_>>()
        };
        let mut out = list("initContainers", true);
        out.extend(list("containers", false));
        out
    }

    /// The rules every run pod keeps.
    fn assert_invariants(config: &PodConfig, pod: &Value) {
        let spec = &pod["spec"];
        let name = pod["metadata"]["name"].as_str().unwrap();
        let id = VmId::parse(name).unwrap();
        assert_eq!(spec["hostUsers"], false);
        assert_eq!(spec["automountServiceAccountToken"], false);
        assert_eq!(
            spec["serviceAccountName"],
            json!(config.run_service_account)
        );
        assert_eq!(spec["enableServiceLinks"], false);
        assert_eq!(spec["restartPolicy"], "Never");
        for key in ["hostNetwork", "hostPID", "hostIPC"] {
            assert!(spec.get(key).is_none(), "{key}");
        }
        let volumes = spec["volumes"].as_array().unwrap();
        for volume in volumes {
            assert!(
                volume.get("projected").is_none() && volume.get("hostPath").is_none(),
                "{volume}"
            );
            let claim = &volume["ephemeral"]["volumeClaimTemplate"]["spec"];
            assert!(
                claim.get("dataSource").is_none() && claim.get("dataSourceRef").is_none(),
                "{volume}"
            );
        }
        let secrets: Vec<&Value> = volumes
            .iter()
            .filter(|v| v.get("secret").is_some())
            .collect();
        assert_eq!(secrets.len(), 1);
        assert_eq!(secrets[0]["secret"]["secretName"], json!(id.ticket_name()));
        for (init, container) in containers(pod) {
            let security = &container["securityContext"];
            let added = &security["capabilities"]["add"];
            if init {
                assert_eq!(config.tap.mode, TapMode::Init);
                assert_eq!(added, &json!(["NET_ADMIN"]));
            } else {
                assert!(added.is_null(), "{container}");
            }
            assert_eq!(security["capabilities"]["drop"], json!(["ALL"]));
            assert_eq!(security["allowPrivilegeEscalation"], false);
            assert_eq!(security["readOnlyRootFilesystem"], true);
            assert!(security.get("privileged").is_none());
            let resources = &container["resources"];
            assert!(resources["requests"].is_object());
            assert_eq!(resources["requests"], resources["limits"]);
            let image = container["image"].as_str().unwrap();
            assert_eq!(image, config.image);
            assert!(image.contains("@sha256:"));
            assert!(container.get("envFrom").is_none());
            for env in container["env"].as_array().into_iter().flatten() {
                assert!(env["valueFrom"].get("secretKeyRef").is_none(), "{env}");
            }
        }
        if config.tap.mode == TapMode::Multus {
            assert!(spec.get("initContainers").is_none());
        }
        let anti = &spec["affinity"]["podAntiAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
            [0];
        assert_eq!(
            anti["labelSelector"]["matchLabels"],
            json!(config.runner.labels)
        );
        assert_eq!(
            anti["namespaceSelector"]["matchLabels"]["kubernetes.io/metadata.name"],
            json!(config.runner.namespace)
        );
        assert_eq!(spec["nodeSelector"], json!(config.node_selector));
        assert_eq!(
            spec["affinity"]["nodeAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]["nodeSelectorTerms"]
                [0]["matchFields"][0],
            json!({"key": "metadata.name", "operator": "NotIn", "values": [NODE]})
        );
    }

    #[test]
    fn every_pod_keeps_the_rules() {
        let configurations = configurations();
        assert_eq!(configurations.len(), 8);
        for config in &configurations {
            for template in config.templates.keys() {
                let pod = pod(config, template, &VmId::random(), &labels(), NODE).unwrap();
                assert_invariants(config, &pod);
                // The accepted pod of the admission check is a run pod too.
                let battery = admission_battery(config, template, &id(), &labels(), NODE).unwrap();
                assert_invariants(config, &battery.accepted);
            }
        }
    }

    #[test]
    fn each_refused_pod_differs_from_the_correct_one_in_its_one_point() {
        let common: BTreeMap<&str, Vec<&str>> = BTreeMap::from([
            ("another ServiceAccount", vec![".spec.serviceAccountName"]),
            (
                "a ServiceAccount token mounted automatically",
                vec![".spec.automountServiceAccountToken"],
            ),
            ("a projected token volume", vec![".spec.volumes"]),
            (
                "another run's ticket Secret",
                vec![".spec.volumes[0].secret.secretName"],
            ),
            (
                "an environment variable from a Secret",
                vec![".spec.containers[0].env"],
            ),
            (
                "the environment from a Secret",
                vec![".spec.containers[0].envFrom"],
            ),
            ("the host's user namespace", vec![".spec.hostUsers"]),
            ("a hostPath volume", vec![".spec.volumes"]),
            ("the host's network", vec![".spec.hostNetwork"]),
            (
                "a privileged container",
                vec![
                    ".spec.containers[0].securityContext.allowPrivilegeEscalation",
                    ".spec.containers[0].securityContext.privileged",
                ],
            ),
            (
                "an added capability (SYS_PTRACE)",
                vec![".spec.containers[0].securityContext.capabilities.add"],
            ),
            (
                "NET_ADMIN on the VM's container",
                vec![".spec.containers[0].securityContext.capabilities.add"],
            ),
            ("another image", vec![".spec.containers[0].image"]),
            (
                "an ephemeral volume with a dataSource",
                vec![".spec.volumes"],
            ),
            (
                "an ephemeral volume with a dataSourceRef",
                vec![".spec.volumes"],
            ),
            ("a node's name", vec![".spec.nodeName"]),
            (
                "another nodeSelector",
                vec![
                    ".spec.nodeSelector.tinfirm.flowtivo.com/kvm",
                    ".spec.nodeSelector.kubernetes.io/hostname",
                ],
            ),
            ("one more toleration", vec![".spec.tolerations"]),
            ("another scheduler", vec![".spec.schedulerName"]),
            (
                "no anti-affinity against the runner pod",
                vec![".spec.affinity.podAntiAffinity"],
            ),
            (
                "a node affinity toward a node",
                vec![
                    ".spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchFields[0].operator",
                ],
            ),
            ("a pod affinity", vec![".spec.affinity.podAffinity"]),
        ]);
        // The claim's cases change the pod's own claim with an ephemeral disk, and add a
        // volume with an emptyDir disk.
        let claims = [
            ("a claim with a volumeName", "volumeName"),
            ("a claim with a selector", "selector"),
            ("a claim of another size", "resources.requests.storage"),
            ("a claim of another access mode", "accessModes[0]"),
            ("a block claim", "volumeMode"),
        ];
        for config in [config(&good()), init_ephemeral()] {
            let ephemeral = config.disk.volume == DiskVolume::Ephemeral;
            let mut expected: BTreeMap<&str, Vec<String>> = common
                .iter()
                .map(|(name, paths)| (*name, paths.iter().map(|p| p.to_string()).collect()))
                .collect();
            for (name, field) in claims {
                let path = if ephemeral {
                    format!(".spec.volumes[2].ephemeral.volumeClaimTemplate.spec.{field}")
                } else {
                    ".spec.volumes".to_string()
                };
                expected.insert(name, vec![path]);
            }
            let battery =
                admission_battery(&config, "sandbox-4c8g", &id(), &labels(), NODE).unwrap();
            assert_eq!(
                battery
                    .refused
                    .iter()
                    .map(|r| r.name)
                    .collect::<std::collections::BTreeSet<_>>(),
                expected.keys().copied().collect()
            );
            for refused in &battery.refused {
                let mut seen = Vec::new();
                differences("", &battery.accepted, &refused.pod, &mut seen);
                let paths: Vec<&str> = seen.iter().map(|d| d.split(':').next().unwrap()).collect();
                assert_eq!(paths, expected[refused.name], "{}: {seen:?}", refused.name);
                if !ephemeral && claims.iter().any(|(name, _)| *name == refused.name) {
                    let added = &refused.pod["spec"]["volumes"][3];
                    assert_eq!(added["name"], "claim", "{}", refused.name);
                }
                assert_eq!(
                    refused.refused_by_validation,
                    refused.name == "the host's network"
                );
            }
            let pod_of = |name: &str| &battery.refused.iter().find(|r| r.name == name).unwrap().pod;
            let other =
                pod_of("another run's ticket Secret")["spec"]["volumes"][0]["secret"]["secretName"]
                    .as_str()
                    .unwrap();
            assert!(
                other.starts_with(TICKET_PREFIX) && other != id().ticket_name(),
                "{other}"
            );
            let image = pod_of("another image")["spec"]["containers"][0]["image"]
                .as_str()
                .unwrap();
            assert!(
                image.contains("@sha256:") && image != config.image,
                "{image}"
            );
        }
    }

    #[test]
    fn the_ticket_secret() {
        let secret = ticket_secret(
            &config(&good()),
            &id(),
            &labels(),
            b"{\"ticket\":\"t\"}",
            "6f1d2c3a-0000-4000-8000-000000000001",
        )
        .unwrap();
        assert_eq!(
            secret,
            json!({
                "apiVersion": "v1",
                "kind": "Secret",
                "metadata": {
                    "name": format!("tinfirm-ticket-{HEX}"),
                    "namespace": "tinfirm-runs",
                    "labels": {"tinfirm.flowtivo.com/host": HOST, "tinfirm.flowtivo.com/vm": "pool-7"},
                    "ownerReferences": [{"apiVersion": "v1", "kind": "Pod",
                                         "name": format!("tinfirm-vm-{HEX}"),
                                         "uid": "6f1d2c3a-0000-4000-8000-000000000001"}],
                },
                "type": "Opaque",
                "immutable": true,
                "data": {"user-data": "eyJ0aWNrZXQiOiJ0In0="},
            })
        );
        let config = config(&good());
        assert!(ticket_secret(&config, &id(), &labels(), b"", "1").is_err());
        assert!(ticket_secret(&config, &id(), &labels(), &[b'x'; MAX_USER_DATA + 1], "1").is_err());
        assert!(ticket_secret(&config, &id(), &labels(), b"x", "").is_err());
        assert!(ticket_secret(&config, &id(), &labels(), b"x", "a/b").is_err());
    }

    #[test]
    fn the_policies_and_their_differences() {
        let config = config(&good());
        assert_eq!(
            default_deny_spec(),
            json!({"podSelector": {}, "policyTypes": ["Ingress", "Egress"]})
        );
        let allow = allow_runner_spec(&config);
        assert_eq!(
            allow,
            json!({
                "podSelector": {"matchLabels": {"tinfirm.flowtivo.com/sandbox": "vm"}},
                "policyTypes": ["Ingress"],
                "ingress": [{
                    "from": [{
                        "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "tinfirm"}},
                        "podSelector": {"matchLabels": {"app.kubernetes.io/component": "runner",
                                                        "app.kubernetes.io/instance": "tinfirm"}},
                    }],
                    "ports": [{"protocol": "TCP", "port": 7443}],
                }],
            })
        );
        let read =
            |spec: Value| json!({"metadata": {"name": "p", "resourceVersion": "7"}, "spec": spec});
        assert!(policy_differences(&allow, &read(allow.clone())).is_empty());
        assert!(policy_differences(&default_deny_spec(), &read(default_deny_spec())).is_empty());
        assert_eq!(
            policy_differences(&allow, &json!({"metadata": {}})),
            ["spec: missing"]
        );
        let mut opened = allow.clone();
        opened["ingress"][0]["from"] = json!([{"namespaceSelector": {}}]);
        opened["ingress"][0]["ports"][0]["port"] = json!(22);
        opened["egress"] = json!([{}]);
        assert_eq!(
            policy_differences(&allow, &read(opened)),
            [
                "spec.ingress[0].from[0].namespaceSelector.matchLabels: missing",
                "spec.ingress[0].from[0].podSelector: missing",
                "spec.ingress[0].ports[0].port: 22, expected 7443",
                "spec.egress: not expected",
            ]
        );
        let mut deny = default_deny_spec();
        deny["policyTypes"] = json!(["Ingress"]);
        assert_eq!(
            policy_differences(&default_deny_spec(), &read(deny)),
            ["spec.policyTypes: 1 items, expected 2"]
        );
    }

    /// The chart renders the driver's configuration and the runs' two network policies; its
    /// CI step (deploy/helm/check-runner-pod.sh) compares them with these files for
    /// ci/runner-pod-values.yaml. Here the driver reads the same files: it loads the
    /// configuration, and expects the very policies the chart installs.
    #[test]
    fn the_charts_objects_are_what_the_driver_expects() {
        const CONFIG: &str =
            include_str!("../../../../../deploy/helm/tinfirm/ci/expected/pod-driver.json");
        const DEFAULT_DENY: &str =
            include_str!("../../../../../deploy/helm/tinfirm/ci/expected/runs-default-deny.json");
        const ALLOW_RUNNER: &str =
            include_str!("../../../../../deploy/helm/tinfirm/ci/expected/runs-allow-runner.json");
        let config = PodConfig::from_json(CONFIG).unwrap();
        let spec = |text: &str| serde_json::from_str::<Value>(text).unwrap();
        assert_eq!(spec(DEFAULT_DENY), default_deny_spec());
        assert_eq!(spec(ALLOW_RUNNER), allow_runner_spec(&config));
        let read = |text: &str| json!({"metadata": {"name": "p"}, "spec": spec(text)});
        assert!(policy_differences(&default_deny_spec(), &read(DEFAULT_DENY)).is_empty());
        assert!(policy_differences(&allow_runner_spec(&config), &read(ALLOW_RUNNER)).is_empty());
        // The chart names the objects as the driver's defaults do, and run pods fit its
        // template.
        assert_eq!(config.policies, super::super::config::Policies::default());
        assert_eq!(config.resource_quota, "tinfirm-runs");
        assert_eq!(config.run_service_account, "tinfirm-run");
        pod(&config, "sandbox-4c8g", &id(), &labels(), NODE).unwrap();
    }

    #[test]
    fn a_pods_state() {
        let pod = |phase: &str, ready: bool, ip: Option<&str>| {
            json!({"metadata": {"name": "p"}, "status": {
                "phase": phase,
                "podIP": ip,
                "conditions": [{"type": "Ready", "status": if ready { "True" } else { "False" }}],
            }})
        };
        assert_eq!(
            pod_state(&pod("Pending", false, None), 7443),
            PodState::Starting
        );
        assert_eq!(
            pod_state(&pod("Running", false, Some("10.1.2.3")), 7443),
            PodState::Starting
        );
        assert_eq!(
            pod_state(&pod("Running", true, None), 7443),
            PodState::Starting
        );
        assert_eq!(pod_state(&json!({}), 7443), PodState::Starting);
        assert_eq!(
            pod_state(&pod("Running", true, Some("10.1.2.3")), 7443),
            PodState::Running {
                address: "10.1.2.3".into(),
                port: 7443
            }
        );
        let mut deleting = pod("Running", true, Some("10.1.2.3"));
        deleting["metadata"]["deletionTimestamp"] = json!("2026-11-20T10:00:00Z");
        assert_eq!(pod_state(&deleting, 7443), PodState::Gone);

        let mut failed = pod("Failed", false, Some("10.1.2.3"));
        failed["status"]["containerStatuses"] = json!([{"name": "vm", "state": {"terminated": {
            "exitCode": 1, "message": "firecracker: cannot open /dev/kvm\n"}}}]);
        assert_eq!(
            pod_state(&failed, 7443),
            PodState::Failed {
                message: "firecracker: cannot open /dev/kvm".into()
            }
        );
        let mut init_failed = pod("Failed", false, None);
        init_failed["status"]["initContainerStatuses"] = json!([{"name": "tap", "state": {"terminated": {
            "exitCode": 1, "message": "net.ipv4.ip_forward is 1"}}}]);
        assert_eq!(
            pod_state(&init_failed, 7443),
            PodState::Failed {
                message: "net.ipv4.ip_forward is 1".into()
            }
        );
        let mut evicted = pod("Failed", false, None);
        evicted["status"]["reason"] = json!("Evicted");
        evicted["status"]["message"] = json!("The node was low on resource: memory.");
        assert_eq!(
            pod_state(&evicted, 7443),
            PodState::Failed {
                message: "the pod ended: Evicted: The node was low on resource: memory.".into()
            }
        );
        let mut long = pod("Succeeded", false, None);
        long["status"]["containerStatuses"] = json!([{"name": "vm", "state": {"terminated": {
            "message": format!("a\u{1b}[31m{}", "x".repeat(4000))}}}]);
        let PodState::Failed { message } = pod_state(&long, 7443) else {
            panic!()
        };
        assert_eq!(message.chars().count(), MAX_MESSAGE + 1);
        assert!(message.starts_with("a [31m"), "{message}");
    }

    #[test]
    fn a_pod_needs_a_known_template_and_a_node_name() {
        let config = config(&good());
        assert!(pod(&config, "other", &id(), &labels(), NODE).is_err());
        assert!(pod(&config, "sandbox-4c8g", &id(), &labels(), "").is_err());
        assert!(pod(&config, "sandbox-4c8g", &id(), &labels(), "Worker 1").is_err());
        let bad = BTreeMap::from([("tinfirm-host".to_string(), "a b".to_string())]);
        assert!(pod(&config, "sandbox-4c8g", &id(), &bad, NODE).is_err());
    }
}
