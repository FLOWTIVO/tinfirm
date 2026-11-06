//! The CLI commands, run as a real process with private config/data directories.
//! `credential add codex` runs a fake `codex` script from a temporary PATH, never the
//! user's own Codex CLI.

mod common;

use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};

use chrono::{TimeDelta, Utc};
use tinfirm_runner_host::mirror::{self, Mirrors, mirror_key};

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");
// TEST-ONLY values.
const CLAUDE_TOKEN: &str = "sk-ant-oat01-TEST-ONLY-cli-token-0123456789abcdef";
const HOST_TOKEN: &str = "tfr_TESTONLYclitoken0123456789abcdefghijklmnop";

fn run(dir: &Path, args: &[&str], stdin: Option<&str>, path: Option<&str>) -> Output {
    let mut cmd = Command::new(BIN);
    cmd.args(args)
        .env("TINFIRM_RUNNER_CONFIG_DIR", dir.join("config"))
        .env("TINFIRM_RUNNER_DATA_DIR", dir.join("data"))
        .env("TINFIRM_RUNNER_LOG", "warn")
        .env_remove("TINFIRM_RUNNER_PROTOCOL_VERSION")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if let Some(path) = path {
        cmd.env("PATH", path);
    }
    let mut child = cmd.spawn().unwrap();
    {
        let mut input = child.stdin.take().unwrap();
        if let Some(text) = stdin {
            input.write_all(text.as_bytes()).unwrap();
        }
    }
    child.wait_with_output().unwrap()
}

fn text(output: &Output) -> String {
    format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    )
}

fn mode(path: &Path) -> u32 {
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

#[test]
fn login_reads_the_token_from_stdin_without_a_warning() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let out = run(
        dir,
        &["login", "--server", "http://127.0.0.1:9/"],
        Some(&format!("{HOST_TOKEN}\n")),
        None,
    );
    assert!(out.status.success(), "{}", text(&out));
    assert!(!text(&out).contains("omit --token"), "{}", text(&out));
    assert!(!text(&out).contains(HOST_TOKEN));
    let saved: serde_json::Value =
        serde_json::from_slice(&std::fs::read(dir.join("config/config.json")).unwrap()).unwrap();
    assert_eq!(saved["token"], HOST_TOKEN);
}

