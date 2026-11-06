# ADR-0037: Restart on the platform design: specialists, triggers, work items and jobs

- Status: Superseded by [ADR-0058](0058-authority-from-the-audience.md) on 2026-10-12 (was Accepted): its work items, jobs and connections are gone; its "What stays" part lives on through ADR-0058's "Keeps in force" list
- Date: 2026-10-05
- Decided by: the owner, 2026-10-05
- Sources: the owner's decision of 2026-10-05 to restart the design and keep only the parts that hold up; [archive/v2/PLATFORM.md](../archive/v2/PLATFORM.md); the inventory of the server, the runner host, the web app and the deployment made for the restart
- Supersedes: [ADR-0015](0015-multi-repository-tasks.md), [ADR-0018](0018-project-task-prefixes.md), [ADR-0019](0019-agent-created-tasks.md), [ADR-0020](0020-changeable-task-prefixes.md), [ADR-0024](0024-jira-tracker-connector.md), [ADR-0028](0028-asks-and-waiting-on.md), [ADR-0029](0029-work-source-per-project.md), [ADR-0030](0030-github-issues-tracker.md), [ADR-0031](0031-rule-for-new-issues.md), [ADR-0032](0032-routines.md), [ADR-0033](0033-one-answer-per-ask.md), [ADR-0034](0034-mention-an-agent.md)
- Keeps in force: ADR-0001 to ADR-0014, [ADR-0016](0016-runner-capacity-settings.md), [ADR-0017](0017-read-only-repositories.md), [ADR-0021](0021-agent-attachments.md), [ADR-0022](0022-pull-request-state.md), [ADR-0023](0023-code-host-interface-gitlab.md), [ADR-0025](0025-kata-sandbox-runtime.md), [ADR-0026](0026-per-provider-oidc-email-trust.md), [ADR-0027](0027-company-model-credentials.md), [ADR-0035](0035-agent-usage-policy.md), [ADR-0036](0036-questions-end-a-run-quietly.md), each read with "task" as "work item or job" as PLATFORM.md says
- Amended by: [ADR-0038](0038-a-job-is-a-thread-with-an-agent.md), under which a job is a thread between people and one agent on one work item, continued by every message addressed to the agent, instead of one trigger firing

## Context

- v0 built a task tracker with agents: projects with task keys and a board, one agent assignee per task, runs on tasks. The pivot of 2026-10-02 put a tracker connector on that model: an issue became a task with one agent, chosen by the first matching rule, once in the issue's lifetime (ADR-0024, ADR-0031). Every later feature was a patch on the mismatch between a ticket with one owner and a firm of specialists: work sources per project (ADR-0029), routines as a separate module (ADR-0032), mentions as the only way to involve a second agent (ADR-0034).
- On 2026-10-05 the owner found the whole part wrong: one issue needs several specialists, each with its own trigger (an analyst on every update, a tester on a status, a developer on a label), and the platform must serve every department of a company, not only software development. The owner asked for a restart that keeps only what is genuinely good: the sandboxing, the read-only repository access, the security posture.
- An inventory of the code found the infrastructure sound and tested (tenancy, identity, vault, audit, the runner host and its sandbox, runs and dispatch, the API clients, the design system and the shell) and the domain entangled with tasks everywhere (the run module drives tasks through a port of thirty methods; `run.task_id` is not null; the tools speak of backlogs).

## Decision

**Tinfirm restarts on the design in [archive/v2/PLATFORM.md](../archive/v2/PLATFORM.md).** In one paragraph: a company connects the systems it already uses (Jira, GitHub, GitLab, a schedule); **triggers** on a connection say *when this event happens to a subject in this scope, this agent does this job*; every matching trigger fires, in order; each firing is a **job** of one agent on one **work item**, the subject in Tinfirm; jobs run in sandboxes as before; every decision a job needs is a **decision** in the inbox and on the source; people start work too, in a conversation with an agent or by mentioning it. One model serves code and non-code work alike.

**What stays.** The runner host and its protocol v1 (extended, not replaced), the sandbox tiers and Kata, the git proxy and push lease, redaction, the images; identity, sessions, OIDC, roles, the vault, the audit chain, tenancy; runs, dispatch, leases, queue reasons, capacity, transcripts and SSE; model credentials; the Jira, GitHub and GitLab clients and the markup converter; attachments with the upload scan; change requests and their state; the mention grammar, the waiting-on resolver, the quiet-questions rule, the usage policy; the design system, the shell, the transcript viewer and the generic pages; the deployment contract (image, ports, probes, environment, Helm values, secrets).

**What goes.** Projects and components, task prefixes and keys, the board and task statuses, tasks as the unit of work, tracker rules with "first wins", work sources, routines as a module, agent-created tasks and `create_task`, asks and repository requests as separate things, the v1 migrations, the domain sections of the v1 conventions.

**How.** A fresh Flyway baseline (`V001__baseline.sql`), no data migration, the v1 domain modules removed and the new ones (`connection`, `work`, a re-parented `run`, an `agent` v2) built in the order of PLATFORM.md §13, each package with its tests, recorded in [reviews/2026-10-05-restart.md](../reviews/2026-10-05-restart.md). The v1 design documents move to [archive/v1](../archive/v1/). The superseded ADRs stay as history, marked superseded; the kept ADRs stay in force.

## Consequences

- The product can express a firm of specialists: several agents on one issue, each with its own event, instruction and repeat policy; agents for analysts, testers and finance next to developers; a person talking to a specialist without a tracker.
- The rewrite costs the v1 domain code (about half of the server, a third of the web app) and the data stored under v1. It costs nothing of the infrastructure, which was most of the effort and all of the risk.
- CONVENTIONS.md shrinks to the engineering rules and grows the v2 domain rules as each package lands; the detailed v1 rules stay readable in the archive.
- Javadoc and comments that cite v1 ADRs and conventions sections are rewritten with the code they describe.

## Alternatives considered

- **Fix the rules only** (snapshot, edge events, every match fires). Rejected by the owner: it keeps the task model, the projects, the keys and the board, and every later feature would keep paying for them.
- **A new codebase.** Rejected: the kept parts are most of the code; moving them gains nothing. The restart replaces the domain in place.
- **Keep the v1 migrations and migrate the data.** Rejected: migrating the data is not worth its complexity, and a baseline is easier to read for everyone who joins.
- **Projects as an organising unit.** Rejected: access is on the agent, scope is on the trigger, and grouping for people is a filter on work items. A project would only reintroduce the container that tasks lived in.
