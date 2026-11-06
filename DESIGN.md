---
name: Tinfirm
description: A self-hosted platform for AI agents, where every job reads as a thread with the agent doing the work.
colors:
  sage-ground: "#edf1ea"
  sheet: "#ffffff"
  ink: "#1b221c"
  moss-muted-text: "#56635a"
  agent-green: "#1d6a4d"
  agent-green-foreground: "#ffffff"
  human-orange: "#b8430d"
  human-orange-foreground: "#ffffff"
  human-soft: "#fdeadf"
  bubble-me: "#1d6a4d"
  bubble-me-foreground: "#ffffff"
  sage-secondary: "#e3eadf"
  sage-muted: "#f1f4ee"
  sage-accent: "#e8eee4"
  sidebar-accent: "#dfe7da"
  hairline: "#dae2d6"
  field-stroke: "#ccd6c8"
  logo-stem: "#4fae86"
  success: "#1f7a45"
  warning: "#9a5a00"
  destructive: "#b42318"
  night-ground: "#111511"
  night-sheet: "#1a1f1a"
  night-popover: "#1f251f"
  night-ink: "#e5ebe4"
  night-muted-text: "#9aa79b"
  night-agent-green: "#72d3a8"
  night-agent-green-foreground: "#0b1f16"
  night-human-orange: "#ff9f6b"
  night-human-orange-foreground: "#2a1206"
  night-human-soft: "#3a2418"
  night-bubble-me: "#244a3a"
  night-bubble-me-foreground: "#e6f5ec"
  night-hairline: "#29312a"
  night-field-stroke: "#354036"
  night-logo-stem: "#3f8c6b"
  night-success: "#6cc592"
  night-warning: "#f2b24c"
  night-destructive: "#f28b80"
typography:
  headline:
    fontFamily: "'Figtree Variable', ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 700
    lineHeight: 1.25
    letterSpacing: "-0.025em"
  title:
    fontFamily: "'Figtree Variable', ui-sans-serif, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 700
    lineHeight: 1.5
  body:
    fontFamily: "'Figtree Variable', ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.625
  label:
    fontFamily: "'Figtree Variable', ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.333
  mono:
    fontFamily: "'DM Mono', ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.333
    fontFeature: "normal"
rounded:
  md: "8px"
  lg: "10px"
  bubble: "16px"
  xl: "14px"
  2xl: "18px"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  2xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.agent-green}"
    textColor: "{colors.agent-green-foreground}"
    rounded: "{rounded.full}"
    padding: "0 16px"
    height: "36px"
  button-outline:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    padding: "0 16px"
    height: "36px"
  button-outline-hover:
    backgroundColor: "{colors.sage-accent}"
  button-ghost-hover:
    backgroundColor: "{colors.sage-accent}"
    textColor: "{colors.ink}"
  button-destructive:
    textColor: "{colors.destructive}"
    rounded: "{rounded.full}"
  input:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "4px 12px"
    height: "36px"
  card:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.xl}"
    padding: "16px"
  bubble-agent:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.bubble}"
    padding: "9px 14px"
  bubble-me:
    backgroundColor: "{colors.bubble-me}"
    textColor: "{colors.bubble-me-foreground}"
    rounded: "{rounded.bubble}"
    padding: "9px 14px"
  chip-status:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    padding: "0 10px"
    height: "24px"
  chip-needs-human:
    backgroundColor: "{colors.human-soft}"
    textColor: "{colors.human-orange}"
    rounded: "{rounded.full}"
    padding: "0 10px"
    height: "24px"
  decision-card-open:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    borderColor: "{colors.human-orange}"
    rounded: "{rounded.xl}"
    padding: "12px 14px"
  composer:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.2xl}"
    padding: "10px 10px 10px 16px"
  nav-item-active:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "8px 10px"
---

# Design System: Tinfirm

## Overview

**Creative North Star: "The Thread"**

