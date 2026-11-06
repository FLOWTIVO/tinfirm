# Runner protocol v2 on the runner hosts

How a runner host picks the runner protocol version, why it must speak v2, and what v2 carries.
How a conversation maps onto the protocol: [PLATFORM.md §11.1](../PLATFORM.md#111-the-runner). The
protocol itself: the schemas [`protocol/runner-v1.schema.json`](../../protocol/runner-v1.schema.json)
and [`protocol/runner-v2.schema.json`](../../protocol/runner-v2.schema.json), and the host's
[README](../../runner/README.md#protocol-versions).

## What changes with v2

| | v1 | v2 |
|---|---|---|
| Platform requests | `run.ask`, `run.repository_request`, `run.task_create` and their acks | one `run.request {kind: ASK \| ACCESS \| ARTIFACT \| NETWORK \| TOOL}` and one `run.request.ack` per request |
| Tools the agent sees | `ask_human`, `finish_run`, `request_repository`, `create_task`, `upload_attachment`, `list_repositories` | The platform tools `ask`, `finish`, `request_access`, `request_network_access` (only on a host with the egress proxy), `save_artifact` and `list_repositories`, as far as `run.assign.platformTools` names them; plus the server-executed tools of `run.assign.tools`: the systems' tools (`jira_get`, `jira_search`, …) and, while the agent's memory is on, `remember`, `forget` and `recall` |
| Documents | `upload_attachment` only | `/output` in the sandbox, saved as outputs of the conversation when the run ends (`run.finished.artifacts`), and `save_artifact` for files elsewhere and for links. The host refuses a file above `run --upload-max-bytes` (default 25 MiB, the server's default `TINFIRM_ATTACHMENT_MAX_FILE_SIZE`) before it reads it; set the two alike |
| `run.assign` | `task {id, key, title}` | `workItem {id, key, title}` and `job {id}`, which carry the conversation (PLATFORM.md §11.1); `tools[]`; `platformTools[]`, the platform tools the agent may use (`finish` and `list_repositories` always); `allowedHosts[]`, the network hosts approved for the conversation or the agent; `skills[]` (feature `run.skills`, below); and `agent.effort` unless it is the runtime's default |
| `hello.features` | none | The optional features the host implements: `run.steer` and `run.skills` (below) |
| `hello.isolationTiers` | `container`, `gvisor` | also `microvm` (a Kata runtime) |
| `welcome` | no version | `protocolVersion`: the version the server speaks |
| Usage (USAGE events, `run.finished.usage`) | cache writes counted in `inputTokens` | `cacheWriteTokens` apart from `inputTokens` |
| A run stopped at `limits.maxWallClockSeconds` | `GUARD_STOPPED`, as the other guards | `TIME_LIMIT`, which the server shows as a stop that a retry goes on from |

Epochs, leases, events, heartbeats, capacity settings, drain and reconnects are the same.

**Conversations run on v2 hosts only.** The server accepts a v1 host's connection, but gives it no
turn. A conversation that only v1 hosts may run waits with "Runner host … speaks runner protocol 1;
conversations run on hosts of protocol 2."

## Choosing the version on a host

- `config.json` in the host's config directory holds `protocolVersion`; `login` writes 2, and
  a file from before this field means 2 as well. `tinfirm-runner-host run --protocol-version 1`
  (or the variable `TINFIRM_RUNNER_PROTOCOL_VERSION=1`) overrides it for that start; `run`
  logs the version it speaks in its `runner host starting` line.
- Keep the default, 2. A host set to 1 connects to the current server but takes no turn (above).

## Negotiation and the failure modes

- The host names its version in `hello.protocolVersion`. The server accepts 1 and 2 and
  answers with `welcome.protocolVersion` set to the host's version.
- A host started with version 2 that gets a `welcome` without `protocolVersion`, or with
  another version, is talking to a server that does not speak v2: it sends `error`
  `UNSUPPORTED_PROTOCOL`, closes with 1002 and exits non-zero. Its error names the mismatch
  and the `--protocol-version` to start it with instead. There is no fallback to v1 within a
  session, and no reconnect loop. With systemd (`Restart=always`) such a host restarts and
  fails the same way every few seconds until it is started with the right version or the
  server is upgraded: check the journal.
- A server that does not know the host's version at all refuses the `hello` with close code
  1002, which the host treats the same way (it says to update the host or to pass
  `--protocol-version 1`).

## Moving a host to v2

1. Find the hosts on v1: their `runner host starting` line shows `protocol_version` 1, and the
   conversations they could run wait with the reason above.
2. On each, set `"protocolVersion": 2` in `config.json` (or remove the field), and remove
   `--protocol-version 1` or `TINFIRM_RUNNER_PROTOCOL_VERSION=1` from its service file. A host
   whose service file passes no version still runs v1 while its `config.json` has
   `"protocolVersion": 1`.
3. `sudo systemctl reload tinfirm-runner` (or your unit's name): the host drains and starts
   again on v2. A host too old to speak v2 needs a current runner host binary first
   ([runner/README.md](../../runner/README.md#install-from-a-release)).

## The server's side

- `hello.protocolVersion` 1 or 2 is accepted, any other is refused with `error`
  `UNSUPPORTED_PROTOCOL` and close code 1002; `welcome.protocolVersion` echoes it. Every later
  frame is read in that version: a v1 frame from a v2 host (or the reverse) is answered with
  `error` `UNKNOWN_TYPE`.
- Every `run.request` gets exactly one `run.request.ack` with its `requestId`, also for a
  stale epoch or an unknown run (`REJECTED` / `RUN_NOT_ACTIVE`); a request sent again after a
  reconnect gets the same decision, the same link artifact, or the answer of the same tool
  call, never a second one. `ASK`, `ACCESS` and `NETWORK` store a decision of the
  conversation and answer `PENDING`; an approved network host is in the next run's
  `allowedHosts`.
- Server-executed tools read the company's systems with the account the conversation uses on
  that system (the person's own account, or a credential lent to a group or a service account),
  revealed for the call with purpose `run-tool:{runId}`, outside any transaction and on a virtual
  thread, within `TINFIRM_RUN_TOOL_TIMEOUT` (`PT60S`; the host waits 5 s longer). A result
  above 800 KiB is refused with `RESULT_TOO_LARGE`. Each call is audited as `tool.called`
  (the system, the tool, its effect and mode, the account it acted with, whether it worked;
  never the arguments or the result). Since package R the calls are kept in the server's call
  journal in the database (CONVENTIONS §15.14): a request sent again, to any replica and after
  a restart, gets the stored answer, and a call Tinfirm lost track of runs again only when its
  tool is `SAFE`; an `UNSAFE` one is answered with the tool error `TOOL_INTERRUPTED` (`isError`,
  `structuredContent.code`), which tells the agent to check the system before calling again.
  The server always answers a TOOL request within its wait: a request sent again while its call
  still runs on another replica waits for it at most 55 s after the call's claim
  (`tinfirm.conversation.tool-journal.answer-within`), then gets `TOOL_INTERRUPTED` (a `SAFE`
  call `TOOL_FAILED`), and a call the server cannot answer within the tool timeout and 2 s is
  `TOOL_INTERRUPTED` too, never `TOOL_FAILED`. The host waits 65 s from its first send, once
  more from a send after a reconnect, and words its own timeout of a TOOL request as
  `TOOL_INTERRUPTED` (cause `TIMEOUT`). `run.assign.tools[].replay` (`SAFE` or `UNSAFE`,
  optional) carries each tool's class for the runtime's information; a host ignores it.
- **The limits a host must respect** (the security review of 2026-10-27, M4). The server
  enforces them before any work, in memory per replica, and answers each refusal at once:
  - `run.request`: 50 per second per host and 200 per company, ten seconds' worth at once;
    beyond it `REJECTED TOO_MANY_REQUESTS`. At most 4 `TOOL` requests of a run wait for their
    answer; a fifth is `REJECTED TOO_MANY_REQUESTS` and never reaches the system. The agent may
    send it again later, with a new `requestId`.
  - `run.event`: 20 per second per run, 200 per host and 500 per company, ten seconds' worth at
    once; beyond it the event is answered `error TOO_MANY_REQUESTS` with its `runId` and not
    stored, and a `LOG` event is dropped without an answer.
  - An event's payload is at most 256 KiB and an `AGENT_MESSAGE` or `AGENT_THOUGHT` text at
    most 32 KiB, in UTF-8: beyond them `error PAYLOAD_TOO_LARGE`, and the event is not stored.
    The host already cuts to these sizes; an error of this code means a host that does not.
  - A run's stored payloads are at most 64 MiB. The first event past them is stored as a `LOG`
    warning in its place, which the run's page shows; later `LOG` events are dropped and the
    others answered `error RUN_EVENTS_FULL`. The run goes on, and ends as its host says.
  - Usage is kept within `[0, Long.MAX_VALUE / 4]` tokens; a negative or non-finite cost is not
    kept.

  A host logs these errors as warnings and does not send a refused event again. The variables
  `TINFIRM_RUNNER_REQUESTS_PER_SECOND`, `TINFIRM_RUNNER_COMPANY_REQUESTS_PER_SECOND`,
  `TINFIRM_RUNNER_EVENTS_PER_SECOND`, `TINFIRM_RUNNER_HOST_EVENTS_PER_SECOND`,
  `TINFIRM_RUNNER_COMPANY_EVENTS_PER_SECOND` (`0` turns one off),
  `TINFIRM_RUNNER_TOOL_CALLS_IN_FLIGHT` and `TINFIRM_RUNNER_EVENTS_MAX_BYTES` change them
  ([server/README.md](../../server/README.md)). The file and session bundle uploads read their
  body only after the host's token was checked, and a session bundle's body must arrive within
  30 seconds, one upload at a time per host.
- The tools of a run are those the agent may use on its systems, less those the settings switch
  off, plus those an approved `request_access` gave its conversation. A tool is named
  `{system handle}_{tool}` in lower case (`shop_jira_get` for the tool `get` of the system whose
  handle is `shop_jira`); a name longer than 64 characters keeps the handle, cuts the tool's name
  and ends in four hex digits of its hash.
- **Public Suffix List.** The server reads only the list bundled with it
  (`server/src/main/resources/psl/`): it downloads, stores and refreshes nothing, and the
  `TINFIRM_PUBLIC_SUFFIX_*` variables are not read. Right after `hello` it sends that list in
  `psl.update` to a v2 host whose `hello.publicSuffixList.sha256` is absent or differs, so
  that the host refuses the same network wildcards as the server; a host that holds it, and
  a v1 host, get nothing. The list changes only with a server release, so nothing pushes it
  again while the server runs. The frame is about 120 KB; no setting needs to change.
- **Steering** ([ADR-0060](../adr/0060-steering-a-running-turn.md)). A v2 host that lists the
  feature `run.steer` in `hello.features` gets the person's messages written while its run works
  as `run.steer`, and answers each with `run.steer.ack`; a host that does not list it keeps the
  old behaviour, where such a message waits for the next turn. Nothing needs configuring:
  upgrade the hosts, and rebuild the pi image for pi's steering
  (`tinfirm-runner-host images build --runtime pi`). OpenCode has no steering; its messages wait
  for the next turn.
- **Skills** ([ADR-0064](../adr/0064-skills-library.md)). A v2 host that lists `run.skills` gets
  the agent's skills in `run.assign.skills` and downloads each from the server before the
  sandbox starts ([runner/README.md](../../runner/README.md#skills-protocol-v2)). A run whose
  agent has skills goes only to such a host; while none is connected it waits with a reason that
  says so.
- **Session bundles** ([ADR-0097](../adr/0097-session-bundles.md)). A company host that lists
  `session.bundle`, as current runner hosts do, uploads the runtime's session folders
  when a run ends and restores them when the conversation's next turn runs on it, so a turn
  resumes on any company host. The server sends `run.assign.sessionBundle` only while it keeps
  bundles: set up a store first ([session-bundles.md](session-bundles.md)), under a Pro or
  Enterprise licence ([ADR-0100](../adr/0100-elastic-runners-in-pro.md)). Without both, nothing
  changes.
- **The sandbox bridge** ([ADR-0095](../adr/0095-sandbox-bridge.md),
  [ADR-0100](../adr/0100-elastic-runners-in-pro.md)). A host with `run --sandbox-bridge` or a VM
  per run lists `sandbox.bridge`, which adds no frame; both need protocol 2. Elastic runners are
  Pro's and Enterprise's: a server whose licence does not hold them answers the `hello` with
  `error` `EDITION_REQUIRED` and close code 1008, and the host stops with that message, without
  reconnecting. Run the host with Docker and without `--sandbox-bridge`, or apply a Pro or
  Enterprise key and start the host again.
- **Ready sandboxes** ([ADR-0098](../adr/0098-ready-vms.md),
  [ADR-0099](../adr/0099-pool-settings.md)). A host with a VM per run (`run --executor vm`)
  lists `sandbox.pool` and reports its operator's `--pool-max-ready` in `hello.pool`. An owner
  or admin sets the schedule on the host's card (Runner hosts › Ready sandboxes); the server
  evaluates it every minute (`TINFIRM_RUNNER_POOL_TICK`) and sends the count now and its next
  change in `host.config.pool`. Until a schedule is set, none waits ready. The host reports its
  sandboxes in `host.pool.status`, which the card shows. Such a host may have up to 1000 slots
  (`--max-concurrent-runs`); a host with Docker keeps at most 16.

## Checks

- `run`'s log line `runner host starting` shows `protocol_version`.
- A v2 host's `hello` lists `isolationTiers: ["microvm"]` on a Kata host, which the server
  accepts (it does not store the tier yet).
- The company's audit log has a `tool.called` event per server-executed tool call, and a
  `secret.accessed` event with purpose `run-tool:{runId}` for each.
- After a run, the conversation's outputs hold the files the agent left in `/output`; the run's
  log has one `saved /output/<path> as an artifact` line per file and names every file it
  skipped.
- A finished run says on its page whether it resumed the conversation's session ("Resumed its
  earlier session", or "Started a new session: …" with the reason), and
  `tinfirm_runs_finished_total{resumed, resume_outcome, runtime}` counts it. Many
  `NO_SESSION` runs mean that turns of a conversation land on another host than the turn before.
- A host with a VM per run shows "Ready sandboxes" on its card once it connected; after a
  schedule is saved, its `host.config.applied` carries `pool.readyTarget`, at most its
  `maxReadyCeiling`, and its card shows the ready, starting and busy sandboxes it reported.
- `tinfirm_runner_refused_frames_total{frame, reason}` counts the requests and events the
  limits refused (`rate`, `size`, `budget`, `in_flight`). A steady count of `rate` for one
  company's hosts, or any `size`, points to a host that floods or does not cut its events.
- A run's page shows its tokens as `in · cache read · cache write · out`
  ([prompt-cache.md](prompt-cache.md)).
