# Prepare PostgreSQL

This page prepares the PostgreSQL database that a Helm installation of Tinfirm runs on, and LiteLLM's database beside it: the roles and their SQL, TLS, the connection pools, backups and restores. It is for operators who run PostgreSQL themselves or on a managed service. The chart runs no database: [Install on Kubernetes with Helm](helm.md) assumes this page is done.

The Compose stack needs none of it: its PostgreSQL container creates the roles and both databases itself.

## What you need

| Item | Requirement |
|---|---|
| PostgreSQL | Version 18: a managed service, an operator in the cluster such as CloudNativePG, or your own server |
| Tinfirm's database | One database, `tinfirm` by default, encoding UTF8. No extension. |
| Roles | `tinfirm_owner` and `tinfirm_app`. The names are fixed: the migrations grant privileges to them. |
| LiteLLM's database | With the bundled LiteLLM, a second database, `litellm` by default, owned by a role of its own |
| Network | Reachable from the server's pods, and from LiteLLM's |
| TLS | Recommended, with the server's certificate verified |
| Backups | Continuous WAL archiving with point-in-time recovery |

## Why two roles

Tinfirm keeps each company's data apart with PostgreSQL's row-level security. The owner of a table can bypass it, so the server runs as a role that owns nothing:

| Role | Used by | Owns | Must not have |
|---|---|---|---|
| `tinfirm_owner` | The migrations, which the server runs as this role at every start | The database, the schema `public`, and every object the migrations create | `SUPERUSER`, `BYPASSRLS` |
| `tinfirm_app` | The running server | Nothing | `SUPERUSER`, `BYPASSRLS`, ownership of any object, membership in `tinfirm_owner` |

Never let the server run as `tinfirm_owner`: the owner can change the rows of the audit log, the runtime role cannot. The server checks its own role at every start: when it is a superuser, has `BYPASSRLS`, owns a relation in `public`, or is a member of `tinfirm_owner` or of another role that owns one there, the server stays not ready (its readiness probe answers `DOWN`) and logs one line that names the role and the condition. Fix the role, then restart the server. The server's pod receives both passwords, because it runs the migrations itself; only the migrations use the owner's. Restrict who can read the pod's Secret.

## Create the roles and the database

