import { type Cron, formatInZone, knownTimeZone, nextFirings, parseCron, tooOften } from './cron';

function cron(expression: string): Cron {
  const result = parseCron(expression);
  if ('error' in result) throw new Error(result.error);
  return result.cron;
}

function error(expression: string): string {
  const result = parseCron(expression);
  return 'error' in result ? result.error : '';
}

const iso = (dates: Date[]) => dates.map((d) => d.toISOString());

describe('cron', () => {
  it('reads numbers, ranges, steps and lists', () => {
    const parsed = cron(' 0-30/15,45 9-17/4 * * 1-5 ');
    expect(parsed.minutes).toEqual([0, 15, 30, 45]);
    expect(parsed.hours).toEqual([9, 13, 17]);
    expect([...parsed.weekdays].sort()).toEqual([1, 2, 3, 4, 5]);
    // 7 is Sunday too.
    expect([...cron('0 0 * * 7').weekdays]).toEqual([0]);
  });

  it("names the reason of a cron that can't work, in the server's words", () => {
    expect(error('')).toContain('Enter a schedule');
    expect(error('0 9 1 *')).toBe(
      'A cron has five fields, minute hour day-of-month month day-of-week; this one has 4.',
    );
    expect(error('60 * * * *')).toBe('The minute 60 is out of range; it is 0 to 59.');
    expect(error('0 9 1 JAN *')).toBe('The month field has JAN; use numbers, e.g. 1-12.');
    expect(error('0 9 * * MON')).toContain('use numbers, e.g. 1-5 for Monday to Friday');
    expect(error('30-10 * * * *')).toContain('goes backwards');
    expect(error('5/10 * * * *')).toContain('a step needs * or a range before it');
    expect(error('0,,30 * * * *')).toBe('The minute field 0,,30 has an empty entry.');
  });

  it('refuses minutes closer than 15 minutes, also across the end of an hour', () => {
    expect(tooOften(cron('*/5 * * * *'))).toContain('at minute 0 and at minute 5, 5 minutes apart');
    expect(tooOften(cron('0,50 9,10 * * *'))).toContain('at minute 50 and at minute 0');
    expect(tooOften(cron('*/15 * * * *'))).toBeNull();
    expect(tooOften(cron('0,50 9 * * *'))).toBeNull();
    expect(tooOften(cron('0 9 1 * *'))).toBeNull();
  });

  it('finds the next firings in the time zone, across summer time', () => {
    const monthly = cron('0 9 1 * *');
    expect(iso(nextFirings(monthly, 'Europe/Prague', new Date('2026-10-03T12:00:00Z'), 3))).toEqual(
      ['2026-11-01T08:00:00.000Z', '2026-12-01T08:00:00.000Z', '2027-01-01T08:00:00.000Z'],
    );
    expect(iso(nextFirings(monthly, 'Europe/Prague', new Date('2027-04-15T00:00:00Z'), 1))).toEqual(
      ['2027-05-01T07:00:00.000Z'],
    );
    // 02:30 does not exist on 28 March 2027 in Prague: it fires at 03:30.
    expect(
      iso(nextFirings(cron('30 2 * * *'), 'Europe/Prague', new Date('2027-03-27T12:00:00Z'), 2)),
    ).toEqual(['2027-03-28T01:30:00.000Z', '2027-03-29T00:30:00.000Z']);
    // 02:30 happens twice on 25 October 2026: it fires once.
    expect(
      iso(nextFirings(cron('30 2 * * *'), 'Europe/Prague', new Date('2026-10-24T12:00:00Z'), 2)),
    ).toEqual(['2026-10-25T00:30:00.000Z', '2026-10-26T01:30:00.000Z']);
    // The 13th or any Friday.
    expect(
      iso(nextFirings(cron('0 9 13 * 5'), 'UTC', new Date('2026-11-01T00:00:00Z'), 2)),
    ).toEqual(['2026-11-06T09:00:00.000Z', '2026-11-13T09:00:00.000Z']);
    expect(nextFirings(cron('0 0 30 2 *'), 'UTC', new Date('2026-01-01T00:00:00Z'), 3)).toEqual([]);
  });

  it('shows a firing as the wall-clock time of its zone', () => {
    expect(formatInZone(new Date('2026-11-01T08:00:00Z'), 'Europe/Prague')).toBe(
      'Sun 2026-11-01 09:00',
    );
    expect(formatInZone(new Date('2026-11-01T08:00:00Z'), 'UTC')).toBe('Sun 2026-11-01 08:00');
  });

  it('knows IANA time zones only', () => {
    expect(knownTimeZone('Europe/Prague')).toBe(true);
    expect(knownTimeZone('UTC')).toBe(true);
    expect(knownTimeZone('Mars/Olympus')).toBe(false);
    expect(knownTimeZone('+02:00')).toBe(false);
    expect(knownTimeZone('')).toBe(false);
  });
});
