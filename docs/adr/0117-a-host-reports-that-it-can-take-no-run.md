# ADR-0117: A runner host reports that it can take no run, and the server backs off a run that one host keeps rejecting

- Status: Proposed
- Date: 2026-11-20
- Decided by: the owner, 2026-11-20, who chose the combination of the host's health report and the server's back-off ("Combination of health report of host and server back off"). The back-off (decision 1) and the report (decisions 2 to 9) are built together
- Edition: the core, in every edition. A host that can make no sandbox, and a run that waits for it, exist in every edition; neither is a feature of [ADR-0077](0077-licence-editions-and-seats.md)'s paid list, so nothing of it is under `ee/` and no licence check guards it (CONVENTIONS §15.24)
- Sources: [ADR-0096](0096-a-vm-per-run.md) (a VM per run, the driver protocol and its `verify`); [ADR-0099](0099-pool-settings.md) (`host.pool.status`, a host's report kept for its page); [ADR-0109](0109-runner-version-is-the-release-version.md) (compatibility is the protocol version and its features); [ADR-0116](0116-short-lived-and-revocable-git-tokens.md) (`welcome.features`, `welcome.done`); [CONVENTIONS.md](../CONVENTIONS.md) §8, §14.10, §15.4, §15.6; the [threat model](../threat-model.md) 5.9, 5.12, 5.13, 5.14; [operations/runner-protocol-v2.md](../operations/runner-protocol-v2.md); the code as of this date: the server's `RunnerSessionHandler.rejected`, `RunDispatcher`, `HostSelection`, `QueueReason`, `RunnerConnectionRegistry`, `RunnerWebSocketHandler`, `RunnerFloodControl` and `RunnerHostService`, the runner host's `vm.rs` (`Vms::refusal`, `record`, `check_within`) and `host.rs` (`Shared::admit`), and `protocol/runner-v2.schema.json`
- Amends: none
- Changes: `protocol/runner-v2.schema.json` and the `tinfirm-protocol` crate (the feature `host.health` and the frame `host.health`; no protocol 3); the runner host (a new module that reports the VM driver's state, `vm.rs` `record`, the host's features and the connection's welcome); the server's `runner` module (the frame, the problems kept with the connection, their flood control, the audit, the host's page) and `run` module (placement, the queue reason `RUNNER_HOST_UNHEALTHY`, the release of the runs that waited, the back-off of decision 1); `api/openapi.yaml` (`RunnerHost.problems`, `problemsSince`, `RunnerHostProblem`, the queue reason's code) and the web app (the host's card, the queue reason's words); V088, changed in place since it is not released (the queue reason's code in `run_queue_reason_ck`); CONVENTIONS §8, §14.10, §15.4, §15.6; the threat model (TB5); [operations/runner-protocol-v2.md](../operations/runner-protocol-v2.md), [runner/README.md](../../runner/README.md) and the operator guide's page on runner hosts

## Context

**A host can refuse a run, and only a run.** A runner host answers `run.assign` with `run.rejected {runId, epoch, reason, code}` when it cannot start it. The server puts the run back in the queue with the queue reason `RUNNER_HOST_REJECTED` (or `PREVIOUS_RUN_ACTIVE` for `TASK_RUN_ACTIVE`) and dispatches it again after `TINFIRM_RUN_REJECT_BACKOFF` (10 seconds), to the same host when it is the only one that may take it. Nothing else in protocol 2 lets a host say that it can take no run at all: `host.config.applied` reports slots that the operator and the server set, and draining means that the host restarts.

**A host with a VM per run can be in that state for hours.** Since `d971d69` a host whose VM driver can make no VM (its `verify` reports problems and no room, for example a sandbox node whose firewall table is missing) rejects every new run with `NO_FREE_SLOT` and the cause (`Vms::refusal`, `record`, `check_within`; `Shared::admit`). It asks `verify` again every 30 seconds and takes runs again once the answer is clean or a create succeeds. Meanwhile:

- every waiting run is assigned again about every 12 seconds;
- each assignment writes `run.assigned` and `secret.accessed` to the audit, sends the run's git tokens and its one-run model token to the host again, and raises the run's epoch by two;
- a host that stays in the state for hours fills the audit log and mints tokens all the time;
- the host's page shows it online with free slots throughout, so nobody looking at it learns why turns wait.

The queue reason of each run names the host and the cause, but only for the moment between two attempts.

**What the server keeps about a connection.** The server keeps its runner connections in memory, on one replica (an accepted limit, PLATFORM.md §14): whether a host is connected, whether it confirmed its capacity, which features its `hello` listed. The dispatcher reads these through `RunnerConnections`. The facts a host reports at `hello` (its version, its isolation) and its ready sandboxes (`host.pool.status`) are stored, because the page shows them while the host is offline too.

## Decision

**The server waits longer for a run that the same host rejects again, and a runner host that can take no run says so. A host that lists the feature `host.health` sends `host.health {problems, since}` when it has a problem, at each change, and once when it is healthy again. The server keeps the problems with the host's connection, assigns no run to the host while it has one, shows them on the host's page, and dispatches the runs that waited for the host as soon as it is healthy again.**

1. **The back-off** (built first, in its own change). A run that the same host rejects again waits `TINFIRM_RUN_REJECT_BACKOFF` doubled for each rejection in a row after the first, at most `TINFIRM_RUN_REJECT_BACKOFF_MAX` (default 5 minutes). The count is kept in the queue reason's stored details (`rejections {runnerHostId, count}`, not in the API), so no migration is needed. It stays while the run waits for another reason and when the run goes to the same host again. It is dropped when the run is assigned to another host, when it starts, and when it goes back to the queue for any other cause. `TASK_RUN_ACTIVE` keeps the fixed wait and leaves the count as it was. A configured cap shorter than the back-off is not used: the server logs a warning at start, and the wait stays fixed. The reason's `retryAt` is when the run is tried again (CONVENTIONS §15.6). This bounds the cost of a host of an older release, which has no other way to say that it can take no run: one assignment per run every five minutes.

2. **The feature and the frame.** `host.health` is an optional feature of protocol 2, negotiated as `run.git.revoke` is: a host lists it in `hello.features`, the server in `welcome.features`. It adds one frame, from the host to the server:

   | Field | Meaning |
   |---|---|
   | `type` | `host.health` |
   | `problems` | At most 8 items, each `{code, message}`. Empty: the host takes runs |
   | `problems[].code` | An upper-case token of at most 64 characters from a closed list, today one code: `VM_DRIVER_NO_VM`, the host's VM driver can make no VM (its `verify` reports problems and no room) |
   | `problems[].message` | At most 1000 characters, one line (no control character): the host's own words, today the problems the driver's `verify` named |
   | `since` | When the host's current problems began, by its clock (RFC 3339); present with problems, absent with none |

   A code is added to the list only for a state that the host really detects. A code the server does not know fails the frame's validation, so a host sends a new code only to a server that knows it: such a code comes with a feature name of its own (open question 1).

3. **The host's side.**
   - It sends `host.health` only on a connection whose `welcome.features` lists `host.health`, after `welcome`, and after `welcome.done` where the server sends one (it lists `run.git.revoke`).
   - It sends the frame when it has a problem, at each change of its problems (a code, or a message), and once with an empty list when it is healthy again. While it is healthy, and has been healthy on this connection, it sends nothing.
   - A reconnect starts from "healthy" on both sides: a host that still has a problem sends it again after the new connection's welcome.
   - It sends at most one frame every 5 seconds, the latest state, so that a state that changes fast costs one frame.
   - It keeps rejecting a run that still arrives while it has a problem (a frame on its way, or a server without the feature): the report saves work, and the rejection stays the check.
   - Today only a host with a VM per run reports, and only its driver's state: `Vms::record` sets, changes and clears it. A host with Docker lists the feature only once it detects a state of its own.

4. **The server keeps the problems with the connection, in memory.** The problems are a fact about the live connection, as whether the host confirmed its capacity is. Kept in memory with the connection (`RunnerConnectionRegistry`), they cannot outlive it: a disconnect and a server restart both start from "healthy", which is also where the host starts after a reconnect, so no stale problem can stay on a host's page or keep runs away from it. Stored, they would need a migration, a cleanup after a restart and a rule for a host that is offline. The page shows them while the host is connected; an offline host shows "Offline", which already says that it takes no run. One replica holds the connections (PLATFORM.md §14), so every dispatcher sees the same state.

5. **Placement.** The dispatcher assigns no run to a host that reports a problem (`HostSelection`, after the drain's check: a draining host says so first). A run that only such hosts may take waits with the new queue reason `RUNNER_HOST_UNHEALTHY`: of one host, its id, name, the first problem's code (`reason`) and message (`message`), and the text "Runner host {name} can take no run: {message}."; of several, no host and the text "None of the {n} runner hosts that may run this conversation can take a run now: each reports a problem." No `run.assign` is built while it waits: no `run.assigned`, no `secret.accessed`, no token, no new epoch. An agent's runs still wait for the hosts of their level (ADR-0113) and do not move to a weaker host that is healthy, as they do not for an offline one.

6. **The release.** When a host's problems change from some to none, the server dispatches at once. The runs that wait in part A's back-off after this host's rejections (`RUNNER_HOST_REJECTED` with this host, `dispatch_after` in the future) have their `dispatch_after` and `retryAt` cleared first, so that they go too; their count of rejections stays, as decision 1 says. The same happens when a host's problems change from none to some: those runs then go to another host that may take them, or wait with `RUNNER_HOST_UNHEALTHY`, which names the cause, rather than with an old rejection.

7. **Flood control.** A connection may send at most 12 `host.health` frames a minute (a bucket of 12, one more every 5 seconds). A frame over it is answered `error TOO_MANY_REQUESTS`, as the protocol's other flood controls answer, and is not applied: the server keeps the last state it took. A host that follows decision 3 never reaches it. A frame from a host that did not list the feature in its `hello` is answered `error BAD_MESSAGE` and ignored, as `host.pool.status` is.

8. **Audit.** One event when a connection's state changes from no problem to a problem, `runner_host.health_failed` (the codes, the first message), and one when it changes back, `runner_host.health_restored`. By SYSTEM, with the host as the target, in the chain of each company the host is in. A change among problems, a frame sent again, and a disconnect write nothing. The audit records who could take work and when that changed; the frames themselves are the host's own words, which change with each `verify`, and one event per frame would bring back the volume this record removes. A host whose connection drops and comes back with the same problem writes one `health_failed` per connection, which the reconnect's own back-off bounds.

9. **API and web.** `RunnerHost` gains `problems` (`RunnerHostProblem {code, message}`) and `problemsSince`, present only while the host is connected and reports a problem. Whoever may see the host today sees them: its card shows "Takes no runs" beside "Online", the problems, and since when. The text is the host's: the web app shows it as text, as any other text of a host, and the server bounds it and never interprets it. The queue reason `RUNNER_HOST_UNHEALTHY` gets its words in the web app as the other reasons about a host do.

10. **Compatibility.**
    - A host of an older release does not list the feature: it rejects as today, and decision 1 bounds the cost.
    - A server of an older release does not list the feature in `welcome.features` (or sends none): the host sends no frame, and rejects as today.
    - Nothing needs configuring, and the server and the hosts can be upgraded in either order.

11. **Trust.** A host's word about its own health can only take work away from that host, which a host can do anyway by rejecting each run or by going offline. It cannot move another host's runs, reach another company, or make the server send it anything. The text it sends is shown to people, so it is bounded (8 problems, 1000 characters, one line), escaped by the web app as any other text of a host, and never interpreted. A personal host's problems are seen by whoever sees the host: its owner.

## Consequences

- **A host that can make no VM costs nothing while it says so.** Its runs wait with a reason that names the host and the cause, and its page says why it takes no run. No audit event, token or epoch is spent until it is healthy again.
- **A run starts within seconds of the host's recovery**, not after the rest of a back-off of up to five minutes.
- **Older hosts cost one assignment per run every five minutes** while they reject, in place of one every 12 seconds.
- **The audit gets two events per episode and connection,** not one set per attempt.
- **A host can keep its own runs away.** A host that reports a problem forever gets no run. That is no new power (decision 11), but an operator who sees runs wait must look at the host's card, which now says why.
- **The state is lost with the connection.** After a server restart, a host that still has a problem says so again after its welcome; between the restart and that frame, one run may be assigned and rejected.
- **One replica.** The state lives where the connection does. When the server runs several replicas that hold connections, this state moves with the rest of the connection's state.

## Alternatives considered

- **The back-off alone.** It bounds the cost, but a host out for hours still gets each run every five minutes, its page still shows free slots, and a run waits up to five minutes after the host is fixed.
- **The health report alone.** Hosts of older releases, and a host whose frame is lost, would still loop every 12 seconds. The owner chose both.
- **The server infers the state from rejections.** It would have to guess from codes and counts which rejection means "the host" and which "the run", and it could not learn that the host is healthy again without assigning a run to it.
- **The host drains, or reports zero slots.** Draining means that the host restarts, and the slots are the operator's and the server's setting; both would show the wrong thing on the page and lose the cause.
- **The host closes its connection.** The runs it still runs would lose their channel, and the page would show it offline with no cause.
- **The problems stored in the database.** A stored problem outlives the connection that reported it: after a server restart, or a host that went offline, the page and the dispatcher would hold it until something cleared it (decision 4).
- **Health in every heartbeat.** It would cost a field every 10 seconds for a state that changes a few times a day, and a heartbeat carries the runs, not the host.
- **A free-form code.** The server, the audit and the web app need to know what a code means; a closed list keeps that true (decision 2).
- **One audit event per frame.** See decision 8.

## Open questions

1. **New codes.** Decision 2 makes a new code need a feature name of its own, so that an older server is never sent a frame it refuses. The alternative is a server that accepts any upper-case token and shows it as "a problem the host names", which would let codes grow without a new feature. Decided when the second code is needed.
2. **Other states a host could report.** A host with Docker whose daemon does not answer, or a host whose disk is full, rejects or fails runs today without a report. Each needs its own detection on the host before it gets a code.
3. **Notification.** A host that has reported a problem for a long time could notify the company's admins, as a spent budget does. Not decided.
