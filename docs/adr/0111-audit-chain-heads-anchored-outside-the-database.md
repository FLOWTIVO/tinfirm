# ADR-0111: Audit chain heads anchored outside the database

- Status: Proposed
- Date: 2026-11-07
- Sources: the owner's decision of 2026-11-07 (release checklist E4: an anchor before 1.0); [ADR-0010](0010-hash-chained-audit-log.md) (the chain, its consequences); [ADR-0077](0077-licence-editions-and-seats.md), decision 4 (the editions; the SIEM stream, planned for Enterprise); [ADR-0081](0081-signed-release-images.md) (keys verified offline, no transparency log); the [threat model](../threat-model.md), 2.8, 2.9, 2.11, 2.12, §6.2, R5, Q6, Q17; [CONVENTIONS](../CONVENTIONS.md) §4, §8, §15.11 (the export and the daily check), §15.24; [PLATFORM.md](../PLATFORM.md) §9
- Changes, once accepted: the `audit` module (a checkpoint writer, the sinks, the comparison in the daily check), a verification tool, configuration (CONVENTIONS §4, Helm values, Compose), the operator guide, the threat model (2.8, R5, Q6 closed, a new asset), and the known limitations of `CHANGELOG.md`

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

At an interval, a `worker` replica reads every chain's head and writes one **checkpoint**: a JSON statement of every chain's head, its time, and the previous checkpoint's hash, signed with a key the server holds outside the database, to a pluggable sink.

