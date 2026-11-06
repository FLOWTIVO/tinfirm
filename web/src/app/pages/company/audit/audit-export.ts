import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCalendar,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideDownload,
  lucideFileText,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { exportCompanyAuditEvents, listCompanyAuditEvents } from '../../../api/generated/functions';
import { problemMessage, readBlobError } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { DayPicker } from './day-picker';
import { dayDate, dayKey, parseTypedDay, parseTypedTime, typedDay, utcOffset } from './days';

/** The most events one export holds (`AUDIT_EXPORT_TOO_LARGE` beyond). */
export const EXPORT_LIMIT = 1_000_000;

/** The query of an export as the dialog holds it: a range of `seq`, or of days and times. */
export interface ExportForm {
  by: 'seq' | 'time';
  fromSeq: string;
  toSeq: string;
  /** A typed day, "8 Oct 2026", and a time, "09:30", in the reader's zone. */
  fromDay: string;
  fromTime: string;
  toDay: string;
  toTime: string;
}

export interface ExportQuery {
  fromSeq?: number;
  toSeq?: number;
  from?: string;
  to?: string;
}

export type ExportPlan =
  { query: ExportQuery; summary: string; count?: number } | { error: string };

const numbers = new Intl.NumberFormat('en-GB');

/** The moment a typed day and time name, or undefined when the day is empty; null when invalid. */
function moment(day: string, time: string): Date | null | undefined {
  if (!day.trim()) return undefined;
  const key = parseTypedDay(day);
  const minutes = time.trim() ? parseTypedTime(time) : 0;
  if (!key || minutes === null) return null;
  const date = dayDate(key);
  return new Date(date.getFullYear(), date.getMonth(), date.getDate(), 0, minutes);
}

/**
 * The export's query and what it holds, or the reason it cannot be asked (CONVENTIONS §15.11): by
 * event number, both ends included and either left open (`head`, when known, is the chain's latest
 * event); by time, `from` included and `to` not. The two never mix.
 */
export function exportPlan(form: ExportForm, head?: number | null): ExportPlan {
  if (form.by === 'seq') {
    const text = [form.fromSeq.trim(), form.toSeq.trim()];
    if (text.some((t) => t && !/^[1-9]\d*$/.test(t)))
      return { error: 'Enter whole numbers from 1.' };
    const [fromSeq, toSeq] = text.map((t) => (t ? Number(t) : undefined));
    if (fromSeq !== undefined && toSeq !== undefined && fromSeq > toSeq) {
      return { error: 'The first event must come before the last.' };
    }
    const first = fromSeq ?? 1;
    const last = toSeq !== undefined && head ? Math.min(toSeq, head) : (toSeq ?? head ?? undefined);
    const count = last !== undefined ? Math.max(0, last - first + 1) : undefined;
    if (count !== undefined && count > EXPORT_LIMIT) {
      return { error: 'That is more than 1,000,000 events: narrow the range.' };
    }
    const end =
      toSeq !== undefined ? `event ${toSeq}` : head ? `the latest, ${head}` : 'the latest';
    return { query: { fromSeq, toSeq }, summary: `Event ${first} to ${end}:`, count };
  }
  const from = moment(form.fromDay, form.fromTime);
  const to = moment(form.toDay, form.toTime);
  if (from === null || to === null) {
    return { error: 'Enter a valid day and time, such as 8 Oct 2026 and 09:30.' };
  }
  if (from && to && from >= to) return { error: 'The range must end after it starts.' };
  const start = from ? `${form.fromDay.trim()}, ${form.fromTime.trim() || '00:00'}` : 'the first';
  const end = to ? `${form.toDay.trim()}, ${form.toTime.trim() || '00:00'}` : 'now';
  return {
    query: { from: from?.toISOString(), to: to?.toISOString() },
    summary: `Every event from ${start} until ${end},`,
  };
}

/**
 * The audit log's export (the package 6 contract, §5.8), behind the header's Export: a contiguous
 * range of the chain as NDJSON, oldest first, by event number or by time, with the chain's head at
 * the end, which anyone holding it verifies offline. What is typed is checked as it is typed. The
 * export itself is recorded in the log, which `exported` tells the page.
 */
