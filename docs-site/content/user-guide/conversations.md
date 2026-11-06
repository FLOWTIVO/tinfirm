# Talk to an agent

A conversation is one agent at work for you, in a thread in Tinfirm. This page is for every member: how to start one, follow the agent while it works, steer it, give it files, answer what it asks, and stop, retry or end it. [Concepts](../concepts.md#conversations-and-runs) explains the words.

## Start a conversation

1. Open **Conversations** and choose **New conversation**, or choose **Talk to** on an agent's page or in the **Agents** list.
2. In **To**, pick the agent. The list holds the agents you may use: your own and those shared with you or your groups. When only one is shared with you, it is already picked.
3. Write your first message (below) and choose **Start**, or press ++ctrl+enter++ (++cmd+enter++ on a Mac).

The conversation opens as soon as it starts. If no agent is shared with you yet, the page says **No agent is shared with you yet** and offers **Create an agent**: see [Make and share an agent](agents.md).

### Before you start: who acts and who pays

Once you pick an agent, the page shows **Who acts and who pays** under your message, before anything goes. An agent holds no credentials: it acts as you.

| Part | What it says |
|---|---|
| **Acts as** | One line per system the agent uses, with the account a conversation would use there: *your own account*, *via Analysts, read-only* (a credential lent to your group), *you choose among your groups' grants when it is first needed*, *connect when first needed*, *no account needed*, or what holds it up, such as *your account needs signing in again* or *waits until an admin signs the lent credential in again* |
| **Pays** | Your budget and what is left of it, for example *Your budget: $38.20 of $50 left, resets 1 Nov*, or a subscription of yours on your own runner host, as your **My settings** for the agent choose |
| **Runs on** | *A company host*, *Your personal host only*, or *No runner host can run it now* with the reason |

A pill beside the agent's name sums it up:

| Pill | Meaning |
|---|---|
| **Ready to start** | The first turn can run at once |
| **First turn waits** | You can start, and the first turn waits: until your budget resets, until the runner host of your subscription is online, until a runner host can take it, or until a paused agent is resumed. The page says which |
| **Can't start yet** | Nobody can pay for the conversation as your settings stand, and **Start** is off. The page says why: the company's model gateway is not set up, your model access is switched off, the subscription your settings pay with is gone, your settings name no model or one your budget may not use, or no runner host may ever run it. Most of these are fixed in [My settings](my-settings.md); the others need an Admin |

When a new version of the agent may do more than the one you accepted, a card lists what it adds and offers to accept it. Until you do, your conversations use the version you accepted. [Make and share an agent](agents.md) says more.

### Connect now, or when the agent first needs it

When the agent uses a system on which you have no account yet, or yours needs signing in again, the page offers **Connect now, or when the agent first needs it**, with **Sign in with {system}** (where the system offers it) and **Paste a token**. It is optional: a conversation that needs the system asks you then, with a Connect card in the thread. [Connect your accounts](accounts.md) explains both ways.

### About an issue

Choose **About an issue** to say what the conversation is about: an issue or a change request on one of the company's Jira, GitHub or GitLab systems. Pick the **System**, give the **Key** people know it by (`SHOP-241`, `acme/shop#57`), and optionally its **Link** (`https://…`) and **Title**. The agent gets it as a reference and reads the issue with its own tools; Tinfirm copies nothing. Conversations about the same subject are grouped (see [Conversations about an issue](#conversations-about-an-issue)). Choosing **About an issue** writes nothing to the issue.

### Repositories

When the agent works with code, its repositories show as chips under your message, each with its access (*read-only*, *read and write*). They are the workspace of the conversation's turns. The chips start from the default repositories of your **My settings**, else all of the agent's. Untick those the work does not need; at most 20 may stay ticked. With none ticked, the agent asks you if it needs one. A repository of a system your settings switch off is not offered.

## Write the first message

Your message is Markdown, up to 100,000 characters. Its first line that is not blank becomes the conversation's title, without a heading's `#`: the page shows the title as you type. You can rename the conversation later with the pencil beside its title (**Rename** in the menu on a phone), up to 200 characters.

**Start** sends the message once. If the network fails, choose **Start** again: the conversation is still started only once.

While an Admin has stopped the company's runs, no conversation starts; messages you write in open conversations are kept, and their turns wait. See [Kill switches](../admin-guide/kill-switches.md).

The first message takes no files. Add them once the conversation is open ([below](#add-files)). An AI client, the Outlook add-in and the Gmail add-on can hand files to a new conversation from its start.

## Follow a running turn

Each time the agent works, it runs a **turn** on a runner host, in a sandbox of its own. The turn running now sits at the end of the thread:

- While it waits for a runner host, a line says why, with a link to what fixes it: **Your budget**, **Connect the account**, **Open the agent**, **Your runner hosts**.
- While it works, the line reads **Working**, then how long, the phase and its latest step, such as *Read src/app.ts*. What the agent says so far shows under it.
- **Show work** unfolds every step in place: tool calls, thoughts, the plan, git. **View run** opens the run's own page.

A finished turn stays in the thread as the agent speaking: what it said, **Show work** (*Worked for 6m 12s · 14 tool calls · 2 messages*), and a status line with the outcome, how long it took, what it pushed (*Pushed 2 commits to* its branch, or *No changes*), the change requests it opened, its commit and its cost. A cost marked `~` is the runtime's estimate until the gateway's record settles, a few minutes after the turn; *subscription* means it cost nothing. The turn's name line says why it ran: *first message*, *a new message*, *after a decision*, *after an account was connected*, *run again*.

| Outcome | Meaning |
|---|---|
| **Completed** | The agent finished its work |
| **Blocked** | The agent finished, but could not do what was asked; it says why |
| **Failed** | The turn failed; the line under the thread says why, with **Retry** |
| **Stopped at its time limit** | See [The time limit of a turn](#the-time-limit-of-a-turn) |
| **Lost: the runner stopped reporting** | The runner host stopped reporting the turn |
| **Cancelled**, **Cancelled before it started** | Someone cancelled it |

### The conversation's page

Above the thread, the header shows the title, the state, the agent, the conversation's key and branch, the subject, and the badge **AI client** when you started it from an AI client. The state reads as in [the list](#the-conversations-list), or **New** before the first turn, **Idle** when nothing waits and you write next, **Cancelled**, **Agent paused** or **Ended**. One line says who acts and who pays; **Details** opens it in full. Beside the thread (behind **Details** on a smaller screen) the conversation lists:

- **Outputs** and **Files** (below);
- **Workspace**: each repository, read-only and why, and *added on request* for one an approval added;
- **Network hosts** the next turn may reach, and those granted in this conversation;
- **Cost**: what the conversation cost so far, with its tokens;
- **Turns**: each turn, its time and status, linking to its run.

A conversation that lives in a chat, such as a direct message to the Tinfirm bot, takes its messages there: in Tinfirm, the composer says where to write, and its cards stay answerable. See [Talk to agents in Slack and Microsoft Teams](chats.md).

### The run page

**View run** opens a turn on its own page: the agent and the turn, who it worked for and who paid (**For whom, paid by whom**), the runner host, whether it resumed its earlier session, its tokens and branch, each repository with what git did, the full transcript as it happens, and the agent's **Result** with its **Next steps** and **Open questions**. While the turn is queued or running, **Cancel run** stops it.

## Write while a turn runs

You don't have to wait for a turn to end. A message you send while the agent works goes to the running turn: the agent takes it after its current step, before it calls the model again. The composer says so: *… is at work: it takes your message after its current step, or in the next turn.*

In the thread, your message first reads **Sending to {agent}…**, then **{agent} took it while working**, under that turn. Steering works with the Claude Code, Codex and pi runtimes, on a runner host recent enough to take it. When the runtime or the host cannot take it (OpenCode cannot), or the turn ends first, the message reads **Waits for the next turn**, and the next turn takes it.

A message that answers the agent's open questions always waits for the next turn.

## Add files

Choose **Add a file** under **Files**, or the attach button in the composer. Every later turn reads the conversation's files, read-only, under `/attachments`. Only the conversation's person adds and deletes files, and only while it is open.

| Limit | By default |
|---|---|
| One file | 25 MB |
| Files in a conversation | 50 |
| All files of a conversation | 250 MB |

The operator may set other limits. Two files of a conversation cannot have the same name.

Tinfirm scans every file for credentials before it stores it, and tells you what it found, never the value:

| The scan finds | What happens |
|---|---|
| The value of one of the company's secrets | The file is refused. Remove the secret from it, or ask an Admin to rotate the secret |
| Something that looks like a credential, such as an API key format | The file is refused. If you are an Owner or Admin, you may store it anyway: tick *I checked the file…*, then **Add it anyway** |
| Content it cannot scan completely | The file is refused |

**Preview** shows a file in place, **Download** saves it, and the bin deletes it after you confirm: later turns no longer see it. A document the agent saved as an output stays while its output does. A file the company's retention removed keeps its name, size and date, and says so.

## What a turn produces

**Outputs** lists what the conversation produced, each linked to the turn that made it:

- **Change requests.** After a turn that pushed to a repository, Tinfirm opens a pull request or merge request: at once, or after your approval, as the agent's tools say. Each shows its state at the code host: **Open**, **Draft**, **Merged**, **Closed**.
- **Documents.** Files the agent writes to its output folder, or saves with its `save_artifact` tool, become documents of the conversation. They are scanned like your files, and listed under **Files** too, *saved by* the agent. Preview or download them.
- **Links.** A link the agent saves with `save_artifact`, such as a page it created in another system.

A personal conversation publishes nothing by itself: about an issue, it writes there only through the agent's tools, each under its approval rules.

## Questions and approvals

When the agent needs you, it stops and asks, in the thread and in your [inbox](inbox.md):

- A **question**, often with options. Pick one or write an answer, and **Send answer**. You can also answer by writing in the composer with **This answers the open question** ticked. The agent goes on once no question is open.
- An **approval**: a tool call that asks first, a repository or a network host it wants, or a change request to open. **Approve** or **Deny**, with a note if you like.

While such a card is out of view, it is pinned above the composer, so you can decide it from wherever you read. [Your inbox](inbox.md) describes each kind and what your decision does.

## Accounts for the conversation's life

A conversation uses one account per system for its whole life. At the first use of a system, it takes your own account when it works; else a credential lent to one of your groups, which it names (*GitHub: via Analysts, read-only*); with several, you choose. Tinfirm checks the account at every use and never switches to another by itself: connecting your own account later changes new conversations, not this one.

When an account is missing or broken, the agent's call waits and a **Connect** card appears in the thread:

| Card | What you do |
|---|---|
| **Connect {system} to continue** | **Sign in with {system}** or **Paste a token**; the call goes on |
| **Sign in to {system} again** | Your account stopped working: sign in again or paste a new token |
| **Act on {system} through:** | Pick the grant to act through, or **Connect my own instead** |
| **Your {system} account needs signing in again** | **Sign in again**, or use the grant of your group |
| **{system} waits for an admin** | Nothing: an Admin must fix the lent credential, and was told |
| **{system} is switched off** | Personal MCP servers are switched off in the company |
| **{system} can no longer be used here** | The grant was withdrawn, you left its group, or the account now signs in as someone else. Start a new conversation |

[Connect your accounts](accounts.md) explains accounts and grants.

## Cancel, Retry and Continue

The menu beside the title holds **Cancel turn**, **Retry** and **End conversation**, as they apply.

- **Cancel turn** stops the turn that is queued or running, and cancels its open questions and approvals. The conversation stays open. Messages that waited for the turn stay unanswered, and the thread says so.
- **Retry** runs a failed or cancelled turn's request again, resuming the agent's session. It is also on the line under the thread and in your inbox. When a turn failed because of your settings (no model, a model no longer allowed), the line links to **My settings**; fix them first.
- When an agent no longer runs the conversation's runtime, the turn cannot run again: the line offers **Start a new conversation with {agent}**.

### Automatic retries

Some failures pass by themselves: the model's provider or gateway is overloaded or unavailable, or the runner host restarted. Turn on **Automatic retries** in your [My settings](my-settings.md) for the agent, and such a turn is retried up to three times in a row, 1, 2 and 4 minutes after each failure, paid as the turn was. Meanwhile the conversation shows **Waiting · Retrying soon**, and the thread says when it retries. Cancel the turn to stop the retries.

### The time limit of a turn

A turn may run for a limited time: 60 minutes unless the operator or the agent sets another, from 5 minutes to 24 hours. At the limit, the runner host stops the turn and keeps its work. The conversation shows **Stopped · Time limit**, and **Continue** goes on from where it stopped. You can also write what to do next instead.

## End a conversation

Choose **End conversation** in the menu and confirm. An ended conversation takes nothing more: no message, no turn. A turn that runs finishes and its result is kept; open questions and approvals are cancelled. Its outputs and files stay, and you can read it. Ending cannot be undone.

A conversation also ends by itself when its agent is deleted, when you leave the company, or when a system it lives in goes; the list says why.

## The Conversations list

**Conversations** lists your conversations. What needs you comes first, under **Needs you**; then **Other conversations**, newest activity first. Each row shows the agent, the subject, the trigger that started it, the number of turns, the settled cost, the tokens and the conversation's key, with a badge of what it waits on:

| Badge | Meaning |
|---|---|
| **Needs you · Question**, **· Approval**, **· Review**, **· Account** | You must answer, decide or connect |
| **Working** | The agent works now |
| **Waiting · …** | A turn waits to run, and why: *Queued*, *Budget spent*, *Account needed*, *Agent paused*, *Host offline*, *Host busy*, *No runner host*, *Nobody can pay*, *Retrying soon* |
| **Turn failed**, **Stopped · Time limit** | The last turn failed or stopped; open it to retry or continue |
| **Ended: …** | Ended, and why |

Narrow the list with **Show** (**All**, **Open**, **Needs a person**, **Working**, **Waiting for a runner**, **Ended**), by **Agent**, and by **Trigger** when triggers' conversations are among them (**Any, or none** shows them all). The filters stay in the page's address, so you can bookmark a view.

The conversations of a trigger you wrote, or whose team you are in, are listed here too. On a trigger's page, the link *{n} open conversations* beside **Firings** opens this list filtered by that trigger. [Write a trigger](triggers.md) explains triggers' conversations.

## Conversations about an issue

Switch the list to **By subject** to see every issue and change request your conversations are about, with how many there are and how many are open. **Show conversations** lists them. Conversations are independent: several can work on one issue at once, each with its own branch and workspace.

## Who can read your conversation

Your conversations are yours. Other members, Admins included, do not see them. The company's Owners can read any conversation in full, for support and audit. Each read is recorded in the audit log, and you are shown it.

On your conversation, beside the line of who acts and who pays, an eye button says **The Owner can read this**, followed by *nobody has*, *read once*, *read 3 times* or *2 people read it*. It opens **Who else read it**: *The company's Owner can read this conversation in full. Each read is recorded in the audit log and listed here.* Then, for each Owner who read it, what they read and how often, such as *Olga Owner read the conversation, its transcripts and a file's content, 3 times*, with when they last and first read it. *Nobody but you has read it* until someone does. Reading the list yourself is not a read. On a phone the list is under **Details**.

An Owner who opens your conversation sees *{you}'s conversation. Only {you} writes, decides and connects accounts here. Your read is recorded.* An Owner may cancel, retry or end it, but never writes in it, answers or approves for you. On **Conversations**, an Owner can switch to **Everyone's**, under a banner that says each conversation opened is recorded. [The audit log](../admin-guide/audit-log.md#when-an-owner-reads-a-personal-conversation) describes the Owner's side.

A conversation you start from an AI client, a chat direct message or an add-in is personal in the same way.