#[test]
fn login_and_credentials() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();

    let out = run(
        dir,
        &[
            "login",
            "--server",
            "http://tinfirm.example.com",
            "--token",
            HOST_TOKEN,
        ],
        None,
        None,
    );
    assert!(
        !out.status.success(),
        "plain http to a remote server is refused"
    );

    let out = run(
        dir,
        &[
            "login",
            "--server",
            "http://127.0.0.1:9/",
            "--token",
            HOST_TOKEN,
        ],
        None,
        None,
    );
    assert!(out.status.success(), "{}", text(&out));
    // I8 of the security review of 2026-10-27: the token on the command line is warned about,
    // and stdin recommended.
    assert!(
        String::from_utf8_lossy(&out.stderr).contains("omit --token")
            && String::from_utf8_lossy(&out.stderr).contains("stdin"),
        "{}",
        text(&out)
    );
    let config = dir.join("config/config.json");
    assert_eq!(mode(&config), 0o600);
    assert_eq!(mode(&dir.join("config")), 0o700);
    let saved: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&config).unwrap()).unwrap();
    assert_eq!(saved["serverUrl"], "http://127.0.0.1:9");
    assert_eq!(saved["token"], HOST_TOKEN);
    assert!(!text(&out).contains(HOST_TOKEN), "the token is not echoed");

    // A Claude login is Claude Code's own sign-in into the credential's directory. With
    // neither `claude` nor `docker` on the PATH, the command explains how to sign in elsewhere
    // and registers the login only once its `.credentials.json` is there (ADR-0085).
    let empty_path = dir.join("empty-path");
    std::fs::create_dir_all(&empty_path).unwrap();
    let no_tools = empty_path.to_str().unwrap();
    let out = run(
        dir,
        &["credential", "add", "claude", "--label", "claude-main"],
        None,
        Some(no_tools),
    );
    assert!(!out.status.success(), "{}", text(&out));
    assert!(text(&out).contains("claude auth login"), "{}", text(&out));
    assert!(text(&out).contains("is missing"), "{}", text(&out));
    assert!(!dir.join("config/credentials/claude-main.json").exists());

    let login = dir.join("data/claude-logins/claude-main");
    std::fs::create_dir_all(&login).unwrap();
    let credentials = login.join(".credentials.json");
    std::fs::write(
        &credentials,
        format!(r#"{{"claudeAiOauth":{{"accessToken":"{CLAUDE_TOKEN}"}}}}"#),
    )
    .unwrap();
    std::fs::set_permissions(&credentials, std::fs::Permissions::from_mode(0o600)).unwrap();
    let out = run(
        dir,
        &["credential", "add", "claude", "--label", "claude-main"],
        None,
        Some(no_tools),
    );
    assert!(out.status.success(), "{}", text(&out));
    assert!(
        !text(&out).contains(CLAUDE_TOKEN),
        "the host reads nothing from the login"
    );
    let file = dir.join("config/credentials/claude-main.json");
    assert_eq!(mode(&file), 0o600);
    let stored: serde_json::Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
    assert_eq!(stored["kind"], "CLAUDE_LOGIN");
    assert_eq!(stored["poolSize"], 3);
    assert!(stored.get("secret").is_none(), "no secret of its own");

    let out = run(
        dir,
        &[
            "credential",
            "add",
            "openai-api-key",
            "--label",
            "openai",
            "--pool",
            "2",
        ],
        Some("sk-TEST-ONLY-openai-0123456789abcdef\n"),
        None,
    );
    assert!(out.status.success(), "{}", text(&out));
    let out = run(
        dir,
        &["credential", "add", "claude", "--label", "Bad Label"],
        Some("x\n"),
        None,
    );
    assert!(!out.status.success());

    let out = run(dir, &["credential", "list"], None, None);
    let listing = text(&out);
    assert!(
        listing.contains("claude-main")
            && listing.contains("CLAUDE_LOGIN")
            && listing.contains("openai")
    );
    assert!(!listing.contains(CLAUDE_TOKEN), "list shows metadata only");

    let out = run(dir, &["credential", "remove", "openai"], None, None);
    assert!(out.status.success());
    let out = run(dir, &["credential", "remove", "openai"], None, None);
    assert!(!out.status.success());
}

