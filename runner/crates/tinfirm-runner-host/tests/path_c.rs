//! The test of path C (ADR-0112 decision 8): a flaw in the code that parses what a sandbox
//! sends gives its attacker code execution in that sandbox's front, the confined process that
//! does this parsing. From there it must find no credential of the host's and reach no other
//! run, and `run` must hand its fronts no byte of a credential.
//!
//! Two runs are in progress on one host over the bridge, each with its own front, with the
//! local test executor (the sandboxes are processes on this machine), the fake server, a fake
//! model gateway over TLS, an echo server for the egress proxy and a git server per
//! repository. The
//! host holds canaries, random strings that no secret scanner reads as real: its own token,
//! each run's git token and the model gateway's key. Both runs fetch through the git proxy, ask
//! the model proxy and connect through the egress proxy, and then wait.
//!
//! The attacker in run 2's front is a probe that `run`'s own code starts as it starts a
//! sandbox's front (`SandboxFront::start`: this binary's `front` with its job, an empty
//! environment, a process group of its own, the control channel on its stdin, nothing on its
//! stdout, a pipe to `run` on its stderr), and that gets over that channel, with
//! their messages, copies of the descriptors that `run` gave run 2's front for its run: its
//! bridge's listener and the adapter's stdin, stdout and stderr (`front::inside`). A tap on
//! what `run` writes to every front (`front::tap`) records each frame and each byte that `run`
//! writes on a descriptor it fills for a front.
//!
//! What this test is not: a test on real machines. It runs with stand-in agents, a fake server
//! and fake upstreams on one machine, as `run`'s user; the fronts and the probe run with
//! Landlock and seccomp as this kernel applies them. Here that user is root: whatever the
//! probe is refused, it is refused by the confinement and not by file permissions or by not
//! being dumpable.

#![cfg(target_os = "linux")]

mod common;

use std::collections::BTreeMap;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use base64::Engine;
use common::git_http::GitHttpServer;
use common::*;
use serde_json::{Value, json};
use tinfirm_runner_host::front::handle::{Ended, FrontCommand};
use tinfirm_runner_host::front::inside::{Attempts, Inside};
use tinfirm_runner_host::front::sandbox::SandboxFront;
use tinfirm_runner_host::front::tap::{FrontTap, Tapped};
use tinfirm_runner_host::front::wire::ToFront;
use tinfirm_runner_host::front::{self, ProbeJob};
use tinfirm_runner_host::secret_hashes::{HashedMatcher, SecretHashes};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use uuid::Uuid;

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");

/// A canary: random, made at run time, and in a shape that no secret scanner reads as real.
fn canary(what: &str) -> String {
    format!("canary_TEST_ONLY_{what}_{}", Uuid::new_v4().simple())
}

/// What the fake gateway answers every model request with.
const GATEWAY_ANSWER: &str = r#"{"id":"msg_path_c","type":"message","role":"assistant","content":[{"type":"text","text":"the path C gateway answers"}]}"#;

/// A model gateway on loopback, over TLS with the certificate `cert` and its key `key` (DER),
/// that answers every request with [`GATEWAY_ANSWER`] and keeps each request's head.
async fn gateway(cert: Vec<u8>, key: Vec<u8>) -> (SocketAddr, Arc<Mutex<Vec<String>>>) {
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    let config = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_no_client_auth()
    .with_single_cert(
        vec![CertificateDer::from(cert)],
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
    )
    .unwrap();
    let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(config));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Mutex::new(Vec::new()));
    let kept = seen.clone();
    tokio::spawn(async move {
        while let Ok((tcp, _)) = listener.accept().await {
            let kept = kept.clone();
            let acceptor = acceptor.clone();
            tokio::spawn(async move {
                let Ok(mut stream) = acceptor.accept(tcp).await else {
                    return;
                };
                let mut bytes = Vec::new();
                let mut buffer = [0u8; 4096];
                let head_end = loop {
                    match stream.read(&mut buffer).await {
                        Ok(0) | Err(_) => return,
                        Ok(read) => bytes.extend_from_slice(&buffer[..read]),
                    }
                    if let Some(at) = bytes.windows(4).position(|w| w == b"\r\n\r\n") {
                        break at + 4;
                    }
                };
                let head = String::from_utf8_lossy(&bytes[..head_end]).to_string();
                let length = head
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse::<usize>().ok())?
                    })
                    .unwrap_or(0);
                while bytes.len() < head_end + length {
                    match stream.read(&mut buffer).await {
                        Ok(0) | Err(_) => break,
                        Ok(read) => bytes.extend_from_slice(&buffer[..read]),
                    }
                }
                kept.lock().unwrap().push(head);
                let answer = format!(
                    "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{GATEWAY_ANSWER}",
                    GATEWAY_ANSWER.len()
                );
                let _ = stream.write_all(answer.as_bytes()).await;
                let _ = stream.shutdown().await;
            });
        }
    });
    (addr, seen)
}

