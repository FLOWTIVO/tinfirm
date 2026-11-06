# Design and business review: credentials, accounts and reusing agents

Status: review, for the owner's decisions (§10) · 2026-10-10 · Owner: J. Balcar

The question: whether the business logic around company credentials, people's credentials and reusing agents is hard to work with because of its model, not its details. This document merges two independent reviews of the same revision, called the first and the second review below. Where they agreed, the text says it once. Where they differed, §7 records the choice and why. Where one found what the other missed, it is added here.

The document lists what exists (§1), walks the common setups (§2), names the findings and their causes (§3), proposes the model (§4), lists fixes that need no design (§5), gives a build order (§6), weighs the alternatives (§7), lists the scenarios that validate the model (§8), maps the old terms to new ones (§9), and lists the points the owner decides (§10). It builds on [ADR-0049](../adr/0049-agent-kinds-groups-and-personal-accounts.md), [ADR-0050](../adr/0050-connection-types.md), [ADR-0051](../adr/0051-connection-company-account.md), [ADR-0052](../adr/0052-shared-accounts.md) and the [connections review](2026-10-08-connections-review.md). It reads the server and web code and the decisions and their tests. No tests were run for it, and nobody was observed using the product: the findings come from reading the source. Everything in §4 is planned, not built.

## Summary

Each decision of the last week is sound on its own. The problem is what they add up to: **the agent's kind decides who acts**, for the agent's whole life. A company agent always acts with the company's credentials, and an interactive agent always acts with the person's. One fixed switch decides how work starts, whose accounts act, who pays for the model, who sees the result and whether the agent may ask. So one specialist is hired twice and kept in sync by hand. Each new need became a new account mechanism beside the switch, and a connection now has five. A member must connect every system an agent might touch before the first message, and a thread can change the identity it acts with between two calls without anyone choosing it.

The merged recommendation: **the agent becomes a reusable definition, and each piece of work runs under an authority that is chosen for it, shown before it starts and kept stable while it runs.**

- An agent has two **profiles**, Automation and Conversation. They share the definition and differ in how work arrives, what it may touch, whose accounts act, who pays, who sees it and what is published (§4.1).
- A thread fixes its **account bindings** when it starts and keeps them. Permissions are still checked at every call, and a failure pauses the operation with the right repair for the right person (§4.4).
- Two account concepts remain for people: **your accounts** and **company accounts**. "Shared" becomes a grant of a company account to a group (§4.3).
- Setup follows the work: the systems a task needs are checked before it runs, and the others are connected **when needed**, from the thread, with a way back (§4.5).
- **Who pays** for the model is a separate choice from **whose systems** the agent uses (§4.7).
- **What a conversation publishes** to the tracker becomes an explicit setting, and "read-only" means read-only on MCP servers too (§4.8, §4.9).

The fastest gains need no new model: accurate texts, repair actions that reach the person who can repair, tokens entered where they are used, and an execution summary of what today's rules will do (§5, §6 package 0 and 1).

## 1. What exists

### 1.1 The credentials

Ten kinds of credential or identity exist. Seven of them sit on or around a connection.

| # | Credential | Held by | Set where | Used for |
|---|---|---|---|---|
| 1 | The connection's token (`connection.secret_id`; the code's `Service`, the audit's `SERVICE`) | Company | Secrets, then the connect dialog | Polling, company agents' tools, company threads' write-back, git and change requests of company runs, the test, an MCP catalogue read. Required on every tracker and repositories connection (`ConnectionKind.needsSecret`), even one that only interactive agents use |
| 2 | The company's own account on an MCP server (ADR-0051) | Company | The MCP connection's page, OAuth only | Replaces #1 while it exists. MCP servers only |
| 3 | Agent accounts (`connection_account`) | One agent on one tracker | The tracker's page, OWNER and ADMIN | The agent's login for `ASSIGNED`, mentions and loop prevention. Its optional token (required on Jira) writes **only** the write-back. The agent's tool calls, its `label` among them, use #1 |
| 4 | People's own accounts (`connection_person_account`) | One member, owned secret | My accounts | Interactive agents' tools, workspaces and write-back |
| 5 | Shared accounts (ADR-0052) | Company, granted to groups | The connection's page, plus a Secrets trip for a token | Interactive agents, for members without #4 on the connection |
| 6 | The connection's OAuth client | Company | Connect dialog or connection's page, one per connection | Obtaining #2, #4 and #5 as OAuth grants. Trackers always need one registered at the system |
| 7 | Vault secrets | Company, or one member | Secrets | Every value above |
| 8 | Runner credentials | The runner host's owner (a person) | CLI on the host; picked on the agent | The model behind every run of the agent, whoever the run is for |
| 9 | Model credentials | Company | Credentials (titled "Model credentials") | The model behind every run of the agent |
| 10 | Tinfirm's personal access tokens and OAuth grants | A user | Profile | Inbound only: people's AI clients |

### 1.2 Who acts, today

The resolver is `ConnectionCredentials.callCredential`. It switches on the **agent's kind** (`ConnectionDirectoryImpl`, `PersonAccountsImpl.credential`), and it resolves again at **every call**.

| Action | Company agent | Interactive agent | When nothing is usable |
|---|---|---|---|
| A tracker's tools | #1. Never #3, even when the agent has its own token | #4, else #5 (`WRITE` before `READ`, then oldest) | `CREDENTIAL_REQUIRED`; a `READ` shared account and a write tool give `TOOL_DENIED` |
| An MCP server's tools | #2, else #1, else none | #4, else #5. A server that needs no credential still needs an account per person (`AccountRequirements`) | As above. A `READ` shared account refuses nothing here: MCP tools have no effect, so none counts as a write (`ConnectionToolInfo.writes()`) |
| Clone, push, change request | #1 of the repositories connection | #4, else #5. A `READ` account makes the workspace read-only | The run fails at checkout. A run keeps the git credential it was given until it ends |
| The state of a change request it opened | #1 | **#1** (`ChangeRequestWatch` ignores the person) | Skipped silently |
| Write-back | #3's token, else #1 | #4, else a `WRITE` #5. Written to the issue automatically, as the person | Dropped with a reason |
| The model | #8 or #9 of the agent | **#8 or #9 of the agent**: the person never counts | Queued with `CREDENTIAL_*` |

