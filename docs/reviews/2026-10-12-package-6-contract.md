# Package 6 contract: governance

Status: decided on review (§11) · Owner: J. Balcar

The contract for package 6 of the [platform redesign](2026-10-12-platform-redesign.md#part-9-how-it-is-built--decided): what makes the platform operable by a company. Who spent what, and who may see it; alerts on budgets; the kill switches on one page, with the ones still missing; the whole flow of a member who leaves; the Owner's reads of personal conversations, recorded, and the notices that tell people so; the MCP endpoint for people's AI clients, moved onto conversations; the audit's views, export and checks; retention, legal holds, erasure and a person's export; and what an instance admin may do in a company. It holds the tables, the invariants they keep, the rules as functions later code calls, the API, the errors, the audit actions and the tests. It builds on packages 1 to 3 as built (CONVENTIONS §15.1 to §15.7), on the [package 4 contract](2026-10-12-package-4-contract.md) as decided on review (its §11), and on the [package 5 contract](2026-10-12-package-5-contract.md), which is proposed and revised after review; where it changes something built, §2.2 says so. Four fixes in flight are taken as built: LiteLLM key rotation in package 2's `model` module (security review, row 2: monthly on a schedule, and rotate-now per principal and per company, audited); the Owner's reads of personal conversations audited as `conversation.read` and throttled per Owner, conversation and kind of read; an instance admin who is a member of a company getting only their role's rights, break-glass being for non-members only; and the row-level security bypass narrowed to server-side work. The schema starts fresh (Part 9), and so far holds `V001` to `V005` and `V008` to `V010`; package 4 takes `V011` to `V013`, the fixes in flight `V014` to `V016` and package 5 `V017` to `V019` (its §3.13); package 6's migrations are `V020` to `V023`.

**Revised after review** (2026-10-12). The migrations move to `V020` to `V023`. A personal conversation is its person's and the Owner's only, as Part 8 says: under break-glass an instance admin gets ADMIN's view and the audited right to appoint an OWNER, and the built exception goes (§2.2, open point 12). Package 4's decided §11 is applied: the triggers of a paused system pause and are resumed by a person's choice in the system's resume dialog, and the company's stop is stated as a deliberate exception to decision 9 (open points 6, 7). Spend views apply the caller's scope to filters and labels too, and the CSV is audited. Retention and erasure respect a legal hold; erasure reaches open conversations and the transcripts that quoted the words, and says what remains; a person's export is added. A suspended member writes, decides and, through bearer credentials, reads nothing; deactivating a user suspends them everywhere. Module boundaries: public APIs, ports and events only; `run_spend` is `governance`'s; purging is each content module's public API. The Owner's reads build on the in-flight `conversation.read`, and the throttle is stated as an amendment of row 18. The audit's daily check detects a shortened chain, and its export runs by sequence. Each open point says whether the review agreed or what changed; points 22 to 25 are new.

## 1. Scope

| In package 6 | Later |
|---|---|
| A run's cost from LiteLLM's records of the requests the relay tagged with it (security review, row 3), else from the relay's own count of each answer's usage; the sandbox's report only as an estimate | A cap on a company's total (open point 18) |
| Spend views by payer, acting principal, agent, trigger, conversation and model, over time ranges, each caller seeing only their part, in filters and labels too; CSV, audited | Charts beyond a table and a daily bar (web plan, W7) |
| Alerts at budget thresholds, in the inbox; key rotation on the principal's page, with its alerts, and the company's "rotate every key now" | Mail and chat notifications (open point 4) |
| The kill switches on one page; new: pausing a system, suspending a member (and every principal of a deactivated user), stopping a company's runs | An instance-wide stop (open point 8) |
| A member who leaves: what becomes of everything that was theirs, who is told, the admins' checklist; the rejoin rule as built | Handing a leaver's open change requests to someone else at the code host |
| The Owner's reads of personal conversations recorded and shown to the person; the notices in the app, the bot's first direct message, the AI client's consent page and a personal token's creation (row 18) | |
| The MCP endpoint on conversations: a person's AI client starts, reads and answers, and decides the person's own approvals, as the person | Notifications and sessions on the endpoint (ADR-0045's later work); uploads; trigger tools (the reads came on 2026-10-16, with systems', webhooks' and spend's: [operations/mcp-clients.md](../operations/mcp-clients.md#2-the-tools); still no tool creates, edits or runs a trigger, open point 15) |
| The audit: filters, an export by sequence, a daily check of each chain against its last head, who sees it | Anchoring chain heads outside the database (threat model Q6) |
| Retention of conversations, transcripts, files and run events per kind; legal holds per conversation and per person; erasing a person's or an outside identity's content on request; a person's export (open points 16, 17, 22, 23) | Holds by trigger, system, outside identity or date (open point 22); an idle end of open conversations (open point 25) |
| Break-glass with a reason and an end, giving ADMIN's view; an instance admin's totals across companies, audited | State shared across server replicas (open point 19) |

## 2. What it builds on

### 2.1 Packages 1 to 5

| Package | What package 6 uses, by its built name | For |
|---|---|---|
| 1 | `principal` (`status` `ACTIVE`, `SUSPENDED`, `LEFT`; `principal_member_uq` counts only members that have not left, so a rejoin makes a new principal); `company.MemberPrincipals.leaving`; `PrincipalDependents.memberLeaving` and what implements it (accounts, owned secrets, personal systems); `PrincipalEvents`; `POST /service-identities/{id}/suspend`, `…/resume`; `Accounts.check` (`PRINCIPAL_INACTIVE`, `SYSTEM_GONE`, …); `account.connected_by`; `GroupEvents.GroupMembersChanged`; `member.role.changed`; the permissions `VIEW_COMPANY`, `MANAGE_MEMBERS`, `MANAGE_OWNERS`, `MANAGE_SYSTEMS`, `VIEW_AUDIT`, `VIEW_MODEL_ACCESS`, `MANAGE_MODEL_ACCESS`; `LAST_OWNER`, `PRINCIPAL_SUSPENDED` | Suspending a member; leaving; switches; appointing an OWNER |
| 2 | `principal_model_key` (`spent_usd`, `budget_resets_at`, `status`), `KeySync`, `KeyReconciliation`, the `LiteLlm` admin client (`model.internal`), `ModelEvents.ModelKeyStatusChanged`; `ModelRelay`, which tags each request `x-litellm-tags: run:…,conversation:…,agent:…`, and `ModelRelay.usage` (requests and bytes); `model_settings`; `GET /me/model-access`, `GET`, `PATCH /principals/{id}/model-access`; key rotation, monthly and rotate-now per principal and per company, audited (in flight) | Spend, alerts, rotation's page and switch |
| 3a, 3b | `Agents.access`; readiness, today `agent.internal.ReadinessService`; pausing an agent with `stopRunning` (`AgentRuns.cancelRunning`); `run` with `agent_id`, `acting_principal_id`, `payer_principal_id`, `model_access`, the totals of its `USAGE` events (`input_tokens`, `output_tokens`, `cached_input_tokens`, `cache_write_tokens`, `cost_usd`) and `relay_requests`; `RunDispatcher`'s steps, `QueueReasonCode`, `Turns.cancelActive`, `RunReads`, `RunEvents`, `run_event`. A run's end reaches other modules today only through the port `TurnEnded`, which the conversation module implements; `run.internal.RunCompletion` is internal | Spend; the company's stop |
| 3c | `Conversations.find`, `maySee` and `isPerson`: the rule is `ConversationsImpl.Caller.sees` (`owner \|\| isPerson`), where `owner` is OWNER or, as built, an instance admin acting in the company, which package 6 changes (§2.2); ADMIN gets `404`. `seenByOwner` is a flag that `ConversationViews` sets on every view, for the notice. `ConversationBindings`, `ConversationEvents`, `AfterCommit`; `conversation_origin_ck`, which already allows `CLIENT`. The services behind the app's controllers (`ConversationService`, `ConversationViews`, `ConversationTurns`, `ConversationMessages`, `DecisionService`, `InboxService`, `AttachmentService`) are `conversation.internal`: package 6 reaches them only through the public API it adds (§2.2) | Recorded reads; the MCP endpoint; retention |
| audit, identity, company, mcp | `AuditLog` with `BearerGrantHolder` (`tokenId`, `grantId` on every event of a bearer call), `AuditChainVerifier`, `GET /audit-events` (limit and cursor) and `…/verification`, `/api/admin/audit-events`; `CompanyAccessGuard` as fixed in flight (a member's role answers for them, an instance admin included); `AdminAccessAudit` (`admin.company.accessed` in a transaction of its own, today once per permission used); a user's deactivation at the instance (`/api/admin/users`, `user.updated`); the authorization server, `GET /api/me/oauth/consent`, `/api/me/oauth/grants`, `/api/me/tokens`; the `mcp` module: `POST /api/companies/{companyId}/mcp`, `McpToolCatalogue`, `@ToolScope`, `McpRequestFilter`, `McpRateLimiter` (`TINFIRM_MCP_RATE_LIMIT_PER_MINUTE`, 120) and **no tool set**: its tools went with the old domain (the module's own note; CONVENTIONS §14.11) | The audit; break-glass; deactivation; the endpoint |
| 4 (decided) | `run.trigger_id`, `conversation.author_principal_id`; `TriggerGate.check`, `trigger.status`, `paused_reason` (`AUTHOR_LEFT`, `ACCOUNT_BROKEN`, `NOT_ENTITLED`, …), `waiting_reason`, `armed_at`, the resume through the gate; `trigger_poll`; `trigger_firing.actor`, `trigger_subject`; `outbox` rows `HELD`; `TriggerAudience`; `ConversationEvents.TurnRecorded`; reassigning, admins editing on the author's behalf, the waiting conversations rebound by `conversation_rebind`, an unusable account pausing the whole trigger, and the firing limits as company settings (its §11, decisions 6, 7, 9, 15) | Spend per trigger and author; switches; leaving; erasure |
| 5 (proposed, revised) | `PersonalConversations.start`, `PlaceConversations`, `PlaceDecisions` and `decision.decided_via`, `decided_by_external_id`; the port `InboxItems`; `DirectMessages.receive`, `ChatBot.usable`, `chat_user.notice_shown_at` and the notice of its §5.6, `chat_event`'s reasons, `webhook_delivery`, `WebhookInbox`, `chat_preference`, links as `LINK` accounts; its read paths audited as `conversation.read` (its §5.13) | The notice; switches in chats and webhooks; the inbox; erasing a chat user's words |

### 2.2 What package 6 changes in what is built

| Built | Change | Why |
|---|---|---|
| `run` keeps no model | `run.model`: the settings' model at assignment (`NULL`: the runtime's default), written by the run module | Spend by model for a subscription's run (§3.1) |
| A run's spend: the sandbox's `USAGE` totals only | `run_spend` (`governance`'s) from LiteLLM's spend logs, by the relay's `run:` tag, else from the relay's own count; the sandbox's totals only an estimate for display | §5.1, row 3, open point 1 |
| `ModelRelay.usage`: requests and bytes per run | Also each answer's `usage`, summed per run, model and key (`relay_usage`, the model module's) | §5.1, open point 1 |
| The `LiteLlm` client reads no spend logs | `LiteLlm.spendLogs` reads LiteLLM's `/spend/logs` with the company's admin key, outside any transaction (CONVENTIONS §10), offered to other modules as the public `model.SpendLogs` | §5.1 |
| No public event for a run's end (`TurnEnded` is a port; `RunCompletion` is `run.internal`) | `RunEvents.RunEnded(companyId, runId, conversationId, status)`, published in the transaction of every run's end, after `TurnEnded` is told | §5.1 |
| Readiness is `agent.internal.ReadinessService` | A public `Agents.readiness(companyId, agentId, userId)`, which the app's controller and the endpoint share | §5.7 |
| `conversation_payer_ck`'s personal branch as package 5 proposes it: `(TINFIRM_THREAD, APP)` or `(CHAT_THREAD, DIRECT_MESSAGE)` | Also `(TINFIRM_THREAD, CLIENT)`; `conversation.client_name` | §5.7 |
| `Conversations.maySee`: `owner` is OWNER or an instance admin acting in the company, who sees every personal conversation in full (CONVENTIONS §6, §15.7) | `owner` is OWNER only (Part 8, PLATFORM §9, ADR-0058 §10). An instance admin who is not a member, under break-glass, gets ADMIN's view: `404` on personal conversations and their runs, run events, decisions and files. An instance admin who is a member reads by their role (the fix in flight) | §5.6, §5.10, open point 12 |
| The authorization server and the MCP endpoint give an instance admin who is not a member the audited break-glass (CONVENTIONS §6; threat model 10.16, R38) | `access_denied` at the consent, naming no company; `NOT_FOUND` from every tool | G9, §5.10 |
| Suspension: service identities only; their running turns end when the relay refuses the blocked key or a binding's check fails | Members too. A suspended member's every write in the company is `409 PRINCIPAL_SUSPENDED` (decisions too: `DecisionService.mayDecide` answers no), and their bearer calls are answered as a non-member's (`NOT_FOUND`). Suspending either kind cancels the running turns it acts for or pays, through the run module's listener | §5.4, open points 5, 24 |
| A deactivated user's requests are refused (threat model 10.10); their triggers and principals go on | Deactivating a user suspends their principal in every company (cause `USER_DEACTIVATED`); reactivating resumes those, and only those | §5.4 |
| `principal.suspended`, `principal.resumed` carry `name` (a service identity's) | `name` only for a `SERVICE` principal; a member's `userId` instead, so no person's name enters the chain (ADR-0010); `cause` | §8 |
| `RunDispatcher` begins with the agent | Step 0: the company's runs stopped → `COMPANY_STOPPED`; a paused system's repository in the workspace → `SYSTEM_PAUSED` | §5.4 |
| `Accounts.check`'s reasons; `TriggerGate.check` (package 4) | `SYSTEM_PAUSED`. The gate pauses a trigger with its source or a row on a paused system (`paused_reason` `SYSTEM_PAUSED`, package 4's decision 9), and fires nothing while the company's runs are stopped (`waiting_reason` `COMPANY_STOPPED`, a stated exception to decision 9) | §5.4, open points 6, 7 |
| `PrincipalDependents.memberLeaving` | Also revokes the OAuth grants consented for the company, deletes `chat_preference`, and publishes `GroupMembersChanged` for each group the membership's cascade takes them out of (the cascade publishes nothing) | §5.5 |
| The Owner's reads audited as `conversation.read`, throttled per Owner, conversation and kind of read (in flight) | Kept, and recorded in `conversation_read`, which holds the throttle across replicas and the person's list of reads; every read path package 6 adds (the endpoint, the export) records through `ConversationReads`, in a transaction of its own | §5.6, open point 9 |
| Break-glass: audited per permission used, no reason (threat model 1.15, Q5) | An access with a reason, for an hour, told to the company's OWNERs, with ADMIN's view; `admin.company.accessed` when it opens and at each permission's first use within it, `adminAccessId` on every event | §5.10 |
| `GET /audit-events`: limit and cursor | Filters; an export by sequence; a daily check against the last head | §5.8 |
| `mcp`: `allowedDependencies` `identity`, `shared`; no tool | Tools on conversations; also depends on `conversation`, `agent`, `governance`, through their public APIs only | §5.7 |
| The package 3 contract offered `ConversationReads` and `ConversationCommands`; package 3c was built without them | Package 6 adds both over the built services, and the REST controllers move onto them | §5.7, open point 20 |
| `InboxService` computes its items from the conversation module's tables; package 5 adds the port `InboxItems` | `governance` implements `InboxItems` for the kinds of §5.11 | §5.11 |
| `message_body_ck CHECK (length(body) BETWEEN 1 AND 100000)`, `message.body NOT NULL`; `attachment.content NOT NULL`, `attachment_size_ck`; the other texts' checks | A purge marker per row; each text `NULL` exactly when its row's marker is set; an attachment keeps its size and name | §3.7 |
| Consent and token creation say nothing of the Owner | `GET /api/me/oauth/consent` and `POST /api/me/tokens` gain `ownerNotice`; `/me` shows it at a token's creation | §5.6 |

### 2.3 Modules and the application before the redesign

A new module, `governance`, at the top. It holds the spend sync and the spend views (`SpendSync`, `Spend`; the tables `spend_settlement`, `run_spend`, `company_spend_alert`), the switches' page and the switches that reach across modules (`KillSwitches`), departures (`Departures`), retention, holds, erasure and export (`Retention`, `Holds`, `Erasure`, `Exports`), and the inbox items of §5.11 (it implements package 5's port `InboxItems`). It depends on `trigger`, `chat`, `conversation`, `run`, `agent`, `model`, `account`, `system`, `principal`, `company`, `audit` and `shared`, through their public types only (CONVENTIONS §10); only `mcp` depends on it, for `get_my_spend`.

Each switch's state stays with its data and is read there through its owner's public API: the company's stop on `company` (`CompanySwitches`: `stopRuns`, `liftRuns`, `runsStopped`; `CompanyEvents.RunsStopped`, `RunsResumed`), a system's pause on `system` (`Systems.pause`, `resume`, `SystemInfo.paused`; `SystemEvents.SystemPaused`, `SystemResumed`), a principal's suspension on `principal` (built for service identities; `PrincipalEvents`). `Accounts.check`, the dispatcher's step 0, `TriggerGate`, `ChatBot.usable` and `WebhookInbox` ask them. What a switch does above its owner (cancelling turns, pausing and resuming triggers, holding and releasing the outbox) is done by the higher module's listener on the owner's event, or by `KillSwitches` through the public APIs of `run`, `conversation` and `trigger`; no lower module calls a higher one. The recorded reads are `conversation`'s (`ConversationReads`, `conversation_read`); budget alerts are `model`'s (`BudgetAlerts`, `budget_alert`), which talks to LiteLLM and offers its spend logs (`SpendLogs`) and the relay's count (`ModelRelay.usage`); break-glass is `company`'s (`AdminAccesses`, `admin_access`); the audit's filters, export and check are `audit`'s.

**`run_spend` belongs to `governance`**: its `SpendSync` is the only writer and its `Spend` the only reader. `governance` hears `RunEvents.RunEnded`, reads the run and its conversation through `RunReads` and `Conversations`, and LiteLLM's logs and the relay's count through `model`'s public API.

**Purging** is each content module's public API, which `governance` calls: `conversation`'s `ConversationPurge` (messages, decisions' texts, outputs' titles, attachments' content, a conversation's title; an outside identity's messages), `run`'s `RunPurge` (run events, a run's texts), `trigger`'s `TriggerPurge` (a firing's actor name, an ended conversation's subject snapshot) and `chat`'s `ChatPurge` (a chat user's row). None of them depends on `governance`.

The `mcp` module depends on `identity`, `shared`, `conversation` (`ConversationReads`, `ConversationCommands`), `agent` (`Agents`, `readiness`) and `governance` (`Spend`). On landing, package 6 adds CONVENTIONS §15.10 and the modules' rows to §10's table.

From the earlier application: the `mcp` module's renderings (`Renderings`, `ResultText`, `TranscriptText`, `WriteRenderings`: other people's words in labelled blocks, the 40 000-character cap, a transcript's continuation) and their tests, ported onto conversations; `McpWorkTools`, `McpRunTools`, `McpAgentTools` and `McpConnectionTools` are not, since their domain is gone. The web app keeps `audit-log`, `audit-verification-result`, `company-audit` and `instance-audit`. Neither the earlier application nor packages 1 to 3 as built have spend views, retention or an audit export.

**Documents this package changes**, on landing (CONTRIBUTING §6):

- CONVENTIONS §6 (the column of an instance admin who is not a member: ADMIN's view of conversations and runs, the right to appoint an OWNER, `access_denied` at the consent and nothing at the endpoint; a suspended member; `STOP_RUNS`, `MANAGE_RETENTION`), §8 (the actions of §8 here), §10 (the `governance` module, the public APIs of §2.2) and a new §15.10.
- PLATFORM §4 (suspending members, and a deactivated user's principals) and §9 (the kill switches: pausing a system, suspending a member, stopping the company's runs; retention, holds and erasure).
- The design record's security review, row 18 ("every read is audited"), once open point 9 is decided.
- The threat model: 1.15 and Q5 (the reason, built); 1.28 and 1.29 (an instance admin no longer reads personal conversations; reads recorded); R4 (the summary through a security-definer lookup, audited); R37 (a team's approvals not decided through a client); 8.37 and Q33 (members suspended, their key blocked and running turns cancelled); 8.38 (the rest of a leaver); 10.10 (a deactivated user's principals suspended); 10.16 and R38 (reversed by G9); R42 and Q30 (reads recorded and shown, notices in every place); 13.3 (spend from LiteLLM's logs or the relay's count); new rows for spend views, a system's pause, the company's stop, retention, holds, erasure and export.

## 3. Tables

### 3.1 Spend: `run` (changed), `relay_usage`, `spend_settlement`, `run_spend`

`run` gains `model` (at most 200 characters), the run module's. Its `USAGE` totals (built) stay where they are and are shown as an estimate until the run settles; they never set a spend row's cost.

`relay_usage`, the model module's: what the relay saw of a run's answers. `(company_id, run_id, model, key_id, requests, input_tokens, output_tokens, cached_input_tokens, cache_write_tokens, updated_at)`, primary key `(run_id, model, key_id)`. The relay adds each answer's `usage` as its format reports it (Anthropic's `message_start` and `message_delta` events, OpenAI's final usage) when the answer ends; an answer without `usage` counts a request and no tokens. `key_id` is the LiteLLM key it forwarded with, since a rotation may fall within a run. Rows are deleted 30 days after the run ends. Read through `ModelRelay.usage`.

`spend_settlement`, `governance`'s: one row per ended run that relayed a request or ran on a subscription. `company_id`, `run_id` (primary key), `payer_principal_id`, `started_at`, `ended_at`, `state` (`PENDING`, `SETTLED`), `attempts`, `due_at`, `lease_until`. Deleted once `SETTLED` for 30 days.

`run_spend`, `governance`'s: what one run spent on one model.

| Column | Meaning |
|---|---|
| `company_id`, `run_id`, `model` | Primary key `(run_id, model)`. The model as LiteLLM logged it or the request named it (the name the runtime asked for), or `run.model` (else `default`) for a subscription's run |
| `conversation_id`, `agent_id`, `trigger_id`, `payer_principal_id`, `acting_principal_id`, `personal` | Copied from the run and its conversation, so that views need no join and outlive a conversation's purge (§5.9); `personal` is true for a person's conversation |
| `day` | The UTC date the run ended |
| `requests`, `input_tokens`, `output_tokens`, `cached_input_tokens`, `cache_write_tokens` | Summed |
| `cost_usd` | `numeric(14,6)`: LiteLLM's spend for `GATEWAY`; the relay's tokens at LiteLLM's model prices for `RELAY`; `NULL` for `SUBSCRIPTION` |
| `source`, `recorded_at` | `GATEWAY` (LiteLLM's records), `RELAY` (LiteLLM's records missing: the relay's own count), `SUBSCRIPTION` (not metered: the tokens the sandbox reported, marked so) |

Indexes `(company_id, day)` and `(company_id, x, day)` for `x` each of `payer_principal_id`, `acting_principal_id`, `agent_id`, `trigger_id`, `conversation_id`. Rows hold no content and are kept for good; only `SpendSync` writes them (G2, G3).

### 3.2 `budget_alert`, `company_spend_alert`, `model_settings` (changed)

`model_settings` gains `budget_alert_thresholds` (1 to 3 whole percentages from 1 to 100, ascending; `{80, 100}`) and `monthly_alert_usd` (optional; open point 18).

`budget_alert`, the model module's: `(company_id, principal_id, resets_at, threshold, spent_usd, budget_usd, crossed_at)`, primary key `(principal_id, resets_at, threshold)`: one threshold crossed in one budget period. `company_spend_alert`, `governance`'s: `(company_id, month, crossed_at, total_usd)`, primary key `(company_id, month)`. Both deleted after 400 days.

### 3.3 Switches

| Table | Gains |
|---|---|
| `company` | `runs_stopped_at`, `runs_stopped_by`, `runs_stopped_reason` (1 to 500 characters), `runs_stopped_access_id` (an instance admin's break-glass access, else `NULL`): the company's runs are stopped while `runs_stopped_at` is set (§5.4) |
| `external_system` | `paused_at`, `paused_by`, `paused_reason`; a company system only (personal servers have the company's switch) |
| `principal` | `suspended_at`, `suspended_by`, `suspended_reason`, `suspended_cause` (`ADMIN`, `USER_DEACTIVATED`), for both kinds (`status` stays the switch); `departure_closed_at`, `departure_closed_by`, for a member who left (§5.5) |
| `trigger` (package 4) | `paused_reason` gains `SYSTEM_PAUSED`; `waiting_reason` gains `COMPANY_STOPPED` |
| `run` | `queue_reason` gains `COMPANY_STOPPED`, `SYSTEM_PAUSED` |
| `chat_event` (package 5) | Reasons `SYSTEM_PAUSED`, `COMPANY_STOPPED`, `SUSPENDED` |

A binding keeps its state under a paused system: for a conversation the system is one without an account (§5.4), and nothing waits on the binding. The reasons people type stay on these rows, out of the audit chain (§8).

### 3.4 `conversation_read`

The conversation module's record of the Owner's reads of other people's personal conversations, beside the in-flight `conversation.read` audit: `company_id`, `reader_user_id`, `conversation_id` (`NULL` for a list), `person_principal_id` (the conversation's person, or the list's filter; `NULL` for a list of everyone's), `kind` (`DETAIL`, `RUNS`, `EVENTS`, `DECISIONS`, `FILES`, `LIST`, `EXPORT`), `first_read_at`, `last_read_at`, `last_audited_at`, `reads` (since the last audit event), `reads_total`. Unique `(reader_user_id, kind, conversation_id, person_principal_id) NULLS NOT DISTINCT`. Written only by `ConversationReads`; the throttle (§5.6) and the person's list of reads read it, so both hold across replicas. A row is deleted 400 days after its `last_read_at`, as `admin_access`; the audit keeps the events.

### 3.5 `admin_access`

An instance admin's break-glass into a company where they are not a member: `id`, `company_id`, `user_id`, `reason` (10 to 500 characters), `started_at`, `expires_at` (an hour later), `ended_at`. Readable by the company's OWNERs and by instance admins; written only by `AdminAccesses`; kept 400 days. An access gives ADMIN's view (§5.10): nothing within it reads a personal conversation.

### 3.6 `audit_check` and the audit's indexes

`audit_check`: `id`, `company_id` (`NULL` for the instance chain), `checked_at`, `ok`, `from_seq` (the previous check's head), `failure` (`BROKEN`, `SHORTER`, `HEAD_CHANGED`), `first_invalid_seq`, `head_seq`, `head_hash` (a passed check records the head it verified; a failed one the chain's last event as found), `events_checked`, `acknowledged_at`, `acknowledged_by`; the last 400 per chain are kept, and always the last that passed. `audit_event` gains indexes for the filters of §5.8, each ending in `seq`: `(company_id, action)`, `(company_id, target_type, target_id)`, `(company_id, actor_type, actor_id)`, `(company_id, occurred_at)`, and on `details ->> 'conversationId'` and `details ->> 'principalId'`. Indexes change nothing that is hashed.

### 3.7 `retention_settings`, `legal_hold` and purge markers

`retention_settings`, one row per company, the OWNER's (open point 16):

| Column | Default | Range | Counted from | Purges |
|---|---|---|---|---|
| `run_events_days` | 90 | 7 to 3650 | the run's end | `run_event` rows: the transcript |
| `chat_content_days` | 30 | 1 to 3650, at most `content_days` | the conversation's end | Of `CHAT_THREAD` and `WEBHOOK` conversations: message bodies and their authors' names (`external_author`), decisions' texts and who decided them outside Tinfirm (`decided_by_name`, `decided_by_external_id`) |
| `content_days` | 365 | 30 to 3650, or `NULL` (keep) | the conversation's end | Messages (bodies, `external_author`), titles, decisions' questions, options, answers, notes, arguments, results, `decided_by_name` and `decided_by_external_id`, runs' summaries, results and failure messages, outputs' titles |
| `files_days` | 365 | 7 to 3650, or `NULL` | the conversation's end | Attachments' content, outputs' documents included |
| `updated_at`, `updated_by` | | | | |

`legal_hold` (open point 22): `id`, `company_id`, `conversation_id` or `principal_id` (exactly one), `reason` (1 to 500 characters, on the row only), `set_at`, `set_by`, `lifted_at`, `lifted_by`. A hold is open while `lifted_at` is `NULL`; a lifted one is deleted after 400 days. A principal's hold covers their personal conversations and, in triggers' conversations, their messages, files and decisions; a conversation's covers all of it, its runs' events included.

**Purge markers, per row**, since a CHECK sees one row: `message.purged_at`; `decision.purged_at`; `run.texts_purged_at` (summary, result, failure message) and `run.events_purged_at`; `output.purged_at` (title); `attachment.content_purged_at`; `conversation.title_purged_at`; and `conversation.content_purged_at`, `conversation.erased_at` for the tombstone's line. Each text is `NULL` exactly when its row's marker is set: `message_body_ck` becomes `(purged_at IS NULL AND length(body) BETWEEN 1 AND 100000) OR (purged_at IS NOT NULL AND body IS NULL)`, and so for every text the table names. An attachment's `content` is `NULL` exactly when `content_purged_at` is set; it keeps its `size_bytes` as the original (`attachment_size_ck` holds while the content is there), its hash, its type and its name (`attachment_name_uq` unchanged); the API shows it as purged.

### 3.8 Migrations

`V020__spend.sql`: §3.1, §3.2. `V021__switches.sql`: §3.3, `conversation.client_name` and `conversation_payer_ck` (§2.2). `V022__reads_and_access.sql`: §3.4 to §3.6. `V023__retention.sql`: §3.7. Every table carries `company_id` (but the instance's rows of `audit_check`), forced row-level security, composite foreign keys and grants to the runtime role, as V001 does. Work across companies runs server-side only, as the narrowed bypass in flight requires, through `SECURITY DEFINER` lookups as `trigger_poll_due` is: `spend_due(now, limit)`, `retention_due(now, limit)`, `audit_check_due(now)`, and `companies_summary(from, to)`, which returns aggregates only (§5.10). They follow package 5's last migration and move with it.

## 4. Invariants

Package 6 numbers its invariants G1 to G16. Each is enforced by the database where it can be, else by the service, and each has a test (§9).

| # | Invariant | Enforced by |
|---|---|---|
| G1 | A caller sees the spend of the runs they paid, of the triggers whose audience they are, and, as an admin, of all; another person's personal conversations only as OWNER, folded per person for ADMIN. Filters and labels obey the same scope: a conversation the caller may not see filters to nothing, and a title is shown only for the caller's own conversations and the triggers' they see | `Spend.scope(caller)` before any filter or grouping |
| G2 | A run's cost is LiteLLM's record of the requests the relay tagged with that run, else the relay's own count of the run's answers; the sandbox's report sets no cost and alerts nothing | `SpendSync`, the only writer of `run_spend`; `source` |
| G3 | One spend row per run and model, written once, by one worker | Primary key; `ON CONFLICT DO NOTHING`; `spend_settlement`'s lease |
| G4 | A company whose runs are stopped assigns no run, starts no conversation, fires no trigger, runs no approved call or parked call and delivers no write-back; its running turns are cancelled, and its parked calls that have not started are refused (amended by the security review of 2026-10-27, M6 and D5: the reveal, `ConversationBindings.use`, is the gate) | The dispatcher's step 0, `TriggerGate`, `ConversationCommands`, `DecisionService`, `ParkedCalls`, `OutboxDelivery`, `WebhookInbox`, the relay; `ConversationBindings.use` for every use of a conversation's binding |
| G5 | A paused system's accounts are used for nothing: no call, clone, push, poll, post or delivery; every trigger whose source it is, or whose table has a row on it, is paused | `Accounts.check` → `SYSTEM_PAUSED`; `TriggerGate`; `TriggerPolls`; `ChatBot.usable`; `WebhookInbox` |
| G6 | A suspended principal, member or service identity, acts, pays and decides for nothing, and its running turns are cancelled (package 1's I7); a suspended member writes nothing in the company, and their bearer credentials reach nothing there | `Accounts.check`, `ModelAccess.forRun`, `DecisionService.mayDecide`, the guard (`PRINCIPAL_SUSPENDED`; a bearer call answered as a non-member's), the run module's cancel on `PrincipalSuspended` |
| G7 | A kill switch is undone only by a person, never by time or an event; a trigger a switch paused is resumed only by a person, by its own resume or in the system's resume dialog. The one exception: the lift of a company's stop arms its triggers again without a resume of each (open point 7) | Only the resume endpoints clear a switch; `TriggerGate` |
| G8 | Every read of a personal conversation by someone other than its person is recorded, and audited as `conversation.read` at least once per Owner, conversation and kind of read every 15 minutes, each download and export every time, in a transaction of its own | `ConversationReads`, the only read path of the services behind the app, the endpoint and the export; `REQUIRES_NEW` |
| G9 | The MCP endpoint acts only as its credential's member: it starts only personal conversations they pay for (`CLIENT`), shows no other person's personal conversation, OWNER's view included, and decides only the member's own approvals and questions, never a team's approval; an instance admin who is not a member gets nothing there, consent included | `ConversationCommands.start`, `decide`; `ConversationReads` with the `CLIENT` view; the authorization server's `access_denied` |
| G10 | The notice precedes a person's first personal conversation in each place: the app, the bot's first direct message, the client's consent, a personal token's creation | §5.6; package 5's `notice_shown_at`; the consent's and the token's `ownerNotice` |
| G11 | Retention, erasure and export never change the audit chain, which holds ids only | `Retention`, `Erasure` and `Exports` touch content tables only (ADR-0010) |
| G12 | Retention purges only ended conversations; erasure purges ended and open ones; both keep tombstones and spend | `retention_due` takes `state = 'ENDED'`; the per-row purge markers |
| G13 | A break-glass access has a reason, lasts at most an hour, gives ADMIN's view (never a personal conversation), and every event within it names it | `admin_access`; `AdminAccesses.require` before every company path of an instance admin who is not a member; `Conversations.maySee` |
| G14 | A member who rejoins gets a new principal; nothing of the old one comes back | `principal_member_uq` (built) |
| G15 | Once a member left, nothing acts or pays as them: no account, key, host, binding or OAuth grant of theirs works in the company | `memberLeaving` (built and §5.5) |
| G16 | Nothing an open legal hold covers is purged or erased | `RetentionSweep` skips it; `Erasure` refuses it (`ON_HOLD`) |

## 5. Rules

### 5.1 Spend: per run, from LiteLLM, else from the relay

`SpendSync`, in `governance`, on `worker` replicas every `tinfirm.spend.sync-interval` (`PT1M`):

1. A run's end publishes `RunEvents.RunEnded` (§2.2). `SpendSync` hears it inside that transaction and inserts the run's `spend_settlement` row, `PENDING`, due two minutes later, since LiteLLM writes its spend logs in batches. A run that relayed nothing and ran on no subscription gets no row. A subscription's run settles at once with a `SUBSCRIPTION` row under `run.model`: the tokens its sandbox reported, marked as reported, and no cost.
2. `spend_due(now, 200)` claims due rows (`FOR UPDATE SKIP LOCKED`, `lease_until` five minutes ahead), grouped by company and paying key, so that two workers never read the same logs.
3. Outside any transaction, `SpendLogs.of(companyId, keyId, from, to)` reads LiteLLM's logs for each key the relay forwarded the run with (`relay_usage.key_id`), over the run's span (a minute before its start to five after its end), and keeps the entries whose request tags hold `run:{runId}`.
4. One transaction writes a `GATEWAY` row per model (requests, tokens and spend summed) and sets `SETTLED`.
5. No entry yet: tried again after 2, 4, 8… minutes. After an hour, a `RELAY` row per model from the relay's own count (`ModelRelay.usage`), priced with LiteLLM's model prices (`/model/info`, read with the admin key and kept an hour), and `SETTLED`. A company with more than 10 `RELAY` runs a day, or whose spend logs cannot be read for an hour, gets an admins' item (§5.11). Should the pinned LiteLLM not keep the tags (open point 1), `tinfirm.spend.source` `RELAY` settles every run from the relay at once.

The sandbox's `USAGE` totals are shown on a run as an estimate until it settles, and never written to `run_spend`. The budget stays LiteLLM's (package 2): `spent_usd` is what LiteLLM counts against the key; `run_spend` explains it, and a principal's period shows both.

### 5.2 Spend views

`Spend.query(caller, from, to, zone, groupBy, filters)`:

- **Range**: `from` inclusive, `to` exclusive, at most 366 days (`400 SPEND_RANGE_INVALID`); by default the current calendar month in `zone` (IANA, default `UTC`). Runs count on the day they ended, bucketed by `day`, `week` or `month` in `zone`.
- **Group by** at most two of `payer` (who paid), `actor` (the run's acting principal: the person, or the trigger's author when the run was made, so that after package 4's `conversation_rebind` the later runs count for the new author; a trigger's runs grouped by `actor` are spend per author), `agent`, `trigger`, `conversation`, `model`, `period`. **Filters**: `payerId`, `actorId`, `agentId`, `triggerId`, `conversationId`, `model`, `source`.
- **Scope first** (G1). Who sees which runs (the table below) is applied before any filter or grouping, so totals count only what the caller sees, and no filter reaches past it: a `conversationId` the caller may not see filters to nothing, exactly as an id that does not exist (never `404` against rows); `groupBy conversation` groups only the conversations the caller sees and folds the rest as the table says.
- **A row**: its key and label. A principal by name ("{name} (left)"); an agent or a trigger by name (a deleted one by its last name); a model; a conversation by its key, with its title only when it is the caller's own or a trigger's conversation they see. Another person's personal conversation never shows its title, to OWNER either: a title is content, and a spend view records no read (§5.6); following the key opens the conversation, which does. Then `runs`, `requests`, the tokens, `costUsd` (`GATEWAY` and `RELAY`, with the share that came from the relay), `subscriptionTokens`; the query's totals beside.

| Caller | Runs counted | Conversation rows |
|---|---|---|
| Every member | Those they paid (their personal conversations', and their triggers' paid by them), and the runs of triggers whose author or team they are | Their own; the triggers' they see; titled |
| ADMIN; an instance admin under break-glass | Every run | Triggers' conversations, titled; another's personal conversations as one row per person, without keys (open point 2) |
| OWNER | Every run | Every conversation; another's personal conversation by key only |

- **A principal's period**: `GET /me/model-access` (built) and an admin's view of a principal gain `periodSpend`, this budget period's `run_spend` by agent and trigger, beside LiteLLM's `spentUsd`.
- **CSV**: `GET /spend` with `Accept: text/csv` answers the same rows, at most 100 000, and is audited `spend.exported` (the filters, the grouping, the rows): it is a bulk export of people's usage. Reading spend in the app is not audited.
- An agent's owner sees no spend of it in package 6 (open point 3).

### 5.3 Budgets: what package 6 adds

Package 2 holds the budgets: the company's default for every member, an override per member, a budget per service identity, LiteLLM's period, `BUDGET_SPENT` and the waiting turns. Package 6 adds:

- **Alerts at thresholds.** After each sync that read a key's spend (`KeySync`: after every run and in the reconciliation), `BudgetAlerts.check(principal)` inserts a `budget_alert` per threshold the period's spend crossed; a new row is an inbox item (open point 4):

  | Principal | Told at each threshold | Also told at 100 % |
  |---|---|---|
  | A member | The member: "You have used 80 % of your budget (USD 40 of 50); it resets on 1 November" | Admins, with how many of the member's conversations and triggers wait: counts only |
  | A service identity | Admins, and the authors of the live triggers it pays | The same, with the counts of what waits |

  An item stays until the period resets or the budget is raised above the spend; an admin's item links to package 2's `PATCH /principals/{id}/model-access`.
- **Key rotation** (row 2, package 2's, in flight): the principal's model access, for admins, shows the key's last and next rotation and package 2's "rotate now"; the switches' page offers the company's "rotate every key now" (§5.4). Members see nothing of keys. Admins get an item when a rotation failed (the key `ERROR` after it) or a key is more than 7 days past its rotation.
- **The company's total**: with `monthly_alert_usd` set, admins are told once a calendar month when the company's `run_spend` crosses it; nothing is capped (open point 18).

### 5.4 Kill switches on one page

`GET /kill-switches` shows admins every switch that is on, with who, when and why, and its undo; a paused system comes with the triggers it paused. A member sees the company's stop and their own suspension (open point 24).

| Switch | Who pulls it | What stops | Running turns | Undone by | Built in |
|---|---|---|---|---|---|
| Cancel a turn; end a conversation | Its person, OWNER; a trigger's audience | That conversation | Cancelled | Retry; an end is final | 3c, 4 |
| Pause an agent | Its owner, admins | Its turns wait `AGENT_PAUSED`, approvals are refused, triggers fire nothing, chats answer "paused" | `stopRunning` | Its owner, admins | 3a, 4, 5 |
| Pause a trigger | Its author, its team, admins | It fires nothing; its turns wait `TRIGGER_PAUSED`; its write-back is `HELD` | `stopRunning` | Its author, admins | 4, 5 |
| Remove a chat bot; unlink a chat user | Admins | The system's events dropped unanswered; the user's direct messages refused | — | Setting the bot; linking again | 5 |
| Switch personal MCP servers off | Admins | Every personal server binds for nobody | — | Admins | 1 |
| **Pause a system** | Admins | Every use of its accounts (G5); its triggers pause `SYSTEM_PAUSED` | `stopRunning`: those whose conversation bound it | Admins; its triggers in the resume's dialog, else by hand | 6 |
| Suspend a service identity | Admins | Everything that uses its accounts or budget; its key blocked; triggers with a row of its accounts pause `ACCOUNT_BROKEN`, those it pays wait `SUSPENDED` (package 4, decision 9) | Cancelled (new) | Admins | 1, 2, 6 |
| **Suspend a member** | Admins (an OWNER: `MANAGE_OWNERS`); nobody themselves; never the last active OWNER | Everything they act, pay, write or decide in the company; their bearer credentials there (below) | Cancelled | Admins | 6 |
| **Deactivate a user** | Instance admins | Their sessions and requests (built); their principal in every company, suspended `USER_DEACTIVATED` | Cancelled | Reactivating the user | identity, 6 |
| Rotate a key now | Admins | The old key, at once | As package 2 rotates | — | 2 (in flight) |
| **Rotate every key now** | Admins | Every key of the company's principals, at once: the switch for a suspected leak on the server's side | As package 2 rotates | — | 2 (in flight), 6 |
| **Stop the company's runs** | OWNER, ADMIN; an instance admin under break-glass | Everything that runs (G4) | Cancelled | OWNER, ADMIN; the instance admin who stopped it, within the same access | 6 |

**Pausing a system** (`POST /systems/{id}/pause {reason, stopRunning}`; open point 6) stops every use of its accounts (G5): `Accounts.check` answers `SYSTEM_PAUSED` for them. For a conversation the system is then one without an account (Part 1): its tools are not listed in the next turns, and a call from a turn that listed them before the pause is refused `TOOL_DENIED` ("{system} is paused by an admin"), as a call to a system without a row is (package 4); bindings keep their state, and nothing parks. A queued turn whose workspace holds one of its repositories waits `SYSTEM_PAUSED`, a queue reason. With `stopRunning`, the running turns of the conversations bound to it are cancelled. Its polls call nothing. Every trigger whose source is the system, or whose table has a row on it, **pauses `SYSTEM_PAUSED`**: package 4's decision 9, an account that cannot be used pausing the whole trigger, with a reason of its own so that the resume can find them; its author, its team and the admins are told. Write-back rows to the system are `HELD`. A paused chat system's events are verified and dropped (`SYSTEM_PAUSED`), since answering needs the system; a paused webhook system answers `200 {"paused": true}` and fires nothing: a pause stops a system whose traffic is not wanted (the company's stop, below, holds traffic instead). Testing, refreshing its tools and connecting accounts on it are `409 SYSTEM_PAUSED`.

**Resuming it** (`POST /systems/{id}/resume {resumeTriggers}`). The dialog lists the triggers the pause paused, each with its author and the accounts and payer it acts with, and offers "Resume its {n} triggers too", on by default. So the resume stays a person's choice over the triggers, as decision 9 wants, at no extra click. Each chosen trigger is resumed through package 4's gate, as an admin resumes a trigger, with `armed_at` moved to the resume, so nothing of the pause fires (decision 10); one the gate stops stays paused with its new reason; one left out stays `SYSTEM_PAUSED` until its author or an admin resumes it. The polls' windows move to the resume too, and the rows held for the system are released, but those of a trigger that stays paused. A trigger that was paused for another reason before the system's pause keeps that reason.

**Suspending a member** (`POST /members/{userId}/suspend {reason}`; open point 5) makes their principal `SUSPENDED`, as a service identity's is (built), so that they act, pay, write and decide nothing in the company (G6):

- Their key is blocked after the commit (package 2's M6). Their bindings, and every binding holding their account, stop with `PRINCIPAL_INACTIVE`. Their conversations' queued turns wait `MODEL_ACCESS_UNAVAILABLE`, and their running turns are cancelled (below).
- The triggers they author pause `NOT_ENTITLED` (package 4's gate: `PRINCIPAL_INACTIVE` for the author) and are resumed by hand after the member is.
- Every write of theirs in the company is `409 PRINCIPAL_SUSPENDED`: messages; decisions (`DecisionService.mayDecide` answers no, so a suspended team member or admin approves nothing and answers no question, in the app, a chat or a client); edits of their agents and settings; triggers (their own, and an admin's edits on another author's behalf); account connections; and every admin act.
- Their bearer credentials, personal tokens and OAuth grants alike, answer `NOT_FOUND` in the company: for a bearer call the guard treats them as a non-member.
- Their direct messages are answered "Your Tinfirm account is suspended in {company}".
- Their reads in the app are open point 24 (proposal: they stop too).

Admins suspend; an OWNER's suspension needs `MANAGE_OWNERS`; nobody suspends themselves (`409 CANNOT_SUSPEND_SELF`), nor the last active OWNER (`409 LAST_OWNER`). Resuming wakes the waiting turns (`PrincipalResumed`, built); paused triggers stay paused until resumed by hand (package 4's §11).

**Suspending either kind cancels the running turns** that act for or are paid by the principal: the run module, after the commit of `PrincipalSuspended`, cancels each (`Turns.cancelActive`, audited `run.cancelled` with the cause) in a transaction of its own; meanwhile the relay already refuses the blocked key.

**Deactivating a user** at the instance (built: `/api/admin/users`) suspends their principal in every company where they are a member, with the cause `USER_DEACTIVATED`, audited in each company's chain; reactivating the user resumes the principals suspended with that cause, and only those. Today a deactivated user's requests are refused (threat model 10.10), but their triggers keep acting with their accounts.

**Stopping the company's runs** (`POST /runs-stop {reason}`, `DELETE /runs-stop`; open point 7). In one transaction the company is marked and every running or assigned run is cancelled, the stopper its actor. While it lasts:

- the dispatcher's step 0 holds every queued run (`COMPANY_STOPPED`);
- starting a conversation, from the app, a direct message or the endpoint, is `409 COMPANY_STOPPED`; a message to an open conversation, in the app, through the endpoint or as a direct message continuing one, is taken and its turn waits;
- approving is `409 COMPANY_STOPPED` (answers and denials are taken), and an approved call not yet run does not run;
- `TriggerGate` fires nothing and records nothing, while polls go on; a trigger's health reads `COMPANY_STOPPED`;
- a mention in a channel fires nothing and is stored nowhere, as under a paused trigger (package 5 §5.14), and is never answered later;
- the bot answers a chat user at most once an hour: "Tinfirm is stopped in {company}; your message waits" for a taken direct message, "Tinfirm is stopped in {company}; nothing was started" otherwise;
- webhooks answer `503` with `Retry-After: 300` and store nothing, so that a sender that retries on `5xx`, as Standard Webhooks senders do, delivers again after the lift, when the delivery fires as a new one (open point 7 names dropping them as the alternative);
- write-back rows are `HELD`; the relay answers `503` to the company's tokens.

Every page shows a banner with who and why. OWNER and ADMIN (`STOP_RUNS`) lift it; an instance admin who stopped it lifts it within the same access, or the company does. The lift moves every active trigger's `armed_at` to it, releases the held rows and dispatches the queue; its dialog shows how many triggers go on again and which other switches stay on.

**The stop and package 4's decision 9.** The lift arms the triggers again without a resume of each: a deliberate exception to decision 9 (open point 7). Decision 9 guards a trigger's authority: an account that broke may have been revoked on purpose, so a person looks before the trigger acts with it again. The stop says nothing about any account or trigger: it holds the whole company, and its lift is itself an admin's deliberate act, made under a banner on every page and with the list of what goes on. A trigger whose account did break meanwhile is paused by the gate as decided, and every other switch pulled during the stop (a system, a principal, a trigger) stays on after the lift.

### 5.5 A member who leaves

Removing a member (`DELETE /members/{userId}`, built), or an instance admin deleting the user, runs `PrincipalDependents.memberLeaving` in the transaction that ends the membership:

| What was theirs | Becomes | Built in |
|---|---|---|
| Their principal | `LEFT`, kept, so that conversations, spend and the audit name who acted | 1 |
| Rejoining | A new principal (`principal_member_uq`): a new key at the company's default, no accounts, settings, triggers, personal systems or hosts; the old conversations stay the old principal's, readable to OWNER and not to the person who rejoined (package 1's §10); the Owner may give them a copy (open point 23) | 1 |
| Accounts, chat links, owned secrets, personal MCP servers | Deleted; OAuth grants at the systems revoked after the commit, best effort | 1, 5 |
| LiteLLM key, budget override | Deleted, in LiteLLM after the commit; their spend stays in `run_spend` under the left principal | 2, 6 |
| Personal runner hosts | Removed, connections closed | 2 |
| Settings; the default agent of their direct messages | Deleted | 3a, 5, 6 |
| Agents they own | Pass to the admins (`owner_principal_id NULL`); admins may give one a new owner (open point 13) | 3a, 6 |
| Personal conversations: app, AI client, direct messages | End `PERSON_LEFT`, running turns and open decisions cancelled; readable to OWNER, under retention and holds (§5.9) | 3c, 5 |
| Triggers they author | Pause `AUTHOR_LEFT`; their open conversations wait, and once reassigned and resumed are rebound to the new table and payer (package 4's §11) | 4 |
| Triggers with a row of their accounts | The row loses its account: pause `ACCOUNT_REMOVED` | 4 |
| Group memberships | Gone with the membership (built cascade); package 6 publishes `GroupMembersChanged` for each, so teams and grants are checked again | company, 6 |
| OAuth grants and tokens at the company's MCP endpoint | Grants consented for this company revoked; personal tokens are the user's across companies and answer `NOT_FOUND` here (built) | identity, 6 |
| Service identities' accounts they connected (`connected_by`) | Stay, listed for reconnecting: the token may be one they made | 6 |
| Change requests and branches made with their own account | Stay at the code host, under their name; listed | 6 |
| The audit | Untouched; the actor's name is resolved when read | ADR-0010 |

**Who is told** (open point 21): admins, by a `DEPARTURE` item until the checklist is closed; each paused trigger's team, by a `TRIGGER_ORPHANED` item ("{trigger} paused: its author {name} left; an admin reassigns it"), beside package 4's admins' item; nobody else. A passed agent's page reads "Owned by the admins since {date}".

**The checklist**, computed from the state: `GET /members/{userId}/departure` before the removal, as a preview of what it will do (how many triggers pause, conversations end, agents pass, hosts go), and `GET /principals/{principalId}/departure` after it, with an action per item:

1. Triggers paused `AUTHOR_LEFT`, each with the rows and the payer a new author must replace, and its waiting conversations: reassign or delete (package 4).
2. Teams left without a member, whose approvals nobody can decide: add members or change the team.
3. Service identities' accounts they connected: connect again with a new token.
4. Agents passed to the admins: give a new owner, or keep.
5. Open change requests their conversations opened with their own account, with links.
6. Their ended personal conversations: a count, the holds on them, and erase now (§5.9).
7. Their spend in the last period and the last twelve months.

Items 1 to 3 stay open until resolved. `POST /principals/{principalId}/departure/close` closes the checklist once they are (`409 DEPARTURE_OPEN`), or with `force` when an admin accepts what remains.

### 5.6 The Owner's reads and the notices

A personal conversation, started in the app, from an AI client or by a direct message, is read in full by its person and by OWNER (Part 8, PLATFORM §9, ADR-0058 §10). ADMIN, every other member and an instance admin who is not a member get `404` on every path: the built exception for an instance admin goes (§2.2; open point 12). An instance admin who is a member of the company reads by their role (the fix in flight). Package 6 records every read by someone other than its person (row 18, G8), on the in-flight `conversation.read`:

- `ConversationReads` (§5.7), on every read path of the conversation (its detail, runs, run events and their stream, decisions and files, and package 5's chat paths), upserts `conversation_read` per Owner, conversation and kind (`DETAIL`, `RUNS`, `EVENTS`, `DECISIONS`, `FILES`), and audits `conversation.read` when that kind's `last_audited_at` is more than 15 minutes old, with the kind and the reads since the last event (open point 9). A file's download is audited every time, with its `attachmentId`. The record is written in a transaction of its own (`REQUIRES_NEW`), as `AdminAccessAudit` writes its record, so a page that fails after it still leaves it.
- OWNER's list of other people's conversations (`GET /conversations` beyond their own, `?personId=`) is `conversation.read` of kind `LIST`, once per Owner, filter and 15 minutes, with the filter and the count: listing is not reading each.
- A person's export (open point 23) is kind `EXPORT`, audited every time.
- **This amends row 18** ("every read is audited") to: every read is recorded and shown to the person; it is audited once per Owner, conversation and kind of read every 15 minutes, and each download and export every time. The record's row changes once open point 9 is decided.
- **The person sees the reads**: `GET /conversations/{id}/reads` lists who read it, when first and last, and which kinds; the conversation's page shows it under the notice (open point 10).
- The MCP endpoint never shows another person's personal conversation (G9; open point 11), a chat has no path to one, and a spend view shows one by key only (§5.2).

**The notices** (G10):

| Place | Where and when | Says | Built in |
|---|---|---|---|
| The app | Every personal conversation's page and the start page (`seenByOwner`, the web plan's `OwnerNotice`) | "The Owners of {company} ({names}) can read this conversation in full, for support and audit. Each read is recorded and shown here." | 3c, web plan; package 6 adds the names and the record |
| A chat | The bot's first direct message to a chat user (`notice_shown_at`), and the link page | Package 5's §5.6 text, with the names | 5 |
| An AI client | The consent page, for the company of the `resource`; the endpoint's `initialize` instructions; `start_conversation`'s first answer to a member | "Conversations you start through {client} are personal: agents act with your own accounts and budget. The Owners of {company} ({names}) can read them in full; each read is recorded and shown to you." | 6 |
| A personal access token | `/me`, when creating a token, and the answer of `POST /api/me/tokens` | "Conversations you start with this token are personal: agents act with your own accounts and budget. The Owners of each company you start them in can read them in full; each read is recorded and shown to you." | 6 |

`GET /api/me/oauth/consent` gains `ownerNotice: {companyName, owners}`, shown above the levels; `POST /api/me/tokens` answers `ownerNotice: {companies: [{companyName, owners}]}` for the user's companies. A personal token sees no consent page, so `/me` is where its person is told; the `initialize` instructions reach only the model.

### 5.7 The MCP endpoint on conversations

The endpoint stays as built (CONVENTIONS §14.11): stateless Streamable HTTP at `POST /api/companies/{companyId}/mcp`, bearer only (personal access tokens, the authorization server's tokens of an hour with refresh tokens of 30 days), `Origin` refused, one message per request, `tinfirm:read`, `tinfirm:write` and `tinfirm:decide` declared per tool and checked on the request (`403 insufficient_scope`), the REST DTO as structured content and a text with other people's words labelled, `tokenId` or `grantId` on the audit. Package 6 gives it tools on conversations, as the credential's member, in their personal space. Every tool calls a public API; none reaches an `internal` class, a repository or a table (CONVENTIONS §10):

| Scope | Tool | Calls | Does |
|---|---|---|---|
| read | `get_inbox` | `ConversationReads.inbox` | What waits for the member |
| read | `list_agents`, `get_agent` | `Agents.access`, `Agents.readiness` (public, §2.2) | The agents they use; for one, who would pay, how each system would bind, whether a host can run it |
| read | `list_conversations`, `get_conversation` | `ConversationReads` | Their own and the triggers' they see (open point 15); one with its header (agent, subject, who pays, bindings in words, turn status and wait), open decisions, outputs and the newest messages by cursor |
| read | `get_turn` | `ConversationReads.turn` | A run and its transcript, capped at 60 000 characters with `afterSeq` (`TranscriptText`) |
| read | `list_decisions` | `ConversationReads.decisions` | Decisions they decide; a team's approval with `decideIn: APP` and its link in the app |
| read | `get_my_spend` | `governance.Spend` | Their budget, spend and reset (built on 2026-10-16 as `get_spend`, `Spend.report`: the Spend page's report in its scope, their own and their triggers', every run for OWNER and ADMIN; not the budget and its reset, which `getSpend` does not answer) |
| write | `start_conversation` | `ConversationCommands.start` | `{agent (id or @handle), message, subject?: {system (id or handle), key}, repositoryIds?}`: origin `CLIENT`, place `TINFIRM_THREAD`, paid by the member |
| write | `send_message` | `ConversationCommands.post` | `{conversation (id or key), body, answersOpenQuestions?}` |
| write | `choose_account`, `cancel_turn`, `retry_turn`, `end_conversation` | `ConversationCommands` | A grant among a binding's choices; the rest as the person does in the app |
| decide | `decide` | `ConversationCommands.decide` | An answer to a question, or the outcome of the member's own approval; a team's approval is the tool error `DECIDE_IN_APP` with its link (open point 15). Its description says the client asks the person first (ADR-0045) |

- **As the person.** `start_conversation` is package 5's `PersonalConversations.start` with origin `CLIENT` and `client_name` (the OAuth client's, or "personal access token {name}"): the member's settings, bindings, budget or subscription, and the app's refusals. A Connect card is a tool result naming the system and the conversation's page in the app, where OAuth or a token connects it. The conversation is the app's in everything, with an "AI client" badge. A decision decided through the endpoint records `decided_via` `CLIENT` (package 5's column) beside the grant or token.
- **A team's approvals stay in the app and the chat** (open point 15), where Tinfirm renders the call and its arguments. Threat model R37 accepts prompt injection into a client because what the client can do is the person's own; a team's approval releases a write with a service identity's account into a shared place.
- **Not offered**: uploads; connecting accounts; changing agents, settings, triggers or systems; another person's personal conversation (G9); a team's approval; admin surfaces; spend beyond one's own. An instance admin who is not a member gets `NOT_FOUND` from every tool and `access_denied` at the consent (§5.10).
- **`ConversationReads` and `ConversationCommands`** are the conversation module's public API over its internal services (`ConversationService`, `ConversationViews`, `ConversationTurns`, `ConversationMessages`, `ConversationBindings`, `DecisionService`, `InboxService`, `AttachmentService`). Each call takes the caller and a view, `APP` or `CLIENT`; the `CLIENT` view leaves out other people's personal conversations and decides no team's approval. They record the Owner's reads (§5.6), and the REST controllers move onto them (open point 20), so that the app and the endpoint cannot drift.
- **Rate limits.** The built bucket per credential (120 requests a minute, per replica) stays. A member starts at most `TINFIRM_MCP_STARTS_PER_HOUR` (30) conversations an hour through the endpoint, counted from `conversation` (`origin CLIENT`), so the limit holds across replicas; over it, the tool error `RATE_LIMITED` with `retryAfter` (open point 14).
- **What changes in `mcp/`**: the tool sets `McpConversationTools`, `McpAgentTools`, `McpDecisionTools`, each method with its `@ToolScope`; the module's dependencies (§2.3); the `initialize` instructions gain the notice and "a turn takes minutes: follow it with `get_conversation`"; the earlier application's renderings ported. The scopes, the consent's levels (beside the notice), the tokens, the filter and the limiter stay.

### 5.8 The audit

Built: a hash chain per company and one for the instance, `GET /audit-events` newest first by cursor, a chain's verification, seen by OWNER and ADMIN (`VIEW_AUDIT`) and instance admins. Package 6 adds:

- **Filters** on `GET /audit-events`: `action` (exact, or a prefix ending in `.`, such as `tool.`), `actorType`, `actorId`, `targetType`, `targetId`, `from`, `to`, and `conversationId`, `principalId` in the details; combined, newest first, keyset on `seq`.
- **Export** by sequence: `GET /audit-events/export?fromSeq=&toSeq=` streams `application/x-ndjson`, oldest first, a contiguous range of `seq` (a chain's order; `occurred_at` across replicas need not follow it), one line per event in its canonical form with `seq`, `prevHash` and `hash`, then a line with the chain's head at the export. `from` and `to` (times) are accepted instead and resolve to the contiguous `seq` range from the first event at or after `from` to the last before `to`, which the first line states. At most 1 000 000 events (`400 AUDIT_EXPORT_TOO_LARGE`). Anyone holding an export checks it offline by ADR-0010's rule, from its first `prevHash`. Audited `audit.exported` once it ends.
- **The daily check**: `AuditCheck`, on `worker` replicas, checks every chain once a day from the previous check's head: the event at that `head_seq` must still exist with the recorded `head_hash`, and every event after it must chain from it. It fails `SHORTER` when the chain ends before that head (removing the newest events leaves a valid, shorter chain, as ADR-0010 notes), `HEAD_CHANGED` when the hash at that head differs, and `BROKEN` when an event after it does not verify (`first_invalid_seq`). The verification when asked recomputes the whole chain, as built. A failure is an item for OWNER and ADMIN and an `ERROR` log until a check passes, or until an OWNER acknowledges it (`POST /audit-events/checks/{checkId}/acknowledge`, audited), after which the next check anchors at the chain's current head. A passed check records the head it verified; a failed one the chain's last event as found: writers lock the head row and the check does not, so an event appended while a check ran is not its head (it would anchor the next check past an event nobody verified), while an acknowledged failure anchors past the damage (`AuditCheckServiceTest`). The audit page shows the last check and the head's hash, which an admin may note elsewhere; until anchoring proper (threat model Q6), the recorded heads are the one anchor Tinfirm has.
- **Who sees it**: as built. Members do not; they see the reads of their own conversations on the conversation (§5.6). ADMIN sees `conversation.read` events: ids, never content.
- **Its retention**: kept for the company's life; nothing purges it (G11; open point 17). Its `details` hold ids and Tinfirm's own names only, by the rules of packages 1 to 5, so erasure never needs it.

### 5.9 Retention, holds, erasure and export

**Per kind**, from `retention_settings` (OWNER: `PUT /retention`; open point 16). `RetentionSweep`, on `worker` replicas once a day, takes `retention_due(now, limit)` per company and kind, and purges through each module's purge API (§2.3), 500 rows a transaction, what §3.7's table names, of runs or conversations ended longer ago than its period, skipping what an open hold covers (G16). What stays: the conversation's tombstone (key, agent, person or trigger, place, subject key, dates, turn count, ended reason), its runs without text (status, hosts, payer, tokens), `run_spend`, decisions' kinds and outcomes, outputs' kinds and links, and the audit. A purged conversation reads "Its content was removed on {date} under the company's retention." A sweep is audited once per company (`content.purged`, counts per kind); shortening a period purges at the next sweep, and the `PUT` answers how much.

**Open conversations** are never purged by retention (G12), and packages 4 and 5 end nothing on idleness, so a chat thread, a tracker conversation or an app conversation left open keeps its content whatever the periods say. The sweep reports them (open point 25): an OWNER item, "{n} open conversations older than your retention; end them to let retention apply", and the retention page lists them by key, place and age (no title), with an end per conversation or for all.

**Legal holds** (open point 22), by OWNER (`MANAGE_RETENTION`): `POST /holds {conversationId | principalId, reason}`, `DELETE /holds/{holdId}`, `GET /holds`; audited `hold.set` and `hold.lifted` with the ids, the reason on the row only. The sweep skips what an open hold covers (§3.7), and an erasure that would purge any of it is `409 ON_HOLD`, naming the holds. A hold brings back nothing a sweep removed before it. "Keep" (a `NULL` period) stays the company-wide hold.

**Erasing content on request** (open point 17), by OWNER (`MANAGE_RETENTION`), refused while a hold covers any of it (`409 ON_HOLD`):

- `POST /principals/{principalId}/erase`: for a member who left, every personal conversation of theirs, and their `PERSON` messages, the files they added and the texts of what they decided in triggers' conversations; for a member still active, their ended personal conversations only (`409 ERASURE_NOT_ALLOWED` for an open one named). It purges now what all four kinds would; in an open trigger's conversation a message's body is replaced by the marker at once. It sets `erased_at` and is audited `principal.erased` with counts.
- `POST /systems/{systemId}/identities/{externalId}/erase`: the words of someone outside Tinfirm, a chat user or a tracker commenter, in every conversation, open ones included (their bodies replaced by the marker at once, since packages 4 and 5 force no end): the bodies and `external_author` of `EXTERNAL` messages by that identity; the run events of the runs that took them, since their prompts quoted them (`# Subject`, `# Request`, `# Conversation so far`); `decided_by_name` and `decided_by_external_id` where the identity answered; the actor's name of its firings (`trigger_firing.actor`); and its `chat_user` row. Audited `identity.erased` with counts.
- **What remains is said in the answer**, with where and who can remove it: the audit (ids only) and `run_spend` and the tombstones (no text); the agent's own messages, outputs and decisions' texts, which may quote the words, until `content_days` or the conversation's own erasure; the runtime's session home on a runner host, which a later turn of an open conversation resumes, outside the server's reach; the source system itself (the comment on the issue, the message in the chat), the company's to remove there; and a member's name and email, which are the instance's (`app_user`). Tinfirm's company-level erasure removes content; the identifier's link to a person is severed only when an instance admin deletes the user, after which the audit names "deleted user". Which requests the law makes the company honour, against which duty to keep records, the company decides case by case, with holds.

**A person's export** (open point 23), by OWNER (`MANAGE_RETENTION`): `GET /principals/{principalId}/export` streams a ZIP of one person's content: their personal conversations with messages, decisions, outputs and files; what they said, added and decided in triggers' conversations; nothing purged. It reads in short transactions and streams outside any, is audited `principal.exported` with counts once it ends, and records the Owner's read of each conversation (kind `EXPORT`). The Owner hands it to the person; a member who rejoined gets their old conversations this way (package 1's §10).

**Fixed elsewhere**, unchanged: `chat_event` a day after receipt, its payload once handled; `webhook_delivery` bodies 24 hours, rows 7 days; `chat_user` 90 days after last seen (package 5); `trigger_subject` unread 90 days, `system_write` 90 days (package 4); revoked relay tokens a day (package 2); LiteLLM's spend logs as the bundled gateway's configuration keeps them, without prompts (row 17). New with package 6: `conversation_read` 400 days after its last read, `relay_usage` 30 days, `legal_hold` 400 days after it is lifted.

### 5.10 Instance administration

An instance admin is a flag on a user (built), apart from the company roles: users, companies, the instance's chain, the vault's rewrap, the authorization server's key. A company's governance is its own: its LiteLLM team and keys, budgets, audit chain, retention and switches, and nothing of one company is seen from another. An instance admin who is a member of a company acts there by their role only (the fix in flight) and needs no access.

- **Break-glass**, with the mandatory reason threat model Q5 decided: an instance admin who is not a member opens an access with a reason (`POST /admin-access {reason}`), which lasts an hour or until ended; without one, every company path answers `409 ADMIN_ACCESS_REQUIRED`. Within it the instance admin sees what ADMIN sees (G13): personal conversations and their runs, run events, decisions and files are `404`, and spend folds them per person. Beside ADMIN's rights it keeps the audited right CONVENTIONS §6 gives it to change a member's role to OWNER (`member.role.changed`, visible to every member), which is the remedy for a company without a reachable Owner: the new OWNER then reads under the notice and the record (open point 12). The instance admin may stop the company's runs within it (§5.4). `admin.company.accessed` (built) is recorded when the access opens, with `adminAccessId` and `expiresAt`, and at each permission's first use within it, with the `permission`, as built; every event within carries `adminAccessId`; the company's OWNERs get a `BREAK_GLASS` item for 7 days ("{name} opened {company}: {reason}").
- **No break-glass through a client.** The authorization server refuses an instance admin who is not a member of the `resource`'s company (`access_denied`, naming no company), and the endpoint answers `NOT_FOUND` (G9). This changes CONVENTIONS §6 and reverses threat model R38.
- **Across companies**, without break-glass: `GET /api/admin/companies/summary` gives per company its members' count, the kinds and counts of the switches that are on (never their reasons, which people type and which may name them), the last audit check, and spend totals per model over a range, with no person, agent, trigger or conversation: what an operator of a bundled LiteLLM shared by companies needs to divide the providers' bill. It is read server-side through `companies_summary(from, to)` (§3.8), aggregates only, and each call is audited once in the instance chain (`admin.companies.summarised`, the range).
- **An instance-wide stop**: later (open point 8).

### 5.11 The inbox

`InboxService` (built, the conversation module's) shows, beside packages 3 to 5's items, the kinds below, which `governance` serves through package 5's port `InboxItems`, computed from the modules' public APIs as the built items are; none is a stored notification:

| Kind | For | Until |
|---|---|---|
| `BUDGET` | §5.3's recipients | The period resets, or the budget is raised above the spend |
| `COMPANY_SPEND` | Admins | The calendar month ends |
| `DEPARTURE` | Admins | The checklist is closed |
| `TRIGGER_ORPHANED` | A paused trigger's team, its author having left | Reassigned or deleted |
| `KEY_ROTATION` | Admins | Rotated |
| `AUDIT_CHECK` | OWNER, ADMIN | A check passes, or an OWNER acknowledges the failure |
| `SPEND_SYNC` | Admins | The spend logs are read again |
| `BREAK_GLASS` | OWNERs | 7 days |
| `RETENTION_OPEN` | OWNERs | No open conversation is older than the content period |

## 6. API

Under `/api/companies/{companyId}`, OpenAPI-first ([ADR-0013](../adr/0013-openapi-first-contract.md)). "Admins" means OWNER and ADMIN. Tags `Spend`, `Governance`, `Audit`, `Retention`. Two permissions are new: `STOP_RUNS` (OWNER, ADMIN) and `MANAGE_RETENTION` (OWNER). The MCP tools are JSON-RPC, outside `openapi.yaml`, under the module's own tenant test.

| Endpoint | Who | Does |
|---|---|---|
| `GET /spend` | Every member (`VIEW_MODEL_ACCESS`), scoped by §5.2 | `from`, `to`, `zone`, `groupBy`, filters; JSON, or `text/csv` (audited) |
| `GET /me/model-access`, `GET /principals/{id}/model-access` | Built | Gain `periodSpend`, and for admins the key's last and next rotation |
| Package 2's rotate-now, per principal and per company | Admins | In flight; offered on the principal's page and on `GET /kill-switches` |
| `PUT /model-settings` | Admins | Gains `budgetAlertThresholds`, `monthlyAlertUsd` |
| `GET /kill-switches` | Every member: the company's stop and their own suspension; admins: all, a paused system with its paused triggers | §5.4 |
| `POST /runs-stop`, `DELETE /runs-stop` | `STOP_RUNS` | `{reason}`; the lift |
| `POST /systems/{systemId}/pause`, `…/resume` | Admins (`MANAGE_SYSTEMS`) | `{reason, stopRunning}`; `{resumeTriggers}` |
| `POST /members/{userId}/suspend`, `…/resume` | Admins (`MANAGE_MEMBERS`; an OWNER's needs `MANAGE_OWNERS`) | `{reason}`; `409 CANNOT_SUSPEND_SELF`, `409 LAST_OWNER` |
| `POST /service-identities/{id}/suspend` | Built | Gains `reason`; cancels running turns |
| `GET /members/{userId}/departure` | Admins | What removing the member will do |
| `GET /principals/{principalId}/departure`, `POST …/departure/close` | Admins | The checklist; closing it (`force`) |
| `PUT /agents/{agentId}/owner` | Admins (`MANAGE_AGENTS`) | `{principalId}` of a member (open point 13) |
| `GET /conversations/{conversationId}/reads` | Its person, OWNER | §5.6 |
| `GET /audit-events` | Built (`VIEW_AUDIT`) | Gains §5.8's filters |
| `GET /audit-events/export`, `GET /audit-events/checks` | `VIEW_AUDIT` | NDJSON by `fromSeq`, `toSeq` (or `from`, `to`); the last checks |
| `POST /audit-events/checks/{checkId}/acknowledge` | OWNER (`MANAGE_RETENTION`) | `{note}`: the next check anchors at the current head |
| `GET`, `PUT /retention` | Admins read; `MANAGE_RETENTION` changes | §5.9 |
| `GET`, `POST /holds`, `DELETE /holds/{holdId}` | `MANAGE_RETENTION` | `{conversationId \| principalId, reason}`; lifting |
| `POST /principals/{principalId}/erase`, `POST /systems/{systemId}/identities/{externalId}/erase` | `MANAGE_RETENTION` | §5.9; the answer says what remains |
| `GET /principals/{principalId}/export` | `MANAGE_RETENTION` | A ZIP of one person's content (§5.9) |
| `POST`, `DELETE /admin-access` | An instance admin who is not a member | Open or end a break-glass access |
| `GET /api/me/oauth/consent` | Built | Gains `ownerNotice`; `access_denied` for an instance admin who is not a member |
| `POST /api/me/tokens` | Built | Gains `ownerNotice` in its answer |
| `GET /api/admin/companies/summary` | Instance admins | §5.10; audited |
| `POST /api/companies/{companyId}/mcp` | Built | The tools of §5.7 |

## 7. Errors

`SPEND_RANGE_INVALID` (400: over 366 days, `to` before `from`, more than two groupings, an unknown zone); `COMPANY_STOPPED` (409: a start or an approval while the company's runs are stopped); `SYSTEM_PAUSED` (409: testing, refreshing or connecting on a paused system); `CANNOT_SUSPEND_SELF` (409); package 1's `LAST_OWNER` (409, also on suspending the last active OWNER); package 1's `PRINCIPAL_SUSPENDED` (409 on every write of a suspended member, and the tool error of one; on their reads in the app, `403` should open point 24 be decided as proposed); `DEPARTURE_OPEN` (409); `AUDIT_EXPORT_TOO_LARGE` (400); `RETENTION_INVALID` (400: a period out of range, chat content kept longer than content); `ERASURE_NOT_ALLOWED` (409); `ON_HOLD` (409: an erasure that would purge what an open hold covers, naming the holds); `ADMIN_ACCESS_REQUIRED` (409); `RATE_LIMITED` (the tool error of a start over the hourly limit, with `retryAfter`); `DECIDE_IN_APP` (the tool error of `decide` on a team's approval, with its link); the authorization server's `access_denied` for an instance admin who is not a member; `NOT_FOUND` for anything the caller may not see (404, never 403). Queue reasons `COMPANY_STOPPED`, `SYSTEM_PAUSED`; `Accounts.check`'s `SYSTEM_PAUSED`; a trigger's `paused_reason` `SYSTEM_PAUSED` and `waiting_reason` `COMPANY_STOPPED`; `chat_event` reasons `SYSTEM_PAUSED`, `COMPANY_STOPPED`, `SUSPENDED`; a webhook's `503` with `Retry-After` during a stop. On the runner protocol nothing changes: a call on a paused system is `TOOL_DENIED`.

## 8. Audit actions

In the company's chain, with ids and kinds: no reason typed by a person, message, person's name or content (the reasons stay on their rows). The actor is USER for people, SYSTEM for the sync, the sweep and the check.

| Action | `details` |
|---|---|
| `model_settings.changed` | Package 2's; `changed` gains `budgetAlertThresholds`, `monthlyAlertUsd` |
| `company.runs_stopped`, `company.runs_resumed` | `runsCancelled` (ids); at the lift, `heldRows` released and `triggersArmed`; `adminAccessId` for an instance admin |
| `system.paused`, `system.resumed` | `stopRunning`, `runsCancelled`, `triggersPaused`; `resumeTriggers`, `triggersResumed` |
| `trigger.paused`, `trigger.resumed` | Package 4's, with the reason `SYSTEM_PAUSED` and `cause` `system.paused` or `system.resumed` |
| `principal.suspended`, `principal.resumed` | Package 1's, for both kinds: `kind`, `cause` (`ADMIN`, `USER_DEACTIVATED`); `name` only for a `SERVICE` principal, a member's `userId` instead, since a person's name stays out of the hashed fields (ADR-0010) |
| `run.cancelled` | Package 3's, with `cause` `principal.suspended`, `company.runs_stopped` or `system.paused` |
| `principal.departure_closed` | `force`, the counts of open items |
| `agent.owner_changed` | `formerOwnerPrincipalId` (null when the admins held it), `ownerPrincipalId` |
| `conversation.read` | The in-flight action: `conversationId` (null for a list), `personPrincipalId`, `kind`, `reads` since the last event, `attachmentId` for a download, `count` for a list |
| `conversation.started` | Package 3's, with `origin` `CLIENT` and `tokenId` or `grantId` (built) |
| `decision.decided` | Package 3's to 5's, with `via` `CLIENT` through the endpoint |
| `oauth.grant.revoked` | Built; `cause` `principal.left` |
| `spend.exported` | The filters and grouping, `rows` |
| `audit.exported` | `fromSeq`, `toSeq`, `count`; `from`, `to` when asked by time |
| `audit.check_acknowledged` | `checkId`, `failure`; never the note |
| `retention.changed` | The four periods after the change, `changed` |
| `content.purged` | Per kind, the rows or conversations purged; once per company and sweep |
| `hold.set`, `hold.lifted` | `holdId`, `conversationId` or `principalId`; never the reason |
| `principal.erased`, `identity.erased` | Counts per kind, and what remains by kind; the system and the identity's id for the second |
| `principal.exported` | `principalId`, counts per kind |
| `admin.company.accessed` | Built; `adminAccessId`, `expiresAt` when the access opens; `permission` at each permission's first use within it |
| `admin.companies.summarised` | In the instance chain: `from`, `to` |

Not audited: reading spend in the app (the CSV is), budget alerts (notices, not choices of authority; package 2's `model_key.budget_spent` stays), the daily check (kept in `audit_check`; its acknowledgement is audited), a person reading their own conversation.

## 9. Tests

| Test | Proves |
|---|---|
| Constraint tests against PostgreSQL: a second spend row per run and model, a spend row of another company's run, a `CLIENT` conversation of a trigger, a paused personal system, an access without a reason or longer than an hour, retention out of range, a purged text without its marker and a marker with its text, a purged attachment keeping its size and name, a hold naming both a conversation and a principal | §3, G3, G12 |
| `ModularityTest`: `governance`'s dependencies as §2.3 states them; nothing but `mcp` depends on it; no tool of `mcp` and no class of `governance` reaches an `internal` package | §2.3 |
| `SpendSyncTest` against `FakeLiteLlm` with spend logs: a run's tagged requests summed per model; another run's tags ignored; logs under two keys across a rotation; logs late, then settled; none after an hour, a `RELAY` row from the relay's count at LiteLLM's prices; `tinfirm.spend.source RELAY`; a subscription's run; a run that relayed nothing; two workers on one company, one read each; the admins' item | §5.1, G2, G3 |
| `SpendScopeTest` over every row of §5.2: a member, a trigger's author and team member, ADMIN with personal conversations folded, an instance admin under break-glass as ADMIN, OWNER with another's personal conversation by key only; a `conversationId` filter the caller may not see answering nothing, as an unknown id; titles; ranges, zones, two groupings; the CSV audited; tenant isolation | §5.2, G1 |
| `BudgetAlertTest`: each threshold once per period; the recipients per kind and at 100 %, with counts only; the item gone at the reset and after a raise; the company's monthly amount | §5.3 |
| Kill switches: every row of §5.4, each with what it stops, its running turns, who may pull and undo it; nothing undone by time or an event; a paused system's tools not listed, an earlier list's call `TOOL_DENIED`, a turn on its repository waiting, its triggers paused `SYSTEM_PAUSED` and resumed in the dialog, one left out staying paused, nothing of the pause fired; a suspended member's writes, decisions and bearer calls refused, their running turns cancelled, the last OWNER kept; a deactivated user's principals suspended and resumed on reactivation; the stop's chat answers and webhooks' `503` | §5.4, G4 to G7 |
| `MemberLeavesTest` (V20) | Every row of §5.5 in the leaving transaction, the grant revoked and `GroupMembersChanged` published; the checklist; Alice rejoining gets a new principal with nothing of before, and her old conversations are `404` to her and readable to OWNER |
| `OwnerReadsTest` (V22) | One `conversation.read` per Owner, conversation and kind every 15 minutes with the reads since, a download each time, the list audited once, the record kept when the page fails after it; the person's list of reads; ADMIN `404`; an instance admin `404` with and without an access |
| `McpConversationToolsTest` through the MCP Java SDK's client, with a personal token and an OAuth token (V21) | Starts as the member, `CLIENT`, paid by them; a Connect card's link; reads capped and labelled; `decide` needs `tinfirm:decide`, decides the member's own approval with `via CLIENT`, and answers a team's approval `DECIDE_IN_APP`; OWNER's list without others' personal conversations; the hourly limit; a suspended member's credentials `NOT_FOUND`; an instance admin who is not a member refused at the consent; the tenant test over every tool |
| `ConsentNoticeTest` | `ownerNotice` names the company's owners on the consent and on a token's creation; `initialize` carries the notice |
| `AuditQueryTest`, `AuditExportTest`, `AuditCheckTest` | The filters; an export by `seq`, and by time over its contiguous range, verified offline from its first `prevHash` to the head; a broken event, the newest events removed (`SHORTER`) and a head rewritten (`HEAD_CHANGED`) found by the daily check and reported; the acknowledgement anchoring anew |
| `RetentionSweepTest`, `HoldTest`, `ErasureTest`, `ExportTest` | Each kind on time; open conversations untouched and reported; tombstones and spend kept; the chain unchanged and verifying (G11, G12); a held conversation and a held person skipped by the sweep and refused by erasure (`ON_HOLD`), purged after the lift; a left member's words erased; a chat user's words erased in an open conversation, with the run events that quoted them, and the answer naming what remains; a person's export, its reads recorded |
| `BreakGlassTest` | No path without an access; the reason, the hour, the OWNERs' item, `adminAccessId` on every event; ADMIN's view; an OWNER appointed with `member.role.changed`; the summary across companies with kinds and counts only, audited in the instance chain |
| **V20** (new) Alice leaves | Her triggers pause and their conversations wait; her key, accounts, links, hosts and grants go; her agents pass to the admins; the team is told; the checklist lists the trigger, the empty team, svc-dev's account she connected and her open pull request; Bob takes the trigger (package 4's rebind) and the checklist closes |
| **V21** (new) Bob's AI client | Bob consents under the notice, starts a conversation from Claude Code that acts with his own Jira account and budget, answers its question, approves its comment with `tinfirm:decide`; the app shows it with "AI client"; Alice sees none of it; a team approval he sees is decided only in the app |
| **V22** (new) The Owner reads | `OwnerReadsTest` end to end, with the banner and Alice's list of reads; an instance admin under break-glass gets `404` on Alice's conversation |
| **V23** (new) The company stops | A running turn cancelled, queued ones and write-back held, starts and approvals refused, a direct message to an open conversation taken, a mention unanswered later, webhooks `503`, the relay `503`; the lift dispatches the queue and fires nothing for the polls' changes during the stop; a webhook delivery retried after the lift fires |

## 10. Open points

Numbered for the owner to decide one by one. Each carries its proposal and how the review of 2026-10-12 left it: *Reviewed: agree* where the reviewer agreed, with the notes the reviewer asked for; *Changed after review* where the proposal itself changed, and how; *New after review* for points 22 to 25, choices the review raised.

1. **A run's cost.** Row 3 says the relay tags each request with its run (built: `x-litellm-tags`); nothing says how the tags come back. LiteLLM writes spend logs in batches, and whether the pinned v1.103.0 keeps request tags in them without its enterprise features is to be confirmed (package 2's §13, row 10, found the tags themselves are not enterprise). **Proposal:** §5.1's sync by the run's tag, over the keys the relay used and the run's span, settled within an hour. Where LiteLLM's tagged logs are missing, the relay's own count: the relay sees every answer it streams, so it sums each answer's `usage` per run and model and prices it with LiteLLM's model prices, a source no sandbox reaches (threat model 13.3). The sandbox's `USAGE` totals never set a spend row: they are an estimate shown on a run until it settles, and a subscription's run, neither relayed nor metered, keeps the tokens it reported, with no cost. Should the opt-in test against a real LiteLLM show that the tags do not come back, the relay's count is the source from the start (`tinfirm.spend.source RELAY`). *Changed after review: the reviewer agreed and strengthened the fallback, from the sandbox's report to the relay's count; it is the reviewer's change.*
2. **Spend per conversation for ADMIN.** Part 8 gives admins all spend "by person, agent and trigger"; a row per personal conversation would show ADMIN keys of conversations it may not see. **Proposal:** another's personal conversations as one row per person for ADMIN, without keys; conversation rows for triggers' conversations, and for OWNER, who sees another person's personal conversation by key only. *Reviewed: agree; the reviewer's rules for filters, titles and the CSV applied (§5.2, G1).*
3. **Spend for an agent's owner.** Part 8 is silent. **Proposal:** none in package 6; later, if owners ask, the agent's daily totals per model, without people or conversations. *Reviewed: agree.*
4. **Budget alerts.** The record names budgets, not alerts. **Proposal:** thresholds at 80 % and 100 %, a company setting of 1 to 3 values; a member told of their own, admins and the paying triggers' authors of a service identity's, admins of everyone's at 100 %, with counts of what waits; the inbox only, since Tinfirm sends no mail; a direct message by the chat bot to a linked member later, through package 5's outbox. *Reviewed: agree; the admins' alert carries counts only.*
5. **Suspending a member.** PLATFORM §4 names suspension for service identities; Part 8 says "suspend a principal", and the schema allows both. **Proposal:** members too, as the kill switch for a compromised person. A suspended member acts, pays, writes and decides nothing in the company: every write is `409 PRINCIPAL_SUSPENDED`, `mayDecide` answers no, and their bearer credentials answer `NOT_FOUND` there. Running turns are cancelled for both kinds, a change to what is built. Admins suspend, `MANAGE_OWNERS` for an OWNER, nobody themselves, never the last active OWNER (`LAST_OWNER`). Deactivating a user suspends their principal in every company (`USER_DEACTIVATED`), and reactivating resumes those. Their reads in the app are open point 24. *Changed after review: the first draft kept a suspended member's login, reads and decisions; the reviewer argued against it, and the proposal is the reviewer's.*
6. **Pausing a system.** The record's kill switches do not include it, and package 4's decision 9 pauses a trigger whose account cannot be used until someone resumes it. **Proposal:** a pause per company system, for admins (a leaked app, a system misbehaving): its accounts used for nothing; its tools not listed, as for a system without an account, and a call from an earlier list refused; a turn needing its repositories waiting `SYSTEM_PAUSED`. Its triggers **pause** `SYSTEM_PAUSED`, as decision 9 decides, and the resume's dialog offers to resume them too, on by default, each through the gate with `armed_at` moved to the resume, so that the resume stays a person's look at the triggers at no extra click; one left out stays paused until resumed by hand. *Changed after review: the first draft let the triggers wait and go on by themselves at the resume, an unstated exception to decision 9; they now pause, the reviewer's preferred change.*
7. **Stopping a company's runs.** The record lists three kill switches. **Proposal:** §5.4's: OWNER and ADMIN (and an instance admin under break-glass) stop and lift it with a reason; running turns cancelled; nothing new runs; messages to open conversations taken and waiting; channel mentions firing nothing; webhooks answered `503` with `Retry-After`, so their senders deliver again after the lift; write-back held and delivered after it; polls go on, as under a paused agent. At the lift every trigger is armed again without a resume of each: **a deliberate exception to package 4's decision 9**, since the stop says nothing about any account or trigger and its lift is itself an admin's act, while a trigger whose account broke meanwhile is paused as decided and every other switch stays on (§5.4). The alternative for webhooks: dropped during the stop (`200 {"stopped": true}`), so that nothing of the stop fires after it, as for polls, at the cost of deliveries the sender believes made. Polls and webhooks differ on purpose: a poll is Tinfirm's own reading, and the next change reads the subject again; a delivery is the sender's only message. *Changed after review: the reviewer agreed with the stop and asked for webhooks answered `503` and the exception to decision 9 stated; both are in.*
8. **An instance-wide stop.** **Proposal:** later, not in package 6. When built: `POST /api/admin/runs-stop` (instance admins), with a reason, stops every company as §5.4 does, is recorded in the instance's chain and in each company's, is shown to each company's admins, and is lifted only by an instance admin; a company cannot lift it, and a company's own stop stays independent of it. Meanwhile the operator stops runner hosts, which is slower and leaves triggers queuing work. *Reviewed: agree; the reason, both chains and who lifts it added, as the reviewer asked.*
9. **How often a read is recorded.** Row 18 says every read is audited; a conversation's page polls and streams run events. **Proposal:** the in-flight `conversation.read`, with a `kind` (`DETAIL`, `RUNS`, `EVENTS`, `DECISIONS`, `FILES`, `LIST`, and `EXPORT` for open point 23). Every read updates `conversation_read`; one audit event per Owner, conversation and kind every 15 minutes, carrying the reads since the last; each download and export its own; the record written in a transaction of its own (`REQUIRES_NEW`), so that a page that fails still leaves it. **Deciding this amends row 18**, from "every read is audited" to "every read is recorded and shown to the person; audited once per Owner, conversation and kind of read every 15 minutes, each download and export every time". *Changed after review: the reviewer agreed with the throttle and asked for the in-flight name, its kinds and the amendment of row 18 stated.*
10. **Showing the reads to the person.** Threat model Q30 asked whether reads are audited or shown. **Proposal:** shown: the page lists who read the conversation and when, as the notice promises; audit alone would keep an investigation quiet but make the notice weaker. *Reviewed: agree.*
11. **The Owner through an AI client.** **Proposal:** the endpoint shows OWNER only their own personal conversations and the triggers' they belong to; another person's is read in the app, under the banner and the record, never fed to a model in a client. *Reviewed: agree.*
12. **The instance admin's reads: an exception to Part 8, or not.** Part 8, PLATFORM §9 and ADR-0058 §10 give a personal conversation to "its person, and the Owner, in full". As built, an instance admin acting in a company sees it too (CONVENTIONS §6, §15.7), which package 6 changes (§2.2). **Proposal: no.** Under break-glass an instance admin gets ADMIN's view (personal conversations `404`) and the audited right to appoint an OWNER (`member.role.changed`), which is the remedy for a company without a reachable Owner: the new OWNER reads under the notice and the record. **Were the owner to decide yes**, it is an explicit exception to Part 8, recorded as such in ADR-0058 and PLATFORM §9, and it needs: `conversation_read.reader_role` (`OWNER`, `INSTANCE_ADMIN`) and `admin_access_id`; each read recorded and audited as the Owner's, with `adminAccessId`; the person's list of reads naming the instance admin and the access's reason; and every notice changed to say so: the app's "The Owners of {company} ({names}) and, with a recorded reason, the instance's administrators can read this conversation in full…", likewise the bot's first direct message and the link page (package 5 §5.6), the consent page's and `POST /api/me/tokens`' `ownerNotice`, the `initialize` instructions and `start_conversation`'s first answer; and threat model 1.29 and R42 widened to the instance's operators, a disclosure across tenants on a shared instance. *Changed after review: the first draft kept the built behaviour behind break-glass; the reviewer argued against it; the proposal is now no, and yes is spelled out as the exception it would be.*
13. **An agent whose owner left.** It passes to the admins and stays ownerless. **Proposal:** admins may give any agent a new owner, a member, audited `agent.owner_changed`; the new owner edits it as its creator did. *Reviewed: agree.*
14. **Starts through an AI client.** **Proposal:** at most 30 conversations started per member an hour through the endpoint, counted from `conversation`, configurable; messages bounded by the credential's request limit and the budget. *Reviewed: agree.*
15. **Triggers' conversations through an AI client.** The record calls the client a personal space, and threat model R37 accepts prompt injection into a client because what the client can do is the person's own. **Proposal:** the read tools list the triggers' conversations the member sees as author or team member; `send_message` answers their questions under package 4's `PLACE_IS_EXTERNAL`; `decide` answers questions and decides the member's own approvals, but not a team's approvals, which stay in the app and the chat buttons, where Tinfirm renders the call's arguments (`DECIDE_IN_APP`); no tool creates, edits or runs a trigger. The alternative: `decide` decides a team's approvals too, marked `decision.decided` `via CLIENT` and shown on the card, so that the team sees a model-mediated approval. *Changed after review: the first draft let `decide` decide a team's approvals; the reviewer argued against it, and the proposal is the reviewer's first choice.*
16. **Retention.** The record is silent. **Proposal:** §3.7's defaults (run events 90 days after the run; chat and webhook content 30 days and other content and files 365 days after the conversation ended), set by the OWNER; a leaver's conversations, "readable to the Owner" (Part 8), purged like any other; open conversations never purged by retention, and reported (open point 25); and a legal hold that both the sweep and erasure respect (open point 22), since "keep" is company-wide, undoes no sweep already done, and erasure ignored it. *Changed after review: the reviewer agreed with the defaults and argued against deferring every hold.*
17. **Erasure and the audit's own retention.** The record is silent; ADR-0010 keeps personal data out of the hashed fields so that erasure never breaks the chain. **Proposal:** §5.9: OWNER erases a person's or an outside identity's content at once, open conversations included, their bodies replaced by a marker; refused while a hold covers it (`ON_HOLD`); an outside identity's erasure also purges the run events whose prompts quoted its words; the answer names what remains, where, and who can remove it, the name and email at the instance by an instance admin deleting the user among them; `conversation_read` kept 400 days; a member's name never in the chain (`principal.suspended` carries a member's `userId`). The chain is never purged and is kept for the company's life; pruning it needs an anchored checkpoint (threat model Q6), later. The alternative for transcripts: keep them until `run_events_days` and say so in the answer. *Changed after review: the reviewer agreed the chain is never purged and found the erasure incomplete; the proposal is the reviewer's.*
18. **A company's total.** **Proposal:** an optional monthly amount at which admins are told; no cap in package 6: a cap is LiteLLM's team budget and a new wait in package 2's `forRun`. *Reviewed: agree.*
19. **Server replicas.** Package 2's §6.1 and the web plan's gap 3 place the work on state shared across replicas with package 6; PLATFORM §14 lists it as later work. **Proposal:** not in package 6: its own state (starts, reads and their throttle, the relay's count, accesses, stops, alerts, settlement leases) is in the database and holds across replicas; runner connections, relayed streams, event streams and a conversation stream become a package of their own. *Reviewed: agree.*
20. **`ConversationReads` and `ConversationCommands`.** The package 3 contract offered them to package 6; package 3c was built without them. **Proposal:** package 6 adds them over the built services, records the Owner's reads in them, and moves the REST controllers onto them, so that the app and the endpoint cannot drift; the endpoint reaches the conversation module through nothing else. *Reviewed: agree.*
21. **Who is told when a member leaves, and when it is over.** Part 8 says what becomes of things, not who is told. **Proposal:** admins by a `DEPARTURE` item until the checklist is closed; each paused trigger's team; nobody else; closing needs the paused triggers, the emptied teams and the accounts they connected resolved, or `force`. *Reviewed: agree.*
22. **The legal hold's scope.** "Keep" is company-wide and cannot stop one erasure. **Proposal:** a hold per conversation and per principal, set and lifted by OWNER (`MANAGE_RETENTION`) with a reason that stays on its row, audited `hold.set` and `hold.lifted` with ids. A principal's hold covers their personal conversations and what they said, added and decided in triggers' conversations; a conversation's covers all of it, run events included. The sweep skips what a hold covers and erasure refuses it (`409 ON_HOLD`); a hold brings back nothing a sweep removed. The person is not shown the hold: it is the company's record-keeping, and telling may defeat it. Later: holds by trigger, system, outside identity or date range. *New after review.*
23. **A person's copy: subject access and portability.** The contract covered erasure and nothing for a request to see one's data: a member who rejoined cannot read their old conversations (package 1's §10) while the Owner can. **Proposal:** in package 6, `GET /principals/{principalId}/export` by OWNER (`MANAGE_RETENTION`): a ZIP of one person's content (their personal conversations with messages, decisions, outputs and files; what they said, added and decided in triggers' conversations), audited `principal.exported` and recorded as the Owner's reads (kind `EXPORT`), which the Owner hands to the person. A member's own export of their current conversations later. The alternative: later, with the Owner producing it by hand from the app until then. *New after review.*
24. **A suspended member's reads in the app.** The reviewer leaves it to the owner. **Proposal:** they stop too. While suspended, the member sees only the company's page, with the suspension (who and when), and every other company path answers `403 PRINCIPAL_SUSPENDED`, so that a stolen session reads nothing: the inbox, tool results and transcripts are what an attacker wants. The alternative keeps their reads in the app, so that the person sees their own work while an admin looks into it; a stolen session then keeps reading until an instance admin deactivates the user. *New after review.*
25. **Open conversations under retention.** Packages 4 and 5 end nothing on idleness, so an open chat thread, tracker conversation or app conversation keeps its content whatever retention says (G12). **Proposal:** the sweep reports them: an OWNER item "{n} open conversations older than your retention; end them to let retention apply", and the retention page lists them by key, place and age (no title) with an end per conversation or for all; erasure reaches open ones when a person asks (open point 17). The alternative: an OWNER-set idle end per place, chats first, which would end people's conversations without them. *New after review.*

## 11. Decided on review

Decided by the owner on 2026-10-12, point by point. Where a decision differs from the proposal, the text below replaces the contract's earlier text on that point.

- **Adopted as proposed** (in the form the revision after review gave them): open points 1 to 8, 10 to 22, 24 and 25. Among them: instance admins under break-glass get ADMIN's view and the right to appoint an OWNER, never personal conversations (12); members can be suspended, with every write, decision, token and read stopped and running turns cancelled (5, 24); a paused system pauses its triggers, its resume offering to resume them too (6); the company's stop re-arms triggers at the lift as a stated exception to package 4's decision 9, webhooks answering `503` meanwhile (7); team approvals are not decided through an AI client (15); retention defaults with a legal hold per conversation and per person (16, 22); erasure reaches open conversations and transcripts and is refused under a hold (17).
- **Open point 9, decided otherwise: every single read is recorded.** No throttle: each read request of a personal conversation by an OWNER who is not its person writes one `conversation.read` (with its kind), in a transaction of its own; a live stream counts as one read when it opens; every download and export as before. This keeps security review row 18 as written ("every read is audited") instead of amending it; the cost is a larger audit chain, which retention of `conversation_read` and the audit export handle.
- **Open point 23, decided otherwise: a person's export comes later**, not in package 6. Subject-access requests are handled by hand until then; the OWNER's reads through the app stay audited as above.
