# Architecture

This page shows how the parts of a Tinfirm installation fit together: what you run, what connects to what, and where the company's data and secrets live. Read it before you plan an installation, then size it with [Requirements](requirements.md).

## The parts at a glance

<figure class="tf-diagram">
--8<-- "architecture.svg"
<figcaption>Each arrow points from the side that opens the connection. Runner hosts need no inbound port: they connect out to the server, through the same host name as everyone else.</figcaption>
</figure>

Tinfirm has two halves. The **deployment** is the server, its database and its model gateway: it holds the company's data, its credentials and its decisions, and it runs no agent. The **runner hosts** are machines that run agents' turns, each in a sandbox of its own. Each keeps its own token, with which it takes turns from the server, and a personal host also keeps its owner's subscription logins; of the company's systems it holds only the git token of a running turn. A host also keeps what later turns reuse: a mirror of each repository, each conversation's session home and the skills it read, deleted after 30 days unused (skills after 14), and the workspaces of the last 5 failed turns. Protect its disk as you protect credentials and the repositories ([Where data and secrets live](#where-data-and-secrets-live)).

| Part | What it does | Runs as | How many |
|---|---|---|---|
| **Server** | Serves the web app, the REST API, the MCP endpoint for people's AI clients, the add-ins, and the endpoints that Slack, Teams and webhooks post to. Its worker dispatches runs to runner hosts, polls trackers, fires schedules and writes results back. It executes every tool call on a company system itself, with the conversation's account. | The image `ghcr.io/flowtivo/tinfirm-server`, a Java application. Port 8080 for the app, 8081 for health and metrics. | One replica. High availability is planned (Enterprise). |
| **PostgreSQL 18** | The only database. It holds everything the server knows, the files of conversations and the company's encrypted secrets included, and the queue of work: there is no message broker. LiteLLM keeps its own database on the same server. | Your PostgreSQL: a managed service, an operator in the cluster, or the container of the Compose stack | One primary, with backups |
| **LiteLLM** | The model gateway. Every model call an agent makes passes through it, with the key and budget of whoever pays. It holds the company's provider keys. | LiteLLM's non-root image `ghcr.io/berriai/litellm-non_root`, pinned by the chart and the Compose file. Port 4000, reached by the server only. | One replica |
| **Model port** | Serves LiteLLM's model routes alone, without its admin API. The server sends its model requests through it, and the company's own services may call it with LiteLLM keys of their own. | The image `ghcr.io/flowtivo/tinfirm-model-api`, beside LiteLLM. Port 4001. | One, beside LiteLLM |
| **Runner hosts** | Run agents' turns. Each turn gets a sandbox: a Docker container, or a Kata Containers microVM, with the agent's runtime (Claude Code, Codex, pi or OpenCode) and its workspace. | The binary `tinfirm-runner-host` on Linux, macOS, or a VM, with Docker | As many as the work needs. A company runs its own; members may connect personal ones. |
| **Identity provider** | Optional. People sign in with it through OIDC, for example Microsoft Entra ID, Keycloak or Okta. Without one, people sign in with an email and a password. | Yours | |

A company that already runs LiteLLM can use its own instead of the bundled one: [Run the model gateway](model-gateway.md).

## What happens in a turn

Follow one message from a person to an agent's answer:

1. **A person asks.** They write in the web app, their AI client, Outlook, Gmail, Slack or Teams, or a trigger fires on an event. The server stores the message and queues a run for the conversation in PostgreSQL.
2. **The server picks a runner host.** Its dispatcher sends the run over the host's open connection to a host with a free slot: a company host, or a personal host of the person whose work it is. A run paid by a person's own subscription runs only on that person's host. A conversation that no host can run says so, and waits.
3. **The host prepares the sandbox.** It fetches each repository of the work into a mirror on the host, with the conversation's account on the code host, and clones a workspace from it. It downloads the conversation's files and the agent's skills, then starts the sandbox with the agent's runtime. No credential of the company enters the sandbox: the agent fetches git through a proxy on the host, which adds the token on the way out.
4. **The agent calls the model.** Its runtime talks to a model proxy on the host, which holds a token that works for this run only. The proxy forwards the request to the server's model relay, which checks the run and sends the request with the paying person's LiteLLM key to the model port and LiteLLM. LiteLLM checks the budget, calls the provider with the company's provider key, and records the spend. The answer streams back the same way. No model key ever reaches the runner host.
5. **The agent uses a tool.** A call to a tracker, a code host or an MCP server goes from the sandbox to the host's platform tools, and from there to the server, which makes the call with the conversation's account, under the agent's rules: some calls wait for a person's approval first.
6. **The agent needs a person.** A question, an approval, or an account to connect goes to the person's inbox, and to the place where the work happens, such as the chat thread.
7. **The turn ends.** The host pushes the agent's branch of each changed repository, with the token that stays on the host, and reports the result. The server opens a change request when the agent may, and writes the result back to where the work came from: the thread, the issue, the chat.

