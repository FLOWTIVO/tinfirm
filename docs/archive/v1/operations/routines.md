# Routines: tasks on a schedule

A routine creates a task for an agent on a schedule, for example a report on the first of every month. This guide is for the owners and admins who set routines up and for the operators who run the server. The rules are in [CONVENTIONS §19](../CONVENTIONS.md#19-routines), the decision in [ADR-0032](../../../adr/0032-routines.md).

## 1. How it works

| Step | What happens |
|---|---|
| Firing | When the routine's schedule comes due, a replica with the `worker` role creates one task in the routine's project: status Todo, assigned to the routine's agent, with the routine's title, description and repositories. |
| Wake | The new task wakes the agent like a member's task. A task that a routine created says "from routine {name}" on its page and on the board. |
| Work | The agent does the work, attaches its report to the task and finishes. |
| Review | The task waits on a person in the inbox, as every task does. |

A routine never wakes an agent without a task, and an idle routine costs nothing.

## 2. Requirements

- A project whose work source is **Tinfirm tasks**. A project that Jira or GitHub feeds takes no routines: its backlog lives in the tracker. While a routine targets a project, the project cannot switch to a tracker; delete the routine or move it to another project first.
- A live agent of the company. It needs access to every repository the routine lists.
- At least one server replica with the `worker` role (section 6).

## 3. Create a routine

Owners and admins open **Routines** in the company navigation and choose **New routine**.

| Field | Meaning |
|---|---|
| Name | Unique in the company, at most 100 characters |
| Project | A project with Tinfirm tasks |
| Agent | Who gets every task |
| Schedule | A cron, read in the time zone (section 4). The dialog shows the next three runs. |
| Time zone | An IANA time zone such as `Europe/Prague`; the browser's zone by default |
| Task title | `{date}` becomes the run's date, e.g. `Monthly report {date}` gives `Monthly report 2026-11-01`. Without `{date}` the date is added in parentheses. |
| Task description | What the agent should do each time, and where the result goes. Markdown. |
| Repositories | Every repository of the project the agent can access, decided at each run; or a fixed choice, possibly none |
| While the last task is open | Skip (default): no new task until the last one is done or cancelled. Create a task anyway: every run creates one. |
| Missed runs | Skip (default): a run missed for over an hour creates no task. Catch up once: one task for the missed runs. |
| Enabled | Off: the routine does not run on its schedule. |

A company has at most 50 routines.

## 4. Schedules

A schedule has five fields: `minute hour day-of-month month day-of-week`.

| Field | Values |
|---|---|
| minute | 0-59 |
| hour | 0-23 |
| day-of-month | 1-31 |
| month | 1-12 |
| day-of-week | 0-7; 0 and 7 are Sunday, 1 is Monday |

Each field is `*` (any), a number, a range `1-5`, a step `*/15` or `0-30/15`, or a list such as `1,15`. Names such as `MON` or `JAN` are not supported. When both day fields name days, a day matches either of them: `0 9 13 * 5` runs on the 13th and on every Friday.

| Schedule | Runs |
|---|---|
| `0 9 1 * *` | 09:00 on the first of every month |
| `30 7 * * 1-5` | 07:30 on weekdays |
| `0 18 * * 5` | 18:00 every Friday |
| `0 6 1 1,4,7,10 *` | 06:00 on the first day of each quarter |
| `*/15 8-17 * * 1-5` | Every 15 minutes during office hours |

A routine runs at most every 15 minutes; a schedule that would run more often, or never (30 February), is refused with the reason.

The schedule is read in the routine's time zone. When the clocks go forward, a time that does not exist that night runs at the same clock time after the change (02:30 becomes 03:30). When the clocks go back, a time that happens twice runs once.

## 5. Run now, outcomes and failures

**Run now** (operators, admins and owners) runs the routine once, at once, with the same rules as the schedule except the missed-run rule, and shows what happened. It does not move the next scheduled run. A disabled routine can be run this way, for example to try it before enabling it.

The Routines page shows each routine's next run and its last run:

| Last run | Meaning |
|---|---|
| created | A task was created; its key links to it. |
| skipped: the last task was still open | "Skip while the last task is open" held the run back. |
| skipped: missed while Tinfirm was down | The run was more than an hour late, and missed runs are skipped. |
| skipped: the project's tasks come from a tracker | The project no longer uses Tinfirm tasks. |
| skipped: the agent was deleted | The routine was disabled; choose another agent and enable it. |
| failed | The task could not be created; the reason shows below, e.g. the agent lost access to a listed repository. |

A failed or skipped run does not repeat: the schedule goes on with the next run. Deleting the agent disables its routines; deleting a repository removes it from the routines that list it. Deleting a routine keeps the tasks it created.

## 6. Operations

| Item | Value |
|---|---|
| Where routines run | On replicas with the `worker` role (`TINFIRM_ROLES`, default `api,worker`; Helm value `serverRoles`). With no worker replica, no routine runs. |
| Tick | Every 30 s a worker looks for due routines (`tinfirm.routine.tick`). A run starts within one tick of its time. |
| Several workers | Each due routine is locked while it runs; a routine that another replica holds is left to it, so a run happens once. |
| Downtime | A run more than an hour late (`tinfirm.routine.missed-after`) counts as missed and follows the routine's missed-runs choice. A restart within the hour runs it late. |
| Audit | `routine.created`, `routine.updated`, `routine.deleted` and `routine.fired` for every run, with the outcome and the task; the task itself as `task.created` with the cause `routine`. |

The two settings have no environment variable; their defaults suit every deployment.
