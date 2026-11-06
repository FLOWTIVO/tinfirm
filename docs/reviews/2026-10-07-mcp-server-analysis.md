# Analysis: Tinfirm as an MCP server for external clients

Status: analysis, with the owner's decisions of 2026-10-07 in §7 · 2026-10-07 · Owner: J. Balcar

The request: a **Tinfirm MCP server**, so that an MCP client on a person's machine, Claude Code first, can control a Tinfirm instance: read the inbox, decide decisions, talk to specialists, follow runs, run triggers. This document says what exists, what is missing, the choices, and the build order. §7 records what the owner decided on 2026-10-07; ADR-0045 is written from it.

Direction matters. [ADR-0040](../adr/0040-mcp-tool-connections.md) made Tinfirm an MCP **client** of the company's servers, for agents in sandboxes. This is the opposite: Tinfirm as an MCP **server**, for people's tools outside. The runner host already serves an MCP server to the sandboxed agent (`runner/crates/tinfirm-runner-host/src/mcp.rs`), but that one is per run, behind a per-run secret, and offers the platform tools of a run. Neither is reusable as the thing asked for, though both are useful models.

## 1. What exists

| Area | State | Where |
|---|---|---|
| A plan | [ADR-0001](../adr/0001-java-spring-server.md) lists "the platform MCP server and the MCP proxy (`mcp`)" among the server's parts and decides "MCP uses the Spring AI MCP server on the official Java MCP SDK, over Streamable HTTP". Nothing of it was built; the v1 design meant it for agents, not for people's clients. | ADR-0001 |
| Authentication of people | Browser session only: the `TINFIRM_SESSION` cookie, CSRF, local login or OIDC ([ADR-0008](../adr/0008-bff-session-auth.md)). The spec's one security scheme is the cookie. **No personal or machine token for the API exists.** | `identity/internal/SecurityConfiguration.java`, `api/openapi.yaml` |
| A bearer-token precedent | Runner hosts: `tfr_` + 43 base64url characters, only `hex(sha256(token))` stored, shown once, parsed from `Authorization: Bearer`, on a separate stateless filter chain for `/api/runner/v1/**` without CSRF. | `runner/internal/RunnerTokens.java`, `RunnerWebSocketConfiguration.java:45-58` |
| Authorization | `CompanyAccessGuard.require(companyId, permission)` in every company service: 404 for a non-member, 403 for a role without the permission, break-glass for instance admins audited. It needs an `ActorAuthentication` (a user) and a transaction, and it opens the tenant context that row-level security reads. | `shared/access/`, `company/internal/CompanyAccessGuardImpl.java` |
| Audit | Hash-chained per company and instance; actor types `USER`, `SYSTEM`, `AGENT` only (a `CHECK` constraint); `details` holds ids. | `audit/`, `V001__baseline.sql:186,196` |
| The operations a client would want | All exist as REST, under `/api/companies/{companyId}/…`: inbox, work items, comments, conversations, threads, jobs (cancel, retry, end), decisions (decide), runs (get, events, SSE stream, cancel), agents, connections, triggers (run now), poll now. About 92 operations in 17 tags. | `api/openapi.yaml`, PLATFORM.md §11 |
| MCP code in Java | A hand-written Streamable HTTP **client** (JSON-RPC over `java.net.http`, Jackson 3, SSE parsing): `initialize`, `tools/list`, `tools/call`, session id, `DELETE`. No SDK, no Spring AI. | `connection/internal/mcp/McpClient.java` (383 lines) |
| MCP code in Rust | A hand-written POST-only MCP **server** for the sandboxed agent: `initialize`, `ping`, `tools/list`, `tools/call`; no sessions, no SSE; refuses browser origins. | `runner/.../mcp.rs` (3238 lines) |
| Tool descriptions and results in MCP shape | `RunTools` builds names, descriptions and JSON Schemas of server-executed tools; `ToolResults` renders results as `{content, structuredContent, isError}` with a size cap. | `run/internal/RunTools.java`, `ToolResults.java` |
| Stack | Spring Boot 4.1.1, Java 25, Spring MVC on servlets (no WebFlux), Jackson 3.1.7 (`tools.jackson`), Spring Modulith 2.1.1, Tomcat 11. | `server/pom.xml` |
| Module rules | One Modulith module per top-level package, lower never depends on higher; `ModularityTest` names the exact module set (13 today), so a new module is added there and in CONVENTIONS §10. | `ModularityTest.java` |
| Contract rules | OpenAPI-first ([ADR-0013](../adr/0013-openapi-first-contract.md)): controller interfaces are generated from the spec; the tenant isolation test calls every `{companyId}` path as a stranger and expects 404. | CONVENTIONS §13, `TenantIsolationTest.java` |

