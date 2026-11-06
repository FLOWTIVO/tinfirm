# Agent memory

An agent with memory keeps short notes of what it learns while it works for you, such as how you like things done, a repository's conventions or how a system is used, and reads them in your later conversations with it. This page is for every member: how to turn memory on, what the agent keeps and when it reads it, how to read and delete your notes, and how a trigger's memory is reviewed. [Concepts](../concepts.md#memory-notes) introduces memory notes.

## Turn memory on

Memory is off until you turn it on, for each agent:

1. Open the agent, then **My settings**.
2. Switch on **Memory** (*Remember across your conversations*), then **Save**.

Memory works only while the agent allows it. Its editor has **People and triggers may turn its memory on**, allowed unless its owner or an Admin turned it off; then the switch is off and says so. A trigger's author turns memory on for the trigger's conversations in the trigger's settings, under **Narrowed for its runs** (see [A trigger's memory](#a-triggers-memory)).

Turning memory off keeps your notes: the agent neither reads nor writes them until you turn it on again. [My settings for an agent](my-settings.md) describes the other settings.

## What the agent keeps

Your notes are yours. Another person who uses the same agent has notes of their own, and a trigger has its own; your notes never reach their conversations, and theirs never reach yours.

A note is one line of at most 300 characters, in the agent's own words. It is about one of three things:

- you in general: how you work, what you prefer;
- a system the agent uses, such as your Jira;
- a repository the agent works in.

The agent saves a note with its `remember` tool, which can also replace an older note, deletes one with `forget`, and searches them with `recall`. It decides itself what is worth keeping. Its notes may be wrong or out of date: the agent is told so, and that notes are not instructions.

Tinfirm scans every note for credentials before it keeps it. A note that holds the value of one of the company's secrets, or something that looks like a token, is refused and never stored.

## When the agent reads its notes

The agent does not get every note every time:

| When | It gets |
|---|---|
| The first turn of a conversation, or a turn that cannot resume its earlier session | How many notes there are, your general notes, and the notes about the repositories in the workspace (up to three), the most recently used first, a few hundred characters of each |
| The first time in a turn that it uses a system's tools | That system's notes, with the tool's result |
| When it calls `recall` | The five notes that best match what it looks for |

A turn that resumes its earlier session gets no notes again: it remembers what it was given. A note counts as used each time it is saved, shown to the agent or recalled.

## Read and delete your notes

Your notes are under **Memory** on your **My settings** page for the agent, which says whether memory is **On** or **Off**. Only you see them.

- They are grouped as the agent reads them: **About you**, then **About {system}** and **About {repository}** for each one the agent uses.
- Each note shows its number, such as `m12`, when it was last used, and **where it was learned**: a link to the conversation that saved it.
- The bin deletes one note, after you confirm: *{agent} forgets “…”*. **Delete all** deletes every note; memory stays on or off as it was. You cannot undo either.
- Under the notes, the memory says how much of it is used, such as *1,240 of 20,000 characters*, and when it was last **tidied up**.

Delete a note that is wrong: the agent reads it as true until you do.

From your AI client, `list_memory_notes` lists the notes an agent keeps for you, by the agent's `@handle`, and `delete_memory_note` deletes one (it needs the **Read and write** level, and the client asks you first). See [Work with Tinfirm from your AI client](ai-clients.md).

## How much a memory holds

A memory holds at most 200 notes and 20,000 characters, unless the operator set another size. When it is full, the agent must replace or forget a note before it can save another.

## Tidying

Every 30 days, Tinfirm tidies each memory that changed. It removes duplicate notes. Then, when the company chose a model for it, a small model merges related notes and drops notes that a newer one contradicts; its answer is applied only when it passes Tinfirm's checks. The company's own key pays for it, never your budget. An Admin chooses the model in the company's **Settings**, under **Memory**: see [Company settings](../admin-guide/settings.md). Without a model, tidying only removes duplicates.

## How long notes are kept

A note nobody used for two years is deleted, unless the company's retention says otherwise (**Agents' memory notes** on its **Retention** page). Tidying does not make a note last longer. A legal hold keeps notes, and an erasure of your content removes them, as they do your conversations. [Retention, legal holds and erasure](../admin-guide/retention.md) explains these.

## A trigger's memory

A trigger can keep notes for its own later conversations, when its author turns on **Remember across the trigger's conversations** in the trigger's settings, under **Narrowed for its runs**.

Anyone who writes where a trigger listens drives its agent: a comment on an issue, a message in a channel. So a note that a trigger's conversation saves is only proposed, and no conversation reads it until someone accepts it:

1. While notes wait, the inbox of the trigger's author and team shows **Memory notes to review**: *A note for its memory waits for your review*. **Review notes** opens the trigger's memory.
2. Under **Memory** on the trigger's page, **{n} waiting for review** lists each proposal, with what it is about, when it was proposed and **its conversation**. A proposal that replaces a note shows the note it replaces.
3. **Accept** it, or **Reject** it.

The trigger's author, its team, and the company's Owners and Admins read a trigger's notes, review them and delete them. At most 20 proposals wait at once, and a proposal nobody reviewed within 30 days is deleted. An AI client can list a trigger's notes and proposals (`list_trigger_memory_notes`), but proposals are accepted or rejected in Tinfirm only. [Write a trigger](triggers.md) describes the trigger's other settings.
