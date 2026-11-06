import type {
  AccountAccess,
  AgentSettings,
  SystemKind,
  Trigger,
  TriggerAccountInput,
  TriggerAction,
  TriggerChoice,
  TriggerConditionGroup,
  TriggerCreateRequest,
  TriggerEvent,
  TriggerPreviewRequest,
  TriggerRepeat,
  TriggerResults,
  TriggerScheduleInput,
  TriggerSource,
  TriggerSubject,
  TriggerUpdateRequest,
  WebhookCondition,
} from '../../../api/generated/models';
import { knownTimeZone, parseCron, tooOften } from '../../../shared/cron';
import {
  type SettingsDraft,
  settingsDraftOf,
  settingsRequest,
} from '../../../shared/agent-settings-model';
import { EVENTS_WITH_VALUE, subjectsFor } from './trigger-labels';

/**
 * The trigger editor's draft (plan §4.5; the package 4 contract, §5.2 and §6) and what it sends:
 * the request of a new trigger, the preview's draft, and for a change the definition, the account
 * table and the settings as three requests. The server checks everything again (§5.2); this only
 * keeps the form's state and says what the client can tell before the preview answers.
 */

export const MAX_EVENTS = 5;
export const MAX_CONDITION_GROUPS = 5;
export const MAX_ACTIONS = 10;
export const MAX_NAME = 100;
export const MAX_INSTRUCTION = 20_000;

export interface DraftEvent {
  readonly event: TriggerEvent;
  readonly value: string;
}

/** A group of conditions as typed: comma-separated lists, each with its "none of". */
export interface DraftConditionGroup {
  readonly statuses: string;
  readonly notStatuses: string;
  readonly labels: string;
  readonly notLabels: string;
  readonly types: string;
  readonly notTypes: string;
  readonly authors: '' | 'PEOPLE' | 'TINFIRM';
}

/** A channel of a channel trigger (package 5b): the bot listens there; one may be the default. */
export interface DraftChannel {
  readonly channelId: string;
  readonly name: string | null;
  readonly default: boolean;
}

/** A webhook trigger's condition on the raw body, as typed: the values comma-separated. */
export interface DraftWebhookCondition {
  readonly pointer: string;
  readonly op: WebhookCondition['op'];
  readonly values: string;
}

export const MAX_CHANNELS = 20;
export const MAX_WEBHOOK_CONDITIONS = 5;

export interface DraftSchedule {
  readonly cron: string;
  readonly zone: string;
  readonly overlap: 'SKIP' | 'CREATE';
  readonly catchUp: 'NONE' | 'ONE';
}

export interface TriggerDraft {
  readonly name: string;
  readonly source: TriggerSource;
  /** A tracker's system; null for a schedule, or while none is picked. */
  readonly systemId: string | null;
  readonly events: readonly DraftEvent[];
  readonly subject: TriggerSubject;
  readonly scope: string;
  readonly conditions: readonly DraftConditionGroup[];
  readonly repeat: TriggerRepeat;
  readonly schedule: DraftSchedule;
  /** A channel trigger's channels. */
  readonly channels: readonly DraftChannel[];
  /** A webhook trigger's mapped events, comma-separated; empty: every delivery. */
  readonly webhookEvents: string;
  readonly webhookConditions: readonly DraftWebhookCondition[];
  readonly agentId: string | null;
  /** The agent's version the editor showed (a new trigger accepts it). */
  readonly agentVersion: number | null;
  readonly instruction: string;
  /** Whose accounts the one choice filled the table from; null once rows were changed by hand. */
  readonly choice: string | null;
  /**
   * The account table: one row per system, the author's own account or a service account with the
   * credential it borrows there (`holderPrincipalId`, ADR-0072).
   */
  readonly accounts: readonly TriggerAccountInput[];
  readonly payerPrincipalId: string | null;
  /** The trigger's narrowing of its agent; null until the agent's settings are known. */
  readonly settings: SettingsDraft | null;
  readonly teamGroupId: string | null;
  readonly results: TriggerResults;
  /** Empty: the company's default. */
  readonly maxFiringsPerHour: string;
  readonly acknowledgeOpenEvents: boolean;
}