**What is missing, in one line:** a way for a client outside the browser to authenticate, and an MCP endpoint that turns the existing services into tools. Everything below the endpoint (permissions, tenancy, audit, limits) exists and must be reused, never bypassed.

## 2. What the client side can do (verified 2026-10-07)

Claude Code connects to remote MCP servers over **Streamable HTTP** (`claude mcp add --transport http <name> <url>`), with either a static header (`--header "Authorization: Bearer …"`, or `${TINFIRM_TOKEN}` expanded from the environment in `.mcp.json`), a `headersHelper` script that mints headers, or **OAuth 2.1** (the `/mcp` command opens the browser; dynamic client registration or a pre-registered client id). SSE as a transport is deprecated; stdio is for local processes. It reads tools, resources and prompts. Tool results above about 25,000 tokens are cut unless the tool declares `anthropic/maxResultSizeChars`; results above 50,000 characters are written to disk instead of the context. Mutating tools are gated by Claude Code's own permission prompt per tool, which the server can only inform through tool annotations (`readOnlyHint`, `destructiveHint`).

The MCP specification (2026-07-28) makes authorization optional. When an HTTP server has it, the spec's way is OAuth 2.1: the server answers 401 with `WWW-Authenticate` naming its protected-resource metadata (RFC 9728), and the client discovers the authorization server. A pre-shared bearer token is outside the spec but is what Claude Code's `--header` and most "internal" servers do; the spec's security rules still apply (token in the header, never in the URL; the server validates every request).

Libraries, server side, in Java:

| Option | What it gives | Fit |
|---|---|---|
| The official MCP Java SDK, `io.modelcontextprotocol.sdk:mcp-core` (2.0.1) + `mcp-json-jackson3` | `McpServer.sync(...)` with tool, resource and prompt specifications; servlet transports `HttpServletStreamableServerTransportProvider` (sessions, SSE, keep-alive, `contextExtractor` that reads the `HttpServletRequest`) and `HttpServletStatelessServerTransport`; Java 17+; a Jackson 3 module | Good: a servlet registered on Tomcat, no WebFlux, Jackson 3 matches. Spring integrations moved to Spring AI, so the SDK alone brings no Spring magic and no Spring AI. |
| Spring AI `spring-ai-starter-mcp-server-webmvc` (2.0.x) | `@McpTool` annotations, auto-configuration, `spring.ai.mcp.server.protocol=STREAMABLE|STATELESS`; the docs say the endpoint is unauthenticated by design and must sit behind Spring Security | **Chosen (§7).** Spring AI 2.0 is built for Spring Boot 4.0 and 4.1 and Jackson 3; the MCP Security library below pins Boot 4.1.0 with Spring AI 2.0.0, so the combination is exercised upstream. ADR-0001 named it. |
| Hand-written, as `McpClient` and `mcp.rs` are | A POST-only JSON-RPC handler: `initialize`, `ping`, `tools/list`, `tools/call`, about 400 lines, no dependency | Matches the repository's habit and the supply-chain posture (Trivy, Dependabot on a small tree). Costs spec drift, no sessions, no SSE, no resources or prompts unless written. |

## 3. The design questions

### 3.1 Who is the caller?

