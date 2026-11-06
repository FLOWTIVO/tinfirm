# ADR-0076: The Outlook pane shows the whole conversation, and remembers each email's conversation in the mailbox's roaming settings

- Status: Accepted
- Date: 2026-10-20
- Decided by: the owner, 2026-10-20
- Sources: [ADR-0074](0074-outlook-add-in.md), decision 8; [the add-in's guide](../operations/outlook-add-in.md) §3; Office.js's `Office.context.roamingSettings` (Mailbox 1.1)
- Changes: ADR-0074's decision 8, where the pane shows the agent's result and remembers the conversation of each email on this computer: it shows the whole conversation, and remembers it in the mailbox. The rest of ADR-0074 stands. The web app only (`pages/outlook`): no operation, tool, table or setting changes

## Context

- The pane showed the latest turn's result, from `get_conversation`'s `result`. A follow-up starts a turn, which clears it, so the pane showed only a spinner until the next turn ended, and never the earlier answers or the person's own messages. `get_conversation`'s `structuredContent` already carries every message, oldest first: the person's, the agent's answer of each turn (its summary with its next steps and open questions) and its questions' cards, and Tinfirm's notes.
- The pane remembered which conversation belongs to which email in its `localStorage`, by Outlook's item id. That link was on one computer and in one Outlook only, and Exchange changes an item's id when the email moves to another folder, so an archived email lost its conversation.
- An email's Internet message id stays when it moves and is the same in every Outlook of the mailbox. Office.js gives each add-in roaming settings: a JSON store of at most 32 KB in the person's mailbox, loaded when the add-in starts and shared by every Outlook of the mailbox (Windows, Mac, the web). Saving them needs no permission beyond `ReadItem`. A save replaces what another Outlook saved since this one loaded them.

## Decision

1. **The pane shows the whole conversation.** It shows the conversation's messages, oldest first: the person's first request as its action ("Investigate") or their own words, marked as sent with the email's text, never the email itself; the agent's answers and questions; the person's follow-ups, marked while they wait for a turn; and Tinfirm's notes. Where the conversation stands sits under the newest message, so a running follow-up keeps the earlier answers in view. **Use as reply** is offered under every answer of a turn that succeeded, with that turn's summary, without its next steps. The open questions are no longer listed apart: their cards are in the thread.
2. **An email is remembered by a hash of its message id.** The key is the first 32 hex digits of the SHA-256 of `message:` and the Internet message id; for an item without one, of `item:` and Outlook's item id. The id itself is not kept.
3. **The links are kept in the add-in's roaming settings and on this computer.** Under `tinfirm.outlook.{companyId}.conversations`, each key holds the conversation's id, the agent's name, the action and when it started: the last 100 emails, and fewer when they would take more than 24,000 bytes (UTF-8) of the settings' 32 KB, the oldest going first. The same links stay in this computer's `localStorage`, which keeps a link another Outlook saved over, and serves outside Outlook. When the two disagree, the newer link wins. A link this computer kept by Outlook's item id before moves to the key, and so to the roaming settings, when the pane opens that email.

## Consequences

- The pane shows an email's conversation in every Outlook of the person's mailbox, on any computer, and after the email moved to another folder.
- Nothing new is kept by Tinfirm. The mailbox keeps a hash of the message id, a conversation's id, an agent's name and an action per email; the mailbox's administrators can read them, as they can read the email.
- The links follow the mailbox, not the person: another mailbox, or a person who opens the email in a shared mailbox, has its own. A pane opened before another Outlook saved a link sees it once the pane is opened again. Two Outlooks that save at the same time keep the later save; the other computer still has its link in its own storage.
- Not in this step: a reference to the email kept by Tinfirm with the conversation, which another client (a Gmail add-in, the web app) could use too. It needs a parameter on `start_conversation`, a filter on `list_conversations`, a column, and rules for privacy, retention and erasure.

## Alternatives considered

- **A reference kept by Tinfirm** (a hash of the message id on the conversation, under its person, looked up with `list_conversations`). It works outside Outlook and across mailboxes, but changes the contract and keeps an identifier of the email that the same email gives every recipient. Deferred until a client other than Outlook needs it.
- **Custom properties on the email** (`item.loadCustomPropertiesAsync`). They also roam and move with the email, but write to every email the person asks about, and anyone who opens a shared mailbox's email sees them. Rejected.
- **`localStorage` alone, keyed by the message id.** It survives a move, but stays on one computer. Rejected.
