//! Capacity settings (docs/archive/v1/CONVENTIONS.md §14.8, §15.7): the ceilings the machine owner set, the
//! desired values that the server sends in `host.config`, and the effective values that
//! admission (§15.4 step 1) uses.
//!
//! - The ceilings are read once, when `run` starts: `--max-concurrent-runs`, and the pool of
//!   each credential in the store. `hello` reports them;
//!   a later `credential add` or `remove` does not change them.
//! - The effective value is [`effective_capacity`] of the desired value and the ceiling. The
//!   host has 0 slots while it drains (§15.4 step 13).
//! - A host with a VM per run has at most the room of its driver's machines that answer while
//!   others do not ([`Capacity::set_vm_room`], crate::vm): a local reason for a lower effective
//!   value, which the host tells the server as it tells it of a drain. The ceiling stays.
//! - `host.config` is the full desired state. On one connection, a `version` lower than the
//!   last one applied is ignored; every new connection forgets that version. The desired
//!   values themselves stay until the next `host.config` replaces them.

use std::collections::{HashMap, HashSet};

use tinfirm_protocol::{
    CredentialCapacity, CredentialInfo, HostConfig, HostConfigApplied, MAX_CAPACITY,
    MAX_HOST_CAPACITY, PoolApplied, PoolConfig, effective_capacity,
};

use crate::credentials::Credential;

/// A host's ceiling within the protocol's bounds (`$defs/HostCapacity`: 1 to
/// [`MAX_HOST_CAPACITY`]); `run` refuses more than [`MAX_CAPACITY`] for a host with Docker.
pub fn clamp_ceiling(value: u32) -> u32 {
    value.clamp(1, MAX_HOST_CAPACITY)
}

/// What [`Capacity::apply`] did with a `host.config`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ApplyOutcome {
    /// Applied; the host answers with this `host.config.applied`.
    Applied(HostConfigApplied),
    /// Lower than `last`, the version applied last on this connection: neither applied nor
    /// answered.
    Outdated { last: u64 },
    /// A desired value outside its bounds, or a label listed twice: answered with
    /// `error BAD_MESSAGE`, and otherwise ignored.
    Invalid(String),
}

/// The capacity state of a serving host.
#[derive(Debug, Clone)]
pub struct Capacity {
    /// `run --max-concurrent-runs`.
    host_ceiling: u32,
    /// The credentials of `hello`, in its order; `pool_size` is each one's ceiling.
    credentials: Vec<CredentialInfo>,
    desired_max_concurrent_runs: Option<u32>,
    /// Desired pools of known labels only.
    desired_pools: HashMap<String, u32>,
    /// The version applied last on the current connection; `None` before the first one.
    applied_version: Option<u64>,
    draining: bool,
    /// A host with ready sandboxes (crate::ready_vms): the operator's `maxReady`.
    pool_ceiling: Option<u32>,
    /// Tinfirm's count of ready sandboxes, as the last `host.config` gave it.
    desired_pool: Option<PoolConfig>,
    /// With a VM per run, while part of the driver's machines do not answer: the most VMs this
    /// host can have on those that do. `None` while every machine answers.
    vm_room: Option<u32>,
}

impl Capacity {
    /// The ceilings of a `run` that starts now.
    pub fn new(max_concurrent_runs: u32, credentials: &[Credential]) -> Capacity {
        Capacity {
            host_ceiling: clamp_ceiling(max_concurrent_runs),
            credentials: credentials.iter().map(Credential::info).collect(),
            desired_max_concurrent_runs: None,
            desired_pools: HashMap::new(),
            applied_version: None,
            draining: false,
            pool_ceiling: None,
            desired_pool: None,
            vm_room: None,
        }
    }

    /// A host that keeps ready sandboxes up to `max_ready` (`hello.pool`, ADR-0099).
    pub fn with_pool(mut self, max_ready: u32) -> Capacity {
        self.pool_ceiling = Some(max_ready.min(MAX_HOST_CAPACITY));
        self
    }

    /// The operator's `maxReady` of a host that keeps ready sandboxes.
    pub fn pool_ceiling(&self) -> Option<u32> {
        self.pool_ceiling
    }

