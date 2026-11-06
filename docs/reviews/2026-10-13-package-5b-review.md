# Package 5b review: webhooks, channel triggers, the outbox's BOT writer, Slack history

Reviewed against the package 5 contract (`2026-10-12-package-5-contract.md`) and
`docs/CONVENTIONS.md` (§10, §15.13, §15.16, §15.17). The API additions made for the web app were
reviewed too.

Every finding was traced in the code from a real caller or input. The two top findings came with
tests that failed when this review was written. The existing `WebhookTriggersTest` and
`WebhookVerifierTest` passed.

## Findings, by severity

### 1. Medium: a verified body holding `\u0000` is answered 503, and the sender retries it for days

`WebhookInbox.parse` (`WebhookInbox.java:338-349`) accepts the JSON escape `\u0000`. The body is an
object within the depth and value limits. The delivery is verified and counted against the system's
rate (`:181`). Then `WebhookStore.insert` (`WebhookStore.java:142-150`) runs `CAST(? AS jsonb)`, and
PostgreSQL refuses it: `\u0000 cannot be converted to text`. The `DataAccessException` is caught at
`WebhookInbox.java:207-210` and answered `503` as if the database had failed.

A Standard Webhooks sender retries a 5xx with backoff for days. Each attempt spends the system's rate
and logs a warning. Nothing tells the admins: there is no `webhook_delivery` row and no audit. The
mapped text would fail the `trigger_firing` and `message` inserts the same way, because `text`
refuses NUL.

**Fix:** refuse the NUL in `parse`, as a bad body (`400`), before the delivery counts or is stored.
Keep the 503 for real database failures.

**Test:** `WebhookTriggersTest.aVerifiedBodyWithANulEscapeIsRefusedAsABadBodyNotAsADatabaseFailure`.

### 2. Low–medium: `channelState` and the trigger editor lock in opposite orders and deadlock

- **The chat's side.** `ChannelTriggersImpl.channelState` (`:222-227`) runs
  `TriggerStore.channelState` (`TriggerStore.java:692-701`), whose `UPDATE trigger_channel …
  RETURNING` locks the channel's rows. Only then does `gate.check` lock the trigger
  (`TriggerGateImpl.java:139`, `FOR NO KEY UPDATE`).
- **The editor.** It locks the trigger first (`TriggerService.java:218`), then the rows in
  `store.replaceChannels` (`:989`; `TriggerStore.java:588-625`).

The chat's side runs from `ChatChannels.changed`: on a lifecycle event, on `seen`, on `gone`, and on
`refresh`. `refresh` runs on every `GET /systems/{id}/chat/channels` by any member. So an author
editing a trigger's channels while another member opens the channel picker can deadlock (`40P01`).
That ends one side: the editor with a 500, or a failed inbox event, a failed outbox attempt, or a 500
on the channel list.

**Fix:** in `channelState`, read the channel's live trigger ids first, lock those triggers in id
order, then update the rows and run the gate. Add the order to §10: the trigger row, then its
`trigger_channel` rows.

**Test:** `trigger/internal/ChannelStateLockOrderTest`.

### 3. Low: a stop or pause racing a webhook delivery loses the delivery

`WebhookInbox.receive` checks `switches.runsStopped` in a short transaction of its own
(`WebhookInbox.java:169-172`). Suppose a stop commits after that check but before the firing's gate
lock:

1. The gate answers `Waits(COMPANY_STOPPED)`.
2. `WebhookFirings.fire` turns that into `Fired(null, …)`.
3. `WebhookReceiver` records the delivery as `NO_TRIGGER` and answers `202`.

The sender takes it as accepted, and it is never fired. The contract says such a delivery is a 503,
so that the sender delivers it again. A system pause has the same shape: `200 {paused}` expected,
`202 NO_TRIGGER` given.

**Fix:** a dedicated exception from `fire` on `COMPANY_STOPPED` (or the source system's
`SYSTEM_PAUSED`), answered 503 or `200 {paused}`. The transaction rolls back, so nothing is stored.

### 4. Low: two concurrent saves claiming the same channel end in a 500, not `409 CHANNEL_TAKEN`

`TriggerEditor.channels` reads the claims without locks (`TriggerEditor.java:790-803`). V030's
unique indexes `trigger_channel_agent_uq` and `trigger_channel_default_uq` hold the invariant, but
the loser's `DataIntegrityViolationException` falls through to the generic 500 handler.

**Fix:** map the unique violation to `409 CHANNEL_TAKEN`.

### 5. Low: the per-address flood check runs after a database lookup

`receive` resolves the system with `webhook_system_by_id` (`:141-143`) before `limiter.flooded`
(`:148`). The contract puts the 429 before anything else.

**Fix:** key the flood check by the path's system id and check it first.

### 6. Low: every channel list calls Slack with the bot's token

