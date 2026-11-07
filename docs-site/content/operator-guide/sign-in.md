# Declare companies and how their people sign in

This page is for the operator of a Tinfirm instance. It shows how the deployment declares the instance's companies and their first Owners, how people sign in with a password, and how to connect an OpenID Connect provider, such as Keycloak, Okta or Microsoft Entra ID, to a company.

Companies and sign-in are part of the deployment's configuration: the Helm values, or the environment of Docker Compose. Nobody creates a company or sets up an identity provider in the app. A company's Owner who wants another company, another provider or other email domains asks you. Everything else about a company stays in the app: its name after it is created, its members and their roles, its systems, agents and triggers.

## What the deployment decides

| What | Helm | Compose and other environments | Takes effect |
|---|---|---|---|
| The companies, each by a key | `companies` | `TINFIRM_COMPANIES_<KEY>_*` | At every start of the server |
| Each company's first Owner | `companies[].owner` | `TINFIRM_COMPANIES_<KEY>_OWNER` | At every start, and when that person signs in |
| Sign-in with a password | `auth.localEnabled` | `TINFIRM_AUTH_LOCAL_ENABLED` | At the start |
| The identity providers, one button each on the login page | `auth.oidc.providers` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` | At the start |
| The company and role a new person from a provider gets | `company`, `role` of the provider | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANY`, `_ROLE` | At the start |
| Which emails a provider may create accounts for | `emailDomains` of the provider | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_DOMAINS` | At the start |
| Who decides a member's role: the provider at every sign-in, or the app | `roleClaim`, `roleMap`, `roleSource` of the provider | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_CLAIM`, `_ROLE_MAP_*`, `_ROLE_SOURCE` | At every sign-in through the provider |
| Further companies one provider fills from the same claim | `companies` of the provider | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANIES_<KEY>_ROLE_MAP_*` | At every sign-in through the provider |

Every change is a change of the deployment and a restart of the server.

## Declare the companies

**One operator vouches for every company of an instance.** Declare on one instance only companies whose admins trust each other, such as the companies of one group, or a company and its test companies. Never host companies that do not trust each other on one instance: give each its own. A person is one account on the instance: an Owner or Admin of any company can add any account by email and learns whether an address has one, and a company's SCIM can take in any account by email.

A company has a **key**: 1 to 40 lower-case letters and digits, starting with a letter, such as `main` or `acme`. The key identifies the company for good: never change it. The server does not start with a key of another shape.

| Setting | Helm (`companies[]`) | Variable | Meaning |
|---|---|---|---|
| Key | `key` | the `<KEY>` of the variables | Required |
| Name | `name` | `TINFIRM_COMPANIES_<KEY>_NAME` | Required, 1 to 100 characters. Used only when the company is created: a rename in the app stays. |
| Owner | `owner` | `TINFIRM_COMPANIES_<KEY>_OWNER` | The email of the person who becomes Owner while the company has none. Empty: the bootstrap admin (`TINFIRM_BOOTSTRAP_ADMIN_EMAIL`). |

=== "Helm"

    ```yaml title="values.yaml"
    companies:
      - key: main
        name: Acme
        owner: alice@example.com
    ```

    The chart's default is one company, `main`, named `My Company`, owned by the bootstrap admin. It refuses to render a key of another shape, the same key twice, or a company without a name.

=== "Docker Compose"

    `compose.yaml` declares one company, `main`, from two values of `deploy/compose/.env`:

    ```sh title="deploy/compose/.env"
    TINFIRM_COMPANY_NAME="Acme"
    # Empty: the bootstrap admin
    TINFIRM_COMPANY_OWNER=alice@example.com
    ```

    For more companies, add an overlay file beside `compose.yaml`. `deploy/compose/compose.companies.example.yaml` declares a second company, `acme`, with a provider of its own: copy it, rename the key and the provider, and start the stack with it:

    ```sh
    docker compose -f deploy/compose/compose.yaml -f deploy/compose/my-companies.yaml up -d
    ```

### What each start does

At every start, one server replica at a time goes through the declared companies in key order:

1. A company with the key exists: nothing changes.
2. Otherwise the company is created, without members. Its audit log shows `company.created` by SYSTEM, with the name and the key.
3. While the company has no Owner, the active user whose email is the declared owner becomes its Owner. At the start, that must be a local account: one an instance admin made, the bootstrap admin among them. When there is none yet, the company waits without an Owner. The person becomes its Owner when they sign in with a password, or through a provider bound to this company that asserts their email. A sign-in through another company's provider never makes them its Owner.

A company that has an Owner is never changed by this: an Owner the company replaced does not come back. Nothing is ever deleted either. A company that no declaration names keeps working, and the start logs a warning that names it.

The bootstrap admin owns every declared company that names no owner. Owners can read their people's conversations in full, so on an instance with several companies, name each company's Owner.

### Several companies

!!! note "Enterprise"

    More than one company in an instance needs an Enterprise licence whose key covers them: [Editions and prices](../admin-guide/editions.md). Until then, a declared company beyond the first is not created: it waits, and the start logs a warning that names it. Tinfirm creates it as soon as a key that covers it comes into force, without a restart. A company that exists keeps working whatever the licence, and counts first.

A person who works for two companies gets an account from one provider; the other company's Owners or Admins add them as a member, or one provider fills both companies from its groups: [One provider for several companies](#one-provider-for-several-companies).

## Sign in with a password

While `TINFIRM_AUTH_LOCAL_ENABLED` is `true` (the default; Helm `auth.localEnabled`), the login page shows **Email**, **Password** and **Sign in**. Local accounts are made by an instance admin on **Users** with **New user**, with an initial password of at least 12 characters, which the person changes on their **Profile**. On the first start, while the instance has no users, the server creates the bootstrap admin from `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` and `TINFIRM_BOOTSTRAP_ADMIN_PASSWORD`.

The login allows 5 attempts a minute per email and 30 a minute per address, on each replica; beyond them it answers **Too many sign-in attempts. Wait a minute, then try again.**

Turn local sign-in off once everyone signs in through a provider. With neither, the page says **No sign-in method is configured on this instance.**

A session ends after 8 hours without use, and after 7 days at most: `TINFIRM_SESSION_IDLE_TIMEOUT` and `TINFIRM_SESSION_MAX_AGE` (Helm `session.idleTimeout`, `session.maxAge`), as ISO-8601 durations.

## Sign in with an OIDC provider

### Register Tinfirm at the provider

Pick a **registration id** for the provider: lower-case letters and digits, such as `entra`, `keycloak` or `acmesso`. Use the same id everywhere: in the redirect URI, the Helm entry, and the `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` and `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_*` variables. The redirect URI is always

```text
https://tinfirm.example.com/login/oauth2/code/<id>
```

with your `TINFIRM_PUBLIC_URL` in front (Helm `publicUrl`). It must match exactly.

At the provider, register a **confidential web application** with the authorization code flow:

- Tinfirm always sends PKCE (`S256`) and authenticates with the client secret (`client_secret_basic`). Client certificates are not supported.
- It asks for the scopes `openid`, `profile` and `email`.
- It needs `sub` and an email ([below](#email-trust-and-email-claims)) in the ID token or the userinfo answer. The display name comes from `name`, else `preferred_username`, else the part of the email before the `@`.

### Add the provider to the deployment

=== "Helm"

    Create the Secret with the client secret, from a file, so that it stays out of your shell history:

    ```sh
    kubectl -n tinfirm create secret generic tinfirm-oidc-acmesso \
      --from-file=client-secret=./acmesso-client-secret
    ```

    ```yaml title="values.yaml"
    auth:
      oidc:
        providers:
          - id: acmesso
            name: Acme                  # the button: "Continue with Acme"
            issuerUri: https://sso.example.com/realms/acme
            clientId: tinfirm
            existingSecret: tinfirm-oidc-acmesso
            company: main
            emailDomains: [example.com]
            roleClaim: groups          # the provider decides the role: see below
            roleMap:
              admin: [tinfirm-admins]
              member: [tinfirm-users]
              viewer: [tinfirm-viewers]
    ```

    `scope` defaults to `openid,profile,email` and `secretKeys.clientSecret` to `client-secret`. The chart refuses a provider whose `company` is not a key of `companies`, and its install notes print each provider's redirect URI to register. Its NetworkPolicy lets the server reach every provider on port 443 while `auth.oidc.providers` is not empty (`networkPolicy.egress.oidc`); narrow `to` when your issuer has a stable address range.

=== "Docker Compose"

    Put the provider's variables in an overlay file, as `deploy/compose/compose.companies.example.yaml` does, and keep the client secret in `deploy/compose/.env`:

    ```yaml title="deploy/compose/my-sso.yaml"
    services:
      server:
        environment:
          SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ACMESSO_CLIENT_ID: tinfirm
          SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ACMESSO_CLIENT_SECRET: ${TINFIRM_ACME_CLIENT_SECRET:?is not set in deploy/compose/.env}
          SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ACMESSO_CLIENT_NAME: Acme
          SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ACMESSO_AUTHORIZATION_GRANT_TYPE: authorization_code
          SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ACMESSO_SCOPE: openid,profile,email
          SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_ACMESSO_ISSUER_URI: https://sso.example.com/realms/acme
          TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_COMPANY: main
          TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_EMAIL_DOMAINS: example.com
          TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_ROLE_CLAIM: groups
          TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_ROLE_MAP_ADMIN: tinfirm-admins
          TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_ROLE_MAP_MEMBER: tinfirm-users
          TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_ROLE_MAP_VIEWER: tinfirm-viewers
    ```

    `make up-oidc` starts the stack with a local Keycloak instead, for trying single sign-on on your machine. Its realm sends the user's groups, and the test user alice is in `tinfirm-users`.

The server reads each provider's discovery document when it starts, and does not start while an issuer is unreachable or names another issuer. Nor does it start on a registration without an issuer URL (`ISSUER_URI`), whose ID tokens' issuer nothing would check, or whose scopes lack `openid`, whose sign-in would skip Tinfirm's checks. A firewall or egress proxy in front of the server must allow each issuer, and the hosts of its token, signing-key and userinfo endpoints.

### The company and role of a new person

| Setting | Helm | Variable | Default |
|---|---|---|---|
| Company | `company` | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANY` | The only company, when exactly one is declared; with several, none, and new people join no company |
| Role | `role` | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE` | `MEMBER`. `ADMIN` and `VIEWER` are allowed. Never `OWNER`: Owners come from the declaration. When the app decides roles ([below](#who-decides-a-members-role)), a new person's role if the role claim maps none. |
| Email domains | `emailDomains` | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_DOMAINS`, comma-separated | Empty: any email the email trust accepts |

