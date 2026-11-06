# ADR-0007: Credentials stay in the deployment

- Status: Accepted.
- Date: 2026-09-26
- Amended by: [ADR-0085](0085-claude-login-on-the-runner-host.md), under which the Claude Pro/Max credential is Claude Code's own login (`claude auth login` into a directory of the host, mounted into the sandbox), not a `setup-token` token the host stores and injects

## Context

- A GitHub App managed from a vendor's cloud leaves a self-hosted install only a personal access token.
- Secrets injected as environment variables leak through processes that print them.
- Claude subscription connections break because of host-inherited logins and rotating refresh tokens.
- A hosted SaaS or a cloud relay is a non-goal.
- Both vendors forbid sharing a subscription login with other people.

## Decision

- **No vendor cloud and no relay.** Each installation creates its own GitHub App through the manifest flow. Webhooks go to the installation's ingress, or the installation polls. A fine-grained PAT is a quick-start fallback only.
- **API keys and integration secrets stay in the server vault** ([ADR-0011](0011-envelope-encrypted-vault.md)). They reach their use without entering the sandbox, in this order of preference:
  1. Gateway-injected: API keys. The sandbox holds only a per-run token that the gateway accepts.
  2. Proxy-injected: Slack, Google and remote MCP server tokens. The server's MCP proxy adds the auth.
  3. Sidecar-held: git. The git sidecar holds a 1-hour installation token for the task's repositories.
  4. Sandbox environment: subscription tokens, and secrets that a member marks "exposed to sandbox". The UI shows this state, and outbound scanning covers these values.
- **Subscription credentials stay on the owner's personal runner host.** They never reach the server. The server stores only metadata: label, vendor, plan, owner, pool size and health.
  - A subscription credential belongs to exactly one member. Only agents that member owns can use it.
  - The host secret store is the OS keychain, a 0600 file owned by the daemon user, or a Kubernetes Secret in the host's namespace.
  - Claude Pro/Max: a one-year `setup-token`, injected as `CLAUDE_CODE_OAUTH_TOKEN`. We never copy `/login` credentials.
  - Codex Plus/Pro: the runner host holds the only `auth.json` and is the only process that refreshes it. Sandboxes get short-lived access tokens.
  - Codex Business/Enterprise: a Codex access token per sandbox.
- **No header-swapping proxy** for subscription tokens.

## Consequences

- The operator owns all key management. There is no vendor to recover keys.
- The server cannot read or leak subscription secrets.
- A subscription token is inside the sandbox during a run. Controls that limit a leak:
  - an egress allowlist;
  - scanning of RunEvents, MCP inputs, artifacts and pushes for the token value in plain, base64, hex and URL-encoded form;
  - a stop as `credential_leak` on a hit;
  - a Claude token that can only make model requests;
  - short-lived Codex tokens in sandboxes.
- One personal runner host holds each subscription. The owner can move the credential to another host.
- Subscription use is a per-member opt-in with a visible warning. API keys stay available for every agent, and the switch is one setting. See the threat model, §6.4.

## Alternatives considered

- **A vendor cloud relay or a managed GitHub App**. Rejected: a non-goal.
- **Subscription tokens in the server vault.** Rejected: subscription logins must stay on the owner's runner host and never reach the server.
- **A proxy that swaps the `Authorization` header**, to keep the token out of the sandbox. Rejected: it matches Anthropic's wording against products that intermediate session tokens.
- **Copying `/login` credentials or `auth.json` into sandboxes.** Rejected: refresh tokens rotate or are single-use, so parallel copies break each other (`refresh_token_reused`).
