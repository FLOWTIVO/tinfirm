//! The `node` driver (`vm-driver node`, ADR-0112) against real node agents: each agent's
//! listener and helper run in this process from the node agent's library, the helper on its
//! fake backend; the driver is this crate's binary, which the tests drive through the driver
//! protocol as the runner host does (`vm::Driver`). No VM runs: the fake backend's "VMs" are
//! records.

use std::collections::BTreeMap;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{Value, json};
use tinfirm_node_agent::backend::FakeBackend;
use tinfirm_node_agent::helper::{self, Helper};
use tinfirm_node_agent::listener::{Listener, ListenerConfig};
use tinfirm_node_agent::plan::HelperConfig;
use tinfirm_node_agent::{local, tls};
use tinfirm_runner_host::vm::{Driver, DriverCommand, UserData, VmState};

const TEMPLATE: &str = "sandbox-4c8g";
const HOST: &str = "6f1c2e0a-host";

fn dir(path: &Path, mode: u32) -> PathBuf {
    std::fs::create_dir_all(path).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).unwrap();
    path.to_path_buf()
}

/// A node agent: its helper on the fake backend, and its listener on TLS and on its socket.
struct Agent {
    _tmp: tempfile::TempDir,
    backend: FakeBackend,
    tls_addr: std::net::SocketAddr,
    socket: PathBuf,
    node_pin: String,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

impl Drop for Agent {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

/// A node agent with `max_vms` slots that takes the control host whose certificate is
/// `client`.
async fn agent(max_vms: u32, client: &[u8]) -> Agent {
    agent_with(max_vms, client, tls::generate(tls::SERVER_NAME).unwrap()).await
}

/// Like [`agent`], with the node's certificate and key `node_cert` (DER).
async fn agent_with(max_vms: u32, client: &[u8], node_cert: (Vec<u8>, Vec<u8>)) -> Agent {
    let own = local::own_uid().unwrap();
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path();
    for name in [
        "helper-socket",
        "helper-state",
        "spool",
        "listener-state",
        "listener-socket",
    ] {
        dir(&root.join(name), 0o700);
    }
    let mut config: HelperConfig = serde_json::from_value(json!({
        "listenerUid": own,
        "socket": root.join("helper-socket/helper.sock"),
        "spool": root.join("spool"),
        "state": root.join("helper-state"),
        "maxVms": max_vms,
        "vmUidBase": 1_100_000,
        "network": {
            "vmRange": "10.231.0.0/16",
            "nodeAddress": "10.0.0.20",
            "controlHost": "10.0.0.5",
            "firstPort": 20000
        },
        "firecracker": {
            "jailer": "/usr/local/bin/jailer",
            "firecracker": "/usr/local/bin/firecracker",
            "chrootBase": "/srv/tinfirm-jails",
            "cgroupParent": "tinfirm-vms",
            "work": "/var/lib/tinfirm-node-agent/work"
        },
        "templates": {
            TEMPLATE: {
                "vcpus": 2, "memoryMib": 2048, "snapshotMib": 4096,
                "image": "/var/lib/tinfirm-node-agent/images/vm.img",
                "kernel": "/var/lib/tinfirm-node-agent/images/vmlinux"
            }
        }
    }))
    .unwrap();
    config.allow_root_listener = true;
    let backend = FakeBackend::new();
    let helper = Helper::start(config.clone(), backend.clone()).unwrap();
    let mut tasks = vec![tokio::spawn(async move {
        helper::run(helper).await.unwrap();
    })];
    // The socket's file is there a moment before it takes connections: wait for one.
    while tokio::net::UnixStream::connect(&config.socket)
        .await
        .is_err()
    {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let socket = root.join("listener-socket/listener.sock");
    let mut listener_config: ListenerConfig = serde_json::from_value(json!({
        "helperSocket": config.socket,
        "spool": root.join("spool"),
        "state": root.join("listener-state"),
        "address": "192.0.2.10",
        "tls": {
            "listen": "127.0.0.1:0", "controlHost": "127.0.0.1",
            "certificate": "/unused", "key": "/unused",
            "clientSha256": tls::to_hex(&tls::sha256(client)),
        },
        "socket": { "path": socket, "peerUid": own },
        "watchMs": 100
    }))
    .unwrap();
    listener_config.allow_root = true;
    let listener = Listener::start(listener_config).await.unwrap();
    let server = tls::server_config(node_cert.0.clone(), node_cert.1, tls::sha256(client)).unwrap();
    let tcp = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let tls_addr = tcp.local_addr().unwrap();
    tasks.push(tokio::spawn(listener.clone().serve_tls(tcp, server)));
    let unix = local::bind(&socket, own).unwrap();
    tasks.push(tokio::spawn(listener.clone().serve_unix(unix, own)));
    tasks.push(tokio::spawn(listener.clone().watch()));
    Agent {
        _tmp: tmp,
        backend,
        tls_addr,
        socket,
        node_pin: tls::to_hex(&tls::sha256(&node_cert.0)),
        tasks,
    }
}

/// This host's client certificate and key, in files: the key 0600.
struct Client {
    cert: Vec<u8>,
    cert_path: PathBuf,
    key_path: PathBuf,
}

fn client(dir: &Path) -> Client {
    let (cert, key) = tls::generate("control-host").unwrap();
    let cert_path = dir.join("client.crt");
    let key_path = dir.join("client.key");
    std::fs::write(&cert_path, &cert).unwrap();
    std::fs::write(&key_path, &key).unwrap();
    std::fs::set_permissions(&key_path, std::fs::Permissions::from_mode(0o600)).unwrap();
    Client {
        cert,
        cert_path,
        key_path,
    }
}

fn tls_node(name: &str, address: &str, pin: &str, client: &Client) -> Value {
    json!({
        "name": name, "address": address, "nodeSha256": pin,
        "certificate": client.cert_path, "key": client.key_path, "templates": [TEMPLATE]
    })
}

/// The driver as the runner host starts it: this crate's binary, `vm-driver node`.
fn driver(dir: &Path, nodes: Vec<Value>) -> Driver {
    let config = dir.join(format!("node-driver-{}.json", rand_suffix()));
    std::fs::write(&config, json!({ "nodes": nodes }).to_string()).unwrap();
    driver_at(&config)
}

fn driver_at(config: &Path) -> Driver {
    Driver::new(DriverCommand {
        program: PathBuf::from(env!("CARGO_BIN_EXE_tinfirm-runner-host")),
        args: vec![
            "vm-driver".into(),
            "node".into(),
            "--config".into(),
            config.to_string_lossy().into_owned(),
        ],
        env: Vec::new(),
        builtin: None,
    })
}

fn rand_suffix() -> String {
    uuid::Uuid::new_v4().simple().to_string()
}

fn user_data() -> UserData {
    UserData {
        tinfirm: 1,
        image: "tinfirm-runner-claude:local".into(),
        env: BTreeMap::from([("TINFIRM_BRIDGE_TICKET".to_string(), "ab".repeat(32))]),
    }
}

fn labels(vm: &str) -> BTreeMap<String, String> {
    BTreeMap::from([
        ("tinfirm-host".to_string(), HOST.to_string()),
        ("tinfirm-vm".to_string(), vm.to_string()),
    ])
}

fn host_only() -> BTreeMap<String, String> {
    BTreeMap::from([("tinfirm-host".to_string(), HOST.to_string())])
}

/// `hello`, `verify`, `create`, `describe`, `list` and `delete` through the driver, as
/// `vm.rs` reads them.
async fn the_driver_protocol(driver: &Driver, agent: &Agent, node: &str) {
    let hello = driver.hello().await.unwrap();
    assert_eq!((hello.protocol, hello.driver.as_str()), (1, "node"));
    let verified = driver.verify(TEMPLATE).await.unwrap();
    assert!(verified.problems.is_empty(), "{verified:?}");
    assert_eq!(verified.max_vms, Some(4));

    let id = driver
        .create(TEMPLATE, "tinfirm-vm-0a1b2c3d", &user_data(), &labels("v1"))
        .await
        .unwrap();
    assert!(id.starts_with(&format!("{node}:vm-")), "{id}");
    // The user data reached the backend as the host sent it.
    let vms = agent.backend.vms();
    let sent = serde_json::to_vec(&user_data()).unwrap();
    assert_eq!(vms.values().next().unwrap().user_data, sent);

    let vm = driver.describe(&id).await.unwrap();
    assert_eq!(vm.id, id);
    assert_eq!(vm.state, VmState::Running);
    assert_eq!(vm.address.as_deref(), Some("192.0.2.10"));
    assert_eq!(vm.port, Some(20000));
    assert_eq!(vm.labels, labels("v1"));

    let listed = driver.list(&host_only()).await.unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].id, id);
    assert_eq!(listed[0].port, Some(20000));

