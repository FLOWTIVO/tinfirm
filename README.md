# Tinfirm

Tinfirm is a self-hosted platform on which a company runs AI agents, each with a role, instructions, tools and access, that people talk to (in Tinfirm, from their AI client, in Slack or Teams) and that triggers start from the systems the company already uses (Jira, GitHub, GitLab, a chat channel, a schedule, a webhook), do a bounded job in a sandbox with the accounts their audience allows (a person's own, or what a trigger's author chose), and bring every decision that needs a person to one inbox and to the issue where the company already looks. Agents serve every department: developers, business analysts, testers, finance.

The server is Java and Spring Boot, the web app is Angular, the runner host is Rust, and PostgreSQL is the only database.

The design is in [docs/PLATFORM.md](docs/PLATFORM.md). The binding engineering contract is [docs/CONVENTIONS.md](docs/CONVENTIONS.md).

## Status

`main` runs the redesign of 2026-10-12 ([ADR-0058](docs/adr/0058-authority-from-the-audience.md), [ADR-0059](docs/adr/0059-litellm-budgets-and-the-model-proxy.md), [docs/PLATFORM.md](docs/PLATFORM.md)): an agent is a definition that holds no credentials; a person's conversation acts with the person's own accounts, or a company credential lent to one of their groups, and is paid from their LiteLLM budget; a trigger acts with the accounts and budget its author chose. Changes land on `main` through pull requests.

Built: the packages of [PLATFORM §12](docs/PLATFORM.md#12-build-order), whose rules are in [CONVENTIONS §15](docs/CONVENTIONS.md#15-the-redesigns-domain):

- **1**, principals and systems: three roles, service accounts, systems, members' own accounts, grants, personal MCP servers;
- **2**, model access: LiteLLM bundled, a key and budget per principal, company and personal runner hosts, the server's model relay and the runner host's model proxy;
- **3**, agents, each person's settings, and conversations with their questions, approvals and outputs;
- **4**, triggers: Jira Data Center and Cloud, GitHub and GitLab by polling, schedules, the outbox and the write-back;
- **5**, chats: Slack and Microsoft Teams, direct messages, channel triggers, the generic webhook;
- **6**, governance: the MCP endpoint on conversations, the audit, retention, legal holds and erasure, spend and budget alerts, kill switches, suspension, members who leave;
- **R**, replay safety of tool calls and idempotent submissions.

Built since: steering a running turn ([ADR-0060](docs/adr/0060-steering-a-running-turn.md)), agent memory ([ADR-0063](docs/adr/0063-agent-memory.md)), skills ([ADR-0064](docs/adr/0064-skills-library.md)), the company's own model key ([ADR-0065](docs/adr/0065-company-model-key.md)), internal hosts ([ADR-0066](docs/adr/0066-internal-hosts.md)), error reports ([ADR-0070](docs/adr/0070-error-reports.md)), MCP servers without accounts ([ADR-0071](docs/adr/0071-mcp-servers-without-accounts.md)), credentials on systems lent to groups and service accounts ([ADR-0072](docs/adr/0072-system-credentials-lent-to-groups-and-service-accounts.md)), automatic retries ([ADR-0073](docs/adr/0073-automatic-retries.md)), the MCP endpoint's read tools on triggers, systems, webhooks and spend, the Outlook add-in ([ADR-0074](docs/adr/0074-outlook-add-in.md); its whole conversation and links that roam with the mailbox, [ADR-0076](docs/adr/0076-outlook-pane-thread-and-roaming-links.md)), the Gmail add-on ([ADR-0083](docs/adr/0083-gmail-add-on.md)), an email's attachments from both add-ins and Gmail's draft replies ([ADR-0084](docs/adr/0084-email-attachments-and-gmail-drafts.md)), companies and their sign-in declared by the deployment ([ADR-0075](docs/adr/0075-companies-and-sign-in-from-the-deployment.md)), the editions, the licence key and seats with the Viewer role ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md)), the Helm chart on GHCR and signed release images ([ADR-0080](docs/adr/0080-helm-chart-published-to-ghcr.md), [ADR-0081](docs/adr/0081-signed-release-images.md)), a member's Claude login on their runner host ([ADR-0085](docs/adr/0085-claude-login-on-the-runner-host.md)), SAML sign-in ([ADR-0086](docs/adr/0086-saml-sign-in.md)), SCIM provisioning ([ADR-0087](docs/adr/0087-scim-provisioning.md)), roles and companies from the identity provider ([ADR-0088](docs/adr/0088-roles-from-the-identity-provider.md), [ADR-0089](docs/adr/0089-companies-from-the-role-claim.md)), personal runner hosts in every company ([ADR-0092](docs/adr/0092-personal-runner-hosts-in-every-company.md)), Jira Cloud service accounts by client credentials ([ADR-0093](docs/adr/0093-jira-cloud-service-accounts-by-client-credentials.md)), elastic runners for Pro and Enterprise: the sandbox bridge, a VM per run, session bundles, ready VMs and their schedule ([ADR-0095](docs/adr/0095-sandbox-bridge.md) to [ADR-0100](docs/adr/0100-elastic-runners-in-pro.md)), the company's own services at the bundled LiteLLM, where the operator admits them ([ADR-0110](docs/adr/0110-no-model-port.md)), and the public docs site.

