//! The pod-per-run VM driver (`vm-driver pod`, ADR-0115 decision 2) as the runner host runs it:
//! this binary's subcommand, over its real stdin and stdout, against a fake Kubernetes API over
//! TLS; `doctor` outside a cluster; the run pod's own subcommand; and the feature
//! `sandbox.elastic` that a host with this paid driver lists. Part of elastic runners, under
//! the Tinfirm Enterprise licence (`ee/LICENSE`).

#[path = "../../../../runner/crates/tinfirm-runner-host/tests/common/mod.rs"]
mod common;

use std::collections::BTreeMap;
use std::convert::Infallible;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use common::*;
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::{Request, Response};
use hyper_util::rt::TokioIo;
use serde_json::{Value, json};
use tinfirm_runner_host::kubernetes::config::PodConfig;
use tinfirm_runner_host::kubernetes::spec::{self, VmId};
use tinfirm_runner_host::vm::{Builtin, Driver, DriverCommand, UserData, VmState};

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");
const NS: &str = "tinfirm-runs";
const TEMPLATE: &str = "sandbox-4c8g";
const RUNNER_NODE: &str = "worker-runner";
const HOST: &str = "0192f1a4-0000-7000-8000-00000000bbbb";
// TEST-ONLY values.
const TOKEN: &str = "eyJTEST-ONLY.pod-driver-runs-token";
const CANARY: &str = "POD-DRIVER-RUNS-CANARY-51c2";

