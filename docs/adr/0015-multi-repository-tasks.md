# ADR-0015: Tasks with several repositories, and repository requests from agents

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-09-26
- Sources: CONVENTIONS §2, §6, §8, §14.1, §14.3, §14.4, §14.7, §15.3–§15.6, §16

## Context

- In v0 a task has at most one component, so an agent works in one repository. Real changes often span several: a server, a web app, a deployment repository.
- The member who writes the task does not always know which repositories the change needs. The agent often finds out while it works.
- Agent access is per component (the agent's `componentIds`). That model stays.
- Git credentials never enter the sandbox ([ADR-0007](0007-credentials-stay-in-deployment.md), CONVENTIONS §15.3). The agent fetches through the runner host's git proxy, one route per repository.
- Wakes are events, and a run that needs a human does not wait long for one ([ADR-0004](0004-event-driven-wakes.md)).
- Only Tinfirm's own runner host speaks runner protocol v1.

## Decision

**Repositories of a task**
- A task has zero to 20 repositories: components of its own project. They replace the single `componentId`.
- When a task is created without `componentIds`, it gets every component of the project that the assignee agent has access to, or none without an assignee.
- The assignee must have access to every repository of the task. A create or update that breaks this fails with `409 AGENT_LACKS_COMPONENT_ACCESS`.
- Every repository has the branch `agent/<KEY>`.
- The full rules are in CONVENTIONS §14.7.

**Workspace layout**
- Each repository is cloned into `/workspace/<dir>`. The agent's working directory is `/workspace`.
- `dir` is derived from the component name: NFKD without combining marks, lowercase, runs of other characters than `a-z0-9` become `-`, trimmed, at most 40 characters, `repo` when empty, and `-2`, `-3` … against collisions within the task (CONVENTIONS §14.7).
- The server computes `dir` when the component joins the task and stores it, so it stays the same across runs and resumed sessions.

**Platform tools**
- The runner host serves an MCP endpoint for every run, on the git proxy's listener, at a URL with a per-run secret. It passes the endpoint in ACP `session/new` and `session/load`.
- `list_repositories` returns the task's repositories with their paths, and the project's other repositories that the agent has access to and can request.
- `request_repository(name, reason)` sends `run.repository_request` to the server. The server validates it and answers with `run.repository_request.ack`: `PENDING` (stored) or `REJECTED` with a code and a reason. The tool returns quickly.
- The run is not held open. The prompt tells the agent to finish its turn after a successful request, and the run finishes normally.
- An agent without HTTP MCP support gets no tools. The prompt asks it to name the repository it needs in its summary.

**Approval**
- PENDING requests show in the task conversation as cards; the v1 inbox will list them too. `Task.pendingRepositoryRequestCount` gives the board a cheap "needs you" signal.
- OWNER, ADMIN and OPERATOR approve or deny, with an optional note. A second decision is `409 REPOSITORY_REQUEST_DECIDED`.
- Approve adds the component to the task. Approve and deny both post a SYSTEM comment with the decision and the note, and wake the agent with the new trigger `REPOSITORY_REQUEST`. After an approval the next run's workspace has the new repository.
- Requests are cancelled when the task becomes DONE or CANCELLED, or the component is deleted.
- Every request, approval, denial and cancellation, and every change of a task's repositories, is audited (CONVENTIONS §8).
- A SUCCEEDED run in which the agent made an accepted request leaves the task status as it is, instead of moving it to IN_REVIEW: the agent waits for the decision.

**Push and pull requests**
- After a run, the runner host pushes `agent/<KEY>` in each repository with commits ahead of the default branch.
- The server opens one pull request per repository with the existing mechanism, now claimed and recorded per repository.
- Each PR body links the task and the task's other pull requests. The server keeps that block current in every PR of the task.
- A task shows its pull request per repository; a run shows its git result per repository.

**Runner host**
- It keeps one bare mirror per repository URL and clones each workspace from it, with no link from the workspace back to the mirror.
- Each repository has its own git proxy route and its own credentials in `run.assign`. The credentials never enter the sandbox.

**Runner protocol v1, changed in place**
- `run.assign.workspace` becomes `workspaces` (an array, empty without repositories). Each item gains `componentId`, `dir` and `name`. `requestableRepositories` is new.
- `run.finished.git` becomes an array of results, each with `componentId`.
- `EventPayloadGit` gains `componentId` and `dir`, optional because stored events do not have them.
- The new messages are `run.repository_request` (runner host to server) and `run.repository_request.ack` (server to runner host).

### Data model: migration `V013__multi_repository.sql`

All new tables are tenant tables (CONVENTIONS §7): `company_id uuid NOT NULL`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, a policy `<table>_tenant FOR ALL USING (company_id = app_company_id()) WITH CHECK (company_id = app_company_id())`, and composite `(id, company_id)` foreign keys as in V007–V011. Create them in the order below.

**`repository_request`**: an agent's request for a repository. Never deleted. Grants: `SELECT, INSERT, UPDATE`.

| Column | Type | Null | Meaning |
|---|---|---|---|
| `id` | `uuid` | no | Primary key |
| `company_id` | `uuid` | no | |
| `task_id` | `uuid` | no | |
| `run_id` | `uuid` | no | The run in which the agent asked |
| `host_request_id` | `uuid` | no | `requestId` of `run.repository_request` |
| `agent_id` | `uuid` | no | The run's agent |
| `component_id` | `uuid` | yes | Null after the component is deleted |
| `component_name` | `varchar(100)` | no | The component name at request time |
| `reason` | `varchar(2000)` | no | |
| `status` | `varchar(16)` | no | `PENDING`, `APPROVED`, `DENIED`, `CANCELLED` |
| `requested_at` | `timestamptz` | no | |
| `decided_by` | `uuid` | yes | `REFERENCES app_user (id) ON DELETE SET NULL` |
| `decided_at` | `timestamptz` | yes | Approve, deny or cancel time |
| `note` | `varchar(2000)` | yes | The member's note, or the cancel reason |

- `repository_request_id_company_uq UNIQUE (id, company_id)`
- `repository_request_host_uq UNIQUE (run_id, host_request_id)`: idempotency, and the requests of a run (`TOO_MANY_REQUESTS`)
- `repository_request_task_fk FOREIGN KEY (task_id, company_id) REFERENCES task (id, company_id)`
- `repository_request_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id)`
- `repository_request_agent_fk FOREIGN KEY (agent_id, company_id) REFERENCES agent (id, company_id)`
- `repository_request_component_fk FOREIGN KEY (component_id, company_id) REFERENCES component (id, company_id) ON DELETE SET NULL (component_id)`
- `repository_request_status_ck CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'CANCELLED'))`
- `repository_request_reason_ck CHECK (length(reason) BETWEEN 1 AND 2000)`
- `repository_request_note_ck CHECK (note IS NULL OR length(note) <= 2000)`
- `repository_request_decided_ck CHECK ((status = 'PENDING') = (decided_at IS NULL))`
- `repository_request_decider_ck CHECK (decided_by IS NULL OR status IN ('APPROVED', 'DENIED'))`
- `repository_request_pending_component_ck CHECK (status <> 'PENDING' OR component_id IS NOT NULL)`: the component delete must cancel first
- `CREATE UNIQUE INDEX repository_request_pending_uq ON repository_request (task_id, component_id) WHERE status = 'PENDING'`: `ALREADY_PENDING`, and `pendingRepositoryRequestCount`
- `CREATE INDEX repository_request_page_idx ON repository_request (task_id, requested_at DESC, id DESC)`: the list, newest first
- `CREATE INDEX repository_request_component_idx ON repository_request (component_id) WHERE component_id IS NOT NULL`

**`task_component`**: the repositories of a task. Grants: `SELECT, INSERT, DELETE`.

| Column | Type | Null | Meaning |
|---|---|---|---|
| `task_id` | `uuid` | no | |
| `component_id` | `uuid` | no | |
| `company_id` | `uuid` | no | |
| `dir` | `varchar(64)` | no | CONVENTIONS §14.7 |
| `added_at` | `timestamptz` | no | Order: `added_at`, then `dir` |
| `added_by` | `uuid` | yes | `REFERENCES app_user (id) ON DELETE SET NULL` |
| `repository_request_id` | `uuid` | yes | The approved request that added it |

- `task_component_pk PRIMARY KEY (task_id, component_id)`
- `task_component_dir_uq UNIQUE (task_id, dir)`
- `task_component_task_fk FOREIGN KEY (task_id, company_id) REFERENCES task (id, company_id)`
- `task_component_component_fk FOREIGN KEY (component_id, company_id) REFERENCES component (id, company_id) ON DELETE CASCADE`
- `task_component_request_fk FOREIGN KEY (repository_request_id, company_id) REFERENCES repository_request (id, company_id) ON DELETE SET NULL (repository_request_id)`
- `task_component_dir_ck CHECK (dir ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(dir) <= 64)`
- `CREATE INDEX task_component_component_idx ON task_component (component_id)`
- The project rule (`COMPONENT_NOT_IN_PROJECT`) and the limit of 20 are checked by the service, not by the database.

**`task_pull_request`**: the pull request of a task in one repository. It outlives the repository's place on the task, so removing and adding a repository again does not open a second PR. Grants: `SELECT, INSERT, UPDATE`.

| Column | Type | Null | Meaning |
|---|---|---|---|
| `task_id` | `uuid` | no | |
| `component_id` | `uuid` | no | |
| `company_id` | `uuid` | no | |
| `url` | `varchar(500)` | no | |
| `number` | `integer` | no | For `PATCH …/pulls/{number}` |
| `run_id` | `uuid` | yes | The run after which it was opened or found |
| `opened_at` | `timestamptz` | no | |

- `task_pull_request_pk PRIMARY KEY (task_id, component_id)`
- `task_pull_request_task_fk FOREIGN KEY (task_id, company_id) REFERENCES task (id, company_id)`
- `task_pull_request_component_fk FOREIGN KEY (component_id, company_id) REFERENCES component (id, company_id) ON DELETE CASCADE`
- `task_pull_request_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id)`
- `task_pull_request_number_ck CHECK (number > 0)`
- `CREATE INDEX task_pull_request_component_idx ON task_pull_request (component_id)`

**`run_repository`**: the workspace of a run, one row per repository, written when the run is created from the task's repositories in task order. `run.finished.git` fills the git columns, matched by `component_id`. The pull request columns move here from `run`. Grants: `SELECT, INSERT, UPDATE`.

| Column | Type | Null | Meaning |
|---|---|---|---|
| `run_id` | `uuid` | no | |
| `company_id` | `uuid` | no | |
| `position` | `smallint` | no | 0-based order in `run.assign.workspaces` |
| `component_id` | `uuid` | yes | Null after the component is deleted |
| `name` | `varchar(100)` | no | The component name when the run was created |
| `dir` | `varchar(64)` | no | Copied from `task_component` |
| `pushed` | `boolean` | yes | Null until `run.finished`, or without a result for it |
| `head_sha` | `varchar(64)` | yes | |
| `commits` | `integer` | yes | |
| `pull_request_url` | `varchar(500)` | yes | The task's PR in this repository at the end of the run, or opened after it |
| `pr_status` | `varchar(16)` | yes | `PENDING`: the server still has to open the PR; then `OPENED` or `FAILED` |
| `pr_attempted_at` | `timestamptz` | yes | |

- `run_repository_pk PRIMARY KEY (run_id, position)`
- `run_repository_dir_uq UNIQUE (run_id, dir)`
- `run_repository_component_uq UNIQUE (run_id, component_id)`
- `run_repository_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id)`
- `run_repository_component_fk FOREIGN KEY (component_id, company_id) REFERENCES component (id, company_id) ON DELETE SET NULL (component_id)`
- `run_repository_position_ck CHECK (position BETWEEN 0 AND 19)`
- `run_repository_dir_ck CHECK (dir ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(dir) <= 64)`
- `run_repository_commits_ck CHECK (commits IS NULL OR commits >= 0)`
- `run_repository_pr_status_ck CHECK (pr_status IS NULL OR pr_status IN ('PENDING', 'OPENED', 'FAILED'))`
- `CREATE INDEX run_repository_component_idx ON run_repository (component_id) WHERE component_id IS NOT NULL`: `COMPONENT_IN_USE` (joined with active runs) and the `SET NULL`
- `CREATE INDEX run_repository_pr_pending_idx ON run_repository (run_id) WHERE pr_status = 'PENDING'`
- `CREATE POLICY run_repository_owner_lookup ON run_repository FOR SELECT TO CURRENT_USER USING (true)`, like `run_owner_lookup` in V012, for the function below.

**Changes to existing tables and functions**
- `run.trigger` and `task.followup_trigger` become `varchar(32)`: `REPOSITORY_REQUEST` has 18 characters.
- `run_trigger_ck` becomes `CHECK (trigger IN ('ASSIGNMENT', 'COMMENT', 'MANUAL', 'REPOSITORY_REQUEST'))`.
- `task_followup_ck` becomes `CHECK (followup_trigger IS NULL OR followup_trigger IN ('ASSIGNMENT', 'COMMENT', 'REPOSITORY_REQUEST'))`.
- `run_failure_class_ck` gets `'MODEL_REFUSED'` added. V011 missed it, so a run that finishes `MODEL_REFUSED` violates the check today.
- `run_pull_request_due(timestamptz, timestamptz, integer)` is replaced (`CREATE OR REPLACE`, same signature, so the grants stay). It returns the runs with at least one `run_repository` row where `pr_status = 'PENDING'` and either `pr_attempted_at IS NULL AND run.finished_at < p_finished_before`, or `pr_attempted_at < p_attempted_before`, ordered by `finished_at, id`, with the same limit clamp.
- `run.branch` stays. The server sets it to `agent/<KEY>` when the run is created with repositories.

**Data copy.** The migration runs as the owner, and `FORCE ROW LEVEL SECURITY` applies to the owner too. So the copy runs between `ALTER TABLE … NO FORCE ROW LEVEL SECURITY` and `ALTER TABLE … FORCE ROW LEVEL SECURITY` on the tables it reads and writes (`task`, `component`, `run`, and the new tables).
1. `task_component` from every task with `component_id`: `dir` by the rule of CONVENTIONS §14.7 in SQL (any result that matches the pattern is fine here, since each task has one repository); `added_at` = `task.created_at`; `added_by` = `task.created_by`.
2. `task_pull_request` from every task with `component_id` and a `pull_request_url` matching `/pull/[0-9]+$`: `number` from the URL; `run_id` and `opened_at` from the newest run of the task with the same `pull_request_url` (its `finished_at`), else `NULL` and `task.updated_at`.
3. `run_repository` from every run with `component_id`: `position` 0, the component's name, `dir` as in step 1, and `pushed`, `head_sha`, `commits`, `pull_request_url`, `pr_status`, `pr_attempted_at` copied. A run without `component_id` but with `branch` or `pushed` set (its component was deleted) gets `component_id` `NULL`, name `deleted component` and `dir` `repo`.

**Dropped columns**, after the copy: `task.component_id` (with `task_component_fk` and `task_component_idx`), `task.pull_request_url`, `run.component_id` (with `run_component_fk`, `run_active_component_idx` and `run_component_idx`), `run.head_sha`, `run.commits`, `run.pushed`, `run.pull_request_url`, `run.pr_status` (with `run_pr_status_ck` and `run_pr_pending_idx`) and `run.pr_attempted_at`.

## Consequences

- An agent can change several repositories in one task. The pull requests share one branch name and link each other.
- Members keep control of what an agent can reach. The access model does not change; a request only asks for what the agent may already access.
- Asking costs one more run: the decision wakes the agent again, and it resumes its session. No slot, lease or subscription capacity waits for a human.
- A task that waits for a decision stays IN_PROGRESS and shows "needs you" on the board.
- Even a single repository now sits at `/workspace/<dir>`, not at `/workspace`. A session resumed from before this change sees other paths; the prompt lists the new ones.
- Breaking API change: `Task.componentId`, `Task.componentName`, `Task.pullRequestUrl`, `TaskCreateRequest.componentId`, `TaskUpdateRequest.componentId` and `clearComponent`, `Run.headSha`, `Run.commits` and `Run.pullRequestUrl` are gone. The web client is generated, so it breaks at build time, not at run time.
- Protocol v1 changes in place, so the server and the runner host must be deployed together. An older runner host cannot parse the new `run.assign` and answers `BAD_MESSAGE`; the run waits in the queue.
- The default of "all accessible components" can mean many clones for an agent with wide access. The limit of 20 bounds it, and the mirrors make repeated clones cheap.
- The runner host uses more disk for mirrors.
- Keeping sibling links current costs a `GET` and a `PATCH` per pull request on GitHub, and each reveals the component token (audited).
- The platform MCP endpoint is plain HTTP guarded by a secret URL, a new v0 shortcut (CONVENTIONS §16).
- An agent runtime without HTTP MCP support cannot request repositories until a stdio relay exists.

## Alternatives considered

- **Whole-project workspaces:** clone every component of the project for every run. Rejected: slow clones and more disk, an agent that reads code it does not need (more tokens), and a larger blast radius. The create default ("everything the assignee may access") keeps the convenience without making it the only option.
- **Human-picked repositories only:** no requests from agents. Rejected: the member often does not know up front, and the agent finds out in the middle of the work. Without requests it can only fail, or guess, and a member has to notice and start another run by hand. A request keeps the member in control at the cost of one click.
- **Hold the run open until a member decides:** the tool call blocks. Rejected: it keeps a runner slot, a lease and subscription capacity busy for as long as a human takes, and runs into the wall-clock and inactivity guards. Event-driven wakes ([ADR-0004](0004-event-driven-wakes.md)) and session resume give the same result without the wait. Waiting runs park after 10 minutes for the same reason.
- **Plain git worktrees:** one clone per repository on the host, and a worktree per run. Rejected: a worktree's `.git` file points to the main repository on the host, which the sandbox cannot see; mounting it would expose the host's refs and configuration, and other tasks' branches. Worktrees also share refs and config across runs, which is the shared working directory that [ADR-0006](0006-runner-hosts-and-isolation-tiers.md) rules out. A mirror plus an independent clone per run keeps the isolation and most of the speed.
- **Runner protocol v2 for the new shapes.** Not chosen: only Tinfirm's runner host speaks v1, so a second version would add negotiation code with no client that needs it.
