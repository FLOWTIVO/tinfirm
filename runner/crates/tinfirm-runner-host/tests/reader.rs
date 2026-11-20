//! The reader (ADR-0112, open question 1; decision 8, "Path C"): the process that parses what
//! a sandbox sends back, with no credential and confined to one reading's directories.
//!
//! - Hostile archives and bundles, through the real reader: refused, or their bad entries
//!   left out, and nothing written outside the reading's directory.
//! - Readers that lie or die (scripts in the reader's place): the host refuses their answers,
//!   empties what they left, kills what they started, and goes on.
//! - The confinement, where the kernel has Landlock: a reader cannot read a file that stands for
//!   the host's token, another run's directory or the host's memory, and cannot open a socket.

#![cfg(target_os = "linux")]

use std::io::Read;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant};

use tinfirm_bridge::files::{Limits, ReadOne};
use tinfirm_runner_host::reader::{Reader, verify_tree};

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");
const WAIT: Duration = Duration::from_secs(60);

fn limits() -> Limits {
    Limits {
        max_files: 20,
        max_bytes: 1 << 20,
        max_file_bytes: 1 << 16,
    }
}

/// Whether this kernel confines a reader: the confinement tests need it; the others run either
/// way, with an unconfined reader where they must.
fn confinable() -> bool {
    Reader::new(BIN)
        .probe(&[], None, WAIT)
        .map(|probe| probe.confined())
        .unwrap_or(false)
}

/// The real reader, confined where this kernel can.
fn reader() -> Reader {
    Reader::new(BIN).allowing_unconfined(!confinable())
}

/// One archive entry of any type, name and claimed size, as a hostile sandbox could send it.
fn entry(name: &[u8], kind: tar::EntryType, size: u64, content: &[u8], link: &[u8]) -> Vec<u8> {
    let mut header = tar::Header::new_gnu();
    {
        let gnu = header.as_gnu_mut().unwrap();
        gnu.name[..name.len()].copy_from_slice(name);
        gnu.linkname[..link.len()].copy_from_slice(link);
    }
    header.set_entry_type(kind);
    header.set_size(size);
    header.set_mode(0o644);
    header.set_cksum();
    let mut out = header.as_bytes().to_vec();
    out.extend_from_slice(content);
    let pad = (512 - content.len() % 512) % 512;
    out.extend(std::iter::repeat_n(0u8, pad));
    out
}

fn end() -> Vec<u8> {
    vec![0u8; 1024]
}

fn archive(entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out: Vec<u8> = entries.concat();
    out.extend(end());
    out
}

fn file(name: &str, content: &[u8]) -> Vec<u8> {
    entry(
        name.as_bytes(),
        tar::EntryType::Regular,
        content.len() as u64,
        content,
        b"",
    )
}

fn names(dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        for e in std::fs::read_dir(&d).unwrap() {
            let p = e.unwrap().path();
            out.push(p.strip_prefix(dir).unwrap().display().to_string());
            if std::fs::symlink_metadata(&p).unwrap().is_dir() {
                stack.push(p);
            }
        }
    }
    out.sort();
    out
}

/// A run's directories: the one the reading writes, beside a file that stands for the host's
/// token and another run's directory.
struct Scene {
    _dir: tempfile::TempDir,
    root: PathBuf,
    dest: PathBuf,
    canary: PathBuf,
    other_run: PathBuf,
}

impl Scene {
    fn new() -> Scene {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().to_path_buf();
        let dest = root.join("runs/run-1/output");
        std::fs::create_dir_all(&dest).unwrap();
        let config = root.join("config");
        std::fs::create_dir_all(&config).unwrap();
        let canary = config.join("config.json");
        std::fs::write(&canary, "tfr_TEST_ONLY_canary_host_token").unwrap();
        std::fs::set_permissions(&canary, std::fs::Permissions::from_mode(0o600)).unwrap();
        let other_run = root.join("runs/run-2/home");
        std::fs::create_dir_all(&other_run).unwrap();
        std::fs::write(other_run.join("session.jsonl"), "another run's session").unwrap();
        Scene {
            _dir: dir,
            root,
            dest,
            canary,
            other_run,
        }
    }

