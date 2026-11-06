# Set up SCIM with Microsoft Entra ID

This guide connects Microsoft Entra ID's provisioning service to your company in Tinfirm, so that Entra ID adds, changes and removes your company's members. Read [Provision people with SCIM](scim.md) first for what SCIM does in Tinfirm.

!!! info "Before you start"
    - Your instance has an Enterprise licence, and whoever runs it allowed SCIM for your company: **Settings**, **Sign-in** shows the **Base URL** without a warning.
    - Your people sign in to Tinfirm through Entra ID (OIDC, set up by whoever runs your instance).
    - Microsoft's provisioning service reaches your instance from the internet. An instance only on your network needs Entra ID's on-premises provisioning agent.

Checked against Microsoft's documented requests; not yet checked against a live Entra ID tenant.

## 1. Issue a token in Tinfirm

As the company's Owner, open **Settings**, **Sign-in**, select **New token**, name it **Entra ID**, keep **No expiry** and select **Issue token**. Copy the token. Copy the **Base URL** too.

## 2. Create the enterprise application

1. In the Microsoft Entra admin center, open **Enterprise applications**, select **New application**, then **Create your own application**.
2. Name it **Tinfirm**, choose **Integrate any other application you don't find in the gallery (Non-gallery)**, and select **Create**.
3. Open **Provisioning**, select **Get started** or **New configuration**, and set **Provisioning Mode** to **Automatic**.
4. Under **Admin Credentials**:
    - **Tenant URL**: the **Base URL** followed by `?aadOptscim062020`, for example `https://tinfirm.example.com/scim/v2?aadOptscim062020`. The flag makes Entra ID's updates follow the SCIM standard.
    - **Secret Token**: the token from step 1.
5. Select **Test Connection**. Entra ID asks Tinfirm for a user that does not exist, and expects an empty answer.

## 3. Map the attributes

Under **Mappings**, open **Provision Microsoft Entra ID Users**.

| Tinfirm attribute | Entra ID source | Note |
|---|---|---|
| `userName` | `userPrincipalName` | Keep your users' UPN equal to their mail; Tinfirm uses it as the email. |
| `externalId` | `objectId` | Change it from the default `mailNickname`. The first sign-in is bound to the account by this id. |
| `active` | `Switch([IsSoftDeleted], , "False", "True", "True", "False")` | The default. |
| `displayName`, `name.givenName`, `name.familyName` | as by default | |
| `urn:ietf:params:scim:schemas:extension:tinfirm:2.0:User:role` | see below | |

Delete the mappings Tinfirm does not keep, or leave them: Tinfirm ignores every attribute it does not keep. Turn off **Provision Microsoft Entra ID Groups**: Tinfirm provisions users only.

### The role from app roles

1. In **App registrations**, open the Tinfirm application, then **App roles**, and create three roles that apply to users and groups, with the values `Tinfirm.Admin`, `Tinfirm.Member` and `Tinfirm.Viewer`.
2. In the attribute mappings, select **Show advanced options**, **Edit attribute list for customappsso**, and add `urn:ietf:params:scim:schemas:extension:tinfirm:2.0:User:role`, type **String**.
3. Add a mapping of type **Expression** to it:

    ```text
    Switch(SingleAppRoleAssignment([appRoleAssignments]), "member", "Tinfirm.Admin", "admin", "Tinfirm.Member", "member", "Tinfirm.Viewer", "viewer")
    ```

4. Under **Users and groups**, assign people or groups to the application, each with its role. Assigning a group provisions its members.

## 4. Sign-in

People SCIM created sign in through your Entra ID OIDC provider. Their first sign-in is bound by the user's object id, which Entra ID sends in its ID token as `oid`: the same id as the `externalId` above. Ask whoever runs your instance to set the provider's `_AUTO_PROVISION` to `false`, so that only people assigned to the application can sign in.

## 5. Start provisioning

Set **Provisioning Status** to **On** and save. Entra ID's first cycle provisions everyone assigned; later cycles run about every 40 minutes. **Provisioning logs** show each request and Tinfirm's answer.

| You do in Entra ID | What happens in Tinfirm |
|---|---|
| Assign a person | They become a member, with their app role |
| Change their app role | Their role changes at the next cycle |
| Remove the assignment, or block sign-in | They are suspended at once at the next cycle; their data stays |
| Delete the user | Suspended at once, then removed from the company 30 days later, when Entra ID deletes them for good |