    /// Tinfirm's count of ready sandboxes, as the last `host.config` gave it.
    pub fn desired_pool(&self) -> Option<&PoolConfig> {
        self.desired_pool.as_ref()
    }

    /// `hello.maxConcurrentRuns`.
    pub fn host_ceiling(&self) -> u32 {
        self.host_ceiling
    }

    /// `hello.credentials`: the credentials as `run` read them at startup, with their ceilings.
    pub fn credentials(&self) -> &[CredentialInfo] {
        &self.credentials
    }

    /// The credential `label` as `run` read it at startup.
    pub fn credential(&self, label: &str) -> Option<&CredentialInfo> {
        self.credentials.iter().find(|c| c.label == label)
    }

    /// The host's effective slots: 0 while draining, and at most the VM driver's room while
    /// part of its machines do not answer ([`Capacity::set_vm_room`]).
    pub fn max_concurrent_runs(&self) -> u32 {
        if self.draining {
            return 0;
        }
        let slots = effective_capacity(self.desired_max_concurrent_runs, self.host_ceiling);
        self.vm_room.map_or(slots, |room| slots.min(room))
    }

    /// The VM driver's room while part of its machines do not answer (crate::vm, `Vms::room`):
    /// the most VMs this host can have on those that do, which may be none; `None` once every
    /// machine answers again. Returns whether the host's effective slots changed.
    pub fn set_vm_room(&mut self, room: Option<u32>) -> bool {
        let before = self.max_concurrent_runs();
        self.vm_room = room;
        self.max_concurrent_runs() != before
    }

    /// The slots `host.config.applied` reports: [`Capacity::max_concurrent_runs`], but at least
    /// 1 while the host does not drain, as the protocol has it (`maxConcurrentRuns` is 0 exactly
    /// while draining, and the server refuses a frame with 0 otherwise). A VM driver whose
    /// machines that answer have no room leaves the host 0 slots of its own, which admission
    /// keeps to: a run assigned to that one reported slot is rejected with the cause and waits in
    /// the server's queue, and a driver that can make no VM at all is told to the server with
    /// `host.health` (`VM_DRIVER_NO_VM`), so that it assigns this host none.
    pub fn reported_max_concurrent_runs(&self) -> u32 {
        if self.draining {
            0
        } else {
            self.max_concurrent_runs().max(1)
        }
    }

    /// The VM driver's room while it is what bounds the host's slots: fewer than the desired
    /// value and the ceiling give.
    pub fn bounding_vm_room(&self) -> Option<u32> {
        let slots = effective_capacity(self.desired_max_concurrent_runs, self.host_ceiling);
        self.vm_room.filter(|room| *room < slots && !self.draining)
    }

    /// The effective pool of `label`; `None` for a credential `run` did not start with.
    pub fn pool_size(&self, label: &str) -> Option<u32> {
        let ceiling = self.credential(label)?.pool_size;
        Some(effective_capacity(
            self.desired_pools.get(label).copied(),
            ceiling,
        ))
    }

    pub fn is_draining(&self) -> bool {
        self.draining
    }

    /// Applies a `host.config` (docs/archive/v1/CONVENTIONS.md §15.7).
    pub fn apply(&mut self, config: &HostConfig) -> ApplyOutcome {
        if let Some(last) = self.applied_version
            && config.version < last
        {
            return ApplyOutcome::Outdated { last };
        }
        let in_bounds = |value: u32| (1..=MAX_CAPACITY).contains(&value);
        if let Some(value) = config.desired_max_concurrent_runs
            && !(1..=MAX_HOST_CAPACITY).contains(&value)
        {
            return ApplyOutcome::Invalid(format!(
                "host.config: desiredMaxConcurrentRuns {value} is not between 1 and {MAX_HOST_CAPACITY}"
            ));
        }
        if let Some(pool) = &config.pool {
            let counts = std::iter::once(pool.ready_target)
                .chain(pool.next.as_ref().map(|next| next.ready_target));
            for count in counts {
                if count > MAX_HOST_CAPACITY {
                    return ApplyOutcome::Invalid(format!(
                        "host.config: the pool's readyTarget {count} is above {MAX_HOST_CAPACITY}"
                    ));
                }
            }
        }
        let mut seen = HashSet::new();
        for credential in &config.credentials {
            if !in_bounds(credential.desired_pool_size) {
                return ApplyOutcome::Invalid(format!(
                    "host.config: desiredPoolSize {} of `{}` is not between 1 and {MAX_CAPACITY}",
                    credential.desired_pool_size, credential.label
                ));
            }
            if !seen.insert(credential.label.as_str()) {
                return ApplyOutcome::Invalid(format!(
                    "host.config: the credential `{}` is listed twice",
                    credential.label
                ));
            }
        }
        self.desired_max_concurrent_runs = config.desired_max_concurrent_runs;
        self.desired_pools = config
            .credentials
            .iter()
            .filter(|c| self.credential(&c.label).is_some())
            .map(|c| (c.label.clone(), c.desired_pool_size))
            .collect();
        self.desired_pool = config.pool.clone();
        self.applied_version = Some(config.version);
        ApplyOutcome::Applied(self.applied().expect("a version was just applied"))
    }