/// The pod driver's configuration that the chart renders for its CI values.
fn config_value() -> Value {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../deploy/helm/tinfirm/ci/expected/pod-driver.json");
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

/// How the fake cluster answers a dry run.
#[derive(Clone, Copy, PartialEq)]
enum Admission {
    /// Only the pod that `spec::pod` makes, for the pod's own name.
    Sound,
    /// That pod, and any pod with a hostPath volume.
    LetsHostPathIn,
}

/// The objects of the runs' namespace, and what the fake API was asked.
struct Cluster {
    config: PodConfig,
    pods: BTreeMap<String, Value>,
    secrets: BTreeMap<String, Value>,
    admission: Admission,
    next_uid: u64,
    calls: Vec<String>,
    /// Requests that did not carry the token.
    unauthorized: u32,
}

impl Cluster {
    fn new(config: PodConfig) -> Cluster {
        Cluster {
            config,
            pods: BTreeMap::new(),
            secrets: BTreeMap::new(),
            admission: Admission::Sound,
            next_uid: 1,
            calls: Vec::new(),
            unauthorized: 0,
        }
    }

    /// Whether the pod is exactly the one the driver's specification makes for its name.
    fn is_the_specs(&self, pod: &Value) -> bool {
        let Ok(id) = VmId::parse(pod["metadata"]["name"].as_str().unwrap_or_default()) else {
            return false;
        };
        let expected = spec::pod(&self.config, TEMPLATE, &id, &BTreeMap::new(), RUNNER_NODE)
            .expect("the configuration's template");
        pod["spec"] == expected["spec"]
    }

    /// The kubelet, as far as the driver sees it: a pod waits for its Secret, then runs, then is
    /// ready with an address.
    fn kubelet(&mut self, name: &str) {
        let ticket = name.replacen(spec::POD_PREFIX, spec::TICKET_PREFIX, 1);
        let has_ticket = self.secrets.contains_key(&ticket);
        let Some(pod) = self.pods.get_mut(name) else {
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

    fn answer(&mut self, method: &str, target: &str, body: &Value) -> (u16, Value) {
        let (path, query) = target.split_once('?').unwrap_or((target, ""));
        let dry = query.contains("dryRun=All");
        self.calls.push(format!(
            "{method} {path}{}",
            if dry { " (dry run)" } else { "" }
        ));
        let pods = format!("/api/v1/namespaces/{NS}/pods");
        let secrets = format!("/api/v1/namespaces/{NS}/secrets");
        let policies = format!("/apis/networking.k8s.io/v1/namespaces/{NS}/networkpolicies");
        let quota = format!("/api/v1/namespaces/{NS}/resourcequotas/tinfirm-runs");
        let not_found = |what: &str, name: &str| {
            status(404, "NotFound", &format!("{what} \"{name}\" not found"))
        };
        match (method, path) {
            ("POST", p) if p == pods => {
                let accept = self.is_the_specs(body)
                    || (self.admission == Admission::LetsHostPathIn
                        && body["spec"]["volumes"]
                            .as_array()
                            .is_some_and(|v| v.iter().any(|v| v.get("hostPath").is_some())));
                if !accept {
                    return status(
                        422,
                        "Invalid",
                        "pods \"x\" is invalid: ValidatingAdmissionPolicy 'tinfirm-run-pods' denied request",
                    );
                }
                if dry {
                    return (201, body.clone());
                }
                let name = body["metadata"]["name"].as_str().unwrap().to_string();
                let mut pod = body.clone();
                pod["metadata"]["uid"] = json!(format!("0000-{:04}", self.next_uid));
                self.next_uid += 1;
                pod["status"] = json!({"phase": "Pending"});
                self.pods.insert(name, pod.clone());
                (201, pod)
            }
            ("GET", p) if p == pods => {
                let selector = query
                    .strip_prefix("labelSelector=")
                    .map(|s| {
                        percent_encoding::percent_decode_str(s)
                            .decode_utf8()
                            .unwrap()
                            .to_string()
                    })
                    .unwrap_or_default();
                let wanted: Vec<(String, String)> = selector
                    .split(',')
                    .filter_map(|kv| {
                        kv.split_once('=')
                            .map(|(k, v)| (k.to_string(), v.to_string()))
                    })
                    .collect();
                let names: Vec<String> = self.pods.keys().cloned().collect();
                for name in &names {
                    self.kubelet(name);
                }
                let items: Vec<&Value> = self
                    .pods
                    .values()
                    .filter(|p| {
                        wanted
                            .iter()
                            .all(|(k, v)| p["metadata"]["labels"][k] == json!(v))
                    })
                    .collect();
                (200, json!({"kind": "PodList", "items": items}))
            }
            ("GET", p) if p.starts_with(&format!("{pods}/")) => {
                let name = p[pods.len() + 1..].to_string();
                self.kubelet(&name);
                match self.pods.get(&name) {
                    Some(pod) => (200, pod.clone()),
                    None => not_found("pods", &name),
                }
            }
            ("DELETE", p) if p.starts_with(&format!("{pods}/")) => {
                let name = &p[pods.len() + 1..];
                match self.pods.remove(name) {
                    Some(pod) => {
                        // The garbage collector: what the pod owns goes with it.
                        let uid = pod["metadata"]["uid"].clone();
                        self.secrets
                            .retain(|_, s| s["metadata"]["ownerReferences"][0]["uid"] != uid);
                        (200, pod)
                    }
                    None => not_found("pods", name),
                }
            }
            ("POST", p) if p == secrets => {
                let name = body["metadata"]["name"].as_str().unwrap().to_string();
                self.secrets.insert(name, body.clone());
                (201, body.clone())
            }
            ("DELETE", p) if p.starts_with(&format!("{secrets}/")) => {
                let name = &p[secrets.len() + 1..];
                match self.secrets.remove(name) {
                    Some(secret) => (200, secret),
                    None => not_found("secrets", name),
                }
            }
            ("GET", p) if p == policies => (
                200,
                json!({"kind": "NetworkPolicyList", "items": [
                    {"metadata": {"name": "tinfirm-runs-default-deny"}, "spec": spec::default_deny_spec()},
                    {"metadata": {"name": "tinfirm-runs-allow-runner"}, "spec": spec::allow_runner_spec(&self.config)},
                ]}),
            ),
            ("GET", p) if p == quota => (
                200,
                json!({"metadata": {"name": "tinfirm-runs"}, "status": {
                    "hard": {"pods": "10", "requests.cpu": "40", "requests.memory": "100Gi"},
                    "used": {"pods": "2", "requests.cpu": "8", "requests.memory": "16800Mi"},
                }}),
            ),
            _ => status(404, "NotFound", "no route"),
        }
    }
}

fn status(code: u16, reason: &str, message: &str) -> (u16, Value) {
    (
        code,
        json!({"kind": "Status", "apiVersion": "v1", "metadata": {}, "status": "Failure",
               "message": message, "reason": reason, "code": code}),
    )
}

fn pem(der: &[u8]) -> String {
    use base64::Engine as _;
    let text = base64::engine::general_purpose::STANDARD.encode(der);
    let mut out = String::from("-----BEGIN CERTIFICATE-----\n");
    for line in text.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(line).unwrap());
        out.push('\n');
    }
    out.push_str("-----END CERTIFICATE-----\n");
    out
}

/// The fake API server: its port, the files of its CA and of the token in `api_dir`, and the
/// cluster behind it.
struct FakeApi {
    port: u16,
    cluster: Arc<Mutex<Cluster>>,
}

impl FakeApi {
    fn cluster(&self) -> std::sync::MutexGuard<'_, Cluster> {
        self.cluster.lock().unwrap()
    }
}

