import type { AuditActor, AuditEvent } from '../api/generated/models';
import { initials } from './forms';

/**
 * How the audit log reads an event (DESIGN.md: calm rows, machine text in mono): the day it
 * belongs to, its time, who acted as an initials tile and a name, and its target as a noun with
 * the name the server gave it (`targetName`, read now and only as the reader may see it) and the
 * short id. Pure functions, tested as tables.
 */

/** Which kind a `principal` target is, where the page knows it: a member or a service account. */
export type PrincipalKind = 'member' | 'service account';

/** The first eight characters of an id, as the log shows it next to a name. */
export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

const NOUNS: Readonly<Record<string, string>> = {
  account: 'Account',
  agent: 'Agent',
  agent_settings: 'Settings',
  api_token: 'Token',
  attachment: 'Attachment',
  audit_check: 'Daily check',
  company: 'Company',
  conversation: 'Conversation',
  conversation_binding: 'Binding',
  decision: 'Decision',
  grant: 'Grant',
  group: 'Group',
  instance_key: 'Instance key',
  legal_hold: 'Legal hold',
  model_settings: 'Model access',
  oauth_client: 'OAuth client',
  oauth_grant: 'OAuth grant',
  output: 'Output',
  outbox: 'Delivery',
  principal: 'Principal',
  principal_model_key: 'Model key',
  repository: 'Repository',
  retention_settings: 'Retention',
  run: 'Run',
  runner_host: 'Runner host',
  secret: 'Secret',
  system: 'System',
  trigger: 'Trigger',
  user: 'Member',
  vault: 'Vault',
};

/** A target type in words, sentence case: `runner_host` is "Runner host"; the instance's users are users. */
export function targetNoun(type: string, instance = false): string {
  if (instance && type === 'user') return 'User';
  const known = NOUNS[type];
  if (known) return known;
  const words = type.replace(/_/g, ' ').trim();
  return words ? words[0].toUpperCase() + words.slice(1) : type;
}

export interface TargetView {
  /** "Runner host", "Model key of", "Service account". */
  readonly noun: string;
  readonly name?: string;
  readonly id?: string;
  readonly shortId?: string;
}

/**
 * An event's target: its noun, its name (the server's `targetName`, else the name the event's own
 * `details.name` kept, as a deleted system's), and its short id. A principal is a member or a
 * service account where `kinds` knows which; a model key is a principal's: "Model key of Dave". An
 * account that is a system's credential (ADR-0072: `account.*` events with `details.credential`,
 * and `credential.changed`, `credential.lent`, `credential.withdrawn`) is a credential, by name.
 */
export function targetView(
  event: Pick<AuditEvent, 'targetType' | 'targetId' | 'targetName' | 'details'> &
    Partial<Pick<AuditEvent, 'action'>>,
  kinds: ReadonlyMap<string, PrincipalKind> = new Map(),
  instance = false,
): TargetView | null {
  if (!event.targetType) return null;
  const id = event.targetId ?? undefined;
  const ownName = event.details?.['name'];
  const credential = event.details?.['credential'];
  const keptName =
    typeof ownName === 'string' && ownName
      ? ownName
      : typeof credential === 'string' && credential
        ? credential
        : undefined;
  const name = event.targetName || keptName;
  let noun = targetNoun(event.targetType, instance);
  if (event.targetType === 'account' && isCredentialEvent(event)) noun = 'Credential';
  const kind = id ? kinds.get(id) : undefined;
  if (event.targetType === 'principal' && kind) {
    noun = kind === 'member' ? 'Member' : 'Service account';
  }
  if (event.targetType === 'principal_model_key' && name) noun = 'Model key of';
  return { noun, name, id, shortId: id ? shortId(id) : undefined };
}

/** Whether an event about an account is about a system's credential (ADR-0072). */
function isCredentialEvent(
  event: Pick<AuditEvent, 'details'> & Partial<Pick<AuditEvent, 'action'>>,
): boolean {
  if (event.action?.startsWith('credential.')) return true;
  return typeof event.details?.['credential'] === 'string';
}

export interface ActorView {
  readonly name: string;
  readonly initials: string;
  /** The tile: a person's is secondary, an agent's green, Tinfirm's a hairline. */
  readonly kind: 'person' | 'agent' | 'tinfirm';
  /** Tinfirm's own events, and actors who are gone, read muted. */
  readonly muted: boolean;
  /** In the opened event: "member", "agent", "its own event". */
  readonly noun: string;
}

/** What the server shows for an actor who is gone: no name to show. */
const GONE = ['deleted user', 'deleted agent', 'erased person'];

