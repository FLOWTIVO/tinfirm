# ADR-0019: Agents create their own tasks

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Proposed)
- Date: 2026-10-01
- Sources: CONVENTIONS §8, §14.1, §14.3, §14.7, §15.5, §15.6, §16; [ADR-0004](0004-event-driven-wakes.md), [ADR-0015](0015-multi-repository-tasks.md)
- Amended by: [ADR-0035](0035-agent-usage-policy.md), under which `create_task` assigns only an agent that the creating agent's owner may use

## Context

- While it works on a task, an agent often finds work that does not belong to it: a bug next to the change, a follow-up, a cleanup, a missing test.
- Today it can only do that work in the same task, which makes the change and its pull requests larger than the task asked for, or mention it in its summary, where a member has to notice it and write a task by hand.
- "Keep it focused" is part of every prompt (CONVENTIONS §15.5). The agent needs a way to put the rest somewhere.
- Delegation happens only through stages. More is needed: an agent that finds work for a colleague, for example a backend agent that finds a frontend change, should be able to hand it over. What must stay with a member is the decision to **start** work.
- Wakes are events (ADR-0004). A task an agent creates must not become a loop where runs create runs.
- The platform MCP tools (CONVENTIONS §15.6) and the request/ack pattern of `request_repository` (ADR-0015) already exist.

## Decision

**The platform tool `create_task`**
- Input: `{"title": string, "description"?: string, "repositories"?: [string], "assignee"?: string}`. `title` is required. `repositories` are component names of the project, as `list_repositories` shows them; a run's repository may also be named by its `dir`. `assignee` is the name of an agent of the company; without it the task is the run's agent's.
- The runner host trims the values, drops duplicate names (ignoring case), redacts the texts like everything that leaves the host, and cuts `title` to 200 and `description` to 50 000 characters. An empty title, a value of the wrong type, a name over 100 characters, a blank or too long assignee, or more than 20 names are error results, and nothing is sent.
- The host sends `run.task_create` with a new `requestId` and waits up to 30 s for `run.task_create.ack`, as for `run.repository_request` (resent after a reconnect, never buffered). `CREATED` is a normal result, `REJECTED` an error result; both carry the ack's `message`.

**What the server creates**
- A task in the project of the run's task, with the next key of that project.
- Status `BACKLOG`, assignee the named agent, or the run's agent without `assignee`. Nothing runs until a member moves the task to `TODO`, which wakes the assignee with `ASSIGNMENT` as for any task (CONVENTIONS §14.3). So an agent can create work for itself or a colleague, but never start it.
- Repositories: the named components; without `repositories`, the repositories of the run's task that the assignee may access, in task order. An empty array means none. The assignee must have access to every repository of the task, as for a task a member creates (CONVENTIONS §14.7).
- It records the creating agent, the run's task (`origin_task_id`), the run and the frame's `requestId`. `Task.createdBy` is the agent's name, and `Task.createdByAgentId`, `Task.originTaskId` and `Task.originTaskKey` are new.
- It audits `task.created` with actor `AGENT` and the usual details plus `originTaskId`, `originTaskKey` and `runId`.
- It posts a `SYSTEM` comment on the run's task: ``{agent} created the task {KEY}: {title}. It waits in the backlog until a member starts it.``, with `` for {assignee}`` after the key when the assignee is another agent.
- The prompt names the company's other live agents (at most 20, by name) in the line about `create_task`, so the agent knows whom it can assign.

**Checks, in order.** The first failure is the answer, `REJECTED` with its `code`, and nothing is stored.
1. The run is `RUNNING` with this epoch on this host; else `RUN_NOT_ACTIVE`.
2. If a task with the same `(runId, requestId)` exists, the answer is `CREATED` with that task again.
3. The title is not blank and at most 200 characters, the description at most 50 000, there are at most 20 repository names, none blank or over 100 characters, and an assignee, if given, is not blank and at most 100 characters; else `INVALID_REQUEST`.
4. The assignee is a live agent of the company, matched by name exactly, then ignoring case; else `UNKNOWN_AGENT`, whose message lists the company's agents (at most 20).
5. Each repository name is a component of the project, matched exactly, then ignoring case; else `UNKNOWN_REPOSITORY`.
6. The assignee has access to each; else `NO_ACCESS`.
7. The run has created fewer than 10 tasks; else `TOO_MANY_TASKS`.

