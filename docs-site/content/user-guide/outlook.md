# Ask an agent from Outlook

Tinfirm's Outlook add-in lets you ask one of your company's agents about the email you have open: summarise it, triage it, investigate the code it complains about, draft a reply, or anything in your own words. This page is for every member who uses it. To deploy the add-in, an Owner or Admin reads [Deploy the Outlook add-in](../admin-guide/outlook-add-in.md).

!!! note "Pro and Enterprise"

    The Outlook add-in needs a Pro or Enterprise licence: [Editions and prices](../admin-guide/editions.md).

The add-in works like an [AI client](ai-clients.md): it acts as you, and every conversation it starts is your own, with your accounts and your budget.

## Before you start

- Your company's administrators have deployed the add-in, or let you add it yourself.
- Your mailbox is on Exchange Online, Exchange on-premises or Outlook.com. Outlook does not load web add-ins for an IMAP account added to classic Outlook.
- You use Outlook on the web, the new Outlook for Windows, classic Outlook for Windows (Microsoft 365, or Outlook 2019 and later), or Outlook for Mac.
- At least one agent is shared with you, and you have a budget, as for any conversation.

## Open the add-in

Open an email and choose **Ask an agent**, in the **Tinfirm** group of the email's toolbar. In the new Outlook and on the web, it is under **Apps**. The task pane opens beside the email. Pin it to keep it open while you move from email to email: it follows the email you select.

## Sign in

The first time on a computer, the pane asks you to sign in.

1. Choose **Sign in to Tinfirm**. A window opens on Tinfirm's sign-in: sign in as you always do.
2. The consent page asks **Allow Tinfirm for Outlook to use Tinfirm as you?** for this company. It selects **Read and write**, the level the add-in needs to start conversations. The add-in does not need **Read, write and decide**: it decides no approval, which stays in Tinfirm.
3. Choose **Allow**. The window closes and the pane shows the open email.

You stay signed in on this computer as long as you use the add-in at least once every 30 days. If you chose **Read**, the pane says **This sign-in may only read. Sign out, sign in again and choose "Read and write".**

## Ask about an email

1. Choose an **Agent**. The list holds the agents you may use.
2. Choose an action, or write your own question under **Or ask in your words** and choose **Ask**.

| Action | What the agent is asked |
|---|---|
| **Summarise** | Who wrote, what they want, dates and deadlines, and what you should do next, briefly |
| **Triage** | What kind of email it is (complaint, bug report, question, request, billing), how urgent, who should handle it, and why |
| **Investigate** | For a problem with your product: the code involved in the agent's repositories, the likely cause with file and line references, how sure it is and what would fix it, without changing any code |
| **Draft a reply** | Only the text of a reply, in the email's language, promising nothing on your behalf, with what you must check in [brackets] |

The agent gets the email's subject, sender, recipients, date and plain-text body. They reach it marked as other people's words, so that an email cannot give the agent instructions.

An investigation reads the repositories the agent works with, as in any conversation. An agent meant for customer emails is best given its repositories read-only.

### Send the attachments too

When the email has attachments, **Send its attachments too** is checked. Clear it to leave them out. Attachments are added to the conversation as files you added:

- at most 20 files and 25 MB in all;
- each checked and scanned for credentials as an upload in Tinfirm is; one that holds a credential is left out, and nobody can let it through from the pane;
- an attached email goes as an `.eml` file; a link to a cloud file is left out;
- the images shown in the email's text are not attachments.

The agent's request names the files it got, and those left out with why. Outlook gives attachments to add-ins in the new Outlook, Outlook on the web and an up-to-date classic Outlook for Windows or Mac. In an older Outlook they are left out, and the request says **this Outlook does not give attachments to add-ins**.

## Follow the conversation in the pane

