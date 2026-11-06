# ADR-0042: A provider's API key as a company model credential, and the model of an agent

- Status: Superseded by [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md) on 2026-10-12 (was Proposed)
- Date: 2026-10-06
- Sources: the owner's request of 2026-10-06 (direct API keys of providers such as Anthropic, OpenAI and Bedrock); PLATFORM.md §4.1; CONVENTIONS §14.6, §14.8; [ADR-0027](0027-company-model-credentials.md), [ADR-0041](0041-pi-and-opencode-runtimes.md); [threat model](../threat-model.md) A16, 7.3, 7.13, R23
- Changes: ADR-0027, where a model credential is an endpoint of the company's gateway with one format per runtime: it is a provider's key, with a gateway in front optional for Anthropic and OpenAI, and a format serves several runtimes. PLATFORM.md §4.1, where an agent has no model.

## Context

- A company model credential (ADR-0027) is a base URL, a format (`ANTHROPIC` for Claude Code, `OPENAI` for Codex) and a key in the vault. It was built for a LiteLLM gateway: the base URL is required, and Claude Code gets the key as a bearer token (`ANTHROPIC_AUTH_TOKEN`), as LiteLLM reads it.
- A company without a gateway wants to give its agents the provider's key directly: an Anthropic or OpenAI API key, an Amazon Bedrock key, and for pi and OpenCode (ADR-0041) the keys of Gemini, OpenRouter, Mistral, Groq, xAI or DeepSeek. With a direct key Claude Code must send the key as `x-api-key` (`ANTHROPIC_API_KEY`), and on Bedrock it needs `CLAUDE_CODE_USE_BEDROCK`, the AWS region and AWS's own credentials.
- Amazon Bedrock takes either an Amazon Bedrock API key (a bearer token, `AWS_BEARER_TOKEN_BEDROCK`) or an IAM access key (an access key ID and its secret access key). Only the secret part is a secret; the region and the key ID are configuration.
- pi and OpenCode reach several providers, so the runtime no longer fixes the provider, and the provider no longer fixes the runtime. Each picks a default model per provider, which on OpenRouter (hundreds of models) or Bedrock (regional inference profiles) is often not the one a company wants. The agent has had no model of its own since the restart: `run.assign.agent.model` was always null.

## Decision

**A model credential is a provider's key: the format names the provider and the runtimes it serves; a base URL makes it a gateway's key, for Anthropic and OpenAI only; Bedrock adds a region and an optional access key ID. An agent gets an optional model.**

**Formats and runtimes.**

| Format | Provider | Runtimes | Base URL | Region, access key ID |
|---|---|---|---|---|
| `ANTHROPIC` | Anthropic Messages API | Claude Code, pi, OpenCode | Optional: a gateway | No |
| `OPENAI` | OpenAI API | Codex, pi, OpenCode | Optional: a gateway | No |
| `BEDROCK` | Amazon Bedrock | Claude Code, pi, OpenCode | No | Region required, key ID optional |
| `GOOGLE` | Gemini API | pi, OpenCode | No | No |
| `OPENROUTER`, `MISTRAL`, `GROQ`, `XAI`, `DEEPSEEK` | The provider's API | pi, OpenCode | No | No |

The format still cannot change. The API's `ModelCredential.runtime` becomes `runtimes` (an array, in the order of `AgentRuntime`); `baseUrl` becomes optional and nullable; `region` (an AWS region, `^[a-z]{2}(-[a-z]+)+-[0-9]+$`) and `accessKeyId` (`^[A-Z0-9]{16,128}$`) are new. A value the format does not take is `400 VALIDATION_FAILED`, as is a Bedrock credential without a region. On an update an empty `baseUrl` turns a gateway into the provider's own API and an empty `accessKeyId` turns an access key into a Bedrock API key; the region can change but not go. `CREDENTIAL_RUNTIME_MISMATCH` applies when the format does not serve the agent's runtime. The migration `V010__providers_and_runtimes.sql` widens the format check, makes `base_url` nullable and checks each column against the format. Audit details gain `region` and `accessKeyId`, never the key.

**The protocol.** `run.assign.modelAccess` carries `format` (now `anthropic`, `openai`, `bedrock`, `google`, `openrouter`, `mistral`, `groq`, `xai`, `deepseek`), `baseUrl` only for a gateway, `region` and `accessKeyId` only for Bedrock, and `apiKey`; the schemas enforce the pairing. The runner host checks the same rules again and refuses an assignment that breaks them (`INVALID_ASSIGNMENT`), and redacts the key as before.

