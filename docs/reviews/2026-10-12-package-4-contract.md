# Package 4 contract: triggers

Status: decided on review (§11) · 2026-10-12 · Owner: J. Balcar

The contract for package 4 of the [platform redesign](2026-10-12-platform-redesign.md#part-9-how-it-is-built--decided): company automation. A trigger is a standing rule on a system: when an event happens to an issue or a change request, or a schedule is due, an agent does an instruction with an account per system and the model access its author chose; its team sees its conversations and decides their approvals, and its results go back to the place. Jira Data Center and Cloud come first, then GitHub and GitLab, then schedules. The contract holds the tables, the invariants they keep, the rules as functions later code calls (entitlement, polling, events, firing, the write-back), the API the trigger editor needs, the errors, the audit actions and the tests. It builds on packages 1 to 3 as built (CONVENTIONS §15.1 to §15.7) and fills the hooks the [package 3 contract](2026-10-12-package-3-contract.md) left; where it changes something built, §2.2 says so. The schema starts fresh (Part 9); package 4's migrations start at `V011`.

## 1. Scope

| In package 4 | Later |
|---|---|
| Triggers on `JIRA_DC` and `JIRA_CLOUD`, then `GITHUB` and `GITLAB`: events, subject, scope, conditions and repeat as [ADR-0043](../adr/0043-trigger-rules.md); the agent, the instruction, the trigger's settings with the version its author accepted | Channel triggers, the chat bots and the generic webhook (package 5) |
| The account table, an account per system filled from one choice; the model access; the team; where results go | Approvals as buttons in Slack and Teams (package 5) |
| Entitlement at save and at every use: a lost right pauses the trigger, a broken account makes it wait | Spend per trigger and per author (package 6; runs carry `trigger_id` for it) |
| Polling per account: windows, cursors, snapshots, comments, de-duplication, Tinfirm's own writes, rate limits, backoff | Webhooks as hints for the next poll (open point 8); Jira's changelog for subjects never read (PLATFORM §14) |
| Events to conversations: one open conversation per trigger and subject on `TRACKER_ISSUE`, `EVENT` turns | An agent's tool that creates issues: the chain rule of §5.6 is ready for it |
| Questions answered by a comment on the issue; approvals and the review of a result by the team | |
| The write-back outbox keyed by conversation: the result as a comment, transitions, labels, closing, documents, remote links | |
| Schedules: cron and time zone, overlap and catch-up ([ADR-0032](../adr/0032-routines.md)), the `SCHEDULE` place | |
| Visibility of triggers and their conversations; the inbox for authors, teams and admins | The MCP endpoint on conversations (package 6) |
| Kill switches: pausing a trigger; what pausing or deleting an agent, suspending or deleting a service identity, disconnecting an account, removing a system and an author who leaves do to triggers | The rest of a member who leaves (package 6) |

## 2. What it builds on

### 2.1 Packages 1 to 3, as built

| Package | What package 4 uses, by its built name | For |
|---|---|---|
| 1 | `Accounts.bind(AccountBinding.Trigger(companyId, authorPrincipalId, accountId), systemId)` → `Fixed(account)`, `ConnectRequired(systemId, reason, choices)` or `NotEntitled(systemId, refusal)` with `NO_SYSTEM`, `NOT_OWNER`, `PERSONAL_SYSTEMS_OFF`, `PRINCIPAL_INACTIVE`, `ACCOUNT_NOT_USABLE` | Saving a trigger's table (§5.2) |
| 1 | `Accounts.check(AccountBinding.Stored(companyId, systemId, actingPrincipalId, holderPrincipalId, grantId, externalId))` with the author as the acting principal and no grant → `Usable(account, WRITE)` or `Stopped(reason)`, `USE_RIGHT_GONE` among the reasons | Every firing and every use (§5.3) |
| 1 | `Accounts.reveal(companyId, accountId, actingPrincipalId, purpose)` outside any transaction; `Accounts.refused(companyId, accountId, reason)` | A poll's token; a poll the system refused |
| 1 | `Principals.mayUse(companyId, userId, principalId)`, `usableServiceIdentities(companyId, userId)`, `ofMember` | The author's right to a service identity's accounts and budget |
| 1 | `PrincipalEvents` (`PrincipalSuspended`, `PrincipalResumed`, `PrincipalLeft`, `ServiceIdentityUsersChanged`), `GroupMembersChanged`, `AccountEvents` (`AccountConnected`, `AccountReconnected`, `AccountDisconnected`, `AccountNeedsReconnect`), `SystemEvents` (`SystemDeleted`, `RepositoryRemoved`); the port `PrincipalDependents` (`memberLeaving`, `principalDeleting`) | Checking triggers again (§5.3); an author who leaves (V9) |
| 1, 3d | `SystemTools.call(companyId, systemId, CallCredential, tool, arguments, commentLabel)` and `check`, `ToolOutcome` (`Done` with `written`, `Reconnect`, `Refused`), `FixedTools`, `OutboundHttp`, `JiraMarkup`, `Adf`; `SystemTools.openChangeRequest` | The write-back's comment, transition, label and close (§5.9) |
| 2 | `ModelAccess.forRun(RunModelContext(companyId, payingPrincipalId, ModelAccessChoice, runtime, workspaceAccountHolders))` → `Budget`, `Subscription`, `Wait(BUDGET_SPENT, KEY_ERROR, HOST_OFFLINE)` or `Refuse(PRINCIPAL_INACTIVE, GATEWAY_NOT_CONFIGURED, SUBSCRIPTION_GONE, PERSONAL_HOST_CREDENTIAL_ONLY)`; `ModelAccess.models(companyId, principalId)`; `RunModelAccess.allows`; `ModelEvents.ModelKeyStatusChanged` | The payer at save, at each firing and at dispatch |
| 2 | `Hosts.eligible(RunModelContext)` → `Eligible(hosts, whyNone)` or `Nowhere(Refuse(CANNOT_RUN_ANYWHERE))` | The editor's "can run" (Part 7: a subscription with a service identity's git account runs nowhere) |
| 3a | `Agents.access(companyId, agentId, userId)`; `EffectiveAgent`, `AgentDefinition.narrowedBy`; `SettingsOwner.Trigger(triggerId)`, for which `Settings.effective` throws `UnsupportedOperationException` until package 4; `AgentEvents` (`AgentWidened`, `AgentPaused`, `AgentResumed`, `AgentDeleted`, `AgentShared`, `AgentPassedToAdmins`); `AgentRuns` | A trigger's settings (§3.3), widening, kill switches |
| 3b | `Turns.start(NewTurn)`, `Turns.cancelActive`, `TurnReason`; the dispatcher's order; the ports `TurnSource`, `TurnEnded`, `RunVisibility`, `RunRequests` | A trigger's turns are runs |
| 3c | `ConversationInfo` (`Place.TRACKER_ISSUE`, `Place.SCHEDULE` and `Origin.TRIGGER` are reserved), `ConversationBindings` (`resolve`, `check`, `use`), `ConversationTurns.request`, `ConversationMessages.post`, the seams `TurnEndHook`, `BindingHook`, `QuestionAnswers`, `OpenDecisions`; `ConversationEvents` (`ConversationStarted`, `ConversationEnded`, `BindingBound`, `BindingWaiting`, `BindingGone`, `SystemWritten`); `ChangeRequestOpener`, `CommitAuthor`, `PromptRenderer`, `DecisionService`, `InboxService` | A trigger's conversation is a conversation |

### 2.2 What package 4 changes in what is built

| Built | Change | Why |
|---|---|---|
| `TurnReason`, `run_turn_reason_ck` | Gain `EVENT` and `REVIEW_RETURNED` | §5.6, §5.8 |
| `TurnSource.plan` (`Ready`, `AccountRequired`, `Cancel`), `run_queue_reason_ck` | `plan` answers a paused trigger's turn with a wait, queue reason `TRIGGER_PAUSED` | §5.11 |
| `run_payer_ck CHECK (payer_principal_id = acting_principal_id)` (V004), `conversation_payer_ck CHECK (payer_principal_id = person_principal_id)` (V005) | Relaxed for a trigger's conversations by the exact rules of §3.4 and §3.7 | Package 3's I10 |
| `NewTurn` | Gains `@Nullable UUID triggerId`, copied to `run.trigger_id` | §3.7 |
| `conversation.person_principal_id NOT NULL` | Nullable, beside `trigger_id` and `author_principal_id`; exactly one owner | §3.4 |
| `conversation_binding.how`, the trigger `conversation_binding_ck` | `FIXED`, with its own branch: the holder is the conversation's author or a service identity | §3.5 |
| `ConversationBindings.resolve` | For a trigger's conversation stores nothing and answers the system unavailable | T5 |
| `decision.approver_principal_id NOT NULL`, `decision_subject_ck` | Nullable, beside `approver_group_id`; subject `RESULT`; `decided_by_name` | §3.8 |
| `message_kind_ck`, `message_author_ck` | `EXTERNAL`, with `external_id` and `external_author` | §3.6 |
| `ConversationInfo.EndedReason` | Gains `AUTHOR_LEFT`, `TRIGGER_DELETED`, `SUBJECT_CLOSED`, `SUPERSEDED` | §5.6, §5.10, §5.11 |
| `SystemWritten(companyId, conversationId, systemId, what, externalId)`, `what` a text such as `COMMENT SHOP-241`; published by `ToolRunner` only | Gains `kind`, `subjectKey` and `values` (the status entered, the labels added and removed); also published by `ChangeRequestOpener` (`CHANGE_REQUEST`) and at a turn's end for each pushed branch (`PUSH`, with the head commit) | Today the poller could not tell which status or labels Tinfirm wrote, and Tinfirm's own change requests and pushes raise events (open point 13) |
| `TurnEnded.TurnEnd` carries no place, although the package 3 contract (§3.14) said it would; `TurnEndHook` is internal to `conversation` | The public event `ConversationEvents.TurnRecorded(companyId, conversationId, runId, place, triggerId, status, outcome)`, published by `TurnEnded.turnEnded` after the hooks, in its transaction | The `trigger` module fills its outbox from it (§5.9) |
| `Conversations.maySee`, `RunVisibility`, `DecisionService.mayDecide`, `InboxService` | Ask the trigger's audience through the port `TriggerAudience` that the `trigger` module implements | §5.13 |
| `ToolRunner`'s comment label, `CommitAuthor` | In a trigger's conversation: `{agent} (Tinfirm agent):`; commits by `Tinfirm agent {agent} for {trigger}` | §5.7 |
| `PrincipalDependents.principalDeleting` of a service identity | Refused while a live trigger names it: `409 SERVICE_IDENTITY_IN_USE` (package 1 promised it) | §5.11 |
| `Settings.effective(…, SettingsOwner.Trigger)` | Answered from the trigger's row | §3.3 |

