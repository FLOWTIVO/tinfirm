//! The node agent end to end: the listener and the helper in this process, the helper on the
//! fake backend, and a driver that speaks the agent's wire over TLS and over the local socket.

use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tinfirm_node_agent::backend::FakeBackend;
use tinfirm_node_agent::helper::{self, Helper, HelperClient};
use tinfirm_node_agent::id::{TemplateName, VmId};
use tinfirm_node_agent::listener::{self, Listener, ListenerConfig};
use tinfirm_node_agent::plan::HelperConfig;
use tinfirm_node_agent::{local, spool, tls, wire};
use tokio::io::{
    AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader, ReadHalf, WriteHalf,
};

const TEMPLATE: &str = "sandbox-4c8g";
const HOST: &str = "6f1c2e0a-host";

fn dir(path: &Path, mode: u32) -> PathBuf {
    std::fs::create_dir_all(path).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).unwrap();
    path.to_path_buf()
}

fn helper_config(root: &Path, listener_uid: u32) -> HelperConfig {
    let mut config: HelperConfig = serde_json::from_value(json!({
        "listenerUid": listener_uid,
        "socket": root.join("helper-socket/helper.sock"),
        "spool": root.join("spool"),
        "state": root.join("helper-state"),
        "maxVms": 4,
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
                "vcpus": 2,
                "memoryMib": 2048,
                "snapshotMib": 4096,
                "image": "/var/lib/tinfirm-node-agent/images/vm.img",
                "kernel": "/var/lib/tinfirm-node-agent/images/vmlinux"
            }
        }
    }))
    .unwrap();
    config.allow_root_listener = true;
    config
}

