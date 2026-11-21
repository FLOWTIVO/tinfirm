//! The front process of a sandbox (ADR-0112, path C, part 2): its confinement, through its
//! probe. A front must read none of the host's files, make no socket, reach no process outside
//! its process group (`run`, another front, any other process of the user), and start no
//! program, and still run its tokio runtime.

#![cfg(target_os = "linux")]

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::Duration;

use tinfirm_runner_host::front::{self, Probe, ProbeJob};

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");
const WAIT: Duration = Duration::from_secs(60);

/// A process outside every front's group, standing for any process of the host's user.
fn bystander() -> Child {
    use std::os::unix::process::CommandExt;
    Command::new("sleep")
        .arg("120")
        .process_group(0)
        .spawn()
        .unwrap()
}

/// A second front that stays until its stdin closes, standing for another sandbox's front.
fn sibling_front() -> Child {
    use std::os::unix::process::CommandExt;
    let mut child = Command::new(BIN)
        .args([front::SUBCOMMAND, "--probe", r#"{"linger":true}"#])
        .env_clear()
        .process_group(0)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    // Its answer: it has confined itself and lingers.
    let mut line = String::new();
    BufReader::new(child.stdout.as_mut().unwrap())
        .read_line(&mut line)
        .unwrap();
    assert!(line.contains("\"status\""), "{line}");
    child
}

fn ended(mut child: Child) {
    assert!(child.try_wait().unwrap().is_none(), "it outlived the probe");
    let _ = child.kill();
    child.wait().unwrap();
}

#[test]
fn a_front_reads_no_file_of_the_host_reaches_no_other_process_and_starts_nothing() {
    let first = front::probe(BIN.as_ref(), &ProbeJob::default(), WAIT).unwrap();
    if !first.status.confined() {
        eprintln!(
            "SKIPPED: this kernel cannot confine a front ({}); `doctor` says why",
            first.describe()
        );
        return;
    }
    // Canaries for the host's token, a take-back record and another run's files.
    let tmp = tempfile::tempdir().unwrap();
    let config = tmp.path().join("config");
    let runs = tmp.path().join("data/runs/other-run");
    std::fs::create_dir_all(&config).unwrap();
    std::fs::create_dir_all(&runs).unwrap();
    std::fs::write(config.join("config.json"), b"tfr_CANARY").unwrap();
    std::fs::write(runs.join("record.json"), b"CANARY").unwrap();
    let host = std::process::id();
    let deny = vec![
        config.join("config.json"),
        config.clone(),
        runs.join("record.json"),
        tmp.path().join("data"),
        PathBuf::from(format!("/proc/{host}/mem")),
        PathBuf::from(format!("/proc/{host}/environ")),
        PathBuf::from("/etc/passwd"),
        PathBuf::from(BIN),
    ];
    let bystander = bystander();
    let sibling = sibling_front();
    let job = ProbeJob {
        deny_read: deny.clone(),
        // This test process stands for `run`.
        signal: vec![host, bystander.id(), sibling.id()],
        linger: false,
        inside: None,
    };
    let probe: Probe = front::probe(BIN.as_ref(), &job, WAIT).unwrap();
    assert!(probe.status.confined(), "{}", probe.describe());
    assert_eq!(probe.read, vec![false; deny.len()], "{probe:?}");
    assert!(!probe.socket, "{probe:?}");
    assert_eq!(probe.reached, vec![Vec::<String>::new(); 3], "{probe:?}");
    assert!(!probe.spawn && !probe.exec, "{probe:?}");
    assert!(probe.own_group, "a front still signals its own group");
    assert!(
        probe.runtime,
        "a front's runtime runs under the confinement"
    );
    assert!(probe.confined());
    ended(bystander);
    ended(sibling);
    // As a control, an unconfined process of the same user reads the canaries.
    for path in &deny[..3] {
        assert!(std::fs::File::open(path).is_ok(), "{}", path.display());
    }
}

#[test]
fn a_front_whose_stdin_is_not_its_channel_serves_nothing() {
    let out = Command::new(BIN)
        .arg(front::SUBCOMMAND)
        .env_clear()
        .stdin(Stdio::null())
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(out.stdout.is_empty());
    let message = String::from_utf8_lossy(&out.stderr);
    assert!(
        message.contains("stdin must be the control channel"),
        "{message}"
    );
    let out = Command::new(BIN)
        .args([
            front::SUBCOMMAND,
            "--probe",
            r#"{"deny_read":[],"shell":"sh"}"#,
        ])
        .env_clear()
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(out.stdout.is_empty());
}

// ----- the wire between `run` and a front (slice 2) -----------------------------------------

use front::handle::{Ended, FrontCommand, FrontHandle};
use front::wire::{self, ToFront, ToMain};

/// The real front: this binary's `front`.
fn the_front() -> FrontCommand {
    FrontCommand {
        program: PathBuf::from(BIN),
        args: vec![front::SUBCOMMAND.to_string()],
        env: Vec::new(),
        tap: None,
    }
}

/// Where [`hostile_front`] tells [`hostile_front_child`] what to do.
const HOSTILE: &str = "TINFIRM_TEST_HOSTILE_FRONT";
/// The file whose descriptor the hostile front sends towards `run`.
const HOSTILE_FILE: &str = "TINFIRM_TEST_HOSTILE_FILE";

/// A fake front that breaks the wire in the way `mode` names: this test binary, started again.
fn hostile_front(mode: &str, extra: &[(&str, &str)]) -> FrontCommand {
    let mut env = vec![(HOSTILE.to_string(), mode.to_string())];
    env.extend(extra.iter().map(|(k, v)| (k.to_string(), v.to_string())));
    FrontCommand {
        program: std::env::current_exe().unwrap(),
        args: ["hostile_front_child", "--exact", "--ignored", "--nocapture"]
            .map(String::from)
            .to_vec(),
        env,
        tap: None,
    }
}

/// Reads one frame of `run`'s from `socket`.
fn next_message_init(socket: &std::os::fd::OwnedFd) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut input = std::fs::File::from(socket.try_clone().ok()?);
    let mut header = [0u8; 4];
    input.read_exact(&mut header).ok()?;
    let mut payload = vec![0u8; u32::from_be_bytes(header) as usize];
    input.read_exact(&mut payload).ok()?;
    Some(payload)
}

/// A seeded xorshift, as the bridge's hostile peers use.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
}

