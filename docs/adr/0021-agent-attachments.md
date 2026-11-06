# ADR-0021: Agents attach files to their task

- Status: Accepted (built in package 3; under runner protocol 2 the tool is `save_artifact`, beside `/output`, [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md))
- Date: 2026-10-01
- Sources: CONVENTIONS §8, §14, §15.6; [ADR-0014](0014-task-attachments-and-upload-scan.md), [ADR-0019](0019-agent-created-tasks.md)

## Context

- Agents produce files that members want: a report, a screenshot, an export, a log, a diagram. Today such a file reaches a member only if the agent commits it to a repository, which puts unrelated files into the change, or pastes it into its summary, which only works for short text.
- Members already attach files to a task, and the agent reads them in `/attachments` (ADR-0014). The upload scan, the limits and the storage exist.
- ADR-0014 says files an agent produces are artifacts, not attachments. Artifacts and object storage do not exist yet; members need to see the agent's files on the task and in its conversation now.
- The platform MCP tools are served by the runner host (CONVENTIONS §15.6). The host reads the sandbox's workspace and session home on its own file system, and the agent controls everything in them, symlinks included.
- A file can be up to 25 MB. The runner WebSocket carries small JSON frames (`tinfirm.runner.max-message-bytes`), and the host already downloads attachments over HTTPS with its host token.

## Decision

