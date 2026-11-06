# The review of 2026-10-27: findings fixed

Status: the 18 open findings of a review on 2026-10-27 (listed in §1), verified against the code and fixed · Owner: J. Balcar

Every finding was first checked again against the code; all 18 held. Each was then fixed in its own area, with unit tests that fail before the fix where the finding allowed one, and integration cases where the proof needs PostgreSQL. The wording each fix needs in `docs/CONVENTIONS.md` is proposed in §3.

## 1. Disposition

| ID | Priority | Finding | What changed |
|---|---|---|---|
| F01 | P1 | MCP endpoint changes can retarget or retain old credentials | `SystemCalls` sends nothing when the row's kind or base URL is not the caller's snapshot's; the storing transaction locks the system row `FOR SHARE` (`Systems.lockShared`) and answers `409 SYSTEM_MOVED` (sign-in outcome `system_moved`) |
| F02 | P1 | Runner output collection trusts a replaceable filesystem root | `/output` is `outputs/<runId>`, outside the workspace, opened before the sandbox starts and read back only while the path still names that inode; `open_beneath` refuses a symlink root |
| F03 | P1 | Tracker write-back ignores READ credential access | The outbox drops a row whose token is lent READ, with a posted notice; the editor refuses a tracker trigger whose source row is read-only while its results write (`400 ACCOUNT_READ_ONLY`) |
| F04 | P1 | Metadata body reads can block beyond the timeout | `shared.net.BoundedBodies` takes a deadline for the whole call; CIMD, `OutboundHttp`, the SAML refresh and the LiteLLM admin client pass their timeouts; the no-deadline handlers are gone |
| F05 | P1 | Relay response metering bypasses the global memory cap | The meter keeps at most 4 MiB, reserved from the request's `InflightBytes` share, dropped on overflow or a full budget (the answer counts a request and no tokens) |
| F06 | P1 | A deployment can be promoted before every required service image exists | The tag a deployment follows moves only after `publish-services` too (`main-complete`, [ADR-0107](../adr/0107-a-deployment-repository-follows-the-registry.md)) |
| F17 | P1 | Bridge receive queues and output buffers have no cumulative memory bound | The link acknowledges what it handed on and reads at most 8 MiB ahead; output and log queues are bounded; an ACP line over 8 MiB ends the run, a stderr line is cut at 64 KiB |
| F07 | P2 | In-flight Outlook refresh restores credentials after sign-out | Authentication generations; an answer from an earlier one is revoked and refused |
| F08 | P2 | Outlook stops polling conversations awaiting action elsewhere | Only an ended conversation stops the reads; an inactive one is read every 30 s and when the pane is shown |
| F09 | P2 | Outlook retries use fresh idempotency keys | The pane keeps the request id, the held attachments and the fence tag across retryable failures (`SubmissionKey` with `isMcpRetryable`) |
| F10 | P2 | Late relay usage can miss spend settlement | `ModelEvents.RelayRequestCounted` after each counted request; `SpendSync` gives a finished budget run the settlement it lacks |
| F11 | P2 | Ignored ZIP entries bypass decompression limits | Skipped entries are drained through the 5 MiB budget |
| F12 | P2 | Audit checks can checkpoint an unverified concurrent tail | A passed check records the head it verified |
| F13 | P2 | Compose deployment omits the required model API image | `deploy-host.sh` and `tinfirm-auto-deploy` build, ship or pull both images and set `TINFIRM_MODEL_API_IMAGE` |
| F14 | P2 | Model prices remain cached across gateway changes | The price cache is keyed by `model_settings.updated_at` |
| F15 | P2 | CSV and diff previews permit excessive DOM allocation | 100 columns, 20,000 cells, 5,000 diff lines, said in the notice |
| F16 | P2 | Session retention can delete a resumed conversation's current bundle | `session_bundle_due` (V068) names the object; the sweeper deletes under the conversation's lock, only that object and only while still due |
| F18 | P2 | Gmail retries stage unused attachment copies before replay | The add-on asks `ConversationCommands.startedWith` before reading or holding; a replayed or refused start releases its holds (`StagedFiles.release`) |

