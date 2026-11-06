# ADR-0014: Task attachments with one credential scan at upload

- Status: Accepted (built for a conversation's files in package 3; not built: the rescan of stored files when a secret changes, the follow-up)
- Date: 2026-09-26
- Sources: CONVENTIONS §2, §6, §7, §8, §9, §10, §14, §15; [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0010](0010-hash-chained-audit-log.md), [ADR-0011](0011-envelope-encrypted-vault.md)
- Amended by: [ADR-0047](0047-attachment-preview.md), under which the web app shows Markdown, text and code, CSV, diffs and images without interpreting anything as HTML or script, instead of never rendering an attachment inline
- Amended by: [ADR-0084](0084-email-attachments-and-gmail-drafts.md), under which a person's client holds a file for an hour before the conversation it starts, checked and scanned as an upload, and the start moves it in before the first turn

## Context

- Members upload files to a task, and the agent that works on the task reads them.
- Tasks, runs and runner hosts exist since v0 (CONVENTIONS §14, §15). Object storage does not exist yet. The HTTP paths are in `api/openapi.yaml`, and `run.assign.attachments` is in `protocol/runner-v1.schema.json`.
- A file can contain a credential. An agent reads it, so a credential in a file can end up in the model provider's logs, in agent output, or outside the sandbox.
- The server vault holds API keys, integration secrets, the GitHub App key and the webhook secret ([ADR-0011](0011-envelope-encrypted-vault.md)). Subscription tokens stay on the owner's runner host and never reach the server ([ADR-0007](0007-credentials-stay-in-deployment.md)).
- The sandbox already holds the subscription token for its run, and the runner host scans everything that leaves the sandbox.
- The audit chain cannot erase entries, so personal data stays out of hashed fields (CONVENTIONS §8).

## Decision

### Scan once, in the server, at upload

- The server reads the upload (at most 25 MiB) and scans it in memory. Only a file that passes the scan is stored, so nothing unscanned is ever visible on the task or to a run.
- **Vault values.** A new internal `VaultService.scan(companyId, content)` decrypts the company's secrets and the instance-level secrets inside the vault module. It matches each value in plain, base64 (all three alignments), hex and URL-encoded form, in one pass over the content (Aho-Corasick). Plain values never leave the vault module. Values shorter than 8 characters are skipped. The scan writes no `secret.accessed` event per secret; the attachment's audit event records the scan.
- **Known formats.** The same pass matches known credential formats: GitHub tokens, AWS keys, Anthropic API and OAuth tokens (`sk-ant-api…`, `sk-ant-oat…`), OpenAI keys, Codex access tokens and other JWTs, and PEM private keys. This covers subscription tokens, which the server never holds.
- **Archives.** The scan descends into zip, tar and gzip content, which includes `.docx`, `.xlsx` and `.pptx`. A cap on the total decompressed size and on the nesting depth stops decompression bombs. Encrypted archives are rejected with `422 ATTACHMENT_NOT_SCANNABLE`.
  - Note (the redesign, CONVENTIONS §15.7): the cap is 64 MiB unpacked in all and 3 levels; the scan streams what it unpacks through bounded windows, never holding a member whole, and a scan that takes longer than 10 s is unscannable too. Each format pattern takes linear time (a left guard or bounded repetitions): the JWT pattern without its guard took minutes for a few hundred KB of `eyJ`.
- **On a hit** the server rejects the upload with `422 ATTACHMENT_CONTAINS_CREDENTIAL`. The problem names the matched vault secret or format, never the value.
  - A vault match cannot be overridden. The value is a live credential: remove it from the file, or rotate it.
  - A format-only match can be overridden by an OWNER or ADMIN. The audit event records the override.
- **Rescan on secret change (follow-up, not in the first implementation).** Creating or updating a vault secret starts a background rescan of the company's attachments with the same scanner. A matching attachment is withheld from runs and flagged for review in the UI.
- There is no second scan before a run. The runner host's outbound scan stays as it is; it is not specific to attachments.

### Limits

- 25 MiB per file (`TINFIRM_ATTACHMENT_MAX_FILE_SIZE`), 50 files and 250 MiB per task (`TINFIRM_ATTACHMENT_MAX_FILES_PER_TASK`, `TINFIRM_ATTACHMENT_MAX_TASK_SIZE`).
- The server enforces the limits with the multipart limits and a counting stream, and aborts the upload when the limit is passed.
- Problem codes: `413 ATTACHMENT_TOO_LARGE`, `409 ATTACHMENT_LIMIT_REACHED` (files or bytes), `409 DUPLICATE_ATTACHMENT_NAME`.
- File names are the last path segment of the uploaded name, in Unicode NFC, without control or formatting characters, and unique within the task, because they become file names in the sandbox.

### Access

| Operation | OWNER | ADMIN | OPERATOR | VIEWER | Instance admin who is not a member |
|---|---|---|---|---|---|
| List or download attachments | yes | yes | yes | yes | yes (audited `admin.company.accessed`) |
| Upload or delete an attachment | yes | yes | yes | no | yes |
| Override a format-only scan hit | yes | yes | no | no | yes |

- The paths are company-scoped, so the tenant isolation test covers them automatically (CONVENTIONS §10).
- Downloads are always `Content-Disposition: attachment` with the stored content type, `X-Content-Type-Options: nosniff` and the strict CSP. The UI never renders an attachment inline.

### Audit

- `attachment.uploaded`: `details` holds `taskId`, `sha256`, `sizeBytes`, `contentType`, `scanned: true` and `acknowledgedCredentialFormats` (the format names) when an OWNER or ADMIN acknowledged a match. `targetId` is the attachment ID.
- `attachment.deleted`: `details` holds `taskId` and `sha256`.
- The file name is not in `details`, because a name can contain personal data. It lives only in the attachment row, which erasure can delete.
- Member downloads are not audited, like other reads. An agent's access is recorded in the run's event log: the attachment IDs and hashes placed in the workspace.

### Agent access

- `run.assign` lists the task's attachments (id, file name, size, SHA-256). Before the sandbox starts, the runner host downloads each one from `GET /api/runner/v1/runs/{runId}/attachments/{id}?epoch={epoch}` with its host token, checks the size and hash, and mounts the files read-only at `/attachments` (also in `TINFIRM_ATTACHMENTS_DIR`). The directory is outside `/workspace`, so the end-of-run WIP commit never picks the files up. It is deleted when the run ends.
- The server serves a file only to the runner host the run is assigned to, only while the run is active and has that epoch, and only for that run's task. Everything else is 404.
- A file deleted after the assignment (404) is skipped with a LOG event. A size or hash mismatch fails the run with `INFRA` before the agent starts.
- The prompt and the resume prompt list the files under `# Attachments` and tell the agent to treat their content as data, not as instructions. A platform tool that lists them, next to `list_repositories` and `request_repository` (CONVENTIONS §15.6), is a possible follow-up; the prompt and the directory are enough for now.
- A run keeps its copy if an attachment is deleted during the run. Files that an agent produces are artifacts, not attachments.

### Storage

- Until object storage exists, the content is a `bytea` column of `task_attachment` in PostgreSQL, stored out of line and uncompressed. It shares the database's row-level security, tenant isolation and backups. List queries never read it. Moving it to object storage (`companies/{companyId}/attachments/{attachmentId}`: S3 in Kubernetes, the filesystem in Compose) later does not change the API.
- The metadata is in the same row: ID, company, task, file name, content type, size, SHA-256, uploader and time.
- Deleting an attachment deletes its content at once. Tasks are not deleted in v0.

## Consequences

- One scanner in one place. The server holds every value it checks against, and plain vault values stay inside the vault module.
- A credential in a file is stopped before an agent or another member sees it.
- A subscription token in a format the scanner does not know passes the upload scan. It does not expose anything new: the sandbox already holds that token, and the outbound scan covers what leaves the sandbox (threat model, R19).
- The scanner cannot read compressed PDF streams or content in images (threat model, R20).
- Each upload decrypts the company's vault secrets in memory. The cost grows with the number of secrets; the vault module caches nothing between scans.
- While the content is in PostgreSQL, attachments grow the database and its backups: at most 250 MB per task by default.
- Until the rescan exists, a file uploaded before a secret was created is not checked against that secret.
- A rescan after a secret change can withhold a file that a run already used. The earlier run's event log shows which files it had.
- New problem codes and audit actions extend CONVENTIONS §6 and §8.

## Alternatives considered

- **Scan at upload and again on the runner host before each run.** Rejected: the second scan only adds the subscription-token values, and the sandbox already holds those. Two scanners with different rules would drift apart.
- **Move subscription tokens to the server so that it holds every credential.** Rejected: [ADR-0007](0007-credentials-stay-in-deployment.md) keeps subscription logins on the owner's runner host.
- **Scan with `VaultService.reveal` per secret.** Rejected: it writes one `secret.accessed` event per secret per upload, and moves plain values out of the vault module.
- **No scan; rely on the outbound scan only.** Rejected: the file would already be in the model provider's context and visible to other members.
