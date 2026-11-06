# Contract: the skills library

Status: decided with [ADR-0064](../adr/0064-skills-library.md), built (`V040__skills.sql`, CONVENTIONS §15.19) · 2026-10-15 · Owner: J. Balcar

The decision is [ADR-0064](../adr/0064-skills-library.md); the reasoning is [the skills analysis](2026-10-15-agent-skills-analysis.md). This document is the exact contract the server, the runner host and the web app build: the tables, the rules, the API, the errors, the audit actions, the runner protocol and the tests.

## 1. Scope

| Part | Builds | Module or area |
|---|---|---|
| **A** | The library: skills, their versions and shares, upload and its checks, delete, a member who leaves, a deleted group | Server, new module `skill` |
| **B** | An agent's skills: pins, updates shown to editors, a person's or a trigger's `skillsOff`, the content through the agent | Server, module `agent` |
| **C** | Runs: the turn's skills, `run.assign.skills`, the download path, the host feature and its queue reason | Server, modules `conversation` and `run` |
| **D** | The runner host: the feature, the download, the cache, the read-only mounts per runtime | `runner/` |
| **E** | The web app: the library's pages, the agent's Skills sheet, the switch in settings | `web/` |

Not in scope: plugins and marketplaces; skills fetched from a URL or a git repository; skills shipped with Tinfirm; a repository's own skills (the analysis, D7 and D8).

## 2. The format

A skill is a folder whose root holds `SKILL.md`: front matter between a first line `---` and the next line `---`, in YAML, then Markdown instructions.

| Key | Rule |
|---|---|
| `name` | Required. `^[a-z0-9]+(-[a-z0-9]+)*$`, 1 to 64 characters. It is the folder's name in the sandbox |
| `description` | Required. 1 to 1,024 characters after trimming |
| `license` | Optional string, at most 500 characters |
| `compatibility` | Optional string, at most 500 characters |
| `metadata` | Optional map of string keys to string values, at most 50 entries, each value at most 500 characters |
| `allowed-tools` | Optional string or list of strings, kept as written; it grants nothing (the analysis, §6.4) |

Any other key, a front matter that is not a YAML map, or a missing front matter is `400 SKILL_INVALID`. The instructions after the front matter may be empty.

## 3. Tables

All tables carry `company_id`, have row-level security on it like every company table (CONVENTIONS §7), and composite foreign keys that include `company_id`.

### 3.1 `skill`

| Column | Meaning |
|---|---|
| `id` | uuid, primary key |
| `name` | §2's `name`; unique among the company's live skills (`skill_name_uq`, partial on `deleted_at IS NULL`) |
| `owner_principal_id` | The member's principal that owns it; `NULL` once the owner left (the admins edit it). A foreign key to a member's principal, as `agent.owner_principal_id` |
| `latest_version` | The highest version; at least 1 |
| `created_at`, `created_by` | |
| `updated_at` | The last new version or share change |
| `deleted_at`, `deleted_by` | The soft delete (§4.5) |

### 3.2 `skill_version`

Append-only: `tinfirm_app` gets `SELECT` and `INSERT` only.

| Column | Meaning |
|---|---|
| `skill_id`, `version` | Primary key; `version` 1, 2, … |
| `description` | The front matter's `description` of this version |
| `sha256` | The SHA-256 of `content`, 64 lower-case hex digits |
| `size_bytes` | The length of `content`; `size_bytes = octet_length(content)` |
| `file_count` | The files in it, `SKILL.md` included |
| `files` | `jsonb`: `[{path, sizeBytes}]` sorted by path, the uncompressed sizes |
| `note` | What changed, as its uploader wrote it; at most 500 characters; nullable |
| `content` | `bytea`, `STORAGE EXTERNAL`: the canonical zip (§4.2) |
| `created_at`, `created_by` | |

### 3.3 `skill_share` and `skill_member_share`

As `agent_share` and `agent_member_share`: (`skill_id`, `group_id`) and (`skill_id`, `principal_id`), with `created_at` and `created_by`. A group's deletion cascades; a member's principal must be a member's.

### 3.4 `agent_skill`

| Column | Meaning |
|---|---|
| `agent_id`, `skill_id` | Primary key |
| `version` | The pinned version; a foreign key (`skill_id`, `version`) to `skill_version` |
| `added_at`, `added_by` | When the skill joined the agent |
| `changed_at`, `changed_by` | The last move of its pin |

### 3.5 `agent_settings_skill`

