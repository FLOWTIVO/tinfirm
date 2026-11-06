import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideDownload, lucideReceipt } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { Api } from '../../../api/generated/api';
import {
  getSpend$Csv,
  getSpend$Json,
  listAgents,
  listMembers,
  listServiceIdentities,
  listTriggers,
} from '../../../api/generated/functions';
import type {
  SpendDimension,
  SpendReport,
  SpendRow,
  SpendSource,
} from '../../../api/generated/models';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { managesCompany } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import {
  costPhrase,
  formatTokens,
  monthRange,
  SPEND_DIMENSION_LABELS,
  SPEND_SOURCE_LABELS,
  spendKeyLabel,
  spendRangeError,
  totalTokens,
} from '../../../shared/governance-labels';
import { formatUsd } from '../../../shared/labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';

type Bucket = 'DAY' | 'WEEK' | 'MONTH';

interface SpendQuery {
  readonly companyId: string;
  readonly from: string;
  readonly to: string;
  readonly zone: string;
  readonly groupBy: SpendDimension[];
  readonly bucket: Bucket;
  readonly payerId?: string;
  readonly actorId?: string;
  readonly agentId?: string;
  readonly triggerId?: string;
  readonly model?: string;
  readonly source?: SpendSource;
}

/** The filters as the form holds them; empty is no filter. */
export interface SpendFilters {
  payerId: string;
  actorId: string;
  agentId: string;
  triggerId: string;
  model: string;
  source: string;
}

export const NO_FILTERS: SpendFilters = {
  payerId: '',
  actorId: '',
  agentId: '',
  triggerId: '',
  model: '',
  source: '',
};

/** The filters a query sends: only those set, the model trimmed (the package 6 contract, §5.2). */
export function filterParams(f: SpendFilters): Partial<SpendQuery> {
  const params: Record<string, string> = {};
  for (const key of ['payerId', 'actorId', 'agentId', 'triggerId', 'source'] as const) {
    if (f[key]) params[key] = f[key];
  }
  if (f.model.trim()) params['model'] = f.model.trim();
  return params as Partial<SpendQuery>;
}

/** How many filters are set. */
export function filterCount(f: SpendFilters): number {
  return Object.keys(filterParams(f)).length;
}

const SOURCES: readonly SpendSource[] = ['GATEWAY', 'RELAY', 'SUBSCRIPTION'];

/** The first grouping's choices: by person, agent, trigger, model, or over time. */
const FIRST: readonly SegmentedOption[] = [
  { value: 'payer', label: 'Person' },
  { value: 'agent', label: 'Agent' },
  { value: 'trigger', label: 'Trigger' },
  { value: 'model', label: 'Model' },
  { value: 'period', label: 'Time' },
];

const THEN: readonly SpendDimension[] = [
  'payer',
  'actor',
  'agent',
  'trigger',
  'conversation',
  'model',
  'period',
];

const BUCKETS: readonly { value: Bucket; label: string }[] = [
  { value: 'DAY', label: 'Days' },
  { value: 'WEEK', label: 'Weeks' },
  { value: 'MONTH', label: 'Months' },
];