/// Starts the fake API on loopback, with a certificate for 127.0.0.1 from a CA of its own, and
/// writes `ca.crt` and `token` to `api_dir` as the chart's projected volume holds them.
async fn fake_api(api_dir: &Path, config: PodConfig) -> FakeApi {
    let mut ca = rcgen::CertificateParams::new(Vec::<String>::new()).unwrap();
    ca.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
    ca.distinguished_name
        .push(rcgen::DnType::CommonName, "test cluster CA");
    ca.key_usages = vec![
        rcgen::KeyUsagePurpose::KeyCertSign,
        rcgen::KeyUsagePurpose::DigitalSignature,
    ];
    let ca_key = rcgen::KeyPair::generate().unwrap();
    let ca_cert = ca.self_signed(&ca_key).unwrap();
    std::fs::create_dir_all(api_dir).unwrap();
    std::fs::write(api_dir.join("ca.crt"), pem(ca_cert.der())).unwrap();
    std::fs::write(api_dir.join("token"), format!("{TOKEN}\n")).unwrap();
    let issuer = rcgen::Issuer::new(ca, ca_key);
    let mut leaf = rcgen::CertificateParams::new(vec!["127.0.0.1".to_string()]).unwrap();
    leaf.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ServerAuth];
    let leaf_key = rcgen::KeyPair::generate().unwrap();
    let leaf_cert = leaf.signed_by(&leaf_key, &issuer).unwrap();
    let tls = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_no_client_auth()
    .with_single_cert(
        vec![leaf_cert.der().clone()],
        rustls::pki_types::PrivateKeyDer::Pkcs8(leaf_key.serialize_der().into()),
    )
    .unwrap();
    let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tls));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let cluster = Arc::new(Mutex::new(Cluster::new(config)));
    let state = cluster.clone();
    tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let acceptor = acceptor.clone();
            let state = state.clone();
            tokio::spawn(async move {
                let Ok(tls) = acceptor.accept(stream).await else {
                    return;
                };
                let service = hyper::service::service_fn(move |req: Request<Incoming>| {
                    let state = state.clone();
                    async move {
                        let authorized = req
                            .headers()
                            .get(hyper::header::AUTHORIZATION)
                            .and_then(|v| v.to_str().ok())
                            == Some(&format!("Bearer {TOKEN}"));
                        let method = req.method().to_string();
                        let target = req.uri().to_string();
                        let bytes = req.into_body().collect().await.unwrap().to_bytes();
                        let body: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
                        let (code, answer) = {
                            let mut cluster = state.lock().unwrap();
                            if authorized {
                                cluster.answer(&method, &target, &body)
                            } else {
                                cluster.unauthorized += 1;
                                status(401, "Unauthorized", "Unauthorized")
                            }
                        };
                        Ok::<_, Infallible>(
                            Response::builder()
                                .status(code)
                                .header(hyper::header::CONTENT_TYPE, "application/json")
                                .body(Full::new(Bytes::from(serde_json::to_vec(&answer).unwrap())))
                                .unwrap(),
                        )
                    }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(TokioIo::new(tls), service)
                    .await;
            });
        }
    });
    FakeApi { port, cluster }
}

