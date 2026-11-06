# Run the model gateway

This page is for the operator. Every model call of an agent goes through the company's model gateway, [LiteLLM](https://docs.litellm.ai), with the key and the budget of whoever pays. Tinfirm ships a LiteLLM with its Compose stack and its Helm chart. Here you add the providers and models to it, secure it, connect the company's own services to it, and keep it running; or you point a company at a LiteLLM it already runs, or at a gateway whose team makes the keys.

## What LiteLLM does for Tinfirm

The company's provider keys and models live in LiteLLM. Every member and every service account has one LiteLLM key with a budget, which Tinfirm creates and keeps in its vault. A run paid by a budget sends its model requests to the Tinfirm server's **model relay**, which forwards them to LiteLLM with the payer's key; LiteLLM meters them and refuses them once the budget is spent. Runner hosts never reach LiteLLM and never hold a LiteLLM key.

The one exception to budgets is a member's own subscription (Claude, ChatGPT) on their personal runner host: it pays only for their own work, and LiteLLM does not meter it ([Install and run runner hosts](runner-hosts.md#subscriptions-on-a-personal-host)).

| Who | Does |
|---|---|
| You, the operator | Run LiteLLM, its database and the model port; keep its secrets; add the provider keys and models; upgrade and back it up |
| A company's Owners and Admins | Choose and test the gateway on **Model access**, and set the budgets and models: [Model access and budgets](../admin-guide/model-access.md) |
| Tinfirm | Creates a LiteLLM team per company and a key per member and service account, relays the runs' model requests, reads the spend, and repairs drift every 5 minutes |

Tinfirm never adds, changes or deletes a model or a provider key: that is yours. It touches only the keys whose alias starts with `tinfirm-<company id>-`, so never start the alias of a key you make yourself with `tinfirm-`.

## The bundled gateway

| | Docker Compose | Helm chart |
|---|---|---|
| LiteLLM | Service `litellm`, LiteLLM's non-root image `ghcr.io/berriai/litellm-non_root`, pinned by digest | Deployment `<release>-litellm`, the same image (`litellm.image`), one replica |
| Its address, every route, the admin API included | `http://litellm:4000` inside the stack; `http://127.0.0.1:4000` on the host (`TINFIRM_LITELLM_HOST_PORT`) | `http://<release>-litellm.<namespace>.svc:4000`, a ClusterIP Service |
| Who reaches it | The server and the model port, and you on the host's loopback | The server pods only: the NetworkPolicy admits nothing else |
| The model port | Service `model-api`: `http://model-api:4001`; `http://127.0.0.1:4001` on the host | Container `model-api` beside LiteLLM, the Service's port `model`: `…svc:4001` |
| Who reaches the model port | Every service of the stack | The server pods, and the peers of `litellm.modelApi.from` |
| Its database | `litellm` on the stack's PostgreSQL, created by the one-shot service `litellm-db` on every `up` | `litellm` on your external PostgreSQL: [Prepare PostgreSQL](database.md) |
| Secrets | `LITELLM_MASTER_KEY`, `LITELLM_SALT_KEY`, `LITELLM_DB_PASSWORD` in `deploy/compose/.env` | The Secret `tinfirm-litellm`: `master-key`, `salt-key`, `database-password` |
| Settings | `deploy/compose/litellm/config.yaml` | The ConfigMap `<release>-litellm` |