    /// Nothing outside `dest` changed: the canary and the other run are as they were.
    fn untouched(&self) {
        assert_eq!(
            std::fs::read_to_string(&self.canary).unwrap(),
            "tfr_TEST_ONLY_canary_host_token"
        );
        let outside: Vec<String> = names(&self.root)
            .into_iter()
            .filter(|n| !n.starts_with("runs/run-1/output/"))
            .collect();
        assert_eq!(
            outside,
            [
                "config",
                "config/config.json",
                "runs",
                "runs/run-1",
                "runs/run-1/output",
                "runs/run-2",
                "runs/run-2/home",
                "runs/run-2/home/session.jsonl",
            ]
        );
        assert_eq!(
            std::fs::read_to_string(self.other_run.join("session.jsonl")).unwrap(),
            "another run's session"
        );
    }
}

#[test]
fn a_plain_archive_is_unpacked_and_its_links_and_devices_left_out() {
    let scene = Scene::new();
    let input = archive(&[
        entry(b"docs", tar::EntryType::Directory, 0, b"", b""),
        file("docs/report.md", b"# Report\n"),
        entry(
            b"docs/link",
            tar::EntryType::Symlink,
            0,
            b"",
            b"/etc/passwd",
        ),
        entry(
            b"docs/hard",
            tar::EntryType::Link,
            0,
            b"",
            b"../config/config.json",
        ),
        entry(b"tty", tar::EntryType::Char, 0, b"", b""),
        entry(b"fifo", tar::EntryType::Fifo, 0, b"", b""),
        file("big.bin", &vec![7u8; (1 << 16) + 1]),
    ]);
    let done = reader()
        .unpack(&mut input.as_slice(), &scene.dest, &limits(), false, WAIT)
        .unwrap();
    assert_eq!(done.files, 1);
    assert_eq!(done.bytes, 9);
    let mut left_out = done.left_out.clone();
    left_out.sort();
    assert_eq!(
        left_out,
        ["big.bin", "docs/hard", "docs/link", "fifo", "tty"]
    );
    assert_eq!(names(&scene.dest), ["docs", "docs/report.md"]);
    scene.untouched();
}

#[test]
fn paths_out_of_the_directory_fail_the_reading_and_write_nothing_outside() {
    for name in [
        &b"../escape"[..],
        b"../../escape",
        b"/escape",
        b"a/../../escape",
        b"./../escape",
    ] {
        let scene = Scene::new();
        let input = archive(&[entry(name, tar::EntryType::Regular, 1, b"x", b"")]);
        let error = reader()
            .unpack(&mut input.as_slice(), &scene.dest, &limits(), false, WAIT)
            .unwrap_err();
        assert!(
            format!("{error:#}").contains("not a relative path"),
            "{}: {error:#}",
            String::from_utf8_lossy(name)
        );
        assert!(names(&scene.dest).is_empty());
        scene.untouched();
    }
}

#[test]
fn too_many_entries_or_bytes_fail_the_reading() {
    let scene = Scene::new();
    let many: Vec<Vec<u8>> = (0..21).map(|i| file(&format!("f{i}"), b"x")).collect();
    let error = reader()
        .unpack(
            &mut archive(&many).as_slice(),
            &scene.dest,
            &limits(),
            false,
            WAIT,
        )
        .unwrap_err();
    assert!(
        format!("{error:#}").contains("more than 20 files"),
        "{error:#}"
    );
    // What it wrote before the limit stays, as an unpacking in the host left it, and it was
    // walked: plain files only.
    assert_eq!(verify_tree(&scene.dest, &limits()).unwrap().files, 20);

    // Oversized entries are left out, and counted: an archive of nothing else ends too.
    let scene = Scene::new();
    let oversized: Vec<Vec<u8>> = (0..21)
        .map(|i| file(&format!("big{i}"), &vec![0u8; (1 << 16) + 1]))
        .collect();
    let error = reader()
        .unpack(
            &mut archive(&oversized).as_slice(),
            &scene.dest,
            &limits(),
            false,
            WAIT,
        )
        .unwrap_err();
    assert!(
        format!("{error:#}").contains("more than 20 directories"),
        "{error:#}"
    );

    let scene = Scene::new();
    let tight = Limits {
        max_files: 20,
        max_bytes: 10,
        max_file_bytes: 8,
    };
    let input = archive(&[file("a", b"12345678"), file("b", b"12345678")]);
    let error = reader()
        .unpack(&mut input.as_slice(), &scene.dest, &tight, false, WAIT)
        .unwrap_err();
    assert!(
        format!("{error:#}").contains("more than 10 bytes"),
        "{error:#}"
    );
}

