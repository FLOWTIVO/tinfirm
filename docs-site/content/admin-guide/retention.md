# Retention, legal holds and erasure

This page is for a company's Owners and Admins. It explains how long the company keeps what its conversations said and made, how a legal hold keeps something past those periods, and how an Owner erases a person's content on request.

Open **Retention** in the sidebar's **Company** section. Only an Owner changes retention, sets legal holds and erases content. An Admin sees the periods, read-only.

| | Community | Pro | Enterprise |
|---|---|---|---|
| Retention periods | Nothing is removed by retention: [Retention in Community](editions.md#retention-in-community) | The Owner's periods | The Owner's periods |
| Legal holds | – | – | :lucide-check: |
| Erasure of a person's content | :lucide-check: | :lucide-check: | :lucide-check: |

The audit log is never removed, by retention or by erasure: [The audit log](audit-log.md).

## Retention periods

!!! note "Pro and Enterprise"

    Periods of your own need a Pro or Enterprise licence. Without one, the **Periods** section shows them read-only with a **Pro** badge. After a licence has run out, the periods in force keep running and cannot be changed until a new key: [When the licence ends](editions.md#when-the-licence-ends).

The Owner sees three sections: **Periods**, **Legal holds** and **Erase on request**. Under **Periods**:

| Period | What it removes | Counted from | Range | Starts at |
|---|---|---|---|---|
| **Conversations' content** | Messages, titles, questions and answers, results and outputs' titles | The end of the conversation | 30 to 3650 days | 365 days |
| **Files** | Files people added and documents the agents made | The end of the conversation | 7 to 3650 days | 365 days |
| **Chats' and webhooks' words** | Messages and answers of conversations in a chat or from a webhook | The end of the conversation | 1 to 3650 days | 30 days |
| **Transcripts** | What the agent did in each turn: its steps, tool calls and their results | The end of the turn, in an ended conversation | 7 to 3650 days | 90 days |
| **Agents' memory notes** | Notes an agent keeps for a person or a trigger | Their last use: being shown to an agent counts, tidying does not | 30 to 3650 days | 730 days |

An empty period, shown as **Keep**, keeps that kind for good. Chats' and webhooks' words are never kept longer than conversations' content.

### Change the periods

1. Type the new number of days, or empty the field to keep that kind. Each field shows what it **was**.
2. The line under the periods counts what the next daily sweep would delete with them, and with the saved ones, and what legal holds keep from it.
3. Choose **Save the periods**. When you shorten a period, **Save the shorter periods?** lists the changes and what the next sweep removes. What it removes cannot be brought back.

### What the sweep removes and what stays

Once a day, Tinfirm sweeps the company. For a conversation that ended longer ago than its period, it removes the content of that kind. The page says when it **Last swept** and what **The next daily sweep removes**.

What stays, whatever the periods:

- what each conversation was: its key, agent, place and dates, without its words;
- its spend: the tokens and cost of every turn;
- the audit log;
- what an open legal hold covers.

### Open conversations

Retention removes nothing of a conversation until it ends. The page says how many open conversations are older than their period, and the Owner's inbox has an item for them, **… open conversations older than the retention period**. The Owner sees them by key, place and age, never by title: opening one reads it, and the Owner's read is shown to its person. End the ones nobody needs on their page, and retention applies to them.

## Legal holds

!!! note "Enterprise"

    Setting a legal hold needs an Enterprise licence. Without it, **Set a hold** shows an **Enterprise** badge. Holds set earlier stay in force, and can be lifted.

What a hold covers is neither removed by retention nor erased until it is lifted. Nobody is told about a hold: their conversations go on as before. A hold brings back nothing already removed.

| Hold | What it keeps |
|---|---|
| **A person** | Their personal conversations, and what they said, added and decided in triggers' conversations |
| **A conversation** | All of it, its transcripts included, whoever spoke in it |

To set one:

1. Under **Legal holds**, choose **Set a hold**.
2. Under **What to hold**, pick **A person** and choose the **Person**, or **A conversation** and enter **The conversation's key** (as its page shows it, such as `SHOP-123-1`; its link works too).
3. Give a **Reason**, which is kept on the hold and never in the audit log, then choose **Set the hold**.

Each open hold shows who or what it covers, its reason, when and by whom it was set. **Lift** ends it, after **Lift the hold on …?**: what it held is then removed by retention and may be erased, like anything else. A lifted hold is deleted 400 days after its lift; the audit log keeps its events.

To hold a kind of content for the whole company, empty its period instead: **Keep**.

## Erase a person's content

Erasure removes someone's content now, as retention would, and says what remains. It is in every edition, so that no company pays to answer an erasure request. It cannot be undone, and a legal hold stops it.

1. Under **Erase on request**, step **Whose content**, pick:
    - **A member**, and choose them under **Member**; or
    - **Someone outside Tinfirm**, such as a person who wrote to the bot in a chat or commented on an issue: pick the **System** and enter **Their id there** (a Slack member ID, a GitHub or Jira username).
2. Choose **Review what goes**. Step **Review what goes** counts, without erasing anything, what goes now, what stays, and what is **Not Tinfirm's to erase here**.
3. Step **Confirm**: type the member's name, or their id at the system, then choose **Erase …'s content**, or **Erase their words** for someone outside Tinfirm.

| Whose | What goes |
|---|---|
| A member who left | Their personal conversations, with their transcripts; what they said in triggers' conversations; the files they added; their answers to questions and approvals |
| A member still in the company | Their ended personal conversations, with their transcripts, and the files in them. Their open ones stay: end them, then erase again |
| Someone outside Tinfirm | Their words in every conversation, open ones included, replaced by a removal marker; the transcripts of turns that quoted them; their name where it was kept |

Not Tinfirm's to erase here: the agent's own words, which may quote theirs until conversations' content goes under retention; the sessions on the runner hosts the turns ran on; what they wrote at the source system, such as an issue or a chat; and, for a member who left, their Tinfirm user, which an instance admin manages.

When a hold covers any of it, nothing is erased: the page names the holds, with **Go to Legal holds**. When it is done, the page says what went and what remains, and the audit log records `principal.erased` or `identity.erased` with the counts. The audit log itself names the erased member nowhere afterwards: it shows them as *Erased person*.

The checklist of [a member who left](members.md#a-member-who-left) leads here with the member already picked.

## The tidying in every edition

Some operational records have fixed periods of their own, outside the Owner's, and the daily sweep removes them in every edition:

| Record | Kept |
|---|---|
| A webhook's deliveries | 7 days; their bodies 24 hours |
| A chat event Slack or Teams sent | 1 day after it arrived |
| A chat user's profile | 90 days after they were last seen; they are read again when they write |
| A member's dismissal of an inbox notice | 400 days |
| A lifted legal hold | 400 days after its lift |
| Notes a trigger's agent proposed that nobody reviewed | 30 days |

Each sweep that removes anything is recorded once in the audit log, as `content.purged`, with what it removed by kind.
