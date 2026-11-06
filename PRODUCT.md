# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Four kinds of people, in every department of a company, not only in software:

- **People who steer agents:** a product owner who has a business analyst agent prepare tickets, a test lead who has a tester agent write scenarios, a team lead who has a developer agent implement labelled issues, a finance lead who has an analyst agent evaluate projects every month. They set up triggers, answer questions in the inbox or on the issue, and review results.
- **Hands-on builders:** engineers and founders who run agents on their own subscriptions and runner hosts, read transcripts, steer a running turn, and review pull requests.
- **Viewers:** they read the agents shared with them, the conversations of the triggers whose team they are in, and their own inbox; they start nothing, decide nothing and hold no seat.
- **Administrators:** they register the company's systems and the credentials those systems lend to groups and to service accounts, manage service accounts, budgets, runner hosts and members, and read the audit log. Any member may define an agent and share it.

## Product Purpose

Tinfirm is a self-hosted platform on which a company runs **AI agents**, each with a role, instructions, tools and access, defined once, that react to events in the systems the company already uses (Jira, GitHub, GitLab, a chat channel in Slack or Microsoft Teams, a schedule, a generic webhook any other software can call), do a bounded job in a sandbox with the accounts the audience of the work allows (a person's own, or those a trigger's author chose) and under the company's policies, and bring every decision that needs a person to one inbox and to the place where the work started. An agent can also be talked to: a person starts a conversation with it in Tinfirm, from their own AI client or in a direct message to the chat bot.

Success means:
- work reaches a known end state, with a visible cause, where the company looks for it;
- humans spend attention only where a human decision is needed;
- the same platform serves developers, analysts, testers and finance.

## Positioning

- **Agents, not an org chart.** Each agent does its own jobs; no agent manages another.
- **Triggers, not a backlog.** The company's tracker owns the issues and the workflow; triggers say which agent does what when.
- **The inbox, not a board.** Home is "what needs me".
- **Universal.** Code and non-code work are the same thing to the platform: a job with a workspace, tools, decisions and artifacts.
- **Credentials stay in your deployment.** No vendor cloud; no system's credential in the sandbox, and no model gateway's key either: the gateway's keys stay on the server, and the runner host's model proxy, always on, keeps even a run's own token out of the sandbox.
- **Reliability.** Event-driven, every run ends in a known state.

## Operating Context

- **Deployment:** self-hosted with Docker Compose or Kubernetes (Helm, GitOps). Runner hosts run on the company's own machines or VMs, with Docker or Kata microVMs, and connect out over WebSocket.
- **Model spend:** per person. Every member and service account has one key on the company's model gateway, with a budget: one Tinfirm makes on LiteLLM, with the budget admins set, or one the company's gateway team makes on its own gateway; a person's conversations spend their own budget, a trigger's runs the budget its author chose, their own or a service account's. A member's own subscription (Claude Max, ChatGPT Pro) serves only their own work, on their own runner host. Tinfirm's own model requests, such as compacting agent memory, spend the company's own key.
- **Daily loop:**
  1. An event happens in Jira, GitHub or GitLab, someone mentions the bot in a chat channel, a schedule fires, another system calls the webhook, or a person starts a conversation (in Tinfirm, from their AI client, or in a direct message to the bot).
  2. The matching triggers start or continue a conversation, with the accounts and budget the trigger's author chose; a person's conversation uses the person's own accounts, or a company credential lent to one of their groups, and the person's budget. The agent runs a turn in a sandbox, and its live transcript streams in the browser.
  3. The result goes back: a comment, a reply in the chat thread, a document, a pull request, a transition.
  4. A person answers a question in the inbox, on the issue or in the chat thread, decides an approval in the inbox or with the bot's buttons in the chat thread, reviews a result in the inbox, or writes in the conversation, also while a turn runs; the agent continues with the same context.
