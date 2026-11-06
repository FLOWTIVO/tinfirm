# Platform redesign: agents, authority, accounts and channels

Status: decided on 2026-10-12; the design of record is [PLATFORM.md](../PLATFORM.md) ([ADR-0058](../adr/0058-authority-from-the-audience.md), [ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md)), and this record keeps the reasoning · started 2026-10-12 · Owner: J. Balcar

This is the working record of a clean-sheet redesign of Tinfirm's business logic. Each part is discussed, then recorded here as **Decided**, **Proposed** or **Open**. When it was written nothing here was built, and the PLATFORM.md of 2026-10-05 stayed the design of record until this one was accepted. It was accepted on 2026-10-12 and built: [PLATFORM.md](../PLATFORM.md) now describes it, and the design it replaced is archived at [archive/v2/PLATFORM.md](../archive/v2/PLATFORM.md).

## Goals

1. A company platform where **AI agents are defined once** and **triggered from many sources**: trackers (Jira and others), chats (Teams, Slack, Buzz), schedules, and the Tinfirm app itself.
2. An agent's access to external systems **follows the permissions of the person it acts for**.
3. Each agent is **configurable per person**.
4. **LLM keys are per person**: the same agent used by several people uses each person's own key.

Constraint: **the runner protocol stays as it is** ([runner-v2.schema.json](../../protocol/runner-v2.schema.json)), and the runner host changes by one addition only: the model proxy (Part 7). Everything else may be rewritten.

## Why a redesign

| Root cause today | Effect |
|---|---|
| **Who acts is a property of the agent** (`agent.kind`, fixed for life), not of the work | One switch decides how work starts, whose credentials act, who pays, who sees and whether the agent may ask. One specialist is hired twice, and each new need added a mechanism beside the switch: 5 account mechanisms per connection, 10 kinds of credential |
| **The agent row mixes definition, configuration, authority and funding** | The model key belongs to the agent or to a runner host's owner; the person using the agent never counts. Per-person settings and per-person keys are impossible |
| **Credentials resolved at every call through fallback chains** | A thread's identity can change while it runs; repairs reach people who cannot act on them |
| **"Connection" does five jobs**: event source, tool provider, repository host, OAuth client, credential holder | 19.5k of the server's 67k lines; three types, six kinds, five account tables |
| **Work is shaped like a tracker**: a work item is an issue snapshot, triggers are edges in it | Chats have no natural place; "Slack later" because it does not fit |

**Lesson of the earlier redesign attempt** (dropped on 2026-10-12): it answered the [2026-10-10 review](2026-10-10-credentials-and-agents-review.md) by adding concepts (profiles, compute pools, saved bindings, grants, consent records, a git gateway) on the old foundation, which kept company and personal work as two worlds. It had too many concepts, the wrong foundation and a confusing UX. **The test for this design is fewer nouns.**

## What the runner gives us

The runner host is already a per-run executor; the server decides everything for each run.

| Need | Supported today |
|---|---|
| A person's LLM key | `run.assign.modelAccess` carries an API key (and an optional `baseUrl`) per run |
| A person's git access | `workspaces[].git` carries a token per repository per run, kept on the host |
| A person's tool permissions | Tools run on the server (`run.request` kind `TOOL`); the server picks the credential per call |
| Who acts | Not on the wire; the runner is identity-agnostic |

Inherited constraints:

- **Subscriptions are host-bound.** A Claude Max or ChatGPT Pro subscription serves only runs on its owner's own runner host (`credentialLabel`). A key sent with a run (`modelAccess`) is an API key.
- **The API key is in the sandbox's environment** today (threat model R23, 7.13). Part 7 adds a model proxy to the runner host, so the sandbox holds only a dummy key.
- **The protocol requires `workItem`, `job` and `agent` ids.** A session resumes per agent and work item on the same host; one active run per work item per host (`TASK_RUN_ACTIVE`); branches are `agent/<KEY>`. The new objects must map onto these.

## Vocabulary

| Term | Meaning |
|---|---|
| **Principal** | Whom a run acts for: a **member** (a person) or a **service identity** |
| **Service identity** | A non-human member that admins manage. It holds accounts and model access exactly as a person does |
| **Account** | A principal's credential on one external system: a token or an OAuth grant |
| **Model access** | Who pays for the model: a principal's **budget** on the company's LiteLLM gateway, or a subscription on the principal's own runner host |
| **Grant** | An admin lets a group use a service identity's account on one system, `READ` or `WRITE` |
| **Agent** | A definition: what a specialist is and what it may use. Holds no credentials |
| **Settings** | One person's settings for one agent; a trigger holds its own |
| **Trigger** | A standing rule on a system: when an event happens (or a message arrives in a channel), an agent does an instruction, with the accounts and model access its author chose |
| **Bot** | A service identity's account on a chat system that only carries messages: it receives them and posts the replies |

