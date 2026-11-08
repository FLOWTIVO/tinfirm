# ADR-0111: Audit chain heads anchored outside the database

- Status: Accepted
- Date: 2026-11-07
- Decided by: the owner, 2026-11-08: option (a) with a pluggable sink and (c)'s cheap half, as recommended, with every part of it in Pro and Enterprise (Community has no export either); the default sink `none`, the interval an hour
- Sources: the owner's decision of 2026-11-07 (release checklist E4: an anchor before 1.0); [ADR-0010](0010-hash-chained-audit-log.md) (the chain, its consequences); [ADR-0077](0077-licence-editions-and-seats.md), decision 4 (the editions; the SIEM stream, planned for Enterprise); [ADR-0081](0081-signed-release-images.md) (keys verified offline, no transparency log); the [threat model](../threat-model.md), 2.8, 2.9, 2.11, 2.12, §6.2, R5, Q6, Q17; [CONVENTIONS](../CONVENTIONS.md) §4, §8, §15.11 (the export and the daily check), §15.24; [PLATFORM.md](../PLATFORM.md) §9
- Changes: the `audit` module's port `AuditAnchors` and the daily check's fourth failure `ANCHOR_MISMATCH`, in the core; the writer, the sinks, the keys and the offline tool `tinfirm-audit-verify`, under `ee/` (`Feature.AUDIT_ANCHOR`); V084; `GET /api/admin/audit-anchor`, the checkpoint in the verification and the export; configuration (CONVENTIONS §4, the chart's `auditAnchor`, Compose); the operator guide's "Anchor the audit"; the threat model (2.8, 2.16, R5, A43, §6.2, Q6 closed); `CHANGELOG.md`'s known limitations
- Amends: [ADR-0010](0010-hash-chained-audit-log.md), whose consequence "a full rewrite … is not detected" now holds only without the anchor; [ADR-0077](0077-licence-editions-and-seats.md), decision 4, whose table gains the anchor in Pro and Enterprise

## Context

- **The chain proves order, not origin.** ADR-0010's hash has no key, and every chain head lives in PostgreSQL: the head rows, and since package 6 the daily check's anchor (`audit_check`). Whoever can write the database as `tinfirm_owner` or a superuser can change an event and recompute every hash after it, delete the newest events and move the head back, then rewrite `audit_check` to match. The full verification and the daily check then pass. ADR-0010 names this, the threat model accepts it as R5 and leaves the anchor open as Q6, and `CHANGELOG.md` lists it as a known limitation.
- **Who can write the database, and what they gain.**
  - *A DBA, a cloud account's operator of a managed PostgreSQL, whoever holds the owner password or a superuser, or a backup that someone restores after editing it.* None of them needs the server. They gain a rewritten history: remove the trace of an access (`admin.company.accessed`, `conversation.read`, `secret.accessed`), of a lent credential or a widened agent, or of their own changes made outside the platform (2.11). The audit is the evidence a company shows its auditors (A5); a silent rewrite turns it into evidence for whatever the writer wants.
  - *Code execution in the server.* The server holds the owner password, because it runs Flyway (2.9, Q17 open). This attacker has the database and everything the server holds.
  - *The runtime role* (`tinfirm_app`) cannot: it has no `UPDATE` or `DELETE` on `audit_event`, and since V073 it moves a head only one event forward (2.12). It is not this ADR's problem.
- **An anchor is a statement of the head (chain, `seq`, hash, time) kept where the database writer cannot change it.** Every later verification must find the chain consistent with every such statement: the event at that `seq` has that hash, and the chain still reaches at least that far. History before the latest anchor is then fixed; at most the events since it can be lost undetected.
- **What the anchor must survive.** A self-hosted product without a route to the vendor (ADR-0077), installations without any route to the internet (Enterprise's air-gapped case), and an operator who sets it up once. Writing an anchor must never stop the audit: a failed write is an alarm, never a refused event.

## Options

### (a) Periodic signed checkpoints to a sink the database writer cannot change

At an interval, a `worker` replica reads every chain's head and writes one **checkpoint**: a JSON statement of the heads, its time, and the previous checkpoint's digest, signed with a key the server holds outside the database, to a pluggable sink.

- *Sinks.* Object storage with object lock in compliance mode (S3, MinIO, Ceph, any S3-compatible store that implements it), where nobody, the server included, can delete or overwrite an object before its retention ends; a directory on a volume that the database writer cannot reach (weaker: whoever controls the host controls the volume); syslog to a remote collector that the operator keeps apart (write-only from the server's side).
- *Closes Q6 against the database writer who does not also hold the server's secrets:* they cannot sign, and they cannot reach the sink.
- *Against code execution in the server:* it can sign new checkpoints and write new objects, but it cannot change or delete the ones an object lock already holds. History up to the last checkpoint before the compromise stays fixed; what follows it is not protected until the key is rotated.
- *Cost:* a key and a sink to configure; one small object per interval; no new dependency at run time beyond the sink, and none in the air-gapped case (MinIO, a volume, or an internal syslog collector).

### (b) Checkpoints sent to an external append-only service

The same checkpoints, sent to a SIEM (Splunk, Sentinel, Elastic, through syslog or HTTP) or to a transparency log (Sigstore's Rekor, a Trillian log of the customer's own).

- *Stronger where a third party runs the log:* nobody inside the installation can change it, and a transparency log proves inclusion and consistency to anyone.
- *Costs:* a service per customer, of many kinds; ADR-0077 plans the audit's SIEM stream for Enterprise, later work; a public log (Rekor) publishes an installation's chain heads and its activity's rhythm, and needs the internet (rejected for images by ADR-0081 on the same ground). Without a route out, only the customer's own SIEM or log remains.
- *Fits (a):* a syslog sink is the first SIEM integration, and the SIEM stream can carry the checkpoints when it comes.

### (c) The checkpoint shown to the company's admins and in exports

The app shows the latest checkpoint of the company's chain (`seq`, hash, time, key) with the verification, and every export carries the checkpoint that covers its range. Admins write it down, or keep the export, and compare later.

- *The only option that protects a company from the operator,* who controls the database, the server's key and the sink alike (the companies of an instance trust their one operator, §2.2 of the threat model, so this is not required).
- *Alone, it relies on people doing it,* and on a server that is not lying when it shows the value. As a complement to (a) it costs a field in the API and a line in the export.

### (d) RFC 3161 timestamps

A timestamp authority (TSA) signs the checkpoint's hash with its time; the token can be kept anywhere, the database included, because nobody can obtain a token with an earlier time for a new hash.

- *Strong on time,* and independent of the server's key.
- *Costs:* a TSA reachable from the server (public TSAs are free but external; an internal one is a service to run), so the air-gapped case needs a TSA inside the network; a token kept in the database can still be deleted along with the newest events, so it closes truncation only together with something outside that says a token should exist. ASN.1 and CMS parsing, and a TSA's certificate chain to trust and renew.
- *Fits (a) later:* a checkpoint may carry a TSA token beside its signature, without a change of format.

## Decision

**(a): signed checkpoints of the chains' heads, written by the server at an interval to a pluggable sink, with sinks for S3-compatible object storage with object lock, a directory, and syslog; an offline tool that checks the chains against them; and (c)'s cheap half, the latest checkpoint shown with the verification and carried by exports. All of it in Pro and Enterprise (`AUDIT_ANCHOR`). (b) and (d) stay later work that plugs into the same checkpoints.**

1. **The checkpoint** (`Checkpoint`). One per write for the whole instance, a JSON object, canonical as ADR-0010's (keys sorted, no whitespace):

   ```json
   {"chains":[{"companyId":null,"hash":"…","seq":5512},{"companyId":"…","hash":"…","seq":88123}],
    "instance":"<instance id>","issuedAt":"2026-11-07T10:00:00.000000Z","keyId":"k2026",
    "kind":"tinfirm.audit.checkpoint","number":1234,"prev":"<digest of checkpoint 1233>","v":1}
   ```

   - `chains` holds **the heads that moved since the latest checkpoint** (every chain's in the first), the instance's chain first, then by company id: a chain's latest checkpoint names its head as it was then, and an idle instance writes nothing. A checkpoint per write rather than per chain keeps one object per interval however many companies an instance holds.
   - `number` counts up from 1, and `prev` is the digest (`hex(sha256)` of the canonical form) of the checkpoint before it, 64 zeros before the first: checkpoints are a chain of their own, so a missing or replaced one shows in a sink that is not locked.
   - `instance` is a random id the server makes at the first write (`audit_anchor_state`), the checkpoints' folder in the sink, so that two instances writing to one bucket, or a restored copy of an instance, are told apart.
   - The signature is **Ed25519** over the canonical bytes, stored with them as one canonical line: `{"checkpoint":{…},"signature":"<base64>"}`. Asymmetric, so that whoever verifies needs only the public key and can never sign; Ed25519, as the licence keys (A37), because the JDK has it and the signatures are small. A checkpoint is read back only when its content is its own canonical form, so that a field added after signing fails as a changed one does.
2. **The writer** (`AuditAnchorService`, under `ee/`). On `worker` replicas, a look every minute writes a checkpoint once `TINFIRM_AUDIT_ANCHOR_INTERVAL` (an hour) has passed since the last look, under an advisory lock so that one replica writes. It reads the heads in the instance-wide context, follows the latest checkpoint in the sink (its signature verified; the database's record only for a sink that cannot be read), signs, writes to the sink, then records the checkpoint in `audit_checkpoint` and the heads it names in `audit_checkpoint_chain`. The database's rows serve the app and the daily check; verification never trusts them.
   - *A failed write never stops an audit event.* It is logged at `ERROR`, counted (`consecutive_failures`, the last reason and time, on `GET /api/admin/audit-anchor`), and tried again after five minutes with the heads of then; a checkpoint's number is taken only once its write succeeded. An instance has no inbox of its own, so the status and the logs are the alarm; the tool reports the longest time between two checkpoints.
   - A latest checkpoint in the sink that does not verify stops the writer, which keeps failing until it is understood: no checkpoint is chained to one the server cannot trust.
3. **The sinks** (`AnchorSink`). `TINFIRM_AUDIT_ANCHOR_SINK`: `none`, `s3`, `file` or `syslog`; one per instance (a list is a later, compatible change).
   - **`s3`** (`S3AnchorSink`). One object per checkpoint, `<prefix><instance>/<number, 12 digits>.json`, written with `If-None-Match: *` and, unless the operator turned the requirement off, `x-amz-object-lock-mode: COMPLIANCE` and a retain-until date `TINFIRM_AUDIT_ANCHOR_S3_RETENTION` ahead (400 days). The start refuses a bucket that answers it has no object lock, unless `TINFIRM_AUDIT_ANCHOR_S3_REQUIRE_LOCK` is `false`; a store that cannot be reached at the start is tried again by the first write. Object lock keeps versions, so a reader reads a checkpoint's first version and reports any second one as an overwrite. The server's key needs `PutObject`, `GetObject`, `ListBucket`, `ListBucketVersions` and `GetBucketObjectLockConfiguration`, never `DeleteObject` or `BypassGovernanceRetention`.
   - **`file`** (`FileAnchorSink`). One file per checkpoint, `<dir>/<instance>/<number, 12 digits>.json`, written beside its name and linked into place, never over an existing one. Its protection is the operator's: a volume that the database's hosts and administrators cannot write, a WORM or append-only mount, or a directory a job ships elsewhere.
   - **`syslog`** (`SyslogAnchorSink`). One RFC 5424 message per checkpoint (facility 13, log audit, severity notice; `APP-NAME` `tinfirm`, `MSGID` `audit-checkpoint`, no structured data, the signed line as the message) over TLS (RFC 5425, the JVM's trust store, the host's name checked), TCP (RFC 6587's octet counting) or UDP to `TINFIRM_AUDIT_ANCHOR_SYSLOG_HOST`:`_PORT`. The server cannot read a collector back, so the writer follows its own record and the daily check compares with it; the tool reads the collector's messages exported to a file. This is the first integration with a SIEM (option (b)).
   - **`none`** (the default). The start logs a warning that the audit is not anchored (threat model 2.8).
4. **Verification: the tool** (`AuditAnchorTool`, under `ee/`). `tinfirm-audit-verify` in the server's image (`java -cp /app/application.jar …AuditAnchorTool`), which starts no server and needs neither it nor the database's writer:
   - `keygen <kid> [<dir>]` writes `<kid>.key` (PKCS#8 PEM, its owner's alone) and `<kid>.pub` (X.509 PEM).
   - `verify --checkpoints <source> --public-key <kid>=<file>… (--jdbc-url <url> [--jdbc-user <user>] | --export <file>…) [--distrust <kid>@<time>]`. The source is the instance's folder of the `file` sink, an S3 prefix (`s3://<bucket>/<prefix><instance>/`, with `--s3-endpoint`, `--s3-region`, `--s3-path-style` and the key in `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), or a file of the collector's messages (`syslog:<file>`). The trusted public keys come from the command line only, never from the database or the checkpoints. The chains come from a read-only connection to the database (a role with `SELECT` on `audit_event`, its password in `TINFIRM_AUDIT_VERIFY_JDBC_PASSWORD`; every chain) or from exports of `GET /audit-events/export` (one chain each).
   - *Checks:* each checkpoint's signature with a trusted key, its place (number and instance), its versions in the sink, a distrusted key's checkpoints stored after the time given, the numbers without gaps and each `prev`; each chain from its first event, ADR-0010's hash; for every checkpoint and every chain it names, that the event at its `seq` exists and has its hash, and that the chain reaches that far. Exit 0 when everything verified, 1 on any finding, 2 for a wrong invocation or a source that cannot be read.
   - *What an operator sees*, one line per chain and per finding:

     ```
     checkpoints   1..1234 read (keys k2026, k2027); 2026-05-02T08:00:00Z to 2026-11-07T10:00:00Z; the longest time between two: PT16H
     chain instance             seq 1..5530 OK, anchored to seq 5512 (checkpoint 1234)
     chain 3f2a…                seq 1..88140 OK, anchored to seq 88123 (checkpoint 1234)
     chain 9c41…                MISMATCH at seq 4410: the hash differs from checkpoint 801 (2026-09-30T14:00:00Z)
     chain 77d0…                SHORTER: the last event is seq 1200, checkpoint 1230 (2026-11-06T09:00:00Z) names seq 1305
     checkpoint 977        MISSING (976 -> 978)
     result: FAILED (3 findings)
     ```
   - The format of checkpoints and the checks are documented in the operator guide, so that an auditor can write a verifier of their own, as for the export.
5. **Verification in the app.** The daily check (`AuditCheckService`, CONVENTIONS §15.11), once the chain verified from its anchor, compares it with the latest checkpoint that names it (`AuditAnchors.compare`): read from the sink and its signature verified for `s3` and `file` (the database's record says which checkpoint, and the sink's latest is read too, so that a record removed from the database hides nothing), the database's record for `syslog`. A mismatch is a fourth failure, `ANCHOR_MISMATCH`, with `first_invalid_seq` the checkpoint's `seq` and `anchor_checkpoint` its number, shown, sent to the inbox of OWNER and ADMIN and acknowledged as the others are. An acknowledgement moves the daily check's anchor but never a checkpoint: the tool still reports the break. `GET …/audit-events/verification` (the company's and the instance's) answers `valid` false at the same `seq`, and `checkpoint` (`number`, `issuedAt`, `keyId`, `seq`, `hash`). `GET /api/admin/audit-anchor` (instance admins) answers whether the anchor is in force, the sink and its place, the instance's id, the active key's id, every public key the server trusts, the last checkpoint written and the writer's last success and failure.
6. **(c), shown and exported.** The verification on the audit page names the checkpoint the chain was compared with, as a sentence an admin may note. An export's first line gains `checkpoint`: the latest checkpoint that names the chain at or before the export's `toSeq` (`number`, `issuedAt`, `keyId`, `seq`, `hash`, or null), which the operator's tool checks against the sink. It is an excerpt, not the signed document: a checkpoint names every company whose head moved, which one company's export must not show.
7. **Keys and rotation** (the threat model's §6.2).
   - *Where.* In the deployment's configuration, never the database or the vault: the vault's data keys and wrapped instance keys are rows of the database, and the database writer could change which public keys the server trusts or what it signs with. `TINFIRM_AUDIT_ANCHOR_KEYS_<KID>_PRIVATE` holds an Ed25519 private key as PKCS#8 PEM (its line breaks may be written `\n`), `TINFIRM_AUDIT_ANCHOR_KEYS_<KID>_PUBLIC` a retired key's public half, X.509 PEM, which only verifies, and `TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY` names the key that signs. A key id is 1 to 32 lowercase letters and digits. With a sink other than `none`, the server refuses to start without an active key, with a key that does not parse, or with a private and a public half that are not one pair.
   - *Normal rotation.* Make the next pair (`keygen`), add its private key, make it active, restart; replace the old key's private half with its public half (`_PUBLIC`); give the new public key to whoever verifies. Old checkpoints keep verifying with the old public key, which is never dropped while a checkpoint it signed is kept.
   - *After a compromise.* As normal rotation, at once. A checkpoint signed by the leaked key is trusted only as far as the sink proves it was stored before the compromise: for `s3` the object's time and lock, for `file` the file's time; the tool's `--distrust <kid>@<time>` fails every checkpoint of that key the sink dates later, or cannot date. Rotating the key does not repair what an attacker with the server wrote while they held it; it stops them from signing more.
   - *The public keys* are logged at every start and answered by `GET /api/admin/audit-anchor`, so that whoever verifies can compare them with a copy taken out of band.
8. **Air-gapped.** (a) needs nothing outside the network: MinIO or another S3-compatible store with object lock, a volume, or an internal syslog collector; the tool runs offline. (b) and (d) need a SIEM, log or TSA inside the network, and stay later work.
9. **Editions** (ADR-0077, decision 4: "Everything is recorded in the audit in every edition; proving it is paid"). The anchor is a way of proving the audit, as its export, verification and daily checks' results are, so it is Pro's and Enterprise's: `Feature.AUDIT_ANCHOR` (Pro), its code under `ee/` (decision 1).

   | | Community | Pro | Enterprise |
   |---|---|---|---|
   | Checkpoints written to a sink, keys and rotation, `GET /api/admin/audit-anchor` | – | ✓ | ✓ |
   | The daily check's comparison (`ANCHOR_MISMATCH`), the checkpoint with the verification and in exports | – | ✓ | ✓ |
   | The offline tool `tinfirm-audit-verify` (in every image; checks the checkpoints a paid instance wrote) | – | ✓ | ✓ |
   | Checkpoints in the audit's SIEM stream, RFC 3161 tokens (later) | – | – | ✓ |

   Under Community the writer writes nothing and the status answers `403 EDITION_REQUIRED`; a sink configured there is checked at the start all the same, so that a key added later finds it ready. Checkpoints written while a licence held the anchor stay in the sink, and the tool reads them in any edition.
10. **Operator configuration** (CONVENTIONS §4; the chart's `auditAnchor`; Compose's `.env` and `.env.audit-anchor`).

    | Variable | What | Default |
    |---|---|---|
    | `TINFIRM_AUDIT_ANCHOR_SINK` | `none`, `s3`, `file`, `syslog` | `none` |
    | `TINFIRM_AUDIT_ANCHOR_INTERVAL` | How often a checkpoint is written while a head moved; `PT5M` to `P1D` | `PT1H` |
    | `TINFIRM_AUDIT_ANCHOR_KEYS_<KID>_PRIVATE`, `_PUBLIC`; `TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY` | The Ed25519 keys (a private key for a key that signs, a public key for a retired one) and the one that signs; required with a sink | none |
    | `TINFIRM_AUDIT_ANCHOR_S3_ENDPOINT` / `_REGION` / `_BUCKET` / `_PATH_STYLE` / `_PREFIX` | As the session bundles' store (ADR-0097): a bucket of its own, with object lock | empty, `us-east-1`, empty, `false`, `audit-checkpoints/` |
    | `TINFIRM_AUDIT_ANCHOR_S3_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | The server's key for that bucket only, without the right to delete | empty |
    | `TINFIRM_AUDIT_ANCHOR_S3_RETENTION`, `_REQUIRE_LOCK` | The object lock's retention; whether the start refuses a bucket without object lock | `P400D`, `true` |
    | `TINFIRM_AUDIT_ANCHOR_FILE_DIR` | The directory of the `file` sink | none (the image's `/var/lib/tinfirm/audit-checkpoints` in Compose and the chart) |
    | `TINFIRM_AUDIT_ANCHOR_SYSLOG_HOST` / `_PORT` / `_PROTOCOL` | The collector of the `syslog` sink; `tls`, `tcp` or `udp` | none, `6514`, `tls` |

    Compose mounts a volume of its own for the `file` sink and reads the keys from an optional `.env.audit-anchor`, apart from `.env`; the chart takes `auditAnchor.*`, the keys from a Secret (`<kid>.key`, and `<kid>.pub` for a retired key), the bucket's key from another, and the `file` sink's volume from a claim of its own. The operator guide shows MinIO with object lock.

### Why this is enough before 1.0

It closes Q6 for the threat ADR-0010 names, the database writer without the server's secrets, with parts already familiar to the code: an Ed25519 signature (the licence's), an S3 client (the session bundles'), the daily check, and the export's canonical form. The residual is bounded and stated: the events of the last interval, and an attacker inside the server between their entry and the key's rotation.

## Consequences

- **Q6 closes; R5 narrows** for instances that run the anchor. A rewrite or a cut of history before the latest checkpoint, by anyone without the active key and write access to the sink, fails the tool and the daily check. What remains: the events since the latest checkpoint (at most the interval, an hour by default), an attacker with code execution in the server from that moment until the key is rotated (they hold the key and the sink's write access, though not the delete right under object lock), the `file` sink's dependence on the operator's volume, and the operator themselves, who controls everything; the checkpoint shown with the verification and kept in exports (decision 6) is the company's own check against the operator.
- **Community keeps R5 as it was** (decision 9): its chain detects partial changes only.
- **A new asset**, A43: the anchor's signing keys, in the deployment's configuration, with the sink's credential. The threat model gains them in §3 and §6.2, and 2.8's mitigation changes.
- **Q17 matters more.** The server holds both the owner password and the anchor's key; running migrations in a separate step would leave a compromised server without the owner password, so it could no longer rewrite events at all.
- **Operators have one more thing to configure,** and the default `none` leaves Q6 open on an installation that does nothing; the start's warning says so, and the status shows it.
- **Writes add little.** One read of every head per interval and one small object; nothing on the path of an audit event.
- **The checkpoint's format is a contract,** as the canonical form is: a change is a new `v`, and the tool reads every version it ever wrote.
- **A restored backup** carries an older chain than the latest checkpoints name: the tool and the daily check report it, as they should, and the operator records the restore. The writer's next checkpoint names the restored heads.

## Alternatives considered

- **(b) An external append-only service as the only anchor.** Stronger where a third party runs it, but a service of many kinds per customer, a public log that leaks an installation's activity, and nothing without the internet. Its SIEM half is the `syslog` sink now and Enterprise's stream later.
- **(c) Alone.** Relies on admins writing the value down, and on the server showing the truth. Kept as a complement (decision 6).
- **(d) RFC 3161 alone.** Needs a TSA reachable from every installation, does not close truncation while its tokens live in the database, and brings ASN.1 and a TSA's certificate chain. A later addition to the checkpoint.
- **The writing and the tool in every edition**, as a security control of the record. Proposed, not chosen: the owner decided the anchor is a way of proving the audit, which is paid, and Community has no export to verify either.
- **An HMAC with a shared key in place of a signature.** Simpler, but whoever verifies must hold the key that signs, so an auditor could forge. Not chosen.
- **The signing key in the vault** (`instance_key`). It needs no new configuration, but its wrapped key and the list of what the server trusts would be rows of the database the anchor must not depend on. Not chosen.
- **One checkpoint per chain, or per event.** Per chain multiplies objects by the companies of an instance for no gain in what is detected; per event puts the sink on the path of every audit write and makes it a reason for a refused change. Not chosen.
- **Every chain's head in every checkpoint.** Simpler to read, but the checkpoint grows with the companies of an instance while most heads stand still; the heads that moved name the same history. Not chosen.
- **The signed checkpoint in full in an export.** A company could check it with the public key alone, but it names the heads of the instance's other companies. Not chosen; the excerpt is checked by the operator's tool.
- **Refusing audit events while the sink fails** (fail closed). It would turn an object store's outage into an outage of every audited change. Not chosen; the failure is an alarm and a gap the tool reports.
