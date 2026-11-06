# ADR-0002: Rust for the runner host and the sandbox bridge

- Status: Accepted
- Date: 2026-09-26

## Context

Every run executes on a runner host. A runner host is any machine or cluster with Docker or Kubernetes, including a member's laptop. The runner host:

- connects out to the server;
- holds subscription credentials;
- starts one sandbox per run;
- runs the ACP client, maps ACP events to `RunEvent`, relays approvals and enforces the guards;
- relays RunEvents and MCP calls;
- scans outbound content for credential values.

A prompt-injected agent can kill or change any process in its own container. The runner host must install easily on many kinds of machines.

## Decision

- The runner host is a Rust daemon, built on tokio, the ACP Rust SDK 1.x, bollard (Docker), kube-rs (Kubernetes), rustls and serde. It ships as one static binary per OS or as a container image.
- All runner logic lives in the runner host, outside the sandbox.
- The sandbox contains only the **bridge**: a static musl Rust binary of a few MB. It starts the runtime's ACP adapter and pipes its stdin and stdout to the runner host. It buffers output for up to 25 s while the runner host reconnects. If the runner host is gone for 25 s, it stops the agent (SIGINT, then SIGTERM).
- The git sidecar is the git CLI plus a small Rust wrapper.
- JSON Schemas in `protocol/` define the runner host messages. They generate the Java and the Rust types.

## Consequences

- Guards, credential scanning and the ACP client are out of the agent's reach.
- The bridge needs no language runtime in the agent image.
- The team maintains two backend languages. Mitigations:
  - The `protocol/` schemas are the only contract, with generated types on both sides.
  - CI runs contract tests of the real runner host against the server.
  - The runner host stays small, with a narrow interface. The crates do the heavy lifting.
- Contributors to the runner host need Rust experience.
- The Cargo workspace follows the Maven `${revision}` version.

## Alternatives considered

- **A Java runner host.** Not chosen. Rust is a choice, not a workaround: it gives the ACP Rust SDK 1.x and a single static binary.
- **Runner logic inside the sandbox.** Rejected. A prompt-injected agent could stop or change the guards and the scanning.
- **The server starts sandboxes itself.** Rejected; see [ADR-0006](0006-runner-hosts-and-isolation-tiers.md).
