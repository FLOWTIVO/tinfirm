import type { ListCompanyAuditEvents$Params } from '../../../api/generated/fn/audit/list-company-audit-events';
import { shortId, targetNoun } from '../../../shared/audit-event-view';
import { dayRangeLabel } from './days';

/**
 * The company audit log's filters (`GET /audit-events`, the package 6 contract §5.8; CONVENTIONS
 * §15.11), as the page keeps them in its URL: an action (exact, or a prefix ending in "."), an
 * actor (a kind, or a person), a target (type and id), a time (the last hour, today, the last seven
 * days, or a range of days), a conversation (by the key people know it by, or by the id an event
 * names) and whom the events are about (a principal: what they did, what was done to them, what
 * names them). Pure functions, tested as tables.
 */

export type ActorType = NonNullable<ListCompanyAuditEvents$Params['actorType']>;

/** A time relative to now: it stays relative in a link. */
export type AuditWhen = 'hour' | 'today' | 'week';

export interface AuditFilters {
  readonly action?: string;
  readonly actorType?: ActorType;
  readonly actorId?: string;
  readonly targetType?: string;
  readonly targetId?: string;
  /** The last hour, today or the last seven days; never with `from` or `to`. */
  readonly when?: AuditWhen;
  /** The first day, `yyyy-mm-dd`, in the reader's time zone. */
  readonly from?: string;
  /** The last day, included, `yyyy-mm-dd`. */
  readonly to?: string;
  /** A conversation by the id an event's details name ("In this conversation"). */
  readonly conversationId?: string;
  /** A conversation by its key, as a person types it (`shop-123-dev-1`), in lower case. */
  readonly conversationKey?: string;
  /** The events about a principal: what they did, what was done to them, what names them. */
  readonly principalId?: string;
}

export const AUDIT_FILTER_KEYS = [
  'action',
  'actorType',
  'actorId',
  'targetType',
  'targetId',
  'when',
  'from',
  'to',
  'conversationId',
  'conversationKey',
  'principalId',
] as const satisfies readonly (keyof AuditFilters)[];

export const WHEN_OPTIONS: readonly { value: AuditWhen; label: string; chip: string }[] = [
  { value: 'hour', label: 'Last hour', chip: 'Last hour' },
  { value: 'today', label: 'Today', chip: 'Today' },
  { value: 'week', label: '7 days', chip: 'Last 7 days' },
];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** A conversation's key (CONVENTIONS §15.7, `{base}-{n}`): the characters of a branch's name. */
const KEY = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;
const ACTION = /^[a-z0-9_.]{1,64}$/;
const TARGET_TYPE = /^[a-z0-9_]{1,64}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const ACTOR_TYPES: readonly ActorType[] = ['USER', 'SYSTEM', 'AGENT', 'SCIM'];
const WHENS: readonly string[] = WHEN_OPTIONS.map((w) => w.value);

export function isUuid(value: string): boolean {
  return UUID.test(value);
}

/** Whether the text is a conversation's key, in any case (`SHOP-123-dev-1`). */
export function isConversationKey(value: string): boolean {
  return value.length <= 93 && KEY.test(value.toLowerCase());
}

/**
 * The conversation filter from what a person typed: a key (`SHOP-123-dev-1`, kept in lower case),
 * or an id pasted from a conversation's address; empty text clears both.
 */
export function conversationFilter(
  text: string,
): Pick<AuditFilters, 'conversationId' | 'conversationKey'> {
  const value = text.trim();
  if (!value) return { conversationId: undefined, conversationKey: undefined };
  if (isUuid(value)) return { conversationId: value, conversationKey: undefined };
  return { conversationId: undefined, conversationKey: value.toLowerCase() };
}

/** The conversation filter as the field shows it: the key, else the id. */
export function conversationText(filters: AuditFilters): string {
  return filters.conversationKey ?? filters.conversationId ?? '';
}

