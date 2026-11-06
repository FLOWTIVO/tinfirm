# Documentation parity audit

Status: findings, fixed in the documentation on 2026-10-19 (§6) except the defects in the code (§2, §6.2) · Owner: J. Balcar

Every maintained document was checked against the code on `main`: the top-level files, `docs/PLATFORM.md`, `docs/CONVENTIONS.md`, the operations guides, the component READMEs, `DESIGN.md`, `api/openapi.yaml`, the runner protocol schemas, the ADRs, the threat model, and the header lines of the review records. The archive (`docs/archive/`) is history and was left out. Each finding was traced to a file and line in the code; the two code defects in §2 and a sample of the others were checked a second time.

## 1. Summary

Most of the stale text has one of five causes.

| # | Cause | Where it shows |
|---|---|---|
| A | **`main` runs the redesign of 2026-10-12**, but many documents still say `main` runs the design of 2026-10-05, or that the redesign is "decided, not built" | README Status, PRODUCT Capabilities, PLATFORM header and §12, CONTRIBUTING, CONVENTIONS §14 head, ADR index, the threat model's scope, litellm.md, helm.md |
| B | **ADR-0072 (credentials on systems, lent to groups and service accounts; V049, V050) is written into no design or rules document.** CONVENTIONS never names ADR-0072, V049, V050, `service_account_credential` or `credential.lent` | PLATFORM §3, §4, §5.2, §7.1, §7.3; CONVENTIONS §6, §7, §8, §9, §10, §15.1 to §15.3, §15.7, §15.13, §15.15, §15.16; PRODUCT Users |
| C | **Message steering (ADR-0060, V036) is documented nowhere** in CONVENTIONS, and §15.7 says the opposite ("During an active run nothing starts, and the message waits for the next turn") | CONVENTIONS §10, §15.6, §15.7 |
| D | **Guides for the removed 2026-10-05 domain are still presented as current**: connections, accounts on connections, shared accounts, agent kinds and rights, model credentials | connections.md, accounts.md, agent-permissions.md, mcp-servers.md, model-providers.md, most of litellm.md; server/README code layout; DESIGN.md layout; README Documentation table |
| E | **Status markers were not moved when features landed** | 25 ADRs "Proposed" though built; about 76 threat-model rows "Planned" though built; PLATFORM §12 State column; the package 1, 2, 3 and skills contracts "proposed, for review" |

What holds well: CONVENTIONS §4 (all 60 bound `TINFIRM_*` variables documented with matching defaults), §8 (all 141 audit actions exist), §9, §10 (all 20 modules, about 450 identifiers resolve), §15.10 to §15.22 (about 450 claims, 10 misses); OpenAPI (240 of 240 operations implemented, every code it names exists); the runner protocol (frames, fields and enums match on both sides); model-api/README, entra-id.md, agent-memory.md, error-reports.md.

## 2. Defects in the code found by the audit

These are not documentation problems; they change behaviour.

1. **Error reporting answers 500 when the operator turns it off.** `api/openapi.yaml:8811` has `enum: [OFF, LOCAL, AUTOMATIC]` with `OFF` unquoted, which YAML 1.1 reads as the boolean `false`. The generated `ErrorReporting.ModeEnum` holds `FALSE("false")`, so `ErrorReportingSettings.java:174` (`ModeEnum.fromValue(mode.name())`) throws for `OFF`, and `GET` and `PUT /api/admin/error-reporting` fail whenever `TINFIRM_ERROR_REPORTS=off`. The web app expects `'OFF'` (`error-reports.spec.ts:133`). Fix: quote it (`'OFF'`, as `openapi.yaml:8332` does) and add an integration test for the mode `OFF`. No other enum in the spec is affected.
2. **The pull-based deploy does not supply the model port's image.** `deploy/compose/compose.yaml:168-172` builds `model-api` with the image `${TINFIRM_MODEL_API_IMAGE:-tinfirm-model-api:local}`; `tinfirm-auto-deploy.sh:760` sets only `TINFIRM_SERVER_IMAGE` and runs `up -d --no-build` (`:764`); `deploy-host.sh` has the same gap. CI publishes `ghcr.io/flowtivo/tinfirm-model-api` (`ci.yml:451-464`). A host without a local build of that image should fail the deploy (not tested). Fix: pull `tinfirm-model-api:sha-<rev>` and set `TINFIRM_MODEL_API_IMAGE`, then document it in auto-deploy.md.
3. Smaller: `web/src/app/api/problem.ts:114` lists `ADMIN_ACCESS_REQUIRED`, which the server never had, and lacks `CREDENTIAL_REQUIRED`, which it sends; `ProblemCode.EMAIL_IN_USE` is only a `/login?error=` value; `TinfirmProperties.workItemUrl` (`shared/config/TinfirmProperties.java:47`) is unused and points at the removed work-item route; the runner's tool texts still say "work item" (`runner/crates/tinfirm-runner-host/src/mcp.rs:111-118`); the MCP rendering says "My accounts" where the navigation says "Accounts" (`mcp/internal/Renderings.java:440`).

