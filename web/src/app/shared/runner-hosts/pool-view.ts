import type {
  RunnerHost,
  RunnerHostPool,
  RunnerHostPoolChange,
  RunnerHostPoolSettings,
  RunnerHostPoolStatus,
  RunnerHostPoolWindow,
} from '../../api/generated/models';
import { formatInZone, midnightsAfter } from '../cron';

/** A window's days, Monday first, as the API takes them. */
export type PoolDay = RunnerHostPoolWindow['days'][number];

export const POOL_DAYS: readonly PoolDay[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

export const POOL_DAY_LABELS: Record<PoolDay, string> = {
  MON: 'Mon',
  TUE: 'Tue',
  WED: 'Wed',
  THU: 'Thu',
  FRI: 'Fri',
  SAT: 'Sat',
  SUN: 'Sun',
};

/** As the server checks them (ADR-0099). */
export const MAX_POOL_WINDOWS = 14;

/** `HH:mm` from 00:00 to 23:59, as the API's pattern says. */
const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

/** The preset an admin starts from (D5 of the Kubernetes runner analysis): working days 08:00 to 18:00, two ready. */
export function workingDaysWindow(): RunnerHostPoolWindow {
  return { days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], from: '08:00', to: '18:00', ready: 2 };
}

/**
 * How many sandboxes a host may keep: its slots as set in Tinfirm, else its machine's limit. The
 * server checks every count against it.
 */
export function poolSize(host: RunnerHost): number {
  return host.desiredMaxConcurrentRuns ?? host.maxConcurrentRunsCeiling;
}

const WEEK_MS = 7 * 86_400_000;

/** A time of the week in the reader's zone: "18:00" today, "Mon 08:00" on another day. */
export function poolWhen(at: string | Date, zone: string, now: Date = new Date()): string {
  const [weekday, day, time] = formatInZone(new Date(at), zone).split(' ');
  const today = formatInZone(now, zone).split(' ')[1];
  return day === today ? time : `${weekday} ${time}`;
}

function ready(n: number): string {
  return n === 0 ? 'none' : String(n);
}

/**
 * The zone a pool's times show in: the pool's own, else its first window's, else the reader's
 * (the analysis §6.4: the card speaks the schedule's time).
 */
export function poolZone(pool: RunnerHostPool, readerZone: string): string {
  return pool.settings?.zone ?? pool.settings?.windows[0]?.zone ?? readerZone;
}

/** "Prague" for Europe/Prague, "New York" for America/New_York, "UTC" for UTC. */
export function zoneCity(zone: string): string {
  return (zone.split('/').pop() ?? zone).replaceAll('_', ' ');
}

/**
 * The count now and its next change, in the pool's zone: "Now 2 ready until 18:00, then 1."; a
 * reader elsewhere also gets their own time: "until 08:00 Prague, 02:00 your time". Without
 * settings no sandbox waits (D5), and every run starts cold.
 */
export function poolNow(pool: RunnerHostPool, readerZone: string, now: Date = new Date()): string {
  if (!pool.settings) return 'No sandbox waits ready: each run starts its own.';
  if (!pool.next) {
    return pool.readyTarget === 0
      ? 'No sandbox waits ready at any time.'
      : `Always ${pool.readyTarget} ready.`;
  }
  const zone = poolZone(pool, readerZone);
  const there = poolWhen(pool.next.at, zone, now);
  const here = poolWhen(pool.next.at, readerZone, now);
  const until = there === here ? there : `${there} ${zoneCity(zone)}, ${here} your time`;
  return `Now ${ready(pool.readyTarget)} ready until ${until}, then ${ready(pool.next.readyTarget)}.`;
}

/**
 * Why the host keeps fewer than the schedule asks: its operator turned ready sandboxes off, or a
 * count is above the ceiling of the host (`--pool-max-ready`), which caps it. Null otherwise.
 */
export function poolCeilingNote(pool: RunnerHostPool): string | null {
  const ceiling = pool.maxReadyCeiling;
  if (ceiling === 0) {
    return 'Ready sandboxes are off on this host: its operator set --pool-max-ready 0.';
  }
  const settings = pool.settings;
  if (!settings) return null;
  const highest = Math.max(settings.readyDefault, ...settings.windows.map((w) => w.ready));
  return highest > ceiling
    ? `Applies up to ${ceiling} on this host, its operator’s limit (--pool-max-ready).`
    : null;
}

/** The host's last report: "2 ready · 1 starting · 3 busy". */
export function poolCounts(status: RunnerHostPoolStatus): string {
  return `${status.ready} ready · ${status.starting} starting · ${status.busy} busy`;
}

/** "1 min 35 s": how long a cold start takes, at the 90th percentile; null before the first. */
export function coldStart(status: RunnerHostPoolStatus): string | null {
  const seconds = status.coldStartP90Seconds;
  if (seconds === null || seconds === undefined) return null;
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest ? `${minutes} min ${rest} s` : `${minutes} min`;
}

/** "Last 24 hours: 7 runs took a ready sandbox, 2 started cold." */
export function poolServed(status: RunnerHostPoolStatus): string {
  const runs = (n: number) => (n === 1 ? '1 run' : `${n} runs`);
  return `Last 24 hours: ${runs(status.servedReady)} took a ready sandbox, ${status.servedCold} started cold.`;
}

