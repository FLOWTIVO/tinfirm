# ADR-0029: A work source per project

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: the owner's decision of 2026-10-03 (a work source per project); CONVENTIONS §6, §8, §14.1, §14.2, §15.5, §15.6, §17.2, §17.3, §17.4, §18; [ADR-0018](0018-project-task-prefixes.md), [ADR-0019](0019-agent-created-tasks.md), [ADR-0020](0020-changeable-task-prefixes.md), [ADR-0024](0024-jira-tracker-connector.md), [ADR-0028](0028-asks-and-waiting-on.md)
- Changes: the plan of ADR-0024 and ADR-0028 that the board, task prefixes and agent-created tasks go. They stay, for projects without a tracker.
- Amended by: [ADR-0030](0030-github-issues-tracker.md), which adds the work source `GITHUB` (GitHub Issues, not GitLab issues, is the second source), fed by connections of kind `GITHUB` under the same rules as `JIRA`
- Amended by: [ADR-0032](0032-routines.md), under which a project with Tinfirm tasks switches to a tracker only while no routine targets it (`409 PROJECT_HAS_ROUTINES`), asked through the port `RoutineUsage`, and a routine targets only a project with Tinfirm tasks

## Context

- Tinfirm is not a task tracker and not a board. ADR-0024 planned to remove projects as task containers, task keys and prefixes, the board and agent-created tasks once the Jira connector carries the work.
- Not every company has a tracker, and not every project of a company that has one lives in it: a team without Jira, or a project that is not in Jira yet. Removing the board leaves them no place to put work.
- Where a project's work does come from Jira, Tinfirm's own backlog competes with it. A member's task or an agent's `create_task` is work that the issue tracker does not know, a second backlog that drifts from the real one (ADR-0024, alternatives). The issue owns the backlog and the workflow there.
- A task made from an issue got a generated key, e.g. `WEB-12`, so its branch `agent/WEB-12` and its merge request `WEB-12: …` named a key that nobody in Jira knows.
- On 2026-10-03 the owner replaced "strip the tracker" with a work source per project: the tracker parts stay for companies and projects without a tracker, and a project that the company's Jira feeds behaves as tracker-fed.

## Decision

**Each project has a work source** (`Project.workSource`, `project.work_source`):
- `TINFIRM`: Tinfirm's own tasks. The board, the new-task dialog, task prefixes and keys, and `create_task`, all exactly as before.
- `JIRA`: the tasks come from the rules of a tracker connection (ADR-0024). The Jira issue owns the backlog and the workflow.

The enum is open for later sources (GitLab issues is the planned second one, then GitHub issues or Jira Cloud), but it has only these two values now, and no placeholder. A create without `workSource` gets `TINFIRM`. Whoever may update a project (OWNER, ADMIN) may change it; there is no new permission. `project.created` and `project.updated` record `workSource` in `details`, and `project.updated` also `previousWorkSource` when it changed.

**The internal source is frozen.** The freeze covers the tracker parts only: the board, task prefixes and keys, the new-task dialog, and `create_task`'s semantics. They stay as they are and get no new tracker features; every later investment in where work comes from goes into connectors. The freeze does not cover agent control. Everything in CONVENTIONS §18 (asks, answers, the inbox, the waiting-on state, `finish_run` results) and §14 (runs, comments, repository requests, pull requests) applies to every task whatever its project's work source, today and for every later feature: a new agent-control feature must work for `TINFIRM` and `JIRA` tasks alike.

**A `JIRA` project**
- `POST /projects/{id}/tasks` by a member is `409 PROJECT_USES_TRACKER`: the detail says to create the issue in Jira, where a tracker rule makes it a task.
- `create_task` in a run of such a project's task answers `REJECTED` / `PROJECT_USES_TRACKER`: ``The tasks of this project come from Jira, so no task was created. Put this work into `nextSteps` when you call `finish_run`; a person creates the issue in Jira.`` The check comes right after step 2 of ADR-0019 (a repeated frame gets its first answer), before the request is validated.
- The prompt (CONVENTIONS §15.5) replaces the `create_task` line with one sentence: work outside the task goes into the `nextSteps` of `finish_run`, and a person creates the issue in Jira. It names no other agents. The prompt of a `TINFIRM` project stays word for word.
- A task made from an issue takes the project's next number, and the issue's key as its key when that has at most 32 characters and the form `^[A-Z][A-Z0-9_]*-[0-9]+$` (e.g. `PROJ-123`); otherwise it gets a key from the project's prefix. Its branch is `agent/PROJ-123` and its merge requests are titled `PROJ-123: …`. When another task of the company has the issue's key (`task_company_key_uq`), that issue fails in the poll, with a reason that names the key (CONVENTIONS §17.3 step 4). Keys of existing tasks do not change, because branches derive from keys.
- Keys are one namespace per company, so a generated key that a task from an issue already holds is skipped: the project takes its next number instead.