export function isDay(value: string): boolean {
  if (!DAY.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  return date.getFullYear() === y && date.getMonth() === m - 1 && date.getDate() === d;
}

/** Why a value cannot be a filter, by field; empty when every value can. */
export function filterErrors(filters: AuditFilters): Partial<Record<keyof AuditFilters, string>> {
  const errors: Partial<Record<keyof AuditFilters, string>> = {};
  if (filters.action && !ACTION.test(filters.action)) {
    errors.action =
      'Lower-case letters, digits, dots and underscores, such as member. or tool.called.';
  }
  if (filters.actorId && !isUuid(filters.actorId)) errors.actorId = 'Not a person’s id.';
  if (filters.targetType && !TARGET_TYPE.test(filters.targetType)) {
    errors.targetType = 'Lower-case letters, digits and underscores, such as runner_host.';
  }
  if (filters.targetId && filters.targetId.length > 255)
    errors.targetId = 'At most 255 characters.';
  if (filters.when && !WHENS.includes(filters.when)) errors.when = 'Not a time.';
  if (filters.from && !isDay(filters.from)) errors.from = 'Pick a day.';
  if (filters.to && !isDay(filters.to)) errors.to = 'Pick a day.';
  if (
    filters.from &&
    filters.to &&
    isDay(filters.from) &&
    isDay(filters.to) &&
    filters.to < filters.from
  ) {
    errors.to = 'The last day comes before the first.';
  }
  if (filters.conversationId && !isUuid(filters.conversationId)) {
    errors.conversationId = 'Not a conversation’s id.';
  }
  if (filters.conversationKey && !isConversationKey(filters.conversationKey)) {
    errors.conversationKey = 'Type a conversation’s key, such as shop-123-dev-1.';
  }
  if (filters.principalId && !isUuid(filters.principalId)) {
    errors.principalId = 'Pick a member or a service account.';
  }
  return errors;
}

/**
 * The filters a URL's query holds, trimmed; a value that cannot be a filter (an old or edited link)
 * is left out rather than sent.
 */
export function filtersFromQuery(
  query: Readonly<Record<string, string | null | undefined>>,
): AuditFilters {
  const raw: Record<string, string> = {};
  for (const key of AUDIT_FILTER_KEYS) {
    const value = query[key]?.trim();
    if (value) raw[key] = value;
  }
  if (raw['actorType'] && !ACTOR_TYPES.includes(raw['actorType'] as ActorType)) {
    delete raw['actorType'];
  }
  if (raw['conversationKey']) raw['conversationKey'] = raw['conversationKey'].toLowerCase();
  const errors = filterErrors(raw as AuditFilters);
  for (const key of Object.keys(errors)) delete raw[key];
  // A person's id needs its kind.
  if (raw['actorId'] && raw['actorType'] !== 'USER') delete raw['actorId'];
  // A time relative to now, or days: not both.
  if (raw['when']) {
    delete raw['from'];
    delete raw['to'];
  }
  return raw as AuditFilters;
}

/** The query that holds the filters: every key, null for those not set, so a merge drops them. */
export function filtersToQuery(filters: AuditFilters): Record<string, string | null> {
  const query: Record<string, string | null> = {};
  for (const key of AUDIT_FILTER_KEYS) query[key] = filters[key]?.trim() || null;
  return query;
}

/** The filters without the keys that hold nothing, so two sets compare by value. */
export function compactFilters(filters: AuditFilters): AuditFilters {
  const out: Record<string, string> = {};
  for (const key of AUDIT_FILTER_KEYS) {
    const value = filters[key]?.trim();
    if (value) out[key] = value;
  }
  return out as AuditFilters;
}

export function sameFilters(a: AuditFilters, b: AuditFilters): boolean {
  return JSON.stringify(compactFilters(a)) === JSON.stringify(compactFilters(b));
}

/** How many filters are set, as the chips show them: a person, a target and a time count once. */
export function activeFilterCount(filters: AuditFilters): number {
  return filterChips(filters).length;
}

function startOfDay(day: string, plusDays = 0): Date {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d + plusDays);
}