export function emptyConditionGroup(): DraftConditionGroup {
  return {
    statuses: '',
    notStatuses: '',
    labels: '',
    notLabels: '',
    types: '',
    notTypes: '',
    authors: '',
  };
}

export function emptyDraft(zone = 'UTC'): TriggerDraft {
  return {
    name: '',
    source: 'TRACKER',
    systemId: null,
    events: [{ event: 'ASSIGNED', value: '' }],
    subject: 'ISSUE',
    scope: '',
    conditions: [],
    repeat: 'EVERY_TIME',
    schedule: { cron: '0 9 * * 1', zone, overlap: 'SKIP', catchUp: 'NONE' },
    channels: [],
    webhookEvents: '',
    webhookConditions: [],
    agentId: null,
    agentVersion: null,
    instruction: '',
    choice: null,
    accounts: [],
    payerPrincipalId: null,
    settings: null,
    teamGroupId: null,
    results: { comment: true, review: false, onSuccess: [], onFailure: [] },
    maxFiringsPerHour: '',
    acknowledgeOpenEvents: false,
  };
}

function joinList(values: readonly string[] | null | undefined): string {
  return (values ?? []).join(', ');
}

/** A comma-separated list: trimmed, without empty entries and without duplicates ignoring case. */
export function parseList(text: string): string[] {
  const seen = new Set<string>();
  const entries: string[] = [];
  for (const part of text.split(',')) {
    const entry = part.trim();
    const key = entry.toLowerCase();
    if (entry && !seen.has(key)) {
      seen.add(key);
      entries.push(entry);
    }
  }
  return entries;
}

/** A stored trigger, and its settings, as the editor's draft. */
export function draftOf(trigger: Trigger, settings: AgentSettings | null): TriggerDraft {
  const empty = emptyDraft();
  return {
    name: trigger.name,
    source: trigger.source,
    systemId: trigger.systemId ?? null,
    events:
      trigger.source === 'TRACKER'
        ? trigger.events.map((e) => ({ event: e.event, value: e.value ?? '' }))
        : empty.events,
    subject: trigger.subject,
    scope: trigger.scope ?? '',
    conditions: trigger.conditions.map((g) => ({
      statuses: joinList(g.statuses),
      notStatuses: joinList(g.notStatuses),
      labels: joinList(g.labels),
      notLabels: joinList(g.notLabels),
      types: joinList(g.types),
      notTypes: joinList(g.notTypes),
      authors: g.authors ?? '',
    })),
    repeat: trigger.repeat,
    schedule: trigger.schedule
      ? {
          cron: trigger.schedule.cron,
          zone: trigger.schedule.zone,
          overlap: trigger.schedule.overlap,
          catchUp: trigger.schedule.catchUp,
        }
      : empty.schedule,
    channels: (trigger.channels ?? []).map((c) => ({
      channelId: c.channelId,
      name: c.name ?? null,
      default: c.default,
    })),
    webhookEvents:
      trigger.source === 'WEBHOOK'
        ? trigger.events
            .map((e) => e.value ?? '')
            .filter(Boolean)
            .join(', ')
        : '',
    webhookConditions: (trigger.webhookConditions ?? []).map((c) => ({
      pointer: c.pointer,
      op: c.op,
      values: joinList(c.values),
    })),
    agentId: trigger.agentId,
    agentVersion: trigger.acceptedVersion,
    instruction: trigger.instruction,
    choice: null,
    accounts: trigger.accounts
      .filter((a) => !!a.accountId)
      .map((a) => row(a.systemId, a.accountId!, a.holderPrincipalId)),
    payerPrincipalId: trigger.payer?.principalId ?? null,
    settings: settings ? settingsDraftOf(settings) : null,
    teamGroupId: trigger.teamGroupId ?? null,
    results: {
      comment: trigger.results.comment,
      review: trigger.results.review,
      onSuccess: trigger.results.onSuccess.map((a) => ({ ...a })),
      onFailure: trigger.results.onFailure.map((a) => ({ ...a })),
    },
    maxFiringsPerHour: String(trigger.maxFiringsPerHour),
    acknowledgeOpenEvents: false,
  };
}

