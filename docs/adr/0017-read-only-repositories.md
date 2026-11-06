# ADR-0017: Read-only repository access for agents

- Status: Accepted (built as a repository's access `READ` in an agent's definition and in a conversation's workspace)
- Date: 2026-09-27
- Sources: CONVENTIONS §8, §14.1, §14.4, §14.7, §15.4, §15.5, §15.6; [ADR-0015](0015-multi-repository-tasks.md)

## Context

- An agent's access is per component (the agent's `componentIds`, CONVENTIONS §14.1). Access means clone and push: after every run the runner host pushes `agent/<KEY>` in each repository with new commits, and the server opens a pull request in it (CONVENTIONS §14.4).
- Members want agents that only read some repositories, or all of them: a reviewer or an analyst, or an agent that must see the API of a service it may not change.
- The component's git token is shared by every agent with access to the component. Tinfirm cannot hand a narrower token to one agent.
- The agent never pushes itself. The runner host's git proxy is already fetch-only, and the host alone pushes, after the run (CONVENTIONS §15.4 step 2 and step 8).

## Decision

**Access mode**
- Each entry of an agent's `componentIds` is read-write (as before) or read-only. The API names the read-only entries `readOnlyComponentIds`, a subset of `componentIds`, on `Agent`, `AgentCreateRequest` and `AgentComponentsRequest`.
- An id in `readOnlyComponentIds` that is not in `componentIds` is `400 VALIDATION_FAILED`. `setAgentComponents` without `readOnlyComponentIds` makes every component read-write.
- A read-only agent is an agent whose components are all read-only. There is no separate agent flag.
- Read-only access is still access: the task rules of CONVENTIONS §14.7 (`AGENT_LACKS_COMPONENT_ACCESS`, the default set on create, requestable repositories) do not change.
- `agent.created` and `agent.components.changed` gain `details.readOnlyComponentIds`. A change of the mode alone is audited as `agent.components.changed` with empty `added` and `removed`.

**Runs**
- A run keeps the mode of each repository as it was when the run was created, like its name and `dir` (`run_repository.read_only`, `RunRepository.readOnly`).
- `run.assign.workspaces[].readOnly` (protocol v1, optional, default `false`) carries it to the runner host.
- The runner host clones, fetches and commits WIP in a read-only repository as usual, and reports its ahead count. It never pushes it, logs that it did not, and does not scan its outgoing commits for credentials, since they never leave the host. The run's result for it has `pushed: false`.
- The server opens no pull request in a read-only repository, even if a host reports `pushed: true` for it.
- The prompt marks each read-only repository `, read-only`, says that Tinfirm does not push them, and says that pushes happen only in the other repositories. When every repository is read-only, it tells the agent not to commit and to put its result in the summary. `list_repositories` gains `readOnly` per repository.

### Data model: migration `V016__read_only_repositories.sql`

| Table | Column | Type | Null | Default |
|---|---|---|---|---|
| `agent_component` | `read_only` | `boolean` | no | `false` |
| `run_repository` | `read_only` | `boolean` | no | `false` |

Existing access and existing runs stay read-write. The grants of V009 and V013 suffice: the agent's components are replaced with `DELETE` and `INSERT`, and `run_repository` rows are inserted with the column.

## Consequences

- Members can give an agent a repository to read without letting it change that repository, per agent and per repository.
- The enforcement is Tinfirm's, not the git host's: the token that the runner host holds for a read-only repository may still allow pushes. A compromised runner host could push; so could one before this change. A GitHub App with scoped installation tokens can later mint read-only tokens for read-only repositories.
- A change of mode applies to runs created after it. A queued run keeps the mode it was created with.
- Protocol v1 changes in place and stays compatible: an older host ignores `readOnly` and pushes, but the server still opens no pull request. Deploy the runner host with the server to enforce it.
- Commits the agent makes in a read-only repository are lost when the workspace is deleted, except in the kept failed workspaces.

## Alternatives considered

- **A read-only flag on the agent.** Simpler, but the mode is wanted per repository, and a flag cannot give an agent one repository to change and another to read. A read-only agent is the case where every repository is read-only.
- **The mode per task repository.** It would let the same agent write in one task and only read in another. Rejected for now: access is a property of the agent (CONVENTIONS §14.1), and a per-task mode would need its own rules for defaults, requests and approvals.
- **A read-only sandbox mount.** Mounting `/workspace/<dir>` read-only stops the agent from building or running tests in it, which reviewers need. Not pushing gives the same protection for the repository.