/// The hostile fake front: what [`HOSTILE`] says, on its stdin, the control channel. Run only
/// by the tests that start it.
#[test]
#[ignore = "the hostile fake front of the wire tests, which start it"]
fn hostile_front_child() {
    use std::io::Write;
    use std::os::fd::AsFd;
    let Ok(mode) = std::env::var(HOSTILE) else {
        return;
    };
    let socket = std::io::stdin().as_fd().try_clone_to_owned().unwrap();
    let mut out = std::fs::File::from(socket.try_clone().unwrap());
    let ready = || ToMain::Ready {
        status: tinfirm_runner_host::confine::Status::none("a hostile fake front"),
    };
    let frame = |message: &ToMain| wire::encode(message, wire::MAX_TO_MAIN).unwrap();
    // Reads `run`'s next frame (the tests' fronts answer pings in order).
    let mut input = std::fs::File::from(socket.try_clone().unwrap());
    let mut next_message = move || -> Option<ToFront> {
        use std::io::Read;
        let mut header = [0u8; 4];
        input.read_exact(&mut header).ok()?;
        let mut payload = vec![0u8; u32::from_be_bytes(header) as usize];
        input.read_exact(&mut payload).ok()?;
        wire::decode_to_front(&payload).ok()
    };
    let mut next_ping = move || -> Option<u64> {
        loop {
            if let ToFront::Ping { id } = next_message()? {
                return Some(id);
            }
        }
    };
    let mut rng = Rng(0x5eed_f00d_cafe_1234);
    match mode.as_str() {
        "garbage" => loop {
            let bytes: Vec<u8> = (0..4096).map(|_| rng.next() as u8).collect();
            if out.write_all(&bytes).is_err() {
                return;
            }
        },
        "huge" => {
            let _ = out.write_all(&0x7fff_ffffu32.to_be_bytes());
            let _ = out.write_all(&[b'{'; 1024]);
        }
        "empty" => {
            let _ = out.write_all(&[0, 0, 0, 0]);
        }
        "unknown-field" => {
            let mut payload = serde_json::to_value(ready()).unwrap();
            payload["shell"] = serde_json::json!("sh");
            let payload = serde_json::to_vec(&payload).unwrap();
            let _ = out.write_all(&(payload.len() as u32).to_be_bytes());
            let _ = out.write_all(&payload);
        }
        "not-json" => {
            let payload = b"\xff\xfenot json at all";
            let _ = out.write_all(&(payload.len() as u32).to_be_bytes());
            let _ = out.write_all(payload);
        }
        "second-ready" => {
            let _ = out.write_all(&frame(&ready()));
            let _ = out.write_all(&frame(&ready()));
        }
        "unasked-pong" => {
            let _ = out.write_all(&frame(&ready()));
            let _ = out.write_all(&frame(&ToMain::Pong { id: 999 }));
        }
        "flood" => {
            let _ = out.write_all(&frame(&ready()));
            for id in 1_000.. {
                if out.write_all(&frame(&ToMain::Pong { id })).is_err() {
                    return;
                }
            }
        }
        "random-frames" => {
            let _ = out.write_all(&frame(&ready()));
            loop {
                let length = (rng.next() % 2048) as usize + 1;
                let mut bytes = (length as u32).to_be_bytes().to_vec();
                bytes.extend(
                    (0..length).map(|_| b"{}\"type:pongid,0123456789 "[rng.next() as usize % 25]),
                );
                if out.write_all(&bytes).is_err() {
                    return;
                }
            }
        }
        "descriptors" => {
            // Every frame it sends carries a descriptor of a file `run` must never get.
            let file = std::fs::File::open(std::env::var(HOSTILE_FILE).unwrap()).unwrap();
            let fd = std::os::fd::OwnedFd::from(file);
            wire::send_frame(socket.as_fd(), &frame(&ready()), std::slice::from_ref(&fd)).unwrap();
            while let Some(id) = next_ping() {
                wire::send_frame(
                    socket.as_fd(),
                    &frame(&ToMain::Pong { id }),
                    std::slice::from_ref(&fd),
                )
                .unwrap();
            }
        }
        "silent" => std::thread::sleep(Duration::from_secs(60)),
        "early-exit" => {
            eprintln!("boom before ready");
            std::process::exit(7);
        }
        "partial-header" => {
            let _ = out.write_all(&frame(&ready()));
            // `init` read first: an exit with unread bytes would reset the channel.
            let _ = next_message_init(&socket);
            let _ = out.write_all(&[0, 0]);
            std::process::exit(0);
        }
        "partial-payload" => {
            let _ = out.write_all(&frame(&ready()));
            // `init` read first: an exit with unread bytes would reset the channel.
            let _ = next_message_init(&socket);
            let _ = out.write_all(&[0, 0, 0, 100]);
            let _ = out.write_all(b"{\"type\":");
            std::process::exit(0);
        }
        "exit-after-ready" => {
            let _ = out.write_all(&frame(&ready()));
            // `init` read first: an exit with unread bytes would reset the channel.
            let _ = next_message_init(&socket);
            std::process::exit(0);
        }
        "connect-hostile" | "connect-abuse" | "closed-unknown" | "connect-flood"
        | "connect-long" => {
            use std::io::Read;
            let _ = out.write_all(&frame(&ready()));
            // `init`, then the listener `run` passes (which this fake front never serves).
            let mut channel = std::fs::File::from(socket.try_clone().unwrap());
            let mut read_frame = || -> Option<ToFront> {
                let mut header = [0u8; 4];
                channel.read_exact(&mut header).ok()?;
                let mut payload = vec![0u8; u32::from_be_bytes(header) as usize];
                channel.read_exact(&mut payload).ok()?;
                wire::decode_to_front(&payload).ok()
            };
            if mode != "connect-flood" {
                let _ = read_frame();
                let _ = read_frame();
            }
            let connect = |req: u64, host: &str, port: u16| {
                frame(&ToMain::Connect {
                    req,
                    host: host.into(),
                    port,
                })
            };
            match mode.as_str() {
                "connect-hostile" => {
                    // Destinations that `run` must refuse, each answered with its status.
                    let cases: &[(&str, u16, u16)] = &[
                        ("a..b", 443, 400),
                        ("-bad.example", 443, 400),
                        ("bad_name!.example", 443, 400),
                        ("example.com", 443, 403),
                        ("127.0.0.1", 443, 403),
                        ("169.254.169.254", 80, 403),
                        ("::1", 443, 403),
                        ("[::1]", 443, 403),
                        ("localhost", 443, 403),
                        ("allowed.invalid", 443, 502),
                    ];
                    let mut failed = Vec::new();
                    for (i, (host, port, status)) in cases.iter().enumerate() {
                        let _ = out.write_all(&connect(i as u64 + 1, host, *port));
                        match read_frame() {
                            Some(ToFront::Refused {
                                req, status: got, ..
                            }) if req == i as u64 + 1 && got == *status => {}
                            other => failed.push(format!("{host}:{port}: {other:?}")),
                        }
                    }
                    if failed.is_empty() {
                        std::process::exit(0);
                    }
                    eprintln!("not refused as expected: {failed:?}");
                    std::process::exit(1);
                }
                "connect-abuse" => {
                    let _ = out.write_all(&connect(5, "example.com", 443));
                    let _ = out.write_all(&connect(3, "example.com", 443));
                }
                "closed-unknown" => {
                    let _ = out.write_all(&frame(&ToMain::Closed { req: 9 }));
                }
                "connect-long" => {
                    let _ = out.write_all(&connect(1, &"a".repeat(254), 443));
                }
                _ => {
                    // `run`'s consumer takes the requests and answers none of them.
                    for req in 1..=600u64 {
                        if out.write_all(&connect(req, "example.com", 443)).is_err() {
                            break;
                        }
                    }
                }
            }
        }
        mode if mode.starts_with("model-") => {
            let _ = out.write_all(&frame(&ready()));
            hostile_model_front(&socket, mode);
        }
        mode if mode.starts_with("git-") => {
            let _ = out.write_all(&frame(&ready()));
            hostile_git_front(&socket, mode);
        }
        "deaf" => {
            // Ready, and then it never reads its channel.
            let _ = out.write_all(&frame(&ready()));
            std::thread::sleep(Duration::from_secs(60));
        }
        "mute" => {
            let _ = out.write_all(&frame(&ready()));
            while next_ping().is_some() {}
        }
        "crash-later" => {
            let _ = out.write_all(&frame(&ready()));
            if let Some(id) = next_ping() {
                let _ = out.write_all(&frame(&ToMain::Pong { id }));
                eprintln!("crashed after a pong");
                std::process::exit(9);
            }
        }
        other => panic!("unknown mode {other}"),
    }
    // Stay, as a front that broke the wire and goes on: `run` must end it.
    std::thread::sleep(Duration::from_secs(60));
}

/// `run`'s next frame, with the descriptor that came with it.
fn read_with_descriptor(
    socket: &std::os::fd::OwnedFd,
) -> Option<(ToFront, Option<std::os::fd::OwnedFd>)> {
    use rustix::net::{RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags, recvmsg};
    use std::io::Read;
    let mut header = [0u8; 4];
    let mut space = [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(1))];
    let mut control = RecvAncillaryBuffer::new(&mut space);
    let got = recvmsg(
        socket,
        &mut [std::io::IoSliceMut::new(&mut header)],
        &mut control,
        RecvFlags::CMSG_CLOEXEC | RecvFlags::WAITALL,
    )
    .ok()?;
    if got.bytes != 4 {
        return None;
    }
    let mut descriptor = None;
    for message in control.drain() {
        if let RecvAncillaryMessage::ScmRights(mut fds) = message {
            descriptor = fds.next();
        }
    }
    let mut payload = vec![0u8; u32::from_be_bytes(header) as usize];
    std::fs::File::from(socket.try_clone().ok()?)
        .read_exact(&mut payload)
        .ok()?;
    Some((wire::decode_to_front(&payload).ok()?, descriptor))
}

/// The model modes of the hostile fake front: after `ready`, `init` and the model listener,
/// requests that `run` must refuse by its own rules (`model-rules`), or that break the wire.
fn hostile_model_front(socket: &std::os::fd::OwnedFd, mode: &str) {
    use std::io::Write;
    use tinfirm_runner_host::model_proxy::{BodyLength, ModelEndpoint, RequestHead, Target};
    let mut out = std::fs::File::from(socket.try_clone().unwrap());
    let mut send = |message: &ToMain| {
        let _ = out.write_all(&wire::encode(message, wire::MAX_TO_MAIN).unwrap());
    };
    let _init = read_with_descriptor(socket);
    let _listener = read_with_descriptor(socket);
    let head = |endpoint, method: &str, length| RequestHead {
        target: Target::Model(endpoint),
        method: method.into(),
        query: None,
        headers: vec![("content-type".into(), "application/json".into())],
        length,
    };
    let messages = head(ModelEndpoint::AnthropicMessages, "POST", BodyLength::Empty);
    // The socket of `req`, then its answer's status and its end.
    let answer = |req: u64, body: Option<&[u8]>, send: &mut dyn FnMut(&ToMain)| {
        let mut status = None;
        loop {
            match read_with_descriptor(socket)? {
                (ToFront::Body { req: got }, Some(fd)) if got == req => {
                    let mut stream = std::os::unix::net::UnixStream::from(fd);
                    if let Some(body) = body {
                        let _ = stream.write_all(body);
                        let _ = stream.shutdown(std::net::Shutdown::Write);
                        send(&ToMain::BodyEnd { req });
                    }
                    // Kept open, unread: `run` answers on it.
                    std::mem::forget(stream);
                }
                (ToFront::Response { req: got, head }, _) if got == req => {
                    status = Some(head.status)
                }
                (ToFront::End { req: got, .. }, _) if got == req => return status,
                _ => {}
            }
        }
    };
    match mode {
        "model-rules" => {
            // Heads the front half would never let through: `run` refuses each by its rules.
            let cases: Vec<(RequestHead, Option<&[u8]>, u16)> = vec![
                (
                    head(ModelEndpoint::OpenaiResponses, "POST", BodyLength::Empty),
                    Some(b""),
                    404,
                ),
                (
                    head(ModelEndpoint::AnthropicMessages, "GET", BodyLength::Empty),
                    Some(b""),
                    405,
                ),
                (
                    head(ModelEndpoint::AnthropicModels, "POST", BodyLength::Empty),
                    Some(b""),
                    405,
                ),
                (
                    head(
                        ModelEndpoint::AnthropicMessages,
                        "POST",
                        BodyLength::Exact(tinfirm_runner_host::model_proxy::MAX_REQUEST_BODY + 1),
                    ),
                    Some(b""),
                    413,
                ),
                // A body longer than its head said, and one shorter.
                (
                    head(
                        ModelEndpoint::AnthropicMessages,
                        "POST",
                        BodyLength::Exact(5),
                    ),
                    Some(b"0123456789"),
                    400,
                ),
                (
                    head(
                        ModelEndpoint::AnthropicMessages,
                        "POST",
                        BodyLength::Exact(10),
                    ),
                    Some(b"01234"),
                    400,
                ),
            ];
            let mut failed = Vec::new();
            for (i, (head, body, want)) in cases.into_iter().enumerate() {
                let req = i as u64 + 1;
                send(&ToMain::Request {
                    req,
                    head: head.clone(),
                });
                let got = answer(req, body, &mut send);
                if got != Some(want) {
                    failed.push(format!("{head:?}: {got:?}, not {want}"));
                }
            }
            if failed.is_empty() {
                std::process::exit(0);
            }
            eprintln!("not refused as expected: {failed:?}");
            std::process::exit(1);
        }
        "model-socket-closed" => {
            // It closes the request's socket at once and says nothing of its body: `run`
            // ends the request all the same.
            let mut streamed = messages.clone();
            streamed.length = BodyLength::Streamed;
            send(&ToMain::Request {
                req: 1,
                head: streamed,
            });
            loop {
                match read_with_descriptor(socket) {
                    Some((ToFront::Body { .. }, fd)) => drop(fd),
                    Some((ToFront::End { req: 1, .. }, _)) => std::process::exit(0),
                    Some(_) => {}
                    None => std::process::exit(1),
                }
            }
        }
        "model-crlf" => {
            let mut bad = messages.clone();
            bad.headers
                .push(("x-injected".into(), "a\r\nhost: elsewhere".into()));
            send(&ToMain::Request { req: 1, head: bad });
        }
        "model-nul" => {
            let mut bad = messages.clone();
            bad.headers.push(("x-injected".into(), "a\0b".into()));
            send(&ToMain::Request { req: 1, head: bad });
        }
        "model-headers" => {
            let mut many = messages.clone();
            many.headers = (0..65).map(|i| (format!("x-h{i}"), "v".into())).collect();
            send(&ToMain::Request { req: 1, head: many });
        }
        "model-query" => {
            let mut bad = messages.clone();
            bad.query = Some("a b".into());
            send(&ToMain::Request { req: 1, head: bad });
        }
        "model-order" => {
            send(&ToMain::Request {
                req: 5,
                head: messages.clone(),
            });
            send(&ToMain::Request {
                req: 3,
                head: messages.clone(),
            });
        }
        "model-body-end-twice" => {
            send(&ToMain::Request {
                req: 1,
                head: messages.clone(),
            });
            while let Some((message, fd)) = read_with_descriptor(socket) {
                if let ToFront::Body { .. } = message {
                    std::mem::forget(fd);
                    send(&ToMain::BodyEnd { req: 1 });
                    send(&ToMain::BodyEnd { req: 1 });
                    break;
                }
            }
        }
        "model-abort-unknown" => send(&ToMain::Abort { req: 99 }),
        "model-flood" => {
            // The gateway holds every request: none ends.
            for req in 1..=65 {
                send(&ToMain::Request {
                    req,
                    head: messages.clone(),
                });
            }
        }
        other => panic!("unknown mode {other}"),
    }
    // Stay, and keep taking `run`'s messages: `run` must end it.
    while read_with_descriptor(socket).is_some() {}
}

