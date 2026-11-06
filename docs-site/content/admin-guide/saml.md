# Sign in with SAML

Your people can sign in to Tinfirm with your company's SAML identity provider, such as Microsoft Entra ID, Okta or AD FS. Whoever runs your instance adds the provider to its deployment, since a provider decides who gets an account; you set up Tinfirm at the identity provider. It needs the Enterprise edition, and keeps working after an Enterprise licence ended.

## What you see in Tinfirm

As an Owner or Admin, open **Settings**, **Sign-in**. **Identity providers** shows each provider whose new people join your company, read-only:

- **Direct link**: the login page with this provider only, to hand to your people.
- **Roles**: whether your identity provider decides each person's role at every sign-in, or gives a new person a role once and you change it after under **Members**.
- For a SAML provider, what your identity provider needs: the **Entity ID**, the **Reply URL**, the **Sign-on URL** for a portal's tile, and the **Service provider metadata** to download or link.
- The state of your identity provider's metadata: when Tinfirm last read it, the last error, and the certificates of both sides with their end dates.

## How a sign-in goes

1. A person selects your provider on Tinfirm's login page, or a tile in your portal that opens the sign-on URL.
2. Tinfirm sends them to your identity provider, which signs them in with its own rules, multi-factor authentication included.
3. Your identity provider sends them back with a signed answer. Tinfirm checks it, then signs them in.

Sign-in always starts at Tinfirm: a tile that sends people straight to Tinfirm with an answer nobody asked for is refused. Set the tile to open the **Sign-on URL**, as each guide shows.

A person is known by your identity provider's stable id for them, never by their email. At their first sign-in Tinfirm creates their account, in your company, with the role your identity provider or the deployment gives them. With [SCIM](scim.md), the account SCIM created is the one they sign in to.

Signing out of Tinfirm ends the Tinfirm session only. Remove a person at your identity provider; with SCIM they lose access to Tinfirm at once.

## Guides

- [Microsoft Entra ID](saml-entra-id.md)
- [Okta](saml-okta.md)
- [AD FS](saml-adfs.md)
