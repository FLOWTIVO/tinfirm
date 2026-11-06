# Operator guide

This guide is for the people who install Tinfirm and keep it running. It assumes you run containers in production and know your way around Kubernetes or Docker Compose.

## What you run

| Part | What it does |
|---|---|
| **Server** | One container image, `ghcr.io/flowtivo/tinfirm-server`, that serves the web app, the REST API and the MCP endpoints. It listens on port 8080, and on 8081 for its health probes. |
| **PostgreSQL 18** | Tinfirm's only database. It also holds the queue of work, so there is no message broker. |
| **LiteLLM** | The model gateway. Every model call an agent makes goes through it, with the key and budget of whoever pays. It ships with the Compose stack and the Helm chart, and only the server reaches it, unless you admit the company's own services too. |
| **Runner hosts** | Machines with Docker that run agents' turns, each in its own sandbox. A runner host connects out to the server, so it needs no inbound port. Company runner hosts are yours to run; members may connect personal ones. |

The company's other services can call the bundled LiteLLM with LiteLLM keys of their own, where you admit them: [The company's own services](model-gateway.md#the-companys-own-services).

[Architecture](architecture.md) shows how the parts connect, with a diagram, and [Requirements](requirements.md) says how much hardware each needs.

## Choose how to install

| Path | Use it for |
|---|---|
| [Docker Compose](quick-start.md) | Trying Tinfirm on one machine, development, or a single small host |
| [Helm chart `tinfirm`](helm.md) | Production on Kubernetes, with an external PostgreSQL 18 |

Both run the same server image. Agents also need at least one [runner host](runner-hosts.md), however you install the server.

## In this guide

### Plan

- [Architecture](architecture.md): the parts of an installation, what connects to what, and where data and secrets live.
- [Requirements](requirements.md): the hardware, software and network each part needs, with sizing examples.

### Install

- [Try Tinfirm with Docker Compose](quick-start.md): start the stack on one machine, add a model, connect a runner host.
- [Prepare PostgreSQL](database.md): the databases, roles, TLS, connections and backups.
- [Install on Kubernetes with Helm](helm.md): the Secrets, the values, the network policies.
- [Declare companies and how their people sign in](sign-in.md): companies, local sign-in and OIDC with Keycloak, Okta or Microsoft Entra ID.
- [Run the model gateway](model-gateway.md): add model providers and models to LiteLLM, or bring your own.
- [Install and run runner hosts](runner-hosts.md): install, sandboxes, Kata Containers, the egress proxy, capacity and upgrades.

### Run

- [Upgrade, back up and rotate secrets](maintenance.md): keep an installation current and its keys fresh.
- [Enter and renew a licence key](licence.md): unlock Pro or Enterprise, add seats, renew, also on an air-gapped instance.
- [Allow SCIM for a company](scim.md): let a company's identity provider provision its members, and bind their sign-in.
- [Verify your images](verify-images.md): check FLOWTIVO's signature and SBOM of each release image, offline, also on an air-gapped network.
- [The Instance admin pages](instance-admin.md): users, companies, the instance's audit log.
- [Error reports](error-reports.md): what the server reports about its own errors, and where it sends them.
