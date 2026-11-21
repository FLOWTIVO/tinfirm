//! The host's level of isolation (ADR-0113, decisions 1 to 3): the strongest wall between an
//! agent's code and the credentials, and whether the machine that runs the host's VMs is
//! hardened. With protocol 2 the host reports the level as the one value of
//! `hello.isolationTiers`, with `hello.hardened` and `hello.isolationStated`; with protocol 1 it
//! reports `container`, as before. It says at its start and in `doctor` what an escape from a
//! sandbox reaches on a host of that level (ADR-0112, decision 7).
//!
//! The host derives the level from what it can see, not from a setting, wherever it can
//! ([`derive`]):
//!
//! | Sandboxes | `isolationTiers` | `hardened` | `isolationStated` |
//! |---|---|---|---|
//! | Docker with runc or a `kata…` runtime | `container` | left out (`no`) | left out |
//! | Docker with a `runsc…` runtime | `gvisor` | left out | left out |
//! | A VM per run through a provider's driver of this binary (`digitalocean`) | `machine` | `none` | left out |
//! | The `node` driver (crate::node), every node over its local socket | `vm-jail` | `yes` when the nodes report it, else left out (`no`) | left out |
//! | The `node` driver, every node over TLS at an address that is not this host's own | `machine` | `yes` when the nodes report it, else left out (`no`) | left out |
//! | The `pod` driver (crate::kubernetes), whose configuration states that the workers for runs hold nothing else | `machine` | `no` | `true` |
//! | The operator's own driver, with `--isolation-level` (and `--isolation-hardened`) | the stated level | the stated value | `true` |
//! | The operator's own driver, without a stated level | `container` | left out | left out |
//! | The local test executor | `container` | left out | left out |
//!
//! A Kata host reports `container`: its VMM is root on the machine that holds the credentials.
//! An operator's own driver without a statement is a VM host shown as a container host until
//! its operator states the level; the host says so at its start and in `doctor`
//! ([`unstated`]). A statement never overrides what the host can see: [`derive`] refuses one
//! where the host derives its own level. The `node` driver's level comes from its
//! configuration ([`crate::node::NodeConfig::isolation`]), the one file the driver reads too; a
//! configuration that mixes sockets and TLS, or reaches this host's own address over TLS, is
//! refused. Its hardening (ADR-0113, decision 5) is what the nodes report: the node agent's
//! helper checks each line of a hardened machine, and the driver's `verify` says `yes` only
//! when every node that offers the host's template says `yes`. The host keeps the last value
//! the driver knew ([`crate::vm::Vms::hardening`]); at its start a node that does not answer
//! makes it `no`. The hardening an operator's own driver sends is not read: the operator's
//! statement stands for it.
//!
//! The `pod` driver's level rests on the operator's statement in its configuration
//! (`isolationStatement`, crate::kubernetes::config): the driver keeps every run pod off the
//! runner pod's worker, which the host can rely on, but whether the workers for runs hold
//! nothing else, and what their kubelet's credential and cloud identity open, it cannot see
//! (ADR-0115, decisions 1 and 6). A configuration without the statement does not load, so the
//! host does not start with it; `--isolation-level` is refused, since the statement has its
//! one place in the driver's configuration. A worker whose pods use user namespaces cannot
//! have the first line of hardening (ADR-0115, decision 2; ADR-0113, decision 5), so such a
//! host reports `hardened: no`, and reads no mark from the driver.
//!
//! A host of this release never sends `microvm`, which a server of this release refuses; a
//! server of an earlier release refuses `machine` and `vm-jail`. So VM hosts and the server
//! take this release together (ADR-0113, decision 2, which amends ADR-0109 for this one
//! change).

use tinfirm_protocol::{Hardened, IsolationTier};

use crate::sandbox::{Executor, RuntimeFamily, SandboxSettings};

