import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { settle } from '../../../testing/test-helpers';
import { DayPicker } from './day-picker';
import {
  addDays,
  addMonths,
  dayRangeLabel,
  dayRangePill,
  monthWeeks,
  parseTypedDay,
  parseTypedTime,
  typedDay,
} from './days';

const NOW = new Date(2026, 9, 14, 14, 20);

describe('the audit page’s days', () => {
  it('lays a month out from Monday', () => {
    const weeks = monthWeeks(2026, 9);
    expect(weeks[0]).toEqual([
      null,
      null,
      null,
      '2026-10-01',
      '2026-10-02',
      '2026-10-03',
      '2026-10-04',
    ]);
    expect(weeks.at(-1)).toEqual([
      '2026-10-26',
      '2026-10-27',
      '2026-10-28',
      '2026-10-29',
      '2026-10-30',
      '2026-10-31',
      null,
    ]);
  });

  it('moves by days and months, to a shorter month’s last day', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(addMonths('2026-10-31', -1)).toBe('2026-09-30');
    expect(addMonths('2026-01-15', -1)).toBe('2025-12-15');
  });

  it.each<[string | undefined, string | undefined, string, string]>([
    ['2026-10-08', '2026-10-14', '8 Oct to 14 Oct', '8–14 Oct'],
    ['2026-09-28', '2026-10-03', '28 Sep to 3 Oct', '28 Sep–3 Oct'],
    ['2026-10-08', '2026-10-08', '8 Oct only', '8 Oct'],
    ['2025-12-30', '2026-01-02', '30 Dec 2025 to 2 Jan', '30 Dec 2025–2 Jan'],
    ['2026-10-08', undefined, 'From 8 Oct', 'From 8 Oct'],
    [undefined, '2026-10-14', 'Until 14 Oct', 'Until 14 Oct'],
  ])('names the days from %s to %s', (from, to, label, pill) => {
    expect(dayRangeLabel(from, to, NOW)).toBe(label);
    expect(dayRangePill(from, to, NOW)).toBe(pill);
  });

  it.each<[string, string | null]>([
    ['8 Oct 2026', '2026-10-08'],
    ['8 october 2026', '2026-10-08'],
    ['  2026-10-08 ', '2026-10-08'],
    ['31 Sep 2026', null],
    ['8 Okt 2026', null],
    ['yesterday', null],
  ])('reads the typed day “%s”', (text, key) => {
    expect(parseTypedDay(text)).toBe(key);
  });

  it('types a day as it reads one', () => {
    expect(parseTypedDay(typedDay('2026-10-08'))).toBe('2026-10-08');
  });

  it.each<[string, number | null]>([
    ['09:30', 570],
    ['9:30', 570],
    ['23:59', 1439],
    ['24:00', null],
    ['9.30', null],
  ])('reads the typed time “%s”', (text, minutes) => {
    expect(parseTypedTime(text)).toBe(minutes);
  });
});

describe('DayPicker', () => {
  let fixture: ComponentFixture<DayPicker>;
  let el: HTMLElement;

  async function open(mode: 'single' | 'range', from: string | null = null, to = from) {
    TestBed.configureTestingModule({ imports: [DayPicker] });
    fixture = TestBed.createComponent(DayPicker);
    fixture.componentRef.setInput('mode', mode);
    fixture.componentRef.setInput('max', '2026-10-14');
    fixture.componentRef.setInput('from', from);
    fixture.componentRef.setInput('to', to);
    el = fixture.nativeElement;
    await settle();
  }

  const day = (key: string) => el.querySelector<HTMLButtonElement>(`[data-day="${key}"]`);
  const pressed = () =>
    Array.from(el.querySelectorAll('[aria-pressed=true]')).map((b) => b.getAttribute('data-day'));

  it('shows the month of the days picked, up to today', async () => {
    await open('range', '2026-10-08', '2026-10-10');
    expect(el.querySelector('[aria-live]')?.textContent?.trim()).toBe('October 2026');
    expect(pressed()).toEqual(['2026-10-08', '2026-10-10']);
    expect(day('2026-10-14')?.getAttribute('aria-label')).toBe('14 October 2026, today');
    expect(day('2026-10-15')).toBeNull();
    expect(el.querySelector<HTMLButtonElement>('[aria-label="Show November 2026"]')?.disabled).toBe(
      true,
    );
  });

  it('picks a range: the first day, then the last, in either order', async () => {
    await open('range');
    day('2026-10-12')!.click();
    await settle();
    expect(fixture.componentInstance.picking()).toBe(true);
    day('2026-10-05')!.click();
    await settle();
    expect(fixture.componentInstance.from()).toBe('2026-10-05');
    expect(fixture.componentInstance.to()).toBe('2026-10-12');
    expect(fixture.componentInstance.picking()).toBe(false);
  });

  it('picks one day and says so', async () => {
    await open('single');
    const picked = vi.fn();
    fixture.componentInstance.picked.subscribe(picked);
    day('2026-10-03')!.click();
    await settle();
    expect(picked).toHaveBeenCalledWith('2026-10-03');
  });

  it('moves the day in the tab order with the arrows, never past today', async () => {
    await open('range', '2026-10-08');
    const key = (k: string) =>
      day(el.querySelector('[tabindex="0"]')!.getAttribute('data-day')!)!.dispatchEvent(
        new KeyboardEvent('keydown', { key: k, bubbles: true }),
      );
    key('ArrowDown');
    await settle();
    expect(el.querySelector('[tabindex="0"]')?.getAttribute('data-day')).toBe('2026-10-14');
    key('ArrowRight');
    await settle();
    expect(el.querySelector('[tabindex="0"]')?.getAttribute('data-day')).toBe('2026-10-14');
    key('PageUp');
    await settle();
    expect(el.querySelector('[tabindex="0"]')?.getAttribute('data-day')).toBe('2026-09-14');
    expect(el.querySelector('[aria-live]')?.textContent?.trim()).toBe('September 2026');
    key('Home');
    await settle();
    expect(el.querySelector('[tabindex="0"]')?.getAttribute('data-day')).toBe('2026-09-14');
    key('End');
    await settle();
    expect(el.querySelector('[tabindex="0"]')?.getAttribute('data-day')).toBe('2026-09-20');
  });
});
