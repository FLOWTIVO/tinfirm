import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideChevronDown,
  lucideChevronRight,
  lucideInbox,
  lucideRefreshCw,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../../api/generated/api';
import { listWebhookDeliveries } from '../../../../api/generated/functions';
import type {
  ExternalSystem,
  WebhookDelivery,
  WebhookDeliveryCounts,
} from '../../../../api/generated/models';
import { pagedResource, sameParams } from '../../../../core/paged-resource';
import { ErrorState } from '../../../../shared/error-state';
import { plural } from '../../../../shared/labels';
import { ListSkeleton } from '../../../../shared/list-skeleton';
import { Time } from '../../../../shared/time';

const PAGE_SIZE = 25;

/** A delivery's outcome as its chip reads, and its tone: nothing waits on anyone here. */
export function deliveryStatus(delivery: Pick<WebhookDelivery, 'status' | 'firings'>): {
  label: string;
  tone: 'success' | 'neutral' | 'destructive';
} {
  switch (delivery.status) {
    case 'FIRED':
      return {
        label: delivery.firings === 1 ? 'Fired 1 trigger' : `Fired ${delivery.firings} triggers`,
        tone: 'success',
      };
    case 'MAPPING_FAILED':
      return { label: 'Mapping failed', tone: 'destructive' };
    default:
      return { label: 'No trigger matched', tone: 'neutral' };
  }
}

const TONES = {
  success: 'text-success border-success/40',
  neutral: 'text-muted-foreground bg-muted',
  destructive: 'text-destructive border-destructive/40',
} as const;

/** What the page's tab and health strip say of the deliveries read so far. */
export interface DeliveriesSummary {
  /** The last 7 days' deliveries, as the server counts them; else those read, "25+" while more wait. */
  readonly count: string;
  readonly latest: WebhookDelivery | null;
  /** The server's counts of the last 7 days, all the time deliveries are kept; null before them. */
  readonly lastSevenDays: WebhookDeliveryCounts | null;
}

/**
 * The last 7 days in a few words: "12 deliveries in 7 days, 1 not mapped, 3 refused". Deliveries are
 * kept 7 days, so the server's counts are whole; the refused ones were never kept.
 */
export function weekLine(week: WebhookDeliveryCounts): string {
  const parts = [
    week.deliveries
      ? `${plural(week.deliveries, 'delivery', 'deliveries')} in 7 days`
      : 'No delivery in 7 days',
  ];
  if (week.failed) parts.push(`${week.failed} not mapped`);
  if (week.refused) parts.push(`${week.refused} refused`);
  return parts.join(', ');
}

/**
 * A webhook system's deliveries, newest first (package 5, §3.12): each verified delivery once, kept
 * 7 days, its body for 24 hours; what the mapping read and how many triggers it fired. A refused
 * delivery is not here: the audit log counts them.
 */