/**
 * Who acted: a person, an agent, or Tinfirm itself (never an all-caps badge). One who is gone
 * reads muted, in the server's words: a deleted user, a deleted agent, or an erased person (a
 * member whose content the company erased after they left, whom its audit names nowhere).
 */
export function actorView(actor: AuditActor): ActorView {
  if (actor.type === 'SYSTEM') {
    return { name: 'Tinfirm', initials: 'T', kind: 'tinfirm', muted: true, noun: 'its own event' };
  }
  const gone = !actor.id || GONE.includes(actor.display);
  const name = gone ? sentence(actor.display) : actor.display;
  if (actor.type === 'AGENT') {
    return { name, initials: initials(name), kind: 'agent', muted: gone, noun: 'agent' };
  }
  if (actor.type === 'SCIM') {
    // A company's SCIM token, by its name (ADR-0087): the identity provider acting through it.
    return {
      name: actor.display,
      initials: 'S',
      kind: 'tinfirm',
      muted: false,
      noun: 'SCIM token',
    };
  }
  return {
    name,
    initials: actor.id && !gone ? initials(name) : '?',
    kind: 'person',
    muted: gone,
    noun: actor.id ? 'member' : 'unknown person',
  };
}

function sentence(text: string): string {
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

const pad = (n: number) => String(n).padStart(2, '0');

/** "Wed 14 Oct". */
function weekdayDay(date: Date): string {
  return `${DAYS[date.getDay()].slice(0, 3)} ${date.getDate()} ${MONTHS[date.getMonth()].slice(0, 3)}`;
}

/** "14 Oct", or "14 Oct 2025" with its year. */
function dayMonth(date: Date, year: boolean): string {
  const day = `${date.getDate()} ${MONTHS[date.getMonth()].slice(0, 3)}`;
  return year ? `${day} ${date.getFullYear()}` : day;
}

/** "Wednesday 14 October 2026". */
function longDay(date: Date): string {
  return `${DAYS[date.getDay()]} ${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
}

function clock(date: Date, seconds = true): string {
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return seconds ? `${time}:${pad(date.getSeconds())}` : time;
}

/** "14:12:05", in the reader's time zone. */
export function clockTime(value: string | Date): string {
  const date = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? String(value) : clock(date);
}

/** "03:12". */
export function shortClockTime(value: string | Date): string {
  const date = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? String(value) : clock(date, false);
}

/** "Wednesday 14 October 2026, 14:12:05". */
export function longTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : `${longDay(date)}, ${clock(date)}`;
}

function localDay(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export interface DayHeading {
  /** "Today", "Yesterday", "12 Oct". */
  readonly label: string;
  /** "Wed 14 Oct", or the weekday after a date. */
  readonly date: string;
  /** "Wednesday 14 October 2026", for the list's name. */
  readonly long: string;
}

/** A day's heading, relative to `now`. */
export function dayHeading(date: Date, now: Date = new Date()): DayHeading {
  const long = longDay(date);
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (localDay(date) === localDay(now)) return { label: 'Today', date: weekdayDay(date), long };
  if (localDay(date) === localDay(yesterday)) {
    return { label: 'Yesterday', date: weekdayDay(date), long };
  }
  const label = dayMonth(date, date.getFullYear() !== now.getFullYear());
  return { label, date: DAYS[date.getDay()], long };
}

/** "today", "yesterday", "on 12 Oct": when a check ran, inside a sentence. */
export function dayPhrase(date: Date, now: Date = new Date()): string {
  const heading = dayHeading(date, now);
  if (heading.label === 'Today' || heading.label === 'Yesterday')
    return heading.label.toLowerCase();
  return `on ${heading.label}`;
}

export interface DayGroup<T> {
  /** `yyyy-mm-dd`, in the reader's time zone. */
  readonly key: string;
  readonly heading: DayHeading;
  readonly events: readonly T[];
}

/** Events, newest first, by the day they happened on in the reader's time zone. */
export function dayGroups<T extends Pick<AuditEvent, 'occurredAt'>>(
  events: readonly T[],
  now: Date = new Date(),
): DayGroup<T>[] {
  const groups: { key: string; date: Date; events: T[] }[] = [];
  for (const event of events) {
    const date = new Date(event.occurredAt);
    const key = Number.isNaN(date.getTime()) ? 'unknown' : localDay(date);
    const last = groups.at(-1);
    if (last && last.key === key) last.events.push(event);
    else groups.push({ key, date, events: [event] });
  }
  return groups.map(({ key, date, events: items }) => ({
    key,
    heading:
      key === 'unknown'
        ? { label: 'Unknown day', date: '', long: 'an unknown day' }
        : dayHeading(date, now),
    events: items,
  }));
}

/** The hash a chain's first event chains from. */
export const GENESIS_HASH = '0'.repeat(64);
