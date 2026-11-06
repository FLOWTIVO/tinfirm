# Tinfirm platform design

Status: the design of record, built, which `main` runs. Decided part by part by the owner from 2026-10-12 ([ADR-0058](adr/0058-authority-from-the-audience.md), [ADR-0059](adr/0059-litellm-budgets-and-the-model-proxy.md)), built package by package (§12), and changed since by the ADRs §12 lists. The design of 2026-10-05 it replaced is archived in [archive/v2/PLATFORM.md](archive/v2/PLATFORM.md). Owner: J. Balcar

The reasoning behind each decision is in the [design record](reviews/2026-10-12-platform-redesign.md); the exact contract of each package is its own document (§12). The binding engineering rules are in [CONVENTIONS.md](CONVENTIONS.md), whose §15 holds the rules the code follows; its §14 is the record of the previous domain.

## 1. What Tinfirm is

Tinfirm is a self-hosted platform on which a company runs **AI specialists**: agents with a role, instructions and tools, such as a business analyst who prepares tickets, a tester, a developer, a reviewer or a finance analyst. A specialist is defined once and used by many: people talk to it in Tinfirm, from their own AI client or in a chat, and **triggers** start it from the systems the company already uses (Jira, GitHub, GitLab, a chat channel, a schedule, any software that can call a webhook). It does its work in a sandbox and brings what needs a person to the place where the work happens and to one inbox.

Two things set it apart:

- **Access follows the audience.** When a person talks to an agent, it acts with that person's own accounts, so every system answers it as it would answer the person. When a trigger starts it in a shared place, it acts with what the trigger's author chose for that place: their own accounts, or a service account and the company's credentials lent to it.
- **Each person pays for their own work.** The same agent used by several people is paid from each person's model budget; a trigger from the budget its author named.

Tinfirm is not a tracker, a board, a chat product or a cloud service. The company's systems own the work and the conversation; Tinfirm owns the agents, their runs, the decisions and the audit trail.

## 2. Principles

1. **The audience decides the authority.** Personal work acts as the person; work in a shared place acts with what its author chose for that place, never with one reader's permissions.
2. **One account or credential per system per conversation, fixed for its life.** Checked at every use, never a silent fallback. The one change is a trigger's conversation following its service account to the credential an admin lent it in place of another, said in the thread (§5.3).
3. **An agent is a definition, not an identity.** It holds no credentials; settings narrow it, never widen it.
4. **Events start work, people converse.** No agent runs without an event or a person; idle agents cost nothing ([ADR-0004](adr/0004-event-driven-wakes.md)).
5. **Credentials stay in the deployment, and none enters the sandbox** ([ADR-0007](adr/0007-credentials-stay-in-deployment.md)): systems through tools the server executes, git through the host's proxy, the model through the host's model proxy (ADR-0059).
6. **Every turn ends in a known state, where the company looks.**
7. **Fewer nouns.** A new need is a value in this model, not a new mechanism beside it.

## 3. Domain model

```
Company (tenant)
├── Member (role OWNER | ADMIN | MEMBER) ─┐
├── Service account ──────────────────────┴─ Principal: whom work acts for, and who pays
│    └── Model access: a LiteLLM key and budget (or a member's subscription on their own host)
├── Group (members; may use service accounts; receives grants; forms trigger teams; shares agents and skills)
├── System (Jira, GitHub, GitLab, git, MCP, Slack, Teams, webhook; one Sign-in app; personal MCP servers)
│    ├── Account (a member's own credential and identity on the system; one per member)
│    ├── Credential (the company's, named; any number; never on a personal server)
│    │    ├── Grant (lent to a group, READ | WRITE)
│    │    └── Lending (lent to a service account, READ | WRITE; at most one per service account)
│    ├── Repository (code hosts)
│    └── Tool catalogue (MCP: each tool's effect)
├── Trigger (a tracker's events, a schedule, a chat channel or a webhook → agent, instruction,
│            an account or credential per system, model access, team)
├── Agent (definition: instructions, runtimes, ceiling of systems, tools, repositories and hosts, modes; skills)
│    └── Settings (a person's, or a trigger's, narrowing the agent)
│         └── Memory (the agent's notes for that person, or a trigger: opt-in, ADR-0063)
├── Skill (the library: a folder in the Agent Skills format, in versions that agents pin; shared like agents)
├── Conversation (one agent, one place, accounts and model access fixed; about a subject or not)
│    ├── Turn (a run in a sandbox)
│    ├── Decision (QUESTION | APPROVAL)
│    └── Output (change request, document, link)
├── Runner host (COMPANY | PERSONAL)
└── Secret (vault), Audit event (hash-chained)
```

