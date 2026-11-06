# Continuous delivery to a single host

This guide sets up a Compose host to deploy every commit on `main` that passes CI. A host that runs only a runner host, while the server runs elsewhere (Kubernetes), uses runner mode: section 8. CI publishes the build; a systemd timer on the host pulls it. CI never connects to the host, and the host needs no inbound access.

For production, install a release with Helm instead: see [helm.md](helm.md).

## 1. What CI publishes

After a push to `main`, when the gate jobs of `ci.yml` pass (the security scans do not hold it back), these jobs publish that commit:

| Job | After | Publishes |
|---|---|---|
| `publish` | server, web, deploy lint, image, runner | The server image `ghcr.io/flowtivo/tinfirm-server:sha-<full sha>`, and the tag `main` on it; the Helm chart `oci://ghcr.io/flowtivo/charts/tinfirm:0.0.0-sha-<full sha>`, with `appVersion: sha-<full sha>`, so its default images are this commit's ([ADR-0080](../adr/0080-helm-chart-published-to-ghcr.md), [ADR-0090](../adr/0090-a-chart-for-any-cluster.md)) |
| `publish-services` | model API, deploy lint, image, runner | The model port's image `ghcr.io/flowtivo/tinfirm-model-api` and the runner pod's `ghcr.io/flowtivo/tinfirm-runner-host`, as `sha-<full sha>` and `main` |
| `rolling` | `publish` | The GitHub prerelease `rolling`, through the reusable workflow `runner-artifacts.yml` |
| `main-complete` | `publish`, `publish-services`, `rolling` | The tag `main-complete` on the server image `ghcr.io/flowtivo/tinfirm-server:sha-<full sha>`, in the same package: the newest commit of `main` whose images and prerelease `rolling` are all published. A deployment repository reads it to keep an environment on `main` ([ADR-0107](../adr/0107-a-deployment-repository-follows-the-registry.md)) |

The prerelease `rolling` moves with `main`: the tag `rolling` moves to the commit, the title is "Rolling (main <short sha>)", and its assets are replaced each time. Its assets have the same names as the assets of a versioned release, with `<version>` = `rolling-<short sha>` (7 characters) instead of `x.y.z`:

| Asset | Contents |
|---|---|
| `tinfirm-runner-host-<version>-x86_64-unknown-linux-musl.tar.gz` | The runner host, static Linux binary |
| `tinfirm-runner-host-<version>-aarch64-unknown-linux-musl.tar.gz` | The runner host, static Linux binary |
| `tinfirm-runner-host-<version>-aarch64-apple-darwin.tar.gz` | The runner host for macOS on Apple silicon |
| `tinfirm-runner-host-<version>-x86_64-pc-windows-msvc.zip` | Planned: only when the repository variable `RUNNER_WINDOWS_ENABLED` is `true`, after the Windows port of the runner host |
| `tinfirm-compose-<version>.tar.gz` | `deploy/compose` of the commit, and `LICENSE`. Committed files only, so never a `.env`. |
| `REVISION` | The full commit SHA |
| `SHA256SUMS` | `sha256sum` format, over every other asset |
| `SHA256SUMS.sig`, `SHA256SUMS.sigstore.json` | The signature of `SHA256SUMS` (cosign `sign-blob`, no transparency log), as base64 and as a cosign bundle: by the rolling key for `rolling`, by the release key for a versioned release (section 1.1) |

Each tarball holds one directory with the files in it. A runner host archive holds the binary, `LICENSE` and `THIRD-PARTY-LICENSES.txt`, the licences of the crates it is built with ([ADR-0082](../adr/0082-licence-notices-and-dependency-licences.md)). A versioned release (`release.yml`) gets the same assets from the same workflow, built from its tag.

### 1.1 Signed assets

A checksum beside the files proves nothing against whoever can replace the files: they replace `SHA256SUMS` too (M13 of the [security review of 2026-10-27](../reviews/2026-10-27-security-review.md)). So `SHA256SUMS` is signed, and a host checks the signature before it trusts any checksum in it.

