# ADR-0091: The image tag is `image.tag` alone

- Status: Accepted
- Date: 2026-10-24
- Decided by: the owner
- Sources: [ADR-0080](0080-helm-chart-published-to-ghcr.md) (a deployment repository pins one key); [ADR-0090](0090-a-chart-for-any-cluster.md) (the tag under both names); [helm.md](../operations/helm.md), sections 2.1 and 6
- Amends: [ADR-0080](0080-helm-chart-published-to-ghcr.md), decision 2 (the pin is `imageTag`); [ADR-0090](0090-a-chart-for-any-cluster.md), decision 7 (the tag under both names) and its alternative "`image.tag` only"
- Amended by: [ADR-0107](0107-a-deployment-repository-follows-the-registry.md), under which a deployment repository sets `image.tag` from the tag `main-complete` in the registry, and a release's version by hand (decision 2)
- Amended by: [ADR-0110](0110-no-model-port.md), under which the model port, whose tag decision 1 defaults to the server's, is gone
- Changes: in `deploy/helm/tinfirm`, the value `imageTag` goes from `values.yaml` and `values.schema.json`, the helper `tinfirm.imageTag` reads `image.tag` alone, `ci/*.yaml` set `image.tag`, and the chart's version is 0.7.0

## Context

- ADR-0090 kept the top-level `imageTag` beside `image.tag`, for tools that rewrite one top-level line of a values file and nothing nested. That left two names for one setting: the guide explains both, the chart refuses them set to different tags, and an operator who reads another chart's values expects `image.tag` only.
- A deployment repository may derive a published chart's version from `image.tag`, as from `imageTag` before.

## Decision

**The server image's tag has one name, `image.tag`.**

1. **The chart drops `imageTag`.** The schema refuses it as an unknown key, so a values file that still sets it fails at install or upgrade, naming it, rather than running the chart's default image. The model port's tag still defaults to the server's.
2. **A deployment repository pins `image.tag`.** It sets `image.tag` in its values and derives the chart's version from it, as from `imageTag` before (ADR-0080, decision 2). *Amended by ADR-0107: it sets `image.tag` from the tag `main-complete` in the registry, on its own schedule, and a release's version by hand.*
3. **Chart 0.7.0.** Removing a value breaks a values file that sets it.

## Consequences

- An operator upgrading to chart 0.7.0 with `imageTag` set moves the value to `image.tag` ([helm.md, section 6](../operations/helm.md#6-upgrades)).

## Alternatives considered

- **Both names, as in ADR-0090.** It cost every operator a second name to suit tools that rewrite one top-level line.