/// A node: the helper on the fake backend, and the listener on TLS and on its local socket.
struct Node {
    _root: tempfile::TempDir,
    root: PathBuf,
    backend: FakeBackend,
    helper_socket: PathBuf,
    tls_addr: std::net::SocketAddr,
    socket: PathBuf,
    node_cert: (Vec<u8>, Vec<u8>),
    control_cert: (Vec<u8>, Vec<u8>),
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

impl Drop for Node {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

/// The helper alone, running, before a listener starts.
struct HelperHalf {
    tmp: tempfile::TempDir,
    root: PathBuf,
    backend: FakeBackend,
    config: HelperConfig,
    task: tokio::task::JoinHandle<()>,
}

async fn helper_half() -> HelperHalf {
    let own = local::own_uid().unwrap();
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().to_path_buf();
    for name in [
        "helper-socket",
        "helper-state",
        "spool",
        "listener-state",
        "listener-socket",
    ] {
        dir(&root.join(name), 0o700);
    }
    let backend = FakeBackend::new();
    let config = helper_config(&root, own);
    let helper = Helper::start(config.clone(), backend.clone()).unwrap();
    let task = tokio::spawn(async move {
        helper::run(helper).await.unwrap();
    });
    while !config.socket.exists() {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    HelperHalf {
        tmp,
        root,
        backend,
        config,
        task,
    }
}

fn listener_config(
    root: &Path,
    helper_socket: &Path,
    control_pin: [u8; 32],
    socket_peer: u32,
) -> ListenerConfig {
    let mut config: ListenerConfig = serde_json::from_value(json!({
        "helperSocket": helper_socket,
        "spool": root.join("spool"),
        "state": root.join("listener-state"),
        "address": "192.0.2.10",
        "tls": {
            "listen": "127.0.0.1:0",
            "certificate": "/unused",
            "key": "/unused",
            "clientSha256": tls::to_hex(&control_pin),
        },
        "socket": { "path": root.join("listener-socket/listener.sock"), "peerUid": socket_peer },
        "watchMs": 100
    }))
    .unwrap();
    config.allow_root = true;
    config
}

async fn node_from(half: HelperHalf, socket_peer: Option<u32>) -> Node {
    let own = local::own_uid().unwrap();
    let HelperHalf {
        tmp,
        root,
        backend,
        config,
        task,
    } = half;
    let mut tasks = vec![task];
    let node_cert = tls::generate(tls::SERVER_NAME).unwrap();
    let control_cert = tls::generate("control-host").unwrap();
    let peer = socket_peer.unwrap_or(own);
    let listener_config =
        listener_config(&root, &config.socket, tls::sha256(&control_cert.0), peer);
    let socket = listener_config.socket.as_ref().unwrap().path.clone();
    let listener = Listener::start(listener_config).await.unwrap();
    let server = tls::server_config(
        node_cert.0.clone(),
        node_cert.1.clone(),
        tls::sha256(&control_cert.0),
    )
    .unwrap();
    let tcp = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let tls_addr = tcp.local_addr().unwrap();
    tasks.push(tokio::spawn(listener.clone().serve_tls(tcp, server)));
    let unix = local::bind(&socket, own).unwrap();
    tasks.push(tokio::spawn(listener.clone().serve_unix(unix, peer)));
    tasks.push(tokio::spawn(listener.clone().watch()));
    Node {
        _root: tmp,
        root,
        backend,
        helper_socket: config.socket,
        tls_addr,
        socket,
        node_cert,
        control_cert,
        tasks,
    }
}

async fn node(socket_peer: Option<u32>) -> Node {
    node_from(helper_half().await, socket_peer).await
}

/// A driver's end of the agent's wire.
struct Driver<S> {
    reader: BufReader<ReadHalf<S>>,
    writer: WriteHalf<S>,
    next: u64,
    events: Vec<Value>,
}

impl<S: AsyncRead + AsyncWrite> Driver<S> {
    fn new(stream: S) -> Driver<S> {
        let (read, write) = tokio::io::split(stream);
        Driver {
            reader: BufReader::new(read),
            writer: write,
            next: 1,
            events: Vec::new(),
        }
    }

    async fn line(&mut self) -> Option<Value> {
        let line = tokio::time::timeout(
            Duration::from_secs(10),
            local::read_line(&mut self.reader, wire::MAX_LINE),
        )
        .await
        .expect("an answer in time");
        match line {
            Ok(Some(line)) => Some(serde_json::from_slice(&line).unwrap()),
            _ => None,
        }
    }

    async fn send_raw(&mut self, line: &[u8]) {
        self.writer.write_all(line).await.unwrap();
        self.writer.write_all(b"\n").await.unwrap();
        self.writer.flush().await.unwrap();
    }

    /// One call: its result, or its error's code and message.
    async fn call(&mut self, method: &str, params: Value) -> Result<Value, (i64, String)> {
        let id = self.next;
        self.next += 1;
        let request = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        self.send_raw(request.to_string().as_bytes()).await;
        loop {
            let message = self.line().await.expect("the listener answers");
            if message.get("id").is_none() {
                self.events.push(message);
                continue;
            }
            assert_eq!(message["id"], id, "{message}");
            assert_eq!(message["jsonrpc"], "2.0");
            if let Some(error) = message.get("error") {
                return Err((
                    error["code"].as_i64().unwrap(),
                    error["message"].as_str().unwrap().to_string(),
                ));
            }
            return Ok(message["result"].clone());
        }
    }

    async fn hello(&mut self) -> Value {
        self.call(
            "hello",
            json!({"wire": wire::WIRE, "build": wire::build_id()}),
        )
        .await
        .unwrap()
    }

    /// The next notification.
    async fn event(&mut self) -> Value {
        if !self.events.is_empty() {
            return self.events.remove(0);
        }
        let message = self.line().await.expect("a notification");
        assert!(message.get("id").is_none(), "{message}");
        message
    }
}

async fn tls_driver(
    node: &Node,
    cert: Option<(Vec<u8>, Vec<u8>)>,
) -> std::io::Result<Driver<impl AsyncRead + AsyncWrite>> {
    let config = tls::client_config(tls::sha256(&node.node_cert.0), cert).unwrap();
    let tcp = tokio::net::TcpStream::connect(node.tls_addr).await?;
    let stream = tokio_rustls::TlsConnector::from(config)
        .connect(tls::server_name(), tcp)
        .await?;
    Ok(Driver::new(stream))
}

async fn unix_driver(node: &Node) -> Driver<tokio::net::UnixStream> {
    unix_driver_at(&node.socket).await
}

async fn unix_driver_at(socket: &Path) -> Driver<tokio::net::UnixStream> {
    Driver::new(tokio::net::UnixStream::connect(socket).await.unwrap())
}

fn labels(host: &str, vm: &str) -> Value {
    json!({"tinfirm-host": host, "tinfirm-vm": vm})
}

/// `verify`, `create`, `describe`, `list` and `delete`, as the runner host's `node` driver
/// relays them.
async fn the_driver_protocol<S: AsyncRead + AsyncWrite>(node: &Node, driver: &mut Driver<S>) {
    let hello = driver.hello().await;
    assert_eq!(
        hello,
        json!({"wire": 1, "agent": "tinfirm-node-agent", "build": wire::build_id()})
    );

    let verified = driver
        .call("verify", json!({"template": TEMPLATE}))
        .await
        .unwrap();
    assert_eq!(verified["problems"], json!([]));
    assert_eq!(verified["maxVms"], 4);
    assert!(
        verified["checked"].as_array().unwrap().len() >= 2,
        "{verified}"
    );

    let user_data = json!({"tinfirm": 1, "image": "tinfirm-runner-claude:local", "env": {"TINFIRM_BRIDGE_TICKET": "ab".repeat(32)}}).to_string();
    let created = driver
        .call(
            "create",
            json!({"template": TEMPLATE, "name": "tinfirm-vm-0a1b2c3d", "userData": user_data, "labels": labels(HOST, "vm-1")}),
        )
        .await
        .unwrap();
    let id = created["id"].as_str().unwrap().to_string();
    assert!(
        tinfirm_node_agent::id::VmId::parse(id.as_bytes()).is_some(),
        "{id}"
    );
    // The helper handed the backend the user data as it came, and the spool file is gone.
    let vms = node.backend.vms();
    assert_eq!(vms.len(), 1);
    let (serial, vm) = vms.iter().next().unwrap();
    assert_eq!(vm.user_data, user_data.as_bytes());
    assert_eq!(vm.plan.published_port, 20000);
    let spooled = spool::user_data_path(
        &node.root.join("spool"),
        &tinfirm_node_agent::id::VmId::parse(id.as_bytes()).unwrap(),
    );
    assert!(!spooled.exists());

    let described = driver.call("describe", json!({"id": id})).await.unwrap();
    assert_eq!(
        described,
        json!({
            "id": id,
            "name": "tinfirm-vm-0a1b2c3d",
            "state": "running",
            "address": "192.0.2.10",
            "port": 20000,
            "labels": labels(HOST, "vm-1"),
        })
    );

    let listed = driver
        .call("list", json!({"labels": {"tinfirm-host": HOST}}))
        .await
        .unwrap();
    assert_eq!(listed["vms"], json!([described]));
    let other = driver
        .call("list", json!({"labels": {"tinfirm-host": "another-host"}}))
        .await
        .unwrap();
    assert_eq!(other["vms"], json!([]));
    let all = driver.call("list", json!({})).await.unwrap();
    assert_eq!(all["vms"].as_array().unwrap().len(), 1);

    // Another host's delete is refused, and so is one that names no host.
    let (code, _) = driver
        .call(
            "delete",
            json!({"id": id, "labels": {"tinfirm-host": "another-host"}}),
        )
        .await
        .unwrap_err();
    assert_eq!(code, wire::code::REFUSED);
    let (code, _) = driver
        .call("delete", json!({"id": id, "labels": {}}))
        .await
        .unwrap_err();
    assert_eq!(code, wire::code::REFUSED);
    assert_eq!(node.backend.vms().len(), 1);

    let deleted = driver
        .call(
            "delete",
            json!({"id": id, "labels": {"tinfirm-host": HOST}}),
        )
        .await
        .unwrap();
    assert_eq!(deleted, json!({}));
    assert!(node.backend.vms().is_empty());
    assert!(!node.backend.vms().contains_key(serial));
    // A VM that is gone is no error to delete, and describes as gone.
    driver
        .call(
            "delete",
            json!({"id": id, "labels": {"tinfirm-host": HOST}}),
        )
        .await
        .unwrap();
    let gone = driver.call("describe", json!({"id": id})).await.unwrap();
    assert_eq!(gone, json!({"id": id, "state": "gone"}));
    let listed = driver
        .call("list", json!({"labels": {"tinfirm-host": HOST}}))
        .await
        .unwrap();
    assert_eq!(listed["vms"], json!([]));
}

#[tokio::test]
async fn the_driver_protocol_works_over_tls_with_the_pinned_client() {
    let node = node(None).await;
    let mut driver = tls_driver(&node, Some(node.control_cert.clone()))
        .await
        .unwrap();
    the_driver_protocol(&node, &mut driver).await;
}

#[tokio::test]
async fn the_driver_protocol_works_over_the_local_socket() {
    let node = node(None).await;
    let mut driver = unix_driver(&node).await;
    the_driver_protocol(&node, &mut driver).await;
}

/// Whether a TLS client gets an answer to `hello`.
async fn answered(node: &Node, cert: Option<(Vec<u8>, Vec<u8>)>) -> bool {
    let Ok(mut driver) = tls_driver(node, cert).await else {
        return false;
    };
    let request = json!({"jsonrpc": "2.0", "id": 1, "method": "hello", "params": {"wire": 1, "build": wire::build_id()}});
    if driver
        .writer
        .write_all(format!("{request}\n").as_bytes())
        .await
        .is_err()
    {
        return false;
    }
    let _ = driver.writer.flush().await;
    matches!(
        tokio::time::timeout(
            Duration::from_secs(5),
            local::read_line(&mut driver.reader, wire::MAX_LINE)
        )
        .await,
        Ok(Ok(Some(_)))
    )
}

#[tokio::test]
async fn a_tls_client_with_another_certificate_or_none_gets_nothing() {
    let node = node(None).await;
    assert!(answered(&node, Some(node.control_cert.clone())).await);
    let other = tls::generate("control-host").unwrap();
    assert!(!answered(&node, Some(other)).await);
    assert!(!answered(&node, None).await);
    // The listener still serves the control host afterwards.
    let mut driver = tls_driver(&node, Some(node.control_cert.clone()))
        .await
        .unwrap();
    driver.hello().await;
}

#[tokio::test]
async fn a_local_peer_that_is_not_the_runner_hosts_user_gets_nothing() {
    let own = local::own_uid().unwrap();
    let node = node(Some(own.wrapping_add(4242))).await;
    let mut stream = tokio::net::UnixStream::connect(&node.socket).await.unwrap();
    let _ = stream
        .write_all(b"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"wire\":1,\"build\":\"x\"}}\n")
        .await;
    let mut buf = Vec::new();
    let _ = tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut buf)).await;
    assert!(buf.is_empty(), "{}", String::from_utf8_lossy(&buf));
}

#[tokio::test]
async fn requests_out_of_their_form_are_refused_with_their_code() {
    let node = node(None).await;
    let mut driver = unix_driver(&node).await;
    let code = |r: Result<Value, (i64, String)>| r.unwrap_err().0;

    // Nothing before hello; hello of another wire.
    assert_eq!(
        code(driver.call("list", json!({})).await),
        wire::code::HELLO_FIRST
    );
    assert_eq!(
        code(
            driver
                .call("hello", json!({"wire": 2, "build": wire::build_id()}))
                .await
        ),
        wire::code::MISMATCH
    );
    assert_eq!(
        code(driver.call("hello", json!({"wire": 1})).await),
        wire::code::INVALID_PARAMS
    );
    driver.hello().await;

    assert_eq!(
        code(driver.call("exec", json!({})).await),
        wire::code::NO_METHOD
    );
    assert_eq!(
        code(
            driver
                .call("verify", json!({"template": "no-such-template"}))
                .await
        ),
        wire::code::UNKNOWN_TEMPLATE
    );
    let create = |template: &str, extra: Value| {
        let mut params = json!({"template": template, "name": "n", "userData": "{}", "labels": {"tinfirm-host": HOST}});
        for (k, v) in extra.as_object().unwrap() {
            params[k] = v.clone();
        }
        params
    };
    assert_eq!(
        code(
            driver
                .call("create", create("no-such-template", json!({})))
                .await
        ),
        wire::code::UNKNOWN_TEMPLATE
    );
    for (params, expected) in [
        (
            create(TEMPLATE, json!({"memoryMib": 1})),
            wire::code::INVALID_PARAMS,
        ),
        (
            create(TEMPLATE, json!({"path": "/etc/shadow"})),
            wire::code::INVALID_PARAMS,
        ),
        (create("../etc", json!({})), wire::code::INVALID_PARAMS),
        (
            create(TEMPLATE, json!({"name": "a/b"})),
            wire::code::INVALID_PARAMS,
        ),
        (
            create(TEMPLATE, json!({"userData": ""})),
            wire::code::INVALID_PARAMS,
        ),
        (
            create(TEMPLATE, json!({"userData": "x".repeat(64 * 1024 + 1)})),
            wire::code::INVALID_PARAMS,
        ),
        (
            create(TEMPLATE, json!({"labels": {}})),
            wire::code::INVALID_PARAMS,
        ),
        (
            create(TEMPLATE, json!({"labels": {"tinfirm-host": "a b"}})),
            wire::code::INVALID_PARAMS,
        ),
    ] {
        assert_eq!(
            code(driver.call("create", params.clone()).await),
            expected,
            "{params}"
        );
    }
    assert!(node.backend.vms().is_empty());

    let good = "vm-0123456789abcdef0123456789abcdef";
    for id in [
        format!("{good}\n"),
        good.to_uppercase(),
        "vm-../../../etc/passwd".into(),
        format!("{good}\u{0}"),
        format!("{good}0"),
        String::new(),
        "vm-0123456789abcdef0123456789abcdéf".into(),
    ] {
        assert_eq!(
            code(driver.call("describe", json!({"id": id})).await),
            wire::code::INVALID_PARAMS,
            "{id:?}"
        );
        assert_eq!(
            code(
                driver
                    .call(
                        "delete",
                        json!({"id": id, "labels": {"tinfirm-host": HOST}})
                    )
                    .await
            ),
            wire::code::INVALID_PARAMS,
            "{id:?}"
        );
    }
    assert_eq!(
        code(
            driver
                .call("describe", json!({"id": good, "extra": 1}))
                .await
        ),
        wire::code::INVALID_PARAMS
    );
    // An id of the form that the node never made is gone.
    assert_eq!(
        driver.call("describe", json!({"id": good})).await.unwrap(),
        json!({"id": good, "state": "gone"})
    );

    // Lines that are not requests, and one that is too long, which closes the connection.
    driver.send_raw(b"{").await;
    assert_eq!(
        driver.line().await.unwrap()["error"]["code"],
        wire::code::PARSE
    );
    driver
        .send_raw(br#"{"jsonrpc":"2.0","id":9,"method":"list","extra":1}"#)
        .await;
    assert_eq!(
        driver.line().await.unwrap()["error"]["code"],
        wire::code::INVALID_REQUEST
    );
    driver.send_raw(&vec![b' '; wire::MAX_LINE + 1]).await;
    assert_eq!(
        driver.line().await.unwrap()["error"]["code"],
        wire::code::PARSE
    );
    assert!(driver.line().await.is_none());
}

#[tokio::test]
async fn a_vmm_that_dies_is_reported_at_once_and_describes_as_failed() {
    let node = node(None).await;
    let mut driver = unix_driver(&node).await;
    driver.hello().await;
    let created = driver
        .call(
            "create",
            json!({"template": TEMPLATE, "name": "n", "userData": "{}", "labels": labels(HOST, "vm-2")}),
        )
        .await
        .unwrap();
    let id = created["id"].as_str().unwrap().to_string();
    let serial = *node.backend.vms().keys().next().unwrap();
    node.backend.kill(serial);
    let event = driver.event().await;
    assert_eq!(
        event,
        json!({"jsonrpc": "2.0", "method": "vmFailed", "params": {"id": id, "state": "failed", "reason": "the VM's VMM ended"}})
    );
    let described = driver.call("describe", json!({"id": id})).await.unwrap();
    assert_eq!(described["state"], "failed");
    // A failed VM is deleted as any other.
    driver
        .call(
            "delete",
            json!({"id": id, "labels": {"tinfirm-host": HOST}}),
        )
        .await
        .unwrap();
    assert!(node.backend.vms().is_empty());
}

#[tokio::test]
async fn the_node_makes_no_more_vms_than_its_slots_and_each_its_own_port() {
    let node = node(None).await;
    let mut driver = unix_driver(&node).await;
    driver.hello().await;
    let mut ports = Vec::new();
    for i in 0..4 {
        let created = driver
            .call("create", json!({"template": TEMPLATE, "name": format!("n{i}"), "userData": "{}", "labels": labels(HOST, &format!("v{i}"))}))
            .await
            .unwrap();
        let described = driver
            .call("describe", json!({"id": created["id"]}))
            .await
            .unwrap();
        ports.push(described["port"].as_u64().unwrap());
    }
    assert_eq!(ports, [20000, 20001, 20002, 20003]);
    let (code, _) = driver
        .call("create", json!({"template": TEMPLATE, "name": "n", "userData": "{}", "labels": labels(HOST, "v")}))
        .await
        .unwrap_err();
    assert_eq!(code, wire::code::FULL);
    let verified = driver
        .call("verify", json!({"template": TEMPLATE}))
        .await
        .unwrap();
    assert_eq!(verified["maxVms"], 0);
    let uids: std::collections::BTreeSet<u32> =
        node.backend.vms().values().map(|vm| vm.plan.uid).collect();
    assert_eq!(uids.len(), 4);
}

/// The helper refuses a real process of another uid, where this test can run one: as root,
/// with `setpriv` and `python3`. Elsewhere the peer check's own tests cover it.
#[tokio::test]
async fn the_helper_refuses_a_process_of_another_user_and_serves_the_listeners() {
    let own = local::own_uid().unwrap();
    let tools = ["/usr/bin/setpriv", "python3"].iter().all(|t| {
        std::process::Command::new(t)
            .arg("--version")
            .output()
            .is_ok_and(|o| o.status.success())
    });
    if own != 0 || !tools {
        eprintln!("skipped: needs root, setpriv and python3; the peer check's unit tests cover it");
        return;
    }
    const NOBODY: u32 = 65534;
    // A refused peer may find the connection closed before it writes: it then reads nothing.
    const SCRIPT: &str = "import socket,sys\ns=socket.socket(socket.AF_UNIX)\ns.connect(sys.argv[1])\nd=b''\ntry:\n    s.sendall(b'{\"v\":1,\"op\":\"list\"}\\n')\n    while True:\n        c=s.recv(65536)\n        if not c: break\n        d+=c\nexcept OSError:\n    pass\nsys.stdout.write(d.decode())\n";
    for (listener_uid, served) in [(4242, false), (NOBODY, true)] {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        // Others may pass through the test's directory; the socket's own directory is root's
        // and the peer's group's, 0750, as the role makes it.
        std::fs::set_permissions(root, std::fs::Permissions::from_mode(0o711)).unwrap();
        for name in ["helper-socket", "helper-state", "spool"] {
            dir(&root.join(name), 0o700);
        }
        std::os::unix::fs::chown(root.join("helper-socket"), None, Some(NOBODY)).unwrap();
        // The spool is the listener's.
        std::os::unix::fs::chown(root.join("spool"), Some(listener_uid), None).unwrap();
        dir(&root.join("helper-socket"), 0o750);
        let config = helper_config(root, listener_uid);
        let helper = Helper::start(config.clone(), FakeBackend::new()).unwrap();
        let task = tokio::spawn(async move { helper::run(helper).await.unwrap() });
        while !config.socket.exists() {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        let output = tokio::process::Command::new("/usr/bin/setpriv")
            .args([
                "--reuid",
                "65534",
                "--regid",
                "65534",
                "--clear-groups",
                "python3",
                "-I",
                "-c",
                SCRIPT,
            ])
            .arg(&config.socket)
            .output()
            .await
            .unwrap();
        task.abort();
        let answer = String::from_utf8_lossy(&output.stdout);
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        if served {
            assert!(answer.contains("\"ok\""), "{answer}");
        } else {
            assert!(answer.is_empty(), "uid 65534 got: {answer}");
        }
    }
}

async fn idle_tcp(node: &Node, n: usize) -> Vec<tokio::net::TcpStream> {
    let mut idle = Vec::new();
    for _ in 0..n {
        idle.push(tokio::net::TcpStream::connect(node.tls_addr).await.unwrap());
    }
    // Let the listener accept them all.
    tokio::time::sleep(Duration::from_millis(100)).await;
    idle
}

#[tokio::test]
async fn idle_connections_without_the_key_never_lock_the_control_host_out() {
    let node = node(None).await;
    // More connections that say nothing than there are handshake places, and than there are
    // served places (which the old code took at accept): the control host gets in at once all
    // the same, not after their timeout.
    let _idle = idle_tcp(&node, listener::MAX_CONNECTIONS + listener::HANDSHAKE_SLOTS).await;
    let started = Instant::now();
    let mut driver = tls_driver(&node, Some(node.control_cert.clone()))
        .await
        .unwrap();
    driver.hello().await;
    assert!(
        started.elapsed() < listener::HANDSHAKE_TIMEOUT,
        "{:?}",
        started.elapsed()
    );
    // Many times the places, while the control host is served: it is never displaced.
    let _more = idle_tcp(&node, 4 * listener::HANDSHAKE_SLOTS).await;
    driver.call("list", json!({})).await.unwrap();
    let started = Instant::now();
    let mut second = tls_driver(&node, Some(node.control_cert.clone()))
        .await
        .unwrap();
    second.hello().await;
    assert!(
        started.elapsed() < listener::HANDSHAKE_TIMEOUT,
        "{:?}",
        started.elapsed()
    );
    driver.call("list", json!({})).await.unwrap();
}

#[tokio::test]
async fn the_listener_serves_at_most_its_connections_on_each_transport() {
    let node = node(None).await;
    // TLS: as many control-host connections as the bound; one more is closed after its
    // handshake, and the ones served stay served.
    let mut served = Vec::new();
    for _ in 0..listener::MAX_CONNECTIONS {
        let mut driver = tls_driver(&node, Some(node.control_cert.clone()))
            .await
            .unwrap();
        driver.hello().await;
        served.push(driver);
    }
    assert!(!answered(&node, Some(node.control_cert.clone())).await);
    served[0].call("list", json!({})).await.unwrap();
    drop(served);

    // The local socket: the same bound.
    let mut served = Vec::new();
    for _ in 0..listener::MAX_CONNECTIONS {
        let mut driver = unix_driver(&node).await;
        driver.hello().await;
        served.push(driver);
    }
    tokio::time::sleep(Duration::from_millis(100)).await;
    let mut extra = tokio::net::UnixStream::connect(&node.socket).await.unwrap();
    let _ = extra
        .write_all(format!("{}\n", hello_line()).as_bytes())
        .await;
    let mut buf = Vec::new();
    let _ = tokio::time::timeout(Duration::from_secs(5), extra.read_to_end(&mut buf)).await;
    assert!(buf.is_empty(), "{}", String::from_utf8_lossy(&buf));
    served[0].call("list", json!({})).await.unwrap();
}

fn hello_line() -> String {
    json!({"jsonrpc": "2.0", "id": 1, "method": "hello", "params": {"wire": 1, "build": wire::build_id()}})
        .to_string()
}

/// A helper that answers the listener's first request (its start's `list`) and none after.
async fn hanging_helper(socket: PathBuf) -> tokio::task::JoinHandle<()> {
    let listener = tokio::net::UnixListener::bind(&socket).unwrap();
    tokio::spawn(async move {
        let mut held = Vec::new();
        let mut first = true;
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            if first {
                first = false;
                let mut reader = BufReader::new(&mut stream);
                let _ = local::read_line(&mut reader, 4096).await;
                let _ = stream
                    .write_all(b"{\"ok\":{\"vms\":[],\"free\":4}}\n")
                    .await;
            } else {
                held.push(stream);
            }
        }
    })
}

#[tokio::test]
async fn a_connection_has_at_most_its_requests_in_flight() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path();
    for name in [
        "helper-socket",
        "spool",
        "listener-state",
        "listener-socket",
    ] {
        dir(&root.join(name), 0o700);
    }
    let own = local::own_uid().unwrap();
    let helper_socket = root.join("helper-socket/helper.sock");
    let helper = hanging_helper(helper_socket.clone()).await;
    let config = listener_config(root, &helper_socket, [0; 32], own);
    let socket = config.socket.as_ref().unwrap().path.clone();
    let listener = Listener::start(config).await.unwrap();
    let unix = local::bind(&socket, own).unwrap();
    let serving = tokio::spawn(listener.serve_unix(unix, own));
    let mut driver = unix_driver_at(&socket).await;
    driver.hello().await;
    // Requests that the helper never answers fill the connection's places; one more is busy.
    for id in 0..=listener::MAX_IN_FLIGHT {
        let request = json!({"jsonrpc": "2.0", "id": 100 + id, "method": "list", "params": {}});
        driver.send_raw(request.to_string().as_bytes()).await;
    }
    let answer = driver.line().await.unwrap();
    assert_eq!(answer["error"]["code"], wire::code::BUSY, "{answer}");
    assert_eq!(answer["id"], 100 + listener::MAX_IN_FLIGHT as u64);
    serving.abort();
    helper.abort();
}

#[tokio::test]
async fn the_listener_refuses_to_start_as_root() {
    let own = local::own_uid().unwrap();
    if own != 0 {
        eprintln!("skipped: this test runs as uid {own}, not root");
        return;
    }
    let half = helper_half().await;
    let mut config = listener_config(&half.root, &half.config.socket, [0; 32], 990);
    config.allow_root = false;
    let error = Listener::start(config).await.err().unwrap();
    assert!(
        format!("{error:#}").contains("must not run as root"),
        "{error:#}"
    );
    half.task.abort();
    drop(half.tmp);
}

fn records_dir(root: &Path) -> PathBuf {
    root.join("listener-state").join(listener::RECORDS)
}

fn template() -> TemplateName {
    TemplateName::parse(TEMPLATE.as_bytes()).unwrap()
}

/// A VM the helper makes for no record of the listener's: what a crashed listener leaves.
async fn unrecorded_vm(root: &Path, client: &HelperClient) -> VmId {
    let id = VmId::generate().unwrap();
    std::fs::write(
        spool::user_data_path(&root.join("spool"), &id),
        b"{\"ticket\":1}",
    )
    .unwrap();
    client.make(&id, &template()).await.unwrap();
    id
}

#[tokio::test]
async fn the_listener_agrees_with_the_helper_at_start_and_on_each_list() {
    let half = helper_half().await;
    let client = HelperClient::new(half.config.socket.clone());
    // A VM that no record holds, whose user data is still in the spool.
    unrecorded_vm(&half.root, &client).await;
    assert_eq!(half.backend.vms().len(), 1);
    // A record whose VM the helper does not have.
    let stale = VmId::generate().unwrap();
    dir(&records_dir(&half.root), 0o700);
    let record =
        json!({"id": stale, "name": "n", "template": TEMPLATE, "labels": {"tinfirm-host": HOST}});
    std::fs::write(
        records_dir(&half.root).join(format!("{stale}.json")),
        record.to_string(),
    )
    .unwrap();

    let node = node_from(half, None).await;
    assert!(
        node.backend.vms().is_empty(),
        "the unrecorded VM is deleted"
    );
    assert!(
        !records_dir(&node.root)
            .join(format!("{stale}.json"))
            .exists()
    );
    assert_eq!(
        std::fs::read_dir(node.root.join("spool")).unwrap().count(),
        0,
        "the spool is emptied"
    );

    // While it runs: a `list` deletes a VM that no record holds again.
    let client = HelperClient::new(node.helper_socket.clone());
    unrecorded_vm(&node.root, &client).await;
    let mut driver = unix_driver(&node).await;
    driver.hello().await;
    let listed = driver.call("list", json!({})).await.unwrap();
    assert_eq!(listed["vms"], json!([]));
    assert!(node.backend.vms().is_empty());
}

#[tokio::test]
async fn a_failed_create_leaves_no_record_and_no_spool_file() {
    let node = node(None).await;
    let mut driver = unix_driver(&node).await;
    driver.hello().await;
    node.backend.fail_next_make();
    let (code, _) = driver
        .call(
            "create",
            json!({"template": TEMPLATE, "name": "n", "userData": "{\"ticket\":1}", "labels": labels(HOST, "v")}),
        )
        .await
        .unwrap_err();
    assert_eq!(code, wire::code::FAILED);
    let records: Vec<_> = std::fs::read_dir(records_dir(&node.root))
        .unwrap()
        .collect();
    assert!(records.is_empty(), "{records:?}");
    assert_eq!(
        std::fs::read_dir(node.root.join("spool")).unwrap().count(),
        0
    );
    // The half-made VM was removed with it.
    assert!(node.backend.vms().is_empty());
}