| Concept | Definition |
|---|---|
| **Company** | The tenant. The deployment's configuration declares an instance's companies, each by a key, with its owner and the identity providers its people sign in with; none is created in the app ([ADR-0075](adr/0075-companies-and-sign-in-from-the-deployment.md)). One provider may fill several companies from its role claim, which then decides each person's companies and roles at every sign-in ([ADR-0089](adr/0089-companies-from-the-role-claim.md)) |
| **Principal** | Whom work acts for, and who pays: a member, or a service account. Model access belongs to principals; a member's own accounts belong to the member |
| **Service account** | A non-human principal that admins manage (`serviceIdentity` in the API and the code). It never logs in; it acts through the credentials lent to it, at most one per system, and pays with its own key and budget. Groups may be given the right to use it |
| **System** | An external service registered once, with its Sign-in app. A company system holds the company's credentials, any number, each named |
| **Account** | A member's own token or sign-in on a system, and who the member is there; at most one per member per system |
| **Credential** | A company token or sign-in that a company system holds, with its name and who it is at the system |
| **Grant** | A credential lent to a group, `READ` or `WRITE` |
| **Lending** | A credential lent to a service account, `READ` or `WRITE`; a service account borrows at most one per system |
| **Model access** | Who pays: a principal's LiteLLM key with its budget, or a member's subscription on their own runner host |
| **Agent** | A specialist's definition, private to its creator until shared with groups or members |
| **Settings** | A person's narrowing of an agent; a trigger holds its own |
| **Skill** | A procedure and its files (a `SKILL.md` and any scripts or templates), in the company's library, in immutable versions; an agent pins one version of each of its skills ([ADR-0064](adr/0064-skills-library.md)) |
| **Trigger** | A standing rule: when an event happens on a tracker, a schedule is due, someone mentions the bot in a channel or a webhook delivers, an agent does an instruction, with an account or credential per system and the model access its author chose |
| **Conversation** | The work: one agent, one place, its accounts and model access fixed for its life |
| **Decision** | A question anyone in the place answers, or an approval a named person or team decides |

## 4. Principals, roles and groups

| Role | May |
|---|---|
| **OWNER** | Everything an admin may; who is OWNER, retention, holds and erasure; reading people's personal conversations in full (§9) |
| **ADMIN** | Members and groups; systems, their Sign-in apps and credentials, and lending them; service accounts and who may use them; budgets and LiteLLM; company runner hosts; secrets; every agent; the audit; the company's stop |
| **MEMBER** | Define agents and skills, create triggers, talk to agents, connect their own accounts, register personal MCP servers and a personal runner host, which serves them in every company they belong to (ADR-0092) |
| **VIEWER** | Read what the company shows its members and their own inbox; holds no seat ([ADR-0077](adr/0077-licence-editions-and-seats.md)). Never starts or continues a conversation, owns or edits an agent, authors a trigger, or answers or decides a request: one whose only candidates are Viewers goes to the Owners and Admins |

