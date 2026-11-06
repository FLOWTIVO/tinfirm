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
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  updateCompanyKeyModelAccess,
  updatePrincipalModelAccess,
} from '../../../api/generated/functions';
import type { ModelAccessView } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { formatDay, formatUsd } from '../../../shared/labels';
import {
  type BudgetForm,
  budgetForm,
  budgetMeter,
  budgetPhrase,
  budgetUpdate,
  modelChoices,
  modelsPhrase,
  parseUsd,
  periodPhrase,
} from './model-access-view';

/**
 * Whose budget the dialog sets: a service account (its id is its principal's), a member's
 * principal, or the company's own key (ADR-0065), which is no principal's.
 */
export interface BudgetPrincipal {
  readonly id: string;
  readonly name: string;
  /** A member, whose own values are "their own"; a service account's are "its own". */
  readonly member?: boolean;
  /** The company's own key: `updateCompanyKeyModelAccess`. */
  readonly company?: boolean;
}

/** The company's defaults, which a principal without its own values uses. */
export interface BudgetDefaults {
  readonly budgetUsd: number;
  readonly period: string;
  readonly models: readonly string[];
}

const ERRORS = {
  'status:403': 'Only owners and admins set budgets.',
  'status:404': 'This principal no longer exists. Reload the page.',
};

/**
 * Sets or clears one principal's budget and models (`updatePrincipalModelAccess`), a member's or a
 * service account's, or the company key's (`updateCompanyKeyModelAccess`): each is the company's default or its own, a choice of two cards. What is
 * spent now heads it, against the budget as chosen. A shared part of Model access that the service
 * identity page may reuse; it sends only what changed, and `clear…` to go back to the default.
 */
