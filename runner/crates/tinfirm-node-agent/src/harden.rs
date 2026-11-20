//! `tinfirm-node-agent harden`: the three lines of the hardening mark that hold only until a
//! reboot (ADR-0113, decision 5), set at each boot by `units/tinfirm-node-agent-hardening.service`
//! from `hardening.json`, which the role writes from its switches.
//!
//! In this order: kexec off, lockdown at `integrity`, module loading off. kexec comes first so
//! that the unit, ordered after kdump's, leaves a crash kernel loaded; module loading comes
//! last, and only once every module the agent needs is there, since nothing loads one after it.
//!
//! The plan is a pure function of the configuration and the current values ([`steps`]); a
//! thin part writes it ([`perform`]). Both work under a root path, `/` on a node. A step says,
//! in one line, what it found and what it did. A second run finds everything set, writes
//! nothing, and ends with success. The helper itself never writes any of these.

use std::io::{Read, Write};
use std::path::Path;

use anyhow::{Context, Result, bail};
use serde::Deserialize;

use crate::linux::{BUILT_IN_COPY, MODULES, module_present};

/// The configuration's place on a node.
pub const CONFIG: &str = "/etc/tinfirm/node-agent/hardening.json";
/// The settings, under the root.
pub const KEXEC: &str = "proc/sys/kernel/kexec_load_disabled";
pub const LOCKDOWN: &str = "sys/kernel/security/lockdown";
pub const MODULES_DISABLED: &str = "proc/sys/kernel/modules_disabled";
/// Modules the agent needs beyond [`MODULES`]: the helper finds these by their devices
/// (`/dev/loop-control`, `/dev/net/tun`), whose nodes are there before the module is loaded and
/// load it when first opened. Once module loading is off, that load fails.
pub const DEVICE_MODULES: [&str; 2] = ["loop", "tun"];
/// KVM for the processor that is there: one of the two.
pub const KVM_VENDOR: [&str; 2] = ["kvm_intel", "kvm_amd"];

/// The lockdown the configuration asks for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WantedLockdown {
    /// Left as it is.
    None,
    /// `integrity` at least: written only when the kernel is at `none`.
    Integrity,
}

/// `hardening.json`: one switch for each line. Exactly these fields.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HardenConfig {
    pub modules_disabled: bool,
    pub kexec_disabled: bool,
    pub lockdown: WantedLockdown,
}

impl HardenConfig {
    /// Reads the configuration: one JSON object with each key once, and exactly the three
    /// fields.
    pub fn parse(bytes: &[u8]) -> Result<HardenConfig> {
        let map = crate::strict::object(bytes)
            .map_err(|_| anyhow::anyhow!("not one JSON object with each key once"))?;
        serde_json::from_value(serde_json::Value::Object(map))
            .context("not `modulesDisabled`, `kexecDisabled` and `lockdown` (\"integrity\" or \"none\") and nothing else")
    }

    /// Reads the file: a regular file of root's (or this process's own user's, where a test
    /// runs it) that neither its group nor others can write, as the helper's configuration.
    pub fn load(path: &Path) -> Result<HardenConfig> {
        use std::os::unix::fs::MetadataExt;
        let file =
            std::fs::File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
        let meta = file
            .metadata()
            .with_context(|| format!("cannot read {}", path.display()))?;
        let own = crate::local::own_uid()?;
        if let Err(problem) = crate::plan::check_config_file(
            meta.file_type().is_file(),
            meta.uid(),
            meta.mode() & 0o7777,
            own,
        ) {
            bail!("the hardening's configuration {} {problem}", path.display());
        }
        let mut bytes = Vec::new();
        file.take(64 * 1024 + 1)
            .read_to_end(&mut bytes)
            .with_context(|| format!("cannot read {}", path.display()))?;
        if bytes.len() > 64 * 1024 {
            bail!("{} is over 64 KiB", path.display());
        }
        HardenConfig::parse(&bytes).with_context(|| format!("{} is refused", path.display()))
    }
}

/// The kernel's lockdown level, lowest first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Level {
    None,
    Integrity,
    Confidentiality,
}

impl Level {
    fn word(self) -> &'static str {
        match self {
            Level::None => "none",
            Level::Integrity => "integrity",
            Level::Confidentiality => "confidentiality",
        }
    }
}

