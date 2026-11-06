# ADR-0107: CI publishes to the registry, and a deployment repository follows it

- Status: Accepted
- Date: 2026-11-03
- Decided by: the owner, 2026-11-03
- Sources: [ADR-0080](0080-helm-chart-published-to-ghcr.md) (the chart published with the images, and a deployment repository's one pin); [ADR-0091](0091-image-tag-alone.md) (`image.tag`, the one pin); [ADR-0106](0106-reusable-workflows-and-pinned-actions.md) (the release's workflows in this repository); [the security review of 2026-10-27](../reviews/2026-10-27-security-review-fixed.md), H3 (the deploy keys in environments); [auto-deploy.md](../operations/auto-deploy.md), sections 1 and 8; the [threat model](../threat-model.md), R53
- Amends: [ADR-0080](0080-helm-chart-published-to-ghcr.md), decision 2 (which tag a deployment repository pins, once the chart is published); [ADR-0091](0091-image-tag-alone.md), decision 2 (where a deployment repository takes `image.tag` from)
- Changes: `ci.yml`: the job `main-complete` is added; `publish-docs` runs in no environment and labels its image with its revision; `reusable-release.yml` and `release.yml`: the release changes no environment; `README.md`; CONTRIBUTING §5; CONVENTIONS §12; the threat model; `docs/operations/auto-deploy.md` and `helm.md`; `docs-site/README.md`; §3 and §4 of [the fix round's record](../reviews/2026-10-27-security-review-fixed.md)

## Context

- A deployment repository pins the server image's tag, `image.tag` ([ADR-0091](0091-image-tag-alone.md)), and derives the chart's version from it ([ADR-0080](0080-helm-chart-published-to-ghcr.md)).
- A key that writes to a deployment repository writes cluster state when a GitOps controller syncs that repository to a cluster: every repository whose CI holds such a key is a way into the cluster.
- What a deployment repository needs from this one is only which images are ready to run. For an environment that follows `main` that is the newest commit of `main` whose server image, model port and runner host images and rolling prerelease are all published: the chart's other images follow `image.tag`, and the runner-only hosts install the runner host of the revision the server reports ([auto-deploy.md, section 8](../operations/auto-deploy.md#8-runner-only-hosts)). The registry can carry it.

## Decision

**CI publishes images and moves tags in the registry, and nothing else. A deployment repository reads the tags on its own schedule and commits its own changes. This repository holds no key to one.**

1. **What CI publishes on a push to `main`.** `publish` pushes the server image `ghcr.io/flowtivo/tinfirm-server:sha-<commit>` and the chart `0.0.0-sha-<commit>`, `publish-services` the model port's and the runner host's images, and `rolling` the runner host's release assets. Each moves its `main` (or `rolling`) only while its commit is still the tip of `main`. Every image carries the label `org.opencontainers.image.revision`, the full commit SHA.
2. **The tag `main-complete`.** A job `main-complete`, after `publish`, `publish-services` and `rolling`, tags the server image `ghcr.io/flowtivo/tinfirm-server:sha-<commit>` as `main-complete` too, in the same package (`ghcr.io/flowtivo/tinfirm-server:main-complete`), while the commit is still the tip of `main`. The tag names the newest commit of `main` whose server image, model port image and rolling prerelease are all published; the image's revision label names the commit. The job uses its `GITHUB_TOKEN` with `packages: write`, no environment and no key. A failed `publish-services` or `rolling` leaves the tag where it is. The tag is on the server image's own package, beside `sha-<commit>` and `main`: the server image is the one `image.tag` names, so a deployment repository reads the image it deploys.
3. **The docs site.** `publish-docs` pushes `ghcr.io/flowtivo/tinfirm-docs:sha-<commit>`, labelled with its revision, then moves `main`. It runs in no environment.
4. **A deployment repository follows the registry.** A scheduled workflow of its own reads `tinfirm-server:main-complete` and commits the `sha-<commit>` it names to its values files, with its own credential. When it runs, and what it checks before it commits, are that repository's decisions.
5. **A release reaches an environment by an edit there.** The release publishes its images, chart and assets, signed, and touches no environment. A version runs in an environment once its deployment repository's values file names `v<version>`, a change made there by hand.
6. **No key to a deployment repository here.** No workflow or action of this repository writes to one. The environment `release-deploy` holds the release App's key, for `tag` and `next`.

## Consequences

- No workflow of this repository, on any branch, reads a key that writes cluster state: there is none here.
- An environment that follows these tags runs what any workflow of this repository requesting `packages: write` can point them at, from any branch, as such a workflow can already re-push a `sha-<commit>` image that the environment pulls (R53). The revision label lets a deployment repository check what a tag names before it deploys it, for example that the commit is on `main`; signing the images of `main` stays the follow-up R53 names.
- An environment that follows the registry takes a new commit at its deployment repository's next scheduled run, not at the end of the push's CI run; that repository's workflow meets its own errors, and CI does not.
- A deployment repository writes its own files, `image.tag` among them; `image.tag` stays the chart's one name for the server image's tag (ADR-0091).
- The tag `main-complete` exists from the first run of the job `main-complete` on `main`; until then a deployment repository has nothing to follow.
- Nothing in this repository says where a version runs: a deployment repository's history does.

## Alternatives considered

- **CI writes `image.tag` to a deployment repository, with the key in an environment.** It would work, but every change to a workflow here would be a change to who can write cluster state, and its safety would rest on environment settings outside the code. Not chosen by the owner.
- **A deployment repository follows `tinfirm-server:main`.** `publish` moves it before the model port's image and the rolling prerelease exist, so an environment that follows it could pull a model port image that is not published yet, and runner-only hosts would wait for `rolling`.
- **An image updater in a cluster (Argo CD Image Updater).** It follows tags by pattern, not by the order of `main`, and holds a credential that writes to a deployment repository or to the Applications. Not discussed.
- **The release points an environment that follows `main` at its version.** The next commit of `main` moves such an environment on, so a release there is a moment between two commits. A release version belongs in an environment that does not follow `main`, and is set where that environment is defined: one place says what runs.