    /// The `host.config.applied` for the current state, or `None` before the first
    /// `host.config` applied on the current connection.
    pub fn applied(&self) -> Option<HostConfigApplied> {
        let version = self.applied_version?;
        Some(HostConfigApplied {
            version,
            draining: self.draining,
            max_concurrent_runs: self.reported_max_concurrent_runs(),
            max_concurrent_runs_ceiling: self.host_ceiling,
            credentials: self
                .credentials
                .iter()
                .map(|c| CredentialCapacity {
                    label: c.label.clone(),
                    pool_size: effective_capacity(
                        self.desired_pools.get(&c.label).copied(),
                        c.pool_size,
                    ),
                    pool_size_ceiling: c.pool_size,
                })
                .collect(),
            pool: self.pool_ceiling.map(|ceiling| PoolApplied {
                ready_target: if self.draining {
                    0
                } else {
                    self.desired_pool
                        .as_ref()
                        .map_or(0, |pool| pool.ready_target.min(ceiling))
                },
                max_ready_ceiling: ceiling,
            }),
        })
    }

    /// A connection ended or a new one started: forget the version (the server sends the
    /// current state after each `welcome`). The desired values stay until then.
    pub fn new_connection(&mut self) {
        self.applied_version = None;
    }

    /// Starts to drain. `false` when the host already drains.
    pub fn start_drain(&mut self) -> bool {
        !std::mem::replace(&mut self.draining, true)
    }
}

#[cfg(test)]
mod tests {
    use chrono::Utc;
    use tinfirm_protocol::{CredentialConfig, CredentialKind};

    use super::*;

    fn credential(label: &str, kind: CredentialKind, pool: u32) -> Credential {
        Credential {
            label: label.into(),
            kind,
            pool_size: pool,
            created_at: Utc::now(),
            secret: None,
        }
    }

    fn capacity() -> Capacity {
        Capacity::new(
            4,
            &[
                credential("claude-main", CredentialKind::ClaudeLogin, 3),
                credential("codex-main", CredentialKind::CodexChatgptAuth, 5),
                credential("openai", CredentialKind::OpenaiApiKey, 40),
            ],
        )
    }

    fn config(version: u64, max: Option<u32>, pools: &[(&str, u32)]) -> HostConfig {
        HostConfig {
            version,
            desired_max_concurrent_runs: max,
            credentials: pools
                .iter()
                .map(|(label, size)| CredentialConfig {
                    label: (*label).into(),
                    desired_pool_size: *size,
                })
                .collect(),
            pool: None,
        }
    }

    fn answer(outcome: ApplyOutcome) -> HostConfigApplied {
        match outcome {
            ApplyOutcome::Applied(applied) => applied,
            other => panic!("not applied: {other:?}"),
        }
    }

