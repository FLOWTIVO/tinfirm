{{/* Chart name, truncated to the 63 characters a DNS label allows. */}}
{{- define "tinfirm.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Fully qualified app name: the release name, plus the chart name unless it contains it. */}}
{{- define "tinfirm.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "tinfirm.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Image tag: `image.tag`, else the chart's own image: v<appVersion>, or the appVersion as it is
when it is no version number (`sha-<commit>`, which CI writes into a chart it publishes for a
commit of main).
*/}}
{{- define "tinfirm.imageTag" -}}
{{- $app := .Chart.AppVersion | toString }}
{{- $default := ternary (printf "v%s" $app) $app (regexMatch "^[0-9]" $app) }}
{{- .Values.image.tag | default "" | toString | default $default }}
{{- end }}

{{- define "tinfirm.image" -}}
{{- if .Values.image.digest }}
{{- printf "%s:%s@%s" .Values.image.repository (include "tinfirm.imageTag" .) .Values.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.image.repository (include "tinfirm.imageTag" .) }}
{{- end }}
{{- end }}

{{/* Selector labels. Immutable on a Deployment: never add to them. */}}
{{- define "tinfirm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tinfirm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: server
{{- end }}

{{- define "tinfirm.labels" -}}
helm.sh/chart: {{ include "tinfirm.chart" . }}
{{ include "tinfirm.selectorLabels" . }}
app.kubernetes.io/version: {{ include "tinfirm.imageTag" . | replace "+" "_" | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/part-of: tinfirm
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "tinfirm.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "tinfirm.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
TINFIRM_PUBLIC_URL: publicUrl, else the first ingress host, else https:// and the HTTPRoute's
first hostname (it attaches to the Gateway's https listener). Empty = not set.
*/}}
{{- define "tinfirm.publicUrl" -}}
{{- if .Values.publicUrl }}
{{- .Values.publicUrl | trimSuffix "/" }}
{{- else if and .Values.ingress.enabled .Values.ingress.hosts }}
{{- $scheme := ternary "https" "http" (gt (len .Values.ingress.tls) 0) }}
{{- printf "%s://%s" $scheme (first .Values.ingress.hosts).host }}
{{- else if and .Values.httpRoute.enabled .Values.httpRoute.hostnames }}
{{- printf "https://%s" (first .Values.httpRoute.hostnames) }}
{{- end }}
{{- end }}

{{/*
"true" on OpenShift (the value `openshift`; auto: the cluster serves security.openshift.io/v1),
else empty.
*/}}
{{- define "tinfirm.openshift" -}}
{{- if eq (toString .Values.openshift) "auto" }}
{{- if .Capabilities.APIVersions.Has "security.openshift.io/v1" }}true{{ end }}
{{- else if .Values.openshift }}true{{ end }}
{{- end }}

{{/*
A security context of the values, as YAML: on OpenShift without runAsUser, runAsGroup and
fsGroup, which its restricted-v2 SCC assigns from the namespace's range and refuses otherwise.
Called with (dict "context" <the values' context> "root" $).
*/}}
{{- define "tinfirm.securityContext" -}}
{{- $context := deepCopy (.context | default dict) }}
{{- if include "tinfirm.openshift" .root }}
{{- $context = omit $context "runAsUser" "runAsGroup" "fsGroup" }}
{{- end }}
{{- if $context }}
{{- toYaml $context }}
{{- else }}
{{- "{}" }}
{{- end }}
{{- end }}

{{/*
The NetworkPolicies' DNS egress rules: networkPolicy.egress.dns on port 53, and on OpenShift
its DNS too, whose pods in openshift-dns answer on 5353 behind the Service's port 53 (a
NetworkPolicy sees the pod's port).
*/}}
{{- define "tinfirm.networkPolicy.dnsEgress" -}}
{{- $dns := .Values.networkPolicy.egress.dns -}}
# DNS.
- to:
    - namespaceSelector:
        {{- toYaml ($dns.namespaceSelector | default dict) | nindent 8 }}
      {{- with $dns.podSelector }}
      podSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
  ports:
    - protocol: UDP
      port: 53
    - protocol: TCP
      port: 53
{{- if include "tinfirm.openshift" . }}
# OpenShift's DNS.
- to:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: openshift-dns
  ports:
    - protocol: UDP
      port: 5353
    - protocol: TCP
      port: 5353
{{- end }}
{{- end }}

{{/* Where the database CA certificate is mounted. */}}
{{- define "tinfirm.databaseCaDir" -}}
/etc/tinfirm/database-ca
{{- end }}

{{/* SPRING_DATASOURCE_URL: database.url, else built from host, port, name and TLS settings. */}}
{{- define "tinfirm.jdbcUrl" -}}
{{- $db := .Values.database }}
{{- if $db.url }}
{{- $db.url }}
{{- else }}
{{- $params := list }}
{{- with $db.sslMode }}{{ $params = append $params (printf "sslmode=%s" .) }}{{ end }}
{{- if $db.caCertSecret.name }}
{{- $params = append $params (printf "sslrootcert=%s/%s" (include "tinfirm.databaseCaDir" .) $db.caCertSecret.key) }}
{{- end }}
{{- with $db.params }}{{ $params = append $params . }}{{ end }}
{{- printf "jdbc:postgresql://%s:%v/%s" $db.host $db.port $db.name }}
{{- if $params }}?{{ join "&" $params }}{{ end }}
{{- end }}
{{- end }}

{{/* LiteLLM, the bundled model gateway (ADR-0059): `<fullname>-litellm`. */}}
{{- define "tinfirm.litellm.fullname" -}}
{{- printf "%s-litellm" (include "tinfirm.fullname" . | trunc 55 | trimSuffix "-") }}
{{- end }}

{{/* LiteLLM's selector labels. Immutable on a Deployment: never add to them. */}}
{{- define "tinfirm.litellm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tinfirm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: litellm
{{- end }}

{{- define "tinfirm.litellm.labels" -}}
helm.sh/chart: {{ include "tinfirm.chart" . }}
{{ include "tinfirm.litellm.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.litellm.image.tag | replace "+" "_" | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/part-of: tinfirm
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "tinfirm.litellm.image" -}}
{{- $img := .Values.litellm.image }}
{{- if $img.digest }}
{{- printf "%s:%s@%s" $img.repository $img.tag $img.digest }}
{{- else }}
{{- printf "%s:%s" $img.repository $img.tag }}
{{- end }}
{{- end }}

{{/* TINFIRM_MODEL_GATEWAY_ADMIN_URL: LiteLLM's Service inside the cluster, plain http (admin API and relayed model requests). */}}
{{- define "tinfirm.litellm.adminUrl" -}}
{{- printf "http://%s.%s.svc:%v" (include "tinfirm.litellm.fullname" .) .Release.Namespace .Values.litellm.service.port }}
{{- end }}

{{/* LiteLLM's database port: litellm.database.port, else database.port. */}}
{{- define "tinfirm.litellm.databasePort" -}}
{{- .Values.litellm.database.port | default .Values.database.port }}
{{- end }}

{{/* LiteLLM's DATABASE_HOST, `host:port`: litellm.database.*, else Tinfirm's database.*. */}}
{{- define "tinfirm.litellm.databaseHost" -}}
{{- printf "%s:%v" (.Values.litellm.database.host | default .Values.database.host) (include "tinfirm.litellm.databasePort" .) }}
{{- end }}

{{/* LiteLLM's database CA: litellm.database.caCertSecret, else database.caCertSecret. */}}
{{- define "tinfirm.litellm.databaseCaSecret" -}}
{{- if .Values.litellm.database.caCertSecret.name }}
{{- toJson .Values.litellm.database.caCertSecret }}
{{- else }}
{{- toJson .Values.database.caCertSecret }}
{{- end }}
{{- end }}

{{/*
The litellm.database rules the schema cannot express. A URL or a user from a Secret needs
that Secret named: without the check the chart would fall back to the split settings and
the password from litellm.existingSecret, silently.
*/}}
{{- define "tinfirm.litellm.validateDatabase" -}}
{{- $db := .Values.litellm.database }}
{{- if and (or $db.secretKeys.url $db.secretKeys.username) (not $db.existingSecret) }}
{{- fail "litellm.database.secretKeys.url and .username are keys of litellm.database.existingSecret: set it to the Secret that holds them" }}
{{- end }}
{{- end }}

{{/* LiteLLM's DATABASE_PASSWORD, {name, key}: litellm.database.existingSecret, else litellm.existingSecret. */}}
{{- define "tinfirm.litellm.databasePasswordRef" -}}
{{- $l := .Values.litellm }}
{{- if $l.database.existingSecret }}
{{- dict "name" $l.database.existingSecret "key" $l.database.secretKeys.password | toJson }}
{{- else }}
{{- dict "name" $l.existingSecret "key" $l.secretKeys.databasePassword | toJson }}
{{- end }}
{{- end }}

{{/*
litellm.database.params as a map, for LiteLLM's general_settings.database_extra_connection_params:
LiteLLM appends them to its connection URL, over its own defaults. Empty with a URL from a
Secret, which replaces them, as database.url replaces database.params.
*/}}
{{- define "tinfirm.litellm.databaseParams" -}}
{{- $db := .Values.litellm.database }}
{{- $params := dict }}
{{- if and $db.params (not $db.secretKeys.url) }}
{{- range splitList "&" $db.params }}
{{- $kv := splitn "=" 2 . }}
{{- $_ := set $params $kv._0 ($kv._1 | default "") }}
{{- end }}
{{- end }}
{{- toJson $params }}
{{- end }}

{{/*
The database port the LiteLLM pods' NetworkPolicy opens. With a URL from a Secret the chart
cannot read the port, so litellm.database.port must name it; otherwise litellm.database.port,
else networkPolicy.egress.database.port, else database.port.
*/}}
{{- define "tinfirm.litellm.networkPolicyDatabasePort" -}}
{{- $db := .Values.litellm.database }}
{{- if $db.secretKeys.url }}
{{- required "litellm.database.port: set it to the port in the URL of litellm.database.secretKeys.url. The chart cannot read the URL, and the LiteLLM NetworkPolicy opens only that port to the database" $db.port }}
{{- else }}
{{- $db.port | default .Values.networkPolicy.egress.database.port | default .Values.database.port }}
{{- end }}
{{- end }}

{{/* The Traefik Middleware of ingress.traefik.redirectHttps: `<fullname>-redirect-https`. */}}
{{- define "tinfirm.redirectHttpsName" -}}
{{- printf "%s-redirect-https" (include "tinfirm.fullname" . | trunc 48 | trimSuffix "-") }}
{{- end }}

{{/*
The Ingress's annotations: ingress.annotations, with the redirect Middleware of
ingress.traefik.redirectHttps put first in traefik.ingress.kubernetes.io/router.middlewares
(Traefik names a CRD middleware `<namespace>-<name>@kubernetescrd`).
*/}}
{{- define "tinfirm.ingressAnnotations" -}}
{{- $annotations := deepCopy (.Values.ingress.annotations | default dict) }}
{{- if .Values.ingress.traefik.redirectHttps }}
{{- $key := "traefik.ingress.kubernetes.io/router.middlewares" }}
{{- $ref := printf "%s-%s@kubernetescrd" .Release.Namespace (include "tinfirm.redirectHttpsName" .) }}
{{- $others := get $annotations $key }}
{{- $_ := set $annotations $key (ternary (printf "%s,%s" $ref $others) $ref (not (empty $others))) }}
{{- end }}
{{- if $annotations }}
{{- toYaml $annotations }}
{{- end }}
{{- end }}

{{/* The base URL of LiteLLM's OpenAI-compatible API, for the company's services of litellm.from. */}}
{{- define "tinfirm.litellm.openAiBaseUrl" -}}
{{- printf "%s/v1" (include "tinfirm.litellm.adminUrl" .) }}
{{- end }}

{{/*
Fails the render on companies the server would refuse (ADR-0075, ADR-0089): a key that is not
1 to 40 lower-case letters and digits starting with a letter (checked here too, since
--skip-schema-validation skips the schema), a key used twice, a company without a name, a
provider whose company no entry of `companies` declares, and a provider whose `companies`
(the companies its role claim fills) name an undeclared key, the provider's own company, or a
key twice, or lack roleClaim.
*/}}
{{- define "tinfirm.companies.validate" -}}
{{- $keys := list }}
{{- range .Values.companies }}
{{- if not (regexMatch "^[a-z][a-z0-9]{0,39}$" (toString .key)) }}
{{- fail (printf "companies: key %q must be 1 to 40 lower-case letters and digits, starting with a letter" (toString .key)) }}
{{- end }}
{{- if has .key $keys }}
{{- fail (printf "companies: the key %q is used twice" .key) }}
{{- end }}
{{- $keys = append $keys .key }}
{{- $_ := required (printf "companies[%s].name is required: the name the company is created with" .key) .name }}
{{- end }}
{{- range .Values.auth.oidc.providers }}
{{- $id := .id }}
{{- with .company }}
{{- if not (has . $keys) }}
{{- fail (printf "auth.oidc.providers[%s].company: %q is not the key of a company in `companies`" $id .) }}
{{- end }}
{{- end }}
{{- include "tinfirm.providerCompanies.validate" (dict "kind" "oidc" "provider" . "keys" $keys) }}
{{- end }}
{{- range .Values.auth.saml.providers }}
{{- include "tinfirm.providerCompanies.validate" (dict "kind" "saml" "provider" . "keys" $keys) }}
{{- end }}
{{- end }}

{{/*
The companies a provider's role claim fills beside its own (ADR-0089): each a declared key, not
the provider's own company, named once, with a role map; and the provider names roleClaim.
*/}}
{{- define "tinfirm.providerCompanies.validate" -}}
{{- $id := .provider.id }}
{{- $where := printf "auth.%s.providers[%s].companies" .kind $id }}
{{- $seen := list }}
{{- range .provider.companies }}
{{- if not (has (toString .key) $.keys) }}
{{- fail (printf "%s: %q is not the key of a company in `companies`" $where (toString .key)) }}
{{- end }}
{{- if eq (toString .key) (toString $.provider.company) }}
{{- fail (printf "%s: %q is the provider's own company; put its values in roleMap" $where (toString .key)) }}
{{- end }}
{{- if has .key $seen }}
{{- fail (printf "%s: the key %q is used twice" $where .key) }}
{{- end }}
{{- $seen = append $seen .key }}
{{- $mapped := false }}
{{- with .roleMap }}
{{- if or .admin .member .viewer }}
{{- $mapped = true }}
{{- end }}
{{- end }}
{{- if not $mapped }}
{{- fail (printf "%s[%s].roleMap needs admin, member or viewer" $where .key) }}
{{- end }}
{{- if not $.provider.roleClaim }}
{{- fail (printf "%s needs roleClaim, the claim whose values it maps" $where) }}
{{- end }}
{{- end }}
{{- end }}

{{/* TINFIRM_CONNECTION_INTERNAL_HOSTS: connection.internalHosts. */}}
{{- define "tinfirm.internalHosts" -}}
{{- join "," (.Values.connection.internalHosts | default list) }}
{{- end }}

{{/*
"true" when a SAML provider's metadata is a document the chart mounts (auth.saml.providers[].metadata), else empty.
*/}}
{{- define "tinfirm.samlMetadataFiles" -}}
{{- range .Values.auth.saml.providers }}{{ if .metadata }}true{{ end }}{{ end }}
{{- end }}

{{/*
"true" when a SAML provider's metadata is a URL the server reads (auth.saml.providers[].metadataUrl), else empty.
*/}}
{{- define "tinfirm.samlMetadataUrls" -}}
{{- range .Values.auth.saml.providers }}{{ if and .metadataUrl (not .metadata) }}true{{ end }}{{ end }}
{{- end }}

{{/* The runner pod (ADR-0101, decision 8): `<fullname>-runner`. */}}
{{- define "tinfirm.runner.fullname" -}}
{{- printf "%s-runner" (include "tinfirm.fullname" . | trunc 56 | trimSuffix "-") }}
{{- end }}

{{/* The runner pod's selector labels. Immutable on a Deployment: never add to them. */}}
{{- define "tinfirm.runner.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tinfirm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: runner
{{- end }}

{{- define "tinfirm.runner.labels" -}}
helm.sh/chart: {{ include "tinfirm.chart" . }}
{{ include "tinfirm.runner.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.runner.image.tag | default (include "tinfirm.imageTag" .) | toString | replace "+" "_" | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/part-of: tinfirm
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* The runner pod's image: the server's tag unless one is set. */}}
{{- define "tinfirm.runner.image" -}}
{{- $img := .Values.runner.image }}
{{- $tag := $img.tag | default (include "tinfirm.imageTag" .) | toString }}
{{- if $img.digest }}
{{- printf "%s:%s@%s" $img.repository $tag $img.digest }}
{{- else }}
{{- printf "%s:%s" $img.repository $tag }}
{{- end }}
{{- end }}

{{/* TINFIRM_RUNNER_SERVER: runner.serverUrl, else the public URL; https only, as the runner host takes it. */}}
{{- define "tinfirm.runner.serverUrl" -}}
{{- $url := .Values.runner.serverUrl | default (include "tinfirm.publicUrl" .) | trimSuffix "/" }}
{{- if not $url }}
{{- fail "runner.enabled needs the server's URL: set publicUrl, an ingress or HTTPRoute host, or runner.serverUrl" }}
{{- end }}
{{- if not (hasPrefix "https://" $url) }}
{{- fail (printf "runner: the server's URL %q is not https: the runner host connects over https only (set runner.serverUrl, or ingress.tls)" $url) }}
{{- end }}
{{- $url }}
{{- end }}

{{/* Where the runner pod's Secrets are mounted. */}}
{{- define "tinfirm.runner.tokenDir" -}}
/etc/tinfirm/runner-token
{{- end }}
{{- define "tinfirm.runner.digitaloceanTokenDir" -}}
/etc/tinfirm/digitalocean-token
{{- end }}
{{- define "tinfirm.runner.digitaloceanConfigDir" -}}
/etc/tinfirm/digitalocean
{{- end }}
{{- define "tinfirm.runner.nodeConfigDir" -}}
/etc/tinfirm/node-driver
{{- end }}
{{- define "tinfirm.runner.nodeCertificateDir" -}}
/etc/tinfirm/node-driver-certificate
{{- end }}
{{/* The key's copy, the pod's user's, 0600 (node.rs refuses any other), in a memory volume. */}}
{{- define "tinfirm.runner.nodeKeyDir" -}}
/etc/tinfirm/node-driver-key
{{- end }}
{{/* The key in the Secret's volume (root's, 0440), mounted in the init container alone. */}}
{{- define "tinfirm.runner.nodeKeySourceDir" -}}
/etc/tinfirm/node-driver-key-source
{{- end }}

{{/*
A node's address, split: dict "host" "port" "ip" "cidr"; "ip" is true for an IPv4 address or an
IPv6 address in brackets, whose "cidr" is the address alone.
*/}}
{{- define "tinfirm.runner.nodeAddress" -}}
{{- $address := toString . }}
{{- $host := regexReplaceAll ":[0-9]+$" $address "" }}
{{- $port := regexFind "[0-9]+$" $address | atoi }}
{{- $ip := false }}
{{- $cidr := "" }}
{{- if regexMatch "^\\[[0-9A-Fa-f:.]+\\]$" $host }}
{{- $ip = true }}
{{- $cidr = printf "%s/128" (trimPrefix "[" (trimSuffix "]" $host)) }}
{{- else if regexMatch "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$" $host }}
{{- $ip = true }}
{{- $cidr = printf "%s/32" $host }}
{{- end }}
{{- toJson (dict "host" $host "port" $port "ip" $ip "cidr" $cidr) }}
{{- end }}

{{/*
Fails the render on a runner the server or the runner host would refuse: a company that
`companies` does not declare, a name a company host cannot have, and a template that the
driver's configuration lacks.
*/}}
{{- define "tinfirm.runner.validate" -}}
{{- $r := .Values.runner }}
{{- $keys := list }}
{{- range .Values.companies }}{{ $keys = append $keys .key }}{{ end }}
{{- if not (has $r.company $keys) }}
{{- fail (printf "runner.company: %q is not the key of a company in `companies`" (toString $r.company)) }}
{{- end }}
{{- if not (regexMatch "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$" (toString $r.name)) }}
{{- fail (printf "runner.name: %q must be letters, digits, '.', '_' and '-', starting with a letter or digit, at most 100 characters" (toString $r.name)) }}
{{- end }}
{{- $_ := required "runner.existingSecret is required: the Secret with the runner host's token" $r.existingSecret }}
{{- $nodes := $r.vm.node.nodes | default list }}
{{- $doTemplates := $r.vm.digitalocean.templates | default dict }}
{{- if eq $r.vm.driver "digitalocean" }}
{{- if $nodes }}
{{- fail "runner.vm.node.nodes is set, but runner.vm.driver is digitalocean: set runner.vm.driver: node to use the sandbox nodes, or remove runner.vm.node.nodes" }}
{{- end }}
{{- $_ := required "runner.vm.digitalocean.existingSecret is required: the Secret with the DigitalOcean API token" $r.vm.digitalocean.existingSecret }}
{{- if not (hasKey $doTemplates $r.vm.template) }}
{{- fail (printf "runner.vm.template: %q is not a key of runner.vm.digitalocean.templates" (toString $r.vm.template)) }}
{{- end }}
{{- else if eq $r.vm.driver "node" }}
{{- if $doTemplates }}
{{- fail "runner.vm.digitalocean.templates is set, but runner.vm.driver is node: remove runner.vm.digitalocean.templates, or set runner.vm.driver: digitalocean and remove runner.vm.node.nodes" }}
{{- end }}
{{- if not $nodes }}
{{- fail "runner.vm.driver is node, but runner.vm.node.nodes is empty: name at least one node, with its name, address (HOST:PORT of its agent's TLS listener), nodeSha256 and templates" }}
{{- end }}
{{- $_ := required "runner.vm.node.existingSecret is required: the Secret with the runner host's client certificate and key for the nodes" $r.vm.node.existingSecret }}
{{- if gt (int $r.vm.node.publishedPorts.first) (int $r.vm.node.publishedPorts.last) }}
{{- fail (printf "runner.vm.node.publishedPorts: first (%v) is above last (%v)" $r.vm.node.publishedPorts.first $r.vm.node.publishedPorts.last) }}
{{- end }}
{{- $names := list }}
{{- $offered := list }}
{{- $np := .Values.networkPolicy }}
{{- range $nodes }}
{{- if has .name $names }}
{{- fail (printf "runner.vm.node.nodes: the name %q is there twice; each node's name is its own" .name) }}
{{- end }}
{{- $names = append $names .name }}
{{- $offered = concat $offered .templates }}
{{- $address := include "tinfirm.runner.nodeAddress" .address | fromJson }}
{{- if gt (int $address.port) 65535 }}
{{- fail (printf "runner.vm.node.nodes: node %q: the address %q has no port from 1 to 65535" .name .address) }}
{{- end }}
{{- if and $np.enabled $np.egress.enabled (not $np.runner.egress.nodes.to) (not $address.ip) }}
{{- fail (printf "runner.vm.node.nodes: node %q has the host name %q, which a NetworkPolicy cannot name: set networkPolicy.runner.egress.nodes.to to the nodes' peers (an ipBlock of their addresses), or give the node's IP address" .name $address.host) }}
{{- end }}
{{- end }}
{{- if not (has $r.vm.template $offered) }}
{{- fail (printf "runner.vm.template: %q is offered by none of runner.vm.node.nodes (their templates: %s)" (toString $r.vm.template) (uniq $offered | join ", ")) }}
{{- end }}
{{- else if eq $r.vm.driver "pod" }}
{{- include "tinfirm.runner.validatePod" . }}
{{- end }}
{{- end }}

{{/*
The pod driver's rules that the schema cannot express, as the driver checks its configuration
(ee/runner/tinfirm-runner-host/src/kubernetes/config.rs): the statement word for word, a runs'
namespace of their own, the image by digest, the template, the tap's attachment, the devices,
the workers and the disk's storage class.
*/}}
{{- define "tinfirm.runner.validatePod" -}}
{{- $r := .Values.runner }}
{{- $c := $r.vm.cluster }}
{{- $p := $r.vm.pod }}
{{- if or ($r.vm.digitalocean.templates | default dict) ($r.vm.node.nodes | default list) }}
{{- fail "runner.vm.driver is pod: remove runner.vm.digitalocean.templates and runner.vm.node.nodes, which only the other drivers use" }}
{{- end }}
{{- if ne (toString $c.isolationStatement) (include "tinfirm.runs.isolationStatement" .) }}
{{- fail (printf "runner.vm.cluster.isolationStatement: set it to %q, word for word, only when the workers that run the run pods (runner.vm.pod.nodeSelector) run no other workload, not the runner pod either, and their kubelet's credential and cloud identity open nothing else (ADR-0115, decisions 1 and 6). The runner host then reports its isolation as `machine`, stated by the operator. Without the statement the driver does not start, and the chart does not render" (include "tinfirm.runs.isolationStatement" .)) }}
{{- end }}
{{- if eq $c.namespace .Release.Namespace }}
{{- fail (printf "runner.vm.cluster.namespace: %q is the release's namespace. The runs' namespace must be another, since it may hold no Secret but the runs' tickets" $c.namespace) }}
{{- end }}
{{- if or (has $c.namespace (list "default" "kube-system" "kube-public" "kube-node-lease")) (hasPrefix "openshift" $c.namespace) }}
{{- fail (printf "runner.vm.cluster.namespace: %q is the cluster's own; the runs' namespace is one of their own" $c.namespace) }}
{{- end }}
{{- if not (and $p.image.repository $p.image.digest) }}
{{- fail "runner.vm.pod.image: set repository and digest, the run pod's image, which the driver names by digest alone" }}
{{- end }}
{{- $templates := $p.templates | default dict }}
{{- if not (hasKey $templates (toString $r.vm.template)) }}
{{- fail (printf "runner.vm.template: %q is not a key of runner.vm.pod.templates" (toString $r.vm.template)) }}
{{- end }}
{{- if eq $p.tap.mode "multus" }}
{{- if not $p.tap.networkAttachment }}
{{- fail "runner.vm.pod.tap.networkAttachment: with mode multus, the NetworkAttachmentDefinition that hands the pod its tap device, `name` or `namespace/name`" }}
{{- end }}
{{- else if $p.tap.networkAttachment }}
{{- fail "runner.vm.pod.tap.networkAttachment: only with mode multus" }}
{{- end }}
{{- $cidr := splitList "/" $p.tap.cidr }}
{{- $octets := splitList "." (first $cidr) }}
{{- range $octets }}
{{- if gt (atoi .) 255 }}
{{- fail (printf "runner.vm.pod.tap.cidr: %q is not an IPv4 network" $p.tap.cidr) }}
{{- end }}
{{- end }}
{{- if ne (mod (atoi (last $octets)) 4) 0 }}
{{- fail (printf "runner.vm.pod.tap.cidr: %q is not the first address of a /30, such as 172.30.0.0/30" $p.tap.cidr) }}
{{- end }}
{{- if not (and $p.devices.kvm $p.devices.tun) }}
{{- fail "runner.vm.pod.devices: set kvm and tun, the device plugin's resource names for /dev/kvm and /dev/net/tun" }}
{{- end }}
{{- if eq $p.devices.kvm $p.devices.tun }}
{{- fail "runner.vm.pod.devices: kvm and tun are the same resource" }}
{{- end }}
{{- if not $p.nodeSelector }}
{{- fail "runner.vm.pod.nodeSelector: at least one label, which selects the workers for runs" }}
{{- end }}
{{- if and $p.disk.storageClassName (ne $p.disk.volume "ephemeral") }}
{{- fail "runner.vm.pod.disk.storageClassName: only with volume ephemeral" }}
{{- end }}
{{- $np := .Values.networkPolicy }}
{{- if and $np.enabled $np.egress.enabled (not $np.runner.egress.apiServer.to) }}
{{- fail "networkPolicy.runner.egress.apiServer.to: with driver pod, the API server's own addresses (kubectl get endpointslices -n default -l kubernetes.io/service-name=kubernetes), which the driver calls; a NetworkPolicy cannot name the `kubernetes` Service" }}
{{- end }}
{{- end }}

{{/* The operator's statement of the pod driver's configuration (config.rs, ISOLATION_STATEMENT). */}}
{{- define "tinfirm.runs.isolationStatement" -}}
the workers for runs hold nothing else
{{- end }}

{{/* The runner pod's own ServiceAccount, with driver pod only: the driver's Role is bound to it. */}}
{{- define "tinfirm.runner.serviceAccountName" -}}
{{- if eq .Values.runner.vm.driver "pod" }}
{{- include "tinfirm.runner.fullname" . }}
{{- else }}
{{- include "tinfirm.serviceAccountName" . }}
{{- end }}
{{- end }}

{{/*
Where the runner pod gets its ServiceAccount token (`token`) and the cluster's CA (`ca.crt`),
with driver pod: a projected volume, which holds nothing else. The driver reads them here.
*/}}
{{- define "tinfirm.runner.kubernetesApiDir" -}}
/etc/tinfirm/kubernetes-api
{{- end }}
{{- define "tinfirm.runner.podConfigDir" -}}
/etc/tinfirm/pod-driver
{{- end }}

{{/* The runs' objects, whose names the driver's configuration carries. */}}
{{- define "tinfirm.runs.defaultDenyName" -}}
tinfirm-runs-default-deny
{{- end }}
{{- define "tinfirm.runs.allowRunnerName" -}}
tinfirm-runs-allow-runner
{{- end }}
{{- define "tinfirm.runs.resourceQuotaName" -}}
tinfirm-runs
{{- end }}

{{/* The label of every run pod, which the allow rule selects (spec.rs, SANDBOX_LABEL). */}}
{{- define "tinfirm.runs.sandboxLabels" -}}
tinfirm.flowtivo.com/sandbox: vm
{{- end }}

{{/*
The `spec` of the runs' default-deny policy, as JSON: every pod, nothing in or out. The driver
compares it with spec.rs's default_deny_spec(), field by field.
*/}}
{{- define "tinfirm.runs.defaultDenySpec" -}}
{{- toPrettyJson (dict "podSelector" dict "policyTypes" (list "Ingress" "Egress")) }}
{{- end }}

{{/*
The `spec` of the one allow rule, as JSON: into run pods, from the runner pod, to the bridge's
port (spec.rs, allow_runner_spec).
*/}}
{{- define "tinfirm.runs.allowRunnerSpec" -}}
{{- $from := dict "namespaceSelector" (dict "matchLabels" (dict "kubernetes.io/metadata.name" .Release.Namespace)) "podSelector" (dict "matchLabels" (include "tinfirm.runner.selectorLabels" . | fromYaml)) }}
{{- $ingress := dict "from" (list $from) "ports" (list (dict "protocol" "TCP" "port" (int .Values.runner.vm.port))) }}
{{- toPrettyJson (dict "podSelector" (dict "matchLabels" (include "tinfirm.runs.sandboxLabels" . | fromYaml)) "policyTypes" (list "Ingress") "ingress" (list $ingress)) }}
{{- end }}

{{/* The run pod's image: repository@digest. */}}
{{- define "tinfirm.runner.podImage" -}}
{{- printf "%s@%s" .Values.runner.vm.pod.image.repository .Values.runner.vm.pod.image.digest }}
{{- end }}

{{/*
The pod driver's configuration file, pod-driver.json, as PodConfig reads it
(ee/runner/tinfirm-runner-host/src/kubernetes/config.rs): every field, the defaults included.
*/}}
{{- define "tinfirm.runner.podConfig" -}}
{{- $v := .Values.runner.vm }}
{{- $p := $v.pod }}
{{- $templates := dict }}
{{- range $name, $t := $p.templates }}
{{- $_ := set $templates $name (dict "vcpus" (int $t.vcpus) "memoryMib" (int $t.memoryMib) "diskMib" (int $t.diskMib)) }}
{{- end }}
{{- $tap := dict "mode" $p.tap.mode "cidr" $p.tap.cidr }}
{{- if eq $p.tap.mode "multus" }}
{{- $_ := set $tap "networkAttachment" $p.tap.networkAttachment }}
{{- end }}
{{- $disk := dict "variant" $p.disk.variant "volume" $p.disk.volume }}
{{- with $p.disk.storageClassName }}
{{- $_ := set $disk "storageClassName" . }}
{{- end }}
{{- $tolerations := list }}
{{- range $p.tolerations }}
{{- $toleration := omit . "tolerationSeconds" }}
{{- if hasKey . "tolerationSeconds" }}
{{- $_ := set $toleration "tolerationSeconds" (int64 .tolerationSeconds) }}
{{- end }}
{{- $tolerations = append $tolerations $toleration }}
{{- end }}
{{- $config := dict
  "isolationStatement" $v.cluster.isolationStatement
  "namespace" $v.cluster.namespace
  "runServiceAccount" $v.cluster.runServiceAccount
  "image" (include "tinfirm.runner.podImage" .)
  "templates" $templates
  "tap" $tap
  "devices" (dict "kvm" $p.devices.kvm "tun" $p.devices.tun)
  "nodeSelector" $p.nodeSelector
  "tolerations" $tolerations
  "disk" $disk
  "bridgePort" (int $v.port)
  "runner" (dict "namespace" .Release.Namespace "labels" (include "tinfirm.runner.selectorLabels" . | fromYaml))
  "policies" (dict "defaultDeny" (include "tinfirm.runs.defaultDenyName" .) "allowRunner" (include "tinfirm.runs.allowRunnerName" .))
  "resourceQuota" (include "tinfirm.runs.resourceQuotaName" .)
}}
{{- toPrettyJson $config }}
{{- end }}

{{/*
The runs' ResourceQuota's `hard`: runner.vm.cluster.quota.hard, else maxConcurrentRuns +
pool.maxReady run pods of the template runner.vm.template, each with what spec.rs's pod
requests: the VM's vCPUs, its memory plus 208 MiB (VMM_OVERHEAD_MIB, VM_POD_MIB,
RUN_VOLUME_MIB), 64 MiB of logs plus the disk in an emptyDir (LOGS_MIB), and one of each device.
*/}}
{{- define "tinfirm.runs.quotaHard" -}}
{{- $r := .Values.runner }}
{{- if $r.vm.cluster.quota.hard }}
{{- toYaml $r.vm.cluster.quota.hard }}
{{- else }}
{{- $p := $r.vm.pod }}
{{- $t := get $p.templates (toString $r.vm.template) }}
{{- $n := add (int $r.maxConcurrentRuns) (int $r.pool.maxReady) }}
{{- $memory := mul $n (add (int $t.memoryMib) 208) }}
{{- $storage := mul $n (add 64 (ternary (int $t.diskMib) 0 (eq $p.disk.volume "emptyDir"))) }}
{{- $cpu := mul $n (int $t.vcpus) }}
{{- $hard := dict
  "pods" (toString $n)
  "requests.cpu" (toString $cpu)
  "limits.cpu" (toString $cpu)
  "requests.memory" (printf "%dMi" $memory)
  "limits.memory" (printf "%dMi" $memory)
  "requests.ephemeral-storage" (printf "%dMi" $storage)
  "limits.ephemeral-storage" (printf "%dMi" $storage)
}}
{{- $_ := set $hard (printf "requests.%s" $p.devices.kvm) (toString $n) }}
{{- $_ := set $hard (printf "requests.%s" $p.devices.tun) (toString $n) }}
{{- toYaml $hard }}
{{- end }}
{{- end }}

{{/* The node driver's configuration file, node-driver.json: the nodes, with the mounted certificate's and key's files. */}}
{{- define "tinfirm.runner.nodeConfig" -}}
{{- $n := .Values.runner.vm.node }}
{{- $certificate := printf "%s/%s" (include "tinfirm.runner.nodeCertificateDir" .) $n.secretKeys.certificate }}
{{- $key := printf "%s/%s" (include "tinfirm.runner.nodeKeyDir" .) $n.secretKeys.key }}
{{- $nodes := list }}
{{- range $n.nodes }}
{{- $nodes = append $nodes (dict "name" .name "address" .address "nodeSha256" (lower .nodeSha256) "certificate" $certificate "key" $key "templates" .templates) }}
{{- end }}
{{- toPrettyJson (dict "nodes" $nodes "peerBuild" $n.peerBuild) }}
{{- end }}

{{/* The DigitalOcean driver's configuration file, digitalocean.json, with the mounted token's file. */}}
{{- define "tinfirm.runner.digitaloceanConfig" -}}
{{- $do := .Values.runner.vm.digitalocean }}
{{- $config := dict "tokenFile" (printf "%s/%s" (include "tinfirm.runner.digitaloceanTokenDir" .) $do.secretKeys.token) "connect" $do.connect "templates" ($do.templates | default dict) }}
{{- toPrettyJson $config }}
{{- end }}