Releases are cut by hand with `.github/workflows/release.yml`, from `main` or a hotfix branch: each release `v<x.y.z>` publishes its signed images, its Helm chart `<x.y.z>` and its release assets (the runner host binaries and the Compose files), and [CHANGELOG.md](CHANGELOG.md) says what it holds and how to upgrade to it. **v1.0.0 is the first release.** Between releases, `main` is a development channel, not a release: after a push to `main` passes the gate, CI publishes that commit's images, tagged `sha-<commit>`, a chart `0.0.0-sha-<commit>` and the prerelease `rolling`, and moves the tag `main-complete` to the commit once they are all published; a deployment that follows `main` follows that tag ([CONTRIBUTING §5](CONTRIBUTING.md#5-checks-before-a-pull-request), [ADR-0107](docs/adr/0107-a-deployment-repository-follows-the-registry.md)). CI's `e2e` job runs the Playwright end-to-end tests against the server built from the commit ([web/e2e/README.md](web/e2e/README.md#in-ci)); the runner host's tests in real containers are not part of CI and run on a developer's machine.

The design of 2026-10-05, which the redesign of 2026-10-12 replaced, is archived at [docs/archive/v2/PLATFORM.md](docs/archive/v2/PLATFORM.md). [docs/reviews/2026-10-05-restart.md](docs/reviews/2026-10-05-restart.md) records its packages and what each verified.

## Quick start (Docker Compose)

You need Docker with Compose v2, and `make`.

```sh
cp deploy/compose/.env.example deploy/compose/.env
make up
```

`make up` builds the server image and starts PostgreSQL, LiteLLM (the model gateway, [Run the model gateway](docs-site/content/operator-guide/model-gateway.md)) and the server. Then open <http://localhost:8080> and log in with the bootstrap admin. The email and password are the values of `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` and `TINFIRM_BOOTSTRAP_ADMIN_PASSWORD` in `deploy/compose/.env`. The defaults are `admin@example.com` and `dev-only-admin-password`.

On the first start, the server creates an instance admin with the bootstrap email and password, because the user table is empty. It does this only once: a later change to the bootstrap values in `.env` does not change existing users. Change the admin password on the profile page (`/me`) after the first login.

Companies come from the deployment, never from the app ([ADR-0075](docs/adr/0075-companies-and-sign-in-from-the-deployment.md)). `compose.yaml` declares one company with the key `main`, named `TINFIRM_COMPANY_NAME` in `.env` (`My Company` by default). The server creates it at its start, and makes the user with the email `TINFIRM_COMPANY_OWNER` its OWNER, or the bootstrap admin when that is empty.

For agents to run, add a model provider to LiteLLM ([Add providers and models](docs-site/content/operator-guide/model-gateway.md#add-providers-and-models)) and connect a runner host ([Install and run runner hosts](docs-site/content/operator-guide/runner-hosts.md)).

> **Warning.** `.env.example` contains development-only defaults, including a published vault master key. Never use these values on a system that other people can reach. The server refuses to start with the published vault key, LiteLLM master key or bootstrap admin password unless `TINFIRM_PUBLIC_URL` is a localhost URL. Compose publishes every port on `127.0.0.1` only.

Other targets (`make help` lists all of them):

| Command | Effect |
|---|---|
| `make logs` | Follow the logs. `make logs SERVICE=server` follows one service. |
| `make down` | Stop and remove all containers, Keycloak included. The volumes are kept. |
| `make dev-db` | Start only PostgreSQL on `127.0.0.1:5432`, for a server that runs from your IDE |

To start again from an empty instance, delete the database volume:

```sh
docker compose -f deploy/compose/compose.yaml -f deploy/compose/compose.oidc.yaml --profile oidc down -v
```

### Optional: OIDC login with Keycloak

```sh
make up-oidc
```

This starts Keycloak (Compose profile `oidc`) with the imported realm `tinfirm`, and adds `deploy/compose/compose.oidc.yaml`, which holds the server's OIDC settings. The login page then shows a "Keycloak (dev)" button next to the local login form.

| Item | Value |
|---|---|
| Issuer | `http://keycloak.localhost:8180/realms/tinfirm`. It must match the `iss` claim exactly. |
| Client | Client ID `tinfirm`. Its secret is `TINFIRM_OIDC_CLIENT_SECRET` in `deploy/compose/.env`. |
| Test user | `alice@example.com`. Its password is `TINFIRM_OIDC_TEST_USER_PASSWORD` in `deploy/compose/.env`, default `dev-only-alice`. The realm file `deploy/compose/keycloak/tinfirm-realm.json` takes the value from that variable at import. |
| Keycloak admin console | <http://keycloak.localhost:8180/admin>, user `admin`, password `KEYCLOAK_ADMIN_PASSWORD` in `deploy/compose/.env` |

`*.localhost` resolves to the loopback address on macOS and on most Linux systems. Linux without systemd-resolved needs a hosts entry:

```text
127.0.0.1 keycloak.localhost
```

The first Keycloak login of alice creates a new user, because `TINFIRM_AUTH_OIDC_AUTO_PROVISION` is `true` by default. The server never links an OIDC login to an existing user by email. If the email already belongs to another user, the login fails with `EMAIL_IN_USE`. `compose.oidc.yaml` binds Keycloak to the company `main`, so alice becomes a MEMBER of it.

To use your own IdP, and to bind it to a company, follow [Declare companies and how their people sign in](docs-site/content/operator-guide/sign-in.md), which also walks through Keycloak, Okta and Microsoft Entra ID; the variables are in [CONVENTIONS §4](docs/CONVENTIONS.md#4-runtime-contract-of-the-server).

## Production

The Helm chart `deploy/helm/tinfirm` deploys the server through GitOps, on an external PostgreSQL 18, with the bundled LiteLLM. Each release publishes it to `oci://ghcr.io/flowtivo/charts/tinfirm` under the release's version, `--version 1.0.0` for v1.0.0, signed with the release's images ([ADR-0080](docs/adr/0080-helm-chart-published-to-ghcr.md), [ADR-0081](docs/adr/0081-signed-release-images.md)).

1. Prepare the database: [Prepare PostgreSQL](docs-site/content/operator-guide/database.md).
2. Create the Secrets and install the chart: [Install on Kubernetes with Helm](docs-site/content/operator-guide/helm.md).

A single Compose host runs a release from its Compose tarball, `tinfirm-compose-1.0.0.tar.gz` on the v1.0.0 GitHub Release, with the image `ghcr.io/flowtivo/tinfirm-server:v1.0.0`. For development, a Compose host can instead follow `main`: CI publishes every commit that passes the gate, and a timer on the host deploys it ([docs/operations/auto-deploy.md](docs/operations/auto-deploy.md)); those builds are not releases.

Runner hosts run on your own machines or VMs ([runner/README.md](runner/README.md); with Kata Containers, [deploy/ansible/README.md](deploy/ansible/README.md)). They speak runner protocol v2 by default. The server accepts a v1 host's connection but runs conversations on v2 hosts only ([docs/operations/runner-protocol-v2.md](docs/operations/runner-protocol-v2.md)). Every host of protocol 2 keeps a run's model token out of the sandbox: its model proxy is always on ([runner/README.md](runner/README.md#model-proxy)). Any runner host release that speaks a protocol the server supports works with it ([runner/README.md](runner/README.md#compatibility-with-the-server)).

Read the [threat model](docs/threat-model.md) before you expose an instance. To report a vulnerability, follow [SECURITY.md](SECURITY.md).

## Repository layout

| Path | Contents |
|---|---|
| `api/openapi.yaml` | HTTP contract (OpenAPI 3.0.3) |
| `server/` | Spring Boot server (Maven module `tinfirm-server`): the 20 modules of [CONVENTIONS §10](docs/CONVENTIONS.md#10-server-code-conventions), the Flyway migrations |
| `web/` | Angular SPA (npm package `tinfirm-web`) on the redesign's pages; Playwright tests in `web/e2e/` |
| `protocol/` | Runner protocol schemas: `runner-v2.schema.json`, the default, and `runner-v1.schema.json` |
| `runner/` | The Rust runner host and the sandbox images |
| `libs/jira/` | The shared Jira library (ADF and wiki markup, issues, searches), compiled into the server |
| `deploy/compose/` | `compose.yaml`, `compose.oidc.yaml`, `compose.entra.yaml` (Entra ID example), `.env.example`, the PostgreSQL scripts for Tinfirm's and LiteLLM's roles and databases, LiteLLM's config, the Keycloak realm, the deploy scripts (`deploy-host.sh`, `auto-deploy/`) and the image's smoke test |
| `deploy/helm/tinfirm/` | Helm chart: the server and the bundled LiteLLM; published as `oci://ghcr.io/flowtivo/charts/tinfirm` |
| `deploy/ansible/` | Playbook and role that turn a Linux VM into a runner host whose sandboxes are Kata Containers microVMs |
| `deploy/release/` | Builds the release assets: static runner host binaries and the Compose tarball (`make release-assets`) |
| `Dockerfile`, `Makefile` | Server image (web built in), developer entry points |
| `.github/` | CI, release, security and PR Agent workflows; Dependabot; the issue forms and the pull request template |
| `docs/` | Design, conventions, threat model, ADRs, operations guides, review records, the archive |
| `docs-site/` | The docs site, <https://docs.tinfirm.com>: the user, admin and operator manuals, built with Zensical (`make docs-serve`, [docs-site/README.md](docs-site/README.md)); image `ghcr.io/flowtivo/tinfirm-docs` |

CONVENTIONS §2 lists the owner of each path.

## Documentation

### Design and contracts

| Document | Contents |
|---|---|
| [docs-site/](docs-site/README.md) | The docs site, public at <https://docs.tinfirm.com>: manuals for the people who use Tinfirm, the Owners and Admins who set a company up, and the operators who install it. How to preview, write and build it |
| [docs/PLATFORM.md](docs/PLATFORM.md) | The platform design of record, the redesign of 2026-10-12: principals and roles, systems and accounts, agents and settings, triggers and channels, conversations and decisions, governance, prompt and tools, execution, build order, later work |
| [PRODUCT.md](PRODUCT.md) | What the product is, for whom, what is built and what is not |
| [DESIGN.md](DESIGN.md) | The visual design system |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | Binding engineering contract: layout, runtime configuration, authentication, the authorization matrix, database, audit, vault, code conventions, and the redesign's rules package by package (§15) |
| [api/openapi.yaml](api/openapi.yaml) | HTTP API contract |
| [protocol/runner-v2.schema.json](protocol/runner-v2.schema.json), [runner-v1.schema.json](protocol/runner-v1.schema.json) | Runner host protocol: v2, the default, and v1 |
| [docs/threat-model.md](docs/threat-model.md) | Assets, trust boundaries, STRIDE threats and their status |

### Working on the code

| Document | Contents |
|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | Prerequisites, local setup, checks, CI, commit and PR rules |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | The Contributor Covenant: how everyone who takes part behaves, and where to report a breach |
| [docs/development.md](docs/development.md) | Local development: the Compose stack and its ports, the server and the web app from your IDE, configuration, the database roles and row-level security, tests |
| [server/README.md](server/README.md) | The server: build and test, running against the Compose database, its environment, the code layout |
| [web/README.md](web/README.md) | The web app: commands, layout, navigation and routes, shared patterns |
| [web/e2e/README.md](web/e2e/README.md) | The Playwright end-to-end tests: what the harness starts, what it needs, how to run and write specs |
| [runner/README.md](runner/README.md) | The runner host: install, sandboxes, the egress and model proxies, Kata Containers, protocol versions, images, skills, steering, platform tools |
| [deploy/ansible/README.md](deploy/ansible/README.md) | Runner hosts with Kata Containers on a Linux VM, set up with Ansible |
| [SECURITY.md](SECURITY.md) | How to report a vulnerability, what follows a report; supported versions |

### Operations guides

How to use, set up and run Tinfirm is the docs site, <https://docs.tinfirm.com>, written from `docs-site/` ([docs-site/README.md](docs-site/README.md)). The guides below keep only what the people who build Tinfirm need beyond it; each opens with a link to its pages on the site.

| Guide | Engineering notes on |
|---|---|
| [docs/operations/systems.md](docs/operations/systems.md) | Systems: how Tinfirm calls each kind (API versions, token checks, tests, the Sign-in app's endpoints), polls, the webhook endpoint, the kill switch and the outbound guard inside; problem codes, audit events, the API |
| [docs/operations/accounts.md](docs/operations/accounts.md) | Members' accounts, credentials, lending and service accounts: how a conversation's binding and a trigger's accounts table are checked, states and codes, the sign-in callback, Tinfirm's client metadata document, the audit |
| [docs/operations/agent-permissions.md](docs/operations/agent-permissions.md) | What an agent may use: why settings cannot widen it, approvals and requests inside, the checks every tool call passes, endpoints, tables, codes and the audit |
| [docs/operations/triggers.md](docs/operations/triggers.md) | Triggers: the API's values and codes, how a tracker is polled, the order of a firing's checks, what the agent is told, the write-back's internals, the audit |
| [docs/operations/chats.md](docs/operations/chats.md) | Slack and Microsoft Teams: endpoints, payloads, codes and audit events, the manifest and the Bot Framework, how long answers are split, why the bot refuses what it refuses |
| [docs/operations/mcp-servers.md](docs/operations/mcp-servers.md) | An MCP server as a system: the protocol with the server and its authorization server, tool names in a run, codes and audit events, a service on the deployment's own network |
| [docs/operations/mcp-clients.md](docs/operations/mcp-clients.md) | The MCP endpoint for people's AI clients: the OAuth server (discovery, client metadata documents, scopes, token lifetimes, its signing key) and the tools' internals |
| [docs/operations/outlook-add-in.md](docs/operations/outlook-add-in.md) | The Outlook add-in ([ADR-0074](docs/adr/0074-outlook-add-in.md)): where it is served, its requirement sets, its OAuth client and token flow, its links, CSP and frame ancestors |
| [docs/operations/gmail-add-on.md](docs/operations/gmail-add-on.md) | The Gmail add-on ([ADR-0083](docs/adr/0083-gmail-add-on.md), [ADR-0084](docs/adr/0084-email-attachments-and-gmail-drafts.md)): the endpoints Google calls, how requests and tokens are verified, where attachments wait |
| [docs/operations/agent-memory.md](docs/operations/agent-memory.md) | Agent memory ([ADR-0063](docs/adr/0063-agent-memory.md)): the API, the token budgets of what the agent sees, the credential scan, compaction and retention inside |
| [docs/operations/session-bundles.md](docs/operations/session-bundles.md) | Session bundles ([ADR-0097](docs/adr/0097-session-bundles.md)): a conversation's session kept between turns so that it resumes on any company host; the store (a directory or S3-compatible storage), its keys and its retention |
| [docs/operations/alerts.md](docs/operations/alerts.md) | Alerts as triggers: what a delivery puts in the prompt, and an example setup (the LogsQL rule on VictoriaLogs, a logs MCP server per analyst, the analysts' instructions, Fluent Bit for Tinfirm's own logs) |
| [docs/operations/litellm.md](docs/operations/litellm.md) | LiteLLM, the model gateway ([ADR-0059](docs/adr/0059-litellm-budgets-and-the-model-proxy.md), [ADR-0110](docs/adr/0110-no-model-port.md)): how the gateway and the relay are secured, the admin routes and key aliases Tinfirm uses, codes and audit events of Model access, the keys of the company's services |
| [docs/operations/prompt-cache.md](docs/operations/prompt-cache.md) | A run's four token counts, what a follow-up turn pays for the prompt cache, and the measurements of Claude Code's TTL |
| [docs/operations/error-reports.md](docs/operations/error-reports.md) | Error reports ([ADR-0070](docs/adr/0070-error-reports.md)): which replica delivers, and the vendor's side, receiving the reports |
| [docs/operations/companies-and-sign-in.md](docs/operations/companies-and-sign-in.md) | Declared companies and OIDC providers: what the start does inside, the Enterprise limit on companies, the login's codes and audit reasons, the migration from providers configured in the app, the Compose overlays for development |
| [docs/operations/entra-id.md](docs/operations/entra-id.md) | Microsoft Entra ID: its claims and issuer in the server, the codes of a refused login, the threat model on `TRUST_ISSUER` |
| [docs/operations/helm.md](docs/operations/helm.md) | The Helm chart: how CI publishes it and a deployment repository sets its image, the variables behind its values, the reasons for its rules, the LiteLLM pin, upgrades from older charts |
| [docs/operations/external-database.md](docs/operations/external-database.md) | PostgreSQL: how the chart builds the connection, the expand/contract migrations, the recovery targets, how LiteLLM rewrites its database URL |
| [docs/operations/auto-deploy.md](docs/operations/auto-deploy.md) | Deploy every commit on `main` to one Compose host, and runner-only hosts that follow it; not on the docs site |
| [docs/operations/runner-protocol-v2.md](docs/operations/runner-protocol-v2.md) | Move runner hosts from protocol v1 to v2: choosing the version, the negotiation, moving a fleet |
| [docs/operations/connections.md](docs/operations/connections.md), [docs/operations/model-providers.md](docs/operations/model-providers.md) | Pointers kept so that links from the ADRs and review records resolve: the connections and model credentials of 2026-10-05 are gone; read systems.md and litellm.md |

### Records

| Document | Contents |
|---|---|
| [docs/adr/README.md](docs/adr/README.md) | Architecture decision records |
| [docs/reviews/2026-10-12-platform-redesign.md](docs/reviews/2026-10-12-platform-redesign.md) | The record of the redesign of 2026-10-12, decided part by part: why, each decision and its reasoning, the packages, the validation scenarios |
| Package contracts: [1](docs/reviews/2026-10-12-package-1-contract.md), [2](docs/reviews/2026-10-12-package-2-contract.md), [3](docs/reviews/2026-10-12-package-3-contract.md), [4](docs/reviews/2026-10-12-package-4-contract.md), [5](docs/reviews/2026-10-12-package-5-contract.md), [6](docs/reviews/2026-10-12-package-6-contract.md), [R](docs/reviews/2026-10-12-package-r-contract.md), [skills](docs/reviews/2026-10-15-skills-contract.md) | The contract of each package of the redesign: its tables, rules, API, errors and audit actions |
| [docs/reviews/2026-10-12-web-plan.md](docs/reviews/2026-10-12-web-plan.md) | The plan of the web app on the redesign |
| [docs/reviews/2026-10-05-restart.md](docs/reviews/2026-10-05-restart.md) | The restart of 2026-10-05: its packages and what was verified |
| [docs/reviews/](docs/reviews/) | The other review records and analyses |
| [docs/archive/v1/](docs/archive/v1/README.md), [docs/archive/v2/](docs/archive/v2/README.md) | The documents of v1, before the restart; the design of 2026-10-05 |

## History

Tinfirm has been built since April 2026, and its source was published here in one commit: this repository's history starts with that commit. The decisions and reviews that shaped it are in the [Records](#records): the decisions, each dated and with who decided it ([docs/adr/](docs/adr/README.md)), the dated reviews, plans and package contracts ([docs/reviews/](docs/reviews/)), and the earlier designs ([docs/archive/v1/](docs/archive/v1/README.md), [docs/archive/v2/](docs/archive/v2/README.md)).

## Editions

One image runs as **Community** (free), **Pro** or **Enterprise**, unlocked by an offline licence key that an instance admin pastes in the app ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md)). Agents, sandboxes, triggers and runs are unlimited in every edition. Pro counts seats by role: Owners, Admins and Members hold one, Viewers do not. The docs site says what each edition includes and what it costs ([Editions and prices](https://docs.tinfirm.com/admin-guide/editions/)), how roles take seats ([Roles](https://docs.tinfirm.com/admin-guide/roles/#seats)), and how to enter and renew a key ([Enter and renew a licence key](https://docs.tinfirm.com/operator-guide/licence/)); its sources are in [docs-site/content/](docs-site/content/). The licence key, the seats and every gate of a paid feature are built (`ee/`, [ee/README.md](ee/README.md)); the audit streamed to a SIEM, high availability and the air-gapped bundle of Enterprise are not built yet.

## License

[LICENSE](LICENSE) states the licence that [ADR-0077](docs/adr/0077-licence-editions-and-seats.md) decides: MIT for everything outside `ee/`, and the Tinfirm Enterprise licence for `ee/` ([ee/LICENSE](ee/LICENSE)), modelled on GitLab's. The documentation, `docs-site/` included, and the code served to the browser are MIT. FLOWTIVO s.r.o. holds the copyright. Contributions are signed off under the DCO and licensed under MIT, to `ee/` too ([CONTRIBUTING §6](CONTRIBUTING.md#sign-off-and-licence)).

Every image and release archive carries the licence and the notices of the components it includes (an image under `/licenses/`), and CI refuses a dependency whose licence is outside one allow list ([ADR-0082](docs/adr/0082-licence-notices-and-dependency-licences.md)). [NOTICE](NOTICE) names the copyright holder, both licences, the third-party files in the repository, and where each artefact keeps its third-party notices.
