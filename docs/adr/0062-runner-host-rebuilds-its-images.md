# ADR-0062: The runner host rebuilds its images when its Dockerfiles change

- Status: Accepted
- Date: 2026-10-15
- Decided by: the owner
- Sources: [ADR-0041](0041-pi-and-opencode-runtimes.md), [ADR-0061](0061-opencode-2.md); runner/README.md ("Runner images", "Drain and upgrades"); docs/operations/auto-deploy.md; deploy/ansible
- Changes: `images build` and `run` of the runner host; `doctor`

## Context

- The runner images are built on each runner host from Dockerfiles embedded in the binary (`images build`); none is published, because the Claude image contains Claude Code.
- A new binary often brings new Dockerfiles (a new pinned CLI, such as OpenCode 2). The images then have to be rebuilt by hand. The Ansible role does it when it installs a new binary; auto-deploy (`rolling`, runner mode on a runner-only host) installs the binary and drains, and nothing rebuilds the images. A host then runs the new binary with the old images: an old CLI, or a runtime that is missing until someone runs `images build`.
- `images build` takes build arguments (package mirrors of a host without internet access). A rebuild must use the same ones.
- `run` reports the runtimes and their versions to the server in `hello`, once per connection, from the images it finds.

## Decision

**`run` rebuilds, before it connects, every runner image that was not built from its own binary's Dockerfiles, with the build arguments that `images build` recorded.**

1. **A digest on every image.** The build labels each image `dev.tinfirm.image-spec`: the SHA-256 of its Dockerfile, the files it copies, the build arguments it declares and, for a runtime image, the base's digest. A changed base makes every runtime image stale.
2. **`images build` records** its build arguments and runtimes in `images.json` in the config directory (0600): the arguments of the last build, and every runtime built so far. It records them before it builds, so a failed build is retried with them.
3. **`run` rebuilds at start**, after it took `run.lock` and before it connects: each runtime image whose digest is not the one its binary would build, and each missing image of a recorded runtime; the base first, when it is not current. It does not touch an image replaced by `TINFIRM_RUNNER_IMAGE_*`, nor build a runtime nobody chose. With the images current, the start is unchanged (a few inspections).
4. **A failure does not stop the host.** When an image cannot be built (a registry or mirror is down), `run` logs the error and goes on with the image it has, if any; the next start, or `images build`, tries again. When the base fails, no runtime image is built on the old one.
5. **A signal ends the rebuild.** SIGTERM, SIGINT or SIGHUP during the rebuild ends `run` at once (no run is active yet); a drain of an upgrade then starts the next binary.
6. **`--no-image-rebuild`** (`TINFIRM_RUNNER_NO_IMAGE_REBUILD`) keeps the images as they are, for hosts whose images are built otherwise. `doctor` shows the names of the recorded build arguments (never their values, which stay in `images.json`) and each image that `run` would rebuild.

## Consequences

- After an upgrade (auto-deploy, Ansible or by hand), a host runs the images of its binary without a manual step. `images build` stays the way to choose the runtimes and the build arguments, and to build the first time.
- A start after an upgrade that changes a Dockerfile takes as long as the rebuild: seconds for a runtime image from Docker's cache, minutes for a new base. The host is offline meanwhile, as it is during a drain; queued runs wait.
- Images built before this change have no digest: the first start of a binary with it rebuilds them, with the recorded build arguments. A host built with `--build-arg` before `images.json` existed has none recorded: run `images build` once with the same arguments, or the first rebuild uses the defaults (and fails without internet access, leaving the old images in use).
- The Ansible role still builds the images before it reloads the service; its build records the arguments, so `run` finds the images current.
- Rebuilding fetches from the same sources as `images build` (Docker Hub's `node` image, Debian, npm, …), now unattended at a start. The trust is unchanged: the versions and checksums are pinned in the embedded Dockerfiles.

## Alternatives considered

- **Rebuild in auto-deploy** (`tinfirm-auto-deploy` runs `images build` with the new binary before the reload). Not chosen: it covers only auto-deploy, and it retags the images while the old binary still takes runs.
- **Rebuild in the background after connecting**, and reconnect to report the new runtimes. Not chosen: the host would take runs on old images meanwhile, and `hello` is per connection.
- **Image tags per digest** (`tinfirm-runner-opencode:<digest>`), so that old and new binaries never share an image. Not now: it needs a cleanup of old tags and changes every Dockerfile's `FROM`; the rebuild at start with a drain before it gives the same result for one binary per host.
- **Publish the images.** Rejected for the Claude image, which is built on the runner host only; the other images would still need the mirrors of hosts without internet access.