#[test]
fn codex_login_uses_a_dedicated_codex_home() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    // A fake `codex` that records its arguments and CODEX_HOME and writes auth.json.
    let bin = dir.join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    let fake = bin.join("codex");
    std::fs::write(
        &fake,
        "#!/bin/sh\necho \"$@\" > \"$CODEX_HOME/args.txt\"\n\
         printf '{\"OPENAI_API_KEY\":null,\"tokens\":{\"id_token\":\"id-TEST\",\"access_token\":\"access-TEST-ONLY\",\"refresh_token\":\"refresh-TEST-ONLY\",\"account_id\":\"a\"}}' > \"$CODEX_HOME/auth.json\"\n",
    )
    .unwrap();
    std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
    let path = format!("{}:/usr/bin:/bin", bin.display());

    let out = run(
        dir,
        &["credential", "add", "codex", "--label", "codex-main"],
        None,
        Some(&path),
    );
    assert!(out.status.success(), "{}", text(&out));
    let home = dir.join("data/codex-homes/codex-main");
    assert_eq!(mode(&home), 0o700);
    let args = std::fs::read_to_string(home.join("args.txt")).unwrap();
    assert!(args.starts_with("login"), "{args}");
    assert!(args.contains("cli_auth_credentials_store"));
    assert_eq!(mode(&home.join("auth.json")), 0o600);
    assert!(
        std::fs::read_to_string(home.join("config.toml"))
            .unwrap()
            .contains("\"file\"")
    );
    let stored: serde_json::Value = serde_json::from_slice(
        &std::fs::read(dir.join("config/credentials/codex-main.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(stored["kind"], "CODEX_CHATGPT_AUTH");
    assert_eq!(
        stored["poolSize"], 3,
        "the default pool, as for every credential"
    );
    assert!(stored.get("secret").is_none());

    // Without `codex` on PATH the command explains how to provide auth.json and fails.
    let tmp2 = tempfile::tempdir().unwrap();
    let out = run(
        tmp2.path(),
        &["credential", "add", "codex", "--label", "codex-main"],
        None,
        Some("/usr/bin:/bin"),
    );
    assert!(!out.status.success());
    let message = text(&out);
    assert!(
        message.contains("not installed") && message.contains("auth.json"),
        "{message}"
    );
}

#[test]
fn doctor_reports_failures() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let out = run(
        dir,
        &[
            "login",
            "--server",
            "http://127.0.0.1:9",
            "--token",
            HOST_TOKEN,
        ],
        None,
        None,
    );
    assert!(out.status.success());
    let out = run(
        dir,
        &["credential", "add", "anthropic-api-key", "--label", "key"],
        Some("not-a-valid-key-format-000000\n"),
        None,
    );
    assert!(
        out.status.success(),
        "a format warning does not block storing the key"
    );
    assert!(text(&out).contains("does not start with `sk-ant-api`"));
    let out = run(dir, &["doctor"], None, None);
    let report = text(&out);
    assert!(!out.status.success(), "{report}");
    assert!(report.contains("[ ok ] git"), "{report}");
    assert!(report.contains("[ ok ] mirrors dir"), "{report}");
    assert!(report.contains("[FAIL] credential key"), "{report}");
    assert!(report.contains("[FAIL] server"), "{report}");
    assert!(
        report.contains("[info] mirrors: 0 (0 B in total"),
        "{report}"
    );
    assert!(!report.contains(HOST_TOKEN));
    // A mirrors directory that others can read is flagged.
    let mirrors = dir.join("data/mirrors");
    std::fs::create_dir_all(&mirrors).unwrap();
    std::fs::set_permissions(&mirrors, std::fs::Permissions::from_mode(0o755)).unwrap();
    let report = text(&run(dir, &["doctor"], None, None));
    assert!(report.contains("[warn] mirrors dir"), "{report}");
    assert!(report.contains("mode 755"), "{report}");
}

#[test]
fn run_without_login_fails_clearly() {
    let tmp = tempfile::tempdir().unwrap();
    let out = run(tmp.path(), &["run"], None, None);
    assert!(!out.status.success());
    assert!(text(&out).contains("not logged in"));
}

#[test]
fn mirrors_list_and_prune() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let out = run(dir, &["mirrors", "list"], None, None);
    assert!(out.status.success(), "{}", text(&out));
    assert!(text(&out).contains("No mirrors in"), "{}", text(&out));

    // Two mirrors, as runs leave them: one last used 45 days ago, one just now.
    let idle = common::TestRepo::new();
    let fresh = common::TestRepo::new();
    let mirrors = Mirrors::new(dir.join("data/mirrors"));
    tokio::runtime::Runtime::new().unwrap().block_on(async {
        drop(mirrors.update(&idle.url, None).await.unwrap());
        drop(mirrors.update(&fresh.url, None).await.unwrap());
    });
    let idle_path = mirrors.path_for(&idle.url, None);
    let mut state = mirror::read_state(&idle_path).unwrap();
    let long_ago = Utc::now() - TimeDelta::days(45);
    state.last_used_at = Some(long_ago);
    mirror::write_state(&idle_path, &state).unwrap();
    let (idle_key, fresh_key) = (mirror_key(&idle.url, None), mirror_key(&fresh.url, None));

    let out = run(dir, &["mirrors", "list"], None, None);
    let listing = text(&out);
    assert!(out.status.success(), "{listing}");
    assert!(listing.contains("LAST USED"), "{listing}");
    let line = listing
        .lines()
        .find(|l| l.starts_with(&idle_key))
        .unwrap_or_else(|| panic!("{listing}"));
    assert!(line.contains(&idle.url), "{line}");
    assert!(
        line.contains(&long_ago.format("%Y-%m-%d %H:%M UTC").to_string()),
        "{line}"
    );
    assert!(line.contains("never"), "no gc yet: {line}");
    assert!(line.contains("KiB") || line.contains(" B "), "{line}");
    assert!(listing.contains(&fresh_key), "{listing}");
    assert!(listing.contains("2 mirror(s)"), "{listing}");

    let out = run(dir, &["mirrors", "prune", "--dry-run"], None, None);
    let report = text(&out);
    assert!(out.status.success(), "{report}");
    let line = report
        .lines()
        .find(|l| l.starts_with("would delete"))
        .unwrap_or_else(|| panic!("{report}"));
    assert!(
        line.contains(&idle_key) && line.contains("not used for 45 days"),
        "{line}"
    );
    assert!(!report.contains(&fresh_key), "{report}");
    assert!(
        report.contains("Dry run: would delete 1 mirror(s)"),
        "{report}"
    );
    assert!(idle_path.exists(), "a dry run deletes nothing");

    // A longer idle limit keeps it.
    let out = run(
        dir,
        &["mirrors", "prune", "--older-than-days", "60"],
        None,
        None,
    );
    assert!(out.status.success(), "{}", text(&out));
    assert!(text(&out).contains("Deleted 0 mirror(s)"), "{}", text(&out));
    assert!(idle_path.exists());

    // While `run` holds run.lock, pruning is refused (a dry run still works).
    std::fs::create_dir_all(dir.join("config")).unwrap();
    let lock = std::fs::File::create(dir.join("config/run.lock")).unwrap();
    lock.try_lock().unwrap();
    let out = run(dir, &["mirrors", "prune"], None, None);
    assert!(!out.status.success(), "{}", text(&out));
    assert!(
        text(&out).contains("`tinfirm-runner-host run` process is active"),
        "{}",
        text(&out)
    );
    assert!(idle_path.exists());
    let out = run(dir, &["mirrors", "prune", "--dry-run"], None, None);
    assert!(out.status.success(), "{}", text(&out));
    drop(lock);

    let out = run(dir, &["mirrors", "prune"], None, None);
    let report = text(&out);
    assert!(out.status.success(), "{report}");
    let line = report
        .lines()
        .find(|l| l.starts_with("deleted"))
        .unwrap_or_else(|| panic!("{report}"));
    assert!(line.contains(&idle_key), "{line}");
    assert!(report.contains("Deleted 1 mirror(s)"), "{report}");
    assert!(report.contains("1 mirror(s),"), "{report}");
    assert!(!idle_path.exists());
    assert!(mirrors.path_for(&fresh.url, None).exists());
    let listing = text(&run(dir, &["mirrors", "list"], None, None));
    assert!(
        !listing.contains(&idle_key) && listing.contains("1 mirror(s)"),
        "{listing}"
    );
}