    #[test]
    fn ceilings_come_from_the_start_and_bound_the_effective_values() {
        let mut c = capacity();
        assert_eq!(c.host_ceiling(), 4);
        let ceilings: Vec<(&str, u32)> = c
            .credentials()
            .iter()
            .map(|i| (i.label.as_str(), i.pool_size))
            .collect();
        assert_eq!(
            ceilings,
            vec![("claude-main", 3), ("codex-main", 5), ("openai", 16)]
        );
        // Without a host.config, the ceilings are the effective values.
        assert_eq!(c.max_concurrent_runs(), 4);
        assert_eq!(c.pool_size("claude-main"), Some(3));
        assert_eq!(c.pool_size("nope"), None);
        assert!(c.applied().is_none(), "nothing applied on this connection");

        let applied = answer(c.apply(&config(
            7,
            Some(9),
            &[("claude-main", 1), ("codex-main", 4), ("nope", 2)],
        )));
        assert_eq!(applied.version, 7);
        assert!(!applied.draining);
        assert_eq!(applied.max_concurrent_runs, 4, "desired above the ceiling");
        assert_eq!(applied.max_concurrent_runs_ceiling, 4);
        let pools: Vec<(&str, u32, u32)> = applied
            .credentials
            .iter()
            .map(|c| (c.label.as_str(), c.pool_size, c.pool_size_ceiling))
            .collect();
        assert_eq!(
            pools,
            vec![
                ("claude-main", 1, 3),
                ("codex-main", 4, 5),
                ("openai", 16, 16)
            ],
            "every credential of hello, unknown labels ignored"
        );
        assert_eq!(c.max_concurrent_runs(), 4);
        assert_eq!(c.pool_size("claude-main"), Some(1));

        // Full state: a credential no longer listed goes back to its ceiling.
        let applied = answer(c.apply(&config(8, Some(2), &[])));
        assert_eq!(applied.max_concurrent_runs, 2);
        assert_eq!(c.pool_size("claude-main"), Some(3));
    }

    #[test]
    fn versions_are_ordered_per_connection() {
        let mut c = capacity();
        answer(c.apply(&config(5, Some(1), &[])));
        assert_eq!(
            c.apply(&config(4, Some(3), &[])),
            ApplyOutcome::Outdated { last: 5 }
        );
        assert_eq!(
            c.max_concurrent_runs(),
            1,
            "an outdated config changes nothing"
        );
        // The same version again is applied and answered.
        assert_eq!(answer(c.apply(&config(5, Some(1), &[]))).version, 5);
        assert_eq!(answer(c.apply(&config(6, Some(2), &[]))).version, 6);

        // A new connection forgets the version, but keeps the values until the next config.
        c.new_connection();
        assert!(c.applied().is_none());
        assert_eq!(c.max_concurrent_runs(), 2);
        let applied = answer(c.apply(&config(1, Some(3), &[])));
        assert_eq!(applied.version, 1);
        assert_eq!(applied.max_concurrent_runs, 3);
    }

    #[test]
    fn invalid_configs_change_nothing() {
        let mut c = capacity();
        answer(c.apply(&config(1, Some(2), &[])));
        for bad in [
            config(2, Some(0), &[]),
            config(2, Some(MAX_HOST_CAPACITY + 1), &[]),
            config(2, None, &[("claude-main", MAX_CAPACITY + 1)]),
            config(2, None, &[("claude-main", 0)]),
            config(2, None, &[("claude-main", 1), ("claude-main", 2)]),
        ] {
            assert!(matches!(c.apply(&bad), ApplyOutcome::Invalid(_)), "{bad:?}");
        }
        assert_eq!(c.applied().unwrap().version, 1);
        assert_eq!(c.max_concurrent_runs(), 2);
    }

    #[test]
    fn draining_has_no_slots() {
        let mut c = capacity();
        assert!(c.start_drain());
        assert!(!c.start_drain(), "a second drain changes nothing");
        assert_eq!(c.max_concurrent_runs(), 0);
        assert!(c.applied().is_none());
        let applied = answer(c.apply(&config(3, Some(2), &[("claude-main", 2)])));
        assert!(applied.draining);
        assert_eq!(applied.max_concurrent_runs, 0);
        assert_eq!(applied.max_concurrent_runs_ceiling, 4);
        assert_eq!(applied.credentials[0].pool_size, 2);
    }

