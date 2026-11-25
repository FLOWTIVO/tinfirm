//! The pod-per-run VM driver (ADR-0115 decision 2): the methods of the driver protocol
//! (crate::vm) over the Kubernetes API. Part of elastic runners, under the Tinfirm Enterprise
//! licence (`ee/LICENSE`).
//!
//! What it does to the cluster, in the runs' namespace only:
//!
//! - `create`: checks the admission policy (at most once a minute per template), creates the
//!   pod, then the ticket's Secret, owned by the pod. The pod's Secret volume holds every
//!   container back until the Secret exists. When the Secret cannot be made, the pod is deleted;
//!   so is a pod whose create got no clear answer, since the cluster may hold it all the same.
//!   A pod that the quota refuses is tried again for up to 10 s while a pod of the host's is
//!   ending or this driver has just deleted one: that pod's share of the quota comes back.
//! - `describe` and `list`: read pods. The first time a pod is seen ready, its ticket's Secret
//!   is deleted: the VM has read it.
//! - `delete`: reads the pod, refuses it unless it carries every label of the request, then
//!   deletes the ticket's Secret and the pod, and answers once the pod is gone from the API or
//!   after 20 s: until it is gone the pod counts against the quota, and the host takes the
//!   answer for a free slot.
//! - `verify`: reads the ResourceQuota and the two network policies, lists the run pods, and
//!   checks the admission policy with dry runs.
//!
//! It never reads a Secret, and nothing it writes to its log or answers holds a run's user data.
//!
//! The protocol has one error code for a method that failed, and the host reads only its
//! message. What makes the host stop taking runs is `verify`: problems and no room. So `verify`
//! answers no room, whatever the quota holds, with each problem for which `create` refuses
//! every pod: a network policy that is missing, changed or added, an admission check that
//! fails, a runner node that is not known.
//!
//! A quota that run pods have filled is no problem: `verify` answers no room, the host bounds
//! its runs by its own slots, and at its start it counts its own VMs to that room, so a host
//! that starts again beside its run pods does start. A quota without room that no run pod
//! holds is a problem: it is too small, or something else fills it. `verify` is not told which
//! host asks, so every run pod of the namespace counts as one that may be the host's; the
//! host's own count at its start tells them apart. A create refused for the quota says so in
//! its message.

use std::collections::{BTreeMap, HashSet};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use tokio::io::{AsyncRead, AsyncWrite};

use super::api::{Api, ApiError, DryRun, Failure};
use super::config::{DiskVolume, PodConfig};
use super::spec::{self, PodState, VmId};
use crate::vm::{DRIVER_PROTOCOL, HOST_LABEL, lock};

/// The driver's name in `hello`.
pub const DRIVER: &str = "pod";
/// How long an admission check that passed is not made again before a create.
const ADMISSION_CHECK_TTL: Duration = Duration::from_secs(60);
/// How long a run pod may wait for a node before `verify` reports it.
const UNSCHEDULABLE_AFTER_SECONDS: i64 = 60;
/// The grace period of a deleted pod: Firecracker ends at SIGTERM.
const GRACE_SECONDS: u32 = 5;
/// How long the delete of a pod may take whose create got no clear answer.
const UNSURE_DELETE_TIMEOUT: Duration = Duration::from_secs(30);
/// How long `delete` waits for the pod to be gone from the API: its grace period, and time for
/// the kubelet to clear it away. Far below the 120 s the host gives a delete.
const GONE_WAIT: Duration = Duration::from_secs(20);
/// How long `create` tries again after the quota's refusal, while room is on its way.
const ROOM_WAIT: Duration = Duration::from_secs(10);
/// How often `delete` and `create` look again while they wait.
const POLL: Duration = Duration::from_millis(500);
/// The longest text of the cluster's that a problem quotes.
const MAX_QUOTED: usize = 300;
/// The most unschedulable pods that `verify` names.
const MAX_UNSCHEDULABLE: usize = 3;

/// The driver: its configuration, the API, the runner pod's node and what it remembers.
pub struct PodDriver {
    config: PodConfig,
    api: Api,
    /// The node the runner pod runs on, which no run pod may share (`TINFIRM_NODE_NAME`).
    runner_node: Option<String>,
    admission_ttl: Duration,
    gone_wait: Duration,
    room_wait: Duration,
    poll: Duration,
    /// The templates whose admission check passed, and when.
    admission_checked: Mutex<BTreeMap<String, Instant>>,
    /// When a delete of this driver's last ended: the quota may count that pod a moment longer.
    deleted: Mutex<Option<Instant>>,
    /// The pods whose ticket's Secret is deleted.
    tickets_deleted: Mutex<HashSet<String>>,
    /// The pods whose failure is written to the log.
    failures_logged: Mutex<HashSet<String>>,
}

impl PodDriver {
    pub fn new(config: PodConfig, api: Api, runner_node: Option<String>) -> PodDriver {
        PodDriver {
            config,
            api,
            runner_node: runner_node.filter(|n| !n.is_empty()),
            admission_ttl: ADMISSION_CHECK_TTL,
            gone_wait: GONE_WAIT,
            room_wait: ROOM_WAIT,
            poll: POLL,
            admission_checked: Mutex::new(BTreeMap::new()),
            deleted: Mutex::new(None),
            tickets_deleted: Mutex::new(HashSet::new()),
            failures_logged: Mutex::new(HashSet::new()),
        }
    }

    /// One request of the driver protocol.
    pub async fn handle(&self, method: &str, params: Value) -> Result<Value> {
        match method {
            "hello" => Ok(hello()),
            "verify" => self.verify(str_param(&params, "template")?).await,
            "create" => {
                self.create(
                    str_param(&params, "template")?,
                    str_param(&params, "userData")?,
                    &labels_param(&params)?,
                )
                .await
            }
            "describe" => self.describe(str_param(&params, "id")?).await,
            "delete" => {
                self.delete(str_param(&params, "id")?, &labels_param(&params)?)
                    .await
            }
            "list" => self.list(&labels_param(&params)?).await,
            other => bail!("no method `{other}`"),
        }
    }

    fn namespace(&self) -> &str {
        &self.config.namespace
    }

    fn runner_node(&self) -> Result<&str> {
        self.runner_node.as_deref().context(
            "the runner pod's node is unknown (TINFIRM_NODE_NAME): a run pod could land beside it",
        )
    }

    // ----- create --------------------------------------------------------------------

    /// Creates a run's pod and its ticket's Secret; the VM's id is the pod's name. The
    /// protocol's `name` is not used: the driver names its pods.
    pub async fn create(
        &self,
        template: &str,
        user_data: &str,
        labels: &BTreeMap<String, String>,
    ) -> Result<Value> {
        self.config.template(template)?;
        if !labels.get(HOST_LABEL).is_some_and(|h| !h.is_empty()) {
            bail!("a sandbox needs the label {HOST_LABEL}");
        }
        if user_data.is_empty() || user_data.len() > spec::MAX_USER_DATA {
            bail!(
                "the user data is empty or over {} bytes",
                spec::MAX_USER_DATA
            );
        }
        let node = self.runner_node()?;
        let id = VmId::random();
        let name = id.pod_name();
        let pod = spec::pod(&self.config, template, &id, labels, node)?;
        let started = Instant::now();
        let mut room_coming = false;
        let created = loop {
            let tried = self.try_create(template, &name, &pod).await;
            // The quota refused the pod. The host asks for a VM as soon as it has a free slot,
            // and a pod it deleted for that slot holds its share of the quota until it is gone
            // and the cluster has counted again: that room is tried for again, for a moment.
            let no_room = tried.as_ref().is_err_and(|e| e.is::<NoRoom>());
            if !no_room || started.elapsed() >= self.room_wait {
                break tried?;
            }
            room_coming = room_coming || self.room_is_coming(labels).await;
            if !room_coming {
                break tried?;
            }
            tokio::time::sleep(self.poll).await;
        };
        let uid = created["metadata"]["uid"].as_str().unwrap_or_default();
        let secret = spec::ticket_secret(&self.config, &id, labels, user_data.as_bytes(), uid);
        let made = match secret {
            Ok(secret) => self
                .api
                .create_secret(self.namespace(), &secret, DryRun::No)
                .await
                .map_err(|e| secret_error(&e)),
            Err(e) => Err(e),
        };
        if let Err(why) = made {
            // The pod never started: its Secret volume waits for the Secret.
            return Err(match self.api.delete_pod(self.namespace(), &name, Some(0)).await {
                Ok(()) => why.context(format!("the pod {name} is deleted again")),
                Err(e) if e.is_not_found() => why,
                Err(e) => why.context(format!(
                    "and the pod {name} could not be deleted ({e}): the host's cleanup of leftovers deletes it, since it carries the host's label"
                )),
            });
        }
        eprintln!("created pod {name}");
        Ok(json!({ "id": name }))
    }

    /// One try of a run pod's create: the admission check, then the pod; the server's pod.
    async fn try_create(&self, template: &str, name: &str, pod: &Value) -> Result<Value> {
        self.check_admission(template).await?;
        match self.api.create_pod(self.namespace(), pod, DryRun::No).await {
            Ok(created) => Ok(created),
            Err(e) if maybe_stored(&e) => {
                Err(self.delete_unsure(name, create_error("the pod", &e)).await)
            }
            Err(e) => Err(create_error("the pod", &e)),
        }
    }

    /// Whether room that the quota refused is on its way: this driver deleted a pod moments
    /// ago, or a pod of the host that `labels` name is ending. A read that fails tells of none.
    async fn room_is_coming(&self, labels: &BTreeMap<String, String>) -> bool {
        if lock(&self.deleted).is_some_and(|at| at.elapsed() < self.room_wait) {
            return true;
        }
        let host = labels
            .iter()
            .filter(|(key, _)| key.as_str() == HOST_LABEL)
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect();
        let Ok(mut selector) = spec::kubernetes_labels(&host) else {
            return false;
        };
        selector.insert(spec::SANDBOX_LABEL.into(), spec::SANDBOX_VALUE.into());
        self.api
            .list_pods(self.namespace(), &selector)
            .await
            .is_ok_and(|pods| {
                pods.iter()
                    .any(|pod| !pod["metadata"]["deletionTimestamp"].is_null())
            })
    }

    /// Deletes the pod `name` after a create whose answer does not tell whether the server
    /// stored it ([`maybe_stored`]), and returns `why` with what became of the pod. Such a pod
    /// has no ticket, so it never starts, and it would hold its share of the quota until the
    /// host's cleanup of leftovers, which runs only when the host starts or connects again.
    /// The delete has [`UNSURE_DELETE_TIMEOUT`]: the host waits for the create's answer.
    async fn delete_unsure(&self, name: &str, why: anyhow::Error) -> anyhow::Error {
        let delete = self.api.delete_pod(self.namespace(), name, Some(0));
        let failed = match tokio::time::timeout(UNSURE_DELETE_TIMEOUT, delete).await {
            Ok(Ok(())) => {
                return why.context(format!(
                    "the server had stored the pod {name}, which is deleted again"
                ));
            }
            // The server did not store it.
            Ok(Err(e)) if e.is_not_found() => return why,
            Ok(Err(e)) => e.to_string(),
            Err(_) => format!("no answer within {UNSURE_DELETE_TIMEOUT:?}"),
        };
        why.context(format!(
            "and the pod {name}, if the server stored it, could not be deleted ({failed}): the host's cleanup of leftovers deletes it, since it carries the host's label"
        ))
    }

    /// Checks the network policies and the admission policy for `template`, unless they passed
    /// within the last minute.
    async fn check_admission(&self, template: &str) -> Result<()> {
        let fresh = lock(&self.admission_checked)
            .get(template)
            .is_some_and(|at| at.elapsed() < self.admission_ttl);
        if fresh {
            return Ok(());
        }
        // A read that fails is a problem too: nothing is cached as passed then.
        let (problems, _) = self.policy_problems().await;
        if !problems.is_empty() {
            // The policies hold for every template.
            lock(&self.admission_checked).clear();
            bail!("refusing to create a run pod: {}", problems.join("; "));
        }
        let problems = match self.admission_problems(template).await {
            Ok(problems) => problems,
            Err(e) => {
                lock(&self.admission_checked).remove(template);
                return Err(e);
            }
        };
        if !problems.is_empty() {
            lock(&self.admission_checked).remove(template);
            bail!("refusing to create a run pod: {}", problems.join("; "));
        }
        lock(&self.admission_checked).insert(template.to_string(), Instant::now());
        Ok(())
    }