Three consequences of resolving at every call:

- **The identity can change inside a thread.** Group membership is read at each call (`PersonAccountsImpl`, `GroupMembership.groupsOf`), and a person's own account wins from the next call once it is connected. Joining a group, leaving one, or connecting an account changes which identity, with which permissions, the same thread acts with.
- **The fallback rules differ.** A broken #2 blocks #1. A broken #4 blocks a working #5. But a broken #5 is skipped when another #5 works.
- **Revocation is not uniform.** A tool call sees a revoked grant at once. A run's git credential stays until the run ends (ADR-0052 §2).

### 1.3 What decides what one agent may do

About eighteen mechanisms touch one agent. Grouped:

| Group | Mechanisms |
|---|---|
| Who may start it | The role (`GIVE_WORK`, `RUN_TRIGGERS`), `kind`, `usage` (`COMPANY` or `OWNER`, interactive agents only, defaulted from the credential), the group right `RUN` (company agents only), `status` |
| Who sees and decides | The group rights `VIEW` and `DECIDE` (company agents only, ADMIN not included; `DECIDE` without `VIEW` is allowed and only warned about), the thread's person (interactive only), OWNER implicitly |
| What it may touch | Repository access, a trigger's workspace, the tool level per connection, per-tool modes, the company's MCP catalogue switch, network hosts per agent and per thread, thread grants |
| What needs approval | `changeRequest`, `sourceWrite`, the four platform-tool policies (`ask` locked to `DENY` on company agents) |
| Whose credentials | The ten rows of §1.1 |

### 1.4 An example instance

To show how the model plays out, a small instance could be set up like this:

- **A business analyst** is a company agent with Jira and GitHub triggers. Its owner cannot talk to it to refine a story, and it cannot ask the product owner a question: its `ask` is locked to `DENY`.
- **A security analyst** is an interactive agent with usage `OWNER`: only owners and admins may talk to it. It uses an MCP server whose company account is signed in with write scopes, yet no company agent uses that server, so the grant's only use is reading the tool list. For the owner to talk to the agent, they need their own account on that server too: two sign-ins to one system by one person.
- One secret is the token of the GitHub tracker, the token of the GitHub repositories connection, **and** a read-only shared account granted to the developers' group. The shared account is the company's token under another name, created so that interactive agents may use it. That is a fallback the model does not offer, built by hand.
- No tracker has agent accounts, and Jira has no OAuth client. A member who wanted an interactive agent on Jira would paste an `email:api-token`.
- The business analyst runs on one person's Claude subscription, a runner credential on that person's own host. An interactive agent on that credential with usage `COMPANY` would then run every member's conversations on that one person's subscription.

## 2. The scenarios

### 2.1 One specialist for triggers and for people

Goal: the business analyst analyses every new story (a trigger), and the product owner can also talk to it about a story.

| # | Step today | Page |
|---|---|---|
| 1 | Hire "Business analyst" as a company agent: instructions, runtime, credential, policies, access, rights | Agents |
| 2 | Add the trigger | The tracker's page |
| 3 | Hire "Business analyst (chat)" as an interactive agent: copy the instructions (up to 50 000 characters), runtime, model, effort, credential, six policies, every repository level, every tool level and per-tool mode. Pick a second name and handle (`DUPLICATE_AGENT_NAME`, `DUPLICATE_AGENT_HANDLE`) | Agents |
| 4 | Each later change of instructions or access, made twice | Agents |

Where it bites: two agents and two handles for one specialist, and they drift apart. The triggered one cannot ask a question. The one people talk to cannot pick up the triggered thread's work. The UI also contradicts itself. The hire page says *"Triggers give it work on their own; people talk to it or mention it"* (`agent-page.html:122`). An interactive agent's Permissions tab says *"or run a trigger of it by hand. Triggers give it work either way"* (`agent-page.html:657`). The trigger editor says *"Triggers need a company agent"*.

### 2.2 An interactive developer for a team of five

Goal: five developers talk to a developer agent that reads Jira and pushes to GitHub.

| # | Step today | Page |
|---|---|---|
| 1 | Store a company Jira token and a company GitHub token. The tracker requires one even though no company agent will use it | Secrets |
| 2 | Connect Jira; connect GitHub repositories; add each repository | Connections |
| 3 | For OAuth: create each connection, copy its callback, register an app at Atlassian and at GitHub, return and edit each connection. One client per connection | Connections, the systems |
| 4 | Create the group with the five members | Groups |
| 5 | Either each member connects Jira and GitHub on My accounts, or a shared account per connection is created, granted and signed in to | Connection pages, My accounts |
| 6 | Make sure each of the five is OPERATOR or above (VIEWERs cannot give work) | Members |
| 7 | Hire the agent as interactive, usage "Everyone in the company", access | Agents |

Where it bites: the agent cannot be limited to the five. Usage is everyone or the owner and admins, and group rights apply to company agents only, so the group decides who gets a credential, not who may talk to the agent. Nothing on the agent page tells the admin which accounts members will need. The members learn it when they press **Talk to**.

### 2.3 A member's first conversation

