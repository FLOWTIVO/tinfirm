# Changelog

Every release of Tinfirm, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/). A release is cut by hand with `.github/workflows/release.yml`; its GitHub Release carries the section of its version below.

The server, the web app, the Helm chart and the runner host carry the release's version. The runner host and the server agree on the runner protocol and its features, never on a version number ([ADR-0109](docs/adr/0109-runner-version-is-the-release-version.md)).

Between releases, `main` is a development channel, not a release: the images tagged `sha-<commit>` and `main`, the charts `0.0.0-sha-<commit>` and the prerelease `rolling` ([README](README.md#status)).

## [1.0.0] - unreleased

The first release of Tinfirm.

| Artefact | v1.0.0 |
|---|---|
| Server image (the API, the web app and the MCP endpoints) | `ghcr.io/flowtivo/tinfirm-server:v1.0.0` |
| Runner host image (the Helm chart's runner pod) | `ghcr.io/flowtivo/tinfirm-runner-host:v1.0.0` |
| Helm chart | `oci://ghcr.io/flowtivo/charts/tinfirm --version 1.0.0`, which runs the images `v1.0.0` with `image.tag` empty |
| Runner host binaries | `tinfirm-runner-host-1.0.0-x86_64-unknown-linux-musl.tar.gz`, `-aarch64-unknown-linux-musl.tar.gz` and `-aarch64-apple-darwin.tar.gz` on the GitHub Release |
| Compose files | `tinfirm-compose-1.0.0.tar.gz` on the GitHub Release |
| Checksums | `SHA256SUMS` over every asset, `REVISION` (the commit), and the signature `SHA256SUMS.sig` with its cosign bundle `SHA256SUMS.sigstore.json` |

The images and the chart are signed, and each image carries a signed SPDX SBOM, with FLOWTIVO's release key, [`docs-site/content/cosign.pub`](docs-site/content/cosign.pub), also at <https://docs.tinfirm.com/cosign.pub>; `SHA256SUMS` is signed with the same key. Verify offline before you deploy: [Verify your images](docs-site/content/operator-guide/verify-images.md) ([ADR-0081](docs/adr/0081-signed-release-images.md)).

### What 1.0.0 is

A self-hosted platform on which a company runs AI agents: people talk to them, triggers start them from the company's systems, each run works in a sandbox with the accounts its audience allows, and every decision that needs a person comes to one inbox ([PLATFORM.md](docs/PLATFORM.md)).

- **Agents and conversations.** Agents are definitions that hold no credentials, shared with groups and members, each person's settings narrowing them; the runtimes Claude Code, Codex, pi and OpenCode. Conversations with live transcripts, steering a running turn, questions, approvals and outputs, automatic retries, agent memory and a skills library ([ADR-0058](docs/adr/0058-authority-from-the-audience.md), [ADR-0060](docs/adr/0060-steering-a-running-turn.md), [ADR-0063](docs/adr/0063-agent-memory.md), [ADR-0064](docs/adr/0064-skills-library.md), [ADR-0073](docs/adr/0073-automatic-retries.md)).
- **Systems and triggers.** Jira Data Center and Cloud (Cloud service accounts by client credentials), GitHub, GitLab, git hosts and MCP servers, the company's, members' own and servers that need no account; triggers on tracker issues and change requests by polling, on schedules, chat channels and a generic webhook, with the outbox and the write-back; credentials on systems lent to groups and service accounts; internal hosts ([ADR-0071](docs/adr/0071-mcp-servers-without-accounts.md), [ADR-0072](docs/adr/0072-system-credentials-lent-to-groups-and-service-accounts.md), [ADR-0093](docs/adr/0093-jira-cloud-service-accounts-by-client-credentials.md)).
- **Chats.** Slack and Microsoft Teams: direct messages to the bot and channel triggers.
- **People's own AI clients and email.** The MCP endpoint, with Tinfirm as the OAuth 2.1 authorization server and personal access tokens; the Outlook add-in and the Gmail add-on, with an email's attachments and Gmail's draft replies ([ADR-0074](docs/adr/0074-outlook-add-in.md), [ADR-0083](docs/adr/0083-gmail-add-on.md), [ADR-0084](docs/adr/0084-email-attachments-and-gmail-drafts.md)).
- **Model access and budgets.** LiteLLM bundled, a key and a budget per principal, the company's own model key, more than one model gateway with keys a gateway team provides, and the server's model relay, which no provider-run tool passes ([ADR-0059](docs/adr/0059-litellm-budgets-and-the-model-proxy.md), [ADR-0094](docs/adr/0094-more-than-one-model-gateway.md), [ADR-0108](docs/adr/0108-no-provider-run-tools.md), [ADR-0110](docs/adr/0110-no-model-port.md)). Spend views and budget alerts.
- **Governance and audit.** The hash-chained audit with its daily check, retention, legal holds and erasure, kill switches, suspension, members who leave, error reports ([ADR-0070](docs/adr/0070-error-reports.md)).
- **Sign-in.** Local sign-in, OIDC, SAML and SCIM provisioning; companies and their sign-in declared by the deployment; roles and companies from the identity provider ([ADR-0075](docs/adr/0075-companies-and-sign-in-from-the-deployment.md), [ADR-0086](docs/adr/0086-saml-sign-in.md), [ADR-0087](docs/adr/0087-scim-provisioning.md), [ADR-0088](docs/adr/0088-roles-from-the-identity-provider.md)).
- **Runner hosts.** Company and personal hosts in every company, with Docker or Kata Containers sandboxes, runner protocol 2, a model proxy that keeps a run's model token out of the sandbox, and a member's Claude login on their host. Elastic runners for Pro and Enterprise: the sandbox bridge, a VM per run, ready VMs on a schedule and session bundles ([ADR-0085](docs/adr/0085-claude-login-on-the-runner-host.md), [ADR-0092](docs/adr/0092-personal-runner-hosts-in-every-company.md), [ADR-0095](docs/adr/0095-sandbox-bridge.md) to [ADR-0100](docs/adr/0100-elastic-runners-in-pro.md)).
- **Editions and licence.** Community, Pro and Enterprise from one image, an offline licence key, seats counted by role with the Viewer role; an MIT core and a commercial `ee/` ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md), [ADR-0082](docs/adr/0082-licence-notices-and-dependency-licences.md)).
- **Deployment.** The Helm chart on GHCR, Docker Compose, Ansible for runner hosts with Kata Containers, signed release images and assets, and the public docs at <https://docs.tinfirm.com> ([ADR-0080](docs/adr/0080-helm-chart-published-to-ghcr.md), [ADR-0081](docs/adr/0081-signed-release-images.md)).

### Known limitations

These are limits of 1.0, not features of it ([PLATFORM.md §14](docs/PLATFORM.md#14-later-work-and-open-items), [threat model](docs/threat-model.md)):

- **One server replica.** The server keeps runner connections, event streams and tool calls in flight in its process, and several limits count per replica (the runner frames' buckets, the sign-in throttle, the refusals per address, the AI clients' metadata fetches, the catalogue refresh; Q43). Run one replica. High availability across replicas, which Enterprise lists, is not built.
- **Enterprise's audit streamed to a SIEM and its air-gapped installation bundle are not built.** Export the audit from the app; on an air-gapped network, copy the signed images with `cosign save` and `cosign load` ([Verify your images](docs-site/content/operator-guide/verify-images.md#on-an-air-gapped-network)).
- **Not built either:** reusing agents (duplicate, templates, export and import as code), a GitHub App for installation and user tokens, webhooks as hints for the next poll, Jira's changelog for issues that are not yet subjects, reuse of the model proxy's connections, and Buzz, which is parked.
- **Git credentials live as long as the account's** (Q41). A run uses the account's token, not a short-lived one per run, and a host does not revoke a run's git token when the server revokes the account.
- **The server pod runs the migrations** (Q17), so it holds the `tinfirm_owner` password; there is no separate migration step, and no pre-upgrade backup hook in the chart.
- **The Claude Code download is not checked against a pinned digest** when a runner host builds its images (Q16).
- **The audit chain's heads are anchored only in the database** (Q6): no export or signature outside it yet.
- **After a sign-in through an identity provider, the server returns to `/`** (Q44); the web app takes back the page that asked for a recent sign-in from the tab, within 15 minutes.
- **The SAML and SCIM guides** for Entra ID, Okta and AD FS are checked against the providers' documentation, not yet against live tenants, and the SCIM endpoint has not yet run Microsoft's SCIM Validator or Okta's test suite.
- **Runner host binaries:** no Windows build yet, and the macOS binary is not signed by Apple.
- **The sandbox hardening** the threat model lists as accepted risks (its section 7).

### Upgrading from development builds

An installation that ran the development channel (the images `sha-<commit>`, a chart `0.0.0-sha-<commit>`, or a Compose host that followed `main`) moves to v1.0.0 in this order. The operator guide's [Upgrade, back up and rotate secrets](docs-site/content/operator-guide/maintenance.md) has each procedure.

1. **Back up** Tinfirm's and LiteLLM's databases, and keep the vault keys and LiteLLM's salt key. Migrations only move forward.
2. **Replace the published development secrets.** The server does not start with `.env.example`'s vault key, LiteLLM master key or bootstrap admin password unless the public URL is a localhost URL: rotate them first ([Rotate the vault master key](docs-site/content/operator-guide/maintenance.md#rotate-the-vault-master-key)).
3. **The model port is gone** ([ADR-0110](docs/adr/0110-no-model-port.md)). Helm refuses a values file that still sets `litellm.modelApi`: move its `from` to `litellm.from` and drop the rest. The company's services that used port 4001 call LiteLLM's own port 4000, `http://<release>-litellm.<namespace>.svc:4000`, where they also reach its admin API: give each a key made with `key_type` `llm_api` first. On Compose, a company service joins the gateway network and calls `http://litellm:4000`; remove the `model-api` container with `--remove-orphans` and the `TINFIRM_MODEL_API_*` lines of `.env`.
4. **No provider-run tools** ([ADR-0108](docs/adr/0108-no-provider-run-tools.md)). The company setting `providerTools` is gone from the API and the database (a client that still sends it changes nothing), and so are `MODEL_API_PROVIDER_TOOLS`, Helm's `litellm.modelApi.providerTools` and Compose's `TINFIRM_MODEL_API_PROVIDER_TOOLS`: remove them. Agents search the web through a search MCP server.
5. **Model gateways** ([ADR-0094](docs/adr/0094-more-than-one-model-gateway.md)). The bundled gateway's variables are now `TINFIRM_MODEL_GATEWAY_ADMIN_URL`, `_MODEL_URL` and `_ADMIN_KEY`; the server still reads `TINFIRM_LITELLM_ADMIN_URL`, `_MODEL_URL` and `_MASTER_KEY` while the new names are unset. The chart and Compose set the new names. A company may now use a gateway whose keys its own team provides.
6. **Drain the development runner hosts, then upgrade the server.** Stop each host after its running turns finish, so that none takes a run the new server would refuse (step 7): a host run by hand with `tinfirm-runner-host drain`, without starting `run` again; a systemd service, which starts a drained `run` again, with `systemctl stop tinfirm-runner-host` once the host's page in the web app shows no active run. Helm: `helm upgrade tinfirm oci://ghcr.io/flowtivo/charts/tinfirm --version 1.0.0`, with `image.tag` empty or `v1.0.0` (a `sha-<commit>` tag left in your values runs that build, not the release), and by the digests you verified. Compose: the files of `tinfirm-compose-1.0.0.tar.gz` and `TINFIRM_SERVER_IMAGE` set to the `v1.0.0` image by digest. A deployment repository that followed `main-complete` names `v1.0.0` instead.
7. **Then upgrade the runner hosts**, to `tinfirm-runner-host` 1.0.0, a build of runner protocol 2, logged in at the server's public URL, as for every upgrade: the server first, then its hosts. Start them on 1.0.0 right after the server: a development build of the runner host that does not send its own token with a run's model requests has them refused by the new server, which is why they were drained in step 6. The model relay requires a host's own token from every host and no longer has `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN`, which is ignored where it is still set; the server decides nothing from a host's version, and the API's `RunnerHost.gatewayRuns` is gone ([ADR-0109](docs/adr/0109-runner-version-is-the-release-version.md)). `--model-proxy` has no effect. With the chart's runner pod, rebuild the VM image with the 1.0.0 runner host.
8. **Re-provision company hosts with the Ansible role's defaults**: the egress proxy, the bridge, systemd sandboxing, Kata Containers 4.3.1 with its known checksums, and the release archive checked against the signed `SHA256SUMS`. The service user's uid moves from 1000 to 1990: drain each host first ([deploy/ansible/README.md](deploy/ansible/README.md)).
9. **Set up MCP servers' apps again.** An MCP server's app stored before the security review's fixes was not checked against its issuer's origin: open each and choose **Set up the app**, which now refuses endpoints off the issuer's origin and asks before a changed token or revocation endpoint removes accounts. Name split-hosted providers in `TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS` first.
10. **Tell people that personal access tokens expire** after 90 days, tokens made before then included, 90 days after their migration; a token names its companies, and a password change revokes it. The web app's token page renews them.
11. **Instances with several companies:** review the users that SCIM provisioned, have never signed in, and hold memberships in more than one company, and confirm that every company of the instance shares one operator.

[1.0.0]: https://github.com/FLOWTIVO/tinfirm/releases/tag/v1.0.0
