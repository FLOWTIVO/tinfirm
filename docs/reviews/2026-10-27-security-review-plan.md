# Fixing the security review of 2026-10-27: a plan

Status: proposed · 2026-10-27 · Owner: J. Balcar

This plan closes every finding of [the security review of 2026-10-27](2026-10-27-security-review.md): H1 to H4, M1 to M18, L1 to L35, I1 to I10 and the design findings D1 to D9 (M11, M12, L28 and L31 withdrawn), with the accepted-risk verdicts of its §8.4. It follows the rules the previous fix round set ([2026-10-27-review-findings-fixed.md](2026-10-27-review-findings-fixed.md)): one pull request per topic, inside the paths its area owns (CONVENTIONS §2); a unit test that fails before the fix wherever the finding allows one, an integration case where the proof needs PostgreSQL, Docker or a real runner; the threat model updated in the same pull request (CONTRIBUTING §6); `docs/CONVENTIONS.md` and `docs/PLATFORM.md` not edited by the packages, their wording proposed here in §5.

The work is twelve packages (§2), each one pull request or a small series, in three rounds (§3). Eight decisions come first (§1): they change what four of the packages build. §4 is the list of findings by package, so that nothing is lost; §6 is what happens outside the code; §7 is how the round is validated and recorded.

## 1. Decisions, taken by the owner on 2026-10-27

Each names the finding, the question, the owner's decision, and what it changes in the packages.

| # | Finding | Question | Decision |
|---|---|---|---|
| Q1 | D4, H1, H4, M16, L5 | Do the companies of one instance trust each other's admins? | **Yes, same operator.** The companies of one instance are declared by one operator who vouches for all their admins (for example, one organisation's companies on one instance). Hosting companies that do not trust each other on one instance is forbidden, said in the threat model and the operator guide. H1, H4, M16 and L5 become accepted-risk rows with that reason; the cheap checks land anyway as hardening (WP1: the SCIM binding by the provisioning company, the SAML entity ID pinned, instance admins never SCIM-bound). Add-by-email, `USER_NOT_FOUND` and the add-by-email invitation stay as they are |
| Q2 | H2 | May an admin reassign a live trigger, or edit one on the author's behalf, so that it carries the author's own accounts? | **Only after the author's loss.** Reassignment only of a trigger paused `AUTHOR_LEFT` or `NOT_ENTITLED`; it stays paused `TO_ACCEPT` until the new author accepts its rows, payer and acknowledgement; an inbox item tells them. An admin's edit on the author's behalf may add no row that names the author's own account or own MCP server |
| Q3 | D2 | Who acknowledges a trigger open to anyone that writes with a lent credential? | **The author, and the lender is informed.** The author's acknowledgement covers every open write, own or lent; whoever lent the credential gets an inbox item the first time a trigger exposes it to an open source, and the credential's and the service account's pages list such triggers. No flag on lendings |
| Q4 | D1 | Does a change to a shared agent's instructions or skills need acceptance? | **When the ceiling can act.** An instruction or skill change on an agent whose current ceiling has an `ALLOW` write tool, a `WRITE` repository or a network host is widening, accepted per person and per trigger author with a diff; otherwise it is audited and announced in the conversation |
| Q5 | D3 | Are READ tools on third-party systems accepted as data sinks, or built against? | **Accepted, with caps.** An accepted-risk row that records the owner's decision; argument size caps per tool call (64 KiB) enforced server-side; a trust class shown on each system's page as information. The `ASK` on a class change is not built |
| Q6 | D9, L34 | Do personal access tokens expire by default, and are they scoped to companies? | **Expiry, scope and revocation.** 90 days by default with renewal; a company list on each token (all companies for tokens made before the change); revoked with OAuth grants on a password change, an admin reset and on leaving a named company; the current password asked to create one |
| Q7 | L35 | May an instance admin reset a company OWNER's local password? | **Yes, audited per company.** An event in the chain of every company the user belongs to, naming the instance admin |
| Q8 | M15 | How are repository mirrors on a runner host keyed? | **Per principal.** The account or credential the fetch used joins the mirror key; maintenance prunes per principal |

## 2. The work packages

Each package: the findings it closes, the changes by file, the tests, the documents it touches, and a size (S: a day; M: two to three days; L: a week). Paths are repo-relative; `server/…` means `server/src/main/java/com/flowtivo/tinfirm/…`.

### WP1. Identity binding across companies and providers

Closes **H1, H4, M16, M17, L5, L32, I10, D4** (under Q1: H1, H4, M16 and L5 are closed as hardening and recorded as accepted risks for instances that break the one-operator rule). Areas: `identity`, `ee/identity/saml`, `ee/scim`, `company`. Size: **S to M** (Q1 removes the invitation flow, the generic `USER_NOT_FOUND` answer and the oracle work).

