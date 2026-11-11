# MCP servers: your own tools for the agents

The public guide is [Give agents the tools of an MCP server](../../docs-site/content/admin-guide/mcp-servers.md) (https://docs.tinfirm.com/admin-guide/mcp-servers/), for a company's Owners and Admins and whoever runs a server; members add servers of their own as [Connect your accounts](../../docs-site/content/user-guide/accounts.md#your-mcp-servers) (https://docs.tinfirm.com/user-guide/accounts/) says. This page keeps what the people who build Tinfirm need beyond it: the design and its sources, the protocol Tinfirm speaks with a server and its authorization server, problem codes, audit events and the API, how a tool's name in a run is made, a service on the deployment's own network, the limits that are the server's configuration, and the troubleshooting that needs them.

What every system shares (registering, the Sign-in app, credentials and their lending, tests, pausing, the outbound guard) is in [systems.md](systems.md). The decisions are [ADR-0040](../adr/0040-mcp-tool-connections.md) (the server's MCP client), [ADR-0058](../adr/0058-authority-from-the-audience.md) (systems and accounts), [ADR-0071](../adr/0071-mcp-servers-without-accounts.md) (servers that need no account), [ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md) (credentials on systems) and [ADR-0066](../adr/0066-internal-hosts.md) (internal hosts); the rules are [CONVENTIONS §15.2](../CONVENTIONS.md#152-systems) and [§15.3](../CONVENTIONS.md#153-accounts-and-grants).

## 1. What the server must do

The public guide's [What the server must offer](../../docs-site/content/admin-guide/mcp-servers.md#what-the-server-must-offer) is the summary for whoever runs a server. The protocol, as the Tinfirm server speaks [MCP](https://modelcontextprotocol.io) over Streamable HTTP at one URL:

- **One JSON-RPC request per `POST`.** Tinfirm sends `initialize` (protocol version `2025-06-18`; it uses the version your server answers), the notification `notifications/initialized`, `tools/list` and `tools/call`, with `Accept: application/json, text/event-stream`, and reads an answer as JSON or as a stream of server-sent events. When `initialize` answers with an `Mcp-Session-Id` header, Tinfirm sends it back on every later request of that session, with `MCP-Protocol-Version`, and a `DELETE` with it when the session is done; nothing depends on that answer. Every refresh, test, token check and call is one session.
- **`tools/list`** as the specification says: `tools`, each with `name`, an optional `title` and `description`, an `inputSchema`, and optional `annotations`; `nextCursor` when there are more. A tool name matches `^[A-Za-z0-9_-]{1,64}$`; a tool with another name, or with a name another tool has ignoring case, is skipped. `annotations.title` stands in for a missing `title`, and `annotations.readOnlyHint` and `annotations.destructiveHint` suggest whether the tool reads or writes (section 5).
- **`tools/call`** with `name` and `arguments`, answered with `content`, optionally `structuredContent` (an object), and `isError` for a failure the agent should see. Text blocks reach the agent as they are; any other block (an image, audio, a resource) is replaced by a text that names its type.
- **https**, unless the operator allows http or names the host as an internal host ([systems.md, section 9](systems.md#9-the-outbound-guard)); a personal server must be on a public address. Only the Tinfirm server calls it, so with the Helm chart's NetworkPolicy it needs an egress rule ([helm.md, section 4](helm.md#4-networkpolicy)).
- **In time**: each request within `TINFIRM_CONNECTION_TIMEOUT` and a whole call within `TINFIRM_RUN_TOOL_TIMEOUT` (section 8).

A `401` (or a `403`) to `initialize` tells Tinfirm that the account was refused: it turns `RECONNECT`, to be signed in again or given a new token. A `403` to `tools/call` is the server's answer for that account, and the agent sees it as the tool's error.

### 1.1 A minimal example

A server with one tool, in Node.js without dependencies, with a bearer token. It answers with JSON; a stream of server-sent events works too.

```js
import http from 'node:http';

const TOKEN = process.env.TOOL_TOKEN;
const TOOLS = [{
  name: 'search_customers',
  description: 'Finds customers by name. Returns at most `limit` matches.',
  inputSchema: {
    type: 'object',
    properties: { query: { type: 'string' }, limit: { type: 'integer', default: 10 } },
    required: ['query'],
  },
  annotations: { readOnlyHint: true },
}];

function search(query, limit) {
  // Your lookup here. Keep the answer small.
  return [{ id: 42, name: `${query} Ltd` }].slice(0, limit);
}

http.createServer((req, res) => {
  if (req.headers.authorization !== `Bearer ${TOKEN}`) return reply(res, 401, {});
  if (req.method !== 'POST') return reply(res, 405, {});
  let body = '';
  req.on('data', (chunk) => (body += chunk));
  req.on('end', () => {
    const { id, method, params } = JSON.parse(body);
    const ok = (result) => reply(res, 200, { jsonrpc: '2.0', id, result });
    switch (method) {
      case 'initialize':
        return ok({ protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'crm', version: '1' } });
      case 'notifications/initialized':
        return reply(res, 202, null);
      case 'tools/list':
        return ok({ tools: TOOLS });
      case 'tools/call': {
        if (params.name !== 'search_customers') return ok({ content: [{ type: 'text', text: 'unknown tool' }], isError: true });
        const matches = search(params.arguments.query, params.arguments.limit ?? 10);
        return ok({ content: [{ type: 'text', text: JSON.stringify(matches) }], structuredContent: { matches } });
      }
      default:
        return reply(res, 200, { jsonrpc: '2.0', id, error: { code: -32601, message: 'unknown method' } });
    }
  });
}).listen(8765);

function reply(res, status, json) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(json === null ? '' : JSON.stringify(json));
}
```

Put it behind https at a path such as `https://tools.example.com/crm/mcp`. The official MCP SDKs for TypeScript, Python, Java and others do the protocol for you; any of them works, as long as the transport is Streamable HTTP.

## 2. Register it

Registering a server, testing it and what to do next are in [Register a company MCP server](../../docs-site/content/admin-guide/mcp-servers.md#register-a-company-mcp-server). Inside:

- **The endpoint** is https, without a user name, password, query or fragment. The handle is made from the name as for every system ([systems.md, section 1](systems.md#1-kinds)).
- **The token header** is 1 to 64 letters, digits and `-`, not one Tinfirm or the transport sets (such as `Host`, `Content-Type`, `Accept` or `Mcp-Session-Id`); `Authorization` is the same as empty.
- Right after the registration Tinfirm runs the sign-in's discovery (section 3.1), but reads no tools: the catalogue is empty until the first **Refresh tools** (section 5).
- **Remove** works as for every system ([systems.md, section 8](systems.md#8-pause-resume-and-remove)); it revokes no sign-in at the server (section 3.1).

### 2.1 Needs no account

What the setting does for admins is in [A server that needs no account](../../docs-site/content/admin-guide/mcp-servers.md#a-server-that-needs-no-account); the decision is [ADR-0071](../adr/0071-mcp-servers-without-accounts.md), for servers whose NetworkPolicy is their boundary, such as the log servers of [alerts.md](alerts.md#3-the-logs-as-tools-a-victorialogs-mcp-server-per-agent). Inside:

- Setting it calls `initialize`, then `tools/list`, without a credential. A server that does not answer both is `400 CREDENTIAL_REQUIRED` with its answer (*CRM asks for a credential, so it needs accounts: …*, or *CRM did not answer without a credential: …*), and nothing changes. A paused server is `409 SYSTEM_PAUSED`.
- Conversations then bind no account on the server (`OPEN`), a trigger's conversations too, without a row for it in the trigger's table; the trigger editor and `GET /me/accounts` show it as `NOT_NEEDED`. An account or credential connected before stays and binds nothing new.
- A server that starts asking for a credential while set this way refuses the calls (`401`), and the agent says so.
- Audited as `system.changed` with `accountsRequired`; API: `PATCH …/systems/{systemId}` with `accountsRequired` (an MCP server only).

### 2.2 A service on the deployment's own network

Register a company's own service on the deployment's network, such as a reporting service, as a company MCP server at its endpoint, with its token as the bearer token (section 3), and name its host in `TINFIRM_CONNECTION_INTERNAL_HOSTS` ([systems.md, section 9](systems.md#9-the-outbound-guard)).

### 2.3 A new endpoint

What a new endpoint removes and keeps, and what to do after, are in [Move a server to a new endpoint](../../docs-site/content/admin-guide/mcp-servers.md#move-a-server-to-a-new-endpoint); the decision is [ADR-0079](../adr/0079-an-mcp-servers-endpoint-changes.md): a token is sent only where it was given. Inside:

- Every account and credential on the server goes, with their lendings to groups and service accounts, each audited as `account.disconnected` with the cause `system.moved`.
- Its Sign-in app, found at the old address, goes, and the discovery runs at the new address as for a new server (section 3.1).
- Its status is `NEW` again, without its last test.
- A server that needs no account is reached at the new endpoint first: one that asks for a token there is refused with its answer (`CREDENTIAL_REQUIRED`). A paused one is not called, so it does not move.
- Audited as `system.changed` with both addresses; API: `PATCH …/systems/{systemId}` with `baseUrl`.

## 3. Credentials

Which account each call acts with, tokens and sign-ins are in [Sign in to the server](../../docs-site/content/admin-guide/mcp-servers.md#sign-in-to-the-server); storing again, renaming, disabling and removing a credential in [systems.md, section 4](systems.md#4-credentials). Inside:

- **A token** is checked with an `initialize` that carries it in the **Token header** (else as `Authorization: Bearer`), and kept only when the server accepts it; a refusal is `400 ACCOUNT_REFUSED` with the server's answer, for a credential and for a member's own token alike. The token header applies to stored tokens only: an access token from a sign-in is always sent as `Authorization: Bearer`.
- **Read-only** is enforced by effect: Tinfirm cannot tell a read from a write on a server beyond what an admin confirms (section 5), so a read-only credential refuses the tools confirmed or counted as writes, and nothing more.

### 3.1 Sign-in with OAuth

What admins do is in the public guide's [Sign in with OAuth](../../docs-site/content/admin-guide/mcp-servers.md#sign-in-with-oauth). Tinfirm finds the server's authorization server as the MCP specification describes (section 3.2), right after the server is registered and whenever the app is set; **Refresh tools** does not look again.

- **Set up by itself.** Where the authorization server advertises `client_id_metadata_document_supported`, Tinfirm's client ID is its client metadata document, `{TINFIRM_PUBLIC_URL}/oauth/client-metadata.json` ([accounts.md, section 9](accounts.md#9-tinfirms-client-metadata-document)). Else, where it publishes a `registration_endpoint`, Tinfirm registers itself there (RFC 7591) as a public client (`token_endpoint_auth_method` `none`). The app change is audited as `system.app_changed` with `automatic` (and `registered` when Tinfirm registered).
- **Set up by hand**, with a client registered at the authorization server with the callback `{TINFIRM_PUBLIC_URL}/api/oauth/callback`. A client with a secret authenticates with `client_secret_basic`.
- **A sign-in** is an authorization code with PKCE `S256`, the callback, the app's scopes (else the authorization server's `scopes_supported`), and `resource` (RFC 8707) set to the MCP endpoint. Tinfirm refreshes the access token before it expires, and revokes the grant at the authorization server's revocation endpoint, where it has one, when the credential or account is removed. Removing the whole server revokes nothing.
- **The endpoints share the issuer's origin.** The authorization, token, revocation and registration endpoints an authorization server's metadata names must have its issuer's scheme, host and port; else the discovery fails (*The token_endpoint of … is not allowed: it is on …, not on the issuer's origin*). A provider that hosts them elsewhere (Google's issuer `https://accounts.google.com` with its token endpoint on `https://oauth2.googleapis.com`) needs the operator to allow that origin in `TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS` (`tinfirm.oauth.discovery.allowed-endpoint-origins`, origins only, comma-separated, empty by default). Otherwise a server could name a real provider's authorization endpoint and its own token endpoint, and receive the codes of that provider's consent (the security review of 2026-10-27, M8).
- **New endpoints take the OAuth accounts with them.** When **Set up the app** finds another token or revocation endpoint than the stored app's (or the app was removed and set up again) while OAuth accounts or credentials are on the server, it answers `409 SYSTEM_APP_ENDPOINTS_CHANGED` with the endpoints before and after, the number of members' OAuth accounts and the OAuth credentials by name, and changes nothing. Saved again with `confirmEndpointsChange`, those accounts and credentials go in the same transaction, each audited as `account.disconnected` with the cause `system.app_endpoints_changed` (not revoked anywhere), with the pending sign-ins; `system.app_changed` names both endpoints and `accountsRemoved`. Accounts with a token typed in stay: their token goes to the MCP endpoint, which did not change. This is ADR-0079's rule extended from the endpoint to the app's endpoints.
- **No sign-in**: **Set up the app** answers `409 OAUTH_NOT_AVAILABLE` with the reason: *The server answers without a token, so it offers no OAuth*, *The server's OAuth discovery failed: …*, or *Tinfirm could not register at the server: …*. An authorization server that takes neither the client metadata document nor a registration, set up without a client ID, is `400 VALIDATION_FAILED` (*clientId is required: register Tinfirm at the system with the callback URL …*). Such a server still takes tokens.

### 3.2 What the authorization server must do

| Step | What Tinfirm does | What your side must do |
|---|---|---|
| Protected-resource metadata | Sends an `initialize` to the endpoint without a token and reads `resource_metadata` from the `WWW-Authenticate: Bearer` header of the `401`; else reads `/.well-known/oauth-protected-resource` for the endpoint's path, then the one at its root. A `2xx` means the server needs no token: no OAuth, and no error | Answer `401` with that header, or serve the document (RFC 9728) naming your `authorization_servers` and your MCP endpoint, exactly, as its `resource` |
| Authorization server metadata | Tries up to 5 of the named authorization servers and takes the first usable one: `/.well-known/oauth-authorization-server` with the path rules of RFC 8414, else `/.well-known/openid-configuration` (inserted, then appended). The document must name exactly the issuer the resource named | Publish the authorization and token endpoints and, if you have them, the revocation and registration endpoints, `scopes_supported` and `client_id_metadata_document_supported`. **`code_challenge_methods_supported` must list `S256`** |
| Tinfirm as a client | The client ID on the Sign-in app; else its client metadata document; else a registration | Support one of the three, and allow the callback as a redirect URI |
| The authorization request | Authorization code with PKCE `S256`, the callback, the scopes and `resource` | Accept `resource`, issue access tokens for your MCP endpoint, and give refresh tokens |
| The calls | Sends the access token as `Authorization: Bearer` and refreshes it before it expires | Accept the access token as that user and enforce that user's permissions |

Every URL the documents name passes the same outbound guard as the endpoint, and a document larger than 64 KiB is refused. At the token endpoint Tinfirm reads `expires_in` to know when to refresh: a missing one means the token never expires; a zero, negative or non-numeric one means it has expired already. A refusal of the token, revocation or registration endpoint is recorded as *answered {status} ({code})* with an RFC 6749 error code only (`invalid_grant`, `invalid_client`, …; any other code reads `unknown_error`), never the `error_description`. Accept only tokens issued for your own endpoint, and never forward a token you received to another service: if your tools call other systems, use credentials of your own or token exchange.

## 4. Personal MCP servers

What a member does and sees is in [Your MCP servers](../../docs-site/content/user-guide/accounts.md#your-mcp-servers), and the company's switch in [Members' own MCP servers](../../docs-site/content/admin-guide/mcp-servers.md#members-own-mcp-servers). Inside:

- **To anyone but its owner**, admins included, it does not exist (`404`) and is listed nowhere.
- **It holds no credential** of the company and cannot be paused (`400 PERSONAL_SYSTEM`), and an agent's definition cannot name it (`400 PERSONAL_SYSTEM`): its tools reach its owner's conversations through their settings only. A trigger's settings may add its author's own servers only while every row of its accounts table is the author's own account.
- **It never reaches a private address**, whatever the operator allows, and internal hosts do not apply to it ([systems.md, section 9](systems.md#9-the-outbound-guard)). It goes when its owner leaves the company.
- **The company's switch**, **Their own MCP servers**: while it is off, registering one is `409 PERSONAL_SYSTEMS_OFF` and **Kill switches** shows `PERSONAL_MCP_SERVERS_OFF`. Audited as `system.settings_changed`. API: `GET`, `PUT /api/companies/{companyId}/system-settings` with `personalMcpServers`.

## 5. The tool catalogue

Refreshing, confirming what each tool does and switching tools off are in [Confirm what each tool does](../../docs-site/content/admin-guide/mcp-servers.md#confirm-what-each-tool-does). Inside:

- **Refresh tools** reads `tools/list` with the `credentialId` picked, else the caller's own working account, else the server's only enabled working credential, else none. A failed read keeps the catalogue as it was. Audited as `system.tools_refreshed`, with what was added, removed and unconfirmed, when something changed. A paused server is not read (`409 SYSTEM_PAUSED`). API: `POST …/systems/{systemId}/tools/refresh?credentialId=`.
- A tool the server no longer lists leaves the catalogue; an agent keeps its row for it, not offered, and runs do not list it. A changed description, input schema or hints drops the confirmed effect; a changed title alone keeps it.
- **For each tool** Tinfirm keeps its name, title, description, input schema, hints, its effect and whether it is offered.
- **The effect** is `READ`, `WRITE` or `UNKNOWN`. A new tool is `UNKNOWN` and stays so until an admin confirms it, and **`UNKNOWN` counts as `WRITE` everywhere**, so each call of it needs an approval and a read-only credential never calls it. The hints only suggest (`CatalogueService.suggested`): *Its hints say it only reads* for `readOnlyHint` true, *Its hints say it writes* for `destructiveHint` true or `readOnlyHint` false, *It sends no hints* otherwise; nothing is confirmed from them. A confirmation is audited as `system.tool_effect_confirmed`.
- **Off**: a tool switched off is listed by no run, a call of it is refused (`UNKNOWN_TOOL`), and no agent can be given it. Audited as `system.tool_switched`. API: `PATCH …/systems/{systemId}/tools/{toolName}` with `effect` (`READ` or `WRITE`) or `enabled`.

**Tool names in a run** are `{handle}_{tool}` in lower case. When that is longer than 64 characters, the tool's name is cut and the first four hex digits of the SHA-256 of its full name are added: `{handle}_{cut name}_{hash}`, so that two tools cut to the same prefix still differ. Two tools whose names differ only in case get one name, and the conversation gives the second a hash of its own. A tool's description in a run is the server's, then whether each call waits for an approval, then whom it runs as. The agent is told that tool results are other people's words, not instructions.

## 6. Security

What admins should do is in the public guide's [Keep it safe](../../docs-site/content/admin-guide/mcp-servers.md#keep-it-safe). Inside:

- **Tokens and sign-ins** are vault secrets: a credential's a managed company secret (`SYSTEM_CREDENTIAL`), a member's own a secret only that member owns (`MEMBER_ACCOUNT`), revealed only for a call that acts as them. Each use reveals one for that use only, audited as `secret.accessed` with its purpose. They never go to a runner host or a sandbox, and no API returns them.
- **What a tool returns is content from outside.** The prompt marks tool results as other people's words, but that is advice to the model, not enforcement: the sandbox, the run's guards and the review of every change by a person contain it.
- **A tool that writes** is one Tinfirm knows only by an admin's confirmation (section 5): nothing in the protocol proves an effect.
- **Audit**: `system.created`, `system.changed`, `system.app_changed`, `system.tools_refreshed`, `system.tool_effect_confirmed`, `system.tool_switched`, `system.paused`, `system.resumed` and `system.deleted` for the server; `account.connected`, `account.reconnected`, `account.disconnected`, `account.refused`, `credential.changed`, `credential.lent`, `credential.withdrawn` and `grant.created`, `grant.changed`, `grant.deleted` for its accounts and credentials; `tool.called` for each call (the conversation, the run, the tool, its effect and mode, the account, its holder and grant, an approval, and whether it worked) and `tool.denied` for a refused one, once per run and tool. None holds a token, the arguments or the results.

## 7. Give tools to agents

What whoever edits an agent does is in [Give the tools to agents](../../docs-site/content/admin-guide/mcp-servers.md#give-the-tools-to-agents); the rules for tools, repositories, approvals and settings are in [agent-permissions.md](agent-permissions.md). Saving makes a new version of the agent. API: `PUT …/agents/{agentId}/systems`, audited as `agent.tools_changed`.

## 8. Limits

The limits a server's author meets (tool names, titles and descriptions, arguments and results, tools on an agent and in a run, the pages of `tools/list` read) are in the public guide's [Limits](../../docs-site/content/admin-guide/mcp-servers.md#limits). Beyond them:

| Limit | Value |
|---|---|
| An answer read from the server | 16 MiB, the outbound client's bound ([systems.md, section 9](systems.md#9-the-outbound-guard)) |
| A sign-in metadata document | 64 KiB; at most 5 authorization servers tried (section 3.2) |
| One page of `tools/list` | 1 MiB; a larger answer fails the refresh (*The answer is larger than 1 MiB*) |
| A catalogue | 500 tools, an `inputSchema` of at most 64 KiB, 2 MiB in all (names, titles, descriptions and schemas as JSON); beyond, the refresh fails with the cap as the reason, the stored catalogue stays and the server's status is `ERROR` (`McpClient`, the security review of 2026-10-27, M9) |
| Refreshes of one server | One a minute per replica (`tinfirm.system.catalogue-refresh-interval`, `PT1M`); another is `429 RATE_LIMITED` with `Retry-After`, before any credential is revealed or request sent |
| One request to the server | `TINFIRM_CONNECTION_TIMEOUT`, 20 s |
| One call, from the agent's request to its answer | `TINFIRM_RUN_TOOL_TIMEOUT`, 60 s (at most 60 s) |

## 9. Troubleshooting

The public guide's table covers what an admin sees. What the codes add:

| What you see | Cause | Fix |
|---|---|---|
| `400 ACCOUNT_REFUSED` when adding a credential | The server refused the token's `initialize` | A new token; check the **Token header** |
| `400 CREDENTIAL_REQUIRED` when ticking **Needs no account** | The server asks for a credential, or does not answer `tools/list` without one | Keep it needing accounts |
| `409 OAUTH_NOT_AVAILABLE` on **Set up the app** | The server needs no token, its discovery failed (no protected-resource metadata, another `resource`, no `S256`, an address the guard refuses, an endpoint off the issuer's origin), or Tinfirm could not register | The reason is in the message: fix the metadata (section 3.2) and set up the app again, or use tokens; for a split-hosted provider, the operator allows its endpoints' origin (section 3.1) |
| `409 SYSTEM_APP_ENDPOINTS_CHANGED` on **Set up the app** | The server's metadata names another token or revocation endpoint while OAuth accounts or credentials are on it | Check that the server's metadata is meant to change; then confirm, and the people and the credentials sign in again |
| `400 VALIDATION_FAILED`, *clientId is required: …* | The authorization server takes neither Tinfirm's client metadata document nor a registration | Register a client there with the callback URL and enter its client ID |
| Back from a sign-in with `exchange_failed` | The authorization server refused the code: the callback is not an allowed redirect URI, or the client ID or secret is wrong and the server said so with an error outside RFC 6749 | Allow `{TINFIRM_PUBLIC_URL}/api/oauth/callback`; check the Sign-in app |
| Back from a sign-in with `app_refused` | The authorization server refused the client (`invalid_client` or `unauthorized_client`): the client ID or secret is wrong | Check the Sign-in app, and set it up again |
| *The host … is unknown*, *… could not be reached (…)*, *The TLS connection to … failed (…)* | Network, DNS, a private address, a certificate | The NetworkPolicy, `TINFIRM_CONNECTION_INTERNAL_HOSTS` or `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES`, the trust store ([systems.md, section 9](systems.md#9-the-outbound-guard)) |
| The agent's call answers `UNKNOWN_TOOL` | The server does not offer that tool now: switched off, or gone at a refresh | **Refresh tools**, switch it on |
| The agent's call answers `TOOL_DENIED` | The agent does not have the tool, the settings switch it off, the server is paused, a write through a read-only credential, or a trigger with no account on the server | What the message names |
| The agent's call answers `ACCOUNT_GONE` | The conversation's account on the server is gone: a credential removed, a grant withdrawn, the person out of the group | Lend another credential, or the person connects their own account |
| The agent's call answers `RESULT_TOO_LARGE` | A result above 800 KiB | Let the tool take a `limit` or a page |
| The agent's call answers `INVALID_REQUEST` | Arguments that are not a JSON object of at most 64 KiB | Narrow what the tool takes |
| The agent's call answers `TOOL_FAILED` | The call took longer than `TINFIRM_RUN_TOOL_TIMEOUT`, or failed inside Tinfirm | Make the tool answer within 60 s |
