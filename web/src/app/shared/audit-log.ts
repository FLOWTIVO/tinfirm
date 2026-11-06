import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight, lucideFilter, lucideHistory, lucideX } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../api/generated/api';
import type { ListCompanyAuditEvents$Params } from '../api/generated/fn/audit/list-company-audit-events';
import { listCompanyAuditEvents, listInstanceAuditEvents } from '../api/generated/functions';
import type { AuditEvent } from '../api/generated/models';
import { problemMessage } from '../api/problem';
import { pagedResource } from '../core/paged-resource';
import {
  actorView,
  type ActorView,
  clockTime,
  dayGroups,
  GENESIS_HASH,
  longTime,
  type PrincipalKind,
  targetNoun,
  targetView,
  type TargetView,
} from './audit-event-view';
import { CopyButton } from './copy-button';
import { ErrorState } from './error-state';

export type AuditScope = { kind: 'company'; companyId: string } | { kind: 'instance' };

/** The company chain's filters, as the API takes them (the instance chain has none). */
export type CompanyAuditQuery = Omit<
  ListCompanyAuditEvents$Params,
  'companyId' | 'limit' | 'cursor'
>;

/** A filter one event offers: its person, its target, whom it is about, its conversation. */
export type AuditQuickFilter = Pick<
  CompanyAuditQuery,
  'actorType' | 'actorId' | 'targetType' | 'targetId' | 'conversationId' | 'principalId'
>;

const PAGE_SIZE = 50;

/** One event as a row draws it. */
interface EventRow {
  readonly event: AuditEvent;
  readonly time: string;
  readonly actor: ActorView;
  readonly prefix: string;
  readonly name: string;
  readonly target: TargetView | null;
}

const TILES: Record<ActorView['kind'], string> = {
  person: 'bg-secondary text-secondary-foreground',
  agent: 'bg-primary text-primary-foreground',
  tinfirm: 'text-muted-foreground border',
};

const numbers = new Intl.NumberFormat('en-GB');

/**
 * Newest-first audit events of one chain in one sheet: whatever leads it
 * (the company's filters) as content, then the events under sticky day headings, the time in mono,
 * who acted as an initials tile and a name (Tinfirm's own events muted), the action in mono, the
 * target as a noun with the name the server gave it and its short id, the sequence at the right.
 * An event opens in place with its ids, hashes with Copy, "Show only" filters and its details.
 * "Load more" at the foot. On a phone each row stacks.
 */