/// An input that never ends: zeros after a header that claims a terabyte.
struct Endless {
    header: Vec<u8>,
    at: usize,
}

impl Read for Endless {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = buf.len().min(64 * 1024);
        for (i, b) in buf[..n].iter_mut().enumerate() {
            *b = self.header.get(self.at + i).copied().unwrap_or(0);
        }
        self.at += n;
        Ok(n)
    }
}

#[test]
fn an_endless_entry_and_a_truncated_one_end_the_reading() {
    let scene = Scene::new();
    let mut endless = Endless {
        header: entry(b"endless", tar::EntryType::Regular, 1 << 40, b"", b"")[..512].to_vec(),
        at: 0,
    };
    let started = Instant::now();
    let error = reader()
        .unpack(
            &mut endless,
            &scene.dest,
            &limits(),
            false,
            Duration::from_secs(3),
        )
        .unwrap_err();
    assert!(format!("{error:#}").contains("did not finish"), "{error:#}");
    assert!(started.elapsed() < Duration::from_secs(20));
    assert!(names(&scene.dest).is_empty());

    // A header that claims more than comes, then the end.
    let scene = Scene::new();
    let mut truncated = entry(b"short", tar::EntryType::Regular, 4096, b"abc", b"");
    truncated.truncate(512 + 3);
    assert!(
        reader()
            .unpack(
                &mut truncated.as_slice(),
                &scene.dest,
                &limits(),
                false,
                WAIT
            )
            .is_err()
    );
    // Not an archive at all.
    let scene = Scene::new();
    let noise: Vec<u8> = (0..4096u32).map(|i| (i * 7919 % 251) as u8).collect();
    assert!(
        reader()
            .unpack(&mut noise.as_slice(), &scene.dest, &limits(), false, WAIT)
            .is_err()
    );
    assert!(names(&scene.dest).is_empty());
}

#[test]
fn one_file_is_read_and_anything_else_is_named() {
    let read = |input: Vec<u8>, max| reader().read_one(&mut input.as_slice(), max, WAIT);
    assert_eq!(
        read(archive(&[file("a.md", b"hello")]), 10).unwrap(),
        ReadOne::File(b"hello".to_vec())
    );
    assert_eq!(read(end(), 10).unwrap(), ReadOne::Missing);
    assert_eq!(
        read(
            archive(&[entry(b"l", tar::EntryType::Symlink, 0, b"", b"/etc/shadow")]),
            10
        )
        .unwrap(),
        ReadOne::NotRegular
    );
    assert_eq!(
        read(archive(&[file("a.md", b"hello")]), 2).unwrap(),
        ReadOne::TooLarge
    );
}

// ----- bundles ------------------------------------------------------------------------------

