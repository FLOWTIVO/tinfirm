# Your inbox: questions, approvals and accounts to connect

When an agent needs a person, it does not guess: it asks, and the request lands in the inbox of whoever must answer. This page is for every member: what your **Inbox** holds, how to answer and decide each kind of item, and what your decision does. [Concepts](../concepts.md#inbox-and-decisions) introduces decisions.

## How the inbox is arranged

Tinfirm opens on your **Inbox**. It holds three groups, each in its own section:

| Group | What it holds |
|---|---|
| **Needs you** | Questions, approvals and reviews you decide, and accounts to connect. Longest waiting first, in orange |
| **Stopped** | Your turns that failed, stopped at their time limit, or wait to run. Nothing here needs a decision |
| **Notices** | For your information: budgets, triggers, chat links and, for Owners and Admins, the company's notices |

The pills above them, **All**, **Needs you**, **Stopped** and **Notices**, show all groups or one alone, with their counts. The **Inbox** item in the sidebar shows how many questions, approvals and accounts wait for you. The inbox reads itself again every 15 seconds while it is open. It lists at most 100 items of each kind, longest waiting first; the rest follow as you handle them.

Each item says how long it has waited and names its conversation; **Open** goes there. What you decide in the inbox is the same as deciding it in the conversation, where the same card shows. Once you act on an item, its row folds into a line of what you did, such as **Answered** or **Approved**, and what happens next.

## Answer a question

A question shows what the agent asks, often with options. In the row:

- With up to four short options, choose one to answer in one click.
- **Reply…** opens the whole card: pick an option, write an answer or add to the option, then **Send answer**.

Your answer is posted in the conversation as your message, and the agent goes on once no question of it is open. The next turn gets every answer. You can also answer from the conversation's composer, with **This answers the open question** ticked.

In a trigger's conversation, the trigger's author and team answer in Tinfirm. Where the trigger lives on an issue or in a chat thread, anyone who can write there may answer too, and an answer you write in the conversation's composer in Tinfirm is posted there as well.

## Decide an approval

An approval asks permission for one action. **Approve** and **Deny** are in the row; the whole card adds a **Note for {agent}**, which the agent gets with your decision.

| The agent wants | The card shows | What your decision does |
|---|---|---|
| To call a tool that asks first, such as *comment on SHOP-241* | **What**, the **System**, the account it **Runs as**, and the arguments behind **Show the arguments** | Approved, the call runs now with that account and the agent gets its result. Denied, the agent is told and goes on without it |
| A **repository** it was not given | The repository, and **Access**: **Read only** or **Read and write** | It joins this conversation's workspace from the next turn, with the access you chose. Denied, the agent is told |
| To reach a **network host** | The host and the agent's reason | Its next turns may reach the host. A conversation holds at most 100 granted hosts |
| To open a **change request** on a repository | The repository | Approved, Tinfirm opens the change request. Denied, the agent is told and goes on |

When the agent gave a reason, the card shows it under *Why: {agent} says*, in the agent's own words.

### For this conversation, or for the agent for good

A repository or a network host can be allowed in two ways:

- **Allow for this conversation**: only this conversation's next turns have it.
- **Allow for {agent}, for good**: it is added to the agent itself, and every conversation with the agent has it. Only someone who may edit the agent sees this button: its owner, and the company's Owners and Admins.

In the row, **Allow for this conversation** and **Deny** decide at once; **More…** opens the card with the access and **for good**.

## Review a trigger's result

A trigger can ask for a review before its result is written back to an issue. The card says *{agent}'s result waits for a review before anything is written*, and shows the agent's result and exactly what would be written to the issue: the comment, then each action in words.

- **Approve and write it**: exactly what is shown is written to the issue.
- To send it back, write what should change in the note, then **Send back** (in the conversation, **Return with the note**). The agent gets your note and works on it again. A note is required.

Nothing reaches the issue before someone approves it. **Review…** opens the card from the row.

## Who decides

| Conversation | Who decides its approvals and reviews |
|---|---|
| Your own conversation | You |
| A trigger's conversation | The trigger's team, or its author when it has no team |

A team's approval reads *For the team {group}: whoever decides first decides for everyone.* When someone decided first, or the request was cancelled, the card says so and shows the outcome. A team's approvals are decided in the web app only, never through an AI client.

An approval you may not decide reads *Waiting for {who}*. Questions and approvals never go to a Viewer: when the only people who could answer are Viewers, the request goes to the company's Owners and Admins.

Approvals wait while an Admin has stopped the company's runs, or while the agent is paused; answering a question is never refused.

## Connect an account

When a conversation of yours needs an account on a system, the inbox asks for it. The row is one of:

| Row | What to do |
|---|---|
| **Connect {system}** | The agent asked to use the system and you have no account there. **Sign in with {system}**, where the system offers it, or **Paste a token** |
| **Sign in to {system}** | Your account there no longer works: sign in again or paste a new token. When a group of yours lends a credential on it, **Choose…** offers that instead |
| **Pick a grant** | Several of your groups lend you a credential on the system. **Choose…** shows them: pick the one this conversation acts through |

Once connected, the agent's call goes on in the conversation, and the row reads **Connected**. A sign-in takes you to the system and back to the inbox. [Connect your accounts](accounts.md) explains tokens, sign-ins and grants.

## Turns that stopped

| Row | What it means | What you can do |
|---|---|---|
| **Failed** | A turn failed, with the cause, such as *Usage limit reached* or *Provider overloaded* | **Retry** runs it again |
| **Time limit** | A turn stopped at its time limit; its work is kept | **Continue** goes on from where it stopped |
| **Waiting** | A turn waits to run, and why | Follow the link, or wait |

The usual reasons a turn waits:

| Reason | It goes on when |
|---|---|
| *Your budget is spent.* | Your budget resets, or an Admin raises it. The row says when it resets. See [Your budget and spend](spend.md) |
| *An account of its workspace waits to be connected.* | You connect it |
| *Its agent is paused; its turns wait until it is resumed.* | The agent's owner or an Admin resumes it |
| *An admin stopped the company's runs; it goes on when they are resumed.* | An Admin lifts the stop |
| *The runner host of its subscription is offline.* | Your runner host is online again |
| *Every slot of the runner host is in use.*, *Its runner host is restarting.* | A slot frees up |
| *No runner host can run it.* | An Admin connects a runner host that can |
| *Its turn failed for a reason that may pass; it is retried by itself soon.* | The automatic retry runs |

## Notices

Notices inform. The ones every member may get:

| Notice | Says |
|---|---|
| **Budget** | *80 % of your budget used*, with what is spent, when it resets and how many turns wait. Admins set the thresholds, 80 % and 100 % at first. **Your budget** opens it |
| **Trigger** | A trigger you wrote, or whose team you are in, is paused or waits, could not write a result back, skipped firings, was changed by an admin, has a new version of its agent to accept (**Accept version N**), or has **Memory notes to review** (**Review notes**). **Open trigger** goes to it |
| A chat system's name, such as **Slack** | *A Slack account was linked to you*, and *Your account was linked to Slack user … Not you? Unlink it.* If you did not link it yourself, **Unlink** it at once. See [Talk to agents in Slack and Microsoft Teams](chats.md) |

Owners and Admins get more items: see [If you are an Owner or Admin](#if-you-are-an-owner-or-admin).

### Dismiss a notice

A notice that only informs you ends with **Dismiss**. It leaves your inbox at once, with **Undo** in its place, and stays away until what it says changes, such as a budget crossing its next threshold. Dismissing is for you alone: nobody else's inbox changes. A notice that asks you to act has no **Dismiss**: it leaves once that is done.

## If you are an Owner or Admin

Owners and Admins also get items about the company itself. Those that keep conversations waiting are in **Needs you**; the others are notices.

| Item | What to do |
|---|---|
| *The credential {name} of {system} needs signing in again* | **Sign in again** in the row, or open the system |
| *The credential {name} of {system} needs a new token* | **Store a new token** opens the credential on the system's page |
| *The credential {name} of {system} is disabled* | **Enable it** in the row, and the conversations waiting on it go on |
| *A credential of {system} needs signing in again* | Sign it in again, or store a new token, on the system's page |
| *The bot of {system} no longer works* | Replace its token under **Bot** on the system's page (**Open its bot**) |
| *{service account} is suspended* | The credentials it borrows act for nothing through it until you resume it (**Open {service account}**) |
| *{system} stopped sending events* | Slack disabled the app's event subscriptions; re-enable them at Slack |
| *The daily check of the audit log failed* | Look into it; an Owner acknowledges the failure (**Open the audit log**) |
| *100 % of {name}'s budget used*, *The company's spend crossed its alert amount*, *Spend is counted by Tinfirm, not read from the gateway* | See **Model access** |
| *{name} left the company* | Their checklist is not closed: hand over their triggers and agents, and check their accounts, under **Members** |
| *{name} joined as a Viewer: every seat was taken* | Free a seat or add seats, then change their role under **Members** |
| *The licence ends on …* (marked **Licence**) | Ask for the renewal's key; an instance admin enters it |
| *{n} open conversations older than the retention period* (Owners) | End the conversations nobody needs: retention removes texts only once a conversation has ended |

Items about credentials and chat bots name how many conversations they keep waiting, never which. [Register systems and lend their credentials](../admin-guide/systems.md) and [Set up Slack and Microsoft Teams](../admin-guide/chat-systems.md) explain how to fix them; the rest of the [Admin guide](../admin-guide/index.md) covers the others.

## From your AI client

An AI client connected to Tinfirm can read your inbox (`get_inbox`) and, at the **Read, write and decide** level, answer your questions and decide your own approvals (`decide`). A team's approvals and a trigger's memory notes are decided in the web app only. [Work with Tinfirm from your AI client](ai-clients.md) explains the levels.

## If you are a Viewer

A Viewer's inbox says *You are a Viewer in this company*: Viewers see what waits, but never answer questions or decide approvals, and no request is sent to them. An Owner or Admin can make you a Member. [Roles](../admin-guide/roles.md#viewers) says what a Viewer may do.