A person's or a trigger's skill switched off: (`settings_id`, `skill_id`) primary key, with `agent_id`; a foreign key (`agent_id`, `skill_id`) to `agent_skill` `ON DELETE CASCADE`, so a skill that leaves the agent leaves every settings row, and the database refuses a switch of a skill the agent does not have (the narrowing rule, I4 of the package 3 contract).

### 3.6 `run_skill`

The skills a run was assigned: (`run_id`, `skill_id`) primary key, `version`. Written in the transaction that assigns the run; read by the download path (§7.2). It goes with its run.

## 4. The library's rules (part A)

### 4.1 Who sees, uses and edits a skill

| Caller | Sees | Adds it to an agent | Uploads a version, shares, deletes |
|---|---|---|---|
| Its owner | yes | yes | yes |
| A member of a group it is shared with, or a member it is shared with | yes | yes | no: `403 SKILL_NOT_EDITABLE` |
| OWNER, ADMIN, an instance admin acting in the company (`MANAGE_AGENTS`) | yes | yes | yes |
| Anyone else | no: `404` | no | no |

Every member creates skills (`VIEW_AGENTS`) and owns those they create; a caller without a principal in the company gets `404` for a create. A deleted skill is seen only through the agents that pin it (§5.4) and by admins in `GET /skills?deleted=true`; every other path answers `404` for it.

### 4.2 An upload and its checks

`POST /skills` and `POST /skills/{skillId}/versions` take `multipart/form-data` with exactly one of `file` (a zip) or `skillMd` (the text of a `SKILL.md`, at most 100,000 characters), and an optional `note`. Neither or both is `400 VALIDATION_FAILED`.