#[test]
fn run_takes_up_to_16_concurrent_runs_with_docker_and_1000_with_a_vm_per_run() {
    let tmp = tempfile::tempdir().unwrap();
    for bad in ["0", "1001"] {
        let out = run(
            tmp.path(),
            &["run", "--max-concurrent-runs", bad],
            None,
            None,
        );
        assert!(!out.status.success());
        assert!(text(&out).contains("1..=1000"), "{}", text(&out));
    }
    // Above 16 only with a VM per run (D12): the machine runs every sandbox with Docker.
    let out = run(
        tmp.path(),
        &["run", "--max-concurrent-runs", "17"],
        None,
        None,
    );
    assert!(!out.status.success());
    assert!(
        text(&out).contains("at most 16 with Docker"),
        "{}",
        text(&out)
    );
    // 16 passes the check; this `run` then stops because it is not logged in.
    let out = run(
        tmp.path(),
        &["run", "--max-concurrent-runs", "16"],
        None,
        None,
    );
    assert!(!out.status.success());
    assert!(text(&out).contains("not logged in"), "{}", text(&out));
}

#[test]
fn run_takes_protocol_version_1_or_2() {
    let tmp = tempfile::tempdir().unwrap();
    for bad in ["0", "3", "two"] {
        let out = run(tmp.path(), &["run", "--protocol-version", bad], None, None);
        assert!(!out.status.success());
        assert!(
            text(&out).contains("1..=2") || text(&out).contains("invalid value"),
            "{}",
            text(&out)
        );
    }
    // 2 passes the check; this `run` then stops because it is not logged in.
    let out = run(tmp.path(), &["run", "--protocol-version", "2"], None, None);
    assert!(!out.status.success());
    assert!(text(&out).contains("not logged in"), "{}", text(&out));
    // The environment variable works the same way.
    let out = run_with_env(
        tmp.path(),
        &["run"],
        &[("TINFIRM_RUNNER_PROTOCOL_VERSION", "3")],
    );
    assert!(!out.status.success());
    assert!(text(&out).contains("1..=2"), "{}", text(&out));
}

