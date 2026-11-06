# ADR-0026: Per-provider trust of the OIDC email claim

- Status: Accepted
- Date: 2026-10-03
- Sources: CONVENTIONS §4, §5, §8; [ADR-0008](0008-bff-session-auth.md); threat model 3.7, 3.9, R7, R21

## Context

- [ADR-0008](0008-bff-session-auth.md) creates a new OIDC user only if the IdP asserts `email_verified=true`.
- Microsoft Entra ID does not send `email_verified`. With ADR-0008 as it stands, no Entra ID user can be provisioned.
- Entra ID does not always send `email` either. It is an optional claim for members of the tenant. `preferred_username` usually holds the user principal name, which often looks like an email address but need not be one. Some tenants send `verified_primary_email`, sometimes as an array.
- `email_verified` speaks for the `email` claim only. It says nothing about `preferred_username` or other claims.
- The identity rules of ADR-0008 must stay: identity by `(issuer, subject)`, no linking by email, auto-provisioning governed by `TINFIRM_AUTH_OIDC_AUTO_PROVISION`.

## Decision

- **Email trust per registration.** `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST` (property `tinfirm.auth.oidc.providers.<id>.email-trust`), where `<id>` is the Spring client registration id:
  - `REQUIRE_VERIFIED_CLAIM`, the default: the email comes from the `email` claim, and the IdP must assert `email_verified=true`. This is the ADR-0008 rule.
  - `TRUST_ISSUER`: the operator trusts the issuer to assert the email. A missing `email_verified` does not block provisioning. An explicit `email_verified=false` still does.
- **Email claims per registration.** `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS` lists the claims that may carry the email, in order. Default: `email`. The first claim whose value looks like an email address wins. An array claim gives its first value that looks like an email address. For Entra ID the order is `email,preferred_username,verified_primary_email`.
- **Claims other than `email` need `TRUST_ISSUER`.** The server does not start otherwise, because `email_verified` cannot vouch for them.
- **Settings name real registrations.** The server does not start when `<id>` names no client registration. A misspelt id would otherwise leave that provider on the default without a sign.
- **A missing or unverified email has its own code.** When no configured claim holds a usable email, the first login fails with `EMAIL_MISSING` (`/login?error=EMAIL_MISSING`) and the audit reason `email_missing`. When the email trust does not accept the email, it fails with `EMAIL_NOT_VERIFIED` (`/login?error=EMAIL_NOT_VERIFIED`) and the audit reason `email_not_verified`; for Entra ID this is the sign of a missing `TRUST_ISSUER`. Every other refusal keeps its code.
- **Unchanged:** identity by `(issuer, subject)`; no linking by email (`EMAIL_IN_USE`); `TINFIRM_AUTH_OIDC_AUTO_PROVISION`; login events without the email. `user.created` records the name of the claim the email came from (`emailClaim`), never the value.

## Consequences

- Entra ID works with two settings, and every other provider keeps the ADR-0008 behaviour unless the operator opts in.
- With `TRUST_ISSUER`, whoever can set a user's email attributes at the IdP decides the email of a new Tinfirm user: tenant admins, guest invitations, and users themselves where the IdP lets them edit the attribute. Such a user cannot take over an existing account, because there is no linking by email. An OWNER who later adds that email as a member gives that user access (threat model 3.9, R21). Operators use `TRUST_ISSUER` only for single-tenant issuers they control.
- `preferred_username` and `verified_primary_email` are only read when the operator lists them.
- The email check is a shape check (`local@domain`), not a deliverability check.
- Two more problem codes (`EMAIL_MISSING`, `EMAIL_NOT_VERIFIED`) for the SPA and the API description.

## Alternatives considered

- **A global switch that ignores `email_verified`.** Rejected: it would weaken every provider to fix one.
- **Treat a missing `email_verified` as true for every provider.** Rejected: the same reason, and it changes the behaviour silently.
- **Look up the email in Microsoft Graph.** Rejected: Graph-specific code, a further permission, and the same trust in the tenant.
- **A fixed Entra ID claim order whenever `TRUST_ISSUER` is set.** Rejected: an IdP that lets users choose their username (for example Keycloak with editable usernames) would then let users choose an email-shaped `preferred_username`. The operator lists the claims explicitly instead.
- **Link an Entra ID login to an existing local account by email.** Rejected: ADR-0008 forbids linking by email (threat model 3.3).
