# Accounts, credentials and service accounts

The public guides are [Connect your accounts](../../docs-site/content/user-guide/accounts.md) (https://docs.tinfirm.com/user-guide/accounts/), for every member; [Register systems and lend their credentials](../../docs-site/content/admin-guide/systems.md) (https://docs.tinfirm.com/admin-guide/systems/), for Owners and Admins, with which account a conversation acts with; and [Service accounts](../../docs-site/content/admin-guide/service-accounts.md) (https://docs.tinfirm.com/admin-guide/service-accounts/), for Owners and Admins. This page keeps what the people who build Tinfirm need beyond them: the design and its risks, the names the API and the code use, states and problem codes, how a conversation's binding and a trigger's accounts table are checked, the sign-in callback's codes, Tinfirm's client metadata document, the audit events, and the troubleshooting that needs them.

The decisions are [ADR-0058](../adr/0058-authority-from-the-audience.md) and [ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md); the rules are [CONVENTIONS §15.1](../CONVENTIONS.md#151-principals) and [§15.3](../CONVENTIONS.md#153-accounts-and-grants); the risks are rows A29, 8.35, 8.36 and 8.44 of the [threat model](../threat-model.md). Registering a system and setting up its Sign-in app is in [systems.md](systems.md).

## 1. Who acts at a system

Who acts where, and the words account, credential, lending and service account, are in [Concepts](../../docs-site/content/concepts.md#accounts-and-credentials). In the API and the code:

- A **personal conversation** is one in the app, from an AI client ([mcp-clients.md](mcp-clients.md)), or in a direct message to the chat bot ([chats.md](chats.md)). It acts with the person's own account, else a credential lent to one of their groups (section 5). A trigger's conversation acts with what its table names (section 6). On an MCP server set to need no account, every conversation calls without a credential ([mcp-servers.md, section 2.1](mcp-servers.md#21-needs-no-account)).
- An **account** is a member's own credential on a system, one per member per system, kept as a vault secret only they own (`MEMBER_ACCOUNT`).
- A **credential** is the company's, a managed company secret (`SYSTEM_CREDENTIAL`); a system may hold several ([systems.md, section 4](systems.md#4-credentials)).
- To **lend** a credential is a **grant** to a group (read-only or read and write) or a **lending** to a service account (at most one credential per system).
- A **service account** is a **service identity** in the API and the code (`/service-identities`, `serviceIdentity`).
- A conversation's **binding** on a system is `OWN`, `GRANTED` or `CHOSEN` in a personal conversation, `FIXED` from a trigger's table, or `OPEN` on a server that needs no account ([ADR-0071](../adr/0071-mcp-servers-without-accounts.md)).

## 2. Your own account

What a member sees and does on **Accounts** is in [Connect your accounts](../../docs-site/content/user-guide/accounts.md). Inside:

- **The page** is every member's; an instance admin who is not a member of the company has none. A webhook system holds no one's account and is not listed. `GET /me/accounts` answers each system with how a new conversation would bind there (`OWN`, `GRANTED`, `CHOOSE`, `CONNECT`, … and `NOT_NEEDED` on a server that needs no account).
- **A token** is checked before it is kept, with the "who am I" of [systems.md, section 2](systems.md#2-base-urls-and-tokens): Jira's `myself`, GitHub's and GitLab's `user`, an MCP server's `initialize`; a git host's is taken as it is. A refusal is `400 ACCOUNT_REFUSED`, audited as `account.refused`, with the system's answer and never the token. A token is at most 4000 characters. Pasting a token again replaces the account (`account.reconnected`).
- **A sign-in** belongs to the browser session and expires after 10 minutes. Tinfirm keeps the access token and the refresh token, and refreshes the access token before it expires. The default scopes are in [systems.md, section 3](systems.md#3-the-sign-in-app).
- **Signing in again or a new token** keeps the conversations bound to the account going: Tinfirm finds the account again by its holder and checks it is the same user at the system.
- **Disconnect** deletes the credential and revokes a sign-in where the system offers revocation: GitLab, Slack, and MCP servers whose authorization server publishes a revocation endpoint. The audit records whether the revocation was confirmed (`revoked`).
- **Status** is `OK`, `RECONNECT` or `ERROR` (the API's `status`). An account turns `RECONNECT` when the system refuses it on a call (a `401`), refuses to refresh a sign-in, or a sign-in expired without a refresh token. Nothing falls back from an account that is not `OK`: a conversation bound to it waits.
- **The secret** (`MEMBER_ACCOUNT`) is not on the **Secrets** page, no API shows it, and it is revealed only for a call that acts as its holder, each reveal audited as `secret.accessed` with its purpose. Owners and admins see on **Members** whether a member holds an account on each company system and its status, never the login, the scopes or the credential. When a member leaves, their accounts go with their membership (`account.disconnected` with the `cause` `principal.left`); when a system is removed, its accounts go with it (`system.deleted`).

## 3. Credentials on a system

Adding, lending, storing again, disabling and removing credentials are in the public guide's [Add a credential](../../docs-site/content/admin-guide/systems.md#add-a-credential) and [Lend a credential](../../docs-site/content/admin-guide/systems.md#lend-a-credential); their codes, audit events and API in [systems.md, section 4](systems.md#4-credentials). Beyond those:

- A chat system's bot is set, replaced and removed under **Bot** only: the credential endpoints refuse it (`409 BOT_ACCOUNT_IN_USE`).
- When a service account's credential is replaced, the authors of its triggers are told in their inbox, unless they lent it themselves (section 6).
- A group's dialog lists the credentials lent to it, under *What its members may use through it*.
- **Test** and **Refresh tools** take a `credentialId`; without one they use the caller's own working account, else the system's only working credential, else none.
- On Jira Cloud a credential may be an Atlassian service account's OAuth 2.0 credential (kind `CLIENT`, [ADR-0093](../adr/0093-jira-cloud-service-accounts-by-client-credentials.md)): `POST …/credentials/client` and `PUT …/credentials/{credentialId}/client`. Its secret holds the client's id and secret and the access token it minted last; a reveal mints the next one (`client_credentials` at `https://auth.atlassian.com/oauth/token`) once that one expires within a minute, under the refresh lock a sign-in's refresh takes. A refused mint (`invalid_client`, `access_denied`, `unauthorized_client`) turns it `RECONNECT`, audited as `account.refused`; another failure keeps it `OK`. It calls Jira at the gateway it connected with, `https://api.atlassian.com/ex/jira/{cloudId}`, and **Test** asks `myself` there. Nothing is revoked when it is removed: revoke the credential in Atlassian Administration.

## 4. Service accounts

Creating one, its page, who may use it, its budget, suspending and deleting it are in [Service accounts](../../docs-site/content/admin-guide/service-accounts.md). Inside:

- **Who may use it** is the groups whose members may pick it, its borrowed credentials and its budget, for what they set up; the Owner always may. **Change** replaces the groups, at most 100. Audited as `service_identity.users_changed`.
- **Budget**: its own key at the company's model gateway, never shared, with its budget under **Model access**, **Members' budgets** ([litellm.md](litellm.md)).
- **Suspend** blocks its model key, makes the credentials it borrows act for nothing through it, and cancels the turns running as it or on its budget; groups that borrow the same credentials go on. Its triggers do not all pause (`TriggerGate`): those whose account rows act through it pause (`ACCOUNT_BROKEN`) and stay paused until a person resumes them; those it only pays for wait (`SUSPENDED`, from `ModelAccess.forRun`'s `PRINCIPAL_INACTIVE`) and go on by themselves once it is resumed. A paused trigger never resumes by itself; a waiting one does. Audited as `principal.suspended` and `principal.resumed`; a suspended service account is listed on **Kill switches**.
- **Delete** withdraws the credentials it borrows, which stay with their systems. It is refused while a live trigger acts through it or is paid by it, or while it borrows a chat bot (`409 SERVICE_IDENTITY_IN_USE`). Audited as `principal.deleted`.

## 5. How a personal conversation picks an account

The rule, and the cards a conversation shows while it waits, are in [Which account a conversation acts with](../../docs-site/content/admin-guide/systems.md#which-account-a-conversation-acts-with) and [How a conversation picks an account](../../docs-site/content/user-guide/accounts.md#how-a-conversation-picks-an-account). Inside:

- The binding is made at the system's first use: the first call of one of its tools, or the first turn with one of its repositories in the workspace. It is the person's own account when it is `OK` (`OWN`); else, when they hold **no account of their own** there, the grants to their groups whose credentials are `OK` and enabled, read and write before read-only, then the oldest grant first: one binds (`GRANTED`); with several the person picks one (`CHOSEN`). A credential lent to two of their groups counts once. Else nothing binds, and the conversation shows a card (`ConnectRequired`).
- An own account that is broken is never passed over silently for a grant. The binding is kept for the conversation's life, checked at every use, and never switches. Audited as `conversation.binding.bound`, `.waiting` and `.gone`.
- From an AI client, `choose_account` picks a grant for a waiting binding ([mcp-clients.md](mcp-clients.md)); connecting an account is done in the app.
- A binding ends for good (`conversation.binding.gone`; a call through it answers `ACCOUNT_GONE`) when the grant is withdrawn, the person leaves its group, the account signs in as another user, its holder leaves, or the system is removed.

### 5.1 What a read-only credential prevents

A credential lent read-only, to a group or to a service account, writes nothing through Tinfirm, whatever the agent and the settings allow; a person's own account is always read and write.

- Every tool that writes is refused (`TOOL_DENIED`, naming the read-only grant). A tool of an MCP server whose effect nobody confirmed counts as a write.
- The repositories of that system are read-only in the workspace: nothing is pushed, so no change request is opened.
- In a trigger's editor, the system's write tools are not counted among the trigger's open writes.

## 6. How a trigger's accounts table works

What a trigger's author chooses is in the public guide's [Use it on a trigger](../../docs-site/content/admin-guide/service-accounts.md#use-it-on-a-trigger). Inside:

Each row names the author's own account on the system, or a service account and the credential lent to it there. The table is checked when the trigger is saved, before every firing and at every use (`TriggerGate`). Nothing in it switches by itself, with one exception (`TriggerLendings`): when an admin lends a service account another credential in place of the one it borrowed, each live trigger whose row acted through it names the new one in the same transaction (`trigger.accounts_changed` with `why` `lending.replaced`), its poll follows, each open conversation binds it at its next check and says so in the thread (*Jira: svc-dev's Second now, in place of First: an admin lent it to svc-dev*), and the author is told in their inbox unless they lent it themselves. When the new credential is a chat bot, the trigger keeps its row and pauses as for a withdrawn one. Rotating a token or signing a credential in again changes nothing: the credential keeps its id.

| What happened | The trigger |
|---|---|
| The credential was withdrawn from the service account, or the author may no longer use the service account | Pauses (`NOT_ENTITLED`) until the author names another account |
| A row's account or credential must be signed in again, given a new token or enabled; or a row's service account is suspended | Pauses (`ACCOUNT_BROKEN`) until a person resumes it |
| A row's account was disconnected, or is another user at the system now | Pauses (`ACCOUNT_REMOVED`) |
| A system a row names, not its source, was removed | Pauses (`SYSTEM_REMOVED`), the row removed ([systems.md, section 8](systems.md#8-pause-resume-and-remove)) |
| The service account that only pays for it is suspended | Waits (`SUSPENDED`), and goes on by itself once the service account is resumed |
| The author left the company | Pauses (`AUTHOR_LEFT`) until an admin hands it to another author |

## 7. One identity per system

A user at a system belongs either to members' own accounts or to the company's credentials, never both, and to at most one member. What people read is on the public pages; the codes:

- A member whose token or sign-in is another member's user is refused (`409 IDENTITY_IN_USE`, `details.holder` `MEMBER`). The other member is never named.
- A member whose token or sign-in is the user of one of the system's credentials is refused with the credential's name (`details.holder` `CREDENTIAL`, `details.credentialName`): *Ask an owner or admin to give the credential another user, such as a machine user; then connect yours.* A sign-in comes back with `identity_shared`.
- A credential whose user is a member's own account is refused (`details.holder` `MEMBER`): add it with a user of the company's own.
- Two credentials of one system may share a user, such as two tokens of one bot user with different scopes.

The rule applies where the system says who the account is: Jira Data Center, Jira Cloud, GitHub and GitLab (and Slack, [chats.md](chats.md)). A git host's token and an MCP server's token carry no identity Tinfirm reads.

## 8. When a sign-in comes back

Every sign-in, a member's or a credential's, comes back through one callback, `{TINFIRM_PUBLIC_URL}/api/oauth/callback`, to the page it started from, with `?account=connected&system={systemId}` or `?account={reason}`. What each says and what to do are in [Connect your accounts](../../docs-site/content/user-guide/accounts.md#when-something-goes-wrong) and the public systems guide's [Troubleshooting](../../docs-site/content/admin-guide/systems.md#troubleshooting).

| `account` | The page says |
|---|---|
| `connected` | On Accounts, *{system} is connected. Agents act there as you: {login}.*; on a system's page, *The credential is connected. Lend it to a group or a service account.* |
| `denied` | *You declined at the system, so nothing was connected.* |
| `invalid_state` | No message: the sign-in was unknown, used already, or another session's, and the browser lands on the start page (`/`) |
| `expired` | *The sign-in took longer than ten minutes and expired. Start it again.* |
| `exchange_failed` | *The system did not hand over the account. Try again, or paste a token instead.* |
| `refused` | *The system refused the account.* |
| `app_refused` | *The system refused its sign-in app: the app's client ID or secret is not the one the system knows. Ask whoever manages the system to set up its app again.*: the token endpoint answered `invalid_client` or `unauthorized_client`. The `account.refused` audit event's `reason` names the error, and for Slack its own code too, such as *The token endpoint answered 200 (invalid_client, Slack's bad_client_secret)* |
| `site_not_granted` | *The sign-in did not include this Jira site. Try again, and choose that site when you allow Tinfirm at Atlassian.* |
| `identity_in_use` | *That account on the system already belongs to someone else in this company.* |
| `identity_shared` | *That account on the system is one of the company's credentials there, which groups use as a shared account. Ask an owner or admin to give the credential another user, such as a machine user; then connect yours.* |
| `name_taken` | *Another credential of the system took this name in the meantime, so nothing was added. Add it again under another name.* (a new credential only) |
| `already_linked` | *You are linked to another Slack user here. Sign in as that user instead.* (Slack only, [chats.md](chats.md)) |
| `system_moved` | *The system moved to another endpoint while you signed in, so nothing was connected. Connect it again.* (an MCP server, [ADR-0079](../adr/0079-an-mcp-servers-endpoint-changes.md)) |
| `forbidden` | *You may no longer connect this account.*: the person was suspended, lost the right to manage the system, or personal MCP servers were switched off |

## 9. Tinfirm's client metadata document

Tinfirm publishes its own OAuth client metadata at `{TINFIRM_PUBLIC_URL}/oauth/client-metadata.json`. An MCP server's authorization server that supports it (`client_id_metadata_document_supported`) takes Tinfirm as a client by this document, and nobody registers an app there:

| Member | Value |
|---|---|
| `client_id` | The document's own URL |
| `client_name` | `Tinfirm` |
| `client_uri` | `{TINFIRM_PUBLIC_URL}` |
| `redirect_uris` | `{TINFIRM_PUBLIC_URL}/api/oauth/callback` |
| `grant_types` | `authorization_code`, `refresh_token` |
| `response_types` | `code` |
| `token_endpoint_auth_method` | `none` (a public client, with PKCE) |

The document is public and may be cached for an hour. The authorization server fetches it, so it must reach your instance's public URL; a server that cannot is set up with dynamic registration or an app an admin registers ([mcp-servers.md](mcp-servers.md)). Changing `TINFIRM_PUBLIC_URL` changes the callback and the document's `client_id`: register the new callback at every system's app, and expect people to sign in again. It is not `/oauth2/**`, which is Tinfirm's own authorization server for people's AI clients ([mcp-clients.md](mcp-clients.md)).

## 10. Audit

Nothing in the audit log holds a token.

| Action | When |
|---|---|
| `account.connected`, `account.reconnected` | A member connected or replaced their own account (`principalId`, `userId`), or an admin added or stored again a credential (`credential`, its name); with the `kind`. A refresh that made an account work again is `account.reconnected` with `refreshed` |
| `account.disconnected` | An account or a credential went, with `revoked` when its sign-in was revoked at the system, and a `cause` when it went with something else (such as `principal.left`, `system.deleted`, `system.bot_removed`) |
| `account.refused` | The system refused a token or a sign-in being connected, or a stored account's refresh or call, which turned it `RECONNECT`; with the system's reason |
| `credential.changed` | A credential renamed, disabled or enabled |
| `grant.created`, `grant.changed`, `grant.deleted` | A credential lent to a group, its access changed, the grant withdrawn |
| `credential.lent`, `credential.withdrawn` | A credential lent to a service account or its access changed (with the credential it `replaced`), or withdrawn from it |
| `principal.created`, `principal.changed`, `principal.deleted` | A service account created, renamed or described, deleted |
| `principal.suspended`, `principal.resumed` | A service account suspended or resumed |
| `service_identity.users_changed` | The groups that may use a service account replaced |
| `trigger.accounts_changed` | A trigger's table changed, with `why` `lending.replaced` when it followed a service account |
| `conversation.binding.bound`, `.waiting`, `.gone` | A conversation's binding bound (with the credential it `followed`, for a trigger's), waits, or ended |
| `tool.called` | Each call that reached a system, with the account, its holder and the grant it went through |
| `secret.accessed` | Each reveal of a credential, with its purpose |

## 11. Troubleshooting

The public guides' tables cover what people see. What the internals add:

| What you see | Inside |
|---|---|
| A write is refused although the agent allows it | `TOOL_DENIED` names the read-only grant the conversation is bound through (section 5.1) |
| A conversation waits with *{system} waits for an admin* | `conversation.binding.waiting`: the lent credential is disabled or `RECONNECT`, or its service account is suspended. The admins' inbox item says which (*A credential of {system} needs signing in again*, *… is disabled*, *… needs a new token*) |
| *{system} can no longer be used in this conversation* | `conversation.binding.gone`; a call through it answers `ACCOUNT_GONE` (section 5) |
| "That account on the system is one of the company's credentials" | `identity_shared`, or `409 IDENTITY_IN_USE` with `details.holder` `CREDENTIAL` (section 7) |
| A trigger paused after an admin changed credentials | Its reason: `NOT_ENTITLED` (withdrawn) or `ACCOUNT_BROKEN` (disabled or refused); a suspended payer makes it wait instead (section 6) |
| **Add credential** is greyed out | `409 SYSTEM_PAUSED` |
| Deleting a service account is refused | `409 SERVICE_IDENTITY_IN_USE`: a live trigger acts through it or is paid by it, or it borrows a chat bot |
