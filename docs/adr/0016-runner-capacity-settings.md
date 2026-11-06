# ADR-0016: Runner capacity settings in the UI, under local ceilings

- Status: Accepted
- Date: 2026-09-27
- Sources: CONVENTIONS §6, §8, §14.2, §14.4, §14.8, §15.1, §15.2, §15.4, §15.7
- Amended by: [ADR-0085](0085-claude-login-on-the-runner-host.md) (a Codex ChatGPT login takes a pool like every credential; nothing is `CREDENTIAL_POOL_FIXED`)
- Amended by: [ADR-0099](0099-pool-settings.md), under which a host's slots go up to 1000 in runner protocol v2 for a host with a sandbox per run (D12 of the Kubernetes runner analysis), a host with Docker and a credential's pool keeping 16, and a company host that keeps ready sandboxes takes its pool's schedule with the desired slots

## Context

- A run starts only when three limits each have a free slot:
  - the agent's `maxConcurrentRuns`, which members set in the UI;
  - the pool of its runner credential, set on the runner host with `credential add --pool` and read-only in the UI;
  - the runner host's slots, set with `run --max-concurrent-runs`. The host reports them in `hello`, but neither the API nor the UI shows them.
- An example: a member sets 3 on the agent and 3 on the credential, but the host runs with 1. The queued run says only "Queued. Waiting for a runner host to pick it up." Nothing shows which limit holds it.
- Changing the host's limit needed a restart of `run`. A restart (SIGTERM) cancels the active runs and pushes their work in progress (CONVENTIONS §15.4 step 12), so an upgrade of the runner host also cancels work.
- The machine owner decides how much of the machine, and of each subscription, Tinfirm may use. The credentials never leave the owner's machine ([ADR-0007](0007-credentials-stay-in-deployment.md)). Subscriptions throttle above about four parallel runs. The pool is bounded to 1–16 today.
- The queue should show why each waiting run waits.
- Only Tinfirm's own runner host binary, `tinfirm-runner-host`, speaks runner protocol v1.

## Decision

**Ceilings on the machine, settings in Tinfirm**
- `run --max-concurrent-runs N` and `credential add … --pool N` become **ceilings**. The machine owner sets them, between 1 and 16. The host reads them when `run` starts and reports them in `hello`, as before.
- Tinfirm stores an optional **desired value** for each runner host and each credential, also between 1 and 16.
- The **effective value** is `min(desired, ceiling)`, or the ceiling without a desired value. A draining host has 0 slots.
  - Tinfirm can lower use below a ceiling, never raise it above one.
  - A desired value above the ceiling is kept. It applies up to the ceiling, and in full once the owner raises the ceiling.
- The agent's `maxConcurrentRuns` (1–10) does not change.
- A Codex ChatGPT login keeps a pool of 1 (its refresh token is single-use). A desired pool for it is `409 CREDENTIAL_POOL_FIXED`. *Amended by ADR-0085: the runs of a Codex login share its `auth.json`, which Codex renews in place and re-reads before each renewal, so the login takes a pool like every credential and nothing is fixed.*

**Live apply, no restart**
- A new server → host message, `host.config`, carries the full desired state and a version number. The server sends it right after `welcome` and after every change.
- A new host → server message, `host.config.applied`, carries the effective values, the ceilings and `draining`. The host sends it as the answer to each `host.config` it applies. It also sends it unprompted when it starts to drain.
- The host applies the values at once:
  - more slots can take the next run;
  - fewer slots never cancel a run; the host just takes no new run until fewer run.
- Ordering and idempotency:
  - every message is the full state;
  - the version increases with every change;
  - the host ignores an older version on the same connection, and forgets the version at every new connection.
- The values of an offline host wait in the database, and the host gets them when it connects.
- The server assigns runs to a host only after its first `host.config.applied` on the current connection. For each limit it uses the last confirmed effective value. A lower desired value counts at once, before the host confirms it.
- `hello` keeps its fields. Its `maxConcurrentRuns` and `poolSize` are documented as ceilings and bounded to 16.

