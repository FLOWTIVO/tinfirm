# ADR-0082: Every artefact carries its licences and its dependencies' notices, and CI checks the dependencies' licences against one policy

- Status: Accepted
- Date: 2026-10-21
- Decided by: the owner, 2026-10-21
- Sources: [ADR-0077](0077-licence-editions-and-seats.md), decision 1 (the licences; the core free of BSL and SSPL components); [ADR-0080](0080-helm-chart-published-to-ghcr.md) (the public chart); [ADR-0081](0081-signed-release-images.md) (the release images and their SBOMs); [auto-deploy.md, section 1](../operations/auto-deploy.md#1-what-ci-publishes) (the release assets)
- Changes: the root `LICENSE` and `ee/LICENSE`; `/licenses/` and the label `org.opencontainers.image.licenses` in the server's and the model port's images (`Dockerfile`, `model-api/Dockerfile`), the label in the docs site's (`docs-site/Dockerfile`); `LICENSE` and `THIRD-PARTY-LICENSES.txt` in every runner host archive, `LICENSE` in the Compose archive (`deploy/release/assets.sh`); `deploy/helm/tinfirm/LICENSE` and the chart's annotation `artifacthub.io/license`; the checks: license-maven-plugin in `server/pom.xml` and `model-api/pom.xml` (with `server/src/license/override-THIRD-PARTY.properties`), `web/scripts/licences.mjs` (`npm run licences`), cargo-about (`runner/about.toml`, `runner/about.hbs`, the stages `licences` and `licences-file` of `runner/ci.Dockerfile`, `runner/THIRD-PARTY-LICENSES.txt`); `ci.yml` (jobs `web`, `helm`, `image`, `runner`) and the `Makefile`; the licence fields of `runner/Cargo.toml` and of the Ansible role (Apache-2.0 before), of both poms, `web/package.json` and `api/openapi.yaml`; the docs site's copyright line

## Context

- ADR-0077 decided the licences: MIT outside `ee/`, the Tinfirm Enterprise licence for `ee/`. No file stated them yet, and two files named another one: `runner/Cargo.toml` and the Ansible role said Apache-2.0, from before ADR-0077.
- FLOWTIVO distributes the server's and the model port's images, the Helm chart (public on GHCR, ADR-0080), the runner host archives and the Compose archive (GitHub Releases). None of them carried a licence text. Without one, a public chart grants nobody anything.
- The MIT and BSD licences of the dependencies ask for their notice in every copy. The runner host binary links about 300 crates statically, and the Rust standard library and, on Linux, musl. The web bundle holds about 50 npm packages. The server's image holds about 180 jars, each with its own licence files. Angular writes the bundle's notices (`3rdpartylicenses.txt`) beside `browser/`, and the image left them out.
- ADR-0077 keeps the core free of BSL and SSPL components. Nothing checked it, nor any other licence a new dependency brings.
- The poms name licences in free text ("Apache License, Version 2.0", "The Apache Software License, Version 2.0", "GPL2 w/ CPE"), and a pom can be wrong: spring-session 4.1.1's name a "Broadcom Foundation License" without a URL, while its jars carry the Apache License 2.0 and the project states it ([spring-projects/spring-session#3910](https://github.com/spring-projects/spring-session/issues/3910)).

## Decision

**Every artefact FLOWTIVO distributes carries the repository's licence and the notices of what it includes, and CI refuses a dependency whose licence the policy does not accept.**

1. **The texts.** The root `LICENSE`: MIT outside `ee/`, the documentation and the code served to the browser included, and the scope of each licence. `ee/LICENSE`: the Tinfirm Enterprise licence, adapted from GitLab's. FLOWTIVO s.r.o. holds the copyright.
2. **In every artefact.**

   | Artefact | What it carries |
   |---|---|
   | The server's image | `/licenses/LICENSE`, `/licenses/ee/LICENSE` (the `ee/` part is compiled in), `/licenses/THIRD-PARTY-server.txt` (each runtime dependency and its licence; each jar keeps its own texts), `/licenses/THIRD-PARTY-web.txt` (the notices of the packages the web bundle holds, and spartan/ui's for its components copied into `web/src/app/ui`, from `web/src/app/ui/LICENSE`); label `MIT AND LicenseRef-Tinfirm-Enterprise` |
   | The model port's image | `/licenses/LICENSE`, `/licenses/THIRD-PARTY-model-api.txt`; label `MIT`. It compiles in no `ee/` code |
   | The docs site's image | Label `MIT` |
   | A runner host archive | `LICENSE`, `ee/LICENSE` (the code of `ee/runner/` is compiled in, ADR-0100), and `THIRD-PARTY-LICENSES.txt`: the full licence text of every crate the binary is built with, the Rust standard library's and musl's |
   | The Compose archive | `LICENSE` |
   | The Helm chart | `LICENSE`, a copy of the root one that CI keeps identical; annotation `artifacthub.io/license: MIT` |

