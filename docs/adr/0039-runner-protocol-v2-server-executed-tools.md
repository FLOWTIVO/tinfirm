# ADR-0039: Runner protocol v2: one request frame, server-executed tools and /output

- Status: Accepted
- Date: 2026-10-05
- Decided by: the owner, with the platform design of 2026-10-05 ([PLATFORM.md](../archive/v2/PLATFORM.md) §6, §7.2, §12); the details were settled and built in package 8 of the restart
- Sources: [archive/v2/PLATFORM.md](../archive/v2/PLATFORM.md) §3 (principle 5), §7.2, §12; CONVENTIONS §14.10; [`protocol/runner-v2.schema.json`](../../protocol/runner-v2.schema.json); the [runner host's README](../../runner/README.md#protocol-versions); [operations/runner-protocol-v2.md](../operations/runner-protocol-v2.md); [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0021](0021-agent-attachments.md), [ADR-0037](0037-restart-on-the-platform-design.md)
- Changes: the runner protocol of v1 (archived CONVENTIONS §15), which stays for hosts that speak it

## Context

- Protocol v1 had a pair of frames for each platform request: `run.ask`, `run.repository_request` and `run.task_create`, each with its own ack, its idempotent request id, its resend after a reconnect and its exactly-one-ack rule. The restart needs more kinds of request: access to a connection's tools, a link the agent saves, and calls to the company's systems. Each would have been one more pair with the same machinery.
- Specialists outside software development (analysts, testers, finance) work from the company's systems: they read an issue, search Jira. The tokens for those systems must stay in the deployment (ADR-0007; PLATFORM.md principle 5): the sandbox's network is open, and a prompt-injected agent must not hold a token with the scopes of a service account.
- Documents were saved one call at a time (`upload_attachment`, ADR-0021); a non-code specialist's result is often several files.
- Runner hosts are installed on machines the company runs; the server and the hosts are upgraded at different times.

## Decision

**Protocol v2** (`protocol/runner-v2.schema.json`) stands next to v1:

- **One request frame.** `run.request {runId, epoch, requestId, kind, payload}` from the host and `run.request.ack {runId, requestId, status, payload | code, message}` from the server replace v1's request pairs. Kinds: `ASK` (a question), `ACCESS` (a repository or a connection's tools), `ARTIFACT` (a link), `TOOL` (a call of a server-executed tool). Every request gets exactly one ack, also for a run that is not active; a request id sent again never raises a second decision, artifact or tool call; the host resends a waiting request after a reconnect and waits per kind.
- **Server-executed tools.** `run.assign.tools[]` lists the tools of the run with their input schemas: `{handle}_get` and `{handle}_search` of each connection the agent has tool access to, or that an approved `ACCESS` decision gave its thread, named by the connection's handle. The runner host serves them over its MCP listener next to the platform tools and forwards each call as a `TOOL` request. The server checks the call in one transaction (the run, the tool, the access, the arguments), executes it with the connection's token outside any transaction, audits it as `tool.called`, and answers with Markdown and structured content; a result above 800 KiB is refused with a message to narrow the query. The token never leaves the server.
- **`/output`.** A writable folder in the sandbox: when the agent's turn has ended, the host scans each file there as it scans an upload and saves it as a document of the job, keeping its path.
- **Negotiation per connection.** The host names its version in `hello`; the server accepts 1 and 2, speaks the host's version on that connection, and echoes it in `welcome`; a frame of the other version is refused. A v2 host that does not get its version back refuses the session; nothing downgrades silently. Runner hosts speak v2 by default.

## Consequences

- A new kind of request adds a payload and a check, not a frame; the ack, idempotency and resend logic exists once on each side.
- Agents read the company's systems without holding a token. Access is per agent and per thread, decided by people, and audited per call (`tool.called`, `secret.accessed` with `run-tool:{runId}`); the results are other people's words in the prompt.
- A tool call is a round trip from the sandbox through the host and the server to the system, bounded by `TINFIRM_RUN_TOOL_TIMEOUT` (60 s at most) and the host's wait of 5 s more. It runs on a virtual thread, so a slow system does not hold up the host's other frames.
- The calls in flight and their answers (kept 2 minutes, for resent requests) live in one server process, like the runner connections: one replica until that state is shared (threat model R29, R30).
- What a tool returns crosses the runner host and enters the sandbox, where the open egress could send it on (threat model R24, R34).
- Results above 800 KiB need an HTTPS side channel, which is later work.
- Two protocol versions are maintained until no v1 host is left. A v2 host refuses a server from before package 8, so the server is upgraded first ([operations/runner-protocol-v2.md](../operations/runner-protocol-v2.md)).
- Documents need no tool call: an agent writes files to `/output`, and the host's scans and limits apply as for an upload.

## Alternatives considered

- **The system's own MCP server inside the sandbox, with a token.** Rejected: the token would sit in a sandbox with open egress, in reach of a prompt-injected agent, with every scope the token has (ADR-0007).
- **Tools executed on the runner host, with the token sent in `run.assign`.** Rejected: it spreads the company's tokens to every host, personal ones included, while the server already holds the tokens, the access rules and the audit log.
- **One more pair of frames per new kind, as in v1.** Rejected: four more pairs, each with its own ack, idempotency and resend logic.
- **An HTTPS side channel for results from the start.** Deferred: 800 KiB in one frame covers issues and searches; a larger result gets a message to narrow the query.
- **Replace v1 at once.** Rejected: hosts in the field would stop working with the server's upgrade; negotiation per connection lets a fleet move one host at a time.
