# Package R: replay safety and idempotent submissions

Status: decided (2026-10-12). Three ideas taken from pi-durable (`earendil-works/pi`, `packages/durable`), a durable agent harness, applied to Tinfirm's server. We take the ideas, not the library: Tinfirm's record stays in PostgreSQL, and the runner keeps wrapping the agent runtimes over ACP.

| pi-durable idea | Here |
|---|---|
| A tool's intent is committed before it runs; after a crash a tool runs again only if it is declared `replay: "safe"`, else the model gets an `interrupted` result | §3.1, §3.2, §3.4: every system tool is `SAFE` or `UNSAFE`; a call is journaled before it runs; an unsafe call left running is settled `TOOL_INTERRUPTED`, never run twice |
| Nested calls are keyed, so a rerun gets the earlier results back | §3.2: a call is keyed by `(run, request id)`; a request sent again gets the stored result, on any replica and after a restart |
| A submission with the same `requestId` returns the existing one | §3.5: `Idempotency-Key` on the REST submissions, `requestId` on the MCP tools |

## 1. Gaps found

- **G1. A tool call can run twice.** `RunRequestRouter` remembers a `TOOL` request by its id only in memory, for 2 minutes, on one replica ("an accepted limit"). The host sends a request again after a reconnect. If the server restarted, or the host reconnects to another replica, a write call (a comment, a transition, a change request) is made a second time.
- **G2. A write that timed out reads as failed.** Past `TINFIRM_RUN_TOOL_TIMEOUT` the call is answered `TOOL_FAILED` and its thread interrupted; the system may have done the write. The model, told it failed, calls again.
- **G3. Submissions are not idempotent.** A client that retries after a lost answer starts a second conversation, posts a second message, uploads a second file or starts another paid turn. This happens with the web app on a flaky network, and with MCP clients, which retry.
- **G4. Recovery settles reads it could rerun.** The recovery of approved and parked calls (`V015`) settles every call taken long ago as of unknown outcome, reads included.

## 2. Non-goals

The storage engine and harness of pi-durable; resuming a turn in the middle of the runtime's work (a later study, after the redesign is built: it needs a runtime of our own); code mode; the runner's ACP protocol and its platform tools (`ask`, `request_access`, `save_artifact` and others already deduplicate durably by request id: a decision, output or request row per id).

## 3. Design

### 3.1 Replay class of a tool

`system.ToolReplay { SAFE, UNSAFE }` on every system tool, beside its `ToolEffect`. The default follows the effect: `READ` is `SAFE`; `WRITE` and `UNKNOWN` are `UNSAFE`. A system's catalog may declare a write `SAFE` only when the system's API makes the call idempotent: the same call twice leaves the same state and writes nothing new. Examples are setting a field to a value with `PUT`, or adding a label to a set. Creating a comment, an issue, a change request or a transition with side effects is never safe. Nothing is declared `SAFE` among writes in this package: the catalog gains the field, and each later declaration is reviewed with its test. The run's tool list carries the class (`run.assign.tools[].replay`, optional, default from the effect), for the runtime's information only; the server decides.

### 3.2 The call journal

A new table `tool_call` (`V026__call_journal.sql`, company-scoped, RLS as V001), one row per `TOOL` request of a run:

