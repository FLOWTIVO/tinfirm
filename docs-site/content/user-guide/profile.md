# Your profile, sign-in and tokens

Your **Profile** belongs to you rather than to a company: your sign-in, the companies you are a member of, the apps you allowed to act as you, and your personal access tokens. This page is for every member: how to read it, change your password, disconnect an app and manage your tokens.

## Open your profile

Open the account menu at the foot of the sidebar, under your name and email, and choose **Profile**. The same menu switches between **Dark theme** and **Light theme**, and has **Log out**.

The profile has these sections, in order: **Account**, **Companies**, **Password**, **Connected apps**, **Gmail** (only once you linked a Google account) and **Personal access tokens**.

## Account

**Account** shows your name and email, as your sign-in gives them, and:

- **Sign-in method**: **Email and password**, for an account kept in Tinfirm, or **Single sign-on (OIDC)**, when you sign in through your organisation's identity provider;
- **User ID**: your id on this Tinfirm instance, which an operator may ask for;
- the badge **Instance admin**, if you manage the installation itself. [Roles](../admin-guide/roles.md#instance-admins) explains what that means.


## Your companies

**Companies** lists every company you are a member of, with your role in each: **Owner**, **Admin**, **Member** or **Viewer**. Choose one to open its inbox.

Each company has its own inbox, conversations, accounts and budget. To move between them, use the company's name at the top of the sidebar (**Switch company**): it lists **Your companies** with your role in each. A role belongs to one company: you can be an Owner in one and a Member in another. [Roles](../admin-guide/roles.md) says what each role may do.

## Change your password

With **Email and password**:

1. Under **Password**, enter your **Current password**.
2. Enter a **New password** of at least 12 characters, different from the current one. A passphrase works well.
3. Repeat it under **Repeat new password**, and choose **Change password**.

Your other sessions, in other browsers and on other devices, are signed out; this one stays signed in. The change is recorded in the instance's audit log.

With single sign-on, you have no Tinfirm password: the section says so. Change your password with your identity provider. If you forgot a Tinfirm password, ask an instance admin to set a new one.

A session ends after 8 hours without activity, and after 7 days in any case, unless the operator set other times. Sign in again then.

## Connected apps

**Connected apps** lists the AI clients you allowed to act as you, such as Claude Code, one row for each app and company:

- the app's name, and the domain it is published under;
- its level: **Read**, **Write** or **Decide**;
- the company it acts in, when you allowed it, and when it was last used, or *never used*.

The Outlook add-in is listed here too, as **Tinfirm for Outlook**.

To stop one, choose its disconnect button and confirm **Disconnect {app} from {company}?** It stops working there at once. To use it again, connect it again from the app. Removing a server from the client itself does not disconnect it in Tinfirm: disconnect it here, especially for a computer you lost.

An app stops working in a company when you leave it, are suspended there, or your Tinfirm account is deactivated. [Work with Tinfirm from your AI client](ai-clients.md) explains how to connect a client and choose its level.

## Gmail

Once you linked a Google account for the Gmail add-on, **Gmail** lists each linked account, one per company: the Google address, the company, when you linked it and when it was last used. Its disconnect button asks **Disconnect {address}?**; Gmail then stops acting as you in that company at once and asks you to sign in again there. [Ask an agent from Gmail](gmail.md) explains the add-on.

## Personal access tokens

A personal access token lets a script, a CI job or an AI client without a browser reach a company's MCP endpoint as you. It works on that endpoint only: the rest of Tinfirm still needs you signed in. A token is yours, not a company's: it works in every company you are a member of, with your role there, and never beyond its scope.

### Create a token

1. Under **Personal access tokens**, choose **New token**.
2. Give it a **Name** you will recognise later, up to 100 characters, such as *Laptop, Claude Code*.
3. Choose its **Scope**:

    | Scope | It may |
    |---|---|
    | **Read** | Read your conversations, decisions and inbox, and the agents you may use |
    | **Write** | Also start conversations, send messages, cancel, retry and end. Never decide |
    | **Decide** | Also answer, approve or deny your own decisions. Only for a person at the keyboard |

    Choose the lowest the work needs: **Read** for anything that runs unattended.

4. Choose when it **Expires**: **7 days**, **30 days**, **90 days** (the default), **1 year** or **No expiry**.
5. Choose **Create token**. If you signed in through your organisation's identity provider more than 10 minutes ago, the dialog asks you to **Sign in again**; after the sign-in you are back on your **Profile**, in any browser tab, and create the token from there.

The dialog shows the token once. Copy it now: Tinfirm keeps only a hash. If you lose it, revoke it and create another. Send it as `Authorization: Bearer` to the company's MCP endpoint, and keep it in an environment variable, never in a file you commit. [Without a browser: a personal access token](ai-clients.md#without-a-browser-a-personal-access-token) shows how to give it to Claude Code.

Conversations you start with a token are personal: agents act with your own accounts and are paid from your budget. The dialog names the Owners of each company you are a member of, who can read such conversations in full; each read is recorded and shown to you.

### Your tokens

The list shows each token's name, its scope, when it was created, when it expires (or *never expires*) and when it was last used. An expired token is marked **Expired** and no longer works.

To revoke a token, choose its bin and confirm **Revoke {name}?** Scripts and clients that use it stop working at once. You cannot undo this. Creating and revoking a token are recorded in the instance's audit log, never the token itself.

## When you leave a company

When you leave a company, are suspended there, or your Tinfirm account is deactivated, your connected apps stop working in that company; in your other companies they go on. When you leave, your accounts on the company's systems go with your membership, and your open conversations there end.
