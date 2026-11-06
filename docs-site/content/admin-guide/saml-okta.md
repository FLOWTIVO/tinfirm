# Set up SAML with Okta

This guide adds Tinfirm to Okta as a SAML application, so that your people sign in to Tinfirm with Okta. Read [Sign in with SAML](saml.md) first.

!!! info "Before you start"
    - Your instance has an Enterprise licence.
    - Whoever runs it chose the provider's id, such as `okta`. **Settings**, **Sign-in** shows its URLs once they added it; until then they give them to you.

Checked against Okta's documentation; not yet checked against a live Okta org.

## 1. Create the application

In the Okta Admin Console, open **Applications**, **Create App Integration**, choose **SAML 2.0**, and name it **Tinfirm**. Under **App visibility**, select **Do not display application icon to users**: step 4 adds the tile that starts the sign-in at Tinfirm.

## 2. SAML settings

| Okta field | Value |
|---|---|
| **Single sign-on URL** | Tinfirm's **Reply URL**, `https://tinfirm.example.com/login/saml2/sso/okta`; keep **Use this for Recipient URL and Destination URL** |
| **Audience URI (SP Entity ID)** | Tinfirm's **Entity ID**, `https://tinfirm.example.com/saml2/service-provider-metadata/okta` |
| **Name ID format** | **Persistent** |
| **Application username** | **Custom**: `user.getInternalProperty("id")`, Okta's id for the person, which never changes |
| **Response** and **Assertion Signature** | **Signed**, **RSA-SHA256** |

Under **Show Advanced Settings**:

- **Signature Certificate**: upload Tinfirm's certificate, from its **Service provider metadata**, so that Okta checks Tinfirm's signed requests.
- **Assertion Encryption** (recommended): **Encrypted**, **AES256-GCM**, key transport **RSA-OAEP**, and Tinfirm's certificate. Ask whoever runs your instance to set `requireEncryptedAssertions: true` once it works.

**Attribute Statements**:

| Name | Value |
|---|---|
| `email` | `user.email` |
| `displayName` | `user.displayName` |

**Group Attribute Statements**, for the role: name `groups`, filter **Starts with** `tinfirm-`. Ask whoever runs your instance to set `roleClaim: groups` and `roleMap` to your groups, such as `tinfirm-admins` and `tinfirm-users`.

## 3. The metadata

Open **Sign On** of the application and copy the **Metadata URL**. Give it to whoever runs your instance as the provider's `metadataUrl`, with your `emailDomains`.

## 4. The tile

Add a **Bookmark App** named **Tinfirm** whose URL is Tinfirm's **Sign-on URL**, `https://tinfirm.example.com/saml2/authenticate/okta`, and assign it to the same people. It starts the sign-in at Tinfirm, which then asks Okta.

## 5. Check it

Assign yourself, open the provider's **Direct link** and sign in. The audit log shows each sign-in as `auth.login.succeeded` with `method: saml`; a refused one as `auth.login.failed` with its reason ([Troubleshooting](../operator-guide/saml.md#troubleshooting)).
