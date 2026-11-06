# ADR-0004: Event-driven wakes instead of LLM heartbeats

- Status: Accepted
- Date: 2026-09-26

## Context

Timer wakes that start a full LLM session only to check if there is work cost tokens on every idle agent: one public measurement puts an idle heartbeat at about 180k input tokens. Idle agents therefore cost money.

## Decision

- The platform decides in SQL if an agent has work. An idle agent uses no tokens.
- A wake comes only from these sources:
  - a task is assigned;
  - a comment or @mention is added;
  - an approval is decided;
  - a blocker is resolved;
  - a PR or CI webhook arrives;
  - a routine fires;
  - a member clicks "run now";
  - a platform timer fires.
- Only the platform sets timers: retry backoff, usage-limit resets and `wake_at` on a task. db-scheduler runs them.
- The worker starts a run only if all conditions hold:
  - the task is assigned to the agent and is `todo` or `in_progress`;
  - the task is not blocked and not waiting on a human;
  - no run is active for the pair (agent, task);
  - the agent is under its concurrency limit and its budget;
  - the agent's credential is not in cooldown and has a free slot.
- **Coalescing.** A wake during an active run sets `followup_pending` with the merged reasons. The transaction that ends the run locks the task row, reads and clears the flag, and creates at most one follow-up run.
- Lease renewal is process liveness in the runner host, not an LLM call.

## Consequences

- Idle agents spend zero tokens.
- An agent cannot schedule free-form check-ins. A later follow-up needs `wake_at` on a task, or a routine.
- A routine run creates a task first, so all work is visible on the board.
- Every wake source must be modelled as an event. A source that is not modelled does not wake an agent.
- Wakes carry an idempotency key and are coalesced, so duplicate events do not create duplicate runs.
- A run that waits for a human parks after 10 minutes. The answer creates a new wake.

## Alternatives considered

- **LLM heartbeats on a timer**. Rejected: token burn on idle agents.
