//! The host's level of isolation (ADR-0113, decisions 1 to 3): the strongest wall between an
//! agent's code and the credentials, and whether the machine that runs the host's VMs is
//! hardened. The host reports it in `hello.isolation` with the feature `host.isolation`
//! (protocol 2 only), and says at its start and in `doctor` what an escape from a sandbox
//! reaches on a host of that level (ADR-0112, decision 7).
//!
//! The host derives the level from what it can see, not from a setting, wherever it can
//! ([`derive`]):
//!
//! | Sandboxes | Level | Hardened | Stated |
//! |---|---|---|---|
//! | Docker, whatever its runtime (runc, `runsc…`, `kata…`) | `container` | `no` | no |
//! | A VM per run through a provider's driver of this binary (`digitalocean`) | `machine` | `none` | no |
//! | A VM per run through the operator's own driver | what `--isolation-level` states, or none | `--isolation-hardened` | yes |
//! | The local test executor | none | | |
//!
//! A statement never overrides what the host can see: [`derive`] refuses one where the host
//! derives its own level. The `node` driver of ADR-0112 (not built yet) will be derived here
//! too: `vm-jail` when it speaks to the node agent over the local socket, `machine` when every
//! node is reached over TLS on another machine, with the hardening the node agent checks.

use tinfirm_protocol::{Hardened, HostIsolation, IsolationLevel};

use crate::sandbox::Executor;

/// What the host's sandboxes are, as far as the host can see.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sandboxes {
    /// Containers of this machine's Docker.
    Docker,
    /// A VM per run at a provider, through a driver of this binary (`--vm-driver digitalocean`).
    ProviderVms,
    /// A VM per run through the operator's own driver: where the VMs run cannot be seen here.
    OwnDriverVms,
    /// The local test executor: the agent is a process of this machine.
    LocalTest,
}

impl Sandboxes {
    pub fn of(executor: &Executor) -> Sandboxes {
        match executor {
            Executor::Docker(_) => Sandboxes::Docker,
            Executor::Vm(vms) => Sandboxes::of_driver(&vms.options.driver),
            Executor::Local { .. } => Sandboxes::LocalTest,
        }
    }

    /// The VM executor with `driver`. This binary's own drivers are each a provider's VM per
    /// run ([`crate::vm::DriverCommand::elastic`]).
    pub fn of_driver(driver: &crate::vm::DriverCommand) -> Sandboxes {
        if driver.elastic {
            Sandboxes::ProviderVms
        } else {
            Sandboxes::OwnDriverVms
        }
    }
}

/// The operator's statement of the level (`run --isolation-level`, `--isolation-hardened`),
/// for a VM driver of their own.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Stated {
    pub level: IsolationLevel,
    pub hardened: Hardened,
}

/// The level the host reports with `sandboxes` and the operator's statement, if any; `None`:
/// it reports no level. An error when the operator states a level where the host derives
/// its own, or where it reports none.
pub fn derive(
    sandboxes: Sandboxes,
    stated: Option<Stated>,
) -> Result<Option<HostIsolation>, String> {
    let seen = |level, hardened| HostIsolation {
        level,
        hardened,
        stated: false,
    };
    match (sandboxes, stated) {
        (Sandboxes::Docker, None) => Ok(Some(seen(IsolationLevel::Container, Hardened::No))),
        (Sandboxes::ProviderVms, None) => Ok(Some(seen(IsolationLevel::Machine, Hardened::None))),
        (Sandboxes::OwnDriverVms, Some(stated)) => Ok(Some(HostIsolation {
            level: stated.level,
            hardened: stated.hardened,
            stated: true,
        })),
        (Sandboxes::OwnDriverVms | Sandboxes::LocalTest, None) => Ok(None),
        (Sandboxes::Docker, Some(_)) => Err(
            "--isolation-level is for a VM driver of your own: with the Docker executor this host reports `container` itself, and a statement never overrides what the host can see"
                .into(),
        ),
        (Sandboxes::ProviderVms, Some(_)) => Err(
            "--isolation-level is for a VM driver of your own: with --vm-driver digitalocean this host reports `machine` itself, and a statement never overrides what the host can see"
                .into(),
        ),
        (Sandboxes::LocalTest, Some(_)) => {
            Err("--isolation-level is for a VM driver of your own, not the local test executor".into())
        }
    }
}

/// What the host reports, in a few words: `container, hardened no`, `vm-jail, hardened yes,
/// stated by the operator`, or `none`.
pub fn describe(isolation: Option<&HostIsolation>) -> String {
    match isolation {
        None => "none".to_string(),
        Some(i) => format!(
            "{}, hardened {}{}",
            i.level.as_str(),
            i.hardened.as_str(),
            if i.stated {
                ", stated by the operator"
            } else {
                ""
            }
        ),
    }
}