The first sign-in of a new person creates their account and makes them a member of the provider's company with its role. Under a Pro licence, when that role holds a seat and none is free, they join as a Viewer and the Owners and Admins are told: [Seats](../admin-guide/roles.md#seats). A person who has an account just signs in, through whichever provider: no membership is given again, so a membership the company removed stays removed. A provider without a company creates accounts without any membership; add those people on **Members**.

Choose `VIEWER` for a provider whose people only follow the agents' work, such as auditors or managers who read the results: a Viewer reads what is shared with their groups and their inbox, starts no work, answers nothing, and holds no seat ([Viewers](../admin-guide/roles.md#viewers)). An Owner or Admin can give one of them another role later on **Members**.

The server does not start when a provider names a company that is not declared, a role it does not take, or an email domain that is not a domain name.

`TINFIRM_AUTH_OIDC_AUTO_PROVISION` (Helm `auth.oidcAutoProvision`, default `true`) decides whether a first sign-in creates an account at all. With `false`, only people who already have an account sign in through a provider.

### Who decides a member's role

A provider either decides its members' roles at every sign-in, or gives a new person a role once and leaves the rest to the company's Owners and Admins:

| Setting | Helm | Variable | Default |
|---|---|---|---|
| Role claim: the claim of the ID token or the userinfo response that carries the role's values, a string or an array | `roleClaim` | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_CLAIM` | None. Entra ID's app roles are `roles`; Okta's and Keycloak's groups are usually `groups`. |
| Role map: the claim's values, matched exactly, that give each role. A person with values of several roles gets the highest | `roleMap.admin`, `roleMap.member`, `roleMap.viewer` | `_ROLE_MAP_ADMIN`, `_ROLE_MAP_MEMBER`, `_ROLE_MAP_VIEWER`, comma-separated | None |
| Role source: `IDP`, the provider decides at every sign-in; `APP`, the mapped role, else `role`, is given once and the app decides after | `roleSource` | `_ROLE_SOURCE` | `IDP` with a role claim, `APP` without one |

