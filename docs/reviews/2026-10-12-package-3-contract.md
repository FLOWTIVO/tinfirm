# Package 3 contract: agents, settings and conversations

Status: decided on review (§11), built · 2026-10-12 · Owner: J. Balcar

The contract for package 3 of the [platform redesign](2026-10-12-platform-redesign.md#part-9-how-it-is-built--decided), its first end-to-end slice: two people use one agent with their own accounts and budgets. It holds the tables, the invariants they keep, the rules as functions later code calls (the account binding of a conversation, the tools of a run, the runner mapping), the API, the errors, the audit actions and the tests. It builds on [package 1](2026-10-12-package-1-contract.md) (principals, systems, accounts, grants, `Accounts.bind`) and on package 2 (model access, runner hosts), and leaves hooks for triggers (package 4) and chats (package 5); where they add to a table, it says so. The schema starts fresh (Part 9): no migration of today's data.

## 1. Scope

| In package 3 | Later |
|---|---|
| Agents as definitions: instructions, runtimes, the systems, tools and repositories they may use (the ceiling), each tool's mode, network hosts; sharing with groups (the use right); pausing; deleting | Duplicating, templates, export and import (PLATFORM §14) |
| A person's settings for an agent, their own MCP servers included | A trigger's settings (package 4 adds `trigger_id` as their other owner) |
| Conversations in a Tinfirm thread, started in the app, about a subject or not: independent sessions and branches, turns, ending, cancelling, retrying | Conversations in a chat thread (package 5), on a tracker issue and of a scheduled firing (package 4); started from a person's AI client (package 6) |
| The account binding at a conversation's first use of each system, through `Accounts.bind`: Connect cards, choosing among grants, `RECONNECT_*` repairs | A trigger's account table, copied into its conversations (package 4) |
| The payer of a conversation and of each run, through package 2 | LiteLLM, keys, budgets, subscriptions and the host scope rule themselves (package 2); spend views (package 6) |
| Questions and approvals, decided by the conversation's person | Approvals by a trigger's team and reviews of results (package 4); answers from a chat thread, approvals as chat buttons (package 5) |
| Tools in three layers; the fixed catalogues' reads and first writes; MCP tools; the five platform tools; the prompt | Chat systems' tools (package 5); creating issues and further writes |
| The runner mapping: one run at a time per conversation, conversations in parallel | Polling, webhooks, the write-back outbox (package 4) |
| Outputs: change requests, documents, links | Attaching documents to issues (package 4) |
| Visibility of agents and personal conversations; the inbox for them | A trigger's conversations seen by its team (package 4); kill switches beyond pausing an agent (package 6) |

## 2. What it builds on

### 2.1 Packages 1 and 2

| Package | What package 3 uses | For |
|---|---|---|
| 1 | `principal`, `user_group`, `external_system` with its `handle`, `system_repository`, `system_tool` with effects, the fixed catalogues in code, `account`, `account_grant` | The ceiling of an agent, tool effects, bindings |
| 1 | `Accounts.bind(Personal(member), systemId)` | A system's first use in a conversation (§5.3) |
| 1 | `Accounts.check(Stored binding)` as built: `Usable(account, access)`, with the grant's access read at that check, or `Stopped(reason)`: `RECONNECT_YOURS`, `RECONNECT_COMPANY`, `GRANT_GONE`, `NOT_IN_GROUP`, `PRINCIPAL_INACTIVE` (the acting member or the holder), `IDENTITY_CHANGED`, `USE_RIGHT_GONE`, `SYSTEM_GONE`, `PERSONAL_SYSTEMS_OFF` | Every use of a stored binding (§5.3) |
| 1 | `Accounts.reveal(companyId, accountId, actingPrincipalId, purpose)` as built: refuses a member's account for anyone but that member (I3); refreshes an OAuth token within 60 s of expiry | A call's or a clone's token, audited as `secret.accessed` |
| 1 | Events, published in the committing transaction (an `@EventListener` gets them inside it, a `@TransactionalEventListener` after commit, never on rollback): `AccountConnected`, `AccountReconnected`, `AccountDisconnected`, `GrantChanged`, `GrantDeleted`, `GroupMembersChanged`, `PrincipalSuspended`, `PrincipalResumed`, `PrincipalLeft`, `SystemDeleted`, `SystemToolsChanged`, `RepositoryRemoved`, `PersonalSystemsSwitched`. Package 1's contract does not list them yet; package 3 needs them | Resolving waiting bindings, marking bindings gone, ending a leaving member's conversations |
| 2 | `ModelAccess.forConversation(companyId, payerPrincipalId, preference, runtime)` → `Budget(principalId, keyId, format, baseUrl, models)`, `Subscription(principalId, runnerHostId, credentialLabel, runtimes)`, `Spent(principalId, resetsAt)` or `Unavailable(reason)` (`NO_KEY`, `SUSPENDED`, `NO_SUBSCRIPTION`, `RUNTIME_NOT_SERVED`) | The payer at the start and at every turn (§5.7) |
| 2 | `ModelAccess.models(principalId)`, `ModelAccess.reveal(keyId, purpose)` | The model in settings; `run.assign.modelAccess.apiKey` |
| 2 | `ModelAccess.runEnded(runId, payerPrincipalId, failureClass, rateLimits)` and the event `BudgetAvailable(principalId)` (a reset or a raise) | A spent budget (V7) |
| 2 | `RunnerHosts.mayRun(hostId, principals)`: the host scope rule (a company host runs any conversation, a personal host only one whose credentials are all its owner's), and each host's kind and owner | Placing a run (§5.7, V8) |
| 2 | The runner's model proxy, on in the end-to-end environment | V11 |

### 2.2 Today's code

| Reused | Reworked | Not created |
|---|---|---|
| The dispatcher's loop, leases, epochs, the reaper, the reconnect grace, run resumption, queue reasons (§3.11 adds codes), run events and their stream, transcripts, `run_command` | `RunTools.modeOf` and `ConnectionCredentials.callCredential` become `ConversationTools` and `ConversationBindings` (§5.3, §5.4) | `work_item`, `work_item_counter`, `comment`, `job`, `artifact`, `trigger_firing`, `outbox`, `sync_*` (package 4 creates its write-back) |
| `run.request` handling and its idempotency by request id; `ToolCalls`' order of checks; `ToolExecutor`, `ToolResults`, `ToolArguments`, `LabelCall`; the Jira, GitHub, GitLab and MCP clients | `AssignmentBuilder`: `workItem` and `job` from the conversation, `modelAccess` from package 2, git tokens from the bindings | `agent.kind`, `usage`, `policy_*`, the agent's runner or model credential, `agent_group_right`, tool levels and `DENY` |
| `ToolApprovals` (an approved call runs in the deciding transaction), `ToolDenials`, `NetworkHosts`, the Public Suffix List | `HostSelection`, `RunDispatcher`: package 2's host scope filters the candidates; a subscription goes to its host | Decision kinds `ACCESS` and `REVIEW` (a review returns in package 4 as an approval of a result); `job.granted_tools`: tools are not requested |
| `ChangeRequestOpener`, `ChangeRequestWatch`, `Branches`, the attachments service and its scan, `/output`, the prompt cache rules | `JobService`, `JobStore`, `Turns` become the conversation's service: one queue per conversation, none across threads (`pending_turns`, `turn_seq` go) | Mentions in Tinfirm comments: a conversation has one agent (the grammar returns with chats in package 5) |
| `PromptRenderer` and its sections, `WaitingOnResolver`, the question rules of ADR-0033 and ADR-0036 | `DecisionService` (two kinds, the approver from the conversation), `Visibility`, `InboxService` | `ModelCredentialHealth` (package 2 keeps the budget's state) |

On landing, package 3 rewrites [CONVENTIONS](../CONVENTIONS.md) §14.6 to §14.8 and the tool parts of §14.10. Row-level security, the tenant-isolation test and the hash-chained audit stay as §6 to §8 define them; every table below carries `company_id`.

## 3. Tables

### 3.1 `agent`

A specialist's definition, private to its owner until shared.

| Column | Meaning |
|---|---|
| `id`, `company_id` | |
| `name` | 1 to 100 characters, unique among the company's live agents ignoring case |
| `handle` | `@handle`: 1 to 50 lower-case ASCII letters, digits and hyphens, unique among live agents, made from the name when absent (ADR-0034's rules, as today) |
| `description` | What the specialist is for, at most 1000 characters |
| `instructions` | The standing instructions, the first part of every prompt, at most 100 000 characters |
| `runtimes` | The runtimes its instructions are tested on: a non-empty array without duplicates of `CLAUDE_CODE`, `CODEX`, `PI`, `OPENCODE`; the first is the default |
| `owner_principal_id` | The member who edits it besides admins: its creator; `NULL` once it passed to the admins (its creator left) |
| `status`, `paused_at`, `paused_by` | `ACTIVE` or `PAUSED` |
| `deleted_at` | Soft delete, as today: the row stays for its conversations' history |
| `created_at`, `updated_at`, `created_by` | `created_by` keeps naming the creator after they leave |

No column names a secret, an account, a model key, a runner credential or a principal it acts as (I1).

### 3.2 `agent_system`

The ceiling of systems: `(company_id, agent_id, system_id)`, primary key `(agent_id, system_id)`. Only company systems (`owner_principal_id IS NULL`) of a kind with tools or repositories: `JIRA_DC`, `JIRA_CLOUD`, `GITHUB`, `GITLAB`, `GIT`, `MCP` (the chat kinds from package 5). A conversation binds accounts only on these and on the person's own MCP servers (§5.3).

### 3.3 `agent_tool`

| Column | Meaning |
|---|---|
| `agent_id`, `system_id`, `tool` | The tool as its system names it: a fixed catalogue's (§5.4) or an MCP server's own name. Primary key `(agent_id, system_id, tool)`; composite foreign key `(agent_id, system_id)` to `agent_system`, cascading |
| `mode` | `ALLOW` or `ASK` (each call an approval). A tool without a row is not available; there is no `DENY` |
| `chosen_for_effect` | The tool's effect when the mode was chosen, `READ` or `WRITE` (an unconfirmed tool's is `WRITE`). An `ALLOW` chosen for a read counts as `ASK` once the tool's effect is `WRITE` (I19) |

Adding a system in the editor fills a row per tool the system serves: reads `ALLOW`, writes and unconfirmed tools `ASK`. The editor removes rows or changes modes. A row whose tool the system no longer serves stays, shown as "no longer offered", and lists nothing.

### 3.4 `agent_repository`

`(company_id, agent_id, system_id, repository_id, access)`, `access` `READ` or `WRITE` (ADR-0017). Primary key `(agent_id, repository_id)`; composite foreign keys `(agent_id, system_id)` to `agent_system` and `(repository_id, system_id)` to `system_repository`, so a repository is listed only with its system. At most 100 per agent.

### 3.5 `agent_host`

The network hosts the agent's runs may reach (ADR-0044): `id`, `company_id`, `agent_id`, `host` (`HOST[:PORT]` or `*.example.com[:PORT]`, normalized as `NetworkHosts` does today), `reason`, `added_by`, `decision_id` (when an approved `request` added it for good), `added_at`. Unique `(agent_id, host)`; at most 100 per agent.

### 3.6 `agent_share`

The use right: `(company_id, agent_id, group_id, shared_by, shared_at)`, primary key `(agent_id, group_id)`, cascading from both. At most 50 groups per agent.

### 3.7 `agent_settings`, `agent_settings_system`, `agent_settings_tool`

One person's narrowing of one agent, made on first use (their first conversation with it, or their first save).

| Column | Meaning |
|---|---|
| `id`, `company_id`, `agent_id`, `principal_id` | The member's principal. Unique `(agent_id, principal_id)`. Package 4 adds `trigger_id` as the other owner, with a check that exactly one is set |
| `runtime` | One of the agent's runtimes; `NULL` is the agent's first |
| `model` | Optional, at most 200 characters of today's pattern; one the person's model access allows |
| `effort` | `DEFAULT`, `LOW`, `MEDIUM`, `HIGH`, `XHIGH` or `MAX` |
| `model_access`, `subscription_id` | `BUDGET` (the default) or `SUBSCRIPTION` with package 2's subscription of this person, which serves the runtime |
| `instructions` | Added after the agent's, at most 20 000 characters |
| `default_repository_ids` | The repositories a new conversation's workspace starts with: some of the agent's; empty means all of them |
| `default_projects` | `[{systemId, key}]` of systems the agent lists, at most 20: tracker projects the prompt names as the person's usual scope |
| `created_at`, `updated_at` | |

`agent_settings_system`: `(settings_id, system_id, state)`, primary key `(settings_id, system_id)`. `OFF` switches a system of the agent off (its tools and repositories are gone for this person); `ADDED` adds the person's own MCP server. A trigger refuses `OFF` for a system the agent does not list and `ADDED` for a system that is not an `MCP` server owned by `principal_id` (`SETTINGS_WIDEN`).

`agent_settings_tool`: `(settings_id, system_id, tool, mode, chosen_for_effect)`, primary key `(settings_id, system_id, tool)`. On a system of the agent, `mode` is `ASK` (an `ALLOW` turned into `ASK`) or `OFF`; `ALLOW` is refused (`SETTINGS_WIDEN`). On an `ADDED` system the rows are the list of its tools, `ALLOW` or `ASK`, as the agent's rows are for its systems (open point 6).

### 3.8 `conversation`

The one object of work: one agent, one place, its accounts and model access fixed for its life.

| Column | Meaning |
|---|---|
| `id`, `company_id`, `agent_id` | |
| `key` | Unique in the company and never reused: `{base}-{n}`. `base` is the subject's key as a slug and the agent's handle (`shop-123-dev`), or the handle alone without a subject, cut to 80 characters; `n` comes from `conversation_key_counter (company_id, base, next)`, advanced under its row lock. At most 93 characters, so the branch `agent/{key}` fits `Branches`' 100 |
| `title` | The first line of the first message, at most 200 characters; the person may rename it |
| `place`, `place_ref` | `TINFIRM_THREAD` in package 3, with `place_ref` `NULL`. Reserved: `CHAT_THREAD` (package 5), `TRACKER_ISSUE`, `SCHEDULE` (package 4), whose `place_ref` (jsonb) names the thread or the issue |
| `origin` | `APP` in package 3. Reserved: `CLIENT` (the person's AI client, package 6), `DIRECT_MESSAGE` (package 5), `TRIGGER` (package 4) |
| `person_principal_id` | The member a personal conversation acts for, is paid by and is seen by. `NOT NULL` in package 3; package 4 adds `trigger_id` and the check that exactly one of the two is set |
| `subject_system_id`, `subject_key`, `subject_url`, `subject_title` | The subject, a reference and not a copy: a company system with issues or change requests, the key people know (`SHOP-123`, `owner/repo#57`), its link, the title given at the start. `NULL` when it is about no subject |
| `runtime` | Fixed at the start: the settings' runtime then (open point 3) |
| `payer_principal_id`, `model_access`, `subscription_id` | Who pays, fixed: the person, `BUDGET` or `SUBSCRIPTION` as their settings said at the start |
| `workspace` | `[{repositoryId, readOnly, source}]`, `source` `AGENT` or `REQUESTED`; at most 20 |
| `granted_hosts` | Hosts an approved `request` gave this conversation; at most 100 |
| `state`, `ended_reason`, `ended_at`, `ended_by` | `OPEN` or `ENDED`; `ENDED` by a person, `AGENT_DELETED`, `PERSON_LEFT` |
| `turn_status` | The latest turn: `QUEUED`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `waiting_on`, `waiting_reason`, `waiting_since` | Today's resolver: `PERSON` (`QUESTION`, `APPROVAL`, `ACCOUNT`, `FAILED`), `AGENT` (`RUNNING`), `RUNNER` (the run's queue reason), `NONE` |
| `turn_count`, `active_run_id`, `last_run_id`, `last_turn_at` | |
| `result_outcome`, `result_summary`, `result_next_steps`, `result_open_questions`, `failure_class`, `failure_cause` | The latest turn's result or failure |
| `created_at`, `updated_at`, `created_by` | |

A trigger refuses updates of `agent_id`, `person_principal_id`, `key`, `runtime`, `payer_principal_id`, `model_access` and `subscription_id` (I10, I12).

### 3.9 `conversation_binding`

The account a conversation uses on one system.

| Column | Meaning |
|---|---|
| `id`, `company_id`, `conversation_id`, `system_id` | Unique `(conversation_id, system_id)` |
| `state` | `BOUND`; `WAITING` (a card: the person or an admin acts before the system can be used); `GONE` (the system can never be used again in this conversation; final) |
| `how` | `OWN` (the person's own account), `GRANTED` (the one usable grant), `CHOSEN` (the person picked a grant) |
| `account_id`, `holder_principal_id`, `external_id` | The bound account, its principal and its identity on the system where it has one. `NULL` until first bound, then set once (I6) |
| `grant_id` | The grant it acts through; `NULL` for the person's own account |
| `reason` | Why it waits: package 1's `NONE`, `RECONNECT_YOURS`, `RECONNECT_COMPANY`, `CHOOSE`, and `PICK_GRANT` (several usable grants), `SUSPENDED` (the holder is suspended), `SYSTEM_OFF` (personal servers are switched off). Why it is gone: `GRANT_REMOVED`, `LEFT_GROUP`, `IDENTITY_CHANGED`, `HOLDER_LEFT`, `SYSTEM_REMOVED` |
| `choices` | The grant ids offered for `PICK_GRANT` and `CHOOSE` |
| `waiting_calls` | Tool calls parked while it waits: `[{runId, requestId, tool, arguments, result}]`, at most 10, arguments at most 64 KiB each (§5.3) |
| `bound_at`, `bound_by` | `bound_by` is the person when they chose |

The access, `READ` or `WRITE`, is not stored: it is `WRITE` for the person's own account and the grant's at every use otherwise (open point 9).

### 3.10 `message`

What is said in a Tinfirm thread: `id`, `company_id`, `conversation_id`, `kind` (`PERSON`, `AGENT`, `SYSTEM`), `author_principal_id` (a `PERSON` message's), `body` (Markdown, 1 to 100 000 characters), `answers_open_questions`, `run_id` (the turn that took a `PERSON` message, or produced an `AGENT` or `SYSTEM` one), `decision_id` (a question's or an approval's card, and an answer), `created_at`. A `PERSON` message without `run_id` waits for the next turn. Packages 4 and 5 add `external_id` and `external_author` for what is said in the place itself.

### 3.11 `run` (changed): a turn is a run

There is no table of turns: a turn is one run.

| Change | Meaning |
|---|---|
| `conversation_id` | Replaces `job_id`, `work_item_id` and `work_item_key` |
| `turn_number`, `turn_reason` | `FIRST`, `MESSAGE`, `DECIDED` (answers and decisions), `CONNECTED` (a binding resolved with parked calls), `RETRY`; packages 4 and 5 add `EVENT` and `REVIEW_RETURNED` |
| `acting_principal_id` | The person the run acts for |
| `payer_principal_id`, `model_access`, `subscription_id` | Copied from the conversation; replace `runner_credential_id` and `model_credential_id` |
| `runner_host_id`, `runner_host_kind` | Set at assignment: the host, `COMPANY` or `PERSONAL` |
| `queue_reason` | Gains `ACCOUNT_REQUIRED` (a workspace binding waits), `BUDGET_SPENT` (with `retryAt`, the reset), `MODEL_ACCESS_UNAVAILABLE`, `NO_HOST_FOR_CREDENTIALS`; keeps `AGENT_PAUSED`, `NO_RUNNER_HOST`, `RUNNER_HOST_OFFLINE`, `RUNNER_HOST_DRAINING`, `RUNNER_HOST_FULL`, `CREDENTIAL_POOL_FULL` (a subscription's), `RUNNER_HOST_REJECTED`, `ASSIGNED` |
| `failure_class` | The runner's classes, `LOST`, and the server's `RUNTIME_REMOVED` and `MODEL_NOT_ALLOWED` (§5.7) |
| `run_one_active_per_conversation_uq` | Partial unique index on `run (conversation_id) WHERE status IN ('QUEUED', 'RUNNING')`, in place of the one per work item (I11) |
| `run_repository` | Gains `binding_id` and `read_only_cause` (`AGENT`, `GRANT`, `REQUEST`) |
| `run_change_request` | Keyed by the output |

`run_event` and `run_command` do not change.

### 3.12 `decision`

| Column | Meaning |
|---|---|
| `id`, `company_id`, `conversation_id`, `run_id`, `request_id` | Unique `(conversation_id, request_id)`: a request id sent again gets the same decision |
| `kind` | `QUESTION` or `APPROVAL` |
| `question`, `options` | A question: 1 to 2000 characters, at most 10 options of 1 to 100 |
| `subject`, `reason` | An approval's subject: `{type: TOOL_CALL, systemId, tool, arguments, what}` (arguments at most 64 KiB, `what` the call in words), `{type: REPOSITORY, repositoryId, access}`, `{type: HOST, host}`, `{type: CHANGE_REQUEST, repositoryId}`; package 4 adds `{type: RESULT}`. `reason` is the agent's, for a request |
| `approver_principal_id` | Who decides: the conversation's person. Package 4 adds `approver_group_id` (the trigger's team) and a check that an approval names exactly one |
| `status` | `OPEN`, `DECIDED`, `CANCELLED` |
| `outcome`, `answer`, `option`, `note` | `ANSWERED`, `APPROVED` or `DENIED`, with what was said |
| `scope` | An approved request's: `CONVERSATION` or `AGENT` (for good) |
| `result` | An approved call's result, at most 50 000 characters |
| `decided_by_principal_id`, `decided_at`, `cancel_cause`, `created_at` | Packages 4 and 5 add `decided_by_name` for a person in the place |

At most 10 open questions and 10 open approvals per conversation (`TOO_MANY_QUESTIONS`, `TOO_MANY_REQUESTS`).

### 3.13 `output`, `attachment`

`output`, what a conversation produced: `id`, `company_id`, `conversation_id`, `run_id`, `kind` (`CHANGE_REQUEST`, `DOCUMENT`, `LINK`), `title` (at most 200 characters), `url` (a link's or a change request's: http(s), no user info, at most 2000 characters), `attachment_id` (a document's), `repository_id`, `external_number`, `change_request_state` (a change request's), `request_id` (a link's; unique per conversation), `created_at`.

`attachment`: today's table with `conversation_id` in place of `work_item_id`, for the files the person adds and the documents of outputs, scanned at upload (ADR-0014, ADR-0021), with today's limits per conversation (25 MB a file, 50 files, 250 MB). A document's file is not deleted while it is an output.

### 3.14 No outbox

Package 3 writes no outbox row: a Tinfirm thread's results are its messages, and writes to systems are tool calls the server runs. Package 4 creates the outbox, keyed by conversation, for a tracker issue's place, and listens to `TurnEnded`, which carries the place. The tool executor publishes `SystemWritten(conversationId, systemId, what, externalId)` for each write, so package 4's poller raises no event for Tinfirm's own writes.

## 4. Invariants

Each is enforced by the database where it can be, else by the service, and each has a test (§9).

| # | Invariant | Enforced by |
|---|---|---|
| I1 | An agent holds no credentials and names no principal it acts as | No such column on `agent*`; a schema test |
| I2 | An agent lists only company systems, and tools and repositories only of systems it lists | Composite foreign keys (§3.3, §3.4); trigger on `agent_system` → `PERSONAL_SYSTEM` |
| I3 | A tool the agent does not list is not available; a listed tool is `ALLOW` or `ASK` | Check on `agent_tool.mode`; `ConversationTools` (§5.4) |
| I4 | Settings narrow, never widen: on the agent's systems only `OFF` and `ASK`; `ADDED` and `ALLOW` only on the person's own MCP server; default repositories among the agent's | Checks and triggers on `agent_settings_*` → `SETTINGS_WIDEN`; the service for repositories and projects |
| I5 | One settings row per person per agent | Unique `(agent_id, principal_id)` |
| I6 | One account per system per conversation, fixed for its life; a replaced account row counts only with the same holder, system and identity | Unique `(conversation_id, system_id)`; trigger: `account_id`, `holder_principal_id`, `external_id`, `grant_id` set once, `account_id` replaced only by the holder's account on the same system with the same `external_id` |
| I7 | A binding is checked at every use and never re-binds; a gone binding stays gone | `ConversationBindings.use` (§5.3), the only path to a token; trigger: `GONE` is final |
| I8 | A personal conversation binds the person's own account, a grant to one of their groups, or their own MCP server; never another member's account | `Accounts.bind` (package 1 §5); trigger: the holder is the person, or a `SERVICE` principal whose account the bound grant lends |
| I9 | A `READ` access writes nothing: write tools are neither listed nor run, the system's repositories are read-only, no change request opens | `ConversationTools`, `Workspace.of`, the change request path |
| I10 | A conversation's payer and model access are fixed; a personal conversation's payer is its person; nobody else pays | Check `payer_principal_id = person_principal_id`; trigger refusing updates; the dispatcher takes model access only from `ModelAccess.forConversation` with the conversation's payer |
| I11 | One run at a time per conversation; conversations run in parallel | `run_one_active_per_conversation_uq` |
| I12 | Each conversation has its own key, session home and branch | Unique `(company_id, key)`; trigger refusing updates of `key`; `workItem.id` and `job.id` are the conversation's id |
| I13 | A run goes only to a host that may run its credentials | The dispatcher's candidates pass `RunnerHosts.mayRun` (package 2) |
| I14 | No system credential reaches a runner host or a sandbox except the workspace's git tokens; the model key only in `modelAccess`, which the host keeps behind its proxy | `AssignmentBuilder`, the only builder of `run.assign`; a test over its output |
| I15 | Only the person replies, answers and approves in a personal conversation; no agent decides | Service → `CONVERSATION_BOUND`; the runner endpoint has no path to decide |
| I16 | A decision is decided once | Conditional update `WHERE status = 'OPEN'` → `DECISION_DECIDED` |
| I17 | What a caller may not see is `404` | `Visibility` in every read of the services behind the web app and the MCP endpoint |
| I18 | A paused agent's conversations start no run and run no approved call | Queue reason `AGENT_PAUSED`; `AGENT_PAUSED` on deciding an approval |
| I19 | An unconfirmed MCP tool counts as `WRITE`; an `ALLOW` chosen for a read is `ASK` for a write | `ConversationTools.modeOf` |

## 5. The rules

### 5.1 Agents: who sees, uses and edits

`Agents.access(caller, agent)`:

| Caller | Sees | Uses | Edits, shares, pauses, deletes |
|---|---|---|---|
| Its owner | Yes | Yes | Yes |
| A member of a group it is shared with | Yes | Yes | No |
| OWNER, ADMIN | Yes | Only as its owner or through a group | Yes (open point 11) |
| Anyone else | No (`404`) | No | No |

An agent whose owner left (`owner_principal_id NULL`) is edited by admins and used through its groups. Deleting an agent is refused while a run of it is queued or running (`AGENT_HAS_ACTIVE_RUNS`); it ends its conversations (`AGENT_DELETED`), cancels their decisions and waiting bindings, and deletes its shares and settings. Removing a system, tool, repository or host from the agent removes it from every conversation's next turn; a removed repository leaves the workspaces where its source is `AGENT`. Package 4 adds what deleting or pausing does to triggers.

### 5.2 Settings in effect

`Settings.effective(agent, person)` is the stored row, or the defaults: the agent's first runtime, no model, `DEFAULT` effort, `BUDGET`, nothing added or switched off. A save checks the runtime among the agent's (`RUNTIME_NOT_LISTED`), the model among `ModelAccess.models(person)` (`MODEL_NOT_ALLOWED`), a subscription that is the person's and serves the runtime (`SUBSCRIPTION_NOT_SERVING`), and I4 (`SETTINGS_WIDEN`). A change applies from each conversation's next turn, except the runtime and the model access, which a conversation fixes at its start.

### 5.3 The binding of a conversation

`ConversationBindings.resolve(conversation, systemId)` runs at a system's first use: the first tool call on it, or the first turn whose workspace holds one of its repositories. It calls `Accounts.bind(Personal(person), systemId)` and stores the outcome:

| `Accounts.bind` returns | The binding | The person sees |
|---|---|---|
| `Own(account)` | `BOUND`, `OWN` | The conversation's header: "GitHub: your own account" |
| `Granted(grant)` | `BOUND`, `GRANTED` | A `SYSTEM` message and the header: "GitHub: via Analysts, read-only" |
| `Choose(grants)` | `WAITING`, `PICK_GRANT`, `choices` | A card: pick a grant, or connect your own |
| `ConnectRequired(NONE)` | `WAITING`, `NONE` | A **Connect** card: OAuth returning to the conversation, or a token |
| `ConnectRequired(RECONNECT_YOURS)` | `WAITING`, `RECONNECT_YOURS` | A card: sign in again |
| `ConnectRequired(RECONNECT_COMPANY)` | `WAITING`, `RECONNECT_COMPANY` | A card saying an admin must sign the granted account in again; the admins' inbox lists the account |
| `ConnectRequired(CHOOSE)` | `WAITING`, `CHOOSE`, `choices` | A card: reconnect your own, or use the grant |

A waiting binding is resolved again by the same call, never by a fallback: when the person chooses (`…/bindings/{systemId}/choose`: a grant among `choices` binds `CHOSEN`; "reconnect" turns `CHOOSE` into `RECONNECT_YOURS`); when package 1 reports an account connected or reconnected, a grant changed or a principal resumed on that system for someone it concerns; and on `…/continue`.

`ConversationBindings.use(binding, purpose)` is the only path to a token. It runs before every call, clone, push and change request:

| Check | Fails as | Addressed to |
|---|---|---|
| The binding is `BOUND` | It waits with its card | Its reason's |
| The holder is `ACTIVE` | `SUSPENDED`: waits; `LEFT`: `GONE` (`HOLDER_LEFT`) | Admins |
| The holder's account on the system exists with the bound identity | Missing: waits (`RECONNECT_YOURS` for one's own, `RECONNECT_COMPANY` for a grant's); another identity: `GONE` (`IDENTITY_CHANGED`) | The person; admins |
| The account is `OK` | Waits: `RECONNECT_YOURS` or `RECONNECT_COMPANY` | The person; admins, who are told |
| Through a grant: it exists and the person is in its group | `GONE` (`GRANT_REMOVED`, `LEFT_GROUP`) | The person: "start a new conversation to use GitHub" |
| The system exists, and a personal one while personal servers are on | `GONE` (`SYSTEM_REMOVED`); waits (`SYSTEM_OFF`) | The person |

It returns the token (`Accounts.reveal`, audited as `secret.accessed` with the purpose) and the access: `WRITE` for the person's own account, the grant's access now otherwise.

**Parked calls.** A tool call that meets a waiting binding is parked on it (at most 10) and answered `PENDING` with: "{system} is not connected for this conversation yet: Tinfirm asked {person} to connect it, and runs your call once they do. End your turn now." The turn ends `WAITING` (`ACCOUNT`). When the binding is `BOUND`, each parked call passes `ConversationTools.modeOf` again with the new access: `ALLOW` runs it, outside the transaction, with the purpose `binding-call:{bindingId}`, and keeps its result; `ASK` raises its approval; a refusal keeps the refusal. Once no decision and no waiting binding of the conversation is open, a `CONNECTED` turn starts with the results in `# Answers`. A binding that becomes `GONE` answers its parked calls with the reason in the next turn. A workspace that meets a waiting binding parks nothing: its run stays `QUEUED` with `ACCOUNT_REQUIRED` until the binding is bound; a gone one takes the system's repositories out of the workspace with a `SYSTEM` message.

Hook for package 4: a trigger's conversation copies the trigger's `Fixed(account)` rows into `BOUND` bindings when it starts and binds nothing later; a system without a row is not available to it (PLATFORM §5.3).

### 5.4 The tools of a run

`ConversationTools.list(conversation)` gives `run.assign.tools`; `ConversationTools.modeOf(conversation, tool)` answers each call. Each step only narrows the one before:

| Step | Rule | Otherwise: not listed, and a call answers |
|---|---|---|
| 1. Served | A tool of the fixed catalogue, or an MCP tool present and enabled in `system_tool` | `UNKNOWN_TOOL` |
| 2. Effect | The catalogue's effect; an MCP tool's confirmed effect, `UNKNOWN` counting as `WRITE` | |
| 3. Agent | A row of `agent_tool` on a system the agent lists; its mode, but `ASK` for an `ALLOW` chosen for a read while the effect is `WRITE`. On the person's own MCP server, the settings' rows instead | `TOOL_DENIED`: "`{tool}` is not one of {agent}'s tools; whoever edits the agent adds tools" |
| 4. Settings | The system or the tool `OFF` removes it; `ASK` turns `ALLOW` into `ASK` | `TOOL_DENIED`: switched off in the person's settings |
| 5. Binding | `GONE` removes the system's tools; a `READ` access removes its `WRITE` tools. An unbound system's tools are listed: the first call binds (§5.3) | `TOOL_DENIED`: "`{tool}` writes, and you act through the read-only grant of {group} on {system}"; `ACCOUNT_GONE` |
| 6. Account | The system's own permissions for the bound account | The system's answer, as the call's result with `isError` |

`ALLOW` runs the call; `ASK` raises an approval (§5.8) and answers `PENDING`. An approved call passes steps 1 to 6 again when it runs. A run lists at most 200 tools (the protocol's limit), by the systems' names, each named `{handle}_{tool}` with package 1's system handle; a description ends with whom it runs as once bound ("Runs as {person}'s own account", "Runs through {holder} via {group}, read-only").

The fixed catalogues in package 3:

| Kind | Reads | Writes |
|---|---|---|
| `JIRA_DC`, `JIRA_CLOUD` | `get`, `search` | `comment`, `transition` |
| `GITHUB`, `GITLAB` | `get`, `search` | `comment`, `label`, `open_change_request` |
| `MCP` | The server's tools by their effect | |
| `GIT` | None: repositories only | |

A comment through the person's own account starts with `*{agent} (Tinfirm agent):*`, through a grant with `*{agent} (Tinfirm agent) for {person}:*`. `open_change_request` is not called by the agent: after a turn that pushed commits to a repository without a change request of this conversation, the server opens one under that tool's mode (`ALLOW` at once, `ASK` an approval), with the conversation's binding on the code host, as `ChangeRequestOpener` does today (open point 5). Pushing `agent/{key}` needs a writable repository and no mode. Nothing else of a personal conversation is written to a system (PLATFORM §8.1).

### 5.5 The platform tools

Always available: `run.assign.platformTools` names all six of the host's. The design's names are the product's; the host keeps its own on the wire (open point 1).

| Design | On the host | `run.request` | The server |
|---|---|---|---|
| `ask(question, options?)` | `ask` | `ASK` | A question (§5.8) |
| `request(repository, reason)` | `request_access` with `repository` | `ACCESS` | An approval of a repository of a system the agent lists that the workspace lacks (`UNKNOWN_REPOSITORY`, `ALREADY_IN_WORKSPACE`, `ALREADY_PENDING`, `TOO_MANY_REQUESTS`). With `tool` instead: `TOOL_DENIED`, tools belong to the agent's definition |
| `request(host, reason)` | `request_network_access` | `NETWORK` | An approval of a host, with today's grammar and checks (`ALREADY_ALLOWED`) |
| `save_output(path \| url, title)` | `save_artifact`, and the files left in `/output` | `ARTIFACT`; the attachments endpoint | A `LINK` or `DOCUMENT` output |
| `list_repositories()` | `list_repositories` | (on the host) | The workspace and `run.assign.requestableRepositories`: the agent's repositories the workspace lacks, then the other repositories of its systems, at most 200 |
| `finish(outcome, summary, next_steps?, open_questions?)` | `finish` | `run.finished.result` | The turn's result |

### 5.6 The workspace

`Workspace.of(conversation)` at each turn: the start's `repositoryIds` (an empty list for none), else the settings' defaults, else every repository the agent lists (more than 20 without a choice is `TOO_MANY_REPOSITORIES`), plus approved requests. An `AGENT` entry leaves when the agent no longer lists the repository or its system, or the settings switch the system off. Each repository's system is bound (§5.3). A repository is read-only when the agent's access is `READ` (`AGENT`), the approval gave `READ` (`REQUEST`), or the binding's access is `READ` (`GRANT`): it is cloned, never pushed, and gets no change request. Its git token comes from `ConversationBindings.use(binding, "run:{runId}")` and stays on the host. Commits name the person: `{Person} via Tinfirm agent {agent}`.

### 5.7 Turns, model access and the runner

**Turns.** `Turns.request(conversation, reason)`: `FIRST` at the start, a person's message (`MESSAGE`), the last open decision decided (`DECIDED`), a binding resolved with parked calls (`CONNECTED`), a retry (`RETRY`). It creates the turn's run `QUEUED`. A message sent while a run is active waits, and the next turn takes every waiting message, oldest first. While questions are open, a plain message wakes nothing and a message with `answersOpenQuestions` answers them all (ADR-0033); a turn that ends on its own questions posts no summary (ADR-0036). Before the run is created: the conversation is `OPEN` (`CONVERSATION_ENDED`); the agent still lists the conversation's runtime, else the turn fails at once with `RUNTIME_REMOVED`; the workspace's systems are resolved.

**Ending, cancelling and retrying** work as today's threads did:

- **End** (the person, OWNER): waiting messages stay unanswered; the running turn finishes and its result is recorded; open decisions and waiting bindings are cancelled. An ended conversation takes nothing more (`CONVERSATION_ENDED`) and stays ended.
- **Cancel** (the person, OWNER): cancels the active run (a queued one at once, a running one through its host), the open decisions and the parked calls; waiting messages stay unanswered; the conversation stays open, `CANCELLED`.
- **Retry** (the person, OWNER): runs the last turn's request again when it `FAILED` or was `CANCELLED` and nothing is active (`TURN_ACTIVE`, `NOTHING_TO_RETRY`); bindings and model access are checked again.
- **The person leaves** (package 1's `PrincipalLeft`): their conversations end (`PERSON_LEFT`), running turns are cancelled, and the conversations stay readable to the Owner; their settings are deleted; their agents pass to the admins (`owner_principal_id NULL`, `agent.passed_to_admins`).

**The dispatcher**, per queued run, in this order:

1. The agent is paused → `AGENT_PAUSED`.
2. A workspace binding waits → `ACCOUNT_REQUIRED`.
3. `ModelAccess.forConversation(payer, model access, runtime)`: `Spent` → `BUDGET_SPENT` until `BudgetAvailable`; `Unavailable` → `MODEL_ACCESS_UNAVAILABLE`; a model the payer's key no longer allows fails the turn (`MODEL_NOT_ALLOWED`).
4. The candidate hosts: connected and configured, not draining, serving the runtime, and passing `RunnerHosts.mayRun(host, principals)` for the principals whose secrets the run carries: the payer and the holders of the workspace's git accounts (open point 7); a subscription only on its own host. None while the company has hosts for the runtime → `NO_HOST_FOR_CREDENTIALS`; none at all → `NO_RUNNER_HOST`.
5. Free slots: a subscription's pool and the host's → `CREDENTIAL_POOL_FULL`, `RUNNER_HOST_FULL`.
6. Assign: build `run.assign` (below) → `ASSIGNED`.

A run that ends `FAILED` with `USAGE_LIMIT` is reported to package 2 (`ModelAccess.runEnded`), which marks the payer's budget spent: the turn shows `FAILED` with the reset time, the payer's other queued runs wait `BUDGET_SPENT`, and nothing switches to another payer (V7, open point 8).

`POST /conversations` refuses what can never run: `MODEL_ACCESS_UNAVAILABLE` (no key, suspended, no subscription) and `NO_HOST_FOR_CONVERSATION` (no registered host, connected or not, may run its credentials by package 2's rule: a subscription whose host cannot carry the workspace's git accounts, or a company without company hosts for a conversation that its person's personal host may not run). A spent budget or an offline host does not refuse the start: the first turn waits.

**`run.assign`**, from the conversation:

| Runner field | From |
|---|---|
| `workItem.id`, `job.id` | The conversation's id, both: its own session home on the host, and the host's one active run per work item is one per conversation |
| `workItem.key`, `workItem.title` | The conversation's key and title |
| `workspaces[].branch` | `agent/{key}` (`Branches.of`): two conversations on one issue never share a branch |
| `agent` | The agent's id and name, the conversation's runtime, the settings' model and effort (effort left out when `DEFAULT`) |
| `prompt`, `resumePrompt`, `resumeSessionId` | §5.9; the session of the conversation's previous run |
| `modelAccess` | `Budget`: `{format, apiKey, baseUrl}`, the payer's LiteLLM key (`ModelAccess.reveal`, purpose `run:{runId}`) and the gateway's URL |
| `credentialLabel` | `Subscription`: its label on its owner's host |
| `workspaces[]` | §5.6: `git` from each repository's binding, `readOnly` |
| `requestableRepositories` | §5.5 |
| `tools[]` | §5.4 |
| `platformTools` | All six (§5.5) |
| `allowedHosts` | The agent's `agent_host` rows and the conversation's `granted_hosts`, sorted, distinct, at most 200, wildcards over a public suffix left out |
| `attachments` | The conversation's attachments |
| `limits` | As today |

### 5.8 Decisions

| Kind | Raised when | Decided by, in package 3 | Hook |
|---|---|---|---|
| Question | `ask`, or a `BLOCKED` result's open questions | The conversation's person, by its card or by a message that answers open questions | Packages 4 and 5: anyone who can write in the place |
| Approval | A call of an `ASK` tool; a `request` of a repository or host; a change request under `ASK` | The conversation's person (`approver_principal_id`) | Package 4: the trigger's team (`approver_group_id`); the review of a result |

`Decisions.mayDecide(caller, decision)`: the approver only. Anyone else who sees the conversation, OWNER included, gets `CONVERSATION_BOUND`; no agent decides. Approving a request with scope `AGENT` needs the right to edit the agent (`AGENT_NOT_EDITABLE`). Approving while the agent is paused is refused (`AGENT_PAUSED`); answering a question is not. An answer through a card is also a `PERSON` message quoting the question's first line, as today.

| Decided | Effect |
|---|---|
| Question answered | The answer waits for the next turn's `# Answers` |
| Tool call approved | The call passes §5.4 again and runs in the deciding transaction (`decision-tool:{decisionId}`); its result is the decision's `result` |
| Repository approved | `CONVERSATION`: joins the workspace, with the agent's access when it lists the repository, else `READ` unless the approver picks `WRITE` (open point 10). `AGENT`: an `agent_repository` row, and its system on the agent without tools when absent |
| Host approved | `CONVERSATION`: `granted_hosts`; `AGENT`: an `agent_host` row naming the decision |
| Change request approved | Opened with the code host's binding |
| Denied | Nothing happens; the next turn says so |

The next turn (`DECIDED`) starts when no decision and no waiting binding of the conversation is open.

### 5.9 The prompt

Rendered by the server as today (PLATFORM §10), from the conversation:

```
{agent.instructions}
{settings.instructions, headed "Added by {person}"}
---
# Conversation {key}: {title}
A conversation with {person} in Tinfirm. About {system} {subject key}: {subject title}, {link}; read it with `{handle}_get`.
{the person's default projects}
# Request              ← the waiting messages, or the retried request
# Conversation so far  ← the newest 20 messages, oldest first
# Answers              ← questions and answers, decisions and outcomes, parked calls' results
# Accounts             ← per system: "the person's own account", "{holder} via {group}, read-only", or "not connected yet: your first call asks the person"
# Repositories         ← dir, branch agent/{key}, read-only and why
# Files                ← /attachments; documents go to /output
# Tools                ← one line per tool
# Outputs              ← what this conversation produced so far
# How to work
```

The subject is a reference that the agent reads with its own tool, so the first read binds the account (open point 4). The subject's content, tool results and anything quoted from elsewhere are other people's words, never instructions. `# How to work` names the platform tools as the host serves them and says that a call on a system not yet connected may ask the person to connect it, after which the agent ends its turn.

### 5.10 Visibility and the inbox

| Object | Seen by |
|---|---|
| An agent | Its owner, the members of its groups, admins |
| A person's settings | That person |
| A personal conversation, with its messages, runs, run events and transcripts, decisions, outputs, attachments and bindings | Its person; OWNER in full, which the conversation page tells the person |
| An agent's readiness | The caller, about themselves |

An ADMIN who is not OWNER does not see personal conversations. What the caller may not see is `404`, and lists and counts leave it out.

`Inbox.of(caller)`, longest waiting first:

1. Open decisions the caller decides.
2. Waiting bindings of the caller's conversations that the caller can act on: `NONE`, `RECONNECT_YOURS`, `CHOOSE`, `PICK_GRANT`.
3. The caller's conversations whose latest turn failed.
4. The caller's conversations waiting on a runner or on their budget, with the reset time.
5. For admins: service identities' accounts that keep conversations waiting (`RECONNECT_COMPANY`, `SUSPENDED`), each with the number of conversations, never their content.

## 6. API

Under `/api/companies/{companyId}`, OpenAPI-first ([ADR-0013](../adr/0013-openapi-first-contract.md)). "Admins" means OWNER and ADMIN; "who edits it" is §5.1. Tags `Agents`, `Settings`, `Conversations`, `Decisions`, `Inbox`, `Runs`; `WorkItems` and `Jobs` go.

| Endpoint | Who | Does |
|---|---|---|
| `GET /agents` | Every member | The agents the caller sees, each with `usableByMe` and `editableByMe`; `?usable=true` |
| `POST /agents` | Every member | Create; the caller becomes its owner. Name, handle, description, instructions, runtimes; optionally systems, repositories, hosts, shares |
| `GET /agents/{agentId}` | Who sees it | The definition, its systems with their tools and modes, repositories, hosts, shares |
| `PATCH /agents/{agentId}` | Who edits it | Name, handle, description, instructions, runtimes |
| `PUT /agents/{agentId}/systems` | Who edits it | The systems and, per system, its tools with their modes, replaced as a whole |
| `PUT /agents/{agentId}/repositories` | Who edits it | `[{repositoryId, access}]`, replaced as a whole |
| `PUT /agents/{agentId}/hosts` | Who edits it | The network hosts, replaced as a whole |
| `PUT /agents/{agentId}/shares` | Who edits it | The groups that may use it |
| `POST /agents/{agentId}/pause`, `…/resume` | Who edits it | Kill switch; `pause` takes `stopRunning` (open point 12) |
| `DELETE /agents/{agentId}` | Who edits it | Soft delete (§5.1) |
| `GET /agents/{agentId}/readiness` | Who may use it | Before a conversation, for the caller: the settings in effect; who pays (package 2's budget left and reset, or the subscription); per system, how it would bind now (`Accounts.bind`, nothing stored); whether a host can run it |
| `GET`, `PUT`, `DELETE /agents/{agentId}/settings/me` | Who may use it | The caller's settings; `GET` gives the defaults when none are stored, `DELETE` returns to them |
| `GET /conversations` | Every member | The conversations the caller sees; filters `agentId`, `subjectSystemId` with `subjectKey`, `state`, `waitingOn`; for OWNER also `personId` |
| `GET /subjects` | Every member | The subjects of the conversations the caller sees, with counts: the app's grouping by subject |
| `POST /conversations` | Who may use the agent | Start: `{agentId, message, subject?: {systemId, key, url?, title?}, repositoryIds?, attachmentIds?}`; `201` with the conversation and its first turn |
| `GET /conversations/{conversationId}` | Who sees it | Messages, turns, decisions, outputs, attachments, bindings, workspace, hosts, who pays |
| `PATCH /conversations/{conversationId}` | The person | The title |
| `POST /conversations/{conversationId}/messages` | The person | `{body, answersOpenQuestions?}`: the next turn, or a message that waits for it |
| `POST /conversations/{conversationId}/cancel`, `…/retry`, `…/end` | The person, OWNER | §5.7 |
| `POST /conversations/{conversationId}/bindings/{systemId}/choose` | The person | `{grantId}` or `{reconnect: true}`, on `PICK_GRANT` or `CHOOSE` |
| `POST /conversations/{conversationId}/bindings/{systemId}/continue` | The person | Resolve a waiting binding again (also done on package 1's events) |
| `GET`, `POST /conversations/{conversationId}/attachments`; `GET`, `DELETE …/{attachmentId}` | Who sees it reads; the person adds and deletes | Files, scanned at upload |
| `GET /decisions`, `GET /decisions/{decisionId}` | Who sees the conversation | `?open=true`, `?mine=true` (those the caller decides) |
| `POST /decisions/{decisionId}/decide` | Its approver | A question: `{answer?, option?}`; an approval: `{outcome: APPROVED \| DENIED, note?, scope?, access?}` |
| `GET /inbox` | Every member | §5.10 |
| `GET /runs/{runId}`, `…/events`, `…/events/stream`; `POST /runs/{runId}/cancel` | Who sees the conversation; cancel: the person, OWNER | As today; a run names its person, payer, model access, host and the host's kind |

A Connect card uses package 1's `POST /me/accounts/{systemId}/oauth` with `returnTo` the conversation's page, or `PUT /me/accounts/{systemId}/token`. The MCP endpoint for people's clients moves onto conversations in package 6, through `ConversationReads` and `ConversationCommands`, which package 3 offers.

## 7. Errors

`DUPLICATE_AGENT_NAME`, `DUPLICATE_AGENT_HANDLE` (409); `PERSONAL_SYSTEM` (package 1's: an agent listing a personal MCP server, 400); `SYSTEM_NOT_ON_AGENT` (a tool, repository or default of a system the agent does not list, 400); `UNKNOWN_TOOL` (a tool its system does not serve, 400); `RUNTIME_NOT_LISTED`, `MODEL_NOT_ALLOWED`, `SUBSCRIPTION_NOT_SERVING`, `SETTINGS_WIDEN` (400); `AGENT_NOT_USABLE` (the caller sees the agent but may not use it, 403); `AGENT_NOT_EDITABLE` (403); `AGENT_HAS_ACTIVE_RUNS`, `AGENT_PAUSED` (409); `TOO_MANY_REPOSITORIES`, `NETWORK_HOST_LIMIT` (409); `MODEL_ACCESS_UNAVAILABLE`, `NO_HOST_FOR_CONVERSATION` (409, with `details.reason`); `CONVERSATION_ENDED`, `CONVERSATION_BOUND`, `TURN_ACTIVE`, `NOTHING_TO_RETRY` (409); `BINDING_NOT_WAITING` (409), `GRANT_NOT_OFFERED` (400); `DECISION_DECIDED` (409); the attachments' codes as today; `NOT_FOUND` for anything the caller may not see (404, never 403).

On the runner protocol, a `run.request.ack` `REJECTED` carries `RUN_NOT_ACTIVE`, `INVALID_REQUEST`, `CONVERSATION_ENDED` (for `WORK_ITEM_CLOSED`), `UNKNOWN_TOOL`, `TOOL_DENIED`, `ACCOUNT_GONE` (new), `UNKNOWN_REPOSITORY`, `ALREADY_IN_WORKSPACE`, `ALREADY_PENDING`, `ALREADY_ALLOWED`, `TOO_MANY_QUESTIONS`, `TOO_MANY_REQUESTS`, `TOOL_FAILED` or `RESULT_TOO_LARGE`; the schema lists codes as examples, so nothing in it changes. `CREDENTIAL_REQUIRED` and `NO_ACCESS` go. A call parked on a waiting binding is `PENDING`, with `payload.decisionId` the binding's id (open point 2). A turn's failure class is the runner's, or the server's `LOST`, `RUNTIME_REMOVED`, `MODEL_NOT_ALLOWED`.

## 8. Audit actions

In the company's chain. `details` hold ids and kinds; instructions, messages, questions, answers, reasons, a call's arguments and results, and account names at a system stay out, as today. The actor is USER for people, AGENT for an agent's requests and calls, SYSTEM for the dispatcher and what events cause.

| Action | `details` |
|---|---|
| `agent.created`, `agent.changed`, `agent.deleted` | Name, handle, runtimes, owner; `changed` on a change; never the description or the instructions |
| `agent.tools_changed`, `agent.repositories_changed`, `agent.hosts_changed` | The whole list after the change (systems with tools and modes; repositories with access; hosts), with `decisionId` when an approval added an entry for good |
| `agent.shares_changed` | The groups |
| `agent.paused`, `agent.resumed`, `agent.passed_to_admins` | `stopRunning` and the runs cancelled; the leaving owner |
| `settings.changed` | Agent, person, the fields changed, the systems and tools switched off or to `ASK`, the own servers added; never the instructions |
| `conversation.started` | Agent, person, place, origin, subject system and key, payer, model access, runtime |
| `conversation.ended` | Cause, number of turns |
| `conversation.binding.bound` | System, `how`, account, holder, grant and its access then |
| `conversation.binding.waiting`, `conversation.binding.gone` | System, reason; once per wait |
| `conversation.read` | Conversation, person, `what` (`conversation`, `conversations`, `runs`, `run-events`, `run-stream`, `attachments`, `attachment`, `decisions`); by OWNER, who is not its person, on every read with no throttle (package 6, open point 9): a list once per person it lists, a decision page once per conversation, a stream once when it opens |
| `run.started` | Conversation, turn and reason, payer, model access |
| `run.assigned` | Host, its kind (`COMPANY`, `PERSONAL`), payer: the model access that paid and the host that ran it |
| `run.finished`, `run.cancelled`, `run.resumed` | As today, by conversation |
| `tool.called` | Conversation, run, system, tool, effect, mode, account, holder, grant, `decisionId` for an approved call, `ok`, `code` |
| `tool.denied` | Once per run and tool: the tool and the step that refused it |
| `decision.created`, `decision.decided`, `decision.cancelled` | Kind, subject type, outcome, scope, decider, cause |
| `output.created` | Kind, attachment or URL |
| `attachment.uploaded`, `attachment.deleted` | As today |
| `secret.accessed` | Purposes `run:{runId}` (a workspace's git token, the LiteLLM key), `run-tool:{runId}`, `decision-tool:{decisionId}`, `binding-call:{bindingId}`, `change-request:{runId}`; with the owner's `userId` for a member's own account |

## 9. Tests

| Test | Proves |
|---|---|
| Constraint tests per I2, I4, I5, I6, I8, I10, I11, I12 against PostgreSQL | The database refuses what it can |
| `ConversationTools` table test: `ALLOW` and `ASK` on the agent; a tool it does not list; settings `OFF` and `ASK`; settings trying `ALLOW` on the agent's tool; an `ALLOW` chosen for a read whose effect became `WRITE`; an unconfirmed MCP tool; a `READ` and a `WRITE` grant; an unbound system; a gone binding; the person's own MCP server for them and for another member | §5.4, I3, I4, I9, I19 |
| `ConversationBindings` table test over each outcome of `Accounts.bind`, and `use` with: a suspended holder, a holder who left, an account to reconnect, an account replaced with the same and with another identity, a grant removed, the person out of the group, a grant lowered to `READ`, a system removed, personal servers off | §5.3, I6, I7, I8 |
| Parked calls: a call on an unconnected system is `PENDING` and the turn waits; connecting runs it once (an `ASK` tool raises its approval instead) and starts one `CONNECTED` turn with the result; a gone binding answers it with the reason; a request id sent again parks nothing twice | §5.3 |
| The dispatcher's order: paused agent, waiting workspace binding, spent budget, no host may carry the credentials, full hosts | §5.7, I13, I18 |
| Turns: a message during a run; plain and answering messages while questions are open; end, cancel and retry by the person and by OWNER; a runtime the agent dropped | §5.7, ADR-0033, ADR-0036 |
| Decisions: the approver decides; OWNER gets `CONVERSATION_BOUND`; scope `AGENT` without the edit right; a second decide; an approval on a paused agent | §5.8, I15, I16, I18 |
| Visibility of agents (owner, group member, admin, other) and of personal conversations (person, OWNER, ADMIN, other member) over every read path, and tenant isolation over every new path | §5.1, §5.10, I17 |
| A snapshot of `run.assign`: ids, key and branch, model access from package 2's stub, git tokens only from bindings and only in `workspaces[].git`, tools, hosts | §5.7, I12, I14 |
| **V1** Alice and Bob talk to one shared agent | Two conversations with their own keys and branches run at once; each tool call reaches the Jira stub with its person's token; each `run.assign.modelAccess` carries its person's key; Bob gets `404` for Alice's conversation and its runs, decisions and outputs; neither inbox shows the other's |
| **V2**, the conversation's half: the BA agent in Alice's conversation | The prompt carries the agent's instructions unchanged, then Alice's additions; Jira binds Alice's own account; a comment on the subject is an approval Alice decides, written with her account and the agent's prefix; nothing else reaches Jira. Package 4 adds the trigger's half |
| **V5** An analyst through a `READ` grant | The conversation binds `GRANTED` and says "GitHub: via Analysts, read-only"; the workspace is read-only (`readOnly`, nothing pushed, no change request); no write tool is listed; a crafted write call is `TOOL_DENIED` naming the grant; an approved `WRITE` repository request stays read-only |
| **V6** Connecting one's own account mid-conversation | Bound through a grant, the person connects their own account: the conversation keeps the grant, a new conversation binds the own account. Without any account: a Connect card, the call parked, OAuth returns to the conversation, the call runs |
| **V7** A spent budget | A run fails `USAGE_LIMIT`; the payer's other conversations wait `BUDGET_SPENT`; another person's conversation with the same agent runs with their key; no `run.assign` carries another principal's key; `BudgetAvailable` dispatches the waiting runs |
| **V8** Bob's conversation and Alice's personal host | With Alice's host free and the company hosts full, Bob's run waits; no frame to Alice's host carries Bob's key or git tokens; Alice's own conversation runs there; her conversation whose workspace binds a grant's git account does not |
| **V10** An unconfirmed MCP tool under a `READ` grant | Not listed, and a call is refused as a write; under the person's own account the same tool is listed as `ASK` when the agent was filled with defaults |
| **V11** A prompt-injected agent | `run.assign` carries no secret but `modelAccess.apiKey` and `workspaces[].git`; a request of another run, an old epoch or an ended run is `RUN_NOT_ACTIVE`; an unlisted tool is `UNKNOWN_TOOL`; a `request` of a host waits for the person. End to end with `--model-proxy`: the sandbox's environment holds the dummy key, the listener refuses another run's path and stops answering when the run ends |

## 10. Open points

1. **Platform tool names on the wire.** The design names `request` and `save_output`; the host serves `request_access`, `request_network_access` and `save_artifact`, and the schema's `platformTools` enumerates them. Renaming changes the host and the schema, which Part 7 rules out beyond the model proxy. Proposal: keep the host's names in package 3; the app and the documents use the design's names, the prompt the host's; rename in a later host release that accepts both names for one version.
2. **The Connect card on the wire.** A call parked on a waiting binding needs an answer the host treats as "a person must act". Proposal: `PENDING` with `payload.decisionId` set to the binding's id (the host shows only the message), and the description of `$defs/Decision` says so; no field or code changes. The alternative, `REJECTED` with `ACCOUNT_REQUIRED` and nothing parked, is simpler, but the agent must call again and the call does not "continue once they connect".
3. **The runtime of a conversation.** Settings choose the runtime, but a session belongs to one runtime, and a subscription and Codex's API format are bound to it. Proposal: a conversation fixes its runtime at the start; model and effort follow the settings at every turn; when the agent drops the runtime, the next turn fails with `RUNTIME_REMOVED` and the person starts a new conversation.
4. **The subject's brief in the prompt.** PLATFORM §10 lists "the subject and its brief", but there is no work item, and fetching the brief on the server would bind an account before the person sees why. Proposal: the prompt carries the reference and the title given at the start and tells the agent to read the subject with `{handle}_get`; revisit in package 4, whose triggers have the issue at hand.
5. **Who opens a change request.** Part 6 makes it a write tool with a mode. Proposal: the server opens it after a turn that pushed, under the mode of `open_change_request` on the agent, reusing `ChangeRequestOpener`; the agent does not call it, and an agent without that tool leaves a pushed branch. An agent-called tool can come later if agents need to set the title and body.
6. **Modes of the person's own MCP server.** Settings never turn `ASK` into `ALLOW`, but the person's own servers are not on the agent. Proposal: the person sets `ALLOW` or `ASK` per tool of their own server, filled with reads `ALLOW` and writes `ASK`: they are its only definer and audience.
7. **Which credentials tie a run to a personal host.** PLATFORM §11.1 says "whose credentials are all their owner's (their model access and their own accounts)", and tool accounts never leave the server. Proposal: count what reaches the host: the payer's model access and the workspace's git accounts. A conversation that uses a grant only for Jira tools may still run on its person's personal host.
8. **The turn that hit the budget.** Proposal: it stays `FAILED` (`USAGE_LIMIT`) with Retry and the reset time, and is not retried by itself, since it may have done part of its work; the payer's queued runs wait and start when the budget is available.
9. **A grant changed after binding.** The account is fixed; its access is the company's current decision. Proposal: `use` reads the grant's access at every use, so a grant lowered to `READ` refuses writes from the next call, and raised to `WRITE` lists writes from the next turn; a deleted grant makes the binding `GONE`.
10. **Repositories beyond the agent's list.** Proposal: `request` may name any repository of a system the agent lists; approved for the conversation, it comes with the agent's access when the agent lists it, else `READ` unless the approver picks `WRITE`; for good, only by someone who may edit the agent.
11. **Admins and members' agents.** PLATFORM says admins see every agent, not that they edit it. Proposal: admins edit, share, pause and delete every agent, as `MANAGE_AGENTS` lets them today, each change audited with its editor: a shared agent is company infrastructure and its owner may be away.
12. **Pausing and running turns.** "Its conversations' turns wait" says nothing of a turn already running. Proposal: `pause` takes `stopRunning` (default false); true cancels the running turns, for a compromised agent.
13. **Resuming across hosts.** The dispatcher spreads runs over hosts, and a turn on another host than the last starts a new session with the full prompt. Proposal: prefer the host of the conversation's previous run when it may run it and has a free slot.

## 11. Decided on review (2026-10-12)

- **Open points 1 to 13**: the proposals above are adopted as written. Points 4 (the subject's brief) and 10 (repositories beyond the agent's list) are to be revisited with package 4.
- **Widening needs acceptance** (the security review's row 11, decided after this contract was drafted). Every change of an agent makes a new **version** (`agent_version`: `agent_id`, `version`, the definition as of that version, `widens`: whether it adds a system, tool, repository or network host, raises a repository to `WRITE` or a tool to `ALLOW`). A person's settings record the version they accepted (`agent_settings.accepted_version`), and a trigger's settings the version its author accepted (package 4). The effective agent of a turn is the current version narrowed by the accepted one: narrowing applies at once; widening waits until the person (or the trigger's author) accepts it with `POST /agents/{agentId}/accept {version}`, which the app offers with what widened. Instructions and descriptions follow the current version. Audit: `agent.widened`, `agent.accepted`. Test: an agent that adds an `ALLOW` write tool lists it for a person only after they accept, and removing a tool removes it at once.
- **Package 1 as built**: §2's dependencies above use its actual signatures. `Accounts.bind` answers `Own`, `Granted`, `Choose` (write grants before read, then oldest), `ConnectRequired` (with the choices for `CHOOSE`), `Fixed` and `NotEntitled`; a grant whose holder is suspended is not offered. The OAuth callback is `/api/oauth/callback`, returning to `returnTo` with `account=connected|denied|…`.
- **Tool names longer than 64 characters.** A system's handle has at most 20 characters and an MCP tool's name at most 64, so `{handle}_{tool}` can exceed the 64 characters runtimes accept. Such a tool is listed as `{handle}_{the tool's name cut to fit}_{4 hex of its hash}`, kept per conversation so that the name is stable across its turns, and the server maps it back before the call.

