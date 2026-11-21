//! `tinfirm-node-agent start-jailer`, run as a caller would run it by hand: what it refuses,
//! and the bounding set it leaves to what it execs.

use std::os::unix::fs::PermissionsExt;
use std::process::Command;

const AGENT: &str = env!("CARGO_BIN_EXE_tinfirm-node-agent");
const JAIL: &str = "tfvm-00000000002a-0003";

fn run(args: &[&str]) -> (bool, String, String) {
    let output = Command::new(AGENT)
        .arg("start-jailer")
        .args(args)
        .output()
        .unwrap();
    (
        output.status.success(),
        String::from_utf8_lossy(&output.stdout).into_owned(),
        String::from_utf8_lossy(&output.stderr).into_owned(),
    )
}

/// A file named `jailer` that says so when it runs, and prints its bounding set.
fn stand_in(dir: &std::path::Path) -> std::path::PathBuf {
    let path = dir.join("jailer");
    std::fs::write(
        &path,
        "#!/bin/sh\necho the stand-in ran\ngrep ^CapBnd: /proc/self/status\n",
    )
    .unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    path
}

#[test]
fn start_jailer_refuses_what_is_not_roots_jailer_or_not_a_jail_of_the_helpers() {
    let dir = tempfile::tempdir().unwrap();
    let jailer = stand_in(dir.path());
    let jailer = jailer.to_str().unwrap();
    let root = tinfirm_node_agent::local::own_uid().unwrap() == 0;

    // Another program, whoever owns it.
    let (ok, out, err) = run(&["/bin/sh", "--id", JAIL]);
    assert!(!ok && out.is_empty(), "{out}{err}");
    assert!(
        err.contains("not an absolute path to a file named `jailer`"),
        "{err}"
    );

    // Arguments that are not a jailer's for one of the helper's jails.
    for args in [
        vec![jailer],
        vec![jailer, "--id", "anything"],
        vec![jailer, "--uid", "0"],
    ] {
        let (ok, out, err) = run(&args);
        assert!(!ok && out.is_empty(), "{args:?}: {out}{err}");
        if root {
            assert!(err.contains("do not start with `--id`"), "{err}");
        }
    }

    // A jailer that its group can write.
    std::fs::set_permissions(jailer, std::fs::Permissions::from_mode(0o775)).unwrap();
    let (ok, out, err) = run(&[jailer, "--id", JAIL]);
    assert!(!ok && out.is_empty(), "{out}{err}");
    assert!(
        err.contains("not a regular file of root's that only root can write"),
        "{err}"
    );

    // A link to root's own program is not a file.
    let link = dir.path().join("link").join("jailer");
    std::fs::create_dir(link.parent().unwrap()).unwrap();
    std::os::unix::fs::symlink("/bin/sh", &link).unwrap();
    let (ok, out, err) = run(&[link.to_str().unwrap(), "--id", JAIL]);
    assert!(!ok && out.is_empty(), "{out}{err}");
    assert!(err.contains("not a regular file of root's"), "{err}");
}

/// As root the stand-in runs with the six capabilities as its bounding set and no other. As
/// any other user nothing runs at all: the file is not root's, and a caller without
/// `CAP_SETPCAP` could not cut the set either.
#[test]
fn what_start_jailer_execs_has_the_six_capabilities_as_its_bounding_set_or_does_not_run() {
    let dir = tempfile::tempdir().unwrap();
    let jailer = stand_in(dir.path());
    let (ok, out, err) = run(&[jailer.to_str().unwrap(), "--id", JAIL, "--uid", "1"]);
    if tinfirm_node_agent::local::own_uid().unwrap() == 0 {
        assert!(ok, "{err}");
        assert!(out.contains("the stand-in ran"), "{out}");
        let set = out
            .lines()
            .find_map(|line| line.strip_prefix("CapBnd:"))
            .map(|set| u64::from_str_radix(set.trim(), 16).unwrap())
            .unwrap();
        // A parent whose own bounding set is narrower leaves fewer; never more.
        assert_eq!(
            set & !tinfirm_node_agent::bounding::jailer_bounding_set(),
            0,
            "{out}"
        );
        assert_ne!(set, 0, "{out}");
    } else {
        assert!(!ok, "{out}");
        assert!(!out.contains("the stand-in ran"), "{out}");
        assert!(err.contains("no jailer was started"), "{err}");
    }
}
