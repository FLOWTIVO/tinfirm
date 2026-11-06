# Agent permissions: what an agent may use

The public guides are [Make and share an agent](../../docs-site/content/user-guide/agents.md) (https://docs.tinfirm.com/user-guide/agents/) and [My settings for an agent](../../docs-site/content/user-guide/my-settings.md) (https://docs.tinfirm.com/user-guide/my-settings/), for members; approvals are decided as [Your inbox](../../docs-site/content/user-guide/inbox.md) (https://docs.tinfirm.com/user-guide/inbox/) says. This page keeps what the people who build Tinfirm need beyond them: the design and its rules, the endpoints, tables and error codes, why settings cannot widen an agent, the internals of approvals and requests, the checks every tool call passes, and the audit.

The decision is [ADR-0058](../adr/0058-authority-from-the-audience.md) (decisions 4, 8 and 9); the rules are [CONVENTIONS §15.5](../CONVENTIONS.md#155-agents-and-settings) and [§15.7](../CONVENTIONS.md#157-conversations).

## 1. The agent's definition

- **Systems and tools.** Each tool the agent lists is `ALLOW` (**Allowed**) or `ASK` (**Needs approval**). There is no deny: a tool the agent does not list is not available. The tools of Jira, GitHub, GitLab and Slack are Tinfirm's own; an MCP server's are its catalogue, whose effects an admin confirms ([systems.md](systems.md), [mcp-servers.md](mcp-servers.md)). A tool whose effect nobody confirmed counts as a write.
- **Skills** are pinned to a version ([ADR-0064](../adr/0064-skills-library.md)).
- **Change requests.** On GitHub and GitLab, the write `open_change_request` is not called by the agent: after a turn that pushed commits, Tinfirm opens the pull or merge request under that tool's mode.
- **Platform tools** (`ask`, `finish`, `request_access`, `request_network_access`, `save_artifact`, `list_repositories`, and `remember`, `forget`, `recall` while memory is in effect, [agent-memory.md](agent-memory.md)) are Tinfirm's own and are not set per agent. `request_network_access` is listed only on runner hosts that run with the egress proxy ([ADR-0044](../adr/0044-sandbox-egress-proxy.md)).

## 2. Who may edit an agent

| Who | Refused |
|---|---|
| A member it is shared with, editing it | `403 AGENT_NOT_EDITABLE` |
| OWNER, ADMIN (and an instance admin acting in the company), using it other than as its owner or through a share | `403 AGENT_NOT_USABLE` |
| Anyone else | `404` |

OWNER and ADMIN give an agent a new owner, an active member, with `PUT /agents/{agentId}/owner`.

## 3. Sharing an agent

`PUT /agents/{agentId}/shares`, kept in `agent_share` and `agent_member_share`, at most 50 groups and 100 members. A deleted group, or a member who leaves, is taken from the shares as `agent.shares_changed` with the `cause`. A trigger can name only an agent its author may use; taking the agent from its author pauses the trigger ([triggers.md](triggers.md)).

## 4. Versions, and what waits for acceptance

Every change of an agent's definition is a version (`agent_version`), which keeps the ceiling, the instructions and the skills it pins (V080). A version **widens** when it adds a system, a tool, a repository or a network host, raises a tool towards `ALLOW` or a repository to `WRITE`; and, when the ceiling after it **can act** (a tool `ALLOW` chosen for a write, a `WRITE` repository or a network host), when it changes the instructions, adds a skill or moves a pin (the owner's decision Q4 of the security review of 2026-10-27). A widening reaches a person's conversations only after they accept it (`POST /agents/{agentId}/accept`), and a trigger's runs after its author does (`POST /triggers/{triggerId}/accept`); until then their turns use the current ceiling narrowed by the accepted one, with the accepted version's instructions and skills. `Agent.widening` and `AgentSettings.widening` show what waits: the ceiling's additions, `instructions` (`accepted` and `current`, which the app shows as a diff) and `skills` (each skill with the version it pins and the one it was).

Everything else reaches everyone's next turn at once: narrowing, a skill removed, and a change of the instructions or skills within a ceiling that cannot act. The last is audited as `agent.instructions_changed` and announced in each conversation by its next turn that uses it: "The agent's instructions changed on 2026-10-27 by Jane Doe." Names, descriptions and runtimes follow the current version. The editor's own settings follow their change when they had accepted the version before it.

## 5. Settings narrow, never widen

### 5.1 A person's own settings

`GET`, `PUT`, `DELETE /agents/{agentId}/settings/me`. A person's settings are made the first time they use the agent, save them or accept a version.

### 5.2 A trigger's settings

`PUT /triggers/{triggerId}/settings` ([triggers.md](triggers.md)): no added instructions; the author's own MCP servers only while every account of the trigger's table is the author's own (else `400 SETTINGS_WIDEN`); a subscription pays only when the company allows one for a trigger and the author pays (ADR-0085).

### 5.3 Why they cannot widen

On the agent's systems a setting only switches a system off, makes a tool ask, or switches it off; the request takes no **Allowed** for them. A save is checked against the agent's current definition (`400 SYSTEM_NOT_ON_AGENT`, `400 UNKNOWN_TOOL`, `400 SETTINGS_WIDEN`, `400 SKILL_NOT_ON_AGENT`), and the database checks it again: when the agent changes between the check and the write, `agent_settings_widen_ck` refuses the write, the save answers `400 SETTINGS_WIDEN` and nothing is stored. Settings that name what the agent no longer lists are ignored.

## 6. Approvals

A call that waits for an approval tells the agent that it waits and to end its turn; the conversation waits on the decision.

- **Approve** runs the call right after the decision is stored, with the conversation's account, after the checks of section 7 pass again. Its result is kept with the decision (at most 50 000 characters), and the agent reads it in its next turn under `# Answers`. **Deny** sends the note, if any, to the agent with the decision. An approval is decided once (`409 DECISION_DECIDED`).
- A conversation holds at most 10 open approvals; a call past them is refused (`TOO_MANY_REQUESTS`).
- While the agent is paused, approving is refused (`409 AGENT_PAUSED`), and while the company's runs are stopped too (`409 COMPANY_STOPPED`): the approval waits. Denying is taken either way.
- A chat card's buttons decide as [chats.md](chats.md) says; from an AI client, `decide` decides the approvals of the person's own conversations only ([mcp-clients.md](mcp-clients.md)).

## 7. The checks of a tool call

Every call an agent makes passes these steps, each only narrowing the one before ([CONVENTIONS §15.7](../CONVENTIONS.md#157-conversations)). The run lists only the tools that pass steps 1 to 5 as **Allowed** or **Needs approval**, at most 200; the checks run again at each call, because an agent may call a tool its list does not show. Each refusal tells the agent why, and to tell the person in its summary when its work needs the tool.

| Step | The call passes when | Else the agent gets |
|---|---|---|
| Before the steps | The conversation is open; the tool's name is one the conversation listed or would list; the arguments are a JSON object of at most 64 KiB | `CONVERSATION_ENDED`, `UNKNOWN_TOOL`, `INVALID_REQUEST` |
| 1. Served | The system serves the tool and has it switched on; never another member's personal server | `UNKNOWN_TOOL` |
| 2. Effect | (no refusal) The tool's effect now is taken, unknown counting as a write | |
| The system | The system is not paused by an admin | `TOOL_DENIED`: *{system} is paused by an admin* |
| 3. The agent | The agent, as the person accepted it, lists the tool (or the person's own server does, in their settings) | `TOOL_DENIED`: *… is not one of {agent}'s tools; whoever edits the agent adds tools* |
| 4. The settings | The system is not switched off and the tool not **Off** in the settings; **Ask** there makes it ask | `TOOL_DENIED`: *… is switched off in {person}'s settings of {agent}* |
| Arguments | The arguments fit the tool | `INVALID_REQUEST` |
| 5. The binding | The conversation's account on the system, bound at its first use ([accounts.md](accounts.md)), is not gone, and no write goes through a read-only credential. A trigger's conversation has an account only where its table has a row | `ACCOUNT_GONE` with why; `TOOL_DENIED` naming the read-only grant or credential, or the trigger without a row there. A binding that waits for someone to connect or sign in parks the call, which runs once it holds; at most 10 parked calls per binding (`TOO_MANY_REQUESTS`) |
| Mode | **Allowed**; or **Needs approval**, which raises the approval (section 6) | `PENDING` with the decision; `TOO_MANY_REQUESTS` at 10 open approvals |
| 6. The system | The system answers for the account | The system's answer, an error included. A `401` turns the account to sign in again and parks the call; `TOOL_FAILED` when Tinfirm could not get a token right now or the call failed inside Tinfirm; `RESULT_TOO_LARGE` |

In a channel thread, Slack's `history` reads only the thread the agent was asked in; any other read is `TOOL_DENIED`. Refusals at steps 3 to 5, and at a paused system, are audited as `tool.denied` with the step (`AGENT`, `SETTINGS`, `BINDING`), once per run and tool, so an agent that tries again and again makes one row; `UNKNOWN_TOOL` is not audited.

What the agent sees: each listed tool's description ends with *Each call waits for {person}'s approval.* under **Needs approval**, and with whom it runs as: *Runs as {person}'s own account.*, *Runs through {holder} via {group}.* (*, read-only.* for a read-only grant), the trigger's account, or that the first call uses the person's account or asks them to connect one.

## 8. Asking and requesting during a run

**Questions.** A conversation holds at most 10 open questions (`TOO_MANY_QUESTIONS`).

**A repository.** `request_access` raises an approval for whoever decides the conversation's approvals. The access it offers is the agent's when the agent lists the repository, which the approver may lower, else read-only unless they pick read and write; a read-only credential keeps it read-only all the same. Refusals: `UNKNOWN_REPOSITORY`, `ALREADY_IN_WORKSPACE`, `ALREADY_PENDING`, `INVALID_REQUEST`, `TOO_MANY_REQUESTS`; a full workspace refuses the approval (`409 TOO_MANY_REPOSITORIES`). A request for a tool is `TOOL_DENIED`: tools belong to the agent's definition, which whoever edits it changes.

**A network host.** A host the agent or the conversation has already, itself or under a wildcard, is `ALREADY_ALLOWED`. A conversation holds at most 100 granted hosts and an agent at most 100 (`409 NETWORK_HOST_LIMIT`).

## 9. Pausing an agent

A paused agent's queued turns wait (`AGENT_PAUSED`). **Delete** is refused while one of its runs is queued or running (`409 AGENT_HAS_ACTIVE_RUNS`). The other switches, from a trigger's pause to stopping every run of the company, are [CONVENTIONS §15.13](../CONVENTIONS.md#1513-package-6-kill-switches-suspension-break-glasss-view-and-a-member-who-leaves).

## 10. Audit

| Action | When |
|---|---|
| `agent.created`, `agent.changed`, `agent.deleted` | An agent created; its details changed (`changed` names which); deleted. Never its description or instructions |
| `agent.tools_changed`, `agent.repositories_changed`, `agent.hosts_changed` | Its systems and tools, repositories or hosts changed: the whole list after the change, with the version (and the decision, for an approval for good) |
| `agent.skills_changed` | Its skills added, removed or moved to another version |
| `agent.widened` | A version widened the one before, with what widened (`instructions` and `skills` `changed` when they are part of it) |
| `agent.instructions_changed` | Its instructions or skills changed within a ceiling that cannot act, so the change reached everyone's next turn: `agentId`, `version`, `by`, `changed`. Never the text |
| `agent.accepted` | A person accepted a widening, with the versions |
| `agent.shares_changed` | The groups and members it is shared with changed |
| `agent.paused`, `agent.resumed` | Paused (with `stopRunning` and the runs cancelled), resumed |
| `agent.owner_changed`, `agent.passed_to_admins` | Admins gave it a new owner; its owner left |
| `settings.changed`, `trigger.settings_changed` | A person's settings, or a trigger's, changed: the systems and tools switched off or to ask, the own servers added; never the instructions |
| `decision.created`, `decision.decided`, `decision.cancelled` | An approval or a question raised, decided (with how: in the app or from a chat), cancelled |
| `decision.approver_changed` | A trigger conversation's open approval moved to the trigger's new team, or to its author |
| `tool.called` | A call that reached its system: the tool, its effect, `mode` (`ALLOW`, or `ASK` with the decision), the account and the grant it went through. Never its arguments or result |
| `tool.denied` | A refused call, once per run and tool, with the step |

## 11. Troubleshooting

Beyond the public guides' rows:

| What you see | Internal diagnosis |
|---|---|
| *{system} can no longer be used in this conversation* | `ACCOUNT_GONE` at step 5: the conversation's binding is gone, with why (`BindingTexts.gone`) |
| Approving says the agent is paused | `409 AGENT_PAUSED` |
| The agent never asks for a network host | The runner host runs without the egress proxy, so `request_network_access` is not listed |
| A save of settings is refused with `SETTINGS_WIDEN` | The agent changed while the person edited, and `agent_settings_widen_ck` refused the write |