## 3. Verdict per document

| Document | Verdict |
|---|---|
| README.md | Status and Documentation table stale (A, D); layout misses `deploy/ansible`, `deploy/release` |
| PRODUCT.md | Capabilities stale (A); "budgets per person" listed as not built though built; Users wording (B) |
| docs/PLATFORM.md | Header and §12 stale (A); §3 to §7 describe accounts before ADR-0072 (B); wrong platform tool names (§10) |
| CONTRIBUTING.md | Says the platform is being rebuilt on the 2026-10-05 design; checks table and CI jobs miss runner, model-api |
| SECURITY.md, docs/development.md | "M0 in progress"; development.md misses the `model-api` service, port 4001, `diagnostics` and `skill` modules, wrong e2e worker count |
| docs/CONVENTIONS.md §1 to §7 | Accurate except B (§6, §7) and missing rows for triggers, chats, webhooks, skills, memory |
| docs/CONVENTIONS.md §8 to §14 | Accurate except B, C; §14 head note wrong (A) |
| docs/CONVENTIONS.md §15.1 to §15.7 | §15.3 badly stale (B: 20 of 30 claims hold); steering (C) |
| docs/CONVENTIONS.md §15.10 to §15.22 | Current; 10 small misses, mostly B |
| operations/connections.md, accounts.md, agent-permissions.md, mcp-servers.md, model-providers.md | Obsolete (D) |
| operations/litellm.md | Partly stale, badly: §3, §4.3, §5, §7, §8 describe model credentials |
| operations/helm.md, runner-protocol-v2.md, auto-deploy.md, deploy/ansible/README.md | Partly stale |
| operations/mcp-clients.md, company-identity-providers.md, alerts.md, prompt-cache.md, external-database.md | Current, with one or two wrong facts each |
| operations/entra-id.md, agent-memory.md, error-reports.md | Current |
| server/README.md | Code layout obsolete; env table has dead rows |
| web/README.md, web/e2e/README.md | Mostly current; skills and new settings pages missing |
| DESIGN.md | Tokens current; Layout, Navigation and Thread sections describe the removed work-item page |
| runner/README.md | Flags, images, protocol current; server-side model credentials and pools stale |
| model-api/README.md, psl/README.md | Current |
| .impeccable/surfaces/…task-detail-html.md | Obsolete (its target file does not exist) |
| api/openapi.yaml | Current except the `OFF` defect (§2) and undocumented error codes |
| protocol/runner-v1 and v2 schemas | Current |
| docs/adr/ | Index complete; 25 statuses stale; one ADR not marked superseded |
| docs/threat-model.md | Scope and statuses stale throughout (A, D, E) |
| docs/reviews/ headers | Five status lines stale |

## 4. Findings by document

Severity: **WRONG** contradicts the code; **STALE** was true, is no longer; **MISSING** built but absent where the document claims completeness; **MINOR** wording, links.

### 4.1 Top-level files

- **README.md:11, :37, :182** WRONG (A): "What runs on `main` is the design of 2026-10-05", "`main` now carries the design of 2026-10-05". Also `:118-119` "on the restart's modules". Fix: say `main` runs the redesign; move the 2026-10-05 table under a history heading.
- **README.md Documentation table** STALE (D): `:161`, `:162`, `:170`, `:177`, `:178` describe connections, connection types, accounts on connections, model credentials and "until package 2" as current; error-reports.md is not listed.
- **README.md:115-130** MISSING: `deploy/ansible/` and `deploy/release/` (CONVENTIONS §2 lacks them too). **README.md:50** MISSING: `make up` also starts `model-api`.
- **PRODUCT.md:52-54** WRONG (A): "Implemented (the design of 2026-10-05, which `main` runs)" lists connections, work items and threads; "Not built yet … budgets per person", though a key and budget per principal are built (`V020__spend.sql`, `PATCH …/principals/{id}/model-access`). **PRODUCT.md:15** STALE (B): "which groups may use their accounts". **PRODUCT.md:19** MINOR: "Slack, Teams or Buzz" (Buzz is parked).
- **CONTRIBUTING.md:6** WRONG (A): "The platform is being rebuilt on the design of 2026-10-05". **:89-99** MISSING: no rows for `make runner-test`, `model-api-test`; no Rust prerequisite (`runner/Cargo.toml`, rust-version 1.89); CI jobs `model-api`, `runner`, `publish-services`, `rolling` absent; deploy-lint does more than "hadolint on the `Dockerfile`, actionlint". **:19** MINOR: tests also run against `TINFIRM_TEST_JDBC_URL` without Docker.
- **SECURITY.md:27**, **docs/development.md:6** STALE: "M0 is in progress".
- **docs/development.md:19, :44, :46-55** MISSING: the `model-api` service, port 4001, `TINFIRM_MODEL_API_HOST_PORT`, `SERVICE=model-api`. **:33** WRONG: the module list lacks `diagnostics` and `skill`. **:306** WRONG: e2e runs 3 workers by default (`web/playwright.config.ts:39`), not one. **:232** MINOR: the `app.system` bypass also covers `audit_check`.