**A `TINFIRM` project**
- Nothing changes.
- A tracker rule may not target it: `PUT .../rules` answers `400 INVALID_TRACKER_RULE` with `Rule N: project X uses Tinfirm tasks; set its work source to Jira first`. A connection's kind names the source its rules need (`JIRA_DC`: `JIRA`).

**Switching**
- `TINFIRM` to `JIRA` is allowed at any time. Existing tasks keep working and keep their keys; new work comes from the rules.
- `JIRA` to `TINFIRM` is `409 PROJECT_HAS_TRACKER_RULES` while any rule of any connection, enabled or not, targets the project.
- Tasks linked to issues stay linked and keep syncing, whatever the source.

**Module boundaries.** The work, run and tracker modules read a project's source through the project module's API (`ProjectCatalog.project`, `ProjectCatalog.lockProjects`, `ProjectView.workSource`), each in one place: the work module in `WorkSourceRules`, the run module where it builds `run.assign`, the tracker module where it checks a rule. The project module asks for rules through a port, `TrackerRuleUsage`, that the tracker module implements, like `ModelCredentialUsage` (ADR-0027); `project` does not depend on `tracker`. A switch locks the project row (`FOR UPDATE`) before it asks; saving rules reads their projects under `FOR KEY SHARE`. So a rule saved while a project switches is either seen by the switch or refused by the rule check, and taking a task number (`FOR NO KEY UPDATE`) waits for neither.

**Web.** The project create form and the project settings offer the two sources with one line each; the settings show `PROJECT_HAS_TRACKER_RULES` with a link to the Trackers page. A `TINFIRM` project keeps its board, now with the waiting-on badge on the cards. A `JIRA` project shows a list only (key linked to the task, title, waiting-on badge and since, assignee, status, pull requests; filters for status, assignee and `waitingOn`) with a line that tasks arrive from the tracker rules, and no board, no view switch and no new-task button. The projects list labels each project with its source, and the rules editor offers only `JIRA` projects. The web reads `Project.workSource` and computes nothing of its own.

### Data model: migration `V027__project_work_source.sql`

| Table | Column | Type | Null | Default |
|---|---|---|---|---|
| `project` | `work_source` | `varchar(16)` | no | `'TINFIRM'` |

- `project_work_source_ck CHECK (work_source IN ('TINFIRM', 'JIRA'))`.
- Every project that an existing `tracker_rule` targets becomes `JIRA`. Nothing is dropped: task prefixes, their history (V020), keys and the columns of agent-created tasks (V019) stay.

## Consequences

- One app, two experiences: a board where Tinfirm holds the backlog, a list where the tracker holds it. Both read the same task, the same runs and the same waiting-on state, and the inbox mixes both.
- Jira keys and internal keys share the company's keys. A generated key skips a key an issue took, so a project's numbers can have gaps. An issue whose key a task already has fails, e.g. when a `TINFIRM` project's prefix equals a Jira project key and its counter got there first. Keys never change, so that issue does not become a task; the poll names it with the key. Choosing task prefixes that are not Jira project keys avoids it.
- Branches and merge requests of new tasks from issues name the issue, so people find the change from the issue's key. Existing tasks keep their generated keys.
- A `JIRA` project without a rule receives no work. Its empty list says where tasks come from.
- A project that switches to `JIRA` keeps its internal tasks, their runs and their keys, but takes no new ones from members or agents. Projects that rules targeted before V027 are `JIRA` from then on, with their internal tasks.
- `create_task` stays among the platform tools of every run; in a `JIRA` project it answers `REJECTED`, and the prompt does not mention it. The protocol changes by one code value, and the runner host is unchanged.
- A later source adds a value here, a connection kind that names it, and a connector; nothing else of this decision changes. GitLab issues is planned next.
- Agent-control features stay shared: the freeze is no reason to build one for one source only.

## Alternatives considered

- **Strip the tracker** (the plan of ADR-0024). Companies and projects without a tracker would have no place to put work. Rejected by the owner on 2026-10-03.
- **A company-wide switch instead of one per project.** Simpler, but a company that uses Jira for some teams and nothing for others could not have both, and a project that moves to Jira would move the whole company. Rules already name a project each, so the source belongs to the project.
- **Allow manual tasks and `create_task` in Jira projects.** Keeps the agents' hand-over, but builds the second backlog that drifts from the tracker's. Work outside the task goes into the result's next steps, and a person files the issue.
- **Give existing linked tasks their issue keys.** Branches, merge requests and commit messages name the old keys (ADR-0020); rejected.
