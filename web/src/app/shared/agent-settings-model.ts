import type {
  Agent,
  AgentEffort,
  AgentModelAccess,
  AgentOwnServerTool,
  AgentProject,
  AgentRuntime,
  AgentSettings,
  AgentSettingsRequest,
  AgentSettingsToolMode,
  AgentSubscription,
  AgentToolMode,
  AgentWidening,
  ExternalSystem,
  ModelSubscription,
  SystemKind,
  SystemTool,
  ToolEffect,
} from '../api/generated/models';
import { problemDetail, problemMessage, toProblem } from '../api/problem';
import { RUNTIME_LABELS, SUBSCRIPTION_KIND_LABELS, TOOL_EFFECT_LABELS } from './labels';
import type { SegmentedOption } from './segmented';

/**
 * The model of `agent-settings-form` (plan §4.4): one person's (or, in package 4, one trigger's)
 * narrowing of one agent. Settings narrow and never widen (the package 3 contract, I4): on the
 * agent's systems a tool is only ever left as the agent says, made to ask, or switched off;
 * "Allowed" is offered only on one's own MCP servers.
 */

/** How the settings choose a tool of the agent: as the agent says, ask first, or off. */
export type SettingsToolChoice = '' | 'ASK' | 'OFF';

/** The draft the form edits; the page turns it into the request (`settingsRequest`). */
export interface SettingsDraft {
  /** The runtime chosen; null for the agent's first. */
  readonly runtime: AgentRuntime | null;
  readonly modelAccess: AgentModelAccess;
  readonly subscription: AgentSubscription | null;
  /** Empty: the runtime's default, which a budget does not pay for (`MODEL_REQUIRED`). */
  readonly model: string;
  readonly effort: AgentEffort;
  readonly instructions: string;
  /** The owner turned the agent's memory on (ADR-0063, opt-in). */
  readonly memory: boolean;
  /** The owner chose automatic retries of failed turns (ADR-0073, opt-in). */
  readonly autoRetry: boolean;
  /** Empty: all of the agent's. */
  readonly defaultRepositoryIds: readonly string[];
  readonly defaultProjects: readonly AgentProject[];
  readonly systemsOff: readonly string[];
  readonly toolModes: readonly AgentSettingsToolMode[];
  readonly ownServers: readonly SettingsOwnServer[];
  /** The agent's skills switched off, by skill id (ADR-0064). */
  readonly skillsOff: readonly string[];
}

/** A tool of one's own MCP server on the draft, with what the server stored of it. */
export interface SettingsOwnTool {
  readonly name: string;
  readonly mode: AgentToolMode;
  /** As stored: the mode, and the effect it was chosen for (I19); null for a tool not stored. */
  readonly savedMode: AgentToolMode | null;
  readonly chosenForEffect: 'READ' | 'WRITE' | null;
  /**
   * The effect the page showed when the reader picked the mode (`UNKNOWN` as `WRITE`); null until
   * they pick one. Sent as `chosenForEffect`, it chooses the mode anew for that effect.
   */
  readonly picked: 'READ' | 'WRITE' | null;
}

/** One of one's own MCP servers added to the agent; `tools` null lets the server fill them. */
export interface SettingsOwnServer {
  readonly systemId: string;
  readonly name: string | null;
  readonly tools: readonly SettingsOwnTool[] | null;
}

/** The effect a pick is made for: what the page shows, an unconfirmed effect as a write. */
export function effectOfPick(effect: ToolEffect | null | undefined): 'READ' | 'WRITE' {
  return effect === 'READ' ? 'READ' : 'WRITE';
}

/**
 * What a tool of one's own server does now: an "Allowed" chosen while it read asks once it writes
 * (I19), until the reader picks it again for what it is now.
 */
export function ownToolMode(
  tool: SettingsOwnTool,
  effect: ToolEffect | null | undefined,
): AgentToolMode {
  const chosenFor = tool.picked ?? tool.chosenForEffect;
  return tool.mode === 'ALLOW' && chosenFor === 'READ' && !!effect && effect !== 'READ'
    ? 'ASK'
    : tool.mode;
}