A conversation is a thread with the agent doing the work ([PLATFORM.md §8.1](docs/PLATFORM.md#81-conversations)). The app sits on a soft sage ground, and every piece of content is a white rounded sheet laid on it: a list, a group of the inbox, the thread of a conversation and the side column beside it, the run's transcript. A thread is one sheet that reads like a calm, flat transcript: people speak in bubbles, yours from the right in green, and each turn is the agent speaking from the left, without a bubble: a name line, its work (folded once the turn is over), its answer as plain text and a thin status line. A decision the agent needs sits in the thread where it was raised, as a card answerable in place, and comes back above the composer while the reader reads elsewhere in the thread. Inside the work the agent's thinking reads in full in quiet italics, and its tool calls and log lines fold into one muted sentence per stretch ("Ran 5 commands, read 2 files"), so the machinery never outshouts what was said. The run page draws the same rows, so a run reads the same everywhere.

Density is moderate and calm: 14px body text, 15px in the thread, 20px gaps between sheets, soft hairlines instead of rules. Colour is scarce and semantic. Deep green is the agent and the primary action; warm orange is reserved for the one thing a human must act on. Everything else is sage-tinted neutral. The system explicitly refuses the category default: a dark zinc sidebar dashboard of dense tables and status badges.

Light is the default theme; dark (`.dark` on `<html>`) is a full parallel palette on a near-black green ground, not an inversion.

**Key Characteristics:**
- Sage ground under white rounded sheets; sheets separate by tone, with only a hairline in the border tone.
- Deep green for the agent and primary actions; orange only for "needs a human".
- Pills everywhere a thing is a status, a phase, a choice or an action; 16px bubbles for speech; 14px sheets for containers; 20px sheets for the thread and the run's transcript.
- Figtree for all UI text, DM Mono only for code, paths, keys and numbers.
- Lucide line icons, never glyphs or emoji.
- No raster imagery: the only bitmap the app ships is its 180px home-screen icon; avatars are initials on colour tiles. An image a person or an agent added (an attachment, a picture in a document) shows as content, never as decoration.

## Colors

A sage-tinted neutral world with exactly two voices: green for the agent, orange for the human decision.

### Primary
- **Agent Green** (agent-green; dark: night-agent-green): the agent's colour and the primary action. Agent initials tiles, primary buttons, the "Working" state of a conversation and the live "Working" line of its turn (pills at 10% tint, a pulsing dot), the "Live" pill and the current phase chip of a run, the running call's spinner, links in rendered Markdown, the focus ring, the text caret, selection tint (22%) and native `accent-color`.
- **My Bubble** (bubble-me; dark: night-bubble-me): the fill of your own messages. It shares Agent Green in light mode and drops to a deep muted green in dark mode so a long run of your own messages does not glare.

### Secondary
- **Needs-a-Human Orange** (human-orange; dark: night-human-orange): the only warm colour. It marks where the reader must decide or act: an open decision card they may decide (its border and its kind), the "Needs you · Question" state of a conversation that waits on them, the "Needs you" rows and count of the inbox and the sidebar's badge, the "Needs you" strip pinned above the composer, a Connect card or a part of who acts and who pays that the reader acts on, the option a person picked in a question, and a turn that ended blocked.
- **Human Soft** (human-soft; dark: night-human-soft): the tinted ground behind orange text in pills and badges and in a picked option.

### Neutral
- **Sage Ground** (sage-ground; dark: night-ground): the page and sidebar background. Content never sits on it without a sheet.
- **Sheet** (sheet; dark: night-sheet, popover night-popover): cards, the thread, the side column, other people's bubbles, inputs, outline buttons, the active nav item and the picked pill of a choice.
- **Ink** (ink; dark: night-ink): body and heading text.
- **Moss Muted Text** (moss-muted-text; dark: night-muted-text): metadata, labels, timestamps, placeholders of fields, the composer's hint, Tinfirm's notes, the sentences of tool call groups and a turn's status line.
- **Faint** (`--faint`: #637067; dark #839086): quieter than muted yet at least 4.5:1 on the ground, cards and muted fills (WCAG AA), for the dots between the parts of a meta line, the composer's placeholder, the icon in a search field, the audit log's quiet details, and a budget bar at rest.
- **Code, Diff Add, Diff Del** (`--code` #f1f4ef / dark #131713, `--diff-add` #e3f1e8 / #1c3326, `--diff-del` #fbeceb / #3a1d1b): an opened tool call and its diff lines, a tool call's arguments on a decision card, code in a decision's request.
- **Sage Secondary / Muted / Accent** (sage-secondary, sage-muted, sage-accent, sidebar-accent): tonal steps for neutral pills and tiles (secondary), code and quote grounds, the track of a segmented control, neutral state chips and the typing indicator (muted), and hover fills (accent, and sidebar-accent in the sidebar).
- **Hairline** (hairline; dark: night-hairline): the one border tone for sheets and dividers.
- **Field Stroke** (field-stroke; dark: night-field-stroke): input, textarea and select borders, a dashed slot; also the scrollbar thumb.

### Status
- **Success** (success; dark: night-success): completed turns, succeeded runs, done plan steps, diff additions, a reachable system, online runner hosts, a verified audit chain.
- **Warning** (warning; dark: night-warning): reconnecting streams, lost runs, a turn stopped at its time limit (in its status line), rate limits, a budget near its limit, a context window nearly full, soft configuration warnings.
- **Destructive** (destructive; dark: night-destructive): failed turns and runs, failed calls, diff removals, an unreachable system, and the destructive button (text on a 10% tint, never a solid red fill).

### Named Rules
**The Two Voices Rule.** Green belongs to the agent and to "do it"; orange belongs to "a human must decide". No third accent. States map accordingly: a conversation the agent works on is agent green ("Working"); one that waits on the reader (a question, an approval, a review, an account) is human orange; a completed turn is success green and a failed one destructive; a conversation waiting on a runner, a budget or another person, an idle, new, cancelled or ended one, and a turn stopped at its time limit stay neutral in their state chip.

**The Scarce Orange Rule.** Orange appears only where the reader is waited on. If nothing on the screen needs them, no orange is visible: a decision someone else decides reads "Waiting for …" without orange, a conversation that waits on its person is neutral to the Owner reading it ("Waits for Ana · Question"), and a decided or cancelled decision loses its orange.

## Typography

**Body Font:** Figtree Variable (with ui-sans-serif, system-ui fallback)
**Mono Font:** DM Mono 400/500 (with ui-monospace, SF Mono, Menlo fallback)

**Character:** Figtree is round, friendly and legible at small sizes, which suits a conversational surface; DM Mono is its quiet technical counterpart, used only where the content is literally machine text. Both are bundled with the app, never loaded from a CDN.

### Hierarchy
- **Headline** (700, 1.5rem, 1.25, tracking -0.025em): page titles (`app-page-header`), balanced wrapping. The run page's and the new conversation's titles are 1.25rem below `sm`. A conversation's title is one truncated line, 1rem on a phone and 1.25rem from `sm`, so the state and the actions keep their place beside it.
- **Title** (700, 1rem): section headings inside a page ("Needs you", "Stopped", "Notices" in the inbox). Card titles use weight 500. The side column's headings ("Workspace", "Cost", "Turns") are semibold 0.8125rem with their count in muted mono.
- **Body** (400, 0.875rem, 1.625): Markdown, table cells; 0.9375rem in the thread and its bubbles. Bubbles cap at 85% of the thread's width, the agent's answer at 66ch.
- **Label** (600, 0.75rem): field labels, chip and pill text, the state chip, the labels of a decision's request. Sentence case.
- **Mono** (400, 0.75rem, tabular numbers): conversation and subject keys (SHOP-241-1, SHOP-241), branch names, SHAs, failure classes, token counts, costs, durations and elapsed timers, the targets of tool calls, repository names and network hosts, keyboard hints.

### Named Rules
**The Mono Is Machine Text Rule.** DM Mono is for code, paths, keys and numbers only. Never for headings, labels or decoration.

**The Sentence Case Rule.** Labels and chips are sentence case at 0.75rem semibold. No uppercase tracked micro-labels above headings.

## Layout

An app shell: a sage sidebar (no border, same ground as the page) and a main column with a sticky, translucent 56px top bar (ground at 70-85% with backdrop blur) holding the sidebar toggle, a short hairline and the breadcrumb. Main content pads 16px on mobile and 32px from `md`, up to 72rem wide, or 100rem for the wide pages: a conversation, a run and the trigger editor.

Pages open with a page header (the title, a one-line description, and the page's actions at the right from `sm`) and stack their sheets 20px apart.

- **The conversation page** (`conversations/:conversationId`): the head spans the width: the header, what the conversation is in one muted line (from `sm`), and one quiet line of who acts and who pays. Below it, from `lg` (1024px), a two-column grid: the thread sheet in a fluid column and a fixed 20rem side column, one 14px sheet of sections split by hairlines (its outputs, its files, the workspace, the network hosts, what it cost, the turns). Below `lg` the side column moves into a right-hand sheet behind "Details", which on a phone also holds what the head leaves out. The full panel of who acts and who pays opens above the thread, level with the side column. The thread is one sheet; its composer is docked at its foot.
- **The conversations list**: two segmented controls ("Mine" or "Everyone's" for the Owner, "List" or "By subject") over one 14px sheet whose head is a row of filters (the Show pills, then Agent, Trigger and, for everyone's, Person) and whose rows, split by hairlines, list what needs the reader first under its heading, then the other conversations. "By subject" is one sheet of the issues the conversations are about, with their counts.
- **The inbox** (at most 66rem wide): a row of pills (All, and each group with its count) over up to three groups, each a heading with its count and hint over one 14px sheet of rows: Needs you (one compact row each, answered in the row or opened in place as the whole card, one at a time), Stopped (turns that failed or wait, with Retry or Continue) and Notices (quiet lines, dismissible). A row settled here collapses to a muted line of what was done.
- **The run page**: a header (a link back to the conversation's key, the agent's tile and name, the run's status chip, "Cancel run" while it is queued or runs), a muted line of the turn and its conversation, why it waits while queued, a line of facts (queued, duration, runner host, session, tokens, branch), who acts and who pays, the repositories with their git result, then the transcript in a 20px sheet that fills the viewport (at least 24rem) and scrolls inside itself with a 1.5rem fade mask at its top edge, and the agent's result under it.
- **Pages with a side column** (an agent's page, a person's settings of an agent): a fluid column and a 20rem column from `lg`; the settings page's column, who acts and who pays, sticks under the top bar.
- **Sectioned pages** (Settings, Model access, Retention, the agent editor and the trigger editor): a section index at the left from `lg` (15rem, 17rem in the agent editor), sticky under the top bar, beside the open section; below `lg` the index of Settings, Model access and Retention is a row of pills that scrolls sideways, and the editors' index is the phone's first screen, from which each section opens on its own. The trigger editor puts "Before you save" in an 18.5rem column of its own from `xl`, and below `lg` behind a sticky save bar.

Spacing rhythm is on a 4px base: 8px inside clusters, 12-16px inside sheets, 20px between sheets and card padding on desktop, 24px between the entries of a thread and between the groups of the inbox.

## Elevation & Depth

Depth is tonal first: white sheets on sage ground, with hairlines in the border tone. Shadows are soft, dark-only (black at low alpha), with negative spread, and appear for two reasons: to lift speech or a picked option a hair off its ground, and to float things over content.

### Shadow Vocabulary
- **Bubble lift** (`box-shadow: 0 1px 2px rgb(0 0 0 / 0.06)`): other people's bubbles on the thread's sheet.
- **Button press** (`box-shadow: 0 1px 2px rgb(0 0 0 / 0.08)`): primary buttons only.
- **Active nav** (`box-shadow: 0 1px 3px rgb(0 0 0 / 0.08)`): the active sidebar item, the current row of a section index, the selected pill of a tab row, a segmented control and the inbox's filters: a white sheet on the ground or a muted track.
- **Floating control** (`box-shadow: 0 6px 16px -4px rgb(0 0 0 / 0.2)`): the "Latest" button over the run's transcript, while the reader has scrolled up from its end.
- **Action bar float** (`box-shadow: 0 10px 30px -12px rgb(0 0 0 / 0.25)`): the sticky bars that float at the foot of a page or a dialog: the unsaved-changes bar of the settings, the "Not set up yet" bar of Model access, the changes bar of the group dialog and the trigger editor's save bar below `lg`. A conversation's composer sits inside its sheet and floats on nothing.
- **Entry sheet** (`box-shadow: 0 12px 32px -12px rgb(0 0 0 / 0.15)`): the card of the entry pages: sign in, the consent page, the chat link.
- **Menu** (`box-shadow: 0 12px 32px -8px rgb(0 0 0 / 0.18)`): dropdown menus and popovers (the audit log's filters, who read a conversation).
- **Dialog** (`box-shadow: 0 24px 48px -12px rgb(0 0 0 / 0.25)`): dialogs and alert dialogs. Side sheets (a conversation's details, the phone's navigation) use Tailwind's `shadow-lg`.

### Named Rules
**The Tone Before Shadow Rule.** A sheet on the ground separates by colour and a hairline; it does not get a shadow at rest. Shadow is for speech, for the picked one of a set, and for things floating over other content.

## Shapes

One base radius (0.625rem) scales the whole system through the spartan preset: 8px for inline code, kbd and the 20px agent tile of a name line; 10px for fields, selects, nav items, code blocks, an opened tool call and the 32px tiles of the inbox; 14px for cards, sheets, dialogs, decision cards, the side column and the inbox's groups; 18px for the composer, the new conversation's message box and the floating action bars; 20px for the thread and the run's transcript. Everything that is a status, a phase, a chip, a choice or a button is a full pill. Speech bubbles are 16px with one tight 8px "tail" corner at the top, on the speaker's side: top-left for other people, top-right for you. The typing indicator keeps its tail at the bottom left.

Borders are 1px hairlines in the border tone, never dark. Dashed hairlines mean "not there yet": empty states, the steps a run or a setup has not reached (a run's phase chips, the steps of registering a system or of an erasure), a transcript that retention removed, and the slot that adds something ("About an issue" on a new conversation, a skill to add to an agent).

## Logo

The mark is **Ti**, drawn in the same pills as the rest of the system. The T is two sheets: its bar in Agent Green laid over its stem in Logo Stem (logo-stem; dark: night-logo-stem), with a 2-unit cut between them. The i is a Logo Stem pill under a Needs-a-Human Orange dot that sits on the bar's line. Beside the mark, the wordmark "tinfirm" is a lowercase monoline in Ink with round caps, its second i dotted in orange. The two dots say the product in one picture: green does the work, orange is where a person is needed. Drawn on a 64-unit grid: the bar 36×14, the stems 14 wide, gaps of 2 and 4 units. One component draws both: `<app-logo>` (`web/src/app/shared/logo.ts`), sized by its host's height.

- **Where:** the entry pages (sign in, the consent page, the chat link) show the mark at 48px above their heading; the sidebar header shows the mark and the wordmark, quiet, linking home; the favicon is the mark.
- **Small sizes:** at 32px and under, the cut disappears and the T and the i are one green, under the orange dot (the favicon).
- **Backgrounds:** never two letters on a dark rounded tile, which reads as a design-tool app icon. The app icon is the mark on a sage square.

### Named Rules
**The Quiet Logo Rule.** Inside the app shell the logo is quiet: the mark's dot turns Agent Green and the wordmark's Ink, so the Scarce Orange Rule still holds. The orange dots appear only on the entry pages and outside the app.

## Components

### Buttons
Soft, round and confident.
- **Shape:** full pill (9999px), semibold 0.875rem.
- **Primary:** agent green fill, white text, 36px tall with 16px side padding (sm 32px, lg 40px, xs 24px; icon buttons are circles of the same heights), with the button-press shadow. Hover drops to 90% opacity of the fill.
- **Hover / Focus:** 3px focus ring in the ring colour at 50%; a 1px press-down on active.
- **Outline:** white sheet with hairline border; hover fills with sage accent.
- **Ghost:** transparent; hover fills with sage accent.
- **Secondary:** sage secondary fill.
- **Destructive:** destructive text on a 10% destructive tint (20% on hover), never a solid red fill.
- **Link:** green text, underline on hover.
- **On a phone:** a control that acts grows to a 44px target.

### Chips
- **Status chip:** a pill 24px tall, 0.75rem semibold, with a hairline border, for a run's status and a system's. A run's status tints it: queued and cancelled stay white, running is green 10% with green text and a live dot (a hollow ring while queued), succeeded, failed and lost keep the white ground with success, destructive or warning text and a border of that tone at 40%. A system reads "Not tested yet" (neutral), "Reachable" (success) or "Unreachable" (destructive, the last error as its title), and "Paused" beside it.
- **State chip** (a conversation's header) and **waiting-on badge** (its row in the lists): who the conversation waits on, as a 24px pill without a border: "Needs you · Question" (or Approval, Review, Account) on human soft with orange text, only for the reader who must act; "Waits for Ana · Question" muted for anyone else, and in the header "Waits for Analysts · Review", orange for a member of the team who decides it; "Working" at green 10% with a pulsing dot; "Waiting · Budget spent" and the other reasons a runner waits, muted; "Turn failed" at destructive 10%; "Stopped · Time limit" muted. The header adds "Cancelled", "Agent paused", "Idle", "New" and "Ended", all muted. In the lists the badge leads with a crossed circle, a pulsing dot, a dot or a clock, and its title says since when.
- **Kind pills** (the inbox's rows): 24px pills naming what a row is; human soft with orange text for what needs the reader, destructive 10% for a failed turn, sage secondary for the rest.
- **Live pill** (the run page's transcript): green 10% tint, green text, a pinging green dot; "Connecting…", "Reconnecting…" (warning) or "Updates stopped" with Retry (destructive) in its place.
- **Phase chips** (the run page's transcript, in its header beside the stream's state and the token meter): Preparing, Cloning, Starting, Running, Finalizing, Pushing as 24px pills in a row: the current one filled green, the ones passed on muted ground with green text and a check, the ones to come with a dashed hairline. The steps of registering a system and of an erasure use the same pills.
- **Run events** (one renderer, `web/src/app/pages/company/runs/transcript-items.ts`): the run page's transcript and a turn's work in the thread draw the same rows, without pills. What the agent said is plain Markdown at 15px, up to 66ch; its thinking reads in full in 14px muted italics; every stretch of tool calls and log lines is one group (Groups, under The Thread), a lone call or log block a group of one; a git step is a 13px muted line with its icon (branch, commit, upload), what happened ("Checked out agent/SHOP-241-1", "Pushed agent/SHOP-241-1 (2 commits)"), the folder and the SHA in mono; the plan is a list-checks line with "2 of 5" and check, circle-dot and circle items (the step in progress in ink and green only while the run is live); a rate limit is a warning line with a clock; an error a destructive line with a circle-x and its failure class in words in front. On the run page only, a phase change is a 12px muted line with the phase in semibold and its message; the thread leaves phases, commits and pushes to the turn's status line and shows a plan only while the turn is live.

### Cards / Containers
- **Corner Style:** 14px.
- **Background:** white sheet (dark: night sheet).
- **Shadow Strategy:** none at rest (see Elevation & Depth).
- **Border:** 1px hairline.
- **Internal Padding:** 16px (12px for the small size); page sheets use 20px. Card footers sit on muted at 50% with a top hairline.

### Decision card
A decision ([PLATFORM.md §8.2](docs/PLATFORM.md#82-decisions)) as a 14px sheet with a hairline and 12px 14px padding, answerable where it stands: in its conversation's thread, pinned above the composer, and in the inbox, where an opened row draws its body inline under the row's own title. Its title line names the kind ("Question", "Approval", or "Review" for a trigger's result) in semibold and, in muted text, who wants what ("Business analyst wants to open a change request on acme/shop"). A 12px muted line under it says how long it has waited, or when it was raised once decided. Then the question in Markdown (13px), and for an approval its request in a bordered box: a tool call's What, System, Runs as and its arguments folded; a repository; a network host; a change request; a result's summary and exactly what would be written to the issue; and why, in the agent's words. The options of a question are outline pills; a repository approval picks "Read only" or "Read and write"; a free answer or a note; and the actions as small pills, the primary one green: "Send answer"; "Approve" or "Deny"; "Allow for this conversation", "Allow for Business analyst, for good" (only for whoever may edit the agent) or "Deny"; "Approve and write it" or "Send back", which needs a note. A muted hint says what happens next, and a team's approval adds that whoever decides first decides for everyone, in Tinfirm and never through an AI client. Open for a reader who may decide, its border and its kind are orange and a picked option turns human soft with orange text. A reader who may not decide sees "Waiting for …" (the person, or "the team Analysts") without orange. Decided or cancelled, its outcome is a muted pill in the title line ("Answered", "Approved", "Denied", "Allowed for the agent, for good", "Approved, written", "Sent back", "Cancelled") and it shows who decided and what, and an approved call's result.

### Inputs / Fields
- **Style:** white sheet, 1px field-stroke border, 10px radius, 36px tall (a small select 32px), 0.875rem text from `md` (1rem below, to avoid mobile zoom).
- **Focus:** border shifts to the ring colour plus a 3px ring at 50%.
- **Error / Disabled:** destructive border with a 3px destructive ring at 20% (40% dark); disabled at 50% opacity on an input-tinted ground.

### Navigation
- **Sidebar:** same sage ground as the page, no border. Its header holds the logo and the company switcher, its foot the user menu. Items are 10px-radius rows, 0.875rem, Lucide 16px icons, in groups: Work (Inbox, Conversations, Agents, Skills, Triggers, Accounts) for every member, Company (Systems, Service accounts, Model access, Runner hosts, Members, Kill switches, Audit log, Retention, Settings) for OWNER and ADMIN, and Instance admin (Users, Companies, Instance audit log, Errors) for an instance admin (`web/src/app/shell/navigation.ts`). Hover fills with sidebar accent; the active item becomes a white sheet with the active-nav shadow, semibold text and a green icon. Group labels are 0.75rem semibold muted sentence case. The Inbox carries a human-soft badge with the number that needs the reader (99+ at most).
- **Top bar:** translucent ground with blur, breadcrumb in muted text with the current page in semibold ink.
- **Tab rows** (Members and its groups, a system's page): 36-40px pills on the ground (44px on a phone); the selected one a white sheet with a hairline and the active-nav shadow, the others muted, filled with accent on hover.
- **Section index** (Settings, Model access, Retention, the agent and trigger editors): from `lg` a sticky column of 14px rows, each an icon, the section's name in semibold and a muted line of how it stands; the current row a white sheet with the active-nav shadow and a green icon. Below `lg` the same items are pills in a row that scrolls sideways. Arrow keys, Home and End move along the index where it is a tab list.
- **Segmented control** (`app-segmented`): a choice of a few values as pills on a muted track, the picked one a white pill with the active-nav shadow; each pill is a radio of one group, so the arrow keys move between them. It stretches to the full width on a phone, or scrolls sideways where its pills keep their width.
- **Mobile:** the sidebar becomes an off-canvas sheet.

### The Thread (signature component)
A conversation's thread holds the people's messages, the agent's turns, the decisions they raised and Tinfirm's notes ([PLATFORM.md §8.1](docs/PLATFORM.md#81-conversations)). It is one white sheet (20px radius, no border) in the conversation page's main column (Layout), its entries 24px apart at 15px, inside 16px 14px padding (20px 24px from `sm`).

- **Header** (above the thread, across the page): a ghost back arrow to the list, the title on one line, renamed in place by its person (a pencil on hover or focus), the state chip, "Details" (outline, below `lg`), and a menu for its person and the Owner: "Cancel turn" while a turn is queued or runs, "Retry" after a failed or cancelled turn ("Continue" after one stopped at its time limit, which goes on from where it stopped), and "End conversation", which asks first ("End this conversation?", "Keep it open"). Under it, from `sm`, one muted 13px line of what the conversation is: the agent's 20px tile and name as a link, whose it is when not the reader's ("for Ana"), its key and branch in mono, the subject as an outline mono pill that opens the issue, the chat that holds it, and "AI client" when its person started it from one. Then one quiet line of who acts and who pays ("Acts as GitHub: your own account · Pays from your budget, $38.20 left · Runs on build-01"), led by the icon of the part that most needs a look and orange only where the reader acts, with "Details", which opens the full panel above the thread, and, for its person, an eye button that lists who else read it.
- **People:**
  - **Your messages:** bubble-me fill, right-aligned, up to 85% wide, 16px radius with the 8px tail top-right; Markdown inside follows the fill. No name line.
  - **Another person's:** a 12px name · time line, then a white bubble with a hairline and the bubble lift, tail top-left.
  - **A comment from the issue** (a trigger's conversation on a tracker): its author's round initials tile, a 12px line with their name, "commented on Jira SHOP-241", the time and a link "on the issue", and a white bubble with a hairline; a webhook's delivery reads "{system} sent about SHOP-241" beside a webhook icon.
  - **Under a message**, a 12px muted note says what became of it: "Waits for the next turn" (a clock), "Sending to Business analyst…", "Business analyst took it while working", "Answers the open questions", or "Not answered: its turn was cancelled".
- **The agent speaking** (a finished turn), in this order, 10px apart:
  - **Name line** (13px muted): a 20px agent-green initials tile at 8px radius, the agent's name in semibold ink, then "Turn 2 · a new message · my-macbook ·" and the time.
  - **Work**, folded into one row above what the agent said: a chevron, "Worked for **6m 12s**" (the duration in mono ink) and "· 14 tool calls · 2 messages · 1 thought" in muted. Failed and lost turns start open; the reader can open or fold any finished turn, remembered for the visit. Its events are read only when it is first opened. Folded, what the agent said along the way still shows under the row, in plain Markdown at 15px like the answer. Open work sits behind a 1px hairline on the left (6px margin, 18px padding), 12px between entries, drawn by the run events renderer (Chips): the checkout, groups, thinking and what it said, and errors the failure does not already say; the agent's last message is left out when it is the answer below.
  - **Groups:** every stretch of tool calls and log lines between two other entries is one 13px muted sentence: the kinds in order of first use ("Ran 5 commands, read 2 files, searched 1, edited 1"), then "· 1 log line" and "· 1 failed" in destructive. While a call runs, a green spinner leads and "· now" and its target follow in mono ink; closed after a failure, the failed call's target follows in destructive. Groups are closed by default, even live. Open, a group lists one row per call indented 22px: a 64px verb (Ran, Read, Searched, Edited, Fetched, Used), the target in mono with an ellipsis, and a note on the right (`+1 −1`, running, stopped, failed). Log lines sit between calls with their level as the verb (the 50 newest, earlier ones on demand). An opened call is one code block (code ground, 10px radius, mono 12px/1.65): the places it touched, its input, its output and its diff, split by hairlines.
  - **What it took while it worked:** the person's messages the turn took after a step (steering, [ADR-0060](docs/adr/0060-steering-a-running-turn.md)), as their bubbles, under its work.
  - **Answer:** what the agent said in the turn, in plain Markdown at 15px, up to 66ch; without a message, a succeeded turn's summary. A turn that ended on its questions has no answer: each question's decision card stands in its place ([ADR-0036](docs/adr/0036-questions-end-a-run-quietly.md)).
  - **Failure** (a turn that ended without a message): "Turn failed" or "Turn lost" in destructive semibold with a circle-x, or "Turn stopped" in warning with a timer for one stopped at its time limit, and the failure class in mono; then the class in words and the cause in ink.
  - **Decision cards** the turn raised, answerable in place.
  - **Status line** (12px muted), under the turn: "Completed" in success with a circle-check, "Blocked" in orange with an octagon, "Failed" in destructive with the failure class, "Stopped at its time limit" in warning, "Lost: the runner stopped reporting" in warning, or "Cancelled"; the duration in mono; "Pushed 2 commits to `agent/SHOP-241-1`" or "No changes"; the change requests as links (`acme/shop#12`, `group/project!7`); the SHA and the cost in mono, the tokens as its title (a settled cost as it is, the runtime's estimate marked "~", "subscription" for a turn on a subscription); View run.
- **The turn running now** closes the thread's turns: the agent's name line with its turn, then a 12px line: "Working" in green semibold with a live dot, the elapsed time in mono, the phase and the latest step ("· Running · Read src/app.ts"); three typing dots before the first event. "Show work" unfolds its work so far in place, fading in; folded, what the agent has said so far stands in its place. Queued, it says why it waits behind a clock, in the conversation's words where they help ("Waiting for your budget to reset on …" with "Your budget", "Business analyst is paused; its turns wait." with "Open the agent", "Connect the account"), else the server's queue reason. It is the only live stream on the page; when it ends the thread reads the conversation again.
- **Decision cards in place:** a decision raised outside a finished turn (by the turn running now) sits where it was raised, as its card.
- **Tinfirm's notes:** muted 13px Markdown, left-aligned beside an info icon, with the time under it; links read by their words. A note about a result's review that is still open folds to one line with "Show".
- **What the state means**, under the turns, as a 14px card: a failed turn ("The last turn failed: Usage limit reached." on a 5% destructive tint, its class in mono, the cause, the link that fixes it such as "Your budget" or "My settings", and Retry); a turn stopped at its time limit (white, "Continue"); a cancelled turn (Retry, and how many messages it left unanswered); a paused agent; an ended conversation (a lock, read only, its outputs and files kept). Running, queued and waiting on the reader say themselves: the live turn and the cards.
- **Connect cards** (`app-binding-card`), at the end of the thread: what holds the conversation for an account on a system, with what the reader can do (sign in, paste a token, pick a grant); orange only where the reader acts.
- **The composer** of an open conversation is docked at the sheet's foot (Composer). The Owner reading someone else's conversation gets a neutral banner in its place instead: whose it is, that only they write, decide and connect accounts in it, and that the read is recorded.
- **Pinned cards** (`app-pinned-cards`): while the reader is inside the thread and none of their open cards is in view, a "Needs you" strip sits above the composer: an orange heading with what waits ("Needs you · 1 open question · 1 account to connect"), the oldest card open and answerable, the others folded, and "Where it was raised", which scrolls to the card in the thread. Folded (as it starts on a phone or a short screen), it is one line on human soft at 40% with a faint orange hairline, with the oldest card's action ("Answer").
- **Following the end:** while the reader is at the thread's end, the page keeps that end, composer included, in view as the turn grows, with instant jumps, never an animation; a reader who scrolled up stays where they are.
- **Motion:** a live turn's new entries fade in over 160ms with a 4px rise; spinners turn once a second; the typing dots bounce. Reduced motion turns them off.

### Composer
A white sheet at 18px radius with a hairline, docked at the foot of an open conversation's thread: it sticks to the bottom of the screen while the thread is on it, under a 16px fade of the sheet, and has no shadow. The textarea ("Message Business analyst…", 15px from `md`, 16px below) grows with content (to 15rem); a 34px round green send button with a right-pointing arrow sits at the right (44px on a phone), and for its person an attach button before it adds a file. While questions are open, a box says that the message answers them ("This answers the open question"). A muted 12px line under it says what the message does, with "Markdown · Ctrl Enter to send" (⌘ on Apple's systems) from `sm`: "Business analyst gets this as the next turn.", "Business analyst is at work: it takes your message after its current step, or in the next turn.", "Business analyst gets this as a message; its open questions stay open." or "Business analyst gets this as the answer to its open questions and goes on." On a trigger's issue it takes only answers, which are posted on the issue too; for a conversation a chat holds, a note says where to write instead. Focus-within adds the ring.

There is no composer outside a conversation. A member starts one with "New conversation" on the conversations page or "Talk to" on an agent, on the new conversation page: one large message box (18px, the agent picked in its header under "To", what it is about and the repositories in its foot, and Start), with who acts and who pays right under it, before the first message goes.

## Do's and Don'ts

### Do:
- **Do** put content on white sheets (14px radius, 1px hairline) over the sage ground; a thread is one 20px sheet.
- **Do** use agent green for the agent, the primary action and the live state, and nothing decorative.
- **Do** reserve orange for "needs a human", and only for the reader who must act: an open decision they decide, a conversation waiting on them, a Connect card they act on.
- **Do** make statuses, phases, chips, choices and buttons full pills.
- **Do** give speech 16px bubbles with an 8px tail corner at the top, on the speaker's side.
- **Do** draw run events with the one renderer, so a turn in its thread and the run page read the same.
- **Do** set code, paths, conversation and subject keys, SHAs, counts, costs and timers in DM Mono with tabular numbers.
- **Do** use Lucide line icons at 16px (12-14px inside small pills).
- **Do** give every control that acts a 44px target on a phone.
- **Do** honour `prefers-reduced-motion`: the global rule collapses animations and hides the live ping.

### Don't:
- **Don't** fall into the category default: a dark zinc sidebar dashboard of dense tables and status badges.
- **Don't** introduce a third accent or use orange for decoration, branding or "you" chrome.
- **Don't** draw dark or high-contrast borders around sheets; the hairline tone is the only border colour.
- **Don't** fill destructive buttons with solid red; use the 10% tint.
- **Don't** set UI labels or headings in DM Mono, and don't add uppercase tracked eyebrow labels above headings.
- **Don't** use raw Tailwind palette colours or hard-coded hex in components; every colour comes from a token in `web/src/styles.css`.
- **Don't** give sheets a shadow at rest.
