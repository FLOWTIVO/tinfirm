# ADR-0001: Java and Spring Boot for the server

- Status: Accepted
- Date: 2026-09-26
- Sources: CONVENTIONS §3, §4, §10
- Amended by: [ADR-0045](0045-mcp-server-for-peoples-clients.md), under which the platform's MCP server serves people's clients, not agents, on Spring AI's starter with the community MCP Security library

## Context

The server holds most of the platform:

- the REST API, SSE streams, authentication and the runner host WebSocket endpoint (`api`);
- wake evaluation, timers, the run queue, dispatch and the lease reaper (`worker`);
- the model gateway for API-key runs (`gateway`);
- the platform MCP server and the MCP proxy (`mcp`).

The product is multi-user, with OIDC and RBAC from M0.

One process that runs everything scales badly: a slow UI and out-of-memory failures.

The Claude Agent SDK exists only for TypeScript and Python. This is the main concern for a Java server.

## Decision

- The server uses Java 25 LTS and Spring Boot 4.1.
- One image holds all server roles. `TINFIRM_ROLES` selects the roles per process (`api`, `worker`). M0 runs both in one process.
- Libraries: Spring MVC on virtual threads with SSE; Spring Security for OIDC login, local accounts and run JWTs; Spring Modulith for module boundaries and the event publication registry; Flyway; Spring Data JPA for aggregates; `JdbcClient` for the queue and the event log; MapStruct.
- MCP uses the Spring AI MCP server on the official Java MCP SDK, over Streamable HTTP.
- The agent SDKs do not run in the server. Each runtime runs in its sandbox behind an ACP adapter ([ADR-0005](0005-acp-default-agent-protocol.md)).
- M0 has these Modulith modules under `com.flowtivo.tinfirm`: `shared`, `identity`, `company`, `audit`, `vault` and `bootstrap`. The build runs `ApplicationModules.verify()`.

## Consequences

- Mature OIDC, session and security support is available from M0.
- Module boundaries are checked in the build, not only by review.
- One image is one release unit. The Helm chart can still run the roles as separate deployments.
- The platform has two backend languages, because the runner host is Rust ([ADR-0002](0002-rust-runner-host-and-bridge.md)). JSON Schemas in `protocol/` are the only contract between them.
- The MCP Java SDK lags the newest MCP spec (2026-07-28). Agents support 2025-11-25, so this does not block.
- Durable execution needs no extra engine ([ADR-0003](0003-postgresql-only-state-and-queue.md)).

## Alternatives considered

- **A TypeScript or Python server** that runs the Claude Agent SDK in-process. Not chosen. ACP runs the SDK inside the agent image, so the server language does not matter for it.
- **Separate deployables for `api`, `worker` and `gateway`.** Not discussed. One image with roles selected at runtime gives the same separation at deploy time with one build.