The server gets four variables: `TINFIRM_MODEL_GATEWAY_VENDOR` (`LITELLM`), `TINFIRM_MODEL_GATEWAY_ADMIN_URL` (LiteLLM's own port, for its admin API), `TINFIRM_MODEL_GATEWAY_MODEL_URL` (the model port, for the requests it relays and its own) and `TINFIRM_MODEL_GATEWAY_ADMIN_KEY`. The deployment bundles a gateway only when the admin URL and the master key are both set; otherwise **Model access** says **This deployment bundles none.** and **Bundled** cannot be chosen. Without a model URL, the model requests go to the admin URL.

Check that LiteLLM is up:

```sh
curl -s http://127.0.0.1:4000/health/readiness    # {"status":"healthy","db":"connected"}
```

Its first start migrates an empty database, which can take several minutes.

## How it is secured

- **Prompts and answers are never stored.** The settings file sets `store_prompts_in_spend_logs: false`, `disable_error_logs: true` and `turn_off_message_logging: true`. LiteLLM's spend logs keep the key, team, model, tokens, cost, time and status of each request, never its body or the answer. A setting in the file wins over the same setting stored through the admin API, so the master key cannot turn these back on: change them only in the file.
- **Only the server reaches LiteLLM's own port.** Never publish it, and point no reverse proxy, tunnel or firewall rule at it. None of LiteLLM's management routes is in reach of a runner host or a sandbox.
- **The model relay carries the runs' traffic.** Each run gets the relay's address on your public URL and a token for that run alone. The relay accepts the token only while the run is active on the host it was sent to, and only with that host's own token. It strips LiteLLM's own fields from each request, refuses every request that asks the model's provider to run a tool itself (search or fetch the web, run code, call an MCP server), with no setting that allows one, passes only the formats' headers and the beta values the runtimes need, allows only the models the payer's key allows, has LiteLLM drop a parameter the model's provider does not take instead of failing the run, and tags each request with its run, conversation and agent in LiteLLM's spend logs.
- **No LiteLLM key leaves the server.** The keys stay in Tinfirm's vault. A runner host holds only the run's token, and keeps it out of the sandbox behind its [model proxy](runner-hosts.md#the-model-proxy).
- **The admin UI is off.** The admin API stays, reachable only inside the deployment.
- **The master key** is the gateway's root credential: only the server and you have it. **The salt key** encrypts the provider keys in LiteLLM's database: keep it with Tinfirm's vault keys, and never change it once keys are stored.

## Add providers and models

LiteLLM serves no model until you add one, with the master key, through its admin API: on the Compose host at `http://127.0.0.1:4000`, in Kubernetes after `kubectl -n <namespace> port-forward deploy/<release>-litellm 4000:4000`. Each model is one entry: `model_name`, the name runtimes ask for and people pick in their settings, and `litellm_params`, where LiteLLM sends it and with which key. Put the request in a file, so that the provider's key stays out of your shell history, and the master key in a header file, so that it stays out of your history and the process list:

```json title="model.json"
{
  "model_name": "<the model name agents ask for>",
  "litellm_params": {
    "model": "anthropic/<the provider's model id>",
    "api_key": "sk-ant-…"
  }
}
```

```sh
# The master key, typed without echo, into a header file only you can read.
umask 077
printf 'LiteLLM master key: '; stty -echo; read -r key; stty echo; echo
printf 'Authorization: Bearer %s\n' "$key" > litellm-auth; unset key

curl -sS http://127.0.0.1:4000/model/new \
  -H @litellm-auth \
  -H "Content-Type: application/json" \
  -d @model.json
rm model.json

# What the gateway serves now
curl -sS http://127.0.0.1:4000/v1/models -H @litellm-auth
```

`-H @file` needs curl 7.55 or later. Delete `litellm-auth` when you are done.

LiteLLM keeps each model in its database, the provider key encrypted with the salt key. Tinfirm reads the gateway's models at most once a minute: a new model shows in people's settings within a minute. To remove one, find its id (`model_info.id`) in `GET /model/info`, and send `{"id": "<id>"}` to `POST /model/delete`.

With Helm, `litellm.adminUi: true` turns LiteLLM's admin UI on at `/ui`, reached like the admin API, through `kubectl port-forward`; the Service is never exposed. Compose keeps it off, and `/ui` says **Admin UI is Disabled**.

### Providers

The provider is the prefix of `litellm_params.model`:

| Provider | `litellm_params` | The key, and how to limit it |
|---|---|---|
| Anthropic | `"model": "anthropic/<model id>"`, `"api_key"` | An API key (`sk-ant-api…`) from a workspace with a spend limit |
| OpenAI | `"model": "openai/<model>"`, `"api_key"` | A project key (`sk-proj-…`) in a project with a budget and allowed models |
| Amazon Bedrock | `"model": "bedrock/<model id or inference profile>"`, `"aws_region_name"`, and `"api_key"` (a Bedrock API key) or `"aws_access_key_id"` and `"aws_secret_access_key"` | An IAM user allowed only `bedrock:InvokeModel` and `bedrock:InvokeModelWithResponseStream` on the models you serve. Temporary credentials expire while LiteLLM keeps them. |
| Google Gemini | `"model": "gemini/<model>"`, `"api_key"` | A key from Google AI Studio, in a project with quotas |
| OpenRouter | `"model": "openrouter/<provider>/<model>"`, `"api_key"` | A key (`sk-or-…`) with a credit limit |
| Mistral, Groq, xAI, DeepSeek | `"model": "mistral/<model>"` (`groq/`, `xai/`, `deepseek/`), `"api_key"` | A key of the provider's console |

LiteLLM must reach the provider's API, for Bedrock `bedrock-runtime.<region>.amazonaws.com`. In Kubernetes, `networkPolicy.litellm.egress.providers` allows https anywhere by default; narrow it if your providers sit behind a proxy or a private endpoint.

Claude Code, pi and OpenCode send their requests in Anthropic's Messages format, Codex in OpenAI's Responses format. LiteLLM translates between them and each provider's API, so a model of any provider can serve every runtime. How well a runtime works with a model outside its own family depends on the model and the translation: try it in a conversation before you give it to a team.

### The model names agents ask for

Serve every model name a run asks for:

- **The model of the settings.** A turn paid by a budget asks for the model of the person's settings of the agent, or of the trigger's, which they pick from what the gateway serves. Settings that name no model do not run on a budget: the conversation is refused with **Choose a model in your settings first**, and a trigger's settings are not saved without a model.
- **Claude Code's small, fast model.** Claude Code also asks for a small model for background work, by the name of its own default. Serve that name too.
- **OpenCode's title model.** OpenCode titles a session with a small model of the provider, for example `claude-haiku-4-5` or `gpt-5.4-nano`.
- **Limited keys.** When an admin limits a key's models, those names must be among them: the relay refuses a model the payer's key does not allow.

When the gateway does not serve a model a run asked for, the turn fails with **The gateway does not serve the model**, and the conversation names the model.

### Prices and provider keys

LiteLLM prices each request from its model price list, which it fetches from GitHub (`raw.githubusercontent.com`), or keeps from its image with `LITELLM_LOCAL_MODEL_COST_MAP=True`. Budgets and **Spend** count that cost. For a model the list does not know, add `input_cost_per_token` and `output_cost_per_token` to its `litellm_params`.

To rotate a provider key without a gap, add the model again with the new key and the same `model_name` (LiteLLM spreads requests over both), check that requests succeed, then delete the old entry and revoke the old key at the provider.

## Prompt caching

A run reports four token counts, shown on the run as `in · cache read · cache write · out`: input neither read from nor written to the provider's prompt cache, cache reads (about a tenth of the input price at Anthropic), cache writes (1.25 times the input price with a 5-minute lifetime, 2 times with a 1-hour one) and output.

A later turn of a conversation resumes the agent's session and sends the earlier history again. If the cache entry is still alive, the history is read cheaply; if not, it is written again. Claude Code keeps its cache for 5 minutes on an API key, which is how a budget reaches the gateway, and for an hour on a Claude subscription. Tinfirm does not set the lifetime. To let a follow-up within the hour (an answer to a question, an event after a push) find the history, set it on each runner host:

```sh
tinfirm-runner-host run --sandbox-env CLAUDE_CODE_PROMPT_CACHE_TTL=1h
```

Every cache write then costs 2 times instead of 1.25 times, whether a follow-up comes or not. It pays off for conversations that wait on people; single-turn conversations only pay more. It changes nothing on a subscription, and the other runtimes ignore it.

## Let runs reach the relay

Runner hosts reach the relay at your public URL, `TINFIRM_PUBLIC_URL`, under `/api/runner/v1/model/`:

- `TINFIRM_PUBLIC_URL` must be an `https` URL: the runner host's model proxy reaches the relay over https only.
- The reverse proxy or ingress in front of Tinfirm must pass requests up to 64 MiB with their `Authorization` and `X-Tinfirm-Runner-Token` headers, and stream answers as they arrive, with up to 10 minutes between two parts. ingress-nginx's defaults (1 MiB, 60 seconds) answer `413` or `504`: [Install on Kubernetes with Helm](helm.md) shows the annotations.
- Every runner host that speaks runner protocol 2 sends its own token with each model request, but only to the origin it logged in at: log hosts in with the public URL. The relay refuses a run's token without its host's token, from any host ([Runner host reference](../reference/runner-host.md#compatibility)).
- The relay holds at most 256 MiB of request bodies at once on a replica (`TINFIRM_MODEL_RELAY_MAX_INFLIGHT_BYTES`); beyond it, requests get `429`.

## An external LiteLLM

A company that already runs LiteLLM can use it instead of the bundled one.

1. With Helm, `litellm.enabled: false` deploys no LiteLLM. The network policy lets the server reach a gateway on the internet over https; for one inside your network, add a rule in `networkPolicy.egress.extra`. Compose always runs the bundled gateway, and a company can still choose its own.
2. On **Model access**, an Owner or Admin chooses **Your own LiteLLM** and enters its **Admin URL** and a **Master or admin key**, which Tinfirm keeps in the company's vault and never shows again.
3. The admin URL is held to the same outbound rules as the company's systems: `https` unless you set `TINFIRM_CONNECTION_ALLOW_HTTP`, and no loopback, link-local or private address unless you set `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES`. `TINFIRM_CONNECTION_INTERNAL_HOSTS` does not apply to it.

The server reaches that one URL for the admin API and the relayed model requests; runner hosts never do. Keep the gateway off the internet, with TLS between it and the server unless they share a private network. Tinfirm cannot enforce settings on a gateway it does not run, so configure it like the bundled one: `store_prompts_in_spend_logs: false`, `disable_error_logs: true` and `turn_off_message_logging: true`; `store_model_in_db: true`; `budget_exceeded_status_code: 429` under `litellm_settings` (otherwise LiteLLM answers a spent budget with `422`, which the relay does not read as a spent budget); a salt key; and the admin UI off, or on an internal address only.

## A gateway whose team makes the keys

A company can keep its gateway's admin API from Tinfirm: it chooses **Keys from your gateway** on **Model access** and enters only the gateway's model URL. The gateway may be LiteLLM, Bifrost, Kong or any gateway that serves OpenAI's and Anthropic's model routes and takes a key per caller as a bearer token (`Authorization: Bearer`). Its team makes a key per member and service account, with its budget and models; an admin, or a member where the company allows it, enters it in Tinfirm, which checks it with `GET /v1/models` and keeps it in the company's vault.

- The model URL is held to the same outbound rules as an external LiteLLM's admin URL, above.
- The gateway must answer a spent budget in words Tinfirm reads: LiteLLM with `429` and `budget_exceeded` (`budget_exceeded_status_code: 429`); another gateway with `402`, or with a `429` whose message speaks of a budget, a quota, a spend limit, credit or billing and not of a rate limit (a provider's rate limit passes on as it came, so that the runtime waits and retries). The relay then answers the runner `429` with `type` `budget_exceeded`, whatever the gateway said.
- Tinfirm sends each turn's tags in `x-tinfirm-tags` to a gateway other than LiteLLM, for the gateway's own spend logs. Its spend in Tinfirm is what the relay counted.
- Tinfirm never rotates or revokes such a key. When a principal is suspended or leaves it stops using the key and records `model_key.revoke_requested`: revoke it at the gateway.

The server's own variables for the bundled gateway are `TINFIRM_MODEL_GATEWAY_VENDOR`, `TINFIRM_MODEL_GATEWAY_ADMIN_URL`, `TINFIRM_MODEL_GATEWAY_ADMIN_KEY` and `TINFIRM_MODEL_GATEWAY_MODEL_URL` ([Server configuration](../reference/configuration.md)); a provided gateway is a company's setting and needs none.

## The model port and the company's own services

The bundled gateway is the company's, not only Tinfirm's. Its **model port** serves LiteLLM's model routes alone, each by its exact path: chat completions, completions, responses, embeddings, messages and their token count, rerank, and the model list. Every other path is `404` without reaching LiteLLM, LiteLLM's own fields are removed from every body, and it holds no key: each caller sends its own LiteLLM key.

The model port also refuses (`400`) every request that asks the model's provider to run a tool itself: search or fetch the web, run code, call an MCP server. No setting lets one through: the former `MODEL_API_PROVIDER_TOOLS` (Helm `litellm.modelApi.providerTools`, Compose `TINFIRM_MODEL_API_PROVIDER_TOOLS`) is gone, and the chart's schema no longer takes `litellm.modelApi.providerTools`: remove it from your values. Agents search the web through a search MCP server the company registers ([Web search for agents](../admin-guide/mcp-servers.md#web-search-for-agents)), which the Tinfirm server calls with its audit and the agents' tool settings. Leave models that search the web by themselves, whatever the request says (Perplexity's Sonar models, OpenAI's `*-search-preview` models), out of the gateway's model list.

Give each service, such as a script or an internal application that embeds documents, a key of its own with its budget and models. Create it at LiteLLM's own port with the master key, from the header file of [Add providers and models](#add-providers-and-models):

```sh
curl -sS http://127.0.0.1:4000/key/generate \
  -H @litellm-auth \
  -H "Content-Type: application/json" \
  -d '{"key_alias": "service-<name>", "models": ["<embedding model>"],
       "max_budget": 20, "budget_duration": "30d"}'
```

The model must be one the gateway serves; an embedding model is added like any other. Put the answer's `key` into the service's own secret, and let it call the model port in OpenAI's or Anthropic's format. Unlike the relay, the model port passes `drop_params` as the caller sent it. Its base URL is `http://model-api:4001/v1` (Compose) or `http://<release>-litellm.<namespace>.svc:4001/v1` (Helm). In Kubernetes, admit the service's pods with `litellm.modelApi.from`. Its spend is in LiteLLM under that key, not in Tinfirm. A spent budget answers `429` until `budget_duration` resets it. To rotate a service's key, create a new one, put it into the service, then delete the old one (`POST /key/delete` with `{"keys": ["sk-…"]}`), since `/key/regenerate` is a feature of LiteLLM Enterprise; to revoke it, `/key/block` or `/key/delete`.

## LiteLLM's database

LiteLLM keeps its keys, teams, budgets, spend logs, model list and the encrypted provider keys in a database of its own, owned by a role that owns nothing else: neither Tinfirm role connects to it. It creates and migrates its tables itself at every start, and does not start when a migration fails. [Prepare PostgreSQL](database.md) covers the database and its connection for Helm.

Back it up with Tinfirm's database, and restore both from the same point in time: Tinfirm's vault holds the keys that must match LiteLLM's. A restored LiteLLM database cannot decrypt its provider keys without the salt key, which is not in it. With Compose, back it up beside Tinfirm's dump:

```sh
docker exec tinfirm-postgres-1 pg_dump -U postgres -Fc litellm > litellm-$(date -u +%Y%m%dT%H%M%SZ).dump
```

## Rotate secrets and keys

| What | How |
|---|---|
| Master key | Compose: a new `LITELLM_MASTER_KEY` in `.env`, then `docker compose … up -d`, which recreates LiteLLM and the server. Helm: update `master-key` in the Secret, restart both Deployments. The members' keys stay valid. |
| Salt key | **Never.** If it leaked with the database or a backup, treat the provider keys as leaked: rotate them at the providers and add them again. |
| Database password | Compose: a new `LITELLM_DB_PASSWORD`, then `up -d`. Helm: `ALTER ROLE litellm PASSWORD …`, update the Secret, restart `<release>-litellm`. |
| Members' and service accounts' keys | Tinfirm rotates each one 30 days after it was made (`TINFIRM_MODEL_KEY_ROTATION_INTERVAL`, at least a day). The key it replaces serves the requests that hold it for 10 minutes more (`TINFIRM_MODEL_KEY_ROTATION_GRACE`, at most an hour), then is blocked and deleted. |

On suspicion of a leak, an Owner or Admin rotates at once, without a grace, through the API (the app has no button for it yet): `POST /api/companies/{companyId}/principals/{principalId}/model-access/rotate-key` for one key, `…/model-access/company-key/rotate-key` for the company key, and `…/model-settings/rotate-keys` for every key of the company.

## What to watch

- **LiteLLM's readiness**, `/health/readiness`, and its JSON logs on stdout. The chart runs one replica: without a shared Redis, several would count limits and recent spend each on their own. Give it its memory (2 GiB by default).
- **The gateway's status** on each company's **Model access**: **Test failed** means nothing is paid by a budget until a test passes.
- **Keys in error** on **Members' budgets**: Tinfirm tries again every 5 minutes, and the row says LiteLLM's reason.
- **LiteLLM's database**: its spend logs keep a row per request. Plan its disk, and its backups, with Tinfirm's.
- **The providers' own limits**: a budget in Tinfirm caps a person's spend; a spend limit at the provider caps the company's.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `docker compose up` fails: `required variable LITELLM_MASTER_KEY is missing a value` | A `.env` without LiteLLM's settings | Add `LITELLM_MASTER_KEY`, `LITELLM_SALT_KEY` and `LITELLM_DB_PASSWORD`. Never generate a new salt key for a `.env` that has one. |
| With Compose, `litellm` does not start, and `litellm-db` exited with an error | `litellm-db` cannot sign in to PostgreSQL as `postgres`: `POSTGRES_PASSWORD` in `.env` is not the superuser password the database volume was created with | Read `docker compose logs litellm-db`, and set the password the volume was created with |
| `litellm` restarts with a database error | A wrong password or database settings; with Helm, a missing database, role or Secret; or a failed migration | Compose: `up -d` again. Helm: [Prepare PostgreSQL](database.md). |
| **Model access** says **This deployment bundles none.** | The server has no `TINFIRM_MODEL_GATEWAY_ADMIN_URL` or `TINFIRM_MODEL_GATEWAY_ADMIN_KEY`; with Helm, `litellm.enabled: false`, or the Secret did not exist when the server started | Create the Secret (Compose: the `.env` value), then restart the server |
| The test fails | LiteLLM is down or not ready, the admin key is wrong, or a company's own gateway's address is refused or unreachable | The reason names it. Check `/health/readiness`, the key, and the outbound rules above |
| The list of models is empty | No model is added | [Add one](#add-providers-and-models) |
| A turn fails: **The gateway does not serve the model** | A model the settings name, or one a runtime asks for on its own, is not served | Add the model, or pick one it serves in the settings |
| Model requests fail with `413`, or `504` after 60 seconds | The ingress in front of Tinfirm has its defaults | Raise them to 64 MiB and 600 seconds |
| A runner host's model requests fail with `401`, and its run log says it sends no host token | The host logged in at another origin than `TINFIRM_PUBLIC_URL` | Log it in again with the public URL |
| Turns paid by a budget wait while a runner host is online | The host speaks runner protocol 1 | Start it with protocol 2: remove `--protocol-version 1` |
