# Write a trigger

A trigger is a standing rule: when something happens on a system, or on a schedule, an agent does an instruction with the accounts and the budget its author chose. This page is for every member who writes triggers, and for the teams that look after them.

A trigger reads as one sentence, which the app shows on its page: *when any of these events happens to an issue in this scope, and these conditions hold, this agent does this instruction.* When it fires, it starts a conversation with its agent, or continues the one it has open on that issue, thread or subject.

## How a trigger works

| Part | What it is |
|---|---|
| Its source | Where it listens: a tracker's issues, a schedule, a chat's channels, or a webhook's deliveries. Fixed once the trigger exists. |
| Its rule | The events that fire it, and what must hold: the scope, conditions, once or every time. |
| Its agent and instruction | The agent is fixed: a trigger for another agent is another trigger. The instruction is the job, in every turn. |
| Its accounts | One account per system it needs: your own, or a service account's with the credential lent to it. |
| Its payer | Your budget, or a service account's. |
| Its settings | Its narrowing of the agent: tools off or asking, the model, memory. |
| Its team | A group that sees its conversations, decides their approvals, runs it now and pauses it. Without one, you alone. |
| Its results | What reaches the place: comments and actions on an issue, answers in a chat thread. A schedule's and a webhook's results stay in Tinfirm. |

The four sources, as **It starts when** offers them:

| It starts when | System | Fires on | Its conversation lives |
|---|---|---|---|
| **Something happens on an issue** | A company Jira Data Center, Jira Cloud, GitHub or GitLab system | Events Tinfirm reads on issues and change requests | On the issue |
| **On a schedule** | None | A time on a schedule, in a time zone | In Tinfirm, one per firing |
| **Someone mentions it in a channel** | A company Slack or Microsoft Teams system | A mention in one of its channels | In the chat thread |
| **A webhook is delivered** | A company webhook system | A delivery that matches its events and conditions | In Tinfirm |

## Who may see, change and run a trigger

Any member who may use an agent can write a trigger for it, and becomes the trigger's author. Viewers read triggers but do not create or edit them.

| Who | Sees it, its firings and conversations | Edits it, accepts a new version | Runs it now, pauses it | Resumes, deletes it | Reassigns it |
|---|---|---|---|---|---|
| Its author | Yes | Yes | Yes | Yes | No |
| A member of its team | Yes | No | Yes | No | No |
| The company's Owners and Admins | Yes | Yes, on the author's behalf | Yes | Yes | Yes |
| Anyone else | No | No | No | No | No |

A trigger's conversations are seen by the same people, and by a former author for the conversations that started with them. Only its author and its team write in them, answer, cancel, retry and end them; Owners and Admins do so only when they are its author or in its team.

**Triggers**, in the sidebar's **Work** section, lists the triggers you wrote and those of your teams (every trigger, for Owners and Admins), filtered by **All**, **Active** or **Paused**. Each row shows the sentence, the source, the agent, the author, the team (or **no team**), the payer, and when it last fired and with what outcome.

When an Owner or Admin edits your trigger, the editor tells them: *You change {author}'s trigger on their behalf: every choice must be one they may use, and they are told what you changed.* Your **Inbox** then shows **An admin changed it**, with what changed, for 7 days.

## Create a trigger

