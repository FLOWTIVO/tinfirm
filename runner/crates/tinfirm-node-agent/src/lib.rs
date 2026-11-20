//! The node agent of ADR-0112: the one program of Tinfirm's that is root on a machine that runs
//! sandbox VMs. It is two processes, as OpenSSH's privilege separation:
//!
//! - the **listener** ([`listener`]), an unprivileged user, takes the control host's connection
//!   and reads the agent's wire ([`wire`]);
//! - the **helper** ([`helper`]), root, with no network code, does four things for the listener
//!   alone: check the node, make a VM, delete a VM, list the VMs. It takes a VM's id ([`id`]) and
//!   a template's name, derives everything else ([`plan`]), and reads the user data from one
//!   file of the listener's ([`spool`]). Its backend ([`backend`], [`linux`]) is the jail, the
//!   network, the disks and the VMM; [`bounding`] cuts the VMM's capability bounding set.
//!
//! Both wires read each line as strict JSON ([`strict`]): no object names a key twice.
//!
//! It links no other crate of the workspace. The README specifies both wires.

pub mod backend;
pub mod bounding;
pub mod helper;
pub mod id;
pub mod linux;
pub mod listener;
pub mod local;
pub mod plan;
pub mod spool;
pub mod strict;
pub mod tls;
pub mod tree;
pub mod wire;