## Part 1. Authority — Decided

**The audience of the work decides whose authority it carries.** Every conversation uses **exactly one account per system**, fixed for its whole life; what differs is who picks it.

| Where the work happens | Accounts, one per system | Model access (who pays) |
|---|---|---|
| **A personal space**: the Tinfirm app, the person's AI client, a direct message to the bot in Slack, Teams or Buzz | **Tinfirm picks**, at the first use of each system: the person's own account, else a grant to one of their groups, else a Connect card (Part 3) | The person's own budget, or their subscription |
| **A shared space or a standing rule**: a chat channel, a tracker event, a schedule, a webhook | **The trigger's author picked**, in the trigger's table: for each system, their own account or a service identity's they may use | The budget on the trigger: the author's own, or a service identity's they may use |

A channel has many readers, so it never answers with one reader's permissions.

Rules:

- A trigger's author may pick only what they are entitled to: their own accounts and budget, or those of a service identity on which they hold the use right. Never another person's.
- When the author loses a right the trigger depends on, or leaves the company, the trigger **pauses**. It never switches to another account or payer.
- A conversation's accounts are **fixed** and checked at every call. They never change inside the conversation, and nothing falls back silently. A missing account is a request to connect it, addressed to the person who can.
- A system without an account in the conversation is not offered to its runs: its tools are not listed, its repositories not cloned.
- Service identities replace the connection's own token, the company OAuth account and agents' accounts on trackers: each is an account of a service identity.

Accepted risk: a trigger that anyone on the source can fire (a Jira mention or comment) lets any user of that system drive the agent with the authority the trigger's author chose, prompt injection included. The trigger editor says so for such events and suggests a narrowly scoped service identity.

## Part 2. Agents and settings — Decided

**An agent is a definition, shared like a document.**

| Part | Holds |
|---|---|
| **Agent** | Name, `@handle`, description, instructions; the **runtimes** its instructions are tested on (one or more); the systems, tools and repositories it **may** use (a ceiling); which actions need approval |
| **Settings** (one per person per agent, created on first use) | Runtime, model and effort, within the agent's runtimes and the models the person's model access allows; budget or subscription; instructions **added** to the agent's; systems and tools turned off; default repositories and projects |
| **Trigger** | The same settings for its runs, plus its event, conditions, instruction, an account per system and the model access |

Rules:

- Members with the role (OPERATOR and above) define agents. An agent is **private to its creator until shared** with groups, who get the use right on it.
- An agent holds **no credentials** and decides nothing about who acts.
- Settings **narrow, never widen**: definition → settings (a person's or a trigger's) → run. Removing a tool from the agent removes it for everyone at their next turn. One exception: a person may add their own **personal MCP servers** (Part 3) to their settings, and to the triggers that run as them.
- **Widening is accepted** (security review, row 11): a change that widens a shared agent reaches a person after they accept it and a trigger after its author does; narrowing applies at once.
- A person picks a runtime the agent lists and a model their model access allows. Through LiteLLM a runtime reaches the gateway's models in the Anthropic or OpenAI format (Part 7). An agent that lists one runtime behaves the same for everyone.

## Part 3. Accounts and model access — Decided

**A system replaces the connection.** It is an external service registered once: its kind (Jira Data Center, Jira Cloud, GitHub, GitLab, a plain git host, Slack, Teams, Buzz, an MCP server), its URL and its **app**: the OAuth client, one per system, and for a chat its bot registration. A system holds **no account of its own**: nothing acts as "the system". What it offers follows from its kind: events, tools, repositories, a chat channel. GitHub and GitLab are one system for issues and code again (the split of ADR-0050 goes), because one person's sign-in covers both.

| Who registers | What |
|---|---|
| Admins | Company systems, which agents and triggers may use |
| Any member | **Personal MCP servers**, reachable only by runs that act as that member: their conversations and the triggers that run as them |

**An account is one per principal per system**, connected by OAuth (one click, returning where the person started) or by a token. A principal that needs a second identity on one system is a second service identity. The account also carries the principal's **identity on the system** (from its "who am I": the Jira user, the GitHub login, the Slack user id), which:

- links a chat user to a member: a message from Slack user `U123` acts as the member whose Slack account is `U123`;
- recognises an assignment or a mention: to the account the trigger watches the tracker with;
- prevents loops: what Tinfirm's accounts wrote raises no events.

**Linking chat users.** Automatic when one identity provider vouches for both sides (Entra ID for Teams and for the Tinfirm login). Otherwise the bot answers once with a sign-in link, and the link becomes the person's account on that chat system.

**Grants.** An admin lets a group use a service identity's account on one system, `READ` or `WRITE`. A person's conversation binds an account to a system the first time it needs the system:

1. the person's own account, when it is usable;
2. else a grant to one of their groups, named on screen ("GitHub: via Analysts, read-only"); with several, the person picks;
3. else a **Connect** card in the thread; the call continues once they connect.

The binding is kept for the conversation. When the person's own account is broken and a grant exists, the person chooses between reconnecting and the grant; Tinfirm never picks. `READ` is enforced by Tinfirm (write tools refused, workspace read-only), not only by the token's scopes.

**Model access** is who pays for the model, never a key a person holds:

- **A budget on the company's LiteLLM gateway** (Part 7). Every principal, member or service identity, has **one LiteLLM key** with its budget and the models it may use, set by admins (Part 8). Tinfirm creates and stores it; the principal never sees it. The provider keys are the company's and stay in LiteLLM. Spend is attributed and capped per principal.
- **A subscription on the principal's own runner host** (Claude Max, ChatGPT Pro): the one exception to the gateway. It serves only that person's own conversations and the triggers whose model access is theirs, routed to their personal host, and LiteLLM does not meter it.

| Run | Model access |
|---|---|
| A person's conversation | The person's budget, or their subscription when their settings say so |
| A trigger's run | The one named on the trigger: the author's own, or a service identity's they may use |

Model access is never granted to groups.

## Part 4. Channels and triggers — Decided

Two ways into an agent, by Part 1:

| Entry | Sources | Accounts |
|---|---|---|
| **A personal conversation** | The Tinfirm app, the person's AI client (MCP), a direct message to the bot | The person's (Part 3). A chat user must be linked to a member; the agent is the person's default or named in the message |
| **A trigger** | Trackers (Jira Data Center and Cloud, GitHub, GitLab), chat channels, schedules, a generic inbound webhook | The trigger's table |

**A trigger** belongs to a system and holds: the events and their conditions (or, on a chat, the channels and when it answers), the agent, the instruction, the settings (Part 2), **an account per system** the agent uses, the model access, and where the results go (Part 5). Anyone who may use the agent creates triggers, with accounts and model access they are entitled to.

- **The table.** The editor fills every row from one choice ("Use the accounts of: svc-dev"), and the author may change any row. A system left without an account is not available to the trigger's runs.
- **The source row watches and writes back.** On a tracker, the trigger's account on that tracker polls it, so the trigger sees only what that account sees, and the write-back (comment, transition, label) is written with it. Triggers on the same account share one poll. Assigned and mentioned mean "to this account": a trigger on Alice's own Jira account fires when an issue is assigned to Alice, which gives personal automation without an admin.
- **Kept from today:** the event vocabulary (created, updated, status entered, label added or removed, assigned, mentioned, commented, change request opened or updated, closed, schedule), edges and not levels, conditions, no events from Tinfirm's own writes, polling as the source of truth and webhooks as a hint for the next poll.
- **Generic inbound webhook:** a system with a URL and a signing secret that any other management software can call (Azure DevOps, ServiceNow, Linear…), with a mapping from its JSON to the event's subject, actor and text. It covers "other management software" without an adapter for each.

**Chats.**

- **One Tinfirm bot per chat system**: the app is installed once per Slack workspace or Teams tenant, and its token is the account of one service identity. The bot only carries messages: it receives those addressed to it and posts the replies. Reading the chat's history as a tool is done with an account from the conversation's table, like any other system.
- **A channel trigger**: on #support, when someone mentions the bot or `@handle`, the support agent answers, with the trigger's accounts and key. On Slack the reply shows the agent's name and avatar (`chat:write.customize`); on Teams it shows as the bot.
- **A chat thread is one conversation**, and every turn mentions the bot: it receives only mentions and direct messages, never the rest of a channel (security review, row 8).
- **Who may talk to a channel agent**: the members of the chat workspace or tenant in the channel, never guests or users of another organisation (Slack Connect, Teams external access). The asker is recorded in the audit, as a linked member when known.
- **A direct message** to the bot is a personal conversation (above).
- **Buzz** sits behind the same chat adapter interface (receive a message, reply in a thread, identify the user, list channels); its mechanism is decided when its API is known. **Parked** by the owner on 2026-10-12: package 5 builds Slack and Teams.

