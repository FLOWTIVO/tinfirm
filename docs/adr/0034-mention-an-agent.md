# ADR-0034: Mention an agent on a task

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Decided by: the owner, 2026-10-03
- Sources: the owner's request of 2026-10-03 (to mention an agent on a task); CONVENTIONS §6, §7, §14.1, §14.2, §14.3, §14.4, §15.5, §17.4, §18.3, §18.5, §18.6; [ADR-0004](0004-event-driven-wakes.md), [ADR-0015](0015-multi-repository-tasks.md), [ADR-0017](0017-read-only-repositories.md), [ADR-0024](0024-jira-tracker-connector.md), [ADR-0028](0028-asks-and-waiting-on.md), [ADR-0030](0030-github-issues-tracker.md), [ADR-0033](0033-one-answer-per-ask.md)
- Changes: ADR-0028 and ADR-0033 on whom an answer wakes: the agent that asked, not the task's assignee. An answer through the answer endpoint wakes the agent that asked once no `OPEN` ask of that agent remains, whatever other agents' asks are open. A reply on the linked issue that mentions agents answers only the `OPEN` asks of those agents; ADR-0033's rule that a reply answers every `OPEN` ask of the task stays for a reply that mentions nobody. A comment that is addressed to other agents, or that answered other agents' asks only, does not wake the assignee.
- Amended by: [ADR-0035](0035-agent-usage-policy.md), under which a mention that gives an agent work needs an agent the member may use, and a mention on a linked issue starts only agents of the whole company

## Context

- An agent works on a task only as its assignee: every wake (CONVENTIONS §14.3) creates a run of the task's assignee. A comment that asks another agent for something, "please write the test scenarios", does nothing but wake the assignee, which then does the test writer's work or ignores the request.
- The owner asked on 2026-10-03 that a person can mention an agent on a task, and that the mentioned agent gets a run on that task: in Tinfirm by writing `@handle` in a comment, on a linked Jira or GitHub issue by writing `@handle` as text or by mentioning the agent's account there (`[~account]`, `@login`).
- Agents have names, which may have spaces and capitals, but nothing a person can type after `@` in a comment, and nothing that a tracker's own mention syntax produces.
- Mentioning an agent's account in Jira or GitHub needs an account per agent on the tracker. On Jira Data Center that is a licence, on GitHub a seat when the account is a member of the organisation. Many companies will not pay for one per agent. A handle written as text costs nothing and works on every tracker and in Tinfirm, but the tracker offers no autocomplete for it. Text handles are therefore the baseline, and account mentions the optional upgrade for agents that have an account anyway.
- Runs on a task are serialized: at most one run per task is `QUEUED` or `RUNNING` (a partial unique index), and every wake during an active run coalesces into the task's follow-up flag (ADR-0004). A second agent working in the same checkout and branch at the same time would race the first.
- An agent may access some repositories and only read some (ADR-0017). A test writer, a reviewer or an analyst often has no access to the repositories the assignee changes.
- An agent that needs a person asks with `ask_human`, and the answer wakes the task's assignee (ADR-0028, ADR-0033). A mentioned agent that asks would wait for an answer that wakes someone else.

## Decision

**A person can mention an agent on a task, and the mentioned agent gets a run on that task.** The owner's decisions:

1. **Handles.** Every agent has a handle, `@handle` in comments. It is made from the name and can be edited.
2. **The mention does not wake the assignee.** A comment addressed to another agent is not for the assignee; it reads the comment at its next wake like any other comment.
3. **A mention never widens access.** The mentioned agent works in the task's repositories it may access, with its own read-only flags; the others are left out of its workspace. With none, it works with an empty workspace and puts its result in its summary.
4. **Runs on a task stay serialized**: one active run per task, as before. A mention during an active run waits and starts when that run ends, before the assignee's follow-up.
5. **A question asked by a mentioned agent is answered to that agent**, not to the assignee.