// --- Requests ------------------------------------------------------------------------------------

/** A row of the account table as the API takes it: whom it acts through, where that is known. */
function row(
  systemId: string,
  accountId: string,
  holderPrincipalId: string | null | undefined,
): TriggerAccountInput {
  return holderPrincipalId ? { systemId, accountId, holderPrincipalId } : { systemId, accountId };
}

function conditionsOf(draft: TriggerDraft): TriggerConditionGroup[] {
  return draft.conditions
    .map((g) => ({
      statuses: parseList(g.statuses),
      notStatuses: parseList(g.notStatuses),
      labels: parseList(g.labels),
      notLabels: parseList(g.notLabels),
      types: parseList(g.types),
      notTypes: parseList(g.notTypes),
      authors: g.authors || null,
    }))
    .filter(
      (g) =>
        g.authors ||
        [g.statuses, g.notStatuses, g.labels, g.notLabels, g.types, g.notTypes].some(
          (l) => l.length > 0,
        ),
    );
}

function eventsOf(draft: TriggerDraft) {
  if (draft.source === 'SCHEDULE') return [];
  if (draft.source === 'CHANNEL') return [{ event: 'MENTIONED' as const, value: null }];
  if (draft.source === 'WEBHOOK') {
    const values = parseList(draft.webhookEvents);
    return values.length
      ? values.map((value) => ({ event: 'DELIVERED' as const, value }))
      : [{ event: 'DELIVERED' as const, value: null }];
  }
  return draft.events.map((e) => ({
    event: e.event,
    value: EVENTS_WITH_VALUE.has(e.event) ? e.value.trim() || null : null,
  }));
}

function webhookConditionsOf(draft: TriggerDraft): WebhookCondition[] {
  if (draft.source !== 'WEBHOOK') return [];
  return draft.webhookConditions.map((c) => ({
    pointer: c.pointer.trim(),
    op: c.op,
    values: c.op === 'PRESENT' || c.op === 'ABSENT' ? [] : parseList(c.values),
  }));
}

function scheduleOf(draft: TriggerDraft): TriggerScheduleInput | null {
  if (draft.source !== 'SCHEDULE') return null;
  return {
    cron: draft.schedule.cron.trim(),
    zone: draft.schedule.zone.trim(),
    overlap: draft.schedule.overlap,
    catchUp: draft.schedule.catchUp,
  };
}

function resultsOf(draft: TriggerDraft): TriggerResults {
  const clean = (actions: readonly TriggerAction[]) =>
    actions.map((a) => ({ kind: a.kind, value: a.value?.trim() || null }));
  return {
    comment: draft.results.comment,
    review: draft.results.review,
    onSuccess: clean(draft.results.onSuccess),
    onFailure: clean(draft.results.onFailure),
  };
}

function firingsOf(draft: TriggerDraft): number | null {
  const n = Number(draft.maxFiringsPerHour.trim());
  return draft.maxFiringsPerHour.trim() && Number.isInteger(n) && n > 0 ? n : null;
}

