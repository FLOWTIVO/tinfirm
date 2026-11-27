//! The credential scan of a run's outgoing commits ([`Git::outgoing_contains_secret`]) reads
//! the inflated content of every new object, which no bundle limit bounds: it streams that
//! content and scans it in pieces, so that what the host holds stays small whatever the
//! commits hold.
//!
//! This file is a test binary of its own, with one test: the process's peak resident memory
//! (`VmHWM`) is the measure, and no other test may run beside it.

#![cfg(target_os = "linux")]

use std::io::Write;
use std::path::Path;
use std::process::Command;

use tinfirm_protocol::WorkspaceSpec;
use tinfirm_runner_host::git::Git;
use tinfirm_runner_host::redact::Redactor;
use uuid::Uuid;

const SECRET: &str = "github_pat_TEST_ONLY_fake_scan_token_0123456789";

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

/// This process's peak resident memory, in bytes.
fn peak() -> u64 {
    let status = std::fs::read_to_string("/proc/self/status").unwrap();
    let line = status
        .lines()
        .find(|line| line.starts_with("VmHWM:"))
        .unwrap();
    let kib: u64 = line
        .trim_start_matches("VmHWM:")
        .trim()
        .trim_end_matches("kB")
        .trim()
        .parse()
        .unwrap();
    kib * 1024
}

/// A text file of at least `len` bytes, written a line at a time so that this process never
/// holds it, with `secret` from byte `at`.
fn write_large(path: &Path, len: usize, secret: Option<(usize, &str)>) {
    let mut out = std::io::BufWriter::new(std::fs::File::create(path).unwrap());
    let line = b"lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor\n";
    let mut written = 0;
    while written < len {
        match secret {
            Some((at, secret)) if written <= at && at < written + line.len() => {
                let (head, tail) = line.split_at(at - written);
                out.write_all(head).unwrap();
                out.write_all(secret.as_bytes()).unwrap();
                out.write_all(tail).unwrap();
                written += line.len() + secret.len();
            }
            _ => {
                out.write_all(line).unwrap();
                written += line.len();
            }
        }
    }
    out.flush().unwrap();
}

fn spec() -> WorkspaceSpec {
    WorkspaceSpec {
        component_id: Uuid::new_v4(),
        dir: "repo".into(),
        name: "Repo".into(),
        repo_url: "https://git.example.test/repo.git".into(),
        default_branch: "main".into(),
        branch: "agent/TEST-1".into(),
        git: None,
        author_name: None,
        author_email: None,
        read_only: false,
    }
}

#[test]
fn the_scan_of_large_outgoing_commits_holds_little_and_still_finds_a_credential() {
    let dir = tempfile::tempdir().unwrap();
    let repo = dir.path().join("repo");
    std::fs::create_dir_all(&repo).unwrap();
    git(&repo, &["init", "-q"]);
    std::fs::write(repo.join("README.md"), "base\n").unwrap();
    git(&repo, &["add", "-A"]);
    git(&repo, &["commit", "-q", "-m", "base"]);
    let base = git(&repo, &["rev-parse", "HEAD"]);
    // 96 MiB of text in the outgoing commit, inflated as the scan reads it.
    const LARGE: usize = 96 << 20;
    write_large(&repo.join("large.txt"), LARGE, None);
    git(&repo, &["add", "-A"]);
    git(&repo, &["commit", "-q", "-m", "large"]);

    let host_git = Git::new(
        &repo,
        &spec(),
        ("Host".into(), "host@tinfirm.invalid".into()),
    );
    let redactor = Redactor::new([SECRET]);
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    let scan = |exclude: &[String]| {
        runtime
            .block_on(host_git.outgoing_contains_secret(&redactor, exclude))
            .unwrap()
    };
    let before = peak();
    assert!(!scan(std::slice::from_ref(&base)));
    let grown = peak().saturating_sub(before);
    assert!(
        grown < 32 << 20,
        "the scan of {} MiB grew the peak by {} MiB",
        LARGE >> 20,
        grown >> 20
    );

    // A credential deep in another large file, then one at each place around the end of the
    // scan's first piece of 1 MiB and its overlap (git's headers of the commit and the tree
    // come first): a scan in pieces that did not overlap would cut it there.
    let large = git(&repo, &["rev-parse", "HEAD"]);
    write_large(&repo.join("deep.txt"), 8 << 20, Some((5 << 20, SECRET)));
    git(&repo, &["add", "-A"]);
    git(&repo, &["commit", "-q", "-m", "deep"]);
    assert!(scan(std::slice::from_ref(&large)));
    let deep = git(&repo, &["rev-parse", "HEAD"]);
    let hold = redactor.hold_back();
    let piece = (1 << 20) + hold;
    // Steps shorter than the credential, so one of them straddles every offset in between.
    let step = SECRET.len() - 7;
    for at in (piece - 1200..piece).step_by(step) {
        git(&repo, &["reset", "-q", "--hard", &deep]);
        write_large(&repo.join("cut.txt"), (1 << 20) + 4096, Some((at, SECRET)));
        git(&repo, &["add", "-A"]);
        git(&repo, &["commit", "-q", "-m", "cut"]);
        assert!(scan(std::slice::from_ref(&deep)), "a credential at {at}");
    }
    let grown = peak().saturating_sub(before);
    assert!(grown < 32 << 20, "the peak grew by {} MiB", grown >> 20);
}
