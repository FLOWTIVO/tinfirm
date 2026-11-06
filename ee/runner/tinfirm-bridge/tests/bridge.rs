//! The bridge end to end: the host's listener and the sandbox's bridge in one process, over
//! TCP and TLS, with `sh` as the adapter.

use std::net::SocketAddr;
use std::time::Duration;

use tinfirm_bridge::control::{Hidden, ProbeCheck, Start};
use tinfirm_bridge::files::{Limits, ReadOne};
use tinfirm_bridge::host::{
    BridgeServer, Exit, HostIo, HostSession, HostTiming, Output, TunnelTarget,
};
use tinfirm_bridge::link::Timing;
use tinfirm_bridge::sandbox::{self, Config, SandboxTiming};
use tinfirm_bridge::tls;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio::task::JoinHandle;

const WAIT: Duration = Duration::from_secs(20);

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
        gone_after: Duration::from_secs(3),
        tunnel_connect: Duration::from_secs(2),
        dial_retry: Duration::from_millis(50),
    }
}

fn sandbox_timing() -> SandboxTiming {
    SandboxTiming {
        link: link_timing(),
        gone_after: Duration::from_secs(1),
        reclaim_wait: Duration::from_secs(10),
        stop_step: Duration::from_millis(300),
        retry: Duration::from_millis(50),
        connect_timeout: Duration::from_secs(2),
        drain_timeout: Duration::from_secs(2),
        idle_after_exit: Duration::from_secs(5),
        listen_wait: Duration::from_secs(5),
    }
}

async fn server() -> BridgeServer {
    BridgeServer::start("127.0.0.1:0".parse().unwrap(), host_timing())
        .await
        .unwrap()
}

fn config(addr: SocketAddr, run_id: &str, ticket: &str, pin: &str) -> Config {
    Config {
        addr: addr.to_string(),
        listen: None,
        run_id: run_id.to_string(),
        ticket: Hidden::new(ticket),
        pin: tls::parse_pin(pin).unwrap(),
        timing: sandbox_timing(),
        build: "test-build".into(),
    }
}

fn spawn_bridge(config: Config) -> JoinHandle<i32> {
    tokio::spawn(sandbox::run(config))
}

fn shell(script: &str, env: &[(&str, &str)]) -> Start {
    Start {
        argv: vec!["sh".into(), "-c".into(), script.into()],
        env: env
            .iter()
            .map(|(k, v)| (k.to_string(), Hidden::new(*v)))
            .collect(),
        cwd: None,
    }
}

/// Reads output until it closes; returns stdout and stderr.
async fn drain(io: &mut HostIo) -> (String, String) {
    let mut out = Vec::new();
    let mut err = Vec::new();
    loop {
        match tokio::time::timeout(WAIT, io.output.recv()).await {
            Ok(Some(Output::Stdout(b))) => out.extend(b),
            Ok(Some(Output::Stderr(b))) => err.extend(b),
            Ok(None) => break,
            Err(_) => panic!(
                "output did not end; so far: {:?} / {:?}",
                String::from_utf8_lossy(&out),
                String::from_utf8_lossy(&err)
            ),
        }
    }
    (
        String::from_utf8(out).unwrap(),
        String::from_utf8(err).unwrap(),
    )
}

async fn read_until(io: &mut HostIo, needle: &str) -> String {
    let mut out = Vec::new();
    while !String::from_utf8_lossy(&out).contains(needle) {
        match tokio::time::timeout(WAIT, io.output.recv()).await {
            Ok(Some(Output::Stdout(b))) => out.extend(b),
            Ok(Some(Output::Stderr(_))) => {}
            other => panic!(
                "no {needle:?} before {other:?}: {:?}",
                String::from_utf8_lossy(&out)
            ),
        }
    }
    String::from_utf8(out).unwrap()
}

async fn exit(io: &mut HostIo) -> Exit {
    tokio::time::timeout(WAIT, io.exit.wait_for(Option::is_some))
        .await
        .expect("an exit")
        .unwrap()
        .expect("checked")
}

/// Closes the session, as the host does once it has taken the sandbox's files, and returns
/// the bridge's exit code.
async fn finish(session: &HostSession, bridge: JoinHandle<i32>) -> i32 {
    session.close(WAIT).await;
    tokio::time::timeout(WAIT, bridge)
        .await
        .expect("the bridge ends once the session is closed")
        .unwrap()
}

async fn connect(server: &BridgeServer, run_id: &str) -> (HostSession, JoinHandle<i32>) {
    let registration = server.register(run_id).unwrap();
    let bridge = spawn_bridge(config(
        server.local_addr(),
        run_id,
        registration.ticket(),
        server.pin(),
    ));
    let session = registration.connected(WAIT).await.unwrap();
    (session, bridge)
}