@Component({
  selector: 'app-audit-export',
  imports: [
    NgIcon,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    DayPicker,
    Segmented,
  ],
  providers: [
    provideIcons({
      lucideCalendar,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideDownload,
      lucideFileText,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex' },
  template: `
    <button
      hlmBtn
      variant="outline"
      type="button"
      class="w-full max-sm:h-11"
      aria-haspopup="dialog"
      data-testid="open-export"
      (click)="openDialog()"
    >
      <ng-icon name="lucideDownload" aria-hidden="true" />
      Export
    </button>

    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[calc(100svh-2rem)] gap-0 overflow-y-auto p-0 sm:max-w-[34rem]"
        data-testid="audit-export"
      >
        <hlm-dialog-header class="px-5 pt-5 pe-12">
          <h2 hlmDialogTitle>Export the audit log</h2>
          <p hlmDialogDescription>
            A range of the chain as NDJSON, oldest first: one event a line with its hashes, then the
            chain’s head. Whoever holds the file can check it offline.
          </p>
        </hlm-dialog-header>

        <form
          id="audit-export-form"
          class="flex flex-col gap-4 px-5 pt-4.5 pb-5"
          novalidate
          (submit)="$event.preventDefault(); download()"
        >
          <app-segmented
            class="self-start"
            inputId="export-by"
            label="Export by"
            [options]="byOptions"
            [value]="form().by"
            (valueChange)="patch({ by: $event === 'time' ? 'time' : 'seq' })"
          />

          @if (form().by === 'seq') {
            <div>
              <div class="grid grid-cols-2 gap-3">
                <div hlmField>
                  <label hlmFieldLabel for="export-from-seq">From event</label>
                  <input
                    hlmInput
                    id="export-from-seq"
                    class="font-mono placeholder:font-sans max-sm:h-11 max-sm:text-base"
                    inputmode="numeric"
                    autocomplete="off"
                    placeholder="The first"
                    aria-describedby="export-seq-help export-invalid"
                    [attr.aria-invalid]="invalid() ? 'true' : null"
                    [value]="form().fromSeq"
                    (input)="patch({ fromSeq: $any($event.target).value })"
                  />
                </div>
                <div hlmField>
                  <label hlmFieldLabel for="export-to-seq">To event</label>
                  <input
                    hlmInput
                    id="export-to-seq"
                    class="font-mono placeholder:font-sans max-sm:h-11 max-sm:text-base"
                    inputmode="numeric"
                    autocomplete="off"
                    placeholder="The latest"
                    aria-describedby="export-seq-help export-invalid"
                    [attr.aria-invalid]="invalid() ? 'true' : null"
                    [value]="form().toSeq"
                    (input)="patch({ toSeq: $any($event.target).value })"
                  />
                </div>
              </div>
              <p id="export-seq-help" class="text-muted-foreground mt-1.5 text-xs">
                Both included. Leave one empty to start at the first event or end at the latest{{
                  head() ? ', ' : ''
                }}<span class="font-mono">{{ head() ?? '' }}</span
                >. Each event’s number is in its row, under Seq.
              </p>
            </div>
          } @else {
            <div>
              <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                @for (end of ends; track end.key) {
                  <div
                    hlmField
                    role="group"
                    [attr.aria-labelledby]="'export-' + end.key + '-label'"
                  >
                    <label
                      hlmFieldLabel
                      [id]="'export-' + end.key + '-label'"
                      [for]="'export-' + end.key + '-day'"
                      >{{ end.label }}</label
                    >
                    <div class="flex gap-1.5">
                      <div class="relative min-w-0 flex-1">
                        <input
                          hlmInput
                          class="pe-10 max-sm:h-11 max-sm:text-base"
                          autocomplete="off"
                          [id]="'export-' + end.key + '-day'"
                          [placeholder]="end.key === 'from' ? 'The first' : 'Now'"
                          aria-describedby="export-time-help export-invalid"
                          [attr.aria-invalid]="invalid() ? 'true' : null"
                          [value]="dayText(end.key)"
                          (input)="patchDay(end.key, $any($event.target).value)"
                        />
                        <button
                          hlmBtn
                          variant="ghost"
                          size="icon-sm"
                          type="button"
                          class="text-muted-foreground absolute end-0.5 top-0.5 max-sm:top-1.5"
                          [attr.aria-label]="end.pick"
                          [attr.aria-expanded]="calendar() === end.key"
                          [attr.aria-controls]="'export-' + end.key + '-calendar'"
                          (click)="toggleCalendar(end.key)"
                        >
                          <ng-icon name="lucideCalendar" aria-hidden="true" />
                        </button>
                      </div>
                      <input
                        hlmInput
                        class="w-19 flex-none font-mono max-sm:h-11 max-sm:text-base"
                        autocomplete="off"
                        placeholder="00:00"
                        [id]="'export-' + end.key + '-time'"
                        [attr.aria-label]="end.label + ', the time'"
                        [attr.aria-invalid]="invalid() ? 'true' : null"
                        [value]="timeText(end.key)"
                        (input)="patchTime(end.key, $any($event.target).value)"
                      />
                    </div>
                    @if (calendar() === end.key) {
                      <div
                        class="bg-card mt-1 rounded-[10px] border p-2.5"
                        [id]="'export-' + end.key + '-calendar'"
                      >
                        <app-day-picker
                          mode="single"
                          [max]="today"
                          [from]="pickedDay(end.key)"
                          [to]="pickedDay(end.key)"
                          (picked)="pickDay(end.key, $event)"
                        />
                      </div>
                    }
                  </div>
                }
              </div>
              <p id="export-time-help" class="text-muted-foreground mt-1.5 text-xs">
                Your time zone, {{ zone }}. Leave one empty to start at the first event or end now.
              </p>
            </div>
          }

          @if (done(); as file) {
            <div
              class="bg-success/10 flex items-start gap-2.5 rounded-[10px] px-3 py-2.5 text-[0.8125rem]"
              role="status"
              data-testid="export-done"
            >
              <ng-icon
                name="lucideCircleCheck"
                class="text-success mt-0.5 shrink-0"
                aria-hidden="true"
              />
              <span class="min-w-0">
                <span class="text-success block font-semibold">The download has started</span>
                <span class="block font-mono text-xs break-all">{{ file }}</span>
                <span class="text-muted-foreground block"
                  >The log now holds this export as
                  <code class="font-mono">audit.exported</code>.</span
                >
              </span>
            </div>
          } @else if (invalid(); as message) {
            <p id="export-invalid" class="text-destructive text-[0.8125rem]" role="alert">
              {{ message }}
            </p>
          } @else if (plan(); as p) {
            <div
              class="bg-muted flex items-start gap-2.5 rounded-[10px] border px-3 py-2.5 text-[0.8125rem]"
              data-testid="export-summary"
            >
              <ng-icon
                name="lucideFileText"
                class="text-muted-foreground mt-0.5 shrink-0"
                aria-hidden="true"
              />
              <span class="min-w-0">
                <span class="block"
                  >{{ p.summary }}
                  @if (p.count !== undefined) {
                    <span class="font-mono">{{ formatCount(p.count) }}</span>
                    {{ p.count === 1 ? 'event' : 'events' }},
                  }
                  oldest first.</span
                >
                <span class="text-muted-foreground block"
                  >At most <span class="font-mono">1,000,000</span> events in one file.</span
                >
              </span>
            </div>
          }
          @if (error(); as message) {
            <div
              class="bg-destructive/10 text-destructive flex items-start gap-2.5 rounded-[10px] px-3 py-2.5 text-[0.8125rem]"
              role="alert"
              data-testid="export-error"
            >
              <ng-icon name="lucideCircleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
              <span>{{ message }}</span>
            </div>
          }
        </form>

        <hlm-dialog-footer
          class="mx-0 mb-0 items-stretch gap-x-3 gap-y-2 px-5 pt-3 pb-3.5 sm:flex-wrap sm:items-center"
        >
          <p class="text-faint text-xs sm:me-auto sm:flex-[1_1_12rem]">
            The export itself is recorded in the audit log.
          </p>
          <button hlmBtn variant="outline" type="button" class="max-sm:h-11" hlmDialogClose>
            {{ done() ? 'Close' : 'Cancel' }}
          </button>
          @if (!done()) {
            <button
              hlmBtn
              type="submit"
              form="audit-export-form"
              class="max-sm:h-11"
              data-testid="export-audit"
              [disabled]="busy() || !!invalid()"
            >
              @if (busy()) {
                <hlm-spinner aria-hidden="true" />
              } @else {
                <ng-icon name="lucideDownload" aria-hidden="true" />
              }
              Export
            </button>
          }
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class AuditExport {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  /** A file was handed over: the log holds one more event. */
  readonly exported = output<void>();

  protected readonly byOptions: readonly SegmentedOption[] = [
    { value: 'seq', label: 'By event number' },
    { value: 'time', label: 'By time' },
  ];
  protected readonly ends = [
    { key: 'from', label: 'From', pick: 'Pick the first day' },
    { key: 'to', label: 'Until, not included', pick: 'Pick the last day' },
  ] as const;
  protected readonly today = dayKey(new Date());
  protected readonly zone = utcOffset();

  protected readonly open = signal(false);
  protected readonly form = signal<ExportForm>({
    by: 'seq',
    fromSeq: '',
    toSeq: '',
    fromDay: '',
    fromTime: '',
    toDay: '',
    toTime: '',
  });

  /** The chain's latest event, read when the dialog opens: the end of an open range. */
  private readonly latest = resource({
    params: () => (this.open() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      this.api.invoke(listCompanyAuditEvents, { companyId, limit: 1 }),
  });
  protected readonly head = computed(() =>
    this.latest.hasValue() ? (this.latest.value()?.items[0]?.seq ?? null) : null,
  );

  private readonly built = computed(() => exportPlan(this.form(), this.head()));
  protected readonly plan = computed(() => {
    const built = this.built();
    return 'error' in built ? null : built;
  });
  protected readonly invalid = computed(() => {
    const built = this.built();
    return 'error' in built ? built.error : null;
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The file handed over, once the download started. */
  protected readonly done = signal<string | null>(null);
  /** Which end's calendar is open. */
  protected readonly calendar = signal<'from' | 'to' | null>(null);

  protected openDialog(): void {
    this.error.set(null);
    this.done.set(null);
    this.calendar.set(null);
    this.open.set(true);
  }

  protected patch(change: Partial<ExportForm>): void {
    this.form.update((f) => ({ ...f, ...change }));
    this.error.set(null);
    this.done.set(null);
  }

  protected dayText(end: 'from' | 'to'): string {
    return end === 'from' ? this.form().fromDay : this.form().toDay;
  }

  protected timeText(end: 'from' | 'to'): string {
    return end === 'from' ? this.form().fromTime : this.form().toTime;
  }

  protected patchDay(end: 'from' | 'to', value: string): void {
    this.patch(end === 'from' ? { fromDay: value } : { toDay: value });
  }

  protected patchTime(end: 'from' | 'to', value: string): void {
    this.patch(end === 'from' ? { fromTime: value } : { toTime: value });
  }

  protected pickedDay(end: 'from' | 'to'): string | null {
    return parseTypedDay(this.dayText(end));
  }

  protected toggleCalendar(end: 'from' | 'to'): void {
    this.calendar.set(this.calendar() === end ? null : end);
  }

  protected pickDay(end: 'from' | 'to', key: string): void {
    this.patchDay(end, typedDay(key));
    if (!this.timeText(end)) this.patchTime(end, '00:00');
    this.calendar.set(null);
    document.getElementById(`export-${end}-day`)?.focus();
  }

  protected formatCount(count: number): string {
    return numbers.format(count);
  }

  protected async download(): Promise<void> {
    const companyId = this.current.id();
    const built = this.built();
    if (!companyId || 'error' in built || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const body = (await this.api.invoke(exportCompanyAuditEvents, {
        companyId,
        ...built.query,
      })) as unknown as Blob;
      const file = `audit-${companyId}.ndjson`;
      const url = URL.createObjectURL(body);
      const a = document.createElement('a');
      a.href = url;
      a.download = file;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
      this.done.set(file);
      this.exported.emit();
    } catch (failure) {
      const error = await readBlobError(failure);
      this.error.set(
        problemMessage(error, {
          'status:403': 'Only owners and admins export the audit log.',
          VALIDATION_FAILED: 'This range cannot be exported: check its ends.',
        }),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
