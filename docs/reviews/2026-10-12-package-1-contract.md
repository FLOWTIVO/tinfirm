# Package 1 contract: principals, systems, accounts and grants

Status: decided on review (§10), built · 2026-10-12 · Owner: J. Balcar

The contract for package 1 of the [platform redesign](2026-10-12-platform-redesign.md#part-9-how-it-is-built--decided): the tables, the invariants they keep, the rule that picks a conversation's account, the API, the errors and the audit actions. Later packages build on it; where they add to a table, it says so. The schema starts fresh (Part 9): no migration of today's data.

## 1. Scope

| In package 1 | Later |
|---|---|
| Three roles; principals: members and service identities; who may use a service identity | Model access on principals (package 2) |
| Systems and their apps; repositories of code hosts; an MCP server's tool catalogue with effects | Agents, settings, conversations, tools in runs (package 3) |
| Accounts, by token or OAuth, with the identity on the system; personal MCP servers | Triggers and polling per account (package 4) |
| Grants of service identities' accounts to groups | Chat bots' events, linking chat users, the webhook's mapping (package 5) |
| The account-binding rule, as a function later packages call | Spend views, kill switches beyond suspending a principal (package 6) |

## 2. What the new baseline keeps and drops

| Kept unchanged | Changed | Not created |
|---|---|---|
| `app_user`, `company`, `spring_session*`, `audit_chain_head`, `audit_event`, `secret`, `secret_version` (with `owner_user_id`), `user_group`, `user_group_member`, `api_token`, `oauth2_*`, `company_identity_provider*` | `company_membership.role`: `OWNER`, `ADMIN`, `MEMBER`. `oauth_authorization_request`: keyed by system and principal (§3.7) | `connection*`, `repository` of connections, `trigger*`, `agent*`, `work_item*`, `job*`, `run*`, `decision*`, `model_credential`, `runner_credential`, `agent_group_right` (packages 2 to 4 create their own) |

Row-level security, the tenant-isolation test and the hash-chained audit stay as [CONVENTIONS](../CONVENTIONS.md) §6 to §8 define them; every table below carries `company_id`.

## 3. Tables

### 3.1 `principal`

Whom work acts for. Accounts and, from package 2, model access hang from it.

| Column | Meaning |
|---|---|
| `id`, `company_id` | |
| `kind` | `MEMBER` or `SERVICE` |
| `user_id` | The member's user; `NULL` for a service identity |
| `name`, `description` | A service identity's name (1 to 100 characters, unique in the company ignoring case) and description; `NULL` for a member, whose name is the user's |
| `status` | `ACTIVE`, `SUSPENDED` (an admin stopped it; reversible) or `LEFT` (the member left the company; final) |
| `created_at`, `updated_at`, `created_by` | |

- A member's principal is created with the membership and kept, as `LEFT`, when the membership ends, so conversations and the audit keep naming who acted. Unique `(company_id, user_id)`.
- A service identity never logs in and has no `app_user`. It acts only through its accounts.

### 3.2 `service_identity_user`

Who may use a service identity: pick its accounts and, from package 2, its budget on a trigger. `(company_id, principal_id, group_id)`, primary key `(principal_id, group_id)`. Only `SERVICE` principals. OWNER may use every service identity; nobody else without a group, ADMIN included (owner's rule: a specific permission means a group).

### 3.3 `external_system`

An external service registered once ("system" in the UI).

| Column | Meaning |
|---|---|
| `id`, `company_id`, `name` | Name unique in the company ignoring case |
| `handle` | The slug that prefixes its tools (`jira_get`), unique in the company |
| `kind` | `JIRA_DC`, `JIRA_CLOUD`, `GITHUB`, `GITLAB`, `GIT`, `MCP`, `SLACK`, `TEAMS`, `BUZZ`, `WEBHOOK` |
| `base_url` | The API root (a `GIT` system has none; `WEBHOOK` has none: Tinfirm is the endpoint) |
| `owner_principal_id` | `NULL` for a company system; the member's principal for a **personal MCP server** |
| `oauth_client_id`, `oauth_client_secret_id`, `oauth_scopes`, `oauth_metadata` | The system's **app**: one OAuth client per system. `oauth_metadata` holds the endpoints (a fixed profile per tracker kind; discovered for MCP) |
| `bot_account_id` | Chat kinds: the account that is the Tinfirm bot (§3.6), set in package 5 |
| `settings` | Kind-specific, validated per kind: Jira Cloud's site, an MCP server's auth header, the webhook's signing secret and mapping |
| `status`, `last_error`, `created_at`, `updated_at`, `created_by` | |

What a system offers follows from its kind:

| Kind | Events (package 4) | Tools | Repositories | Chat (package 5) |
|---|---|---|---|---|
| `JIRA_DC`, `JIRA_CLOUD` | Yes | Fixed catalogue | | |
| `GITHUB`, `GITLAB` | Yes | Fixed catalogue | Yes | |
| `GIT` | | | Yes | |
| `MCP` | | Its catalogue (§3.5) | | |
| `SLACK`, `TEAMS`, `BUZZ` | Messages | Fixed catalogue | | Yes |
| `WEBHOOK` | Yes | | | |

### 3.4 `system_repository`

A git repository of a `GITHUB`, `GITLAB` or `GIT` system: `id`, `company_id`, `system_id`, `url`, `name`, `default_branch`. Unique `(system_id, url)`. Added by URL; listing them from an account is later work.

### 3.5 `system_tool`

An MCP server's catalogue: `system_id`, `name`, `description`, `input_schema`, `effect` (`READ`, `WRITE`, `UNKNOWN`), `hint_read_only`, `hint_destructive` (as the server sent them), `confirmed_by`, `confirmed_at`, `enabled`. The fixed catalogues of trackers and chats are code, with their effects. **An `UNKNOWN` effect counts as `WRITE` everywhere** (Part 6). Refreshing the catalogue keeps confirmed effects; a tool whose schema changed loses its confirmation.

### 3.6 `account`

A principal's credential on a system, and who it is there.

| Column | Meaning |
|---|---|
| `id`, `company_id`, `system_id`, `principal_id` | |
| `kind` | `TOKEN`, `OAUTH`, or `LINK` (an identity without a credential: a chat user linked through the company's identity provider, package 5) |
| `secret_id` | The token or the OAuth grant. For a member, a secret **the member owns** (`secret.owner_user_id`); for a service identity, a company secret this account manages. `NULL` for `LINK` |
| `external_id`, `external_login` | The identity on the system, from its "who am I": a stable id (Jira user key or Atlassian account id, GitHub or GitLab numeric id, Slack user id, Entra object id) and a readable login. `NULL` where the system has no "who am I" (many MCP servers, `GIT`) |
| `api_base_url`, `scopes`, `access_expires_at` | Where the grant is valid (Jira Cloud's gateway), its scopes, its expiry |
| `status`, `last_error` | `OK`, `RECONNECT` (the system refused it or the grant expired: whoever manages it must connect again), `ERROR` |
| `connected_at`, `connected_by`, `last_used_at` | `connected_by` is the admin who connected a service identity's account |

### 3.7 `account_grant`

A service identity's account lent to a group: `id`, `company_id`, `account_id`, `group_id`, `access` (`READ` or `WRITE`), `created_at`, `created_by`. Unique `(account_id, group_id)`.

### 3.8 `oauth_authorization_request` (reworked)

A pending OAuth sign-in: `state` (32 random bytes, stored hashed), `code_verifier`, `company_id`, `system_id`, `principal_id` (whose account it becomes), `user_id` and `session_id` (the person in the browser: the member, or the admin connecting a service identity), `return_to` (a validated same-origin path: the draft, the thread or the page the person started from), `created_at`, `expires_at` (10 minutes). Consumed once by the callback.

## 4. Invariants

Each is enforced by the database where it can be (a constraint or a trigger), else by the service, and each has a test (§9).

| # | Invariant | Enforced by |
|---|---|---|
| I1 | One account per principal per system | Unique `(system_id, principal_id)` |
| I2 | One principal per identity per system: a Slack user is one member, a Jira user one principal | Unique `(system_id, external_id)` where `external_id IS NOT NULL` → `IDENTITY_IN_USE` |
| I3 | A member's account secret is owned by the member; it is never listed, revealed or changed through the Secrets API, and revealed only for a call that acts as that member | `secret.owner_user_id`; the vault's existing rules |
| I4 | Only the member manages their own accounts; only OWNER and ADMIN manage a service identity's. Others see whether a member holds an account and its status, never more | Service |
| I5 | A grant lends only a `SERVICE` principal's account, on a company system | Trigger on `account_grant` → `GRANT_NOT_SERVICE` |
| I6 | A personal system is an `MCP` server; only its owner holds an account on it; it is never granted | Check `owner_principal_id IS NULL OR kind = 'MCP'`; service checks → `PERSONAL_SYSTEM` |
| I7 | A `SUSPENDED` or `LEFT` principal's accounts are used for nothing: no call, no poll, no git, no bot | The binding rule (§5) and every use path |
| I8 | A member who leaves: the principal becomes `LEFT`; their accounts, owned secrets and personal systems are deleted, in the transaction that ends the membership | Service, in the membership's removal |
| I9 | A chat system has at most one bot, an account of a `SERVICE` principal on that system | Foreign key and trigger on `bot_account_id` |
| I10 | Nothing falls back: an account that is `RECONNECT` or `ERROR` is never silently replaced by another | The binding rule (§5) |

## 5. The binding rule

One function, `Accounts.bind(context, systemId)`, called by package 3 when a conversation first needs a system and by package 4 when a trigger is saved. It returns a binding or a reason, and stores nothing itself: the conversation or the trigger keeps what it returns.

| Context | Returns |
|---|---|
| A **personal** conversation of member *M* | *M*'s own account if `OK` → `Own(account)`. Else, if *M* holds no own account: the grants to *M*'s groups on the system whose account is `OK` and whose principal is `ACTIVE`: one → `Granted(grant)`, several → `Choose(grants)` (the person picks). Else `ConnectRequired(system, reason)`: `NONE` (connect your own), `RECONNECT_YOURS` (your account needs signing in again), `RECONNECT_COMPANY` (the granted account needs an admin; admins are told), `CHOOSE` (your own is broken and a grant exists: reconnect, or use the grant) |
| A **trigger** saved by author *A*, row for the system naming account *X* | `Fixed(X)` when *X* is *A*'s own, or a service identity's that *A* may use (§3.2), and *X* is `OK`; else `NotEntitled` or `ConnectRequired` |

- A personal system binds only for its owner's own conversations and triggers.
- A binding is checked again at every use (the principal `ACTIVE`, the grant still there, the person still in the group, the account `OK`), and a failed check stops the use with the reason; it never re-binds.

## 6. API

Under `/api/companies/{companyId}`, OpenAPI-first ([ADR-0013](../adr/0013-openapi-first-contract.md)). "Admins" means OWNER and ADMIN.

| Endpoint | Who | Does |
|---|---|---|
| `GET /members`, `PATCH /members/{userId}` | Members read; admins change roles | Roles `OWNER`, `ADMIN`, `MEMBER` |
| `GET/POST/PATCH/DELETE /groups…` | As today | Groups and their members |
| `GET /service-identities` | Every member | Names, descriptions, status, and whether the caller may use each |
| `POST /service-identities`, `PATCH /service-identities/{id}`, `DELETE …/{id}` | Admins | Create, rename, describe, delete (refused while triggers use it, package 4) |
| `PUT /service-identities/{id}/users` | Admins | The groups that may use it |
| `POST /service-identities/{id}/suspend`, `…/resume` | Admins | Kill switch (I7) |
| `GET /systems`, `GET /systems/{id}` | Every member | Company systems, and the caller's own personal ones |
| `POST /systems`, `PATCH /systems/{id}`, `DELETE /systems/{id}` | Admins (company); any member for their own `MCP` server | Register, change, remove |
| `PUT /systems/{id}/app` | Admins (the owner, for a personal one) | The OAuth client; the callback URL to register is shown |
| `POST /systems/{id}/test` | As for changing it | Reaches the system without a credential, or with the caller's own account |
| `GET/POST/DELETE /systems/{id}/repositories…` | Members read; admins change | Code hosts' repositories |
| `POST /systems/{id}/tools/refresh`, `PATCH /systems/{id}/tools/{name}` | Admins (the owner, for a personal one) | Read the MCP catalogue; confirm a tool's effect, enable or disable it |
| `GET /me/accounts` | The member | Per system: the own account and its status, else the grant the member would act with, else how to connect |
| `PUT /me/accounts/{systemId}/token` | The member | Store a token: checked with the system's "who am I", refused if the system refuses it |
| `POST /me/accounts/{systemId}/oauth` | The member | Start a sign-in with `returnTo`; answers the authorization URL |
| `DELETE /me/accounts/{systemId}` | The member | Disconnect (revokes the grant where the system offers revocation) |
| `PUT/POST/DELETE /service-identities/{id}/accounts/{systemId}…` | Admins | The same three for a service identity's account |
| `GET /grants`, `POST /grants`, `PATCH /grants/{id}`, `DELETE /grants/{id}` | Admins | Lend an account to a group, `READ` or `WRITE` |
| `GET /api/oauth/callback` | The browser | One callback for every system; consumes the request (§3.8), stores the account, returns to `returnTo` |

## 7. Errors

`DUPLICATE_SYSTEM_NAME`, `DUPLICATE_SYSTEM_HANDLE`, `DUPLICATE_SERVICE_IDENTITY_NAME` (409); `INVALID_BASE_URL`, `SYSTEM_KIND_INVALID` (400); `ACCOUNT_REFUSED` (the system refused the token, 400); `IDENTITY_IN_USE` (I2, 409); `GRANT_NOT_SERVICE` (I5, 400); `PERSONAL_SYSTEM` (I6, 400); `PRINCIPAL_SUSPENDED` (409); `OAUTH_NOT_AVAILABLE` (the system has no app, 409); `NOT_FOUND` for anything the caller may not see (404, never 403, as today).

## 8. Audit actions

`principal.created`, `principal.suspended`, `principal.resumed`, `principal.left`; `service_identity.users_changed`; `system.created`, `system.changed`, `system.deleted`, `system.app_changed`, `system.tool_effect_confirmed`; `repository.added`, `repository.removed`; `account.connected`, `account.reconnected`, `account.disconnected`, `account.refused` (with the system's reason, never a token); `grant.created`, `grant.changed`, `grant.deleted`; `secret.accessed` with the purpose of each reveal, as today. A member's own account is audited with the member's id; its secret never appears.

## 9. Tests

| Test | Proves |
|---|---|
| Constraint tests per invariant I1, I2, I5, I6, I9 against PostgreSQL | The database refuses what it can |
| `Accounts.bind` table test: own `OK`; own `RECONNECT` with and without a grant; one, two grants; a grant whose principal is suspended; a person who left the group; a personal system for its owner and for another member; a trigger naming its author's own account, a usable service identity's, an unusable one's | §5, I7, I10 |
| A member leaves | I8: principal `LEFT`, accounts, owned secrets and personal systems gone, audit written |
| OAuth: state reuse, another session, expiry, a `returnTo` to another origin | §3.8 |
| Tenant isolation over every new path, and the role checks of §6 | As today's suites |
| Redesign scenarios V5 (an analyst through a `READ` grant) and V6 (connecting one's own account mid-conversation keeps the binding) | As binding tests now, as end-to-end tests with package 3 |

## 10. Decided on review (2026-10-12)

- **Personal MCP servers** can be switched off per company: a company setting, on by default. Switching it off keeps existing personal servers unusable (they bind for nobody) until it is on again, and `POST /systems` of a personal server answers `PERSONAL_SYSTEMS_OFF` (409).
- **My accounts lists every usable grant** for a system, the default first, so the choice a conversation asks for is no surprise.
- **A member who rejoins gets a new principal.** Old conversations keep naming the old one, and nothing of before comes back: accounts, personal systems and triggers stay gone.
