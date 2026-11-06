# ADR-0069: The bundled LiteLLM is the company's model gateway: a model port for its services, the admin port for Tinfirm's server only

- Status: Accepted
- Date: 2026-10-16
- Decided by: the owner, 2026-10-16
- Sources: [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md); the security review of 2026-10-12, row 21 ([reviews/2026-10-12-platform-redesign.md](../reviews/2026-10-12-platform-redesign.md)); [operations/litellm.md](../operations/litellm.md); the [threat model](../threat-model.md)
- Amends: [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md) decision 1 (who reaches the bundled LiteLLM); its decision 2 (runner hosts reach only the server's relay) stands
- Changes: a new top-level directory `model-api/` (Maven project `tinfirm-model-api`, image `ghcr.io/flowtivo/tinfirm-model-api`), run beside LiteLLM in its pod (Helm) or as a service of its own (Compose); a shared library `libs/litellm` with the filter of LiteLLM's request fields, which the server's relay and the model port both use; the server's `TINFIRM_LITELLM_MODEL_URL`

## Context

- ADR-0059 made LiteLLM the mandatory model gateway, bundled by default. The security review then kept it inside the deployment, reached by Tinfirm's server alone, because LiteLLM serves every route on one port: its admin API (keys, teams, budgets, spend, the model list) beside its model API. It also takes fields of a request's body as its own (an upstream URL, credentials, metadata, a canned answer), so a caller who reaches it can send the company's provider key elsewhere or bill another key.
- The company's other services need models too, such as a script or an internal application. Routing them through Tinfirm's server would make the server a model proxy for services that have nothing to do with its principals, budgets or audit.
- The owner's view: the bundled LiteLLM is the company's gateway, deployed together with Tinfirm, not Tinfirm's own. The company's services should reach it as they would reach any company gateway, with keys of their own.

## Decision

**LiteLLM gets a second port, the model port, that serves its model API and nothing else to the company's services; its own port, with the admin API, stays reachable by Tinfirm's server alone. The server, too, sends its model traffic to the model port.**

1. **The model port** (`model-api`, port 4001) runs beside LiteLLM in its pod and passes on its model routes only, each by its exact path: chat completions, completions, responses, embeddings, messages and their token count, rerank (OpenAI's and Cohere's paths also without `/v1`), and the model list. Any other path is `404` and never reaches LiteLLM; a query string is dropped. It removes LiteLLM's own fields from every body with the server relay's filter (`libs/litellm`), passes the caller's key and the formats' headers, and streams the answer back. It holds no key.
2. **The company's services use LiteLLM keys of their own**, which an operator creates in LiteLLM with their budgets and models (a reporting service, a script, an internal application). LiteLLM checks the key and meters it; the spend is the service's, not a Tinfirm principal's. A key, even the master key, reaches only the model routes through this port.
3. **LiteLLM's own port** (4000) serves the admin API and the model API as before, reached only by Tinfirm's server (and by an operator, on the Compose host's loopback or through a port-forward, for the admin API: the chart and Compose keep LiteLLM's admin UI off). The chart's network policies and Compose's networks enforce it: LiteLLM's pod takes 4000 from the server's pods only, and 4001 from the server and the pods the operator allows (`litellm.modelApi.from`); in Compose, LiteLLM sits on a network of its own, `gateway`, which only the server, the model port and the PostgreSQL that holds LiteLLM's database join.
4. **The server splits its traffic the same way**: its model requests (the runner hosts' relay, its own requests on the company's key) go to `TINFIRM_LITELLM_MODEL_URL`, the model port when LiteLLM is bundled; its admin calls stay on `TINFIRM_LITELLM_ADMIN_URL`. Without a model URL, as with a company's own LiteLLM, both go to `TINFIRM_LITELLM_ADMIN_URL`.
5. **One filter of LiteLLM's fields**, in `libs/litellm`, compiled into both. The server's relay still forces LiteLLM to drop the parameters a model does not take (`drop_params`, as before); the model port passes a caller's `drop_params` as it came, since a service may want an unsupported parameter refused rather than dropped.
6. **Runner hosts do not use the model port.** They are outside the deployment; they keep reaching the server's relay with a one-run token (ADR-0059 decision 2), and no LiteLLM key leaves the server.

## Consequences

- One gateway for the whole company, as the owner wants: one place for provider keys, models, budgets and spend, whoever calls.
- The admin API stays unreachable but from the server, so a service's key or a compromised pod on the deployment's network cannot create keys, read spend or change models; and LiteLLM's body fields are stripped for every caller, so no caller can redirect a request with the company's provider key.
- LiteLLM is exposed more widely than before: every pod the operator allows can send model requests, with a key. The key is what stops a caller; an operator gives each service its own, with a budget.
- Tinfirm's own model traffic passes the same filter twice (in the relay and in the port) and makes one more local hop; the cost is negligible beside a model's answer.
- A company running its own LiteLLM gets nothing from the model port; it exposes its gateway as it decides.
- One more image to build, release and upgrade with LiteLLM.

## Alternatives considered

- **The server as the services' model proxy** (an endpoint for company services on the server, with their own tokens): the server would carry and meter traffic that belongs to no principal of its own, and a service would depend on Tinfirm's server to reach a model. Rejected by the owner: LiteLLM is the gateway.
- **LiteLLM's own port opened to the services**: it would expose the admin API and LiteLLM's body fields to every caller with a key, which the security review closed (row 21).
- **An off-the-shelf proxy** (nginx, Envoy) with a path allow-list in front of LiteLLM: it routes by path but does not read JSON bodies, so LiteLLM's fields would pass. A filter needs the body; the server's filter exists and is tested.
- **A port of LiteLLM itself for the model API**: LiteLLM has no setting that serves the model routes alone on another port, or that ignores its body fields.
