# Server configuration

Every environment variable an operator may set on the Tinfirm server, with its default, what it does, and the Helm value that sets it. [Install on Kubernetes with Helm](../operator-guide/helm.md) and [Try Tinfirm with Docker Compose](../operator-guide/quick-start.md) say how to install; this page is the list to look things up in.

## How to set a variable

- **Helm.** Use the value in the tables' last column. A variable without a value of its own goes into `extraConfig`, which the chart writes into the server's ConfigMap, or, for a secret, into `extraEnv` with a `secretKeyRef`.
- **Docker Compose.** `compose.yaml` hands the server an explicit list of variables, most of them from `deploy/compose/.env`: [The Compose file's variables](#the-compose-files-variables). To set another, add it to the `environment` of the service `server`.

Durations are ISO 8601, such as `PT20S` or `P7D`. Sizes take a unit, such as `25MB`. Lists are comma-separated. A variable marked **Secret** belongs in a Secret, never in a ConfigMap or a values file. The server checks its configuration when it starts, and a missing or malformed required value stops it with the reason.

## Database

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/tinfirm` | The JDBC URL of Tinfirm's database | Built from `database.host`, `.port`, `.name`, `.sslMode`, `.caCertSecret`, `.params`; or `database.url` |
| `SPRING_DATASOURCE_USERNAME` | none | The runtime role: `tinfirm_app` | Set by the chart |
| `SPRING_DATASOURCE_PASSWORD` | none | **Secret.** Its password | `database.existingSecret`, key `database.secretKeys.appPassword` |
| `SPRING_FLYWAY_USER` | none | The role the migrations run as: `tinfirm_owner` | Set by the chart |
| `SPRING_FLYWAY_PASSWORD` | none | **Secret.** Its password | `database.existingSecret`, key `database.secretKeys.ownerPassword` |
| `SPRING_FLYWAY_URL` | the datasource's URL | A URL of its own for the migrations, for example to bypass a transaction pooler | `extraConfig` |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | The connection pool of each server pod | `extraConfig` |

[Prepare PostgreSQL](../operator-guide/database.md) explains the roles, TLS and pool sizes.

## Public URL and proxies

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_PUBLIC_URL` | `http://localhost:8080` | The address people reach Tinfirm at. It sets the identity providers' redirect URIs, the `Secure` flag of the session cookie and HSTS (with `https`), the issuer of Tinfirm's authorization server for AI clients, the address of each company's MCP endpoint, the **Callback URL** of systems' sign-in apps (`/api/oauth/callback`) and Tinfirm's client metadata document (`/oauth/client-metadata.json`). Changing it is a migration: [Change the public URL](../operator-guide/maintenance.md#change-the-public-url). | `publicUrl`, else the first ingress host |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | Tomcat's: every private range | A Java regular expression of the proxies whose `X-Forwarded-For` the server trusts; the throttle of sign-ins counts per client address. Never set it empty: the server would then trust every peer. | `trustedProxies` |

## Vault

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_VAULT_KEYS` | **required** | **Secret.** The master keys, `keyId:base64(32 bytes)`, comma-separated. A key id is 1 to 64 letters, digits, dots, dashes or underscores. | `vault.existingSecret`, key `vault.secretKeys.keys` |
| `TINFIRM_VAULT_ACTIVE_KEY` | **required** | The id of the key that wraps new secrets; one of `TINFIRM_VAULT_KEYS` | `vault.existingSecret`, key `vault.secretKeys.activeKey` |

[Rotate the vault master key](../operator-guide/maintenance.md#rotate-the-vault-master-key) uses both.

## Bootstrap and companies

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` | unset | The first instance admin, created at a start while the instance has no user. Also the owner of a declared company that names none. | `bootstrap.existingSecret`, key `bootstrap.secretKeys.adminEmail` |
| `TINFIRM_BOOTSTRAP_ADMIN_PASSWORD` | unset | **Secret.** That admin's password. Changing it later changes no user. | `bootstrap.existingSecret`, key `bootstrap.secretKeys.adminPassword` |
| `TINFIRM_COMPANIES_<KEY>_NAME` | none: no company declared | Declares the company `<key>`: 1 to 40 lower-case letters and digits, starting with a letter, written upper case in the variable. Created with this name, 1 to 100 characters, at a start when it does not exist; renaming it in the app is kept. | `companies[].key`, `.name` |
| `TINFIRM_COMPANIES_<KEY>_OWNER` | the bootstrap admin's email | The email of the user who becomes the company's Owner while it has none | `companies[].owner` |
| `TINFIRM_COMPANIES_<KEY>_ID` | unset | Adopts an existing company without a key, by its id, as this declaration. Unset, a company without a key whose name is exactly `_NAME` is adopted; when several have that name, the server does not start. | `companies[].id` |
| `TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_NAME` | unset | A company runner host of the company that the deployment runs itself, such as the chart's runner pod: registered at every start when the company has none of this name. A host of this name registered in the app stops the start. Set with `_TOKEN`. | `runner.name`, with `runner.company` |
| `TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_TOKEN` | unset | **Secret.** That host's token, `tfr_` and 43 base64url characters. The server keeps its hash; a new token replaces the old one at the next start and closes its connection. A malformed token, or another host's, stops the start. | `runner.existingSecret`, key `runner.secretKeys.token` |

A company beyond the first needs an Enterprise licence: [Declare companies and how their people sign in](../operator-guide/sign-in.md).

## Sign-in

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_AUTH_LOCAL_ENABLED` | `true` | Sign-in with an email and a password | `auth.localEnabled` |
| `TINFIRM_AUTH_OIDC_AUTO_PROVISION` | `true` | Create a user at their first sign-in through an identity provider. A sign-in is never linked to an existing user by email. | `auth.oidcAutoProvision` |

Each identity provider is a registration `<ID>`: lower-case letters and digits, written upper case in the variables.

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_ID` | none | The client id at the provider | `auth.oidc.providers[].clientId` |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_SECRET` | none | **Secret.** The client secret | `auth.oidc.providers[].existingSecret`, key `secretKeys.clientSecret` |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_NAME` | the id | The label of its button on the sign-in page | `auth.oidc.providers[].name` |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_SCOPE` | none | The scopes, such as `openid,profile,email` | `auth.oidc.providers[].scope`, default `openid,profile,email` |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_AUTHORIZATION_GRANT_TYPE` | none | `authorization_code` | Set by the chart |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_ISSUER_URI` | none | The issuer; it must match the tokens' `iss` exactly | `auth.oidc.providers[].issuerUri` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST` | `REQUIRE_VERIFIED_CLAIM` | How a new user's email is trusted: the provider must send `email_verified=true`, or `TRUST_ISSUER` trusts the provider without it | `auth.oidc.providers[].emailTrust` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` | `email` | The claims that may carry the email, tried in order. Any claim other than `email` needs `TRUST_ISSUER`. | `auth.oidc.providers[].emailClaims` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANY` | the only declared company, when exactly one is declared; else none | The key of the declared company a new user joins | `auth.oidc.providers[].company` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE` | `MEMBER` | The new member's role: `MEMBER`, `ADMIN` or `VIEWER`, never `OWNER`. A role that holds a seat becomes Viewer when the licence has no seat free. | `auth.oidc.providers[].role` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_DOMAINS` | empty: any email | When set, only an email in one of these domains gets a new user through this provider | `auth.oidc.providers[].emailDomains` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_CLAIM` | none | The claim of the ID token or the userinfo response that carries the role's values. With it, the provider decides each member's role at every sign-in ([Who decides a member's role](../operator-guide/sign-in.md#who-decides-a-members-role)) | `auth.oidc.providers[].roleClaim` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_MAP_ADMIN`, `_ROLE_MAP_MEMBER`, `_ROLE_MAP_VIEWER` | none | The claim's values, comma-separated and matched exactly, that give each role; the highest wins | `auth.oidc.providers[].roleMap.admin`, `.member`, `.viewer` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_SOURCE` | `IDP` with a role claim, else `APP` | `IDP`: the provider decides at every sign-in. `APP`: a new member gets the mapped role, else `_ROLE`, once | `auth.oidc.providers[].roleSource` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANIES_<KEY>_ROLE_MAP_ADMIN`, `_ROLE_MAP_MEMBER`, `_ROLE_MAP_VIEWER` | none | The claim's values that give each role in the further declared company `<KEY>`, so that one provider fills several companies (Enterprise; [One provider for several companies](../operator-guide/sign-in.md#one-provider-for-several-companies)) | `auth.oidc.providers[].companies[].key`, `.roleMap` |

The server does not start when a `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_*` variable names no registration, or names a company that is not declared.

SAML providers (Enterprise, [Sign in with a SAML provider](../operator-guide/saml.md)):

| Variable | Default | Meaning | Helm |
|---|---|---|---|
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_METADATA_LOCATION` | required | The IdP's metadata: an `https://` URL or a `file:` path | `auth.saml.providers[].metadataUrl`, or `.metadata` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_NAME` | the id | The login page's button | `auth.saml.providers[].name` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_ENTITY_ID` | the IdP of the first good document | The IdP to take from a document of several, and the IdP the provider is: a document that names another is refused | `auth.saml.providers[].entityId` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_COMPANY`, `_ROLE`, `_EMAIL_DOMAINS` | as for OIDC | The company a new person joins, their role, the domains their email must be in | `auth.saml.providers[].company`, `.role`, `.emailDomains` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_SUBJECT` | `NAMEID` | The NameID, or the attribute that identifies the person | `auth.saml.providers[].subject` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_EMAIL_ATTRIBUTES`, `_NAME_ATTRIBUTES` | see the operator guide | The attributes read for the email and the name, in order | `auth.saml.providers[].emailAttributes`, `.nameAttributes` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_ROLE_CLAIM`, `_ROLE_MAP_ADMIN`, `_ROLE_MAP_MEMBER`, `_ROLE_MAP_VIEWER`, `_ROLE_SOURCE`, `_COMPANIES_<KEY>_ROLE_MAP_*` | as for OIDC | The attribute that carries the role, its values, who decides, and the further companies it fills | `auth.saml.providers[].roleClaim`, `.roleMap`, `.roleSource`, `.companies` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_AUTO_PROVISION` | `true` | Whether an unknown person gets a new account | `auth.saml.providers[].autoProvision` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_REQUIRE_ENCRYPTED_ASSERTIONS` | `false` | Refuse an assertion that is not encrypted | `auth.saml.providers[].requireEncryptedAssertions` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_METADATA_REFRESH` | `PT12H` | How often the metadata is read again, at least `PT5M` | `auth.saml.providers[].metadataRefresh` |
| `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_SCIM_MATCH` | `EXTERNAL_ID` | How a first sign-in finds a person SCIM created | `auth.saml.providers[].scimMatch` |
| `TINFIRM_AUTH_SAML_SP_CREDENTIALS_<KID>_KEY`, `_CERT` | none | The service provider's PKCS#8 PEM key and its certificate; every one is published | `auth.saml.sp.existingSecret`, `.credentials` |
| `TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL` | none | The credential that signs | `auth.saml.sp.activeCredential` |

The server does not start with SAML providers on a public URL that is not https (`localhost` aside), without a service provider credential, or on a provider whose id an OIDC provider also uses.

## Sessions

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_SESSION_IDLE_TIMEOUT` | `PT8H` | A session ends after this long without a request | `session.idleTimeout` |
| `TINFIRM_SESSION_MAX_AGE` | `P7D` | A session ends this long after sign-in, whatever its use | `session.maxAge` |

## Connections to systems

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_CONNECTION_ALLOW_HTTP` | `false` | Accept `http` addresses of company systems and of a company's own LiteLLM; their tokens then travel in clear text | `connection.allowHttp` |
| `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES` | `false` | Accept company systems and a company's own LiteLLM on loopback, link-local or private addresses | `connection.allowPrivateAddresses` |
| `TINFIRM_CONNECTION_INTERNAL_HOSTS` | empty | Host names or IPv4 literals of services on your own network, without a scheme, port or wildcard. A company system on one of them may use `http` and must resolve to private addresses only; every other host keeps the guard. | `connection.internalHosts` |
| `TINFIRM_CONNECTION_TIMEOUT` | `PT20S` | Connect and request timeout of one request to a company system | `connection.timeout` |
| `TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS` | empty | Origins (`https://host` or `https://host:port`, comma-separated) on which an MCP server's authorization server may name its endpoints although they are not its issuer's, for providers that host them apart (Google's `https://oauth2.googleapis.com`). Every other endpoint must share the issuer's origin | `extraConfig` |
| `TINFIRM_OUTBOUND_PROXY` | empty | An `http://host:port` proxy for the server's requests to systems, to a company's own LiteLLM and to AI clients' metadata documents; without it the server connects directly and ignores the JVM's proxy settings. The proxy must refuse private and link-local addresses itself | `extraConfig` |
| `TINFIRM_RUN_TOOL_TIMEOUT` | `PT60S` | How long one tool call on a system may take in all, and how long a run's tool call waits for its answer; at most `PT60S` | `extraConfig` |

A member's personal MCP server never gets the exceptions of the first three.

## Model gateway

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_MODEL_GATEWAY_VENDOR` | `LITELLM` | The product the bundled gateway is: `LITELLM`, the only value the server accepts. It does not start with `GENERIC`, which is for a company's own gateway whose keys its team provides | Set by the chart with `litellm.enabled` |
| `TINFIRM_MODEL_GATEWAY_ADMIN_URL` | unset | The bundled LiteLLM's own port, inside the deployment, for its admin API. Without it, or without the master key, no gateway is bundled, and each company brings its own. | Set by the chart with `litellm.enabled` |
| `TINFIRM_MODEL_GATEWAY_MODEL_URL` | unset: the admin URL | The bundled LiteLLM's model port, where the server sends the model requests it relays for runs, and its own | Set by the chart with `litellm.enabled` |
| `TINFIRM_MODEL_GATEWAY_ADMIN_KEY` | unset | **Secret.** The bundled LiteLLM's master key | `litellm.existingSecret`, key `litellm.secretKeys.masterKey` |

The bundled gateway's variables were called `TINFIRM_LITELLM_ADMIN_URL`, `TINFIRM_LITELLM_MODEL_URL` and `TINFIRM_LITELLM_MASTER_KEY` before Tinfirm could speak to more than one gateway. The server still reads each old name when its new name is unset.

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_MODEL_KEY_ROTATION_INTERVAL` | `P30D` | How long after its creation each member's, service account's and company's LiteLLM key is rotated; at least `P1D` | `extraConfig` |
| `TINFIRM_MODEL_KEY_ROTATION_GRACE` | `PT10M` | How long a key replaced on schedule still serves the requests that hold it; `PT0S` to `PT1H` | `extraConfig` |
| `TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` | `true` | Refuse a run's model requests without its runner host's own token from every host: runner host 0.3.0 is the minimum. Set `false` only while a host older than 0.3.0 is being upgraded; such a host is then accepted with a warning. | `extraConfig` |
| `TINFIRM_MODEL_RELAY_MAX_INFLIGHT_BYTES` | `268435456` (256 MiB) | The most bytes of request bodies the model relay holds at once, counted as they arrive; beyond, `429`. At least twice the largest request, 64 MiB. | `extraConfig` |
| `TINFIRM_MODEL_RELAY_BODY_TIMEOUT` | `PT30S` | How long the model relay waits for a request's whole body; beyond, `408`. | `extraConfig` |
| `TINFIRM_MODEL_RELAY_ANTHROPIC_BETAS`, `TINFIRM_MODEL_RELAY_OPENAI_BETAS` | the pinned runtimes' | The `anthropic-beta` and `OpenAI-Beta` values the model relay passes on, comma-separated; any other is dropped. Never add one that lets the provider act for a request (MCP connector, code execution, web fetch). | `extraConfig` |

## Runs and runner hosts

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_ROLES` | `api,worker` | The roles of this server: `worker` runs the background work (dispatching runs, polls, schedules, writing back, chats' events, spend, retention, error reports). Every replica serves the API and keeps runner connections. | `serverRoles` |
| `TINFIRM_RUN_MAX_WALL_CLOCK` | `PT60M` | How long a run may take, for an agent that sets no time limit of its own; at least a minute | `extraConfig` |
| `TINFIRM_RUN_MAX_TURNS` | `200` | The most tool calls of a run | `extraConfig` |
| `TINFIRM_RUN_ACCEPT_TIMEOUT` | `PT10S` | A run its host has not accepted this long after it was sent goes back to the queue | `extraConfig` |
| `TINFIRM_RUN_REJECT_BACKOFF` | `PT10S` | A run its host rejected is not sent again before this | `extraConfig` |
| `TINFIRM_RUN_MAX_EVENTS_PER_RUN` | `20000` | The events stored per run, at least 100; log events go first | `extraConfig` |
| `TINFIRM_RUN_STREAM_TIMEOUT` | `PT30M` | The lifetime of one live stream of a run's or a conversation's events; the page reconnects | `extraConfig` |
| `TINFIRM_RUNNER_LEASE_TTL` | `PT30S` | The lease of a running run, renewed by its host's heartbeats | `extraConfig` |
| `TINFIRM_RUNNER_RECONNECT_GRACE` | `PT2M` | When a host disconnects, and when the server starts, the runs that hold a slot keep a lease at least this long | `extraConfig` |
| `TINFIRM_RUNNER_LOST_RESUME_WINDOW` | `PT10M` | A run lost because its lease expired resumes when its host reports it within this time; `PT0S` turns it off | `extraConfig` |
| `TINFIRM_RUNNER_HEARTBEAT_INTERVAL` | `PT10S` | The heartbeat the server asks of runner hosts | `extraConfig` |
| `TINFIRM_RUNNER_REQUESTS_PER_SECOND` | `50` | The platform requests (`run.request`) one runner host may send per second, ten seconds' worth at once; beyond it a request is refused `TOO_MANY_REQUESTS`; `0` turns it off | `extraConfig` |
| `TINFIRM_RUNNER_COMPANY_REQUESTS_PER_SECOND` | `200` | The same for all the runner hosts of a company together | `extraConfig` |
| `TINFIRM_RUNNER_EVENTS_PER_SECOND` | `20` | The events (`run.event`) of one run per second, ten seconds' worth at once; beyond it an event is not stored | `extraConfig` |
| `TINFIRM_RUNNER_HOST_EVENTS_PER_SECOND` | `200` | The events of all the runs of one runner host per second | `extraConfig` |
| `TINFIRM_RUNNER_COMPANY_EVENTS_PER_SECOND` | `500` | The events of all the runner hosts of a company per second | `extraConfig` |
| `TINFIRM_RUNNER_TOOL_CALLS_IN_FLIGHT` | `4` | The server-executed tool calls of one run that may wait for their answer at once; one more is refused `TOO_MANY_REQUESTS` | `extraConfig` |
| `TINFIRM_RUNNER_EVENTS_MAX_BYTES` | `64MB` | The events one run stores; past them a warning is stored and later events are dropped or refused | `extraConfig` |

## Conversations, files and memory

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_ATTACHMENT_MAX_FILE_SIZE` | `25MB` | The largest file of a conversation, a person's or the agent's | `extraConfig` |
| `TINFIRM_ATTACHMENT_MAX_REQUEST_SIZE` | `26MB` | The largest upload request, the file with its other parts | `extraConfig` |
| `TINFIRM_ATTACHMENT_MAX_FILES_PER_CONVERSATION` | `50` | The most files one conversation holds | `extraConfig` |
| `TINFIRM_ATTACHMENT_MAX_CONVERSATION_SIZE` | `250MB` | The most bytes of all files of one conversation | `extraConfig` |
| `SERVER_TOMCAT_MAXPARTHEADERSIZE` | `16KB` | The largest header of one part of an upload, which carries the file's name | `extraConfig` |
| `TINFIRM_CHANGE_REQUEST_STATE_INTERVAL` | `PT5M` | How often the state of a change request an agent opened is read again; at least `PT30S` | `extraConfig` |
| `TINFIRM_TOOL_JOURNAL_RETENTION` | `P7D` | How long a run's tool calls are kept after the run ended | `extraConfig` |
| `TINFIRM_IDEMPOTENCY_KEY_TTL` | `PT24H` | How long a submission's idempotency key is remembered | `extraConfig` |
| `TINFIRM_MEMORY_MAX_CHARACTERS` | `20000` | The most characters an agent memory's notes hold in all, 1000 to 100000 | `extraConfig` |

The ingress must let the largest request through: when you raise `TINFIRM_ATTACHMENT_MAX_REQUEST_SIZE` beyond its body size, raise that too ([Ingress](../operator-guide/helm.md#ingress)).

## Triggers, webhooks and chats

The limits marked "seed" are the instance's starting values: each company's admins can set their own in the company's settings.

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_TRIGGER_FIRINGS_PER_HOUR_DEFAULT` | `30` | Seed: a new trigger's firings an hour, 1 to 10000 | `extraConfig` |
| `TINFIRM_TRIGGER_FIRINGS_PER_HOUR_MAX` | `300` | Seed: the most a trigger may set, 1 to 10000 | `extraConfig` |
| `TINFIRM_TRIGGER_POLL_TICK` | `PT15S` | How often the server looks for trackers due for a poll; each is polled at its system's own interval | `extraConfig` |
| `TINFIRM_TRIGGER_SCHEDULE_TICK` | `PT30S` | How often the server looks for schedules due | `extraConfig` |
| `TINFIRM_TRIGGER_SETTLE` | `PT90S` | A change newer than this waits for the next poll | `extraConfig` |
| `TINFIRM_TRIGGER_CATCH_UP` | `PT24H` | A change older than this when first read fires nothing | `extraConfig` |
| `TINFIRM_TRIGGER_MISSED_AFTER` | `PT1H` | A schedule's firing due longer ago than this was missed; the trigger's catch-up decides | `extraConfig` |
| `TINFIRM_WEBHOOK_DELIVERIES_PER_MINUTE_DEFAULT` | `60` | Seed: a webhook system's deliveries a minute, 1 to 6000 | `extraConfig` |
| `TINFIRM_WEBHOOK_DELIVERIES_PER_MINUTE_MAX` | `600` | Seed: the most a webhook system may set, 1 to 6000 | `extraConfig` |
| `TINFIRM_CHAT_MAX_BODY` | `1MB` | The largest request Slack or Teams may send; beyond, `413` | `extraConfig` |
| `TINFIRM_CHAT_MESSAGES_PER_USER_MINUTE` | `20` | Seed: messages a minute per chat user | `extraConfig` |
| `TINFIRM_CHAT_MESSAGES_PER_USER_DAY` | `200` | Seed: messages a day per chat user | `extraConfig` |
| `TINFIRM_CHAT_EVENTS_PER_SYSTEM_MINUTE` | `600` | Seed: events a minute per chat system | `extraConfig` |
| `TINFIRM_CHAT_SOCKETS_PER_SYSTEM` | `2` | Socket Mode connections per Slack system; Slack allows 10 per app | `extraConfig` |

## MCP endpoint

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_MCP_RATE_LIMIT_PER_MINUTE` | `120` | Requests a minute one token or AI client may send to the MCP endpoints, with a burst as large; beyond, `429`. Counted per replica. `0` turns it off. | `extraConfig` |
| `TINFIRM_MCP_STARTS_PER_HOUR` | `30` | Conversations one member may start through AI clients in an hour, in each company. The Outlook add-in and the Gmail add-on count against it too. It is counted in the database, so it holds across replicas. `0` turns it off. | `extraConfig` |

## Add-ins

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_OUTLOOK_FRAME_ANCESTORS` | `https://outlook.office.com,https://outlook.office365.com,https://outlook.live.com,https://outlook.cloud.microsoft` | The `https` origins that may frame the Outlook add-in, each optionally with `*.` before its host; a national cloud's Outlook needs its hosts here | `extraConfig` |
| `TINFIRM_GMAIL_SERVICE_ACCOUNT` | unset | The Gmail add-on's service account, as Google Cloud names it. Without it and the client id, the add-on's endpoints answer `404`. | `extraConfig` |
| `TINFIRM_GMAIL_CLIENT_ID` | unset | The Gmail add-on's OAuth client id | `extraConfig` |

[Deploy the Outlook add-in](../admin-guide/outlook-add-in.md) and [Deploy the Gmail add-on](../admin-guide/gmail-add-on.md) say how to use them.

## Audit and retention

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_AUDIT_CHECK_INTERVAL` | `P1D` | How often each audit chain is verified | `extraConfig` |
| `TINFIRM_AUDIT_CHECK_TICK` | `PT1H` | How often the server looks for chains due | `extraConfig` |
| `TINFIRM_AUDIT_CHECK_INITIAL_DELAY` | `PT10M` | The first look after a start | `extraConfig` |
| `TINFIRM_AUDIT_EXPORT_MAX_EVENTS` | `1000000` | The most events one export of the audit log holds | `extraConfig` |
| `TINFIRM_RETENTION_SWEEP_INTERVAL` | `P1D` | How often each company is swept for retention | `extraConfig` |
| `TINFIRM_RETENTION_SWEEP_TICK` | `PT1H` | How often the server looks for companies due | `extraConfig` |
| `TINFIRM_RETENTION_SWEEP_INITIAL_DELAY` | `PT15M` | The first look after a start | `extraConfig` |

The audit's checks and export, and retention periods, are part of Pro and Enterprise: [Editions and prices](../admin-guide/editions.md).

## Error reports

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `TINFIRM_ERROR_REPORTS` | unset: the instance admins choose | `off`, `local` or `automatic` | `errorReports.mode` |
| `TINFIRM_ERROR_REPORTS_DSN` | unset: nothing is sent | **Secret.** The tracker's DSN, `https://<public key>@<host>/<project id>` | `errorReports.dsnSecret.name`, `.key` |
| `TINFIRM_ERROR_REPORTS_LABEL` | unset | The installation's name in its reports, 1 to 64 letters, digits, dots, dashes or underscores | `errorReports.label` |
| `TINFIRM_ERROR_REPORTS_PROXY` | unset: the JVM's proxy settings | `http://host:port` of a proxy for the delivery | `errorReports.proxy` |

[Error reports](../operator-guide/error-reports.md) explains the modes.

## Logging, metrics and the JVM

| Variable | Default | Meaning | Helm value |
|---|---|---|---|
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` | The JVM's options | `javaToolOptions` |
| `LOGGING_LEVEL_<LOGGER>` | `INFO` | The level of one logger, such as `LOGGING_LEVEL_COM_FLOWTIVO_TINFIRM=DEBUG` | `extraConfig` |
| `LOGGING_FILE_NAME` | unset | A file the server also writes its log to, on a writable volume | `extraConfig` |
| `LOGGING_STRUCTURED_FORMAT_FILE` | unset | The format of that file: `ecs`, as on standard output | `extraConfig` |
| `TINFIRM_REVISION` | set by the image | The commit the image was built from. Do not set it. | none |

The server logs ECS JSON to standard output, and never logs a secret, a password or a session id. Port 8080 serves the application, port 8081 the health probes (`/actuator/health/liveness`, `/actuator/health/readiness`) and Prometheus metrics (`/actuator/prometheus`). Neither port is configurable, and 8081 must never be exposed through the ingress.

## Tuning without a variable of its own

These settings have no variable of their own, but take one by Spring Boot's rule: dots become underscores, dashes are dropped, letters are upper case. `tinfirm.auth.login-throttle.per-email.capacity` is `TINFIRM_AUTH_LOGINTHROTTLE_PEREMAIL_CAPACITY`.

| Property | Default | Meaning |
|---|---|---|
| `tinfirm.auth.login-throttle.per-email.capacity`, `.period` | `5`, `1m` | Failed sign-ins one email may have, refilled over the period; counted per replica |
| `tinfirm.auth.login-throttle.per-ip.capacity`, `.period` | `30`, `1m` | Sign-in attempts one client address may make; counted per replica |
| `tinfirm.model.admin-timeout` | `PT10S` | Timeout of one call to a LiteLLM admin API |
| `tinfirm.model.relay-idle-timeout` | `PT10M` | The model relay's longest wait for an answer to start, and between two of its parts |
| `tinfirm.model.relay-max-request-bytes` | `67108864` (64 MiB) | The largest model request the relay passes; beyond, `413` |
| `tinfirm.model.relay-run-concurrency` | `16` | Model requests of one run at a time |
| `tinfirm.model.relay-host-concurrency-per-slot` | `16` | Model requests of one runner host at a time, per slot |
| `tinfirm.oauth.cimd.timeout` | `PT5S` | Timeout of one fetch of an AI client's metadata document |
| `tinfirm.oauth.cimd.max-concurrent-fetches` | `8` | Fetches of AI clients' metadata documents in flight at once per replica; one more is refused |
| `tinfirm.oauth.cimd.fetches-per-address-minute` | `10` | Fetches of metadata documents not cached that one client address may cause per minute; counted per replica |
| `tinfirm.oauth.cimd.refusal-ttl` | `PT60S` | How long a refused metadata document stays refused without a new fetch |
| `tinfirm.system.catalogue-refresh-interval` | `PT1M` | The shortest time between two refreshes of one MCP server's tools; counted per replica |
| `tinfirm.runner.max-message-bytes` | `1048576` (1 MiB) | The largest message a runner host may send on its connection |

Other properties of the server are internal schedules and are not part of this contract.

## The Compose file's variables

`deploy/compose/.env` sets these for `compose.yaml`. The server receives the ones that are also in the tables above under the same name, and these:

| Variable | Default in `.env.example` | Meaning |
|---|---|---|
| `POSTGRES_PASSWORD` | development value | **Secret.** The superuser of the bundled PostgreSQL |
| `TINFIRM_OWNER_PASSWORD`, `TINFIRM_APP_PASSWORD` | development values | **Secret.** The passwords of `tinfirm_owner` and `tinfirm_app`, set when the database volume is created |
| `TINFIRM_COMPANY_NAME`, `TINFIRM_COMPANY_OWNER` | `My Company`, empty | The company `main`: `TINFIRM_COMPANIES_MAIN_NAME` and `_OWNER` |
| `LITELLM_MASTER_KEY` | development value | **Secret.** LiteLLM's master key, also the server's `TINFIRM_MODEL_GATEWAY_ADMIN_KEY` |
| `LITELLM_SALT_KEY` | development value | **Secret.** Encrypts the provider keys in LiteLLM's database. Never change it once keys are stored. |
| `LITELLM_DB_PASSWORD` | development value | **Secret.** The password of LiteLLM's database role |
| `TINFIRM_HTTP_BIND`, `TINFIRM_HTTP_HOST_PORT` | `127.0.0.1`, `8080` | Where the server's port is published on the host |
| `TINFIRM_DB_HOST_PORT`, `TINFIRM_LITELLM_HOST_PORT`, `TINFIRM_MODEL_API_HOST_PORT` | `5432`, `4000`, `4001` | PostgreSQL, LiteLLM and the model port, published on `127.0.0.1` only |
| `TINFIRM_SERVER_IMAGE`, `TINFIRM_MODEL_API_IMAGE` | local builds | The images to run instead of building them |
| `TINFIRM_SERVER_MEM_LIMIT`, `TINFIRM_LITELLM_MEM_LIMIT`, `TINFIRM_MODEL_API_MEM_LIMIT` | `2g`, `2g`, `512m` | Memory limits of the containers |
| `TINFIRM_JAVA_TOOL_OPTIONS` | as `JAVA_TOOL_OPTIONS` above | The server's JVM options |

The server also receives `TINFIRM_PUBLIC_URL`, `TINFIRM_VAULT_KEYS`, `TINFIRM_VAULT_ACTIVE_KEY`, `TINFIRM_BOOTSTRAP_ADMIN_EMAIL`, `TINFIRM_BOOTSTRAP_ADMIN_PASSWORD`, `TINFIRM_CONNECTION_INTERNAL_HOSTS`, `TINFIRM_AUTH_LOCAL_ENABLED`, `TINFIRM_AUTH_OIDC_AUTO_PROVISION`, `TINFIRM_SESSION_IDLE_TIMEOUT`, `TINFIRM_SESSION_MAX_AGE`, `TINFIRM_ROLES` and the four `TINFIRM_ERROR_REPORTS*` from `.env`. Every value in `.env.example` is public: replace each secret before anyone else can reach the machine. When `TINFIRM_PUBLIC_URL` is not a localhost URL, the server refuses to start with the development vault key of `.env.example` as the active key, with its LiteLLM master key, or with its bootstrap password.