Corrections to the review's numbers: the Helm server memory limit is 2 GiB (1 GiB is the request), and F05's per-answer peak was worse than 16 MiB because `result()` also made a String copy and a tree.

## 2. Validation

| Check | Result |
|---|---|
| Server, `./mvnw -B -ntp test-compile` (core, `ee/`, `libs/`) | Passed (JDK 25) |
| Server unit tests of every changed area, 22 classes, `ModularityTest` included | 110 tests, 0 failures |
| Server Testcontainers suites | **Not run.** New cases in `SessionBundleIntegrationTest`, `GmailAddOnIntegrationTest`, `McpFilesIntegrationTest` and `SpendSyncTest` compile and must still run |
| Model port, `./mvnw -B -ntp test-compile` and `BoundedBodiesTest` | Passed |
| Web, `npm test`, `npm run lint`, `tsc` for app and spec, Prettier | 168 files, 2,752 tests, 0 failures; lint clean (Node 24) |
| Runner, `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo build`, `cargo test --workspace` | Clean; tests green, 2 Docker tests ignored; one pre-existing `vm_runs` timing flake passed on re-run |
| Deploy, shellcheck 0.11.0 and actionlint 1.7.12 on the changed scripts and workflow | Clean |
| `make deploy-lint`'s Compose `config`, `make helm-lint`, `make smoke-image`, `make docs-build` | **Not run** |

## 3. Proposed wording for `docs/CONVENTIONS.md`

Each item names the finding and the place.

### F01 and F03 (§10, §15)

- §10 ports table, `system` row: add `lockShared`, "the system's row `FOR SHARE` for a module that stores a credential it checked at the system's endpoint before its transaction and compares the row's base URL with the one it checked at (`409 SYSTEM_MOVED`, ADR-0079; the lock order below)".
- §10 lock order, item 1, after "A system's row (`external_system`) comes before them": "and before the account rows: a move (`SystemService.change`, `FOR UPDATE`) removes the accounts under it, and the transaction that stores an account after a call to the system (`AccountService.again`) locks it `FOR SHARE` (`Systems.lockShared`) before the account's row and refuses a changed base URL (`409 SYSTEM_MOVED`), so a connect that races the move stores nothing. `FOR SHARE` waits on the move's `FOR UPDATE` and holds a move back for the length of that short transaction; an account insert's foreign key (`FOR KEY SHARE`) never waits on it."
- §10 "No call to another system inside a database transaction", after `SystemCalls`: "and every call of `SystemCalls` reads the system's row again in that short transaction and sends nothing when the row's kind or base URL is not the caller's `SystemInfo`'s (the call fails as if the system were gone, ADR-0079)".
- §15.15 write-back, the delivery's step 2: "A token whose access is `READ` now (the lending's access at this attempt, rows queued before a narrowing included) writes nothing: the row is dropped before any writer with the notice `its credential {name} is lent read-only to {service account}` (I9); the editor refuses a tracker trigger whose source row is lent read-only while its results write there (`400 ACCOUNT_READ_ONLY` with `details.systemId`), and a lending narrowed after the save pauses nothing."
- §15.7 tool rules, I9: "… no write through a `READ` grant or a trigger's `READ` lending (I9), at a tool's call (`TOOL_DENIED`) and at the write-back, whose delivery drops the row (§15.15)".
- §15.2 sign-in outcomes: add `system_moved`.

### F02 and F17 (§14.10, §15)

