# ADR-0031: Rules: a trigger for new issues, and conditions on status, labels and type

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: the owner's decision of 2026-10-03 and its two additions of the same day (conditions on status and labels; a condition on the issue type); CONVENTIONS §17; [ADR-0024](0024-jira-tracker-connector.md), [ADR-0030](0030-github-issues-tracker.md)

## Context

- A rule turns an issue without a task into a task of a project, for an agent (ADR-0024, ADR-0030). Its trigger is one thing a person does on the issue: assign it to the agent's account (`ASSIGNEE`), add a label (`LABEL`), move it to a status (`STATUS`, Jira only), or mention the agent in a comment (`MENTION`).
- Some agents should look at every new issue as soon as it exists: an agent that checks a new ticket for completeness and acceptance criteria and does a first triage. With the four triggers, a person or an automation in the tracker has to label or assign every new issue first.
- A rule cannot narrow what its trigger matches. "Issues labelled `ai-agent`, but only once they are ready", or "new bugs only", need a second condition; a team that keeps several kinds of work in one Jira project or repository otherwise gets tasks for issues the agent should not take.
- GitHub lists closed issues among the changed ones, and ADR-0030's rules match them like open ones: a label added to a closed issue starts work on it.

## Decision

**A trigger for new issues.** `CREATED`: an issue without a task matches when it was created at or after the poll's lower bound (`PollWindow.since()`, the bound that `MENTION` uses for comments), whatever its assignee, labels or status. It works for both kinds, Jira Data Center and GitHub.

- An issue's creation time is Jira's `created` (now one of the search fields) or GitHub's `created_at`; an issue without a usable one counts as created when it was last updated.
- "At or after the lower bound" is `!created.isBefore(since)`. The first poll's bound is the connection's creation minus the overlap (two minutes), so issues created before that never match. The bound does not move while polling is off, so an issue created meanwhile is caught up when polling resumes.
- `CREATED` takes no `value` and needs no agent account. Which triggers need an account is now stated per trigger: `ASSIGNEE` and `MENTION` do; `LABEL`, `STATUS` and `CREATED` do not. The rule check, `409 TRACKER_ACCOUNT_IN_USE` when the accounts are replaced, and the web app's check follow it.
- The first enabled rule in position order still wins; `CREATED` has no precedence of its own. A linked issue is never matched again, as for every trigger.

**Conditions.** A rule keeps its trigger and gains optional `conditions` that must also hold at the moment the trigger matches:

| Condition | The issue matches when | Kinds |
|---|---|---|
| `statuses` | its status is one of them | `JIRA_DC`; on a `GITHUB` connection `400 INVALID_TRACKER_RULE`, `Rule N: GitHub issues have no status; conditions on status are for Jira` |
| `labels` | it has every one of them | Both |
| `types` | its issue type is one of them: Jira's `issuetype` (now one of the search fields), or the GitHub issue type of the repository's organisation (`type.name`) | Both; an issue without a type never matches |

- Names compare ignoring case. An empty or absent list is no condition. Each list holds at most 10 entries of 1 to 255 characters after trimming; duplicates, ignoring case, are dropped and the first spelling stays. A label that repeats the `LABEL` trigger's value is fine.
- Conditions are checked after the trigger, so a `CREATED` rule with conditions matches only an issue that meets them when it is created. A rule that should start work when a label or status is set later uses the `LABEL` or `STATUS` trigger, with conditions for the rest: "has the label `ai-agent`, only when in status `Ready for AI`".

**Closed GitHub issues.** A GitHub issue whose `state` is `closed` never matches a rule; `TrackerIssue` carries `closed`, always false for Jira. The poller skips a closed issue without a task before reading its comments, and still follows linked issues whatever their state.

**API, audit and web.** `TrackerRuleTrigger` gains `CREATED`. A new schema `TrackerRuleConditions` (`statuses`, `labels`, `types`) is `TrackerRule.conditions`, always present with its three lists, and the optional `TrackerRuleInput.conditions`. The audit details of `tracker.rules.changed` give each rule's `conditions` with its non-empty lists, and leave it out for a rule without conditions. The rules editor offers "is new", and each rule gets an "only when" line with comma-separated statuses (Jira only), labels and types; the read view says, for example, "only when in status Ready for AI, with the label backend, of type Bug".

**Data model: migration `V030__rule_created_trigger.sql`.** It changes a constraint and adds a column with a default; it reads and writes no row.

| Table | Change |
|---|---|
| `tracker_rule` | `tracker_rule_trigger_ck` admits `CREATED` without a value; `LABEL` and `STATUS` keep their value, `ASSIGNEE` and `MENTION` stay without one |
| `tracker_rule` | New column `conditions jsonb NOT NULL DEFAULT '{}'`, checked to be a JSON object (`tracker_rule_conditions_ck`): `{"statuses": [...], "labels": [...], "types": [...]}` without empty lists. Existing rules get `{}`, no condition. |

## Consequences

- A connection with a `CREATED` rule turns every new issue of the rule's scope into a task, and each task wakes its agent. Such a rule belongs to a triage project whose agent checks every new ticket; narrow it with conditions, for example to the type `Bug`, where a project or repository holds more than that agent should see.
- `CREATED` sees an issue only in the polls whose lower bound is at or before its creation, normally the first poll after the issue was created. An issue that fails to become a task in that poll (CONVENTIONS §17.3 step 4) is not matched by `CREATED` when it changes later, and neither is a new issue that a poll first sees after the bound has passed its creation: a bulk change of more issues than a poll handles, while the new issue changed again. Another rule can still match both. Issues created while a connection had no enabled rule do not match either, because the bound moves on while there is none.
- Conditions hold or not at the moment the trigger matches. An issue created without the label that a `CREATED` rule asks for, and labelled after a poll has seen it, does not become a task through that rule; a `LABEL` or `STATUS` rule waits instead: an issue with the label but not yet the status becomes a task when a later change brings the status.
- Closing a GitHub issue keeps it from becoming a task, also when it is labelled or assigned later. Reopening it is a change, so a rule whose trigger and conditions hold then matches it; `CREATED` does not, the issue being older than the bound. Jira has no such default because what a closed status is depends on the workflow; a status condition expresses it.
- A types condition on GitHub needs the organisation's issue types. Issues of a repository without them have no type, so such a condition matches none of them.
- Nothing changes for the other triggers or for existing rules, which have no conditions. The Jira search reads two more fields, `created` and `issuetype`, and the GitHub list already carries `created_at`, `state` and `type`.
- The web app's fields split the lists at commas, so a name with a comma can be set only through the API.

## Alternatives considered

- **A `STATUS` rule on the initial status** (for example `Open`) instead of `CREATED`. Rejected: an older issue in that status matches as soon as anything about it changes, so it is no rule for new issues; and GitHub issues have no status.
- **A webhook for created issues.** Deferred as in ADR-0024 and ADR-0030: a webhook needs a Jira administrator or an address GitHub reaches, and polling stays the source of truth. A webhook can later be a hint that starts the next poll at once.
- **A filter language, or JQL, per rule.** Rejected: the tracker's own search stays the place for complex queries, JQL exists only in Jira, and a rule stays readable in one sentence. Three lists cover the cases the owner named.
- **A default that excludes closed Jira issues.** Not possible without knowing each workflow's closed statuses; a status condition does it per rule.
