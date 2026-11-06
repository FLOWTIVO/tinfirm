# ADR-0030: GitHub Issues as a second tracker kind

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: the owner's decision of 2026-10-03 (GitHub Issues as a second tracker kind); CONVENTIONS §14.1, §15.5, §15.6, §17; [ADR-0023](0023-code-host-interface-gitlab.md), [ADR-0024](0024-jira-tracker-connector.md), [ADR-0029](0029-work-source-per-project.md)
- Amended by: [ADR-0031](0031-rule-for-new-issues.md), which adds the trigger `CREATED` and conditions on labels and type to GitHub rules, and lets no rule match a closed GitHub issue

## Context

- ADR-0024 connected one tracker, Jira Data Center: rules turn issues into tasks, a poller follows them, and a write-back sends the agents' comments, pull request links and transitions back. ADR-0029 gave each project a work source and named the next one: "a later source adds a value here, a connection kind that names it, and a connector".
- Many teams that host their code on GitHub keep their backlog in GitHub Issues. On 2026-10-03 the owner chose GitHub Issues as the second tracker kind, behind the same rules, links, inbox, waiting-on state and write-back as Jira.
- The connector was written for Jira. The poller built JQL and floored dates to the minute in the account's time zone, the write-back converted Markdown to wiki markup, made transitions and added remote links, and the rules read Jira project keys from issue keys. A second kind would have spread checks for the kind through all of them.
- GitHub Issues differs from Jira in what the connector touches:
  - the REST API (`https://api.github.com`, or `https://<host>/api/v3` on GitHub Enterprise Server) with personal access tokens, a version header, pages by `Link` header, and rate limits that answer 403 or 429;
  - issues live in repositories, `owner/repo#57`, and the list of a repository's issues also holds its pull requests;
  - an issue has no workflow status: only labels, and open or closed;
  - an issue has several assignees, a mention is `@login`, and texts are Markdown;
  - the list filters by `since` with second precision, in UTC;
  - a pull request whose body says `Closes #57` closes the issue when it is merged.

## Decision

**One interface for every kind** (D9). `IssueTracker` in `tracker.internal` holds everything that depends on the kind: who a token belongs to, the search for changed issues of the rules' scopes and where the next poll starts, reading comments, writing a comment, applying the status mapping, adding a link, the markup in both directions, a bold label, mentions, the issue's URL, and what a scope looks like. `IssueTrackers` picks the implementation by `TrackerKind`. `TrackerPoller`, `TrackerWriteBack`, `IssueRules`, `IssueComments` and `TrackerService` know nothing else about a kind. The records that cross the interface are kind-neutral (`TrackerIssue`, `TrackerComment`, `TrackerUser`, `TrackerAccount`, `TrackerResult`); the Jira client keeps its own types (`Jira.SearchPage`, `Jira.Transition`). `JiraTracker` implements the interface over the existing `JiraClient`, `JiraMarkup`, `Jql`, `PollWindow` and transitions, with Jira's behaviour unchanged; `GitHubTracker` implements it over a new `GitHubIssuesClient`.

**Kinds and sources** (D1). `TrackerKind.GITHUB` feeds the projects whose work source is `GITHUB` (display name GitHub), and its tasks have `externalSource` `GITHUB`. Everything ADR-0029 says about `JIRA` applies to `GITHUB`: a member's task in such a project is `409 PROJECT_USES_TRACKER` and `create_task` answers `REJECTED` / `PROJECT_USES_TRACKER`, both naming GitHub; the project shows a list instead of the board; the prompt says that a person creates the issue in GitHub; a rule may target only a project whose work source is its connection's (`JIRA` for `JIRA_DC`, `GITHUB` for `GITHUB`); and a project leaves `GITHUB` only while no rule targets it (`409 PROJECT_HAS_TRACKER_RULES`). The texts take the source's display name, so no sentence names one tracker where both apply.