/// Echoes one line per connection, on loopback: the egress proxy's destination.
async fn echo() -> SocketAddr {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = [0u8; 1024];
                if let Ok(n) = socket.read(&mut buf).await {
                    let _ = socket.write_all(&buf[..n]).await;
                }
            });
        }
    });
    addr
}

/// The `Authorization` that the run's git token makes, as the host sends it upstream.
fn git_authorization(token: &str) -> String {
    let basic = base64::engine::general_purpose::STANDARD.encode(format!("x-access-token:{token}"));
    format!("Basic {basic}")
}

/// A host over the bridge whose token is `host_token`, whose fronts `tap` records, with an
/// egress proxy that allows `echo` and a model proxy that may reach a gateway on loopback.
async fn tapped_host(
    server: &FakeServer,
    tap: &FrontTap,
    host_token: &str,
    echo: SocketAddr,
) -> TestHost {
    use tinfirm_runner_host::config::HostConfig;
    use tinfirm_runner_host::egress_proxy::Rule;
    let tap = tap.clone();
    let token = host_token.to_string();
    let url = server.url.clone();
    let setup = HostSetup {
        egress: Some(EgressSettings {
            allow: vec![Rule::parse(&format!("localhost:{}", echo.port())).unwrap()],
            allow_private: false,
            allow_loopback: true,
        }),
        model_proxy: ModelProxySettings {
            allow_loopback: true,
            allow_http: true,
            ..ModelProxySettings::default()
        },
        options: Some(Box::new(move |opts| {
            // The host's token is a canary: written as `login` writes it, before the host
            // reads its configuration.
            HostConfig::new(url, tinfirm_protocol::Secret::new(token))
                .save(&opts.paths)
                .unwrap();
            opts.bridge
                .as_mut()
                .expect("a host over the bridge")
                .front
                .tap = Some(tap);
        })),
        ..HostSetup::bridged()
    };
    start_host(&server.url, setup).await
}

/// Reads frames until each run's agent has said `needle`; the runs' agent texts.
async fn until_each_says(conn: &mut Conn, runs: &[Uuid], needle: &str) -> Vec<String> {
    let mut said = vec![String::new(); runs.len()];
    loop {
        if said.iter().all(|text| text.contains(needle)) {
            return said;
        }
        let frame = conn.next().await.expect("the connection is open");
        for (run, text) in runs.iter().zip(said.iter_mut()) {
            if frame["runId"] != run.to_string() {
                continue;
            }
            assert!(
                frame["type"] != "run.finished",
                "run {run} finished before its agent said {needle:?}: {text}\n{frame:#}"
            );
            if frame["eventType"] == "AGENT_MESSAGE" {
                text.push_str(frame["payload"]["text"].as_str().unwrap_or_default());
            }
        }
    }
}