/** A new trigger as `createTrigger` takes it (and the preview's draft). */
export function createRequest(draft: TriggerDraft): TriggerCreateRequest {
  const tracker = draft.source === 'TRACKER';
  const results = resultsOf(draft);
  return {
    name: draft.name.trim(),
    source: draft.source,
    systemId: draft.source === 'SCHEDULE' ? null : draft.systemId,
    events: eventsOf(draft),
    subject: tracker ? draft.subject : 'ANY',
    scope: tracker ? draft.scope.trim() || null : null,
    conditions: tracker ? conditionsOf(draft) : [],
    repeat: tracker ? draft.repeat : 'EVERY_TIME',
    schedule: scheduleOf(draft),
    channels:
      draft.source === 'CHANNEL'
        ? draft.channels.map((c) => ({ channelId: c.channelId, default: c.default }))
        : [],
    webhookConditions: webhookConditionsOf(draft),
    agentId: draft.agentId ?? '',
    agentVersion: draft.agentVersion ?? 1,
    instruction: draft.instruction.trim(),
    accounts: draft.accounts.map((a) => row(a.systemId, a.accountId, a.holderPrincipalId)),
    payerPrincipalId: draft.payerPrincipalId ?? '',
    settings: draft.settings ? settingsRequest(draft.settings) : null,
    teamGroupId: draft.teamGroupId,
    // Only a tracker's results are written to its place; elsewhere the team's review stays.
    results: tracker ? results : { ...results, comment: false, onSuccess: [], onFailure: [] },
    maxFiringsPerHour: firingsOf(draft),
    acknowledgeOpenEvents: draft.acknowledgeOpenEvents,
  };
}

/** The preview's request: the draft as the save would send it, acknowledgement and all. */
export function previewRequest(
  draft: TriggerDraft,
  triggerId: string | null,
): TriggerPreviewRequest {
  return { triggerId, draft: createRequest(draft) };
}

/** A change's definition, whole (`updateTrigger` checks it whole, §5.2). */
export function updateRequest(draft: TriggerDraft): TriggerUpdateRequest {
  const body = createRequest(draft);
  return {
    name: body.name,
    instruction: body.instruction,
    events: draft.source === 'TRACKER' || draft.source === 'WEBHOOK' ? body.events : null,
    subject: draft.source === 'TRACKER' ? body.subject : null,
    scope: draft.source === 'TRACKER' ? (body.scope ?? '') : null,
    conditions: draft.source === 'TRACKER' ? body.conditions : null,
    repeat: draft.source === 'TRACKER' ? body.repeat : null,
    schedule: body.schedule,
    channels: draft.source === 'CHANNEL' ? body.channels : null,
    webhookConditions: draft.source === 'WEBHOOK' ? body.webhookConditions : null,
    teamGroupId: draft.teamGroupId,
    clearTeam: draft.teamGroupId === null,
    results: body.results,
    maxFiringsPerHour: body.maxFiringsPerHour,
    acknowledgeOpenEvents: draft.acknowledgeOpenEvents,
  };
}

/** What a change sends: the definition, the table and payer, the settings. */
export interface TriggerChange {
  readonly definition: boolean;
  readonly accounts: boolean;
  readonly settings: boolean;
}

export function changeOf(saved: TriggerDraft, draft: TriggerDraft): TriggerChange {
  const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
  const sorted = (rows: readonly TriggerAccountInput[]) =>
    [...rows].sort((a, b) => a.systemId.localeCompare(b.systemId));
  return {
    definition: !same(updateRequest(saved), updateRequest(draft)),
    accounts:
      !same(sorted(saved.accounts), sorted(draft.accounts)) ||
      saved.payerPrincipalId !== draft.payerPrincipalId,
    settings:
      !!draft.settings &&
      (!saved.settings || !same(settingsRequest(saved.settings), settingsRequest(draft.settings))),
  };
}

export function changed(change: TriggerChange): boolean {
  return change.definition || change.accounts || change.settings;
}

// --- The one choice and the account table --------------------------------------------------------

/**
 * "Use the accounts of: …" (§5.2, the one choice): every system the choice holds an account on
 * fills its row; a system without one is left out (not available to the trigger's runs); the
 * payer becomes the choice's budget. Someone else's budget pays with the budget only, never a
 * subscription (§3.3); the author's subscription pays only where the company allows it (ADR-0085,
 * decision 7), which the editor's settings form decides.
 */
export function fillFromChoice(
  draft: TriggerDraft,
  choice: TriggerChoice,
  authorPrincipalId: string | null,
): TriggerDraft {
  const accounts = choice.systems
    .filter((s) => s.status !== 'NONE' && !!s.accountId)
    .map((s) => row(s.systemId, s.accountId!, choice.principalId));
  const settings =
    draft.settings && choice.principalId !== authorPrincipalId
      ? { ...draft.settings, modelAccess: 'BUDGET' as const, subscription: null }
      : draft.settings;
  return {
    ...draft,
    choice: choice.principalId,
    accounts,
    payerPrincipalId: choice.budget.principalId,
    settings,
  };
}

