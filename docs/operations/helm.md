# Install with Helm

The public guides are [Install on Kubernetes with Helm](../../docs-site/content/operator-guide/helm.md) (https://docs.tinfirm.com/operator-guide/helm/) and [Upgrade, back up and rotate secrets](../../docs-site/content/operator-guide/maintenance.md) (https://docs.tinfirm.com/operator-guide/maintenance/), for operators, with the server variable behind each value in [Server configuration](../../docs-site/content/reference/configuration.md) (https://docs.tinfirm.com/reference/configuration/). This page keeps what the people who build Tinfirm need beyond them: how CI publishes the chart and a deployment repository sets its image, the variables of LiteLLM's container behind the chart's values, the reasons behind the chart's rules with their ADRs and CONVENTIONS sections, the upkeep of the LiteLLM pin, and the upgrades from older charts.

CI publishes the chart `tinfirm` from `deploy/helm/tinfirm` to `oci://ghcr.io/flowtivo/charts/tinfirm` ([ADR-0080](../adr/0080-helm-chart-published-to-ghcr.md)): each release as its version, each push to `main` as `0.0.0-sha-<full commit SHA>`. The chart deploys the server and, unless it is turned off, LiteLLM, the model gateway, with its model port ([ADR-0059](../adr/0059-litellm-budgets-and-the-model-proxy.md), [ADR-0069](../adr/0069-company-model-gateway.md), [litellm.md](litellm.md)), and, when it is turned on, a runner host as a pod, whose sandboxes are VMs ([ADR-0101](../adr/0101-closing-the-elastic-runners-gaps.md), decision 8; section 2.8). It deploys no database and no Secrets. All value names come from `deploy/helm/tinfirm/values.yaml`, and `values.schema.json` validates them.

## 1. Create the Secrets

The Secrets, their keys and how to create them are in the public guide's [Create the Secrets](../../docs-site/content/operator-guide/helm.md#create-the-secrets); the server variable each key becomes is in [Server configuration](../../docs-site/content/reference/configuration.md). The chart references Secrets by name and never inlines a secret value (CONVENTIONS §12).

The server's references to the bootstrap Secret and to the LiteLLM Secret's master key are `optional` (`templates/deployment.yaml`): the bootstrap's password matters only while the user table is empty (its email is also the owner of a declared company that names none, `tinfirm.bootstrap.admin-email` in `DeclaredCompaniesService`), and a missing LiteLLM Secret stops only the LiteLLM pods, while the server starts without a bundled gateway ([litellm.md, section 1](litellm.md#1-the-bundled-gateway)).

The variables of LiteLLM's container, which the public pages do not name:

| Secret key value (default) | LiteLLM's variable |
|---|---|
| `litellm.secretKeys.masterKey` (`master-key`) | `LITELLM_MASTER_KEY`; the same key is the server's `TINFIRM_LITELLM_MASTER_KEY` |
| `litellm.secretKeys.saltKey` (`salt-key`) | `LITELLM_SALT_KEY` |
| `litellm.secretKeys.databasePassword` (`database-password`) | `DATABASE_PASSWORD`, the password of the role `litellm`, unless `litellm.database.existingSecret` is set |
| `litellm.database.secretKeys.password` (`password`), `.username` (empty), `.url` (empty) of `litellm.database.existingSecret` | `DATABASE_PASSWORD` and `DATABASE_USERNAME`, or `DATABASE_URL` ([external-database.md, section 4.1](external-database.md#41-credentials)) |
| `litellm.database.caCertSecret.key` (`ca.crt`) of `litellm.database.caCertSecret.name` (empty: `database.caCertSecret`) | Mounted as a file for `DATABASE_SSLROOTCERT` |

## 2. Values

The values with their defaults are in the public guide's [Choose the values](../../docs-site/content/operator-guide/helm.md#choose-the-values). What follows is what it leaves out.

### 2.1 Image: `image.tag`

`image.tag` is the server image's tag, under that one name ([ADR-0091](../adr/0091-image-tag-alone.md)), and the key a deployment repository sets in each environment's values file. An environment that follows `main` does so through the registry, and this repository holds no key to any deployment repository: after `publish`, `publish-services` and `rolling`, `ci.yml`'s `main-complete` moves the server image's tag `ghcr.io/flowtivo/tinfirm-server:main-complete` to the commit's image, and a deployment repository can set `image.tag: sha-<commit>` from it with a scheduled workflow; the model port's and the runner pod's images follow `image.tag`, so the tag waits for those images too ([ADR-0107](../adr/0107-a-deployment-repository-follows-the-registry.md)). A release sets no environment's tag: `image.tag: v<version>` is an edit in a deployment repository. Charts up to 0.6.0 also took the top-level `imageTag`, the same setting under a second name ([ADR-0090](../adr/0090-a-chart-for-any-cluster.md)); 0.7.0 refuses it (section 6).

Unset, the tag is the chart's own image (`tinfirm.imageTag` in `_helpers.tpl`): `v<appVersion>` while `appVersion` starts with a digit, otherwise `appVersion` as it is. A released chart carries its release's version as `appVersion`, so it runs that release's images. A chart published for `main` carries `appVersion: sha-<full commit SHA>` (`ci.yml`, `publish`, the action's `app-version`), so `0.0.0-sha-<commit>` runs `sha-<commit>` without a value ([auto-deploy.md, section 1](auto-deploy.md#1-what-ci-publishes)).

### 2.2 Public URL: `publicUrl`

The public guide's [Public URL](../../docs-site/content/operator-guide/helm.md#public-url) says what `publicUrl` drives and how the chart falls back when it is empty. It sets `TINFIRM_PUBLIC_URL`, which the OIDC redirect URIs (`<publicUrl>/login/oauth2/code/<id>`), the `Secure` flag of the session cookie and HSTS are built from (CONVENTIONS §5).

### 2.3 Sessions and calls to systems

The values are in the public guide's [Sessions](../../docs-site/content/operator-guide/helm.md#sessions) and [Connections to systems](../../docs-site/content/operator-guide/helm.md#connections-to-systems). `connection.internalHosts` is [ADR-0066](../adr/0066-internal-hosts.md).

### 2.4 Authentication

The values are in the public guide's [Sign-in](../../docs-site/content/operator-guide/helm.md#sign-in), and each provider field, with the server variable it becomes, in [Declare companies and how their people sign in](../../docs-site/content/operator-guide/sign-in.md) and [Server configuration](../../docs-site/content/reference/configuration.md#sign-in). The rules behind them:

- The server never links a sign-in to an existing user by email, and uses PKCE for every registration (CONVENTIONS §5).
- `emailTrust` is per provider ([ADR-0026](../adr/0026-per-provider-oidc-email-trust.md), CONVENTIONS §5): Entra ID needs `TRUST_ISSUER` ([entra-id.md](entra-id.md)).
- `role` is `MEMBER`, `ADMIN` or `VIEWER`, never `OWNER`. A `VIEWER` holds no seat; a role that holds one becomes `VIEWER` when no seat is free ([ADR-0077](../adr/0077-licence-editions-and-seats.md)).
- The chart does not render a provider whose `company` is not in `companies` (section 2.5).

[entra-id.md](entra-id.md) walks through Microsoft Entra ID, and [companies-and-sign-in.md](companies-and-sign-in.md) Keycloak and Okta, a provider's company, and the direct link.

### 2.5 Companies

`companies` declares the instance's companies ([ADR-0075](../adr/0075-companies-and-sign-in-from-the-deployment.md)); the public guide's [Companies](../../docs-site/content/operator-guide/helm.md#companies) has the values, and [Server configuration](../../docs-site/content/reference/configuration.md#bootstrap-and-companies) the variables `TINFIRM_COMPANIES_<KEY>_*`. The whole procedure, with adopting the companies of an older instance, is in [companies-and-sign-in.md](companies-and-sign-in.md).

- The chart does not render an invalid or a duplicate key.
- A declared company beyond the first needs an Enterprise licence whose key covers it: until then it is not created and waits, with a warning in the log, and companies that already exist keep working whatever the licence (`DeclaredCompaniesService.apply`, [ADR-0077](../adr/0077-licence-editions-and-seats.md) decision 4).
- `bootstrap.companyName` is gone (section 6).

### 2.6 Other values

The public guide has the values; the notes behind some of them:

- `replicaCount`: besides the live updates, the login throttle is in memory, per replica (CONVENTIONS §5), so each replica counts attempts separately.
- `ingress.traefik.redirectHttps` renders a Middleware `<fullname>-redirect-https` (`templates/redirect-https.yaml`) and puts it first in the Ingress's `router.middlewares`.
- `httpRoute.*` renders the Gateway API's `HTTPRoute` (`templates/httproute.yaml`), and with `redirectParentRefs` a second one, `<fullname>-redirect-https`, whose only rule is a `RequestRedirect` to `https` (301): the Gateway API's counterpart of the Traefik Middleware. The chart refuses a route without `parentRefs`. `publicUrl` falls back to `https://` and its first hostname, as the route attaches to an https listener ([ADR-0090](../adr/0090-a-chart-for-any-cluster.md)).
- `metrics.podMonitor.*` renders a `PodMonitor` (`templates/podmonitor.yaml`), not a ServiceMonitor: port 8081 is on no Service, by design (CONVENTIONS §12). In another namespace it selects the release's with a `namespaceSelector`.
- `strategy` with `type: Recreate` loses `rollingUpdate` in the template: the chart's default carries one, and Helm merges a values file's `strategy` into it.
- `openshift` (`auto`, `true`, `false`) drops `runAsUser`, `runAsGroup` and `fsGroup` from every security context the chart renders (`tinfirm.securityContext`), and adds OpenShift's DNS (namespace `openshift-dns`, port 5353 behind the Service's 53) to both policies' DNS rule (`tinfirm.networkPolicy.dnsEgress`). `auto` asks `.Capabilities.APIVersions` for `security.openshift.io/v1`, which Argo CD and `helm install` fill from the cluster; `ci/openshift-values.yaml` renders it with `true`.
- `extraConfig` carries the add-ins' variables ([outlook-add-in.md](outlook-add-in.md), [gmail-add-on.md](gmail-add-on.md)); the log shipper beside the server is [alerts.md](alerts.md), section 7; `errorReports.*` is [error-reports.md](error-reports.md).

### 2.7 LiteLLM

The values are in the public guide's [The bundled model gateway](../../docs-site/content/operator-guide/helm.md#the-bundled-model-gateway); what it is for, what Tinfirm manages and what the operator configures are in [litellm.md](litellm.md). Beyond them:

- **The server's variables.** The chart sets `TINFIRM_LITELLM_ADMIN_URL` to `http://<release>-litellm.<namespace>.svc:4000` (LiteLLM's own port, every route) and `TINFIRM_LITELLM_MODEL_URL` to `http://<release>-litellm.<namespace>.svc:4001`, the container `model-api` that serves LiteLLM's model routes alone ([ADR-0069](../adr/0069-company-model-gateway.md)); the company's services reach the model port with LiteLLM keys of their own ([litellm.md, section 8](litellm.md#8-keys-for-the-companys-services)). With `litellm.enabled: false` the chart renders no LiteLLM objects and no `TINFIRM_LITELLM_*` variables ([litellm.md, section 6](litellm.md#6-an-external-litellm)).
- **Nothing exposes LiteLLM**, by design: the Service is always `ClusterIP`, the chart has no Ingress for it, and its NetworkPolicy admits the server pods only to port 4000, with no value to widen it (section 4). Runner hosts never reach it: the server relays their model requests ([litellm.md, section 2](litellm.md#2-how-it-is-secured)).
- **Bumping `litellm.image`.** The image is LiteLLM's non-root one, `ghcr.io/berriai/litellm-non_root`, the same release as `ghcr.io/berriai/litellm` ([ADR-0090](../adr/0090-a-chart-for-any-cluster.md)). Dependabot bumps the Compose file's; bump the chart's tag and digest to the same (CI fails while they differ), and keep the shared filter `LiteLlmRequests` (`libs/litellm`) to the new version's `all_litellm_params`.
- **`litellm.securityContext`.** User 65534, the non-root image's, with a read-only root filesystem: the image writes nothing outside `/tmp` (`litellm.tmpVolume`, an `emptyDir`), and `XDG_CACHE_HOME=/tmp/.cache` points the caches of its libraries there.
- **`litellm.probes.*`.** `/health/liveliness` for startup and liveness, `/health/readiness` (the database answers) for readiness.
- **`litellm.modelApi.maxInflightBytes`.** Each request body counts twice as it arrives and once while its answer streams. LiteLLM checks a key only once its body is read, so a request that would pass the limit is `429`, and a slow upload holds only what it sent.

### 2.8 The runner pod: `runner.*`

The public guide's [The runner pod](../../docs-site/content/operator-guide/helm.md#the-runner-pod) has the values and the steps; [ADR-0101](../adr/0101-closing-the-elastic-runners-gaps.md), decision 8, the decision. Behind them:

- **The server's side.** The chart sets `TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_NAME` in the server's ConfigMap and `_RUNNER_HOST_TOKEN` from `runner.existingSecret` for the company `runner.company`. At every start `DeclaredCompaniesService.apply` hands the host to the runner module's `RunnerHostDeclarations` (the port `company.DeclaredRunnerHosts`): a company host with `declared` set (`V071`) is registered, or its token's hash replaced and its connection closed, audited `runner_host.registered` or `runner_host.updated` (`changed: token`) with `declared: true`. A host of that name registered in the app, a malformed token or another host's token stops the start; the token is checked there, not while the properties bind, so that it is never in a binding error.
- **The pod's side.** `run --server`/`--token-file` (`TINFIRM_RUNNER_SERVER`, `TINFIRM_RUNNER_TOKEN_FILE`) instead of `login`; every other setting is a `TINFIRM_RUNNER_*` variable of `templates/runner-configmap.yaml`, `--max-concurrent-runs` the container's argument. The driver's `digitalocean.json` is a ConfigMap of its own (an `envFrom` would make its key a variable), with `tokenFile` pointing at the mounted Secret.
- **One replica, `Recreate`.** Two processes with one token replace each other's connection (4409) and share the records; the claim is `ReadWriteOnce`. The `preStop` hook runs `drain`, which waits for `run` to exit: the pod's `terminationGracePeriodSeconds` (`runner.terminationGracePeriodSeconds`, an hour) bounds how long an upgrade waits for the runs, and a pod stopped before they end leaves them to the next pod's take-back.
- **The image.** The last stage of `deploy/release/runner-musl.Dockerfile` (the stage `binary` is what `assets.sh` exports), published by `ci.yml`'s `publish-services` as `ghcr.io/flowtivo/tinfirm-runner-host:sha-<sha>`, released and signed with the server's version (`release.yml`). It holds no runtime, unlike the runner images, which contain Claude Code and are never published. Its tag follows `image.tag` unless `runner.image.tag` is set, as the model port's does.
- **The policy** (`templates/runner-networkpolicy.yaml`) admits no ingress: the pod dials the server and the VMs' bridges, and the sandboxes' traffic comes back through the bridges' tunnels to the pod's loopback listeners. Egress is DNS, `networkPolicy.runner.egress.https` (any address on 443, private ones included, for the public URL that may resolve to the cluster's own load balancer), `.vms` on `runner.vm.port`, and `.extra`.

## 3. Install

The values file, the install and the checks of the rollout are in the public guide's [Install the chart](../../docs-site/content/operator-guide/helm.md#install-the-chart) and [Check the rollout](../../docs-site/content/operator-guide/helm.md#check-the-rollout). For development and for an environment that follows `main`:

- A chart `0.0.0-sha-<full commit SHA>` runs that commit's images; a values file that sets `image.tag` must name that commit's tag, `sha-<full commit SHA>` (section 2.1).
- From a checkout, `deploy/helm/tinfirm` in place of the `oci://` reference installs the chart of that checkout.
- The ingress limits of the public guide come from the uploads (a conversation's attachments, package 3 of PLATFORM.md §12, up to 25 MiB) and the server's model relay (up to 64 MiB, with answers that pause up to 10 minutes).
- The readiness probe's group is `readinessState,db,databaseRole` (`application.yaml`, held by `ReadinessProbeTest`); `databaseRole` stays down while the server's database role could bypass row-level security (a superuser, `BYPASSRLS`, a relation owned in `public`, a member of `tinfirm_owner`). Liveness is the application's own state only, so a database outage never restarts the pods.

## 4. NetworkPolicy

The server's, LiteLLM's and the runner pod's policies, the hosts the server calls and the rules to add are in the public guide's [Network policy](../../docs-site/content/operator-guide/helm.md#network-policy) (`templates/networkpolicy.yaml`, `templates/litellm-networkpolicy.yaml`, `templates/runner-networkpolicy.yaml`; the runner pod's, section 2.8). Two defaults hold for any cluster rather than one ([ADR-0090](../adr/0090-a-chart-for-any-cluster.md)): port 8080 admits any source until `networkPolicy.ingress.controller` names the controller, and `networkPolicy.egress.systems` opens port 443 to public IPv4 and IPv6 addresses (not RFC 1918, `100.64.0.0/10`, `fc00::/7`, `fec0::/10`, loopback, link-local, unspecified, multicast, the reserved `240.0.0.0/4`, the special-use ranges no internet host is in (RFC 6890: `192.0.0.0/24`, `198.18.0.0/15`, the documentation ranges, `100::/64`) or the local-use translation prefix `64:ff9b:1::/48`, which may carry private IPv4 addresses; NAT64's well-known `64:ff9b::/96`, which never does (RFC 6052), stays open for IPv6-only clusters), behind the server's own guard of private addresses ([threat model](../threat-model.md), 8.8). The guides of what the server calls: [systems.md](systems.md) and [mcp-servers.md](mcp-servers.md) (the company's systems and their sign-in hosts), [chats.md](chats.md) (Slack and Teams), [mcp-clients.md](mcp-clients.md) (the AI clients' metadata documents), [error-reports.md](error-reports.md) (the error tracker), [gmail-add-on.md](gmail-add-on.md) (Google), and [litellm.md, section 6](litellm.md#6-an-external-litellm) (a company's own LiteLLM). LiteLLM's database port with a URL from a Secret: [external-database.md, section 4.4](external-database.md#44-network).

## 5. Rotate a Secret

The procedure for each Secret is in the public guide's [Rotate a Secret](../../docs-site/content/operator-guide/maintenance.md#rotate-a-secret). The pods read Secret values as environment variables when they start, and the pod template carries a checksum of the chart's ConfigMap only, so an upgrade rolls the pods when the non-secret configuration changes, never when a Secret changes: restart the Deployment after each change. The runner pod's token is read by the server at its start and by the pod at its own: restart the server first, which replaces the hash and closes the old connection, then the pod. The vault's rewrap is `POST /api/admin/vault/rewrap`; keep an old key while a backup needs it ([external-database.md, section 3](external-database.md#3-backups-and-point-in-time-recovery)). The [threat model, section 6.2](../threat-model.md#62-key-compromise-and-rotation) covers compromise of each key.

## 6. Upgrades

The public guide's [Upgrade with Helm](../../docs-site/content/operator-guide/maintenance.md#upgrade-with-helm) covers the rolling update, the migrations and LiteLLM's. Beyond it:

- A Helm pre-upgrade hook that takes a database backup is planned; the chart does not have it yet.
- **To chart 0.8.0** (L29 of the [security review of 2026-10-27](../reviews/2026-10-27-security-review.md)). `trustedProxies` defaults to loopback only: the server trusts no proxy until you set it to your ingress controller's pod network ([Trusted proxies](../../docs-site/content/operator-guide/helm.md#trusted-proxies)). A release that relied on the old default sees every client at the controller's address, and the install notes say so; set the controller's range, not the old value, which the notes also warn about. With an Ingress or HTTPRoute on, the notes warn until `networkPolicy.ingress.controller` (or `extraFrom`) names the controller.
- **To chart 0.7.0** ([ADR-0091](../adr/0091-image-tag-alone.md)). The top-level `imageTag` is gone: move its value to `image.tag`. Helm refuses a values file that still sets it (`additional properties 'imageTag' not allowed`), so a release never falls back to the chart's default image unnoticed.
- **To chart 0.6.0** ([ADR-0090](../adr/0090-a-chart-for-any-cluster.md)). The NetworkPolicy no longer admits ingress-nginx by default: until `networkPolicy.ingress.controller` names your controller, port 8080 admits any source. A release that relied on the old default sets the ingress-nginx selectors itself. `networkPolicy.egress.systems` opens https to public addresses: a catch-all 443 rule in `networkPolicy.egress.extra` can go, and `networkPolicy.egress.systems.to: []` keeps the old closed default. LiteLLM restarts from its non-root image with a read-only root filesystem; its database is unchanged. `trustedProxies` also matches `100.64.0.0/10` and `fc00::/7`: a release that wants the old value sets it.
- **From chart 0.1.0 (no LiteLLM).** Chart 0.2.0 deploys LiteLLM by default. Before the upgrade, create its database and role ([external-database.md, section 4](external-database.md#4-litellms-database)) and its Secret (section 1). Until they exist the LiteLLM pod does not start; without the Secret the server also runs without a bundled gateway. To upgrade without it, set `litellm.enabled: false`.
- **To declared companies** ([ADR-0075](../adr/0075-companies-and-sign-in-from-the-deployment.md)). `bootstrap.companyName` is no longer read. Set `companies` (section 2.5) before the upgrade, one entry per company the instance has; the server adopts each at its start and gives it the key. A company's own identity provider, configured on its settings page, is deleted by the upgrade with its client secret: add it to `auth.oidc.providers` first, with the same issuer and `company` set to the company's key ([companies-and-sign-in.md, section 5](companies-and-sign-in.md#5-from-a-provider-configured-in-the-app)).