@Component({
  selector: 'app-principal-budget-dialog',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmDialogImports, HlmInput, HlmSpinner],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-lg"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Budget of {{ principal()?.name }}</h2>
          <p hlmDialogDescription>
            What {{ principal()?.name }} may spend through the gateway per {{ periodWords() }}, and
            on which models. The key in LiteLLM follows when you save.
          </p>
        </hlm-dialog-header>

        <form
          id="principal-budget-form"
          class="flex flex-col gap-5"
          novalidate
          (submit)="onSubmit($event)"
        >
          @if (error(); as message) {
            <div hlmAlert variant="destructive" data-testid="budget-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }

          <!-- What is spent now, against the budget as chosen -->
          @if (meter(); as m) {
            <div
              class="bg-muted flex flex-col gap-1.5 rounded-xl px-3.5 py-3"
              data-testid="spent-now"
            >
              <div class="flex justify-between gap-2 text-xs">
                <span>Spent now</span>
                <span class="font-mono tabular-nums">{{ m.amount }}</span>
              </div>
              <div
                class="bg-secondary h-1.5 overflow-hidden rounded-full"
                role="meter"
                aria-label="Share of the budget spent"
                aria-valuemin="0"
                aria-valuemax="100"
                [attr.aria-valuenow]="m.percent"
                [attr.aria-valuetext]="m.amount"
              >
                <div class="h-full rounded-full" [class]="fill()" [style.width.%]="m.percent"></div>
              </div>
              @if (resets(); as day) {
                <div class="text-muted-foreground text-xs">
                  Resets <span class="font-mono">{{ day }}</span>
                </div>
              }
            </div>
          }

          <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
            <legend class="mb-2 p-0 text-sm font-semibold">Budget</legend>
            <label [class]="option">
              <input
                type="radio"
                name="budget-own"
                class="accent-primary mt-0.5 size-4 shrink-0"
                id="budget-default"
                [checked]="!form().budgetOwn"
                (change)="patch({ budgetOwn: false })"
              />
              <span class="flex min-w-0 flex-col">
                <span class="font-semibold">The company’s default</span>
                <span class="text-muted-foreground font-mono text-[0.8125rem]">{{
                  defaultBudget()
                }}</span>
              </span>
            </label>
            <div [class]="option + ' flex-col'">
              <label class="flex min-h-6 cursor-pointer items-start gap-2.5">
                <input
                  type="radio"
                  name="budget-own"
                  class="accent-primary mt-0.5 size-4 shrink-0"
                  id="budget-own"
                  [checked]="form().budgetOwn"
                  (change)="patch({ budgetOwn: true })"
                />
                <span class="font-semibold">{{ ownWords() }}</span>
              </label>
              @if (form().budgetOwn) {
                <div class="ms-6.5 flex flex-wrap items-center gap-2">
                  <span class="text-muted-foreground" aria-hidden="true">$</span>
                  <input
                    hlmInput
                    id="budget-amount"
                    inputmode="decimal"
                    autocomplete="off"
                    class="w-28 text-right font-mono tabular-nums"
                    [attr.aria-label]="'USD per ' + periodWords()"
                    [value]="form().budget"
                    [attr.aria-invalid]="budgetInvalid() ? 'true' : null"
                    aria-describedby="budget-amount-error"
                    (input)="patch({ budget: $any($event.target).value })"
                  />
                  <span class="text-muted-foreground" aria-hidden="true"
                    >per {{ periodWords() }}</span
                  >
                  @if (budgetWas(); as was) {
                    <span class="text-muted-foreground text-xs"
                      >was <span class="font-mono tabular-nums">{{ was }}</span></span
                    >
                  }
                </div>
              }
            </div>
            <p
              id="budget-amount-error"
              class="text-destructive flex items-center gap-1.5 text-xs font-medium empty:hidden"
              role="alert"
            >
              {{
                budgetInvalid() ? 'Enter an amount from 0 to 1,000,000, such as 25 or 12.50.' : ''
              }}
            </p>
          </fieldset>

          <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
            <legend class="mb-2 p-0 text-sm font-semibold">Models</legend>
            <label [class]="option">
              <input
                type="radio"
                name="models-own"
                class="accent-primary mt-0.5 size-4 shrink-0"
                id="models-default"
                [checked]="!form().modelsOwn"
                (change)="patch({ modelsOwn: false })"
              />
              <span class="flex min-w-0 flex-col">
                <span class="font-semibold">The company’s default</span>
                <span class="text-muted-foreground text-[0.8125rem]">{{ defaultModels() }}</span>
              </span>
            </label>
            <label [class]="option">
              <input
                type="radio"
                name="models-own"
                class="accent-primary mt-0.5 size-4 shrink-0"
                id="models-own"
                [checked]="form().modelsOwn"
                [disabled]="choices().length === 0"
                (change)="patch({ modelsOwn: true })"
              />
              <span class="flex min-w-0 flex-col">
                <span class="font-semibold">Only these</span>
                @if (choices().length === 0) {
                  <span class="text-muted-foreground text-[0.8125rem]"
                    >The gateway lists its models once it passes its test.</span
                  >
                }
              </span>
            </label>
            @if (form().modelsOwn) {
              <ul class="ms-9 flex list-none flex-col gap-0.5 p-0" aria-label="Models it may use">
                @for (choice of choices(); track choice.name) {
                  <li>
                    <label class="flex min-h-11 items-center gap-2.5 text-sm sm:min-h-7">
                      <input
                        type="checkbox"
                        class="accent-primary size-4"
                        [checked]="form().models.includes(choice.name)"
                        (change)="toggleModel(choice.name, $any($event.target).checked)"
                      />
                      <span class="font-mono text-xs">{{ choice.name }}</span>
                      @if (!choice.served) {
                        <span class="text-warning text-xs">not served now</span>
                      }
                    </label>
                  </li>
                }
              </ul>
              @if (modelsInvalid()) {
                <p class="text-destructive ms-9 text-xs font-medium" role="alert">
                  Pick at least one model.
                </p>
              }
            }
          </fieldset>
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="principal-budget-form"
            data-testid="save-budget"
            [disabled]="busy()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Save
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class PrincipalBudgetDialog {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly open = model(false);
  readonly companyId = input.required<string>();
  readonly principal = input<BudgetPrincipal | null>(null);
  /** The principal's model access as last read. */
  readonly view = input<ModelAccessView | null>(null);
  readonly defaults = input.required<BudgetDefaults>();
  /** The models the gateway serves; empty while it has not passed its test. */
  readonly served = input<readonly string[]>([]);
  readonly saved = output<ModelAccessView>();

  protected readonly form = signal<BudgetForm>({
    budgetOwn: false,
    budget: '',
    modelsOwn: false,
    models: [],
  });
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  private readonly submitted = signal(false);

  protected readonly ownWords = computed(() =>
    this.principal()?.member ? 'Their own' : 'Its own',
  );
  protected readonly defaultBudget = computed(() =>
    budgetPhrase(this.defaults().budgetUsd, this.defaults().period),
  );
  protected readonly periodWords = computed(() =>
    periodPhrase(this.view()?.budgetPeriod ?? this.defaults().period),
  );
  protected readonly defaultModels = computed(() => modelsPhrase(this.defaults().models));
  protected readonly choices = computed(() => modelChoices(this.served(), this.form().models));

  /** A choice: a card with a hairline, green while picked. */
  protected readonly option =
    'flex min-h-11 cursor-pointer items-start gap-2.5 rounded-xl border px-3 py-2.5 text-sm has-checked:border-primary has-checked:bg-primary/10';

  /** The spend against the budget as chosen: the one typed, or the default. */
  protected readonly meter = computed(() => {
    const view = this.view();
    if (!view) return null;
    const form = this.form();
    const typed = form.budgetOwn ? parseUsd(form.budget) : null;
    return budgetMeter(
      view,
      typed ?? (form.budgetOwn ? view.budgetUsd : this.defaults().budgetUsd),
    );
  });
  protected readonly fill = computed(() => {
    const state = this.meter()?.state;
    return state === 'spent' ? 'bg-destructive' : state === 'near' ? 'bg-warning' : 'bg-faint';
  });
  protected readonly resets = computed(() => formatDay(this.view()?.budgetResetsAt));
  /** The own budget this one replaces, while another is typed. */
  protected readonly budgetWas = computed(() => {
    const view = this.view();
    const form = this.form();
    if (!view?.budgetOverridden || !form.budgetOwn) return null;
    return parseUsd(form.budget) === view.budgetUsd ? null : formatUsd(view.budgetUsd);
  });
  protected readonly budgetInvalid = computed(
    () => this.submitted() && this.form().budgetOwn && parseUsd(this.form().budget) === null,
  );
  protected readonly modelsInvalid = computed(
    () => this.submitted() && this.form().modelsOwn && this.form().models.length === 0,
  );

  constructor() {
    // Each opening starts from the principal's model access as read.
    effect(() => {
      const view = this.view();
      if (this.open() && view) {
        untracked(() => {
          this.form.set(budgetForm(view));
          this.error.set(null);
          this.submitted.set(false);
        });
      }
    });
  }

  protected patch(change: Partial<BudgetForm>): void {
    this.form.update((f) => ({ ...f, ...change }));
  }

  protected toggleModel(name: string, checked: boolean): void {
    this.form.update((f) => ({
      ...f,
      models: checked
        ? [...f.models.filter((m) => m !== name), name]
        : f.models.filter((m) => m !== name),
    }));
  }

  protected async onSubmit(event: Event): Promise<void> {
    event.preventDefault();
    const principal = this.principal();
    const view = this.view();
    if (!principal || !view || this.busy()) return;
    this.submitted.set(true);
    if (this.budgetInvalid() || this.modelsInvalid()) return;
    const body = budgetUpdate(view, this.form());
    if (!body) {
      this.open.set(false);
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const updated = principal.company
        ? await this.api.invoke(updateCompanyKeyModelAccess, { companyId: this.companyId(), body })
        : await this.api.invoke(updatePrincipalModelAccess, {
            companyId: this.companyId(),
            principalId: principal.id,
            body,
          });
      this.open.set(false);
      this.saved.emit(updated);
      this.notify.success(
        `Budget of ${principal.name} saved`,
        `${budgetPhrase(updated.budgetUsd, updated.budgetPeriod)}${updated.budgetOverridden ? '' : ', the default'}.`,
      );
    } catch (error) {
      this.error.set(problemMessage(error, ERRORS));
    } finally {
      this.busy.set(false);
    }
  }
}
