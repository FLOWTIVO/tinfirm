# Analysis: skills for agents

Status: analysis; the owner decided on 2026-10-15 to build the library at once (D2), which [ADR-0064](../adr/0064-skills-library.md) records · 2026-10-15 · Owner: J. Balcar

The question: **would it make sense for Tinfirm to support skills**, the Agent Skills format that Claude Code, Codex, OpenCode and pi read? This document says what a skill is, what Tinfirm has today, what the four pinned runtimes do, the case for and against, a design that fits [PLATFORM.md](../PLATFORM.md), what it leaves out, and the points the owner decides.

**Short answer: yes.** It makes sense, as a small package after package 3. Skills should be a value of the agent beside its instructions, not a new library, and they should hold whole folders (scripts and templates included). Plugins, marketplaces and anything else that brings its own tools into the sandbox stay out. Skills give the agents of every department, not only developers, the procedures and files their work needs. Agents also get cheaper per person, and the governance model does not change, because a skill widens nothing (§6). It is not on the redesign's critical path, so it should wait until the agent and run modules of package 3 have landed.

## 1. What a skill is

A skill is a folder `<name>/` with a `SKILL.md`: YAML front matter with `name` (lower-case letters, digits and hyphens, the folder's name) and `description` (at most 1,024 characters), then Markdown instructions. The folder may also hold scripts, references and templates. The runtime lists only each skill's name and description to the model. It reads the instructions when a skill fits the work, and the other files only when the instructions point to them. The format is an open specification ([agentskills.io](https://agentskills.io)). `.agents/skills` is becoming the common directory: Codex, OpenCode and pi read it, Claude Code does not.

## 2. What exists

| Where know-how lives today | Limits | Where |
|---|---|---|
| The agent's **instructions**, the first part of every full prompt | At most 100,000 characters. Sent in full in every new session. Follow the current version and are not kept in `agent_version` | `V003__agents.sql`, `PromptRenderer` |
| A person's **added instructions** | At most 20,000 characters | `agent_settings.instructions` |
| A trigger's **instruction** | The job itself. A trigger's settings add no instructions (`V033`) | `V033__trigger_settings_no_instructions.sql` |
| A conversation's **files** | Per conversation, not per agent; read-only at `/attachments` | `AttachmentService`, `run.assign.attachments` |
| A repository's own `CLAUDE.md`, `AGENTS.md` and skills | Only for that repository, and found inconsistently (§3) | `/workspace/<dir>` |

Tinfirm has no way to give an agent know-how that loads only when needed, or to ship a script or a template with an agent.

Two constraints of the runner host shape the design:

- The runner host must not write into the session home. The agent controls that home, and a previous turn may have left symlinks in it.
- A mount point that the container engine creates inside `/home/agent` is owned by root, and the runtime then cannot save its session.

The v1 design had planned skills in the Agent Skills format, in a versioned, forkable library. The current design does not mention skills.

## 3. What the runtimes do (checked 2026-10-15 against the pinned versions)

All four read `SKILL.md` natively. They differ in where they look:

| Runtime (pinned) | User-level | Project-level | An extra directory | Listing budget |
|---|---|---|---|---|
| Claude Code 2.1.287 (claude-agent-acp 0.87.0) | `~/.claude/skills` | `.claude/skills` from the cwd up to the repository root. Nested `.claude/skills` only once a file there is read or edited. Never `.agents/skills` | ACP `additionalDirectories`, which claude-agent-acp forwards to the SDK: `<dir>/.claude/skills` loads at once | 1% of the context window; least-used skills lose their descriptions first |
| Codex 0.156.1 (codex-acp 1.13.1) | `~/.agents/skills` | `.agents/skills` from the cwd up to the `.git` root. No nested skills, no `.claude/skills` | codex-acp maps each `additionalDirectories` entry to `<dir>/.agents/skills` | 2% of the context window |
| OpenCode 1.18.34 | `~/.config/opencode/skills`, `~/.claude/skills`, `~/.agents/skills` | `.opencode/`, `.claude/` and `.agents/skills` from the cwd up to the worktree | `skills.paths` in the config, which works in `OPENCODE_CONFIG_CONTENT` | None |
| pi 1.0.4 | `~/.pi/agent/skills`, `~/.agents/skills` | `.pi/skills` in the cwd; `.agents/skills` up to the git root, but skipped in RPC mode without project trust | `--skill <path>`, which our own `tinfirm-pi-acp` can pass | None |

None of them needs the network to load a skill. Scripts run through the runtime's shell tool, inside the sandbox. Sources: the runtimes' docs and their source code at the pinned tags (§10).

**Found along the way.** The cwd of a run is `/workspace`, and the repositories are cloned into `/workspace/<dir>`. `/workspace` is not inside a git repository, and Codex, OpenCode and pi only search from the cwd upwards. So today none of them finds a repository's own skills, and Claude Code finds them only after it touches a file in that repository. By the same search rule, a repository's `AGENTS.md` is probably not read by Codex, OpenCode or pi either. That is inferred, not verified.

## 4. The case for

1. **Specialists outside development need procedures and files.** A business analyst's ticket template, a tester's scenario format, a finance analyst's monthly evaluation with a script that builds the spreadsheet in `/output`. Instructions cannot carry a file, and attachments belong to one conversation. A skill is exactly a procedure plus its files, which is what [PRODUCT.md](../../PRODUCT.md) counts as success: "the same platform serves developers, analysts, testers and finance".
2. **Each person pays for the agent's size.** Instructions of up to 100,000 characters (about 25,000 tokens) go in every new session's first message. Every resumed turn reads them again with the rest of the history: a cache read at 0.1×, or after a cold start a write at 1.25× or 2× ([operations/prompt-cache.md](../operations/prompt-cache.md)). A skill costs only its description, which sits in the runtime's cached system prompt, until it is used. An agent with ten procedures pays for the one it uses, and every person who uses it pays less from their own budget (PLATFORM §1).
3. **One format on every runtime.** The same folder works on all four runtimes, so the rule "the runtimes its instructions are tested on" (PLATFORM §6) covers skills as it covers instructions. Teams can bring the skills they already use in Claude Code or Codex unchanged.
4. **It widens nothing.** A skill is words and files in the sandbox. It adds no system, tool, repository or network host. Its scripts run inside the sandbox, within the agent's hosts and through the same three tool layers (PLATFORM §10). PLATFORM §6 already says "instructions and descriptions are not widening", so a skill needs no acceptance and no approval.
5. **It is the start of "export and import as code"** (PLATFORM §14): an agent's instructions and skills are the text half of an agent as files.

## 5. The case against

1. **Fewer nouns** (principle 7). A skill library with sharing, versions and groups would be a mechanism beside the model. That is the main reason to start with skills as a value of the agent (§6.1).
2. **Four runtimes to keep right.** Each looks in its own place (§3), and an image upgrade can move those places. A host test per runtime image has to prove the skills are seen.
3. **Listing budgets.** Claude Code gives the skill listing 1% of the context window and drops descriptions silently when it overflows. The number of skills per agent needs a cap.
4. **Little gain for small agents.** An agent with two pages of instructions gains nothing.
5. **Timing.** Package 3 is in progress and packages 4 to 6 are queued. Skills touch the agent and run modules that package 3 is still building.

## 6. A design that fits

### 6.1 A value of the agent

- An agent holds **skills** beside its instructions, edited by whoever edits the agent. Like the instructions, they follow the current version (they are not kept in `agent_version`), and a change applies to everyone at their next turn. They are not widening (PLATFORM §6), so there is no acceptance step.
- **Settings narrow:** a person's settings, and a trigger's, may switch a skill off, as they switch a tool off. Adding personal skills, like personal MCP servers, is later work.
- A **company library** shared across agents is later work too, only if copying a skill between agents turns out to hurt.

### 6.2 Content and checks

- A skill is uploaded as a folder (a zip) or written as a single `SKILL.md` in the editor. The server checks it:
  - `SKILL.md` at the root;
  - `name` equal to the folder's name, matching `^[a-z0-9]+(-[a-z0-9]+)*$` and at most 64 characters;
  - `description` 1 to 1,024 characters;
  - only the specification's front-matter keys;
  - no symlinks, no absolute paths and no `..`;
  - limits on the number of files and their size. Starting values: at most 20 skills per agent (Claude Code's budget, §5, point 3), 5 MiB per skill and 20 MiB per agent.
- **No credentials:** every text file is scanned as attachments are (`CredentialScan`), and a match fails the save. There is no way to acknowledge a match: a skill can always be written without the credential (principle 5).
- It is stored in PostgreSQL as attachments are (`bytea`, external storage), keyed by its SHA-256.

### 6.3 To the sandbox

- **Protocol:** `run.assign.skills[]` gives each skill as `{name, sha256, sizeBytes}`, behind a host feature `run.skills` (`Hello.features`, as `run.steer` is). The host downloads each skill as it does attachments, with `GET /api/runner/v1/runs/{runId}/skills/{name}?epoch=…`. It checks the size and hash, unpacks into a cache keyed by hash (refusing links and paths that leave the folder), and mounts the result **read-only**.
- **Outside the session home.** One host directory is mounted read-only at `/tinfirm/skills/.claude/skills` and at `/tinfirm/skills/.agents/skills`. Two bind mounts of the same directory need no symlinks. Each runtime is then pointed at it:

  | Runtime | How it finds the skills |
  |---|---|
  | Claude Code | `additionalDirectories: ["/tinfirm/skills"]` in `session/new` and `session/load` |
  | Codex | The same `additionalDirectories` |
  | OpenCode | `skills.paths: ["/tinfirm/skills/.agents/skills"]` in `OPENCODE_CONFIG_CONTENT` |
  | pi | `--skill /tinfirm/skills/.agents/skills`, from a variable the host sets for `tinfirm-pi-acp` |

  The `additionalDirectories` route comes from reading the adapters' source and still has to be proven on the real images. If it fails, Claude Code falls back to a mount at `~/.claude/skills`, with the host creating the mount point itself through plain directories.
- **A host without the feature** cannot run a turn whose agent has skills. The turn waits with a queue reason, as it would for a missing runtime, rather than running without them.
- **Prompt:** no new section. The runtimes list the skills themselves. If testing shows a runtime ignores them, one line in `# How to work` names the folder.
- **Audit:** `agent.skills.changed` records the skill names and their hashes, never their contents (as `agent.created` leaves out the instructions). The run's log names the skills it got.

### 6.4 What it leaves out

- **Plugins and marketplaces**:
  - a plugin brings MCP servers and hooks that run in the sandbox, outside the server-executed tools, their modes and `tool.called` (PLATFORM §10);
  - it needs the network at every start;
  - it cannot be pinned;
  - it is for Claude Code only.
- **`allowed-tools` in front matter:** the runtime may read it, but it grants nothing that matters. Inside the sandbox the host already answers permission prompts, and every tool of a system is governed on the server.
- **Fetching skills from URLs at run time** (OpenCode's `skills.urls`, `npx skills`): what runs must be what the agent's editor saved.

## 7. Alternatives

| Alternative | Why not, or not alone |
|---|---|
| Keep everything in the instructions | Fine for short agents. It cannot carry files, and every person pays for the whole text on every cold start (§4, point 2) |
| A "skills repository" the agent lists `READ` | Works today with no code. But the clone uses the audience's account (PLATFORM §5.3), so anyone without access to that repository breaks the turn. It also takes a workspace, and only Claude Code would find the skills on its own (§3). A stopgap at best |
| Serve skills through the run's MCP endpoint as prompts or resources | The runtimes do not treat MCP prompts as skills, and scripts cannot be delivered that way |
| A company skill library first | A new noun with sharing and versions before anyone knows that copying hurts (§5, point 1) |

## 8. Size and order

There are three parts, each with its tests:

- **Server:** the table, the agent's skills API, validation of the zip and the credential scan, the audit, the run mapping, and switching a skill off in settings.
- **Runner host:** the feature, the safe download, cache and unpack, the mounts, the wiring for each runtime, and a test per image that the runtime lists the skill.
- **Web app:** a "Skills" sheet on the agent page (upload, write, list, remove) and the switch in a person's settings.

A good place in the build order is **after package 3, beside package 4**. It needs package 3's agent and run modules and nothing from packages 4 to 6.

## 9. For the owner to decide

| # | Decision | Recommendation |
|---|---|---|
| D1 | Support skills at all, and when | Yes, after package 3 |
| D2 | The unit: on the agent, or a company library | On the agent; a library later, if copying hurts |
| D3 | The content: folders with scripts and files, or `SKILL.md` text only | Folders; the files are much of the value for analysts and finance |
| D4 | Plugins and marketplaces | Out (§6.4) |
| D5 | Settings: switch a skill off; add one's own | Switching off now; one's own skills later |
| D6 | A host without `run.skills` | The turn waits for a host that has it |
| D7 | A repository's own skills (and `AGENTS.md`), found inconsistently today (§3) | A separate change: point each runtime at the repositories, opt-in per repository, since anyone who can push there writes the agent's instructions |
| D8 | Skills shipped with Tinfirm (document skills for `/output`) | Later. Check each one's licence first: Anthropic's document skills are not published under an open-source licence |

## 10. Sources

- Claude Code: [Skills](https://code.claude.com/docs/en/skills), [Agent SDK skills](https://code.claude.com/docs/en/agent-sdk/skills), [CHANGELOG](https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md); claude-agent-acp [`src/acp-agent.ts` at v0.87.0](https://github.com/agentclientprotocol/claude-agent-acp/blob/v0.87.0/src/acp-agent.ts)
- Codex: [`codex-rs/ext/skills/src/host_roots.rs`](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/ext/skills/src/host_roots.rs), [`render.rs`](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/ext/skills/src/render.rs), [`config/src/skills_config.rs`](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/config/src/skills_config.rs) at rust-v0.156.1; [codex-acp](https://github.com/agentclientprotocol/codex-acp)
- OpenCode: [`docs/skills.mdx`](https://github.com/anomalyco/opencode/blob/v1.18.34/packages/web/src/content/docs/skills.mdx) and [`src/skill/index.ts`](https://github.com/anomalyco/opencode/blob/v1.18.34/packages/opencode/src/skill/index.ts) at v1.18.34
- pi: [`docs/skills.md`](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md), [`docs/security.md`](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/security.md), [CHANGELOG](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/CHANGELOG.md)
- Tinfirm: PLATFORM §1, §2, §6, §10, §14; `V003__agents.sql`; `PromptRenderer`; `runner-v2.schema.json` (`RunAssign.attachments`, `Hello.features`); `runner/.../sandbox.rs` (`/workspace`, `/home/agent`); `runner/.../acp.rs` (`session/new` sends only the cwd and `mcpServers`)
