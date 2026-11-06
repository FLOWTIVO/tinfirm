# Sign in with a SAML provider

SAML 2.0 sign-in lets a company's people sign in with an identity provider that speaks SAML, such as Microsoft Entra ID, Okta or AD FS, beside the [OIDC providers](sign-in.md). You declare each SAML provider in the deployment, as you declare an OIDC one, and bind it to a declared company. It needs an Enterprise licence and keeps working after an Enterprise key expired, so that a company that bought it is never locked out.

Sign-in always starts at Tinfirm: the login page's button, or a portal's tile set to open Tinfirm's sign-on URL. Tinfirm does not accept a response nobody asked for (IdP-initiated sign-in), and has no single logout: signing out of Tinfirm ends its own session only. Remove a person at the identity provider with [SCIM](scim.md) to end their sessions at once.

## What Tinfirm needs

- An https `TINFIRM_PUBLIC_URL`. The identity provider posts its answer to Tinfirm from another site, and the browser sends Tinfirm's binding cookie with that post over https only. `http://localhost` works for development.
- The service provider's keys, below: Tinfirm signs its requests with them and publishes them in its metadata.
- The IdP's metadata, from an https URL or from a file the deployment mounts.
- The paths `/saml2/` and `/login/saml2/` routed to the server, as `/api/` and `/login/oauth2/` are.

## The service provider's keys

Tinfirm's SAML keys are the instance's, held in the deployment as the OIDC client secrets are. Each credential is a private key and its certificate:

| Environment variable | Meaning |
|---|---|
| `TINFIRM_AUTH_SAML_SP_CREDENTIALS_<KID>_KEY` | A PKCS#8 PEM private key, RSA of at least 3072 bits or EC P-256 |
| `TINFIRM_AUTH_SAML_SP_CREDENTIALS_<KID>_CERT` | Its X.509 certificate in PEM; self-signed is fine |
| `TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL` | The `<KID>` of the credential that signs |

`<KID>` is 1 to 32 lower-case letters and digits, such as `k2026`. Make a credential with OpenSSL:

```bash
openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=tinfirm-saml" \
  -keyout k2026.key -out k2026.crt
kubectl create secret generic tinfirm-saml-sp --from-file=k2026.key --from-file=k2026.crt
```

