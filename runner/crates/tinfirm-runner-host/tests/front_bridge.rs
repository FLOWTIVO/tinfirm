//! The sandbox's bridge in its front (ADR-0095; ADR-0112, path C, part 2): a real front
//! process and the bridge crate's own sandbox side, in this process; `run`'s side is the
//! library's. The front does the TLS and reads the `hello`, `run` decides it, and the session
//! runs in the front.

#![cfg(target_os = "linux")]

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tinfirm_bridge::control::{Control, Hidden, ProbeCheck, RefuseCode, Start};
use tinfirm_bridge::frame::{self, Frame, Kind};
use tinfirm_bridge::host::{Exit, HostTiming, Output};
use tinfirm_bridge::link::Timing;
use tinfirm_bridge::sandbox::{self, Config, SandboxTiming};
use tinfirm_bridge::tls::{self, Identity};
use tinfirm_runner_host::front::handle::{Ended, FrontCommand};
use tinfirm_runner_host::front::sandbox::SandboxFront;
use tinfirm_runner_host::front::session::{
    BridgeRegistration, Expected, FrontIo, FrontSession, Peer,
};
use tinfirm_runner_host::front::wire::{self, ToFront, ToMain};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

/// The bridge crate's hostile sandbox: its bytes, against a front here.
#[path = "../../tinfirm-bridge/tests/hostile/mod.rs"]
mod hostile;

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");
const WAIT: Duration = Duration::from_secs(20);

/// The tests of this file count this process's descriptors: one runs at a time.
static SERIAL: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn the_front() -> FrontCommand {
    FrontCommand {
        program: PathBuf::from(BIN),
        args: vec![tinfirm_runner_host::front::SUBCOMMAND.to_string()],
        env: Vec::new(),
    }
}

fn link_timing() -> Timing {
    Timing {
        tick: Duration::from_millis(20),
        ping_after: Duration::from_millis(200),
        dead_after: Duration::from_secs(2),
        write_timeout: Duration::from_secs(2),
    }
}

fn host_timing() -> HostTiming {
    HostTiming {
        link: link_timing(),
        handshake: Duration::from_secs(5),
        gone_after: Duration::from_secs(5),
        tunnel_connect: Duration::from_secs(2),
        dial_retry: Duration::from_millis(50),
    }
}

fn sandbox_timing() -> SandboxTiming {
    SandboxTiming {
        link: link_timing(),
        gone_after: Duration::from_secs(20),
        reclaim_wait: Duration::from_secs(20),
        stop_step: Duration::from_millis(300),
        retry: Duration::from_millis(50),
        connect_timeout: Duration::from_secs(2),
        drain_timeout: Duration::from_secs(2),
        idle_after_exit: Duration::from_secs(10),
        listen_wait: Duration::from_secs(5),
    }
}

/// The bridge crate's sandbox side, in this process, connecting to `addr`.
fn spawn_bridge(
    addr: SocketAddr,
    run_id: &str,
    ticket: &str,
    identity: &Identity,
) -> JoinHandle<i32> {
    tokio::spawn(sandbox::run(Config {
        addr: addr.to_string(),
        listen: None,
        run_id: run_id.to_string(),
        ticket: Hidden::new(ticket),
        pin: tls::parse_pin(&identity.pin_hex()).unwrap(),
        timing: sandbox_timing(),
        build: "test-build".into(),
    }))
}

/// The sandbox side as a process of its own (this host's `bridge`), so that what it holds is
/// not counted with `run`'s descriptors.
fn bridge_process(
    addr: SocketAddr,
    run_id: &str,
    ticket: &str,
    identity: &Identity,
) -> tokio::process::Child {
    tokio::process::Command::new(BIN)
        .arg("bridge")
        .env_clear()
        .env("PATH", std::env::var("PATH").unwrap_or_default())
        .env(tinfirm_bridge::ENV_ADDR, addr.to_string())
        .env(tinfirm_bridge::ENV_RUN, run_id)
        .env(tinfirm_bridge::ENV_TICKET, ticket)
        .env(tinfirm_bridge::ENV_PIN, identity.pin_hex())
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap()
}

