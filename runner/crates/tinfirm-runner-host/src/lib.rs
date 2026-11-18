//! Tinfirm runner host library. The binary (`main.rs`) is a thin CLI over it; the
//! integration tests drive the same code in-process.
//!
//! The modules here are MIT. The binary also compiles in the paid modules of
//! `ee/runner/tinfirm-runner-host/src` (the Tinfirm Enterprise licence, ADR-0100, ADR-0114):
//! DigitalOcean's VM driver ([`digitalocean`]), ready VMs ([`ready_vms`]) and session bundles
//! ([`session_bundles`]).

pub mod acp;
pub mod attachments;
pub mod bridge;
pub mod capacity;
pub mod config;
pub mod connection;
pub mod credentials;
#[path = "../../../../ee/runner/tinfirm-runner-host/src/digitalocean.rs"]
pub mod digitalocean;
pub mod docker;
pub mod egress_proxy;
pub mod git;
pub mod git_proxy;
pub mod homes;
pub mod host;
pub mod images;
pub mod isolation;
pub mod limits;
pub mod maintenance;
pub mod mapper;
pub mod mcp;
pub mod mirror;
pub mod model_proxy;
pub mod node;
pub mod outbox;
pub mod output;
pub mod paths;
pub mod pool;
pub mod public_suffix;
#[path = "../../../../ee/runner/tinfirm-runner-host/src/ready_vms.rs"]
pub mod ready_vms;
pub mod redact;
pub mod run;
pub mod runtime_env;
pub mod sandbox;
pub mod sandbox_files;
pub mod secret_eq;
pub mod secure_fs;
#[path = "../../../../ee/runner/tinfirm-runner-host/src/session_bundles.rs"]
pub mod session_bundles;
pub mod skills;
pub mod steer;
pub mod takeback;
pub mod usage;
pub mod util;
pub mod version;
pub mod vm;
pub mod vm_boot;