/// The git modes of the hostile fake front: after `ready`, `init`, the git listener and its
/// routes, requests that `run` must refuse by its own rules (`git-rules`), or too many.
fn hostile_git_front(socket: &std::os::fd::OwnedFd, mode: &str) {
    use std::io::Write;
    use tinfirm_runner_host::model_proxy::{
        BodyLength, HeaderBytes, ModelEndpoint, RequestHead, Target,
    };
    let mut out = std::fs::File::from(socket.try_clone().unwrap());
    let mut send = |message: &ToMain| {
        let _ = out.write_all(&wire::encode(message, wire::MAX_TO_MAIN).unwrap());
    };
    // Up to the routes, answering `run`'s round trips.
    let routes = loop {
        match read_with_descriptor(socket) {
            Some((ToFront::Ping { id }, _)) => send(&ToMain::Pong { id }),
            Some((ToFront::GitRoutes { routes, .. }, _)) if !routes.is_empty() => {
                break routes.iter().map(|r| r.route).collect::<Vec<u64>>();
            }
            Some(_) => {}
            None => std::process::exit(1),
        }
    };
    let head = |target, method: &str, query: Option<&str>, length| RequestHead {
        target,
        method: method.into(),
        query: query.map(str::to_string),
        headers: vec![("user-agent".into(), HeaderBytes::from("git/2.54.0"))],
        length,
    };
    let route = routes[0];
    let info = Some("service=git-upload-pack");
    let answer = |req: u64, body: &[u8], send: &mut dyn FnMut(&ToMain)| {
        let mut status = None;
        loop {
            match read_with_descriptor(socket)? {
                (ToFront::Ping { id }, _) => send(&ToMain::Pong { id }),
                (ToFront::Body { req: got }, Some(fd)) if got == req => {
                    let mut stream = std::os::unix::net::UnixStream::from(fd);
                    let _ = stream.write_all(body);
                    let _ = stream.shutdown(std::net::Shutdown::Write);
                    send(&ToMain::BodyEnd { req });
                    std::mem::forget(stream);
                }
                (ToFront::Response { req: got, head }, _) if got == req => {
                    status = Some(head.status)
                }
                (ToFront::End { req: got, .. }, _) if got == req => return status,
                _ => {}
            }
        }
    };
    match mode {
        "git-rules" => {
            let cases: Vec<(RequestHead, &[u8], u16)> = vec![
                // A route the run does not have; a route asked for as the other request; a push.
                (
                    head(
                        Target::GitInfoRefs { route: 999 },
                        "GET",
                        info,
                        BodyLength::Empty,
                    ),
                    b"",
                    404,
                ),
                (
                    head(
                        Target::GitInfoRefs { route },
                        "POST",
                        info,
                        BodyLength::Empty,
                    ),
                    b"",
                    404,
                ),
                (
                    head(
                        Target::GitInfoRefs { route },
                        "GET",
                        Some("service=git-receive-pack"),
                        BodyLength::Empty,
                    ),
                    b"",
                    404,
                ),
                (
                    head(Target::Mcp { route }, "POST", None, BodyLength::Empty),
                    b"",
                    404,
                ),
                // A model request on a front whose sandbox has no model listener.
                (
                    head(
                        Target::Model(ModelEndpoint::AnthropicMessages),
                        "POST",
                        None,
                        BodyLength::Empty,
                    ),
                    b"",
                    503,
                ),
                // A body longer than its head said.
                (
                    head(
                        Target::GitUploadPack { route },
                        "POST",
                        None,
                        BodyLength::Exact(4),
                    ),
                    b"00000000",
                    400,
                ),
            ];
            let mut failed = Vec::new();
            for (i, (head, body, want)) in cases.into_iter().enumerate() {
                let req = i as u64 + 1;
                send(&ToMain::Request {
                    req,
                    head: head.clone(),
                });
                let got = answer(req, body, &mut send);
                if got != Some(want) {
                    failed.push(format!("{head:?}: {got:?}, not {want}"));
                }
            }
            if failed.is_empty() {
                std::process::exit(0);
            }
            eprintln!("not refused as expected: {failed:?}");
            std::process::exit(1);
        }
        "git-flood" => {
            // The code host holds every request, spread over the routes so that `run`'s
            // limit per route does not end them: none ends.
            for req in 1..=65u64 {
                let route = routes[req as usize % routes.len()];
                send(&ToMain::Request {
                    req,
                    head: head(
                        Target::GitInfoRefs { route },
                        "GET",
                        info,
                        BodyLength::Empty,
                    ),
                });
            }
        }
        other => panic!("unknown mode {other}"),
    }
    while let Some((message, _)) = read_with_descriptor(socket) {
        if let ToFront::Ping { id } = message {
            send(&ToMain::Pong { id });
        }
    }
}

fn alive(pid: u32) -> bool {
    std::fs::read_to_string(format!("/proc/{pid}/stat")).is_ok_and(|stat| {
        !stat
            .rsplit(')')
            .next()
            .unwrap_or("")
            .trim_start()
            .starts_with('Z')
    })
}