/// What the machine shows now; each value, or why it cannot be read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Current {
    pub kexec_load_disabled: Result<i64, String>,
    /// `Ok(None)`: the kernel has no lockdown.
    pub lockdown: Result<Option<Level>, String>,
    pub modules_disabled: Result<i64, String>,
    /// The modules the agent needs that are neither loaded nor built in.
    pub missing_modules: Result<Vec<String>, String>,
}

fn read(root: &Path, path: &str) -> std::io::Result<String> {
    let mut text = String::new();
    std::fs::File::open(root.join(path))?
        .take(64 * 1024)
        .read_to_string(&mut text)?;
    Ok(text)
}

fn number(root: &Path, path: &str) -> Result<i64, String> {
    let text = read(root, path).map_err(|e| format!("/{path} cannot be read ({})", e.kind()))?;
    text.trim().parse().map_err(|_| {
        format!(
            "/{path} is \"{}\", not a number",
            text.trim().escape_debug().take(32).collect::<String>()
        )
    })
}

/// The level in brackets of `/sys/kernel/security/lockdown` (`none [integrity] confidentiality`).
pub fn lockdown_level(text: &str) -> Option<Level> {
    match text
        .split_whitespace()
        .find_map(|w| w.strip_prefix('[')?.strip_suffix(']'))?
    {
        "none" => Some(Level::None),
        "integrity" => Some(Level::Integrity),
        "confidentiality" => Some(Level::Confidentiality),
        _ => None,
    }
}

/// Reads what [`steps`] decides by, under `root`.
pub fn read_current(root: &Path) -> Current {
    let lockdown = match read(root, LOCKDOWN) {
        Ok(text) => lockdown_level(&text).map(Some).ok_or_else(|| {
            format!(
                "/{LOCKDOWN} is \"{}\", with no level in brackets",
                text.trim().escape_debug().take(64).collect::<String>()
            )
        }),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(format!("/{LOCKDOWN} cannot be read ({})", e.kind())),
    };
    Current {
        kexec_load_disabled: number(root, KEXEC),
        lockdown,
        modules_disabled: number(root, MODULES_DISABLED),
        missing_modules: missing_modules(root),
    }
}

/// The modules the agent needs that are neither loaded nor built into the running kernel, by
/// the kernel's list of built-in modules in `/lib/modules/<release>` or the copy that the
/// modules' unit makes.
fn missing_modules(root: &Path) -> Result<Vec<String>, String> {
    let release = read(root, "proc/sys/kernel/osrelease")
        .map(|r| r.trim().to_string())
        .unwrap_or_default();
    let places = [
        format!("lib/modules/{release}/modules.builtin"),
        BUILT_IN_COPY.trim_start_matches('/').to_string(),
    ];
    // A kernel may ship no list: then a module counts only when it is loaded.
    let built_in = places
        .iter()
        .find_map(|place| read(root, place).ok())
        .unwrap_or_default();
    let sys_module = root.join("sys/module");
    if !sys_module.is_dir() {
        return Err("/sys/module cannot be read: which modules are loaded is not known".into());
    }
    let there = |name: &str| module_present(name, &sys_module, &built_in);
    let mut missing: Vec<String> = MODULES
        .iter()
        .chain(DEVICE_MODULES.iter())
        .filter(|name| !there(name))
        .map(|name| name.to_string())
        .collect();
    if !KVM_VENDOR.iter().any(|name| there(name)) {
        missing.push(KVM_VENDOR.join(" or "));
    }
    Ok(missing)
}

/// One step of the plan.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Step {
    /// Nothing to write: what was found.
    Leave(String),
    /// A write of `value` to `path` under the root; `says` is the line that tells it.
    Write {
        path: &'static str,
        value: &'static str,
        says: String,
    },
    /// Not done, and why: `harden` ends with an error.
    Refuse(String),
}