/// `vm-driver pod` of this binary, as `--vm-driver pod` starts it, in a runner pod whose API
/// server is at `port` on loopback.
fn pod_driver(config: &Path, api_dir: &Path, port: Option<u16>) -> Driver {
    let mut env = vec![
        ("TINFIRM_RUNNER_LOG".to_string(), "warn".to_string()),
        (
            "TINFIRM_KUBERNETES_API_DIR".to_string(),
            api_dir.to_string_lossy().into_owned(),
        ),
        ("TINFIRM_NODE_NAME".to_string(), RUNNER_NODE.to_string()),
    ];
    if let Some(port) = port {
        env.push(("KUBERNETES_SERVICE_HOST".into(), "127.0.0.1".into()));
        env.push(("KUBERNETES_SERVICE_PORT".into(), port.to_string()));
    }
    let mut command = DriverCommand::pod(config.to_path_buf()).unwrap();
    command.program = PathBuf::from(BIN);
    command.env = env;
    Driver::new(command)
}

fn write_config(dir: &Path, value: &Value) -> PathBuf {
    let path = dir.join("pod-driver.json");
    std::fs::write(&path, value.to_string()).unwrap();
    path
}

fn labels(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_pod_driver_speaks_the_protocol_through_its_subcommand() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let value = config_value();
        let config = write_config(dir.path(), &value);
        let api_dir = dir.path().join("kubernetes-api");
        let api = fake_api(&api_dir, PodConfig::from_json(&value.to_string()).unwrap()).await;
        let driver = pod_driver(&config, &api_dir, Some(api.port));

        let hello = driver.hello().await.unwrap();
        assert_eq!(hello.driver, "pod");

        // `verify`: the quota's room, the chart's two policies, and the admission battery.
        let verified = driver.verify(TEMPLATE).await.unwrap();
        assert!(verified.problems.is_empty(), "{:?}", verified.problems);
        assert_eq!(verified.max_vms, Some(8), "{:?}", verified.checked);
        let checked = verified.checked.join("; ");
        assert!(
            checked.contains("the admission policy refuses every pod it must"),
            "{checked}"
        );
        assert!(
            checked.contains("NetworkPolicy tinfirm-runs-default-deny"),
            "{checked}"
        );
        let battery = 1 + spec::admission_battery(
            &api.cluster().config,
            TEMPLATE,
            &VmId::random(),
            &BTreeMap::new(),
            RUNNER_NODE,
        )
        .unwrap()
        .refused
        .len();
        let dry_runs = api
            .cluster()
            .calls
            .iter()
            .filter(|c| c.ends_with("(dry run)"))
            .count();
        assert_eq!(dry_runs, battery);
        let error = format!("{:#}", driver.verify("other").await.unwrap_err());
        assert!(error.contains("no template `other`"), "{error}");

        // `create`: the pod, then its ticket's Secret; the pod is the specification's own.
        let mine = labels(&[("tinfirm-host", HOST), ("tinfirm-vm", "v1")]);
        let user_data = UserData {
            tinfirm: 1,
            image: "tinfirm-runner-claude:local".into(),
            env: BTreeMap::from([("TINFIRM_TEST_CANARY".to_string(), CANARY.to_string())]),
        };
        api.cluster().calls.clear();
        let id = driver
            .create(TEMPLATE, "run-1", &user_data, &mine)
            .await
            .unwrap();
        let vm = VmId::parse(&id).unwrap();
        {
            let cluster = api.cluster();
            // Within the minute of the `verify`: no second admission check.
            assert_eq!(
                cluster.calls,
                [
                    format!("POST /api/v1/namespaces/{NS}/pods"),
                    format!("POST /api/v1/namespaces/{NS}/secrets"),
                ]
            );
            let pod = &cluster.pods[&id];
            assert!(cluster.is_the_specs(pod), "{pod}");
            assert_eq!(pod["metadata"]["labels"]["tinfirm.flowtivo.com/host"], HOST);
            let secret = &cluster.secrets[&vm.ticket_name()];
            assert_eq!(
                secret["metadata"]["ownerReferences"][0]["uid"],
                pod["metadata"]["uid"]
            );
        }

        // `describe`: starting while the pod is not ready; running at the pod's address once it
        // is, and the ticket's Secret is gone then.
        let first = driver.describe(&id).await.unwrap();
        assert_eq!(first.state, VmState::Starting, "{first:?}");
        assert!(api.cluster().secrets.contains_key(&vm.ticket_name()));
        let ready = driver.describe(&id).await.unwrap();
        assert_eq!(ready.state, VmState::Running, "{ready:?}");
        assert_eq!(ready.address.as_deref(), Some("10.42.1.7"));
        assert_eq!(ready.port, Some(7443));
        assert_eq!(ready.labels, mine);
        assert!(api.cluster().secrets.is_empty(), "the ticket is deleted");

        // `list`: this host's pods by their labels.
        let listed = driver
            .list(&labels(&[("tinfirm-host", HOST)]))
            .await
            .unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, id);
        assert!(
            driver
                .list(&labels(&[("tinfirm-host", "another-host")]))
                .await
                .unwrap()
                .is_empty()
        );

        // `delete`: refused without this host's label, then done.
        let error = driver
            .delete(&id, &labels(&[("tinfirm-host", "another-host")]))
            .await
            .unwrap_err();
        assert!(
            format!("{error:#}").contains("not this host's to delete"),
            "{error:#}"
        );
        assert!(api.cluster().pods.contains_key(&id));
        driver
            .delete(&id, &labels(&[("tinfirm-host", HOST)]))
            .await
            .unwrap();
        assert!(api.cluster().pods.is_empty());
        assert_eq!(driver.describe(&id).await.unwrap().state, VmState::Gone);
        // A delete of a pod that is gone is no error.
        driver
            .delete(&id, &labels(&[("tinfirm-host", HOST)]))
            .await
            .unwrap();

        // An admission policy that lets a hostPath volume in: `verify` names it, and `run`
        // would refuse to start.
        api.cluster().admission = Admission::LetsHostPathIn;
        let verified = driver.verify(TEMPLATE).await.unwrap();
        assert!(
            verified
                .problems
                .iter()
                .any(|p| p.contains("accepts a run pod with a hostPath volume")),
            "{:?}",
            verified.problems
        );
        assert_eq!(api.cluster().unauthorized, 0);
        // Nothing the fake was asked carries the user data but the Secret's create.
        assert!(
            api.cluster()
                .calls
                .iter()
                .all(|call| !call.contains(CANARY))
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn outside_a_cluster_the_driver_says_hello_and_refuses_the_rest() {
    with_timeout(60, async {
        let dir = tempfile::tempdir().unwrap();
        let config = write_config(dir.path(), &config_value());
        // No token file, and no API server in the environment.
        let api_dir = dir.path().join("no-kubernetes-api");
        let driver = pod_driver(&config, &api_dir, None);
        assert_eq!(driver.hello().await.unwrap().driver, "pod");
        let error = format!("{:#}", driver.verify(TEMPLATE).await.unwrap_err());
        assert!(
            error.contains(
                "the pod driver cannot reach the cluster's API: cannot read the token file"
            ),
            "{error}"
        );
        assert!(error.contains("runner.vm.driver `pod`"), "{error}");
        for error in [
            driver.describe("tinfirm-vm-00").await.unwrap_err(),
            driver
                .delete("tinfirm-vm-00", &labels(&[("tinfirm-host", HOST)]))
                .await
                .unwrap_err(),
            driver.list(&BTreeMap::new()).await.map(|_| ()).unwrap_err(),
        ] {
            assert!(
                format!("{error:#}").contains("cannot reach the cluster's API"),
                "{error:#}"
            );
        }
        // A token file but no API server: the variables are named.
        std::fs::create_dir_all(&api_dir).unwrap();
        std::fs::write(api_dir.join("token"), TOKEN).unwrap();
        let mut command = DriverCommand::pod(config.clone()).unwrap();
        command.program = PathBuf::from(BIN);
        command.env = vec![
            (
                "TINFIRM_KUBERNETES_API_DIR".into(),
                api_dir.to_string_lossy().into_owned(),
            ),
            // An empty value counts as not set.
            ("KUBERNETES_SERVICE_HOST".into(), String::new()),
        ];
        let error = format!(
            "{:#}",
            Driver::new(command).verify(TEMPLATE).await.unwrap_err()
        );
        assert!(error.contains("KUBERNETES_SERVICE_"), "{error}");
        // A configuration that does not load stops the driver before it answers.
        let mut wrong = config_value();
        wrong["isolationStatement"] = json!("yes");
        let wrong = {
            let path = dir.path().join("wrong.json");
            std::fs::write(&path, wrong.to_string()).unwrap();
            path
        };
        let error = format!(
            "{:#}",
            pod_driver(&wrong, &api_dir, None)
                .hello()
                .await
                .unwrap_err()
        );
        assert!(
            error.contains("exited before it answered `hello`"),
            "{error}"
        );
    })
    .await;
}

/// `doctor` with the environment of the chart's runner pod, on a machine that is no pod.
fn doctor_outside_a_cluster(dir: &Path, config: &Path) -> std::process::Output {
    std::process::Command::new(BIN)
        .args([
            "doctor",
            "--executor",
            "vm",
            "--vm-driver",
            "pod",
            "--vm-template",
            TEMPLATE,
        ])
        .env("TINFIRM_RUNNER_CONFIG_DIR", dir.join("config"))
        .env("TINFIRM_RUNNER_DATA_DIR", dir.join("data"))
        .env("TINFIRM_RUNNER_LOG", "warn")
        .env("TINFIRM_POD_DRIVER_CONFIG", config)
        .env("TINFIRM_KUBERNETES_API_DIR", dir.join("kubernetes-api"))
        .env("DOCKER_HOST", "unix:///nonexistent/docker.sock")
        .env_remove("KUBERNETES_SERVICE_HOST")
        .env_remove("KUBERNETES_SERVICE_PORT")
        .env_remove("TINFIRM_NODE_NAME")
        .env_remove("TINFIRM_RUNNER_EXECUTOR")
        .env_remove("TINFIRM_RUNNER_VM_DRIVER")
        .env_remove("TINFIRM_RUNNER_VM_TEMPLATE")
        .env_remove("TINFIRM_RUNNER_VM_PROBE")
        .env_remove("TINFIRM_RUNNER_ISOLATION_LEVEL")
        .env_remove("TINFIRM_RUNNER_ISOLATION_HARDENED")
        .env_remove("TINFIRM_RUNNER_SANDBOX_RUNTIME")
        .env_remove("TINFIRM_RUNNER_SANDBOX_DOCKER")
        .env_remove("TINFIRM_RUNNER_SANDBOX_ENV")
        .env_remove("TINFIRM_RUNNER_PROTOCOL_VERSION")
        .env_remove("TINFIRM_RUNNER_ALLOW_UNCONFINED_READER")
        .stdin(std::process::Stdio::null())
        .output()
        .unwrap()
}

#[test]
fn doctor_outside_a_cluster_fails_clearly() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let config = write_config(dir, &config_value());
    let started = std::time::Instant::now();
    let out = doctor_outside_a_cluster(dir, &config);
    let report = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    // For the record of what an operator sees.
    eprintln!("{report}");
    assert!(!out.status.success(), "{report}");
    assert!(!report.contains("panicked"), "{report}");
    assert!(
        started.elapsed() < Duration::from_secs(60),
        "no hang: {report}"
    );
    assert!(
        report.contains(&format!(
            "[ ok ] pod driver configuration: {}: the runs' namespace tinfirm-runs, template(s) sandbox-4c8g, sandbox-8c16g, tap multus",
            config.display()
        )),
        "{report}"
    );
    assert!(
        report.contains("[FAIL] vm driver: the VM driver's `verify`: the pod driver cannot reach the cluster's API: cannot read the token file"),
        "{report}"
    );
    assert!(
        report.contains("[info] isolation: reports machine, hardened no, stated by the operator"),
        "{report}"
    );
    assert!(report.contains("[info] docker: not used"), "{report}");
    // A configuration without the statement: neither the driver nor the level.
    let mut missing = config_value();
    missing
        .as_object_mut()
        .unwrap()
        .remove("isolationStatement");
    let config = write_config(dir, &missing);
    let out = doctor_outside_a_cluster(dir, &config);
    let report = String::from_utf8_lossy(&out.stdout).into_owned();
    assert!(
        report.contains("[FAIL] pod driver configuration:")
            && report.contains("isolationStatement"),
        "{report}"
    );
    assert!(
        report.contains("[FAIL] isolation: --vm-driver pod:"),
        "{report}"
    );
    assert!(!report.contains("vm driver:"), "{report}");
}

