# More than one model gateway: what it would take

An analysis of 2026-10-26: what Tinfirm would need to change to let a company choose its model gateway among LiteLLM, Bifrost, Kong AI Gateway and others. The decisions of that day are in §0 and the questions it left open in §6; [ADR-0094](../adr/0094-more-than-one-model-gateway.md) records the decision that followed. The vendors' facts come from their public documentation as read on the day and are marked *to verify* where it did not confirm them; they must be checked against the vendors' API references before any design.

**In short.** LiteLLM is wired into Tinfirm at four points: the key lifecycle on its admin API, budget enforcement, spend settlement from its logs, and the request-body filter. The rest is already independent of the gateway: the relay's transport, its usage meter, the runner host, its model proxy and the runner protocol (`modelAccess` is `{format, baseUrl, apiKey}` pointing at the server's relay). Supporting several gateways means putting those four points behind an adapter. In every mode **the gateway enforces the budget, and each principal has a key of its own at the gateway**. What varies is who creates and manages that key:

- **Managed** (LiteLLM, Bifrost): Tinfirm creates one key per principal, with its budget and models, through the gateway's admin API. This is today's model.
- **Provided** (any gateway: Kong AI Gateway, Bifrost, LiteLLM, Portkey, agentgateway, any OpenAI- or Anthropic-compatible endpoint): the company's gateway team creates a virtual key per person and per service account in the gateway, with its budget and models. They hand each key to Tinfirm through the web app or an API. Tinfirm holds no admin key, keeps each key in its vault, pays with it and recognises its refusals. Keeping keys and budgets right is the gateway's job.

Provided mode also covers companies that run LiteLLM or Bifrost but will not give Tinfirm an admin key. For those two gateways Tinfirm still knows the vendor's refusals and, where the key may read its own state, its budget and reset time (§3.3).

A "pass-through" tier was weighed against it: one shared key, with Tinfirm enforcing per-person budgets in its relay. Provided mode keeps the budget where it is today, at the gateway, with no overshoot and no price list of Tinfirm's own. It needs no budgeting in the relay, so it is also less code. The owner chose provided mode, rejected the shared-key variant and allowed members to paste their own keys where the company allows it. §0 records these decisions, and §3.3 compares the two designs.

Recommended order:

1. A refactor with no change in behaviour: the adapter, neutral names and a normalised budget refusal.
2. Provided mode.
3. Bifrost as a second managed gateway.

Kong needs no adapter of its own beyond provided mode.

## 0. Decided

The owner, 2026-10-26:

- **Provided mode replaces the shared-key pass-through.** Each person has a virtual key of their own, set in the gateway, which enforces its budget. LiteLLM and Bifrost get the close integration of managed mode, and provided mode serves the companies that will not give Tinfirm even their admin API.
- **Members may paste their own keys, if the company allows it**: a company setting that its admins change.
- **No shared-key variant.** Tinfirm never enforces budgets in its relay. A gateway that cannot hold a key per person, with its own limits, is not supported.

The other questions of §6 stay open.

**Bifrost spike.** The Bifrost items marked *to verify* below were answered by the [Bifrost spike](2026-10-26-bifrost-spike.md) of the same day, against v2.3.2. Its main answers:
- A spent budget is `402`, with `billing_error` on the Anthropic route and `budget_exceeded` on the OpenAI routes.
- Keys rotate natively, keeping their id and spend.
- A key can read its own budget at `/api/governance/virtual-keys/quota`.
- Spend is in `/api/logs`, tagged with `x-bf-lh-*` headers.
- The admin API shares the model port behind one username and password, so `model-api` stays.
- Bifrost does not boot without a local price file when offline.

## 1. Where LiteLLM is wired in today

### 1.1 Key lifecycle on the admin API

`LiteLlm` (server, `model/internal/LiteLlm.java`) is the one client. Its callers are `KeySync`, `KeyRotation`, `KeyReconciliation`, `KeyWork`, `ModelSettingsService`, `SpendLogsImpl` and `PlatformModelService`. It uses these routes: `/key/generate|update|block|unblock|delete|info|list`, `/team/info|new|update`, `/v1/models`, `/model/info` and `/spend/logs`, plus chat completions for the company key (ADR-0065).

