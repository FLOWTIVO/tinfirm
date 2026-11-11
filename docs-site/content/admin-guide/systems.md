# Register systems and lend their credentials

A system is a service agents work with: a tracker, a code host, an MCP server, a chat, or a webhook that other software posts to. This page is for Owners and Admins. It shows how to register Jira, GitHub, GitLab and Git, set up sign-in, test a system, and add the company's credentials. It also covers lending those credentials to groups and service accounts, and how a conversation picks the account it acts with. MCP servers, webhooks and chats have pages of their own: [MCP servers](mcp-servers.md), [Receive webhooks](webhooks.md) and [Set up Slack and Microsoft Teams](chat-systems.md).

Only Owners and Admins register, change, test, pause and remove a company system. The same goes for its Sign-in app, its credentials and their lending. Everyone but a Viewer can connect their own account on a system, under **Accounts** ([Connect your accounts](../user-guide/accounts.md)).

## The kinds of system

**Systems** lists the company's systems in sections. Each row shows the system's name, its handle, its kind, its address, its status, and how accounts connect to it (**Sign-in or token**, **Tokens only**, **Tokens only, no app yet**).

| Kind | Section | What agents get | Address field | Sign-in app | Triggers |
|---|---|---|---|---|---|
| **Jira Data Center** | Trackers | Issues; read and write tools | **Base URL** | Yes | Issues, polled |
| **Jira Cloud** | Trackers | The same | **Site URL** | Yes | Issues, polled |
| **GitHub** | Code hosts | Issues, pull requests, repositories | **API URL** | Yes | Issues and pull requests, polled |
| **GitLab** | Code hosts | Issues, merge requests, projects | **Instance URL** | Yes | Issues and merge requests, polled |
| **Git** | Code hosts | Repositories on any https git host, cloned and pushed with a token | None | No: tokens only | None |
| **MCP server** | MCP servers | The server's own tools ([MCP servers](mcp-servers.md)) | **MCP endpoint** | Found by discovery | None |
| **Slack**, **Microsoft Teams** | Chats | Direct messages and channels through Tinfirm's bot ([Set up Slack and Microsoft Teams](chat-systems.md)) | Fixed | Slack only | Channel triggers |
| **Webhook** | Webhooks | Deliveries other software posts ([Receive webhooks](webhooks.md)) | None: Tinfirm gives it a URL | No | Webhook triggers |

## Register a system

1. Open **Systems** and choose **Register a system**.
2. **Kind**: pick the kind, then choose **Next**.
3. **Details**: enter the **Name** and, for a kind that takes one, the address. The field is named as the table above shows. The name has 1 to 100 characters, and no other system of the company may have it, ignoring case.
4. **Settings**: Jira, GitHub and GitLab take **Check for changes every** … **seconds**. This is how often Tinfirm reads the system for the triggers that watch it, from 30 seconds to an hour. Leave it empty for every minute. More often reacts sooner and uses more of the system's rate limit. Git has no settings, so its registration has two steps.
5. Choose **Register**. The system's page opens.

Then, on the system's page: **Test** it ([Test a system](#test-a-system)). Set up the **Sign-in app** if people should sign in rather than paste tokens. **Add credential** and lend it to the groups and service accounts that act there. On GitHub, GitLab and Git, **Add a repository** for each repository agents clone. Then write the triggers that watch it ([Write a trigger](../user-guide/triggers.md)). Git has no test and no Sign-in app: add a credential, lend it, and add the repositories.

Tinfirm makes the system's **handle** from its name, for example `support_jira` for *Support Jira*. The handle prefixes the system's tools in a run, such as `support_jira_comment`. Nobody types it, and it never changes, so renaming a system keeps every tool's name.

The kind and the address never change. Tinfirm sends a token only to the address it was connected at. To move a system to another address, register a new one. An MCP server's endpoint is the one exception ([Move a server to a new endpoint](mcp-servers.md#move-a-server-to-a-new-endpoint)).

