# ADR-0097: Session bundles in S3-compatible storage

- Status: Accepted
- Date: 2026-10-16
- Accepted by: the owner, 2026-10-27, with the fixes of the gaps it left open ([ADR-0101](0101-closing-the-elastic-runners-gaps.md))
- Amended by: [ADR-0100](0100-elastic-runners-in-pro.md): Session bundles are part of elastic runners, Pro's and Enterprise's (`Feature.ELASTIC_RUNNERS`); their code is under `ee/`, the runner host's under `ee/runner/`, and every edition still deletes the bundles that exist.
- Amended by: [ADR-0101](0101-closing-the-elastic-runners-gaps.md): The upload streams through the scan into the store, a chunk at a time, committed only when it is clean; the vault's rewrap covers the bundles' data keys (`vault.StoredDataKeys`, `V070`).
- Sources: [ADR-0095](0095-sandbox-bridge.md) (the sandbox bridge and its file streams), [ADR-0096](0096-a-vm-per-run.md) (a VM per run), [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0014](0014-task-attachments-and-upload-scan.md), [ADR-0064](0064-skills-library.md); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §3.5, §6.5 and §9, and its decision D3; the [session-resume review](../reviews/2026-10-07-session-resume.md); [threat model](../threat-model.md) A42, 5.17, 6.17, 18.1 to 18.3, R58
- Changes: the runner protocol v2 gains the feature `session.bundle`, with `run.assign.sessionBundle` and `run.finished.sessionBundle`; the server gains a bundle store (a directory or a bucket of S3-compatible storage), the table `session_bundle`, two endpoints for runner hosts and a sweeper; the runner host packs, uploads, downloads and restores the runtime's session folders. A new dependency: the AWS SDK for Java v2's S3 client.

## Context

- A runtime resumes a session from folders in the agent's home: Claude Code's `~/.claude/projects`, Codex's `~/.codex/sessions`, and so on. The runner host keeps a session home per conversation and agent, and the next turn resumes from it (`resumeSessionId`).
- That home is on the host that ran the last turn. A turn that goes to another company host, a runner pod that moved, and every VM of ADR-0096 lose it, and the turn starts a new session (`NO_SESSION`): it works, but it costs tokens, and the agent loses what it read.
- The session-resume review names a copy of the session on the server as the only fix for hosts that come and go, and D3 chose S3-compatible storage for it, reached only through the server and encrypted with the vault's keys.
- The folders were written in the sandbox, where the agent can be root. They are untrusted on the way in and on the way out.

## Decision

**What a bundle is.** The runtime's session folders of the home, as a gzip-compressed tar.

| Runtime | Folders | Left out |
|---|---|---|
| Claude Code | `.claude/projects`, `.claude/todos` | |
| Codex | `.codex/sessions` | |
| pi | `.pi/agent/sessions` | |
| OpenCode | `.local/share/opencode` | its `log` and `bin` |

- No settings, credentials or caches: only what a resume reads.
- At most 64 MiB before compression and 32 MiB after (`SESSION_BUNDLE_MAX_UNPACKED_BYTES`, `SESSION_BUNDLE_MAX_BYTES`). A larger one is not stored.
- One bundle per conversation and agent: the newest. It replaces the last when it is committed.

**The protocol.** Runner protocol v2, for hosts that list `session.bundle` in `hello.features`.

- `run.assign.sessionBundle` is absent unless the server keeps bundles for the run: a store is configured and the host is a company host. `{}` asks the host to upload the run's bundle when it ends. `{sha256, sizeBytes}` also names the bundle to restore before the sandbox starts: the conversation agent's, when its session is the run's `resumeSessionId` and its runtime the run's.
- When the run ends, the host uploads the bundle with the session id it reports, then reports what happened in `run.finished.sessionBundle`: `STORED`, `TOO_LARGE`, `REFUSED` (by the server's scan or the host's own), `FAILED` (it could not pack or upload) or `NONE` (no session, or no session folders).
- **The stale rule.** A run that reports a session without `STORED` leaves no bundle behind: the earlier bundle holds an older state of the session, or another session, and a resume from it would lose the turn that was just taken. The server deletes it, unless that run uploaded it.
- `resumeOutcome` does not change. A host without the feature, and a personal host (ADR-0007), keep their homes as before.

**The endpoints.** Under `/api/runner/v1`, with the host's own token, as the attachments are:

- `POST /runs/{runId}/session-bundle?epoch=&sessionId=` with the bundle as the body, for a run RUNNING on this host with this epoch. The answer is 200 with `STORED` and the SHA-256, or `REFUSED` with a message for the run's log; 413 when its `Content-Length` says more than 32 MiB (a longer body without one is `REFUSED`); anything else 404.
- `GET /runs/{runId}/session-bundle?epoch=`: the conversation agent's bundle of the run's runtime, decrypted, with its SHA-256 in `X-Content-SHA256`, for a run assigned to this host with this epoch and active. Anything else is 404. The host checks the size and the SHA-256 that its assignment named, so a bundle replaced since is not restored.
- Both answer only in the host's company, and only for a company host.

**Checked as untrusted, on the server.** The server reads the upload into memory, at most 32 MiB, and at most 4 uploads at once, so at most about 256 MiB of bundles and their ciphertext. It scans it as it scans the files that agents save (ADR-0014): for the values of the company's vault secrets and the known credential formats, through the gzip and the tar, up to the scan's 64 MiB unpacked. A match, or a bundle that cannot be scanned through, is refused, and the conversation's earlier bundle goes with it.

**Checked as untrusted, on the host.**

- Before the upload, the host scans the unpacked tar for the credentials of the run that the server does not know: the model key, the git token, the subscription token. A match is `REFUSED`. A run that leaked a credential (`CREDENTIAL_LEAK`) stores no bundle at all.
- On restore, the host checks the size and the SHA-256, and unpacks with the rules of `/output` (ADR-0095): directories and regular files only, at relative paths without `..`, at most 100,000 files and 64 MiB, into a directory of its own beside the home. Only the runtime's own folders of it then replace the home's; everything else of the bundle is left out and logged.
- `homes/<agentId>/<taskId>.bundle` records the bundle that the home's folders were last stored as or restored from. A home whose record names the assigned bundle is not restored again: it holds that bundle, or a later state of it from this host's own runs. The record is dropped before each upload and when a restore fails, and deleted with the home.
- A bundle that cannot be stored or restored is a log line of the run, never a failed run: the turn starts a new session.

**Encrypted before it leaves the server.**

- Each bundle gets a fresh 256-bit data key, which the vault's active master key wraps (`DataKeys`, a new port of the vault module), with the company, the conversation, the agent and the object's name as the wrapped key's additional data.
- The bundle is sealed with AES-256-GCM in 1 MiB chunks (`BundleCipher`): a 16-byte header (`TFSB`, the version, the chunk size and a random 7-byte nonce prefix), then each chunk with a nonce of the prefix, its counter and a flag on the last chunk, and the header and the object's name as its additional data. A reordered, cut or swapped chunk or object fails. The download decrypts as it streams.
- The object's name is a random UUID, with no company, agent or conversation in it. The bucket holds ciphertext only; the provider's encryption at rest comes on top.

**The store.** `tinfirm.session-bundles.store` (`TINFIRM_SESSION_BUNDLES_STORE`):

- `none`, the default: bundles are off, no assignment carries `sessionBundle`, and the hosts keep their homes as before.
- `filesystem`: a directory (`TINFIRM_SESSION_BUNDLES_DIRECTORY`), for Compose and the tests. Each object is written to a temporary file and moved into place, with mode `0600`.
- `s3`: a bucket of any storage that speaks the S3 API: an endpoint, a region, a bucket, path-style addressing, a key and an object prefix (`TINFIRM_SESSION_BUNDLES_S3_*`). For example DigitalOcean Spaces, or on OKD and OpenShift an S3 of the cluster's own (Ceph RGW, MinIO, OpenShift Data Foundation). The bucket is private and holds nothing else, and its key reaches that bucket only.

**The table.** `session_bundle` (V066), a tenant table with row-level security: the company, the conversation, the agent, the runtime and the session id; the object's name, the size and the SHA-256 of the bundle; the wrapped data key and its master key's id; the run that uploaded it and when. Its key is the conversation and the agent, and it goes with the conversation and with the run.

**Lifetime.**

