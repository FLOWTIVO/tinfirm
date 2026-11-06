# ADR-0009: Tenant isolation with PostgreSQL row-level security

- Status: Accepted
- Date: 2026-09-26
- Sources: CONVENTIONS §6, §7, §10

## Context

- The company is the tenant boundary. Every entity has `company_id`.
- One missing filter in one query leaks another company's data.

## Decision

- **Two layers.** Repositories filter by `company_id`, and PostgreSQL row-level security (RLS) enforces it again.
- **Tenant tables.** Every table that belongs to a company has `company_id uuid NOT NULL`, and runs `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY`.
- **Audit tables** also have RLS, but their `company_id` is nullable, because null means the instance chain. Their policies are in CONVENTIONS §7 and [ADR-0010](0010-hash-chained-audit-log.md).
- **Two database roles.**
  - `tinfirm_owner` owns all objects and runs Flyway.
  - `tinfirm_app` is the runtime role. It owns nothing, because owners bypass RLS. Migrations grant it only what it needs.
- **Tenant context.** Each transaction sets these values with transaction-local `set_config(..., true)`:
  - `app.company_id`: the company of the request, or empty;
  - `app.user_id`: the caller;
  - `app.instance_admin`: `true` or `false`.
- **Policies** use `current_setting('app.company_id', true)` and deny when it is empty. The only exceptions are explicit cross-company reads: a user's own memberships, companies where the user is a member, and any row when `app.instance_admin = 'true'`.
- **API behaviour.** A non-member gets 404 on every company-scoped path, never 403.
- **Tests** run as the real `tinfirm_app` role. One tenant isolation test calls every company-scoped path in `api/openapi.yaml` as a member of company A against company B, and expects 404.

## Consequences

- A bug in one query does not expose another company, as long as the context is correct.
- The context is transaction-local, so connection pooling is safe.
- Every database access must run in a transaction that sets the context. Code that forgets it sees no tenant rows. The failure is safe but can confuse developers.
- `FORCE ROW LEVEL SECURITY` also filters `tinfirm_owner`. Data migrations must set the context or use a policy that allows them.
- The instance-admin clause gives instance admins every row. A bug that sets this flag wrongly exposes all companies (threat model, R4).
- An external database needs both roles set up by the operator ([operations/external-database.md](../operations/external-database.md)).
- A new company-scoped path is covered by the isolation test without extra work.

## Alternatives considered

- **Repository filtering only.** Rejected: tenant isolation needs a second enforcement in the database.
- **A schema or a database per company.** Not discussed. It would multiply migrations and make the cross-company reads for instance admins harder.
- **The application role as table owner.** Rejected: owners bypass RLS unless it is forced, and the owner can change audit rows (CONVENTIONS §7).