Accepted risk, as for a tracker's mention or comment trigger: whoever may write in the channel drives the agent with the trigger's accounts, prompt injection included. The trigger editor says so and suggests narrowly scoped service identities.

## Part 5. Conversations and results — Decided

**The conversation is the one object of work.** It is a thread between an agent and its audience in one **place**, and it holds the agent, the settings, the accounts (one per system, Part 1), the model access, the turns (each a run), the decisions and the outputs. A conversation may be **about a subject** (an issue, a pull request), and the app groups conversations by subject.

| Place | Started by | Results go to |
|---|---|---|
| A Tinfirm thread | A person in the app or their AI client | The thread |
| A chat thread | A direct message (personal) or a channel trigger | A reply of the bot in the thread |
| A tracker issue | A tracker trigger | A comment written with the trigger's account on the tracker, then the trigger's on-success or on-failure actions (transition, label); with a review, after the review |
| A scheduled firing | A schedule trigger | The outputs, in Tinfirm; a review when the trigger asks for one |

- **No work item.** The subject is a reference (system, key, link), not a second copy of the issue. The snapshots the event detection compares against are the poller's own bookkeeping.
- **Conversations are independent**: each has its own session, branch and workspace, so several agents work on one issue **at the same time**. On the runner, a conversation is the `workItem` and the `job` of its runs (Part 7).
- **A personal conversation publishes nothing by itself.** About a tracker issue, it writes there only through the agent's write tools (a comment, a transition), each under its mode (Part 6), approved by the person when the mode asks. There is no separate publication setting.

**Two kinds of decision** instead of four:

| Kind | Raised when | Decided by |
|---|---|---|
| **Question** | The agent asks (`ask`) | Anyone who can write in the place: the person in a personal conversation, anyone in the channel thread, anyone who replies on the issue. The next turn gets the answer |
| **Approval** | A gated action (a tool whose mode asks), a request for more (a repository, a network host), or the review of a result | In a personal conversation, the person. In a shared one, **the group named on the trigger**: its team |

**The trigger's team** sees the trigger's conversations in Tinfirm, decides their approvals and runs the trigger now. It replaces the `VIEW`, `RUN` and `DECIDE` rights on agents. A personal conversation is seen by its person (and OWNER, for support and audit: Part 8). Approvals appear in the Tinfirm inbox and, for linked approvers, as buttons in Slack and Teams.

## Part 6. Tools and permissions — Decided

Three layers, each only narrowing the one before.

**1. A system's tools, each with an effect, `READ` or `WRITE`.**

| System | Tools (examples) |
|---|---|
| Jira, GitHub, GitLab | Get and search issues (`READ`); comment, transition, label, create an issue (`WRITE`) |
| Git host | Clone the workspace (`READ`); push to `agent/*`, open a change request (`WRITE`) |
| MCP server | The server's own tools. The effect is prefilled from its `readOnlyHint` and `destructiveHint` and confirmed by an admin on the system's page (by the owner, for a personal MCP server); an **unconfirmed tool counts as `WRITE`** |
| Chat | Read a channel's history (`READ`); post (`WRITE`) |

**2. The agent's mode per tool: `ALLOW` or `ASK`.** A tool the agent does not list is not available; there is no separate `DENY`. Defaults: reads `ALLOW`, writes `ASK`. The policies `changeRequest` and `sourceWrite` become the modes of the tools that open a change request and write to a tracker.

**3. Settings and the account.** A person's settings or a trigger may switch a tool off or turn `ALLOW` into `ASK`, never the reverse: for more autonomy, the agent's creator changes the agent, or duplicates it. The account bounds what the system itself permits, and a `READ` grant refuses every `WRITE` tool.

Not tools, and always run: the write-back of a trigger to its own place (its comment on the issue, its reply in the thread) and the trigger's on-success and on-failure actions, which its author configured.

**Platform tools:** `ask` (a question), `request` (an approval for a repository not in the workspace, or a network host), `save_output`, `list_repositories`, `finish`. Always available: the four switches of ADR-0046 go.

**Requests.** An approved `request` covers this conversation, or, when the approver may edit the agent and picks it, the agent for good. **Network:** the agent lists the hosts it may reach; a conversation gets more through a `request`.

## Part 7. Execution on the runner — Decided

The runner and its protocol do not change. A conversation maps onto `run.assign`:

