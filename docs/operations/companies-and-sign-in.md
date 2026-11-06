# Companies and their sign-in

The public guide is [Declare companies and how their people sign in](../../docs-site/content/operator-guide/sign-in.md) (https://docs.tinfirm.com/operator-guide/sign-in/), for operators. This page keeps what the people who build Tinfirm need beyond it: what the start does inside (its lock, its audit events, why another company's provider never makes an owner), the Enterprise limit on declared companies and where the code applies it, the login's error codes and audit reasons, what the migration from providers configured in the app removed, and the Compose overlays used in development. The decision is [ADR-0075](../adr/0075-companies-and-sign-in-from-the-deployment.md); the rules are [CONVENTIONS §4 and §5](../CONVENTIONS.md#5-authentication) and [ADR-0026](../adr/0026-per-provider-oidc-email-trust.md); the editions are [ADR-0077](../adr/0077-licence-editions-and-seats.md).

## 1. Declare the companies

**One operator for all the companies of an instance.** The companies of one instance are declared by one operator, who vouches for all their admins: the companies of one group, or a company and its test companies. Never host companies that do not trust each other's admins on one instance; give each customer an instance of its own. The design relies on it ([threat model §2.2](../threat-model.md#22-runner-hosts-and-the-companys-systems), R59 to R62): a user is one row of the instance; an OWNER or ADMIN of any company adds any user by email, without their consent, and learns whether an address has an account (`USER_NOT_FOUND`); a company's SCIM token adopts any user by email and reads their name; a SAML provider without `_ENTITY_ID` takes its IdP from its first metadata document. The checks that keep one company's IdP from taking over another's users are built as hardening, not as a boundary: a first sign-in binds only a user its own company's SCIM created and never an instance admin, a company's SCIM deactivates on the instance only users it created, a SAML provider's entity ID is pinned, and the server does not start while two SAML providers reading different metadata name one IdP, a SAML provider names an OIDC issuer, or an OIDC registration has no `issuer-uri` or no `openid` scope.

### 1.1 What the start does

`DeclaredCompaniesService.apply` runs at every start, after the bootstrap admin exists, as actor SYSTEM, in one transaction under a PostgreSQL advisory lock, so that replicas starting together apply the declarations once. It goes through them in key order: a company with the key stays as it is; otherwise it is adopted (section 1.2) or created without members (`company.created` by SYSTEM with the name and the key).

While a company has no OWNER, its declared owner becomes it: `member.added`, or `member.role.changed` when they were a member already, by SYSTEM with `declaredOwner: true`, under the company's lock, so that two of these never both add an owner. At the start that must be an active **local** user, since an OIDC user's email is only what some provider asserted. After a sign-in, in a transaction of its own after the login's commit (a failure there never fails the login), the user becomes the OWNER of each declared company that names their email and has none, when they signed in locally or through a provider bound to that company whose login asserts the email itself. A provider bound to another company, or to none, asserts the emails of the users it creates, so the company that trusts it is not this one: it never claims the ownership.

A company that no declaration names stays, and the start logs a warning that names it.

**Several companies need Enterprise** ([ADR-0077](../adr/0077-licence-editions-and-seats.md) decision 4). A declared company beyond the first needs an Enterprise licence whose key covers it, up to the key's cap (`LicenceStatus.companiesLicensed`, null for no cap). Until then it is not created: it waits, and the start logs `Declared companies wait for a licence that holds them` with the keys. The companies that exist count first, whatever their keys' order, so that a new one never takes their place; a declaration that adopts a company from before keys adds none and never waits. When another key comes into force, `LicenceChanged` applies the declarations again, without a restart. Companies that already exist keep working whatever the licence (decision 7.3).

### 1.2 Companies from before keys

The adoption is audited as `company.updated` with `key` and `adoptedBy` (`id` or `name`). Before ADR-0075 the bootstrap's company took its name from the Helm value `bootstrap.companyName`, which is no longer read ([helm.md, section 6](helm.md#6-upgrades)).

## 2. Add the provider to the deployment

### 2.1 Docker Compose

In development, `compose.oidc.yaml` adds the dev Keycloak (profile `oidc`, realm `tinfirm` from `deploy/compose/keycloak/tinfirm-realm.json`, `make up-oidc`) and binds its provider to the company `main`, as `compose.entra.yaml` binds Entra ID ([development.md](../development.md)). `deploy/compose/compose.companies.example.yaml` declares a second company, `acme`, with a provider of its own: the public guide's example.

## 3. Email trust and email domains

The email trust is ADR-0026's; for Entra ID, which needs `TRUST_ISSUER`, the security note is [entra-id.md, section 2](entra-id.md#2-email-trust-and-the-security-note).

An email outside a provider's domains is refused with `/login?error=EMAIL_DOMAIN_NOT_ALLOWED`, audited in the instance chain as `auth.login.failed` with the reason `email_domain_not_allowed`, and no user is created. `ExternalProvisioning` checks the domains before it checks whether the email is taken, so that a refused login does not tell whether an address has an account.

## 4. The direct link and the first sign-in

What a first sign-in records: in the instance's audit log, `user.created` with `provider` (the registration id), `emailClaim` and `companyId`, and `auth.login.succeeded` with `provider`, never the email; in the company's, `member.added` by SYSTEM with `provider`.

How the login page's messages map to the codes `OidcLoginFailureHandler` redirects with, and to the reasons of `auth.login.failed`:

| Message | `/login?error=` | Audit reason |
|---|---|---|
| "This email is already in use" | `EMAIL_IN_USE` | `email_in_use` |
| "No email address from your identity provider" | `EMAIL_MISSING` | `email_missing` |
| "Email address not confirmed" | `EMAIL_NOT_VERIFIED` | `email_not_verified` |
| "Email domain not allowed for this sign-in" | `EMAIL_DOMAIN_NOT_ALLOWED` | `email_domain_not_allowed` |
| "No role for you in this sign-in" | `ROLE_NOT_MAPPED` | `role_not_mapped` (a provider that decides roles maps the person to none; the user when known) |
| "Single sign-on failed" | `OIDC_LOGIN_FAILED` | `oidc_error` (the provider's flow failed, most often the token request), `not_provisioned` (auto-provisioning off), `inactive` (a deactivated user), `invalid_email` |

### 4.1 The role from the provider (ADR-0088)

A provider with `_ROLE_CLAIM` decides roles (`IDP`, unless `_ROLE_SOURCE=APP`); the public guide is [Who decides a member's role](../../docs-site/content/operator-guide/sign-in.md#who-decides-a-members-role). Inside:

- `ProviderRoleSources` checks the settings while the context starts (`AuthProperties.Oidc.verifyRoleSources`): each refusal names the variable, and two deciding providers of one company's key (the provider's `_COMPANY`, else the only declared company, and each key of its `_COMPANIES_*`) stop the start. It logs the providers without a claim once.
- At a sign-in through a deciding provider, `ExternalProvisioning` maps the merged claims of the ID token and the userinfo with `ProviderRoles.mapped` for the provider's own company and `ProviderRoles.RoleMap.mapped` for each company of `_COMPANIES_<KEY>_*` (ADR-0089), which `ProviderCompanies.resolve` turned into company ids from the deployment before the sign-in. `ExternalProvisioning.applyClaim` then goes through the companies: a mapped role goes through `ProvisionedMemberships.applyProviderRole`, which the company module's `MembershipRoles.apply` serves (OWNER and `SCIM` are `KEPT`; a non-member is `NOT_A_MEMBER`, and the membership is granted with the role source `IDP:<registration id>`); no mapped role for a member is `ProvisionedMemberships.leave`, a departure through `MemberService.leave` with the provider in `member.removed` (OWNER and `SCIM` kept). When the claim maps nothing in any company, nothing changes and the login is refused (`NOT_MAPPED`) before it records the login, unless an OWNER's or SCIM's membership keeps the person in. `ProvisionedMembershipsImpl` enters each company's tenant context (`TenantScope.enterCompany`) before it writes, inside the login's transaction, so the audit log appends each event under its company.
- Once the server is ready, `releaseProviderRoles` makes every `IDP:<id>` membership whose provider no longer decides that company's roles `APP` (`MembershipRoles.release`, `member.role_source.changed` with `count`), company by company in its own tenant context. Two replicas starting together release each row once: the second finds none left and audits nothing.
- `company_membership_owner_source_ck` holds that an OWNER's role source is `APP`; `MembershipEntity` sets it so whenever the role becomes OWNER.

## 5. From a provider configured in the app

For operators, the move has two steps. The deployment adopts the company by its exact name, or by its id in `TINFIRM_COMPANIES_<KEY>_ID` (Helm `companies[].id`; [the configuration reference](../../docs-site/content/reference/configuration.md#bootstrap-and-companies); section 1.2). It declares the provider again, with the same issuer and the same client registration at the provider ([Add the provider to the deployment](../../docs-site/content/operator-guide/sign-in.md#add-the-provider-to-the-deployment)).

Before ADR-0075, a company's OWNER could configure the company's own provider on **Settings > Sign-in**, with the registration id `company-<companyId>`. The upgrade's migration, `V052__companies_from_the_deployment.sql`, deletes those providers (`company_identity_provider`), their email domains (`company_identity_provider_domain`) and their client secrets in the companies' vaults, and drops `app_user.provisioned_by_company_id`: an account such a provider created no longer belongs to its company, and any company may add it as a member. People keep their accounts across the move because Tinfirm knows a person by `(issuer, sub)`; the same issuer and the same client registration at the provider give the same pair. The rules of the removed feature are in [archive/v2/CONVENTIONS.md](../archive/v2/CONVENTIONS.md#1412-companies-own-identity-providers).
