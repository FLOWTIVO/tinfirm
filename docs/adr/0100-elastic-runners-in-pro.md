# ADR-0100: Elastic runners are Pro's and Enterprise's, with their code under `ee/`, the runner host's included

- Status: Accepted
- Date: 2026-10-26
- Decided by: the owner, 2026-10-26
- Amends: [ADR-0077](0077-licence-editions-and-seats.md), decision 1 (what is under `ee/`) and decision 4 (a row, `Feature.ELASTIC_RUNNERS`); [ADR-0095](0095-sandbox-bridge.md), [ADR-0096](0096-a-vm-per-run.md), [ADR-0097](0097-session-bundles.md), [ADR-0098](0098-ready-vms.md) and [ADR-0099](0099-pool-settings.md), which become paid
- Sources: the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §9 (the steps 1 to 6 of these ADRs); [CONVENTIONS.md](../CONVENTIONS.md) §14.10 (protocol v2's features), §15.24 (the licence and the paid features' code); [ADR-0082](0082-licence-notices-and-dependency-licences.md) (the licences every artefact carries)
- Changes: `Feature.ELASTIC_RUNNERS` (Pro); runner protocol v2's feature `sandbox.bridge` and error code `EDITION_REQUIRED`; the runner host's paid code under `ee/runner/`, its manifests still in `runner/`; the server's paid code under `ee/src/main/java` behind the core's interfaces `run.internal.SessionBundles` and `runner.internal.RunnerPools`; the web app's Pro badge on a host's ready sandboxes; the public pricing page

## Context