/// Waits until `pid` is gone (or a zombie).
async fn gone(pid: u32) -> bool {
    for _ in 0..100 {
        if !alive(pid) {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    false
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_front_says_it_is_ready_answers_takes_a_descriptor_and_goes_with_its_handle() {
    let handle = FrontHandle::spawn(&the_front(), uuid::Uuid::new_v4())
        .await
        .unwrap();
    assert!(handle.status().seccomp, "{:?}", handle.status());
    assert!(handle.status().not_dumpable, "{:?}", handle.status());
    for _ in 0..3 {
        handle.ping().await.unwrap();
    }
    handle.check_descriptor().await.unwrap();
    handle.check_descriptor().await.unwrap();
    assert_eq!(handle.ended(), None);
    let pid = handle.pgid();
    assert!(alive(pid));
    // A message without the descriptor it carries is refused before it goes.
    assert!(handle.send(&ToFront::Check { id: 99 }, None).await.is_err());
    drop(handle);
    assert!(gone(pid).await, "the front outlived its handle");
}

#[test]
fn a_front_exits_when_its_channel_closes() {
    use std::io::Read;
    let (ours, theirs) = std::os::unix::net::UnixStream::pair().unwrap();
    let mut child = Command::new(BIN)
        .arg(front::SUBCOMMAND)
        .env_clear()
        .stdin(Stdio::from(std::os::fd::OwnedFd::from(theirs)))
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let mut header = [0u8; 4];
    (&ours).read_exact(&mut header).unwrap();
    let mut payload = vec![0u8; u32::from_be_bytes(header) as usize];
    (&ours).read_exact(&mut payload).unwrap();
    assert!(matches!(
        wire::decode_to_main(&payload).unwrap(),
        ToMain::Ready { .. }
    ));
    drop(ours);
    let status = child.wait().unwrap();
    assert!(status.success(), "{status:?}");
}

/// The descriptors of this process that point at `path`.
fn descriptors_of(path: &std::path::Path) -> usize {
    std::fs::read_dir("/proc/self/fd")
        .unwrap()
        .filter_map(|e| e.ok())
        .filter(|e| std::fs::read_link(e.path()).is_ok_and(|target| target == path))
        .count()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_hostile_front_ends_only_its_own_handle_and_run_gets_no_descriptor_of_it() {
    let dir = tempfile::tempdir().unwrap();
    let canary = dir.path().join("canary");
    std::fs::write(&canary, "a file only a hostile front opened").unwrap();
    let canary_path = canary.to_string_lossy().into_owned();
    // A real front beside the hostile ones, which must go on as if nothing happened.
    let honest = FrontHandle::spawn(&the_front(), uuid::Uuid::new_v4())
        .await
        .unwrap();
    for mode in [
        "garbage",
        "huge",
        "empty",
        "unknown-field",
        "not-json",
        "second-ready",
        "unasked-pong",
        "flood",
        "random-frames",
    ] {
        // Those that break the wire before `ready` fail the start; the others end the handle.
        let ended = match FrontHandle::spawn(&hostile_front(mode, &[]), uuid::Uuid::new_v4()).await
        {
            Err(e) => {
                let e = format!("{e:#}");
                assert!(
                    e.contains("broke its wire") || e.contains("not `ready`"),
                    "{mode}: {e}"
                );
                continue;
            }
            Ok(handle) => {
                let pid = handle.pgid();
                let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
                    .await
                    .unwrap_or_else(|_| panic!("{mode}: the handle did not end the front"));
                assert!(handle.ping().await.is_err(), "{mode}");
                assert!(gone(pid).await, "{mode}: the front was not ended");
                ended
            }
        };
        assert!(matches!(ended, Ended::Broken(_)), "{mode}: {ended:?}");
    }
    // A front that sends descriptors along: `run` never holds one, and the wire goes on.
    let handle = FrontHandle::spawn(
        &hostile_front("descriptors", &[(HOSTILE_FILE, canary_path.as_str())]),
        uuid::Uuid::new_v4(),
    )
    .await
    .unwrap();
    for _ in 0..5 {
        handle.ping().await.unwrap();
    }
    assert_eq!(
        descriptors_of(&canary),
        0,
        "run holds a descriptor a front sent"
    );
    drop(handle);
    // A front that never says it is ready, or exits first, fails its start, with its words.
    let started = std::time::Instant::now();
    let error = FrontHandle::spawn(&hostile_front("silent", &[]), uuid::Uuid::new_v4())
        .await
        .unwrap_err();
    assert!(
        format!("{error:#}").contains("not ready within"),
        "{error:#}"
    );
    assert!(started.elapsed() < Duration::from_secs(20));
    let error = FrontHandle::spawn(&hostile_front("early-exit", &[]), uuid::Uuid::new_v4())
        .await
        .unwrap_err();
    assert!(
        format!("{error:#}").contains("boom before ready"),
        "{error:#}"
    );
    // A front that crashes later: its handle says how, with its stderr.
    let handle = FrontHandle::spawn(&hostile_front("crash-later", &[]), uuid::Uuid::new_v4())
        .await
        .unwrap();
    let _ = handle.ping().await;
    let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
        .await
        .unwrap();
    match &ended {
        Ended::Exited { code, stderr, .. } => {
            assert_eq!(*code, Some(9), "{ended:?}");
            assert!(stderr.contains("crashed after a pong"), "{ended:?}");
        }
        other => panic!("{other:?}"),
    }
    assert!(ended.to_string().contains("exited with code 9"), "{ended}");
    // The honest front was touched by none of it.
    honest.ping().await.unwrap();
    honest.check_descriptor().await.unwrap();
    assert_eq!(honest.ended(), None);
}

/// Where [`front_without_landlock`] hands the probe's job to [`launch_front_without_landlock`].
const WITHOUT_LANDLOCK: &str = "TINFIRM_TEST_FRONT_WITHOUT_LANDLOCK";

/// A front on a kernel without Landlock: this test binary starts itself again, and
/// [`launch_front_without_landlock`] refuses Landlock to its thread, as such a kernel does, and
/// runs the front in its place. The front's seccomp filter is then all that confines it. Its
/// answer is on the last line of the output, after the test harness's own words.
fn front_without_landlock(job: &ProbeJob) -> Probe {
    let out = Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "launch_front_without_landlock",
            "--nocapture",
            "--test-threads=1",
        ])
        .env(WITHOUT_LANDLOCK, serde_json::to_string(job).unwrap())
        .stdin(Stdio::null())
        .output()
        .unwrap();
    let stdout = String::from_utf8_lossy(&out.stdout);
    let line = stdout
        .lines()
        .rev()
        .find_map(|l| l.find('{').map(|at| &l[at..]))
        .unwrap_or("");
    serde_json::from_str(line)
        .unwrap_or_else(|e| panic!("{e}: {stdout}{}", String::from_utf8_lossy(&out.stderr)))
}

/// Not a test of its own: does nothing unless [`front_without_landlock`] started it.
#[test]
fn launch_front_without_landlock() {
    use std::collections::BTreeMap;
    use std::os::unix::process::CommandExt;

    use seccompiler::{BpfProgram, SeccompAction, SeccompFilter, TargetArch};

    let Ok(job) = std::env::var(WITHOUT_LANDLOCK) else {
        return;
    };
    let arch = TargetArch::try_from(std::env::consts::ARCH).unwrap();
    let filter: BpfProgram = SeccompFilter::new(
        BTreeMap::from([(libc::SYS_landlock_create_ruleset, Vec::new())]),
        SeccompAction::Allow,
        SeccompAction::Errno(libc::ENOSYS as u32),
        arch,
    )
    .unwrap()
    .try_into()
    .unwrap();
    seccompiler::apply_filter(&filter).unwrap();
    let error = Command::new(BIN)
        .args([front::SUBCOMMAND, "--probe", &job])
        .env_clear()
        .process_group(0)
        .exec();
    panic!("cannot start the front: {error}");
}

#[test]
fn without_landlock_the_fronts_seccomp_filter_still_refuses_sockets_signals_and_programs() {
    let bystander = bystander();
    let host = std::process::id();
    let probe = front_without_landlock(&ProbeJob {
        deny_read: Vec::new(),
        signal: vec![host, bystander.id()],
        linger: false,
        inside: None,
    });
    // Landlock is gone, as before Linux 5.19; the filter is there.
    assert!(!probe.status.landlock, "{probe:?}");
    assert!(
        probe.status.seccomp && probe.status.not_dumpable,
        "{probe:?}"
    );
    assert!(!probe.status.confined());
    assert!(!probe.socket, "{probe:?}");
    assert_eq!(probe.reached, vec![Vec::<String>::new(); 2], "{probe:?}");
    assert!(!probe.spawn && !probe.exec, "{probe:?}");
    assert!(probe.own_group && probe.runtime, "{probe:?}");
    ended(bystander);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_front_that_does_not_answer_is_ended_and_leaves_no_request_waiting() {
    let mut handle = FrontHandle::spawn(&hostile_front("mute", &[]), uuid::Uuid::new_v4())
        .await
        .unwrap();
    handle.set_answer_timeout(Duration::from_millis(300));
    let error = handle.ping().await.unwrap_err();
    assert!(format!("{error:#}").contains("did not answer"), "{error:#}");
    assert_eq!(handle.waiting(), 0, "the request stays recorded");
    let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(&ended, Ended::Broken(why) if why.contains("did not answer")),
        "{ended:?}"
    );
    // A request on an ended front fails at once and leaves nothing recorded either.
    assert!(handle.ping().await.is_err());
    assert!(handle.check_descriptor().await.is_err());
    assert_eq!(handle.waiting(), 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_front_that_stops_reading_its_channel_is_ended_and_blocks_no_send() {
    let mut handle = FrontHandle::spawn(&hostile_front("deaf", &[]), uuid::Uuid::new_v4())
        .await
        .unwrap();
    handle.set_send_timeout(Duration::from_millis(500));
    let handle = std::sync::Arc::new(handle);
    // Messages without an answer to wait for, until the channel's buffer is full.
    let started = std::time::Instant::now();
    let mut error = None;
    for id in 1..1_000_000u64 {
        if let Err(e) = handle.send(&ToFront::Ping { id }, None).await {
            error = Some(format!("{e:#}"));
            break;
        }
    }
    let error = error.expect("the channel never filled");
    assert!(error.contains("did not take a message"), "{error}");
    assert!(
        started.elapsed() < Duration::from_secs(20),
        "{:?}",
        started.elapsed()
    );
    let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(&ended, Ended::Broken(why) if why.contains("did not take a message")),
        "{ended:?}"
    );
    // Nothing waits behind it: a later send fails at once.
    let later = std::time::Instant::now();
    assert!(handle.send(&ToFront::Ping { id: 0 }, None).await.is_err());
    assert!(later.elapsed() < Duration::from_secs(1));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_end_inside_a_frame_is_a_broken_wire_and_one_between_frames_is_not() {
    for (mode, why) in [
        ("partial-header", "inside a frame's header"),
        ("partial-payload", "within a frame"),
    ] {
        let handle = FrontHandle::spawn(&hostile_front(mode, &[]), uuid::Uuid::new_v4())
            .await
            .unwrap();
        let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
            .await
            .unwrap();
        assert!(
            matches!(&ended, Ended::Broken(text) if text.contains(why)),
            "{mode}: {ended:?}"
        );
    }
    let handle = FrontHandle::spawn(
        &hostile_front("exit-after-ready", &[]),
        uuid::Uuid::new_v4(),
    )
    .await
    .unwrap();
    let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(ended, Ended::Exited { code: Some(0), .. }),
        "{ended:?}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_handle_signals_no_group_once_its_front_is_reaped() {
    let handle = FrontHandle::spawn(
        &hostile_front("exit-after-ready", &[]),
        uuid::Uuid::new_v4(),
    )
    .await
    .unwrap();
    let pgid = handle.pgid();
    assert_eq!(handle.signalled_group(), Some(pgid));
    handle.wait_ended().await;
    // Reaped: its id may belong to another process by now, and is never signalled again.
    assert_eq!(handle.signalled_group(), None);
    drop(handle);
    // A live front's group is signalled at the drop, before it is reaped.
    let live = FrontHandle::spawn(&the_front(), uuid::Uuid::new_v4())
        .await
        .unwrap();
    let pid = live.pgid();
    assert_eq!(live.signalled_group(), Some(pid));
    drop(live);
    assert!(gone(pid).await);
}

/// The children of this process that are zombies.
fn zombies() -> Vec<u32> {
    let me = std::process::id();
    std::fs::read_dir("/proc")
        .unwrap()
        .filter_map(|e| e.ok())
        .filter_map(|e| e.file_name().to_str()?.parse::<u32>().ok())
        .filter(|pid| {
            std::fs::read_to_string(format!("/proc/{pid}/stat")).is_ok_and(|stat| {
                let fields: Vec<&str> = stat
                    .rsplit(')')
                    .next()
                    .unwrap_or("")
                    .split_whitespace()
                    .collect();
                fields.first() == Some(&"Z") && fields.get(1) == Some(&me.to_string().as_str())
            })
        })
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn fronts_started_and_dropped_by_the_hundred_leave_no_zombie() {
    let mut pids = Vec::new();
    for _ in 0..300 {
        let front = FrontHandle::spawn(&the_front(), uuid::Uuid::new_v4())
            .await
            .unwrap();
        pids.push(front.pgid());
        drop(front);
    }
    // Every front is reaped by its handle's reader, shortly after the drop.
    let mut left = Vec::new();
    for _ in 0..200 {
        let zombies = zombies();
        left = pids
            .iter()
            .copied()
            .filter(|p| zombies.contains(p) || alive(*p))
            .collect();
        if left.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(
        left.is_empty(),
        "{} fronts not reaped: {left:?}",
        left.len()
    );
}

// ----- the egress proxy through a front (slice 3) -------------------------------------------

use tinfirm_runner_host::egress_proxy::{EgressGuard, EgressProxy, EgressSettings, Rule};
use tinfirm_runner_host::front::sandbox::{SandboxFront, Serving};
use tinfirm_runner_host::git_proxy::Endpoint;

/// An egress proxy whose listeners a front serves, with `allow` and loopback as `loopback`.
async fn egress_through(front: FrontCommand, allow: &[String], loopback: bool) -> EgressGuard {
    egress_with(Some(front), allow, loopback).await
}

/// A sandbox's front started with `command`, if one is named.
async fn sandbox_front(command: Option<FrontCommand>) -> Option<std::sync::Arc<SandboxFront>> {
    match command {
        Some(command) => Some(SandboxFront::start(&command).await.unwrap()),
        None => None,
    }
}

/// What serves a proxy: `front` on a listener of the proxy's, or this process without one.
fn serving(front: Option<std::sync::Arc<SandboxFront>>) -> Serving {
    match front {
        Some(front) => Serving::Listener(front),
        None => Serving::Host,
    }
}

/// An egress proxy whose front half runs in a front, or in this process without one.
async fn egress_with(front: Option<FrontCommand>, allow: &[String], loopback: bool) -> EgressGuard {
    let settings = EgressSettings {
        allow: allow.iter().map(|r| Rule::parse(r).unwrap()).collect(),
        allow_private: false,
        allow_loopback: loopback,
    };
    let endpoint = Endpoint {
        bind: "127.0.0.2:0".parse().unwrap(),
        sandbox_host: "127.0.0.2".into(),
        extra_hosts: Vec::new(),
    };
    EgressProxy::new(settings, &endpoint)
        .start_run_at(
            uuid::Uuid::new_v4(),
            Vec::new(),
            &serving(sandbox_front(front).await),
        )
        .await
        .unwrap()
}

/// A server that echoes each connection's bytes, and answers `GET` with "hello".
async fn echo_and_http() -> std::net::SocketAddr {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = vec![0u8; 64 * 1024];
                let mut first = true;
                while let Ok(n) = stream.read(&mut buf).await {
                    if n == 0 {
                        break;
                    }
                    if first && buf.starts_with(b"GET ") {
                        let _ = stream
                            .write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 5\r\nconnection: close\r\n\r\nhello")
                            .await;
                        break;
                    }
                    first = false;
                    if stream.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            });
        }
    });
    addr
}

async fn proxy_request(guard: &EgressGuard, request: &str) -> (tokio::net::TcpStream, String) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut stream = tokio::net::TcpStream::connect(guard.local_addr())
        .await
        .unwrap();
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        if stream.read_exact(&mut byte).await.is_err() {
            break;
        }
        head.push(byte[0]);
    }
    (stream, String::from_utf8_lossy(&head).into_owned())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_egress_proxy_in_a_front_tunnels_forwards_and_refuses_as_run_decides() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let server = echo_and_http().await;
    let guard = egress_through(the_front(), &[format!("127.0.0.1:{}", server.port())], true).await;
    let front = guard.front().expect("a front serves the listener");
    assert!(front.status().seccomp);
    let auth = guard.proxy_authorization().to_string();
    // A tunnel to an allowed destination: the front copies its bytes.
    let (mut tunnel, head) = proxy_request(
        &guard,
        &format!(
            "CONNECT {server} HTTP/1.1\r\nHost: {server}\r\nProxy-Authorization: {auth}\r\n\r\n"
        ),
    )
    .await;
    assert!(head.starts_with("HTTP/1.1 200"), "{head}");
    tunnel.write_all(b"through the front").await.unwrap();
    let mut echoed = [0u8; 17];
    tunnel.read_exact(&mut echoed).await.unwrap();
    assert_eq!(&echoed, b"through the front");
    assert_eq!(front.open_requests(), 1);
    // A plain request, forwarded by the front on the connection `run` made.
    let (mut plain, head) = proxy_request(
        &guard,
        &format!("GET http://{server}/x HTTP/1.1\r\nHost: {server}\r\nProxy-Authorization: {auth}\r\nConnection: close\r\n\r\n"),
    )
    .await;
    assert!(head.starts_with("HTTP/1.1 200"), "{head}");
    let mut body = String::new();
    plain.read_to_string(&mut body).await.unwrap();
    assert_eq!(body, "hello");
    // A destination off the list: `run` refuses it and remembers it.
    let (_s, head) = proxy_request(
        &guard,
        &format!("CONNECT 127.0.0.1:9 HTTP/1.1\r\nHost: x\r\nProxy-Authorization: {auth}\r\n\r\n"),
    )
    .await;
    assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    assert!(head.contains("x-tinfirm-egress: denied"), "{head}");
    assert_eq!(guard.denied(), vec!["127.0.0.1:9".to_string()]);
    // The front checks the credential: nothing reaches `run` without it.
    let (_s, head) = proxy_request(
        &guard,
        &format!("CONNECT {server} HTTP/1.1\r\nHost: {server}\r\n\r\n"),
    )
    .await;
    assert!(head.starts_with("HTTP/1.1 407"), "{head}");
    assert_eq!(guard.counts(), (2, 1));
    // A closed tunnel gives its place back.
    drop(tunnel);
    for _ in 0..100 {
        if front.open_requests() == 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(front.open_requests(), 0);
    let pid = front.pgid();
    drop(guard);
    assert!(gone(pid).await, "the front outlived its listener's guard");
}

/// A listener whose queue is full: a connection to it waits until the test takes the queued
/// ones, which `release` does.
async fn a_stalled_destination() -> (
    std::net::SocketAddr,
    tokio::net::TcpListener,
    Vec<tokio::net::TcpStream>,
) {
    let socket = tokio::net::TcpSocket::new_v4().unwrap();
    socket.bind("127.0.0.1:0".parse().unwrap()).unwrap();
    let listener = socket.listen(0).unwrap();
    let addr = listener.local_addr().unwrap();
    let mut queued = Vec::new();
    loop {
        match tokio::time::timeout(
            Duration::from_millis(300),
            tokio::net::TcpStream::connect(addr),
        )
        .await
        {
            Ok(Ok(stream)) => queued.push(stream),
            Ok(Err(e)) => panic!("{e}"),
            Err(_) => break,
        }
        assert!(queued.len() < 64, "the listener's queue never fills");
    }
    (addr, listener, queued)
}

async fn a_client_that_leaves_while_run_connects_costs_no_place(front: Option<FrontCommand>) {
    use tokio::io::AsyncWriteExt;
    let (addr, listener, _queued) = a_stalled_destination().await;
    let guard = egress_with(front, &[addr.to_string()], true).await;
    let auth = guard.proxy_authorization().to_string();
    let mut client = tokio::net::TcpStream::connect(guard.local_addr())
        .await
        .unwrap();
    client
        .write_all(
            format!(
                "CONNECT {addr} HTTP/1.1\r\nHost: {addr}\r\nProxy-Authorization: {auth}\r\n\r\n"
            )
            .as_bytes(),
        )
        .await
        .unwrap();
    // `run` holds a place while it connects.
    for _ in 0..200 {
        if guard.open_connections() == 1 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(guard.open_connections(), 1);
    // The client gives up, and only then does the connection succeed.
    drop(client);
    tokio::time::sleep(Duration::from_millis(200)).await;
    tokio::spawn(async move {
        let mut taken = Vec::new();
        while let Ok((stream, _)) = listener.accept().await {
            taken.push(stream);
        }
    });
    // In a front, `run` makes the connection and hands it to a client that is gone; in `run`,
    // the client's handler is dropped with the connect it waits for. Either way the place
    // comes back.
    let made = u64::from(guard.front().is_some());
    for _ in 0..1000 {
        if guard.counts().0 == made
            && guard.open_connections() == 0
            && guard.front().is_none_or(|f| f.open_requests() == 0)
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(guard.counts(), (made, 0));
    assert_eq!(guard.open_connections(), 0, "the place was not given back");
    if let Some(front) = guard.front() {
        assert_eq!(front.open_requests(), 0);
        assert!(front.ended().is_none(), "{:?}", front.ended());
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_client_that_leaves_while_run_connects_costs_no_place_in_a_front() {
    a_client_that_leaves_while_run_connects_costs_no_place(Some(the_front())).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_client_that_leaves_while_run_connects_costs_no_place_in_run() {
    a_client_that_leaves_while_run_connects_costs_no_place(None).await;
}

async fn only_the_allowlists_refusal_says_denied(front: Option<FrontCommand>) {
    use tokio::io::AsyncReadExt;
    // `localhost` is on the list but resolves to loopback, which this proxy refuses.
    let guard = egress_with(front, &["localhost".to_string()], false).await;
    let auth = guard.proxy_authorization().to_string();
    let refusal = |host: &'static str| {
        let (guard, auth) = (&guard, auth.clone());
        async move {
            let (mut stream, head) = proxy_request(
                guard,
                &format!(
                    "CONNECT {host}:443 HTTP/1.1\r\nHost: x\r\nProxy-Authorization: {auth}\r\n\r\n"
                ),
            )
            .await;
            let length: usize = head
                .lines()
                .find_map(|l| l.strip_prefix("content-length: "))
                .unwrap()
                .trim()
                .parse()
                .unwrap();
            let mut body = vec![0u8; length];
            stream.read_exact(&mut body).await.unwrap();
            (head, String::from_utf8(body).unwrap())
        }
    };
    let (head, body) = refusal("example.com").await;
    assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    assert!(body.contains("not on this sandbox's allowlist"), "{body}");
    assert!(head.contains("x-tinfirm-egress: denied"), "{head}");
    let (head, body) = refusal("localhost").await;
    assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    assert!(body.contains("which no allowlist entry opens"), "{body}");
    assert!(!head.contains("x-tinfirm-egress"), "{head}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_the_allowlists_refusal_says_denied_in_a_front() {
    only_the_allowlists_refusal_says_denied(Some(the_front())).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_the_allowlists_refusal_says_denied_in_run() {
    only_the_allowlists_refusal_says_denied(None).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_hostile_front_gets_from_run_only_what_runs_rules_allow() {
    // `allowed.invalid` is on the list but resolves to nothing; `127.0.0.1:443` and
    // `localhost` are on it but loopback; nothing else is.
    let allow = [
        "allowed.invalid".to_string(),
        "127.0.0.1:443".to_string(),
        "localhost".to_string(),
    ];
    let guard = egress_through(hostile_front("connect-hostile", &[]), &allow, false).await;
    let ended = tokio::time::timeout(Duration::from_secs(60), guard.front().unwrap().wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(&ended, Ended::Exited { code: Some(0), .. }),
        "{ended:?}"
    );
    // What it asked for is in the run's log of refused destinations.
    let denied = guard.denied();
    for expected in [
        "example.com:443",
        "127.0.0.1:443",
        "[::1]:443",
        "localhost:443",
    ] {
        assert!(
            denied.contains(&expected.to_string()),
            "{expected}: {denied:?}"
        );
    }
    // Requests that break the wire end the front.
    for (mode, why) in [
        ("connect-abuse", "a request 3 after 5"),
        ("closed-unknown", "an unknown request 9 closed"),
        ("connect-long", "a destination of 254 bytes"),
    ] {
        let guard = egress_through(hostile_front(mode, &[]), &[], false).await;
        let ended =
            tokio::time::timeout(Duration::from_secs(10), guard.front().unwrap().wait_ended())
                .await
                .unwrap();
        assert!(
            matches!(&ended, Ended::Broken(text) if text.contains(why)),
            "{mode}: {ended:?}"
        );
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_front_with_too_many_open_requests_is_ended() {
    let handle = FrontHandle::spawn(&hostile_front("connect-flood", &[]), uuid::Uuid::new_v4())
        .await
        .unwrap();
    // Taken and never answered.
    let mut requests = handle.take_requests().unwrap();
    tokio::spawn(async move { while requests.recv().await.is_some() {} });
    let ended = tokio::time::timeout(Duration::from_secs(10), handle.wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(&ended, Ended::Broken(text) if text.contains("more than 512 open requests")),
        "{ended:?}"
    );
}

/// The plan's 2.8, as far as this slice makes it measurable: a front's start (to `ready`), its
/// memory while idle, a round trip and a descriptor passed.
/// `cargo test --release -p tinfirm-runner-host --test front measure -- --ignored --nocapture`
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "a measurement, run by hand in release"]
async fn measure_the_wire() {
    let mut starts = Vec::new();
    for _ in 0..20 {
        let started = std::time::Instant::now();
        let front = FrontHandle::spawn(&the_front(), uuid::Uuid::new_v4())
            .await
            .unwrap();
        starts.push(started.elapsed());
        drop(front);
    }
    starts.sort();
    let front = FrontHandle::spawn(&the_front(), uuid::Uuid::new_v4())
        .await
        .unwrap();
    let rss = std::fs::read_to_string(format!("/proc/{}/status", front.pgid()))
        .unwrap()
        .lines()
        .find(|l| l.starts_with("VmRSS:"))
        .unwrap()
        .to_string();
    let started = std::time::Instant::now();
    for _ in 0..10_000 {
        front.ping().await.unwrap();
    }
    let ping = started.elapsed() / 10_000;
    let started = std::time::Instant::now();
    for _ in 0..1_000 {
        front.check_descriptor().await.unwrap();
    }
    let check = started.elapsed() / 1_000;
    println!(
        "start to ready: median {:?}, slowest {:?}; idle {rss}; round trip {ping:?}; descriptor passed and answered {check:?}",
        starts[starts.len() / 2],
        starts[starts.len() - 1]
    );
}

/// The plan's 2.8 for the egress proxy: a tunnel's throughput and a `CONNECT`'s latency, with
/// the front half in this process and in a front.
/// `cargo test --release -p tinfirm-runner-host --test front measure -- --ignored --nocapture`
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "a measurement, run by hand in release"]
async fn measure_the_egress_proxy() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    // A sink that reads everything and answers its byte count when the client half-closes.
    let sink = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let sink_addr = sink.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = sink.accept().await {
            tokio::spawn(async move {
                let mut buf = vec![0u8; 256 * 1024];
                let mut total: u64 = 0;
                while let Ok(n) = stream.read(&mut buf).await {
                    if n == 0 {
                        break;
                    }
                    total += n as u64;
                }
                let _ = stream.write_all(&total.to_be_bytes()).await;
            });
        }
    });
    let allow = [format!("127.0.0.1:{}", sink_addr.port())];
    for (name, front) in [("in run", None), ("in a front", Some(the_front()))] {
        let settings = EgressSettings {
            allow: allow.iter().map(|r| Rule::parse(r).unwrap()).collect(),
            allow_private: false,
            allow_loopback: true,
        };
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let guard = EgressProxy::new(settings, &endpoint)
            .start_run_at(
                uuid::Uuid::new_v4(),
                Vec::new(),
                &serving(sandbox_front(front).await),
            )
            .await
            .unwrap();
        let auth = guard.proxy_authorization().to_string();
        let connect = format!(
            "CONNECT {sink_addr} HTTP/1.1\r\nHost: {sink_addr}\r\nProxy-Authorization: {auth}\r\n\r\n"
        );
        let mut latencies = Vec::new();
        for _ in 0..200 {
            let started = std::time::Instant::now();
            let (stream, head) = proxy_request(&guard, &connect).await;
            assert!(head.starts_with("HTTP/1.1 200"), "{head}");
            latencies.push(started.elapsed());
            drop(stream);
        }
        latencies.sort();
        let (mut tunnel, head) = proxy_request(&guard, &connect).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        let chunk = vec![7u8; 1 << 20];
        let started = std::time::Instant::now();
        for _ in 0..1024 {
            tunnel.write_all(&chunk).await.unwrap();
        }
        tunnel.shutdown().await.unwrap();
        let mut count = [0u8; 8];
        tunnel.read_exact(&mut count).await.unwrap();
        let took = started.elapsed();
        assert_eq!(u64::from_be_bytes(count), 1 << 30);
        println!(
            "{name}: CONNECT to 200: median {:?}, p99 {:?}; 1 GiB through a tunnel in {:.2} s ({:.0} MiB/s)",
            latencies[latencies.len() / 2],
            latencies[latencies.len() * 99 / 100],
            took.as_secs_f64(),
            1024.0 / took.as_secs_f64()
        );
    }
}

// ----- the model proxy in a front ------------------------------------------------------------

/// A gateway that takes every connection and answers nothing.
async fn silent_gateway() -> std::net::SocketAddr {
    use tokio::io::AsyncReadExt;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = [0u8; 4096];
                while matches!(stream.read(&mut buf).await, Ok(n) if n > 0) {}
            });
        }
    });
    addr
}

