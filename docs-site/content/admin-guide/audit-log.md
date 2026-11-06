# The audit log

This page is for a company's Owners and Admins. It explains what the company's audit log records, how its events are chained so that a change or a removal shows, and how you read, filter, export and verify it.

Open **Audit log** in the sidebar's **Company** section. It shows every change in the company, newest first.

!!! note "Pro and Enterprise"

    Every edition records every event and chains it. Community shows the last 90 days. The filters, the export, **Verify chain** and the daily checks need a Pro or Enterprise licence: [Editions and prices](editions.md).

## What is recorded

Tinfirm records an event for every change in the company and for every use of authority: who added a member, who lent a credential, which account and which budget a run used, which tool an agent called with which account, and who approved it. Tinfirm itself records events too, such as a run that finished or a retention sweep.

An event holds:

- when it **Occurred**;
- the **Actor**: a person, an agent, or Tinfirm itself;
- the **Action**, such as `member.added` or `tool.called`;
- the **Target**: what it is about, by its kind and id;
- its **Details**: ids, counts and settings, never a person's name, a message's text or the reason someone gave for a switch;
- its **Hash** and its **Previous hash**.

The log shows names by looking them up when you read it. An actor who is gone shows as *Deleted user*, *Deleted agent*, or *Erased person* for a member whose content was erased after they left.

The audit log is kept for good. Retention never removes it, and neither does erasing a person's content: [Retention, legal holds and erasure](retention.md).

## The hash chain

The company's events form one chain. Each event's hash is computed from its own content and from the previous event's hash. Changing an event, removing one, or putting one back in another order breaks every hash after it, so a recomputation shows where the chain was tampered with.

??? note "How the hash is computed"

    The hash is the SHA-256, in hexadecimal, of the previous event's hash, a line break, and the event in a canonical JSON form: its company, its number in the chain (**Seq**), when it occurred in UTC, its actor's id and type, its action, its target's type and id, and its details, with the keys of every object sorted and no whitespace. The first event of a chain has no previous event.

The instance has a chain of its own, for what happens outside any company, such as sign-ins and users. Instance admins read it: [The Instance admin pages](../operator-guide/instance-admin.md).

## Read the log

Events are grouped by day, **Today**, **Yesterday** and then by date, with their count. Each row shows **Time**, **Actor**, **Action**, **Target** and the event's number in the chain. Choose a row to open it:

| Field | What it shows |
|---|---|
| **Occurred** | The date and time, in your time zone |
| **Actor** | Who acted, and whether a member, an agent or Tinfirm |
| **Target** | What it is about, with its id |
| **Event** | The event's id |
| **Hash**, **Previous hash** | The two hashes, each with **Copy** |
| **Show only** | Quick filters: **By** this person, **On this** target, **About** a member or service account, **In this conversation** |
| **Details** | The event's details as JSON, with **Copy** |

**Load more** reads older events.

## Filter the events

Above the list:

- **Search actions** finds an action by name. An action ending in a dot is a prefix: `tool.` finds every tool call. Suggestions list the common families.
- The actor: **Anyone**, **Any person**, **Tinfirm itself**, **An agent**, or one member by name.
- When: **Last hour**, **Today**, **7 days**, or **Custom**: days you pick on a calendar.
- **More filters**: **Target** (a kind), **Target id**, **Conversation** (its key, such as `shop-123-dev-1`, or its id) and **About** (a member or a service account: what they did, what was done to them, and what names them).

The filters in use show as chips; **Clear** removes them all. The address of the page keeps them, so you can share a filtered view with another Owner or Admin.

## Export a range

1. Choose **Export**.
2. Pick **By event number**, with **From event** and **To event** (both included; empty for the first or the latest), or **By time**, with **From** and **Until, not included**, in your time zone.
3. Choose **Export**.

The file is NDJSON, oldest first: a first line that states the range and the hash the first event chains from, one line per event in its canonical form with its hashes, and a last line with the chain's head at the time of the export. Whoever holds the file can recompute the hashes without Tinfirm. The export itself is recorded in the audit log, as `audit.exported`. One export holds at most a million events by default; export a long chain in ranges.

