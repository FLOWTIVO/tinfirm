# Ask an agent from Gmail

Tinfirm's Gmail add-on lets you ask one of your company's agents about the email you have open in Gmail: summarise it, triage it, investigate the code it complains about, draft a reply, or anything in your own words. This page is for every member who uses it. To deploy the add-on, an Owner or Admin reads [Deploy the Gmail add-on](../admin-guide/gmail-add-on.md).

!!! note "Pro and Enterprise"

    The Gmail add-on needs a Pro or Enterprise licence: [Editions and prices](../admin-guide/editions.md). Without one, the add-on's card says **The Gmail add-on needs Tinfirm's Pro edition.**

The add-on works like an [AI client](ai-clients.md): it acts as you, through the Google account you link to your Tinfirm account, and every conversation it starts is your own, with your accounts and your budget.

## Before you start

- Your company's administrators have deployed the add-on in Google Workspace, or installed it for you.
- You use Gmail with a Google Workspace account or, where your company allows it, a personal Google account. Gmail on the web shows the add-on in its side panel; Gmail's mobile apps show it under an open email.
- At least one agent is shared with you, and you have a budget, as for any conversation.

## Link your Google account

The first time, the add-on links the Google account you use in Gmail to your Tinfirm account.

1. Open an email and choose the Tinfirm icon in Gmail's side panel, on the right. Google first asks you to allow the add-on what it needs (see [What the add-on may do in Gmail](#what-the-add-on-may-do-in-gmail)).
2. The card names the Google account to link. Choose **Sign in to Tinfirm**. A window opens on Tinfirm: sign in as you always do.
3. The page asks **Link your Google account to Tinfirm?** It names the Google account, as Google gave its email, and when the link was asked for. Choose **Link to my account** only if you just chose **Sign in to Tinfirm** in Gmail yourself. **It wasn't me** links nothing.
4. Close the window. The side panel reloads, signed in.

The sign-in link works once, for 10 minutes. Each link is for one company: a Google account is linked to at most one member of a company.

| The link page says | What to do |
|---|---|
| **This link has expired** | Choose **Sign in to Tinfirm** in Gmail again for a new link |
| **This link was already used** | The same, if Gmail still asks you to sign in |
| **This Google account is someone else's** | Another member of the company linked this Google account. They disconnect it on their profile first |
| **This link is not for this account** | It is unknown, replaced by a newer link, or for another company. Check that you are signed in to Tinfirm as yourself |
| **This link is incomplete** | Choose **Sign in to Tinfirm** in Gmail again, and sign in in the window it opens |

## Ask about an email

Opening an email sends Tinfirm nothing of it. Only when you choose an action does Tinfirm read that one email from Gmail.

1. Choose an **Agent**. The list holds the agents you may use; the one you used last is selected.
2. Choose **Summarise**, **Triage**, **Investigate** or **Draft a reply**. Or write **Your question about this email**, under **Or in your own words**, and choose **Ask**.

| Action | What the agent is asked |
|---|---|
| **Summarise** | Who wrote, what they want, dates and deadlines, and what you should do next, briefly |
| **Triage** | What kind of email it is (complaint, bug report, question, request, billing), how urgent, who should handle it, and why |
| **Investigate** | For a problem with your product: the code involved in the agent's repositories, the likely cause with file and line references, how sure it is and what would fix it, without changing any code |
| **Draft a reply** | Only the text of a reply, in the email's language, promising nothing on your behalf, with what you must check in [brackets] |

The agent gets the email's subject, sender, recipients, date and plain-text body. They reach it marked as other people's words, so that an email cannot give the agent instructions.

### Send the attachments too

**Send its attachments too** is checked unless you clear it. Tinfirm then reads the email's attachments from Gmail too, and adds them to the conversation as files you added:

- at most 20 files and 25 MB in all;
- each checked and scanned for credentials as an upload in Tinfirm is; one that holds a credential is left out;
- the images shown in the email's text are not attachments.

The agent's request names the files it got, and those left out with why: a credential in it, too large, more than 20 files, not sent, or **Gmail did not give it**.

## Follow the conversation in the card

The card shows the conversation, oldest first: your request (its action or your words; the email stays in Gmail), the agent's answers and questions, your follow-ups and Tinfirm's notes. It shows the newest ten messages, each cut at 2,000 characters, and says where the conversation stands.

A turn takes minutes, and a Gmail card does not refresh itself. Choose **Refresh**, or open the email again, to see the answer.

- **The agent asks you something.** Write **Your answer** and choose **Send**.
- **After a turn**, write **Your follow-up** and choose **Send**.
- **The agent needs your approval**, or **an account of yours**: these wait for you in Tinfirm, and the card says so. Decide or connect in your [inbox](inbox.md).
- **Open in Tinfirm** shows the whole conversation with the agent's work.
- **Ask something else** starts over for this email.

### Save an answer as a draft reply

Under an answer of a turn that succeeded, **Use as reply** saves the answer (the turn's summary, without its next steps) as a draft reply in the email's thread:

- to the email's Reply-To address, or else its sender; when you sent the email yourself, to its recipients;
- with `Re:` and the email's subject.

The card says **The answer is a draft reply in this thread: edit it and send it from Gmail.** and offers **Open the draft**. Edit the draft and send it from Gmail yourself. The add-on never sends an email.

## The add-on remembers each email's conversation

Tinfirm remembers which conversation belongs to which email, for your last 100 emails. When you open the email again, or another email of the same thread, the card shows its conversation, on any computer and in Gmail's apps. For another email of the thread, the card says **About an earlier email of this thread.** Tinfirm keeps, for each email, a hash of Gmail's ids for the message and its thread, never the ids or the email.

## Your conversations are personal

What you start from the add-on is a personal conversation, as in Tinfirm: the agent acts with your own accounts, and your budget pays. In Tinfirm it shows among your **Conversations** with the badge **AI client**. The company's Owners can read it in full; each read is recorded and shown to you.

You can start at most 30 conversations an hour from AI clients in each company, the add-on included, unless your operator set another limit.

## What the add-on may do in Gmail

When you first open the add-on, Google asks you to allow it to:

- run in Gmail;
- read the email you have open and its attachments, and only that email;
- save a draft reply while you act in the add-on;
- know your Google account's email address.

When your administrators replace the add-on with a newer version, Google may ask again the next time you open it.

## Sign out and disconnect

**Sign out** on the add-on's card ends the link of this Google account at once, and the card offers **Sign in to Tinfirm** again.

Your **Profile** in Tinfirm lists your linked Google accounts under **Gmail**, with the company each acts in, when you linked it and when it was last used. **Disconnect** ends one at once. A link also ends after 30 days without use, and when you leave the company.

## When something goes wrong

| The card says | What it means |
|---|---|
| **You may not use any agent yet** | No agent is shared with you, or all are paused. Ask an Admin of your company to share one with you |
| **Gmail did not give the email to Tinfirm. Open it again and retry.** | Gmail refused the read. If it keeps happening, tell your administrator |
| **Gmail did not save the draft. Open the email again and retry.** | If it keeps happening, the add-on may predate **Use as reply**: tell your administrator |
| **The email names nobody to reply to.** | The email has no sender or Reply-To address to answer |
| The card still says it is working | Choose **Refresh**: a card does not refresh itself |
| Gmail says the add-on could not be loaded | Tinfirm is not reachable from Google, or the add-on is not set up yet: tell your administrator |