/// Reads frames until every run has finished; their `run.finished` frames.
async fn until_each_finished(conn: &mut Conn, runs: &[Uuid]) -> Vec<Value> {
    let mut finished: Vec<Option<Value>> = vec![None; runs.len()];
    while finished.iter().any(Option::is_none) {
        let frame = conn.next().await.expect("the connection is open");
        if frame["type"] != "run.finished" {
            continue;
        }
        for (run, slot) in runs.iter().zip(finished.iter_mut()) {
            if frame["runId"] == run.to_string() {
                *slot = Some(frame.clone());
            }
        }
    }
    finished.into_iter().map(Option::unwrap).collect()
}

/// Every file and directory under `root`, the objects of a git repository left out (its
/// directory is kept).
fn every_path(root: &Path, into: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(root) else {
        return;
    };
    for entry in entries.filter_map(Result::ok) {
        let path = entry.path();
        into.push(path.clone());
        let Ok(kind) = entry.file_type() else {
            continue;
        };
        if kind.is_dir() && !path.ends_with(".git/objects") {
            every_path(&path, into);
        }
    }
}

/// The processes the host started (the fronts and the sandboxes' bridges and agents), with
/// their descendants: every process under `parent` but those of `except`'s group.
fn descendants(parent: u32, except: u32, into: &mut Vec<u32>) {
    for entry in std::fs::read_dir("/proc").unwrap().filter_map(Result::ok) {
        let Ok(pid) = entry.file_name().to_string_lossy().parse::<u32>() else {
            continue;
        };
        let Ok(stat) = std::fs::read_to_string(entry.path().join("stat")) else {
            continue;
        };
        let fields: Vec<&str> = stat
            .rfind(')')
            .map(|at| stat[at + 1..].split_whitespace().collect())
            .unwrap_or_default();
        let (Some(ppid), Some(pgrp)) = (
            fields.get(1).and_then(|f| f.parse::<u32>().ok()),
            fields.get(2).and_then(|f| f.parse::<u32>().ok()),
        ) else {
            continue;
        };
        if ppid == parent && pgrp != except && !into.contains(&pid) {
            into.push(pid);
            descendants(pid, except, into);
        }
    }
}

/// The address of a TCP listener among `kept`'s descriptors.
fn listeners(kept: &[(ToFront, Vec<std::os::fd::OwnedFd>)]) -> Vec<SocketAddr> {
    use rustix::net::{AddressFamily, sockopt};
    kept.iter()
        .flat_map(|(_, fds)| fds)
        .filter(|fd| {
            matches!(
                sockopt::socket_domain(fd),
                Ok(AddressFamily::INET | AddressFamily::INET6)
            ) && sockopt::socket_acceptconn(fd).unwrap_or(false)
        })
        .filter_map(|fd| {
            std::net::TcpListener::from(fd.try_clone().unwrap())
                .local_addr()
                .ok()
        })
        .collect()
}

/// `host:port` after `label` in an agent's report.
fn reported_addr(text: &str, label: &str) -> Option<SocketAddr> {
    text.split(label)
        .nth(1)?
        .split_whitespace()
        .next()?
        .parse()
        .ok()
}

/// Every canary form that the redactor matches (raw, URL-encoded, base64 at each alignment).
fn matcher(canaries: &[&str]) -> HashedMatcher {
    let mut hashes = SecretHashes::new();
    for canary in canaries {
        hashes.add(canary);
    }
    HashedMatcher::new(&hashes).unwrap()
}

/// Where in what `run` sent its fronts a form of a canary is: `(front's process group,
/// "frames" or the stream's number)`.
fn found_in(fronts: &[Tapped], matcher: &HashedMatcher) -> Vec<(u32, String)> {
    let mut found = Vec::new();
    for front in fronts {
        if matcher.contains(&front.frames) {
            found.push((front.pgid, "frames".to_string()));
        }
        for (i, stream) in front.streams.iter().enumerate() {
            if matcher.contains(stream) {
                found.push((front.pgid, format!("stream {i}")));
            }
        }
    }
    found
}

fn holds(bytes: &[u8], needle: &[u8]) -> bool {
    bytes.windows(needle.len()).any(|w| w == needle)
}

