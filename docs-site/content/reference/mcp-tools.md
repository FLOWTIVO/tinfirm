# MCP tools

The tools a company's MCP endpoint offers to an AI client, and the level each needs. [Work with Tinfirm from your AI client](../user-guide/ai-clients.md) explains how to connect one and choose its level.

Every tool works in the company of the endpoint, as you, with the same rules as the web app: what you cannot see there is "not found" here. A client sees every tool in its list, and a call beyond its level is refused.

## Your work

| Tool | What it does | Level |
|---|---|---|
| `get_inbox` | What waits for you: your decisions, and your conversations that wait on an account, failed, or wait on a runner host or your budget | Read |
| `list_agents` | The agents you may use, with their handles and status | Read |
| `get_agent` | One agent, by id or `@handle`, and whether it is ready for you: who would pay, which account it would use on each system, and whether a runner host can run it | Read |
| `list_conversations` | Your conversations, newest activity first | Read |
| `get_conversation` | One of your conversations: its accounts, cost, latest result or failure, open decisions, outputs and newest messages | Read |
| `list_decisions` | The questions and approvals of your conversations, newest first | Read |
| `get_decision` | One decision: its question and options, or the call it approves, and its status | Read |
| `start_conversation` | Start a conversation with an agent, your message its first request, with files you held for it (`fileIds`, below) | Read and write |
| `send_message` | Send your message in one of your conversations, optionally as the answer to its open questions | Read and write |
| `choose_account` | Pick the account a waiting conversation should use on a system | Read and write |
| `cancel_turn` | Cancel a conversation's running or queued turn and its open decisions; the conversation stays open | Read and write |
| `retry_turn` | Run a conversation's failed or cancelled last turn again | Read and write |
| `end_conversation` | End a conversation for good | Read and write |
| `decide` | Answer a question or decide an approval of yours. A team's approvals are decided in the web app only. | Read, write and decide |

## Memory notes

| Tool | What it does | Level |
|---|---|---|
| `list_memory_notes` | The notes an agent keeps for you, and whether memory is on for you | Read |
| `delete_memory_note` | Delete one of your notes, by its number | Read and write |
| `list_trigger_memory_notes` | A trigger's notes and the notes its conversations proposed, for its author, its team, Owners and Admins. Proposals are accepted or rejected in the web app only. | Read |

## How the company is set up

| Tool | What it does | Level |
|---|---|---|
| `list_triggers` | The triggers you see: their source, system, agent, status and last firing | Read |
| `get_trigger` | One trigger as its page shows it: its events and conditions, its agent and instruction, the accounts its runs use, who pays, its team and its status | Read |
| `list_trigger_firings` | A trigger's firings, newest first, and for each one whether it started a turn or why it did not | Read |
| `list_systems` | The systems you see, and your own MCP servers | Read |
| `get_system` | One system's settings, never a secret | Read |
| `list_system_tools` | A system's tools, whether each reads or writes, and whether it is enabled | Read |
| `list_webhook_deliveries` | A webhook system's recent deliveries and what each fired. Owners and Admins. | Read |
| `try_webhook_delivery` | Show how a webhook system would read a body, and which triggers it would fire, without storing or firing anything. Owners and Admins. | Read |
| `test_system` | Test whether Tinfirm reaches a system you manage, as its **Test** button does | Read and write |
| `get_spend` | The spend you may see, as the **Spend** page shows it | Read |

## Files for a start

A client can give a new conversation files, as the Outlook add-in gives it an email's attachments: it sends each file first, one multipart `file` in a `POST` to the endpoint's `/files` (`/api/companies/{companyId}/mcp/files`) with the same token, and then names the ids it was answered in `start_conversation`'s `fileIds`. This needs **Read and write**. Tinfirm checks and scans each file as an upload in the web app (its name, its size, a secret or a credential in it), holds it for an hour for you only, and the start moves it into the conversation, where the agent finds it among the conversation's files from its first turn. You hold at most as many files and bytes as a conversation takes.

## Limits

- **Requests:** each token or connected client may send 120 requests a minute, unless the operator changed the limit. Beyond it, the endpoint answers that the client must wait.
- **New conversations:** at most 30 an hour per person and company start from AI clients, the Outlook and Gmail add-ons included, unless the operator changed the limit.
- **Results:** a result's text for the model is cut at 40,000 characters; the full result is in its structured content.
- **Changes:** no tool changes agents, settings, systems, triggers, accounts, members, secrets or runner hosts, and no tool returns a secret. Files go through `/files`, never through a tool's arguments. `test_system` only records the system's last test, as the **Test** button does.
