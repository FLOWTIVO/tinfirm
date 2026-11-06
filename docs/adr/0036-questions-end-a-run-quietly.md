# ADR-0036: A run that ends on its questions posts no summary

- Status: Accepted
- Date: 2026-10-04
- Decided by: the owner, 2026-10-04
- Sources: the owner's report of 2026-10-04 (each question shown three times on the task page and twice on the issue); CONVENTIONS §14.4, §14.7, §15.5, §15.6, §17.5, §18.2, §18.4, §18.5, §18.6; [ADR-0015](0015-multi-repository-tasks.md), [ADR-0024](0024-jira-tracker-connector.md), [ADR-0028](0028-asks-and-waiting-on.md), [ADR-0030](0030-github-issues-tracker.md), [ADR-0033](0033-one-answer-per-ask.md)
- Changes: ADR-0028, under which every SUCCEEDED run gets the end-of-run AGENT comment and an agent that needs a person ends its turn with a short summary: a run without a result that ends while a question or a repository request of its own waits for a person posts no comment, and the agent asks every question it needs and stops without a summary. ADR-0033's task page, which listed the open questions with their answer forms in a section under the conversation: each question is a card in the conversation, where its comment is, and the waiting sentence and the run page's note point there.

## Context

- Each question the agent asked appeared three times on the task page: the question comment that `ask_human` posted, the end-of-run summary in which the agent restated the question and its options, and the "Open questions" section under the conversation with the answer form.
- On the linked issue the first two were written back as two comments (CONVENTIONS §17.5): the question, which says to reply to it, and the agent's summary with the same question in other words. A person on the issue sees the question twice and cannot tell which comment to answer.
- The prompt and the tool's answer asked for the summary: "finish your turn at once with a short summary that says what you are waiting for" (CONVENTIONS §15.5, §18.2). The summary stood for the question in runs without the platform tools; with them, it repeated what the question comment already said.
- Claude Code's own question tool holds the turn open until the person answers. Tinfirm ends the run instead ([ADR-0028](0028-asks-and-waiting-on.md)): a run holds a sandbox, a slot of the agent, its credential and its runner host, and a subscription session, a person may answer hours later, and a restart would lose the waiting run. Since [ADR-0033](0033-one-answer-per-ask.md), the agent is woken once, with every answer, when its last open question is answered.
- On 2026-10-04 the owner chose to end the run without a summary, over a bounded wait of the tool for a quick answer, which stays an option for later.

## Decision

**A run that ends on its own open questions posts no summary. The questions are its result.** The agent asks every question it needs, one `ask_human` call each, and stops; Tinfirm wakes it with every answer at once, as ADR-0033 already does. The same holds for a run that ends on its own pending repository request. The thread shows each question once, as a card with its options and the reply box in place.

**Server.**
- A `SUCCEEDED` run without a `finish_run` result that, at the moment it ended, had an `OPEN` ask or a `PENDING` repository request of its own gets no AGENT comment: neither when it ends nor after its pull requests open. `RunCompletion.endsOnItsQuestions` makes the decision for both places. The asks and the requests are the work module's, so the run module asks through the `TaskBoard` port, `leftOpenQuestions(runId, endedAt)`: an ask answered or cancelled after `endedAt`, or a request decided after it, was open at that moment, so an answer that arrives while the pull requests are opened does not bring the comment back.
- Everything else of the ending stays: the git results, the pull requests (opened, recorded on the task and the run, their state watched), the follow-up and pending wakes, the status rules of CONVENTIONS §14.4, the waiting-on state, the audit, and `run.summary`, which keeps the agent's last message for the run page.
- A run with a result keeps its comment, also `BLOCKED` with its open questions, which become asks of that one comment (ADR-0028). A run that asked and then called `finish_run` too keeps the comment: the result wins.
- A run whose questions were all answered before it ended, for example while it still worked, had nothing open when it ended: it is an ordinary run, and gets its comment.
- **The prompt** (CONVENTIONS §15.5): "When you need a decision or information that only a person has, call the Tinfirm tool `ask_human`, one clear question per call, with `options` when the person should pick one. Ask every question you need, then stop: do not write a summary, your questions are on the task, and Tinfirm wakes you with all the answers at once. Never ask for credentials. If the Tinfirm tools are not available, end your summary with the questions instead." The `request_repository` line has the same shape: after a pending request, stop without a summary; Tinfirm wakes the agent with the decision. The `finish_run` line and the resume prompt do not change.
- **The ack messages** of a `PENDING` ask and of a `PENDING` repository request say that the question (the request) is posted, that the agent may ask more, and that it stops without a summary when it has nothing else to do; Tinfirm wakes it with the answers (the decision).