**Change**, on the system's page, renames it and changes its settings, such as the poll interval.

## The system's page

The header holds **Test**, **Change** and a **⋯** menu with **Pause** … and **Remove** …. On a phone, all four are behind **⋯**. Below it, a health strip shows the last test or the pause, and how accounts connect (**Sign-in app: set up** or **Sign-in app: not set up**).

The tabs depend on the kind:

| Tab | Kinds | What it holds |
|---|---|---|
| **Overview** | All | **Credentials**, the **Sign-in app**, and **How Tinfirm reads it** for a tracker |
| **Repositories** | GitHub, GitLab, Git | The repositories agents may clone |
| **Tools** | Jira, GitHub, GitLab, MCP server, Slack | The tools, named as a run names them |
| **Triggers** | Jira, GitHub, GitLab, chats, webhooks | The triggers that use the system, and how Tinfirm reads it |
| **Bot**, **Channels** | Slack, Microsoft Teams | [Set up Slack and Microsoft Teams](chat-systems.md) |
| **Mapping**, **Deliveries** | Webhook | [Receive webhooks](webhooks.md) |

## Addresses and tokens, per kind

A token is a user's token at the system. For a credential, use a user of the company's own, such as a machine user: whoever the credential is lent to acts as that user. Give the token only the permissions agents need. A credential lent read-only needs only the read permissions. Tinfirm checks every token, except a Git token, by asking the system whose it is before it keeps it.

=== "Jira Data Center"

    - **Base URL**: the address you open Jira at, without `/rest`, for example `https://jira.example.com`. Jira Data Center or Server 8.14 or later.
    - **Token**: a personal access token of the Jira user.
    - **Permissions** in the projects agents and triggers use: *Browse projects* to read and poll; *Add comments* to comment; *Transition issues* to move issues; *Create attachments* for a trigger that attaches documents; *Link issues* to link an issue to a change request Tinfirm opened.

=== "Jira Cloud"

    - **Site URL**: the site without a path, for example `https://acme.atlassian.net`.
    - **Token**: the Atlassian account's email and an API token, joined by a colon without spaces: `alice@example.com:api-token`. Create the API token at id.atlassian.com, under *Security*, *API tokens*.
    - **Or a service account**: an Atlassian service account's OAuth 2.0 credential, its **Client ID** and **Client secret** ([Add a Jira Cloud service account](#add-a-jira-cloud-service-account)). Its API tokens do not work here: they are valid only through Atlassian's gateway, which a token is never sent to.
    - **Permissions**: the same as on Data Center.

=== "GitHub"

    - **API URL**: `https://api.github.com`, or `https://<host>/api/v3` on GitHub Enterprise Server.
    - **Token**: a personal access token of the GitHub user. With a fine-grained token: *Issues*, *Pull requests* and *Metadata* read to read and poll; *Issues* and *Pull requests* read and write to comment, label and close; *Contents* read to clone; *Contents* read and write to push and open pull requests.
    - Leave out *Workflows*, so that GitHub refuses a push that changes a workflow. A classic token needs `repo` (without `workflow`), which reaches every repository the user can.

=== "GitLab"

    - **Instance URL**: the instance's address, for example `https://gitlab.com`. Tinfirm adds `/api/v4`.
    - **Token**: a personal, group or project access token. A group or project token keeps it to those projects.
    - **Scopes**: `read_api` to read and poll; `api` to comment, label, close and open merge requests; `read_repository` to clone; `write_repository` to push.

=== "Git"

    - No address: the system holds repositories on any https git host.
    - **Token**: one the host accepts for git over https, with read access to clone and write access to push. Tinfirm keeps it as it is, without a check: the first clone uses it.
    - Git has no Sign-in app, no **Test** and no tools, and Tinfirm opens no change request there.

## Set up the Sign-in app