/// A front that serves the bridge of `run_id` on a listener of its own, at `port` (0: any).
async fn bridged_front(
    run_id: &str,
    expected: Expected,
    identity: &Identity,
    port: u16,
) -> (Arc<SandboxFront>, BridgeRegistration, SocketAddr) {
    let front = SandboxFront::start(&the_front()).await.unwrap();
    // The port of a front that just ended may take a moment to be free.
    let mut tries = 0;
    let listener = loop {
        match TcpListener::bind(("127.0.0.1", port)).await {
            Ok(listener) => break listener,
            Err(_) if tries < 100 => {
                tries += 1;
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(e) => panic!("cannot bind {port}: {e}"),
        }
    };
    let addr = listener.local_addr().unwrap();
    let registration = front
        .bridge(
            identity,
            &host_timing(),
            Peer::Listen(listener),
            run_id,
            expected,
        )
        .await
        .unwrap();
    (front, registration, addr)
}

/// A front whose sandbox's bridge has connected: the front, the session, the bridge's task,
/// the identity and the front's listener.
type Connected = (
    Arc<SandboxFront>,
    FrontSession,
    JoinHandle<i32>,
    Identity,
    SocketAddr,
);

async fn connected(run_id: &str) -> Connected {
    let identity = Identity::generate().unwrap();
    let (front, registration, addr) =
        bridged_front(run_id, Expected::new_ticket().unwrap(), &identity, 0).await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    let bridge = spawn_bridge(addr, run_id, registration.ticket().unwrap(), &identity);
    let session = registration.connected(WAIT).await.unwrap();
    (front, session, bridge, identity, addr)
}

fn shell(script: &str) -> Start {
    Start {
        argv: vec!["sh".into(), "-c".into(), script.into()],
        env: Vec::new(),
        cwd: None,
    }
}

/// Reads the adapter's output until it ends: stdout and stderr.
async fn drain(io: &mut FrontIo) -> (String, String) {
    let (mut out, mut err) = (Vec::new(), Vec::new());
    loop {
        match tokio::time::timeout(WAIT, io.output.recv()).await {
            Ok(Some(Output::Stdout(b))) => out.extend(b),
            Ok(Some(Output::Stderr(b))) => err.extend(b),
            Ok(None) => break,
            Err(_) => panic!("the output did not end: {out:?} {err:?}"),
        }
    }
    (
        String::from_utf8(out).unwrap(),
        String::from_utf8(err).unwrap(),
    )
}

async fn read_until(io: &mut FrontIo, needle: &str) {
    let mut out = Vec::new();
    while !String::from_utf8_lossy(&out).contains(needle) {
        match tokio::time::timeout(WAIT, io.output.recv()).await {
            Ok(Some(Output::Stdout(b))) => out.extend(b),
            Ok(Some(Output::Stderr(_))) => {}
            other => panic!("no {needle:?} before {other:?}"),
        }
    }
}

async fn finish(session: &FrontSession, bridge: JoinHandle<i32>) -> i32 {
    session.close(WAIT).await;
    tokio::time::timeout(WAIT, bridge)
        .await
        .expect("the bridge ends once the session is closed")
        .unwrap()
}

/// This process's open descriptors.
fn descriptors() -> usize {
    std::fs::read_dir("/proc/self/fd").unwrap().count()
}

/// Waits until `f` holds, or fails after `WAIT` with `what`.
async fn eventually(what: &str, f: impl Fn() -> bool) {
    let deadline = tokio::time::Instant::now() + WAIT;
    while !f() {
        assert!(tokio::time::Instant::now() < deadline, "{what}");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_bridge_in_a_front_runs_the_adapter_and_the_hosts_commands() {
    let _one = SERIAL.lock().await;
    let (front, mut session, bridge, _identity, _addr) = connected("run-front-1").await;
    assert_eq!(session.bridge_build(), Some("test-build"));
    assert_eq!(session.session_key().expose().len(), 64);
    let files = session.files();
    let out = files
        .exec(
            &["sh", "-c", "printf hi; printf err >&2; exit 3"],
            &[("A", "1")],
            None,
            None,
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(
        (out.code, out.stdout.as_str(), out.stderr.as_str()),
        (Some(3), "hi", "err")
    );
    // A command's end at its full size, with output that JSON would escape six times over:
    // it passes within the frame's bound, and the front lives on.
    let out = files
        .exec(
            &[
                "sh",
                "-c",
                "head -c 65536 /dev/zero | tr '\\000' '\\001'; head -c 65536 /dev/zero | tr '\\000' '\"' >&2",
            ],
            &[],
            None,
            None,
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(out.stdout.len(), 65536);
    assert!(out.stdout.bytes().all(|b| b == 1));
    assert_eq!(out.stderr.len(), 65536);
    assert!(front.handle().ended().is_none());
    // Nothing listens on port 1: the probe finds nothing open.
    let open = files
        .probe(
            vec![ProbeCheck::Tcp {
                addr: "127.0.0.1:1".into(),
            }],
            Duration::from_secs(5),
        )
        .await
        .unwrap();
    assert!(open.is_empty(), "{open:?}");
    let mut io = session
        .start(shell("echo ready; cat; echo bye >&2"))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    io.stdin.write_all(b"hello\n").await.unwrap();
    io.stdin.shutdown().await.unwrap();
    let (out, err) = drain(&mut io).await;
    assert_eq!(out, "hello\n");
    assert_eq!(err, "bye\n");
    io.exit.wait_for(Option::is_some).await.unwrap();
    assert_eq!(
        *io.exit.borrow(),
        Some(Exit::Exited {
            code: Some(0),
            signal: None
        })
    );
    assert_eq!(finish(&session, bridge).await, 0);
    eventually("the session ends with the bridge", || session.has_ended()).await;
    assert_eq!(front.handle().bridge_waiting(), (0, 0));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_new_front_takes_the_session_of_one_that_ended_back_with_its_key() {
    let _one = SERIAL.lock().await;
    let (front, mut session, bridge, identity, addr) = connected("run-front-2").await;
    let mut io = session.start(shell("echo up; sleep 30")).await.unwrap();
    read_until(&mut io, "up").await;
    let key = session.session_key().clone();
    // The front ends with its last owner, as when it crashes: the session in `run` ends too.
    let ended = front.handle().end_watch();
    let watched = session.exit();
    drop(io);
    drop(session);
    drop(front);
    assert!(!matches!(ended.wait().await, Ended::Broken(_)));
    assert_eq!(*watched.borrow(), Some(Exit::Lost));
    // A new front, with the same identity on the same port, takes the session back with its
    // key: the bridge comes back to it, and the agent that the first one started stops.
    let (front, registration, _) = bridged_front(
        "run-front-2",
        Expected::Reclaim(key.clone()),
        &identity,
        addr.port(),
    )
    .await;
    assert!(registration.ticket().is_none());
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    let mut session = registration.connected(WAIT).await.unwrap();
    assert_eq!(session.session_key(), &key);
    assert!(session.reclaim(WAIT).await.unwrap(), "the agent still ran");
    assert!(matches!(
        *session.exit().borrow(),
        Some(Exit::Exited { .. })
    ));
    // The files are still there to take.
    let out = session
        .files()
        .exec(&["echo", "after"], &[], None, None, WAIT)
        .await
        .unwrap();
    assert_eq!(out.stdout, "after\n");
    let code = finish(&session, bridge).await;
    assert_ne!(code, sandbox::EXIT_HOST_GONE);
    drop(front);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn bytes_written_right_before_runs_end_of_stdin_all_reach_the_agent_then_the_end() {
    let _one = SERIAL.lock().await;
    let (_front, mut session, bridge, _identity, _addr) = connected("run-front-stdin-1").await;
    // `wc` counts until its stdin ends: its count is every byte, and it comes only after the
    // end.
    let mut io = session
        .start(shell("echo ready; wc -c; echo ended >&2"))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    let bytes = vec![b'x'; 1 << 20];
    io.stdin.write_all(&bytes).await.unwrap();
    io.stdin.write_all(b"last").await.unwrap();
    io.stdin.shutdown().await.unwrap();
    let (out, err) = drain(&mut io).await;
    assert_eq!(out.trim(), ((1 << 20) + 4).to_string());
    assert_eq!(err, "ended\n");
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_stdin_that_ends_without_runs_word_never_ends_the_agents_input() {
    let _one = SERIAL.lock().await;
    let dir = tempfile::tempdir().unwrap();
    let marker = dir.path().join("stdin-ended");
    let (front, mut session, bridge, identity, addr) = connected("run-front-stdin-2").await;
    let mut io = session
        .start(shell(&format!(
            "echo up; cat; touch '{}'; sleep 30",
            marker.display()
        )))
        .await
        .unwrap();
    read_until(&mut io, "up").await;
    io.stdin.write_all(b"through\n").await.unwrap();
    read_until(&mut io, "through").await;
    // `run`'s end of the stdin socket closes as a killed `run`'s does, with no word, and the
    // front is held back: its control channel stays open, well past the moment it would have
    // ended. The agent's stdin stays open all that time.
    let FrontIo {
        stdin,
        output,
        exit,
    } = io;
    drop(stdin);
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert!(!marker.exists(), "the agent's stdin ended");
    assert!(
        exit.borrow().is_none(),
        "the agent ended: {:?}",
        *exit.borrow()
    );
    assert!(front.handle().ended().is_none());
    // Then the front ends, as with `run`'s end: the agent goes on, and a new front takes the
    // session back and finds it running, its stdin never ended.
    let key = session.session_key().clone();
    let ended = front.handle().end_watch();
    drop((output, exit, session, front));
    assert!(!matches!(ended.wait().await, Ended::Broken(_)));
    let (front, registration, _) = bridged_front(
        "run-front-stdin-2",
        Expected::Reclaim(key),
        &identity,
        addr.port(),
    )
    .await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    let mut session = registration.connected(WAIT).await.unwrap();
    assert!(!marker.exists(), "the agent's stdin ended");
    assert!(session.reclaim(WAIT).await.unwrap(), "the agent still ran");
    assert!(!marker.exists(), "the agent's stdin ended");
    let code = finish(&session, bridge).await;
    assert_ne!(code, sandbox::EXIT_HOST_GONE);
    drop(front);
}

/// Forwards each connection on `listener` to `to`, and counts them; `cut` ends the ones open
/// now, as a network that loses them does.
async fn relay(
    listener: TcpListener,
    to: SocketAddr,
) -> (
    Arc<std::sync::atomic::AtomicUsize>,
    tokio::sync::watch::Sender<u64>,
) {
    let count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let (cut, _) = tokio::sync::watch::channel(0u64);
    let (counted, cuts) = (count.clone(), cut.clone());
    tokio::spawn(async move {
        while let Ok((mut inbound, _)) = listener.accept().await {
            counted.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            let mut cut = cuts.subscribe();
            cut.borrow_and_update();
            tokio::spawn(async move {
                let Ok(mut outbound) = TcpStream::connect(to).await else {
                    return;
                };
                tokio::select! {
                    _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound) => {}
                    _ = cut.changed() => {}
                }
            });
        }
    });
    (count, cut)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn run_dials_a_listening_bridge_for_its_front_and_again_when_the_link_is_lost() {
    let _one = SERIAL.lock().await;
    let identity = Identity::generate().unwrap();
    let ticket = "d".repeat(64);
    // The sandbox's bridge listens, as a VM's does; `run` reaches it through a relay that can
    // lose its connections.
    let bridge_listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let bridge_addr = bridge_listener.local_addr().unwrap();
    drop(bridge_listener);
    let bridge = tokio::spawn(sandbox::run(Config {
        addr: String::new(),
        listen: Some(bridge_addr),
        run_id: "run-front-dial".to_string(),
        ticket: Hidden::new(ticket.clone()),
        pin: tls::parse_pin(&identity.pin_hex()).unwrap(),
        timing: sandbox_timing(),
        build: "test-build".into(),
    }));
    let relay_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let target = relay_listener.local_addr().unwrap();
    let (dialed, cut) = relay(relay_listener, bridge_addr).await;
    let front = SandboxFront::start(&the_front()).await.unwrap();
    let registration = front
        .bridge(
            &identity,
            &host_timing(),
            Peer::Dial(target.to_string()),
            "run-front-dial",
            Expected::Ticket(Hidden::new(ticket)),
        )
        .await
        .unwrap();
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session
        .start(shell(
            "echo up; while read line; do echo \"got $line\"; done",
        ))
        .await
        .unwrap();
    read_until(&mut io, "up").await;
    io.stdin.write_all(b"one\n").await.unwrap();
    read_until(&mut io, "got one").await;
    let before = dialed.load(std::sync::atomic::Ordering::SeqCst);
    // The link loses its connection: `run` dials again, and the session goes on.
    cut.send_modify(|n| *n += 1);
    io.stdin.write_all(b"two\n").await.unwrap();
    read_until(&mut io, "got two").await;
    assert!(dialed.load(std::sync::atomic::Ordering::SeqCst) > before);
    // While the link is up, `run` dials no more.
    let now = dialed.load(std::sync::atomic::Ordering::SeqCst);
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert_eq!(dialed.load(std::sync::atomic::Ordering::SeqCst), now);
    io.stdin.shutdown().await.unwrap();
    let _ = drain(&mut io).await;
    assert_eq!(finish(&session, bridge).await, 0);
    drop(front);
}

/// A connection that says `hello` as `hello` asks, through TLS with `pin`, and the first
/// frame the front answers with.
async fn hello_to(addr: SocketAddr, identity: &Identity, hello: Control) -> Option<Control> {
    let tcp = TcpStream::connect(addr).await.ok()?;
    let connector = tokio_rustls::TlsConnector::from(tls::client_config(
        tls::parse_pin(&identity.pin_hex()).unwrap(),
    ));
    let mut tls = connector.connect(tls::server_name(), tcp).await.ok()?;
    let payload = serde_json::to_vec(&hello).unwrap();
    let bytes = Frame::new(Kind::Control, frame::STREAM_CONTROL, 0, payload).encode();
    tls.write_all(&bytes).await.ok()?;
    tls.flush().await.ok()?;
    let answer = tokio::time::timeout(WAIT, frame::read_frame(&mut tls))
        .await
        .ok()?
        .ok()??;
    serde_json::from_slice(&answer.payload).ok()
}

fn hello(
    run_id: &str,
    ticket: Option<&str>,
    session: Option<&str>,
    tx_seq: Option<u64>,
) -> Control {
    Control::Hello {
        version: tinfirm_bridge::PROTOCOL_VERSION,
        run_id: run_id.into(),
        ticket: ticket.map(Hidden::new),
        session: session.map(Hidden::new),
        rx_seq: 0,
        tx_seq,
        build: None,
    }
}

fn refused(answer: Option<Control>) -> Option<RefuseCode> {
    match answer {
        Some(Control::Refused { code, .. }) => Some(code),
        _ => None,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hostile_peers_of_a_front_are_refused_and_its_session_goes_on() {
    let _one = SERIAL.lock().await;
    let identity = Identity::generate().unwrap();
    let ticket = "t".repeat(64);
    let (front, registration, addr) = bridged_front(
        "run-front-3",
        Expected::Ticket(Hidden::new(ticket.clone())),
        &identity,
        0,
    )
    .await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    let mut rng = hostile::Rng::new(0x5eed_b41d);
    // Bytes that are no TLS, and a TLS connection whose first frame is no hello.
    for _ in 0..8 {
        let mut tcp = TcpStream::connect(addr).await.unwrap();
        let bytes: Vec<u8> = (0..4096).map(|_| rng.next() as u8).collect();
        let _ = tcp.write_all(&bytes).await;
        let mut sink = Vec::new();
        let _ = tokio::time::timeout(WAIT, tcp.read_to_end(&mut sink)).await;
    }
    {
        let tcp = TcpStream::connect(addr).await.unwrap();
        let connector = tokio_rustls::TlsConnector::from(tls::client_config(
            tls::parse_pin(&identity.pin_hex()).unwrap(),
        ));
        let mut tls = connector.connect(tls::server_name(), tcp).await.unwrap();
        let bytes: Vec<u8> = (0..4096).map(|_| rng.next() as u8).collect();
        let _ = tls.write_all(&bytes).await;
        let _ = tls.flush().await;
        let mut sink = Vec::new();
        let _ = tokio::time::timeout(WAIT, tls.read_to_end(&mut sink)).await;
        // A TLS connection that leaves before its hello.
        let tcp = TcpStream::connect(addr).await.unwrap();
        let tls = connector.connect(tls::server_name(), tcp).await.unwrap();
        drop(tls);
    }
    // A wrong ticket, another run's id, and a session key before any session.
    for (run_id, ticket, session, want) in [
        (
            "run-front-3",
            Some("x".repeat(64)),
            None,
            RefuseCode::BadTicket,
        ),
        (
            "run-other",
            Some(ticket.clone()),
            None,
            RefuseCode::BadTicket,
        ),
        (
            "run-front-3",
            None,
            Some("s".repeat(64)),
            RefuseCode::UnknownSession,
        ),
    ] {
        let answer = hello_to(
            addr,
            &identity,
            hello(run_id, ticket.as_deref(), session.as_deref(), None),
        )
        .await;
        assert_eq!(refused(answer), Some(want), "{run_id}");
    }
    // The real bridge still connects with the ticket.
    let bridge = spawn_bridge(addr, "run-front-3", &ticket, &identity);
    let session = registration.connected(WAIT).await.unwrap();
    // The ticket works once; a wrong session key is no session.
    for (ticket, session_key, want) in [
        (Some(ticket.clone()), None, RefuseCode::TicketUsed),
        (None, Some("s".repeat(64)), RefuseCode::UnknownSession),
    ] {
        let answer = hello_to(
            addr,
            &identity,
            hello(
                "run-front-3",
                ticket.as_deref(),
                session_key.as_deref(),
                Some(0),
            ),
        )
        .await;
        assert_eq!(refused(answer), Some(want));
    }
    let out = session
        .files()
        .exec(&["echo", "alive"], &[], None, None, WAIT)
        .await
        .unwrap();
    assert_eq!(out.stdout, "alive\n");
    assert!(front.handle().ended().is_none());
    eventually("every hello came to its end", || {
        session.admitted_waiting() == 0 && front.handle().bridge_waiting() == (0, 0)
    })
    .await;
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_reclaim_past_any_frame_number_is_refused_and_the_session_waits_on() {
    let _one = SERIAL.lock().await;
    let identity = Identity::generate().unwrap();
    let key = "k".repeat(64);
    let (front, registration, addr) = bridged_front(
        "run-front-4",
        Expected::Reclaim(Hidden::new(key.clone())),
        &identity,
        0,
    )
    .await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    let past = tinfirm_bridge::link::MAX_SEQ + 1;
    let answer = hello_to(
        addr,
        &identity,
        hello("run-front-4", None, Some(&key), Some(past)),
    )
    .await;
    assert_eq!(refused(answer), Some(RefuseCode::BadRequest));
    let answer = hello_to(
        addr,
        &identity,
        hello("run-front-4", None, Some(&key), None),
    )
    .await;
    assert_eq!(refused(answer), Some(RefuseCode::UnsupportedVersion));
    // With its own numbers, the session of the earlier host comes back.
    let answer = hello_to(
        addr,
        &identity,
        hello("run-front-4", None, Some(&key), Some(3)),
    )
    .await;
    assert!(
        matches!(
            answer,
            Some(Control::Welcome {
                session: None,
                rx_seq: 3,
                ..
            })
        ),
        "{answer:?}"
    );
    let session = registration.connected(WAIT).await.unwrap();
    assert_eq!(session.session_key().expose(), key);
    drop(session);
    drop(front);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_connection_from_another_address_is_closed_before_tls() {
    let _one = SERIAL.lock().await;
    let identity = Identity::generate().unwrap();
    let ticket = "t".repeat(64);
    let (front, registration, addr) = bridged_front(
        "run-front-5",
        Expected::Ticket(Hidden::new(ticket.clone())),
        &identity,
        0,
    )
    .await;
    // The sandbox is 127.0.0.2; a connection from 127.0.0.3 (another sandbox) and one from
    // 127.0.0.1 are closed before a byte of TLS.
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)))
        .await
        .unwrap();
    for from in [Ipv4Addr::new(127, 0, 0, 3), Ipv4Addr::LOCALHOST] {
        let socket = tokio::net::TcpSocket::new_v4().unwrap();
        socket.bind(SocketAddr::new(IpAddr::V4(from), 0)).unwrap();
        let mut tcp = socket.connect(addr).await.unwrap();
        let mut got = Vec::new();
        let read = tokio::time::timeout(WAIT, tcp.read_to_end(&mut got)).await;
        assert!(read.is_ok(), "{from}: the front kept the connection");
        assert!(got.is_empty(), "{from}: the front answered");
    }
    // The sandbox's own address gets its session.
    let socket = tokio::net::TcpSocket::new_v4().unwrap();
    socket
        .bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), 0))
        .unwrap();
    let tcp = socket.connect(addr).await.unwrap();
    let connector = tokio_rustls::TlsConnector::from(tls::client_config(
        tls::parse_pin(&identity.pin_hex()).unwrap(),
    ));
    let mut tls = connector.connect(tls::server_name(), tcp).await.unwrap();
    let payload = serde_json::to_vec(&hello("run-front-5", Some(&ticket), None, Some(0))).unwrap();
    tls.write_all(&Frame::new(Kind::Control, frame::STREAM_CONTROL, 0, payload).encode())
        .await
        .unwrap();
    tls.flush().await.unwrap();
    let welcome = frame::read_frame(&mut tls).await.unwrap().unwrap();
    assert!(matches!(
        serde_json::from_slice::<Control>(&welcome.payload).unwrap(),
        Control::Welcome {
            session: Some(_),
            ..
        }
    ));
    let session = registration.connected(WAIT).await.unwrap();
    assert_eq!(session.admitted_waiting(), 0);
    drop(session);
    drop(front);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_peer_or_a_front_that_leaves_costs_run_nothing_that_stays() {
    let _one = SERIAL.lock().await;
    // A first front and session, so that what the runtime keeps for good is there before the
    // count.
    let (front, session, bridge, _, _) = connected("run-front-6a").await;
    assert_eq!(finish(&session, bridge).await, 0);
    drop((session, front));
    tokio::time::sleep(Duration::from_millis(300)).await;
    let before = descriptors();
    let identity = Identity::generate().unwrap();
    let ticket = "t".repeat(64);
    let (front, registration, addr) = bridged_front(
        "run-front-6",
        Expected::Ticket(Hidden::new(ticket.clone())),
        &identity,
        0,
    )
    .await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    let connector = tokio_rustls::TlsConnector::from(tls::client_config(
        tls::parse_pin(&identity.pin_hex()).unwrap(),
    ));
    for _ in 0..10 {
        // Leaves during the TLS handshake.
        let mut tcp = TcpStream::connect(addr).await.unwrap();
        let _ = tcp.write_all(&[0x16, 0x03, 0x01, 0x00]).await;
        drop(tcp);
        // Leaves after it, before its hello.
        let tcp = TcpStream::connect(addr).await.unwrap();
        drop(connector.connect(tls::server_name(), tcp).await.unwrap());
        // Leaves right after its hello, with a wrong ticket and with the right one.
        for presented in ["x".repeat(64), ticket.clone()] {
            let tcp = TcpStream::connect(addr).await.unwrap();
            let mut tls = connector.connect(tls::server_name(), tcp).await.unwrap();
            let payload =
                serde_json::to_vec(&hello("run-front-6", Some(&presented), None, Some(0))).unwrap();
            let _ = tls
                .write_all(&Frame::new(Kind::Control, frame::STREAM_CONTROL, 0, payload).encode())
                .await;
            let _ = tls.flush().await;
            drop(tls);
        }
    }
    // The real bridge connects; its session runs a command that the front's end cuts short.
    let mut bridge = bridge_process(addr, "run-front-6", &ticket, &identity);
    let session = match registration.connected(WAIT).await {
        Ok(session) => session,
        Err(e) => panic!("{e:#}"),
    };
    eventually("every hello came to its end", || {
        session.admitted_waiting() == 0 && front.handle().bridge_waiting().1 == 0
    })
    .await;
    let files = session.files();
    let running = tokio::spawn(async move {
        files
            .exec(&["sleep", "30"], &[], None, None, Duration::from_secs(60))
            .await
    });
    eventually("the command is asked", || {
        front.handle().bridge_waiting().0 == 1
    })
    .await;
    tinfirm_runner_host::confine::kill_group(front.handle().pgid());
    let failed = tokio::time::timeout(WAIT, running).await.unwrap().unwrap();
    assert!(failed.is_err());
    assert_eq!(front.handle().bridge_waiting(), (0, 0));
    eventually("the session ends with its front", || session.has_ended()).await;
    assert_eq!(*session.exit().borrow(), Some(Exit::Lost));
    drop(session);
    drop(front);
    let _ = bridge.kill().await;
    // Every descriptor of the front, its listener's and its sockets' included, is gone.
    let deadline = tokio::time::Instant::now() + WAIT;
    while descriptors() > before && tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let open: Vec<String> = std::fs::read_dir("/proc/self/fd")
        .unwrap()
        .filter_map(|e| std::fs::read_link(e.ok()?.path()).ok())
        .map(|p| p.display().to_string())
        .collect();
    assert!(descriptors() <= before, "{before} before, now {open:?}");
}

/// Where [`hostile_bridge_front`] tells [`hostile_bridge_front_child`] what to do, and the
/// ticket of the run it pretends to serve.
const HOSTILE: &str = "TINFIRM_TEST_HOSTILE_BRIDGE_FRONT";
const HOSTILE_TICKET: &str = "TINFIRM_TEST_HOSTILE_BRIDGE_TICKET";
const HOSTILE_RUN: &str = "run-hostile";

/// A fake front (this test binary, started again) that breaks the bridge's rules as `mode`
/// says.
fn hostile_bridge_front(mode: &str, ticket: &str) -> FrontCommand {
    FrontCommand {
        program: std::env::current_exe().unwrap(),
        args: [
            "hostile_bridge_front_child",
            "--exact",
            "--ignored",
            "--nocapture",
        ]
        .map(String::from)
        .to_vec(),
        env: vec![
            (HOSTILE.to_string(), mode.to_string()),
            (HOSTILE_TICKET.to_string(), ticket.to_string()),
        ],
    }
}

#[test]
#[ignore = "a hostile fake front, started by the tests of this file"]
fn hostile_bridge_front_child() {
    use std::io::{Read, Write};
    use std::os::fd::AsFd;
    let Ok(mode) = std::env::var(HOSTILE) else {
        return;
    };
    let ticket = std::env::var(HOSTILE_TICKET).unwrap();
    let socket = std::io::stdin().as_fd().try_clone_to_owned().unwrap();
    let mut out = std::fs::File::from(socket.try_clone().unwrap());
    let mut input = std::fs::File::from(socket.try_clone().unwrap());
    let (tx, rx) = std::sync::mpsc::channel::<ToFront>();
    std::thread::spawn(move || {
        loop {
            let mut header = [0u8; 4];
            if input.read_exact(&mut header).is_err() {
                return;
            }
            let mut payload = vec![0u8; u32::from_be_bytes(header) as usize];
            if input.read_exact(&mut payload).is_err() {
                return;
            }
            if let Ok(message) = wire::decode_to_front(&payload)
                && tx.send(message).is_err()
            {
                return;
            }
        }
    });
    let mut send = |message: &ToMain| {
        let frame = wire::encode(message, message.max_frame()).unwrap();
        out.write_all(&frame).unwrap();
    };
    send(&ToMain::Ready {
        status: tinfirm_runner_host::confine::Status::none("a hostile fake front"),
    });
    let next = |want: &dyn Fn(&ToFront) -> bool| -> ToFront {
        loop {
            let message = rx.recv().unwrap();
            if want(&message) {
                return message;
            }
        }
    };
    next(&|m| matches!(m, ToFront::BridgeInit { .. }));
    let hello = |req: u64, run_id: &str| ToMain::Hello {
        req,
        hello: wire::WireHello {
            run_id: run_id.into(),
            ticket: Some(Hidden::new(ticket.clone())),
            session: None,
            rx_seq: 0,
            tx_seq: Some(0),
            build: None,
        },
    };
    // The run's session, as a front that is well behaved this far opens it.
    let open = |send: &mut dyn FnMut(&ToMain)| {
        send(&hello(1, HOSTILE_RUN));
        next(&|m| matches!(m, ToFront::Admit { req: 1, .. }));
        send(&ToMain::Admitted {
            req: 1,
            attached: true,
        });
    };
    match mode.as_str() {
        "hello-flood" => {
            for req in 1..=wire::MAX_HELLOS as u64 + 1 {
                send(&hello(req, HOSTILE_RUN));
            }
        }
        "hello-backwards" => {
            send(&hello(5, HOSTILE_RUN));
            send(&hello(3, HOSTILE_RUN));
        }
        "hello-oversized" => send(&hello(1, &"r".repeat(wire::MAX_HELLO_NAME + 1))),
        "big-frame" => {
            // A hello padded past the bound of every message but a command's end.
            let mut payload = serde_json::to_vec(&hello(1, HOSTILE_RUN)).unwrap();
            payload.pop();
            payload.extend(vec![b' '; wire::MAX_TO_MAIN]);
            payload.push(b'}');
            let mut frame = (payload.len() as u32).to_be_bytes().to_vec();
            frame.extend(payload);
            out.write_all(&frame).unwrap();
        }
        "answer-unasked" => send(&ToMain::Answer {
            id: 1_000_000,
            reply: wire::Reply::Done,
        }),
        "admitted-unknown" => send(&ToMain::Admitted {
            req: 7,
            attached: true,
        }),
        "link-after-end" => {
            open(&mut send);
            send(&ToMain::Link { up: true });
            send(&ToMain::SessionEnded {});
            send(&ToMain::Link { up: false });
        }
        "exited-twice" => {
            open(&mut send);
            for _ in 0..2 {
                send(&ToMain::Exited {
                    code: Some(0),
                    signal: None,
                });
            }
        }
        "exec-too-big" | "exec-not-text" | "probe-unasked" | "wrong-kind" => {
            open(&mut send);
            let asked = next(&|m| matches!(m, ToFront::Exec { .. } | ToFront::Probe { .. }));
            let id = match &asked {
                ToFront::Exec { exec } => exec.id,
                ToFront::Probe { id, .. } => *id,
                _ => unreachable!(),
            };
            let reply = match mode.as_str() {
                "exec-too-big" => wire::Reply::ExecDone {
                    code: Some(0),
                    signal: None,
                    stdout: "A".repeat(wire::MAX_EXEC_OUTPUT_B64 + 4),
                    stderr: String::new(),
                },
                "exec-not-text" => wire::Reply::ExecDone {
                    code: Some(0),
                    signal: None,
                    stdout: "//4=".into(),
                    stderr: String::new(),
                },
                "probe-unasked" => wire::Reply::Probed {
                    open: vec!["somewhere else".into()],
                },
                _ => wire::Reply::Reclaimed { running: false },
            };
            send(&ToMain::Answer { id, reply });
        }
        "answer-twice" | "late-once" | "late-twice" => {
            let ToFront::Probe { id, .. } = next(&|m| matches!(m, ToFront::Probe { .. })) else {
                unreachable!()
            };
            if mode != "answer-twice" {
                // Later than `run` waits.
                std::thread::sleep(Duration::from_millis(1000));
            }
            let times = if mode == "late-once" { 1 } else { 2 };
            for _ in 0..times {
                send(&ToMain::Answer {
                    id,
                    reply: wire::Reply::Probed { open: Vec::new() },
                });
            }
        }
        "answer-ping-id" => {
            let ToFront::Ping { id } = next(&|m| matches!(m, ToFront::Ping { .. })) else {
                unreachable!()
            };
            send(&ToMain::Answer {
                id,
                reply: wire::Reply::Done,
            });
        }
        "tunnel-other-ip" | "tunnel-port-0" => {
            open(&mut send);
            let ToFront::OpenTunnels { id, .. } =
                next(&|m| matches!(m, ToFront::OpenTunnels { .. }))
            else {
                unreachable!()
            };
            let addr = if mode == "tunnel-other-ip" {
                "127.0.0.9:4000"
            } else {
                "127.0.0.1:0"
            };
            send(&ToMain::Answer {
                id,
                reply: wire::Reply::Listening {
                    addrs: vec![addr.parse().unwrap()],
                },
            });
        }
        other => panic!("no mode {other}"),
    }
    // Stays until `run` ends it.
    std::thread::sleep(Duration::from_secs(60));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_front_that_breaks_the_bridges_rules_is_ended_and_leaves_nothing_in_run() {
    let _one = SERIAL.lock().await;
    let ticket = "t".repeat(64);
    let cases = [
        ("hello-flood", "more than 8 hellos"),
        ("hello-backwards", "a request 3 after 5"),
        ("hello-oversized", "out of bounds"),
        ("big-frame", "over the limit"),
        ("answer-unasked", "an answer to nothing asked"),
        ("admitted-unknown", "an unknown hello 7"),
        ("exited-twice", "the adapter ended twice"),
        ("link-after-end", "the link of a session that ended"),
        ("exec-too-big", "out of bounds"),
        ("exec-not-text", "not text"),
        ("probe-unasked", "not asked"),
        ("wrong-kind", "answered a command with `reclaimed`"),
        ("tunnel-other-ip", "other addresses than asked"),
        ("tunnel-port-0", "other addresses than asked"),
    ];
    for (mode, why) in cases {
        let front = SandboxFront::start(&hostile_bridge_front(mode, &ticket))
            .await
            .unwrap();
        let identity = Identity::generate().unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let registration = front
            .bridge(
                &identity,
                &host_timing(),
                Peer::Listen(listener),
                HOSTILE_RUN,
                Expected::Ticket(Hidden::new(ticket.clone())),
            )
            .await
            .unwrap();
        let ended = front.handle().end_watch();
        // What `run` still holds of the bridge's hellos, read once the front has ended.
        let waiting: Box<dyn Fn() -> usize> = match mode {
            "exec-too-big" | "exec-not-text" | "probe-unasked" | "wrong-kind" => {
                let session = registration.connected(WAIT).await.unwrap();
                let files = session.files();
                let asked = if mode == "probe-unasked" {
                    files
                        .probe(
                            vec![ProbeCheck::Tcp {
                                addr: "127.0.0.1:1".into(),
                            }],
                            Duration::from_secs(5),
                        )
                        .await
                        .map(|_| ())
                } else {
                    files
                        .exec(&["true"], &[], None, None, Duration::from_secs(5))
                        .await
                        .map(|_| ())
                };
                assert!(asked.is_err(), "{mode}: the answer was taken");
                Box::new(move || session.admitted_waiting())
            }
            "tunnel-other-ip" | "tunnel-port-0" => {
                let mut session = registration.connected(WAIT).await.unwrap();
                let asked = session
                    .open_tunnels(
                        &[(
                            "127.0.0.1:0".parse().unwrap(),
                            tinfirm_runner_host::front::wire::Service::Egress,
                        )],
                        Duration::from_secs(5),
                    )
                    .await;
                assert!(asked.is_err(), "{mode}: the addresses were taken");
                Box::new(move || session.admitted_waiting())
            }
            "exited-twice" => {
                let session = registration.connected(WAIT).await.unwrap();
                eventually("the session ends with its front", || session.has_ended()).await;
                Box::new(move || session.admitted_waiting())
            }
            _ => Box::new(move || registration.admitted_waiting()),
        };
        let how = tokio::time::timeout(WAIT, ended.wait())
            .await
            .unwrap_or_else(|_| panic!("{mode}: the front was not ended"));
        match how {
            Ended::Broken(reason) => assert!(reason.contains(why), "{mode}: {reason}"),
            other => panic!("{mode}: {other}"),
        }
        eventually("nothing of the front stays in run", || {
            front.handle().bridge_waiting() == (0, 0) && front.answering() == 0 && waiting() == 0
        })
        .await;
    }
}

/// A TLS connection to a front's listener, as a bridge makes it.
async fn hostile_connect(
    addr: SocketAddr,
    identity: &Identity,
) -> tokio_rustls::client::TlsStream<TcpStream> {
    let tcp = TcpStream::connect(addr).await.unwrap();
    let connector = tokio_rustls::TlsConnector::from(tls::client_config(
        tls::parse_pin(&identity.pin_hex()).unwrap(),
    ));
    connector.connect(tls::server_name(), tcp).await.unwrap()
}

/// The bridge crate's flood after a `hello`, against a front: it ends at most its own session,
/// never the front's wire to `run`, and `run`'s requests get an answer, an error or a timeout.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_hostile_peer_after_its_hello_ends_at_most_its_own_session_and_never_its_front() {
    let _one = SERIAL.lock().await;
    for seed in 0..10u64 {
        let run_id = format!("run-hostile-{seed}");
        let identity = Identity::generate().unwrap();
        let ticket = format!("{seed:064}");
        let (front, registration, addr) = bridged_front(
            &run_id,
            Expected::Ticket(Hidden::new(ticket.clone())),
            &identity,
            0,
        )
        .await;
        registration
            .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
            .await
            .unwrap();
        let tls = hostile_connect(addr, &identity).await;
        let (mut reader, mut writer) = tokio::io::split(tls);
        assert!(hostile::send_or_give_up(&mut writer, &hostile::hello(&run_id, &ticket)).await);
        let welcome = tokio::time::timeout(WAIT, frame::read_frame(&mut reader))
            .await
            .expect("a welcome")
            .unwrap()
            .expect("a frame");
        assert_eq!(welcome.kind, Kind::Control);
        let drain = tokio::spawn(async move {
            let mut sink = vec![0u8; 64 * 1024];
            while matches!(reader.read(&mut sink).await, Ok(n) if n > 0) {}
        });
        let session = registration.connected(WAIT).await.unwrap();
        let files = session.files();
        let asking = tokio::spawn(async move {
            tokio::time::timeout(
                Duration::from_secs(15),
                files.exec(&["true"], &[], None, None, Duration::from_secs(2)),
            )
            .await
        });
        let mut rng = hostile::Rng::new(seed);
        let mut seq = 0u64;
        for _ in 0..300 {
            let frame = hostile::hostile_frame(&mut rng, &mut seq);
            if !hostile::send_or_give_up(&mut writer, &frame).await {
                break;
            }
        }
        let asked = asking.await.unwrap();
        assert!(asked.is_ok(), "seed {seed}: run's request hung");
        drop(writer);
        drain.abort();
        tokio::time::timeout(WAIT, session.close(Duration::from_secs(1)))
            .await
            .unwrap_or_else(|_| panic!("seed {seed}: closing the session hung"));
        // Whatever the sandbox sent, the front kept to the wire's rules.
        assert!(
            !matches!(front.handle().ended(), Some(Ended::Broken(_))),
            "seed {seed}: {:?}",
            front.handle().ended()
        );
        drop(session);
        drop(front);
    }
}

/// The bridge crate's hostile first frames, against a front: each is refused or closed, never
/// welcomed, and the run's ticket still works after them.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hostile_first_frames_are_refused_and_the_front_goes_on() {
    let _one = SERIAL.lock().await;
    let identity = Identity::generate().unwrap();
    let ticket = "t".repeat(64);
    let (front, registration, addr) = bridged_front(
        "run-real",
        Expected::Ticket(Hidden::new(ticket.clone())),
        &identity,
        0,
    )
    .await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    for seed in 0..64u64 {
        let mut rng = hostile::Rng::new(1000 + seed);
        let first = hostile::first_frame(&mut rng, "run-real", &ticket);
        let tls = hostile_connect(addr, &identity).await;
        let (mut reader, mut writer) = tokio::io::split(tls);
        let _ = hostile::send_or_give_up(&mut writer, &first).await;
        let mut buf = vec![0u8; 4096];
        let mut answered = Vec::new();
        loop {
            match tokio::time::timeout(WAIT, reader.read(&mut buf)).await {
                Ok(Ok(0)) | Ok(Err(_)) => break,
                Ok(Ok(n)) => answered.extend_from_slice(&buf[..n]),
                Err(_) => panic!("seed {seed}: the front kept a connection that is not a bridge"),
            }
        }
        let text = String::from_utf8_lossy(&answered);
        assert!(!text.contains("welcome"), "seed {seed}: {text}");
    }
    assert!(front.handle().ended().is_none());
    eventually("every hello came to its end", || {
        registration.admitted_waiting() == 0 && front.handle().bridge_waiting() == (0, 0)
    })
    .await;
    let bridge = spawn_bridge(addr, "run-real", &ticket, &identity);
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session.start(shell("echo real")).await.unwrap();
    assert_eq!(drain(&mut io).await.0, "real\n");
    assert_eq!(finish(&session, bridge).await, 0);
}

/// An answer is taken only for a request that waits for it, or, once, for one that `run` gave
/// up on by its timeout: a slow front is not ended for its late answer, and a front that
/// answers twice, answers a late request twice, or answers a ping with a bridge answer is.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_late_answer_is_taken_once_and_any_other_answer_to_nothing_ends_the_front() {
    let _one = SERIAL.lock().await;
    let ticket = "t".repeat(64);
    for (mode, why) in [
        ("late-once", None),
        ("answer-twice", Some("waits for none")),
        ("late-twice", Some("waits for none")),
        ("answer-ping-id", Some("waits for none")),
    ] {
        let front = SandboxFront::start(&hostile_bridge_front(mode, &ticket))
            .await
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let _registration = front
            .bridge(
                &Identity::generate().unwrap(),
                &host_timing(),
                Peer::Listen(listener),
                HOSTILE_RUN,
                Expected::Ticket(Hidden::new(ticket.clone())),
            )
            .await
            .unwrap();
        let probe = |id| ToFront::Probe {
            id,
            checks: Vec::new(),
            timeout_ms: 1000,
        };
        match mode {
            "answer-ping-id" => {
                assert!(front.handle().ping().await.is_err(), "{mode}");
            }
            "answer-twice" => {
                let answered = front
                    .handle()
                    .ask(probe, Vec::new(), Duration::from_secs(5))
                    .await;
                assert!(answered.is_ok(), "{mode}: {answered:?}");
            }
            _ => {
                let answered = front
                    .handle()
                    .ask(probe, Vec::new(), Duration::from_millis(300))
                    .await;
                assert!(answered.is_err(), "{mode}: the answer was not late");
                assert_eq!(front.handle().abandoned(), 1, "{mode}");
            }
        }
        match why {
            None => {
                // The late answer comes, is dropped, and its id is forgotten; the front lives.
                eventually("the late answer is taken", || {
                    front.handle().abandoned() == 0
                })
                .await;
                tokio::time::sleep(Duration::from_millis(300)).await;
                assert!(front.handle().ended().is_none(), "{mode}");
            }
            Some(why) => {
                let how = tokio::time::timeout(WAIT, front.handle().end_watch().wait())
                    .await
                    .unwrap_or_else(|_| panic!("{mode}: the front was not ended"));
                match how {
                    Ended::Broken(reason) => assert!(reason.contains(why), "{mode}: {reason}"),
                    other => panic!("{mode}: {other}"),
                }
                assert_eq!(front.handle().bridge_waiting(), (0, 0), "{mode}");
                assert_eq!(front.handle().abandoned(), 0, "{mode}");
            }
        }
    }
}

// ----- files and tunnels through a front --------------------------------------------------

/// This process's children that are alive: fronts, readers, bridges.
fn children() -> usize {
    let me = std::process::id().to_string();
    std::fs::read_dir("/proc")
        .unwrap()
        .filter_map(|e| e.ok())
        .filter_map(|e| std::fs::read_to_string(e.path().join("stat")).ok())
        .filter(|stat| {
            // `pid (comm) state ppid …`; the name may hold spaces, the last `)` ends it.
            let Some(rest) = stat.rfind(')').map(|at| &stat[at + 1..]) else {
                return false;
            };
            let mut fields = rest.split_whitespace();
            let state = fields.next();
            let ppid = fields.next();
            ppid == Some(me.as_str()) && state != Some("Z")
        })
        .count()
}

fn reader() -> tinfirm_runner_host::reader::Reader {
    tinfirm_runner_host::reader::Reader::new(BIN).allowing_unconfined(true)
}

fn limits(max_bytes: u64) -> tinfirm_bridge::files::Limits {
    tinfirm_bridge::files::Limits {
        max_files: 100,
        max_bytes,
        max_file_bytes: max_bytes,
    }
}

/// A file of `mib` MiB, written whole (tar packs a sparse file as its holes).
fn big_file(path: &std::path::Path, mib: usize) {
    use std::io::Write as _;
    let mut file = std::fs::File::create(path).unwrap();
    let chunk: Vec<u8> = (0..1024 * 1024).map(|i| (i % 251) as u8).collect();
    for _ in 0..mib {
        file.write_all(&chunk).unwrap();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn files_go_in_and_come_back_through_a_front_and_a_readers_pipe() {
    let _one = SERIAL.lock().await;
    let (front, session, bridge, _, _) = connected("run-front-files").await;
    let host = tempfile::tempdir().unwrap();
    let source = host.path().join("source");
    std::fs::create_dir_all(source.join("a/b")).unwrap();
    std::fs::write(source.join("a/b/in.txt"), "from the host\n").unwrap();
    big_file(&source.join("big.bin"), 64);
    std::fs::create_dir_all(source.join(".cache")).unwrap();
    std::fs::write(source.join(".cache/skip"), "cached").unwrap();
    let sandbox_dir = host.path().join("sandbox/work");
    let dir = sandbox_dir.to_str().unwrap().to_string();
    let files = session.files();
    files
        .put_dir(&source, &dir, vec![".cache".into()], WAIT)
        .await
        .unwrap();
    assert_eq!(
        std::fs::read_to_string(sandbox_dir.join("a/b/in.txt")).unwrap(),
        "from the host\n"
    );
    assert!(!sandbox_dir.join(".cache").exists());
    assert_eq!(
        std::fs::metadata(sandbox_dir.join("big.bin"))
            .unwrap()
            .len(),
        64 << 20
    );
    // Back, through a reader whose stdin is the front's pipe.
    let dest = host.path().join("back");
    std::fs::create_dir_all(&dest).unwrap();
    let into = dest.clone();
    let unpacked = files
        .receive(&dir, Vec::new(), vec!["a".into()], WAIT, move |input| {
            reader().unpack_from(input, &into, &limits(80 << 20), false, WAIT)
        })
        .await
        .unwrap();
    assert_eq!(unpacked.files, 1);
    assert_eq!(
        std::fs::read(dest.join("big.bin")).unwrap(),
        std::fs::read(source.join("big.bin")).unwrap()
    );
    let one = files
        .receive(&dir, vec!["a/b/in.txt".into()], Vec::new(), WAIT, |input| {
            reader().read_one_from(input, 1024, WAIT)
        })
        .await
        .unwrap();
    assert_eq!(
        one,
        tinfirm_bridge::files::ReadOne::File(b"from the host\n".to_vec())
    );
    // A reader that stops early (its archive is over its bounds) fails the get with its own
    // reason; the rest of the stream is read and dropped, and the session goes on.
    let small = host.path().join("small");
    std::fs::create_dir_all(&small).unwrap();
    let refused = files
        .receive(&dir, Vec::new(), Vec::new(), WAIT, move |input| {
            let tight = tinfirm_bridge::files::Limits {
                max_files: 100,
                max_bytes: 1024,
                max_file_bytes: 1 << 30,
            };
            reader().unpack_from(input, &small, &tight, false, WAIT)
        })
        .await;
    assert!(refused.is_err());
    // The reader is gone: the front is this process's one child.
    eventually("no reader stays", || children() == 1).await;
    let out = files
        .exec(&["echo", "still"], &[], None, None, WAIT)
        .await
        .unwrap();
    assert_eq!(out.stdout, "still\n");
    assert_eq!(front.handle().bridge_waiting(), (0, 0));
    assert!(front.handle().ended().is_none());
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_put_or_a_get_cut_by_the_bridge_or_the_front_leaves_nothing_in_run() {
    let _one = SERIAL.lock().await;
    let host = tempfile::tempdir().unwrap();
    let source = host.path().join("source");
    std::fs::create_dir_all(&source).unwrap();
    big_file(&source.join("big.bin"), 256);
    // What a sandbox holds: the same file, in its own directory on this machine.
    let sandbox_dir = host.path().join("sandbox");
    std::fs::create_dir_all(&sandbox_dir).unwrap();
    std::fs::hard_link(source.join("big.bin"), sandbox_dir.join("big.bin")).unwrap();
    let dir = sandbox_dir.to_str().unwrap().to_string();
    // A first front, so that what the runtime keeps for good is there before the count.
    let (front, session, bridge, _, _) = connected("run-front-cut-0").await;
    assert_eq!(finish(&session, bridge).await, 0);
    drop((session, front));
    eventually("no child stays", || children() == 0).await;
    let before = descriptors();
    for (case, cut) in [
        ("get", "bridge"),
        ("get", "front"),
        ("put", "bridge"),
        ("put", "front"),
    ] {
        let run_id = format!("run-front-cut-{case}-{cut}");
        let identity = Identity::generate().unwrap();
        let ticket = "t".repeat(64);
        let (front, registration, addr) = bridged_front(
            &run_id,
            Expected::Ticket(Hidden::new(ticket.clone())),
            &identity,
            0,
        )
        .await;
        registration
            .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
            .await
            .unwrap();
        let mut bridge = bridge_process(addr, &run_id, &ticket, &identity);
        let session = registration.connected(WAIT).await.unwrap();
        let files = session.files();
        let (dir, source, into) = (dir.clone(), source.clone(), host.path().join(&run_id));
        std::fs::create_dir_all(&into).unwrap();
        let transfer = tokio::spawn(async move {
            match case {
                "get" => files
                    .receive(&dir, Vec::new(), Vec::new(), WAIT, move |input| {
                        reader().unpack_from(input, &into, &limits(512 << 20), false, WAIT)
                    })
                    .await
                    .map(drop),
                _ => {
                    files
                        .put_dir(&source, &format!("{dir}-{case}"), Vec::new(), WAIT)
                        .await
                }
            }
        });
        tokio::time::sleep(Duration::from_millis(150)).await;
        assert!(
            !transfer.is_finished(),
            "{case} {cut}: it ended before its cut"
        );
        match cut {
            "bridge" => {
                let _ = bridge.kill().await;
            }
            _ => tinfirm_runner_host::confine::kill_group(front.handle().pgid()),
        }
        let done = tokio::time::timeout(WAIT, transfer)
            .await
            .unwrap_or_else(|_| panic!("{case} {cut}: the transfer hung"))
            .unwrap();
        assert!(done.is_err(), "{case} {cut}: a cut transfer succeeded");
        assert_eq!(front.handle().bridge_waiting().0, 0, "{case} {cut}");
        drop(session);
        drop(front);
        let _ = bridge.kill().await;
        let _ = std::fs::remove_dir_all(format!("{}-{case}", sandbox_dir.display()));
        eventually("no reader, front or bridge stays", || children() == 0).await;
    }
    let deadline = tokio::time::Instant::now() + WAIT;
    while descriptors() > before && tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(
        descriptors() <= before,
        "{before} before, {} now",
        descriptors()
    );
}

/// A broker that refuses every destination, for an egress listener in a front.
struct Refusing;

impl tinfirm_runner_host::egress_proxy::Connector for Refusing {
    fn connect(
        &self,
        _host: String,
        _port: u16,
    ) -> std::pin::Pin<
        Box<
            dyn std::future::Future<
                    Output = Result<
                        tinfirm_runner_host::egress_proxy::Granted,
                        tinfirm_runner_host::egress_proxy::Refusal,
                    >,
                > + Send,
        >,
    > {
        Box::pin(async {
            Err(tinfirm_runner_host::egress_proxy::Refusal {
                status: 403,
                message: "no\n".into(),
                off_allowlist: true,
            })
        })
    }
}

/// An answer's head, however many reads it takes.
async fn read_head(stream: &mut TcpStream) -> String {
    let mut out = Vec::new();
    tokio::time::timeout(WAIT, async {
        let mut buf = [0u8; 4096];
        while !out.windows(4).any(|w| w == b"\r\n\r\n") {
            match stream.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => out.extend_from_slice(&buf[..n]),
            }
        }
    })
    .await
    .expect("an answer");
    String::from_utf8_lossy(&out).into_owned()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_tunnel_reaches_the_fronts_service_and_no_address() {
    use tinfirm_runner_host::front::wire::Service;
    let _one = SERIAL.lock().await;
    let (front, mut session, bridge, _, _) = connected("run-front-tunnel").await;
    let egress = TcpListener::bind("127.0.0.1:0").await.unwrap();
    front
        .listen_egress(Some(egress), "Basic c2VjcmV0".into(), Arc::new(Refusing))
        .await
        .unwrap();
    let any: SocketAddr = "127.0.0.1:0".parse().unwrap();
    // A service the front does not serve is no tunnel.
    let refused = session.open_tunnels(&[(any, Service::Model)], WAIT).await;
    assert!(
        format!("{:#}", refused.unwrap_err()).contains("does not serve"),
        "a tunnel to no service"
    );
    let addrs = session
        .open_tunnels(&[(any, Service::Egress)], WAIT)
        .await
        .unwrap();
    assert_eq!(addrs.len(), 1);
    assert_ne!(addrs[0].port(), 0);
    let mut io = session.start(shell("echo up; sleep 30")).await.unwrap();
    read_until(&mut io, "up").await;
    let connect = "CONNECT example.com:443 HTTP/1.1\r\nhost: example.com:443\r\n";
    for (auth, want) in [
        ("", "HTTP/1.1 407"),
        ("proxy-authorization: Basic c2VjcmV0\r\n", "HTTP/1.1 403"),
    ] {
        let mut stream = TcpStream::connect(addrs[0]).await.unwrap();
        stream
            .write_all(format!("{connect}{auth}\r\n").as_bytes())
            .await
            .unwrap();
        let answer = read_head(&mut stream).await;
        assert!(answer.starts_with(want), "{answer}");
    }
    session.stop(Duration::from_secs(1)).await.unwrap();
    let _ = drain(&mut io).await;
    let code = finish(&session, bridge).await;
    assert_ne!(code, sandbox::EXIT_HOST_GONE);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_tunnel_not_asked_for_and_streams_past_the_bound_are_refused() {
    use tinfirm_bridge::control::Tunnel;
    use tinfirm_bridge::tunnel::MAX_STREAMS;
    use tinfirm_runner_host::front::wire::Service;
    let _one = SERIAL.lock().await;
    let identity = Identity::generate().unwrap();
    let ticket = "t".repeat(64);
    let (front, registration, addr) = bridged_front(
        "run-front-streams",
        Expected::Ticket(Hidden::new(ticket.clone())),
        &identity,
        0,
    )
    .await;
    registration
        .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
        .await
        .unwrap();
    front
        .listen_egress(
            Some(TcpListener::bind("127.0.0.1:0").await.unwrap()),
            "Basic c2VjcmV0".into(),
            Arc::new(Refusing),
        )
        .await
        .unwrap();
    // A sandbox that speaks the bridge's frames itself.
    let tls = hostile_connect(addr, &identity).await;
    let (mut reader, mut writer) = tokio::io::split(tls);
    let payload =
        serde_json::to_vec(&hello("run-front-streams", Some(&ticket), None, Some(0))).unwrap();
    writer
        .write_all(&Frame::new(Kind::Control, frame::STREAM_CONTROL, 0, payload).encode())
        .await
        .unwrap();
    let welcome = frame::read_frame(&mut reader).await.unwrap().unwrap();
    assert!(matches!(
        serde_json::from_slice::<Control>(&welcome.payload).unwrap(),
        Control::Welcome { .. }
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut seq = 0u64;

    let listen: SocketAddr = "127.0.0.1:4100".parse().unwrap();
    let asking = tokio::spawn(async move {
        let addrs = session
            .open_tunnels(&[(listen, Service::Egress)], WAIT)
            .await;
        (session, addrs)
    });
    // The front's controls, until the tunnels are asked.
    let next_control = |frame: Frame| -> Option<Control> {
        (frame.kind == Kind::Control && frame.seq > 0)
            .then(|| serde_json::from_slice(&frame.payload).ok())
            .flatten()
    };
    let id = loop {
        let frame = tokio::time::timeout(WAIT, frame::read_frame(&mut reader))
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        if let Some(Control::Tunnels { id, .. }) = next_control(frame) {
            break id;
        }
    };
    writer
        .write_all(&control(
            &mut seq,
            &Control::Listening {
                id,
                tunnels: vec![Tunnel {
                    id: 1,
                    addr: listen.to_string(),
                }],
            },
        ))
        .await
        .unwrap();
    let (_session, addrs) = asking.await.unwrap();
    assert_eq!(addrs.unwrap(), [listen]);
    // A stream on a tunnel that was not asked for, then one more stream than the bound.
    let first = frame::STREAM_DYNAMIC;
    writer
        .write_all(&control(
            &mut seq,
            &Control::Open {
                stream: first,
                tunnel: 9,
            },
        ))
        .await
        .unwrap();
    for n in 1..=MAX_STREAMS as u32 + 1 {
        writer
            .write_all(&control(
                &mut seq,
                &Control::Open {
                    stream: first + n,
                    tunnel: 1,
                },
            ))
            .await
            .unwrap();
    }
    // TLS holds what was written until it is flushed.
    writer.flush().await.unwrap();
    let past = first + MAX_STREAMS as u32 + 1;
    let mut reset = Vec::new();
    while !(reset.contains(&first) && reset.contains(&past)) {
        let frame = tokio::time::timeout(WAIT, frame::read_frame(&mut reader))
            .await
            .expect("the front resets the streams it does not take")
            .unwrap()
            .expect("the front keeps the connection");
        if let Some(Control::Reset { stream }) = next_control(frame) {
            reset.push(stream);
        }
    }
    // The streams within the bound reached the egress service and stay open.
    assert_eq!(reset.len(), 2, "{reset:?}");
    assert!(front.handle().ended().is_none());
}

/// A frame of the sandbox's, numbered after `seq`.
fn numbered(seq: &mut u64, kind: Kind, stream: u32, payload: Vec<u8>) -> Vec<u8> {
    *seq += 1;
    Frame::new(kind, stream, *seq, payload).encode()
}

/// A control message of the sandbox's, numbered after `seq`.
fn control(seq: &mut u64, control: &Control) -> Vec<u8> {
    numbered(
        seq,
        Kind::Control,
        frame::STREAM_CONTROL,
        serde_json::to_vec(control).unwrap(),
    )
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn each_sandboxs_front_takes_only_its_own_bridge() {
    let _one = SERIAL.lock().await;
    let (identity_a, identity_b) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let (ticket_a, ticket_b) = ("a".repeat(64), "b".repeat(64));
    let (front_a, registration_a, addr_a) = bridged_front(
        "run-a",
        Expected::Ticket(Hidden::new(ticket_a.clone())),
        &identity_a,
        0,
    )
    .await;
    let (front_b, registration_b, addr_b) = bridged_front(
        "run-b",
        Expected::Ticket(Hidden::new(ticket_b.clone())),
        &identity_b,
        0,
    )
    .await;
    for registration in [&registration_a, &registration_b] {
        registration
            .allow_peer(IpAddr::V4(Ipv4Addr::LOCALHOST))
            .await
            .unwrap();
    }
    // Sandbox A's bridge, at B's port: B's certificate is not the one it pins.
    let answer = hello_to(
        addr_b,
        &identity_a,
        hello("run-a", Some(&ticket_a), None, Some(0)),
    )
    .await;
    assert!(answer.is_none(), "{answer:?}");
    // With B's pin, A's run and ticket, or B's run with A's ticket, are no session of B's.
    for (run_id, ticket) in [("run-a", &ticket_a), ("run-b", &ticket_a)] {
        let answer = hello_to(
            addr_b,
            &identity_b,
            hello(run_id, Some(ticket), None, Some(0)),
        )
        .await;
        assert_eq!(refused(answer), Some(RefuseCode::BadTicket), "{run_id}");
    }
    // Each sandbox's own bridge gets its own session.
    let bridge_a = spawn_bridge(addr_a, "run-a", &ticket_a, &identity_a);
    let bridge_b = spawn_bridge(addr_b, "run-b", &ticket_b, &identity_b);
    let session_a = registration_a.connected(WAIT).await.unwrap();
    let session_b = registration_b.connected(WAIT).await.unwrap();
    assert_ne!(session_a.session_key(), session_b.session_key());
    assert_eq!(finish(&session_a, bridge_a).await, 0);
    assert_eq!(finish(&session_b, bridge_b).await, 0);
    drop((front_a, front_b));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_tunnel_port_that_a_program_of_the_sandbox_holds_is_named_with_its_service() {
    use tinfirm_runner_host::front::wire::Service;
    let _one = SERIAL.lock().await;
    let (front, mut session, bridge, _, _) = connected("run-front-taken").await;
    front
        .listen_egress(None, "Basic c2VjcmV0".into(), Arc::new(Refusing))
        .await
        .unwrap();
    // A program of the sandbox's listens there first.
    let taken = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = taken.local_addr().unwrap();
    let error = session
        .open_tunnels(&[(port, Service::Egress)], WAIT)
        .await
        .unwrap_err();
    let error = format!("{error:#}");
    assert!(
        error.contains(&format!("the egress proxy on {port}")),
        "{error}"
    );
    assert!(front.handle().ended().is_none());
    assert_eq!(finish(&session, bridge).await, 0);
}
