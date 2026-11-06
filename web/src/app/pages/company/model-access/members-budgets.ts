import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBuilding2,
  lucideIdCard,
  lucidePencil,
  lucideSearch,
  lucideSearchX,
  lucideX,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmInput } from '@spartan-ng/helm/input';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import {
  BUDGET_FILTERS,
  type BudgetFilter,
  budgetCounts,
  type BudgetRow,
  groupRows,
  kindLabel,
  type ShownRow,
} from './budget-rows';
import type { BudgetMeter } from './model-access-view';

const LABEL_TONES: Record<BudgetMeter['state'], string> = {
  ok: 'text-muted-foreground',
  near: 'text-warning',
  spent: 'text-destructive',
};

const FILLS: Record<BudgetMeter['state'], string> = {
  ok: 'bg-faint',
  near: 'bg-warning',
  spent: 'bg-destructive',
};

const NOTE_TONES: Record<BudgetMeter['noteTone'], string> = {
  faint: 'text-muted-foreground',
  muted: 'text-muted-foreground',
  warning: 'text-warning',
  destructive: 'text-destructive',
};

/**
 * Members' budgets on Model access: every member and service account in one list, near or past
 * the limit first, then those with their own budget, then those on the default. Pills filter it,
 * a search finds a name; each row shows its spend as a thin bar (neutral, warning from 80 %,
 * destructive once spent) and opens the budget dialog. The page owns the rows and the dialog.
 */
