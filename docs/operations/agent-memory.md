# Agent memory

The public guide is [Agent memory](../../docs-site/content/user-guide/memory.md) (https://docs.tinfirm.com/user-guide/memory/), for members and the authors of triggers; the admins' choices are on [Company settings](../../docs-site/content/admin-guide/settings.md) (https://docs.tinfirm.com/admin-guide/settings/) and [Model access](../../docs-site/content/admin-guide/model-access.md) (https://docs.tinfirm.com/admin-guide/model-access/), for Owners and Admins. This page keeps what the people who build Tinfirm need beyond them: the design and its rules, the API, the token budgets of what the agent sees and why, and the audit and internals of the credential scan, compaction and retention.

The decision is [ADR-0063](../adr/0063-agent-memory.md); the binding rules are [CONVENTIONS §15.18](../CONVENTIONS.md#1518-agent-memory).

## 1. Turn it on

| Who | Through the API | Default |
|---|---|---|
| Whoever edits the agent (its owner, OWNER, ADMIN) | `PATCH /agents/{agentId}` with `memoryAllowed` | Allowed |
| Each person, for their own conversations with the agent | `PUT /agents/{agentId}/settings/me` with `memory: true` | Off |
| A trigger's author, for the trigger's conversations | `PUT /triggers/{triggerId}/settings` with `memory: true` | Off |

## 2. What the agent sees, and what it costs

What reaches the agent, and its budget:

| When | What | At most |
|---|---|---|
| A conversation's first turn (or a turn that cannot resume its session) | How many notes there are; the general notes; the notes about the repositories in the workspace | About 100 tokens for the general notes, 150 for each of 3 repositories |
| The first call of a system's tools in a turn | The notes about that system, with the tool's result | About 150 tokens |
| When the agent calls `recall` | The 5 notes that best match its words | About 400 tokens |

A turn that resumes its session gets no notes again, so memory does not invalidate the prompt cache ([prompt-cache.md](prompt-cache.md)). An agent without memory gets no memory section and no memory tools.

## 3. Read and delete your notes

- A person's own: `GET /agents/{agentId}/settings/me/memory`; delete one note with `DELETE …/memory/{noteNumber}`, or all of them with `DELETE …/memory`.
- A trigger's: `GET /triggers/{triggerId}/memory`, `DELETE …/memory/{noteNumber}` and `DELETE …/memory`.
- From an AI client, `delete_memory_note` needs the `tinfirm:write` scope.

## 4. A trigger's notes are reviewed

A proposal is accepted with `POST /triggers/{triggerId}/memory/{noteNumber}/accept` and rejected with `DELETE …/memory/{noteNumber}`. While proposals wait, the inbox of the trigger's author and team holds a `TRIGGER` item with reason `MEMORY_REVIEW`.

## 5. For admins

What admins do is on the public pages: [Tidying](../../docs-site/content/user-guide/memory.md#tidying) and [How long notes are kept](../../docs-site/content/user-guide/memory.md#how-long-notes-are-kept) in Agent memory, the memory's model in [Company settings](../../docs-site/content/admin-guide/settings.md), and the company key's budget and models in [Model access](../../docs-site/content/admin-guide/model-access.md#the-company-key). Beyond them:

- **Secrets in notes.** The vault scans every note before it is stored. A refused note is audited as `memory.refused`, naming the secrets and formats it found, never the note itself.
- **Compaction and the company key.** Every 30 days, a memory that changed is compacted (`MemoryCompaction`). Duplicates are removed without a model. Then a small model merges related notes and drops notes a newer one contradicts, paid by the company's own key ([ADR-0065](../adr/0065-company-model-key.md)), never by a person or a trigger: `PATCH /model-access/company-key` sets its budget and models, and `PUT /memory-settings` the model. Without a model chosen, or with one the company key no longer allows, only the duplicates go; a key that does not answer (its budget spent, the gateway down) leaves the memory due for the next day. The model's answer is applied only when it passes every check, and each compaction is audited as `memory.compacted`, with its token counts; its requests show in LiteLLM's spend logs tagged `tinfirm-task:memory-compaction`.
- **Retention.** The retention setting is `memoryDays` (730 by default, empty to keep notes; `PUT /retention`), applied by the daily sweep. A note counts as used when it is saved, shown to the agent or recalled; compaction never extends a note's life.
