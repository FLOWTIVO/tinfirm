# ADR-0010: Hash-chained, append-only audit log

- Status: Accepted
- Date: 2026-09-26
- Sources: CONVENTIONS §5, §6, §7, §8

## Context

- The audit log is part of M0.
- The audit log needs an append-only table without `UPDATE` or `DELETE` grants, with a hash chain.
- Instance-level events (users, logins, the vault) do not belong to a company.

## Decision

- **Chains.** Each company has its own chain. The instance has one chain, with `company_id` null.
- **Ordering.** Each chain has a head row. A new event locks it with `SELECT … FOR UPDATE`. The head gives the next `seq` and `prevHash`.
- **Hash.** `hash = hex(sha256(prevHash + "\n" + canonical))`.
  - `canonical` is JSON with keys sorted recursively and no whitespace.
  - It contains `companyId`, `seq`, `occurredAt` (UTC, microseconds), `actor`, `action`, `targetType`, `targetId` and `details`.
  - The canonical `actor` is `{type, id}` only.
  - The first `prevHash` is 64 zeros.
- **Grants.** `tinfirm_app` has only `INSERT` and `SELECT` on `audit_event`. It has `SELECT`, `INSERT` and `UPDATE` on the chain-head table only, for the `FOR UPDATE` lock and to advance the head. It never has `DELETE` on either table.
- **Row-level security.** Both tables have RLS with a nullable `company_id`. Company rows are visible for that company and for instance admins. Instance-chain rows are visible only for instance admins. Inserts are allowed for the instance chain or for the company in the context.
- **Verification.** `GET /api/companies/{companyId}/audit-events/verification` recomputes a company's chain and returns the first invalid `seq`.
- **Content.** `details` never contains secret values or passwords. CONVENTIONS §8 lists the M0 actions.
- **Personal data stays out of the hashed fields.** The API resolves `actor.display` when it reads an event; a deleted user shows as `deleted user`. `details` holds IDs, roles and names of non-personal objects, never emails or display names. A failed login for an unknown email records `{"reason":"unknown_user"}` without the email.
- **Break-glass.** Instance admin access to a company that the admin is not a member of is audited as `admin.company.accessed`. A mandatory reason field comes in M1 (CONVENTIONS §6).

## Consequences

- The application role cannot change or delete events. A partial change by anyone else breaks the chain, and verification finds the first broken `seq`.
- A full rewrite by the database owner or a superuser is not detected: the hash has no key, so an attacker can recompute the chain. Removing the newest events leaves a valid, shorter chain. Both need an external anchor (threat model, R5 and Q6).
- Writes serialize per chain on the head row, so this lock bounds audit throughput per company.
- The application role can change the chain head. A forged head produces a break at the next event, so verification still finds it (threat model, 2.12).
- The canonical form is part of the contract. A change breaks verification of existing events.
- Erasure requests do not break the chain, because the hashed fields hold no personal data. A deleted user's events stay, and show the actor as `deleted user`.
- The server process holds the owner password, because it runs Flyway at startup. Code execution in the server can therefore bypass the grants (threat model, 2.9).

## Alternatives considered

- **An append-only table with grants only, no chain.** Not discussed. It gives no evidence when someone with more privileges changes rows.
- **One global chain for all companies.** Not discussed. A company could not verify its own log alone, and all writes would share one lock.
- **An external log service or WORM storage.** Not discussed. It would add a dependency to every installation.