#[tokio::test]
async fn the_adapter_runs_with_its_environment_and_its_stdio() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-1").await;
    // What built the bridge's binary, as its `hello` named it (ADR-0101).
    assert_eq!(session.bridge_build(), Some("test-build"));
    let mut io = session
        .start(shell(
            r#"printf 'key=%s\n' "$SECRET"; cat; echo done >&2; exit 3"#,
            &[("SECRET", "sk-ant-test")],
        ))
        .await
        .unwrap();
    io.stdin
        .write_all(b"hello over the bridge\n")
        .await
        .unwrap();
    // Ends the adapter's stdin, as closing the attach does today.
    io.stdin.shutdown().await.unwrap();
    let (out, err) = drain(&mut io).await;
    assert_eq!(out, "key=sk-ant-test\nhello over the bridge\n");
    assert_eq!(err, "done\n");
    assert_eq!(
        *io.exit.borrow(),
        Some(Exit::Exited {
            code: Some(3),
            signal: None
        })
    );
    assert_eq!(finish(&session, bridge).await, 3);
}

#[tokio::test]
async fn a_bridge_with_the_wrong_pin_never_connects() {
    let server = server().await;
    let registration = server.register("run-pin").unwrap();
    let other = tls::Identity::generate().unwrap();
    let bridge = spawn_bridge(config(
        server.local_addr(),
        "run-pin",
        registration.ticket(),
        &other.pin_hex(),
    ));
    assert_eq!(
        tokio::time::timeout(WAIT, bridge).await.unwrap().unwrap(),
        sandbox::EXIT_UNREACHABLE
    );
    assert!(
        registration
            .connected(Duration::from_millis(100))
            .await
            .is_err()
    );
}

#[tokio::test]
async fn a_ticket_works_once_and_a_wrong_one_is_refused() {
    let server = server().await;
    let registration = server.register("run-ticket").unwrap();
    let ticket = registration.ticket().to_string();
    let wrong = spawn_bridge(config(
        server.local_addr(),
        "run-ticket",
        &"0".repeat(64),
        server.pin(),
    ));
    assert_eq!(wrong.await.unwrap(), sandbox::EXIT_REFUSED);
    // The wrong ticket did not use up the right one.
    let first = spawn_bridge(config(
        server.local_addr(),
        "run-ticket",
        &ticket,
        server.pin(),
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let again = spawn_bridge(config(
        server.local_addr(),
        "run-ticket",
        &ticket,
        server.pin(),
    ));
    assert_eq!(again.await.unwrap(), sandbox::EXIT_REFUSED);
    let unknown = spawn_bridge(config(
        server.local_addr(),
        "run-nobody",
        &ticket,
        server.pin(),
    ));
    assert_eq!(unknown.await.unwrap(), sandbox::EXIT_REFUSED);
    let mut io = session.start(shell("echo still here", &[])).await.unwrap();
    assert_eq!(drain(&mut io).await.0, "still here\n");
    assert_eq!(finish(&session, first).await, 0);
}

/// A TCP relay between the bridge and the listener whose connections a test can cut.
async fn relay(target: SocketAddr) -> (SocketAddr, watch::Sender<u32>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let (cut_tx, cut_rx) = watch::channel(0u32);
    tokio::spawn(async move {
        loop {
            let Ok((mut inbound, _)) = listener.accept().await else {
                return;
            };
            let mut cut = cut_rx.clone();
            cut.mark_unchanged();
            tokio::spawn(async move {
                let Ok(mut outbound) = TcpStream::connect(target).await else {
                    return;
                };
                tokio::select! {
                    _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound) => {}
                    _ = cut.changed() => {}
                }
            });
        }
    });
    (addr, cut_tx)
}

#[tokio::test]
async fn output_is_neither_lost_nor_doubled_when_the_connection_drops() {
    let server = server().await;
    let (relay_addr, cut) = relay(server.local_addr()).await;
    let registration = server.register("run-cut").unwrap();
    let bridge = spawn_bridge(config(
        relay_addr,
        "run-cut",
        registration.ticket(),
        server.pin(),
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session
        .start(shell(
            "i=0; while [ $i -lt 300 ]; do echo line$i; i=$((i+1)); sleep 0.005; done",
            &[],
        ))
        .await
        .unwrap();
    let mut out = read_until(&mut io, "line40\n").await;
    cut.send_modify(|n| *n += 1);
    out.push_str(&read_until(&mut io, "line150\n").await);
    cut.send_modify(|n| *n += 1);
    out.push_str(&drain(&mut io).await.0);
    let expected: String = (0..300).map(|i| format!("line{i}\n")).collect();
    assert_eq!(out, expected);
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test]
async fn the_bridge_stops_the_agent_when_the_host_is_gone() {
    let marker = tempfile::tempdir().unwrap();
    let mark = marker.path().join("signal");
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-gone").await;
    let script = format!(
        r#"trap 'echo INT > "{}"; exit 3' INT; echo ready; while :; do sleep 0.05; done"#,
        mark.display()
    );
    let mut io = session.start(shell(&script, &[])).await.unwrap();
    read_until(&mut io, "ready").await;
    // The host goes: no listener to reconnect to, and the session is gone.
    server.shutdown();
    drop(io);
    drop(session);
    let code = tokio::time::timeout(WAIT, bridge).await.unwrap().unwrap();
    assert_eq!(code, sandbox::EXIT_HOST_GONE);
    assert_eq!(std::fs::read_to_string(&mark).unwrap(), "INT\n");
}

#[tokio::test]
async fn stop_terminates_the_adapter_and_kills_it_after_the_grace() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-stop").await;
    let mut io = session
        .start(shell(
            "trap '' TERM; echo ready; while :; do sleep 0.05; done",
            &[],
        ))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    session.stop(Duration::from_secs(1)).await.unwrap();
    assert_eq!(
        exit(&mut io).await,
        Exit::Exited {
            code: None,
            signal: Some(9)
        }
    );
    assert_eq!(finish(&session, bridge).await, 128 + 9);
}

#[tokio::test]
async fn the_host_ends_the_output_when_the_bridge_is_gone() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-lost").await;
    let mut io = session
        .start(shell("echo ready; while :; do sleep 0.05; done", &[]))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    // The sandbox disappears (its bridge and, with it, the adapter).
    bridge.abort();
    let _ = drain(&mut io).await;
    assert_eq!(exit(&mut io).await, Exit::Lost);
    // A request after that fails at once, whatever its timeout.
    let asked = tokio::time::timeout(
        Duration::from_secs(2),
        session
            .files()
            .exec(&["true"], &[], None, None, Duration::from_secs(600)),
    )
    .await
    .expect("a request to a lost sandbox ends at once");
    assert!(format!("{:#}", asked.unwrap_err()).contains("gone"));
}

#[tokio::test]
async fn an_adapter_that_cannot_start_is_reported() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-missing").await;
    let mut io = session
        .start(Start {
            argv: vec!["/nonexistent/adapter".into()],
            env: Vec::new(),
            cwd: None,
        })
        .await
        .unwrap();
    let (_, err) = drain(&mut io).await;
    assert!(err.contains("cannot start the adapter"), "{err}");
    assert_eq!(
        *io.exit.borrow(),
        Some(Exit::Exited {
            code: Some(sandbox::EXIT_START_FAILED),
            signal: None
        })
    );
    assert_eq!(finish(&session, bridge).await, sandbox::EXIT_START_FAILED);
}