/// What the host's sandboxes are, as far as the host can see.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sandboxes {
    /// Containers of this machine's Docker; `gvisor` under a `runsc…` runtime.
    Docker { gvisor: bool },
    /// A VM per run at a provider, through a driver of this binary (`--vm-driver digitalocean`).
    ProviderVms,
    /// Sandbox nodes through this binary's `node` driver (`--vm-driver node`, ADR-0112), as
    /// its configuration reaches them, with the hardening they report (ADR-0113, decision 5):
    /// the VM executor's last known value ([`crate::vm::Vms::hardening`]), `no` where the
    /// driver was not asked.
    Nodes(crate::node::Isolation, crate::vm::Mark),
    /// A pod per run in a Kubernetes cluster through this binary's `pod` driver
    /// (`--vm-driver pod`, ADR-0115 decision 2), whose configuration carries the operator's
    /// statement that the workers for runs hold nothing else.
    ClusterPods,
    /// A VM per run through the operator's own driver: where the VMs run cannot be seen here.
    OwnDriverVms,
    /// The local test executor: the agent is a process of this machine.
    LocalTest,
}

impl Sandboxes {
    /// The sandboxes of `executor`, with `sandbox`'s Docker runtime; with the `node` driver,
    /// the hardening the executor last knew of its nodes. An error when the `node` driver's
    /// configuration cannot be read, or is refused.
    pub fn of(executor: &Executor, sandbox: &SandboxSettings) -> Result<Sandboxes, String> {
        match executor {
            Executor::Docker(_) => Ok(Sandboxes::docker(sandbox)),
            Executor::Vm(vms) => Sandboxes::of_driver(&vms.options.driver)
                .map(|sandboxes| sandboxes.hardened(vms.hardening().hardened)),
            Executor::Local { .. } => Ok(Sandboxes::LocalTest),
        }
    }

    /// The `node` driver's sandboxes with the hardening `mark` its nodes report; any other
    /// sandboxes as they are: no other driver's mark is read.
    pub fn hardened(self, mark: crate::vm::Mark) -> Sandboxes {
        match self {
            Sandboxes::Nodes(isolation, _) => Sandboxes::Nodes(isolation, mark),
            other => other,
        }
    }

    /// Docker's containers under `sandbox`'s runtime, by its name (`--sandbox-runtime`).
    pub fn docker(sandbox: &SandboxSettings) -> Sandboxes {
        Sandboxes::Docker {
            gvisor: sandbox.family() == RuntimeFamily::Gvisor,
        }
    }

    /// The VM executor with `driver`: this binary's own drivers by their kind
    /// ([`crate::vm::Builtin`]); the `node` driver as its configuration reaches the nodes, with
    /// the hardening `no` until its `verify` says otherwise ([`Sandboxes::hardened`]).
    pub fn of_driver(driver: &crate::vm::DriverCommand) -> Result<Sandboxes, String> {
        match &driver.builtin {
            Some(crate::vm::Builtin::Digitalocean) => Ok(Sandboxes::ProviderVms),
            Some(crate::vm::Builtin::Node { config }) => crate::node::NodeConfig::load(config)
                .and_then(|config| config.isolation())
                .map(|isolation| Sandboxes::Nodes(isolation, crate::vm::Mark::No))
                .map_err(|e| format!("--vm-driver node: {e:#}")),
            // Loading checks the statement: without it, no level and no start.
            Some(crate::vm::Builtin::Pod { config }) => {
                crate::kubernetes::config::PodConfig::load(config)
                    .map(|_| Sandboxes::ClusterPods)
                    .map_err(|e| format!("--vm-driver pod: {e:#}"))
            }
            None => Ok(Sandboxes::OwnDriverVms),
        }
    }
}

/// The operator's statement of the level (`run --isolation-level`, `--isolation-hardened`),
/// for a VM driver of their own: `machine` or `vm-jail`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Stated {
    pub level: IsolationTier,
    pub hardened: Hardened,
}

/// What the host reports of its isolation: `hello.isolationTiers`' one value, `hello.hardened`
/// and `hello.isolationStated` (protocol 2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Reported {
    pub tier: IsolationTier,
    /// `None`: left out of the hello, which means `no`.
    pub hardened: Option<Hardened>,
    /// The level rests on the operator's statement, not on what the host can see.
    pub stated: bool,
}