| Runner field | From |
|---|---|
| `workItem.id`, `job.id` | The conversation: each gets its own session home on the host, and "one active run per work item" serialises the turns of one conversation only |
| `workItem.key`, the branch | A key per conversation, such as `shop-123-dev-1` for branch `agent/shop-123-dev-1`: two conversations on one issue never share a branch |
| `agent` | The agent, with the runtime, model and effort of the settings |
| `prompt` | Rendered by the server |
| `modelAccess` / `credentialLabel` | The paying principal's LiteLLM key and the gateway's URL, which the host keeps behind its model proxy (below), or a subscription's label on the owner's own host |
| `workspaces[].git` | A token of the conversation's account on the git host, per repository, kept on the host and never in the sandbox |
| `tools[]` | The tools left after the three layers of Part 6; calls run on the server with the conversation's accounts |

**LiteLLM is the mandatory model gateway.** Bundled with Tinfirm by default (Helm chart, Compose), or a company's existing proxy with an admin key. Tinfirm manages it through its admin API: **one virtual key per principal**, with the principal's budget, its models and the principal as metadata, created when the principal is and kept in Tinfirm's vault; the company's provider keys and model list live in LiteLLM (configured through Tinfirm when bundled). A run's `modelAccess` carries the paying principal's key (format `ANTHROPIC` or `OPENAI`, `baseUrl` the gateway). Usage per principal comes from LiteLLM's spend logs, per agent, trigger and conversation from the runs' usage events. A spent budget fails the run with `USAGE_LIMIT`, and the principal's conversations wait until it resets or an admin raises it: Tinfirm never switches to another payer. *Amended after the security review (row 21): runner hosts no longer reach LiteLLM; the server relays model traffic with a one-run token, and no LiteLLM key leaves the server.*

**The model proxy: the runner host's one addition.** The key never enters the sandbox. Like the git proxy and the platform MCP listener, the host starts a listener per run on the sandbox network's gateway, plain HTTP behind a per-run secret in the path, and stops it when the run ends. The sandbox's runtime gets that listener as its base URL (`ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`, pi's and OpenCode's configuration) and a **dummy key**; the host forwards each request to LiteLLM over https with the real key in `Authorization`, and streams the answer back. It is always on for a gateway's runs, with no option to turn it off (security review, row 1).

- A prompt-injected agent finds only the dummy key. It can use the model only through its own run's listener, only while the run lasts.
- The sandbox needs no route to LiteLLM: with the egress proxy, the model host leaves the run's allowlist.
- The protocol and the server do not change for it: `run.assign` already carries the key and the base URL. The host's runtime configuration does (`runtime_env.rs`).
- **Verified on 2026-10-12** with the images' pinned versions (Claude Code 2.1.287 through `claude-agent-acp` 0.87.0, Codex 0.156.1 through `codex-acp` 1.13.1, pi 1.0.4 through `tinfirm-pi-acp`, OpenCode 1.18.34), each driven over ACP as `acp.rs` does, with the environment `runtime_env.rs` builds, the base URL a plain-http listener behind a run secret, a dummy key, `HTTP(S)_PROXY` at a refusing egress stub and `NO_PROXY` naming the listener, in front of a mock gateway that accepts only the real key:

  | Runtime and format | Result | Header the dummy came in |
  |---|---|---|
  | Claude Code, `ANTHROPIC` | Answered | `Authorization: Bearer` |
  | Codex, `OPENAI` | Answered, after about 6 s of WebSocket retries and a fallback warning written into the agent's text; answered at once with the gateway as its own provider and `supports_websockets: false` (below) | `Authorization: Bearer` |
  | pi, `ANTHROPIC` and `OPENAI` | Answered | `Authorization: Bearer` |
  | OpenCode, `ANTHROPIC` and `OPENAI` | Answered | `Authorization` and, for `ANTHROPIC`, `x-api-key` too |

  What the proxy and the host must do, from it: strip **every** credential header (`Authorization`, `x-api-key`, `api-key`), not only `Authorization`; give Codex the gateway as its own provider in `CODEX_CONFIG` (`model_provider` with `base_url`, `env_key: OPENAI_API_KEY`, `wire_api: responses`, `supports_websockets: false`) instead of `openai_base_url`, since neither the proxy nor LiteLLM serves the Responses API over WebSocket; allow the small models the runtimes call on their own (OpenCode titles a session with `claude-haiku-4-5` or `gpt-5.4-nano`) on each principal's LiteLLM key. Codex (github.com, chatgpt.com) and OpenCode (models.opencode.ai, registry.npmjs.org) also try to reach hosts of their own; the egress proxy refuses them and both work on. Not covered by the test: the proxy's lifecycle with a run, which is the host's code to write.
