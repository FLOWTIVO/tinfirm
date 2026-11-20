//! The Linux backend's execution, for real: jailed Firecracker VMs on this machine.
//!
//! These tests run only with `TINFIRM_NODE_KVM_TEST=1`, as root, on a machine with `/dev/kvm`;
//! otherwise each prints why it skips, and passes. They need:
//!
//! | Variable | What |
//! |---|---|
//! | `TINFIRM_NODE_KVM_JAILER`, `TINFIRM_NODE_KVM_FIRECRACKER` | The two binaries, root's; the second is named `firecracker` |
//! | `TINFIRM_NODE_KVM_KERNEL` | The guest kernel, a regular file of root's (not a link) |
//! | `TINFIRM_NODE_KVM_IMAGE` | The VM image of `runner/images/vm/local-disk.sh` |
//! | `TINFIRM_NODE_KVM_RUNNER_IMAGE` | A runner image that the VM image holds (`runnerImages` in `tinfirm-vm.json`) |
//!
//! and the tools `ip`, `nft`, `sysctl`, `losetup`, `dmsetup`, `blockdev`, `mknod`, `kill`,
//! `modprobe` and `python3`. They change the machine while they run: they load the kernel
//! modules a node needs, set `net.ipv4.ip_forward=1`, and make two network namespaces that
//! stand for the control host (`tfkvm-ctl`, 10.250.0.2) and for any other machine
//! (`tfkvm-3rd`, 10.250.0.6), each behind a veth of this machine. The namespaces and every VM
//! are removed again; the modules and the sysctl stay.
//!
//! `cargo test -p tinfirm-node-agent --test linux_kvm -- --nocapture` prints the timings.

use std::collections::BTreeSet;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::json;
use tinfirm_node_agent::backend::{Backend, BackendError};
use tinfirm_node_agent::helper::{Code, Helper, VmState};
use tinfirm_node_agent::id::{TemplateName, VmId};
use tinfirm_node_agent::linux::{self, LinuxBackend, Places, Step};
use tinfirm_node_agent::plan::{self, HelperConfig, Names, VmPlan};
use tinfirm_node_agent::{bounding, spool};

const TEMPLATE: &str = "kvm-test";
const CONTROL_NS: &str = "tfkvm-ctl";
const THIRD_NS: &str = "tfkvm-3rd";
const NODE: &str = "10.250.0.1";
const CONTROL: &str = "10.250.0.2";
const CGROUP_PARENT: &str = "tinfirm-kvm-test";

/// The names of a VM are unique on a node with one helper. Each test is a helper of its own,
/// so the tests run one at a time.
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

struct Paths {
    jailer: PathBuf,
    firecracker: PathBuf,
    kernel: PathBuf,
    image: PathBuf,
    runner_image: String,
}

/// The paths of a machine that can run these tests, or why it cannot.
fn paths() -> Result<Paths, String> {
    if std::env::var("TINFIRM_NODE_KVM_TEST").as_deref() != Ok("1") {
        return Err("TINFIRM_NODE_KVM_TEST is not 1".into());
    }
    if tinfirm_node_agent::local::own_uid().ok() != Some(0) {
        return Err("the test does not run as root".into());
    }
    if !Path::new("/dev/kvm").exists() {
        return Err("this machine has no /dev/kvm".into());
    }
    let var = |name: &str| std::env::var(name).map_err(|_| format!("{name} is not set"));
    Ok(Paths {
        jailer: var("TINFIRM_NODE_KVM_JAILER")?.into(),
        firecracker: var("TINFIRM_NODE_KVM_FIRECRACKER")?.into(),
        kernel: var("TINFIRM_NODE_KVM_KERNEL")?.into(),
        image: var("TINFIRM_NODE_KVM_IMAGE")?.into(),
        runner_image: var("TINFIRM_NODE_KVM_RUNNER_IMAGE")?,
    })
}

