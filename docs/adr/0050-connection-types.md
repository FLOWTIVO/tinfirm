# ADR-0050: Three types of connection: a tracker, repositories and an MCP server, each set up on its own

- Status: Superseded by [ADR-0058](0058-authority-from-the-audience.md) on 2026-10-12 (was Accepted (the owner decided on 2026-10-08))
- Date: 2026-10-08
- Sources: the [connections review](../reviews/2026-10-08-connections-review.md) (§3 F1, F9 and F10; §4.1; §5; §6 package 1; §7 rows 1, 2 and 18); PLATFORM.md §4.2, §4.3; CONVENTIONS §14.5, §14.6; [ADR-0023](0023-code-host-interface-gitlab.md), [ADR-0040](0040-mcp-tool-connections.md), [ADR-0048](0048-jira-cloud-connection.md), [ADR-0049](0049-agent-kinds-groups-and-personal-accounts.md)
- Changes: PLATFORM.md §4.2 and §4.3, where one `GITHUB` or `GITLAB` connection was the tracker and the code host at once, with one token for both.
- Amended by: [ADR-0052](0052-shared-accounts.md), under which a repositories connection takes people's and shared accounts for the interactive agents' workspaces

## Context

- A connection had a kind only: `JIRA_DC`, `GITHUB`, `GITLAB`, `GIT`, `MCP` or `SCHEDULE`. A `GITHUB` or `GITLAB` connection was the tracker (polled for issues and pull or merge requests, with events, the write-back, the agents' accounts, the triggers and the fixed tools) and the code host (its repositories cloned and pushed, change requests opened in them) at once, with one token for both (the review's F1).
- One token for both means that the token the runner host gets for git can also comment on, label and close issues (threat model R27). A company that wanted a narrower git token had only `GIT`, which opens no change request.
- A company with Jira as its tracker and GitHub for its code (the review's §2.2) had to create a GitHub connection to get pull requests, and that connection was a tracker: polling on, Accounts, Triggers and Tools on its page, and a guide that asked for Issues permissions it never used (F9).
- The need the review's §2.2 describes: with Jira as the tracker, the agent must push to GitHub, open the pull request and follow it through its review. Today a pull request is followed for its state only; a reviewer's "changes requested" never reaches the agent, and a GitHub trigger on pull requests makes the pull request a second work item on another branch (F10).
- The review's first draft kept one connection per system with switchable uses (issues, repositories, or both) and an optional second token. The owner chose three types on 2026-10-08 (the review's §7, rows 1 and 2).

## Decision

**A connection has a type, what it is for, beside its kind, the system it talks to. A tracker, a repositories connection and an MCP server are each set up on their own, each with its own token.**

**Types and kinds.** The type is given on create and never changes, nor does the kind.

| Type | Kinds | Holds | Tinfirm does with it |
|---|---|---|---|
| `TRACKER` | `JIRA_DC`; `JIRA_CLOUD` ([ADR-0048](0048-jira-cloud-connection.md)); `GITHUB` (issues and pull requests as subjects); `GITLAB` (issues and merge requests) | The base URL, the token, polling, the agents' accounts, the people's accounts ([ADR-0049](0049-agent-kinds-groups-and-personal-accounts.md)), the triggers, the fixed tools (`get`, `search`, `label`), the agents' tool access. No repositories | Polls the subjects of its triggers, raises events, writes comments, labels and transitions back, executes the agents' tool calls |
| `REPOSITORIES` | `GITHUB`, `GITLAB`, `GIT` (any git host) | The host (the API root on GitHub and GitLab, none for `GIT`), the git token, the repositories. Never polled; no accounts of agents or people, no triggers, no tools, no tool access | Clones and pushes its repositories during runs, opens change requests in them on GitHub and GitLab and watches their state, as before this decision |
| `MCP` | `MCP` | The endpoint, the optional token and its header, the catalogue ([ADR-0040](0040-mcp-tool-connections.md)), the people's accounts | Unchanged |
| `SCHEDULE` | `SCHEDULE` | The schedule triggers; one built-in connection per company | Unchanged |

A tracker and a repositories connection to the same host share nothing but the host. On GitHub the git token holds Contents and Pull requests and nothing on issues; the tracker's token holds Issues and Pull requests and nothing on code. On GitLab the git token holds `write_repository` for git and `api` for merge requests. A company that does not want two tokens picks the same secret for both.