- ADR-0095 to ADR-0099 built elastic runners: the sandbox bridge, a VM per run through a driver (DigitalOcean's first), ready VMs, Tinfirm's schedule of them, and session bundles, which let a turn resume on any company host. Their code was in the MIT core: the runner host's in `runner/`, the server's in `server/`.
- Under ADR-0077, decision 1 as amended, the code that only a paid feature runs is under `ee/`, beside a core that answers as Community without it. Decision 4's table had no row for runners: every edition had them.
- Community runs agents today on always-on runner hosts with Docker: the host starts a container per run on its own machine, at most 16 slots a host, and a run resumes its session on the host that ran the turn before (ADR-0007). That is what a team needs to try Tinfirm and to run it; elastic runners are what an organisation that runs many agents on rented machines needs.
- The runner host is one binary, `tinfirm-runner-host`, built from one Cargo workspace whose members must sit under its root (`runner/`).

## Decision

**Elastic runners are a paid feature, Pro's and Enterprise's: `Feature.ELASTIC_RUNNERS`. Their code is under `ee/`, the runner host's under `ee/runner/`. Community keeps runner hosts as they were before ADR-0095: always on, with Docker, the agent attached.**

1. **What the feature holds.** `ELASTIC_RUNNERS`, Pro's and Enterprise's (`Edition.PRO`):
   - the sandbox bridge (ADR-0095): `run --sandbox-bridge`, its tunnels and its file transfer;
   - a VM per run (ADR-0096): `run --executor vm`, the driver protocol, the DigitalOcean driver and the VM image's recipe;
   - ready VMs (ADR-0098) and Tinfirm's schedule of them (ADR-0099);
   - session bundles (ADR-0097), for every company host, with Docker too.

   Decision 4's table gains the row; nothing else moves between editions. Community keeps every runner host, company and personal, with Docker, as many as it runs, each up to 16 slots, its homes on the host.
2. **The runner host's code under `ee/runner/`.** The bridge crate's sources and tests (`ee/runner/tinfirm-bridge`); the runner host's modules of the bridge, the VM executor and its boot, the DigitalOcean driver, the pool, session bundles and the run's files over the bridge (`ee/runner/tinfirm-runner-host/src`), and their tests; the VM image's recipe (`ee/runner/images/vm`). The manifests stay in `runner/`, since a workspace's members must be under its root: they name the code under `ee/` (`[lib] path`, `[[test]] path`, and `#[path]` for the runner host's modules). The bridge crate declares `license = "LicenseRef-Tinfirm-Enterprise"`, and the runner host's crate, which compiles in its modules under `ee/`, `license = "MIT AND LicenseRef-Tinfirm-Enterprise"`, as the server's pom names both. The rest of the runner host stays in `runner/`, MIT, where it was.
3. **One binary, as one image.** As `ee/` is in every image (ADR-0077, decision 3), the runner host's paid code is in every build of `tinfirm-runner-host`: one binary, one image, one release, both licences in each archive (`LICENSE`, `ee/LICENSE`, `THIRD-PARTY-LICENSES.txt`). The paid code runs only with `--sandbox-bridge` or `--executor vm`, which need runner protocol 2, so that the server sees them.
4. **The server's gates.** Each is the server's (ADR-0077, decision 1):
   - **The hello.** A host that runs its agents over the bridge, with `--sandbox-bridge` or a VM per run, lists the feature `sandbox.bridge` in `hello.features`; it adds no frame. Without `ELASTIC_RUNNERS` in force the server answers such a hello, and one that lists `sandbox.pool`, which only a VM per run implements (amended 2026-10-26), with an `error` `EDITION_REQUIRED`, which says what to run instead, and closes with 1008; the host stops, with that message, and does not reconnect. A limit on the core's own endpoint, so a check in the core (`RunnerWebSocketHandler`), as the Outlook add-in's MCP calls are. It is checked at each hello: a host connected when a licence ends keeps its connection until it reconnects.
   - **Session bundles.** Without the feature no `run.assign` names a bundle, and the host's `GET` and `POST /api/runner/v1/runs/{runId}/session-bundle` answer `403 EDITION_REQUIRED` after its token is checked. Keeping, handing out, uploading and downloading bundles are under `ee/` (`SessionBundlesImpl`, `SessionBundleRows`, `BundleCipher`, `RunnerSessionBundleController`), behind the core's `SessionBundles`.
   - **The pool's schedule.** Without the feature `PATCH /runner-hosts/{id}` with `pool` or `clearPool` answers `403 EDITION_REQUIRED` after the access check, and neither the minute's tick nor a host's hello evaluates the schedule again (amended 2026-10-26: the hello's refresh, too, keeps the count the host has). The schedule, its evaluation and its SQL are under `ee/` (`RunnerPoolsImpl`, `PoolSchedule`, `RunnerPoolStore`), behind the core's `RunnerPools`; without `ee/` a host's page shows no pool.
5. **What stays in the core, in every edition.** The protocol: every feature, frame and field, and its schema. The schema (V066, V067). What a host reports of its pool (`hello.pool`, `host.config.applied.pool`, `host.pool.status`) and the `host.config` it is sent. The bundle store's adapters (a directory, S3) and every delete of a bundle: with its conversation's content (retention, erasure), when a run reports its session without one, and the sweep of ended, idle and orphaned ones (`SessionBundleCleanup`), so that what a paid licence stored goes as it would under it. The vault's data keys (`vault.DataKeys`), plain envelope encryption. The web app, MIT as ADR-0077 has it, which shows a Pro badge in place of the schedule's button.

## Consequences

- A Community instance refuses a runner host started with `--sandbox-bridge` or `--executor vm` at its first frame, with a message that says why and what to run instead; its Docker hosts work as before. A Pro or Enterprise key lets the same host connect at its next start.
- The public pricing page gains a row for elastic runners, in Pro and Enterprise.
- `ModularityTest` lists two more of the core's gates: the hello's limit (`RunnerWebSocketHandler`) and the pool's fallback (`RunnerHostService`).
- The runner's checks build, lint and test the code under `ee/runner/` with the rest of the workspace, unchanged; the Docker build contexts copy `ee/runner/`.
- The bridge carries no credential and the VM no secret of the server's: moving the code changes neither ADR-0095's threat model nor ADR-0097's, only who may run it.

## Alternatives considered

- **Enterprise only.** Not chosen: elastic runners are what a team that grows past a few hosts needs, before it needs SAML or SCIM.
- **Keep the runner host's code MIT and gate the server only.** Simpler for the build, but the paid feature's code would be under the MIT licence, free to copy into any runner and to run against any server; ADR-0077 puts the code that only a paid feature runs under `ee/` (decision 1).
- **A separate paid runner binary, or a Cargo feature that leaves the paid code out of Community's build.** Two binaries, two images and two release flows, and an operator who moves to Pro would swap binaries; ADR-0077 keeps one artefact for every edition and gates at run time.
- **Session bundles in every edition.** They serve a pool of company hosts, the same need as a VM per run, and so belong to the same paid feature.