**Who may change it**
- The same people who may delete the runner host: its owner (OWNER, ADMIN or OPERATOR), or any OWNER or ADMIN of the company. For a credential, the owner of its host.
- The endpoints are `PATCH /api/companies/{companyId}/runner-hosts/{runnerHostId}` (`desiredMaxConcurrentRuns`) and `PATCH /api/companies/{companyId}/runner-credentials/{runnerCredentialId}` (`desiredPoolSize`). Each has a `clear…` flag.
- Each change is audited: `runner_host.updated` or `runner_credential.updated`, with the old value, the new value and the new version.

**Visibility**
- `RunnerHost` gains:
  - `maxConcurrentRuns` (effective, 0–16);
  - `maxConcurrentRunsCeiling`;
  - `desiredMaxConcurrentRuns` (nullable);
  - `draining`;
  - `configPending` (the host has not confirmed the latest change yet).

  It keeps `activeRuns`.
- `RunnerCredential` gains `poolSizeCeiling` and `desiredPoolSize` (nullable). Its `poolSize` becomes the effective pool. It keeps `activeRuns`, `status`, `cooldownUntil` and `detail`.

**Why a run waits**
- `Run.queueReason` is null except for `QUEUED` runs. It holds a code (`QueueReasonCode`), `since`, and the facts the UI needs: the host, the credential, `used` and `limit`, `cooldownUntil`, `lastSeenAt`, `retryAt`, `rejectCode` and `message`.
- The dispatcher stores it at each attempt. It records the first check that fails, in this order:
  1. `AGENT_PAUSED`;
  2. `CREDENTIAL_MISSING`, when the credential is gone;
  3. `RUNNER_HOST_OFFLINE`;
  4. `RUNNER_HOST_DRAINING`;
  5. `CREDENTIAL_MISSING`, when the host no longer reports the credential;
  6. `OTHER`, when the credential no longer serves the agent's runtime;
  7. `CREDENTIAL_INVALID`;
  8. `CREDENTIAL_COOLDOWN`;
  9. `AGENT_AT_LIMIT`;
  10. `CREDENTIAL_POOL_FULL`;
  11. `RUNNER_HOST_FULL`.
- Other codes:
  - `ASSIGNED`: the run was sent to the host, which has not accepted it yet;
  - `PREVIOUS_RUN_ACTIVE` and `RUNNER_HOST_REJECTED`: the host rejected the run;
  - `OTHER`: anything else, explained in `message`.
- The server writes the reason only when it changes, and each change is a `run-status` SSE event.
- CONVENTIONS §14.8 has the exact rules and one canonical English text per code, e.g. "Waiting for a free slot on office (1 of 1 in use)." The UI shows that text. The `RUNNER_UNAVAILABLE` comment after 5 minutes reuses it.
- **Waiting for the task's previous run.** At most one run of a task is `QUEUED` or `RUNNING` (CONVENTIONS §14.1). But the host can still execute a run that the server already ended, for example one marked `LOST` during a long disconnect that the host is now cancelling. A new run of the same task would then share its session home and its branch. So the host rejects it:
  - `run.rejected` gains an optional machine-readable `code`;
  - for this case the code is `TASK_RUN_ACTIVE`, and the server shows it as `PREVIOUS_RUN_ACTIVE`;
  - every other code is shown as `RUNNER_HOST_REJECTED`, with the host's reason and the time of the next try.

**Drain restarts**
- SIGTERM and SIGINT keep today's behaviour: cancel the runs, push the work in progress, report `CANCELLED`, exit.
- SIGHUP, or the new command `tinfirm-runner-host drain`, drains the host instead:
  1. It sends `host.config.applied` with `draining: true` and 0 slots.
  2. It rejects new runs with `DRAINING`.
  3. It lets the active runs finish.
  4. It delivers every message, then closes the connection with 1000 and exits with status 0.
