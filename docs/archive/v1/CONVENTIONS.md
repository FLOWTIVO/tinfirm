# Tinfirm engineering conventions (M0)

This file is the shared contract for everyone who works on the repo, human or agent.
The HTTP contract is [`api/openapi.yaml`](../../../api/openapi.yaml).
If this file and the code disagree, fix the code or change this file on purpose. Never let them drift apart.

## 1. M0 scope

M0 is the skeleton: login, companies, members, roles, an append-only audit log and a credential vault. It ships as one server image that runs with Docker Compose and Helm.
There are no agents, tasks or runners in M0. v0 adds them (§14–§16).

**Exit criterion:** `docker compose up` gives an empty company with login, and the Helm chart deploys through GitOps.

## 2. Repository layout and ownership

| Path | Contents | Owner |
|---|---|---|
| `api/openapi.yaml` | HTTP contract (OpenAPI 3.0.3) | Maintainer. Others may change it in their own pull request, where a human reviews the change (§13). |
| `server/` | Spring Boot app (single Maven module, artifactId `tinfirm-server`) | backend |
| `web/` | Angular app (npm package `tinfirm-web`) | web |
| `Dockerfile`, `.dockerignore`, `Makefile`, `deploy/`, `.github/`, `.semgrepignore`, `.trivyignore` | Image, Compose, Helm, CI | deploy |
| `README.md`, `CONTRIBUTING.md`, `docs/` except `CONVENTIONS.md` | Documentation, threat model, ADRs | docs |
| `protocol/runner-v1.schema.json` | Runner WebSocket protocol (JSON Schema 2020-12) | Maintainer only. Others propose changes; they do not edit it. |
| `runner/` | Rust Cargo workspace: runner host, protocol types, runner images | runner |

## 3. Names and versions

- Product name: **Tinfirm**. Keep it in few places: package root, image names and the Helm chart name.
- Java package root: `com.flowtivo.tinfirm`. Maven groupId `com.flowtivo.tinfirm`, artifactId `tinfirm-server`, version `0.1.0-SNAPSHOT`.
- Images: `ghcr.io/flowtivo/tinfirm-server`. The Helm chart is `tinfirm`.
- Versions: take the latest patch release of each line at implementation time.

| Component | Version line |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.x (manages Spring Security, Spring Session, Flyway, Testcontainers, JUnit) |
| Spring Modulith | the 2.x release that matches Boot 4.1 |
| PostgreSQL | 18 |
| Node | 24 LTS |
| Angular | 22.x |
| spartan/ui | 1.x |
| Tailwind | 4.x |

## 4. Runtime contract of the server

### Ports
- `8080`: the application (API, OIDC endpoints, SPA).
- `8081`: management, with `/actuator/health/liveness`, `/actuator/health/readiness` and `/actuator/prometheus`. Never expose 8081 through ingress.

### Paths
- `/api/**`: JSON API, as defined in `api/openapi.yaml`.
- `/oauth2/authorization/{registrationId}` and `/login/oauth2/code/{registrationId}`: the Spring Security OIDC flow.
- Everything else is the SPA. The server serves `classpath:/static/`, and any GET that does not match a file, `/api/**`, `/oauth2/**`, `/login/oauth2/**` or `/actuator/**` returns `index.html`.

### Configuration

Configuration uses environment variables. Spring's standard names are used where they exist.

| Variable | Meaning | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/tinfirm` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Runtime DB role `tinfirm_app`. It does not own tables, so RLS applies to it. | — |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | Migration DB role `tinfirm_owner`, which owns the schema | — |
| `TINFIRM_PUBLIC_URL` | External base URL, e.g. `https://tinfirm.example.com`. It drives OIDC redirects and the `Secure` cookie flag. | `http://localhost:8080` |
| `TINFIRM_VAULT_KEYS` | Master keys, as comma-separated `keyId:base64(32 bytes)` | **required**; the server fails at startup without it |
| `TINFIRM_VAULT_ACTIVE_KEY` | The keyId used for new wraps | **required** |
| `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | Creates the first instance admin when the user table is empty | unset |
| `TINFIRM_BOOTSTRAP_COMPANY_NAME` | First company, owned by the bootstrap admin | `My Company` |
| `TINFIRM_BOOTSTRAP_COMPANY_PREFIX` | Its issue prefix | `MYCO` |
| `TINFIRM_AUTH_LOCAL_ENABLED` | Enables local accounts | `true` |
| `TINFIRM_AUTH_OIDC_AUTO_PROVISION` | Creates a new OIDC user on first login (see §5 for the linking rules) | `true` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST` | How the email of a new user from registration `<ID>` is trusted (§5): `REQUIRE_VERIFIED_CLAIM` or `TRUST_ISSUER` | `REQUIRE_VERIFIED_CLAIM` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` | Claims that may carry that email, comma-separated, tried in order (§5). Claims other than `email` need `TRUST_ISSUER`. | `email` |
| `TINFIRM_SESSION_IDLE_TIMEOUT` | Session idle timeout (ISO-8601 duration) | `PT8H` |
| `TINFIRM_SESSION_MAX_AGE` | Absolute session lifetime | `P7D` |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | Regex of trusted proxy IPs whose `X-Forwarded-For` is honoured (used by the per-IP login throttle) | Tomcat default (private ranges) |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` / `..._PROVIDER_<ID>_ISSUER_URI` | OIDC providers; standard Spring properties. Each registration appears in `GET /api/auth/providers`. `<ID>` is the registration id in lower case; use letters and digits only. The `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_*` variables use the same `<ID>`, and the server does not start when one names no registration. | none |
| `TINFIRM_ROLES` | Server roles in this process: `api`, `worker`. M0 runs both. | `api,worker` |

The server sends no product telemetry. `SystemInfo.telemetryEnabled` is always `false`.
Logs are structured JSON on stdout (Spring Boot structured logging).
The server never logs secret values, passwords, session IDs or CSRF tokens.

## 5. Authentication

- **BFF pattern.** The server keeps a session. The browser holds only an HttpOnly cookie.
  - Cookie `TINFIRM_SESSION`: HttpOnly, `SameSite=Lax`, and `Secure` when `TINFIRM_PUBLIC_URL` is https.
  - Sessions live in Postgres via Spring Session JDBC, so replicas need no sticky sessions. Flyway creates the session tables; Spring must not auto-initialize them.
- **CSRF.** Cookie `XSRF-TOKEN`, readable by JS, plus the header `X-XSRF-TOKEN`. These are the Angular `HttpClient` defaults.
  - `GET /api/auth/providers` must set the cookie, because the SPA calls it before `POST /api/auth/login`.
  - Every mutating request needs the token, including login and logout.
- **Local accounts.** Passwords are hashed with Argon2id. Login is throttled per email and per client IP; the throttle returns 429 with problem code `RATE_LIMITED`. Failed and successful logins are audited in the instance chain. M0 has only the login throttle; general per-principal API limits come in M1.
  - The in-memory throttle is per replica. That is acceptable for M0.
  - Per IP, every attempt counts (default 30 per minute).
  - Per email, only failures count (default 5 per minute).
  - Both limits are checked before the password is verified.
  - A password change or reset ends the user's other sessions.
- **Session lifecycle.**
  - Login, local or OIDC, rotates the session ID.
  - Idle timeout is `TINFIRM_SESSION_IDLE_TIMEOUT`, and the absolute lifetime is `TINFIRM_SESSION_MAX_AGE`. The absolute lifetime is checked against the session creation time on every request.
  - Logout deletes the session in the database and clears the session and XSRF cookies.
- **OIDC.** Spring Security `oauth2Login` against any provider, with PKCE enabled for every registration, including confidential clients. After login the server redirects to `/`.
  - An OIDC user is identified by `(issuer, subject)`, never by email. A known `(issuer, subject)` logs in whatever its email claims say.
  - On the first login with an unknown `(issuer, subject)`, the server creates a new user with `authSource=OIDC`, if auto-provisioning is on and the email can be trusted. The registration's email trust decides (`TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST`, §4; [ADR-0026](../../adr/0026-per-provider-oidc-email-trust.md)):

    | Email trust | Email from | `email_verified` |
    |---|---|---|
    | `REQUIRE_VERIFIED_CLAIM` (default) | the `email` claim | must be `true` |
    | `TRUST_ISSUER` | the first claim in `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` (default `email`) whose value looks like an email address; an array claim gives its first such value | may be missing; an explicit `false` still refuses |

    `TRUST_ISSUER` is for single-tenant issuers that the operator controls, such as one Entra ID tenant, which sends no `email_verified`. Its claim order for Entra ID is `email,preferred_username,verified_primary_email` ([operations/entra-id.md](../../operations/entra-id.md)). The server does not start when a registration names claims other than `email` without `TRUST_ISSUER`, because `email_verified` speaks only for `email`.
  - Without a usable email the first login fails with code `EMAIL_MISSING`, and the SPA shows `/login?error=EMAIL_MISSING`. When the email trust does not accept the email (no `email_verified=true` under `REQUIRE_VERIFIED_CLAIM`, or an explicit `email_verified=false`), it fails with code `EMAIL_NOT_VERIFIED`, and the SPA shows `/login?error=EMAIL_NOT_VERIFIED`. Every other refusal except `EMAIL_IN_USE` gives `OIDC_LOGIN_FAILED`; the `auth.login.failed` event records the reason (§8).
  - It **never** links automatically to an existing user. If the email already belongs to another user, login fails with problem code `EMAIL_IN_USE`, and the SPA shows `/login?error=EMAIL_IN_USE`. This holds for every email trust.
  - OIDC users cannot use `/api/me/password`, which returns `409 NOT_LOCAL_USER`.
