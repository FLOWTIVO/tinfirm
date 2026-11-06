# ADR-0023: A code host per component, and GitLab merge requests

- Status: Accepted
- Date: 2026-10-03
- Sources: CONVENTIONS §8, §14.1, §14.2, §14.4, §15.4, §16; [ADR-0015](0015-multi-repository-tasks.md), [ADR-0022](0022-pull-request-state.md)

## Context

- Some companies host their code on a self-managed GitLab, where review happens in merge requests. Each agent can get a GitLab bot user with a personal access token (scopes `api` and `write_repository`).
- The server opens, reads and rewrites pull requests only on github.com (`GitHubClient`, `PullRequests`, `PullRequestStates`, CONVENTIONS §14.4). A repository on any other host gets no pull request, and nobody is told.
- The URL of a repository does not say which code host serves it. A self-managed GitLab runs under any host name, such as `git.acme.internal`, and so do GitHub Enterprise, Gitea and Bitbucket Data Center.
- The runner host pushes with the component's token over HTTPS. The user name that goes with the token differs: GitHub accepts `x-access-token`, GitLab expects `oauth2` with a personal access token.
- The API, the web app and the database speak of pull requests: `TaskRepository.pullRequestUrl`, `pullRequestNumber`, `PullRequestState`, `Task.watchPullRequests`, `task_pull_request`, `run_repository.pr_status`. Clients depend on these names.
- Where the server reaches only internal services, a self-managed GitLab instance is internal too, and its TLS certificate may be issued by an internal CA.

## Decision

**A code host per component**
- `component.code_host` is `GITHUB` or `GITLAB`, required. Migration `V023__component_code_host.sql` sets existing components on github.com to `GITHUB` and all others to `GITLAB`.
- The API has the schema `CodeHost` and the required field `Component.codeHost`. `ComponentCreateRequest.codeHost` and `ComponentUpdateRequest.codeHost` are optional.
  - A create without it gets `GITHUB` when the host of the URL is `github.com`. Any other host, `gitlab.com` included, must be named: else `400 VALIDATION_FAILED`.
  - An update without it keeps the code host, also when the URL changes.
- The web form has a code host select. It follows the URL (github.com: GitHub, any other host: GitLab) until the member picks one.
- `component.created` carries `codeHost` in its `details`, and `component.updated` carries it when it changes.

**One interface for change requests**
- `CodeHostClient` in `run.internal`, package-private, with the operations that pull requests need today: find the repository that a clone URL names; open a change request (head branch, base branch, title, body) and return its URL, number and state; find the open one of a head branch; read its state by number; read and update its body; rewrite the related block on top of read and update. It also names the git user name for the host.
- `CodeHosts` picks the client by the component's code host. `PullRequests`, `PullRequestStates`, `RunCompletion` and `AssignmentBuilder` use only that.
- **GitHub** keeps its behaviour: `TINFIRM_GITHUB_API_URL`, `Authorization: Bearer`, a 422 "already exists" resolved with `GET …/pulls?head={owner}:{branch}&state=open`, git user name `x-access-token`.
- **GitLab** uses the REST API v4:
  - The API base is the origin of the clone URL plus `/api/v4`, so every self-managed instance works without configuration. The project id is the URL-encoded path, with any depth of subgroups: `https://git.acme.internal/platform/backend/api.git` is `platform%2Fbackend%2Fapi`. An instance under a relative URL root (`https://acme.example/gitlab/…`) is not supported.
  - The component's token goes in the `PRIVATE-TOKEN` header.
  - Open: `POST /projects/:id/merge_requests` with `source_branch`, `target_branch`, `title`, `description` and `remove_source_branch` false. A 409 "Another open merge request already exists" is resolved like GitHub's 422: `GET /projects/:id/merge_requests?source_branch={branch}&state=all`, the open one first.
  - State: `GET /projects/:id/merge_requests/:iid`. `opened` is `OPEN`, or `DRAFT` when `draft` (or the older `work_in_progress`) is true; `merged` is `MERGED`; `closed` and `locked` are `CLOSED`.
  - Body: the `description` of the same `GET`, written with `PUT /projects/:id/merge_requests/:iid`.
  - The stored URL is the merge request's `web_url`, and the stored number is its `iid`.
  - Requests time out after `tinfirm.run.gitlab-timeout` (default `PT30S`). Like the GitHub client, the GitLab client follows no redirects.
  - A 5xx answer or a connection error is tried again, up to `tinfirm.run.gitlab-attempts` (default `3`) attempts with a pause of `tinfirm.run.gitlab-retry-backoff` (default `PT1S`) that doubles each time. A `POST` tried again after it reached GitLab gets the 409 of the merge request it opened, which resolves to that merge request.
  - The git user name is `oauth2`.
