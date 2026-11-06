# Give agents the tools of an MCP server

An MCP server is a system whose tools agents can call: search your CRM, read your wiki, query a database. You register the server once. Tinfirm reads its tools into a catalogue, you confirm what each tool does, and each agent gets the tools it needs. This page is for Owners and Admins. It also covers the MCP servers members add for themselves, and the switch that turns those off.

What every system shares, such as credentials, lending, **Test**, pausing and removing, is in [Register systems and lend their credentials](systems.md).

## How it works

| Step | What happens |
|---|---|
| Register | You register the server on **Systems**, with its endpoint |
| Account | Each call acts with an account: a person's own, a company credential lent to a group or a service account, or none for a server that checks no credential |
| Catalogue | **Refresh tools** reads the server's tools. You confirm whether each one reads or writes, and may switch any off |
| Agent | Whoever edits an agent gives it the server's tools, each **Allowed** or **Needs approval** |
| Run | A run lists the agent's tools as `{handle}_{tool}`, for example `crm_search_customers`. The Tinfirm server makes each call with the conversation's account and gives the result to the agent |

The agent never sees the server's address or token, and cannot call a tool it was not given. Your server sees the account the conversation acts with, and answers each person by its own rules.

## What the server must offer

Share this with whoever runs the server:

- **MCP over Streamable HTTP** at one https URL, with the `initialize`, `tools/list` and `tools/call` requests. Any official MCP SDK does this.
- **A certificate the Tinfirm server trusts**: a public certificate authority, or your own one added by your operator.
- **Reachable from the Tinfirm server**, which reads the catalogue and makes every call. Runner hosts and sandboxes never reach the server.
- **A credential, or none**: a bearer token, a token in a header of its own name, a sign-in with OAuth, or nothing for a server only Tinfirm's network reaches. Map each token or sign-in to a user and enforce that user's permissions.
- **Answers in time**: each request within 20 seconds and each tool call within 60 seconds, unless your operator changed them.
- **Small results**: arguments up to 64 KiB and results up to 800 KiB. Give tools a `limit` or a page.
- **Text results**: text reaches the agent as it is. An image, audio or a resource in a result is replaced by a text that names its type.
- **Tool names** of 1 to 64 letters, digits, `_` and `-`, unique ignoring case. A tool with another name is skipped.
- **Refusals that mean something**: a `401` or `403` to `initialize` tells Tinfirm the account was refused, so it asks for a new token or a new sign-in. A `403` to a tool call is the tool's answer for that account: the agent sees it as the tool's error.

## Register a company MCP server

1. Open **Systems**, choose **Register a system**, and the kind **MCP server**.
2. **Details**: the **Name**, and the **MCP endpoint**, the URL the server answers MCP at, for example `https://tools.example.com/mcp`. Use https, without a user name, password, query or fragment.
3. **Settings**: **Token header** (optional) is the header a token goes in. Leave it empty for `Authorization: Bearer`. Name another header, such as `X-Api-Key`, if the server reads the token there.
4. Choose **Register**.

The name makes the handle that prefixes the server's tools in a run: *CRM* gives `crm`, and the tool `search_customers` is `crm_search_customers`. A rename keeps the handle.

Right after registering, Tinfirm looks for the server's sign-in and sets it up where it can ([Sign in with OAuth](#sign-in-with-oauth)). It does not read the tools yet: **Tools** says *No tools read yet.* Then:

1. **Test**. Without any account, it says *The server answers without a token* or *The server answers and asks for a token*.
2. Choose how calls sign in: **Add credential** and lend it to groups and service accounts, let members connect their own accounts, or set the server to need no account ([A server that needs no account](#a-server-that-needs-no-account)).
3. **Refresh tools**, and confirm what each tool does.
4. Give the tools to agents.

## Sign in to the server

Every call acts with one account. The order is the same as on every system:

| Who calls | The account |
|---|---|
| A person's own conversation | Their own account on the server, else a credential lent to one of their groups |
| A trigger's conversation | What the trigger names: its author's own account, or the credential lent to its service account |
| **Test**, **Refresh tools** | The credential you pick beside the button; else your own working account; else the server's only enabled, working credential; else none |
| Any call to a server that needs no account | None |

A person who holds no account and no lent credential is asked to connect one when the agent first calls the server: the call waits and runs once they connect.

Tinfirm knows whether a tool writes only from what you confirm ([Confirm what each tool does](#confirm-what-each-tool-does)). A credential lent read-only refuses the tools confirmed or counted as writes, and nothing more. Scope the token or the sign-in at the server too, so that the limit holds there.

### With a token

Paste the token the server issued, as a company credential (**Add credential**, **Paste a token**) or as a member's own account under **Accounts**. Tinfirm checks it with an `initialize` call in the **Token header** and keeps it only when the server accepts it. A token from a sign-in always goes as `Authorization: Bearer`, whatever the token header.

### Sign in with OAuth

A server that offers OAuth is signed in to through its **Sign-in app**. Tinfirm finds the server's authorization server as the MCP specification describes, when the server is registered and whenever you set the app up.

- **Set up by itself.** Where the authorization server accepts Tinfirm's client metadata document, or lets clients register themselves, nothing needs registering by hand. The health strip says **Sign-in app: set up**, and the card's **Sign-in at** row shows the authorization endpoint found.
- **Set up by hand.** When the authorization server offers neither, register a client there with the **Callback URL** shown on the card, for example `https://tinfirm.example.com/api/oauth/callback`. Then choose **Set up the app** and enter its **Client ID**, its **Client secret** (empty for a public client), and **Scopes** if you want others than the server's. Choosing **Set up the app** with the client ID empty looks for the sign-in again and says what is missing.

Once the app is set, **Add credential** offers **Sign in to** the server, and members can sign in on **Accounts**. Tinfirm refreshes access tokens before they expire. It revokes a grant at the server when its credential or account is removed, where the server has a revocation endpoint. Removing the whole server revokes nothing: revoke the grants at the server.

??? note "What the server's authorization side must support"

    - Answer an `initialize` without a token with `401` and a `WWW-Authenticate: Bearer` header naming its protected-resource metadata, or serve that document at `/.well-known/oauth-protected-resource`. It names the authorization servers and, as its `resource`, the MCP endpoint exactly.
    - Publish authorization server metadata with the authorization and token endpoints, `code_challenge_methods_supported` listing `S256`, and if it has them, the revocation and registration endpoints and `scopes_supported`.
    - Take Tinfirm as a client by a client ID you enter, by Tinfirm's client metadata document, or by dynamic registration, with the callback URL as a redirect URI. The authorization server must reach your Tinfirm's public address to fetch the metadata document.
    - Accept the `resource` parameter, issue access tokens for your MCP endpoint, and issue refresh tokens.

## Confirm what each tool does

The server's **Tools** tab shows its catalogue as the last refresh read it, with each tool named as a run names it.

**Refresh tools** reads the server's tools again, with the account picked beside it (**With the usual account** or **With** a credential). It says what changed, for example *Read the tools again. 2 tools added, 1 tool removed.*

- **New tools** join the catalogue unconfirmed.
- **A tool the server no longer lists** leaves the catalogue. Runs no longer list it.
- **A tool whose description, input schema or hints changed** loses its confirmation and must be confirmed again. A changed title alone keeps it.
- **A failed read** keeps the catalogue as it was. *Couldn't read the server's tools* also sets the server's status to **Error**. *The server asks for a credential, and none was given* means the server was reached: add a credential (the button is offered there), and the tools are read with it at once.

**Every tool counts as a write until you confirm it.** That holds everywhere: agents ask before each call to it, and a read-only credential never calls it. The tab shows the number **to confirm** beside its name. Under **To confirm**, choose **Reads or writes?** for each tool, then:

| Choice | Means |
|---|---|
| **It only reads** | Agents may run it without asking, where their definition says so |
| **It writes** | It counts as a write: a read-only grant never calls it |

The server's own hints only suggest an answer: *Its hints say it only reads* (`readOnlyHint`), *Its hints say it writes* (`destructiveHint`, or `readOnlyHint` false), or *It sends no hints*. The choice the hints point at is marked **Its hints say so**, but nothing is confirmed until you choose. Confirm effects as they are: Tinfirm knows a write only by your word.

Confirmed tools are listed under **Confirmed**, where the **Reads** and **Writes** pills change the effect. Each tool also has a switch, **Offered** or **Off**: an agent whose definition names an offered tool gets it, and no agent gets a tool that is off.

## Give the tools to agents

On an agent's page, whoever may edit it adds the server under **Systems and tools**, **Add a system** ([Make and share an agent](../user-guide/agents.md)). A server new to the agent brings every tool it offers: confirmed reads **Allowed**, writes and unconfirmed tools **Needs approval**. **Needs approval** makes each call wait for an approval, from the person in their own conversation, or from the trigger's team.

- An **Allowed** tool that later counts as a write, because you confirmed it writes or a refresh dropped its confirmation, needs approval again until the agent's editor allows it again.
- A person's settings, or a trigger's, may switch the server off or make an allowed tool need approval, never the reverse.
- A run lists a tool only when the server offers it, the server is not paused, the agent has it, the settings leave it on, and the conversation's account may use it.
- An agent holds at most 100 systems and 200 tools of one system. A run lists at most 200 tools in all.

## A server that needs no account

Some servers check no credential: they are reachable only from Tinfirm's network, and their network policy is their boundary. Set such a server to need no account, rather than give every person and service account a token it ignores.

1. On the server's page, choose **Change** and tick **Needs no account**: *The server checks no credential, as one reachable only from Tinfirm's network. Runs call it without one, and nobody connects an account or names one in a trigger. Tinfirm checks that it answers first.*
2. Choose **Save**. Tinfirm calls `initialize`, then `tools/list`, without a credential. If the server does not answer both, the change is refused with its answer, and nothing changes.

The setting is offered when you change a server, not when you register it. From then on, every conversation calls the server without a credential, and a trigger needs no account there. The health strip and **Accounts** say **No account needed**, and the trigger editor shows *no account needed*. Accounts and credentials connected before stay, but are not used.

Set it only on a server that truly checks nothing, and only when nothing but Tinfirm can reach it. Anyone who can reach the server can call it as Tinfirm does.

Untick **Needs no account** to need accounts again. A person's conversation that used the server then waits for an account at its next call. A trigger's conversation cannot use it until the trigger names an account there. If a server starts asking for a credential while set to need none, its calls are refused and the agent says so.

## Move a server to a new endpoint

When the server moves to another host, port or path, **Change** takes the new **MCP endpoint**. A token is sent only where it was given, so saving the new endpoint:

- removes every account and credential on the server, with their lending (the form warns before it saves);
- removes the Sign-in app found at the old address, and looks for one at the new address;
- makes the server **Not tested yet** again.

It keeps its name, handle, tools with their confirmed effects, agents' tools and triggers. Afterwards, add a credential for the new address and lend it again, ask the people who used the server to connect their accounts again, and choose **Refresh tools**. A tool whose definition differs at the new address loses its confirmation.

## Servers on your own network

Tinfirm reaches MCP servers over https only, and refuses private addresses. For a company server on your own network, ask your operator to name its host as an internal host: that host may then be reached at a private address and over http. Your operator may also let every company system reach private addresses. [Server configuration](../reference/configuration.md) lists the settings. When the Tinfirm server's outbound traffic is limited, your operator also allows the server's host.

## Members' own MCP servers

Any member may add an MCP server of their own, under **Accounts**, **Your MCP servers**, **Add your MCP server**: a **Name**, the **Address** (*The server's MCP endpoint (streamable HTTP), over https.*) and a **Header for your token (optional)**.

- **Only its owner** sees it, changes it and uses it. To anyone else, Admins and Owners included, it does not exist. Its page says *Your own MCP server: it acts for you only, with your own account, and nobody else sees it, admins included.*
- **Its owner manages it** as you manage a company server: the Sign-in app, **Test**, **Refresh tools**, confirming effects, switching tools off, **Needs no account** and removing it. They connect their own account on it under **Accounts**.
- **It holds no company credential**, cannot be paused, and goes when its owner leaves the company.
- **It must be on a public address.** It never reaches a private address, whatever your operator allows, and internal hosts do not apply to it.
- **Its tools reach only its owner's conversations**, through **My settings** of each agent, under **Your own MCP servers** ([My settings for an agent](../user-guide/my-settings.md)), never through an agent's definition. A trigger may add its author's own servers only while it acts with the author's own accounts everywhere.

The company decides whether members may do this, under **Settings**, **General**, **Members**: **Their own MCP servers**, on by default ([Company settings](settings.md)). Turning it off asks first: *The servers members registered stay, but no conversation uses them, and nobody can register a new one until you turn this on again.* **Kill switches** then shows *Members' own MCP servers are off*, and a conversation that needs such a server says it is switched off.

## Keep it safe

- **A credential is the company's.** Whoever it is lent to acts at the server as its user. Lend it read-only where you can, and give its user at the server only what the groups and service accounts that borrow it need.
- **The server receives what agents pass to its tools.** A call's arguments go to the server as the agent wrote them, a read tool's too, and they can include what the agent read elsewhere in the conversation: a ticket, a file, another system's answer. Nothing asks before such a call. The server's page says so under its name, and calls a server that is not on one of your operator's internal hosts, a member's own included, *a third-party server*. Give an agent a third-party server's tools only where what its conversations read may go there. A call's arguments are at most 64 KiB as JSON; a larger call is refused and nothing is sent.
- **What a tool returns comes from outside.** A tool that reads a ticket or a web page can return text written to steer the agent. Tinfirm tells the agent that tool results are other people's words, but the agent may still follow them. Give agents that read such content no tool that writes to anything they could be steered against.
- **Confirm effects as they are.** Tinfirm knows a write only by your word. Leave writes at **Needs approval** on agents unless their work needs otherwise, and switch off what no agent should call.
- **Rotate a token** by storing the new one on the credential, or a member on their account, then revoking the old one at the server.
- **The Tinfirm server calls the endpoint you entered**, with the account's token. If it should reach nothing else on your network, ask your operator to limit its outbound traffic to the systems it needs.

## Limits

| Limit | Value |
|---|---|
| A tool's name | 1 to 64 letters, digits, `_` and `-`, unique on the server ignoring case |
| A tool's title and description kept | 200 and 4000 characters |
| Pages of the server's tool list read | 10 |
| Tools of one server | 500, each schema at most 64 KiB, 2 MiB in all; each page of the list at most 1 MiB. Beyond, **Refresh tools** fails and says which limit |
| Refreshes of one server | One a minute |
| A tool's name in a run | At most 64 characters; a longer one is cut and given a short hash |
| Arguments of a call | 64 KiB as JSON; a larger call is refused, never sent |
| A result given to the agent | 800 KiB |
| The token header | 1 to 64 letters, digits and `-`, not a header Tinfirm or the transport sets |
| Systems on one agent | 100, with at most 200 tools of one system |
| Tools in one run | 200, every system counted |

## Troubleshooting

| What you see | Cause | What to do |
|---|---|---|
| *Couldn't read the server's tools: … answered 404 to initialize* (or 405) | The endpoint is not the server's MCP URL | **Change** it to the URL the server answers MCP at, often ending in `/mcp` |
| *The server's answer to initialize is not a JSON-RPC message with the request's id* | The URL answers a web page or another API | The same |
| *The server asks for a credential, and none was given* | The server wants a token, and nobody's account or credential could be used | **Add a credential**, or connect your own account under **Accounts** |
| *The server refused the account it was read with* | The token is wrong or revoked, the **Token header** is not the one the server reads, or the sign-in was revoked | **Store a new token** or **Sign in again**; check the token header with **Change** |
| **Needs no account** is refused | The server asks for a credential, or does not answer `tools/list` without one | Keep it needing accounts |
| **Set up the app** says the server offers no sign-in | It answers without a token, its discovery failed, or Tinfirm could not register | Read the reason, fix the server's metadata, or use tokens |
| A tool is missing from an agent's run | It is switched off or gone, the agent does not have it, the person's settings switch it off, the server is paused, or the tool writes and the conversation acts through a read-only credential | The **Tools** tab, the agent's **Systems and tools**, the settings |
| The agent sees *The server refused tools/call: …* | The server answered the call with an error | Its message is in the result: fix the arguments or the server |
| The agent's call is refused as too large | A result above 800 KiB | Let the tool take a `limit` or a page |
| The agent's call fails after a minute | The tool took longer than the tool timeout | Make the tool answer within 60 seconds |