/// A run's model proxy whose listener `front` serves, towards `gateway`.
async fn model_through(
    front: FrontCommand,
    gateway: std::net::SocketAddr,
) -> tinfirm_runner_host::model_proxy::ModelProxyGuard {
    use tinfirm_runner_host::model_proxy::{ModelProxy, ModelProxySettings};
    let settings = ModelProxySettings {
        allow_private: false,
        local_gateway: None,
        allow_loopback: true,
        allow_http: true,
    };
    let endpoint = Endpoint {
        bind: "127.0.0.2:0".parse().unwrap(),
        sandbox_host: "127.0.0.2".into(),
        extra_hosts: Vec::new(),
    };
    let access = tinfirm_protocol::ModelAccess {
        format: tinfirm_protocol::ModelFormat::Anthropic,
        base_url: Some(format!("http://{gateway}")),
        region: None,
        access_key_id: None,
        api_key: tinfirm_protocol::Secret::new("sk-TEST-ONLY-front-gateway-key"),
    };
    ModelProxy::new(settings, &endpoint)
        .start_run_at(
            uuid::Uuid::new_v4(),
            &access,
            &serving(sandbox_front(Some(front)).await),
        )
        .await
        .unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_hostile_front_gets_from_runs_model_proxy_only_what_its_rules_allow() {
    let gateway = silent_gateway().await;
    // Requests that `run` refuses by its own rules: each answered with its status.
    for mode in ["model-rules", "model-socket-closed"] {
        let guard = model_through(hostile_front(mode, &[]), gateway).await;
        let front = guard.front().unwrap().clone();
        let ended = tokio::time::timeout(Duration::from_secs(60), front.handle().wait_ended())
            .await
            .unwrap();
        assert!(
            matches!(&ended, Ended::Exited { code: Some(0), .. }),
            "{mode}: {ended:?}"
        );
        assert_eq!(front.answering(), 0, "{mode}: a task of `run` stays");
        assert_eq!(
            guard.counts().0,
            0,
            "{mode}: a request reached the gateway whole"
        );
    }
    // Requests that break the wire end the front.
    for (mode, why) in [
        ("model-crlf", "a header that HTTP does not allow"),
        ("model-nul", "a header that HTTP does not allow"),
        ("model-headers", "more than 64 headers"),
        ("model-query", "a query with a byte that no query has"),
        ("model-order", "a request 3 after 5"),
        ("model-body-end-twice", "the body of request 1 ended twice"),
        ("model-abort-unknown", "an abort of an unknown request 99"),
        ("model-flood", "more than 64 open model requests"),
    ] {
        let guard = model_through(hostile_front(mode, &[]), gateway).await;
        let front = guard.front().unwrap().clone();
        let ended = tokio::time::timeout(Duration::from_secs(20), front.handle().wait_ended())
            .await
            .unwrap();
        assert!(
            matches!(&ended, Ended::Broken(text) if text.contains(why)),
            "{mode}: {ended:?}"
        );
        // Nothing of its requests stays in `run` once its front is gone.
        for _ in 0..500 {
            if front.answering() == 0 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert_eq!(front.answering(), 0, "{mode}: a task of `run` stays");
    }
}

/// A gateway for the measurements: `x-measure: small` answers `{}`, `sse:N` N events as fast
/// as it can, `big:N` N bytes; the request body is read whole first.
async fn measuring_gateway() -> std::net::SocketAddr {
    use http_body_util::{BodyExt, StreamBody};
    use hyper::body::{Bytes, Frame};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let _ = stream.set_nodelay(true);
            tokio::spawn(async move {
                let service = hyper::service::service_fn(
                    |req: hyper::Request<hyper::body::Incoming>| async move {
                        let what = req
                            .headers()
                            .get("x-measure")
                            .and_then(|v| v.to_str().ok())
                            .unwrap_or("small")
                            .to_string();
                        let _ = req.into_body().collect().await;
                        let (kind, n) = what.split_once(':').unwrap_or((what.as_str(), "0"));
                        let n: u64 = n.parse().unwrap_or(0);
                        let frames: Box<dyn Iterator<Item = Bytes> + Send> = match kind {
                            "sse" => Box::new(
                                (0..n).map(|i| Bytes::from(format!("data: {{\"i\":{i}}}\n\n"))),
                            ),
                            "big" => {
                                let chunk = Bytes::from(vec![b'x'; 64 * 1024]);
                                Box::new((0..n.div_ceil(64 * 1024)).map(move |_| chunk.clone()))
                            }
                            _ => Box::new(std::iter::once(Bytes::from_static(b"{}"))),
                        };
                        let body = StreamBody::new(futures::stream::iter(
                            frames.map(|b| Ok::<_, std::convert::Infallible>(Frame::data(b))),
                        ));
                        Ok::<_, std::convert::Infallible>(hyper::Response::new(body))
                    },
                );
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), service)
                    .await;
            });
        }
    });
    addr
}