**Handles.**
- `agent.handle` (`V032__mentions.sql`): 1 to 50 lower-case ASCII letters, digits and hyphens, not starting or ending with a hyphen, unique among the live agents of a company like the name (a partial unique index). Requests may send capitals; the handle is stored in lower case. An invalid handle is `400 VALIDATION_FAILED` with a message that names the rule, the handle of another live agent `409 DUPLICATE_AGENT_HANDLE`. A change is audited within `agent.updated`.
- A create without a handle, or an update with an empty one, gets one made from the name: Unicode NFD without combining marks (`Vývojář` becomes `vyvojar`), lower case, every run of other characters one hyphen, hyphens trimmed, cut to 50 characters without a trailing hyphen, `agent` when nothing is left, and `-2`, `-3`… when a live agent has it. The migration gives existing agents a handle the same way, without folding accents.
- The API shows `Agent.handle`; the agents page shows `@handle` next to the name, and the agent dialog has a Handle field whose placeholder is the handle the typed name makes.

**Mentions.** One parser in the work module reads `@handle` in a comment's Markdown body: `@` at the start or after a character that is not a letter, digit, `_`, `-`, `/`, `.` or `@` (so `someone@example.com` and `owner/@repo` mention nobody), then the longest run of letters, digits and hyphens, matched ignoring case against the handles of the company's live agents. Nothing in a fenced code block or inline code mentions. An imported tracker comment also mentions the agents whose account on the connection it mentions in the tracker's own way, as the `MENTION` rule matches them; the poller passes them with the comment. Only people's comments mention: `USER` and `EXTERNAL` comments, never `AGENT` or `SYSTEM` ones. Whoever may comment may mention; there is no new permission.

**Who a comment wakes.** `UserComments.post`, in this order:
1. **Answers.** A comment that answers the open asks (`answersOpenAsks`, ADR-0033) wakes each agent whose asks it answered, with `ASK`.
2. **Mentions.** Every agent the body mentions that is not the assignee and was not woken in step 1 is woken with `MENTION`, in the order of the body.
3. **The assignee**, when not woken in step 1, with `COMMENT` under ADR-0033's rule (no `OPEN` ask on the task, the task not closed), unless the comment mentions other agents and not the assignee: then it is addressed to them. A comment that answered other agents' asks only does not wake the assignee either. A comment that mentions the assignee is a plain comment for it.

An answer through the answer endpoint wakes the agent that asked, with `ASK`, once no `OPEN` ask of that agent remains on the task. A reply on the linked issue that mentions agents is addressed to them and answers only their `OPEN` asks; a reply that mentions nobody answers every `OPEN` ask of the task. Then steps 1 to 3 apply, and the comments of one poll coalesce: the assignee's flag once, every pending wake stored before any starts. The decision on a repository request, and a task update that adds a requested repository, wake the agent that requested it.

**Pending wakes.** The port `TaskWakes` keeps `wake(task, trigger)` for the assignee and gains `wakeAgent(task, agent, trigger, comment)`. For the assignee it is `wake`, as before (a mention with `COMMENT`). For any other agent it is a row of `task_wake`, a queue of runs per task in the run module: `PENDING`, then `STARTED` with the run that took it, or `DROPPED` with the reason.
- Without an active run, the wake starts at once. During one, it stays `PENDING`. When a run ends, whatever its final status, cancels included, the oldest `PENDING` wake starts, with every `PENDING` wake of the same agent on the task in one run; only when none is left is the assignee's follow-up taken, as before. The chain is the mentions in the order they arrived, by agent, then the follow-up.
- Starting a wake needs the agent live, `ACTIVE` and with a credential; repository access is not a precondition. A closed task, or an agent that fails these, drops the agent's wakes with a `SYSTEM` comment, `{agent} was not started (mention): {reason}.`, and the next agent's wakes are tried.
- The run is created like any wake's: the trigger (`MENTION`, `ASK` or `REPOSITORY_REQUEST`; `MENTION` when it took a mention), the commenter as `created_by`, the comment in the `run.started` audit, and a workspace of the task's repositories the agent may access, each with its own read-only flag. Without any, the run has no workspace and no branch.
- Deleting an agent drops its `PENDING` wakes. Pausing it leaves them pending; they are dropped with the comment when their turn comes.
- `run.trigger` gains `MENTION`. The task's follow-up never is one.