1. **Read the zip.** Entries are read in order, never trusting the sizes the zip claims: reading stops past the limits. A directory entry is skipped. Every file path is checked: relative, `/`-separated, each segment 1 to 255 bytes of UTF-8 without control characters, `\`, or `:`, never `.` or `..`, at most 8 segments and 1,024 bytes in all; no two files differ in case only; no file is also a directory's prefix. When every file lies under one top-level folder, that folder is stripped. Limits: at most 200 files, 5 MiB uncompressed in all, 5 MiB compressed upload. A link stored in the zip is read as the file it is in the zip (its target's text) and stored as a regular file. A zip that is encrypted, damaged or not a zip is `400 SKILL_INVALID`; over a limit, `400 SKILL_TOO_LARGE` with `details.limit`.
2. **Check `SKILL.md`** at the root (§2), else `400 SKILL_INVALID` with `details.rule`. A `skillMd` upload is one file, `SKILL.md`.
3. **The name.** A create takes the front matter's `name`, `409 DUPLICATE_SKILL_NAME` when a live skill has it. A new version must keep the skill's name, else `400 SKILL_NAME_CHANGED`.
4. **Normalise.** The files are written to a new zip in the order of their paths, stored with deflate, with the fixed time 1980-01-01T00:00, no extra fields, no comments and permissions `0644`. Its SHA-256 is the version's `sha256`; the same files give the same hash. It may have at most 5 MiB, `run.assign.skills[].sizeBytes`'s maximum, which deflate can pass by a little for files that do not compress: over it, `400 SKILL_TOO_LARGE` with `details.limit` `STORED`.
5. **Scan** the canonical zip with the vault's scan (`VaultService.scan`), which reads inside zips: a vault secret's value or a known credential format, or content it cannot scan completely, is `422 SKILL_CONTAINS_CREDENTIAL` with `credentialMatches` (the names of the secrets and formats, never a value) and stores nothing. No match can be acknowledged: a skill can always be written without the credential.
6. **Store.** A new version whose `sha256` equals the latest version's stores nothing and answers the skill as it is (`200`, not audited). Otherwise the version is `latest_version + 1`; at most 100 versions per skill (`409 SKILL_VERSION_LIMIT`).

The check runs outside any transaction (it is CPU work on the request's bytes); the store is one transaction that locks the skill's row (`FOR NO KEY UPDATE`) and checks the version limit, the name and that the caller still edits it.

### 4.3 Shares

`PUT /skills/{skillId}/shares` takes `groupIds` (at most 50, each the company's) and `principalIds` (at most 100, each a current member's principal, `SUSPENDED` included; absent or null keeps them), as an agent's shares do (CONVENTIONS §15.5), else `400 VALIDATION_FAILED`. Not a version. Taking a share from someone takes no skill from an agent that pins it.

### 4.4 A member who leaves, a deleted group

As agents (CONVENTIONS §15.5): in the leaving transaction (`PrincipalDependents.memberLeaving`), every live skill the member owns passes to the admins (`owner_principal_id` `NULL`, audited `skill.passed_to_admins` with `formerOwnerPrincipalId` and `cause` `principal.left`), and every share with the member on their own goes (audited `skill.shares_changed`, `cause` `principal.left`). A group's deletion (`GroupDependents.groupDeleting`) takes it from every skill, audited `skill.shares_changed` with `cause` `group.deleted`.

### 4.5 Delete

`DELETE /skills/{skillId}` (who edits it) sets `deleted_at`, deletes its shares, frees its name, and keeps its versions. By default the agents that pin it keep it and their runs still get it (§5); it can no longer be added to an agent or moved to another version. With `?removeFromAgents=true` it also leaves every live agent that pins it, those the caller neither edits nor sees included: a narrowing, so it reaches everyone's next turn without anyone's acceptance. Each agent is locked in the order of its id after the skill's row (`SkillUsage.removeFromAgents`, implemented by the agent module), and its change is a version that widens nothing, audited as `agent.skills_changed` with `cause` `skill.deleted`; every settings switch of the skill goes with it (§3.5). Audited `skill.deleted` with `agentCount` (the live agents that pinned it) and `removedFromAgents`. `204`.

## 5. An agent's skills (part B)

### 5.1 `PUT /agents/{agentId}/skills`

Who edits the agent. `{skills: [{skillId, version?}]}`, at most 20, each skill once (`400 VALIDATION_FAILED`), replacing the agent's skills as a whole:

| Entry | Rule | Else |
|---|---|---|
| A skill the agent has, without `version` | Keeps its pin | |
| A skill the agent has, with `version` | Moves the pin to that version of it (up or down); the skill must be live to move | `400 SKILL_DELETED`; `400 VALIDATION_FAILED` for a version it does not have |
| A skill the agent does not have | Joins at `version`, or at its latest without one. The caller must see the skill and it must be live | `400 VALIDATION_FAILED` for a skill the caller does not see (never a `404` of the agent path); `400 SKILL_DELETED` |
| Two skills of the same name (a deleted one and a live one) | | `400 SKILL_NAME_CONFLICT` |

A request that changes nothing changes nothing and is not audited. Otherwise it makes a version of the agent that widens nothing (as a change of instructions does: `AgentVersions.next`, `AgentChanged`), its editor's own settings follow it, and it is audited as `agent.skills_changed` with `added` and `removed` (`[{skillId, name, version}]`) and `moved` (`[{skillId, name, from, to}]`). It applies to everyone's next turn.

### 5.2 What the agent shows

`Agent.skills`, for whoever sees the agent, sorted by name: `{skillId, name, description, version, latestVersion, deleted}` (whether the caller may move them is the agent's `editableByMe`); `description` is the pinned version's. `latestVersion` is the skill's latest, so `latestVersion > version` is an update the app offers the agent's editors. `AgentSummary.skillUpdates` is the number of such skills, for editors only (0 otherwise).

### 5.3 Settings

`AgentSettings.skillsOff` and `AgentSettingsRequest.skillsOff`: skills of the agent switched off, at most 20; a skill the agent does not have is `400 SKILL_NOT_ON_AGENT`. A trigger's settings take the same field. The database refuses a switch whose skill left the agent meanwhile (§3.5), answered `400 SETTINGS_WIDEN`. `EffectiveSettings.skills()` is the agent's skills less those switched off.

### 5.4 The content through the agent

`GET /agents/{agentId}/skills/{skillId}/content`, for whoever sees the agent: the pinned version's canonical zip (`application/zip`, `Content-Disposition: attachment; filename="{name}-v{version}.zip"`). It is how a person who uses an agent reads what its skills tell it, as they read its instructions, without seeing the skill in the library.

## 6. The library's API (part A)

Under `/api/companies/{companyId}`, tag `Skills`.

| Endpoint | Who | Does |
|---|---|---|
| `GET /skills` | Every member | The live skills the caller sees, by name (keyset on name and id): `SkillSummary` `{id, name, description, latestVersion, ownerPrincipalId, ownerName, editableByMe, shareGroupIds, sharePrincipalIds, agentCount, updatedAt, deleted}`; `agentCount`, the live agents that pin it, for whoever edits it (null otherwise). `?q=` filters on name and description (case ignored); `?deleted=true`, admins only, lists deleted skills instead |
| `POST /skills` | Every member | Create from an upload (§4.2): version 1, owned by the caller. `201` `Skill` |
| `GET /skills/{skillId}` | Who sees it | `Skill`: the summary's fields, `memberShares` (`{principalId, userId, name}`), and `versions` newest first: `{version, description, sha256, sizeBytes, fileCount, note, createdAt, createdByName}` |
| `GET /skills/{skillId}/versions/{version}` | Who sees it | `SkillVersion`: the version's fields, `files` (`[{path, sizeBytes}]`) and `skillMd`, the text of its `SKILL.md` |
| `GET /skills/{skillId}/versions/{version}/content` | Who sees it | The canonical zip, as §5.4 |
| `POST /skills/{skillId}/versions` | Who edits it | A new version from an upload (§4.2). `201` `Skill`, or `200` when it equals the latest |
| `PUT /skills/{skillId}/shares` | Who edits it | §4.3; `Skill` |
| `DELETE /skills/{skillId}` | Who edits it | §4.5, with `?removeFromAgents=true` to take it from every live agent that pins it; `204` |

## 7. Runs (part C)

### 7.1 The turn's skills

`TurnSource.Content` carries `skills`: the turn's `EffectiveSettings.skills()` at its plan, each `{skillId, version, name, sha256, sizeBytes}`. The run module writes them to `run_skill` in the transaction that assigns the run and sends them as `run.assign.skills` (§8). The prompt has no skills section.

A run with skills goes only to a connected host whose `hello.features` lists `run.skills`. While the hosts that could take it otherwise exist but none lists it, the run waits with the queue reason `NO_HOST_FOR_SKILLS` (`details.skills`, the count), and the readiness of the agent (`GET …/readiness`) says so: `hosts.runnable` false and a sentence in `hosts.reason`, when hosts that may run it are connected and none lists the feature.

### 7.2 The download path

`GET /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}?epoch=`, with the host's bearer token, as the attachments' download (`RunnerFilesController`): for a run assigned to this host with this epoch and active, whose `run_skill` names this skill at this version, the canonical zip (`application/zip`, `Content-Length`, `X-Content-SHA256`). Anything else is `404`. The host checks the size and the hash.

## 8. The runner protocol (part D)

- **Feature** `run.skills` in `hello.features`.
- **`run.assign.skills`**: optional, at most 20; absent or empty for none. Each item `{skillId: uuid, version: integer ≥ 1, name: §2's name pattern, sha256: 64 lower-case hex, sizeBytes: integer 1 to 5,242,880}`; names unique within the run. The server sends it only to a host that lists the feature. The host provides the skills after it accepted the run, where it downloads the attachments: one it cannot provide (download, hash, unpack, mount point) fails the run as an infrastructure failure whose message names the skill.
- **The cache.** The host keeps each skill under `<data>/skills/<sha256>/<name>/`, unpacked once into a temporary directory and renamed into place. Unpacking refuses an entry that is a link or a device, an absolute path, `..`, a path over §4.2's limits or more than 5 MiB in all. Files are written `0644`, directories `0755`, owned by the host's user. Maintenance removes cache entries no run used for `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` (default 14).
- **The mounts.** Each skill is bind-mounted read-only into the sandbox at the runtime's user-level skill directory:

  | Runtime | Mount point |
  |---|---|
  | `CLAUDE_CODE` | `/home/agent/.claude/skills/<name>` |
  | `CODEX`, `OPENCODE`, `PI` | `/home/agent/.agents/skills/<name>` |

  The session home is the agent's, so before it starts the sandbox the host creates the mount points in it through plain directories only (each component a directory, never a link, created if missing, `0755`); a component that is a link or a file fails the run the same way. It first removes, in those two directories, any empty directory it created for an earlier run (listed in `<data>/homes/<agentId>/<taskId>.skills`, outside the mount). The local executor, which has no mounts, does not list the feature.
- **OpenCode** reads `~/.claude/skills` as well, so the host sets `OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1` for it. OpenCode 2 ([ADR-0061](../adr/0061-opencode-2.md)) has no such switch and ignores it; it still reads `~/.agents/skills` (checked in the `@opencode/cli` 2.0.29 binary: its compatibility sources scan `skills/` under `~/.claude` and `~/.agents` for `**/SKILL.md`), and the host mounts nothing in `~/.claude/skills` for it.

## 9. Errors

| Code | Status | When |
|---|---|---|
| `SKILL_INVALID` | 400 | §4.2 steps 1 and 2, with `details.rule` (`NOT_A_ZIP`, `PATH`, `NO_SKILL_MD`, `FRONT_MATTER`, `NAME`, `DESCRIPTION`, `KEY`) and `details.path` where one applies |
| `SKILL_TOO_LARGE` | 400 | §4.2 steps 1 and 4, with `details.limit` (`FILES`, `BYTES`, `UPLOAD`, `STORED`) |
| `SKILL_NAME_CHANGED` | 400 | A new version under another name |
| `DUPLICATE_SKILL_NAME` | 409 | A create, or a race of two |
| `SKILL_CONTAINS_CREDENTIAL` | 422 | §4.2 step 5, with `credentialMatches` |
| `SKILL_NOT_EDITABLE` | 403 | §4.1 |
| `SKILL_VERSION_LIMIT` | 409 | A 101st version |
| `SKILL_DELETED` | 400 | Adding or moving a deleted skill |
| `SKILL_NAME_CONFLICT` | 400 | Two skills of one name on an agent |
| `SKILL_NOT_ON_AGENT` | 400 | A settings switch of a skill the agent does not have |

## 10. Audit actions

Never a skill's content; names, versions, hashes and sizes only. Target `skill` unless stated.

| Action | Details |
|---|---|
| `skill.created` | `name`, `version` 1, `sha256`, `sizeBytes`, `fileCount` |
| `skill.version_added` | `name`, `version`, `previousVersion`, `sha256`, `sizeBytes`, `fileCount` |
| `skill.shares_changed` | `groupsAdded`, `groupsRemoved`, `principalsAdded`, `principalsRemoved`, `cause` (absent, `principal.left`, `group.deleted`) |
| `skill.deleted` | `name`, `agentCount`, `removedFromAgents` |
| `skill.passed_to_admins` | `name`, `formerOwnerPrincipalId`, `cause` |
| `agent.skills_changed` (target `agent`) | `added`, `removed`, `moved` (§5.1), `version`; `cause` `skill.deleted` when a delete took the skill from it (§4.5) |

A rejected upload stores and audits nothing, as a person's rejected attachment does: the problem tells the uploader what matched.

## 11. Events

`SkillEvents` (ids only, after the audit, in the changing transaction): `SkillCreated`, `SkillVersionAdded` (`version`), `SkillShared`, `SkillDeleted`, `SkillPassedToAdmins`. A change of an agent's skills is `AgentChanged` with `widens` false. No module listens to the skill events yet; they are for the app's live views and later work.

## 12. Modules

`skill` stands on `company` (groups), `principal`, `vault` (the scan), `audit` and `shared`; it implements `principal.PrincipalDependents` and `company.GroupDependents`. Its API: `Skills` (`find`, `version`, `content`, `sees`, `visibleIds`), `SkillInfo`, `SkillVersionInfo`. `agent` stands on `skill` (an agent's pins, the editor's sight of a skill). `run` stands on `skill` (the download's content). `conversation` reads the turn's skills through `EffectiveSettings.skills()`.

## 13. Tests

| Test | Covers |
|---|---|
| `SkillBundleTest` | §4.2 steps 1, 2 and 4: paths, links, limits read and not claimed, the top folder stripped, the front matter's keys, the canonical zip's bytes and hash stable across uploads of the same files in another order |
| `SkillIntegrationTest` | Who sees, uses and edits a skill on every path; create, versions, the same content twice, the version limit, shares with groups and members, delete, a member who leaves, a deleted group; audit and events |
| `SkillCredentialTest` | A vault secret and a known format in a file inside the zip, `422` and nothing stored, the rejection audited |
| `AgentSkillsTest` | §5: add, keep, move, remove; adding an unseen or deleted skill; a deleted skill kept; name conflicts; the agent's version and its widening (none); `skillUpdates`; the content through the agent for a user who does not see the skill; settings `skillsOff` and the database's refusal |
| `RunSkillsTest` | §7: `run.assign.skills` and `run_skill`; a host without the feature and `NO_HOST_FOR_SKILLS`; the download for the run's own version only, `404` for another run's, epoch or host |
| `TenantIsolationTest`, `CrossTenantBodyTest` | Every skill path of company B as a member of A; B's skill in A's `PUT /agents/{a}/skills`; B's run's skill through A's host |
| Runner: `skills` unit tests, `run` tests | The download's checks, unpack refusals, the cache, mount points through links refused, the mounts per runtime, `OPENCODE_DISABLE_CLAUDE_CODE_SKILLS` |
| Web: the library's pages and the agent's sheet | Spec tests as the agent pages have |

## 14. Open points

1. The mount points are verified against the runtimes' documentation and source at the pinned versions (the analysis, §3), not yet on the real images: a host test with the images, as the pi adapter's, should list each runtime's skills.
2. Updates of a skill are shown to an agent's editors on its page; an inbox item for them is later work.
3. Copying a skill to another owner, and an admin naming a new owner (as `PUT /agents/{agentId}/owner`), are later work.