/// The model proxy, with its front half in this process or in a front: the time a small
/// request adds, the gaps between the events of a long SSE answer, and the throughput of a
/// large answer and of a large request body.
/// `cargo test --release -p tinfirm-runner-host --test front measure_the_model -- --ignored --nocapture`
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "a measurement, run by hand in release"]
async fn measure_the_model_proxy() {
    use http_body_util::{BodyExt, Full};
    use hyper::body::Bytes;
    use tinfirm_runner_host::model_proxy::{ModelProxy, ModelProxySettings};
    let gateway = measuring_gateway().await;
    for in_front in [false, true] {
        let settings = ModelProxySettings {
            allow_private: false,
            local_gateway: None,
            allow_loopback: true,
            allow_http: true,
        };
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let access = tinfirm_protocol::ModelAccess {
            format: tinfirm_protocol::ModelFormat::Anthropic,
            base_url: Some(format!("http://{gateway}")),
            region: None,
            access_key_id: None,
            api_key: tinfirm_protocol::Secret::new("sk-TEST-ONLY-measured-gateway-key"),
        };
        let front = sandbox_front(in_front.then(the_front)).await;
        let guard = ModelProxy::new(settings, &endpoint)
            .start_run_at(uuid::Uuid::new_v4(), &access, &serving(front))
            .await
            .unwrap();
        let path = format!(
            "{}/v1/messages",
            url::Url::parse(guard.base_url()).unwrap().path()
        );
        let stream = tokio::net::TcpStream::connect(guard.local_addr())
            .await
            .unwrap();
        stream.set_nodelay(true).unwrap();
        let (mut sender, connection) = hyper::client::conn::http1::handshake::<_, Full<Bytes>>(
            hyper_util::rt::TokioIo::new(stream),
        )
        .await
        .unwrap();
        tokio::spawn(connection);
        let request = |measure: &str, body: Bytes| {
            hyper::Request::post(path.as_str())
                .header("host", "proxy")
                .header("x-measure", measure)
                .body(Full::new(body))
                .unwrap()
        };
        let mode = if in_front { "in a front" } else { "in run" };
        // The time of a small request, the proxy's own share with the gateway's.
        let mut times = Vec::new();
        for _ in 0..500 {
            let started = std::time::Instant::now();
            sender.ready().await.unwrap();
            let answer = sender
                .send_request(request("small", Bytes::from_static(b"{}")))
                .await
                .unwrap();
            let _ = answer.into_body().collect().await.unwrap();
            times.push(started.elapsed());
        }
        times.sort();
        println!(
            "{mode}: a small request: median {:?}, p99 {:?}",
            times[times.len() / 2],
            times[times.len() * 99 / 100]
        );
        // A long SSE answer: the gaps between its parts as they arrive.
        let started = std::time::Instant::now();
        sender.ready().await.unwrap();
        let answer = sender
            .send_request(request("sse:10000", Bytes::from_static(b"{}")))
            .await
            .unwrap();
        let mut body = answer.into_body();
        let mut gaps = Vec::new();
        let mut last = std::time::Instant::now();
        let mut events = 0;
        let mut previous = 0u8;
        while let Some(frame) = body.frame().await {
            let data = frame.unwrap().into_data().unwrap_or_default();
            for &byte in data.iter() {
                if byte == b'\n' && previous == b'\n' {
                    events += 1;
                }
                previous = byte;
            }
            gaps.push(last.elapsed());
            last = std::time::Instant::now();
        }
        assert_eq!(events, 10_000);
        gaps.sort();
        println!(
            "{mode}: 10000 SSE events in {:?}, {} parts, gap median {:?}, p99 {:?}",
            started.elapsed(),
            gaps.len(),
            gaps[gaps.len() / 2],
            gaps[gaps.len() * 99 / 100]
        );
        // A large answer.
        let size = 1u64 << 30;
        let started = std::time::Instant::now();
        sender.ready().await.unwrap();
        let answer = sender
            .send_request(request(&format!("big:{size}"), Bytes::from_static(b"{}")))
            .await
            .unwrap();
        let mut body = answer.into_body();
        let mut got = 0u64;
        while let Some(frame) = body.frame().await {
            got += frame
                .unwrap()
                .into_data()
                .map(|d| d.len() as u64)
                .unwrap_or(0);
        }
        assert_eq!(got, size);
        let took = started.elapsed();
        println!(
            "{mode}: a 1 GiB answer in {took:?} ({:.0} MiB/s)",
            1024.0 / took.as_secs_f64()
        );
        // A large request body, the largest the proxy takes.
        let body = Bytes::from(vec![b'y'; 64 * 1024 * 1024]);
        let mut times = Vec::new();
        for _ in 0..5 {
            let started = std::time::Instant::now();
            sender.ready().await.unwrap();
            let answer = match sender.send_request(request("small", body.clone())).await {
                Ok(answer) => answer,
                Err(e) => panic!("{mode}: {e:?}"),
            };
            assert!(answer.status().is_success(), "{}", answer.status());
            let _ = answer.into_body().collect().await.unwrap();
            times.push(started.elapsed());
        }
        times.sort();
        println!(
            "{mode}: a 64 MiB request body: median {:?} ({:.0} MiB/s)",
            times[2],
            64.0 / times[2].as_secs_f64()
        );
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn one_front_serves_a_sandboxs_egress_and_model_proxies_and_ends_with_the_last() {
    use http_body_util::{BodyExt, Full};
    use hyper::body::Bytes;
    use tinfirm_runner_host::model_proxy::{ModelProxy, ModelProxySettings};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let front = SandboxFront::start(&the_front()).await.unwrap();
    let pid = front.handle().pgid();
    // The egress listener, as a VM gets it, and the model listener of its run, on one front.
    let server = echo_and_http().await;
    let settings = EgressSettings {
        allow: vec![Rule::parse(&format!("127.0.0.1:{}", server.port())).unwrap()],
        allow_private: false,
        allow_loopback: true,
    };
    let endpoint = Endpoint {
        bind: "127.0.0.2:0".parse().unwrap(),
        sandbox_host: "127.0.0.2".into(),
        extra_hosts: Vec::new(),
    };
    let egress = EgressProxy::new(settings, &endpoint)
        .start_run_at(
            uuid::Uuid::new_v4(),
            Vec::new(),
            &Serving::Listener(front.clone()),
        )
        .await
        .unwrap();
    let gateway = measuring_gateway().await;
    let access = tinfirm_protocol::ModelAccess {
        format: tinfirm_protocol::ModelFormat::Anthropic,
        base_url: Some(format!("http://{gateway}")),
        region: None,
        access_key_id: None,
        api_key: tinfirm_protocol::Secret::new("sk-TEST-ONLY-shared-front-key"),
    };
    let model_settings = ModelProxySettings {
        allow_private: false,
        local_gateway: None,
        allow_loopback: true,
        allow_http: true,
    };
    let model = ModelProxy::new(model_settings, &endpoint)
        .start_run_at(
            uuid::Uuid::new_v4(),
            &access,
            &Serving::Listener(front.clone()),
        )
        .await
        .unwrap();
    assert_eq!(egress.front().unwrap().pgid(), pid);
    assert_eq!(model.front().unwrap().handle().pgid(), pid);
    drop(front);
    let model_request = |guard: &tinfirm_runner_host::model_proxy::ModelProxyGuard| {
        let addr = guard.local_addr();
        let path = format!(
            "{}/v1/messages",
            url::Url::parse(guard.base_url()).unwrap().path()
        );
        async move {
            let stream = tokio::net::TcpStream::connect(addr).await?;
            let (mut sender, connection) = hyper::client::conn::http1::handshake::<_, Full<Bytes>>(
                hyper_util::rt::TokioIo::new(stream),
            )
            .await
            .map_err(std::io::Error::other)?;
            tokio::spawn(connection);
            let request = hyper::Request::post(path.as_str())
                .header("host", "proxy")
                .body(Full::new(Bytes::from_static(b"{}")))
                .unwrap();
            let answer = sender
                .send_request(request)
                .await
                .map_err(std::io::Error::other)?;
            let status = answer.status();
            let body = answer
                .into_body()
                .collect()
                .await
                .map_err(std::io::Error::other)?;
            Ok::<_, std::io::Error>((status, body.to_bytes()))
        }
    };
    let (status, body) = model_request(&model).await.unwrap();
    assert!(status.is_success(), "{status}");
    assert_eq!(&body[..], b"{}");
    let auth = egress.proxy_authorization().to_string();
    let tunnel_works = || async {
        let (mut tunnel, head) = proxy_request(
            &egress,
            &format!(
                "CONNECT {server} HTTP/1.1\r\nHost: {server}\r\nProxy-Authorization: {auth}\r\n\r\n"
            ),
        )
        .await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        tunnel.write_all(b"ping").await.unwrap();
        let mut echoed = [0u8; 4];
        tunnel.read_exact(&mut echoed).await.unwrap();
        assert_eq!(&echoed, b"ping");
    };
    tunnel_works().await;
    // The model listener closes with its run; the egress listener and the front stay.
    let model_addr = model.local_addr();
    drop(model);
    let mut closed = false;
    for _ in 0..100 {
        if tokio::net::TcpStream::connect(model_addr).await.is_err() {
            closed = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(closed, "the model listener is still served");
    tunnel_works().await;
    assert!(alive(pid));
    // The front ends with the last of its owners.
    drop(egress);
    assert!(
        gone(pid).await,
        "the front outlived its sandbox's listeners"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_front_serves_one_listener_of_each_service_and_run_refuses_a_second() {
    use tinfirm_runner_host::model_proxy::{ModelProxy, ModelProxySettings};
    let front = SandboxFront::start(&the_front()).await.unwrap();
    let gateway = measuring_gateway().await;
    let start_model = |front: std::sync::Arc<SandboxFront>| async move {
        let settings = ModelProxySettings {
            allow_private: false,
            local_gateway: None,
            allow_loopback: true,
            allow_http: true,
        };
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let access = tinfirm_protocol::ModelAccess {
            format: tinfirm_protocol::ModelFormat::Anthropic,
            base_url: Some(format!("http://{gateway}")),
            region: None,
            access_key_id: None,
            api_key: tinfirm_protocol::Secret::new("sk-TEST-ONLY-one-listener-key"),
        };
        ModelProxy::new(settings, &endpoint)
            .start_run_at(uuid::Uuid::new_v4(), &access, &Serving::Listener(front))
            .await
    };
    let server = echo_and_http().await;
    let egress = EgressProxy::new(
        EgressSettings {
            allow: vec![Rule::parse(&format!("127.0.0.1:{}", server.port())).unwrap()],
            allow_private: false,
            allow_loopback: true,
        },
        &Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        },
    )
    .start_run_at(
        uuid::Uuid::new_v4(),
        Vec::new(),
        &Serving::Listener(front.clone()),
    )
    .await
    .unwrap();
    let model = start_model(front.clone()).await.unwrap();
    drop(model);
    // A second model listener, after the first closed: refused by `run`, not by the front.
    let error = start_model(front.clone()).await.unwrap_err();
    assert!(
        format!("{error:#}").contains("a sandbox has one in its life"),
        "{error:#}"
    );
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(
        front.handle().ended().is_none(),
        "{:?}",
        front.handle().ended()
    );
    // The other listener goes on.
    let auth = egress.proxy_authorization().to_string();
    let (_tunnel, head) = proxy_request(
        &egress,
        &format!(
            "CONNECT {server} HTTP/1.1\r\nHost: {server}\r\nProxy-Authorization: {auth}\r\n\r\n"
        ),
    )
    .await;
    assert!(head.starts_with("HTTP/1.1 200"), "{head}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_hostile_front_gets_from_runs_git_proxy_only_what_its_rules_allow() {
    use tinfirm_runner_host::git_proxy::{GitProxy, ListenerLimits};
    let upstream = silent_gateway().await;
    let start = |mode: &'static str| async move {
        let front = SandboxFront::start(&hostile_front(mode, &[]))
            .await
            .unwrap();
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let (proxy, listener) = GitProxy::start_in_front(
            endpoint,
            true,
            ListenerLimits::default(),
            &Serving::Listener(front.clone()),
        )
        .await
        .unwrap();
        // Nine routes: room for more than 64 requests held open at 8 per route.
        let route: Vec<_> = (0..9)
            .map(|i| {
                proxy
                    .register(
                        uuid::Uuid::new_v4(),
                        &format!("http://{upstream}/acme/repo{i}.git"),
                        None,
                        "agent/TEST-1",
                    )
                    .unwrap()
            })
            .collect();
        // A front that floods may be ended before it answers the round trip after its routes.
        let _ = proxy.publish().await;
        (front, proxy, listener, route)
    };
    let (front, _proxy, _listener, _route) = start("git-rules").await;
    let ended = tokio::time::timeout(Duration::from_secs(60), front.handle().wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(&ended, Ended::Exited { code: Some(0), .. }),
        "{ended:?}"
    );
    for _ in 0..500 {
        if front.answering() == 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(front.answering(), 0, "a task of `run` stays");
    // More requests than a front may have open: it is ended, and nothing stays.
    let (front, _proxy, _listener, _route) = start("git-flood").await;
    let ended = tokio::time::timeout(Duration::from_secs(20), front.handle().wait_ended())
        .await
        .unwrap();
    assert!(
        matches!(&ended, Ended::Broken(text) if text.contains("more than 64 open git requests")),
        "{ended:?}"
    );
    for _ in 0..500 {
        if front.answering() == 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(front.answering(), 0, "a task of `run` stays");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_front_that_ends_with_its_run_is_no_broken_wire() {
    use tinfirm_runner_host::model_proxy::{ModelProxy, ModelProxySettings};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let endpoint = Endpoint {
        bind: "127.0.0.2:0".parse().unwrap(),
        sandbox_host: "127.0.0.2".into(),
        extra_hosts: Vec::new(),
    };
    let server = echo_and_http().await;
    let gateway = silent_gateway().await;
    for traffic in [false, true] {
        let front = SandboxFront::start(&the_front()).await.unwrap();
        let end = front.handle().end_watch();
        let egress = EgressProxy::new(
            EgressSettings {
                allow: vec![Rule::parse(&format!("127.0.0.1:{}", server.port())).unwrap()],
                allow_private: false,
                allow_loopback: true,
            },
            &endpoint,
        )
        .start_run_at(
            uuid::Uuid::new_v4(),
            Vec::new(),
            &Serving::Listener(front.clone()),
        )
        .await
        .unwrap();
        let access = tinfirm_protocol::ModelAccess {
            format: tinfirm_protocol::ModelFormat::Anthropic,
            base_url: Some(format!("http://{gateway}")),
            region: None,
            access_key_id: None,
            api_key: tinfirm_protocol::Secret::new("sk-TEST-ONLY-ending-front-key"),
        };
        let model = ModelProxy::new(
            ModelProxySettings {
                allow_private: false,
                local_gateway: None,
                allow_loopback: true,
                allow_http: true,
            },
            &endpoint,
        )
        .start_run_at(
            uuid::Uuid::new_v4(),
            &access,
            &Serving::Listener(front.clone()),
        )
        .await
        .unwrap();
        let mut open = Vec::new();
        if traffic {
            // A tunnel that is open, and a model request that waits for its gateway, when
            // the run ends.
            let auth = egress.proxy_authorization().to_string();
            let (mut tunnel, head) = proxy_request(
                &egress,
                &format!("CONNECT {server} HTTP/1.1\r\nHost: {server}\r\nProxy-Authorization: {auth}\r\n\r\n"),
            )
            .await;
            assert!(head.starts_with("HTTP/1.1 200"), "{head}");
            tunnel.write_all(b"ping").await.unwrap();
            let mut echoed = [0u8; 4];
            tunnel.read_exact(&mut echoed).await.unwrap();
            open.push(tunnel);
            let path = format!(
                "{}/v1/messages",
                url::Url::parse(model.base_url()).unwrap().path()
            );
            let mut request = tokio::net::TcpStream::connect(model.local_addr())
                .await
                .unwrap();
            request
                .write_all(
                    format!("POST {path} HTTP/1.1\r\nhost: p\r\ncontent-length: 2\r\n\r\n{{}}")
                        .as_bytes(),
                )
                .await
                .unwrap();
            for _ in 0..500 {
                if front.answering() == 1 {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            assert_eq!(front.answering(), 1);
            open.push(request);
        }
        // The run ends: its listeners close, and the sandbox's front goes with the last owner.
        drop(model);
        drop(egress);
        drop(front);
        let ended = tokio::time::timeout(Duration::from_secs(10), end.wait())
            .await
            .unwrap();
        assert!(
            !matches!(ended, Ended::Broken(_)),
            "traffic {traffic}: {ended:?}"
        );
        drop(open);
    }
}
