# ADR-0099: Pool settings: Tinfirm's schedule of ready sandboxes

- Status: Accepted
- Date: 2026-10-16
- Accepted by: the owner, 2026-10-27, with the fixes of the gaps it left open ([ADR-0101](0101-closing-the-elastic-runners-gaps.md))
- Amended by: [ADR-0100](0100-elastic-runners-in-pro.md): The pool's schedule is part of elastic runners, Pro's and Enterprise's (`Feature.ELASTIC_RUNNERS`); its code is under `ee/`: the server's schedule in `ee/src/main/java`, the host's side under `ee/runner/`.
- Sources: [ADR-0098](0098-ready-vms.md) (ready VMs), [ADR-0096](0096-a-vm-per-run.md), [ADR-0016](0016-runner-capacity-settings.md) (desired values), [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md) (protocol v2 and its features); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §6.4 and §9 (step 6), and its decisions D5, D11 and D12; [threat model](../threat-model.md) 6.18
- Changes: runner protocol v2 gets the feature `sandbox.pool` (`hello.pool`, `host.config.pool`, `host.config.applied.pool`, `host.pool.status`) and a host's slots go up to 1000; the API's `PATCH /runner-hosts/{id}` takes `pool` and `clearPool`, and a company host shows its `pool`; migration V067; the server evaluates schedules every minute (`tinfirm.runner.pool-tick`).

## Context

- With ADR-0098 a host with a VM per run keeps ready VMs, bounded by its operator's `--pool-max-ready`. Their count from Tinfirm (`scheduled`) was 0: only `--pool-headroom` kept any.
- A company wants ready sandboxes while its people work and none at night (D5): "working days 08:00 to 18:00: 2 ready". Teams in several zones want windows in each. Summer time moves a window's instants twice a year.
- A host has no reason to know time zones, and a host whose zone data is old would get the count wrong (D11).
- A host with a VM per run is limited by its provider's account, not by Docker on one machine: 16 slots is too few (D12).

## Decision

**The schedule.** A company host that keeps ready sandboxes has pool settings in Tinfirm:

- `readyDefault`, the count when no window applies, and up to 14 weekly **windows**, each with its days (`MON` … `SUN`), a start and an end (`HH:mm`, 00:00 to 23:59) and its count. A window whose end is before its start ends the next day.
- An IANA time zone for the pool, and optionally one per window. Times are local to it, with its summer time: a time the change to summer time skips moves forward by the gap, and a time the change back repeats takes its first occurrence (`java.time.ZonedDateTime.of`). A window over that night is an hour shorter or longer.
- Where windows overlap, the highest count applies; inside a window its count applies, even when lower than the default (none at night).
- Every count is at most the **pool size**: the desired slots set in Tinfirm, else the host's ceiling. The operator's `--pool-max-ready` applies on the host; a higher count is kept and applies up to it.
- **No settings: none waits ready (D5).** A new host keeps none until an admin opts in. The page offers the preset "working days 08:00 to 18:00, 2 ready" in the admin's own zone.

**The server evaluates; the host gets numbers (D11).**

- The server computes the count now and its **next change** within 7 days, and stores both on the host row (`pool_ready_target`, `pool_next_target`, `pool_next_at`). When they change, `config_version` moves on and a connected host gets its `host.config`.
- It does so at the host's `hello`, after a change of its settings, and on a **tick** every minute on every replica, for the pool hosts connected to that replica.
- `host.config.pool` carries `readyTarget` and `next {readyTarget, at}`. The host follows `next` by itself when its time comes before the server's next word. It also fills a window early: a higher next count applies from the 90th percentile of its last cold starts before `at` (5 minutes before the first), at most `--pool-lead-time-max` (30 minutes).
- The host answers in `host.config.applied.pool`: the count it aims at for Tinfirm, `min(readyTarget, maxReady)` and 0 while draining, and its `maxReadyCeiling`.

**The protocol: feature `sandbox.pool` of v2.**

- A host that keeps ready sandboxes lists it, and sends `hello.pool {maxReady}`. The server sends `host.config.pool` only to such a host, and stores `maxReady` as the pool's ceiling. A host without it shows no pool and its settings cannot be set (`409 RUNNER_HOST_POOL_UNSUPPORTED`), nor can a personal host's.
- The host reports **`host.pool.status`**: ready, starting and busy sandboxes, the 90th percentile of its cold starts, and the runs of the last 24 hours that took a ready sandbox or started cold. It sends it when its sandboxes change, at most every 10 seconds, and after each `host.config`. The server keeps the latest for the host's page and answers nothing; from a host without the feature it is a `BAD_MESSAGE`.

**A host's slots go to 1000 (D12).** In protocol v2 a host's ceiling, desired and effective slots are 1 to 1000; a credential's pool stays at 16. A host with Docker refuses more than 16 at `run`, and a v1 host above 16 is still a `BAD_MESSAGE`.

**API and audit.**

- `PATCH /companies/{companyId}/runner-hosts/{id}` takes `pool` (the settings) or `clearPool`, alone or with the desired slots; an owner or admin sets them. Settings that cannot be a schedule are `400 VALIDATION_FAILED` with the reason: an unknown zone, a window without days, a start equal to its end, a count above the pool size, more than 14 windows.
- A company host shows `pool`: its settings, the ceiling, the count now and next, and the week (the count now and every change of the next 7 days, computed with the zone rules so that a client needs none), and the host's last report.
- A change is audited once as `runner_host.updated`, with `changed: pool`, `oldPool`, `newPool` (the settings, never a person's data) and the new `configVersion`, together with the slots when both changed.

## Consequences

- Ready sandboxes follow working hours across summer time without the host knowing a zone; the JDK's zone data, updated with the server, decides.
- A host that loses the server keeps following the last `next` it got, so a window still ends on time; after 15 minutes offline it keeps none (ADR-0098).
- A schedule change reaches a connected host at once; a change of the count by the clock reaches it within a minute through the tick, and on time through `next`.
- Tinfirm's count can never exceed what the operator allows: `maxReady`, the free slots, and the provider's account limit that `run` checks.
- The page draws the week from the server's change points, in the reader's own zone.
- The tests cover the schedule (summer time in Prague and New York, overnight windows, two zones, the refusals), the API with its checks, roles and audit, `host.config.pool` and its confirmation, `host.pool.status` on the page, and the tick; on the host, Tinfirm's count, the early fill and following `next` without the server.

## Alternatives considered

- **Cron expressions,** as triggers have. Rejected for this: a window needs a start and an end, and an admin thinks in "working days 08:00 to 18:00".
- **The host evaluates the schedule.** Rejected (D11): every host would need current zone data, and a stale one would be wrong for an hour after a change of the rules.
- **The server sends the whole week.** Not needed: the count now and the next change keep a host on time between two ticks, and the server stays the only one that reads the schedule.
- **A schedule per runtime or shape.** Not now: the pool serves one shape (ADR-0098).