- Subscriptions keep the host's own credential handling for now; a Claude subscription's token through the same proxy is a later step, the Codex login (a mounted directory) cannot be.
- The host holds the principal's real key in memory, which is why personal hosts get only their owner's (below).

**Runner hosts are company or personal.**

| Host | Registered by | Runs |
|---|---|---|
| **Company** | An admin | Any conversation |
| **Personal** | A member | Only conversations whose credentials are all its owner's: their subscription or budget and their own accounts. A trigger that combines a subscription with a service identity's git account can run nowhere, and its editor says so |

Today any connected host of the company gets any run of a model credential, whoever owns it (`RunDispatcher.placeOnAnyHost`); personal hosts close that.

**Revocation during a run** is accepted: a run keeps its git token until it ends (bounded by its maximum wall-clock time), and the next turn checks everything again. No git gateway is built.

## Part 8. Governance: roles, visibility, audit, cost — Decided

**Three roles.** `OPERATOR` and `VIEWER` go: conversations and triggers carry their own audience, so a read-only member means nothing.

| Role | May |
|---|---|
| **Owner** | Everything an admin may, and the company's settings |
| **Admin** | Systems and their apps, service identities and who may use them, grants, groups, budgets, company runner hosts, LiteLLM |
| **Member** | Define agents, create triggers, talk to agents, register personal MCP servers and a personal runner host |

**Who sees what.**

| Object | Seen by |
|---|---|
| An agent | Its creator, the groups it is shared with, admins |
| A trigger and its conversations | Its author, its team, admins |
| A personal conversation (in the app, through an AI client, a chat direct message) | Its person, and the Owner, in full, for support and audit (as ADR-0049 decided); the app says so |
| Spend | Each principal their own; a trigger's author and team the trigger's; admins all of it, by person, agent and trigger |

**Budgets.** One company default monthly budget for every member; admins override it per person and set each service identity's. Each is the budget on the principal's LiteLLM key (Part 7). Members see their own spend and the reset date.

**Audit** (the hash-chained log, kept): every tool call with its conversation, the account used and its holder, the tool's effect and its approval; every run with the model access that paid and the host that ran it; every choice of authority: a trigger's account table and model access, grants, budgets, who may use a service identity.

**Kill switches.** Pause an agent (its conversations and triggers stop), pause a trigger, suspend a principal (everything that uses its accounts or budget stops: a compromised service identity).

**A member who leaves.** Their triggers pause; an admin may hand each to a new author who is entitled to its accounts and model access. The agents they created pass to the admins. Their personal conversations close and stay readable to the Owner; their accounts, personal MCP servers and personal runner host are removed.

## Part 9. How it is built — Decided

