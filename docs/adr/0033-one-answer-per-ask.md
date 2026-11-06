# ADR-0033: Answering an agent's questions: one answer per form, and comments answer only on request

- Status: Superseded by [ADR-0037](0037-restart-on-the-platform-design.md) (was Accepted)
- Date: 2026-10-03
- Sources: the owner's reports of 2026-10-03 (answers to an agent's several questions); CONVENTIONS §14.3, §15.5, §17.5, §18; [ADR-0028](0028-asks-and-waiting-on.md)
- Changes: ADR-0028's rule that any comment of a person answers every open ask of the task. The answer endpoint answers the one ask it names, and a member's comment answers the open asks only when the member says so. Replies on the linked issue keep ADR-0028's rule.
- Amended by: [ADR-0034](0034-mention-an-agent.md), under which an answer through the answer endpoint wakes the agent that asked once none of its asks is open, and a reply on the linked issue that mentions agents answers only their asks
- Amended by: [ADR-0036](0036-questions-end-a-run-quietly.md), under which each open question is a card in the conversation instead of a section of answer forms under it

## Context

- An agent can ask several questions in one run: several `ask_human` calls, or a `BLOCKED` result with several open questions (CONVENTIONS §18.2, §18.4). The inbox and the task page show one answer form per question.
- Under ADR-0028 any comment of a person answers every `OPEN` ask of the task, and the answer endpoint posts such a comment. When a member answered the first of several questions through its form, the other questions disappeared from the inbox and the task page, each got the same text as its answer, and the agent was woken with one real answer and the rest wrong.
- The same rule made every plain comment on the task an answer. A comment that has nothing to do with the questions, such as "the deadline moved", marked them all answered with its text and woke the agent with that text as every answer. It is the same error the answer form had, made through the comment box, and on 2026-10-03 the owner decided that a comment answers only when the member says so.
- A form belongs to one question, so its answer can name that question. A plain comment on the task, and a reply on a Jira or GitHub issue, cannot: neither Tinfirm nor Jira Data Center comments have replies to a comment. A member writing in Tinfirm can still say whether a comment is meant as the answer. A person on the issue cannot, and the agent's question there says to reply to it.

## Decision

**An answer through the answer endpoint answers exactly the ask it names, and a member's comment answers no ask unless the member says so.** The `EXTERNAL` comments a poll imports keep answering every `OPEN` ask of the task at once: the issue has no answer form, a reply cannot name one question, and the agent's question on the issue asks for a reply.

- `POST .../tasks/{taskId}/asks/{askId}/answer` answers that ask only, through `AskAnswers.answerAsk(taskId, askId, commentId, answer, byUserId)`, which locks the ask, checks that it is `OPEN`, answers it, audits `ask.answered`, recomputes the waiting-on state and says whether an `OPEN` ask of the task remains. `answerAsks` stays the path of comments that answer all and of imported comments.
- The member's `USER` comment starts with the question as a Markdown quote, cut to its first line and 200 characters (`> {question}`), and a blank line, then `**{option}**`, a blank line and the text, or either alone. The thread and the linked issue show which question the answer belongs to. The ask gets this comment as `answerCommentId`, the comment without the quote as its `answer`, the member and the time. The rules for 400 and `409 ASK_DECIDED` stay as in ADR-0028.
- **The wake of an answer.** The agent is woken with `ASK` only when the answer leaves no `OPEN` ask on the task, with the same coalescing as before: once, or the follow-up flag during an active run (CONVENTIONS §14.3). While other asks stay `OPEN`, nothing is woken, not even with `COMMENT`, and the task keeps waiting on a person with reason `ASK`; the resolver needs no change.
- **A comment.** `POST .../tasks/{taskId}/comments` takes an optional `answersOpenAsks` (boolean, default false) in `CommentCreateRequest`, and `UserComments.post(task, userId, body, answersOpenAsks)` follows it:
  - **false:** the `USER` comment answers nothing. While the task has an `OPEN` ask, nothing is woken, not even with the follow-up flag: every new comment is in the next resume prompt (CONVENTIONS §15.5), so the agent reads it together with the answers at its next wake. The comment stays in Tinfirm, as plain comments always did. Without an `OPEN` ask it wakes the agent with `COMMENT`, as before.
  - **true:** the comment answers every `OPEN` ask of the task through `answerAsks`, goes to a linked issue, and wakes the agent once with `ASK`, as under ADR-0028. Without an `OPEN` ask it is a comment that wakes with `COMMENT`, and no error.