The MCP server must act **as a member**, with that member's role in that company, through `CompanyAccessGuard` and row-level security, so that nothing a client can do differs from what the person could do in the web app. That rules out a company-wide "MCP key" with its own powers, and it rules out any path around the services.

What is missing is a credential a person's client can present. The MCP spec's model: the MCP endpoint is an **OAuth 2.1 resource server**; an **authorization server** issues tokens for it; the client finds the authorization server through the endpoint's protected-resource metadata (RFC 9728) and gets a token with the person in the browser. Tinfirm has two ways to be on that model, and a third credential beside it.

**Tinfirm as the authorization server (decided, §7).** Tinfirm issues the tokens. The authorization page needs a Tinfirm session, which comes from the login Tinfirm already has: a local account, or an OIDC provider. So a company's SSO applies to MCP without any OAuth work per provider, and a company without a provider uses local accounts; both land on the same consent page. The flow a person sees: `claude mcp add --transport http tinfirm https://tinfirm.example/api/companies/{id}/mcp`; the first use answers 401 and Claude Code opens the browser; the company's login (or Tinfirm's); a consent page "Claude Code asks for read, write and decide access to company X"; Claude Code keeps a short-lived access token and a refresh token in the keychain and refreshes on 401. Headless machines print the URL once (`--no-browser`).

The pieces, verified in source on 2026-10-07:
- **Spring Security 7** holds the former Spring Authorization Server, so Spring Boot 4.1.1 already carries it: PKCE, refresh tokens, revocation, the JWK set endpoint, RFC 8414 metadata, `JdbcRegisteredClientRepository` and `JdbcOAuth2AuthorizationService`, open client registration (`openRegistrationAllowed`), and on the resource-server side RFC 9728 protected-resource metadata.
- **MCP Security for Spring AI** (`org.springaicommunity`, 0.1.14 of 2026-08-06, Apache 2.0, built against Boot 4.1.0 and Spring AI 2.0.0): a thin layer on the above for the MCP spec. It is a **community project**, incubated by a Spring Security engineer and linked from the Spring AI manual as "MCP Security (WIP)", which says it is "not officially endorsed yet by Spring AI or the MCP project" and that its APIs may change; there is no official Spring project for MCP security (checked 2026-10-07). Authorization server: `McpAuthorizationServerConfigurer` (open DCR on or off, `cimd(true)`), `ClientIdMetadataDocumentRegisteredClientRepository` with `DefaultClientIdMetadataDocumentResolver` and validators, `LocalhostWildcardPortValidator` (Claude Code's `http://localhost:<random port>/callback`), `ResourceIdentifierAudienceTokenCustomizer` (the `resource` parameter becomes the JWT's `aud`), `DelegatingRegisteredClientRepository`. Resource server: `McpServerOAuth2Configurer` (`resourcePath`, `validateAudienceClaim`, origin and host validation, session binding), `BearerResourceMetadataTokenAuthenticationEntryPoint` (the 401 names the metadata URL), `AudienceValidationJwtDecoder`; `McpApiKeyConfigurer` for API keys. Its Boot auto-configurations assume they own the filter chains (`@ConditionalOnDefaultWebSecurity`); Tinfirm has chains, so it uses the configurers by hand. Known limits: WebMVC only, JWT only, every client may name every resource.
- **Claude Code** as a client: tries pre-registered credentials, then Client ID Metadata Documents, then dynamic registration; its metadata document is `https://claude.ai/oauth/claude-code-client-metadata` with port-less loopback redirect URIs; requests the scopes the 401 advertises; re-authorizes on `insufficient_scope`; refreshes on 401; `claude mcp login` and `--no-browser`.

**Client registration (decided: CIMD plus pre-registration, no open DCR).** The authorization server must know a client's redirect URIs; that check is the one that keeps the authorization code from a stranger. With **CIMD** the `client_id` is an https URL to the client's own metadata document; Tinfirm fetches it when it sees a URL-shaped client id, checks that the document's `client_id` equals the URL, caches it by its HTTP headers, matches the redirect URI ignoring the loopback port, and shows the client's domain on the consent page. The fetch is an outbound request to a URL chosen by the client, so it goes through the one outbound client and its guard (https only, no redirects, no private addresses), as a connection's base URL does. **Pre-registration** is a fixed list of built-in public clients for those that cannot do CIMD, passed with `--client-id`. **Open dynamic registration** (a public, unauthenticated `POST /oauth2/register`) stays off; the library has it, and it can be switched on later behind a rate limit, a cap and a sweep if a client needs it. The spec deprecates it in favour of CIMD.

**Scopes (decided: three).** `tinfirm:read`, `tinfirm:write`, `tinfirm:decide`. The endpoint's 401 advertises `tinfirm:read` only; a write tool without `tinfirm:write`, or `decide` without `tinfirm:decide`, answers 403 `insufficient_scope` naming the scope, and Claude Code asks the person to re-authorize with it. The consent page shows what is granted, which is the separation-of-duties control of 3.6, made explicit to the person. A scope never adds to the member's role: the guard still decides.

**Tokens.** JWTs signed with an RSA key generated once and kept as an instance secret in the vault (decided), with the JWK set endpoint for the resource server; access tokens of about an hour, refresh tokens with rotation, both revocable; `aud` is the company endpoint, so a token for company A is refused at company B. Spring's three tables (`oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`) come by Flyway as instance-level tables, since a client and a grant are a person's, not a company's; the grant records the company it was consented for. Persistence in PostgreSQL only (ADR-0003).

**Personal access tokens (decided: also).** Beside OAuth, a user creates a token on `/me` (name, scope among the same three, optional expiry), sees it once (`tfp_` + 43 characters), and the server keeps `sha256`, `last_used_at` and revocation, as `RunnerTokens` does for hosts. It is for scripts, `curl` and CI, where a browser consent does not fit. On the MCP endpoint both arrive as `Authorization: Bearer`: a `tfp_` token is resolved by its format and a JWT by the decoder, in one filter chain. Audited: `token.created`, `token.revoked`; every audited action done through either credential carries the grant or token id in `details`.

**What this costs beyond the endpoint itself.** The signing key and its rotation; the three tables and the `api_token` table; a consent page (Spring's default is server-rendered HTML; under the strict CSP it is an Angular route that POSTs to `/oauth2/authorize` with CSRF); the login round trip, since the authorization request must survive the SPA login and the OIDC redirect, which both land on `/` today (a `RequestCache` plus a `returnTo` in the SPA: a small change to ADR-0008's flow); a "Connected apps and tokens" section on `/me` to revoke grants and tokens; audit actions for consent, revocation and token use; the endpoints `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/oauth2/revoke`, `/.well-known/oauth-authorization-server` and `/.well-known/oauth-protected-resource/...` in CONVENTIONS §4's reserved paths (the OIDC client's `/oauth2/authorization/{id}` and `/login/oauth2/**` are distinct and stay).