    /// The network policies of the runs' namespace: the chart's two, each as expected, and no
    /// other. `verify` reports these problems, and `create` refuses while there is one (ADR-0115
    /// decision 2: no VM starts while a policy is missing or changed). The problems, and what
    /// was checked.
    async fn policy_problems(&self) -> (Vec<String>, Vec<String>) {
        let ns = self.namespace();
        let mut problems = Vec::new();
        let mut checked = Vec::new();
        // One list, so that the decision rests on one state of the namespace.
        let policies = match self.api.list_network_policies(ns).await {
            Ok(policies) => policies,
            Err(e) => return (vec![api_problem("the network policies", &e)], checked),
        };
        let name_of = |policy: &Value| {
            policy["metadata"]["name"]
                .as_str()
                .unwrap_or("?")
                .to_string()
        };
        // The two network policies the chart installs.
        let expected = [
            (
                &self.config.policies.default_deny,
                spec::default_deny_spec(),
            ),
            (
                &self.config.policies.allow_runner,
                spec::allow_runner_spec(&self.config),
            ),
        ];
        for (name, spec) in &expected {
            match policies.iter().find(|p| &&name_of(p) == name) {
                Some(policy) => {
                    let differences = spec::policy_differences(spec, policy);
                    if differences.is_empty() {
                        checked.push(format!("NetworkPolicy {name}"));
                    } else {
                        problems.push(format!(
                            "the NetworkPolicy {name} is not the one expected: {}",
                            one_line(&differences.join(", "))
                        ));
                    }
                }
                None => problems.push(format!(
                    "there is no NetworkPolicy {name} in the runs' namespace {ns}"
                )),
            }
        }
        // And no other: network policies add up, so a third one may open the run pods.
        for policy in &policies {
            let name = name_of(policy);
            if !expected.iter().any(|(expected, _)| **expected == name) {
                problems.push(format!(
                    "the runs' namespace {ns} holds the NetworkPolicy {}, which is not the chart's: network policies add up, so it may open the run pods; delete it",
                    one_line(&name)
                ));
            }
        }
        (problems, checked)
    }

    /// The admission check: dry runs of the correct pod, which the cluster must accept, and of
    /// each pod it must refuse. The problems it found; an error when it could not run.
    async fn admission_problems(&self, template: &str) -> Result<Vec<String>> {
        let battery = spec::admission_battery(
            &self.config,
            template,
            &VmId::random(),
            &BTreeMap::new(),
            self.runner_node()?,
        )?;
        let mut problems = Vec::new();
        match self.dry_run(&battery.accepted).await? {
            DryRunAnswer::Accepted => {}
            DryRunAnswer::Refused(why) => problems.push(format!(
                "the cluster refuses the correct run pod: {}",
                one_line(&why)
            )),
        }
        for refused in &battery.refused {
            if let DryRunAnswer::Accepted = self.dry_run(&refused.pod).await? {
                problems.push(format!(
                    "the cluster accepts a run pod with {}: the admission policy is missing or incomplete",
                    refused.name
                ));
            }
        }
        Ok(problems)
    }

    async fn dry_run(&self, pod: &Value) -> Result<DryRunAnswer> {
        match self
            .api
            .create_pod(self.namespace(), pod, DryRun::All)
            .await
        {
            Ok(_) => Ok(DryRunAnswer::Accepted),
            Err(e) if namespace_missing(&e) => {
                bail!("the runs' namespace {} does not exist", self.namespace())
            }
            Err(e) if quota_exceeded(&e) => {
                Err(NoRoom(", so the admission policy cannot be checked".into()).into())
            }
            Err(e) if unreachable(&e) => {
                bail!("cannot reach the API server for the admission check: {e}")
            }
            Err(e) => Ok(DryRunAnswer::Refused(e.to_string())),
        }
    }

    // ----- describe and list ----------------------------------------------------------

    pub async fn describe(&self, id: &str) -> Result<Value> {
        VmId::parse(id)?;
        match self.api.get_pod(self.namespace(), id).await {
            Ok(pod) => Ok(self.view(&pod).await),
            Err(e) if e.is_not_found() => {
                self.forget(id);
                Ok(json!({ "id": id, "state": "gone" }))
            }
            Err(e) => Err(e.into()),
        }
    }

    /// The run pods that carry every one of `labels`.
    pub async fn list(&self, labels: &BTreeMap<String, String>) -> Result<Value> {
        let mut selector = spec::kubernetes_labels(labels)?;
        selector.insert(spec::SANDBOX_LABEL.into(), spec::SANDBOX_VALUE.into());
        let pods = self.api.list_pods(self.namespace(), &selector).await?;
        let mut vms = Vec::new();
        for pod in &pods {
            if VmId::parse(pod["metadata"]["name"].as_str().unwrap_or_default()).is_ok() {
                vms.push(self.view(pod).await);
            }
        }
        Ok(json!({ "vms": vms }))
    }

    /// A pod as the protocol describes a VM. A pod seen ready the first time has its ticket's
    /// Secret deleted; a delete that fails is tried again at the next view, and fails nothing.
    async fn view(&self, pod: &Value) -> Value {
        let name = pod["metadata"]["name"].as_str().unwrap_or_default();
        let mut vm = json!({
            "id": name,
            "name": name,
            "labels": spec::protocol_labels(pod),
        });
        match spec::pod_state(pod, self.config.bridge_port) {
            PodState::Gone => vm["state"] = json!("gone"),
            PodState::Starting => vm["state"] = json!("starting"),
            PodState::Running { address, port } => {
                vm["state"] = json!("running");
                vm["address"] = json!(address);
                vm["port"] = json!(port);
                self.delete_ticket_once(name).await;
            }
            PodState::Failed { message } => {
                vm["state"] = json!("failed");
                if lock(&self.failures_logged).insert(name.to_string()) {
                    eprintln!("pod {name} failed: {message}");
                }
            }
        }
        vm
    }

    async fn delete_ticket_once(&self, pod_name: &str) {
        if lock(&self.tickets_deleted).contains(pod_name) {
            return;
        }
        let Ok(id) = VmId::parse(pod_name) else {
            return;
        };
        match self
            .api
            .delete_secret(self.namespace(), &id.ticket_name())
            .await
        {
            Ok(()) => {
                lock(&self.tickets_deleted).insert(pod_name.to_string());
            }
            Err(e) if e.is_not_found() => {
                lock(&self.tickets_deleted).insert(pod_name.to_string());
            }
            Err(e) => eprintln!(
                "cannot delete the ticket of pod {pod_name} yet ({}); tried again at the next look",
                failure_kind(&e.failure)
            ),
        }
    }

    fn forget(&self, pod_name: &str) {
        lock(&self.tickets_deleted).remove(pod_name);
        lock(&self.failures_logged).remove(pod_name);
    }

    // ----- delete ---------------------------------------------------------------------

    /// Deletes the pod `id` after reading it: it must carry each of `labels`, which name the
    /// host it belongs to. A pod that is gone is no error.
    pub async fn delete(&self, id: &str, labels: &BTreeMap<String, String>) -> Result<Value> {
        // Without the host's label every run pod would pass the check below.
        if !labels.get(HOST_LABEL).is_some_and(|host| !host.is_empty()) {
            bail!("a delete must name the host's label {HOST_LABEL}: not this host's to delete");
        }
        let vm = VmId::parse(id)?;
        let pod = match self.api.get_pod(self.namespace(), id).await {
            Ok(pod) => pod,
            Err(e) if e.is_not_found() => {
                self.forget(id);
                return Ok(json!({}));
            }
            Err(e) => return Err(e.into()),
        };
        let carried = spec::protocol_labels(&pod);
        for (key, value) in labels {
            if carried.get(key) != Some(value) {
                bail!("the pod {id} does not carry {key}={value}: not this host's to delete");
            }
        }
        if let Err(e) = self
            .api
            .delete_secret(self.namespace(), &vm.ticket_name())
            .await
            && !e.is_not_found()
        {
            // The pod owns it: the cluster deletes it with the pod.
            eprintln!(
                "cannot delete the ticket of pod {id} ({}); it goes with the pod",
                failure_kind(&e.failure)
            );
        }
        // The host frees the VM's slot when this answers, and a pod that is ending still holds
        // its share of the quota: the answer waits until the pod is gone.
        let gone = match self
            .api
            .delete_pod(self.namespace(), id, Some(GRACE_SECONDS))
            .await
        {
            Ok(()) => self.gone(id).await,
            Err(e) if e.is_not_found() => true,
            Err(e) => return Err(e.into()),
        };
        self.forget(id);
        *lock(&self.deleted) = Some(Instant::now());
        if gone {
            eprintln!("deleted pod {id}");
        } else {
            eprintln!(
                "deleted pod {id}, which is still ending after {} s: it holds its share of the quota until it is gone",
                self.gone_wait.as_secs()
            );
        }
        Ok(json!({}))
    }

    /// Waits until the pod `id` is gone from the API, for [`GONE_WAIT`] at most; whether it
    /// is. A read that fails otherwise tells nothing, and the next one may.
    async fn gone(&self, id: &str) -> bool {
        let gone = async {
            while !matches!(self.api.get_pod(self.namespace(), id).await, Err(e) if e.is_not_found())
            {
                tokio::time::sleep(self.poll).await;
            }
        };
        tokio::time::timeout(self.gone_wait, gone).await.is_ok()
    }

    // ----- verify ---------------------------------------------------------------------

    /// What keeps the driver from making a sound run pod of `template`, and how many more pods
    /// the namespace's quota holds: none while `create` would refuse every pod.
    pub async fn verify(&self, template: &str) -> Result<Value> {
        self.config.template(template)?;
        let mut problems = Vec::new();
        let mut checked = Vec::new();
        let ns = self.namespace().to_string();
        // Whether a problem is one for which `create` refuses every pod, whatever the quota
        // holds: an unknown runner node, the network policies, the admission check.
        let mut refuses = false;

        if self.runner_node.is_none() {
            problems.push(format!("{:#}", self.runner_node().unwrap_err()));
            refuses = true;
        }

        // The quota: the driver's room.
        let mut max_vms = None;
        // A quota that leaves no room, in its own words.
        let mut full = None;
        let quota_name = &self.config.resource_quota;
        match self.api.get_resource_quota(&ns, quota_name).await {
            Ok(quota) => {
                let usage = pod_usage(&self.config, template, self.runner_node.as_deref())?;
                let room = room(&quota, &usage);
                checked.push(format!("ResourceQuota {quota_name}: {}", room.summary));
                max_vms = room.max_vms;
                if room.max_vms == Some(0) {
                    full = Some(room.summary);
                }
            }
            Err(e) if e.is_not_found() => problems.push(format!(
                "there is no ResourceQuota {quota_name} in the runs' namespace {ns}"
            )),
            Err(e) => problems.push(api_problem("the ResourceQuota", &e)),
        }

        // The network policies: the chart's two, and no other.
        let (found, seen) = self.policy_problems().await;
        let policies_sound = found.is_empty();
        if !policies_sound {
            // No create passes on an earlier check while they are not sound, whatever its
            // template.
            lock(&self.admission_checked).clear();
            refuses = true;
        }
        problems.extend(found);
        checked.extend(seen);

        // Run pods that no node takes, and how many run pods hold room in the quota.
        let mut holding = None;
        let selector = BTreeMap::from([(
            spec::SANDBOX_LABEL.to_string(),
            spec::SANDBOX_VALUE.to_string(),
        )]);
        match self.api.list_pods(&ns, &selector).await {
            Ok(pods) => {
                let stuck = unschedulable(&pods, chrono::Utc::now());
                checked.push(format!("{} run pod(s)", pods.len()));
                problems.extend(stuck.into_iter().take(MAX_UNSCHEDULABLE));
                holding = Some(pods.iter().filter(|pod| holds_room(pod)).count());
            }
            Err(e) => problems.push(api_problem("the run pods", &e)),
        }

        // A quota that run pods have filled is full, which is no problem: the answer is no
        // room. With no run pod to hold the room, no pod can ever be made as things are.
        if let Some(summary) = &full
            && holding == Some(0)
        {
            problems.push(format!(
                "no room in the runs' namespace: the ResourceQuota {quota_name} holds no more run pods ({summary}), and no run pod holds its room: the quota is too small for one run pod of this template, or something else in the namespace fills it"
            ));
        }

        // The admission policy.
        if self.runner_node.is_some() {
            match self.admission_problems(template).await {
                Ok(found) if found.is_empty() => {
                    // A create's check reads the policies too: it passed only if they are sound.
                    if policies_sound {
                        lock(&self.admission_checked).insert(template.to_string(), Instant::now());
                    }
                    checked.push("the admission policy refuses every pod it must".into());
                }
                Ok(found) => {
                    lock(&self.admission_checked).remove(template);
                    problems.extend(found);
                    refuses = true;
                }
                // The quota refused the dry run, and run pods hold its room: the namespace is
                // full, not broken. Nothing is noted as passed, so the create that finds room
                // makes the check before it makes a pod.
                Err(e) if e.is::<NoRoom>() && holding.is_some_and(|pods| pods > 0) => {
                    lock(&self.admission_checked).remove(template);
                    max_vms = Some(0);
                    checked.push(
                        "the admission policy is not checked: the quota has no room for a dry run, and the next create checks it first"
                            .into(),
                    );
                }
                Err(e) => {
                    lock(&self.admission_checked).remove(template);
                    problems.push(format!("{e:#}"));
                    refuses = true;
                }
            }
        }

        // The host stops taking runs for problems and no room (crate::vm::no_vms_cause). Where
        // `create` refuses every pod there is no room, whatever the quota would hold: else
        // the host would go on taking runs and fail each at its create. A driver that cannot
        // see the quota cannot tell its room; with problems, the host then takes no run either.
        if refuses {
            max_vms = Some(0);
        }
        Ok(json!({
            "problems": problems,
            "maxVms": max_vms,
            "checked": checked,
        }))
    }
}