@Component({
  selector: 'app-webhook-deliveries',
  imports: [NgIcon, HlmButton, HlmSpinner, ErrorState, ListSkeleton, Time],
  providers: [
    provideIcons({ lucideChevronDown, lucideChevronRight, lucideInbox, lucideRefreshCw }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="webhook-deliveries-title"
      data-testid="webhook-deliveries"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="webhook-deliveries-title" class="text-base font-bold">Deliveries</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            @if (week(); as w) {
              <span class="text-foreground font-medium" data-testid="deliveries-week">{{
                weekLine(w)
              }}</span>
              ·
            }
            The last 7 days, newest first; a body for 24 hours. Refused deliveries are not kept: the
            audit log records them.
          </p>
        </div>
        <button
          hlmBtn
          variant="ghost"
          size="sm"
          type="button"
          class="max-sm:h-11 -me-2"
          data-testid="deliveries-reload"
          (click)="deliveries.refresh()"
        >
          <ng-icon name="lucideRefreshCw" aria-hidden="true" />
          Refresh
        </button>
      </div>

      @if (deliveries.firstLoad()) {
        <div class="px-4 pb-4 sm:px-5"><app-list-skeleton [rows]="2" /></div>
      } @else if (deliveries.failed()) {
        <div class="px-4 pb-4 sm:px-5">
          <app-error-state
            [error]="deliveries.error()"
            heading="Couldn't load the deliveries"
            (retry)="deliveries.retry()"
          />
        </div>
      } @else if (deliveries.empty()) {
        <p
          class="text-muted-foreground mx-4 mb-4 flex items-center justify-center gap-2 rounded-lg border border-dashed px-3 py-4 text-sm sm:mx-5 sm:mb-5"
          data-testid="deliveries-empty"
        >
          <ng-icon name="lucideInbox" aria-hidden="true" />
          None yet. Send one from the other software to see it here.
        </p>
      } @else {
        <ul aria-labelledby="webhook-deliveries-title">
          @for (d of deliveries.items(); track d.id) {
            @let status = statusOf(d);
            <li class="flex flex-col border-t" data-testid="delivery" [attr.data-status]="d.status">
              <button
                type="button"
                class="hover:bg-muted/50 focus-visible:ring-ring/50 flex min-h-13 w-full items-start gap-2 px-4 py-2.5 text-start outline-none focus-visible:ring-3 focus-visible:ring-inset sm:px-5"
                [attr.aria-expanded]="open() === d.id"
                [attr.aria-controls]="'delivery-' + d.id"
                (click)="toggle(d.id)"
              >
                <ng-icon
                  [name]="open() === d.id ? 'lucideChevronDown' : 'lucideChevronRight'"
                  class="text-muted-foreground mt-0.5 shrink-0"
                  aria-hidden="true"
                />
                <span class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
                  <span class="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                    <span class="font-semibold break-all">{{ d.event ?? 'No event' }}</span>
                    {{ ' ' }}
                    @if (d.subjectKey) {
                      <code class="font-mono text-xs break-all">{{ d.subjectKey }}</code>
                      {{ ' ' }}
                    }
                    <span
                      class="inline-flex h-5 items-center rounded-full border px-2 text-xs font-semibold"
                      [class]="tones[status.tone]"
                      >{{ status.label }}</span
                    >
                  </span>
                  <span class="text-muted-foreground text-xs">
                    <app-time [value]="d.receivedAt" /> · {{ d.bodyBytes }} bytes
                  </span>
                </span>
              </button>
              @if (open() === d.id) {
                <div
                  class="flex flex-col gap-2 pe-4 pb-3.5 ps-10 text-xs sm:pe-5 sm:ps-11"
                  [id]="'delivery-' + d.id"
                >
                  <p class="break-all">
                    <span class="text-muted-foreground">Delivery id:</span>
                    {{ ' ' }}<code class="font-mono">{{ d.deliveryId }}</code>
                  </p>
                  @if (d.failure) {
                    <p class="text-destructive">{{ d.failure }}</p>
                  }
                  @if (d.body) {
                    <pre
                      class="bg-muted/50 max-h-72 overflow-auto rounded-lg border p-2 font-mono text-xs whitespace-pre-wrap break-all"
                      data-testid="delivery-body"
                      >{{ pretty(d.body) }}</pre>
                  } @else {
                    <p class="text-muted-foreground">Its body is gone after 24 hours.</p>
                  }
                </div>
              }
            </li>
          }
        </ul>
        @if (deliveries.hasMore()) {
          <div class="flex justify-center border-t px-4 py-3">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              class="max-sm:h-11"
              type="button"
              data-testid="deliveries-more"
              [disabled]="deliveries.loadingMore()"
              (click)="deliveries.loadMore()"
            >
              @if (deliveries.loadingMore()) {
                <hlm-spinner aria-hidden="true" />
              }
              Load more
            </button>
          </div>
        }
      }
    </section>
  `,
})
export class WebhookDeliveries {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  /** How many, and the newest, once read: the page's tab and health strip say them. */
  readonly summary = output<DeliveriesSummary>();

  protected readonly tones = TONES;
  protected readonly weekLine = weekLine;
  protected readonly open = signal<string | null>(null);
  /** The first page's counts of the last 7 days; another system's start over. */
  protected readonly week = signal<WebhookDeliveryCounts | null>(null);

  private readonly key = computed(
    () => ({ companyId: this.companyId(), systemId: this.system().id }),
    { equal: sameParams },
  );
  protected readonly deliveries = pagedResource<
    WebhookDelivery,
    { companyId: string; systemId: string }
  >({
    params: () => this.key(),
    load: async (params, cursor) => {
      const page = await this.api.invoke(listWebhookDeliveries, {
        ...params,
        limit: PAGE_SIZE,
        cursor,
      });
      // Only the first page counts the week; a refresh reads it again.
      if (!cursor) this.week.set(page.lastSevenDays ?? null);
      return page;
    },
  });

  constructor() {
    effect(() => {
      if (this.deliveries.firstLoad() || this.deliveries.failed()) return;
      const items = this.deliveries.items();
      const week = this.week();
      const summary: DeliveriesSummary = {
        count: week
          ? String(week.deliveries)
          : this.deliveries.hasMore()
            ? `${items.length}+`
            : String(items.length),
        latest: items[0] ?? null,
        lastSevenDays: week,
      };
      untracked(() => this.summary.emit(summary));
    });
  }

  protected statusOf(delivery: WebhookDelivery) {
    return deliveryStatus(delivery);
  }

  protected toggle(id: string): void {
    this.open.update((current) => (current === id ? null : id));
  }

  protected pretty(body: unknown): string {
    return JSON.stringify(body, null, 2);
  }
}
