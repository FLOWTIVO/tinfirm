import type {
  Agent,
  AgentCreateRequest,
  AgentHostInput,
  AgentRepositoriesRequest,
  AgentRepositoryAccess,
  AgentRuntime,
  AgentSharesRequest,
  AgentSkillInput,
  AgentSkillsRequest,
  AgentSystemInput,
  AgentSystemsRequest,
  AgentToolMode,
  AgentHostsRequest,
  AgentUpdateRequest,
  SystemKind,
  SystemTool,
  ToolEffect,
} from '../../../api/generated/models';

/**
 * The agent editor's draft (plan §4.3) and what it computes before one Save: the request of a new
 * agent, the sections a change sends, and the widening a change makes for the people who use the
 * agent. The server decides every rule again; this only says it before the Save.
 */

/** A tool of a system on the draft, with what the server said of it. */
export interface DraftTool {
  readonly name: string;
  readonly title: string | null;
  readonly mode: AgentToolMode;
  /** The tool's effect now; null when its system no longer serves it. */
  readonly effect: ToolEffect | null;
  /** Whether its system still serves it. A row that is not keeps its mode and lists nothing. */
  readonly offered: boolean;
  /** The effect its mode was chosen for (I19); null on a row the server has not stored yet. */
  readonly chosenForEffect: 'READ' | 'WRITE' | null;
  /**
   * The effect the page showed when the owner picked the mode here (`UNKNOWN` as `WRITE`); null
   * until they pick one. Sent as `chosenForEffect`, it chooses the mode anew for that effect: so a
   * "Allowed" whose tool became a write is confirmed for writes by picking "Allowed" again (I19).
   */
  readonly picked: 'READ' | 'WRITE' | null;
}

export interface DraftSystem {
  readonly systemId: string;
  readonly name: string;
  readonly handle: string;
  readonly kind: SystemKind;
  readonly tools: readonly DraftTool[];
}

export interface DraftRepository {
  readonly repositoryId: string;
  readonly systemId: string;
  readonly name: string;
  readonly access: AgentRepositoryAccess;
}

export interface DraftHost {
  readonly host: string;
  readonly reason: string | null;
}

/**
 * A skill of the library on the draft, pinned at `version` (ADR-0064). A deleted one stays as
 * the agent keeps it, and cannot move.
 */
export interface DraftSkill {
  readonly skillId: string;
  readonly name: string;
  readonly description: string;
  readonly version: number;
  readonly latestVersion: number;
  readonly deleted: boolean;
}

export interface AgentDraft {
  readonly name: string;
  readonly handle: string;
  readonly description: string;
  readonly instructions: string;
  /** In order; the first is the default. */
  readonly runtimes: readonly AgentRuntime[];
  /** How long one turn may run, in minutes, as typed; empty for the deployment's default. */
  readonly turnTimeLimitMinutes: string;
  /** Whether its people and triggers may turn its memory on (ADR-0063). */
  readonly memoryAllowed: boolean;
  readonly systems: readonly DraftSystem[];
  readonly repositories: readonly DraftRepository[];
  readonly hosts: readonly DraftHost[];
  /** Its skills, each at the version it pins. */
  readonly skills: readonly DraftSkill[];
  readonly shareGroupIds: readonly string[];
  /** The members it is shared with on their own, by principal. */
  readonly sharePrincipalIds: readonly string[];
}

/**
 * The kinds an agent lists: trackers and code hosts (tools and repositories), MCP servers, and
 * Slack for its `history` (the package 5 contract, open point 13), as the server's
 * `AgentService.KINDS`; not Teams, which has no tools, nor the webhook.
 */
export const AGENT_SYSTEM_KINDS: ReadonlySet<SystemKind> = new Set<SystemKind>([
  'JIRA_DC',
  'JIRA_CLOUD',
  'GITHUB',
  'GITLAB',
  'GIT',
  'MCP',
  'SLACK',
]);

