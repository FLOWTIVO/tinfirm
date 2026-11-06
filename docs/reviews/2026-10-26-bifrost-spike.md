# Bifrost spike: does it fit as a second managed gateway?

A spike of 2026-10-26, after the [gateway analysis](2026-10-26-multiple-model-gateways.md). It answers that analysis's *to verify* items for Bifrost by running it, not from its documentation alone. The answers come from Bifrost's own docs and code at the tag `transports/v2.3.2` (Apache-2.0), and from live calls against a binary built from that tag. The binary ran in front of a mock provider that logs every request it receives: [mock-provider.mjs](2026-10-26-bifrost-spike/mock-provider.mjs), with the spike's [config.json](2026-10-26-bifrost-spike/config.json) and [pricing.json](2026-10-26-bifrost-spike/pricing.json).

**Not verified:**
- A real provider.
- The real runtimes (Claude Code, Codex, pi, OpenCode).
- PostgreSQL as Bifrost's store (the spike used SQLite).
- Several replicas.
- Load.

**In short.** Bifrost fits the managed mode with no change to Tinfirm's domain, and it is simpler to manage than LiteLLM in three places:

- **Rotation.** It rotates a key natively, keeping the key's id, its budget and its spend, with a grace period for the old value. Tinfirm's create, grace, block, delete dance, and its copying of spend to the new key, would not be needed.
- **Self-read.** It has a self-service quota endpoint, where a key reads its own budget, usage and status. Provided mode gets budget and reset information for free.
- **Request body.** It parses each request into its own types and drops what it does not know. A caller cannot redirect a request through body fields. The relay's body filter for Bifrost is a few names, not LiteLLM's 150.

Six things need work on Tinfirm's side or a decision. In order of weight:

1. **Bifrost does not start without its price list.** It downloads the list from `getbifrost.ai` at boot, and without it the boot is fatal. A bundled or air-gapped deployment must ship a price file. The `pricing_url` setting takes a `file://` URL.
2. **Admin access is one username and password.** Basic auth or a session, with no scoped API token in the open-source edition. Until an admin account exists, the admin API is open to anyone who reaches the port. The admin API and the model API share one port.
3. **Its streamed Anthropic answers break Tinfirm's usage meter as it is.** Bifrost's `message_delta` event reports `input_tokens: 0`, and `RelayUsageMeter` keeps the last value of each field. The relay would count no input tokens. The fix is small and independent of the vendor: keep the largest value of each running total.
4. **Its model list names models `provider/model`** (`anthropic/claude-sonnet-4-5`), while requests without the prefix work. Tinfirm's model lists and allow-lists need to map between the two forms.
5. **Its errors differ by route.** A spent budget is 402, not LiteLLM's 429. It is `billing_error` in the Anthropic format and `budget_exceeded` in Bifrost's own format on the OpenAI routes, and it gives no reset time.
6. **Its request logs carry Tinfirm's tags** through `x-bf-lh-*` headers, and its spend per request is exact. The logs are written asynchronously, after a few seconds up to about 25 seconds in the spike, like LiteLLM's batches.

**Recommendation.** Bifrost as the second managed gateway, phase 2 of the analysis. It needs first:

- A usage meter that keeps the largest value of each running total (point 3): reading the last value is a latent bug for any gateway that reports running totals with zeros.
- A way for a bundled Bifrost to get its prices and its admin credentials (points 1 and 2).

Keep LiteLLM as the bundled default until Bifrost has run with the real runtimes against real providers (§3).

## 1. What was asked, and what Bifrost answered