Run these statements as an administrative role: a superuser, or a role with `CREATEROLE` and `CREATEDB`. Use the passwords of the Secret `tinfirm-database` ([Create the Secrets](helm.md#create-the-secrets)).

```sql
CREATE ROLE tinfirm_owner WITH LOGIN PASSWORD '<owner password>'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE ROLE tinfirm_app WITH LOGIN PASSWORD '<app password>'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE DATABASE tinfirm
  OWNER tinfirm_owner
  ENCODING 'UTF8'
  TEMPLATE template0;
```

On a managed service your administrative role is usually not a superuser. PostgreSQL then requires it to be a member of `tinfirm_owner` to create a database that role owns: run `GRANT tinfirm_owner TO <admin role>;` before `CREATE DATABASE`, and `REVOKE tinfirm_owner FROM <admin role>;` after it.

### Restrict access

Connect to the new database `tinfirm` and run:

```sql
-- Only the owner and the app role may connect.
REVOKE ALL ON DATABASE tinfirm FROM PUBLIC;
GRANT CONNECT ON DATABASE tinfirm TO tinfirm_app;

-- The schema public: the owner, and USAGE for the app role.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO tinfirm_app;
```

Since PostgreSQL 15 the database's owner owns the schema `public`, so `tinfirm_owner` needs no further grant to create the migrations' objects there.

Grant `tinfirm_app` nothing else: no table privileges, and no `ALTER DEFAULT PRIVILEGES`. Each migration grants it exactly what it needs. A default privilege would give it `UPDATE` and `DELETE` on the audit log's tables, and the audit log depends on their absence.

## Create LiteLLM's database

The bundled LiteLLM keeps its keys, teams, budgets, spend, model list and the company's provider keys in a database of its own: on the same server as Tinfirm's by default, or on another. Skip this section with `litellm.enabled: false`.

Its role owns that database and nothing else. Neither Tinfirm role can connect to it, and it cannot connect to Tinfirm's: the server reaches LiteLLM only through its HTTP API.

```sql
CREATE ROLE litellm WITH LOGIN PASSWORD '<litellm password>'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE DATABASE litellm
  OWNER litellm
  ENCODING 'UTF8'
  TEMPLATE template0;

-- Only the owner may connect.
REVOKE ALL ON DATABASE litellm FROM PUBLIC;
```

As above, a non-superuser administrative role needs `GRANT litellm TO <admin role>;` before and `REVOKE litellm FROM <admin role>;` after. LiteLLM creates and migrates its tables itself at every start, and does not start when a migration fails. It needs no extension.

Unlike Tinfirm's, these names are yours to choose (`litellm.database.name`, `litellm.database.user`). A database operator that creates the database and its owner for you serves as well; then run the `REVOKE` yourself.

## Connect the chart to the database

### Passwords

The Secret `database.existingSecret` (`tinfirm-database`) holds both passwords, under `database.secretKeys.appPassword` (`app-password`) and `database.secretKeys.ownerPassword` (`owner-password`). The chart sets the user names itself: `tinfirm_app` for the server, `tinfirm_owner` for the migrations.

### Connection values

| Value | Default | Meaning |
|---|---|---|
| `database.host` | `postgres` | The database server |
| `database.port` | `5432` | Its port |
| `database.name` | `tinfirm` | The database |
| `database.sslMode` | empty | libpq's `sslmode`: `disable`, `allow`, `prefer`, `require`, `verify-ca` or `verify-full`. Empty means the driver's default, `prefer`. |
| `database.caCertSecret.name`, `.key` | empty, `ca.crt` | A Secret with the CA certificate of your database server |
| `database.params` | empty | More JDBC parameters, as `k=v&k=v` |
| `database.url` | empty | A complete JDBC URL, which replaces `host`, `port`, `name`, `sslMode` and `params` |

The chart builds `jdbc:postgresql://<host>:<port>/<name>?sslmode=…&sslrootcert=…&<params>`. The migrations use the same URL.

### TLS

Use `verify-full`: it checks the server's certificate and its host name. `require` encrypts, but does not check who answers.

With `verify-ca` or `verify-full`, set `database.caCertSecret`. The chart mounts the certificate read-only at `/etc/tinfirm/database-ca/<key>` and passes it as `sslrootcert`. Without it, the driver looks for a file in the home directory of the image's user, which has none.

```yaml
database:
  host: tinfirm-db.example.internal
  sslMode: verify-full
  caCertSecret:
    name: tinfirm-database-ca
  params: ApplicationName=tinfirm
```

```sh
kubectl -n tinfirm create secret generic tinfirm-database-ca --from-file=ca.crt=./ca.pem
```

With `database.url`, the chart still mounts the certificate, but adds neither `sslmode` nor `sslrootcert`: put both into the URL yourself.

### Network

With the network policy on, the server may reach any address on the database port (`networkPolicy.egress.database.port`, empty for `database.port`). Narrow `networkPolicy.egress.database.to` to your database's address range, or to a namespace and pod selector for a database in the cluster.

## Connect LiteLLM to its database

By default LiteLLM connects as `litellm.database.user` (`litellm`) to the database `litellm` on Tinfirm's server, with Tinfirm's TLS settings, and reads its password from the LiteLLM Secret (`litellm.secretKeys.databasePassword`, key `database-password`). LiteLLM encodes the user and the password into its connection URL itself, so the password may hold any character.

| Value | Default | Meaning |
|---|---|---|
| `litellm.database.host` | empty | Empty means `database.host` |
| `litellm.database.port` | empty | Empty means `database.port`. With a URL from a Secret, the port in that URL: required then. |
| `litellm.database.name` | `litellm` | |
| `litellm.database.user` | `litellm` | |
| `litellm.database.sslMode` | empty | Empty means `database.sslMode`. `verify-ca` and `verify-full` both check the certificate chain and the host name. |
| `litellm.database.caCertSecret.name`, `.key` | empty, `ca.crt` | Empty means `database.caCertSecret`. Only this key is mounted, at `/etc/litellm/database-ca/<key>`, so a Secret that also holds a CA's private key exposes only the certificate. A file with several root certificates, such as a cloud provider's regional bundle, works. |
| `litellm.database.params` | empty | More connection parameters, `k=v&k=v`, such as `connection_limit`, `pool_timeout` or `connect_timeout`. Written literally, without `%` escapes: the chart refuses `%`, `?` and spaces. They go into a ConfigMap: never a secret here, and set TLS with `sslMode`, not here. |

For a schema other than `public`, set `DATABASE_SCHEMA` in `litellm.extraEnv` rather than a `schema` parameter: LiteLLM also reads it to find its views.

### Credentials from a Secret of their own

To read the credentials from a Secret that a database operator or an external-secrets tool writes, name it in `litellm.database.existingSecret`:

| Value | Default | Meaning |
|---|---|---|
| `litellm.database.existingSecret` | empty | The Secret, in the release's namespace |
| `litellm.database.secretKeys.password` | `password` | The key of the password |
| `litellm.database.secretKeys.username` | empty | The key of the role's name; empty means `litellm.database.user` |
| `litellm.database.secretKeys.url` | empty | The key of a complete URL, below |

A pod reads only the Secrets of its own namespace: when the operator writes the Secret into another one, copy it into the release's namespace with your secret tooling. The chart refuses to render `secretKeys.username` or `secretKeys.url` without `existingSecret`.

CloudNativePG writes such a Secret for a cluster's application role, `<cluster>-app`, with the keys `username`, `password` and `uri`. Its read-write Service `<cluster>-rw.<namespace>.svc` is the host, and its `<cluster>-ca` the CA Secret.

```yaml
litellm:
  database:
    host: litellm-db.example.internal
    sslMode: verify-full
    caCertSecret:
      name: litellm-database-ca
    params: connection_limit=5&connect_timeout=10
    existingSecret: litellm-database
    secretKeys:
      username: username
      password: password
```

### A connection URL from a Secret

`litellm.database.secretKeys.url` names a key that holds a complete URL, `postgresql://<user>:<password>@<host>:<port>/<name>?<parameters>`. It replaces the host, name, user, TLS mode, parameters and password. There is no plain value for the URL, because it carries the password.

- The password must be percent-encoded in the URL.
- Put `sslmode=verify-full&sslrootcert=/etc/litellm/database-ca/ca.crt` into it: the chart mounts the CA certificate but adds nothing to the URL. Without them the connection is encrypted when the server offers TLS, and never verified. If your operator's URL has none and you cannot change it, use its user and password keys with the split values instead.
- LiteLLM sets its pool, `connection_limit=10` and `pool_timeout=60`, over the URL's. To change them, use the split values with `params`.
- Set `litellm.database.port` to the port in the URL: the chart cannot read the URL, and refuses to render LiteLLM's network policy without the port.

```yaml
litellm:
  database:
    port: 5432                          # the port in the URL, for the network policy
    caCertSecret:
      name: litellm-database-ca         # mounted at /etc/litellm/database-ca/ca.crt
    existingSecret: litellm-database
    secretKeys:
      # postgresql://litellm:<encoded password>@litellm-db.example.internal:5432/litellm
      #   ?sslmode=verify-full&sslrootcert=/etc/litellm/database-ca/ca.crt
      url: uri
```

### LiteLLM's network

LiteLLM's network policy allows the peers of `networkPolicy.egress.database.to` on LiteLLM's database port. When its database is elsewhere, for example in the cluster while Tinfirm's is a managed service, add a rule in `networkPolicy.litellm.egress.extra`.

## Size the connections

Count every connection against the database server's `max_connections`:

| Who | Connections | Set with |
|---|---|---|
| Each server pod | Up to 10, its pool | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` in `extraConfig` |
| The migrations, in each server pod while it starts | A few more, as `tinfirm_owner` | |
| Each LiteLLM pod | Up to 10, its pool | `connection_limit` in `litellm.database.params` |
| You | Your administrative sessions, backups and monitoring | |

During an upgrade, a new server pod starts before the old one stops, so count one server pod more than `replicaCount`. With the defaults, one server pod and one LiteLLM pod on the same database server: 2 × (10 + a few) for the server during an upgrade, 10 for LiteLLM, plus PostgreSQL's `superuser_reserved_connections` and your own sessions. A server with `max_connections` of 100 has room to spare.

### Connection poolers

If you put PgBouncer or another pooler in front of PostgreSQL, use session pooling, or let the migrations connect directly with `SPRING_FLYWAY_URL`. The migrations take a session-level advisory lock, which transaction pooling breaks; transaction pooling is not tested. The server sets the company context of each transaction locally, so it never leaks between pooled connections.

## The first start

At every start, the server migrates the database as `tinfirm_owner`: it creates or upgrades the schema, turns on and forces row-level security on every company table and on the audit log, and grants `tinfirm_app` what it needs. Then it connects as `tinfirm_app`. Pods that start together migrate once: the migrations hold a lock in the database. The chart's startup probe allows 5 minutes for the first migration of an empty database.

After the first start, check the result as an administrative role:

```sql
-- 1. No superuser, no BYPASSRLS: false in every column
SELECT rolname, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb
FROM pg_roles
WHERE rolname IN ('tinfirm_owner', 'tinfirm_app', 'litellm');

-- 2. tinfirm_app owns nothing: 0
SELECT count(*)
FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
WHERE r.rolname = 'tinfirm_app';

-- 3. Row-level security on every company table: true, true
SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'company_id' AND NOT a.attisdropped
WHERE c.relkind IN ('r', 'p') AND n.nspname = 'public'
ORDER BY 1;

-- 4. Neither side can connect to the other's database: false, false
SELECT has_database_privilege('tinfirm_app', 'litellm', 'CONNECT'),
       has_database_privilege('litellm', 'tinfirm', 'CONNECT');
```

In query 3, one table shows `false` and `false` by design: `oauth_authorization_request`, which holds a person's pending sign-in to a system for ten minutes and is read before any company is known.

## Back up

Use continuous WAL archiving with point-in-time recovery: your managed service's, CloudNativePG's backups, or a tool such as pgBackRest. Archive WAL at least every 15 minutes, for example with `archive_timeout`, so that a quiet instance loses at most that much. Back up LiteLLM's database with Tinfirm's, so that both can be restored to the same point in time: Tinfirm's vault holds each person's LiteLLM key, and the two must match.

A backup holds everything of the companies, so encrypt it and restrict who reads it. Among other things it holds:

- the session ids of signed-in people, in clear text: whoever reads them can use a live session until it ends;
- the hashes of local passwords;
- the encrypted secrets of the vault, with their wrapped data keys;
- the audit log;
- in LiteLLM's database, the provider keys, encrypted with the salt key, and the spend logs: who used which model, the tokens and the cost, never prompts or answers.

### The keys that are not in the database

The vault's master keys (`tinfirm-vault`) and LiteLLM's salt key (`litellm.secretKeys.saltKey`) are not in either database. A restored database is useless for secrets without the master keys that wrapped its data keys, and a restored LiteLLM database cannot decrypt its provider keys without the salt key. Keep both in your secret manager, outside the cluster.

When you rotate the vault's master key, the live database stops using the old key once you rewrap ([Rotate the vault master key](maintenance.md#rotate-the-vault-master-key)), but older backups still need it. Keep an old master key until no backup you might restore was taken before the rewrap.

## Restore

1. Stop the server and LiteLLM: `kubectl -n tinfirm scale deployment/tinfirm deployment/tinfirm-litellm --replicas=0`.
2. Restore Tinfirm's and LiteLLM's databases to the same point in time, with your backup tool.
3. Check that the vault Secret holds every master key the backup's data keys were wrapped with, and that the LiteLLM Secret holds the same salt key.
4. Start them again with the replica counts of your values: `helm upgrade` with your values file, or `kubectl scale`.

Then:

- **Record the restore outside the database.** A restore to a point in time removes the newest events of the audit log. What remains is still a valid chain, so its verification cannot tell a restore from a removed tail.
- **End the restored sessions** if you need to. Sessions from the backup are valid again until they time out. To end them all at once, run `DELETE FROM spring_session;` in Tinfirm's database as `tinfirm_owner`: everybody signs in again.

## Checklist

- [ ] PostgreSQL 18, database encoding UTF8.
- [ ] `tinfirm_owner` owns the database. Neither role is a superuser or has `BYPASSRLS`.
- [ ] `tinfirm_app` has only `CONNECT` on the database and `USAGE` on the schema `public`, no default privileges, and no membership in `tinfirm_owner`.
- [ ] `PUBLIC` has no privileges on either database or on the schema `public`.
- [ ] Only the operators can read the Secret with the passwords.
- [ ] TLS with `verify-full` and a CA certificate, for Tinfirm and for LiteLLM.
- [ ] `networkPolicy.egress.database.to` is narrowed to the database.
- [ ] `max_connections` covers the pools, the migrations, one extra server pod during upgrades, and your own sessions.
- [ ] Point-in-time recovery is on, with WAL archived at least every 15 minutes, for both databases.
- [ ] The vault's master keys and LiteLLM's salt key are kept outside the cluster, and old master keys stay while a backup needs them.
- [ ] A restore was tried, and the four checks above give the expected results.
