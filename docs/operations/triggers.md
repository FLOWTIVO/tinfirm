# Triggers

The public guide is [Write a trigger](../../docs-site/content/user-guide/triggers.md) (https://docs.tinfirm.com/user-guide/triggers/), for the members who write triggers and the teams that look after them. This page keeps what the people who build Tinfirm need beyond it: the design and its rules, the API's values, endpoints and error codes, how a tracker is polled, the order of a firing's checks, what the agent is told, the write-back's internals, the status codes, the audit, and the diagnosis that relies on them.

The design is the [package 4 contract](../reviews/2026-10-12-package-4-contract.md) with its [§11, decided on review](../reviews/2026-10-12-package-4-contract.md#11-decided-on-review), and for channels and webhooks the [package 5 contract](../reviews/2026-10-12-package-5-contract.md). The binding rules are [CONVENTIONS §15.15](../CONVENTIONS.md#1515-package-4-part-a-the-trigger-core) and [CONVENTIONS §15.17](../CONVENTIONS.md#1517-package-5b-channel-triggers-the-chat-outbox-and-the-webhook). Rules and their condition groups come from [ADR-0043](../adr/0043-trigger-rules.md), schedules from [ADR-0032](../adr/0032-routines.md), and the credentials a service account borrows from [ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md). 

## 1. How a trigger works

| "It starts when" | `source` | Its conversations' place |
|---|---|---|
| Something happens on an issue | `TRACKER` | `TRACKER_ISSUE` |
| On a schedule | `SCHEDULE` | `SCHEDULE`, one per firing |
| Someone mentions it in a channel | `CHANNEL` | `CHAT_THREAD` |
| A webhook is delivered | `WEBHOOK` | `WEBHOOK` |

## 2. Create a trigger

- **Create**: `POST /triggers` (the web app's `triggers/new`). The caller becomes the author; one who may not use the agent gets `403 AGENT_NOT_USABLE`.
- **The preview**: the editor calls `POST /triggers/preview`, which stores nothing, after every change (section 9).
- **Armed.** A trigger only reacts to changes made after it was armed (`armed_at`): its creation, each resume, a reassignment, and the lift of a company's stop. Its name must be unique among the company's live triggers, ignoring case (`409 DUPLICATE_TRIGGER_NAME`).
- **Change**: the web app's `triggers/{id}/edit` sends what changed: the definition (`PATCH /triggers/{id}`), the account table and payer (`PUT /triggers/{id}/accounts`), the settings (`PUT /triggers/{id}/settings`). Each is checked whole, for the author, as a create is.

## 3. Sources

### 3.1 Something happens on an issue (`TRACKER`)

The trigger reads the tracker with its source account (section 5): it sees what that account sees, and no more.

**Events.** In the API, in the editor's order: `ASSIGNED`, `MENTIONED`, `COMMENTED`, `CREATED`, `UPDATED`, `STATUS_ENTERED`, `LABEL_ADDED`, `LABEL_REMOVED`, `CHANGE_REQUEST_OPENED`, `CHANGE_REQUEST_UPDATED`, `CLOSED`. `STATUS_ENTERED`, `LABEL_ADDED` and `LABEL_REMOVED` take a value (the status, the label).

**On** is `ANY`, `ISSUE` or `CHANGE_REQUEST`.

**Conditions** (ADR-0043). The label rows are `labels` and `notLabels`; **Made by** is `authors`, `PEOPLE` or `TINFIRM` (a subject a trigger's conversation created). A comment that raises an event is always a person's. **Type is one of** and **Type is none of** are offered for Jira in the editor. The server refuses a rule that could never fire (`400 INVALID_TRIGGER`, with the reason): a status every group excludes or does not allow, a label added that every group excludes, a label removed that every group requires, a comment event when every group wants *Tinfirm*, a name in a row and its negative within one group, and an empty group among others.

**How often** is every time, or `ONCE` (*Once per issue*).

**How it is read.** Tinfirm polls; it takes no webhooks from trackers.

- One poll per system and account serves every trigger whose source row names that account or credential. A `worker` replica looks for due polls every `TINFIRM_TRIGGER_POLL_TICK` (15 seconds), and each poll is due at its system's `pollIntervalSeconds` (60 seconds unless an admin sets 30 to 3600 on the system). With less than a tenth of the system's rate limit left, the interval doubles until the reset.
- A subject changed within the last `TINFIRM_TRIGGER_SETTLE` (90 seconds) is left for the next pass. The window reads two minutes back, so a change the system lists late is still read.
- A change older than `TINFIRM_TRIGGER_CATCH_UP` (24 hours) when first read records the subject and fires nothing. The trigger counts it: "changes read too late".
- Tinfirm's own writes raise nothing. A comment, a status, a label, a close or a pushed commit that a trigger's conversation wrote is recorded (`system_write`) and is no event. A comment by the trigger's account that Tinfirm did not write is a person's.
- The first time a poll reads a subject, only what needs no earlier read fires: the subject's creation (with its labels, a change request's opening, and an assignment it was created with) when it was created after the poll began, and comments written since.
- A `401` marks the account to sign in again and pauses the triggers that use it (`ACCOUNT_BROKEN`, section 10). A scope that answers `403` or `404` makes its triggers wait `SCOPE_UNREADABLE` while the other scopes are read. A rate limit backs off until its reset, at most an hour. Errors and timeouts back off 1, 2, 4… minutes up to 30, and after an hour of failures the triggers wait `SYSTEM_UNREACHABLE`. The window holds meanwhile.

Admins see a system's polls on its page (`GET /systems/{id}/polls`, OWNER and ADMIN). Base URLs, token permissions and the poll interval are in [systems.md](systems.md).

### 3.2 On a schedule (`SCHEDULE`)

- *While the last one is busy* is `SKIP` (the default) or `CREATE`. Busy means the previous firing's conversation has an active turn, an open decision or a waiting account.
- *Missed firings* is `NONE` (the default) or `ONE`. A firing is missed when it was due longer ago than `TINFIRM_TRIGGER_MISSED_AFTER` (one hour).
- The previous firing's idle conversation ends `SUPERSEDED`.
- A company has at most 50 live schedules (`409 TOO_MANY_TRIGGERS`).

### 3.3 Someone mentions it in a channel (`CHANNEL`)

- A channel has one default trigger, and an agent one trigger per channel (`409 CHANNEL_TAKEN`).
- The author must be linked on the chat system and a member of each channel (`400 CHANNEL_NOT_JOINED`). The system must have a bot (`409 BOT_NOT_SET`), and each channel must be one the bot is in, not archived, deleted or shared with another organisation (`400 CHANNEL_UNAVAILABLE`). Private channels show only to their members; an admin editing for the author sees the author's.
- Approvals in the thread are decided only by a linked, active member who may decide them.
- A channel trigger needs no row on its chat system; on Slack it may have one, for `history`. The chat's bot is never a row (`400 BOT_ACCOUNT`), nor a chat link alone (`400 ACCOUNT_NOT_ENTITLED`).
- When the bot cannot be used, the trigger waits, and goes on by itself once the bot works again (section 10.2).

The bot, linking chat users and the thread's cards are in [chats.md](chats.md).

### 3.4 A webhook is delivered (`WEBHOOK`)

An admin registers a webhook system with its secret and its mapping: JSON Pointers that read a delivery's event, subject key and title, actor and text ([systems.md](systems.md)).

- **Its events**: at most five through the API, each up to 200 characters.
- **Only when the body**: up to 5 conditions, a JSON Pointer (RFC 6901) and `EQUALS` (one value), `IN` (1 to 20 values), `PRESENT` or `ABSENT`. A pointer that is not RFC 6901 is `400 MAPPING_INVALID`.
- The actor a delivery names is as the sender says, unverified, and never a member.
- Without a mapped subject key, each delivery starts a conversation, and the trigger's previous idle one without a key ends `SUPERSEDED`.
- Echoes are recognised by the subject key (section 7.2); the preview asks for `subject.key` on the system when the mapping has none.
- Run now is refused (`400 VALIDATION_FAILED`): a delivery fires it.

The system's deliveries are `FIRED`, `NO_TRIGGER` or `MAPPING_FAILED`. "Try it with a sample delivery" is `POST /systems/{id}/webhook/map`, which stores nothing and names the first event or condition a trigger's match fails. A worked example with Alertmanager is [alerts.md](alerts.md).

## 4. Author, team and payer

### 4.1 Who may do what

- A member of the trigger's team who tries to edit it gets `403 TRIGGER_NOT_EDITABLE`. Anyone outside its audience gets `404` on every path, its firings and conversations included. An instance admin acting in the company has OWNER's and ADMIN's rights.
- `GET /triggers` answers the triggers the caller authored and those of their teams, and every trigger to OWNER and ADMIN.
- **An admin's edit** is checked for the author, never for the admin: `GET /triggers/choices?forPrincipalId=` answers the author's choices. The audit names the admin as the editor (`editorPrincipalId`).

### 4.2 The team

- Approvals are a tool call that asks, a request for access, a change request under `ASK`, and a result's review. They are decided in Tinfirm or by a chat card's button, never by a comment, and not from an AI client (`DECIDE_IN_APP`).
- Changing the team moves the open approvals to the new one (`decision.approver_changed`).

### 4.3 The payer

A payer the author may not use is `400 MODEL_ACCESS_NOT_ENTITLED`. The company's switch for subscriptions is `triggerSubscriptions` of `PUT /settings`, off by default ([ADR-0085](../adr/0085-claude-login-on-the-runner-host.md)). A payer that cannot pay is refused at the save (`409 MODEL_ACCESS_UNAVAILABLE`, with the reason), and a trigger no runner host may run is `409 CANNOT_RUN_ANYWHERE`.

### 4.4 Reassigning and a departed author

When an author leaves the company, their triggers pause `AUTHOR_LEFT` in the same transaction: their conversations' turns are cancelled, and the conversations stay open and take no new turn. Their own accounts go, so the rows that named them lose their account.

`POST /triggers/{id}/reassign` (OWNER and ADMIN only) is allowed only for a trigger paused `AUTHOR_LEFT` or `NOT_ENTITLED`, or one reassigned and not accepted yet (`TO_ACCEPT`); a live trigger is `409 TRIGGER_PAUSED` (the security review of 2026-10-27, H2). It names the new author, a member who holds a seat (a Viewer is `400 VALIDATION_FAILED`); a member who may not use the agent is refused ("This member may not use the trigger's agent"). `accounts` and `payerPrincipalId` may be left out to keep the credentials of service accounts the new author may use. The admin names no row of the new author's own accounts and adds none of their own MCP servers (`403 AUTHOR_ACCOUNT_NOT_ADMINS`): the new author adds those.

The reassigned trigger is paused `TO_ACCEPT`, its open events' acknowledgement cleared, and the new author's inbox has the `TRIGGER` item `TRIGGER_REASSIGNED_TO_YOU`. Nobody resumes it (`409 TRIGGER_PAUSED`, `details.reason` `TO_ACCEPT`): the new author accepts it with `POST /triggers/{id}/accept` (`{version, acknowledgeOpenEvents}`), which checks its table and payer for them, takes their acknowledgement where the open events would write without approval, and resumes it through the gate; an admin's accept is `403 TRIGGER_NOT_EDITABLE`. At the resume, each waiting conversation takes the trigger's new table and payer, audited as `conversation.rebound`; what happened before the resume fires nothing.

`GET /triggers/choices?forPrincipalId=` answers another member's choices only to OWNER and ADMIN and only with `triggerId`: for that trigger's author (their own accounts only where the trigger's rows name them), or for a new author of a trigger that may be reassigned.

A member made a Viewer, by an admin, the identity provider's role claim or SCIM, authors no trigger and uses no service account (the security review of 2026-10-27, M7): the gate pauses their triggers `NOT_ENTITLED` after the role changes, and at every firing. An admin may reassign them.

## 5. The accounts table: Acts as and pays

### 5.1 One choice fills the table

| Row | Rule |
|---|---|
| The source tracker | Required, with an identity (`400 SOURCE_ACCOUNT_REQUIRED`) |
| Each system of the agent | Optional. Without a row, its tools are refused (`TOOL_DENIED`) and its repositories stay out of the workspace |
| An MCP server that needs no account | No row ([ADR-0071](../adr/0071-mcp-servers-without-accounts.md)) |
| The author's own MCP servers | Only while every row holds the author's own account (`400 SETTINGS_WIDEN`) |

A row holds either the author's own account on the system ([accounts.md](accounts.md)), or a service account the author may use together with the credential lent to it on that system (`holderPrincipalId` in the API). Nothing else: not another member's account (`400 ACCOUNT_NOT_ENTITLED`), not a group's grant, which serves personal conversations only, and not a chat bot.

### 5.2 A replaced or withdrawn lending

A trigger follows its service account ([ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md), migration V050). When an admin lends the service account another credential on a system in place of the one it borrowed, in the same transaction:

- each live trigger whose row acted through it names the new credential (`trigger.accounts_changed` with `why` `lending.replaced`), and its poll follows;
- its open conversations bind the new credential at their next check (`conversation.binding.bound` with the credential it `followed`), and the conversation says so;
- its author is told in their inbox, as of an admin's change on their behalf, unless they lent it themselves. The lend dialog says how many triggers switch.

A trigger does not follow, and pauses as for a withdrawn credential, when the new credential is a chat system's bot, or when it would be the source row of a tracker trigger and has no identity.

A credential withdrawn from the service account pauses the trigger `NOT_ENTITLED` until its author names another. A disabled credential, or one that needs signing in again, pauses it `ACCOUNT_BROKEN`. Rotating a token or signing a credential in again keeps its id and changes no trigger. A service account that a live trigger's row or payer names cannot be deleted (`409 SERVICE_IDENTITY_IN_USE`).

### 5.3 Anyone can drive this agent: the open-writes acknowledgement

The preview names who can drive the trigger (`openToAnyone`). While a write tool still runs without asking, with the author's own account or a credential lent to a service account alike, the save needs the author's acknowledgement (`acknowledgeOpenEvents`; `400 OPEN_EVENTS_NOT_ACKNOWLEDGED` without it; the security review of 2026-10-27, D2). It is stored with the trigger and audited (`trigger.open_events_acknowledged`); a later change needs no new one, and a reassignment clears it for the new author to give. A row lent read-only writes nothing and needs none.

A trigger on any source but a schedule whose row acts with a credential lent to a service account exposes that credential to whoever may write at its source. The first time a save names it (a create, a change of the table, a reassignment, a lending the trigger follows), Tinfirm records it (`trigger_credential_exposure`, V077) and tells whoever lent the credential to the service account in their inbox, for 30 days (`TRIGGER` item `CREDENTIAL_EXPOSED_TO_OPEN_SOURCE`); when the lending's creator was not recorded or is no member any more, OWNER and ADMIN are told. The credential's page (`GET /systems/{systemId}/credentials`, `openTriggers`) and the service account's (`GET /service-identities/{id}/credentials`, `openTriggers`) list the live open triggers that act with it. No setting of a lending allows or forbids it.

## 6. The agent and its settings

**The version.** A new version that may do more waits until the author accepts it (`POST /triggers/{id}/accept`). A deleted agent deletes its triggers; their conversations end `AGENT_DELETED`.

**Narrowed for its runs** is `GET` and `PUT /triggers/{id}/settings`.

| Refused | When |
|---|---|
| `400 MODEL_NOT_ALLOWED` | A model the payer's key does not allow |
| `400 MODEL_REQUIRED` | A budget pays and no model is set |
| `400 VALIDATION_FAILED` | Instructions in a trigger's settings: the trigger's instruction is in every turn |
| `400 SETTINGS_WIDEN` | A widening |

Model access by subscription follows [ADR-0085](../adr/0085-claude-login-on-the-runner-host.md), automatic retries [ADR-0073](../adr/0073-automatic-retries.md). What each tool mode means is in [agent-permissions.md](agent-permissions.md).

**Memory.** The API is `GET /triggers/{id}/memory`, `POST …/memory/{noteNumber}/accept`, `DELETE …/memory/{noteNumber}` and `DELETE …/memory`. The rest is [agent-memory.md](agent-memory.md).

## 7. Firing

A firing goes through these steps, in this order:

1. **The gate.** A paused trigger, or one that waits, fires nothing and records nothing (section 10).
2. **The agent.** A paused agent fires nothing; a schedule records `SKIPPED_AGENT_PAUSED`.
3. **The rate** (7.1).
4. **The chain** (7.2).
5. **The conversation**: the trigger's open conversation on the subject takes the firing (`CONTINUED`), else a new one starts (`STARTED`).
6. **Once per issue**: a trigger set to *Once per issue* that already fired on the subject fires no more there.

Each recorded firing is audited as `trigger.fired`.

### 7.1 The rate limit

Each trigger fires at most `maxFiringsPerHour` times in any hour; started and continued conversations count. Past it, events are skipped (`SKIPPED_RATE`, recorded once an hour, the count kept in "skipped over it").

A trigger's limit is at most the company's maximum (`400 TRIGGER_LIMIT_TOO_HIGH`). The company's default and maximum are seeded from `TINFIRM_TRIGGER_FIRINGS_PER_HOUR_DEFAULT` (30) and `TINFIRM_TRIGGER_FIRINGS_PER_HOUR_MAX` (300), until OWNER or ADMIN set their own with `PUT /settings` and `triggerFiringsPerHour: {default, max}` (a default of at least 1, at most the maximum, itself at most 10 000). The web app has no field for them yet. Lowering the maximum lowers every trigger above it, each audited as `trigger.changed` with `cause` `company_settings.changed`. The settings carry a `version`: a change names the version it read, and one made by someone else meanwhile is refused (`409 SETTINGS_CHANGED`) rather than written over; the web app then reads the settings again and asks for the change once more.

### 7.2 Echoes and chains

An agent's own work must not set off the next turn without end.

- **On a tracker**, Tinfirm's own writes raise no event (section 3.1). A subject that a trigger's conversation created (an issue or a change request) carries that conversation's depth plus one when a poll first reads it. Past a depth of 3, it fires nothing (`SKIPPED_CHAIN`), and a `SYSTEM` message in the conversation that created it says that it started no trigger.
- **On a webhook**, a delivery about a subject key that a conversation of the trigger wrote to within `tinfirm.trigger.settle` (`TINFIRM_TRIGGER_SETTLE`, 90 seconds) is an echo. It fires one deeper than that conversation's deepest firing, and past 3 it is `SKIPPED_CHAIN`, with a `SYSTEM` message in the writing conversation. Without `subject.key` in the mapping, echoes cannot be recognised, and only the rate and the payer's budget bound such a loop.

### 7.3 Continuing a conversation

- **A firing on an open conversation** (`CONTINUED`) starts an `EVENT` turn when no turn runs and no decision is open; else the next turn takes it.
- **On an issue**, every new comment is stored in the trigger's open conversations on the subject and read by the next turn. In Tinfirm such a conversation takes answers to open questions only: a plain message is `409 PLACE_IS_EXTERNAL`.
- **In a channel**, likewise: in Tinfirm, a plain message is `409 PLACE_IS_EXTERNAL`.

### 7.4 When a conversation ends

| Reason | When |
|---|---|
| `SUBJECT_CLOSED` | The issue closed: conversations the close did not fire end at once, one the close fired once its turn ended. A result still waiting to be written is written |
| `SUPERSEDED` | A schedule's next firing, or a webhook's next delivery without a subject key, ended the previous idle conversation |
| `TRIGGER_DELETED` | The trigger was deleted: undelivered write-back is dropped |
| `AGENT_DELETED` | The agent was deleted, and its triggers with it |
| `USER` | Its author, its team or an admin ended it |

### 7.5 What the agent is told

Every turn carries the trigger's instruction under `# Instruction`. The first turn of a tracker conversation also gets the subject (type, status, labels, assignees and description, cut to 16 KiB) and the firing that started it ("assigned to … by …", a comment quoted); a channel's, the mention. A webhook's first turn gets the delivery under `# Event`: the system, the mapped event, subject, actor and text, and the whole body, cut to 16 KiB (`WebhookFirings.summary`). A delivery that continues an open conversation adds only the mapped event, subject, sender and text, never the body (`WebhookFirings.external` and `request`). What people wrote is marked as their words, never as the instruction.

## 8. Results

### 8.1 On a tracker: the write-back

Results reach the issue through an outbox, written with the conversation's account on the source system, in order per conversation. The actions are `TRANSITION` (Jira), `ATTACH_DOCUMENTS` (Jira), `LABEL_ADD` and `LABEL_REMOVE` (GitHub, GitLab) and `CLOSE` (GitHub, GitLab), up to 10 for success and 10 for failure.

A write that fails for a passing reason (no answer, a `5xx`, a timeout, a rate limit) is tried again after 1, 2, 4… minutes up to an hour, at most 10 attempts; a repeated attempt first looks whether the earlier one arrived, so nothing is written twice. Any other failure, and the last attempt, drops the write with the reason and posts a `SYSTEM` message in the conversation. A `401` pauses the trigger `ACCOUNT_BROKEN` and holds its writes. A write waiting on an account waits up to 7 days. Every write is audited as `source.written`, never with its body.

### 8.2 Results that wait for review

Returning a result starts a `REVIEW_RETURNED` turn with the note. A failure and a blocked result are not reviewed.

### 8.3 In a channel: replies in the thread

The chat's bot posts each answer, card and document in the conversation's thread, through the same outbox. A channel gone drops the post and marks the trigger's channel unavailable. See [chats.md](chats.md).

## 9. Run now, preview, accept

**Run now** is `POST /triggers/{id}/run`: `{subjectKey}` for a tracker, `{}` for a schedule. A tracker's issue search is `GET /triggers/{id}/subjects?query=` (at most 20, with the trigger's account in its scope); a run now has depth 0. A schedule's run now applies its overlap and not its catch-up. Channel and webhook triggers answer `400 VALIDATION_FAILED`.

The gate and the rate apply: a run over the rate is recorded `SKIPPED_RATE`, as a firing would be. Refusals:

| Code | Means |
|---|---|
| `409 TRIGGER_PAUSED` | The trigger is paused or waits (the company's runs stopped, its system paused), its agent is paused, or it did not fire (a rate skip already recorded this hour); `details.reason` says which |
| `409 SUBJECT_NOT_VISIBLE` | The trigger's account cannot read the issue, or it does not exist, or the account cannot be used now |
| `409 TRIGGER_CONDITIONS_FAIL` | The issue is outside the trigger's scope, subject kind or conditions; `details.failing` names the clauses |

A run now is audited as `trigger.run` and `trigger.fired`.

**Preview.** `POST /triggers/preview` takes `{triggerId?, draft}` and answers every check a save makes, storing and auditing nothing: per system the account, its holder, identity and status, and the systems left unavailable; the payer, its model access and budget, or why it waits or is refused; whether a runner host can run it; the sentence; `openToAnyone`; a schedule's next three firings; `warnings` (*Mind that* in the editor); and for a change, how many open conversations keep their accounts. A missing acknowledgement is answered in words (`openToAnyone.acknowledgementMissing`), not refused; the save still refuses it.

**Accept.** `POST /triggers/{id}/accept` with `{version}` accepts the agent's new version, up to the current one (section 6).

## 10. Status and health

A trigger is `ACTIVE` or `PAUSED`. An active trigger may also wait.

### 10.1 Paused

A paused trigger's conversations take no new turn and its write-back is held. **Resume** (`POST /triggers/{id}/resume`, its author or an admin) runs the gate first; a trigger that still fails stays paused (`409 TRIGGER_PAUSED` with the reason).

| Reason | In the app | Set when |
|---|---|---|
| `USER` | Paused by hand | Its author, its team or an admin paused it |
| `AUTHOR_LEFT` | Its author left the company | Section 4.4 |
| `NOT_ENTITLED` | Its author may no longer use an account, the agent or the payer | Its author is suspended, the agent is no longer shared with them, a service account of a row or the payer is no longer theirs to use, the credential was withdrawn from the service account, the subscription that paid is gone, or the payer's key no longer allows the model |
| `ACCOUNT_REMOVED` | An account it acts with was disconnected | The account went, is another identity now, or its holder left |
| `SYSTEM_REMOVED` | A system it used was removed | A system on which it had an account row, not its source, was removed; the row went with it |
| `ACCOUNT_BROKEN` | An account it acts with needs signing in again | The author's own account needs signing in again, a credential needs an admin to sign it in again or enable it, or a row's holder is suspended |
| `SYSTEM_PAUSED` | An admin paused its system | Its source system, or a system of its table, is paused. The system's resume with `resumeTriggers` (the default) resumes it through the gate |

A removed source system deletes the trigger instead (`trigger.deleted` with `cause` `system.deleted`).

### 10.2 Waiting

| Reason | In the app | Set when |
|---|---|---|
| `SUSPENDED` | Waits: an account's holder or the payer is suspended | The payer is suspended (a suspended holder pauses it `ACCOUNT_BROKEN`) |
| `GATEWAY_NOT_CONFIGURED` | Waits for an admin to set up model access | The company's model gateway is not set up |
| `COMPANY_STOPPED` | Waits: an admin stopped the company's runs | The company's runs are stopped; the lift arms it again |
| `SYSTEM_UNREACHABLE` | Waits: its system does not answer | A tracker's poll failed for an hour |
| `SCOPE_UNREADABLE` | Waits: its account cannot read the scope | A tracker's scope answered `403` or `404` |
| `BOT_MISSING` | Waits: its chat system has no bot | A channel trigger's system has no bot |
| `BOT_RECONNECT` | Waits for an admin to sign the chat's bot in again | The bot's credential needs signing in again |
| `BOT_SUSPENDED` | Waits: the chat bot's service account is suspended | The bot's service account is suspended |
| `CHANNEL_UNAVAILABLE` | Waits: the bot is in none of its channels | Every channel of it is unavailable |

`RECONNECT_YOURS` and `RECONNECT_COMPANY` remain in the API's list, but an account that needs signing in again now pauses the trigger `ACCOUNT_BROKEN` (package 4 contract §11, point 9). Each wait is audited once, as `trigger.waiting`.

### 10.3 Firings

`GET /triggers/{id}/firings`, newest first. The outcomes are `STARTED`, `CONTINUED`, `SKIPPED_OPEN`, `SKIPPED_MISSED`, `SKIPPED_CHAIN`, `SKIPPED_RATE`, `SKIPPED_AGENT_PAUSED` and `FAILED`; the public guide gives each one's words in the app. An event that the gate stops, or that the rule leaves out, records no firing.

### 10.4 Kill switches

| Switch | API | Internals |
|---|---|---|
| Pause a trigger | `POST /triggers/{id}/pause` with `stopRunning` | `PAUSED` `USER` |
| Delete a trigger | `DELETE /triggers/{id}` | Its open conversations end `TRIGGER_DELETED` and their undelivered write-back is dropped |
| Pause the agent | | Polls go on, so nothing is caught up after the resume |
| Pause a system | `POST /systems/{id}/pause` | Triggers on it, or with a row on it, pause `SYSTEM_PAUSED`. Its polls call nothing; a webhook system answers `200 {"paused": true}` and stores nothing. The resume moves the polls past the pause, so what changed meanwhile fires nothing; `resumeTriggers` is on by default |
| Stop the company's runs | `POST /runs-stop`; `DELETE /runs-stop` lifts it | Active triggers wait `COMPANY_STOPPED`. Polls go on and store comments in open conversations, but nothing fires or is recorded; a schedule due meanwhile moves to its next firing; webhooks answer `503` with `Retry-After`; a mention is never answered later. The lift arms every active trigger and sends the held write-back |
| Suspend a principal | | A suspended author pauses `NOT_ENTITLED`, a suspended holder `ACCOUNT_BROKEN`; a suspended payer makes it wait `SUSPENDED` |

## 11. Limits

| What | Limit | Refused as |
|---|---|---|
| Live triggers on one system, whatever their source (tracker, chat, webhook) | 100 | `409 TOO_MANY_TRIGGERS` |
| Live schedules in a company | 50 | `409 TOO_MANY_TRIGGERS` |
| Name | 1 to 100 characters, unique among the company's live triggers ignoring case | `400 VALIDATION_FAILED`, `409 DUPLICATE_TRIGGER_NAME` |
| Instruction | 1 to 20 000 characters | `400 VALIDATION_FAILED` |
| Events of a tracker trigger | 1 to 5, a value up to 255 characters | `400 INVALID_TRIGGER` |
| Condition groups | 5, each row up to 10 names of up to 255 characters | `400 INVALID_TRIGGER` |
| Scope | 140 characters | `400 INVALID_TRIGGER` |
| Actions | 10 on success and 10 on failure | `400 INVALID_TRIGGER` |
| Account table | 100 rows, one per system | `400 VALIDATION_FAILED` |
| Channels | 1 to 20 | `400 VALIDATION_FAILED` |
| Webhook events, conditions | 5 events of up to 200 characters; 5 conditions, a pointer up to 500 characters, `IN` 1 to 20 values | `400 INVALID_TRIGGER`, `400 MAPPING_INVALID` |
| Schedule | A cron of up to 100 characters, at most one firing every 15 minutes; a zone of up to 64 characters | `400 INVALID_TRIGGER` |
| Firings per hour | 1 to the company's maximum; the company's default when left empty | `400 TRIGGER_LIMIT_TOO_HIGH` |
| Chain of triggered conversations | 3 deep | `SKIPPED_CHAIN` |
| Write-back | 10 attempts; 7 days waiting for an account | Dropped |

A request beyond the API's own bounds (the lengths and counts `api/openapi.yaml` states for `TriggerCreateRequest`) is `400 VALIDATION_FAILED` before these checks run.

## 12. The API

Under `/api/companies/{companyId}`, tag *Triggers* of [`api/openapi.yaml`](../../api/openapi.yaml). "Its audience" is its author, its team, OWNER and ADMIN; anyone else gets `404`.

| Method and path | Who | What |
|---|---|---|
| `GET /triggers` | Every member | The triggers the caller sees, by name; filters `systemId`, `agentId`, `authorId`, `teamGroupId`, `status`, `accountHolderPrincipalId` |
| `POST /triggers` | Who may use the agent | Create: the caller becomes its author; `201` |
| `GET /triggers/choices` | Who may use the agent | The one choice for `agentId` and `systemId`; `forPrincipalId` with `triggerId` (OWNER, ADMIN) answers that trigger's author's, or a reassignment's new author's |
| `POST /triggers/preview` | Who may use the agent | Every check of a save for `{triggerId?, draft}`, storing nothing |
| `GET /triggers/{triggerId}` | Its audience | The trigger, its table with each row's status, payer, version, health, counts and the caller's `rights` |
| `PATCH /triggers/{triggerId}` | Its author, admins | The definition; absent fields stay |
| `DELETE /triggers/{triggerId}` | Its author, admins | A soft delete; `204` |
| `PUT /triggers/{triggerId}/accounts` | Its author, admins | The table and the payer, as a whole |
| `GET`, `PUT /triggers/{triggerId}/settings` | Its audience reads; its author and admins save | Its settings of the agent |
| `POST /triggers/{triggerId}/accept` | Its author | `{version, acknowledgeOpenEvents?}`: accept the agent's new version; for a trigger reassigned to the caller, its table, payer and open events, which resumes it |
| `GET`, `DELETE /triggers/{triggerId}/memory`; `DELETE …/memory/{noteNumber}`; `POST …/memory/{noteNumber}/accept` | Its audience | Its memory and proposals |
| `POST /triggers/{triggerId}/pause` | Its audience | `{stopRunning}`; idempotent |
| `POST /triggers/{triggerId}/resume` | Its author, admins | The gate, then `ACTIVE`; never a trigger paused `TO_ACCEPT` |
| `POST /triggers/{triggerId}/run` | Its audience | `{subjectKey}` for a tracker, `{}` for a schedule; answers the firing |
| `GET /triggers/{triggerId}/subjects` | Its audience | Up to 20 subjects in a tracker trigger's scope, `query` optional |
| `POST /triggers/{triggerId}/reassign` | OWNER, ADMIN | `{authorPrincipalId, accounts?, payerPrincipalId?}`, only after the author's loss; paused `TO_ACCEPT` |
| `GET /triggers/{triggerId}/firings` | Its audience | Its firings, newest first, paged |
| `GET /systems/{systemId}/polls` | OWNER, ADMIN | A tracker's polls |
| `GET`, `PUT /settings` | Every member reads; OWNER and ADMIN change | `triggerFiringsPerHour`, `webhookDeliveriesPerMinute` and `triggerSubscriptions`, with the `version` a change must name |
| `GET /systems/{systemId}/chat/channels` | Every member; `authorPrincipalId` for admins | The channels a chat's bot is in, for a channel trigger |
| `POST /systems/{systemId}/webhook/map` | OWNER, ADMIN | What a body maps to, and which webhook triggers it would fire |

## 13. For operators: configuration

The variables, with their defaults, are on the public [Server configuration](../../docs-site/content/reference/configuration.md) page. Beyond it: `TINFIRM_TRIGGER_SETTLE` is also a webhook's echo window (section 7.2), and polls, schedules and the write-back run on replicas with the `worker` role. The full list is [CONVENTIONS §4](../CONVENTIONS.md#configuration).

## 14. From an AI client

The tools are listed on the public [MCP tools](../../docs-site/content/reference/mcp-tools.md) page and described in [CONVENTIONS §15.20](../CONVENTIONS.md#1520-the-mcp-endpoint-on-triggers-systems-webhooks-and-spend). They read triggers with the same rules as the web app: a trigger the caller is neither the author of nor in the team of is "not found", unless they are an owner or admin. No tool creates, edits, pauses or runs a trigger ([mcp-clients.md](mcp-clients.md)).

## 15. The audit

In the company's chain. Instructions, comments, bodies and answers stay out. The actor is the person for their changes and `SYSTEM` for polls, firings, schedules and deliveries.

| Action | When, and its details |
|---|---|
| `trigger.created` | A create: source, system, agent, events, subject, scope, repeat, schedule, team, results |
| `trigger.changed` | A change of the definition: the fields and `editorPrincipalId`; also each trigger lowered by a lower company maximum (`cause` `company_settings.changed`) |
| `trigger.deleted` | A delete: `cause` `USER`, `agent.deleted` or `system.deleted` |
| `trigger.accounts_changed` | The whole table and the payer with its model access, after a change: `why` `created`, `changed`, `reassigned` or `lending.replaced` |
| `trigger.settings_changed` | Its settings, as `settings.changed`, with `editorPrincipalId` |
| `trigger.accepted` | A new version accepted: the version and the previous one |
| `trigger.open_events_acknowledged` | The acknowledgement: the events, the `ALLOW` write tools and the accounts it named |
| `trigger.paused` | The reason, `stopRunning` and the runs cancelled; a kill switch's `cause` |
| `trigger.resumed` | The reason it was paused for (`from`), who resumed it, the conversations rebound; a kill switch's `cause` |
| `trigger.waiting` | Once per wait, with the reason |
| `trigger.reassigned` | The former and the new author and payer |
| `trigger.fired` | A recorded firing: subject, event, outcome, conversation, chain depth, whether run now |
| `trigger.run` | A run now: the subject, the outcome, the firing |
| `conversation.binding.bound` | A conversation's account bound; with `followed` when it followed a replaced lending |
| `conversation.rebound` | A reassigned trigger's waiting conversation took the new table and payer |
| `decision.approver_changed` | Open approvals moved to a new team, or to the author |
| `source.written` | A write-back `DELIVERED` or `DROPPED`: conversation, system, subject, kind, cause, account, holder, attempts, reason; never the body |
| `company_settings.changed` | The company's firing limits (and webhook rates), from and to |
| `secret.accessed` | The trigger's credentials used: purposes `trigger-poll:{pollId}`, `trigger-run:{triggerId}`, `write-back:{outboxId}` |

A trigger's memory is audited as any memory (`memory.accepted`, `memory.deleted`, `memory.cleared`); see [agent-memory.md](agent-memory.md).

## 16. Troubleshooting: why a trigger did not fire

The public guide's "Firings, and why nothing started" and "When a trigger pauses or waits by itself" hold the rows in user terms. The internal diagnosis behind them:

| What you see | Internal diagnosis |
|---|---|
| A "Paused" or "Waits" chip, and no firing | The gate stops it and records nothing (section 10) |
| Nothing for a change made before the trigger existed, was resumed or reassigned | Changes before `armed_at` never fire it |
| A change to an older issue fired nothing, the next change did | The poll's first read of a subject fires only its creation and new comments (section 3.1) |
| Nothing on an issue the agent's account cannot see | A scope that answers `403` or `404` shows `SCOPE_UNREADABLE` |
| Nothing, and the issue matches by eye | **Run now** on the issue: `409 TRIGGER_CONDITIONS_FAIL` names the failing clauses in `details.failing` |
| An issue a conversation created started nothing | `SKIPPED_CHAIN`, 3 deep; a `SYSTEM` message in the creating conversation |
| A change from yesterday fired nothing after an outage | Older than `TINFIRM_TRIGGER_CATCH_UP` when first read: "changes read too late" |
| A firing a minute or two after the change | `pollIntervalSeconds` plus `TINFIRM_TRIGGER_SETTLE` |
| `SYSTEM_UNREACHABLE` | The poll failed for an hour; its error is under "How it is read" (`GET /systems/{id}/polls`) |
| `ACCOUNT_BROKEN` | A `401` from the system, a disabled credential, or a suspended holder |
| `SKIPPED_OPEN`, `SKIPPED_MISSED` | Section 3.2; a firing due while the company's runs were stopped moved to the next one |
| A webhook delivery shows `NO_TRIGGER` | No trigger's events or conditions match, or the trigger is paused or waits: `POST /systems/{id}/webhook/map` or `try_webhook_delivery` names the failing event or condition |
| A webhook delivery shows `MAPPING_FAILED` | A pointer of the mapping cannot be read ([systems.md](systems.md), [alerts.md](alerts.md)) |
| Every trigger waits `COMPANY_STOPPED` | `POST /runs-stop`; the lift arms every trigger, so nothing of the stop fires |
| Results never reach the issue | The write was dropped: the `SYSTEM` message in the conversation, and `source.written` with `DROPPED` |