    #[test]
    fn the_vm_drivers_room_bounds_the_slots_while_it_is_set_and_never_the_ceiling() {
        let mut c = capacity();
        // Room above the slots changes nothing, and is not what bounds them.
        assert!(!c.set_vm_room(Some(6)));
        assert_eq!((c.max_concurrent_runs(), c.bounding_vm_room()), (4, None));
        // Room for 2: the host has 2 slots, and its ceiling is what `run` started with.
        assert!(c.set_vm_room(Some(2)));
        assert!(!c.set_vm_room(Some(2)), "the same room changes nothing");
        assert_eq!(c.max_concurrent_runs(), 2);
        assert_eq!(c.bounding_vm_room(), Some(2));
        let applied = answer(c.apply(&config(1, None, &[])));
        assert_eq!(
            (
                applied.max_concurrent_runs,
                applied.max_concurrent_runs_ceiling
            ),
            (2, 4)
        );
        // A desired value below the room is what counts, and one above it is bounded by it.
        let applied = answer(c.apply(&config(2, Some(1), &[])));
        assert_eq!(applied.max_concurrent_runs, 1);
        assert_eq!(c.bounding_vm_room(), None);
        let applied = answer(c.apply(&config(3, Some(3), &[])));
        assert_eq!(applied.max_concurrent_runs, 2);
        // No machine that answers has a free VM: the host has no slot of its own, and does not
        // drain. It still reports one, as the protocol has it for a host that does not drain
        // (the server refused the frame with 0 as BAD_MESSAGE: the release test of 1.0.0-rc.2,
        // F-5); admission keeps to none.
        assert!(c.set_vm_room(Some(0)));
        assert_eq!(c.max_concurrent_runs(), 0);
        let applied = c.applied().unwrap();
        assert_eq!((applied.max_concurrent_runs, applied.draining), (1, false));
        // Every machine answers again: the slots of the desired value and the ceiling.
        assert!(c.set_vm_room(None));
        assert_eq!(c.applied().unwrap().max_concurrent_runs, 3);
        // A draining host has no slot whatever the room, and the room changes none.
        assert!(c.start_drain());
        assert!(!c.set_vm_room(Some(1)));
        assert_eq!((c.max_concurrent_runs(), c.bounding_vm_room()), (0, None));
        assert!(!c.set_vm_room(Some(0)));
        let applied = c.applied().unwrap();
        assert_eq!((applied.max_concurrent_runs, applied.draining), (0, true));
    }

    #[test]
    fn ceilings_are_bounded() {
        assert_eq!(clamp_ceiling(0), 1);
        assert_eq!(clamp_ceiling(16), 16);
        assert_eq!(clamp_ceiling(99), 99);
        assert_eq!(clamp_ceiling(999), 999);
        assert_eq!(clamp_ceiling(5000), MAX_HOST_CAPACITY);
        assert_eq!(Capacity::new(40, &[]).host_ceiling(), 40);
    }

    #[test]
    fn a_pool_takes_tinfirms_count_up_to_its_ceiling_and_none_while_draining() {
        use tinfirm_protocol::{PoolChange, PoolConfig};
        // A host without a pool reports none.
        let mut plain = capacity();
        assert!(answer(plain.apply(&config(1, None, &[]))).pool.is_none());

        let mut c = capacity().with_pool(3);
        assert_eq!(c.pool_ceiling(), Some(3));
        // No count yet: 0.
        let applied = answer(c.apply(&config(1, None, &[])));
        assert_eq!(applied.pool.unwrap().ready_target, 0);
        let mut with_pool = config(2, Some(4), &[]);
        with_pool.pool = Some(PoolConfig {
            ready_target: 5,
            next: Some(PoolChange {
                ready_target: 1,
                at: Utc::now(),
            }),
        });
        let applied = answer(c.apply(&with_pool));
        let pool = applied.pool.unwrap();
        assert_eq!((pool.ready_target, pool.max_ready_ceiling), (3, 3));
        assert_eq!(c.desired_pool().unwrap().ready_target, 5);
        // Out of bounds: refused, and the count stays.
        let mut bad = config(3, None, &[]);
        bad.pool = Some(PoolConfig {
            ready_target: MAX_HOST_CAPACITY + 1,
            next: None,
        });
        assert!(matches!(c.apply(&bad), ApplyOutcome::Invalid(_)));
        assert_eq!(c.desired_pool().unwrap().ready_target, 5);
        assert!(c.start_drain());
        assert_eq!(c.applied().unwrap().pool.unwrap().ready_target, 0);
    }
}
