# Development guide

This guide explains how to work on the server and the web app on your machine.
Setup steps and pre-PR checks are in [CONTRIBUTING.md](../CONTRIBUTING.md). The binding rules are in [CONVENTIONS.md](CONVENTIONS.md).

`main` runs the redesign of 2026-10-12 ([PLATFORM.md](PLATFORM.md)). Nothing is released yet. The runner host and the model port have their own guides: [runner/README.md](../runner/README.md) and [model-api/README.md](../model-api/README.md).

## 1. Layout

| Path | Contents | Build tool |
|---|---|---|
| `api/openapi.yaml` | HTTP contract. The server interfaces and the web client are generated from it. | — |
| `server/` | Spring Boot app, one Maven module (`com.flowtivo.tinfirm:tinfirm-server`) | Maven wrapper `./mvnw` |
| `server/src/main/resources/db/migration/` | Flyway migrations `V001__baseline.sql`, `V002__…` | Flyway |
| `web/` | Angular 22 SPA (`tinfirm-web`) | npm |
| `web/src/app/ui/` | spartan/ui styled components, generated once and then owned by us | spartan CLI |
| `web/src/app/api/generated/` | Generated API client. Git ignores it. | `npm run generate:api` |
| `web/e2e/` | Playwright end-to-end tests | Playwright |
| `runner/` | The runner host (a Cargo workspace) and the sandbox images | Cargo |
| `protocol/` | Runner protocol schemas: `runner-v1.schema.json`, `runner-v2.schema.json` | — |
| `model-api/` | The model port, one Maven module (`tinfirm-model-api`) | Maven wrapper `./mvnw` |
| `libs/` | Shared sources (`jira`, `net`, `litellm`) that the builds of the server and the model port compile in, with their tests | Maven, through each module |
| `deploy/compose/compose.yaml` | Local stack: `postgres`, `litellm-db` (creates LiteLLM's database, then exits), `litellm`, `model-api`, `server`, and `keycloak` in profile `oidc` | Docker Compose |
| `deploy/compose/compose.oidc.yaml` | The server's OIDC settings for the dev Keycloak. `make up-oidc` adds it. | Docker Compose |
| `deploy/compose/compose.entra.yaml` | Example OIDC settings for a Microsoft Entra ID tenant ([operations/entra-id.md](operations/entra-id.md)) | Docker Compose |
| `deploy/compose/.env.example` | Development-only defaults. `make` copies it to `.env` (git-ignored) when `.env` is missing. | — |
| `deploy/compose/postgres/init.sql` | Creates the roles `tinfirm_owner` and `tinfirm_app` and the database | PostgreSQL |
| `deploy/compose/postgres/litellm.sql` | Creates LiteLLM's role and database `litellm`, or brings them up to date; `litellm-db` runs it on every start | PostgreSQL |
| `deploy/compose/litellm/config.yaml` | LiteLLM's settings: models in its database, prompts and answers never stored | LiteLLM |
| `deploy/compose/keycloak/tinfirm-realm.json` | Keycloak realm `tinfirm`: client `tinfirm`, test user `alice@example.com` | Keycloak |
| `deploy/compose/deploy-host.sh` | Deploys the Compose stack to one host over SSH (`make deploy-host`) | Docker, SSH |
| `deploy/compose/auto-deploy/` | Pull-based deploy of every commit on `main` to one Compose host. See [operations/auto-deploy.md](operations/auto-deploy.md). | systemd, Docker |
| `deploy/release/` | Builds the release assets: static runner host binaries, the Compose tarball (`make release-assets`) | Docker, zig |
| `deploy/helm/tinfirm/` | Helm chart. See [operations/helm.md](operations/helm.md). | Helm |
| `deploy/ansible/` | Turns a Linux VM into a runner host with Kata Containers sandboxes ([deploy/ansible/README.md](../deploy/ansible/README.md)) | Ansible |
| `Dockerfile` | Multi-stage image: web build, server build, JRE 25 runtime | Docker |
| `Makefile` | Developer entry points. `make help` lists them. | make |
| `.github/workflows/` | `ci.yml`, `release.yml`, `runner-artifacts.yml` (release assets), `security.yml`, `hotfix-branch.yml`, `pr_agent.yml` | GitHub Actions |

Server modules (Spring Modulith, CONVENTIONS §10) are the 20 top-level packages under `com.flowtivo.tinfirm`: `shared`, `identity`, `company`, `audit`, `vault`, `bootstrap`, `runner`, `mcp`, `principal`, `system`, `account`, `model`, `agent`, `run`, `conversation`, `governance`, `trigger`, `chat`, `skill` and `diagnostics`. `ModularityTest` lists them. A module's internals are package-private or in an `internal` subpackage. `ApplicationModules.verify()` runs in the build.

## 2. Ways to run

| Goal | What to run | URL |
|---|---|---|
| Try the product | `make up` | <http://localhost:8080> |
| Try OIDC login | `make up-oidc` | <http://localhost:8080> |
| Work on the server | `make dev-db`, plus `./mvnw spring-boot:run` in `server/` | <http://localhost:8080> |
| Work on the web app | A running server (either way above), plus `npm start` in `web/` | <http://localhost:4200> |

`make logs` follows the logs (`SERVICE=server|postgres|litellm|model-api|keycloak` for one service). `make down` stops and removes everything, Keycloak included, and keeps the volumes.

Compose publishes these ports on `127.0.0.1` only (CONVENTIONS §12):

| Host port | Service |
|---|---|
| 8080 | Server: API, OIDC endpoints, SPA |
| 5432 | PostgreSQL |
| 4000 | LiteLLM's admin and model API (the operator's) |
| 4001 | The model port `model-api`: LiteLLM's model routes alone, for a service run from your IDE with a LiteLLM key of its own ([model-api/README.md](../model-api/README.md)) |
| 8180 | Keycloak (profile `oidc`) |