#[test]
fn run_takes_no_statement_by_flag_with_the_pod_driver() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let config = write_config(dir, &config_value());
    let out = std::process::Command::new(BIN)
        .args([
            "run",
            "--executor",
            "vm",
            "--vm-driver",
            "pod",
            "--vm-template",
            TEMPLATE,
            "--isolation-level",
            "machine",
        ])
        .env("TINFIRM_RUNNER_CONFIG_DIR", dir.join("config"))
        .env("TINFIRM_RUNNER_DATA_DIR", dir.join("data"))
        .env("TINFIRM_RUNNER_LOG", "warn")
        .env("TINFIRM_POD_DRIVER_CONFIG", &config)
        .env_remove("TINFIRM_RUNNER_EXECUTOR")
        .env_remove("TINFIRM_RUNNER_VM_DRIVER")
        .env_remove("TINFIRM_RUNNER_ISOLATION_LEVEL")
        .env_remove("TINFIRM_RUNNER_ISOLATION_HARDENED")
        .stdin(std::process::Stdio::null())
        .output()
        .unwrap();
    let message = String::from_utf8_lossy(&out.stderr);
    assert!(!out.status.success());
    assert!(
        message.contains("with --vm-driver pod the level is the statement of the driver's configuration (isolationStatement)"),
        "{message}"
    );
    assert!(!message.contains("not logged in"), "{message}");
}

