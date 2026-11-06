# Deploy the Outlook add-in

Tinfirm's Outlook add-in lets members ask one of the company's agents about the email they have open, without leaving Outlook. This page is for the Owners and Admins who deploy it, with their Microsoft 365 or Exchange administrator. How members use it is in [Ask an agent from Outlook](../user-guide/outlook.md).

!!! note "Pro and Enterprise"

    The Outlook add-in needs a Pro or Enterprise licence: [Editions and prices](editions.md). Without one, **Settings**, **General** shows a **Pro** badge beside **Outlook** and offers no manifest, and the add-in's requests are refused.

## How it works

The add-in is a page that Tinfirm serves itself, at your Tinfirm's address. Outlook shows it in a task pane beside the open email. It is a client of the company's MCP endpoint, as Claude Code is ([Work with Tinfirm from your AI client](../user-guide/ai-clients.md)): each person signs in once and allows it to act as them, and every conversation it starts is that person's own, with their accounts and their budget.

Outlook loads the pane from people's own computers. Microsoft's servers do not need to reach Tinfirm, so Tinfirm need not be reachable from the internet, only from your people's computers.

Each company has its own add-in: a person in two companies uses two add-ins, each signed in to its own company.

## What it needs

| Need | Why |
|---|---|
| Tinfirm at an **https** address that people's computers reach | Outlook loads the task pane from it |
| Mailboxes on Exchange Online, Exchange on-premises or Outlook.com | Outlook loads web add-ins for these mailboxes only, not for IMAP accounts in classic Outlook |
| Outlook on the web, the new Outlook for Windows, classic Outlook for Windows (Microsoft 365, or Outlook 2019 and later), or Outlook for Mac | The pane can be pinned and follows the selected email |
| People's computers reach `appsforoffice.microsoft.com` and `ajax.aspnetcdn.com` | Every add-in loads Microsoft's add-in library from there |
| Agents shared with the people who use it, model access and a runner host | As for any conversation |
| Your operator allows Outlook to show the pane | See [What your operator must allow](#what-your-operator-must-allow) |

## Download the manifest

On **Settings**, **General**, under **Outlook**, choose **Download manifest** beside **Outlook add-in**. It saves `tinfirm-outlook-manifest.xml`, the add-in's manifest for this company. It names the task pane at your Tinfirm's address with the company's id, the icons, and one button, **Ask an agent**, in a group **Tinfirm** on the toolbar of an open email.

Nothing in it is secret. Every download is the same add-in, whose id is made from Tinfirm's address and the company, so you may download it again at any time.

## Deploy it to your organisation

An Exchange or Microsoft 365 administrator deploys it, so that everyone chosen gets the button without doing anything:

1. In the Microsoft 365 admin center, open **Settings**, **Integrated apps**, and choose **Upload custom apps**.
2. Choose **Office Add-in** and upload `tinfirm-outlook-manifest.xml`.
3. Choose who gets it: everyone, or the groups who use Tinfirm.
4. Deploy it. The button may take up to 24 hours to appear in people's Outlook.

## Or let people add it themselves

Where your organisation lets people add their own add-ins, give them the manifest file. In Outlook, they open **Get Add-ins** (the **Apps** button in the new Outlook and on the web), then **My add-ins**, **Custom Addins**, **Add a custom add-in**, **Add from File**, and choose the manifest.

## What your operator must allow

Pass these to whoever runs Tinfirm. [Server configuration](../reference/configuration.md) lists the settings, and [Install on Kubernetes with Helm](../operator-guide/helm.md) says where to set them.

- **The Outlooks that may show the pane.** Outlook on the web and the new Outlook show the pane in a frame, which Tinfirm allows only for the Outlook addresses in `TINFIRM_OUTLOOK_FRAME_ANCESTORS`. By default these are `https://outlook.office.com`, `https://outlook.office365.com`, `https://outlook.live.com` and `https://outlook.cloud.microsoft`. A national cloud's Outlook needs its own addresses added: each an `https` address, which may start its host with `*.`. The server does not start with any other entry. Classic Outlook for Windows does not use a frame. The add-in's pages carry a security policy of their own; every other page of Tinfirm keeps refusing to be framed.
- **The `Origin` header.** The add-in calls the company's MCP endpoint from Tinfirm's own address. A reverse proxy in front of Tinfirm must pass the request's `Origin` header unchanged.
- **Uploads.** Attachments travel to Tinfirm before a conversation starts, one request each: the ingress must allow request bodies as large as for uploads in the web app.

Nothing needs registering by hand: the add-in signs in with an OAuth client built into Tinfirm, **Tinfirm for Outlook**, whose callback is on Tinfirm's own address.

## What members see

1. In an open email, **Ask an agent** opens the pane, and **Sign in to Tinfirm** opens Tinfirm's sign-in.
2. The consent page asks **Allow Tinfirm for Outlook to use Tinfirm as you?**, with **Read and write** selected. The add-in answers the agent's questions in the pane, but decides no approval: approvals, and accounts to connect, wait in the person's inbox.
3. They choose an agent and **Summarise**, **Triage**, **Investigate** or **Draft a reply**, or ask in their own words. The agent gets the email's subject, sender, recipients, date and plain-text body, and its attachments unless they clear **Send its attachments too**: at most 20 files and 25 MB in all, each scanned for credentials as an upload is.
4. The pane follows the conversation. **Use as reply** opens Outlook's own reply form with an answer, which the person edits and sends: the add-in never sends an email.

Their conversations are personal, shown in Tinfirm with the badge **AI client**. As for every AI client, a person may start a limited number of conversations an hour (30 by default, which your operator can change). Each person's **Profile** lists **Tinfirm for Outlook** under **Connected apps**, where **Disconnect** ends it on every computer. An **Investigate** reads the repositories the agent works with: give an agent meant for customer emails its repositories read-only.

## When Tinfirm's address changes

Download the manifest again, deploy it, and remove the old one. The task pane's address, the sign-in's callback and the add-in's id all change with Tinfirm's address. Until then, people see **Tinfirm refused the sign-in request** when they sign in.

## Troubleshooting

| People see | Cause | Fix |
|---|---|---|
| No **Ask an agent** button | The deployment is less than 24 hours old, or the mailbox is not one Outlook loads web add-ins for | Wait, or check the mailbox type |
| **Outlook's add-in library didn't start** | Their computer does not reach `appsforoffice.microsoft.com` or `ajax.aspnetcdn.com` within 20 seconds | Allow both in the network, proxy or VPN |
| An empty pane in Outlook on the web or the new Outlook | That Outlook's address is not among those allowed to frame the pane | Your operator adds it; the browser's console names the address refused |
| **Tinfirm refused the sign-in request** | The manifest names an old address of Tinfirm | Download the manifest again and redeploy it |
| **Tinfirm was not allowed to connect, or you are not a member of this company.** | They chose **Deny**, are not a member of the company whose manifest you deployed, or are suspended there | Add them on **Members**, or deploy the right company's manifest |
| **This sign-in may only read** | They chose **Read** on the consent page | They sign out, sign in again and choose **Read and write** |
| **You may not use any agent yet** | No agent is shared with them, or all are paused | Share an agent with them or their group |
| **The Outlook add-in needs a Pro licence** | The instance has no Pro or Enterprise licence | An instance admin enters a licence key: [Enter and renew a licence key](../operator-guide/licence.md) |
| An attachment left out because **it holds a credential** | Tinfirm's scan found a secret or a credential format in it | Nobody can let it through from the pane: remove the credential, or upload the file in Tinfirm, where an Owner or Admin may acknowledge a format |

### Inspect the task pane

The browser console of the task pane names what failed: an address the computer does not reach, a script the security policy refused, an answer of Tinfirm.

- **Outlook on the web and the new Outlook:** the browser's developer tools (++f12++); the pane is a frame of Tinfirm's address.
- **Outlook for Mac:** quit Outlook, run `defaults write com.microsoft.Outlook OfficeWebAddinDeveloperExtras -bool true` in Terminal and start Outlook again; then right-click in the pane and choose **Inspect Element**.
- **Classic Outlook for Windows:** follow Microsoft's guide to debugging add-ins with the developer tools of Microsoft Edge.
