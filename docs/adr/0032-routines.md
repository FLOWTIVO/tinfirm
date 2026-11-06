# ADR-0032: Routines create a task on a schedule

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: the owner's decision of 2026-10-03 (routines that create a task on a schedule); CONVENTIONS §14 and §19; [ADR-0004](0004-event-driven-wakes.md), [ADR-0019](0019-agent-created-tasks.md), [ADR-0029](0029-work-source-per-project.md)

## Context

- Some work recurs on a calendar: a report every month, a dependency check every week, a digest every night. Today a member has to remember it and create the task by hand each time.
- Agents wake only on events (ADR-0004): an assignment, a comment, an answer, a member's "run now". "A routine fires" is one of the wake sources, and a routine run creates a task first, so all work is visible. M2 plans routines with cron and webhook triggers and with concurrency and catch-up policies.
- The finished agent-control features already carry the rest: an agent attaches its report to the task (`upload_attachment`, ADR-0021), reports its result with `finish_run`, and the task then waits on a person in the inbox (CONVENTIONS §18). What is missing is something that creates the task at the right time.
- A project's work source decides where its tasks come from (ADR-0029). In a project that Jira or GitHub feeds, the issue owns the backlog: a task that Tinfirm creates by itself would be a second backlog next to the tracker's.
- Servers restart and can be down when a firing is due. Several replicas may run the worker role.

## Decision

**A routine creates a task on a schedule.** It belongs to a company, names a project with Tinfirm tasks and an agent, and holds the task's template: a title, a description and the repositories. Every firing creates one TODO task assigned to the agent, which wakes it with `ASSIGNMENT` like a member's task (CONVENTIONS §14.3). The agent does the work, attaches its report and finishes; the task then waits on a person for review. The first use is a report every month.

**Model** (migration `V031__routines.sql`, a new Spring Modulith module `routine`). A tenant table `routine` (company_id, forced row-level security, composite foreign keys, CONVENTIONS §7):

| Column | Meaning |
|---|---|
| `project_id`, `agent_id` | Where the tasks go and who gets them. The project's work source must be `TINFIRM`. |
| `name` | 1 to 100 characters, unique in the company (`409 DUPLICATE_ROUTINE_NAME`). |
| `enabled` | Default true. |
| `cron`, `time_zone` | A 5-field cron (`minute hour day-of-month month day-of-week`; `*`, numbers, ranges, steps and lists; days of the week 0 to 7, both 0 and 7 Sunday; a day matches either day field when both name days), stored as given, read in an IANA time zone. It must fire at most every 15 minutes. |
| `title`, `description` | The task's title template (1 to 200 characters; `{date}` becomes the firing's local date, else the date is appended in parentheses) and its Markdown description (at most 50 000). |
| `component_ids` | The task's repositories: null means every component of the project the agent may access at the firing, as for a member's task without `componentIds`; an empty array means none. |
| `overlap` | `SKIP` (default): no task while the routine's last task is open (not DONE or CANCELLED); `CREATE`: a task anyway. |
| `catch_up` | `NONE` (default): a firing missed for longer than `tinfirm.routine.missed-after` (default one hour) creates no task; `ONE`: it creates one. Either way the schedule continues with the first firing after now. |
| `next_run_at` | When the next firing is due. |
| `last_run_at`, `last_task_id`, `last_outcome`, `last_error` | The last firing: `CREATED`, `SKIPPED_OPEN`, `SKIPPED_MISSED`, `SKIPPED_TRACKER`, `SKIPPED_AGENT` or `FAILED` with the reason. `last_task_id` is the last task the routine created; a firing that creates none keeps it. |
| `disabled_reason` | Why the server disabled the routine: `agent.deleted`. |

A task gets `routine_id` (`ON DELETE SET NULL`); `Task.routineId` and `Task.routineName` show it, and `Task.createdBy` is the routine's name, as it is the agent's name for an agent's task (ADR-0019). Such a task has no creating member. A company has at most 50 routines. A `SECURITY DEFINER` lookup `routine_due(now, limit)` returns the ids and company ids of the enabled routines that are due, as V024's `tracker_poll_due` does.

**Firing.** Replicas with the `worker` role look for due routines every `tinfirm.routine.tick` (default 30 s) and fire each in its company's tenant context, in its own transaction, as actor SYSTEM:

1. Lock the routine (`FOR UPDATE SKIP LOCKED`; another replica may hold it) and check again that it is enabled and due.
2. Decide, in this order: the project no longer uses Tinfirm tasks (`SKIPPED_TRACKER`); the agent was deleted (`SKIPPED_AGENT`, and the routine is disabled with `agent.deleted`); the last task is open and `overlap` is `SKIP` (`SKIPPED_OPEN`); the firing is older than `missed-after` and `catch_up` is `NONE` (`SKIPPED_MISSED`, one record for any number of missed firings); else create the task.
3. Create the task through the work module's `ScheduledWork`: TODO, assigned to the agent, the project's next key, the title with the firing's local date, the description and repositories, `routine_id` set; audited as `task.created` with `cause` `routine` and `routineId`; the assignment wakes the agent.
4. Record the firing (`last_run_at` is the due time) and the next firing: the first one after now.
5. Audit `routine.fired` with the outcome, the task and the scheduled time.

A failure to create the task, for example an agent that lost access to a listed repository, rolls that transaction back; a second transaction records `FAILED` with the reason and moves the schedule on, so one bad firing does not repeat at every tick.

**Run now.** `POST .../routines/{id}/run` (OWNER, ADMIN, OPERATOR) fires the routine once at once, as the calling member (`cause` `routine.run`), with the same decision except the catch-up. It answers the outcome and the task when one was created, records the run as the last one, and does not move `next_run_at`. A disabled routine runs too.

**Changes.** Creating or changing a routine computes `next_run_at` from now; disabling keeps it, enabling computes it again and clears `disabled_reason`. Deleting a routine keeps its tasks. Deleting an agent disables its routines in the deleting transaction (`routine.updated`, `cause` `agent.deleted`); a member enables one again after choosing another agent. Deleting a component removes it from the routines that list it (`routine.updated`, `cause` `component.deleted`).

**The work-source rule.** A routine targets only a project with Tinfirm tasks: a create or change for a project that a tracker feeds is `409 PROJECT_USES_TRACKER`. A project with Tinfirm tasks cannot switch to a tracker while routines, enabled or not, target it: `409 PROJECT_HAS_ROUTINES`, naming them. The project module asks the routine module through a port, `RoutineUsage`, like `TrackerRuleUsage` (ADR-0029): the switch locks the project row before it asks, and saving a routine reads its project under a key-share lock, so the two cannot pass each other.

**Module boundaries.** `routine` depends on `work` (`ScheduledWork`), `project`, `agent` and `audit`; nothing depends on it. It implements the work module's port `RoutineNames` (the names on tasks) and the project module's `RoutineUsage`, and listens to `AgentDeleted` and `ComponentDeleted`.

**API and web.** Tag Routines: list (any member, keyset by name), create, get, change and delete (OWNER and ADMIN for changes), and run now; new permissions `MANAGE_ROUTINES` and `RUN_ROUTINES`. The company navigation gets a Routines page after Trackers: the routines with their schedule and zone, project, agent, next run, last run with its outcome and task, and why the server disabled one; a dialog with the next three firings computed in the browser; and Run now. A task a routine created links to it on its page and its board card.

## Consequences

- Every firing is visible work: a task on the board with its runs. An idle routine costs nothing, and no agent wakes without a task (ADR-0004).
- A monthly report is a routine with `0 9 1 * *` in the company's zone and a description that says where the report goes; the agent attaches it, and the task waits for a person's review.
- Firings missed while no worker ran are skipped, or caught up once; there is never a burst of tasks after downtime. A routine that keeps failing records why on its page and goes on with its schedule.
- The 15-minute floor keeps routines to calendar work; work that must react faster belongs to events.
- Projects that a tracker feeds get no routines for now: the tracker owns their backlog. A later version can create the issue in the tracker instead, and the tracker's rules then make it a task.
- The overlap rule looks only at the routine's last task. With `CREATE`, several tasks of a routine can be open at once.
- A routine's tasks outlive it; once it is deleted they show "deleted routine" as their creator.
- Scheduled firings are audited every time, so a routine that fires every 15 minutes writes about 100 events a day.

## Alternatives considered

- **A routine that wakes an agent without a task.** Rejected: all work is a task, so it shows on the board, waits on a person and can be reviewed.
- **Webhook triggers** (M2). Deferred: a schedule covers the first uses; a webhook needs an authenticated endpoint per routine.
- **Routines per project, on the project page.** Rejected: a routine names an agent and a project, and one company page lists them all.
- **db-scheduler for the firing** (ADR-0003). Not chosen for now: a tick over an indexed `next_run_at` with `SKIP LOCKED`, as the tracker's poller does, needs no new dependency and keeps the schedule in the routine's own row.
- **Run every missed firing.** Not chosen: after downtime it would create a burst of tasks for work that only needs doing once.