- Each stored bundle replaces the last; the replaced object is deleted after the commit.
- The sweeper (on worker replicas, hourly) deletes the bundles of ended conversations and of those without a turn for the retention (`TINFIRM_SESSION_BUNDLES_RETENTION`, 30 days), and then the store's objects older than an hour that no row names: the leftovers of an upload that failed after its `put`, or of a delete that failed. It finds them with two narrow `SECURITY DEFINER` lookups that return ids only, and deletes each bundle again under its company's tenant context.
  - Amended 2026-10-26 (`V068`): the due lookup also returns the object the row named when it was found. Between the lookup and the delete a conversation may resume: its turn sets `last_turn_at` under the conversation's row lock, and its run replaces the bundle with a new object (`SessionBundleRows.replace`); the sweeper's plain delete by pair took the current bundle of a live conversation. The sweeper now locks the conversation first in the company's transaction (`run.ConversationLock`, `FOR NO KEY UPDATE`: the lock order of CONVENTIONS §10, the conversation before its other rows) and deletes the row only while it still names that object and the conversation is still ended or without a turn since the cutoff (`SessionBundleStore.deleteDue`); an empty answer leaves row and object alone. The object goes after its row, as before, and a batch that removed no row ends the pass.
- A purge of a conversation's content (retention's, or an erasure's of the whole conversation) deletes its bundles in the same transaction, and their objects after it.
- An erasure of words in an open conversation (a member who left, an outside identity) leaves its bundle, as it leaves the hosts' session homes: a later turn resumes that session. The erasure's answer names both among what remains (`RUNNER_SESSIONS`).
- The operator adds a lifecycle rule on the bucket that deletes objects older than 37 days, as a backstop.

**Format and dependencies.**

- gzip, not zstd as the analysis proposed: the server's scan reads gzip already, and the host and the server need no native library. Session files are text and compress well either way.
- The AWS SDK for Java v2's S3 client with its `url-connection-client`; the Netty and Apache clients are excluded. It works with Spaces, Ceph and MinIO through an endpoint override and path-style addressing.

## Consequences

- A turn resumes its session on any company host that lists `session.bundle`: another VM host, a VM per run, a runner pod that moved. The VM executor of ADR-0096 lists it, as every v2 host of this version does.
- Where a store is configured, a session's folders leave the host. They hold what the agent read: the company's code, issues and tool results. They are encrypted under the vault's master key, reached only through the server, scanned on both sides, and deleted at the latest 30 days after the last turn.
- The upload is held in the server's memory, not streamed to the bucket as a multipart upload (the analysis §6.5): the scan needs the whole content, and the caps keep it small. At most about 256 MiB at once in the worst case, so the [operations guide](../operations/session-bundles.md) asks for 2 GiB for the server with bundles on. (Since ADR-0101 the upload streams: about 10 MiB an upload, whatever its size.) A store that is down refuses nothing: the upload answers 500, the host reports `FAILED`, and the stale rule drops the earlier bundle.
- The vault's rewrap (`POST /api/admin/vault/rewrap`) does not rewrap the bundles' data keys. A bundle whose master key is removed can no longer be opened, and its next turn starts a new session. Every turn's bundle is wrapped under the active key, so the old key is no longer needed by bundles once the retention has passed after the rotation. (Since ADR-0101 the rewrap covers the bundles' data keys.)
- A bundle helps only a conversation whose agent and runtime stay the same, and only the session the run resumes. A turn of another agent in the same conversation has a bundle of its own.
- The tests: the cipher, the data keys, the file system store and the S3 adapter against a fake S3 API that checks path-style requests and their SigV4 signature; the server end to end (upload, restore on another host, refusals, the stale rule, hosts without the feature, other hosts and companies, the sweeper); and the host end to end (a session carried to a second host, a bundle with more than the allowlist, refused, oversized and leaked bundles). Not yet checked against a real bucket on Spaces or MinIO.

## Alternatives considered

- **Presigned URLs, so that the host reaches the bucket.** Rejected for now (as in the analysis): every host would need a new egress, the URLs are bearer tokens, and the server could not scan before it stores.
- **zstd.** Rejected for now: a native dependency on both sides, and a second decompressor in the server's scan, for a few per cent on text.
- **The bundle in PostgreSQL.** Rejected: up to 32 MiB per conversation's agent, written again at every turn, would churn the database and grow its write-ahead log and backups.
- **Keeping every bundle, one per run.** Rejected: only the newest is ever restored, and each kept bundle is more of the company's content outside the database.
- **Bundles for personal hosts.** Rejected: their homes stay on their owner's machine (ADR-0007), and a subscription's session would reach the server.