A system's **Sign-in app** is its one OAuth client. With it, members connect their own accounts, and you add credentials, by signing in at the system. Without it, accounts connect by token only. Jira Data Center, Jira Cloud, GitHub and GitLab take an app you register at the provider. An MCP server's app is usually found by itself ([MCP servers](mcp-servers.md#sign-in-with-oauth)).

1. On the system's **Overview**, under **Sign-in app**, copy the **Callback URL**, for example `https://tinfirm.example.com/api/oauth/callback`. It must match exactly at the provider.
2. Register an OAuth app at the provider with that callback URL (below).
3. Choose **Set up the app**. Enter the **Client ID**, the **Client secret** and, if you want other than the kind's defaults, the **Scopes**, space-separated. Choose **Save the app**.

The client secret goes into the company's vault and is never shown again: the card says **Stored · never shown again**. Leave the secret empty when you change the app later to keep the stored one. **Change** edits the app. **Remove the app** stops sign-ins: accounts that signed in keep working until their sign-in expires, then ask to be connected again.

Every sign-in expires after 10 minutes if it is not finished, and returns to the page it started from. Tinfirm refreshes the access token before it expires, so people sign in once.

| Kind | Where to register the app | Default scopes |
|---|---|---|
| Jira Cloud | The Atlassian developer console: create an *OAuth 2.0 integration*. Under *Permissions*, add the Jira API with `read:jira-work`, `read:jira-user` and `write:jira-work`, and the user identity API with `read:me`. Under *Authorization*, add the callback URL. Under *Distribution*, allow sharing so that others than its owner can authorize it. Copy the client ID and secret from *Settings*. | `read:jira-work read:jira-user write:jira-work offline_access read:me` |
| Jira Data Center | In Jira, *Administration*, *Applications*, *Application links*: create a link to an *External application* with *Incoming* authentication. Enter the callback URL as the *Redirect URL*, and set the *Application permission* to *Write* (or *Read*, with the scope `READ`). | `WRITE` |
| GitHub | *Settings*, *Developer settings*, *OAuth Apps*, *New OAuth App*, of the organization that owns the repositories or of a user; on GitHub Enterprise Server, on your host. Enter Tinfirm's address as the *Homepage URL* and the callback URL as the *Authorization callback URL*, then *Generate a new client secret*. | `repo` |
| GitLab | Your user settings, *Applications* (or a group's, or the *Admin Area*'s), *Add new application*. Enter the callback URL as the *Redirect URI*, tick *Confidential* and the scope `api`. Save, and copy the *Application ID* as the client ID, and the *Secret*. | `api` |

For agents that only read, narrow the scopes at the provider and on the app: `read_api` (and `read_repository`) on GitLab, `READ` on Jira Data Center, and on Jira Cloud the default scopes without `write:jira-work`. GitHub's OAuth scopes cannot be narrowed to reading private repositories: use `public_repo` for public repositories only, or ask for fine-grained tokens. An organization that restricts third-party OAuth apps must approve the app first.

A Jira Cloud sign-in must include the system's site: Atlassian asks which sites to allow. Jira and GitHub offer Tinfirm no way to revoke a grant. When you remove a credential there, or a member disconnects their account, revoke the grant at the provider too. On GitLab, Tinfirm revokes it for you.

## Test a system

**Test** reaches the system and says as whom. It uses, in this order:

1. the credential you pick in the list beside it (**With the usual account** or **With** a credential), offered when the system holds more than one;
2. your own working account on the system;
3. the system's only enabled, working credential;
4. no account at all.

With an account, Tinfirm asks the system who the account is, and the strip reads, for example, *Reached with the credential CI bot, as ci-bot.* Without one, it makes a request that needs no credential. A system that answers is reached; when it asks for a credential and none works yet, the strip offers **Add a credential**. A credential you pick that is disabled or must be signed in again is not sent: *Not tested: … is disabled, or must be signed in again.*

The result becomes the system's status and its **last test**, replacing the one before: *Tested 2 hours ago by Alex Rivera · passed with the credential CI bot*. Every member who sees the system can read when it was tested and how that ended; who ran the test and with which credential, only Owners and Admins. A failed test shows **Test failed**, the reason and **Test again**.

| Status on **Systems** | On the system's page | Set by |
|---|---|---|
| **Not tested yet** | **Not tested yet** | Registration |
| **Reachable** | **Ready** | A test that passed |
| **Unreachable**, with the error as its tooltip | **Error** | A test that failed |

A paused system shows **Paused** and cannot be tested. A Git system and a webhook have nothing to test.

## Add a credential

A credential is the company's token or sign-in on a system. A system can hold any number, each with its own name.

1. On the system's **Overview**, under **Credentials**, choose **Add credential**. The dialog is **Add a credential to** the system's name, such as **Add a credential to GitHub**.
2. **Name**: what it is for, as the system's page and the trigger editor name it. 1 to 100 characters, unique on the system, ignoring case.
3. **How**: **Paste a token**, or **Sign in to GitHub** (or the system's name) where its Sign-in app is set up. Git takes tokens only. Jira Cloud also takes **Service account (OAuth 2.0)** ([below](#add-a-jira-cloud-service-account)).
4. With a token, paste it under **Token** and choose **Check and add**. Tinfirm checks it with the system and keeps it in the vault: nobody sees it again. With a sign-in, choose the sign-in button, sign in at the system as the user the credential should be, and come back. The page says: *The credential is connected. Lend it to a group or a service account.*

A new credential is lent to nobody. Lend it next ([Lend a credential](#lend-a-credential)).

One user at a system is either a member's own account or the company's credential, never both. A token whose user is a member's own account is refused: *This … user is a member's own account. Use a user of the company's own, such as a machine user.* Two credentials may share one user, such as two tokens of one machine user with different scopes.

### Add a Jira Cloud service account

An Atlassian service account is a user of the organization's own, with no email and no sign-in, made for integrations. Tinfirm acts as it with its OAuth 2.0 credential (the `client_credentials` grant): no Sign-in app, no browser, no refresh token. Tinfirm mints a new access token itself whenever the last one expires, every hour.

1. In Atlassian Administration, *Directory*, *Service accounts*, create the service account and give it access to Jira on the site, and the project permissions above.
2. On the service account, *Create credentials*, *OAuth 2.0*. Choose the scopes: `read:jira-work` and `read:jira-user` to read and poll, and `write:jira-work` to comment, transition, attach and link. Leave out `write:jira-work` for agents that only read. Copy the **Client ID** and **Client secret**: Atlassian shows the secret once.
3. On the system's **Overview**, **Add credential**, **How**: **Service account (OAuth 2.0)**. Enter its name, the client ID and the secret, and choose **Check and add**.

Tinfirm mints a token, reads the site's cloud id from `https://<site>.atlassian.net/_edge/tenant_info`, and asks Jira who the token is through Atlassian's gateway, `https://api.atlassian.com/ex/jira/{cloudId}`. Anything refused there refuses the credential, with the status and error code Atlassian answered. The credential shows **Client credentials** as how it was connected. When Atlassian refuses the client later (the credential was revoked or expired), the credential turns **Store new client credentials**: create new ones on the service account and store them with that button.

Like any credential, it is lent to nobody at first: lend it to a group or to a Tinfirm service account ([Lend a credential](#lend-a-credential)), whose triggers then act as the Atlassian service account.

## Keep a credential working

Each credential shows its state, how it was connected (**Token**, **Sign-in** or **Client credentials**), who it is at the system (**As** its login), who connected it and when, and when it was last used.

| State | Meaning | What to do |
|---|---|---|
| **Working** | The system accepts it | Nothing |
| **Sign in again**, **Store a new token**, **Store new client credentials** | The system refused it, or its sign-in expired and could not be refreshed | The button of that name |
| **Not working** | It failed otherwise; the last error is shown under it | Read the error, then store a new token or sign in again |
| **Disabled** | You disabled it: nothing acts with it | **Enable** |

When a credential lent to a group or a service account breaks, the conversations that act with it wait, and Owners and Admins get an inbox item such as *The credential CI bot of GitHub needs a new token*.

Its **⋯** menu:

- **Store a new token…** or **Sign in again…** keeps its name, its id and what it is lent to, so the triggers and conversations that act with it go on. To rotate a token, create the new one at the system, store it here, then revoke the old one at the system.
- **Rename…** keeps its grants, lendings and triggers.
- **Disable** stops every use of it, by groups and service accounts, at its next check, until you **Enable** it. Conversations that act with it wait for an admin and go on once you enable it. Triggers that act with it pause, and stay paused until their author or an admin resumes them.
- **Remove…** removes its grants, then its lendings to service accounts (their triggers that act with it pause until their authors name another account), then the credential and its token. A sign-in is revoked at the system where the system allows that. Conversations that act with it stop at their next use of it.

**Credentials** also counts the members who connect their own account on the system, for example *3 members connect their own account on GitHub (1 needs signing in again).* Their accounts are theirs: you see whether they hold one and its state on [Members](members.md), never the login or the token.

A credential's token is a managed company secret. **Settings**, **Secrets** lists it but cannot replace or delete it: change it on the system's page.

## Lend a credential

You lend a credential on the system's page, under the credential.

### To a group

**Lend to a group** opens **Lend** *CI bot* **to a group**. Pick the **Group** and the **Access**, then choose **Lend it**.

| Access | Means |
|---|---|
| **Read-only** (the default) | No write tool is called with it, and its repositories are read-only in the workspace: nothing is pushed |
| **Read and write** | Write tools are called with it, as the agent and the person allow |

The group's members act with the credential on the system in their own conversations, when they hold no account of their own there. Change the access with the pills on the group's row: each call reads it anew, so open conversations follow at their next call. The **×** on the row stops lending: a conversation that acts through it can no longer use the system and says so. A credential is lent to a group once. Groups are made on [Members](members.md).

### To a service account

**Lend to a service account** opens **Lend** *CI bot* **to a service account**. Pick the **Service account** and the **Access**, **Read and write** by default, then choose **Lend it**. The triggers that act as the service account act with this credential on the system, and the service account's budget pays.

A service account borrows at most one credential on each system. Lending it another one replaces the one it borrows, and the dialog says so first. Its triggers that acted with the old one name the new one at once, their open conversations take it at their next check, and their authors are told in their inbox, unless they lent it themselves. **Withdraw** (the **×** on the row) leaves the service account without a credential on the system: its triggers that act with it pause until their authors name another account. [Service accounts](service-accounts.md) says more.

## Which account a conversation acts with

An agent holds no credentials. Each conversation binds one account per system, at the system's first use: its first call of one of the system's tools, or its first turn with one of the system's repositories.

In a person's own conversation:

1. **Their own account**, when it works.
2. Else, when they hold **no account of their own** there, a working, enabled credential lent to one of their groups. One such grant binds at once, and the thread says so, for example *GitHub: via Analysts, read-only*. With several, the person picks one; read and write grants are offered before read-only ones, then the oldest first.
3. Else the conversation shows *Connect GitHub to continue*, and the call waits until the person connects their own account.

A person's own account that stopped working is never passed over silently: they choose between signing in again and the grant. The binding is kept for the conversation's life and checked at every use. It never switches: connecting an account later changes new conversations only.

A trigger's conversation acts with what the trigger names for each system: its author's own account, or a service account and the credential lent to it ([Write a trigger](../user-guide/triggers.md)). An MCP server that needs no account binds no account at all ([MCP servers](mcp-servers.md#a-server-that-needs-no-account)).

When a binding waits on a company credential, the conversation shows *GitHub waits for an admin*, and Owners and Admins are told in their inbox.

## Repositories

On a GitHub, GitLab or Git system, the **Repositories** tab lists what agents may clone into their workspace. **Add a repository** asks for:

- **URL**: the repository's https address, at most 500 characters, without a password, a query or a fragment. On GitHub, `https://<host>/owner/repo` on the system's host (`github.com` for `api.github.com`); on GitLab, `https://<host>/group/project`; on Git, any https address the token can clone.
- **Default branch**: empty takes the host's default.

The repository's name is its path, for example `acme/shop`. Removing it means agents no longer get it in new workspaces; the repository itself stays where it is.

An agent gets a repository on its definition ([Make and share an agent](../user-guide/agents.md)). A run clones and pushes with the account its conversation acts with, so a read-only grant never pushes. The runner host uses the credential for git and never passes it into the sandbox. After a turn that pushed to GitHub or GitLab, Tinfirm opens the pull or merge request itself, as the agent's settings say.

## Tools

The **Tools** tab lists the system's tools, named as a run names them: the handle, then the tool. Jira's, GitHub's, GitLab's and Slack's are Tinfirm's own and fixed:

| Tool | Kinds | Effect | What it does |
|---|---|---|---|
| `get` | Jira, GitHub, GitLab | Reads | Reads one issue, pull request or merge request by its key, with its newest comments |
| `search` | Jira, GitHub, GitLab | Reads | Searches with JQL, GitHub's issue search syntax, or GitLab's search terms; at most 50 results |
| `comment` | Jira, GitHub, GitLab | Writes | Adds a comment, written in Markdown, which starts with the agent's name |
| `transition` | Jira | Writes | Moves an issue through a transition its status offers |
| `label` | GitHub, GitLab | Writes | Adds or removes labels; GitHub creates a label that does not exist yet |
| `close` | GitHub, GitLab | Writes | Closes an issue, pull request or merge request |
| `open_change_request` | GitHub, GitLab | Writes | Opens the pull or merge request of a conversation's branch. The agent never calls it: Tinfirm does after a turn that pushed, as its mode on the agent says |
| `history` | Slack | Reads | Reads a channel's or a thread's messages, with the conversation's own Slack account |

A tool that writes is refused with a read-only credential, whatever the agent allows. An MCP server's tools are its own catalogue, which you confirm one by one ([MCP servers](mcp-servers.md#confirm-what-each-tool-does)).

## How Tinfirm reads a tracker

Tinfirm reads Jira, GitHub and GitLab for the triggers that watch them. Each read uses one account: a member's own account for their triggers, or a credential, whose one read serves the triggers of every service account that borrows it. A read sees only what its account sees.

- **Overview**, **How Tinfirm reads it**, shows **Checked for changes** *every minute* (or the interval you set), with **Change**.
- **Triggers**, **How it is read**, lists each read, such as *With the credential CI bot* or *With Alex Rivera's own account*, with its state (**Read**, **Idle: no active trigger**, **Waiting**), **Last read**, the requests left of the system's rate limit, and its last error.

When less than a tenth of the system's rate limit is left, Tinfirm reads it half as often. When the system refuses the account, the account turns **Sign in again** or **Store a new token**, and the read waits until it works again. When the system answers that one project or repository is forbidden or not found, only the triggers that watch it wait; the others are read. On a rate limit, Tinfirm backs off until the limit resets, at most an hour. When the system cannot be reached, it backs off up to 30 minutes, and after an hour of failures its triggers wait. Changes are not lost while a read waits: they are read later. A system takes at most 100 live triggers.

## The three switches

Three switches stop a credential's use, each at a different reach. None is undone by time.

| Switch | Where | What stops |
|---|---|---|
| **Disable** a credential | The credential's **⋯** menu | Every use of that credential, by groups and service accounts |
| **Suspend** a service account | The service account's page | What acts through that service account or on its budget. Groups' grants of the same credentials go on |
| **Pause** a system | The system's **⋯** menu | Every account and credential on the system, its tools, its triggers |

Paused systems and suspended service accounts are listed on [Kill switches](kill-switches.md).

## Pause, resume and remove a system

**Pause** … opens a dialog that says: *Its accounts and credentials act for nothing until you resume it: agents get none of its tools, turns that need it wait, and every trigger that uses it pauses. Testing and connecting accounts wait too.* **Why** is required, and is shown on the page and on **Kill switches** with your name. Tick **Also cancel the turns running now in conversations that use it** to stop those too.

While it is paused, adding credentials, testing and reading tools wait. A webhook system answers deliveries but stores and fires nothing.

**Resume**, in the health strip or the **⋯** menu, makes its accounts and credentials work again, and waiting turns go on. **Resume its … triggers too**, ticked by default, resumes the triggers the pause paused. Nothing that happened during the pause fires. A trigger you leave out stays paused until its author or an admin resumes it.

**Remove** … cannot be undone. The dialog lists what stops at once:

- every account on the system is disconnected: members' own, and the company's credentials with what they are lent to;
- agents lose its repositories and tools;
- the Sign-in app's client secret is deleted;
- the triggers that watch it are deleted, and their conversations end. These are the triggers that start from the system: on its issues, its channels or its deliveries.

Other triggers that only act with an account on the system are not deleted: they pause, shown as **A system it used was removed**, and their author checks their accounts, then resumes them ([When a trigger pauses or waits by itself](../user-guide/triggers.md#when-a-trigger-pauses-or-waits-by-itself)). Conversations that used it stay, readable in the app. Grants made through the Sign-in app are not revoked at the provider: revoke them there.

## Systems on your own network

Tinfirm reaches systems only over https, follows no redirects, and refuses loopback, link-local, private and other non-public addresses, such as the documentation and benchmarking ranges. A Jira Data Center or GitLab on your company network therefore needs your operator. They can either let company systems reach private addresses, or name the system's host as an internal host, which may then use private addresses and http. If the system's certificate comes from your own certificate authority, the operator adds that authority to the Tinfirm server's trust store. [Server configuration](../reference/configuration.md) lists the settings.

Tinfirm calls a system from the Tinfirm server, never from a runner host. When the server's outbound traffic is limited, the operator allows each system and each sign-in host.

## Troubleshooting

| What you see | Cause | What to do |
|---|---|---|
| *baseUrl is not usable: it must be https* | An http address, which your operator does not allow | Use https, or ask your operator ([Systems on your own network](#systems-on-your-own-network)) |
| *baseUrl is not usable: the host … resolves to a private address* | The system is on a private network | Ask your operator to allow it |
| *A Jira Cloud base URL is the site* | A path after the site | Register the site only, such as `https://acme.atlassian.net` |
| *… answered 401 when asked whose the token is* | The token is wrong, expired or revoked. On Jira Cloud, the email or the colon is missing | A new token; on Jira Cloud `email:api-token` |
| *The host … is unknown*, *… could not be reached*, *The TLS connection to … failed* | DNS, the network, or the certificate | Ask your operator |
| Back from a sign-in: *The system did not hand over the account* | The callback URL is not registered exactly, or the client ID or secret is wrong (a provider that says so in its own words, such as GitHub's `incorrect_client_credentials`) | Check the app at the provider and on **Sign-in app** |
| Back from a sign-in: *The system refused its sign-in app* | The client ID or secret on **Sign-in app** is not the app's: mistyped, regenerated at the provider, or another value of the app's page (on Slack, the signing secret or the verification token in place of the client secret). The audit log's `account.refused` names the system's error | **Set up the app** again with the app's current client ID and client secret |
| Back from a sign-in: *The sign-in did not include this Jira site* | The Atlassian grant left out the system's site | Sign in again and choose that site |
| Back from a sign-in: *Another credential of the system took this name in the meantime, so nothing was added* | Someone added a credential of the same name while you signed in | Add it again under another name |
| **Add credential** is greyed out | The system is paused | **Resume** it |
| A conversation waits with *… waits for an admin* | The lent credential is disabled, refused or needs a sign-in, or its service account is suspended | Fix it from the inbox item, or resume the service account |
| A write is refused although the agent allows it | The conversation acts through a read-only credential | Lend it read and write, or the person connects their own account |