/** One stretch of the week strip: a count from `from` to `to`, its share of the week. */
export interface PoolSegment {
  readonly from: Date;
  readonly to: Date;
  readonly count: number;
  /** The share of the week, 0 to 1. */
  readonly share: number;
  /** "08:00 to 18:00: 2 ready", "From Fri 18:00: none ready". */
  readonly label: string;
}

/** The week the server computed, as stretches of one count each, from now for 7 days. */
export function poolSegments(
  week: readonly RunnerHostPoolChange[],
  zone: string,
  now: Date = new Date(),
): PoolSegment[] {
  if (week.length === 0) return [];
  const start = new Date(week[0].at).getTime();
  const end = start + WEEK_MS;
  return week.map((change, i) => {
    const from = Math.max(new Date(change.at).getTime(), start);
    const to = Math.min(i + 1 < week.length ? new Date(week[i + 1].at).getTime() : end, end);
    const fromText = i === 0 ? 'now' : poolWhen(new Date(from), zone, now);
    const span =
      i + 1 < week.length
        ? `${i === 0 ? 'Now' : fromText} to ${poolWhen(new Date(to), zone, now)}`
        : `From ${fromText}`;
    return {
      from: new Date(from),
      to: new Date(to),
      count: change.readyTarget,
      share: Math.max(0, to - from) / WEEK_MS,
      label: `${span}: ${ready(change.readyTarget)} ready`,
    };
  });
}

/** Where each day of the strip begins in the reader's zone: its share of the week and its weekday. */
export function poolDayTicks(
  week: readonly RunnerHostPoolChange[],
  zone: string,
): { share: number; label: string }[] {
  if (week.length === 0) return [];
  const start = new Date(week[0].at);
  return midnightsAfter(start, 7, zone)
    .map((midnight) => ({
      share: (midnight.getTime() - start.getTime()) / WEEK_MS,
      label: formatInZone(midnight, zone).split(' ')[0],
    }))
    .filter((tick) => tick.share > 0 && tick.share < 1);
}

/** A window in the editor: its own zone, or null for the pool's. */
export interface PoolWindowDraft {
  days: PoolDay[];
  from: string;
  to: string;
  ready: number;
  zone: string | null;
}

/** The editor's model of the pool's settings. */
export interface PoolDraft {
  zone: string;
  readyDefault: number;
  windows: PoolWindowDraft[];
}

/** The settings to edit: the stored ones, else none ready outside a first window in the reader's zone. */
export function poolDraft(
  settings: RunnerHostPoolSettings | null | undefined,
  zone: string,
): PoolDraft {
  if (!settings) {
    return { zone, readyDefault: 0, windows: [{ ...workingDaysWindow(), zone: null }] };
  }
  return {
    zone: settings.zone ?? zone,
    readyDefault: settings.readyDefault,
    windows: settings.windows.map((w) => ({
      days: [...w.days],
      from: w.from,
      to: w.to,
      ready: w.ready,
      zone: w.zone ?? null,
    })),
  };
}

/** The request body of a draft, days Monday first. */
export function poolSettings(draft: PoolDraft): RunnerHostPoolSettings {
  return {
    zone: draft.zone.trim(),
    readyDefault: draft.readyDefault,
    windows: draft.windows.map((w) => ({
      days: POOL_DAYS.filter((day) => w.days.includes(day)),
      from: w.from,
      to: w.to,
      ready: w.ready,
      ...(w.zone ? { zone: w.zone.trim() } : {}),
    })),
  };
}

/** What the server would refuse, as the field it belongs to and the reason; empty when none. */
export function poolDraftProblems(
  draft: PoolDraft,
  size: number,
  knownZone: (zone: string) => boolean,
): Map<string, string> {
  const problems = new Map<string, string>();
  const count = (n: number) => Number.isInteger(n) && n >= 0 && n <= size;
  if (!knownZone(draft.zone)) problems.set('zone', 'Choose a time zone, e.g. Europe/Prague.');
  if (!count(draft.readyDefault)) {
    problems.set('readyDefault', `Choose a number from 0 to ${size}, the host’s slots.`);
  }
  if (draft.windows.length > MAX_POOL_WINDOWS) {
    problems.set('windows', `At most ${MAX_POOL_WINDOWS} windows.`);
  }
  draft.windows.forEach((w, i) => {
    if (w.days.length === 0) problems.set(`windows.${i}.days`, 'Choose at least one day.');
    if (!TIME.test(w.from) || !TIME.test(w.to)) {
      problems.set(`windows.${i}.time`, 'Give a start and an end, 00:00 to 23:59.');
    } else if (w.from === w.to) {
      problems.set(`windows.${i}.time`, 'A window ends at another time than it starts.');
    }
    if (!count(w.ready)) {
      problems.set(`windows.${i}.ready`, `Choose a number from 0 to ${size}, the host’s slots.`);
    }
    if (w.zone !== null && !knownZone(w.zone)) {
      problems.set(`windows.${i}.zone`, 'Choose a time zone, or the pool’s.');
    }
  });
  return problems;
}