impl Reported {
    /// `container`, hardened left out: a host with Docker's runc, or one that cannot tell.
    pub const CONTAINER: Reported = Reported::seen(IsolationTier::Container, None);

    const fn seen(tier: IsolationTier, hardened: Option<Hardened>) -> Reported {
        Reported {
            tier,
            hardened,
            stated: false,
        }
    }
}

/// What the host reports with `sandboxes` and the operator's statement, if any. An error when
/// the operator states a level where the host derives its own, or states another level than
/// `machine` or `vm-jail`.
pub fn derive(sandboxes: Sandboxes, stated: Option<Stated>) -> Result<Reported, String> {
    match (sandboxes, stated) {
        (Sandboxes::Docker { gvisor: false }, None) => Ok(Reported::CONTAINER),
        (Sandboxes::Docker { gvisor: true }, None) => {
            Ok(Reported::seen(IsolationTier::Gvisor, None))
        }
        (Sandboxes::ProviderVms, None) => Ok(Reported::seen(
            IsolationTier::Machine,
            Some(Hardened::None),
        )),
        // `yes` as the nodes report it (ADR-0113, decision 5); `no` is left out.
        (Sandboxes::Nodes(isolation, mark), None) => Ok(Reported::seen(
            match isolation {
                crate::node::Isolation::VmJail => IsolationTier::VmJail,
                crate::node::Isolation::Machine => IsolationTier::Machine,
            },
            (mark == crate::vm::Mark::Yes).then_some(Hardened::Yes),
        )),
        // The operator's statement in the driver's configuration; never hardened (ADR-0115,
        // decision 2).
        (Sandboxes::ClusterPods, None) => Ok(Reported {
            tier: IsolationTier::Machine,
            hardened: Some(Hardened::No),
            stated: true,
        }),
        (Sandboxes::OwnDriverVms, Some(stated)) => match stated.level {
            IsolationTier::Machine | IsolationTier::VmJail => Ok(Reported {
                tier: stated.level,
                hardened: Some(stated.hardened),
                stated: true,
            }),
            other => Err(format!(
                "--isolation-level states `machine` or `vm-jail`, not `{}`: without a statement a VM driver of your own reports `container`",
                other.as_str()
            )),
        },
        // A VM host shown as a container host until its operator states the level.
        (Sandboxes::OwnDriverVms | Sandboxes::LocalTest, None) => Ok(Reported::CONTAINER),
        (Sandboxes::Docker { gvisor }, Some(_)) => Err(format!(
            "--isolation-level is for a VM driver of your own: with the Docker executor this host reports `{}` itself, and a statement never overrides what the host can see",
            if gvisor { "gvisor" } else { "container" }
        )),
        (Sandboxes::ProviderVms, Some(_)) => Err(
            "--isolation-level is for a VM driver of your own: with --vm-driver digitalocean this host reports `machine` itself, and a statement never overrides what the host can see"
                .into(),
        ),
        (Sandboxes::Nodes(nodes, _), Some(_)) => Err(format!(
            "--isolation-level is for a VM driver of your own: with --vm-driver node this host reports `{}` itself, from its nodes' configuration, and a statement never overrides what the host can see",
            nodes.as_str()
        )),
        (Sandboxes::ClusterPods, Some(_)) => Err(
            "--isolation-level is for a VM driver of your own: with --vm-driver pod the level is the statement of the driver's configuration (isolationStatement), and this host reports `machine`, hardened `no`, from it"
                .into(),
        ),
        (Sandboxes::LocalTest, Some(_)) => {
            Err("--isolation-level is for a VM driver of your own, not the local test executor".into())
        }
    }
}

/// Whether the host is a VM host of the operator's own driver that reports `container` for
/// want of a statement: it says so at its start and in `doctor`.
pub fn unstated(sandboxes: Sandboxes, reported: &Reported) -> bool {
    sandboxes == Sandboxes::OwnDriverVms && !reported.stated
}

/// What the host reports, in a few words: `container, hardened no`, `machine, hardened none`,
/// or `vm-jail, hardened yes, stated by the operator`. A hardening left out is `no`.
pub fn describe(reported: &Reported) -> String {
    format!(
        "{}, hardened {}{}",
        reported.tier.as_str(),
        reported.hardened.unwrap_or(Hardened::No).as_str(),
        if reported.stated {
            ", stated by the operator"
        } else {
            ""
        }
    )
}

