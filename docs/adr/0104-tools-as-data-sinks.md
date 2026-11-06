# ADR-0104: A READ tool is a destination of data: accepted, with a cap on arguments and the system's trust class shown

- Status: Accepted (not built: the `ASK` on a class change, by decision)
- Date: 2026-10-27
- Decided by: the owner, 2026-10-27
- Sources: [the security review of 2026-10-27](../reviews/2026-10-27-security-review.md), D3 and §8.6 point 2; [PLATFORM.md](../PLATFORM.md) §10 (the three layers; reads `ALLOW` by default); [ADR-0007](0007-credentials-stay-in-deployment.md) and [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md) (the server executes every tool); [ADR-0044](0044-sandbox-egress-proxy.md) (the sandbox's egress allowlist); the [threat model](../threat-model.md), TB9, R41, R64, Q42
- Changes: the threat model's R64 and Q42 and its positions table (§2.4); the 64 KiB cap on a tool call's arguments, on the runner host and in the server (`ToolCalls`, `ToolArguments`, which held it before), with boundary tests; `receivesData` and `internalHost` on the API's `ExternalSystem` and the sentence on the system's page; CONVENTIONS §14.10 and §15.2, PLATFORM §10

## Context

- A tool's arguments go to its system verbatim, and a READ tool is `ALLOW` by default (PLATFORM §10). Everything a conversation read (code, issues, documents, memory, other systems' results) can therefore be sent to any system the conversation may call, as the arguments of a READ tool, with no approval.
- The design's data-flow control is the sandbox's egress allowlist (ADR-0044). Server-executed tools, the single trust root that keeps every credential out of the sandbox (ADR-0039), bypass it by construction: the server is the deputy for every flow between systems. Prompt injection is what starts such a flow (R41); the design is what makes it legal (the review's D3).
- The effect axis (READ, WRITE) describes what a call changes at its system, not what it sends there.

## Decision

**A READ tool of any system an agent may call is a destination for what the conversation read elsewhere. This is accepted, with a cap on what one call may carry and the system's trust class shown, and no approval on a change of class.**

1. **The accepted risk** (R64): "A READ tool of any system an agent may call receives what the agent passes as its arguments, so any system in a conversation is a destination for what the conversation read elsewhere; accepted, with a cap of 64 KiB per tool call's arguments (as JSON in UTF-8, refused by the runner host after redaction and again by the server before its call journal, `INVALID_REQUEST`) and the trust class shown on each system's page as information (`receivesData`; an MCP server off the operator's internal hosts, a personal one included, named a third-party server); the `ASK` on a class change is not built."
2. **The cap.** A `TOOL` request's arguments are at most 64 KiB as JSON in UTF-8: the runner host refuses more after redaction, and the server again before its call journal (`REJECTED INVALID_REQUEST`; `ToolCalls`, `ToolArguments`), so that one call cannot carry a repository or a document whole.
3. **The trust class, as information.** A system's view says whether agents may call tools on it (`receivesData`, `SystemKind.receivesData`: every kind with tools) and whether its base URL's host is one of the operator's internal hosts (`internalHost`, never for a personal server); its page shows the first as one sentence under its name and names an MCP server off those hosts, a personal one included, a third-party server, so that whoever adds it to an agent sees where the agent's reads may go.
4. **Not built:** an enforced class with an `ASK` on the first call to a system of a lower class after a read from a higher one in the same conversation. It stays an open item (Q42).

## Consequences

- The companies decide the exposure where they decide the systems: which systems an agent lists, and which tools stay `ALLOW`. A company that does not want a vendor's server to receive what its agents read keeps that server's tools at `ASK` or off the agent.
- A flow stays possible in pieces of 64 KiB per call; the cap bounds the rate and the size, not the fact.
- The design keeps the server as the deputy (the review's §8.6 point 2) and records the cost here rather than in each system's row.

## Alternatives considered

- **The trust class enforced, with an `ASK` on a class change** (the review's fix for D3). The owner chose not to build it now: it needs a class per system and per personal server, a memory of what the conversation read from which class, and an approval that interrupts ordinary research conversations; it stays Q42.
- **Every READ tool `ASK` by default.** Closes the flow, at the price of an approval for most of what agents do.
- **No row.** The design would keep calling a flow legal that the threat model never named.
