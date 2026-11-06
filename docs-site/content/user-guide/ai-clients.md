# Work with Tinfirm from your AI client

Each company in Tinfirm has an MCP endpoint. Connect an AI client on your computer to it, Claude Code for example, and the client can work in Tinfirm as you: read your inbox, start and follow your conversations with agents, and, if you allow it, answer questions and approvals for you.

The client signs in with your Tinfirm login, and you choose on a consent page what it may do. It never gets more rights than you have in that company. You must be a member of the company: an instance admin who is not one cannot connect a client to it.

## Connect Claude Code

You need the company's endpoint address. Open the company in the web app: its address is `https://<your Tinfirm>/c/<company id>/inbox`. The endpoint is:

```text
https://<your Tinfirm>/api/companies/<company id>/mcp
```

1. Add the endpoint to Claude Code, with a name for the company:

    ```sh
    claude mcp add --transport http tinfirm-acme https://tinfirm.example.com/api/companies/0190f5a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b/mcp
    ```

    A client signs in to one company at a time. If you work in several companies, add one server for each, with its own name (`tinfirm-acme`, `tinfirm-globex`).

2. In Claude Code, run `/mcp`, pick the server and choose to authenticate. Your browser opens.

3. Sign in to Tinfirm as you always do: email and password, or single sign-on. The login says that an app asks to connect, and brings you back to its request.

4. The consent page asks **Allow Claude Code to use Tinfirm as you?** It names the app, the domain the app is published from, the company, and what the app may do. Check that the domain is `claude.ai`: an app can call itself anything, but it cannot change where it is published.

5. Choose a level (below) and press **Allow**. The browser hands you back to Claude Code, which keeps its sign-in in its credential store (on macOS, the keychain).

You stay signed in as long as you use the client at least once every 30 days. Otherwise, authenticate again from `/mcp`.

## Choose what the client may do

| Level | The client may |
|---|---|
| **Read** | Read your inbox, your conversations and decisions, the agents you may use and the notes they keep for you, the triggers and systems you see, and the spend you may see. |
| **Read and write** | Also start conversations, send messages in them, choose accounts, cancel, retry and end turns and conversations, delete your memory notes, and test a system you manage. It never decides. |
| **Read, write and decide** | Also answer your questions and approve or deny your approvals, as you. A team's approvals are still decided in the web app only. |

A client asks for the levels by their OAuth scopes: `tinfirm:read`, `tinfirm:write` and `tinfirm:decide`. The consent page offers all three whatever the client asked for, and selects the one it asked for. Claude Code asks for **Read**, so the consent page selects it first. Choose a higher level there if you want the client to act. The page never selects **Read, write and decide** for you, even when a client asks for it: choose it yourself, only for a client you sit in front of, and only when you mean to decide from it.

