# ADR-0041: pi and OpenCode as runtimes, with Tinfirm's ACP adapter for pi

- Status: Accepted
- Date: 2026-10-06
- Sources: the owner's request of 2026-10-06 (support the pi.dev and OpenCode agents); PLATFORM.md §4.1 (an agent's runtime); CONVENTIONS §14.6, §14.8, §14.10; [ADR-0005](0005-acp-default-agent-protocol.md), [ADR-0027](0027-company-model-credentials.md), [ADR-0042](0042-provider-api-keys.md); runner/README.md
- Changes: ADR-0005, which planned OpenCode for M2 on its native ACP: OpenCode runs on it now, and pi joins on an adapter of ours. PLATFORM.md §4.1, where `runtime` was `CLAUDE_CODE` or `CODEX`.
- Amended by: [ADR-0061](0061-opencode-2.md), which moves the OpenCode image to OpenCode 2 (`@opencode/cli`): exact turn totals instead of the last request's, a refused key as `-32000`, OpenCode's kind of a provider error in `data.errorName`, and no `OPENCODE_DISABLE_SHARE`

## Context

- Every run speaks ACP with an adapter in the sandbox image (ADR-0005): `claude-agent-acp` for Claude Code and `codex-acp` for Codex. Both CLIs reach one vendor's models.
- The owner wants two more agents. [pi](https://pi.dev) (`@earendil-works/pi-coding-agent`, 1.0 since 2026-10-01) and [OpenCode](https://opencode.ai) (`opencode-ai`) are coding agents that reach many providers: Anthropic, OpenAI, Amazon Bedrock, Gemini, OpenRouter and more, each with its own API key. Together with [ADR-0042](0042-provider-api-keys.md) they let a company run its specialists on the provider it already pays for.
- OpenCode speaks ACP itself (`opencode acp`). Checked at 1.18.34: it advertises MCP over HTTP (so the run's platform tools reach it in `session/new`), `session/load` (a follow-up run resumes the session), permission requests (which the host answers), `usage_update` with the session's cost, and the turn's tokens in the prompt response, of the last model request only. A refused key ends the prompt with an error that names it (`API key is invalid.`).
- pi speaks ACP only through `pi-acp` (svkozak/pi-acp 0.0.34, an MVP for Zed). Checked: it accepts `mcpServers` but does not pass them to pi and advertises no MCP, so a run would have none of the platform tools (`finish`, `ask`, `request_access`, `save_artifact`, the server-executed tools); it reports no tokens and no cost; it ends a turn whose model request failed with `end_turn`, so a refused key would look like a finished run; and it drops pi's stderr. pi itself has a documented JSON protocol over stdio (`pi --mode rpc`): prompt, abort, session state and statistics (tokens and cost of the session), and an event stream (message deltas, tool executions, retries, `agent_settled`). pi 1.0 reads MCP servers from `~/.pi/agent/mcp.json`, with an `exposure` of `direct` for tools the model sees like built-in ones.

## Decision

**Two runtimes, `PI` and `OPENCODE`, each with a runner image the runner host builds like the others. OpenCode runs on its own ACP agent; pi runs on `tinfirm-pi-acp`, an ACP adapter of ours over `pi --mode rpc`.**

**Images.** `tinfirm-runner-pi:local` pins `@earendil-works/pi-coding-agent` (1.0.4) and holds `tinfirm-pi-acp` (one dependency-free Node module, embedded in the runner host binary with the Dockerfiles); `tinfirm-runner-opencode:local` pins `opencode-ai` (1.18.34) and runs `opencode acp`. Both build `FROM tinfirm-runner-base:local` with the base image's entrypoint, user 1000 and the `NPM_REGISTRY` build argument; neither updates itself (`PI_SKIP_VERSION_CHECK`, `OPENCODE_DISABLE_AUTOUPDATE`), sends telemetry (`PI_TELEMETRY=0`) or shares sessions (`OPENCODE_DISABLE_SHARE`). `images build --runtime pi|opencode|all`; `hello.runtimes` lists a runtime when its image is there, as before.

**`tinfirm-pi-acp`.** It is an ACP agent for the runner host's client only:
- `initialize` advertises `loadSession` and MCP over HTTP.
- `session/new` and `session/load` write the client's MCP servers to pi's `mcp.json` with `exposure: direct` and `autoEnableCodemode: false` (through a temporary file and a rename, so a symlink the agent planted is replaced, not followed), and start `pi --mode rpc --no-approve --session-id <id>`. pi keeps its sessions in the agent's home, which the host keeps for a job's runs, so `session/load` resumes the previous turn's session; one that pi does not have is `-32002`, and the host starts a new session with the full prompt. `--no-approve` keeps a repository's `.pi/` settings, extensions and MCP servers out: they would ask for trust, which nobody answers in a sandbox.
- `session/prompt` sends pi's `prompt` and maps pi's events to `session/update` until `agent_settled`: text and thinking deltas, each tool execution as a tool call with its title, kind, input, output and status, progress every 15 s during a long tool (so the host's inactivity guard sees activity). The response carries the turn's tokens, the difference of pi's `get_session_stats` before and after the turn; a `usage_update` carries the context occupancy and the cost of this process's turns. A failed model request ends the prompt with a JSON-RPC error: `-32000` when the provider refused the key (a 401, an invalid key, AWS's invalid security token), so the host classifies it `LOGIN_EXPIRED`, else `-32603` with pi's message, which the host classifies (rate limits, budgets, context overflow). A length stop is `max_tokens`; a cancelled turn `cancelled`.
- `session/cancel` sends pi's `abort`. pi's stderr goes to the run's log. Extension dialogs are answered "cancelled".
- The provider and model come from `TINFIRM_MODEL_PROVIDER`, `TINFIRM_MODEL` and `TINFIRM_MODEL_BASE_URL`, which the host sets (ADR-0042); a gateway's base URL goes to pi's `models.json` as the provider's `baseUrl`, and is removed again when a later run has none.

**OpenCode's configuration** comes from `OPENCODE_CONFIG_CONTENT`, which the host sets per run: only the credential's provider enabled (OpenCode would otherwise offer its own free models, which need no key and would send the work to a third party), the agent's model as `provider/model`, no updates, no sharing, and for a gateway the provider's `baseURL` and `Authorization: Bearer {env:…}`, OpenCode's own substitution, so the key is not copied into the configuration. OpenCode's Anthropic `baseURL` ends in `/v1` (it appends `/messages`), so the host adds `/v1` to the credential's root, which Claude Code and pi take as it is.

**Usage.** pi's turns report exact totals (`UsageMode::TurnTotals`). OpenCode's prompt response holds only the last model request of the turn, so a turn of several requests reports fewer tokens than it used: the host adds them and marks the run's usage `estimated` (`TurnLastRequest`). OpenCode's cost in `usage_update` is the session's: complete for a new session, and left out after `session/load`, where it would count the earlier runs again.

**Credentials.** pi and OpenCode take a provider's API key, never a subscription: a company model credential of a provider that serves them (ADR-0042), or an API-key credential of a runner host (`anthropic-api-key`, `openai-api-key`), which now serves Claude Code or Codex and pi and OpenCode. A Claude or ChatGPT subscription stays its vendor's CLI's.

**Protocol.** `PI` and `OPENCODE` join `Runtime` in both schemas. In v1 they are an additive change that a server from before them refuses, so a host speaking v1 leaves them out of `hello.runtimes` and out of each credential's runtimes, as it does with `microvm`.

**Errors of other CLIs.** The host's classifier also knows the providers' own wording of a refused key that pi and OpenCode pass on (`API key is invalid`, `Incorrect API key provided`, `API key not valid`, AWS's `UnrecognizedClientException`), and `rate_limit`, `Too Many Requests` and `ThrottlingException` as rate limits.

## Consequences

- A company can run specialists on pi or OpenCode, with any provider of ADR-0042, on the same runner hosts, with the same platform tools, guards, redaction, `/output` and session resume as Claude Code and Codex.
- We own an adapter. It follows pi's RPC protocol, which pi documents as its integration interface, and is covered by tests against a fake pi (`runner/images/pi/test`, run by `cargo test`) and by a smoke test of the real image. A pi release that changes the protocol needs a change of the adapter before the pin moves (ADR-0005's rule: adapter and runtime move together).
- The tool names the model sees are `mcp__tinfirm__<tool>` in pi, as in Claude Code; the prompt names the tools without the prefix, as before.
- OpenCode's token counts are a lower bound, marked `estimated`; budgets that rely on tokens see less than was used. Its cost is reported for a run that starts a session, not for one that resumes it.
- Both CLIs fetch their model catalogues (pi from pi.dev, OpenCode from models.dev) unless a gateway restricts the network: the host sets `PI_OFFLINE=1` for a gateway, as it keeps Claude Code to the gateway; OpenCode has no such switch that keeps its catalogue working.
- The images are larger than the base by about 0.2 GB each; a host that does not want a runtime does not build its image.

## Alternatives considered

- **`pi-acp` as it is.** Rejected: without MCP a pi run has none of the platform tools, so it cannot finish, ask or save an artifact, and the server-executed tools are unreachable; without tokens and cost the usage policies see nothing; and a refused key would end as a success. Contributing MCP, usage and error mapping upstream is the better long-term path; the adapter can go away when `pi-acp` (or pi itself) covers them.
- **pi in-process through its SDK** (a Node host that embeds `@earendil-works/pi-coding-agent`). Not chosen: the SDK does not load pi's built-in extensions (MCP among them) by itself, and couples us to pi's internal classes; the RPC protocol is the documented boundary.
- **A wrapper that writes `mcp.json` and then runs `pi-acp`.** Not chosen: it gives the platform tools but keeps the missing usage and the success on a failed request.
- **OpenCode through a server-side "native runtime"** (PLATFORM.md §14). Not now: OpenCode's own ACP agent covers what a run needs.
