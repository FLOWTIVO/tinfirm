# Deploy the Gmail add-on

Tinfirm's Gmail add-on lets members ask one of the company's agents about the email they have open in Gmail. This page is for the Owners and Admins who deploy it, with their Google Workspace administrator and the operator who runs Tinfirm. How members use it is in [Ask an agent from Gmail](../user-guide/gmail.md).

!!! note "Pro and Enterprise"

    The Gmail add-on needs a Pro or Enterprise licence: [Editions and prices](editions.md). Without one, **Settings**, **General** shows a **Pro** badge beside **Gmail** and offers no deployment file, and the add-on's card in Gmail says that it needs Tinfirm's Pro edition.

## How it works

The add-on is a Google Workspace add-on whose endpoints Tinfirm serves itself. When a person opens an email, or chooses a button, Google calls Tinfirm, and Gmail shows the card Tinfirm answers in its side panel. Tinfirm checks that each request was signed by Google for your deployment before it reads anything else.

Each person links their Google account to their Tinfirm account once. The add-on then acts as they do in an AI client: every conversation it starts is that person's own, with their accounts and their budget. Opening an email sends Tinfirm nothing of it: Tinfirm reads the one email from Gmail only when the person asks about it, or saves a draft reply.

The pieces, and who sets each one:

| Piece | Who | Once per |
|---|---|---|
| A Google Cloud project, with its OAuth consent screen | A Google Cloud administrator | Instance |
| The project's service account and OAuth client, set in Tinfirm | Tinfirm's operator | Instance |
| The company's deployment, created from Tinfirm's deployment file | You, in the Google Cloud project | Company |
| The add-on published to your domain and installed | A Google Workspace administrator | Company |

When one instance holds several companies, they share the project, and each creates a deployment of its own.

## What it needs

| Need | Why |
|---|---|
| Tinfirm at an **https** address that Google reaches from the internet | Google calls the company's endpoints on it. An installation that only a private network reaches cannot serve the add-on |
| The server reaches `www.googleapis.com` and `gmail.googleapis.com` | Google's keys, which sign its requests, and the Gmail API, which gives the open email |
| A Google Cloud project with the Google Workspace add-ons API and the Gmail API on | The deployment lives in it |
| Gmail with a Google Workspace account, or a personal Google account | Gmail on the web shows the add-on in its side panel; Gmail's mobile apps show it under an open email |
| Agents shared with the people who use it, model access and a runner host | As for any conversation |

## Prepare the Google Cloud project

Do this once for the instance.

1. Choose or create a Google Cloud project, and turn on its APIs:

    ```sh
    gcloud config set project <project>
    gcloud services enable gsuiteaddons.googleapis.com gmail.googleapis.com
    ```

2. Configure the project's OAuth consent screen (Google Auth Platform, **Branding** and **Audience**). For a Google Workspace domain, choose **Internal**: only your domain's accounts may use the add-on, and Google does not review it. For personal Google accounts, choose **External** and keep it in **Testing**, with the accounts that may use it as test users.
3. Read the project's service account and OAuth client:

    ```sh
    gcloud workspace-add-ons get-authorization
    ```

    It answers `serviceAccountEmail` (`service-<number>@gcp-sa-gsuiteaddons.iam.gserviceaccount.com`) and `oauthClientId`.

4. Give both to Tinfirm's operator, who sets them as `TINFIRM_GMAIL_SERVICE_ACCOUNT` and `TINFIRM_GMAIL_CLIENT_ID`: [Server configuration](../reference/configuration.md). Until both are set, Tinfirm refuses Google's requests, and **Settings**, **General** says so under **Gmail**.

## Create the company's deployment

1. On **Settings**, **General**, under **Gmail**, choose **Download deployment file** beside **Gmail add-on**. It saves `tinfirm-gmail-deployment.json`. Nothing in it is secret.
2. Create the deployment in the project:

    ```sh
    gcloud workspace-add-ons deployments create tinfirm-<company> --deployment-file=tinfirm-gmail-deployment.json
    ```

3. Try it on your own account:

    ```sh
    gcloud workspace-add-ons deployments install tinfirm-<company>
    ```

    Open Gmail on the web: the Tinfirm icon is in the side panel on the right. Open an email and choose it to link your own Google account.

The deployment file names the company's endpoints on Tinfirm's address as the add-on's home page and as its trigger for every email opened. Its links may open only Tinfirm and Gmail (to open a saved draft). It asks for four scopes:

| Scope | What it allows |
|---|---|
| `gmail.addons.execute` | Running in Gmail |
| `gmail.addons.current.message.readonly` | Reading the open email and its attachments, and only that email |
| `gmail.addons.current.action.compose` | Saving a draft reply while the person acts in the add-on |
| `userinfo.email` | The Google account's email address, which the link page shows |

## Publish it to your domain

1. In the same project, open the **Google Workspace Marketplace SDK** and choose **App configuration**.
2. Choose the visibility **Private**, the integration **Google Workspace add-on**, and the deployment's id, then publish it.
3. A Google Workspace administrator installs it for the domain or for groups in the Admin console, under **Apps**, **Google Workspace Marketplace apps**.

## Replace the deployment

When Tinfirm's address changes, or Tinfirm's deployment file changes, download the file again and replace the deployment:

```sh
gcloud workspace-add-ons deployments replace tinfirm-<company> --deployment-file=tinfirm-gmail-deployment.json
```

A deployment created before **Use as reply** has only three scopes, without `gmail.addons.current.action.compose`: replace it. Each person allows the new scopes the next time they open the add-on.

## What members see

1. In an open email, the Tinfirm icon in the side panel. Google first asks them to allow the add-on its scopes.
2. **Sign in to Tinfirm**, which opens Tinfirm in a window. After they sign in, the page names the Google account to link and when it was asked for; **Link to my account** links it. The link works once, for 10 minutes. A Google account is linked to at most one member of a company.
3. An **Agent** to choose, and **Summarise**, **Triage**, **Investigate**, **Draft a reply**, or their own question with **Ask**. **Send its attachments too** adds the email's attachments: at most 20 files and 25 MB in all, each scanned for credentials as an upload is. Google waits only about half a minute for Tinfirm's answer, which is why a start sends no more.
4. The conversation in the card, with **Refresh**, **Send**, **Open in Tinfirm** and **Ask something else**. **Use as reply** saves an answer as a draft reply in the email's thread, which they edit and send from Gmail: the add-on never sends an email.

Their conversations are personal, shown in Tinfirm with the badge **AI client**, and the audit log of what they do names the Gmail link. As for every AI client, a person may start a limited number of conversations an hour (30 by default, which your operator can change). Each person's **Profile** lists their linked Google accounts under **Gmail**, where **Disconnect** ends one. A link also ends after 30 days without use, and when the person leaves the company. An **Investigate** reads the repositories the agent works with: give an agent meant for customer emails its repositories read-only.

## What your operator must allow

Pass these to whoever runs Tinfirm: [Install on Kubernetes with Helm](../operator-guide/helm.md) and [Server configuration](../reference/configuration.md) have the details.

- The two settings from [Prepare the Google Cloud project](#prepare-the-google-cloud-project). Without both, the add-on's endpoints answer as if they did not exist.
- The ingress routes `/api/gmail/…` from the internet to the server. Google calls from no fixed addresses.
- The server reaches `www.googleapis.com` and `gmail.googleapis.com` on port 443. Behind the Helm chart's NetworkPolicy, this needs an egress rule.

Tinfirm uses the Google tokens of a request for that request only, to read the one email a start is about, or to save a draft, and neither keeps nor logs them.

## Troubleshooting

| You see | Cause | Fix |
|---|---|---|
| Gmail says the add-on could not be loaded, and Tinfirm logs nothing | Google does not reach Tinfirm, or the operator has not set the project's two settings | Check the ingress and the settings |
| Gmail says the add-on could not be loaded, and Tinfirm's log says the deployment's token **is for another URL** | The deployment names an old address of Tinfirm | Download the file again and replace the deployment |
| The log says the token **is another service account's** or **is for another OAuth client** | The operator's settings are not this project's | Compare them with `gcloud workspace-add-ons get-authorization` |
| The log says a request **is signed with an unknown key** | The server could not read Google's keys | Check its egress to `www.googleapis.com` |
| **Gmail did not give the email to Tinfirm** | The Gmail API is not on in the project, or the server does not reach `gmail.googleapis.com` | Turn the API on; check the egress |
| **Gmail did not save the draft** | The deployment predates **Use as reply** | [Replace the deployment](#replace-the-deployment); people then open the add-on again and allow the new scope |
| **This Google account is someone else's** | Another member of the company linked this Google account | They disconnect it on their **Profile** first |
| An attachment left out because **Gmail did not give it** | Tinfirm could not read the attachment; its log says why | Check the egress to `gmail.googleapis.com` |
| The card says the add-on needs Tinfirm's Pro edition | The instance has no Pro or Enterprise licence | An instance admin enters a licence key: [Enter and renew a licence key](../operator-guide/licence.md) |
