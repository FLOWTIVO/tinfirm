# Package 2 contract: model access, budgets and runner hosts

Status: decided on build (§13), built · 2026-10-12 · Owner: J. Balcar

The contract for package 2 of the [platform redesign](2026-10-12-platform-redesign.md#part-9-how-it-is-built--decided): who pays for a run, how Tinfirm manages LiteLLM, where a run may execute, and what reaches a runner host. It builds on [package 1](2026-10-12-package-1-contract.md) (principals) and implements [ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md); [PLATFORM.md](../PLATFORM.md) §11 is the design. Package 3 calls the two functions of §5 when it starts a conversation and dispatches its turns.

## 1. Scope

| In package 2 | Later |
|---|---|
| LiteLLM: bundled or the company's own, managed by Tinfirm through its admin API | Spend views per agent and trigger (package 6) |
| One LiteLLM key per principal, with its budget and models; company defaults and per-principal overrides | Which model access a person's settings or a trigger names (packages 3, 4) |
| Subscriptions on a member's own runner host as model access | |
| Runner hosts `COMPANY` or `PERSONAL`, and which runs each may take | |
| The model access of a run and the hosts eligible for it, as functions | |
| The model proxy as the default for gateway runs | |

## 2. What changes in the kept tables

| Table | Change |
|---|---|
| `runner_host` | `scope` (`COMPANY` or `PERSONAL`) and `owner_principal_id` (the member's principal for a personal host, `NULL` for a company host); `owner_user_id` goes. A host's scope is set when it is registered and never changes |
| `runner_credential` | Kept as it is: a credential on a host (`CLAUDE_OAUTH_TOKEN`, `CODEX_CHATGPT_AUTH`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`), reported by label. Allowed only on a personal host, and it is its owner's model access |
| `model_credential` | Not created: a principal's LiteLLM key replaces it |

## 3. Tables

### 3.1 `model_settings`

One row per company.

| Column | Meaning |
|---|---|
| `company_id` | Primary key |
| `gateway` | `BUNDLED` (the LiteLLM Tinfirm ships) or `EXTERNAL` (the company's own) |
| `admin_url`, `admin_key_secret_id` | Where the server reaches LiteLLM's admin API, and its master key (a company secret for `EXTERNAL`; the deployment's for `BUNDLED`) |
| `relay_enabled` | Whether gateway runs may start: false until LiteLLM answers the admin test. The relay's URL is the server's own (`{TINFIRM_PUBLIC_URL}/api/runner/v1/model/…`); runner hosts never reach LiteLLM |
| `default_budget_usd`, `budget_period` | The budget of every member without an override, and its period in LiteLLM's terms (`30d` by default) |
| `default_models` | The models a key may use by default (the runtimes' small models included: a session title, a background task) |
| `updated_at`, `updated_by` | |

### 3.2 `principal_model_key`

A principal's LiteLLM key.

| Column | Meaning |
|---|---|
| `principal_id` | Primary key; members and service identities alike |
| `company_id` | |
| `secret_id` | The virtual key, a company secret this row manages; never shown, listed or changed through the Secrets API |
| `litellm_key_id` | LiteLLM's id of the key (its hash or alias), for update, block and delete without the key itself |
| `budget_usd`, `models` | Overrides; `NULL` takes the company's default |
| `status` | `OK`, `BUDGET_SPENT` (until `budget_resets_at`), `BLOCKED` (the principal is suspended or left), `ERROR` (LiteLLM refused or is unreachable; `last_error`) |
| `spent_usd`, `budget_resets_at`, `synced_at` | The last values read from LiteLLM |
| `created_at`, `updated_at` | |

## 4. Invariants

| # | Invariant | Enforced by |
|---|---|---|
| M1 | One LiteLLM key per principal; a key is never shared, granted or reused for another principal | Primary key; the service |
| M2 | A run is paid by exactly one model access, chosen before it is dispatched and kept on the run: a principal's key, or a subscription on its owner's host | `run.paying_principal_id` and `run.model_access` (`BUDGET` or `SUBSCRIPTION`), not null |
| M3 | Tinfirm never switches payer: a spent budget, a blocked key or a missing subscription host makes the run wait or fail with the reason | §5.1 |
| M4 | A personal host runs only its owner's work: a run is assigned to it only when the paying principal is its owner and every workspace git account of the run is the owner's own. No LiteLLM key ever reaches a host; the rule protects what a host's owner can see of a run (its prompt, workspace, transcript and model traffic) | §5.2 and the dispatcher; a test per case |
| M5 | A host credential (subscription or API key) exists only on a personal host and serves only its owner's runs | Check on `runner_credential` via its host; §5.2 |
| M6 | A suspended or left principal's key is blocked in LiteLLM in the transaction's after-commit step, and deleted when the principal leaves | Service, with a reconciliation job that repeats what failed |
| M7 | **A principal's LiteLLM key never leaves the server.** A run gets a one-run token for the server's model relay (§6.1), revealed only to the run's host in `run.assign`; the model proxy keeps it out of the sandbox; it is refused once the run is no longer active on that host | The assignment builder; the relay (§6.1) |

## 5. The rules, as functions

### 5.1 `ModelAccess.forRun(conversation)`

Package 3 passes the conversation; the model access it named (a person's settings: their budget or their subscription; a trigger: its author's or a service identity's budget, or the author's subscription) is already authorised by packages 3 and 4.

| Named | Returns |
|---|---|
| A principal's budget | `Budget(principal, key)` when the key is `OK`; `Wait(BUDGET_SPENT, resetsAt)`; `Wait(KEY_ERROR, reason)`; `Refuse(PRINCIPAL_INACTIVE)` for a suspended or left principal |
| A member's subscription (a host credential's label) | `Subscription(host, label)` when the member's personal host has it; `Wait(HOST_OFFLINE)`; `Refuse(SUBSCRIPTION_GONE)` when the credential no longer exists |

### 5.2 `Hosts.eligible(run)`

| Run | Eligible hosts |
|---|---|
| `Budget(P)`, with a workspace account that is not P's own (a service identity's, or a grant) | Company hosts that serve the runtime |
| `Budget(P)`, every workspace account P's own (or no workspace) | Company hosts, and P's personal hosts, that serve the runtime |
| `Subscription(host, label)`, every workspace account the owner's own | That host only |
| `Subscription(...)` with a workspace account that is not the owner's | None: `Refuse(CANNOT_RUN_ANYWHERE)`, shown before the conversation starts and in the trigger's editor |

Among eligible hosts the dispatcher keeps today's choice (free slots, capacity settings, queue reasons; [ADR-0016](../adr/0016-runner-capacity-settings.md)). A queued run says why it waits, with the new reasons `BUDGET_SPENT`, `KEY_ERROR`, `HOST_OFFLINE`.

## 6. LiteLLM

- **Bundled** (the default): the Helm chart and Compose add a pinned LiteLLM image with its own database (a separate database on the same PostgreSQL), its master key a deployment secret Tinfirm reads. Tinfirm configures the company's provider keys and model list through LiteLLM's admin API from the Model settings page. **External**: an admin enters the admin URL and a master or admin key; Tinfirm then manages only the keys it creates (alias prefix `tinfirm-{companyId}-`).
- **LiteLLM stays inside the deployment**: only the server reaches it (its network policy in Helm, the Compose network). Runner hosts reach only the server, through the model relay (§6.1).
- **Key lifecycle**: created when a principal is created (members joining, service identities), lazily for principals that exist when a company first configures LiteLLM; `max_budget` and `budget_duration` from the override or the default, `models` likewise, metadata naming the company and principal; updated when a budget or the defaults change; blocked when a principal is suspended, unblocked when resumed; deleted when a member leaves or a service identity is deleted. A reconciliation job compares Tinfirm's keys with LiteLLM's every few minutes and repairs what drifted.
- **Spend**: read from LiteLLM per key (spent, reset time) on a schedule and after a run ends; usage per run comes from the run's usage events, and a cost per run from LiteLLM's model prices, labelled as an estimate.
- **A spent budget**: LiteLLM refuses with `budget_exceeded`; the runner reports `USAGE_LIMIT`; the key becomes `BUDGET_SPENT` until its reset time, and the principal's queued turns wait. A refused key (401, 403) becomes `ERROR` and admins are told.

### 6.1 The model relay

The server is the runner hosts' model endpoint.

| Part | Rule |
|---|---|
| Endpoint | `POST {TINFIRM_PUBLIC_URL}/api/runner/v1/model/anthropic/v1/messages` and `…/v1/messages/count_tokens`; `POST …/model/openai/v1/responses` and `…/v1/chat/completions`; `GET …/v1/models` of both. Under the runner's reserved path (`/api/runner/v1/**`), so it needs no new route in the deployment. Nothing else is served |
| `modelAccess` | `baseUrl` the relay's root for the run's format (`…/model/anthropic` or `…/model/openai/v1`), `apiKey` the run's one-run token. Same fields as today: no protocol change |
| Token (`run_model_token`) | 32 random bytes, stored hashed, bound to the run, its epoch and its host; created at assignment, revoked when the run ends or is reassigned. Accepted only while the run is `RUNNING` on that host with that epoch; anything else is 401 without saying why |
| Request | At most 64 MiB. The body is JSON of the format's API; **LiteLLM's own fields are removed** before forwarding (any field that names an upstream: `api_base`, `base_url`, `api_key`, `api_version`, `custom_llm_provider`, `organization`; LiteLLM's controls: `metadata`, `tags`, `user`, `mock_response`, `litellm_*`, `user_config`, `extra_headers`, `extra_body`), and the model must be one the paying principal's key allows. Headers: only `Content-Type`, `Accept`, and the format's version and beta headers (`anthropic-version`, `anthropic-beta`, `OpenAI-Beta`) pass |
| Forwarding | To LiteLLM inside the deployment, with the paying principal's key; the run's id, conversation and agent as LiteLLM tags set by the server (exact spend per run: open point 1 closes); the answer streamed back as it arrives, with no total timeout and an idle timeout of 10 minutes |
| Limits | Concurrent requests per run (16) and per host (the host's slots × 16); a refused request is 429 |
| Audit | Not per request (a run makes hundreds); the run records its relayed request count, its bytes, and LiteLLM's spend for it |

Package 6's replica work covers relayed streams like the run's other in-flight state.

## 7. The model proxy, always on

The runner host's model proxy (ADR-0059) is always on for a run whose `modelAccess` is a gateway, with no option to turn it off. The protocol does not change, so the server cannot tell a host that proxies from an older one: gateway keys are sent only once the company's hosts run a version with the always-on proxy (the minimum runner version is checked from `hello.runnerVersion`, which the protocol already carries). The proxy's gateway is the server's relay (§6.1), so a runner host on the same machine as the server names the server's https origin with `--model-proxy-local-gateway`, and one that reaches the server at a private address needs `--egress-allow-private`.

## 8. API

| Endpoint | Who | Does |
|---|---|---|
| `GET /model-settings`, `PUT /model-settings` | Admins | The gateway, its URLs and admin key, the defaults |
| `POST /model-settings/test` | Admins | Reaches LiteLLM's admin API and lists its models |
| `GET /model-settings/models` | Every member | The models the gateway serves, for settings and triggers |
| `GET /me/model-access` | The member | Their budget, spent, reset time, models, status, and their subscriptions on their personal hosts |
| `GET /principals/{id}/model-access`, `PATCH …` | Admins | A principal's budget and models; set or clear an override |
| `GET /runner-hosts`, `POST /runner-hosts` | Admins see and register company hosts; a member sees and registers their own personal hosts | `scope` set at registration; the registration token shown once, as today |
| `PATCH /runner-hosts/{id}`, `DELETE /runner-hosts/{id}` | Admins for company hosts; the owner for a personal one | Capacity settings and removal, as today |

## 9. Errors

`GATEWAY_NOT_CONFIGURED` (409: no conversation can start until an admin configures LiteLLM), `GATEWAY_UNREACHABLE` (502 on the settings test), `CANNOT_RUN_ANYWHERE` (409, §5.2), `PERSONAL_HOST_CREDENTIAL_ONLY` (400: a host credential on a company host), `SUBSCRIPTION_GONE` (409). Queue reasons `BUDGET_SPENT`, `KEY_ERROR`, `HOST_OFFLINE`.

## 10. Audit actions

`model_settings.changed`; `model_key.created`, `model_key.budget_changed`, `model_key.blocked`, `model_key.unblocked`, `model_key.deleted`, `model_key.budget_spent`, `model_key.refused`; `runner_host.registered` (with its scope and owner), `runner_host.removed`; `secret.accessed` with `run:<runId>` for every key sent to a host.

## 11. Tests

| Test | Proves |
|---|---|
| Key lifecycle against a fake LiteLLM admin API: create, update on a budget or default change, block on suspend, unblock on resume, delete on leaving, reconciliation after a failed call | §6, M1, M6 |
| `ModelAccess.forRun` table test: budget `OK`, spent, error, inactive principal; subscription present, host offline, credential gone | §5.1, M3 (V7: nobody else pays) |
| `Hosts.eligible` table test: every row of §5.2, with company and personal hosts, own and granted workspace accounts | §5.2, M4, M5 (V8) |
| The assignment carries the relay's URL and a one-run token, never a LiteLLM key; the token is refused after the run ends, after reassignment and from another host; LiteLLM's fields are removed from the body; a model the key does not allow is refused; an answer streams through | M2, M4, M7, §6.1 |
| A run against a real LiteLLM through the model proxy, when a Docker daemon is available (opt-in test) | §6, §7 |

## 12. Open points

| # | Question | Proposal |
|---|---|---|
| 1 | Per-run cost from LiteLLM itself rather than an estimate | Decided: the relay tags each request with the run (§6.1) |
| 2 | Keys created eagerly for every member, or at a member's first run | Eagerly, so a member's budget shows before their first conversation |
| 3 | A budget period aligned to the calendar month for everyone, rather than LiteLLM's rolling period per key | LiteLLM's period per key; a company-wide reset day only if companies need it for accounting |
| 4 | The bundled LiteLLM's exposure to runner hosts | Decided: none. The server relays (§6.1) |

## 13. Decided on build (2026-10-12)

What the build of package 2 settled where this contract was silent or wrong:

| # | Point | Decided |
|---|---|---|
| 1 | `PRINCIPAL_INACTIVE` has no error in §9 | It maps to `409 PRINCIPAL_SUSPENDED` |
| 2 | §3.2 gives a principal that left the status `BLOCKED`, while M6 deletes its key | The key and its row are deleted when a member leaves or a service identity is deleted; `BLOCKED` is for a suspended principal only |
| 3 | §10 audits a key "sent to a host", but no key reaches a host since §6.1 | `secret.accessed` with `run:<runId>` when the relay reveals the paying key for a run, once per token and server replica |
| 4 | A relay URL from an `http` `TINFIRM_PUBLIC_URL` (development) breaks the protocol's https `baseUrl` | Open: a development instance over plain http cannot serve gateway runs; see open points |
| 5 | "Refused from another host" | The model proxy sends only the token, so the relay checks the token's binding to its run, epoch and host through `RunModelTokens`; a copied token works only while its run is active on its own host, from wherever it can reach the server. Binding to the host's own connection needs a runner change (a second header), recorded as later hardening. **Closed** by open point 6, built: from runner host 0.3.0 a copied token works only with its host's own token |
| 6 | `PERSONAL_HOST_CREDENTIAL_ONLY` as an HTTP 400 | No endpoint takes a host credential: it is a protocol error and a refusal of `ModelAccess.forRun` |
| 7 | The header allowlist of §6.1 omits `User-Agent` | Passed through: LiteLLM uses it to turn `drop_params` on for Claude Code and Codex |
| 8 | `metadata` and `user` are legitimate API fields | Still removed: they never reach the provider, which the runtimes do not need |
| 9 | Runner hosts | Registration defaults to `PERSONAL`; a personal host is visible to its owner only (admins included: `404`); credentials reported by a company host are refused and not stored; a leaving member's personal hosts are removed |
| 10 | Requests carry the run as `x-litellm-tags` | Key-level tags are an enterprise feature of LiteLLM; request tags are not |
| 11 | Not built in package 2 | Key rotation (security review row 2); the opt-in test against a real LiteLLM. The relay answers `401` to everything until package 3 implements `RunModelTokens` |

### Open points after the build

| # | Question | Proposal |
|---|---|---|
| 5 | Gateway runs on a development instance served over plain http | Development uses `https://localhost` with the Compose stack's own certificate, or the runner's test-only `allow_http` stays a build flag; to settle with package 3's end-to-end test |
| 6 | Binding a run's token to its host's connection | **Built (2026-10-12)**, without a protocol change. Runner host **0.3.0**'s model proxy sends the host's own runner token in `X-Tinfirm-Runner-Token` next to the run's token, only to a gateway at the origin of the host's server URL (the server's relay), and drops one the sandbox sends; the token never enters the sandbox. The relay authenticates it as the runner's other endpoints do (by its hash) and takes the run's token only with the token of its own host, on which the run is `RUNNING` with the token's epoch. Without the header, a host whose latest `hello.runnerVersion` is 0.3.0 or later is refused (`401`, as every refusal); an older host (0.2.x) is accepted as before, with a warning once a day per host and replica, until `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` is on, which refuses it from every host. Whoever holds the host's own token as well (its memory or its configuration) still acts as the host: the binding stops a run token copied alone (CONVENTIONS §15.4; the threat model's 13.4) |