/// What an escape from a sandbox reaches on a host that reports `isolation` with `sandboxes`
/// (ADR-0112, decision 1; ADR-0113, decision 1), in a sentence or two.
pub fn what_an_escape_reaches(sandboxes: Sandboxes, isolation: Option<&HostIsolation>) -> String {
    let Some(isolation) = isolation else {
        return match sandboxes {
            Sandboxes::OwnDriverVms => "This host reports no level of isolation: its VM driver is your own, and where the VMs run cannot be seen from here. The server places its runs as on a container host. To state the level, start `run` with --isolation-level machine|vm-jail and --isolation-hardened yes|no|none.".into(),
            _ => "This host reports no level of isolation.".into(),
        };
    };
    let stated = if isolation.stated {
        " (as the operator states)"
    } else {
        ""
    };
    match isolation.level {
        IsolationLevel::Container => "An agent that escapes its sandbox reaches this machine, which holds the host's token and every run's git token, and the other runs on it.".into(),
        IsolationLevel::Machine if sandboxes == Sandboxes::ProviderVms => "An agent that escapes its VM reaches the provider's machine, which holds nothing of Tinfirm's.".into(),
        IsolationLevel::Machine => format!(
            "An agent that escapes its VM reaches the machine that runs it{stated}: no credential is there, but the other runs on that machine are."
        ),
        IsolationLevel::VmJail => format!(
            "An agent that escapes its VM through the VMM is a jailed user that reads no credential{stated}; a flaw of the kernel under the VMs reaches this machine, with every credential and every run."
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seen(level: IsolationLevel, hardened: Hardened) -> Option<HostIsolation> {
        Some(HostIsolation {
            level,
            hardened,
            stated: false,
        })
    }

    #[test]
    fn the_host_derives_what_it_can_see() {
        assert_eq!(
            derive(Sandboxes::Docker, None),
            Ok(seen(IsolationLevel::Container, Hardened::No))
        );
        assert_eq!(
            derive(Sandboxes::ProviderVms, None),
            Ok(seen(IsolationLevel::Machine, Hardened::None))
        );
        assert_eq!(derive(Sandboxes::OwnDriverVms, None), Ok(None));
        assert_eq!(derive(Sandboxes::LocalTest, None), Ok(None));
    }

    #[test]
    fn an_own_driver_reports_what_the_operator_states() {
        let stated = Stated {
            level: IsolationLevel::VmJail,
            hardened: Hardened::Yes,
        };
        assert_eq!(
            derive(Sandboxes::OwnDriverVms, Some(stated)),
            Ok(Some(HostIsolation {
                level: IsolationLevel::VmJail,
                hardened: Hardened::Yes,
                stated: true,
            }))
        );
    }

    #[test]
    fn a_statement_never_overrides_what_the_host_sees() {
        let stated = Stated {
            level: IsolationLevel::Machine,
            hardened: Hardened::No,
        };
        for sandboxes in [
            Sandboxes::Docker,
            Sandboxes::ProviderVms,
            Sandboxes::LocalTest,
        ] {
            let error = derive(sandboxes, Some(stated)).unwrap_err();
            assert!(error.contains("--isolation-level"), "{error}");
        }
        assert!(
            derive(Sandboxes::Docker, Some(stated))
                .unwrap_err()
                .contains("reports `container` itself")
        );
        assert!(
            derive(Sandboxes::ProviderVms, Some(stated))
                .unwrap_err()
                .contains("reports `machine` itself")
        );
    }

    #[test]
    fn each_level_says_what_an_escape_reaches() {
        let container = derive(Sandboxes::Docker, None).unwrap();
        let text = what_an_escape_reaches(Sandboxes::Docker, container.as_ref());
        assert!(
            text.contains("the host's token and every run's git token"),
            "{text}"
        );
        let provider = derive(Sandboxes::ProviderVms, None).unwrap();
        let text = what_an_escape_reaches(Sandboxes::ProviderVms, provider.as_ref());
        assert!(text.contains("nothing of Tinfirm's"), "{text}");
        let text = what_an_escape_reaches(Sandboxes::OwnDriverVms, None);
        assert!(text.contains("--isolation-level"), "{text}");
        assert!(text.contains("as on a container host"), "{text}");
        let jail = derive(
            Sandboxes::OwnDriverVms,
            Some(Stated {
                level: IsolationLevel::VmJail,
                hardened: Hardened::No,
            }),
        )
        .unwrap();
        let text = what_an_escape_reaches(Sandboxes::OwnDriverVms, jail.as_ref());
        assert!(text.contains("as the operator states"), "{text}");
        assert_eq!(
            describe(jail.as_ref()),
            "vm-jail, hardened no, stated by the operator"
        );
        assert_eq!(describe(container.as_ref()), "container, hardened no");
        assert_eq!(describe(None), "none");
    }
}
