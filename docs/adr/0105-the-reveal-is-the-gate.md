# ADR-0105: The reveal of a conversation's credential is the one gate

- Status: Accepted
- Date: 2026-10-27
- Decided by: the fix plan of [the security review of 2026-10-27](../reviews/2026-10-27-security-review-plan.md) (WP3), which the owner approved with the plan on 2026-10-27; the review's D5, D6 and §8.6 points 5 and 6
- Sources: [the security review of 2026-10-27](../reviews/2026-10-27-security-review.md), M2, M6, D5, D6 and §8.5 ("the reveal as the invariant"); [ADR-0058](0058-authority-from-the-audience.md) (one account per system per conversation, checked at every use); [ADR-0072](0072-system-credentials-lent-to-groups-and-service-accounts.md) (credentials lent to groups and service accounts); [ADR-0092](0092-personal-runner-hosts-in-every-company.md) (personal hosts run only their owner's work); [CONVENTIONS.md](../CONVENTIONS.md) §15.7, §15.13; the [threat model](../threat-model.md), §6.7, 5.14, 8.37
- Changes: `ConversationBindings.use` answers `BindingUse.Held` under a kill switch (`ConversationHolds`); `ParkedCalls`, `ApprovedCalls`, the outbox, a turn's git tokens and a change request handle it; the company's stop refuses the parked calls that have not started (`ConversationSwitches`, `COMPANY_STOPPED`); `Hosts.personalHostMayRun`, one predicate for the dispatcher and for `TurnSource.content`, with `RunPlacementStale` and the run taken back to the queue; `409 AGENT_NOT_USABLE` at a message and a retry; CONVENTIONS §15.7 and §15.13

## Context

- The design states its rules per feature and the code enforced them per path. The company's stop, a paused trigger and a paused agent were each consulted by the dispatcher, the approved calls, the outbox's claim, the pollers and the firings on their own; the parked calls did not, so an admin's stop during an incident let parked writes run once a credential was signed in again (M6).
- The rule that a personal host runs only work whose every git account is its owner's own was checked at dispatch and not when the workspace's git tokens were revealed, outside the dispatch transaction; a repository bound through a group grant could join in between, and a company credential's token went to a member's machine (M2).
- Every new path that uses a credential had to remember every switch. The one place every rule can hold at once is where a secret leaves the vault for a use (the review's §8.5).

## Decision

**A conversation's credential leaves the vault only through `ConversationBindings.use`, which checks every condition of its use there, in order. Every other check of the same conditions is an early exit, never the control.**

1. **One path.** `ConversationBindings.use(binding, purpose)` is the only path to a conversation's token: a workspace's git token, a run's tool call, an approved or parked call, a change request, a trigger's write-back.
2. **The binding's check first:** the system is not paused and, for a personal server, personal servers are on; the acting principal and the holder are `ACTIVE`; the grant and the member's group membership, or the use right and the lending, still exist; the credential is `OK`, enabled and the same identity. A failure records the binding `WAITING` or `GONE`; it never binds again. A binding that waits or is gone is answered so first, so that the outbox still drops a row whose binding is gone.
3. **Then the kill switches:** the company's runs are not stopped, the conversation's trigger is not paused, its agent is not paused. Otherwise the answer is `BindingUse.Held`, which reveals nothing and changes nothing, for every purpose.
4. **Then the host kind**, in `TurnSource.content` for a run placed on a personal host: the holder of every git account it reveals is the run's payer (`Hosts.personalHostMayRun`, the predicate the dispatcher's eligibility asks); otherwise `RunPlacementStale`, and the run goes back to the queue unsent.
5. **Beside the gate.** The company's stop refuses every parked call that has not started. A personal conversation's person must still be able to use its agent at every message, retry and plan (`409 AGENT_NOT_USABLE`; the turn waits).
6. **Early exits.** The dispatcher's, the outbox's claim, the approved and parked calls', the polls' and the firings' checks stay: they say why sooner and in the thread, and they are not what keeps a credential from being used.

## Consequences

- A new path that reveals a conversation's credential through `use` inherits the switches and the host rule without repeating them; a path that reveals around it is a defect a reviewer can name.
- Secrets that act for no conversation (a trigger's poll, a chat bot's token, a catalogue's refresh, a webhook's secret, a principal's LiteLLM key at the relay) are outside the gate and keep their own checks; the company's stop reaches the polls and firings through theirs (CONVENTIONS §15.13).
- A run whose workspace changed between its plan and its reveal on a personal host costs a requeue, never a token on the wrong machine.
- The threat model names the gate in §6.7 and its list of checks; 5.14 and 8.37 point to it.

## Alternatives considered

- **Keep the checks per path and add the missing ones** (the parked calls', the reveal-time host check). Fixes M2 and M6 and leaves the next path to remember every switch.
- **A gate at the dispatcher only.** The reveal happens later and outside its transaction (M2), and the parked calls and the outbox run without a dispatch.
