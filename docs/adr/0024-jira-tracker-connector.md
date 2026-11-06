# ADR-0024: Work comes from the company's Jira

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: CONVENTIONS §7, §8, §14.1, §14.2, §14.3, §17; [ADR-0003](0003-postgresql-only-state-and-queue.md), [ADR-0004](0004-event-driven-wakes.md), [ADR-0013](0013-openapi-first-contract.md)
- Amended by: [ADR-0028](0028-asks-and-waiting-on.md), which adds a member's answer to an agent's question to the comments the write-back sends; [ADR-0029](0029-work-source-per-project.md), which keeps the board, task prefixes and agent-created tasks for projects without a tracker instead of removing them, and lets rules target only projects whose work source is Jira; [ADR-0030](0030-github-issues-tracker.md), which adds GitHub Issues as a second kind behind one interface for every kind, turns the rule's Jira project key into a scope, and adds `closeOnDone`; [ADR-0031](0031-rule-for-new-issues.md), which adds the trigger `CREATED` for every new issue and optional conditions on status, labels and type to every rule

## Context

- The companies Tinfirm is for keep the tracker they have. They will not move their backlog into a new board, and they look for results where they already look: on the issue.
- Tinfirm v0 is a tracker of its own: projects with task keys, a board, comments. Its board does not know who the work waits on, and the board is no longer a product pillar.
- Jira Data Center offers REST API v2 and a personal access token (PAT) per service account; Tinfirm polls, because a webhook needs a Jira administrator. Each agent can get a Jira service account, so its comments show as the agent.
- Work arrives in four ways: an issue is assigned to the agent's account, gets a label, reaches a status, or a comment mentions the agent.
- Wakes are events (ADR-0004), and PostgreSQL is the only state store (ADR-0003). An external tracker must fit both: a poller finds changes, and the existing wakes start the runs.

## Decision

**The issue is the brief, the tracker owns the workflow, Tinfirm owns the run and the waiting-on state.**

- A new Spring Modulith module `tracker` connects a company to Jira Data Center (`tracker_connection`): a base URL, a vault secret with the PAT of the service account that polls, a poll interval (default 60 s, at least 30 s), and an optional mapping of task statuses to Jira transition names.
- **Rules** (`tracker_rule`) say which issues become tasks: per Jira project, `ASSIGNEE` (assigned to the agent's account), `LABEL`, `STATUS` or `MENTION` (`[~account]` in a new comment); each names the agent and the Tinfirm project. The first enabled rule that matches an issue without a task wins.
- **Agent accounts** (`tracker_agent_account`) give each agent its Jira username and the secret with its PAT. The connector writes an agent's comments and transitions with its token; without one, with the connection's.
- **The poller** runs on worker replicas, each connection at its own interval, under a lease. It searches with JQL (`project in (...) AND updated >= "..." ORDER BY updated ASC, key ASC`) in the account's time zone, from the newest change it saw minus an overlap, page by page and bounded. A matching issue becomes a linked TODO task (`task.external_*`, `tracker_issue`): its summary is the title, its description in Markdown the description, and its comments of people come along as `EXTERNAL` comments. The task wakes its agent with `ASSIGNMENT`. A linked issue that changed updates the title and the description, and its new comments of people wake the agent with `COMMENT`, under the rules of a member's comment.
- **The write-back** listens to the work module's events after the commit: AGENT comments (the end-of-run comment lists the task's pull requests and each becomes a remote link of the issue), SYSTEM comments with a `Tinfirm:` label, and mapped status transitions. It queues them in `tracker_outbox` and retries what Jira may accept later. The connector records the id of every comment it writes (`tracker_sync`) and never imports a comment by an account it writes with, so its own comments never come back.
- **Markup**: Jira DC speaks wiki markup; tasks are Markdown. A small converter covers the common subset both ways and leaves the rest as it is.
- **API and UI**: tag Trackers in `api/openapi.yaml`; owners and admins manage connections, rules and accounts, every member reads them. A Trackers page in the web app; a task shows its issue key as a link.
- **Audit**: `tracker.created`, `tracker.updated`, `tracker.deleted`, `tracker.rules.changed`, `tracker.agent_accounts.changed`, `tracker.issue.linked`; every token reveal is `secret.accessed` with a `tracker-*` purpose.

## Consequences

- An issue labelled or assigned in Jira starts an agent within one poll interval, and the agent's answer, its pull requests and the next status show on the issue. Members keep working in Jira.
- **The internal task becomes a link to the issue.** It still holds the runs, the transcript, the repositories and the pull requests, but its title, description and the comments of people come from Jira: an edit in Tinfirm is overwritten by the next change of the issue. Edit the brief in Jira.
- **The board is on the way out.** Tasks made from issues still appear on it, so the UI is never without work, but the board, task prefixes and agent-created tasks go once the connector carries the work. The task status still drives the transitions until a "waiting on" state replaces it.
- Polling costs one `myself` call and one or more searches per interval per connection, and each change is seen up to one interval late. JQL dates have minute precision and depend on the searching account's time zone; the overlap and the offset for bulk changes handle both, at the price of reading some issues twice (an unchanged issue is skipped).
- An issue that matched once stays linked: reassigning it in Jira does not move the task, and a comment on a DONE or CANCELLED task is recorded but wakes nobody.
- The write-back is at least once: a delivery that timed out after Jira stored it can post a comment twice. An event is lost when the server stops between the commit and the queueing of the delivery; the Spring Modulith event publication registry (ADR-0003) will close that gap.
- Wiki markup outside the common subset (colours, panels, images, macros) reaches the agent unconverted, and Markdown outside it reaches Jira as text.
- The poller reads with the connection's PAT, so it sees what that service account sees. Comments of people, with their Jira display names, are stored in Tinfirm like members' comments.
- A tracker connection is a new trust boundary (threat model TB8): PATs in the vault, a base URL that owners and admins choose.

## Alternatives considered

- **Webhooks first.** Jira DC webhooks push changes at once, but configuring one needs a Jira administrator, and a company may let agents work with service accounts only. A webhook can be added later as a wake hint: the poll stays the source of truth, as `NOTIFY` is only a hint in ADR-0003.
- **Jira Cloud first.** Both are needed. Data Center's REST API v2, PATs and wiki markup differ from Cloud's v3 API, OAuth or API tokens, and Atlassian Document Format. Data Center first; Cloud is a second `kind` behind the same rules, links and write-back.
- **Tinfirm as the tracker.** The v0 path: members write tasks in Tinfirm. Rejected: companies keep their tracker, and a second backlog drifts from the real one.
- **Read the issue live at every dispatch instead of copying it.** Better for long tasks, but it puts a Jira call in the dispatcher and the prompt. Copying on change keeps the run module unchanged for now.