3. **The policy.** A dependency that ships may be under: MIT, MIT-0, Apache-2.0 (also with the LLVM exception), BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, Zlib, Unlicense, CC0-1.0, Unicode-3.0 and Unicode-DFS-2016, BSL-1.0 (Boost), EDL-1.0, the Bouncy Castle licence, OFL-1.1 (fonts), and, used unmodified, the weak copyleft EPL-2.0, MPL-2.0, LGPL-2.1 and GPL-2.0 with the Classpath exception. Everything else is refused, in particular BUSL (the Business Source License), SSPL, AGPL, GPL without an exception, the Elastic License, the Commons Clause, licences that forbid commercial use or derivatives, and no licence at all. A dependency that offers a choice ("MIT OR Apache-2.0") passes when one choice is accepted. What ships: the runtime dependencies of the server and the model port, the web app's production packages and the runner host's crates; not tests or build tools.
4. **Three checks, one list.** Each names the same licences by their SPDX identifiers (the Bouncy Castle licence, which has none, only in the poms; EDL-1.0 not in `runner/about.toml`, below), and a change of the policy changes all four files and this ADR:

   | What | Check | Where it runs |
   |---|---|---|
   | The server's and the model port's runtime dependencies | license-maven-plugin `add-third-party` in `server/pom.xml` and `model-api/pom.xml`: `includedLicenses`, with `failOnBlacklist` and `failOnMissing`; it also writes the list the image carries | Every Maven build: CI's `server` and `model-api` jobs, `make server-test`, `make model-api-test`, and the image builds |
   | The web app's production packages | `web/scripts/licences.mjs` over `npm query ".prod"` (`npm run licences`) | CI's `web` job, `make web-test` |
   | The runner host's crates | cargo-about (`runner/about.toml`), which also writes `runner/THIRD-PARTY-LICENSES.txt` and fails when the committed file is not what `Cargo.lock` gives | CI's `runner` job (stage `licences` of `runner/ci.Dockerfile`), `make runner-licences-check`; `make runner-licences` writes the file |

   EDL-1.0 is not an SPDX identifier either: SPDX names the Eclipse Distribution License 1.0, whose text is the BSD 3-clause licence's, BSD-3-Clause. The poms and the web app's list accept the name; cargo-about refuses a name SPDX does not know, and a crate under that licence declares BSD-3-Clause, which `runner/about.toml` accepts.

5. **Names and wrong metadata.** `licenseMerges` in the poms maps each name a pom uses to its SPDX identifier, added once someone has looked at the licence it means; a new name fails the check until then. A dependency whose metadata is wrong is corrected for its one version, never by widening the policy: `server/src/license/override-THIRD-PARTY.properties` (Maven), `CLARIFIED` in `web/scripts/licences.mjs`, a `clarify` entry in `runner/about.toml`. Today there is one: spring-session-core and spring-session-jdbc 4.1.1, Apache-2.0. The Maven file also records one choice, for its one version: cryptacular 1.3.0 (OpenSAML's, ADR-0086) is under the Apache License 2.0, which its pom names "Apache 2", or the LGPL 3.0, which the policy refuses; it is taken under Apache-2.0.
6. **The runner's notices are committed.** `runner/THIRD-PARTY-LICENSES.txt` is generated from `Cargo.lock` and committed, so that the macOS and Windows builds package it without cargo-about. The archive of every target gets the same file; it covers the dependencies of all four targets.

## Consequences

- A dependency update that brings a licence outside the policy, or a new name for one, fails CI: the job of the build it ships in names the dependency and the licence.
- A change to `runner/Cargo.lock` comes with `make runner-licences` and the new `runner/THIRD-PARTY-LICENSES.txt`, or the `runner` job fails.
- The images do not build with a refused dependency either: the Maven check runs in their build.
- The release images' SBOMs (ADR-0081) name the licences of Tinfirm's own components, from the poms.
- Not covered: the base images' own packages (their licences are in each image's `/usr/share/doc`), the runner images, which every host builds from their Dockerfiles, and the images a deployment pulls from their vendors (LiteLLM, PostgreSQL, Keycloak). The planned air-gapped bundle (ADR-0077) redistributes those and needs their notices.

## Alternatives considered

- **A deny list** (BUSL, SSPL, AGPL). Shorter, but the free-text names of the poms slip past it, and a licence nobody looked at passes. An allow list fails on it.
- **Permissive licences only.** Refuses today's logback (EPL-2.0 or LGPL-2.1), jakarta.annotation and jakarta.transaction (EPL-2.0 or GPL-2.0 with the Classpath exception), option-ext (MPL-2.0) and the fonts (OFL-1.1). Not chosen by the owner.
- **Trivy's licence scanner for all three.** One tool, but `Cargo.lock` holds no licences and the poms' licences need a network lookup; one more pinned binary in CI.
- **Generating the runner's notices in each release job.** No committed file, but the macOS and Windows jobs would need cargo-about too, and a notice that changed would show only at release.
