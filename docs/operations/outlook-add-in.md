# The Outlook add-in

The public guides are [Deploy the Outlook add-in](../../docs-site/content/admin-guide/outlook-add-in.md) (https://docs.tinfirm.com/admin-guide/outlook-add-in/), for the Owners and Admins who deploy it with their Microsoft 365 or Exchange administrator, and [Ask an agent from Outlook](../../docs-site/content/user-guide/outlook.md) (https://docs.tinfirm.com/user-guide/outlook/), for members. This page keeps what the people who build Tinfirm need beyond them: where the add-in is served, the Outlook requirement sets it relies on, the manifest's REST endpoint, where the pane keeps its links, its OAuth client and token flow, its upload endpoint, and its pages' CSP and frame ancestors. The decision is [ADR-0074](../adr/0074-outlook-add-in.md); the pane's links are [ADR-0076](../adr/0076-outlook-pane-thread-and-roaming-links.md); the risks are rows 10.17 to 10.19 of the [threat model](../threat-model.md).

The add-in is a page that Tinfirm serves itself, at `{TINFIRM_PUBLIC_URL}/outlook/{companyId}`. It is a client of the company's MCP endpoint, as Claude Code is ([mcp-clients.md](mcp-clients.md)).

## 1. What it needs

- **Mailbox 1.5** or later: the task pane can be pinned and follows the selected email. Outlook gives add-ins attachments from **Mailbox 1.8** on; in an older Outlook the start leaves them out and says so.
- The pane loads Office.js from `https://appsforoffice.microsoft.com`, and Office.js loads MicrosoftAjax.js for Outlook from `https://ajax.aspnetcdn.com`. The pane gives Office.js 20 seconds to start before it shows "Outlook's add-in library didn't start".

## 2. Deploy it

### Download the manifest

The manifest is the add-in-only XML manifest. Any member may fetch it with `GET /api/companies/{companyId}/outlook/manifest`, which answers JSON with the XML in `manifest` and the file name in `fileName` (session only, as the rest of the REST API). The add-in's id is derived from the public URL and the company, so every download is the same add-in; the task pane's address, the sign-in's callback and the id move with the public URL.

## 3. Use it

The pane keeps which conversation belongs to which email, for the last 100 emails, in the add-in's roaming settings in the mailbox, which Outlook shares between the mailbox's Outlooks ([ADR-0076](../adr/0076-outlook-pane-thread-and-roaming-links.md)): per email, a hash of its message id, the conversation's id, the agent's name and the action, never the email.

## 4. For operators

- The add-in's pages, `/outlook/**`, carry a CSP of their own: Office.js's path on Microsoft's CDN (`https://appsforoffice.microsoft.com/lib/1/hosted/`) in `script-src` and `connect-src`, the one file `https://ajax.aspnetcdn.com/ajax/3.5/MicrosoftAjax.js` in `script-src` (paths, not hosts: the CDNs serve other libraries too; a new Office.js that loads another file shows as "Not able to load MicrosoftAjax.js" and the CSP is widened after a check in Outlook), and `frame-ancestors 'self'` with Outlook's hosts, without `X-Frame-Options`. Every other page keeps the strict CSP ([CONVENTIONS §5](../CONVENTIONS.md#5-authentication)).
- `TINFIRM_OUTLOOK_FRAME_ANCESTORS` (`OutlookAddInProperties`) lists the hosts that frame the task pane in Outlook on the web and the new Outlook, by default `https://outlook.office.com,https://outlook.office365.com,https://outlook.live.com,https://outlook.cloud.microsoft`. Classic Outlook on Windows does not frame the pane. Each entry is an https origin, optionally with `*.` before its host; a value that is not makes the server refuse to start.
- The MCP endpoint refuses requests with an `Origin` header, except from the public URL's origin, which only Tinfirm's own pages send. A reverse proxy must pass the `Origin` header unchanged.
- The add-in signs in with Tinfirm's built-in OAuth client `tinfirm-outlook` (`BuiltInOAuthClients`), whose one redirect URI is `{TINFIRM_PUBLIC_URL}/outlook/callback`, and asks for `tinfirm:write`; nothing is registered by hand.
- Attachments travel to `POST /api/companies/{companyId}/mcp/files` before the start, one request each, with the pane's token: the ingress must allow request bodies up to `TINFIRM_ATTACHMENT_MAX_REQUEST_SIZE` there, as for uploads in the web app. A held file waits at most an hour for its start; a worker replica sweeps expired ones each minute.