    // A VMM that died: `failed`, as the agent pushed it.
    let serial = *agent.backend.vms().keys().next().unwrap();
    agent.backend.kill(serial);
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    while driver.describe(&id).await.unwrap().state != VmState::Failed {
        assert!(tokio::time::Instant::now() < deadline, "never failed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // Another host's delete is refused; this host's goes, and the VM is gone.
    let other = BTreeMap::from([("tinfirm-host".to_string(), "another".to_string())]);
    assert!(driver.delete(&id, &other).await.is_err());
    driver.delete(&id, &host_only()).await.unwrap();
    assert!(agent.backend.vms().is_empty());
    assert_eq!(driver.describe(&id).await.unwrap().state, VmState::Gone);
    assert!(driver.list(&host_only()).await.unwrap().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_driver_relays_the_driver_protocol_over_tls() {
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let agent = agent(4, &client.cert).await;
    let driver = driver(
        tmp.path(),
        vec![tls_node(
            "node-a",
            &agent.tls_addr.to_string(),
            &agent.node_pin,
            &client,
        )],
    );
    the_driver_protocol(&driver, &agent, "node-a").await;
}

/// `tinfirm-runner-host node …`, as a process: the one line it prints.
fn host_node(dir: &Path, args: &[&str]) -> String {
    let out = std::process::Command::new(env!("CARGO_BIN_EXE_tinfirm-runner-host"))
        .arg("node")
        .args(args)
        .env("TINFIRM_RUNNER_CONFIG_DIR", dir.join("config"))
        .env("TINFIRM_RUNNER_DATA_DIR", dir.join("data"))
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    let line = String::from_utf8(out.stdout).unwrap();
    assert_eq!(line.lines().count(), 1, "{line}");
    line.trim().to_string()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_identity_of_either_binary_works_with_the_other() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let path = |name: &str| dir.join(name).to_str().unwrap().to_string();
    let identity = |name: &str| {
        let (cert, key) = (path(&format!("{name}.crt")), path(&format!("{name}.key")));
        host_node(dir, &["identity", "--cert", &cert, "--key", &key])
    };
    let fingerprint = |name: &str| host_node(dir, &["fingerprint", "--cert", &path(name)]);
    // Two pairs: first the runner host makes the control host's identity and the node agent's
    // code the node's, then the other way round.
    for (host, node, host_by_runner_host) in
        [("host-a", "node-a", true), ("host-b", "node-b", false)]
    {
        let (host_crt, host_key) = (path(&format!("{host}.crt")), path(&format!("{host}.key")));
        let (node_crt, node_key) = (path(&format!("{node}.crt")), path(&format!("{node}.key")));
        let (client_pin, node_pin) = if host_by_runner_host {
            let client_pin = identity(host);
            let node_pin =
                tls::write_identity(node_crt.as_ref(), node_key.as_ref(), tls::SERVER_NAME)
                    .unwrap();
            (client_pin, node_pin)
        } else {
            let client_pin =
                tls::write_identity(host_crt.as_ref(), host_key.as_ref(), "control").unwrap();
            (client_pin, identity(node))
        };
        // Each binary's rules read the other's files: the same pins, keys of their owner's alone.
        assert_eq!(fingerprint(&format!("{host}.crt")), client_pin);
        assert_eq!(fingerprint(&format!("{node}.crt")), node_pin);
        assert_eq!(tls::fingerprint(host_crt.as_ref()).unwrap(), client_pin);
        assert_eq!(tls::fingerprint(node_crt.as_ref()).unwrap(), node_pin);
        let client_files = tls::load(host_crt.as_ref(), host_key.as_ref()).unwrap();
        let node_files = tls::load(node_crt.as_ref(), node_key.as_ref()).unwrap();
        // And TLS takes them: the listener with the node's, the driver with the host's.
        let agent = agent_with(4, &client_files.0, node_files).await;
        assert_eq!(agent.node_pin, node_pin);
        let client = Client {
            cert: client_files.0,
            cert_path: host_crt.into(),
            key_path: host_key.into(),
        };
        let driver = driver(
            dir,
            vec![tls_node(
                node,
                &agent.tls_addr.to_string(),
                &node_pin,
                &client,
            )],
        );
        the_driver_protocol(&driver, &agent, node).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_driver_relays_the_driver_protocol_over_a_local_socket() {
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let agent = agent(4, &client.cert).await;
    let driver = driver(
        tmp.path(),
        vec![json!({ "name": "local", "socket": agent.socket, "templates": [TEMPLATE] })],
    );
    the_driver_protocol(&driver, &agent, "local").await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn two_nodes_take_the_vms_by_their_free_slots_and_a_restarted_driver_finds_them() {
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let (a, b) = (agent(3, &client.cert).await, agent(3, &client.cert).await);
    let config = tmp.path().join("two.json");
    std::fs::write(
        &config,
        json!({ "nodes": [
            tls_node("node-a", &a.tls_addr.to_string(), &a.node_pin, &client),
            tls_node("node-b", &b.tls_addr.to_string(), &b.node_pin, &client),
        ]})
        .to_string(),
    )
    .unwrap();
    let driver = driver_at(&config);
    driver.hello().await.unwrap();
    assert_eq!(driver.verify(TEMPLATE).await.unwrap().max_vms, Some(6));
    // A tie goes to the first node; then to the one with the most free slots.
    let mut ids = Vec::new();
    for i in 0..4 {
        ids.push(
            driver
                .create(
                    TEMPLATE,
                    &format!("n{i}"),
                    &user_data(),
                    &labels(&format!("v{i}")),
                )
                .await
                .unwrap(),
        );
    }
    let nodes: Vec<&str> = ids.iter().map(|id| id.split(':').next().unwrap()).collect();
    assert_eq!(nodes, ["node-a", "node-b", "node-a", "node-b"]);
    assert_eq!((a.backend.vms().len(), b.backend.vms().len()), (2, 2));
    assert_eq!(driver.verify(TEMPLATE).await.unwrap().max_vms, Some(2));

    // The host starts the driver again at will: the ids still name their nodes.
    drop(driver);
    let again = driver_at(&config);
    for id in &ids {
        let vm = again.describe(id).await.unwrap();
        assert_eq!(vm.state, VmState::Running, "{id}");
    }
    assert_eq!(again.list(&host_only()).await.unwrap().len(), 4);
    for id in &ids {
        again.delete(id, &host_only()).await.unwrap();
    }
    assert!(a.backend.vms().is_empty() && b.backend.vms().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_node_that_is_down_is_not_counted_listed_or_described_as_gone() {
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let a = agent(4, &client.cert).await;
    // A node where nothing listens.
    let closed = {
        let gone = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        gone.local_addr().unwrap().to_string()
    };
    let none = driver(
        tmp.path(),
        vec![tls_node("down", &closed, &"cd".repeat(32), &client)],
    );
    let driver = driver(
        tmp.path(),
        vec![
            tls_node("node-a", &a.tls_addr.to_string(), &a.node_pin, &client),
            tls_node("down", &closed, &"cd".repeat(32), &client),
        ],
    );
    let verified = driver.verify(TEMPLATE).await.unwrap();
    assert!(verified.problems.is_empty(), "{verified:?}");
    assert_eq!(verified.max_vms, Some(4));
    assert!(
        verified
            .checked
            .iter()
            .any(|c| c.contains("node down") && c.contains("not counted")),
        "{verified:?}"
    );
    let id = driver
        .create(TEMPLATE, "n", &user_data(), &labels("v"))
        .await
        .unwrap();
    assert!(id.starts_with("node-a:"), "{id}");
    // `list` shows the nodes that answer; a VM of the node that does not is an error to
    // describe and to delete, never gone.
    let listed = driver.list(&host_only()).await.unwrap();
    assert_eq!(listed.len(), 1);
    let theirs = "down:vm-0123456789abcdef0123456789abcdef";
    let error = format!("{:#}", driver.describe(theirs).await.unwrap_err());
    assert!(error.contains("node down"), "{error}");
    assert!(driver.delete(theirs, &host_only()).await.is_err());
    // An id of no configured node, or of no form, is an error too.
    assert!(driver.describe("elsewhere:vm-1").await.is_err());
    assert!(driver.describe("vm-1").await.is_err());

    // Every node down: a problem, which refuses the host.
    let verified = none.verify(TEMPLATE).await.unwrap();
    assert!(
        verified.problems.iter().any(|p| p.contains("no node")),
        "{verified:?}"
    );
    // A template that no node offers: a problem.
    let verified = driver.verify("other").await.unwrap();
    assert!(!verified.problems.is_empty(), "{verified:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_driver_refuses_a_client_key_that_others_can_read_and_a_node_of_another_pin() {
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let a = agent(4, &client.cert).await;
    std::fs::set_permissions(&client.key_path, std::fs::Permissions::from_mode(0o640)).unwrap();
    let refused = driver(
        tmp.path(),
        vec![tls_node(
            "node-a",
            &a.tls_addr.to_string(),
            &a.node_pin,
            &client,
        )],
    );
    let error = format!("{:#}", refused.hello().await.unwrap_err());
    assert!(error.contains("exited"), "{error}");
    std::fs::set_permissions(&client.key_path, std::fs::Permissions::from_mode(0o600)).unwrap();
    // A node whose certificate is not the pinned one: not counted, and no VM goes there.
    let wrong = driver(
        tmp.path(),
        vec![tls_node(
            "node-a",
            &a.tls_addr.to_string(),
            &"ef".repeat(32),
            &client,
        )],
    );
    let verified = wrong.verify(TEMPLATE).await.unwrap();
    assert!(!verified.problems.is_empty(), "{verified:?}");
    assert!(
        wrong
            .create(TEMPLATE, "n", &user_data(), &labels("v"))
            .await
            .is_err()
    );
    assert!(a.backend.vms().is_empty());
}

/// An agent that was restarted: the connection is lost, and the next call makes a new one.
/// A fake agent on a local socket answers `hello` and one call per connection, then closes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_driver_reconnects_to_an_agent_that_was_restarted() {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
    let tmp = tempfile::tempdir().unwrap();
    let socket = tmp.path().join("agent.sock");
    let listener = tokio::net::UnixListener::bind(&socket).unwrap();
    let connections = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    {
        let connections = connections.clone();
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                connections.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                tokio::spawn(async move {
                    let (read, mut write) = stream.into_split();
                    let mut lines = BufReader::new(read).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        let request: Value = serde_json::from_str(&line).unwrap();
                        let result = match request["method"].as_str() {
                            Some("hello") => {
                                json!({"wire": 1, "agent": "tinfirm-node-agent", "build": "0.0.0-fake"})
                            }
                            _ => json!({"problems": [], "maxVms": 2, "checked": []}),
                        };
                        let answer =
                            json!({"jsonrpc": "2.0", "id": request["id"], "result": result});
                        let _ = write.write_all(format!("{answer}\n").as_bytes()).await;
                        if request["method"] != "hello" {
                            // The agent stops after one call.
                            return;
                        }
                    }
                });
            }
        });
    }
    let driver = driver(
        tmp.path(),
        vec![json!({ "name": "local", "socket": socket, "templates": [TEMPLATE] })],
    );
    for _ in 0..3 {
        assert_eq!(driver.verify(TEMPLATE).await.unwrap().max_vms, Some(2));
    }
    assert!(
        connections.load(std::sync::atomic::Ordering::SeqCst) >= 3,
        "the driver did not reconnect"
    );
}

/// A hardening mark of every line the agent checks: each holds, but those of `off`, which do
/// not and say what was found.
fn mark(off: &[(&str, &str)]) -> tinfirm_node_agent::hardening::Hardening {
    use tinfirm_node_agent::hardening::{Hardening, LINES, Line};
    Hardening::of(
        LINES
            .iter()
            .map(|id| match off.iter().find(|(line, _)| line == id) {
                Some((_, found)) => Line::new(id, false, *found),
                None => Line::new(id, true, "holds"),
            })
            .collect(),
    )
}

fn socket_node(name: &str, socket: &Path, templates: &[&str]) -> Value {
    json!({ "name": name, "socket": socket, "templates": templates })
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_driver_reports_the_hardening_of_the_nodes_that_offer_the_template() {
    use tinfirm_runner_host::vm::Mark;
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let (a, b, c) = (
        agent(1, &client.cert).await,
        agent(4, &client.cert).await,
        agent(4, &client.cert).await,
    );
    a.backend.set_hardening(mark(&[]));
    b.backend.set_hardening(mark(&[]));
    // `c` keeps the fake backend's own mark, every line failing; it offers another template
    // only, so it does not count for this one.
    let nodes = driver(
        tmp.path(),
        vec![
            socket_node("node-a", &a.socket, &[TEMPLATE]),
            socket_node("node-b", &b.socket, &[TEMPLATE]),
            socket_node("node-c", &c.socket, &["other"]),
        ],
    );
    nodes.hello().await.unwrap();
    let verified = nodes.verify(TEMPLATE).await.unwrap();
    assert_eq!(verified.hardened, Some(Mark::Yes), "{verified:?}");
    assert!(verified.not_hardened.is_empty(), "{verified:?}");

    // One line off on one node: `no`, and that line, named with its node.
    let io_uring = "kernel.io_uring_disabled is 0, not 2";
    b.backend.set_hardening(mark(&[("io-uring", io_uring)]));
    let verified = nodes.verify(TEMPLATE).await.unwrap();
    assert_eq!(verified.hardened, Some(Mark::No), "{verified:?}");
    assert_eq!(
        verified.not_hardened,
        [format!("node node-b: io-uring: {io_uring}")]
    );

    // A VM goes to the hardened node with one free slot before the other with four; once it is
    // full, to the other.
    let first = nodes
        .create(TEMPLATE, "n0", &user_data(), &labels("v0"))
        .await
        .unwrap();
    assert!(first.starts_with("node-a:"), "{first}");
    let second = nodes
        .create(TEMPLATE, "n1", &user_data(), &labels("v1"))
        .await
        .unwrap();
    assert!(second.starts_with("node-b:"), "{second}");
    for id in [&first, &second] {
        nodes.delete(id, &host_only()).await.unwrap();
    }

    // A node that does not answer beside one that says `yes`: not known, and which.
    let with_down = driver(
        tmp.path(),
        vec![
            socket_node("node-a", &a.socket, &[TEMPLATE]),
            socket_node("down", &tmp.path().join("nothing.sock"), &[TEMPLATE]),
        ],
    );
    let verified = with_down.verify(TEMPLATE).await.unwrap();
    assert_eq!(verified.hardened, None, "{verified:?}");
    assert_eq!(
        verified.not_hardened,
        ["node down: did not answer, so its hardening is not known"]
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn doctor_names_each_line_of_a_node_that_does_not_hold() {
    let tmp = tempfile::tempdir().unwrap();
    let client = client(tmp.path());
    let (a, b) = (agent(2, &client.cert).await, agent(2, &client.cert).await);
    a.backend.set_hardening(mark(&[]));
    b.backend.set_hardening(mark(&[
        ("io-uring", "kernel.io_uring_disabled is 0, not 2"),
        (
            "lockdown",
            "the lockdown level is none, not integrity or confidentiality",
        ),
    ]));
    let config = tmp.path().join("node-driver.json");
    std::fs::write(
        &config,
        json!({ "nodes": [
            socket_node("node-a", &a.socket, &[TEMPLATE]),
            socket_node("node-b", &b.socket, &[TEMPLATE]),
        ]})
        .to_string(),
    )
    .unwrap();
    let doctor = || async {
        let out = tokio::process::Command::new(env!("CARGO_BIN_EXE_tinfirm-runner-host"))
            .args([
                "doctor",
                "--executor",
                "vm",
                "--vm-driver",
                "node",
                "--vm-template",
                TEMPLATE,
            ])
            .env("TINFIRM_RUNNER_CONFIG_DIR", tmp.path().join("config"))
            .env("TINFIRM_RUNNER_DATA_DIR", tmp.path().join("data"))
            .env("TINFIRM_RUNNER_LOG", "warn")
            .env("TINFIRM_NODE_DRIVER_CONFIG", &config)
            .env_remove("TINFIRM_RUNNER_ISOLATION_LEVEL")
            .env_remove("TINFIRM_RUNNER_ISOLATION_HARDENED")
            .stdin(std::process::Stdio::null())
            .output()
            .await
            .unwrap();
        String::from_utf8_lossy(&out.stdout).into_owned()
    };
    let report = doctor().await;
    let lines = "node node-b: io-uring: kernel.io_uring_disabled is 0, not 2; node node-b: lockdown: the lockdown level is none, not integrity or confidentiality";
    assert!(
        report.contains(&format!("[info] node hardening: no: {lines}\n")),
        "{report}"
    );
    assert!(
        report.contains(&format!(
            "[info] isolation: reports vm-jail, hardened no (as `run` with these options and this environment). Not hardened: {lines}. "
        )),
        "{report}"
    );
    // Every node hardened: `yes`, and the isolation says so.
    b.backend.set_hardening(mark(&[]));
    let report = doctor().await;
    assert!(
        report.contains(&format!(
            "[info] node hardening: yes: every node that offers the template `{TEMPLATE}` reports each line of the hardening as holding"
        )),
        "{report}"
    );
    assert!(
        report.contains("[info] isolation: reports vm-jail, hardened yes (as `run`"),
        "{report}"
    );
}
