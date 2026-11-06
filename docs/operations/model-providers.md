# Model providers

**Model credentials**, in the design of 2026-10-05, were providers' API keys stored in Tinfirm as secrets and handed to runs ([ADR-0027](../adr/0027-company-model-credentials.md), [ADR-0042](../adr/0042-provider-api-keys.md)). They are gone: there is no Model credentials page and no `model-credentials` endpoint, and the web app's old `credentials` address opens **Model access**.

Today the provider keys and models live in LiteLLM, the company's model gateway ([ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md)). The operator adds them through LiteLLM's admin API, and Tinfirm adds none. A run gets no provider key: the server sends it only its model relay's address and a one-run token, in Anthropic's format, or OpenAI's for Codex, and relays its model requests to LiteLLM with the paying principal's LiteLLM key.

- To add a provider or a model, with each provider's settings: [litellm.md, section 4.2](litellm.md#42-providers-and-models).
- Budgets, the gateway and the company key on **Model access**: [litellm.md, section 5](litellm.md#5-model-access-in-tinfirm).
- A member's own subscription on their personal runner host: [runner/README.md](../../runner/README.md).

The page exists so that links from the records resolve.
