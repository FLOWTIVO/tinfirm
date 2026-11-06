import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideSearch } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listTriggerSubjects, runTrigger } from '../../../api/generated/functions';
import type { Trigger, TriggerFiring } from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';

/** How long the search waits after a keystroke. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The words of a refused run now (the package 4 contract, §5.6): the trigger paused (with why),
 * an issue its account cannot read, or one outside its scope or conditions (with the clauses the
 * server names).
 */
export function runNowRefusal(error: unknown): string {
  const problem = toProblem(error);
  const detail = problem.detail?.trim();
  switch (problem.code) {
    case 'TRIGGER_PAUSED':
      return detail
        ? `The trigger is paused, so it did not run: ${detail.replace(/\.$/, '')}.`
        : 'The trigger is paused, so it did not run. Resume it first.';
    case 'SUBJECT_NOT_VISIBLE':
      return 'The trigger’s account cannot read this issue, or it does not exist. Check the key, or the account’s access.';
    case 'TRIGGER_CONDITIONS_FAIL':
      return detail
        ? `This issue does not meet the trigger’s rules: ${detail.replace(/\.$/, '')}.`
        : 'This issue does not meet the trigger’s scope or conditions.';
    default:
      return problemMessage(error);
  }
}

/**
 * Run now (plan §7's W5 row; the package 4 contract, §5.6): a tracker trigger on one subject,
 * searched in its scope with its source account (`listTriggerSubjects`) or typed as a key, read
 * and checked as its first event; a schedule once now. It emits the firing.
 */
@Component({
  selector: 'app-run-now-dialog',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmDialogImports, HlmInput, HlmLabel, HlmSpinner],
  providers: [provideIcons({ lucideCircleAlert, lucideSearch })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-md"
        data-testid="run-now-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Run {{ trigger().name }} now</h2>
          <p hlmDialogDescription>
            @if (schedule()) {
              It fires once now, as if it were due. Its next firing stays where it is.
            } @else {
              It fires on one issue as its first event, with its own accounts and payer. The issue
              must be in its scope and meet its conditions.
            }
          </p>
        </hlm-dialog-header>

        <form id="run-now-form" class="flex flex-col gap-3" novalidate (submit)="run($event)">
          @if (!schedule()) {
            <div class="flex min-w-0 flex-col gap-1.5">
              <label hlmLabel for="run-now-key">Issue</label>
              <div class="relative">
                <ng-icon
                  name="lucideSearch"
                  class="text-muted-foreground pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
                  aria-hidden="true"
                />
                <input
                  hlmInput
                  id="run-now-key"
                  class="ps-9"
                  autocomplete="off"
                  spellcheck="false"
                  placeholder="SHOP-241, or words of its title"
                  [value]="query()"
                  (input)="setQuery($any($event.target).value)"
                />
              </div>
              <p class="text-muted-foreground text-xs">
                Searched with the trigger’s account, in its scope. Pick one, or type its key.
              </p>
            </div>
            @if (subjects.isLoading()) {
              <p class="text-muted-foreground text-xs" aria-live="polite">Searching…</p>
            } @else if (subjects.error()) {
              <p class="text-destructive text-xs" role="alert">{{ searchError() }}</p>
            } @else if (hits().length) {
              <ul
                class="flex max-h-60 flex-col divide-y overflow-y-auto rounded-lg border"
                aria-label="Issues found"
                data-testid="run-now-subjects"
              >
                @for (s of hits(); track s.key) {
                  <li>
                    <button
                      type="button"
                      class="hover:bg-accent focus-visible:ring-ring/50 flex w-full min-w-0 flex-col items-start gap-0.5 px-3 py-2 text-start text-sm outline-none focus-visible:ring-3 aria-pressed:bg-primary/5"
                      [attr.aria-pressed]="picked() === s.key"
                      [attr.data-subject]="s.key"
                      (click)="pick(s.key)"
                    >
                      <span class="font-mono text-xs font-semibold">{{ s.key }}</span>
                      @if (s.title) {
                        <span class="min-w-0 break-words">{{ s.title }}</span>
                      }
                      @if (s.status) {
                        <span class="text-muted-foreground text-xs">{{ s.status }}</span>
                      }
                    </button>
                  </li>
                }
              </ul>
            }
          }
          @if (error(); as message) {
            <div hlmAlert variant="destructive" data-testid="run-now-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose [disabled]="busy()">
            Cancel
          </button>
          <button
            hlmBtn
            type="submit"
            form="run-now-form"
            data-testid="confirm-run-now"
            [disabled]="busy() || (!schedule() && !subjectKey())"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Run now
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class RunNowDialog {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly open = model(false);
  readonly trigger = input.required<Trigger>();
  readonly fired = output<TriggerFiring>();

  protected readonly schedule = computed(() => this.trigger().source === 'SCHEDULE');
  protected readonly query = signal('');
  private readonly debounced = signal('');
  protected readonly picked = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected readonly subjects = resource({
    params: () => {
      const companyId = this.current.id();
      if (!companyId || !this.open() || this.schedule()) return undefined;
      return { companyId, triggerId: this.trigger().id, query: this.debounced() || undefined };
    },
    loader: ({ params }) => this.api.invoke(listTriggerSubjects, params),
  });
  protected readonly hits = computed(() =>
    this.subjects.hasValue() ? (this.subjects.value()?.items ?? []) : [],
  );
  protected readonly searchError = computed(() => problemMessage(this.subjects.error()));

  /** The subject to run on: the one picked, or the key typed. */
  protected readonly subjectKey = computed(() => this.picked() ?? (this.query().trim() || null));

  constructor() {
    effect(() => {
      if (!this.open()) {
        clearTimeout(this.timer);
        this.query.set('');
        this.debounced.set('');
        this.picked.set(null);
        this.error.set(null);
      }
    });
  }

  protected setQuery(value: string): void {
    this.query.set(value);
    this.picked.set(null);
    this.error.set(null);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.debounced.set(value.trim()), SEARCH_DEBOUNCE_MS);
  }

  protected pick(key: string): void {
    this.picked.set(key);
    this.query.set(key);
    this.error.set(null);
  }

  protected async run(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || this.busy()) return;
    const subjectKey = this.schedule() ? null : this.subjectKey();
    if (!this.schedule() && !subjectKey) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const firing = await this.api.invoke(runTrigger, {
        companyId,
        triggerId: this.trigger().id,
        body: subjectKey ? { subjectKey } : {},
      });
      this.fired.emit(firing);
      this.open.set(false);
    } catch (error) {
      this.error.set(runNowRefusal(error));
    } finally {
      this.busy.set(false);
    }
  }
}