/// What a dry run's answer shows.
enum DryRunAnswer {
    Accepted,
    Refused(String),
}

/// The namespace's ResourceQuota refused a pod, a dry run's too; the text ends the message.
/// `create` tries again for a moment where a pod that is ending still holds the room.
#[derive(Debug, thiserror::Error)]
#[error("no room in the runs' namespace: its ResourceQuota is used up{0}")]
struct NoRoom(String);

/// Serves the driver protocol on `input` and `output` until `input` ends
/// ([`crate::vm::serve_driver`]).
pub async fn serve<R, W>(driver: Arc<PodDriver>, input: R, output: W) -> Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin + Send + 'static,
{
    crate::vm::serve_driver(input, output, move |method, params| {
        let driver = driver.clone();
        async move { driver.handle(&method, params).await }
    })
    .await
}

/// `vm-driver pod`: the driver of the configuration at `config_path`, on stdin and stdout,
/// with the API server's credential from `api_dir` (`token` and `ca.crt`, the chart's
/// projected volume) and the runner pod's node from `TINFIRM_NODE_NAME`. A configuration that
/// does not load stops it before it answers anything. Where it cannot reach the cluster at all
/// (outside a pod, no token file), it still answers `hello`, and every other method fails with
/// the reason: so `run` refuses to start at its `verify`, and `doctor` says why.
pub async fn run(config_path: &Path, api_dir: &Path) -> Result<()> {
    let config = PodConfig::load(config_path)?;
    let runner_node = std::env::var(super::NODE_NAME_ENV).ok();
    match in_cluster(api_dir) {
        Ok(api) => {
            serve(
                Arc::new(PodDriver::new(config, api, runner_node)),
                tokio::io::stdin(),
                tokio::io::stdout(),
            )
            .await
        }
        Err(e) => {
            let why = format!("{e:#}");
            eprintln!("{why}");
            crate::vm::serve_driver(
                tokio::io::stdin(),
                tokio::io::stdout(),
                move |method, _params| {
                    let why = why.clone();
                    async move {
                        match method.as_str() {
                            "hello" => Ok(hello()),
                            "verify" | "create" | "describe" | "delete" | "list" => {
                                bail!("{why}")
                            }
                            other => bail!("no method `{other}`"),
                        }
                    }
                },
            )
            .await
        }
    }
}

/// The client of the cluster the runner pod runs in, with the token and CA files of `api_dir`.
/// The token file is checked first: outside a pod it is what is missing, and its absence says
/// most plainly where the driver must run.
fn in_cluster(api_dir: &Path) -> Result<Api> {
    let token = api_dir.join("token");
    let not_in_cluster = |what: String| {
        anyhow::anyhow!(
            "the pod driver cannot reach the cluster's API: {what}. It runs in the Helm chart's runner pod (runner.vm.driver `pod`), which mounts its ServiceAccount's token and the cluster's CA at {}",
            super::DEFAULT_API_DIR
        )
    };
    if let Err(e) = std::fs::metadata(&token) {
        return Err(not_in_cluster(format!(
            "cannot read the token file {}: {e}",
            token.display()
        )));
    }
    Api::in_cluster(&api_dir.join("ca.crt"), &token).map_err(|e| not_in_cluster(format!("{e:#}")))
}

/// The answer to `hello`.
fn hello() -> Value {
    json!({
        "protocol": DRIVER_PROTOCOL,
        "driver": DRIVER,
        "version": env!("CARGO_PKG_VERSION"),
    })
}

fn str_param<'a>(params: &'a Value, key: &str) -> Result<&'a str> {
    params[key]
        .as_str()
        .with_context(|| format!("the parameter `{key}` is missing"))
}

fn labels_param(params: &Value) -> Result<BTreeMap<String, String>> {
    match &params["labels"] {
        Value::Null => Ok(BTreeMap::new()),
        labels => serde_json::from_value(labels.clone()).context("`labels` is not a map of text"),
    }
}

fn one_line(text: &str) -> String {
    crate::util::one_line(text, MAX_QUOTED)
}

/// The API server could not answer the call at all, or answered as broken.
fn unreachable(e: &ApiError) -> bool {
    match &e.failure {
        Failure::Transport(_)
        | Failure::Timeout(_)
        | Failure::Http { .. }
        | Failure::Malformed
        | Failure::TooLarge { .. }
        | Failure::NotSent(_) => true,
        Failure::Status { code, .. } => *code >= 500,
        _ => false,
    }
}

/// A create whose answer does not tell whether the server stored the object: no answer in
/// time, a broken connection, a 5xx, or a successful answer that cannot be read. A create is
/// sent once, and the server may have acted on it (super::api). Any other failure is the
/// server's refusal, and it stored nothing.
fn maybe_stored(e: &ApiError) -> bool {
    match &e.failure {
        Failure::Transport(_)
        | Failure::Timeout(_)
        | Failure::Malformed
        | Failure::TooLarge { .. } => true,
        Failure::Status { code, .. } | Failure::Http { code } => *code >= 500,
        _ => false,
    }
}

/// A create in a namespace that does not exist.
fn namespace_missing(e: &ApiError) -> bool {
    matches!(&e.failure, Failure::NotFound(m) if m.starts_with("namespaces "))
}

/// A create that the namespace's ResourceQuota refused.
fn quota_exceeded(e: &ApiError) -> bool {
    matches!(&e.failure, Failure::Forbidden(m) if m.contains("exceeded quota"))
}

/// A problem of `verify` from a read that failed.
fn api_problem(what: &str, e: &ApiError) -> String {
    match &e.failure {
        Failure::Forbidden(_) => format!(
            "the driver may not read {what}: give its ServiceAccount the right ({})",
            one_line(&e.to_string())
        ),
        _ if unreachable(e) => format!(
            "cannot reach the API server to read {what}: {}",
            one_line(&e.to_string())
        ),
        _ => format!("cannot read {what}: {}", one_line(&e.to_string())),
    }
}

/// The error of a create that failed; a full quota says that there is no room.
fn create_error(what: &str, e: &ApiError) -> anyhow::Error {
    if quota_exceeded(e) {
        NoRoom(format!(" ({})", one_line(&e.to_string()))).into()
    } else {
        anyhow::anyhow!("cannot create {what}: {e}")
    }
}

/// The error of the ticket Secret's create, without a word of the server's message, which may
/// quote the request and so the user data: the call, and the failure's kind.
fn secret_error(e: &ApiError) -> anyhow::Error {
    let call = e.call.split('?').next().unwrap_or_default();
    if quota_exceeded(e) {
        return anyhow::anyhow!(
            "no room in the runs' namespace: its ResourceQuota holds no more Secrets ({call})"
        );
    }
    anyhow::anyhow!(
        "cannot create the ticket's Secret: {call}: {}",
        failure_kind(&e.failure)
    )
}

/// A failure's kind, without the server's messages: safe for anything the request held.
fn failure_kind(failure: &Failure) -> String {
    match failure {
        Failure::NotFound(_) => "not found".into(),
        Failure::AlreadyExists(_) => "already exists".into(),
        Failure::Forbidden(_) => "forbidden".into(),
        Failure::Invalid { causes, .. } => {
            let fields: Vec<&str> = causes.iter().map(|c| c.field.as_str()).collect();
            format!("invalid ({})", one_line(&fields.join(", ")))
        }
        Failure::Status { code, reason, .. } => format!("HTTP {code} {}", one_line(reason)),
        Failure::Http { code } => format!("HTTP {code}"),
        Failure::Malformed => "an answer that is not JSON".into(),
        Failure::TooLarge { limit } => format!("an answer over {limit} bytes"),
        Failure::Timeout(after) => format!("no answer within {after:?}"),
        Failure::Transport(why) => one_line(why),
        Failure::NotSent(why) => format!("not sent: {}", one_line(why)),
    }
}

/// Whether a pod counts against the namespace's quota: every pod that has not ended, one
/// that is being deleted too.
fn holds_room(pod: &Value) -> bool {
    !matches!(
        pod["status"]["phase"].as_str(),
        Some("Failed" | "Succeeded")
    )
}

/// The run pods that no node took for longer than [`UNSCHEDULABLE_AFTER_SECONDS`], each
/// with the scheduler's message.
fn unschedulable(pods: &[Value], now: chrono::DateTime<chrono::Utc>) -> Vec<String> {
    let mut out = Vec::new();
    for pod in pods {
        let condition = pod["status"]["conditions"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|c| c["type"] == "PodScheduled");
        let Some(condition) = condition else {
            continue;
        };
        if condition["status"] != "False" || condition["reason"] != "Unschedulable" {
            continue;
        }
        let since = condition["lastTransitionTime"]
            .as_str()
            .and_then(|t| chrono::DateTime::parse_from_rfc3339(t).ok());
        let Some(since) = since else {
            continue;
        };
        let waited = (now - since.with_timezone(&chrono::Utc)).num_seconds();
        if waited > UNSCHEDULABLE_AFTER_SECONDS {
            out.push(format!(
                "the run pod {} has found no node for {waited} s: {}",
                pod["metadata"]["name"].as_str().unwrap_or("?"),
                one_line(condition["message"].as_str().unwrap_or("no message"))
            ));
        }
    }
    out
}

/// What one run pod of `template` takes of a ResourceQuota, by the quota's resource names, in
/// thousandths of a unit.
fn pod_usage(
    config: &PodConfig,
    template: &str,
    runner_node: Option<&str>,
) -> Result<BTreeMap<String, u128>> {
    let pod = spec::pod(
        config,
        template,
        &VmId::random(),
        &BTreeMap::new(),
        runner_node.unwrap_or("unknown-node"),
    )?;
    // A pod's request is the larger of its containers' sum and its largest init container.
    let effective = |kind: &str| -> BTreeMap<String, u128> {
        let mut sum: BTreeMap<String, u128> = BTreeMap::new();
        let mut init: BTreeMap<String, u128> = BTreeMap::new();
        for (key, into) in [("containers", &mut sum), ("initContainers", &mut init)] {
            for container in pod["spec"][key].as_array().into_iter().flatten() {
                for (name, value) in container["resources"][kind]
                    .as_object()
                    .into_iter()
                    .flatten()
                {
                    let amount = value.as_str().and_then(milli).unwrap_or(0);
                    let entry = into.entry(name.clone()).or_insert(0);
                    *entry = if key == "containers" {
                        *entry + amount
                    } else {
                        (*entry).max(amount)
                    };
                }
            }
        }
        for (name, amount) in init {
            let entry = sum.entry(name).or_insert(0);
            *entry = (*entry).max(amount);
        }
        sum
    };
    const ONE: u128 = 1000;
    let mut usage = BTreeMap::new();
    for name in ["pods", "count/pods", "secrets", "count/secrets"] {
        usage.insert(name.to_string(), ONE);
    }
    for (name, amount) in effective("requests") {
        if matches!(name.as_str(), "cpu" | "memory" | "ephemeral-storage") {
            usage.insert(name.clone(), amount);
        }
        usage.insert(format!("requests.{name}"), amount);
    }
    for (name, amount) in effective("limits") {
        if matches!(name.as_str(), "cpu" | "memory" | "ephemeral-storage") {
            usage.insert(format!("limits.{name}"), amount);
        }
    }
    if config.disk.volume == DiskVolume::Ephemeral {
        let disk = u128::from(config.template(template)?.disk_mib) * 1024 * 1024 * ONE;
        for name in ["persistentvolumeclaims", "count/persistentvolumeclaims"] {
            usage.insert(name.to_string(), ONE);
        }
        usage.insert("requests.storage".to_string(), disk);
        if let Some(class) = &config.disk.storage_class_name {
            let prefix = format!("{class}.storageclass.storage.k8s.io/");
            usage.insert(format!("{prefix}requests.storage"), disk);
            usage.insert(format!("{prefix}persistentvolumeclaims"), ONE);
        }
    }
    Ok(usage)
}

