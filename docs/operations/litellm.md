# Model access through LiteLLM

The public guides are [Run the model gateway](../../docs-site/content/operator-guide/model-gateway.md) (https://docs.tinfirm.com/operator-guide/model-gateway/), for operators, and [Model access and budgets](../../docs-site/content/admin-guide/model-access.md) (https://docs.tinfirm.com/admin-guide/model-access/), for a company's Owners and Admins. This page keeps what the people who build Tinfirm need beyond them: the decisions behind the gateway with their ADRs and review rows, how the bundled gateway, the model port and the relay are secured in detail, the admin routes and key aliases Tinfirm uses, the API, error codes and audit events of **Model access**, the Compose hosts deployed by script, and the keys of the company's own services.

[LiteLLM](https://github.com/BerriAI/litellm) is the model gateway Tinfirm manages ([ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md), [PLATFORM.md §11.2](../PLATFORM.md#112-model-access)). A company may instead use a gateway whose keys its own gateway team provides, which Tinfirm reaches by its model URL alone ([ADR-0094](../adr/0094-more-than-one-model-gateway.md); the public guide's [A gateway whose team makes the keys](../../docs-site/content/operator-guide/model-gateway.md#a-gateway-whose-team-makes-the-keys)); this page is about LiteLLM. Every principal, member or service account, has one LiteLLM key with a budget, which Tinfirm creates and keeps in its vault, and every model request of a run paid by a budget goes through the server's model relay to LiteLLM with the paying principal's key. The bundled LiteLLM is the company's gateway, not only Tinfirm's ([ADR-0069](../adr/0069-company-model-gateway.md)): its model port serves the company's other services too, such as a company's own reporting service (section 8). The one exception to the budget is a member's subscription on their own personal runner host, which LiteLLM does not meter ([runner/README.md](../../runner/README.md)).

## 1. The bundled gateway

The public guide's [The bundled gateway](../../docs-site/content/operator-guide/model-gateway.md#the-bundled-gateway) has the services, addresses, Secrets and settings in Compose and Helm. Beyond it:

- The image is LiteLLM's non-root one, `ghcr.io/berriai/litellm-non_root:v1.106.6`, pinned by digest, the same in `deploy/compose/compose.yaml`, `compose.saml.yaml` (for its Python) and the chart's `litellm.image` ([helm.md, section 2.7](helm.md#27-litellm) for bumping it and how it runs).
- In Compose, LiteLLM sits on the network `gateway` with the server, the model port and the PostgreSQL of its database, and nothing else. The one-shot service `litellm-db` creates its database and role and sets the password on every `up` (`deploy/compose/postgres/litellm.sql`). Its settings are `deploy/compose/litellm/config.yaml`; the chart's ConfigMap `<release>-litellm` carries the same.
- With Helm, its database is on the external PostgreSQL ([external-database.md, section 4](external-database.md#4-litellms-database)), and its Secret is [helm.md, section 1](helm.md#1-create-the-secrets).

The server gets three variables ([CONVENTIONS §4](../CONVENTIONS.md#4-runtime-contract-of-the-server)): LiteLLM's own port for its admin API, and the model port for the model requests it relays and its own.

| Variable | Compose | Helm |
|---|---|---|
| `TINFIRM_MODEL_GATEWAY_ADMIN_URL` | `http://litellm:4000` | `http://<release>-litellm.<namespace>.svc:4000` |
| `TINFIRM_MODEL_GATEWAY_MODEL_URL` | `http://model-api:4001` | `http://<release>-litellm.<namespace>.svc:4001` |
| `TINFIRM_MODEL_GATEWAY_ADMIN_KEY` | `LITELLM_MASTER_KEY` of `.env` | `master-key` of the Secret |

The deployment bundles a LiteLLM only when both the admin URL and the master key are set; without `TINFIRM_MODEL_GATEWAY_MODEL_URL`, the model requests go to the admin URL. Compose and the chart also set `TINFIRM_MODEL_GATEWAY_VENDOR` to `LITELLM`, its default and the only value the server accepts. The variables' older names, `TINFIRM_LITELLM_ADMIN_URL`, `TINFIRM_LITELLM_MODEL_URL` and `TINFIRM_LITELLM_MASTER_KEY`, are still read when the new ones are unset ([ADR-0094](../adr/0094-more-than-one-model-gateway.md)).

## 2. How it is secured

The [security review](../reviews/2026-10-12-platform-redesign.md#security-review--2026-10-12) asked for three things (row 17), and the owner then decided that runner hosts never reach LiteLLM (row 21, [ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md) decision 2). The public guide's [How it is secured](../../docs-site/content/operator-guide/model-gateway.md#how-it-is-secured) says what that means for the operator; the details:

- **Prompts and answers are never stored.** `store_prompts_in_spend_logs: false`, in the settings file and as `STORE_PROMPTS_IN_SPEND_LOGS=False`; error strings in the spend logs are redacted. `disable_error_logs: true`, because LiteLLM's error table would keep a failed request's arguments. `turn_off_message_logging: true`: no message content for logging callbacks, should anyone add one. A setting in the settings file wins over the same setting stored through the admin API, so the master key cannot turn these back on.
- **Only the server reaches LiteLLM's own port**, so none of its routes (key, team, user, model and spend management, the pass-through routes, the UI) is in a runner host's reach, whatever path or encoding it sends.
- **The model port passes the model routes alone** ([ADR-0069](../adr/0069-company-model-gateway.md), [model-api/README.md](../../model-api/README.md)): each by its exact path (chat completions, completions, responses, embeddings, messages and their token count, rerank, the model list), every other path `404` without reaching LiteLLM, a query string dropped, LiteLLM's own fields removed from every body by the same filter as the server's relay (`LiteLlmRequests`, `libs/litellm`), a tool the provider would run refused as at the relay (below), only the formats' headers passed on. It holds no key: even the master key reaches only the model routes through it.
- **The server relays the runs' model traffic** ([CONVENTIONS §15.4](../CONVENTIONS.md#154-model-access-and-runner-hosts)). A run's `modelAccess` names the server's model relay, `{TINFIRM_PUBLIC_URL}/api/runner/v1/model/…`, and a one-run token: the Anthropic format for Claude Code, pi and OpenCode, OpenAI's for Codex. The runner host's [model proxy](../../runner/README.md#model-proxy) sends the run's model requests there. The server accepts the token only while the run is active on that host and only with that host's own token, which the model proxy sends in `X-Tinfirm-Runner-Token` to the server's origin alone (section 5.5). It removes LiteLLM's own request fields from the body (an upstream URL, credentials, metadata, tags and the like), sets `"drop_params": true` (`RelayBody`), passes only the format's headers, allows only the models the paying principal's key allows, and forwards the request to the gateway's model URL with that principal's key, streaming the answer back. It tags each request with its run, conversation and agent (`x-litellm-tags`). A body is read as it arrives, its bytes counted against the replica's budget as they come, a declared length too, and must arrive whole within `TINFIRM_MODEL_RELAY_BODY_TIMEOUT` (30 s; else `408`); Tomcat's `server.tomcat.connection-timeout` (20 s) bounds each wait for its next part.
- **The provider is a channel for what the model emits, never a relay the agent directs, and never runs a tool itself** (the [security review of 2026-10-27](../reviews/2026-10-27-security-review.md), M1 and D8; threat model 7.2, 13.4, R13; [ADR-0108](../adr/0108-no-provider-run-tools.md)). The providers can run tools themselves: search or fetch the web, run code in a container, call an MCP server at any URL. Through the relay a prompt-injected agent held by the egress allowlist could have the provider carry what it writes to any host, with no audit, grant or rate limit of Tinfirm's. Every web search and every MCP call of a run goes through Tinfirm instead (web search through a search MCP server the company registers as a system, ADR-0071). So the relay, and the model port with the same filter (`LiteLlmRequests`), always, with no setting to change it:
  - refuses `400`, naming the field, a body that sets `mcp_servers` (Anthropic's MCP connector), `container` (Anthropic's code execution container), `web_search_options` (Chat Completions' web search) or `search_parameters` (xAI's live search) to anything but `null`, or names a prompt stored at the provider (an OpenAI Responses `prompt` object, which can carry its own tools). These are refused, not removed: no pinned runtime sends them (Claude Code, Codex, OpenCode and pi run MCP themselves), so a request that has one was not built by a runtime, and a silent removal would hide why the model never got its tool;
  - refuses `400`, naming the type, a tool whose `type` is not one the runtime runs, wherever the body defines one (`tools`, a `namespace`'s or a `tool_search_output`'s tools, an Anthropic `tool_addition` block): web search and fetch of every version (Anthropic's `web_search_*` and `web_fetch_*`, the dynamic-filtering `web_search_20260209` and later among them; OpenAI's `web_search` and `web_search_preview`; xAI's `x_search`), code execution, MCP (`mcp_toolset`, OpenAI's `mcp` to a server or a connector), file search, image generation, computer use. It also refuses a tool that names one of Gemini's built-in tools by a field (`googleSearch`, `codeExecution`, `urlContext`, `enterpriseWebSearch`, ..., either spelling; `LiteLlmRequests.PROVIDER_TOOL_KEYS`), and a tool whose `allowed_callers` names anything but `direct` (the provider's code execution calling it). What passes: `function`, `custom` (and an Anthropic tool without a type), `namespace`, `local_shell`, OpenAI's `tool_search` and Anthropic's `tool_search_tool_*` (they only pick among the request's own tools), and Anthropic's client tools `bash_*`, `text_editor_*` and `memory_*` (`LiteLlmRequests.CLIENT_TOOL_TYPES`, one comment per type);
  - passes only the `anthropic-beta` and `OpenAI-Beta` values on its lists (`TINFIRM_MODEL_RELAY_ANTHROPIC_BETAS`, `TINFIRM_MODEL_RELAY_OPENAI_BETAS`; by default the pinned runtimes' request-shaping betas, `ModelProperties.ANTHROPIC_BETAS`); another value is dropped from the forwarded request, never refused. Never add one that lets the provider act for the request (`mcp-client-*`, `mcp-servers-*`, `code-execution-*`, `web-fetch-*`, `advanced-tool-use-*`, `skills-*`, `files-api-*`). When a runtime upgrade brings a beta the list lacks, the feature is off until the list has it; a body that needs it is the provider's `400`.

  Until 2026-11-06 a company's admin could let the providers' web search through (`providerTools: WEB_SEARCH` on `PUT /model-settings`, column `model_settings.provider_tools` of V076), and the model port had its own `MODEL_API_PROVIDER_TOOLS`. ADR-0108 removed both: `V083` dropped the column, the API no longer has the field (a client that still sends it changes nothing), and the model port, the Helm value `litellm.modelApi.providerTools` and Compose's `TINFIRM_MODEL_API_PROVIDER_TOOLS` are gone. The runner host turns the runtimes' own provider-run tools off in every run, through a gateway or not (a key of the runner host, a Claude or ChatGPT login, Bedrock), since no Tinfirm run may use one: Codex 0.156.1 gets `web_search = "disabled"` in its configuration, and Claude Code gets WebSearch (Anthropic's server-side `web_search` tool) as a disallowed tool of its session (`_meta.claudeCode.options.disallowedTools`); Claude Code's WebFetch fetches in the sandbox, through its egress proxy, and stays. A model whose provider searches the web by itself, whatever the request says (Perplexity's Sonar models, OpenAI's `*-search-preview` models), is a choice of the gateway's model list: leave such models out of it.
- **Each company is a LiteLLM team**, `tinfirm-{companyId}`, whose keys carry the company in their alias and metadata (section 3).
- **The admin UI is off** (`DISABLE_ADMIN_UI=True`), unless the Helm value `litellm.adminUi` turns it on.
- **No LiteLLM key leaves the server.** The principals' keys and the company key stay in Tinfirm's vault; each reveal is audited as `secret.accessed`. A runner host holds only its run's one-run token, which its model proxy keeps out of the sandbox (the sandbox gets a dummy key).
- The master key, the salt key and their rotation: the public guide's [Rotate secrets and keys](../../docs-site/content/operator-guide/model-gateway.md#rotate-secrets-and-keys).

## 3. What Tinfirm manages

Tinfirm manages the company's gateway through LiteLLM's admin API, with the master key (or, for a company's own gateway, the admin key an admin entered). Its client calls the key routes (`/key/generate`, `/key/update`, `/key/block`, `/key/unblock`, `/key/delete`, `/key/info`, `/key/list`), the team routes (`/team/info`, `/team/new`), the model list (`/v1/models`), the model prices (`/model/info`) and the spend logs (`/spend/logs`), and the chat completions route with the company key for its own requests (section 5.3). It never adds, changes or deletes a model or a provider key.

- A LiteLLM team per company, `tinfirm-{companyId}`.
- One key per principal (member or service account), alias `tinfirm-{companyId}-{principalId}`, `key_type` `llm_api` (the model routes only), with its budget, budget period and models, and metadata naming the company and the principal; kept in Tinfirm's vault; blocked while the principal is suspended, deleted when they leave; rotated (section 5.4).
- One key of the company's own, alias `tinfirm-{companyId}-company`, which pays for Tinfirm's own requests ([ADR-0065](../adr/0065-company-model-key.md), section 5.3).
- A reconciliation every 5 minutes, on a `worker` replica, that repairs drift: a key LiteLLM lost is made again, a stray key of Tinfirm's alias deleted, every key's spend read.

Tinfirm touches only the keys whose alias starts with `tinfirm-{companyId}-`.

## 4. Set it up

### 4.1 Docker Compose

The Compose stack's LiteLLM is in the public guides' [The bundled gateway](../../docs-site/content/operator-guide/model-gateway.md#the-bundled-gateway) and [Let runs reach the relay](../../docs-site/content/operator-guide/model-gateway.md#let-runs-reach-the-relay); its `.env` values in [The Compose file's variables](../../docs-site/content/reference/configuration.md#the-compose-files-variables). For hosts deployed with `deploy-host.sh` or tinfirm-auto-deploy, and for development:

- `deploy/compose/deploy-host.sh` generates `LITELLM_MASTER_KEY`, `LITELLM_SALT_KEY` and `LITELLM_DB_PASSWORD` for a new host and adds them to an existing `.env` that lacks them. [tinfirm-auto-deploy](auto-deploy.md) adds missing secrets too. Never generate a new salt key for a `.env` that has one.
- `TINFIRM_MODEL_API_IMAGE` defaults to `tinfirm-model-api:local`, which `make up` builds. A host that does not build its images needs a published one, `ghcr.io/flowtivo/tinfirm-model-api:sha-<full commit SHA>`: `compose up --no-build` cannot start the model port without it. `deploy-host.sh` sets it to the image it built and shipped, and [tinfirm-auto-deploy](auto-deploy.md) to the published one of the deployed revision, each beside `TINFIRM_SERVER_IMAGE`; a value set by hand is overwritten on the next deploy.
- `TINFIRM_MODEL_API_HOST_PORT` publishes the model port on `127.0.0.1` for a service run from the IDE.
- `tinfirm-auto-deploy` dumps the `tinfirm` database only: dump `litellm` with it, from the same point in time where you can.

### 4.2 Providers and models

How to add providers and models, the providers' settings, the model names runtimes ask for and the prices are in the public guide's [Add providers and models](../../docs-site/content/operator-guide/model-gateway.md#add-providers-and-models). Beyond it:

- The bundled settings keep no model list in the settings file: `store_model_in_db: true` keeps every model added through the admin API in LiteLLM's database, its provider key encrypted with the salt key.
- The provider prefixes of `litellm_params.model` are those of LiteLLM v1.105.1.
- Settings that pay with a budget and name no model do not run: a runtime's own default changes with its releases and need not be one the gateway serves. The agent's readiness and the conversation's start refuse with `MODEL_REQUIRED`, a turn that gets that far fails with it, and a trigger's settings are not saved without a model.
- When the gateway answers that it does not serve a model a run asked for, the run fails with `MODEL_NOT_SERVED` and names the model, whatever the runtime made of LiteLLM's error.

## 5. Model access in Tinfirm

The page **Model access** is in the public guide [Model access and budgets](../../docs-site/content/admin-guide/model-access.md). It takes `MANAGE_MODEL_ACCESS`. The API is `/api/companies/{companyId}/model-settings` and `…/model-access` (tags `ModelSettings` and `ModelAccess` in [api/openapi.yaml](../../api/openapi.yaml)).

### 5.1 The gateway

- A test that fails answers `502 GATEWAY_UNREACHABLE` with LiteLLM's reason.
- Until the gateway passes its test, a conversation on a budget is refused with `GATEWAY_NOT_CONFIGURED`, and a trigger's editor with `MODEL_ACCESS_UNAVAILABLE` (reason `GATEWAY_NOT_CONFIGURED`).
- The row **Relay** is `relayBaseUrl`. The settings are audited as `model_settings.changed`, never with a key; no setting lets a provider run a tool itself (section 2).

### 5.2 Budgets

- **Period** is Tinfirm's own, in the words of LiteLLM's `budget_duration`: a number and `h`, `d`, `w` or `mo`, which the LiteLLM adapter writes as they are ([ADR-0094](../adr/0094-more-than-one-model-gateway.md)).
- A key's own budget and models are saved with `PATCH /principals/{principalId}/model-access`, audited as `model_key.budget_changed`.
- The statuses behind the notes of **Members' budgets** are `OK`, `BUDGET_SPENT`, `BLOCKED` and `ERROR`.
- A trigger's payer, and the author's subscription where the company allows it: [ADR-0085](../adr/0085-claude-login-on-the-runner-host.md) and [triggers.md](triggers.md).
- A spent budget: LiteLLM refuses the key's next request and the run fails with `USAGE_LIMIT`.

### 5.3 The company key

The company's own key ([ADR-0065](../adr/0065-company-model-key.md)) pays for Tinfirm's own model requests, never for a run: today the tidying of agents' memories ([agent-memory.md](agent-memory.md#5-for-admins)). Its requests go to the gateway's chat completions route (the model port, for the bundled gateway) and are tagged `tinfirm-task:{purpose}` in LiteLLM's spend logs.

### 5.4 Key rotation

Tinfirm rotates every key `TINFIRM_MODEL_KEY_ROTATION_INTERVAL` after its creation: it creates the next key with the same team, budget, period, models and spend, and the key it replaces keeps serving the requests that already hold it for `TINFIRM_MODEL_KEY_ROTATION_GRACE`. Then LiteLLM blocks the old key, and a minute later Tinfirm moves its late spend to the new key and deletes it. Audited as `model_key.rotated` and `model_key.retired`. The endpoints that rotate at once are in the public guide's [Rotate secrets and keys](../../docs-site/content/operator-guide/model-gateway.md#rotate-secrets-and-keys).

### 5.5 Runner hosts

What the operator does is in the public guide's [Let runs reach the relay](../../docs-site/content/operator-guide/model-gateway.md#let-runs-reach-the-relay). Every runner host of protocol 2 has the always-on model proxy, which keeps the run's token out of the sandbox, and sends `X-Tinfirm-Runner-Token` to its server's origin only ([ADR-0109](../adr/0109-runner-version-is-the-release-version.md), CONVENTIONS §14.10). A host logged in at another origin than `TINFIRM_PUBLIC_URL` sends none, and the relay refuses its model requests with `401`: the relay requires the host's own token from every host, with no setting to turn that off (the security review of 2026-10-27, I8; `ModelRelayService`). No runner version is asked.

## 6. An external LiteLLM

How a company uses its own LiteLLM, and how to configure it, is in the public guide's [An external LiteLLM](../../docs-site/content/operator-guide/model-gateway.md#an-external-litellm). Beyond it:

- Tinfirm keeps the admin key as the company secret `MODEL_GATEWAY_ADMIN_KEY` (`LITELLM_ADMIN_KEY` for a key entered before ADR-0094), and manages only the team and the keys it creates (alias prefix `tinfirm-{companyId}-`).
- The admin URL is held to the outbound guard of the company's systems; a refused one is `400 INVALID_BASE_URL`. `TINFIRM_CONNECTION_INTERNAL_HOSTS` does not apply to it ([ADR-0066](../adr/0066-internal-hosts.md)).
- The settings to copy are those of `deploy/compose/litellm/config.yaml`. `budget_exceeded_status_code: 429` is needed because from v1.105.1 LiteLLM answers a spent budget with 422, which the server's relay does not take for a spent key.

## 7. Troubleshooting

The public guide's [Troubleshooting](../../docs-site/content/operator-guide/model-gateway.md#troubleshooting) has the symptoms and fixes in the operator's terms. The codes behind them:

| Code | Raised when | See |
|---|---|---|
| `502 GATEWAY_UNREACHABLE` | The gateway's test fails: LiteLLM is down or not ready, the admin key is wrong, or a company's own gateway's address is refused or unreachable; the reason names it | Sections 5.1 and 6 |
| `GATEWAY_NOT_CONFIGURED`, or `MODEL_ACCESS_UNAVAILABLE` with that reason | A conversation on a budget starts, or a trigger's editor saves, while the company's gateway has not passed its test, or changed since | Section 5.1 |
| `MODEL_REQUIRED` | The settings that pay with a budget name no model: the agent's readiness, the conversation's start, a turn, or a trigger's settings | Section 4.2 |
| `MODEL_NOT_ALLOWED` | The settings name a model the payer's key does not allow: refused settings, or a failed turn | Section 5.2 |
| `MODEL_NOT_SERVED` | The gateway does not serve the model a run asked for: one the settings name, or a small model a runtime asks for on its own | Section 4.2 |
| `ERROR`, a key's status | LiteLLM refused the key or did not answer, or the key is not made yet; the reconciliation tries again every 5 minutes, and a rotation gives it a new key | Sections 3 and 5.4 |
| `401` from the relay | A runner host logged in at another origin than `TINFIRM_PUBLIC_URL` sends no host token | Section 5.5 |
| `400` from the relay or the model port, "asks the model's provider to run the tool type `…` itself", "… its built-in tool `…`", "… to act itself (`…`)" | The request asked the provider for a tool it runs itself (web search or fetch, code execution, MCP, `mcp_servers`, `container`, `web_search_options`); always refused (ADR-0108). A runtime's own switch for it is off in every run: a refusal from a pinned runtime means it gained a provider-run tool the runner host does not turn off yet. Agents search the web through a search MCP server the company registers as a system | Section 2 |
| `408` from the relay | A request's body did not arrive whole within `TINFIRM_MODEL_RELAY_BODY_TIMEOUT` | Section 2 |

## 8. Keys for the company's services

How to create, use, rotate and revoke a service's key at the model port ([ADR-0069](../adr/0069-company-model-gateway.md)) is in the public guide's [The model port and the company's own services](../../docs-site/content/operator-guide/model-gateway.md#the-model-port-and-the-companys-own-services). For a service such as a reporting service, a script or an internal application, the key goes into that service's own secret, with an alias such as `service-reports` and the models it uses; in Kubernetes, list its pods in `litellm.modelApi.from` ([helm.md, section 2.7](helm.md#27-litellm)).