#[tokio::test]
async fn a_connection_that_is_not_the_bridge_is_refused() {
    let server = server().await;
    let registration = server.register("run-noise").unwrap();
    // Plain bytes instead of TLS.
    let mut tcp = TcpStream::connect(server.local_addr()).await.unwrap();
    tcp.write_all(b"GET / HTTP/1.1\r\n\r\n").await.unwrap();
    let mut buf = [0u8; 64];
    // The host answers with a TLS alert or closes; it does not hang.
    let _ = tokio::time::timeout(WAIT, tcp.read(&mut buf))
        .await
        .expect("the host ends a connection that is not TLS");
    // The run still waits for its bridge.
    let bridge = spawn_bridge(config(
        server.local_addr(),
        "run-noise",
        registration.ticket(),
        server.pin(),
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session.start(shell("echo fine", &[])).await.unwrap();
    assert_eq!(drain(&mut io).await.0, "fine\n");
    assert_eq!(finish(&session, bridge).await, 0);
}

// ----- tunnels -------------------------------------------------------------------------

/// A listener of the host for the tunnels: it echoes what it reads, answers the end of its
/// input with `total N`, and after a first read of `hold` reads nothing any more.
async fn target() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                return;
            };
            tokio::spawn(async move {
                let mut buf = vec![0u8; 64 * 1024];
                let mut total = 0usize;
                loop {
                    let n = match socket.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
                    };
                    if total == 0 && buf[..n].starts_with(b"hold") {
                        std::future::pending::<()>().await;
                    }
                    total += n;
                    if socket.write_all(&buf[..n]).await.is_err() {
                        return;
                    }
                }
                let _ = socket
                    .write_all(format!("total {total}\n").as_bytes())
                    .await;
            });
        }
    });
    addr
}

fn any_port() -> SocketAddr {
    "127.0.0.1:0".parse().unwrap()
}

/// Sends `data` through `addr`, ends its side, and returns all it read until the end.
async fn exchange(addr: SocketAddr, data: Vec<u8>) -> Vec<u8> {
    paced_exchange(addr, data, Duration::ZERO).await
}

/// [`exchange`], with a pause after every 128 KiB.
async fn paced_exchange(addr: SocketAddr, data: Vec<u8>, pause: Duration) -> Vec<u8> {
    let socket = TcpStream::connect(addr).await.unwrap();
    let (mut reader, mut writer) = socket.into_split();
    let writing = tokio::spawn(async move {
        for chunk in data.chunks(128 * 1024) {
            writer.write_all(chunk).await.unwrap();
            if !pause.is_zero() {
                tokio::time::sleep(pause).await;
            }
        }
        writer.shutdown().await.unwrap();
    });
    let mut got = Vec::new();
    tokio::time::timeout(WAIT, reader.read_to_end(&mut got))
        .await
        .expect("the exchange ended")
        .unwrap();
    writing.await.unwrap();
    got
}