With Helm, name the Secret in `auth.saml.sp.existingSecret` and each credential in `auth.saml.sp.credentials` (`kid`, and `keyKey` and `certKey` when the Secret's keys are not `<kid>.key` and `<kid>.crt`).

Every credential is published in Tinfirm's metadata and tried to decrypt an encrypted assertion; only the active one signs. The server does not start with SAML providers and no credential, with an unreadable key or certificate, with a certificate that is not its key's, or with an active certificate that has ended. It warns from 30 days before the active certificate ends.

**Rollover.** Add the next credential beside the current one and restart. Wait until every identity provider read Tinfirm's metadata again, or its admin uploaded it. Then make the next credential active, and remove the old one later.

## Declare a provider

| Helm (`auth.saml.providers[]`) | Environment variable | Meaning | Default |
|---|---|---|---|
| `id` | the `<ID>` of each variable | Lower-case letters and digits, unique across the SAML and OIDC providers | required |
| `name` | `TINFIRM_AUTH_SAML_PROVIDERS_<ID>_NAME` | The login page's button | the id |
| `metadataUrl` or `metadata` | `_METADATA_LOCATION` | The IdP's metadata: an `https://` URL, or a `file:` path. Helm's `metadata` is the document itself, mounted from a ConfigMap. `http:` is refused | required |
| `entityId` | `_ENTITY_ID` | The IdP to take from a document that holds several, and the IdP the provider is | the IdP of the first good document |
| `company`, `role`, `emailDomains` | `_COMPANY`, `_ROLE`, `_EMAIL_DOMAINS` | As an [OIDC provider's](sign-in.md#the-company-and-role-of-a-new-person): the company a new person joins, their role, the domains their email must be in | as OIDC |
| `subject` | `_SUBJECT` | `NAMEID`, or the attribute that identifies the person | `NAMEID` |
| `emailAttributes` | `_EMAIL_ATTRIBUTES` | The attributes read for the email, in order | `email`, `mail`, the claims URI of `emailaddress`, `urn:oid:0.9.2342.19200300.100.1.3` |
| `nameAttributes` | `_NAME_ATTRIBUTES` | The attributes read for the name, in order; then the given name and surname | `displayName`, Microsoft's `displayname` claim, `urn:oid:2.16.840.1.113730.3.1.241`, `name` |
| `roleClaim`, `roleMap`, `roleSource` | `_ROLE_CLAIM`, `_ROLE_MAP_*`, `_ROLE_SOURCE` | The attribute that carries the role and its values, as for OIDC ([Who decides a member's role](sign-in.md#who-decides-a-members-role)) | `IDP` with an attribute, else `APP` |
| `companies` | `_COMPANIES_<KEY>_ROLE_MAP_*` | Further companies the attribute fills, as for OIDC ([One provider for several companies](sign-in.md#one-provider-for-several-companies)) | none |
| `autoProvision` | `_AUTO_PROVISION` | Whether an unknown person gets a new account; `false` when SCIM provisions the company | `true` |
| `requireEncryptedAssertions` | `_REQUIRE_ENCRYPTED_ASSERTIONS` | Refuse an assertion that is not encrypted | `false` |
| `metadataRefresh` | `_METADATA_REFRESH` | How often the metadata is read again, at least `PT5M` | `PT12H` |
| `scimMatch` | `_SCIM_MATCH` | How a first sign-in finds a person SCIM created | `EXTERNAL_ID` |

A SAML provider sends no `email_verified`: Tinfirm trusts it for the email, as an OIDC provider under `TRUST_ISSUER`. Set `emailDomains`, so that it creates accounts for your domains only.

The server does not start on a provider of an undeclared company, an id an OIDC provider also uses, a metadata location over `http:`, or role settings it cannot apply. Nor does it start when two providers that read different metadata name the same IdP (by `_ENTITY_ID`, or by the documents they last read), or when a provider names an OIDC provider's issuer: a SAML user is identified by the IdP's entity ID and their subject, so one entity ID must come with one set of keys. Two providers that read the same metadata location are one IdP with two sets of settings, and may. For one IdP that serves several companies, declare one provider that fills them ([One provider for several companies](sign-in.md#one-provider-for-several-companies)). On a worker, it does not start when a provider's metadata cannot be read and none was read before, under a licence that serves SAML.

### At the identity provider

Give the identity provider's admin these, which **Settings**, **Sign-in** also shows the company's Owners and Admins:

| | Value |
|---|---|
| Entity ID (audience) | `{TINFIRM_PUBLIC_URL}/saml2/service-provider-metadata/<id>` |
| Service provider metadata | the same URL, public |
| Reply URL (ACS) | `{TINFIRM_PUBLIC_URL}/login/saml2/sso/<id>`, HTTP-POST |
| Sign-on URL | `{TINFIRM_PUBLIC_URL}/saml2/authenticate/<id>`: a portal's tile opens it |

The guides for [Microsoft Entra ID](../admin-guide/saml-entra-id.md), [Okta](../admin-guide/saml-okta.md) and [AD FS](../admin-guide/saml-adfs.md) walk through each.

## The IdP's metadata

A worker reads each provider's metadata at the start and every `metadataRefresh`, sooner when the document asks, and stores it; every replica signs people in with the stored document, never with a read of its own. A URL is fetched over verified TLS, at most 1 MiB, following a redirect only to the same host; a file is read again too, so a changed ConfigMap needs no restart.

- **A failed read** keeps the last good metadata, logs a warning, and audits `saml.metadata.failed` once. **Settings**, **Sign-in** shows the error.
- **Expired metadata** (its `validUntil` passed) is not used: sign-in through the provider fails until a read succeeds.
- **Certificate rollover** at the IdP needs nothing from you: every signing certificate the metadata publishes is trusted, so the next one works as soon as the IdP publishes it. A change is audited as `saml.idp_keys.changed`.
- **The entity ID is pinned.** Without `entityId`, the IdP the provider's first good document names is the provider's: a later document that names another is refused (`entity_id_changed`), audited as `saml.idp_entity_id_changed`, and **Settings**, **Sign-in** shows the metadata as refused while the last good one stays in use. A document that names another provider's IdP or an OIDC issuer is refused too (`entity_id_in_use`). When an IdP really changes its entity ID, set `entityId` to the new one.

With the chart's NetworkPolicy, `networkPolicy.egress.saml` opens https for the metadata URLs; a mounted document needs no rule.

## Troubleshooting

A refused sign-in ends on the login page with `SAML_LOGIN_FAILED`; why is only in the audit log, as `auth.login.failed` with `method: saml` and a `reason`:

| Reason | What to check |
|---|---|
| `metadata_expired` | The IdP's metadata could not be read, or expired: see **Settings**, **Sign-in** |
| `signature_invalid`, `unsigned`, `issuer_mismatch` | The IdP signs with a certificate its metadata publishes, and its entity ID is the metadata's |
| `weak_algorithm` | The IdP signs with SHA-256 or stronger, and encrypts with RSA-OAEP |
| `destination_mismatch`, `audience_mismatch`, `recipient_mismatch` | The reply URL and entity ID at the IdP are Tinfirm's, with the same public URL |
| `unsolicited` | The person started at the IdP's portal: make the tile open the sign-on URL |
| `browser_mismatch`, `in_response_to_mismatch` | The sign-in took over 5 minutes, or ended in another browser: start again |
| `not_encrypted`, `decryption_failed` | The provider requires encryption: the IdP encrypts with Tinfirm's published certificate, and signs the response as well as the assertion |
| `transient_subject` | The IdP sends a transient NameID: send a persistent one, or set `subject` to a stable attribute |
| `edition_required` | The licence does not serve SAML |

`EMAIL_MISSING`, `EMAIL_DOMAIN_NOT_ALLOWED`, `EMAIL_IN_USE` and `ROLE_NOT_MAPPED` mean what they mean for [OIDC](sign-in.md#troubleshooting).
