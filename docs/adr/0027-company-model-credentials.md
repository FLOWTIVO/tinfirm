# ADR-0027: Company model credentials, routed to any runner host

- Status: Superseded by [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md) on 2026-10-12 (was Accepted)
- Date: 2026-10-03
- Sources: CONVENTIONS §8, §14.1 to §14.4, §14.6, §14.8, §15.1, §15.3, §15.4, §16; [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0016](0016-runner-capacity-settings.md), [ADR-0025](0025-kata-sandbox-runtime.md); [threat model](../threat-model.md) R23
- Amended: 2026-10-03, the status INVALID for a key the gateway refuses (CONVENTIONS §14.8)
- Amended by: [ADR-0035](0035-agent-usage-policy.md), under which assigning a task, starting a run and mentioning need an agent the member may use
- Amended by: [ADR-0042](0042-provider-api-keys.md), under which a model credential is a provider's key, with a gateway in front optional for Anthropic and OpenAI, and one format serves several runtimes

## Context

- Every credential lives on a runner host today, and an agent may use one only when the agent's owner owns that host (`CREDENTIAL_NOT_OWNED`, CONVENTIONS §14.1). That rule enforces one owner per subscription ([ADR-0007](0007-credentials-stay-in-deployment.md)), and it means a company cannot have an agent that every member uses.
- A company may run a LiteLLM gateway: an HTTP endpoint that speaks the Anthropic Messages API and the OpenAI API, holds the provider keys, and enforces a budget per virtual key. It can give each agent its own virtual key. A company's runner hosts can be shared VMs (ADR-0025), not personal machines.
- A gateway of our own for API keys was planned: the sandbox would hold a per-run token, and the gateway would swap it for the real key and enforce USD budgets. That gateway does not exist, and where a company runs LiteLLM it would duplicate what LiteLLM already does.
- API keys on a runner host already reach the sandbox environment (CONVENTIONS §16): the runtime's CLI has to send them.
- The run module dispatches a run to the runner host of its credential (`RunDispatcher`). The host reports in `hello.runtimes` the runtimes whose images it found, but the server does not store them.

## Decision

**A credential of the company, in the server**
- A new module `model` holds `model_credential` (migration `V025__model_credential.sql`): name (unique per company), `format`, `base_url`, `secret_id` (a vault secret with the key, `ON DELETE SET NULL`), `pool_size` (1–16, default 4), and a cooldown state (`AVAILABLE` or `COOLDOWN` with `cooldown_until` and `detail`). It is a tenant table with RLS, composite foreign keys and the keyset index of its name.
- `format` is `ANTHROPIC` (the Messages API, for `CLAUDE_CODE`) or `OPENAI` (for `CODEX`). It cannot change. `base_url` is https without user info, query or fragment.
- API (tag ModelCredentials): list and get for every member, metadata only; create, update, delete and `clear-cooldown` for OWNER and ADMIN. A delete while live agents use the credential is `409 CREDENTIAL_IN_USE`; `?force=true` takes it from them. Every change is audited (`model_credential.*`); the key never appears in the API or the audit log.

**Agents use either kind**
- An agent has a runner credential or a model credential (`agent.model_credential_id`), never both. `AgentCreateRequest` takes exactly one of `runnerCredentialId` and `modelCredentialId`; an update that sets one clears the other.
- The owner rule (`CREDENTIAL_NOT_OWNED`) stays for runner credentials only. Any member who may create agents may give one a model credential. `CREDENTIAL_RUNTIME_MISMATCH` applies to both kinds.
- `agent.owner_user_id` stays the creator; the permissions of CONVENTIONS §14.2 do not change.
- A run records the agent's credential when it is created (`run.model_credential_id`), as it does for runner credentials.