/** The tool as the request takes it: `chosenForEffect` only for a pick that chooses anything anew. */
function ownToolInput(tool: SettingsOwnTool): AgentOwnServerTool {
  const anew =
    !!tool.picked &&
    (tool.savedMode === null ||
      tool.savedMode !== tool.mode ||
      tool.chosenForEffect !== tool.picked);
  return anew
    ? { name: tool.name, mode: tool.mode, chosenForEffect: tool.picked }
    : { name: tool.name, mode: tool.mode };
}

export function settingsDraftOf(settings: AgentSettings): SettingsDraft {
  return {
    runtime: settings.chosenRuntime ?? null,
    modelAccess: settings.modelAccess,
    subscription: settings.subscription ?? null,
    model: settings.model ?? '',
    effort: settings.effort,
    instructions: settings.instructions ?? '',
    memory: settings.memory ?? false,
    autoRetry: settings.autoRetry ?? false,
    defaultRepositoryIds: [...settings.defaultRepositoryIds],
    defaultProjects: settings.defaultProjects.map((p) => ({ ...p })),
    systemsOff: [...settings.systemsOff],
    toolModes: settings.toolModes.map((t) => ({ ...t })),
    ownServers: settings.ownServers.map((s) => ({
      systemId: s.systemId,
      name: s.name ?? null,
      tools: s.tools
        ? s.tools.map((t) => ({
            name: t.name,
            mode: t.mode,
            savedMode: t.mode,
            chosenForEffect: t.chosenForEffect ?? null,
            picked: null,
          }))
        : null,
    })),
    skillsOff: [...settings.skillsOff],
  };
}

/** The draft as `putMyAgentSettings` (or a trigger's settings) takes it, as a whole. */
export function settingsRequest(draft: SettingsDraft): AgentSettingsRequest {
  return {
    runtime: draft.runtime,
    model: draft.model.trim() || null,
    effort: draft.effort,
    modelAccess: draft.modelAccess,
    subscription: draft.modelAccess === 'SUBSCRIPTION' ? draft.subscription : null,
    instructions: draft.instructions.trim() || null,
    memory: draft.memory,
    autoRetry: draft.autoRetry,
    defaultRepositoryIds: [...draft.defaultRepositoryIds],
    defaultProjects: draft.defaultProjects.map((p) => ({ systemId: p.systemId, key: p.key })),
    systemsOff: [...draft.systemsOff],
    toolModes: draft.toolModes.map((t) => ({ systemId: t.systemId, tool: t.tool, mode: t.mode })),
    ownServers: draft.ownServers.map((s) => ({
      systemId: s.systemId,
      tools: s.tools ? s.tools.map(ownToolInput) : null,
    })),
    skillsOff: [...draft.skillsOff],
  };
}

/** Whether two drafts would save the same settings. */
export function sameSettings(a: SettingsDraft, b: SettingsDraft): boolean {
  return JSON.stringify(settingsRequest(a)) === JSON.stringify(settingsRequest(b));
}

// --- The agent's tools, as the reader has them --------------------------------------------------

/** The three choices on a tool of the agent. Never "Allowed": settings narrow (I4). */
export const TOOL_CHOICES: readonly SegmentedOption[] = [
  { value: '', label: 'As the agent says' },
  { value: 'ASK', label: 'Ask me' },
  { value: 'OFF', label: 'Off' },
];

/** The same three for a trigger, whose writes ask its team rather than "me". */
export const TRIGGER_TOOL_CHOICES: readonly SegmentedOption[] = [
  { value: '', label: 'As the agent says' },
  { value: 'ASK', label: 'Ask' },
  { value: 'OFF', label: 'Off' },
];

/** The two choices on a tool of one's own MCP server. */
export const OWN_TOOL_CHOICES: readonly SegmentedOption[] = [
  { value: 'ALLOW', label: 'Allowed' },
  { value: 'ASK', label: 'Needs your approval' },
];