The pane shows the whole conversation, oldest first: your request (its action or your words; the email itself stays in Outlook), each of the agent's answers and questions, your follow-ups, and Tinfirm's notes. A turn takes minutes; the pane follows it and says where it stands, such as **Working on it. A turn takes a few minutes.** or **Waiting for a runner host.**

- **The agent asks you something.** Answer in the pane and choose **Send**. Your message answers every open question.
- **After a turn**, write a follow-up and choose **Send**. The earlier answers stay in view while the agent works on it.
- **The agent needs your approval**, or **an account of yours**: these wait for you in Tinfirm, and the pane says so. Decide or connect in your [inbox](inbox.md).
- **Use as reply**, under an answer of a turn that succeeded, opens Outlook's own reply form with that answer's text, without its next steps. Edit it and send it yourself. The add-in never sends an email.
- **Open in Tinfirm** shows the conversation with all of the agent's work.

## The pane remembers each email's conversation

When you open an email again, the pane shows its conversation instead of the start. It remembers the last 100 emails, in every Outlook where you use this mailbox, on any computer, and after you move the email to another folder. The links are kept in the add-in's settings in your mailbox, which Outlook shares between your Outlooks. For each email they hold a hash of its message id, the conversation's id, the agent's name and the action, never the email.

**Ask something else** forgets the conversation for this email and starts over. The conversation itself stays in Tinfirm.

A shared mailbox, or another account in Outlook, has settings of its own: its emails do not show conversations you started from your own mailbox. A pane that was already open when another Outlook started a conversation shows it once you open the pane again.

## Your conversations are personal

What you start from the add-in is a personal conversation, as in Tinfirm: the agent acts with your own accounts, and your budget pays. In Tinfirm it shows among your **Conversations** with the badge **AI client**. The company's Owners can read it in full; each read is recorded and shown to you.

You can start at most 30 conversations an hour from AI clients in each company, the add-in included, unless your operator set another limit. Beyond it, the pane says **You started many conversations from AI clients this hour.**

## Sign out and disconnect

**Sign out**, at the top of the pane, ends the add-in's sign-in on this computer. If Tinfirm did not confirm it, the pane says so and forgets the sign-in anyway.

To end it on every computer, open your **Profile** in Tinfirm: **Connected apps** lists **Tinfirm for Outlook** for each company, and **Disconnect** ends it at once. The add-in also stops working in a company you leave or are suspended in.

## When something goes wrong

| The pane says | What it means |
|---|---|
| **Outlook's add-in library didn't start** | Your computer does not reach Microsoft's servers for add-ins (`appsforoffice.microsoft.com`, `ajax.aspnetcdn.com`). Check the network or the VPN, then close the pane and open it again |
| **Open this from Outlook** | The page was opened outside an email in Outlook |
| **The sign-in window was blocked. Allow it and try again.** | Your browser blocked the window: allow it and sign in again |
| **Tinfirm was not allowed to connect, or you are not a member of this company.** | You chose **Deny**, or you are not a member of the company whose add-in was deployed, or you are suspended there |
| **Tinfirm refused the sign-in request** | The add-in was deployed for an old address of Tinfirm: ask your administrator to download the manifest again and redeploy it |
| **Sign in to Tinfirm again.** | The sign-in ended: it was disconnected under **Connected apps**, unused for 30 days, or you left the company |
| **You may not use any agent yet** | No agent is shared with you, or all are paused |
| **You may not use this agent, or it is paused.** | Choose another agent, or ask its owner |
| **You have no model access to pay for this** | You have no budget yet: ask your administrator |
| **No runner host can run this agent for you now.** | No runner host is available for the agent: try again later, or ask your administrator |
| **The Outlook add-in needs a Pro licence** | Your instance runs without a Pro or Enterprise licence |
| An empty pane in Outlook on the web or the new Outlook | Tinfirm does not allow this Outlook to show the pane: ask your administrator |
| No **Ask an agent** button | A new deployment takes up to 24 hours to appear, or your mailbox is not one Outlook loads add-ins for |
