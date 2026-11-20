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
fn a_front_without_its_probe_serves_nothing_yet() {
    let out = Command::new(BIN)
        .arg(front::SUBCOMMAND)
        .env_clear()
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(out.stdout.is_empty());
    let message = String::from_utf8_lossy(&out.stderr);
    assert!(message.contains("only runs its probe"), "{message}");
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
