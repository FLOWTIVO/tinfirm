/**
 * The 5-field cron of a schedule (ADR-0032, CONVENTIONS §15.15), read the way the server reads
 * it: `minute hour day-of-month month day-of-week`, each `*`, a number, a range `a-b`, a step
 * `*` + `/n` or `a-b/n`, or a list of those. When both day fields name days (neither starts with
 * `*`), a day matches either. The server decides; this only lets the form say early what is wrong
 * and when the schedule will fire.
 */

interface Field {
  readonly name: string;
  readonly min: number;
  readonly max: number;
}

const MINUTE: Field = { name: 'minute', min: 0, max: 59 };
const HOUR: Field = { name: 'hour', min: 0, max: 23 };
const DAY: Field = { name: 'day-of-month', min: 1, max: 31 };
const MONTH: Field = { name: 'month', min: 1, max: 12 };
const WEEKDAY: Field = { name: 'day-of-week', min: 0, max: 7 };

/** A schedule fires at most every 15 minutes. */
export const MIN_INTERVAL_MINUTES = 15;

/** How far the next firings are looked for: long enough for the 29th of February. */
const HORIZON_DAYS = 9 * 366;

const DAY_MS = 86_400_000;

export interface Cron {
  readonly minutes: readonly number[];
  readonly hours: readonly number[];
  readonly days: ReadonlySet<number>;
  readonly months: ReadonlySet<number>;
  /** 0 (Sunday) to 6 (Saturday). */
  readonly weekdays: ReadonlySet<number>;
  readonly eitherDay: boolean;
}

export type CronResult = { readonly cron: Cron } | { readonly error: string };

class CronError extends Error {}

/** Parses a cron, or says why it cannot work, in the server's words. */
export function parseCron(expression: string): CronResult {
  const trimmed = expression.trim();
  if (!trimmed) {
    return { error: 'Enter a schedule, e.g. 0 9 1 * * for 09:00 on the first of each month.' };
  }
  const fields = trimmed.split(/\s+/);
  if (fields.length !== 5) {
    return {
      error: `A cron has five fields, minute hour day-of-month month day-of-week; this one has ${fields.length}.`,
    };
  }
  try {
    const weekdays = field(fields[4], WEEKDAY);
    if (weekdays.has(7)) {
      weekdays.delete(7);
      weekdays.add(0);
    }
    return {
      cron: {
        minutes: [...field(fields[0], MINUTE)].sort((a, b) => a - b),
        hours: [...field(fields[1], HOUR)].sort((a, b) => a - b),
        days: field(fields[2], DAY),
        months: field(fields[3], MONTH),
        weekdays,
        eitherDay: !fields[2].startsWith('*') && !fields[4].startsWith('*'),
      },
    };
  } catch (error) {
    if (error instanceof CronError) return { error: error.message };
    throw error;
  }
}

function field(text: string, f: Field): Set<number> {
  const values = new Set<number>();
  for (const item of text.split(',')) {
    if (!item) throw new CronError(`The ${f.name} field ${text} has an empty entry.`);
    let range = item;
    let step = 1;
    const slash = item.indexOf('/');
    if (slash >= 0) {
      range = item.slice(0, slash);
      step = number(item.slice(slash + 1), f, 'step');
      const span = f.max - f.min + 1;
      if (step < 1 || step > span) {
        throw new CronError(
          `The step ${step} in the ${f.name} field is out of range; it is 1 to ${span}.`,
        );
      }
      if (range !== '*' && !range.includes('-')) {
        throw new CronError(
          `In the ${f.name} field, a step needs * or a range before it, e.g. */15 or 0-30/15; ${item} has neither.`,
        );
      }
    }
    let from: number;
    let to: number;
    if (range === '*') {
      from = f.min;
      to = f.max;
    } else {
      const dash = range.indexOf('-');
      if (dash >= 0) {
        from = value(range.slice(0, dash), f);
        to = value(range.slice(dash + 1), f);
        if (from > to) {
          throw new CronError(
            `The range ${range} in the ${f.name} field goes backwards; write the smaller number first.`,
          );
        }
      } else {
        from = value(range, f);
        to = from;
      }
    }
    for (let v = from; v <= to; v += step) values.add(v);
  }
  return values;
}

function value(text: string, f: Field): number {
  const v = number(text, f, 'value');
  if (v < f.min || v > f.max) {
    throw new CronError(`The ${f.name} ${v} is out of range; it is ${f.min} to ${f.max}.`);
  }
  return v;
}

function number(text: string, f: Field, what: string): number {
  if (/^\d{1,4}$/.test(text)) return Number(text);
  if (/^[A-Za-z]+$/.test(text)) {
    const hint =
      f === WEEKDAY ? '1-5 for Monday to Friday (0 and 7 are Sunday)' : `${f.min}-${f.max}`;
    throw new CronError(`The ${f.name} field has ${text}; use numbers, e.g. ${hint}.`);
  }
  throw new CronError(
    `The ${f.name} field has ${text ? `the ${what} ${text}` : `an empty ${what}`}, which is not a number.`,
  );
}

