# Allow SCIM for a company

SCIM lets a company's identity provider add, change and remove the company's members ([Provision people with SCIM](../admin-guide/scim.md)). It needs an Enterprise licence and your yes for each company, since it creates users of the instance. The company's Owner then issues the token in the app.

## Allow it

| Helm (`companies[]`) | Environment variable | Meaning | Default |
|---|---|---|---|
| `scim.enabled` | `TINFIRM_COMPANIES_<KEY>_SCIM_ENABLED` | The company serves SCIM | `false` |
| `scim.role` | `TINFIRM_COMPANIES_<KEY>_SCIM_ROLE` | A new member's role when the provider sends none: `ADMIN`, `MEMBER` or `VIEWER` | `MEMBER` |
| `scim.roleSource` | `TINFIRM_COMPANIES_<KEY>_SCIM_ROLE_SOURCE` | `IDP`: the provider decides roles whenever it sends one, and they are not changed in the app. `APP`: the provider gives a role once, at creation | `IDP` |

Changing `scim.enabled` to `false`, or `scim.roleSource` to `APP`, takes effect at the next start: the roles SCIM decided become the app's, keeping their values.

The endpoint is `{TINFIRM_PUBLIC_URL}/scim/v2`, one for the instance: the token names the company. Route `/scim/` to the server as you route `/api/`. Entra ID and Okta call it from their clouds, so it must be reachable from the internet, unless the company uses their on-premises provisioning agents.

## Sign-in of provisioned people

A person SCIM created signs in through the company's OIDC provider, and their first sign-in is bound to their account by the id the provider sends in both. Two settings of each OIDC registration:

| Environment variable | Meaning | Default |
|---|---|---|
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_AUTO_PROVISION` | Whether an unknown person gets a new account at their first sign-in. Set `false` for a company provisioned by SCIM, so that only the people its identity provider assigned sign in. | `TINFIRM_AUTH_OIDC_AUTO_PROVISION` |
| `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_SCIM_MATCH` | `EXTERNAL_ID`: bind by the provider's id only. `EXTERNAL_ID_OR_EMAIL`: also by the email the sign-in asserts, for a provider that cannot send the same id in both | `EXTERNAL_ID` |

No sign-in is ever bound to an Owner's account.

## Limits

| Setting | Meaning | Default |
|---|---|---|
| `TINFIRM_SCIM_REQUESTS_PER_MINUTE` | A company's SCIM requests a minute, per replica; beyond, `429` with `Retry-After`, which Entra ID and Okta honour | `600` |
| `TINFIRM_SCIM_REFUSALS_PER_MINUTE` | Refused tokens a minute from one client address, per replica | `20` |