export interface SettingsToolRow {
  readonly systemId: string;
  readonly name: string;
  readonly title: string;
  readonly effect: ToolEffect | null;
  readonly effectLabel: string;
  /** What the agent does with it for this reader: their accepted version, I19 applied. */
  readonly agentMode: AgentToolMode;
  /** The tool comes with a version the reader has not accepted: not theirs until they do. */
  readonly pending: boolean;
  /** The choices offered; never "Allowed". */
  readonly options: readonly SegmentedOption[];
}

export interface SettingsSystemRow {
  readonly systemId: string;
  readonly name: string;
  readonly kind: SystemKind;
  /** The system comes with a version the reader has not accepted. */
  readonly pending: boolean;
  readonly tools: readonly SettingsToolRow[];
}

/**
 * The agent's systems and tools as the reader has them: each tool's mode in the version they
 * accepted (a tool the current version raised to "Allowed" still asks until they accept it, I19's
 * "Allowed while it reads" asks once the tool writes), and the tools and systems a version they
 * have not accepted adds marked pending. A tool its system no longer serves is left out.
 */
export function settingsSystemRows(
  agent: Pick<Agent, 'systems'>,
  widening: AgentWidening | null | undefined,
): SettingsSystemRow[] {
  return agent.systems.map((system) => {
    const systemPending = widening?.systems.includes(system.systemId) ?? false;
    return {
      systemId: system.systemId,
      name: system.name,
      kind: system.kind,
      pending: systemPending,
      tools: system.tools
        .filter((tool) => tool.offered)
        .map((tool) => {
          const widened = widening?.tools.find(
            (w) => w.systemId === system.systemId && w.tool === tool.name,
          );
          let agentMode: AgentToolMode = tool.mode;
          if (
            tool.mode === 'ALLOW' &&
            tool.chosenForEffect === 'READ' &&
            tool.effect &&
            tool.effect !== 'READ'
          ) {
            agentMode = 'ASK';
          }
          if (widened?.was) agentMode = 'ASK';
          return {
            systemId: system.systemId,
            name: tool.name,
            title: tool.title || tool.name,
            effect: tool.effect ?? null,
            effectLabel: tool.effect ? TOOL_EFFECT_LABELS[tool.effect] : '',
            agentMode,
            pending: systemPending || (widened !== undefined && !widened.was),
            options: TOOL_CHOICES,
          };
        }),
    };
  });
}

/** The reader's choice on a tool of the agent. */
export function toolChoice(
  draft: SettingsDraft,
  systemId: string,
  tool: string,
): SettingsToolChoice {
  return draft.toolModes.find((t) => t.systemId === systemId && t.tool === tool)?.mode ?? '';
}

/** The draft with a tool's choice set; "As the agent says" stores nothing. */
export function withToolChoice(
  draft: SettingsDraft,
  systemId: string,
  tool: string,
  choice: SettingsToolChoice,
): SettingsDraft {
  const others = draft.toolModes.filter((t) => !(t.systemId === systemId && t.tool === tool));
  return {
    ...draft,
    toolModes: choice ? [...others, { systemId, tool, mode: choice }] : others,
  };
}

/** What a tool does for the reader with their choice: "Allowed", "Needs your approval" or "Off". */
export function toolOutcome(row: SettingsToolRow, choice: SettingsToolChoice): string {
  if (choice === 'OFF') return 'Off';
  if (choice === 'ASK' || row.agentMode === 'ASK') return 'Needs your approval';
  return 'Allowed';
}

/**
 * "Ask before every write" (a person's settings, and a trigger that anyone may drive, the plan
 * §4.5): "Ask" on every write and unconfirmed tool of the agent left as the agent says, keeping
 * what is off. A tool that comes with a version not accepted yet is not the reader's to choose.
 */
