# Anchor the audit

Every company's audit log is a hash chain: each event's hash covers the event before it, so a change shows as a broken chain ([The audit log](../admin-guide/audit-log.md)). A chain alone cannot stop one attacker: whoever can write Tinfirm's database as its owner or a superuser can change an event, recompute every hash after it, and leave a chain that verifies. The anchor stops that attacker. A worker writes a **signed checkpoint** of the chains' heads, at an interval, to a place that whoever writes the database cannot change. From then on a rewritten or cut chain disagrees with its checkpoint: the daily check fails, the verification fails, and an offline tool shows where.

This page is for the operators who deploy Tinfirm. The decision behind it is [ADR-0111](https://github.com/flowtivo/tinfirm/blob/main/docs/adr/0111-audit-chain-heads-anchored-outside-the-database.md).

!!! info "Pro and Enterprise"

    The anchor needs a Pro or Enterprise licence. Under Community, Tinfirm writes no checkpoint and the anchor's status answers `403 EDITION_REQUIRED`. Checkpoints written while a licence held the anchor stay in the sink, and the tool reads them in any edition.

## What it protects against

| Who | Without the anchor | With the anchor |
|---|---|---|
| A database administrator, a superuser, whoever holds the owner password or restores an edited backup | Rewrites or cuts the history, recomputes the chain, and nothing shows | Every change before the latest checkpoint shows; the events of the last interval (an hour by default) do not |
| Code running in the Tinfirm server, which holds the owner password and the signing key | As above | Checkpoints already in a locked bucket stay; it can sign new ones until you rotate the key |
| The operator, who holds the database, the key and the sink | As above | Not protected: the checkpoint that admins note from the verification, and keep in exports, is the company's own check |

## Choose a sink

| Sink | Where the checkpoints go | Protection | Use it when |
|---|---|---|---|
| `s3` | One object per checkpoint in a bucket with object lock in compliance mode: nobody can delete or overwrite it before its retention ends, Tinfirm and the bucket's owner included | Strong | You have S3, MinIO, Ceph RGW or another store with object lock. Recommended |
| `syslog` | One RFC 5424 message per checkpoint, over TLS, TCP or UDP, to a collector or SIEM | As strong as the collector is apart from the database | You already keep logs in a SIEM that the database's administrators cannot change |
| `file` | One file per checkpoint in a directory | Only as strong as who else can write the volume | A small installation, with the directory on a volume of its own, ideally WORM or append-only, copied elsewhere by a job |
| `none` | Nowhere | None: the server warns at every start | The default |

Every sink works on an air-gapped network: MinIO inside it, a volume, or an internal collector.

## Make a key

The checkpoints are signed with an Ed25519 key that lives in the deployment's configuration, never in the database. Make one with the tool in the server's image:

```sh
docker run --rm --user "$(id -u)" -v "$PWD:/keys" --entrypoint tinfirm-audit-verify \
  ghcr.io/flowtivo/tinfirm-server@sha256:<digest> keygen k2026 /keys
```

It writes `k2026.key`, the private key, readable by you alone, and `k2026.pub`, the public key. A key id is 1 to 32 lowercase letters and digits; a year or a date is a good one.

- Put `k2026.key` in a Secret: the server reads it as `TINFIRM_AUDIT_ANCHOR_KEYS_K2026_PRIVATE`.
- Give `k2026.pub` to whoever verifies, out of band: your security team, your auditors. The verification trusts only the public keys it is given.

## Configure

### Helm

```yaml
auditAnchor:
  sink: s3
  interval: PT1H
  existingSecret: tinfirm-audit-anchor    # k2026.key
  activeKey: k2026
  keys:
    - kid: k2026
  s3:
    endpoint: https://minio.example.internal
    bucket: tinfirm-audit-checkpoints
    pathStyle: true
    retention: P400D
    existingSecret: tinfirm-audit-anchor-s3   # access-key-id, secret-access-key
```

```sh
kubectl create secret generic tinfirm-audit-anchor --from-file=k2026.key
kubectl create secret generic tinfirm-audit-anchor-s3 \
  --from-literal=access-key-id=<id> --from-literal=secret-access-key=<secret>
```

For the `file` sink, set `auditAnchor.file.existingClaim` to a PersistentVolumeClaim of its own; the chart mounts it at `/var/lib/tinfirm/audit-checkpoints`. For `syslog`, set `auditAnchor.syslog.host`, `port` and `protocol`. With `networkPolicy.egress.enabled`, add the bucket's or the collector's address to `networkPolicy.egress.extra` when it is not a public address on port 443.

### Docker Compose

In `deploy/compose/.env`:

```sh
TINFIRM_AUDIT_ANCHOR_SINK=s3
TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY=k2026
TINFIRM_AUDIT_ANCHOR_S3_ENDPOINT=https://minio.example.internal
TINFIRM_AUDIT_ANCHOR_S3_PATH_STYLE=true
TINFIRM_AUDIT_ANCHOR_S3_BUCKET=tinfirm-audit-checkpoints
TINFIRM_AUDIT_ANCHOR_S3_ACCESS_KEY_ID=<id>
TINFIRM_AUDIT_ANCHOR_S3_SECRET_ACCESS_KEY=<secret>
```

The key goes in a file of its own, `deploy/compose/.env.audit-anchor`, apart from `.env`, on one line with its line breaks written as `\n`:

```sh
printf 'TINFIRM_AUDIT_ANCHOR_KEYS_K2026_PRIVATE=%s\n' "$(awk '{printf "%s\\n", $0}' k2026.key)" \
  > deploy/compose/.env.audit-anchor
chmod 600 deploy/compose/.env.audit-anchor
```

The `file` sink writes to the Compose volume `audit-checkpoints`. On a host whose administrators also run the database it protects little: copy the directory off the host, or use `s3` or `syslog`.

Every variable is listed in [Configuration](../reference/configuration.md#audit-and-retention).

### A bucket with object lock

Object lock can only be turned on when the bucket is made. With MinIO's client:

```sh
mc mb --with-lock myminio/tinfirm-audit-checkpoints
```

Give the server a key that can write, read and list the bucket, and nothing else: no `DeleteObject`, no `BypassGovernanceRetention`, no other bucket. On AWS:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:PutObjectRetention", "s3:GetObject", "s3:GetObjectVersion",
               "s3:ListBucket", "s3:ListBucketVersions", "s3:GetBucketObjectLockConfiguration"],
    "Resource": ["arn:aws:s3:::tinfirm-audit-checkpoints", "arn:aws:s3:::tinfirm-audit-checkpoints/*"]
  }]
}
```

The server refuses to start when the bucket says it has no object lock. A store without object lock can still be used with `TINFIRM_AUDIT_ANCHOR_S3_REQUIRE_LOCK=false`; the checkpoints are then only as safe as the bucket's permissions.

## Check that it runs

At every start the server logs the sink and the public keys it trusts. An instance admin reads the anchor's status:

```sh
curl -b cookies.txt https://tinfirm.example.com/api/admin/audit-anchor
```

| Field | Meaning |
|---|---|
| `inForce` | The licence holds the anchor and a sink is configured: checkpoints are written |
| `sink`, `location` | The sink, and the bucket and prefix, the directory or the collector |
| `instanceId` | The instance's folder in the sink, made at the first checkpoint |
| `activeKeyId`, `publicKeys` | The key that signs, and every key the server trusts with its public key |
| `lastCheckpoint` | The latest checkpoint written: its number, time, key, place and how many chains it names |
| `lastSuccessAt`, `lastFailureAt`, `lastFailure`, `consecutiveFailures` | The writer's health |

A checkpoint is written every interval while some chain grew; an idle instance writes none. When a write fails, the server logs it at `ERROR`, counts it, and tries again after five minutes. **No audit event ever waits for the sink**: while it is down, the events since the last checkpoint are simply not anchored yet. Alert on `consecutiveFailures` above zero, or on the `ERROR` line `The audit anchor could not write a checkpoint`.

Where the anchor runs, a company's daily check fails **ANCHOR_MISMATCH** when its chain disagrees with a checkpoint, and its Owners and Admins see it in their inbox. Their verification names the checkpoint the chain was compared with.

## Verify offline

`tinfirm-audit-verify verify` needs neither the server nor write access to the database. It reads the checkpoints from the sink, the public keys from its command line, and the chains from a read-only database connection or from exports.

Against the database, every chain at once:

```sh
docker run --rm -v "$PWD:/work" -e TINFIRM_AUDIT_VERIFY_JDBC_PASSWORD \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY \
  --entrypoint tinfirm-audit-verify ghcr.io/flowtivo/tinfirm-server@sha256:<digest> verify \
  --checkpoints s3://tinfirm-audit-checkpoints/audit-checkpoints/<instance id>/ \
  --s3-endpoint https://minio.example.internal --s3-path-style \
  --public-key k2026=/work/k2026.pub \
  --jdbc-url jdbc:postgresql://db.example.internal:5432/tinfirm --jdbc-user tinfirm_audit_reader
