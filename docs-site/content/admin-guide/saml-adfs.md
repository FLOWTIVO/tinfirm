# Set up SAML with AD FS

This guide adds Tinfirm to Active Directory Federation Services as a relying party, so that your people sign in to Tinfirm with AD FS. Read [Sign in with SAML](saml.md) first.

!!! info "Before you start"
    - Your instance has an Enterprise licence.
    - Whoever runs it chose the provider's id, such as `adfs`. **Settings**, **Sign-in** shows its URLs once they added it; until then they give them to you.
    - AD FS reaches nothing of Tinfirm's: the person's browser carries every message. Tinfirm reads AD FS's metadata, so whoever runs it needs its URL, or the document as a file.

Checked against Microsoft's documentation; not yet checked against a live AD FS farm.

## 1. Add the relying party trust

1. In **AD FS Management**, open **Relying Party Trusts**, **Add Relying Party Trust**, **Claims aware**.
2. Choose **Import data about the relying party published online or on a local network** and enter Tinfirm's **Service provider metadata** URL, `https://tinfirm.example.com/saml2/service-provider-metadata/adfs`. Or download the file and choose **Import data about the relying party from a file**.
3. Name it **Tinfirm**, choose who may sign in, and finish. The trust holds Tinfirm's entity ID, reply URL and certificates.
4. Under **Advanced**, keep the secure hash algorithm **SHA-256**.

## 2. The claims

Add claim rules to the trust's **Claim Issuance Policy**:

1. **Send LDAP Attributes as Claims**, attribute store **Active Directory**:

    | LDAP attribute | Outgoing claim type |
    |---|---|
    | `E-Mail-Addresses` | `E-Mail Address` |
    | `Display-Name` | `Name` |
    | `objectGUID` | `objectGUID`, a custom claim type, such as `http://tinfirm.example.com/claims/objectguid` |

2. **Transform an Incoming Claim**: the `objectGUID` claim to **Name ID**, format **Persistent Identifier**.
3. **Send Group Membership as a Claim** for each Tinfirm role, such as the group `Tinfirm Admins` as the **Role** claim with value `Tinfirm.Admin`, and so on for members and viewers.

Ask whoever runs your instance to set `nameAttributes` to `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name`, `roleClaim` to `http://schemas.microsoft.com/ws/2008/06/identity/claims/role`, `roleMap` to your values, and `emailDomains` to your domains.

## 3. Signing and encryption

AD FS signs the assertion by default. Run, in PowerShell:

```powershell
Set-AdfsRelyingPartyTrust -TargetName "Tinfirm" -SamlResponseSignature MessageAndAssertion
```

AD FS encrypts the assertion with the certificate it imported from Tinfirm's metadata (recommended); Tinfirm decrypts it in the signed response. Ask whoever runs your instance to set `requireEncryptedAssertions: true` once it works.

## 4. The metadata and the link

Give whoever runs your instance AD FS's metadata URL, `https://adfs.example.com/FederationMetadata/2007-06/FederationMetadata.xml`, as the provider's `metadataUrl`. Publish Tinfirm's **Sign-on URL**, `https://tinfirm.example.com/saml2/authenticate/adfs`, as the link on your intranet instead of AD FS's `idpinitiatedsignon` page: sign-in always starts at Tinfirm.

## 5. Check it

Open the provider's **Direct link** and sign in. The audit log shows each sign-in as `auth.login.succeeded` with `method: saml`; a refused one as `auth.login.failed` with its reason ([Troubleshooting](../operator-guide/saml.md#troubleshooting)).
