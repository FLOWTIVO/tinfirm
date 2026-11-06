# ADR-0005: ACP as the default agent protocol

- Status: Accepted (built for Claude Code, Codex, OpenCode and pi, [ADR-0041](0041-pi-and-opencode-runtimes.md); not built: the `codex-app-server` adapter, since a ChatGPT login runs on `codex-acp` with the host's `CODEX_HOME`; the other native-ACP runtimes; the weekly job that moves to new versions)
- Date: 2026-09-26
- Amended by: [ADR-0041](0041-pi-and-opencode-runtimes.md), under which OpenCode runs on its own ACP agent now, not in M2, and pi joins on Tinfirm's ACP adapter; [ADR-0085](0085-claude-login-on-the-runner-host.md), under which a Claude subscription is Claude Code's own login in a directory of the host, mounted into the sandbox, not a `setup-token` token

## Context

Adapters that parse a vendor CLI's output break when the CLI updates. The root cause is unpinned host CLIs that the adapters parse as black boxes.

The platform must run Claude Code and Codex in M1, and Gemini CLI, OpenCode, Goose, Cursor, Copilot and Cline in M2.

## Decision

- The Agent Client Protocol (ACP) v1 over stdio is the default protocol for every runtime.
- The runner host has one ACP client, built on the ACP Rust SDK 1.x. The bridge carries the ACP stream between the adapter in the sandbox and the runner host.
- Runtimes and adapters:

  | Runtime | Adapter | Credentials |
  |---|---|---|
  | Claude Code | `claude-agent-acp` (on the Claude Agent SDK) | API key, or a subscription through `setup-token` |
  | Codex | `codex-acp` (wraps `codex app-server`) | API key, or a Business/Enterprise access token |
  | Codex on ChatGPT Plus/Pro | `codex-app-server` adapter: `codex app-server` JSON-RPC, `chatgptAuthTokens` mode | Access token that the runner host supplies |
  | Gemini CLI, OpenCode, Goose, Cursor, Copilot, Cline | Native ACP | Per runtime (M2) |

- The `codex-app-server` adapter exists only because `codex-acp` does not accept tokens that the host supplies. We propose that support upstream. When it lands, the adapter goes away.
- Reliability rules:
  - The adapter and the runtime are pinned together in the image tag. Auto-update is off (`DISABLE_AUTOUPDATER=1`).
  - A weekly job moves to new versions only if contract tests on recorded ACP transcripts and a smoke test with a small budget pass.

## Consequences

- The platform has one protocol client instead of one per vendor. The adapter maintainers absorb CLI changes.
- Tool approvals use `session/request_permission`. No MCP permission-tool workaround is needed.
- Claude runs on the official Agent SDK, not on the undocumented stream-json control protocol.
- Usage comes from `usage_update` and `PromptResponse.usage`.
- ACP gaps need our own handling:
  - Rate limits are outside the standard. A vendor-extension reader maps `_meta["_claude/rateLimit"]` to our `rate_limit` event. A limit event that arrives before the first usage is dropped (claude-agent-acp #1127). The `usage_limit` error still arrives.
  - `codex-acp` reports only the last request in `PromptResponse.usage` (codex-acp #447). The runner host sums the turns.
- The Codex Plus/Pro path is a second adapter, on an experimental API. The fallback is one serialized `auth.json` with a pool of 1.
- The M1 spike must verify, before the adapters are built:
  - `claude-agent-acp` with `CLAUDE_CODE_OAUTH_TOKEN`, `session/load`, `usage_update` and the rate-limit `_meta`;
  - `codex-acp` with `CODEX_ACCESS_TOKEN`;
  - the subprocess env scrub;
  - Codex `chatgptAuthTokens` under 2 parallel sandboxes.

## Alternatives considered

- **One adapter per vendor CLI that parses its output**. Rejected: breaks on CLI updates.
- **Claude's stream-json control protocol.** Rejected: undocumented.
- **`codex app-server` for every Codex run.** Not chosen. It stays only for the Plus/Pro case that `codex-acp` cannot serve.
