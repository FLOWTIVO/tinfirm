import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucidePlus, lucideTrash2 } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { updateRunnerHost } from '../../api/generated/functions';
import type { RunnerHost, RunnerHostUpdateRequest } from '../../api/generated/models';
import { problemMessage, toProblem } from '../../api/problem';
import { browserTimeZone, knownTimeZone, timeZones } from '../cron';
import {
  MAX_POOL_WINDOWS,
  POOL_DAY_LABELS,
  POOL_DAYS,
  type PoolDay,
  type PoolDraft,
  poolDraft,
  poolDraftProblems,
  type PoolWindowDraft,
  poolSettings,
  poolSize,
  workingDaysWindow,
} from './pool-view';

const SAVE_ERRORS = {
  'status:403': 'Only owners and admins change a company runner host.',
  'status:404': 'This runner host no longer exists. Reload the page.',
};

/**
 * Edits the schedule of a company host's ready sandboxes (ADR-0099): a count when no window
 * applies, and weekly windows with their days, start, end and count, in the pool's time zone (the
 * reader's own by default) or a window's own. It starts from the preset "working days 08:00 to
 * 18:00: 2 ready", saves the settings, or turns ready sandboxes off. The server evaluates
 * the schedule; it checks what this dialog checks, and its reason shows when it refuses.
 */
