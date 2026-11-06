# Members and groups

This page is for a company's Owners and Admins. It explains how you add people to the company, give them a role, put them in groups, suspend them, and remove them, and what Tinfirm does with what a member leaves behind.

Everything here happens on the **Members** page, in the sidebar's **Company** section. It has two tabs, **Members** and **Groups**, each with its count.

## The Members tab

The tab lists the people of the company, one row each: their name and email, their role, the status of their accounts on the company's systems in one line (for example *Jira must sign in again · GitHub connected*), and the day they were added. A suspended member carries the badge **Suspended**.

- **Find a member by name or email** searches the list.
- The filters **All**, **Owners**, **Admins**, **Members**, **Viewers** and **Suspended** narrow it, each with its count.

Under the tabs, the page shows the [seats](roles.md#seats) the instance uses, for example **12 of 20 seats used on this instance**. When every seat is taken, it adds that new people join as Viewers until one is free. Under Community, or Enterprise without a seat count, it shows the count and says that nothing is counted against a licence.

## Add a member

You add a person who already has a user on this Tinfirm instance:

- a local user, which an instance admin creates on the **Users** page ([The Instance admin pages](../operator-guide/instance-admin.md)), or
- a user of the company's identity provider, who is created on their first sign-in.

1. On the **Members** tab, choose **Add member**.
2. Enter their **Email**: the email of their Tinfirm user.
3. Pick a **Role**: **Member**, **Admin**, **Owner** or **Viewer**. [Roles](roles.md) says what each may do.
4. Choose **Add member**.

The person can open the company at once. Tinfirm refuses the addition when:

| You see | Why |
|---|---|
| The email field says no user exists | Nobody on the instance has this email. Ask an instance admin to create the user, or ask the person to sign in once through single sign-on. |
| The email field says the user is already a member | They are in the company already. Open their row to change their role. |
| **Your role cannot add members with this role.** | Only an Owner adds an Owner. The dialog says so under the roles: **Only an owner adds an owner.** |
| **Every seat of the licence is taken, so only a Viewer can be added now.** | Under a Pro licence with every seat taken, the dialog picks **Viewer** for you. Make someone else a Viewer, or ask an instance admin for a key with more seats. |

Someone you removed earlier and add again starts afresh: nothing of before comes back.

## Members who join through the identity provider

When the deployment binds an identity provider to the company, a person who signs in through it for the first time becomes a member by themselves, with the role the deployment gives that provider: **Member**, **Admin** or **Viewer**, never **Owner**. The operator sets this up: [Declare companies and how their people sign in](../operator-guide/sign-in.md).

- When that role holds a seat and every seat is taken, the person joins as a **Viewer** instead, and you get an inbox item: **… joined as a Viewer: every seat was taken**. Change their role once a seat is free.
- A person you removed is not added again when they sign in later through the provider: the provider only adds people the first time it sees them.
- When the operator lets the provider decide roles, it sets each member's role again at every sign-in, from their groups or app roles at the provider. Their row shows **managed by** the provider, and a person the provider gives no role cannot sign in, keeping their membership: [Roles from your identity provider](roles.md#roles-from-your-identity-provider).
- With SCIM, the identity provider adds, changes, suspends and removes members itself, before they ever sign in. A member it suspended is resumed only by it: [Provision people with SCIM](scim.md).

## Open a member

Choose a row to open the member's dialog. It has these sections:

| Section | What it shows and does |
|---|---|
| **Role** | The four roles as cards. Pick one; a bar **Role changes to …** appears with **Save**. |
| **Accounts** | Which company systems the member holds an account on, and its status: **Connected**, **Linked** (a chat system), **Must sign in again** or **Not working**. Nothing more: you never see who they are there, nor their personal MCP servers. Only the member connects or disconnects these accounts. |
| **Groups** | The groups they are in. **Add to a group** and the cross on a group change it at once. |
| **Budget** | What the conversations they start may spend on models: the company's default or their own, with when it resets. **Change under Model access** leads to [Model access and budgets](model-access.md). |
| **Suspend** or **Resume**, **Remove from …** | At the foot. See below. |

### Change a role

1. Pick the new role in **Role**, then choose **Save**.
2. A confirmation, **Make … a member?** for example, lists what the new role grants or takes away. It applies at once.

The rules:

- Only an Owner gives, changes or takes away the **Owner** role.
- A role **managed by** the identity provider changes at the provider, not here: only the **Owner** card can be picked, and making them an Owner makes the role Tinfirm's.
- The company always keeps an active Owner. The only Owner's role is locked, with **The only owner: make someone else an owner first.**
- Giving a seat role (Owner, Admin or Member) to a Viewer needs a free seat when seats are counted. Making someone a Viewer frees theirs, and pauses the triggers they wrote: a Viewer authors no trigger and uses no service account. An admin may reassign those triggers to a member who holds a seat.
- When you lower your own role below Admin, the **Company** pages close for you at once.

### Remove a member's chat link

Under **Accounts**, a member's link to a Slack or Microsoft Teams account shows **Remove link**: the one account an admin may remove for a member. Use it when the chat account is no longer theirs, or was taken over. The bot refuses that chat account's direct messages until the member links again; their open direct-message conversations stay, readable in the app, and the audit log records the removal. [Set up Slack and Microsoft Teams](chat-systems.md) explains chat links in full.

## Groups

A group is a named set of members. It adds to what its members' roles allow and never takes anything away. A group can:

- have agents and skills shared with it, from the agent's or the skill's page;
- receive a system's credential, read-only or read and write, lent on the system's page under **Credentials** ([Systems and their credentials](systems.md));
- be allowed to use a service account in triggers ([Service accounts](service-accounts.md));
- be a trigger's team, which sees the trigger, runs it, pauses it and decides its approvals.

The **Groups** tab lists each group with its members' initials, its member count, and what it may use: the agents shared with it, the credentials lent to it (for example *GitHub with svc-dev, read-only*) and the service accounts it may use.

### Create a group

1. On the **Groups** tab, choose **New group**.
2. Give it a **Name** (up to 100 characters, unique in the company) and, if you like, a **Description**.
3. Under **Members**, choose **Add a member** and pick people. **Find a member by name or email** narrows the list.
4. Choose **Create group**.

Then share agents with it and lend it credentials on their own pages. To put one person in several groups, open them on the **Members** tab and use **Add to a group**.

### Change or delete a group

Choose the group's row. Rename it, change its description, add or take out members, then choose **Save**; **Discard** drops your changes. The dialog also shows **What its members may use through it**.

**Delete…** removes the group. What it was given goes with it: its members no longer use the agents shared with it, the credentials lent to it or the service accounts it may use. They stay members of the company. A trigger whose team it was keeps going without a team, and its open approvals move to the trigger's author. You can't undo a deletion.

## Suspend and resume a member

Suspending is a [kill switch](kill-switches.md): use it when an account may be compromised or someone must stop at once, without removing them.

1. Open the member and choose **Suspend…**.
2. Give a **Reason**. The member sees it while they are suspended; it is kept with the switch, never in the audit log.
3. Choose **Suspend**.

Until you resume them:

- nothing acts as them, with their accounts or on their budget, and their running turns are cancelled;
- they write, decide and read nothing in the company: they can open only its **Kill switches** page, which tells them they are suspended. Their tokens and connected AI clients stop working there;
- the triggers they wrote pause.

**Resume** lifts it. Their waiting turns go on. Their triggers stay paused until they or an admin resume each one.

Nobody suspends themselves, only an Owner suspends an Owner, and the last active Owner cannot be suspended. When an instance admin deactivates a person's Tinfirm user, they are suspended in every company; such a suspension is lifted only by reactivating the user, and the page says so.

## Remove a member

1. Open the member and choose **Remove…**.
2. The confirmation says what else happens, for example *2 triggers they wrote pause until an admin reassigns them*, then **Remove member**.

They lose access at once. Only an Owner removes an Owner, and the last Owner cannot be removed. You can remove yourself; you lose access right away.

### What happens when a member leaves

| What was theirs | What happens |
|---|---|
| Their accounts on systems, and the secrets they owned | Deleted, and revoked at the system where Tinfirm can |
| Their personal MCP servers and personal runner hosts | Deleted |
| Their personal conversations | End at once; running turns are cancelled. Only the Owner can read them, until retention removes their content or the Owner erases it |
| The triggers they wrote | Pause, **Its author left the company**, until an admin reassigns each to another member, who accepts it, which resumes it. Their open conversations wait |
| The agents and skills they owned | Pass to the admins, who edit them from then on |
| Their **My settings** of agents, their model key, their place in groups and in agents' shares | Removed |
| Their spend and the audit log | Stay |

The person keeps their Tinfirm user: removing a member never deletes it.

## A member who left

After you remove someone, Tinfirm opens their checklist, **… left**: what they left behind and who takes it over. While it is open, the **Members** tab shows a banner, *… left: what they left behind needs someone*, with **Open the checklist**, and every Owner and Admin has an inbox item **… left the company**.

| Item | What to do |
|---|---|
| **1. Triggers they wrote** | Open each and reassign it, or delete it. Its waiting conversations go on with the new author. |
| **2. Groups left without members** | Nobody in them decides their approvals any more: add members, or give their triggers another team. |
| **3. System credentials they connected** | The token or the sign-in may be theirs: on the system's page, store a new token or sign it in again. |
| **4. Agents passed to the admins** | Pick a new owner and choose **Give**, or leave **Keep with the admins**. |
| **5. Change requests opened with their own account** | They stay at the code host under their name; someone may take them over there. |
| **6. Their personal conversations** | **Holds and erasure** leads to [Retention](retention.md), to erase their content now. |

Items 1 to 3 keep the checklist open. Once they are done, **Close the checklist** ends the admins' inbox item. You can close it with items open: **Close anyway** leaves them as they are, and the audit log records that you accepted it.