**The sandbox.** The host gives each runtime the key as its CLI reads it (runner/README.md, "Company model credentials"):
- Claude Code: `ANTHROPIC_API_KEY` for Anthropic's API; for a gateway `ANTHROPIC_BASE_URL` and `ANTHROPIC_AUTH_TOKEN`, as before; for Bedrock `CLAUDE_CODE_USE_BEDROCK=1`, `AWS_REGION` and `AWS_DEFAULT_REGION`, and `AWS_BEARER_TOKEN_BEDROCK` or `AWS_ACCESS_KEY_ID` with `AWS_SECRET_ACCESS_KEY`. A gateway and Bedrock also get `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`.
- Codex: `OPENAI_API_KEY` with the API-key login; for a gateway also `OPENAI_BASE_URL` and `openai_base_url` in `CODEX_CONFIG`, as before.
- pi and OpenCode: the provider's own variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, the AWS variables, `GEMINI_API_KEY` and for OpenCode `GOOGLE_GENERATIVE_AI_API_KEY`, `OPENROUTER_API_KEY`, `MISTRAL_API_KEY`, `GROQ_API_KEY`, `XAI_API_KEY`, `DEEPSEEK_API_KEY`); the provider, model and gateway as ADR-0041 describes. Both use the provider IDs `anthropic`, `openai`, `amazon-bedrock`, `google`, `openrouter`, `mistral`, `groq`, `xai`, `deepseek`.

`--sandbox-env` may not set any of these variables.

**The agent's model.** `agent.model` (nullable, at most 200 characters of `^[A-Za-z0-9][A-Za-z0-9._:/@+\[\]-]*$`: model names, Bedrock IDs and ARNs, provider prefixes, Claude Code's `[1m]`) is in the Agent API (create and update; an empty value clears it), in the audit details, and in `run.assign.agent.model`. The runtime gets it unchanged: `ANTHROPIC_MODEL` for Claude Code, `model` in `CODEX_CONFIG` for Codex. For pi and OpenCode it is a model of the credential's provider, with the provider in front optional: `claude-sonnet-4-5` and `anthropic/claude-sonnet-4-5` are the same model; an OpenRouter model keeps its own slash (`anthropic/claude-sonnet-4.5` is `openrouter/anthropic/claude-sonnet-4.5`). Without a model, each runtime uses its default for the provider.

**Health.** The detail of an `INVALID` credential no longer says the gateway refused the key, since there may be none: `The key was refused in run … of work item ….` Besides a new secret, a changed base URL, region or access key ID returns an `INVALID` credential to `AVAILABLE`: a wrong key ID is refused like a wrong key.

**Web.** The model credential dialog picks a provider from a list and shows the runtimes it serves, a gateway URL only for Anthropic and OpenAI, the region and access key ID only for Bedrock, and what the secret must hold. The agent dialog offers the four runtimes and a model field, and a model credential whose provider does not serve the runtime stays visible but cannot be picked.

## Consequences

- A company can give an agent a provider's key without running a gateway, and pick the model. LiteLLM credentials keep working unchanged: a base URL is a gateway, as before.
- **A provider key reaches the sandbox environment.** A gateway's virtual key had its own budget (ADR-0027); a provider's key usually has none and opens the provider's whole account, so a leaked key costs more until it is revoked. The redaction, the leak stop and the INVALID status work as before. The threat model records it under 7.13 and R23. Companies that want a budget per agent keep a gateway in front, or use the provider's own per-key limits (Anthropic workspaces, OpenAI projects, AWS IAM policies limited to `bedrock:InvokeModel*`).
- An AWS access key in the sandbox is a long-lived IAM credential: it should belong to a user or role that may only invoke Bedrock models. Temporary credentials (a session token) are not supported, since they expire during a credential's life.
- Older runner hosts cannot parse a `run.assign` with a new format or without a base URL; as in ADR-0027, server and runner hosts are deployed together. Runs of the existing formats with a base URL are unchanged on the wire.
- Clients of the API that read `ModelCredential.runtime` must read `runtimes`.

## Alternatives considered

- **A separate "provider key" resource next to model credentials.** Rejected: agents, dispatch, pools, cooldowns, INVALID and the queue reasons are the same for a gateway's key and a provider's key; one resource with a format keeps one path.
- **A kind field (gateway or direct) next to the format.** Not chosen: a base URL already says it, and only Anthropic and OpenAI have gateways that speak their API.
- **Bedrock's credentials as one JSON secret** (key ID and secret together). Rejected: the key ID is configuration, which members should see and change without the vault; only the secret access key is a secret.
- **The model on the model credential instead of the agent.** Not chosen: agents with the same key often want different models (a cheap one for triage, a strong one for code), and the model belongs to the specialist.
- **Vertex AI and Azure OpenAI.** Planned: both need more than a key (a service account file; a resource name and deployments), which the sandbox cannot get through an environment variable alone.
