//! What sits behind the helper's checks: the jail, the network, the disks and the VMM. The
//! helper hands a backend only what it derived itself ([`VmPlan`], [`Names`]) and the user
//! data's bytes; a backend never sees a VM's id.
//!
//! Two backends: [`crate::linux::LinuxBackend`] (Firecracker under its jailer) and
//! [`FakeBackend`], for tests.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use crate::plan::{Names, Template, VmPlan};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BackendError {
    /// This backend cannot do it. The Linux backend never answers so; the kind stays for a
    /// backend that is built in parts.
    NotBuilt(&'static str),
    Failed(String),
}

impl std::fmt::Display for BackendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BackendError::NotBuilt(what) => write!(f, "not built yet: {what}"),
            BackendError::Failed(why) => f.write_str(why),
        }
    }
}

/// What a node check found.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Checked {
    /// What breaks the contract: the node must not run VMs while any is here.
    pub problems: Vec<String>,
    /// What was checked, for the log.
    pub checked: Vec<String>,
}

/// The node's own firewall table (`inet tinfirm_node`), as the backend finds it.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub enum NodeTable {
    #[default]
    There,
    Missing,
    /// The node's tables cannot be listed: why.
    Unknown(String),
}

/// A VM that the backend finds on the node.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Present {
    /// The jail's name as found, for the operator: escaped before it is shown.
    pub jail: String,
    /// Its names, from the jail's name; `None` for a jail of a form the helper does not know,
    /// whose slot cannot be known.
    pub names: Option<Names>,
    /// Its VMM runs. A VM whose VMM died stays until it is deleted.
    pub alive: bool,
}

/// The four things the helper does, below its checks.
pub trait Backend: Send + Sync + 'static {
    fn check(&self, template: &Template) -> Checked;
    /// Whether the node's own firewall table is loaded. The helper asks it in `check` and
    /// before each make, and decides with its configuration (`crate::helper::node_table_line`).
    fn node_table(&self) -> NodeTable;
    /// Builds the VM's jail, network and disks, and starts its VMM.
    fn make(&self, plan: &VmPlan, user_data: &[u8]) -> Result<(), BackendError>;
    /// Removes everything of the VM named `names`; one that is not there is no error.
    fn delete(&self, names: &Names) -> Result<(), BackendError>;
    /// The VMs on the node, the helper's and any an earlier helper left.
    fn list(&self) -> Result<Vec<Present>, BackendError>;
}

/// One VM of the fake backend.
#[derive(Debug, Clone)]
pub struct FakeVm {
    pub plan: VmPlan,
    pub user_data: Vec<u8>,
    pub alive: bool,
}

#[derive(Debug, Default)]
struct FakeState {
    vms: BTreeMap<u64, FakeVm>,
    /// Jails of a form the helper does not know.
    unknown: Vec<String>,
    problems: Vec<String>,
    node_table: NodeTable,
    fail_next_make: bool,
    fail_next_delete: bool,
    fail_deletes: bool,
}

/// A backend in memory, for tests: its "VMs" are records. A test kills one, fails a make or
/// reports a problem through it, and reads what the helper handed it.
#[derive(Debug, Clone, Default)]
pub struct FakeBackend {
    state: Arc<Mutex<FakeState>>,
}

impl FakeBackend {
    pub fn new() -> FakeBackend {
        FakeBackend::default()
    }

    fn state(&self) -> std::sync::MutexGuard<'_, FakeState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The VMs it holds, by serial.
    pub fn vms(&self) -> BTreeMap<u64, FakeVm> {
        self.state().vms.clone()
    }

    /// Its VMM dies: the VM stays, not alive.
    pub fn kill(&self, serial: u64) {
        if let Some(vm) = self.state().vms.get_mut(&serial) {
            vm.alive = false;
        }
    }

    /// A VM that an earlier helper left: one this helper has no record of.
    pub fn leave(&self, plan: VmPlan) {
        self.state().vms.insert(
            plan.names.serial,
            FakeVm {
                plan,
                user_data: Vec::new(),
                alive: true,
            },
        );
    }

    pub fn set_problems(&self, problems: Vec<String>) {
        self.state().problems = problems;
    }

    /// What the fake node's own firewall table is: there, until a test says otherwise.
    pub fn set_node_table(&self, table: NodeTable) {
        self.state().node_table = table;
    }

    /// The next make fails half-way: it leaves a VM whose VMM does not run.
    pub fn fail_next_make(&self) {
        self.state().fail_next_make = true;
    }

    /// The next delete fails; the ones after it work again.
    pub fn fail_next_delete(&self) {
        self.state().fail_next_delete = true;
    }

    /// Every delete fails while this is set.
    pub fn fail_deletes(&self, fail: bool) {
        self.state().fail_deletes = fail;
    }

    /// A jail of a form the helper does not know, whose slot cannot be known.
    pub fn leave_unknown(&self, name: &str) {
        self.state().unknown.push(name.to_string());
    }

    pub fn remove_unknown(&self) {
        self.state().unknown.clear();
    }
}

impl Backend for FakeBackend {
    fn check(&self, _template: &Template) -> Checked {
        Checked {
            problems: self.state().problems.clone(),
            checked: vec!["the fake backend".into()],
        }
    }

    fn node_table(&self) -> NodeTable {
        self.state().node_table.clone()
    }

    fn make(&self, plan: &VmPlan, user_data: &[u8]) -> Result<(), BackendError> {
        let mut state = self.state();
        if state.vms.contains_key(&plan.names.serial) {
            return Err(BackendError::Failed("the jail exists already".into()));
        }
        if std::mem::take(&mut state.fail_next_make) {
            state.vms.insert(
                plan.names.serial,
                FakeVm {
                    plan: plan.clone(),
                    user_data: Vec::new(),
                    alive: false,
                },
            );
            return Err(BackendError::Failed("the fake VMM did not start".into()));
        }
        state.vms.insert(
            plan.names.serial,
            FakeVm {
                plan: plan.clone(),
                user_data: user_data.to_vec(),
                alive: true,
            },
        );
        Ok(())
    }

    fn delete(&self, names: &Names) -> Result<(), BackendError> {
        let mut state = self.state();
        if std::mem::take(&mut state.fail_next_delete) || state.fail_deletes {
            return Err(BackendError::Failed(
                "the fake jail cannot be removed".into(),
            ));
        }
        state.vms.remove(&names.serial);
        Ok(())
    }

    fn list(&self) -> Result<Vec<Present>, BackendError> {
        let state = self.state();
        let known = state.vms.values().map(|vm| Present {
            jail: vm.plan.names.jail(),
            names: Some(vm.plan.names),
            alive: vm.alive,
        });
        let unknown = state.unknown.iter().map(|jail| Present {
            jail: jail.clone(),
            names: None,
            alive: false,
        });
        Ok(known.chain(unknown).collect())
    }
}
