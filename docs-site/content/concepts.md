# Concepts

This page names the parts of Tinfirm that every guide talks about. Each heading is the name the web app uses, and the quoted line under a page's name is what that page says about itself.

## Agents

> The AI agents you talk to. An agent holds no credentials: it acts as whoever uses it.

An agent is a definition: a role, instructions, the systems and tools it may use, and the skills it reads. It is private to the person who made it until they share it with members or groups. Any member can make one.

An agent never owns an account. When you talk to it, it works with **your** accounts and is paid from **your** budget. When a trigger starts it, it works with the accounts and budget that the trigger's author chose.

**My settings** is your own narrowing of an agent: who pays for your turns (your budget, or a subscription of yours on your own runner host), the model, instructions added to the agent's, systems switched off and tools that ask you first, whether it keeps memory notes for you, and whether your failed turns are retried by themselves. A trigger holds settings of its own. [Make and share an agent](user-guide/agents.md) and [My settings for an agent](user-guide/my-settings.md) say more.

## Conversations and runs

A conversation is one agent at work in one place: a thread in Tinfirm, a chat in Slack or Microsoft Teams, or what a trigger started. Each turn of the agent in it is a **run**.

A conversation keeps one account per system and one payer for its whole life. Tinfirm checks them at every use and never switches to another account by itself. When an account is missing, the conversation waits and your inbox asks you to connect or choose one. [Talk to an agent](user-guide/conversations.md) says more.

Conversations you start are yours. Other members, Admins included, do not see them. A company's Owners can read any conversation in full; each such read is recorded, and you are shown that it happened.

## Inbox and decisions

> What needs you comes first: questions, approvals and accounts to connect. Then the turns that stopped, and notices.

When an agent needs a person, it does not guess. It asks, and the request lands in the inbox of whoever must answer:

| Decision | What it asks |
|---|---|
| **Question** | An answer the agent needs to go on, often with options to pick from |
| **Approval** | Permission for one action, such as a tool call that needs approval, or access to a repository or a network host. In your own conversation you decide it; in a trigger's conversation, the trigger's team does, or its author when it has no team. |
| **Review** | An approval of a trigger's result before it is written back, when the trigger asks for one |

[Your inbox](user-guide/inbox.md) says how to answer each.

## Systems

> The services agents work with: trackers, code hosts and MCP servers, the chats people talk to them in, and webhooks other software posts to. People connect their own accounts; each system holds the company's credentials on its page.

The kinds of system are Jira Data Center, Jira Cloud, GitHub, GitLab, Git, MCP server, Slack, Microsoft Teams and Webhook. A company's Owners and Admins register its systems. When the company allows it, a member can also add MCP servers of their own, which only they use. [Register systems and lend their credentials](admin-guide/systems.md) says more.

## Accounts and credentials

An **account** is who you are on a system: your own token, or your own sign-in to it through OAuth. You connect your accounts on the **Accounts** page, and an agent you talk to acts with them.

A **credential** is the company's own access to a system, kept on that system's page. Owners and Admins **lend** a credential, read-only or read and write, to groups and to service accounts; a service account borrows at most one credential on each system. When you have no account of your own on a system, an agent you talk to acts with a credential lent to one of your groups. [Connect your accounts](user-guide/accounts.md) says more.

## Service accounts

> Accounts of the company rather than a person: they act with the credentials systems lend them, in the triggers of whoever may use them, and pay with their own budget.

A service account never signs in. Use one when work should not act as, or be paid by, a particular person: for example, a trigger that comments on every new issue in a tracker. [Service accounts](admin-guide/service-accounts.md) says more.

## Triggers

> Standing rules: when something happens on a system, or on a schedule, an agent does an instruction with the accounts and the budget its author chose.

A trigger watches for an event: an issue created in Jira, a message in a chat channel, a delivery to a webhook, a time on a schedule. When it fires, it starts a conversation with its agent, or continues one. Any member who may use the agent can write a trigger. Its **team** can see it, run it now and pause it. [Write a trigger](user-guide/triggers.md) says more.

## Skills

> Know-how agents read when a task fits: a SKILL.md and the files it needs. An agent pins a version; a new version reaches no agent until its editors move the pin.

[Skills](user-guide/skills.md) says more.

## Memory notes

An agent can keep short notes for one person or for one trigger, and read them in later conversations. Memory is off until you turn it on in **My settings** (a trigger's author turns it on in the trigger's settings), and you can read and delete the notes an agent keeps for you. A trigger's new notes wait for its author or team to accept them before any conversation reads them. [Agent memory](user-guide/memory.md) says more.

## Model access, budgets and spend

Every turn of an agent calls a model through the company's model gateway, and someone pays for it. Each member and each service account has a **budget** on the gateway; a member can also run turns on a subscription of their own on their own runner host. **Spend** shows what was spent, by person, agent, trigger and model. [Your budget and spend](user-guide/spend.md) and [Model access and budgets](admin-guide/model-access.md) say more.

## Runner hosts

> The company's machines that run agents' turns in Docker, paid by a budget.

A runner host is a machine that runs agents' turns, each in its own sandbox. A company runs its own runner hosts, and a member can connect a personal one, for example their own computer. [Company runner hosts](admin-guide/runner-hosts.md) and [Install and run runner hosts](operator-guide/runner-hosts.md) say more.

## Kill switches

> Everything that is switched off in this company, who did it and why, and how to undo it. Nothing is undone by time.

Owners and Admins can stop all of the company's runs at once, pause a system, an agent or a trigger, and suspend a member or a service account. An agent's owner can pause it too, and a trigger's author and team can pause the trigger. A kill switch stays on until someone turns it off. [Kill switches](admin-guide/kill-switches.md) says more.

## Roles

A company has four roles: **Owner**, **Admin**, **Member** and **Viewer**. A Viewer reads what is shared with them but never drives an agent. [Roles](admin-guide/roles.md) says what each may do, and which roles hold a seat under a Pro licence.

## Editions

Tinfirm comes as **Community**, free, and as **Pro** and **Enterprise**, which a licence key unlocks in the same installation. [Editions and prices](admin-guide/editions.md) says what each includes.