/**
 * Why the cron fires more often than every 15 minutes: two minutes of an hour that are closer, also
 * across the end of an hour when two hours follow each other; null when it does not.
 */
export function tooOften(cron: Cron): string | null {
  const minutes = cron.minutes;
  for (let i = 1; i < minutes.length; i++) {
    const gap = minutes[i] - minutes[i - 1];
    if (gap < MIN_INTERVAL_MINUTES) return tooOftenMessage(minutes[i - 1], minutes[i], gap);
  }
  if (minutes.length > 1 && cron.hours.some((h) => cron.hours.includes((h + 1) % 24))) {
    const last = minutes[minutes.length - 1];
    const gap = 60 - last + minutes[0];
    if (gap < MIN_INTERVAL_MINUTES) return tooOftenMessage(last, minutes[0], gap);
  }
  return null;
}

function tooOftenMessage(from: number, to: number, gap: number): string {
  return `It fires at minute ${from} and at minute ${to}, ${gap} ${gap === 1 ? 'minute' : 'minutes'} apart; a routine fires at most every 15 minutes.`;
}

function firesOn(cron: Cron, year: number, month: number, day: number, weekday: number): boolean {
  if (!cron.months.has(month)) return false;
  const byDay = cron.days.has(day);
  const byWeekday = cron.weekdays.has(weekday);
  return cron.eitherDay ? byDay || byWeekday : byDay && byWeekday;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  let format = formatters.get(zone);
  if (!format) {
    format = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
    });
    formatters.set(zone, format);
  }
  return format;
}

/** The wall-clock time of an instant in the zone, as if it were UTC. */
function wallClock(utc: number, zone: string): number {
  const parts: Record<string, number> = {};
  for (const part of formatter(zone).formatToParts(new Date(utc))) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return Date.UTC(parts['year'], parts['month'] - 1, parts['day'], parts['hour'], parts['minute']);
}

function offsetAt(utc: number, zone: string): number {
  return wallClock(utc, zone) - Math.floor(utc / 60_000) * 60_000;
}

/**
 * The instant of a local time in the zone, as the server resolves it: a time that a change to
 * summer time skips moves forward by the gap, and a time that the change back repeats is its first
 * occurrence.
 */
function instantOf(local: number, zone: string): number {
  const before = local - offsetAt(local - DAY_MS, zone);
  const after = local - offsetAt(local + DAY_MS, zone);
  if (wallClock(before, zone) === local) return before;
  if (wallClock(after, zone) === local) return after;
  return before;
}

/** An IANA zone name such as Europe/Prague, UTC or Etc/GMT+2; offsets such as +02:00 are not. */
const ZONE_NAME = /^[A-Za-z_]+(\/[A-Za-z0-9_+-]+)*$/;

/** True when the zone is an IANA zone the browser knows, e.g. Europe/Prague or UTC. */
export function knownTimeZone(zone: string): boolean {
  const name = zone.trim();
  if (!ZONE_NAME.test(name)) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: name });
    return true;
  } catch {
    return false;
  }
}

/** The IANA zones the browser knows, with UTC. */
export function timeZones(): readonly string[] {
  const zones =
    typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
  return zones.includes('UTC') ? zones : ['UTC', ...zones];
}

/** The browser's own zone, the default of a new schedule. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** "Sun 2026-11-01 09:00": the wall-clock time of an instant in the zone. */
export function formatInZone(date: Date, zone: string): string {
  const wall = new Date(wallClock(date.getTime(), zone));
  return `${WEEKDAYS[wall.getUTCDay()]} ${wall.toISOString().slice(0, 16).replace('T', ' ')}`;
}

/** The next `days` local midnights after `from` in the zone, resolved as the server does. */
export function midnightsAfter(from: Date, days: number, zone: string): Date[] {
  const start = new Date(wallClock(from.getTime(), zone));
  const midnights: Date[] = [];
  for (let i = 1; i <= days; i++) {
    const local = Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate() + i);
    midnights.push(new Date(instantOf(local, zone)));
  }
  return midnights;
}

/** The next `count` firings strictly after `after`, read in the zone; fewer when it stops firing. */
export function nextFirings(cron: Cron, zone: string, after: Date, count: number): Date[] {
  const firings: Date[] = [];
  let cursor = after.getTime();
  while (firings.length < count) {
    const next = nextFiring(cron, zone, cursor);
    if (next === null) break;
    firings.push(new Date(next));
    cursor = next;
  }
  return firings;
}

function nextFiring(cron: Cron, zone: string, after: number): number | null {
  const start = new Date(wallClock(after, zone));
  for (let i = 0; i < HORIZON_DAYS; i++) {
    const day = new Date(
      Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate() + i),
    );
    const year = day.getUTCFullYear();
    const month = day.getUTCMonth() + 1;
    const date = day.getUTCDate();
    if (!firesOn(cron, year, month, date, day.getUTCDay())) continue;
    let first: number | null = null;
    for (const hour of cron.hours) {
      for (const minute of cron.minutes) {
        const at = instantOf(Date.UTC(year, month - 1, date, hour, minute), zone);
        if (at > after && (first === null || at < first)) first = at;
      }
    }
    if (first !== null) return first;
  }
  return null;
}