The concepts it relies on are all LiteLLM's:

- **Team.** A company is the team `tinfirm-{companyId}`.
- **Alias.** A key's alias is `tinfirm-{companyId}-{principalId}`, with a `-r{n}` suffix after a rotation. The reconciliation's sweep finds keys by this alias prefix.
- **Key id.** A key's id is the SHA-256 of the key, which is LiteLLM's `token`. It is stored as `principal_model_key.litellm_key_id` and `relay_usage.key_id`.
- **Budget.** Each key carries `max_budget` and `budget_duration`. The reset time comes from LiteLLM's `budget_reset_at`.
- **Blocking.** `block` and `unblock` are used for suspended principals and for rotation's grace.
- **Rotation.** Rotation is create, grace, block, delete. Tinfirm does it itself because `/key/regenerate` is enterprise-only.

### 1.2 Budgets: LiteLLM enforces, Tinfirm mirrors and reacts

Tinfirm pushes each budget onto its key, and LiteLLM refuses a request once the budget is spent. Tinfirm learns about it in three places:

- `KeySync.status` reads `spend >= max_budget` from `/key/info` and marks the key `BUDGET_SPENT`.
- The relay recognises a 429 with error type `budget_exceeded` (`ModelRelayService`). The bundled configuration forces `budget_exceeded_status_code: 429`, because LiteLLM answers 422 otherwise.
- The runner host matches LiteLLM's wording of the refusal to classify a run as `USAGE_LIMIT` (`runner/crates/tinfirm-runner-host/src/limits.rs`, `is_gateway_budget_text`).

Other parts depend on LiteLLM's semantics as well:

- `ModelAccessImpl` queues runs as `Wait(BUDGET_SPENT, resetsAt)`.
- Budget alerts compare against LiteLLM's spend.
- `model_settings.budget_period` uses LiteLLM's `budget_duration` syntax (`^[1-9][0-9]{0,3}(h|d|w|mo)$`), in the schema, in the OpenAPI and in the web app.
- A 400 whose text contains `invalid model name passed in model=` becomes `MODEL_NOT_SERVED`.
- The model lists `all-proxy-models` and `all-team-models` mean "every model".

### 1.3 Spend settlement

`SpendSync` (governance) settles a run 2 minutes after it ends. It reads `/spend/logs` for the paying key and keeps the entries tagged `run:{runId}`. When it finds as many entries as the relay counted answers, it settles the run as `GATEWAY` with LiteLLM's costs. After an hour without them, it falls back to `RELAY`: the relay's own token counts, priced with LiteLLM's `/model/info`. The relay attaches the tags with `x-litellm-tags: run:…,conversation:…,agent:…`.

`tinfirm.spend.source` (`LITELLM | RELAY`) already has a gateway-free mode, except for its prices.

### 1.4 The request-body filter

`libs/litellm` (`LiteLlmRequests`) strips about 150 of LiteLLM's own body fields (pinned to the bundled LiteLLM's version) and about 25 prefixes, which would otherwise let a caller redirect a request or bill another key. The relay also forces `drop_params: true`. The model port (`model-api/`, ADR-0069) exists only because LiteLLM serves its admin API and its model API on one port, and the port uses the same filter.

### 1.5 Around the code

The rest of the coupling is names, deployment and tests:

- **Settings.** `GatewayKind` is `BUNDLED | EXTERNAL`, which is a choice of deployment, not of vendor. The secret is `LITELLM_ADMIN_KEY`, and the properties are `TINFIRM_LITELLM_ADMIN_URL`, `TINFIRM_LITELLM_MASTER_KEY` and `TINFIRM_LITELLM_MODEL_URL`.
- **Helm.** The chart has a `litellm:` block with its own database, the `model-api` sidecar, network policies and a configmap. The values schema and CI values cover it.
- **Compose.** `litellm`, `litellm-db` and `model-api` services, and generated `LITELLM_*` secrets.
- **Web and OpenAPI.** Texts such as "Which LiteLLM" and "Bundled LiteLLM", and the period units.
- **Docs.** `docs/operations/litellm.md`, the docs-site's operator guide `model-gateway.md`, and PRODUCT.md.
- **Tests.** `FakeLiteLlm` (every integration test's bundled gateway, which `IntegrationTest` registers), `ModelGateways`, and `web/e2e/harness/fake-litellm.ts`.

### 1.6 Already independent of the gateway

- `RelayUsageMeter` reads usage in the Anthropic, OpenAI and Responses formats.
- The relay's streaming, limits and one-run tokens.
- The runner's model proxy and `runtime_env.rs`. The only exception is `x-litellm-api-key` among the credential headers it drops, which is harmless.
- The protocol's `modelAccess`.
- `run_spend.source` values (`GATEWAY | RELAY | SUBSCRIPTION`).

## 2. The candidates

| | LiteLLM (today) | Bifrost | Kong AI Gateway | Envoy AI Gateway / agentgateway | Portkey, OpenRouter |
|---|---|---|
| Fits the mode | managed or provided | managed or provided | provided: a consumer per person with key-auth and a cost limit; managed is possible but brittle | provided, if its keys carry per-person limits | provided, as SaaS |
| Per-principal keys by API | `/key/generate` and the rest | virtual keys, `POST/PUT /api/governance/virtual-keys` | consumers + `key-auth` credentials on the Admin API; not in DB-less or declarative mode | none: keys and limits are Kubernetes resources or an external rate-limit service | Portkey: per-key budgets in the dashboard (Enterprise), Admin API at workspace level; OpenRouter: provisioning API with credit limits |
| Company grouping | team | team / customer (hierarchical budgets) | consumer group | none | workspace |
| Budget in USD with reset | yes, `h/d/w/mo`, calendar reset | yes, `1m 1h 1d 1w 1M 1Q 1Y`, rolling or calendar-aligned; temporary additive overrides | `ai-rate-limiting-advanced` (Enterprise) limits cost per window in seconds, sliding; cost is applied on the *next* request | token rate limits through a rate-limit service; no USD budget | yes (Portkey Enterprise; OpenRouter credits) |
| Spend read back per request with Tinfirm's tags | `/spend/logs` + tags | request logs with metadata (*to verify*: API, filter by key, tag header) | no log API: logging plugins (http-log, OpenTelemetry) push AI usage | metrics and access logs | analytics API (*to verify*) |
| Spent-budget refusal | 429 `budget_exceeded` (configured) | *to verify* | 429 from the rate limiter | 429 | 402/429 (*to verify*) |
| Admin and model on separate ports | no, hence `model-api` | no: management API and UI on 8080 beside inference (*to verify*), so `model-api` is still needed | yes (Admin API 8001, proxy 8000) | yes (control plane) | n/a |
| Formats for Tinfirm's runtimes | Anthropic `/v1/messages`, OpenAI chat and responses | OpenAI-compatible and per-provider routes such as `/anthropic/v1/messages`; models named `provider/model` (*to verify* how Claude Code's and Codex's model names map) | `ai-proxy-advanced` in the OpenAI format; native Anthropic passthrough (*to verify* for Messages and streaming) | OpenAI format; Anthropic (*to verify*) | OpenAI format; Anthropic (*to verify*) |
| Body fields of its own to strip | about 150 (`libs/litellm`) | a few of its own (`fallbacks`, provider params; *to verify*) | none known: plugins configure it, not the body | none known | some (Portkey's config headers) |
| Self-hosted | yes, MIT core | yes, Apache-2.0 core, Go, light; governance in the open-source edition (RBAC and SSO enterprise; *to verify*) | yes; AI cost limits are Enterprise / Konnect | yes | Portkey's gateway yes, its budgets and analytics in the cloud; OpenRouter is SaaS only |

What this means for each candidate:

- **Bifrost** matches LiteLLM's shape: virtual key ≈ key, team or customer ≈ team, budget with a reset period, model allow-list per key. It fits managed mode with a second adapter and no change to the domain, and provided mode for companies that keep its admin API to themselves.
- **Kong** is the gateway companies already run as their API platform. That makes it a likely choice, and it is also why Tinfirm should not manage it:
  - Its admin API belongs to the company's platform team.
  - Many installations are declarative (decK, Konnect, the Ingress controller) and refuse Admin API writes.

  Provided mode fits it as it is. The platform team declares a consumer per person with a key-auth credential and an `ai-rate-limiting-advanced` cost limit, in their own tooling, and hands the keys to Tinfirm. One caveat: Kong's limits are sliding windows, not budgets that reset on the 1st, and its cost lands on the *next* request, so one answer can overshoot. That is the company's choice of gateway, not something Tinfirm needs to fix.
- **SaaS gateways** (OpenRouter, Portkey's cloud) send prompts and spend data out of the deployment. That conflicts with PRODUCT.md's "No vendor cloud". Technically they would work in provided mode; whether Tinfirm offers them at all is left open (§6, question 2).

## 3. What has to change

### 3.1 A gateway adapter in the model module

Turn `LiteLlm` into one implementation of an interface internal to `model/internal`, chosen per company from its settings:

```java
interface GatewayAdapter {
    Set<Capability> capabilities();  // KEYS, BUDGETS, TEAMS, SPEND_LOGS, PRICES, BLOCK
    // key lifecycle (KEYS): generate, update, block, unblock, delete, deleteByAlias, info, teamKeys
    // ensureTeam (TEAMS), models(), spendLogs(...) (SPEND_LOGS), prices() (PRICES)
    RequestPolicy requests();        // body filter, tag headers, header allow-lists
    Refusal classify(int status, JsonNode error); // BUDGET_SPENT, KEY_REFUSED, MODEL_NOT_SERVED, OTHER
    String keyId(String secret, CreatedKey created); // sha256 for LiteLLM, the vendor's id for Bifrost
}
```

- `Gateway` gains a `vendor` and keeps its URLs and admin key. `Gateways` picks the adapter.
- `KeySync`, `KeyRotation`, `KeyReconciliation` and `KeyWork` keep their algorithms. They call the adapter, and they skip their work when the gateway lacks `KEYS` (provided mode). The reconciliation then only checks each provided key with `/v1/models` and its self-read, if any.
- Rotation stays Tinfirm's create, grace, block, delete for every vendor. Bifrost may offer a native regeneration, which is optional.
- `LiteLlmException` becomes `GatewayException`.
- The alias scheme stays, as long as the vendor keeps a name or alias that Tinfirm can list keys by. Bifrost's virtual keys have a name; *verify that it is unique and can be filtered on*.

### 3.2 Budget periods and refusals in Tinfirm's own terms

- **Budget period.** Replace the `budget_period` text with a neutral period (`DAY | WEEK | MONTH`, plus an optional count). Each adapter maps it to `budget_duration` or `reset_duration`. A migration converts the existing values, and the OpenAPI and the web app change with it. Periods in hours are allowed today, so dropping them would need a migration of such periods.
- **Budget refusal.** Normalise the relay's refusal. When an adapter classifies an answer as `BUDGET_SPENT`, the relay answers the sandbox with *Tinfirm's* own error: in the request's format, a fixed type and wording, and the reset time. `limits.rs` then matches Tinfirm's wording instead of LiteLLM's. This makes the runner independent of the gateway. It needs one runner release, and the server must keep LiteLLM's wording passing through for older hosts until their minimum version moves.
- **LiteLLM's 429 setting.** `budget_exceeded_status_code: 429` stays a LiteLLM adapter requirement, and the adapter's test checks it.

### 3.3 Provided mode: the gateway's keys, handed to Tinfirm

This is the largest piece of new behaviour. With it, Kong and every other gateway work, and so do LiteLLM and Bifrost without an admin key.

**Settings.** A company chooses *Provided keys* and gives the gateway's model URL, its format or formats, and its vendor (`LITELLM`, `BIFROST`, `KONG` or `GENERIC`). There is no admin URL, no admin key and no team. `BUNDLED` stays managed only: the deployment holds the bundled gateway's master key anyway.

**How a key reaches Tinfirm.** Each principal's key, and the company's own key (ADR-0065), is an internal vault secret as today. Three ways in:

1. **In the web app.** An admin enters a key on the principal's row in Members' budgets, next to the company key's row. This needs the `MANAGE_MODEL_ACCESS` permission and is audited as `model_key.provided`. The key is write-only: the app shows its last four characters and when it was entered, never the key.
2. **Through the API**, for the company's automation: `PUT /principals/{id}/model-key`, plus a bulk call keyed by e-mail or by service account name. A gateway team that keeps keys in Terraform (Bifrost and Kong have providers) or in decK can push each key to Tinfirm in the same pipeline that creates it. A service account with `MANAGE_MODEL_ACCESS` calls it.
3. **By the member, where the company allows it** (decided, §0). This is a company setting in Model access, `memberProvidedKeys`, off by default, which only OWNER and ADMIN with `MANAGE_MODEL_ACCESS` may change. It is audited as `model_settings.changed`.
   - **Who may paste.** With the setting on, a member pastes on their own profile a key the gateway team gave them. It is checked with `/v1/models` like any other and audited as `model_key.provided` with `by: self`. A member can replace or remove only their own key.
   - **Service accounts and the company key** are always entered by admins or the API.
   - **Admins see who provided each key**, the member or an admin. They can replace a member's key, and a key they entered is replaced by the member only if they allow it.
   - **Turning the setting off** keeps the keys members already entered. Admins see them marked, and can remove them or ask for new ones.
   - **Security.** This amends ADR-0059's "the principal never sees the key" for provided mode: the key was created outside Tinfirm, and the member already holds it. The budget is not weakened, since the gateway caps the key wherever it is used. Tinfirm still never sends the key to a runner host or a sandbox, and it still shows only its last four characters once entered.

**Checking a key when it is entered.** Tinfirm calls the gateway's `GET /v1/models` with the key. That is the standard model list, served by every candidate, and it confirms that the key works and which models it allows. That model list replaces the per-key models Tinfirm sets in managed mode. Tinfirm cannot check whose key it is; the admin who entered it answers for it, and the audit records who did.

**What Tinfirm still does in this mode:**
- **Pays with the key.** The relay forwards with the paying principal's key, as today. Runs, payers, subscriptions and personal hosts are unchanged.
- **Blocks locally.** A suspended or leaving principal's key is refused in the relay at once. The relay is the only way Tinfirm's runs reach the gateway, so this is enough for Tinfirm's own use. Revoking the key *at the gateway* is the gateway team's job, so Tinfirm tells them: an inbox item for admins and an event on the audit stream, `model_key.revoke_requested` with the key's last four characters, plus a webhook if one is set. Tinfirm deletes its own copy when the principal leaves.
- **Has no key for a new principal.** A principal without a key cannot pay. A new wait reason `NO_MODEL_KEY` holds the principal's conversations and runs, the inbox tells admins, and the conversation tells the person who is waiting. SCIM-provisioned members show up in the inbox list the day they arrive.
- **Does not rotate, but shows age.** Tinfirm cannot rotate a key it did not create. CONVENTIONS §15.4's 30-day rotation becomes the gateway team's duty. Tinfirm shows each key's age, raises an inbox item after a configurable number of days, and swaps a key atomically when a new one arrives through the app or the API. The old key is used until the swap and forgotten after it.
- **Recognises a spent budget.** The refusal is classified per vendor:
  - LiteLLM: 429 `budget_exceeded`, or 400/422 with its wording.
  - Bifrost: *to verify*.
  - Kong: 429 from `ai-rate-limiting-advanced`, with `Retry-After` and its `X-AI-RateLimit-*` headers.
  - `GENERIC`: an admin-configurable status and error-type pattern, defaulting to 402, or to 429 with a budget or quota word in the error.

  The relay turns it into Tinfirm's normalised refusal (§3.2), so the runner needs nothing per vendor.
- **Learns the reset time when it can.** Where a key may read its own state, Tinfirm reads spend, budget and reset time with the key itself and shows them in Members' budgets, as managed mode does. LiteLLM answers `/key/info` for the calling key (*to verify* for current versions). Bifrost's self-read is *to verify*. Kong's are the `X-AI-RateLimit-Reset` headers on each answer. Where it cannot, the key's status is *Budget spent, reset unknown*. Its runs wait with a backoff: first after 15 minutes, then doubling up to 6 hours, retried with a cheap `/v1/models` or a `count_tokens` call. An admin's "Try again now" is also a way out.
- **Settles spend from the relay.** Spend is settled as `RELAY`: the relay's metered tokens priced with Tinfirm's price list, shown with that source as today. For LiteLLM and Bifrost, a key that may read its own logs can settle as `GATEWAY`, as today; *to verify* for both. The relay sends run, conversation and agent as neutral `x-tinfirm-*` headers, plus `x-litellm-tags` for LiteLLM, so the gateway's own logs can attribute spend. With no gateway prices, the shown cost is an estimate, and the gateway's figures are the truth.
- **Platform requests.** These pay with the provided company key, which the admin enters on its row like any other.

**What managed mode keeps and provided mode loses:**
- One-click onboarding: Tinfirm creates the key the moment a principal exists.
- Revocation and blocking at the gateway, done by Tinfirm.
- Rotation by Tinfirm.
- Budgets edited in Tinfirm. In provided mode, Members' budgets shows the gateway's values read-only, where it can read them, with a link to the gateway.
- Exact spend per run from the gateway's logs.

**Compared with the rejected pass-through idea** (§0), one shared key with budgets enforced in Tinfirm's relay:

| | Provided keys | Shared key, budgets in the relay (rejected) |
|---|---|---|
| Who enforces the budget | the gateway, exactly | Tinfirm's server, overshoot up to the answers in flight |
| Revoking one person at the gateway | yes | no |
| A leaked key costs | one person's budget | the whole company's |
| Price list of Tinfirm's own | only to estimate spend | required to enforce |
| Work for the gateway team | a key per person, kept in step | one key |
| New code in Tinfirm | key intake, wait reason, refusal classification | budget ledger and enforcement in the relay, price list |

Every candidate gateway can hold virtual keys with limits, so the rejected variant leaves none of them out.

### 3.4 The body filter per vendor

- `RequestPolicy` carries the vendor's filter. LiteLLM keeps `LiteLlmRequests`.
- Bifrost needs its own list, taken from its request schema and pinned to a version in a test, like today's `all-litellm-params.txt`.
- In provided mode, the vendor's filter applies when the vendor is known (LiteLLM, Bifrost). For Kong and `GENERIC` gateways, the relay keeps the strict part common to all of them: no unknown top-level `metadata`, no credential-shaped or URL-shaped fields. Kong is configured by plugins, not by the body, so this costs nothing there. Whether the strict filter is mandatory is a security decision, since the filter exists so that no caller can redirect the company's provider key.
- `libs/litellm` becomes `libs/gateway-requests`, with one class per vendor. `model-api` takes the vendor as configuration.

### 3.5 Schema, API and text

- **Database.**
  - Migrations rename `litellm_key_id` to `gateway_key_id`, in `principal_model_key` and `company_model_key`, together with their unique index.
  - `model_settings` gains a `vendor` column (`LITELLM` by default, then `BIFROST`, `KONG` and `GENERIC`), a `key_mode` (`MANAGED | PROVIDED`; `BUNDLED` is always managed) and a `model_url` for provided mode. It also gains, for `GENERIC`, the budget-refusal pattern and the key-age warning.
  - `principal_model_key` and `company_model_key` gain `source` (`MANAGED | PROVIDED`), `provided_by`, `provided_at` and the key's last four characters. In provided mode, `gateway_key_id` is the vendor's id where a self-read gives one, else the key's SHA-256. The `relay_usage.key_id` join keeps working either way.
  - The `BUNDLED | EXTERNAL` choice stays as it is.
- **Changing the vendor** is changing the gateway. `ModelSettingsService` already handles that: it deletes the keys at the old gateway after commit and forgets the cache.
- **OpenAPI.** Add `vendor` to `ModelSettingsRequest` and its view, add `modelUrl`, replace the budget-period pattern with an enum, and rewrite the descriptions that say LiteLLM. The secret name `LITELLM_ADMIN_KEY` becomes `MODEL_GATEWAY_ADMIN_KEY`, with a migration of the vault rows' kind.
- **Web.** The Model access page gets a gateway selector with a form per vendor, and the texts become neutral. The budget dialog and the period units change too. In provided mode, Members' budgets turns into a key-intake list: a key field per row, its age, its status from the self-read, the gateway's budget read-only, and a filter *Missing keys*. Rotation buttons are hidden. Each row shows who provided its key (*member* or *admin*). A member's profile gets a *Model key* card while the company allows it.
- **API.** Add `PUT` and `DELETE /principals/{id}/model-key` and `PUT /company-model-key` (provided mode only), plus a bulk `POST /model-keys:import`. For members, add `PUT` and `DELETE /me/model-key`, allowed only while `memberProvidedKeys` is on, and add `memberProvidedKeys` to the model settings. Add `NO_MODEL_KEY` to the queue reasons, and the `model_key.provided`, `model_key.removed` and `model_key.revoke_requested` audit actions.
- **Properties.** Add `TINFIRM_MODEL_GATEWAY_VENDOR`, plus neutral `TINFIRM_MODEL_GATEWAY_*` names, keeping the `TINFIRM_LITELLM_*` names as aliases for a release or two.

### 3.6 Deployment

- **Helm.** Add `gateway.bundled: litellm | bifrost | none`. Bifrost needs its own deployment, service, network policy and config. It keeps its configuration in SQLite or PostgreSQL, so it would take a database or a volume of its own (*verify* which, and how it migrates). The `model-api` sidecar goes with whichever bundled gateway serves its admin API on the model port. `values.schema.json` and the CI values files need one case per vendor.
- **Compose.** The same choice as a profile.
- **Kong.** Never bundled. The docs describe the operator's side: one consumer for Tinfirm, the `ai-proxy-advanced` routes for the formats the runtimes speak, and optionally a logging plugin that picks up the `x-tinfirm-*` headers.

### 3.7 Tests

- Extract a gateway contract test suite from today's integration tests: key lifecycle, rotation, reconciliation, budget refusal, spend settlement and the model-not-served case. Run it against each fake.
- Write `FakeBifrost`, in Java and as an e2e harness port, of about the size of today's fakes. Add a provided-mode fake: a model endpoint that accepts a fixed set of keys, refuses with a configurable budget answer, and puts usage in its answers. Add switches for LiteLLM-style, Kong-style and generic refusals and self-reads.
- Add a nightly or manual job against real gateway images (LiteLLM, Bifrost, Kong OSS with a stub upstream). It catches what fakes cannot: wording, status codes and streaming.
- Re-verify the runtimes (Claude Code, Codex, pi, OpenCode) through each gateway, as ADR-0059 did for LiteLLM. Model naming (`provider/model` in Bifrost) and small models (OpenCode's titles) are the likely breakages.

### 3.8 Docs and ADRs

- **A new ADR** that amends ADR-0059 decision 1 ("LiteLLM is the mandatory model gateway") and ADR-0069. It records that the gateway always enforces the budget, the two modes (managed and provided), and the gateway team's duties in provided mode: keys, revocation and rotation.
- **docs/operations.** Split `litellm.md` into `model-gateway.md` (common) plus a page per vendor.
- **docs-site.** Rewrite the operator guide's `model-gateway.md`, and give each vendor its own recipe.
- **Threat model.** Add the following:
  - Keys created and seen outside Tinfirm: who at the company has them, and member self-service if allowed.
  - A leaver's key, alive at the gateway until the gateway team revokes it.
  - Unrotated keys.
  - Each vendor's body fields.

  Also update R23's retirement note.
- **PRODUCT.md.** Replace "LiteLLM's keys stay on the server" and "one key on the company's LiteLLM gateway" with the neutral wording.

## 4. Plan

| Phase | Content | Behaviour change |
|---|---|---|
| 0 | ADR; adapter interface with LiteLLM as its only implementation; neutral names (columns, secret, properties with aliases, texts); budget period as an enum; normalised budget refusal in the relay and the runner | none for LiteLLM companies; one runner release |
| 1 | Provided mode: settings, key intake (app, API, bulk, members' own keys behind the company setting), `/v1/models` check, local blocking and revoke requests, `NO_MODEL_KEY`, key age, refusal classification for LiteLLM, Kong and generic, self-read for LiteLLM, relay settlement with a price list for estimates, `x-tinfirm-*` headers, fake, docs (generic page plus Kong, LiteLLM and Bifrost recipes) | new option |
| 2 | Bifrost as a managed gateway: adapter, body filter, `FakeBifrost` (Java and e2e), Helm and Compose bundle option, runtime re-verification, docs | new option |
| 3 (on demand) | Kong managed mode; Portkey or OpenRouter recipes | new option |

Phase 0 is worth doing alone. It takes LiteLLM's wording out of the runner and its name out of the schema and the API, and it makes the later phases additive.

## 5. Risks

- **Drift between Tinfirm and the gateway.** People join, leave and change budgets in Tinfirm, but their keys live in the gateway. Missing keys block work (`NO_MODEL_KEY`), and leavers' keys stay alive at the gateway. The inbox items, the revoke events and the bulk API are what keep the two in step. Without automation on the gateway team's side, the work grows with headcount.
- **Onboarding friction.** A new member cannot work until a key arrives. Managed mode remains the answer for companies that accept giving an admin key.
- **Lowest common denominator.** Keep per-vendor capabilities explicit (`capabilities()`) rather than shrinking every gateway to what all of them support. Blocking a key, budget alerts read from the gateway, and native rotation stay where the vendor has them.
- **Upkeep.** Each managed vendor is an admin API and a body filter that change with its releases. Pin images and lists, and run the real-image job.
- **Spend that differs from the gateway's.** In provided mode, without access to the gateway's logs, Tinfirm's spend per run is an estimate from its own prices. Show the source (`RELAY`) as today, and point to the gateway's figures.
- **Unknown reset times.** For a gateway that does not report them, waiting runs retry on a backoff, so a budget that resets at midnight may resume work up to the backoff's step late.

## 6. Questions left open

Provided mode, members' own keys and the rejection of the shared-key variant are decided (§0). Still open:

1. Which gateway is bundled by default, and is a second bundled gateway (Bifrost) worth a second set of chart templates and images?
2. Are SaaS gateways (OpenRouter, Portkey's cloud) allowed, given "No vendor cloud"?
3. Does the strict body filter stay mandatory for every vendor, including Kong and generic gateways whose body fields are unknown?

## Sources

- Bifrost: [Virtual keys](https://docs.getbifrost.ai/features/governance/virtual-keys), [Budget and limits](https://docs.getbifrost.ai/features/governance/budget-and-limits), [Set virtual key budget override](https://docs.getbifrost.ai/api-reference/governance/set-virtual-key-budget-override), [Governance in config.json](https://docs.getbifrost.ai/deployment-guides/config-json/governance), [Helm governance guide](https://github.com/maximhq/bifrost/blob/dev/docs/deployment-guides/helm/governance.mdx)
- Kong: [ai-rate-limiting-advanced](https://docs.konghq.com/hub/kong-inc/ai-rate-limiting-advanced/), [its examples](https://developer.konghq.com/plugins/ai-rate-limiting-advanced/examples), [Tiered AI budgets with Kong Identity](https://developer.konghq.com/ai-gateway/enforce-tiered-ai-budgets-with-kong-identity/)
- Portkey: [Rate limits on virtual keys](https://portkey.ai/docs/product/ai-gateway/virtual-keys/rate-limits), [Enforce budget and rate limit](https://portkey.ai/docs/aigw/product/administration/enforce-budget-and-rate-limit.md)
- agentgateway: [Virtual keys](https://agentgateway.dev/docs/kubernetes/2.2.x/llm/cost-controls/virtual-keys/)
- Tinfirm: [ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md), [ADR-0065](../adr/0065-company-model-key.md), [ADR-0069](../adr/0069-company-model-gateway.md), [operations/litellm.md](../operations/litellm.md)