| # | Step today |
|---|---|
| 1 | **Talk to**. The composer is replaced by *"You need to connect Acme Jira and Acme GitHub repositories with your credentials, and after that you can communicate with this agent."* |
| 2 | **Open My accounts**. Per connection: **Connect** (OAuth, if the admin registered a client) or **Add a token** (create a token at the system, choose its permissions from a table, paste it) |
| 3 | The OAuth sign-in returns to My accounts, not to the agent or the draft. Navigate back by hand |
| 4 | Every connection of every tool **and** of every repository counts. A member who only asks about an issue must still connect the repositories |

Other walls: a `RECONNECT` on a shared account sends the member to My accounts, where they can do nothing, since only an admin signs it in again. A member whose own grant expired cannot use the group's working account until they disconnect their own. A member with no usable agent reads *"Hire one on the Agents page"*, which they may not do.

### 2.4 A finance analyst

The product serves analysts, testers and finance, not only developers (PRODUCT.md). For a finance analyst, step 2 of §2.3 on Jira Cloud without an OAuth client means: open id.atlassian.com, create an API token, type `email:token` without spaces, and paste it. For a GitHub repository it means a fine-grained token with **Contents** read. These are the people least able to do it, and the wall comes before their first question.

### 2.5 A conversation on a Jira issue

A member opens a thread with an interactive agent on `SHOP-123` to think aloud. Inside Tinfirm the thread is private: the member and OWNER see it. On Jira, each turn's comment is written to the issue as the member, under *"{agent} (Tinfirm agent):"*, for everyone who can read the issue. The member is not asked. The thread is private, but its output is not.

### 2.6 One agent across two projects

A company agent works across projects: one trigger per project, each with its own scope, workspace and instruction. That part works. An interactive agent has no per-project shape. A conversation cannot pick a project or a repository, every connection of the agent counts for every person, and project-specific instructions need a second agent. Nothing duplicates an agent, templates one, or exports one.

## 3. Findings

