# ADR-0035: Who may give an agent work

- Status: Superseded by [ADR-0058](0058-authority-from-the-audience.md) on 2026-10-12 (was Accepted)
- Date: 2026-10-03
- Decided by: the owner, 2026-10-03
- Sources: the owner's question of 2026-10-03 (who may give an agent work); CONVENTIONS §6, §7, §8, §14.1, §14.2, §14.3, §15.5, §15.6, §17.4, §19; [ADR-0019](0019-agent-created-tasks.md), [ADR-0024](0024-jira-tracker-connector.md), [ADR-0027](0027-company-model-credentials.md), [ADR-0030](0030-github-issues-tracker.md), [ADR-0032](0032-routines.md), [ADR-0034](0034-mention-an-agent.md)
- Changes: ADR-0027, which kept the permissions of CONVENTIONS §14.2 for every agent, so that any member who may create tasks gave work to any agent: assigning a task, starting a run and mentioning now need an agent the member may use. ADR-0034's "whoever may comment may mention": a mention that gives an agent work needs an agent the member may use, and a mention on a linked issue starts only agents of the whole company. ADR-0019's assignee of `create_task`, "another agent of the company": only an agent that the creating agent's owner may use.

## Context

- [ADR-0027](0027-company-model-credentials.md) gave an agent one of two kinds of credential. A runner credential lives on a runner host of the agent's owner and is the owner's own subscription, for example a Claude Max plan; one owner per subscription is enforced (`403 CREDENTIAL_NOT_OWNED`). A model credential belongs to the company: its key is the company's gateway key, with the gateway's budget.
- The permissions did not follow. Every member with OPERATOR or above may create and change tasks, start runs and comment (CONVENTIONS §14.2), and, since [ADR-0034](0034-mention-an-agent.md), mention agents. Nothing asked whose agent it was: any operator could assign a task to an agent that runs on a colleague's subscription, start its runs, or mention it, and spend the colleague's usage limits.
- The owner asked on 2026-10-03: an agent on a member's subscription was usable by every OPERATOR. A member who registers a personal subscription must be able to keep it for their own work, and an agent on the company's gateway key should stay usable by everyone.
- The owner of an agent could only pause or delete it, which stops their own work too.
- Some work reaches an agent without a member's action at the moment: a tracker rule turns an issue into a task for its agent (ADR-0024, ADR-0030), a routine creates a task on a schedule (ADR-0032), an agent creates a task for a colleague with `create_task` (ADR-0019), and a person on a linked issue mentions an agent (ADR-0034).

## Decision

**Every agent says who may give it work.** `agent.usage`:
- `COMPANY`: every member who may create tasks.
- `OWNER`: only the agent's owner, and the company's OWNERs and ADMINs, who may manage every agent. An instance admin acting in the company counts as an ADMIN, as `CompanyAccess.covers(MANAGE_AGENTS)` says.

"Giving work" is assigning the agent a task, starting its run by hand, mentioning it in a comment, and naming it as the assignee of `create_task`.

**Defaults.** A create without `usage` gets `OWNER` for an agent on a runner credential, a member's subscription, and `COMPANY` for an agent on a model credential, the company's gateway key. An explicit `usage` wins. On update, `usage` is optional, and a change of the credential, also to the other kind, does not change it. The migration (`V033__agent_usage.sql`) gives existing agents `OWNER` when they have a runner credential, else `COMPANY`; an agent whose credential was removed is `COMPANY`, and its owner can change it.

**One rule, in one place.** The agent module exposes it as `AgentUsage.allows(access, userId, ownerUserId)` (and `AgentView.usableBy(access, userId)`): true for a `COMPANY` agent; for an `OWNER` agent when the access covers `MANAGE_AGENTS`, or when the user is the agent's owner and still a member. A person who is not a member may use `COMPANY` agents only. The web's `canUseAgent` mirrors the rule to show what the server would refuse. `agent.created` and `agent.updated` record `usage`; a changed usage is in `changed`.