/// The room a ResourceQuota leaves.
struct Room {
    /// The fewest more pods any resource the quota bounds holds; `None` when it bounds none
    /// that a run pod takes.
    max_vms: Option<u64>,
    summary: String,
}

/// The room the quota leaves for pods that each take `usage`: for each resource the quota
/// bounds and a run pod takes, (hard − used) ÷ the pod's share, and the smallest. A resource
/// the quota bounds and a pod does not take (services, config maps, a storage class the pod
/// does not use) is not counted; a resource the pod takes and the quota does not bound sets no
/// limit. A resource whose amount the driver cannot read counts as no room.
fn room(quota: &Value, usage: &BTreeMap<String, u128>) -> Room {
    let hard = quota["status"]["hard"]
        .as_object()
        .or_else(|| quota["spec"]["hard"].as_object());
    let used = &quota["status"]["used"];
    let mut best: Option<(u64, String)> = None;
    for (name, limit) in hard.into_iter().flatten() {
        let Some(share) = usage.get(name).filter(|share| **share > 0) else {
            continue;
        };
        let limit = limit.as_str().and_then(milli);
        let taken = match &used[name] {
            Value::Null => Some(0),
            value => value.as_str().and_then(milli),
        };
        let more = match (limit, taken) {
            (Some(limit), Some(taken)) => {
                u64::try_from(limit.saturating_sub(taken) / share).unwrap_or(u64::MAX)
            }
            _ => 0,
        };
        if best.as_ref().is_none_or(|(least, _)| more < *least) {
            best = Some((more, name.clone()));
        }
    }
    match best {
        Some((more, name)) => Room {
            max_vms: Some(more),
            summary: format!("room for {more} more run pod(s), bounded by {name}"),
        },
        None => Room {
            max_vms: None,
            summary: "bounds nothing a run pod takes".into(),
        },
    }
}

/// A Kubernetes quantity in thousandths of a unit, rounded up (`500m`, `4`, `8400Mi`, `1.5Gi`,
/// `1e3`).
fn milli(text: &str) -> Option<u128> {
    let text = text.trim();
    let split = text
        .find(|c: char| !(c.is_ascii_digit() || c == '.'))
        .unwrap_or(text.len());
    let (number, suffix) = text.split_at(split);
    let (whole, fraction) = number.split_once('.').unwrap_or((number, ""));
    if whole.is_empty() && fraction.is_empty() {
        return None;
    }
    let digits = format!("{whole}{fraction}");
    let mantissa: u128 = digits.parse().ok()?;
    let fraction_scale = 10u128.checked_pow(u32::try_from(fraction.len()).ok()?)?;
    // The value is mantissa ÷ fraction_scale × numerator ÷ denominator.
    let (numerator, denominator): (u128, u128) = match suffix {
        "" => (1, 1),
        "n" => (1, 1_000_000_000),
        "u" => (1, 1_000_000),
        "m" => (1, 1000),
        "k" => (1000, 1),
        "M" => (1_000_000, 1),
        "G" => (1_000_000_000, 1),
        "T" => (1_000_000_000_000, 1),
        "P" => (1_000_000_000_000_000, 1),
        "E" => (1_000_000_000_000_000_000, 1),
        "Ki" => (1 << 10, 1),
        "Mi" => (1 << 20, 1),
        "Gi" => (1 << 30, 1),
        "Ti" => (1 << 40, 1),
        "Pi" => (1 << 50, 1),
        "Ei" => (1 << 60, 1),
        exponent => {
            let power: i32 = exponent
                .strip_prefix('e')
                .or_else(|| exponent.strip_prefix('E'))?
                .parse()
                .ok()?;
            let scale = 10u128.checked_pow(power.unsigned_abs())?;
            if power >= 0 { (scale, 1) } else { (1, scale) }
        }
    };
    let top = mantissa.checked_mul(numerator)?.checked_mul(1000)?;
    let bottom = fraction_scale.checked_mul(denominator)?;
    Some(top.div_ceil(bottom))
}

#[cfg(test)]
mod tests {
    use super::super::api::tests::{Handler, Reply, Seen, setup, status};
    use super::super::config::tests::{config, good};
    use super::*;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    const NS: &str = "tinfirm-runs";
    const HOST: &str = "0192f1a4-0000-7000-8000-00000000aaaa";
    const NODE: &str = "worker-runner";
    /// User data with a mark that no answer and no log may hold.
    const USER_DATA: &str = "{\"tinfirm\":1,\"ticket\":\"USER-DATA-CANARY-7f3a\"}";

    /// How the fake cluster answers a dry run.
    #[derive(Clone, Copy, PartialEq)]
    enum Admission {
        /// As a sound policy: the correct pod in, the others out.
        Sound,
        /// A sound policy that lets a hostPath volume in.
        LetsHostPathIn,
        /// Every pod refused.
        RefusesAll,
    }

    /// The fake cluster's state: the objects of the runs' namespace and the test's rules.
    struct Cluster {
        namespace: bool,
        pods: BTreeMap<String, Value>,
        secrets: BTreeMap<String, Value>,
        policies: BTreeMap<String, Value>,
        quota: Option<Value>,
        admission: Admission,
        quota_full: bool,
        /// The most pods the quota admits. A pod that is ending counts until it is gone.
        pod_limit: Option<usize>,
        /// How many more creates the quota refuses for a pod that is gone: it counts late.
        stale_quota: u32,
        /// How many more requests about pods a deleted pod is there for, ending.
        ending_for: u32,
        /// The pods that are ending, each with the requests it is still there for.
        ending: BTreeMap<String, u32>,
        /// The pod's create stores the pod, and answers with this code and no pod.
        lost_pod_answer: Option<u16>,
        fail_secret_create: bool,
        /// How many more of the Secrets' deletes fail.
        fail_secret_deletes: u32,
        fail_pod_delete: bool,
        /// Reads of the network policies are forbidden.
        fail_policy_reads: bool,
        next_uid: u64,
        /// The driver's configuration, which the fake admission policy follows.
        config: PodConfig,
    }

    fn quota() -> Value {
        json!({"metadata": {"name": "tinfirm-runs"}, "status": {
            "hard": {"pods": "10", "requests.cpu": "40", "requests.memory": "100Gi",
                     "requests.devices.example.com/kvm": "10", "services": "5", "count/secrets": "20"},
            "used": {"pods": "2", "requests.cpu": "8", "requests.memory": "16800Mi", "count/secrets": "2"},
        }})
    }

    fn cluster() -> Cluster {
        let config = config(&good());
        Cluster {
            namespace: true,
            pods: BTreeMap::new(),
            secrets: BTreeMap::new(),
            policies: BTreeMap::from([
                (
                    "tinfirm-runs-default-deny".to_string(),
                    json!({"metadata": {"name": "tinfirm-runs-default-deny"}, "spec": spec::default_deny_spec()}),
                ),
                (
                    "tinfirm-runs-allow-runner".to_string(),
                    json!({"metadata": {"name": "tinfirm-runs-allow-runner"}, "spec": spec::allow_runner_spec(&config)}),
                ),
            ]),
            quota: Some(quota()),
            admission: Admission::Sound,
            quota_full: false,
            pod_limit: None,
            stale_quota: 0,
            ending_for: 0,
            ending: BTreeMap::new(),
            lost_pod_answer: None,
            fail_secret_create: false,
            fail_secret_deletes: 0,
            fail_pod_delete: false,
            fail_policy_reads: false,
            next_uid: 1,
            config,
        }
    }