/// Where the child test finds the gateway's certificate and key.
const GATEWAY_FILES: &str = "TINFIRM_TEST_PATH_C_GATEWAY";

/// The host takes only an https gateway and trusts the system's roots, which name no
/// certificate of a test's: the test runs in a child of this binary whose `SSL_CERT_FILE` is
/// the fake gateway's own certificate ([`two_runs_with_canaries`]).
#[test]
fn a_front_taken_over_finds_no_canary_reaches_no_other_run_and_is_sent_none() {
    let dir = tempfile::tempdir().unwrap();
    let certified = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
    let der = certified.cert.der().to_vec();
    let base64 = base64::engine::general_purpose::STANDARD.encode(&der);
    let mut pem = String::from("-----BEGIN CERTIFICATE-----\n");
    for line in base64.as_bytes().chunks(64) {
        pem.push_str(std::str::from_utf8(line).unwrap());
        pem.push('\n');
    }
    pem.push_str("-----END CERTIFICATE-----\n");
    std::fs::write(dir.path().join("cert.pem"), pem).unwrap();
    std::fs::write(dir.path().join("cert.der"), &der).unwrap();
    std::fs::write(
        dir.path().join("key.der"),
        certified.signing_key.serialize_der(),
    )
    .unwrap();
    let out = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "two_runs_with_canaries",
            "--nocapture",
            "--test-threads=1",
        ])
        .env("SSL_CERT_FILE", dir.path().join("cert.pem"))
        .env_remove("SSL_CERT_DIR")
        .env(GATEWAY_FILES, dir.path())
        .stdin(std::process::Stdio::null())
        .output()
        .unwrap();
    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    for line in stdout.lines().chain(stderr.lines()) {
        if line.starts_with("path C:")
            || line.starts_with("SKIPPED")
            || line.starts_with("this kernel")
        {
            eprintln!("{line}");
        }
    }
    assert!(
        out.status.success() && stdout.contains("test two_runs_with_canaries ... ok"),
        "{stdout}\n{stderr}"
    );
}

/// Not a test of its own: does nothing unless
/// [`a_front_taken_over_finds_no_canary_reaches_no_other_run_and_is_sent_none`] started it.
#[test]
fn two_runs_with_canaries() {
    let Some(files) = std::env::var_os(GATEWAY_FILES).map(PathBuf::from) else {
        return;
    };
    let cert = std::fs::read(files.join("cert.der")).unwrap();
    let key = std::fs::read(files.join("key.der")).unwrap();
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .unwrap()
        .block_on(with_timeout(240, two_runs(cert, key)));
}