fn sh(argv: &[&str]) -> Result<String, String> {
    let output = Command::new(argv[0])
        .args(&argv[1..])
        .output()
        .map_err(|e| format!("{argv:?}: {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "{argv:?}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn must(argv: &[&str]) -> String {
    sh(argv).unwrap_or_else(|e| panic!("{e}"))
}

/// One test's node: a helper's directories of its own, and the two namespaces that stand for
/// the control host and for any other machine.
struct Node {
    _lock: std::sync::MutexGuard<'static, ()>,
    _tmp: tempfile::TempDir,
    config: HelperConfig,
    runner_image: String,
}

impl Node {
    fn start(max_vms: u32) -> Option<Node> {
        let paths = match paths() {
            Ok(paths) => paths,
            Err(why) => {
                eprintln!("skipped: {why}");
                return None;
            }
        };
        let lock = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
        remove_namespaces();
        must(&[
            "modprobe",
            "-a",
            "kvm_intel",
            "dm_snapshot",
            "veth",
            "tun",
            "nf_tables",
            "nft_ct",
            "nft_chain_nat",
            "nft_nat",
            "nf_nat",
        ]);
        must(&["sysctl", "-qw", "net.ipv4.ip_forward=1"]);
        for (ns, link, node, peer) in [
            (CONTROL_NS, "tfkvmc", NODE, CONTROL),
            (THIRD_NS, "tfkvm3", "10.250.0.5", "10.250.0.6"),
        ] {
            must(&["ip", "netns", "add", ns]);
            must(&[
                "ip", "link", "add", link, "type", "veth", "peer", "name", "eth0", "netns", ns,
            ]);
            must(&["ip", "addr", "add", &format!("{node}/30"), "dev", link]);
            must(&["ip", "link", "set", link, "up"]);
            must(&["ip", "-n", ns, "link", "set", "lo", "up"]);
            must(&[
                "ip",
                "-n",
                ns,
                "addr",
                "add",
                &format!("{peer}/30"),
                "dev",
                "eth0",
            ]);
            must(&["ip", "-n", ns, "link", "set", "eth0", "up"]);
            must(&["ip", "-n", ns, "route", "add", "default", "via", node]);
        }
        let tmp = tempfile::Builder::new()
            .prefix("tinfirm-kvm-test.")
            .tempdir_in("/var/tmp")
            .unwrap();
        let root = tmp.path();
        std::fs::set_permissions(root, std::fs::Permissions::from_mode(0o755)).unwrap();
        for (name, mode) in [
            ("jails", 0o755),
            ("work", 0o700),
            ("state", 0o700),
            ("spool", 0o700),
            ("socket", 0o750),
        ] {
            std::fs::create_dir(root.join(name)).unwrap();
            std::fs::set_permissions(root.join(name), std::fs::Permissions::from_mode(mode))
                .unwrap();
        }
        let mut config: HelperConfig = serde_json::from_value(json!({
            "listenerUid": 0,
            "socket": root.join("socket/helper.sock"),
            "spool": root.join("spool"),
            "state": root.join("state"),
            "maxVms": max_vms,
            "vmUidBase": 1_300_000,
            "network": {
                "vmRange": "10.251.0.0/24",
                "nodeAddress": NODE,
                "controlHost": CONTROL,
                "firstPort": 21000
            },
            "firecracker": {
                "jailer": paths.jailer,
                "firecracker": paths.firecracker,
                "chrootBase": root.join("jails"),
                "cgroupParent": CGROUP_PARENT,
                "work": root.join("work"),
                "guestConsole": true
            },
            "templates": {
                TEMPLATE: {
                    "vcpus": 2,
                    "memoryMib": 2048,
                    "snapshotMib": 4096,
                    "image": paths.image,
                    "kernel": paths.kernel
                }
            }
        }))
        .unwrap();
        config.allow_root_listener = true;
        config.validate().unwrap();
        Some(Node {
            _lock: lock,
            _tmp: tmp,
            config,
            runner_image: paths.runner_image,
        })
    }

    fn template(&self) -> plan::Template {
        self.config.templates.values().next().unwrap().clone()
    }

    fn plan(&self, slot: u32, serial: u64) -> VmPlan {
        plan::plan(&self.config, &self.template(), slot, serial)
    }

    fn backend(&self) -> LinuxBackend {
        // The helper starts its own binary to cut the bounding set; here that is the agent's.
        LinuxBackend::new(self.config.clone())
            .with_starter(env!("CARGO_BIN_EXE_tinfirm-node-agent"))
    }

    /// User data as the runner host writes it: the bridge listens for the host, with a
    /// ticket and a pin that are no one's.
    fn user_data(&self, run: &str) -> Vec<u8> {
        serde_json::to_vec(&json!({
            "tinfirm": 1,
            "image": self.runner_image,
            "env": {
                "TINFIRM_BRIDGE_LISTEN": "0.0.0.0:7443",
                "TINFIRM_BRIDGE_RUN": run,
                "TINFIRM_BRIDGE_TICKET": "a-ticket-of-the-kvm-test",
                "TINFIRM_BRIDGE_PIN": "00".repeat(32),
            }
        }))
        .unwrap()
    }

    /// The listener's part of a make: the user data in the spool, under a new id.
    fn spooled(&self, run: &str) -> VmId {
        let id = VmId::generate().unwrap();
        let path = spool::user_data_path(&self.config.spool, &id);
        std::fs::write(&path, self.user_data(run)).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        id
    }

    fn places(&self, names: &Names) -> Places {
        linux::places(&self.config.firecracker, names)
    }
}

fn remove_namespaces() {
    for (ns, link) in [(CONTROL_NS, "tfkvmc"), (THIRD_NS, "tfkvm3")] {
        let _ = sh(&["ip", "netns", "del", ns]);
        let _ = sh(&["ip", "link", "del", link]);
    }
}

impl Drop for Node {
    fn drop(&mut self) {
        // Whatever a failed test left: every VM of this node's jails directory.
        let backend = self.backend();
        for present in backend.list().unwrap_or_default() {
            if let Some(names) = present.names {
                let _ = backend.delete(&names);
            }
        }
        remove_namespaces();
        let _ = std::fs::remove_dir(Path::new("/sys/fs/cgroup").join(CGROUP_PARENT));
    }
}

/// What a TCP connection and a TLS client hello get from `address:port`, from a namespace or
/// from this machine itself: `tls …` (a TLS server answered), `tcp` (a connection, no TLS),
/// `refused`, or `nothing` (no answer within 3 s, or no route).
fn probe(ns: Option<&str>, address: &str, port: u16) -> String {
    const SCRIPT: &str = r#"
import socket, ssl, sys
s = socket.socket(); s.settimeout(3)
try:
    s.connect((sys.argv[1], int(sys.argv[2])))
except ConnectionRefusedError:
    print("refused"); sys.exit()
except OSError:
    print("nothing"); sys.exit()
c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE
try:
    print("tls", c.wrap_socket(s).version())
except ssl.SSLError:
    print("tls alert")
except OSError:
    print("tcp")
"#;
    let port = port.to_string();
    let mut argv = vec![];
    if let Some(ns) = ns {
        argv.extend(["ip", "netns", "exec", ns]);
    }
    argv.extend(["python3", "-c", SCRIPT, address, &port]);
    sh(&argv).unwrap_or_else(|e| format!("probe failed: {e}"))
}

/// Waits until the control host gets a TLS answer on the node's port; the time it took.
fn guest_ready(port: u16) -> Duration {
    let start = Instant::now();
    loop {
        if probe(Some(CONTROL_NS), NODE, port).starts_with("tls") {
            return start.elapsed();
        }
        assert!(
            start.elapsed() < Duration::from_secs(120),
            "the guest's bridge did not answer the control host within 120 s"
        );
        std::thread::sleep(Duration::from_millis(250));
    }
}

fn cgroup_pids(places: &Places) -> Vec<u32> {
    std::fs::read_to_string(places.cgroup.join("cgroup.procs"))
        .map(|text| text.lines().filter_map(|l| l.parse().ok()).collect())
        .unwrap_or_default()
}

fn names_in(dir: &str, prefix: &str, file: &str) -> Vec<String> {
    std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().starts_with(prefix))
        .filter_map(|e| std::fs::read_to_string(e.path().join(file)).ok())
        .map(|text| text.trim().to_string())
        .collect()
}

/// Everything of a VM that is still on the machine, each kind looked for by its name.
fn left_of(node: &Node, names: &Names) -> Vec<String> {
    let places = node.places(names);
    let uid = (node.config.vm_uid_base + names.slot).to_string();
    let mut left = Vec::new();
    if !cgroup_pids(&places).is_empty() {
        left.push("processes in its cgroup".to_string());
    }
    if sh(&["pgrep", "-u", &uid]).is_ok() {
        left.push(format!("processes of uid {uid}"));
    }
    for (what, path) in [
        ("cgroup", places.cgroup.clone()),
        ("network namespace", places.netns.clone()),
        ("snapshot file", places.snapshot_file.clone()),
        ("console file", places.console.clone()),
        ("jail", places.jail_dir.clone()),
        ("veth", Path::new("/sys/class/net").join(names.veth())),
        (
            "device node",
            Path::new("/dev/mapper").join(names.snapshot()),
        ),
    ] {
        if path.symlink_metadata().is_ok() {
            left.push(format!("{what} {}", path.display()));
        }
    }
    if names_in("/sys/block", "dm-", "dm/name").contains(&names.snapshot()) {
        left.push(format!("device-mapper device {}", names.snapshot()));
    }
    if names_in("/sys/block", "loop", "loop/backing_file")
        .iter()
        .any(|backing| linux::is_backing_file(backing, &places.snapshot_file))
    {
        left.push("loop device of the snapshot file".to_string());
    }
    if must(&["nft", "list", "tables"]).contains(&names.table()) {
        left.push(format!("firewall table {}", names.table()));
    }
    if must(&["ip", "netns", "list"]).contains(&names.jail()) {
        left.push(format!("namespace {} in `ip netns list`", names.jail()));
    }
    left
}

fn status_of(pid: u32) -> String {
    std::fs::read_to_string(format!("/proc/{pid}/status")).unwrap()
}

fn status_field(status: &str, name: &str) -> String {
    status
        .lines()
        .find_map(|line| line.strip_prefix(name)?.strip_prefix(':'))
        .map(|rest| rest.split_whitespace().collect::<Vec<_>>().join(" "))
        .unwrap_or_default()
}

#[test]
fn a_made_vm_runs_jailed_answers_only_its_control_host_and_a_delete_leaves_nothing() {
    let Some(node) = Node::start(4) else { return };
    let backend = node.backend();
    let checked = backend.check(&node.template());
    assert_eq!(checked.problems, Vec::<String>::new(), "{checked:?}");

    let plan = node.plan(1, 0x10);
    let places = node.places(&plan.names);
    let start = Instant::now();
    backend
        .make(&plan, &node.user_data("run-of-the-kvm-test"))
        .unwrap();
    let made = start.elapsed();

    // The VMM: the slot's own user, no capability, the jailer's six as its bounding set.
    let pids = cgroup_pids(&places);
    assert_eq!(pids.len(), 1, "one process in the VM's cgroup: {pids:?}");
    let pid = pids[0];
    let status = status_of(pid);
    eprintln!(
        "the VMM, pid {pid}: Name {}; Uid {}; Gid {}; CapEff {}; CapPrm {}; CapBnd {}; NoNewPrivs {}; Seccomp {}",
        status_field(&status, "Name"),
        status_field(&status, "Uid"),
        status_field(&status, "Gid"),
        status_field(&status, "CapEff"),
        status_field(&status, "CapPrm"),
        status_field(&status, "CapBnd"),
        status_field(&status, "NoNewPrivs"),
        status_field(&status, "Seccomp"),
    );
    assert_eq!(status_field(&status, "Name"), "firecracker");
    assert_eq!(
        status_field(&status, "Uid"),
        "1300001 1300001 1300001 1300001"
    );
    assert_eq!(
        status_field(&status, "Gid"),
        "1300001 1300001 1300001 1300001"
    );
    assert_eq!(status_field(&status, "CapEff"), "0000000000000000");
    assert_eq!(status_field(&status, "CapPrm"), "0000000000000000");
    assert_eq!(status_field(&status, "CapBnd"), "00000000082000c3");
    assert_eq!(
        u64::from_str_radix(&status_field(&status, "CapBnd"), 16).unwrap(),
        bounding::jailer_bounding_set()
    );
    assert_eq!(status_field(&status, "NoNewPrivs"), "1");
    assert_eq!(status_field(&status, "Seccomp"), "2");
    assert_eq!(status_field(&status, "Groups"), "");

    // Its namespaces, its root and its cgroup.
    let link = |pid: &str, ns: &str| {
        std::fs::read_link(format!("/proc/{pid}/ns/{ns}"))
            .unwrap()
            .display()
            .to_string()
    };
    let vmm = pid.to_string();
    let netns_inode = std::fs::metadata(&places.netns).unwrap().ino();
    assert_eq!(link(&vmm, "net"), format!("net:[{netns_inode}]"));
    for ns in ["net", "pid", "mnt"] {
        assert_ne!(link(&vmm, ns), link("self", ns), "its own {ns} namespace");
    }
    // In its PID namespace the VMM is process 1, and sees no other.
    assert_eq!(status_field(&status, "NSpid"), format!("{pid} 1"));
    // The jailer pivots into the chroot, so the VMM's root is `/` of a mount namespace that
    // holds the chroot's entries and nothing of the machine.
    let entries = |dir: &Path| -> BTreeSet<String> {
        std::fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect()
    };
    let vmm_root = entries(Path::new(&format!("/proc/{pid}/root")));
    assert_eq!(vmm_root, entries(&places.chroot));
    assert!(
        !vmm_root.contains("etc") && !vmm_root.contains("proc"),
        "{vmm_root:?}"
    );
    let cgroup = std::fs::read_to_string(format!("/proc/{pid}/cgroup")).unwrap();
    assert_eq!(
        cgroup.trim(),
        format!("0::/{CGROUP_PARENT}/{}", plan.names.jail())
    );
    let limit = |file: &str| {
        std::fs::read_to_string(places.cgroup.join(file))
            .unwrap()
            .trim()
            .to_string()
    };
    assert_eq!(limit("cpu.max"), "200000 100000");
    assert_eq!(
        limit("memory.max"),
        ((2048u64 + 128) * 1024 * 1024).to_string()
    );
    assert_eq!(limit("memory.swap.max"), "0");
    assert_eq!(limit("pids.max"), "64");

    // What the jail holds, and whose it is.
    let mut inside: Vec<String> = std::fs::read_dir(&places.chroot)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    inside.sort();
    assert_eq!(
        inside,
        [
            "dev",
            "firecracker",
            "firecracker.pid",
            "root.disk",
            "run",
            "userdata.disk",
            "vm.json",
            "vmlinux"
        ]
    );
    for name in ["root.disk", "userdata.disk", "vm.json", "vmlinux"] {
        let meta = std::fs::symlink_metadata(places.chroot.join(name)).unwrap();
        assert_eq!(
            (meta.uid(), meta.mode() & 0o7777),
            (1_300_001, 0o600),
            "{name}"
        );
    }
    let jail = std::fs::symlink_metadata(&places.jail_dir).unwrap();
    assert_eq!((jail.uid(), jail.mode() & 0o7777), (0, 0o700));
    for file in [&places.snapshot_file, &places.console] {
        let meta = std::fs::symlink_metadata(file).unwrap();
        assert_eq!((meta.uid(), meta.mode() & 0o7777), (0, 0o600));
    }

    // It is listed, alive.
    let listed = backend.list().unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].names, Some(plan.names));
    assert!(listed[0].alive);

    // The guest answers the control host on the published port, and nobody else anywhere.
    let ready = guest_ready(plan.published_port);
    let guest = plan.addresses.guest.to_string();
    let veth_vm = plan.addresses.veth_vm.to_string();
    let port = plan.published_port;
    let answers = [
        (
            "the control host, the published port",
            probe(Some(CONTROL_NS), NODE, port),
        ),
        (
            "the control host, the next port",
            probe(Some(CONTROL_NS), NODE, port + 1),
        ),
        (
            "the control host, the guest's own address",
            probe(Some(CONTROL_NS), &guest, 7443),
        ),
        (
            "the control host, the namespace's address",
            probe(Some(CONTROL_NS), &veth_vm, 7443),
        ),
        (
            "another machine, the published port",
            probe(Some(THIRD_NS), NODE, port),
        ),
        (
            "another machine, the guest's own address",
            probe(Some(THIRD_NS), &guest, 7443),
        ),
        (
            "another machine, the namespace's address",
            probe(Some(THIRD_NS), &veth_vm, 7443),
        ),
        (
            "the node itself, the published port",
            probe(None, NODE, port),
        ),
        (
            "the node itself, the namespace's address",
            probe(None, &veth_vm, 7443),
        ),
    ];
    for (who, answer) in &answers {
        eprintln!("{who}: {answer}");
    }
    assert!(answers[0].1.starts_with("tls"), "{answers:?}");
    for (who, answer) in &answers[1..] {
        assert!(
            answer == "refused" || answer == "nothing",
            "{who} got `{answer}`"
        );
    }

    // The console: the kernel got its address and the disk's place on its command line, and
    // `vm-boot` started the run that only the user-data disk names.
    let console = String::from_utf8_lossy(&std::fs::read(&places.console).unwrap()).into_owned();
    for wanted in [
        "tinfirm.user-data=disk:/dev/vdb",
        "IP-Config: Complete:",
        "ipaddr=10.251.0.14, mask=255.255.255.252, gw=10.251.0.13",
        "for run run-of-the-kvm-test",
    ] {
        assert!(console.contains(wanted), "the console lacks `{wanted}`");
    }
    for line in console.lines().filter(|l| l.contains("vm-boot")) {
        eprintln!("console: {line}");
    }

    let start = Instant::now();
    backend.delete(&plan.names).unwrap();
    let deleted = start.elapsed();
    assert_eq!(left_of(&node, &plan.names), Vec::<String>::new());
    assert_eq!(backend.list().unwrap(), []);
    assert!(std::fs::metadata(format!("/proc/{pid}")).is_err());
    assert_ne!(
        probe(Some(CONTROL_NS), NODE, port).split(' ').next(),
        Some("tls")
    );
    // A second delete, of a VM that is gone, is no error.
    backend.delete(&plan.names).unwrap();
    eprintln!(
        "timings: make {} ms, guest ready {} ms after the make, delete {} ms",
        made.as_millis(),
        ready.as_millis(),
        deleted.as_millis()
    );
}