!!! warning "What a client does in Tinfirm, it does as you"

    Keep Claude Code asking you before every tool that changes something: every tool of the **Read and write** and **Read, write and decide** levels, among them `start_conversation`, `send_message`, `choose_account`, `retry_turn`, `cancel_turn`, `end_conversation`, `delete_memory_note`, `test_system` and `decide`. [MCP tools](../reference/mcp-tools.md) lists each tool's level. Never put any of them in its allow-list (`/permissions`, or `permissions.allow` in a settings file): they choose the accounts agents act with, spend your budget, delete your notes and decide as you. Never allow a wildcard such as `mcp__tinfirm-acme__*` either, which takes them in, and every new tool later. Allowing a read tool by name, such as `mcp__tinfirm-acme__get_inbox`, changes nothing in Tinfirm, but it lets the client read without asking: allow only the reads you need, and remember that what they return is other people's words.

    What the client reads (messages, agents' results, questions, a trigger's instruction, a webhook's deliveries, an MCP server's tool descriptions, a system's errors) is other people's words, and may try to steer it, for instance to approve a pending request. Tinfirm marks those words in every result and tells the client to treat them as data, but it cannot make a client resist them. Your confirmation of each change is the guard: when the client proposes one you did not ask for, refuse it.

When a tool needs a higher level than the client has, Tinfirm refuses the call, and Claude Code says that the call needs additional permissions. It does not sign in again by itself. To give it a higher level, disconnect it (below), or clear the server's authentication in `/mcp`, and authenticate again from `/mcp`, choosing the new level on the consent page.

You can also set the scopes in the server's configuration, so that Claude Code asks for them and the consent page selects that level. In `.mcp.json`:

```json title=".mcp.json"
{
  "mcpServers": {
    "tinfirm-acme": {
      "type": "http",
      "url": "https://tinfirm.example.com/api/companies/0190f5a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b/mcp",
      "oauth": {"scopes": "tinfirm:read tinfirm:write"}
    }
  }
}
```

## What you start from a client is yours

A conversation you start from a client is a personal conversation, like one you start in the web app: the agent acts with your own accounts and is paid from your budget. In the web app, the conversation's page shows the badge **AI client** in its header. If the agent needs an account you have not connected, connect it on the **Accounts** page.

A company's Owners can read any conversation in full, including these. Each such read is recorded, and you are shown that it happened. The consent page says so before you allow a client.

Through a client you see only your own conversations, even as an Owner: another person's conversation is read in the web app, where the read is recorded and shown to them, never through a client.

## Disconnect a client

On your **Profile**, **Connected apps** lists every client you allowed, for each company, with its level, when you allowed it and when it was last used. **Disconnect** stops it at once. Tinfirm's Outlook add-in is listed there too, as **Tinfirm for Outlook**. The Google accounts you linked for the Gmail add-on are under **Gmail**, below it, each with **Disconnect**.

Removing the server from Claude Code (`claude mcp remove tinfirm-acme`) does not disconnect it in Tinfirm. If you lose a computer, disconnect its client on your Profile.

When you leave a company, are suspended in it, or your Tinfirm account is deactivated, your clients stop working there.

## Without a browser: a personal access token

For a script, a CI job, or a machine without a browser, create a token under **Profile**, **Personal access tokens**, with **New token**. Its **Scope** is one of the same three levels, named **Read**, **Write** and **Decide** there. Choose the lowest the work needs: **Read** for anything that runs unattended.

Give the token to the client as a header, and keep it out of files you commit. In Claude Code, refer to an environment variable:

```sh
claude mcp add --transport http --scope project tinfirm-acme \
  https://tinfirm.example.com/api/companies/0190f5a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b/mcp \
  --header 'Authorization: Bearer ${TINFIRM_TOKEN}'
```

The single quotes keep `${TINFIRM_TOKEN}` as written; set `TINFIRM_TOKEN` where Claude Code starts. A token is yours, not a company's: it works in the companies you name when you create it, or in every company you are a member of when you name none. Creating one asks for your current password; if you sign in through your organisation's identity provider, sign in again first when your sign-in is more than 10 minutes old. A token expires 90 days after you create it; renew it on your **Profile** before then. Changing your password, an administrator's reset of it, and leaving a company the token names revoke it. Create one token per purpose, and revoke a token you no longer use on your **Profile**.

## Other clients

Any MCP client that publishes a client metadata document signs in as Claude Code does, and the consent page names the domain the document comes from. Tinfirm does not let clients register themselves, so a client that can only do that cannot sign in: give it a personal access token as a header instead, if it can send one, or ask its maker to publish a metadata document.

For developers, Tinfirm knows the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) without a document. Start it with `npx @modelcontextprotocol/inspector`, choose the **Streamable HTTP** transport and the endpoint's address, and enter the client ID `mcp-inspector` in its OAuth settings. It must run on `localhost`, on its default port 6274.

## What a client cannot do

A client works with conversations, decisions and memory notes. Agents, your settings, systems, triggers, accounts, members, secrets and runner hosts are changed in the web app only, and no tool ever returns a secret. [MCP tools](../reference/mcp-tools.md) lists every tool and the level it needs.
