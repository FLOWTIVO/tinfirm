# Log in with Microsoft Entra ID

The public guide is [Declare companies and how their people sign in](../../docs-site/content/operator-guide/sign-in.md) (https://docs.tinfirm.com/operator-guide/sign-in/), for operators: its worked setup "Microsoft Entra ID" has the app registration, the settings for Helm and Compose, the network and the rotation of the client secret. This page keeps what the people who build Tinfirm need beyond it: why Entra ID's claims and issuer behave as they do in the server, the codes and audit reasons of a refused login, and the threat model's entries on `TRUST_ISSUER`. The rules are [CONVENTIONS §5](../CONVENTIONS.md#5-authentication), [ADR-0026](../adr/0026-per-provider-oidc-email-trust.md) and [ADR-0075](../adr/0075-companies-and-sign-in-from-the-deployment.md); companies, other providers and the login's codes are in [companies-and-sign-in.md](companies-and-sign-in.md).

## 1. What is different about Entra ID

- **No `email_verified`.** Under the default email trust every first login fails with `/login?error=EMAIL_NOT_VERIFIED` and the audit reason `email_not_verified`; Entra ID needs `TRUST_ISSUER` (section 2).
- **Email claims.** `OidcEmailClaims` reads the claims in the order of `TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_EMAIL_CLAIMS` (`email,preferred_username,verified_primary_email` in `compose.entra.yaml`) and takes the first that holds an email address. `preferred_username` is the user principal name, which need not be a mailbox; `verified_primary_email` comes as a string or an array, of which the server takes the first email.
- **One tenant.** The multi-tenant issuers `common` and `organizations` publish a discovery document that names a different issuer (a template with `{tenantid}`), so Spring's issuer check fails and the server does not start.
- **Pairwise `sub`.** Entra ID's `sub` is unique per user and per app registration, and Tinfirm knows a user by `(issuer, sub)`: a new app registration makes every user a new identity, whose first login fails with `EMAIL_IN_USE`.
- **Variables.** Spring Boot maps `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` to the registration id in lower case, which is why ids are lower-case letters and digits only.
- **No front-channel logout.** Leave the app registration's front-channel logout URL empty: Tinfirm has no logout endpoint for the IdP to call ([threat model](../threat-model.md#53-tb3-server-and-oidc-idp), 3.6).

## 2. Email trust and the security note

What `TRUST_ISSUER` means for operators, and how to reduce the risk, is in the public guide under [Email trust and email claims](../../docs-site/content/operator-guide/sign-in.md#email-trust-and-email-claims) (the warning "What `TRUST_ISSUER` means") and in its worked setup for Entra ID.

For the people who build Tinfirm: with `TRUST_ISSUER` the tenant vouches for the email of each new user, so whoever controls a user's email attributes in the tenant (its administrators, guest invitations, the users themselves where allowed, and with `preferred_username` whoever can change a UPN) decides the email of that user's Tinfirm account. That person still cannot take over an existing account: Tinfirm never links an OIDC login to an existing user by email, and a taken email fails with `EMAIL_IN_USE` ([threat model](../threat-model.md#53-tb3-server-and-oidc-idp), 3.3). Only the configured claims are read, an explicit `email_verified=false` still refuses, and every other registration keeps `REQUIRE_VERIFIED_CLAIM`. The threat model records the residual risk as item 3.9 and accepted risk R21.

## 3. Check the login

`user.created` names in `emailClaim` the claim that gave the email (for Entra ID, which of `email`, `preferred_username` and `verified_primary_email`), beside `provider` and `companyId`; neither it nor `auth.login.succeeded` holds the email. The public guide's troubleshooting has every symptom in an operator's words; the codes behind them:

| Symptom | Code and audit reason |
|---|---|
| "Single sign-on failed" after Entra ID's page | `/login?error=OIDC_LOGIN_FAILED`, audit reason `oidc_error`: the token request failed, most often a wrong secret (the Secret ID instead of the value) or an expired one. Entra ID's sign-in logs show the `AADSTS` code |
| "Email address not confirmed" | `/login?error=EMAIL_NOT_VERIFIED`, audit reason `email_not_verified`: `TRUST_ISSUER` is not set for the registration |
| "No email address from your identity provider" | `/login?error=EMAIL_MISSING`, audit reason `email_missing`: no configured claim holds an email address |
| "This email is already in use" | `/login?error=EMAIL_IN_USE`, audit reason `email_in_use`: another user has the email, for example the same person's local account. An email cannot be changed |
| "Email domain not allowed for this sign-in" | `/login?error=EMAIL_DOMAIN_NOT_ALLOWED`, audit reason `email_domain_not_allowed` |
| "No role for you in this sign-in" | `/login?error=ROLE_NOT_MAPPED`, audit reason `role_not_mapped`: `..._ROLE_CLAIM=roles` is set and the person has none of the mapped app roles; assign one in the enterprise application |