### 3.2 Which company?

A user may belong to several companies; the guard decides per company. Two shapes for the MCP endpoint:

- **One endpoint per company**: `POST /api/companies/{companyId}/mcp`. The client names the company in the URL; every tool is implicitly in that company; the canonical resource URI of the MCP spec is per company, which suits OAuth audiences later. The path is not in `openapi.yaml` (it is JSON-RPC), so `TenantIsolationTest` does not pick it up: the module needs its own test that calls every tool as a member of company A against company B and gets the 404-shaped error. A person in two companies adds two servers to Claude Code.
- **One endpoint for the user**: `POST /api/mcp`, and every tool takes `companyId`, or a `use_company` tool sets it for the session. Fewer URLs, noisier tools, state per session (which the stateless transport cannot hold).

Recommendation: per company. It is the shape of the whole API and of the tests.

### 3.3 Transport and sessions

The SDK offers sessions (`Mcp-Session-Id`, a GET stream for server-to-client messages, keep-alive) or a stateless mode (each POST stands alone, no server push). Server push would let Tinfirm notify a client of a new decision or a finished turn, but Claude Code acts on notifications only in narrow ways, and sessions are in-process state, the thing PLATFORM.md §14 already lists as a one-replica limitation (R29).

Recommendation: **stateless** first. A client asks (`get_inbox`, `get_job`); nothing waits on the server beyond one request. Sessions and notifications are a later package, when the replica question is answered for SSE and runner connections too.