- **Core objects:** companies; members and service accounts (the principals), groups; systems with members' own accounts and the company's credentials, lent to groups and service accounts; agents with each person's settings, their skills and memory; triggers; conversations with their turns (runs), questions, approvals and outputs; model access; runner hosts, company or personal.
- **Design:** [docs/PLATFORM.md](docs/PLATFORM.md).

## Capabilities and Constraints

- **Stack (fixed):** Angular 22 (standalone, zoneless, signals) with spartan/ui and Tailwind 4; Java 25 and Spring Boot for the server; Rust for the runner host; PostgreSQL 18.
- **CSP:** strict, no inline scripts, same-origin connections, self-hosted fonts.
- **Live data:** transcripts can reach 20 000 events per run.
- **Implemented** (the redesign of 2026-10-12, which `main` runs: [PLATFORM.md §12](docs/PLATFORM.md#12-build-order), [CONVENTIONS §15](docs/CONVENTIONS.md#15-the-redesigns-domain)):
  - login, local and OIDC, with companies and their sign-in declared in the deployment's configuration; SAML sign-in and SCIM provisioning for Enterprise ([ADR-0086](docs/adr/0086-saml-sign-in.md), [ADR-0087](docs/adr/0087-scim-provisioning.md)); roles and companies from the identity provider at every sign-in ([ADR-0088](docs/adr/0088-roles-from-the-identity-provider.md), [ADR-0089](docs/adr/0089-companies-from-the-role-claim.md)); members with four roles (OWNER, ADMIN, MEMBER, VIEWER), groups and service accounts; secrets in the vault; the hash-chained audit log;
  - systems: Jira Data Center and Cloud, GitHub, GitLab, git hosts, MCP servers (the company's, members' personal ones, and servers that need no account), Slack, Microsoft Teams and a generic webhook; members' own accounts by token or sign-in; company credentials on systems, lent to groups and service accounts ([ADR-0072](docs/adr/0072-system-credentials-lent-to-groups-and-service-accounts.md));
  - agents shared with groups and members, each person's settings narrowing them; the runtimes Claude Code, Codex, pi and OpenCode; systems and their tools (`ALLOW` or `ASK`), repositories (`READ` or `WRITE`) and network hosts; the company's library of skills ([ADR-0064](docs/adr/0064-skills-library.md)); agent memory ([ADR-0063](docs/adr/0063-agent-memory.md));
  - conversations in Tinfirm, from people's AI clients through the MCP endpoint and in direct messages to the chat bot: turns with live transcripts, steering a running turn, questions, approvals and the inbox, outputs (change requests on GitHub and GitLab, documents, links), automatic retries of turns that failed for a reason that may pass;
  - triggers on tracker issues and change requests (Jira, GitHub and GitLab, by polling), on schedules, on chat channels and on webhooks, each with its author's accounts or a service account's, a payer and a team; the write-back to the issue or the thread; a trigger keeps one conversation per issue, so several triggers can work on one issue at once;
  - model access: the bundled LiteLLM or a company's own, or a gateway whose keys the company's gateway team provides ([ADR-0094](docs/adr/0094-more-than-one-model-gateway.md)), a key and budget per member and service account, the company's own key for Tinfirm's own requests, members' subscriptions on their personal runner hosts, the server's model relay and the runner host's model proxy; the model port for the company's own services ([ADR-0069](docs/adr/0069-company-model-gateway.md));
  - runner hosts, company or personal, with Docker or Kata sandboxes, and runner protocol v2 with tools the server executes; elastic runners for Pro and Enterprise: the sandbox bridge, a VM per run, ready VMs on a schedule and session bundles that resume a conversation on any company host ([ADR-0095](docs/adr/0095-sandbox-bridge.md) to [ADR-0100](docs/adr/0100-elastic-runners-in-pro.md));
  - governance: spend views and budget alerts, kill switches, suspension, members who leave, retention, legal holds and erasure, the audit's daily check; error reports ([ADR-0070](docs/adr/0070-error-reports.md));
  - the MCP endpoint for people's own AI clients, with Tinfirm as the OAuth 2.1 authorization server and personal access tokens ([ADR-0045](docs/adr/0045-mcp-server-for-peoples-clients.md)), its tools on conversations, decisions and memory, and its read tools on triggers, systems, webhooks and spend;
  - an Outlook add-in, served by Tinfirm, that asks a person's agents about the email they have open ([ADR-0074](docs/adr/0074-outlook-add-in.md));
  - a Gmail add-on, whose endpoints Tinfirm serves, that does the same in Gmail's side panel ([ADR-0083](docs/adr/0083-gmail-add-on.md));
  - the public documentation site for the people who use, administer and install Tinfirm, at <https://docs.tinfirm.com>;
  - the editions: Community, Pro and Enterprise from one image, an offline licence key an instance admin pastes, seats counted by role, and every paid feature checked by the server ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md));
  - deployment with Docker Compose or the Helm chart, published to GHCR, with signed release images ([ADR-0080](docs/adr/0080-helm-chart-published-to-ghcr.md), [ADR-0081](docs/adr/0081-signed-release-images.md)), continuous delivery of `main` to a Compose host, and Ansible for runner hosts with Kata Containers.
