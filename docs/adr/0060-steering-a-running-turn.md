# ADR-0060: A person's message reaches the running turn (steering)

- Status: Accepted
- Date: 2026-10-13
- Decided by: the owner
- Sources: [PLATFORM.md](../PLATFORM.md) §8.1 and §11.1; [CONVENTIONS.md](../CONVENTIONS.md) §15.7 (messages and turns); the runner protocol ([runner-v2.schema.json](../../protocol/runner-v2.schema.json), `$defs/RunSteer`, `$defs/RunSteerAck`, `hello.features`); the steering extension of the pinned ACP adapters (claude-agent-acp 0.87.0, codex-acp 1.13.1: `_session/steering`) and pi's RPC `steer` (pi 1.0.4)
- Changes: the runner protocol 2 (an optional feature, no new version); the runner host; Tinfirm's pi adapter (1.1.0); the server's conversation and run modules; the API's `ConversationMessage.steering`; the web app's thread and composer

## Context

- A conversation runs one turn at a time (I11). A person's message written while a turn runs waits for the next turn (`waiting`), so a correction ("use the staging database, not production") reaches the agent only after it finished the work the correction was about.
- Claude Code and Codex take such a message into the running turn: after the agent's current step (a tool call), before its next model request, without cancelling the turn. Their ACP adapters, which the runner images pin, expose this as the extension request `_session/steering` (`{sessionId, prompt, _meta.steering.idleBehavior}`, advertised in `initialize`'s `_meta.steering.supported`), answered `injected`, or `promptRequired` when no turn runs. pi has the same in its RPC (`steer`, delivered after the current tool calls). OpenCode has none.
- Tinfirm's turn is one ACP `session/prompt` on the runner host; only the host talks to the agent. The server knows the person's message and who wrote it; the host knows whether the agent's prompt runs and whether its agent can steer.

## Decision

1. **A message written while a turn runs goes to that turn**, when its run is RUNNING on a connected host that takes steers: the server sends `run.steer` (the message's id as `steerId`, and a text it renders: who wrote it, the words as a quote, and how to take them, including calling `finish` again) after the transaction that stored the message, and the message is `SENDING`. A message that answered open questions does not: `# Answers` of the next turn tells it. The messages written while the turn was queued go to it when its run is accepted.
2. **The runner host answers each steer once** with `run.steer.ack`, before the run's `run.finished`: DELIVERED when the agent has the text in the turn it runs, REFUSED with a code (`NOT_SUPPORTED`, `NOT_RUNNING`, `FAILED`) otherwise. Before the prompt is sent, the host appends the text to the prompt (every runtime takes that); while the prompt runs, it sends `_session/steering` with `idleBehavior: promptRequired`, when the agent advertises steering, and maps `injected` to DELIVERED; once the prompt is over or a stop was asked, it refuses.
3. **What the turn took is the turn's.** DELIVERED makes the message the run's (`run_id`, `steering` DELIVERED), as the messages of its request are: a retry takes it as its request; the next turn's resume prompt leaves it out, since the agent's session holds it. What the turn did not take, and what had no answer when the turn ended, waits for the next turn as before.
4. **An optional feature, not a new protocol version.** A v2 host lists `run.steer` in `hello.features`; the server sends `run.steer` only to such a host, and a host of an older version keeps today's behaviour. `run.steer` is not sent again after a reconnect: a message whose answer is lost waits for the next turn.
5. **The person sees where the message is**: "Sending to {agent}…" while it goes, "{agent} took it while working" under the turn that took it (the live one, or a finished one, between its work and its answer), "Waits for the next turn" otherwise. The composer says that the agent takes the message after its current step, or in the next turn.

## Consequences

- A correction reaches Claude Code, Codex and pi within one step of the agent, without a cancel, a lost turn or a second run. OpenCode keeps waiting for the next turn until its ACP agent has steering; a message written before its prompt starts still goes with the prompt.
- The person's text enters the sandbox while the turn runs, as the prompt's request does; nothing else does, and the trust is the request's (the person is the one who asks). No credential or system content is added.
- Narrow races remain, each erring towards telling the agent twice rather than not at all: a steer that an agent took in the moment its turn ended, and whose answer the host did not read within its grace (0.5 s), is refused, and the next turn's request repeats it; codex-acp, which has no `promptRequired`, starts a turn of its own for a steer that finds none, in a sandbox that is about to stop. The opposite error (DELIVERED, but the agent never read it) needs an adapter that answers `injected` and then drops the message; the pinned adapters settle a turn only after a steered message is answered.
- A host that is disconnected when a message is written does not get it; the message shows "Sending…" until the turn ends, and then waits for the next turn.

## Alternatives considered

- **Cancel the turn and start a new one with the message** (Codex's first form of steering). Loses the step in progress, and with it a running command's output; a cancelled turn in Tinfirm leaves its waiting messages unanswered and may push work in progress. Rejected.
- **Inject the message into the next model request** at the model proxy or the relay. Works for every runtime that uses a gateway, but the runtime's own history never holds the message, so it must be injected again into every later request, in each API's format, and it fails for subscriptions, which bypass the relay. Rejected.
- **Append the message to the result of the next platform or system tool call** (the host's MCP server). Reaches the agent only when it calls one of Tinfirm's tools, which a turn spent in its own shell may never do. Rejected; appending to the prompt before it is sent is the one place every runtime reads.
- **A new protocol version** for two frames. Every frame of v2 stays as it is; an optional feature keeps older hosts working without a version negotiation of their own.