**Migration.** `V025__connection_types.sql` adds `connection.type` with a check of the pairs a kind takes. Every `GITHUB` or `GITLAB` connection that had repositories is split: the connection stays the tracker and keeps its accounts, triggers, the agents' tool access and its polling; a new repositories connection named "<name> repositories", with the same secret, takes the repositories. The agents' repository access and the triggers' workspaces follow, because they reference repository ids. A `GIT` connection becomes a repositories connection; a `GITHUB` or `GITLAB` connection without repositories stays a tracker; `JIRA_DC`, `JIRA_CLOUD`, `MCP` and `SCHEDULE` take the one type their kind allows. An instance is migrated in place, not recreated (the review's §7, row 18).

**API.** `ConnectionType` (`TRACKER`, `REPOSITORIES`, `MCP`, `SCHEDULE`); `Connection.type` and `ConnectionCreateRequest.type`, both required; `ConnectionKind` keeps the system. A pair the kind does not take, repositories on a tracker, accounts (an agent's or a person's), OAuth settings or polling on a repositories connection, and tool access to one are `400 VALIDATION_FAILED`; a trigger on a repositories or MCP connection is `400 INVALID_TRIGGER`. `connection.created` and `connection.updated` record `type`. A tracker's tool handle is its name, as before; a repositories connection has no tools and no handle.

**Web.** The Connections page groups the connections by type: Trackers, Repositories, MCP servers, Schedules. **Connect** asks which type first, and each type has its own dialog with only its fields. Connecting a GitHub or GitLab tracker ends with **Connect its repositories too**, prefilled with the host, so the common case is one dialog more and nothing typed twice. A connection's page shows the sections of its type.

**Not in this decision.** The review's later packages stay planned: repositories discovered from the token (they are still added by URL), a trigger's scope linked to a repository and the `SCOPE` workspace, the agent's accounts on its own page, the "Used by" line, the pull request follow-up, company defaults and the GitHub App.

## Consequences

- Least privilege by construction. Each token holds what its type needs, and the pages ask for exactly that. R27 narrows: a company that splits gives git a token with nothing on issues, so the token on the runner host can no longer comment on, label or close an issue. The migration keeps one secret for both halves, so nothing narrows until an admin stores a git token of its own. GitLab's scopes do not separate merge requests from issues (`api` reaches both); there the narrowing is the projects the token reaches.
- One shape per type on every page. A connection whose only job is its repositories shows no polling, accounts, triggers or tools (F9). The Jira and GitHub company connects Jira as its tracker and GitHub as repositories, and nothing on the GitHub side reads as a tracker it does not use.
- A company that uses GitHub for its issues and its code has two connections where it had one. **Connect its repositories too** softens it: one more dialog, the host and the secret offered, nothing typed twice.
- A GitHub tracker keeps pull requests as subjects, for a reviewer's trigger on "a pull request is opened".
- The pull request follow-up is planned (the review's §4.5, package 4 of its §6) and builds on the repositories connection: a pull request Tinfirm opened belongs to the work item it was opened for, on whatever tracker that work item lives, and the repositories connection that opened it follows its reviews into that work item's thread with the git token. Until it is built, a pull request is followed for its state only, and its review does not reach the agent's thread.
- The split keeps what refers to a repository working: the repository keeps its id and changes connection, so the agents' access and the triggers' workspaces stay as they were. The tracker keeps its name, so its tool handles stay too. The agent page lists the repositories under the new connection's name.
- An API client that created connections without `type` must send it now. Nothing is released, so the field is required rather than defaulted, as [ADR-0046](0046-agent-tool-permissions.md) changed `tools` in place.

## Alternatives considered

- **One connection per system with switchable uses** (the review's first draft): a GitHub connection used for issues, for repositories or for both, with an optional second token for git. Fewer connections, but one page for two roles, one token unless an admin adds the second, and pages and an agent card whose shape depends on the switches. The owner chose the three types on 2026-10-08: one shape per type on every page, least privilege by construction, and a place where the GitHub App fits later.
- **Separate kinds** (`GITHUB_REPOSITORIES` and `GITLAB_REPOSITORIES` beside `GITHUB` and `GITLAB`). The same split in one field, but the kind would stop naming the system: every rule per system (the base URL, the URL check, the git user name, the clients) would be written twice, and a third use of a system would need a third set of kinds. The type says what a connection is for and the kind what it talks to; which pairs exist is one table. Not discussed in the review.
- **The GitHub App first** (the review's §4.8 and §5). Installation tokens of one hour, scoped to a job's repositories and without the Workflows permission, would close R27 on GitHub. But the App does nothing for Jira, GitLab or `GIT`, so it is not the answer to the Jira and GitHub company (the review's §2.2) or to GitLab (§2.3), and it needs the types either way: an installation is a credential of the repositories connection. It comes later, with its own design.