@Component({
  selector: 'app-pool-schedule-dialog',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmDialogImports, HlmInput, HlmLabel, HlmSpinner],
  providers: [provideIcons({ lucideCircleAlert, lucidePlus, lucideTrash2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-2xl"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Ready sandboxes on {{ host()?.name }}</h2>
          <p hlmDialogDescription>
            A run that finds a sandbox ready starts at once; one that does not waits while the host
            creates its own. Each ready sandbox is a machine that costs while it waits.
          </p>
        </hlm-dialog-header>

        <form
          id="pool-schedule-form"
          class="flex flex-col gap-4"
          novalidate
          (submit)="onSave($event)"
        >
          @if (error(); as message) {
            <div hlmAlert variant="destructive" data-testid="pool-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          @let d = draft();
          <div class="grid gap-4 sm:grid-cols-2">
            <div class="flex min-w-0 flex-col gap-1.5">
              <label hlmLabel for="pool-zone">Time zone</label>
              <input
                hlmInput
                id="pool-zone"
                autocomplete="off"
                spellcheck="false"
                list="pool-zones"
                aria-describedby="pool-zone-hint"
                [attr.aria-invalid]="shown('zone') ? 'true' : null"
                [value]="d.zone"
                (input)="patch({ zone: text($event) })"
              />
              <p class="text-muted-foreground text-xs" id="pool-zone-hint">
                The windows’ times are in this zone, with its summer time.
              </p>
              @if (shown('zone'); as message) {
                <p class="text-destructive text-xs" role="alert">{{ message }}</p>
              }
            </div>
            <div class="flex min-w-0 flex-col gap-1.5">
              <label hlmLabel for="pool-default">Ready at other times</label>
              <input
                hlmInput
                id="pool-default"
                type="number"
                inputmode="numeric"
                min="0"
                [max]="size()"
                class="font-mono tabular-nums sm:w-28"
                aria-describedby="pool-default-hint"
                [attr.aria-invalid]="shown('readyDefault') ? 'true' : null"
                [value]="d.readyDefault"
                (input)="patch({ readyDefault: number($event) })"
              />
              <p class="text-muted-foreground text-xs" id="pool-default-hint">
                When no window applies. 0 to {{ size() }}, the host’s slots.
              </p>
              @if (shown('readyDefault'); as message) {
                <p class="text-destructive text-xs" role="alert">{{ message }}</p>
              }
            </div>
          </div>
          <datalist id="pool-zones">
            @for (zone of zones; track zone) {
              <option [value]="zone"></option>
            }
          </datalist>

          <div class="flex flex-col gap-2">
            <h3 class="text-sm font-semibold">Windows</h3>
            @if (d.windows.length === 0) {
              <p class="text-muted-foreground text-xs" data-testid="pool-no-windows">
                No window: the count above applies at all times.
              </p>
            }
            <ul class="m-0 flex list-none flex-col gap-3 p-0">
              @for (w of d.windows; track $index; let i = $index) {
                <li
                  class="flex flex-col gap-3 rounded-lg border p-3"
                  data-testid="pool-window"
                  [attr.aria-label]="'Window ' + (i + 1)"
                >
                  <div class="flex items-start justify-between gap-2">
                    <div
                      class="flex flex-wrap gap-1"
                      role="group"
                      [attr.aria-label]="'Days of window ' + (i + 1)"
                    >
                      @for (day of days; track day) {
                        <button
                          hlmBtn
                          size="sm"
                          type="button"
                          class="h-8 min-w-11 px-2"
                          [variant]="w.days.includes(day) ? 'default' : 'outline'"
                          [attr.aria-pressed]="w.days.includes(day)"
                          [attr.data-day]="day"
                          (click)="toggleDay(i, day)"
                        >
                          {{ dayLabels[day] }}
                        </button>
                      }
                    </div>
                    <button
                      hlmBtn
                      variant="ghost"
                      size="icon-sm"
                      type="button"
                      data-testid="pool-window-remove"
                      [attr.aria-label]="'Remove window ' + (i + 1)"
                      [title]="'Remove window ' + (i + 1)"
                      (click)="removeWindow(i)"
                    >
                      <ng-icon name="lucideTrash2" aria-hidden="true" />
                    </button>
                  </div>
                  @if (shown('windows.' + i + '.days'); as message) {
                    <p class="text-destructive -mt-2 text-xs" role="alert">{{ message }}</p>
                  }
                  <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <div class="flex min-w-0 flex-col gap-1.5">
                      <label hlmLabel class="text-xs" [for]="'pool-from-' + i">From</label>
                      <input
                        hlmInput
                        type="time"
                        class="font-mono tabular-nums"
                        [id]="'pool-from-' + i"
                        [attr.aria-invalid]="shown('windows.' + i + '.time') ? 'true' : null"
                        [value]="w.from"
                        (input)="patchWindow(i, { from: text($event) })"
                      />
                    </div>
                    <div class="flex min-w-0 flex-col gap-1.5">
                      <label hlmLabel class="text-xs" [for]="'pool-to-' + i">To</label>
                      <input
                        hlmInput
                        type="time"
                        class="font-mono tabular-nums"
                        [id]="'pool-to-' + i"
                        [attr.aria-invalid]="shown('windows.' + i + '.time') ? 'true' : null"
                        [value]="w.to"
                        (input)="patchWindow(i, { to: text($event) })"
                      />
                    </div>
                    <div class="flex min-w-0 flex-col gap-1.5">
                      <label hlmLabel class="text-xs" [for]="'pool-ready-' + i">Ready</label>
                      <input
                        hlmInput
                        type="number"
                        inputmode="numeric"
                        min="0"
                        class="font-mono tabular-nums"
                        [max]="size()"
                        [id]="'pool-ready-' + i"
                        [attr.aria-invalid]="shown('windows.' + i + '.ready') ? 'true' : null"
                        [value]="w.ready"
                        (input)="patchWindow(i, { ready: number($event) })"
                      />
                    </div>
                    <div class="flex min-w-0 flex-col gap-1.5">
                      <label hlmLabel class="text-xs" [for]="'pool-window-zone-' + i"
                        >Time zone</label
                      >
                      <input
                        hlmInput
                        autocomplete="off"
                        spellcheck="false"
                        list="pool-zones"
                        placeholder="The pool’s"
                        [id]="'pool-window-zone-' + i"
                        [attr.aria-invalid]="shown('windows.' + i + '.zone') ? 'true' : null"
                        [value]="w.zone ?? ''"
                        (input)="patchWindow(i, { zone: text($event).trim() || null })"
                      />
                    </div>
                  </div>
                  @if (shown('windows.' + i + '.time'); as message) {
                    <p class="text-destructive -mt-2 text-xs" role="alert">{{ message }}</p>
                  } @else if (w.to < w.from) {
                    <p class="text-muted-foreground -mt-2 text-xs" data-testid="pool-overnight">
                      Ends at {{ w.to }} the next day.
                    </p>
                  }
                  @if (shown('windows.' + i + '.ready'); as message) {
                    <p class="text-destructive -mt-2 text-xs" role="alert">{{ message }}</p>
                  }
                  @if (shown('windows.' + i + '.zone'); as message) {
                    <p class="text-destructive -mt-2 text-xs" role="alert">{{ message }}</p>
                  }
                </li>
              }
            </ul>
            @if (shown('windows'); as message) {
              <p class="text-destructive text-xs" role="alert">{{ message }}</p>
            }
            @if (d.windows.length < maxWindows) {
              <div class="flex flex-wrap gap-2">
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  data-testid="pool-add-window"
                  (click)="addWindow(false)"
                >
                  <ng-icon name="lucidePlus" aria-hidden="true" />
                  Add a window
                </button>
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  data-testid="pool-add-preset"
                  (click)="addWindow(true)"
                >
                  <ng-icon name="lucidePlus" aria-hidden="true" />
                  Working days 08:00 to 18:00, 2 ready
                </button>
              </div>
            }
            <p class="text-muted-foreground text-xs text-pretty">
              Where windows overlap, the higher count applies. A window may also lower the count,
              e.g. to none at night. The host keeps at most
              <span class="font-mono tabular-nums">{{ ceiling() }}</span
              >, its operator’s limit, and never more than its free slots.
            </p>
          </div>
        </form>

        <hlm-dialog-footer class="sm:justify-between">
          @if (host()?.pool?.settings) {
            <button
              hlmBtn
              variant="ghost"
              type="button"
              class="text-destructive"
              data-testid="pool-clear"
              [disabled]="busy()"
              (click)="onClear()"
            >
              Turn off ready sandboxes
            </button>
          } @else {
            <span></span>
          }
          <div class="flex flex-col-reverse gap-2 sm:flex-row">
            <button hlmBtn variant="outline" type="button" hlmDialogClose [disabled]="busy()">
              Cancel
            </button>
            <button
              hlmBtn
              type="submit"
              form="pool-schedule-form"
              data-testid="pool-save"
              [disabled]="busy()"
            >
              @if (busy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Save schedule
            </button>
          </div>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class PoolScheduleDialog {
  private readonly api = inject(Api);

  readonly open = model(false);
  readonly companyId = input.required<string>();
  readonly host = input<RunnerHost | null>(null);
  readonly saved = output<RunnerHost>();

  protected readonly days = POOL_DAYS;
  protected readonly dayLabels = POOL_DAY_LABELS;
  protected readonly maxWindows = MAX_POOL_WINDOWS;
  protected readonly zones = timeZones();

  protected readonly draft = signal<PoolDraft>(poolDraft(null, browserTimeZone()));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Problems show after the first save, then follow the edits. */
  private readonly attempted = signal(false);

  protected readonly size = computed(() => {
    const host = this.host();
    return host ? poolSize(host) : 0;
  });
  protected readonly ceiling = computed(() => this.host()?.pool?.maxReadyCeiling ?? 0);
  private readonly problems = computed(() =>
    poolDraftProblems(this.draft(), this.size(), knownTimeZone),
  );

  constructor() {
    effect(() => {
      if (!this.open()) return;
      const host = this.host();
      untracked(() => {
        this.draft.set(poolDraft(host?.pool?.settings, browserTimeZone()));
        this.attempted.set(false);
        this.error.set(null);
        this.busy.set(false);
      });
    });
  }

  protected shown(key: string): string | null {
    return this.attempted() ? (this.problems().get(key) ?? null) : null;
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected number(event: Event): number {
    const raw = (event.target as HTMLInputElement).value;
    return raw === '' ? Number.NaN : Number(raw);
  }

  protected patch(change: Partial<PoolDraft>): void {
    this.draft.update((d) => ({ ...d, ...change }));
  }

  protected patchWindow(index: number, change: Partial<PoolWindowDraft>): void {
    this.draft.update((d) => ({
      ...d,
      windows: d.windows.map((w, i) => (i === index ? { ...w, ...change } : w)),
    }));
  }

  protected toggleDay(index: number, day: PoolDay): void {
    const days = this.draft().windows[index].days;
    this.patchWindow(index, {
      days: days.includes(day) ? days.filter((d) => d !== day) : [...days, day],
    });
  }

  protected addWindow(preset: boolean): void {
    const window: PoolWindowDraft = preset
      ? { ...workingDaysWindow(), zone: null }
      : { days: [], from: '09:00', to: '17:00', ready: 1, zone: null };
    this.draft.update((d) => ({ ...d, windows: [...d.windows, window] }));
  }

  protected removeWindow(index: number): void {
    this.draft.update((d) => ({ ...d, windows: d.windows.filter((_, i) => i !== index) }));
  }

  protected onSave(event: Event): void {
    event.preventDefault();
    this.attempted.set(true);
    if (this.problems().size > 0) return;
    void this.send({ pool: poolSettings(this.draft()) });
  }

  protected onClear(): void {
    void this.send({ clearPool: true });
  }

  private async send(body: RunnerHostUpdateRequest): Promise<void> {
    const host = this.host();
    if (!host || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const updated = await this.api.invoke(updateRunnerHost, {
        companyId: this.companyId(),
        runnerHostId: host.id,
        body,
      });
      this.saved.emit(updated);
      this.open.set(false);
    } catch (error) {
      // The server's reason names what it refused, e.g. a zone it does not know.
      const problem = toProblem(error);
      this.error.set(
        problem.code === 'VALIDATION_FAILED' && problem.detail
          ? problem.detail
          : problemMessage(error, SAVE_ERRORS),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