- `run.assign` carries the user name per repository in `Workspace.git.username`, which the protocol already has. Runner protocol v1 does not change.
- The body of a change request stays the same on both hosts, in Markdown: the summary, the task URL, the related block and "Opened by Tinfirm agent {name}".

**Names stay**
- The API, the database and the comments keep the pull request names. For a GitLab repository they hold merge requests: `pullRequestUrl` is the `web_url`, `pullRequestNumber` the `iid`. The OpenAPI descriptions and CONVENTIONS §14.4 say so.
- The web app labels a GitLab link `group/subgroup/project!12`, as GitLab does.

## Consequences

- A task can have repositories on both code hosts. The related block lists the change requests of both.
- Secret reveals and their purposes do not change (`run:<runId>`, `pull-request-state:<componentId>`).
- The server calls every GitLab instance that a component names, at the origin of the component's own URL, with that component's token. This is a new external service on TB8 (threat model 8.7, 8.8):
  - Owners and admins choose both the URL and the token. They can make the server send `GET`, `POST` and `PUT` requests under `/api/v4/projects/` to any HTTPS origin the server reaches. A failure reason shows at most the status and 300 characters of the message, in a `SYSTEM` comment.
  - Egress rules must allow the GitLab instance on port 443, for example `networkPolicy.egress.extra` in the Helm chart.
  - The server's JVM trust store must contain the CA of the GitLab instance. The client never turns TLS verification off.
- A component with the wrong code host gets failing calls and a `SYSTEM` comment on each failed attempt. An owner or admin changes the code host.
- Existing components on other hosts become `GITLAB`. They had no pull requests before. Now Tinfirm tries to open merge requests there, and reports a failure when the host is no GitLab.
- The state is still polled (ADR-0022). GitLab webhooks would need a public endpoint and a secret token, like GitHub webhooks.
- Reading merge request discussions is not part of this decision.
- A third code host is one more implementation of the interface and one more value of `CodeHost`, plus a migration for the check constraint.

## Alternatives considered

- **Derive the code host from the URL only:** github.com is GitHub, every other host is GitLab, or the server probes the host's API. Rejected: self-managed hosts have arbitrary names, so a guess misreads GitHub Enterprise, Gitea or Bitbucket as GitLab, and a probe sends requests, maybe with the token, to find out. An explicit field costs one select in the form. GitHub stays implied for github.com, so existing clients keep working.
- **GitLab project access tokens per job:** the server mints a short-lived token for the run's projects through the GitLab API, like the installation tokens of the planned GitHub App. Scoping and rotation would be better than a long-lived personal access token per bot user. It needs a token with more rights in the vault, and an expiry and revocation flow. It stays for later (CONVENTIONS §16).
- **Merge request names in the API:** new fields such as `mergeRequestUrl`, or names that depend on the code host. Rejected: it breaks clients for a naming difference. The descriptions say what the fields hold on GitLab.
- **A plugin interface or a module per code host.** Not needed for two implementations. A package-private interface in the run module keeps the module boundaries as they are.
- **A GitLab client library.** It would add a dependency for four endpoints. The JDK `HttpClient` serves both clients, as it did for GitHub.
