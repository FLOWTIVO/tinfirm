# ADR-0003: PostgreSQL as the only state store and queue

- Status: Accepted
- Date: 2026-09-26
- Sources: CONVENTIONS §5, §7

## Context

A run must never fail silently.

The platform needs a run queue, timers, durable commands, an outbox, and leases with fencing. Operators self-host it, so every extra stateful service is a cost.

## Decision

PostgreSQL 18 is the only stateful service, besides object storage for large blobs.

| Need | Mechanism |
|---|---|
| Run queue | Own table, dequeued with `FOR UPDATE SKIP LOCKED` |
| Cron and timers | db-scheduler |
| Outbox | Spring Modulith event publication registry |
| Wake-up hints | `LISTEN`/`NOTIFY`, only as a hint |
| Durable commands | `run_command` table, acked by the runner host |
| Run state and leases | Run state machine with lease epochs |
| Run events | `run_event`, range-partitioned by the run's start month |
| HTTP sessions | Spring Session JDBC (CONVENTIONS §5) |

PostgreSQL is a real service. Compose and Helm are the only install paths.

## Consequences

- One backup and restore procedure covers all state: point-in-time recovery.
- All worker replicas are active. There is no leader election.
- `NOTIFY` can be lost. The tables are the truth, so a lost hint only delays work.
- We own the run state machine, the fencing and the reaper.
- Queue throughput is bounded by PostgreSQL. A Postgres-only state machine is enough, because the long step of a run is an opaque subprocess.
- Server replicas need no sticky sessions (CONVENTIONS §5).

## Alternatives considered

- **Temporal.** Rejected: too heavy to self-host.
- **Restate.** Rejected: its server licence is BSL. The core stays free of BSL and SSPL components.
- **DBOS Java** (MIT, 1.1). Kept as a later option.
- **Embedded PostgreSQL**. Rejected.
- **A separate message broker or session store.** Not discussed. It would add a stateful service with its own backup and upgrade path.