**What is gated**, each with `403 AGENT_NOT_USABLE` and a detail that names the agent and says it is usable by its owner only:
1. **Assigning a task**: `createTask` with `assigneeAgentId`, and `updateTask` that sets `assigneeAgentId` to such an agent. An update that keeps the assignee, or clears it, is not a use.
2. **Starting a run by hand**: `POST /tasks/{id}/runs` when the member may not use the task's assignee.
3. **Mentioning the agent** in a member's comment, when the mention gives it work: the agent is not the task's assignee, and the comment does not answer its open questions. The whole comment is refused and nothing is stored; the detail names the handles.
4. **`create_task` from a run** (ADR-0019): the creating agent acts with the rights of its owner. The assignee it names must be usable by that owner, judged by the owner's current membership role; an owner who is no longer a member may use `COMPANY` agents only. Otherwise the tool answers `REJECTED` with the code `AGENT_NOT_USABLE` and a message that names the agent and says to leave the task to itself or name another agent. The agent may always leave the task to itself. The prompt's colleagues (CONVENTIONS §15.5), and the list of an `UNKNOWN_AGENT` answer, name only the agents the owner may use.
5. **A mention on a linked issue** (an EXTERNAL comment): the person on the issue is not a member, so an `OWNER` agent cannot be mentioned from there. The comment is stored, the other mentions and answers proceed, no pending wake is created for that agent, and the task gets a `SYSTEM` comment, `{agent name} was not started (mention): it is usable by its owner only, and a mention on {Jira or GitHub} cannot be matched to a member.`, which the write-back carries to the issue. An answer to the agent's own question on the issue is not a mention that gives it work: it wakes the agent as before.

**What is not gated, and why**
- Comments, answers to asks and repository decisions on a task that is already assigned to the agent, and a mention of the task's assignee: the owner or an admin gave the agent that task, and collaborators continue that work. So is a status change of such a task, moving it to Todo included, since the assignee stays.
- Tracker rules and agent accounts, and routines, name their agent when they are set up, by OWNERs and ADMINs, who may use every agent; the tasks they create are SYSTEM actions. The tracker's `MENTION` rule names its agent the same way. A routine's "run now" by an operator fires what an owner or admin set up, and is not gated either.
- Viewing anything: every member sees every agent, its usage and its owner.

**API.** `AgentUsage` (`COMPANY`, `OWNER`); `Agent.usage`; `usage` in `AgentCreateRequest` (optional, with the default above) and `AgentUpdateRequest` (optional). `AGENT_NOT_USABLE` (403) is a problem code, described on `createTask`, `updateTask`, `startTaskRun` and `addTaskComment`. The runner protocol does not change: the code of a `run.task_create.ack` is a free string.

**Web.** The agent dialog has a field "Who may give it work", "Everyone in the company" or "Only its owner and admins", with the hint "Runs of an agent on a runner credential use its owner's subscription." When creating, the choice follows the kind of credential the member picks until the member changes it; when editing, it shows the agent's value and is sent only when changed. The agents list shows "Owner only" next to such agents. The assignee pickers list the agents the member may not use, disabled, with "(owner only)". The composer's `@` list offers the task's assignee and the agents the member may use; a body that mentions another says "@{handle} is usable by its owner only; this comment would be refused.", and the send button stays enabled: the server answers.

## Consequences

- **Existing deployments.** After the migration, every agent on a runner credential is usable by its owner only. Operators who gave work to a colleague's agent can no longer do so until its owner chooses "Everyone in the company". This is the intended change: the subscription is the owner's. Agents on a model credential, and tasks already assigned, are not affected.
- **Colleagues' comments still cost the owner.** A colleague's comment on a task that the owner or an admin assigned to the agent wakes it, and so does an answer to its question or a repository decision, and each of those runs is on the owner's subscription. The owner controls it by what they assign, and by pausing the agent.
- **ADMINs and OWNERs may use every agent**, also one on another member's subscription. They manage every agent anyway, and could change its usage.
- **Agents act with their owner's rights.** An operator's agent cannot hand a task to another member's owner-only agent, and sees only the agents it may name. When the owner is demoted or leaves the company, its agent may name fewer agents, judged at each call; an owner who left may name only `COMPANY` agents. The agent can always keep the task itself. A member who moves a task that an agent created to Todo starts the assignee as for any assigned task.
- **On a linked issue**, an owner-only agent is reachable only through the tracker's own setup: a rule an admin made, or an answer to its question. A person on the issue who mentions it gets the note, and a member who may use it can mention it in Tinfirm.
- People see what they may not use: owner-only agents stay in the lists, marked, so nobody wonders where an agent went.

## Alternatives considered

- **By role: operators use only their own agents.** Rejected: it binds the rule to roles rather than to whose subscription pays. An agent on the company's gateway key should be usable by every operator, and an owner may want to share their own agent.
- **Gate comments too**, on every task of an owner-only agent. Rejected: it would stop collaboration on a task the owner assigned; a colleague could not even ask the agent for a change on the task it works on.
- **A list of members per agent.** Not chosen: two values cover the owner's question, and a list can come later without changing the rule's place.
- **No policy**, the state before. Rejected by the owner: a colleague can spend a member's subscription.