**When the provider decides**, at every sign-in through it:

- The person's role in the provider's company becomes the one the claim maps. Moving someone into the admins' group at the provider makes them an Admin at their next sign-in; moving them out makes them what the provider says then. Each change is `member.role.changed` by SYSTEM in the company's audit log, with `roleSource: IDP` and the provider.
- Seats still count: a role that holds a seat, for a person without one while every seat is taken, gives Viewer, and the Owners and Admins are told in their inbox. A later sign-in gives the role once a seat is free.
- A person whose claim maps to no role, or who has no such claim, cannot sign in through the provider: **No role for you in this sign-in** (audited as `auth.login.failed` with the reason `role_not_mapped`). No account is created for a new person. A person who has one keeps it, with their membership, role and work, until someone removes them on **Members**; the sessions they already have end at their idle timeout or maximum age.
- **Members** shows the role as "managed by" the provider and cannot change it. It can make the person an Owner: the provider never makes, changes or refuses an Owner, so a group misconfigured at the provider never locks a company out of its Owners.
- One provider decides per company: the server does not start when two providers of one company decide roles. Set `roleSource: APP` on all but one.
- The provider decides the membership too: a person removed on **Members** is a member again at their next sign-in while the provider's claim still names them. Remove people at the provider, or set `roleSource: APP`.