- `server/identity/internal/ExternalProvisioning.java` (`scimUser`): bind only when `user.getScimCompanyId()` equals the provider's company (H1) and the user is not an instance admin (M16). Keep the OWNER exclusion. Two lines; built even under Q1 because they cost nothing and close the takeover for an instance that is mis-declared.
- `ee/…/scim/ScimDirectoryImpl.java` (`create`): for an adopted user of another company, ignore the caller's `externalId` (it names a membership, never the user); `deactivate` and `delete` set `scim_active=false` on the user only when `scim_company_id` is this company, else only suspend or remove the membership (L5, the deactivation half). The cross-company adoption itself, the duplicate-email answer and the returned name stay as they are (Q1: same operator).
- `server/identity/internal/SamlProperties.java` and `ee/…/saml/SamlIdpMetadata.java`, `SamlMetadataRefresh.java`, `SamlRegistrations.java`: require `_ENTITY_ID` per provider, or store the first accepted entity ID in `saml_idp_metadata` (new migration `V069__saml_entity_id.sql`, column `entity_id`) and refuse a later document that names another, audited `saml.idp_entity_id_changed`, shown on the sign-in page as "metadata refused"; at startup refuse two providers (SAML or OIDC) that share an issuer or entity ID (H4).
- `server/identity/internal/SecurityConfiguration.java`: refuse to start for an OIDC registration without `issuer-uri` (L32) or without `openid` in its scopes (M17); `ActiveUserFilter.java` and `AuthorizationServerUserFilter.java`: invalidate the session and clear the context when the principal is not a `SessionUser` (M17).
- Tests: `ScimOidcBindingIntegrationTest` (provision through company B's SCIM, adopt through A's, sign in through A's provider: refused; the same user through B's provider: bound), `ScimTenantIsolationTest` (deactivation of an adopted user suspends the membership only), `ScimEditionIntegrationTest` (instance admin never bound), `SamlIdpMetadataTest` (entity ID change refused and audited), `SamlRegistrationsTest` (duplicate identifiers refuse startup), `OidcLoginIntegrationTest` (registration without `issuer-uri` or `openid` refuses startup; a non-`SessionUser` principal gets a new session).
- Docs: threat-model 17.2, 17.4, 16.9, 10.16 and R38 retired (I10); the one-operator rule of Q1 as a sentence in §2 and as accepted-risk rows for the cross-company adoption by SCIM and by email, the `USER_NOT_FOUND` answer across companies and the returned name (R3 and R7 restated with that reason); `docs/operations/companies-and-sign-in.md` says that the companies of one instance must share one operator.

### WP2. Trigger authority: reassignment, VIEWER, open triggers with lent credentials

Closes **H2, M7, D2**. Areas: `trigger`, `principal`, `account`, `conversation` (inbox). Size: **L**. Needs Q2, Q3.