## Connections

Plan your firewalls and network policies from this table. Every connection is TLS, except inside the deployment, where you choose.

| From | To | Protocol and port | What for |
|---|---|---|---|
| Browsers, AI clients, the Outlook add-in | The server, through your ingress | HTTPS 443 | The web app, the REST API, the MCP endpoint (`/api/companies/<company id>/mcp`) |
| Slack (Events API), Microsoft Teams | The server, through your ingress | HTTPS 443, `/api/chat/…` | Messages and button presses for the bot. With Slack's Socket Mode, the server connects out to Slack instead, and Slack needs no route to you. |
| Google, for the Gmail add-on | The server, through your ingress | HTTPS 443, `/api/gmail/…` | The add-on's requests |
| Webhook senders | The server, through your ingress | HTTPS 443, `/api/hooks/<system id>` | Deliveries that start triggers |
| Runner hosts | The server, through your ingress | WebSocket and HTTPS 443, `/api/runner/…` | Runs, their events, files, skills and model requests. The host opens every connection. |
| The server | PostgreSQL | 5432, or your port | All of its data |
| The server | LiteLLM | 4000 (admin API) and 4001 (model port), inside the deployment | Keys, budgets and spend; the runs' model requests |
| LiteLLM | PostgreSQL | 5432, or your port | Its own database |
| LiteLLM | Model providers | HTTPS 443 | The model calls, and its price list |
| The server | The identity provider | HTTPS 443 | Sign-in, when you use OIDC |
| The server | Company systems: Jira, GitHub, GitLab, MCP servers, Slack's and Teams' APIs | HTTPS 443, or the system's port | Tool calls, polls of trackers, writing results back, the bot's replies |
| Runner hosts | Code hosts | HTTPS 443 | Fetching and pushing git |
| Sandboxes | The runner host's git proxy, platform tools and model proxy | Ports on the Docker bridge's gateway | Git fetches, tool calls and model calls of the agent |
| Sandboxes | The internet | As the agent needs | Package registries and documentation. With the host's egress proxy on, only the hosts you allow. |

The server's port 8081, with health and metrics, is never exposed through the ingress. LiteLLM is never exposed outside the deployment: runner hosts reach models only through the server.

## Where data and secrets live

| Where | What it holds | Protect it with |
|---|---|---|
| PostgreSQL, Tinfirm's database | Companies, members, agents, triggers, conversations, transcripts, files, decisions, the audit log, and the vault: every company secret (tokens, sign-ins, LiteLLM keys), encrypted | Backups with point-in-time recovery: [Prepare PostgreSQL](database.md) |
| The vault's master key | The key that unlocks the vault. It is not in the database, so a backup alone cannot be decrypted. | Your secret manager, outside the database: keep a copy with your backups' keys |
| PostgreSQL, LiteLLM's database | The provider keys, encrypted with LiteLLM's salt key; each person's and service account's LiteLLM key, budget and spend | The same backups, and the salt key in your secret manager |
| Runner hosts | The host's own token; for a personal host, its owner's subscription logins; repository mirrors and agents' session homes; a run's workspace while it runs | Disk encryption and access control on the machine. A company host holds no company credential beyond the git token of a running turn. |
| Sandboxes | The run's workspace and session, and nothing else: no model key, no system credential, no git token | The sandbox itself: Kata Containers for the strongest isolation |

## Scale and availability

- **More agents at once** means more runner capacity: add slots to a host, or add hosts. The server's share of a turn is light: it relays the model's stream and executes tool calls.
- **One server replica.** The server keeps the runner connections and the relayed model streams in its process, so it runs as one replica. While it restarts, runner hosts reconnect and their running turns carry on; the agent's runtime retries a model request that failed meanwhile. High availability is planned for Enterprise.
- **PostgreSQL** carries all state and the queue. Its availability is the installation's: use your provider's high availability or a replicated cluster if you need it.

## Next steps

- [Requirements](requirements.md): the hardware, software and network each part needs.
- [Try Tinfirm with Docker Compose](quick-start.md) on one machine, or [install on Kubernetes with Helm](helm.md).
- [Install and run runner hosts](runner-hosts.md).
