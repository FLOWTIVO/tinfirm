import type { RunnerHostPool } from '../../api/generated/models';
import { aPoolRunnerHost } from '../../testing/fixtures';
import { knownTimeZone } from '../cron';
import { capacityValues } from './capacity-select';
import {
  coldStart,
  poolCeilingNote,
  poolDayTicks,
  poolDraft,
  poolDraftProblems,
  poolNow,
  poolSegments,
  poolServed,
  poolSettings,
  poolSize,
  poolZone,
  zoneCity,
} from './pool-view';

const PRAGUE = 'Europe/Prague';
/** Monday 19 October 2026, 07:00 in Prague. */
const MONDAY_MORNING = new Date('2026-10-19T05:00:00Z');

function pool(overrides: Partial<RunnerHostPool> = {}): RunnerHostPool {
  return { ...aPoolRunnerHost().pool!, ...overrides };
}

describe('pool view', () => {
  it('reads the count now and its next change in the pool’s zone, with the reader’s own time', () => {
    expect(poolNow(pool(), PRAGUE, MONDAY_MORNING)).toBe('Now none ready until 08:00, then 2.');
    // From Saturday the change is on another day.
    expect(poolNow(pool(), PRAGUE, new Date('2026-10-17T10:00:00Z'))).toBe(
      'Now none ready until Mon 08:00, then 2.',
    );
    expect(poolNow(pool(), 'America/New_York', MONDAY_MORNING)).toBe(
      'Now none ready until 08:00 Prague, 02:00 your time, then 2.',
    );
    // London is an hour behind Prague, the same day.
    expect(poolNow(pool(), 'Europe/London', MONDAY_MORNING)).toBe(
      'Now none ready until 08:00 Prague, 07:00 your time, then 2.',
    );
    expect(zoneCity('America/New_York')).toBe('New York');
    expect(zoneCity('UTC')).toBe('UTC');
    // Without a pool zone, the first window's; without either, the reader's.
    const ownZones = pool({
      settings: {
        readyDefault: 0,
        windows: [{ ...pool().settings!.windows[0], zone: 'Asia/Tokyo' }],
      },
    });
    expect(poolZone(ownZones, PRAGUE)).toBe('Asia/Tokyo');
    expect(poolZone(pool({ settings: null }), PRAGUE)).toBe(PRAGUE);
    expect(poolNow(pool({ readyTarget: 1, next: null }), PRAGUE, MONDAY_MORNING)).toBe(
      'Always 1 ready.',
    );
    expect(poolNow(pool({ readyTarget: 0, next: null }), PRAGUE)).toBe(
      'No sandbox waits ready at any time.',
    );
    // A new pool host keeps none until an admin opts in (ADR-0099).
    expect(poolNow(pool({ settings: null, next: null }), PRAGUE)).toBe(
      'No sandbox waits ready: each run starts its own.',
    );
  });

  it('says when the host’s operator caps the count or turned ready sandboxes off', () => {
    expect(poolCeilingNote(pool())).toBeNull();
    const six = pool({
      settings: {
        zone: PRAGUE,
        readyDefault: 1,
        windows: [{ ...pool().settings!.windows[0], ready: 6 }],
      },
    });
    expect(poolCeilingNote(six)).toBe(
      'Applies up to 4 on this host, its operator’s limit (--pool-max-ready).',
    );
    expect(poolCeilingNote(pool({ maxReadyCeiling: 0 }))).toBe(
      'Ready sandboxes are off on this host: its operator set --pool-max-ready 0.',
    );
  });

  it('reads the host’s report', () => {
    const status = pool().status!;
    expect(coldStart(status)).toBe('1 min 35 s');
    expect(coldStart({ ...status, coldStartP90Seconds: 45 })).toBe('45 s');
    expect(coldStart({ ...status, coldStartP90Seconds: 120 })).toBe('2 min');
    expect(coldStart({ ...status, coldStartP90Seconds: null })).toBeNull();
    expect(poolServed(status)).toBe('Last 24 hours: 7 runs took a ready sandbox, 2 started cold.');
    expect(poolServed({ ...status, servedReady: 1 })).toContain('1 run took');
  });

  it('draws the week the server computed, with the days of the reader’s zone', () => {
    const segments = poolSegments(pool().week, PRAGUE, MONDAY_MORNING);
    expect(segments.map((s) => s.label)).toEqual([
      'Now to 08:00: none ready',
      '08:00 to 18:00: 2 ready',
      'From 18:00: none ready',
    ]);
    expect(segments.map((s) => s.count)).toEqual([0, 2, 0]);
    expect(segments.reduce((sum, s) => sum + s.share, 0)).toBeCloseTo(1);
    expect(segments[1].share).toBeCloseTo(10 / 168);

    // Midnights in Prague, the last one after the change back on Sunday 25 October.
    const ticks = poolDayTicks(pool().week, PRAGUE);
    expect(ticks.map((t) => t.label)).toEqual(['Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun', 'Mon']);
    expect(ticks[0].share).toBeCloseTo(17 / 168);
    expect(ticks[6].share).toBeCloseTo(162 / 168);
  });

  it('starts a new schedule from working days 08:00 to 18:00, in the reader’s zone', () => {
    const draft = poolDraft(null, PRAGUE);
    expect(draft).toEqual({
      zone: PRAGUE,
      readyDefault: 0,
      windows: [
        {
          days: ['MON', 'TUE', 'WED', 'THU', 'FRI'],
          from: '08:00',
          to: '18:00',
          ready: 2,
          zone: null,
        },
      ],
    });
    // Days go Monday first, and a window without its own zone sends none.
    draft.windows.push({
      days: ['SUN', 'SAT'],
      from: '22:00',
      to: '06:00',
      ready: 1,
      zone: ' UTC ',
    });
    expect(poolSettings({ ...draft, zone: ` ${PRAGUE} ` })).toEqual({
      zone: PRAGUE,
      readyDefault: 0,
      windows: [
        { days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], from: '08:00', to: '18:00', ready: 2 },
        { days: ['SAT', 'SUN'], from: '22:00', to: '06:00', ready: 1, zone: 'UTC' },
      ],
    });
    expect(poolDraft(pool().settings, 'UTC').zone).toBe(PRAGUE);
  });

  it('finds what the server would refuse', () => {
    const fine = poolDraft(null, PRAGUE);
    expect(poolDraftProblems(fine, 8, knownTimeZone).size).toBe(0);
    const problems = poolDraftProblems(
      {
        zone: 'Mars/Olympus',
        readyDefault: 9,
        windows: [
          { days: [], from: '08:00', to: '08:00', ready: Number.NaN, zone: 'Nowhere' },
          { days: ['MON'], from: '24:00', to: '18:00', ready: 2, zone: null },
        ],
      },
      8,
      knownTimeZone,
    );
    expect(Object.fromEntries(problems)).toEqual({
      zone: 'Choose a time zone, e.g. Europe/Prague.',
      readyDefault: 'Choose a number from 0 to 8, the host’s slots.',
      'windows.0.days': 'Choose at least one day.',
      'windows.0.time': 'A window ends at another time than it starts.',
      'windows.0.ready': 'Choose a number from 0 to 8, the host’s slots.',
      'windows.0.zone': 'Choose a time zone, or the pool’s.',
      'windows.1.time': 'Give a start and an end, 00:00 to 23:59.',
    });
  });

  it('takes the pool size from the slots set in Tinfirm, else the machine’s', () => {
    expect(poolSize(aPoolRunnerHost())).toBe(8);
    expect(poolSize(aPoolRunnerHost({ desiredMaxConcurrentRuns: 3 }))).toBe(3);
  });

  it('offers up to 16 runs at a time, more on a machine that allows more', () => {
    expect(capacityValues(4)).toHaveLength(16);
    expect(capacityValues(200)).toHaveLength(200);
    expect(capacityValues(1200)).toHaveLength(1000);
  });
});
