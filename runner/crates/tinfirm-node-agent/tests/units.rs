//! The hardening's unit and the VMM's AppArmor profile against the code they serve: the
//! unit runs `harden` on its configuration, in its order, and only wanted by the helper; the
//! profile names the profile the helper checks for, and every file the VMM is given by
//! `src/linux.rs`. Neither can be loaded here: they are read as text.

use tinfirm_node_agent::{harden, hardening, linux};

const HARDENING: &str = include_str!("../units/tinfirm-node-agent-hardening.service");
const HELPER: &str = include_str!("../units/tinfirm-node-agent-helper.service");
const LISTENER: &str = include_str!("../units/tinfirm-node-agent-listener.service");
const PROFILE: &str = include_str!("../units/tinfirm-vmm.apparmor");

/// The words of a unit's directive, all lines of it together, comments left out.
fn directive(unit: &str, name: &str) -> Vec<String> {
    unit.lines()
        .filter(|l| !l.trim_start().starts_with('#'))
        .filter_map(|l| l.strip_prefix(&format!("{name}=")))
        .flat_map(|v| v.split_whitespace().map(String::from))
        .collect()
}

#[test]
fn the_hardening_unit_runs_harden_once_after_the_modules_firewall_and_kdump() {
    assert_eq!(
        directive(HARDENING, "ExecStart"),
        [
            "/usr/local/bin/tinfirm-node-agent",
            "harden",
            "--config",
            harden::CONFIG
        ]
    );
    assert_eq!(directive(HARDENING, "Type"), ["oneshot"]);
    let after = directive(HARDENING, "After");
    for unit in [
        "tinfirm-node-agent-modules.service",
        "tinfirm-node-agent-firewall.service",
        "kdump-tools.service",
        "kdump.service",
    ] {
        assert!(after.contains(&unit.to_string()), "{unit}");
    }
    assert_eq!(
        directive(HARDENING, "Before"),
        ["tinfirm-node-agent-helper.service"]
    );
    // The settings it writes stay writable.
    for name in ["ProtectKernelTunables", "ProtectKernelModules"] {
        assert!(directive(HARDENING, name).is_empty(), "{name}");
    }
}

#[test]
fn the_hardening_waits_for_the_network_and_does_not_require_it() {
    // Module loading goes off only once the network, which loads modules of its own, is up.
    let network = "network-online.target".to_string();
    assert!(directive(HARDENING, "After").contains(&network));
    // The target is passive: without `Wants=` nothing would wait for the network.
    assert!(directive(HARDENING, "Wants").contains(&network));
    // A network that does not come up delays the hardening and does not fail it.
    for name in ["Requires", "Requisite", "BindsTo"] {
        assert!(directive(HARDENING, name).is_empty(), "{name}");
    }
    // With the default dependencies the unit is also after sysinit.target, and so after
    // systemd-modules-load.service: a site's own list in /etc/modules-load.d is loaded first.
    assert!(directive(HARDENING, "DefaultDependencies").is_empty());
}

#[test]
fn the_hardening_waits_a_bounded_time_for_the_devices_and_runs_anyway() {
    // udev's events of the devices found at boot, with the modules their rules load, before
    // module loading goes off; for 60 s at most, and a wait that fails stops nothing (`-`).
    assert_eq!(
        directive(HARDENING, "ExecStartPre"),
        ["-/usr/bin/udevadm", "settle", "--timeout=60"]
    );
    // This unit alone waits: not the whole boot, as the deprecated settle service would.
    for name in ["Wants", "Requires", "After"] {
        assert!(
            !directive(HARDENING, name).contains(&"systemd-udev-settle.service".to_string()),
            "{name}"
        );
    }
    // A oneshot has no start timeout by default; the bound is the settle's own.
    assert!(directive(HARDENING, "TimeoutStartSec").is_empty());
    // udevadm talks to udev over its control socket under /run/udev: AF_UNIX stays allowed.
    assert_eq!(directive(HARDENING, "RestrictAddressFamilies"), ["AF_UNIX"]);
}

#[test]
fn the_helper_wants_the_hardening_and_does_not_require_it() {
    let unit = "tinfirm-node-agent-hardening.service".to_string();
    assert!(directive(HELPER, "Wants").contains(&unit));
    assert!(directive(HELPER, "After").contains(&unit));
    assert!(!directive(HELPER, "Requires").contains(&unit));
    assert!(!directive(HELPER, "BindsTo").contains(&unit));
}

#[test]
fn the_listener_is_started_again_after_a_failure_however_often() {
    assert_eq!(directive(LISTENER, "Restart"), ["on-failure"]);
    // In [Unit]: the service manager ignores the setting in [Service].
    let (unit, service) = LISTENER.split_once("[Service]").unwrap();
    assert_eq!(directive(unit, "StartLimitIntervalSec"), ["0"]);
    assert!(directive(service, "StartLimitIntervalSec").is_empty());
    // With no limit to the starts, a fault that lasts is not tried again every second.
    assert_eq!(directive(service, "RestartSec"), ["5"]);
}

#[test]
fn the_profile_names_the_two_directories_that_a_required_profile_asks_of_the_configuration() {
    use tinfirm_node_agent::plan::{PROFILE_CHROOT_BASE, PROFILE_WORK};
    // `HelperConfig::validate` refuses `vmmProfile` `required` under other directories,
    // because these two are written into the profile.
    assert!(PROFILE.contains(&format!("{PROFILE_WORK}/tfvm-*.console w,")));
    assert!(PROFILE.contains(&format!("{PROFILE_WORK}/tfvm-*.errors w,")));
    assert!(PROFILE.contains(&format!(
        "{}/firecracker/tfvm-*/root/",
        PROFILE_CHROOT_BASE.trim_start_matches('/')
    )));
}

#[test]
fn the_profile_is_the_one_the_helper_checks_for_and_names_every_file_the_vmm_is_given() {
    let rules: Vec<&str> = PROFILE
        .lines()
        .map(str::trim)
        .filter(|l| !l.starts_with('#') && !l.is_empty())
        .collect();
    let head = rules
        .iter()
        .find(|l| l.starts_with("profile "))
        .expect("a profile");
    assert!(
        head.starts_with(&format!("profile {} ", hardening::PROFILE)),
        "{head}"
    );
    assert!(head.contains("flags=(attach_disconnected)"), "{head}");
    for (path, access) in [
        ("/firecracker", "mr"),
        (linux::KERNEL, "r"),
        (linux::ROOT_DISK, "rw"),
        (linux::USER_DATA_DISK, "r"),
        (linux::VM_CONFIG, "r"),
        (linux::VMM_LOG, "rw"),
        (linux::API_SOCKET, "rw"),
        ("/dev/kvm", "rw"),
        ("/dev/net/tun", "rw"),
    ] {
        let rule = format!("{path} {access},");
        assert!(rules.contains(&rule.as_str()), "{rule}");
    }
    // No capability, and nothing of /proc or /sys.
    for rule in &rules {
        assert!(!rule.starts_with("capability"), "{rule}");
        assert!(
            !rule.starts_with("/proc") && !rule.starts_with("/sys") && !rule.starts_with("@{PROC}"),
            "{rule}"
        );
    }
}
