# Roles

Each member of a company has one of four roles in it. The **Members** page describes them so:

| Role | In one line |
|---|---|
| **Owner** | Everything an admin does, and the company's retention and who else is an owner. |
| **Admin** | Sets the company up: systems and their credentials, service accounts, groups, budgets, runner hosts and the model gateway. |
| **Member** | Talks to agents, defines agents and triggers, and connects their own accounts and runner host. |
| **Viewer** | Reads the conversations and results shared with their groups and sees their inbox, but starts no work and answers nothing. Holds no seat. |

A role belongs to one company. The same person can be an Owner in one company and a Member in another.

Under a Pro licence, Owners, Admins and Members each hold a [seat](#seats); Viewers do not.

## Who may do what

| Task | Owner | Admin | Member | Viewer |
|---|---|---|---|---|
| Talk to agents, make agents, write triggers | Yes | Yes | Yes | No |
| Connect your own accounts, add a personal runner host | Yes | Yes | Yes | No |
| Add your own MCP servers, when the company allows it | Yes | Yes | Yes | No |
| See and edit every agent and trigger | Yes | Yes | Only their own; agents shared with them, to use; triggers whose team they are in, to see, run and pause | No; reads the conversations and results shared with their groups |
| Register systems, add and lend their credentials | Yes | Yes | No | No |
| Manage service accounts and groups | Yes | Yes | No | No |
| Set the model gateway and budgets | Yes | Yes | No | No |
| Register company runner hosts | Yes | Yes | No | No |
| Manage the company's secrets | Yes | Yes | No | No |
| Add, suspend and remove members | Yes | Yes, except Owners | No | No |
| Stop all of the company's runs, pause systems | Yes | Yes | No | No |
| Pause agents and triggers | Every one | Every one | Their own agents; triggers they wrote or whose team they are in | No |
| Read the audit log | Yes | Yes | No | No |
| See spend | Every run; another person's personal conversation by its key, without its title | Every run; other people's personal conversations summed per person | Their own, and their triggers' | No |
| Read other people's conversations | Yes, in full; each read is recorded and shown to the person | No | No | No |
| Appoint or remove Owners | Yes | No | No | No |
| Change retention, set legal holds, erase a person's content | Yes | No; reads the retention periods | No | No |
| Answer questions, decide approvals and reviews, when asked | Yes | Yes | Yes | No |

A company always keeps at least one active Owner: the last one cannot be removed, demoted or suspended.

## Viewers

A Viewer can sign in, read the conversations and results shared with their groups, and see their inbox. A Viewer never:

- starts or continues a conversation;
- owns or edits an agent or a skill;
- writes a trigger;
- answers a question, or decides an approval or a review.

An agent never sends a question or an approval to a Viewer. When the only people who could answer a request are Viewers, it goes to the company's Owners and Admins instead, so that it never waits on someone who may not answer. Viewers still read the request and its outcome where they read the conversation.

A Viewer sees a note that they are a Viewer in the company, and that an Owner or Admin can make them a Member.

Make someone a Viewer when they need to follow the agents' work but not drive it: a manager who reads the results, an auditor, a colleague between projects.

## Seats

A seat is what a Pro licence counts. It follows the role:

| Role | Holds a seat |
|---|---|
| Owner, Admin, Member | Yes |
| Viewer | No |

These never hold a seat: Viewers, service accounts, members who left, and the people who use agents without signing in to Tinfirm, such as whoever mentions the bot in a channel trigger's channel or answers an agent's question on an issue. Their runs are paid from the trigger's budget, and a trigger's author always holds a seat.

- **Seats are checked when a role is given.** When every seat is taken, making someone who holds no seat yet an Owner, Admin or Member is refused (`409 SEATS_EXHAUSTED`). Make another member a Viewer first, or buy more seats. No right is ever taken away in the middle of work, and no action is refused for want of a seat.
- **Signing in never fails for want of a seat.** When a person signs in through the company's identity provider, for the first time or, when [the provider decides roles](#roles-from-your-identity-provider), any time, and the role it gives them holds a seat but none is free, they get Viewer, and the company's Owners and Admins are told in their inbox. The inbox item, **… joined as a Viewer: every seat was taken**, links to **Members**. It names the person and the role their provider gives, stays while they are still a Viewer, for 30 days at most, and can be dismissed. Give them the role once a seat is free.
- **Seats count people across the instance.** When one instance holds several companies, they share one pool of seats, and a person who holds a seat role in two companies takes one seat. Deactivated users take none.
- **Community, and Enterprise without a seat count**, have the same roles, and count nothing.

The **Members** page shows the seats under its tabs, for example **12 of 20 seats used on this instance**, and says when every seat is taken that new people join as Viewers until one is free. The **Licence** section of the company's **Settings** shows them too. [Editions and prices](editions.md) says what a seat costs.

## Roles from your identity provider

Your operator can let the company's identity provider decide its members' roles, from a claim such as Entra ID's app roles or a list of groups. Then:

- **The role follows the identity provider, at every sign-in.** Someone your IT team moves into the admins' group becomes an Admin the next time they sign in; someone moved out becomes what the provider says then. Each change is in the company's audit log, made by the system, naming the provider.
- **Members shows the role as "managed by" the provider**, for example **managed by Microsoft**, and Owners and Admins cannot change it there. Ask whoever runs the identity provider instead.
- **Owner stays yours.** The provider never makes anyone an Owner and never changes or refuses an Owner. An Owner can make a managed member an Owner; their role is then Tinfirm's while they are one. An Owner made an Admin again becomes the provider's at their next sign-in.
- **A person the provider gives no role cannot sign in** through it: they see **No role for you in this sign-in**. Their account, membership and work stay. To take someone's access away for good, remove them on **Members**.
- **Seats still count**, as above: without a free seat, a seat role waits as Viewer until a later sign-in finds one.

When the operator has not set this up, the provider gives a new member a role once, and Owners and Admins change it on **Members** after.

## Groups

A group is a named set of members. It is not a role and never takes rights away: it adds to what its members' roles allow. A group can:

- receive credentials that systems lend it;
- be allowed to use a service account in triggers;
- have agents and skills shared with it;
- be a trigger's team.

## Instance admins

An instance admin manages the Tinfirm installation itself: its users and companies. This is a flag on the person, separate from company roles.

An instance admin who is a member of a company acts there by their role, like anyone else. One who opens a company they are not a member of gets an Admin's view of it, and the opening is recorded in the company's audit log. That view cannot read people's conversations. Its one addition is that it can appoint an Owner, for a company that has no Owner left to reach.