- *Sinks.* Object storage with object lock in compliance mode (S3, MinIO, Ceph, any S3-compatible store that implements it), where nobody, the server included, can delete or overwrite an object before its retention ends; a directory on a volume that the database writer cannot reach (weaker: whoever controls the host controls the volume); syslog to a remote collector that the operator keeps apart (write-only from the server's side).
- *Closes Q6 against the database writer who does not also hold the server's secrets:* they cannot sign, and they cannot reach the sink.
- *Against code execution in the server:* it can sign new checkpoints and write new objects, but it cannot change or delete the ones an object lock already holds. History up to the last checkpoint before the compromise stays fixed; what follows it is not protected until the key is rotated.
- *Cost:* a key and a sink to configure; one object per interval, a few hundred bytes per chain; no new dependency at run time beyond the sink, and none in the air-gapped case (MinIO, a volume, or an internal syslog collector).

### (b) Checkpoints sent to an external append-only service

The same checkpoints, sent to a SIEM (Splunk, Sentinel, Elastic, through syslog or HTTP) or to a transparency log (Sigstore's Rekor, a Trillian log of the customer's own).

- *Stronger where a third party runs the log:* nobody inside the installation can change it, and a transparency log proves inclusion and consistency to anyone.
- *Costs:* a service per customer, of many kinds; ADR-0077 plans the audit's SIEM stream for Enterprise, later work; a public log (Rekor) publishes an installation's chain heads and its activity's rhythm, and needs the internet (rejected for images by ADR-0081 on the same ground). Without a route out, only the customer's own SIEM or log remains.
- *Fits (a):* a syslog sink is the first SIEM integration, and the SIEM stream can carry the checkpoints when it comes.

### (c) The checkpoint shown to the company's admins and in exports

The app shows the latest checkpoint of the company's chain (`seq`, hash, time, key) on the audit page, and every export carries the checkpoint that covers its range. Admins write it down, or keep the export, and compare later.

- *The only option that protects a company from the operator,* who controls the database, the server's key and the sink alike (the companies of an instance trust their one operator, §2.2 of the threat model, so this is not required).
- *Alone, it relies on people doing it,* and on a server that is not lying when it shows the value. As a complement to (a) it costs a field in the API and a line in the export.

### (d) RFC 3161 timestamps

A timestamp authority (TSA) signs the checkpoint's hash with its time; the token can be kept anywhere, the database included, because nobody can obtain a token with an earlier time for a new hash.

- *Strong on time,* and independent of the server's key.
- *Costs:* a TSA reachable from the server (public TSAs are free but external; an internal one is a service to run), so the air-gapped case needs a TSA inside the network; a token kept in the database can still be deleted along with the newest events, so it closes truncation only together with something outside that says a token should exist. ASN.1 and CMS parsing, and a TSA's certificate chain to trust and renew.
- *Fits (a) later:* a checkpoint may carry a TSA token beside its signature, without a change of format.

## Decision (recommended)

**Build (a): signed checkpoints of every chain's head, written by the server at an interval to a pluggable sink, with sinks for S3-compatible object storage with object lock, a directory, and syslog; a verification tool that checks a chain against them offline; and (c)'s cheap half, the latest checkpoint shown to admins and carried by exports. (b) and (d) stay later work that plugs into the same checkpoints.**

1. **The checkpoint.** One per interval for the whole instance, a JSON object, canonical as ADR-0010's (keys sorted, no whitespace):

   ```json
   {"kind":"tinfirm.audit.checkpoint","v":1,"instance":"<instance id>","number":1234,
    "issuedAt":"2026-11-07T10:00:00.000000Z","prev":"<sha256 of checkpoint 1233>",
    "keyId":"2026-11","chains":[{"companyId":null,"seq":5512,"hash":"…"},
                                {"companyId":"…","seq":88123,"hash":"…"}]}
   ```

   - `chains` holds every chain's head as the writer read it, in the order of `companyId` (null first). A checkpoint is written only when some head moved since the last one, so an idle instance writes nothing.
   - `number` counts up from 1 and `prev` is the SHA-256 of the previous checkpoint's canonical form: checkpoints are a chain of their own, so a missing or replaced one shows in a sink that is not locked.
   - `instance` is a random id that the server creates once and keeps in the deployment's sink (the first checkpoint) and in the database, so that two instances writing to one bucket, or a restored copy of an instance, are told apart.
   - The signature is **Ed25519** over the canonical bytes, stored beside them: `{"checkpoint": {…}, "signature": "<base64>"}`. Asymmetric, so that whoever verifies needs only the public key and can never sign; Ed25519, as the licence keys (A37), because the JDK has it and the signatures are small.
2. **The writer.** `AuditAnchorService`, on `worker` replicas, every `TINFIRM_AUDIT_ANCHOR_INTERVAL` (an hour), under an advisory lock so that one replica writes. It reads the heads in one read-only transaction (the head rows, checked against the events they name), builds and signs the checkpoint, writes it to the sink, and records the result in `audit_anchor` (number, time, the sink's object name, the outcome). The database row is a convenience for the app and the daily check; it is never what verification trusts.
   - *A failed write never stops an audit event.* It is retried at the next tick with the heads of then (the checkpoint's `number` is taken only once a write succeeded); it is logged at `ERROR` at every failure, and an `AUDIT_ANCHOR` item in the inbox of instance admins shows from the second consecutive failure until a write succeeds. Verification reports the gap between two checkpoints' times.
3. **The sinks.** `TINFIRM_AUDIT_ANCHOR_SINK`: `none`, `s3`, `file` or `syslog`. One sink per instance in 1.0; a list is a later, compatible change.
   - **`s3`.** One object per checkpoint, `<prefix><instance>/<number, 12 digits>.json`, written with `x-amz-object-lock-mode: COMPLIANCE` and a retain-until date `TINFIRM_AUDIT_ANCHOR_S3_RETENTION` ahead (400 days by default, beyond the audit's longest look-back in the app). At start the server reads the bucket's object lock configuration and refuses to start when the bucket has none, unless `TINFIRM_AUDIT_ANCHOR_S3_REQUIRE_LOCK` is `false` (for a store without object lock, which the guide marks as weaker). The server's key for the bucket needs `PutObject`, `GetObject`, `ListBucket` and `GetBucketObjectLockConfiguration`, never `DeleteObject` or `BypassGovernanceRetention`.
   - **`file`.** One file per checkpoint in `TINFIRM_AUDIT_ANCHOR_FILE_DIR`, written to a temporary name and renamed, never overwritten (`CREATE_NEW`). Its protection is the operator's: a volume that the database's hosts and its administrators cannot write, ideally a WORM or append-only mount, or a directory that a separate job ships elsewhere.
   - **`syslog`.** One RFC 5424 message per checkpoint (`APP-NAME` `tinfirm`, `MSGID` `audit-checkpoint`, the signed JSON as the message) over TCP, TLS or UDP to `TINFIRM_AUDIT_ANCHOR_SYSLOG_HOST`:`_PORT`. The server cannot read a collector back, so the app's comparison (decision 5) reads `audit_anchor` only, and the verification tool takes the collector's messages as a file the operator exports. This is the first integration with a SIEM (option (b)).
   - **`none`** (the default; see the open questions). The server starts, logs a `WARN` at every start that the audit is not anchored, and the instance admins' inbox shows `AUDIT_ANCHOR` with `NOT_CONFIGURED`.
4. **Verification: the tool.** `tinfirm-audit-verify`, a Java command in the server image (`java -cp /app/app.jar … AuditAnchorTool verify`, wrapped in a script), that needs neither the server nor its database writer:
   - *Inputs:* the checkpoints (a directory, an `s3://` prefix with a read-only key, or a syslog export), the trusted public keys (`--public-key <kid>=<file>`, repeated; never read from the database or from the checkpoints), and the chains, either an export of `GET /audit-events/export` (NDJSON, ADR-0010's canonical lines) or a read-only JDBC connection (`--jdbc-url`, a role with `SELECT` on `audit_event` alone).
   - *Checks:* every checkpoint's signature with a trusted key, and the checkpoints' own chain (`number` without gaps, `prev`); every chain from its first event, ADR-0010's hash; then, for every checkpoint and every chain it names, that the event at its `seq` exists and has its hash. It reports the first failure of each kind and exits non-zero on any.
   - *What an operator sees,* one line per finding, for example:

     ```
     checkpoints   1..1234 verified (keys 2026-05, 2026-11); last 2026-11-07T10:00:00Z
     chain instance        seq 1..5530       OK, anchored to seq 5512 (checkpoint 1234)
     chain 3f2a…           seq 1..88140      OK, anchored to seq 88123 (checkpoint 1234)
     chain 9c41…           BROKEN at seq 4410: hash differs from checkpoint 801 (2026-09-30T14:00:00Z)
     chain 77d0…           SHORTER: head seq 1200, checkpoint 1230 names seq 1305
     checkpoint 977        MISSING (976 → 978)
     gap                   2026-10-12T03:00Z → 2026-10-12T19:00Z: no checkpoint for 16 h
     result: FAILED (3 findings)
     ```
   - The format of checkpoints and the checks are documented in the operator guide, so that an auditor can write a verifier of their own, as for the export.
5. **Verification in the app.** The daily check (`AuditCheckService`, CONVENTIONS §15.11) also compares each chain with the latest checkpoint that names it: from the sink for `s3` and `file`, verifying the signature, and from `audit_anchor` for `syslog`. A mismatch is a new failure, `ANCHOR_MISMATCH` (with the checkpoint's number and the `seq`), shown and acknowledged as the other failures are. An acknowledgement moves the daily check's anchor but never a checkpoint: the tool still reports the break. `GET /api/admin/audit-anchor` (instance admins) answers the sink, the active key's id, the last checkpoint written and the last failure. The company's verification answers `anchoredSeq`, `anchoredAt` and `checkpoint` (its number) beside `firstInvalidSeq`.
6. **(c), shown and exported.** The audit page shows the latest checkpoint of the company's chain (`seq`, hash, time, key id), and an export's first line gains `checkpoint`: the latest checkpoint that names the chain at or below the export's `toSeq`, in full with its signature, so that a kept export can be checked against the sink and the public key later.
7. **Keys and rotation** (the threat model's §6.2 gains the row).
   - *Where.* In the deployment's configuration, never the database or the vault: the vault's data keys and wrapped instance keys are rows of the database, and the database writer could change which public keys the server trusts or what it signs with. `TINFIRM_AUDIT_ANCHOR_KEYS_<KID>` holds an Ed25519 private key as PKCS#8 PEM (a Secret of its own in Helm, `tinfirm-audit-anchor`), `TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY` names the one that signs, and `TINFIRM_AUDIT_ANCHOR_PUBLIC_KEYS_<KID>` lists retired public keys that only verify. `AuditAnchorTool keygen` writes a pair. With a sink other than `none`, the server refuses to start without an active key, with a key that does not parse, or with two keys under one id.
   - *Normal rotation.* Add the next key, make it active, restart; move the old one to `_PUBLIC_KEYS_<KID>` (its private half deleted); give the new public key to whoever verifies. Old checkpoints keep verifying with the old public key, which is never dropped while a checkpoint it signed is kept.
   - *After a compromise.* As normal rotation, at once. A checkpoint signed by the leaked key is trusted only as far as the sink proves it was written before the compromise: for `s3` the object's creation time and lock, which the tool prints beside `issuedAt`; for `file` and `syslog` the operator's own records. The tool takes `--distrust <kid>@<time>` to fail every checkpoint of that key the sink dates later. Rotating the key does not repair what an attacker with the server wrote while they held it; it stops them from signing more.
   - *The public keys* are shown to instance admins (`GET /api/admin/audit-anchor`) and printed by the server at start, so that whoever verifies can take a copy out of band.
8. **Air-gapped.** (a) needs nothing outside the network: MinIO or another S3-compatible store with object lock, a volume, or an internal syslog collector; the tool runs offline. (b) and (d) need a SIEM, log or TSA inside the network, and stay later work.
9. **Editions** (ADR-0077, decision 4: "Everything is recorded in the audit in every edition; proving it is paid").

   | | Community | Pro | Enterprise |
   |---|---|---|---|
   | Checkpoints written to every sink (`s3`, `file`, `syslog`), keys and rotation | ✓ | ✓ | ✓ |
   | The offline tool `tinfirm-audit-verify` (from a JDBC connection; from an export where there is one) | ✓ | ✓ | ✓ |
   | The daily check's comparison with the checkpoints (`ANCHOR_MISMATCH`), the company's anchored status, the checkpoint on the audit page and in exports | – | ✓ | ✓ |
   | Checkpoints in the audit's SIEM stream, RFC 3161 tokens (later) | – | – | ✓ |

   Writing checkpoints and the tool stay in the MIT core, as the chain itself does: they are a security control of the record that every edition keeps, and a verifier anyone could write from the documented format would be no gate. What is paid is what already is: the audit's tools in the app (`AUDIT_TOOLS`), whose code is under `ee/` (CONVENTIONS §15.24).
10. **Operator configuration** (CONVENTIONS §4; Helm `audit.anchor.*`; Compose's `.env.example`).

    | Variable | What | Default |
    |---|---|---|
    | `TINFIRM_AUDIT_ANCHOR_SINK` | `none`, `s3`, `file`, `syslog` | `none` |
    | `TINFIRM_AUDIT_ANCHOR_INTERVAL` | How often a checkpoint is written when a head moved; at least `PT5M`, at most `P1D` | `PT1H` |
    | `TINFIRM_AUDIT_ANCHOR_KEYS_<KID>`, `TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY` | The Ed25519 private keys (PKCS#8 PEM) and the one that signs; required with a sink | none |
    | `TINFIRM_AUDIT_ANCHOR_PUBLIC_KEYS_<KID>` | Retired public keys (PEM), for the app's comparison | none |
    | `TINFIRM_AUDIT_ANCHOR_S3_ENDPOINT` / `_REGION` / `_BUCKET` / `_PATH_STYLE` / `_PREFIX` | As the session bundles' store (ADR-0097), a bucket of its own with object lock | empty, `us-east-1`, empty, `false`, `audit-checkpoints/` |
    | `TINFIRM_AUDIT_ANCHOR_S3_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | The server's key for that bucket only, without delete rights | empty |
    | `TINFIRM_AUDIT_ANCHOR_S3_RETENTION`, `_REQUIRE_LOCK` | The object lock's retention; whether the start refuses a bucket without object lock | `P400D`, `true` |
    | `TINFIRM_AUDIT_ANCHOR_FILE_DIR` | The directory of the `file` sink | none |
    | `TINFIRM_AUDIT_ANCHOR_SYSLOG_HOST` / `_PORT` / `_PROTOCOL` | The collector of the `syslog` sink; `tcp`, `tls` (the JVM's trust store) or `udp` | none, `6514`, `tls` |

    The Compose file mounts a volume of its own for the `file` sink, commented out as an example; the Helm chart takes the sink's values and the Secret of the keys, and the guide shows MinIO with object lock for both.

### Why this is enough before 1.0

It closes Q6 for the threat ADR-0010 names, the database writer without the server's secrets, with the parts that are cheap and already familiar to the code: an Ed25519 signature (the licence's), an S3 client (the session bundles'), the daily check's comparison, and the export's canonical form. The residual is bounded and stated: the events of the last interval, and an attacker inside the server between their entry and the key's rotation.

## Consequences

- **Q6 closes; R5 narrows.** A rewrite or truncation of history before the latest checkpoint, by anyone without the active key and write access to the sink, fails the tool and the daily check. What remains: the events since the latest checkpoint (at most the interval, an hour by default), an attacker with code execution in the server from that moment until the key is rotated (they hold the key and the sink's write access, though not the delete right under object lock), the `file` sink's dependence on the operator's volume, and the operator themselves, who controls everything; the latest checkpoint shown to admins and kept in exports (decision 6) is the company's own check against the operator.
- **A new asset**: the anchor's signing keys, in the deployment's configuration, and the sink's credential. The threat model gains them in §3 and §6.2, and 2.8's mitigation changes.
- **Q17 matters more.** The server holds both the owner password and the anchor's key; running migrations in a separate step would leave a compromised server without the owner password, so it could no longer rewrite events at all.
- **Operators have one more thing to configure,** and the default `none` leaves Q6 open on an installation that does nothing; the start's warning and the inbox item say so.
- **Writes add little.** One read of every head per interval and one small object; nothing on the path of an audit event.
- **The checkpoint's format is a contract,** as the canonical form is: a change is a new `v`, and the tool reads every version it ever wrote.
- **A restored backup** carries an older chain than the latest checkpoints name: the tool reports `SHORTER` for every chain, as it should, and the operator records the restore. A later checkpoint names the restored heads again.

## Alternatives considered

- **(b) An external append-only service as the only anchor.** Stronger where a third party runs it, but a service of many kinds per customer, a public log that leaks an installation's activity, and nothing without the internet. Its SIEM half is the `syslog` sink now and Enterprise's stream later.
- **(c) Alone.** Relies on admins writing the value down, and on the server showing the truth. Kept as a complement (decision 6).
- **(d) RFC 3161 alone.** Needs a TSA reachable from every installation, does not close truncation while its tokens live in the database, and brings ASN.1 and a TSA's certificate chain. A later addition to the checkpoint.
- **An HMAC with a shared key in place of a signature.** Simpler, but whoever verifies must hold the key that signs, so an auditor could forge. Not chosen.
- **The signing key in the vault** (`instance_key`). It needs no new configuration, but its wrapped key and the list of what the server trusts would be rows of the database the anchor must not depend on. Not chosen.
- **One checkpoint per chain, or per event.** Per chain multiplies objects by the companies of an instance for no gain in what is detected; per event puts the sink on the path of every audit write and makes it a reason for a refused change. Not chosen.
- **Refusing audit events while the sink fails** (fail closed). It would turn an object store's outage into an outage of every audited change. Not chosen; the failure is an alarm and a gap the tool reports.

## Open questions for the owner

1. **The default sink.** `none` with a warning and an inbox item (recommended: nothing to set up for a trial, and a sink cannot be guessed), or `file` on a volume that the chart and Compose create, so every installation is anchored from its first start, more weakly.
2. **Editions.** The tool and the checkpoints in the MIT core (recommended, decision 9), or the tool under `ee/` as part of "proving it is paid".
3. **The interval.** An hour (recommended), or shorter, which narrows the window of the last checkpoint at the cost of more objects.
