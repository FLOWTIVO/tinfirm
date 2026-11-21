//! The pod-per-run VM driver (ADR-0115 decision 2): one pod per run in a namespace of the
//! runs' own, where Firecracker runs the run's VM. Part of elastic runners, under the Tinfirm
//! Enterprise licence (`ee/LICENSE`); the runner host compiles it in
//! (`tinfirm-runner-host/src/lib.rs`).
//!
//! Built so far: [`api`], the client of the Kubernetes API that the driver calls; [`config`],
//! the driver's configuration; [`spec`], the objects the driver creates and checks, as pure
//! functions of the configuration; [`pod_driver`], the driver's methods over them; and
//! [`vm_pod`] with [`tap`], the program of a run's pod. The subcommands that reach the driver
//! and `vm-pod`, and the driver's place among the host's drivers, are planned.

pub mod api;
pub mod config;
pub mod pod_driver;
pub mod spec;
pub mod tap;
pub mod vm_pod;
