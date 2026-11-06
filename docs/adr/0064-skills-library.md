# ADR-0064: A company's library of skills, pinned by agents

- Status: Accepted
- Date: 2026-10-15
- Decided by: the owner, 2026-10-15
- Sources: [PLATFORM.md](../PLATFORM.md) §2 (principles 3, 5 and 7), §6 and §10; the Agent Skills format ([agentskills.io](https://agentskills.io)); the skill directories of the pinned runtimes ([the skills analysis](../reviews/2026-10-15-agent-skills-analysis.md), §3); [ADR-0014](0014-task-attachments-and-upload-scan.md) (the credential scan)
- Changes: a new server module `skill`; the agent's definition (its skills) and a person's settings (`skillsOff`); the runner protocol 2 (the optional feature `run.skills`, no new version); the runner host; the API; the web app

## Context

- An agent's know-how lives in its instructions: at most 100,000 characters, sent in full in every new session's first message, and read again by every resumed turn. Instructions carry no files, and the same procedure in several agents is copied by hand.
- Claude Code, Codex, OpenCode and pi all read **skills** in the Agent Skills format: a folder with a `SKILL.md` (front matter `name` and `description`, then instructions) and any scripts, references or templates. The runtime lists only each skill's name and description, and reads the rest when a skill fits the work.
- The owner wants a **library** that agents share, so that a procedure is written once and every agent that needs it uses it. A shared skill must not let its editor change agents they cannot edit without those agents' editors knowing.

## Decision

1. **A skill is the company's, in a library** (module `skill`). Every member creates skills and owns those they create; a skill is private to its owner until shared with groups or members, exactly as an agent is; OWNER and ADMIN see and edit every skill. Its name is the Agent Skills name, unique among the company's live skills.
2. **A skill's content is a folder, in immutable versions.** Each upload (a zip, or a `SKILL.md` written in the app) is checked (the format, paths, sizes), scanned for credentials as attachments are, normalised to a canonical zip and stored as a new version keyed by its SHA-256. A version is never changed or removed while the skill exists.
3. **An agent pins a version of each of its skills.** Its editors add a skill they see at its latest version, move a pin to any version of it, and remove it. A new version reaches no agent by itself: the agent's page tells its editors that an update is there. So whoever edits a shared skill changes no agent, and whoever edits an agent decides what its runs do.
4. **Skills are not widening** (PLATFORM §6). A skill adds no system, tool, repository or network host; its scripts run in the sandbox, within the agent's hosts and through the same tool layers. A change of an agent's skills makes a version that widens nothing, like a change of its instructions, and applies to everyone at their next turn.
5. **Settings narrow:** a person's settings, and a trigger's, may switch a skill of the agent off (`skillsOff`).
6. **Runs get their skills as files, read-only, outside Tinfirm's prompt.** `run.assign.skills` names each skill's version and hash; the runner host downloads, verifies, unpacks and caches it, and mounts it read-only where the runtime reads a user's skills: `~/.claude/skills/<name>` for Claude Code, `~/.agents/skills/<name>` for Codex, OpenCode and pi. The runtimes list the skills themselves; the prompt adds no section.
7. **An optional feature of protocol 2.** A host lists `run.skills` in `hello.features`; a turn with skills runs only on such a host, and waits (`NO_HOST_FOR_SKILLS`) while none is connected, rather than running without them.
8. **No plugins, no marketplaces, nothing fetched at run time.** What runs is what an editor saved and the server scanned.

## Consequences

- A procedure is written once and shared; an agent with ten procedures pays for the descriptions of ten and the instructions of the one it uses.
- An update of a shared skill is a choice per agent: safe, but agents may lag behind. The agent's page shows the editors which skills have updates.
- A deleted skill stays in the agents that pin it (its versions are kept); it can no longer be added or moved to another version. Its name is free again. Its deleter may instead take it from every agent that pins it (`removeFromAgents`): a narrowing, which reaches every agent's next turn, audited per agent.
- A member who leaves passes their skills to the admins, as their agents do.
- The skill's files enter every sandbox of every agent that pins it; the credential scan keeps vault secrets and known credential formats out, and the trust is the agent's instructions': whoever edits the agent chose them.
- The skill directories of the four runtimes are fixed in this decision and tested against the pinned images; a runtime upgrade that moves them is caught there.
- Protocol 2 grows by one optional field and one download path; an older host never gets a turn with skills.

## Alternatives considered

- **Skills on the agent alone, copied between agents.** Recommended first by [the skills analysis](../reviews/2026-10-15-agent-skills-analysis.md); the owner chose the library.
- **A shared skill that follows its latest version in every agent.** Rejected: an editor of the skill would change agents they cannot edit, at the next turn of everyone who uses them.
- **Skills from a git repository the agent lists.** Rejected: the clone uses the audience's account, so a person without access to that repository breaks the turn.
- **Mounting skills through ACP's `additionalDirectories`.** Not chosen: not verified against the pinned adapters, while the user-level directories are documented by all four runtimes.
- **Claude Code plugins and marketplaces.** Rejected: they bring MCP servers and hooks that run outside the server's tools and their audit, need the network at every start, and cannot be pinned.