- §14.10, after "The sandbox bridge": "**What a runner host holds for a sandbox is bounded** (ADR-0095, threat model 6.13). Each side of the bridge's link acknowledges only what it has handed on to its reader and reads at most 8 MiB ahead of it; the adapter's output waits for the run's loop in bounded queues (256 chunks in the bridge session, 1024 in the host's output and log channels). A sandbox that floods, or a host that falls behind, is held back through the bridge's credit and then TCP: the adapter's writes block in the sandbox and nothing is dropped. An ACP line over 8 MiB on the agent's stdout ends the run as `PROTOCOL_ERROR`; a stderr line is kept up to 64 KiB, the rest dropped with `…[truncated N bytes]`."
- §15's `/output` text: "**`/output` is a directory of the host's**, `outputs/<runId>`, outside the workspace and every directory the sandbox writes to, opened before the sandbox starts; the host saves its files only while the path still names that directory (not a symlink, the same inode) and otherwise saves nothing with one `warn` LOG event, and it never resolves a path component the agent could have replaced when it reads back (threat model 6.19)."

### F04 (§2, §14.4)

- §2, `libs/net/` row: "`BoundedBodies`, body handlers of the JDK's HTTP client that read at most a number of bytes of an answer and within a deadline the caller sets to its timeout, request to body's end (§14.4)".
- §14.4, replace "An answer from an outside host is read within a limit … of that host do." with: "An answer from an outside host is read within a limit and within a deadline, never whole whatever its size and never for as long as the host likes (`HttpResponse.BodyHandlers.ofString()`, `ofByteArray()` and `ofInputStream()` are not used on one: the JDK's request timeout bounds the headers alone, and a body read from a stream has no clock): the shared outbound client reads 16 MiB at most, the identity module's documents their `maxDocumentBytes`, the SAML metadata 1 MiB, the model gateway's answers their limits (§15.4), through `shared.net.BoundedBodies`, whose handler takes the call's timeout as the deadline of the whole call, from the request (the handler is made right before the send) to the end of the body. It refuses a body declared longer before it reads it, cuts one off at the limit, closing its connection, and cuts one off at the deadline, which fails the call with `HttpTimeoutException` as an answer that never came does; a larger or a stalled answer fails as the caller's other errors of that host do."
- Optional, §4 settings: `TINFIRM_CONNECTION_TIMEOUT`, `tinfirm.model.admin-timeout` and `tinfirm.oauth.cimd.timeout` read "Connect timeout, and the whole budget of one request from its send to the end of its body".

### F05, F10 and F14 (§4, §10, §15.4, §15.12)

- §4, `TINFIRM_MODEL_RELAY_MAX_INFLIGHT_BYTES`: "… and what the relay keeps of a plain JSON answer to read its usage (at most 4 MiB, reserved part by part and given back when the request ends); a body beyond it is `429` with `Retry-After`, an answer beyond it is passed on unmetered (§15.4)."
- §10 events table, `model` row: add "`RelayRequestCounted` (`companyId`, `runId`; published outside any transaction after the relay counted a request)". §10 paragraph on the governance module's listeners: "it also listens, outside any transaction, to the model module's `RelayRequestCounted`, which the relay publishes after it counted a request and after the answer's usage was recorded (`SpendSync.relayRequestCounted`, in a short transaction of the run's company): a run that ended while its last answer was under way gets its settlement when it has none yet."
- §15.4 relay table, "Before LiteLLM" row: "the same budget holds what the relay keeps of a plain JSON answer to read its usage (`RelayUsageMeter`: at most 4 MiB, reserved part by part as the answer streams back, given back when the request ends; of a stream only one line, at most 4 MiB, a longer one dropped), so that an answer the cap or the budget cannot hold is passed on unmetered, counting a request and no tokens, with one warning per answer".
- §15.12 first bullet: "or a plain JSON answer, kept up to 4 MiB within the replica's `relay-max-inflight-bytes`, else unmetered: a request and no tokens". Step 1, append: "A request the relay counts after the run ended is published as `ModelEvents.RelayRequestCounted` outside any transaction, once per request; `SpendSync` hears it in a short transaction of the company and gives the finished run paid by a budget the same `PENDING` settlement when it has none yet (`SpendStore.pendingIfAbsent`, `ON CONFLICT DO NOTHING`); a settlement that exists stays. The count's `UPDATE` of the token row waits for the end's transaction, which revoked the row, so the count either came before the end, which saw it, or sees the run finished." Step 5: "(`SpendLogs.prices`, kept an hour per company for the settings they were read under, `model_settings.updated_at`: a changed gateway reads them again at once, and a read that fails after a change prices nothing rather than with the old gateway's prices)". Tests table: `SpendSyncLateUsageTest`, `SpendLogsImplTest`, the new `RelayUsageMeterTest` and `SpendSyncTest` cases.