/// Like [`run`], with extra environment variables.
fn run_with_env(dir: &Path, args: &[&str], env: &[(&str, &str)]) -> Output {
    let mut cmd = Command::new(BIN);
    cmd.args(args)
        .env("TINFIRM_RUNNER_CONFIG_DIR", dir.join("config"))
        .env("TINFIRM_RUNNER_DATA_DIR", dir.join("data"))
        .env("TINFIRM_RUNNER_LOG", "warn")
        .env_remove("TINFIRM_RUNNER_SANDBOX_RUNTIME")
        .env_remove("TINFIRM_RUNNER_SANDBOX_DOCKER")
        .env_remove("TINFIRM_RUNNER_SANDBOX_ENV")
        .env_remove("TINFIRM_RUNNER_PROTOCOL_VERSION")
        .env_remove("TINFIRM_RUNNER_MODEL_PROXY")
        .env_remove("TINFIRM_RUNNER_MODEL_PROXY_LOCAL_GATEWAY")
        .env_remove("TINFIRM_RUNNER_EXECUTOR")
        .env_remove("TINFIRM_RUNNER_VM_DRIVER")
        .env_remove("TINFIRM_RUNNER_VM_TEMPLATE")
        .env_remove("TINFIRM_RUNNER_VM_PROBE")
        .envs(env.iter().copied())
        .stdin(Stdio::null());
    cmd.output().unwrap()
}

#[test]
fn the_model_proxy_is_always_on_and_its_old_option_does_nothing() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    const IGNORED: &str = "--model-proxy (TINFIRM_RUNNER_MODEL_PROXY) has no effect";
    // The opt-in of 0.1.0, in any form a service file may have, also one that asks for "off":
    // accepted, with a warning that the proxy is always on. This `run` then stops because it is
    // not logged in.
    for (args, env) in [
        (&["run", "--model-proxy"][..], &[][..]),
        (&["run", "--model-proxy=false"][..], &[][..]),
        (&["run"][..], &[("TINFIRM_RUNNER_MODEL_PROXY", "1")][..]),
        (&["run"][..], &[("TINFIRM_RUNNER_MODEL_PROXY", "0")][..]),
        (
            &["run", "--model-proxy", "--max-concurrent-runs", "3"][..],
            &[][..],
        ),
    ] {
        let out = run_with_env(dir, args, env);
        assert!(!out.status.success(), "{args:?} {env:?}");
        let message = text(&out);
        assert!(
            message.contains("not logged in"),
            "{args:?} {env:?}: {message}"
        );
        assert!(
            message.contains(IGNORED) && message.contains("always on"),
            "{args:?} {env:?}: {message}"
        );
    }
    // Without it, no warning.
    let out = run_with_env(dir, &["run"], &[]);
    assert!(text(&out).contains("not logged in"), "{}", text(&out));
    assert!(!text(&out).contains(IGNORED), "{}", text(&out));
    // --egress-allow-private widens the model proxy's addresses, so it needs no other option.
    let out = run_with_env(dir, &["run", "--egress-allow-private"], &[]);
    assert!(text(&out).contains("not logged in"), "{}", text(&out));
    // --help says so.
    let out = run_with_env(dir, &["run", "--help"], &[]);
    assert!(out.status.success(), "{}", text(&out));
    let help = text(&out).split_whitespace().collect::<Vec<_>>().join(" ");
    assert!(
        help.contains(
            "Deprecated, no effect: the model proxy is always on, and nothing turns it off"
        ),
        "{help}"
    );
}