> **Port already in use?** Set `TINFIRM_HTTP_HOST_PORT`, `TINFIRM_DB_HOST_PORT`, `TINFIRM_LITELLM_HOST_PORT` and `TINFIRM_MODEL_API_HOST_PORT` in `deploy/compose/.env`, and set `TINFIRM_PUBLIC_URL` to the same HTTP port (for example `http://localhost:18080`). The Keycloak client's redirect URI follows `TINFIRM_PUBLIC_URL`. Keycloak itself must stay on 8180, because its issuer URL is fixed.

Compose does not publish the health and management ports: the server's 8081 and the model port's 4002.

### 2.1 Server against the Compose database

```sh
make dev-db
cd server && ./mvnw spring-boot:run
```

`make dev-db` starts only PostgreSQL and waits until it is healthy. Set the environment first (section 3). If the Compose `server` container runs, stop it: it also uses port 8080.

On startup the server does these steps in this order:

1. Flyway migrates the schema as `tinfirm_owner` (`SPRING_FLYWAY_USER`).
2. The application connects as `tinfirm_app` (`SPRING_DATASOURCE_USERNAME`).
3. If the user table is empty and both bootstrap variables are set, the server creates the instance admin, audited with actor `SYSTEM`.
4. The server creates each declared company (`TINFIRM_COMPANIES_<KEY>_*`) that does not exist yet, and makes its declared owner, or the bootstrap admin, its OWNER while it has none ([ADR-0075](adr/0075-companies-and-sign-in-from-the-deployment.md), [operations/companies-and-sign-in.md](operations/companies-and-sign-in.md)). Without a declared company, the instance has none: set at least `TINFIRM_COMPANIES_MAIN_NAME`.

The server fails at startup if `TINFIRM_VAULT_KEYS` or `TINFIRM_VAULT_ACTIVE_KEY` is missing.

Server ports:

| Port | Contents |
|---|---|
| 8080 | API (`/api/**`), OIDC endpoints (`/oauth2/**`, `/login/oauth2/**`), SPA |
| 8081 | Management: `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` |

Logs are structured JSON on stdout. The server never logs secret values, passwords, session IDs or CSRF tokens. Keep it that way when you add log statements.

### 2.2 Web app with the dev proxy

```sh
cd web
npm ci
npm start
```

The dev server listens on port 4200. `web/proxy.conf.json` forwards `/api`, `/oauth2` and `/login/oauth2` to `http://localhost:8080`. The browser sees one origin, so the session cookie and the CSRF cookie work as in production.

The SPA calls `GET /api/auth/providers` before login. That call sets the `XSRF-TOKEN` cookie. Angular `HttpClient` copies it into the `X-XSRF-TOKEN` header on mutating requests. Do not replace this mechanism.

