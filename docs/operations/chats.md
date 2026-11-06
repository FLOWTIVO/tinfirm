# Chats: Slack and Microsoft Teams

The public guides are [Talk to agents in Slack and Microsoft Teams](../../docs-site/content/user-guide/chats.md) (https://docs.tinfirm.com/user-guide/chats/), for members, and [Set up Slack and Microsoft Teams](../../docs-site/content/admin-guide/chat-systems.md) (https://docs.tinfirm.com/admin-guide/chat-systems/), for Owners and Admins. This page keeps what the people who build Tinfirm need beyond them: the design and its rules, the endpoints, payloads, error codes and audit events, the manifest's and the Bot Framework's details, how a long answer is split, the reasons behind what the bot refuses, and the diagnosis from metrics and logs.

The design is the [package 5 contract](../reviews/2026-10-12-package-5-contract.md), with its open points as [decided on review](../reviews/2026-10-12-package-5-contract.md#11-decided-on-review); the binding rules are [CONVENTIONS §15.16](../CONVENTIONS.md#1516-package-5a-chat-systems-and-direct-messages) and [§15.17](../CONVENTIONS.md#1517-package-5b-channel-triggers-the-chat-outbox-and-the-webhook). Buzz is parked: it is listed as a kind of system, but cannot be registered.

## 1. Register a chat system

Owners and admins register and change chat systems (`MANAGE_SYSTEMS`), one per Slack workspace or Teams tenant in a company. Their addresses are fixed (Slack's Web API, the Bot Framework's connector), so a base URL given is refused (`400 SYSTEM_KIND_INVALID`). `GET /systems/{systemId}` answers a chat system's settings and whether its bot and secrets are set; to owners and admins also the URLs the platform sends to and the bot's service account.

### 1.1 Slack

- **Register**: `POST /systems` with `kind` `SLACK` and `slack: {transport: EVENTS_API | SOCKET_MODE}`. With the Events API, Slack posts events and button presses to `/api/chat/slack/…`, signed with the app's signing secret. With Socket Mode, the `worker` replicas open the connections with an `xapp-` app-level token that has `connections:write`.
- **The manifest** (`GET /systems/{systemId}/chat/manifest`) asks for the bot scopes the chat needs and no history scope for the bot, the events of direct messages, mentions and channels (never `message.channels`), interactivity for the buttons, the App Home's messages tab, and the user scopes `channels:history` and `groups:history` for Slack's `history` tool (section 8). Its URLs are made from `TINFIRM_PUBLIC_URL`.
- **The secrets**: `PUT /systems/{systemId}/chat/secrets` with `signingSecret` or `appToken`, kept in the vault; audited as `system.changed`.
- **The sign-in app**, for `history` only: `PUT /systems/{systemId}/app`.

### 1.2 Microsoft Teams

The Bot Framework's token proves the connector, not the activity, and stays valid for about an hour, so the TLS endpoint of Tinfirm's public URL must sit directly in front of the server, with no plaintext hop between them (the [package 5 contract, §5.2](../reviews/2026-10-12-package-5-contract.md#52-receiving-verification-acknowledgement-idempotency-ordering-limits)).

- **Register**: `POST /systems` with `teams: {tenantId, appId}`. A second system of the same tenant is refused (`409 CHAT_WORKSPACE_TAKEN`).
- **The messaging endpoint** is `{TINFIRM_PUBLIC_URL}/api/chat/teams/{systemId}/messages`.
- **The app package** is `GET /systems/{systemId}/chat/manifest`, a zip.

## 2. The bot

The bot is a credential of the chat system, named `Bot` (`Bot 2` when that name is taken), that a service account borrows ([ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md)).

**Set** with `PUT /systems/{systemId}/bot` (`principalId` and `token`). Tinfirm checks the token with the platform before it keeps it: Slack's `auth.test`, whose workspace becomes the system's and stays fixed from then on, or for Teams a token from Microsoft's login for the app. Audited as `system.bot_set`. Only the service account that borrows it replaces the token. **Remove** is `DELETE /systems/{systemId}/bot`, audited as `system.bot_removed`.

| Refused | When |
|---|---|
| `400 VALIDATION_FAILED` | No token, or a Slack token that does not start with `xoxb-` |
| `400 ACCOUNT_REFUSED` | The platform refused the token |
| `400 BOT_IDENTITY_MISMATCH` | A Slack token of another workspace than the system's, or of another bot user than its bot |
| `409 BOT_ACCOUNT_IN_USE` | Another service account borrows the bot |
| `409 PRINCIPAL_SUSPENDED` | The service account is suspended |
| `409 IDENTITY_IN_USE` | A member's own account on the system already is this identity |
| `409 SYSTEM_PAUSED` | The system is paused; removing the bot still works |

**What refuses the bot.** It carries messages and nothing else:

| Attempt | Answer |
|---|---|
| Lending it to a group | `400 BOT_ACCOUNT` |
| Naming it in a trigger's accounts table | `400 BOT_ACCOUNT` |
| Binding it in a conversation, or calling a tool with it (`history` included) | Never done: a binding refuses it |
| Renaming, disabling, signing in again, removing or lending it among the system's credentials, or withdrawing it from its service account | `409 BOT_ACCOUNT_IN_USE`: it changes only on the **Bot** tab |
| Lending its service account another credential on the same system | `409 BOT_ACCOUNT_IN_USE` |
| Deleting its service account | `409 SERVICE_IDENTITY_IN_USE` |

## 3. Linking chat users to members

A link joins one chat user to one member on one system, by Slack's user id or, on Teams, the user's Entra object id; never by an email or a name.

- **Automatically, on Teams.** At a Teams user's first message, Tinfirm links them when their Entra object id and the system's tenant match exactly one active member of the company whose last Tinfirm login went through that tenant's Entra ID v2.0 issuer (`https://login.microsoftonline.com/{tenant}/v2.0`), one of the instance's providers ([entra-id.md](entra-id.md)). Only the `tid` and `oid` of the login's ID token count. A provider registered with Entra's v1.0 endpoint links nothing automatically; its users link by the sign-in link.
- **By a sign-in link**: `{TINFIRM_PUBLIC_URL}/link/{companyId}`, for that chat user only. The page reads it with `POST /chat-links/read`, which leaves it unused and, outside any transaction, asks the platform who the chat user is now: Slack's `users.info` with the bot's token (`platformName`, its real name; `avatar`, the picture from Slack's avatar host as a `data:` URL of at most 64 KiB), on Teams the Entra name stored from the Bot Framework's roster (`platformChecked` false when it could not be asked). `POST /chat-links/confirm` uses it up, whatever the outcome; the messages that waited for it (`AWAITING_LINK`, at most 5) are not released but end `IGNORED UNLINKED`, and the bot's "Linked to" message asks the chat user to send them again (the security review of 2026-10-27, L3). A member's own Slack sign-in, which proves the Slack account, still releases them.
- **By signing in to Slack** with the system's sign-in app: the sign-in replaces a link of the same Slack user, and is refused as another Slack user than the linked one.

A link is audited as `account.connected`, with `how` `AUTOMATIC` or `SIGN_IN_LINK`. The link page's messages answer these codes:

| The link page says | Code |
|---|---|
| "This link has expired" | `410 CHAT_LINK_EXPIRED` |
| "This link was already used" | `409 CHAT_LINK_USED` |
| "This chat user is someone else’s" | `409 IDENTITY_IN_USE` |
| "You are linked to another chat user" | `409 ALREADY_LINKED` |
| "This link is not for this account" | `404` |

**Unlinking.** The member: `DELETE /me/accounts/{systemId}`. An admin: `DELETE /members/{userId}/accounts/{systemId}` (`400 NOT_A_CHAT_ACCOUNT` for another kind of system), audited as `account.disconnected` with `cause` `admin`. On Teams the automatic link applies again at the chat user's next message.

## 4. Direct messages

- **The default agent**: `PUT /me/chat` with `defaultAgentId`, an agent the member uses (`403 AGENT_NOT_USABLE` otherwise).
- **A Viewer is refused** (`DirectMessages`, [ADR-0077](../adr/0077-licence-editions-and-seats.md) decision 5: a Viewer starts and continues no conversation, here neither). The check runs once the chat user is linked, so a Viewer is offered a sign-in link like anyone else, and every direct message after that is answered "You are a Viewer in this company: you read what it shares with you in Tinfirm, and start or continue no conversation with its agents. An admin can change your role." (outcome `VIEWER_READ_ONLY`).
- **Another member's conversation**: a message in its thread, or a press of its **End**, is refused with "This conversation is another member's." (`NOT_YOURS`).
- **From the app**, a chat conversation takes answers to its open questions only (`409 PLACE_IS_EXTERNAL` for another message): the chat stays its one place.
- **A long answer** is split by `ChatMarkdown.split` into parts that fit the platform: each cut at a blank line, else at a line break, else hard. A part may be cut inside a code fence: the fence is then closed at the end of the part and opened again (without its language) at the start of the next. Parts are marked (1/3), (2/3)…; an answer that fits is one part without a mark. With more than `ChatMarkdown.MAX_PARTS` (4) parts, `ChatPoster` posts the first and then the whole answer as `answer.md` (Slack) or a link to it in Tinfirm (Teams).

## 5. Channel triggers

The chat adds these checks to a channel trigger's save ([triggers.md](triggers.md), section 3.3), made for the trigger's author whoever edits it:

| Check | Refused |
|---|---|
| The system has a bot | `409 BOT_NOT_SET` |
| Each channel (1 to 20) is one the bot is in, not archived, deleted or shared with another organisation | `400 CHANNEL_UNAVAILABLE` |
| The author is linked on the system and a member of each channel | `400 CHANNEL_NOT_JOINED` |
| One trigger per agent per channel, and one default trigger per channel | `409 CHANNEL_TAKEN` |

`GET /systems/{systemId}/chat/channels` lists the channels the bot is in: public ones, and private ones to a linked member who is in them. A channel trigger counts against the system's 100 live triggers (`409 TOO_MANY_TRIGGERS`). Run now is refused (`400 VALIDATION_FAILED`): a mention fires it. When the agent would write without an approval, with the author's own account or a lent credential, saving needs the open-writes acknowledgement (`OPEN_EVENTS_NOT_ACKNOWLEDGED`; [triggers.md](triggers.md), section 5.3).

**When the bot cannot be used** (removed, its token refused, its service account suspended), the system's channel triggers wait (`BOT_MISSING`, `BOT_RECONNECT`, `BOT_SUSPENDED`), and a trigger whose channels are all unavailable waits `CHANNEL_UNAVAILABLE`; mentions in between are dropped and never answered later. This is a deliberate exception to the rule that an account a trigger cannot use pauses it until someone resumes it: the bot carries no authority ([package 5 contract, open point 21](../reviews/2026-10-12-package-5-contract.md#10-open-points)).

## 6. Questions and approvals

A refused press is told to the presser alone (`ChatInbox.tell`: an ephemeral message on Slack, a direct message on Teams), and the decision stays open. A decision taken by a card's button records `via` `CHAT` and the presser's chat user.

## 7. Files

Slack files of a direct message are downloaded with the bot's token, then go through the conversation's limits and the upload's scan like any upload.

## 8. Slack's `history` tool

`history` returns, oldest first, the time, author and text of each message (cut at 4,000 characters), at most 100 a call and 20 by default, with a cursor for the next page. Allowing it on an agent is as for any system ([agent-permissions.md](agent-permissions.md)). It reads with the conversation's own account on Slack, never with the bot's token: a chat link alone is no account, and the conversation asks the person to sign in to Slack. In a channel trigger's conversation, any thread or channel other than the one the agent was asked in is refused (`TOOL_DENIED`) before Slack is called, since anyone in the channel may ask.

## 9. Limits and rates

The company's chat rates are `GET`, `PUT /chat-settings`, audited as `chat.settings_changed`. They carry a `version`: a change names the version it read, and one made by someone else meanwhile is refused (`409 SETTINGS_CHANGED`) rather than written over.

| Setting | API field | Seeded from |
|---|---|---|
| Messages a minute, per chat user | `messagesPerUserPerMinute` | `TINFIRM_CHAT_MESSAGES_PER_USER_MINUTE` |
| Messages a day, per chat user | `messagesPerUserPerDay` | `TINFIRM_CHAT_MESSAGES_PER_USER_DAY` |
| Events a minute, per chat system | `eventsPerSystemPerMinute` | `TINFIRM_CHAT_EVENTS_PER_SYSTEM_MINUTE` |

They are counted after an event is verified and before it is stored, in memory on each replica: a message against its sender's rates first, and only one within them against the system's, so that one chat user cannot spend the system's events; a guest's or another organisation's user's event (Slack's `user_team` other than the workspace, or the audience Tinfirm stored when it checked the chat user) counts in a bucket of its own, a tenth of the system's rate (60 a minute by default), and not in the system's. Before any of them, Slack's two endpoints answer `429` with `Retry-After: 60` to a client address with more than 60 requests refused for the system in the last minute (a signature that did not verify), before the body is read; Slack's own requests are never refused, so they never count (`InboundRefusals`). Behind a proxy, `getRemoteAddr()` must be the client's address (the server's forwarded-header settings), as for the login throttle.

The operator's variables, with their defaults, are on the public [Server configuration](../../docs-site/content/reference/configuration.md) page. Beyond it: `TINFIRM_CHAT_MAX_BODY` also bounds a Socket Mode message, which is dropped unacknowledged beyond it, and `TINFIRM_CHAT_SOCKETS_PER_SYSTEM` counts the connections across all `worker` replicas. Socket Mode's connections and the handling of every event run on the `worker` replicas (`TINFIRM_ROLES`). Behind the Helm chart's NetworkPolicy, the server's egress to Slack or Microsoft needs a rule ([helm.md, section 4](helm.md#4-networkpolicy)).

## 10. Kill switches

- **Pause the system**: events are verified and acknowledged, then dropped; setting a bot is refused meanwhile (`409 SYSTEM_PAUSED`).
- **Stop every run**: the chat user is told at most once an hour (the package 6 contract, §5.4).
- **Remove the system**: `ChatSystemRemoval` ends its open conversations (`SYSTEM_REMOVED`). Its channel triggers, whose source it is, are deleted (`trigger.deleted` with `cause` `system.deleted`), and any other trigger with an account row on it pauses `SYSTEM_REMOVED`, the row gone with the system (`TriggerKillSwitches.systemDeleted`).

## 11. What is refused, and why

Guests and users of other organisations are refused, told once an hour, and audited as `chat.refused`.

| Who or what | Why |
|---|---|
| A guest: Slack's multi-channel and single-channel guests, a Teams guest | A guest is not one of the company's people, yet would drive agents with a member's or a trigger's accounts |
| A user of another organisation: Slack Connect, another Teams tenant, an anonymous Teams user | The agent's answers and actions would serve another organisation |
| Group chats: Slack's multi-person direct messages, Teams group chats | A personal conversation acts as one person |
| Shared channels: Slack Connect channels, Teams shared channels | Its answers would reach another organisation |
| Bots' and other apps' messages; edits and deletions | Two apps cannot answer each other in a loop; a turn takes a message as it was sent |
| A channel message that does not mention the bot | The bot reads only what is addressed to it |
| A chat user the platform marks deleted | Only the workspace's current members are served |
| A suspended member | A suspended member acts for nothing, here as in the app |
| A Viewer's direct message (section 4) | A Viewer starts and continues no conversation ([ADR-0077](../adr/0077-licence-editions-and-seats.md) decision 5) |

## 12. Troubleshooting

A chat system whose status is `ERROR` shows **Unreachable**. Beyond the public guides' rows:

| What you see | Internal diagnosis |
|---|---|
| "The platform refused the bot: …" | The platform refused the bot's token twice (`ChatBot`); the server logs it |
| "Slack may have disabled this app's event subscriptions after deliveries failed …" | Events API only: Tinfirm answered Slack `503` (its database failed) and has received no event for 30 minutes since; the next event Tinfirm stores clears the error |
| Slack's events are refused (Events API) | `tinfirm_chat_inbound_refused_total` with `reason` `SIGNATURE` (`/actuator/prometheus`): store the app's current signing secret |
| Teams' activities are refused | `tinfirm_chat_inbound_refused_total` with `reason` `TOKEN` or `ACTIVITY`, and the server's log: check the Microsoft App ID and tenant, the messaging endpoint, and that nothing between the TLS endpoint and the server rewrites the request |
| Events of another workspace | `reason` `OTHER_WORKSPACE`: the app is installed in another workspace than the bot's, or no bot is set yet |
| A guest or another organisation's user | `chat.refused` in the audit log |

A direct message or a press that Tinfirm failed to handle five times is answered "Something went wrong while Tinfirm took this. Please send it again.", and the server logs each failed attempt.