/// The plan: kexec, then lockdown, then module loading, one step each.
pub fn steps(config: &HardenConfig, current: &Current) -> Vec<Step> {
    let name = "kernel.kexec_load_disabled";
    let kexec = match (&current.kexec_load_disabled, config.kexec_disabled) {
        (Ok(1), true) => Step::Leave(format!("{name} is 1: nothing to do")),
        (Ok(v), true) => Step::Write {
            path: KEXEC,
            value: "1",
            says: format!("{name} is {v}: set to 1"),
        },
        (Err(why), true) => Step::Refuse(format!("{name} not set: {why}")),
        (Ok(v), false) => Step::Leave(format!(
            "{name} is {v}: left as it is (kexecDisabled is false)"
        )),
        (Err(why), false) => Step::Leave(format!(
            "{name} left as it is (kexecDisabled is false): {why}"
        )),
    };
    let lockdown = match (&current.lockdown, config.lockdown) {
        (Ok(Some(level)), WantedLockdown::Integrity) if *level >= Level::Integrity => {
            Step::Leave(format!(
                "lockdown is {}: nothing to do (integrity or higher)",
                level.word()
            ))
        }
        // The kernel answers EPERM to a write of the level it is at, and refuses a lower one.
        (Ok(Some(level)), WantedLockdown::Integrity) => Step::Write {
            path: LOCKDOWN,
            value: "integrity",
            says: format!("lockdown is {}: set to integrity", level.word()),
        },
        (Ok(None), WantedLockdown::Integrity) => Step::Refuse(format!(
            "lockdown not set: the kernel has no lockdown (no /{LOCKDOWN})"
        )),
        (Err(why), WantedLockdown::Integrity) => Step::Refuse(format!("lockdown not set: {why}")),
        (Ok(Some(level)), WantedLockdown::None) => Step::Leave(format!(
            "lockdown is {}: left as it is (lockdown is \"none\")",
            level.word()
        )),
        (Ok(None), WantedLockdown::None) => {
            Step::Leave("the kernel has no lockdown: left as it is (lockdown is \"none\")".into())
        }
        (Err(why), WantedLockdown::None) => Step::Leave(format!(
            "lockdown left as it is (lockdown is \"none\"): {why}"
        )),
    };
    let name = "kernel.modules_disabled";
    let modules = match (&current.modules_disabled, config.modules_disabled) {
        (Ok(1), true) => Step::Leave(format!("{name} is 1: nothing to do")),
        (Ok(v), true) => match &current.missing_modules {
            Ok(missing) if missing.is_empty() => Step::Write {
                path: MODULES_DISABLED,
                value: "1",
                says: format!("{name} is {v}: every module the agent needs is there; set to 1"),
            },
            Ok(missing) => Step::Refuse(format!(
                "{name} left at {v}: the module(s) {} are neither loaded nor built in, and nothing could load them after it (the modules' unit loads them at boot)",
                missing.join(", ")
            )),
            Err(why) => Step::Refuse(format!("{name} left at {v}: {why}")),
        },
        (Err(why), true) => Step::Refuse(format!("{name} not set: {why}")),
        (Ok(v), false) => Step::Leave(format!(
            "{name} is {v}: left as it is (modulesDisabled is false)"
        )),
        (Err(why), false) => Step::Leave(format!(
            "{name} left as it is (modulesDisabled is false): {why}"
        )),
    };
    vec![kexec, lockdown, modules]
}

/// Performs the plan under `root`, in its order, and returns one line for each step: what it
/// found and what it did. A failed write does not stop the steps after it; the result is
/// `false` when a step was refused or a write failed.
pub fn perform(root: &Path, steps: &[Step]) -> (Vec<String>, bool) {
    let mut lines = Vec::new();
    let mut ok = true;
    for step in steps {
        match step {
            Step::Leave(found) => lines.push(found.clone()),
            Step::Refuse(why) => {
                ok = false;
                lines.push(format!("refused: {why}"));
            }
            Step::Write { path, value, says } => {
                // The file is the kernel's: opened to write, never made.
                let written = std::fs::OpenOptions::new()
                    .write(true)
                    .open(root.join(path))
                    .and_then(|mut file| file.write_all(value.as_bytes()));
                match written {
                    Ok(()) => lines.push(says.clone()),
                    Err(e) => {
                        ok = false;
                        lines.push(format!("failed: {says}: /{path}: {}", e.kind()));
                    }
                }
            }
        }
    }
    (lines, ok)
}

/// What was written must read back as set. `again` is the plan made after the writes of
/// `planned`: for each step that wrote, the same step must now have nothing to do. One line
/// for each that would write again, and for each whose setting cannot be read back.
pub fn not_taken(planned: &[Step], again: &[Step]) -> Vec<String> {
    planned
        .iter()
        .zip(again)
        .filter(|(first, _)| matches!(first, Step::Write { .. }))
        .filter_map(|(_, second)| match second {
            Step::Leave(_) => None,
            Step::Write { says, .. } => Some(format!("did not take: {says}")),
            Step::Refuse(why) => Some(format!("written, but it cannot be read back: {why}")),
        })
        .collect()
}