| Release | Signed by | Where the private key is | Public key on the host |
|---|---|---|---|
| `v<version>` | The release key, the one that signs the images ([ADR-0081](../adr/0081-signed-release-images.md)) | Secrets `COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD` of the environment `release` (required reviewers); used by `release.yml`'s `sign-assets` on the release runners (`CI_RELEASE_RUNNER`, else GitHub-hosted `ubuntu-latest`), after the assets are on the Release | `cosign.pub`, a copy of `docs-site/content/cosign.pub` (CI checks they are the same) |
| `rolling` | The rolling key, a key pair of its own | Secrets `ROLLING_COSIGN_PRIVATE_KEY`, `ROLLING_COSIGN_PASSWORD` of the environment `rolling` (deployment branch `main`, no reviewers); used by `runner-artifacts.yml`'s `publish` on the CI runners | `cosign-rolling.pub` |

The rolling key is not the release key on purpose: it signs on the CI runners, beside pull-request jobs, after every push to `main`. A stolen rolling key lets someone sign assets of `rolling` that the hosts following it install, never a release or an image that customers verify. To replace the rolling key: `cosign generate-key-pair`, store the private key and its password in the environment `rolling`, commit the public key as `deploy/compose/auto-deploy/cosign-rolling.pub`, and run install.sh again on each host that follows `rolling`. While the environment holds no key, `rolling` is published unsigned, with a warning in the run.

`install.sh` copies both public keys from `deploy/compose/auto-deploy/` into `~/.config/tinfirm-auto-deploy/` and prints their fingerprints; the script never takes a key from a downloaded release. A host that follows `rolling` checks against `cosign-rolling.pub`, one that follows a versioned release or `latest` against `cosign.pub` (`AUTO_DEPLOY_PUBLIC_KEY` overrides it).

Order: `rolling` uploads `SHA256SUMS`, then its signature, then `REVISION`. A versioned release gets its signature after its assets: `sign-assets` downloads them, checks them against `SHA256SUMS` and `REVISION` against the tag, signs, and uploads. A host that sees the release before that waits (step 2). Re-running the assets of a release removes the old signature: re-run `sign-assets` after it.

By hand, with openssl or cosign:

```sh
openssl base64 -d -A -in SHA256SUMS.sig -out SHA256SUMS.der
openssl dgst -sha256 -verify cosign.pub -signature SHA256SUMS.der SHA256SUMS
cosign verify-blob --key cosign.pub --bundle SHA256SUMS.sigstore.json --insecure-ignore-tlog=true SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
```

No image with Claude Code is ever published. The runner host binary only embeds the Dockerfiles of the runner images, and every runner host builds the images itself (`tinfirm-runner-host images build`). After an upgrade, the new `run` rebuilds the images whose Dockerfiles changed before it connects, with the build arguments of the last `images build` ([ADR-0062](../adr/0062-runner-host-rebuilds-its-images.md)): the timer installs the binary and drains, and no step on the host is left to do.

Ordering and races:

- `rolling` runs after `publish`, so the server image `sha-<sha>` exists before a host can see the new `REVISION`. `publish-services` runs beside them and waits for neither, so a `REVISION` may name a commit whose model port image is not published yet: a Compose host then ends its pass without an attempt and pulls it at a later pass (section 2, step 3). `main-complete` waits for `publish`, `publish-services` and `rolling`, so an environment that follows that tag never pulls a model port image that does not exist yet, and a failed `publish-services` leaves the tag, and the environment, where they are.
- Each job has a concurrency group that never cancels a running job, so consecutive pushes publish one after the other. GitHub keeps one waiting run per group: with three quick pushes, the middle one may be skipped, and the last one publishes.
- The jobs move `main`, `rolling` and `main-complete` only while their commit is still the tip of `main`. A re-run of an older run publishes `sha-<sha>` but never moves them back.
- The release update replaces the assets in this order: the new versioned assets, `SHA256SUMS`, its signature (or, unsigned, the removal of the previous one), the tag and the release page, `REVISION`, then deletes the previous commit's assets. A host that reads `REVISION` and `SHA256SUMS` in between finds that they disagree, and tries again at its next pass.
- A failed macOS build (a GitHub-hosted runner, unless the repository variable `CI_RUNNER_MACOS` names a self-hosted Mac) does not hold back `rolling`: it ships without the macOS asset, and the failed job stays red. It does hold back a versioned release; re-run the release run's failed `assets` job: it builds on the release runners again, and `sign-assets` runs after it. Actions → Release assets → Run workflow with the release's tag attaches them too, built from that tag on the CI runners; re-run the release run's `sign-assets` after it (section 1.1).

## 2. What the host does