#[test]
fn a_make_that_fails_at_any_step_leaves_nothing() {
    let Some(node) = Node::start(4) else { return };
    for (i, step) in Step::ALL.into_iter().enumerate() {
        let plan = node.plan(0, 0x20 + i as u64);
        let backend = node.backend().failing_after(step, false);
        let error = backend.make(&plan, &node.user_data("fails")).unwrap_err();
        assert!(
            matches!(&error, BackendError::Failed(why) if why.contains(&format!("{step:?}")) && !why.contains("left")),
            "{step:?}: {error}"
        );
        assert_eq!(
            left_of(&node, &plan.names),
            Vec::<String>::new(),
            "{step:?}"
        );
        assert_eq!(backend.list().unwrap(), [], "{step:?}");
        // The helper deletes once more after a failed make: no error.
        backend.delete(&plan.names).unwrap();
    }
}

#[test]
fn a_killed_vmm_is_listed_as_not_alive_and_its_delete_cleans_up() {
    let Some(node) = Node::start(4) else { return };
    let backend = node.backend();
    let plan = node.plan(2, 0x30);
    let places = node.places(&plan.names);
    backend.make(&plan, &node.user_data("killed")).unwrap();
    assert!(backend.list().unwrap()[0].alive);
    let pid = cgroup_pids(&places)[0];
    must(&["kill", "-KILL", &pid.to_string()]);
    let start = Instant::now();
    while backend.list().unwrap()[0].alive {
        assert!(start.elapsed() < Duration::from_secs(5), "still alive");
        std::thread::sleep(Duration::from_millis(10));
    }
    // The VM is still there, and listed: its jail, its namespace, its snapshot.
    let listed = backend.list().unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].names, Some(plan.names));
    let left = left_of(&node, &plan.names);
    assert!(left.iter().any(|l| l.starts_with("jail ")), "{left:?}");
    assert!(
        left.iter().any(|l| l.starts_with("device-mapper")),
        "{left:?}"
    );
    backend.delete(&plan.names).unwrap();
    assert_eq!(left_of(&node, &plan.names), Vec::<String>::new());
    assert_eq!(backend.list().unwrap(), []);
}