- The server shows the host as draining and assigns nothing to it.
- `drain` finds the `run` process through its PID in `run.lock`. It waits for the process to exit, unless `--no-wait` is given.
- With systemd, `ExecReload=/bin/kill -HUP $MAINPID` and `Restart=always` make `systemctl reload` an upgrade that cancels nothing.

**Runner protocol v1, changed in place**
- New messages: `host.config` (server → host) and `host.config.applied` (host → server).
- `hello.maxConcurrentRuns` and `hello.credentials[].poolSize` are documented as ceilings, with a maximum of 16 (`$defs/Capacity`).
- `run.rejected` gains an optional `code`.
- `welcome` is now followed by `host.config`. The server assigns runs only after `host.config.applied`.

### Data model: migration `V014__runner_capacity.sql`

The tables stay tenant tables with their policies and grants (V008, V011); the runtime role already has `UPDATE` on all three. Existing rows get their ceilings from the values they have, cut to 16.

**`runner_host`**

| Column | Type | Null | Meaning |
|---|---|---|---|
| `max_concurrent_runs` | `integer` | no | Changed meaning: the effective value, as last confirmed by the host. On `hello` it is set to the expected value, `min(desired, ceiling)`. It is 0 while the host drains. Default 1 as before. |
| `max_concurrent_runs_ceiling` | `integer` | no | From `hello`. Default 1; the migration copies `least(max_concurrent_runs, 16)`. |
| `desired_max_concurrent_runs` | `integer` | yes | Set through the API |
| `draining` | `boolean` | no | From `host.config.applied`. `hello` resets it to false. Default false. |
| `config_version` | `bigint` | no | +1 on every change of a desired value of the host or one of its credentials. Default 0. |
| `applied_config_version` | `bigint` | yes | The highest `version` the host confirmed. `configPending` is `config_version > coalesce(applied_config_version, 0)`. |

- The migration runs `UPDATE runner_host SET max_concurrent_runs = least(max_concurrent_runs, 16), max_concurrent_runs_ceiling = least(max_concurrent_runs, 16)` before it adds the checks.
- `runner_host_max_runs_ck` is replaced by `CHECK (max_concurrent_runs BETWEEN 0 AND 16 AND max_concurrent_runs <= max_concurrent_runs_ceiling)`.
- `runner_host_max_runs_ceiling_ck CHECK (max_concurrent_runs_ceiling BETWEEN 1 AND 16)`
- `runner_host_desired_max_runs_ck CHECK (desired_max_concurrent_runs IS NULL OR desired_max_concurrent_runs BETWEEN 1 AND 16)`
- `runner_host_draining_ck CHECK (NOT draining OR max_concurrent_runs = 0)`
- `runner_host_config_version_ck CHECK (config_version >= 0)`

**`runner_credential`**

| Column | Type | Null | Meaning |
|---|---|---|---|
| `pool_size` | `integer` | no | Changed meaning: the effective pool, as last confirmed by the host. On `hello` it is set to the expected value, `min(desired, ceiling)`. |
| `pool_size_ceiling` | `integer` | no | From `hello`. Default 1; the migration copies `least(pool_size, 16)`. |
| `desired_pool_size` | `integer` | yes | Set through the API. The `hello` upsert keeps it. |

- The migration runs `UPDATE runner_credential SET pool_size = least(pool_size, 16), pool_size_ceiling = least(pool_size, 16)` before it adds the checks.
- `runner_credential_pool_ck` is replaced by `CHECK (pool_size BETWEEN 1 AND 16 AND pool_size <= pool_size_ceiling)`.
- `runner_credential_pool_ceiling_ck CHECK (pool_size_ceiling BETWEEN 1 AND 16)`
- `runner_credential_desired_pool_ck CHECK (desired_pool_size IS NULL OR desired_pool_size BETWEEN 1 AND 16)`
- There is no database check against a desired pool on `CODEX_CHATGPT_AUTH`. The kind of a label can change when the host reports it again, so only the API refuses it (`CREDENTIAL_POOL_FIXED`).

**`run`**