**Connection** (D2). The kind is set at creation and never changes.
- `baseUrl` is the REST API's base: `https://api.github.com`, or `https://<host>/api/v3` for GitHub Enterprise Server. The URL rules are those of every connection, http only with `TINFIRM_TRACKER_ALLOW_HTTP`.
- The secret holds a personal access token: fine-grained with Issues read and write and Metadata read on the repositories, or classic with `repo`. Requests send `Authorization: Bearer <token>`, `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28`, with the connector's timeout, and follow no redirects.
- `POST .../test` calls `GET /user` and answers the login.
- `closeOnDone` (default false) closes the issue when its task is done. Only a GitHub connection has it: true on a `JIRA_DC` connection is `400 VALIDATION_FAILED`.
- `transitions` keeps its name, shape and column. For GitHub its three names are labels, at most 50 characters each (GitHub's limit).

**Rules** (D3). A rule names a `scope` instead of a Jira project key, in the API, the column `tracker_rule.scope` and the web app. The service checks it by kind: a Jira project key for `JIRA_DC` (as before), a repository `owner/repo` of at most 140 characters, compared ignoring case, for `GITHUB`; the database checks only that it has 1 to 140 characters without whitespace. On GitHub, `ASSIGNEE` matches when any of the issue's assignees is the agent's login, `LABEL` as in Jira, and `MENTION` when a comment created since the poll's lower bound, not by an account Tinfirm writes with, contains `@login` as a whole word, ignoring case. A GitHub connection takes no `STATUS` rule: `Rule N: GitHub issues have no status; use LABEL`.

**Agent accounts** (D4). On a GitHub connection the account name is the agent's GitHub login, and its secret is optional: without one, the connection's token writes the agent's comments, as for a Jira agent without an account.

**Polling** (D5). For each distinct scope of the enabled rules, the poller lists `GET /repos/{owner}/{repo}/issues?state=all&since=...&sort=updated&direction=asc&per_page=100&page=N` and skips the pull requests in it.
- An issue's id is GitHub's numeric `id`; its key is `owner/repo#number` with the names GitHub reports for the repository; its URL is `html_url`; its title, body (Markdown, as it is), labels, assignees (logins), `updated_at` and number of comments come from the list.
- Comments come from `GET /repos/{owner}/{repo}/issues/{number}/comments`, with `since` for a linked issue: for a new link the newest 50 of people, for a changed issue those above the last one seen, as for Jira. The author is shown by login; GitHub sends no display name there.
- The limit of 200 issues per poll is shared by the repositories: each gets an equal part of what is left, so a busy repository cannot starve the others.
- The window has second precision and no time zone. After a poll, the lower bound is the earliest over the repositories of the newest `updated_at` seen (the poll's start when none was seen, the last one handled when the limit cut the repository) minus the overlap, and it never moves back; `poll_offset` stays 0. When the limit cut a repository and that bound would not move, it moves to the earliest point a cut repository reached (minus the overlap when that still moves it, else without it), so that a bulk change of more issues than the limit within the overlap cannot hold the polling in place. Any failure keeps the window.
- A 403 or 429 with `x-ratelimit-remaining: 0` or a `retry-after`, a 429, a 5xx and no answer may work later; the poll ends and `last_poll_error` says why, with the reset time when GitHub sends one. 401, other 403s and 404 do not. A failure to read an issue's comments ends the poll the same way, so the issue is read again by the next one.
- The login of the connection's token and the agents' logins are the accounts Tinfirm writes with; their comments are never imported.

**Write-back** (D6). The outbox, its order, its retries and its attempts stay as they are.
- A comment is `POST /repos/{owner}/{repo}/issues/{number}/comments` with the Markdown as it is; Tinfirm's label is bold Markdown, `**Tinfirm:**`. It is written with the agent's token when its account has one, else the connection's, and the returned id goes to `tracker_sync.remote_comment_id`, renamed from `jira_comment_id`.
- The end-of-run comment lists the task's pull requests as it does for Jira; GitHub links the URLs. GitHub has no remote links, so `tracker_remote_link` stays Jira's.
- A status change with a label in the mapping adds it (`POST .../labels`) and removes the mapping's labels of the other statuses (`DELETE .../labels/{name}`, where a 404 is fine). `DONE` with `closeOnDone` also sends `PATCH .../issues/{number}` with `{"state": "closed", "state_reason": "completed"}`. A task that leaves `DONE` does not reopen the issue. The connection's token writes status changes.

**The pull request names the issue** (D7). For a task from a GitHub issue, the change request's body gets one line after the task URL and before the related block: `Closes #57` in a GitHub pull request of the issue's own repository (owner and name compared ignoring case), so GitHub links the pull request and closes the issue when it is merged; `Refs owner/repo#57` in another GitHub repository; `Issue: <url>` in a GitLab merge request. `TaskView.external` gives the run module the issue. Tasks from Jira keep the body they had.

**Task keys** (D8). ADR-0029 stands: `owner/repo#57` does not have the form of a task key, so a task from a GitHub issue takes the project's next key, e.g. `WEB-61`, with the branch `agent/WEB-61` and the title `WEB-61: …`. `task.external_key` and `tracker_issue.issue_key` widen to 200 characters, so `owner/repo#number` always fits.

**Data model: migration `V029__github_issues.sql`** (D10). It changes constraints, one column and names, and no row:

| Table | Change |
|---|---|
| `project` | `project_work_source_ck` admits `GITHUB` |
| `tracker_connection` | `tracker_connection_kind_ck` admits `GITHUB`; new column `close_on_done boolean NOT NULL DEFAULT false` |
| `tracker_rule` | `jira_project_key` becomes `scope varchar(140)`; `tracker_rule_project_key_ck` gives way to `tracker_rule_scope_ck` (1 to 140 characters, no whitespace) |
| `tracker_sync` | `jira_comment_id` becomes `remote_comment_id` |
| `task` | `task_external_source_ck` admits `GITHUB`; `external_key` is `varchar(200)`, checked for 1 to 200 characters |
| `tracker_issue` | `issue_key` is `varchar(200)`, checked for 1 to 200 characters |

**API and web** (D11, D12). `TrackerKind`, `WorkSource` and `Task.externalSource` gain `GITHUB`; `Tracker`, `TrackerCreateRequest` and `TrackerUpdateRequest` gain `closeOnDone`; `TrackerRule.scope` and `TrackerRuleInput.scope` replace `jiraProjectKey`; `TrackerAgentAccountInput.secretId` is optional (still required by a Jira connection); the descriptions say what each field means per kind. The Trackers page connects "a tracker" and asks for its kind first, labels the URL, the token, the status mapping and the accounts by kind, offers the close on done for GitHub, and names rules' scopes "Jira project" or "Repository"; the projects offer GitHub as a work source.

## Consequences

- A company can connect Jira and GitHub at the same time; each project takes its work from one of them, or from Tinfirm. Asks, answers, the inbox and the waiting-on state work the same for every task (ADR-0029).
- A third kind is one more implementation of `IssueTracker`, one value of `TrackerKind` and `WorkSource`, and a migration for the check constraints; the poller, the write-back and the rules do not change.
- Jira behaves as before. What a client sees change: the rule's field is `scope` in the API and in the audit details of `tracker.rules.changed`, `tracker.created` and `tracker.updated` record `closeOnDone`, and the Trackers page says "Connect a tracker".
- The poll costs one `GET /user`, at least one list request per repository, and one or two comment requests per changed issue with comments, within GitHub's rate limit of 5 000 requests an hour per token. Pull requests count against the poll's limit, because GitHub lists them among the issues.
- Comments of people are imported under their login, not their name.
- An issue closed by GitHub when a pull request with `Closes #57` is merged changes on GitHub only; the task follows its own status. Reopening a task in Tinfirm does not reopen the issue.
- The bulk change rule can skip a change that GitHub lists late, by more than the overlap, in another repository while one repository's changes exceed the limit; more issues of one repository than its share of the limit changed within the same second still hold the polling.
- The server calls the GitHub API that a connection names with the connection's token and the agents' tokens: a new use of TB8 (threat model A15, 8.9 to 8.11). The tokens can do more than the connector does; the operator guide asks for fine-grained tokens limited to the repositories and to Issues and Metadata.

## Alternatives considered

- **A GitHub App first.** An app installed on an organisation gets short-lived installation tokens per repository, acts as a bot, and can receive webhooks. It needs an app registration, its private key in the vault, a JWT exchange and an installation flow in the web app, and the agents' own accounts would still need tokens. Personal access tokens are what an owner can set up today, and the connection's settings stay those of a Jira connection. The GitHub App planned for change requests can later serve the tracker too, as one more way to get a token.
- **Webhooks.** GitHub can push issue and comment events at once, but a webhook needs an endpoint that GitHub reaches and a shared secret per repository or app, which many self-hosted installs do not expose. As in ADR-0024, polling stays the source of truth; a webhook can later be a hint that starts the next poll at once.
- **A code-host-side issue reader in the run module.** The run module already calls GitHub with each component's token (ADR-0023), and could read the issues of the task's repositories. Rejected: rules, links, the outbox and loop prevention belong to the tracker module, an issue's repository need not be a component, and the tracker module must not depend on the run module. A small client in the tracker module costs less than that coupling.
- **Issue keys as task keys.** `owner/repo#57` has characters a branch name cannot carry everywhere, and repository names collide across owners. ADR-0029's key rule stays, and the pull request names the issue instead (D7).
- **Status labels per status without removing the others.** Simpler, but an issue would carry `in progress` and `in review` at once after a run. Removing the mapping's other labels keeps one status label on the issue.