- **A fresh schema**, no data migration: an existing deployment's database is replaced.
- **A restart, package by package**, as [ADR-0037](../adr/0037-restart-on-the-platform-design.md) did: today's application keeps running until the new one replaces it. Each package lands with its tests.
- **Kept** (about 18k of the server's 67k lines): login and identity providers, Tinfirm's OAuth authorization server, the vault, the audit log, members and groups, runner connections and the protocol. **Reworked**: `connection` into systems, accounts, grants and the tool catalogue (the Jira, GitHub and GitLab HTTP clients and the outbound guard reused); `work` into conversations and decisions; `sync` into triggers polling per account (the event detection reused); `run` (dispatch and transcripts kept; assignment and host scope new); `agent` into agents and settings; the MCP endpoint for people's clients onto conversations. **New**: LiteLLM management, the chat adapters, the generic webhook.

| # | Package | Gives |
|---|---|---|
| 1 | Principals and systems: three roles, service identities, systems and their apps, accounts, grants, personal MCP servers. Contract: [package 1](2026-10-12-package-1-contract.md) | Who acts, and with what |
| 2 | Model access: LiteLLM bundled and managed, a key and budget per principal, the runner's model proxy, subscriptions on their owner's host, company and personal runner hosts. Contract: [package 2](2026-10-12-package-2-contract.md) | Who pays |
| 3 | Agents, settings and conversations in the app: tools in three layers, questions, approvals | **The first end-to-end slice**: two people use one agent with their own accounts and budgets |
| 4 | **Jira triggers first** (Data Center and Cloud), then GitHub and GitLab: account tables, polling per account, write-back, the trigger's team. Contract: [package 4](2026-10-12-package-4-contract.md) | Company automation |
| 5 | Chats: direct messages and channel triggers (Slack and Teams; Buzz parked), the generic webhook. Contract: [package 5](2026-10-12-package-5-contract.md) | The shared and personal spaces outside the app |
| 6 | Governance: spend views, kill switches, a member who leaves; the MCP endpoint on conversations. Contract: [package 6](2026-10-12-package-6-contract.md) | Operable by a company |

## Security review — 2026-10-12

The threat model's pass over the redesign ([threat-model.md](../threat-model.md), Q30 to Q40, R41 to R45) found twenty-one gaps. Their resolution:

| # | Finding | Resolution |
|---|---|---|
| 1 | The server cannot tell whether a host runs the model proxy; a company host started without it puts every principal's key in the sandbox | **Decided:** the proxy is always on for a gateway's runs, with no option to turn it off; no protocol change, so hosts are upgraded before gateway keys are sent |
| 2 | One long-lived key per principal, sent to every host that runs their work, with no rotation | **Adopted:** keys rotate on a schedule (monthly) and at once on suspicion; with the proxy the key stays in the host's memory (package 2) |
| 3 | Spend per conversation comes from what the sandbox reports | Already open point 1 of [package 2](2026-10-12-package-2-contract.md): the proxy tags each request with the run |
| 4 | Suspending or leaving does not reach LiteLLM | Already M6 of package 2: the key is blocked on suspend and deleted on leaving |
| 5 | The chat sign-in link is not bound to one chat user | **Adopted:** the link names the chat user it was sent to, is single-use, expires in 10 minutes, and the person confirms "Link Slack user *X* to your account?" before it is stored (package 5) |
| 6 | The automatic Entra link must match the object id and tenant, not the login's subject | **Adopted:** match on `oid` and `tid` (package 5) |
| 7 | Approval buttons in a chat are authenticated by the chat platform only | **Accepted** (R45): the linked chat identity is the member's; an approval is audited with the chat user and the member |
| 8 | Continuing a thread without a mention makes the bot receive every message of its channels | **Decided:** every turn mentions the bot; it receives only mentions and direct messages |
| 9 | Guests may answer the agent's questions in a channel thread | **Adopted:** the channel's audience rule applies to answers too: answers from guests and other organisations are ignored |
| 10 | Loop prevention by the writing account would silence a member's own comments; agent-created issues can fire `CREATED` triggers | **Adopted:** loops are prevented by Tinfirm's record of what it wrote, never by identity; an issue an agent created may fire triggers, up to a chain of 3 triggered conversations, after which it fires none and says so |
| 11 | A shared agent's creator can widen it (a new system, an `ALLOW` write) for every user at their next turn | **Decided:** narrowing applies at once; widening reaches a person's conversations after they accept it, and a trigger's runs after its author does; until then they keep the accepted version |
| 12 | Personal MCP servers: any member chooses the URL; a trigger mixing a service identity's accounts with its author's personal server opens a way out | **Adopted:** a personal server never reaches private addresses, whatever the instance allows for company systems; a trigger may use its author's personal servers only when all its accounts are the author's own |
| 13 | A confirmed MCP effect survives a change of the tool's description | **Adopted:** a change of description or schema drops the confirmation |
| 14 | The webhook's signature scheme is unspecified; a replayed delivery is a real event | **Adopted:** HMAC over a timestamp, a delivery id and the body, with a 5-minute window and the delivery id remembered; a sender that cannot sign uses a secret header, marked weaker on the system's page (package 5) |
| 15 | No inbound size and rate limits | **Adopted:** limits per system and per chat user, with the excess refused and logged (package 5) |
| 16 | `chat:write.customize` lets a creator name an agent like a colleague | **Adopted:** posts show the agent's name with "(Tinfirm agent)", and its avatar is the agent's, never a person's |
| 17 | LiteLLM: prompts in spend logs, its admin routes, tenancy of a bundled gateway | **Adopted:** prompts are not stored in spend logs; only the model API routes are exposed to runner hosts; each company is a LiteLLM team and its keys carry the company (package 2) |
| 18 | The Owner's reads of personal conversations are not audited, and the notice does not reach chats and AI clients | **Adopted:** every read is audited; the notice is shown in the app, in the bot's first direct message and on the AI client's consent page |
| 19 | Chat and webhook signature checks are not stated | **Adopted:** platform requirements of package 5: Slack's signing secret, Teams' bot token validation, the webhook's HMAC |
| 20 | A leaked Slack app-level token lets someone receive Tinfirm's events | **Accepted**, with rotation documented (threat model 11.8) |
| 21 | Runner hosts reaching LiteLLM directly expose it beyond the deployment, put principals' keys on every host, and pass LiteLLM's own request fields from the sandbox unfiltered (an upstream URL could send the company's provider key elsewhere) | **Decided:** the Tinfirm server relays model traffic: runner hosts get a one-run token for the server's relay; the server strips LiteLLM's fields and forwards inside the deployment with the principal's key; no LiteLLM key leaves the server ([ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md) decision 2) |

## The model on one page

| Noun | One line |
|---|---|
| Member, service identity | The principals: whom work acts for |
| Group | A set of members: shares agents, receives grants, forms a trigger's team, may use a service identity |
| System | An external service registered once, with its app; no account of its own |
| Account | A principal's credential on a system, with its identity there; one per principal per system |
| Grant | A service identity's account lent to a group, `READ` or `WRITE`, for personal conversations |
| Model access | A LiteLLM budget, or a subscription on the owner's runner host |
| Agent | A definition with a ceiling; no credentials |
| Settings | A person's or a trigger's narrowing of an agent |
| Trigger | A standing rule on a system with an account per system, model access and a team |
| Conversation | The work: one agent, one place, one account per system for life |
| Question, approval | The two decisions |
| Runner host | Company or personal |

## Validation scenarios

The design holds when each of these does, as an integration test where the server decides and an end-to-end test where a person sees it.

| # | Scenario | Holds when |
|---|---|---|
| V1 | Alice and Bob talk to one shared agent | Each conversation uses its person's accounts and budget; neither sees the other's |
| V2 | The BA agent runs from a Jira trigger as `svc-ba` and in Alice's conversation | Same instructions; the trigger's conversation acts with its table and the team sees it; Alice's acts as Alice |
| V3 | #support has a channel trigger; Bob sends the bot a direct message | The channel's answers use the trigger's accounts, guests are refused; Bob's direct message acts as Bob |
| V4 | Alice's own trigger: "when an issue is assigned to me, prepare it" | It polls with Alice's Jira account, fires on assignments to Alice, runs with her accounts and budget |
| V5 | An analyst without GitHub access, in a group with a `READ` grant | The workspace is read-only, no write tool runs, the conversation names the grant |
| V6 | The person connects their own account during a conversation | The conversation keeps the account it bound |
| V7 | A budget is spent | Runs fail with `USAGE_LIMIT`, the principal's conversations wait; nobody else pays |
| V8 | Bob's conversation and Alice's personal runner host | Bob's run, LiteLLM key and tokens never reach Alice's host |
| V9 | A trigger's author leaves | The trigger pauses until an admin reassigns it to an entitled author |
| V10 | An MCP tool with no confirmed effect, under a `READ` grant | Refused as a write |
| V11 | A prompt-injected agent dumps its environment and probes the network | It finds a dummy key; the model proxy refuses another run's secret, and stops answering when the run ends |

## What goes away

| Today | In this design |
|---|---|
| Agent kinds `COMPANY` and `INTERACTIVE` | One agent; authority from what starts the work (Part 1) |
| `usage` (`COMPANY`, `OWNER`) | The use right on a shared agent (Part 2) |
| The connection's token, the company OAuth account (ADR-0051), agent accounts | Accounts of service identities (Part 1) |
| Shared accounts and their fallback order (ADR-0052) | Grants of a service identity's account to a group, bound once per conversation (Part 3) |
| Connection types `TRACKER` and `REPOSITORIES` (ADR-0050), the connection's OAuth client per connection | One system per service, with one app (Part 3) |
| Model and runner credentials on the agent, model credentials, LiteLLM keys stored by hand | Model access of principals: a LiteLLM budget managed by Tinfirm, or a subscription on the owner's host (Parts 3, 7) |
| Resolving the credential at every call | Fixed per conversation, checked per call (Part 1) |
| Work items, one active run per work item, the shared `agent/{key}` branch | Conversations about a subject, independent and parallel (Part 5) |
| Decisions `QUESTION`, `APPROVAL`, `ACCESS`, `REVIEW` | Question and approval (Part 5) |
| Group rights `VIEW`, `RUN`, `DECIDE` on company agents | The trigger's team (Part 5) |
| Automatic write-back of interactive threads as the person | Write tools under their modes (Part 5) |
| Tool levels per connection, `DENY`, `changeRequest` and `sourceWrite`, platform-tool policies, thread grants of tools | A tool's effect, the agent's `ALLOW` or `ASK`, narrowing settings (Part 6) |
