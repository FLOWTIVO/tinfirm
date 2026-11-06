import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const DIVISIONS: { amount: number; unit: Intl.RelativeTimeFormatUnit }[] = [
  { amount: 60, unit: 'second' },
  { amount: 60, unit: 'minute' },
  { amount: 24, unit: 'hour' },
  { amount: 7, unit: 'day' },
  { amount: 4.34524, unit: 'week' },
  { amount: 12, unit: 'month' },
  { amount: Number.POSITIVE_INFINITY, unit: 'year' },
];

const relativeFormat = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
const absoluteFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export function formatRelative(date: Date, now: Date = new Date()): string {
  let duration = (date.getTime() - now.getTime()) / 1000;
  for (const division of DIVISIONS) {
    if (Math.abs(duration) < division.amount) {
      return relativeFormat.format(Math.round(duration), division.unit);
    }
    duration /= division.amount;
  }
  return absoluteFormat.format(date);
}

const clockFormat = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' });
const dayClockFormat = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

/** "14:12" today, "Sep 26, 14:12" on another day. */
export function formatClock(date: Date, now: Date = new Date()): string {
  return date.toDateString() === now.toDateString()
    ? clockFormat.format(date)
    : dayClockFormat.format(date);
}

const dayFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

/** "Oct 1, 2026": the day of a timestamp, without the time. */
export function formatDay(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : dayFormat.format(date);
}

export function formatAbsolute(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : absoluteFormat.format(date);
}

/**
 * A timestamp shown relative ("3 hours ago"), as a clock time ("14:12") or as its day ("Oct 1,
 * 2026"), with the exact time on hover and for machines.
 */
@Component({
  selector: 'app-time',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<time class="tabular-nums" [attr.datetime]="value()" [title]="absolute()">{{
    text()
  }}</time>`,
})
export class Time {
  readonly value = input.required<string>();
  readonly mode = input<'relative' | 'absolute' | 'clock' | 'day'>('relative');

  private readonly date = computed(() => new Date(this.value()));
  protected readonly absolute = computed(() => formatAbsolute(this.value()));
  protected readonly text = computed(() => {
    const date = this.date();
    if (Number.isNaN(date.getTime())) return this.value();
    switch (this.mode()) {
      case 'absolute':
        return this.absolute();
      case 'clock':
        return formatClock(date);
      case 'day':
        return dayFormat.format(date);
      default:
        return formatRelative(date);
    }
  });
}