**When the app decides**, a person gets the mapped role, or `role` when the claim maps none, once; a person who has an account only signs in, and Owners and Admins change roles on **Members**.

A provider configured before this setting existed names no role claim, so it runs as `APP`, and each start logs that its roles stay the app's. Name a claim and its map to let the provider decide. The server does not start with `roleSource: IDP` and no claim, a map without a claim, a claim without a map, an empty value, or a value listed under two roles, and its message names the setting.

### One provider for several companies

!!! note "Enterprise"

    Several companies in one instance need an Enterprise licence: [Several companies](#several-companies).

One identity provider can fill several companies from the same claim, instead of a registration per company. Beside the provider's own `company` and its `roleMap`, list each further company with the claim's values that give each role there:

=== "Helm"

    ```yaml title="values.yaml"
    companies:
      - { key: acme, name: Acme }
      - { key: globex, name: Globex }
    auth:
      oidc:
        providers:
          - id: groupsso
            # ...
            company: acme
            roleClaim: groups
            roleMap:
              admin: [acme-admins]
              member: [acme-users]
            companies:
              - key: globex
                roleMap:
                  admin: [globex-admins]
                  member: [globex-users]
    ```

=== "Docker Compose"

    ```yaml
    TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANY: acme
    TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_ROLE_CLAIM: groups
    TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_ROLE_MAP_ADMIN: acme-admins
    TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_ROLE_MAP_MEMBER: acme-users
    TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANIES_GLOBEX_ROLE_MAP_ADMIN: globex-admins
    TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANIES_GLOBEX_ROLE_MAP_MEMBER: globex-users
    ```

| Setting | Helm | Variable | Default |
|---|---|---|---|
| The further companies the claim fills, each by its key in `companies`, with the values that give each role there | `companies[].key`, `companies[].roleMap.admin`, `.member`, `.viewer` | `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANIES_<KEY>_ROLE_MAP_ADMIN`, `_ROLE_MAP_MEMBER`, `_ROLE_MAP_VIEWER`, comma-separated | None |

**When the provider decides** (`roleSource: IDP`, the default with a role claim), the claim says which companies a person is in, and with which role, at every sign-in:

- A person in `acme-users` and `globex-admins` is a Member of Acme and an Admin of Globex from their first sign-in, with one account.
- Moved into another company's group at the provider, they join that company at their next sign-in. Moved out of a company's groups, they leave it: as a member an admin removes, their work stays, the company's Owners and Admins get the departure's checklist, and the company's audit log shows `member.removed` by SYSTEM with the provider. Owners are never removed this way.
- Moved out of every mapped group, they cannot sign in and nothing changes, as [above](#who-decides-a-members-role): a group misconfigured at the provider refuses sign-ins instead of emptying companies.
- One value may give a role in several companies. One provider decides per company, counting every company it fills.

**When the app decides** (`roleSource: APP`), a new person joins each company the claim names, once, and the provider's own company with `role` when the claim maps none there; after that Owners and Admins manage memberships and roles on **Members**.

Each company's **Settings**, **Sign-in** lists every provider that fills it, marks one that fills it through its claim, and shows the claim's values that give each role there. The server does not start on a `companies` entry without `roleClaim`, with an undeclared key, with the provider's own company, without a role map, or on a deciding provider whose own company has no `roleMap`, and its message names the setting.

The same works for a SAML provider, with `companies` on it and `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_COMPANIES_<KEY>_ROLE_MAP_*` ([Sign in with a SAML provider](saml.md)).

### Email trust and email claims

Tinfirm needs a trusted email for a new account. Each provider has an **email trust**:

| Email trust | Email from | `email_verified` |
|---|---|---|
| `REQUIRE_VERIFIED_CLAIM` (the default) | the `email` claim | must be `true` |
| `TRUST_ISSUER` | the first claim of the provider's email claims that holds an email address; of an array claim, its first email | may be missing; an explicit `false` still refuses |

Set it with `emailTrust` and `emailClaims` (Helm), or `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST` and `_EMAIL_CLAIMS` (comma-separated). Claims other than `email` need `TRUST_ISSUER`: the server does not start otherwise. Tinfirm stores the email at the first sign-in, and does not change it when it changes at the provider.

!!! warning "What `TRUST_ISSUER` means"

    With `TRUST_ISSUER`, whoever controls a person's email attributes at the provider decides the email of their Tinfirm account: the provider's administrators, guest invitations, and people themselves where the provider lets them edit the attribute. With `preferred_username` among the email claims, whoever can change a user name does too. Use it only for an issuer of your own, never a multi-tenant one, and set email domains with it.

    Such a person cannot take over an existing account: Tinfirm never links accounts by email ([below](#tinfirm-never-links-accounts-by-email)). But on an instance with several companies, the account they get carries that email everywhere: another company's Owners or Admins who add it as a member give it access there too. Add as members only people you know.

### Email domains

With email domains set, a person without an account gets one only when the part of their email after the last `@` is exactly one of the domains: `alice@example.com` for `example.com`, not `alice@mail.example.com`. Anyone else sees **Email domain not allowed for this sign-in**, and no account is made. Set domains when the provider can sign in people who are not the company's, such as guests of an Entra ID tenant. People who already have an account sign in whatever their domain. Nothing checks that a domain belongs to the company: you list each provider's domains.

### Tinfirm never links accounts by email

Tinfirm knows a person by the provider's issuer and subject, never by email. When the email a provider sends belongs to another account already, such as the same person's local account, the first sign-in fails with **This email is already in use**, and the person keeps the account they had. Give the bootstrap admin an email that no one at your provider has, such as `tinfirm-admin@example.com`.

### The direct link

The login page shows one button per provider, **Continue with** and its name, and the password form while local sign-in is on. On an instance with several companies, hand each company's people its provider's direct link:

```text
https://tinfirm.example.com/login?provider=<id>
```

It offers only that provider and the password form. People whom an AI client sent to the login return to the client after signing in, through any provider. People who opened a page of Tinfirm without a session, such as a link to a conversation, return to that page after signing in, through any provider; a link to any other site or to Tinfirm's API is never followed, and a refused sign-in ends on the login page.

## Worked setups

=== "Keycloak"

    1. In your realm, **Clients**, **Create client**: OpenID Connect, client ID `tinfirm`; **Client authentication** on; **Standard flow** only.
    2. **Valid redirect URIs**: the redirect URI above. Under **Advanced**, **Proof Key for Code Exchange Code Challenge Method**: `S256`.
    3. **Credentials**: copy the client secret.

    Issuer `https://sso.example.com/realms/<realm>`. Keep the default email trust, `REQUIRE_VERIFIED_CLAIM`: Keycloak sends `email_verified`. Make sure the realm verifies emails, or marks its users' emails as verified.

=== "Okta"

    1. **Applications**, **Create App Integration**: OIDC, **Web Application**, grant type **Authorization Code**.
    2. **Sign-in redirect URIs**: the redirect URI above. **Client authentication**: client secret. **Require PKCE**: on.
    3. **Assignments**: the people or groups who may use Tinfirm.

    Issuer `https://<your-org>.okta.com` (the org authorization server) or `https://<your-org>.okta.com/oauth2/default` (a custom one). Keep the default email trust, `REQUIRE_VERIFIED_CLAIM`.

=== "Microsoft Entra ID"

    In the Microsoft Entra admin center:

    1. **App registrations**, **New registration**: **Accounts in this organizational directory only**; redirect URI of the platform **Web**, for example `https://tinfirm.example.com/login/oauth2/code/entra`. Entra ID accepts `http` only for `localhost`.
    2. On **Overview**, note the **Application (client) ID** and the **Directory (tenant) ID**.
    3. **Authentication**: leave **Access tokens** and **ID tokens** of the implicit flow unchecked, and the front-channel logout URL empty.
    4. **Certificates & secrets**, **New client secret**: copy its **Value** at once (not the **Secret ID**), and note when it expires.
    5. **Token configuration**, **Add optional claim**, token type **ID**: `email` and `preferred_username`, and `verified_primary_email` if your tenant has it. Accept the Microsoft Graph `email` permission.
    6. **API permissions**: Microsoft Graph, delegated, `openid`, `profile` and `email` (`User.Read` is there by default); **Grant admin consent**, so that people see no consent prompt.
    7. In **Enterprise applications**, the same application, **Properties**: **Assignment required** **Yes**. Then assign the users or groups who may use Tinfirm under **Users and groups**. Other users of the tenant then cannot sign in at all; without it, every user of the tenant with an email gets an account at their first sign-in, while auto-provisioning is on.

    ```yaml title="values.yaml"
    auth:
      oidc:
        providers:
          - id: entra
            name: Microsoft
            issuerUri: https://login.microsoftonline.com/<tenant-id>/v2.0
            clientId: <application-client-id>
            existingSecret: tinfirm-oidc-entra
            emailTrust: TRUST_ISSUER
            emailClaims: [email, preferred_username, verified_primary_email]
            company: main
            emailDomains: [example.com]
            roleClaim: roles
            roleMap:
              admin: [Tinfirm.Admin]
              member: [Tinfirm.Member]
              viewer: [Tinfirm.Viewer]
    ```

    For `roleClaim: roles`, create three **App roles** on the app registration first, allowed member types **Users/Groups**, with the values `Tinfirm.Admin`, `Tinfirm.Member` and `Tinfirm.Viewer`, and give each user or group one of them when you assign it in step 7. Entra ID sends the values of a person's app roles in the ID token's `roles` claim. A person assigned none cannot sign in, and **Members** shows the role as managed by Microsoft.

    With Compose, layer `deploy/compose/compose.entra.yaml` on `compose.yaml` and set `TINFIRM_ENTRA_TENANT_ID`, `TINFIRM_ENTRA_CLIENT_ID` and `TINFIRM_ENTRA_CLIENT_SECRET` in `.env`; optionally `TINFIRM_ENTRA_EMAIL_DOMAINS`, and, for the app roles, `TINFIRM_ENTRA_ROLE_CLAIM=roles` with `TINFIRM_ENTRA_ROLE_MAP_ADMIN`, `_MEMBER` and `_VIEWER` (`.env.example` lists the values). Without the claim the app decides, and `TINFIRM_ENTRA_ROLE` is a new person's role. The file binds the provider to the company `main`.

    What is different about Entra ID:

    - It sends no `email_verified`, so it needs `TRUST_ISSUER`; otherwise every first sign-in fails with **Email address not confirmed**. Keep the right to change mail attributes and user principal names with few administrators, and leave `preferred_username` out when your user principal names are not mail addresses.
    - Use the tenant's issuer, with the tenant ID. The multi-tenant issuers `common` and `organizations` do not work: the server does not start with them.
    - Its `sub` is unique per user and per app registration. Keep the registration: a new one gives everyone a new identity, and their next sign-in fails with **This email is already in use**.
    - The server reaches `login.microsoftonline.com` (discovery, keys, tokens) and `graph.microsoft.com` (userinfo) on port 443.
    - Client secrets expire, after 24 months at most. Rotate before then ([below](#change-or-remove-a-provider)).

## Check a new provider

1. Open the direct link in a private window and sign in as a person the provider allows.
2. The person lands in Tinfirm as a member of the provider's company, with its role, and appears on **Members**.
3. The instance audit log shows `user.created` and `auth.login.succeeded` with `provider` set to the registration id; the company's audit log shows `member.added` by SYSTEM. Neither holds the email.

## Change or remove a provider

- **Rotate the client secret** before it expires: create a new one at the provider, update the Secret (Helm) or `.env` (Compose), restart the server (pods read Secrets only when they start), sign in once to check, then delete the old secret at the provider. If it leaked, do this at once.
- **Change a provider's company, role or domains** and restart: it applies to accounts created afterwards.
- **Let a provider decide roles**: name its role claim and map, and restart. Each member takes the provider's role at their next sign-in: an Admin made in the app becomes a Member then if the provider says Member. Check the groups or app roles at the provider first.
- **Stop a provider deciding roles**: set `roleSource: APP`, or remove its claim, and restart. At the start every membership whose role it decided becomes the app's, keeping its role, audited once in the company's log as `member.role_source.changed` with their count. The same happens when the provider is removed or bound to another company.
- **Change the issuer**, or register Tinfirm anew at a provider whose `sub` is per registration: everyone becomes a new identity, and their old accounts stay.
- **Remove a provider** and restart: nobody signs in through it any more. Sessions end at their idle timeout or maximum age, or when an instance admin deactivates the account. The accounts and memberships it created stay; remove members on **Members**.
- **Remove a company's declaration**: the company stays, with its members and data, and each start warns about it. Nothing in the app deletes a company.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The server does not start: a company key, name or id is refused | A key of another shape, a blank name, an id that names no company or one with another key | Correct the declaration |
| The server does not start: a provider names a company that is not declared | `company` is not a key of `companies` | Declare the company, or correct the key |
| The server does not start: `tinfirm.auth.oidc.providers names no OIDC registration` | The `TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_*` and `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` variables use different ids | Use the same id |
| The server does not start: the issuer is unreachable, or did not match | The discovery document cannot be read, or names another issuer | Check the issuer URL (for Entra ID, the tenant's v2.0 issuer) and the egress to it |
| The server does not start: `Invalid OIDC registrations` | A registration has no issuer URL, or its scopes lack `openid` | Set `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_ISSUER_URI`, and the scopes to `openid,profile,email` |
| A new company has no Owner | No local account has the declared owner's email yet, and the person has not signed in through the company's provider | Have them sign in once through it, or create their account on **Users**; check the email |
| **Single sign-on failed** after the provider's page | Most often a wrong or expired client secret (for Entra ID, the Secret ID instead of the value), a deactivated account, or auto-provisioning off for a new person | Store the secret again; check the account; turn auto-provisioning on. Entra ID's sign-in logs show the `AADSTS` code of a refused token request |
| The provider shows an error before Tinfirm (Entra ID: `AADSTS50011`) | The redirect URI differs from the registered one | Register it exactly, or correct `TINFIRM_PUBLIC_URL` |
| Entra ID shows `AADSTS50105` or `AADSTS65001` | The person is not assigned to the application, or admin consent is missing | Assign them; grant admin consent |
| A new person has no company | Several companies are declared and the provider names none | Set `company`; add the person on **Members** |
| **Email address not confirmed** | `REQUIRE_VERIFIED_CLAIM`, and the provider sent no `email_verified=true` | Verify emails at the provider; for Entra ID, `TRUST_ISSUER` |
| **No email address from your identity provider** | No configured claim holds an email. For Entra ID: the user has no mail address, the optional claims are missing, or the user principal name is not an email | Add the optional claims (Entra ID), give the user a mail address, or check the email claims |
| **This email is already in use** | Another account has that email | Tinfirm never links accounts: the person keeps the account they have |
| **Email domain not allowed for this sign-in** | The person has no account yet, and their email is in none of the provider's domains | Add the domain under the email domains, if it is the company's |
| **No role for you in this sign-in** | The provider decides roles, and the person's role claim holds none of the mapped values | Add them to a mapped group or app role at the provider, or check the role map's values, which are matched exactly |
| The server does not start: `Invalid role settings` | A role setting the server cannot apply; the message names it | Correct that setting |
| The server does not start: `More than one provider decides the roles of the company` | Two providers of one company have a role claim | Set `roleSource: APP` on all but one |
