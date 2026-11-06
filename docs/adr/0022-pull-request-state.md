# ADR-0022: Pull request state on the task, and watching it

- Status: Accepted (built for a conversation's change requests, GitHub's and GitLab's, each watched until it is merged, the conversation ends or its binding on the code host goes)
- Date: 2026-10-01
- Sources: CONVENTIONS §8, §14.4, §14.6; [ADR-0015](0015-multi-repository-tasks.md)

## Context

- A task shows a link to its pull request in each repository. Members had to open the link to learn whether the pull request is still a draft, waits for review, was merged or was closed.
- The server opens pull requests through the GitHub REST API with the component's token (CONVENTIONS §14.4). GitHub webhooks need a public endpoint and a webhook secret; they come with the GitHub App in M1 (threat model A10, TB8, 8.1).
- Each reveal of a component token is audited as `secret.accessed` (CONVENTIONS §8).
- Polling every pull request of every task costs GitHub requests and audit events for pull requests nobody looks at. Members need a way to choose the tasks whose pull requests they want to follow.

## Decision

- `task_pull_request` stores `state` (`OPEN`, `DRAFT`, `MERGED`, `CLOSED`, or NULL before it was read) and `state_checked_at`. The API returns it as `TaskRepository.pullRequestState`. The web app shows it as a badge next to the link in the task's Details card, on the PR link of board cards and list rows, and next to the links of work to review on the overview.
- The state maps from GitHub's pull request object: `merged` true or `merged_at` set is `MERGED`; `state` "closed" is `CLOSED`; an open one with `draft` true is `DRAFT`; else `OPEN`.
- Opening a pull request records the state of GitHub's answer, so a new pull request is not read again at once.
- `task.watch_pull_requests` (`Task.watchPullRequests`, off for a new task) says whether Tinfirm keeps the task's pull request states current. Whoever may update the task turns it on or off in the task's Details card; the change is audited as `task.updated`.
- The worker polls. Every 15 s it takes up to 100 due pull requests of every company from the `SECURITY DEFINER` lookup `task_pull_request_state_due` (only ids, like the lookups of V012). A pull request is due when `state_checked_at` is NULL: it has no state yet, a run pushed to it (`RunCompletion` marks it), or its task just started watching. For a watching task, a pull request that is not `MERGED` is also due once it was last read `TINFIRM_RUN_PULL_REQUEST_STATE_INTERVAL` (default `PT5M`) ago or more. `CLOSED` stays due, because a closed pull request can be reopened.
- Per company and repository, the worker stamps each due pull request's read time under its row lock, so a second worker skips it. It then reveals the token once (purpose `pull-request-state:<componentId>`), calls `GET /repos/{owner}/{repo}/pulls/{number}` outside any transaction, and records each state it read.
- A failed read is logged at WARN. The pull request keeps its last state; a watched one is read again after the interval. A repository that is not on github.com, or has no credential, is skipped the same way.

## Consequences

- Members see the state without opening GitHub. For a watched task it is at most the interval plus 15 s old.
- An unwatched task shows the state from the last event: opened, pushed to, or watched. A pull request merged or closed on GitHub without a new run keeps its old state until someone watches the task.
- Each watched pull request that is not merged costs one GitHub request per interval, about 12 an hour with the default. That is far below the 5000 an hour of a token, unless a token serves hundreds of watched pull requests.
- Each repository with watched pull requests adds one `secret.accessed` audit event per pass, about 12 an hour with the default. A larger interval, or fewer watched tasks, lowers both.
- No new trust boundary: the server already calls the GitHub API with the same tokens.

## Alternatives considered

- **GitHub webhooks.** The state would be current within seconds, with no polling and no audit volume. They need the GitHub App, its webhook secret and a public endpoint, which arrive in M1. With them, the poll should become a fallback or be removed.
- **Poll every task's pull requests.** No setting to learn, but the cost in requests and audit events grows with every open pull request, including those nobody follows.
- **Watch per member, with notifications.** Tinfirm has no notifications yet (email and Slack are planned). The watch is per task for now; per-member watching fits once notifications exist.
- **Read the state when a member opens the task.** Fresh on view, but each page view would reveal tokens and call GitHub, and a list of tasks would fan out to many calls.
- **Use conditional requests (`ETag`, `304 Not Modified`).** These do not count against the rate limit, but they need another column and do not reduce the token reveals. A follow-up can add them.