- **Security headers** on every response:
  - `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - HSTS when `TINFIRM_PUBLIC_URL` is https.

  The SPA must work under this CSP, so it uses no inline scripts. Angular's critical-CSS inlining is off, because it injects an `onload` handler.
- **Bootstrap.** On startup, if no user exists and both bootstrap variables are set, the server creates:
  - an instance admin;
  - the first company;
  - the admin's OWNER membership.

  All three steps are audited with actor `SYSTEM`. The operation is idempotent.
- **Deactivated users.** A user with `active=false` cannot log in, and their existing sessions stop working.

## 6. Authorization matrix

Roles apply per company. **Instance admin** is a flag on the user, separate from company roles.

| Operation | OWNER | ADMIN | OPERATOR | VIEWER | Instance admin who is not a member |
|---|---|---|---|---|---|
| List or get the company | yes | yes | yes | yes | yes (audited `admin.company.accessed`) |
| Create a company | — | — | — | — | yes (becomes OWNER) |
| Rename the company | yes | yes | no | no | yes |
| List members | yes | yes | yes | yes | yes |
| Add a member, or change a role to or from OWNER | yes | only non-owner roles | no | no | yes |
| Remove a member | yes | only non-owners | no | no | yes |
| Company audit log and verification | yes | yes | no | no | yes |
| List secret metadata | yes | yes | yes | no | yes |
| Create, update or delete a secret | yes | yes | no | no | yes |
| `/api/admin/**` | — | — | — | — | instance admin only |

Rules:
- A non-member without instance admin gets **404** for every company-scoped path, never 403.
- Break-glass in M0: an instance admin's access to a company where they are not a member is audited as `admin.company.accessed`. A mandatory reason field comes in M1.
- A member without the needed role gets **403**.
- The last OWNER of a company cannot be removed or demoted: `409 LAST_OWNER`.
- The last active instance admin cannot be demoted or deactivated: `409 LAST_INSTANCE_ADMIN`.
- Problem `code` values: `VALIDATION_FAILED`, `RATE_LIMITED`, `DUPLICATE_EMAIL`, `DUPLICATE_ISSUE_PREFIX`, `DUPLICATE_SECRET_NAME`, `DUPLICATE_MEMBER`, `USER_NOT_FOUND` (add member with an unknown email; returns 404), `LAST_OWNER`, `LAST_INSTANCE_ADMIN`, `NOT_LOCAL_USER` (409), `WRONG_PASSWORD` (400), `EMAIL_IN_USE`, `EMAIL_MISSING`, `EMAIL_NOT_VERIFIED` and `OIDC_LOGIN_FAILED` (OIDC login failures, sent as `/login?error=` redirects; §5).
- v0 adds the codes in §14.2. Multi-repository tasks (§14.7) add:
  - `COMPONENT_NOT_IN_PROJECT` (400): a `componentIds` entry is not a component of the task's project.
  - `AGENT_LACKS_COMPONENT_ACCESS` (409): the assignee agent has no access to one of the task's repositories (task create or update, or approving a request).
  - `TOO_MANY_REPOSITORIES` (409): the task would have more than 20 repositories (the default set on create, or approving a request).
  - `REPOSITORY_REQUEST_DECIDED` (409): the repository request is no longer PENDING.
- Runner capacity settings (§14.8) add:
  - `CREDENTIAL_POOL_FIXED` (409): a desired pool size for a `CODEX_CHATGPT_AUTH` credential, whose pool is always 1.
- Trackers (§17) add:
  - `DUPLICATE_TRACKER_NAME` (409): the company has a tracker connection with this name.
  - `INVALID_TRACKER_RULE` (400): a rule cannot work, e.g. an `ASSIGNEE` rule for an agent without an account on the connection, a `LABEL` rule without a value, or a rule for a project that uses Tinfirm tasks.
  - `TRACKER_ACCOUNT_IN_USE` (409): replacing the agent accounts would leave an `ASSIGNEE` or `MENTION` rule without its agent's account.
- Asks (§18) add:
  - `ASK_DECIDED` (409): the ask is no longer `OPEN`: it was answered or cancelled.
- Work sources ([ADR-0029](../../adr/0029-work-source-per-project.md), §14.1) add:
  - `PROJECT_USES_TRACKER` (409): the operation belongs in the project's tracker, e.g. a member's new task in a project that Jira or GitHub feeds. The detail says to create the issue there.
  - `PROJECT_HAS_TRACKER_RULES` (409): a project cannot switch to `TINFIRM` while a tracker rule, enabled or not, targets it. The detail names the connections.
  - `TASK_KEY_TAKEN` (409): a member's new task lost its key to a task from an issue of another project that a poll inserted at the same moment. The detail names the key; a retry takes the next one.
- Mentions ([ADR-0034](../../adr/0034-mention-an-agent.md), §14.1) add:
  - `DUPLICATE_AGENT_HANDLE` (409): another live agent of the company has the handle. A handle against the rule of §14.1 is `400 VALIDATION_FAILED`, with the rule as the detail.
- Who may give an agent work ([ADR-0035](../../adr/0035-agent-usage-policy.md), §14.2) adds:
  - `AGENT_NOT_USABLE` (403): the agent is usable by its owner only (`usage` `OWNER`, §14.1), and the caller is neither its owner nor an OWNER or ADMIN of the company: a task created for it or changed to it, a manual start of its run, or a comment that mentions it (§14.3). The detail names the agent, or the handles of a comment, and says it is usable by its owner only; nothing is stored. `create_task` answers `REJECTED` with the same code (§15.6).
- Routines (§19, [ADR-0032](../../adr/0032-routines.md)) add:
  - `DUPLICATE_ROUTINE_NAME` (409): the company has a routine with this name.
  - `PROJECT_HAS_ROUTINES` (409): a project with Tinfirm tasks cannot switch to a tracker while a routine, enabled or not, targets it. The detail names the routines.
  - A routine for a project that a tracker feeds is `409 PROJECT_USES_TRACKER`. An invalid cron or time zone, a cron that fires more often than every 15 minutes, a project, agent or component that does not fit, and a company's 51st routine are `400 VALIDATION_FAILED`, with the reason as the detail.

## 7. Database

- **Migrations:** Flyway files in `server/src/main/resources/db/migration`, named `V001__short_name.sql`, `V002__...`. Never edit a released migration.
- **Identifiers:** UUIDv7, generated in the application.
- **Timestamps:** `timestamptz`, stored in UTC.
- **Roles:** `tinfirm_owner` owns all objects and runs Flyway. `tinfirm_app` is the runtime role; migrations `GRANT` it only what it needs.
  - Compose creates both roles in `deploy/compose/postgres/init.sql`.
  - For Helm with an external database, the operator creates them; `docs/` explains how.
- **Tenant tables:** every table that belongs to a company has `company_id uuid NOT NULL`, and runs `ENABLE ROW LEVEL SECURITY` plus `FORCE ROW LEVEL SECURITY`.
- **Tenant context:** each transaction sets these values with `set_config(..., true)` (transaction-local):
  - `app.company_id`: the company of the request, or empty.
  - `app.user_id`: the caller.
  - `app.instance_admin`: `true` or `false`.

  Policies use `current_setting('app.company_id', true)` and must deny when it is empty, except for explicitly cross-company reads. Examples:
  - a user's own memberships (`user_id = app.user_id`);
  - companies where the user is a member;
  - any row when `app.instance_admin = 'true'`.
- **Audit tables:**
  - `tinfirm_app` has only `INSERT` and `SELECT` on `audit_event`.
  - It has `SELECT`, `INSERT` and `UPDATE` on the chain-head table only. That is needed for the `FOR UPDATE` lock and for advancing the head.
  - The instance-chain head row is readable and updatable in every context, because logins advance it before any admin context exists. It holds only `seq` and a hash.
  - It never has DELETE on either table.
- **Audit RLS:**
  - `audit_event` and the chain-head table have RLS, but `company_id` is nullable (null means the instance chain).
  - Company rows are visible when `company_id = app.company_id` or `app.instance_admin = 'true'`.
  - Instance-chain rows are visible only when `app.instance_admin = 'true'`.
  - Inserts are allowed when `company_id IS NULL OR company_id = app.company_id`, because instance events such as logins are written with no company context.
- **Pagination:** cursor-based and keyset. Never unbounded queries or OFFSET.
- **Mentions** ([ADR-0034](../../adr/0034-mention-an-agent.md), `V032__mentions.sql`):
  - `agent.handle` (`varchar(50) NOT NULL`, the rule of §14.1 as a CHECK) with a partial unique index on `(company_id, handle)` of the live agents. The migration made the handles of existing agents from their names, without folding accents.
  - `task_wake`, the pending wakes of named agents (§14.3), a tenant table like `ask` with composite `(id, company_id)` foreign keys to the task, the agent, the comment and the run: `trigger` (`MENTION`, `ASK` or `REPOSITORY_REQUEST`), `comment_id` (the comment that mentioned or answered the agent), `status` (`PENDING`, `STARTED` with `run_id`, `DROPPED` with `reason`), `created_by` (the member whose comment or decision caused it; the run's `created_by`), `created_at`, `decided_at`. `tinfirm_app` has `SELECT`, `INSERT` and `UPDATE`; wakes are never deleted.
  - `run.trigger` may be `MENTION`; `task.followup_trigger` and `run.lost_followup` may not.
- **Agent usage** ([ADR-0035](../../adr/0035-agent-usage-policy.md), `V033__agent_usage.sql`): `agent.usage` (`varchar(16) NOT NULL`, `COMPANY` or `OWNER` as a CHECK), who may give the agent work (§14.1, §14.2). The migration gave existing agents `OWNER` when they had a runner credential, else `COMPANY`, lifting FORCE ROW LEVEL SECURITY for the update as V032 did.

## 8. Audit log

- Each company has its own chain, and there is one instance chain (`company_id` null) for instance events such as users, logins and the vault.
- Each chain has a head row, locked with `SELECT … FOR UPDATE`. The head gives the next `seq` and `prevHash`.
- `hash = hex(sha256(prevHash + "\n" + canonical))`.
  - `canonical` is JSON with keys sorted recursively and no whitespace.
  - It contains `companyId`, `seq`, `occurredAt` (UTC with microseconds: `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`), `actor`, `action`, `targetType`, `targetId` and `details`.
  - The first `prevHash` is 64 zeros.
- **Actions in M0:**
  - `auth.login.succeeded`, `auth.login.failed`, `auth.logout`
  - `user.created`, `user.updated`, `user.password.set`, `user.password.changed`
  - `company.created`, `company.updated`, `admin.company.accessed`
  - `member.added`, `member.role.changed`, `member.removed`
  - `secret.created`, `secret.updated`, `secret.deleted`
  - `secret.accessed` (emitted by the internal reveal, which is not used in M0)
  - `vault.rewrapped`
- **Actions added later:** v0 in §14.2. Multi-repository tasks (§14.7) add these, all in the company chain:

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `task.repositories.changed` | USER (who updated the task, approved the request or deleted the component) | `task` | `key`, `added` and `removed` (component ids), `cause`: `task.updated`, `repository_request.approved` or `component.deleted` |
  | `repository_request.created` | AGENT (the run's agent) | `repository_request` | `taskId`, `taskKey`, `runId`, `componentId`, `componentName` |
  | `repository_request.approved` | USER | `repository_request` | `taskId`, `taskKey`, `componentId`, `agentId`, `hasNote` |
  | `repository_request.denied` | USER | `repository_request` | `taskId`, `taskKey`, `componentId`, `agentId`, `hasNote` |
  | `repository_request.cancelled` | USER (who closed the task or deleted the component) | `repository_request` | `taskId`, `taskKey`, `componentId`, `cause`: `task.closed` or `component.deleted` |

  The request's reason and the note are free text, so they stay out of `details`.

  Runner capacity settings (§14.8) add these, in the company chain. Only a request that changes the value is audited.

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `runner_host.updated` | USER | `runner_host` | `name`, `oldDesiredMaxConcurrentRuns`, `newDesiredMaxConcurrentRuns` (null: no desired value), `configVersion` (the host's new version) |
  | `runner_credential.updated` | USER | `runner_credential` | `runnerHostId`, `label`, `oldDesiredPoolSize`, `newDesiredPoolSize` (null: no desired value), `configVersion` (the host's new version) |

  Company model credentials ([ADR-0027](../../adr/0027-company-model-credentials.md)) add these, in the company chain. `details` never holds the key.

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `model_credential.created` | USER | `model_credential` | `name`, `format`, `baseUrl`, `secretId`, `poolSize` |
  | `model_credential.updated` | USER | `model_credential` | `name`, `changed` (of `name`, `baseUrl`, `secretId`, `poolSize`), `baseUrl`, `secretId`, `poolSize` |
  | `model_credential.deleted` | USER | `model_credential` | `name`, `format`, `clearedAgentIds` (the agents that lost it with `force=true`) |
  | `model_credential.status_reset` | USER | `model_credential` | `name`, `previousStatus` (`COOLDOWN` or `INVALID`), `cooldownUntil` (when the cooldown would have ended; null unless it was cooling down) |

  A member resets the status with `POST /model-credentials/{id}/reset-status`, or by storing another secret in an `INVALID` credential (then after `model_credential.updated`). The server's cooldown after a usage limit and its `INVALID` after a refused key (§14.8) are not audited; the run's `run.finished` event and its `RATE_LIMIT` event record them.

  Resumed runs (§15.1) add this, in the company chain:

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `run.resumed` | SYSTEM | `run` | `taskId`, `taskKey`, `agentId`, `runnerHostId`, `epoch`, `reason` (the frame that showed that the host still runs it: `hello`, `run.heartbeat` or `run.finished`), `lostCause` (`LEASE_EXPIRED`), `lostAt` (when the run was marked `LOST`), `followupRestored` (the follow-up trigger that is pending again, or null) |

  Changed task prefixes ([ADR-0020](../../adr/0020-changeable-task-prefixes.md)) add `taskPrefix` and `previousTaskPrefix` (null when unchanged) to the `details` of `project.updated`.

  Work sources ([ADR-0029](../../adr/0029-work-source-per-project.md)) add `workSource` to the `details` of `project.created` and `project.updated`, and `previousWorkSource` (null when unchanged) to those of `project.updated`.

  Code hosts ([ADR-0023](../../adr/0023-code-host-interface-gitlab.md)) add `codeHost` to the `details` of `component.created`, and to those of `component.updated` when it changes (`changed` then lists `codeHost`).

  Tasks created by agents ([ADR-0019](../../adr/0019-agent-created-tasks.md)) are audited as `task.created` with actor `AGENT` (the run's agent). `details` has the usual fields plus `originTaskId`, `originTaskKey` and `runId`.

  Trackers (§17) add these, in the company chain:

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `tracker.created` | USER | `tracker` | `kind`, `name`, `baseUrl`, `secretId`, `pollEnabled`, `pollIntervalSeconds`, `closeOnDone` |
  | `tracker.updated` | USER | `tracker` | `name`, `changed` (the changed fields), `baseUrl`, `secretId`, `pollEnabled`, `pollIntervalSeconds`, `closeOnDone` |
  | `tracker.deleted` | USER | `tracker` | `name` |
  | `tracker.rules.changed` | USER, or the deleting actor with `cause` `agent.deleted` | `tracker` | `count`, and `rules` (each `scope`, `trigger`, `value`, `conditions` with its non-empty lists when the rule has any, `agentId`, `projectId`, `enabled`), or `cause` and `agentId` |
  | `tracker.agent_accounts.changed` | USER, or the deleting actor with `cause` `agent.deleted` | `tracker` | `count`, and `agentIds`, or `cause` and `agentId` |
  | `tracker.issue.linked` | SYSTEM (the poller) | `task` | `connectionId`, `issueId`, `issueKey`, `taskKey`, `ruleId`, `trigger`, `projectId`, `agentId`, `adopted` (true when an existing task of the issue was linked again) |

  Account names (Jira usernames, GitHub logins) and the names of tracker users stay out of `details`. A task made from an issue is audited as `task.created` with actor `SYSTEM` and the usual fields plus `externalSource` and `externalKey`; a change of its title or description from the issue as `task.updated` with `cause` `external`.

  Asks (§18) add these, in the company chain:

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `ask.created` | AGENT (the run's agent) | `ask` | `taskId`, `taskKey`, `runId`, `askId`, `options` (how many) |
  | `ask.answered` | USER (who commented or answered), or SYSTEM for a comment a poll imported | `ask` | `taskId`, `taskKey`, `runId`, `askId`, `answerCommentId`, `cause` (`external` for an imported comment, else null) |
  | `ask.cancelled` | The actor who closed the task | `ask` | `taskId`, `taskKey`, `runId`, `askId`, `cause` (`task.closed`) |

  The question, its options and the answer are free text, so they stay out of `details` and out of logs. `run.finished` gets `resultOutcome`: `COMPLETED` or `BLOCKED` from the agent's `finish_run`, or null for a run without a result.

  Routines (§19) add these, in the company chain:

  | Action | Actor | Target | `details` |
  |---|---|---|---|
  | `routine.created` | USER | `routine` | `name`, `enabled`, `projectId`, `agentId`, `cron`, `timeZone`, `componentIds` (null: every component the agent may access), `overlap`, `catchUp`, `nextRunAt` |
  | `routine.updated` | USER; the deleting actor of an agent or a component; the actor of a firing that found the agent deleted | `routine` | The fields of `routine.created` and `changed` (the changed fields). When the server changed it: `name`, `changed`, `cause` (`agent.deleted` with `enabled` and `agentId`, or `component.deleted` with `componentId`) |
  | `routine.deleted` | USER | `routine` | `name` |
  | `routine.fired` | SYSTEM for a scheduled firing, USER for run now | `routine` | `outcome` (§19.3), `taskId` and `taskKey` (null without a task), `scheduledFor` (the due time, or the moment of a run now), `cause` (`routine.run` for run now, else null) |

  A routine's title and description are free text and stay out of `details`, and so does the reason of a failed firing. A task that a routine created is audited as `task.created` with the firing's actor and the usual fields plus `cause` (`routine`, or `routine.run` for run now) and `routineId`.

  Mentions ([ADR-0034](../../adr/0034-mention-an-agent.md)) add `handle` to the `details` of `agent.created` and `agent.updated` (a changed handle is in `changed`), and `commentId` to `run.started`: the comment that mentioned or answered the agent of a pending wake (§14.3), else null. Comments and pending wakes themselves are not audited.

  Who may give an agent work ([ADR-0035](../../adr/0035-agent-usage-policy.md)) adds `usage` to the `details` of `agent.created` and `agent.updated` (a changed usage is in `changed`). A refused request (`AGENT_NOT_USABLE`) is not audited.
- `details` never contains secret values or passwords.
- **Personal data stays out of the hashed part.** Erasure requests must not break the chain.
  - The canonical actor is `{type, id}` only. The API resolves `actor.display` at read time; a deleted user shows as `deleted user`.
  - `details` holds IDs, roles and names of non-personal objects, never emails or display names.
  - A failed login for an unknown email records `{"reason":"unknown_user"}` with no email.
  - OIDC login events record the registration id as `provider`, and a refused OIDC login its `reason` (`not_provisioned`, `email_missing`, `email_not_verified`, `invalid_email`, `email_in_use`, `inactive`, `oidc_error`). `user.created` for an OIDC user records the claim the email came from (`emailClaim`), never the email.

## 9. Credential vault

- **Envelope encryption.** Each secret version gets a random 256-bit data key (DEK).
  - The value is encrypted with AES-256-GCM under the DEK, with a 96-bit random nonce and AAD `companyId|secretId|version`.
  - The DEK is wrapped with AES-256-GCM under the active master key.
  - The server stores the `keyId`, the wrapped DEK, the nonces and the ciphertext.
- **Master keys** come only from `TINFIRM_VAULT_KEYS` and `TINFIRM_VAULT_ACTIVE_KEY`. In Kubernetes these env vars are filled from a Secret. HashiCorp Vault or KMS is a later extension point, behind a `MasterKeyProvider` interface.
- **Rewrap:** `POST /api/admin/vault/rewrap` rewraps every DEK that is not under the active key. It is idempotent and audited.
- **No API returns a value.** An internal `VaultService.reveal(companyId, secretId, purpose)` exists for M1. It is audited as `secret.accessed`, it updates `lastAccessedAt`, and it is not exposed over HTTP. In M0, `lastAccessedAt` is always null.

## 10. Server code conventions

- **Spring Modulith**: one top-level package per module under `com.flowtivo.tinfirm`: `shared`, `identity`, `company`, `audit`, `vault`, `bootstrap`, from v0 `project`, `runner`, `model` (company model credentials, [ADR-0027](../../adr/0027-company-model-credentials.md)), `agent`, `work`, `run`, `tracker` (§17) and `routine` (§19). A module's internals are package-private or in an `internal` subpackage. The build runs `ApplicationModules.verify()`.
- **Controllers** implement interfaces generated from `api/openapi.yaml` by `openapi-generator-maven-plugin` (the `spring` generator, `interfaceOnly`). Controllers stay thin; services hold the logic. DTOs are mapped with MapStruct.
- **Errors** are Spring `ProblemDetail`, with the `code` property from §6.
- **Tests:**
  - JUnit with Testcontainers PostgreSQL 18, running as the real `tinfirm_app` role, so RLS is really exercised.
  - One **tenant isolation test** reads every company-scoped path from `api/openapi.yaml` and calls it as a member of company A against company B. It expects 404. A new path is covered automatically.
- **Build:** `cd server && ./mvnw -B verify` must pass with Docker available. The Maven wrapper is committed.

## 11. Web conventions

- Angular 22: standalone, zoneless, signals and `OnPush` everywhere; control flow `@if` and `@for`; `httpResource` or `resource` for reads.
- **UI:** spartan/ui. Primitives come from `@spartan-ng/brain`. Styled components are generated with the spartan CLI into `web/src/app/ui/` and belong to us. Styling is Tailwind 4, with light and dark themes through a class on `<html>`.
- **API client:** generated from `../api/openapi.yaml` into `web/src/app/api/generated/`. That folder is ignored by git.
  - `npm run generate:api` regenerates it, and `prestart`, `prebuild` and `pretest` run that script.
  - Hand-written code never duplicates DTO types.
- **Build output:** `web/dist/tinfirm-web/browser`. The Dockerfile copies it into the server's `static/`.
- **Dev:** `npm start` serves on port 4200, with `proxy.conf.json` forwarding `/api`, `/oauth2` and `/login/oauth2` to `http://localhost:8080`.
- **Routes:**
  - `/login`
  - `/` redirects to the first company
  - `/c/:companyId/overview` (empty state for M0)
  - `/c/:companyId/members`, `/c/:companyId/secrets`, `/c/:companyId/audit`, `/c/:companyId/settings`
  - `/me` (profile and password)
  - `/admin/users`, `/admin/companies`, `/admin/audit`
  - v0: `/c/:companyId/projects`, `/c/:companyId/projects/:projectId`, `/c/:companyId/tasks/:taskId` (linked from PR bodies), `/c/:companyId/runs/:runId`, `/c/:companyId/agents`, `/c/:companyId/runners`
  - Trackers (§17): `/c/:companyId/trackers`
  - Asks and the waiting-on state (§18): `/c/:companyId/inbox`, second in the navigation after the overview
  - Routines (§19): `/c/:companyId/routines`, after Trackers in the navigation
- **Checks:** `npm run lint`, `npm test` (Vitest) and `npm run build` must pass. Playwright e2e tests live in `web/e2e/` and run against `E2E_BASE_URL`, default `http://localhost:8080`.

## 12. Image and deployment

- **Root `Dockerfile`**, multi-stage:
  1. Node 24 builds `web/`.
  2. JDK 25 with Maven builds `server/`, with the web output copied into `server/src/main/resources/static/`.
  3. Runtime JRE 25: non-root UID `10001`, read-only-root compatible (writes only to `/tmp`), ports 8080 and 8081.
- **Compose** (`deploy/compose/compose.yaml`): `postgres` (18) and `server`. Profile `oidc` adds Keycloak with an imported realm and a test user.
  - Host ports, bound to `127.0.0.1` only: server `8080`, Postgres `5432` (so a locally run server and the tests can use it), Keycloak `8180`.
  - `make up`, `make up-oidc` and `make down` are the entry points. `deploy/compose/.env.example` holds clearly marked **development-only** defaults, including a dev vault key.
- **Helm** (`deploy/helm/tinfirm`):
  - The image tag comes from the top-level value `imageTag`.
  - Secrets are referenced by name, never inlined.
  - Probes use port 8081, the pod runs non-root with a read-only root filesystem, and a NetworkPolicy is included.
- **CI** (`.github/workflows/`):
  - `ci.yml`: server verify, web lint, test and build, helm lint, and a docker build.
  - `release.yml` and `security.yml` call reusable release and security workflows, with manifests `pom:server/pom.xml:tinfirm-server` and `npm:web`.

## 13. Rules for contributors and agents

- Stay inside the paths you own (§2). If you need something from another area, say so in an issue instead of changing it.
- Do not edit this file. Propose changes in an issue.
- `api/openapi.yaml`: you may change it in your own pull request, together with the code that needs the change. Say so in the pull request description. A human reviews the contract change before it is merged. Without a pull request of your own, propose the change in an issue.
- **Agents** do not run `git commit`, `git push` or any command that publishes anything, and do not create remote repos, registries or cloud resources. Humans follow `CONTRIBUTING.md`.
- Commit no real secrets. Development defaults are allowed only in `.env.example` and in test code, and must be clearly marked.
- Before you finish, run your area's checks and give the exact result in your pull request's description.

## 14. v0: projects, components, agents, tasks and runs

v0 is the smallest slice that turns a member's task into pull requests. A member creates a task, and an agent on the member's own runner host then does these things in order:
1. clones the task's repositories;
2. makes the change and commits;
3. the runner host pushes the branch in every repository with new commits;
4. the server opens one pull request per repository on its code host: a GitHub pull request, or a GitLab merge request.

The HTTP contract is in `api/openapi.yaml` (tags Projects, Components, Agents, RunnerHosts, ModelCredentials, Tasks, Runs). Tasks with several repositories and repository requests are decided in [ADR-0015](../../adr/0015-multi-repository-tasks.md). Runner capacity settings, queue reasons and drain restarts are decided in [ADR-0016](../../adr/0016-runner-capacity-settings.md).

### 14.1 Domain rules
- Every new table is a tenant table (§7): `company_id NOT NULL`, RLS, keyset pagination.
- **Project:** a named container for tasks and components. It has a task prefix, unique within the company ([ADR-0018](../../adr/0018-project-task-prefixes.md)). Owners and admins can change it: existing tasks keep their keys, and every prefix a project used stays reserved for it ([ADR-0020](../../adr/0020-changeable-task-prefixes.md)).
  - Its **work source** ([ADR-0029](../../adr/0029-work-source-per-project.md)) says where its tasks come from: `TINFIRM` (the default), `JIRA` or `GITHUB` ([ADR-0030](../../adr/0030-github-issues-tracker.md)). Owners and admins change it like the rest of the project.
    - `TINFIRM`: Tinfirm's own tasks: the board, the new-task dialog, task prefixes and keys, and `create_task`. A tracker rule may not target such a project (§17.2). Routines create tasks only in such a project (§19).
    - `JIRA` and `GITHUB`: the issues that the rules of a tracker connection of kind `JIRA_DC` or `GITHUB` match become its tasks (§17); the issue owns the backlog and the workflow. A member's `POST /projects/{id}/tasks` is `409 PROJECT_USES_TRACKER`, and `create_task` answers `REJECTED` / `PROJECT_USES_TRACKER` (§15.6), both naming the tracker (the source's display name, Jira or GitHub). Its runs get the prompt's tracker line (§15.5).
    - `TINFIRM` to `JIRA` or `GITHUB` works while no routine, enabled or not, targets the project, else `409 PROJECT_HAS_ROUTINES` naming the routines ([ADR-0032](../../adr/0032-routines.md)): existing tasks keep working and keep their keys, new work comes from the rules. Leaving `JIRA` or `GITHUB` is `409 PROJECT_HAS_TRACKER_RULES` while a rule of any connection, enabled or not, targets the project. Tasks linked to issues stay linked and keep syncing whatever the source.
    - The source decides only where tasks come from. The tracker parts of `TINFIRM` (board, prefixes and keys, new-task dialog, `create_task`) are frozen and get no new tracker features; later sources come as connectors. Everything about agent control applies to every task whatever its project's source: runs, comments, repository requests and pull requests (§14), asks, answers, the inbox, the waiting-on state and `finish_run` results (§18), and every later agent-control feature.
    - Other modules read it through `ProjectCatalog` (`project`, and `lockProjects` under a key-share lock); the project module asks the tracker module whether rules target a project through the port `TrackerRuleUsage`, and the routine module whether routines do through the port `RoutineUsage`.
- **Component:** a git repository of a project. v0 supports only `GIT_REPOSITORY` with an HTTPS URL.
  - Its **code host** is `GITHUB` or `GITLAB` ([ADR-0023](../../adr/0023-code-host-interface-gitlab.md)): where Tinfirm opens the change requests of its tasks (§14.4). `GITLAB` covers self-managed GitLab and gitlab.com.
    - A create without `codeHost` gets `GITHUB` when the host of the URL is `github.com`. Any other host, `gitlab.com` included, must be named: else `400 VALIDATION_FAILED`.
    - An update without `codeHost` keeps it, also when the URL changes.
  - The credential is a vault secret: on GitHub, for example a fine-grained PAT with Contents and Pull requests read/write; on GitLab, a personal access token of the agent's bot user with the scopes `api` and `write_repository`.
  - `run.assign` sends the token with the git user name of the code host (`Workspace.git.username`): `x-access-token` on GitHub, `oauth2` on GitLab.
  - A null credential means a public repository.
- **Agent:**
  - It has a **handle** ([ADR-0034](../../adr/0034-mention-an-agent.md)): how people mention it in comments, `@handle` (§14.3). 1 to 50 lower-case ASCII letters, digits and hyphens, not starting or ending with a hyphen (`^(?!-)[a-z0-9-]{1,50}(?<!-)$`), unique among the live agents of the company like the name. A request may send capitals; the handle is stored in lower case. A create without a handle, or an update with an empty one, gets one made from the name: Unicode NFD without combining marks (`Vývojář` becomes `vyvojar`), lower case, every run of other characters one hyphen, hyphens trimmed, cut to 50 characters without a trailing hyphen, `agent` when nothing is left, and `-2`, `-3`… (the base shortened to stay within 50) when a live agent has it. A changed handle does not rewrite earlier comments. The web makes the same handle from a typed name as the dialog's placeholder.
  - It has a runtime (`CLAUDE_CODE` or `CODEX`) and a credential: a runner credential or a model credential ([ADR-0027](../../adr/0027-company-model-credentials.md)), never both. A create names exactly one of `runnerCredentialId` and `modelCredentialId`, an update at most one, else `400 VALIDATION_FAILED`; an update that sets one clears the other. Deleting the runner host or the model credential leaves the agent without a credential.
  - It has **one owner**, its creator. It may use a runner credential only when the credential's runner host has the same owner (`403 CREDENTIAL_NOT_OWNED`); this enforces one owner per subscription. A model credential belongs to the company: anyone who may create or change the agent (§14.2) may give it one.
  - Its **usage** says who may give it work ([ADR-0035](../../adr/0035-agent-usage-policy.md), §14.2): `COMPANY`, every member who may create tasks, or `OWNER`, only its owner and the company's OWNERs and ADMINs. A create without `usage` gets `OWNER` on a runner credential (the owner's subscription) and `COMPANY` on a model credential (the company's gateway key); a given `usage` wins. An update changes it only when the request names one: a new credential, also of the other kind, keeps it.
  - The credential must serve the agent's runtime (`400 CREDENTIAL_RUNTIME_MISMATCH`): a runner credential lists the runtime, a model credential's `format` serves it.
  - The agent can clone and push only the components in its `componentIds`.
- **Task:**
  - Its key is the task prefix of its project plus a per-project number that increases by 1, e.g. `WEB-12`. The counter lives on the project row, which is locked when the next number is taken.
  - A task made from an issue (§17.4) takes the project's next number too, but the issue's key as its key when that has at most 32 characters and the form `^[A-Z][A-Z0-9_]*-[0-9]+$`, e.g. `PROJ-123`; otherwise a key from the prefix ([ADR-0029](../../adr/0029-work-source-per-project.md)), as always for a GitHub issue (`owner/repo#57`). Keys are unique in the company (`task_company_key_uq`), so a key from the prefix that a task from an issue already has is skipped for the next number, and numbers can have gaps. Existing keys never change. When a poll gives the same key to a task from an issue of another project at the same moment, a member's create answers `409 TASK_KEY_TAKEN`, and `create_task` `REJECTED` / `TASK_KEY_TAKEN` (§15.6).
  - A task has at most one agent assignee and zero to 20 **repositories**: components of its own project (§14.7).
  - Its working branch is `agent/<KEY>`, the same in every repository.
  - An agent can create a task during a run with the platform tool `create_task` (§15.6, [ADR-0019](../../adr/0019-agent-created-tasks.md)): in the project of the run's task, in `BACKLOG`, assigned to that agent or to another agent of the company it names. It runs only once a member moves it to `TODO`. A project whose work source is a tracker (`JIRA` or `GITHUB`) takes none.
- **Run:** one execution of an agent on a task: of the task's assignee, or of an agent woken by name, which a person mentioned, whose question a person answered or whose repository request a member decided (§14.3, [ADR-0034](../../adr/0034-mention-an-agent.md)). At most one run per task can be `QUEUED` or `RUNNING`, enforced by a partial unique index.
  - A run keeps the task's repositories as they were when the run was created (its workspace), each with its `dir`. Later changes to the task affect only later runs.
- **Runner host:**
  - Registered by a member, who becomes its owner. It belongs to one company.
  - Registration returns a token once, formatted `tfr_` + base64url of 32 random bytes. The server stores only its SHA-256.
  - The host reports its credentials in `hello`, and the server upserts them as `runner_credential` rows by `(runnerHostId, label)`. They hold metadata only.
  - `hello` also reports the host's ceilings: its slots and each credential's pool. Members can lower them in Tinfirm without a restart (§14.8).
  - The credentials of an offline host show `OFFLINE`.
- **Model credential** ([ADR-0027](../../adr/0027-company-model-credentials.md)): an endpoint of the company's model gateway, for example one LiteLLM virtual key per agent. It belongs to the company, not to a member, and lives in the server: a name (unique in the company), a `format`, a `baseUrl`, a vault secret with the key, a pool size (1–16, default 4) and a status: `AVAILABLE`, `COOLDOWN` or `INVALID` (§14.8).
  - `format` is the API the endpoint speaks: `ANTHROPIC` (the Messages API) serves `CLAUDE_CODE`, `OPENAI` serves `CODEX`. It cannot change after creation.
  - `baseUrl` is https, without user info, query or fragment; a trailing slash is removed. For `ANTHROPIC` it is the URL to which the runtime appends `/v1/messages`, for `OPENAI` the URL that ends in `/v1`.
  - Deleting the secret leaves the credential without a key (`secretId` null); its runs then wait with `CREDENTIAL_MISSING`.
  - The API returns metadata only. The server reveals the key only for `run.assign` (§15.1), audited as `secret.accessed` with purpose `run:<runId>`.

### 14.2 Permissions (added to §6)

| Operation | OWNER | ADMIN | OPERATOR | VIEWER |
|---|---|---|---|---|
| List or get projects, components, agents, tasks, comments, repository requests, asks, runs, run events, runner hosts, credentials, routines | yes | yes | yes | yes |
| Create or update a project (its work source included); create, update or delete a component | yes | yes | no | no |
| Create an agent (the caller becomes its owner) | yes | yes | yes | no |
| Update or delete an agent, set its components | yes | yes | only own agents | no |
| Register a runner host (the caller becomes its owner) | yes | yes | yes | no |
| Delete a runner host | yes | yes | only own hosts | no |
| Set or clear the desired capacity of a runner host or of its credentials (§14.8) | yes | yes | only own hosts | no |
| Create or update a task, comment, cancel a run | yes | yes | yes | no |
| Assign a task to an agent the caller may use (create, or change the assignee) | yes | yes | yes | no |
| Start a run of a task whose assignee is an agent the caller may use | yes | yes | yes | no |
| Mention an agent the caller may use in a comment (§14.3) | yes | yes | yes | no |
| Approve or deny a repository request | yes | yes | yes | no |
| List or get model credentials (metadata only) | yes | yes | yes | yes |
| Create, update or delete a model credential, or reset its status (end a cooldown, clear `INVALID`) | yes | yes | no | no |
| Answer an ask (§18) | yes | yes | yes | no |
| List or get tracker connections, their rules and agent accounts (§17) | yes | yes | yes | yes |
| Create, update, test, poll or delete a tracker connection; replace its rules or agent accounts (§17) | yes | yes | no | no |
| Create, update or delete a routine (§19) | yes | yes | no | no |
| Run a routine now (§19) | yes | yes | yes | no |

Instance admins who are not members follow §6: they have access, and it is audited as `admin.company.accessed`.

**An agent the caller may use** ([ADR-0035](../../adr/0035-agent-usage-policy.md)): an agent whose `usage` is `COMPANY`; or one whose `usage` is `OWNER` when the caller is its owner and a member, or an OWNER or ADMIN of the company (an instance admin acting in the company counts as an ADMIN, as `CompanyAccess.covers(MANAGE_AGENTS)` says). The agent module holds this one rule (`AgentUsage.allows`, `AgentView.usableBy`); the web mirrors it (`canUseAgent`). Otherwise `403 AGENT_NOT_USABLE` (§6).
- **Assigning**: `createTask` with `assigneeAgentId`, and `updateTask` that sets `assigneeAgentId` to another agent. An update that keeps the assignee or clears it is not a use, and neither is a status change, moving the task to Todo included.
- **Starting**: `POST /tasks/{id}/runs`, for the task's assignee.
- **Mentioning**: a member's comment that mentions an agent other than the task's assignee whose open questions it does not answer (§14.3); the whole comment is refused, and nothing is stored.
- **Agents act with their owner's rights**: `create_task` (§15.6) names only an agent its owner may use, judged by the owner's current role; an owner who is no longer a member may use `COMPANY` agents only.
- **Not gated**: comments, answers to asks and repository decisions on a task that is already assigned to the agent, which continue work its owner or an admin gave it; tracker rules, agent accounts and routines (§17.2, §19), which OWNERs and ADMINs set up and whose tasks are SYSTEM actions, and a routine's run now; viewing anything.

New problem codes:
- `CREDENTIAL_NOT_OWNED` (403)
- `CREDENTIAL_RUNTIME_MISMATCH` (400)
- `COMPONENT_IN_USE`, `AGENT_HAS_ACTIVE_RUNS`, `RUN_ALREADY_ACTIVE`, `TASK_NOT_RUNNABLE`, `DUPLICATE_RUNNER_HOST_NAME`, `DUPLICATE_COMPONENT_NAME`, `DUPLICATE_PROJECT_NAME`, `DUPLICATE_AGENT_NAME`, `RUNNER_HOST_HAS_ACTIVE_RUNS` (409)
- Multi-repository tasks: see §6.
- Model credentials ([ADR-0027](../../adr/0027-company-model-credentials.md)): `DUPLICATE_MODEL_CREDENTIAL_NAME` (409); `CREDENTIAL_IN_USE` (409): live agents use the model credential, and the delete has no `force=true`. With `force=true` the agents lose it (as when a runner host is deleted), and the audit event lists them.
- Asks: `ASK_DECIDED`, see §6.
- Work sources: `PROJECT_USES_TRACKER`, `PROJECT_HAS_TRACKER_RULES`, `TASK_KEY_TAKEN`, see §6.
- Routines (§19): `DUPLICATE_ROUTINE_NAME`, `PROJECT_HAS_ROUTINES`, see §6.
- Mentions: `DUPLICATE_AGENT_HANDLE`, see §6. Whoever may comment may mention an agent the caller may use (above); there is no permission of its own.
- Who may give an agent work: `AGENT_NOT_USABLE`, see §6.

New audit actions:
- `project.created`, `project.updated`
- `component.created`, `component.updated`, `component.deleted`
- `agent.created`, `agent.updated`, `agent.deleted`, `agent.components.changed`
- `runner_host.registered`, `runner_host.deleted`
- Runner capacity settings: `runner_host.updated`, `runner_credential.updated` (see §8)
- `task.created` (`details.componentIds`: the initial repositories), `task.updated` (fields other than the repositories)
- `run.started`, `run.finished`, `run.cancelled`
- Resumed runs: `run.resumed` (see §8)
- Repositories and repository requests: see §8.
- Model credentials: `model_credential.created`, `model_credential.updated`, `model_credential.deleted`, `model_credential.status_reset` (see §8).
- Trackers (§17): see §8; their problem codes are in §6.
- Asks (§18): `ask.created`, `ask.answered`, `ask.cancelled`, and `resultOutcome` in `run.finished`; see §8.
- Routines (§19): `routine.created`, `routine.updated`, `routine.deleted`, `routine.fired`; see §8.

Revealing a component's git token for a run emits `secret.accessed` with purpose `run:<runId>`: one event per repository of the run, and one per pull request the server opens or updates. Reading pull request states (§14.4) emits one event per repository and worker pass, with purpose `pull-request-state:<componentId>`. Tracker tokens (§17) use the purposes `tracker-poll:<trackerId>`, `tracker-write:<trackerId>` and `tracker-test:<trackerId>`.

### 14.3 Wakes (event-driven; no timers wake agents in v0)

A wake has a target ([ADR-0034](../../adr/0034-mention-an-agent.md)): the task's **assignee**, or a **named agent**. The port `TaskWakes` (work module; the run module implements it) has `wake(task, trigger)` for the assignee and `wakeAgent(task, agent, trigger, comment)` for a named agent; for the assignee, `wakeAgent` is `wake` (a `MENTION` of the assignee wakes it with `COMMENT`).

A run is created, with its trigger, when one of these happens:
- **`ASSIGNMENT`**:
  - a task is created with an agent assignee and status `TODO`, also the task of a routine's firing (§19): a routine wakes nobody itself, its task does;
  - an update gives a `TODO` task an agent assignee;
  - an update moves a task with an agent assignee to `TODO`.
- **`COMMENT`:** a person's comment wakes the assignee: a USER comment on a task that has an agent assignee and is not `DONE` or `CANCELLED`, or the EXTERNAL comments a poll imports into such a task (§17; one wake per poll and task), under the rules of a comment below. If a run is already active, set `followup_pending` on the task; when that run finishes, and no pending wake is left, create at most one follow-up run in the same transaction (§7 coalescing rule). A member's comment that does not answer the open asks (`answersOpenAsks` absent or false) wakes no assignee while the task has an `OPEN` ask, not even with `followup_pending`: the agent reads it among the new comments of its next wake, together with the answers (§15.5, §18.3).
- **`ASK`:** a person answered an agent's asks (§18.3). It wakes **the agent that asked**: the assignee with the same conditions and coalescing as `COMMENT`, which it replaces, so the agent is woken once; another agent with a pending wake. A comment wakes each agent whose asks it answered: a member's comment with `answersOpenAsks: true`, or the EXTERNAL comments of a poll. An answer through `POST /tasks/{id}/asks/{askId}/answer` answers one ask and wakes the agent that asked only when it leaves no open ask of that agent on the task, whatever other agents' asks are open; until then it wakes nobody ([ADR-0033](../../adr/0033-one-answer-per-ask.md), ADR-0034).
- **`MENTION`:** a person mentions an agent that is not the task's assignee in a comment (below). The mentioned agent gets a pending wake.
- **`MANUAL`:** `POST /tasks/{id}/runs`, always the assignee's, by a member who may use it (§14.2).
- **`REPOSITORY_REQUEST`:** a member approves or denies a repository request of the task (§14.7), or a task update adds a repository that had a PENDING request. It wakes **the agent that requested it** (`repository_request.agent_id`): the assignee with the same coalescing as `COMMENT`, another agent with a pending wake.

**Mentions.** In a comment's Markdown body, `@handle` is a mention when `@` is at the start or after a character that is not a letter, digit, `_`, `-`, `/`, `.` or `@` (so `someone@example.com` and `owner/@repo` mention nobody), and the handle is the longest run of letters, digits and hyphens after it (`@test-writer,` mentions `test-writer`; `@test` does not mention `test-writer`), matched ignoring case against the handles of the company's live agents (§14.1); other text is not a mention. Nothing in a fenced code block (```` ``` ```` or `~~~`) or in inline code mentions. One parser in the work module reads them (`Mentions.in`). An imported tracker comment also mentions the agents whose account on the connection it mentions (§17.4). Only people's comments mention: `USER` and `EXTERNAL` comments, never `AGENT` or `SYSTEM` ones. Whoever may comment may mention an agent they may use (§14.2).

**Mentions of an agent usable by its owner only** ([ADR-0035](../../adr/0035-agent-usage-policy.md)). A mention that would give an agent a `MENTION` wake (step 2 below: not the assignee, its open questions not answered by the comment) gives it work:
- A member's comment (`UserComments.post`) that mentions such an agent the member may not use is `403 AGENT_NOT_USABLE`, naming the handles; nothing is stored.
- An EXTERNAL comment's author is not a member, so it may give work to `COMPANY` agents only. The comment is stored and its answers and other wakes proceed; the `MENTION` wakes of `OWNER` agents are left out, and for each such agent that has no other wake from the poll the task gets a `SYSTEM` comment `{agent name} was not started (mention): it is usable by its owner only, and a mention on {source display name} cannot be matched to a member.` (`Jira` or `GitHub`), which the write-back carries to the issue (§17.5). No pending wake is created for it.

**A comment** (`UserComments.post`; a poll's EXTERNAL comments one by one, §17.4) wakes, in this order:
1. **Answers.** With `answersOpenAsks` (an EXTERNAL comment always), it answers the `OPEN` asks (§18.3) and wakes each agent whose asks it answered with `ASK`.
2. **Mentions.** Every agent it mentions that is not the assignee and was not woken in step 1 gets a pending wake with `MENTION`, in the order of the body.
3. **The assignee**, when not woken in step 1: with `COMMENT`, when the task is not closed, has an assignee and has no `OPEN` ask after step 1, unless the comment mentions other agents and not the assignee (it is addressed to them) or answered other agents' asks only (the assignee reads it at its next wake). A comment that mentions the assignee, alone or with others, is a plain comment for it.

The comments of one poll coalesce: the assignee is woken once (`ASK` when one of its asks was answered, else `COMMENT`), and every pending wake is stored before any starts, so several mentions of one agent start one run.

**Pending wakes** (`task_wake`, §7; the run module keeps the table and its store, the work module sees only the port): a wake of a named agent that is not the assignee.
- Without an active run on the task it starts at once; during one it stays `PENDING`.
- **When a run ends**, whatever its final status, `CANCELLED` included, the task's oldest `PENDING` wake starts, in the run's ending transaction with the task locked: one run of its agent that takes **every** `PENDING` wake of that agent on the task, so its prompt has all its mentions (§15.5). Only when no `PENDING` wake is left is the assignee's follow-up taken (a cancel still only clears it). So the chain is the named agents in the order their wakes arrived, then the assignee's follow-up. A cancel stops the one run; the other wakes stay queued, and a person who wants to stop them cancels those runs too.
- **Starting** a wake needs the agent live, `ACTIVE` and with a credential; access to the task's repositories is not a precondition. The run's trigger is `MENTION` when it takes a mention, else the trigger of its oldest wake; its `created_by` is the member whose comment or decision caused that wake (none for an imported comment); `run.started` records the comment (§8). Its **workspace** is the task's repositories the agent may access, each with the agent's own read-only flag; the others are left out, and without any the run has no workspace and no branch, as for a task without repositories.
- When the agent cannot start, or the task is `DONE` or `CANCELLED`, its wakes are `DROPPED` with the reason, the task gets a `SYSTEM` comment `{agent name} was not started ({trigger}): {reason}.` (e.g. `Test writer was not started (mention): agent Test writer is paused.`, or `...: the task is DONE.`), and the next agent's wakes are tried.
- Deleting an agent drops its `PENDING` wakes (`the agent was deleted`), without a comment. Pausing it leaves them pending; they are dropped with the comment when their turn comes.
- `task.followup_trigger` is never `MENTION`: the assignee's follow-up is never a mention.

**Preconditions of the assignee:**
- The agent is `ACTIVE` and has a credential: a runner credential or a model credential (§14.1). The run records it when it is created (`run.runner_credential_id` or `run.model_credential_id`).
- The agent has access to every repository of the task.

When a precondition fails:
- The manual API answers `409 TASK_NOT_RUNNABLE` with the reason.
- Automatic wakes post a `SYSTEM` comment with the reason instead.

### 14.4 Dispatch, leases and task status
- **Dispatch.** The worker dispatches every 2 s with `FOR UPDATE SKIP LOCKED`, oldest first, and at once when a run is queued, a slot is freed or a host sends `host.config.applied`. A `QUEUED` run goes to its credential's runner host when all of these are true:
  - the agent is `ACTIVE`;
  - the host is connected, has sent `host.config.applied` on this connection (§15.7) and does not drain;
  - the host still reports the credential, the credential serves the agent's runtime, and it is `AVAILABLE` (not invalid, not in cooldown);
  - the agent's `maxConcurrentRuns`, the credential's pool and the host's slots all have a free slot, counted with the dispatch limits of §14.8.

  A `QUEUED` run of a **model credential** ([ADR-0027](../../adr/0027-company-model-credentials.md)) goes to any runner host of the company, when all of these are true:
  - the agent is `ACTIVE`;
  - the model credential exists, still has its secret, and its `format` serves the agent's runtime;
  - a host is connected, has sent `host.config.applied` on this connection, does not drain, and lists the agent's runtime in its latest `hello.runtimes` (§15.1; a host without a stored list counts as serving every runtime);
  - the gateway has not refused the credential's key (it is not `INVALID`), and it is not in cooldown;
  - the agent's `maxConcurrentRuns`, the credential's `poolSize` (runs of the credential that hold a slot) and the host's slots have a free slot.

  Of the hosts that can run it and have a free slot, the one with the most free slots wins, then the first by name, so the load spreads. The dispatcher locks the agent, the model credential and then those hosts in the order of their ids. No credential of the host is involved, and the host's runner credentials keep their own pools.

  Otherwise the run stays queued, and its queue reason names the first check that failed (§14.8).

  Assignment increments the run's `epoch` and sends `run.assign`. If there is no `run.accepted` within 10 s, the run goes back to the queue; during a reconnect grace, only once the grace is over (§15.1).
  - A requeue, after an accept timeout or `run.rejected`, also increments the epoch to fence late messages. A rejected run waits 10 s before it is dispatched again.
  - When a run ends, the task's pending wakes of named agents start first (§14.3). Follow-up runs are created after `SUCCEEDED`, `FAILED` and `LOST`, once no pending wake is left. A cancel clears `followup_pending` then, but starts a pending wake as any end does.
  - Manual runs are allowed in every task status except `DONE` and `CANCELLED`.
  - Deleting an agent is a soft delete: history keeps it, its tasks lose their assignee, and its name becomes free again.
- **Leases.** `run.accepted` sets the run to `RUNNING`, `startedAt` to now, and the lease to now + `TINFIRM_RUNNER_LEASE_TTL` (default `PT30S`). Heartbeats renew it, and so do the other frames of its host (§15.1).
  - The reaper runs every 5 s and marks a `RUNNING` run with an expired lease `LOST`, unless its host's connection received a frame within the lease time; then it renews the lease.
  - A restart of the server or a dropped connection does not lose runs: they keep their lease for a reconnect grace, and a run lost by lease expiry is resumed when its host still runs it (§15.1).
  - Messages with a stale epoch get `error STALE_EPOCH` and are ignored. The exceptions are `run.repository_request`, `run.task_create` and `run.ask`: they always get a `run.repository_request.ack`, `run.task_create.ack` or `run.ask.ack`, here `REJECTED` / `RUN_NOT_ACTIVE` (§14.7, ADR-0019, §18.2).
- **Queued too long.** A run stays `QUEUED` while its host is offline. After 5 min, one `SYSTEM` comment says so: ``The run is still queued after 5 minutes (RUNNER_UNAVAILABLE). {text}``, where `{text}` is the text of its queue reason (§14.8), or `The dispatcher has not looked at it yet.` without one.
- **Cancel.**
  - A `QUEUED` run becomes `CANCELLED` immediately.
  - A `RUNNING` run gets a `run_command` row and `run.cancel`. Pending commands are sent again when the host reconnects.
- **Task status.** Only the runs of the task's assignee move its status ([ADR-0034](../../adr/0034-mention-an-agent.md)): the run of a named agent (§14.3) leaves it as it is, also when it succeeds; its `AGENT` comment, git results, pull requests and asks are as for any run, and a pull request it opens is the task's. A run start moves `TODO` → `IN_PROGRESS`. `run.finished` then does this:
  - `SUCCEEDED`: `IN_REVIEW`, plus an `AGENT` comment with the summary and the PR links (with the agent's result, also its next steps and open questions, §18.4). A run without a result that ends on its own questions gets **no** comment ([ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)): at the moment it ended, an ask of the run was `OPEN` or a repository request of the run was `PENDING` (one answered or decided later counts; one answered before does not). Everything else of its ending is as for any run: git results, pull requests, wakes, the waiting-on state, the audit and `run.summary`. A run with a result keeps its comment, also when it asked. The status stays instead when the agent waits for a person:
    - the run stored a repository request (its ack was `PENDING`, §14.7), whatever the request's status is now;
    - an ask of the run is still `OPEN` (§18.2);
    - the agent's result is `BLOCKED` (§18.4).
  - `FAILED` or `LOST`: the status stays, plus a `SYSTEM` comment with the failure class and message.
  - `CANCELLED`: the status stays.
- **Pull requests: one per repository, on its code host.** The component's code host (§14.1, [ADR-0023](../../adr/0023-code-host-interface-gitlab.md)) decides where: a pull request on GitHub, a merge request on GitLab. The API, the database and the comments call both pull requests. For a GitLab repository, `TaskRepository.pullRequestUrl` is the merge request's `web_url`, `pullRequestNumber` its `iid`, and `PullRequestState` its state.

  After a `SUCCEEDED` or `CANCELLED` run, the server looks at each repository of the run whose git result has `pushed=true` and `commits > 0`, and whose URL the code host can address: on GitHub, a repository on `github.com`; on GitLab, `https://{host}[:{port}]/{namespace}/{project}(.git)` with any depth of subgroups. When the task has no pull request in that repository yet, the server opens one with that component's token:
  - title `<KEY>: <title>`; head `agent/<KEY>`; base the repository's default branch;
  - body, in Markdown on both hosts: the summary, the task URL, for a task from a GitHub issue a line that names the issue ([ADR-0030](../../adr/0030-github-issues-tracker.md)), the related pull requests block (below), and "Opened by Tinfirm agent <name>". The issue line is `Closes #{number}` in a GitHub pull request of the issue's own repository (owner and name compared ignoring case), so the issue closes when the pull request is merged; `Refs {owner}/{repo}#{number}` in another GitHub repository; `Issue: {externalUrl}` in a GitLab merge request. A task from a Jira issue gets no such line. For a run that ended on its questions (above, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)), the summary is `{agent name} asked a question on the task before finishing; this pull request holds the work so far. The conversation continues on the task.`, followed by a blank line and the agent's last message when that is not blank;
  - **GitHub:** `POST {TINFIRM_GITHUB_API_URL}/repos/{owner}/{repo}/pulls` with `Authorization: Bearer`. On a 422 "already exists", it looks the PR up with `GET /pulls?head={owner}:{branch}&state=open`.
  - **GitLab:** the REST API v4 at the origin of the component's URL, `https://{host}[:{port}]/api/v4`, with the token in the `PRIVATE-TOKEN` header. The project id is the URL-encoded path (`group%2Fsubgroup%2Fproject`). `POST /projects/:id/merge_requests` with `source_branch`, `target_branch`, `title`, `description` and `remove_source_branch` false. On a 409 "already exists", it looks the merge request up with `GET /projects/:id/merge_requests?source_branch={branch}&state=all`, the open one first. Requests time out after `tinfirm.run.gitlab-timeout` (default `PT30S`) and follow no redirects. A 5xx answer or a connection error (refused, reset, timed out, unknown host) is tried again, up to `tinfirm.run.gitlab-attempts` attempts in all (default `3`), after a pause of `tinfirm.run.gitlab-retry-backoff` (default `PT1S`) that doubles each time; a 4xx answer is not. The server's trust store must hold the CA of a self-managed instance.

  Each repository is claimed, attempted and recorded on its own (`run_repository.pr_status`, ADR-0015). The PR URL and number are stored for the task and component (`task_pull_request`) and on the run's repository. A PR failure never fails the run; it posts a `SYSTEM` comment that names the repository. The `AGENT` comment of a `SUCCEEDED` run is posted once no repository of the run is still `PENDING`, and lists every pull request of the task: `Pull requests:` then one line `- {component name}: {url}` each. A run that ended on its questions gets none then either: the same rule decides, at the moment the run ended (`RunCompletion.endsOnItsQuestions`).
- **Pull request state and watching (ADR-0022).** `task_pull_request.state` is `OPEN`, `DRAFT`, `MERGED` or `CLOSED`, as the code host last reported it, and `TaskRepository.pullRequestState` shows it. Opening a PR records the state of the code host's answer. Every 15 s the worker reads the due ones, revealing each component's token once per pass: on GitHub with `GET /repos/{owner}/{repo}/pulls/{number}`; on GitLab with `GET /projects/:id/merge_requests/:iid`, where `opened` is `OPEN`, or `DRAFT` when `draft` (or the older `work_in_progress`) is true, `merged` is `MERGED`, and `closed` and `locked` are `CLOSED`. A PR is due when it has no state yet, when a run pushed to it, and when its task starts watching. Whoever may update the task (§14.2) sets `Task.watchPullRequests` (audited as `task.updated`, off for a new task); while it is on, every PR of the task that is not `MERGED` is also due once it was last read `TINFIRM_RUN_PULL_REQUEST_STATE_INTERVAL` ago or more. `CLOSED` stays watched, because a closed PR can be reopened. A failed read is logged; the PR keeps its last state.
- **Related pull requests.** Each PR body has a block between `<!-- tinfirm:related -->` and `<!-- /tinfirm:related -->`: the line `Related pull requests:` and one line `- {component name}: {url}` for each other pull request of the task, or nothing when there is none. After the server opened at least one PR for a run and the task has two or more, it rewrites that block in every other PR of the task: `GET` the pull request, replace the block (or append it when a body has none), and write the new body back, on GitHub with `PATCH /repos/{owner}/{repo}/pulls/{number}`, on GitLab as the `description` with `PUT /projects/:id/merge_requests/:iid`. A failed update is logged and not retried; the next opened PR updates the bodies again.

### 14.5 Run events and SSE
- Stored in `run_event (run_id, seq)` with the primary key on that pair, capped at 20 000 events per run: LOG events are dropped first.
- Payload shapes are in `protocol/runner-v1.schema.json` `$defs/EventPayload*`, and the server stores them unchanged.
- `USAGE` payloads carry **cumulative** totals for the run. The latest USAGE event is the run's usage, and `run.finished.usage` repeats the final totals.
- SSE uses Spring MVC `SseEmitter`: replay from the database after `afterSeq` or `Last-Event-ID`, then live from an in-process publisher, then a final `run-status` event, then the stream closes. Timeout 30 min; the client reconnects with `Last-Event-ID`.
- v0 assumes one server replica for the live fan-out and the runner connection registry (§16).

### 14.6 New server configuration

| Variable | Default |
|---|---|
| `TINFIRM_RUNNER_LEASE_TTL` | `PT30S` |
| `TINFIRM_RUNNER_HEARTBEAT_INTERVAL` | `PT10S` |
| `TINFIRM_RUNNER_RECONNECT_GRACE` (§15.1) | `PT2M` |
| `TINFIRM_RUNNER_LOST_RESUME_WINDOW` (§15.1; `PT0S` turns resuming off) | `PT10M` |
| `TINFIRM_RUN_MAX_WALL_CLOCK` | `PT60M` |
| `TINFIRM_RUN_MAX_TURNS` | `200` |
| `TINFIRM_GITHUB_API_URL` | `https://api.github.com` |
| `TINFIRM_RUN_PULL_REQUEST_STATE_INTERVAL` (§14.4; at least `PT30S`) | `PT5M` |
| `TINFIRM_RUN_MODEL_CREDENTIAL_COOLDOWN` (§14.8: the cooldown of a model credential after a usage limit without a reported reset time) | `PT15M` |

### 14.7 Task repositories and repository requests

The decision and the tables (migration `V013__multi_repository.sql`) are in [ADR-0015](../../adr/0015-multi-repository-tasks.md).

**Repositories of a task**
- They are components of the task's own project. Another id: `400 COMPONENT_NOT_IN_PROJECT`. At most 20.
- **Create.** Without `componentIds`, the task gets every component of the project that the assignee agent has access to, or none without an assignee. More than 20 that way: `409 TOO_MANY_REPOSITORIES`. An empty array means none.
- **Update.** `componentIds` replaces the set; without it the set stays. Repositories that stay keep their `dir`.
- **Access.** When a create or update sets the assignee or the repositories, the resulting assignee must have access to every resulting repository, else `409 AGENT_LACKS_COMPONENT_ACCESS` (the detail names the components). Other updates do not check this. When an agent loses access later, its tasks keep their repositories and the wake precondition (§14.3) blocks the run.
- An update that adds a repository with a PENDING request approves that request: decided by the caller, no note, with the audit, the comment and the wake of an approval (below).
- Deleting a component removes it from every task and cancels its PENDING requests.
- Every change of the set is audited as `task.repositories.changed` (§8).
- **Order.** Oldest first, then by `dir`. `Task.repositories`, the prompt and `run.assign.workspaces` use this order.

**Directory rule.** The server derives `dir` from the component name when the component joins the task, and stores it. It does not change while the component stays on the task, even if the component is renamed, so a resumed session finds the same paths.
1. Decompose to Unicode NFKD and drop combining marks (`é` becomes `e`).
2. Lowercase.
3. Replace each run of characters other than `a-z` and `0-9` with one `-`.
4. Trim `-` from both ends. Cut to 40 characters, then trim a trailing `-` again.
5. If the result is empty, use `repo`.
6. If another repository of the task already has this `dir`, append `-2`, `-3` and so on: the first free one.

The result matches `^[a-z0-9]+(-[a-z0-9]+)*$` and has at most 64 characters. Examples: `server` → `server`; `Web App` → `web-app`; `web_app` on a task that has `web-app` → `web-app-2`; `Ürün API` → `urun-api`; `日本` → `repo`.

**Repository requests**
- An agent asks for another repository of the project with the platform tool `request_repository` (§15.6). The runner host sends `run.repository_request`, and the server answers with exactly one `run.repository_request.ack`, also for a stale epoch or an unknown run.
- The server checks in this order. The first failure is the answer: status `REJECTED` with its `code`, and nothing is stored.
  1. The run is `RUNNING` with this epoch; else `RUN_NOT_ACTIVE`.
  2. If a request with the same `(runId, requestId)` exists, the answer is `PENDING` with its id again, whatever its status now. Nothing else happens.
  3. The task is not `DONE` or `CANCELLED`; else `TASK_CLOSED`.
  4. `name` and `reason` are not blank after trimming; else `INVALID_REQUEST`.
  5. The component is `componentId` when that is a component of the task's project. Otherwise it is the project's component whose name equals `name`, first exactly, then ignoring case. Else `UNKNOWN_REPOSITORY`.
  6. It is not on the task; else `ALREADY_ON_TASK`.
  7. The run's agent has access to it; else `NO_ACCESS`.
  8. The task has no PENDING request for it; else `ALREADY_PENDING`.
  9. The task has fewer than 20 repositories; else `TOO_MANY_REPOSITORIES`.
  10. The run has fewer than 10 requests; else `TOO_MANY_REQUESTS`.

  Then the server stores the request as `PENDING`, audits `repository_request.created` with the agent as actor, and answers `PENDING`.
- The ack's `message` is written for the agent. For `PENDING` ([ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)): ``Requested the repository `{name}`. The request is posted on the task, where a member approves or denies it. You may ask more, or request another repository. When you have nothing else to do, stop without writing a summary: Tinfirm wakes you with the decision.`` For `REJECTED`: one sentence with the reason, for example ``The repository `server` is already on the task, at /workspace/server.``
- The run is not held open for the decision. It finishes normally, and §14.4 leaves the task status as it is.
- **Approve** (OWNER, ADMIN, OPERATOR), in one transaction that locks the task and then the request:
  - not `PENDING`: `409 REPOSITORY_REQUEST_DECIDED`;
  - the task has 20 repositories: `409 TOO_MANY_REPOSITORIES`;
  - the current assignee, if any, has no access to the component: `409 AGENT_LACKS_COMPONENT_ACCESS`;
  - otherwise: add the component to the task with a new `dir`; set the request `APPROVED` with the member, the time and the note; audit `repository_request.approved` and `task.repositories.changed`;
  - post a `SYSTEM` comment: ``{member} approved the request for the repository `{name}`. It is checked out at `/workspace/{dir}` from the next run.``, then a blank line and the note, if any;
  - wake the agent that requested it with `REPOSITORY_REQUEST` (§14.3): the assignee, or another agent, such as a mentioned one, with a pending wake.
- **Deny** (OWNER, ADMIN, OPERATOR): not `PENDING` gives `409 REPOSITORY_REQUEST_DECIDED`. Otherwise set `DENIED`, audit `repository_request.denied`, post the `SYSTEM` comment ``{member} denied the request for the repository `{name}`.`` with the note as above, and wake the agent that requested it with `REPOSITORY_REQUEST`.
- **Cancel.** Only the server cancels, never the API:
  - when the task moves to `DONE` or `CANCELLED`: all its PENDING requests, note `The task was closed.`;
  - when the component is deleted: its PENDING requests, note `The repository was deleted.`

  A cancelled request has no decider, `decidedAt` is the time of the cancel, and it is audited as `repository_request.cancelled`. There is no comment and no wake.
- **Signal.** `Task.pendingRepositoryRequestCount` counts the PENDING requests. The board marks a task with a count above zero as "needs you". The task conversation shows PENDING requests as cards with Approve and Deny (OPERATOR and above), and decisions as the `SYSTEM` comments above. The v1 inbox will list them too.

### 14.8 Runner capacity and queue reasons

The decision and the migration `V014__runner_capacity.sql` are in [ADR-0016](../../adr/0016-runner-capacity-settings.md). The protocol side is §15.7.

**Three limits.** A run starts only when three limits each have a free slot:
- the agent's `maxConcurrentRuns` (1–10), set in Tinfirm;
- the pool of the agent's runner credential, or the `poolSize` (1–16) of its model credential, set in Tinfirm;
- the slots of that credential's runner host; for a model credential, of the runner host the dispatcher picks (§14.4).

A slot is held by every run that is `RUNNING`, or `QUEUED` and assigned (`dispatched_at` set).

**Ceilings, desired values and effective values.** The host's slots and each credential's pool have three values, each between 1 and 16 (only a draining host has 0 slots):
- The **ceiling** is set by the machine owner on the host: `run --max-concurrent-runs N` (default 2) and `credential add … --pool N` (default 3; always 1 for a Codex ChatGPT login). The host reads the ceilings when `run` starts and reports them in `hello`.
- The **desired value** is optional. A member sets it in Tinfirm, the server stores it, and `host.config` sends it to the host. It may be above the ceiling: it is kept, and applies up to the ceiling.
- The **effective value** is `min(desired, ceiling)`, or the ceiling when no desired value is set. A draining host has 0 slots (§15.4 step 13). The host computes the effective values and confirms them with `host.config.applied`. Tinfirm can lower use below a ceiling, never raise it above one.

**Changing a desired value**
- `PATCH /runner-hosts/{id}` with `desiredMaxConcurrentRuns`, or `PATCH /runner-credentials/{id}` with `desiredPoolSize`. `clearDesiredMaxConcurrentRuns: true` or `clearDesiredPoolSize: true` removes the value.
- Who (§14.2): the host's owner, if they are OWNER, ADMIN or OPERATOR; or any OWNER or ADMIN. For a credential, the owner of its host. Anyone else gets 403.
- Validation:
  - a value outside 1–16, or a value together with its `clear…` flag: `400 VALIDATION_FAILED`;
  - a `desiredPoolSize` for a `CODEX_CHATGPT_AUTH` credential: `409 CREDENTIAL_POOL_FIXED`. Clearing it is allowed.
- The update runs in one transaction:
  1. lock the host row `FOR UPDATE` (for a credential, its host's row);
  2. store the value;
  3. increase `runner_host.config_version` by 1;
  4. audit `runner_host.updated` or `runner_credential.updated` (§8).

  A request that changes nothing does none of this and answers 200.
- After the commit, if the host is connected, the server sends it `host.config` with the full current state (§15.7).
- The response has the new desired value, the last confirmed effective value and `configPending: true`. It stays so until the host confirms the change. An offline host confirms it when it next connects.

**What the server stores**
- On `hello`, it stores the ceilings. It also sets each effective value to `min(desired, ceiling)` (the value it expects) and `draining` to false, until the host confirms.
- On `host.config.applied`, it stores:
  - the effective values, the ceilings and `draining`, for the host and for each listed credential it knows;
  - `version` as `applied_config_version`, when that is higher than the stored one.

  Then it starts a dispatch pass. It answers an applied message whose effective value exceeds its ceiling, or that lists a label twice, with `error BAD_MESSAGE`, and ignores it.
- `RunnerHost.configPending` is `config_version > coalesce(applied_config_version, 0)`. `RunnerHost.draining` is true only while the host is connected.

**Dispatch limits.** For each limit, the dispatcher (§14.4) uses the last confirmed effective value. When the desired value is lower, it uses the desired value instead. So a lower setting counts at once, and a higher one only after the host confirms it:
- host: `min(runner_host.max_concurrent_runs, coalesce(desired_max_concurrent_runs, 16))`;
- credential: `min(runner_credential.pool_size, coalesce(desired_pool_size, 16))`;
- agent: `agent.max_concurrent_runs`;
- model credential: `model_credential.pool_size`, with no ceiling on a host.

**Queue reasons.** `Run.queueReason` says why a `QUEUED` run waits. The server stores it on the run (`queue_reason`, `queue_reason_details`, `queue_reason_since`). It is null for every other status.

When it is set and cleared:
- A new run has none. The UI shows `Queued.` until the dispatcher has looked at the run.
- A dispatch attempt locks the run. If the run stays unassigned, the attempt records the first check of the table below that fails, in table order.
- Assignment records `ASSIGNED`.
- `run.rejected` records one of two codes, each with `retryAt` set to the run's new `dispatch_after`. The run is not dispatched before then, so the reason stays until that time:
  - `PREVIOUS_RUN_ACTIVE` for the code `TASK_RUN_ACTIVE`;
  - `RUNNER_HOST_REJECTED` for any other code, or none.
- A requeue without a rejection clears it: an accept timeout, or a reconnect of the host that reports another epoch. The next dispatch attempt sets it again; after an accept timeout, that happens in the same pass.
- When the run leaves `QUEUED`, the same update clears it.
- The server writes it only when the code or a field changes. `since` is set when the code changes, and stays while only fields change. Every write publishes a `run-status` SSE event for the run (§14.5).

The fields are those of the API's `QueueReason`. "Host" means `runnerHostId` and `runnerHostName`; "credential" means `credentialId` and `credentialLabel`.

A run of a runner credential is checked in this order:

| Order | Code | When | Fields | Text |
|---|---|---|---|---|
| 1 | `AGENT_PAUSED` | The run's agent is `PAUSED`. | — | `Agent {agentName} is paused.` |
| 2 | `CREDENTIAL_MISSING` | The run has no runner credential, or the credential was deleted. | `credentialId` if known | `The credential of agent {agentName} was removed.` |
| 3 | `RUNNER_HOST_OFFLINE` | The credential's host is not connected, or has not sent `host.config.applied` on its current connection. | host, credential, `lastSeenAt` | `Runner host {host} is offline.` |
| 4 | `RUNNER_HOST_DRAINING` | The host drains (§15.4 step 13). | host, credential | `Runner host {host} is finishing its runs before it restarts.` |
| 5 | `CREDENTIAL_MISSING` | The host's latest `hello` did not list the credential. | host, credential | `Runner host {host} no longer has the credential {credentialLabel}.` |
| 6 | `OTHER` | The credential no longer serves the agent's runtime. | host, credential, `message` | `{message}`, e.g. `Credential claude-main no longer serves the runtime CODEX.` |
| 7 | `CREDENTIAL_INVALID` | The host reported the credential `INVALID`. | host, credential, `message` (its `detail`) | `Credential {credentialLabel} on {host} cannot be used: {message}.` |
| 8 | `CREDENTIAL_COOLDOWN` | The credential is in `COOLDOWN` and `cooldownUntil` is in the future. | host, credential, `cooldownUntil`, `message` (its `detail`) | `Credential {credentialLabel} is in cooldown until {cooldownUntil}.` |
| 9 | `AGENT_AT_LIMIT` | The agent's slots are all held. | host, credential, `used`, `limit` | `Agent {agentName} already runs {used} of {limit} runs.` |
| 10 | `CREDENTIAL_POOL_FULL` | The credential's pool is full. | host, credential, `used`, `limit` | `Waiting for a free slot of credential {credentialLabel} ({used} of {limit} in use).` |
| 11 | `RUNNER_HOST_FULL` | The host's slots are all held. | host, credential, `used`, `limit` | `Waiting for a free slot on {host} ({used} of {limit} in use).` |
| — | `ASSIGNED` | Sent to the host; waiting for `run.accepted`. `since` is the assignment time. | host, credential | `Sent to {host}; waiting for it to start the run.` |
| — | `PREVIOUS_RUN_ACTIVE` | `run.rejected` with code `TASK_RUN_ACTIVE`. The host still runs an earlier run of the task, for example one it is cancelling after the server marked it `LOST`. | host, credential, `retryAt` | `The previous run of this task is still ending on {host}.` |
| — | `RUNNER_HOST_REJECTED` | Any other `run.rejected`. | host, credential, `rejectCode`, `message` (the host's reason, cut to 500 characters), `retryAt` | `Runner host {host} could not start the run: {message}.` |
| — | `OTHER` | Anything else, e.g. the agent was deleted (checked before 1). | `message` | `{message}` |

A run of a **model credential** ([ADR-0027](../../adr/0027-company-model-credentials.md)) is checked in this order. "Credential" is the model credential: `credentialId` is its id and `credentialLabel` its name.

| Order | Code | When | Fields | Text |
|---|---|---|---|---|
| 1 | `AGENT_PAUSED` | The run's agent is `PAUSED`. | — | `Agent {agentName} is paused.` |
| 2 | `CREDENTIAL_MISSING` | The model credential was deleted (the run has no credential). | — | `The credential of agent {agentName} was removed.` |
| 3 | `CREDENTIAL_MISSING` | Its secret was deleted, so there is no key. | credential | `Model credential {credentialLabel} has no key: its secret was deleted.` |
| 4 | `OTHER` | Its `format` no longer serves the agent's runtime. | credential, `message` | `{message}`, e.g. `Model credential litellm no longer serves the runtime CODEX.` |
| 5 | `NO_RUNNER_HOST` | No runner host is connected, has confirmed its capacity on the connection, does not drain and lists the runtime in `hello.runtimes`. | credential, `runtime` | `No connected runner host can run the runtime {runtime}.` |
| 6 | `CREDENTIAL_INVALID` | The gateway refused the credential's key (below): it is `INVALID`. | credential, `message` (its `detail`) | `Credential {credentialLabel} is invalid: {detail}` |
| 7 | `CREDENTIAL_COOLDOWN` | The credential cools down after a usage limit (below), and `cooldownUntil` is in the future. | credential, `cooldownUntil`, `message` (its `detail`) | `Credential {credentialLabel} is in cooldown until {cooldownUntil}.` |
| 8 | `AGENT_AT_LIMIT` | The agent's slots are all held. | credential, `used`, `limit` | `Agent {agentName} already runs {used} of {limit} runs.` |
| 9 | `CREDENTIAL_POOL_FULL` | The credential's `poolSize` is held. | credential, `used`, `limit` | `Waiting for a free slot of credential {credentialLabel} ({used} of {limit} in use).` |
| 10 | `RUNNER_HOST_FULL` | Every host of 5 is full. `used` and `limit` are summed over those hosts; host only when there is one. | credential, `used`, `limit`, host if one | `Waiting for a free slot on {host} ({used} of {limit} in use).`, or without a host `Waiting for a free slot on a runner host ({used} of {limit} in use).` |
| — | `ASSIGNED` | Sent to the chosen host. | host, credential | `Sent to {host}; waiting for it to start the run.` |

`PREVIOUS_RUN_ACTIVE`, `RUNNER_HOST_REJECTED` and `OTHER` apply as for runner credentials, with the model credential as the credential.

**Cooldown of a model credential.** The server keeps it, not the runner host: a run of the credential that ends `FAILED` / `USAGE_LIMIT` puts the credential into `COOLDOWN` until the latest `resetsAt` of the run's `RATE_LIMIT` events when that is in the future, else for `TINFIRM_RUN_MODEL_CREDENTIAL_COOLDOWN` (§14.6). A cooldown that already lasts longer stays. An `INVALID` credential stays invalid. It is not audited. OWNER and ADMIN end it with `POST /model-credentials/{id}/reset-status` (`model_credential.status_reset`, §8).

**Invalid model credential.** A run of the credential that ends `FAILED` / `LOGIN_EXPIRED` means the gateway refused the key: Claude Code reports a 401 or 403 of the gateway as `authentication_failed` and Codex a 401 as `unauthorized`, and the runner host classifies both as `LOGIN_EXPIRED`. The server then puts the credential into `INVALID`, with the `detail` `The gateway refused the key in run {runId} of task {taskKey}.`, in the transaction that ends the run; an invalid credential keeps its first detail and replaces a cooldown, which never replaces it. Its runs wait with `CREDENTIAL_INVALID` instead of failing one after the other. A budget error of the gateway is a usage limit and a rate limit is `SERVER_LIMITED`, so neither makes the credential invalid. It is not audited. It ends when OWNER or ADMIN store another secret in the credential (the key changed) or reset its status with `POST /model-credentials/{id}/reset-status`, for example after unblocking the key in the gateway; both are audited as `model_credential.status_reset` (§8).

- `used` counts the runs that hold a slot of the agent, the credential or the host. `limit` is the dispatch limit from above.
- The texts are the canonical English wording. The UI shows them and may add links and relative times. The `RUNNER_UNAVAILABLE` comment (§14.4) uses the same text.

## 15. Runner protocol and runner host

### 15.1 Transport
- WebSocket at `GET /api/runner/v1/connect` on the application port, authenticated with `Authorization: Bearer <token>`. It is not part of the browser session: there is no CSRF, and no cookies.
- Close codes: 4401 for a bad or revoked token. A second connection from the same host replaces the first, and the server closes the old one with 4409. A host whose drain is complete closes with 1000 (§15.4 step 13).
- Order: `hello`, then `welcome`, then `host.config` before any other frame of the server (§15.7). The server sends `run.assign` to a host only after the host's first `host.config.applied` on that connection.
- Messages are defined in `protocol/runner-v1.schema.json`:
  - Both sides validate them in tests against that schema; `server/` and `runner/` each keep sample messages.
  - Types are hand-written in each language, and a schema test keeps them honest.
- **The credential of `run.assign`** ([ADR-0027](../../adr/0027-company-model-credentials.md)): exactly one of `credentialLabel` (a credential of the host, as `hello.credentials` lists it) and `modelAccess` (a company model credential: `format` `anthropic` or `openai`, `baseUrl`, `apiKey`). The server reveals the key in the transaction that assigns the run, audited as `secret.accessed` with purpose `run:<runId>`, and sends it only in that frame. Like the git tokens, neither side logs the frame, and the types mask the key in `toString()` and `Debug`.
- **`hello.runtimes`** lists the runtimes whose images the host found when it built the frame. The server stores the list (`runner_host.runtimes`) with each `hello`, and sends a run with `modelAccess` only to a host that lists the run's runtime (§14.4). A host with no stored list counts as serving every runtime.
- In production the transport is `wss://` through the ingress. In local development it is `ws://localhost`.
- **Leases.** A `RUNNING` run has a lease of `TINFIRM_RUNNER_LEASE_TTL` (§14.4). These renew it to at least now + TTL:
  - `run.heartbeat`, and the other frames about the run: `run.event`, `run.repository_request`, `run.task_create`, `run.ask` and a repeated `run.accepted`;
  - any frame on the host's connection, a pong included. A host that talks is alive, so this renews all the host's `RUNNING` runs, at most once per sixth of the TTL (5 s). A backlog of events that delays the heartbeats therefore loses no run.

  The reaper marks a run whose lease expired `LOST` only when its host is not connected, or sent nothing for longer than the TTL. Otherwise it renews the lease.
- **Reconnect grace.** `TINFIRM_RUNNER_RECONNECT_GRACE` (default `PT2M`). The runs that hold a slot (§14.8) get a lease of at least now + grace, never a shorter one:
  - when the server starts: before it binds its port for runner connections, and before the reaper and the dispatcher run. While the server was down, no host could renew a lease;
  - when a host's connection closes, and no newer connection of the host has replaced it.

  For an assigned run that awaits `run.accepted`, the lease is how long the accept timeout waits (§14.4): the host may have accepted it while the connection was down. While it holds runs, the host reconnects with a backoff of at most 5 s (§15.4 step 11), well within the grace, and reports its runs in `hello`.
- **Reconciling `hello.activeRuns`:**
  - a `RUNNING` run listed with its current epoch is kept with a fresh lease, also when its lease expired less than the resume window ago;
  - a `RUNNING` run that is not listed is `LOST`: the host restarted. One listed with another epoch, or after its lease expired longer ago than the resume window, is `LOST` and cancelled;
  - an assigned run listed with its current epoch becomes `RUNNING`; another assigned run goes back to the queue;
  - any other listed run is resumed (below), or cancelled;
  - pending cancel commands of the kept and resumed runs are sent again.
- **Resuming a lost run.** The server records why it marked a run `LOST`: `LEASE_EXPIRED` (the reaper), `HOST_RESTARTED` or `STALE_ON_RECONNECT` (`hello`). A run lost with `LEASE_EXPIRED` becomes `RUNNING` again when all of these are true:
  - its host reports it: in `hello.activeRuns`, or with `run.heartbeat` or `run.finished`;
  - it is the run's host, with the run's epoch;
  - the run was marked `LOST` less than `TINFIRM_RUNNER_LOST_RESUME_WINDOW` ago (default `PT10M`; `PT0S` turns resuming off);
  - it is still the latest run of its task, and the task is not `DONE` or `CANCELLED`;
  - its agent and the components of its repositories still exist.

  Resuming undoes what marking the run `LOST` did:
  - the run gets a fresh lease, and loses its finish time, failure class and message;
  - its repositories lose the end state that was recorded (no git result, no pull request);
  - cancel commands that the end completed are pending again, and are sent;
  - the follow-up that the end took from the task is pending again. Had it started a run, that run would be newer, and the run would not be resumed;
  - a task in `TODO` moves to `IN_PROGRESS`, as when a run starts. `lastRunStatus` and `activeRunId` follow the run.

  The `Run lost` comment and the `run.finished` audit event stay. The server audits `run.resumed` (§8). If the `Run lost` comment was posted, it adds the `SYSTEM` comment ``{agent}'s run reconnected and continues.`` It sends no `run.cancel`. The host's buffered events follow as usual. A `run.finished` that resumes the run then ends it with the host's outcome, so its git results and pull requests count.

  A host that still runs a `LOST` run is told to cancel it: at once for `HOST_RESTARTED` and `STALE_ON_RECONNECT`; for `LEASE_EXPIRED`, when it next reports the run (`hello` or `run.heartbeat`) and the run cannot be resumed. The server ignores a `run.finished` for a run that cannot be resumed.

### 15.2 Runner host binary (`runner/`, Rust stable)
- **Crates:**
  - `tinfirm-runner-host` (binary): tokio, tokio-tungstenite, the ACP Rust SDK (`agent-client-protocol` 1.x), bollard, hyper (the git proxy and the platform MCP endpoint), serde, clap, `tracing` with JSON output.
  - `tinfirm-protocol`: message types and schema tests.
- **Directories:**
  - Config: `$XDG_CONFIG_HOME/tinfirm-runner`, default `~/.config/tinfirm-runner`. It holds the server URL, the host token and credential secrets, as 0600 files in a 0700 directory.
  - Data: `$XDG_DATA_HOME/tinfirm-runner`, default `~/.local/share/tinfirm-runner`. It holds workspaces, repository mirrors (`mirrors/`), session homes, and the Codex home.
- **Commands:**

  | Command | Does |
  |---|---|
  | `login --server <url> --token <token>` | Stores the server URL and the host token |
  | `credential add claude --label <l> [--pool 3]` | Asks for the token printed by `claude setup-token` and stores it. The pool is the credential's ceiling, 1–16 (§14.8). |
  | `credential add codex --label <l>` | Runs `codex login` with `CODEX_HOME` set to a dedicated directory under the data dir, never the user's `~/.codex`. Pool is always 1. |
  | `credential add anthropic-api-key` and `credential add openai-api-key`, each `--label <l> [--pool 3]` | Store an API key. The pool is a ceiling, as for `claude`. |
  | `credential list` and `credential remove <label>` | Manage stored credentials. `run` reads the credentials and their pools when it starts. An added credential or a changed pool takes effect at its next start (a restart or a `drain`); a removed one is refused at once (`UNKNOWN_CREDENTIAL`). |
  | `images build [--runtime claude\|codex\|all] [--build-arg KEY=VALUE]…` | Builds the runner images locally from `runner/images/`, tags `tinfirm-runner-claude:local` and `tinfirm-runner-codex:local`. Nothing with Claude Code is ever published. `--build-arg` sets an `ARG` that the Dockerfiles declare, such as a package mirror of a host without internet access (runner/README.md), and only for the images that declare it. An unknown name, a value with a control character and a URL with a user name or password are refused before Docker is called: build arguments stay in the image's history, so never credentials. |
  | `doctor` | Checks Docker (and lists its runtimes), images, credentials (presence and format only, with no paid calls), the mirrors directory and the server connection |
  | `mirrors list` | Lists the repository mirrors: key, size, last use, last gc and repository URL (never a credential) |
  | `mirrors prune [--older-than-days N] [--dry-run]` | Deletes idle mirrors and collects garbage in the others, like the maintenance of `run` (§15.4 step 2). Refused while `run` holds `run.lock`. |
  | `run [--max-concurrent-runs N] [--sandbox-runtime <name>] [--sandbox-docker] [--sandbox-env KEY=VALUE]…` | Connects and serves. N is the host's ceiling, 1–16 (default 2); other values are refused. Members can lower it in Tinfirm (§14.8). The sandbox options (§15.3) have the variables `TINFIRM_RUNNER_SANDBOX_RUNTIME`, `TINFIRM_RUNNER_SANDBOX_DOCKER` and `TINFIRM_RUNNER_SANDBOX_ENV` (one `KEY=VALUE` per line). `run` checks them first and refuses to start on a forbidden combination, or with a runtime that Docker does not know. Once Docker answers, it logs `runner host starting` with the effective runtime, the tier, the Docker flag and the names of the extra variables. After it takes `run.lock`, it writes its PID into that file (decimal, then a newline). |
  | `drain [--no-wait]` | Asks the `run` of this config directory to drain (§15.4 step 13): it sends SIGHUP to the process that holds `run.lock`, whose PID is in the file, and waits until the process has exited. `--no-wait` returns after the signal. Exits 0, also when no `run` is running ("nothing to drain"). |

  **Upgrades without cancelled runs.** A drain lets the active runs finish, and then `run` exits with status 0. A restart script can then start the new binary: `tinfirm-runner-host drain && <start run again>`. With systemd, use `ExecReload=/bin/kill -HUP $MAINPID` and `Restart=always`. Then `systemctl reload` drains, and systemd starts the new binary once `run` exits. `systemctl stop` and `restart` send SIGTERM, which still cancels the active runs (§15.4 step 12).

### 15.3 Sandbox (Docker executor; the isolation tier is a runner host setting)
- **One container per run:** name `tinfirm-run-<runId>`, labels `dev.tinfirm.run-id` and `dev.tinfirm.runner-host`.
- **Image:** the runtime's image. Its entrypoint `tinfirm-sandbox-entrypoint` prepares the sandbox and then execs the ACP adapter (`claude-agent-acp` or `codex-acp`), which becomes the container's main process. The host attaches to its stdio through the Docker API. The entrypoint writes only to stderr.
- **Runtime and tier** ([ADR-0025](../../adr/0025-kata-sandbox-runtime.md)): `run --sandbox-runtime <name>` sets the container's Docker runtime (`HostConfig.runtime`); without it, Docker's default (runc). The name gives the tier:

  | Runtime name | Tier | Boundary |
  |---|---|---|
  | none, `runc` or other | `container` | Shared host kernel |
  | `runsc…` (gVisor) | `gvisor` | gVisor's kernel in user space |
  | `kata…` (Kata Containers, Cloud Hypervisor) | `microvm` | A VM with its own kernel per sandbox |

  `hello.isolationTiers` stays `["container"]` until the protocol carries the tier.
- **Container settings:** user 1000:1000, `cap_drop: ALL`, `no-new-privileges`, memory 4 GiB, 2 CPUs and pids 1024 (all configurable).
- **Docker in the sandbox** (`run --sandbox-docker`): the agent gets its own dockerd. The container then starts as root with `cap_add: ALL`, `seccomp=unconfined`, `apparmor=unconfined`, `label=disable`, empty `MaskedPaths` and `ReadonlyPaths` (the CLI's `systempaths=unconfined`; the Engine API rejects that option itself), a private cgroup namespace, the device cgroup rules `b 7:* rwm` and `c 10:237 rwm` (the VM's loop devices), and `TINFIRM_SANDBOX_DOCKER=1` and `TINFIRM_SANDBOX_AGENT_USER=1000:1000`. The limits stay. The entrypoint starts dockerd with `/var/lib/docker` on a loop disk in the container's own filesystem (never under `/workspace` or `/home/agent`) and runs the adapter as 1000:1000.
  - **Rule:** only with a runtime whose name starts with `kata`. `run` refuses to start otherwise: under runc these settings are root on the host.
  - **Never `privileged`:** Docker lists every host device in a privileged container's spec, and Kata hot-plugs host block devices into the VM. Docker has no `privileged_without_host_devices` (a containerd CRI and CRI-O option).
- **Mounts:**
  - `/workspace`: bind from `<data>/workspaces/<runId>`. It is the agent's working directory. Each repository of the run is a directory `/workspace/<dir>` in it (`run.assign.workspaces[].dir`); without repositories it is empty.
  - `/home/agent`: bind from `<data>/homes/<agentId>/<taskId>`. It keeps the runtime's session files, so a follow-up run can resume.
  - For Codex: `CODEX_HOME=/home/agent/.codex`, bound to the credential's dedicated Codex home.
  - Never a mirror or any other host path: nothing in `/workspace/<dir>/.git` may point outside the workspace (no alternates).
- **Environment:**
  - The credential of the host: `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` (with `DEFAULT_AUTH_REQUEST={"methodId":"api-key"}`, so `codex-acp` logs in with it).
  - Or the model access of a company model credential (`run.assign.modelAccess`, [ADR-0027](../../adr/0027-company-model-credentials.md)), with no credential of the host:
    - `anthropic`: `ANTHROPIC_BASE_URL` (the gateway's root, without `/v1`) and the key as `ANTHROPIC_AUTH_TOKEN`, which Claude Code sends as `Authorization: Bearer`, the header LiteLLM reads for virtual keys; and `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`, so the CLI sends nothing but model requests (no telemetry, error reports or update checks) and works where the sandbox reaches only the gateway;
    - `openai`: `OPENAI_BASE_URL` (as the gateway serves the OpenAI API, usually ending in `/v1`) and the key as `OPENAI_API_KEY`, with the same `DEFAULT_AUTH_REQUEST`. Codex takes the base URL of its OpenAI provider from its config, not from `OPENAI_BASE_URL`, so the host also puts it into `CODEX_CONFIG` as `openai_base_url`, next to the agent's model; `codex-acp` merges that object into the config of every session.

    The key reaches the sandbox like any API key (§16): the agent can read it. The gateway's budget on the key bounds what a leaked key can spend.
  - `DISABLE_AUTOUPDATER=1`.
  - `ANTHROPIC_MODEL` or the Codex model setting, when the agent has a model.
  - Git identity, and `safe.directory` for each `/workspace/<dir>`.
  - The entries of `run --sandbox-env KEY=VALUE`, such as the `TINFIRM_SANDBOX_*` mirror settings that the entrypoint writes into dockerd's `daemon.json` and the agent's Maven, npm, pip and uv settings (runner/README.md). They reach every sandbox unredacted, so **never credentials**. `run` refuses a key that the host sets (`TINFIRM_*` other than `TINFIRM_SANDBOX_*`, `GIT_CONFIG*`, `HOME`, `PATH` and the variables above, `ANTHROPIC_BASE_URL` and `OPENAI_BASE_URL` included), a key that looks like a credential (`TOKEN`, `SECRET`, `PASSWORD`, `KEY`, `AUTH` and similar), a value with a control character, a URL with a user name or password, and a key given twice; its error never repeats the value. Should a key of the host slip through, the host's value wins.
- **Never in the sandbox:** git credentials and the host token. The agent fetches through the host's git proxy (§15.4 step 2) and reaches the platform tools through the host's MCP endpoint (§15.6); the container gets the extra host `host.docker.internal:host-gateway` when they need it.
- **Cleanup:** on startup, the host removes containers with its label that belong to no live run.

### 15.4 Run flow in the runner host
1. Accept or reject. A rejection is `run.rejected` with a `reason` for humans and a `code` (`tinfirm_protocol::reject_code`):
   - `DRAINING`: the host drains or shuts down (steps 12 and 13);
   - `STALE_EPOCH`: the host runs this run with a newer epoch (the same epoch is accepted again);
   - `UNKNOWN_CREDENTIAL`: there is no credential with the label, or the host cannot read it;
   - `CREDENTIAL_UNAVAILABLE`: the credential does not serve the runtime, is invalid or in cooldown, or its Codex home is broken;
   - `INVALID_ASSIGNMENT`: the frame carries both or neither of `credentialLabel` and `modelAccess`; the model access does not serve the runtime (`anthropic` serves `CLAUDE_CODE`, `openai` serves `CODEX`), its `baseUrl` is not HTTPS or has a user name or password, or its URL or key has whitespace or a control character (the reason never repeats the key); or the workspaces are not all valid: more than 20, a `dir` that does not match the pattern or appears twice, a URL that is not HTTPS, or a branch that is not `agent/…`;
   - `IMAGE_MISSING` or `DOCKER_UNAVAILABLE`: the runtime's image is missing, or Docker does not answer;
   - `TASK_RUN_ACTIVE`: another run of the same task still runs on this host, for example one it is cancelling after a reconnect;
   - `NO_FREE_SLOT`: the host's active runs reach its effective `maxConcurrentRuns` (§15.7);
   - `POOL_FULL`: the credential's active runs reach its effective pool.

   The last three are checked while the run takes its slot, so two assignments cannot both take the last one.

   A run with `modelAccess` ([ADR-0027](../../adr/0027-company-model-credentials.md)) uses no credential of the host: `UNKNOWN_CREDENTIAL`, `CREDENTIAL_UNAVAILABLE` and `POOL_FULL` never apply to it, and it holds no pool. It takes a slot of the host like any run; the server keeps the model credential's pool.
2. Git, for each repository of `workspaces`, in order. The token of each repository goes only with commands for that repository, as a per-command `http.extraHeader`, so it is never written to any `.git/config`.
   - **Mirror:** the host keeps one bare mirror per repository URL in `<data>/mirrors/<key>.git`, where `<key>` is the first 32 hex characters of SHA-256 of `repoUrl`. Before each run it fetches the mirror from `repoUrl` with the run's own credentials (`--prune`, `+refs/heads/*` and `+refs/tags/*`), holding a per-mirror lock, so the mirror has the current `agent/<KEY>` and default branch. A new mirror is created in a temporary directory and renamed into place; `gc.auto` is off. Any fetch failure, a 401 or 403 included, fails the run with `GIT_ERROR`: a run never gets content its token can no longer read. This host keeps full mirrors and makes a full local copy (`git clone --no-hardlinks`). Maintenance deletes a mirror that no run used for `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS` (default 30; renamed to `.tmp-delete-…` first, then removed), and otherwise runs `git gc --prune=now` under the mirror's lock when due (weekly after a fetch, or at 1000 loose objects or 20 packs), which is safe because no workspace links to a mirror; `TINFIRM_RUNNER_MIRROR_MAX_BYTES` optionally caps the total by deleting the least recently used mirrors. It never waits for a lock and never deletes a mirror in use. `run` does this after startup and every 6 hours, together with deleting session homes idle for `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS` (default 30, never under an active run; a follow-up run then falls back to `session/new`).
   - **Clone:** clone from the mirror into `<data>/workspaces/<runId>/<dir>`, with no alternates or other link to the mirror (see §15.3). Set `origin` to `repoUrl`. Check out `agent/<KEY>` from `origin` if it exists, or else create it from `origin/<defaultBranch>`. Save this `.git/config` for step 8.
   - **Git proxy:** then point `origin` at the host's fetch-only git proxy: `http://<proxy host>:<port>/<route secret>/<repository path>`. Each repository has its own route and route secret. The agent can `git fetch`, and a partial clone can fetch missing blobs, with no credentials in the sandbox.
   - The proxy forwards only `GET …/info/refs?service=git-upload-pack` and `POST …/git-upload-pack` to the route's repository, with that repository's `Authorization` header. Other request headers, the client's `Authorization` included, are dropped. It does not follow redirects.
   - It answers `git-receive-pack` (push) with 403 and a plain-text reason, and everything else, unknown secrets included, with 404.
   - A route secret is 32 random bytes (base64url), compared in constant time, and leads to one repository. The routes are removed when the run ends.
   - One listener serves all runs, on a random port. It also serves the platform MCP endpoint (§15.6), so it is needed by every run, with or without repositories. Docker Engine on Linux: the gateway IP of the sandbox network. Docker Desktop: `127.0.0.1`, reached as `host.docker.internal`. `TINFIRM_RUNNER_GIT_PROXY_BIND` and `TINFIRM_RUNNER_GIT_PROXY_HOST` override the listen address and the host name the sandbox uses. The network `none` has no proxy.
   - If the proxy cannot start, the run goes on with a warning, without fetch and without platform tools.
   - A repository that cannot be cloned fails the run with `GIT_ERROR` before the container starts.
3. Start the container. ACP `initialize` with no fs or terminal client capabilities, because the agent uses its own tools inside the sandbox.
4. Open the session: `session/load(resumeSessionId)` when it is given; if that fails, `session/new`. Both get `cwd: "/workspace"` and `mcpServers` with the platform MCP server (§15.6), or `[]` when it is not available.
5. `session/prompt`, with `resumePrompt` when the load succeeded, else with `prompt`.
6. Map ACP `session/update` notifications to `run.event`, coalescing text every 250 ms. Answer `session/request_permission` by auto-selecting an allow option (a v0 shortcut).
7. Guards (plus an inactivity guard: no ACP message for `TINFIRM_RUNNER_INACTIVITY_TIMEOUT`, default 20 min, → `session/cancel`, then `GUARD_STOPPED`). A model refusal (`stopReason: refusal`) finishes `FAILED` / `MODEL_REFUSED`, which never pauses the credential. Guards:
   - wall clock > `maxWallClockSeconds`, or tool calls > `maxTurns`: `session/cancel`, then `GUARD_STOPPED`;
   - repeated identical tool calls (5×): the same.
8. Finish, for each repository in order:
   - Restore the `.git/config` saved at clone time, so `origin` is `repoUrl` again (the proxy URL is gone), and remove what the agent could have planted: hooks, alternates, `commondir`, grafts, `shallow`, `http-alternates`, commit-graph and multi-pack-index files, and symlinks in `.git`. Host-side git runs with `GIT_NO_REPLACE_OBJECTS=1`, hooks, fsmonitor, credential helpers and signing off.
   - Commit uncommitted changes as `WIP: uncommitted changes from run <id>`.
   - Count and scan against the mirror's ref tips, which the host controls, never against the workspace's `refs/remotes/origin/*`, which the agent controls: the tips at clone time plus the tips after a best-effort mirror fetch now. The count is `HEAD ^<default branch tip>`; the credential scan covers commit messages and objects reachable from `HEAD` but not from those tips.
   - Push with the explicit URL and a lease, only when the count is above 0: `git push --porcelain --no-verify --force-with-lease=refs/heads/agent/<KEY>:<expect> -- <repoUrl> HEAD:refs/heads/agent/<KEY>`, where `<expect>` is the tip of `agent/<KEY>` in the mirror at clone time, or empty when the branch did not exist then. The push never reads `origin` from `.git/config` and is never a plain force.
     - The branch still has `<expect>` (an empty `<expect>`: it still does not exist): the push goes through, also when the agent rewrote the branch, for example by rebasing it onto the default branch.
     - The branch changed during the run (someone else pushed to it, created it or deleted it): git refuses the push (`[rejected] (stale info)`), and the repository checks the old value again when it updates the ref (`[remote rejected] (incorrect old value provided)` for a change in between). Nothing is pushed or overwritten, and the reason is "the remote branch changed during the run".
     - A rejected or failed push does not stop the other repositories; the run then finishes `FAILED` / `GIT_ERROR`, and `failureMessage` names the repositories and the reason.
   - If the run leaked a credential, or the outgoing commits of any repository contain a credential value, the host pushes no repository.
   - Emit `GIT` events with `componentId` and `dir` (cloned, committed, pushed, nothing_to_push).

   Then send `run.finished` with `git` (one result per cloned repository, in `workspaces` order; `[]` for a run without repositories; when a later repository fails to clone, the repositories cloned so far with `pushed: false`, or `null` when none was cloned), the summary (the agent's last message), `result` (the agent's last `finish_run` call, redacted, or null; §18.4), the session id and usage. Delete the workspace; keep the last 5 failed workspaces.
9. **Usage limits:** `_meta["_claude/rateLimit"]` with status `rejected`, a Codex `UsageLimitExceeded`, the CLI's limit text, or a model gateway's budget error (LiteLLM's error type `"budget_exceeded"`, `Budget has been exceeded!` or `ExceededBudget:`):
   - `RATE_LIMIT` event;
   - `credential.status COOLDOWN` until `resetsAt`;
   - the run finishes `FAILED` / `USAGE_LIMIT`.

   With `modelAccess` the host sends no `credential.status`: it has no credential to cool down. The `RATE_LIMIT` event carries `resetsAt` only when the runtime reported a reset time in the future, and the server cools the model credential down (§14.8).
10. **Redaction:** before any event, summary, log, `run.repository_request` (its `name` and `reason`), `run.task_create` (its `title`, `description` and `repositories`), `run.ask` (its `question` and `options`) or `run.finished.result` (every text) leaves the host, the host replaces occurrences of every credential value (raw, base64, URL-encoded) with `[REDACTED]`. The key of a run's `modelAccess` counts from the run's first event on, in that run only (the host does not keep it). When the agent's own output contains a credential value, the run stops with `CREDENTIAL_LEAK`. The git proxy's route secrets and the MCP endpoint's secret are replaced too, but they are not credentials: `git fetch` prints the proxy URL, so finding one is no leak.
11. **Reconnect:** exponential backoff from 1 s to 30 s, capped at 5 s while the host holds runs (executing, being admitted, or with an undelivered end). Heartbeats go out right after `welcome` and between outbox frames when due, never behind a backlog, every `min(heartbeatIntervalSeconds, leaseTtlSeconds / 3)`. Runs keep going, and events are buffered, up to 10 000 per run. After reconnect, `hello.activeRuns` lists them, and the buffer is flushed with the same epoch. The server keeps the runs for its reconnect grace, and resumes one it marked `LOST` in the meantime (§15.1).
12. **SIGINT or SIGTERM:** cancel all runs (push WIP, report `CANCELLED`), then exit. A second signal exits at once. This does not change with draining (step 13).
13. **SIGHUP or `drain`: drain, then exit.** The host:
    - sends `host.config.applied` with `draining: true` and `maxConcurrentRuns: 0` (§15.7), and from then on rejects every `run.assign` with `DRAINING`;
    - lets its active runs finish normally, with events, heartbeats, platform tools, reconnects and `run.finished` as usual;
    - once no run is active and every message is delivered, closes the connection with 1000 and exits with status 0. It waits for the delivery as long as it takes, reconnects included.

    A second SIGHUP changes nothing. SIGINT or SIGTERM during a drain switches to step 12 for the runs that are still active.

### 15.5 Prompt (rendered by the server)

```
{agent.instructions}                      ← only if set, followed by a line "---"
You are {agent.name}, an AI agent of the company "{company.name}", working through Tinfirm.

# Task {task.key}: {task.title}

{task.description, or "(no description)"}

# Request                                 ← only for the run of a mentioned agent (ADR-0034)
{author} mentioned you in this comment on the task and asked for your help. The task is assigned to {assignee}. Do what the comment asks, and nothing more: the rest of the task belongs to the assignee.

> {comment body, as a Markdown quote, cut like a comment}

# Comments
{all task comments, oldest first: "**{authorDisplay}** · {createdAt}\n{body}"}   ← omit the section when empty; each comment ≤ 8 KiB; the section ≤ 64 KiB, dropping the oldest first with a note

# Repositories
Your working directory is `/workspace`. Each repository of the task is checked out in its own directory, on branch `agent/{KEY}`:   ← only with repositories
- `/workspace/{dir}`: {name} ({repoUrl}), based on `{defaultBranch}`   ← one line per repository, in task order
No repository is checked out for this task. `/workspace` is an empty scratch directory that Tinfirm does not keep, so put your result in your summary.   ← without repositories
You can request these other repositories of the project: `{name}`, `{name}`.   ← only when requestableRepositories is not empty

# How to work
- Work only in the repositories above. To get newer changes in one, run `git fetch origin {defaultBranch}` in its directory, then merge or rebase. You may rebase `agent/{KEY}` onto `origin/{defaultBranch}`: when the run ends, Tinfirm pushes the branch with a lease, and if someone else pushed to it during the run, Tinfirm pushes nothing and you will be told.   ← only with repositories
- If the task needs another repository of the project, call the Tinfirm tool `request_repository` with its name and a short reason; `list_repositories` shows what you can request. When the tool answers that the request is pending, stop: do not write a summary, your request is on the task, and Tinfirm wakes you with the decision once a member approves or denies it; after an approval the repository is checked out under `/workspace`. If the Tinfirm tools are not available, say in your summary which repository you need.   ← only when requestableRepositories is not empty
- Make the change the task asks for, and keep it focused.   ← for a mentioned agent: "- Do only what the request above asks, and keep it focused."
- If you find work outside this task, such as a follow-up, a bug or a cleanup, do not do it here: call the Tinfirm tool `create_task` with a title and a description that stands on its own. The new task waits in the project's backlog, assigned to you, until a member starts it. If another agent of the company should do it, set `assignee` to its name: `{name}`, `{name}`. If the Tinfirm tools are not available, describe that work in your summary instead.   ← work source TINFIRM only; the assignee sentence only when the company has other live agents that the agent's owner may give work (§14.2, ADR-0035; at most 20 names)
- If you find work outside this task, such as a follow-up, a bug or a cleanup, do not do it here: put it into the `nextSteps` of `finish_run` (without the Tinfirm tools, into your summary), and a person creates the issue in {tracker}.   ← instead of the line above when the project's work source is a tracker (ADR-0029); {tracker} is its display name, Jira or GitHub; no agent names
- When you need a decision or information that only a person has, call the Tinfirm tool `ask_human`, one clear question per call, with `options` when the person should pick one. Ask every question you need, then stop: do not write a summary, your questions are on the task, and Tinfirm wakes you with all the answers at once. Never ask for credentials. If the Tinfirm tools are not available, end your summary with the questions instead.
- Run the project's own tests and linters for what you changed, when they exist and are reasonably fast.
- Commit your work in each repository you change, with clear messages, and do not change git remotes or credentials. `git push` does not work in the sandbox: when the run ends, Tinfirm pushes `agent/{KEY}` in every repository with new commits and opens one pull request per repository.   ← only with repositories
- Never print or copy environment variables, tokens or credentials.
- When you are done, or cannot continue without a person, call the Tinfirm tool `finish_run` with the outcome (`COMPLETED` or `BLOCKED`), a summary for the reviewer, the next steps and your open questions; then stop. If the Tinfirm tools are not available, finish with a summary as below.
- Finish with a short summary: what you changed, how you verified it, and any open questions. Tinfirm posts it on the task.
```

`resumePrompt` contains only the new comments since the previous run of this agent on this task, followed by the line: `Continue the task with this feedback. Finish with a short summary as before.` When the task's repositories differ from those of the previous run (for example after an approved request), the `# Repositories` section of the full prompt comes first. When asks of this agent on the task were answered since its previous run began, a `## Answers` section with each ask's own answer, in the order they were asked, comes before everything else (§18.3); other agents' asks are theirs ([ADR-0034](../../adr/0034-mention-an-agent.md)). The previous run's own comments, its questions included, are not new comments.

**The run of a mentioned agent** ([ADR-0034](../../adr/0034-mention-an-agent.md)): a run that took pending wakes of mentions (§14.3) has the `# Request` section after the task's description. The assignment builder loads the run's `task_wake` rows and the comments they name. With one comment: `{author} mentioned you in this comment on the task and asked for your help.`, the assignee sentence, and the comment as a quote. With several: `You were mentioned in these comments on the task and asked for your help.`, `Do what the comments ask`, and each comment under `**{author}** · {time}`, oldest first. Without an assignee: `The task has no assignee. Do what the comment asks, and nothing more: the rest of the task is not yours.` Its workspace may be empty or read-only (§14.3); the `# Repositories` and "How to work" lines for those cases apply as they are. When it resumes an earlier session of the same agent on the task, its resume prompt starts with the same `# Request` section, then the answers, the repositories, the new comments and the attachments as above, and ends with `Do what the request asks. Finish with a short summary as before.` instead of the usual line.

**The colleagues** ([ADR-0035](../../adr/0035-agent-usage-policy.md)) in the `create_task` line are the company's live agents, other than the run's agent, that the run's agent's owner may give work (§14.2), by name: the owner's current role decides, and an owner who is no longer a member may give work to `COMPANY` agents only. `create_task` refuses the others (§15.6).

### 15.6 Platform MCP tools

The runner host offers every run six platform tools over MCP: `list_repositories`, `request_repository`, `create_task`, `upload_attachment` ([ADR-0021](../../adr/0021-agent-attachments.md)), `ask_human` and `finish_run` (§18). They are served by the host, not by the sandbox, so the agent cannot change them.

**Transport**
- MCP Streamable HTTP (spec 2025-06-18 or later) at `http://<proxy host>:<port>/<mcp secret>/mcp`, on the same listener as the git proxy (§15.4 step 2). The MCP secret is 32 random bytes (base64url), one per run, compared in constant time; the route is removed when the run ends.
- `POST` takes one JSON-RPC object: `initialize` (echoes a supported `protocolVersion`, else `2025-06-18`), `notifications/*` (202, empty body), `ping`, `tools/list`, `tools/call`. Answers are `application/json`, with no `Mcp-Session-Id`. Other methods are 405 with `Allow: POST`. Other paths and unknown secrets are 404. A request with an `Origin` header is 403. Invalid JSON is 400 with JSON-RPC error -32700, a batch 400 with -32600; an unknown method is -32601 and an unknown tool -32602. Bodies are limited to 1 MiB.
- The host passes the endpoint in ACP `session/new` and `session/load`: `mcpServers: [{"type": "http", "name": "tinfirm", "url": "<endpoint>", "headers": []}]`. The URL is new for every run, so a resumed session gets the current one.
- **Fallback:** if the agent's `initialize` result does not set `agentCapabilities.mcpCapabilities.http`, or the listener is not running (network `none`, or it failed to start), the host passes `mcpServers: []` and logs a `LOG` warning. The run goes on without the tools; the prompt tells the agent to name the repository it needs in its summary instead. A stdio relay in the runner images can come later.

**`list_repositories`**
- Input: none (`{"type": "object", "properties": {}}`).
- Result: a text block with this JSON, also as `structuredContent`: `{"repositories": [{"name", "path", "repoUrl", "branch", "defaultBranch"}], "requestable": [{"name", "repoUrl", "pendingRequest"}]}`. `path` is `/workspace/<dir>`. The host builds it from `run.assign`; `pendingRequest` is also true for repositories this run requested with a `PENDING` answer.

**`request_repository`**
- Input: `{"name": string, "reason": string}`, both required. `name` is a repository name from `list_repositories`; `reason` says why the task needs it.
- The host:
  1. trims both; an empty value, or a name over 100 characters, is an error result, and nothing is sent. A longer reason is cut to 2000 characters;
  2. answers a name that matches a repository of the run (by name or `dir`, ignoring case) with an error result that gives its path, and sends nothing;
  3. sets `componentId` when the name matches an entry of `requestableRepositories`, ignoring case;
  4. without a server connection, answers with an error result: Tinfirm is not reachable, name the repository in the summary;
  5. sends `run.repository_request` with a new `requestId` and waits up to 30 s for `run.repository_request.ack`. After a reconnect within that time it sends the same frame again. When the time is up, the result is an error: Tinfirm did not answer, name the repository in the summary.
- `PENDING` is a normal result, `REJECTED` an error result (`isError: true`); both carry the ack's `message` as text.
- The tool's description and the MCP `instructions` say what the prompt says (§15.5, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)): after a pending answer, stop without a summary; the request is on the task, and Tinfirm wakes the agent with the decision.
- The tool calls appear in the transcript as ACP tool calls, like any other tool.

**`create_task`** ([ADR-0019](../../adr/0019-agent-created-tasks.md))
- Input: `{"title": string, "description"?: string, "repositories"?: [string], "assignee"?: string}`; only `title` is required. `repositories` are component names of the project; omitted means the repositories of the run's task that the assignee may access, an empty array none. `assignee` is the name of an agent of the company; omitted means the run's agent.
- The host trims the values; an empty title, a value of the wrong type, a repository name or assignee over 100 characters, a blank assignee or more than 20 names is an error result, and nothing is sent. A name that matches a repository of the run by name or `dir` (ignoring case) becomes its name; duplicates (ignoring case) count once. It redacts the texts and cuts the title to 200 and the description to 50 000 characters.
- Then it sends `run.task_create` and waits for `run.task_create.ack` as in steps 4 and 5 of `request_repository`. `CREATED` is a normal result, `REJECTED` an error result; both carry the ack's `message`.
- The server's checks, what it stores and the messages are in ADR-0019. The new task is in `BACKLOG`, assigned to the run's agent or the named agent, with `Task.originTaskId` set; a `SYSTEM` comment on the run's task links it. Nothing wakes.
- When the project's work source is a tracker, `JIRA` or `GITHUB` (§14.1, [ADR-0029](../../adr/0029-work-source-per-project.md)), the answer is `REJECTED` with the code `PROJECT_USES_TRACKER` and the message ``The tasks of this project come from {tracker}, so no task was created. Put this work into `nextSteps` when you call `finish_run`; a person creates the issue in {tracker}.``, with the source's display name (Jira or GitHub) as `{tracker}`, and nothing is stored. The check comes after ADR-0019's step 2, so a repeated frame still gets its first answer, and before the request is validated.
- When a poll gives the new task's key to a task from an issue of another project at the same moment (§14.1), the answer is `REJECTED` with the code `TASK_KEY_TAKEN` and the message ``The key {KEY} was taken by a task from an issue at the same moment; call `create_task` again.``, and nothing is stored. The insert skips the row instead of failing (`ON CONFLICT ON CONSTRAINT task_company_key_uq DO NOTHING`), so the run's transaction still renews the lease and sends the ack.
- The run's agent acts with the rights of its owner ([ADR-0035](../../adr/0035-agent-usage-policy.md)): a named `assignee` other than itself must be an agent its owner may give work (§14.2), judged by the owner's current role; an owner who is no longer a member may name `COMPANY` agents only. Otherwise, after the agent is found by name, the answer is `REJECTED` with the code `AGENT_NOT_USABLE` and the message ``The agent `{name}` is usable by its owner only, so you cannot assign it a task. Leave out `assignee` to keep the task yourself, or name another agent. Nothing was created.``, and nothing is stored. The list of an `UNKNOWN_AGENT` answer names only the agents the owner may give work. The code is a free string of `run.task_create.ack`; the protocol does not change.

**`ask_human`** (§18.2)
- Input: `{"question": string, "options"?: [string]}`; only `question` is required. Without `options`, the person answers in their own words.
- The host trims the values; an empty question, a value of the wrong type, an empty option, an option over 100 characters or more than 10 options (after dropping duplicates, ignoring case) is an error result, and nothing is sent. It redacts the texts and cuts the question to 2000 characters.
- Then it sends `run.ask` and waits for `run.ask.ack` as in steps 4 and 5 of `request_repository`. `PENDING` is a normal result, `REJECTED` an error result; both carry the ack's `message`. When the host cannot reach the server, gets no answer in time or the run is ending, nothing is on the task, so the error result tells the agent to write the question in its summary.
- The tool's description and the MCP `instructions` say what the prompt says (§15.5, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)): one clear question per call, every question the agent needs, then a stop without a summary; the questions are on the task, and Tinfirm wakes the agent with all the answers at once. `finish_run`'s description does not change.

**`finish_run`** (§18.4)
- Input: `{"outcome": "COMPLETED" | "BLOCKED", "summary": string, "nextSteps"?: [string], "openQuestions"?: [string]}`.
- The host trims the values; another outcome, an empty summary, a list of the wrong type, more than 20 next steps or more than 10 open questions is an error result, and nothing is recorded. Empty entries are dropped. It redacts the texts and cuts the summary to 20 000 characters and each entry to 2000.
- It sends nothing: it keeps the call, a later call replaces it, and `run.finished.result` carries the last one when the run ends (§15.4 step 8). The result tells the agent to stop.

### 15.7 Capacity settings (`host.config`)

The server sends the desired values of §14.8 to the host. The host applies them without a restart, and confirms what it uses.

**Messages and order**
1. `hello` (host): the ceilings, in `maxConcurrentRuns` and in each credential's `poolSize`.
2. `welcome` (server), then at once `host.config`: the host's current `config_version` and every desired value. Other frames of the server, such as the `run.cancel` frames of §14.4, come after it.
3. `host.config.applied` (host): the answer. The server assigns runs to the host only after this first answer on the connection.
4. After every change of a desired value, the server sends `host.config` again. It carries the full state and the new `version`. The host answers every one it applies.
5. When the host starts to drain (§15.4 step 13), it sends `host.config.applied` unprompted, with `draining: true` and `maxConcurrentRuns: 0`. It does so only after it has applied a `host.config` on the current connection. A drain that starts earlier shows in the answer to the first one.

**Versions and idempotency**
- `host.config` is the full desired state, never a delta. A credential without an entry has no desired value. Applying the same message twice changes nothing, and the host answers both.
- `version` increases by 1 with every change of a desired value of the host or of one of its credentials. It is 0 when none was ever set.
- On one connection, the host ignores a `host.config` whose `version` is lower than the last one it applied: it neither applies nor answers it. Two frames of the server can overtake each other: a change sent right after a commit can pass the `host.config` that follows `welcome`.
- The host forgets the last version at every new connection, because the server sends the current state after each `welcome`.
- The server keeps the highest version the host confirmed (`applied_config_version`, §14.8). A host that is offline when a value changes gets the stored state at its next connection; nothing is queued.

**Applying (the host)**
- The effective values are `min(desired, ceiling)`, or the ceiling without a desired value (`tinfirm_protocol::effective_capacity`). The host's slots are 0 while it drains. The host ignores labels it does not know.
- The values apply at once to admission (§15.4 step 1):
  - more slots can take the next `run.assign`;
  - fewer slots never cancel or pause a running run. The host rejects new runs (`NO_FREE_SLOT`, `POOL_FULL`) until fewer run.
- The desired values live only in the host's memory. Until the first `host.config` of a process, the host uses its ceilings; the server assigns nothing before then anyway. After a reconnect it keeps the last values until the new `host.config` arrives.
- The ceilings are the values of `hello`, read when `run` starts: `--max-concurrent-runs`, and the pools of the credential store. A later `credential add` or `remove` does not change them. The next start reports the new ones (§15.2).
- `host.config.applied` lists every credential of `hello`, each once, with its effective pool and its ceiling.

## 16. v0 shortcuts (accepted risks, removed in the hardening step)

| Shortcut | Risk | Removed by |
|---|---|---|
| Sandbox network is open; there is no egress proxy | A prompt-injected agent can send data out | Egress proxy with an allowlist |
| API keys are passed into the sandbox env, not through the gateway. A company model credential ([ADR-0027](../../adr/0027-company-model-credentials.md)) passes the gateway's key the same way | Key exposure to the agent; no hard USD budget for a host credential. A model credential's key is bounded by the budget the gateway sets on it, but the agent can still spend it from outside the run | Model gateway behind the sandbox bridge, the key never in the sandbox |
| The host attaches to the container's stdio through Docker; there is no bridge | A host process restart loses the runs | Sandbox bridge |
| Tool permissions are auto-approved inside the sandbox | The agent can run anything in its container | Policy-based approvals |
| The Codex Plus/Pro `auth.json` is mounted read-write, with pool 1 | The agent can read the refresh token | `chatgptAuthTokens` through the app-server adapter |
| The git token travels to the runner host in `run.assign` | Exposure on the host (never in the sandbox) | GitHub App with 1-hour scoped tokens; on GitLab, project access tokens per job ([ADR-0023](../../adr/0023-code-host-interface-gitlab.md)) |
| One server replica for the runner connections and SSE | No HA | LISTEN/NOTIFY fan-out |
| The runner host serves the platform MCP tools over plain HTTP on the git proxy listener, guarded only by the secret in the URL | A process that reaches the listener and learns a run's URL can call every tool in that run's name: list and request repositories, create backlog tasks, upload files to the task, ask people questions and set the run's result (a member still decides) | Platform MCP through the sandbox bridge |

## 17. Trackers

Work arrives from the company's issue tracker, and results go back there: the issue is the brief, the tracker owns the workflow, and Tinfirm owns the runs. The decisions are [ADR-0024](../../adr/0024-jira-tracker-connector.md) (Jira Data Center), [ADR-0030](../../adr/0030-github-issues-tracker.md) (GitHub Issues, and one interface for every kind) and [ADR-0031](../../adr/0031-rule-for-new-issues.md) (the trigger for new issues, and rule conditions). The tables are in `V024__tracker.sql`, `V029__github_issues.sql` and `V030__rule_created_trigger.sql`, the HTTP contract is tag Trackers in `api/openapi.yaml`, and the operator guides are [operations/jira.md](operations/jira.md) and [operations/github-issues.md](operations/github-issues.md). It feeds the projects whose work source is `JIRA` or `GITHUB` ([ADR-0029](../../adr/0029-work-source-per-project.md), §14.1).

The module `tracker` uses the work module (`ExternalWork`, `TaskBoard`, and the events `CommentPosted` and `TaskStatusChanged`), the agent, project, vault and audit modules, and implements the project module's port `TrackerRuleUsage`. No module depends on it. Everything that depends on the kind of tracker is behind the interface `IssueTracker` in `tracker.internal`, which `IssueTrackers` picks by the connection's kind: who a token belongs to, the search for changed issues and the next window, reading comments, writing comments, links and status changes, the markup, mentions, the issue's URL and the form of a rule's scope. The poller, the write-back, the rules and the service contain nothing else of a kind.

**Kinds**

| | `JIRA_DC` | `GITHUB` |
|---|---|---|
| Tracker | Jira Data Center or Server | GitHub Issues on github.com or GitHub Enterprise Server |
| API and `baseUrl` | REST API v2; the address Jira is opened at, without `/rest` | REST API; `https://api.github.com`, or `https://<host>/api/v3` on GitHub Enterprise Server |
| Token | A service account's PAT, `Authorization: Bearer` | A personal access token, fine-grained (Issues read and write, Metadata read) or classic (`repo`); `Authorization: Bearer`, `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28` |
| Work source of the rules' projects | `JIRA` | `GITHUB` |
| `Task.externalSource` | `JIRA` | `GITHUB` |
| Rule `scope` | A Jira project key, `PROJ` | A repository, `owner/repo` |
| Rule conditions (§17.2) | Statuses, labels, issue types | Labels, and the issue types of the repository's organisation; no statuses |
| Closed issues | No default: what is closed depends on the workflow | Match no rule |
| Issue key | `PROJ-123`, also the task's key when it can be (§17.4) | `owner/repo#57`, never a task key |
| Status mapping (`transitions`) | Transition names | Labels |
| `closeOnDone` | No | Yes |
| Agent account | Username and the PAT of its service account | Login, and optionally its token |
| Markup | Wiki markup, converted (§17.6) | Markdown, as it is |
| Mention | `[~username]` | `@login` |
| Pull requests on the issue | In the end-of-run comment, and as remote links | In the end-of-run comment; the pull request's body names the issue (§14.4) |

### 17.1 Data

Every table is a tenant table (§7) with composite `(id, company_id)` foreign keys.

| Table | Contents | Keys |
|---|---|---|
| `tracker_connection` | `kind` (`JIRA_DC` or `GITHUB`, never changed), `name`, `base_url`, `secret_id` (the token of the account that polls and writes as Tinfirm), `poll_enabled`, `poll_interval_seconds`, `transitions` (JSON: task status to transition name or label), `close_on_done` (GitHub only), `last_polled_at`, `last_poll_error`, and the poll state `poll_since`, `poll_offset`, `poll_lease_until` | Name unique per company. Deleting the secret sets `secret_id` NULL. |
| `tracker_agent_account` | `agent_id`, `account_name` (the Jira username or GitHub login of the agent), `secret_id` (its token; optional on a GitHub connection) | One per (connection, agent) and per (connection, account name ignoring case) |
| `tracker_rule` | `position`, `scope` (a Jira project key or `owner/repo`, 1 to 140 characters without whitespace), `trigger`, `value`, `conditions` (a JSON object `{"statuses": [...], "labels": [...], "types": [...]}` without empty lists; `{}` is no condition), `agent_id`, `project_id`, `enabled` | Position unique per connection |
| `tracker_issue` | The link: `issue_id`, `issue_key` (at most 200 characters), `issue_url`, `task_id`, `last_updated_at` (the issue's `updated` when last handled), `last_comment_id_seen` | Unique (connection, issue) and unique task |
| `tracker_sync` | The tracker's ids of the comments the connector wrote (`remote_comment_id`), with the Tinfirm comment id | (connection, issue, remote comment id) |
| `tracker_remote_link` | The pull request URLs added to a Jira issue as remote links | (connection, issue, URL) |
| `tracker_outbox` | What the write-back still has to send: a `COMMENT` (`comment_id`) or a `STATUS` change, with `attempts` and `next_attempt_at` | Due per connection, oldest first |

Deleting a connection deletes its rows in every table above. Tasks keep their link fields.

The work module adds:
- `task.external_source` (`JIRA` or `GITHUB`), `task.external_key` (at most 200 characters), `task.external_url`: all three NULL, or source and key set; unique per (company, source, key). The API shows them as `Task.externalSource`, `externalKey` and `externalUrl`.
- `CommentAuthorKind.EXTERNAL` with `task_comment.external_author` (1 to 200 characters): an EXTERNAL comment has no `author_id`. The API shows it as `authorType` `EXTERNAL`, with the author's name in `authorDisplay`: the Jira display name, or the GitHub login.

Two `SECURITY DEFINER` lookups return only ids and company ids across companies, like those of V012: `tracker_poll_due` (connections whose poll is due) and `tracker_outbox_due` (connections with a due delivery).

### 17.2 Connections, agent accounts and rules

**Connection**
- `kind` is `JIRA_DC` or `GITHUB` (the table above), set at creation; `PATCH` cannot change it.
- `name`: 1 to 100 characters after trimming, unique in the company, else `409 DUPLICATE_TRACKER_NAME`.
- `baseUrl`: an https URL with a host and an optional path, without user info, query or fragment, at most 500 characters. A trailing `/` is removed. http only when `TINFIRM_TRACKER_ALLOW_HTTP` is `true`.
- `secretId`: a secret of the company, else `400 VALIDATION_FAILED`.
- `pollIntervalSeconds`: 30 to 86 400, default 60. `pollEnabled` (default true) turns the scheduled poll on; `POST .../poll` polls also when it is off.
- `transitions`: `inProgress`, `inReview` and `done`, each a name or none: a Jira transition name of at most 100 characters, or a GitHub label of at most 50 characters (GitHub's limit). `PATCH` replaces the whole mapping when it is present.
- `closeOnDone` (default false): close the GitHub issue when its task moves to `DONE`. True on a `JIRA_DC` connection is `400 VALIDATION_FAILED`.
- `POST .../test` asks who the connection's token belongs to, Jira with `GET /rest/api/2/myself` and GitHub with `GET /user`, and answers `{ok, accountName}` (the username or login) or `{ok: false, error}`.

**Agent accounts** (`PUT .../agent-accounts` replaces the list, at most 100)
- One per agent, a live agent of the company. The account name (a Jira username or a GitHub login) has no whitespace, brackets, `|` or `~` (it is used in `[~name]`), 1 to 255 characters, and is unique on the connection ignoring case. Its secret is a secret of the company; on a GitHub connection it is optional, and without it the connection's token writes the agent's comments. Otherwise `400 VALIDATION_FAILED`.
- When the new list leaves an `ASSIGNEE` or `MENTION` rule without its agent's account: `409 TRACKER_ACCOUNT_IN_USE`, naming the rules.

**Rules** (`PUT .../rules` replaces the list, at most 50; the list order is `position`, from 0)

| Trigger | An issue without a task matches, `JIRA_DC` | An issue without a task matches, `GITHUB` | `value` |
|---|---|---|---|
| `ASSIGNEE` | when its assignee's username is the agent's account | when one of its assignees is the agent's login | none |
| `LABEL` | when it has the label `value` | when it has the label `value` | the label, 1 to 255 characters |
| `STATUS` | when its status is `value` | refused: `Rule N: GitHub issues have no status; use LABEL` | the status name, 1 to 255 characters |
| `MENTION` | when a comment created since the poll's lower bound, not by an account Tinfirm writes with, contains `[~account]` of the agent | when such a comment contains `@login` of the agent as a whole word | none |
| `CREATED` | when it was created (`created`) at or after the poll's lower bound, whatever its assignee, labels or status | the same, by `created_at` | none |

An issue without a usable creation time counts as created when it was last updated. The first poll's lower bound is the connection's creation minus the overlap (§17.3), so `CREATED` never matches an issue created before that, and the bound stays while polling is off, so an issue created meanwhile is caught up. `CREATED` normally matches in the first poll after the issue was created: an issue that fails to become a task then (§17.3 step 4) does not match `CREATED` again when it changes later.

- Names compare ignoring case. A rule applies only to issues of its `scope`: on `JIRA_DC` a Jira project key (upper-case letters, digits and `_`, starting with a letter, at most 50), on `GITHUB` a repository `owner/repo` (`^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?/[A-Za-z0-9._-]+$`, at most 140 characters, compared ignoring case).
- The agent is a live agent and the project a project of the company whose work source is the connection's tracker: `JIRA` for `JIRA_DC`, `GITHUB` for `GITHUB` (§14.1, [ADR-0029](../../adr/0029-work-source-per-project.md)). A project with another source fails with `Rule N: project X uses Tinfirm tasks; set its work source to Jira first` (or `takes its work from GitHub`, `to GitHub`, by the sources). `ASSIGNEE` and `MENTION` need the agent to have an account on the connection; `LABEL`, `STATUS` and `CREATED` do not. `LABEL` and `STATUS` need a `value`; `ASSIGNEE`, `MENTION` and `CREATED` take none. Any failure is `400 INVALID_TRACKER_RULE` with a detail that names the rule (`Rule 2: ...`).
- Both lists are replaced under the connection's row lock, so a concurrent change cannot leave a rule without its account. The projects the rules target are read under a key-share lock (`ProjectCatalog.lockProjects`), and a project's switch away from its tracker locks its row before it asks for rules (`TrackerRuleUsage`), so a rule and a switch cannot pass each other.

**Conditions** (`conditions`, optional): what the issue must also meet at the moment the trigger matches. They are checked after the trigger.

| Condition | The issue matches when | Kinds |
|---|---|---|
| `statuses` | its status is one of them | `JIRA_DC` only: on `GITHUB`, `Rule N: GitHub issues have no status; conditions on status are for Jira` |
| `labels` | it has every one of them | Both |
| `types` | its issue type is one of them: Jira's `issuetype`, or the GitHub issue type of the repository's organisation; an issue without a type never matches | Both |

- Names compare ignoring case. An empty or absent list is no condition. Each list holds at most 10 entries of 1 to 255 characters after trimming; duplicates, ignoring case, are dropped and the first spelling is stored. A label that repeats the `LABEL` trigger's value is fine.
- A `CREATED` rule with conditions matches only an issue that meets them when it is created. To start work when a label or status is set later, use the `LABEL` or `STATUS` trigger, with conditions for the rest: "has the label `ai-agent`, only when in status `Ready for AI`".
- A closed GitHub issue (`state` `closed`) matches no rule, whatever its trigger and conditions (§17.4).

**Deleted agents.** Deleting an agent removes its rules (the others keep their order) and its accounts in the deleting transaction, audited as `tracker.rules.changed` and `tracker.agent_accounts.changed` with `cause` `agent.deleted`.

**Agents usable by their owner only** ([ADR-0035](../../adr/0035-agent-usage-policy.md), §14.2). A rule or an agent account may name any live agent of the company, whatever its usage: OWNERs and ADMINs set them up, and they may use every agent. The tasks the rules create, by the `MENTION` rule too, are SYSTEM actions and are not gated. A mention in a comment on an issue that already has a task is a person's mention, and starts only agents of the whole company (§17.4).

### 17.3 Polling

Replicas with the `worker` role check every `tinfirm.tracker.poll-tick` (default 15 s) for due connections: polling on, a secret, no poll in progress, and `last_polled_at` at least `pollIntervalSeconds` ago. A poll of one connection:

1. **Claim.** Lock the connection with `SKIP LOCKED`, set `poll_lease_until` to now + 10 min, read the enabled rules and the agent accounts, and reveal the connection's token once (`secret.accessed`, purpose `tracker-poll:<trackerId>`). Without enabled rules nothing is revealed or called, and the lower bound moves to now minus the overlap: rules apply to issues updated after they exist.
2. **Account.** Jira: `GET /rest/api/2/myself`, whose name joins the accounts Tinfirm writes with and whose time zone is the zone of the JQL dates (UTC when Jira sends none). GitHub: `GET /user`, whose login joins them.
3. **Search** the scopes of the enabled rules, oldest change first, at most 200 issues per poll:
   - Jira: `GET /rest/api/2/search` with `project in ("A", "B") AND updated >= "yyyy/MM/dd HH:mm" ORDER BY updated ASC, key ASC`, `startAt` from `poll_offset`, pages of 50, and the fields `summary,description,status,issuetype,assignee,labels,created,updated,comment`. When an issue has more comments than the search returned, its newest 50 are read with `GET /rest/api/2/issue/{key}/comment`.
   - GitHub: per repository (each once, ignoring case), `GET /repos/{owner}/{repo}/issues?state=all&since=<UTC, seconds>&sort=updated&direction=asc&per_page=100&page=N`, page by page. Each repository gets an equal part of what is left of the 200, at least one entry. Pull requests, which GitHub lists among the issues, are skipped but count. The issue's id is GitHub's `id`, its key `owner/repo#number` with the names GitHub reports for the repository, its URL `html_url`; title, body (Markdown), labels, assignees (logins), `created_at`, `updated_at`, `state`, the issue type (`type.name`, the organisation's issue type, or none) and the number of comments come from the list. An issue with comments that a poll handles has them read with `GET /repos/{owner}/{repo}/issues/{number}/comments?per_page=100`, with `since` its last `updated` when it is linked: the newest 50, from the last pages.
4. **Handle** each issue in its own transaction (§17.4); a linked issue whose `updated` and key did not change is skipped before its comments are read, and so is an issue without a task that no enabled rule's scope names or that is closed (GitHub). A failure rolls back only that issue and is listed in `last_poll_error` as `{key}: {reason}`, e.g. `PROJ-1: another task of the company has the key PROJ-1, so the issue cannot become a task with it`. The window moves on, so a failed issue is tried again when it changes. A failure to read a GitHub issue's comments ends the poll instead, so the next one reads the issue again.
5. **Record** `last_polled_at` (the poll's start), `last_poll_error` (null after a clean poll, at most 2000 characters) and the next window, release the lease, then deliver what the write-back has due for the connection (§17.5).

**Window.** The first poll starts at the connection's creation minus the overlap (`tinfirm.tracker.poll-overlap`, default 2 min). The bound never moves back, and any failure keeps the window, so the next poll repeats it.
- Jira: after a poll that reached the end of its search, the lower bound is the newest `updated` it saw minus the overlap. After a poll that stopped at 200 issues or at a failed page, it is the last issue's `updated` minus the overlap; when that is not a later minute than the current bound, the bound stays and `poll_offset` grows by the issues handled.
- GitHub (`since` has second precision and no time zone; `poll_offset` stays 0): the lower bound is the earliest over the repositories of the newest `updated_at` seen (the poll's start when none was seen; when the limit cut the repository, the last one handled) minus the overlap. When the limit cut a repository and that bound would not move, the bound moves to the earliest point a cut repository reached, minus the overlap when that still moves it, else without it, so that a bulk change of more issues than the limit cannot hold the polling in place.

A failed call is recorded and logged; the next poll is due after the interval as usual. On GitHub a 403 or 429 with `x-ratelimit-remaining: 0` or a `retry-after` (the rate limit) is such a failure: the poll ends, and `last_poll_error` says so with the time GitHub names. A 401, another 403 or a 404 is a failure that will not go away by itself.

### 17.4 Issues and tasks

**An issue without a link** that matches a rule (§17.2: the first enabled rule whose trigger matches and whose conditions hold) becomes a task of the rule's project. A closed GitHub issue matches no rule; Jira has no such default, since what a closed status is depends on the workflow. The task is:
- status `TODO`, assigned to the rule's agent, with the repositories of the project the agent may access (§14.7; more than 20 fails the issue);
- the project's next number, and as `Task.key` the issue key when it has at most 32 characters and the form `^[A-Z][A-Z0-9_]*-[0-9]+$` (e.g. `PROJ-123`), else a key from the project's prefix ([ADR-0029](../../adr/0029-work-source-per-project.md)). A GitHub key, `owner/repo#57`, never has that form, so a task from a GitHub issue always gets a key from the prefix, e.g. `WEB-61`. A task with the issue's key has the branch `agent/PROJ-123` and merge requests titled `PROJ-123: {title}`. When another task of the company has that key (`task_company_key_uq`), for example one of a project with Tinfirm tasks whose prefix is `PROJ`, the issue fails with a reason that names the key (§17.3 step 4). Keys of existing tasks are never changed: branches derive from them;
- title: the summary or title, cut to 200 characters; description: the description in Markdown (§17.6), cut to 50 000 characters with the marker `[Cut by Tinfirm: the original is longer than 50 000 characters.]`;
- `externalSource` `JIRA` or `GITHUB`, `externalKey` the issue key, `externalUrl` `{baseUrl}/browse/{key}` (Jira) or the issue's `html_url` (GitHub); `createdBy` is `Jira` or `GitHub`;
- the newest 50 comments of people (§17.5, loop prevention) as EXTERNAL comments, without a wake of their own. They predate the task, so they mention nobody;
- audited as `task.created` (actor SYSTEM) and `tracker.issue.linked`; the assignment wakes the agent with `ASSIGNMENT` (§14.3).

If a task of the company is already linked to the issue's key (`externalKey`), the connector links it again when no connection links it (a connection was deleted and created again), and skips the issue otherwise.

**A linked issue** whose `updated` or key changed since it was last handled:
- its summary and description replace the task's title and description when they differ (audited as `task.updated` with `cause` `external`): the issue is the brief, so an edit in Tinfirm is overwritten by the next change of the issue;
- its comments of people with an id above `last_comment_id_seen` become EXTERNAL comments, oldest first, at most the newest 50. Each carries the agents it mentions by their account on the connection: the agents of `accountByAgent` whose account `IssueTracker.mentions` finds in its raw body (`[~account]`, `@login`), as the `MENTION` rule matches them ([ADR-0034](../../adr/0034-mention-an-agent.md)); the work module adds the `@handle`s of its Markdown body. Jira's `[~name]` becomes `@name` in the Markdown, which may also be a handle; the union makes that harmless. A comment that mentions agents answers only their open asks, one that mentions nobody every open ask of the task (§18.3); then they wake as a comment does (§14.3): each agent that asked with `ASK`, each other mentioned agent with `MENTION`, and the assignee once, with `ASK` when one of its asks was answered, else with `COMMENT`. The person on the issue is not a member, so a mentioned agent usable by its owner only gets no `MENTION` wake, and a `SYSTEM` comment on the task, which goes to the issue, says `{agent name} was not started (mention): it is usable by its owner only, and a mention on {Jira or GitHub} cannot be matched to a member.` ([ADR-0035](../../adr/0035-agent-usage-policy.md));
- `last_updated_at`, the key and `last_comment_id_seen` (the highest id of all its comments read) are stored.

A linked issue never creates a second task. Reassigning it in the tracker does not change the task's assignee, and comments on a `DONE` or `CANCELLED` task wake nobody.

### 17.5 Write-back

The work module publishes `CommentPosted` for every AGENT and SYSTEM comment and for a USER comment that answered an ask (§18.3), and `TaskStatusChanged` for every status change, inside the changing transaction. The tracker listens after the commit and never fails that transaction:
1. On one background thread, in the order the events came, it queues a `tracker_outbox` row when the task is linked; a status change only when the connection maps the new status, or the status is `DONE` and the connection closes issues (`closeOnDone`).
2. On another background thread it delivers the connection's due rows: claim at most 20 for 5 minutes, only the oldest row of each task, so a task's writes reach the issue in order; reveal each token once per round (`secret.accessed`, purpose `tracker-write:<trackerId>`); call the tracker outside any transaction; record the result. It repeats while rounds make progress.

| What | Written as | Jira | GitHub |
|---|---|---|---|
| AGENT comment | The agent's account; without an account (or its secret), the connection's account with the agent's name in a bold label in front (`*{agent name} (Tinfirm agent):*`, `**{agent name} (Tinfirm agent):**`) | `POST /rest/api/2/issue/{key}/comment`, body in wiki markup (§17.6) | `POST /repos/{owner}/{repo}/issues/{number}/comments`, body in Markdown as it is |
| The AGENT comment that ends a run (`runId` set) | As above, plus a `Pull requests:` line `- {title}: {url}` for each pull request of the task that the comment does not name | Each pull request URL also becomes a remote link once: `POST .../remotelink` with `globalId` `tinfirm:{url}` and the title `Pull request #{number} ({component})` | No links; GitHub links the URLs, and the pull request names the issue (§14.4) |
| SYSTEM comment | The connection's account, starting with the bold label `Tinfirm:` | `*Tinfirm:*`, `POST .../comment` | `**Tinfirm:**`, `POST .../comments` |
| USER comment that answered an ask (§18.3) | The connection's account, with `{member name} (via Tinfirm):` as a bold label in front | `POST .../comment` | `POST .../comments` |
| Status `IN_PROGRESS`, `IN_REVIEW` or `DONE` with a mapped name | Jira: the assignee's account, else the connection's. GitHub: the connection's account | `GET .../transitions`, then `POST .../transitions` with the id of the transition whose name matches, ignoring case | `POST .../issues/{number}/labels` with `{"labels": [name]}`, then `DELETE .../labels/{other}` for the mapping's labels of the other statuses (a 404 is fine) |
| Status `DONE` with `closeOnDone` (GitHub only) | The connection's account | — | After the labels, `PATCH .../issues/{number}` with `{"state": "closed", "state_reason": "completed"}`. A task that leaves `DONE` does not reopen the issue. |

- A delivered comment's id in the tracker is stored in `tracker_sync` (`remote_comment_id`); a delivered Jira link in `tracker_remote_link`.
- A delivery is dropped, with a log line naming the issue, when the task is no longer linked, the comment is gone, the status is no longer mapped, there is no token, or the Jira issue's status does not offer the transition.
- A failure that may work later (no answer, a 5xx, a 429, or GitHub's exhausted rate limit) is retried after 1 min, doubling up to 1 h, at most 10 attempts (`tinfirm.tracker.max-delivery-attempts`); the poll pass delivers due rows of every connection. Any other failure is logged with the issue key and given up. A failed remote link is logged and not retried.
- **Loop prevention.** A poll never imports a comment whose id is in `tracker_sync`, nor one by the connection's account or any agent account of the connection.
- **Asks (§18).** An agent's question is an AGENT comment without `runId`, so it reaches the issue as a plain comment of the agent that ends with `Reply to this comment to answer.` A run that ends on its questions writes no end-of-run comment (§14.4, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)), so the question reaches the issue once, and nothing else of that run follows it. The open questions of a `BLOCKED` result are in the end-of-run comment. A reply on the issue comes back as an EXTERNAL comment and answers all of them at once, since a reply cannot name one question (§17.4, §18.3), unless it mentions agents: then it answers only their questions ([ADR-0034](../../adr/0034-mention-an-agent.md)). An answer given in Tinfirm is a USER comment, through a question's answer form or a comment that the member marked as answering the open questions (§18.3): because it answered an ask, it is written to the issue as the connection's account with the member's display name in front, so the issue holds the question and its answer; an answer through a question's answer form starts with that question quoted, one comment per answer. Other member comments, plain comments while questions are open included, stay in Tinfirm. The connection's own comments are never imported, so the answer does not come back.

### 17.6 Markup

Tasks and comments are Markdown. GitHub Issues speaks Markdown too: texts go both ways as they are, and Tinfirm's labels are bold Markdown with the characters that would change them escaped. Jira Data Center reads and writes wiki markup; both conversions cover the same subset. Inside code nothing is converted, URLs keep their characters, and what is not in the subset stays as it is.

| Jira wiki markup | Markdown |
|---|---|
| `h1.` to `h6.` | `#` to `######` |
| `*bold*`, `_italic_` | `**bold**`, `*italic*` |
| `{{mono}}` | `` `mono` `` |
| `{code:lang}...{code}`, `{noformat}...{noformat}` | Fenced block with the language; without one, `{noformat}` |
| `*`, `**`, `#`, `##`, `*#`, `-` lists | `-` and `1.` lists, indented by depth |
| `[text\|url]`, `[url]`, `[~user]` | `[text](url)`, `<url>`, `@user` |
| `bq.`, `{quote}...{quote}` | `>` lines |
| `\|\|h\|\|h\|\|`, `\|c\|c\|` | A Markdown table |
| `----` | `---` |

From Markdown to wiki markup, `~~strike~~` becomes `-strike-`, images become `!url!`, an unescaped `{` or `[` in text is escaped, and inside `{{...}}` the characters that would start an effect are escaped.

### 17.7 Configuration

| Variable | Default |
|---|---|
| `TINFIRM_TRACKER_ALLOW_HTTP` (accept http base URLs; the token travels in clear text) | `false` |

Further settings under `tinfirm.tracker` have no environment variable: `poll-tick` (`PT15S`), `poll-overlap` (`PT2M`), `poll-lease` (`PT10M`), `page-size` (50, Jira's search pages; GitHub's pages hold 100), `max-issues-per-poll` (200, shared by the repositories of a GitHub connection), `max-comments-per-issue` (50), `max-delivery-attempts` (10) and `timeout` (`PT30S`, per request).

## 18. Asks and the waiting-on state

An agent asks a person with the platform tool `ask_human`, reports its result with `finish_run`, and every task says who it waits on. The decision is [ADR-0028](../../adr/0028-asks-and-waiting-on.md), [ADR-0033](../../adr/0033-one-answer-per-ask.md) for answers to one ask and for comments, which answer only on request, and [ADR-0034](../../adr/0034-mention-an-agent.md) for the agent an answer wakes and for replies that mention agents. The tables are in `V026__asks.sql`, the HTTP contract is tag Asks and the fields `Task.waitingOn`, `waitingReason`, `waitingSince` and `Run.result` in `api/openapi.yaml`, and the messages are `run.ask`, `run.ask.ack` and `run.finished.result` in `protocol/runner-v1.schema.json`.

The work module owns asks and the waiting-on state. The run module reaches them only through `TaskBoard` (`askQuestion`, `postRunResult`, `refreshWaitingOn`, `runStarted`, `runSucceeded`, `leftOpenQuestions`, `asks`); the tracker module through `ExternalWork`.

Everything in this section applies to every task, whatever its project's work source (§14.1, [ADR-0029](../../adr/0029-work-source-per-project.md)), and so does every later agent-control feature.

### 18.1 Data

| Table or column | Contents |
|---|---|
| `ask` | A tenant table (§7) with composite `(id, company_id)` foreign keys: `task_id`, `run_id`, `agent_id`, `request_id` (the frame's `requestId`, unique per run; null for an open question of a result), `comment_id` (the AGENT comment that carries the question), `question` (1 to 2000 characters), `options` (a JSON array of at most 10 strings), `status` (`OPEN`, `ANSWERED`, `CANCELLED`), `answer` (the answering comment's body; without the quoted question for an answer through the answer endpoint), `answer_comment_id`, `answered_by_user_id` (null for an answer from the tracker), `answered_at` (also the time of a cancel), `created_at`. Asks are never deleted. |
| `run.result_outcome`, `result_next_steps`, `result_open_questions` | The agent's last `finish_run`: `COMPLETED` or `BLOCKED`, and two JSON arrays (at most 20 and 10 strings); all null without a result. The result's summary is the run's `summary`. |
| `task.waiting_on`, `waiting_reason`, `waiting_since` | Who the task waits on (§18.5), and since when its kind or reason last changed. |

### 18.2 Asking

The tool is in §15.6. The runner host sends `run.ask` (`runId`, `epoch`, `requestId`, `question`, `options`) and waits for exactly one `run.ask.ack` (`status` `PENDING` with `askId`, or `REJECTED` with `code`, and a `message` for the agent). The frame renews the run's lease (§15.1).

The server checks, in order, and the first failure is the answer:
1. The run is `RUNNING` on this host with the frame's epoch, else `RUN_NOT_ACTIVE` (also for an unknown run or a stale epoch).
2. A `requestId` the run already sent gets the same `PENDING` answer with the same `askId`, and nothing more happens.
3. The task is not `DONE` or `CANCELLED`, else `TASK_CLOSED`.
4. The question has 1 to 2000 characters after trimming, and at most 10 options of 1 to 100 characters (duplicates ignoring case count once), else `INVALID_REQUEST`.
5. The run asked fewer than 10 questions, else `TOO_MANY_ASKS`.

Then, in the same transaction, it:
- posts the question as an AGENT comment of the run's agent **without** a `runId`: the question, the options as a `- ` list when there are any, a blank line, and `Reply to this comment to answer.` The write-back sends it to a linked issue as a plain comment (§17.5);
- stores the ask as `OPEN` with that comment, audits `ask.created` (§8), and recomputes the waiting-on state;
- answers `PENDING` with: `Your question is posted on the task, where a person answers it. Ask any other question you need, one per call. When you have nothing else to do, stop without writing a summary: Tinfirm wakes you with all the answers at once.`

An agent asks several questions in one run: one call each, then it stops without a summary (§15.5, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)). A run with an ask that is still `OPEN` when it succeeds leaves the task's status as it is (§14.4) and, without a result, posts no end-of-run comment: its questions are its result. The answer to the last of them wakes the agent with every answer at once (§18.3).

### 18.3 Answers

**An answer through the answer endpoint answers the one ask it names; a member's comment answers every `OPEN` ask of its task at once only when the member says so, and a reply on the linked issue always does**, because a comment on the task and a reply on the issue cannot name one question ([ADR-0033](../../adr/0033-one-answer-per-ask.md)). Each answered ask gets its answer, the comment, the member (none for an EXTERNAL comment) and the time, and is audited as `ask.answered`, in the comment's transaction. There are three ways:
- **One ask:** `POST /api/companies/{companyId}/tasks/{taskId}/asks/{askId}/answer` with `{"option"?, "answer"?}` goes through `AskAnswers.answerAsk(taskId, askId, commentId, answer, byUserId)`. It posts the member's comment: the question as a Markdown quote, cut to its first line and 200 characters (`> {question}`), a blank line, then `**{option}**`, a blank line and the text, or either alone. The quote shows in the thread and on the linked issue which question the answer belongs to. The ask's answer is the comment without the quote; the task's other `OPEN` asks stay open. The option must be one of the ask's (exactly, else ignoring case; the ask's spelling is kept); neither, an unknown option or an answer over 20 000 characters is `400 VALIDATION_FAILED`, and an ask that is not `OPEN` is `409 ASK_DECIDED`. Whoever may comment may answer (§14.2): viewers get 403. The response is the answered ask.
- **Every open ask:** each gets the comment's body as its answer, whoever asked. Both ways go through `AskAnswers.answerAsks(taskId, commentId, byUserId)`, which returns the agents whose asks it answered:
  - a member's comment with `answersOpenAsks: true` (`POST /tasks/{id}/comments`); without an `OPEN` ask it is a comment that wakes with `COMMENT`, and no error;
  - the EXTERNAL comments a poll imports into a linked task (§17.4), audited with actor SYSTEM and `cause` `external`: the issue has no answer form, and the agent's question there says to reply to it.
- **The open asks of the agents it mentions:** an EXTERNAL comment that mentions agents (§14.3, §17.4) is addressed to them and answers only the `OPEN` asks of those agents (`AskAnswers.answerAsksOf`), whose runs they are ([ADR-0034](../../adr/0034-mention-an-agent.md)). A reply that mentions nobody answers every open ask, as above. A member's comment says what it answers with the box, so its mentions do not narrow it.
- **None:** a member's plain comment (`answersOpenAsks` absent or false) answers no ask. While the task has an `OPEN` ask it wakes no assignee, not even with `followup_pending`, and stays in Tinfirm: the agent reads it among the new comments of its next wake, together with the answers (§15.5). Without an `OPEN` ask it wakes with `COMMENT` (§14.3). Either way, the agents other than the assignee that it mentions get a pending wake with `MENTION` (§14.3, [ADR-0034](../../adr/0034-mention-an-agent.md)). Comments themselves are not audited (§8).

The wake is `ASK` instead of `COMMENT` (§14.3), with the same conditions and coalescing, and it wakes **the agent that asked** ([ADR-0034](../../adr/0034-mention-an-agent.md)): the assignee through its follow-up flag as before, another agent, such as a mentioned one, through a pending wake. A comment that answered asks wakes each agent that asked once, whatever the number of asks; when it answered other agents' asks only, it does not wake the assignee. An answer to one ask wakes the agent that asked only when no `OPEN` ask of that agent on the task remains (`AskAnswers.answerAsk` says what remains of that agent's); while one does, nothing is woken and the task keeps waiting on a person with reason `ASK` (§18.5). Open asks of other agents do not hold it back. So an agent that asked several questions gets every answer in one wake, when the last one arrives, and a plain comment never wakes an agent that waits on answers, unless it mentions an agent other than the assignee. A member's comment that answered an ask, either way, is also published as `CommentPosted`, so it goes to a linked issue with the member's name (§17.5), one comment per answer; other member comments stay in Tinfirm.

The resume prompt of the agent's next run (§15.5) starts with its own asks on the task answered since its previous run began, in the order they were asked, each with its own answer (the ask's answer, so without the quote):
```
## Answers
**Q:** {question}
**A:** {answer, cut like a comment}

**Q:** ...
```
The answering comments are also among the new comments, and so are the plain comments written while the agent waited; the previous run's own question comments are not.

Closing a task (`DONE` or `CANCELLED`) cancels its `OPEN` asks in the same transaction, audited as `ask.cancelled`.

### 18.4 Results

`finish_run` (§15.6) records the agent's result, and `run.finished.result` carries the last call: `outcome`, `summary`, `nextSteps`, `openQuestions`. On a `SUCCEEDED` run the server:
- stores the result on the run, and its summary (cut to 20 000 characters) as the run's summary instead of the last message;
- writes the end-of-run AGENT comment (§14.4) as the summary, then `Next steps:` and `Open questions:` with one `- ` line each when the lists have entries, then the pull request lines;
- for `BLOCKED`, keeps the task's status (§14.4) and turns each open question into an `OPEN` ask that points at that comment (no options, no request id, at most 10, each cut to 2000 characters, audited as `ask.created`), so a linked issue gets one comment. The run's agent is the asker.

A run without a result works as before, except that it posts no end-of-run comment when it ends on its own open questions or pending repository request (§14.4, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)); its summary stays on the run. A run with a result always gets its comment, also when it asked too. `Run.result` shows the result; the run page shows it.

### 18.5 Waiting on

`Task.waitingOn` is `HUMAN`, `AGENT`, `RUNNER` or `NONE`, with `waitingReason` and `waitingSince`. The first rule that applies wins:

| # | When | `waitingOn` | `waitingReason` |
|---|---|---|---|
| 1 | The task is `DONE` or `CANCELLED` | `NONE` | null |
| 2 | Its latest run is `QUEUED` | `RUNNER` | the run's queue reason code (§14.8), `ASSIGNED` once it was sent to its host, or null before the dispatcher looked at it |
| 3 | Its latest run is `RUNNING` | `AGENT` | null |
| 4 | It has an `OPEN` ask | `HUMAN` | `ASK` |
| 5 | It has a `PENDING` repository request | `HUMAN` | `REPOSITORY_REQUEST` |
| 6 | Its latest run's result is `BLOCKED` | `HUMAN` | `BLOCKED` |
| 7 | Its latest run is `FAILED` or `LOST` | `HUMAN` | `FAILED` |
| 8 | It is `IN_REVIEW` | `HUMAN` | `REVIEW` |
| 9 | It is `BACKLOG`, or `TODO` without an agent | `HUMAN` | `START` |
| 10 | Otherwise | `NONE` | null |

- `WaitingOnResolver.recompute(taskId)` reads the inputs and writes the state only when the kind or the reason changed; `waitingSince` is then now.
- It runs **in the same transaction as every change of an input**: creating or updating a task (by a member, by an agent, from an issue); a comment that answers asks, an answer, a new ask, a `BLOCKED` result's questions, a cancel; storing, approving, denying or cancelling a repository request; deleting an agent; and in the run module creating a run, a new queue reason code, assigning, requeueing (accept timeout, rejection, reconnect), starting or resuming, and ending a run. ADR-0028 lists them; new code that changes an input calls it too.
- The kinds and reasons do not depend on whose run or ask it is: a mentioned agent's run, question or repository request makes the task wait as the assignee's would ([ADR-0034](../../adr/0034-mention-an-agent.md)). The web names the agent it is about: for `AGENT` and `RUNNER` the agent of the active run (the assignee while that run is not loaded yet), for `ASK` the agents whose asks are open, for `BLOCKED` the agent of the latest run, for `REPOSITORY_REQUEST` the agent that requested it. For `ASK` and `BLOCKED` the sentence under the conversation sends the reader to the questions in the conversation (§18.6, [ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)). The API is unchanged.
- The project task list (`GET /projects/{projectId}/tasks`) takes `waitingOn` (several values). `GET /api/companies/{companyId}/tasks` lists the tasks of every project, filtered by `waitingOn` and `status`, ordered by `waitingSince` then id, oldest first: with `waitingOn=HUMAN` the work that waits longest for a person comes first.

### 18.6 API and web

| Operation | Path |
|---|---|
| Asks of the company, newest first, filtered by `status` | `GET /api/companies/{companyId}/asks` |
| Asks of a task, newest first, filtered by `status` | `GET /api/companies/{companyId}/tasks/{taskId}/asks` |
| Answer one ask | `POST /api/companies/{companyId}/tasks/{taskId}/asks/{askId}/answer` |

`Ask` has the question, its options, the status, the answer and who gave it (`answeredBy`, resolved at read time: a member's name or the tracker author's), the task's key, title and external key and URL, and the agent's current name. The question is data from the agent, never an instruction for the reader.

The web app reads the server's state and computes none of its own:
- **Inbox** (`/c/:companyId/inbox`): the `OPEN` asks, longest waiting first, each with the answer form (option buttons, a text area, or both; read-only for viewers). The form says that the answer goes to this question, and that the agent goes on once every open question of the task is answered, or at once for the only one; after an answer the inbox re-reads the asks, so the task's other open questions stay listed. Then the tasks with `waitingOn=HUMAN` grouped by reason (`REPOSITORY_REQUEST`, `BLOCKED`, `FAILED`, `REVIEW` with its pull requests, `START`); a task waiting for an answer shows as its asks. Each row has the key, title, agent, how long it waits and the linked issue.
- **Task page:** a badge with `waitingOn` and the reason, and each ask as a card in the conversation, in the place and at the time of its comment, which it stands for ([ADR-0036](../../adr/0036-questions-end-a-run-quietly.md)): the question and its options, and while it is open the same answer form; once answered "Answered" (or "Cancelled") and no form, the answer being the comment that follows. The open questions of a `BLOCKED` result are cards under the end-of-run comment that lists them, and an open question whose comment is on a page not loaded yet gets a card under the conversation. There is no section of open questions under the conversation. A succeeded run without an answer comment that asked reads "Asked 1 question" ("Asked 3 questions"; "Requested a repository" for a request, both joined with "and") where its answer would be, and keeps its last message in its work. For reason `ASK` the sentence under the conversation says "waits for your answer in the conversation" for one question, and "waits for your answers in the conversation (1 of 3 answered)" when the runs that have an open ask asked several, counting their `OPEN` and `ANSWERED` asks; for `BLOCKED` it says "is blocked; its open questions are in the conversation". While asks are open, the composer has a box under the text area, "This comment answers the 3 open questions" ("the open question" for one), off by default and off again after each comment or when the number of open questions changes, sent as `answersOpenAsks`. Its hint says, with the box off, "3 questions are open. {agent} reads this comment together with your answers.", and with it on, "This comment answers every open question at once and wakes {agent}." Without open asks the composer has no box.
- **Mentions in the composer** ([ADR-0034](../../adr/0034-mention-an-agent.md)): the task page loads the company's agents once (`listAgents`). Typing `@` at the start of a word opens a list under the text area of the agents whose handle or name starts with the text typed after it, ignoring case, the assignee first, at most 8. Arrow keys move, Enter or Tab inserts `@handle ` at the caret, Escape closes, a click inserts. When the text mentions agents other than the assignee, a line under the composer says "{Name} will be asked to work on this task." ("{Name} and {Name} will be asked …"), and, when it mentions others only and the task has an assignee (and the comment does not answer the open questions), adds " {Assignee} is not woken by this comment." The thread shows `@handle` of a live agent in comments in bold, with the agent's name as its title, outside code; a run with trigger `MENTION` reads "mentioned by {author}", and its label is Mention. The agents page shows `@handle` next to each name, and the agent dialog has a Handle field: "People mention the agent as @handle in comments. Lower-case letters, digits and hyphens. Left empty, it is made from the name.", with the handle the typed name makes as its placeholder.
- **Run page:** the result, with its outcome, next steps and open questions. Under the open questions of a `BLOCKED` result, a note says that each is a question in the task's conversation with its own answer form there, and that a comment answers them all only when it is marked as their answer. A run without a result shows its summary, also one that ended on its questions and posted no comment.
- **Overview:** the number of tasks per `waitingOn`, and the tasks that wait on a person in the server's order, after the caller's own credentials that stopped working.
- **Project tasks** ([ADR-0029](../../adr/0029-work-source-per-project.md)): a project with Tinfirm tasks keeps its board, and each card carries the waiting-on badge. A project that a tracker (Jira or GitHub) feeds shows a list instead: the key linked to the task, the title, the badge and since when, the assignee, the status and the pull requests, filtered on the server by status, assignee and `waitingOn`, without "New task".

## 19. Routines

A routine creates a task on a schedule, for example a report on the first of every month. The decision is [ADR-0032](../../adr/0032-routines.md), the table is in `V031__routines.sql`, the HTTP contract is tag Routines and the fields `Task.routineId` and `Task.routineName` in `api/openapi.yaml`, and the guide is [operations/routines.md](operations/routines.md).

Every firing creates one `TODO` task assigned to the routine's agent, which wakes it with `ASSIGNMENT` like a member's task (§14.3). The agent works on it as on any task: it attaches its report (`upload_attachment`) and finishes (`finish_run`), and the task then waits on a person for review (§18). An idle routine wakes nobody, and every firing is a task on the board.

The module `routine` uses the work module (`ScheduledWork`), the project, agent and audit modules, implements the work module's port `RoutineNames` and the project module's port `RoutineUsage`, and listens to `AgentDeleted` and `ComponentDeleted`. No module depends on it.

### 19.1 Data

`routine` is a tenant table (§7) with composite `(id, company_id)` foreign keys:

| Column | Contents |
|---|---|
| `project_id` | The project the tasks are created in; its work source must be `TINFIRM` (§14.1) |
| `agent_id` | The assignee of every task |
| `name` | 1 to 100 characters after trimming, unique in the company, else `409 DUPLICATE_ROUTINE_NAME` |
| `enabled` | Default true |
| `cron`, `time_zone` | The schedule, stored as given (without surrounding whitespace), and the IANA time zone it is read in (§19.2) |
| `title` | The task title template, 1 to 200 characters |
| `description` | The task description, Markdown, at most 50 000 characters; null for none |
| `component_ids` | The task's repositories (`uuid[]`, at most 20): null for every component of the project the agent may access at the firing, as for a member's task without `componentIds` (§14.7); an empty array for none |
| `overlap` | `SKIP` (default) or `CREATE` (§19.3) |
| `catch_up` | `NONE` (default) or `ONE` (§19.3) |
| `next_run_at` | When the next firing is due |
| `last_run_at`, `last_task_id`, `last_outcome`, `last_error` | The last firing: its scheduled time (or the moment of a run now), the last task the routine created (a firing without a task keeps it), its outcome, and why it `FAILED` (at most 2000 characters) |
| `disabled_reason` | Why the server disabled the routine: `agent.deleted`; null when a member disabled it, or it is enabled |
| `created_by`, `created_at`, `updated_at` | |

A company has at most 50 routines (`400 VALIDATION_FAILED`). The work module adds `task.routine_id`, a composite foreign key to `routine` with `ON DELETE SET NULL`. Such a task has no creating member, agent or tracker item (`task_routine_ck`); `Task.createdBy` is the routine's name, or `deleted routine` once it is deleted.

A `SECURITY DEFINER` lookup like those of V012 and V024, `routine_due(p_now, p_limit)`, returns the ids and company ids of the enabled routines whose `next_run_at` has passed, the longest due first.

### 19.2 Schedule

- **Cron.** Five fields, `minute hour day-of-month month day-of-week`. Each is `*`, a number, a range `a-b`, a step `*/n` or `a-b/n`, or a list of those separated by `,`. Minutes 0-59, hours 0-23, days 1-31, months 1-12, days of the week 0-7, where 0 and 7 are Sunday. Names (`MON`, `JAN`) and `?`, `L`, `W` or `#` are not part of the form. When both day fields name days (neither starts with `*`), a day matches either; otherwise it must match both.
- **Time zone.** An IANA zone such as `Europe/Prague` or `UTC`; offsets such as `+02:00` are refused. The cron is read in it: `{date}` and the firing times are local. A local time that the change to summer time skips fires at the same wall-clock time after the change (02:30 becomes 03:30); a local time that the change back repeats fires once, at its first occurrence.
- **At most every 15 minutes.** Two minutes of the minute field closer than 15 minutes are refused, also across the end of an hour when the hour field has two hours in a row, and so are two of the next 50 firings that are closer. A cron that never fires (30 February) is refused too. Every refusal is `400 VALIDATION_FAILED` with the reason, e.g. `The cron fires at minute 0 and at minute 5, 5 minutes apart; a routine fires at most every 15 minutes`.
- **Title.** `{date}` becomes the firing's local date (`2026-11-01`); a title without `{date}` gets the date appended in parentheses, so the tasks of a routine stay distinct. The result is cut to 200 characters, keeping an appended date.

