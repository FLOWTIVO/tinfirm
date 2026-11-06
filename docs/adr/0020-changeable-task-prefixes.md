# ADR-0020: Changeable task prefixes

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Proposed)
- Date: 2026-10-01
- Sources: CONVENTIONS §7, §14.1, §14.4; [ADR-0018](0018-project-task-prefixes.md), [ADR-0009](0009-rls-tenant-isolation.md)
- Changes: the "can't be changed" rule of ADR-0018. The rest of ADR-0018 stays.

## Context

- ADR-0018 gave every project a task prefix that can't be changed. Projects that V018 migrated got a generated prefix such as `MYCO2`, and members can't fix a prefix they chose badly.
- Members want to change it. The initial prefix is still suggested from the project name, or typed when the project is created.
- A key names the working branch `agent/<KEY>` and its pull requests (CONVENTIONS §14.4), so keys must stay unique within the company and never be reused. ADR-0018 rejected a changeable prefix for this reason: another project could take an old prefix and meet old keys.

## Decision

- `ProjectUpdateRequest.taskPrefix` changes the prefix (OWNER, ADMIN; the rule `^[A-Z][A-Z0-9]{1,9}$`).
- Existing tasks keep their keys, and so their branches and pull requests. New tasks take the new prefix and the next number of the same project counter, e.g. `WEB-12`, then `SITE-13`.
- Every prefix a project has used stays **reserved** for it. No other project can take it, on create or on update (`409 DUPLICATE_TASK_PREFIX`). The project itself can take an old prefix back: its counter never goes back, so its new keys don't meet its old ones.
- `project.updated` gains `details.taskPrefix` and `details.previousTaskPrefix` (null when unchanged).

### Data model: migration `V020__project_task_prefix_history.sql`

| Table | Column | Type | Null |
|---|---|---|---|
| `project_task_prefix` | `company_id` | `uuid` | no |
| `project_task_prefix` | `project_id` | `uuid` | no |
| `project_task_prefix` | `task_prefix` | `varchar(10)` | no |
| `project_task_prefix` | `created_at` | `timestamptz` | no |

- Primary key `(company_id, task_prefix)`: the database keeps a prefix with one project, also when two requests race.
- The table is a tenant table with row-level security (ADR-0009). The app role may `SELECT` and `INSERT`; rows are never deleted while projects are never deleted.
- The migration copies the current prefix of every project. `project.task_prefix` stays the current prefix.

## Consequences

- Members can rename the generated prefixes from V018 and their own.
- One task can have a key with a different prefix from its project, e.g. `WEB-12` in a project now called `SITE`. Both keys stay unique, so branches stay unique.
- Old prefixes stay reserved, even if their project has no tasks with them. Prefixes are cheap and the rule stays simple.
- If projects ever get deleted, their reserved prefixes must stay while tasks with those keys exist.

## Alternatives considered

- **Rename the keys of existing tasks.** That breaks the branches, pull requests and commit messages that name the old keys. Rejected.
- **Reserve an old prefix only while tasks with it exist.** The work module owns the tasks, so the project module would have to query their keys. Concurrent requests could also race between the check and the insert. Rejected for the history table, which the database enforces.
- **Restart the numbers at 1 with a new prefix.** If the project ever took an old prefix back, its new keys could meet its old ones. Rejected: the counter carries on.