### 4.2 docs/PLATFORM.md

- **:3, :245, :251-256** STALE (A, E): "What runs on `main` is the design of 2026-10-05"; §12 marks package 3 "In progress" and leaves 4, 5, 6 blank, though CONVENTIONS §15 records all as landed (`TriggerPolls.java`, `OutboxDelivery.java`, `WebhookReceiver.java`, `KillSwitchesService.java`); package 2's "the relay serves once package 3 implements `RunModelTokens`" is done (`RunActivityPorts.java:30`); package R has no row.
- **:37-38, :58, :60, :98-100, :135, :151** WRONG (B): accounts "one per principal", a system "holds no account of its own", a grant "lends a service identity's account", the bot "is a service identity's account". Since V049 company credentials sit on the system, grants lend them, a service account borrows at most one per system, and the bot is a credential lent to a service account.
- **:208** WRONG: platform tools `request(…)` and `save_output(…)`; the code serves `request_access`, `request_network_access` and `save_artifact` (`PromptRenderer.java:51-61`, `mcp.rs:97-99`).
- **:91, :202** WRONG: chats have a "fixed catalogue"; only Slack has one tool, `history`, and Teams none (`SystemKind.java:34-38`).
- **:192** MISSING: kill switches also include stopping the company's runs, pausing a system and turning personal MCP servers off (`KillSwitchesService.java:117-156`).
- **:98, :107** MISSING: an MCP server that needs no account (ADR-0071, V048).
- **:235** MINOR: requests reach LiteLLM through the model port (ADR-0069).

### 4.3 docs/CONVENTIONS.md

**§1 to §7**

- **:424, :425, :420, :547** WRONG/STALE (B): `account`, `account_grant` and the bot as before V049 (`principal_kind` `SYSTEM`, `account_name_uq`, `account_ck()`, `account_grant_credential_ck`, `external_system_bot_kind_ck`). **§7** MISSING: `service_account_credential`; the 7 skills tables (V040); `trigger_account`; `principal_departure_group`; `user_group`, `user_group_member`; columns `agent.turn_time_limit_minutes`, `message.steering`, `run_model_token.model_not_served`, `trigger.admin_changed_*`. 90 of 104 tables are described. **:383** STALE: `oauth_authorization_request` gained `credential_id`, `credential_name`.
- **§6 :305, :311** STALE (B): rows for a service identity's accounts; credentials are managed under `MANAGE_SYSTEMS` (`AccountService.java:199-201`), a service account's borrowing under `MANAGE_SERVICE_IDENTITIES`. **§6 :285** MISSING: no rows for triggers, chats, webhooks, skills, memory, though §15's head says "the permissions are in §6".
- **§4 :58** STALE: a fourth runner endpoint, the skill download (`RunnerSkillsController.java:42`). **§4** MISSING: `TINFIRM_REVISION` (`Dockerfile:101`, `BuildInfo.java:22`); tunables `tinfirm.runner.max-message-bytes`, `tinfirm.auth.login-throttle.*`, `tinfirm.oauth.cimd.*`, `tinfirm.auth.company-providers.*`. **:146** MINOR: seven model variables, not six.
- **§2 :4, :20** MISSING: `protocol/runner-v2.schema.json` (binding: `RunnerMessages.java:29-30`). **§2** MINOR: no owner for `SECURITY.md`, `DESIGN.md`, `PRODUCT.md`, `.pr_agent.toml`, `.editorconfig`, `.impeccable`, `deploy/ansible`, `deploy/release`. **§3** MINOR: no lines for Rust 1.89, Spring AI 2.0.1, LiteLLM v1.105.1, runner host 0.3.0.
- **§6 :370** MISSING: `DUPLICATE_CREDENTIAL_NAME`, `SKILL_NAME_CHANGED`, `DUPLICATE_SKILL_NAME`, `MODEL_REQUIRED` appear nowhere in CONVENTIONS.
- **:236, :88** STALE: "M0 has only the login throttle … limits come in M1"; MCP rate limits exist. **:390** MINOR: error reports also run as `app.system`.

**§8 to §14**

