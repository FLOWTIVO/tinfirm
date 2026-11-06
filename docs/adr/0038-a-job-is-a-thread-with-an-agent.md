# ADR-0038: A job is a thread with an agent

- Status: Superseded by [ADR-0058](0058-authority-from-the-audience.md) on 2026-10-12 (was Accepted)
- Date: 2026-10-05
- Decided by: the owner, 2026-10-05
- Sources: the owner's decision of 2026-10-05, recorded in [archive/v2/PLATFORM.md](../archive/v2/PLATFORM.md) §4.6, §4.8 and §5.1; CONVENTIONS §14.4 and §14.7; [the restart record](../reviews/2026-10-05-restart.md), packages 4 to 7
- Changes: [ADR-0037](0037-restart-on-the-platform-design.md), which made each trigger firing a job of one agent on one work item; the first draft of PLATFORM.md, in which a job was one agent doing one request
- Amended by: [ADR-0049](0049-agent-kinds-groups-and-personal-accounts.md), under which an interactive agent's thread is between one person and the agent

## Context

- The first draft of PLATFORM.md (2026-10-05) defined a **job** as one agent doing one request on one work item: the unit of work, of status and of result. Every trigger firing, every mention and every message started a job of its own, with origins `TRIGGER`, `MENTION`, `CONVERSATION`, `MANUAL` and `RETRY`. A person's comment continued the agent's job only while that job was `WAITING` on a question; otherwise it started a new `MENTION` job, and a retry created a new job.
- The owner's examples showed how people actually talk to a specialist about an issue:
  - **A reply in a comment thread continues the job.** On GitLab, and in GitHub pull request review threads, people answer the agent's comment by replying in its discussion, as they would to a colleague, and expect the agent to go on with what it knows.
  - **Flat sources need a mention.** Jira and GitHub issue comments have no threads: a comment is addressed to nobody in particular, so it continues an agent's work only when it mentions the agent (or answers its open question).
  - **Mentions work in any discussion where the source has threads**: in a new discussion, in one between people, and in one that already holds another agent's thread.
- Under one job per request, an issue would collect a list of short jobs instead of a conversation; each would start a fresh session without the context of the last; and a reply in a discussion would have no natural target. The analyst who gets a turn on every edit of an issue, and the developer asked to change what it did, need the same context throughout.

## Decision

**A job is a thread between people and one agent on one work item.** It is created once, by a trigger, a mention or a person in Tinfirm; it stays open as long as the work item is open; every message addressed to the agent is a new **turn**: a run that resumes the agent's session and gets the conversation so far.

Which thread a message continues:

- **Where the source has threads** (GitLab issue and merge request discussions, GitHub pull request review threads), the discussion is the thread, and a job is bound to one discussion. A comment that mentions agents continues each mentioned agent's job bound to that discussion, or starts one there, in any discussion. A comment that mentions nobody continues the one job bound to it (with two agents bound there, a mention is needed). A reply in the discussion of an ended job reopens it. An agent may hold several threads on one work item, one per discussion.
- **Where the source is flat** (Jira, GitHub issue comments), an agent has at most one open thread per work item. An answer to an open question continues that question's thread; a mention continues the agent's open thread or starts one; a plain comment continues nothing, and the agents read it with their next turn.
- **In Tinfirm**, a person replies inside a thread, which names the job; a conversation has one thread, and every message there is its next turn.
- **Triggers** add turns to their agent's thread: `ONCE` starts the thread on a work item once, `EVERY_TIME` adds a turn for every further event, creating the thread when none is open.

Turns of one work item run one at a time, queued across its threads, because they share its branch. A thread ends when its work item closes, when a member ends it, or when its agent is deleted; a closed thread takes no turn.

## Consequences

- The context carries across turns: each turn resumes the agent's session on the work item and gets its own request (the trigger's instruction, the comments, the answers, a returned review's note). A turn whose runner host cannot load the previous session (another host, or a session home cleaned up) starts a new one with the full prompt: the work item, the turn's request and the newest comments.
- Status moves from the job to the turn: a thread has a state (`OPEN`, `CLOSED`) and the status of its latest turn (`QUEUED`, `RUNNING`, `WAITING`, `REVIEW`, `SUCCEEDED`, `FAILED`, `CANCELLED`); the waiting-on resolver reads the threads. Runs are turns, with a number and a reason (`FIRST`, `EVENT`, `COMMENT`, `DECIDED`, `REVIEW_RETURNED`, `RETRY`); a retry runs the last turn again in the same thread, and a cancel leaves the thread open.
- A trigger's on-success and on-failure actions run after every turn of its thread that succeeded or failed, not once per job.
- The write-back follows the threads: on GitLab an agent's comment goes into its thread's discussion, and a thread started by a trigger or in Tinfirm starts a discussion with its first comment and is bound to it; on GitHub replies go into the pull request review thread.
- On flat sources people must mention an agent to give it work; a plain comment wakes nobody. The composer in Tinfirm says whom a comment reaches before it is sent.
- New conflicts: `409 THREAD_EXISTS` for a second unbound thread of an agent on a work item, `409 JOB_CLOSED` for a turn of an ended thread.
- The web app shows a work item as its threads (DESIGN.md, The Thread), each with its turns, its folded work, its decisions in place and a composer that replies in it.

## Alternatives considered

- **One job per request** (the first draft). Rejected by the owner: it loses the context between requests about the same issue, turns an issue into a list of short jobs, and gives a reply in a discussion no natural target.
- **One thread per agent and work item everywhere**, ignoring the source's discussions. Rejected for threaded sources: two discussions with the same agent are two topics, and people reply where they talk. Kept for flat sources, where it is the only unambiguous target.
- **A plain comment continues a thread on flat sources** (the last active one, or every open one). Rejected: several people and agents share an issue, and guessing would often be wrong; a mention is explicit and cheap.
- **Mentions only, even on threaded sources.** Rejected: on GitLab and in GitHub review threads, replying in the discussion is how people already talk, and demanding a mention there would make the agent the only participant that must be named in every reply.