#[test]
fn the_model_proxys_local_gateway_is_checked_at_start() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    // An https origin passes the check, without the old --model-proxy too; this `run` then stops
    // because it is not logged in.
    for (args, env) in [
        (
            &[
                "run",
                "--model-proxy-local-gateway",
                "https://litellm.example.test:4000",
            ][..],
            &[][..],
        ),
        (
            &[
                "run",
                "--model-proxy",
                "--model-proxy-local-gateway",
                "https://litellm.example.test:4000",
            ][..],
            &[][..],
        ),
        (
            &["run"][..],
            &[(
                "TINFIRM_RUNNER_MODEL_PROXY_LOCAL_GATEWAY",
                "https://localhost:4000/",
            )][..],
        ),
    ] {
        let out = run_with_env(dir, args, env);
        assert!(!out.status.success());
        assert!(text(&out).contains("not logged in"), "{}", text(&out));
    }
    // Refused at once, before the login is read, without repeating the value.
    for (value, why) in [
        ("http://localhost:4000", "only over https"),
        ("https://localhost:4000/v1", "without a path"),
        (
            "https://ci:hunter2@localhost:4000",
            "no user name or password",
        ),
        ("localhost:4000", "only over https"),
        ("not an origin", "expected the gateway's https origin"),
    ] {
        let out = run_with_env(dir, &["run", "--model-proxy-local-gateway", value], &[]);
        assert!(!out.status.success(), "{value}");
        let message = text(&out);
        assert!(
            message.contains("--model-proxy-local-gateway: ") && message.contains(why),
            "{value}: {message}"
        );
        assert!(!message.contains("not logged in"), "{message}");
        assert!(
            !message.contains("hunter2") && !message.contains("localhost"),
            "{message}"
        );
    }
}

#[test]
fn docker_in_the_sandbox_is_refused_without_a_kata_runtime() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    for (args, env) in [
        (&["run", "--sandbox-docker"][..], &[][..]),
        (
            &["run", "--sandbox-docker", "--sandbox-runtime", "runc"][..],
            &[][..],
        ),
        (&["run"][..], &[("TINFIRM_RUNNER_SANDBOX_DOCKER", "1")][..]),
        (
            &["run"][..],
            &[
                ("TINFIRM_RUNNER_SANDBOX_DOCKER", "true"),
                ("TINFIRM_RUNNER_SANDBOX_RUNTIME", "runsc"),
            ][..],
        ),
    ] {
        let out = run_with_env(dir, args, env);
        assert!(!out.status.success(), "{args:?} {env:?}");
        let message = text(&out);
        // Refused at once, before the login is even read.
        assert!(
            message.contains("--sandbox-docker needs a Kata"),
            "{message}"
        );
        assert!(!message.contains("not logged in"), "{message}");
    }
    // Allowed combinations pass the check; this `run` then stops because it is not logged in.
    for (args, env) in [
        (
            &["run", "--sandbox-docker", "--sandbox-runtime", "kata"][..],
            &[][..],
        ),
        (
            &["run"][..],
            &[
                ("TINFIRM_RUNNER_SANDBOX_DOCKER", "1"),
                ("TINFIRM_RUNNER_SANDBOX_RUNTIME", "kata-clh"),
            ][..],
        ),
        (
            &["run"][..],
            &[("TINFIRM_RUNNER_SANDBOX_DOCKER", "false")][..],
        ),
        (&["run", "--sandbox-runtime", "runsc"][..], &[][..]),
    ] {
        let out = run_with_env(dir, args, env);
        assert!(!out.status.success());
        assert!(text(&out).contains("not logged in"), "{}", text(&out));
    }
}

#[test]
fn sandbox_env_is_checked() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let out = run_with_env(
        dir,
        &[
            "run",
            "--sandbox-env",
            "TINFIRM_SANDBOX_NPM_REGISTRY=https://nexus.example/repository/npm/",
            "--sandbox-env",
            "HTTPS_PROXY=http://proxy.example:3128",
        ],
        &[],
    );
    assert!(text(&out).contains("not logged in"), "{}", text(&out));
    // One entry per line in the environment variable; empty lines are skipped.
    let out = run_with_env(
        dir,
        &["run"],
        &[(
            "TINFIRM_RUNNER_SANDBOX_ENV",
            "TINFIRM_SANDBOX_PIP_INDEX=https://nexus.example/pypi/simple\nNO_PROXY=.internal\n",
        )],
    );
    assert!(text(&out).contains("not logged in"), "{}", text(&out));

    for (entry, why) in [
        ("NPM_TOKEN=npm_secret_value", "looks like a credential"),
        (
            "TINFIRM_SANDBOX_MAVEN_MIRROR=https://ci:hunter2@nexus.example/maven",
            "user name or password",
        ),
        ("TINFIRM_SANDBOX_DOCKER=1", "set by the runner host"),
        ("no-equals-sign", "expected KEY=VALUE"),
    ] {
        let out = run_with_env(dir, &["run", "--sandbox-env", entry], &[]);
        assert!(!out.status.success(), "{entry}");
        let message = text(&out);
        assert!(message.contains(why), "{entry}: {message}");
        assert!(
            !message.contains("hunter2") && !message.contains("npm_secret_value"),
            "{message}"
        );
    }
    let out = run_with_env(
        dir,
        &[
            "run",
            "--sandbox-env",
            "NO_PROXY=a",
            "--sandbox-env",
            "NO_PROXY=b",
        ],
        &[],
    );
    assert!(
        text(&out).contains("sets NO_PROXY more than once"),
        "{}",
        text(&out)
    );
}

