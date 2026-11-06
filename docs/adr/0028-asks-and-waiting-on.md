# ADR-0028: Agents ask people, report a result, and every task says who it waits on

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: CONVENTIONS §8, §14.3, §14.4, §15.5, §15.6, §16, §17, §18; [ADR-0004](0004-event-driven-wakes.md), [ADR-0015](0015-multi-repository-tasks.md), [ADR-0019](0019-agent-created-tasks.md), [ADR-0024](0024-jira-tracker-connector.md)
- Amended by: [ADR-0029](0029-work-source-per-project.md), which keeps the board for projects with Tinfirm tasks, with the waiting-on badge on its cards, and states that asks, results and the waiting-on state apply to every task whatever its project's work source
- Amended by: [ADR-0033](0033-one-answer-per-ask.md), under which an answer through the answer endpoint answers only the ask it names, with the question quoted in its comment, and the agent is woken once no ask of the task is open; a member's comment answers every open ask only when the member says so, and a plain comment wakes nobody while asks are open; a reply on the linked issue still answers every open ask
- Amended by: [ADR-0034](0034-mention-an-agent.md), under which an answer wakes the agent that asked, not the task's assignee, and a reply on the linked issue that mentions agents answers only their open asks
- Amended by: [ADR-0036](0036-questions-end-a-run-quietly.md), under which a run without a result that ends while a question or a repository request of its own waits posts no end-of-run comment, and the agent stops without a summary

## Context

- An agent could report only at the end of its run, and only as text: the summary is its last message (a v0 shortcut of CONVENTIONS §16). An agent that needs a decision ends the run and hopes that someone reads the question in the summary. Nothing marks the task as waiting for an answer, and in Jira the question is one line in a long end-of-run comment.
- Who has to act was worked out in the browser: the overview ran an if/else chain over task fields (repository requests, the last run's status, `IN_REVIEW`), and every other view would have needed its own copy. There should be one "waiting on" state that the server owns and every view reads.
- Repository requests (ADR-0015) already have the shape an ask needs: a frame from the runner host, checks on the server, exactly one ack, a member's decision and a wake.
- Work comes from the company's Jira (ADR-0024), and people answer where they already work: on the issue.

## Decision

**Asks.**
- The runner host offers two more platform tools (CONVENTIONS §15.6). `ask_human` (`question`, optional `options`) sends `run.ask` and waits for `run.ask.ack`, like `request_repository`. `finish_run` (`outcome` `COMPLETED` or `BLOCKED`, `summary`, optional `nextSteps` and `openQuestions`) sends nothing: the host keeps the last call, and `run.finished.result` carries it. The protocol stays v1; both messages are new types and `result` is a new optional field.
- The server answers every `run.ask` with exactly one ack. It checks, in order: the run is `RUNNING` on this host with this epoch (`RUN_NOT_ACTIVE`); the same `requestId` of the run gets the same `PENDING` answer; the task is open (`TASK_CLOSED`); the question has 1 to 2000 characters and at most 10 options of 1 to 100 (`INVALID_REQUEST`); the run asked fewer than 10 questions (`TOO_MANY_ASKS`).
- A stored ask (table `ask`, `V026__asks.sql`) is also an `AGENT` comment of the task, without a run id: the question, its options as a list, and `Reply to this comment to answer.` The existing write-back sends it to a linked issue as a plain comment.
- **Any comment of a person answers every open ask of the task**: a member's comment, the answer endpoint (it posts the member's comment), or an `EXTERNAL` comment that the poller imports from the issue. All three go through one method, and the agent is then woken once with the trigger `ASK` instead of `COMMENT`. The resume prompt starts with `## Answers`: a `**Q:**` and an `**A:**` line per ask answered since the agent's previous run.
- Closing a task cancels its open asks.

**Results.**
- A `run.finished` with a result stores it on the run, and its summary becomes the run's summary.
- `BLOCKED` keeps the task's status. The end-of-run comment lists the next steps and the open questions, and each open question becomes an ask that points at that comment, so the issue gets one comment.
- A run that leaves an open ask stays out of `IN_REVIEW`, like a run with a repository request.

