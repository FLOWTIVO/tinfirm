# ADR-0018: Task keys per project

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Proposed; the "can't be changed" rule is changed by [ADR-0020](0020-changeable-task-prefixes.md))
- Date: 2026-09-30
- Sources: CONVENTIONS §7, §14.1, §14.4; [ADR-0009](0009-rls-tenant-isolation.md)

## Context

- A task's key was the company issue prefix plus a per-company number, e.g. `MYCO-12`, so every task of every project started with the same prefix.
- Members want to tell the project of a task from its key, e.g. `WEB-12` for the website and `API-7` for the server.
- The key names the working branch `agent/<KEY>` in every repository of the task (CONVENTIONS §14.4), so keys must stay unique within the company and never be reused.

## Decision

- Every project has a **task prefix**: 2 to 10 capital letters or digits, from a letter (`^[A-Z][A-Z0-9]{1,9}$`, the rule of the issue prefix). It is required on create, unique within the company (`409 DUPLICATE_TASK_PREFIX`) and can't be changed.
- `Project.taskPrefix` returns it; `ProjectCreateRequest.taskPrefix` sets it. `ProjectUpdateRequest` does not have it. `project.created` gains `details.taskPrefix`.
- A task's key is the task prefix of its project plus a per-project number that increases by 1, e.g. `WEB-12`. The counter lives on the project row, which is locked when the next number is taken.
- The company issue prefix stays: it is the company's short code, and the web app suggests a task prefix from the project name as it suggests the issue prefix from the company name.

### Data model: migration `V018__project_task_prefix.sql`

| Table | Column | Type | Null | Default |
|---|---|---|---|---|
| `project` | `task_prefix` | `varchar(10)` | no | – |
| `project` | `task_counter` | `bigint` | no | `0` |

- `company.task_counter` is dropped.
- `project_company_task_prefix_uq UNIQUE (company_id, task_prefix)`.
- `task_company_number_uq` becomes `task_project_number_uq UNIQUE (project_id, number)`, and `task_company_key_uq UNIQUE (company_id, key)` is added.

Existing projects:
- The oldest project of each company takes the company issue prefix, so its keys carry on as before.
- Every other project takes the issue prefix plus its position among the company's projects, e.g. `MYCO2`, cut to ten characters; if that is taken, the next number.
- Every counter starts at the company counter, so no new number or key meets an existing one. Existing tasks keep their keys, and so their branches and pull requests.

## Consequences

- Keys tell the project. Numbers are per project, so `WEB-3` and `API-3` can both exist.
- Existing projects other than the oldest get a generated prefix, which can't be changed. Their numbers do not start at 1.
- Moving a task to another project would change neither its key nor its branch; Tinfirm has no such move today.
- Projects can't be deleted today. If deletion comes, a prefix must stay reserved while tasks with its keys exist.

## Alternatives considered

- **A changeable task prefix.** Keys already name branches and pull requests, so old tasks would keep the old prefix and a new project could take it and meet old keys. Rejected: a fixed prefix keeps keys unique without a history of prefixes.
- **Company prefix plus a project code, e.g. `MYCO-WEB-12`.** Longer keys and branch names for no gain within one company.
- **An optional task prefix that falls back to the company one.** Two projects would share the company's number sequence and prefix, which is the problem this ADR solves.
