# Changelog

All notable changes to Tinfirm, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).

Releases are cut by hand with `.github/workflows/release.yml`, which dates a version's section in the release commit and uses it as the GitHub Release's notes. The server, the web app, the Helm chart and the runner host share the release's version; the runner host and the server agree on the runner protocol, not on a version number ([ADR-0109](docs/adr/0109-runner-version-is-the-release-version.md)).

Between releases, `main` is a development channel: images tagged `sha-<commit>` and `main`, charts `0.0.0-sha-<commit>` and the `rolling` prerelease ([README](README.md#status)).

## [1.0.0-rc.2] - unreleased

The second release candidate of 1.0.0. It contains everything listed under 1.0.0 below, plus the fixes since 1.0.0-rc.1. Install 1.0.0, not this candidate.

### Changed

- A cancelled run whose agent leaked a credential now fails with `CREDENTIAL_LEAK` instead of `CANCELLED`.
- A run that a runner host rejected is offered to another free host before the one that rejected it.
- A bundle whose head has more than 1,000,000 files, more than 16 GiB in total, or a path longer than 4096 bytes is refused before checkout. Each git the reader runs is limited to 4 GiB of memory.
- On an orderly stop, the runner host waits up to 5 s for the server to answer its close.
- A run pod that finds no node for 60 s is a warning in `verify`, not a problem that stops the runner host from starting.
- The pod driver answers a `create` within 150 s.
- The node agent's hardening waits up to 60 s for udev before it turns module loading off.
- The VM image and the runner host's Ansible role accept Docker's apt key only when the downloaded file holds that one key.
- Upload limits: an authenticated upload over the file limit is read to its end and answered `413 ATTACHMENT_TOO_LARGE`, up to the new `TINFIRM_ATTACHMENT_MAX_DRAIN_SIZE` (64 MB).
- Multipart requests are accepted under `/api/**` only, and need the `X-XSRF-TOKEN` header.
- A request that finds no free database connection in time answers `503 SERVER_BUSY` with `Retry-After` when nothing of it was saved yet. When a change may already be saved, it answers `500` with "The request did not finish, but a change of it may already be saved. Look at the result before you send it again."

### Fixed

**Runs and runner hosts**

- A leaked credential in the agent's output after another stop (a cancel, a guard, a time or usage limit) was ignored, and the run's work was pushed. Nothing of such a run leaves the host now.
- A runner host that died hard after a run's agent finished lost the run. The host now keeps the run until its report reaches the server, and the next start pushes its work and reports it.
- On an orderly stop, a run's end could be lost when the connection broke after it was written or the server answered it with an error. The report now stays for the next start.
- A killed write of a run's record left a copy with the run's credentials in `runs/`. The next start removes it.
- An abort could delete the workspace of a newer execution of the same run, and the homes' cleanup could delete directories a collection was still using.
- A run over the sandbox bridge could fail with `GIT_ERROR` ("the sandbox's bridge is gone") when its agent ended just as the host stopped it.
- A runner host with a VM per run could refuse to start after a restart while its own VMs still held the driver's room.
- A VM whose create timed out at the host kept its node's room until the next cleanup. The host now finds and deletes it within 5 minutes.
- `drain` refused to signal a run that is PID 1, as in the chart's runner pod.
- The model proxy refused a `--model-proxy-local-gateway` on its own address with another port.
- The node driver tried a dead node twice per call, and a host did not learn that a node went down until its next health check.
- The runner host builds for macOS again.

**Sandbox bounds**

- A tunnel stream could be made to queue frames without limit. It now holds at most its window.
- The scan of outgoing commits for a credential held all of git's output in memory, and a failed read ended the scan as if it had finished. It now reads in 1 MiB pieces and fails on a read error.
- The shared bridge listener of a host from before the per-sandbox fronts was never closed. It closes after its last run.

**Server**

- U+0000 (NUL) in a search, a JSON string, a form field or a file name answered `500`. It now answers `400 VALIDATION_FAILED` everywhere, including overlong UTF-8 forms, bodies in other charsets, and MCP messages of any Content-Type.
- A query string or form body that was not valid UTF-8, had a malformed escape or more than 1000 parameters answered `500`. It now answers `400 VALIDATION_FAILED`; SCIM answers `invalidSyntax`.
- Under many concurrent requests, the server could stop answering for 30 s and then answer `500`: some requests held one database connection while they waited for a second.
- Work after a commit could be lost without a trace: releasing roles that SCIM no longer manages, releasing a Slack system's Socket Mode slot, and recording a runner host's disconnect.
- An answer that was complete when its session could not be saved was cut off. It is now kept whole, with a warning in the log.
- A run cancelled because its user was suspended or deactivated could still push their work. Its git access is now revoked before the cancel reaches the host, and the run, the binding's card and the inbox say why.
- The first prompt of a conversation repeated the conversation's title.

**Web app**

- A write that failed after its change was saved said "Try again in a moment", which could save the change twice. It now says that the change may already be saved.
- Pressing Enter in the Connect card right after typing a token could send nothing.

## [1.0.0-rc.1] - 2026-11-09

The first release candidate of 1.0.0, built from the same code as 1.0.0 to test the release itself: the gate, the signed images and chart, the runner host binaries, the Compose tarball and their signed `SHA256SUMS`. Install 1.0.0, not this candidate. Its fixes since are listed under 1.0.0-rc.2.

## [1.0.0] - unreleased

The first release of Tinfirm: a self-hosted platform on which a company runs AI agents. People talk to them, triggers start them from the company's systems, each run works in a sandbox with the accounts its audience allows, and every decision that needs a person comes to one inbox ([PLATFORM.md](docs/PLATFORM.md)).

| Artefact | v1.0.0 |
|---|---|
| Server image (API, web app, MCP endpoints) | `ghcr.io/flowtivo/tinfirm-server:v1.0.0` |
| Runner host image (the Helm chart's runner pod) | `ghcr.io/flowtivo/tinfirm-runner-host:v1.0.0` |
| Helm chart | `oci://ghcr.io/flowtivo/charts/tinfirm --version 1.0.0` |
| Runner host binaries | `tinfirm-runner-host-1.0.0-{x86_64,aarch64}-unknown-linux-musl.tar.gz` and `-aarch64-apple-darwin.tar.gz` |
| Node agent binaries | `tinfirm-node-agent-1.0.0-{x86_64,aarch64}-unknown-linux-musl.tar.gz`, for sandbox nodes |
| Compose files | `tinfirm-compose-1.0.0.tar.gz` |
| Checksums | `SHA256SUMS` over every asset, `REVISION`, and the signature `SHA256SUMS.sig` with `SHA256SUMS.sigstore.json` |

The images and the chart are signed with FLOWTIVO's release key ([`cosign.pub`](docs-site/content/cosign.pub), also at <https://docs.tinfirm.com/cosign.pub>), and each image carries a signed SPDX SBOM. Verify them before you deploy: [Verify your images](docs-site/content/operator-guide/verify-images.md).

### Added

- **Agents and conversations.** Agents hold no credentials and are shared with groups and members. Runtimes: Claude Code, Codex, pi and OpenCode. Live transcripts, steering a running turn, questions, approvals, outputs, automatic retries, agent memory and a skills library ([ADR-0058](docs/adr/0058-authority-from-the-audience.md), [ADR-0060](docs/adr/0060-steering-a-running-turn.md), [ADR-0063](docs/adr/0063-agent-memory.md), [ADR-0064](docs/adr/0064-skills-library.md), [ADR-0073](docs/adr/0073-automatic-retries.md)).
- **Systems and triggers.** Jira Data Center and Cloud, GitHub, GitLab, other git hosts and MCP servers. Triggers on issues, change requests, schedules, chat channels and webhooks. Credentials can be lent to groups and service accounts ([ADR-0071](docs/adr/0071-mcp-servers-without-accounts.md), [ADR-0072](docs/adr/0072-system-credentials-lent-to-groups-and-service-accounts.md), [ADR-0093](docs/adr/0093-jira-cloud-service-accounts-by-client-credentials.md)).
- **GitHub App.** A company's own GitHub App on a GitHub system: runs get short-lived tokens limited to their repositories, renewed through the server ([ADR-0116](docs/adr/0116-short-lived-and-revocable-git-tokens.md), [Set up the GitHub App](docs-site/content/admin-guide/systems.md)).
- **Git token revocation.** When a run's binding stops being valid, or an admin pauses a system, the server tells the run's host to drop the repository's token, and the repository is not pushed. Audited as `run.git_revoked` ([ADR-0116](docs/adr/0116-short-lived-and-revocable-git-tokens.md)).
- **Chats.** Slack and Microsoft Teams: direct messages to the bot and channel triggers.
- **People's own AI clients and email.** An MCP endpoint with Tinfirm as the OAuth 2.1 authorization server, personal access tokens, an Outlook add-in and a Gmail add-on ([ADR-0074](docs/adr/0074-outlook-add-in.md), [ADR-0083](docs/adr/0083-gmail-add-on.md), [ADR-0084](docs/adr/0084-email-attachments-and-gmail-drafts.md)).
- **Model access and budgets.** Bundled LiteLLM with a key and budget per principal, a company's own model key, more than one model gateway, spend views and budget alerts ([ADR-0059](docs/adr/0059-litellm-budgets-and-the-model-proxy.md), [ADR-0094](docs/adr/0094-more-than-one-model-gateway.md)).
- **Governance and audit.** A hash-chained audit with a daily check, retention, legal holds, erasure, kill switches and suspension. Pro and Enterprise can anchor the audit chains' heads outside the database ([ADR-0111](docs/adr/0111-audit-chain-heads-anchored-outside-the-database.md), [Anchor the audit](docs-site/content/operator-guide/audit-anchor.md)).
- **Sign-in.** Local sign-in, OIDC, SAML and SCIM provisioning, with roles and companies from the identity provider ([ADR-0075](docs/adr/0075-companies-and-sign-in-from-the-deployment.md), [ADR-0086](docs/adr/0086-saml-sign-in.md), [ADR-0087](docs/adr/0087-scim-provisioning.md), [ADR-0088](docs/adr/0088-roles-from-the-identity-provider.md)).
- **Runner hosts.** Company and personal hosts, with Docker or Kata Containers sandboxes, or a VM per run. A model proxy keeps a run's model token out of the sandbox. Each host reports its level of isolation, and an agent's runs go to the strongest level available, never to a weaker host on their own ([ADR-0092](docs/adr/0092-personal-runner-hosts-in-every-company.md), [ADR-0113](docs/adr/0113-levels-of-isolation-and-placement.md), [ADR-0114](docs/adr/0114-the-secure-sandbox-is-in-the-core.md)).
- **The sandbox bridge.** Each sandbox reaches the git proxy, the platform tools, the model proxy and the egress proxy through one bridge port of its own, set with `run --sandbox-bridge-ports`. What a sandbox sends is parsed in a confined process that holds no credential ([ADR-0095](docs/adr/0095-sandbox-bridge.md), [ADR-0112](docs/adr/0112-no-credential-on-a-sandbox-machine.md), [Runner hosts](docs-site/content/operator-guide/runner-hosts.md#the-sandbox)).
- **Host health.** A runner host that can take no run says so (`host.health`); the server then sends it none, and its turns wait with `RUNNER_HOST_UNHEALTHY` ([ADR-0117](docs/adr/0117-a-host-reports-that-it-can-take-no-run.md)).
- **Editions and licence.** Community, Pro and Enterprise from one image, with an offline licence key and seats counted by role. MIT core, commercial `ee/` ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md)).
- **Deployment.** A Helm chart on GHCR, Docker Compose, Ansible for runner hosts, signed release images and assets, and public docs at <https://docs.tinfirm.com> ([ADR-0080](docs/adr/0080-helm-chart-published-to-ghcr.md), [ADR-0081](docs/adr/0081-signed-release-images.md)).

API and runner protocol changes for integrators are in [api/openapi.yaml](api/openapi.yaml) and [runner-protocol-v2.md](docs/operations/runner-protocol-v2.md).

### Known limitations

These are limits of 1.0 ([PLATFORM.md §14](docs/PLATFORM.md#14-later-work-and-open-items), [threat model](docs/threat-model.md)):

- **Run one server replica.** Runner connections, event streams and several rate limits live in the server's process. High availability across replicas is not built.
- **Not built:** Enterprise's audit streaming to a SIEM, an air-gapped installation bundle, reusing agents (templates, export and import), a GitHub App's user tokens, and GitLab per-run tokens.
- **Sandbox nodes are not ready for production** ([ADR-0112](docs/adr/0112-no-credential-on-a-sandbox-machine.md)). The `node` VM driver, `tinfirm-node-agent` and their Ansible roles have run on single test nodes only, and Tinfirm publishes no VM image: you build it with `runner/images/vm/local-disk.sh` ([Sandbox nodes](docs-site/content/operator-guide/sandbox-nodes.md)).
- **A pod per run in Kubernetes is not ready for use** ([ADR-0115](docs/adr/0115-sandbox-vms-in-a-kubernetes-cluster.md)). The `pod` VM driver has not run in a cluster ([Sandbox VMs in your Kubernetes cluster](docs-site/content/operator-guide/kubernetes-pod-per-run.md)).
- **The push runs in the runner host** with the run's git token by default. `run --push-client host` pushes without the token in the confined process; it is checked against GitHub only.
- **Git credentials other than a GitHub App's are long-lived.** They can be revoked, not shortened.
- **A revocation does not reach** a request already under way, what the sandbox already fetched, the token at the code host, or a runner host older than this release.
- **The GitHub App is untested against GitHub itself**; every call ran against a stub.
- **The server pod runs the migrations**, so it holds the `tinfirm_owner` password. The chart has no pre-upgrade backup hook.
- **The SAML and SCIM guides** for Entra ID, Okta and AD FS follow the providers' documentation and have not been checked against live tenants.
- **Runner host binaries:** no Windows build, and the macOS binary is not signed by Apple.
- **Sandbox hardening** that the threat model accepts as a risk (its section 7).

### Upgrading from development builds

An installation that followed the development channel (images `sha-<commit>`, a chart `0.0.0-sha-<commit>`, or Compose on `main`) moves to v1.0.0 in this order. Each procedure is in [Upgrade, back up and rotate secrets](docs-site/content/operator-guide/maintenance.md).

1. **Back up** Tinfirm's and LiteLLM's databases, and keep the vault keys and LiteLLM's salt key. Migrations only move forward.
2. **Replace the development secrets.** The server refuses to start with `.env.example`'s vault key, LiteLLM master key or admin password unless the public URL is a localhost URL ([Rotate the vault master key](docs-site/content/operator-guide/maintenance.md#rotate-the-vault-master-key)).
3. **Remove the model port** ([ADR-0110](docs/adr/0110-no-model-port.md)). Helm refuses `litellm.modelApi`: move its `from` to `litellm.from`. Services that called port 4001 call LiteLLM on port 4000 with a key of `key_type` `llm_api`. On Compose, remove the `model-api` container and the `TINFIRM_MODEL_API_*` variables.
4. **Remove provider-run tool settings** ([ADR-0108](docs/adr/0108-no-provider-run-tools.md)): `providerTools`, `MODEL_API_PROVIDER_TOOLS`, `litellm.modelApi.providerTools` and `TINFIRM_MODEL_API_PROVIDER_TOOLS`. Agents search the web through a search MCP server.
5. **Rename the gateway variables** ([ADR-0094](docs/adr/0094-more-than-one-model-gateway.md)) to `TINFIRM_MODEL_GATEWAY_ADMIN_URL`, `_MODEL_URL` and `_ADMIN_KEY`. The old `TINFIRM_LITELLM_*` names still work while the new ones are unset.
6. **Upgrade the server.** Helm: `helm upgrade tinfirm oci://ghcr.io/flowtivo/charts/tinfirm --version 1.0.0`, with `image.tag` empty or `v1.0.0`. Compose: the files of `tinfirm-compose-1.0.0.tar.gz`, with `TINFIRM_SERVER_IMAGE` set to the `v1.0.0` image by digest.
7. **At once, upgrade runner hosts with a VM per run or Kata Containers** ([ADR-0113](docs/adr/0113-levels-of-isolation-and-placement.md)). The new server refuses older ones with `UPGRADE_REQUIRED`, and an older server refuses new ones. First run `tinfirm-runner-host doctor` of 1.0.0 on each: these hosts need Linux 5.19 or later with Landlock enabled, or `--allow-unconfined-reader` ([docs/operations/runner-isolation.md](docs/operations/runner-isolation.md)).
8. **Then upgrade the other runner hosts** at your own pace. Each must be logged in at the server's public URL (`TINFIRM_PUBLIC_URL`). `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` and `--model-proxy` are gone. With the chart's runner pod, rebuild the VM image with the 1.0.0 runner host.
9. **Re-provision company hosts** with the Ansible role's defaults. The service user's uid moves from 1000 to 1990, so drain each host first ([deploy/ansible/README.md](deploy/ansible/README.md)).
10. **Set up a GitHub App only once the company's hosts run this release.** Until a connected host can renew tokens, the App's runs wait with `NO_HOST_FOR_APP_TOKENS` ([ADR-0116](docs/adr/0116-short-lived-and-revocable-git-tokens.md)).
11. **Ready VMs are off by default.** `run --pool-max-ready` and `runner.pool.maxReady` now default to 0 (4 before). Set the value to keep ready VMs; it needs a Pro or Enterprise key ([ADR-0114](docs/adr/0114-the-secure-sandbox-is-in-the-core.md)).
12. **Set up MCP servers' apps again** with **Set up the app**, which now checks endpoints against the issuer's origin. Name split-hosted providers in `TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS` first.
13. **Tell people that personal access tokens now expire** after 90 days, existing tokens included. A password change revokes them.
14. **On instances with several companies,** review SCIM-provisioned users who never signed in and belong to more than one company.

[1.0.0-rc.1]: https://github.com/FLOWTIVO/tinfirm/releases/tag/v1.0.0-rc.1