/** Midnight of `now`'s day, `plusDays` later (or earlier). */
function midnight(now: Date, plusDays = 0): Date {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + plusDays);
}

/**
 * The API's parameters: a day range becomes `from` (included) and the next day's start (`to`,
 * excluded); a time relative to now starts an hour ago, at midnight, or at midnight six days ago.
 */
export function auditQuery(
  filters: AuditFilters,
  now: Date = new Date(),
): Omit<ListCompanyAuditEvents$Params, 'companyId' | 'limit' | 'cursor'> {
  const params: Omit<ListCompanyAuditEvents$Params, 'companyId' | 'limit' | 'cursor'> = {};
  if (filters.action) params.action = filters.action;
  if (filters.actorType) params.actorType = filters.actorType;
  if (filters.actorType === 'USER' && filters.actorId) params.actorId = filters.actorId;
  if (filters.targetType) params.targetType = filters.targetType;
  if (filters.targetId) params.targetId = filters.targetId;
  if (filters.when === 'hour') {
    params.from = new Date(now.getTime() - 60 * 60 * 1000).toISOString();
  } else if (filters.when === 'today') {
    params.from = midnight(now).toISOString();
  } else if (filters.when === 'week') {
    params.from = midnight(now, -6).toISOString();
  } else {
    if (filters.from) params.from = startOfDay(filters.from).toISOString();
    if (filters.to) params.to = startOfDay(filters.to, 1).toISOString();
  }
  if (filters.conversationId) params.conversationId = filters.conversationId;
  if (filters.conversationKey) params.conversationKey = filters.conversationKey;
  if (filters.principalId) params.principalId = filters.principalId;
  return params;
}

/** The actor select's value: anyone, a kind, or a person (`USER:<id>`). */
export function actorChoice(filters: AuditFilters): string {
  if (filters.actorType === 'USER' && filters.actorId) return `USER:${filters.actorId}`;
  return filters.actorType ?? '';
}

export function fromActorChoice(value: string): Pick<AuditFilters, 'actorType' | 'actorId'> {
  if (value.startsWith('USER:')) return { actorType: 'USER', actorId: value.slice(5) };
  return ACTOR_TYPES.includes(value as ActorType)
    ? { actorType: value as ActorType, actorId: undefined }
    : { actorType: undefined, actorId: undefined };
}

/** One filter in use, as a removable chip: its words, a value in mono, and what removing it clears. */
export interface FilterChip {
  readonly key: 'action' | 'actor' | 'time' | 'target' | 'conversation' | 'principal';
  readonly label: string;
  readonly value?: string;
  /** The remove button's name. */
  readonly aria: string;
  readonly clears: Partial<Record<keyof AuditFilters, undefined>>;
}

const KINDS: Record<ActorType, string> = {
  USER: 'any person',
  SYSTEM: 'Tinfirm itself',
  AGENT: 'an agent',
  SCIM: 'a SCIM token',
};