`ChatChannels.list` runs `refresh` (`:235-258`) on every call, by any member. Hammering the list can
get the bot rate-limited (Slack tier 2), and the outbox's posts then wait on 429s.

**Fix:** refresh at most once per system per minute.

### 7. Low (tests): `/api/hooks/{systemId}` has no case in `CrossTenantBodyTest`

§10 asks for one for every path without a company. The code is sound.

**Fix:** add company A's secret on company B's system: 401, nothing stored in B, no audit in B.

### Nits

- **(a)** A kept previous secret can pass up to 10 minutes past its 24 hours, because of the
  per-replica 10-minute cache (`WebhookInbox.java:216-218`, `WebhookSystemsImpl.java:226`).
- **(b)** `WebhookMapping.text` and `cutBody` cut on UTF-16 indexes and can split a surrogate pair.
- **(c)** A company with more than about 50 webhook systems reaches `SecretService.createNamed`'s
  `_99` suffix limit on rotation (`WEBHOOK_SECRET_n`).

## Checked and found sound

### Signature verification (`WebhookVerifier.java:64-99`)

- The headers' shape is checked first.
- The timestamp must match `^[0-9]{1,12}$` and be within ±300 s.
- The signature is HMAC-SHA256 over `{id}.{timestamp}.{raw bytes}`, keyed with the base64-decoded
  bytes after `whsec_`.
- Every `v1,` signature is compared in constant time (`MessageDigest.isEqual`) against the current
  secret and, while kept, the previous one. `SECRET_HEADER` is compared in constant time too.
- The raw body (at most 1 MiB) is verified before it is parsed, and before the stop and pause
  checks, the rate and any storage.
- Replays are refused by the unique `(system_id, delivery_id)` with `ON CONFLICT DO NOTHING`, in the
  transaction that maps and fires. The rows are kept 7 days.

### Secrets

- Generated with `SecureRandom` (32 bytes) and stored as managed company secrets in the vault.
- Answered once, on create and on rotate. `GET /systems/{id}` never carries them.
- Every reveal is audited as `secret.accessed`.
- Rotation keeps the previous secret for 24 h, deletes the one before, and is audited with
  `previousUntil` only.

### SSRF

- The webhook makes no outbound call.
- Teams' `serviceUrl` comes from verified activities and is checked again against the connector's
  origin at send time.
- Slack permalinks are built from ids, URL-encoded.

### Rates

- Refusals are counted per (system, client address).
- Only verified deliveries count toward `maxPerMinute`, which is capped by the company's maximum.
- `webhook.refused` is audited once a minute per system and reason.
- With `forward-headers-strategy: native`, `X-Forwarded-For` counts only from internal proxies.

### Tenant isolation

- The inbound path finds the company only through `webhook_system_by_id` (SECURITY DEFINER, ids
  only, `search_path` pinned). Everything after it runs under that company's RLS.
- `webhook_delivery` and `trigger_channel` have FORCE RLS and composite foreign keys.
- The admin routes require `MANAGE_SYSTEMS` within the company.

### Who may do what

- Webhook and channel triggers cannot be run by hand.
- The editor's channel checks run for the author, whoever edits.
- Open writes need `acknowledgeOpenEvents`.
- In a channel:
  - an approval is decided only by a linked, active member that `Decider.approves` admits;
  - a result's review is never decided from the chat;
  - a question may be answered by any workspace member;
  - a mention's text answers questions only.
- `PLACE_IS_EXTERNAL` applies as specified.

### Slack history and the bot's token (C9)

- `history` is a read with validated arguments, run with the conversation's own user token.
- A `LINK` binds nothing.
- The bot's account is refused.
- Slack's OAuth asks `user_scope` only and checks the team.
- `already_linked` and `identity_in_use` behave as specified.

### Logs and audit (C16)

Only ids, the delivery id, the event, the subject key and the depth reach the audit, with names
where the contract allows them. Tokens are masked.

### Against the contract

The following match the contract:

- the mapping (RFC 6901 pointers, cut lengths, http(s) urls, events and conditions);
- firing (gate, rate, echoes and depth, one open conversation per trigger and key, the `WEBHOOK`
  place and actor, the `EXTERNAL` message, the stop's 503 and the pause's 200);
- channel mentions (§15.17's order, one conversation per thread);
- the bot's failure (open point 21b);
- the outbox's BOT writer (V029's checks, waits, drops, `system_write`);
- the migrations V019, V029, V030 and V031 (RLS, grants, composite foreign keys, the definer).

### API additions for the web app

All are additive and permission-checked:

- `GET /departures`: `MANAGE_MEMBERS`;
- `GET /holds/conversations/{key}`: `MANAGE_RETENTION`;
- `userId` on `MEMBER_SUSPENDED` rows only;
- `botPrincipalId` and `botPrincipalName`: for managers only;
- `Conversation.chat`: for callers who already see the conversation;
- `MemberAccount.systemKind`;
- `GET /subjects?systemId`: within the caller's scope.