/// `tinfirm-node-agent harden`: reads the configuration, plans, writes, and checks that each
/// write took by planning again. Each line goes to standard output, the unit's journal.
pub fn harden(root: &Path, config: &HardenConfig) -> Result<()> {
    let planned = steps(config, &read_current(root));
    let (lines, mut ok) = perform(root, &planned);
    for line in &lines {
        println!("{line}");
    }
    let wrote = planned.iter().any(|s| matches!(s, Step::Write { .. }));
    if wrote {
        for line in not_taken(&planned, &steps(config, &read_current(root))) {
            ok = false;
            println!("{line}");
        }
    }
    if !ok {
        bail!("the node is not hardened as hardening.json asks: the lines above say why");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: HardenConfig = HardenConfig {
        modules_disabled: true,
        kexec_disabled: true,
        lockdown: WantedLockdown::Integrity,
    };

    fn current(kexec: i64, lockdown: Option<Level>, modules: i64, missing: &[&str]) -> Current {
        Current {
            kexec_load_disabled: Ok(kexec),
            lockdown: Ok(lockdown),
            modules_disabled: Ok(modules),
            missing_modules: Ok(missing.iter().map(|m| m.to_string()).collect()),
        }
    }

    fn writes(steps: &[Step]) -> Vec<(&str, &str)> {
        steps
            .iter()
            .filter_map(|s| match s {
                Step::Write { path, value, .. } => Some((*path, *value)),
                _ => None,
            })
            .collect()
    }

    fn refusals(steps: &[Step]) -> Vec<&str> {
        steps
            .iter()
            .filter_map(|s| match s {
                Step::Refuse(why) => Some(why.as_str()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn a_fresh_boot_gets_all_three_in_order_and_a_second_run_writes_nothing() {
        let s = steps(&ALL, &current(0, Some(Level::None), 0, &[]));
        assert_eq!(
            writes(&s),
            [
                (KEXEC, "1"),
                (LOCKDOWN, "integrity"),
                (MODULES_DISABLED, "1")
            ]
        );
        let s = steps(&ALL, &current(1, Some(Level::Integrity), 1, &[]));
        assert_eq!(writes(&s), []);
        assert_eq!(refusals(&s), Vec::<&str>::new());
        assert!(
            s.iter()
                .all(|s| matches!(s, Step::Leave(l) if l.contains("nothing to do")))
        );
    }

    #[test]
    fn lockdown_is_written_only_when_it_is_lower_than_integrity() {
        for (level, written) in [
            (Level::None, true),
            (Level::Integrity, false),
            (Level::Confidentiality, false),
        ] {
            let s = steps(&ALL, &current(1, Some(level), 1, &[]));
            assert_eq!(
                writes(&s).contains(&(LOCKDOWN, "integrity")),
                written,
                "{level:?}"
            );
        }
        let s = steps(&ALL, &current(1, Some(Level::Confidentiality), 1, &[]));
        assert_eq!(
            s[1],
            Step::Leave("lockdown is confidentiality: nothing to do (integrity or higher)".into())
        );
        // A kernel without lockdown: refused, and the other two still done.
        let s = steps(&ALL, &current(0, None, 0, &[]));
        assert_eq!(writes(&s), [(KEXEC, "1"), (MODULES_DISABLED, "1")]);
        assert_eq!(
            refusals(&s),
            ["lockdown not set: the kernel has no lockdown (no /sys/kernel/security/lockdown)"]
        );
    }

    #[test]
    fn module_loading_stays_on_while_a_module_is_missing_and_names_it() {
        let s = steps(&ALL, &current(0, Some(Level::None), 0, &["nft_nat", "tun"]));
        assert_eq!(writes(&s), [(KEXEC, "1"), (LOCKDOWN, "integrity")]);
        let refused = refusals(&s);
        assert_eq!(refused.len(), 1);
        assert!(refused[0].contains("nft_nat, tun"), "{refused:?}");
        // Already off: nothing is asked of the modules.
        let s = steps(&ALL, &current(1, Some(Level::Integrity), 1, &["nft_nat"]));
        assert_eq!(refusals(&s), Vec::<&str>::new());
        // A list that cannot be read is no proof that the modules are there.
        let mut c = current(1, Some(Level::Integrity), 0, &[]);
        c.missing_modules = Err("/sys/module cannot be read".into());
        assert_eq!(refusals(&steps(&ALL, &c)).len(), 1);
    }

    #[test]
    fn each_switch_off_leaves_its_setting_as_it_is() {
        let fresh = current(0, Some(Level::None), 0, &["kvm"]);
        let off = HardenConfig {
            modules_disabled: false,
            kexec_disabled: false,
            lockdown: WantedLockdown::None,
        };
        let s = steps(&off, &fresh);
        assert_eq!(writes(&s), []);
        assert_eq!(refusals(&s), Vec::<&str>::new());
        for (config, left) in [
            (
                HardenConfig {
                    kexec_disabled: false,
                    ..ALL
                },
                KEXEC,
            ),
            (
                HardenConfig {
                    lockdown: WantedLockdown::None,
                    ..ALL
                },
                LOCKDOWN,
            ),
            (
                HardenConfig {
                    modules_disabled: false,
                    ..ALL
                },
                MODULES_DISABLED,
            ),
        ] {
            let s = steps(&config, &current(0, Some(Level::None), 0, &[]));
            let written: Vec<&str> = writes(&s).iter().map(|(p, _)| *p).collect();
            assert_eq!(written.len(), 2, "{config:?}");
            assert!(!written.contains(&left), "{config:?}");
        }
        // Even unreadable, a setting that is switched off is no refusal.
        let unreadable = Current {
            kexec_load_disabled: Err("x".into()),
            lockdown: Err("y".into()),
            modules_disabled: Err("z".into()),
            missing_modules: Err("w".into()),
        };
        assert_eq!(refusals(&steps(&off, &unreadable)), Vec::<&str>::new());
        assert_eq!(refusals(&steps(&ALL, &unreadable)).len(), 3);
    }

    #[test]
    fn the_configuration_is_exactly_its_three_fields() {
        assert_eq!(
            HardenConfig::parse(
                br#"{"modulesDisabled": true, "kexecDisabled": true, "lockdown": "integrity"}"#
            )
            .unwrap(),
            ALL
        );
        assert_eq!(
            HardenConfig::parse(
                br#"{"modulesDisabled": false, "kexecDisabled": false, "lockdown": "none"}"#
            )
            .unwrap()
            .lockdown,
            WantedLockdown::None
        );
        for bad in [
            &br#"{"modulesDisabled": true, "kexecDisabled": true}"#[..],
            br#"{"modulesDisabled": true, "kexecDisabled": true, "lockdown": "confidentiality"}"#,
            br#"{"modulesDisabled": true, "kexecDisabled": true, "lockdown": "Integrity"}"#,
            br#"{"modulesDisabled": "yes", "kexecDisabled": true, "lockdown": "none"}"#,
            br#"{"modulesDisabled": true, "kexecDisabled": true, "lockdown": "none", "x": 1}"#,
            br#"{"modulesDisabled": true, "modulesDisabled": false, "kexecDisabled": true, "lockdown": "none"}"#,
            b"[]",
            b"",
        ] {
            assert!(
                HardenConfig::parse(bad).is_err(),
                "{}",
                String::from_utf8_lossy(bad)
            );
        }
    }

    #[test]
    fn a_configuration_file_others_can_write_is_refused() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("hardening.json");
        std::fs::write(
            &path,
            br#"{"modulesDisabled": true, "kexecDisabled": true, "lockdown": "integrity"}"#,
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert_eq!(HardenConfig::load(&path).unwrap(), ALL);
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o666)).unwrap();
        let e = HardenConfig::load(&path).unwrap_err();
        assert!(format!("{e:#}").contains("0666"), "{e:#}");
    }

    /// A machine under a temporary root, with what `harden` reads and writes.
    fn machine(kexec: &str, lockdown: &str, modules: &str) -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let put = |path: &str, text: &str| {
            let path = root.join(path);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, text).unwrap();
        };
        put(KEXEC, kexec);
        put(LOCKDOWN, lockdown);
        put(MODULES_DISABLED, modules);
        put("proc/sys/kernel/osrelease", "6.8.0-49-generic\n");
        for module in MODULES
            .iter()
            .chain(DEVICE_MODULES.iter())
            .chain(["kvm_amd"].iter())
        {
            std::fs::create_dir_all(root.join("sys/module").join(module)).unwrap();
        }
        dir
    }

    #[test]
    fn the_modules_are_read_from_sys_module_and_the_built_in_list() {
        let dir = machine("0\n", "[none] integrity confidentiality\n", "0\n");
        let root = dir.path();
        assert_eq!(read_current(root).missing_modules, Ok(vec![]));
        std::fs::remove_dir(root.join("sys/module/tun")).unwrap();
        std::fs::remove_dir(root.join("sys/module/kvm_amd")).unwrap();
        assert_eq!(
            read_current(root).missing_modules,
            Ok(vec!["tun".to_string(), "kvm_intel or kvm_amd".to_string()])
        );
        // Built in, by the copy of the modules' unit.
        let copy = root.join(BUILT_IN_COPY.trim_start_matches('/'));
        std::fs::create_dir_all(copy.parent().unwrap()).unwrap();
        std::fs::write(
            &copy,
            "kernel/drivers/net/tun.ko\nkernel/arch/x86/kvm/kvm-amd.ko\n",
        )
        .unwrap();
        assert_eq!(read_current(root).missing_modules, Ok(vec![]));
    }

    #[test]
    fn a_write_that_does_not_read_back_as_set_is_a_failure() {
        let write = |says: &str| Step::Write {
            path: KEXEC,
            value: "1",
            says: says.into(),
        };
        let planned = [
            write("kexec"),
            Step::Leave("lockdown".into()),
            write("modules"),
        ];
        // Every write took.
        let taken = [
            Step::Leave("a".into()),
            Step::Leave("b".into()),
            Step::Leave("c".into()),
        ];
        assert_eq!(not_taken(&planned, &taken), Vec::<String>::new());
        // One would be written again, and one cannot be read any more.
        let again = [
            write("kexec is 0: set to 1"),
            Step::Leave("b".into()),
            Step::Refuse("kernel.modules_disabled not set: not a number".into()),
        ];
        assert_eq!(
            not_taken(&planned, &again),
            [
                "did not take: kexec is 0: set to 1",
                "written, but it cannot be read back: kernel.modules_disabled not set: not a number",
            ]
        );
        // A step that wrote nothing is not judged here: its own line said what it found.
        let refused = [
            Step::Leave("a".into()),
            Step::Refuse("lockdown not set".into()),
            Step::Leave("c".into()),
        ];
        assert_eq!(not_taken(&planned, &refused), Vec::<String>::new());
    }

    #[test]
    fn harden_writes_what_is_planned_and_a_second_run_finds_it_set() {
        // Lockdown is at integrity already: a plain file stands in for the kernel's, which
        // would show the written level in brackets.
        let dir = machine("0\n", "none [integrity] confidentiality\n", "0\n");
        let root = dir.path();
        harden(root, &ALL).unwrap();
        // The kernel's files take a write without truncating; here the newline stays.
        let value = |path: &str| std::fs::read_to_string(root.join(path)).unwrap();
        assert_eq!(value(KEXEC).trim(), "1");
        assert_eq!(value(MODULES_DISABLED).trim(), "1");
        let again = steps(&ALL, &read_current(root));
        assert_eq!(writes(&again), []);
        harden(root, &ALL).unwrap();

        // A write that does not take (the file stands in for a kernel that ignores it) is an
        // error; so is a refusal, after the other steps were done.
        let dir = machine("0\n", "[none] integrity confidentiality\n", "0\n");
        let root = dir.path();
        std::fs::remove_dir(root.join("sys/module/veth")).unwrap();
        let e = harden(root, &ALL).unwrap_err();
        assert!(e.to_string().contains("not hardened"), "{e}");
        let value = |path: &str| std::fs::read_to_string(root.join(path)).unwrap();
        assert_eq!(value(KEXEC).trim(), "1");
        assert_eq!(value(MODULES_DISABLED).trim(), "0");
        // A setting whose file is not there is never made.
        let dir = machine("0\n", "[integrity]\n", "1\n");
        let root = dir.path();
        std::fs::remove_file(root.join(KEXEC)).unwrap();
        assert!(harden(root, &ALL).is_err());
        assert!(!root.join(KEXEC).exists());
    }

    #[test]
    fn perform_says_one_line_for_each_step() {
        let dir = machine("0\n", "[none]\n", "0\n");
        let (lines, ok) = perform(
            dir.path(),
            &[
                Step::Leave("found".into()),
                Step::Write {
                    path: KEXEC,
                    value: "1",
                    says: "kernel.kexec_load_disabled is 0: set to 1".into(),
                },
                Step::Write {
                    path: "proc/sys/kernel/no_such_setting",
                    value: "1",
                    says: "no such setting: set to 1".into(),
                },
                Step::Refuse("why".into()),
            ],
        );
        assert!(!ok);
        assert_eq!(
            lines,
            [
                "found",
                "kernel.kexec_load_disabled is 0: set to 1",
                "failed: no such setting: set to 1: /proc/sys/kernel/no_such_setting: entity not found",
                "refused: why"
            ]
        );
    }
}