/// What an escape from a sandbox reaches on a host that reports `reported` with `sandboxes`
/// (ADR-0112, decision 1; ADR-0113, decision 1), in a sentence or two.
pub fn what_an_escape_reaches(sandboxes: Sandboxes, reported: &Reported) -> String {
    if unstated(sandboxes, reported) {
        return "This host reports `container`: its VM driver is your own, and where the VMs run cannot be seen from here, so it is a VM host shown as a container host until you state its level, and the server places its runs as on a container host. To state the level, start `run` with --isolation-level machine|vm-jail and --isolation-hardened yes|no|none.".into();
    }
    if sandboxes == Sandboxes::LocalTest {
        return "The local test executor runs the agent as a process of this machine, with no sandbox; this host reports `container`.".into();
    }
    let stated = if reported.stated {
        " (as the operator states)"
    } else {
        ""
    };
    match reported.tier {
        IsolationTier::Container | IsolationTier::Gvisor => "An agent that escapes its sandbox reaches this machine, which holds the host's token and every run's git token, and the other runs on it.".into(),
        IsolationTier::Machine if sandboxes == Sandboxes::ProviderVms => "An agent that escapes its VM reaches the provider's machine, which holds nothing of Tinfirm's.".into(),
        IsolationTier::Machine if matches!(sandboxes, Sandboxes::Nodes(..)) => "An agent that escapes its VM reaches its sandbox node, which holds no credential, and the other runs on that node.".into(),
        IsolationTier::Machine if sandboxes == Sandboxes::ClusterPods => "An agent that escapes its VM is the unprivileged user of its run's pod, in a user namespace of its own; a flaw of the kernel under the VMs (KVM) or of the user namespace reaches that worker, which runs no runner pod and, as the operator states, nothing but run pods, and the other runs on it.".into(),
        IsolationTier::VmJail if matches!(sandboxes, Sandboxes::Nodes(..)) => "An agent that leaves its VM through the VMM is one jailed user that reads no credential; a flaw of the kernel under the VMs (KVM) reaches this machine, with every credential.".into(),
        IsolationTier::Machine => format!(
            "An agent that escapes its VM reaches the machine that runs it{stated}: no credential is there, but the other runs on that machine are."
        ),
        IsolationTier::VmJail => format!(
            "An agent that escapes its VM through the VMM is a jailed user that reads no credential{stated}; a flaw of the kernel under the VMs reaches this machine, with every credential and every run."
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::node::Isolation;
    use crate::vm::Mark;

    fn seen(tier: IsolationTier, hardened: Option<Hardened>) -> Result<Reported, String> {
        Ok(Reported {
            tier,
            hardened,
            stated: false,
        })
    }

    fn settings(runtime: Option<&str>) -> SandboxSettings {
        SandboxSettings::new(runtime.map(str::to_string), false, Vec::new()).unwrap()
    }

    #[test]
    fn the_host_derives_what_it_can_see() {
        // The table of the module's documentation, row by row.
        for runtime in [None, Some("runc"), Some("kata"), Some("kata-clh")] {
            assert_eq!(
                derive(Sandboxes::docker(&settings(runtime)), None),
                seen(IsolationTier::Container, None),
                "{runtime:?}"
            );
        }
        for runtime in ["runsc", "runsc-kvm"] {
            assert_eq!(
                derive(Sandboxes::docker(&settings(Some(runtime))), None),
                seen(IsolationTier::Gvisor, None),
                "{runtime}"
            );
        }
        assert_eq!(
            derive(Sandboxes::ProviderVms, None),
            seen(IsolationTier::Machine, Some(Hardened::None))
        );
        assert_eq!(
            derive(Sandboxes::Nodes(Isolation::VmJail, Mark::No), None),
            seen(IsolationTier::VmJail, None)
        );
        assert_eq!(
            derive(Sandboxes::Nodes(Isolation::Machine, Mark::No), None),
            seen(IsolationTier::Machine, None)
        );
        assert_eq!(
            derive(Sandboxes::OwnDriverVms, None),
            seen(IsolationTier::Container, None)
        );
        assert_eq!(
            derive(Sandboxes::LocalTest, None),
            seen(IsolationTier::Container, None)
        );
        for level in [IsolationTier::Machine, IsolationTier::VmJail] {
            for hardened in [Hardened::Yes, Hardened::No, Hardened::None] {
                assert_eq!(
                    derive(Sandboxes::OwnDriverVms, Some(Stated { level, hardened })),
                    Ok(Reported {
                        tier: level,
                        hardened: Some(hardened),
                        stated: true,
                    })
                );
            }
        }
    }

    #[test]
    fn a_statement_is_machine_or_vm_jail() {
        for level in [IsolationTier::Container, IsolationTier::Gvisor] {
            let error = derive(
                Sandboxes::OwnDriverVms,
                Some(Stated {
                    level,
                    hardened: Hardened::No,
                }),
            )
            .unwrap_err();
            assert!(error.contains("`machine` or `vm-jail`"), "{error}");
        }
    }

    #[test]
    fn the_node_driver_reports_what_its_configuration_reaches() {
        let stated = Stated {
            level: IsolationTier::Machine,
            hardened: Hardened::Yes,
        };
        let error =
            derive(Sandboxes::Nodes(Isolation::VmJail, Mark::No), Some(stated)).unwrap_err();
        assert!(
            error.contains("with --vm-driver node this host reports `vm-jail` itself"),
            "{error}"
        );
        let jail = derive(Sandboxes::Nodes(Isolation::VmJail, Mark::No), None).unwrap();
        let text = what_an_escape_reaches(Sandboxes::Nodes(Isolation::VmJail, Mark::No), &jail);
        assert!(
            text.contains("one jailed user that reads no credential"),
            "{text}"
        );
        assert!(
            text.contains("(KVM) reaches this machine, with every credential"),
            "{text}"
        );
        let machine = derive(Sandboxes::Nodes(Isolation::Machine, Mark::No), None).unwrap();
        let text = what_an_escape_reaches(Sandboxes::Nodes(Isolation::Machine, Mark::No), &machine);
        assert!(
            text.contains("reaches its sandbox node, which holds no credential"),
            "{text}"
        );
    }

    #[test]
    fn the_node_drivers_hardening_is_what_its_nodes_report() {
        for (isolation, tier) in [
            (Isolation::VmJail, IsolationTier::VmJail),
            (Isolation::Machine, IsolationTier::Machine),
        ] {
            assert_eq!(
                derive(Sandboxes::Nodes(isolation, Mark::Yes), None),
                seen(tier, Some(Hardened::Yes))
            );
            // `no` is left out, as before.
            assert_eq!(
                derive(Sandboxes::Nodes(isolation, Mark::No), None),
                seen(tier, None)
            );
        }
        assert_eq!(
            describe(&derive(Sandboxes::Nodes(Isolation::VmJail, Mark::Yes), None).unwrap()),
            "vm-jail, hardened yes"
        );
        // A statement stays refused, whatever the nodes report.
        let stated = Stated {
            level: IsolationTier::Machine,
            hardened: Hardened::Yes,
        };
        assert!(
            derive(
                Sandboxes::Nodes(Isolation::Machine, Mark::Yes),
                Some(stated)
            )
            .is_err()
        );
        // Only the node driver's sandboxes take a mark.
        for other in [
            Sandboxes::OwnDriverVms,
            Sandboxes::ProviderVms,
            Sandboxes::LocalTest,
            Sandboxes::Docker { gvisor: false },
        ] {
            assert_eq!(other.hardened(Mark::Yes), other);
        }
    }

    #[tokio::test]
    async fn the_sandboxes_of_a_vm_executor_follow_its_value_for_the_node_driver_alone() {
        use crate::vm::{DriverCommand, Verified, VmOptions, Vms};
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("node-driver.json");
        std::fs::write(
            &config,
            r#"{"nodes": [{"name": "local", "socket": "/run/tinfirm-node-agent/agent.sock", "templates": ["sandbox"]}]}"#,
        )
        .unwrap();
        let vms = |driver: DriverCommand| Vms::new(VmOptions::new(driver, "sandbox".into()));
        let mark = |mark| Verified {
            hardened: Some(mark),
            ..Verified::default()
        };
        let sandbox = SandboxSettings::for_vms(None, false, Vec::new()).unwrap();
        let nodes = vms(DriverCommand::node(config).unwrap());
        let of = |vms: &std::sync::Arc<Vms>| Sandboxes::of(&Executor::Vm(vms.clone()), &sandbox);
        // Before the driver answered, `no`.
        assert_eq!(
            of(&nodes),
            Ok(Sandboxes::Nodes(Isolation::VmJail, Mark::No))
        );
        nodes.note_hardening(&mark(Mark::Yes), true);
        assert_eq!(
            of(&nodes),
            Ok(Sandboxes::Nodes(Isolation::VmJail, Mark::Yes))
        );
        nodes.note_hardening(&Verified::default(), false);
        assert_eq!(
            of(&nodes),
            Ok(Sandboxes::Nodes(Isolation::VmJail, Mark::Yes))
        );
        nodes.note_hardening(&mark(Mark::No), false);
        assert_eq!(
            of(&nodes),
            Ok(Sandboxes::Nodes(Isolation::VmJail, Mark::No))
        );
        // An operator's own driver that says `yes`: its operator's statement stands.
        let own = vms(DriverCommand::parse("/usr/local/bin/my-driver").unwrap());
        own.note_hardening(&mark(Mark::Yes), true);
        assert_eq!(of(&own), Ok(Sandboxes::OwnDriverVms));
        let stated = Stated {
            level: IsolationTier::VmJail,
            hardened: Hardened::No,
        };
        assert_eq!(
            derive(of(&own).unwrap(), Some(stated)).unwrap().hardened,
            Some(Hardened::No)
        );
        assert_eq!(derive(of(&own).unwrap(), None), Ok(Reported::CONTAINER));
    }

    /// The pod driver's configuration that the chart renders for its CI values.
    const POD_CONFIG: &str =
        include_str!("../../../../deploy/helm/tinfirm/ci/expected/pod-driver.json");

    #[test]
    fn the_pod_driver_reports_machine_as_its_configuration_states() {
        use crate::vm::DriverCommand;
        let dir = tempfile::tempdir().unwrap();
        let file = |name: &str, value: &serde_json::Value| {
            let path = dir.path().join(name);
            std::fs::write(&path, value.to_string()).unwrap();
            path
        };
        let good: serde_json::Value = serde_json::from_str(POD_CONFIG).unwrap();
        // With the statement: `machine`, hardened `no`, stated by the operator.
        let pod = DriverCommand::pod(file("good.json", &good)).unwrap();
        let sandboxes = Sandboxes::of_driver(&pod).unwrap();
        assert_eq!(sandboxes, Sandboxes::ClusterPods);
        let reported = derive(sandboxes, None).unwrap();
        assert_eq!(
            reported,
            Reported {
                tier: IsolationTier::Machine,
                hardened: Some(Hardened::No),
                stated: true,
            }
        );
        assert_eq!(
            describe(&reported),
            "machine, hardened no, stated by the operator"
        );
        assert!(!unstated(sandboxes, &reported));
        let text = what_an_escape_reaches(sandboxes, &reported);
        assert!(
            text.contains("unprivileged user of its run's pod"),
            "{text}"
        );
        assert!(text.contains("runs no runner pod"), "{text}");
        // No mark of the driver's changes it: user namespaces rule out the hardening.
        assert_eq!(sandboxes.hardened(Mark::Yes), Sandboxes::ClusterPods);
        // A statement by flag is refused: the configuration holds it.
        for level in [IsolationTier::Machine, IsolationTier::VmJail] {
            let error = derive(
                sandboxes,
                Some(Stated {
                    level,
                    hardened: Hardened::Yes,
                }),
            )
            .unwrap_err();
            assert!(
                error.contains("with --vm-driver pod the level is the statement of the driver's configuration (isolationStatement)"),
                "{error}"
            );
        }
        // Without the statement, or with another sentence: no level, so no start.
        let mut missing = good.clone();
        missing
            .as_object_mut()
            .unwrap()
            .remove("isolationStatement");
        let mut wrong = good.clone();
        wrong["isolationStatement"] = serde_json::json!("the workers hold other pods too");
        for (name, value) in [("missing.json", &missing), ("wrong.json", &wrong)] {
            let pod = DriverCommand::pod(file(name, value)).unwrap();
            let error = Sandboxes::of_driver(&pod).unwrap_err();
            assert!(error.starts_with("--vm-driver pod: "), "{error}");
            assert!(error.contains("isolationStatement"), "{name}: {error}");
        }
        let pod = DriverCommand::pod(dir.path().join("absent.json")).unwrap();
        assert!(
            Sandboxes::of_driver(&pod)
                .unwrap_err()
                .contains("--vm-driver pod: cannot read")
        );
    }

    #[test]
    fn a_statement_never_overrides_what_the_host_sees() {
        let stated = Stated {
            level: IsolationTier::Machine,
            hardened: Hardened::No,
        };
        for sandboxes in [
            Sandboxes::Docker { gvisor: false },
            Sandboxes::Docker { gvisor: true },
            Sandboxes::ProviderVms,
            Sandboxes::LocalTest,
        ] {
            let error = derive(sandboxes, Some(stated)).unwrap_err();
            assert!(error.contains("--isolation-level"), "{error}");
        }
        assert!(
            derive(Sandboxes::Docker { gvisor: false }, Some(stated))
                .unwrap_err()
                .contains("reports `container` itself")
        );
        assert!(
            derive(Sandboxes::Docker { gvisor: true }, Some(stated))
                .unwrap_err()
                .contains("reports `gvisor` itself")
        );
        assert!(
            derive(Sandboxes::ProviderVms, Some(stated))
                .unwrap_err()
                .contains("reports `machine` itself")
        );
    }

    #[test]
    fn each_level_says_what_an_escape_reaches() {
        for gvisor in [false, true] {
            let docker = Sandboxes::Docker { gvisor };
            let container = derive(docker, None).unwrap();
            let text = what_an_escape_reaches(docker, &container);
            assert!(
                text.contains("the host's token and every run's git token"),
                "{text}"
            );
            assert!(!unstated(docker, &container));
        }
        let provider = derive(Sandboxes::ProviderVms, None).unwrap();
        let text = what_an_escape_reaches(Sandboxes::ProviderVms, &provider);
        assert!(text.contains("nothing of Tinfirm's"), "{text}");
        // An own driver without a statement: `container`, and how to state the level.
        let own = derive(Sandboxes::OwnDriverVms, None).unwrap();
        assert!(unstated(Sandboxes::OwnDriverVms, &own));
        let text = what_an_escape_reaches(Sandboxes::OwnDriverVms, &own);
        assert!(text.contains("reports `container`"), "{text}");
        assert!(
            text.contains("a VM host shown as a container host"),
            "{text}"
        );
        assert!(text.contains("--isolation-level"), "{text}");
        assert!(text.contains("as on a container host"), "{text}");
        let jail = derive(
            Sandboxes::OwnDriverVms,
            Some(Stated {
                level: IsolationTier::VmJail,
                hardened: Hardened::No,
            }),
        )
        .unwrap();
        assert!(!unstated(Sandboxes::OwnDriverVms, &jail));
        let text = what_an_escape_reaches(Sandboxes::OwnDriverVms, &jail);
        assert!(text.contains("as the operator states"), "{text}");
        assert_eq!(
            describe(&jail),
            "vm-jail, hardened no, stated by the operator"
        );
        assert_eq!(describe(&Reported::CONTAINER), "container, hardened no");
        assert_eq!(describe(&provider), "machine, hardened none");
        let local = derive(Sandboxes::LocalTest, None).unwrap();
        let text = what_an_escape_reaches(Sandboxes::LocalTest, &local);
        assert!(text.contains("no sandbox"), "{text}");
    }
}