### 19.3 Firing

Replicas with the `worker` role look for due routines every `tinfirm.routine.tick` (default 30 s) through `routine_due`, and fire each in its company's tenant context, in its own transaction, as actor SYSTEM, like the tracker's poll (§17.3):

1. **Lock** the routine with `FOR UPDATE SKIP LOCKED`; another replica may hold it. Check again that it is enabled and `next_run_at <= now`.
2. **Decide**; the first rule that applies wins:

   | Outcome | When |
   |---|---|
   | `SKIPPED_TRACKER` | The project's work source is no longer `TINFIRM`. |
   | `SKIPPED_AGENT` | The agent was deleted. The routine is disabled with `disabled_reason` `agent.deleted`. |
   | `SKIPPED_OPEN` | `overlap` is `SKIP` and the routine's last task is open (not `DONE` or `CANCELLED`). |
   | `SKIPPED_MISSED` | The firing was due longer ago than `tinfirm.routine.missed-after` (default one hour) and `catch_up` is `NONE`. Several missed firings make one record. |
   | `CREATED` | Otherwise: the task is created (step 3). With `catch_up` `ONE`, the missed firings make this one task. |

3. **Create** the task through `ScheduledWork`, on behalf of the firing's actor: `TODO`, assigned to the agent, the project's next key (§14.1), the title with the date (§19.2), the description, the repositories of `component_ids` (the agent must have access to each, §14.7), and `task.routine_id`. It is audited as `task.created` with `cause` `routine` and `routineId` (§8), and the assignment wakes the agent with `ASSIGNMENT` (§14.3).
4. **Record** `last_run_at` (the `next_run_at` that was due), `last_task_id`, `last_outcome`, `last_error` and the new `next_run_at`: the first firing after now.
5. **Audit** `routine.fired` (§8).

