# Prepare an external PostgreSQL database

The public guide is [Prepare PostgreSQL](../../docs-site/content/operator-guide/database.md) (https://docs.tinfirm.com/operator-guide/database/), for operators, with backups and restores also in [Upgrade, back up and rotate secrets](../../docs-site/content/operator-guide/maintenance.md) (https://docs.tinfirm.com/operator-guide/maintenance/). This page keeps what the people who build Tinfirm need beyond it: the rules and their sources, how the chart builds the connection, the expand/contract migrations and the one table without row-level security, the recovery targets, and how LiteLLM builds and rewrites its database URL, as checked against its source.

The rules come from [CONVENTIONS §7](../CONVENTIONS.md#7-database), the reasons from [ADR-0009](../adr/0009-rls-tenant-isolation.md), and the pod's two passwords are item 2.9 of the threat model. Docker Compose creates the roles itself in `deploy/compose/postgres/init.sql`, with the same grants as the public guide's, and LiteLLM's database in `deploy/compose/postgres/litellm.sql`. The chart installation is in [helm.md](helm.md).

## 1. Connection settings in the Helm chart

The values are in the public guide's [Connect the chart to the database](../../docs-site/content/operator-guide/database.md#connect-the-chart-to-the-database), and the variables they become in [Server configuration](../../docs-site/content/reference/configuration.md#database). The chart builds the server's database variables (CONVENTIONS §4) from the `database` values, and reads the passwords from an existing Secret, never inlining them (CONVENTIONS §12).

- `SPRING_DATASOURCE_URL` is `jdbc:postgresql://<host>:<port>/<name>?<parameters>`, the parameters being `sslmode`, then `sslrootcert` when `caCertSecret.name` is set, then `params`. With `database.url`, the chart still mounts the CA certificate but adds neither.
- The user names are fixed in the chart: `SPRING_DATASOURCE_USERNAME` is `tinfirm_app`, `SPRING_FLYWAY_USER` is `tinfirm_owner`, because the migrations grant to them. Flyway uses the datasource URL unless `SPRING_FLYWAY_URL` is set (Spring Boot's behaviour).
- The tenant context is transaction-local (`set_config(..., true)`), so it never leaks between pooled connections; Flyway's session-level advisory lock is why a pooler in front must use session pooling.

## 2. First start and upgrades

On every start the server runs Flyway as `tinfirm_owner`. It creates or upgrades the schema, the Spring Session tables included, enables and forces row-level security on every tenant table and on the audit tables, and grants `tinfirm_app` only what it needs; replicas that start together serialize on Flyway's database lock. Migrations are expand/contract. A Helm pre-upgrade hook that takes a database backup is planned.

The public guide's [The first start](../../docs-site/content/operator-guide/database.md#the-first-start) has the checks. In its query 3, `oauth_authorization_request` shows `false` and `false` by design: it holds a person's pending sign-in to a system for ten minutes, and the sign-in's callback finds it by its state before any company context exists, so it is an instance-level table without row-level security, like the API tokens; the callback consumes only a request of the caller's own session and then checks the company (`V001__baseline.sql`).

## 3. Backups and point-in-time recovery

Backups, the keys that are not in the database, and restores are in the public guide's [Back up](../../docs-site/content/operator-guide/database.md#back-up) and [Restore](../../docs-site/content/operator-guide/database.md#restore).

- **Suggested targets.** RPO 15 minutes and RTO 4 hours, with a restore drill at every upgrade. Archiving WAL at least every 15 minutes (`archive_timeout`) keeps the RPO on a quiet instance.
- **What a backup holds**: session IDs in clear text (the Spring Session tables), Argon2id password hashes, the vault's ciphertext and wrapped data keys, and the audit log.
- **Old master keys.** After a rotation and `POST /api/admin/vault/rewrap`, the live database no longer uses the old key; older backups still do, so the key stays while a backup within the retention window predates the rewrap ([ADR-0011](../adr/0011-envelope-encrypted-vault.md)).
- **A restore cannot be told from a deleted tail.** A point-in-time restore removes the newest audit events, and the remaining hash chain is still valid, so the verification endpoint cannot tell the two apart: record every restore outside the database.

## 4. LiteLLM's database

How to create LiteLLM's database and connect the chart to it is in the public guide's [Create LiteLLM's database](../../docs-site/content/operator-guide/database.md#create-litellms-database) and [Connect LiteLLM to its database](../../docs-site/content/operator-guide/database.md#connect-litellm-to-its-database). The server reaches LiteLLM only through its HTTP API (the admin API, and the model requests it relays), never its database. LiteLLM creates and migrates its tables itself (Prisma) at every start, as the owner of its database, and needs no extension and no shadow database.

The chart configures LiteLLM's connection the way it configures Tinfirm's (section 1): split values that fall back to Tinfirm's, further parameters, or one complete URL. The difference: LiteLLM's URL carries the password, so the chart takes a URL from a Secret only, never as a value (the schema rejects `litellm.database.url`). What follows was checked against the source of LiteLLM v1.105.1.

### 4.1 Credentials

The values are in the public guide's [Credentials from a Secret of their own](../../docs-site/content/operator-guide/database.md#credentials-from-a-secret-of-their-own). They become LiteLLM's variables: `litellm.database.secretKeys.password` is `DATABASE_PASSWORD`, `.username` is `DATABASE_USERNAME` (empty: `litellm.database.user`), and `.url` is `DATABASE_URL`, which replaces the split settings (section 4.3). Without `litellm.database.existingSecret`, `DATABASE_PASSWORD` comes from `litellm.existingSecret` (`tinfirm-litellm`), key `litellm.secretKeys.databasePassword`, with the master key and the salt key ([helm.md, section 1](helm.md#1-create-the-secrets)).

### 4.2 How LiteLLM builds its URL

From the split settings, LiteLLM builds `postgresql://<user>:<password>@<host>:<port>/<name>` and URL-encodes the user and the password itself, so the password may hold any character. The chart puts the port into `DATABASE_HOST`: when LiteLLM builds the URL from a password it ignores `DATABASE_PORT` (`construct_database_url_from_env_vars` in `litellm/proxy/utils.py`). `litellm.database.sslMode` is `DATABASE_SSLMODE`, and the CA certificate is mounted at `/etc/litellm/database-ca/<key>` as `DATABASE_SSLROOTCERT`.

LiteLLM connects through Prisma, not libpq, and rewrites the URL for it at startup (`run_server` in `litellm/proxy/proxy_cli.py`, `translate_libpq_ssl_params` in `litellm/proxy/db/db_url_settings.py`):

- It adds its pool settings, `connection_limit=10`, `pool_timeout=60` and `max_idle_connection_lifetime=60`.
- It turns `sslmode=verify-ca` or `verify-full` and the CA into Prisma's `sslmode=require&sslcert=<CA>&sslaccept=strict`. Prisma then checks the certificate chain and the host name in both modes. Prisma loads one certificate, so with a CA file of several roots LiteLLM probes the server once and keeps the root that verifies it.

The chart hands `litellm.database.params` to LiteLLM as `general_settings.database_extra_connection_params` in its ConfigMap, and LiteLLM appends them to the URL over its own defaults. They are Prisma's PostgreSQL connection arguments, for example `connection_limit`, `pool_timeout`, `connect_timeout`, `socket_timeout`, `max_idle_connection_lifetime`, `statement_cache_size` and `pgbouncer`; an `sslmode` among them would override `sslMode` and `caCertSecret`. `DATABASE_SCHEMA` is preferred over `schema` in `params` because LiteLLM adds it to the URL and also reads it to find its views.

### 4.3 A connection URL from a Secret

With `DATABASE_URL`, LiteLLM uses the URL as it is (so the password must be percent-encoded), translates its TLS parameters as in section 4.2, and sets `connection_limit` and `pool_timeout` over the URL's, keeping its other parameters. A URL without TLS parameters connects with Prisma's default, `prefer`: encrypted when the server offers TLS, never verified. The public guide's [A connection URL from a Secret](../../docs-site/content/operator-guide/database.md#a-connection-url-from-a-secret) has the values and an example.

### 4.4 Network

The public guide's [LiteLLM's network](../../docs-site/content/operator-guide/database.md#litellms-network) has the rule. With a URL from a Secret the chart cannot see the port, so `litellm.database.port` must name it: while `networkPolicy.egress.enabled` is on, the chart refuses to render without it (`templates/_helpers.tpl`). It does not guess Tinfirm's port instead, because a wrong guess shows only as a LiteLLM pod that cannot reach its database and fails its startup probe after 10 minutes.

### 4.5 Backups and checks

The public guide's [Back up](../../docs-site/content/operator-guide/database.md#back-up) and [The first start](../../docs-site/content/operator-guide/database.md#the-first-start) cover LiteLLM's database: back it up with Tinfirm's, with the same targets (section 3), and restore both from the same point in time, because Tinfirm keeps each principal's LiteLLM key, and the company's own, in its vault (package 2, [ADR-0065](../adr/0065-company-model-key.md)), and the two must match. The salt key (`litellm.secretKeys.saltKey`) is not in the database.
