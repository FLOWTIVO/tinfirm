# Solution design: granular tool permissions per agent

Status: design, for the owner's decisions (§6) · 2026-10-07 · Owner: J. Balcar

The request: **set, per agent, which tools it may use**, down to one tool: deny an agent `request_network_access`, deny it a tool that updates an issue, and so on. This document says what the platform does today, where the gap is, the design that closes it, what it touches, the order to build it in, and the points the owner decides. An ADR (0046) is written from §6, as [ADR-0045](../adr/0045-mcp-server-for-peoples-clients.md) was written from the [MCP server analysis](2026-10-07-mcp-server-analysis.md).

## 1. What exists

An agent's run offers three kinds of tool through the runner host's MCP listener (PLATFORM.md §7.2). What a person can set about each today:

| Kind of tool | Examples | Who serves it | Per company | Per agent | Per thread |
|---|---|---|---|---|---|
| **Platform tools** | `ask`, `finish`, `request_access`, `request_network_access`, `save_artifact`, `list_repositories` | The runner host itself (`runner/.../mcp.rs`, `platform_tools_v2`); `request_network_access` only on a host with the egress proxy ([ADR-0044](../adr/0044-sandbox-egress-proxy.md)) | nothing | **nothing**: every agent has every platform tool | nothing |
| **Reads of a tracker connection** (`JIRA_DC`, `GITHUB`, `GITLAB`) | `jira_get`, `jira_search` | The server (`RunTools`, `ToolCalls`) with the connection's token | nothing | **per connection**: `READ` or `WRITE` (`agent_tool.access`); both give the same two reads today | an `ACCESS` decision grants the thread the connection (`job.granted_tools`) |
| **Tools of an `MCP` connection** ([ADR-0040](../adr/0040-mcp-tool-connections.md)) | `crm_search_customers`, `crm_update_customer` | The server, calling the company's MCP server with the connection's token | **per tool**: the catalogue's policy `ALLOW` or `DENY` (`connection_tool.policy`), the company's switch | **per tool**: the names the agent was granted (`agent_tool.tool_names`); a tool not named is not available | an `ACCESS` decision grants the thread **every** served tool of the connection, whatever the agent's own names |

Beside the tools, an agent has **policies** (`changeRequest` `ALLOW` or `ASK`, `sourceWrite` `ALLOW` or `ASK`: what raises an `APPROVAL` decision), a **usage** rule (who may give it work, ADR-0035), and **network hosts** a member granted it for good (ADR-0044, `agent_network_host`).

Where the checks run today, which is what the design has to keep:

