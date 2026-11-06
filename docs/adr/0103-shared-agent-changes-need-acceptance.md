# ADR-0103: A shared agent's instruction and skill changes need acceptance where the agent can act

- Status: Accepted
- Date: 2026-10-27
- Decided by: the owner, 2026-10-27
- Amends: [ADR-0058](0058-authority-from-the-audience.md) (widening is accepted, narrowing is not; instructions and descriptions were not widening); [ADR-0064](0064-skills-library.md) (skills followed the current version for everyone)
- Sources: [the security review of 2026-10-27](../reviews/2026-10-27-security-review.md), D1 and I5, §8.6 points 4 and 7; [PLATFORM.md](../PLATFORM.md) §2 principle 1 and §6; the [threat model](../threat-model.md), 1.30, 9.11, R63, Q31
- Changes: `agent_version.instructions` and `.skills` (`V080`); `AgentDefinition.canAct`, `Widening.instructions`, `Widening.skills`, `AgentVersions.effective`, `EffectiveAgent.content`; the audit action `agent.instructions_changed`, and `agent.widened` with `instructions` and `skills`; the conversation's `SYSTEM` notice of a change (`conversation.agent_content_version`); the API's `AgentWidening` with `instructions` and `skills`; the web app's line diff of the instructions on the widening cards and the editor's notice; the trigger's widening asked only for a version that widens

## Context

- A person accepts a shared agent's ceiling once: its systems, tools and modes, repositories and hosts. Whoever edits the agent (its owner, an admin) then changed its instructions and its skills for everyone's next turn, unaccepted and unannounced, and those instructions steer what the agent does with each person's own accounts inside the ceiling they accepted, the OWNER's included.
- The ceiling is the limit of the damage; the instructions are the damage. An agent shared with many members is the highest-leverage object of a company, and the design treated its text as harmless (the review's D1; I5 asked for an accepted-risk row if that was intended).

## Decision

**An agent's instructions and the skills it pins are part of its version. Where the ceiling after a change can act, a change of them is a widening, accepted per person and per trigger author with a diff; where it cannot, the change reaches everyone's next turn, audited and announced.**

1. **The version keeps what the agent is told.** Each version keeps its instructions and the versions of the skills it pins (`agent_version.instructions`, `.skills`, `V080`, back-filled from the agent).
2. **"Can act".** The ceiling after the change can act when it has a tool `ALLOW` chosen for a write, a `WRITE` repository or a network host (`AgentDefinition.canAct`).
3. **Where it can act, the change widens.** A change of the instructions, a skill added or a pin moved is a widening (`Widening.instructions`, `Widening.skills`; `agent.widened` with `instructions` or `skills` `changed`). Each person and each trigger's author sees the instructions' diff and the skills' versions and accepts them; until then their turns keep the accepted version's instructions and skills, less the skills since removed (`AgentVersions.effective`). Only the trigger's author accepts for a trigger.
4. **Where it cannot act, the change is announced.** It reaches everyone's next turn, audited as `agent.instructions_changed` (`agentId`, `version`, `by`, `changed`), and a conversation's next turn that uses other instructions or skills than its turn before posts a `SYSTEM` message: "The agent's instructions changed on {date} by {name}." Such an agent only reads and asks, so what it is told can lead it to read with the person's accounts and to propose writes a person approves, no more (R63).
5. **What never widens.** The description, the name and the runtimes. A skill removed narrows and reaches everyone at once.

## Consequences

- PLATFORM §2's first principle gains its second clause: the definition an agent acts by is accepted by the people it acts for. PLATFORM §6 and CONVENTIONS §15.5 change with it ([the record of the fixes](../reviews/2026-10-27-security-review-fixed.md), §3).
- People see more acceptance cards: every instruction edit of an agent that can write. The diff on the card is what makes them cheap to answer.
- A trigger's runs keep the accepted version's instructions until its author accepts, so an admin who edits an agent learns from the trigger's page who still has to accept.
- The threat model: 9.11 extended to instructions, 1.30 and Q31 restated, R63 for the remainder.

## Alternatives considered

- **Every instruction change widens.** Simplest to explain, but an agent that only reads would ask everyone to accept each wording fix, and acceptance would become a reflex.
- **Audit and announce only.** Leaves the case that matters, an agent that can write with people's accounts, to whoever edits it.
- **Let a person pin the version they reviewed.** Equivalent where the agent can act, and more work for everyone where it cannot.
