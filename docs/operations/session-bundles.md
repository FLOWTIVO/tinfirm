# Session bundles

A session bundle keeps an agent's session of a conversation on the server between turns, so that the next turn resumes it on whichever company runner host runs it: another machine, a runner pod that moved, or a VM per run, which keeps nothing after its run. Without bundles, a turn that lands on another host starts a new session (`resumeOutcome` `NO_SESSION`): it works, but it costs tokens, and the agent loses what it read. This guide is for the operators of the server. The decision is [ADR-0097](../adr/0097-session-bundles.md); the binding rules are [CONVENTIONS §15.27](../CONVENTIONS.md#1527-session-bundles-adr-0097), and the host's side is in the [runner README](../../runner/README.md#session-bundles-protocol-v2).

Bundles are off by default (`TINFIRM_SESSION_BUNDLES_STORE=none`). Turn them on when a company runs on more than one company host, or on VMs per run. They are part of elastic runners, Pro's and Enterprise's ([ADR-0100](../adr/0100-elastic-runners-in-pro.md)): under Community, or once a licence has ended, the server hands out no bundle and refuses the hosts' uploads and downloads (`403 EDITION_REQUIRED`), and the bundles it holds still go as below.

## 1. What is kept, and where

- The runtime's session folders of the agent's home, and nothing else: no settings, credentials or caches. A gzip tar of at most 32 MiB, the newest per conversation and agent. The host packs a session folder only when it and every folder above it in the home are plain directories, never links (the agent owns `~/.codex` and the like, and a link there would pack another home's sessions); links inside are packed as links, which a restore leaves out.
- The server scans each bundle for the company's vault secrets and the known credential formats, and refuses one with a match. The host refuses to upload one that holds a credential of its run.
- Each bundle is sealed on the server with a data key of its own, which the vault's master key (`TINFIRM_VAULT_KEYS`) wraps. The store holds ciphertext only, under random names. Only the server reaches it; runner hosts never do.
- A bundle goes when a newer one replaces it, when its conversation ends, when its conversation's content is purged (retention, erasure), and 30 days after the conversation's last turn (`TINFIRM_SESSION_BUNDLES_RETENTION`). Worker replicas sweep every hour, and delete the store's objects that no bundle names.
- Only company hosts get bundles. A member's personal host keeps its homes on its own machine.

## 2. Make a bucket

Any storage that speaks the S3 API: DigitalOcean Spaces, AWS S3, MinIO, Ceph RGW, OpenShift Data Foundation's object gateway.

1. Create a private bucket for the bundles alone, in the region of the server. Do not turn on public access, a CDN or listing for anyone.
2. Create an access key that reaches this bucket only, with read, write and delete. On Spaces, a key with limited access to the one bucket; on AWS, an IAM user or role with `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket`, `s3:AbortMultipartUpload` and `s3:ListBucketMultipartUploads` on it. A bundle larger than 8 MiB goes up as a multipart upload; the sweeper aborts one that a stopped server left.
3. Add a lifecycle rule that deletes objects older than 37 days, and incomplete multipart uploads after a day, as a backstop behind the sweeper. With the AWS CLI, for Spaces in fra1:

   ```sh
   aws s3api put-bucket-lifecycle-configuration \
     --endpoint-url https://fra1.digitaloceanspaces.com \
     --bucket tinfirm-session-bundles \
     --lifecycle-configuration '{"Rules":[{"ID":"tinfirm-backstop","Status":"Enabled","Filter":{"Prefix":"session-bundles/"},"Expiration":{"Days":37},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'
   ```

## 3. Settings

| Variable | Meaning | Default |
|---|---|---|
| `TINFIRM_SESSION_BUNDLES_STORE` | `none`, `filesystem` or `s3` | `none` |
| `TINFIRM_SESSION_BUNDLES_S3_ENDPOINT` | The storage's endpoint, such as `https://fra1.digitaloceanspaces.com`; empty for AWS S3 | empty |
| `TINFIRM_SESSION_BUNDLES_S3_REGION` | The region of the signature. For Spaces, DigitalOcean's SDK examples keep `us-east-1` and name the datacenter in the endpoint | `us-east-1` |
| `TINFIRM_SESSION_BUNDLES_S3_BUCKET` | The bucket | empty |
| `TINFIRM_SESSION_BUNDLES_S3_PATH_STYLE` | `true` for MinIO and Ceph, which address a bucket by path rather than by host name | `false` |
| `TINFIRM_SESSION_BUNDLES_S3_PREFIX` | The objects' prefix in the bucket | `session-bundles/` |
| `TINFIRM_SESSION_BUNDLES_S3_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` | The key of section 2, from a Secret | empty |
| `TINFIRM_SESSION_BUNDLES_DIRECTORY` | The `filesystem` store's directory, writable by the server's user (uid 10001 in the image) and by nothing else | empty |
| `TINFIRM_SESSION_BUNDLES_RETENTION` | How long after a conversation's last turn its bundles go | `P30D` |

The server does not start with `s3` and no bucket or key, or with `filesystem` and no directory. `filesystem` is for one server on one machine and for the tests: several replicas need one store they all reach.

**Memory.** An upload streams ([ADR-0101](../adr/0101-closing-the-elastic-runners-gaps.md)): the server scans it as it arrives, and seals and writes it to the store a chunk at a time. An upload holds about a 1 MiB chunk of the bundle and of its ciphertext, the scan's window, and for S3 an 8 MiB part. At most 4 uploads run at once, so the bundles add about 50 MiB at most, whatever their size. The object is committed only once the scan found the bundle clean. A company host uploads one bundle at a time, and a body must arrive within 30 seconds, so a slow host holds one of the 4 uploads for at most that long and never holds up its own other runs for more than their turn (the security review of 2026-10-27, L24).

## 4. With Helm

The chart has no values of its own for bundles yet. Use `extraConfig`, `extraEnv` and an egress rule:

```sh
kubectl -n tinfirm create secret generic tinfirm-session-bundles \
  --from-literal=access-key-id='…' \
  --from-literal=secret-access-key='…'
```

```yaml
extraConfig:
  TINFIRM_SESSION_BUNDLES_STORE: s3
  TINFIRM_SESSION_BUNDLES_S3_ENDPOINT: https://fra1.digitaloceanspaces.com
  TINFIRM_SESSION_BUNDLES_S3_BUCKET: tinfirm-session-bundles

extraEnv:
  - name: TINFIRM_SESSION_BUNDLES_S3_ACCESS_KEY_ID
    valueFrom:
      secretKeyRef:
        name: tinfirm-session-bundles
        key: access-key-id
  - name: TINFIRM_SESSION_BUNDLES_S3_SECRET_ACCESS_KEY
    valueFrom:
      secretKeyRef:
        name: tinfirm-session-bundles
        key: secret-access-key

resources:
  requests:
    memory: 2Gi
  limits:
    memory: 2Gi

networkPolicy:
  egress:
    extra:
      # The bucket's endpoint. Narrow it to the storage's addresses where they are stable.
      - to:
          - ipBlock:
              cidr: 0.0.0.0/0
        ports:
          - protocol: TCP
            port: 443
```

An in-cluster store (MinIO, Ceph RGW) needs a rule to its pods and port instead, and `TINFIRM_SESSION_BUNDLES_S3_PATH_STYLE: "true"`.

## 5. With Compose

The Compose file passes the bundles' variables of section 3 from `deploy/compose/.env`, off by default. Set `TINFIRM_SESSION_BUNDLES_STORE` there:

- `s3`, with the bucket and key of section 2 (`TINFIRM_SESSION_BUNDLES_S3_*`, as in `.env.example`);
- or `filesystem`, for one server on one machine: the bundles go to the `session-bundles` volume, which only the server's user (uid 10001) can read. The directory is set for you.

Then `docker compose up -d server`. The Compose file gives the server 2 GiB (`TINFIRM_SERVER_MEM_LIMIT`), as the Helm values of section 4 do.

## 6. Keys and rotation

- **The bucket's key**: create a new one for the bucket, update the Secret, restart the Deployment, revoke the old key. The bucket holds ciphertext only.
- **The vault's master key**: the rewrap (`POST /api/admin/vault/rewrap`) rewraps the bundles' data keys with the secrets' ([ADR-0101](../adr/0101-closing-the-elastic-runners-gaps.md)). After a rotation, run it, and then remove the retired key from `TINFIRM_VAULT_KEYS`, as for the secrets. The rewrap refuses to run while a bundle's key is wrapped under a master key that is no longer configured.

## 7. Turn it off

Set `TINFIRM_SESSION_BUNDLES_STORE=none` and restart. The hosts keep their homes as before, and the server no longer sweeps: the bucket's lifecycle rule deletes what is left in it. Delete the bucket's objects if you will not turn bundles on again.

## 8. What to watch

- The run's log lines say when a bundle was stored, refused (and why), too large, or restored, and what of a bundle was left out.
- The server logs each store and refusal with the run and the conversation, each missing or unreadable object, and each sweep.
- A run's `resumeOutcome` shows whether the next turn resumed. Many `NO_SESSION` with bundles on point to refusals, sizes over 32 MiB, or a store that fails.