#[test]
fn images_build_checks_its_build_args_before_docker() {
    let tmp = tempfile::tempdir().unwrap();
    // DOCKER_HOST points nowhere: the arguments are checked first.
    let env = [("DOCKER_HOST", "unix:///nonexistent/docker.sock")];
    let out = run_with_env(
        tmp.path(),
        &[
            "images",
            "build",
            "--build-arg",
            "NPM_REGISTERY=https://n.example/",
        ],
        &env,
    );
    assert!(!out.status.success());
    let message = text(&out);
    assert!(
        message.contains("no build argument NPM_REGISTERY"),
        "{message}"
    );
    assert!(message.contains("NPM_REGISTRY"), "{message}");
    let out = run_with_env(
        tmp.path(),
        &["images", "build", "--build-arg", "NPM_REGISTRY"],
        &env,
    );
    assert!(!out.status.success());
    assert!(text(&out).contains("expected KEY=VALUE"), "{}", text(&out));
    let out = run_with_env(
        tmp.path(),
        &[
            "images",
            "build",
            "--build-arg",
            "NPM_REGISTRY=https://ci:hunter2@nexus.example/npm/",
        ],
        &env,
    );
    assert!(!out.status.success());
    let message = text(&out);
    assert!(message.contains("user name or password"), "{message}");
    assert!(!message.contains("hunter2"), "{message}");
    // Valid arguments get as far as Docker.
    let out = run_with_env(
        tmp.path(),
        &[
            "images",
            "build",
            "--build-arg",
            "NPM_REGISTRY=https://nexus.example/repository/npm/",
            "--build-arg",
            "APT_MIRROR=http://nexus.example/repository",
        ],
        &env,
    );
    assert!(!out.status.success());
    assert!(!text(&out).contains("build argument"), "{}", text(&out));
}

#[test]
fn credential_pools_are_between_1_and_16() {
    let tmp = tempfile::tempdir().unwrap();
    for bad in ["0", "17"] {
        let out = run(
            tmp.path(),
            &[
                "credential",
                "add",
                "claude",
                "--label",
                "claude-main",
                "--pool",
                bad,
            ],
            Some(&format!("{CLAUDE_TOKEN}\n")),
            None,
        );
        assert!(!out.status.success());
        assert!(
            text(&out).contains("--pool must be between 1 and 16"),
            "{}",
            text(&out)
        );
    }
    assert!(
        !tmp.path()
            .join("config/credentials/claude-main.json")
            .exists()
    );
}

#[test]
fn drain_without_a_run_has_nothing_to_drain() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    for args in [&["drain"][..], &["drain", "--no-wait"][..]] {
        let out = run(dir, args, None, None);
        assert!(out.status.success(), "{}", text(&out));
        assert!(text(&out).contains("Nothing to drain"), "{}", text(&out));
    }
    // The run.lock of a `run` that has ended: nobody holds it, so no PID is read and no
    // signal is sent (this number is above every PID limit anyway).
    std::fs::create_dir_all(dir.join("config")).unwrap();
    std::fs::write(dir.join("config/run.lock"), "999999999\n").unwrap();
    let out = run(dir, &["drain"], None, None);
    assert!(out.status.success(), "{}", text(&out));
    assert!(text(&out).contains("Nothing to drain"), "{}", text(&out));
    // Held, but without a PID (as by `mirrors prune`): nothing is signalled.
    let lock = std::fs::File::create(dir.join("config/run.lock")).unwrap();
    lock.try_lock().unwrap();
    let out = run(dir, &["drain"], None, None);
    assert!(!out.status.success(), "{}", text(&out));
    assert!(text(&out).contains("holds no PID"), "{}", text(&out));
    drop(lock);
}