export function askOnEveryWrite(
  agent: Pick<Agent, 'systems'>,
  draft: SettingsDraft,
  widening?: AgentWidening | null,
): SettingsDraft {
  let next = draft;
  for (const system of settingsSystemRows(agent, widening)) {
    for (const tool of system.tools) {
      if (
        !tool.pending &&
        tool.effect !== 'READ' &&
        toolChoice(next, system.systemId, tool.name) === ''
      ) {
        next = withToolChoice(next, system.systemId, tool.name, 'ASK');
      }
    }
  }
  return next;
}

/**
 * A system's tools in a few words, as the reader's choices leave them: "2 writes ask · 1 read
 * runs · 1 off", and how many come with the version `pendingVersion` not accepted yet.
 */
export function systemSummary(
  row: SettingsSystemRow,
  draft: SettingsDraft,
  pendingVersion: number | null = null,
): string {
  const counts = { writesAsk: 0, writesRun: 0, readsAsk: 0, readsRun: 0, off: 0, pending: 0 };
  for (const tool of row.tools) {
    const choice = toolChoice(draft, row.systemId, tool.name);
    const asks = toolOutcome(tool, choice) === 'Needs your approval';
    const reads = tool.effect === 'READ';
    if (tool.pending) counts.pending++;
    else if (choice === 'OFF') counts.off++;
    else if (reads) counts[asks ? 'readsAsk' : 'readsRun']++;
    else counts[asks ? 'writesAsk' : 'writesRun']++;
  }
  const part = (n: number, one: string, many: string) =>
    n ? [`${n} ${n === 1 ? one : many}`] : [];
  return [
    ...part(counts.writesAsk, 'write asks', 'writes ask'),
    ...part(counts.writesRun, 'write runs', 'writes run'),
    ...part(counts.readsAsk, 'read asks', 'reads ask'),
    ...part(counts.readsRun, 'read runs', 'reads run'),
    ...part(counts.off, 'off', 'off'),
    ...(counts.pending
      ? [`${counts.pending} with version ${pendingVersion ?? 'not accepted yet'}`]
      : []),
  ].join(' · ');
}

// --- What changed --------------------------------------------------------------------------------

function sameSet(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && [...a].sort().join('\n') === [...b].sort().join('\n');
}

/**
 * What a draft changes against the saved settings, by the names of the settings ("Model", "GitHub
 * off", "2 GitHub tools", "write-jira-ticket off"), for the unsaved-changes bar; empty when it
 * would save the same.
 */