- **:991** WRONG (A): §14 is "the domain of the design of 2026-10-05, which the code on `main` runs". **:993** STALE: "the migrations are `V001__baseline.sql` to `V031`" (now V051). **:995** STALE: "grows with each package of its §13".
- **:1120** STALE: §14.5 Connections says "Implemented with package 2 (`connection` module)" without the "Superseded" banner §14.6 to §14.10 carry. **:993 vs :1330, :1361, :1384** MISSING marking: §14.13's Kinds, Rights, Deciding and running now, the company's MCP account, OAuth settings still read as current (`agent.kind`, `agent_group_right` exist in no migration).
- **:1316** WRONG: §14.12 (which still holds) says a provider's default role is `VIEWER`; it is `MEMBER` (`CompanyProviderService.java:278`, `V001__baseline.sql:524, :543`).
- **§8 ~:661-699** MISSING (B): `credential.changed`, `credential.lent`, `credential.withdrawn` (`AuditAction.java:397-405`), the `credential` detail on `account.*`, `followed` on `conversation.binding.bound`, `trigger.accounts_changed` with `why` `lending.replaced`. **:718** MINOR: `settings.changed` lacks `skillsOff`; §8 has no pointer to §15.19's actions.
- **§9 :871, :872** STALE (B): new secrets are `SYSTEM_CREDENTIAL` (`AccountSecrets.java:24`), `SERVICE_ACCOUNT` only for migrated ones; the 409's words changed (`AccountSecretKeeper.java:35`). **:875** MISSING: `MODEL_KEY_COMPANY` (`KeyHolder.java:67`).
- **§10 :893** MISSING (B): `CredentialUses`, `AccountEvents.CredentialChanged`, `LendingReplaced`, `Accounts.lentTo`, `borrowedBy`, `borrowers`, `credentialsConnectedBy`, `fixedAccess`, `bot`. **:898** MISSING (C): `Turns.steer`, `TurnSteered`. **:892, :890, :891** MINOR: `SystemEvents.AccountsRequiredChanged`; `skill` implements `GroupDependents` and `PrincipalDependents`.
- **§11 :955** STALE: routes lack skills and the settings sub-pages (`sign-in`, `limits`, `memory`, `secrets`). **§12 :969-978** STALE: model-api port 4001, `compose.oidc.yaml`, `compose.entra.yaml`, `make dev-db`; release manifests and CI jobs for model-api.
- **§14.2 :1024-1037, §14.3 :1041-1104** MINOR: removed codes and audit actions not all marked superseded.

**§15.1 to §15.7**

- **:1630, :1639** WRONG (B): `/service-identities/{id}/accounts` does not exist; a service account has `GET /service-identities/{id}/credentials`, and credentials are managed under `/systems/{id}/credentials…` (`openapi.yaml:1878, :1898-2076`). **:1628, :1640, :1642, :1648** STALE (B): the I2 refusal's details (`holder` `CREDENTIAL`), secret kind, what a grant lends, the trigger binding context (`AccountBinding.java:29-36`).
- **:1543** STALE (B): deleting a service identity withdraws what it borrows; the credentials stay (`AccountDependents.java:146-161`).
- **:1881** WRONG (C): "During an active run nothing starts, and the message waits for the next turn"; a message written during a turn is steered into it (`TurnService.java:58-60, :345-381`, `V036`, `Message.steering`). **:1805, :1807, :1833** MISSING (C): `Turns.steer`, the port `TurnSteered`, the `run.steer` frame, `ConversationSteeringIntegrationTest`.
- **:1910, :1943** STALE (B): a trigger's binding through a service account follows a replaced credential (V050, `BindingService.java:259-312`).
- **:1573** WRONG: the system's last test keeps `credentialId` and `credentialName`, not `accountId` (`SystemService.java:1259-1281`). **:1576** STALE: a catalogue that needs a token stays `OK` with `credentialRequired` (`CatalogueService.java:301-308`).
- **:1641, :1652, :1655** MISSING: callback reason `name_taken`; check reasons `SYSTEM_PAUSED`, `ACCOUNT_REQUIRED`; `CredentialChanged`, `LendingReplaced`; `CredentialMigrationTest`. **:1942, :1959, :2021** STALE: binding API's `credentialName`, `credentialKind`, `credentialEnabled`; `# Accounts` and inbox wording.
- MINOR: **:1536** the package map omits §15.22; **:1542, :1805** `skill` among `PrincipalDependents` and the run module's dependencies; **:1750** `PATCH /agents/{id}` takes `turnTimeLimitMinutes`; **:1793** readiness `NOT_NEEDED`; **:1695, :1700** `MODEL_REQUIRED`, `MODEL_NOT_ALLOWED`.

**§15.10 to §15.22**

