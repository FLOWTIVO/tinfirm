# Company settings

This page is for a company's Owners and Admins. It goes through the company's **Settings**: its name, what members may bring and what triggers may pay with, the Outlook and Gmail add-ons, the limits on what arrives from chats and webhooks, how agents' memories are tidied, the secrets in the company's vault, provisioning by SCIM, and the licence.

Open **Settings** in the sidebar's **Company** section. Its sections are **General**, **Chats and webhooks**, **Memory**, **Secrets**, **Sign-in** and **Licence**. A change to a field waits in a bar at the foot of the page, which names what changed, until you save or discard it; a switch saves at once.

How the company's people sign in is the deployment's configuration, not a setting: [Declare companies and how their people sign in](../operator-guide/sign-in.md). **Sign-in** shows the identity providers that fill your company, as their own company or through their claim ([One provider for several companies](../operator-guide/sign-in.md#one-provider-for-several-companies)), with the claim's values that give each role here, and holds SCIM provisioning, which the deployment allows.

## General

### Company

| Field | What it is |
|---|---|
| **Name** | Shown to members everywhere in Tinfirm and on the sign-in page. Up to 100 characters. |
| **Company ID** | How the API and the company's links name it, with a button to copy it. Your AI client's endpoint uses it: [Work with Tinfirm from your AI client](../user-guide/ai-clients.md). |
| **Created** | When the company was created. |

To rename the company, change **Name** and save. The deployment declares the company, but it does not rename it later: your name stays.

### Members: their own MCP servers

**Their own MCP servers** says whether members may register MCP servers of their own under **Accounts** and use their tools in their own conversations. Only they see such a server, and only their conversations use it.

Switching it off asks **Turn off members' own MCP servers?** first. The servers members registered stay, but no conversation uses them, and nobody can register a new one until you turn it on again. It is a kill switch, listed on [Kill switches](kill-switches.md#turn-members-own-mcp-servers-off). [MCP servers](mcp-servers.md) says more.

### Triggers: the author's subscription

**Its author's subscription** says what a trigger may pay with. It is off by default: a trigger pays with its payer's budget on the company's model gateway.

On, an author may pay for their own triggers with their Claude or ChatGPT subscription, on their personal runner host. A trigger fires with nobody signed in and its work goes to its audience, which the vendors' terms for a personal plan may not cover: turning this on, after **Let triggers pay with their author's subscription?**, is the company's call. The author still chooses it per trigger, and only for triggers they pay for themselves.

Turned off again, a trigger that still pays with a subscription pauses at its next firing.

### Outlook and Gmail

!!! note "Pro and Enterprise"

    The Outlook add-in and the Gmail add-on need a Pro or Enterprise licence. Without one, each section shows a **Pro** badge and offers no file: [Editions and prices](editions.md).

| Section | What it offers |
|---|---|
| **Outlook** | **Download manifest**: this company's manifest of the Outlook add-in, which a Microsoft 365 administrator uploads under **Integrated apps**, or a person adds from a file under **My add-ins** in Outlook. |
| **Gmail** | **Download deployment file**: this company's deployment of the Gmail add-on, which a Google Workspace administrator creates in the company's Google Cloud project. When the operator has not configured that project yet, the section says that Tinfirm refuses Gmail's requests until they do. |

Each person then signs in once and allows the add-in to act as them. [Deploy the Outlook add-in](outlook-add-in.md) and [Deploy the Gmail add-on](gmail-add-on.md) give the steps.

## Chats and webhooks

What arrives from Slack, Teams and webhooks beyond these limits is refused before any agent sees it, so a flood spends no budget. A person whose chat message is refused is told why.

| Limit | What it counts | At most |
|---|---|---|
| **Messages a minute, per chat user** | Messages from one person; more are refused, and the person told | 600 |
| **Messages a day, per chat user** | The same, over the day | 10,000 |
| **Events a minute, per chat system** | Everything Slack or Teams sends to one chat system, a flood included | 6,000 |
| **Deliveries a minute, by default** | Deliveries to a webhook system that names no limit of its own | 6,000 |
| **Deliveries a minute, at most** | The most a webhook system may name; lowering it lowers those above it | 6,000 |

Each is a whole number from 1. The default for webhooks is at most the maximum. Change the numbers, then choose **Save the limits**. If another Owner or Admin saved meanwhile, the page reads their limits again and tells you; make your change again on top of them.

The size of a request is the operator's to set, not a limit here. [Receive webhooks](webhooks.md) and [Set up Slack and Microsoft Teams](chat-systems.md) say more about each kind of system.

## Memory

Agents keep short notes for the people and the triggers that turned memory on: [Agent memory](../user-guide/memory.md). Once every 30 days, Tinfirm tidies each memory that changed: a small model merges related notes and settles contradictions, and its answer is applied only when it passes every check.

Under **Tidying**, **Model** picks that model, among those the company key allows. A small one suffices: about 5,000 tokens a month for a busy memory. With **None: duplicates only**, Tinfirm only removes duplicate notes.

The company key pays for the tidying, never a person or a trigger. It is the company's own key on the model gateway; its budget and models are under [Model access](model-access.md). The section warns when tidying falls back to duplicates only:

- the company key no longer allows the chosen model: choose another, or widen the key's models;
- the company key's budget is spent, until it resets;
- the company key is blocked, or the gateway refuses it.

Until the model gateway is set up under **Model access**, the list offers no model.

## Secrets

The company's vault holds the API keys, tokens and passwords its systems use. **Values are write-only**: Tinfirm encrypts every value and never shows it again, not even to Owners. To change a secret, store a new value; the old version stops being used.

The list shows each secret's **Name**, **Version**, when it was **Updated**, who **Created by**, and when it was **Last used**. **Find a secret** searches names and descriptions; **All**, **Added here** and **Managed** filter them.

| Kind | What it is | What you may do |
|---|---|---|
| Added here | A secret an Owner or Admin stored with **New secret** | Store a new value, change its description, delete it |
| **Managed** | A secret Tinfirm keeps for a system: a system's credential, a system's OAuth client, a chat system's own secrets, or a webhook system's signing secret. The row says what maintains it. | Change it on the system's page, never here. Delete it here only once nothing keeps it; deleting it disconnects what used it. |

The list never shows a member's own secrets, such as the tokens of their own accounts or a personal MCP server's client secret, nor Tinfirm's internal keys. A member's own secrets are deleted when they leave the company.

To store a secret:

1. Choose **New secret**.
2. Give a **Name** of letters, digits and underscores, such as `GITHUB_BOT_TOKEN`, unique in the company, and a **Description** if you like.
3. Paste the **Value**, at most 64 KiB, and choose **Store secret**.

To replace a value, choose the refresh button on its row, paste the **New value** and choose **Store new value**: it becomes the next version, for everyone who uses the secret. **Delete secret** deletes the secret and all its versions; anything that still uses it fails, and you can't undo it.

Nothing reads a value back. The server decrypts one only to use it, and records each use in the audit log as `secret.accessed`. Every file someone adds to a conversation, or an agent saves, is also checked against the vault's values: a file that holds one is refused.

## Sign-in

!!! note "Enterprise"

    SCIM provisioning needs an Enterprise licence, and the deployment must allow it for the company.

**Sign-in** shows the address your identity provider sends SCIM requests to, the role a person it creates gets, and who decides members' roles: your identity provider or Tinfirm. It lists the company's SCIM tokens. Only an Owner issues or revokes one; an Admin sees them. [Provision people with SCIM](scim.md) gives the steps.

## Licence

**Licence** shows, read-only, the **Edition** the instance runs as with its state (**Active**, **Ends soon**, **Ended, in grace**, **Expired** or **No licence**), the **Seats** used of those licensed, and when it **Ends**. One licence covers every company of the instance, and an instance admin enters its key. [Editions and prices](editions.md) says what each edition includes and what happens when a licence ends; [Enter and renew a licence key](../operator-guide/licence.md) is the instance admin's side.