#[test]
fn a_helper_started_over_what_a_crash_left_removes_it_or_keeps_its_slot() {
    let Some(node) = Node::start(4) else { return };
    let template = TemplateName::parse(TEMPLATE.as_bytes()).unwrap();
    // A jail without a record, in each state a helper killed in a make can leave.
    for (i, step) in Step::ALL.into_iter().enumerate() {
        let plan = node.plan(1, 0x40 + i as u64);
        let crashed = node.backend().failing_after(step, true);
        crashed.make(&plan, &node.user_data("crashed")).unwrap_err();
        let left = left_of(&node, &plan.names);
        assert!(
            left.iter().any(|l| l.starts_with("jail ")),
            "{step:?}: {left:?}"
        );
        let listed = crashed.list().unwrap();
        assert_eq!(listed.len(), 1, "{step:?}");
        assert_eq!(listed[0].alive, step == Step::Vmm, "{step:?}");

        let helper = Helper::start(node.config.clone(), node.backend()).unwrap();
        assert_eq!(
            left_of(&node, &plan.names),
            Vec::<String>::new(),
            "{step:?}"
        );
        let checked = helper.check(&template).unwrap();
        assert_eq!(checked.problems, Vec::<String>::new(), "{step:?}");
        assert_eq!(checked.free, 4, "{step:?}");
    }

    // One that cannot be removed: something holds its snapshot device open.
    let plan = node.plan(1, 0x50);
    node.backend()
        .failing_after(Step::Disks, true)
        .make(&plan, &node.user_data("held"))
        .unwrap_err();
    // The helper does not wait for udev, which makes this node a moment after the device.
    let node_of_device = Path::new("/dev/mapper").join(plan.names.snapshot());
    let start = Instant::now();
    while !node_of_device.exists() && start.elapsed() < Duration::from_secs(5) {
        std::thread::sleep(Duration::from_millis(10));
    }
    let holder = std::fs::File::open(&node_of_device).unwrap();
    let helper = Helper::start(node.config.clone(), node.backend()).unwrap();
    let left = left_of(&node, &plan.names);
    assert!(
        left.iter().any(|l| l.starts_with("device-mapper")),
        "{left:?}"
    );
    // The jail stays, so the VM stays listed; its slot is not handed out.
    assert!(left.iter().any(|l| l.starts_with("jail ")), "{left:?}");
    let checked = helper.check(&template).unwrap();
    assert!(
        checked
            .problems
            .iter()
            .any(|p| p.contains("cannot be removed")),
        "{checked:?}"
    );
    assert_eq!(checked.free, 3);
    let direct = node.backend().delete(&plan.names).unwrap_err();
    assert!(
        matches!(&direct, BackendError::Failed(why) if why.contains("the device-mapper device tinfirm-tfvm-000000000050-0001")),
        "{direct}"
    );
    // A VM made meanwhile gets another slot and a later serial.
    let id = node.spooled("beside-a-leftover");
    helper.make(&id, &template).unwrap();
    let present = node.backend().list().unwrap();
    let other = present
        .iter()
        .filter_map(|p| p.names)
        .find(|n| *n != plan.names)
        .unwrap();
    assert_eq!(other.slot, 0);
    assert!(other.serial > plan.names.serial);
    helper.delete(&id).unwrap();
    assert_eq!(left_of(&node, &other), Vec::<String>::new());

    drop(holder);
    let checked = helper.check(&template).unwrap();
    assert_eq!(checked.problems, Vec::<String>::new());
    assert_eq!(checked.free, 4);
    assert_eq!(left_of(&node, &plan.names), Vec::<String>::new());
}