- `server/trigger/internal/TriggerService.java` (`reassign`): allowed only when the trigger is paused `AUTHOR_LEFT` or `NOT_ENTITLED`; the reassigned trigger stays paused `TO_ACCEPT` (new `PausedReason`), with `open_events_acknowledged_at` reset; the new author's `accept` (existing endpoint, extended) accepts rows, payer and acknowledgement together and resumes; an inbox item `TRIGGER_REASSIGNED_TO_YOU` for the new author (`TriggerInboxImpl`, listener on `TriggerReassigned`). `choices(forPrincipalId)`: only the author of a trigger the caller edits, or the target of an allowed reassignment. `update` on the author's behalf (`TriggerEditor.check`): refuse a row that names the author's own account or own MCP server unless the existing trigger already had it (`403 AUTHOR_ACCOUNT_NOT_ADMINS`); refuse `accept` by anyone but the author.
- `server/principal/internal/PrincipalsImpl.java` (`usableServiceIdentities`): empty for VIEWER; `trigger/internal/TriggerGateImpl.java` (`entitled`): a VIEWER author pauses `NOT_ENTITLED`; a listener on `MemberRoleChanged` runs the gate on the author's triggers; `reassign` and `choices` refuse a VIEWER target (M7).
- `server/trigger/internal/TriggerEditor.java`: `needed = !writes.isEmpty()` (every open write, own or lent, needs the author's acknowledgement; the refusal text no longer recommends a service account as the way out and says that the lender will be told). `GET /systems/{id}/credentials/{id}` and the service account's page list the open triggers that use the credential (`openTriggers[]`, API change); an inbox item `CREDENTIAL_EXPOSED_TO_OPEN_SOURCE` for whoever lent the credential (the grant's or lending's `created_by`, else the system's managers) the first time a trigger exposes it, with the trigger and the source (D2, Q3). No change to `account_grant` or `service_account_credential`.
- Tests: `TriggerIntegrationTest` (reassignment of a live trigger refused; of an `AUTHOR_LEFT` one paused `TO_ACCEPT` until the new author accepts; an admin's edit that adds the author's own account refused; `choices?forPrincipalId` limited; a VIEWER author paused on demotion through the app, the IdP claim and SCIM; an open trigger with a lent write refused until the author acknowledges, and the lender's inbox item once it is saved), `PrincipalsImplTest` (VIEWER gets no service accounts).
- Docs: PLATFORM §4 (ADMIN), §7.2; CONVENTIONS §15.15, §15.23, §6 matrix rows; threat-model 8.36, R41 restated ("the author acknowledges every open write; the lender is told"), R43; the API (`api/openapi.yaml`): `openTriggers[]`, the inbox item and the `TO_ACCEPT` reason.

### WP3. One gate for the reveal: kill switches, host kind, agent access

Closes **M2, M6, L22, L23, I4, D5, D6**. Areas: `conversation`, `run`, `agent`. Size: **M**.

- `server/conversation/internal/BindingService.java` (`use`) and `account/internal/AccountsImpl.java` (`check`): refuse, or answer "waiting", while the company's runs are stopped, the conversation's trigger is paused or its agent is paused, for every purpose but the outbox's own drop; `ParkedCalls.bound` and `take` leave `ALLOW` calls parked under those states; the company's stop (`RunSwitches`) also settles parked calls `REFUSED COMPANY_STOPPED` (M6, D5).
- `server/conversation/internal/TurnSourceImpl.java` (`content`): when `placement.hostKind()` is `PERSONAL`, recompute the holders of the repositories about to be revealed and throw `RunPlacementStale` if any holder is not the payer; `RunDelivery` requeues on it (M2, D6). The same predicate (`HostsImpl.personalHostMayRun`) used at plan and at reveal, one function.
- `ConversationService.message`, `retry` and `TurnSourceImpl.plan`: re-check `agents.access(...).uses()`; a lost share ends the turn `AGENT_NOT_USABLE` and the conversation waits (L22). `AgentSkills` (pin move): require `skills.sees` (L23). `ToolCalls` journal: compare the tool name with the arguments hash (I4).
- Tests: `ParkedCallsTest` (an `ALLOW` call parked under a stop stays parked; is settled by the stop), `OutboxSwitchesIntegrationTest` extended, `RunDispatchIntegrationTest` (a repository approved between plan and reveal on a personal host requeues the run: inject the approval through a test hook between the two transactions), `ConversationIntegrationTest` (unshare ends the next turn), `AgentSkillsTest`, `ToolCallsTest`.
- Docs: threat-model 5.14 ("checked again at the reveal"), package-6 rule G4 extended to parked calls; CONVENTIONS §15.7 (the reveal as the gate: proposed wording in §5).

### WP4. Shared agents: instruction and skill changes

Closes **D1, I5**. Area: `agent`, `web`. Size: **L**. Needs Q4.

- `server/agent/internal/AgentDefinition.wideningOver` and `AgentVersions`: an instruction or pinned-skill change counts as widening when the ceiling after the change has any `ALLOW` write tool, `WRITE` repository or network host; `Widening.details` carries `instructions: changed` and the version's instruction text is kept (`agent_version.instructions`, migration `V071__agent_version_instructions.sql`); otherwise audit `agent.instructions_changed` and record the version on the conversation's next turn (`SYSTEM` message "The agent's instructions changed on …, by …").
- `web/`: the acceptance page shows the diff of the instructions between the accepted and the current version (`pages/company/agents/accept`), and the conversation shows the change notice.
- Tests: `AgentVersionsTest` (which changes widen), `AgentIntegrationTest` (a person on the accepted version keeps it; a trigger author too), web specs for the diff.
- Docs: PLATFORM §6, threat-model 9.11 extended to instructions, R46-style row for the remainder (an owner's instruction change inside a ceiling that cannot act).

### WP5. The model relay and the model port

Closes **M1, M3, D8, I8 (relay items)**. Areas: `model`, `libs/litellm`, `model-api`. Size: **M**.

- `libs/litellm/…/LiteLlmRequests.java`: remove `mcp_servers` and `container`; keep only client-side `tools[].type` values (`function`, `custom`, and the runtimes' local tool types, listed) and refuse the request `400` otherwise; the relay allow-lists `anthropic-beta` and `openai-beta` values (a list in `ModelProperties`); a company setting `modelSettings.providerTools` (`OFF` by default) that turns the refusal into pass-through for `web_search` only (M1, D8). The model port applies the same filter (it shares the class).
- `server/model/internal/ModelRelayService.java` (`body`): read a declared length through the incremental `read(in, max, share)` loop, reserving as bytes arrive; a body deadline (`relay.body-timeout`, 30 s) and `server.tomcat.connection-timeout` set in `application.yaml` (M3).
- `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` defaults to `true`; the Helm chart and Compose say so; the release notes name runner 0.3.0 as the minimum (I8).
- Tests: `LiteLlmRequestsTest` (the removed fields, the refused tool types, the beta allow-list), `ModelRelayIntegrationTest` (two slow declared bodies do not exhaust the share; a body past the deadline is 408), `ModelPortTest` (the same filter).
- Docs: threat-model 7.2, 13.x, R13 restated ("the provider is a channel for what the model emits, not a relay the agent directs"); `docs/operations/litellm.md`.

### WP6. Runner-facing flood controls on the server

Closes **M4, L24, I7 (server side), I8 (bundle 500)**. Areas: `run`, `runner`, `ee/run`. Size: **M**.

- `server/run/internal/RunRequestRouter.java`: a per-run semaphore on `TOOL` requests (`runner.tool-calls-in-flight`, 4) answered `REJECTED TOO_MANY_REQUESTS` beyond it; a bucket per host and per company on `run.request` and `run.event` (bucket4j, as the MCP limiter); `RunnerMessages` and `RunnerSessionHandler`: `payload` at most 256 KiB and a text chunk at most 32 KiB (`REJECTED PAYLOAD_TOO_LARGE`), a per-run byte budget (`runner.events-max-bytes`, 64 MiB) after which only `LOG` events are dropped and the rest refused with a `run.event` warning; `longValue()` of usage clamped and negative or non-finite values ignored.
- `spring.servlet.multipart.resolve-lazily: true` in `application.yaml`, and `RunnerFilesController` authenticates before the multipart is read (L24); `ee/…/SessionBundlesImpl`: a slot per host, a read deadline; `RunnerSessionBundleController` uses `authentication.forRun` (404 for a personal host).
- Tests: `RunnerSessionIntegrationTest` (floods rejected, payload caps, the byte budget), `RunnerFilesIntegrationTest` (an anonymous multipart is refused before it is read: assert no temp file), `SessionBundleIntegrationTest` (slots per host; personal host 404).
- Docs: threat-model 5.12 rewritten with the limits; CONVENTIONS §14.10 (the limits, proposed in §5).

### WP7. The runner host

Closes **M5, M15, L25, L26, L27, I6, I7 (host side), I8 (host items), D7 (the host's half)**. Areas: `runner/crates`, `ee/runner`, `runner/images`, `deploy/ansible`. Size: **L**. Needs Q8.

- `git_proxy.rs`, `mcp.rs`: a whole-request deadline (headers and body, 60 s), an idle timeout, a cap per peer address and per route (8), `tokio::time::timeout` around the MCP body read (M5). `mcp.rs`, `attachments.rs`, `host.rs`: `stat` and refuse above the server's limit (read from `hello`'s answer or a config value) before reading; a host-wide upload semaphore (3); streamed multipart (M5).
- `mirror.rs`: `mirror_key(principal, url)` (Q8), with maintenance pruning per principal (M15).
- `ee/…/session_bundles.rs`, `tinfirm-bridge/src/files.rs` (`pack`): `lstat` every ancestor of a session folder and refuse one that is not a plain directory (L25).
- `mapper.rs`, `run.rs` (`EventSink`), `util.rs`: redact before truncating, keep a rolling tail of `max_secret_len - 1` bytes per text stream across chunks; `mcp.rs::redact_json` redacts keys as `redact.rs` does (L26).
- `usage.rs`: saturating arithmetic, non-finite and negative costs dropped (I7). `model_proxy.rs`/`runtime_env.rs`: refuse a `RunCredential::Model` without a `base_url` (I8). `mcp.rs`: `readOnlyHint` from the tool's effect; `attachments.rs::safe_file_name`: refuse Unicode format and bidi characters; `main.rs`: `login --token` warns and prefers stdin (I8).
- `runner/images/base/Dockerfile`: `node` by digest; `claude`, `codex`, `opencode`: `npm ci --ignore-scripts` from a committed lockfile (I6).
- `deploy/ansible/roles/tinfirm_runner_host`: `--egress-proxy` and `--sandbox-bridge` on by default (a variable to turn each off, documented), `assert` non-empty checksums when a URL is given, the known SHA-256 of the pinned Kata release in `vars/`, Docker's key fingerprint checked, systemd `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `ReadWritePaths` for the data and config directories, a service uid other than 1000 (L27, D7).
- Tests: `tests/local_runs.rs` and `docker_runs.rs` (listener deadlines and caps; upload refused above the limit; uploads in parallel bounded), `mirror.rs` unit tests (keying), `session_bundles` test with a symlinked parent, `redact` tests for a secret split across two chunks, `usage` tests; the Ansible role's `checks.yml` extended.
- Docs: threat-model 6.5, 7.1, 7.15, R24's and R28's "default" sentences, R26, R34 narrowed, 5.13 (company host as tier-0, §5 of this plan); `docs-site` operator guide for the new defaults.

### WP8. Outbound and inbound hardening

Closes **M8, M9, L3, L6, L7, L8, L9, L10, L11, L12, L13, L14, L15**. Areas: `system`, `account`, `identity` (CIMD), `trigger` (webhook), `chat`, `company` (SCIM). Size: **M**, two pull requests (outbound, inbound).

Outbound:
- `system/internal/oauth/OAuthDiscovery.usable`: every endpoint on the issuer's origin, or on a short allow-list of split-hosted providers (`tinfirm.oauth.discovery.allowed-endpoint-origins`); `SystemService.setApp`: when the token or revocation endpoint changes, remove the OAuth accounts and credentials in the same transaction and say so (`409 SYSTEM_APP_ENDPOINTS_CHANGED` on preview, the removal on confirm) (M8).
- `system/internal/mcp/McpClient` and `CatalogueService`: a `HashSet` for names, 1 MiB per `tools/list` page, at most 500 tools, 64 KiB per schema and 2 MiB in all, the refresh failing with the reason; a refresh rate limit per system (M9).
- `identity/internal/CimdDocumentFetcher`: a semaphore (8) and a per-address limiter on unknown client ids, a negative cache (L11); `OutboundHttp`, `GatewayHttp`, `CimdDocumentFetcher`: `.proxy(NO_PROXY)` or the documented explicit proxy (L12); `OAuthProperties`: the CIMD test settings refused outside the test profile (L13); `OAuthClient.tokens`: tokens above 4 KiB refused (L14); `AddressGuard`: `168.63.129.16`, `2002::/16` and Teredo decoded (L15).

Inbound:
- `trigger/internal/WebhookInbox`: parse the UUID first (404 otherwise) and key both limiter calls by `id.toString()`; a per-address limiter on the Slack endpoints too (L6); `WebhookSystemsImpl.rotate`: a "revoke the previous secret now" option (L8).
- `chat/internal/ChatReceiver` and `InboundLimits`: the per-user limit first, the system's count only for events that passed it, a small bucket for guest and external events (L7); `TeamsVerifier.keys`, `GoogleIdTokens.keys`: fetch outside the monitor (L9).
- `chat/internal/ChatLinks` and `web/…/pages/link/chat-link.ts`: the link page shows a live `users.info` name and avatar (Slack) or the Entra name (Teams) and the workspace, and queued messages are not released on confirmation but shown as waiting for the person's own send (L3).
- SCIM: a request body limit (256 KiB) in `ScimRequestFilter`; `ScimFilter.EQ` rewritten without the nested quantifier (L10).
- Tests: `OAuthDiscoveryTest` (endpoints off the issuer's origin refused), `SystemServiceIntegrationTest` (endpoint change removes accounts), `McpClientTest` (caps), `CimdDocumentFetcherTest` (concurrency), `AddressGuardTest`, `WebhookInboxIntegrationTest` (an upper-case id counts), `ChatReceiverTest` (one user cannot starve the system), `ChatLinksIntegrationTest` (queued messages not released), `ScimFilterTest`.
- Docs: threat-model 8.7, 8.22, 12.4, 11.3, 11.6; CONVENTIONS §14.4 (the endpoint rule), §15.2.

### WP9. The web app and the browser boundary

Closes **M10, L1, L4, I9**. Areas: `web`, `identity` (headers), `run`/`conversation` (streams). Size: **S**.

- `web/src/app/shared/markdown.ts`: the `text` renderer always escapes; specs for `<kbd> <a …`, `<code> <img …`, `<pre>` and `<script>` openers that assert no element or attribute survives; `safeHref` rejects backslashes (M10, I9).
- `web/src/app/pages/outlook/task-pane.ts`: the reply body built from escaped text with paragraphs and line breaks, links as `text (url)` (M10). `server/identity/internal/OutlookAddInProperties.java`: path-scoped `script-src` (`https://appsforoffice.microsoft.com/lib/1/hosted/`, the exact `MicrosoftAjax.js` URL), checked in a real Outlook before merge (M10).
- `run/internal/RunEventService` and `conversation/internal/ConversationStreams`: keep the session id and check it at every poll; end the stream when the session is gone, and at the session's absolute expiry (L1).
- The non-session filter chains call `securityHeaders` (L4). `sign-in-result.ts`: do not echo the unknown value; `permissions.ts`: instance-admin helpers follow the server (I9).
- Tests: `markdown.spec.ts`, `task-pane.spec.ts`, `RunEventServiceIntegrationTest` (a stream ends after logout), `SecurityHeadersTest` over every chain.
- Docs: threat-model 1.25, 1.20, R50 extended.

### WP10. Vault, audit, sessions, tokens and the database role

Closes **M14, L2, L16, L17, L18, L19, L20, L21, L33, L34, L35, I1, I2, I3, D9**. Areas: `vault`, `audit`, `identity`, `diagnostics`, `shared/tenant`, migrations. Size: **M**. Needs Q6, Q7.

- A startup assertion (`DatabaseRoleCheck`, an `ApplicationRunner` that holds readiness): `current_user` is not a superuser, has no `BYPASSRLS`, owns no relation and is not a member of `tinfirm_owner`; a CI schema test that every table with `company_id` has forced RLS and a policy, with the allow-list of the 18 instance-level tables (M14).
- `LocalLoginService`: a semaphore around `matches` (8), `429 RATE_LIMITED` when full; `LoginThrottle`: consume the per-email token before the check and refund on success, key IPv6 by /64 (L2, L33).
- `ApiTokenService`, `PasswordService`, `api_token` (migration `V072__api_token_scope.sql`: `expires_at` default 90 days, `company_ids uuid[]`): expiry by default, a company list, revocation of tokens and OAuth grants on a password change or reset and on departure, the current password required to create a token (D9, L34); `PasswordService.setPassword` by an instance admin audited in each of the target's companies (L35, Q7).
- `V073__audit_chain_head_trigger.sql`: a `BEFORE UPDATE` trigger requiring `NEW.last_seq = OLD.last_seq + 1` and a matching `audit_event` (L16). `V074__conversation_rebind_search_path.sql`: `CREATE OR REPLACE` with `search_path = pg_catalog, pg_temp` and `public.app_company_id()` (L20).
- `EnvMasterKeyProvider`, `BootstrapService`: refuse the published dev key (by SHA-256), the dev LiteLLM keys and the dev bootstrap password when `TINFIRM_PUBLIC_URL` is not localhost (L17, Q9 closed).
- Spring Session: a JSON serialiser (`springSessionConversionService` with the Security Jackson modules), or at least `-Djdk.serialFilter` in the image's `JAVA_TOOL_OPTIONS` (L21).
- `ClientErrors`, `ErrorReportStore`: row budgets per source and a cap on distinct web fingerprints per user per hour (L18). `VaultServiceImpl.reveal`/`scan`: assert the current tenant context is null, SYSTEM or the same company. `SecretService.create`: reserve managed prefixes (I1). `RefusalAudit`: the remote reason through `Scrubber.mask` (I2). `maintenance.md` step 4: the session-bundle keys (L19). I3: a sentence in ADR-0077.
- Tests: `DatabaseRoleCheckTest` (a role with `BYPASSRLS` refuses startup, Testcontainers), `RowLevelSecurityTest` generalised over the catalog, `LoginThrottleTest`, `ApiTokenIntegrationTest` (expiry, company list, revocation on password change), `AuditChainIntegrationTest` (a head moved backwards is refused by the trigger), `EnvMasterKeyProviderTest`, `SessionSerializationTest`, `ClientErrorsTest`.
- Docs: threat-model 2.5, 2.10, R5, R6, R18 (the serial filter), 4.3 and Q9 closed, A22 (token scope), 1.15; CONVENTIONS §5 (tokens), §7 (the startup check), §9.

### WP11. Deployment and CI

Closes **H3, M13, M18, L29, L30**. Areas: `.github` (its reusable workflows included), `deploy` and the settings in §6. Size: **L**.

The calling workflows and the other files:
- `.github/workflows/release.yml`, `ci.yml`: the `release` caller passes `environment: release` through the reusable release workflow's new input (below); `pr_agent.yml` reads `OPENAI_KEY` from an environment restricted to `main` and same-repository pull requests; `signing-key`, `sign` and the release `image` job run on a dedicated runner label, read from `vars.CI_RELEASE_RUNNER`; PR builds use cache ids suffixed `-pr` (`Dockerfile`, `model-api/Dockerfile`, `runner-musl.Dockerfile` take a build arg) (H3).
- `.github/CODEOWNERS`: `.github/**`, `deploy/**`, `Dockerfile`, `model-api/Dockerfile`, `docs-site/Dockerfile`, `.github/dependabot.yml`; `dependabot.yml`: `maven` (server, model-api), `npm` (web), `cargo` (runner), and `docker` for `runner/images/*`; `.mvn/wrapper/maven-wrapper.properties`: `distributionSha256Sum` (L30).
- `deploy/release/assets.sh` and `release.yml`: sign `SHA256SUMS` with cosign in the `sign` job (`cosign sign-blob`), publish the signature beside it; `deploy/compose/auto-deploy/tinfirm-auto-deploy.sh` and the Ansible role verify it with `cosign.pub` before installing; the unit gets `NoNewPrivileges`, `ProtectSystem=full`, `PrivateTmp` (M13). For `rolling`, a CI-held key in the `rolling` environment (`main` only).
- `deploy/helm/tinfirm/values.yaml`: `trustedProxies` default narrowed to the documented ingress CIDR with a NOTES warning; `networkPolicy.ingress.controller` required when `ingress.enabled` (L29).
- Tests: `actionlint`, `hadolint`, `shellcheck` as today; a dry run of a release on a feature branch that must fail at the environment gate.
- Docs: ADR-0081 consequences, threat-model 6.3, R53 narrowed; `docs/operations/auto-deploy.md`.

The reusable workflows (`reusable-release.yml`, `reusable-security.yml`, `reusable-hotfix-branch.yml`):
- Every action pinned by SHA, the scanner images by digest, and an `environment` input for their release jobs (M18, H3).

M11, M12, L28 and L31 were withdrawn.

### WP12. Documents: the threat model, the conventions, the ADRs

Closes **I10, the §7 list of the review, D3's and I5's accepted-risk rows, the §8.4 verdicts, the §8.5 omissions**. Area: `docs`. Size: **M**.

- `docs/threat-model.md`: a new §2.4 "Positions" with the table of §8.1 of the review and Q1's one-operator rule as the first sentence ("the companies of one instance are declared by one operator who vouches for their admins; an instance never hosts companies that do not trust each other"); the lifetimes table of every credential (§8.5); the reveal named as the invariant (§8.5) with the list it checks; the rows the review's §7 and each package name; R3, R7, R41, R53 restated, R38 retired, 9.11 extended, R24 and R28 with the new defaults, new rows for D3 (Q5), I5, and the cross-company items Q1 accepts (adoption by SCIM and by email, the existence answer, SAML entity IDs where `_ENTITY_ID` is unset); the "Built" markers of 2.5, 5.12, 6.3 corrected.
- `docs/CONVENTIONS.md`: §5 (tokens), §6 (the matrix rows of WP2), §7 (the startup check), §14.4 (endpoint origins), §14.10 (the limits), §15.7 (the reveal gate), §15.15 and §15.23 (reassignment, open triggers, the flag), §15.24 (I3). The wording is proposed in §5 below.
- `docs/PLATFORM.md`: §2 principle 1 gains the clause "the definition is accepted by the people it acts for"; §4 ADMIN; §6 (instruction changes); §7.2.
- ADRs: `0102-companies-on-one-instance.md` (Q1), `0103-shared-agent-changes-need-acceptance.md` (Q4), `0104-tools-as-data-sinks.md` (Q5), `0105-the-reveal-is-the-gate.md` (WP3).

## 3. Order

Three rounds. Each package is a pull request against `main` that passes the gate; the rounds exist because the first one removes the findings an attacker would use first and the third changes behaviour people see.

| Round | Packages | Why first | Size |
|---|---|---|---|
| 0 | §6's settings that need no code (GitHub secrets in environments, release jobs on runners apart from pull-request jobs, tag rulesets) | The Highs outside the product close with settings; §1's decisions are taken | The settings an afternoon |
| 1 | WP1, WP2, WP3, WP5, WP9 (M10 and L1 first), WP11's CI parts | H1, H2, H4, M1, M2, M3, M6, M7, M10, M16, M18 and the branch-reachable secrets | About two weeks: WP1 and WP3 in parallel with WP2; WP5 and WP9 beside them |
| 2 | WP6, WP7, WP8, WP10, WP11's deployment and auto-deploy parts | The flood controls, the host's defaults, the outbound rule, the database assertion, the deployment | About two weeks: WP7 is the long one; WP6 and WP8 in parallel; WP10 after WP1 (shared files in `identity`) |
| 3 | WP4, WP12, Q5's follow-up if the row is not enough | Behaviour people see (acceptance of instruction changes) and the documents that record the whole round | A week |

Dependencies: WP2 before WP12's §15.15 wording; WP3 before WP6 (the gate's refusal codes); WP1 before WP10 (both edit `identity`); WP7's Ansible change after its runner changes are released (the role installs a release).

## 4. Every finding, by package

| Package | Findings |
|---|---|
| WP1 | H1, H4, M16, M17, L5, L32, I10, D4 (with Q1 and WP12's actor row) |
| WP2 | H2, M7, D2 |
| WP3 | M2, M6, L22, L23, I4, D5, D6 |
| WP4 | D1, I5 |
| WP5 | M1, M3, D8, I8 (relay token default) |
| WP6 | M4, L24, I7 (server), I8 (bundle endpoints) |
| WP7 | M5, M15, L25, L26, L27, I6, I7 (host), I8 (host items), D7 |
| WP8 | M8, M9, L3, L6, L7, L8, L9, L10, L11, L12, L13, L14, L15 |
| WP9 | M10, L1, L4, I9 |
| WP10 | M14, L2, L16, L17, L18, L19, L20, L21, L33, L34, L35, I1, I2, I3, D9 |
| WP11 | H3, M13, M18, L29, L30 (M11, M12, L28 and L31 withdrawn) |
| WP12 | the review's §7 and §8.4, §8.5; D3 (Q5's row), I5's row, I10 |
| Outside the code (§6) | H3's and M18's settings, L29's verification |

Not planned, on purpose: D3's full `ASK` mechanism (Q5 decides whether the row is enough); the GitHub App for installation tokens (Q11, A10), which D7 recommends scheduling but which is a feature of its own; the SIEM stream and the audit anchor (Q6 of the threat model).

## 5. Proposed wording

- **CONVENTIONS §15.7, the reveal.** "A secret leaves the vault for a use only through `ConversationBindings.use`, which checks, in this order: the company's runs are not stopped; the conversation's trigger and agent are not paused; the system is not paused and, for a personal server, personal servers are on; the acting principal and the holder are `ACTIVE`; the grant and the member's group membership, or the use right and the lending, still exist; the credential is `OK`, enabled and the same identity; for a run placed on a personal host, the holder of every git account is the run's payer. Every other check of these conditions is an early exit, never the control."
- **CONVENTIONS §15.15, reassignment.** "A trigger is reassigned only while paused `AUTHOR_LEFT` or `NOT_ENTITLED`, by OWNER or ADMIN, to an active MEMBER who may use the agent; it stays paused `TO_ACCEPT` until the new author accepts its rows, payer and open-events acknowledgement, which resumes it. An admin's edit on the author's behalf may not add a row that names the author's own account or the author's own MCP servers."
- **CONVENTIONS §15.23, open triggers.** "A trigger on a source anyone may write to writes, with the author's own account or a lent credential alike, only after its author acknowledged the open events. The first trigger that exposes a credential to such a source puts an item in the inbox of whoever lent it; the credential's page and the service account's page list the open triggers that use it."
- **ADR-0102, companies on one instance (Q1).** "The companies of one instance are declared by one operator who vouches for all their admins. An instance never hosts companies that do not trust each other; such companies each get an instance of their own. Consequences: a user is one row of the instance; an admin of any company may add any user by email and learns whether an address has an account; SCIM adopts users by email; a SAML provider's entity ID is pinned by `_ENTITY_ID` or by its first metadata, as hardening, not as a boundary."
- **CONVENTIONS §5, tokens.** "A personal access token expires 90 days after creation unless renewed, names the companies it reaches (all when none), and is revoked with the user's OAuth grants on a password change, an admin's reset, and on leaving a company it names."
- **CONVENTIONS §7, the role.** "At startup the server verifies that its database role is not a superuser, has no `BYPASSRLS`, owns no relation and is not a member of `tinfirm_owner`, and does not become ready otherwise. A schema test asserts forced RLS and a policy on every table with `company_id`, except the listed instance-level tables."
- **CONVENTIONS §14.4.** "The authorization, token, revocation and registration endpoints an MCP server's metadata names must share the issuer's origin, except the listed split-hosted providers. A change of the token or revocation endpoint removes the system's OAuth accounts and credentials."
- **CONVENTIONS §14.10.** "A `run.event` payload is at most 256 KiB and a text chunk at most 32 KiB; a run's events are at most 64 MiB; at most 4 `TOOL` requests of a run are in flight; the server rate-limits `run.request` and `run.event` per host and per company."
- **PLATFORM §2, principle 1.** Add: "and the definition an agent acts by is accepted by the people it acts for: a change that lets it do more, instructions included where the ceiling can write or reach the network, waits for their acceptance."

## 6. Outside the code

An ordered checklist for the owner and the operator; none of it is a pull request in the product. Updated with what the packages left after the round: [the record's §4](2026-10-27-security-review-fixed.md#4-settings-and-steps-outside-the-code).

1. GitHub: `RELEASE_APP_*` and `OPENAI_KEY` as secrets of environments (`release` with reviewers and deployment branches `main` and `hotfix/**`); the release jobs on GitHub-hosted runners, or on a runner group of their own whose runners run no pull-request job, as `release.yml`'s header says; tag rulesets on `rolling` and `v*`.
2. Cosign: the key in the `release` environment, where only the release jobs read it, or keyless signing.
3. Ingress (L29): the ingress controller's Service with PROXY protocol or `externalTrafficPolicy: Local`, then `trustedProxies` narrowed.
4. Runner hosts: re-provision every company host with the new role defaults (egress proxy, bridge, hardening, checksums).
5. After WP1: on an instance with several companies, review `app_user` rows with `auth_source = SCIM` and no sign-in for memberships in more than one company; confirm that every company of every instance shares one operator (Q1), and move any that does not to its own instance.
6. After WP10: rotate the dev-looking secrets of any installation that still has them, and tell operators that personal access tokens now expire.

## 7. Validation and the record

The round's record, with what ran, what its checks did not cover and what stays open: [2026-10-27-security-review-fixed.md](2026-10-27-security-review-fixed.md), §2 and [§5](2026-10-27-security-review-fixed.md#5-open-after-this-round).

- Each pull request runs the gate (CONTRIBUTING §5): the server's unit and Testcontainers suites, the model port's, the web app's lint and tests, the runner's `clippy` and tests, the Helm and deploy lints, the security scan. The runner's container tests and the Playwright suite run before the round closes.
- Each package's pull request description names the findings it closes and the threat-model rows it changed; PR Agent's findings are answered as CONTRIBUTING §6 says.
- When the round closes, a record like [2026-10-27-review-findings-fixed.md](2026-10-27-review-findings-fixed.md) lists every finding and what changed, and the review points to it.
- Two checks after the round, by someone who did not write the fixes: H1's and H4's tests run against a two-company instance; a release dispatched from a feature branch fails at the environment gate and a pull-request workflow cannot read the App keys.