**Runner host.** The MCP `instructions` and the descriptions of `ask_human` and `request_repository` say what the prompt says: ask every question you need, one per call, then stop without a summary; Tinfirm wakes you with the answers. The `finish_run` description does not change. When a question could not be posted (Tinfirm did not answer in time, is not reachable, or the run is ending), the tool still tells the agent to write it into its summary, since nothing is on the task then. Frames, fields and codes do not change; `protocol/runner-v1.schema.json` is untouched.

**Web.**
- The conversation renders a question's comment as its card, in the comment's place and time: the question, its options and the answer form while it is open; once answered, the question and its options with "Answered" and no form (a cancelled one says "Cancelled"), and the answer is the comment that follows. The open questions of a `BLOCKED` result are cards under the comment that lists them. The "Open questions" section under the conversation is gone. The inbox keeps its cards.
- A succeeded run without an answer comment that asked or requested a repository reads "Asked 1 question", "Asked 3 questions" or "Requested a repository" where its answer would be; its last message stays in its folded work. Its pushed commits and its pull request links stay on its status line.
- The waiting sentence says "{agent} waits for your answer in the conversation", "{agent} waits for your answers in the conversation (1 of 3 answered)", and for `BLOCKED` "{agent} is blocked; its open questions are in the conversation". The run page's note on open questions says they are questions in the task's conversation.

## Consequences

- The thread and the issue show a question once. On the issue, the question is all the run writes; the answer, given there or in Tinfirm, follows it.
- An agent asks several questions in one run and gets every answer in one wake (ADR-0033), instead of asking one and ending with a summary.
- A run that did work and then asked loses its prose on the task: what it changed is no longer told in a comment. Its commits, its pull requests and `run.summary` stay, on the run's status line, on the task, on the code host and on the run page. An agent that wants to report work before a question calls `finish_run`, whose result keeps the comment. A pull request opened after such a run starts its body with "{agent name} asked a question on the task before finishing; this pull request holds the work so far. The conversation continues on the task.", then the agent's last message when it left one, instead of that message alone or the "(The agent finished without a summary.)" fallback; the rest of the body stays.
- Runner hosts with the old tool texts still tell the agent to write a summary. The server drops it the same way, so the thread and the issue stay right with old hosts; the text is kept on the run.
- A question the agent could not post is still written into its summary. Nothing waits on the task then, so the run posts that summary as before.
- An answered question stays in the conversation as a card without a form, so the answer below it has its question above it, also when the answer came from the issue and quotes nothing.

## Alternatives considered

- **A bounded wait of the tool for a quick answer.** `ask_human` would hold the turn for a few minutes, as Claude Code's own tool does, and the run would end only when nobody answered in time. Deferred by the owner on 2026-10-04: the sandbox, the slots and the subscription session stay busy while people decide. It remains an option for later, and the rule already treats a question answered within the run as answered.
- **Keep the summary and collapse it in the web.** Rejected: the summary is still written back, so the issue still gets both comments.
- **Suppress only the web display of the summary.** Rejected for the same reason: the issue still gets the duplicate, and the task's comments in the API would differ from what the page shows.