@Component({
  selector: 'app-members-budgets',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmInput,
    ErrorState,
    ListSkeleton,
    Segmented,
  ],
  providers: [
    provideIcons({
      lucideBuilding2,
      lucideIdCard,
      lucidePencil,
      lucideSearch,
      lucideSearchX,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-3', 'data-testid': 'budgets' },
  template: `
    <div class="px-1">
      <h2 id="budgets-title" class="text-base font-bold">Members’ budgets</h2>
      <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
        What every member may spend. Service accounts are listed here too, and the company key,
        which pays for Tinfirm’s own requests such as tidying agents’ memories. Near or past the
        limit comes first, then those with their own budget, then those on the default,
        <span class="font-mono text-[0.8125rem]">{{ defaultPhrase() }}</span
        >.
      </p>
    </div>

    <div class="bg-card overflow-hidden rounded-xl border">
      <div
        class="flex flex-col gap-2.5 p-2.5 lg:flex-row lg:flex-wrap lg:items-center lg:justify-between"
      >
        <app-segmented
          inputId="budget-filter"
          label="Show"
          data-testid="budget-filter"
          [scroll]="true"
          [options]="filterOptions()"
          [value]="filter()"
          (valueChange)="setFilter($event)"
        />
        <div class="relative min-w-0 lg:w-64">
          <ng-icon
            name="lucideSearch"
            class="text-muted-foreground pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
            aria-hidden="true"
          />
          <input
            hlmInput
            type="search"
            class="w-full rounded-full ps-9"
            autocomplete="off"
            placeholder="Search by name"
            aria-label="Search by name"
            data-testid="budget-search"
            [value]="query()"
            (input)="query.set($any($event.target).value)"
          />
        </div>
      </div>

      @if (loading()) {
        <div class="border-t p-4"><app-list-skeleton [rows]="3" /></div>
      } @else {
        @if (membersError(); as error) {
          <div class="border-t p-4">
            <app-error-state
              [error]="error"
              heading="Couldn't load the members’ budgets"
              (retry)="retryMembers.emit()"
            />
          </div>
        }
        @if (identitiesError(); as error) {
          <div class="border-t p-4">
            <app-error-state
              [error]="error"
              heading="Couldn't load the service accounts’ budgets"
              (retry)="retryIdentities.emit()"
            />
          </div>
        }
        @for (group of groups(); track group.id) {
          @if (group.heading) {
            <h3
              class="bg-muted/70 text-muted-foreground m-0 flex items-center gap-2 border-t px-4 py-2 text-xs font-semibold"
              [id]="'budget-group-' + group.id"
              [attr.data-group]="group.id"
            >
              {{ group.label }}
              <span
                class="bg-secondary text-foreground inline-flex h-4.5 min-w-4.5 items-center justify-center rounded-full px-1.5 font-mono text-[0.6875rem] tabular-nums"
                >{{ group.rows.length }}</span
              >
            </h3>
          }
          <ul
            class="m-0 list-none p-0"
            [attr.aria-labelledby]="group.heading ? 'budget-group-' + group.id : null"
            [attr.aria-label]="group.heading ? null : 'Budgets'"
          >
            @for (r of group.rows; track r.row.principalId) {
              @let row = r.row;
              <li
                class="grid grid-cols-[2rem_minmax(0,1fr)_auto] items-start gap-x-3 gap-y-2 border-t px-4 py-3.5 md:grid-cols-[2rem_minmax(0,1fr)_14rem_auto] md:items-center md:gap-x-4"
                [attr.data-testid]="
                  row.kind === 'member'
                    ? 'member-budget-' + row.email
                    : row.kind === 'company'
                      ? 'company-budget'
                      : 'identity-budget-' + row.name
                "
                [attr.data-state]="r.meter?.state ?? 'unread'"
              >
                @if (row.kind === 'member') {
                  <span
                    class="bg-secondary flex size-8 items-center justify-center rounded-[9px] text-xs font-bold"
                    aria-hidden="true"
                    >{{ initialsOf(row.name) }}</span
                  >
                } @else if (row.kind === 'company') {
                  <span
                    class="bg-primary/10 text-primary flex size-8 items-center justify-center rounded-[9px]"
                    aria-hidden="true"
                  >
                    <ng-icon name="lucideBuilding2" size="16" />
                  </span>
                } @else {
                  <span
                    class="bg-muted text-muted-foreground flex size-8 items-center justify-center rounded-[9px] border"
                    aria-hidden="true"
                  >
                    <ng-icon name="lucideIdCard" size="16" />
                  </span>
                }
                <div class="min-w-0">
                  <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                    @if (row.kind !== 'identity') {
                      <span class="text-[0.9375rem] font-semibold break-words">{{ row.name }}</span>
                    } @else {
                      <a
                        class="focus-visible:ring-ring/50 rounded-sm font-mono text-sm font-semibold break-all underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                        [routerLink]="['/c', companyId(), 'service-identities', row.principalId]"
                        >{{ row.name }}</a
                      >
                    }
                    @if (row.access) {
                      <span
                        class="inline-flex h-5.5 items-center rounded-full px-2 text-xs font-semibold whitespace-nowrap"
                        [class]="
                          r.own ? 'bg-secondary text-foreground' : 'text-muted-foreground border'
                        "
                        data-testid="budget"
                        >{{ r.own ? 'Own budget' : 'The default' }}</span
                      >
                    }
                    @if (row.suspended) {
                      <span
                        class="text-muted-foreground inline-flex h-5.5 items-center rounded-full border px-2 text-xs font-semibold"
                        >Suspended</span
                      >
                    }
                  </div>
                  <div class="text-muted-foreground mt-0.5 text-xs">
                    {{ kindOf(row) }}
                    @if (row.access; as a) {
                      ·
                      <span data-testid="models">{{ modelsOf(a.models) }}</span>
                    }
                  </div>
                </div>
                <div
                  class="col-start-2 row-start-2 flex min-w-0 flex-col gap-1.5 md:col-start-3 md:row-start-1"
                >
                  @if (r.meter; as m) {
                    <div class="flex justify-between gap-2 text-xs leading-normal">
                      <span class="font-mono tabular-nums" data-testid="spend">{{ m.amount }}</span>
                      <span class="font-semibold" [class]="labelTones[m.state]">{{ m.label }}</span>
                    </div>
                    <div
                      class="bg-secondary h-1.5 overflow-hidden rounded-full"
                      role="meter"
                      aria-valuemin="0"
                      aria-valuemax="100"
                      [attr.aria-label]="'Budget of ' + row.name + ' spent'"
                      [attr.aria-valuenow]="m.percent"
                      [attr.aria-valuetext]="m.amount"
                    >
                      <div
                        class="h-full rounded-full"
                        [class]="fills[m.state]"
                        [style.width.%]="m.percent"
                      ></div>
                    </div>
                    @if (m.note) {
                      <div
                        class="text-xs leading-normal text-pretty"
                        [class]="noteTones[m.noteTone]"
                        [attr.data-testid]="m.keyNote ? 'key-status' : 'budget-note'"
                      >
                        {{ m.note }}
                      </div>
                    }
                  } @else {
                    <p class="text-destructive m-0 text-xs" role="alert">{{ row.error }}</p>
                  }
                </div>
                @if (row.access) {
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    class="text-muted-foreground md:text-foreground col-start-3 row-start-1 max-md:-me-2 max-md:-mt-1.5 max-md:size-11 max-md:border-transparent max-md:bg-transparent max-md:px-0 md:col-start-4 max-md:dark:border-transparent max-md:dark:bg-transparent"
                    [attr.aria-label]="'Change budget of ' + row.name"
                    (click)="edit.emit(row)"
                  >
                    <ng-icon name="lucidePencil" aria-hidden="true" />
                    <span class="max-md:hidden">Change budget</span>
                  </button>
                }
              </li>
            }
          </ul>
        }
        @if (noMatch()) {
          <div class="border-t p-4">
            <div hlmEmpty class="border border-dashed" data-testid="budgets-filtered-empty">
              <div hlmEmptyHeader>
                <div hlmEmptyMedia variant="icon">
                  <ng-icon name="lucideSearchX" aria-hidden="true" />
                </div>
                <div hlmEmptyTitle>No budget matches</div>
                <p hlmEmptyDescription>
                  Change the filter or the search, or clear them to see everyone.
                </p>
              </div>
              <div hlmEmptyContent>
                <button hlmBtn variant="outline" size="sm" type="button" (click)="clear()">
                  <ng-icon name="lucideX" aria-hidden="true" />
                  Clear
                </button>
              </div>
            </div>
          </div>
        }
        @if (noIdentities() && filter() === 'all' && !query().trim()) {
          <p
            class="text-muted-foreground m-0 border-t px-4 py-3 text-sm"
            data-testid="no-identities"
          >
            No service accounts yet. Each one you create under
            <a
              class="text-primary underline underline-offset-2"
              [routerLink]="['/c', companyId(), 'service-identities']"
              >Service accounts</a
            >
            gets a budget here.
          </p>
        }
      }
    </div>

    <p class="text-muted-foreground m-0 px-1 text-xs text-pretty">
      What each budget spent, by person, agent, trigger and model, is under
      <a
        class="text-primary font-semibold underline underline-offset-4"
        [routerLink]="[]"
        [queryParams]="{ tab: 'spend' }"
        >Spend</a
      >. A member’s own subscription on their own runner host pays for their own work and shows no
      budget here.
    </p>
  `,
})
export class MembersBudgets {
  /** Every member with a principal and every service account, as read. */
  readonly rows = input.required<readonly ShownRow[]>();
  readonly loading = input(false);
  readonly membersError = input<unknown>(null);
  readonly identitiesError = input<unknown>(null);
  /** "$50.00 per 30 days": the budget of those on the default. */
  readonly defaultPhrase = input('');
  readonly companyId = input('');
  /** The service accounts were read, and there are none. */
  readonly noIdentities = input(false);

  readonly edit = output<BudgetRow>();
  readonly retryMembers = output<void>();
  readonly retryIdentities = output<void>();

  protected readonly labelTones = LABEL_TONES;
  protected readonly fills = FILLS;
  protected readonly noteTones = NOTE_TONES;

  protected readonly filter = signal<BudgetFilter>('all');
  protected readonly query = signal('');

  protected readonly filterOptions = computed<SegmentedOption[]>(() => {
    const counts = budgetCounts(this.rows());
    return BUDGET_FILTERS.map((f) => ({ value: f.value, label: `${f.label} ${counts[f.value]}` }));
  });
  protected readonly groups = computed(() => groupRows(this.rows(), this.filter(), this.query()));
  protected readonly noMatch = computed(() => this.rows().length > 0 && this.groups().length === 0);

  protected setFilter(value: string): void {
    const filter = BUDGET_FILTERS.find((f) => f.value === value)?.value;
    if (filter) this.filter.set(filter);
  }

  protected clear(): void {
    this.filter.set('all');
    this.query.set('');
  }

  protected kindOf(row: BudgetRow): string {
    return kindLabel(row);
  }

  protected modelsOf(models: readonly string[]): string {
    return models.length ? `${models.join(', ')} only` : 'every model';
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }
}
