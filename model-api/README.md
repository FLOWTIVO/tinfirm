# Tinfirm model port

The bundled LiteLLM's model API and nothing else, for the company's services: [ADR-0069](../docs/adr/0069-company-model-gateway.md). LiteLLM serves every route on one port, its admin API (keys, teams, budgets, spend, models) beside its model API, and takes some fields of a request's body as its own: where the request goes and with which credentials, what it costs and logs, a canned answer. This port runs beside LiteLLM in its pod and passes on:

- **Its model routes only**, each by its exact path: `POST` `/v1/chat/completions`, `/v1/completions`, `/v1/responses`, `/v1/embeddings`, `/v1/messages`, `/v1/messages/count_tokens`, `/v1/rerank` (each of OpenAI's and Cohere's also without `/v1`), and `GET /v1/models` and `/models`. Any other path is `404` and never reaches LiteLLM; a query is dropped.
- **Bodies without LiteLLM's fields**: the shared filter of the server's model relay (`../libs/litellm`, `LiteLlmRequests`). `drop_params` passes as it came. `mcp_servers` and `container` are removed, and a request that names a tool the model's provider would run itself (web search or fetch, code execution, MCP) is `400`, naming its type, unless `MODEL_API_PROVIDER_TOOLS` allows it ([litellm.md](../docs/operations/litellm.md#2-how-it-is-secured)). The bodies held at once stay within `MODEL_API_MAX_INFLIGHT_BYTES` (`../libs/net`, `InflightBytes`, as the relay's).
- **The caller's key and the formats' headers**: `Authorization`, `x-api-key`, `api-key`, `anthropic-version`, `anthropic-beta`, `openai-beta`, `Accept`, `User-Agent` and `x-litellm-tags`. LiteLLM checks the key: each service has a LiteLLM key of its own, with its budget and models.

The answer streams back part by part; of its headers, the type, the request ids and the rate limits. Tinfirm's server sends its model requests here too (`TINFIRM_MODEL_GATEWAY_MODEL_URL`); its admin API calls go to LiteLLM's own port, which only the server reaches.

Java 25 and Spring Boot 4.1, as the server.

## Variables

| Variable | Meaning | Default |
|---|---|---|
| `MODEL_API_UPSTREAM` | LiteLLM's own port, without a path | `http://127.0.0.1:4000` |
| `MODEL_API_MAX_REQUEST_BYTES` | The largest request body passed on; a larger one is `413` | `67108864` (64 MiB) |
| `MODEL_API_MAX_INFLIGHT_BYTES` | The most bytes of request bodies held at once (each body twice while it is read and written anew, once while its answer streams), counted as a body's bytes arrive, at least twice the largest body: LiteLLM checks a key only once its body is read, so a request that would pass it is `429` with `Retry-After`, and a slow upload holds only what it sent | `134217728` (128 MiB) |
| `MODEL_API_IDLE_TIMEOUT` | The longest wait for LiteLLM's answer to start, and between two of its parts | `PT10M` |
| `MODEL_API_PROVIDER_TOOLS` | Which tools the model's provider may run itself for a request: `OFF`, none; `WEB_SEARCH`, the providers' web search too, and nothing else. The server's relay applies each company's own choice before the port: the stricter holds | `OFF` |
| `MODEL_API_PORT`, `MODEL_API_MANAGEMENT_PORT` | The model routes; the health probes (`/actuator/health`) | `4001`, `4002` |

## Build and test

```sh
cd model-api
./mvnw -B -ntp verify
```

The build compiles the sources of the shared libraries `../libs/litellm` and `../libs/net` and runs their tests too. The tests run the port against a fake LiteLLM on localhost.

The image is built from the repository root: `docker build -f model-api/Dockerfile .`.