### 3.4 Where in the server

A new Modulith module **`mcp`**, at the top beside `sync` (nothing depends on it), depending on `work`, `agent`, `connection`, `run`, `company`, `identity` (the token principal) and `audit`. It holds the servlet (or handler), the tool catalogue, and the mapping from tool arguments to the services' inputs and from their outputs to results. It owns no table. The token itself belongs to **`identity`** (users own tokens; the filter chain lives next to the session one), so that `identity` stays the one module that says who the caller is.

Rules that follow from CONVENTIONS: the module joins the list in `ModularityTest` and §10; the path `/api/companies/{companyId}/mcp` is under `/api/**`, so the SPA fallback does not catch it; its filter chain is stateless and has no CSRF, like the runner chain, and it must **ignore the session cookie** entirely (a browser must not be able to call it, so no cookie-based principal, and requests with an `Origin` header are refused, as `mcp.rs` does); the token endpoints go into `api/openapi.yaml` under a `Tokens` tag; the MCP endpoint itself is JSON-RPC, not OpenAPI, and is documented in an operations guide and named in the spec's description; a new `TINFIRM_MCP_*` variable, if any, goes into §4; new problem codes into §14.2, new audit actions into §14.3; the threat model gains an asset, a trust boundary and a threats table in the same PR.

### 3.5 The tools

A tool is not an endpoint. The REST API has 92 operations; a good MCP surface has about twenty tools with descriptions a model can act on, built around what a person does: look at the inbox, decide, talk to a specialist, follow a run, poke a connection. Every tool calls the service the controller calls, with the same inputs, so the permission matrix, the usage policy, the one-active-run rule, the size limits and the audit apply unchanged.

A first catalogue, scope `tinfirm:read`:

| Tool | Does | Service |
|---|---|---|
| `get_inbox` | What needs a person now: open decisions, failed jobs, work waiting on a runner | `getInbox` |
| `list_work_items` | Filters: connection, kind, agent, waiting on, state, a search | `listWorkItems` |
| `get_work_item` | By id or **by key** (`PROJ-123`, `TF-7`): brief, threads, decisions, artifacts, attachments | `getWorkItem` (+ a key lookup) |
| `list_comments` | The conversation of a work item, oldest first, paged | `listComments` |
| `get_job` | A thread with its turns, their results and decisions | `getJob` |
| `list_jobs` | Jobs of the company or of a work item, filters | `listJobs`, `listWorkItemJobs` |
| `get_decision`, `list_decisions` | One, or the open ones longest waiting first | `getDecision`, `listDecisions` |
| `get_run` | A turn: status, queue reason, workspace, result | `getRun` |
| `get_run_transcript` | Events after a sequence number, rendered as text, capped (Claude Code's limit) | `listRunEvents` |
| `list_agents`, `get_agent` | The specialists, with `usable` for the caller | `listAgents`, `getAgent` |
| `list_connections`, `list_triggers` | Connections and their triggers with next firings | `listConnections`, `listTriggers` |

Scope `tinfirm:write` adds (and `decide` needs `tinfirm:decide`):

| Tool | Does | Service | Note |
|---|---|---|---|
| `start_conversation` | Talk to an agent: a new `CONVERSATION` work item and its first turn | `startConversation` | Usage policy applies (`AGENT_NOT_USABLE`) |
| `start_thread` | A new thread of an agent on an open work item | `startThread` | |
| `post_comment` | Reply in a thread (`jobId`), mention agents, or answer open questions | `postComment` | One parser for mentions, as in the web app |
| `decide` | Answer, approve or deny, accept or return | `decide` | See 3.6 |
| `cancel_job`, `retry_job`, `end_thread`, `close_work_item` | Thread and work item control | the same | `destructiveHint` on end and close |
| `cancel_run` | Cancel a queued or running turn | `cancelRun` | |
| `run_trigger`, `poll_connection` | Run a trigger now; poll now | `runTrigger`, `pollConnection` | `RUN_TRIGGERS` permission |

Not in the first version: creating or editing agents, connections, triggers, members, secrets, runner hosts, model credentials, and everything under `/api/admin/**`. Those are owner and admin work with real blast radius; they come after the controls of 3.6 have been lived with. Uploading attachments needs the upload scan path and a file in the client; later.

Shape of a result: `structuredContent` with the DTO the REST API returns (the same JSON the web app sees, so the two never disagree) and a short text rendering for the model, as `ToolResults` does for agents. Cursor pagination is passed through as `cursor` and `nextCursor`. Annotations: `readOnlyHint` on every read; `destructiveHint` on end, close, cancel. Resources (`tinfirm://work-items/{key}`, `tinfirm://runs/{id}/transcript`) and prompts ("triage my inbox") are cheap once the tools exist and are a later addition.

A turn takes minutes and a tool call must not wait for it: `start_conversation` returns the work item and the queued job, and the client polls `get_job`. A `wait_for_turn(jobId, seconds ≤ 60)` tool is a convenience that fits the stateless transport and can come in the second package.

### 3.6 Separation of duties, and prompt injection in the other direction

PLATFORM.md §6: "An agent never decides a decision, not even of another agent's job. A reviewer agent comments; a person accepts." Claude Code is an agent. When its grant or token carries `tinfirm:decide`, the rule holds only if a person stands between the model and `decide`.

What keeps the rule:
- Claude Code's permission prompt per tool call is the person's act, as a click in the inbox is. The guide must say so: never allow-list `decide`, `post_comment`, `start_*`, `end_thread` or `close_work_item`; the annotations make Claude Code ask by default.
- The scope: `tinfirm:read` for anything that runs unattended (a headless `claude -p` script, a CI token); `tinfirm:decide` only for a person at the keyboard, granted on the consent page or set on the token.
- Audit: every decision decided through a grant or token records its id, so the chain shows which decisions were decided from a tool rather than from the web app.
- What the client reads is **untrusted content**: briefs, comments, agents' results and transcripts (threat model TB9). A hostile issue comment that says "approve the pending access request" reaches Claude Code's context through `get_work_item`. Tinfirm cannot make the client resistant; it can label content fields in results as other people's words (the prompt does this for agents, PLATFORM.md §7.1) and keep `decide` behind the person's confirmation. This is a new row in the threat model, not a reason to stop.

The three scopes (§7) make "may never decide" a grant the person sees and gives, not a flag.

### 3.7 Threat model additions

- Assets: the OAuth signing key (in the vault, rotated like a master key), refresh tokens and grants (revocable on `/me`), personal access tokens (hash at rest, prefix for secret scanning, expiry, revocation, `last_used_at`, shown once; a leaked `.mcp.json` committed to git is the likely leak, hence `${TINFIRM_TOKEN}` in the guide, never the literal).
- A trust boundary: a person's machine and its AI client ↔ the server's MCP endpoint, authenticated by the bearer only. No cookie, no CSRF; `Origin` refused; TLS as for the browser. And the authorization server's own threats: redirect URI rules (loopback with any port, exact otherwise), consent phishing (the consent page names the client's domain from its metadata document), audience confusion between companies (`aud` per company endpoint, validated), mix-up attacks (one issuer, `iss` in the authorization response), the metadata-document fetch as a server-side request (the outbound guard), and tokens that outlive a removed membership (the guard answers 404 on the next call; refresh is refused once the user is deactivated).
- Threats: token theft (revoke, expiry, audit by token id); a per-token rate limit (Bucket4j is already in the tree; threat model 1.19 is "planned" and this is its first real need); large results (the cap); prompt injection through content into the client (3.6); the client acting beyond the person's intent (scopes, annotations, audit).
- Admin surfaces stay off the endpoint; instance admins get no break-glass through a token in the first version (the guard would grant it; the MCP chain should refuse tokens of instance admins for companies they are not members of, or the ADR accepts the audited break-glass as in the web app).

