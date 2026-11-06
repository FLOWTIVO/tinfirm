import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideClock, lucideTrash2 } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { RunnerCredential, RunnerHost } from '../../api/generated/models';
import {
  RUNTIME_LABELS,
  SUBSCRIPTION_KIND_LABELS,
  SUBSCRIPTION_STATUS_LABELS,
  SUBSCRIPTION_STATUS_TONES,
} from '../labels';
import { LicenceState } from '../../core/licence';
import { browserTimeZone } from '../cron';
import { EditionBadge } from '../edition-badge';
import { Time } from '../time';
import { CapacitySelect } from './capacity-select';
import {
  coldStart,
  poolCeilingNote,
  poolCounts,
  poolDayTicks,
  poolNow,
  poolSegments,
  poolServed,
  poolZone,
} from './pool-view';

/**
 * One runner host as a sheet (package 2): online or offline and since when, draining, its version
 * and machine, its slots (in use, effective, the machine's ceiling, the desired value Tinfirm sets
 * and whether the host confirmed it), for a company host that keeps ready sandboxes its pool
 * (ADR-0099: the count now and next, the week, the host's last report), and for a personal host
 * the subscriptions it reported. Its page saves a capacity, edits the pool's schedule and confirms
 * a removal; the card only asks.
 */
