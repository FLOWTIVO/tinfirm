# ADR-0043: Trigger rules: several events, condition groups, a subject, and LABEL_REMOVED

- Status: Accepted
- Date: 2026-10-07
- Sources: the owner's request of 2026-10-07 (run a trigger only when an issue does not have a label; split rules between GitHub issues and pull requests; several triggers with AND/OR logic); PLATFORM.md §4.5, §5.1; CONVENTIONS §14.5, §14.9; [ADR-0031](0031-rule-for-new-issues.md)
- Changes: PLATFORM.md §4.5, where a trigger has one `event` with its `value` and one set of `conditions`.

## Context

- A trigger of the restart (PLATFORM.md §4.5) reads as one sentence: when `event` happens to a subject in `scope`, and `conditions` hold, `agent` does `instruction`. Its conditions were one set of lists that all had to hold: the status is one of, the subject has every label of, the type is one of, and who did it.
- Three things could not be said. A rule that must not run while an issue is blocked ("does not have the label `blocked`"). A rule for GitHub issues that must leave pull requests alone, or the reverse, although both are subjects of one repository. And a rule that starts the same job on either of two changes ("when the label `ai` is added, or the issue is assigned to the agent"), or whose conditions are alternatives ("in status Open with the label `ai`, or of type Bug"), which took several triggers with the same instruction, each with its own thread.
- A label that is removed raised no event, so "unblock" could not start anything: a `LABEL_ADDED` trigger whose conditions excluded `blocked` would wait for the next label, not for the removal.

## Decision

**A trigger fires on any of one to five `events`, watches a `subject` (issues, change requests or both), and its `conditions` are groups, any of which must hold; `LABEL_REMOVED` is an event.**

- **Events.** `events` replaces `event` and `value`: a list of `{event, value}`, each once (the value ignoring case), a value where the event takes one (`STATUS_ENTERED`, `LABEL_ADDED`, `LABEL_REMOVED`). Any of them fires the trigger; a trigger fires once per change, however many of its events happened, with the first in its list that happened as the turn's event (`job.event`, `trigger_firing.event`). A SCHEDULE trigger has that event alone. A manual run records the first event.
- **"And" between events is not offered.** Events are edges: two rarely land in one poll, and an "and" of edges would fire only when they did. "The label `ai` is added and the issue is assigned" is the event with the state as a condition, which the conditions express (an assignment condition is a later addition).
- **Subject.** `subject` is `ANY` (default), `ISSUE` or `CHANGE_REQUEST`, beside the scope. Jira takes ISSUE or ANY; a schedule ANY; ISSUE refuses `CHANGE_REQUEST_OPENED` and `CHANGE_REQUEST_UPDATED`, which happen to change requests only. The sentence reads "when a pull request gets the label …".
- **Condition groups.** `conditions` is a list of at most five groups (disjunctive normal form): the trigger fires when no group exists or any group holds, and a group holds when every clause of it does. Each group keeps the lists of before, with their negatives (`notStatuses`, `notLabels`, `notTypes`: none of). A single empty group is no condition; an empty group among others would always hold and is refused, so a mistake in the editor cannot silently drop the other groups.
- **What could never fire is refused** (`400 INVALID_TRIGGER`, checked per event against the groups): a `STATUS_ENTERED` whose status every group excludes or does not allow; a `LABEL_ADDED` whose label every group excludes; a `LABEL_REMOVED` whose label every group requires; `MENTIONED` or `COMMENTED` when every group wants `authors` AGENTS (a comment that raises an event is always a person's); a name in a list and its negative within a group.
- **`LABEL_REMOVED`** fires for each label of the snapshot the subject lacks, ignoring case, so only for a work item (a subject Tinfirm has not seen has no snapshot). Tinfirm's own `LABEL_REMOVE` action updates the snapshot and raises nothing.
- **Storage** (`V011__trigger_rules.sql`): `connection_trigger.subject`, `events` (jsonb, backfilled from `event` and `value`, which are dropped), `conditions` turned into an array (one group, or none when it was empty); the schedule index and `schedule_trigger_due` key on `schedule IS NOT NULL`; `trigger_firing.event` takes `LABEL_REMOVED`.
- **API.** `Trigger`, `TriggerInput` and `TriggerUpdateRequest` carry `subject`, `events` (`TriggerEventSpec`) and `conditions` as an array; `event` and `value` are gone. An update replaces the events as a whole. The audit detail follows.

## Consequences

- The editor reads "When [an issue or pull request] [gets the label] [ai], or [is assigned to the agent] in [acme/web], only when …, or only when …", with one event row and one group by default. The list reads the same sentence, with several groups as "(… and …) or (…)".
- Existing triggers keep their behaviour: one event, one group or none, subject ANY.
- Not in this decision: conditions on assignment ("unassigned", "assigned to the agent"), and "any of the labels" beside "all of"; both fit a group as further lists.