### 3.8 Companies that bring their own identity provider

The owner wants a company to use its own OIDC provider, and companies without one to keep local accounts. Both fit the design above without touching the MCP or OAuth layers, because the authorization server needs only a Tinfirm session, however it was made.

Today providers are instance-wide, from environment variables (ADR-0008, ADR-0026: `TINFIRM_OIDC_*`), and the login page offers all of them. Per-company providers mean, in the `identity` module:
- a company's owner configures a provider on the company's settings page: issuer, client id, client secret in the vault, the email-trust rule of ADR-0026, auto-provisioning on or off; audited;
- Spring Security's `ClientRegistrationRepository` reads registrations from the database as well as from the environment, with ids like `company-{id}`, revealing the secret from the vault on use; the redirect URI pattern `/login/oauth2/code/{registrationId}` stays;
- the login page routes a person to their company's provider: a "sign in with your company" entry that asks for the company (or the email domain, when the company registered its domains), beside the instance providers and the local form;
- a user is still one user, identified by `(issuer, subject)`; a provider creates users only when its company allows it, and a new user from company A's provider is a member of company A only (the provisioning gives the membership the company configured, VIEWER by default), never of another company;
- instance-wide providers remain, so nothing changes for an existing instance.

Threat-model rows of its own: a company that controls its provider controls who it creates and must not reach another company (membership comes from the provider's company only; email is never a link, as ADR-0008 says); a provider's secret is a connection-like asset in the vault; a removed provider logs nobody out of their sessions but ends logins. Decided (§7): this is part of ADR-0045 and the same programme, as its own package.

## 4. Alternatives to building it in the server

| Alternative | Verdict |
|---|---|
| **A stdio MCP server beside the client** (a Node or Rust package that calls the REST API with a token) | Needs the same token work in the server, then duplicates the API contract in a second codebase per release. Right for products whose server cannot change; wrong here, where the server is ours and the services are one call away. Keep as a possibility for air-gapped clients. |
| **An OpenAPI-to-MCP bridge** (a generic server that turns `openapi.yaml` into 92 tools) | A one-afternoon demo once tokens exist, and a poor product: tool names are operation ids, descriptions are summaries, results are raw DTOs, nothing is capped, and every tool is "write" to the client's permission prompt. Useful to try the token package; not what to ship. |
| **A `tinfirm` CLI** (Claude Code uses it through Bash) | Complements rather than replaces MCP: the same token, the same API, good for scripts and CI, no permission prompt per call. Later, if people ask. |
| **Claude Code inside a sandbox as a runtime** (the agent is already a Tinfirm runtime) | Different thing: that is Tinfirm driving Claude; the request is Claude driving Tinfirm. |

## 5. Build order

ADR-0045 first, then packages, each with its tests and its documentation (CONVENTIONS §4, §5, §6 and §14; PLATFORM.md §4, §11 and §14; the threat model in the same PR; an operations guide; the README's status table; the restart log).

1. **ADR-0045**: "Tinfirm as an MCP server for people's clients: OAuth 2.1 with Tinfirm as the authorization server, per-company endpoints, personal tokens, and companies' own identity providers." It records §7.
2. **Package A, the authorization server** (`identity`): the Spring Security 7 authorization server with MCP Security's configurers by hand (CIMD on, open DCR off, the built-in client list, `LocalhostWildcardPortValidator`, the audience customizer); the signing key as an instance secret in the vault (`JWKSource` from it) and its rotation story; migration `V008__oauth.sql` with Spring's three tables; the consent page in the web app and the login round trip (`RequestCache`, `returnTo`); the three scopes; revocation; "Connected apps" on `/me`; audit actions `oauth.consent.granted`, `oauth.grant.revoked`, `oauth.client.resolved` (CIMD, with the domain); the reserved paths; threat-model rows. Tests: the full flow with the SDK's or MCP Security's client against a running server, the loopback-port rule, the audience check across two companies, a stranger's metadata document refused by the outbound guard.
3. **Package B, personal tokens** (`identity`): `api_token` (id, user id, name, scope, `token_hash`, `expires_at`, `last_used_at`, `revoked_at`); `GET/POST /api/me/tokens`, `DELETE /api/me/tokens/{id}` in the spec under a `Tokens` tag; the bearer resolver that tells a `tfp_` token from a JWT; the Tokens section on `/me`; `token.created`, `token.revoked`. Tests: a user sees and revokes only their own; a session cookie cannot reach the MCP chain and a token cannot reach the browser API.
4. **Package C, the `mcp` module, read tools**: `spring-ai-starter-mcp-server-webmvc` and `mcp-server-security`, pinned and in Dependabot's scope; the endpoint at `/api/companies/{companyId}/mcp` on a stateless chain with `McpServerOAuth2Configurer` (resource path per company, audience validated, origin refused) and the token resolver; the `tinfirm:read` catalogue with result caps; the per-tool tenant test of 3.2; `docs/operations/mcp-clients.md` (the `claude mcp add` line, the consent flow, scopes, what never to allow-list, `${TINFIRM_TOKEN}` for tokens); `ModularityTest`.
5. **Package D, write and decide tools**: the `tinfirm:write` and `tinfirm:decide` catalogues with annotations and the 403 `insufficient_scope` challenge; grant and token ids in audit details; the per-principal rate limit (Bucket4j; threat model 1.19).
6. **Package E, companies' own identity providers** (`identity`): 3.8.

Later, behind the same design: sessions and notifications on the MCP endpoint; resources and prompts; admin tools; open dynamic registration if a client needs it; a CLI on the same tokens.

Rough size: A is about twice ADR-0040's server package (the consent page, the key handling and the login round trip are most of it); B and C each about one; D and E smaller than one. Nothing changes the deployment contract (PLATFORM.md §14): new tables, no new port, no renamed variable; new `TINFIRM_OAUTH_*` settings only if the key is not generated.

## 6. Open points

1. **Lifetimes.** Access token one hour and refresh token thirty days with rotation are the proposal; the ADR fixes them.
2. **The built-in client list.** Which clients to pre-register besides Claude Code's metadata document, and whether owners may add one on a settings page.
3. **Instance admins.** Break-glass through a grant or token, audited as in the web app, or refused on the MCP endpoint.
4. **Stale line.** `PRODUCT.md:52` still says remote MCP servers are not built; fix with the same PR.

## 7. Decisions of 2026-10-07 (owner)

| Question | Decision |
|---|---|
| Credentials | OAuth 2.1 from the first version, with Tinfirm as the authorization server over its own login; **and** personal access tokens for scripts and CI. |
| Company SSO | Companies bring their own OIDC provider (3.8), as part of ADR-0045; companies without one use local accounts; the authorization server is always Tinfirm's. |
| Scopes | Three: `tinfirm:read`, `tinfirm:write` (no decide), `tinfirm:decide`. |
| Client registration | Client ID Metadata Documents plus a pre-registered client list; no open dynamic registration. |
| Library | Spring AI's MCP server starter (WebMVC), as ADR-0001 says, with the MCP Security library (`org.springaicommunity`, community, 0.1.x) used through its manual configurers, never its auto-configuration. Confirmed with its community status in view: pinned, in Dependabot's scope, its dozen glue classes small enough to vendor later if the project stalls. The heavy parts (the authorization server, RFC 9728 metadata) are Spring Security 7's own. |
| Persistence | Spring's OAuth tables by Flyway; the signing key as an instance secret in the vault. |
| Endpoint | One per company, `/api/companies/{companyId}/mcp`, stateless Streamable HTTP (3.2, 3.3). |
