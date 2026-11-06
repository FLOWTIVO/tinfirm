# ADR-0073: A turn that failed for a reason that may pass is retried by itself, when its owner chose so

- Status: Accepted
- Date: 2026-10-17
- Decided by: the owner, 2026-10-17
- Sources: [PLATFORM.md](../PLATFORM.md) §8.1 (ending, cancelling and retrying), §11; [CONVENTIONS.md](../CONVENTIONS.md) §15.5 (settings), §15.6 (turns and the queue), §15.14 (package R: the call journal); [ADR-0060](0060-steering-a-running-turn.md) (messages written while a turn is queued); [ADR-0063](0063-agent-memory.md) (an opt-in in the settings); the package R contract ([reviews/2026-10-12-package-r-contract.md](../reviews/2026-10-12-package-r-contract.md))
- Changes: the runner host's failure classes (`limits.rs`); `V051__automatic_retry.sql`; the server's agent module (the setting), conversation module (the retry at a turn's end, `TurnRecorded.retrying`), run module (a turn inserted to wait until its time, `NewTurn.Scheduled`), trigger and chat modules (no failure written while it is retried); the API (`AgentSettings.autoRetry`, `AgentSettingsRequest.autoRetry`, `QueueReasonCode` `RETRY_SCHEDULED`); the MCP endpoint's rendering of a trigger; the web app's settings form and queue reasons

## Context

- A turn that fails stays failed until a person presses **Retry** (`retryConversation`): nothing in Tinfirm retries a turn by itself. The failure class `USAGE_LIMIT` says so in words ("retry the turn then", the package 3 contract's open point 8); every other class is silent.
- Many failures pass on their own. A model provider that is overloaded or rate limited, or a gateway in front of it that has no backend for a minute (`503 no available server`), fails the turn once the runtime's own retries, a few seconds long, are spent. A runner host that restarts loses the runs it ran.
- A trigger's conversation has nobody watching it. A failure at night stays a failure until someone reads the inbox.
- Before this decision the runner classed a provider's or gateway's `5xx` as `PROTOCOL_ERROR`, which reads as a bug in Tinfirm and gives the server nothing to tell a failure that may pass from one that will not.
- A retry is safe since package R: a retry resumes the agent's session, so what the failed turn did is in its history; a write call that Tinfirm lost track of was settled `TOOL_INTERRUPTED`, never run twice, and the next turn's prompt quotes it.
- A retry spends the payer's budget. A person who wants to look at a failure first must be able to say no.

## Decision

1. **The runner classes an unavailable provider or gateway as `SERVER_LIMITED`** ("Provider overloaded"): an HTTP status 500, 502, 503 or 504 standing as a number of its own in the error, the wordings of such an answer (`service unavailable`, `bad gateway`, `no available server`, `no healthy upstream`, …), and Claude Code's error kind `server_error`; after every other rule, so that a spent budget, a refused key and a full context keep their classes.
2. **An opt-in in the settings.** The owner of the settings, a person for their conversations with the agent or a trigger's author for the trigger's, turns automatic retries on (`AgentSettings.autoRetry`, `agent_settings.auto_retry`, off by default; audited in `settings.changed` or `trigger.settings_changed` as `autoRetry`). It is not a widening: it reaches nothing a person's retry could not.
3. **What is retried.** A turn that ended `FAILED` with the class `SERVER_LIMITED`, or `LOST` because its host lost it for good (`HOST_RESTARTED`, `STALE_ON_RECONNECT`), in an open conversation whose settings in effect have the opt-in, while nothing else waits: an open decision, a trigger's firing or a person's message is taken by the turn that follows any failure today, at once, and a paused trigger's conversation takes no turn. No other class: a run lost because its lease expired may still be taken back by its host within the resume window (`TINFIRM_RUNNER_LOST_RESUME_WINDOW`), which a retry's active run would refuse; `USAGE_LIMIT` waits for a budget a person may want to raise or not; `CONTEXT_OVERFLOW`, `LOGIN_EXPIRED`, `VENDOR_BLOCKED`, `CREDENTIAL_LEAK`, `MODEL_REFUSED`, `GUARD_STOPPED`, `TIME_LIMIT`, `GIT_ERROR`, `PROTOCOL_ERROR` and `INFRA` fail the same way again or need a person (the runner's `INFRA` mixes both, and the server's never passes); a cancelled turn was a person's choice.
4. **How often.** At most three automatic retries in a row, the first 1 minute after the failure, then 2, then 4 (`conversation.auto_retries` counts them). Any other turn (a person's message, retry or decision, a firing) counts from nothing again. After the third, the turn stays failed as today, and its `SYSTEM` message says that the automatic retries are spent.
5. **How.** In the transaction that ends the failed turn, the conversation starts a turn `RETRY` started by nobody (`createdBy` null, the actor `SYSTEM`), whose run is inserted `QUEUED` with `dispatch_after` at its time and the queue reason `RETRY_SCHEDULED` (`retryAt`, and its number of how many in `used` and `limit`). The dispatcher passes over it until then, as over a rejected run, and then treats it as any queued run: the budget, the hosts and the switches are checked as for a person's retry. It takes the failed turn's request and resumes the agent's session as a person's retry does ("Your previous turn did not finish. Do what it was asked again.").
6. **What the person sees and does.** The failed turn's `SYSTEM` message says that Tinfirm retries it by itself, in how long, and which retry of three this is. The conversation shows the queued turn with its queue reason and time. **Cancel** cancels the scheduled retry, as any queued turn; the failed turn may then be retried by hand. A message written while the retry waits goes with it when it starts on a host that takes steers ([ADR-0060](0060-steering-a-running-turn.md), decision 1), else waits for the turn after it, as for any queued turn.
7. **The place hears of the last failure only.** While a failure is retried by itself, its place is told nothing (`TurnRecorded.retrying`): no failure comment and no on-failure action on a tracker issue, no failure post in a chat thread. An on-failure action such as a close would otherwise end the conversation and its retry; the issue would read "could not finish this" and then a result. The failure that ends the retries is written as any failure is.
8. **Payment and audit.** A retry is paid as the turn was, by the conversation's payer, and is a run like any other: `run.started` with the actor `SYSTEM` and the details `automatic` true, `attempt` and `retryAt`.

## Consequences

- A provider's or gateway's blip, and a host's restart, no longer need a person: the turn goes on within minutes, on the person's or the trigger's choice.
- A failure that is not one that may pass is unchanged. A turn retried three times in vain costs at most three more turns of the payer's budget; a provider that is down fails each before much is spent.
- A turn lost because its runner host stopped renewing the lease (a host that went away without restarting) is not retried by itself; a later decision may retry it once the resume window has passed.
- `SERVER_LIMITED` now also covers a provider or gateway that is down, not only one that limits; the web app's words ("Provider overloaded") still fit.
- The setting is a new field of the settings and of their `PUT`: a client that does not send it turns it off, as with every field of the settings.

## Alternatives considered

- **Retry without asking.** Simpler, and the most useful for triggers, but it spends a budget without a person's choice. Rejected: the owner asked for a choice.
- **A setting of the company or of the agent.** One switch for everyone, but the payer of a person's conversation is that person, and a trigger's is the one its author chose; they decide what their budget is spent on. Rejected.
- **Retry in the runner host.** The host could prompt the agent again after a failed model request. It does not know the budget, the settings or the queue, and a host that restarted cannot. Rejected.
- **A sweep that starts due retries.** Keeps no queued run while waiting, but needs a table of its own and shows nothing in the conversation while it waits. The queue already waits until a time for a rejected run. Rejected.
- **Resume the turn where the runtime stopped (pi-durable's durable turns).** Needs a runtime of Tinfirm's own (the package R contract, non-goals). A retry that resumes the session keeps most of the work. Not now.