### 2.3 The application before the redesign

| Reused, ported | Reworked | Not created |
|---|---|---|
| `PollWindow` (the window, its overlap, the offset within a listing's precision); `EventDetector` and `TriggerEvaluator`, both pure; `TriggerValidator`'s "could never fire" checks; `Schedule` (the cron grammar, the 15-minute floor) and `ScheduleDecisions` (overlap, catch-up); the source systems' listings (`JiraSourceSystem`, `JiraCloudSourceSystem` and its cursor, `GitHubSourceSystem`, `GitLabSourceSystem` and its fall-back from projects to groups); `OutboxDelivery`'s rounds, claims, retries and markup conversion; `WriteBack`'s actions per kind | `Poller`: one poll per account (`trigger_poll`) instead of per connection. `SubjectSync`: the snapshots are the poller's own (`trigger_subject`), not a work item's. `EventDetector`: assigned and mentioned mean the poll's account; Tinfirm's writes are recognised by its record, never by identity. `trigger_firing` keyed by subject and conversation. The outbox keyed by conversation and written with its binding. `ManualRuns` on a subject key | `connection_trigger`, work items and their snapshots, `sync_comment` and `sync_remote_link` (now `system_write`), the built-in Schedules connection, agents' accounts on trackers, `TriggerTurn`, the group rights `RUN` and `DECIDE`, the write-back of a person's thread (Part 5) |

A new module, `trigger`, holds triggers, polling, firings, schedules and the write-back. It depends on `conversation`, `run`, `agent`, `account`, `system`, `principal`, `company`, `model`, `audit` and `shared`; the conversation module reaches it only through the port `TriggerAudience` (looked up when used, as the run module's ports are) and through its own events. The `system` module gains `SystemPolls` (a listing page, one subject with its newest comments) and `SystemWrites` (an attachment, a remote link), ported from the earlier application's source systems, outside any transaction and through `OutboundHttp`. On landing, package 4 adds CONVENTIONS §15.8.

## 3. Tables

### 3.1 `trigger`

| Column | Meaning |
|---|---|
| `id`, `company_id` | |
| `name` | 1 to 100 characters, unique among the company's live triggers ignoring case |
| `source`, `system_id` | `TRACKER` with a company system of kind `JIRA_DC`, `JIRA_CLOUD`, `GITHUB` or `GITLAB`: the system it belongs to; or `SCHEDULE`, with `system_id` `NULL` (open point 5). Both fixed |
| `events`, `subject`, `scope`, `conditions`, `repeat` | As ADR-0043 and the earlier application's rules per kind ([archived CONVENTIONS §14.5](../archive/v2/CONVENTIONS.md#145-connections)): 1 to 5 `{event, value}`; `ANY`, `ISSUE` or `CHANGE_REQUEST`; a Jira project key, `owner/repo` or a GitLab path, or none (everything the source account sees); at most 5 condition groups, whose `authors` are `PEOPLE` or `TINFIRM` (§5.5); `ONCE` or `EVERY_TIME` |
| `schedule_cron`, `schedule_zone`, `overlap`, `catch_up`, `next_run_at`, `last_run_at`, `last_outcome` | A schedule's, as ADR-0032: five fields, at most every 15 minutes, an IANA zone; `SKIP` or `CREATE`; `NONE` or `ONE` |
| `agent_id` | The agent; fixed. A trigger for another agent is another trigger |
| `instruction` | What the job is, 1 to 20 000 characters, written by the author |
| `author_principal_id` | A member's principal: the creator, or whom an admin reassigned it to (§5.3) |
| `team_group_id` | The team: sees its conversations, decides their approvals, runs it now. `NULL`: the author alone (open point 3) |
| `payer_principal_id` | Who pays: the author, or a service identity the author may use. The model access itself is the settings' (§3.3) |
| `results` | `{comment, review, onSuccess, onFailure}`: whether turns' results and failures are written to the issue as comments; whether a result is reviewed first; at most 10 actions each, per kind as in the earlier application: Jira `TRANSITION`, `ATTACH_DOCUMENTS`; GitHub and GitLab `LABEL_ADD`, `LABEL_REMOVE`, `CLOSE` |
| `max_firings_per_hour` | 1 to 300, 30 by default (§5.6) |
| `status`, `paused_reason`, `paused_at`, `paused_by` | `ACTIVE` or `PAUSED`; `USER`, `AUTHOR_LEFT`, `NOT_ENTITLED`, `ACCOUNT_REMOVED`, `SYSTEM_REMOVED` |
| `waiting_reason`, `waiting_since` | Its health, kept for lists and the inbox: `RECONNECT_YOURS`, `RECONNECT_COMPANY`, `SUSPENDED`, `SYSTEM_UNREACHABLE`, `SCOPE_UNREADABLE`, `GATEWAY_NOT_CONFIGURED`; `NULL` when nothing waits |
| `armed_at` | A change before it fires nothing (T16): the creation, each resume, each reassignment |
| `missed_count`, `rate_skipped_count` | Changes read too late (§5.4) and firings over the rate, since the last resume |
| `open_events_acknowledged_at`, `open_events_acknowledged_by` | The author's acknowledgement of the editor's warning (§5.8) |
| `deleted_at`, `deleted_cause` | Soft delete: the row stays for its conversations' history |
| `created_at`, `updated_at`, `created_by` | |

At most 100 live triggers per system and 50 schedules per company.

### 3.2 `trigger_account`

The account table: `(company_id, trigger_id, system_id, account_id, holder_principal_id, external_id, filled_from, saved_at, saved_by)`, primary key `(trigger_id, system_id)`, one row per system the trigger needs, at most 100.

- `account_id` is the account the trigger's conversations bind, `holder_principal_id` its principal (the author or a service identity, T1), `external_id` its identity when saved. `account_id` becomes `NULL` when the account goes (`ON DELETE SET NULL`): the trigger pauses `ACCOUNT_REMOVED` until its author or an admin names another or removes the row.
- `filled_from` is `CHOICE` (the editor's one choice filled it) or `ROW` (the author changed it).
- The source system's row is required and needs an identity (`SOURCE_ACCOUNT_REQUIRED`): it polls and writes back. A system of the agent without a row is not available to the trigger's runs. A row on a system the agent no longer lists stays, unused.
- There is no grant: grants serve personal conversations only (the model on one page).

### 3.3 A trigger's settings

`agent_settings` gains `trigger_id` as its other owner: `principal_id` becomes nullable, `agent_settings_owner_ck` wants exactly one, unique `(trigger_id)`, a foreign key to `trigger`. The row is made with the trigger and goes with it. Package 3's columns and checks hold, with:

- `accepted_version`: the agent's version the author accepted for this trigger. Its runs use the current version narrowed by it; a widening waits until the author accepts it (`POST /triggers/{id}/accept`).
- `model`: one the payer's key allows (`ModelAccess.models(companyId, payer)`).
- `model_access`, `subscription_host_id`, `subscription_label`: `BUDGET`, the payer's; `SUBSCRIPTION` only when the payer is the author, on the author's own personal host.
- `default_repository_ids`: the workspace of the trigger's conversations.
- `instructions`: always `NULL` (`agent_settings_trigger_instructions_ck`, V033; a save with them is `400 VALIDATION_FAILED`). The trigger's instruction is in every turn (§5.12), so its author's words have one place.
- `agent_settings_system.ADDED`: the author's own MCP servers, only while every row of the table holds the author's own account (security review, row 12; `SETTINGS_WIDEN`).

### 3.4 `conversation` (changed)

| Column | Change |
|---|---|
| `person_principal_id` | `NULL` for a trigger's conversation |
| `trigger_id` | The trigger; `NULL` for a personal conversation |
| `author_principal_id` | A trigger's conversation: the trigger's author when it started, fixed. Its bindings act for this principal (`Stored.actingPrincipalId`), and it is its runs' `acting_principal_id` |
| `place`, `place_ref` | `TRACKER_ISSUE` with `{systemId, subjectId, key, kind, url}`, `subjectId` the system's stable id (Jira's issue id, GitHub's and GitLab's global id), so that a moved issue stays one place; `SCHEDULE` with `{triggerId, firingId, dueAt}` |
| `origin` | `TRIGGER` |
| `chain_depth` | 0, or one more than the creating conversation's for a conversation fired by a subject Tinfirm created (§5.6); at most 3 |
| `ended_reason` | Gains `AUTHOR_LEFT`, `TRIGGER_DELETED`, `SUBJECT_CLOSED`, `SUPERSEDED` |

`conversation_payer_ck` becomes: `(trigger_id IS NULL AND author_principal_id IS NULL AND person_principal_id IS NOT NULL AND payer_principal_id = person_principal_id) OR (trigger_id IS NOT NULL AND person_principal_id IS NULL AND author_principal_id IS NOT NULL AND origin = 'TRIGGER' AND place IN ('TRACKER_ISSUE', 'SCHEDULE'))`. The insert trigger `conversation_trigger_ck` checks that the payer and the author are the trigger's at that moment. `conversation_fixed_ck` adds `trigger_id`, `author_principal_id`, `place`, `place_ref ->> 'subjectId'` and `chain_depth`. The partial unique index `conversation_trigger_subject_open_uq ON conversation (trigger_id, (place_ref ->> 'subjectId')) WHERE state = 'OPEN' AND place = 'TRACKER_ISSUE'` keeps one open conversation per trigger and subject (T11).

### 3.5 `conversation_binding` (changed)

`how` gains `FIXED`: a trigger's conversation has only `FIXED` bindings, one per row of the table with an account, inserted `BOUND` in the transaction that starts it, with the row's account, holder and identity and no grant; a personal conversation never has one. `conversation_binding_ck` gains the branch: for a trigger's conversation the holder is its `author_principal_id` or a `SERVICE` principal. `reason` gains `NOT_ENTITLED` (waiting: the author lost the right to the service identity, §5.3).

### 3.6 `message` (changed)

`kind` gains `EXTERNAL`: what someone wrote in the place, a comment on the issue, with `external_id` (the comment's id; unique `(conversation_id, external_id)`), `external_author` (`{id, login, name}` at the system) and `author_principal_id` set when a member's account on that system has that identity (package 1's I2), else `NULL`. Its body is the comment in Markdown (`JiraMarkup`, `Adf`), cut to 100 000 characters. `message_author_ck` becomes: `PERSON` has an author; `EXTERNAL` has `external_id` and `external_author`; `AGENT` and `SYSTEM` have neither. A team member's message in Tinfirm is a `PERSON` message.

### 3.7 `run` (changed)

`trigger_id` is copied from the conversation; `acting_principal_id` is the person or the author; `turn_reason` gains `EVENT` and `REVIEW_RETURNED`. The relaxed I10, exactly: `run_payer_ck CHECK (trigger_id IS NOT NULL OR payer_principal_id = acting_principal_id)`, and the insert trigger `run_conversation_ck`: the run's `trigger_id`, `acting_principal_id` (`coalesce(person_principal_id, author_principal_id)`), `payer_principal_id`, `model_access` and subscription are its conversation's. So a personal run is still paid by its person, and a trigger's run only by its conversation's payer, which was the trigger's payer when it started.

### 3.8 `decision` (changed)

| Column | Change |
|---|---|
| `approver_principal_id` | Nullable. A personal conversation's decisions name its person; an approval of a trigger without a team names its author |
| `approver_group_id` | An approval of a trigger with a team: the team. `decision_approver_ck`: an approval names exactly one; a question of a trigger's conversation names neither (anyone in the place answers) |
| `subject` | Gains `{type: RESULT, runId, summary, writes}`: the turn's result and exactly what would be written to the place (the comment, the actions) |
| `decided_by_name` | For an answer from the place: the commenter's name at the system |
| `decided_by_message_id` | The `EXTERNAL` or `PERSON` message that answered |

When a trigger's team changes, its open approvals move to the new team (`decision.approver_changed`).

### 3.9 `trigger_poll`

One read of a system per account: triggers on the same account share it (Part 4).

| Column | Meaning |
|---|---|
| `id`, `company_id`, `system_id`, `account_id`, `holder_principal_id` | Unique `(system_id, account_id)`; goes with its account |
| `since`, `offset`, `cursor` | The window (`PollWindow`): its lower bound, the subjects at the bound already handled, Jira Cloud's page token |
| `etags` | GitHub's ETag per listing, sent as `If-None-Match` |
| `status` | `ACTIVE`; `IDLE` (no active trigger); `WAITING` (the account needs a reconnect, or its holder is suspended) |
| `next_poll_at`, `last_polled_at`, `lease_until` | |
| `failures`, `backoff_until`, `failing_since`, `last_error` | §5.4 |
| `rate_limit`, `rate_remaining`, `rate_resets_at` | As the system last said |

### 3.10 `trigger_subject`

The poller's snapshot of a subject as one poll saw it, the edges' baseline: `poll_id`, `subject_id`, `key`, `kind` (`ISSUE`, `CHANGE_REQUEST`), `open`, `status`, `type`, `labels`, `assignees` (identities), `text_hash` (title, description, type), `head_sha`, `created_by_tinfirm_depth` (§5.6), `last_comment_id`, `last_comment_at`, `source_updated_at`, `seen_at`. Primary key `(poll_id, subject_id)`. A snapshot unread for 90 days is deleted; the subject then fires only what a subject without a snapshot fires.

### 3.11 `trigger_firing`

Each time a trigger fired, or was kept from firing for a reason worth showing: `id`, `company_id`, `trigger_id`, `subject_id` and `subject_key` (`NULL` for a schedule), `event`, `value`, `actor` (`{id, login, name}`), `comment_external_id`, `changed_at`, `due_at` (a schedule's), `manual_by` (run now), `once`, `outcome` (`STARTED`, `CONTINUED`, `SKIPPED_OPEN`, `SKIPPED_MISSED`, `SKIPPED_CHAIN`, `SKIPPED_RATE`, `SKIPPED_AGENT_PAUSED`, `FAILED`), `reason`, `conversation_id`, `run_id` (the turn that took it; `NULL` while it waits for one), `created_at`. Unique `(trigger_id, subject_id) WHERE once` (ONCE across replicas); `NOT (once AND manual_by IS NOT NULL)`.

### 3.12 `system_write`

Tinfirm's record of what it wrote (security review, row 10): `id`, `company_id`, `system_id`, `subject_key`, `kind` (`COMMENT`, `TRANSITION`, `LABEL`, `CLOSE`, `ATTACHMENT`, `REMOTE_LINK`, `CHANGE_REQUEST`, `PUSH`, `ISSUE`), `external_id` (a comment's or attachment's id, a change request's number, a head commit), `values` (the status entered; the labels added and removed), `conversation_id`, `chain_depth` (for `CHANGE_REQUEST` and `ISSUE`: the creating conversation's), `written_at`. Indexed by `(system_id, subject_key)` and `(system_id, external_id)`; kept 90 days. Written on every `SystemWritten` (personal conversations' tool calls included) and by the outbox.

### 3.13 `outbox`

What the write-back still has to write to a conversation's place, filled in the transaction that commits its cause.

| Column | Meaning |
|---|---|
| `id`, `company_id`, `conversation_id`, `system_id`, `binding_id`, `subject_key` | The place and the binding that writes it |
| `seq` | Order within the conversation |
| `kind` | `COMMENT`, `TRANSITION`, `LABEL_ADD`, `LABEL_REMOVE`, `CLOSE`, `ATTACHMENT`, `REMOTE_LINK` |
| `cause`, `cause_key` | `RESULT`, `FAILURE`, `QUESTION`, `ANSWER`, `ON_SUCCESS`, `ON_FAILURE`, `CHANGE_REQUEST`; unique `(conversation_id, cause_key)`, so a cause queued twice queues once (T13) |
| `run_id`, `decision_id`, `output_id`, `attachment_id`, `message_id` | What it carries |
| `body`, `value`, `url` | A comment's Markdown (at most 30 000 characters, the fixed `comment` tool's limit), a transition's or label's name, a link |
| `status` | `PENDING`, `HELD` (its trigger is paused), `DELIVERED`, `DROPPED` |
| `attempts`, `next_attempt_at`, `claimed_until`, `ambiguous`, `last_error`, `external_id`, `delivered_at`, `dropped_reason` | §5.9 |

### 3.14 Migrations

`V011__triggers.sql`: §3.1 to §3.8. `V012__trigger_polls.sql`: §3.9 to §3.12 and the `SECURITY DEFINER` lookups `trigger_poll_due(now, limit)` and `trigger_schedule_due(now, limit)`, as `tracker_poll_due` and `routine_due` were. `V013__outbox.sql`: §3.13 and `outbox_due(now, limit)`. Every table carries `company_id`, forced row-level security, composite foreign keys and grants to the runtime role, as V001 does. `V010` is left to package 3's remaining work.

## 4. Invariants

Package 4 numbers its invariants T1 to T16. Each is enforced by the database where it can be, else by the service, and each has a test (§9).

| # | Invariant | Enforced by |
|---|---|---|
| T1 | A trigger acts only with what its author is entitled to: each row the author's own account or a service identity's they may use, the payer the author or such a service identity; never another member's | Trigger on `trigger_account` and `trigger`: the holder and the payer are the author or a `SERVICE` principal; `Accounts.bind(Trigger)` answers `Fixed` and `Principals.mayUse` at every save |
| T2 | One account per system per trigger; a tracker trigger has a row on its source system, whose account has an identity | Primary key `(trigger_id, system_id)`; deferred constraint trigger on `trigger` for the source row (its account may be `NULL` only while the trigger is paused `ACCOUNT_REMOVED`) |
| T3 | A trigger's settings narrow the agent as its author accepted it; the author's MCP servers only when every row is the author's own | Package 3's checks on `agent_settings*`; a check on `ADDED` rows of a trigger's settings → `SETTINGS_WIDEN` |
| T4 | A conversation belongs to a person or to a trigger, never both; a trigger's conversation's payer and author are the trigger's when it starts, then fixed | `conversation_payer_ck`, `conversation_trigger_ck`, `conversation_fixed_ck` |
| T5 | A trigger's conversation binds the table's accounts at its start, `FIXED`, and nothing later; a system without a row is unavailable to it | `how = 'FIXED'` only on a trigger's conversation (trigger); `ConversationBindings.resolve` stores nothing for one; `ConversationTools` step 5 |
| T6 | A run is paid by its conversation's payer; a personal run by its person | `run_payer_ck`, `run_conversation_ck` (§3.7) |
| T7 | A lost right pauses the trigger; nothing switches an account or a payer silently | `TriggerGate` (§5.3); only the author's save and an admin's reassignment write `trigger_account` and the payer |
| T8 | A tracker trigger sees only what its source account sees: every read is made with that account, and assigned and mentioned mean that account's identity | `TriggerPolls`, the only caller of `SystemPolls`; `EventDetector` |
| T9 | Tinfirm's own writes raise no events, except a subject Tinfirm created, up to a chain of 3 triggered conversations | `system_write`, the snapshots updated with each write, the settle time (§5.4, §5.5) |
| T10 | One firing per edge per trigger; `ONCE` at most once per trigger and subject | The snapshot row locked in the subject's transaction; `trigger_firing_once_uq` |
| T11 | At most one open conversation per trigger and subject | `conversation_trigger_subject_open_uq` |
| T12 | An approval in a trigger's conversation is decided by its team (its author without one); a question by anyone who can write in the place; never by an agent, and never an approval by a comment | `decision_approver_ck`; `DecisionService.mayDecide` |
| T13 | The write-back writes with the conversation's binding on its place's system, in order per conversation, each row at most once | `OutboxDelivery` through `ConversationBindings.use`; `cause_key`; the checks before a repeated attempt (§5.9) |
| T14 | Nothing of a result under review reaches the place before it is approved | `RESULT` and `ON_SUCCESS` rows are queued only on `APPROVED` |
| T15 | A trigger, its firings and its conversations are `404` to anyone but its author, its team and admins | `TriggerAudience`, `Visibility` in every read |
| T16 | A paused trigger fires nothing, and a resumed one fires for no change made before it resumed | `TriggerGate`; `armed_at` in `TriggerEvaluator` |

## 5. Rules

### 5.1 Who sees and acts on a trigger

`Triggers.access(caller, trigger)`:

| Caller | Sees it, its firings and its conversations | Edits, accepts a widening | Runs it now, pauses | Resumes, deletes | Reassigns | Decides approvals | Answers questions in Tinfirm |
|---|---|---|---|---|---|---|---|
| Its author | Yes | Yes | Yes | Yes | No | Without a team | Yes |
| A member of its team | Yes | No (`TRIGGER_NOT_EDITABLE`) | Yes | No | No | Yes | Yes |
| OWNER, ADMIN | Yes | No (open point 7) | Yes | Yes | Yes | Only as a team member | Only as a team member |
| The author of one of its conversations | That conversation | No | No | No | No | No | No |
| Anyone else | No: `404` | | | | | | |

Anyone who may use the agent creates triggers (`Agents.access(…).uses`, else `403 AGENT_NOT_USABLE`), with what they are entitled to (§5.2).

### 5.2 Saving a trigger

`TriggerEditor.check(author, draft)` answers the preview, storing nothing, and guards the save; a create and every change pass it whole. In this order:

1. **The agent**: live, used by the author; the version the editor showed (`agentVersion`) becomes the accepted one on a create.
2. **The source**: a company tracker system for `TRACKER` (`400 SYSTEM_KIND_INVALID`); events, subject, scope and conditions as ADR-0043, with the earlier application's "could never fire" checks (`400 INVALID_TRIGGER`); a schedule's cron and zone (`400 INVALID_TRIGGER`).
3. **Each row**: `Accounts.bind(Trigger(companyId, author, accountId), systemId)`. `Fixed` is stored; `NotEntitled` is `400 ACCOUNT_NOT_ENTITLED` with the system; `ConnectRequired` is stored, and the trigger waits (`RECONNECT_YOURS` or `RECONNECT_COMPANY`) until the account is connected again. The source row exists and has an identity (`400 SOURCE_ACCOUNT_REQUIRED`).
4. **The payer**: the author, or a `SERVICE` principal with `Principals.mayUse` (`400 MODEL_ACCESS_NOT_ENTITLED`). `ModelAccess.forRun` with the payer, the settings' model access and runtime, and the holders of the git accounts the workspace would carry: `Refuse` is `409 MODEL_ACCESS_UNAVAILABLE` with the reason; `Wait` is stored, and the preview says so.
5. **Hosts**: `Hosts.eligible` for the same context: `Nowhere` is `409 CANNOT_RUN_ANYWHERE`; `Eligible` without a host is stored, and the preview says why.
6. **The settings**: package 3's checks, the model against the payer's models (`MODEL_NOT_ALLOWED`), narrowing only and T3 (`SETTINGS_WIDEN`).
7. **The team**: a group of the company, or none.
8. **Open events**: the acknowledgement of §5.8 (`400 OPEN_EVENTS_NOT_ACKNOWLEDGED`).

**The one choice.** `TriggerEditor.fill(author, agentId, sourceSystemId, principalId)` fills the table from "Use the accounts of: …": for each system the trigger needs (the source, the agent's systems as the author accepted the agent, and, when the choice is the author, the author's MCP servers added in the settings), the chosen principal's account when it holds one, else no row; and the payer is the chosen principal's budget. The choices are the author and the service identities they may use; while the events are open to anyone (§5.8) the service identities come first. The author then changes any row to their own account or another such service identity's, or removes it.

**The preview** (`POST /triggers/preview`) says, before anything is saved, who acts and who pays: per system the account, its holder and identity and its status, and the systems left unavailable; the payer, the model access, the budget left and its reset, or the wait or refusal; whether a host can run it; the trigger as one sentence; who can drive it and what runs without approval (§5.8); a schedule's next three firings in its zone; and, for a change, how many open conversations keep the accounts they started with.

### 5.3 Entitlement at every use

`TriggerGate.check(trigger)` runs before every firing, at a resume and a reassignment, and after the commit of package 1's and 3a's events for the triggers they concern (each trigger in a transaction of its own):

| Check | Fails as | The trigger | Addressed to |
|---|---|---|---|
| The author is an active member | `LEFT` | Pauses `AUTHOR_LEFT`; its open conversations end `AUTHOR_LEFT` (open point 6) | Admins |
| The author uses the agent | No longer shared with them | Pauses `NOT_ENTITLED` | The author, admins |
| Each row: `Accounts.check(Stored(…, actingPrincipalId = author, grantId = null, …))` | `USE_RIGHT_GONE`; `PRINCIPAL_INACTIVE` for the author | Pauses `NOT_ENTITLED` | The author, admins |
| | The account gone, or another identity (`IDENTITY_CHANGED`) | Pauses `ACCOUNT_REMOVED` | The author |
| | `SYSTEM_GONE` | The source's: deleted. Another's: pauses `SYSTEM_REMOVED`, the row removed | The author |
| | `RECONNECT_YOURS`, `RECONNECT_COMPANY` | Waits. The source row's: its poll waits and its window holds. Another row's: it fires, and its conversations' binding waits as package 3's does | The author for their own account; admins for a service identity's |
| | The holder `SUSPENDED` | Waits `SUSPENDED`: fires nothing | Admins |
| The payer: the author, or `Principals.mayUse` | Lost | Pauses `NOT_ENTITLED` | The author |
| `ModelAccess.forRun` | `Refuse(PRINCIPAL_INACTIVE)` (a suspended payer), `Refuse(GATEWAY_NOT_CONFIGURED)` | Waits `SUSPENDED`, `GATEWAY_NOT_CONFIGURED`: fires nothing | Admins |
| | `Refuse(SUBSCRIPTION_GONE)` | Pauses `NOT_ENTITLED` | The author |
| | `Wait(…)` | Fires; the turn waits (`BUDGET_SPENT`, V7) | |

Nothing re-binds and nothing switches (T7). A paused trigger resumes only when a person resumes it and the gate passes; a waiting one goes on by itself on the event that repairs it (`AccountReconnected`, `PrincipalResumed`, `ModelKeyStatusChanged`). Within a conversation each binding is checked at each use, as package 3 does, with the author as the acting principal: `USE_RIGHT_GONE` makes it wait `NOT_ENTITLED` (it binds again if the right comes back), never `GONE`.

**An author who leaves** (V9), in `PrincipalDependents.memberLeaving`: their triggers pause `AUTHOR_LEFT`; their triggers' open conversations end `AUTHOR_LEFT` (the running turns cancelled, the open decisions cancelled, the undelivered outbox rows dropped with the reason); their own accounts go (package 1), so the rows that named them lose their account and their polls go. **Reassigning** (`POST /triggers/{id}/reassign`, admins) names a new author who uses the agent; every row and the payer must pass §5.2 for that author (the rows of the old author's accounts were emptied, so an admin names accounts the new author is entitled to). The trigger keeps its accepted version and stays paused until the new author or an admin resumes it; `armed_at` moves to the resume.

### 5.4 Polling

`TriggerPolls`, on `worker` replicas every `tinfirm.trigger.poll-tick` (`PT15S`), takes the polls `trigger_poll_due` returns (`ACTIVE`, unleased, due, not backing off). A pass:

1. **Claims** the poll (`FOR UPDATE SKIP LOCKED`, `lease_until` 10 minutes) and finds its triggers: live, `ACTIVE`, whose source row names this account and whose gate passes. None: the poll is `IDLE` and its window moves to now.
2. **Reveals** the token once, outside any transaction: `Accounts.reveal(companyId, accountId, holder, "trigger-poll:{pollId}")`.
3. **Lists** the subjects changed since the window (`SystemPolls.list`), over the union of its triggers' scopes, oldest change first, 50 a page, at most 200 a pass: one JQL (`project in (…) AND updated >= … ORDER BY updated ASC`; Jira Cloud's `search/jql` by cursor); one listing per GitHub repository, or `GET /issues?filter=all&state=all&since=…` without a scope; a GitLab project, else a group. Without any scope, everything the account sees; when every trigger of the poll is unscoped and fires only on `ASSIGNED`, `assignee = currentUser()`, `filter=assigned`, `scope=assigned_to_me`.
4. **Handles each subject** in a transaction of its own, its snapshot row locked: its newest 50 comments (read when the listing lacks them), the events (§5.5), the firings (§5.6), the new comments as `EXTERNAL` messages of the trigger conversations open on it (§5.8), the snapshot. A failure rolls back that subject only and is named in `last_error`.
5. **Moves the window** as `PollWindow` does, but never past now minus `tinfirm.trigger.settle` (`PT90S`, beyond the tool timeout): a subject changed more recently is left for the next pass, so that the transaction recording a write of Tinfirm's has committed before the change is read. The overlap (2 minutes) reads it again.
6. Sets `next_poll_at` to now plus the system's `pollIntervalSeconds` (30 to 3600, 60 by default, set by admins in the system's settings).

