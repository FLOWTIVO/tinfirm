# ADR-0061: OpenCode 2 in the OpenCode runner image

- Status: Accepted
- Date: 2026-10-15
- Decided by: the owner
- Sources: [ADR-0041](0041-pi-and-opencode-runtimes.md) (the OpenCode runtime, checked at 1.18.34); [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md) (the model proxy); OpenCode's migration guide ("Migrate from V1", `services/www/src/docs/content/migrate-v1.mdx` at tag v2.0.29 of anomalyco/opencode) and its source at v1.18.34 and v2.0.29; runner/README.md
- Changes: ADR-0041's OpenCode image, usage accounting and error wording (below); [ADR-0062](0062-runner-host-rebuilds-its-images.md) makes the host rebuild the image after the upgrade

## Context

- OpenCode 2 is a new npm package, `@opencode/cli` (2.0.0 on 2026-09-11, 2.0.29 on 2026-10-14). `opencode-ai` stays on 1.x (1.19.0 on 2026-10-11), so raising `OPENCODE_VERSION` alone never reaches 2.
- OpenCode names three breaking changes: the plugin API, the server's HTTP API, and the terminal client's configuration. Tinfirm uses none of them. It does use `opencode acp`, which OpenCode 2 rewrote on a new ACP SDK (0.21 → 1.6): `opencode acp` now starts OpenCode's server as a child (`opencode serve --stdio --port 0`, on 127.0.0.1) and talks to it over HTTP.
- What a run relies on, as OpenCode 2's source and image show:
  - Unchanged: `OPENCODE_CONFIG_CONTENT` in the v1 shape that the host writes (`autoupdate`, `share`, `enabled_providers`, `model`, `provider.<id>.options.{baseURL,headers}`, `{env:…}`), which OpenCode 2 reads as it is; `loadSession` and MCP over HTTP in `initialize`; `configOptions` in `session/new` and `session/load`, with the history replayed before the load's answer; the thought-level option (`effort`, with `default`); the permission options `once`, `always` and `reject`; the stop reasons; `usage_update` with the session's cost; the providers' key variables; the default permissions (allow, ask for `.env`); an unknown session is `-32602 session not found`.
  - Changed: the prompt response's `usage` sums every model request of the turn (OpenCode 1: the last request only). A refused key is `-32000 provider authentication required` (OpenCode 1: `-32603 … API key is invalid.`). Any other provider error is `-32603` with the provider's own message and OpenCode's kind of it in `data.errorName` (`provider.quota`, `provider.rate-limit`, `provider.no-route`, …), after OpenCode retried a rate limit or a transport error (ten times, about 85 s). `opencode --version` prints `opencode v2.0.29`. The server's logs go nowhere unless `OPENCODE_PRINT_LOGS=1`. `OPENCODE_DISABLE_SHARE` is gone; OpenCode 2 cannot share a session.
  - Gone for the agent: language servers (the configuration is accepted, nothing runs), and the `CLAUDE.md` fallback: OpenCode 2 reads only `AGENTS.md`.
- OpenCode 2 keeps its sessions in the same SQLite file as OpenCode 1 (`~/.local/share/opencode/opencode.db`, in the session home the host keeps for a job's runs). It migrates the file in place on its first start, and imports OpenCode 1's sessions in the background.

## Decision

**The OpenCode image pins `@opencode/cli` 2.0.29; the host works with OpenCode 1 and 2 alike.**

1. **Image.** `npm install @opencode/cli@${OPENCODE_VERSION}`; the version check takes the last word of `--version` without its `v`, as OpenCode's own installer does. `OPENCODE_PRINT_LOGS=1` with `OPENCODE_LOG_LEVEL=WARN`: the warnings and errors of both processes reach stderr, which the host shows in the run's log and the failure text, while stdout stays ACP's. `OPENCODE_DISABLE_SHARE` goes; `share: "disabled"` stays in the configuration.
2. **Configuration.** Unchanged, in OpenCode 1's shape, which both versions read: an image the host has not rebuilt yet still runs.
3. **Usage by the version the agent names.** `initialize`'s `agentInfo.version` decides: OpenCode 2 reports exact turn totals (`UsageMode::SessionTurnTotals`, not `estimated`); OpenCode 1 keeps `TurnLastRequest` (`estimated`). Both report the session's cost, which the host leaves out after `session/load`, as before.
4. **Errors.** `-32000` stays `LOGIN_EXPIRED`. After the texts of a usage limit (a gateway's budget among them), `data.errorName` decides: `provider.quota` is `USAGE_LIMIT`, `provider.rate-limit` `SERVER_LIMITED`, `provider.auth` `LOGIN_EXPIRED`; any other kind goes on to the message, as before. A model the gateway does not serve stays a protocol error, which the server's `MODEL_NOT_SERVED` rule reads.

## Consequences

- OpenCode runs report exact token totals; budgets see what was used.
- Errors are classified by OpenCode's kind of them, not only by the provider's wording.
- A follow-up run in a home of OpenCode 1 resumes its session once OpenCode 2 imported it. The import runs in the background after the server starts, so the first `session/load` may not find the session yet; the host then starts a new session with the full prompt (`NO_SESSION`), as for a pruned home. The migration of the file is one-way: going back to OpenCode 1 over a home that OpenCode 2 opened is not supported; delete OpenCode's directories in the homes (or the homes) when you roll back.
- A sandbox runs two OpenCode processes (`acp` and its server); both count against the sandbox's memory and process limits (4 GiB and 1024 by default).
- A failed model request ends about 85 s later than with OpenCode 1, after OpenCode's retries; the retries send `session_info_update`s, so the inactivity guard sees activity.
- A repository whose agent instructions are only in `CLAUDE.md` loses them under OpenCode; it needs an `AGENTS.md` for OpenCode runs.
- The image is about as large as before: the npm package installs the binaries of all four Linux variants of the CPU (about 0.8 GB), as `opencode-ai` did.

## Alternatives considered

- **Stay on OpenCode 1.** Possible for now: `opencode-ai` still ships 1.x releases. Not chosen: OpenCode 2 is where OpenCode's fixes go, and its usage report is exact.
- **The native OpenCode 2 configuration** (`providers`, `permissions`, `update`). Not chosen: OpenCode 2 reads the v1 shape without warning, and a host whose image is not rebuilt yet still runs OpenCode 1, which reads only that shape.
- **Usage by the image's version label** (`dev.tinfirm.runtime-version`). Not chosen: `initialize` names the agent that actually runs, also under an image override.