/**
 * One account offered for a row: a choice's account on that system, the author's own or the
 * credential a service account borrows there. A credential lent to two service accounts is two
 * options, told apart by `key`.
 */
export interface RowOption {
  /** `principalId:accountId`, the select's value. */
  readonly key: string;
  readonly accountId: string;
  readonly principalId: string;
  readonly label: string;
  readonly reconnect: boolean;
  /** `READ`: a service account's credential lent read-only; its runs call no write tool there. */
  readonly access?: AccountAccess | null;
}

/** The select's value of a row: whom it acts through and with what. */
export function rowKey(principalId: string | null | undefined, accountId: string): string {
  return `${principalId ?? ''}:${accountId}`;
}

/**
 * The accounts a row may hold: each choice's on that system, the author's first named "Yours", a
 * service account's with the credential it borrows: "svc-reader with CI bot (svc-reader)".
 */
export function rowOptions(
  choices: readonly TriggerChoice[],
  systemId: string,
  authorPrincipalId: string | null,
): RowOption[] {
  const options: RowOption[] = [];
  for (const choice of choices) {
    const system = choice.systems.find((s) => s.systemId === systemId);
    if (!system?.accountId || system.status === 'NONE') continue;
    const own = choice.principalId === authorPrincipalId;
    const who = own ? 'Yours' : (choice.name ?? 'A service account');
    const credential = !own && system.credentialName ? ` with ${system.credentialName}` : '';
    const login = system.externalLogin ? ` (${system.externalLogin})` : '';
    const readOnly = system.access === 'READ' ? ', read-only' : '';
    options.push({
      key: rowKey(choice.principalId, system.accountId),
      accountId: system.accountId,
      principalId: choice.principalId,
      label: `${who}${credential}${login}${readOnly}${system.status === 'RECONNECT' ? ', needs signing in again' : ''}`,
      reconnect: system.status === 'RECONNECT',
      access: system.access ?? null,
    });
  }
  return options;
}

/** The draft with one row set by hand (or removed with null). */
export function withRow(
  draft: TriggerDraft,
  systemId: string,
  option: Pick<RowOption, 'accountId' | 'principalId'> | null,
): TriggerDraft {
  const others = draft.accounts.filter((a) => a.systemId !== systemId);
  return {
    ...draft,
    choice: null,
    accounts: option ? [...others, row(systemId, option.accountId, option.principalId)] : others,
  };
}

/** The systems the trigger would need: its source, then the agent's systems. */
export function neededSystems(
  sourceSystemId: string | null,
  agentSystemIds: readonly string[],
): string[] {
  const ids = sourceSystemId ? [sourceSystemId] : [];
  for (const id of agentSystemIds) if (!ids.includes(id)) ids.push(id);
  return ids;
}

/** Whether every row holds the author's own account: one's own MCP servers only then (T3). */
export function allRowsOwn(
  draft: TriggerDraft,
  choices: readonly TriggerChoice[],
  authorPrincipalId: string | null,
): boolean {
  const own = choices.find((c) => c.principalId === authorPrincipalId);
  if (!own) return draft.accounts.length === 0;
  return draft.accounts.every((row) =>
    own.systems.some((s) => s.systemId === row.systemId && s.accountId === row.accountId),
  );
}

// --- What the client can tell before the preview ------------------------------------------------

/** The editor's sections, in order. */
export type TriggerSection =
  'when' | 'agent' | 'accounts' | 'payer' | 'settings' | 'team' | 'results';

export type DraftErrors = Partial<Record<TriggerSection | 'name', string>>;

/**
 * What keeps a draft from being previewed or saved, said before the server: a name, an agent and
 * an instruction; a tracker's system and events with their values; a schedule's cron, its floor
 * and its zone; a row on the source system (SOURCE_ACCOUNT_REQUIRED); a payer.
 */