- A member's principal is created with the membership. A principal is `ACTIVE`, `SUSPENDED` (a kill switch: everything it acts for or pays stops, its key is blocked and it decides nothing, until it is resumed) or, for a member, `LEFT`. Deactivating a user suspends them in every company they belong to. When a member leaves, their accounts, owned secrets, personal MCP servers and personal runner hosts are deleted, their personal conversations end, their triggers pause until an admin hands them to an entitled author, and their agents pass to the admins. A member who rejoins gets a new principal. An OIDC provider the deployment binds to the company makes its new users MEMBER, ADMIN or VIEWER, never OWNER ([ADR-0075](adr/0075-companies-and-sign-in-from-the-deployment.md)), and VIEWER when its role holds a seat and none is free ([ADR-0077](adr/0077-licence-editions-and-seats.md)).
- A **service account** borrows at most one credential per system, lent by whoever manages the system (§5.2), and keeps what credentials alone cannot give: its own LiteLLM key and budget, which pay for the triggers that use it; its suspension, which stops everything that acts through it; and the groups that may use it. Using it on a trigger needs OWNER or a group that holds the use right; ADMIN manages it but uses it only through a group. Deleting it withdraws what it borrows and removes no credential.
- A group is a named set of members ([ADR-0049](adr/0049-agent-kinds-groups-and-personal-accounts.md)'s groups stay). It may use service accounts, receive grants, form a trigger's team and have agents and skills shared with it.

## 5. Systems, accounts and credentials

### 5.1 Systems

| Kind | Events | Tools | Repositories | Chat |
|---|---|---|---|---|
| `JIRA_DC`, `JIRA_CLOUD` | Issues, polled | Fixed catalogue | | |
| `GITHUB`, `GITLAB` | Issues and change requests, polled | Fixed catalogue | Yes | |
| `GIT` | | | Yes | |
| `MCP` | | The server's catalogue | | |
| `SLACK` | Mentions of the bot | One fixed tool, `history` | | Yes |
| `TEAMS` | Mentions of the bot | | | Yes |
| `WEBHOOK` | Deliveries | | | |
| `BUZZ` | Parked: not a kind that can be registered (§14) | | | |

Admins register company systems. A member may register a **personal MCP server**, used only by runs that act as that member, unless the company switches personal servers off. A system's **Sign-in app** is one OAuth client, so a member connects their own account, and an admin signs a credential in, with one click wherever the system offers sign-in (Jira, GitHub, GitLab, MCP servers, and Slack for `history`); for an MCP server it is found by the MCP specification's discovery. Slack's `history` reads a channel with the conversation's account or credential on Slack, never the bot's token. A chat system also has a bot (§7.3).

### 5.2 Accounts, credentials and lending

- **A member's own account**: at most one per member per system, connected on the Accounts page by sign-in (returning where the member started) or by a token Tinfirm checks with the system's "who am I". It carries the member's identity there (the Jira user, the GitHub login, the Slack user id): it links a chat user to a member, it says what "assigned to me" means for a trigger, and it keeps Tinfirm's own writes from raising events. Its secret is owned by the member (`secret.owner_user_id`) and revealed only for a call that acts as them; others see only whether they hold one and its state.
- **A credential** belongs to a company system, never to a personal server; a system holds any number, each with a name unique on it. OWNER and ADMIN add one on the system's page (**Add credential**: paste a token, or sign in to the system as the user the credential should be, where it offers sign-in), store a new token or sign it in again, rename, disable, enable and remove it. Its secret is a managed company secret. One identity on a system belongs to members' own accounts or to the system's credentials, never both; two credentials may share one (two tokens of one bot user with different scopes).
- **Lending** is done on the system's page. A **grant** lends a credential to a group, `READ` or `WRITE`, once per group. A **service account** borrows at most one credential per system, `READ` or `WRITE`; a credential may be lent to several, and a service account's page lists what it borrows. Tinfirm enforces `READ` itself: write tools are refused and the workspace is read-only.
- **Three switches.** Disabling a credential stops every use of it, by groups and by service accounts, at its next check; suspending a service account stops what acts through it, while groups' grants of the same credential go on; pausing a system stops every credential on it.
- **An MCP server that needs no account** ([ADR-0071](adr/0071-mcp-servers-without-accounts.md); `external_system.accounts_required` false, V048) is called without a credential: nobody connects an account there, its conversations bind it `OPEN`, with no account, and a trigger needs no row for it. Whoever manages the server sets it so only after Tinfirm reached the server without a credential (`initialize`, then `tools/list`); the server's own boundary, such as its network policy, is what protects it. Set to need accounts again, its conversations wait for one.

### 5.3 Binding: which account a conversation uses

| Conversation | For each system it uses |
|---|---|
| **Personal** (§8.1) | At its first use: the person's own account when it works; else a grant of a working, enabled credential to one of their groups, named on screen ("GitHub: via Analysts, read-only"), the person choosing among several; else a **Connect** card in the thread, and the call continues once they connect. When the person's own account is broken and a grant exists, the person chooses between reconnecting and the grant. An MCP server that needs no account binds `OPEN`, whatever account the person holds there |
| **Of a trigger** | What the trigger's table names for that system: the author's own account, or the credential lent to the service account the row names. An MCP server that needs no account binds `OPEN` without a row; any other system without one is not available to the trigger's runs |

The binding is kept for the conversation and checked at every use (the acting principal and the service account active; the grant and the group membership still there; the service account still borrowing the credential and the author still allowed to use it; the account or credential working and, for a credential, enabled). A failed check stops that use with the reason, addressed to whoever can repair it (`RECONNECT_YOURS`: you; `RECONNECT_COMPANY`: an admin, who is told); it never re-binds, with one exception: when an admin lends a trigger's service account another credential on the system in place of the one it borrowed, the trigger's row names the new one in that transaction, its open conversations bind it at their next check and say so in the thread, and the author is told ([ADR-0072](adr/0072-system-credentials-lent-to-groups-and-service-accounts.md), V050).

## 6. Agents and settings

| Part | Holds |
|---|---|
| **Agent** | Name, `@handle`, description, instructions; the runtimes its instructions are tested on (Claude Code, Codex, pi, OpenCode; [ADR-0041](adr/0041-pi-and-opencode-runtimes.md)); the time limit of one turn (5 to 1440 minutes, else the deployment's `TINFIRM_RUN_MAX_WALL_CLOCK`; a turn stopped there is `TIME_LIMIT`, and a retry goes on from where it stopped); the systems, tools and repositories it may use, each repository `READ` or `WRITE` ([ADR-0017](adr/0017-read-only-repositories.md)); each tool's mode (§10); the network hosts it may reach; whether memory is allowed (ADR-0063); its **skills**, each at the version it pins |
| **Settings** (one per person per agent, made on first use) | Runtime among the agent's, and a model the person's model access allows; effort; budget or subscription; instructions added to the agent's; tools, systems and skills switched off; default repositories and projects; the person's own MCP servers; memory, opt-in (ADR-0063); automatic retries, opt-in ([ADR-0073](adr/0073-automatic-retries.md)) |
| **A trigger's settings** | The same, for its runs (§7), without added instructions: the trigger's instruction is in every turn |

- Members define agents. An agent is private to its creator until shared with groups or with members on their own (someone in no group included), who may then use it with their own settings. Admins see every agent.
- Settings narrow the agent; removing a tool from the agent removes it for everyone at their next turn. The one exception is a person's own MCP servers, added to their own settings and to the triggers whose every row is their own account.
- **Widening is accepted, narrowing is not.** A change that widens a shared agent (a new system, tool, repository or network host; a repository from `READ` to `WRITE`; a tool from `ASK` to `ALLOW`) reaches a person's conversations only after that person accepts it, and a trigger's runs only after its author does; until then they keep the version they accepted. Narrowing applies to everyone at their next turn. Instructions and descriptions are not widening.
- An agent can be paused: its conversations' turns wait and its triggers do not fire.
- **Skills** ([ADR-0064](adr/0064-skills-library.md)) come from the company's library, where every member creates them and shares them as agents are shared. An agent pins a version of each; a new version reaches no agent until its editors move the pin, so whoever edits a shared skill changes no agent. Skills are not widening: like instructions, they follow the current version for everyone. A run gets them as files, read-only, where its runtime reads a user's skills; the runtime lists them to the model, which reads one when it fits the work.

## 7. Triggers and channels

### 7.1 Triggers

A trigger reads as one sentence: *when any of `events` happens to a subject in `scope`, and `conditions` hold, `agent` does `instruction`*. Its source is a tracker system (`TRACKER`: Jira Data Center or Cloud, GitHub, GitLab), a schedule (`SCHEDULE`, which belongs to no system), a chat system's channels (`CHANNEL`, §7.3) or a webhook system (`WEBHOOK`). It holds:

| Field | Meaning |
|---|---|
| events, scope, subject, conditions, repeat | As [ADR-0043](adr/0043-trigger-rules.md): created, updated, status entered, label added or removed, assigned, mentioned, commented, change request opened or updated, closed, schedule, a webhook's delivery; edges, not levels; every matching trigger fires; condition groups; `ONCE` or `EVERY_TIME` |
| agent, instruction, settings | Who does the job, what the job is, and the narrowing of the agent for it |
| **accounts** | For each system the agent uses: the author's own account, or a service account the author may use, with the credential lent to it. The editor fills every row from one choice ("Use the accounts of: svc-dev") and the author may change any row. An MCP server that needs no account takes no row |
| **model access** | The author's own, or a service account's they may use |
| **team** | The group that sees the trigger's conversations, decides their approvals and runs the trigger now; without one, the author alone |
| results | Where the result goes, and on success or failure a transition, a label, attaching documents or closing |

- **Anyone who may use the agent creates triggers**, with accounts and model access they are entitled to. When the author loses a right the trigger depends on, or leaves, or an account or credential of its table breaks or is withdrawn, the trigger pauses; it never switches payer. It follows its service account: a credential an admin lends that service account in place of the one it borrowed becomes the trigger's (§5.3).
- **On a tracker, the trigger's account or credential there watches and writes back.** Tinfirm polls the tracker with it at the system's poll interval (triggers on the same account or credential share one poll), so the trigger sees only what it sees; "assigned" and "mentioned" mean that account, which gives a member personal automation ("when an issue is assigned to me, prepare it") without an admin. Polling is the source of truth. Tinfirm's own writes raise no events.
- **Schedules** fire on a cron in a time zone, at most every 15 minutes, with [ADR-0032](adr/0032-routines.md)'s overlap and catch-up; each firing is a conversation of its own, and the previous firing's idle one ends.
- **A generic inbound webhook** is a system with a URL and a secret (a Standard Webhooks signature, or a header that carries the secret) that any other software can call, with a mapping of JSON Pointers from its body to the event, the subject, the actor and the text. A subject key has one open conversation per trigger.

### 7.2 Who can drive a shared trigger

Whoever may write where a trigger listens (a Jira comment for a mention trigger, a channel message) drives the agent with the trigger's accounts and model access, prompt injection included. This is accepted: for such events the editor says who can drive the trigger and which writes then run without approval, offers a service account instead or asking the team before every write, and asks the author to acknowledge it while a row holds their own account and a write tool stays `ALLOW`. A `READ` lending bounds what such a trigger can do.

### 7.3 Chats

- **One Tinfirm bot per chat system**: the app installed once per Slack workspace (over the Events API or Socket Mode) or Teams tenant, one chat system per workspace or tenant in a company. The bot is a credential on the chat system lent to a service account ([ADR-0072](adr/0072-system-credentials-lent-to-groups-and-service-accounts.md)), set, replaced and removed only with the bot; it is lent to no group and only carries messages. Without a usable bot (none set, its token refused, its service account suspended) events are dropped unanswered and channel triggers wait, and go on by themselves once it is fixed.
- **A channel trigger**: in its channels, ones the bot is in, when someone mentions the bot, the agent answers in the thread with the trigger's accounts and model access. A new thread goes to the trigger of the first `@handle` mentioned that has one in the channel, else to the channel's default. Every post carries the agent's name, marked "(Tinfirm agent)". It answers members of the workspace or tenant only, linked or not, never guests or users of another organisation.
- **A direct message** to the bot is a personal conversation (§8.1) with the sender's own accounts, budget and settings. A chat user is linked to a member automatically when one identity provider vouches for both (Entra ID for Teams and the Tinfirm login), else once by a sign-in link the bot sends.
- **A chat thread is one conversation.** In a channel every turn is addressed to the bot: a follow-up in the thread mentions it again. The bot receives only messages that mention it and direct messages, never the rest of a channel. A question in a thread is answered by any member's press or mention; an approval only by a linked member who may decide it.

## 8. Conversations and decisions

### 8.1 Conversations

A conversation is a thread between an agent and its audience in one **place**, holding the agent, its settings, its accounts and model access (§5.3), its turns, decisions and outputs.

| Place | Started by | Results go to |
|---|---|---|
| A Tinfirm thread | A person in the app or their AI client ([ADR-0045](adr/0045-mcp-server-for-peoples-clients.md)) | The thread |
| A chat thread | A direct message (personal) or a channel trigger | A reply of the bot in the thread |
| A tracker issue or change request | A tracker trigger | A comment written with the trigger's account or credential there, then the trigger's on-success or on-failure actions (after a review, when the trigger asks for one) |
| A scheduled firing | A schedule trigger | Its outputs in Tinfirm. Nothing is written elsewhere, so a review holds nothing back |
| A webhook delivery | A webhook trigger | Its outputs in Tinfirm. Nothing is written elsewhere, so a review holds nothing back |

- A conversation may be **about a subject** (an issue, a pull request: a reference, not a copy); the app groups conversations by subject. There is no work item and no second backlog.
- **Conversations are independent**: each has its own session, branch and workspace, so several agents work on one issue at the same time. Turns of one conversation run in order.
- **A personal conversation publishes nothing by itself.** About a tracker issue, it writes there only through the agent's write tools, each under its mode (§10).
- **A message written in Tinfirm while a turn runs goes to that turn** ([ADR-0060](adr/0060-steering-a-running-turn.md), V036): the agent takes it after its current step when its runner host and runtime take steers (Claude Code, Codex and pi), else it waits for the next turn, and the thread says which. A message that answers open questions waits for the next turn, as does what is written on an issue or in a chat thread.
- A turn that ends on its own questions posts no summary ([ADR-0036](adr/0036-questions-end-a-run-quietly.md)). **Cancel** stops the active turn, its open decisions and its parked calls, and the conversation stays open; **Retry** runs a failed or cancelled turn's request again, resuming the agent's session; ending a conversation is final.
- **Automatic retries** ([ADR-0073](adr/0073-automatic-retries.md), V051): a turn that failed for a reason that may pass (the model's provider or gateway down or limiting, a runner host that restarted) is retried by itself when the person, or the trigger's author, chose so in their settings: at most three times in a row, 1, 2 and then 4 minutes after each failure, paid as the turn was. The place hears only of the failure that ends the retries.

### 8.2 Decisions

| Kind | Raised when | Decided by |
|---|---|---|
| **Question** | The agent calls `ask` | Anyone who can write in the place: the person, anyone in the channel thread, anyone who replies on the issue. The next turn gets every answer |
| **Approval** | A tool whose mode is `ASK`; `request_access` for a repository or `request_network_access` for a host; a result the trigger wants reviewed | In a personal conversation, the person; in a shared one, the trigger's team, or its author without one |

An approved request covers this conversation or, when the approver may edit the agent and chooses so, the agent for good. Approvals appear in the inbox and, in a chat thread, as buttons for linked approvers in Slack and Teams. A member decides; never an agent.

## 9. Visibility and governance

| Object | Seen by |
|---|---|
| An agent | Its creator, the groups and members it is shared with, admins |
| A trigger and its conversations | Its author, its team, admins |
| A personal conversation (app, AI client, chat direct message) | Its person, and the Owner in full, for support and audit; each read is recorded and the person is shown it |
| Spend | Each member what they paid; a trigger's author and team the trigger's; admins all of it, by person, agent and trigger |

What a caller may not see is `404`, never `403`. **Kill switches**, on one page ([CONVENTIONS §15.13](CONVENTIONS.md#1513-package-6-kill-switches-suspension-break-glasss-view-and-a-member-who-leaves)): pause an agent, pause a trigger, suspend a principal (a member or a service account), stop the company's runs (`RUNS_STOPPED`), pause a system (`SYSTEM_PAUSED`) and turn personal MCP servers off (`PERSONAL_MCP_SERVERS_OFF`). **Budgets** (§11.2): a company default for every key, overridden per member or service account.

**Audit** (the hash-chained log, [ADR-0010](adr/0010-hash-chained-audit-log.md)): every tool call with its conversation, the account or credential used and who acted through it, the tool's effect and its approval; every run with the model access that paid and the host that ran it; every choice of authority: a trigger's accounts and model access, grants and lendings, budgets, who may use a service account.

## 10. Prompt and tools

The server renders every run's prompt: the agent's instructions and the person's added instructions; the conversation, its place, its subject (a reference the agent reads with its own tools) and the person's usual projects; the memory notes; a trigger's instruction and, in the first turn, the subject as its poll read it or a webhook's event; the request; the conversation so far; the answers; the accounts; the repositories; the files; the tools; the outputs so far; how to work. A message that steers a running turn reaches it as a section of its own (§8.1). The content of a subject, of messages and of tool results is other people's words, never instructions.

Tools pass three layers, each narrowing the one before:

1. **Effect.** Every tool of a system is `READ` or `WRITE`. Tinfirm's fixed catalogues, the trackers' and Slack's `history`, are known; an MCP tool's effect is confirmed by an admin (by the owner for a personal server), which the server's `readOnlyHint` and `destructiveHint` only suggest, and **an unconfirmed tool counts as `WRITE`**.
2. **Mode, on the agent.** `ALLOW` or `ASK` (each call an approval). A tool the agent does not list is not available. Defaults: reads `ALLOW`, writes `ASK`; an `ALLOW` chosen for a read is `ASK` once the tool writes. Writing to a tracker is a write tool like any other. A code host's `open_change_request` is not called by the agent: after a turn that pushed, Tinfirm opens the change request at once under `ALLOW`, after an approval under `ASK`, and not at all without the tool.
3. **Settings, account and lending.** A person's or a trigger's settings may switch a tool off or turn `ALLOW` into `ASK`, never the reverse; the account or credential bounds what the system itself permits; a `READ` grant or lending refuses every `WRITE` tool.

A trigger's write-back to its own place and its on-success or on-failure actions are not tools: its author configured them. Tools of systems are executed by the server with the conversation's account or credential for that system, or with none on an MCP server that needs no account ([ADR-0039](adr/0039-runner-protocol-v2-server-executed-tools.md)); no system's credential reaches a runner host or a sandbox, except the git token of a workspace, which stays on the host.

**Platform tools**, which the runner host serves in every run: `ask(question, options?)`, `request_access(repository, reason)`, `request_network_access(host, reason)` (on a host that runs the egress proxy), `save_artifact(path | url, title?, note?)`, `list_repositories()`, `finish(outcome, summary, nextSteps?, openQuestions?)`. `request_access` names a repository: a request for a system's tools is refused, since tools belong to the agent's definition.

**Memory** ([ADR-0063](adr/0063-agent-memory.md)), when the agent allows it and its person, or a trigger's author, turned it on: the agent keeps one-line notes for that person or trigger alone with `remember(note, about?, replaces?)`, `forget(note)` and `recall(query)`, tools the server executes. They reach it progressively: a hint, the general notes and the workspace's repositories' notes in a session's first prompt, a system's notes with the first result of its tools, the rest only through `recall`; never in a resumed turn's prompt. Every note is scanned for credentials; a trigger's are reviewed by its audience before any conversation reads them; the company's own model key ([ADR-0065](adr/0065-company-model-key.md)), with its own budget, compacts each changed memory at most once in 30 days on the model chosen in the company's settings; people's AI clients read the notes and delete their own; notes not used for two years go, by default (the company's retention).

## 11. Execution

### 11.1 The runner

The runner host speaks runner protocol v2 ([runner-v2.schema.json](../protocol/runner-v2.schema.json)) and runs the model proxy (§11.2). A conversation maps onto `run.assign`:

| Runner field | From |
|---|---|
| `workItem.id`, `job.id` | The conversation: its own session home on the host; turns of one conversation run one at a time |
| `workItem.key`, the branch | A key per conversation, such as `shop-123-dev-1` for branch `agent/shop-123-dev-1` |
| `agent` | The agent, with the runtime, model and effort of the settings |
| `prompt` | §10 |
| `modelAccess` / `credentialLabel` | The server's model relay and a one-run token (§11.2), or a subscription's label on its owner's host |
| `workspaces[].git` | A token of the conversation's account or credential on the code host, per repository |
| `tools[]`, `platformTools[]`, `allowedHosts[]` | What §10 leaves, and the agent's and the conversation's network hosts |
| `skills[]` | The agent's skills at their pinned versions, less those the settings switch off; only to a host that lists `run.skills` (§6) |

A message for a running turn goes as `run.steer` to a host that lists it in `hello.features`, which answers each with `run.steer.ack` (§8.1).

**Runner hosts** are **company** hosts, registered by admins, which run any conversation paid by a budget, or **personal** hosts, registered by a member, which run only their owner's work: paid by their owner, with every repository of the workspace through their own accounts. A conversation that can run on no host says so before it starts.

### 11.2 Model access

**LiteLLM is the model gateway Tinfirm manages** ([ADR-0059](adr/0059-litellm-budgets-and-the-model-proxy.md)), bundled with Tinfirm by default or the company's own, managed by Tinfirm through its admin API. A company may instead bring a gateway whose keys its own gateway team provides, which Tinfirm reaches by its model URL alone; each key is entered in Tinfirm, and the gateway still enforces its budget ([ADR-0094](adr/0094-more-than-one-model-gateway.md)). Every principal has **one key at the gateway** with its budget and models, kept in the vault; the company's provider keys stay in the gateway. A spent budget fails the run with `USAGE_LIMIT`, and the principal's conversations wait until it resets or an admin raises it; nobody else pays. A member's **subscription** (Claude, ChatGPT) on their own runner host is the one exception: only their own work, not metered by LiteLLM. The company's own key ([ADR-0065](adr/0065-company-model-key.md)), no principal's, pays for Tinfirm's own requests (a memory's compaction) and never for a run.

**The server relays the model traffic.** LiteLLM stays inside the deployment: a run's `modelAccess` names the server's model relay (`/api/runner/v1/model/…`) and a one-run token, which the server accepts only while the run is active on its host and, from runner host 0.3.0, only with that host's own token. The server removes LiteLLM's own request fields from the body (an upstream URL, credentials, metadata) and forwards the request with the paying principal's key to the bundled LiteLLM's **model port** ([ADR-0069](adr/0069-company-model-gateway.md); `TINFIRM_MODEL_GATEWAY_MODEL_URL`), to the admin URL of a company's own LiteLLM, or to the model URL of a gateway whose keys its team provides, streaming the answer back. The model port (`model-api`) serves LiteLLM's model routes only, through the same filter, and the company's other services reach LiteLLM through it with keys of their own; LiteLLM's own port, with its admin API, is reached by Tinfirm's server alone. No LiteLLM key leaves the server, and runner hosts reach only the server.

**The model proxy**: the runner host starts a listener per run on the sandbox network's gateway, behind a random per-run path, and stops it when the run ends. The runtime gets it as its base URL with a dummy key; the host forwards only the model API's endpoints to the server's relay over https with the run's token, replacing every credential header the sandbox sends, streams the answer back, scans it for the key, and refuses WebSocket upgrades. A run whose proxy cannot start fails. It is always on for a gateway's runs: there is no option to turn it off, so a host never gives a sandbox the key. Verified with the pinned Claude Code, Codex, pi and OpenCode ([the record](reviews/2026-10-12-platform-redesign.md#part-7-execution-on-the-runner--decided)).

### 11.3 Results back to the place

The write-back outbox: a row written in the transaction that commits the change, delivered in order per conversation, retried for what may work later, audited as `source.written`, and recorded so that Tinfirm's writes raise no events. It writes with the trigger's account or credential on a tracker, and with the bot's credential in a chat. Its rows wait while the company's runs are stopped, their system is paused or their trigger is paused, and are delivered after; a failure that Tinfirm retries by itself is not written (§8.1).

## 12. Build order

Built on a fresh schema with no data migration, package by package.

| # | Package | Gives | State |
|---|---|---|---|
| 0 | This design, ADR-0058, ADR-0059, the archive of the previous design | The decision | Done |
| — | The runner's model proxy | No key in the sandbox | Built; always on, part of runner protocol 2 (ADR-0109) |
| 1 | Principals and systems: three roles, service accounts, systems and their Sign-in apps, accounts, grants, personal MCP servers, the binding rule ([contract](reviews/2026-10-12-package-1-contract.md)) | Who acts, and with what | Built, on `main` ([CONVENTIONS §15.1](CONVENTIONS.md#151-principals) to §15.3) |
| 2 | Model access: LiteLLM bundled and managed, a key and budget per principal, subscriptions on their owner's host, company and personal runner hosts, the model relay ([contract](reviews/2026-10-12-package-2-contract.md)) | Who pays | Built, on `main` ([CONVENTIONS §15.4](CONVENTIONS.md#154-model-access-and-runner-hosts)) |
| 3 | Agents, settings and conversations in the app: tools in three layers, questions, approvals, the runner mapping ([contract](reviews/2026-10-12-package-3-contract.md)) | Two people use one agent with their own accounts and budgets | Built, on `main` ([CONVENTIONS §15.5](CONVENTIONS.md#155-agents-and-settings) to §15.7) |
| 4 | Triggers: Jira Data Center and Cloud, GitHub and GitLab polls, schedules, account tables, the outbox and write-back, teams ([contract](reviews/2026-10-12-package-4-contract.md)) | Company automation | Built, on `main` ([CONVENTIONS §15.15](CONVENTIONS.md#1515-package-4-part-a-the-trigger-core)) |
| 5 | Chats (Slack and Teams; Buzz parked, §14): direct messages and linking (5a); channel triggers, the chat outbox and the generic webhook (5b) ([contract](reviews/2026-10-12-package-5-contract.md)) | The shared and personal spaces outside the app | Built, on `main` ([CONVENTIONS §15.16](CONVENTIONS.md#1516-package-5a-chat-systems-and-direct-messages), [§15.17](CONVENTIONS.md#1517-package-5b-channel-triggers-the-chat-outbox-and-the-webhook)) |
| 6 | Governance: the MCP endpoint for people's clients on conversations; the audit, retention, holds and erasure; spend and budget alerts; kill switches, suspension and members who leave ([contract](reviews/2026-10-12-package-6-contract.md)) | Operable by a company | Built, on `main` ([CONVENTIONS §15.10](CONVENTIONS.md#1510-package-6-the-mcp-endpoint-on-conversations) to §15.13) |
| R | Replay safety: tool calls that are never run twice, and idempotent submissions ([contract](reviews/2026-10-12-package-r-contract.md)) | A retried turn or a resent request repeats nothing | Built, on `main` ([CONVENTIONS §15.14](CONVENTIONS.md#1514-package-r-replay-safety-and-idempotent-submissions)) |

Built since, on `main`:

- Agent memory ([ADR-0063](adr/0063-agent-memory.md)) and skills ([ADR-0064](adr/0064-skills-library.md)).
- The company's own model key ([ADR-0065](adr/0065-company-model-key.md)).
- Internal hosts: company systems on hosts the operator names may reach the private network ([ADR-0066](adr/0066-internal-hosts.md)).
- The model port, `model-api/` ([ADR-0069](adr/0069-company-model-gateway.md)).
- Error reports ([ADR-0070](adr/0070-error-reports.md)).
- MCP servers that need no account ([ADR-0071](adr/0071-mcp-servers-without-accounts.md)).
- Credentials on systems, lent to groups and service accounts ([ADR-0072](adr/0072-system-credentials-lent-to-groups-and-service-accounts.md)).
- Automatic retries ([ADR-0073](adr/0073-automatic-retries.md)).
- Steering a running turn ([ADR-0060](adr/0060-steering-a-running-turn.md)).
- The Outlook add-in, a client of the company's MCP endpoint that Tinfirm serves ([ADR-0074](adr/0074-outlook-add-in.md)); its pane shows the whole conversation and finds an email's conversation in every Outlook of the mailbox ([ADR-0076](adr/0076-outlook-pane-thread-and-roaming-links.md)).
- The Gmail add-on, a Google Workspace add-on whose endpoints Tinfirm serves, acting as the person who linked their Google account, as an AI client does ([ADR-0083](adr/0083-gmail-add-on.md)); it saves an answer as a draft reply in the thread.
- Both add-ins send the email's attachments with it, as files the person's client holds for an hour before the start, checked and scanned as uploads ([ADR-0084](adr/0084-email-attachments-and-gmail-drafts.md)).
- The MCP endpoint's reads of triggers, systems, webhooks and spend ([CONVENTIONS §15.20](CONVENTIONS.md#1520-the-mcp-endpoint-on-triggers-systems-webhooks-and-spend)).
- Companies and their sign-in declared by the deployment ([ADR-0075](adr/0075-companies-and-sign-in-from-the-deployment.md)).
- The editions, the licence key and seats by role, with the VIEWER role ([ADR-0077](adr/0077-licence-editions-and-seats.md), [CONVENTIONS §15.24](CONVENTIONS.md#1524-the-licence-editions-keys-and-seats-adr-0077)).
- The Helm chart published to GHCR, and signed release images ([ADR-0080](adr/0080-helm-chart-published-to-ghcr.md), [ADR-0081](adr/0081-signed-release-images.md)).
- A member's Claude subscription signed in on their runner host, which holds no token ([ADR-0085](adr/0085-claude-login-on-the-runner-host.md)).
- SAML sign-in and SCIM provisioning for Enterprise ([ADR-0086](adr/0086-saml-sign-in.md), [ADR-0087](adr/0087-scim-provisioning.md)); roles and companies from the identity provider at every sign-in ([ADR-0088](adr/0088-roles-from-the-identity-provider.md), [ADR-0089](adr/0089-companies-from-the-role-claim.md)).
- Personal runner hosts that serve their owner in every company ([ADR-0092](adr/0092-personal-runner-hosts-in-every-company.md)).
- Jira Cloud credentials from an Atlassian service account's client credentials ([ADR-0093](adr/0093-jira-cloud-service-accounts-by-client-credentials.md)).
- More than one model gateway: an adapter per vendor, and a company's gateway whose keys its gateway team provides ([ADR-0094](adr/0094-more-than-one-model-gateway.md)).
- Elastic runners for Pro and Enterprise: the sandbox bridge, a VM per run, session bundles, ready VMs and their schedule ([ADR-0095](adr/0095-sandbox-bridge.md) to [ADR-0100](adr/0100-elastic-runners-in-pro.md)).

The scenarios that validate the design (V1 to V11) are in [the record](reviews/2026-10-12-platform-redesign.md#validation-scenarios).

## 13. Kept from the previous design

The runner host, sandbox tiers, git proxy, push lease, redaction, images and ACP adapters; identity, sessions, OIDC, the MCP endpoint and Tinfirm's OAuth server for people's clients (companies' own identity providers were dropped since: companies and their sign-in come from the deployment, [ADR-0075](adr/0075-companies-and-sign-in-from-the-deployment.md)); the vault, the audit chain and tenancy; runs, dispatch, leases, queue reasons, capacity settings, transcripts and their stream; the Jira, GitHub and GitLab clients, markup conversion, poll windows, the outbox and loop prevention; the event vocabulary and conditions of triggers; attachments with the upload scan; change requests and their state; the mention grammar; the design system.

## 14. Later work and open items

- **Buzz: parked** (the owner, 2026-10-12). `BUZZ` is a system kind that cannot be registered (`400 SYSTEM_KIND_INVALID`), and nothing of its chat is built.
- The model proxy: reusing connections to the gateway (each request opens its own).
- A GitHub App for installation and user tokens.
- Webhooks from trackers as hints for the next poll; polling stays the source of truth.
- Jira's changelog for issues that are not yet subjects.
- Reusing agents: duplicate, templates, export and import as code, without credentials.
- State shared across server replicas (runner connections, event streams, tool calls in flight), which Enterprise's high availability needs.
- Enterprise's audit streamed to a SIEM, and its air-gapped installation bundle ([ADR-0077](adr/0077-licence-editions-and-seats.md), decision 4).
- The hardening the [threat model](threat-model.md) lists.
