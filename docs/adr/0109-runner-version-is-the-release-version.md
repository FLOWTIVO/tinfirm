# ADR-0109: The runner host reports the release version; compatibility is the protocol

- Status: Accepted
- Date: 2026-11-06
- Decided by: the owner, 2026-11-06, before the 1.0 release
- Sources: [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md) (protocol 2 and its negotiation); [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md), decision 5 (the always-on model proxy); [the package 2 contract](../reviews/2026-10-12-package-2-contract.md), §7 (the minimum runner version) and open point 6 (the host's own token at the relay); [the security review of 2026-10-27](../reviews/2026-10-27-security-review-fixed.md), I8 (`TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` on by default); [ADR-0106](0106-reusable-workflows-and-pinned-actions.md) (the release's `set-version` action)
- Amends: [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md), where the server recognised a host with the always-on proxy by its version; the package 2 contract's §7, whose minimum runner version is gone
- Changes: `runner/Cargo.toml` and `Cargo.lock` (`0.1.0-SNAPSHOT`); the runner host's `version` module (`--version`, `hello.runnerVersion`); `.github/actions/set-version` (`cargo:<dir>`), `release.yml` and `hotfix-branch.yml` (`cargo:runner`), `runner-artifacts.yml`, `deploy/release/assets.sh` and `runner-musl.Dockerfile` (`TINFIRM_RUNNER_BUILD`); the server: `RunnerVersions` and `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` removed, `RunnerHost.gatewayRuns` removed from the API; the web app's "too old" note; `protocol/runner-v2.schema.json` (descriptions only); CONVENTIONS §3, §4, §14.10, §15.4; the threat model 5.15 and 13.4; the operator and member docs

## Context

- The runner workspace had a version of its own, 0.3.0, which no release set: `release.yml` versioned the server's and the model port's poms and the web app's package only. A v1.0.0 release would have shipped `tinfirm-runner-host-1.0.0-<target>` archives and the image `tinfirm-runner-host:v1.0.0` whose binary says 0.3.0, in `--version` and in `hello.runnerVersion`.
- The server decided behaviour from that number (`RunnerVersions`): a run paid by a budget went only to a host of 0.2.0 or later, the first with the always-on model proxy, and the relay required the host's own token without asking from a host of 0.3.0 or later, and from every host while `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` was on (the default since the security review of 2026-10-27).
- The protocol already has the means of compatibility: `hello.protocolVersion`, negotiated (1 or 2, ADR-0039), and `hello.features`, the optional capabilities within protocol 2. Versions compared as numbers are a second, implicit protocol, which a release that renumbers breaks.
- Nothing is released. Staging runs the relay with the host token required and runner 0.3.0, built from `main`.

## Decision

**The runner host reports the product's release version. The server shows it and decides nothing from it. Compatibility between server and runner host is the protocol version and its features.**

1. **Three numbers, each moved by something else.**

   | Number | Where | What moves it |
   |---|---|---|
   | The product version | `--version`, `hello.runnerVersion`, the release assets `tinfirm-runner-host-<version>-<target>`, the image `ghcr.io/flowtivo/tinfirm-runner-host:v<version>` | Every release, as for the server and the web app |
   | The protocol version | `hello.protocolVersion` | A breaking change of the wire: a new protocol version, negotiated as ADR-0039 does |
   | The features | `hello.features` | A new optional capability within a protocol version: a new feature name, whatever release ships it |

2. **The runner is versioned with the release.** `set-version.sh` takes a `cargo:<dir>` manifest: it sets `version` in the workspace's `[workspace.package]` and the same version for the workspace's own packages in `Cargo.lock` (the lock's packages without a `source` that had the old version), without a toolchain or the network, so that `cargo build --locked` takes the result. `release.yml` and `hotfix-branch.yml` list `cargo:runner`, so the release commit sets the release's version and the next-version commit the development version after it. Cargo takes `1.0.1-SNAPSHOT` as a semver prerelease, so the development version is the same string as Maven's and npm's. Between releases the workspace's version is the server's, `0.1.0-SNAPSHOT`.
3. **A rolling build says what it is.** `hello.runnerVersion` stays the workspace's version alone, always a semantic version. A build may name what it was published as in `TINFIRM_RUNNER_BUILD` at build time; `deploy/release/assets.sh` and `runner-artifacts.yml` pass the asset's version (`rolling-<sha7>`, or the release's version). `--version` and the startup log show it after the version, `0.1.0-SNAPSHOT (rolling-abc1234)`, and leave it out when it is the version itself. The image tags of the runner images and the VM image's build id (ADR-0101, decision 6) keep using the workspace's version: a new version rebuilds a VM image once, as a new release should.
4. **The model proxy and the host token are part of protocol 2.** Every host of protocol 2 has the always-on model proxy (a gateway's run never gets its one-run token into the sandbox) and sends its own token in `X-Tinfirm-Runner-Token` with every request to its server's model relay. The runner host has no mode without either: the proxy has no option to turn it off, and every proxy the host starts carries its token for the server's origin. So neither is a feature: a feature names what some hosts of the protocol lack. The schema's description and CONVENTIONS §14.10 say so.
5. **The version checks go.** `RunnerVersions` is removed with its callers' version logic: a run paid by a budget goes to any host that scope and runtime allow (dispatch already takes hosts of protocol 2 only), and the relay refuses a run's token without its host's own token from every host. `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` has no purpose left (no host of protocol 2 sends no token) and is removed with its Helm, Compose and docs entries; an environment that still sets it is not harmed, as the server ignores it. `RunnerHost.gatewayRuns` leaves the API and the web app's "too old" note goes, since no version is too old. The host's `runnerVersion` is still stored and shown on its page.

## Consequences

- Any runner host release that speaks a protocol the server supports works with it; hosts and server can be upgraded in either order. A support question names one number for the whole product.
- A capability added later is a feature name, gated on `hello.features`; a change that a host of protocol 2 cannot ignore is protocol 3. No code compares runner versions.
- A host built before the always-on proxy, before runner 0.2.0, that speaks protocol 2 would now get runs paid by a budget. No such host was released, and none of `main`'s builds of protocol 2 lacks the proxy. A host modified to leave the proxy out holds its own runner token anyway, which is more than a run's token (threat model 5.10, 5.15).
- A request without the host's own token is refused from every host, with no setting to allow it: an operator whose hosts log in at another origin than `TINFIRM_PUBLIC_URL` must log them in again, as before.
- The release commit changes `runner/Cargo.toml` and `runner/Cargo.lock`, and the `runner` job's licence check is unaffected: `THIRD-PARTY-LICENSES.txt` names no version of the workspace's own crates.

## Alternatives

- **An independent runner semver** (0.3.0 now, its own releases later). Rejected: support would need a matrix of runner and server versions, the release assets and the image are named for the product's version and would carry a binary of another, and the server would keep deciding from a number that its protocol does not define.
- **A feature per duty** (`model.proxy`, `relay.host-token`). Rejected: every host of protocol 2 has both, so the features would be listed by every host and gate nothing. They are kept for a duty that some hosts may lack.
- **Keep the version checks, with the release's numbers** (a minimum of 1.0.0). Rejected: it keeps the implicit protocol, and every build of `main` before 1.0 (`0.1.0-SNAPSHOT`) and every prerelease (`1.0.0-rc.1`) would compare below the minimum and get no runs paid by a budget.