export function settingsChanges(
  saved: SettingsDraft,
  draft: SettingsDraft,
  agent: Pick<Agent, 'systems'> & Partial<Pick<Agent, 'skills'>>,
): string[] {
  const a = settingsRequest(saved);
  const b = settingsRequest(draft);
  const changes: string[] = [];
  if (a.runtime !== b.runtime) changes.push('Runtime');
  if (
    a.modelAccess !== b.modelAccess ||
    JSON.stringify(a.subscription) !== JSON.stringify(b.subscription)
  ) {
    changes.push('Who pays');
  }
  if (a.model !== b.model) changes.push('Model');
  if (a.effort !== b.effort) changes.push('Effort');
  if (a.instructions !== b.instructions) changes.push('Added instructions');
  if (a.memory !== b.memory) changes.push('Memory');
  if (a.autoRetry !== b.autoRetry) changes.push('Automatic retries');
  if (!sameSet(a.defaultRepositoryIds ?? [], b.defaultRepositoryIds ?? [])) {
    changes.push('Default repositories');
  }
  const projects = (r: AgentSettingsRequest) =>
    (r.defaultProjects ?? []).map((p) => `${p.systemId} ${p.key}`);
  if (!sameSet(projects(a), projects(b))) changes.push('Default projects');

  const nameOf = (systemId: string) =>
    agent.systems.find((s) => s.systemId === systemId)?.name ?? 'A system';
  const offA = a.systemsOff ?? [];
  const offB = b.systemsOff ?? [];
  for (const id of offB.filter((id) => !offA.includes(id))) changes.push(`${nameOf(id)} off`);
  for (const id of offA.filter((id) => !offB.includes(id))) changes.push(`${nameOf(id)} on`);

  const modeOf = (r: AgentSettingsRequest, systemId: string, tool: string) =>
    r.toolModes?.find((t) => t.systemId === systemId && t.tool === tool)?.mode ?? '';
  const touched = new Map<string, Set<string>>();
  for (const t of [...(a.toolModes ?? []), ...(b.toolModes ?? [])]) {
    if (modeOf(a, t.systemId, t.tool) === modeOf(b, t.systemId, t.tool)) continue;
    touched.set(t.systemId, (touched.get(t.systemId) ?? new Set()).add(t.tool));
  }
  for (const [systemId, tools] of touched) {
    const n = tools.size;
    changes.push(`${n} ${nameOf(systemId)} ${n === 1 ? 'tool' : 'tools'}`);
  }

  if (JSON.stringify(a.ownServers) !== JSON.stringify(b.ownServers)) {
    changes.push('Your own MCP servers');
  }

  const skillName = (skillId: string) =>
    agent.skills?.find((s) => s.skillId === skillId)?.name ?? 'A skill';
  const skillsA = a.skillsOff ?? [];
  const skillsB = b.skillsOff ?? [];
  for (const id of skillsB.filter((id) => !skillsA.includes(id))) {
    changes.push(`${skillName(id)} off`);
  }
  for (const id of skillsA.filter((id) => !skillsB.includes(id))) {
    changes.push(`${skillName(id)} on`);
  }
  return changes;
}

// --- One's own MCP servers ------------------------------------------------------------------------

/** One of the reader's own MCP servers, with the tools it serves. */
export interface OwnServerOption {
  readonly system: Pick<ExternalSystem, 'id' | 'name' | 'handle'>;
  /** Null while unknown: the server then fills reads "Allowed", writes "Needs approval". */
  readonly tools: readonly SystemTool[] | null;
}

/**
 * The tools of an own server when it is added: reads "Allowed", writes and unconfirmed "Needs
 * approval".
 */
export function ownServerTools(tools: readonly SystemTool[] | null): SettingsOwnTool[] | null {
  if (!tools) return null;
  return tools
    .filter((t) => t.enabled)
    .map((t) => ({
      name: t.name,
      mode: t.effect === 'READ' ? 'ALLOW' : 'ASK',
      savedMode: null,
      chosenForEffect: null,
      picked: null,
    }));
}

// --- Who pays ------------------------------------------------------------------------------------

/** The runtime in effect for new conversations: the one chosen, or the agent's first. */
export function runtimeOf(draft: SettingsDraft, agent: Pick<Agent, 'runtimes'>): AgentRuntime {
  return draft.runtime && agent.runtimes.includes(draft.runtime)
    ? draft.runtime
    : agent.runtimes[0];
}

/** The runtimes, the agent's first named as its default. */
export function runtimeOptions(agent: Pick<Agent, 'runtimes'>): SegmentedOption[] {
  return agent.runtimes.map((runtime, i) => ({
    value: runtime,
    label: i === 0 ? `${RUNTIME_LABELS[runtime]} (default)` : RUNTIME_LABELS[runtime],
  }));
}

export interface PayerOption {
  /** `BUDGET`, or `sub:{runnerHostId}:{label}`. */
  readonly value: string;
  readonly label: string;
  readonly detail: string | null;
  readonly disabled: boolean;
}

export function payerValue(draft: SettingsDraft): string {
  return draft.modelAccess === 'SUBSCRIPTION' && draft.subscription
    ? `sub:${draft.subscription.runnerHostId ?? ''}:${draft.subscription.label}`
    : 'BUDGET';
}

const SUBSCRIPTION_STATUS: Record<ModelSubscription['status'], string | null> = {
  AVAILABLE: null,
  COOLDOWN: 'Cooling down: turns wait until it can be used again',
  INVALID: 'Its host says it no longer works',
  OFFLINE: 'Its host is offline',
};

