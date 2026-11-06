# Make and share an agent

An agent is a role you define once and others use: its instructions, the systems and tools it may call, the repositories it works in, and the skills it reads. This page is for every member who makes an agent, changes one, or wants to know what an agent they use may do and why.

An agent holds no credentials. When you talk to it, it acts with your accounts and is paid from your budget; when a trigger starts it, it acts with the accounts the trigger's author chose. The agent only sets the most it may ever do.

## The Agents page

**Agents**, in the sidebar's **Work** section, lists the agents you see. Switch between **I can use** (yours and those shared with you or your groups) and **All I see** (for Owners and Admins, every agent of the company).

Each row shows the agent's name, `@handle`, description, runtimes, version and owner, and these chips when they apply:

| Chip | Means |
|---|---|
| **Paused** | Its turns wait until someone resumes it ([below](#pause-resume-or-delete-an-agent)). |
| **New version to review** | Its current version may do more than the one you accepted, or changes the instructions or skills of an agent that can act ([below](#change-an-agent-versions-widening-and-narrowing)). |
| **1 skill update**, **2 skill updates** | Shown to its editors: the library has newer versions of skills it pins. |

**Talk to** starts a conversation with it, **My settings** opens your own narrowing of it ([My settings for an agent](my-settings.md)), and **Edit** opens the editor, for whoever may edit it. A Viewer reads the agents but does not talk to them, create or edit them.

## Make an agent

1. On **Agents**, choose **New agent**.
2. Fill in the sections. The editor shows one at a time: **Who it is**, **Instructions**, **Skills**, **What it may use** and **Whom you share it with**. Any tab opens any section; **Back** and **Next** step through them. Only a name and a runtime are required: the rest can wait for a later version.
3. Choose **Create agent**. It is saved as version 1, private to you until you share it.

Any member can make an agent, and owns the agents they make.

### Who it is

| Field | What it takes |
|---|---|
| **Name** | Up to 100 characters, unique in the company. |
| **Handle** | How people mention it, for example `@business-analyst` in a chat. 1 to 50 lower-case letters, digits and hyphens, not starting or ending with a hyphen. Empty: made from the name. Unique in the company. |
| **Description** (optional) | What it does, in a line or two: up to 1000 characters. People read it when they pick an agent. |
| **Runtimes** | The runtimes its instructions work on: **Claude Code**, **Codex**, **pi** and **OpenCode**. One is the **Default** (**Make default** changes it); each person may pick another of them in their settings. A new agent starts on the runtime that suits how your company pays for models, and says why: **Claude Code** when the company's gateway serves a Claude model, **Codex** when it serves OpenAI's models only, **pi** when it serves other providers' models; without a gateway, the runtime of your own subscription, else **Claude Code**. |
| **Time limit of a turn** | Minutes one turn may run before the runner host stops it, from 5 to 1440. Empty: the installation's default, which the field shows (usually 60). A stopped turn keeps its work: retry it and the agent goes on from where it stopped. |
| **Memory** | **People and triggers may turn its memory on**, on by default. Each person who turns memory on gets notes of their own; a trigger's notes wait for its team's review. Turning it off keeps the notes, unread and unwritten. See [Agent memory](memory.md). |

### Instructions

The instructions are the first part of every prompt the agent gets, in Markdown, up to 100,000 characters. Say what the role is, how it works and what a good result looks like. When the agent can act (below), a change of them waits until each person who uses it accepts it; otherwise everyone gets the new instructions at their next turn, and each conversation says so.

A person can add instructions of their own after yours in [My settings](my-settings.md); a trigger adds its own instruction.

### Skills

Skills are know-how from the company's library, each pinned to a version. The agent reads one when a task fits. [Skills](skills.md#give-an-agent-a-skill) says how to add one and move its pin.

### What it may use

What the agent may call besides its own workspace. With nothing here, it works only in its workspace.

**Systems and tools.** Choose **Add a system** and pick one of the company's systems: **Issue trackers** (Jira Data Center, Jira Cloud), **Code hosts** (GitHub, GitLab, Git), **MCP servers**, and **Chat** (Slack, whose one tool reads a channel's messages). Your own MCP servers are not offered here: each person adds theirs in their own settings.

Adding a system lists every tool it serves and has switched on. Each tool row shows its effect and its mode:

- The effect is **Reads**, **Writes** or **Unconfirmed: counts as a write**. Tinfirm knows the effects of its own tools for Jira, GitHub, GitLab and Slack; an MCP server's tools are confirmed by an admin, and until then each counts as a write.
- The mode is **Allowed** (the call runs without asking) or **Needs approval** (each call waits for an approval). Reads start as **Allowed**, writes and unconfirmed tools as **Needs approval**. Change any of them, or remove a tool.
- **It also serves:** lists the system's tools the agent does not list, to add back. A tool the agent does not list is not available to it at all.
- A tool its system no longer serves reads **No longer offered: its system does not serve it**. Remove it.

A tool you allowed while it read, and which now writes or is unconfirmed, needs approval again until you pick **Allowed** once more. So a change at an MCP server never lets a write run unasked.

On GitHub and GitLab, **Open a pull request** and **Open a merge request** are not called by the agent. After a turn that pushed commits, Tinfirm opens the change request itself, under that tool's mode: at once when it is **Allowed**, after an approval when it **Needs approval**. Without the tool, the branch stays pushed and the conversation says why.

An agent lists at most 100 systems and 200 tools per system.

**Repositories.** A code host on the agent lists the repositories an admin added on the system's page. Tick the ones the agent works in, each **Read-only** (cloned, never pushed) or **Read and write** (it may push a branch and open a change request). At most 100. A code host with no tools serves the agent its repositories only.

**Network hosts.** What else its runs may reach, such as a package registry, with a reason of up to 500 characters. Write the host's name only, without `https://` or a path: `registry.npmjs.org`, `registry.npmjs.org:8443`, or `*.example.com`, where the wildcard stands for the first label only. IP addresses, and wildcards over shared domains such as `*.github.io`, are refused. Its systems need no entry. At most 100 hosts. When a run needs a host the agent does not list, it asks ([below](#the-tools-every-run-has)).

### Whom you share it with

Tick **Groups**, and find **People** by name or email: someone in none of the groups, or in no group at all, may use it on their own. Each person who uses the agent acts with their own accounts and pays with their own budget. Nobody else may use it until you share it.

- At most 50 groups and 100 people.
- Sharing makes no new version and widens nothing.
- A deleted group is taken from every agent shared with it, and a member who leaves from every agent shared with them.
- Taking an agent from someone pauses the triggers they wrote with it ([Write a trigger](triggers.md#when-a-trigger-pauses-or-waits-by-itself)).

## How a tool call is allowed

Every call an agent makes to a system's tool passes three layers. Each layer can only narrow what the one before allows.

| Layer | Who sets it | What it does |
|---|---|---|
| 1. The tool's effect | Tinfirm, or an admin for an MCP server | Every tool is a read or a write. A tool whose effect nobody confirmed counts as a write. |
| 2. The mode on the agent | Whoever edits the agent | **Allowed** or **Needs approval**. A tool the agent does not list cannot be called. |
| 3. Settings, account and lending | The person, or a trigger's author; the system; an admin | Settings switch a system or a tool off, or make a tool ask first, never the reverse. The account the conversation acts with can do only what the system lets it do. A credential lent read-only refuses every write tool. |

Besides these, a system an admin paused refuses every call.

When a call needs approval, the agent is told that it waits and ends its turn. The approval goes to your **Inbox** and the conversation: in your own conversation you decide it; in a trigger's conversation, the trigger's team does, or its author when it has no team. **Approve** runs the call with the conversation's account, after the checks pass again; **Deny** runs nothing. [Your inbox](inbox.md) says more.

When a call is refused, the agent is told why, for example that a tool *is not one of {agent}'s tools* or *is switched off in {person}'s settings*, and says so in its summary when its work needed the tool.

## The tools every run has

Besides the systems' tools, Tinfirm gives every run a few tools of its own. They are not set per agent.

| Tool | What the agent does with it |
|---|---|
| `ask` | Asks a question, with up to 10 options to pick from. The conversation waits for the answer. |
| `request_access` | Asks for a repository of a code host the agent lists, with a reason. Whoever decides the conversation's approvals chooses **Allow for this conversation**, **Allow for {agent}, for good** (only someone who may edit the agent sees it) or **Deny**, and picks **Read only** or **Read and write**. Tools cannot be requested: they belong to the agent. |
| `request_network_access` | Asks for a network host, with the same three choices. Offered only on runner hosts that guard outbound traffic; elsewhere, add the host to the agent. |
| `save_artifact` | Saves a file or a link the conversation keeps, rather than committing a file only to share it. |
| `list_repositories` | Lists the repositories the run may ask for. |
| `finish` | Reports how the turn ended: the outcome, a summary, next steps and open questions. |
| `remember`, `forget`, `recall` | Keep and read memory notes, listed only while memory is on for the conversation ([Agent memory](memory.md)). |

## The agent's page

Open an agent from **Agents**. The page shows:

- Its version, with **(you use N)** when you have not accepted the latest, its runtimes, and its owner (or **owned by the company's admins** once its owner left).
- **When you use it**: who you act as on each of its systems (**Acts as**), who pays (**Pays**) and where its turns run (**Runs on**), for you.
- **What it may use**: **Systems and tools**, each tool with its effect and **Allowed** or **Needs your approval**; **Repositories** with their access; **Network hosts**.
- **Instructions**, and **Skills** at the versions it pins, each with a download of the version it reads.
- **Shared with**: the groups and people who may use it.

If it is not shared with you, **You cannot use this agent** says whom to ask.

## Change an agent: versions, widening and narrowing

**Edit** on the agent's page opens the editor. **Save** sends only the sections you changed. Every change of what the agent is makes a new version.

A version **widens** the one before when it:

- adds a system, or adds a tool;
- raises a tool from **Needs approval** to **Allowed**, or allows a tool for writes that was allowed only while it read;
- adds a repository, or raises one from read-only to read and write;
- adds a network host that no host before covers;
- changes the instructions, adds a skill or moves a skill's pin, when the agent **can act** after the change: it has a tool **Allowed** for writes, a repository with read and write access, or a network host.

Removing or lowering anything widens nothing, a removed skill included, and nor do changes to the name, handle, description, runtimes, time limit or memory switch. A change of the instructions or skills of an agent that cannot act reaches everyone at their next turn: each conversation then says *The agent's instructions changed on 2026-10-27 by Jane Doe.* (or its skills), and the change is in the audit log.

- **Narrowing reaches everyone at once**, from each conversation's next turn.
- **A widening reaches each person only once they accept it.** Until then, their turns use the current version without what it adds. Before you save, the editor names what widens: **This change widens {agent}**, and people who use it keep their version until they accept the change.
- People who use the agent see **{agent} has a new version to accept** on its page and in **My settings**, with what it adds and **Accept version N**: *What it no longer has applies already; only this waits.* A change of the instructions shows as the lines removed and added; a skill as the version it moves to and the one it was. Until they accept, their turns keep the instructions and skills of the version they accepted.
- You accept your own change, when you had accepted the version before it. Approving a repository or a host **for good** also makes a new version, which the person who approved it accepts the same way.
- A trigger's runs keep the version its author accepted for the trigger, until the author accepts the new one on the trigger's page ([Write a trigger](triggers.md#a-new-version-of-its-agent)).

## Pause, resume or delete an agent

Whoever may edit an agent can pause it, for example when it misbehaves. **Pause** on its page asks **Pause {agent}?**; tick **Also stop the turns running now** to cancel its running turns too, then choose **Pause agent**.

- A paused agent's conversations start no turn and run no approved call. Its queued turns wait. People see **{agent} is paused; its turns wait.**
- Approving its approvals is refused while it is paused; answers and denials are taken.
- Its triggers fire nothing. Nothing that happened meanwhile is caught up after the resume.
- Owners and Admins see it on **Kill switches**. **Resume** on the agent's page undoes it. Pausing and resuming make no version.

**Delete** ends its conversations (their history stays), deletes everyone's settings of it and its triggers, and frees its name and handle. It is refused while one of its turns is queued or running: wait for it, or cancel it. You can't undo a delete.

## Who sees and edits an agent

| Who | Sees it | Uses it | Edits, shares, pauses, deletes it |
|---|---|---|---|
| Its owner | Yes | Yes | Yes |
| A member it is shared with, directly or through a group | Yes | Yes | No |
| The company's Owners and Admins | Yes | Only as its owner or through a share | Yes |
| Anyone else | No | No | No |

When an owner leaves the company, their agents pass to the company's admins, and the people they are shared with go on using them. Admins can give such an agent a new owner when they handle the departure ([Members and groups](../admin-guide/members.md)). A Viewer may see an agent shared with their group, but never uses it.

## When something goes wrong

| What you see | Why | What to do |
|---|---|---|
| The agent says a tool *is not one of {agent}'s tools* | The agent does not list it, or you have not accepted the version that adds it | Ask whoever edits the agent to add it, or accept the new version on its page |
| A tool that ran unasked now asks each time | It now writes, or its effect is unconfirmed | Whoever edits the agent picks **Allowed** again, if it should run unasked |
| A write is refused though the agent allows it | The conversation acts through a credential lent read-only | Connect your own account on **Accounts**, or ask an admin for read and write access |
| The agent never asks for a network host | Its runner host does not guard outbound traffic, so the request tool is missing | Add the host under **Network hosts** |
| **Allow for {agent}, for good** is missing | You may not edit the agent | Allow it for this conversation, or ask its owner |
