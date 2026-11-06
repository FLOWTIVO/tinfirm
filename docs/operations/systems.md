# Systems: register and run them

The public guides are [Register systems and lend their credentials](../../docs-site/content/admin-guide/systems.md) (https://docs.tinfirm.com/admin-guide/systems/) and [Receive webhooks](../../docs-site/content/admin-guide/webhooks.md) (https://docs.tinfirm.com/admin-guide/webhooks/), for a company's Owners and Admins; the variables an operator sets are in [Server configuration](../../docs-site/content/reference/configuration.md) (https://docs.tinfirm.com/reference/configuration/). This page keeps what the people who build Tinfirm need beyond them: the design and its sources, how Tinfirm calls each kind (API versions, token headers, the requests that check a token and test a system, the Sign-in app's endpoints), problem codes, audit events, the API, how polls, the webhook endpoint, the kill switch and the outbound guard work inside, and the troubleshooting that needs them.

Managing a company system, its Sign-in app, its credentials and their lending is the permission `MANAGE_SYSTEMS` (OWNER and ADMIN). Every member reads a company system; a personal MCP server is its owner's alone ([mcp-servers.md, section 4](mcp-servers.md#4-personal-mcp-servers)).

The design is [PLATFORM.md §5](../PLATFORM.md#5-systems-accounts-and-credentials) and [ADR-0058](../adr/0058-authority-from-the-audience.md); the company's credentials on systems are [ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md), MCP servers that need no account [ADR-0071](../adr/0071-mcp-servers-without-accounts.md), internal hosts [ADR-0066](../adr/0066-internal-hosts.md). The rules are [CONVENTIONS §15.2](../CONVENTIONS.md#152-systems), [§15.3](../CONVENTIONS.md#153-accounts-and-grants) and [§15.17](../CONVENTIONS.md#1517-package-5b-channel-triggers-the-chat-outbox-and-the-webhook), from the [package 1](../reviews/2026-10-12-package-1-contract.md) and [package 5](../reviews/2026-10-12-package-5-contract.md) contracts. Related guides: [accounts.md](accounts.md) (members' own accounts, service accounts, which account a conversation uses), [triggers.md](triggers.md), [chats.md](chats.md), [mcp-servers.md](mcp-servers.md), [agent-permissions.md](agent-permissions.md).

## 1. Kinds

The kinds as the app names them, their address fields and what each gives agents are in the public guide's [The kinds of system](../../docs-site/content/admin-guide/systems.md#the-kinds-of-system), their fixed tools under [Tools](../../docs-site/content/admin-guide/systems.md#tools).

- **The API's `kind`**: `JIRA_DC`, `JIRA_CLOUD`, `GITHUB`, `GITLAB`, `GIT`, `MCP`, `SLACK`, `TEAMS`, `WEBHOOK`. `BUZZ` is parked and cannot be registered (`400 SYSTEM_KIND_INVALID`).
- **`open_change_request`** is in GitHub's and GitLab's catalogues, but no agent calls it: it carries a mode on the agent, and Tinfirm opens the pull or merge request after a turn that pushed, under that mode.
- **The name** is unique in the company ignoring case (`409 DUPLICATE_SYSTEM_NAME`).
- **The handle** is made from the name at registration: accents dropped, lower case, `_` for every run of other characters, `s_` in front when the name does not start with a letter, cut to 17 characters when longer than 20, then `_2`, `_3`… while the company has it already (`ops_alerts_2` for *Ops-Alerts* beside *Ops Alerts*). It matches `^[a-z][a-z0-9_]{0,19}$` and never changes, so a rename keeps every tool's name. A `handle` sent to the API is `400 VALIDATION_FAILED`.
- **The kind and the base URL never change**, so that a token is sent only where it was connected. An MCP server's endpoint is the exception: it changes, and every account and credential on the server goes with the old one ([mcp-servers.md, section 2.3](mcp-servers.md#23-a-new-endpoint), [ADR-0079](../adr/0079-an-mcp-servers-endpoint-changes.md)).

## 2. Base URLs and tokens

The address and token each kind takes, and the permissions or scopes a token needs, are in [Addresses and tokens, per kind](../../docs-site/content/admin-guide/systems.md#addresses-and-tokens-per-kind); repositories in [Repositories](../../docs-site/content/admin-guide/systems.md#repositories). How Tinfirm calls each kind:

| Kind | API | A stored token is sent | Its check, and **Test** with an account (section 5) | **Test** without an account |
|---|---|---|---|---|
| Jira Data Center | REST API v2 | As `Authorization: Bearer` | `/rest/api/2/myself` | `serverInfo` |
| Jira Cloud | REST API v3 at the site; Atlassian's gateway after a sign-in (section 3) | As Basic authentication of `email:api-token` | `/rest/api/3/myself` | `serverInfo` |
| GitHub | REST API version `2022-11-28` | As `Authorization: Bearer` | `/user` | `/meta` |
| GitLab | `{base}/api/v4` | In the `PRIVATE-TOKEN` header | `/api/v4/user` | `/version` |
| Git | Git over https | As the host takes it for git | None: a git host has no "who am I", so the token is stored unchecked and the first clone uses it | None (`400 SYSTEM_KIND_INVALID`) |
| MCP server | MCP Streamable HTTP ([mcp-servers.md, section 1](mcp-servers.md#1-what-the-server-must-do)) | In the **Token header**, else as `Authorization: Bearer` | `initialize` | `initialize` |

- A Jira Cloud base URL with a path is `400 INVALID_BASE_URL`.
- A repository URL the system lists already is `409 DUPLICATE_REPOSITORY`. Adding and removing one is audited as `repository.added` and `repository.removed`.
- Slack's and Microsoft Teams' base URLs are fixed. Their accounts are the bot, a credential lent to a service account on the **Bot** tab, and the links of members to their chat users ([chats.md](chats.md)).

## 3. The Sign-in app

Setting it up, the callback URL, the default scopes and the app to register at each provider are in [Set up the Sign-in app](../../docs-site/content/admin-guide/systems.md#set-up-the-sign-in-app). Inside:

- The callback is `{TINFIRM_PUBLIC_URL}/api/oauth/callback`. The client secret is a managed secret in the company's vault; a change with an empty secret keeps the stored one while the client ID stays. Audited as `system.app_changed`, with `removed` when the app is removed, never with the secret. API: `PUT` and `DELETE /api/companies/{companyId}/systems/{systemId}/app`; problems `400 VALIDATION_FAILED` (a tracker's app without a client ID), `400 SYSTEM_KIND_INVALID` (Git, Microsoft Teams, a webhook), `409 OAUTH_NOT_AVAILABLE` (an MCP server that offers no sign-in, or whose authorization server names an endpoint off its issuer's origin), `409 SYSTEM_APP_ENDPOINTS_CHANGED` (an MCP server's new token or revocation endpoint while OAuth accounts or credentials are on it: confirmed with `confirmEndpointsChange`, they go; [mcp-servers.md, section 3.1](mcp-servers.md#31-sign-in-with-oauth)).
- Every sign-in carries PKCE (`S256`), expires after 10 minutes and returns to the page it started from. Tinfirm sends the client secret as each system expects it: in the request body for Atlassian and GitHub, as Basic authentication for Jira Data Center, GitLab and MCP servers. An access token from a sign-in is always sent as `Authorization: Bearer`, whatever the system's scheme for stored tokens, and refreshed before it expires.

| Kind | Authorization endpoint | Token endpoint | Revocation | Default scopes |
|---|---|---|---|---|
| Jira Data Center | `{base}/rest/oauth2/latest/authorize` | `{base}/rest/oauth2/latest/token` | None | `WRITE` |
| Jira Cloud | `https://auth.atlassian.com/authorize` (`audience=api.atlassian.com`, `prompt=consent`) | `https://auth.atlassian.com/oauth/token` | None | `read:jira-work read:jira-user write:jira-work offline_access read:me` |
| GitHub | `{web}/login/oauth/authorize` | `{web}/login/oauth/access_token` | None | `repo` |
| GitLab | `{base}/oauth/authorize` | `{base}/oauth/token` | `{base}/oauth/revoke` | `api` |
| MCP server | Discovered | Discovered | Discovered, if any | The app's, else the server's `scopes_supported` |

`{web}` is `https://github.com` for `https://api.github.com`, else the scheme and host of `https://<host>/api/v3`.

- **Who signed in** is read from `{base}/rest/api/2/myself` (Jira Data Center), `/user` (GitHub), `/api/v4/user` (GitLab), and `https://api.atlassian.com/me` (Jira Cloud).
- **Jira Cloud**: after a sign-in Tinfirm asks Atlassian which sites the grant includes (`https://api.atlassian.com/oauth/token/accessible-resources`) and takes the one whose address is the system's site, ignoring case and a trailing slash. A grant without it stores nothing and returns `site_not_granted`. Calls then go to `https://api.atlassian.com/ex/jira/{cloudId}`.
- **Revocation**: removing a credential or an account revokes its grant at GitLab and at an MCP server's authorization server that publishes a revocation endpoint. Atlassian has none, and GitHub none that Tinfirm calls.
- **An MCP server's** sign-in is found by discovery: [mcp-servers.md, section 3.1](mcp-servers.md#31-sign-in-with-oauth). **Slack's** Sign-in app is for the `history` tool only, with the fixed scopes `channels:history groups:history` ([chats.md](chats.md)).
- The server calls the authorization server itself, so with the Helm chart's NetworkPolicy it needs an egress rule ([helm.md, section 4](helm.md#4-networkpolicy)); every such call passes the outbound guard (section 9).

## 4. Credentials

Adding a credential, its states and its menu, and lending it to groups and service accounts are in [Add a credential](../../docs-site/content/admin-guide/systems.md#add-a-credential), [Keep a credential working](../../docs-site/content/admin-guide/systems.md#keep-a-credential-working) and [Lend a credential](../../docs-site/content/admin-guide/systems.md#lend-a-credential). The account model and how a conversation picks an account are in [accounts.md](accounts.md). Inside:

- **Who holds them**: a company Jira, GitHub, GitLab, Git or MCP server. A personal MCP server holds none (`400 PERSONAL_SYSTEM`: only its owner's own account acts there), nor does a webhook. A chat system's bot is a credential, marked **Chat bot** and managed only under **Bot** ([chats.md](chats.md)).
- **Problems**: `409 DUPLICATE_CREDENTIAL_NAME` (a name the system has, ignoring case), `400 ACCOUNT_REFUSED` with the system's reason (a token the "who am I" of section 2 refuses), `409 IDENTITY_IN_USE` (the user is a member's own account: [accounts.md, section 7](accounts.md#7-one-identity-per-system)), `409 SYSTEM_PAUSED`, `409 DUPLICATE_GRANT` (a credential lent to a group twice).
- **States**: **Working** is `OK`; **Sign in again** and **Store a new token** are `RECONNECT` (refused, or a sign-in that could not be refreshed); **Not working** is `ERROR`; **Disabled** is `enabled` false.
- **Storing again keeps the credential's id**, so the triggers whose rows name it go on.
- **Disable** stops every use by groups and service accounts at its next check; conversations that act with it wait for an admin.
- **Remove** goes in this order: its grants (`grant.deleted`), its lendings (`credential.withdrawn`: the triggers that act with it pause until their authors name another), its token from the vault, and its sign-in at the system where the system revokes (`account.disconnected`). Conversations that act with it stop at their next use of it.
- **A service account borrows at most one credential per system**: lending it another replaces the one it borrows, and its triggers' rows follow ([accounts.md, section 6](accounts.md#6-how-a-triggers-accounts-table-works)).
- **The token** is a managed company secret (`SYSTEM_CREDENTIAL`). The **Secrets** page lists it but cannot replace or delete it (`409 SECRET_MANAGED`). Each use reveals it for that use only, audited as `secret.accessed` with its purpose (for example `system-test:{systemId}`, `system-tools:{systemId}`, `trigger-poll:{pollId}`). The server makes every tool call itself; a code host's credential also goes to the runner host for a run's git, never into the sandbox. No API returns a token.

| Operation | API, under `/api/companies/{companyId}` | Audited as |
|---|---|---|
| Add with a token | `POST /systems/{systemId}/credentials` with `name` and `token` | `account.connected` with the credential's name; a refusal `account.refused` |
| Add by signing in | `POST …/credentials/oauth` with `name` and `returnTo`, which answers the URL to send the browser to | `account.connected` |
| Store a new token, sign in again | `PUT …/credentials/{credentialId}/token`, `POST …/credentials/{credentialId}/oauth` | `account.reconnected` |
| Rename, disable, enable | `PATCH …/credentials/{credentialId}` with `name` or `enabled` | `credential.changed` |
| Remove | `DELETE …/credentials/{credentialId}` | `grant.deleted`, `credential.withdrawn`, `account.disconnected` |
| Lend to a group | `POST /grants` (`createGrant`), `PATCH` and `DELETE /grants/{grantId}` | `grant.created`, `grant.changed`, `grant.deleted` |
| Lend to a service account | `PUT …/credentials/{credentialId}/service-accounts/{serviceIdentityId}` (`lendSystemCredential`), `DELETE` the same path (`withdrawSystemCredential`); `GET /service-identities/{serviceIdentityId}/credentials` | `credential.lent`, `credential.withdrawn` |

None of these events holds a token.

## 5. Test a system

What **Test** uses, what it says and the status labels are in [Test a system](../../docs-site/content/admin-guide/systems.md#test-a-system). Inside:

- **The account**: the `credentialId` the caller picked; else the caller's own working account on the system; else the system's only enabled working credential; else none. A picked credential that is disabled or `RECONNECT` is not sent, and the status stays as it was.
- **With an account** it makes the "who am I" request of section 2. **Without one** it makes that section's last request: a tracker is reached by any answer below 500 that is not a redirect; an MCP server must answer the `initialize`, or ask for a token. A `401` counts as reached. A chat system's test checks the bot, its secrets and, with Socket Mode, the connection to Slack ([chats.md](chats.md)).
- Git is `400 SYSTEM_KIND_INVALID`, a webhook is never tested (its health is its deliveries), and a paused system is `409 SYSTEM_PAUSED`.
- **The last test** (V043) replaces the one before: when it ended, whether it passed and why not (at most 500 characters), whether it used an account, who ran it and with which credential. Every member who sees the system reads when and how it ended; who ran it and the credential, by its name now, only whoever manages the system (`SystemService.lastTest`). Tests are not audited. API: `POST …/systems/{systemId}/test?credentialId=`.

### 5.1 Status

| `status` | Systems list | System page | Set by |
|---|---|---|---|
| `NEW` | **Not tested yet** | **Not tested yet** | Registration; an MCP server's new endpoint |
| `OK` | **Reachable** | **Ready** | A test that passed; an MCP server's **Refresh tools** that read the catalogue, or that found the server asking for a credential when none was given |
| `ERROR` | **Unreachable**, the error as its tooltip | **Error** | A test that failed; an MCP server's refresh that could not read the catalogue |

`ERROR` clears at the next test that passes, or an MCP server's next refresh that reads. A paused system shows **Paused** instead. Polls do not change the status: their errors show on the poll (section 6).

## 6. Polls

What admins see of polls is in [How Tinfirm reads a tracker](../../docs-site/content/admin-guide/systems.md#how-tinfirm-reads-a-tracker); what the triggers do in [triggers.md](triggers.md). Inside:

- A **poll** is one read of a tracker with one account: a member's own, for the triggers that act with it, or a credential, whose one poll serves the triggers of every service account that borrows it. API: `pollIntervalSeconds` on the system, 30 to 3600, 60 unless set; `GET /api/companies/{companyId}/systems/{systemId}/polls`, each poll with its account, holder, triggers, last pass, error, backoff and rate limit.
- A replica with the `worker` role (`TINFIRM_ROLES`) looks for due polls every `TINFIRM_TRIGGER_POLL_TICK`. A pass reveals the account's token once (`trigger-poll:{pollId}`), lists the subjects changed since the last pass over its triggers' scopes, at most 200 a pass (the next passes go on), handles each one, and sets the next pass after the system's interval, doubled while less than a tenth of the rate limit is left. A poll without an active trigger turns idle.
- `TINFIRM_TRIGGER_SETTLE` also sets how long after Tinfirm's own write a webhook delivery about the same subject counts as an echo. The variables are in [Triggers, webhooks and chats](../../docs-site/content/reference/configuration.md#triggers-webhooks-and-chats).
- A system takes at most 100 live triggers, whatever their source (`409 TOO_MANY_TRIGGERS`).

| The system answers | The poll |
|---|---|
| `401` | The account or credential turns `RECONNECT`, and the poll waits with *The system refused the account's credential: …*. It goes on once the account works again |
| `403` or `404` for one scope | That scope's triggers wait; the other scopes are read |
| A rate limit (`429`, GitHub's `403` with its rate-limit headers) | Backs off until the reset, at most an hour |
| `5xx`, a timeout, no connection | Backs off 1, 2, 4… minutes, up to 30; after an hour of failures its triggers wait |

## 7. Webhook systems

Registering one, the two schemes, the secret, the mapping, the try, the deliveries and the limits are in [Receive webhooks](../../docs-site/content/admin-guide/webhooks.md); [alerts.md](alerts.md) is an example setup with Alertmanager. Inside:

- **Registration**: `POST …/systems` with `kind` `WEBHOOK` and `webhook: {scheme, secretHeader?, mapping, maxPerMinute?}`; the answer holds the secret once, in `webhook.secret`. The delivery URL is `{TINFIRM_PUBLIC_URL}/api/hooks/{systemId}`.
- **`STANDARD`** (**Signed (Standard Webhooks)**): the headers `webhook-id` (1 to 200 printable characters), `webhook-timestamp` (Unix seconds) and `webhook-signature` (space-separated `v1,{base64}`). One signature must equal the HMAC-SHA256 of `{id}.{timestamp}.{raw body}`, keyed with the bytes of the base64 after `whsec_`, and the timestamp must be within 300 seconds of the server's clock either way.
- **`SECRET_HEADER`** (**Secret in a header (weaker)**): the system's header equals the secret; `X-Tinfirm-Secret` unless set.
- **The secret** is `whsec_` and 32 random bytes in base64. A rotation shows the new one once; the current one still works for 24 hours and the one before it stops at once. For a leaked secret, **Stop the current secret now** (`{"revokePrevious": true}`) drops the current one too: `previousUntil` is null, and every replica refuses it from its next delivery, which reads the system's row. Audited as `system.webhook_secret_rotated` (with `revokedPrevious`); API `POST …/systems/{systemId}/webhook/rotate`. The inbox parses the path's system id before anything else (`404` for anything but a UUID of 36 characters) and counts an address's refusals under its canonical form (L6).
- **A delivery's id** is its `webhook-id`, else the mapped delivery id, else the SHA-256 of the body.
- **The answers**, in the public guide's order: a source flood's `429` carries `Retry-After: 60`, stopped runs' `503` `Retry-After: 300`, and a new delivery's `202` `{"deliveryId", "firings"}`. Refusals are audited as `webhook.refused`, once a minute per system and reason (`SIGNATURE`, `WINDOW`, `SIZE`, `SOURCE_RATE`, `RATE`), with their count. The limits on refusals and on deliveries a minute are kept in memory per replica.
- **The mapping** is JSON Pointers (RFC 6901); one that is not is `400 MAPPING_INVALID`. A delivery whose pointer cannot be read, or whose mapped delivery id is not 1 to 200 printable characters, is kept as `MAPPING_FAILED` and fires nothing; else it is `FIRED` when a firing started or continued a conversation, else `NO_TRIGGER` (`WebhookReceiver`).
- **What reaches the prompt**: a new conversation's first turn gets the whole body, cut to 16 KiB, under `# Event`; a delivery that continues an open conversation adds only the mapped event, subject, sender and text (`WebhookFirings.external` and `request`).
- **The try**: `POST …/systems/{systemId}/webhook/map` with a body answers what each field reads, each trigger's state and, for one that does not match, the first event or condition the body fails (`mismatch`). The same try is `try_webhook_delivery` on Tinfirm's MCP endpoint ([mcp-clients.md](mcp-clients.md)).
- **The deliveries**: `GET …/systems/{systemId}/webhook/deliveries`, whose first page also counts the last 7 days (`lastSevenDays`).
- **Deliveries a minute**: a system's `maxPerMinute`, 1 to 6000 and at most the company's maximum (`400 WEBHOOK_LIMIT_TOO_HIGH`), else the company's default. Until admins set the company's, they are `TINFIRM_WEBHOOK_DELIVERIES_PER_MINUTE_DEFAULT` (60) and `TINFIRM_WEBHOOK_DELIVERIES_PER_MINUTE_MAX` (600).

## 8. Pause, resume and remove

What the dialogs say and what admins see are in [Pause, resume and remove a system](../../docs-site/content/admin-guide/systems.md#pause-resume-and-remove-a-system). Inside:

- **Pause** is for company systems only: a personal MCP server is `400 PERSONAL_SYSTEM`, and the company's switch of personal servers stops those ([mcp-servers.md, section 4](mcp-servers.md#4-personal-mcp-servers)). While a system is paused, its tools are not listed in the next turns and a call from an earlier list is refused; a queued turn whose workspace holds one of its repositories waits; testing, **Refresh tools** and connecting accounts or credentials are `409 SYSTEM_PAUSED`; its triggers pause and its polls wait; a webhook answers `200 {"paused": true}` and stores nothing; a chat's events are verified and dropped. Audited as `system.paused`. API: `POST …/systems/{systemId}/pause` with `reason` (1 to 500 characters) and `stopRunning`, which cancels the turns running in conversations that use it.
- **Resume** with `resumeTriggers` (on by default in the app) resumes each trigger the pause paused, from now on: nothing of the pause fires. One left out stays paused until its author or an admin resumes it. Audited as `system.resumed`. API: `POST …/systems/{systemId}/resume` with `resumeTriggers`.
- **Remove** disconnects every account on the system first: members' own, and the company's credentials with their grants and lendings (`account.disconnected` with the cause `system.deleted`). The grants are not revoked at the system, since the Sign-in app goes with the system. Agents lose its repositories and tools, the Sign-in app's client secret is deleted, a webhook's URL stops at once and its secret is deleted, and a chat's bot, secrets, members' links and open direct-message conversations go.
- **The triggers** (`TriggerKillSwitches.systemDeleted`): only the triggers whose source the system is, those that start from it, are deleted (`trigger.deleted` with the cause `system.deleted`), and their conversations end (`TRIGGER_DELETED`). The app's dialog calls them "the triggers that watch it". Every other live trigger with an account row on the system pauses: `ACCOUNT_REMOVED` when the row's account goes, then `SYSTEM_REMOVED` (`trigger.paused`), the row gone with the system. Its author checks the accounts, then resumes it.
- Conversations that used the system stay, readable in the app; their bindings on it end. Audited as `system.deleted`. API: `DELETE …/systems/{systemId}`.

## 9. The outbound guard

What admins and operators need is in [Systems on your own network](../../docs-site/content/admin-guide/systems.md#systems-on-your-own-network), with the variables and their Helm values in [Connections to systems](../../docs-site/content/reference/configuration.md#connections-to-systems). Inside:

Every call Tinfirm makes to a system goes through one outbound client (`OutboundHttp`, `OutboundProperties`; the guard is `AddressGuard`, `GuardedHosts`, `GuardedResolverProvider` and `OutboundUrls`): tests, polls, tool calls, change requests, token checks, sign-ins, the discovery of an MCP server's sign-in and the Sign-in apps' authorization servers.

- **https only**, unless the operator allows http. The server's JVM must trust the certificate: add an internal CA to its trust store.
- **No redirects**, and only to the origin of the base URL a call names.
- **No loopback, link-local, private, unspecified, multicast or special-use addresses**, unless the operator allows them. Special-use are the ranges no internet host is in, which a private network may route (RFC 6890: `192.0.0.0/24`, the documentation ranges, `198.18.0.0/15`, `240.0.0.0/4`, `64:ff9b:1::/48`, `100::/64`, the IPv4-compatible IPv6 addresses) and Azure's wire server `168.63.129.16`; an address of NAT64's well-known prefix `64:ff9b::/96`, of 6to4's `2002::/16` or of Teredo's `2001::/32` is checked as the IPv4 address it embeds (for Teredo the client's, stored inverted) (`shared/net/AddressGuard`). The host is resolved and every address checked before each request, and the lookup the request connects with leaves those addresses out, so a DNS answer that changes in between (DNS rebinding) cannot reach them. Without `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES=true`, the server refuses to start when the JVM does not use this guarded resolver; it does from the image and from the executable jar.
- **No proxy but an explicit one.** The outbound client, the model gateway's client (`GatewayHttp`) and the fetch of AI clients' metadata documents (`CimdDocumentFetcher`) ignore the JVM's proxy settings (`https.proxyHost`, `java.net.useSystemProxies`, `JAVA_TOOL_OPTIONS`): a proxy resolves the target's name itself, so the connect-time guard would not see the lookup. Where the deployment's egress must go through a proxy, name it in `TINFIRM_OUTBOUND_PROXY` (`tinfirm.outbound.proxy`), as `http://host:port` without credentials (the server refuses to start with another form); the bundled LiteLLM is still reached directly. The guard still resolves and checks every host before each request, but a DNS answer that changes between that check and the proxy's own lookup is the proxy's to refuse: configure it to refuse loopback, link-local, private and the cloud's metadata addresses (`OutboundProxy`, the security review of 2026-10-27, L12).
- **Internal hosts** (`TINFIRM_CONNECTION_INTERNAL_HOSTS`, [ADR-0066](../adr/0066-internal-hosts.md)): a company system on one may resolve to private addresses only, never public, loopback, link-local, unspecified, multicast or special-use ones, and may use http; every other host keeps the guard. A company's own service on the deployment's network, for example, is reached this way ([mcp-servers.md, section 2.2](mcp-servers.md#22-a-service-on-the-deployments-own-network)).
- **A personal MCP server never reaches private addresses**, whatever the operator allows, and the internal hosts do not apply to it.
- A read (`GET`) that the system answers with 502, 503 or 504, or that cannot connect, is tried again twice, after 0.5 s and 2 s (or after a 503's `Retry-After` of at most 5 s). A write is never tried again. An answer is read up to 16 MiB.
- `TINFIRM_CONNECTION_TIMEOUT` bounds the connect and each request; `TINFIRM_RUN_TOOL_TIMEOUT` (at most `PT60S`) one tool call or change request in all, and the runner host waits 5 s longer.

With the Helm chart's NetworkPolicy, `networkPolicy.egress.systems` lets the server reach systems, sign-in hosts and MCP servers on public addresses over https; one inside the network, or on another port, needs an egress rule in `networkPolicy.egress.extra` ([helm.md, section 4](helm.md#4-networkpolicy)).

## 10. What a system receives

Every system an agent may call tools on receives what the agent passes as their arguments, a read tool's as much as a write's, so what a conversation read elsewhere (code, issues, documents, its memory, another system's answers) may reach any system it calls. The kinds with tools are `JIRA_DC`, `JIRA_CLOUD`, `GITHUB`, `GITLAB`, `MCP` and `SLACK` (its `history`); `GIT`, `TEAMS` and `WEBHOOK` have none (`SystemKind.receivesData`). The threat model accepts this (the security review of 2026-10-27, D3, the owner's decision Q5) with two controls, neither of which asks before a call. A tool call's arguments are at most 64 KiB as JSON in UTF-8 (`SystemTools.MAX_ARGUMENT_BYTES`): the runner host refuses a larger call after redaction, and the server refuses one again before its call journal (`ToolCalls.tooLarge`, `REJECTED INVALID_REQUEST`, nothing journaled or called) and in `SystemTools.check`. And the system's view says it: `receivesData`, with `internalHost` for whether its base URL's host is one of `TINFIRM_CONNECTION_INTERNAL_HOSTS` (never for a personal server); the system's page shows one sentence under its name, *Agents send this system what they pass to its tools, which can include what they read elsewhere in the conversation.*, and names an MCP server off the internal hosts, a personal one included, *(a third-party server)*. The `ASK` the review proposed, on the first call to a system of a lower trust class after a read from a higher one, is not built.

## 11. Troubleshooting

The public guides' tables cover what an admin sees. What the internals add:

| What you see | Inside | Fix |
|---|---|---|
| `400 INVALID_BASE_URL`, *baseUrl is not usable: it must be https (…)* | An `http` address, without `TINFIRM_CONNECTION_ALLOW_HTTP` or an internal host | Use https, or set one of them |
| *baseUrl is not usable: the host … resolves to a private address (…)* | The guard (section 9) | `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES=true`, or name the host in `TINFIRM_CONNECTION_INTERNAL_HOSTS` |
| *The account's credential is not an Atlassian account's email and API token as email:token* | A Jira Cloud token stored without the email | Store a new token as `email:api-token` |
| *The host … is unknown*, *… could not be reached (…)*, *The TLS connection to … failed (…)* | DNS, the network, the NetworkPolicy or the trust store | An egress rule, a proxy setting, or the CA in the JVM's trust store |
| *… did not answer in time (…)* | Slower than `TINFIRM_CONNECTION_TIMEOUT` | Check the system; raise the timeout |
| Back from a sign-in with `?account=site_not_granted`, `exchange_failed` or `name_taken` | The callback's codes ([accounts.md, section 8](accounts.md#8-when-a-sign-in-comes-back)) | As that table says |
| The sender gets `401` or `429` | `webhook.refused` in the audit log names the reason and the count | Fix the secret, the header, the sender's clock or the rate |
| A delivery is `NO_TRIGGER` | No trigger took it | `POST …/webhook/map` with its body: `mismatch` names the first event or condition each trigger fails |

## 12. The API

Under `/api/companies/{companyId}`; the contract is `api/openapi.yaml` (tags Systems, Credentials, Grants, Webhooks; the pause in Governance, the polls in Triggers).

| Operation | Who |
|---|---|
| `GET /systems`, `GET /systems/{systemId}` | Any member; a personal server, its owner only |
| `POST /systems`, `PATCH` and `DELETE /systems/{systemId}` | OWNER, ADMIN; a personal MCP server, its owner |
| `PUT`, `DELETE /systems/{systemId}/app` | OWNER, ADMIN; a personal server, its owner |
| `POST /systems/{systemId}/test` | OWNER, ADMIN; a personal server, its owner |
| `GET`, `POST /systems/{systemId}/repositories`, `DELETE …/repositories/{repositoryId}` | Read: any member; change: OWNER, ADMIN |
| `GET /systems/{systemId}/tools`, `POST …/tools/refresh`, `PATCH …/tools/{toolName}` | Read: any member who sees it; change: OWNER, ADMIN, a personal server's owner |
| `GET`, `POST /systems/{systemId}/credentials`, `POST …/credentials/oauth`, `PATCH`, `DELETE …/credentials/{credentialId}`, `PUT …/token`, `POST …/oauth`, `PUT`, `DELETE …/service-accounts/{serviceIdentityId}` | OWNER, ADMIN |
| `GET /service-identities/{serviceIdentityId}/credentials` | OWNER, ADMIN |
| `GET`, `POST /grants`, `PATCH`, `DELETE /grants/{grantId}` | OWNER, ADMIN |
| `POST /systems/{systemId}/pause`, `POST …/resume` | OWNER, ADMIN |
| `GET /systems/{systemId}/polls` | OWNER, ADMIN |
| `POST /systems/{systemId}/webhook/rotate`, `POST …/webhook/map`, `GET …/webhook/deliveries` | OWNER, ADMIN |
| `GET`, `PUT /system-settings` (members' own MCP servers on or off) | Read: any member; change: OWNER, ADMIN |

Registering and changing a system are audited as `system.created` and `system.changed`, with what changed. A webhook's deliveries go to `POST /api/hooks/{systemId}`, outside `/api/companies` and outside the OpenAPI document, since a generated interface would parse a body before its signature is checked. Tinfirm's MCP endpoint offers the reads `list_systems`, `get_system`, `list_system_tools`, `list_webhook_deliveries`, `try_webhook_delivery` and the tool `test_system` to people's AI clients ([mcp-clients.md](mcp-clients.md)).
