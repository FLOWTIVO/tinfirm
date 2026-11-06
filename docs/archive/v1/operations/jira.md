# Connect Jira Data Center

This guide is for owners and admins of a company who want work to come from their Jira Data Center (or Server): an issue that matches a rule becomes a task, an agent works on it, and its comments, pull requests and status changes go back to the issue.

The binding rules are in [CONVENTIONS §17](../CONVENTIONS.md#17-trackers). The decisions are [ADR-0024](../../../adr/0024-jira-tracker-connector.md), [ADR-0031](../../../adr/0031-rule-for-new-issues.md) for new issues and rule conditions, and, for the work source of a project, [ADR-0029](../../../adr/0029-work-source-per-project.md). To connect GitHub Issues instead, see [github-issues.md](github-issues.md).

## 1. How it works

| Step | What happens |
|---|---|
| Poll | Every poll interval (default 60 s), Tinfirm searches the projects of your rules for issues updated since the last poll, as the connection's service account. |
| Match | An issue without a task that matches a rule becomes a TODO task of the rule's Tinfirm project, assigned to the rule's agent. The summary is the title, the description is the brief, and the comments of people come along. The agent starts at once. |
| Follow | When the issue changes, the task's title and description follow it. New comments of people go to the task and wake the agent; a reply answers all of the agent's open questions at once, since it cannot name one. A reply that mentions another agent asks that agent instead (section 8). |
| Write back | The agent's comments go to the issue as the agent's Jira account, with links to its pull requests. Tinfirm's own notes go as the connection's account, starting with **Tinfirm:**. Status changes become the Jira transitions you map. |

The issue is the brief: edit the title and the description in Jira, not in Tinfirm. A change in Tinfirm is overwritten by the next change of the issue.

Each Tinfirm project has a **work source**. A project that Jira feeds has the work source **Jira**: its tasks come only from your rules, so members create no tasks in it and agents create none with `create_task` (they list such work in their result's next steps, and a person creates the issue). Its Tasks page is a list of the tasks from issues instead of a board. Projects with **Tinfirm tasks** keep their board and are not fed by Jira.

## 2. Requirements

| Item | Requirement |
|---|---|
| Jira | Data Center or Server 8.14 or later, with personal access tokens (PATs) enabled |
| Network | The Tinfirm server reaches Jira's base URL over HTTPS. With the Helm chart's NetworkPolicy, add an egress rule for Jira's address and port 443 in `networkPolicy.egress.extra` ([helm.md, section 4](../../../operations/helm.md#4-networkpolicy)). |
| TLS | Jira's certificate is trusted by the server's JVM. http is refused unless the operator sets `TINFIRM_TRACKER_ALLOW_HTTP=true`, because the token would travel in clear text. |
| Accounts | One service account for the connection, and one per agent (section 3) |

## 3. Service accounts and tokens

Create the accounts in Jira (or in the directory Jira reads users from). Agents never log in to Jira; Tinfirm uses their tokens on the server.

| Account | Used for | Jira permissions in the projects of your rules |
|---|---|---|
| Connection account, e.g. `svc-tinfirm` | Polling, Tinfirm's own notes, and the comments and transitions of agents without an account | Browse projects, Add comments, Transition issues, Link issues (for the pull request links) |
| One per agent, e.g. `svc-backend-agent` | The agent's comments and transitions; `ASSIGNEE` and `MENTION` rules match it | Browse projects, Add comments, Transition issues, Link issues, Assignable user (so people can assign issues to it) |

For each account:

1. Log in to Jira as the account, open **Profile → Personal Access Tokens**, and create a token. Give it an expiry date that your policy allows, and note it down.
2. In Tinfirm, open **Secrets** and store the token, for example as `JIRA_TINFIRM_PAT` or `JIRA_BACKEND_AGENT_PAT`. The value is encrypted and never shown again.
3. To rotate a token, create a new one in Jira, store it as a new value of the same secret in Tinfirm, then revoke the old one in Jira. The next poll or write uses the new value.

Every use of a token is audited as `secret.accessed`: `tracker-poll:<id>` once per poll, `tracker-write:<id>` once per delivery round, `tracker-test:<id>` for **Test**.

The poller sees what the connection account sees. An issue in a project or under a security level that the account cannot browse never becomes a task.

## 4. Connect

1. For each Tinfirm project that Jira should feed, open the project's **Settings** and set its **Work source** to **Jira**, or choose Jira when you create the project. Rules can target only such projects.
2. Open **Trackers**, choose **Connect a tracker**, and pick **Jira Data Center**.
3. Enter a name, Jira's base URL (the address you open Jira at, without `/rest`), and pick the connection account's secret.
4. Optionally map task statuses to Jira transitions (section 6).
5. Choose **Test**. It shows the account the token belongs to, or why Jira refused it.
6. Under **Agent accounts**, give each agent its Jira username and the secret with its token.
7. Under **Rules**, add the rules (section 5).

The first poll looks at issues updated since you connected Jira. An issue that matched before is picked up when it next changes, for example when someone assigns it again or adds a label.

## 5. Rules

A rule reads: *an issue of project `PROJ` that matches a condition becomes a task of a Tinfirm project, for an agent.*

| Condition | Matches an issue without a task when | Typical use |
|---|---|---|
| is assigned to the agent (`ASSIGNEE`) | its assignee is the agent's Jira account | People assign issues to the agent as to a colleague |
| has the label (`LABEL`) | it has the label, ignoring case | A label such as `ai-agent` sends issues to an agent |
| is in the status (`STATUS`) | it is in the status, ignoring case | A column such as `Ready for AI` on the Jira board |
| mentions the agent (`MENTION`) | a new comment by a person contains `[~account]` of the agent | Ask the agent from a discussion |
| is new (`CREATED`) | it was created since the last poll, whatever its assignee, labels or status | A triage project whose agent checks every new ticket |

- Rules are evaluated in list order; the first enabled rule that matches wins. Disable a rule to keep it without using it.
- `ASSIGNEE` and `MENTION` need the agent's account on the connection. Tinfirm refuses such a rule without one, and refuses to remove an account that a rule needs. `CREATED` needs none.
- **Every new issue of the Jira project becomes a task** with a `CREATED` rule: use it for a triage project whose agent checks each new ticket (is it complete, does it have acceptance criteria, where does it belong). Issues created more than two minutes before you connected Jira never match, and an issue created while polling is off is picked up when polling is on again. Narrow it with "only when" below, for example to new issues of type `Bug`.
- A rule only creates tasks. Once an issue has a task, rules do not apply to it again: reassigning the issue in Jira does not move the task to another agent.
- A rule's Tinfirm project must have the work source Jira; the rules editor lists only such projects. While a rule targets a project, enabled or not, the project keeps the work source Jira: remove its rules first to switch it back to Tinfirm tasks.
- The task gets every repository of its project that the agent may access, at most 20.
- The task's key is the issue key, e.g. `PROJ-123`, so its branch is `agent/PROJ-123` and its merge requests are titled `PROJ-123: …`. A key longer than 32 characters gets a key from the project's task prefix instead. Tasks keep the keys they have.
- **Limitation:** task keys are unique in the company, and issue keys share them with the keys of projects that use Tinfirm tasks. If such a project's task prefix equals a Jira project key, its tasks can take an issue's key first; that issue then never becomes a task, and the last poll names the key (section 11). Choose task prefixes that are not Jira project keys. The other way round is handled: a project's new task skips a key that an issue already took.
- Every project key of an enabled rule goes into one search. If the connection account cannot see one of the projects, Jira refuses the whole search, and the last poll shows that error. Fix or disable the rule.

**Only when.** Each rule can also ask that the issue, when the condition matches, is in one of some statuses, has every one of some labels, and is of one of some issue types. Enter them in the rule's **only when** line, separated by commas; leave a field empty for no condition. Names compare ignoring case, and each field takes at most 10 names.

| Rule | Becomes a task |
|---|---|
| has the label `ai-agent`, only when in status `Ready for AI` | A labelled issue once it reaches `Ready for AI`: the poll after the move takes it |
| is new, only when of type `Bug` | Every new bug, and no other new issue |
| is assigned to the agent, only when with the label `backend` | Issues assigned to the agent that also carry `backend`; the poll after the label is added takes an assigned issue |

"Only when" is checked at the moment the rule's condition matches. A rule that is new (`CREATED`) with conditions takes only issues that meet them when they are created: an issue that gets the label after a poll has seen it without is not taken by it. To react to a label or status set later, make it the rule's condition (has the label, is in the status) and put the rest under only when. Jira has no closed state of its own, so closed issues can match; add a status condition if they should not.

## 6. Status transitions

Tinfirm moves a task to In progress when its run starts, to In review when the run succeeds, and a member may move it to Done. For each of these statuses you can name a Jira transition. Tinfirm then reads the transitions the issue's current status offers and makes the one with that name (ignoring case), as the task's agent account, or as the connection account when the agent has none.

- No name: no transition. Leave all three empty to keep the workflow fully in Jira.
- Use the name of the **transition** (the arrow, e.g. `Start Progress`), not of the target status.
- If the issue's status does not offer it, for example because someone already moved the issue, the transition is skipped and logged.
- Projects with different workflows need the same transition names, or a connection each.

## 7. Polling, not webhooks

Tinfirm polls Jira instead of receiving webhooks. A Jira webhook needs a Jira administrator to configure it, while polling needs only the service accounts.

| Topic | Behaviour |
|---|---|
| Delay | A change reaches Tinfirm within one poll interval, plus up to 15 s for the poller's tick. The interval is 30 s to one day. |
| Load | One `myself` request and at least one search per poll; at most 200 issues per poll, in pages of 50. |
| Overlap | Each poll repeats the last two minutes, so changes that Jira indexes late are not missed. An issue that did not change is skipped. |
| Time zone | JQL dates use the time zone of the connection account in Jira. Tinfirm reads it from `myself`. |
| Bulk changes | When more than 200 issues changed in the same minutes, the next polls continue where the last one stopped. |
| Poll now | **Poll now** on the Trackers page polls at once, also when scheduled polling is off. |
| Failures | The last poll's error shows on the Trackers page. The next poll repeats the same window. |

Webhooks may be added later as a hint that makes the next poll start at once; the poll stays the source of truth.

## 8. Mention an agent on a linked issue

A person on the issue can ask another agent of the company to help with the issue's task, for example the test writer for the test scenarios ([ADR-0034](../../../adr/0034-mention-an-agent.md)). There are two ways to mention it in a comment:

| Mention | Example | Needs |
|---|---|---|
| The agent's handle, as text | `@test-writer please write the test scenarios` | Nothing: each agent's handle is on the Agents page in Tinfirm |
| The agent's Jira account | `[~svc-test-writer] please write the test scenarios` | The agent's account on the connection (section 3) |

What happens:

- The next poll brings the comment to the task, and Tinfirm starts a run of the mentioned agent on the task: at once when no run is active, else when the active run ends, before the task's own agent goes on. The mentioned agent's answer comes back to the issue as its comment, like any agent's.
- The mentioned agent works in the task's repositories it may access in Tinfirm, read-only where its access is read-only. Without any, it works from the issue and its comments, and its comment carries the result.
- A comment that mentions another agent is addressed to it: the task's own agent is not woken, and reads the comment at its next run. A comment that mentions the task's own agent too wakes it as well.
- A reply that mentions an agent answers that agent's open questions only. A reply that mentions nobody answers every open question of the task, as before.
- When the mentioned agent cannot start, because it is paused, has no credential or the task is closed, a **Tinfirm:** note on the issue says so.
- An agent usable by its owner only (on the Agents page, "Owner only"; [ADR-0035](../../../adr/0035-agent-usage-policy.md)) cannot be mentioned from the issue: the person there is not a member of the company. The comment still reaches the task, and a **Tinfirm:** note on the issue says `{agent} was not started (mention): it is usable by its owner only, and a mention on Jira cannot be matched to a member.` Its owner or an admin can choose "Everyone in the company" for the agent, or mention it in Tinfirm.
- A handle inside `{{monospace}}` or a `{code}` or `{noformat}` block mentions nobody. A mention of the agent's account counts wherever it is, as in the `MENTION` rule.
- The `MENTION` rule (section 5) is a different thing: it turns an issue without a task into a task for its agent. On an issue that already has a task, a mention starts a run of the mentioned agent on that task.

**Accounts or text.** An account per agent costs a Jira licence, but Jira's editor completes `[~account]` as you type, and the agent's comments carry its own name. A handle written as text costs nothing, but Jira offers no completion for it: type it as the Agents page shows it.

## 9. What Tinfirm writes to Jira

| From Tinfirm | In Jira |
|---|---|
| An agent's comment | A comment by the agent's account; without one, by the connection account, starting with the agent's name |
| An agent's question | A comment by the agent, as above: the question, its options as a list, and "Reply to this comment to answer." |
| The comment that ends a run | The summary and every pull request of the task; each pull request also becomes a link of the issue, once. A run that ends on its questions, or on a repository request that waits for a member, writes nothing else: the issue holds each question once ([ADR-0036](../../../adr/0036-questions-end-a-run-quietly.md)) |
| A note of Tinfirm, e.g. "the agent was not started" | A comment by the connection account, starting with **Tinfirm:** |
| A member's answer to an agent's question, given in Tinfirm | A comment by the connection account, starting with the member's name and **(via Tinfirm)**; an answer given in a question's answer form quotes that question first. A comment on the task is an answer only when the member ticks that it answers the open questions; their other comments stay in Tinfirm |
| A status change you mapped | The transition (section 6) |

Tinfirm never imports these comments back: it records their ids and ignores every comment by an account it writes with. Do not let people use the service accounts, or their comments are ignored too.

A write that Jira cannot take now (no answer, a server error, rate limiting) is retried after 1, 2, 4… minutes, up to an hour apart, at most 10 times. Other refusals, such as a missing permission, are logged with the issue key and not retried.

## 10. Markup

Jira Data Center uses wiki markup, Tinfirm uses Markdown. Tinfirm converts the common subset both ways: headings, bold and italic, monospace, code and noformat blocks, bullet and numbered lists, links and mentions, quotes and simple tables.

- Everything else stays as it is: colours, panels, images, attachments (`[^file]`), emoji and other macros reach the agent as wiki markup, and Markdown that has no wiki equivalent reaches Jira as text.
- Inside code nothing is converted.
- Edited and deleted comments in Jira are not followed: the task keeps the comment as it was imported.
- Attachments of the issue do not reach the task.

## 11. Troubleshooting

| Last poll error | Cause | Fix |
|---|---|---|
| `Jira answered 401 (AUTHENTICATED_FAILED)` | The token is wrong, expired or revoked | Create a new token and store it in the secret |
| `Jira answered 302, a redirect` | The base URL points at a login page or another address | Use the address you open Jira at, without `/rest` |
| `Jira answered 404` | The base URL has a wrong path, or it is not Jira | Check the context path, e.g. `https://example.com/jira` |
| `Jira answered 400: ... does not exist for the field 'project'` | A rule names a project the account cannot see | Grant Browse projects, or fix the rule's project key |
| `Jira could not be reached` | Network, DNS, proxy or certificate | Check the NetworkPolicy, the egress proxy and the server's trust store |
| `{KEY}: The assignee may access ... components` | The agent may access more than 20 components of the project | Narrow the agent's components |
| `{KEY}: another task of the company has the key {KEY}, ...` | A task of a project with Tinfirm tasks already has the issue's key (section 5, limitation) | Keys never change, so this issue cannot become a task. Change that project's task prefix, so that its new tasks stop taking issue keys |
| `The connection has no secret with a token` | The secret was deleted | Pick another secret in the connection's settings |
