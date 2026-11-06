# Connect GitHub Issues

This guide is for owners and admins of a company who want work to come from the issues of their GitHub repositories, on github.com or on GitHub Enterprise Server: an issue that matches a rule becomes a task, an agent works on it, and its comments, status labels and pull requests go back to the issue.

The binding rules are in [CONVENTIONS §17](../CONVENTIONS.md#17-trackers). The decisions are [ADR-0030](../../../adr/0030-github-issues-tracker.md) for GitHub Issues, [ADR-0024](../../../adr/0024-jira-tracker-connector.md) for the tracker connector it shares with Jira, [ADR-0031](../../../adr/0031-rule-for-new-issues.md) for new issues and rule conditions, and [ADR-0029](../../../adr/0029-work-source-per-project.md) for the work source of a project. To connect Jira Data Center instead, see [jira.md](jira.md).

## 1. How it works

| Step | What happens |
|---|---|
| Poll | Every poll interval (default 60 s), Tinfirm lists the issues of the repositories your rules name that changed since the last poll, with the connection's token. |
| Match | An issue without a task that matches a rule becomes a TODO task of the rule's Tinfirm project, assigned to the rule's agent. The title is the title, the issue's body is the brief, and the comments of people come along. The agent starts at once. |
| Follow | When the issue changes, the task's title and description follow it. New comments of people go to the task and wake the agent; a reply answers all of the agent's open questions at once, since it cannot name one. A reply that mentions another agent asks that agent instead (section 8). |
| Write back | The agent's comments go to the issue as the agent's GitHub account, with the links to its pull requests. Tinfirm's own notes go as the connection's account, starting with **Tinfirm:**. Status changes set the labels you map, and a done task can close the issue. |
| Pull request | The agent's pull request in the issue's repository says `Closes #57`, so GitHub links it to the issue and closes the issue when it is merged. |

The issue is the brief: edit the title and the body on GitHub, not in Tinfirm. A change in Tinfirm is overwritten by the next change of the issue.

Each Tinfirm project has a **work source**. A project that GitHub Issues feed has the work source **GitHub**: its tasks come only from your rules, so members create no tasks in it and agents create none with `create_task` (they list such work in their result's next steps, and a person opens the issue). Its Tasks page is a list of the tasks from issues instead of a board. Projects with **Tinfirm tasks** keep their board, and projects with the work source **Jira** take their work from a Jira connection.

## 2. Requirements

| Item | Requirement |
|---|---|
| GitHub | github.com, or GitHub Enterprise Server with the REST API version `2022-11-28` |
| Network | The Tinfirm server reaches the API over HTTPS: `api.github.com`, or your GitHub Enterprise Server host. With the Helm chart's NetworkPolicy, add an egress rule for it on port 443 in `networkPolicy.egress.extra` ([helm.md, section 4](../../../operations/helm.md#4-networkpolicy)). |
| TLS | The server's JVM trusts the certificate. For GitHub Enterprise Server with an internal CA, add the CA to the trust store. http is refused unless the operator sets `TINFIRM_TRACKER_ALLOW_HTTP=true`, because the token would travel in clear text. |
| Accounts | One GitHub account for the connection, and one per agent if agents should comment as themselves (section 3) |

## 3. Accounts and tokens

Tinfirm reads and writes with personal access tokens. Agents never log in to GitHub; Tinfirm uses their tokens on the server.

| Account | Used for | Token |
|---|---|---|
| Connection account, e.g. a machine user `tinfirm-bot` | Polling, Tinfirm's own notes, the status labels and the close, and the comments of agents without a token | Fine-grained: the repositories of your rules, with **Issues: Read and write** and **Metadata: Read**. Classic: `repo`. |
| One per agent, e.g. `tinfirm-backend` (optional) | The agent's comments; `ASSIGNEE` and `MENTION` rules match its login | The same as the connection's, on the repositories the agent works on |

An agent needs a login on the connection for `ASSIGNEE` and `MENTION` rules, but not a token: without one, its comments are written by the connection's account with the agent's name in front. People can assign an issue to the agent's account when that account is a member of the organisation with access to the repository, or a collaborator with write access.

For each token:

1. Log in to GitHub as the account and create the token under **Settings → Developer settings → Personal access tokens** (the fine-grained token page is <https://github.com/settings/personal-access-tokens/new>). Give it an expiry date that your policy allows, and choose the resource owner (the organisation) and the repositories. An organisation may have to approve fine-grained tokens first.
2. In Tinfirm, open **Secrets** and store the token, for example as `GITHUB_TINFIRM_TOKEN` or `GITHUB_BACKEND_AGENT_TOKEN`. The value is encrypted and never shown again.
3. To rotate a token, create a new one, store it as a new value of the same secret in Tinfirm, then delete the old one on GitHub. The next poll or write uses the new value.

Every use of a token is audited as `secret.accessed`: `tracker-poll:<id>` once per poll, `tracker-write:<id>` once per delivery round, `tracker-test:<id>` for **Test**.

The poller sees what the connection's token sees. An issue of a repository the token cannot read never becomes a task.

## 4. Connect

1. For each Tinfirm project that GitHub Issues should feed, open the project's **Settings** and set its **Work source** to **GitHub**, or choose GitHub when you create the project. Rules can target only such projects.
2. Open **Trackers**, choose **Connect a tracker**, and pick **GitHub Issues**.
3. Enter a name, the API URL, and pick the connection account's secret. The API URL is `https://api.github.com` for github.com, or `https://<host>/api/v3` for GitHub Enterprise Server. The kind cannot change later.
4. Optionally map task statuses to labels, and choose whether a done task closes its issue (section 6).
5. Choose **Test**. It shows the login the token belongs to, or why GitHub refused it.
6. Under **Agent accounts**, give each agent its GitHub login and, optionally, the secret with its token.
7. Under **Rules**, add the rules (section 5).

The first poll looks at issues updated since you connected. An issue that matched before is picked up when it next changes, for example when someone assigns it again or adds a label.

## 5. Rules

A rule reads: *an issue of repository `owner/repo` that matches a condition becomes a task of a Tinfirm project, for an agent.*

| Condition | Matches an issue without a task when | Typical use |
|---|---|---|
| is assigned to the agent (`ASSIGNEE`) | one of its assignees is the agent's login | People assign issues to the agent as to a colleague |
| has the label (`LABEL`) | it has the label, ignoring case | A label such as `ai-agent` sends issues to an agent |
| mentions the agent (`MENTION`) | a new comment by a person contains `@login` of the agent | Ask the agent from a discussion |
| is new (`CREATED`) | it was created since the last poll, whatever its assignees or labels | A triage project whose agent checks every new issue |

- GitHub issues have no workflow status, so there is no status rule; use a label instead.
- The repository is `owner/repo`, as in the repository's URL. Names compare ignoring case.
- Rules are evaluated in list order; the first enabled rule that matches wins. Disable a rule to keep it without using it.
- `ASSIGNEE` and `MENTION` need the agent's login on the connection. Tinfirm refuses such a rule without one, and refuses to remove a login that a rule needs. `CREATED` needs none.
- **Every new issue of the repository becomes a task** with a `CREATED` rule: use it for a triage project whose agent checks each new issue (is it complete, does it have acceptance criteria, where does it belong). Issues created more than two minutes before you connected never match, and an issue created while polling is off is picked up when polling is on again. Narrow it with "only when" below, for example to new issues of type `Bug`.
- **A closed issue never becomes a task**, whatever the rule. An issue that already has a task is still followed when it is closed.
- A rule only creates tasks. Once an issue has a task, rules do not apply to it again: reassigning the issue on GitHub does not move the task to another agent.
- A rule's Tinfirm project must have the work source GitHub; the rules editor lists only such projects. While a rule targets a project, enabled or not, the project keeps that work source: remove its rules first to switch it.
- The task gets every repository of its project that the agent may access, at most 20.
- The task's key comes from the project's task prefix, e.g. `WEB-61`: `owner/repo#57` cannot be a branch name. Its branch is `agent/WEB-61` and its pull requests are titled `WEB-61: …`; the task links the issue, and the pull request names it (section 9).

**Only when.** Each rule can also ask that the issue, when the condition matches, has every one of some labels and is of one of some issue types. Enter them in the rule's **only when** line, separated by commas; leave a field empty for no condition. Names compare ignoring case, and each field takes at most 10 names. There is no status field, since GitHub issues have none. The types are the issue types an organisation sets up in its settings; an issue without a type, for example in a repository of a user, never matches a rule with types.

| Rule | Becomes a task |
|---|---|
| is new, only when of type `Bug` | Every new bug, and no other new issue |
| has the label `ai-agent`, only when with the label `backend` | Issues labelled `ai-agent` that also carry `backend` |
| is assigned to the agent, only when of type `Task` or `Feature` | Issues of those types that are assigned to the agent; the poll after the type is set takes an assigned issue |

"Only when" is checked at the moment the rule's condition matches. A rule that is new (`CREATED`) with conditions takes only issues that meet them when they are created: an issue that gets the label after a poll has seen it without is not taken by it. To react to a label set later, make it the rule's condition (has the label) and put the rest under only when.

## 6. Labels and closing

Tinfirm moves a task to In progress when its run starts, to In review when the run succeeds, and a member may move it to Done. For each of these statuses you can name a label, at most 50 characters. When the task moves, Tinfirm adds that label to the issue and removes the labels you mapped for the other two statuses. GitHub creates a label that the repository does not have yet.

- No label: no change. Leave all three empty to keep the workflow fully on GitHub.
- **Close the issue when the task is done** closes the issue as completed when the task moves to Done, after the label.
- A task that leaves Done does not reopen the issue; reopen it on GitHub.
- Labels and the close are written by the connection's account.
- A pull request that says `Closes #57` closes the issue on GitHub when it is merged, whatever these settings say.

## 7. Polling, not webhooks

Tinfirm polls GitHub instead of receiving webhooks. A webhook needs an address that GitHub can reach and a shared secret; polling needs only the token.

| Topic | Behaviour |
|---|---|
| Delay | A change reaches Tinfirm within one poll interval, plus up to 15 s for the poller's tick. The interval is 30 s to one day. |
| Load | One `GET /user` and at least one list request per repository per poll, pages of 100, at most 200 issues per poll shared by the repositories; one or two more requests for the comments of each changed issue that has comments. GitHub's rate limit for a token is 5 000 requests an hour. |
| Pull requests | GitHub lists pull requests among the issues. Tinfirm skips them, but they count toward the 200. |
| Overlap | Each poll repeats the last two minutes, so a change that GitHub lists late is not missed. An issue that did not change is skipped. |
| Bulk changes | When more issues changed than a poll handles, the next polls continue where the last one stopped. |
| Rate limit | When GitHub answers that the rate limit is exhausted, the poll stops and the last poll's error says so, with the time it resets. The next poll repeats the same window. |
| Poll now | **Poll now** on the Trackers page polls at once, also when scheduled polling is off. |
| Failures | The last poll's error shows on the Trackers page. The next poll repeats the same window. |

Webhooks may be added later as a hint that makes the next poll start at once; the poll stays the source of truth.

## 8. Mention an agent on a linked issue

A person on the issue can ask another agent of the company to help with the issue's task, for example the test writer for the test scenarios ([ADR-0034](../../../adr/0034-mention-an-agent.md)). There are two ways to mention it in a comment:

| Mention | Example | Needs |
|---|---|---|
| The agent's handle, as text | `@test-writer please write the test scenarios` | Nothing: each agent's handle is on the Agents page in Tinfirm |
| The agent's GitHub login | `@tinfirm-test-writer please write the test scenarios` | The agent's login on the connection (section 3) |

What happens:

- The next poll brings the comment to the task, and Tinfirm starts a run of the mentioned agent on the task: at once when no run is active, else when the active run ends, before the task's own agent goes on. The mentioned agent's answer comes back to the issue as its comment, like any agent's.
- The mentioned agent works in the task's repositories it may access in Tinfirm, read-only where its access is read-only. Without any, it works from the issue and its comments, and its comment carries the result.
- A comment that mentions another agent is addressed to it: the task's own agent is not woken, and reads the comment at its next run. A comment that mentions the task's own agent too wakes it as well.
- A reply that mentions an agent answers that agent's open questions only. A reply that mentions nobody answers every open question of the task, as before.
- When the mentioned agent cannot start, because it is paused, has no credential or the task is closed, a **Tinfirm:** note on the issue says so.
- An agent usable by its owner only (on the Agents page, "Owner only"; [ADR-0035](../../../adr/0035-agent-usage-policy.md)) cannot be mentioned from the issue: the person there is not a member of the company. The comment still reaches the task, and a **Tinfirm:** note on the issue says `{agent} was not started (mention): it is usable by its owner only, and a mention on GitHub cannot be matched to a member.` Its owner or an admin can choose "Everyone in the company" for the agent, or mention it in Tinfirm.
- A handle inside backticks or a code block mentions nobody. A mention of the agent's login counts wherever it is, as in the `MENTION` rule.
- The `MENTION` rule (section 5) is a different thing: it turns an issue without a task into a task for its agent. On an issue that already has a task, a mention starts a run of the mentioned agent on that task.

**Logins or text.** A GitHub account per agent costs a seat when it is a member of your organisation, but GitHub completes `@login` as you type, and the agent's comments carry its own name. A handle written as text costs nothing, but GitHub offers no completion for it: type it as the Agents page shows it. A handle may also be someone's GitHub login, whom GitHub then notifies; that does not disturb Tinfirm, but choose handles that are not logins in your organisation.

## 9. What Tinfirm writes to GitHub

| From Tinfirm | On GitHub |
|---|---|
| An agent's comment | A comment by the agent's account; without its token, by the connection account, starting with the agent's name |
| An agent's question | A comment by the agent, as above: the question, its options as a list, and "Reply to this comment to answer." |
| The comment that ends a run | The summary and every pull request of the task. A run that ends on its questions, or on a repository request that waits for a member, writes nothing else: the issue holds each question once ([ADR-0036](../../../adr/0036-questions-end-a-run-quietly.md)) |
| A note of Tinfirm, e.g. "the agent was not started" | A comment by the connection account, starting with **Tinfirm:** |
| A member's answer to an agent's question, given in Tinfirm | A comment by the connection account, starting with the member's name and **(via Tinfirm)**; an answer given in a question's answer form quotes that question first. A comment on the task is an answer only when the member ticks that it answers the open questions; their other comments stay in Tinfirm |
| A status change you mapped | The status's label, without the other mapped labels (section 6) |
| A done task, with closing on | The issue closed as completed |
| The agent's pull request | `Closes #57` in a pull request of the issue's repository; `Refs owner/repo#57` in a pull request of another repository; `Issue: <url>` in a GitLab merge request |

Tinfirm writes comments in Markdown, as GitHub shows them. It never creates, edits the text of, assigns or deletes an issue.

Tinfirm never imports its own comments back: it records their ids and ignores every comment by an account it writes with. Do not let people use these accounts, or their comments are ignored too.

A write that GitHub cannot take now (no answer, a server error, the rate limit) is retried after 1, 2, 4… minutes, up to an hour apart, at most 10 times. Other refusals, such as a missing permission, are logged with the issue key and not retried.

## 10. Limits

- Comments are imported with the author's login, not their name: the comments list of GitHub's API carries no name.
- Edited and deleted comments on GitHub are not followed: the task keeps the comment as it was imported.
- Attachments and images of the issue stay on GitHub; their links reach the agent as they are.
- Pull requests do not become tasks, also when a rule would match them.
- A renamed or transferred repository answers with a redirect, which Tinfirm does not follow: change the rules' repository.
- When more issues of one repository than one poll handles changed within the same second, the polls repeat that second without getting further. Bulk edits on GitHub change far fewer issues at a time, so this is unlikely; the operator can raise `tinfirm.tracker.max-issues-per-poll` (CONVENTIONS §17.7).

## 11. Troubleshooting

| Last poll error | Cause | Fix |
|---|---|---|
| `GitHub answered 401: Bad credentials` | The token is wrong, expired or revoked | Create a new token and store it in the secret |
| `owner/repo: GitHub answered 404: Not Found` | The repository does not exist, or the token cannot see it | Fix the rule's repository, or give the token access to it |
| `owner/repo: GitHub answered 403: Resource not accessible by personal access token` | The fine-grained token lacks Issues or Metadata access, or the organisation has not approved it | Grant the permissions, or approve the token in the organisation's settings |
| `owner/repo: GitHub answered 403, the rate limit is exhausted; it resets at ...` | The token used up its hourly requests | Poll less often, or use a token of its own for the connection |
| `owner/repo: GitHub answered 301, a redirect: ...` | The repository was renamed or moved, or the API URL is wrong | Use the repository's new name, and `https://api.github.com` or `https://<host>/api/v3` |
| `GitHub could not be reached` | Network, DNS, proxy or certificate | Check the NetworkPolicy, the egress proxy and the server's trust store |
| `{owner/repo#57}: The assignee may access ... components` | The agent may access more than 20 components of the project | Narrow the agent's components |
| `The connection has no secret with a token` | The secret was deleted | Pick another secret in the connection's settings |