    /// Whether a sound admission policy takes `pod` (the chart's rules, in outline).
    fn sound(config: &PodConfig, pod: &Value) -> bool {
        let spec = &pod["spec"];
        let name = pod["metadata"]["name"].as_str().unwrap_or_default();
        let ticket = name.replacen("tinfirm-vm-", "tinfirm-ticket-", 1);
        let volumes = spec["volumes"].as_array().cloned().unwrap_or_default();
        let volumes_ok = volumes.iter().all(|v| {
            v.get("emptyDir").is_some()
                || (v.get("ephemeral").is_some()
                    && v["ephemeral"]["volumeClaimTemplate"]["spec"]
                        .get("dataSource")
                        .is_none()
                    && v["ephemeral"]["volumeClaimTemplate"]["spec"]
                        .get("dataSourceRef")
                        .is_none())
                || v["secret"]["secretName"] == json!(ticket)
        });
        let containers_ok = spec["containers"]
            .as_array()
            .into_iter()
            .flatten()
            .all(|c| {
                c.get("envFrom").is_none()
                    && c["env"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .all(|e| e["valueFrom"].get("secretKeyRef").is_none())
                    && c["securityContext"]["privileged"] != json!(true)
                    && c["securityContext"]["capabilities"].get("add").is_none()
                    && c["image"] == json!(config.image)
                    && c["command"] == json!([spec::vm_pod::BINARY, spec::vm_pod::COMMAND])
                    && c.get("args").is_none()
                    && c["env"].as_array().is_some_and(|env| env.len() == 9)
            });
        // Of Multus's annotations, the tap's attachment alone, where the tap comes from it.
        let annotations = &pod["metadata"]["annotations"];
        let tap = config
            .tap
            .network_attachment
            .as_ref()
            .map(|attachment| json!(format!("{attachment}@tap0")));
        let annotations_ok = annotations.get(spec::MULTUS_ANNOTATION) == tap.as_ref()
            && annotations.get(spec::MULTUS_DEFAULT_NETWORK).is_none();
        // Every ephemeral claim: no way to another volume, and a template's size.
        let sizes: Vec<Value> = config
            .templates
            .values()
            .map(|t| json!(format!("{}Mi", t.disk_mib)))
            .collect();
        let claims_ok = volumes
            .iter()
            .filter_map(|v| v["ephemeral"]["volumeClaimTemplate"].get("spec"))
            .all(|c| {
                c.get("volumeName").is_none()
                    && c.get("selector").is_none()
                    && sizes.contains(&c["resources"]["requests"]["storage"])
                    && c["accessModes"] == json!(["ReadWriteOnce"])
                    && (c.get("volumeMode").is_none() || c["volumeMode"] == "Filesystem")
            });
        // Placement: only where the scheduler puts it, on the workers for runs, never beside
        // the runner pod.
        let affinity = &spec["affinity"];
        let tolerations = json!(config.tolerations);
        let tolerations_ok = spec["tolerations"]
            .as_array()
            .into_iter()
            .flatten()
            .all(|t| {
                tolerations
                    .as_array()
                    .is_some_and(|known| known.contains(t))
            });
        let anti_ok = affinity["podAntiAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|term| {
                term["labelSelector"]["matchLabels"] == json!(config.runner.labels)
                    && term["namespaceSelector"]["matchLabels"]["kubernetes.io/metadata.name"]
                        == json!(config.runner.namespace)
            });
        let node_affinity_ok = affinity["nodeAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
            ["nodeSelectorTerms"]
            .as_array()
            .into_iter()
            .flatten()
            .all(|term| {
                term.get("matchExpressions").is_none()
                    && term["matchFields"].as_array().into_iter().flatten().all(|f| {
                        f["key"] == "metadata.name" && f["operator"] == "NotIn"
                    })
            });
        let placement_ok = spec.get("nodeName").is_none()
            && (spec.get("schedulerName").is_none()
                || spec["schedulerName"] == "default-scheduler")
            && spec["nodeSelector"] == json!(config.node_selector)
            && tolerations_ok
            && anti_ok
            && affinity.get("podAffinity").is_none()
            && node_affinity_ok;
        spec["serviceAccountName"] == json!(config.run_service_account)
            && spec["automountServiceAccountToken"] == false
            && spec["hostUsers"] == false
            && spec.get("hostNetwork").is_none()
            && annotations_ok
            && volumes_ok
            && claims_ok
            && containers_ok
            && placement_ok
    }

    /// The kubelet, as far as the driver sees it: a pod waits for its Secret, then runs, then
    /// is ready with an address.
    fn kubelet(cluster: &mut Cluster, name: &str) {
        let ticket = name.replacen("tinfirm-vm-", "tinfirm-ticket-", 1);
        let has_ticket = cluster.secrets.contains_key(&ticket);
        let Some(pod) = cluster.pods.get_mut(name) else {
            return;
        };
        if pod["status"]["phase"] == "Pending" && has_ticket {
            pod["status"] =
                json!({"phase": "Running", "conditions": [{"type": "Ready", "status": "False"}]});
        } else if pod["status"]["phase"] == "Running" && pod["status"]["podIP"].is_null() {
            pod["status"] = json!({"phase": "Running", "podIP": "10.42.1.7",
                                   "conditions": [{"type": "Ready", "status": "True"}]});
        }
    }

    /// Takes a pod out of the cluster, and what it owns with it (the garbage collector).
    fn remove_pod(cluster: &mut Cluster, name: &str) -> Option<Value> {
        let pod = cluster.pods.remove(name)?;
        let uid = pod["metadata"]["uid"].clone();
        cluster
            .secrets
            .retain(|_, s| s["metadata"]["ownerReferences"][0]["uid"] != uid);
        cluster.ending.remove(name);
        Some(pod)
    }

    /// One more request about pods: a pod that is ending is there for one request less, and
    /// gone after its last.
    fn pods_end(cluster: &mut Cluster) {
        let gone: Vec<String> = cluster
            .ending
            .iter()
            .filter(|(_, left)| **left == 0)
            .map(|(name, _)| name.clone())
            .collect();
        for name in gone {
            remove_pod(cluster, &name);
        }
        for left in cluster.ending.values_mut() {
            *left -= 1;
        }
    }

    fn answer(cluster: &mut Cluster, seen: &Seen) -> Reply {
        let (path, query) = seen.target.split_once('?').unwrap_or((&seen.target, ""));
        let dry = query.contains("dryRun=All");
        let pods = format!("/api/v1/namespaces/{NS}/pods");
        if seen.method != "DELETE" && path.starts_with(&pods) {
            pods_end(cluster);
        }
        let secrets = format!("/api/v1/namespaces/{NS}/secrets");
        let policies = format!("/apis/networking.k8s.io/v1/namespaces/{NS}/networkpolicies/");
        let quotas = format!("/api/v1/namespaces/{NS}/resourcequotas/");
        let not_found = |what: &str, name: &str| {
            Reply::Json(
                404,
                status(404, "NotFound", &format!("{what} \"{name}\" not found")),
            )
        };
        match (seen.method.as_str(), path) {
            ("POST", p) if p == pods => {
                if !cluster.namespace {
                    return not_found("namespaces", NS);
                }
                if cluster.quota_full
                    || cluster.stale_quota > 0
                    || cluster
                        .pod_limit
                        .is_some_and(|limit| cluster.pods.len() >= limit)
                {
                    cluster.stale_quota = cluster.stale_quota.saturating_sub(1);
                    return Reply::Json(
                        403,
                        status(
                            403,
                            "Forbidden",
                            "pods \"x\" is forbidden: exceeded quota: tinfirm-runs, requested: pods=1, used: pods=10, limited: pods=10",
                        ),
                    );
                }
                if dry {
                    let accept = match cluster.admission {
                        Admission::Sound => sound(&cluster.config, &seen.body),
                        Admission::LetsHostPathIn => {
                            sound(&cluster.config, &seen.body)
                                || seen.body["spec"]["volumes"]
                                    .as_array()
                                    .is_some_and(|v| v.iter().any(|v| v.get("hostPath").is_some()))
                        }
                        Admission::RefusesAll => false,
                    };
                    return if accept {
                        Reply::Json(201, seen.body.clone())
                    } else {
                        Reply::Json(
                            422,
                            status(
                                422,
                                "Invalid",
                                "pods \"x\" is invalid: ValidatingAdmissionPolicy 'tinfirm-run-pods' denied request",
                            ),
                        )
                    };
                }
                let name = seen.body["metadata"]["name"].as_str().unwrap().to_string();
                let mut pod = seen.body.clone();
                pod["metadata"]["uid"] = json!(format!("0000-{:04}", cluster.next_uid));
                cluster.next_uid += 1;
                pod["status"] = json!({"phase": "Pending"});
                cluster.pods.insert(name, pod.clone());
                match cluster.lost_pod_answer {
                    Some(500) => Reply::Json(
                        500,
                        status(500, "InternalError", "etcdserver: request timed out"),
                    ),
                    // Not a Status, or not the pod.
                    Some(code) => Reply::Text(code, "upstream request timeout".into()),
                    None => Reply::Json(201, pod),
                }
            }
            ("GET", p) if p == pods => {
                let wanted: Vec<(String, String)> = query
                    .strip_prefix("labelSelector=")
                    .map(|s| {
                        percent_encoding::percent_decode_str(s)
                            .decode_utf8()
                            .unwrap()
                            .to_string()
                    })
                    .unwrap_or_default()
                    .split(',')
                    .filter_map(|kv| {
                        kv.split_once('=')
                            .map(|(k, v)| (k.to_string(), v.to_string()))
                    })
                    .collect();
                let names: Vec<String> = cluster.pods.keys().cloned().collect();
                for name in &names {
                    kubelet(cluster, name);
                }
                let items: Vec<Value> = cluster
                    .pods
                    .values()
                    .filter(|p| {
                        wanted
                            .iter()
                            .all(|(k, v)| p["metadata"]["labels"][k] == json!(v))
                    })
                    .cloned()
                    .collect();
                Reply::Json(200, json!({"kind": "PodList", "items": items}))
            }
            ("GET", p) if p.starts_with(&format!("{pods}/")) => {
                let name = &p[pods.len() + 1..];
                kubelet(cluster, name);
                match cluster.pods.get(name) {
                    Some(pod) => Reply::Json(200, pod.clone()),
                    None => not_found("pods", name),
                }
            }
            ("DELETE", p) if p.starts_with(&format!("{pods}/")) => {
                let name = &p[pods.len() + 1..];
                if cluster.fail_pod_delete {
                    return Reply::Json(403, status(403, "Forbidden", "pods is forbidden"));
                }
                // With a grace period the pod is there for a while yet, ending.
                if cluster.ending_for > 0 && !query.contains("gracePeriodSeconds=0") {
                    let linger = cluster.ending_for;
                    return match cluster.pods.get_mut(name) {
                        Some(pod) => {
                            pod["metadata"]["deletionTimestamp"] = json!("2026-11-20T10:00:00Z");
                            let pod = pod.clone();
                            cluster.ending.entry(name.to_string()).or_insert(linger);
                            Reply::Json(200, pod)
                        }
                        None => not_found("pods", name),
                    };
                }
                match remove_pod(cluster, name) {
                    Some(pod) => Reply::Json(200, pod),
                    None => not_found("pods", name),
                }
            }
            ("POST", p) if p == secrets => {
                if cluster.fail_secret_create {
                    // A server that quotes what it got, the worst case.
                    let quoted = seen.body.to_string();
                    return Reply::Json(
                        422,
                        json!({"kind": "Status", "reason": "Invalid", "code": 422,
                        "message": format!("Secret is invalid: {quoted}"),
                        "details": {"causes": [{"reason": "FieldValueInvalid", "field": "data", "message": quoted}]}}),
                    );
                }
                let name = seen.body["metadata"]["name"].as_str().unwrap().to_string();
                cluster.secrets.insert(name, seen.body.clone());
                Reply::Json(201, seen.body.clone())
            }
            ("DELETE", p) if p.starts_with(&format!("{secrets}/")) => {
                let name = &p[secrets.len() + 1..];
                if cluster.fail_secret_deletes > 0 {
                    cluster.fail_secret_deletes -= 1;
                    return Reply::Json(403, status(403, "Forbidden", "secrets is forbidden"));
                }
                match cluster.secrets.remove(name) {
                    Some(secret) => Reply::Json(200, secret),
                    None => not_found("secrets", name),
                }
            }
            ("GET", p)
                if p.starts_with(policies.trim_end_matches('/')) && cluster.fail_policy_reads =>
            {
                Reply::Json(
                    403,
                    status(
                        403,
                        "Forbidden",
                        "networkpolicies.networking.k8s.io is forbidden",
                    ),
                )
            }
            ("GET", p) if p == policies.trim_end_matches('/') => Reply::Json(
                200,
                json!({"kind": "NetworkPolicyList", "items": cluster.policies.values().collect::<Vec<_>>()}),
            ),
            ("GET", p) if p.starts_with(&policies) => {
                let name = &p[policies.len()..];
                match cluster.policies.get(name) {
                    Some(policy) => Reply::Json(200, policy.clone()),
                    None => not_found("networkpolicies.networking.k8s.io", name),
                }
            }
            ("GET", p) if p.starts_with(&quotas) => match &cluster.quota {
                Some(quota) => Reply::Json(200, quota.clone()),
                None => not_found("resourcequotas", &p[quotas.len()..]),
            },
            _ => Reply::Json(404, status(404, "NotFound", "no route")),
        }
    }

    struct Test {
        driver: Arc<PodDriver>,
        cluster: Arc<Mutex<Cluster>>,
        fake: super::super::api::tests::Fake,
        dir: tempfile::TempDir,
    }

    impl Test {
        fn cluster(&self) -> std::sync::MutexGuard<'_, Cluster> {
            self.cluster.lock().unwrap()
        }

        /// The calls the fake API got, as `METHOD path` without the query's values.
        fn calls(&self) -> Vec<String> {
            self.fake
                .seen()
                .iter()
                .map(|s| {
                    let (path, query) = s.target.split_once('?').unwrap_or((&s.target, ""));
                    let dry = if query.contains("dryRun=All") {
                        " (dry run)"
                    } else {
                        ""
                    };
                    format!("{} {path}{dry}", s.method)
                })
                .collect()
        }

        fn forget_calls(&self) {
            self.fake.seen.lock().unwrap().clear();
        }

        /// A delete of the pod `name` that is not this driver's: the pod is ending, and there
        /// for `requests` more requests about pods.
        fn end(&self, name: &str, requests: u32) {
            let mut cluster = self.cluster();
            cluster.pods.get_mut(name).unwrap()["metadata"]["deletionTimestamp"] =
                json!("2026-11-20T10:00:00Z");
            cluster.ending.insert(name.to_string(), requests);
        }

        /// One request through the driver protocol, on in-memory pipes; its answer.
        async fn rpc(&self, method: &str, params: Value) -> Value {
            let (mut client, server) = tokio::io::duplex(1024 * 1024);
            let (input, output) = tokio::io::split(server);
            let served = tokio::spawn(serve(self.driver.clone(), input, output));
            let request = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
            client
                .write_all(format!("{request}\n").as_bytes())
                .await
                .unwrap();
            let mut lines = BufReader::new(&mut client).lines();
            let line = lines.next_line().await.unwrap().unwrap();
            drop(lines);
            drop(client);
            served.await.unwrap().unwrap();
            serde_json::from_str(&line).unwrap()
        }

        async fn ok(&self, method: &str, params: Value) -> Value {
            let answer = self.rpc(method, params).await;
            assert!(answer.get("error").is_none(), "{method}: {answer}");
            answer["result"].clone()
        }

        async fn error(&self, method: &str, params: Value) -> String {
            let answer = self.rpc(method, params).await;
            assert_eq!(answer["error"]["code"], crate::vm::DRIVER_ERROR, "{answer}");
            answer["error"]["message"].as_str().unwrap().to_string()
        }

        async fn create(&self) -> String {
            let created = self
                .ok(
                    "create",
                    json!({"template": "sandbox-4c8g", "name": "run-1", "userData": USER_DATA,
                                    "labels": {"tinfirm-host": HOST, "tinfirm-vm": "v1"}}),
                )
                .await;
            created["id"].as_str().unwrap().to_string()
        }
    }

    async fn start_with(mut cluster: Cluster, config_value: Value, node: Option<&str>) -> Test {
        cluster.config = config(&config_value);
        let cluster = Arc::new(Mutex::new(cluster));
        let state = cluster.clone();
        let handler: Handler =
            Arc::new(move |seen: &Seen, _: usize| answer(&mut state.lock().unwrap(), seen));
        let setup = setup(handler).await;
        let mut driver = PodDriver::new(config(&config_value), setup.api, node.map(str::to_string));
        // The waits as built, and a short look between two tries.
        driver.poll = Duration::from_millis(5);
        Test {
            driver: Arc::new(driver),
            cluster,
            fake: setup.fake,
            dir: setup._dir,
        }
    }

    async fn start(cluster: Cluster) -> Test {
        start_with(cluster, good(), Some(NODE)).await
    }