#[test]
fn the_run_pods_steps_are_subcommands_of_this_binary() {
    let run = |args: &[&str]| {
        std::process::Command::new(BIN)
            .args(args)
            .env_remove("TINFIRM_RUNNER_LOG")
            .stdin(std::process::Stdio::null())
            .output()
            .unwrap()
    };
    let help = run(&["vm-pod", "--help"]);
    assert!(help.status.success());
    let text = String::from_utf8_lossy(&help.stdout);
    assert!(
        text.contains("ready") && text.contains("tap-setup"),
        "{text}"
    );
    // The readiness probe outside a pod: no ready file, so not ready; not a usage error.
    let ready = run(&[spec::vm_pod::COMMAND, spec::vm_pod::READY]);
    assert_eq!(ready.status.code(), Some(1), "{ready:?}");
    assert!(ready.stderr.is_empty(), "{ready:?}");
    let unknown = run(&["vm-pod", "start"]);
    assert_eq!(unknown.status.code(), Some(2), "{unknown:?}");
    // The run pod's subcommand and the driver's are hidden from the help.
    let help = run(&["--help"]);
    let text = String::from_utf8_lossy(&help.stdout);
    assert!(
        !text.contains("vm-pod") && !text.contains("vm-driver"),
        "{text}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_with_the_pod_driver_lists_it_as_paid_and_reports_its_stated_level() {
    with_timeout(60, async {
        let dir = tempfile::tempdir().unwrap();
        let config = write_config(dir.path(), &config_value());
        // The fake driver, standing in for the pod driver, whose configuration the host reads
        // for its level.
        let mut options = vm_options(&dir.path().join("driver.log"), &[]);
        options.driver.builtin = Some(Builtin::Pod { config });
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            protocol_version: 2,
            ..HostSetup::vm_with(options)
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.expect("hello").await;
        let features = hello["features"].as_array().unwrap();
        assert!(features.contains(&json!("sandbox.elastic")), "{hello}");
        assert!(features.contains(&json!("sandbox.bridge")), "{hello}");
        // `machine` as the configuration states, never hardened (ADR-0115, decision 2).
        assert_eq!(hello["isolationTiers"], json!(["machine"]), "{hello}");
        assert_eq!(hello["hardened"], "no", "{hello}");
        assert_eq!(hello["isolationStated"], true, "{hello}");
        // A Community server (ADR-0114): an error before the welcome, then 1008.
        conn.send(json!({
            "type": "error",
            "code": "EDITION_REQUIRED",
            "message": "This server's licence does not hold elastic runners"
        }));
        conn.close_with(1008, "Edition required");
        let err = host.handle.await.unwrap().expect_err("a fatal end");
        let message = format!("{err:#}");
        assert!(message.contains("EDITION_REQUIRED"), "{message}");
        assert!(
            message.contains("does not hold elastic runners"),
            "{message}"
        );
        assert!(
            tokio::time::timeout(Duration::from_millis(500), server.accept())
                .await
                .is_err(),
            "the host must not reconnect"
        );
    })
    .await;
}