#[test]
fn bridge_is_hidden_and_needs_its_settings() {
    let tmp = tempfile::tempdir().unwrap();
    let help = run(tmp.path(), &["--help"], None, None);
    assert!(help.status.success());
    assert!(!text(&help).contains("bridge"), "{}", text(&help));
    // Outside a sandbox there are no TINFIRM_BRIDGE_* settings: the bridge says which.
    let out = Command::new(BIN)
        .arg("bridge")
        .env_remove("TINFIRM_BRIDGE_ADDR")
        .env_remove("TINFIRM_BRIDGE_RUN")
        .env_remove("TINFIRM_BRIDGE_TICKET")
        .env_remove("TINFIRM_BRIDGE_PIN")
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(70));
    assert!(
        text(&out).contains("TINFIRM_BRIDGE_ADDR is not set"),
        "{}",
        text(&out)
    );
    // A pin that is not one is refused before anything connects.
    let out = Command::new(BIN)
        .arg("bridge")
        .env("TINFIRM_BRIDGE_ADDR", "127.0.0.1:9")
        .env("TINFIRM_BRIDGE_RUN", "r")
        .env("TINFIRM_BRIDGE_TICKET", "t")
        .env("TINFIRM_BRIDGE_PIN", "not-a-pin")
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(70));
    assert!(text(&out).contains("TINFIRM_BRIDGE_PIN"), "{}", text(&out));
}

#[test]
fn the_vm_executor_needs_its_driver_and_template_and_no_docker_runtime() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    for (args, env, expected) in [
        (
            &["run", "--executor", "vm", "--vm-template", "sandbox-4c8g"][..],
            &[][..],
            "--executor vm needs --vm-driver",
        ),
        (
            &["run", "--executor", "vm", "--vm-driver", "digitalocean"][..],
            &[][..],
            "--executor vm needs --vm-template",
        ),
        (
            &["run"][..],
            &[
                ("TINFIRM_RUNNER_EXECUTOR", "vm"),
                ("TINFIRM_RUNNER_VM_DRIVER", "digitalocean"),
                ("TINFIRM_RUNNER_VM_TEMPLATE", "sandbox-4c8g"),
                ("TINFIRM_RUNNER_VM_PROBE", "udp:10.0.0.1:53"),
            ][..],
            "--vm-probe",
        ),
        (
            &[
                "run",
                "--executor",
                "vm",
                "--vm-driver",
                "digitalocean",
                "--vm-template",
                "sandbox-4c8g",
                "--sandbox-runtime",
                "kata",
            ][..],
            &[][..],
            "with --executor vm every sandbox is a VM of its own",
        ),
    ] {
        let out = run_with_env(dir, args, env);
        assert!(!out.status.success(), "{args:?} {env:?}");
        let message = text(&out);
        assert!(message.contains(expected), "{message}");
        // Refused at once, before the login is even read.
        assert!(!message.contains("not logged in"), "{message}");
    }
    // The agent's own Docker needs no Kata in a VM of its own: this `run` passes the checks,
    // then stops because it is not logged in.
    let out = run_with_env(
        dir,
        &[
            "run",
            "--executor",
            "vm",
            "--vm-driver",
            "digitalocean",
            "--vm-template",
            "sandbox-4c8g",
            "--sandbox-docker",
            "--vm-probe",
            "tcp:10.0.0.5:5432",
        ],
        &[],
    );
    assert!(!out.status.success());
    assert!(text(&out).contains("not logged in"), "{}", text(&out));
}

#[test]
fn vm_boot_refuses_user_data_that_is_not_tinfirms() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let data = dir.join("user-data");
    for (user_data, expected) in [
        ("#cloud-config\nruncmd: [id]\n", "not Tinfirm's"),
        (
            r#"{"tinfirm":1,"image":"tinfirm-runner-claude:local","env":{"CLAUDE_CODE_OAUTH_TOKEN":"x"}}"#,
            "not the bridge's nor the sandbox's",
        ),
    ] {
        std::fs::write(&data, user_data).unwrap();
        let out = run(
            dir,
            &["vm-boot", "--user-data", data.to_str().unwrap()],
            None,
            None,
        );
        assert!(!out.status.success());
        assert!(text(&out).contains(expected), "{}", text(&out));
    }
}