- **Not built yet** ([PLATFORM.md §14](docs/PLATFORM.md#14-later-work-and-open-items)): Buzz, parked; reusing agents (duplicate, templates, export and import as code); a GitHub App for installation and user tokens; webhooks as hints for the next poll; Jira's changelog for issues that are not yet subjects; state shared across server replicas, and with it Enterprise's high availability; the audit streamed to a SIEM; the air-gapped installation bundle; the sandbox hardening of the [threat model](docs/threat-model.md#7-accepted-risks). Not released yet.
- **Licence and editions, decided** ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md)): an MIT core and a commercial `ee/`, both public; Community, Pro and Enterprise unlocked by an offline licence key; seats in Pro by role, with a Viewer role that holds none. Built: the licence key, its expiry and grace, the seats and the gates of the paid features (`ee/`); the licence files are in the repository (`LICENSE`, `ee/LICENSE`), every artefact carries them ([ADR-0082](docs/adr/0082-licence-notices-and-dependency-licences.md)). The docs site describes the editions and prices ([Editions and prices](https://docs.tinfirm.com/admin-guide/editions/)), seats and roles ([Roles](https://docs.tinfirm.com/admin-guide/roles/)) and the licence key ([Enter and renew a licence key](https://docs.tinfirm.com/operator-guide/licence/)). Contributions carry the DCO's sign-off ([CONTRIBUTING §6](CONTRIBUTING.md#sign-off-and-licence)).

## Brand Commitments

- Name: **Tinfirm**. The app and its docs say "AI agents": "one platform for AI agents in every system you work in".
- The visual design system is [DESIGN.md](DESIGN.md): sage ground, white sheets, green for the agent, orange only where a person is needed. It must stay clearly different from a dark dashboard of dense tables.

## Evidence on Hand

- Do not fabricate customers, testimonials, benchmarks or press.

## Product Principles

1. **Attention is the scarce resource.** Show humans only what needs a human decision, and make that decision fast, where they already are.
2. **Every job ends in a known state.** Status, cause and evidence are always visible. Nothing fails silently.
3. **The user owns everything:** data, credentials, runners and subscriptions.
4. **Work over theater.** Agents do work; they are not characters in an org chart.
5. **One model for every kind of work.** A feature for developers must work for analysts and finance too.
6. **Honest numbers.** Estimates are labelled as estimates, and capacity and cost are shown as they are.

## Accessibility & Inclusion

- WCAG 2.2 AA as a hard requirement: contrast, visible focus, full keyboard operation and reduced motion.
- Light and dark themes are both first-class.