fn pattern(len: usize, seed: u8) -> Vec<u8> {
    (0..len)
        .map(|i| (i as u8).wrapping_mul(31).wrapping_add(seed))
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn tunnels_carry_connections_to_their_targets_both_ways() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-tunnel").await;
    let (echo, other) = (target().await, target().await);
    let listening = session
        .open_tunnels(
            &[
                TunnelTarget {
                    listen: any_port(),
                    target: echo,
                },
                TunnelTarget {
                    listen: any_port(),
                    target: other,
                },
            ],
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(listening.len(), 2);
    assert!(listening.iter().all(|a| a.port() != 0));
    let mut io = session
        .start(shell("echo ready; cat >/dev/null; echo bye", &[]))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    // Several connections at once, larger than the windows, on both tunnels.
    let mut exchanges = Vec::new();
    for i in 0..6u8 {
        let addr = listening[usize::from(i % 2)];
        let data = pattern(1024 * 1024 + usize::from(i) * 1000, i);
        exchanges.push(tokio::spawn(async move {
            let got = exchange(addr, data.clone()).await;
            let mut expected = data.clone();
            expected.extend(format!("total {}\n", data.len()).into_bytes());
            assert!(got == expected, "connection {i}: {} bytes back", got.len());
        }));
    }
    for exchange in exchanges {
        exchange.await.unwrap();
    }
    io.stdin.shutdown().await.unwrap();
    assert_eq!(drain(&mut io).await.0, "bye\n");
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_stalled_connection_holds_back_only_its_own_stream() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-stall").await;
    let listening = session
        .open_tunnels(
            &[TunnelTarget {
                listen: any_port(),
                target: target().await,
            }],
            WAIT,
        )
        .await
        .unwrap();
    let mut io = session
        .start(shell(
            r#"echo ready; while read line; do echo "got $line"; done"#,
            &[],
        ))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    // A connection whose target stops reading: what it writes piles up nowhere but in its
    // own window and the sockets' buffers, and its writer waits.
    let mut stalled = TcpStream::connect(listening[0]).await.unwrap();
    stalled.write_all(b"hold").await.unwrap();
    let flood = tokio::spawn(async move {
        let chunk = vec![0u8; 1024 * 1024];
        for _ in 0..256 {
            if stalled.write_all(&chunk).await.is_err() {
                return false;
            }
        }
        true
    });
    tokio::time::sleep(Duration::from_millis(500)).await;
    // Another connection, and the adapter's stdio, go on.
    assert_eq!(
        exchange(listening[0], b"ping".to_vec()).await,
        b"pingtotal 4\n"
    );
    io.stdin.write_all(b"one\n").await.unwrap();
    read_until(&mut io, "got one").await;
    assert!(
        !flood.is_finished(),
        "256 MiB went nowhere: no backpressure"
    );
    flood.abort();
    io.stdin.shutdown().await.unwrap();
    let _ = drain(&mut io).await;
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_connection_whose_listener_is_gone_is_closed() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-down").await;
    // A port that nobody listens on any more.
    let gone = TcpListener::bind("127.0.0.1:0")
        .await
        .unwrap()
        .local_addr()
        .unwrap();
    let listening = session
        .open_tunnels(
            &[TunnelTarget {
                listen: any_port(),
                target: gone,
            }],
            WAIT,
        )
        .await
        .unwrap();
    let mut io = session
        .start(shell("echo ready; cat >/dev/null", &[]))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    let mut socket = TcpStream::connect(listening[0]).await.unwrap();
    let mut buf = [0u8; 16];
    let read = tokio::time::timeout(WAIT, socket.read(&mut buf))
        .await
        .expect("the connection is closed, not left hanging");
    assert!(matches!(read, Ok(0) | Err(_)), "{read:?}");
    io.stdin.shutdown().await.unwrap();
    let _ = drain(&mut io).await;
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_tunnels_bytes_are_neither_lost_nor_doubled_when_the_connection_drops() {
    let server = server().await;
    let (relay_addr, cut) = relay(server.local_addr()).await;
    let registration = server.register("run-tunnel-cut").unwrap();
    let bridge = spawn_bridge(config(
        relay_addr,
        "run-tunnel-cut",
        registration.ticket(),
        server.pin(),
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let listening = session
        .open_tunnels(
            &[TunnelTarget {
                listen: any_port(),
                target: target().await,
            }],
            WAIT,
        )
        .await
        .unwrap();
    let mut io = session
        .start(shell("echo ready; cat >/dev/null", &[]))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    let data = pattern(8 * 1024 * 1024, 9);
    let addr = listening[0];
    let sent = data.clone();
    // 64 pieces 10 ms apart: the cuts fall in the middle of it.
    let exchange =
        tokio::spawn(async move { paced_exchange(addr, sent, Duration::from_millis(10)).await });
    for _ in 0..3 {
        tokio::time::sleep(Duration::from_millis(150)).await;
        assert!(
            !exchange.is_finished(),
            "the exchange ended before the cuts"
        );
        cut.send_modify(|n| *n += 1);
    }
    let got = exchange.await.unwrap();
    let mut expected = data.clone();
    expected.extend(format!("total {}\n", data.len()).into_bytes());
    assert_eq!(got.len(), expected.len());
    assert!(got == expected, "the bytes differ");
    io.stdin.shutdown().await.unwrap();
    let _ = drain(&mut io).await;
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test]
async fn a_tunnel_the_sandbox_cannot_listen_for_fails_before_the_start() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-taken").await;
    let taken = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let error = session
        .open_tunnels(
            &[TunnelTarget {
                listen: taken.local_addr().unwrap(),
                target: target().await,
            }],
            WAIT,
        )
        .await
        .unwrap_err();
    assert!(format!("{error:#}").contains("cannot listen"), "{error:#}");
    // The run goes on without them, or ends; here it starts.
    let mut io = session.start(shell("echo fine", &[])).await.unwrap();
    assert_eq!(drain(&mut io).await.0, "fine\n");
    assert_eq!(finish(&session, bridge).await, 0);
}

// ----- files and commands --------------------------------------------------------------

fn limits() -> Limits {
    Limits {
        max_files: 1000,
        max_bytes: 64 << 20,
        max_file_bytes: 16 << 20,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn files_go_in_before_the_start_and_come_back_after_the_adapter() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-files").await;
    let host = tempfile::tempdir().unwrap();
    let source = host.path().join("source");
    std::fs::create_dir_all(source.join("a/b")).unwrap();
    std::fs::write(source.join("a/b/in.txt"), "from the host\n").unwrap();
    std::fs::write(source.join("big.bin"), pattern(3 * 1024 * 1024, 5)).unwrap();
    std::fs::create_dir_all(source.join(".cache")).unwrap();
    std::fs::write(source.join(".cache/skip"), "cached").unwrap();
    // The sandbox's directory, here on the same machine.
    let sandbox_dir = host.path().join("sandbox/work");
    let files = session.files();
    files
        .put_dir(
            &source,
            sandbox_dir.to_str().unwrap(),
            vec![".cache".into()],
            WAIT,
        )
        .await
        .unwrap();
    assert!(!sandbox_dir.join(".cache").exists());
    let script = format!(
        r#"cd "{}" && cat a/b/in.txt && echo new > c.txt && mkdir -p d && echo deep > d/e.txt && ln -s /etc/passwd link && rm big.bin"#,
        sandbox_dir.display()
    );
    let mut io = session.start(shell(&script, &[])).await.unwrap();
    assert_eq!(drain(&mut io).await.0, "from the host\n");
    assert_eq!(
        exit(&mut io).await,
        Exit::Exited {
            code: Some(0),
            signal: None
        }
    );
    // After the adapter, the bridge stays for the host to take the files.
    let dest = host.path().join("back");
    let unpacked = files
        .get_dir(
            sandbox_dir.to_str().unwrap(),
            Vec::new(),
            vec!["a".into()],
            &dest,
            limits(),
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(unpacked.files, 2);
    assert_eq!(unpacked.left_out, ["link"]);
    assert_eq!(
        std::fs::read_to_string(dest.join("c.txt")).unwrap(),
        "new\n"
    );
    assert_eq!(
        std::fs::read_to_string(dest.join("d/e.txt")).unwrap(),
        "deep\n"
    );
    assert!(!dest.join("a").exists() && !dest.join("big.bin").exists());
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_host_reads_one_file_and_runs_commands_in_the_sandbox_while_the_adapter_runs() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-read").await;
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().to_str().unwrap().to_string();
    std::fs::write(dir.path().join("report.md"), "# Report\n").unwrap();
    std::fs::write(dir.path().join("large"), vec![0u8; 4096]).unwrap();
    std::os::unix::fs::symlink("/etc/passwd", dir.path().join("link")).unwrap();
    let mut io = session
        .start(shell("echo ready; cat >/dev/null", &[]))
        .await
        .unwrap();
    read_until(&mut io, "ready").await;
    let files = session.files();
    assert_eq!(
        files
            .read_file(&root, "report.md", 1024, WAIT)
            .await
            .unwrap(),
        ReadOne::File(b"# Report\n".to_vec())
    );
    assert_eq!(
        files.read_file(&root, "missing", 1024, WAIT).await.unwrap(),
        ReadOne::Missing
    );
    assert_eq!(
        files.read_file(&root, "link", 1024, WAIT).await.unwrap(),
        ReadOne::NotRegular
    );
    assert_eq!(
        files.read_file(&root, "large", 1024, WAIT).await.unwrap(),
        ReadOne::TooLarge
    );
    assert!(files.read_file(&root, "../etc", 1024, WAIT).await.is_err());
    // A command with its environment, its input and its working directory.
    let out = files
        .exec(
            &[
                "sh",
                "-c",
                r#"read line; echo "$line $GREETING $(pwd)"; echo oops >&2; exit 4"#,
            ],
            &[("GREETING", "hello")],
            Some(&root),
            Some("input\n".into()),
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(out.code, Some(4));
    assert!(!out.success());
    let resolved = std::fs::canonicalize(dir.path()).unwrap();
    assert_eq!(
        out.stdout.trim(),
        format!("input hello {}", resolved.display())
    );
    assert_eq!(out.stderr, "oops\n");
    let slow = files
        .exec(&["sleep", "30"], &[], None, None, Duration::from_secs(1))
        .await
        .unwrap_err();
    assert!(
        format!("{slow:#}").contains("did not end within 1 s"),
        "{slow:#}"
    );
    let missing = files
        .exec(&["/nonexistent/program"], &[], None, None, WAIT)
        .await
        .unwrap_err();
    assert!(format!("{missing:#}").contains("cannot run"), "{missing:#}");
    // The bridge's own settings never reach a command.
    let env = files
        .exec(
            &["sh", "-c", "env | grep -c TINFIRM_BRIDGE_ || true"],
            &[],
            None,
            None,
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(env.stdout.trim(), "0");
    io.stdin.shutdown().await.unwrap();
    let _ = drain(&mut io).await;
    assert_eq!(finish(&session, bridge).await, 0);
    // Once the session is over, the files are gone with it.
    assert!(
        files
            .read_file(&root, "report.md", 1024, WAIT)
            .await
            .is_err()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_get_past_the_hosts_limits_fails_and_the_session_goes_on() {
    let server = server().await;
    let (mut session, bridge) = connect(&server, "run-limits").await;
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().to_str().unwrap().to_string();
    for i in 0..5 {
        std::fs::write(dir.path().join(format!("f{i}")), pattern(256 * 1024, i)).unwrap();
    }
    let mut io = session.start(shell("exit 0", &[])).await.unwrap();
    let _ = drain(&mut io).await;
    let files = session.files();
    let small = Limits {
        max_files: 10,
        max_bytes: 600 * 1024,
        max_file_bytes: 1 << 20,
    };
    let error = files
        .get_dir(
            &root,
            Vec::new(),
            Vec::new(),
            &dir.path().join("out1"),
            small,
            WAIT,
        )
        .await
        .unwrap_err();
    assert!(format!("{error:#}").contains("more than"), "{error:#}");
    let few = Limits {
        max_files: 2,
        ..small
    };
    assert!(
        files
            .get_dir(
                &root,
                Vec::new(),
                Vec::new(),
                &dir.path().join("out2"),
                few,
                WAIT
            )
            .await
            .is_err()
    );
    // The stream that failed is gone; the next request works.
    let unpacked = files
        .get_dir(
            &root,
            vec!["f1".into()],
            Vec::new(),
            &dir.path().join("out3"),
            limits(),
            WAIT,
        )
        .await
        .unwrap();
    assert_eq!(unpacked.files, 1);
    assert_eq!(
        std::fs::read(dir.path().join("out3/f1")).unwrap(),
        pattern(256 * 1024, 1)
    );
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn files_are_neither_lost_nor_doubled_when_the_connection_drops() {
    let server = server().await;
    let (relay_addr, cut) = relay(server.local_addr()).await;
    let registration = server.register("run-files-cut").unwrap();
    let bridge = spawn_bridge(config(
        relay_addr,
        "run-files-cut",
        registration.ticket(),
        server.pin(),
    ));
    let session = registration.connected(WAIT).await.unwrap();
    let dir = tempfile::tempdir().unwrap();
    let source = dir.path().join("source");
    std::fs::create_dir_all(&source).unwrap();
    for i in 0..16u8 {
        std::fs::write(source.join(format!("part{i}")), pattern(1024 * 1024, i)).unwrap();
    }
    let files = session.files();
    let target = dir.path().join("sandbox");
    let put = {
        let (files, source, target) = (files.clone(), source.clone(), target.clone());
        tokio::spawn(async move {
            files
                .put_dir(&source, target.to_str().unwrap(), Vec::new(), WAIT)
                .await
        })
    };
    for _ in 0..2 {
        tokio::time::sleep(Duration::from_millis(40)).await;
        cut.send_modify(|n| *n += 1);
    }
    put.await.unwrap().unwrap();
    let back = dir.path().join("back");
    files
        .get_dir(
            target.to_str().unwrap(),
            Vec::new(),
            Vec::new(),
            &back,
            limits(),
            WAIT,
        )
        .await
        .unwrap();
    for i in 0..16u8 {
        assert!(
            std::fs::read(back.join(format!("part{i}"))).unwrap() == pattern(1024 * 1024, i),
            "part{i} differs"
        );
    }
    session.close(WAIT).await;
    assert_eq!(
        tokio::time::timeout(WAIT, bridge).await.unwrap().unwrap(),
        0
    );
}

// ----- a VM per run: the host dials in -------------------------------------------------

/// A bridge that listens on a free port for the host to dial in.
async fn listening_bridge(
    server: &BridgeServer,
    run_id: &str,
    ticket: &str,
    pin: &str,
) -> (SocketAddr, Config) {
    let addr = {
        let free = TcpListener::bind("127.0.0.1:0").await.unwrap();
        free.local_addr().unwrap()
    };
    let mut config = config(server.local_addr(), run_id, ticket, pin);
    config.addr = String::new();
    config.listen = Some(addr);
    (addr, config)
}

#[tokio::test]
async fn the_host_dials_a_bridge_that_listens_and_dials_again_after_a_cut() {
    let server = server().await;
    let registration = server.register("run-dial").unwrap();
    let (addr, config) =
        listening_bridge(&server, "run-dial", registration.ticket(), server.pin()).await;
    let bridge = spawn_bridge(config);
    let (relay_addr, cut) = relay(addr).await;
    registration.dial(relay_addr.to_string());
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session
        .start(shell(
            "i=0; while [ $i -lt 300 ]; do echo line$i; i=$((i+1)); sleep 0.005; done",
            &[],
        ))
        .await
        .unwrap();
    let mut out = read_until(&mut io, "line40\n").await;
    cut.send_modify(|n| *n += 1);
    out.push_str(&read_until(&mut io, "line150\n").await);
    cut.send_modify(|n| *n += 1);
    out.push_str(&drain(&mut io).await.0);
    let expected: String = (0..300).map(|i| format!("line{i}\n")).collect();
    assert_eq!(out, expected);
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test]
async fn a_bridge_that_listens_takes_only_the_host_with_its_pin() {
    let server = server().await;
    let registration = server.register("run-stranger").unwrap();
    let (addr, config) =
        listening_bridge(&server, "run-stranger", registration.ticket(), server.pin()).await;
    let bridge = spawn_bridge(config);
    // Plain bytes, then another host with the same run and ticket but another key: the
    // bridge refuses both and waits on.
    let stranger = self::server().await;
    let theirs = stranger.register("run-stranger").unwrap();
    theirs.dial(addr.to_string());
    let mut noise = loop {
        match TcpStream::connect(addr).await {
            Ok(tcp) => break tcp,
            Err(_) => tokio::time::sleep(Duration::from_millis(20)).await,
        }
    };
    noise.write_all(b"GET / HTTP/1.1\r\n\r\n").await.unwrap();
    assert!(theirs.connected(Duration::from_millis(500)).await.is_err());
    registration.dial(addr.to_string());
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session.start(shell("echo ours", &[])).await.unwrap();
    assert_eq!(drain(&mut io).await.0, "ours\n");
    assert_eq!(finish(&session, bridge).await, 0);
}

#[tokio::test]
async fn a_bridge_that_listens_gives_up_when_the_host_never_comes() {
    let server = server().await;
    let registration = server.register("run-alone").unwrap();
    let (_, mut config) =
        listening_bridge(&server, "run-alone", registration.ticket(), server.pin()).await;
    config.timing.listen_wait = Duration::from_millis(300);
    config.timing.connect_timeout = Duration::from_millis(100);
    assert_eq!(
        tokio::time::timeout(WAIT, spawn_bridge(config))
            .await
            .unwrap()
            .unwrap(),
        sandbox::EXIT_UNREACHABLE
    );
}

#[tokio::test]
async fn the_probe_reports_only_what_the_sandbox_reaches() {
    let server = server().await;
    let (session, bridge) = connect(&server, "run-probe").await;
    let open = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let open_addr = open.local_addr().unwrap().to_string();
    let closed = {
        let gone = TcpListener::bind("127.0.0.1:0").await.unwrap();
        gone.local_addr().unwrap().to_string()
    };
    let checks = vec![
        ProbeCheck::Tcp {
            addr: closed.clone(),
        },
        ProbeCheck::Tcp {
            addr: open_addr.clone(),
        },
        ProbeCheck::Dns {
            name: "nothing.invalid".into(),
        },
    ];
    let reached = session.files().probe(checks, WAIT).await.unwrap();
    assert_eq!(reached, [format!("a connection to {open_addr}")]);
    assert_eq!(finish(&session, bridge).await, 0);
}

/// Host A "crashes": its listener goes, then its connection, without a word to the bridge;
/// host B starts on A's port with A's identity and the session's key, as a runner host that
/// restarted does (ADR-0101). `wait` is how long nobody is there in between.
async fn restart(
    identity: &tls::Identity,
    a: BridgeServer,
    session: HostSession,
    run_id: &str,
    wait: Duration,
) -> HostSession {
    let addr = a.local_addr();
    let key = session.session_key().clone();
    a.shutdown();
    drop(session);
    tokio::time::sleep(wait).await;
    let started = loop {
        match BridgeServer::start_with(
            addr,
            host_timing(),
            identity,
            vec![(run_id.to_string(), key.clone())],
        )
        .await
        {
            Ok(started) => break started,
            // A's listener may hold the port a moment longer.
            Err(_) => tokio::time::sleep(Duration::from_millis(20)).await,
        }
    };
    let (b, mut registrations) = started;
    let session = registrations.pop().unwrap().connected(WAIT).await.unwrap();
    // B is dropped with this function; its session goes on.
    std::mem::forget(b);
    session
}

async fn workspace_file(session: &HostSession, path: &str) -> String {
    let out = session
        .files()
        .exec(&["cat", path], &[], None, None, WAIT)
        .await
        .unwrap();
    assert!(out.success(), "{}", out.stderr);
    out.stdout
}

#[tokio::test]
async fn a_host_that_restarted_takes_the_session_back_and_stops_the_agent() {
    let identity = tls::Identity::generate().unwrap();
    let (a, _) = BridgeServer::start_with(
        "127.0.0.1:0".parse().unwrap(),
        host_timing(),
        &identity,
        Vec::new(),
    )
    .await
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let work = dir.path().join("work.txt");
    let registration = a.register("run-r").unwrap();
    let bridge = spawn_bridge(config(
        a.local_addr(),
        "run-r",
        registration.ticket(),
        a.pin(),
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session
        .start(shell(
            r#"echo done-so-far > "$WORK"; echo started; exec sleep 60"#,
            &[("WORK", &work.to_string_lossy())],
        ))
        .await
        .unwrap();
    read_until(&mut io, "started").await;

    // Back within the bridge's `gone_after`: the agent still runs, and stops for host B.
    let mut session = restart(&identity, a, session, "run-r", Duration::ZERO).await;
    assert!(session.reclaim(WAIT).await.unwrap(), "the agent still ran");
    assert!(matches!(
        *session.exit().borrow(),
        Some(Exit::Exited { .. })
    ));
    assert_eq!(
        workspace_file(&session, &work.to_string_lossy()).await,
        "done-so-far\n"
    );
    session.close(WAIT).await;
    let code = tokio::time::timeout(WAIT, bridge).await.unwrap().unwrap();
    assert_ne!(code, sandbox::EXIT_HOST_GONE);
}

#[tokio::test]
async fn an_agent_whose_host_is_gone_stops_and_its_sandbox_waits_for_the_host_that_restarted() {
    let identity = tls::Identity::generate().unwrap();
    let (a, _) = BridgeServer::start_with(
        "127.0.0.1:0".parse().unwrap(),
        host_timing(),
        &identity,
        Vec::new(),
    )
    .await
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let work = dir.path().join("work.txt");
    let registration = a.register("run-o").unwrap();
    let bridge = spawn_bridge(config(
        a.local_addr(),
        "run-o",
        registration.ticket(),
        a.pin(),
    ));
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session
        .start(shell(
            r#"trap 'echo stopped >> "$WORK"; exit 3' INT; echo kept > "$WORK"; echo started; while :; do sleep 0.05; done"#,
            &[("WORK", &work.to_string_lossy())],
        ))
        .await
        .unwrap();
    read_until(&mut io, "started").await;

    // Gone for longer than the bridge's `gone_after` (1 s): the bridge stops the agent itself,
    // and keeps the sandbox for the host that comes back.
    let mut session = restart(&identity, a, session, "run-o", Duration::from_secs(3)).await;
    assert!(
        !session.reclaim(WAIT).await.unwrap(),
        "the agent was stopped before"
    );
    assert_eq!(
        workspace_file(&session, &work.to_string_lossy()).await,
        "kept\nstopped\n"
    );
    session.close(WAIT).await;
    let code = tokio::time::timeout(WAIT, bridge).await.unwrap().unwrap();
    assert_eq!(code, 3);
}

#[tokio::test]
async fn a_host_that_restarted_without_the_session_lets_the_sandbox_go() {
    let identity = tls::Identity::generate().unwrap();
    let (a, _) = BridgeServer::start_with(
        "127.0.0.1:0".parse().unwrap(),
        host_timing(),
        &identity,
        Vec::new(),
    )
    .await
    .unwrap();
    let addr = a.local_addr();
    let registration = a.register("run-f").unwrap();
    let bridge = spawn_bridge(config(addr, "run-f", registration.ticket(), a.pin()));
    let mut session = registration.connected(WAIT).await.unwrap();
    let mut io = session
        .start(shell("echo started; exec sleep 60", &[]))
        .await
        .unwrap();
    read_until(&mut io, "started").await;
    a.shutdown();
    drop(session);
    // Host B knows nothing of the run: the bridge is refused, stops the agent and ends.
    let _b = loop {
        match BridgeServer::start_with(addr, host_timing(), &identity, Vec::new()).await {
            Ok((b, _)) => break b,
            Err(_) => tokio::time::sleep(Duration::from_millis(20)).await,
        }
    };
    let code = tokio::time::timeout(WAIT, bridge).await.unwrap().unwrap();
    assert_eq!(code, sandbox::EXIT_HOST_GONE);
}
