# Set up SAML with Microsoft Entra ID

This guide adds Tinfirm to Microsoft Entra ID as a SAML application, so that your people sign in to Tinfirm with Entra ID. Read [Sign in with SAML](saml.md) first.

!!! info "Before you start"
    - Your instance has an Enterprise licence.
    - Whoever runs it chose the provider's id, such as `entra`. **Settings**, **Sign-in** shows its URLs once they added it; until then they give them to you.

Checked against Microsoft's documentation; not yet checked against a live Entra ID tenant.

## 1. Create the enterprise application

1. In the Microsoft Entra admin center, open **Enterprise applications**, select **New application**, then **Create your own application**.
2. Name it **Tinfirm**, choose **Integrate any other application you don't find in the gallery (Non-gallery)**, and select **Create**.
3. Open **Single sign-on** and choose **SAML**.

## 2. Basic SAML configuration

| Entra ID field | Value |
|---|---|
| **Identifier (Entity ID)** | Tinfirm's **Entity ID**, `https://tinfirm.example.com/saml2/service-provider-metadata/entra` |
| **Reply URL (Assertion Consumer Service URL)** | Tinfirm's **Reply URL**, `https://tinfirm.example.com/login/saml2/sso/entra` |
| **Sign on URL** | Tinfirm's **Sign-on URL**, `https://tinfirm.example.com/saml2/authenticate/entra`. The My Apps tile then starts the sign-in at Tinfirm. |
| **Relay State**, **Logout Url** | empty |

You can also select **Upload metadata file** and upload Tinfirm's **Service provider metadata** instead of typing the first two.

## 3. Attributes and claims

Tinfirm identifies a person by Entra ID's object id, which never changes. Keep the defaults and check these claims:

| Claim | Source |
|---|---|
| `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress` | `user.mail` |
| `http://schemas.microsoft.com/identity/claims/displayname` | `user.displayname` (add it if missing) |
| `http://schemas.microsoft.com/identity/claims/objectidentifier` | `user.objectid`, sent by default |

Ask whoever runs your instance to set the provider's `subject` to `http://schemas.microsoft.com/identity/claims/objectidentifier`, and its `emailDomains` to your domains.

### The role from app roles

1. In **App registrations**, open the Tinfirm application, then **App roles**, and create three roles for users and groups, with the values `Tinfirm.Admin`, `Tinfirm.Member` and `Tinfirm.Viewer`.
2. Under **Users and groups** of the enterprise application, assign people or groups, each with a role.
3. Entra ID sends the roles as `http://schemas.microsoft.com/ws/2008/06/identity/claims/role`. Ask whoever runs your instance to set `roleClaim` to that, and `roleMap` to the three values. A person with no role cannot sign in, so assign everyone who should.

## 4. Signing and encryption

- **SAML Signing Certificate**: keep **Sign SAML assertion** and **SHA-256**. Copy the **App Federation Metadata Url** and give it to whoever runs your instance as the provider's `metadataUrl`.
- **Token encryption** (recommended): upload Tinfirm's certificate, which the **Service provider metadata** holds, and activate it. Then set **Signing Option** to **Sign SAML response and assertion**: Tinfirm decrypts an assertion only in a signed response. Ask whoever runs your instance to set `requireEncryptedAssertions: true` once it works.

## 5. Check it

Select **Test** in Entra ID, or open the provider's **Direct link**. The audit log shows each sign-in as `auth.login.succeeded` with `method: saml`; a refused one as `auth.login.failed` with its reason ([Troubleshooting](../operator-guide/saml.md#troubleshooting)).

When Entra ID rolls its signing certificate over, it publishes the next one in its metadata first; Tinfirm trusts both, so nobody is signed out.
