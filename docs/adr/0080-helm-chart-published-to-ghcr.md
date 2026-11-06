# ADR-0080: The Helm chart is published to GHCR, and a deployment repository deploys it from there

- Status: Accepted
- Date: 2026-10-20
- Decided by: the owner, 2026-10-20
- Amended by: [ADR-0090](0090-a-chart-for-any-cluster.md), under which a chart published for `main` carries `appVersion: sha-<commit>` and defaults to its own commit's images; [ADR-0091](0091-image-tag-alone.md), under which the one pin is `image.tag` (decision 2)
- Amended by: [ADR-0106](0106-reusable-workflows-and-pinned-actions.md), under which the chart's publishing (`.github/actions/helm-publish`) and the release that passes `charts` (`reusable-release.yml`) are a local action and a reusable workflow with pinned actions
- Amended by: [ADR-0107](0107-a-deployment-repository-follows-the-registry.md), under which CI moves the tag `main-complete` in the registry, and a deployment repository sets `image.tag` from it on its own schedule and a release's version by hand (decision 2)
- Sources: [helm.md](../operations/helm.md); [auto-deploy.md, section 1](../operations/auto-deploy.md#1-what-ci-publishes); [ADR-0077](0077-licence-editions-and-seats.md) (the editions and their licences)
- Changes: `ci.yml`'s `publish` job pushes the chart; `release.yml` passes `charts: deploy/helm/tinfirm` to the release; the chart gets `sources` and the value `litellm.adminUi`; the action `helm-publish` and the release's opt-in inputs `charts` and `chart_registry`.

## Context

- A deployment repository that keeps a copy of `deploy/helm/tinfirm` must keep it identical by hand, and an edit made to the copy (LiteLLM's admin UI turned on, say) has to be carried into each later copy.
- Operators install the chart ([helm.md](../operations/helm.md)), until now from a checkout.
- Argo CD renders Helm charts from OCI registries, with value files from another source (a multi-source Application). GHCR stores Helm charts as OCI artifacts, beside the images.
- A deployment repository names the server image's tag by one top-level key of a values file, `imageTag`, a line that a tool can rewrite.

## Decision

**CI publishes the chart to `oci://ghcr.io/flowtivo/charts/tinfirm` with the images it publishes, and a deployment repository deploys that chart with only a values file of its own: it keeps no copy.**

1. **Versions.** A release publishes the chart as its version, with `appVersion` the same, so the chart's default image is the release's. Each push to `main` publishes `0.0.0-sha-<full commit SHA>`, beside the images `sha-<full commit SHA>`: SemVer, and below every release, so no version range picks a build of `main`. `Chart.yaml`'s version keeps naming a chart packaged by hand.
2. **One pin.** A deployment repository pins `imageTag` alone, and derives the chart's version from it: `v<version>` gives `<version>`, `sha-<sha>` gives `0.0.0-sha-<sha>`. The chart and the images always come from the same commit. The chart is published with the images: in `publish`, and in the release's `charts` job. *Amended by ADR-0091: the pin is `image.tag`.* *Amended by ADR-0107: a deployment repository sets the pin from the tag `main-complete` in the registry, which moves only after `publish` has published the chart, and sets a release's version by hand.*
3. **One action, opt-in.** The publishing step is the action `helm-publish`, used by `ci.yml` and by the release. The release publishes charts only for a caller that passes `charts`.
4. **Public.** The package `charts/tinfirm` is public, as are the chart's sources (ADR-0077): Argo CD pulls it without a credential, and anyone installs it with `helm install oci://ghcr.io/flowtivo/charts/tinfirm`.
5. **What a copy would edit becomes a value.** LiteLLM's admin UI is `litellm.adminUi` (default `false`); a deployment that wants the UI sets it.

## Consequences

- A chart change reaches a deployment with its commit's image, through the same pin. A deployment repository changes values only; a chart change can no longer be tried there alone, it is a pull request here.
- The chart lists this repository first under `sources`. Helm makes it the package's `org.opencontainers.image.source`, which links the package to this repository, so that its workflows may write to it. The publishing action refuses a chart without it.
- Every push to `main` adds a chart version to the package, as it adds image tags.
- A chart published for `main` defaults to `v<appVersion of Chart.yaml>`, an image that does not exist: it is deployed with its commit's `imageTag`, as a deployment repository does. *Amended by ADR-0090: it carries `appVersion: sha-<commit>`, and its default tag is that, the images of its own commit.*
- `release.yml` passes `charts` to the release (`reusable-release.yml`), so each release of Tinfirm publishes its chart.

## Alternatives considered

- **An umbrella chart in a deployment repository that depends on the published one.** The values would move under a `tinfirm:` key, whose `imageTag` a tool that rewrites one top-level line of a values file cannot rewrite, and the chart's version would be a second pin, in `Chart.yaml` and `Chart.lock`.
- **Argo CD rendering the chart from this repository's git, at the image's commit.** No publishing and the same single pin, but Argo CD clones this repository for each render, and operators still get no published chart.
- **A second key in the values file for the chart's version.** Explicit, but the values file is the chart's input, and the chart's schema refuses keys it does not know; two writes per push could also pair a chart with another commit's image for a moment.
- **A Helm repository on GitHub Pages (`index.yaml`).** A second publishing mechanism beside GHCR, which already holds the images.
