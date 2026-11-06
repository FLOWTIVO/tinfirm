# Kill switches

This page is for a company's Owners and Admins. It explains each way to stop agents' work in a hurry: what each switch stops, what keeps going, who may undo it, and what members see meanwhile.

Kill switches are in every edition. A switch stays on until a person turns it off: nothing is undone by time.

| Switch | Where you pull it | Who may undo it |
|---|---|---|
| [Stop every run](#stop-every-run-of-the-company) | **Kill switches** | Owners and Admins |
| [Pause a system](#pause-a-system) | **Kill switches**, or the system's page | Owners and Admins |
| [Suspend a member](#suspend-a-member-or-a-service-account) | **Members** | Owners and Admins; only an Owner for an Owner |
| [Suspend a service account](#suspend-a-member-or-a-service-account) | The service account's page | Owners and Admins |
| [Pause an agent](#pause-an-agent) | The agent's page | The agent's owner, Owners and Admins |
| [Pause a trigger](#pause-a-trigger) | The trigger's page | Its author, Owners and Admins (its team may pause, not resume) |
| [Turn members' own MCP servers off](#turn-members-own-mcp-servers-off) | **Settings**, **General** | Owners and Admins |

## The Kill switches page

**Kill switches**, in the sidebar's **Company** section, shows everything that is switched off in the company, who did it, when and why:

- **The company's runs**: **Runs go as usual**, or **Stopped** with who stopped them and why.
- A count of each kind that is on, such as *2 paused systems* or *1 suspended member*.
- **Paused systems**, each with **Resume**, and **Pause a system**.
- **Suspended**: the members and service accounts that act, pay and decide for nothing. A member resumes here or on **Members**; a service account on its own page.
- **Also switched off**: paused agents, paused triggers, and **Members' own MCP servers are off**. Each is undone on its own page, which its name links to.

Stopping the runs, pausing a system and suspending a member ask for a **Reason**, up to 500 characters. It is kept with the switch and shown on this page, never written to the audit log. The audit log records who pulled each switch and when.

## Stop every run of the company

Use it when agents must stop now, everywhere: a leaked key, an agent doing damage, a runaway cost.

1. On **Kill switches**, choose **Stop every run**.
2. Give a **Reason**, and choose **Stop every run**.

Every running turn is cancelled at once; the notice says how many. Until you lift the stop:

| Stops | Keeps going |
|---|---|
| No turn runs: queued turns wait | Messages and answers people send are kept, and their turns wait for the lift |
| No conversation starts, from the app, an AI client, a chat or a trigger | People can still read everything |
| No approval can be granted, and a call approved earlier does not run | Questions can still be answered, and approvals denied |
| No trigger fires; its conversations' write-backs to trackers are held | Triggers' polls go on, but nothing they see fires |
| A webhook delivery is refused with `503` and `Retry-After: 300`, nothing stored, so that the sender delivers it again later | |
| A mention of the bot in a channel fires nothing and is never answered later; a direct message that would start a conversation starts none. The chat user is told, at most once an hour | A direct message to an open conversation is kept, and its turn waits |

### Lift the stop

1. On **Kill switches**, choose **Lift the stop**.
2. The confirmation lists the other switches that stay on. Choose **Lift the stop**.

Waiting turns are dispatched, held write-backs go out, and every active trigger fires again from now on. What happened during the stop never fires a trigger. The page then says what went on again: how many triggers fire again and how many held write-backs went out.

## Pause a system

Use it when one system must not be touched: a tracker with a compromised credential, an MCP server that misbehaves.

1. On **Kill switches**, choose **Pause a system**.
2. Pick the **System**. You pause a company system here; a member's personal MCP server is stopped with [the company's switch](#turn-members-own-mcp-servers-off).
3. Tick **Also cancel the turns running now in conversations that use it** if they must stop at once.
4. Give a **Reason** and choose **Pause**.

While it is paused:

- its accounts and credentials are used for nothing: agents are not offered its tools, and a call to one is refused because the system is paused;
- a turn whose workspace holds one of its repositories waits;
- testing it, refreshing its tools and connecting accounts to it are refused;
- every trigger that watches it, or acts with an account on it, pauses (**An admin paused its system**), and its write-backs wait;
- a webhook system answers deliveries `200` with `{"paused": true}`: nothing is stored, and they never fire later.

### Resume a system

Choose **Resume** beside it. Its accounts work again and waiting turns go on. When its pause paused triggers, the dialog lists them, with **Resume its triggers too** ticked. Each trigger resumes from now on: nothing that happened during the pause fires it. A trigger you leave out stays paused until its author or an admin resumes it.

## Suspend a member or a service account

A suspended member or service account acts, pays and decides for nothing until resumed:

- every running turn that acts as them, with their accounts or on their budget is cancelled;
- their model key is blocked;
- they decide no question or approval, neither as a conversation's person nor as a trigger's approver;
- a suspended member opens nothing in the company but its **Kill switches** page, and their tokens and AI clients stop working there.

Suspend a member on **Members**: [Suspend and resume a member](members.md#suspend-and-resume-a-member). Suspend a service account on its own page: [Service accounts](service-accounts.md).

Triggers written by a suspended member pause, and stay paused after the resume until they or an admin resume each. A trigger that acts with a suspended person's account pauses too. A trigger paid by a suspended service account waits, and goes on by itself once it is resumed.

When an instance admin deactivates a person's Tinfirm user, they are suspended in every company, with *Their Tinfirm account was deactivated; reactivating the user resumes them.* No Owner or Admin can resume such a suspension: an instance admin reactivates the user.

## Pause an agent

On the agent's page, its owner, an Owner or an Admin chooses **Pause**, and then **Pause agent**. With **Also stop the turns running now**, its running turns are cancelled. Until it is resumed:

- its conversations start no turn and run no approved call; their turns wait;
- questions can still be answered, but approvals wait;
- its triggers skip their firings, **Skipped: the agent is paused**;
- people see that it is paused.

**Resume** on its page undoes it. [Make and share an agent](../user-guide/agents.md) says more.

## Pause a trigger

On the trigger's page, its author, a member of its team, an Owner or an Admin chooses **Pause**, and then **Pause trigger**. With **Also stop the turns running now**, its conversations' running turns are cancelled. It fires nothing, its conversations take no new turn and nothing is written back until it is resumed, and what happens meanwhile never fires it.

Only its author or an Owner or Admin resumes it. [Write a trigger](../user-guide/triggers.md) says more.

## Turn members' own MCP servers off

In **Settings**, **General**, under **Members**, switch off **Their own MCP servers** and confirm with **Turn off**. The servers members registered stay, but no conversation uses them, and nobody can register a new one until you turn it on again. The switch shows on **Kill switches** as **Members' own MCP servers are off**. [MCP servers](mcp-servers.md) says more about personal servers.

## What members see

- While the company's runs are stopped, every company page shows a banner, **The company's runs are stopped**, with who stopped them and why. Owners and Admins also get **Open the kill switches**.
- A suspended member sees **You are suspended in this company** with who suspended them, when and why, and can open only the company's **Kill switches** page, which shows **You are suspended**.
- A member who opens **Kill switches** sees whether the company's runs are stopped and their own suspension, and nothing else.
- A paused system, agent or trigger is marked paused where members see it, and their turns that wait on it say why.
