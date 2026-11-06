/**
 * Days as the audit page picks them: `yyyy-mm-dd` keys in the reader's time zone, a month's weeks
 * from Monday, and the words of a range ("8 Oct to 14 Oct", "8–14 Oct"). Pure functions.
 */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_LONG = [
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

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** The day of a date, `yyyy-mm-dd`, in the reader's time zone. */
export function dayKey(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function dayDate(key: string): Date {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(key: string, days: number): string {
  const date = dayDate(key);
  return dayKey(new Date(date.getFullYear(), date.getMonth(), date.getDate() + days));
}

/** The same day a number of months later, or that month's last day when it has fewer. */
export function addMonths(key: string, months: number): string {
  const date = dayDate(key);
  const first = new Date(date.getFullYear(), date.getMonth() + months, 1);
  const last = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  return dayKey(new Date(first.getFullYear(), first.getMonth(), Math.min(date.getDate(), last)));
}

/** "October 2026". */
export function monthLabel(year: number, month: number): string {
  return `${MONTHS_LONG[month]} ${year}`;
}

/** "8 Oct", with the year when it is not `now`'s. */
export function shortDay(key: string, now: Date = new Date()): string {
  const date = dayDate(key);
  const day = `${date.getDate()} ${MONTHS[date.getMonth()]}`;
  return date.getFullYear() === now.getFullYear() ? day : `${day} ${date.getFullYear()}`;
}

/** "8 October 2026", for a day's button. */
export function longDayLabel(key: string): string {
  const date = dayDate(key);
  return `${date.getDate()} ${MONTHS_LONG[date.getMonth()]} ${date.getFullYear()}`;
}

/** A range of days in words: "8 Oct to 14 Oct", "8 Oct only", "From 8 Oct", "Until 14 Oct". */
export function dayRangeLabel(from?: string, to?: string, now: Date = new Date()): string {
  if (from && to) {
    return from === to
      ? `${shortDay(from, now)} only`
      : `${shortDay(from, now)} to ${shortDay(to, now)}`;
  }
  if (from) return `From ${shortDay(from, now)}`;
  if (to) return `Until ${shortDay(to, now)}`;
  return 'Any time';
}

/** A range of days on the Custom pill: "8–14 Oct", "28 Sep–3 Oct", "8 Oct". */
export function dayRangePill(from?: string, to?: string, now: Date = new Date()): string {
  if (from && to) {
    if (from === to) return shortDay(from, now);
    const a = dayDate(from);
    const b = dayDate(to);
    if (a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth()) {
      return `${a.getDate()}–${shortDay(to, now)}`;
    }
    return `${shortDay(from, now)}–${shortDay(to, now)}`;
  }
  return dayRangeLabel(from, to, now);
}

/** A month's weeks from Monday: each day's key, null before the first and after the last. */
export function monthWeeks(year: number, month: number): (string | null)[][] {
  const first = new Date(year, month, 1);
  const days = new Date(year, month + 1, 0).getDate();
  const lead = (first.getDay() + 6) % 7;
  const cells: (string | null)[] = Array.from({ length: lead }, () => null);
  for (let d = 1; d <= days; d++) cells.push(dayKey(new Date(year, month, d)));
  while (cells.length % 7) cells.push(null);
  const weeks: (string | null)[][] = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
  return weeks;
}

/** "8 Oct 2026": how a typed day field shows a day. */
export function typedDay(key: string): string {
  const date = dayDate(key);
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
}

/**
 * A typed day: "8 Oct 2026", "8 October 2026" or "2026-10-08"; null when it is none. The month's
 * name may be short or long, in any case.
 */
export function parseTypedDay(text: string): string | null {
  const value = text.trim();
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(value);
  const named = /^(\d{1,2})\.?\s+([a-z]+)\.?,?\s+(\d{4})$/i.exec(value);
  let y: number;
  let m: number;
  let d: number;
  if (iso) {
    [y, m, d] = [Number(iso[1]), Number(iso[2]) - 1, Number(iso[3])];
  } else if (named) {
    const name = named[2].toLowerCase();
    m = MONTHS_LONG.findIndex(
      (month) => month.toLowerCase() === name || month.slice(0, 3).toLowerCase() === name,
    );
    [y, d] = [Number(named[3]), Number(named[1])];
    if (m < 0) return null;
  } else {
    return null;
  }
  const date = new Date(y, m, d);
  return date.getFullYear() === y && date.getMonth() === m && date.getDate() === d
    ? dayKey(date)
    : null;
}

/** A typed time of day, "09:30" or "9:30", as minutes after midnight; null when it is none. */
export function parseTypedTime(text: string): number | null {
  const match = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(text.trim());
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
}

/** The reader's offset from UTC on a day, "UTC+02:00". */
export function utcOffset(date: Date = new Date()): string {
  const minutes = -date.getTimezoneOffset();
  const sign = minutes < 0 ? '−' : '+';
  const abs = Math.abs(minutes);
  return `UTC${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}