/**
 * Who may pay for new conversations: the budget, and each subscription on the reader's own
 * runner hosts that serves the runtime in effect. A subscription the settings name that is gone
 * stays listed, so the page can say so.
 */
export function payerOptions(
  subscriptions: readonly ModelSubscription[],
  runtime: AgentRuntime,
  draft: SettingsDraft,
  budgetLabel = 'Your budget',
): PayerOption[] {
  const options: PayerOption[] = [
    { value: 'BUDGET', label: budgetLabel, detail: null, disabled: false },
  ];
  for (const s of subscriptions) {
    if (!s.runtimes.includes(runtime)) continue;
    options.push({
      value: `sub:${s.runnerHostId}:${s.label}`,
      label: `${SUBSCRIPTION_KIND_LABELS[s.kind]} on ${s.runnerHostName}`,
      detail: [`“${s.label}”`, SUBSCRIPTION_STATUS[s.status]].filter(Boolean).join(' · '),
      disabled: false,
    });
  }
  const current = payerValue(draft);
  if (current !== 'BUDGET' && !options.some((o) => o.value === current)) {
    options.push({
      value: current,
      label: `“${draft.subscription?.label}”`,
      detail: draft.subscription?.runnerHostId
        ? `It does not serve ${RUNTIME_LABELS[runtime]}, or it is no longer on your host`
        : 'Gone: its runner host was removed',
      disabled: true,
    });
  }
  return options;
}

/** Whether the draft names a subscription that cannot pay now (gone, or not serving). */
export function subscriptionGone(
  subscriptions: readonly ModelSubscription[],
  runtime: AgentRuntime,
  draft: SettingsDraft,
): boolean {
  if (draft.modelAccess !== 'SUBSCRIPTION') return false;
  const s = draft.subscription;
  return (
    !s?.runnerHostId ||
    !subscriptions.some(
      (o) =>
        o.runnerHostId === s.runnerHostId && o.label === s.label && o.runtimes.includes(runtime),
    )
  );
}

/** The draft with the payer picked from `payerOptions`. */
export function withPayer(
  draft: SettingsDraft,
  value: string,
  subscriptions: readonly ModelSubscription[],
): SettingsDraft {
  if (value === 'BUDGET') return { ...draft, modelAccess: 'BUDGET', subscription: null };
  const picked = subscriptions.find((s) => `sub:${s.runnerHostId}:${s.label}` === value);
  return picked
    ? {
        ...draft,
        modelAccess: 'SUBSCRIPTION',
        subscription: { runnerHostId: picked.runnerHostId, label: picked.label },
      }
    : draft;
}

// --- Models --------------------------------------------------------------------------------------

/**
 * Whether the reader's model access allows a model (CONVENTIONS §15.5): every model for an empty
 * list or `all-proxy-models` / `all-team-models`, a name of the list, or one a `*` pattern
 * prefixes.
 */
export function modelAllowed(model: string, allowed: readonly string[]): boolean {
  if (allowed.length === 0) return true;
  return allowed.some(
    (entry) =>
      entry === model ||
      entry === 'all-proxy-models' ||
      entry === 'all-team-models' ||
      (entry.endsWith('*') && model.startsWith(entry.slice(0, -1))),
  );
}

/** The models to pick from: the gateway's that the key allows, and the current one if it is not. */
export function modelChoices(
  gateway: readonly string[],
  allowed: readonly string[],
  current: string,
): { value: string; label: string }[] {
  const models = gateway.filter((m) => modelAllowed(m, allowed));
  const choices = models.map((m) => ({ value: m, label: m }));
  if (current && !models.includes(current)) {
    choices.unshift({ value: current, label: `${current} (not offered now)` });
  }
  return choices;
}

// --- Errors --------------------------------------------------------------------------------------