The `CREATED` message is written for the agent: ``Created the task {KEY} ("{title}") in the backlog of the project, assigned to {you, or the assignee's name}. A member decides when it starts, and it runs on its own, so do not do that work in this task. Mention {KEY} in your summary.``

**Prompt.** Every prompt gets one line in `# How to work`, after "keep it focused": find work outside the task, do not do it there, call `create_task` with a title and a description that stands on its own; when the company has other agents, ``If another agent of the company should do it, set `assignee` to its name: `A`, `B`.``; without the Tinfirm tools, describe that work in the summary.

**Runner protocol v1, changed in place.** New messages `run.task_create` (runner host to server) and `run.task_create.ack` (server to runner host). Like `run.repository_request`, `run.task_create` renews the run's lease and is always answered with exactly one ack, also for a stale epoch or an unknown run.

### Data model: migration `V019__agent_created_tasks.sql`

| Column of `task` | Type | Null | Meaning |
|---|---|---|---|
| `created_by_agent_id` | `uuid` | yes | The agent that created the task (the run's agent, whoever the assignee is) |
| `origin_task_id` | `uuid` | yes | The run's task |
| `origin_run_id` | `uuid` | yes | The run in which the agent called the tool |
| `origin_request_id` | `uuid` | yes | `run.task_create.requestId` |

- `task_created_by_agent_fk FOREIGN KEY (created_by_agent_id, company_id) REFERENCES agent (id, company_id)`
- `task_origin_task_fk FOREIGN KEY (origin_task_id, company_id) REFERENCES task (id, company_id)`
- `task_origin_run_fk FOREIGN KEY (origin_run_id, company_id) REFERENCES run (id, company_id)`
- `task_origin_uq UNIQUE (origin_run_id, origin_request_id)`: idempotency, and the tasks of a run (`TOO_MANY_TASKS`)
- `task_origin_ck`: all four columns are set, and `created_by` is null, or none of them is set.

The table keeps its policy and grants. Existing tasks get nulls.

## Consequences

- Agents keep their changes focused and still leave the rest of their findings as work a member can start with one click.
- Agents can hand work to each other, but a member stays in control: nothing an agent creates runs, or costs subscription capacity, before a member moves it to `TODO`. Runs cannot create runs.
- The assignee may belong to another member (another owner's subscription). It only spends that capacity once a member starts the task, and that member sees who created it and from which task.
- The backlog can fill up with agent ideas. The limit of 10 per run bounds it; the board shows who created a task and from which task.
- The new task does not see the conversation of the task it came from, so its description must stand on its own. The prompt and the tool description say so.
- `Task.createdBy` is no longer always a member: it can be an agent's name. `createdByAgentId` tells them apart.
- Protocol v1 changes in place, so the server and the runner host must be deployed together. An older server answers `run.task_create` with `error UNKNOWN_TYPE`, and the tool call times out; an older runner host offers no `create_task`.
- The platform MCP endpoint is still guarded only by the secret in its URL (CONVENTIONS §16): a process that learns a run's URL can now also create backlog tasks in that run's name. They do not run without a member.

## Alternatives considered

- **Start the new task at once (`TODO`).** Rejected: a run could create runs without a member, a self-driving loop, and it spends subscription capacity no member asked for.
- **Only the run's agent as assignee.** Rejected: members want agents to hand work to the agent that fits it, and the backlog keeps the start with a member.
- **An unassigned task.** Not chosen: the agent that found the work has the context and the access, and a member who agrees only has to move it to `TODO`. A member can still reassign or clear the assignee.
- **Subtasks with a parent link and blockers**. Not chosen for now: it needs a task hierarchy in the API and the board. `originTaskId` records where a task came from without making it a child.
- **Only the summary.** The status quo: the work gets lost unless a member writes it down.