/** The kinds whose repositories an agent may work in. */
export const CODE_HOST_KINDS: ReadonlySet<SystemKind> = new Set<SystemKind>([
  'GITHUB',
  'GITLAB',
  'GIT',
]);

export const EMPTY_DRAFT: AgentDraft = {
  name: '',
  handle: '',
  description: '',
  instructions: '',
  runtimes: ['CLAUDE_CODE'],
  turnTimeLimitMinutes: '',
  memoryAllowed: true,
  systems: [],
  repositories: [],
  hosts: [],
  skills: [],
  shareGroupIds: [],
  sharePrincipalIds: [],
};

/** The draft of a saved agent, or of a new one. */
export function draftOf(agent: Agent | null): AgentDraft {
  if (!agent) return EMPTY_DRAFT;
  return {
    name: agent.name,
    handle: agent.handle,
    description: agent.description ?? '',
    instructions: agent.instructions,
    runtimes: [...agent.runtimes],
    turnTimeLimitMinutes:
      agent.turnTimeLimitMinutes != null ? String(agent.turnTimeLimitMinutes) : '',
    memoryAllowed: agent.memoryAllowed !== false,
    systems: agent.systems.map((s) => ({
      systemId: s.systemId,
      name: s.name,
      handle: s.handle,
      kind: s.kind,
      tools: s.tools.map((t) => ({
        name: t.name,
        title: t.title ?? null,
        mode: t.mode,
        effect: t.effect ?? null,
        offered: t.offered,
        chosenForEffect: t.chosenForEffect,
        picked: null,
      })),
    })),
    repositories: agent.repositories.map((r) => ({
      repositoryId: r.repositoryId,
      systemId: r.systemId,
      name: r.name,
      access: r.access,
    })),
    hosts: agent.hosts.map((h) => ({ host: h.host, reason: h.reason ?? null })),
    skills: agent.skills.map((k) => ({
      skillId: k.skillId,
      name: k.name,
      description: k.description,
      version: k.version,
      latestVersion: k.latestVersion,
      deleted: k.deleted,
    })),
    shareGroupIds: agent.shares.map((g) => g.id),
    sharePrincipalIds: agent.memberShares.map((m) => m.principalId),
  };
}

/**
 * The tools a system brings when it is added, as the server would fill them: every enabled tool,
 * reads "Allowed", writes and unconfirmed tools "Needs approval".
 */
export function toolsForNewSystem(tools: readonly SystemTool[]): DraftTool[] {
  return tools
    .filter((t) => t.enabled)
    .map((t) => ({
      name: t.name,
      title: t.title ?? null,
      mode: t.effect === 'READ' ? 'ALLOW' : 'ASK',
      effect: t.effect,
      offered: true,
      chosenForEffect: null,
      picked: null,
    }));
}

/** The effect a pick is made for: what the page shows, an unconfirmed effect as a write. */
export function effectOfPick(effect: ToolEffect | null): 'READ' | 'WRITE' {
  return effect === 'READ' ? 'READ' : 'WRITE';
}

/**
 * The mode a tool has in effect: an "Allowed" chosen while the tool read counts as "Needs
 * approval" once its effect is a write or unconfirmed (I19), until the owner picks it again for
 * what it is now.
 */
export function effectiveMode(
  tool: Pick<DraftTool, 'mode' | 'effect' | 'chosenForEffect'> & { picked?: DraftTool['picked'] },
): AgentToolMode {
  const chosenFor = tool.picked ?? tool.chosenForEffect;
  if (tool.mode === 'ALLOW' && chosenFor === 'READ' && tool.effect && tool.effect !== 'READ') {
    return 'ASK';
  }
  return tool.mode;
}

/**
 * An "Allowed" that counts as "Needs approval" because its tool became a write (I19), not picked
 * again yet.
 */
export function asksSinceItWrites(tool: DraftTool): boolean {
  return !tool.picked && effectiveMode(tool) !== tool.mode;
}