@Component({
  selector: 'app-runner-host-card',
  imports: [NgIcon, HlmButton, CapacitySelect, EditionBadge, Time],
  providers: [provideIcons({ lucideCircleAlert, lucideClock, lucideTrash2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class:
      'bg-card focus-visible:ring-ring/50 block rounded-xl border outline-none focus-visible:ring-3',
    tabindex: '-1',
    'data-testid': 'runner-host',
    '[id]': "'host-' + host().id",
    '[attr.data-online]': 'host().online',
  },
  template: `
    @let h = host();
    <div class="flex items-start gap-2 p-4">
      <div class="flex min-w-0 flex-1 flex-col gap-1">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span
            class="inline-block size-2.5 shrink-0 rounded-full"
            [class]="h.online ? 'bg-success' : 'bg-muted-foreground/40'"
            aria-hidden="true"
          ></span>
          <h3 class="font-mono text-sm font-semibold break-all" [id]="'host-' + h.id + '-name'">
            {{ h.name }}
          </h3>
          <span
            class="text-xs"
            data-testid="host-state"
            [class]="h.online ? 'text-success' : 'text-muted-foreground'"
          >
            @if (h.online) {
              Online
            } @else if (h.lastSeenAt) {
              Offline · last seen <app-time [value]="h.lastSeenAt" />
            } @else {
              Offline · never connected
            }
          </span>
          @if (h.draining) {
            <span
              class="border-warning/40 bg-warning/10 text-warning inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold"
              data-testid="host-draining"
              >Draining</span
            >
          }
        </div>
        @if (meta(); as m) {
          <p class="text-muted-foreground font-mono text-xs break-all">{{ m }}</p>
        }
      </div>
      @if (editable()) {
        <button
          hlmBtn
          variant="ghost"
          size="icon-sm"
          type="button"
          class="-me-1 -mt-1 shrink-0"
          data-testid="host-remove"
          [attr.aria-label]="'Remove ' + h.name"
          [title]="'Remove ' + h.name"
          (click)="remove.emit()"
        >
          <ng-icon name="lucideTrash2" aria-hidden="true" />
        </button>
      }
    </div>

    <div
      class="flex flex-col gap-3 border-t px-4 py-3 sm:flex-row sm:items-start sm:justify-between"
      data-testid="host-capacity"
    >
      <div class="flex min-w-0 flex-col gap-0.5">
        <p class="text-sm" data-testid="host-slots">
          @if (h.draining) {
            Finishing
            <span class="font-mono tabular-nums">{{ h.activeRuns }}</span>
            {{ h.activeRuns === 1 ? 'run' : 'runs' }} before it stops; it takes no new runs.
          } @else {
            <span class="font-mono tabular-nums">{{ h.activeRuns }}</span> of
            <span class="font-mono tabular-nums">{{ h.maxConcurrentRuns }}</span>
            {{ h.maxConcurrentRuns === 1 ? 'slot' : 'slots' }} in use
          }
        </p>
        <p class="text-muted-foreground text-xs">
          Up to
          <span class="font-mono tabular-nums">{{ h.maxConcurrentRunsCeiling }}</span> on this
          machine
        </p>
        @if (h.configPending) {
          <p
            class="text-muted-foreground flex items-start gap-1 text-xs"
            data-testid="host-pending"
          >
            <ng-icon name="lucideClock" class="mt-0.5 shrink-0" aria-hidden="true" />
            @if (h.online) {
              Waiting for {{ h.name }} to apply the change.
            } @else {
              {{ h.name }} applies the change when it connects.
            }
          </p>
        }
      </div>
      @if (editable()) {
        <div class="flex flex-col gap-1 sm:items-end">
          <app-capacity-select
            label="Runs at a time"
            [labelDetail]="' on ' + h.name"
            [selectId]="'host-capacity-' + h.id"
            [ceiling]="h.maxConcurrentRunsCeiling"
            [value]="desired()"
            [invalid]="!!capacityError()"
            [describedBy]="capacityError() ? 'host-capacity-error-' + h.id : null"
            (valueChange)="capacityChange.emit($event)"
          />
          @if (capacityError(); as message) {
            <p
              class="text-destructive inline-flex max-w-60 items-start gap-1 text-xs"
              role="alert"
              [id]="'host-capacity-error-' + h.id"
            >
              <ng-icon name="lucideCircleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
              {{ message }}
            </p>
          }
        </div>
      }
    </div>

    @if (h.pool; as pool) {
      <section
        class="flex flex-col gap-2 border-t px-4 py-3"
        data-testid="host-pool"
        [attr.aria-labelledby]="'host-pool-' + h.id"
      >
        <div class="flex items-start justify-between gap-3">
          <div class="flex min-w-0 flex-col gap-0.5">
            <h4 class="text-sm font-semibold" [id]="'host-pool-' + h.id">Ready sandboxes</h4>
            <p class="text-sm" data-testid="pool-now">{{ now() }}</p>
            @if (ceilingNote(); as note) {
              <p
                class="text-muted-foreground flex items-start gap-1 text-xs"
                data-testid="pool-ceiling"
              >
                <ng-icon name="lucideCircleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
                <span>{{ note }}</span>
              </p>
            }
            @if (pool.status; as status) {
              <p class="text-muted-foreground text-xs" data-testid="pool-status">
                <span class="font-mono tabular-nums">{{ counts(status) }}</span>
                · reported <app-time [value]="status.reportedAt" />
              </p>
              <p class="text-muted-foreground text-xs" data-testid="pool-served">
                {{ served(status) }}
                @if (coldStart(status); as cold) {
                  Cold starts take up to {{ cold }} (9 in 10).
                }
              </p>
            }
          </div>
          @if (editable() && !poolLicensed()) {
            <app-edition-badge feature="ELASTIC_RUNNERS" class="shrink-0" />
          } @else if (editable()) {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="shrink-0"
              data-testid="pool-edit"
              [attr.aria-label]="
                (pool.settings ? 'Edit schedule of ' : 'Set up ready sandboxes on ') + h.name
              "
              (click)="poolEdit.emit()"
            >
              {{ pool.settings ? 'Edit schedule' : 'Set up' }}
            </button>
          }
        </div>
        @if (pool.settings && segments().length > 1) {
          <div class="flex flex-col gap-0.5" aria-hidden="true" data-testid="pool-week">
            <div class="bg-muted flex h-4 overflow-hidden rounded">
              @for (s of segments(); track s.from.getTime()) {
                <div
                  class="bg-primary h-full basis-0"
                  [style.flex-grow]="s.share"
                  [style.opacity]="opacity(s.count)"
                  [title]="s.label"
                ></div>
              }
            </div>
            <div class="text-muted-foreground relative h-4 overflow-hidden text-[10px] leading-4">
              @for (t of ticks(); track t.share) {
                <span
                  class="border-border absolute top-0 border-s ps-0.5"
                  [style.left.%]="t.share * 100"
                  >{{ t.label }}</span
                >
              }
            </div>
          </div>
          <ul class="sr-only" [attr.aria-label]="'The week ahead on ' + h.name">
            @for (s of segments(); track s.from.getTime()) {
              <li>{{ s.label }}</li>
            }
          </ul>
          <p class="text-muted-foreground text-xs">The next 7 days, times in {{ zone() }}.</p>
        }
      </section>
    }

    @if (h.scope === 'PERSONAL') {
      @if (h.credentials.length) {
        <ul class="divide-y border-t" [attr.aria-label]="'Subscriptions on ' + h.name">
          @for (c of h.credentials; track c.label) {
            <li
              class="flex flex-col gap-1 px-4 py-3 sm:flex-row sm:items-center sm:gap-3"
              data-testid="host-subscription"
              [attr.data-status]="c.status"
            >
              <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                <span class="font-mono text-sm font-medium break-all">{{ c.label }}</span>
                <p class="text-muted-foreground text-xs">
                  {{ kindLabels[c.kind] }} · {{ runtimesOf(c) }}
                  @if (c.status === 'COOLDOWN' && c.cooldownUntil) {
                    · usable again <app-time [value]="c.cooldownUntil" />
                  }
                  @if (!c.reported) {
                    · not reported when the host last connected
                  }
                </p>
              </div>
              <span
                class="inline-flex h-6 items-center self-start rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap sm:self-center"
                [class]="statusTones[c.status]"
                >{{ statusLabels[c.status] }}</span
              >
            </li>
          }
        </ul>
      } @else {
        <p class="text-muted-foreground border-t px-4 py-3 text-xs text-pretty">
          No subscription reported.
          @if (h.lastSeenAt) {
            Add one on the host with
            <code class="font-mono">tinfirm-runner-host credential add claude</code>, then restart
            it.
          } @else {
            The host reports its subscriptions when it connects.
          }
        </p>
      }
    }
  `,
})
export class RunnerHostCard {
  readonly host = input.required<RunnerHost>();
  /** Whether the reader may set its capacity and remove it (its owner; an admin for a company one). */
  readonly editable = input(false);
  /** The desired value to show: a value picked and not saved yet, else the host's. */
  readonly desired = input<number | null>(null);
  readonly capacityError = input<string | null>(null);

  readonly capacityChange = output<number | null>();
  readonly poolEdit = output<void>();
  readonly remove = output<void>();

  private readonly licence = inject(LicenceState);
  /** Whether the licence holds elastic runners, whose schedule the server sets (ADR-0100). */
  protected readonly poolLicensed = computed(() => this.licence.has('ELASTIC_RUNNERS'));

  /** The reader's zone, for their own time next to the pool's. */
  private readonly readerZone = browserTimeZone();
  /** The pool's zone: its times show in it. */
  protected readonly zone = computed(() => {
    const pool = this.host().pool;
    return pool ? poolZone(pool, this.readerZone) : this.readerZone;
  });
  protected readonly counts = poolCounts;
  protected readonly served = poolServed;
  protected readonly coldStart = coldStart;
  protected readonly now = computed(() => {
    const pool = this.host().pool;
    return pool ? poolNow(pool, this.readerZone) : '';
  });
  protected readonly ceilingNote = computed(() => {
    const pool = this.host().pool;
    return pool ? poolCeilingNote(pool) : null;
  });
  protected readonly segments = computed(() =>
    poolSegments(this.host().pool?.week ?? [], this.zone()),
  );
  protected readonly ticks = computed(() =>
    poolDayTicks(this.host().pool?.week ?? [], this.zone()),
  );
  private readonly highest = computed(() => Math.max(1, ...this.segments().map((s) => s.count)));

  /** None ready shows the strip's ground; more ready, a stronger colour. */
  protected opacity(count: number): number {
    return count === 0 ? 0 : 0.35 + (0.65 * count) / this.highest();
  }

  protected readonly kindLabels = SUBSCRIPTION_KIND_LABELS;
  protected readonly statusLabels = SUBSCRIPTION_STATUS_LABELS;
  protected readonly statusTones = SUBSCRIPTION_STATUS_TONES;

  /** "v0.2.0 · darwin/arm64 · my-laptop.local". */
  protected readonly meta = computed(() => {
    const h = this.host();
    const machine = [h.os, h.arch].filter(Boolean).join('/');
    const parts = [h.version ? `v${h.version}` : null, machine || null, h.hostname ?? null];
    const text = parts.filter(Boolean).join(' · ');
    return text || null;
  });

  protected runtimesOf(credential: RunnerCredential): string {
    return credential.runtimes.map((r) => RUNTIME_LABELS[r]).join(', ');
  }
}