A failure to create the task, for example an agent without access to a listed repository, rolls the firing's transaction back. A second transaction locks the routine again and, when its `next_run_at` is still the one that was tried, records `FAILED` with the reason and moves `next_run_at` on, so one bad firing does not repeat at every tick.

### 19.4 Run now

`POST /api/companies/{companyId}/routines/{routineId}/run` (OWNER, ADMIN, OPERATOR) fires the routine once, at once, as the calling member: the decision of §19.3 without `SKIPPED_MISSED`, a task audited with `cause` `routine.run`, and `routine.fired` with actor USER and `cause` `routine.run`. It locks the routine (waiting for a firing in progress), records the run as the last one, and does not move `next_run_at`. A disabled routine runs too. The answer is `RoutineRunResult`: the `outcome`, the `task` (`id`, `key`, `title`) when one was created, and the `error` of a `FAILED` run. The usage of the routine's agent does not gate it ([ADR-0035](../../adr/0035-agent-usage-policy.md)): an owner or admin set the routine up.

### 19.5 Changes

- **Create and change** (OWNER, ADMIN). The project must be a project of the company with Tinfirm tasks, read under a key-share lock (`ProjectCatalog.lockProjects`), else `409 PROJECT_USES_TRACKER`; the agent a live agent of the company; every listed component a component of the project that the agent may access. Otherwise `400 VALIDATION_FAILED` with the reason. The agent may be one usable by its owner only ([ADR-0035](../../adr/0035-agent-usage-policy.md)): owners and admins may use every agent, and the routine's tasks are SYSTEM actions, or run now (§19.4), neither gated by it.
- **The next firing.** Creating or changing a routine computes `next_run_at` from now. Disabling keeps it, unless the schedule changes too; enabling computes it again and clears `disabled_reason`.
- **Change request.** `RoutineUpdateRequest`: absent fields stay as they are. `componentIds` replaces the repositories (an empty array: none); `clearComponentIds: true` sets them to null (every component the agent may access), not together with `componentIds`. An empty `description` removes it.
- **Delete** (OWNER, ADMIN). The routine's tasks stay; their `routine_id` becomes null.
- **Deleted agent.** Deleting an agent disables its routines with `disabled_reason` `agent.deleted` in the deleting transaction, audited as `routine.updated` with `cause` `agent.deleted`. Enabling such a routine needs another agent first (`400 VALIDATION_FAILED`).
- **Deleted component.** Deleting a component removes it from the `component_ids` of every routine in the deleting transaction, audited as `routine.updated` with `cause` `component.deleted`.
- **Work source.** A project with Tinfirm tasks cannot switch to `JIRA` or `GITHUB` while a routine, enabled or not, targets it: `409 PROJECT_HAS_ROUTINES`, naming the routines (§14.1). The project module asks through `RoutineUsage` after locking the project row, so a routine saved at the same time is either seen or refused.