/** The filters in use, in the bar's order; `names` gives a person's or a target's name by id. */
export function filterChips(
  filters: AuditFilters,
  names: ReadonlyMap<string, string> = new Map(),
): FilterChip[] {
  const chips: FilterChip[] = [];
  if (filters.action) {
    chips.push({
      key: 'action',
      label: 'Action',
      value: filters.action,
      aria: 'Stop filtering by the action',
      clears: { action: undefined },
    });
  }
  if (filters.actorType) {
    const person = filters.actorType === 'USER' && filters.actorId;
    const name = person ? names.get(person) : undefined;
    chips.push({
      key: 'actor',
      label: person ? (name ? `By ${name}` : 'By a person') : `By ${KINDS[filters.actorType]}`,
      value: person && !name ? shortId(person) : undefined,
      aria: 'Stop filtering by who acted',
      clears: { actorType: undefined, actorId: undefined },
    });
  }
  const when = WHEN_OPTIONS.find((w) => w.value === filters.when);
  if (when || filters.from || filters.to) {
    chips.push({
      key: 'time',
      label: when ? when.chip : dayRangeLabel(filters.from, filters.to),
      aria: 'Stop filtering by time',
      clears: { when: undefined, from: undefined, to: undefined },
    });
  }
  if (filters.targetType || filters.targetId) {
    const noun = filters.targetType ? targetNoun(filters.targetType).toLowerCase() : '';
    const name = filters.targetId ? names.get(filters.targetId) : undefined;
    chips.push({
      key: 'target',
      label: !filters.targetId
        ? `On any ${noun}`
        : name
          ? `On ${noun ? noun + ' ' : ''}${name}`
          : `On ${noun || 'the target'}`,
      value: filters.targetId && !name ? shortId(filters.targetId) : undefined,
      aria: 'Stop filtering by the target',
      clears: { targetType: undefined, targetId: undefined },
    });
  }
  if (filters.conversationKey || filters.conversationId) {
    chips.push({
      key: 'conversation',
      label: 'In conversation',
      value: filters.conversationKey ?? shortId(filters.conversationId ?? ''),
      aria: 'Stop filtering by the conversation',
      clears: { conversationId: undefined, conversationKey: undefined },
    });
  }
  if (filters.principalId) {
    const name = names.get(filters.principalId);
    chips.push({
      key: 'principal',
      label: name ? `About ${name}` : 'About',
      value: name ? undefined : shortId(filters.principalId),
      aria: 'Stop filtering by whom the events are about',
      clears: { principalId: undefined },
    });
  }
  return chips;
}

/** How many of the filters under "More filters" are set: the target, the conversation, about whom. */
export function moreFilterCount(filters: AuditFilters): number {
  return (
    (filters.targetType || filters.targetId ? 1 : 0) +
    (filters.conversationId || filters.conversationKey ? 1 : 0) +
    (filters.principalId ? 1 : 0)
  );
}

/** The common prefixes of actions, for the action field's suggestions. */
export const ACTION_SUGGESTIONS: readonly { value: string; label: string }[] = [
  { value: 'member.', label: 'Members' },
  { value: 'group.', label: 'Groups' },
  { value: 'system.', label: 'Systems' },
  { value: 'account.', label: 'Accounts and credentials' },
  { value: 'credential.', label: 'Credentials: changed, lent, withdrawn' },
  { value: 'grant.', label: 'Grants' },
  { value: 'service_identity.', label: 'Service accounts' },
  { value: 'principal.', label: 'Principals' },
  { value: 'model_settings.', label: 'Model access' },
  { value: 'model_key.', label: 'Model keys and budgets' },
  { value: 'memory.', label: 'Agents’ memory' },
  { value: 'runner_host.', label: 'Runner hosts' },
  { value: 'agent.', label: 'Agents' },
  { value: 'conversation.', label: 'Conversations' },
  { value: 'run.', label: 'Runs' },
  { value: 'decision.', label: 'Decisions' },
  { value: 'tool.', label: 'Tool calls' },
  { value: 'secret.', label: 'Secrets' },
  { value: 'company.', label: 'The company' },
  { value: 'audit.', label: 'The audit log' },
];

/** The target types the company's chain records, for the target select. */
export const TARGET_TYPES: readonly string[] = [
  'account',
  'agent',
  'agent_settings',
  'attachment',
  'audit_check',
  'company',
  'company_model_key',
  'conversation',
  'conversation_binding',
  'decision',
  'grant',
  'group',
  'legal_hold',
  'memory_settings',
  'user',
  'model_settings',
  'principal_model_key',
  'output',
  'principal',
  'repository',
  'retention_settings',
  'run',
  'runner_host',
  'secret',
  'system',
  'trigger',
];