**Waiting on.**
- Each task stores `waiting_on` (`HUMAN`, `AGENT`, `RUNNER`, `NONE`), `waiting_reason` and `waiting_since`. One resolver computes them from the task's status and assignee, its latest run (status, queue reason, result outcome), its open asks and its pending repository requests, with a fixed precedence (CONVENTIONS §18.5). `V026` fills them for existing tasks.
- The resolver runs **in the same transaction as every change of an input**, and writes only when the state changed, so `waiting_since` is when it last changed. The transactions are:
  - work module: creating a task (by a member, by an agent with `create_task`, from a Jira issue); updating a task (status, assignee, repositories), closing included; a member's comment and an imported `EXTERNAL` comment that answer asks; answering an ask; storing an ask; turning open questions into asks; storing, approving, denying and cancelling a repository request; deleting an agent (its tasks lose their assignee);
  - run module, through `TaskBoard.refreshWaitingOn`, `runStarted` or `runSucceeded`: creating a run (a wake); a new queue reason code; assigning a run to a host; requeueing it after an accept timeout, a rejection or a reconnect; starting or resuming it; ending it (succeeded, failed, cancelled, lost).
- The API shows `Task.waitingOn`, `waitingReason` and `waitingSince`. The project task list filters by `waitingOn`, and a new company-wide task list (`GET /api/companies/{companyId}/tasks`) is ordered by `waitingSince`, longest waiting first. Asks have their own tag (`Asks`): list, list per task, and answer.
- The web app gets an Inbox page (open asks with an answer form, then the tasks that wait on a person by reason), a badge on the task page, and an overview that counts the tasks per `waitingOn` and lists those that wait on a person. The overview's own rules are gone.

## Consequences

- One state answers "who has to act" for every view and, later, for the tracker. The inbox needs no client logic, and a new reason is added in one place.
- **Every writer of an input must call the resolver.** A missed call leaves a stale state until the next change of that task. The list above is the contract; new code that changes a task's status, assignee, runs, asks or requests adds itself to it. The resolver reads one row per input, so a call is cheap.
- An agent that needs a person ends its turn at once; the run finishes, and the answer starts a new run that resumes the session. No sandbox or credential slot waits for a person.
- **A comment answers every open ask, also when it was not meant as an answer.** The agent gets the comment as the answer and in its new comments, and can ask again. Jira Data Center comments have no threads to tell answers apart, so the rule is the same everywhere.
- An answer given in Tinfirm reaches the issue too: a member's comment that answered an ask is written there as the connection's account, with `*{member name} (via Tinfirm):*` in front, so the tracker holds the whole conversation. Other member comments stay in Tinfirm. The member's display name goes to the issue with the answer, and only then. An answer given in Jira reaches both, as an imported comment.
- An ask's question and answer are free text from the agent and from people. The audit log keeps ids and keys only (`ask.created`, `ask.answered`, `ask.cancelled`), and logs never contain them.
- A prompt-injected agent can use `ask_human` to ask a person for a secret, or to flood a task with questions: threat model 7.11 and 7.12. The limits are 10 asks per run and the prompt's rule never to ask for credentials; the answer is a normal comment, which the agent reads like any other.
- The project board keeps its own "Needs you" mark for repository requests until the board goes.

## Alternatives considered

- **Compute the state when it is read.** A view or a query over runs, asks and requests: always current, but the inbox orders and filters by it, which needs the same joins on every list and gives no `waiting_since`. Rejected for a stored column.
- **Recompute after the commit, from events.** Keeps the resolver out of the writers' transactions, but the state is late, and an event is lost when the server stops between the commit and the listener (the gap ADR-0024 accepts for the write-back). Rejected: the state must change together with its inputs.
- **An ask that blocks the tool until a person answers.** The agent would keep its context, but the sandbox and a credential slot would wait for hours, and a restart would lose the run. Rejected: the run ends, and the answer resumes the session in a new run.
- **Answer one ask per comment, by reply.** Precise, but neither Tinfirm nor Jira Data Center comments have replies, and a person who answers two questions in one comment would answer only one. Not discussed.
- **Keep the summary as the only result.** Kept as the fallback when the platform tools are not available; `finish_run` adds the outcome and the lists that the server needs to decide the task's status.