1. On **Triggers**, choose **New trigger**.
2. Fill in the sections. The editor shows one at a time: **When**, **The agent does**, **Acts as and pays**, **Narrowed for its runs**, **Team** and **Results**. Any tab opens any section; **Back** and **Next** step through them.
3. Read **Before you save**, beside the sections (on a phone, tap the bar at the bottom). After every change it shows the trigger's sentence, **Who acts and who pays**, a schedule's **Next firings**, what to **Mind that**, and who can drive the trigger.
4. Choose **Create trigger**. It waits until the preview passes, and, when it asks for one, until you tick **I understand** ([below](#who-can-drive-the-trigger)). A refused save opens the section and the field it names.

*It fires from now on; nothing before counts.* A trigger reacts only to what happens after it was created, resumed or reassigned. Its **Name** (up to 100 characters) must be unique among the company's triggers.

To change a trigger, choose **Edit** on its page. The source, its system and the agent stay fixed. *Its open conversations keep the accounts they started with.*

## When: what fires it

### Something happens on an issue

Pick the tracker under **System**. Tinfirm reads it with the trigger's account on that system ([below](#the-account-per-system)): the trigger sees what that account sees, and no more.

**Events (any of them fires it).** Up to five, each once. A change to an issue fires the trigger once, with the first of its events that happened:

| Event | Jira | GitHub, GitLab |
|---|---|---|
| **Assigned to the trigger's account** | Yes | Yes |
| **The trigger's account mentioned in a comment** | Yes | Yes |
| **Commented on** | Yes | Yes |
| **Created** | Yes | Yes |
| **Title, description or type changed** | Yes | Yes |
| **Moved to a status**, with the **Status** | Yes | |
| **Label added**, **Label removed**, with the **Label** | Yes | Yes |
| **Change request opened** | | Yes |
| **Change request got new commits** | | Yes |
| **Closed** | Yes | Yes |

"Assigned" and "mentioned" mean the trigger's account on the tracker, not you: an issue assigned to that account, a comment that mentions it. With your own account, that gives you personal automation: "when an issue is assigned to me, prepare it". With a service account's credential, they mean that credential's user. A comment that mentions the account raises the mention, any other comment **Commented on**.

**On.** On GitHub and GitLab, a change request is a pull request or a merge request. Pick **Issues and change requests**, **Issues** or **Change requests**. Jira has issues only. **Issues** takes no change request event, since those happen to change requests only.

**Scope.** Where it listens; empty means everything the trigger's account sees:

| System | Field | Example |
|---|---|---|
| Jira | **Project** | `SHOP` |
| GitHub | **Repository** | `acme/shop` |
| GitLab | **Project or group**: a project's or a group's path, up to 10 segments | `acme/shop` |

**Only when (optional).** Without conditions, every event in the scope fires it. **Add conditions** adds a group of rows, and **Or when** another, up to five. The trigger fires when any group holds, and a group holds when every row in it does. Each row takes up to 10 names, comma-separated, compared ignoring case:

| Row | Holds when |
|---|---|
| **Status is one of**, **Status is none of** (Jira) | The issue's status is one of the names, or none of them |
| **Has a label of** | Every label named is on it |
| **Has no label of** | None of the labels named is on it |
| **Type is one of**, **Type is none of** (Jira) | Its issue type is one of the names, or none of them |
| **Made by** | **Anyone**, **People**, or **Tinfirm (a conversation made it)** |

An issue without a status or a type counts as none of the names. A rule that could never fire is refused with the reason, for example a status every group excludes.

**How often.** **Every time**: each event starts a conversation on the issue, or continues the one this trigger has open there. **Once per issue**: it fires on an issue once, and never there again.

**How Tinfirm reads the tracker.** Tinfirm polls the tracker, about once a minute unless an admin set another interval on the system; it takes no webhooks from trackers. So:

- A firing comes a minute or two after the change: Tinfirm leaves a change of the last 90 seconds for the next pass.
- Tinfirm's own writes raise no event: a comment, status, label or commit that a trigger's conversation wrote fires nothing. This prevents loops.
- The first time Tinfirm reads an issue, only its creation (when it was created after Tinfirm began reading the tracker with that account) and new comments fire. A status, label or assignment change to an issue it had not read before fires nothing; its next change does. **Run now** fires it at once.
- A change older than 24 hours when it is first read fires nothing: the trigger counts it as a change read too late.

### On a schedule

| Field | What it takes |
|---|---|
| **Schedule** | A five-field cron: minute, hour, day of the month, month, day of the week, for example `0 9 1 * *` for 09:00 on the 1st of each month. Each field is `*`, a number, a range `a-b`, a step `*/n` or `a-b/n`, or a list of them. Days of the week are 0 to 7, both 0 and 7 Sunday; names such as `MON` are not taken. When both day fields name days, either matches. At most once every 15 minutes. |
| **Time zone** | Such as `Europe/Prague` or `UTC`. It starts with your browser's zone. Offsets such as `+02:00` are not zones. |
| **While the last one is busy** | **Skip while the last one is busy** (the default): no new conversation while the previous firing's is still working or waiting. **Start one anyway**: every firing starts a conversation of its own. |
| **Missed firings** | **Skip missed firings** (the default): a firing missed while Tinfirm was down, due more than an hour ago, starts nothing. **Catch up once**: one conversation for the missed firings, then the schedule goes on. |

The editor lists the **Next firings**. A time that a change to summer time skips fires at the same clock time after the change; a time the change back repeats fires once. A change keeps the next firing, unless you change the schedule or the time zone, which counts it again from now. A paused schedule has no next firing until it is resumed. Each firing starts a conversation of its own, and the previous firing's conversation, when idle, ends. A company has at most 50 schedules.

### Someone mentions it in a channel

Pick the Slack or Teams system under **Chat**, then its **Channels**: the channels its bot is in that you can see, 1 to 20. *A mention of the agent's handle in one of them starts a conversation in its thread; in a default channel any mention of the bot does. You must be a member of each.*

- Tick **Its default** on a channel to have a mention of the bot without any agent's handle come to this trigger. A channel has one default trigger, and an agent one trigger per channel.
- Any member of the workspace or tenant in those channels can drive it, linked to Tinfirm or not.
- A mention in the thread continues the same conversation. Questions are answered in the thread by anyone there; approvals only by a linked member who may decide them.
- A channel trigger needs no account on its chat system. On Slack it may have one: someone's own Slack sign-in, so that the agent can read the thread it is asked in.

[Talk to agents in Slack and Microsoft Teams](chats.md) explains threads, cards and linking; [Set up Slack and Microsoft Teams](../admin-guide/chat-systems.md) the bot.

### A webhook is delivered

Pick the webhook system under **Webhook**. An admin registered it with its secret and a mapping that reads each delivery's event, subject, actor and text ([Receive webhooks](../admin-guide/webhooks.md)).

| Field | What it takes |
|---|---|
| **Its events** | The event names the mapping reads, comma-separated, such as `issue.created, issue.updated`. Empty: every delivery. |
| **Only when the body** (optional) | Up to 5 conditions on the delivery's body, all of which must hold: a JSON Pointer such as `/issue/fields/priority`, and **is** one value, **is one of** several, **is present** or **is absent**. |

- Anyone who can make the sending software call the webhook drives the trigger. The actor a delivery names is as the sender says.
- When the mapping reads a subject key, a delivery about a subject the trigger has an open conversation on continues it. Without a key, each delivery starts a conversation, and the previous idle one ends.
- A new conversation's first turn gets the whole delivery, its body cut to 16 KiB. A delivery that continues a conversation passes only what the mapping reads: the event, the subject, the sender and the text.
- When the agent writes back to the sender, the sender's next delivery about that subject is an echo, and **Mind that** warns of it. Echoes count towards a chain ([below](#firings-and-why-nothing-started)); without a subject key they cannot be recognised, and only the firing limit and the budget bound such a loop.

[Alerts as triggers](../admin-guide/alerts.md) is a worked example.

## The agent does

**Agent**: an agent you may use. It is fixed once the trigger exists.

**Instruction**: *What the job is, every time it fires.* Up to 20,000 characters, in Markdown, in every turn. It comes from you alone: what people write on the issue, in the channel or in a delivery reaches the agent marked as their words, never as this instruction. The first turn also gets the issue, the mention or the delivery that fired it.

## Acts as and pays

*An agent holds no accounts: the trigger lends it one per system. Its runs act and pay as chosen here; nothing switches later by itself.*

### Use the accounts of

**Use the accounts of** lists you and the [service accounts](../admin-guide/service-accounts.md) you may use, each with its account on every system the trigger needs (for a service account, the credential lent to it), its state, and what its budget has left. While anyone can drive the trigger ([below](#who-can-drive-the-trigger)), the service accounts come first. Picking one fills **The account per system** and **Paid by**. You may then change any row; the editor then says **Rows changed by hand below.**

You may use a service account when an admin lets one of your groups use it. The company's Owners may use every service account.

### The account per system

One row per system the trigger needs: its source tracker first, then each system of its agent.

| Row | Rule |
|---|---|
| The source tracker | Required: *the trigger reads {system} and writes its results back with it.* |
| Each system of the agent | Optional. Left empty, it is **Not available to this trigger's runs**: its tools are refused and its repositories stay out of the workspace. |
| An MCP server that needs no account | No row: *No account needed: its runs call it without one.* |

A row holds your own account on the system, or a service account you may use with the credential lent to it. Never another member's account, never a credential lent to a group, which serves people's own conversations only, and never a chat's bot. A credential lent read-only shows **Lent read-only: its runs call no write tool on {system}.**

A row's state is **Ready**, **Sign in again**, **An admin must sign it in again**, **Its holder is suspended**, **Not yours to use** or **Disconnected**.

Each conversation of the trigger takes the table and the payer at its start, and keeps them; a change reaches new conversations only. Two things move an open conversation to other accounts: a reassignment ([below](#hand-a-trigger-to-another-author)), and an admin lending the service account another credential on a system in place of the one it had. Then the trigger follows its service account, and you are told in your inbox.

### Paid by

**Your budget**, or **{service account}'s budget** for a service account you may use. The budget pays through the company's model gateway, for a model the payer's key allows. The preview refuses a payer that cannot pay, or a trigger no runner host may run, with the reason.

The trigger's turns show on **Spend** under its name, for you and its team ([Your budget and spend](spend.md)). When a service account pays, its budget alerts reach you too, as the author of a live trigger it pays.

#### Pay with your own subscription

A trigger can also run on a subscription of yours, such as a Claude or ChatGPT subscription, on your own personal runner host, instead of a budget. All of these must hold:

- The company allows it: an Owner or Admin turns on **Its author's subscription**, under **Triggers** in the company's **Settings**, **General** ([Company settings](../admin-guide/settings.md)). It is off by default.
- You pay yourself: **Paid by** is **Your budget**. Picking a service account's budget sets the trigger back to a budget.
- You choose it yourself, as the author: an admin editing your trigger cannot, since only you see your own runner hosts.
- The subscription is still reported by your runner host and serves the trigger's runtime.

Choose it under **Narrowed for its runs**, in **Runtime and who pays**: **Who pays** then lists your subscriptions next to **The payer's budget**, as [My settings](my-settings.md#runtime-and-who-pays) does. While the company does not allow it, the field says *This company lets a trigger pay only with a budget: a subscription pays for your own conversations.* With a subscription, the trigger's turns run on the runner host that holds it.

A trigger fires with nobody signed in and its work goes to its audience, which a vendor's terms for a personal plan may not cover: the company that turns the switch on takes that on itself. If the company turns it off again, a trigger that pays with a subscription pauses at its next firing (**Its author may no longer use an account, the agent or the payer**) until you choose a model under the budget in its settings and resume it. It pauses the same way when the subscription is gone from your host.

## Who can drive the trigger

Whoever may write where a trigger listens drives its agent with the trigger's accounts and budget: anyone who may comment on or change the issues in its scope, any member in its channels, anyone who can make the webhook's sender call. What they write can try to steer the agent. So, for every trigger but a schedule, **Before you save** shows **Anyone can drive this agent**:

- It names who: for example *Anyone who may comment on or change issues in SHOP can drive Business analyst with these accounts.*
- It lists what would then run **without an approval**: each write tool still **Allowed**, with the account it would act as. Or it says *Every write asks the team first.*
- **Use {service account} instead** fills the table from a service account, so that writes do not act as you.
- **Ask before every write** makes every write tool ask the team first.

On a new trigger, the editor already sets every write tool to ask: *Anyone who may comment can drive this trigger, so every write asks the team first. You may change that below.*

While a write tool runs without asking, the save needs **I understand**, whether the row holds your own account or a credential lent to a service account: *anyone who may comment there drives this agent, and its writes run without asking.* The acknowledgement is kept with the trigger and recorded in the audit log; a later change needs no new tick. A credential lent read-only writes nothing and needs none.

When a trigger anyone may drive acts with a credential lent to a service account, whoever lent that credential is told in their inbox the first time, and the credential's page and the service account's page list the trigger.

## Narrowed for its runs

*The trigger's settings of the agent: they narrow it, never widen it.* They work as [My settings](my-settings.md) do, for the trigger's runs:

| Setting | What it does |
|---|---|
| **Runtime and who pays** | The runtime, and **The payer's budget** or, where allowed, your own subscription ([above](#pay-with-your-own-subscription)). Fixed when a conversation starts. |
| **Model**, **Effort** | A model the payer's key allows; a budget needs one. |
| **Memory** | **Remember across the trigger's conversations** ([below](#the-triggers-memory)). |
| **Automatic retries** | **Retry the trigger's failed turns by themselves**. |
| **Default repositories**, **Default projects** | A new conversation's workspace and where it looks first. |
| **Systems and tools** | Each system **Off for the trigger's conversations**; each tool **As the agent says**, **Ask** or **Off**. **Ask** waits for the team's approval, or yours without a team. |
| **Your own MCP servers** | *Only while every account of the trigger is your own: runs through a service account never reach your own servers.* |
| **Skills** | Each **Read when a task fits** or **Off for the trigger's conversations**. |

There are no added instructions: the trigger's instruction is in every turn. Saved settings reach the trigger's open conversations from their next turn.

## Team

**Who sees its conversations and decides**: a group of the company, or **Only you**. *The team sees the trigger and its conversations, decides their approvals, runs it now and pauses it. Without one, you alone do.* The team does not edit, resume or delete it.

- Approvals of the trigger's conversations go to the team, or to you without one. They are decided in Tinfirm, or in a channel's thread with the card's buttons by a linked member who may decide them; never by a comment on an issue, and never from an AI client.
- Questions go to the place: on an issue as a comment, which anyone answers with the next comment; in a channel's thread, to anyone there; on a schedule or a webhook, in Tinfirm. The team and the author can also answer in Tinfirm.
- Changing the team moves its open approvals to the new one. A deleted group leaves its triggers without a team, and their approvals go to the author.

## Results

### On an issue

With **Comment the result on the issue** (on by default), each result becomes a comment: the summary, the next steps, links to what the turn made and to the conversation in Tinfirm. A failure is commented too: *{agent} could not finish this: {cause}. The team of {trigger} was told.* A failure that is retried by itself is commented only once its last retry fails. Open questions become one comment that ends *Reply in a comment on this issue to answer.* An answer given in Tinfirm is commented as **{member} answered in Tinfirm:**, with the questions quoted. Every comment starts with **{agent} (Tinfirm agent):**, and is written with the trigger's account on the tracker. A cancelled turn writes nothing. On Jira, the issue also gets a link to the conversation, and a link to each change request the conversation opens.

Then come the actions of **When it succeeds** and **When it fails**, up to 10 each (**Add an action**):

| Action | Systems |
|---|---|
| **Move to status**, with the status | Jira |
| **Attach the documents**: each document the turn made | Jira |
| **Add a label**, **Remove a label**, with the label | GitHub, GitLab |
| **Close it** | GitHub, GitLab |

A write that fails for a passing reason, such as a timeout, is tried again for up to an hour, and never written twice. One that still fails is dropped, the conversation says why, and your inbox shows **Results could not be written back**.

### Review each result before it is written

With **Review each result before it is written**, nothing of a result reaches the issue until the team approves it, or you without a team. The result becomes a **Review** in the inbox, showing the writes exactly as they would be made. Approving sends them; returning it needs a note and sends the agent back to work with it. Failures are not reviewed. In a channel, the thread is told that the answer waits for the team's review, and the answer is posted once approved. The trigger's page has the same switch.

### In a channel, on a schedule, on a webhook

- **In a channel**: *Each answer is posted in its thread by the chat's bot.*
- **On a schedule or a webhook**: *Results stay in Tinfirm, with its team.* They write nothing outside Tinfirm by themselves, and the review switch holds nothing back. To put a report somewhere, the agent uses its tools, with the trigger's accounts and under their modes.

### Firings per hour, at most

*Past it, events are skipped and you are told once an hour.* The field says the company's default, which an empty field takes, and its maximum: 30 and 300 unless your admins changed them. Both a started and a continued conversation count.

## The trigger's conversations

A trigger keeps one open conversation per issue, per channel thread, or per webhook subject key; a schedule starts one per firing. A second event on the same issue continues the conversation, on the same session and branch. Another trigger on the same issue has a conversation of its own.

- **On an issue**, people talk on the issue. Every new comment there is passed to the agent's next turn; a comment wakes the agent only when it fires a trigger or answers an open question. In Tinfirm, such a conversation takes answers to its open questions only.
- **On a schedule or a webhook**, the team and the author write in the conversation in Tinfirm, and each message starts a turn.
- A conversation on an issue ends when the issue closes. There is no idle timeout.

The trigger's page links its open conversations.

## The trigger's page

Open a trigger from **Triggers**. Under its name: the sentence, the source, the agent and the version the trigger uses, the author and the team. The page shows:

- Its state: a **Paused** notice with the reason and what fixes it, or a notice of what it waits for.
- **Who acts and who pays**: each system's account, the payer, and where its runs run.
- **The rule**: **When**, **Instruction** and **Limits** (its firings per hour, how many were skipped over it, and how many changes were read too late).
- **Results**, with the switch **The team reviews each result before it is written**.
- **Memory** ([below](#the-triggers-memory)) and **Firings** ([below](#firings-and-why-nothing-started)).

Its buttons, as your rights allow: **Run now**, **Pause** or **Resume**, **Edit**, **Reassign** and **Delete**.

## Run now

**Run now** fires the trigger by hand, for its author, its team and admins:

- **A tracker trigger** fires on one **Issue** as its first event, with its own accounts and payer. Search with words of its title or type its key: the search uses the trigger's account, in its scope. The issue must be in the scope and meet the conditions; if it does not, the dialog names what fails. **Once per issue** does not hold it back.
- **A schedule** fires once now, as if it were due. Its next firing stays where it is.
- Channel and webhook triggers have no **Run now**: a mention or a delivery fires them.

A paused trigger does not run, and a run over the firing limit is skipped as a firing would be.

## Pause, resume or delete a trigger

**Pause** asks **Pause {trigger}?**: *It fires nothing, its conversations take no new turn and nothing is written back until it is resumed. What happens meanwhile never fires it.* Tick **Also stop the turns running now** to cancel its running turns too. Its author, its team and admins may pause it.

**Resume**, for its author and admins, first checks that what paused it is fixed; if not, it stays paused and says why. *What changed while it was paused fires nothing.*

**Delete**, for its author and admins: *Its open conversations end and what they have not written back is dropped; their history stays. Its name is free again.* Deleting its agent, or its source system, deletes the trigger too.

## A new version of its agent

When the trigger's agent gets a version that may do more, the trigger's runs keep the version its author accepted. The trigger's page shows **{agent} has a new version**, with what it adds, and the author's inbox shows **A new version of its agent to accept**. Only the author chooses **Accept version N**, never an admin on their behalf; an admin and the team see the card without the button, with **Waiting for {author} to accept**. What the agent no longer has applies to the trigger's runs at once. A change of the agent's instructions or skills waits the same way when the agent can act ([Make and share an agent](agents.md#change-an-agent-versions-widening-and-narrowing)), shown as the lines removed and added; until it is accepted, the runs keep the instructions and skills of the accepted version.

## The trigger's memory

With **Remember across the trigger's conversations** on (and memory allowed on the agent), the agent keeps notes for the trigger's later conversations. Each new note waits until the author, the team or an admin accepts it under **Memory** on the trigger's page, and the inbox shows **Memory notes to review**. [Agent memory](memory.md) says more.

## Firings, and why nothing started

**Firings**, on the trigger's page, lists what happened, newest first: when, the issue, the event (or **Run now**), the outcome, the depth of a chain, and a link to the conversation.

| Outcome | Means |
|---|---|
| **Started a conversation** | It fired and a conversation started. |
| **Continued its conversation** | Its open conversation on the subject took the event. |
| **Skipped: the last one is still busy** | A schedule under **Skip while the last one is busy**. |
| **Skipped: missed while Tinfirm was down** | A schedule under **Skip missed firings**. |
| **Skipped: too many triggered conversations in a chain** | An issue, or a webhook echo, that the agent's own work caused, more than 3 deep. The conversation that caused it says so. |
| **Skipped: over its firings per hour** | Past **Firings per hour, at most**. You and the team are told once an hour. |
| **Skipped: the agent is paused** | A schedule while the agent was paused. |
| **Failed** | A schedule's firing that could not start; the schedule moves on. |

A paused or waiting trigger records nothing, nor does an event its rule leaves out. *It has not fired yet. Changes made before it was created or last resumed never fire it.*

| What you see | Why | What to do |
|---|---|---|
| Nothing for an issue that matches by eye | Another event, another kind of subject, the scope, a condition, or **Once per issue** already fired there | **Run now** on the issue: the dialog names the failing clause |
| **Assigned** or **mentioned** never fires | They mean the trigger's account, not you | Assign or mention that account's user |
| Nothing on an issue the account cannot see | Tinfirm reads with the trigger's account | Give that account access, or change the scope |
| A comment started nothing | Only **Commented on** or a mention fires on a comment; otherwise the next turn reads it | Add **Commented on**, or mention the trigger's account |
| The agent's own comment or label started nothing | Tinfirm's own writes raise no event | Expected |
| A change made a day or more before Tinfirm read it fired nothing, for example after an outage | It was read too late: the page counts it under **Limits** | **Run now** on the issues that need it |
| **Skipped: over its firings per hour** | More events than its limit | Raise **Firings per hour, at most**, up to the company's maximum |
| A schedule shows **Skipped: the last one is still busy** | The previous firing's conversation still works or waits | Answer or end that conversation, or choose **Start one anyway** |
| A schedule shows **Skipped: missed while Tinfirm was down**, or nothing while the company's runs were stopped | Due more than an hour ago under **Skip missed firings**, or due during the stop | Choose **Catch up once**, or **Run now** |
| A schedule fires at the wrong hour | Its time zone | Set the zone you mean; check **Next firings** |
| **Waits: its system does not answer** | Tinfirm could not read the tracker for an hour | Ask an admin to check the system's address and network |
| A webhook delivery started nothing | No trigger's events or conditions match, or it is paused or waits | Ask an admin to try the delivery against the mapping ([Receive webhooks](../admin-guide/webhooks.md)) |
| Results never reach the issue | The comment is off with no actions, a review waits, or the write was dropped | Turn the comment on, decide the review, or read the conversation |

## When a trigger pauses or waits by itself

A trigger never switches to other accounts or another payer by itself. When something it depends on breaks, it pauses or waits.

**Paused** triggers stay paused until a person resumes them:

| Reason | Fix |
|---|---|
| **Paused by hand** | Its author or an admin resumes it. |
| **Its author left the company** | An admin reassigns it; the new author accepts it, which resumes it. |
| **Reassigned: waits for its new author to accept it** | The new author accepts its accounts, its payer and its open events, which resumes it. Nobody else resumes it. |
| **Its author may no longer use an account, the agent or the payer** | The author was suspended or made a Viewer, the agent is no longer shared with them, a service account is no longer theirs to use or lost its credential, the subscription that paid is gone, or the model is no longer allowed. The author changes the accounts or the payer, then resumes it; an admin may also reassign it. |
| **An account it acts with was disconnected** | The account went, became another identity, or its holder left. The author names another account for the system, then resumes it. |
| **A system it used was removed** | The author checks the accounts, then resumes it. |
| **An account it acts with needs signing in again** | Your own account needs signing in again, a credential needs an admin to sign it in again or enable it, or the service account it acts through is suspended. Once that is fixed, its author or an admin resumes it: it does not go on by itself. What happened meanwhile fires nothing. |
| **An admin paused its system** | It resumes when an admin resumes the system with its triggers, as the resume does by default; otherwise its author or an admin resumes it. |

**Waiting** triggers go on by themselves once the cause is fixed: *It fires nothing meanwhile, and goes on by itself once that is fixed.* They wait while the payer is suspended, while model access is not set up, while an admin stopped the company's runs, while the tracker does not answer or the account cannot read the scope, and while a chat's bot is missing, needs signing in again, is suspended, or is in none of the trigger's channels. When an admin lifts a stop of the company's runs, what happened during the stop fires nothing.

Your **Inbox** tells you: **Paused** or **Waiting** with the reason, **A new version of its agent to accept**, **Results could not be written back**, **Firings were skipped**, **An admin changed it**, **Memory notes to review** and **Reassigned to you: accept it**. The team also gets **Paused** when an account the trigger acts with needs signing in again, and Owners and Admins get it when that account is a credential lent to a service account. Owners and Admins also get **Its author left: reassign it**, and **Its system is not read** when Tinfirm's reading of a tracker waits on a credential or a suspended account. Whoever lent a credential to a service account gets **A credential you lent acts for anyone** when a trigger anyone may drive first acts with it (Owners and Admins, when who lent it is not known).

## Hand a trigger to another author

An Owner or Admin reassigns a trigger only after its author left, or lost a right it depends on (it is paused **Its author left the company** or **Its author may no longer use an account, the agent or the payer**). An active trigger cannot be reassigned. *The new author must hold a seat (not a Viewer) and use {agent}, and every account and the payer must be theirs to use: a service account's they may use, or their own budget. Their own accounts are theirs to add.*

1. Pick the **New author**.
2. For each row of **The account per system**, **Keep** what is there, if it passes for the new author, or name a service account they may use. A row whose account went with the former author says so.
3. For **Paid by**, keep the payer if it passes, or pick **The new author's budget** or a service account's.
4. Choose **Reassign**.

A reassigned trigger stays paused, **Reassigned: waits for its new author to accept it**, and the new author's inbox says **Reassigned to you: accept it**. The new author accepts its accounts, its payer and, when its writes run without asking, the acknowledgement; that resumes it. Nobody else resumes it, and an admin does not accept it for them. Without a team, its open approvals move to the new author. At the resume, each waiting conversation takes the new accounts and payer, and the conversation says so.

When an admin edits a trigger on its author's behalf, they cannot add the author's own accounts or the author's own MCP servers: only the author does. An admin cannot accept a new version of the agent for the author either.

When an author leaves the company, their triggers pause at once, their running turns are cancelled, and admins see **Its author left: reassign it** in their inbox.

## Limits

| What | Limit |
|---|---|
| Triggers on one system | 100 |
| Schedules in a company | 50; at most one firing every 15 minutes |
| Name | 1 to 100 characters, unique in the company |
| Instruction | 1 to 20,000 characters |
| Events of a tracker trigger | 1 to 5 |
| Condition groups | 5, each row up to 10 names |
| Actions | 10 on success and 10 on failure |
| Channels | 1 to 20 |
| Webhook conditions | 5 |
| Chain of triggered conversations | 3 deep |