```

The database role needs `SELECT` on `audit_event` and nothing else: `CREATE ROLE tinfirm_audit_reader LOGIN PASSWORD '…'; GRANT SELECT ON audit_event TO tinfirm_audit_reader;`. Give the S3 key read rights only.

Against exports, one chain each, which a company's Owner can give you (`--export audit-<company>-1-88140.ndjson`, repeated for several). For a directory, `--checkpoints /var/lib/tinfirm/audit-checkpoints/<instance id>`; for syslog, `--checkpoints syslog:<file>` with the collector's messages exported to a file.

| Line | Meaning |
|---|---|
| `checkpoints 1..1234 read (keys …)` | The checkpoints found, their time span and the longest time between two |
| `chain <id> seq 1..88140 OK, anchored to seq 88123 (checkpoint 1234)` | The chain recomputes and agrees with every checkpoint that names it |
| `MISMATCH at seq N: the hash differs from checkpoint C` | The event at a checkpoint's head has another hash: the chain was rewritten and recomputed |
| `SHORTER: the last event is seq N, checkpoint C names seq M` | The chain was cut below a checkpoint |
| `BROKEN at seq N` | The chain does not recompute from there |
| `MISSING (N -> M)`, `BROKEN LINK` | A checkpoint is gone, or does not follow the one before |
| `BAD SIGNATURE`, `OVERWRITTEN`, `DISTRUSTED` | A checkpoint does not verify with a key you gave, has a second version in the bucket, or was signed by a distrusted key after its compromise |

It exits with `0` when everything verified, `1` on any finding, `2` when it was called wrongly or cannot read a source. A restored backup shows as `SHORTER` for every chain that grew since: record the restore, and the next checkpoint names the restored heads.

## Rotate the key

1. Make the next key: `tinfirm-audit-verify keygen k2027`.
2. Add `k2027.key` to the Secret, add `- kid: k2027` to `auditAnchor.keys` (Compose: a line `TINFIRM_AUDIT_ANCHOR_KEYS_K2027_PRIVATE=…`), make it the `activeKey`, and restart.
3. Retire the old key: replace its private key with its public key (Helm: `retired: true` and `k2026.pub` in the Secret; Compose: `TINFIRM_AUDIT_ANCHOR_KEYS_K2026_PUBLIC=…`), and restart. It verifies the checkpoints it signed and signs nothing.
4. Give `k2027.pub` to whoever verifies. Keep `k2026.pub` as long as you keep the checkpoints it signed.

After a compromise of the key, rotate at once, and verify with `--distrust k2026@<time of the compromise>`: the tool fails every checkpoint of that key that the sink stored after that time, or cannot date. In a locked bucket, checkpoints stored before the compromise stay trustworthy.

## The checkpoint's format

You can check the checkpoints with a tool of your own. Each is one line of JSON:

```json
{"checkpoint":{"chains":[{"companyId":null,"hash":"…","seq":5512},{"companyId":"…","hash":"…","seq":88123}],"instance":"…","issuedAt":"2026-11-07T10:00:00.000000Z","keyId":"k2026","kind":"tinfirm.audit.checkpoint","number":1234,"prev":"…","v":1},"signature":"…"}
```

- `checkpoint` is in canonical form: object keys sorted, no whitespace, as the audit's events are. `signature` is the base64 of an Ed25519 signature over its UTF-8 bytes, by the key `keyId`.
- `chains` holds the heads that moved since the checkpoint before (every chain's in the first): `companyId` (null for the instance's chain), and the `seq` and `hash` of the chain's last event then.
- `number` counts from 1, and `prev` is the hex SHA-256 of the previous checkpoint's canonical form (64 zeros before the first).
- In a bucket or a directory, checkpoint `number` of instance `instance` is `<prefix><instance>/<number in 12 digits>.json`.

A chain verifies against the checkpoints when, for every checkpoint that names it, its event at `seq` exists and has that `hash`.
