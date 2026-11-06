# Connect your accounts

An agent holds no credentials. When it works for you, it acts on each system as you: with your own account there, or with a credential the company lends to one of your groups. This page is for every member: how to connect your accounts, what the **Accounts** page shows, and how to add your own MCP servers and runner hosts. [Concepts](../concepts.md#accounts-and-credentials) explains accounts and credentials.

## The Accounts page

Open **Accounts** in the sidebar: *Who you act as and who pays: your accounts on the company's systems, your budget and your runner hosts.* It has three tabs:

| Tab | What it holds |
|---|---|
| **Systems** | Your account on each of the company's systems, your chat links, and your own MCP servers |
| **Budget** | Your budget at the company's model gateway, and your subscriptions. See [Your budget and spend](spend.md) |
| **Runner hosts** | Your own runner hosts |

Every member has the page. A Viewer does not, since a Viewer never works with agents.

## Your accounts on the company's systems

Under **The company's systems**, **Systems** lists every Jira Data Center, Jira Cloud, GitHub, GitLab, git host and MCP server the company registered. Each row says how a new conversation of yours would act on the system now, in a chip and a sentence:

| Chip | Meaning | What you can do |
|---|---|---|
| **Your own account** | Your account works. Agents act there as you, under your login | **Sign in again** or **Paste a new token** to replace it; **Disconnect** |
| **Via {group}, read-only** (or *read and write*) | You hold no account of your own, and a credential lent to your group works. Agents act with it | Connect your own to act as yourself in new conversations |
| **Choose per conversation** | Several of your groups lend you a credential. A conversation asks which one when it first needs the system | Connect your own, or choose in each conversation |
| **Sign in again** (orange) | Your account stopped working. The row shows the system's last error and, when a group lends you a credential, that a conversation offers it instead | **Sign in again** or **Paste a new token** |
| **Waits for an admin** | The credential lent to your group must be signed in again, given a new token or enabled. Admins were told | Wait, or connect your own meanwhile |
| **No account needed** | The MCP server takes calls without a credential. An account you hold there is not used | Nothing; you may disconnect it |
| **Not connected** | No account of yours, and nothing lent to your groups | Connect now, or when a conversation first asks you |

When you hold an account, the row says how and when you connected it, *with a token* or *by signing in*, and when it was last used.

You never have to connect in advance: a conversation that needs a system asks you with a **Connect** card in the thread and an item in your [inbox](inbox.md).

### Connect by signing in

**Sign in with {system}** is offered where the company set up the system's sign-in app: on Jira, GitHub and GitLab once an Admin registered it, and on MCP servers that offer it. A git host has no sign-in.

1. Choose **Sign in with {system}**. Your browser goes to the system.
2. Sign in there as yourself and allow Tinfirm. The system's page names what Tinfirm asks for.
3. The browser comes back to the page you started from, which says what happened, such as *{system} is connected. Agents act there as you: {login}.*

A sign-in expires if it takes more than ten minutes; start it again. Tinfirm keeps the sign-in fresh by itself, so you sign in once. If you are refused with *That account on the system already belongs to someone else in this company*, sign in as your own user.

### Connect with a token

Choose **Paste a token**. The dialog asks for the token the system takes:

| System | Field | What to paste |
|---|---|---|
| Jira Data Center | **Personal access token** | A personal access token of your own Jira account: in Jira, your profile, then **Personal Access Tokens** |
| Jira Cloud | **Email and API token** | Your Atlassian email and an API token, joined by a colon: `alice@example.com:api-token`. Create the token at id.atlassian.com under **Security**, **API tokens** |
| GitHub | **GitHub token** | A personal access token of your own, fine-grained or classic, with access to the repositories and issues agents work on |
| GitLab | **GitLab token** | A personal access token of your own with the `api` scope |
| Git host | **Token for git** | A token of your own at the git host, which clones the repositories and pushes where the agent writes |
| MCP server | **Token** | The token the MCP server issued to you. Tinfirm sends it with every call made as you |

**Check and connect** asks the system whose token it is before Tinfirm keeps it (a git host's token is taken as it is). A token the system refuses is not stored, and the dialog shows the system's answer. A token has at most 4000 characters. Tinfirm keeps the token in its vault as yours, uses it only for calls that act as you, and never shows it again.

An agent can do with your account whatever the account may do at the system. Give a token no more rights than your work with agents needs.

### Sign in again, replace or disconnect

- **Sign in again** or **Paste a new token** replaces your account's credential, and it works again at once. Conversations bound to it go on.
- **Disconnect** asks first, then deletes your credential and revokes the sign-in at the system where the system allows it (GitLab and some MCP servers do; Jira and GitHub do not, so revoke Tinfirm there too). Conversations that act as you on the system wait until you connect again; new ones act through a credential lent to your group, if there is one.
- A token that leaked is the system's to revoke first: revoke it there, then disconnect here.

Owners and Admins see on **Members** whether you hold an account on each system and whether it works, never your login or your token.

### Credentials lent to your groups

Owners and Admins can lend a company credential on a system to a group, read-only or read and write. When you have no account of your own there, an agent you talk to acts with it, and the row lists the grants: *Analysts: the credential CI bot, read-only*. Your own account always comes first.

A read-only grant writes nothing through Tinfirm, whatever the agent allows: every tool that writes is refused, and the system's repositories are read-only in the workspace, so nothing is pushed and no change request opens. Connect your own account, or ask an Admin for read and write access.

### How a conversation picks an account

At a system's first use in a conversation, Tinfirm picks:

1. your own account, when it works;
2. else, when you hold none there, a working credential lent to one of your groups, read and write before read-only, then the oldest grant; with several, you choose;
3. else nothing, and the conversation asks you to connect.

Your own account that is broken is never passed over silently: the conversation offers to sign in again or to use the grant. The choice holds for the conversation's whole life and is checked at every use. Connecting your own account later changes new conversations, not open ones. [Talk to an agent](conversations.md#accounts-for-the-conversations-life) shows the cards a conversation asks with.

## Chats

When the company connected Slack or Microsoft Teams, the **Systems** tab has a section **Chats**, with your link to each: **Not linked**, **Linked**, or **Signed in** for Slack. A link is made from the chat: send the Tinfirm bot a direct message, and it answers with a link you confirm, once; Teams often links you by itself. On Slack, **Sign in to Slack** links you too, and lets agents read channels as you. **Unlink** stops the bot answering your direct messages until you link again; your conversations stay. If a link appears that you did not make, unlink it at once.

**Your direct messages go to** picks the agent a direct message goes to when it names none, or **Ask me each time**. [Talk to agents in Slack and Microsoft Teams](chats.md) explains it all.

## Your MCP servers

When the company allows it, you can add MCP servers of your own under **Your MCP servers**. Only you see such a server, and only your own conversations use it, with your own account on it.

1. Choose **Add your MCP server**.
2. Give it a **Name** and its **Address**: the server's MCP endpoint (streamable HTTP), such as `https://mcp.example.com/mcp`. Tinfirm calls it from its own server, so the address must be public; Tinfirm refuses addresses it may not call, and says why.
3. Optionally, name the **Header for your token**. Empty, your token goes as `Authorization: Bearer`.
4. Choose **Add server**. The server's page opens: confirm there what each of its tools does, whether it reads or writes. A tool nobody confirmed counts as one that writes.
5. Back on **Accounts**, connect your account on it, by signing in or with a token, unless it needs none.

**Settings and tools** on the server's row opens its page again. To let an agent use it, add it in your [My settings](my-settings.md) for the agent. When the company switches personal MCP servers off, your servers stay, marked **Switched off**, and agents use them again only once an Admin switches them back on. [MCP servers](../admin-guide/mcp-servers.md) says more about servers and tool effects.

## Your runner hosts

A runner host is a machine that runs agents' turns in sandboxes. The company runs its own; you can add a personal one, such as your own computer with Docker. A personal runner host runs only your own work, paid by a subscription of yours on it: a Claude or ChatGPT subscription, or an Anthropic or OpenAI API key of your own. The subscription never leaves the machine, and nobody else's work runs there.

A host of yours is yours in every company you belong to on this Tinfirm: register it in one, and it appears on the **Runner hosts** tab of each, under the same name, with the same slots and subscriptions, over one connection. Its slots are shared: a turn it runs for one company takes a slot the others see in use. When you join a company, your hosts are there from the start; when you leave one, they stop serving that company and keep serving the others.

### Register one

1. On the **Runner hosts** tab, choose **Register a runner host**.
2. Give it a **Name**: letters, digits, dots, dashes and underscores.
3. The dialog shows the host's token once. Copy it now: Tinfirm keeps only its hash. If you lose it, remove the host and register it again.
4. Run the **Next steps** on the machine, in order: log the host in to Tinfirm with the token, add your subscription (`tinfirm-runner-host credential add claude`, or `codex` for ChatGPT), build the runner images, check everything with `doctor`, and start serving runs. **Copy all** copies the commands.

[Install and run runner hosts](../operator-guide/runner-hosts.md) explains the host program, Docker and the sandboxes.

To pay with the host, pick its subscription as the payer in your [My settings](my-settings.md) for an agent. [Your budget and spend](spend.md#pay-with-a-subscription-on-your-runner-host) explains what that changes.

### What the tab shows

Each host shows whether it is **Online**, or **Offline** and when it was last seen, and **Draining** while it finishes its runs before it stops. Under it:

- **{n} of {m} slots in use**, and **Up to {k} on this machine**: the ceiling set on the machine. **Runs at a time** lets Tinfirm run fewer, from 1 up to that ceiling, or **Use the machine's limit**. A change applies without a restart and never stops a running run; until the host applies it, the card says so.
- Its subscriptions as the host reported them, each with its runtimes and its state: **Available**, **Cooling down** (with when it is usable again), **Invalid** or **Host offline**. A subscription the host did not report when it last connected cannot pay for runs.
- A warning when the host's runner is too old for turns paid by a budget.

### Remove one

The bin on the host's card removes it, after you confirm, from every company you belong to. Its token stops working at once, and your conversations paid by its subscriptions wait until another host of yours can run them. A host that is running a turn, in any of your companies, cannot be removed until the turn ends. You can register the machine again later.

## When something goes wrong

| What you see | Why | What to do |
|---|---|---|
| **Paste a token** says the system answered 401 | The token is wrong, expired or revoked; on Jira Cloud, the email or the API token | Create a new token and paste it again |
| No **Sign in with {system}** | The system's sign-in app is not set up, or it is a git host | Paste a token, or ask an Admin to set up the app |
| **Sign in again** with an error such as *invalid_grant* | The system refused to refresh your sign-in | Sign in again |
| Back from a sign-in: *You declined at the system, so nothing was connected.* | You did not allow Tinfirm at the system | Sign in again and allow Tinfirm, or ask an Admin whether the system's app allows it |
| Back from a sign-in: *The system did not hand over the account. Try again, or paste a token instead.* | The system's sign-in app is not set up as the system expects | Try again, or paste a token. If it keeps failing, an Admin checks the system's sign-in app |
| Back from a sign-in: *The system refused the account.* | The system refused the account you signed in with | Try again, or paste a token |
| Back from a sign-in: *The sign-in did not include this Jira site.* | You did not pick the system's site when you allowed Tinfirm at Atlassian | Sign in again and pick that site |
| Back from a sign-in, on the start page, and nothing was connected | The sign-in was used already, or started in another browser or session | Start again from the same page, in the same browser, once |
| *You may no longer connect this account.* | You were suspended, lost the right to manage the system, or the company switched personal MCP servers off | Ask an Admin |
| *That account on the system is one of the company's credentials there* | Your user at the system is the user of a company credential | Ask an Admin to give the credential another user, such as a machine user; then connect yours |
| A conversation still acts through your group's credential after you connected your own | Its account was chosen before, and never switches | Start a new conversation |
| An agent's write is refused although the agent allows it | You act through a read-only grant | Connect your own account, or ask an Admin for read and write |