| Column | Meaning |
|---|---|
| `company_id`, `run_id`, `request_id` | Key: unique `(run_id, request_id)` |
| `conversation_id`, `system_id`, `tool`, `effect`, `replay` | What is called, as checked |
| `arguments_sha256` | Of the normalized arguments; a request sent again with other arguments is `INVALID_REQUEST`, never a call |
| `state` | `RUNNING`, `DONE`, `INTERRUPTED` |
| `attempt` | 1, and +1 for each rerun of a `SAFE` call |
| `started_at`, `finished_at` | The claim of the current attempt, and its end |
| `result` | The MCP result as answered (scrubbed, within `SystemTools`' limits); cleared when the run ends |

The flow of a `TOOL` request, replacing the in-memory map:

1. **Claim**, in a short transaction of its own that locks nothing but this row: `INSERT … ON CONFLICT (run_id, request_id) DO NOTHING`, then the row `FOR NO KEY UPDATE`.
   - **New:** the row is `RUNNING`, attempt 1. Run the call (steps 2 and 3).
   - **`DONE`:** answer the stored result. Nothing is called.
   - **`INTERRUPTED`:** answer `TOOL_INTERRUPTED` again.
   - **`RUNNING`, younger than the tool timeout plus 15 seconds:** it runs elsewhere, on this replica or another. Wait for it: read the row every 500 ms until it is `DONE` or `INTERRUPTED`, at most until the host's 65 s wait ends, then answer what it holds. A row still `RUNNING` at the end of the wait is answered nothing, and the host sends the request again.
   - **`RUNNING`, older:** its replica stopped. A `SAFE` call is claimed again (attempt +1) and run. An `UNSAFE` call is set `INTERRUPTED` and answered `TOOL_INTERRUPTED`.
2. **Call**, outside any transaction, as today (`ConversationBindings.use`, `SystemTools.call`).
3. **Record**, in the caller's transaction as today (`ToolRunner.record`). The row is set `DONE` with its result in the same transaction as `tool.called`. The order follows CONVENTIONS §10: the binding and the account (a credential refused: `Accounts.refused`) first, then the `tool_call` row, then the audit last. This also closes the known lock-order gap: `record` audited before `Accounts.refused`.

**Timeout.** When `TINFIRM_RUN_TOOL_TIMEOUT` passes, an `UNSAFE` call is set `INTERRUPTED` and answered `TOOL_INTERRUPTED` instead of `TOOL_FAILED`; its thread is interrupted as today. A `SAFE` call is still `TOOL_FAILED`, and its row is set `DONE` with that error result, so a request sent again does not run it a second time.

**`TOOL_INTERRUPTED`** is a tool error result (`isError: true`), not a rejection: the text names the tool and the system and says that the call may or may not have taken effect, and that the agent should check the system's state (with a read tool where it has one) before calling it again. Audited `tool.interrupted` (SYSTEM, target `conversation`, details `runId`, `requestId`, `systemId`, `tool`, `attempt`, `cause`: `TIMEOUT` or `RESTART`). The conversation's view shows the call as interrupted.

**A sweep** (on `worker` replicas, every minute) settles the `RUNNING` rows older than the tool timeout plus 15 seconds whose host never sent the request again. An `UNSAFE` call is set `INTERRUPTED` and audited; a `SAFE` call is set `INTERRUPTED` without an audit, and is claimed again if the request comes back. When a run ends, its rows' `result` is cleared. Rows are deleted 7 days after their run ended (`TINFIRM_TOOL_JOURNAL_RETENTION`, `P7D`). The retention sweep of package 6 and an erasure delete a purged conversation's rows with its runs' events.

### 3.3 Approved and parked calls

`V015`'s recovery keeps its rules for `UNSAFE` calls. A stale approved call (`decision.call_started_at` set long ago, no result) or a parked call left `RUNNING` whose tool is `SAFE` is run again instead of being settled as of unknown outcome. An `UNSAFE` one is settled as today, and its answer to the agent is the same `TOOL_INTERRUPTED` text.

### 3.4 Lock order

The `tool_call` row joins CONVENTIONS §10's order after bindings, decisions and outbox, and before `audit_chain_head`. The claim transaction (step 1) and the sweep lock only `tool_call` rows.

### 3.5 Idempotent submissions

**REST.** An optional header `Idempotency-Key` (1 to 200 visible ASCII characters; a UUID is recommended) applies to:

- `startConversation`
- `postConversationMessage`
- `uploadAttachment`
- `retryConversation`
- `decideDecision`

How each case is answered:

- **First use:** the request runs, and the key is stored with what it made. It is inserted in the same transaction that creates the resource, so a concurrent request with the same key waits on the unique index, then finds the first one's resource.
- **Same key again, same operation, same request** (the SHA-256 of the method, the path and the body; for an upload, of the file's hash, its name and the note): the answer has the first request's status code and shows the resource it made as it is now, plus the header `Idempotent-Replayed: true`. Nothing new is made, audited or charged.
- **Same key with another operation or another request:** `422 IDEMPOTENCY_KEY_REUSED`.
- **The first request failed:** a request that answered an error stored no key, so the client may retry with the same key.

**Storage.** A new table `submission_key` (in `V026`): `company_id`, `principal_id` (the caller's), `key`, `operation`, `request_sha256`, `resource_type`, `resource_id`, `created_at`. It is unique on `(company_id, principal_id, key)`. Keys are kept 24 hours (`TINFIRM_IDEMPOTENCY_KEY_TTL`, `PT24H`) and then deleted by the worker's sweep. A key is the caller's own: the same key from another member is another key.

**MCP.** `start_conversation`, `send_message`, `retry_turn` and `decide` take an optional `requestId` argument with the same rules. Their descriptions tell the client to send a new one for each intent and to reuse it when it retries.

**Web.** The web app makes a key with `crypto.randomUUID()` per submission (a message, a start, an upload, a retry, a decision). It keeps the key until the request is answered, so a retry sends the same key. This is built in web slices W3 and W4.

## 4. Tests

| Test | Proves |
|---|---|
| `ToolCallJournalIntegrationTest` | A request sent again after the call ended returns the stored result with no second call, on another router instance (a second replica) and after the in-memory state is gone; a request sent again while the call runs waits for it; other arguments under the same id are `INVALID_REQUEST`; the result is cleared when the run ends |
| `ToolInterruptedIntegrationTest` | An `UNSAFE` call left `RUNNING` (a replica stopped mid-call) is answered `TOOL_INTERRUPTED` and audited, never called again; a `SAFE` one is called again (attempt 2); an `UNSAFE` call past the timeout is `TOOL_INTERRUPTED`, a `SAFE` one `TOOL_FAILED`; the sweep settles rows whose request never comes back |
| `ToolReplayTest` | The defaults by effect; a catalog's `SAFE` write; the class in `run.assign.tools` |
| `CallRecoveryIntegrationTest` (extended) | A stale approved `SAFE` call runs again; an `UNSAFE` one is settled as before |
| `ConversationLockOrderTest` (extended) | `record` takes the account before the journal row and the audit last |
| `IdempotentSubmissionIntegrationTest` | For each operation: a replay returns the first answer with `Idempotent-Replayed: true` and makes, audits and charges nothing new (one run, one message); two concurrent requests with one key make one resource; another body is `422 IDEMPOTENCY_KEY_REUSED`; a failed first request stores no key; keys are per principal; the sweep deletes keys after 24 h |
| `McpConversationToolsIntegrationTest` (extended) | `requestId` on `start_conversation`, `send_message`, `retry_turn`, `decide` |
| `TenantIsolationTest` | Company B's journal rows and keys are invisible to company A |

## 5. Configuration

| Variable | Meaning | Default |
|---|---|---|
| `TINFIRM_TOOL_JOURNAL_RETENTION` | How long after its run ended a call's journal row is kept | `P7D` |
| `TINFIRM_IDEMPOTENCY_KEY_TTL` | How long an idempotency key is kept | `PT24H` |

## 6. Decisions taken

1. Take the ideas, not the library (it duplicates the server's record, its storage is per host, and its API is experimental).
2. Writes default to `UNSAFE`; none is declared `SAFE` in this package.
3. `TOOL_INTERRUPTED` is a tool error the model reads, not a rejection: the agent decides how to check.
4. The header is `Idempotency-Key` (the IETF draft's name) on REST, and `requestId` on MCP tools.
5. A replay answers the first request's status and body. A key reused for another request is `422`.
6. Package R is built beside package 4. The web's use of keys comes with slices W3 and W4.