    fn battery_calls() -> usize {
        let config = config(&good());
        1 + spec::admission_battery(
            &config,
            "sandbox-4c8g",
            &VmId::random(),
            &BTreeMap::new(),
            NODE,
        )
        .unwrap()
        .refused
        .len()
    }

    #[tokio::test]
    async fn hello_names_the_driver() {
        let t = start(cluster()).await;
        let hello = t.ok("hello", json!({"protocol": 1})).await;
        assert_eq!(hello["driver"], "pod");
        assert_eq!(hello["protocol"], DRIVER_PROTOCOL);
        assert!(t.error("fly", json!({})).await.contains("no method `fly`"));
    }

    #[tokio::test]
    async fn create_checks_admission_then_makes_the_pod_then_its_secret() {
        let t = start(cluster()).await;
        let id = t.create().await;
        let vm = VmId::parse(&id).unwrap();
        let calls = t.calls();
        let n = battery_calls();
        let policies = format!("/apis/networking.k8s.io/v1/namespaces/{NS}/networkpolicies");
        // The policies: one request, one list.
        assert_eq!(calls.len(), 1 + n + 2, "{calls:#?}");
        assert_eq!(calls[0], format!("GET {policies}"));
        assert!(
            calls[1..1 + n]
                .iter()
                .all(|c| c == &format!("POST /api/v1/namespaces/{NS}/pods (dry run)"))
        );
        assert_eq!(calls[1 + n], format!("POST /api/v1/namespaces/{NS}/pods"));
        assert_eq!(
            calls[1 + n + 1],
            format!("POST /api/v1/namespaces/{NS}/secrets")
        );
        {
            let cluster = t.cluster();
            let pod = &cluster.pods[&id];
            let secret = &cluster.secrets[&vm.ticket_name()];
            assert_eq!(
                secret["metadata"]["ownerReferences"][0]["uid"],
                pod["metadata"]["uid"]
            );
            assert_eq!(pod["metadata"]["labels"]["tinfirm.flowtivo.com/host"], HOST);
        }

        // A second create within the minute: no admission check.
        t.forget_calls();
        t.create().await;
        assert_eq!(t.calls().len(), 2, "{:#?}", t.calls());
        // After its time, the check runs again.
        lock(&t.driver.admission_checked).insert(
            "sandbox-4c8g".into(),
            Instant::now() - ADMISSION_CHECK_TTL - Duration::from_secs(1),
        );
        t.forget_calls();
        t.create().await;
        assert_eq!(t.calls().len(), 1 + n + 2);
    }