| Column | Type | Null | Meaning |
|---|---|---|---|
| `queue_reason` | `varchar(32)` | yes | `QueueReasonCode` |
| `queue_reason_details` | `jsonb` | yes | The other fields of the API's `QueueReason` (not `code` and `since`), with the API's names; null fields are left out |
| `queue_reason_since` | `timestamptz` | yes | `QueueReason.since` |

- `run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN ('AGENT_PAUSED', 'CREDENTIAL_MISSING', 'RUNNER_HOST_OFFLINE', 'RUNNER_HOST_DRAINING', 'CREDENTIAL_INVALID', 'CREDENTIAL_COOLDOWN', 'AGENT_AT_LIMIT', 'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED', 'ASSIGNED', 'OTHER'))`
- `run_queue_reason_status_ck CHECK (queue_reason IS NULL OR status = 'QUEUED')`: every update that moves a run out of `QUEUED` clears the reason in the same statement.
- `run_queue_reason_since_ck CHECK ((queue_reason IS NULL) = (queue_reason_since IS NULL))`
- `run_queue_reason_details_ck CHECK (queue_reason IS NOT NULL OR queue_reason_details IS NULL)`

## Consequences

- The UI shows every limit that can hold a run, and which limit holds it now. The case above would read "Waiting for a free slot on office (1 of 1 in use)", next to the host's setting.
- Members change capacity without touching the machine, and without cancelling anything. The machine owner still bounds it, and a desired value can never exceed the owner's ceiling.
- To give members room, the owner starts the host with a generous ceiling, and members lower the value in Tinfirm. Without a desired value the ceiling is the value in use, so the defaults (2 slots, pool 3) behave as before.
- Raising a limit takes effect only after the host confirms it, one round trip later. A lower limit counts at once.
- A connected host whose runs are over their new limit keeps them running. The UI can show more active runs than the effective value until they finish.
- Upgrades can drain instead of cancelling. A drain can take as long as the longest active run (up to `maxWallClockSeconds`, 60 minutes by default), and the host takes no new run meanwhile.
- The dispatcher writes to queued runs when their reason changes. The reason is up to one dispatch interval (2 s) old.
- The server's fake runner in the tests must answer `host.config` with `host.config.applied`, or nothing is dispatched.
- Protocol v1 changes in place, so the server and the runner host must be deployed together. An older host does not answer `host.config`, so the server assigns it nothing, and its runs wait with `RUNNER_HOST_OFFLINE`. An older host started with more than 16 slots sends a `hello` that fails validation (close 1008).
- API change: `RunnerCredential.poolSize` now means the effective pool; the value of `--pool` is the new `poolSizeCeiling`. `RunnerHost` gains required fields. The web client is generated, so the new fields show at build time.

## Alternatives considered

- **A setting in the UI only, without a local ceiling.** Rejected: the server could then run more parallel sessions on a member's machine and subscription than the machine owner agreed to. The owner lends the machine and the subscription ([ADR-0007](0007-credentials-stay-in-deployment.md)), so the owner keeps the last word, and a compromised or mistaken server cannot raise use above it.
- **Restart the host to apply a change.** Rejected: a restart cancels the active runs and pushes their work in progress, which is the problem we want to solve. It also needs shell access to the machine, while members manage everything else in the UI.
- **Hide the host limit and keep only the agent and credential limits.** Rejected: it causes the case above. The host limit exists either way, because a machine can only run so many sandboxes. Hiding it makes a waiting run unexplainable.
- **Deltas or one message per setting instead of the full state.** Not chosen (not discussed): the full state is small, idempotent, and needs no replay after a reconnect.
- **Compute the queue reason when the API reads a run.** Not chosen (not discussed): it would repeat the dispatcher's checks in every read, and would need the live connection state. It also cannot know why the host rejected a run. The dispatcher already has every fact, and storing the result lets the UI follow changes over SSE.
- **Make SIGTERM graceful.** Not chosen (not discussed): `systemctl stop`, Ctrl-C and container runtimes expect a prompt exit, and a drain can take an hour. A separate signal keeps both behaviours.