/** A host as the server stores it: lower case, without a trailing dot or `:443`. */
export function normalizeHost(raw: string): string {
  let host = raw.trim().toLowerCase();
  const colon = host.indexOf(':');
  let port = colon >= 0 ? host.slice(colon + 1) : null;
  let name = colon >= 0 ? host.slice(0, colon) : host;
  if (name.endsWith('.')) name = name.slice(0, -1);
  if (port === '443') port = null;
  host = port ? `${name}:${port}` : name;
  return host;
}

/**
 * Why a host would be refused, in the server's words, or null when it looks right: `HOST[:PORT]`
 * or `*.example.com[:PORT]`, without a scheme, path or IP address. The server checks the rest
 * (public suffixes, shared hosting).
 */
export function hostProblem(raw: string): string | null {
  const text = raw.trim().toLowerCase();
  if (!text) return 'Name the host: a DNS name such as registry.npmjs.org, or *.example.com.';
  if (/:\/\/|[/@?#\s]/.test(text)) {
    return 'The host is HOST or HOST:PORT only, without a scheme, a path or a user name.';
  }
  const [name, port, extra] = text.split(':');
  if (extra !== undefined) return 'IP addresses are not accepted; name the host by its DNS name.';
  if (port !== undefined && (!/^\d{1,5}$/.test(port) || +port < 1 || +port > 65535)) {
    return 'The port is a number from 1 to 65535.';
  }
  const labels = name.replace(/\.$/, '').split('.');
  if (labels.every((l) => /^\d+$/.test(l))) {
    return 'IP addresses are not accepted; name the host by its DNS name.';
  }
  const wildcard = labels[0] === '*';
  const rest = wildcard ? labels.slice(1) : labels;
  if (
    rest.length < (wildcard ? 2 : 1) ||
    rest.some((l) => !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(l))
  ) {
    return wildcard
      ? 'A wildcard stands only for the first label, with two labels after it: *.example.com.'
      : 'Name the host by its DNS name, such as registry.npmjs.org.';
  }
  return null;
}

/** Whether an allowed host covers a requested one: the same, or a wildcard whose suffix it has. */
export function hostCovers(allowed: string, requested: string): boolean {
  if (allowed === requested) return true;
  if (!allowed.startsWith('*.')) return false;
  const suffix = allowed.slice(1);
  const portOf = (h: string) => (h.includes(':') ? h.slice(h.indexOf(':') + 1) : null);
  const nameOf = (h: string) => (h.includes(':') ? h.slice(0, h.indexOf(':')) : h);
  return portOf(suffix) === portOf(requested) && nameOf(requested).endsWith(nameOf(suffix));
}

// --- Requests ----------------------------------------------------------------------------------

/**
 * The systems as `replaceAgentSystems` (or `createAgent`) takes them. A tool carries
 * `chosenForEffect` only for a mode the owner picked on the page that chooses anything anew: a new
 * mode, a new effect, or a tool the saved agent does not list. Without it the server keeps an
 * unchanged mode's row, so saving another section never widens a tool.
 */
function systemInputs(draft: AgentDraft, saved?: AgentDraft): AgentSystemInput[] {
  return draft.systems.map((s) => {
    const before = saved?.systems.find((b) => b.systemId === s.systemId);
    return {
      systemId: s.systemId,
      tools: s.tools.map((t) => {
        const was = before?.tools.find((b) => b.name === t.name);
        const anew =
          !!t.picked && (!was || was.mode !== t.mode || was.chosenForEffect !== t.picked);
        return anew
          ? { name: t.name, mode: t.mode, chosenForEffect: t.picked }
          : { name: t.name, mode: t.mode };
      }),
    };
  });
}

function repositoryInputs(draft: AgentDraft) {
  return draft.repositories.map((r) => ({ repositoryId: r.repositoryId, access: r.access }));
}

function hostInputs(draft: AgentDraft): AgentHostInput[] {
  return draft.hosts.map((h) => ({ host: h.host, reason: h.reason?.trim() || null }));
}

/** The bounds of an agent's time limit of a turn, in minutes (`Agent.turnTimeLimitMinutes`). */
export const TURN_TIME_LIMIT_MIN = 5;
export const TURN_TIME_LIMIT_MAX = 1440;

/** The time limit as typed, in whole minutes; null when empty (the deployment's default). */
export function turnTimeLimitOf(typed: string): number | null {
  const text = typed.trim();
  return text ? Number(text) : null;
}

/** What is wrong with a typed time limit, or null: empty, or 5 to 1440 whole minutes. */
export function turnTimeLimitProblem(typed: string): string | null {
  const minutes = turnTimeLimitOf(typed);
  if (minutes === null) return null;
  return Number.isInteger(minutes) &&
    minutes >= TURN_TIME_LIMIT_MIN &&
    minutes <= TURN_TIME_LIMIT_MAX
    ? null
    : `Enter ${TURN_TIME_LIMIT_MIN} to ${TURN_TIME_LIMIT_MAX} minutes, or leave it empty for the default.`;
}

/** A new agent in one `createAgent`: version 1, with everything the draft holds. */
export function createRequest(draft: AgentDraft): AgentCreateRequest {
  const limit = turnTimeLimitOf(draft.turnTimeLimitMinutes);
  return {
    name: draft.name.trim(),
    handle: draft.handle.trim() || null,
    description: draft.description.trim() || null,
    instructions: draft.instructions || null,
    runtimes: [...draft.runtimes],
    // Left out for the deployment's default.
    ...(limit !== null ? { turnTimeLimitMinutes: limit } : {}),
    memoryAllowed: draft.memoryAllowed,
    systems: systemInputs(draft),
    repositories: repositoryInputs(draft),
    hosts: hostInputs(draft),
    shareGroupIds: [...draft.shareGroupIds],
    sharePrincipalIds: [...draft.sharePrincipalIds],
  };
}

/**
 * The skills as `replaceAgentSkills` takes them, as a whole: a skill the saved agent pins at the
 * same version goes without `version`, which keeps its pin (a deleted skill can never move, so it
 * is never sent with one); a moved or a new skill names its version.
 */
export function skillInputs(draft: AgentDraft, saved?: AgentDraft): AgentSkillInput[] {
  return draft.skills.map((k) => {
    const was = saved?.skills.find((b) => b.skillId === k.skillId);
    return was && was.version === k.version
      ? { skillId: k.skillId }
      : { skillId: k.skillId, version: k.version };
  });
}

/** The sections of the editor, in the order a change sends them. */
export type AgentSection = 'about' | 'skills' | 'systems' | 'repositories' | 'hosts' | 'shares';

export const SECTION_ORDER: readonly AgentSection[] = [
  'about',
  'skills',
  'systems',
  'repositories',
  'hosts',
  'shares',
];

/** What a change sends: only the sections it changed, each as its call takes it. */
export interface AgentChange {
  readonly about?: AgentUpdateRequest;
  readonly skills?: AgentSkillsRequest;
  readonly systems?: AgentSystemsRequest;
  readonly repositories?: AgentRepositoriesRequest;
  readonly hosts?: AgentHostsRequest;
  readonly shares?: AgentSharesRequest;
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/**
 * The fields of "Who it is" and "Instructions" that changed, as `updateAgent` takes them. Allowing
 * memory is not a widening: it reaches nothing a conversation could not reach already.
 */
function aboutChange(saved: AgentDraft, draft: AgentDraft): AgentUpdateRequest | undefined {
  const body: AgentUpdateRequest = {};
  if (draft.name.trim() !== saved.name) body.name = draft.name.trim();
  if (draft.handle.trim() !== saved.handle) body.handle = draft.handle.trim();
  if (draft.description.trim() !== saved.description.trim()) {
    body.description = draft.description.trim();
  }
  if (draft.instructions !== saved.instructions) body.instructions = draft.instructions;
  if (!same(draft.runtimes, saved.runtimes)) body.runtimes = [...draft.runtimes];
  const limit = turnTimeLimitOf(draft.turnTimeLimitMinutes);
  if (limit !== turnTimeLimitOf(saved.turnTimeLimitMinutes)) {
    // 0 returns to the deployment's default.
    body.turnTimeLimitMinutes = limit ?? 0;
  }
  if (draft.memoryAllowed !== saved.memoryAllowed) body.memoryAllowed = draft.memoryAllowed;
  return Object.keys(body).length > 0 ? body : undefined;
}

/** The sections a change of the saved agent sends, and nothing for a section left as it was. */
export function changeOf(agent: Agent, draft: AgentDraft): AgentChange {
  const saved = draftOf(agent);
  const change: {
    -readonly [K in keyof AgentChange]: AgentChange[K];
  } = {};
  const about = aboutChange(saved, draft);
  if (about) change.about = about;
  const pins = (d: AgentDraft) => d.skills.map((k) => `${k.skillId}@${k.version}`).sort();
  if (!same(pins(saved), pins(draft))) {
    change.skills = { skills: skillInputs(draft, saved) };
  }
  if (!same(systemInputs(saved), systemInputs(draft, saved))) {
    change.systems = { systems: systemInputs(draft, saved) };
  }
  if (!same(repositoryInputs(saved), repositoryInputs(draft))) {
    change.repositories = { repositories: repositoryInputs(draft) };
  }
  if (!same(hostInputs(saved), hostInputs(draft))) change.hosts = { hosts: hostInputs(draft) };
  if (
    !same([...saved.shareGroupIds].sort(), [...draft.shareGroupIds].sort()) ||
    !same([...saved.sharePrincipalIds].sort(), [...draft.sharePrincipalIds].sort())
  ) {
    change.shares = {
      groupIds: [...draft.shareGroupIds],
      principalIds: [...draft.sharePrincipalIds],
    };
  }
  return change;
}

/** The sections a change sends, in order. */
export function changedSections(change: AgentChange): AgentSection[] {
  return SECTION_ORDER.filter((section) => change[section] !== undefined);
}

// --- Widening ----------------------------------------------------------------------------------

/**
 * ", with 4 tools: 2 allowed, 2 need your approval" for a system the change adds; nothing without
 * tools.
 */
function toolCounts(tools: readonly DraftTool[]): string {
  if (tools.length === 0) return '';
  const allowed = tools.filter((t) => t.mode === 'ALLOW').length;
  const asks = tools.length - allowed;
  const parts = [
    allowed ? `${allowed} allowed` : '',
    asks ? `${asks} ${asks === 1 ? 'needs' : 'need'} your approval` : '',
  ].filter(Boolean);
  return `, with ${tools.length} ${tools.length === 1 ? 'tool' : 'tools'}: ${parts.join(', ')}`;
}

/** One thing a change adds over the saved agent, in words. */
export interface WideningItem {
  readonly key: string;
  readonly kind: 'system' | 'tool' | 'repository' | 'host' | 'instructions' | 'skill';
  readonly text: string;
}

/**
 * Whether a tool of the draft is "Allowed" for writes: its mode is chosen for a write, and its
 * effect is not a read the owner picked it for (as the server's `AgentDefinition.canAct` reads it).
 */
function allowsWrites(tool: DraftTool): boolean {
  if (tool.mode !== 'ALLOW') return false;
  const shown = tool.picked ?? tool.chosenForEffect;
  const now = tool.effect === 'READ' ? 'READ' : 'WRITE';
  return shown ? shown === 'WRITE' && now === 'WRITE' : now === 'WRITE';
}

/**
 * Whether the draft's ceiling lets the agent act on its own (the owner's decision Q4 of the
 * security review of 2026-10-27): a tool "Allowed" for writes, a read and write repository, or a
 * network host. Within it, a change of the instructions or of the skills widens.
 */
export function canAct(draft: AgentDraft): boolean {
  return (
    draft.hosts.length > 0 ||
    draft.repositories.some((r) => r.access === 'WRITE') ||
    draft.systems.some((s) => s.tools.some(allowsWrites))
  );
}

/**
 * What a change adds over the saved agent (PLATFORM.md §6; the security review of 2026-10-12,
 * row 11): a new system, a new tool, a tool from "Needs approval" to "Allowed", a new repository or
 * one from read-only to read and write, a host none of the saved ones covered; and, where the agent
 * can act after the change ({@link canAct}, ADR-0103), its instructions changed, a skill added or
 * a pin moved. People who use the agent keep the saved version until they accept the change.
 * Names, descriptions, runtimes, the time limit, memory and sharing never widen, nor does a skill
 * removed.
 */
export function wideningOf(agent: Agent, draft: AgentDraft): WideningItem[] {
  const saved = draftOf(agent);
  const items: WideningItem[] = [];
  for (const system of draft.systems) {
    const before = saved.systems.find((s) => s.systemId === system.systemId);
    if (!before) {
      items.push({
        key: `system:${system.systemId}`,
        kind: 'system',
        text: `${system.name} (new)${toolCounts(system.tools)}`,
      });
      continue;
    }
    for (const tool of system.tools) {
      const was = before.tools.find((t) => t.name === tool.name);
      const name = `${system.name} · ${tool.title || tool.name}`;
      if (!was) {
        items.push({
          key: `tool:${system.systemId}:${tool.name}`,
          kind: 'tool',
          text: `${name}: ${tool.mode === 'ALLOW' ? 'Allowed' : 'Needs your approval'} (new)`,
        });
      } else if (effectiveMode(tool) === 'ALLOW' && effectiveMode(was) === 'ASK') {
        items.push({
          key: `tool:${system.systemId}:${tool.name}`,
          kind: 'tool',
          text: `${name}: Allowed, where it needed your approval`,
        });
      }
    }
  }
  for (const repository of draft.repositories) {
    const was = saved.repositories.find((r) => r.repositoryId === repository.repositoryId);
    if (!was) {
      items.push({
        key: `repository:${repository.repositoryId}`,
        kind: 'repository',
        text: `${repository.name}: ${repository.access === 'WRITE' ? 'read and write' : 'read-only'} (new)`,
      });
    } else if (repository.access === 'WRITE' && was.access === 'READ') {
      items.push({
        key: `repository:${repository.repositoryId}`,
        kind: 'repository',
        text: `${repository.name}: read and write, was read-only`,
      });
    }
  }
  for (const host of draft.hosts) {
    const normalized = normalizeHost(host.host);
    if (!saved.hosts.some((h) => hostCovers(normalizeHost(h.host), normalized))) {
      items.push({ key: `host:${normalized}`, kind: 'host', text: normalized });
    }
  }
  // What it is told after what it may reach, as the acceptance card lists them.
  return [...items, ...contentWidening(saved, draft)];
}

/** The instructions changed, and the skills added or moved, where the draft can act. */
function contentWidening(saved: AgentDraft, draft: AgentDraft): WideningItem[] {
  if (!canAct(draft)) return [];
  const items: WideningItem[] = [];
  if (draft.instructions !== saved.instructions) {
    items.push({ key: 'instructions', kind: 'instructions', text: 'Its instructions, changed' });
  }
  for (const skill of draft.skills) {
    const was = saved.skills.find((k) => k.skillId === skill.skillId);
    if (!was) {
      items.push({
        key: `skill:${skill.skillId}`,
        kind: 'skill',
        text: `Skill ${skill.name}: version ${skill.version} (new)`,
      });
    } else if (was.version !== skill.version) {
      items.push({
        key: `skill:${skill.skillId}`,
        kind: 'skill',
        text: `Skill ${skill.name}: version ${skill.version}, was version ${was.version}`,
      });
    }
  }
  return items;
}
