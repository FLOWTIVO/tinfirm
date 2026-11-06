---
version: 1
slug: "web-src-app-pages-company-conversations-conversation-page-ts"
primary_target: "web/src/app/pages/company/conversations/conversation-page.ts"
related_targets: ["web/src/app/pages/company/conversations/conversation-header.ts","web/src/app/pages/company/conversations/message-list.ts","web/src/app/pages/company/conversations/conversation-turn.ts","web/src/app/pages/company/conversations/live-turn.ts","web/src/app/pages/company/conversations/composer.ts","web/src/app/pages/company/conversations/conversation-side.ts","web/src/app/pages/company/runs/transcript-items.ts","web/src/app/shell/shell.html","web/src/styles.css"]
---

## Direction contract

THESIS: A conversation is a thread with the agent doing the work. The page refuses the category default: a dark zinc sidebar dashboard of tables and status badges in the shadcn style.

OWN-WORLD: Sage ground (#edf1ea, dark #111511) under white rounded sheets (#ffffff, dark #1a1f1a). Deep green (#1d6a4d, dark #72d3a8) marks the agent and primary actions. Warm orange (#b8430d, dark #ff9f6b) marks only "needs a human", and only for the reader who must act. Pill chips, 16px bubble radii, 14px cards, a 20px sheet for the thread, no hard borders on the canvas. Figtree for all UI text; DM Mono only for code, paths, keys and numbers. Lucide icons.

STORY: The visitor sees what the agent is doing and whether it waits on them, reads what was said and done, answers its questions and approvals in place, and writes in the same thread to steer it, even while a turn runs.

FIRST VIEWPORT: Head across the width: a back arrow, the title on one line, the state chip ("Working" green, "Needs you · Question" orange), "Details" below lg and the menu (Cancel turn, Retry or Continue, End conversation); under it the agent's tile and name, the key and branch in mono, the subject pill; then one quiet line of who acts and who pays. Main column: the thread as one white 20px sheet: people's messages as bubbles, yours green on the right; each turn as the agent speaking from the left without a bubble (a name line, its work folded into "Worked for 6m 12s · 14 tool calls", its answer as plain Markdown, its decision cards in place, a thin status line); the live turn's "Working" line; the composer docked at the sheet's foot, with the "Needs you" strip pinned above it while the reader's open cards are out of view. Right column 20rem from lg, one sheet: outputs, files, workspace, network hosts, cost, turns; below lg a sheet behind "Details".

FORM: Direction "Thread". Signature interaction: steering the agent by writing in the thread; a message sent while a turn runs reaches the agent after its current step, or in the next turn (ADR-0060).
