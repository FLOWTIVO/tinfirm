# Talk to agents in Slack and Microsoft Teams

When your company has connected Slack or Microsoft Teams to Tinfirm, you can work with agents without opening Tinfirm: write to the Tinfirm bot in a direct message, or mention it in a channel. This page is for every member who does. To connect a workspace or a tenant, an Owner or Admin reads [Set up Slack and Microsoft Teams](../admin-guide/chat-systems.md).

## Two ways to ask

| | A direct message to the bot | A mention of the bot in a channel |
|---|---|---|
| Who can ask | You, once your chat account is [linked](#link-your-chat-account) to you in Tinfirm | Any member of the workspace who can write in the channel, linked or not |
| What it starts | Your own conversation with an agent you use | A conversation of the channel's [trigger](triggers.md) |
| The agent acts with | Your accounts, your budget and your **My settings** | The accounts and the budget the trigger's author chose |
| Where it answers | In your message's thread in Slack; in your personal chat with the bot in Teams | In the message's thread |
| Who sees it in Tinfirm | You, and the company's Owners, each read recorded and shown to you | The trigger's author and team, and the company's Owners and Admins; not the people who asked |

The bot only carries messages. It never acts in a system: the agent acts as you in a direct message, or with the trigger's accounts in a channel.

## Link your chat account

A direct message, and some of the bot's buttons, need to know which member is writing. Tinfirm knows it once your chat user is linked to you. A link joins one chat user to one member on one Slack workspace or Teams tenant, by the chat's own id for that user, never by an email address or a display name. You have at most one chat user per workspace, and a chat user belongs to one member.

### In Microsoft Teams

If you sign in to Tinfirm with your organisation's Microsoft account, Tinfirm usually links you by itself at your first message to the bot: it does when the Microsoft account you last signed in to Tinfirm with is the one writing in Teams. Otherwise the bot sends you a sign-in link, as in Slack.

### In Slack, and whenever Teams cannot link you by itself

1. Send the Tinfirm bot a direct message. In Slack, find **Tinfirm** under **Apps**.
2. The bot answers with **Link my account**. The link is for you only, works once and for 10 minutes. The bot sends a new one at most every 10 minutes.
3. Choose **Link my account** and sign in to Tinfirm as you always do.
4. The page asks **Link the Slack user … to your account?** It names the chat user by what nobody can choose (its id, the workspace, and when the link was asked for), shows who Slack says the user is right now (the real name and picture; on Teams the Microsoft name), and warns that a display name proves nothing. Choose **Link to my account** only if you just wrote to the bot yourself. **It wasn't me** links nothing.

Your messages wait while you link, up to 5 of them, but they are not answered once you link: whoever confirms a link may not be whoever wrote them, so they are dropped, and the bot asks you to send them again. When the link expires first, they are dropped too and the bot tells you so.

Both sides are told. The bot writes **Linked to** your name **in Tinfirm**, with a note that these conversations are personal and that the company's Owner can read them. Your inbox keeps a notice, **Your account was linked to…**, for 7 days, with **Unlink**. If you did not link it yourself, unlink it at once.

| The link page says | What to do |
|---|---|
| **This link has expired** | Send the bot a direct message again for a new one |
| **This link was already used** | The same, if your chat account is not linked yet |
| **This chat user is someone else's** | Another member is linked to it. Nothing was linked |
| **You are linked to another chat user** | Unlink the other one under **Accounts**, then ask the bot for a new link |
| **This link is not for this account** | Sign in as yourself, in the company the link is for |
| **This link is incomplete** | Open it again from the bot's direct message, in the same browser tab you sign in with |

Confirming uses the link up, whatever the outcome.

### See and remove your links

On **Accounts**, the **Systems** tab has a **Chats** section with each Slack and Teams system of the company. Each row says **Not linked**, **Linked**, **Linked, not working**, **Signed in** (Slack, after [Sign in to Slack](#let-agents-read-slack-channels-as-you)) or **Sign in again**, with when you linked and your last message.

**Unlink** removes the link. The bot refuses your direct messages until you link again; your direct-message conversations stay, readable in Tinfirm. An Owner or Admin can also remove your link, for a chat account that is no longer yours or was taken over. When you leave the company, your links go with your other accounts.

## Write to the bot directly

A direct message to the bot is a personal conversation, exactly like one you start in Tinfirm: the agents you use, your accounts, your budget and your **My settings**. The first time, the bot says so: **This is a personal conversation: … acts with your own accounts and budget. The Owner of … can read it for support and audit.**

### Which agent answers

When a message starts a conversation, Tinfirm picks the agent in this order:

1. **A handle at the start.** `@dev why does the build fail?` goes to the agent whose handle is `dev`. The handle is taken off the message.
2. **Your default.** Under **Accounts**, **Systems**, **Chats**, once you are linked, **Your direct messages go to** names one of the agents you use, or **Ask me each time**.
3. **The bot asks.** **Which agent should answer this?** with a button for each agent you use (at most 10), and **Always @…**, which also makes that agent your default. Your message waits an hour for your choice; only you can choose. At most 5 messages wait at once.

If the handle names no agent, an agent you may not use, or a paused one, the bot says so: **No agent is called @dev.**, **You may not use @dev.** or **… is paused.** If you use no agent at all: **You use no agent in Tinfirm yet: ask an admin to share one with you.**

### Conversations and threads

| | Slack | Teams |
|---|---|---|
| A conversation starts | With every new message in your direct message with the bot; the agent answers in that message's thread | With your first message: your personal chat holds one open conversation at a time |
| It continues | With a reply in that thread | With your next message in the chat |
| Another one starts | With a new message outside the thread | After **New conversation** under an answer, or with a message that starts with another agent's handle; either ends the open one |
| It ends | **End** under an answer, or in Tinfirm | The same |

While the agent has open questions, your next message answers all of them. Otherwise it is the request for the next turn, as in Tinfirm. A message to an ended conversation gets **This conversation ended; send a new message to start one.** A message, or a press of **End**, in a conversation of another member gets **This conversation is another member's.**

### What the bot posts

- When a turn starts, the bot shows that it took your message: an eyes reaction in Slack, the typing indicator in Teams.
- Each answer carries the agent's name followed by **(Tinfirm agent)**, so nobody takes it for a person. It lists the turn's outputs, and **End** under it (and **New conversation** in Teams).
- A long answer is split into at most 4 messages, marked (1/3) and so on, at blank lines where it can. A part may end inside a code block: the block is closed at the end of that part and opened again in the next, so it stays a code block in both. A longer answer posts its first part, then the whole answer as `answer.md` in Slack, or a link to it in Tinfirm in Teams.
- When the agent needs a system you have not connected: **… needs …: connect it in Tinfirm to go on.** with **Connect in Tinfirm**, which opens the conversation in Tinfirm. Once you connect, the card reads **Connected, continuing.**
- When a turn fails: **… could not finish this (…). Open it in Tinfirm to retry it.**
- The bot's posts never notify a channel or a person, and Slack shows no preview of their links.

### Files

In Slack, the files you add to a direct message are added to the conversation, marked **From Slack**, within the usual limits of a conversation's files and after the same scan for credentials as an upload. A file left out is named in the thread with why. Each document an agent produces is uploaded into the thread.

Teams files are not read yet: the bot answers with a link to add the file to the conversation in Tinfirm, and an agent's document is a link to it in Tinfirm.

### Follow it in Tinfirm

Your direct-message conversations are among your **Conversations**, with a badge such as **Direct message in Slack · Acme Slack** and **Open in Slack** to go back to the thread. In Tinfirm you can answer its questions and approvals, and connect accounts; new messages go in the chat, which stays the conversation's one place. An answer you give in Tinfirm is posted in the thread as **… answered in Tinfirm:**.

## Ask an agent in a channel

A channel answers mentions of the bot when it has a **channel trigger**: a trigger whose source is **Someone mentions it in a channel**. Any member can write one for an agent they use, in channels they are a member of: [Write a trigger](triggers.md) says how.

1. Mention the bot, and name the agent if the channel has several: `@Tinfirm @support why is checkout slow?`. Without a handle, the channel's default trigger answers: `@Tinfirm why is checkout slow?`.
2. The agent answers in your message's thread. The thread is one conversation, titled in Tinfirm after the trigger and the first line of your message: mention the bot in the thread to answer its questions or to ask the next thing.
3. The thread stays with its agent. Another agent's handle there gets **This thread is @support's; start a new message to ask @dev.** Once its conversation has ended, the thread takes nothing more: **This conversation ended; mention me in a new message.**

Only messages that mention the bot reach Tinfirm. A reply in the thread that does not mention it is never read.

The agent acts with the accounts and the budget the trigger's author chose, never with yours, even when you are linked. Everyone in the channel reads the answers. In Tinfirm, the conversation belongs to the trigger: you see it only if you are its author, in its team, or an Owner or Admin.

When no trigger answers, the bot gives a hint: **No agent @dev answers here; ask @support.**, **Name the agent to ask; ask @support or @analyst.** or **No agent answers in this channel.** A paused trigger or agent answers **… is paused here** or **… is paused**, a trigger that waits (for example for a working bot) **… cannot answer now**, and a trigger that has fired too often this hour **Too many requests to … this hour**. Nothing you sent then is kept or answered later.

## Answer questions and approvals in the chat

When the agent needs you, the bot posts a card in the thread.

| | In a direct message | In a channel thread |
|---|---|---|
| A question | Up to 5 options as buttons; more are listed under **Also:** | The same |
| Who answers it | You: a button, or your next message, which answers every open question; or in Tinfirm | Any member of the workspace: a button, or a mention in the thread with the answer; or the trigger's author or team in Tinfirm |
| An approval | The action in words, with **Approve** and **Deny** | The same card |
| Who decides it | You | A linked member of the trigger's team, or its author when it has no team |
| A review of the result | | In Tinfirm only; the thread reads **…'s answer waits for its team's review in Tinfirm.** |

Only a button decides an approval, and only on the card the bot posted for it. Typed words never approve anything. When someone who may not decide presses a button, they alone are told who decides (**Only members of … decide this**), and the approval stays open. The card then shows who decided and where, for example **Approved by Alice in Slack: …**.

A button approves for this conversation only. Choices that need more, such as allowing a repository for the agent for good, or adding a note to a denial, are made in Tinfirm: [Your inbox](inbox.md).

## Let agents read Slack channels as you

A Slack system offers agents one tool, `history`: it reads a channel's latest messages, or a thread's. It never reads with the bot. In your own conversations it reads with your own Slack account, so it sees only what you may see in Slack.

To give agents that account, choose **Sign in to Slack** in the **Chats** section of **Accounts**. Your company's Owners and Admins must have set up Slack's sign-in app first; without it, the button is not there. Signing in also links your chat user, so you need no sign-in link. A sign-in as another Slack user than the one you are linked to is refused.

The agent must also have the Slack system and its `history` tool allowed, like any other tool: [Make and share an agent](agents.md). In a channel trigger's conversation, `history` reads only the thread the agent was asked in, since anyone in the channel can ask.

## What the bot never sees

- Channel messages that do not mention it, and replies in a thread that do not mention it.
- Group direct messages in Slack and group chats in Teams: the bot is not installed there.
- Messages of other bots and apps, and edits and deletions of messages: these reach it, but it ignores them.
- Files sent in a channel mention.

## Who the bot does not serve

| Who | What happens |
|---|---|
| Guests of the workspace or tenant, users of another organisation (Slack Connect, another Teams tenant), anonymous Teams users | **Tinfirm serves members of this workspace only.**, told once an hour. They never get a sign-in link |
| A member who is suspended in the company | **Your Tinfirm account is suspended in ….**, told once an hour |
| A Viewer | Can link their chat account, but each direct message then gets **You are a Viewer in this company: you read what it shares with you in Tinfirm, and start or continue no conversation with its agents. An admin can change your role.** An Owner or Admin can change the role: [Roles](../admin-guide/roles.md#viewers) |
| Anyone, while an admin has stopped every run of the company | A message that would start something gets **Tinfirm is stopped in … by an admin; nothing was started.** A message to an open conversation is taken and waits until the stop is lifted. Either is told at most once an hour. **Approve** is refused until then; answers and denials are taken |
| Anyone who writes too much | **You sent more messages than this workspace allows for now. Try again later.** |

When the bot answers nothing at all, the system may have no working bot, or it may be paused: ask your company's Owners or Admins. If Tinfirm failed to handle a message, the bot says **Something went wrong while Tinfirm took this. Please send it again.**