The server sends a strict Content-Security-Policy on every response (CONVENTIONS §5). It forbids inline scripts, so Angular's critical-CSS inlining is off. `npm start` does not send these headers. Check UI changes on port 8080 too (`make up`).

### 2.3 OIDC in development

`make up-oidc` starts Keycloak (profile `oidc`) and adds `deploy/compose/compose.oidc.yaml`, which holds the server's OIDC settings. Keycloak imports the realm from `deploy/compose/keycloak/tinfirm-realm.json`.

| Item | Value |
|---|---|
| Issuer | `http://keycloak.localhost:8180/realms/tinfirm`. It must match the `iss` claim exactly. |
| Client ID | `tinfirm` (confidential client, PKCE `S256`) |
| Client secret | `TINFIRM_OIDC_CLIENT_SECRET` in `deploy/compose/.env` |
| Registration ID in the server | `keycloak` |
| Redirect URIs in the realm | `http://localhost:8080/login/oauth2/code/keycloak`, `http://localhost:4200/login/oauth2/code/keycloak` |
| Test user | `alice@example.com`, email verified. Password: `TINFIRM_OIDC_TEST_USER_PASSWORD` in `deploy/compose/.env`, default `dev-only-alice`. The realm file takes the value from that variable at import. |
| Admin console | <http://keycloak.localhost:8180/admin>, user `admin`, password `KEYCLOAK_ADMIN_PASSWORD` in `deploy/compose/.env` |

**Why `keycloak.localhost`.** The browser, the server container and a server that runs from your IDE must all use the same issuer string. `*.localhost` resolves to the loopback address on macOS and on most Linux systems. Inside the Compose network, `keycloak.localhost` is a network alias of the Keycloak service. Linux without systemd-resolved needs a hosts entry:

```text
127.0.0.1 keycloak.localhost
```

**Server from the IDE against the Compose Keycloak.** Start Keycloak with `make up-oidc`, stop the Compose server, then set the same variables as `compose.oidc.yaml`:

```sh
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID=tinfirm
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET='<TINFIRM_OIDC_CLIENT_SECRET>'
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_NAME='Keycloak (dev)'
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_AUTHORIZATION_GRANT_TYPE=authorization_code
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email
export SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI=http://keycloak.localhost:8180/realms/tinfirm
```

With an issuer URI set, the server fetches the issuer's discovery document at startup. The server does not start while Keycloak is down. That is why these settings live in a separate Compose file.

Notes:

- `TINFIRM_PUBLIC_URL` drives the OIDC redirect URI. To log in through the dev proxy on port 4200, set `TINFIRM_PUBLIC_URL=http://localhost:4200`. The realm already allows that redirect URI.
- The server identifies an OIDC user by `(issuer, subject)`, never by email (CONVENTIONS §5). The first login creates a new user if auto-provisioning is on and the email can be trusted. By default (`REQUIRE_VERIFIED_CLAIM`) the email comes from the `email` claim and the IdP must assert `email_verified=true`; the dev Keycloak does that for alice.
- A registration can trust its issuer instead: `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST=TRUST_ISSUER`, with the claims to read in `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` (section 3). Entra ID needs this, because it sends no `email_verified`: see [operations/entra-id.md](operations/entra-id.md) and `deploy/compose/compose.entra.yaml`.
- The new user joins the registration's company (`TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANY`; `compose.oidc.yaml` binds Keycloak to `main`). Keycloak decides the role at every sign-in from the realm's groups (ADR-0088): alice is in `tinfirm-users`, a Member; in `tinfirm-admins` she is an Admin at her next sign-in, and in neither she is refused with `ROLE_NOT_MAPPED`. With `..._EMAIL_DOMAINS` set, an email outside them fails with `EMAIL_DOMAIN_NOT_ALLOWED` (`/login?error=EMAIL_DOMAIN_NOT_ALLOWED`).
- If none of those claims holds an email address, the first login fails with `EMAIL_MISSING`, and the SPA shows `/login?error=EMAIL_MISSING`. If the email trust does not accept the email, it fails with `EMAIL_NOT_VERIFIED` (`/login?error=EMAIL_NOT_VERIFIED`). Other refusals show `OIDC_LOGIN_FAILED`; the `auth.login.failed` audit event in the instance chain names the reason, for example `not_provisioned`.
- The server never links an OIDC login to an existing user. If the email already belongs to another user, the login fails with `EMAIL_IN_USE`, and the SPA shows `/login?error=EMAIL_IN_USE`. That is why alice has a different email from the bootstrap admin.
- OIDC users cannot change a password: `PUT /api/me/password` returns `409 NOT_LOCAL_USER`.

