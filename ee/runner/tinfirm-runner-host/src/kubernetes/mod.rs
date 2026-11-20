//! The pod-per-run VM driver (ADR-0115 decision 2): one pod per run in a namespace of the
//! runs' own, where Firecracker runs the run's VM. Part of elastic runners, under the Tinfirm
//! Enterprise licence (`ee/LICENSE`); the runner host compiles it in
//! (`tinfirm-runner-host/src/lib.rs`).
//!
//! Built so far: [`api`], the client of the Kubernetes API that the driver calls; [`config`],
//! the driver's configuration; and [`spec`], the objects the driver creates and checks, as pure
//! functions of the configuration. The driver itself is planned (ADR-0115 decision 2).

pub mod api;
pub mod config;
pub mod spec;
