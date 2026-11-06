# Admin guide

This guide is for a company's **Owners** and **Admins**: the people who set the company up so that its members' agents can work. It assumes you know the [concepts](../concepts.md). To install Tinfirm itself, read the [Operator guide](../operator-guide/index.md).

## The Company pages

Owners and Admins see a second section in the sidebar, **Company**:

| Page | What it is for |
|---|---|
| **Systems** | The services agents work with: trackers, code hosts and MCP servers, the chats people talk to them in, and webhooks other software posts to. Each system's page holds the company's credentials for it and lends them. |
| **Service accounts** | Accounts of the company rather than a person: they act with the credentials systems lend them, in the triggers of whoever may use them, and pay with their own budget. |
| **Model access** | The company's model gateway, the budgets that pay for agents' turns, and what they spent. |
| **Runner hosts** | The company's machines that run agents' turns in Docker, paid by a budget. |
| **Members** | The people of the company, their roles, and the groups they form. |
| **Kill switches** | Everything that is switched off in this company, who did it and why, and how to undo it. Nothing is undone by time. |
| **Audit log** | Every change in this company, newest first. Each event is chained to the one before it. |
| **Retention** | How long this company keeps what its conversations said and made. The audit log is kept for good. |
| **Settings** | How the company is named, what may arrive from outside, how agents' memories are tidied, the secrets in its vault, and SCIM provisioning. How its people sign in is the deployment's configuration, not a setting. |

On the **Work** pages, Owners and Admins also see and edit every agent, skill and trigger in the company, not only their own.

**Settings**, **General**, also holds the manifest of Tinfirm's Outlook add-in and the deployment file of its Gmail add-on, with which members ask agents about an email without leaving Outlook or Gmail.

## Set up a company

A new company can do nothing until it has a way to pay for models, a machine to run on, and systems to work with. In order:

1. **Model access**: under **Which LiteLLM**, check the gateway the company uses, and under **Default budget**, set the budget and models of every member and service account without their own. [Model access and budgets](model-access.md)
2. **Runner hosts**: register at least one company runner host. Without one, no turn paid by a budget runs. [Company runner hosts](runner-hosts.md)
3. **Systems**: register the systems agents should work with, add the company's credentials, and lend them to groups or service accounts. [Register systems and lend their credentials](systems.md)
4. **Members**: add people by the email of their Tinfirm user, give each a role, and put them in groups. [Members and groups](members.md)

Members can then make agents, connect their own accounts and start conversations: the [User guide](../user-guide/index.md) is theirs.

## In this guide

- [Roles](roles.md): what an Owner, an Admin, a Member and a Viewer may do, which roles hold a seat, and what an instance admin may do in a company.
- [Editions and prices](editions.md): what Community, Pro and Enterprise include, what Pro costs, and what happens when a licence ends.

### Set up the company

- [Model access and budgets](model-access.md): the model gateway, the budgets that pay for agents' turns, and the spend.
- [Company runner hosts](runner-hosts.md): register the machines that run agents' turns.
- [Members and groups](members.md): add people, give them roles, form groups, and what happens when a member leaves.
- [Provision people with SCIM](scim.md): let your identity provider add, change and remove your members, with its token.
- [Set up SCIM with Microsoft Entra ID](scim-entra-id.md) and [with Okta](scim-okta.md): each step at the provider.
- [Service accounts](service-accounts.md): accounts of the company that triggers act with and pay from.
- [Company settings](settings.md): the company's name, what may arrive from outside, memory, secrets and the licence.

### Systems

- [Register systems and lend their credentials](systems.md): Jira, GitHub, GitLab and git hosts; the company's credentials and whom they are lent to.
- [Give agents the tools of an MCP server](mcp-servers.md): connect MCP servers and confirm what each of their tools does.
- [Set up Slack and Microsoft Teams](chat-systems.md): the Tinfirm bot in your workspace or tenant.
- [Receive webhooks](webhooks.md): let any software start agents.
- [Start an agent from an alert](alerts.md): an agent that looks into each alert from Alertmanager or another alerting tool.

### Add-ins

- [Deploy the Outlook add-in](outlook-add-in.md) and [the Gmail add-on](gmail-add-on.md), so that members ask agents about an email.

### Govern

- [Kill switches](kill-switches.md): stop the company's runs, pause a system, an agent or a trigger, suspend an account.
- [The audit log](audit-log.md): every change, chained so that it cannot be rewritten.
- [Retention, legal holds and erasure](retention.md): how long the company keeps what its conversations said and made.