- **Listing.** The server builds `run.assign.tools` per run (`RunTools.ofRun`: the connections the agent may use or the thread was granted; of an MCP connection the granted names, or all served tools under a thread grant), and the runner host lists them next to the platform tools in `tools/list`. The platform tools are listed by the host from a fixed table; the server has no say.
- **Executing.** A `TOOL` request is checked on the server in one transaction (`ToolCalls.check`: the run active, the tool one of the company's, `RunTools.mayCall`, the arguments) before the token is revealed. `ASK`, `ACCESS`, `ARTIFACT` and `NETWORK` requests are checked in `RunnerSessionHandler.request` for shape and limits, never for permission: there is no permission to check.
- **Auditing.** `tool.called` per executed call; `agent.access.changed` per change of access; nothing for a refused call.

Two things are not tools and stay out of scope: the write-back (PLATFORM.md §8, the trigger's own actions and the end-of-turn comment) and pushes to `agent/*` branches, which repository access governs (ADR-0017).

## 2. The gap

The owner's two examples, against the table:

- **Denying requests for network access.** `request_network_access` is a platform tool: every agent has it on every host that runs with the egress proxy. Nothing in the agent's settings removes it, and a member in the inbox can only decline each request as it comes. The same holds for `ask`, `request_access` and `save_artifact`.
- **Denying a tool that updates an issue.** Today no tool of Tinfirm's own updates an issue: the tracker tools are the two reads, and writes "come later, behind `APPROVAL`" (PLATFORM.md §7.2, §14). An `update_issue` an agent can call today is a tool of the company's own MCP server. For those, ADR-0040 already gives two switches: the company denies the tool for everyone (catalogue `DENY`), or does not grant it to the agent. But **not granted is not denied**: a member who approves the agent's `request_access` for the connection gives the thread every served tool, the ungranted ones included. And when Tinfirm's own tracker writes come, `agent_tool.access` knows only `READ` and `WRITE` for the whole connection: an agent that may comment may also transition.

So the gap is one model, in three parts: the platform tools have no per-agent switch; the tracker tools are granted per connection, not per tool; and no tool has a **deny** that a thread's grant cannot lift. ADR-0040 considered "a policy per agent and tool (allow, ask, deny)" and chose the grant plus the company's switch instead. The owner's request reopens that choice for the per-agent part.

## 3. The design

### 3.1 One rule: the mode of a tool for an agent

Every tool a run can offer gets a **mode** for the agent, from one rule, in one place (the run module, as `RunTools.mayCall` is today):

| Mode | Listed in the run | A call | Who sets it |
|---|---|---|---|
| `ALLOW` | yes | runs | the agent's settings, or a thread's `ACCESS` grant |
| `ASK` (package 3, §5) | yes, marked | raises an `APPROVAL` decision; the result comes with the next turn | the agent's settings |
| `DENY` | **no** | refused by the server with `TOOL_DENIED`, whatever the thread was granted | the agent's settings; for an MCP tool also the company's catalogue |
| not granted | no | refused with `NO_ACCESS`, which tells the agent to `request_access` | the default of a connection's tool the agent was not given |

The rule resolves in this order; the first that applies wins:

1. **The company's switch.** A tool of an `MCP` connection whose catalogue policy is `DENY`: `DENY` (exists, ADR-0040).
2. **The agent's own entry for the tool** (new): `ALLOW`, `ASK` or `DENY`.
3. **The agent's level for the connection** (exists, narrowed): `READ` allows the connection's read tools; `WRITE` allows its read tools and, when write tools exist, gives them the `sourceWrite` policy as their mode (`ASK` by default). An `MCP` connection's level grants nothing by itself: its tools are granted by name, as today.
4. **The thread's grant** (exists, narrowed): an approved `ACCESS` decision for the connection makes every served tool that is not denied by 1 or 2 `ALLOW` for that thread.
5. **The default**: a platform tool is `ALLOW`; a connection's tool is not granted.

Two consequences carry the request: a `DENY` of the agent beats a thread's grant (step 2 before step 4), and a platform tool can be denied (step 2 applies to it; step 5 is its default). `ASK` is the mode that PLATFORM.md §4.7 and §14 already promise for tools with write effect; it is designed here so that the data model holds it, and built last (§5).

### 3.2 Platform tools: four switches on the agent's policies

The platform tools are few and fixed, and denying one is a statement about how the agent works with people, like `changeRequest` and `sourceWrite`. So they join the agent's **policies**, as four more fields, each `ALLOW` (the default) or `DENY`:

| Policy | Tool | `DENY` means |
|---|---|---|
| `ask` | `ask` | The agent cannot put questions to people. An `ASK` request is refused with `TOOL_DENIED`; the open questions of a `BLOCKED` result stay text in the end-of-turn comment and raise **no** `QUESTION` decision (today `JobService.openQuestions` turns them into decisions, which would be `ask` by another door); the prompt's `# How to work` says to decide within the request or finish `BLOCKED` and say what is missing. |
| `requestAccess` | `request_access` | The agent cannot ask for repositories or connections beyond what it has. An `ACCESS` request is refused; `list_repositories` lists no requestable repositories (`run.assign.requestableRepositories` is empty); `NO_ACCESS` messages stop saying "ask for it with `request_access`". |
| `requestNetworkAccess` | `request_network_access` | The agent cannot ask for network hosts. A `NETWORK` request is refused; the hosts already granted (`agent_network_host`, `job.granted_hosts`) stay in `allowedHosts` until revoked, as today. Has an effect only on a host with the egress proxy, where the tool exists. |
| `saveArtifact` | `save_artifact` | The agent cannot save links or files by the tool. An `ARTIFACT` request is refused; the upload endpoint refuses `artifact=true` uploads from the run's host for files the agent named (`403`, logged). The files the agent leaves in `/output` are still saved: `/output` is the job's result channel, not a tool, and the host saves it without a call. A company that wants no documents from an agent has no switch for that here (§6). |

`finish` and `list_repositories` have no switch: `finish` is how a run reports, and `list_repositories` names only what the run already has. `PolicyMode` gains `DENY`; the spec says per policy which values it takes (`changeRequest`, `sourceWrite`: `ALLOW` or `ASK`; the four new ones: `ALLOW` or `DENY`; the others `400 VALIDATION_FAILED`).

**Enforced in two places.** The server sends the host the list of platform tools of the run (§3.6), so the agent does not see a denied tool, and it refuses the request kinds of denied tools (§3.4), so a host that lists them anyway, an older host or a process that reaches the listener (threat model R28) gets nothing. The second is the control; the first keeps the agent from trying.

### 3.3 Connection tools: a mode per tool, for every kind

**The catalogue of every connection with tools.** An `MCP` connection has one (`connection_tool`, read from `tools/list`). A tracker connection gets a **fixed** catalogue the server knows: `get` and `search` today, each with an **effect** (`READ`), and later the writes (`comment`, `transition`, `label`, `close`, …) with effect `WRITE`. It is not stored: `ConnectionTools.tools(connection)` returns it for the `SOURCE` kinds, and `GET /connections/{id}` and the list show it as `Connection.tools` for every kind with tools, with `policy` `ALLOW` and `present` `true` for a tracker's. One shape for the web and for `RunTools`. A company switch for a tracker's tools (a `DENY` in that catalogue) is possible later by storing rows for them; it is not part of this design (§6).

**The agent's entries.** `agent_tool.tool_names` (a JSON array of granted names, `MCP` only) becomes `agent_tool.tools`: a JSON object from tool name to mode, for every kind, holding only the agent's **explicit** entries:

```json
{ "search_customers": "ALLOW", "update_customer": "DENY" }
```

- For a tracker connection, `access` keeps its meaning as the level (step 3 of §3.1) and `tools` holds the exceptions: an agent with `READ` and `{"search": "DENY"}` has `get` only. Today the map is empty for every tracker row (the migration leaves it so); it fills when the writes come and people pick them one by one.
- For an `MCP` connection, `tools` is the grant, as `tool_names` was (migration: each name becomes `"ALLOW"`), plus denials. A row may hold denials only: "this agent never calls `delete_customer`, and holds nothing else of this server". So **a connection is in a run when the agent has an allowed tool of it or the thread was granted it**; a row with denials only brings the connection into no run, is not `ALREADY_IN_WORKSPACE` for `request_access`, and `AgentView.tools()` stops meaning "has access" (a new `AgentView.allowedConnections()` does; `mayCall` resolves per tool).
- `request_access` for a connection whose every served tool the agent is denied is refused with `TOOL_DENIED`: a decision nobody could satisfy is not raised. With some tools denied and others not, the request is a decision as today, and the approval lifts only what is not denied; the `# Answers` line names what the thread got.
- Limits as today: at most 50 connections; a tool name must be in the catalogue (a tracker's fixed one, or the `MCP` catalogue, listed or not); the same name once; stored sorted by name.

**Why not drop `READ`/`WRITE` for a map only.** The level is the sensible default for a tracker: "may read Jira" is what most agents need, and a new write tool in a release joins under the agent's level and `sourceWrite` policy without anyone editing fifty agents. The map is for exceptions. For `MCP` servers the level was already a marker, and stays one.

### 3.4 Enforcement in a run

All on the server, before anything executes or is revealed:

| Where | Today | With the design |
|---|---|---|
| `RunTools.ofRun` | lists the tools of usable connections | lists the tools whose mode is `ALLOW` or `ASK` (§3.1); `ASK` tools get the marker in the description (package 3) |
| `ToolCalls.check` | `UNKNOWN_TOOL`, then `mayCall` else `NO_ACCESS` | after `UNKNOWN_TOOL`: mode `DENY` is `TOOL_DENIED` ("Your agent may not call `x`. Tell a person in your summary."); not granted is `NO_ACCESS` with the `request_access` hint only when `requestAccess` is allowed; `ASK` raises the decision (package 3) |
| `RunnerSessionHandler.request` | shape and limits per kind | first, the kind's policy: `ASK` with `ask` denied, `ACCESS` with `requestAccess` denied, `NETWORK` with `requestNetworkAccess` denied, `ARTIFACT` with `saveArtifact` denied, each `REJECTED` / `TOOL_DENIED` with a message that says what to do instead |
| `RunnerAttachmentsController` (`artifact=true`) | saves the file as a document | `403` for a file the agent named with `save_artifact` when the policy is `DENY`; the host's `/output` uploads at the run's end pass (they are marked by the host; today both go through `artifact=true`, so the host sends `source=output` or the server tells them apart by the run's state, to settle in the build) |
| `JobService.openQuestions` | a `BLOCKED` result's questions become `QUESTION` decisions | not when the agent's `ask` is `DENY`; the comment keeps them as text |
| `AssignmentBuilder` | `requestableRepositories`, `allowedHosts`, `tools` | plus `platformTools` (§3.6); `requestableRepositories` empty under `requestAccess` `DENY` |
| `PromptRenderer` | `# How to work` names `ask` and `request_access` | names only the allowed platform tools; one line per denied one that says what to do instead ("You cannot ask questions in this job: …") |

The refusal code `TOOL_DENIED` is new, next to `NO_ACCESS` and `UNKNOWN_TOOL` (CONVENTIONS §14.10's table). It differs from `NO_ACCESS` on purpose: the agent should not spend a decision on what no member can grant it.

### 3.5 API

Nothing is released yet (README, status), so the v2 contract can change shape where that is cleaner than an added field.

- `AgentPolicies` gains `ask`, `requestAccess`, `requestNetworkAccess`, `saveArtifact` (`PolicyMode`, with `DENY` added to the enum); required in responses, optional with defaults in `AgentCreateRequest`, optional in `AgentUpdateRequest` (an absent field stays). A `DENY` on `changeRequest` or `sourceWrite`, or an `ASK` on the four, is `400 VALIDATION_FAILED`.
- `AgentToolAccess.tools` and `AgentToolAccessInput.tools`: from `string[]` to `[{ name, mode }]` (`ToolMode`: `ALLOW`, `DENY`; `ASK` joins in package 3), for every kind; `null` or empty for a tracker row without exceptions. `access` stays required and keeps its rules (`READ` only for `MCP`).
- `Connection.tools` is present for every kind with tools; a tracker's entries carry `effect` (`READ`, `WRITE`), `policy` `ALLOW`, `present` `true`, and a description. `ConnectionTool` gains `effect`, `null` for an `MCP` tool (unknown; `readOnlyHint` later, PLATFORM.md §14).
- `PUT /agents/{id}/tools` and `PUT /connections/{id}/tools` keep their paths and permissions. No new path, so the tenant isolation test needs no new entry; `AgentIntegrationTest` covers the rules.
- The MCP endpoint for people's clients (ADR-0045): `get_agent` renders the new policies and the modes (`Renderings.agent`); no new tool.

### 3.6 Protocol

One optional field in `run.assign`, protocol v2 only:

```json
"platformTools": {
  "type": "array",
  "description": "The platform tools the agent may use in this run, by name. The host lists a platform tool only when it is named here and the host offers it (request_network_access needs the egress proxy). Absent: every platform tool, as before.",
  "items": { "enum": ["ask", "finish", "request_access", "request_network_access", "save_artifact", "list_repositories"] }
}
```

An allowlist, so a host that gets the field lists nothing the server did not name; `finish` and `list_repositories` are always in it. The host filters `tools_list()` and `instructions()` by it, and refuses a call of an unlisted platform tool as it refuses an unknown one. An older host ignores the field and lists everything; the server's refusals (§3.4) still hold, and the agent gets `TOOL_DENIED` with its explanation. A v1 host gets nothing new (v1 has no `run.request` to refuse; the server refuses `run.ask` under `ask` `DENY` with the same code, the one v1 kind the policies touch). The schema has a code owner, who reviews the change (CONVENTIONS §2). `tinfirm-protocol`'s `RunAssign` struct and `RunnerMessages.RunAssign` gain the field; `schema.rs` and the mcp.rs tests cover the filtering.

### 3.7 Data

`V020__agent_tool_policies.sql`:

```sql
ALTER TABLE agent ADD COLUMN policy_ask                    varchar(8) NOT NULL DEFAULT 'ALLOW';
ALTER TABLE agent ADD COLUMN policy_request_access         varchar(8) NOT NULL DEFAULT 'ALLOW';
ALTER TABLE agent ADD COLUMN policy_request_network_access varchar(8) NOT NULL DEFAULT 'ALLOW';
ALTER TABLE agent ADD COLUMN policy_save_artifact          varchar(8) NOT NULL DEFAULT 'ALLOW';
-- each: CHECK (... IN ('ALLOW', 'DENY'))

ALTER TABLE agent_tool ADD COLUMN tools jsonb;
UPDATE agent_tool SET tools = (SELECT jsonb_object_agg(name, 'ALLOW') FROM jsonb_array_elements_text(tool_names) AS name)
    WHERE tool_names IS NOT NULL;
ALTER TABLE agent_tool DROP CONSTRAINT agent_tool_names_ck;
ALTER TABLE agent_tool DROP COLUMN tool_names;
ALTER TABLE agent_tool ADD CONSTRAINT agent_tool_tools_ck CHECK (tools IS NULL OR jsonb_typeof(tools) = 'object');
```

The columns follow `policy_change_request` and `policy_source_write`; the map follows the repository's habit of JSON arrays and objects for small per-row sets (`tool_names`, `granted_tools`, `granted_hosts`). The modes' values are checked by the service (`ALLOW`, `DENY`, later `ASK`), as the names are today. Row-level security and grants do not change: the tables exist. Package 3 adds the decision's payload (§5).

### 3.8 Web

- **Permissions tab** ("Who may give it work, and what needs approval"): a third group, **What it may do**, with four rows on the segmented control the policies use, `Allow` / `Deny`: "Ask people questions", "Request access to repositories and connections", "Request network access" (with the hint that it matters on runner hosts with the egress proxy), "Save files and links as artifacts" (with the hint that `/output` is still saved). The glance summary counts the denials ("2 tools denied").
- **Access tab**, Tools section: every connection with tools shows its catalogue. A tracker keeps its level control and lists its tools under it, each with its effective state and a control `Deny` / `Default` / `Allow` (the default being what the level gives; `Ask` joins in package 3); a write tool is marked `writes`. An `MCP` server's tools change from a checkbox to the same control, with `Default` meaning not granted, the badges "denied for everyone" and "no longer listed" as today, and "All" / "None" acting on the allows. `AccessDraft.tools[id].tools` becomes the map.
- **Connection page**: a tracker's tools are shown read-only under **Tools**, as an MCP server's catalogue is, with their effect; the allow/deny checkboxes stay for `MCP` only until a company switch for trackers exists.
- **Run and thread pages**: a refused call is in the transcript as the tool's error, as any refusal is; nothing new. The inbox sees fewer requests; nothing new.

### 3.9 Audit, limits and the threat model

- `agent.created`, `agent.updated`: `policies` carries the six policies; a changed one is in `changed`. `agent.access.changed`: `tools[].tools` carries name to mode (CONVENTIONS §14.3).
- **`tool.denied`** (new, actor `AGENT`, target `run`): `jobId`, `tool` (the platform tool's name, or the connection's tool name as the run would list it), `connectionId` when there is one, `kind` (`ASK`, `ACCESS`, `NETWORK`, `ARTIFACT`, `TOOL`). Recorded **once per run and tool**: a prompt-injected agent that keeps calling a denied tool is one row, not a flood in the hash chain; every attempt is a `LOG` event of the run, so the run page shows them. The run's wall-clock and turn limits bound the rest.
- No new rate limit: a refusal costs one transaction and reveals nothing.
- Threat model: 9.1's mitigation gains "a tool the company denied the agent is refused on the server, whatever the thread was granted"; R33 narrows (a member who decides can no longer grant a thread a tool the agent is denied); a new row under TB6 or TB9 for "the agent reaches a denied tool through another door" with the two doors closed here (`finish`'s open questions, `/output` named as the one left open by design). Q19 (whether tool access needs an OWNER or ADMIN) stays open; §6 says why.

## 4. Alternatives considered

- **Deny by instruction.** Tell the agent in its instructions not to ask for network access. Rejected: instructions are advice to the model (R15); a prompt-injected agent ignores them, and the request still lands in a member's inbox.
- **A company-wide switch per platform tool** ("no agent of this company may request network access"), instead of per agent. Not what was asked: the owner wants it per agent, and one strict agent next to a free one. A company default that new agents inherit is a possible later addition behind the same fields.
- **One table `agent_tool_policy (agent, connection?, tool, mode)` for platform and connection tools alike.** Uniform storage, but the API and the web would still show platform tools among the policies and connection tools under access, so the uniformity buys nothing people see; it also leaves `agent_tool.access` and `tool_names` beside it. The design keeps each where its kind already lives.
- **A map only, no `READ`/`WRITE` level.** Every tool named explicitly for every agent. Cleaner in theory; in practice every new tracker write tool would need every agent edited, and "may read Jira" is what most agents need. The level stays as the default, the map as the exception (§3.3).
- **Deny as a host-side filter only** (`run.assign.platformTools` without server refusals). Rejected: the host is semi-trusted (R28), an older host ignores the field, and the sandbox can send any frame the host forwards. The server refuses; the host's filter is the courtesy that keeps the agent from trying.
- **Additive API fields** (`deniedTools: string[]` next to `tools: string[]`) to avoid changing `tools`' shape. Possible, and the right move after a release; before one, two lists that must not overlap are worse than one map with a mode, which also takes `ASK` without a third list.
- **`ASK` first, or `ASK` never.** The owner asked for deny; `ASK` is designed so that the model takes it (one more enum value, one decision payload) and built when the tracker writes need it (§5). Not designing it now would risk a second shape for the same setting.

## 5. Build order

Each package lands with its tests and documentation, like the restart's (the [restart review](2026-10-05-restart.md) gets a row per package).

| # | Package | Contents | Touches |
|---|---|---|---|
| 1 | **Platform tool policies** | The four policies and `PolicyMode.DENY`; `V020` (the columns); `run.assign.platformTools` in the schema, `tinfirm-protocol`, `RunnerMessages` and `AssignmentBuilder`; the host's filtering of `tools_list` and `instructions`; the server's refusals of `ASK`, `ACCESS`, `NETWORK`, `ARTIFACT` (and v1's `run.ask`) with `TOOL_DENIED`; `requestableRepositories` empty under `requestAccess` `DENY`; `openQuestions` under `ask` `DENY`; the upload endpoint under `saveArtifact` `DENY`; the prompt's `# How to work`; `tool.denied`; the Permissions tab and the glance; `get_agent`'s rendering. Docs: `operations/connections.md` or a short `operations/agent-permissions.md`; proposals for PLATFORM.md §4.1, §7.2, CONVENTIONS §14.3, §14.6, §14.10, the threat model. | server, runner, protocol, web, docs |
| 2 | **A mode per connection tool** | `agent_tool.tools` (the map, the migration of `tool_names`); the fixed catalogue of tracker tools with `effect`, in `ConnectionTools` and `Connection.tools`; the rule of §3.1 in the run module (`RunTools`: listing, `mayCall` per tool, `TOOL_DENIED` in `ToolCalls`; a thread grant lifts no denial; `request_access` refused when every tool is denied); the API shapes (`tools: [{name, mode}]`, `ConnectionTool.effect`); the Access tab per tool and the connection page's read-only tracker catalogue; `agent.access.changed` with modes; the MCP servers guide §4 and §5. | server, web, docs |
| 3 | **`ASK` per tool, and the tracker writes behind it** | `ToolMode.ASK`; a `TOOL` call under `ASK` answers `PENDING {decisionId}` and raises an `APPROVAL` decision (`ApprovalAction.TOOL`, which exists as a placeholder) whose subject holds the tool and its arguments (at most 64 KiB, as a call's); the member approves or denies in the inbox; on approval the server executes the call **in the deciding transaction's follow-up**, with the token, as a change request opens on approval today (PLATFORM.md §4.7), and the next turn's `# Answers` carries the result (cut to the prompt's comment size, the whole as an artifact of the job when larger) or the denial; the `WRITE` level with `sourceWrite` as the default mode of write tools; the first tracker writes (`comment`, `transition`, `label`, `close`) as tools of the fixed catalogue; the host's wait for a `PENDING` tool answer (today a `TOOL` ack is `DONE` or `REJECTED` within 65 s; `PENDING` ends the turn like `request_access`). The MCP servers guide §7 stops saying that writes are unguarded. | server, runner, protocol, web, docs |

Package 1 alone answers the first example in the owner's request and is the smallest change: six files on the server for the rule and the refusals, the schema field, one host filter, one tab. Package 2 answers the second example for `MCP` tools in full and prepares the tracker writes. Package 3 is the "later work" of PLATFORM.md §14 given its shape; it can wait until a tracker write is wanted.

## 6. Open points for the owner

| # | Question | Recommendation |
|---|---|---|
| 1 | **Does an agent's `DENY` beat a thread's `ACCESS` grant?** Today a grant gives the thread every served tool of the connection. | Yes. That is what "deny" means; "not granted" stays the state a member can lift for a thread. Without it, the second example is not met for `MCP` tools. |
| 2 | **Are `finish` and `list_repositories` deniable?** | No. `finish` is how a run reports; `list_repositories` names what the run already has. Denying them gains nothing and breaks the run's shape. |
| 3 | **Is `/output` a door to leave open under `saveArtifact` `DENY`?** The host saves what the agent leaves there, without a tool call. | Yes, for now: `/output` is the result channel of every non-code specialist, and the same scans apply as to an upload. A policy "no documents" would be a different switch, on the job's result, if wanted. |
| 4 | **Who may set a denial, and who may lift it?** Today whoever may change the agent changes its access: OWNER and ADMIN for every agent, an OPERATOR for its own. Under that rule an operator can lift a denial an admin set on the operator's agent. | Keep one rule for now, as for every setting of an agent, and note it under Q19 (R33), which asks the same of tool access as a whole. A company default per platform tool that an operator cannot override is the shape of a later answer; it is one more layer of §3.1 (a step 1 for platform tools). |
| 5 | **Change `tools` from `string[]` to `[{name, mode}]` before the release, or add `deniedTools` beside it?** | Change it: nothing is released, and one map with a mode takes `ASK` later without a third list. |
| 6 | **A company switch for a tracker's tools** (a `DENY` in the fixed catalogue, as for `MCP` tools), so that "no agent transitions issues" is one setting? | Later, when the tracker writes exist; it needs stored rows for the fixed catalogue and is independent of the per-agent modes. |
| 7 | **Audit every denied attempt, or once per run and tool?** | Once per run and tool in the audit log (one row, not a flood in the chain), every attempt as a `LOG` event of the run. |
| 8 | **Build `ASK` (package 3) now, or with the first tracker write?** | With the first tracker write. The model and the API take it without change; the host's `PENDING` tool answer and the decision's execution are the work, and they are worth doing when a write needs them. |

## 7. What changes where

Everything the packages touch, by path; CONVENTIONS §2 names each path's owner:

| Path | Change | Package |
|---|---|---|
| `api/openapi.yaml` | `AgentPolicies` (four fields), `PolicyMode.DENY`, `ToolMode`, `AgentToolAccess.tools` and `AgentToolAccessInput.tools` as `[{name, mode}]`, `ConnectionTool.effect`, `Connection.tools` for every kind with tools; the descriptions of `replaceAgentTools`, `createAgent`, `updateAgent` | 1, 2 |
| `protocol/runner-v2.schema.json` | `run.assign.platformTools` | 1 |
| `server/.../db/migration/V020__agent_tool_policies.sql` | §3.7 | 1 (columns), 2 (the map) |
| `server/.../agent` | `AgentPolicies`, `PolicyMode`, `AgentView` (`toolModes`, `allowedConnections`), `AgentService` (validation, audit), `AgentStore`, `AgentMapper`, `AgentsController` | 1, 2 |
| `server/.../connection` | `ConnectionTools.tools` for the `SOURCE` kinds (the fixed catalogue with `effect`), `ConnectionToolInfo.effect`, the connection mapper | 2 |
| `server/.../run` | `RunTools` (the rule of §3.1, listing), `ToolCalls` (`TOOL_DENIED`), `RunnerSessionHandler` (the kinds' policies, v1's `run.ask`), `AssignmentBuilder` (`platformTools`, `requestableRepositories`), `PromptRenderer`, `RunnerAttachmentsController` | 1, 2 |
| `server/.../runner/RunnerMessages` | `RunAssign.platformTools` | 1 |
| `server/.../work` | `JobService.openQuestions` under `ask` `DENY` (through `AgentDirectory`) | 1 |
| `server/.../audit/AuditAction` | `TOOL_DENIED` (`tool.denied`) | 1 |
| `server/.../mcp/internal/Renderings` | the agent's policies and modes in `get_agent` | 1, 2 |
| `runner/crates/tinfirm-protocol` | `RunAssign.platform_tools` | 1 |
| `runner/crates/tinfirm-runner-host/src/mcp.rs`, `run.rs` | `with_platform_tools`, filtering in `tools_list` and `instructions`, refusal of an unlisted platform tool; tests | 1 |
| `web/src/app/pages/company/agents/*` | the Permissions tab's third group and the glance; the Access tab per tool; `AccessDraft`; specs | 1, 2 |
| `web/src/app/pages/company/connections/*` | a tracker's read-only catalogue under Tools | 2 |
| `docs/adr/0046-…`, `docs/adr/README.md` | the decision, from §6 | 1 |
| `docs/PLATFORM.md` §4.1, §4.7, §7.2, §14 | policies "what the agent may do, and what needs approval"; tool access "per tool, with a mode"; `# How to work` under denials; `ASK` moved from later work to package 3 | 1, 2 |
| `docs/CONVENTIONS.md` §14.2, §14.3, §14.6, §14.10 | `TOOL_DENIED`; `tool.denied`; the policies' values and the map's rules; the kinds' refusals, `platformTools`, the rule of §3.1 | 1, 2 |
| `docs/threat-model.md` | §3.9 | 1 |
| `docs/operations/mcp-servers.md` §4, §5, §7; `docs/operations/connections.md` or a new `agent-permissions.md` | how to deny a tool to an agent; what the agent sees | 1, 2 |