**The run of a mentioned agent.**
- Its prompt has a `# Request` section after the task's description: who mentioned the agent, whose the task is, that it does what the comment asks and nothing more, and the comments as Markdown quotes, oldest first. Its "How to work" asks for the request only. When it resumes the agent's own earlier session on the task, the resume prompt starts with the same section and ends with `Do what the request asks. Finish with a short summary as before.`
- The `## Answers` section of a resume prompt lists only the answers to the agent's own asks.
- The end of the run is as for any run: the agent's comment with the summary, the git results, pull requests, and the asks of a `BLOCKED` result. Only the assignee's runs move the task's status (`TODO` to `IN_PROGRESS` at the start, to `IN_REVIEW` on success). A pull request opened by a mention run is the task's.
- The waiting-on state keeps its kinds and reasons. The web names the agent it is about: the agent of the active run, the agents that asked, the agent of the latest run for `BLOCKED`, the agent that requested a repository.

**Trackers.** Nothing changes in the write-back, the loop prevention or the `MENTION` rule, which still turns an issue without a task into a task. The earlier comments that a new task imports predate the task and mention nobody.

## Consequences

- People ask a colleague of the assignee in the conversation where the task lives, in Tinfirm or on the issue, without reassigning the task and without waking the assignee for work that is not its own. The test writer's scenarios, a reviewer's notes or an analyst's estimate arrive as that agent's comment on the task.
- A mention run can open a pull request on the task's branch, in a repository the mentioned agent may push. It is the task's pull request like any other, and the assignee's next run works on the same branch.
- A mentioned agent without repository access works from the task's text and its comments, with an empty scratch workspace, and puts its result in its summary.
- The assignee's follow-up waits behind the mentions that arrived during its run. A mention of a slow agent delays the assignee by that agent's run.
- A cancel stops the one run it names. Other people's requests stay queued and start in turn: the pending wakes of other agents, and the assignee's follow-up. A person who wants to stop them cancels those runs too.
- A member's plain comment that mentions other agents does not wake the assignee; the composer says so before it is sent ("Test writer will be asked to work on this task. Backend engineer is not woken by this comment.").
- An answer wakes the agent that asked, also when it is no longer the assignee, or on a task without one.
- Handles of existing agents are derived from their names without folding accents, `Vývojář` becomes `v-voj`; they can be edited. Earlier mentions in comment bodies are text and are not rewritten when a handle changes.
- On GitHub, `@handle` written as text may be a real GitHub login, which GitHub then notifies. That is harmless for Tinfirm, but worth knowing when choosing handles.
- The tracker offers no autocomplete for text handles; Tinfirm's composer does. An account per agent gives the tracker's autocomplete at the cost of a seat.
- Every mention costs a run, and a comment that mentions several agents costs one run each.
- The later `ask_agent` tool, with which an agent asks a colleague, is an agent mentioning another agent: it will build on the pending wakes, with an `AGENT` comment that mentions. Until then agents' comments never mention.

## Alternatives considered

- **Wake the assignee too.** Rejected by the owner: a comment addressed to another agent is not the assignee's work, and waking it costs a run that does nothing or, worse, does the other agent's part.
- **Always read-only.** Run the mentioned agent with every repository of the task, read-only. Rejected: it would widen what the agent may read beyond its own access, and a test writer that may push its test repository could not.
- **Widen access to the task's repositories.** Rejected by the owner: a mention is not a grant. An agent's access is set on the Agents page, by the people who may change the agent.
- **A menu instead of a textual mention.** "Ask an agent" as a button on the task. Rejected: it does not work from a tracker, where the conversation often is, and a comment carries the request in its own words.
- **Tracker accounts only.** Mention agents only by their Jira or GitHub account. Rejected as the baseline: an account per agent costs a seat or a licence, and does not work in Tinfirm. It stays as the optional upgrade.
- **A second active run per task.** Let the mentioned agent run beside the assignee. Rejected: both would work on the same branch and repositories at once, and the partial unique index, the follow-up flag and the waiting-on state all rest on one active run per task.