`tinfirm-auto-deploy` runs every 5 minutes (`tinfirm-auto-deploy.timer`: 2 minutes after boot, then 5 minutes after each run) as the deploy user. One pass:

1. Reads `REVISION` of the release `rolling`. If it is the deployed revision, the pass ends. If it is a revision that was deployed before (after a manual rollback, for example), it logs a warning and does nothing: auto-deploy never goes back. If the revision failed 3 times, it logs an error and does nothing.
2. Downloads `SHA256SUMS` and checks that it lists `REVISION` with the same checksum. Downloads `SHA256SUMS.sig` and checks it against the public key (section 1.1): a signature that does not verify stops the pass with an error and nothing is installed; a release without a signature (not signed yet) ends the pass with a warning, and the next pass looks again, unless `TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1`. Downloads the Compose tarball, and the runner host tarball for this machine (`uname -m` → `x86_64` or `aarch64` musl) only if a runner host is installed. Checks each file against `SHA256SUMS`. Then reads `REVISION` again: if `rolling` moved during the download, the pass ends and the next pass starts over.
3. Pulls `ghcr.io/flowtivo/tinfirm-server:sha-<rev>` and `ghcr.io/flowtivo/tinfirm-model-api:sha-<rev>` and checks their revision labels. When the model port's image cannot be pulled, the pass ends without an attempt, as it does for a release that is being updated: `publish-services` may still be running. The next pass tries again.
4. From here on, a failure counts as an attempt on this revision.
5. Unpacks the files. If the runner host binary differs from the installed one, runs the new binary with `--version`; a binary that cannot run stops the deploy here, before anything changed.
6. Backs up the database: `pg_dump -Fc` in `tinfirm-postgres-1` to `~/backups/tinfirm-auto-<UTC time>-<previous revision>.dump`, mode 600. It keeps the newest 10 of these dumps and never touches other files in the directory. A database container that exists but does not run stops the deploy; without any database container (a first deployment) there is nothing to back up.
7. Copies the Compose files over `~/tinfirm/deploy/compose`. It never writes `.env` from the tarball. It sets two lines in `.env`, `TINFIRM_SERVER_IMAGE=ghcr.io/flowtivo/tinfirm-server:sha-<rev>` and `TINFIRM_MODEL_API_IMAGE=ghcr.io/flowtivo/tinfirm-model-api:sha-<rev>`, adds LiteLLM's secrets (`LITELLM_MASTER_KEY`, `LITELLM_SALT_KEY`, `LITELLM_DB_PASSWORD`) with random values when they are missing, without logging them, and leaves the other lines as they are ([litellm.md, section 4.1](litellm.md#41-docker-compose)).
8. `docker compose -f compose.yaml --env-file .env up -d --no-build --remove-orphans`.
9. Waits up to 300 s until `http://127.0.0.1:<TINFIRM_HTTP_HOST_PORT>/api/system/info` answers. It fails at once when the server container exits or restarts.
10. Runner host, only if a runner host is installed (a binary at `~/.local/bin/tinfirm-runner-host`) and its checksum changed: keeps the old binary as `tinfirm-runner-host.prev`, installs the new one atomically (`.new`, `chmod 755`, rename), and asks for `systemctl reload tinfirm-runner`: it writes `~/.local/share/tinfirm-auto-deploy/runner-reload-request`, and the root path unit `tinfirm-auto-deploy-reload.path` reloads the unit. The runner drains: active runs finish, `run` exits, and systemd (`Restart=always`) starts the new binary ([runner/README.md, Drain and upgrades](../../runner/README.md#drain-and-upgrades)). A unit that is not active only gets the new binary for its next start.
11. Records the revision as deployed, keeps the downloaded assets in `~/.local/share/tinfirm-auto-deploy/revisions/<rev>/`, and prunes: the images and cached assets of the deployed revision and the 2 before it stay; older `sha-*` tags of the server image and of the model port's image are removed.
12. Logs one summary line: the revision, the commit subject (the server image's `dev.tinfirm.commit-subject` label; for a merge commit, the pull request's title), whether the runner host changed, and the backup.

**The model port's image follows the server's.** `compose.yaml` takes it from `TINFIRM_MODEL_API_IMAGE`, `tinfirm-model-api:local` by default, and `up --no-build` builds nothing, so the script sets that line with `TINFIRM_SERVER_IMAGE` (step 7), and `make deploy-host` sets it to the image it built and shipped ([litellm.md, section 4.1](litellm.md#41-docker-compose)). A value you set by hand is overwritten on the next deploy, like `TINFIRM_SERVER_IMAGE`. To keep an image of your own, such as `ghcr.io/flowtivo/tinfirm-model-api:dev` from `make model-api-image` loaded on the host, pause the timer first (section 5).

Everything goes to the journal: `journalctl -u tinfirm-auto-deploy`. Failures have the priority `err`, so `journalctl -u tinfirm-auto-deploy -p warning` shows only the problems. A pass with nothing to do logs nothing.

**On failure,** the pass logs the step, the attempt number, the containers and the last lines of the server log, and exits non-zero, so `systemctl --failed` shows the unit. The revision is not marked deployed, and the next pass tries it again, at most 3 times in all. **Nothing is rolled back automatically:** the new server may have run Flyway migrations, and the old server may not work on the migrated schema. Fix forward (a new commit is deployed as usual), roll back by hand (section 6), or try the same revision again with `tinfirm-auto-deploy retry`.

## 3. Install

Requirements on the host: Linux, Docker Engine with Compose v2, `curl`, `jq`, `flock` (util-linux), `cmp` (diffutils), `openssl`, systemd. The server image is published for `linux/amd64` only. The stack must already run: deploy it once with `make deploy-host` ([deploy/compose/deploy-host.sh](../../deploy/compose/deploy-host.sh)), which generates `.env` with random secrets. It builds and ships the server image and the model port's image, and sets both in that `.env`.

1. Bring `deploy/compose/auto-deploy/` to the host. `make deploy-host` copies it with the rest of `deploy/compose`, or download a Compose tarball from a release.
2. Install, as root:

   ```sh
   sudo ~/tinfirm/deploy/compose/auto-deploy/install.sh            # --user <deploy user>, --runner-unit <unit>
   ```

   It installs `/usr/local/bin/tinfirm-auto-deploy` and the units `tinfirm-auto-deploy.service` (`User=` the deploy user, `Nice=10`, and sandboxed: `NoNewPrivileges=true`, `ProtectSystem=full`, `PrivateTmp=true`) and `.timer`, and enables and starts the timer. It creates `~/.config/tinfirm-auto-deploy/env` from `env.example` if it is missing, and copies the public keys `cosign.pub` and `cosign-rolling.pub` there (section 1.1). When the unit `tinfirm-runner` exists, it installs `tinfirm-auto-deploy-reload.path` and `.service`, which run exactly `systemctl reload tinfirm-runner` as root when a deploy asks for it, and removes `/etc/sudoers.d/tinfirm-auto-deploy` if one exists: under `NoNewPrivileges`, sudo cannot work. It is idempotent; run it again after an update (a deploy logs a warning when the deployed revision ships a different script).
   A host that follows a `rolling` published without a signature (section 1.1) deploys nothing until `~/.config/tinfirm-auto-deploy/env` has `TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1`.
3. The deploy user must be in the `docker` group. install.sh warns if it is not; add it with `sudo usermod -aG docker <user>`.
4. Optional, before the token: if the server already runs a revision that you want as the baseline, record it, so that the first pass does not deploy it again:

   ```sh
   tinfirm-auto-deploy mark-deployed <full 40-character commit SHA>
   ```

   Otherwise the first pass deploys the newest `rolling`.
5. Store the token (section 4). The next pass deploys.

Commands:

| Command | Does |
|---|---|
| `tinfirm-auto-deploy` | One pass (what the timer runs) |
| `tinfirm-auto-deploy status` | Deployed revision and its subject, the image the server runs, history, failures, cached revisions, runner host, backups. No network calls. |
| `tinfirm-auto-deploy deploy <rev>` | Deploys `<rev>` now, with the same steps, whether or not it is new or failed before. The assets come from `rolling` if it is at `<rev>`, or from the host's cache of the last 3 revisions. |
| `tinfirm-auto-deploy mark-deployed <rev>` | Records `<rev>` as deployed without deploying it |
| `tinfirm-auto-deploy retry` | Forgets the failed attempts, so that the next pass tries the same revision again |
| `DRY_RUN=1 tinfirm-auto-deploy` | Downloads and checks as usual, then prints what it would do. It pulls no image (it uses a local one if present) and changes no file, container, binary or state. |

Settings are in `~/.config/tinfirm-auto-deploy/env`, which the script reads with bash ([env.example](../../deploy/compose/auto-deploy/env.example)):

| Setting | Default | Meaning |
|---|---|---|
| `AUTO_DEPLOY_MODE` | `stack` | `stack`: this host runs the Compose stack, and maybe a runner host. `runner`: only a runner host, which follows its server's revision (section 8). |
| `AUTO_DEPLOY_SERVER_URL` | empty | Runner mode: the server whose revision the runner host follows. Empty means `serverUrl` of the runner host's `config.json`. |
| `AUTO_DEPLOY_RUNNER_CONFIG` | `~/.config/tinfirm-runner/config.json` | Runner mode: the runner host's `config.json`, read for its `serverUrl` |
| `AUTO_DEPLOY_RELEASE` | `rolling` | The release to follow: `rolling`, a fixed tag such as `v0.2.0`, or `latest` (the newest versioned release) |
| `AUTO_DEPLOY_REPO` | `FLOWTIVO/tinfirm` | Repository of the releases |
| `AUTO_DEPLOY_TOKEN_FILE` | `~/.config/tinfirm-auto-deploy/github-token` | The release assets token |
| `AUTO_DEPLOY_SERVER_IMAGE` | `ghcr.io/flowtivo/tinfirm-server` | Server image repository; the tag is `sha-<rev>` |
| `AUTO_DEPLOY_MODEL_API_IMAGE` | `ghcr.io/flowtivo/tinfirm-model-api` | The model port's image repository; the tag is `sha-<rev>` |
| `AUTO_DEPLOY_COMPOSE_DIR` | `~/tinfirm/deploy/compose` | The Compose install, with its `.env` |
| `AUTO_DEPLOY_PROJECT` | `tinfirm` | Compose project; the containers are `<project>-postgres-1` and `<project>-server-1` |
| `AUTO_DEPLOY_DATABASE` | `tinfirm` | Database to back up |
| `AUTO_DEPLOY_BACKUP_DIR`, `AUTO_DEPLOY_BACKUP_KEEP` | `~/backups`, `10` | Where the automatic dumps go, and how many stay |
| `AUTO_DEPLOY_RUNNER_BIN` | `~/.local/bin/tinfirm-runner-host` | The runner host binary. No file here: the runner step is skipped. |
| `AUTO_DEPLOY_RUNNER_UNIT` | `tinfirm-runner` | Its systemd unit. A different unit also needs `install.sh --runner-unit <unit>`, which the reload path unit is installed for. |
| `AUTO_DEPLOY_PUBLIC_KEY` | empty | The public key `SHA256SUMS.sig` must verify against. Empty means `cosign-rolling.pub` in the settings directory for `rolling`, `cosign.pub` for a versioned release or `latest` (section 1.1). |
| `TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED` | `0` | `1` deploys a release whose `SHA256SUMS` has no signature, or with no key to check it, with a warning: a `rolling` published while the environment `rolling` holds no key. A signature that does not verify is refused anyway. |
| `AUTO_DEPLOY_READY_TIMEOUT` | `300` | Seconds to wait for the server |
| `AUTO_DEPLOY_MAX_ATTEMPTS` | `3` | Attempts per revision |
| `AUTO_DEPLOY_KEEP_REVISIONS` | `3` | Revisions whose images (the server's and the model port's) and assets stay: the deployed one and the ones before it |

State, in `~/.local/share/tinfirm-auto-deploy/`: `deployed`, `history` (the last 50 deployed revisions), `failures` (revision and failed attempts), `runner-reload-pending`, `runner-reload-request`, `revisions/<rev>/` (with the signature; a revision cached without one is used only while unsigned assets are allowed) and `lock`. One pass runs at a time (`flock` on `lock`).

## 4. Tokens

The host needs a read-only token for the GitHub API, which allows only 60 requests an hour per address without one. The images are public and pull without a login; only an image in a private package needs the GHCR login of the second row, with a token of the other kind.

| For | Token | Scope | Stored in |
|---|---|---|---|
| Release assets (GitHub API) | Fine-grained personal access token | Repository access: Public repositories (read-only). No permissions to add. | `~/.config/tinfirm-auto-deploy/github-token`, mode 600 |
| Only an image in a private package (GHCR) | Classic personal access token | `read:packages` only. GHCR does not accept fine-grained tokens. | `~/.docker/config.json` of the deploy user, through `docker login` |

As the deploy user:

```sh
(umask 077; cat > ~/.config/tinfirm-auto-deploy/github-token)    # paste the token, Enter, Ctrl-D
docker login ghcr.io -u <GitHub user name>                       # private image only: the classic token
```

For a deploy user without a login shell, pipe the token in: `sudo -u <user> -H docker login ghcr.io -u <GitHub user name> --password-stdin < token-file`.

Notes:

- **Read-only, and as narrow as GitHub allows.** The fine-grained token reads public repositories only. The classic token reads every package that its GitHub account can read, so prefer a bot account that can read only the packages you deploy. Give both tokens an expiry date and renew them before it.
- **Credentials live only in the deploy user's home:** the token file (mode 600) and `~/.docker/config.json`. Docker stores the GHCR token there base64-encoded, not encrypted. Root and anyone with the deploy user's access can read both. The script hands the API token to curl as a header file in its private temporary directory, never on a command line, and curl does not send it on when an asset download redirects to GitHub's storage host.
- **Membership of the `docker` group is root-equivalent** on the host. The unit's `NoNewPrivileges`, `ProtectSystem=full` and `PrivateTmp` keep the script and what it runs from gaining privileges or writing the system directories, not a Compose file from starting a privileged container; the signature check is what keeps a foreign Compose file out. The reload path unit adds only `systemctl reload <runner unit>`.
- **The trust boundary is `main`, its CI and the signing keys.** Whoever can merge to `main`, or change the workflows, deploys to the host. Branch protection on `main`, with code owners and required checks, is what should guard it, and the rolling key is readable only by a run of `main` (environment `rolling`). The host trusts `SHA256SUMS` only with a signature by the key it was installed with (section 1.1), and then each asset against it: a replaced asset, `SHA256SUMS` or `REVISION` is refused, not only a broken or half-updated release. The images are pulled by `sha-<rev>`, not by `main`, so the images and the Compose files are always of the same commit, and a later push to `main` cannot swap an image under a running deploy; the `main` and `sha-*` images themselves are not signed (threat model R53), so a host trusts GHCR's `packages: write` holders for them.

## 5. Pause and resume

```sh
sudo systemctl stop tinfirm-auto-deploy.timer      # no new passes; a running pass finishes
sudo systemctl start tinfirm-auto-deploy.timer     # resume (it is still enabled at boot)
sudo systemctl disable --now tinfirm-auto-deploy.timer   # off, also after a reboot
```

Pause before you deploy by hand with `make deploy-host`, or set an image of your own in `.env`: both write `TINFIRM_SERVER_IMAGE` and `TINFIRM_MODEL_API_IMAGE`, and the timer would otherwise keep your images only until the next commit on `main`.

To uninstall: disable the timer and `tinfirm-auto-deploy-reload.path`, then delete `/etc/systemd/system/tinfirm-auto-deploy.{service,timer}`, `/etc/systemd/system/tinfirm-auto-deploy-reload.{path,service}`, `/usr/local/bin/tinfirm-auto-deploy` and, if present, `/etc/sudoers.d/tinfirm-auto-deploy`, and run `sudo systemctl daemon-reload`.

## 6. Roll back

Auto-deploy never rolls back. To go back to an earlier revision:

1. Pause: `sudo systemctl stop tinfirm-auto-deploy.timer`.
2. Find the revision to go back to: `tinfirm-auto-deploy status` lists the history and the cached revisions. The images and assets of the last 3 revisions stay on the host.
3. If the failed revision changed the database (a new Flyway migration) and the earlier server does not start on it, restore the backup that the deploy took first. Its name ends with the revision it was taken on (`tinfirm-auto-<time>-<revision>.dump`). Restoring loses every change since that backup.

   ```sh
   cd ~/tinfirm/deploy/compose
   docker compose -f compose.yaml --env-file .env stop server
   docker exec tinfirm-postgres-1 dropdb -U postgres --force tinfirm
   docker exec -i tinfirm-postgres-1 pg_restore -U postgres --create -d postgres < ~/backups/tinfirm-auto-<time>-<revision>.dump
   ```

   `--create` creates the database again with its owner `tinfirm_owner` and its grants, from the dump.
4. Deploy the earlier revision: `tinfirm-auto-deploy deploy <full sha>`. It takes a new backup, puts back that revision's Compose files and images, and the runner host binary if it differs. Without the cached assets (an older revision), set `TINFIRM_SERVER_IMAGE=ghcr.io/flowtivo/tinfirm-server:sha-<full sha>` and `TINFIRM_MODEL_API_IMAGE=ghcr.io/flowtivo/tinfirm-model-api:sha-<full sha>` in `.env` and run `docker compose -f compose.yaml --env-file .env up -d --no-build`; the previous runner host binary is `~/.local/bin/tinfirm-runner-host.prev`.
5. Resume the timer once `main` has a fix. Until then, the revision you left is held back anyway: auto-deploy does not deploy a revision that was deployed before, and does not retry one that failed 3 times.

## 7. Troubleshooting

| Symptom in the journal | Cause and fix |
|---|---|
| `cannot read release rolling` with `401` | The release assets token is wrong or expired: section 4 |
| `cannot pull ghcr.io/…` | The host cannot reach GHCR, or the image does not exist; for an image in a private package, the deploy user is not logged in to GHCR or the classic token expired: section 4 |
| `cannot reach Docker as <user>` | The deploy user is not in the `docker` group |
| `release rolling is being updated` | CI is replacing the assets; the next pass continues |
| `release … has no SHA256SUMS.sig` | A versioned release whose `sign-assets` job has not run yet (it waits for a reviewer), or a build of `rolling` made without the rolling key: wait, run `sign-assets`, or set `TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1` for `rolling` (section 1.1) |
| `SHA256SUMS of <rev> … is not signed by …` | The signature does not verify against the installed key: the assets were replaced, or the key changed and install.sh has not run again since. Nothing is installed; check the release before anything else |
| `no public key at …` | install.sh has not copied the keys yet, or the key file is a placeholder: run install.sh again, or set `TINFIRM_AUTO_DEPLOY_ALLOW_UNSIGNED=1` for `rolling` |
| `not deploying <rev>: it failed 3 times` | Push a fix, roll back (section 6), or `tinfirm-auto-deploy retry` |
| `<rev> was deployed before` | `rolling` points at an older revision than the deployed one; deploy it by hand if you mean it |
| `not deploying <rev> now: ghcr.io/…/tinfirm-model-api:sha-<rev> is not available yet` | `publish-services` has not published the model port's image of that commit yet, or it failed. The next pass tries again; check that commit's run in Actions when it does not pass |

## 8. Runner-only hosts

When the server runs elsewhere, for example on Kubernetes through Helm and GitOps, a host with only a runner host sets `AUTO_DEPLOY_MODE=runner` in `~/.config/tinfirm-auto-deploy/env`.

The runner protocol changes in place, so a runner host must not run ahead of its server. In runner mode a pass therefore follows the server, not the release:

1. Reads the server's revision: `GET <server>/api/system/info`, field `commit`, the full SHA that images built by CI report (`TINFIRM_REVISION`). The server is `AUTO_DEPLOY_SERVER_URL`, or `serverUrl` in the runner host's `config.json`. A server that does not answer, or reports no full revision, gets a warning, and the runner host stays as it is.
2. If that revision is the deployed one, the pass ends.
3. Takes the runner host tarball of that revision from this host's cache, or from the release when the release is at that revision. Otherwise it logs that it waits, and the next pass looks again. The release `rolling` only holds the newest commit of `main`: deploy the server at `rolling`'s revision, and the runner host follows within 5 minutes. For a server that follows the tag `main-complete` this happens by itself: after `publish`, `publish-services` and `rolling`, the CI job `main-complete` moves the server image's tag `ghcr.io/flowtivo/tinfirm-server:main-complete` to the commit's image, the deployment that follows the tag rolls the server out on that image, and the runner host follows its revision.
4. Installs the binary and reloads the unit as in section 2, step 10, records the revision and prunes the cache.

It follows the server back, too, when the older revision is still cached here (the deployed revision and the 2 before it). Runner mode needs no Compose files, no `.env`, no server image and no backup; the GitHub token for the release is still needed. `tinfirm-auto-deploy status` shows the mode and the revision the server reports.

To switch a Compose host to runner mode: stop the Compose stack, set `AUTO_DEPLOY_MODE=runner`, run `install.sh` again, and check with `tinfirm-auto-deploy status`.