- **:2283** STALE (B): `Accounts.serviceAccountsConnectedBy` is now `credentialsConnectedBy` (`DeparturesService.java:196-197`). **:2474** STALE (B): the bot is stored as the system's `TOKEN` credential `Bot`, lent to the service account (`ChatAccountsImpl.java:105`).
- **:2387-2391** MISSING (B): triggers following lendings (V050, `TriggerLendings.java:27-38`); the gate's `NOT_ENTITLED` pauses (`TriggerGateImpl.java:194-299`).
- **:2250** STALE: `Spend.paidBy` for `get_my_spend`; the tool is `get_spend` on `Spend.report` (§15.10 already says so). **:2364** STALE: "The web app's keys (W3, W4) are not built"; the web app sends one `Idempotency-Key` per submission (`core/submission-key.ts`). **:2399** MINOR: `accountHolderPrincipalId` listed as not built and built.
- MINOR: **:2475** `tinfirm.chat.max-body` is bytes, not characters; §15.16 never names `GET`, `PUT /chat-settings`; **:2166** `dismissNotice`, `restoreNotice`; §15.21 omits the list and delete routes.

### 4.4 Operations guides

- **connections.md** OBSOLETE (D): three connection types, `/connections` paths, `POST …/connections/{id}/poll`, `CONNECTION_POLLING`, `TINFIRM_SYNC_*`, "Polling, not webhooks" are all gone (systems at `/systems`, `TINFIRM_TRIGGER_*`, the `WEBHOOK` kind). Worth keeping: base URL and token permission tables, OAuth app steps (now the system's **Sign-in app**), `TINFIRM_CONNECTION_ALLOW_*`.
- **accounts.md** OBSOLETE (D): company and interactive agents, shared accounts; `CONNECTION_SHARED_ACCOUNT`, `connection.shared_account.*`, `ACCOUNTS_REQUIRED` exist nowhere. Worth keeping: the OAuth return errors, the client metadata document.
- **agent-permissions.md** OBSOLETE (D): Deny/Default/Allow/Ask, `sourceWrite`, agent kinds, group rights, the OPERATOR role. `ToolMode` has `ALLOW` and `ASK` only (`agent/ToolMode.java:4-9`); roles are OWNER, ADMIN, MEMBER.
- **mcp-servers.md** OBSOLETE (D), and in the reusable parts: tool names up to 64 characters, not 59 (`McpClient.java:45`); the handle is at most 20 characters (`SystemService.java:110`), not a 40-character slug; 100 systems per agent, not 50 connections (`AgentService.java:92`); the button is **Refresh tools**.
- **model-providers.md** OBSOLETE (D): `POST …/model-credentials` does not exist; provider keys live in LiteLLM, and the server sends only the relay's access.
- **litellm.md** STALE: `:5-10` "Agents use model credentials as `main` runs them today" and every "until/from package 2" (`:22, :26, :43, :52, :83, :93, :270`); `:58, :97` WRONG: Tinfirm adds no models on a "Model settings" page (the page is **Model access**; `LiteLlm.java` never calls `/model/new`); §5, `:257` and §8 rows OBSOLETE (model credentials, `reset-status`, cooldown; `run --model-proxy` is a no-op flag).
- **helm.md** `:321` MISSING: the egress rules omit Jira, MCP servers, Slack, Teams, OIDC providers and the error tracker, and port 443 opens only with OIDC providers (`templates/networkpolicy.yaml:86-95`). `:41, :360` STALE: without the master key the bundled gateway is silently off. `:105-111` MISSING: `connection.internalHosts`. `:220` MINOR.
- **auto-deploy.md** `:9-14, :46-51` MISSING: `publish-services` and the model-api image (see §2.2); `:96-110` MINOR: `AUTO_DEPLOY_MODE`, `_SERVER_URL`, `_RUNNER_CONFIG`.
- **runner-protocol-v2.md** `:98-100` WRONG: tool names are `{system handle}_{tool}` (`ToolNames.java:36-44`). `:13-16` MISSING: `NETWORK` / `request_network_access`; `platformTools`, `allowedHosts`, `skills` on `run.assign`. `:4` WRONG link: the runner mapping is PLATFORM §11.1.
- **deploy/ansible/README.md** `:23-31` OBSOLETE: company hosts hold no credentials of their own; `:56` STALE: pins runner 0.1.0 (budget-paid runs need 0.2.0 or later; current 0.3.0).
- **mcp-clients.md** `:130` WRONG: `JOB_CLOSED`, `WORK_ITEM_CLOSED`, `THREAD_EXISTS`, `THREAD_BOUND`, `ACCOUNTS_REQUIRED`, `CONNECTION_POLLING` do not exist; the tools name `CONVERSATION_ENDED`, `CONVERSATION_BOUND`, `TURN_ACTIVE`, `NOTHING_TO_RETRY`, `BINDING_NOT_WAITING`, `GRANT_NOT_OFFERED`, `AGENT_PAUSED`, `MODEL_ACCESS_UNAVAILABLE`, `NO_HOST_FOR_CONVERSATION`, `IDEMPOTENCY_KEY_REUSED`. `:122` MINOR: **Accounts**, not "My accounts".
- **company-identity-providers.md** `:11, :67` WRONG: "Viewer by default; Operator or Admin"; it is Member by default, or Admin. `:62` MINOR: links connections.md.
- **alerts.md** MINOR: `:26` the label is "Secret in a header (weaker)"; `:140` **Refresh tools**; `:174` service accounts; `:5` links mcp-servers.md.
- **prompt-cache.md** `:23` STALE (`V019` is now the webhooks migration); `:29` WRONG link (effort is PLATFORM §6); `:87-91` MINOR wording.
- **external-database.md** `:175` WRONG: `oauth_authorization_request` is the one table with `company_id` and no row-level security.
- Implementation comments with the same "package 2" staleness: `deploy/compose/litellm/config.yaml:4-5`, `compose.yaml:106-107`, `deploy/helm/tinfirm/values.yaml:205-212`, `templates/deployment.yaml:106-108`.

### 4.5 Component READMEs and DESIGN.md

- **server/README.md:63-81** OBSOLETE: the Code layout lists `connection`, `work`, `sync` (none exist) and misses account, chat, conversation, diagnostics, governance, mcp, principal, skill, system, trigger; names classes with no references (`AgentDirectory`, `JobRuns`, `ModelCredentialDirectory`, …). **:54-60** STALE: `TINFIRM_RUN_UNAVAILABLE_AFTER`, `TINFIRM_RUN_CHANGE_REQUEST_RETRY_AFTER`, `TINFIRM_RUN_MODEL_CREDENTIAL_COOLDOWN` are read nowhere; `TINFIRM_RUN_CHANGE_REQUEST_STATE_INTERVAL` is `TINFIRM_CHANGE_REQUEST_STATE_INTERVAL`; `TINFIRM_SYNC_*` gone. **:46** STALE: the outbound client is `system/internal/http/OutboundHttp.java`. **:30, :87, :91** MINOR: no company prefix in bootstrap; V001 is the redesign's baseline; runs go to v2 hosts only.
- **runner/README.md:500-553, :564, :117** STALE: the server sends only a relay access and a one-run token (`ModelRelayImpl.java:53-67`), never provider keys; it sends empty credential lists to company hosts (`RunnerHostSessions.java:185`), and there is no `desiredPoolSize`. **:20, :429, :1115, :1227** WRONG: cite PLATFORM §7.2, §12, §6 for the run protocol and risks, which are now other sections.
- **DESIGN.md:164, :219-234, :288, :311-337** STALE (D): the work-item page with thread tabs, "New thread with…", "End thread", "Work list"; the conversation page has "Cancel turn", "Retry", "End conversation" (`conversation-header.ts:365-385`). Also `:290` phase chips, `:332` "Latest", `:247` action bar, `:301` "an operator".
- **web/README.md:37-58** MISSING: Skills (six navigation items, not five), `settings/limits`, `settings/memory`, `/admin/errors`. **:29, :31** MINOR: `coming-page.ts` gone; fixtures list lacks `skills.ts`.
- **web/e2e/README.md:44** STALE: "Until W0 makes the app compile…"; **:269** STALE: no e2e job in `ci.yml`; **:7, :133** MINOR: Slack, MCP and webhook stubs; the seed's svc-dev is a lent credential.
- **model-api/README.md:30** MINOR: also compiles `libs/net`.
- **.impeccable/surfaces/web-src-app-pages-company-tasks-task-detail-html.md** OBSOLETE: `pages/company/tasks/task-detail.html` does not exist.

### 4.6 Contracts

- **api/openapi.yaml:8811**: the `OFF` defect (§2.1). **:8392-8399** WRONG: `autoRetry` retries a `LOST` turn only when the cause is `HOST_RESTARTED` or `STALE_ON_RECONNECT` (`TurnService.java:555-560`).
- MISSING error codes: `createTrigger`, `updateTrigger` omit `409 BOT_NOT_SET`, `400 CHANNEL_UNAVAILABLE`, `400 CHANNEL_NOT_JOINED`, `409 CHANNEL_TAKEN` (`TriggerEditor.java:818-860`); also `DUPLICATE_SECRET_NAME`, `USER_NOT_FOUND`, `DUPLICATE_MEMBER`, `LAST_INSTANCE_ADMIN`, `WRONG_PASSWORD`, `NOT_LOCAL_USER`, `NETWORK_HOST_LIMIT`, `PLACE_IS_EXTERNAL`, `SERVICE_IDENTITY_IN_USE` on their operations.
- MINOR: `:7094, :7109-7114` manager-only fields of `ChatSystemDetails` not marked; `:113-119` the webhook inbox also answers 404; `:43-45` `GET /oauth/client-metadata.json` not listed; `:13-14` "Triggers and chats arrive with packages 4 and 5".
- **protocol/runner-v2.schema.json** MINOR: the example codes omit `PERSONAL_HOST_CREDENTIAL_ONLY`, `CONVERSATION_ENDED`, `REQUEST_UNAVAILABLE`.

### 4.7 ADRs

- **docs/adr/README.md:4** WRONG (A): "which the code on `main` still runs". **:85** STALE: "Proposed" is defined through the M1 spike.
- **0037** STALE: still Accepted, though its work items, jobs and connections are gone; mark it superseded by ADR-0058 and add it to 0058's Supersedes line. **0058** MISSING: "Amended by: ADR-0072". **0063** MINOR: "Amended by: ADR-0065".
- STALE (E): built but "Proposed", in the file and the index: 0005, 0006, 0014, 0017, 0021, 0022, 0026, 0040, 0041, 0043, 0044, 0047, 0048, 0060, 0061, 0062, 0063, 0064, 0065, 0066, 0069, 0070, 0071, 0072, 0073.

### 4.8 docs/threat-model.md

- **:3, :15** WRONG (A): "The platform redesign … decided and not built … The code that runs is still the design above", while calling ADR-0049, 0051, 0052 built (their tables exist in no migration). From this follow 64 rows "Planned (ADR-0058)" and 12 "Planned (ADR-0059)".
- WRONG, marked planned but built: the MCP endpoint (`:396`, `:505`); chats and webhooks (§2.3, `:117`, TB11, TB12 `:174-175`, `:419`, `:436`, rows 11.1 to 11.9 and 12.1 to 12.4, `:508`); the model relay (`:448`, rows 13.1 to 13.3); personal hosts (5.8, 5.14); a branch per conversation (7.5); the egress proxy (TB7 `:170`); key blocking at suspension and departure (8.37, 8.38, Q33); the webhook scheme (12.2, Q38); chat limits and bot filtering (11.3, Q37); Owner reads shown (1.29, Q30); three roles (1.11, `:594`); personal access tokens and OIDC provider secrets (`:533-534`).
- WRONG, removed domain as current: `:329` the `connection` and `sync` modules; A15 to A17, A25 to A27 (`:137-149`); 8.9 purposes `connection-poll:{id}` (now `trigger-poll:`, `system-tools:`, …); 8.14 `sync_comment`; 8.27, 8.28; §6.5 `:587` OPERATOR and VIEWER roles; `:593` `RightsIsolationTest` (does not exist); `:506` `agent_group_right`; `:526` the company model credential key.
- STALE: 5.7, 5.10, 7.3, 7.13, R23, R25 (only a hashed one-run relay token reaches the host); 1.26, 1.27 (usage policy, OPERATOR); Q29 (shared accounts). MINOR: 1.19, 1.23.

### 4.9 Review records

- STALE headers: `2026-10-12-package-1-contract.md:3`, `package-2:3`, `package-3:3`, `2026-10-15-skills-contract.md:3` still "proposed, for review", though each is decided and built. `2026-10-12-platform-redesign.md:5` WRONG: "Nothing here is built". `2026-10-12-web-plan.md:11` STALE: "The web app still targets the 2026-10-05 API". `2026-10-05-restart.md:3` MINOR: links PLATFORM.md for the 2026-10-05 design (now `archive/v2/PLATFORM.md`).

## 5. Suggested order of work

1. The two code defects of §2, each with a test.
2. Cause A in one pass: README Status, PRODUCT Capabilities, PLATFORM header and §12, CONTRIBUTING, CONVENTIONS §14 head, ADR index, the threat model's scope line.
3. Cause B: write ADR-0072 into PLATFORM §3 to §7 and CONVENTIONS §6, §7, §8, §9, §10 and §15; cause C: a steering section in §15.6 and §15.7.
4. Cause D: replace connections.md, accounts.md, agent-permissions.md and mcp-servers.md with guides for systems and credentials and for agents' tools; delete model-providers.md or turn it into a page on adding providers to LiteLLM; cut litellm.md to what holds; rebuild server/README's code layout and DESIGN.md's layout sections.
5. Cause E: ADR statuses, threat-model rows, contract headers.
6. The minor findings.

`docs/PLATFORM.md` and `docs/CONVENTIONS.md` belong to their code owner (CONVENTIONS §2), so steps 2 and 3 need the owner's word on their parts.

## 6. Resolution

### 6.1 Fixed in the documentation

Every finding of §4 was corrected, and the changes made on `main` after the audit (the Outlook add-in, ADR-0074 and the docs site) were checked too:

- **Cause A.** README's Status, PRODUCT's capabilities, PLATFORM's header and §12, CONTRIBUTING, the ADR index, the threat model's scope and CONVENTIONS §14's head now say that `main` runs the redesign, built.
- **Cause B.** ADR-0072 is written into PLATFORM §3 to §7, CONVENTIONS §6 to §10, §15.1 to §15.3 and the new §15.23, the guides and the OpenAPI descriptions.
- **Cause C.** Steering is in CONVENTIONS §10, §15.6 and §15.7, and in PLATFORM §8.1.
- **Cause D.** connections.md and model-providers.md are pointer pages. systems.md, chats.md and triggers.md are new. accounts.md, agent-permissions.md, mcp-servers.md and litellm.md are rewritten. server/README's code layout and DESIGN.md's layout sections are rebuilt from the code. The superseded rules of CONVENTIONS §14 moved to [archive/v2/CONVENTIONS.md](../archive/v2/CONVENTIONS.md): §14 keeps only the rules that still bind the code, under the same numbers.
- **Cause E.** 26 ADRs are Accepted (the 25 of §4.7 and ADR-0074). ADR-0037 is superseded by ADR-0058, and the "Amended by" back-links are added. The threat model's Planned rows are re-statused against the code. The contracts' headers say decided and built.
- Every relative link and heading anchor outside the archive resolves. The docs site builds in strict mode.

### 6.2 Left for the owner: defects in the code

These change behaviour, so the documentation rewrite did not touch them. The guides describe the code as it is.

1. §2.1: the unquoted `OFF` in `ErrorReporting.mode` (`api/openapi.yaml`), which makes `/api/admin/error-reporting` answer 500 when `TINFIRM_ERROR_REPORTS=off`.
2. §2.2: the pull-based deploy and `deploy-host.sh` never set `TINFIRM_MODEL_API_IMAGE`. auto-deploy.md states the limitation and the workaround.
3. Review of results for SCHEDULE and WEBHOOK triggers is stored but never raised (`OutboxQueue` handles `TRACKER_ISSUE` only), though the editor and the package 4 contract promise it.
4. Setting a bot from a Slack workspace already registered on another system likely answers 500, not `409 CHAT_WORKSPACE_TAKEN` (`ChatSystemsImpl.setBot`, `external_system_chat_workspace_uq`).
5. `deleteServiceIdentity` has no `409` response in the contract. The Rust protocol crate's `request_code` constants still list `WORK_ITEM_CLOSED`, `UNKNOWN_CONNECTION` and `NO_ACCESS`.
6. **Web texts behind the code:**
   - The footer in `identity-triggers.ts` says that lending another credential pauses triggers; since V050 they follow it.
   - "Has a label of" means every label.
   - The Model access page's Period help text contradicts LiteLLM's calendar resets.
   - `sign-in-result.ts`, `token-hints.ts` and `group-dialog.ts` still say "shared account" or "lend it accounts".
   - `web/src/app/api/problem.ts` lists `ADMIN_ACCESS_REQUIRED` and lacks `CREDENTIAL_REQUIRED`.
7. **Server texts and smaller code points:**
   - `WaitingReason.RECONNECT_YOURS` and `RECONNECT_COMPANY` are no longer set.
   - An account's status `ERROR` is never set.
   - The MCP renderings say "My accounts" and "OAuth app".
   - `TinfirmProperties.workItemUrl` is unused.
   - `Grants.views` reads each credential's name with its own query.
   - `tinfirm.chat.max-body` is bytes over HTTP and characters over Socket Mode.
   - The runner's tool texts say "work item", "job" and "connection" (`mcp.rs`), and its `doctor` warns of no credentials on a company host.
8. **Code comments and config:**
   - Comments that cite sections of the archived PLATFORM.md or of the former CONVENTIONS §14: `RunnerMessages`, `mcp.rs`, `output.rs`, `AddressGuard`, `Mentions`, `ScheduleTest`, `CronTest`, `EffortLevel`, `ClientMetadataDocument` and others.
   - The comments in `application.yaml` on connections and `TINFIRM_SYNC_*`.
   - The javadoc of `CompanyPermission`, `InboxService`, `ManagedSecrets` and `TenantScope`.
   - `deploy/helm/tinfirm/values.yaml:108`; `deploy/helm/tinfirm/ci/litellm-external-values.yaml`.
   - `.impeccable/design.json`, generated before the redesign.
9. **Makefile against CI:** `make deploy-lint` does not shellcheck `deploy/compose/smoke.sh`, and `make ci` has no LiteLLM pin check.