/** The parts of the form a problem can name. */
export type SettingsField =
  | 'runtime'
  | 'payer'
  | 'model'
  | 'instructions'
  | 'repositories'
  | 'systems'
  | 'ownServers'
  | 'skills';

export type SettingsErrors = Partial<Record<SettingsField, string>>;

const CODE_FIELDS: Record<string, SettingsField> = {
  RUNTIME_NOT_LISTED: 'runtime',
  MODEL_NOT_ALLOWED: 'model',
  MODEL_REQUIRED: 'model',
  SUBSCRIPTION_NOT_SERVING: 'payer',
  SUBSCRIPTION_GONE: 'payer',
  SYSTEM_NOT_ON_AGENT: 'systems',
  UNKNOWN_TOOL: 'systems',
  SKILL_NOT_ON_AGENT: 'skills',
};

/**
 * A failed save as field errors (`RUNTIME_NOT_LISTED`, `MODEL_NOT_ALLOWED`, `MODEL_REQUIRED`,
 * `SUBSCRIPTION_NOT_SERVING`, `SYSTEM_NOT_ON_AGENT`, `UNKNOWN_TOOL`) or a message for the whole
 * form (`SETTINGS_WIDEN` and the rest). The server's `detail` names the part, so it is kept.
 */
export function settingsFailure(error: unknown): {
  errors: SettingsErrors;
  message: string | null;
  at: SettingsErrorAt | null;
} {
  const problem = toProblem(error);
  const systemId = problemDetail(error, 'systemId');
  if ((problem.code === 'UNKNOWN_TOOL' || problem.code === 'SYSTEM_NOT_ON_AGENT') && systemId) {
    return {
      errors: {},
      message: null,
      at: { code: problem.code, systemId, tool: problemDetail(error, 'tool') ?? null },
    };
  }
  const field = problem.code ? CODE_FIELDS[problem.code] : undefined;
  const base = problemMessage(error);
  const message =
    problem.detail && problem.code && problem.status === 400 && !base.includes(problem.detail)
      ? `${base} ${problem.detail.trim().replace(/([^.!?])$/, '$1.')}`
      : base;
  if (field) return { errors: { [field]: message }, message: null, at: null };
  return { errors: {}, message, at: null };
}

/**
 * The system and tool a refused save names (`UNKNOWN_TOOL` with `details.systemId` and
 * `details.tool`, `SYSTEM_NOT_ON_AGENT` with `details.systemId`): the form says it where they
 * stand, in their names.
 */
export interface SettingsErrorAt {
  readonly code: 'UNKNOWN_TOOL' | 'SYSTEM_NOT_ON_AGENT';
  readonly systemId: string;
  readonly tool: string | null;
}

/** Where the form shows what a refused save names, and the sentence it shows there. */
export function settingsErrorAt(
  at: SettingsErrorAt,
  agent: Pick<Agent, 'name' | 'systems'>,
  ownServers: readonly OwnServerOption[],
): { place: 'system' | 'own' | 'section'; message: string } {
  const system = agent.systems.find((s) => s.systemId === at.systemId);
  const own = ownServers.find((o) => o.system.id === at.systemId);
  if (at.code === 'UNKNOWN_TOOL' && own) {
    const title = own.tools?.find((t) => t.name === at.tool)?.title || at.tool;
    return {
      place: 'own',
      message: `${own.system.name} no longer serves ${title ? `“${title}”` : 'one of its tools'}: switch it off and on again to take its tools as they are now.`,
    };
  }
  if (at.code === 'UNKNOWN_TOOL' && system) {
    const title = system.tools.find((t) => t.name === at.tool)?.title || at.tool;
    return {
      place: 'system',
      message: `${agent.name} no longer lists ${title ? `“${title}”` : 'one of its tools'} on ${system.name}: your choice on it cannot be kept. Reload the page to see the agent as it is now.`,
    };
  }
  return {
    place: 'section',
    message: `${agent.name} no longer uses a system your settings name. Reload the page to see the agent as it is now.`,
  };
}