fn git(dir: &Path, args: &[&str]) -> String {
    let out = Command::new("git")
        .args(["-c", "user.name=T", "-c", "user.email=t@example.invalid"])
        .args([
            "-c",
            "init.defaultBranch=main",
            "-c",
            "commit.gpgsign=false",
        ])
        .args(args)
        .current_dir(dir)
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "git {args:?}: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

/// The host's clone (one commit), and a sandbox's clone of it with one more commit.
struct Repos {
    _dir: tempfile::TempDir,
    root: PathBuf,
    host: PathBuf,
    sandbox: PathBuf,
    base: String,
    head: String,
}

impl Repos {
    fn new() -> Repos {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().to_path_buf();
        let host = root.join("runs/run-1/workspace/repo");
        std::fs::create_dir_all(&host).unwrap();
        git(&host, &["init", "-q"]);
        std::fs::write(host.join("README.md"), "base\n").unwrap();
        git(&host, &["add", "-A"]);
        git(&host, &["commit", "-q", "-m", "base"]);
        let base = git(&host, &["rev-parse", "HEAD"]);
        let host_arg = host.to_string_lossy().into_owned();
        git(&root, &["clone", "-q", "--no-local", &host_arg, "sandbox"]);
        let sandbox = root.join("sandbox");
        std::fs::write(sandbox.join("work.txt"), "the agent's work\n").unwrap();
        git(&sandbox, &["add", "-A"]);
        git(&sandbox, &["commit", "-q", "-m", "work"]);
        let head = git(&sandbox, &["rev-parse", "HEAD"]);
        Repos {
            _dir: dir,
            root,
            host,
            sandbox,
            base,
            head,
        }
    }

    /// A bundle of the sandbox with `refs`, without the host's commit, as an archive.
    fn bundle(&self, refs: &[&str]) -> Vec<u8> {
        let path = self.root.join("b.bundle");
        let path_arg = path.to_string_lossy().into_owned();
        let exclude = format!("^{}", self.base);
        let mut args = vec!["bundle", "create", "-q", path_arg.as_str()];
        args.extend(refs);
        args.push(&exclude);
        git(&self.sandbox, &args);
        let bytes = std::fs::read(&path).unwrap();
        archive(&[file("repository-0.bundle", &bytes)])
    }

    fn import(&self, input: Vec<u8>, head: &str) -> anyhow::Result<()> {
        let scratch = self.root.join("runs/run-1/bundle-0");
        let _ = std::fs::remove_dir_all(&scratch);
        std::fs::create_dir_all(&scratch).unwrap();
        reader().import_bundle(
            &mut input.as_slice(),
            &self.host,
            &scratch,
            "repository-0.bundle",
            head,
            &Limits {
                max_files: 1,
                max_bytes: 1 << 30,
                max_file_bytes: 1 << 30,
            },
            WAIT,
        )
    }

    fn host_head(&self) -> String {
        git(&self.host, &["rev-parse", "HEAD"])
    }
}

#[test]
fn a_bundle_of_the_sandboxs_head_is_imported_into_the_clone() {
    let repos = Repos::new();
    repos.import(repos.bundle(&["HEAD"]), &repos.head).unwrap();
    assert_eq!(repos.host_head(), repos.head);
    assert_eq!(
        std::fs::read_to_string(repos.host.join("work.txt")).unwrap(),
        "the agent's work\n"
    );
    git(&repos.host, &["fsck", "--strict", "--no-dangling"]);
    // The moment's ref is gone again.
    assert!(
        !git(&repos.host, &["for-each-ref"]).contains("refs/tinfirm"),
        "the import left its ref"
    );
}

#[test]
fn hostile_bundles_are_refused_and_the_clone_keeps_its_head() {
    let repos = Repos::new();
    let refused = |input: Vec<u8>, head: &str, expected: &str| {
        let error = repos.import(input, head).unwrap_err();
        assert!(
            format!("{error:#}").contains(expected),
            "{expected}: {error:#}"
        );
        assert_eq!(repos.host_head(), repos.base);
    };
    // A ref the run may not write, beside HEAD.
    refused(
        repos.bundle(&["HEAD", "main"]),
        &repos.head,
        "must hold its HEAD alone",
    );
    // Another HEAD than the sandbox named.
    refused(repos.bundle(&["HEAD"]), &repos.base, "another HEAD");
    // Not a bundle.
    refused(
        archive(&[file(
            "repository-0.bundle",
            b"PK\x03\x04 a zip, not a bundle\n",
        )]),
        &repos.head,
        "not a git bundle",
    );
    // A bundle's header with a pack that is not a pack.
    let header = format!(
        "# v2 git bundle\n-{} base\n{} HEAD\n\n",
        repos.base, repos.head
    );
    let mut fake = header.into_bytes();
    fake.extend_from_slice(b"PACK\x00\x00\x00\x02\x00\x00\x00\x01 this is not a pack at all");
    refused(
        archive(&[file("repository-0.bundle", &fake)]),
        &repos.head,
        "do not fetch",
    );
    // A partial bundle.
    let partial = format!(
        "# v3 git bundle\n@filter=blob:none\n{} HEAD\n\nPACK",
        repos.head
    );
    refused(
        archive(&[file("repository-0.bundle", partial.as_bytes())]),
        &repos.head,
        "asks for",
    );
    // The bundle as a link, or beside another file.
    refused(
        archive(&[entry(
            b"repository-0.bundle",
            tar::EntryType::Symlink,
            0,
            b"",
            b"/etc/passwd",
        )]),
        &repos.head,
        "did not come back",
    );
    let bundle = repos.bundle(&["HEAD"]);
    let mut two = bundle[..bundle.len() - 1024].to_vec();
    two.extend(file("other.bundle", b"x"));
    two.extend(end());
    refused(two, &repos.head, "more than 1 files");
    // Then a good one still goes in.
    repos.import(repos.bundle(&["HEAD"]), &repos.head).unwrap();
    assert_eq!(repos.host_head(), repos.head);
}

// ----- readers that lie or die ---------------------------------------------------------------

/// A script in the reader's place.
///
/// A child process writes the script, not this one. A file that any process has open for
/// writing cannot be executed ("Text file busy"), and a descriptor that this process opened
/// would be held, between a fork and its exec, by every child that another test's thread
/// starts at that moment: the script was then busy when its test ran it.
fn fake_reader(dir: &Path, body: &str) -> Reader {
    use std::io::Write;
    let path = dir.join("fake-reader");
    let mut writer = Command::new("/bin/sh")
        .args(["-c", r#"cat > "$1" && chmod 755 "$1""#, "sh"])
        .arg(&path)
        .stdin(std::process::Stdio::piped())
        .spawn()
        .unwrap();
    let mut script = writer.stdin.take().unwrap();
    script
        .write_all(format!("#!/bin/sh\n{body}\n").as_bytes())
        .unwrap();
    drop(script);
    assert!(writer.wait().unwrap().success());
    Reader::new(path)
}

#[test]
fn a_reader_that_plants_links_or_lies_about_what_it_wrote_is_refused() {
    let scene = Scene::new();
    let scripts = tempfile::tempdir().unwrap();
    let dest = scene.dest.display().to_string();
    let canary = scene.canary.display().to_string();
    let ok = r#"echo '{"ok":{"kind":"unpacked","files":0,"bytes":0,"left_out":[]}}'"#;
    for (body, expected) in [
        (
            format!("cat >/dev/null; ln -s {canary} {dest}/token; {ok}"),
            "link",
        ),
        (
            format!("cat >/dev/null; mkdir -p {dest}/a; ln -s ../../ {dest}/a/up; {ok}"),
            "link",
        ),
        (
            format!("cat >/dev/null; ln {canary} {dest}/token; {ok}"),
            "hard link",
        ),
        (
            format!("cat >/dev/null; mkfifo {dest}/pipe; {ok}"),
            "pipe",
        ),
        (
            format!("cat >/dev/null; echo secret > {dest}/f; {ok}"),
            "the reader says it wrote 0 files",
        ),
        (
            format!(
                r#"cat >/dev/null; head -c 70000 /dev/zero > {dest}/big; echo '{{"ok":{{"kind":"unpacked","files":1,"bytes":70000,"left_out":[]}}}}'"#
            ),
            "larger than its limit",
        ),
        (
            r#"cat >/dev/null; echo '{"ok":{"kind":"unpacked","files":0,"bytes":0,"left_out":[],"token":"x"}}'"#
                .to_string(),
            "not one the host knows",
        ),
        (
            r#"cat >/dev/null; echo '{"ok":{"kind":"bundle","head":"0000000000000000000000000000000000000000"}}'"#
                .to_string(),
            "something else",
        ),
        (
            format!("cat >/dev/null; echo '{{\"ok\":{{\"kind\":\"unpacked\",\"files\":0,\"bytes\":0,\"left_out\":[\"{}\"]}}}}'", "x".repeat(5000)),
            "longer than",
        ),
    ] {
        let reader = fake_reader(scripts.path(), &body);
        let input = archive(&[file("a", b"x")]);
        let error = reader
            .unpack(&mut input.as_slice(), &scene.dest, &limits(), false, WAIT)
            .unwrap_err();
        assert!(format!("{error:#}").contains(expected), "{body}: {error:#}");
        // What it left is gone, and nothing outside changed.
        assert!(names(&scene.dest).is_empty(), "{body}");
        scene.untouched();
    }
}

#[test]
fn a_reader_that_dies_or_says_nothing_or_too_much_fails_its_reading_only() {
    let scene = Scene::new();
    let scripts = tempfile::tempdir().unwrap();
    for (body, expected) in [
        ("kill -9 $$".to_string(), "without an answer"),
        ("exit 0".to_string(), "without an answer"),
        ("cat >/dev/null; echo not json".to_string(), "not one the host knows"),
        (
            "cat >/dev/null; head -c 3000000 /dev/zero | tr '\\0' a".to_string(),
            "more than",
        ),
        (
            r#"cat >/dev/null; echo '{"error":"refused\u001b[2J, the host is fine"}'"#.to_string(),
            "refused?[2J",
        ),
        (
            r#"cat >/dev/null; echo '{"ok":{"kind":"unpacked","files":0,"bytes":0,"left_out":[]}}'; exit 3"#
                .to_string(),
            "ended with",
        ),
    ] {
        let reader = fake_reader(scripts.path(), &body);
        let input = archive(&[file("a", b"x")]);
        let started = Instant::now();
        let error = reader
            .unpack(&mut input.as_slice(), &scene.dest, &limits(), false, WAIT)
            .unwrap_err();
        assert!(format!("{error:#}").contains(expected), "{body}: {error:#}");
        assert!(started.elapsed() < Duration::from_secs(30), "{body}");
    }
    // A file's reading that claims more than it may, or other bytes than it says.
    for body in [
        r#"cat >/dev/null; printf '{"ok":{"kind":"read_one","state":"file","size":5}}\nabc'"#,
        r#"cat >/dev/null; printf '{"ok":{"kind":"read_one","state":"file","size":99}}\n'; head -c 99 /dev/zero"#,
        r#"cat >/dev/null; printf '{"ok":{"kind":"read_one","state":"missing","size":0}}\nextra'"#,
    ] {
        let reader = fake_reader(scripts.path(), body);
        let input = archive(&[file("a", b"x")]);
        assert!(
            reader.read_one(&mut input.as_slice(), 10, WAIT).is_err(),
            "{body}"
        );
    }
    scene.untouched();
}

#[test]
fn a_reader_that_never_ends_is_killed_with_what_it_started() {
    let scene = Scene::new();
    let scripts = tempfile::tempdir().unwrap();
    let pids = scripts.path().join("pids");
    // It starts a child that holds its stdout and would outlive it, and never answers.
    let body = format!("sleep 300 & echo $! > {}; sleep 300", pids.display());
    let reader = fake_reader(scripts.path(), &body);
    let started = Instant::now();
    let error = reader
        .unpack(
            &mut archive(&[file("a", b"x")]).as_slice(),
            &scene.dest,
            &limits(),
            false,
            Duration::from_secs(2),
        )
        .unwrap_err();
    assert!(format!("{error:#}").contains("did not finish"), "{error:#}");
    assert!(started.elapsed() < Duration::from_secs(15));
    let child: u32 = std::fs::read_to_string(&pids)
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    // The child is gone too (a zombie at most, until init reaps it).
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let state = std::fs::read_to_string(format!("/proc/{child}/stat")).unwrap_or_default();
        if state.is_empty() || state.contains(") Z ") {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the reader's child still runs: {state}"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[test]
fn a_reader_gets_no_environment_but_path_and_no_descriptor_but_its_three() {
    let scripts = tempfile::tempdir().unwrap();
    let seen = scripts.path().join("seen");
    let body = format!(
        "env > {seen}.env; ls /proc/$$/fd > {seen}.fd; cat >/dev/null; echo '{{\"ok\":{{\"kind\":\"unpacked\",\"files\":0,\"bytes\":0,\"left_out\":[]}}}}'",
        seen = seen.display()
    );
    let scene = Scene::new();
    fake_reader(scripts.path(), &body)
        .unpack(
            &mut archive(&[]).as_slice(),
            &scene.dest,
            &limits(),
            false,
            WAIT,
        )
        .unwrap();
    let env = std::fs::read_to_string(format!("{}.env", seen.display())).unwrap();
    let names: Vec<&str> = env
        .lines()
        .filter_map(|l| l.split_once('=').map(|(k, _)| k))
        .collect();
    // The shell adds PWD and the like itself; the host gives PATH alone.
    for name in &names {
        assert!(
            matches!(*name, "PATH" | "PWD" | "SHLVL" | "_" | "OLDPWD"),
            "the reader got {name} from the host"
        );
    }
    let fds = std::fs::read_to_string(format!("{}.fd", seen.display())).unwrap();
    let fds: Vec<u32> = fds
        .split_whitespace()
        .filter_map(|f| f.parse().ok())
        .collect();
    // 0, 1, 2, and the one `ls` reads its directory with, which is the highest.
    assert!(fds.iter().filter(|&&fd| fd > 2).count() <= 2, "{fds:?}");
    assert!(fds.contains(&0) && fds.contains(&1) && fds.contains(&2));
}

// ----- the confinement ------------------------------------------------------------------------

#[test]
fn a_confined_reader_cannot_read_the_hosts_token_another_run_or_the_host_or_open_a_socket() {
    if !confinable() {
        eprintln!(
            "SKIPPED: this kernel cannot confine a reader (no Landlock 2 or no seccomp); `doctor` says why"
        );
        return;
    }
    let scene = Scene::new();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let host = std::process::id();
    let deny = [
        scene.canary.clone(),
        scene.canary.parent().unwrap().to_path_buf(),
        scene.other_run.clone(),
        scene.other_run.join("session.jsonl"),
        PathBuf::from(format!("/proc/{host}/mem")),
        PathBuf::from(format!("/proc/{host}/environ")),
        PathBuf::from("/etc/passwd"),
        std::env::current_exe().unwrap(),
    ];
    let probe = Reader::new(BIN)
        .probe(&deny, Some(listener.local_addr().unwrap()), WAIT)
        .unwrap();
    assert!(probe.status.confined(), "{}", probe.describe());
    assert!(probe.reads_refused, "{}", probe.describe());
    assert!(probe.network_refused, "{}", probe.describe());
    assert!(probe.confined());
    // As a control, an unconfined process of the same user reads all of them.
    for path in &deny[..4] {
        assert!(std::fs::File::open(path).is_ok(), "{}", path.display());
    }
    // And the confined reader still does its own job in its own directory.
    let done = Reader::new(BIN)
        .unpack(
            &mut archive(&[file("ok.txt", b"fine")]).as_slice(),
            &scene.dest,
            &limits(),
            false,
            WAIT,
        )
        .unwrap();
    assert_eq!(done.files, 1);
    scene.untouched();
}

#[test]
fn the_confinement_is_reported_as_it_is() {
    let probe = Reader::new(BIN).probe(&[], None, WAIT).unwrap();
    let kernel = std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
    eprintln!(
        "kernel {}: the reader is {}",
        kernel.trim(),
        probe.describe()
    );
    if probe.status.landlock {
        assert!(probe.status.landlock_abi >= 2);
        assert!(probe.status.no_new_privs);
    }
}

/// A process outside every reader's process group, to be signalled: it must outlive the probe.
fn bystander() -> std::process::Child {
    use std::os::unix::process::CommandExt;
    Command::new("sleep")
        .arg("120")
        .process_group(0)
        .spawn()
        .unwrap()
}

#[test]
fn a_confined_reader_can_neither_end_nor_limit_a_process_outside_its_group() {
    if !confinable() {
        eprintln!(
            "SKIPPED: this kernel cannot confine a reader (no Landlock 2 or no seccomp); `doctor` says why"
        );
        return;
    }
    let mut target = bystander();
    let (status, signals) = Reader::new(BIN).probe_signals(target.id(), WAIT).unwrap();
    assert!(status.confined(), "{}", status.describe());
    assert!(signals.reached.is_empty(), "{signals:?}");
    assert!(signals.own_group, "the reader still signals its own group");
    assert!(target.try_wait().unwrap().is_none(), "the bystander lives");
    // As a control, an unconfined process of the same user limits it.
    use rustix::process::{Pid, Resource, getrlimit, prlimit};
    let pid = Pid::from_raw(target.id() as i32).unwrap();
    assert!(prlimit(Some(pid), Resource::Core, getrlimit(Resource::Core)).is_ok());
    target.kill().unwrap();
    target.wait().unwrap();
}

/// Where [`reader_without_landlock`] hands the reader's job to [`launch_reader_without_landlock`].
const WITHOUT_LANDLOCK: &str = "TINFIRM_TEST_READER_WITHOUT_LANDLOCK";

/// A reader as on a kernel before Linux 6.12 at worst, with no Landlock at all: this test binary
/// starts itself again, and [`launch_reader_without_landlock`] refuses Landlock to its thread, as
/// a kernel without it does, and runs the reader in its place. The reader's seccomp filter is
/// then all that confines it. Its answer is the last line of the output.
fn reader_without_landlock(job: &str) -> serde_json::Value {
    let out = Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "launch_reader_without_landlock",
            "--nocapture",
            "--test-threads=1",
        ])
        .env(WITHOUT_LANDLOCK, job)
        .stdin(std::process::Stdio::null())
        .output()
        .unwrap();
    let stdout = String::from_utf8_lossy(&out.stdout);
    // The test harness names the test on the same line, before the answer.
    let line = stdout
        .lines()
        .rev()
        .find_map(|l| l.find('{').map(|at| &l[at..]))
        .unwrap_or("");
    serde_json::from_str(line)
        .unwrap_or_else(|e| panic!("{e}: {stdout}{}", String::from_utf8_lossy(&out.stderr)))
}

/// Not a test of its own: does nothing unless [`reader_without_landlock`] started it.
#[test]
fn launch_reader_without_landlock() {
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
        .arg(tinfirm_runner_host::reader::SUBCOMMAND)
        .arg(job)
        .env_clear()
        .env("PATH", "/usr/bin:/bin")
        .process_group(0)
        .exec();
    panic!("cannot start the reader: {error}");
}

#[test]
fn without_landlock_the_seccomp_filter_alone_keeps_a_readers_signals_in_its_group() {
    let mut target = bystander();
    let job = serde_json::json!({
        "allow_unconfined": true,
        "work": { "kind": "probe", "deny_read": [], "connect": null, "signal": target.id() }
    });
    let answer = reader_without_landlock(&job.to_string());
    let done = &answer["ok"];
    // Landlock is gone, as before Linux 5.19; the filter is there.
    assert_eq!(done["status"]["landlock"], false, "{answer}");
    assert_eq!(done["status"]["seccomp"], true, "{answer}");
    assert_eq!(
        done["signals"]["reached"],
        serde_json::json!([]),
        "{answer}"
    );
    assert_eq!(done["signals"]["own_group"], true, "{answer}");
    assert!(target.try_wait().unwrap().is_none(), "the bystander lives");
    target.kill().unwrap();
    target.wait().unwrap();
}