**The platform tool `upload_attachment`**
- Input: `{"path": string, "fileName"?: string, "note"?: string}`. `path` is absolute in the sandbox or relative to `/workspace`, and must lie under `/workspace` or `/home/agent`. `fileName` is the name members see; without it, the file's own name. `note` is Markdown for the conversation.
- The runner host:
  - refuses `..`, paths elsewhere (a file in `/attachments` is already an attachment), and a `fileName` that is not a plain file name (the rule of ADR-0014);
  - opens the file in the run's workspace or session home with `O_NOFOLLOW | O_NONBLOCK`, after checking that the path is not a device, FIFO or socket, then resolves the path again: it must lie beneath the directory and be the same file (device and inode). A symlink that leads out cannot pass, because the sandbox cannot make a file with the inode of a host file it cannot see;
  - refuses anything but a regular file and anything over 100 MiB;
  - refuses a file that contains a credential or a masked value of the host in any encoding (the redactor's patterns, on the raw bytes), so it never leaves the host;
  - redacts the file name and the note, and cuts the note to 2000 characters;
  - sends `multipart/form-data` (parts `file`, with the name also as RFC 5987 `filename*`, and `note`) to `POST /api/runner/v1/runs/{runId}/attachments?epoch={epoch}` with its host token, and waits up to 120 s.
- `STORED` is a normal result and `REJECTED` an error result; both carry the server's `message`. `404` (the run is not active), `413` (larger than the server accepts) and a failed request are error results written by the host. A failed request is not retried.

**What the server does.** The endpoint lives next to the attachment download, under `/api/runner/v1/**` (no session, no CSRF), and is not part of `api/openapi.yaml`.
1. The host token is valid; else `401`.
2. The run is assigned to this host and is `RUNNING` with this epoch; else `404`. The run's row is locked.
3. The work module checks, in order, and the first failure is the `REJECTED` answer with its `code`; nothing is stored:
   - the run's agent is not deleted (`RUN_NOT_ACTIVE`);
   - the file name is valid as for a member's upload, and the note has at most 2000 characters (`INVALID_REQUEST`);
   - the size, the name and the per-task limits of ADR-0014 (`ATTACHMENT_TOO_LARGE`, `DUPLICATE_ATTACHMENT_NAME`, `ATTACHMENT_LIMIT_REACHED`);
   - the scan of ADR-0014: a vault value **or** a known credential format is `ATTACHMENT_CONTAINS_CREDENTIAL`, because nobody can acknowledge a format hit for an agent; content that cannot be scanned is `ATTACHMENT_NOT_SCANNABLE`. The message names no secret.
4. It stores the file as an attachment of the run's task, with `uploaded_by` null and the agent and the run recorded.
5. It audits `attachment.uploaded` with actor `AGENT` and the details of ADR-0014 plus `runId`.
6. It posts a `SYSTEM` comment on the task, with the run: ``{agent} attached the file `{fileName}`.``, followed by the note, and links the attachment (`task_comment.attachment_id`). So the file shows in the conversation as well as in the files panel. The comment wakes nobody.
7. The answer is `200` with `{"status": "STORED", "attachmentId", "fileName", "message"}`, the message written for the agent: ``Attached `{fileName}` to the task {KEY}. Members see it in the task's files and in the conversation, and later runs of the task find it in `/attachments`.``

**API.** `TaskAttachment.uploadedByAgentId` and `TaskAttachment.runId` are new; `uploadedByDisplay` is the agent's name for an agent's file. `Comment.attachment` (`id`, `fileName`, `contentType`, `sizeBytes`) is the linked file, null when there is none or it was deleted.

**Web.** A `SYSTEM` comment with an attachment shows a download link to the file. The files panel reads its list again when the conversation links a new file.

**Prompt.** Every prompt gets one line in `# How to work`, after the line about `create_task`: to give the members a file, call `upload_attachment` with its path under `/workspace` or `/home/agent`; do not commit a file only to share it.

### Data model: migration `V021__agent_attachments.sql`

| Column | Type | Null | Meaning |
|---|---|---|---|
| `task_attachment.uploaded_by_agent_id` | `uuid` | yes | The agent that attached the file |
| `task_attachment.run_id` | `uuid` | yes | The run in which it called the tool |
| `task_comment.attachment_id` | `uuid` | yes | The file the comment announces |

- `task_attachment_id_company_uq UNIQUE (id, company_id)`, so comments can reference attachments of their own company.
- `task_attachment_agent_fk`, `task_attachment_run_fk`: composite foreign keys with `company_id`, as in ADR-0019.
- `task_attachment_agent_ck`: an agent's file has both columns and no `uploaded_by`; a member's file neither.
- `task_comment_attachment_fk FOREIGN KEY (attachment_id, company_id) REFERENCES task_attachment (id, company_id) ON DELETE SET NULL (attachment_id)`: deleting the file keeps the comment.

Existing rows get nulls. Both tables keep their policies and grants.

## Consequences

- Agents hand members files without putting them in a repository or a summary, and later runs of the task read them like members' files.
- A file is scanned twice: by the runner host against the host's credentials and the run's masked values, which the server never holds, and by the server against the vault and the known formats. Each covers what only it knows; neither changes the other's rules.
- An agent cannot store a file that merely looks like it holds a credential. A member who needs it can download the agent's output another way and upload it with an acknowledgement.
- Agent files count against the task's limits (50 files, 250 MB by default), so a run can fill them. A member deletes files they do not need. There is no per-run limit.
- A file name the task already has is refused: an agent names a new version differently. Replacing a file is a possible follow-up.
- The content still lives in PostgreSQL (ADR-0014).
- The runner host and the server must be deployed together: an older server answers the upload with `404` or `405`, which the tool reports as an error; an older runner host offers no `upload_attachment`.
- The platform MCP endpoint is still guarded only by the secret in its URL (CONVENTIONS §16): a process that learns a run's URL can now also attach files of that run's workspace to its task.
- CONVENTIONS §8 (audit details), §14 and §15.6 (the tool list) should name the tool.

## Alternatives considered

- **Send the file in a WebSocket frame** (`run.attachment_upload`, base64). Rejected: frames are limited to small JSON messages, a 25 MB file would block the run's control messages, and the HTTPS path with the host token already exists for downloads.
- **Artifacts in object storage, separate from attachments** (ADR-0014). Not chosen now: neither exists, and members need to see the files on the task. When artifacts exist, this tool can store there without changing its input.
- **Let the agent post a comment with the file.** Not chosen: an `AGENT` comment of a run is that run's answer in the conversation. A `SYSTEM` comment with a link announces the file without taking that place.
- **Resolve paths with `openat` per component, or `openat2(RESOLVE_BENEATH)`.** Not chosen: it needs `unsafe` code, which the runner host denies, and `openat2` is Linux-only. Resolving again and comparing the inode gives the same guarantee for files the sandbox can create.
- **Commit the file to the task branch.** The status quo; it mixes deliverables into code changes.
