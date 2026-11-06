# Skills

A skill is a procedure written once for every agent that needs it: a `SKILL.md` with the instructions, and any scripts, references or templates it uses. The company keeps its skills in one library, and each agent pins the version of a skill it reads. This page is for every member who writes skills, and for whoever edits agents that use them.

The **Skills** page says it in one line: *Know-how agents read when a task fits: a SKILL.md and the files it needs. An agent pins a version; a new version reaches no agent until its editors move the pin.*

## What a skill is

A skill is a folder in the Agent Skills format, which Claude Code, Codex, pi and OpenCode all read:

- `SKILL.md` at its root starts with front matter: a line `---`, then YAML with its `name` and `description`, then a line `---`. The instructions follow, in Markdown.
- The `name` is 1 to 64 lower-case letters and digits, in words joined by single hyphens, such as `write-jira-ticket`. It is the skill's folder name in the sandbox, and unique among the company's skills.
- The `description` (up to 1,024 characters) is how an agent knows when to read the skill. Say what it is for and when to use it.
- The front matter may also hold `license`, `compatibility`, `metadata` and `allowed-tools`, and nothing else.
- Other files sit beside `SKILL.md`, in folders up to 8 deep.

An agent's runtime lists only each skill's name and description to the model. The model reads the rest of a skill when a task fits it. So an agent with ten skills pays for ten descriptions, and for the instructions of the one it uses.

A skill adds no rights. Its scripts run in the agent's sandbox, with the agent's tools and network hosts and nothing more. A skill never needs a credential: its agent acts with the accounts of whoever uses it.

## The Skills page

**Skills**, in the sidebar's **Work** section, lists the skills you see: yours, those shared with you or your groups, and for Owners and Admins every skill. **Find a skill by name or description** filters the list. Each row shows the skill's name, latest version and description, and a line such as **Owned by Alice Novak · shared with Analysts · used by 2 agents**.

## Create a skill

1. On **Skills**, choose **New skill**.
2. Under **Upload from**, pick one:
    - **Write SKILL.md**: write it in the page. It starts with the front matter to fill in, and the page shows the name it reads. Up to 100,000 characters. This makes a skill of that one file.
    - **Upload a zip**: **Choose a zip** of the skill's folder, with `SKILL.md` at its root or under its one top folder. At most 5 MiB, 200 files, and 5 MiB of files unpacked.
3. Add a **Note** if you like, such as *First version* (up to 500 characters).
4. Choose **Create skill**. It is version 1, private to you until you share it.

Tinfirm checks every upload before it stores it: the format, the paths (relative, no `..`, `\` or `:`, no two differing only in case), the sizes, and a scan for credentials. A skill that holds a secret from the company's vault, or a value in a known credential format, is refused, and nothing is stored: *Remove it and upload again.* When a name is taken, the page says so: name the skill otherwise, or upload a new version of that skill from its page.

## Add a new version

Whoever edits a skill opens it and chooses **New version**. A version keeps the skill's name: the `name` in `SKILL.md` must stay the same.

- **Write SKILL.md** starts from the latest version's `SKILL.md`. A version written this way holds that one file only: the page warns that the latest version's other files are left out. Upload a zip to keep them.
- **What changed** is the note for this version, such as *Adds the acceptance criteria*.
- **Upload version** stores it. The same files as the latest version store nothing: **Nothing new to store**.

*No agent moves to it by itself: their editors see that it is there, and move the pin when they choose.* A skill keeps at most 100 versions; past that, create a new skill.

## Read a skill's versions

A skill's page shows **Versions**. Each version stays exactly as it was uploaded, with its note, who uploaded it, its files and its size. Open one to see its files and its `SKILL.md`, or download it as a zip named `{name}-v{version}.zip`.

The page also says how many agents use the skill: *Each agent pins a version. A new version reaches an agent when its editors move the pin; the agent's page tells them it is there.*

## Share a skill

A skill is private to its owner until shared, as an agent is. Under **Shared with**, its owner ticks **Groups** and picks **People**, then chooses **Save sharing**. Sharing a skill lets people *see it and add it to the agents they edit*; it does not let them change it.

Taking a share away takes the skill from no agent that already pins it.

## Who edits a skill

| Who | Sees it, reads its versions | Adds it to agents they edit | Adds versions, shares, deletes it |
|---|---|---|---|
| Its owner | Yes | Yes | Yes |
| A member it is shared with, directly or through a group | Yes | Yes | No |
| The company's Owners and Admins | Yes | Yes | Yes |
| Anyone else | No | No | No |

Any member can create skills and owns those they create. When an owner leaves the company, their skills pass to the company's admins. Viewers create and edit no skill.

## Give an agent a skill

Whoever edits an agent chooses its skills, in the editor's **Skills** section (**Edit skills** on the agent's page opens it).

1. Under **The company's library**, find the skill among those you see, and add it. It joins at its latest version.
2. Under **Its skills**, **Pinned at** shows the version the agent reads. Pick another version to move the pin, back or forward.
3. Choose **Save**.

An agent holds at most 20 skills, and no two with the same name. **Remove** takes a skill off the agent.

When the library has a newer version than the one an agent pins, its editors see it: **Update to vN** in the editor, **Update to vN available** on the agent's page, and a chip such as **1 skill update** on **Agents**. The pin moves only when an editor moves it, so whoever edits a shared skill changes no agent they cannot edit.

A change of an agent's skills makes a new version, as a change of its instructions does. When the agent can act (a tool **Allowed** for writes, a repository with read and write access, or a network host), a skill added or a pin moved waits until each person who uses it accepts it ([Make and share an agent](agents.md#change-an-agent-versions-widening-and-narrowing)); otherwise, and for a skill removed, everyone gets it at their next turn, and each conversation says so. People who see the agent can download the version of each skill it pins from the agent's page.

## How a run gets its skills

When a turn starts, the runner host gets each skill at the version the agent pins, checks it and puts its files in the sandbox, read-only, where the runtime looks for a user's skills, in a folder named after the skill. The runtime lists the skills to the model by name and description; Tinfirm's prompt adds nothing about them.

A turn of an agent with skills runs only on a runner host that can deliver skills. While none is connected, the turn waits with **No host for its skills**, rather than run without them.

## Switch a skill off for yourself

In [My settings](my-settings.md#skills) for an agent, each of its skills is **Read when a task fits** or **Off for your conversations**. A trigger's author does the same for the trigger's runs.

## Delete a skill

Its owner or an admin chooses **Delete** on the skill's page. The skill leaves the library: nobody can add it to an agent again or move a pin to another of its versions, and its name is free again. You can't undo it.

- By default, the agents that pin a version of it keep that version, and their runs still get it. The agent's editor sees **Deleted from the library**, and can remove it.
- Tick **Also remove it from the N agents that pin it** to take it from them as well. *Their next turns run without it, agents you don't edit included. Each agent's change is recorded in its history and the audit log.*