### F11, F12 and F16 (§7, §8, §10, §15.27)

- Skill zip (§15): after "… `__MACOSX/` and `.DS_Store` skipped" add "(skipped, not unread: the stream inflates every entry, so a skipped entry's bytes count against the 5 MiB too and are refused past it, `SkillBundleTest.countsWhatItSkipsToo`; only the file count leaves them out)".
- §7 `audit_check` row and §8 the daily check: "a passed check records the head it verified (`head_seq`, `head_hash`: the head row as it read it before the verification, never an event appended while it ran, which writers may do since only they lock the head row); a failed check records the chain's last event as found, so that an acknowledgement anchors the next check past the damage (`AuditCheckServiceTest`)". §7's migration list is stale (names `V055`); the highest is now `V068`.
- §15.27 sweeper bullet: "the bundles whose conversation ended or took no turn for the retention (`session_bundle_due`, a `SECURITY DEFINER` lookup of ids and the object each row names, `V068`), each deleted again in its company's context with the conversation's row locked first (`run.ConversationLock`) and only while the row still names that object and the conversation is still ended or idle (`SessionBundleStore.deleteDue`): a conversation that resumed since the lookup keeps its current bundle; a batch that removes no row ends the pass". §10 lock order, item 2: add `session_bundle` after the conversation.

### F13 (§12)

- Compose bullet: "The images of `server` and `model-api` come from `.env`: `TINFIRM_SERVER_IMAGE` and `TINFIRM_MODEL_API_IMAGE`, unset `tinfirm-server:local` and `tinfirm-model-api:local`, which `make up` builds. `deploy/compose/deploy-host.sh` builds, ships and sets both; `tinfirm-auto-deploy` sets both to `sha-<rev>` of the deployed revision and overwrites a value set by hand." `ci.yml` bullet: "`main-complete` runs after `publish`, `publish-services` and `rolling`, so that a deployment that follows its tag never pulls a model port image that is not published yet".

### F18 (§14.11, §15.7, §15.14)

- §14.11, files for a start: "`StagedFiles.release` lets the caller go of the held files among the ids it names, their own only, for a start that was answered as a replay or refused; not audited. `ConversationCommands.startedWith` answers what the caller's start under a request id made (`SubmissionKeys.find`: the answered key's resource within its lifetime, whatever the request's hash; it claims, waits for and refuses nothing), so that a client that holds files asks before it holds them again." Gmail paragraph: "A start asks for the click's conversation first, before it reads the email or holds anything; a start the core answers as a replay or refuses releases the files this click held."
- §15.7 held files: "what a replayed or refused start did not take, the client releases (`StagedFiles.release`), else they expire".
- §15.14 tests table: the extended `McpFilesIntegrationTest` and `GmailAddOnIntegrationTest` cases and `GmailAddOnImplTest`.

## 4. Open after these fixes

- The Testcontainers suites still have to run (§2).
- F01 pins the `SystemCalls` paths. A tool call (`SystemTools.call`) with a token revealed before a move is outside this change.
- A consumer of the bridge that stalls longer than the peer's write timeout makes the peer reconnect; nothing is lost, and it is inherent to backpressure by not reading (ADR-0095).
- `docs-site/content/reference/configuration.md` already lists `TINFIRM_MODEL_API_IMAGE`; the user-guide's sign-in outcome table does not list `system_moved`.