@Component({
  selector: 'app-audit-log',
  imports: [HlmButton, HlmEmptyImports, HlmSkeleton, HlmSpinner, NgIcon, CopyButton, ErrorState],
  providers: [provideIcons({ lucideChevronRight, lucideFilter, lucideHistory, lucideX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section class="bg-card overflow-clip rounded-xl border" aria-labelledby="audit-events-title">
      <h2 id="audit-events-title" class="sr-only">Events</h2>
      <ng-content />

      @if (events.firstLoad()) {
        <div class="divide-y" [class.border-t]="filterable()" aria-busy="true">
          <span class="sr-only" role="status">Loading the events…</span>
          @for (row of skeletonRows; track row) {
            <div class="flex items-center gap-3.5 px-4 py-3">
              <hlm-skeleton class="h-3 w-14 shrink-0" />
              <hlm-skeleton class="size-6 shrink-0 rounded-[6px]" />
              <hlm-skeleton class="h-3.5 w-1/4 max-w-40" />
              <hlm-skeleton class="hidden h-3.5 w-1/3 max-w-56 md:block" />
            </div>
          }
        </div>
      } @else if (events.failed()) {
        <div class="p-4" [class.border-t]="filterable()">
          <app-error-state
            [error]="events.error()"
            heading="Couldn't load the audit log"
            [messages]="{ 'status:403': 'Only owners and admins can see the audit log.' }"
            (retry)="events.retry()"
          />
        </div>
      } @else if (events.empty()) {
        <div class="p-4 pt-1" [class.pt-4]="!filterable()">
          <div hlmEmpty class="border border-dashed" data-testid="audit-empty">
            <div hlmEmptyHeader>
              <div hlmEmptyMedia variant="icon">
                <ng-icon name="lucideHistory" aria-hidden="true" />
              </div>
              @if (filtered()) {
                <div hlmEmptyTitle>No event matches</div>
                <p hlmEmptyDescription>Change the filters, or clear them to see every event.</p>
              } @else {
                <div hlmEmptyTitle>No events yet</div>
                <p hlmEmptyDescription>Changes show up here as soon as someone makes one.</p>
              }
            </div>
            @if (filtered()) {
              <div hlmEmptyContent>
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  class="max-sm:h-11"
                  (click)="clear.emit()"
                >
                  <ng-icon name="lucideX" aria-hidden="true" />
                  Clear the filters
                </button>
              </div>
            }
          </div>
        </div>
      } @else {
        <div
          class="text-muted-foreground hidden h-[34px] items-center gap-x-3.5 px-4 text-xs font-medium md:grid"
          [class]="grid"
          [class.border-t]="filterable()"
          aria-hidden="true"
        >
          <span></span>
          <span>Time</span>
          <span>Actor</span>
          <span>Action</span>
          <span>Target</span>
          <span class="text-end">Seq</span>
        </div>
        @for (group of groups(); track group.key; let first = $first, last = $last) {
          <h3
            class="bg-muted sticky top-14 z-[1] flex items-baseline gap-2 border-y px-4 py-[7px] text-[0.8125rem] leading-5 font-semibold"
            [class.max-md:border-t-0]="first && !filterable()"
          >
            {{ group.heading.label }}
            <span class="text-muted-foreground font-normal">{{ group.heading.date }}</span>
            <span class="text-faint ms-auto text-xs font-normal"
              ><span class="font-mono"
                >{{ group.events.length }}{{ last && events.hasMore() ? '+' : '' }}</span
              >
              {{
                group.events.length === 1 && !(last && events.hasMore()) ? 'event' : 'events'
              }}</span
            >
          </h3>
          <ul class="divide-y" [attr.aria-label]="'Events on ' + group.heading.long">
            @for (row of group.events; track row.event.id) {
              @let event = row.event;
              @let open = isExpanded(event.id);
              <li data-testid="audit-event" [attr.data-state]="open ? 'open' : 'closed'">
                <button
                  type="button"
                  class="hover:bg-accent/60 focus-visible:ring-ring/50 aria-expanded:bg-muted grid min-h-11 w-full items-center gap-x-3.5 gap-y-[3px] px-4 py-2.5 text-start text-sm outline-none focus-visible:ring-3 focus-visible:ring-inset md:py-1.5"
                  [class]="grid"
                  [attr.aria-expanded]="open"
                  [attr.aria-controls]="'audit-details-' + event.id"
                  (click)="toggle(event.id)"
                >
                  <ng-icon
                    name="lucideChevronRight"
                    size="14"
                    class="text-faint transition-transform motion-reduce:transition-none max-md:col-start-1 max-md:row-start-1"
                    aria-hidden="true"
                    [class.rotate-90]="open"
                  />
                  <time
                    class="text-muted-foreground font-mono text-[0.78125rem] tabular-nums max-md:col-start-3 max-md:row-start-1 max-md:text-end"
                    [attr.datetime]="event.occurredAt"
                    >{{ row.time }}</time
                  >
                  <span
                    class="flex min-w-0 items-center gap-2 max-md:col-span-2 max-md:col-start-2 max-md:row-start-2"
                  >
                    <span
                      class="inline-flex size-6 shrink-0 items-center justify-center rounded-[6px] text-[0.65625rem] font-bold"
                      [class]="tile(row.actor)"
                      aria-hidden="true"
                      >{{ row.actor.initials }}</span
                    >
                    <span class="truncate" [class.text-muted-foreground]="row.actor.muted">{{
                      row.actor.name
                    }}</span>
                  </span>
                  <span
                    class="min-w-0 truncate font-mono text-[0.78125rem] max-md:col-start-2 max-md:row-start-1"
                    data-testid="audit-action"
                    ><span class="text-muted-foreground">{{ row.prefix }}</span
                    >{{ row.name }}</span
                  >
                  <span
                    class="min-w-0 truncate text-[0.84375rem] max-md:col-span-2 max-md:col-start-2 max-md:row-start-3"
                    [class.max-md:hidden]="!row.target"
                  >
                    @if (row.target; as target) {
                      <span class="text-muted-foreground">{{ target.noun }}</span
                      >{{ target.name ? ' ' + target.name : '' }}
                      @if (target.shortId) {
                        <span class="text-faint font-mono text-[0.71875rem]">
                          · {{ target.shortId }}</span
                        >
                      }
                    } @else {
                      <span class="text-faint">—</span>
                    }
                  </span>
                  <span
                    class="text-muted-foreground text-end font-mono text-[0.78125rem] tabular-nums max-md:hidden"
                    >{{ event.seq }}</span
                  >
                </button>
                @if (open) {
                  <div
                    class="bg-muted grid gap-x-7 gap-y-5 px-4 pt-1.5 pb-5 md:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] md:ps-11"
                    [id]="'audit-details-' + event.id"
                    data-testid="audit-details"
                  >
                    <dl
                      class="grid grid-cols-1 gap-x-4 gap-y-1 text-[0.8125rem] leading-[1.55] sm:grid-cols-[7rem_minmax(0,1fr)] sm:gap-y-2"
                    >
                      <dt class="text-muted-foreground">Occurred</dt>
                      <dd class="mb-1.5 min-w-0 sm:mb-0">
                        {{ when(event) }}
                        <span class="text-faint block font-mono text-[0.71875rem] break-all">{{
                          event.occurredAt
                        }}</span>
                      </dd>
                      <dt class="text-muted-foreground">Actor</dt>
                      <dd class="mb-1.5 min-w-0 sm:mb-0">
                        {{ row.actor.name }} · {{ row.actor.noun }}
                        @if (event.actor.id) {
                          <span class="text-faint block font-mono text-[0.71875rem] break-all">{{
                            event.actor.id
                          }}</span>
                        }
                      </dd>
                      <dt class="text-muted-foreground">Target</dt>
                      <dd class="mb-1.5 min-w-0 sm:mb-0">
                        @if (row.target; as target) {
                          {{ target.noun }}
                          @if (target.name) {
                            {{ target.name }}
                          }
                          <span class="text-faint font-mono text-[0.71875rem]">{{
                            event.targetType
                          }}</span>
                          @if (target.id) {
                            <span class="text-faint block font-mono text-[0.71875rem] break-all">{{
                              target.id
                            }}</span>
                          }
                        } @else {
                          —
                        }
                      </dd>
                      <dt class="text-muted-foreground">Event</dt>
                      <dd class="mb-1.5 min-w-0 sm:mb-0">
                        <span class="font-mono">{{ event.seq }}</span>
                        <span class="text-faint block font-mono text-[0.71875rem] break-all">{{
                          event.id
                        }}</span>
                      </dd>
                      <dt class="text-muted-foreground">Hash</dt>
                      <dd class="mb-1.5 flex min-w-0 items-start gap-1 sm:mb-0">
                        <code
                          class="min-w-0 flex-1 pt-1 font-mono text-[0.71875rem] leading-[1.55] break-all"
                          data-testid="event-hash"
                          >{{ event.hash }}</code
                        >
                        <app-copy-button
                          class="max-sm:[&_button]:size-11"
                          [value]="event.hash"
                          ariaLabel="Copy the hash"
                        />
                      </dd>
                      <dt class="text-muted-foreground">Previous hash</dt>
                      <dd class="mb-1.5 min-w-0 sm:mb-0">
                        <span class="flex min-w-0 items-start gap-1">
                          <code
                            class="text-muted-foreground min-w-0 flex-1 pt-1 font-mono text-[0.71875rem] leading-[1.55] break-all"
                            >{{ event.prevHash }}</code
                          >
                          <app-copy-button
                            class="max-sm:[&_button]:size-11"
                            [value]="event.prevHash"
                            ariaLabel="Copy the previous hash"
                          />
                        </span>
                        <span class="text-faint block text-xs">
                          @if (event.prevHash === genesis) {
                            The chain’s start: no event comes before it.
                          } @else {
                            Event <span class="font-mono">{{ event.seq - 1 }}</span
                            >’s hash, which this event’s hash includes.
                          }
                        </span>
                      </dd>
                      @if (filterable() && quickFilters(event).length) {
                        <dt class="text-muted-foreground">Show only</dt>
                        <dd class="flex flex-wrap gap-1.5">
                          @for (quick of quickFilters(event); track quick.label) {
                            <button
                              hlmBtn
                              variant="outline"
                              size="xs"
                              type="button"
                              class="max-sm:h-11 max-sm:px-3.5"
                              data-testid="quick-filter"
                              (click)="filterBy.emit(quick.filter)"
                            >
                              <ng-icon name="lucideFilter" aria-hidden="true" />
                              {{ quick.label }}
                            </button>
                          }
                        </dd>
                      }
                    </dl>
                    <div class="bg-code min-w-0 self-start overflow-hidden rounded-[10px] border">
                      <div
                        class="text-muted-foreground flex h-9 items-center justify-between border-b ps-3 pe-1 text-xs font-semibold max-sm:h-11"
                      >
                        <span>Details</span>
                        @if (hasDetails(event)) {
                          <app-copy-button
                            class="max-sm:[&_button]:h-11"
                            label="Copy"
                            ariaLabel="Copy the details"
                            [value]="json(event)"
                          />
                        }
                      </div>
                      @if (hasDetails(event)) {
                        <pre
                          class="focus-visible:ring-ring/50 max-h-72 overflow-auto px-3 py-2.5 font-mono text-xs leading-[1.65] outline-none focus-visible:ring-3 focus-visible:ring-inset"
                          tabindex="0"
                          aria-label="Details"
                          data-testid="event-details"
                          >{{ json(event) }}</pre>
                      } @else {
                        <p class="text-muted-foreground px-3 py-2.5 text-xs">No details</p>
                      }
                    </div>
                  </div>
                }
              </li>
            }
          </ul>
        }

        <div
          class="text-muted-foreground flex flex-wrap items-center justify-center gap-x-3.5 gap-y-2 border-t px-4 py-3.5 text-[0.8125rem]"
        >
          @if (events.moreFailed()) {
            <p class="text-destructive" role="alert">{{ errorMessage(events.error()) }}</p>
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="max-sm:h-11"
              (click)="events.retry()"
            >
              Try again
            </button>
          } @else if (events.hasMore()) {
            <span
              ><span class="font-mono">{{ count(events.items().length) }}</span> shown, newest
              first</span
            >
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="max-sm:h-11 max-sm:w-full"
              [disabled]="events.loadingMore()"
              (click)="events.loadMore()"
            >
              @if (events.loadingMore()) {
                <hlm-spinner aria-hidden="true" />
              }
              Load more
            </button>
          } @else {
            <span data-testid="audit-end"
              >{{ count(events.items().length) }}
              {{ events.items().length === 1 ? 'event' : 'events' }} ·
              {{ filtered() ? 'every event that matches' : 'start of the chain' }}</span
            >
          }
        </div>
      }
    </section>
  `,
})
export class AuditLog {
  private readonly api = inject(Api);

  /** Which chain to show; undefined keeps the list idle (e.g. while the company id resolves). */
  readonly scope = input.required<AuditScope | undefined>();
  /** The company chain's filters; a change reads the list again from its newest event. */
  readonly query = input<CompanyAuditQuery>({});
  /** Whether filters are set, for the words of an empty list and its "Clear the filters". */
  readonly filtered = input(false);
  /** Whether filters lead the sheet, and an opened event offers "Show only" ones (`filterBy`). */
  readonly filterable = input(false);
  /** Which principals are members and which service accounts, where the page knows it. */
  readonly principalKinds = input<ReadonlyMap<string, PrincipalKind>>(new Map());
  readonly filterBy = output<AuditQuickFilter>();
  /** "Clear the filters", from an empty list. */
  readonly clear = output<void>();

  protected readonly genesis = GENESIS_HASH;
  protected readonly skeletonRows = [0, 1, 2, 3, 4, 5];

  protected readonly events = pagedResource<
    AuditEvent,
    { scope: AuditScope; query: CompanyAuditQuery }
  >({
    params: () => {
      const scope = this.scope();
      return scope ? { scope, query: this.query() } : undefined;
    },
    load: ({ scope, query }, cursor) =>
      scope.kind === 'company'
        ? this.api.invoke(listCompanyAuditEvents, {
            ...query,
            companyId: scope.companyId,
            limit: PAGE_SIZE,
            cursor,
          })
        : this.api.invoke(listInstanceAuditEvents, { limit: PAGE_SIZE, cursor }),
  });

  private readonly instance = computed(() => this.scope()?.kind === 'instance');

  /** The names the loaded events gave their targets, by id: for the page's chips. */
  readonly targetNames = computed(() => {
    const names = new Map<string, string>();
    for (const event of this.events.items()) {
      if (event.targetId && event.targetName) names.set(event.targetId, event.targetName);
    }
    return names as ReadonlyMap<string, string>;
  });

  /** The loaded events as rows, by day. */
  protected readonly groups = computed(() => {
    const kinds = this.principalKinds();
    const instance = this.instance();
    const rows: (EventRow & { occurredAt: string })[] = this.events.items().map((event) => {
      const dot = event.action.lastIndexOf('.');
      return {
        event,
        occurredAt: event.occurredAt,
        time: clockTime(event.occurredAt),
        actor: actorView(event.actor),
        prefix: dot > 0 ? event.action.slice(0, dot + 1) : '',
        name: dot > 0 ? event.action.slice(dot + 1) : event.action,
        target: targetView(event, kinds, instance),
      };
    });
    return dayGroups(rows);
  });

  /** Columns: chevron, time, actor, action, target, seq; on a phone each row stacks. */
  protected readonly grid =
    'grid-cols-[14px_minmax(0,1fr)_auto] md:grid-cols-[14px_4.6rem_minmax(0,11rem)_minmax(0,1fr)_minmax(0,1.3fr)_3.5rem]';

  private readonly expanded = signal<ReadonlySet<string>>(new Set());

  /** Re-reads the newest events, e.g. after an export, which the chain records. */
  refresh(): void {
    this.events.refresh();
  }

  protected isExpanded(id: string): boolean {
    return this.expanded().has(id);
  }

  protected toggle(id: string): void {
    this.expanded.update((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  protected tile(actor: ActorView): string {
    return TILES[actor.kind];
  }

  protected when(event: AuditEvent): string {
    return longTime(event.occurredAt);
  }

  protected count(n: number): string {
    return numbers.format(n);
  }

  protected hasDetails(event: AuditEvent): boolean {
    return !!event.details && Object.keys(event.details).length > 0;
  }

  protected json(event: AuditEvent): string {
    return JSON.stringify(event.details ?? {}, null, 2);
  }

  /**
   * The filters an event offers: its person, its target, everything about its principal (what
   * they did, what was done to them, what names them), its conversation.
   */
  protected quickFilters(event: AuditEvent): { label: string; filter: AuditQuickFilter }[] {
    const quick: { label: string; filter: AuditQuickFilter }[] = [];
    if (event.actor.type === 'USER' && event.actor.id) {
      const name = actorView(event.actor);
      quick.push({
        label: name.muted ? 'By this person' : `By ${name.name}`,
        filter: { actorType: 'USER', actorId: event.actor.id },
      });
    }
    if (event.targetType) {
      quick.push({
        label: `On this ${targetNoun(event.targetType, this.instance()).toLowerCase()}`,
        filter: { targetType: event.targetType, targetId: event.targetId ?? undefined },
      });
    }
    if (
      (event.targetType === 'principal' || event.targetType === 'principal_model_key') &&
      event.targetId
    ) {
      quick.push({
        label: event.targetName ? `About ${event.targetName}` : 'About them',
        filter: { principalId: event.targetId },
      });
    }
    const conversationId = event.details?.['conversationId'];
    if (typeof conversationId === 'string' && conversationId) {
      quick.push({ label: 'In this conversation', filter: { conversationId } });
    }
    return quick;
  }

  protected errorMessage(error: unknown): string {
    return problemMessage(error);
  }
}