When a Pro or Enterprise licence has run out, the export keeps working for 90 days more: [When the licence ends](editions.md#when-the-licence-ends).

## Verify the chain

**Verify chain** recomputes every hash of the company's chain now.

- **Chain intact**: every hash matches, so no event was changed or removed. It says how many events it recomputed.
- **Chain broken**: the hash of an event, named by its number, does not match. That event, or one before it, was changed or removed outside Tinfirm.

A full verification cannot see that the newest events were cut off the end: what remains is still a valid chain. The daily checks catch that.

## The daily checks

Once a day, Tinfirm checks the chain from the head that the check before recorded. The page shows the latest result above the list, for example **Chain checked today at 03:12 · 18,204 events · none removed**, with the head's number and hash, which **Copy** lets you keep elsewhere. **Past checks** lists the last five.

A check fails when:

| It found | Meaning |
|---|---|
| The chain ends before the head the last check recorded | Its newest events were removed |
| The event at that head has another hash now | It was changed |
| An event does not chain from the one before | It, or one before it, was changed |

A failure goes to the inbox of every Owner and Admin, **The daily check of the audit log failed**, and stays there until a check passes or an Owner acknowledges it. Look into it first. Then an Owner chooses **Acknowledge the failure** and writes a **Note** saying why it is accepted. The note is kept on the check; the audit log records only that it was acknowledged. The next check starts from the head the failed one found.

The check runs in every edition; its results show under Pro and Enterprise.

## In Community

Every event is still recorded and chained. The page shows the last 90 days, without filters, export, verification or the daily checks' results. Enter a licence key to see the whole history: nothing was dropped.

## When an Owner reads a personal conversation

An Owner can read any member's personal conversation in full. Every such read is recorded as `conversation.read`, with what was read: the conversation, its turns, its transcripts, a live turn, its files or its questions and approvals. Each request is a record of its own, with no throttle.

The member is shown it: their conversation's page says **The Owner can read this**, with how often it was read, and **Who else read it** lists each reader, what they read, how many times, and when first and last. A trigger's conversations have no person, and their audience reads them by right, so their reads are not recorded.

An instance admin who opens a company they are not a member of cannot read personal conversations. Each request they make there is recorded as `admin.company.accessed`.

## The main families of events

| Prefix | What it records |
|---|---|
| `member.`, `principal.` | Members added, removed and their roles; suspensions, resumptions, departures, a closed checklist, erasures |
| `group.` | Groups created, changed and deleted, and their members |
| `system.`, `repository.` | Systems registered, changed, paused, resumed and removed; their tools, apps, bots and webhook secrets |
| `account.`, `credential.`, `grant.` | Accounts connected and disconnected; the company's credentials changed, lent and withdrawn |
| `service_identity.` | Who may use a service account |
| `agent.`, `skill.` | Agents and skills created, changed, shared, paused, given a new owner or passed to the admins |
| `trigger.` | Triggers created, changed, fired, paused, resumed and reassigned |
| `conversation.`, `decision.`, `run.`, `tool.` | Conversations started, read by an Owner and ended; questions and approvals; runs; every tool call and refusal |
| `memory.`, `memory_settings.` | Memory notes saved, accepted, refused, tidied and deleted |
| `model_settings.`, `model_key.`, `spend.` | The model gateway, keys and budgets, and spend exports |
| `runner_host.` | Runner hosts registered, changed and removed |
| `secret.` | Secrets created, updated, deleted and used |
| `company.`, `settings.`, `company_settings.`, `chat.` | The company's name, the stop of its runs, and its settings |
| `retention.`, `hold.`, `content.`, `identity.` | Retention periods, legal holds, what a sweep removed, erasures of outside people's words |
| `audit.` | Exports, and acknowledged daily checks |
| `admin.` | An instance admin acting in a company they are not a member of |