async fn two_runs(cert: Vec<u8>, key: Vec<u8>) {
    {
        let host_token = canary("host_token");
        let git_1 = canary("git_token_run_1");
        let git_2 = canary("git_token_run_2");
        let model_key = canary("model_gateway_key");
        let canaries = [
            host_token.as_str(),
            git_1.as_str(),
            git_2.as_str(),
            model_key.as_str(),
        ];

        let (gateway, gateway_seen) = gateway(cert, key).await;
        let echo = echo().await;
        let repos = [TestRepo::new(), TestRepo::new()];
        let git_servers = [
            GitHttpServer::start(repos[0].dir.path(), Some(git_authorization(&git_1))).await,
            GitHttpServer::start(repos[1].dir.path(), Some(git_authorization(&git_2))).await,
        ];
        let mut server = FakeServer::start().await;
        let tap = FrontTap::new();
        let host = tapped_host(&server, &tap, &host_token, echo).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        let urls: Vec<String> = git_servers
            .iter()
            .map(|s| format!("{}/origin.git", s.url))
            .collect();
        let mut specs = [
            AssignSpec::new("proxies", Some(&urls[0])),
            AssignSpec::new("proxies", Some(&urls[1])),
        ];
        for (spec, token) in specs.iter_mut().zip([&git_1, &git_2]) {
            spec.workspaces[0].token = Some(token.to_string());
            // `[for:<run>]` tells the tap's streams of the two fronts apart.
            spec.prompt_extra = format!(
                " [target:localhost:{}] [wait] [for:{}]",
                echo.port(),
                spec.run_id
            );
            spec.model_access = Some(json!({
                "format": "anthropic",
                "baseUrl": format!("https://localhost:{}", gateway.port()),
                "apiKey": model_key
            }));
        }
        let runs = [specs[0].run_id, specs[1].run_id];
        tap.keep();
        for spec in &specs {
            conn.send(spec.to_json());
        }
        let said = until_each_says(&mut conn, &runs, "waiting for release").await;

        // The runs did use their credentials and the three proxies: each git server saw only
        // its run's token, the gateway saw the key, and the agents got their answers.
        for (text, git_server) in said.iter().zip(&git_servers) {
            for line in [
                "fetch: ok",
                "messages=200",
                "echo: hello through the tunnel",
            ] {
                assert!(text.contains(line), "no `{line}` in:\n{text}");
            }
            assert!(!git_server.seen().is_empty());
        }
        for (git_server, token) in git_servers.iter().zip([&git_1, &git_2]) {
            assert!(
                git_server
                    .seen()
                    .iter()
                    .all(|r| r.authorization.as_deref() == Some(git_authorization(token).as_str()))
            );
        }
        let heads = gateway_seen.lock().unwrap().clone();
        assert!(heads.len() >= 2, "{heads:?}");
        assert!(
            heads.iter().all(|head| head.contains(&model_key)),
            "{heads:?}"
        );

        // The fronts of the two runs, by the prompt that went to each.
        let fronts = tap.fronts();
        let front_of = |run: Uuid| {
            let marker = format!("[for:{run}]");
            let found: Vec<u32> = fronts
                .iter()
                .filter(|f| f.streams.iter().any(|s| holds(s, marker.as_bytes())))
                .map(|f| f.pgid)
                .collect();
            assert_eq!(found.len(), 1, "the fronts of run {run}: {found:?}");
            found[0]
        };
        let (front_1, front_2) = (front_of(runs[0]), front_of(runs[1]));
        // Run 1's bridge listener, from the copies the tap kept of its descriptors.
        let kept_1 = tap.take_kept(front_1);
        let bridge_1 = listeners(&kept_1);
        drop(kept_1);
        assert!(!bridge_1.is_empty(), "run 1's front has no bridge listener");
        let kept_2 = tap.take_kept(front_2);
        tap.stop_keeping();
        let kept_descriptors: usize = kept_2.iter().map(|(_, fds)| fds.len()).sum();

        // What the attacker tries. The host's own process is this test's.
        let run = std::process::id();
        let bystander = std::process::Command::new("sleep")
            .arg("120")
            .spawn()
            .unwrap();
        let mut processes = vec![run, bystander.id()];
        descendants(run, front_2, &mut processes);
        assert!(processes.contains(&front_1), "{processes:?}");
        let mut deny_read = Vec::new();
        every_path(host.dir.path(), &mut deny_read);
        let host_files = deny_read.len();
        // Of each process, its memory, environment, arguments, maps and descriptors: those
        // that are there, as far as this process sees (a front's are root's: it is not
        // dumpable).
        for pid in &processes {
            for entry in [
                "mem", "environ", "cmdline", "maps", "status", "stack", "fd", "fd/0", "fd/1",
                "fd/2", "fd/3", "fd/4", "fd/5", "fd/6", "fd/7", "root", "cwd",
            ] {
                let path = PathBuf::from(format!("/proc/{pid}/{entry}"));
                let there = match std::fs::symlink_metadata(&path) {
                    Ok(_) => true,
                    Err(e) => e.kind() == std::io::ErrorKind::PermissionDenied,
                };
                if there {
                    deny_read.push(path);
                }
            }
        }
        deny_read.extend(
            [
                "/",
                "/etc/passwd",
                "/tmp",
                "/dev/shm",
                BIN,
                "/proc/self/environ",
            ]
            .map(PathBuf::from),
        );
        let paths = &host.paths;
        let marker = Uuid::new_v4().simple().to_string();
        let deny_write: Vec<PathBuf> = vec![
            paths.config_file(),
            paths.config_dir.join(format!("new-{marker}")),
            paths
                .data_dir
                .join("runs")
                .join(format!("{}.json", runs[0])),
            paths.data_dir.join("runs").join(format!("new-{marker}")),
            paths.workspace(runs[0]).join(format!("new-{marker}")),
            paths.workspace(runs[1]).join(format!("new-{marker}")),
            PathBuf::from(format!("/tmp/path-c-{marker}")),
            PathBuf::from(format!("/dev/shm/path-c-{marker}")),
            PathBuf::from(format!("/proc/{run}/mem")),
            PathBuf::from(format!("/proc/{front_1}/mem")),
        ];
        let mut connect: Vec<SocketAddr> = bridge_1.clone();
        connect.extend([gateway, echo]);
        connect.extend(git_servers.iter().map(|s| {
            s.url
                .trim_start_matches("http://")
                .parse::<SocketAddr>()
                .unwrap()
        }));
        connect.push(
            server
                .url
                .split("://")
                .nth(1)
                .and_then(|rest| rest.split('/').next())
                .unwrap()
                .parse()
                .unwrap(),
        );
        // Run 1's tunnels: its proxies, as its sandbox reaches them.
        connect.extend(reported_addr(&said[0], "proxy: "));
        // A Unix socket of the host's own, in the abstract namespace.
        let abstract_name = format!("tinfirm-path-c-{marker}");
        let listener = {
            use std::os::linux::net::SocketAddrExt;
            let addr = std::os::unix::net::SocketAddr::from_abstract_name(abstract_name.as_bytes())
                .unwrap();
            std::os::unix::net::UnixListener::bind_addr(&addr).unwrap()
        };
        listener.set_nonblocking(true).unwrap();
        let job = ProbeJob {
            deny_read: deny_read.clone(),
            signal: processes.clone(),
            linger: false,
            inside: Some(Attempts {
                deny_write: deny_write.clone(),
                connect: connect.clone(),
                abstract_names: vec![abstract_name.clone()],
            }),
        };

        // The attacker in run 2's front: started by `run`'s own code, as `run` starts a
        // sandbox's front, then given what `run` gave run 2's front.
        let command = FrontCommand {
            program: PathBuf::from(BIN),
            args: vec![
                front::SUBCOMMAND.to_string(),
                "--probe".to_string(),
                serde_json::to_string(&job).unwrap(),
            ],
            env: Vec::new(),
            tap: None,
        };
        let probe = SandboxFront::start(&command).await.unwrap();
        for (message, fds) in kept_2 {
            probe.handle().send_all(&message, fds).await.unwrap();
        }
        // At the `ping` it tries, answers on its stderr and exits.
        assert!(
            probe.handle().ping().await.is_err(),
            "the probe answered as a front"
        );
        let ended = probe.handle().wait_ended().await;
        drop(probe);
        let Ended::Exited {
            code: Some(0),
            stderr,
            ..
        } = &ended
        else {
            panic!("the probe did not answer: {ended:?}");
        };
        let inside: Inside = serde_json::from_str(stderr.lines().last().unwrap_or_default())
            .unwrap_or_else(|e| panic!("{e}: {stderr}"));
        let status = &inside.probe.status;
        let confined = status.confined();
        if !confined {
            eprintln!(
                "SKIPPED: this kernel cannot confine a front ({}); `doctor` says why",
                inside.probe.describe()
            );
        } else {
            if !status.landlock_net {
                eprintln!(
                    "this kernel's Landlock has no TCP rules (ABI {} < 4): the TCP attempts are refused by the seccomp filter alone",
                    status.landlock_abi
                );
            }
            if !status.landlock_scope {
                eprintln!(
                    "this kernel's Landlock has no scope for signals and abstract sockets (ABI {} < 6): those attempts are refused by the seccomp filter alone",
                    status.landlock_abi
                );
            }
            eprintln!(
                "path C: {} attempts, {} host files and directories, {} processes, {} addresses; refused: {:?}",
                inside.tried,
                host_files,
                processes.len(),
                connect.len(),
                inside.refused
            );
            eprintln!(
                "path C: the probe held {:?} after {:?}",
                inside.held, inside.taken
            );
            assert!(inside.got_through.is_empty(), "{:#?}", inside.got_through);
            assert!(inside.probe.refused_all(), "{}", inside.probe.describe());
            assert!(inside.probe.own_group && inside.probe.runtime, "{inside:?}");
            let counted = |kind: &str| -> usize {
                inside.refused.get(kind).map_or(0, |by| by.values().sum())
            };
            assert_eq!(counted("read"), deny_read.len());
            assert_eq!(counted("write"), deny_write.len());
            assert_eq!(counted("pidfd_open"), processes.len());
            assert_eq!(counted("signal"), 3 * processes.len());
            assert_eq!(counted("prlimit64"), processes.len());
            assert_eq!(counted("connect"), connect.len());
            assert_eq!(counted("connect abstract"), 1);
            assert_eq!(counted("bind abstract"), 1);
            let refused: usize = inside.refused.values().flat_map(|by| by.values()).sum();
            assert_eq!(refused, inside.tried);
            // Landlock refused every path (EACCES), and the seccomp filter every other attempt
            // (EPERM), before Landlock or the kernel's own checks would have.
            for (kind, by) in &inside.refused {
                let by_whom = if kind == "read" || kind == "write" {
                    "EACCES"
                } else {
                    "EPERM"
                };
                assert!(by.keys().all(|e| e == by_whom), "{kind}: {by:?}");
            }
            // It held what a front holds: its channel and stderr, both to `run`, /dev/null on
            // its stdout, and the copies of what `run` gave run 2's front; no file and no
            // directory, and no socket whose peer is another process than `run`.
            assert_eq!(inside.held.len(), 3 + kept_descriptors, "{:?}", inside.held);
            for held in &inside.held {
                assert!(
                    matches!(held.kind.as_str(), "char" | "fifo")
                        || held.kind.starts_with("unix ")
                        || held.kind.starts_with("inet"),
                    "{held:?}"
                );
                if held.kind.starts_with("unix ") {
                    assert_eq!(held.peer, Some(run), "{held:?}");
                }
            }
            assert!(
                inside.taken.contains(&"bridgeInit".to_string()),
                "{:?}",
                inside.taken
            );
            assert!(
                inside.taken.contains(&"start".to_string()),
                "{:?}",
                inside.taken
            );
            // Nothing reached the host's abstract socket; this process can.
            assert!(listener.accept().is_err());
        }
        // As a control, an unconfined process of the same user reads every host file the
        // probe could not, and finds the canaries there: the host's token in its
        // configuration, and the runs' tokens in their records.
        let mut holding: BTreeMap<String, Vec<String>> = BTreeMap::new();
        for path in &deny_read[..host_files] {
            if path.is_file() {
                let bytes = std::fs::read(path).unwrap();
                for (name, canary) in [
                    ("host token", &host_token),
                    ("git token of run 1", &git_1),
                    ("git token of run 2", &git_2),
                    ("model key", &model_key),
                ] {
                    if bytes.windows(canary.len()).any(|w| w == canary.as_bytes()) {
                        let relative = path.strip_prefix(host.dir.path()).unwrap();
                        holding
                            .entry(name.to_string())
                            .or_default()
                            .push(relative.display().to_string());
                    }
                }
            }
        }
        eprintln!("path C: the host's files that hold a canary: {holding:?}");
        assert!(holding.contains_key("host token"), "{holding:?}");
        {
            use std::os::linux::net::SocketAddrExt;
            let addr = std::os::unix::net::SocketAddr::from_abstract_name(abstract_name.as_bytes())
                .unwrap();
            std::os::unix::net::UnixStream::connect_addr(&addr).unwrap();
        }
        // The runs went on: both fronts are there, and each run ends as it would have.
        for pid in [front_1, front_2] {
            assert!(
                Path::new(&format!("/proc/{pid}")).exists(),
                "front {pid} is gone"
            );
        }
        for run in runs {
            release(paths, run);
        }
        let finished = until_each_finished(&mut conn, &runs).await;
        for frame in &finished {
            assert_eq!(frame["outcome"], "SUCCEEDED", "{frame:#}");
        }
        let mut bystander = bystander;
        let _ = bystander.kill();
        let _ = bystander.wait();

        // What `run` sent its fronts for the whole of both runs: no form of any canary, in a
        // frame or on a descriptor that `run` filled.
        let fronts = tap.fronts();
        let found = found_in(&fronts, &matcher(&canaries));
        assert!(found.is_empty(), "a canary went to a front: {found:?}");
        for front in &fronts {
            for canary in canaries {
                assert!(
                    !front
                        .frames
                        .windows(canary.len())
                        .any(|w| w == canary.as_bytes())
                        && !front
                            .streams
                            .iter()
                            .any(|s| s.windows(canary.len()).any(|w| w == canary.as_bytes()))
                );
            }
        }
        // The tap saw what it had to see: per run, the adapter's stdin with the prompt, the
        // gateway's answers and the git proxy's ref listing on their sockets.
        for (run, pgid) in runs.into_iter().zip([front_1, front_2]) {
            let streams: Vec<&Vec<u8>> = fronts
                .iter()
                .filter(|f| f.pgid == pgid)
                .flat_map(|f| &f.streams)
                .collect();
            let has = |needle: &[u8]| streams.iter().any(|s| holds(s, needle));
            assert!(
                has(b"[scenario:proxies]"),
                "no prompt on a stream of run {run}"
            );
            assert!(
                has(GATEWAY_ANSWER.as_bytes()),
                "no gateway answer on a stream of run {run}"
            );
            assert!(
                has(b" refs/heads/main\n"),
                "no answer of the git proxy on a stream of run {run}"
            );
            let frames: usize = fronts
                .iter()
                .filter(|f| f.pgid == pgid)
                .map(|f| f.frames.len())
                .sum();
            let bytes: usize = streams.iter().map(|s| s.len()).sum();
            eprintln!(
                "path C: run {run}: {frames} bytes of frames and {bytes} bytes on {} descriptors went to its front",
                streams.len()
            );
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    }
}

/// The reverse check that keeps the tap honest: a canary that `run` does send a front, in a
/// frame (the agent's model, in the adapter's environment) and on a descriptor it fills (the
/// prompt, on the adapter's stdin), is caught.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_tap_catches_a_canary_that_run_sends_a_front() {
    with_timeout(120, async {
        let in_prompt = canary("in_prompt");
        let as_model = canary("as_model");
        let echo = echo().await;
        let mut server = FakeServer::start().await;
        let tap = FrontTap::new();
        let host = tapped_host(&server, &tap, &canary("host_token"), echo).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("steer", None);
        spec.prompt_extra = format!(" [done] {in_prompt}");
        spec.model = Some(&as_model);
        conn.send(spec.to_json());
        let finished = until_each_finished(&mut conn, &[spec.run_id]).await;
        assert_eq!(finished[0]["outcome"], "SUCCEEDED", "{:#}", finished[0]);
        // The host's one front is the run's.
        let fronts = tap.fronts();
        assert_eq!(
            fronts.len(),
            1,
            "{:?}",
            fronts.iter().map(|f| f.pgid).collect::<Vec<_>>()
        );
        let in_frames = found_in(&fronts, &matcher(&[&as_model]));
        assert!(
            in_frames.iter().any(|(_, at)| at == "frames"),
            "the tap missed the model in a frame: {in_frames:?}"
        );
        let on_streams = found_in(&fronts, &matcher(&[&in_prompt]));
        assert!(
            on_streams.iter().any(|(_, at)| at.starts_with("stream")),
            "the tap missed the prompt on the adapter's stdin: {on_streams:?}"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