function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** A text the browser saves as a file. */
function saveText(text: string, name: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * What the agents' turns cost (the package 6 contract, §5.2; CONVENTIONS §15.12): over a range of
 * days, by person, agent, trigger, model or period, and then by one more, within what the caller
 * may see (G1): a member their own and their triggers', an admin everyone's with other people's
 * personal conversations folded per person, the Owner every conversation by key. The cost is the
 * gateway's record of each turn, else Tinfirm's own count; a subscription's turns show their tokens
 * and no cost. The CSV of the same rows is audited.
 */
@Component({
  selector: 'app-spend-view',
  imports: [
    NgIcon,
    RouterLink,
    HlmButton,
    HlmEmptyImports,
    HlmFieldImports,
    HlmInput,
    HlmNativeSelectImports,
    HlmSpinner,
    HlmTableImports,
    ErrorState,
    ListSkeleton,
    Segmented,
  ],
  providers: [provideIcons({ lucideDownload, lucideReceipt })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-4">
      <form
        class="bg-card flex flex-col gap-3 rounded-xl border p-4"
        aria-label="What to show"
        (submit)="$event.preventDefault()"
      >
        <app-segmented
          inputId="spend-group"
          label="Group the spend by"
          [options]="firstOptions"
          [value]="first()"
          (valueChange)="setFirst($event)"
        />
        <div class="flex flex-wrap items-end gap-3">
          <div hlmField class="w-full sm:w-40">
            <label hlmFieldLabel for="spend-from">From</label>
            <input
              hlmInput
              id="spend-from"
              type="date"
              [value]="from()"
              (change)="from.set($any($event.target).value)"
            />
          </div>
          <div hlmField class="w-full sm:w-40">
            <label hlmFieldLabel for="spend-to">Until (not included)</label>
            <input
              hlmInput
              id="spend-to"
              type="date"
              [value]="to()"
              (change)="to.set($any($event.target).value)"
            />
          </div>
          <div hlmField class="w-full sm:w-44">
            <label hlmFieldLabel for="spend-then">Then by</label>
            <hlm-native-select
              class="w-full"
              selectId="spend-then"
              [value]="then()"
              (valueChange)="then.set($event ?? '')"
            >
              <option value="">Nothing more</option>
              @for (d of thenOptions(); track d) {
                <option [value]="d">{{ dimensionLabels[d] }}</option>
              }
            </hlm-native-select>
          </div>
          @if (usesPeriod()) {
            <div hlmField class="w-full sm:w-32">
              <label hlmFieldLabel for="spend-bucket">Per</label>
              <hlm-native-select
                class="w-full"
                selectId="spend-bucket"
                [value]="bucket()"
                (valueChange)="setBucket($event)"
              >
                @for (b of buckets; track b.value) {
                  <option [value]="b.value">{{ b.label }}</option>
                }
              </hlm-native-select>
            </div>
          }
          <button
            hlmBtn
            variant="outline"
            type="button"
            class="sm:ms-auto"
            data-testid="spend-csv"
            [disabled]="!!rangeError() || exporting()"
            (click)="exportCsv()"
          >
            @if (exporting()) {
              <hlm-spinner aria-hidden="true" />
            } @else {
              <ng-icon name="lucideDownload" aria-hidden="true" />
            }
            CSV
          </button>
        </div>
        <details
          class="group rounded-lg border px-3 py-2"
          data-testid="spend-filters"
          [open]="filterCount() > 0"
        >
          <summary class="cursor-pointer text-sm font-semibold select-none">
            Only some
            @if (filterCount()) {
              <span class="text-muted-foreground font-normal">· {{ filterCount() }} set</span>
            }
          </summary>
          <div class="mt-3 flex flex-wrap items-end gap-3">
            @if (manages()) {
              <div hlmField class="w-full sm:w-44">
                <label hlmFieldLabel for="spend-payer">Who paid</label>
                <hlm-native-select
                  class="w-full"
                  selectId="spend-payer"
                  [value]="filters().payerId"
                  (valueChange)="setFilter('payerId', $event)"
                >
                  <option value="">Anyone</option>
                  @for (p of principals(); track p.id) {
                    <option [value]="p.id">{{ p.name }}</option>
                  }
                </hlm-native-select>
              </div>
              <div hlmField class="w-full sm:w-44">
                <label hlmFieldLabel for="spend-actor">Who acted</label>
                <hlm-native-select
                  class="w-full"
                  selectId="spend-actor"
                  [value]="filters().actorId"
                  (valueChange)="setFilter('actorId', $event)"
                >
                  <option value="">Anyone</option>
                  @for (p of principals(); track p.id) {
                    <option [value]="p.id">{{ p.name }}</option>
                  }
                </hlm-native-select>
              </div>
            }
            <div hlmField class="w-full sm:w-44">
              <label hlmFieldLabel for="spend-agent">Agent</label>
              <hlm-native-select
                class="w-full"
                selectId="spend-agent"
                [value]="filters().agentId"
                (valueChange)="setFilter('agentId', $event)"
              >
                <option value="">Any agent</option>
                @for (a of agents(); track a.id) {
                  <option [value]="a.id">{{ a.name }}</option>
                }
              </hlm-native-select>
            </div>
            <div hlmField class="w-full sm:w-44">
              <label hlmFieldLabel for="spend-trigger">Trigger</label>
              <hlm-native-select
                class="w-full"
                selectId="spend-trigger"
                [value]="filters().triggerId"
                (valueChange)="setFilter('triggerId', $event)"
              >
                <option value="">Any trigger, or none</option>
                @for (t of triggers(); track t.id) {
                  <option [value]="t.id">{{ t.name }}</option>
                }
              </hlm-native-select>
            </div>
            <div hlmField class="w-full sm:w-44">
              <label hlmFieldLabel for="spend-model">Model</label>
              <input
                hlmInput
                id="spend-model"
                autocomplete="off"
                spellcheck="false"
                maxlength="200"
                placeholder="Any model"
                [value]="filters().model"
                (change)="setFilter('model', $any($event.target).value)"
              />
            </div>
            <div hlmField class="w-full sm:w-52">
              <label hlmFieldLabel for="spend-source">Counted from</label>
              <hlm-native-select
                class="w-full"
                selectId="spend-source"
                [value]="filters().source"
                (valueChange)="setFilter('source', $event)"
              >
                <option value="">Any record</option>
                @for (source of sources; track source) {
                  <option [value]="source">{{ sourceLabels[source] }}</option>
                }
              </hlm-native-select>
            </div>
            @if (filterCount()) {
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                data-testid="spend-clear-filters"
                (click)="filters.set(noFilters)"
              >
                Clear
              </button>
            }
          </div>
        </details>
        <p class="text-muted-foreground text-xs">
          Days in {{ zone }}; a turn counts on the day it ended. A CSV is recorded in the audit log.
        </p>
        @if (rangeError(); as message) {
          <p class="text-destructive text-sm" role="alert" data-testid="spend-range-error">
            {{ message }}
          </p>
        }
      </form>

      @if (report.isLoading() && !loaded()) {
        <app-list-skeleton [rows]="4" />
      } @else if (report.error()) {
        <app-error-state
          [error]="report.error()"
          heading="Couldn't load the spend"
          (retry)="report.reload()"
        />
      } @else if (loaded(); as r) {
        @if (r.rows.length === 0) {
          <div hlmEmpty class="border" data-testid="spend-empty">
            <div hlmEmptyHeader>
              <div hlmEmptyMedia variant="icon">
                <ng-icon name="lucideReceipt" aria-hidden="true" />
              </div>
              <div hlmEmptyTitle>Nothing spent in this range</div>
              <p hlmEmptyDescription>No turn you may see ended between these days.</p>
            </div>
          </div>
        } @else {
          <div hlmTableContainer class="bg-card rounded-xl border" data-testid="spend-table">
            <table hlmTable>
              <caption class="sr-only">
                Spend from
                {{
                  r.from
                }}
                until
                {{
                  r.to
                }}
              </caption>
              <thead hlmTHead>
                <tr hlmTr class="hover:bg-transparent">
                  @for (d of r.groupBy; track d) {
                    <th hlmTh>{{ dimensionLabels[d] }}</th>
                  }
                  <th hlmTh class="hidden text-end sm:table-cell">Turns</th>
                  <th hlmTh class="hidden text-end md:table-cell">Tokens</th>
                  <th hlmTh class="text-end">Cost</th>
                </tr>
              </thead>
              <tbody hlmTBody>
                @for (row of r.rows; track $index) {
                  <tr hlmTr data-testid="spend-row">
                    @for (key of row.keys; track $index) {
                      <td hlmTd class="max-w-0 whitespace-normal">
                        @if (conversationLink(key); as link) {
                          <a
                            class="font-mono text-xs underline-offset-4 hover:underline"
                            [routerLink]="link"
                            >{{ keyLabel(key) }}</a
                          >
                        } @else {
                          <span [class.text-muted-foreground]="key.folded">{{
                            keyLabel(key)
                          }}</span>
                        }
                      </td>
                    }
                    <td hlmTd class="hidden text-end tabular-nums sm:table-cell">
                      {{ row.amounts.runs }}
                    </td>
                    <td hlmTd class="hidden text-end tabular-nums md:table-cell">
                      {{ tokens(row) }}
                    </td>
                    <td hlmTd class="text-end whitespace-normal tabular-nums">
                      {{ cost(row) }}
                    </td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr hlmTr class="font-semibold hover:bg-transparent" data-testid="spend-total">
                  <td hlmTd [attr.colspan]="r.groupBy.length">Total</td>
                  <td hlmTd class="hidden text-end tabular-nums sm:table-cell">
                    {{ r.totals.runs }}
                  </td>
                  <td hlmTd class="hidden text-end tabular-nums md:table-cell">
                    {{ formatTokens(totalTokens(r.totals)) }}
                  </td>
                  <td hlmTd class="text-end tabular-nums">{{ usd(r.totals.costUsd) }}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          @if (r.totals.relayCostUsd > 0 || r.totals.subscriptionTokens > 0) {
            <p class="text-muted-foreground text-xs" data-testid="spend-sources">
              @if (r.totals.relayCostUsd > 0) {
                {{ usd(r.totals.relayCostUsd) }} of it is Tinfirm’s own count, priced at the
                gateway’s prices, where the gateway kept no record.
              }
              @if (r.totals.subscriptionTokens > 0) {
                Subscriptions’ turns show the tokens their runner reported, and no cost.
              }
            </p>
          }
        }
      }
    </div>
  `,
})
export class SpendView {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly dimensionLabels = SPEND_DIMENSION_LABELS;
  protected readonly firstOptions = FIRST;
  protected readonly buckets = BUCKETS;
  protected readonly zone = browserZone();
  protected readonly formatTokens = formatTokens;
  protected readonly totalTokens = totalTokens;
  protected readonly usd = formatUsd;
  protected readonly keyLabel = spendKeyLabel;
  protected readonly sources = SOURCES;
  protected readonly sourceLabels = SPEND_SOURCE_LABELS;
  protected readonly noFilters = NO_FILTERS;
  protected readonly manages = computed(() => managesCompany(this.current.access()));

  /** Filters beyond what the caller may see answer nothing, never 404 (G1). */
  protected readonly filters = signal<SpendFilters>(NO_FILTERS);
  protected readonly filterCount = computed(() => filterCount(this.filters()));

  protected setFilter(key: keyof SpendFilters, value: string | null | undefined): void {
    this.filters.update((f) => ({ ...f, [key]: value ?? '' }));
  }

  /** The people and service accounts to filter by: admins only, who see everyone's spend. */
  private readonly people = resource({
    params: () => (this.manages() ? this.current.id() : undefined),
    loader: async ({ params: companyId }) => {
      const [members, identities] = await Promise.all([
        loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
        loadAll((cursor) =>
          this.api.invoke(listServiceIdentities, { companyId, limit: 100, cursor }),
        ),
      ]);
      return [
        ...members.flatMap((m) =>
          m.principalId ? [{ id: m.principalId, name: m.displayName }] : [],
        ),
        ...identities.map((i) => ({ id: i.id, name: i.name })),
      ].sort((a, b) => a.name.localeCompare(b.name));
    },
  });
  protected readonly principals = computed(() =>
    this.people.hasValue() ? (this.people.value() ?? []) : [],
  );
  private readonly agentList = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listAgents, { companyId, limit: 100, cursor })),
  });
  protected readonly agents = computed(() =>
    this.agentList.hasValue() ? (this.agentList.value() ?? []) : [],
  );
  private readonly triggerList = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listTriggers, { companyId, limit: 100, cursor })),
  });
  protected readonly triggers = computed(() =>
    this.triggerList.hasValue() ? (this.triggerList.value() ?? []) : [],
  );

  private readonly month = monthRange(new Date());
  protected readonly from = signal(this.month.from);
  protected readonly to = signal(this.month.to);
  protected readonly first = signal<SpendDimension>('payer');
  protected readonly then = signal<string>('');
  protected readonly bucket = signal<Bucket>('DAY');

  protected readonly thenOptions = computed(() => THEN.filter((d) => d !== this.first()));
  protected readonly usesPeriod = computed(
    () => this.first() === 'period' || this.then() === 'period',
  );
  protected readonly rangeError = computed(() => spendRangeError(this.from(), this.to()));

  protected setFirst(value: string): void {
    const d = value as SpendDimension;
    this.first.set(d);
    if (this.then() === d) this.then.set('');
  }

  protected setBucket(value: string | null | undefined): void {
    if (value === 'DAY' || value === 'WEEK' || value === 'MONTH') this.bucket.set(value);
  }

  private readonly query = computed<SpendQuery | undefined>(
    () => {
      const companyId = this.current.id();
      if (!companyId || this.rangeError()) return undefined;
      const groupBy = [this.first(), ...(this.then() ? [this.then() as SpendDimension] : [])];
      return {
        companyId,
        from: this.from(),
        to: this.to(),
        zone: this.zone,
        groupBy,
        bucket: this.bucket(),
        ...filterParams(this.filters()),
      };
    },
    { equal: (a, b) => JSON.stringify(a) === JSON.stringify(b) },
  );

  protected readonly report = resource({
    params: () => this.query(),
    loader: ({ params }) => this.api.invoke(getSpend$Json, params),
  });
  protected readonly loaded = computed<SpendReport | null>(() =>
    this.report.hasValue() ? (this.report.value() ?? null) : null,
  );

  protected tokens(row: SpendRow): string {
    return formatTokens(totalTokens(row.amounts));
  }

  protected cost(row: SpendRow): string {
    return costPhrase(row.amounts);
  }

  /** A conversation the caller may open: its key links to it (opening it records an Owner's read). */
  protected conversationLink(key: SpendRow['keys'][number]): string[] | null {
    const companyId = this.current.id();
    if (key.dimension !== 'conversation' || key.folded || !key.id || !companyId) return null;
    return ['/c', companyId, 'conversations', key.id];
  }

  protected readonly exporting = signal(false);

  protected async exportCsv(): Promise<void> {
    const params = this.query();
    if (!params || this.exporting()) return;
    this.exporting.set(true);
    try {
      const csv = await this.api.invoke(getSpend$Csv, params);
      saveText(csv, `spend-${params.from}-${params.to}.csv`, 'text/csv');
    } catch (error) {
      this.notify.error('Couldn’t export the spend', error);
    } finally {
      this.exporting.set(false);
    }
  }
}
