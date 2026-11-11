# Set up Slack and Microsoft Teams

Connect a Slack workspace or a Microsoft Teams tenant to your company, so that members can write to Tinfirm's bot and mention it in channels. This page is for the Owners and Admins who register the chat systems. What members then do is in [Talk to agents in Slack and Microsoft Teams](../user-guide/chats.md).

## How it works

Each chat system is one Slack workspace or one Teams tenant, with one bot: the Slack app installed in the workspace, or the Azure Bot in the tenant. The bot receives and posts messages, and nothing else.

| | A direct message to the bot | A mention of the bot in a channel |
|---|---|---|
| Who | A member whose chat user is linked to their Tinfirm account | Any member of the workspace in the channel, linked or not |
| What it starts | Their personal conversation with an agent they use | A conversation of the channel's trigger for the agent named, or of its default trigger |
| The agent acts with | The member's own accounts, budget and settings | The accounts and the budget the trigger's author chose |
| Questions and approvals | The member presses the buttons | Questions: any member of the workspace; approvals: the trigger's team |

Agents act as the person who writes, or with the trigger's accounts, never as the bot. A company has at most one system per Slack workspace or Teams tenant.

## Before you start

- **A service account for the bot.** Create one on **Service accounts** that borrows nothing else, such as `svc-slack-bot`: [Service accounts](service-accounts.md).
- **Tinfirm reachable from the internet**, for Teams and for Slack's Events API. Slack's Socket Mode needs only outbound connections. Your operator routes `/api/chat/…` to the server and lets it reach Slack or Microsoft: [What your operator must allow](#what-your-operator-must-allow).
- **The rights to create an app** in your Slack workspace, or an Azure Bot in your Microsoft Entra tenant.

## Register a Slack workspace

### Choose how events arrive

| | **Events API** | **Socket Mode** |
|---|---|---|
| How | Slack posts each event and each button press to Tinfirm, signed with the app's signing secret | Tinfirm opens connections to Slack with an app-level token; nothing reaches in |
| Needs | Tinfirm reachable from the internet | An app-level token (`xapp-…`) with `connections:write` |
| Secret to store | **Signing secret** | **App-level token** |
| When Tinfirm cannot take an event | Slack retries, and disables the app's event subscriptions when deliveries keep failing | Slack delivers the event again |

Choose Socket Mode for a Tinfirm behind a firewall.

### Register the Slack app

1. On **Systems**, choose **Register a system**. Under **Chats**, choose **Slack**, then **Next**. Give it a **Name**, then **Next**. Under **How events arrive**, choose **Events API** or **Socket Mode**, then **Register**.
2. On the system's **Overview**, under **Slack app**, choose **Download the manifest**. At api.slack.com, create an app from this manifest and install it in your workspace. The manifest asks for the bot's scopes, the events of direct messages, mentions and channels, the buttons, and the user scopes of the [`history` tool](#slacks-history-tool). Its URLs are made from Tinfirm's public address: if that changes, download the manifest again.
3. Under **Secrets**, choose **Store** for what your choice needs:
    - **Signing secret**, from the app's Basic Information › App Credentials › Signing Secret;
    - **App-level token**, from Basic Information › App-Level Tokens: an `xapp-` token with `connections:write`. With Socket Mode, also turn Socket Mode on in the app's settings.

    Tinfirm keeps it in its vault and never shows it again. Each row says **Stored** or **Not stored**.
4. With the Events API, the Overview shows the **Events request URL** and the **Interactivity request URL**, which the manifest already sets. Slack checks the events URL with a signed request, which passes only once the signing secret is stored: if Slack checked it before, verify it again on the app's Event Subscriptions page.
5. On the **Bot** tab, [set the bot](#set-the-bot) with the app's Bot User OAuth Token.
6. Choose **Test**. It checks the bot, the stored secret and, with Socket Mode, that a connection opens.
7. Invite the bot to the channels where channel triggers should answer.
8. Optionally, set up the [sign-in app](#slacks-history-tool), so that agents can read channels.

To change how events arrive later, choose **Change** beside **Events arrive by** on the Overview, then store the secret the new way needs.

## Register a Microsoft Teams tenant

Teams always posts to Tinfirm, so Tinfirm must be reachable from the internet.

1. In Azure, create a single-tenant Azure Bot with the Teams channel on. Note its Microsoft App ID and your tenant ID, and create a client secret of its app (Certificates & secrets): it is the bot's app password.
2. On **Systems**, choose **Register a system**, then **Microsoft Teams** under **Chats**, and **Next**. Give it a **Name**, then **Next**. Enter the **Tenant ID** and the **Microsoft App ID**, then **Register**. Neither can change later. A second system of the same tenant is refused.
3. The **Overview** shows the **Messaging endpoint**. Set it in the Azure Bot's Configuration › Messaging endpoint.
4. On the **Bot** tab, [set the bot](#set-the-bot) with the **App password**.
5. On the Overview, choose **Download the app package** and upload it to Teams. Add the app to the teams whose channels should have channel triggers. The package installs the bot in personal chats and in teams, without files.
6. Choose **Test**. It asks Microsoft for a token with the app password.

Tinfirm learns a team's channels from what Teams sends it: the bot added, a channel created, renamed or deleted, and mentions of the bot. A Teams system has no sign-in app, no tools and no files.

## Set the bot

The bot is a credential of the chat system, named **Bot**, that a service account borrows. It is used only to receive and post in the chat.

1. On the system's **Bot** tab, choose **Set the bot**.
2. Choose the **Service account**.
3. Paste the **Bot token** (Slack, `xoxb-…`, from OAuth & Permissions after installing the app) or the **App password** (Teams).
4. Choose **Check and set**. Tinfirm checks the token with Slack or Microsoft before it keeps it. On Slack, the token's workspace becomes the system's, and stays fixed.

The **Bot** tab then shows who it is **Borrowed by**, the **Credential**, and the bot's **Bot user** and **Workspace** (Slack) or **Microsoft App ID** (Teams). Among the system's credentials on the Overview, it is marked **Chat bot**.

- **Replace the bot token** (or **Replace the app password**) keeps the same service account. To move the bot to another service account, remove it first.
- **Remove** deletes the credential and its token. Until a bot is set again, what people write to Tinfirm in the chat is checked and dropped, and never answered later. Open conversations stay, readable in the app.

The bot carries messages and nothing else. It is never lent to a group, named in a trigger's accounts, used by a conversation or by a tool, and it changes only on the **Bot** tab. Its service account cannot borrow another credential of the same system, and cannot be deleted until the bot is removed.

| The bot is refused when | What to do |
|---|---|
| The Slack token does not start with `xoxb-` | Paste the Bot User OAuth Token |
| Slack or Microsoft refused the token | Check that it is current |
| The Slack token is of another workspace, or of another bot user, than the system's | Use the token of this system's own app, or remove the bot first |
| Another service account borrows the bot | Remove the bot first |
| The service account is suspended | Resume it, or choose another one |
| A member's own account on the system is this identity | Use the token of this system's own app |
| The system is paused | Resume it; removing the bot still works |

## The system's page

| Tab | What it holds |
|---|---|
| **Overview** | The health strip (the last test, an error or the pause; **Bot: set** or **Bot: not set**); the Slack app or the Teams app, with its download, its URLs and Slack's secrets; the system's credentials; on Slack, the **Sign-in app** |
| **Bot** | The bot: the service account that borrows it, its credential, and its identity in Slack or Teams |
| **Channels** | The channels the bot is in: public ones, and the private ones you are in yourself. Each with its kind, the channel triggers on it, and why no trigger may answer there (archived, shared with another organisation, unavailable) |
| **Tools** | Slack only: `history` |
| **Triggers** | The system's channel triggers |

## Slack's history tool

A Slack system offers agents one tool, `history`: a read of a channel's latest messages, or of a thread's replies, at most 100 a call. It never reads with the bot's token, only with the conversation's own Slack account:

- in a member's personal conversation, the member's own Slack account, which they connect with **Sign in to Slack** under **Accounts**;
- in a channel trigger's conversation, the Slack account its accounts name: the author's own, or a credential a service account borrows, added on the system's page by signing in. There, `history` reads only the thread the agent was asked in, since anyone in the channel may ask.

For anyone to sign in, set up the **Sign-in app** on the Overview: **Set up the app** with the Slack app's client ID and client secret (Basic Information, under **App Credentials**). Take the **Client Secret**, not the **Signing Secret** or the **Verification Token** beside it: with another value, every sign-in comes back with *The system refused its sign-in app*, and the audit log's `account.refused` says *Slack's bad_client_secret*. The manifest already holds the callback URL, and the scopes are fixed: `channels:history groups:history`. Without it, the Overview says **Sign-in app: not set up — nobody signs in, so agents cannot read its channels**.

An agent uses `history` once its editor adds the Slack system to it and allows the tool. A Teams system offers no tool.

## Channel triggers

A channel trigger answers mentions of the bot in its channels, with the accounts and the budget its author chose. Any member who may use the agent writes one, with the source **Someone mentions it in a channel**: [Write a trigger](../user-guide/triggers.md). The chat adds these checks:

- The system has a bot.
- Each channel (1 to 20) is one the bot is in, not archived, deleted or shared with another organisation.
- The author is linked on the system and a member of each channel.
- One trigger per agent per channel, and one default trigger per channel (**Its default**).

Any member of the workspace who can write in the channel drives the agent, linked or not. The trigger editor says so with **Anyone can drive this agent**. **Run now** does not apply: a mention fires it.

## Limits

Set the company's chat rates on **Settings**, **Chats and webhooks**, under **Slack and Teams**: [Company settings](settings.md). Until you change them, the instance's values apply.

| Setting | Range | Default |
|---|---|---|
| **Messages a minute, per chat user** | 1 to 600 | 20 |
| **Messages a day, per chat user** | 1 to 10,000 | 200 |
| **Events a minute, per chat system** | 1 to 6,000 | 600 |

What is over a rate is refused before any agent sees it, and costs no budget. The person is told at most once a minute. Each server replica counts on its own, so behind several replicas a sender gets up to that many times the rate. Other bounds still apply: a channel trigger's firings an hour, the payer's budget, and one turn at a time per conversation.

## Links between chat users and members

Members link their chat user themselves: by a sign-in link the bot sends them, by signing in to Slack, or, on Teams, automatically when their last Tinfirm sign-in went through the tenant's Microsoft Entra ID. Nobody else can link them.

An Owner or Admin can remove a member's link, for a chat account that is no longer theirs or was taken over: on **Members**, open the member, and choose **Remove link** beside the chat system among their accounts. The bot then refuses that chat user's direct messages until the member links again; their conversations stay. On Teams, Tinfirm links them again by itself at their next message when it can, as at their first. The audit log records it.

## Without a usable bot

When the bot is removed, its token is refused, or its service account is suspended:

- what people write to Tinfirm in the chat is checked and dropped, and never answered later;
- the system's channel triggers wait, with **Waits: its chat system has no bot**, **Waits for an admin to sign the chat's bot in again** or **Waits: the chat bot's service account is suspended**, and go on by themselves once the bot works again;
- open conversations keep their posts for up to 7 days, waiting for a bot.

A trigger whose channels are all unavailable waits too: **Waits: the bot is in none of its channels**.

When Slack or Microsoft refuses the bot's token twice, the system turns **Unreachable**, with **The platform refused the bot: …**, and the Owners' and Admins' inbox shows **The bot of … no longer works**. Replace the token on the **Bot** tab.

## Kill switches

| Switch | What happens in the chat |
|---|---|
| **Remove** the bot | As [without a usable bot](#without-a-usable-bot) |
| **Pause** the system, on its page or on **Kill switches** | Events are checked, then dropped and never answered later. The bot posts and reads nothing; the channel triggers pause; the conversations' posts wait and go out after the resume. **Resume** offers to resume the triggers too |
| Suspend the bot's service account | As removing the bot, until it is resumed |
| Pause a channel trigger | Mentions in its channels get **… is paused here** |
| Pause an agent | A message that would start a conversation with it gets **… is paused**; its open conversations wait |
| **Stop every run** | Nothing starts: **Tinfirm is stopped in … by an admin; nothing was started.** A message to an open conversation waits for the lift. **Approve** buttons are refused meanwhile |
| Suspend a member | Their chat user is refused: **Your Tinfirm account is suspended in ….** |
| Remove the system | Its bot, secrets and links go; its open direct-message conversations end; its channel triggers are deleted. Any other trigger with an account on it pauses until its author resumes it |

[Kill switches](kill-switches.md) explains each switch in the app.

## Who the bot refuses

The bot serves the members of the workspace or tenant only:

- Guests (Slack's multi-channel and single-channel guests, Teams guests), users of other organisations (Slack Connect, another Teams tenant, anonymous Teams users) and deleted users are refused with **Tinfirm serves members of this workspace only.**, told once an hour, never offered a link, and recorded in the audit log.
- Group direct messages and group chats are never served: the app is not installed there.
- Shared channels (Slack Connect, Teams shared channels) take no channel trigger; a channel that becomes shared makes its triggers' channel unavailable.
- Messages of bots and other apps, edits and deletions are ignored.
- A Viewer can link their chat account, but their direct messages are refused: **You are a Viewer in this company: you read what it shares with you in Tinfirm, and start or continue no conversation with its agents. An admin can change your role.** Make them a Member to let them write to the bot ([Roles](roles.md#viewers)).

## Troubleshooting

| The page says | Cause | Fix |
|---|---|---|
| **The platform refused the bot: …** | The token was revoked or rotated in Slack, or the Azure app's client secret expired | **Replace the bot token** or **Replace the app password** on the **Bot** tab |
| **Slack: app_uninstalled** or **Slack: tokens_revoked** | The Slack app was uninstalled, or its tokens revoked | Install the app again, then replace the bot token |
| **Slack stopped sending events** | Events API only: Tinfirm could not take Slack's events for a while, and has received none for 30 minutes since. Slack disables an app's event subscriptions when deliveries keep failing | Re-enable them on the app's Event Subscriptions page. The next event Tinfirm stores clears it |
| A failed test naming a missing bot, signing secret or app-level token | What it names | Set it, then **Test again** |
| A failed test: **Slack did not open a Socket Mode connection: …** | Socket Mode is off in the app, or the server does not reach Slack | Turn Socket Mode on; ask your operator about the egress |

When the bot answers nothing:

| Look at | Fix |
|---|---|
| **Bot: not set** on the system's page, or its service account suspended | Set or replace the bot; resume the service account |
| The system is paused | **Resume** it |
| The company's runs are stopped, on **Kill switches** | Lift the stop |
| A channel without a trigger: the bot answers **No agent answers in this channel.** | Add a channel trigger, or make one the channel's default |
| A channel message that names only an agent | Mention the bot itself, then the agent's handle |
| Slack's events refused (Events API) | Store the app's current signing secret |
| Teams' messages refused | Check the Microsoft App ID, the tenant and the messaging endpoint; your operator checks that nothing between the TLS endpoint and the server rewrites the request |

## What your operator must allow

Pass these to whoever runs Tinfirm: [Install on Kubernetes with Helm](../operator-guide/helm.md) and [Server configuration](../reference/configuration.md) have the details.

- The ingress routes `/api/chat/…` to the server, as the Helm chart's default path does. Teams and Slack's Events API post there.
- The server reaches `slack.com` and `files.slack.com`, and Slack's Socket Mode hosts; or `login.microsoftonline.com`, `login.botframework.com` and `smba.trafficmanager.net`; on port 443. Behind a NetworkPolicy, this needs an egress rule.
- For Teams, Tinfirm's public TLS endpoint sits directly in front of the server, with no plaintext hop between them.
- Socket Mode's connections, and the handling of every event, run on the server's `worker` replicas.