- A member's comment that answered an ask, either way, is published as `CommentPosted` and goes to a linked issue with the member's name (CONVENTIONS §17.5), one comment per answer.
- **The resume prompt** lists every ask answered since the previous run began in the order they were asked, each with its own answer under `## Answers`. The `**A:**` line is the ask's `answer`, so the quoted question is not repeated there. The answering comments are among the new comments, with their quotes, and so are the plain comments written while the agent waited.
- The audit does not change: each answered ask is audited as `ask.answered` (CONVENTIONS §8). A plain comment written while asks are open answers none, so it is audited as any other comment is: comments themselves are not audited.
- `Ask` gains nothing in the API. The inbox re-reads the asks after an answer, so the task's other open questions stay listed; the task page counts the open and answered asks of the runs that wait for answers ("1 of 3 answered"). While questions are open, its composer shows a box "This comment answers the N open questions", off by default and off again after each comment or when the number of open questions changes, and says what the comment does: unticked, that the agent reads it together with the answers; ticked, that it answers every open question at once and wakes the agent. The run page's note on open questions names the answer forms and says that a comment answers them all only when it is marked as their answer.

## Consequences

- An agent that asked several questions gets every answer, each with its own text, in one wake when the last one arrives. It never resumes with a question still open.
- An agent that waits on answers is never woken by a plain comment. A comment that has nothing to do with the questions no longer answers them, and the questions stay in the inbox until someone answers them.
- A member who wants to answer everything in one go ticks the box under the comment: it answers all open questions at once and wakes the agent, as plain comments did before. A member who wants to redirect a waiting agent rather than answer its questions ticks the box, so the comment becomes the answer to every question and wakes the agent, or answers the questions; a plain comment reaches the agent only with the answers.
- A reply on the linked issue still answers all open questions of the task, since the issue cannot name one and has no box. In Jira or GitHub a person answers several questions in one reply.
- The thread and the issue show the quoted question before each answer through a form, so a reader sees which answer belongs to which question. In Jira the quote is a `bq.` line (CONVENTIONS §17.6).
- A task whose questions are answered one by one waits on a person until the last answer; the waiting sentence and the inbox show what is left. An answer that is never given keeps the agent asleep, as an unanswered single question did before, also when people comment on the task.
- An ask's `answer` and its comment's body now differ for answers through the form: the comment has the quote, the ask does not. Readers that need the comment use `answerCommentId`.
- A client of the API that posts a comment without `answersOpenAsks` answers nothing. The web app sends the flag when the box is ticked.

## Alternatives considered

- **Wake the agent after each answer.** Rejected: the agent would resume with questions still open, could act on a partial picture or ask again, and each answer would cost a run.
- **Wake the agent with `COMMENT` for a plain comment and leave the questions open.** Rejected for the same reason as waking after each answer: the agent would resume with its questions still open, and each comment would cost a run. The agent reads the comment at its next wake anyway.
- **A comment answers every open question by default, as before, with a box to say it does not.** Rejected by the owner on 2026-10-03: a comment that was not meant as an answer must not become one, and a box that is easy to miss would keep the error.
- **A per-ask reply in Jira and GitHub.** Deferred: a reply on the issue's own comment thread, where a tracker offers one, could later name the ask. Until then a reply on the issue answers all open questions of the task.
- **Keep one answer for every open ask (ADR-0028).** Rejected by the owner's report: one form's answer became the answer of questions it was not meant for, and those questions disappeared from the inbox.