### 19.6 API and web

| Operation | Path | Who |
|---|---|---|
| List routines, by name (keyset) | `GET /api/companies/{companyId}/routines` | Any member |
| Create a routine | `POST /api/companies/{companyId}/routines` | OWNER, ADMIN |
| Get, change or delete a routine | `GET`, `PATCH`, `DELETE /api/companies/{companyId}/routines/{routineId}` | Any member reads; OWNER, ADMIN change and delete |
| Run now | `POST /api/companies/{companyId}/routines/{routineId}/run` | OWNER, ADMIN, OPERATOR |

The permissions are `VIEW_WORK`, `MANAGE_ROUTINES` and `RUN_ROUTINES`. `Routine` has every column of §19.1 in camelCase, with `projectName`, `agentName`, `lastTaskKey` and `createdBy` resolved at read time.

The web app:
- **Routines** (`/c/:companyId/routines`, after Trackers in the navigation): each routine with its schedule as given and its zone, the project, the agent, the next run, the last run with its outcome and the task's key linked, and a disabled routine with the server's reason. Owners and admins create, change and delete routines in a dialog: the name, a project with Tinfirm tasks, the agent, the cron with a one-line help and the next three firings computed in the browser, the time zone (the browser's by default), the title with the `{date}` hint, the description, the repositories (every one the agent may access, or a choice, like the new-task dialog), the overlap and catch-up choices with one line each, and enabled. Operators and above run a routine now and see its result.
- **Task page and board card:** "from routine {name}", linked to the routine on the Routines page (`#routine-{id}`), where a task from an issue shows the issue's key.
- **Project settings:** `PROJECT_HAS_ROUTINES` shows the server's detail with a link to the Routines page.

### 19.7 Configuration

Settings under `tinfirm.routine` have no environment variable: `tick` (`PT30S`, how often replicas with the `worker` role look for due routines) and `missed-after` (`PT1H`, how late a firing may be before the catch-up rule applies). A routine fires only where a replica runs the `worker` role (`TINFIRM_ROLES`, §4).
