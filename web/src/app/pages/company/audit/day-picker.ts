import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  model,
  output,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronLeft, lucideChevronRight } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { addDays, addMonths, dayDate, dayKey, longDayLabel, monthLabel, monthWeeks } from './days';

const WEEKDAYS = [
  { short: 'Mo', long: 'Monday' },
  { short: 'Tu', long: 'Tuesday' },
  { short: 'We', long: 'Wednesday' },
  { short: 'Th', long: 'Thursday' },
  { short: 'Fr', long: 'Friday' },
  { short: 'Sa', long: 'Saturday' },
  { short: 'Su', long: 'Sunday' },
];

let nextId = 0;

/**
 * A small month calendar, Monday first, up to today (the audit log holds nothing later): one day,
 * or a range of days, the first pick its start and the second its end. One day of the month is in
 * the tab order; the arrows move by a day or a week, Home and End to the week's ends, Page Up and
 * Page Down by a month (the WAI-ARIA date picker's keys).
 */
@Component({
  selector: 'app-day-picker',
  imports: [NgIcon, HlmButton],
  providers: [provideIcons({ lucideChevronLeft, lucideChevronRight })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="mb-1.5 flex items-center justify-between gap-2">
      <button
        hlmBtn
        variant="ghost"
        size="icon-sm"
        type="button"
        class="text-muted-foreground max-sm:size-11"
        [attr.aria-label]="'Show ' + monthName(-1)"
        (click)="shiftMonth(-1)"
      >
        <ng-icon name="lucideChevronLeft" aria-hidden="true" />
      </button>
      <span class="text-sm font-semibold" aria-live="polite" [id]="captionId">{{
        monthName(0)
      }}</span>
      <button
        hlmBtn
        variant="ghost"
        size="icon-sm"
        type="button"
        class="text-muted-foreground max-sm:size-11"
        [attr.aria-label]="'Show ' + monthName(1)"
        [disabled]="!canGoForward()"
        (click)="shiftMonth(1)"
      >
        <ng-icon name="lucideChevronRight" aria-hidden="true" />
      </button>
    </div>
    <table
      class="w-full table-fixed border-collapse text-center"
      [attr.aria-labelledby]="captionId"
    >
      <thead>
        <tr>
          @for (day of weekdays; track day.short) {
            <th scope="col" class="text-faint h-6 p-0 text-[0.6875rem] font-normal">
              <abbr class="no-underline" [title]="day.long">{{ day.short }}</abbr>
            </th>
          }
        </tr>
      </thead>
      <tbody>
        @for (week of weeks(); track $index) {
          <tr>
            @for (key of week; track $index) {
              <td class="p-0 py-px" [class]="cellClass(key)">
                @if (key) {
                  @if (key > max()) {
                    <span
                      class="text-faint flex h-[34px] items-center justify-center text-[0.8125rem] opacity-55 max-sm:h-11"
                      aria-hidden="true"
                      >{{ dayOf(key) }}</span
                    >
                  } @else {
                    <button
                      type="button"
                      class="hover:bg-accent focus-visible:ring-ring/50 flex h-[34px] w-full items-center justify-center rounded-full text-[0.8125rem] outline-none focus-visible:ring-3 max-sm:h-11"
                      [class]="dayClass(key)"
                      [attr.data-day]="key"
                      [attr.tabindex]="key === focusKey() ? 0 : -1"
                      [attr.aria-pressed]="isEnd(key)"
                      [attr.aria-current]="key === max() ? 'date' : null"
                      [attr.aria-label]="dayLabel(key)"
                      (click)="choose(key)"
                      (keydown)="onKey($event)"
                    >
                      {{ dayOf(key) }}
                    </button>
                  }
                }
              </td>
            }
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class DayPicker {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** One day, or a range of days. */
  readonly mode = input<'single' | 'range'>('range');
  /** The last day that may be picked: today. */
  readonly max = input<string>(dayKey(new Date()));
  /** The picked day, or the range's first day. */
  readonly from = model<string | null>(null);
  /** The range's last day (the picked day again in `single` mode). */
  readonly to = model<string | null>(null);
  /** A range's first day is picked, its last not yet. */
  readonly picking = model(false);
  /** A day was picked (in `single` mode: the picker's job is done). */
  readonly picked = output<string>();

  protected readonly weekdays = WEEKDAYS;
  protected readonly captionId = `day-picker-${nextId++}`;

  /** The day in the tab order, and the month it shows. */
  protected readonly focusKey = linkedSignal(() => {
    const key = this.to() ?? this.from() ?? this.max();
    return key > this.max() ? this.max() : key;
  });
  private readonly view = computed(() => dayDate(this.focusKey()));
  protected readonly weeks = computed(() =>
    monthWeeks(this.view().getFullYear(), this.view().getMonth()),
  );
  protected readonly canGoForward = computed(() => {
    const max = dayDate(this.max());
    const view = this.view();
    return (
      view.getFullYear() < max.getFullYear() ||
      (view.getFullYear() === max.getFullYear() && view.getMonth() < max.getMonth())
    );
  });

  protected monthName(offset: number): string {
    const view = this.view();
    const date = new Date(view.getFullYear(), view.getMonth() + offset, 1);
    return monthLabel(date.getFullYear(), date.getMonth());
  }

  protected dayOf(key: string): number {
    return Number(key.slice(8));
  }

  protected isEnd(key: string): boolean {
    return key === this.from() || key === this.to();
  }

  private inRange(key: string): boolean {
    const from = this.from();
    const to = this.to();
    return this.mode() === 'range' && !!from && !!to && key > from && key < to;
  }

  protected cellClass(key: string | null): string {
    if (!key) return '';
    const from = this.from();
    const to = this.to();
    if (this.mode() !== 'range' || !from || !to || from === to) return '';
    // The tint runs between the ends, under their round fills.
    if (key === from) return 'bg-linear-to-r from-transparent from-50% to-primary/10 to-50%';
    if (key === to) return 'bg-linear-to-l from-transparent from-50% to-primary/10 to-50%';
    return this.inRange(key) ? 'bg-primary/10' : '';
  }

  protected dayClass(key: string): string {
    const today = key === this.max() ? ' underline underline-offset-[3px]' : '';
    if (this.isEnd(key)) {
      return 'bg-primary text-primary-foreground hover:bg-primary/90 font-semibold' + today;
    }
    return (this.inRange(key) ? 'rounded-none hover:rounded-full' : '') + today;
  }

  protected dayLabel(key: string): string {
    return longDayLabel(key) + (key === this.max() ? ', today' : '');
  }

  protected choose(key: string): void {
    this.focusKey.set(key);
    if (this.mode() === 'single') {
      this.from.set(key);
      this.to.set(key);
      this.picked.emit(key);
      return;
    }
    const from = this.from();
    if (!this.picking() || !from) {
      this.from.set(key);
      this.to.set(key);
      this.picking.set(true);
    } else {
      if (key < from) {
        this.from.set(key);
        this.to.set(from);
      } else {
        this.to.set(key);
      }
      this.picking.set(false);
      this.picked.emit(key);
    }
  }

  protected shiftMonth(months: number): void {
    const key = addMonths(this.focusKey(), months);
    this.focusKey.set(key > this.max() ? this.max() : key);
  }

  protected onKey(event: KeyboardEvent): void {
    const target = event.target as HTMLElement;
    const current = target.dataset?.['day'];
    if (!current) return;
    const moves: Record<string, () => string> = {
      ArrowLeft: () => addDays(current, -1),
      ArrowRight: () => addDays(current, 1),
      ArrowUp: () => addDays(current, -7),
      ArrowDown: () => addDays(current, 7),
      Home: () => addDays(current, -((dayDate(current).getDay() + 6) % 7)),
      End: () => addDays(current, 6 - ((dayDate(current).getDay() + 6) % 7)),
      PageUp: () => addMonths(current, -1),
      PageDown: () => addMonths(current, 1),
    };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    const next = move();
    const key = next > this.max() ? this.max() : next;
    this.focusKey.set(key);
    // A day of the month on screen takes the focus at once; another month's once it is drawn.
    const drawn = this.host.nativeElement.querySelector<HTMLElement>(`[data-day="${key}"]`);
    if (drawn) drawn.focus();
    else this.focusDay();
  }

  /** Moves the focus to the day in the tab order, once it is drawn. */
  focusDay(): void {
    afterNextRender(
      () => {
        this.host.nativeElement
          .querySelector<HTMLElement>(`[data-day="${this.focusKey()}"]`)
          ?.focus();
      },
      { injector: this.injector },
    );
  }
}
