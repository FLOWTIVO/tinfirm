# Install on Kubernetes with Helm

This page installs Tinfirm on Kubernetes with the Helm chart `tinfirm`: the Secrets to create first, the values that matter, the install itself, how to check it, and the network policy the chart puts around the server. It is for operators who know Kubernetes, Helm and PostgreSQL.

The chart deploys the server and, unless you turn it off, the bundled LiteLLM with its model port, and, if you turn it on, a runner host whose sandboxes are VMs ([The runner pod](#the-runner-pod)). It deploys no database and creates no Secret: you prepare PostgreSQL ([Prepare PostgreSQL](database.md)) and create the Secrets yourself.

!!! warning "No release yet"

    No version of Tinfirm is released yet. Each commit on the development branch publishes a chart `0.0.0-sha-<commit>` and images tagged `sha-<commit>`: they are not releases, and they are not signed as releases are ([What is not signed](verify-images.md#what-is-not-signed)). Use them to evaluate Tinfirm, and run a release in production once one exists.

## Before you start

| You need | Requirement |
|---|---|
| Kubernetes | 1.29 or later |
| Helm | 4 |
| PostgreSQL | 18, outside the chart, with the roles `tinfirm_owner` and `tinfirm_app`, and a database and role of its own for LiteLLM: [Prepare PostgreSQL](database.md) |
| A host name | For example `tinfirm.example.com`, with a TLS certificate. People, AI clients, runner hosts, chat platforms and webhook senders all reach the server there. LiteLLM needs none: only the server reaches it. |
| An ingress controller or a Gateway | In the cluster: any Ingress controller, such as Traefik, NGINX, HAProxy, a cloud load balancer's or OpenShift's router, or a Gateway API implementation ([Gateway API](#gateway-api)). Name it in the network policy, so that nothing else reaches the server ([Ingress to the server](#ingress-to-the-server)). |
| The Secrets | In the release's namespace, before you install ([Create the Secrets](#create-the-secrets)) |

## Create the Secrets

The chart names Secrets and never holds a secret value. The pods do not start until the required Secrets exist. Create them with your secret tooling, never in a values file.

| Secret (value, default name) | Keys (values, default keys) | What it holds | Required |
|---|---|---|---|
| `database.existingSecret` (`tinfirm-database`) | `database.secretKeys.appPassword` (`app-password`), `database.secretKeys.ownerPassword` (`owner-password`) | The passwords of `tinfirm_app`, which the server runs as, and `tinfirm_owner`, which runs the migrations | Yes |
| `vault.existingSecret` (`tinfirm-vault`) | `vault.secretKeys.keys` (`keys`), `vault.secretKeys.activeKey` (`active-key`) | The vault's master keys, as `keyId:base64(32 bytes)`, comma-separated, and the id of the key that wraps new secrets. The server does not start without them. | Yes |
| `bootstrap.existingSecret` (`tinfirm-bootstrap`) | `bootstrap.secretKeys.adminEmail` (`admin-email`), `bootstrap.secretKeys.adminPassword` (`admin-password`) | The first instance admin, created while the instance has no user | For the first start |
| `litellm.existingSecret` (`tinfirm-litellm`) | `litellm.secretKeys.masterKey` (`master-key`), `litellm.secretKeys.saltKey` (`salt-key`), `litellm.secretKeys.databasePassword` (`database-password`) | LiteLLM's master key (`sk-…`), its salt key, and the password of its database role | With the bundled LiteLLM |
| Each `auth.oidc.providers[].existingSecret` | `secretKeys.clientSecret` (`client-secret`) | The client secret at that identity provider | One per provider |
| `database.caCertSecret.name` | `database.caCertSecret.key` (`ca.crt`) | The CA certificate of your database server | With `verify-ca` or `verify-full` |
| `errorReports.dsnSecret.name` | `errorReports.dsnSecret.key` (`dsn`) | Where error reports are sent: [Error reports](error-reports.md) | No |
| `runner.existingSecret` (`tinfirm-runner`) | `runner.secretKeys.token` (`token`) | The runner pod's token, `tfr_` and 43 base64url characters | With the runner pod |
| `runner.vm.digitalocean.existingSecret` (`tinfirm-digitalocean`) | `runner.vm.digitalocean.secretKeys.token` (`token`) | The DigitalOcean API token that creates the runner pod's VMs | With the runner pod |

LiteLLM's database credentials can also come from a Secret of their own, for example one that a database operator writes: [Connect LiteLLM to its database](database.md#connect-litellm-to-its-database).

Generate the values into files, without a trailing newline, then create the Secrets from the files, so that no value lands in your shell history:

```sh
printf '%s' "$(openssl rand -hex 24)" > app-password
printf '%s' "$(openssl rand -hex 24)" > owner-password
printf 'k1:%s' "$(openssl rand -base64 32)" > vault-keys
printf 'k1' > vault-active-key
printf 'sk-%s' "$(openssl rand -hex 32)" > litellm-master-key
printf '%s' "$(openssl rand -hex 32)" > litellm-salt-key
printf '%s' "$(openssl rand -hex 24)" > litellm-database-password
printf '%s' 'admin@example.com' > admin-email      # the first instance admin: your email
printf '%s' "$(openssl rand -base64 24)" > admin-password

kubectl create namespace tinfirm
kubectl -n tinfirm create secret generic tinfirm-database \
  --from-file=app-password=./app-password --from-file=owner-password=./owner-password
kubectl -n tinfirm create secret generic tinfirm-vault \
  --from-file=keys=./vault-keys --from-file=active-key=./vault-active-key
kubectl -n tinfirm create secret generic tinfirm-bootstrap \
  --from-file=admin-email=./admin-email --from-file=admin-password=./admin-password
kubectl -n tinfirm create secret generic tinfirm-litellm \
  --from-file=master-key=./litellm-master-key --from-file=salt-key=./litellm-salt-key \
  --from-file=database-password=./litellm-database-password
```

Use the same passwords when you create the database roles, and sign in the first time with the email and the password of `admin-email` and `admin-password`. Then keep the files in your secret manager and delete them.

!!! danger "Keep the vault keys and the salt key"

    The vault's master keys are not in the database, so a backup of the database cannot decrypt the company's secrets without them. LiteLLM's salt key encrypts the provider keys in LiteLLM's database, and changing it later makes them unreadable. Keep both in your secret manager, with your backups' keys.

- A key id of the vault is 1 to 64 letters, digits, dots, dashes or underscores. [Rotate the vault master key](maintenance.md#rotate-the-vault-master-key) adds a second key later.
- The bootstrap Secret is optional for the pods: they start without it. The server creates the first instance admin from it while the instance has no user, and at every start it also takes its email as the owner of each declared company that names no `owner` and has no Owner. Once the Secret is gone, such a company, one you declare later included, gets no Owner until you set its `owner` or an instance admin appoints one ([Open a company you are not a member of](instance-admin.md#open-a-company-you-are-not-a-member-of)). So set `owner` on every company before you delete the Secret. `bootstrap.existingSecret: ""` leaves the bootstrap variables out entirely.
- Without the LiteLLM Secret, LiteLLM's pod does not start, and the server starts without a bundled gateway: **Model access** says "This deployment bundles none." until the Secret exists and the server restarts.

## Choose the values

Write your values into a file, such as `values-prod.yaml`. `helm show values oci://ghcr.io/flowtivo/charts/tinfirm --version <version>` prints every value of a version with its default, and the chart's schema refuses a value of the wrong shape before anything is applied. [Server configuration](../reference/configuration.md) lists the server variable behind each value.

### Image

| Value | Default | Meaning |
|---|---|---|
| `image.tag` | empty | The server image's tag. Empty means the chart's own image: a release's chart runs that release's image, `v<version>`, and a development chart `0.0.0-sha-<commit>` the images `sha-<commit>`. |
| `image.repository` | `ghcr.io/flowtivo/tinfirm-server` | Point it at your registry when you mirror the images |
| `image.digest` | empty | `sha256:…`. Set, the chart pulls the image as tag and digest. |
| `imagePullSecrets` | `[]` | For a private registry |
| `litellm.modelApi.image.repository`, `.tag`, `.digest` | `ghcr.io/flowtivo/tinfirm-model-api`, the server's tag, empty | The model port's image, released with the server |

Deploy by digest, the digests you verified ([Verify your images](verify-images.md)): set both `image.digest` and `litellm.modelApi.image.digest`, or the model port's image is still pulled by its tag.

### Public URL

`publicUrl` is the address people reach Tinfirm at, for example `https://tinfirm.example.com`. The server builds from it the redirect URIs of the identity providers, the issuer of its own authorization server for AI clients, and the address of each company's MCP endpoint. With an `https` URL the session cookie is `Secure` and every answer carries HSTS.

Left empty, the chart takes the first ingress host, with `https` when `ingress.tls` is set. Without an ingress either, the server falls back to `http://localhost:8080`, and the install notes warn you. Always set it.

!!! warning "Choose it once"

    Changing the public URL later is a migration of its own, not a value to flip: it signs every AI client out, changes the redirect URIs at your identity providers, and changes the **Callback URL** that every system's **Sign-in app** is registered with. [Change the public URL](maintenance.md#change-the-public-url) lists what to do.

### Trusted proxies

`trustedProxies` is a Java regular expression matched against the address of the peer that connects to the server. The server takes the client's address from `X-Forwarded-For` only from a matching peer, and the throttle of failed sign-ins counts per client address.

- The default matches loopback only, so the server trusts no proxy and no pod can choose the client address with an `X-Forwarded-For` of its own. Behind an ingress controller or Gateway every client then has the controller's address, and the throttle counts them all as one: set `trustedProxies` to your controller's pod network, for example `'10\.244\.\d{1,3}\.\d{1,3}'` for `10.244.0.0/16` (`kubectl get nodes -o jsonpath='{.items[*].spec.podCIDR}'` lists the node ranges). The install notes warn while the default is set with an Ingress or HTTPRoute on.
- The install notes also warn while a release sets `trustedProxies` to every private range pods get (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, `fc00::/7`), since any pod that reaches port 8080 from such an address can choose the client address.
- Name your controller in the network policy ([Ingress to the server](#ingress-to-the-server)). Until you do, any pod that reaches port 8080 reaches the server; the install notes warn while an Ingress or HTTPRoute is on without it. Pods with global IPv6 addresses, as on an EKS IPv6 cluster, need their range added to `trustedProxies`.
- Behind a cloud load balancer, keep the client's address on its way in, for example with `externalTrafficPolicy: Local` or the PROXY protocol.
- `trustedProxies: ""` leaves the variable unset, and Tomcat's default, every private range, applies. Never set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to an empty value through `extraConfig`: the server would then trust every peer.

### Sessions

| Value | Default | Meaning |
|---|---|---|
| `session.idleTimeout` | `PT8H` | A session ends after this long without a request |
| `session.maxAge` | `P7D` | A session ends this long after sign-in, whatever its use |

Sessions live in the database, so any replica serves any request. Durations are ISO 8601; the schema refuses another format.

### Connections to systems

The server calls the company's systems, such as Jira, GitHub, GitLab and MCP servers, over `https` only, never to a loopback, link-local, private or other non-public address (the ranges no internet host is in, RFC 6890), and never follows a redirect. Relax this only for systems that really live inside your network.

| Value | Default | Meaning |
|---|---|---|
| `connection.allowHttp` | `false` | Accept `http` addresses for every company system and a company's own LiteLLM. Its token then travels in clear text. |
| `connection.allowPrivateAddresses` | `false` | Accept addresses on loopback, link-local or private networks for every company system and a company's own LiteLLM, for example a Jira Data Center on your network |
| `connection.internalHosts` | `[]` | Host names or IPv4 literals of services on your own network, without a scheme, port or wildcard. A company system on one of them may use `http` and must resolve to private addresses only, while every other host keeps the guard. Prefer it to `allowPrivateAddresses`, for example for a company MCP server in another namespace. |
| `connection.timeout` | `PT20S` | Connect and request timeout of one call to a system |

A member's personal MCP server never gets any of these exceptions. The server's network policy must let it reach these systems too: [What the server calls out to](#what-the-server-calls-out-to).

### Sign-in

| Value | Default | Meaning |
|---|---|---|
| `auth.localEnabled` | `true` | Sign-in with an email and a password |
| `auth.oidcAutoProvision` | `true` | Create a user at their first sign-in through an identity provider. The server never links a sign-in to an existing user by email. |
| `auth.oidc.providers` | `[]` | One entry per identity provider, each a button on the sign-in page |

Each provider entry needs `id` (lower-case letters and digits, starting with a letter), `issuerUri`, `clientId` and `existingSecret`, and may set `name` (the button's label), `scope`, `secretKeys.clientSecret`, `emailTrust`, `emailClaims`, `company`, `role`, `emailDomains`, and the role settings `roleClaim`, `roleMap.admin`, `roleMap.member`, `roleMap.viewer` and `roleSource`, which let the provider decide its members' roles at every sign-in, and `companies`, a list of further companies the claim fills, each with its `key` and `roleMap`. At the provider, register `<publicUrl>/login/oauth2/code/<id>` as the redirect URI and allow PKCE with `S256`. [Declare companies and how their people sign in](sign-in.md) explains each field, with Keycloak, Okta and Microsoft Entra ID.

SAML providers (Enterprise) go in `auth.saml.providers`, each with `id` and either `metadataUrl` (https) or `metadata`, the IdP's metadata document itself, which the chart mounts from a ConfigMap. The service provider's keys come from the Secret `auth.saml.sp.existingSecret`, one entry of `auth.saml.sp.credentials` per key, and `auth.saml.sp.activeCredential` names the one that signs. `networkPolicy.egress.saml` opens https for the metadata URLs. [Sign in with a SAML provider](saml.md) explains each field and the key rollover.

### Companies

`companies` declares the instance's companies. Nobody creates a company in the app. At each start the server creates a declared company that does not exist yet, and never deletes one, also not when you remove its entry.

```yaml
companies:
  - key: main           # lower-case letters and digits, starting with a letter; never change it
    name: Acme          # the name it is created with; renaming it in the app is kept
    owner: ""           # becomes its Owner while it has none; empty: the bootstrap admin
```

The default is one company, `main`, named `My Company`. The chart refuses an invalid or repeated key, and a provider whose `company` names no entry. More than one company needs an Enterprise licence: a company beyond the first waits, with a warning in the server's log, until a licence holds it, while a company that exists keeps working whatever the licence ([Editions and prices](../admin-guide/editions.md)). [Declare companies and how their people sign in](sign-in.md) has the whole procedure.

### Database

`database.host`, `.port`, `.name`, `.sslMode`, `.caCertSecret`, `.params` and `.url` say how the server reaches PostgreSQL; `litellm.database.*` the same for LiteLLM. [Prepare PostgreSQL](database.md#connect-the-chart-to-the-database) lists them, with TLS and pool sizes.

### Replicas and roles

| Value | Default | Meaning |
|---|---|---|
| `replicaCount` | `1` | Keep one. The live updates of conversations and runs are signalled within one server process, so with several replicas a page may miss them. High availability is planned. |
| `serverRoles` | `api,worker` | `worker` runs the background work: dispatching runs, polling trackers, firing schedules, writing results back, syncing spend. Keep both. |
| `strategy` | rolling, `maxSurge: 1`, `maxUnavailable: 0` | A new pod starts and becomes ready before the old one stops. `strategy: {type: Recreate}` stops the old pod first; the chart then leaves out `rollingUpdate`. |
| `podDisruptionBudget.enabled` | `true`, `maxUnavailable: 1` | Never blocks a node drain, even with one replica |

### Ingress

| Value | Default | Meaning |
|---|---|---|
| `ingress.enabled` | `false` | An Ingress for the server's port 8080. Set `className`, `hosts` and `tls`. |
| `ingress.annotations` | `{}` | Your controller's settings, below |
| `ingress.traefik.redirectHttps` | `false` | Traefik only: adds a Middleware that answers plain `http` with a redirect to `https`, so that the `Secure` session cookie is never lost. Needs Traefik's CRDs; leave it off when Traefik already redirects. |

Route the whole host to the server, with the path `/`: the web app, the API, sign-in, the MCP endpoint, the runner hosts' connections and the endpoints that chat platforms, webhooks and Google post to all live under it. The server's port 8081, with health and metrics, is never behind the Service or the Ingress.

A conversation's files are up to 25 MiB, and runner hosts send the agents' model requests through the server: up to 64 MiB, with answers that may pause for up to 10 minutes. ingress-nginx allows 1 MiB and 60 seconds by default, so raise them:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: 64m
    nginx.ingress.kubernetes.io/proxy-read-timeout: "600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "600"
  hosts:
    - host: tinfirm.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: tinfirm-tls
      hosts:
        - tinfirm.example.com
```

With another controller, set the same limits its own way.

### Gateway API

Instead of an Ingress, or beside it, the chart renders an `HTTPRoute` (`gateway.networking.k8s.io/v1`) from a Gateway your cluster runs, such as Envoy Gateway, Istio, Cilium, NGINX Gateway Fabric, Traefik or your cloud provider's. The cluster needs the Gateway API's CRDs.

| Value | Default | Meaning |
|---|---|---|
| `httpRoute.enabled` | `false` | The route from the Gateway to the server's port 8080 |
| `httpRoute.parentRefs` | `[]` | The Gateway listener the route attaches to: its `https` listener, by `sectionName`. Required. The listener must allow routes from the release's namespace (`allowedRoutes`). |
| `httpRoute.hostnames` | `[tinfirm.example.com]` | The host names. With `publicUrl` empty and no Ingress, the public URL is `https://` and the first of them. |
| `httpRoute.matches` | every path | The matches of the route's one rule. Keep every path. |
| `httpRoute.redirectParentRefs` | `[]` | The Gateway's `http` listener: a second route, `<release>-redirect-https`, answers every request there with a redirect to `https`, so that the `Secure` session cookie is never lost. Leave it empty when the Gateway redirects on its own or has no `http` listener. |

```yaml
httpRoute:
  enabled: true
  parentRefs:
    - name: public
      namespace: gateway-system
      sectionName: https
  hostnames:
    - tinfirm.example.com
  redirectParentRefs:
    - name: public
      namespace: gateway-system
      sectionName: http
```

Set the request size and timeouts of the [Ingress](#ingress) on the Gateway, its own way: 64 MiB, and answers that pause for up to 10 minutes.

### Resources and probes

| Value | Default | Meaning |
|---|---|---|
| `resources` | request 250m CPU and 1Gi, limit 1Gi | The server container |
| `javaToolOptions` | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` | The heap is 75% of the memory limit |
| `tmpVolume.sizeLimit`, `.medium` | `512Mi`, disk | `/tmp`, the only path the server writes to. `Memory` makes it a tmpfs that counts against the memory limit. |
| `probes.startup` | up to 5 minutes | Covers the first start, when the server migrates an empty database |
| `terminationGracePeriodSeconds`, `preStopSleepSeconds` | `45`, `5` | A stopping pod keeps serving for 5 seconds while the ingress drops it, then finishes its requests |

The containers run as user 10001, with a read-only root filesystem and no capabilities ([OpenShift and Pod Security](#openshift-and-pod-security)). Node placement takes the usual `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints` and `priorityClassName`.

### OpenShift and Pod Security

Every pod of the chart meets the `restricted` Pod Security Standard: no root user, no privilege escalation, no capabilities, and the `RuntimeDefault` seccomp profile. Every container also has a read-only root filesystem. The chart installs into a namespace that enforces `restricted`.

On OpenShift the `restricted-v2` security context constraint gives each namespace's pods a user and an `fsGroup` from the namespace's own range, and refuses any other. `openshift: auto`, the default, finds OpenShift by its API `security.openshift.io/v1`, as `helm install` and Argo CD see it, and then:

- leaves `runAsUser`, `runAsGroup` and `fsGroup` out of every security context the chart renders, so that OpenShift assigns them; your `extraContainers` keep what you set;
- adds OpenShift's DNS, in the namespace `openshift-dns` on port 5353, to the network policies' DNS rule.

`helm template` sees no cluster, so set `openshift: true` when you render the chart for OpenShift elsewhere, or pass `--api-versions security.openshift.io/v1`. `openshift: false` turns it off. OpenShift turns the chart's Ingress into a Route; set `route.openshift.io/termination: edge` in `ingress.annotations` for TLS at the router, and name the router in the network policy ([Ingress to the server](#ingress-to-the-server)).

### Extra configuration and log shipping

| Value | Meaning |
|---|---|
| `extraConfig` | More non-secret variables for the server, in its ConfigMap: any variable of [Server configuration](../reference/configuration.md) that has no value of its own |
| `extraEnv` | More environment entries, such as a variable from a Secret of yours (`valueFrom.secretKeyRef`) |
| `extraVolumes`, `extraVolumeMounts`, `extraContainers` | More volumes, their mounts in the server container, and more containers in the pod |

The server logs ECS JSON to standard output. To have a log shipper beside it read a file instead, let the server write the same lines to a volume:

```yaml
extraConfig:
  LOGGING_FILE_NAME: /var/log/tinfirm/server.log
  LOGGING_STRUCTURED_FORMAT_FILE: ecs
extraVolumes:
  - name: logs
    emptyDir:
      sizeLimit: 128Mi
extraVolumeMounts:
  - name: logs
    mountPath: /var/log/tinfirm
extraContainers:
  - name: log-shipper
    image: fluent/fluent-bit:<version>
    args: ["-i", "tail", "-p", "path=/var/log/tinfirm/server.log", "-o", "stdout"]
    securityContext:
      runAsUser: 10001
      readOnlyRootFilesystem: true
    volumeMounts:
      - name: logs
        mountPath: /var/log/tinfirm
```

Each extra container sets its own security context; the pod's (`podSecurityContext`) applies to all.

### Metrics

The server serves Prometheus metrics at `/actuator/prometheus` on port 8081, which no Service exposes. Either way below, `networkPolicy.ingress.metrics.enabled: true` lets your scraper in ([Ingress to the server](#ingress-to-the-server)); the install notes warn while it is off.

| Value | Default | Meaning |
|---|---|---|
| `metrics.scrapeAnnotations` | `false` | The `prometheus.io/*` annotations on the pod, for scrapers that find pods by them |
| `metrics.podMonitor.enabled` | `false` | A `PodMonitor` for the Prometheus Operator, which ignores the annotations. Needs its CRD. The VictoriaMetrics operator converts it too. |
| `metrics.podMonitor.labels`, `.annotations` | `{}` | The labels your Prometheus selects PodMonitors by, such as `release: kube-prometheus-stack` |
| `metrics.podMonitor.namespace` | the release's | Where to create it, when your Prometheus looks in its own namespace only |
| `metrics.podMonitor.interval`, `.scrapeTimeout` | your Prometheus's | Such as `30s` |

### Error reports

`errorReports.mode`, `.dsnSecret`, `.label` and `.proxy` decide whether the errors of the server and the web app stay on the installation or also go to the vendor: [Error reports](error-reports.md).

## The bundled model gateway

By default the chart deploys LiteLLM as the model gateway, beside the server: a Deployment and a Service named `<release>-litellm`. Its own port 4000, with every route, is for the server alone, which manages its keys and budgets through the admin API with the master key. The container `model-api` in the same pod serves LiteLLM's model routes alone on port 4001: the server sends its model requests there, and so may the company's other services, with LiteLLM keys of their own. Runner hosts never reach LiteLLM: the server relays their model requests.

Nothing exposes LiteLLM outside the release: its Service is always `ClusterIP`, the chart has no Ingress for it, and its network policy admits the server to both ports and nobody else to port 4000. You add the provider keys and the models through its admin API: [Run the model gateway](model-gateway.md).

| Value | Default | Meaning |
|---|---|---|
| `litellm.enabled` | `true` | `false` for a company that brings its own LiteLLM: no LiteLLM objects, and an admin enters the gateway's address and key on **Model access**. On the internet, the server reaches it over https by default; inside your network, it needs an egress rule ([What the server calls out to](#what-the-server-calls-out-to)). |
| `litellm.image.repository`, `.tag`, `.digest` | LiteLLM's non-root image, `ghcr.io/berriai/litellm-non_root`, pinned by tag and digest | Change them only with a Tinfirm release that names the new version |
| `litellm.tmpVolume.sizeLimit` | `256Mi` | `/tmp`, the only path LiteLLM writes to, its caches included |
| `litellm.replicaCount` | `1` | Keep one: without a shared Redis, which the chart does not run, several replicas would each count request limits and recent spend on their own |
| `litellm.adminUi` | `false` | LiteLLM's admin UI at `/ui`, reachable only through a port-forward. The admin API stays either way. |
| `litellm.existingSecret`, `litellm.secretKeys.*` | `tinfirm-litellm` | [Create the Secrets](#create-the-secrets) |
| `litellm.database.*` | the database `litellm`, role `litellm`, on `database.host` with the same TLS settings | [Connect LiteLLM to its database](database.md#connect-litellm-to-its-database) |
| `litellm.service.port` | `4000` | LiteLLM's own port on the Service, the one the server's admin address names |
| `litellm.resources` | request 250m CPU and 1Gi, limit 2Gi | |
| `litellm.probes.startup` | up to 10 minutes | The first start migrates LiteLLM's empty database |
| `litellm.extraEnv` | `[]` | More LiteLLM variables, for example `LITELLM_LOCAL_MODEL_COST_MAP=True` where LiteLLM cannot fetch its price list from GitHub |
| `litellm.modelApi.port` | `4001` | The model port on the Service |
| `litellm.modelApi.maxRequestBytes` | 64 MiB | The largest request body passed on; larger is `413` |
| `litellm.modelApi.maxInflightBytes` | 128 MiB | The most bytes of request bodies held at once, at least twice `maxRequestBytes`; beyond, `429`. Keep it within the container's memory limit. |
| `litellm.modelApi.idleTimeout` | `PT10M` | The longest wait for LiteLLM's answer to start, and between two of its parts |
| `litellm.modelApi.from` | `[]` | More network policy peers that may call the model port, such as the namespace of your services. The server always may. |
| `litellm.modelApi.resources` | request 50m CPU and 256Mi, limit 384Mi | |

LiteLLM runs from its non-root image as user 65534, the model port as user 10001, both with a read-only root filesystem, no capabilities and no privilege escalation ([OpenShift and Pod Security](#openshift-and-pod-security)).

## The runner pod

With `runner.enabled: true` the chart also runs a runner host, as a pod: the company runner host of one company in `companies`, whose sandboxes are VMs that it creates and deletes, on DigitalOcean a Droplet per run. You need no machine for it, and nobody registers it in the app: the server registers it at its start, with the token you put in a Secret, and replaces the token when you change it. One pod serves many runs at once, each in a VM of its own; it needs no Docker and no privilege.

Sandboxes as VMs are part of Pro and Enterprise ([Licence](licence.md)). Without such a key the server refuses the host, and the pod stops.

Before you turn it on, prepare on DigitalOcean:

- the VM image: a private snapshot that holds the runner images and the runner host of the same release (the repository's `ee/runner/images/vm`), rebuilt after every upgrade;
- a VPC of the sandboxes' own, peered with the cluster's when the pod reaches the VMs on their private addresses (`connect: private`);
- a Cloud Firewall on the tag `tinfirm-sandbox` that lets in only the cluster's addresses, to the bridge's port 7443, and lets nothing out;
- an API token that may create, read and delete Droplets and read the firewall and the VPC.

Then create the two Secrets, in the release's namespace:

```sh
printf 'tfr_%s' "$(openssl rand 32 | basenc --base64url | tr -d '=')" > runner-token
kubectl -n tinfirm create secret generic tinfirm-runner --from-file=token=./runner-token
kubectl -n tinfirm create secret generic tinfirm-digitalocean --from-file=token=./digitalocean-token
```

And add the values:

```yaml
runner:
  enabled: true
  company: main            # the key of a company in `companies`
  name: kubernetes         # the host's name on that company's Runner hosts page
  maxConcurrentRuns: 10
  vm:
    template: sandbox
    digitalocean:
      connect: private
      templates:
        sandbox:
          region: fra1
          size: s-4vcpu-8gb
          image: "123456789"     # the VM image's snapshot id
          vpc: VPC_UUID
          firewall: FIREWALL_ID
```

| Value | Default | Meaning |
|---|---|---|
| `runner.enabled` | `false` | Runs the runner pod |
| `runner.company` | `main` | The key of the company whose host it is |
| `runner.name` | `kubernetes` | The host's name in that company. A host of this name that someone registered in the app stops the server's start: the server never takes it over. |
| `runner.existingSecret`, `runner.secretKeys.token` | `tinfirm-runner`, `token` | The host's token, `tfr_` and 43 base64url characters. The server gets it at its start and keeps only its hash; the pod reads it from a file. |
| `runner.serverUrl` | the public URL | The server's URL that the pod connects to, https only, as any runner host's |
| `runner.maxConcurrentRuns` | `4` | The most runs at once, up to 1000; admins can lower it on **Runner hosts**, never raise it |
| `runner.vm.template` | `sandbox` | The template, a key of `runner.vm.digitalocean.templates`, that every VM is created from |
| `runner.vm.digitalocean.existingSecret`, `.secretKeys.token` | `tinfirm-digitalocean`, `token` | The DigitalOcean API token. The driver holds it; the runner host never reads it. |
| `runner.vm.digitalocean.connect` | `private` | `private`: the pod reaches the VMs on their VPC addresses; `public`: on their public addresses, which the firewall opens to the cluster's egress addresses alone |
| `runner.vm.digitalocean.templates` | none | Each template's `region`, `size`, `image` (the snapshot id), `vpc`, `firewall` and optional `bridgePort` |
| `runner.vm.port`, `.startTimeout`, `.imageVersion`, `.probes` | `7443`, `900`, `warn`, none | The bridge's port in the VM; how long a VM may take to come up, in seconds; what to do with a VM image of another release (`warn` or `refuse`); more addresses a VM must not reach |
| `runner.pool.*` | at most 4 ready VMs, of Claude Code's image | The ready VMs that admins may ask for on **Runner hosts**; `pool.runtime: any` lets them serve every runtime |
| `runner.persistence.*` | a 20Gi claim | The pod's data: the records that let a restarted pod take its runs back, its repository mirrors and the runs' outputs. Disabled, a restart loses the runs in progress. |
| `runner.terminationGracePeriodSeconds` | `3600` | How long an upgrade waits for the active runs to finish |
| `runner.extraConfig` | `{}` | More settings of the runner host as its variables, such as `TINFIRM_RUNNER_EGRESS_ALLOW` (one host per line) for package registries the sandboxes may reach. Never credentials. |
| `runner.resources` | request 250m CPU and 512Mi, limit 2Gi | |

The pod runs as user 10001 with a read-only root filesystem, no capabilities and no privilege escalation, and never calls the Kubernetes API. On an upgrade it drains first: it takes no new runs and lets the active ones finish. When `terminationGracePeriodSeconds` ends first, it stops, and the next pod takes its runs back: their work is pushed and their sessions saved, and they end as failed, so the next message of each conversation goes on from there.

To rotate the token, put a new one in the Secret, then restart the server, which replaces the old token at its start and closes its connection, and then the pod.

## Install the chart

A minimal values file for production:

```yaml title="values-prod.yaml"
image:
  digest: sha256:<the server image's digest>
publicUrl: https://tinfirm.example.com
database:
  host: tinfirm-db.example.internal
  sslMode: verify-full
  caCertSecret:
    name: tinfirm-database-ca
companies:
  - key: main
    name: Acme
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: 64m
    nginx.ingress.kubernetes.io/proxy-read-timeout: "600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "600"
  hosts:
    - host: tinfirm.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: tinfirm-tls
      hosts:
        - tinfirm.example.com
auth:
  oidc:
    providers:
      - id: sso
        name: Acme SSO
        issuerUri: https://sso.example.com/realms/acme
        clientId: tinfirm
        existingSecret: tinfirm-oidc-sso
networkPolicy:
  ingress:
    controller:             # your ingress controller's pods: Network policy, below
      namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-nginx
      podSelector:
        matchLabels:
          app.kubernetes.io/name: ingress-nginx
```

Install it with the chart's version, or with the chart's digest after you verified it ([Verify the Helm chart](verify-images.md#verify-the-helm-chart)):

```sh
helm upgrade --install tinfirm oci://ghcr.io/flowtivo/charts/tinfirm \
  --version <version> \
  --namespace tinfirm \
  --values values-prod.yaml
```

The install notes list the Secrets the pods wait for, the database the server connects to, the public URL, each provider's redirect URI and direct sign-in link (`<publicUrl>/login?provider=<id>`), and LiteLLM's database and addresses, and warn while the network policy names no ingress controller. They never print a password.

With the release name `tinfirm`, the server's Deployment is `tinfirm` and LiteLLM's `tinfirm-litellm`. A release name without `tinfirm` in it gives `<release>-tinfirm`, unless you set `fullnameOverride`.

## Check the rollout

The first start migrates the empty database, which can take a few minutes. Port 8081 is not exposed, so check the health endpoints through a port-forward:

```sh
kubectl -n tinfirm rollout status deployment/tinfirm
kubectl -n tinfirm port-forward deploy/tinfirm 8081:8081
curl -s localhost:8081/actuator/health/readiness   # {"status":"UP"} while PostgreSQL answers
```

| Probe | Asks | What it means |
|---|---|---|
| Startup and liveness | `/actuator/health/liveness` | The server's own state only, so a database outage never restarts the pod |
| Readiness | `/actuator/health/readiness` | The server and the database: a pod that cannot reach PostgreSQL leaves the Service until it can again; a pod whose database role could bypass row-level security never joins it ([database roles](database.md)) |

Then LiteLLM:

```sh
kubectl -n tinfirm rollout status deployment/tinfirm-litellm
kubectl -n tinfirm port-forward deploy/tinfirm-litellm 4000:4000
curl -s localhost:4000/health/readiness   # "db": "connected"
```

Open `https://tinfirm.example.com` and sign in as the bootstrap admin, or through your identity provider. Next, add a model to LiteLLM ([Run the model gateway](model-gateway.md)) and connect a runner host ([Install and run runner hosts](runner-hosts.md)).

## Network policy

`networkPolicy.enabled` is on by default: the server's pods accept only the traffic below, and with `networkPolicy.egress.enabled`, also on by default, they reach only what the policy allows.

### Ingress to the server

Port 8080 is for your ingress controller or Gateway. The chart cannot know which you run, so by default the policy admits port 8080 from any source, and only port 8080: the chart then works with every controller, Gateway and load balancer. Name yours in `networkPolicy.ingress.controller` to close the port to everything else, which also keeps other pods from setting the client address the sign-in throttle counts ([Trusted proxies](#trusted-proxies)). For Traefik:

```yaml
networkPolicy:
  ingress:
    controller:
      namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: traefik
      podSelector:
        matchLabels:
          app.kubernetes.io/name: traefik
```

The labels of common controllers, in the namespaces of their default installs; check yours with `kubectl get pods -n <namespace> --show-labels`:

| Controller | `namespaceSelector.matchLabels` | `podSelector.matchLabels` |
|---|---|---|
| ingress-nginx | `kubernetes.io/metadata.name: ingress-nginx` | `app.kubernetes.io/name: ingress-nginx` |
| Traefik | `kubernetes.io/metadata.name: traefik` | `app.kubernetes.io/name: traefik` |
| Envoy Gateway | `kubernetes.io/metadata.name: envoy-gateway-system` | `app.kubernetes.io/name: envoy`, `app.kubernetes.io/component: proxy` |
| OpenShift's router | `policy-group.network.openshift.io/ingress: ""` | none |

A cloud load balancer that sends straight to the pods' addresses, such as an AWS Application Load Balancer in IP mode or Google's container-native load balancing, connects from addresses, not pods: leave `controller` empty and add `ipBlock` peers of its source ranges in `networkPolicy.ingress.extraFrom` (your VPC's subnets, or Google's `35.191.0.0/16` and `130.211.0.0/22`). Cilium's own Ingress and Gateway connect from the Cilium agent, which a Kubernetes network policy cannot name: leave `controller` empty there, or use Cilium's own policies.

An empty `podSelector` admits the whole namespace. `networkPolicy.ingress.extraFrom` adds more peers for port 8080; with `controller` empty, they alone are admitted. `networkPolicy.ingress.metrics.enabled: true` admits your scraper to port 8081, from the namespace `monitoring` by default. The kubelet's probes are not subject to network policy.

### Egress of the server

| Rule | Values | Default |
|---|---|---|
| DNS | `networkPolicy.egress.dns` | The `kube-dns` pods in `kube-system`, port 53 |
| PostgreSQL | `networkPolicy.egress.database.to`, `.port` | Any address on the database port. Narrow it to your database. |
| The bundled LiteLLM | none | LiteLLM's pods, ports 4000 and 4001 |
| Identity providers | `networkPolicy.egress.oidc.to`, `.ports` | Any address on port 443, only while `auth.oidc.providers` is not empty |
| The internet over https | `networkPolicy.egress.systems.to`, `.ports` | Port 443 on every public IPv4 and IPv6 address: not the private, shared, unique local, site-local, loopback, link-local, unspecified, multicast or reserved ranges, nor the special-use ranges no internet host is in (documentation, benchmarking, IETF protocol assignments, discard-only), nor the local-use translation prefix `64:ff9b:1::/48`. `to: []` drops the rule. |
| Everything else | `networkPolicy.egress.extra` | Nothing |

On OpenShift the DNS rule also admits OpenShift's DNS ([OpenShift and Pod Security](#openshift-and-pod-security)). Every rule that defaults to any address has an IPv4 and an IPv6 peer, so the defaults hold on IPv6 and dual-stack clusters; a cluster without IPv6 ignores the IPv6 peers.

### What the server calls out to

Besides its database and LiteLLM, the server calls:

- the company's systems at their addresses: Jira Data Center, Jira Cloud (`<site>.atlassian.net`, and `auth.atlassian.com` and `api.atlassian.com` for sign-in to Jira Cloud), GitHub (`api.github.com`, and `github.com` for sign-in), GitLab, and MCP servers;
- Slack (`slack.com`, `files.slack.com` and Socket Mode's hosts under `slack.com`) and Microsoft Teams (`login.microsoftonline.com`, `login.botframework.com` and `smba.trafficmanager.net`);
- the metadata documents of people's AI clients, such as `claude.ai`;
- Google (`www.googleapis.com` and `gmail.googleapis.com`), when you run the Gmail add-on;
- the error tracker of [Error reports](error-reports.md), when you set one;
- a company's own LiteLLM, when it uses one instead of the bundled gateway;
- NodeLocal DNSCache, if your cluster uses it.

A network policy matches addresses, not host names, and most of these services sit behind public addresses that change, so the chart's rule `networkPolicy.egress.systems` allows `https` to every public address. Narrow its `to` to your egress proxy if you have one. A system inside your network needs a rule of its own in `networkPolicy.egress.extra`:

```yaml
networkPolicy:
  egress:
    extra:
      # A Jira Data Center on your network (connection.allowPrivateAddresses).
      - to:
          - ipBlock:
              cidr: 10.20.30.40/32
        ports:
          - protocol: TCP
            port: 8443
      # A company MCP server in another namespace (connection.internalHosts).
      - to:
          - namespaceSelector:
              matchLabels:
                kubernetes.io/metadata.name: mcp-tools
        ports:
          - protocol: TCP
            port: 8080
```

Each entry is a Kubernetes `NetworkPolicyEgressRule`. A system the server cannot reach fails its test on its page in the app.

### The model gateway's policy

LiteLLM's pods have a policy of their own:

| Direction | Peers | Ports |
|---|---|---|
| Ingress | The server's pods, and nothing else; no value adds a peer | 4000, LiteLLM itself |
| Ingress | The server's pods and `litellm.modelApi.from` | 4001, the model port |
| Egress | DNS, and PostgreSQL through `networkPolicy.egress.database.to` | 53, and LiteLLM's database port |
| Egress | The model providers: `networkPolicy.litellm.egress.providers.to`, `.ports` | Any address on 443 by default: the providers' APIs and LiteLLM's price list on `raw.githubusercontent.com`. Narrow it to your proxy or private endpoints if you have them. |
| Egress | `networkPolicy.litellm.egress.extra` | Nothing. `networkPolicy.egress.extra` does not apply here: add NodeLocal DNSCache here too. |

### The runner pod's policy

The runner pod (`runner.enabled`) has a policy of its own:

| Direction | Peers | Ports |
|---|---|---|
| Ingress | None, and no value adds one: the pod dials the server and the VMs, and the sandboxes' traffic comes back over those connections | |
| Egress | DNS | 53 |
| Egress | `networkPolicy.runner.egress.https.to`, `.ports` | Any address on 443 by default: the server's public URL (which may resolve to the cluster's own load balancer), DigitalOcean's API, the git hosts, the model gateways, and what the sandboxes may reach |
| Egress | The VMs: `networkPolicy.runner.egress.vms.to` | `runner.vm.port`, any address by default; narrow it to the sandboxes' VPC |
| Egress | `networkPolicy.runner.egress.extra` | Nothing. An ingress controller in the cluster whose pods listen on another port, such as 8443, needs a rule for them here. |

## Next steps

- [Upgrade, back up and rotate secrets](maintenance.md)
- [Declare companies and how their people sign in](sign-in.md)
- [Run the model gateway](model-gateway.md)
- [Install and run runner hosts](runner-hosts.md)