| The system answers | The poll |
|---|---|
| `401` | `Accounts.refused`: the account is `RECONNECT`, the poll `WAITING`, its triggers wait `RECONNECT_YOURS` or `RECONNECT_COMPANY`; it goes on after `AccountReconnected` |
| `403` or `404` for one scope | That scope's triggers wait `SCOPE_UNREADABLE`; the other scopes are read |
| `429`, GitHub's `403` with its rate-limit headers, `Retry-After`, `X-RateLimit-Reset`, `RateLimit-Reset` | `backoff_until` the reset, at most an hour; the window holds |
| `5xx`, a timeout, no connection | Backs off 1, 2, 4… minutes up to 30; after an hour of failures its triggers wait `SYSTEM_UNREACHABLE`; the window holds |
| Less than 10 % of the rate limit left | The interval doubles until the reset |

GitHub's listings are conditional (`If-None-Match` with the stored ETag): a `304` costs no rate limit and changes nothing. While a poll waits its window holds, so changes are read later; a change older than `tinfirm.trigger.catch-up` (`PT24H`) when first read updates the snapshot and fires nothing (`missed_count`).

### 5.5 Events

`EventDetector` (the earlier application's, pure) compares the read with the poll's snapshot, or with the window's lower bound for a subject it has none of:

- **Tinfirm's own writes** raise nothing, by record and never by identity (row 10, which overrides the identity wording PLATFORM §5.2 and Part 3 still carry): a comment whose id is in `system_write` is not new; a transition, label, close or push of Tinfirm's updated every poll's snapshot of that subject on that system when it was recorded (§5.9), so it is no edge. A comment by the poll's own account that Tinfirm did not write, Alice's own on her issue, is a person's comment.
- **Without a snapshot**: `CREATED` when created at or after the bound, then `CHANGE_REQUEST_OPENED`, `LABEL_ADDED` per label, and `ASSIGNED` when it was created assigned to the account.
- **With a snapshot**: `UPDATED` (the hash of title, description and type), `STATUS_ENTERED`, `LABEL_ADDED`, `LABEL_REMOVED`, `ASSIGNED` (the account's identity newly among the assignees), `CHANGE_REQUEST_UPDATED` (a new head commit that is not a recorded `PUSH`), `CLOSED` (it was open).
- **New comments** of people, above `last_comment_id` or written at or after the bound: `MENTIONED` when it mentions the account's identity in the system's syntax (`[~accountid:…]`, `[~name]`, `@login`), else `COMMENTED`.
- **`authors`** in a condition group: `TINFIRM` for a subject Tinfirm created (`system_write` `ISSUE` or `CHANGE_REQUEST`), else `PEOPLE`; a comment that raises an event is always a person's.

`TriggerEvaluator` (the earlier application's) then takes each trigger of the poll in name order: its scope and subject kind cover the subject; one of its events happened, with its value; its conditions hold on the read; `ONCE` has not fired on the subject; the change is at or after `armed_at` and within the catch-up. A trigger fires once per subject per pass, with the first of its events that happened (ADR-0043).

### 5.6 Firing: events to conversations

`Firings.fire(trigger, subject, event)`, in the subject's transaction:

1. **The gate** (§5.3): a pause, or a wait that stops firing, fires nothing and records nothing.
2. **The agent** paused: nothing fires; counted, not recorded. **The rate**: past `max_firings_per_hour` within the last hour, `SKIPPED_RATE`, and the author's inbox says so once an hour.
3. **The chain** (row 10): the first read of a subject Tinfirm created at depth *d* gives depth *d* + 1; beyond 3 it is `SKIPPED_CHAIN`, and a `SYSTEM` message in the creating conversation says that the subject started nothing. Any other event has depth 0.
4. **The conversation** (open point 1): when the trigger has an open conversation on the subject, the firing is `CONTINUED`: an `EVENT` turn when no run is active and no decision open, else it waits and the next turn takes it, as waiting messages do. Otherwise it is `STARTED`: a new conversation (§5.7) with its `FIRST` turn. A second event on the same issue therefore continues the same session and branch; another trigger, even of the same agent, has a conversation of its own.
5. **`ONCE`** sets `once`; a row another replica inserted first drops the firing (`ON CONFLICT DO NOTHING`).

A comment that fires is also stored as the conversation's `EXTERNAL` message. After the firings of a `CLOSED` subject, its open trigger conversations that the event did not fire end `SUBJECT_CLOSED`, a running turn finishing first; those it fired end once their turn has ended (open point 2).

**De-duplication** rests on the snapshot row lock (a second pass waits for the first and finds no edge), the `ONCE` index, one firing per trigger and subject per pass, and the unique `(conversation_id, external_id)` of messages.

**Run now** (`POST /triggers/{id}/run`; author, team, admins): on a tracker trigger, `subjectKey` is read with the source account (`SystemPolls.read`; a subject it cannot read is `409 SUBJECT_NOT_VISIBLE`), scope, subject kind and conditions are checked on that read (`409 TRIGGER_CONDITIONS_FAIL`, naming the failing clauses), and it fires as its first event: `manual_by` set, never `once`, depth 0, the gate and the rate applied. A paused trigger is `409 TRIGGER_PAUSED`. A schedule fires once now, with its overlap and without its catch-up, and its next firing does not move.

### 5.7 A trigger's conversation

`TriggerConversations.start(…)`, a public function of the conversation module, called in the firing's transaction:

| Field | From |
|---|---|
| `agent_id`, `trigger_id`, `author_principal_id` | The trigger's, at this moment |
| `runtime` | The trigger's settings' runtime then; fixed (package 3's open point 3) |
| `payer_principal_id`, `model_access`, subscription | The trigger's payer and its settings' model access then; fixed |
| `place`, `place_ref`, `subject_*` | `TRACKER_ISSUE` from the read (key, link, title); `SCHEDULE` without a subject |
| `key` | Package 3's: `{subject slug}-{handle}-{n}`, or `{handle}-{n}` |
| `title` | `{trigger}: {key} {title}`; a schedule's `{trigger} {local date}` |
| `workspace` | The settings' default repositories, else every repository the agent lists, leaving out those of systems without a row |
| Bindings | One `FIXED`, `BOUND` row per row of the table with an account (T5) |
| `chain_depth` | §5.6 |

Its turns are package 3's, with: `acting_principal_id` the conversation's author; `Settings.effective(companyId, agentId, SettingsOwner.Trigger(triggerId))` at every turn; the tools of §5.4 of package 3 with step 5 refusing a system without a binding (`TOOL_DENIED`: "{system} is not available to the trigger {name}"), every binding's access `WRITE`; `ModelAccess.forRun` and `Hosts.eligible` with the conversation's payer and the holders of its git accounts. Its comments through tools start with `*{agent} (Tinfirm agent):*`, and the account they are written with says whose; its commits name `Tinfirm agent {agent} for {trigger}` at `agent+{agentId}@tinfirm.invalid`, and its change requests' last line `Opened by Tinfirm agent {agent} for the trigger {name}.`

**The end** of a trigger's conversation: its subject closed (§5.6), its trigger deleted (`TRIGGER_DELETED`), its author leaving (`AUTHOR_LEFT`), its agent deleted (`AGENT_DELETED`, as built), the next firing of a schedule (`SUPERSEDED`, §5.10), or ended by its author, its team or an admin (`USER`). Cancel and retry are open to the same people.

### 5.8 Decisions, the place, and prompt injection

| | `TRACKER_ISSUE` | `SCHEDULE` |
|---|---|---|
| A question is raised (`ask`, or a `BLOCKED` result's open questions) | Written to the issue: one comment per turn holding its questions, ending "Reply in a comment on this issue to answer." (`QUESTION`) | In Tinfirm |
| It is answered by | The next new comment on the issue by anyone, not Tinfirm's: an `EXTERNAL` message that answers every open question (ADR-0033); or a team member or the author in Tinfirm, whose answer is written to the issue as `*{member} answered in Tinfirm:*` (`ANSWER`) | A team member or the author in Tinfirm |
| An approval (a tool call, a `request`, a change request under `ASK`, a result's review) | The team, or the author without one, in Tinfirm; never by a comment | The same |

**Anyone who can write in the place** is anyone whose comment the source account reads on the issue: the tracker decides who may comment, and Tinfirm adds no check of its own (the audience rule of chats is package 5's). An answer records `decided_by_principal_id` when a member's account has the commenter's identity, `decided_by_name`, and the message. A comment while no question is open is an `EXTERNAL` message the next turn reads in `# Conversation so far`; it wakes nothing unless it fires a trigger (`COMMENTED`, `MENTIONED`). In Tinfirm, a `TRACKER_ISSUE` conversation takes only answers (`answersOpenQuestions`): a plain message is `409 PLACE_IS_EXTERNAL`. A `SCHEDULE` conversation takes messages from its team and author, which start `MESSAGE` turns.

**The review of a result.** With `results.review`, a turn that ends `SUCCEEDED` with a `COMPLETED` result raises an approval `{type: RESULT, runId, summary, writes}` for the team instead of queuing its writes; `writes` shows the comment and the actions exactly as they would be written. `APPROVED` queues them (T14). `DENIED` needs a note (`400 VALIDATION_FAILED`) and starts a `REVIEW_RETURNED` turn with it. A failed turn and a `BLOCKED` result are not reviewed. Events that arrive meanwhile wait, as for any open decision.

**Prompt injection** stays accepted (ADR-0058, PLATFORM §7.2): whoever may comment or create in the trigger's scope drives the agent with its accounts. The mitigations, concretely:

1. **The editor warns.** The preview's `openToAnyone` names who can drive the trigger ("anyone who may comment on issues in SHOP") and what then runs without an approval: each `ALLOW` write tool, with the account it would act as. Saving needs `acknowledgeOpenEvents` while a row holds the author's own account and an `ALLOW` write tool remains; the acknowledgement is stored and audited.
2. **It proposes a narrow service identity.** The one choice lists service identities first, and the warning names the author's own accounts as what a stranger would act with.
3. **It proposes `ASK`.** For open events the editor fills the trigger's settings with `ASK` for every write tool, a narrowing the author may undo; the team then approves each write.
4. **The prompt** marks the subject, the comments and the tool results as other people's words (package 3 §5.9); `# Instruction` comes from the author alone.
5. **The sandbox** reaches only the agent's hosts as the author accepted them; a `request` for another host is the team's approval.
6. **Bounds**: the firing rate per trigger and the payer's budget bound what spam costs; the chain of 3 bounds what agents set off; an approval is never decided by a comment.

### 5.9 Results back to the place: the outbox

The `trigger` module fills the outbox in the transaction that commits the cause, for `TRACKER_ISSUE` conversations; a `SCHEDULE` conversation writes nothing outside Tinfirm.

| Cause | On | Rows |
|---|---|---|
| `RESULT` | `TurnRecorded` `SUCCEEDED` `COMPLETED` with `results.comment`, without a review or once it is approved | A `COMMENT`: the summary, next steps, and the turn's new outputs (change requests with their links; documents by name) |
| `ON_SUCCESS` | The same, after `RESULT` | The trigger's actions: `TRANSITION`, `LABEL_ADD`, `LABEL_REMOVE`, `CLOSE`; one `ATTACHMENT` per new document with `ATTACH_DOCUMENTS` (Jira) |
| `QUESTION` | Questions raised (§5.8) | A `COMMENT` |
| `ANSWER` | An answer in Tinfirm (§5.8) | A `COMMENT` |
| `FAILURE`, `ON_FAILURE` | `TurnRecorded` `FAILED` or `LOST`, with `results.comment` for the comment | "{agent} could not finish this: {cause}. The team of {trigger} was told."; the failure actions |
| `CHANGE_REQUEST` | A change request output on a Jira issue | A `REMOTE_LINK` |

A cancelled turn writes nothing.

**Delivery** (`OutboxDelivery`, the earlier application's, keyed by conversation): every poll tick and after the commit of a transaction that queued rows; per conversation the oldest pending row first, later rows waiting behind it; rounds of 20, each row claimed 5 minutes. For each row, outside any transaction, `ConversationBindings.use(binding, "write-back:{outboxId}")`: a token writes; a waiting binding keeps the row waiting without an attempt until `BindingBound`, at most 7 days, then `DROPPED`; a gone binding drops it. `COMMENT`, `TRANSITION`, `LABEL_*` and `CLOSE` go through `SystemTools.call` with the fixed tools (the comment labelled `{agent} (Tinfirm agent):`), `ATTACHMENT` and `REMOTE_LINK` through `SystemWrites`. A delivered row records its `external_id`, a `system_write` row, and the status entered, the labels and the close in every poll's snapshot of that subject on that system, in one transaction, audited as `source.written`.

**Retry and idempotency.** A retryable failure (no answer, `5xx`, `429` or a rate limit) is tried again after 1, 2, 4… minutes up to an hour, or the system's `Retry-After` when longer, at most 10 attempts; any other failure, and the last attempt, drop the row with the reason. A `401` runs `Accounts.refused`: the binding waits, and the row with it. An attempt whose request may have reached the system (a timeout, a connection reset after sending) is marked `ambiguous`; before the next one, a `COMMENT` looks among the subject's comments by the account since the first attempt and takes one with the same text as delivered, an `ATTACHMENT` looks for its name and size, a `TRANSITION` is skipped when the issue is in its status already; labels and closing are idempotent. A dropped row posts a `SYSTEM` message and reaches the author's and the team's inbox. While the trigger is paused its pending rows are `HELD`; deleting it drops them.

### 5.10 Schedules

- **Firing**: every `tinfirm.trigger.schedule-tick` (`PT30S`), the triggers `trigger_schedule_due` returns, each under its row lock (`SKIP LOCKED`, due checked again), as SYSTEM: the gate; the agent paused (`SKIPPED_AGENT_PAUSED`); overlap `SKIP` while the previous firing's conversation is busy, with an active run, an open decision or a waiting binding (`SKIPPED_OPEN`); a firing older than `tinfirm.trigger.missed-after` (`PT1H`) under catch-up `NONE` (`SKIPPED_MISSED`, one record for any number); else `STARTED`. The trigger records the due time, the outcome and the next firing after now. A firing that cannot start rolls back and is recorded `FAILED` with the reason in a new transaction; the schedule moves on.
- **The place** is `SCHEDULE`: a conversation per firing, without an external thread. The previous firing's conversation, when idle, ends `SUPERSEDED` as the next one starts, so a schedule keeps one open conversation at rest (open point 5).
- **Results** stay in Tinfirm: the turn's `AGENT` message and its outputs, seen by the team; with `results.review`, the team approves or returns them as in §5.8. To write a report somewhere, the agent uses its tools with the table's accounts, under their modes.

### 5.11 Kill switches and what other changes do to triggers

| Change | The trigger | Its conversations |
|---|---|---|
| Pause a trigger (author, team, admins) | `PAUSED` `USER`; its poll serves its other triggers; `stopRunning` cancels the active turns of its conversations | Queued turns wait (`TRIGGER_PAUSED`, a new queue reason); outbox rows `HELD` |
| Resume it (author, admins) | The gate passes, `armed_at` moves to now | Go on |
| Delete it (author, admins) | Soft delete; its poll goes `IDLE` when no other trigger needs it | End `TRIGGER_DELETED`; outbox rows dropped |
| Pause an agent | Fires nothing; polls go on, so nothing is caught up later | Turns wait `AGENT_PAUSED` (built) |
| Delete an agent | Its triggers are deleted with it (`deleted_cause` `agent.deleted`), and the delete dialog lists them (open point 11) | End `AGENT_DELETED` (built) |
| Narrow an agent / widen it | Applies at once / after the author accepts (`POST /triggers/{id}/accept`); the author's inbox offers it | From the next turn |
| Suspend a service identity | Rows or payer naming it: waits `SUSPENDED`, fires nothing; its polls `WAITING` | Bindings wait `SUSPENDED`; runs wait `MODEL_ACCESS_UNAVAILABLE` (built) |
| Delete a service identity | `409 SERVICE_IDENTITY_IN_USE` while a live trigger's row or payer names it | |
| Disconnect an account a trigger names (the service identity's page lists them first) | Pauses `ACCOUNT_REMOVED` | Bindings wait for a reconnect, or are gone with another identity (built) |
| Remove a system | Triggers on it deleted (`system.deleted`); triggers with a row on it pause `SYSTEM_REMOVED` | `SYSTEM_REMOVED` (built) |
| The author loses a right; leaves | Pauses `NOT_ENTITLED`; `AUTHOR_LEFT` (§5.3) | Bindings wait `NOT_ENTITLED`; end `AUTHOR_LEFT` |
| Delete the team's group | `team_group_id` `NULL`: the author alone; audited | Open approvals move to the author |

### 5.12 The prompt of a trigger's turn

Package 3's renderer, with these differences:

```
{agent.instructions}
---
# Conversation {key}: {title}
Started by the trigger {name} of {author} on {system} {key}: {title}, {link}. Results are written to the issue
as {account identity}; the team {team} decides approvals.
# Instruction          ← the trigger's instruction, in every turn
# Subject              ← first turn only: type, status, labels, assignees, description (cut to 16 KiB) from the
                         poll's read, as other people's words (open point 12)
# Request              ← the firings the turn took ("assigned to svc-ba by Bob at 10:02", a comment quoted),
                         an answered question, or the review's note
# Conversation so far  ← EXTERNAL comments, AGENT and SYSTEM messages
# Answers · # Accounts ("svc-ba's account, chosen by the trigger") · # Repositories · # Files · # Tools · # Outputs
# How to work          ← adds: questions are posted on the issue and anyone who comments there answers them
```

### 5.13 Visibility and the inbox

`TriggerAudience` answers a trigger's audience (its author, its team) and whether a caller belongs to it; the conversation module asks it in `Conversations.maySee`, `RunVisibility`, `DecisionService.mayDecide` and `InboxService`. A trigger, its firings and its conversations (with their messages, runs, events, transcripts, decisions, outputs, files and bindings) are seen by its author, the members of its team and OWNER and ADMIN; a conversation also by the author it started with. Anything else is `404`, and lists and counts leave it out (T15).

`Inbox.of(caller)` adds, longest waiting first:

1. Open approvals whose `approver_group_id` is a group of the caller's, or whose approver is the caller as an author without a team.
2. Open questions of the conversations of the caller's teams, with a link to the issue.
3. Trigger conversations whose latest turn failed, for the author and the team.
4. The caller's triggers, as author: paused or waiting with the reason, a widening to accept, write-backs dropped in the last 7 days, firings skipped by the chain or the rate.
5. For admins: triggers paused `AUTHOR_LEFT`, to reassign; polls waiting on a service identity's account or a suspended principal, each with the triggers it stops.

## 6. API

Under `/api/companies/{companyId}`, OpenAPI-first ([ADR-0013](../adr/0013-openapi-first-contract.md)), tag `Triggers`. "Admins" means OWNER and ADMIN; "its audience" is its author, its team and admins (§5.1).

| Endpoint | Who | Does |
|---|---|---|
| `GET /triggers` | Every member | The triggers the caller sees, each with its sentence, status, health, author, team, payer and last firing; filters `systemId`, `agentId`, `authorId`, `teamGroupId`, `status` |
| `GET /triggers/choices?agentId=&systemId=` | Who may use the agent | The editor's one choice: the caller and the service identities they may use, each with its account and status on every system the trigger would need, its budget's state, and the systems it would leave unavailable |
| `POST /triggers/preview` | Who may use the agent | §5.2's checks and answer for a draft or a change, storing nothing: who acts, who pays, whether it can run, the sentence, `openToAnyone`, the next firings |
| `POST /triggers` | Who may use the agent | Create: `{name, source, systemId?, events, subject, scope?, conditions, repeat, schedule?, agentId, agentVersion, instruction, accounts: [{systemId, accountId}], payerPrincipalId, settings, teamGroupId?, results, maxFiringsPerHour?, acknowledgeOpenEvents?}`; `201` |
| `GET /triggers/{triggerId}` | Its audience | The trigger, its table with each row's status, payer, settings in effect, accepted version and pending widening, health and counts |
| `PATCH /triggers/{triggerId}` | Its author | Name, instruction, events, subject, scope, conditions, repeat, schedule, team, results, rate; checked whole (§5.2) |
| `PUT /triggers/{triggerId}/accounts` | Its author | The table and the payer, as a whole; open conversations keep theirs |
| `GET`, `PUT /triggers/{triggerId}/settings` | Its audience reads; its author saves | Package 3's settings, for the trigger |
| `POST /triggers/{triggerId}/accept` | Its author | `{version}`: accepts the agent's widening |
| `POST /triggers/{triggerId}/pause` | Its audience | `{stopRunning}`; idempotent |
| `POST /triggers/{triggerId}/resume` | Its author, admins | The gate; `armed_at` |
| `POST /triggers/{triggerId}/run` | Its audience | `{subjectKey}`, or `{}` for a schedule (§5.6); answers the firing |
| `GET /triggers/{triggerId}/subjects?query=` | Its audience | Subjects in its scope, read with its source account, to pick one for run now |
| `POST /triggers/{triggerId}/reassign` | Admins | `{authorPrincipalId, accounts?, payerPrincipalId?}` (§5.3) |
| `DELETE /triggers/{triggerId}` | Its author, admins | Soft delete (§5.11) |
| `GET /triggers/{triggerId}/firings` | Its audience | Newest first, keyset |
| `GET /systems/{systemId}/polls` | Admins | The system's polls: account and holder, triggers, last pass, error, backoff, rate limit left |
| `GET /conversations?triggerId=`, `GET /subjects` | Every member | Package 3's lists, with a trigger's conversations for its audience |
| `POST /conversations/{conversationId}/messages` | A trigger's conversation: its team and author | `TRACKER_ISSUE`: answers only; `SCHEDULE`: any message |
| `POST /decisions/{decisionId}/decide` | Its approver (§5.8) | A `RESULT`: `{outcome: APPROVED}`, or `{outcome: DENIED, note}` |

The trigger editor needs, in order: `GET /agents?usable=true`; `GET /systems`; `GET /triggers/choices` to fill the table from one choice; `GET /model-settings/models` and package 3's settings form for the narrowing; `POST /triggers/preview` before every save, to show who acts, who pays and the injection warning; `POST /triggers` or the `PATCH` and `PUT`s.

## 7. Errors

`DUPLICATE_TRIGGER_NAME` (409); `INVALID_TRIGGER` (400, ADR-0043's, the cron and its floor included); `SYSTEM_KIND_INVALID` (400: a source that is not a tracker); `SOURCE_ACCOUNT_REQUIRED`, `ACCOUNT_NOT_ENTITLED`, `MODEL_ACCESS_NOT_ENTITLED`, `OPEN_EVENTS_NOT_ACKNOWLEDGED` (400, with `details.systemId` where one applies); `AGENT_NOT_USABLE`, `TRIGGER_NOT_EDITABLE` (403); `MODEL_ACCESS_UNAVAILABLE`, `CANNOT_RUN_ANYWHERE` (409, with `details.reason`); `TOO_MANY_TRIGGERS` (409); `TRIGGER_PAUSED`, `SUBJECT_NOT_VISIBLE`, `TRIGGER_CONDITIONS_FAIL` (409); `PLACE_IS_EXTERNAL` (409); `SERVICE_IDENTITY_IN_USE` (409, naming the triggers); package 3's `SETTINGS_WIDEN`, `MODEL_NOT_ALLOWED`, `RUNTIME_NOT_LISTED`, `CONVERSATION_BOUND`, `DECISION_DECIDED`; `NOT_FOUND` for anything the caller may not see (404, never 403). Queue reason `TRIGGER_PAUSED`. On the runner protocol, `TOOL_DENIED` for a system without a row; nothing else changes.

## 8. Audit actions

In the company's chain, with ids and kinds; instructions, comments, questions, answers, bodies and arguments stay out, as in package 3. The actor is USER for people, SYSTEM for polls, firings, schedules and deliveries.

| Action | `details` |
|---|---|
| `trigger.created`, `trigger.changed`, `trigger.deleted` | Source, system, agent, events, subject, scope, repeat, schedule, team, results; `changed`: the fields; `deleted`: the cause |
| `trigger.accounts_changed` | The whole table after the change (system, account, holder, `filled_from`) and the payer with its model access: every choice of authority (Part 8) |
| `trigger.settings_changed`, `trigger.accepted` | As `settings.changed`; the version and the previous one |
| `trigger.open_events_acknowledged` | The events, the `ALLOW` write tools and the accounts named in the warning |
| `trigger.paused`, `trigger.resumed`, `trigger.waiting` | The reason, `stopRunning` and the runs cancelled; `waiting` once per wait |
| `trigger.reassigned` | The former and the new author, the rows and payer that changed |
| `trigger.fired` | Trigger, subject, event, outcome, conversation, depth; for `STARTED`, `CONTINUED`, `SKIPPED_CHAIN`, `SKIPPED_RATE` and a schedule's every outcome |
| `trigger.run` | By USER: the subject or the schedule, the outcome |
| `conversation.started` | Package 3's, plus trigger, author, firing, depth |
| `conversation.binding.bound` | `how` `FIXED`, account, holder |
| `decision.decided` | Package 3's, plus `decidedByName` and the commenter's identity for an answer from the place |
| `decision.approver_changed` | The former and the new team |
| `source.written` | Conversation, system, kind, cause, account, holder, attempts, `DELIVERED` or `DROPPED` with the reason; never the body |
| `secret.accessed` | Purposes `trigger-poll:{pollId}`, `trigger-run:{triggerId}`, `write-back:{outboxId}` |

## 9. Tests

| Test | Proves |
|---|---|
| Constraint tests for T1 to T6, T10, T11 and the relaxed `run_payer_ck` against PostgreSQL: a row naming another member's account, a payer that is another member, a conversation with both owners, a `FIXED` binding on a personal conversation, a run paid by another principal than its conversation's, two open conversations of one trigger on one subject, a second `ONCE` firing | The database refuses what it can |
| `TriggerEditor` table test: each outcome of `Accounts.bind(Trigger)`, a payer without the use right, `Refuse` and `Wait` of `forRun`, `Nowhere`, the author's MCP server with a service identity's row, the one choice for the author and for a service identity with and without an account per system, the preview's `openToAnyone` | §5.2, T1, T3 |
| `TriggerGate` table test over every row of §5.3, and the events that pause, make wait and repair | §5.3, T7 |
| `EventDetector` and `TriggerEvaluator` table tests, ported from the earlier application's, per account: assigned and mentioned to the account and to someone else; a comment Tinfirm wrote, one by the account's own person; `authors` `TINFIRM`; `armed_at`; the catch-up | §5.5, T8, T9, T16 |
| Polling against the Jira Data Center, Jira Cloud, GitHub and GitLab stubs (the earlier application's sync tests, ported): windows, offsets, Cloud's cursor, the settle time, two triggers sharing one poll, a scope that `403`s, `401`, `429` with `Retry-After`, GitHub's `304`, the backoff and `SYSTEM_UNREACHABLE` | §5.4 |
| Firing: a second event continues the open conversation with an `EVENT` turn, during a run it waits; another trigger on the same issue gets its own conversation and branch; a closed subject ends the conversation; two replicas on one poll fire one edge once; the rate; the chain of 3 through change requests | §5.6, T10, T11 |
| Write-back: order per conversation; retries and drops; an ambiguous comment not written twice; a waiting binding holding its rows; every write updating every poll's snapshot so no event follows | §5.9, T9, T13 |
| Kill switches: every row of §5.11 | §5.11 |
| Visibility of a trigger and its conversations (author, team member, admin, another member) over every read path, and tenant isolation over every new path | §5.13, T15 |
| **V2**, the trigger's half: the BA agent fires from a Jira `ASSIGNED` trigger whose table is `svc-ba`'s | The prompt carries the agent's instructions unchanged, then the trigger's additions and instruction; every Jira call and the poll reach the stub with `svc-ba`'s token; `run.assign.modelAccess` is the relay's token for a run paid by `svc-ba` (its LiteLLM tags); the result is a comment by `svc-ba` labelled `BA (Tinfirm agent):`; a team member sees the conversation and decides its approval, Alice (not in the team) gets `404`, and her own conversation with the same agent acts as Alice |
| **V4** Alice's own trigger, "when an issue is assigned to me, prepare it", without a team | The poll uses Alice's Jira token; an issue assigned to Alice fires, one assigned to Bob does not, an issue only `svc-ba` sees never appears; the run is paid by Alice's key and may run on her personal host; the write-back uses her token; Bob gets `404`; Alice's own comment on the issue raises `COMMENTED`, Tinfirm's comment through her account raises nothing |
| **V9** A trigger's author leaves | The trigger pauses `AUTHOR_LEFT` in the leaving transaction, its open conversations end and their pending write-back is dropped, the polls of her own accounts go with them, nothing fires; an admin's reassignment to Bob is refused while a row or the payer is not Bob's to use and accepted with `svc-dev`, which Bob may use; it stays paused until resumed, and changes made before the resume fire nothing |
| **V12** (new) Questions on the issue | The agent's question is a comment on the issue; a reply by a Jira user who is no member answers it with `decided_by_name` and starts a `DECIDED` turn; a comment saying "approved" decides no approval; the team approves in Tinfirm |
| **V13** (new) The review of a result | Nothing reaches the issue before the team approves; a return with a note starts a `REVIEW_RETURNED` turn; the approved comment and transition are written once, even when the delivery is repeated |
| **V14** (new) An injected comment on a `MENTIONED` trigger with open events | The editor refused to save without the acknowledgement while Alice's own account had an `ALLOW` write; with `svc-ba` and `ASK` writes, the comment's instruction to transition another issue becomes an approval of the team, and the sandbox reaches no host the agent lacks |
| **V15** (new) A schedule, `0 9 1 * *` in `Europe/Prague` | It fires once a month in the zone, across a daylight-saving change; overlap `SKIP` while the last one is busy; one firing after downtime with catch-up `ONE`, none with `NONE`; the team answers its question in Tinfirm; nothing is written outside Tinfirm |

## 10. Open points

1. **One conversation per subject, or one per event.** In the earlier application, a trigger's turn went to its agent's open thread on the work item, and the record makes conversations independent, each with its own branch. **Proposal:** one open conversation per trigger and subject; a second event of the same trigger on that subject is an `EVENT` turn of it (its session and branch `agent/{key}` go on); once it ended, the next event starts a new one, unless `ONCE` already fired; another trigger, even of the same agent, has its own conversation, since its accounts, payer and team may differ.
2. **When a tracker conversation ends.** The record names no end. **Proposal:** when its subject closes (after the turn a `CLOSED` firing gave it), when its trigger is deleted or its author leaves, or when its author, team or an admin ends it; no idle timeout, since the next event continues it.
3. **The team as optional.** Groups are admins' to make, and V4 promises personal automation without an admin. **Proposal:** `team_group_id` is optional; without it the author alone sees the trigger's conversations and decides their approvals.
4. **Questions through comments.** Jira has no reply threads. **Proposal:** while questions are open, the next new comment on the issue by anyone (not Tinfirm's) answers them all; the team and the author may also answer in Tinfirm, and their answer is written to the issue; approvals are never decided by a comment; plain messages in Tinfirm on a tracker conversation are refused (`PLACE_IS_EXTERNAL`).
5. **Schedules and their place.** PLATFORM §7.1 says a trigger belongs to a system, but there is no schedule system kind, and a scheduled firing has no external thread. **Proposal:** `source SCHEDULE` with no system; one conversation per firing in the `SCHEDULE` place; results are its `AGENT` message and outputs in Tinfirm with an optional review; the previous firing's idle conversation ends `SUPERSEDED`; writing a report elsewhere is the agent's tool calls under their modes.
6. **An author who leaves.** V9 says the trigger pauses; it says nothing of its open conversations, whose bindings act for the author. **Proposal:** they end `AUTHOR_LEFT`, their pending write-back dropped, as a leaving person's conversations end; the reassigned trigger starts new ones.
7. **Who acts on a trigger.** **Proposal:** the author edits; the team runs it now and pauses it (a kill switch for those who watch it) but does not resume or edit; admins pause, resume, delete and reassign, but do not edit its table or payer, which are the author's entitlement.
8. **Webhooks for GitHub and GitLab.** A webhook needs Tinfirm reachable from the code host, a signing secret per system (GitHub's HMAC `X-Hub-Signature-256`; GitLab's secret token header, the weaker kind of row 14), and the right to register it on the repository or group, which a member's personal trigger lacks; it still has to be mapped to the polls that may see the repository. Polling costs rate limit: about one listing per scope per interval, plus comments of changed subjects. **Proposal:** polling only in package 4, with GitHub's conditional requests and the backoff of §5.4; webhooks later as hints for the next poll, never trusted as events, through package 5's webhook machinery (row 14's HMAC and replay window).
9. **A broken account on a trigger.** **Proposal:** the source row's broken account makes the poll wait with its window held (caught up later, within 24 hours); another row's broken account lets the trigger fire, and its conversations wait on that binding as package 3's do; a suspended principal stops firing altogether.
10. **No burst after a pause.** **Proposal:** `armed_at` moves to each resume and reassignment, and a change before it fires nothing; an agent's pause keeps the polls going, so nothing is caught up after it either.
11. **Deleting an agent that has triggers.** Package 3 deferred it. **Proposal:** the agent's triggers are deleted with it, their conversations ending `AGENT_DELETED` as built, and the delete dialog lists them first; pausing an agent fires nothing and lets polls go on.
12. **The subject in the first turn** (package 3's open point 4, revisited). **Proposal:** the first turn carries the subject's type, status, labels, assignees and description, cut to 16 KiB, from the poll's read, marked as other people's words; comments stay a `{handle}_get` away.
13. **`SystemWritten` as built.** Its `what` is a text and its producers miss change requests and pushes. **Proposal:** add `kind`, `subjectKey` and `values` to the event; `ChangeRequestOpener` and the end of a turn that pushed publish it too (§2.2); `system_write` records every one.
14. **The place on a turn's end.** The package 3 contract said `TurnEnded` carries the place; the built `TurnEnd` does not, and `TurnEndHook` is internal. **Proposal:** the public event `ConversationEvents.TurnRecorded` with the place and the trigger, published in the transaction after the hooks; the `trigger` module listens inside it.
15. **A firing rate per trigger.** Budgets cap spend, but an open `COMMENTED` trigger on a busy project may start many conversations. **Proposal:** `max_firings_per_hour`, 30 by default and at most 300; past it, `SKIPPED_RATE` and one inbox item an hour.

## 11. Decided on review

Decided by the owner on 2026-10-12, point by point. Where a decision differs from the proposal, the text below replaces the contract's earlier text on that point; elsewhere the body stands.

- **Adopted as proposed**: open points 1 (one open conversation per trigger and subject; a second event is an `EVENT` turn of it), 2 (the end on the subject's close, the trigger's deletion or by hand; no idle timeout), 3 (the team is optional; without one the author alone sees and decides), 4 (while questions are open, the next new comment on the issue answers them; approvals never by a comment; `PLACE_IS_EXTERNAL` for plain messages), 5 (schedules are a source of their own, one conversation per firing, results in Tinfirm), 8 (polling only; webhooks later as hints for the next poll), 10 (no burst after a pause: `armed_at` moves to each resume and reassignment), 11 (deleting an agent deletes its triggers; the dialog lists them first), 12 (the subject's summary in the first turn, cut to 16 KiB, marked as other people's words), 13 (`SystemWritten` with `kind`, `subjectKey` and `values`; change requests and pushes publish it; `system_write` records every write) and 14 (the public event `ConversationEvents.TurnRecorded`).
- **Open point 6, decided otherwise: a departed author's open conversations wait for the new author.** In `PrincipalDependents.memberLeaving` the trigger pauses `AUTHOR_LEFT`, and its open conversations do not end: their queued and running turns are cancelled, they take no new turn while the trigger is paused (queue reason `TRIGGER_PAUSED`), and their open decisions and undelivered outbox rows stay. Once an admin reassigns the trigger (§5.3: every row and the payer pass §5.2 for the new author) and it is resumed, each waiting conversation goes on under the trigger's new table:
  - each binding whose account is not the new table's row for its system is replaced by that row's account, and the conversation's payer and model access by the trigger's; a system the new table has no row for is `GONE` (`ACCOUNT_REMOVED`) in it;
  - this is the one exception to T7 and to package 3's I6, I7 and I10, made only by a reassignment's resume, through one security-definer function (`conversation_rebind`) that the database's fixed-column triggers let through and nothing else calls;
  - it is audited per conversation (`conversation.rebound`: per system the old and new holder, account and grant; the old and new payer and model access) and posted as a `SYSTEM` message ("From now on, {new author}'s choices act and pay: …");
  - parked calls, open approvals and undelivered outbox rows pass the new table's checks again before they run or are written.

  §5.3's "An author who leaves", §5.11's row and V9 change accordingly: Alice leaves; her trigger's open conversation waits; reassigned to Bob with `svc-dev` and resumed, it goes on, its bindings now `svc-dev`'s, the switch audited and posted, and its pending write-back written by `svc-dev`'s account.
- **Open point 7, decided otherwise: admins may also edit a trigger**, on its author's behalf: its definition, its settings, its account table and its payer, and the acceptance of a widened agent version. Every choice must pass §5.2 **for the author**, never for the admin's own entitlement; each change is audited with the admin as the editor, and the author gets an inbox item ("{admin} changed your trigger {name}: …"). In §5.1's table the OWNER and ADMIN row reads "Edits, accepts a widening: Yes, on the author's behalf". The team's rights stay as proposed: it runs the trigger now and pauses it, and does not resume or edit it.
- **Open point 9, decided otherwise: an account that cannot be used pauses the whole trigger.** In §5.3's table, every outcome of a row that "Waits" (`RECONNECT_YOURS`, `RECONNECT_COMPANY`, the holder `SUSPENDED`), for the source row and for any other row, becomes **Pauses `ACCOUNT_BROKEN`** (a new `paused_reason`). Such a trigger resumes only by hand (its author or an admin), once the gate passes, and what happened while it was paused fires nothing (open point 10). It is addressed to the author for their own account, to admins for a service identity's, and to the team. The model access's refusals (`SUSPENDED` payer, `GATEWAY_NOT_CONFIGURED`) still make it wait, as proposed. A conversation already open whose binding breaks waits on that binding, as package 3's do.
- **Open point 15, decided: limits with configurable values.** Each trigger has `max_firings_per_hour`. The company's default and maximum are company settings that admins change (`PUT /settings`, `triggerFiringsPerHour: {default, max}`), seeded from the instance configuration (`TINFIRM_TRIGGER_FIRINGS_PER_HOUR_DEFAULT`, 30; `TINFIRM_TRIGGER_FIRINGS_PER_HOUR_MAX`, 300). The author (or an admin, open point 7) sets a trigger's limit up to the company's maximum (`TRIGGER_LIMIT_TOO_HIGH`, 400). Past it, events are skipped (`SKIPPED_RATE`) and the author and the team get one inbox item an hour; lowering the company's maximum lowers the triggers above it.
