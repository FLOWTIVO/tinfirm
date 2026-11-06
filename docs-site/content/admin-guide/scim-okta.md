# Set up SCIM with Okta

This guide connects Okta's provisioning to your company in Tinfirm, so that Okta adds, changes and deactivates your company's members. Read [Provision people with SCIM](scim.md) first for what SCIM does in Tinfirm.

!!! info "Before you start"
    - Your instance has an Enterprise licence, and whoever runs it allowed SCIM for your company: **Settings**, **Sign-in** shows the **Base URL** without a warning.
    - Your people sign in to Tinfirm through Okta (OIDC, set up by whoever runs your instance).
    - Okta's cloud reaches your instance from the internet, or you use Okta's on-premises provisioning agent.

Checked against Okta's documented requests; not yet checked against a live Okta org.

## 1. Issue a token in Tinfirm

As the company's Owner, open **Settings**, **Sign-in**, select **New token**, name it **Okta**, keep **No expiry** and select **Issue token**. Copy the token and the **Base URL**.

## 2. Turn on SCIM for the app

1. In the Okta Admin Console, open the Tinfirm app integration (the OIDC app people sign in with), then **General**, **App Settings**, **Edit**, and set **Provisioning** to **SCIM**. Save.
2. Open the new **Provisioning** tab, **Integration**, **Edit**:
    - **SCIM connector base URL**: the **Base URL**.
    - **Unique identifier field for users**: `userName`.
    - **Supported provisioning actions**: **Push New Users**, **Push Profile Updates**; not **Push Groups**.
    - **Authentication Mode**: **HTTP Header**, and paste the token as the **Bearer** token.
3. Select **Test Connector Configuration**, then **Save**.
4. Under **Provisioning**, **To App**, **Edit**, turn on **Create Users**, **Update User Attributes** and **Deactivate Users**. Leave **Sync Password** off: Tinfirm ignores passwords.

## 3. The role

1. In **Directory**, **Profile Editor**, open the Tinfirm app's profile and **Add Attribute**:
    - **Display name**: `Role`; **Variable name**: `role`.
    - **External name**: `role`; **External namespace**: `urn:ietf:params:scim:schemas:extension:tinfirm:2.0:User`.
    - **Enum**: `admin`, `member`, `viewer`.
2. Assign groups to the app under **Assignments**, and set each group's **Role**. A person in several groups gets the role of the group Okta ranks first.

Without the attribute, Okta still provisions people, with the default role whoever runs your instance chose, and never changes a role it does not send.

## 4. Sign-in

Okta sends its user id as the `externalId`, which is also the subject (`sub`) of its OIDC sign-in: a person's first sign-in is bound to the account SCIM made with nothing more to set. Ask whoever runs your instance to set the provider's `_AUTO_PROVISION` to `false`, so that only people assigned to the app can sign in.

## What happens in Tinfirm

| You do in Okta | What happens in Tinfirm |
|---|---|
| Assign a person or a group | They become members, with the group's role |
| Change a profile or a role | Okta sends the whole user; the role changes only when Okta sends one |
| Unassign or deactivate a person | They are suspended at once; their data stays |
| Reactivate them | The suspension Okta set is lifted |

Okta never deletes users through SCIM. Remove a person who left for good in **Members**, if you want them out of the company's list.
