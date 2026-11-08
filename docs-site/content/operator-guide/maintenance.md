# Upgrade, back up and rotate secrets

This page keeps a Tinfirm installation current and its secrets fresh: upgrading the Helm release and LiteLLM, backing up before you do, rotating each Secret, upgrading runner hosts, and the same tasks on the Compose stack. It is for the operators who installed Tinfirm with [Helm](helm.md) or [Docker Compose](quick-start.md).

!!! info "Read the release notes first"

    Each release's notes, on its page of [Tinfirm's GitHub Releases](https://github.com/FLOWTIVO/tinfirm/releases), say what to do before and after you upgrade to it. v1.0.0 is the first release: its notes list the steps for an installation that ran a development build. The development branch's charts `0.0.0-sha-<commit>` and images tagged `sha-<commit>` are not releases ([What is not signed](verify-images.md#what-is-not-signed)); the steps below work the same for them.

## Before you upgrade

1. **Verify the new images and chart**, and note their digests: [Verify your images](verify-images.md).
2. **Back up both databases**, Tinfirm's and LiteLLM's ([Back up](database.md#back-up)). With point-in-time recovery, note the time just before you start. The chart takes no backup itself; a pre-upgrade hook that does is planned.
3. **Check that your Secrets hold what the new version needs.** The install notes of `helm upgrade` list every Secret the pods wait for.

## Upgrade with Helm

Change the image digests in your values file to the new ones, then upgrade with the new chart:

```sh
helm upgrade tinfirm oci://ghcr.io/flowtivo/charts/tinfirm \
  --version <new version> \
  --namespace tinfirm \
  --values values-prod.yaml
kubectl -n tinfirm rollout status deployment/tinfirm
```

What happens:

1. Kubernetes starts a new server pod beside the old one (`maxSurge: 1`, `maxUnavailable: 0`). The old pod keeps serving.
2. The new pod migrates the database as `tinfirm_owner`. The startup probe allows up to 5 minutes. Migrations only move forward: there is no downgrade.
3. Once the new pod is ready, the old one leaves the Service, finishes the requests in flight within 30 seconds, and stops.
4. Runner hosts reconnect to the new pod. Their running turns carry on: the server gives every run that holds a slot at least 2 minutes to report back after it starts.
5. The chart's runner pod, if you run it ([The runner pod](helm.md#the-runner-pod)), drains when its image or settings change: it takes no new runs, lets the active ones finish within `runner.terminationGracePeriodSeconds`, then the new pod starts and takes back the runs that did not finish. Rebuild the VM image with the new release's runner host too.

A change to the chart's non-secret configuration rolls the pods by itself: the pod template carries a checksum of the server's ConfigMap. A change to a Secret does not ([Rotate a Secret](#rotate-a-secret)).

From a development chart with the model port (`model-api`, before the change that removed it), Helm refuses a values file that still sets `litellm.modelApi`: move its `from` to `litellm.from` and drop the rest. The services those peers run then call `http://<release>-litellm.<namespace>.svc:4000/v1` instead of port 4001, and reach LiteLLM's admin API there too: give each a key made with `key_type` `llm_api` before it moves ([The company's own services](model-gateway.md#the-companys-own-services)).

### Roll back

`helm rollback` puts back the previous chart and image, but not the database: the older server may not start on a schema the newer one migrated. To go back after a migration:

1. Scale the server and LiteLLM to zero: `kubectl -n tinfirm scale deployment/tinfirm deployment/tinfirm-litellm --replicas=0`.
2. Restore both databases to the point before the upgrade ([Restore](database.md#restore)). Everything done since is lost.
3. `helm rollback tinfirm <revision>`, which also sets the replicas back.

When you can, fix forward with the next version instead.

### Upgrade LiteLLM

The chart pins LiteLLM's image by tag and digest, and a Tinfirm release moves the pin: the server is checked against that version of LiteLLM. Keep the pinned version unless a release changes it.

LiteLLM migrates its own database when it starts with a new image, and does not start when the migration fails. Back up the `litellm` database before an upgrade that changes `litellm.image`. Its startup probe allows 10 minutes. The server keeps working while LiteLLM restarts, but model requests fail until LiteLLM is ready again: upgrade it when few turns run.

## Back up

Back up Tinfirm's and LiteLLM's databases together, with point-in-time recovery, and keep the keys that are not in them: [Prepare PostgreSQL](database.md#back-up). Besides the databases, keep in your secret manager:

| What | Why |
|---|---|
| The vault's master keys (`tinfirm-vault`) | Without them, nobody can decrypt the company secrets in a restored database |
| LiteLLM's salt key | Without it, a restored LiteLLM database cannot decrypt its provider keys |
| Your values file | With the digests you deployed, so that you can deploy the same again |
| The audit anchor's public keys, and its checkpoints if they are in a directory (Pro and Enterprise) | To verify the restored audit against the checkpoints written before the restore: [Anchor the audit](audit-anchor.md) |

## Rotate a Secret

The pods read their Secrets when they start. Changing a Secret restarts nothing, so restart the Deployment after each change:

```sh
kubectl -n tinfirm rollout restart deployment/tinfirm
```

| Secret | How |
|---|---|
| Database passwords | Change the password in PostgreSQL (`ALTER ROLE tinfirm_app PASSWORD '…'`, or `tinfirm_owner`), then in the Secret `tinfirm-database`, then restart the server. Between the steps, new connections fail: do them in quick succession. |
| Vault master key | Add a new key, rewrap, then retire the old one: [below](#rotate-the-vault-master-key) |
| OIDC client secret | Create a new secret at the provider, put it into the provider's Secret, restart the server, then revoke the old one at the provider. If the provider holds only one secret at a time, sign-in through it fails until the restart. |
| Bootstrap | Nothing to rotate: its password is read only while the instance has no user, so changing it changes no user. Its email is also, at every start, the owner of a declared company that names no `owner` and has no Owner: set `owner` on every company before you delete the Secret. |
| LiteLLM master key | Put a new `sk-…` value into `master-key` of the LiteLLM Secret, then restart both Deployments: `kubectl -n tinfirm rollout restart deployment/tinfirm-litellm deployment/tinfirm`. The keys of members and service accounts stay valid. |
| LiteLLM salt key | **Never.** It encrypts the provider keys in LiteLLM's database, and with another salt key LiteLLM cannot decrypt them. If it leaked together with the database or a backup of it, treat the provider keys as leaked: revoke them at the providers and add new ones ([Run the model gateway](model-gateway.md)). |
| LiteLLM database password | As the database passwords above, for LiteLLM's role, in the LiteLLM Secret or in `litellm.database.existingSecret`, then restart `tinfirm-litellm`. A password that a database operator rotates needs the same restart. |
| Error reports DSN | Change it in its Secret, then restart the server: it reads the DSN when it starts |
| Runner pod token | Put a new `tfr_…` token into the runner Secret, restart the server, which replaces the old token at its start and closes the pod's connection, then the pod: `kubectl -n tinfirm rollout restart deployment/tinfirm-runner` |
| DigitalOcean API token | Create a new token, put it into its Secret, restart the runner pod, then revoke the old one |
| Audit anchor key | Add the next key, make it active and restart, then keep the old one's public key only: [Rotate the key](audit-anchor.md#rotate-the-key) |
| Audit anchor bucket key | Create a new key for the bucket, put it into `auditAnchor.s3.existingSecret`, restart the server, then revoke the old one |

The LiteLLM keys of members and service accounts rotate by themselves, every 30 days by default: [Model access and budgets](../admin-guide/model-access.md).

### Rotate the vault master key

The vault encrypts each company secret with a data key of its own, and wraps the data keys with the master key; each session bundle has a data key of its own too, wrapped the same way. Rotating the master key rewraps the data keys, the bundles' included; the secrets and bundles themselves stay as they are.

1. **Add the new key.** Generate it, and put both keys into `keys` of the Secret `tinfirm-vault`, the old one first, with `active-key` naming the new one:

    ```sh
    printf 'k1:<old key>,k2:%s' "$(openssl rand -base64 32)" > vault-keys
    printf 'k2' > vault-active-key
    kubectl -n tinfirm create secret generic tinfirm-vault \
      --from-file=keys=./vault-keys --from-file=active-key=./vault-active-key \
      --dry-run=client -o yaml | kubectl apply -f -
    ```

2. **Restart the server.** New secrets are now wrapped with `k2`; existing ones are still read with `k1`.
3. **Rewrap.** The web app has no button for it: call `POST /api/admin/vault/rewrap` as an instance admin. The endpoint takes a signed-in session with its CSRF token, never a personal access token or another bearer token.

    === "Browser"

        Sign in to Tinfirm as an instance admin, through any sign-in method, open your browser's developer console on Tinfirm's page, and run:

        ```js
        await fetch('/api/admin/vault/rewrap', {
          method: 'POST',
          headers: { 'X-XSRF-TOKEN': decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1]) },
        }).then((r) => r.json());
        ```

    === "curl"

        For an instance admin with a local password. Put the credentials in a file, so that the password stays out of your shell history, then sign in and call the endpoint with the session's cookies:

        ```json title="login.json"
        {"email": "admin@example.com", "password": "…"}
        ```

        ```sh
        url=https://tinfirm.example.com
        jar=$(mktemp)
        xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar"; }
        curl -sS -c "$jar" -b "$jar" -o /dev/null "$url/api/auth/providers"      # sets the CSRF cookie
        curl -sS -c "$jar" -b "$jar" -o /dev/null -H "X-XSRF-TOKEN: $(xsrf)" \
          -H 'Content-Type: application/json' --data @login.json "$url/api/auth/login"
        curl -sS -c "$jar" -b "$jar" -X POST -H "X-XSRF-TOKEN: $(xsrf)" "$url/api/admin/vault/rewrap"
        curl -sS -c "$jar" -b "$jar" -o /dev/null -X POST -H "X-XSRF-TOKEN: $(xsrf)" "$url/api/auth/logout"
        rm -f "$jar" login.json
        ```

    The answer names the active key and the number of data keys rewrapped, for example `{"activeKeyId": "k2", "rewrapped": 42}`. The rewrap is recorded in the instance audit log, and running it again does no harm.

4. **Retire the old key** once no backup you might restore was taken before the rewrap, and not before 30 days after it: the rewrap does not rewrap the data keys of session bundles (the agents' saved sessions, which last 30 days after a conversation's last turn, `TINFIRM_SESSION_BUNDLES_RETENTION`), so a bundle made before the rotation needs the old key until it goes. Then remove `k1:…` from `keys`, and restart again. Keep `k1` in your secret manager until then. Removed earlier, a bundle whose key is gone cannot be opened, and its conversation's next turn starts a new session.

A data key wrapped with a key that is no longer configured makes the rewrap fail with the id of the missing key: put that key back, restart, and rewrap again.

An installation that still runs on the development key of `deploy/compose/.env.example` (`dev1`) does not start once `TINFIRM_PUBLIC_URL` is not a localhost URL: the server refuses that key as the active key. Rotate off it with these steps, a key of your own as the new active key and `dev1` kept as the old one; the server reads it as a retired key and warns at every start until you remove it.

## Change the public URL

!!! warning "Everything that knows Tinfirm's address must learn the new one"

    Tinfirm's address is built into the registrations other services hold. After you change `publicUrl` (or `TINFIRM_PUBLIC_URL`), sign-ins break until each of them is updated.

| What | What changes | What to do |
|---|---|---|
| Identity providers | The redirect URI, `<public URL>/login/oauth2/code/<id>` | Register the new one at each provider before you switch |
| Systems' **Sign-in app** | The **Callback URL**, `<public URL>/api/oauth/callback`, where a system sends people back after they sign in to it | Register the new **Callback URL** in each system's app at its provider (GitHub, Jira Cloud, GitLab, Slack). Until then, signing in to that system fails. |
| MCP servers that identify Tinfirm by its metadata document | Tinfirm's client id there, `<public URL>/oauth/client-metadata.json` | Nothing to register, but such a server sees a new client: people connect their accounts on it again |
| AI clients | The issuer of Tinfirm's authorization server and the address of each company's MCP endpoint | Every AI client is signed out: people add Tinfirm to their clients again with the new address |
| Runner hosts | The server they connect to | Point each host at the new address with `tinfirm-runner-host login --server <new address>`, then restart it ([Install and run runner hosts](runner-hosts.md)) |

Keep the old host name pointing at Tinfirm, redirecting to the new one, for as long as people still use links to it.

## Upgrade runner hosts

Upgrade the server first, then its runner hosts: a runner host must not run ahead of its server. A runner host drains before it upgrades, so that running turns finish, and rebuilds its runner images whose Dockerfiles changed when it starts again. [Install and run runner hosts](runner-hosts.md) has the procedure.

## The Compose stack

The Compose stack keeps all of its settings and secrets in `deploy/compose/.env`, and its databases in the Docker volume `tinfirm_postgres-data`. [Try Tinfirm with Docker Compose](quick-start.md#look-after-the-stack) lists the `make` targets.

### Back up the stack

`.env` holds the vault's master key and LiteLLM's salt key: keep a copy of it, encrypted, apart from the dumps. Then dump both databases:

```sh
docker exec tinfirm-postgres-1 pg_dump -U postgres -Fc tinfirm > tinfirm-$(date -u +%Y%m%dT%H%MZ).dump
docker exec tinfirm-postgres-1 pg_dump -U postgres -Fc litellm > litellm-$(date -u +%Y%m%dT%H%MZ).dump
```

A dump is a backup of one moment, unlike point-in-time recovery: take one before every upgrade, and on a schedule.

### Upgrade the stack

From the Compose files of a release ([Try Tinfirm with Docker Compose](quick-start.md#1-start-the-stack)): download and check the new release's `tinfirm-compose-<version>.tar.gz` against its signed `SHA256SUMS` as there, and unpack it beside the old directory. Copy your `.env` into the new directory, and name the new release's image in it, by the digest you verified ([Verify your images](verify-images.md)):

```sh title=".env"
TINFIRM_SERVER_IMAGE=ghcr.io/flowtivo/tinfirm-server@sha256:<digest>   # the image tagged v<version>
```

Then start the new version from the new directory. Compose recreates the containers that changed and keeps the volumes, which belong to the project `tinfirm` whatever the directory:

```sh
docker compose up -d --no-build --remove-orphans
```

From a checkout of the repository, for development, take the new commit and rebuild:

```sh
git pull
make up
```

`make up` builds the server from the checkout, under the name `tinfirm-server:local`, and recreates the containers that changed. To run a published image from a checkout instead, name it in `deploy/compose/.env` as above and start without building: `docker compose -f deploy/compose/compose.yaml --env-file deploy/compose/.env up -d --no-build --remove-orphans`. Do not mix the two ways: `make up` builds and tags whatever image `.env` names.

A stack that ran a development build with the model port (`model-api`) keeps that container running after `make up`: remove it with `--remove-orphans`, as in the commands above. Nothing reads the `TINFIRM_MODEL_API_*` lines of `.env` any more: remove them.

Use the Compose files of the same version as the image, and keep your `.env`. When Compose stops with `required variable … is missing a value`, the new version needs a setting your `.env` lacks: copy it from `.env.example`, with a value of your own for every secret.

The server migrates the database when it starts, as on Kubernetes. Nothing rolls back by itself. To go back to an older version after a migration, restore the dump you took before the upgrade, then start the older version:

```sh
docker compose -f deploy/compose/compose.yaml --env-file deploy/compose/.env stop server
docker exec tinfirm-postgres-1 dropdb -U postgres --force tinfirm
docker exec -i tinfirm-postgres-1 pg_restore -U postgres --create -d postgres < tinfirm-<time>.dump
```

`--create` recreates the database with its owner `tinfirm_owner` and its grants. Restoring loses every change since the dump.

### Rotate secrets in `.env`

Edit `.env`, then run `make up`, or the `docker compose … up -d` above: Compose recreates the containers whose settings changed.

| Setting | How |
|---|---|
| `TINFIRM_APP_PASSWORD`, `TINFIRM_OWNER_PASSWORD` | The roles were created with the first values only. Change the password in PostgreSQL first, for example `docker exec tinfirm-postgres-1 psql -U postgres -c "ALTER ROLE tinfirm_app PASSWORD '…'"`, then in `.env`, then start the stack again. |
| `TINFIRM_VAULT_KEYS`, `TINFIRM_VAULT_ACTIVE_KEY` | As on Kubernetes: `TINFIRM_VAULT_KEYS=k1:<old>,k2:<new>` and `TINFIRM_VAULT_ACTIVE_KEY=k2`, start the stack, [rewrap](#rotate-the-vault-master-key), and later remove the old key |
| `LITELLM_MASTER_KEY` | Change it and start the stack: LiteLLM and the server are recreated with the new key |
| `LITELLM_DB_PASSWORD` | Change it and start the stack: the stack sets the new password on LiteLLM's role before LiteLLM starts |
| `LITELLM_SALT_KEY` | **Never** |
| `TINFIRM_BOOTSTRAP_ADMIN_*` | Nothing to rotate: they are read only while the instance has no user |
| `TINFIRM_AUDIT_ANCHOR_KEYS_*` in `.env.audit-anchor` | As on Kubernetes: [Rotate the key](audit-anchor.md#rotate-the-key) |