    #[tokio::test]
    async fn a_verify_that_finds_a_third_policy_takes_back_an_earlier_pass() {
        let t = start(cluster()).await;
        t.create().await;
        assert!(lock(&t.driver.admission_checked).contains_key("sandbox-4c8g"));
        t.cluster().policies.insert(
            "allow-all".into(),
            json!({"metadata": {"name": "allow-all"}, "spec": {"podSelector": {}, "ingress": [{}]}}),
        );
        let verified = t.ok("verify", json!({"template": "sandbox-4c8g"})).await;
        assert!(
            verified["problems"].to_string().contains("allow-all"),
            "{verified}"
        );
        assert!(
            lock(&t.driver.admission_checked).is_empty(),
            "the pass is taken back"
        );
        // Within the minute, the next create checks again, and is refused.
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                   "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(
            error.contains("holds the NetworkPolicy allow-all"),
            "{error}"
        );
        assert_eq!(t.cluster().pods.len(), 1, "only the first create's pod");
    }

    #[tokio::test]
    async fn create_is_refused_while_the_policies_are_not_the_charts() {
        let t = start(cluster()).await;
        let create = json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                            "labels": {"tinfirm-host": HOST}});
        let sound = t.cluster().policies.clone();
        let mut changed = sound["tinfirm-runs-allow-runner"].clone();
        changed["spec"]["ingress"][0]["ports"][0]["port"] = json!(22);
        type Change = Box<dyn Fn(&mut Cluster)>;
        let cases: Vec<(&str, Change)> = vec![
            (
                "there is no NetworkPolicy tinfirm-runs-default-deny",
                Box::new(|c| {
                    c.policies.remove("tinfirm-runs-default-deny");
                }),
            ),
            (
                "the NetworkPolicy tinfirm-runs-allow-runner is not the one expected",
                Box::new(move |c| {
                    c.policies
                        .insert("tinfirm-runs-allow-runner".into(), changed.clone());
                }),
            ),
            (
                "holds the NetworkPolicy allow-all, which is not the chart's: network policies add up",
                Box::new(|c| {
                    c.policies.insert(
                        "allow-all".into(),
                        json!({"metadata": {"name": "allow-all"}}),
                    );
                }),
            ),
            (
                "the driver may not read the network policies",
                Box::new(|c| c.fail_policy_reads = true),
            ),
        ];
        for (expected, change) in cases {
            change(&mut t.cluster());
            let error = t.error("create", create.clone()).await;
            assert!(error.contains("refusing to create a run pod"), "{error}");
            assert!(error.contains(expected), "{expected}: {error}");
            assert!(lock(&t.driver.admission_checked).is_empty(), "not cached");
            assert!(t.cluster().pods.is_empty());
            // The namespace made clean again: the next create runs the check, and passes.
            {
                let mut cluster = t.cluster();
                cluster.policies = sound.clone();
                cluster.fail_policy_reads = false;
            }
            t.ok("create", create.clone()).await;
            // Its check passed: let it be old, for the next case.
            lock(&t.driver.admission_checked).clear();
            t.cluster().pods.clear();
        }
    }

    #[tokio::test]
    async fn create_is_refused_by_a_wrong_admission_answer() {
        let mut c = cluster();
        c.admission = Admission::LetsHostPathIn;
        let t = start(c).await;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(
            error.contains("accepts a run pod with a hostPath volume"),
            "{error}"
        );
        assert!(t.cluster().pods.is_empty());

        t.cluster().admission = Admission::RefusesAll;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(error.contains("refuses the correct run pod"), "{error}");
        // A full quota is no room, not a broken policy.
        t.cluster().admission = Admission::Sound;
        t.cluster().quota_full = true;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(error.contains("no room in the runs' namespace"), "{error}");
        assert!(!error.contains("admission policy is missing"), "{error}");
    }

    #[tokio::test]
    async fn a_failed_secret_deletes_the_pod_and_quotes_no_user_data() {
        let mut c = cluster();
        c.fail_secret_create = true;
        let t = start(c).await;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(
            error.contains("cannot create the ticket's Secret"),
            "{error}"
        );
        assert!(error.contains("invalid (data)"), "{error}");
        assert!(error.contains("is deleted again"), "{error}");
        assert!(t.cluster().pods.is_empty());
        let calls = t.calls();
        assert!(
            calls
                .last()
                .unwrap()
                .starts_with(&format!("DELETE /api/v1/namespaces/{NS}/pods/tinfirm-vm-")),
            "{calls:#?}"
        );
        let encoded = {
            use base64::Engine as _;
            base64::engine::general_purpose::STANDARD.encode(USER_DATA)
        };
        assert!(!error.contains("USER-DATA-CANARY"), "{error}");
        assert!(!error.contains(&encoded[..20]), "{error}");

        // When the pod cannot be deleted either, the answer says that the cleanup takes it.
        t.cluster().fail_pod_delete = true;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(error.contains("could not be deleted"), "{error}");
        assert!(error.contains("cleanup of leftovers"), "{error}");
        assert!(!error.contains("USER-DATA-CANARY"), "{error}");
        assert_eq!(t.cluster().pods.len(), 1);
    }

    #[tokio::test]
    async fn a_pod_whose_create_got_no_clear_answer_is_deleted_again() {
        let create = json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                            "labels": {"tinfirm-host": HOST}});
        // The server stored the pod, and then: a 5xx, a proxy's page, an answer that is no pod.
        for (code, failure) in [
            (500, "HTTP 500 InternalError"),
            (504, "HTTP 504, and the answer is not a Status"),
            (201, "the answer is not a JSON object of the expected shape"),
        ] {
            let mut c = cluster();
            c.lost_pod_answer = Some(code);
            let t = start(c).await;
            let error = t.error("create", create.clone()).await;
            assert!(error.contains("cannot create the pod"), "{error}");
            assert!(error.contains(failure), "{failure}: {error}");
            assert!(
                error.contains("the server had stored the pod tinfirm-vm-")
                    && error.contains("which is deleted again"),
                "{error}"
            );
            assert!(t.cluster().pods.is_empty(), "{code}: the pod is gone");
            assert!(t.cluster().secrets.is_empty(), "{code}: no ticket was made");
            // The pod's create, then its delete at once, and nothing after.
            let calls = t.calls();
            assert_eq!(
                calls[calls.len() - 2],
                format!("POST /api/v1/namespaces/{NS}/pods"),
                "{calls:#?}"
            );
            let last = t.fake.seen().last().unwrap().target.clone();
            assert!(
                last.starts_with(&format!("/api/v1/namespaces/{NS}/pods/tinfirm-vm-"))
                    && last.ends_with("?gracePeriodSeconds=0"),
                "{calls:#?}"
            );
            assert_eq!(calls.last().unwrap().split(' ').next(), Some("DELETE"));
        }

        // When the delete fails too, the answer says that the cleanup takes the pod.
        let mut c = cluster();
        c.lost_pod_answer = Some(500);
        c.fail_pod_delete = true;
        let t = start(c).await;
        let error = t.error("create", create.clone()).await;
        assert!(error.contains("cannot create the pod"), "{error}");
        assert!(
            error.contains("if the server stored it, could not be deleted"),
            "{error}"
        );
        assert!(error.contains("cleanup of leftovers"), "{error}");
        assert_eq!(t.cluster().pods.len(), 1);

        // A refusal stored nothing: no delete follows it.
        let t = start(cluster()).await;
        t.ok("create", create.clone()).await;
        t.cluster().quota_full = true;
        t.forget_calls();
        let error = t.error("create", create.clone()).await;
        assert!(error.contains("no room in the runs' namespace"), "{error}");
        assert!(
            !t.calls().iter().any(|call| call.starts_with("DELETE")),
            "{:#?}",
            t.calls()
        );
        assert_eq!(t.cluster().pods.len(), 1, "the first create's pod");
    }

    #[test]
    fn only_a_create_without_a_clear_answer_may_have_stored_its_object() {
        let error = |failure: &Failure| ApiError {
            call: format!("POST /api/v1/namespaces/{NS}/pods"),
            failure: failure.clone(),
        };
        let status = |code: u16, reason: &str| Failure::Status {
            code,
            reason: reason.into(),
            message: String::new(),
        };
        for unclear in [
            Failure::Timeout(Duration::from_secs(30)),
            Failure::Transport("connection reset by peer".into()),
            Failure::Malformed,
            Failure::TooLarge { limit: 1 },
            status(500, "InternalError"),
            status(504, "Timeout"),
            Failure::Http { code: 502 },
        ] {
            assert!(maybe_stored(&error(&unclear)), "{unclear}");
        }
        for refusal in [
            Failure::Forbidden("exceeded quota".into()),
            Failure::Invalid {
                message: String::new(),
                causes: Vec::new(),
            },
            Failure::AlreadyExists(String::new()),
            Failure::NotFound("namespaces \"tinfirm-runs\" not found".into()),
            status(429, "TooManyRequests"),
            Failure::Http { code: 403 },
            Failure::NotSent("the token file".into()),
        ] {
            assert!(!maybe_stored(&error(&refusal)), "{refusal}");
        }
    }

    #[tokio::test]
    async fn create_needs_the_host_label_the_runner_node_and_bounded_user_data() {
        let t = start(cluster()).await;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA, "labels": {}}),
            )
            .await;
        assert!(error.contains("tinfirm-host"), "{error}");
        let big = "x".repeat(spec::MAX_USER_DATA + 1);
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": big,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(error.contains("over 65536 bytes"), "{error}");
        assert!(!error.contains("xxxx"), "{error}");
        let error = t
            .error(
                "create",
                json!({"template": "other", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(error.contains("no template `other`"), "{error}");
        assert!(t.calls().is_empty());

        let t = start_with(cluster(), good(), None).await;
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                                              "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(error.contains("TINFIRM_NODE_NAME"), "{error}");
    }

    #[tokio::test]
    async fn describe_follows_the_pod_and_deletes_its_ticket_once_it_is_ready() {
        let mut c = cluster();
        c.fail_secret_deletes = 1;
        let t = start(c).await;
        let id = t.create().await;
        let ticket = VmId::parse(&id).unwrap().ticket_name();
        // The kubelet: Running and not ready, then ready.
        let first = t.ok("describe", json!({"id": id})).await;
        assert_eq!(first["state"], "starting");
        assert_eq!(
            first["labels"],
            json!({"tinfirm-host": HOST, "tinfirm-vm": "v1"})
        );
        t.forget_calls();
        let ready = t.ok("describe", json!({"id": id})).await;
        assert_eq!(
            ready,
            json!({"id": id, "name": id, "state": "running", "address": "10.42.1.7",
                                 "port": 7443, "labels": {"tinfirm-host": HOST, "tinfirm-vm": "v1"}})
        );
        // The Secret's delete failed: the describe did not, and the next one tries again.
        assert!(t.cluster().secrets.contains_key(&ticket));
        t.ok("describe", json!({"id": id})).await;
        assert!(!t.cluster().secrets.contains_key(&ticket));
        t.ok("describe", json!({"id": id})).await;
        let deletes = t
            .calls()
            .iter()
            .filter(|c| c.starts_with("DELETE") && c.ends_with(&ticket))
            .count();
        assert_eq!(
            deletes,
            2,
            "one failed, one done, and none after: {:#?}",
            t.calls()
        );

        // A failed pod, with vm-pod's message; a pod that is gone.
        t.cluster().pods.get_mut(&id).unwrap()["status"] = json!({"phase": "Failed",
            "containerStatuses": [{"name": "vm", "state": {"terminated": {"message": "cannot open /dev/kvm"}}}]});
        assert_eq!(t.ok("describe", json!({"id": id})).await["state"], "failed");
        t.cluster().pods.clear();
        assert_eq!(
            t.ok("describe", json!({"id": id})).await,
            json!({"id": id, "state": "gone"})
        );
        assert!(
            t.error("describe", json!({"id": "1; rm"}))
                .await
                .contains("not the id of a run pod")
        );
    }

    #[tokio::test]
    async fn list_finds_a_hosts_pods_and_deletes_tickets_of_ready_ones() {
        let t = start(cluster()).await;
        let a = t.create().await;
        let b = t.create().await;
        // Another host's pod.
        t.ok(
            "create",
            json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                             "labels": {"tinfirm-host": "another-host"}}),
        )
        .await;
        let listed = t
            .ok("list", json!({"labels": {"tinfirm-host": HOST}}))
            .await;
        let ids: HashSet<&str> = listed["vms"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v["id"].as_str().unwrap())
            .collect();
        assert_eq!(ids, HashSet::from([a.as_str(), b.as_str()]));
        let listed_with = t
            .fake
            .seen()
            .into_iter()
            .rfind(|s| s.method == "GET")
            .unwrap()
            .target;
        assert_eq!(
            listed_with,
            format!(
                "/api/v1/namespaces/{NS}/pods?labelSelector=tinfirm.flowtivo.com%2Fhost%3D{HOST}%2Ctinfirm.flowtivo.com%2Fsandbox%3Dvm"
            )
        );
        // The kubelet makes them ready; the list that sees it deletes their tickets.
        t.ok("list", json!({"labels": {"tinfirm-host": HOST}}))
            .await;
        let listed = t
            .ok("list", json!({"labels": {"tinfirm-host": HOST}}))
            .await;
        assert!(
            listed["vms"]
                .as_array()
                .unwrap()
                .iter()
                .all(|v| v["state"] == "running")
        );
        {
            let cluster = t.cluster();
            for id in [&a, &b] {
                assert!(
                    !cluster
                        .secrets
                        .contains_key(&VmId::parse(id).unwrap().ticket_name())
                );
            }
            assert_eq!(
                cluster.secrets.len(),
                1,
                "the other host's pod is in neither list"
            );
        }
        let error = t
            .error("list", json!({"labels": {"tinfirm-host": "a,b"}}))
            .await;
        assert!(error.contains("tinfirm-host"), "{error}");
    }

    #[tokio::test]
    async fn delete_checks_the_labels_and_takes_the_secret_then_the_pod() {
        let t = start(cluster()).await;
        let id = t.create().await;
        let ticket = VmId::parse(&id).unwrap().ticket_name();
        let error = t
            .error(
                "delete",
                json!({"id": id, "labels": {"tinfirm-host": "another-host"}}),
            )
            .await;
        assert!(error.contains("not this host's to delete"), "{error}");
        let error = t.error("delete", json!({"id": id, "labels": {}})).await;
        assert!(error.contains("must name the host's label"), "{error}");
        assert!(t.cluster().pods.contains_key(&id));

        t.forget_calls();
        t.ok(
            "delete",
            json!({"id": id, "labels": {"tinfirm-host": HOST, "tinfirm-vm": "v1"}}),
        )
        .await;
        // The last read finds the pod gone.
        assert_eq!(
            t.calls(),
            [
                format!("GET /api/v1/namespaces/{NS}/pods/{id}"),
                format!("DELETE /api/v1/namespaces/{NS}/secrets/{ticket}"),
                format!("DELETE /api/v1/namespaces/{NS}/pods/{id}"),
                format!("GET /api/v1/namespaces/{NS}/pods/{id}"),
            ]
        );
        assert!(t.fake.seen()[2].target.ends_with("?gracePeriodSeconds=5"));
        assert!(t.cluster().pods.is_empty() && t.cluster().secrets.is_empty());
        // Gone already: success.
        assert_eq!(
            t.ok(
                "delete",
                json!({"id": id, "labels": {"tinfirm-host": HOST}})
            )
            .await,
            json!({})
        );
    }

    #[tokio::test]
    async fn delete_answers_once_the_pod_is_gone() {
        let mut c = cluster();
        c.ending_for = 2;
        let mut t = start(c).await;
        let id = t.create().await;
        let pod = format!("/api/v1/namespaces/{NS}/pods/{id}");
        let delete = json!({"id": id, "labels": {"tinfirm-host": HOST}});
        t.forget_calls();
        t.ok("delete", delete).await;
        // After its delete the pod was there for two reads, ending; the third found it gone.
        assert_eq!(
            t.calls()[2..],
            [
                format!("DELETE {pod}"),
                format!("GET {pod}"),
                format!("GET {pod}"),
                format!("GET {pod}"),
            ]
        );
        assert!(t.cluster().pods.is_empty());
        assert!(lock(&t.driver.deleted).is_some());

        // A pod that is not gone within the wait: the delete answers all the same, and the pod
        // reads as gone, as every pod that is being deleted.
        let id = t.create().await;
        t.cluster().ending_for = u32::MAX;
        Arc::get_mut(&mut t.driver).unwrap().gone_wait = Duration::from_millis(100);
        let started = Instant::now();
        t.ok(
            "delete",
            json!({"id": id, "labels": {"tinfirm-host": HOST}}),
        )
        .await;
        assert!(started.elapsed() >= Duration::from_millis(100));
        assert!(t.cluster().pods.contains_key(&id), "still ending");
        assert_eq!(t.ok("describe", json!({"id": id})).await["state"], "gone");
    }

    #[tokio::test]
    async fn create_waits_for_the_room_of_a_pod_that_is_ending() {
        let create = json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                            "labels": {"tinfirm-host": HOST}});
        let pods = format!("/api/v1/namespaces/{NS}/pods");
        let full = || {
            let mut c = cluster();
            c.pod_limit = Some(1);
            c
        };
        let t = start(full()).await;
        let a = t.create().await;
        // Not this driver's delete: the pod is ending, and counts for two creates more.
        t.end(&a, 3);
        t.forget_calls();
        let b = t.ok("create", create.clone()).await["id"]
            .as_str()
            .unwrap()
            .to_string();
        // Refused; a pod of the host is ending; refused again; then there is room.
        assert_eq!(
            t.calls(),
            [
                format!("POST {pods}"),
                format!("GET {pods}"),
                format!("POST {pods}"),
                format!("POST {pods}"),
                format!("POST /api/v1/namespaces/{NS}/secrets"),
            ]
        );
        assert_eq!(
            t.fake.seen()[1].target,
            format!(
                "{pods}?labelSelector=tinfirm.flowtivo.com%2Fhost%3D{HOST}%2Ctinfirm.flowtivo.com%2Fsandbox%3Dvm"
            )
        );
        assert_eq!(
            t.cluster().pods.keys().collect::<Vec<_>>(),
            [&b],
            "the pod that ended is gone"
        );

        // This driver's own delete: the pod is gone when it answers, and the quota counts it
        // for two creates more. The admission check is due, and its dry run is refused too.
        t.ok("delete", json!({"id": b, "labels": {"tinfirm-host": HOST}}))
            .await;
        t.cluster().stale_quota = 2;
        lock(&t.driver.admission_checked).clear();
        t.forget_calls();
        t.ok("create", create.clone()).await;
        let calls = t.calls();
        let policies = format!("GET /apis/networking.k8s.io/v1/namespaces/{NS}/networkpolicies");
        let dry_run = format!("POST {pods} (dry run)");
        assert_eq!(
            calls[..4],
            [
                policies.clone(),
                dry_run.clone(),
                policies.clone(),
                dry_run.clone()
            ],
            "{calls:#?}"
        );
        // Then the whole check, the pod and its Secret; no pod was listed on the way.
        assert_eq!(calls.len(), 4 + 1 + battery_calls() + 2, "{calls:#?}");
        assert!(!calls.contains(&format!("GET {pods}")), "{calls:#?}");

        // A quota full of pods that stay: refused at once, after one look for a pod that ends.
        let t = start(full()).await;
        t.create().await;
        t.forget_calls();
        let error = t.error("create", create.clone()).await;
        assert!(
            error.contains("no room in the runs' namespace: its ResourceQuota is used up"),
            "{error}"
        );
        assert_eq!(t.calls(), [format!("POST {pods}"), format!("GET {pods}")]);

        // A pod that does not end: the create gives up after its wait.
        let mut t = start(full()).await;
        let a = t.create().await;
        t.end(&a, u32::MAX);
        Arc::get_mut(&mut t.driver).unwrap().room_wait = Duration::from_millis(100);
        t.forget_calls();
        let started = Instant::now();
        let error = t.error("create", create.clone()).await;
        assert!(error.contains("no room in the runs' namespace"), "{error}");
        assert!(started.elapsed() >= Duration::from_millis(100));
        let tries = t
            .calls()
            .iter()
            .filter(|c| **c == format!("POST {pods}"))
            .count();
        assert!(tries > 1, "{:#?}", t.calls());
        assert_eq!(t.cluster().pods.len(), 1, "no second pod");
    }

    #[tokio::test]
    async fn verify_on_a_clean_namespace_gives_the_quotas_room() {
        let t = start(cluster()).await;
        let verified = t.ok("verify", json!({"template": "sandbox-4c8g"})).await;
        assert_eq!(verified["problems"], json!([]), "{verified}");
        // pods: 8; cpu: 32 ÷ 4 = 8; memory: (102400 − 16800) Mi ÷ 8400 Mi = 10; kvm: 10;
        // secrets: 18. Services are not counted.
        assert_eq!(verified["maxVms"], 8, "{verified}");
        assert!(
            verified["checked"]
                .as_array()
                .unwrap()
                .iter()
                .any(|c| c.as_str().unwrap().contains("room for 8 more"))
        );
        // The check passed: a create right after needs none.
        t.forget_calls();
        t.create().await;
        assert_eq!(t.calls().len(), 2);
    }

    #[tokio::test]
    async fn verify_reports_each_problem() {
        let problems = |verified: &Value| -> String { verified["problems"].to_string() };

        let mut c = cluster();
        c.quota = None;
        c.policies.remove("tinfirm-runs-default-deny");
        let mut opened = c.policies["tinfirm-runs-allow-runner"].clone();
        opened["spec"]["ingress"][0]["ports"][0]["port"] = json!(22);
        c.policies
            .insert("tinfirm-runs-allow-runner".into(), opened);
        c.admission = Admission::LetsHostPathIn;
        c.policies.insert(
            "allow-all".into(),
            json!({"metadata": {"name": "allow-all"}, "spec": {"podSelector": {}, "ingress": [{}], "egress": [{}]}}),
        );
        c.pods.insert("tinfirm-vm-00112233445566778899aabbccddeeff".into(), json!({
            "metadata": {"name": "tinfirm-vm-00112233445566778899aabbccddeeff",
                         "labels": {"tinfirm.flowtivo.com/sandbox": "vm"}},
            "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                "reason": "Unschedulable", "lastTransitionTime": "2026-01-01T00:00:00Z",
                "message": "0/3 nodes are available: 3 node(s) didn't match pod anti-affinity rules."}]},
        }));
        let t = start(c).await;
        let verified = t.ok("verify", json!({"template": "sandbox-4c8g"})).await;
        let text = problems(&verified);
        for expected in [
            "there is no ResourceQuota tinfirm-runs",
            "there is no NetworkPolicy tinfirm-runs-default-deny",
            "the NetworkPolicy tinfirm-runs-allow-runner is not the one expected: spec.ingress[0].ports[0].port: 22, expected 7443",
            "has found no node for",
            "didn't match pod anti-affinity rules",
            "accepts a run pod with a hostPath volume",
            "holds the NetworkPolicy allow-all, which is not the chart's: network policies add up, so it may open the run pods",
        ] {
            assert!(text.contains(expected), "{expected}: {text}");
        }
        // No quota to read, and a create would be refused: no room.
        assert_eq!(verified["maxVms"], 0);

        // No namespace; no runner node.
        let mut c = cluster();
        c.namespace = false;
        let t = start_with(c, good(), None).await;
        let text = problems(&t.ok("verify", json!({"template": "sandbox-4c8g"})).await);
        assert!(text.contains("TINFIRM_NODE_NAME"), "{text}");
        let mut c = cluster();
        c.namespace = false;
        let t = start_with(c, good(), Some(NODE)).await;
        let text = problems(&t.ok("verify", json!({"template": "sandbox-4c8g"})).await);
        assert!(
            text.contains("the runs' namespace tinfirm-runs does not exist"),
            "{text}"
        );
    }

    #[tokio::test]
    async fn verify_answers_no_room_while_create_refuses_every_pod() {
        let verify = json!({"template": "sandbox-4c8g"});
        let create = json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                            "labels": {"tinfirm-host": HOST}});
        // The host's reading: with problems and no room it takes no run, and reports why.
        let no_vms = |verified: &Value| {
            crate::vm::no_vms_cause(&serde_json::from_value(verified.clone()).unwrap())
        };
        type Change = Box<dyn Fn(&mut Cluster)>;
        // Each in a namespace whose quota has room for eight pods.
        let cases: Vec<(&str, Option<&str>, Change)> = vec![
            (
                "there is no NetworkPolicy tinfirm-runs-default-deny",
                Some(NODE),
                Box::new(|c| {
                    c.policies.remove("tinfirm-runs-default-deny");
                }),
            ),
            (
                "holds the NetworkPolicy allow-all, which is not the chart's",
                Some(NODE),
                Box::new(|c| {
                    c.policies.insert(
                        "allow-all".into(),
                        json!({"metadata": {"name": "allow-all"}}),
                    );
                }),
            ),
            (
                "the driver may not read the network policies",
                Some(NODE),
                Box::new(|c| c.fail_policy_reads = true),
            ),
            (
                "accepts a run pod with a hostPath volume",
                Some(NODE),
                Box::new(|c| c.admission = Admission::LetsHostPathIn),
            ),
            (
                "the cluster refuses the correct run pod",
                Some(NODE),
                Box::new(|c| c.admission = Admission::RefusesAll),
            ),
            ("TINFIRM_NODE_NAME", None, Box::new(|_| {})),
        ];
        for (problem, node, change) in cases {
            let mut c = cluster();
            change(&mut c);
            let t = start_with(c, good(), node).await;
            let verified = t.ok("verify", verify.clone()).await;
            assert!(
                verified["problems"].to_string().contains(problem),
                "{problem}: {verified}"
            );
            assert_eq!(verified["maxVms"], 0, "{problem}: {verified}");
            assert!(
                verified["checked"].to_string().contains("room for 8 more"),
                "{problem}: the quota's own room is still said: {verified}"
            );
            assert!(
                no_vms(&verified).is_some_and(|cause| cause.contains(problem)),
                "{problem}"
            );
            // And `create` does refuse.
            t.error("create", create.clone()).await;
            assert!(t.cluster().pods.is_empty(), "{problem}");
        }

        // A problem that refuses no create leaves the room as the quota has it: a run pod
        // that waits for a node.
        let mut c = cluster();
        c.pods.insert("tinfirm-vm-00112233445566778899aabbccddeeff".into(), json!({
            "metadata": {"name": "tinfirm-vm-00112233445566778899aabbccddeeff",
                         "labels": {"tinfirm.flowtivo.com/sandbox": "vm"}},
            "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                "reason": "Unschedulable", "lastTransitionTime": "2026-01-01T00:00:00Z",
                "message": "0/3 nodes are available"}]},
        }));
        let t = start(c).await;
        let verified = t.ok("verify", verify.clone()).await;
        assert!(
            verified["problems"]
                .to_string()
                .contains("has found no node for"),
            "{verified}"
        );
        assert_eq!(verified["maxVms"], 8, "{verified}");
        assert!(no_vms(&verified).is_none());
        t.ok("create", create.clone()).await;
    }

    /// A pod of the runs' namespace, as a list of it shows it.
    fn pod_in(phase: &str, labels: Value, n: u8) -> (String, Value) {
        let name = format!("tinfirm-vm-{n:032x}");
        let pod = json!({"metadata": {"name": name, "labels": labels}, "status": {"phase": phase}});
        (name, pod)
    }

    /// A cluster whose quota of four pods is used up, with these pods.
    fn full_cluster(pods: impl IntoIterator<Item = (String, Value)>) -> Cluster {
        let mut c = cluster();
        let status = &mut c.quota.as_mut().unwrap()["status"];
        status["hard"]["pods"] = json!("4");
        status["used"]["pods"] = json!("4");
        c.quota_full = true;
        c.pods.extend(pods);
        c
    }

    #[tokio::test]
    async fn a_quota_that_run_pods_fill_is_full_and_no_problem_of_verify() {
        let verify = json!({"template": "sandbox-4c8g"});
        let run_pod =
            json!({"tinfirm.flowtivo.com/sandbox": "vm", "tinfirm.flowtivo.com/host": HOST});
        let read =
            |verified: &Value| serde_json::from_value::<crate::vm::Verified>(verified.clone());

        // The chart's default quota is the host's ceiling in pods. A host that starts again
        // beside its four run pods: no room, and nothing wrong.
        let own = (0..4).map(|n| pod_in("Running", run_pod.clone(), n));
        let t = start(full_cluster(own)).await;
        let verified = t.ok("verify", verify.clone()).await;
        assert_eq!(verified["problems"], json!([]), "{verified}");
        assert_eq!(verified["maxVms"], 0, "{verified}");
        let checked = verified["checked"].to_string();
        for expected in [
            "ResourceQuota tinfirm-runs: room for 0 more run pod(s), bounded by pods",
            "4 run pod(s)",
            "the admission policy is not checked: the quota has no room for a dry run",
        ] {
            assert!(checked.contains(expected), "{expected}: {checked}");
        }
        // For the host that is full, not a driver that can make no VM.
        assert!(crate::vm::no_vms_cause(&read(&verified).unwrap()).is_none());
        // One dry run was tried, and nothing counts as passed: when there is room again, the
        // create makes the admission check before its pod.
        let dry_runs = |t: &Test| {
            t.calls()
                .iter()
                .filter(|call| call.ends_with("(dry run)"))
                .count()
        };
        assert_eq!(dry_runs(&t), 1);
        assert!(lock(&t.driver.admission_checked).is_empty());
        {
            let mut cluster = t.cluster();
            cluster.quota_full = false;
            cluster.admission = Admission::LetsHostPathIn;
        }
        let error = t
            .error(
                "create",
                json!({"template": "sandbox-4c8g", "userData": USER_DATA,
                       "labels": {"tinfirm-host": HOST}}),
            )
            .await;
        assert!(
            error.contains("accepts a run pod with a hostPath volume"),
            "{error}"
        );

        // The same quota filled by what is no run pod, by run pods that have ended and hold
        // nothing, or by nothing at all: no pod can be made, which is a problem.
        let foreign = (0..4).map(|n| pod_in("Running", json!({"app": "other"}), n));
        let ended = (0..4).map(|n| pod_in("Failed", run_pod.clone(), n));
        for pods in [foreign.collect::<Vec<_>>(), ended.collect(), Vec::new()] {
            let t = start(full_cluster(pods)).await;
            let verified = t.ok("verify", verify.clone()).await;
            assert_eq!(verified["maxVms"], 0, "{verified}");
            let text = verified["problems"].to_string();
            for expected in [
                "no room in the runs' namespace: the ResourceQuota tinfirm-runs holds no more run pods (room for 0 more run pod(s), bounded by pods), and no run pod holds its room",
                "its ResourceQuota is used up, so the admission policy cannot be checked",
            ] {
                assert!(text.contains(expected), "{expected}: {text}");
            }
            assert!(!text.contains("admission policy is missing"), "{text}");
            assert!(crate::vm::no_vms_cause(&read(&verified).unwrap()).is_some());
        }

        // A quota too small for one pod of the template, in an empty namespace.
        let mut c = cluster();
        c.quota = Some(json!({"metadata": {"name": "tinfirm-runs"},
            "status": {"hard": {"pods": "4", "requests.cpu": "2"}, "used": {}}}));
        c.quota_full = true;
        let t = start(c).await;
        let verified = t.ok("verify", verify.clone()).await;
        assert_eq!(verified["maxVms"], 0, "{verified}");
        let text = verified["problems"].to_string();
        assert!(
            text.contains("bounded by requests.cpu), and no run pod holds its room: the quota is too small for one run pod of this template"),
            "{text}"
        );
    }

    #[tokio::test]
    async fn an_api_server_out_of_reach_is_its_own_problem() {
        let t = start(cluster()).await;
        // A client of a port where nothing listens.
        let closed = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap();
        let api = Api::new(
            url::Url::parse(&format!("https://{closed}/")).unwrap(),
            &t.dir.path().join("ca.crt"),
            &t.dir.path().join("token"),
        )
        .unwrap();
        let driver = Arc::new(PodDriver::new(config(&good()), api, Some(NODE.into())));
        let verified = driver.verify("sandbox-4c8g").await.unwrap();
        let text = verified["problems"].to_string();
        assert!(text.contains("cannot reach the API server"), "{text}");
        assert!(!text.contains("admission policy is missing"), "{text}");
        assert!(!text.contains("there is no"), "{text}");
        assert!(
            text.contains("cannot reach the API server to read the network policies"),
            "{text}"
        );
    }

    #[test]
    fn quantities_and_room() {
        for (text, expected) in [
            ("4", 4000),
            ("500m", 500),
            ("1.5", 1500),
            ("8400Mi", 8400 * 1024 * 1024 * 1000),
            ("1.5Gi", 3 * 512 * 1024 * 1024 * 1000),
            ("2k", 2_000_000),
            ("1e3", 1_000_000),
            ("1e+3", 1_000_000),
            ("1E3", 1_000_000),
            ("1e-3", 1),
            ("15e-1", 1500),
            ("1e-4", 1),
            ("250u", 1),
        ] {
            assert_eq!(milli(text), Some(expected), "{text}");
        }
        for bad in ["", "x", "1Zi", "-1", "1e39", "1e-39", "1e99999999999", "1e"] {
            assert_eq!(milli(bad), None, "{bad}");
        }
        let config = config(&good());
        let usage = pod_usage(&config, "sandbox-4c8g", Some(NODE)).unwrap();
        assert_eq!(usage["requests.cpu"], 4000);
        assert_eq!(usage["requests.memory"], milli("8400Mi").unwrap());
        assert_eq!(usage["requests.devices.example.com/kvm"], 1000);
        assert!(!usage.contains_key("persistentvolumeclaims"));
        let room = room(&quota(), &usage);
        assert_eq!(room.max_vms, Some(8));
        assert!(room.summary.contains("bounded by pods"), "{}", room.summary);
        // A quota that bounds nothing a pod takes tells no room.
        let services = json!({"status": {"hard": {"services": "5"}}});
        assert_eq!(super::room(&services, &usage).max_vms, None);
        // An amount that cannot be read is no room.
        let broken = json!({"status": {"hard": {"pods": "ten"}}});
        assert_eq!(super::room(&broken, &usage).max_vms, Some(0));

        // An ephemeral disk takes a claim and its storage, also by its class.
        let mut value = good();
        value["disk"] =
            json!({"variant": "copy", "volume": "ephemeral", "storageClassName": "local-xfs"});
        let usage = pod_usage(
            &super::super::config::tests::config(&value),
            "sandbox-4c8g",
            Some(NODE),
        )
        .unwrap();
        assert_eq!(usage["persistentvolumeclaims"], 1000);
        assert_eq!(
            usage["local-xfs.storageclass.storage.k8s.io/requests.storage"],
            milli("32768Mi").unwrap()
        );
        assert_eq!(usage["requests.ephemeral-storage"], milli("64Mi").unwrap());
    }
}