export function draftErrors(draft: TriggerDraft): DraftErrors {
  const errors: DraftErrors = {};
  if (!draft.name.trim()) errors.name = 'Name the trigger.';
  else if (draft.name.trim().length > MAX_NAME) errors.name = `At most ${MAX_NAME} characters.`;
  if (draft.source === 'TRACKER') {
    if (!draft.systemId) errors.when = 'Pick the system the trigger watches.';
    else if (draft.events.length === 0) errors.when = 'Pick at least one event.';
    else {
      const missing = draft.events.find((e) => EVENTS_WITH_VALUE.has(e.event) && !e.value.trim());
      if (missing) errors.when = 'Name the status or the label of each event that takes one.';
    }
  } else if (draft.source === 'CHANNEL') {
    if (!draft.systemId) errors.when = 'Pick the chat whose channels it listens in.';
    else if (draft.channels.length === 0) errors.when = 'Pick at least one channel.';
    else if (draft.channels.length > MAX_CHANNELS)
      errors.when = `At most ${MAX_CHANNELS} channels.`;
  } else if (draft.source === 'WEBHOOK') {
    if (!draft.systemId) errors.when = 'Pick the webhook that starts it.';
    else {
      const bad = draft.webhookConditions.find(
        (c) =>
          !c.pointer.trim().startsWith('/') ||
          (c.op === 'EQUALS' && parseList(c.values).length !== 1) ||
          (c.op === 'IN' && parseList(c.values).length === 0),
      );
      if (bad)
        errors.when =
          'Each condition reads a JSON Pointer such as /issue/key, and is one value or one of several.';
    }
  } else {
    const parsed = parseCron(draft.schedule.cron);
    if ('error' in parsed) errors.when = parsed.error;
    else if (tooOften(parsed.cron))
      errors.when = tooOften(parsed.cron)!.replace('a routine', 'a schedule');
    else if (!knownTimeZone(draft.schedule.zone))
      errors.when = 'Pick a time zone, e.g. Europe/Prague.';
  }
  if (!draft.agentId) errors.agent = 'Pick the agent.';
  else if (!draft.instruction.trim()) errors.agent = 'Say what the agent does each time.';
  else if (draft.instruction.trim().length > MAX_INSTRUCTION)
    errors.agent = `At most ${MAX_INSTRUCTION} characters.`;
  if (
    draft.source === 'TRACKER' &&
    draft.systemId &&
    !draft.accounts.some((a) => a.systemId === draft.systemId)
  ) {
    errors.accounts =
      'The trigger needs an account on the system it watches: it reads it and writes back with it.';
  }
  if (!draft.payerPrincipalId) errors.payer = 'Pick who pays.';
  const firings = draft.maxFiringsPerHour.trim();
  if (firings && !(Number.isInteger(Number(firings)) && Number(firings) > 0)) {
    errors.results = 'Firings per hour: a whole number, at least 1.';
  }
  return errors;
}

/** Whether a draft can be previewed: everything but the payer may still be missing for the panel. */
export function previewable(draft: TriggerDraft): boolean {
  const errors = draftErrors(draft);
  return !errors.name && !errors.when && !errors.agent && !errors.payer;
}

/** The subject kinds a source's system has, keeping the draft's when it is one of them. */
export function subjectFor(
  kind: SystemKind | null | undefined,
  current: TriggerSubject,
): TriggerSubject {
  const subjects = subjectsFor(kind);
  return subjects.includes(current) ? current : subjects[0];
}

/** A new trigger's settings: nothing narrowed, the payer's budget. */
export function defaultSettingsDraft(): SettingsDraft {
  return {
    runtime: null,
    modelAccess: 'BUDGET',
    subscription: null,
    model: '',
    effort: 'DEFAULT',
    instructions: '',
    memory: false,
    autoRetry: false,
    defaultRepositoryIds: [],
    defaultProjects: [],
    systemsOff: [],
    toolModes: [],
    ownServers: [],
    skillsOff: [],
  };
}