| # | Finding | Evidence | Consequence |
|---|---|---|---|
| F1 | **The agent's kind decides five independent things, for good.** How work starts, whose accounts act, who pays, who sees and decides, and whether it may ask | `agent.kind`, immutable (`AgentService`); `TriggerValidator`; `WorkAccess`; `ask` locked by a database check (V023) | One specialist is two agents that drift (§2.1). A triggered agent cannot ask. People cannot talk to a triggered agent |
| F2 | **Five account mechanisms per connection, five precedence rules.** Connection token, company account (MCP only), agent accounts (trackers only), people's accounts, shared accounts. Each has its own table, section, failure rule and name | §1.1, §1.2; four sections on one connection page | The admin learns a matrix. An instance can end up rebuilding "let interactive agents use the company token" as a shared account holding the same secret (§1.4) |
| F3 | **The identity of a thread is not stable.** It is resolved again at each call from the current group memberships and accounts | `PersonAccountsImpl` (`groupsOf` at every call); ADR-0052 §2 | Connecting an account or joining a group changes whose permissions a running thread uses, without anyone choosing it |
| F4 | **Repairs reach the wrong person.** Every account error points to My accounts, including a shared account only an admin can sign in again. A broken own account blocks a working group account | `missing-accounts.ts`, `PersonAccountsImpl.missingAccounts` | Members are sent where they cannot act |
| F5 | **Setup is all or nothing, before anything.** Every connection of every tool and every repository, before the first message and before each turn. Auth-less MCP servers count too | `AccountRequirements`, `WorkAccess`, `Turns` | Adding a capability to an agent raises everyone's setup cost. A member connects systems the work never touches |
| F6 | **OAuth is set up per connection, by hand.** A client registered at the system for each tracker and repositories connection | `connection.oauth_client_id`; accounts.md §5 "no company-wide client yet" | Without it every member pastes tokens (§2.4). The Jira of §1.4 has none |
| F7 | **The way back is lost.** OAuth returns to My accounts; nothing returns to the agent, the draft or the thread | `PersonOAuth` callback | Each connected account costs a navigation |
| F8 | **The model never follows the person.** A run uses the agent's runner or model credential. A runner credential is one person's subscription, and it must sit on the agent creator's host | `TurnStarter`, `RunDispatcher`, `AgentService` (`CREDENTIAL_NOT_OWNED`) | Members' conversations run on the agent owner's subscription; a requester cannot bring their own. This may also conflict with the provider's terms for personal plans |
| F9 | **Who may use an agent follows its credential.** A runner credential sets usage `OWNER`, a model credential `COMPANY`, until someone opens Permissions | `AgentUsage`, `agent-page.ts:539` | Who may talk to an agent changes with an unrelated field |
| F10 | **Two permission models for agents.** `usage` (interactive) and group rights (company). `usage` is stored and ignored on company agents. ADMIN and the agent's creator see none of a company agent's threads unless in a group. `DECIDE` without `VIEW` is allowed | `AgentUsage`, `AgentRights`, CONVENTIONS §14.1 | An admin builds an agent they cannot debug. "Owner" means the company role, the agent's creator and `usage=OWNER` |
| F11 | **One agent, several external identities.** An agent account's token writes only the write-back; its tools use the connection's token; its pull requests are followed with the connection's token even when a person's account opened them. The UI says *"so it writes as itself"* | `OutboxDelivery` (only caller of `accountToken`), `ToolCalls`, `ChangeRequestWatch`; `connection-accounts.ts:60` | The external identity of an action is hard to predict. Interactive agents can be given agent accounts that nothing uses (no kind check in `ConnectionService`) |
| F12 | **Read-only is promised more broadly than it is enforced.** A `READ` shared account refuses known writes, but an MCP tool has no effect in the catalogue, so every MCP tool runs. The dialog says *"no tool that writes"* | `ConnectionToolInfo` (effect null for MCP, `writes()` false); `connection-shared-accounts.html:345`; accounts.md §6.2 says it correctly | On an MCP server the token's own scope is the only bound, and the UI does not say so |
| F13 | **A private conversation publishes.** An interactive thread on a tracker issue writes each turn's comment to the issue as the person, automatically | ADR-0049 §4; `OutboxDelivery` | The audience in Tinfirm and the audience of the output differ, and nobody chose it (§2.5) |
| F14 | **No reuse of agent definitions.** No duplicate, template, version or export. Kind and creator cannot change | `AgentsController`; openapi | Duplication by copy and paste, and no way to share a good agent |
| F15 | **One word, many meanings.** "Account" (five), "credential" (four), "company" (the role, the kind, the company account, shared accounts labelled "company credentials"), "personal access token" (a Jira PAT and Tinfirm's tokens) | `connection-shared-accounts.html:10`, `secrets.html:4`, `account-labels.ts:45`, `personal-tokens.ts:47` | Every guide needs a glossary; the UI has none |
| F16 | **The effective result is shown nowhere.** The agent page shows neither its triggers nor its agent accounts nor what a person will act with; the connection page has four account sections and no "who acts here"; nothing shows a thread's identity, payer and audience before it starts | `agent-page.html`, `connection-detail.html`, `new-conversation-dialog.ts` | "With what will Ana's thread read Jira, and who pays?" needs the code |

Root causes: F1 is the main one. The kind was the switch between two credential worlds, so each new need (an OAuth-only MCP server, analysts without code access) became a new mechanism beside the switch instead of a value in one model. F2, F3 and F5 follow from it. F8 and F9 are F1 again, for the model. F6 and F7 are the cost of setting OAuth up per connection. F4 and F10 to F16 are the leftovers. Most of these were deliberate decisions that became complicated as use cases were added; the review does not question the security goals behind them.

## 4. The model

One principle: **the agent is a reusable definition; each piece of work runs under an authority chosen for it, shown before it starts and kept stable while it runs.** The connection decides with what that authority acts. Every page shows the effective result.

### 4.1 One definition, two profiles

An agent is a **definition** and two **profiles**.

| Part | Holds |
|---|---|
| **Definition** | Name, handle, description, instructions, runtime, model, effort, and the capabilities it may be given: repositories, tools, network hosts. One name and one handle for both profiles |
| **Automation profile** (on or off) | Triggers attach to it. Within the definition's capabilities: the access it uses, its approval policies, its escalation (§4.8), its funding (§4.7), its audience (groups with `VIEW` and `DECIDE`) |
| **Conversation profile** (on or off) | People start it: conversations, threads on work items, mentions in Tinfirm, AI clients. Within the definition's capabilities: the access it uses, its approval policies, its publication policy (§4.8), its funding, who may use it (`USE`, §4.2) |

The thread takes its **mode** from the profile that started it:

| How the thread starts | Mode | Acts with | Seen by | Decided by |
|---|---|---|---|---|
| A trigger, a schedule, "run now" | **Company work** | The company's accounts (§4.3) | OWNER and the groups with `VIEW` | OWNER and the groups with `DECIDE` |
| A person | **Personal work** for that person | The person's account or their group's grant (§4.3) | The person and OWNER | The person |

The UI shows one agent with **Talk to** and **Automations**. Existing agents migrate one by one: a company agent gets Automation on and Conversation off, an interactive agent the reverse, each keeping its visibility and rules.

Why exactly two profiles, and not any number: the second review proposed open-ended execution profiles. Two cover the cases found (a conversation that only reads and an automation that writes; company funding for automation and the person's own for conversation), while the trigger already holds what differs between automations (scope, instruction, workspace). More profiles would rebuild the matrix this review removes: definition × profile × trigger × account. If a third profile is ever needed, the data model allows it; the UI should not offer it until then.

What ADR-0049 guarantees still holds, because a thread is one mode for its whole life:

- Personal work never acts with the company's credential unless the company granted it to the person's group (§4.3). Nothing falls back silently.
- Company work stays a closed box: people do not reply in it. A person who wants to steer it starts their own thread (planned: "Continue as mine", seeded with the company thread's summary, for people who hold `VIEW`).
- `job.user_id` and `run.acting_user_id` already exist. The resolver asks the thread (`callCredential(job)`) instead of the agent's kind plus a nullable person, which also removes the overload where a caller must pass `null` for company agents (`ToolCalls`).

This answers ADR-0049's rejected alternative, *"a credential mode per connection and an acting person per turn"*. The mode here is per thread, set by the profile, and it never changes inside a thread.

Reusing a definition never carries authority: a copy (§4.10) takes no credentials, threads, rights or account bindings, and a thread keeps the bindings it started with (§4.4).

### 4.2 One rights model, with presets

Groups hold rights on an agent: **`USE`** (start personal work, mention it), `VIEW`, `RUN` and `DECIDE` (company work). `usage` goes away.

- Two built-in groups: **Everyone** (every member; nobody edits its membership) and **Admins** (OWNER and ADMIN). `usage=COMPANY` becomes `USE` for Everyone; `usage=OWNER` becomes `USE` for Admins.
- **Presets** in the UI, so a right never lacks what it needs to be used: **User** (`USE`), **Observer** (`VIEW`), **Runner** (`VIEW` and `RUN`), **Reviewer** (`VIEW` and `DECIDE`). The API keeps the single rights.
- The agent's **manager** (today's `owner_user_id`, renamed so "owner" means one thing) maintains the definition and implicitly holds Observer and User. The manager's role ends there: being the manager never decides whose subscription other people consume (§4.7).
- The choice of credential no longer changes who may use the agent.
- "Limit the developer agent to the five" (§2.2) becomes one row: User for the group `developers`.

### 4.3 Your accounts and company accounts

People meet two concepts for external systems:

| Concept | What it is | Replaces |
|---|---|---|
| **Your account** | A member's own account at a system, owned and private. Token or OAuth are two ways to connect it | #4 |
| **Company account** | An identity the company manages at a system, with a name, the identity it represents (read with "who am I"), its access (read or read & write), its health and who maintains it. Token or OAuth are two ways to connect it. A connection may hold several. For each one the page says **who may use it, for what**: the default for automation (polling, catalogue, company threads), an **agent's identity** (§ below), and **grants** to groups for personal work | #1, #2, #3, #5 |

- **"Shared" becomes a grant.** A grant gives a group the use of a company account in personal work, read or read & write. **Everyone** is a group, so "members without their own account read through the company account" is one explicit, audited grant, not a hand-built copy of the token.
- **An agent's identity.** A company account can be bound to an agent on a connection: its login (for `ASSIGNED` and mentions) and, by default, the credential of **all** that agent's company work there, tools and write-back, and git on a code host. Polling, tool calls, publishing and repository operations may still be bound to different accounts where a company needs it, as an advanced setting; the page then shows each binding, so the difference is visible. The connections review's package 3 builds the default.
- **A company account is optional on every type.** A tracker used only for personal work needs none, and then has no triggers. A connection that needs no credential (an open MCP server, a public git host) is **open**: it requires no account from anyone.
- **Read & write has one meaning** on every account: what the token may do at the system *and* what Tinfirm allows (§4.9).
- **Tokens are entered where they are used.** Every dialog that takes a token takes the value and stores the vault secret itself, named after the connection and the account. "Use an existing secret" stays for one token used in several places. The Secrets page shows "used by" for each secret, and still lists the secrets other modules manage.
- Each company account shows the profiles and agents that depend on it.

Data: the company accounts absorb `connection.secret_id`, `connection_company_account` and the company side of `connection_shared_account`; grants keep `(account, group, access)`; `connection_account` becomes the binding of an agent to a company account, or keeps its own token. `connection_person_account` stays separate: owned secrets have different privacy and lifecycle rules. Nothing is released yet, so the migration may be direct, as `V025` was.

### 4.4 Account bindings that stay with the thread

**Before work starts**, the composer shows an **execution summary** for the conversation profile, in the requester's terms:

> Jira: your account (jdoe)
> GitHub: Analysts company account, read-only
> Model: company-funded (Anthropic key)
> Visible to: you and the company's owners
> Publish to Jira: ask me each time

When more than one account is authorized for a system, the line is a choice, with the default preselected; otherwise it is a statement. The common case stays one click. Automation threads get the same summary on the trigger and on each thread's page.

**While it runs:**

- The thread **keeps** each account it bound (`job` gains its bindings: per connection, the account and its holder). Connecting another personal account or joining a group does not replace a binding. Token rotation and OAuth refresh continue through the same binding.
- **Permissions are checked at every operation.** A binding is never a grant: when the person leaves the group, the grant is removed, or the account is deleted, the next operation stops. The pause names the repair (below).
- **A failure pauses, never switches.** If a bound account fails, the operation waits with **Reconnect** (your account) or **Choose another account** (from those authorized). Choosing another is a decision of the thread's person, recorded in the thread and the audit.
- **Repository operations during a run** need a defined answer to a revocation. Today the run keeps its git credential until it ends. Recommended: what is cloned stays, and a push after a revocation is refused and the turn ends with the reason; the mechanism (the push lease or a server check before push) is a design point (§10, row 8).

**Repairs reach the person who can act:**

| Problem | What the person sees |
|---|---|
| Your account expired or was refused | **Reconnect** your account, back to this thread |
| A company account failed | "An admin must sign in *Analysts* again"; admins get it in their inbox |
| You hold no grant for the system | **Request access**: a decision for the admins |
| The profile or account allows reads only | What the restriction is, and that the agent continues with reads |
| An optional system is not connected | Nothing blocks; the agent continues with what is available (§4.5) |

### 4.5 Setup for the selected work

- **Required or when needed.** Each of the agent's connections is required or when needed in the conversation profile; the default is when needed. A conversation starts when the required ones are bound.
- **The task's own resources.** The person may pick what the task works on (a repository, a project) when the agent offers several. The pre-check covers those and the required connections, not everything the agent could reach. An analyst asking about the code proceeds with GitHub even if the agent also supports Jira and Sentry.
- **The server enforces the boundary.** A tool of a connection without a binding is not offered to the run, and a call to it is refused; a repository without one is not cloned. The agent does not have to remember what to avoid.
- **Connect when needed.** Asking for a missing system (a tool, or a repository through `request_access`) raises a **Connect** card in the thread, built like an `APPROVAL`, with the system's sign-in. The call waits and runs once the person connects, as an approved call runs today (`ToolApprovals`).
- **The way back.** Sign-in and **Add a token** carry a `returnTo`, so the person returns to the draft, the thread or **Talk to**, with the call continued.
- **The agent page says it first.** "To work with you, this agent needs: Jira (required, you're connected), GitHub (when needed, not connected)". The agents list marks the agents that need something from you. Admins see the same list on the agent page before anyone presses **Talk to**.

### 4.6 OAuth once per system; the same system grouped, not merged

- **One OAuth app per system, at the company.** A company registers Tinfirm once at Atlassian, once per GitHub host and once per GitLab instance; every connection on that system uses it. The connection's page shows "uses the company's Atlassian app". The per-connection client stays as an override. MCP discovery and the client metadata document stay as they are.
- **Members see Connect, not Add a token,** wherever the system offers OAuth. A token stays as the fallback.
- **Grouped in the UI, bound per connection.** My accounts groups connections by system ("GitHub: tracker, repositories"). Connecting one offers **use the same account for the others** in one click. The binding stays per connection, because accounts of the same provider are not interchangeable: a fine-grained GitHub token covers one resource owner, two organisations on `github.com` need two tokens, and a tracker's token and a git token need different permissions.
- **The GitHub App** (connections review §4.8) remains the target for GitHub: installation tokens for automation, user-to-server tokens for people, one registration.

### 4.7 Who pays is separate from whose systems

Two independent questions per thread: **whose permissions apply at Jira, GitHub or an MCP server** (§4.3, §4.4), and **whose model account and runner execute the work**.

| Profile | Funding |
|---|---|
| Automation | The agent's company model credential. A runner credential is allowed, labelled "automation runs on Ana's subscription", and only with that person's consent |
| Conversation | A profile setting: **company-funded** (a company model credential; it needs the `USE` right, and budgets per agent or person are planned), **the requester's own** (their runner host's subscription, or a personal key stored as an owned secret, planned), or **the requester's own, else company-funded**, shown as such in the summary |

Rules: a person's subscription never serves another person's work without that person's explicit authorization. The summary says who pays before work starts. An unavailable funding source pauses the thread; it never switches to another payer on its own. The agent's manager maintains the definition and does not, by being its manager, fund other people's conversations.

### 4.8 Audience, publication and escalation

| Setting | Profile | Values |
|---|---|---|
| **Conversation audience** | Conversation | The requester, and OWNER for support and audit, said plainly in the summary |
| **Publication** | Conversation | What a personal thread writes to the tracker: **private** (nothing; the result stays in Tinfirm), **ask me each time** (each comment becomes an approval of the thread's person, one click), **automatic** (today's behaviour). The summary names the destination's audience. Recommended default: ask me each time |
| **Automation audience** | Automation | Groups with Observer, Runner and Reviewer |
| **Escalation** | Automation | `ask` becomes a policy, `DENY` by default. When allowed, a question goes to the groups with `DECIDE`, as access requests do today. An answer is a decision, not a reply: it never changes the thread's acting identity |

### 4.9 Read-only means read-only

- Every tool gets an effect: the trackers' fixed tools have one today; an MCP server's tools get one from the admin on the connection's page, prefilled from the server's `readOnlyHint` and `destructiveHint` annotations where it sends them. Those annotations are hints from the server, so an admin confirms them.
- Under a read-only account or profile, a tool with an **unknown** effect counts as a write and is refused until someone classifies it.
- Until that is built, the dialog says what is true: on an MCP server, the token's own scope is the bound (§5, Q16).

### 4.10 Reusing agents

| Step | What | When |
|---|---|---|
| Duplicate | **Duplicate** copies the definition and both profiles' policies. It takes no name, handle, account bindings, rights, triggers or threads | Now (Q15) |
| Templates | "Hire an agent" offers starting points (developer, business analyst, tester, triage, reviewer, finance analyst) with instructions, policies and the access they usually need, filled from the company's connections | Next |
| Agents as code | Export and import an agent as YAML: definition, profiles, access by connection and repository name, never credentials. Importing into another company maps the resources again and authorizes no account. Later, sync from a git repository | Later |
| Per-task shape | Already there for automation: instruction and workspace per trigger. Conversation gets the same through the task's resources (§4.5) | With §4.5 |

### 4.11 What the pages show

| Page | Shows |
|---|---|
| The agent page › **Talk to** and **Automations** | The two profiles, each on or off. Automations lists its triggers (linked), its audience and escalation. Talk to lists who may use it, publication, required and when-needed systems |
| The agent page › **Access** | One card per connection (connections review §4.3) with the binding chain per mode, in words: "Automation: acts as `svc-ba` · Conversation: your account, else Analysts (read-only)" |
| The composer and the thread | The execution summary before start; on the thread, the bound accounts, the payer, the audience and the publication setting; Connect cards and paused operations with their repair |
| A connection's page › **Accounts** | One section instead of four: company accounts, each with what uses it (automation default, agents, grants) and its health; "people with their own account: 4" (a count only); OAuth: "uses the company's Atlassian app" |
| My accounts | Grouped by system; per connection your account, or the grant you act with, or Connect; "use the same account" across a system's connections |
| Settings › OAuth apps | The company's app per system, with the callback to register |

## 5. Fixes that need no design

Each is small, fits today's model, and removes a confusion now. All are verified in the code.

| # | Fix | Where |
|---|---|---|
| Q1 | Make the texts agree with the rule: the hire page and an interactive agent's Permissions tab say triggers give it work | `agent-page.html:122-125`, `:657-661` |
| Q2 | A member with no usable agent reads "Hire one on the Agents page", which they may not do. Say who to ask | `new-conversation-dialog.ts:123` |
| Q3 | The agent accounts picker lists interactive agents, and the server accepts them, though nothing uses them. Filter and refuse | `connection-detail.ts:180-187`, `ConnectionService` (replace accounts) |
| Q4 | The run page's *"Its tool calls run with this person's own accounts"* and the decision card's tooltip leave out shared accounts | `runs/run.ts:101`, `decision-card.ts:183` |
| Q5 | Split `RECONNECT` into "you reconnect" and "an admin must sign in the shared account again", and tell the admins | `PersonAccountsImpl.missingAccounts`, `missing-accounts.ts` |
| Q6 | Return to where the person started: a `returnTo` on the OAuth authorize request and on Add a token | `PersonOAuth`, `my-accounts` |
| Q7 | An MCP connection without a credential and without OAuth requires no account from people | `AccountRequirements.toolConnections` |
| Q8 | Show, instead of silently changing, the usage a credential implies | `agent-page.ts:539-546` |
| Q9 | Follow an interactive thread's change request with the credential that opened it | `ChangeRequestWatch` |
| Q10 | Show on the agent page, for admins too, the accounts members will need, and the agent's triggers and agent accounts (read-only, linked) | `agent-page.html` |
| Q11 | The agent accounts section says *"so it writes as itself"*. Say what is true until the agent account acts for all company work: its comments, not its tool calls | `connection-accounts.ts:60` |
| Q12 | Accept a token's value in the connect, agent account and shared account dialogs, and store the secret | `connection-dialog.ts`, `connection-accounts.ts`, `connection-shared-accounts.html` |
| Q13 | Rename the nav item "Credentials" to the page's title, "Model credentials" | `shell.ts:110` |
| Q14 | Remove the duplicate computation of missing accounts (`AgentService` beside `PersonAccountsImpl`), the unused `PersonAccounts.has()` and `missing()` with their different rule, and the stale comments of `V025` | server |
| Q15 | **Duplicate** an agent, carrying no authority (§4.10) | web, one endpoint or a prefilled form |
| Q16 | The read-only hint promises *"no tool that writes"*. On an MCP server say that the token's scope is the bound, as accounts.md §6.2 already does | `connection-shared-accounts.html:345` |
| Q17 | Warn in the composer of an interactive thread on a tracker issue that its comments are written to the issue as the person | `thread-composer.ts` |

## 6. Build order

Each package lands with its tests and documentation. Packages 2 to 4 change the contract (`api/openapi.yaml`, CONVENTIONS §14.6 and §14.13, PLATFORM.md §4.1 and §4.2) and need an ADR each, written from §10.

| # | Package | Contents | Gives |
|---|---|---|---|
| 0 | **Quick fixes** | §5, Q1 to Q17 | Accurate texts and fewer dead ends, now |
| 1 | **Understandable and recoverable** | The execution summary of today's rules (read-only, before start and on the thread); repairs that reach the right person (§4.4 table); tokens entered in place; `returnTo`; the agent page's "needs" list | Today's behaviour can be predicted and repaired |
| 2 | **Profiles and rights** (§4.1, §4.2, §4.8 escalation) | `agent.kind` replaced by the two profiles; `job.mode`; `callCredential(job)`; `USE`, Everyone and Admins, the presets, the manager; `usage` retired; the `ask` policy for automation; the migration of each agent into its equivalent profile | One specialist for triggers and people |
| 3 | **Stable bindings and setup for the work** (§4.4, §4.5) | Bindings on the job; pause instead of switch; Choose another account; required or when needed; the task's resources; the server-side boundary; the Connect card | The identity of a thread is chosen once and kept; setup follows the work |
| 4 | **Company accounts** (§4.3, §4.6) | Company accounts with uses and grants; the agent's identity as the credential of its company work; open connections; one OAuth app per system; My accounts grouped by system | One account model; OAuth without per-connection setup |
| 5 | **Funding** (§4.7) | The conversation profile's funding; personal keys as owned secrets; pause on an unavailable payer; budgets planned | Who pays is chosen, shown and never switched |
| 6 | **Publication and read-only** (§4.8, §4.9) | The publication setting and its approval; tool effects for MCP; unknown counts as a write under read-only | Private stays private; read-only holds on every system |
| 7 | **Reuse** (§4.10) | Templates; export and import as YAML; later, sync from git | Good agents are shared without copy and paste |
| 8 | **The GitHub App** | Its own design, as the connections review planned | — |

The setup before and after packages 1 to 4:

| Scenario | Today | After |
|---|---|---|
| 2.1 One specialist for triggers and people | Two agents, two handles, copied settings; no questions from the triggered one | One agent with Automation and Talk to. The trigger runs as the company; the product owner talks to the same agent as themself; the automation may ask its reviewers |
| 2.2 A developer agent for five | A group, five accounts or a shared account per connection, usage for everyone, a client per connection | User for `developers`; the company's GitHub and Atlassian apps once; a read & write grant to `developers` if they hold no access of their own |
| 2.3 A member's first message | A wall listing every connection; My accounts; back by hand | The summary shows what will act; the conversation starts; a Connect card appears when the agent first reads Jira; the sign-in returns to the thread and the call runs |
| 2.4 A finance analyst | Paste `email:api-token` before the first question | Sign in with Atlassian when the agent first needs it |
| 2.5 A conversation on a Jira issue | Comments written to the issue as the member, unasked | "Publish to Jira: ask me each time" in the summary; each comment is the member's one-click approval |
| 1.4 A shared account built by hand | A shared account that repeats the company's token | A read grant of the company account to the group |

## 7. Alternatives considered

The first group records where the two reviews differed and what this merge chose.

| Question | First review | Second review | Merged, and why |
|---|---|---|---|
| How to separate the definition from authority | The thread's mode from how it started; one access set for both modes | Open-ended execution profiles per specialist | **Two fixed profiles.** One access set cannot give a conversation reads and an automation writes; open-ended profiles risk a new matrix, and triggers already hold what differs between automations |
| When a bound account fails | Step to a working group grant, and say so | Pause; offer Reconnect or Choose another account | **Pause.** An automatic step is another identity change nobody chose (F3) |
| Choosing accounts before start | Not proposed; a needs list on the agent page | An execution summary with explicit choices | **The summary always; a choice only where more than one account is authorized**, so the common case stays one click |
| One account per system | Key people's accounts by host: GitHub once | Do not merge credentials of the same provider | **Group by system, bind per connection**, with "use the same account" in one click. Fine-grained tokens are per resource owner, and the two connections need different permissions |
| Identities per operation | The agent's account acts for all its company work | Explicit bindings for polling, tool calls, publishing and repositories | **One identity by default, per-operation bindings as an advanced setting**, always shown |
| OAuth set up | One app per system at the company | Not covered | Kept: the largest barrier for non-technical members (§2.4) |
| Who may use an agent | `USE` right, Everyone and Admins | Presets Observer, Run, Reviewer | **Both**: rights in the API, presets in the UI |
| Publication | Not covered | An explicit publication policy | Added (§4.8, F13) |
| MCP read-only | Not covered | Classify conservatively; until then describe truthfully | Added (§4.9, F12, Q16) |

Other alternatives:

- **Keep the kinds and add Duplicate, or a linked twin.** Cheapest: two agents share one definition and differ by kind. It still shows two names and two handles, a triggered agent still cannot ask, and the credential switch stays on the agent, so F2 to F5 remain. Worth doing now as Q15; it does not replace §4.1.
- **A credential mode per connection on the agent** (ADR-0049's rejected alternative). Still rejected: it mixes both worlds inside one thread.
- **Fall back to the company's account for anyone without their own.** Rejected: it undoes the reason for ADR-0049. §4.3 makes the same effect an explicit grant to Everyone, read-only if the company wants.
- **A permissions matrix page.** As in the connections review: a view later, not a home. The summary and the chains in words answer the common questions where they are asked.
- **Merge people's accounts into the same table as company accounts.** Rejected: owned secrets have different privacy and lifecycle rules. The pages present one model; the tables need not.

## 8. Validation scenarios

The model is right when each of these holds, as an integration test where the server decides and as an end-to-end test where a person sees it:

| # | Scenario | Holds when |
|---|---|---|
| V1 | Two people use one agent with different accounts | Each thread acts with its own person's bindings; neither sees the other's thread |
| V2 | One agent serves a trigger and a conversation | The trigger's thread acts as the company and is seen by its groups; the conversation acts as the person; both use the same instructions |
| V3 | An analyst asks about code through a read-only company account, holding no account at the code host | The workspace is read-only, no push or change request, no write tool runs, nothing is published as the analyst |
| V4 | An unrelated system's account expired | Work that does not need it starts and finishes; work that needs it pauses with the right repair |
| V5 | A person connects their own account, or joins a group, during a thread | The thread's bindings do not change |
| V6 | A grant is revoked during a thread | The next operation stops with the repair; a push after the revocation is refused (§10, row 8) |
| V7 | A shared company account fails | Members see "an admin must sign it in again", admins see a task, nobody is sent to My accounts for it |
| V8 | An agent is duplicated or exported | No credential, binding, right, trigger or thread is carried over |
| V9 | The chosen payer is unavailable | The thread pauses; no other account is charged |
| V10 | A guaranteed read-only account on an MCP server | A tool with an unknown or write effect is refused |
| V11 | A conversation on a tracker issue | Its output is written to the issue only as its publication setting says, and the summary named the audience |
| V12 | An instance set up as in §1.4 migrates | A company agent gets Automation with its triggers and rights; an interactive agent with usage `OWNER` gets Talk to with User for Admins; a shared account built by hand becomes a read grant of the GitHub company account to its group |

## 9. One vocabulary

| Today | Proposed |
|---|---|
| Company agent / interactive agent | One agent with **Automations** and **Talk to**; **company work** / **personal work** for the thread |
| The connection's token, secret, `Service`; the company account | **Company account** (one is the default for automation) |
| Shared account, "company credentials" | A **grant** of a company account to a group |
| Agent account | The **agent's identity**: a company account bound to the agent |
| Person's account | **Your account** at a system (My accounts) |
| Runner credential, model credential, nav "Credentials" | **Model access**: company keys and subscriptions; "who pays" in the summary |
| `usage` | The **`USE`** right; the presets User, Observer, Runner, Reviewer |
| The agent's owner | The agent's **manager** |
| Personal access token (Tinfirm) / Jira PAT | **Tinfirm token** / the system's own name ("Jira token") |
| `RECONNECT` | `RECONNECT_YOURS` (you can fix it) / `RECONNECT_COMPANY` (an admin must) |

## 10. Open points for the owner

| # | Question | Recommendation |
|---|---|---|
| 1 | Replace the agent's kind with one definition and two profiles, Automation and Conversation, with the thread's mode taken from the profile? | Yes. It removes the main cause (F1) and keeps ADR-0049's guarantees per thread |
| 2 | Fixed two profiles, or open-ended? | Two. Revisit only with a case two cannot serve |
| 3 | May automation ask questions, to the groups with `DECIDE`? | Yes, as a policy, `DENY` by default |
| 4 | Replace `usage` with a `USE` right, built-in Everyone and Admins, and presets in the UI? | Yes |
| 5 | Implicit rights: the manager Observer and User; ADMIN? | The manager yes. ADMIN through the Admins group, which the owner may remove from an agent |
| 6 | Bind accounts to the thread and keep them; check permissions per call; pause and offer a choice on failure? | Yes |
| 7 | An execution summary before every conversation, with choices only where several accounts are authorized? | Yes |
| 8 | A revocation during a run: refuse later pushes, keep what is cloned? | Yes; the mechanism is a design point of package 3 |
| 9 | Company accounts with uses and grants instead of shared accounts, the agent's identity as a binding? | Yes |
| 10 | One identity per agent per connection by default, per-operation bindings as an advanced setting? | Yes |
| 11 | Required or when needed per connection, Connect cards, the server enforcing the boundary? | Yes; "when needed" by default |
| 12 | One OAuth app per system at the company, the per-connection client as an override? | Yes; the GitHub App later |
| 13 | Group by system in the UI, bind per connection? | Yes |
| 14 | Funding per profile; a person's subscription never serves another without consent; never switch payers? | Yes |
| 15 | Publication for conversations: private, ask me each time, automatic; default ask me each time? | Yes |
| 16 | Tool effects for MCP tools; unknown counts as a write under read-only? | Yes |
| 17 | Duplicate now, templates next, agents as code later? | Yes |
| 18 | Later: a mention on the source starts personal work for the member whose connected account wrote it? | Worth a design once accounts are grouped by system: the account's identity maps the source's user to the member |