**Any runner host that can run the runtime**
- The server stores `hello.runtimes` as `runner_host.runtimes`. The host lists a runtime there only when it found the runtime's image, so the list says which runtimes it can run. A host with no stored list (no `hello` since the migration) counts as serving every runtime.
- A run of a model credential goes to a connected runner host of the company that has confirmed its capacity (`host.config.applied`), does not drain, serves the agent's runtime and has a free slot. Of those, the one with the most free slots wins, ties by name, so the load spreads.
- Three limits apply: the agent's `maxConcurrentRuns`, the credential's `pool_size` (runs with that credential that hold a slot), and the host's slots. Runner credentials of the host are not involved.
- New queue reasons: `NO_RUNNER_HOST` ("No connected runner host can run the runtime {runtime}.") and, for several hosts that are all full, `RUNNER_HOST_FULL` without a host name. `CREDENTIAL_MISSING`, `CREDENTIAL_COOLDOWN`, `AGENT_AT_LIMIT` and `CREDENTIAL_POOL_FULL` are reused, with the model credential as the credential.

**The key travels like a git token, and reaches the sandbox like an API key**
- `run.assign` gets `modelAccess: {format, baseUrl, apiKey}`; `credentialLabel` becomes optional, and exactly one of the two is present. The server reveals the key in the assigning transaction, audited as `secret.accessed` with purpose `run:<runId>`.
- The runner host uses no local credential for such a run: no credential lookup, no pool of a local credential, no `credential.status`. The host's slots still count.
- It sets the runtime's variables in the sandbox: for `CLAUDE_CODE` `ANTHROPIC_BASE_URL` and the key as `ANTHROPIC_AUTH_TOKEN` (an `Authorization: Bearer` header, which LiteLLM accepts for virtual keys); for `CODEX` `OPENAI_BASE_URL`, `OPENAI_API_KEY` and the `DEFAULT_AUTH_REQUEST` of an OpenAI API key, and `openai_base_url` in `CODEX_CONFIG`, because Codex reads the base URL of its OpenAI provider from its config. It redacts the key like the git tokens of the run.
- **Cooldown is the server's.** A run that ends `FAILED` / `USAGE_LIMIT` puts its model credential into `COOLDOWN`: until the latest `resetsAt` of the run's `RATE_LIMIT` events when it is in the future, else for `TINFIRM_RUN_MODEL_CREDENTIAL_COOLDOWN` (default `PT15M`). OWNER and ADMIN can end it early.

## Consequences

- A company can have shared agents: any member assigns them work, and their runs spread over the company's runner hosts. A company's LiteLLM keys need no Tinfirm gateway.
- **The key reaches the sandbox environment.** A prompt-injected agent can read it and use it from inside the sandbox or, without an egress allowlist, from anywhere, until it is revoked. The gateway's per-key budget bounds the damage, and the runner host's redaction stops a run that prints it (accepted risk R23 in the threat model). One virtual key per agent keeps a leak to one agent's budget.
- **Budgets are the gateway's.** Tinfirm neither meters nor caps spend for these runs; it reports the usage that the runtime reports. When the gateway refuses a request for budget or rate limits, the run fails. The runner host counts a LiteLLM budget error (`budget_exceeded`) as a usage limit, so it cools the credential down like a usage limit of a subscription.
- The key also passes through the runner host (in `run.assign`) and its memory, as git tokens do today.
- A runner host serves model credential runs only for runtimes whose images it had when `run` started; images built later count after its next connection.
- Protocol v1 changes in place: an older runner host cannot parse a `run.assign` without `credentialLabel`. Server and runner hosts are deployed together, as for ADR-0016.
- The cooldown is coarse: one usage limit of one run blocks every run of the credential until the reset time or the default period.

The setup with LiteLLM, from the virtual key to the agent, is in [operations/litellm.md](../operations/litellm.md).

## Alternatives considered

- **A Tinfirm model gateway, as planned.** Not now: the sandbox would hold only a per-run token, and we would meter and cap spend ourselves. It is weeks of work, and LiteLLM already enforces budgets per key. The model credential can later point at our own gateway with a per-run token, without changing agents or dispatch.
- **Keys on every runner host**, as `credential add anthropic-api-key` on each VM. Rejected: every key on every host, rotation by hand on each machine, and agents still bound to the owner of one host.
- **A shared runner credential**: drop the owner rule for API-key kinds of runner credentials. Rejected: the run would still be tied to the one host that holds the key, so load would not spread, and the key would still live on a machine instead of the vault.
