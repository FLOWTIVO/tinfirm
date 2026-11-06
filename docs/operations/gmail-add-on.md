# The Gmail add-on

The public guides are [Deploy the Gmail add-on](../../docs-site/content/admin-guide/gmail-add-on.md) (https://docs.tinfirm.com/admin-guide/gmail-add-on/), for the Owners and Admins who deploy it with their Google Workspace administrator and operator, and [Ask an agent from Gmail](../../docs-site/content/user-guide/gmail.md) (https://docs.tinfirm.com/user-guide/gmail/), for members. This page keeps what the people who build Tinfirm need beyond them: the endpoints Google calls and the REST endpoint of the deployment file, how each request and its tokens are verified and used, where attachments wait, and the log lines behind the public troubleshooting. The decision is [ADR-0083](../adr/0083-gmail-add-on.md); the risks are rows 10.20 to 10.23 of the [threat model](../threat-model.md). The add-on acts as an AI client does ([mcp-clients.md](mcp-clients.md)), as the person who linked their Google account.

## 1. What it needs

Google calls `{TINFIRM_PUBLIC_URL}/api/gmail/{companyId}/home`, `/open` and `/act` (`GmailAddOnController`): the add-on's homepage and its contextual trigger, for every email opened, are the company's endpoints, and the cards' buttons call `/act`. That is why the public URL must be https and reachable from the internet.

## 2. Deploy it

`GET /api/companies/{companyId}/gmail/deployment` serves the deployment file to any member: JSON with the file in `deployment`, its name in `fileName`, and whether the operator configured the project in `configured`. **Download deployment file** on **Settings**, **General** saves it.

## 3. Use it

The audit of what a conversation started from the add-on does names the link (`gmailLinkId`). The starts count against `TINFIRM_MCP_STARTS_PER_HOUR`, as any AI client's.

## 4. For operators

- `TINFIRM_GMAIL_SERVICE_ACCOUNT` is the `serviceAccountEmail` that Google's token of each request must carry, `TINFIRM_GMAIL_CLIENT_ID` the `oauthClientId`, the audience the person's token must carry. Without both, `/api/gmail/**` answers 404.
- `/api/gmail/**` is outside the session chain, without cookies or CSRF. Each request is verified before anything else is read: Google's two RS256 ID tokens against Google's keys (`https://www.googleapis.com/oauth2/v3/certs`, kept a day), the issuer `https://accounts.google.com`, not expired; the deployment's token for the endpoint's URL under `TINFIRM_PUBLIC_URL` and the service account; the person's token for the OAuth client. A refused request is 401, logged with why (`A Gmail add-on request for company … was refused: …`), never with a token.
- The server's egress to `www.googleapis.com` and `gmail.googleapis.com` on 443 is the chart's `networkPolicy.egress.systems`, open to public addresses by default.
- The tokens of a request (the person's OAuth token and the message's token) are used for that request only, to read the one email a start is about and its attachments, or its reply headers and save a draft, and are neither kept nor logged.
- An attachment waits as a held file (`staged_file`) only between its read and the start, at most an hour; a worker replica sweeps expired ones each minute. Google waits about half a minute for the add-on's answer, which is why a start sends at most 25 MB of attachments.

## 5. When it does not work

The public troubleshooting quotes the log lines in an operator's words. Behind them: `was refused: the deployment's token is for another URL`, `the deployment's token is another service account's`, `the person's token is for another OAuth client` and `is signed with an unknown key` end the verification above; Gmail's "could not be loaded" with nothing in Tinfirm's log is Google not reaching Tinfirm, or the 404 of an unconfigured instance; an attachment that **Gmail did not give** is logged as `could not read an attachment`, with why.
