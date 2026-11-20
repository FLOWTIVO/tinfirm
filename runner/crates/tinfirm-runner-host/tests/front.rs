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
            wire::send_frame(socket.as_fd(), &frame(&ready()), Some(&fd)).unwrap();
            while let Some(id) = next_ping() {
                wire::send_frame(socket.as_fd(), &frame(&ToMain::Pong { id }), Some(&fd)).unwrap();
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
