# Service accounts

A service account is an account of the company rather than a person. It acts with the credentials systems lend it, in the triggers of whoever may use it, and pays with its own budget. This page is for Owners and Admins, who create and manage service accounts on the **Service accounts** page.

A service account never signs in to Tinfirm and holds no seat. It is the account a trigger can act as and be paid by, so that work does not depend on any one person.

## When to use one

Use a service account when work should not act as a particular person, or be paid by one:

- A trigger that comments on every new issue in a tracker should comment as a bot user, not as whoever wrote the trigger.
- A trigger that runs for a team should keep working when its author is away, changes role or leaves.
- A team's automation should be paid from a budget of its own, and seen apart in **Spend**.

A person's own account is the right choice for work they start themselves: their own conversations always act with their own accounts first. To let members act with a company credential in their own conversations, lend the credential to their group instead ([Lend a credential](systems.md#lend-a-credential)). A service account is for triggers.

| | A member's own account | A credential lent to a group | A service account |
|---|---|---|---|
| Who acts | The member, as themselves | The group's members, as the credential's user | Triggers, as the credential's user |
| Where | Their own conversations, and their triggers | Their own conversations, when they hold no account of their own | The triggers of whoever may use it |
| Who pays | The member's budget | The member's budget | The service account's budget |

## Create a service account

1. Open **Service accounts** and choose **New service account**.
2. Enter a **Name**, at most 100 characters and unique in the company, ignoring case, such as `svc-support`. Add a **Description** of what it is for, at most 500 characters.
3. Choose **Create**. Its page opens.

A new service account borrows nothing, and only Owners may use it. Then:

1. Lend it a credential on each system it acts on, from that system's page.
2. Under **Who may use it**, pick the groups whose members may put it on their triggers.
3. Check its budget under **Model access**.

**Edit details** changes the name and the description.

## Lend it credentials

Credentials are lent on each system's page, never on the service account's page ([Lend a credential](systems.md#lend-a-credential)). On the system's **Overview**, under the credential, choose **Lend to a service account**, pick the **Service account** and the **Access**, then **Lend it**. The access is **Read and write** by default; **Read-only** refuses every tool that writes and every push.

- **At most one credential per system.** Lending a service account a second credential on the same system replaces the first. The dialog says so before you lend. Its triggers that acted with the old one name the new one at once, their open conversations take it at their next check and say so in the thread, and their authors are told in their inbox, unless they lent it themselves.
- **One credential, several borrowers.** A credential can be lent to several service accounts and groups at once.
- **Withdraw** (the **×** on the lending's row) leaves the service account without a credential on that system. Its triggers that act with it there pause until their authors name another account.

Storing a new token for a credential, or signing it in again, changes nothing for the service account: the credential keeps its id, and its triggers go on.

## Its page

The page shows the service account at a glance: how many systems it **Borrows** a credential on, how many **Triggers** use it, and its **Budget this period**. Below:

- **Borrowed credentials**: per system, the credential it borrows, the credential's state, its access, since when, and how many of its triggers act with it. The list is read-only: **Change on** the system's name, such as **Change on GitHub**, opens the credential on its system's page.
- **Who may use it**: the groups whose members may pick it for their triggers.
- **Triggers that use it**: every trigger that acts through it or is paid by its budget, with each trigger's state.
- A chat system's bot it borrows, if any. A chat bot is lent to a service account on the chat system's **Bot** tab, and only receives and posts in the chat ([Set up Slack and Microsoft Teams](chat-systems.md)).
- **Budget**: what it spent this period and what it may spend.

## Who may use it

**Who may use it** lists the groups whose members may pick the service account, the credentials it borrows and its budget, for what they set up, such as triggers. **Change** opens **Who may use** *svc-support*, where you tick the groups, at most 100; it replaces the groups as a whole.

Owners always may use every service account. Nobody else may without one of its groups, Admins included: an Admin manages a service account but uses it only through a group. This is not lending: to let members act with a credential in their own conversations, lend it to their group on the system's page.

When a member is no longer in any of its groups, their triggers that use the service account pause until they, or an admin, name another account.

## Its budget

Each service account has its own key at the company's model gateway, never shared, and a budget that pays for every turn done as it. Until you give it a budget of its own, it has the company's default. Set it under **Model access**, **Members' budgets**, where service accounts are listed with the members ([Model access and budgets](model-access.md)). Its spend shows under **Spend**, apart from any person's.

## Use it on a trigger

A trigger's author chooses its accounts in the trigger editor's **Acts as and pays** step ([Write a trigger](../user-guide/triggers.md)). **Use the accounts of** offers the author and every service account they may use, each with what it would act with on each system and its budget. The author's choice fills **The account per system**, and **Paid by** names who pays: the author or a service account.

The trigger checks its accounts when it is saved, and again before every firing and every use. Nothing in it switches by itself, except when an admin lends the service account another credential in place of the one it borrowed.

| What happened | The trigger |
|---|---|
| The credential was withdrawn from the service account, or the author may no longer use the service account | Pauses until the author names another account |
| The credential must be signed in again, needs a new token or is disabled | Pauses. Once the credential works again, its author or an admin resumes it |
| The service account it acts as is suspended | Pauses. Once the service account is resumed, its author or an admin resumes it |
| The service account that only pays for it is suspended | Waits, and goes on by itself once the service account is resumed |
| The author left the company | Pauses until an admin hands it to another author |

A paused trigger never resumes by itself: a person resumes it, and nothing that happened while it was paused fires.

## Suspend and resume

**Suspend** is the service account's kill switch. The dialog says: *Everything that acts through it or on its budget stops: the credentials it borrows act for nothing through it, and the turns running as it or on its budget are cancelled, until you resume it. Groups that borrow the same credentials go on.* Add a **Reason** if you like; it is kept with it.

While it is suspended, its model key is blocked, and its conversations wait with *… waits for an admin*. The triggers that act as it pause, and those it only pays for wait. The page shows **Suspended** with the time and the reason, and [Kill switches](kill-switches.md) lists it. Nothing is undone by time.

**Resume** makes it **Active** again. Its waiting conversations and triggers go on. The triggers that paused stay paused until their authors or an admin resume them ([Use it on a trigger](#use-it-on-a-trigger)).

To stop one credential everywhere instead, disable the credential on its system's page. To stop everything on a system, pause the system ([The three switches](systems.md#the-three-switches)).

## Delete

**Delete** removes the service account for good. The credentials it borrows are withdrawn from it and stay with their systems, lent to whoever else they are lent to.

Deleting is refused while a live trigger acts through it or is paid by its budget, and while it borrows a chat system's bot. Change or delete those triggers first, and remove the bot under the chat system's **Bot** tab.
