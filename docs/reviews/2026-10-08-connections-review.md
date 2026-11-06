# Design and business review: working with connections

Status: review, with the owner's decisions of 2026-10-08 in §7 · 2026-10-08 · Owner: J. Balcar

The problem: the connections are hard to set up; permissions live in several places; a company that uses GitHub for its issues and for its repositories, or Jira for its issues and GitHub for its repositories, does not find the setup straightforward. This document says what exists and where each setting lives (§1), walks the common setups step by step (§2), names the findings (§3), proposes the design that closes them (§4), weighs the alternatives (§5), gives a build order with the setup before and after (§6), and lists the points the owner decides, with the ones decided (§7). An ADR is written from §7, as [ADR-0046](../adr/0046-agent-tool-permissions.md) was written from the [tool permissions design](2026-10-07-agent-tool-permissions.md).

In one paragraph: the settings are each justified on their own, and the problem is what they add up to. One GitHub connection is a tracker and a code host at once, with one token for both, so a company with Jira and GitHub sets up a tracker it does not use. One repository is named three times (as a URL, as a trigger's scope, in a trigger's workspace), nothing links the three, and nothing is discovered from the token. What an agent is on a system (its account) is set on the connection page while what it may do there is set on the agent page. And a pull request an agent opened is followed for its state only: its review never reaches the agent, and a trigger that watches pull requests makes it a second work item on another branch. The design keeps the rule of ADR-0046 and the data model, and moves each setting to its subject: a connection is one of three types, a tracker, repositories or an MCP server, each set up on its own with its own token (the owner's decision of 2026-10-08); repositories and scopes come from the token; a pull request Tinfirm opened belongs to the work item it was opened for, and its review continues that thread; the agent page holds everything about one agent on one connection; every other page shows the result read-only.

## 1. What exists

Three objects carry the setup (PLATFORM.md §4): a **connection** (a system, its credential, what Tinfirm does there), an **agent** (what it may do, and who may give it work), and a **trigger** (when, and in which repositories). A **decision** widens one thread. Every setting a person makes about access, with where it lives:

| Setting | Stored on | Set where | Who | Per |
|---|---|---|---|---|
| The connection's token: the poll, the write-back, the tool calls, change requests, clone and push | `connection.secret_id` | Secrets, then Connections › Connect a system | OWNER, ADMIN | company |
| The tools of an MCP server the company allows or denies | `connection_tool.policy` | the connection page › Tools | OWNER, ADMIN | company |
| A tracker's tools (`get`, `search`, `label`) | fixed | the connection page › Tools, read-only; no company switch ([ADR-0046](../adr/0046-agent-tool-permissions.md), later work) | — | company |
| An agent's identity on a system: a login or username, and its own token (required on Jira, optional on GitHub and GitLab) | `connection_account` | the connection page › Accounts, replaced as a whole | OWNER, ADMIN | agent × connection |
| The repositories the token clones and pushes | `repository` | the connection page › Repositories, each by URL | OWNER, ADMIN | company |
| What a trigger watches: `owner/repo`, a project key or a GitLab path, typed | `connection_trigger.scope` | the trigger editor › Scope | OWNER, ADMIN | trigger |
| The repositories of a trigger's jobs, each read-only or not | `connection_trigger.workspace` | the trigger editor › Workspace: all the agent may access, or chosen | OWNER, ADMIN | trigger |
| An agent's access to a repository, `READ` or `WRITE` | `agent_repository` | the agent page › Access › Repositories | OWNER, ADMIN; an OPERATOR for their own | agent × repository |
| An agent's level on a connection's tools: None, Read, Read & write | `agent_tool.access` | the agent page › Access › Tools | idem | agent × connection |
| An agent's mode per tool: Deny, Ask, Default, Allow | `agent_tool.tools` | the agent page › Access › Tools | idem | agent × tool |
| What needs approval: `changeRequest`, `sourceWrite` | `agent.policy_*` | the agent page › Permissions › What needs approval | idem | agent |
| The platform tools: `ask`, `requestAccess`, `requestNetworkAccess`, `saveArtifact` | `agent.policy_*` | the agent page › Permissions › What it may do | idem | agent |
| Who may give it work | `agent.usage` | the agent page › Permissions › Who may give it work | idem | agent |
| Network hosts granted for good | `agent_network_host` | the inbox ("approve for this agent"); revoked on the agent page › Network access | a member who decides; whoever may change the agent | agent |
| A thread's extra repository, tools or host | `job.workspace`, `job.granted_tools`, `job.granted_hosts` | the inbox (an `ACCESS` decision) | a member who decides | thread |
| What the token may do at the source | the source | GitHub, GitLab, Jira | the source's administrator | outside Tinfirm |

Five pages set them: Secrets, Connections, the connection page, the agent page (three of its tabs), the inbox. The rule that resolves them at run time is one, on the server (`RunTools.modeOf` for tools, ADR-0046; `Workspaces.of` for a job's repositories; the git proxy and `CodeHosts` for pushes and change requests), and that rule, the data model and the audit trail are not the problem. The guides ([connections](../operations/connections.md), [agent permissions](../operations/agent-permissions.md), [MCP servers](../operations/mcp-servers.md)) describe each setting; none walks a company through a setup.

What happens to a pull request an agent opened, today: the watch (`ChangeRequestWatch`, [ADR-0022](../adr/0022-pull-request-state.md)) reads its state (open, draft, merged, closed) with the repository's token and records it on the artifact; nothing tells the thread. Its review comments are read only by a GitHub connection's poll under a trigger that watches pull requests, which makes the pull request a work item of its own (`SubjectSync.linkOrCreate`), keyed `acme/shop#57`, with the branch `agent/acme-shop-57`, while the pull request's head is `agent/shop-123`. Reviews as such (changes requested, approved) and check results are not read anywhere.

## 2. The scenarios

### 2.1 GitHub for the issues and for the repositories

Goal: a developer agent implements the issues of `acme/shop` that get the label `ai` and opens pull requests; a triage agent labels every new issue.

| # | Step today | Page |
|---|---|---|
| 1 | Store a fine-grained token of a machine user with Issues, Pull requests and Contents read and write and Metadata read on the repositories: one token for everything | Secrets |
| 2 | Connect a system › GitHub: name, `https://api.github.com`, the secret; Test | Connections |
| 3 | Add repository: `https://github.com/acme/shop.git`, name `shop`; each repository by hand, nothing lists what the token sees | the connection page |
| 4 | Hire the agent: identity, instructions, runtime, credential; Access: `shop` Read & write, GitHub tools Read; Permissions: the defaults | Agents |
| 5 | Accounts: the agent's login, so that `ASSIGNED` works and its comments appear as the agent | the connection page |
| 6 | Add trigger: "when an issue gets the label `ai`", scope `acme/shop` typed again, the agent, the instruction; Workspace: all, or chosen (`shop` a third time) | the connection page |
| 7 | The triage agent: steps 4 and 6 again, with GitHub tools Read & write and `label` set to Allow (or its source write policy to Allow) so that its labels need no approval | Agents, the connection page |

Where it bites: the one repository is named three times, in two syntaxes, and nothing links them. A trigger whose agent has no access to the repository its scope names starts jobs without it: the editor says nothing (`Workspaces.of` gives the job every repository the agent may access, which may be none), and the agent finds out in the sandbox. The connection shows polling, accounts, triggers and tools before a trigger exists. The one token holds Contents write, so the token the runner host gets for git can also comment, label and close issues (threat model R27). The developer's pull request gets its review only through a second trigger, which makes it a second work item on another branch. Seven steps on four pages, the agent page twice.

### 2.2 Jira for the issues, GitHub for the repositories

Goal: a developer agent implements `SHOP-123` when it enters `Ready for AI`, opens a pull request in `acme/shop`, fixes what the reviewer asks, and the issue moves to `In Review`.

| # | Step today | Page |
|---|---|---|
| 1 | Store the Jira PAT of a service account, a Jira PAT per agent (required on Jira), and a GitHub token | Secrets |
| 2 | Connect Jira: base URL, the secret; Test | Connections |
| 3 | Connect GitHub: the only way to get pull requests (a `GIT` connection clones and pushes but opens none, `CodeHosts`). It is created as a tracker: polling on, Accounts, Triggers and Tools on its page, and a token the guide describes with Issues and Pull requests permissions, though only Contents and Pull requests are used | Connections |
| 4 | Add repository `acme/shop` by URL | the GitHub connection page |
| 5 | Accounts: the agent's Jira username and its PAT | the Jira connection page |
| 6 | Hire the agent: Access: `shop` Read & write (under the GitHub connection), Jira tools Read | Agents |
| 7 | Add trigger: "when an issue enters `Ready for AI` in `SHOP`", Workspace: chosen, `shop` (a repository of the other connection); on success `STATUS_TO In Review` | the Jira connection page |

Where it bites: two connections with two vocabularies for one job, and the GitHub one looks like a tracker that is not one. One PAT per agent before the first run, which GitHub and GitLab do not ask for. "This agent works on Jira as `svc-dev` and may read it" is two pages: the identity on the connection page, the access on the agent page. The agent's own PAT writes its comments, but its tool calls read with the connection's PAT (`ToolCalls`, `ToolApprovals`), so the service account's permissions, not the agent's, decide what it reads. And the loop stops at the pull request: the reviewer's "changes requested" never reaches the agent, and a GitHub trigger on pull requests would start a second work item on another branch instead of continuing the Jira thread.

### 2.3 GitLab for both (self-managed)

One connection, as 2.1, with group paths. The same three names for one project (`https://gitlab.example/acme/shop.git`, the scope `acme/shop`, the workspace). A group scope (`acme`) watches every project below it, but repositories are still added one by one.

### 2.4 A non-code specialist: Jira and the company's own MCP server

A finance analyst with Jira tools Read and the CRM's `search_customers` allowed, no repositories. This one is coherent: the MCP connection's page holds the company's switch, the agent's Access tab the grant, by design ([ADR-0040](../adr/0040-mcp-tool-connections.md)). The one wrinkle: the level control on the row is required (`READ`) and means nothing (`WRITE` is `400`).

### 2.5 Two GitHub organisations, or two tokens

Each token is a connection (`GitHub acme`, `GitHub acme-labs`), both at `api.github.com`; repositories and triggers go under the right one, an agent's access lists them under the connection's name, the tool handles become `github_acme_get` and `github_acme_labs_get`. It works; the cost is that "GitHub" is not one thing on any page.

## 3. Findings

| # | Finding | Evidence | Consequence |
|---|---|---|---|
| F1 | **One system, two roles, one token.** A `GITHUB` or `GITLAB` connection is the tracker (events, write-back, tools) and the code host (clone, push, change requests) at once, with one credential for both | `ConnectionKind`, `RepositoryCredentials`, `CodeHosts`; the connections guide §3 | A company that uses GitHub for code only sets up a tracker (2.2). A company that wants the narrowest git token has only `GIT`, which opens no change request. R27 stays as it is |
| F2 | **One repository, three names, no link.** `repository.url`, `connection_trigger.scope` and `connection_trigger.workspace` describe the same `acme/shop` in three forms; the scope is free text behind a pattern | `BaseUrls.repositoryUrl`, `GitHubRepository.parse`, `connection-kinds.ts`, the trigger editor | The admin types the repository three times (2.1). A trigger whose agent cannot reach its scope's repository starts jobs without it, and nobody is told |
| F3 | **Nothing is discovered.** The token's own list of repositories is never asked for; the test says who the token is and nothing about what it reaches | `GitHubClient`, `GitLabClient` (no repository listing), `testConnection` | Every repository is typed as a URL that must match the connection's host, with a name chosen by hand |
| F4 | **Per-agent settings are split by page, not by subject.** The agent's identity on a system is set on the connection page; its access to the same system on the agent page. The Jira account's token is required, GitHub's and GitLab's optional, for no stated reason; and the account's token serves the write-back but not the agent's tool calls | CONVENTIONS §14.5 (accounts), §14.6 (access); `OutboxDelivery` (the account's token), `ToolCalls` (the connection's) | One fact, "this agent is `svc-dev` on Jira and may read it", is two pages; "the agent reads as itself" is true nowhere; N+1 PATs before the first Jira run |
| F5 | **One word, two meanings.** `AccessLevel` is `READ`/`WRITE` on a repository (clone / push) and on a connection's tools (reads / writes); on an MCP connection it is required and means nothing | `AccessLevel`, `AgentToolAccessInput.access`, the Access tab's "Read & write" on both | The same control reads the same and does different things |
| F6 | **Approval is set in three places that interact.** `sourceWrite` on the Permissions tab is the mode of `label` under `WRITE`, unless the per-tool entry on the Access tab says otherwise; a trigger's own `LABEL_ADD` is never gated; `changeRequest` sits apart from the repository it concerns | ADR-0046, `AgentPolicies`, the Access tab's hints | The hint on the Access tab is the only place that says what a label call will do for this agent |
| F7 | **Thread grants are asymmetric.** A host can be approved "for this agent" from the inbox ([ADR-0044](../adr/0044-sandbox-egress-proxy.md)); a connection's tools only for the thread | PLATFORM.md §4.7 | Widening an agent happens in the inbox for hosts and on the agent page for everything else |
| F8 | **No company-level defaults.** No company switch for a tracker's tools, no default policies for new agents, and an OPERATOR may give their own agent `WRITE` on every repository (Q19, R33) | ADR-0046 consequences; threat model Q19, R33 | Every strict setting is repeated per agent; the token's scope at the source is the only limit |
| F9 | **The connection page shows what the kind can do, not what this connection is for.** Polling (on by default), accounts, triggers and tools appear on a GitHub connection whose only job is to hold repositories | `connection-detail.html`, `connection-kinds.ts` | A repositories-only connection reads as a half-configured tracker. (A connection without enabled triggers reveals no token: `Poller` skips it) |
| F10 | **A pull request an agent opened is followed for its state only, and a trigger on it splits the work.** The watch reads the state; reviews, review comments and checks reach no thread; a tracker's trigger on pull requests makes the pull request a work item of its own, with a branch of its own | `ChangeRequestWatch`, `ChangeRequestStore`, `SubjectSync.linkOrCreate`, CONVENTIONS §14.8 (the branch rule); `GitHubClient` (no reviews, no checks) | With Jira as the tracker, "fix what the reviewer asked" does not exist (2.2). With GitHub for both, it exists only on a second work item whose agent cannot push to the pull request (2.1) |

Not findings: the rule of ADR-0046 (one order, enforced on the server, a denial no grant lifts), MCP connections as such (2.4), the audit trail, thread grants being per thread by default, and the `GIT` kind for hosts without an API.

## 4. The design

One principle: **each setting lives with its subject, in one place.** What Tinfirm may do on a system lives on the connection. What an agent may do there, and who it is there, lives on the agent. When a job happens and where it works lives on the trigger. A one-off widening lives on the decision. Every other page shows the effective result read-only, with a link to the place that sets it. The rule of ADR-0046, the tables and the audit actions stay; the changes are to what a connection is, to where repositories and scopes come from, to where the per-agent settings are edited, and to what happens to a pull request an agent opened.

### 4.1 Three types of connection, each set up on its own

A connection is one of three types, and the type says what the connection holds and what Tinfirm does with it. The owner decided this on 2026-10-08; §5 records the alternative, one GitHub connection with two switchable uses.

| Type | Systems | Holds | Tinfirm does with it |
|---|---|---|---|
| **Tracker** | Jira Data Center; GitHub (issues and pull requests as subjects); GitLab (issues and merge requests); later Jira Cloud | The base URL, the token, polling, the agents' accounts, the triggers, the fixed tools (`get`, `search`, `label`) | Polls the subjects of its triggers, raises events, writes comments, labels and transitions back, executes the agents' tool calls |
| **Repositories** | GitHub, GitLab, any git host (`GIT`) | The host, the API root where the host has one, the git token, the repositories | Clones and pushes, opens change requests, follows the pull requests it opened (4.5) |
| **MCP server** | A remote MCP server | The endpoint, the optional token and its header, the tool catalogue with the company's switches | Executes the agents' tool calls ([ADR-0040](../adr/0040-mcp-tool-connections.md)), unchanged |
| Schedules | built in | The schedule triggers | Fires them; one per company, never set up |

A tracker and a repositories connection to the same host share nothing but the host. Each has its own token, so the git token holds Contents and Pull requests and nothing on issues, and the tracker's token holds Issues and Pull requests and nothing on code: least privilege by construction, and R27 narrowed to a token that can neither comment nor close. A company that does not care picks the same secret twice. A GitHub tracker keeps pull requests as subjects, for a reviewer's trigger on "a pull request is opened"; the pull requests Tinfirm itself opened are the repositories connection's to follow (4.5). The agents' accounts stay on the tracker: assignment, mentions and the write-back happen there. A reply in a review thread of a pull request Tinfirm opened is written with the agent's account token on a tracker of the same host when it has one, else with the repositories connection's token and the agent's name in front.

Data: `connection.type` (`TRACKER`, `REPOSITORIES`, `MCP`, `SCHEDULE`) beside `connection.kind`, which stays the system (`JIRA_DC`, `GITHUB`, `GITLAB`, `GIT`, `MCP`, `SCHEDULE`), with a check of which systems each type takes (`JIRA_DC` only a tracker, `GIT` only repositories); `poll_enabled`, accounts and triggers only on a tracker, repositories only on a repositories connection, `connection_tool` only on an MCP server. The migration `V025` splits every `GITHUB` and `GITLAB` connection that has repositories into a tracker and a repositories connection ("GitHub acme" and "GitHub acme repositories") with the same secret, moving the repository rows and, with them, the agents' repository access and the triggers' workspaces, and turns every `GIT` connection into a repositories connection as it is. API: `Connection.type` and `ConnectionCreateRequest.type`; `ConnectionKind` keeps the system. The tool handle of a tracker is its name, as today; a repositories connection has no tools and no handle. The connections list groups by type (Trackers, Repositories, MCP servers, Schedules), "Connect" asks which type first, and each type has its own dialog with only its fields. Connecting a GitHub or GitLab tracker ends with "Connect its repositories too", prefilled with the host and offering the same secret, so the common case is one dialog more and nothing typed twice.

### 4.2 Repositories and scopes come from the token

- **Discovery.** `GET /connections/{id}/repositories/available?query=` (OWNER, ADMIN, a repositories connection) lists the repositories the git token sees that are not added yet (GitHub `GET /user/repos`, for an App later `GET /installation/repositories`; GitLab `GET /projects?membership=true`; the first 100 by name, narrowed by the query), revealed with purpose `connection-discover:{id}` and never stored. "Add repositories" picks from it and keeps the URL field for `GIT` and for a repository the list does not show; the name defaults to the repository's own, the default branch to the host's.
- **A trigger's scope is a repository where the company has one.** On a GitHub or GitLab tracker the editor offers the repositories of the company's repositories connections on the tracker's host (`github.com` for `api.github.com`; a GitLab instance by its origin) first, and keeps free text for a repository that is watched but not cloned (issues only) and for a GitLab group. The stored `scope` stays a string; the server records `connection_trigger.scope_repository_id` when the scope names such a repository (host and path compared ignoring case, `ON DELETE SET NULL`), and links it when the repository is added later, so the trigger knows its repository without a second field people edit.
- **The workspace defaults to the scope.** A third workspace mode, `SCOPE`, is the default for a GitHub or GitLab trigger with a repository scope: the job gets the scope's repository when the agent may access it, read-only where it may only read, and nothing when it may not; `ALL` and `CHOSEN` stay as they are (`connection_trigger.workspace_mode`, `workspace` kept for `CHOSEN`; `Workspaces.of` resolves it). The editor's sentence ends with the effective workspace: "works in `shop` (clone and push)", or "works in no repository: the agent has no access to `acme/shop`", as it already says when an `ASSIGNED` trigger's agent has no account. A Jira trigger keeps `ALL` and `CHOSEN`: Jira issues name no repository.

### 4.3 The agent page holds everything about one agent on one connection

The Access tab becomes one card per connection, shaped by the connection's type: a **tracker** card holds the agent's identity there (the account name and its token: today's accounts) and its tools (the level and the per-tool modes, as today); a **repositories** card holds its repositories (each None, Clone, or Clone and push, as the groups show today) and what opening a change request comes to; an **MCP server** card holds its tools. A tracker's and an MCP server's page show which agents use it as one line, "Used by", of linked pills, each with what the agent has there (its identity and level on a tracker, how many tools on an MCP server), not as a table of its own (the owner's call): the setting lives on the agent's page, one click away. A repositories connection's page shows the agents per repository instead, in a column of its Repositories table with the agents that may clone and the agents that may push each one, and nowhere else on that page: one place for that information (the owner's call).

Rules that change with it:

- **An account's token is optional on every kind**, Jira included: without one, the connection's account writes with the agent's name in front, as on GitHub and GitLab (PLATFORM.md §4.2 already says so for every kind; `ConnectionService` requires it on Jira). The name alone serves `ASSIGNED`, `MENTIONED` and loop prevention.
- **An agent's tool calls run with its own token when it has one**, else the connection's (`ToolCalls` and `ToolApprovals` ask `accountToken` first; the purposes stay). What the agent reads on Jira is then what its account may read: the source's own permissions become the per-agent limit that Q19 asks for, for the companies that give agents accounts, with no new mechanism in Tinfirm.
- **Who sets the identity: OWNER and ADMIN**, as today. An account name claims that login's comments as the agent's and keeps them from being imported, which an OPERATOR should not be able to do to a person's login. On an OPERATOR's own agent the identity part of the card is read-only, with one line that says who sets it.
- **API.** `PUT /agents/{agentId}/accounts` replaces the agent's accounts on every tracker (`[{connectionId, accountName, secretId}]`, OWNER and ADMIN, audited as `connection.accounts.changed` per connection as today) and replaces `PUT /connections/{id}/accounts`: one write path per subject. `Agent.tools[]` gains `account` (the name and whether it has a token) so the card reads from one object. The rows stay in `connection_account`; the agent module calls down through a port of the connection module, as its cascades do (CONVENTIONS §14.6, module boundary).

### 4.4 One vocabulary, and the policies shown where they apply

- The labels say what the level does: repositories **None / Clone / Clone and push**, a tracker's tools **None / Reads / Reads and writes**; an MCP server's row has no level control, and `AgentToolAccessInput.access` becomes optional, ignored for `MCP`. Whether the API renames the tools' enum (`ToolLevel` beside `AccessLevel`) is open (§7, row 8); the web labels do not depend on it.
- The Access tab shows, in each card, the policy that applies and what it comes to: under the repositories "opening a pull request: at once" or "asks a person" (the agent's `changeRequest`), under a tracker's writes "each call waits for approval" (its `sourceWrite`, as the hint says today), each linked to the Permissions tab. The policies stay agent-wide where they are; a per-repository or per-connection exception is one later step behind the same fields, as the per-tool entries are the exceptions to a level.

### 4.5 The pull requests Tinfirm opens belong to their work item

The rule: **a pull request Tinfirm opened belongs to the work item it was opened for**, on whatever tracker that work item lives, and the repositories connection that opened it follows it.

- **What is followed.** The watch (`ChangeRequestWatch`, extended) reads, with the git token, what it reads today plus the reviews (approved, changes requested, with their body), the review comments and their threads, the top-level comments, the head commit and, later, the check results, for every open pull request Tinfirm opened, at the watch's cadence (`run_change_request`: due at once after a push, else after the interval). Merged and closed pull requests are read no more.
- **Where it goes.** What is read goes through the event detector against a snapshot kept on the artifact, and each event continues the thread that opened the pull request, on the same branch. The review and its comments are imported as `EXTERNAL` comments of the work item ("Ana on GitHub, acme/shop#57, changes requested"), the turn's request quotes them, and the agent's replies go back into the review thread; `job.thread_ref` gains the artifact it is bound to, so a thread can be bound to a review thread of a pull request on another connection than its work item's.
- **No second work item.** A tracker's poll leaves such pull requests to the follow-up: a subject whose repository and number match a `CHANGE_REQUEST` artifact of the company never becomes a work item, and a GitHub tracker's triggers on pull requests fire on the owning work item instead, so a reviewer's thread lands beside the developer's, on one work item and one branch.
- **What continues the thread is the agent's policy**, "Pull requests it opens" on its Permissions tab beside `changeRequest` (the owner's call: it is how the agent works, and it holds for every thread of the agent, from a trigger, a conversation or a mention; neither the trigger nor the connection carries it), with a company default for new agents (4.7): a review that requests changes and a comment that mentions the agent, on by default; every review comment, off; failing checks, off and later, once checks are read. A plain comment is read at the next turn, as on a flat source. A merged pull request ends the follow-up with a note on the work item (an `ON_MERGED` trigger action, `STATUS_TO Done` on Jira, is a natural later step); a closed one is noted and no longer followed.
- **Cost and audit.** One request per followed pull request per interval, plus one per new review; `secret.accessed` with purpose `change-request-follow:<repositoryId>` once per pass and repository; `source.written` for each reply. No new trust boundary: the same token calls the same host.

### 4.6 Approve for the agent, from the inbox

An `ACCESS` decision for a connection's tools takes the scope a host's has (ADR-0044): `JOB` (the default) for this thread, or `AGENT` for good, the latter offered to whoever may change the agent's access (OWNER, ADMIN; an OPERATOR for their own agent) and audited as `agent.access.changed`. For the agent it sets the tracker's level to `READ`, or allows every served tool of the MCP server the agent is not denied, exactly what the thread would have got. A repository request needs no `AGENT` scope: the agent may already read every repository it can request (PLATFORM.md §7.2), and the thread's workspace is the only thing it lacks.

### 4.7 Company defaults

- A company switch for a tracker's tools, stored rows in the fixed catalogue with a `DENY`, as ADR-0046 names: "no agent transitions issues" as one setting when the writes come.
- Default policies for new agents (`company.agent_policy_defaults`, the six policies), applied on a create that names none; the Settings page edits them.
- Q19 (R33): whether an OPERATOR may give their own agent `WRITE` on a repository or a tracker. The design does not depend on the answer; §7 gives the options.

### 4.8 The GitHub App, shaped now and built later

A repositories connection's credential on GitHub becomes one of two: a token (today), or a GitHub App installation (the app id, its private key as a vault secret, the installation id; one App per installation of Tinfirm through the manifest flow, [ADR-0007](../adr/0007-credentials-stay-in-deployment.md)); a GitHub tracker can take the same installation. With an App: the available repositories are the installation's; git, change requests and the follow-up use installation tokens of one hour, scoped to the job's repositories and without the Workflows permission (closes R27, 7.8 and Q11); the write-back and the follow-up's replies write as the App's bot unless the agent has an account token; a webhook is a later poll hint. The types of 4.1 and the discovery of 4.2 are the same for both credentials, which is why they come first and the App after them. It does nothing for Jira or GitLab, so it is not the answer to 2.2 or 2.3.

### 4.9 What the pages show

| Page | Shows |
|---|---|
| Connections | The connections grouped by type (Trackers, Repositories, MCP servers, Schedules), each with its system, its tokens, the agents on it, and what is missing ("no trigger yet", "no repository yet", "the token was never tested"); "Connect" asks which type; a setup checklist for a company without a connection: store a token, connect a tracker, connect repositories, hire an agent, write a trigger, each linked, each ticked from the data |
| A tracker's page | Settings (the system, the token, polling, the test, and "Used by": the agents, linked, each with its identity and its level there); Triggers; Tools (the fixed catalogue; the company's switches when they come) |
| A repositories connection's page | Settings (the host, the git token, the follow-up defaults, the test); Repositories, each with the agents that may clone or push it, the one place for that; the pull requests Tinfirm opened and follows, each with its work item, its state and what its thread does now |
| An MCP server's page | As today |
| The agent page › Access | One card per connection, shaped by its type: identity and tools on a tracker, repositories and the pull request policy on a repositories connection, tools on an MCP server; the effective state and the policy that applies, each linked to where it is set; the Network access tab stays as it is |
| The trigger editor | The scope picked from the repositories on the tracker's host or typed; the sentence ends with the effective workspace and, when it has a writable repository on a code host, with what of the pull request continues the thread, from the agent's policy |
| The agent page › Permissions | Beside "What needs approval", the group "Pull requests it opens": which events of a pull request the agent opened continue its thread |
| The work item | The review of a pull request the thread opened, imported into the thread as the reviewer's comment "on GitHub", the agent's next turn under it, and the pull request among the artifacts with its state and its open review threads |
| The inbox | An access request for a connection's tools with "for this job" and "for this agent", as a host's has |

## 5. Alternatives considered

- **One connection per system with switchable uses**: a GitHub connection used for issues, for repositories, or both, with an optional second token for git. Fewer connections, but one page for two roles, one token unless a second is added, and a card on the agent page whose shape depends on the switches. The owner chose the three types on 2026-10-08: one shape per type on every page, least privilege by construction, the GitHub App slotting into the repositories connection, at the price of two connections for a GitHub used for both, which the "Connect its repositories too" step softens.
- **One permissions matrix page** (agents × repositories, connections and tools), as the single place for all of them. Not as the primary: a matrix hides the per-tool modes, the policies and the thread grants, and it duplicates every control the agent page has; it is a view, not a home. The read-only lists on the connection page give the view from the system's side without a second set of controls. It can be added as a view later if people ask for it.
- **Everything on the connection page** (an agents' access table per connection). It mirrors the agent page's controls on a second page: one more place for permissions, the problem this review starts from.
- **Move the accounts and keep `PUT /connections/{id}/accounts` too.** Two write paths for one row; one path per subject.
- **A second work item for the pull request, linked to the first** (a `parent_work_item_id`, the branch inherited). Two work items and two conversations for one piece of work, against the principle that the issue is the place of record; the follow-up keeps one.
- **The pull request follow-up as triggers on the GitHub tracker** (subject pull requests, author the agent). It needs a GitHub tracker where Jira is the tracker, splits the work item today, and ties what is Tinfirm's own output to the subject poll's scope and limits. The follow-up reads only what Tinfirm opened, with the git token, and needs no tracker.
- **The GitHub App first.** It fixes the GitHub cases best, but not Jira with GitHub (2.2) or GitLab (2.3), and it needs the types and the discovery either way.
- **Documentation only**, a walkthrough per scenario in the connections guide. Cheap and worth doing now (package 0), but it leaves the three names for one repository and the split pages as they are.

## 6. Build order, and the setup before and after

Each package lands with its tests and documentation, like the restart's (the [restart review](2026-10-05-restart.md) gets a row per package).

| # | Package | Contents | Touches |
|---|---|---|---|
| 0 | **Walkthroughs** | §2.1 and §2.2 as numbered walkthroughs in the connections guide, with the token permissions per type | docs |
| 1 | **Three connection types** (4.1, 4.9 in part) | `V025`: `connection.type`, the checks per type, the split of the GitHub and GitLab connections with repositories; `Connection.type`, `ConnectionCreateRequest.type`; the three dialogs and "Connect its repositories too"; the list by type; the pages by type; the guide's token permissions per type; the threat model (R27, A10, A15, 8.10) | server, web, api, docs |
| 2 | **Discovery, scope as a repository, workspace from the scope** (4.2) | `available` on GitHub and GitLab (`connection-discover:{id}`); the repository dialog's picker; `scope_repository_id` across connections and its upkeep; `workspace_mode` with `SCOPE`; `Workspaces.of`; the editor's picker and effective workspace; `TriggerInput.workspaceMode` | server, web, api, docs |
| 3 | **The agent's card per connection** (4.3, 4.4) | `PUT /agents/{agentId}/accounts` and the port; the Jira token optional; tool calls with the account's token; `Agent.tools[].account`; the Access tab's cards by type with the effective policies; the connection page's read-only lists and the repositories' agents column; the labels; no level on an MCP row; the guides | server, web, api, docs |
| 4 | **The pull request follow-up** (4.5) | The watch extended to reviews, review comments, comments and the head commit, with the detector on its reads and a snapshot on the artifact; the GitHub and GitLab calls for reviews; `job.thread_ref` with the artifact; the tracker's poll leaving followed pull requests and its triggers firing on the owning work item; the replies into review threads with the account's or the git token; the agent's pull request policy and its company default; the note on merge and close; the work item page and the repositories page's followed list; `change-request-follow`; the guides | server, web, api, docs |
| 5 | **For the agent, and company defaults** (4.6, 4.7) | the `AGENT` scope on a tools request; the tracker catalogue's company switch; `agent_policy_defaults` and the Settings page; Q19 as decided | server, web, api, docs |
| 6 | **The GitHub App** (4.8) | its own design and ADR | — |

What a company does after packages 1 to 4:

| Scenario | Today | After | Pages |
|---|---|---|---|
| 2.1 GitHub for both | 7 steps, the repository typed three times, the agent page twice, the review on a second work item | Store a token (or two); connect the GitHub tracker, then its repositories with the same secret and pick `shop`; hire the agent (the tracker card: identity, tools reads; the repositories card: `shop` clone and push); add the trigger, its scope picked, its workspace the scope, the follow-up on; the review continues the developer's thread | Secrets, Connections, Agents |
| 2.2 Jira and GitHub | 7 steps, a PAT per agent, a tracker that is not one, no review back | Store two tokens; connect Jira; connect GitHub repositories and pick `shop`; hire the agent (the Jira card: identity, tools reads; the repositories card: `shop` clone and push); add the Jira trigger with `shop` chosen; the reviewer's "changes requested" continues the Jira thread on the same branch | Secrets, Connections, Agents |
| 2.4 Jira and an MCP server | coherent | the same, without a level control on the MCP row | — |

Package 1 alone gives 2.2 its shape; 1 and 2 remove the three names of 2.1; 3 removes the split per-agent settings of both; 4 closes the loop at the pull request.

## 7. Open points for the owner

| # | Question | Recommendation, or the decision |
|---|---|---|
| 1 | One connection per system with switchable uses, or three types set up separately? | **Decided (2026-10-08): three types, a tracker, repositories and an MCP server, each set up on its own.** |
| 2 | A second token for git on the same connection, or its own connection? | **Decided (2026-10-08) with 1: the repositories connection holds the git token; the same secret may be picked twice.** |
| 3 | Discovery of repositories from the token, on GitHub and GitLab, with the URL field kept? | Yes. `GIT` stays by URL. |
| 4 | A trigger's scope linked to a repository of a repositories connection on the same host, and the workspace defaulting to it? | Yes; free text stays for issues-only repositories and GitLab groups; Jira keeps all or chosen. |
| 5 | Accounts edited on the agent page, listed read-only on the tracker's page, `PUT /agents/{agentId}/accounts` in place of `PUT /connections/{id}/accounts`? | Yes. OWNER and ADMIN set them, as today. |
| 6 | The Jira account's token optional? | Yes; the name alone serves assignment and mentions, as on GitHub. |
| 7 | Tool calls with the agent's own token when it has one? | Yes: the agent reads as itself, and the source's permissions bound it. The poll keeps the connection's token. |
| 8 | Rename the tools' level in the API (`ToolLevel`), or only the labels? | The labels now (clone / push, reads / writes); the enum only if the spec should say it, before the release. |
| 9 | Move `changeRequest` and `sourceWrite` to the Access tab, or show their effect there? | Show them there, linked; a per-repository or per-connection exception is a later step behind the same fields. |
| 10 | Approve a connection's tools for the agent from the inbox (scope `AGENT`), as a host? | Yes, in package 5, for whoever may change the agent. |
| 11 | Q19: may an OPERATOR give their own agent `WRITE` on a repository or a tracker? | Keep the one rule until a company asks for another; the alternative is `MANAGE_AGENTS` for `WRITE`, one check in `AgentService`. The design does not depend on it. |
| 12 | Default policies per company, and the company switch for a tracker's tools? | Package 5; both are one table and one page. |
| 13 | The GitHub App after packages 1 to 4? | Yes, with its own design when wanted; the installation serves the repositories connection and the GitHub tracker alike. |
| 14 | A permissions matrix page? | No; the read-only lists on the connection page instead, a view later if asked for. |
| 15 | Which events of a followed pull request continue the thread, and where is that set? | On the agent, as its policy "Pull requests it opens" beside `changeRequest` (the owner's call), with a company default for new agents: changes requested and a mention on by default; every review comment off; checks later, off. |
| 16 | A reply in a review thread of a pull request Tinfirm opened: with the agent's account token on a tracker of the same host, else the git token with the agent's name in front? | Yes, in that order; with the App, the bot. |
| 17 | Read check results in package 4, or later? | Later: it needs the Checks API, a rule for noise (one event per head commit when the combined conclusion turns failed) and GitLab's pipelines; the channel is the same. |
| 18 | Migrate an existing instance's GitHub connections by splitting them in `V025`? | Yes: the migration is mechanical (two rows, the repositories moved, the access and workspaces following), and nothing is lost. |

## 8. What changes where

Everything the packages touch, by path; CONVENTIONS §2 names each path's owner:

| Path | Change | Package |
|---|---|---|
| `docs/operations/connections.md` | the two walkthroughs; a section per type with its token permissions; discovery; accounts on the agent page; the pull request follow-up | 0, 1, 2, 3, 4 |
| `api/openapi.yaml` | `Connection.type`, `ConnectionCreateRequest.type`; `listAvailableRepositories`; `TriggerInput.workspaceMode`, `Trigger.scopeRepositoryId`; `AgentPolicies.pullRequests` (the events that continue the thread) and its default in `Company.agentPolicyDefaults`; `Artifact` with the review state and the open threads; `replaceAgentAccounts`, `Agent.tools[].account`, `AgentToolAccessInput.access` optional; the `AGENT` scope on a tools decision; `Company.agentPolicyDefaults` and the same on `CompanyUpdateRequest` | 1, 2, 3, 4, 5 |
| `server/.../db/migration/V025__…` and on | `connection.type` and the split; `scope_repository_id`, `workspace_mode`; the follow-up's snapshot, `agent.policy_pull_requests`, `job.thread_ref` with the artifact; the tracker catalogue rows; `agent_policy_defaults` | 1, 2, 4, 5 |
| `server/.../connection` | `ConnectionKind` and a `ConnectionType` (what each type takes), `ConnectionService` (the types, the Jira token optional), `RepositoryService` and the clients (`available`, reviews), `TriggerService` and `TriggerValidator` (the scope's repository, `SCOPE`), a port for the agent's accounts | 1, 2, 3, 4 |
| `server/.../agent` | `AgentsController` and `AgentService` (`replaceAccounts`, the account in the view), `AgentToolAccessInput.access` optional for `MCP`; `AgentPolicies.pullRequests` | 3, 4 |
| `server/.../sync` | `Workspaces.of` (`SCOPE`); the poll leaving followed pull requests and firing their triggers on the owning work item; the detector on the follow-up's reads; the replies' write-back | 2, 4 |
| `server/.../run` | `ChangeRequestWatch` extended, `ChangeRequestStore` with the snapshot; `CodeHosts` (reviews, replies); `ToolCalls` and `ToolApprovals` (the account's token first); `DecisionService` for the `AGENT` scope on a tools request | 3, 4, 5 |
| `server/.../work` | the thread's binding to an artifact's review thread; `EXTERNAL` comments from a follow-up; the notes on merge and close | 4 |
| `web/src/app/pages/company/connections/*` | the type choice and the three dialogs, the list by type, the pages by type, the checklist, the repository picker, the trigger editor's scope picker and effective workspace, the read-only accounts, the repositories' agents column, the followed pull requests | 1, 2, 3, 4 |
| `web/src/app/pages/company/agents/*` | the Access tab's cards by type (identity, repositories, tools), the labels, the policies' effect shown; the Permissions tab's group "Pull requests it opens" | 3, 4 |
| `web/src/app/pages/company/work/*` | the review as an imported comment in the thread; the artifact's state and open threads | 4 |
| `web/src/app/shared/decision-card.ts` | "for this agent" on a tools request | 5 |
| `docs/PLATFORM.md` §4.2, §4.3, §4.5, §4.6, §4.9, §9 | the three types; where repositories come from; the scope's repository and the `SCOPE` workspace; a thread bound to a review thread of a pull request it opened; the follow-up; the pages | 1, 2, 3, 4 |
| `docs/CONVENTIONS.md` §14.1, §14.5, §14.6, §14.7, §14.8, §14.9, §14.10 | the permissions of the new paths; the types and their checks; accounts under the agent; the account's token on tool calls; the follow-up's reads, events and writes; the `AGENT` scope | 1, 2, 3, 4, 5 |
| `docs/threat-model.md` | A10, A15 and R27 split by type; 8.10 per type; 1.27 and R33 with the account's token on tool calls; the follow-up as a use of TB8; Q19 as decided | 1, 3, 4, 5 |
| `docs/adr/0050-connection-types.md`, `docs/adr/README.md` | the decision, from §7 | 1 |