#[test]
fn two_vms_at_once_share_nothing_and_the_node_holds_no_more_than_max_vms() {
    let Some(node) = Node::start(2) else { return };
    let template = TemplateName::parse(TEMPLATE.as_bytes()).unwrap();
    let helper = Helper::start(node.config.clone(), node.backend()).unwrap();
    let (a, b) = (node.spooled("first-of-two"), node.spooled("second-of-two"));
    let port_a = helper.make(&a, &template).unwrap().port;
    let port_b = helper.make(&b, &template).unwrap().port;
    assert_eq!((port_a, port_b), (21000, 21001));

    let third = node.spooled("one-too-many");
    assert_eq!(helper.make(&third, &template).unwrap_err().code, Code::Full);
    assert_eq!(helper.make(&a, &template).unwrap_err().code, Code::Exists);

    let listed = helper.list().unwrap();
    assert_eq!(listed.free, 0);
    assert_eq!(listed.vms.len(), 2);
    assert!(listed.vms.iter().all(|vm| vm.state == VmState::Running));

    let present = node.backend().list().unwrap();
    let names: Vec<Names> = present.iter().filter_map(|p| p.names).collect();
    assert_eq!(names.len(), 2);
    let mut seen = BTreeSet::new();
    let mut vmms = Vec::new();
    for n in &names {
        let places = node.places(n);
        let plan = node.plan(n.slot, n.serial);
        let pid = cgroup_pids(&places)[0];
        let status = status_of(pid);
        vmms.push((pid, plan.uid, places.clone()));
        // Each of these is one VM's alone: a second insert of any would fail.
        for own in [
            format!("uid {}", status_field(&status, "Uid")),
            format!("slot {}", n.slot),
            format!("serial {}", n.serial),
            format!("port {}", plan.published_port),
            format!("address {}", plan.addresses.guest),
            format!("address {}", plan.addresses.tap),
            format!("address {}", plan.addresses.veth_vm),
            format!("address {}", plan.addresses.veth_node),
            format!("jail {}", places.jail_dir.display()),
            format!("cgroup {}", places.cgroup.display()),
            format!("netns {}", std::fs::metadata(&places.netns).unwrap().ino()),
            format!("snapshot {}", places.snapshot_file.display()),
            format!(
                "root disk {}",
                std::fs::metadata(places.chroot.join("root.disk"))
                    .unwrap()
                    .rdev()
            ),
            format!("table {}", n.table()),
            format!("veth {}", n.veth()),
            format!(
                "pid namespace {}",
                std::fs::read_link(format!("/proc/{pid}/ns/pid"))
                    .unwrap()
                    .display()
            ),
        ] {
            assert!(seen.insert(own.clone()), "two VMs share {own}");
        }
        assert_eq!(
            status_field(&status, "Uid").split(' ').next().unwrap(),
            plan.uid.to_string()
        );
    }
    // One VM's user cannot read the other's jail, disk or user data.
    let (_, uid_a, _) = &vmms[0];
    let (_, _, places_b) = &vmms[1];
    for path in [
        places_b.chroot.clone(),
        places_b.chroot.join("userdata.disk"),
        places_b.chroot.join("root.disk"),
        places_b.snapshot_file.clone(),
    ] {
        let uid = uid_a.to_string();
        let tried = sh(&[
            "setpriv",
            "--reuid",
            &uid,
            "--regid",
            &uid,
            "--clear-groups",
            "--inh-caps=-all",
            "--bounding-set=-all",
            "head",
            "-c",
            "1",
            &path.display().to_string(),
        ]);
        assert!(
            tried.is_err(),
            "{} is readable by the other VM's user",
            path.display()
        );
    }

    // Both guests answer the control host, each on its own port.
    guest_ready(port_a);
    guest_ready(port_b);

    helper.delete(&a).unwrap();
    // A slot is free again, and the VM that stays is untouched.
    assert_eq!(helper.list().unwrap().free, 1);
    assert!(probe(Some(CONTROL_NS), NODE, port_b).starts_with("tls"));
    helper.make(&third, &template).unwrap();
    helper.delete(&b).unwrap();
    helper.delete(&third).unwrap();
    for n in &names {
        assert_eq!(left_of(&node, n), Vec::<String>::new());
    }
    assert_eq!(node.backend().list().unwrap(), []);
    assert_eq!(helper.list().unwrap().free, 2);
}

#[test]
fn a_guest_without_the_console_setting_writes_nothing_to_the_node() {
    let Some(mut node) = Node::start(4) else {
        return;
    };
    node.config.firecracker.guest_console = false;
    let backend = node.backend();
    let plan = node.plan(3, 0x60);
    backend.make(&plan, &node.user_data("quiet")).unwrap();
    guest_ready(plan.published_port);
    let console = std::fs::read(node.places(&plan.names).console).unwrap();
    let console = String::from_utf8_lossy(&console);
    assert!(
        !console.contains("Linux version") && !console.contains("vm-boot"),
        "{console}"
    );
    backend.delete(&plan.names).unwrap();
    assert_eq!(left_of(&node, &plan.names), Vec::<String>::new());
}