## 3. Configuration

[CONVENTIONS §4](CONVENTIONS.md#4-runtime-contract-of-the-server) is the source of truth for all variables and defaults. The table gives values for local development. Names in angle brackets are variables in `deploy/compose/.env`.

| Variable | Local development value | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | default `jdbc:postgresql://localhost:5432/tinfirm` | Matches `make dev-db`. Set it only if your database is elsewhere. |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `tinfirm_app` / `<TINFIRM_APP_PASSWORD>` | Runtime role. RLS applies to it. |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | `tinfirm_owner` / `<TINFIRM_OWNER_PASSWORD>` | Migration role. Owns the schema. |
| `TINFIRM_PUBLIC_URL` | default `http://localhost:8080` | `http://localhost:4200` for OIDC through the dev proxy. A run paid from a LiteLLM budget reaches its model through the server's relay under this URL, and the runner host's model proxy and `run.assign` accept only an `https://` base URL: with an `http://` public URL such runs cannot reach a model, so try them behind an `https://` URL (a tunnel, for example) |
| `TINFIRM_VAULT_KEYS` | `<TINFIRM_VAULT_KEYS>` | Format `keyId:base64(32 bytes)`, comma-separated. Required. |
| `TINFIRM_VAULT_ACTIVE_KEY` | `<TINFIRM_VAULT_ACTIVE_KEY>` | Required |
| `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | `<TINFIRM_BOOTSTRAP_ADMIN_EMAIL>` / `<TINFIRM_BOOTSTRAP_ADMIN_PASSWORD>` | Used only when the user table is empty |
| `TINFIRM_COMPANIES_MAIN_NAME` | `My Company` | Declares the company `main`; no default: without it the instance has no company. Compose sets it from `TINFIRM_COMPANY_NAME` |
| `TINFIRM_COMPANIES_MAIN_OWNER` | unset | Its OWNER while it has none; unset, the bootstrap admin. Compose sets it from `TINFIRM_COMPANY_OWNER` |
| `TINFIRM_AUTH_LOCAL_ENABLED` | default `true` | |
| `TINFIRM_AUTH_OIDC_AUTO_PROVISION` | default `true` | Creates new OIDC users; never links (section 2.3) |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST` | default `REQUIRE_VERIFIED_CLAIM` | `TRUST_ISSUER` lets the issuer of registration `<ID>` assert the email without `email_verified` (section 2.3) |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` | default `email` | Claims to read the email from, in order. Entra ID: `email,preferred_username,verified_primary_email`. Only with `TRUST_ISSUER`. |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANY`, `_ROLE`, `_EMAIL_DOMAINS` | unset | The declared company a new user of registration `<ID>` joins (the only one when one is declared), its role (`MEMBER`), and the email domains that may get an account (CONVENTIONS §5) |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_CLAIM`, `_ROLE_MAP_ADMIN`, `_ROLE_MAP_MEMBER`, `_ROLE_MAP_VIEWER`, `_ROLE_SOURCE` | unset; `compose.oidc.yaml` sets `groups` | With a claim, the provider decides each member's role at every sign-in (ADR-0088, CONVENTIONS §5) |
| `TINFIRM_SESSION_IDLE_TIMEOUT` | default `PT8H` | ISO-8601 duration |
| `TINFIRM_SESSION_MAX_AGE` | default `P7D` | Absolute session lifetime, checked on every request |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | unset | Trusted proxies for `X-Forwarded-For`. Unset means Tomcat's default (private ranges). Never set it to an empty string: Spring Boot then trusts every peer. |
| `SPRING_SECURITY_OAUTH2_CLIENT_…` | see section 2.3 | One registration per IdP |
| `TINFIRM_LITELLM_ADMIN_URL`, `TINFIRM_LITELLM_MASTER_KEY`, `TINFIRM_LITELLM_MODEL_URL` | unset | The bundled LiteLLM. Unset, the server bundles no gateway, and a company configures its own on Model access. To use the Compose LiteLLM from your IDE, start it (`make up`, then stop the Compose `server`) and set `http://localhost:4000`, `<LITELLM_MASTER_KEY>` and `http://localhost:4001` |
| `TINFIRM_ROLES` | default `api,worker` | `worker` runs the background work: the dispatcher and the reaper, polls, schedules, the outbox, retention and the other sweeps. Run both roles in one process locally. |

To make a new development vault key:

```sh
printf 'dev1:%s\n' "$(openssl rand -base64 32)"
```

A new key cannot decrypt secrets that an older key wrapped. If your database already holds secrets, keep the old key in `TINFIRM_VAULT_KEYS` and call `POST /api/admin/vault/rewrap` as an instance admin (CONVENTIONS §9).

## 4. The API contract and generated code

`api/openapi.yaml` is the only HTTP contract. The maintainer owns it; others may change it in their own pull request, where a human reviews the change (CONVENTIONS §2, §13). To propose a change, follow [CONTRIBUTING §8](../CONTRIBUTING.md#8-the-contract-conventions-and-api).

| Side | Generator | Output | When it runs |
|---|---|---|---|
| Server | `openapi-generator-maven-plugin`, `spring` generator, `interfaceOnly` | Controller interfaces and DTOs under `server/target/` | Every Maven build (`./mvnw -B verify`, `./mvnw spring-boot:run`) |
| Web | `npm run generate:api` (ng-openapi-gen, config `web/ng-openapi-gen.json`) | `web/src/app/api/generated/`, ignored by git | Before `npm start`, `npm run build` and `npm test` (`prestart`, `prebuild`, `pretest`) |

Rules:

- Controllers implement the generated interfaces. They stay thin. Services hold the logic. MapStruct maps DTOs.
- Errors are Spring `ProblemDetail` with a `code` property from CONVENTIONS §6.
- Hand-written web code never duplicates DTO types. Import them from the generated client.
- To regenerate the web client by hand after you pull a contract change, run `npm run generate:api` in `web/`.

## 5. Database

### 5.1 Roles

| Role | Purpose | Privileges |
|---|---|---|
| `tinfirm_owner` | Owns every object. Runs Flyway. | Full control of the schema |
| `tinfirm_app` | Runtime role of the server | `CONNECT`, `USAGE` on schema `public`, and what the migrations `GRANT`. It owns nothing, so RLS applies to it. |

Compose creates both roles and the database in `deploy/compose/postgres/init.sql`. The script runs only when the Postgres container starts with an empty volume. To run it again, remove the volume: `make down`, then `docker volume rm tinfirm_postgres-data`.

For an external database, see [operations/external-database.md](operations/external-database.md).

### 5.2 Migrations

- Put Flyway files in `server/src/main/resources/db/migration/`. Name them `V001__short_name.sql`, `V002__…`.
- Never edit a released migration. Add a new one.
- Each migration that creates a table also grants `tinfirm_app` exactly the privileges it needs. Do not use `ALTER DEFAULT PRIVILEGES` for this. It would give the runtime role more than it needs, for example `UPDATE` on `audit_event`.
- Flyway creates the Spring Session tables. Spring must not initialize them itself.
- Identifiers are UUIDv7, generated in the application. Timestamps are `timestamptz` in UTC.
- Use keyset (cursor) pagination. Never use unbounded queries or `OFFSET`.

### 5.3 Row-level security

Every table that belongs to a company:

- has `company_id uuid NOT NULL`;
- runs `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY`.

Each transaction sets the tenant context with transaction-local `set_config(..., true)`:

| Setting | Value |
|---|---|
| `app.company_id` | The company of the request, or empty |
| `app.user_id` | The caller |
| `app.instance_admin` | `true` or `false` |
| `app.system` | `true` only in the SYSTEM tenant context: the bootstrap, the vault's rewrap and the other trusted work across companies |

Policies read `current_setting('app.company_id', true)`. They deny access when it is empty. The only exceptions are explicit cross-company reads (CONVENTIONS §7):

- a user's own memberships (`user_id = app.user_id`);
- companies where the user is a member;
- every company, and the instance audit chain, when `app.instance_admin = 'true'`;
- any row of `company`, `company_membership`, `secret`, `secret_version`, `audit_event`, `audit_chain_head` and `audit_check` (V023) when `app.system = 'true'`.

Things that surprise developers:

- **Empty is not only `NULL`.** On a pooled connection, `current_setting('app.company_id', true)` returns `NULL` before the first `set_config`, and an empty string after a transaction-local value expires. Policies must treat both as "no company", for example with `NULLIF(current_setting('app.company_id', true), '')::uuid`.
- **The context exists only inside a transaction.** A query outside a transaction, or in a new transaction without the context, sees no tenant rows. That is intended. Do not work around it.
- **`FORCE ROW LEVEL SECURITY` applies to the owner too.** A Flyway migration that reads or changes tenant rows as `tinfirm_owner` is also filtered. A data migration must set the context or use a policy that allows it.
- **Two layers.** Repositories also filter by `company_id`. RLS is the second layer, not a replacement.
- **Non-members get 404.** A missing membership must return 404, never 403 (CONVENTIONS §6).

To test a policy by hand, connect as `tinfirm_app` and set the context inside a transaction:

```sql
BEGIN;
SELECT set_config('app.user_id', '<user uuid>', true);
SELECT set_config('app.company_id', '<company uuid>', true);
SELECT set_config('app.instance_admin', 'false', true);
SELECT * FROM <tenant table>;   -- only rows of <company uuid>
ROLLBACK;
```

### 5.4 Audit tables

Grants (CONVENTIONS §7):

| Table | `tinfirm_app` privileges |
|---|---|
| `audit_event` | `INSERT`, `SELECT` |
| Chain-head table | `SELECT`, `INSERT`, `UPDATE`: needed for the `FOR UPDATE` lock and to advance the head |

`tinfirm_app` never has `DELETE` on either table.

Row-level security: both tables have RLS, but `company_id` is nullable. Null means the instance chain.

| Rows | Visible when |
|---|---|
| Company chain | `company_id = app.company_id`, or `app.system = 'true'` |
| Instance chain | `app.instance_admin = 'true'` or `app.system = 'true'` |

Inserts are allowed when `company_id IS NULL OR company_id = app.company_id`. Instance events such as logins are written without a company context.

The chain (CONVENTIONS §8):

- Each company has one hash chain. The instance has one chain with `company_id` null.
- The next event locks the chain's head row with `SELECT … FOR UPDATE` and takes the next `seq` and `prevHash` from it.
- `hash = hex(sha256(prevHash + "\n" + canonical))`. CONVENTIONS §8 defines the canonical JSON. Do not change it: old events would no longer verify.

Personal data stays out of the hashed fields, so that erasure requests do not break the chain:

- The canonical actor is `{type, id}` only. The API resolves `actor.display` when it reads the event. A deleted user shows as `deleted user`.
- `details` holds IDs, roles and names of non-personal objects. It never holds emails, display names, secret values or passwords.
- A failed login for an unknown email records `{"reason":"unknown_user"}`, with no email.

## 6. Tests

### 6.1 Server

```sh
make server-test          # cd server && ./mvnw -B -ntp verify
```

- By default, Testcontainers starts PostgreSQL 18, so Docker must run.
- Without Docker, set `TINFIRM_TEST_JDBC_URL` to a database on a PostgreSQL 18 of your own, for example `jdbc:postgresql://localhost:5432/tinfirm_test`. The roles `tinfirm_owner` (password `owner-test-password`) and `tinfirm_app` (password `app-test-password`) must exist, and `tinfirm_owner` must own that database: `server/src/test/resources/db/test-roles.sql` is what the container runs. `TINFIRM_TEST_SUPERUSER` and `TINFIRM_TEST_SUPERUSER_PASSWORD` (default `postgres` and `postgres`) name a superuser, with which tests that need a fresh instance create databases named after the test database. Two runs on one server each name their own database. The tests run in two JVMs in parallel (`server/pom.xml`), and each makes a database of its own named after that one (`tinfirm_test_fork1`, `tinfirm_test_fork2`), so the superuser must be able to create them. PostgreSQL's default `max_connections` of 100 is enough: the test classes' Spring contexts stay cached, but an idle one's pool shrinks to one connection (`application-test.yaml`).
- Tests connect as the real `tinfirm_app` role, so RLS is really exercised. Do not run tests as a superuser or as `tinfirm_owner`.
- The **tenant isolation test** reads every company-scoped path from `api/openapi.yaml`. It calls each path as a member of company A with the ID of company B, and expects 404. A new path is covered automatically. If the test fails for a new path, fix the code, not the test.
- The build also runs `ApplicationModules.verify()`.

To run one test class: `./mvnw -B test -Dtest=<ClassName>`.

### 6.2 Web

```sh
make web-test             # npm ci, lint, test (Vitest) and build in web/
```

Playwright end-to-end tests live in `web/e2e/`. `npm run e2e` in `web/` starts the harness's own stack (the server on a database it recreates, fakes of LiteLLM, Jira Data Center and GitHub, a scripted runner host and the web app), seeds it, runs the tests and stops it. It needs no Docker, but a PostgreSQL 18 it can reach as a superuser; [web/e2e/README.md](../web/e2e/README.md) says what it needs and lists its `E2E_*` variables. The tests share one stack and run on 3 workers by default (`E2E_WORKERS`). A test tagged `@serial` runs alone: no other test runs while it does. CI runs them in its `e2e` job.

### 6.3 Runner host and the model port

```sh
make runner-test          # cargo test, clippy and fmt in runner/; the tests in real containers need Docker
make model-api-test       # cd model-api && ./mvnw -B -ntp verify; against a fake LiteLLM
```

### 6.4 Deployment artefacts

```sh
make helm-lint            # helm lint --strict and helm template, default and ci/*.yaml values
make deploy-lint          # compose config (with oidc, Entra ID), hadolint, shellcheck, actionlint
make build-image          # docker build -t ghcr.io/flowtivo/tinfirm-server:dev .
make smoke-image          # build-image, then start it against PostgreSQL until /api/system/info answers
make model-api-image      # the model port's image, ghcr.io/flowtivo/tinfirm-model-api:dev
```

`make ci` runs the checks of `ci.yml`, except the security scans, the check of the LiteLLM pin, the build of the model port's image and the publishing ([CONTRIBUTING §5](../CONTRIBUTING.md#5-checks-before-a-pull-request)).

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Server fails at startup with a vault configuration error | `TINFIRM_VAULT_KEYS` or `TINFIRM_VAULT_ACTIVE_KEY` is missing | Set both (section 3) |
| Server cannot connect to the database | PostgreSQL is not running | `make dev-db` |
| Role `tinfirm_app` does not exist, or its password is wrong | The volume was created before `.env` changed; `init.sql` runs only on an empty volume | `make down`, `docker volume rm tinfirm_postgres-data`, then start again |
| Server with OIDC settings does not start | Keycloak is not reachable at the issuer URI | Start Keycloak (`make up-oidc`). Check that `keycloak.localhost` resolves (section 2.3). |
| OIDC login fails with an issuer mismatch | The issuer URI differs from the `iss` claim | Use exactly `http://keycloak.localhost:8180/realms/tinfirm` |
| OIDC login shows `EMAIL_IN_USE` | The IdP email already belongs to another user | Use another IdP account. The server never links by email. |
| OIDC login shows `EMAIL_MISSING` | No claim in `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` (default `email`) holds an email address | Request the `email` scope and claim at the IdP, or list more claims with `TRUST_ISSUER` (section 2.3) |
| OIDC login shows `EMAIL_NOT_VERIFIED` (audit reason `email_not_verified`) | The IdP sends no `email_verified=true`; Entra ID does not send the claim at all | Set `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST=TRUST_ISSUER` for an issuer you control ([operations/entra-id.md](operations/entra-id.md)) |
| Server does not start: `tinfirm.auth.oidc.providers names no OIDC registration` | A `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_*` variable uses an `<ID>` without a `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` registration | Use the same `<ID>` in both |
| Every company-scoped query returns no rows | The tenant context is not set in the transaction | Run the query in a transaction that sets `app.company_id` |
| Mutating requests return 403 | The CSRF token is missing | Call `GET /api/auth/providers` first. Send `X-XSRF-TOKEN`. |
| Bootstrap admin cannot log in after an `.env` change | Bootstrap runs only when the user table is empty | Use the first password, or remove the volume to start over |
| The SPA works on port 4200 but not on 8080 | The server's CSP blocks something, for example an inline script | Check the browser console for CSP errors |
| Server tests fail at container start | Docker is not running | Start Docker, or set `TINFIRM_TEST_JDBC_URL` (section 6.1) |