| Question from the analysis | Answer at v2.3.2 | How it was checked |
|---|---|---|
| Per-principal keys by API | `POST/GET/PUT/DELETE /api/governance/virtual-keys`; the answer to `POST` carries the key's `value` (`sk-bf-…`) and its `id` (a UUID) | live |
| Key name unique, findable | names are unique (`409` on a duplicate); list by `?search=` and by metadata, `?metadata_<key>=<value>` | live |
| Metadata on a key | up to 50 string entries, copied onto every log entry of the key; a request cannot override them | docs, live |
| Company grouping | teams (`/api/governance/teams`), and customers above them; a key belongs to one team or one customer | live (team) |
| Budget | `budgets: [{max_limit, reset_duration}]`, with `1m 1h 1d 1w 1M 1Q 1Y`. `calendar_aligned` is a field of the key or team, not of the budget, and resets at UTC boundaries. A rolling window counts from the key's creation otherwise | live; calendar reset not observed |
| Changing a budget | `PUT` with a new limit keeps `current_usage` | live |
| Temporary extra budget | `PUT …/budgets/{id}/override`, adds to the limit for some cycles or until removed | docs |
| Enforcement and overshoot | the check happens before a request, against usage so far; the request that crosses the limit is served (0.021 spent against 0.02), as with LiteLLM | live |
| Spent-budget refusal | `402`. Anthropic route: `{"type":"error","error":{"type":"billing_error","message":"Budget exceeded: …"}}`. OpenAI routes: Bifrost's envelope, `{"type":"budget_exceeded","status_code":402,"error":{"message":…}}`. No reset time in the answer | live |
| Blocked key | `PUT {"is_active": false}` → `403`; Anthropic route `permission_error` "virtual key is inactive"; back at once with `true` | live |
| Model not allowed | `403 permission_error` "Model 'x' is not allowed for virtual key 'y'" when the model names its provider; `400 invalid_request_error` "could not auto resolve a provider…" for a bare model name the key's providers do not serve | live |
| Rotation | `POST /api/governance/virtual-keys/{id}/rotate`: a new value, the **same id, budget and spend**. With `client.vk_rotation_cooldown` (a gateway-wide setting, for example `1h`), the old value works until `previous_value_expires_at`; without it, it stops at once. Also bulk `POST /virtual-keys/rotate` | live |
| Deletion | `DELETE`; every value of the key stops at once, the previous one included | live |
| Expiry | optional `expires_at`, `403` after it | docs |
| A key reading its own state | `GET /api/governance/virtual-keys/quota` with the key as credential: budgets with `max_limit`, `current_usage`, `reset_duration`, `last_reset`, usage per model, `is_active`, allowed models. Next reset = `last_reset + reset_duration` | live |
| A key reaching the admin API | `401` on every admin route, whichever header carries it; only `/quota` takes a key | live |
| Spend per request | `GET /api/logs?virtual_key_ids=…` or `?metadata_<key>=…`: per entry `cost`, `token_usage` with a cost breakdown, model, provider, key id and name, team, and metadata | live |
| Tinfirm's tags on a request | headers `x-bf-lh-<name>: <value>` become metadata `<name>`, for example `x-bf-lh-run: run-42` → `metadata.run`. No configuration needed | live |
| When logs are written | asynchronously: absent 4 seconds after the answer, present within about 25 seconds | live |
| Prices | its own model catalog (`pricing_url`), in a format like LiteLLM's (`input_cost_per_token`, `output_cost_per_token`); overrides by API (`/api/governance/pricing-overrides`). `/v1/models` returns each model's prices | live |
| Formats for the runtimes | Anthropic `/anthropic/v1/messages` (plain and streamed), `/anthropic/v1/messages/count_tokens`, `/anthropic/v1/models`; OpenAI `/v1/…` and `/openai/v1/…` chat completions and responses (plain and streamed) | live, against the mock |
| Model names | requests take `claude-sonnet-4-5` or `anthropic/claude-sonnet-4-5`. `/v1/models` lists `anthropic/claude-sonnet-4-5`; `/anthropic/v1/models` lists bare names | live |
| Anthropic fidelity | `anthropic-beta` and `anthropic-version` pass through; `cache_control`, `thinking`, `tools`, `context_management` and `metadata` arrive upstream. The body is rebuilt from Bifrost's types (a string `content` becomes a text block; `input_schema` gains `properties: {}`) | live, against the mock |
| Body fields of its own | none acted on: `fallbacks`, `base_url`, `api_base`, `api_key`, `extra_params` and `mock_response` in a body were all dropped before the provider. `extra_params` is merged into the body only with the request header `x-bf-passthrough-extra-params: true` | live |
| Headers of its own | `x-bf-direct-key` (a caller's own provider key) is ignored, since it was removed in v1.5.0. `x-bf-eh-<name>` adds a header to the provider's request (`x-bf-eh-x-custom` arrived upstream; `x-bf-eh-x-api-key` did not replace the key). `x-bf-api-key` picks a provider key by name among those the virtual key may use | live |
| Admin and model on separate ports | no: one port (8080) for the model API, the admin API, the UI and `/metrics` (admin routes and `/metrics` need credentials) | live |
| Admin credentials | one admin username and password (Basic auth, or a session after login); SSO and SCIM are Enterprise. **Until an admin account exists the admin API is open**; creating the first one needs a setup token set by the operator (`setup_token` or `BIFROST_SETUP_TOKEN`) | docs, live |
| Store | SQLite by default, PostgreSQL supported, for configuration and logs separately; migrations run at boot | live (SQLite) |
| Footprint | the binary was 222 MB unstripped (the image strips it); about 240 MB resident after the spike's calls, against the 2 GB memory limit Tinfirm's Compose file allows LiteLLM (`TINFIRM_LITELLM_MEM_LIMIT`) | live, one process, no load |
| Price list at boot | fetched from `https://getbifrost.ai/datasheet`; with no network and no stored copy, the boot fails (`FTL failed to initialize pricing manager`). `framework.pricing.pricing_url` takes a `file://` URL | live |

## 2. What this means for Tinfirm

### 2.1 Managed mode (the Bifrost adapter)

The `LiteLlm` operations map one to one:

| Tinfirm (`LiteLlm`) | Bifrost |
|---|---|
| `ensureTeam` | `GET/POST /api/governance/teams` (the team's name is `tinfirm-{companyId}`; keep its Bifrost id in `model_settings`) |
| `generate` | `POST /virtual-keys` with `name` = today's alias, `team_id`, `metadata` (`tinfirm_kind`, `tinfirm_principal`), `provider_configs` from the allowed models, `budgets` |
| `update` | `PUT /virtual-keys/{id}` (budget, models); usage is kept |
| `block` / `unblock` | `PUT {"is_active": false/true}` |
| `delete` / `deleteByAlias` | `DELETE /virtual-keys/{id}`; by alias through `?search=` first |
| `info` | `GET /virtual-keys/{id}`, or `/quota` with the key |
| `teamKeys` (the sweep) | `GET /virtual-keys?…` filtered by team or by the metadata `tinfirm_company` |
| `models` | `GET /v1/models` with the admin's view, or the providers' model lists |
| `spendLogs` | `GET /api/logs?virtual_key_ids=…&metadata_run=…` |
| `prices` | `/v1/models` (prices per model) or the catalog |
| `chat` (the company key) | `/v1/chat/completions`, tagged with `x-bf-lh-tinfirm-task: {purpose}` |

What changes beside the mapping:

- **Key id.** `gateway_key_id` holds Bifrost's UUID, not the key's SHA-256. The relay's usage rows (`relay_usage.key_id`) need the same id. The relay knows which principal pays, so it can store the id from the key row instead of hashing the key.
- **Rotation becomes native.** `KeyRotation` for Bifrost is: rotate, save the new value, and let the cooldown cover the switch. There is no new alias, no second key, no copying of spend, and no block-then-delete of the old key. The cooldown is one setting for the whole gateway: the bundled config sets it, and the adapter's test checks it on an external gateway. `key_generation` stays as Tinfirm's counter, but the alias no longer changes.
- **Allowed models.** Bifrost wants them per provider (`provider_configs[].allowed_models`). Tinfirm keeps one list of names. The adapter groups them by provider using the gateway's model list, where each name carries its provider.
- **Budget period.** Tinfirm's neutral period from phase 0 maps to `1d`, `1w` or `1M`, with `calendar_aligned: true` on the key, to match LiteLLM's calendar resets.
- **Refusals.**
  - **Spent budget:** `402`, with `billing_error` on the Anthropic route or `budget_exceeded` on the OpenAI routes. The adapter classifies it as `BUDGET_SPENT`. The reset time comes from `/quota` (`last_reset + reset_duration`).
  - **Blocked key:** `403` with "virtual key is inactive" means `KEY_REFUSED`.
  - **Model not served:** `403` "is not allowed for virtual key", or `400` "could not auto resolve a provider", means `MODEL_NOT_SERVED`. These are wordings, so pin them in a test against the image.
- **Spend settlement.** It works as for LiteLLM: logs per key, matched by `metadata.run`, with exact costs. The two-minute settle delay is enough for the 25 seconds seen in the spike.

### 2.2 Provided mode

- **Self-read.** `/quota` answers, with the key alone, what provided mode needed most: whether the key is active, its budget, usage, reset and allowed models. Bifrost is the best fit among the candidates for keys that a gateway team creates.
- **Budget refusal.** Use the same classification as managed mode.
- **Exact spend needs log access.** It needs `/api/logs`, which needs the admin credentials. Without them, spend is settled from the relay's own counts (`RELAY`), as planned.

### 2.3 The relay and the model port

- **The body filter for Bifrost is short.** It refuses or strips `extra_params` and `fallbacks`. Bifrost already drops the rest, `fallbacks` included in the spike, but Tinfirm should not depend on that.
- **The relay's header allow-list already keeps every `x-bf-*` header out.** Most importantly that covers `x-bf-eh-*`, which would let a sandbox add headers to the provider's request, and `x-bf-api-key`, which picks a provider key. The relay then *adds* its own `x-bf-lh-run`, `-conversation` and `-agent`, as it adds `x-litellm-tags` today. The model port must strip callers' `x-bf-*` headers the same way.
- **A model port is still needed for a bundled Bifrost** that company services reach (ADR-0069). The admin API shares the port, and a password is all that guards it. The `model-api` sidecar would take Bifrost as its upstream with Bifrost's filter, and network policies keep 8080 for Tinfirm's server.
- **The usage meter must keep each field's largest value.** `RelayUsageMeter` reads each field's last value. Bifrost's streamed `message_delta` carries `input_tokens: 0`, which would overwrite the 1000 from `message_start`. Keeping the largest value of each field is right for Anthropic, OpenAI and the Responses API, which all report running totals. It fixes Bifrost and costs nothing for LiteLLM.

### 2.4 Deployment

- **Prices.** Ship a price file with the chart and Compose, refreshed with releases, and set `pricing_url` to it. Otherwise a Bifrost without internet access does not boot. Admins' pricing overrides go through Bifrost's own API.
- **Admin credentials.** Generate them with the deployment, as the LiteLLM master key is today: `admin_username`, `admin_password` and `setup_token` from a Secret, with `auth_config.is_enabled` set at the first boot. Otherwise the admin API is open until someone creates an account. Compose's `deploy-host.sh`, which generates LiteLLM's secrets for a new host, would generate these too.
- **Store.** PostgreSQL in the deployment, with its own database and role as LiteLLM has (*to verify*: its migrations against PostgreSQL, and two replicas sharing it). SQLite only for a single-node Compose install.
- **Content logging.** Set `client.disable_content_logging: true`, the equivalent of LiteLLM's `turn_off_message_logging`. Bifrost then logs metadata and cost, not prompts.

## 3. What the spike did not settle

1. The real runtimes (Claude Code, Codex, pi, OpenCode) through Bifrost against real providers, as ADR-0059 ran them through LiteLLM, in particular:
   - Bifrost's rebuilt Anthropic bodies, including fields it does not know yet.
   - Its extra `extra_fields` in streamed Responses events.
   - Codex over the Responses API.
2. PostgreSQL as Bifrost's store, two replicas, and whether its in-memory budget counters stay right across them. Clustering is listed as Enterprise; what the open-source edition does with two replicas is not known.
3. A calendar-aligned reset, observed over a day boundary.
4. Whether `/v1/models` with admin credentials lists every model the gateway serves, which the adapter needs to group allowed models by provider.
5. The licence and support terms of Bifrost Enterprise, for a company that needs SSO, audit or clustering from it.

## 4. How to repeat it

```sh
git clone --depth 1 --branch transports/v2.3.2 https://github.com/maximhq/bifrost
cd bifrost/transports && mkdir -p bifrost-http/ui && echo stub > bifrost-http/ui/index.html
GOWORK=off CGO_ENABLED=1 go build -trimpath -o bifrost ./bifrost-http
node mock-provider.mjs 9911 &              # logs requests at GET /_log
./bifrost -app-dir <dir with config.json, pricing.json and an empty params.json> -port 8080
```

The calls in §1 were made with `curl` against `localhost:8080`, with admin credentials `admin:spike-pass` and the virtual key the `POST` returned. Each request's upstream form was read from the mock's `/_log`.
