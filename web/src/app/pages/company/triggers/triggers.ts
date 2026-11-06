import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCalendarClock,
  lucideClock,
  lucideMessageSquare,
  lucidePause,
  lucidePlus,
  lucideWebhook,
  lucideZap,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listTriggers } from '../../../api/generated/functions';
import type { TriggerStatus, TriggerSummary } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { pagedResource } from '../../../core/paged-resource';
import { isViewer, mayWork } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { ViewerNotice } from '../../../shared/viewer-notice';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { Time } from '../../../shared/time';
import { TriggerStateChips } from './trigger-state-chips';
import { EVENT_SHORT, OUTCOME_LABELS, sourceText } from './trigger-labels';

const PAGE_SIZE = 100;

const SHOWN: readonly SegmentedOption[] = [
  { value: '', label: 'All' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PAUSED', label: 'Paused' },
];

/**
 * The triggers (plan §3, §7's W5 row; the package 4 contract, §6): those the reader authored or
 * whose team they are in, and for OWNER and ADMIN every trigger, each with its sentence, why it is
 * paused or waits (`AUTHOR_LEFT`, `SYSTEM_PAUSED`, `COMPANY_STOPPED`, …), its author, team and
 * payer, and its last firing. Anyone who may use an agent creates one.
 */
@Component({
  selector: 'app-triggers',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
    ViewerNotice,
    Segmented,
    Time,
    TriggerStateChips,
  ],
  providers: [
    provideIcons({
      lucideCalendarClock,
      lucideClock,
      lucideMessageSquare,
      lucidePause,
      lucidePlus,
      lucideWebhook,
      lucideZap,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    <div class="flex flex-col gap-5">
      <app-page-header
        title="Triggers"
        description="Standing rules: when something happens on a system, or on a schedule, an agent does an instruction with the accounts and the budget its author chose."
      >
        @if (member()) {
          <a hlmBtn data-testid="new-trigger" [routerLink]="['/c', companyId, 'triggers', 'new']">
            <ng-icon name="lucidePlus" aria-hidden="true" />
            New trigger
          </a>
        }
      </app-page-header>

      @if (viewer()) {
        <app-viewer-notice place="triggers" />
      }

      <app-segmented
        class="sm:self-start"
        inputId="triggers-shown"
        label="Which triggers"
        [options]="shownOptions"
        [value]="statusFilter() ?? ''"
        (valueChange)="setShown($event)"
      />

      @if (triggers.firstLoad()) {
        <app-list-skeleton [rows]="3" />
      } @else if (triggers.failed()) {
        <app-error-state
          [error]="triggers.error()"
          heading="Couldn't load triggers"
          (retry)="triggers.retry()"
        />
      } @else if (triggers.empty()) {
        <div hlmEmpty class="border border-dashed" data-testid="triggers-empty">
          <div hlmEmptyHeader>
            <div hlmEmptyMedia variant="icon"><ng-icon name="lucideZap" aria-hidden="true" /></div>
            <div hlmEmptyTitle>
              {{ statusFilter() === 'PAUSED' ? 'No paused triggers' : 'No triggers yet' }}
            </div>
            <p hlmEmptyDescription>
              A trigger starts an agent when an issue is assigned, a comment mentions it, or a
              schedule is due. You see the triggers you made and those of your teams.
            </p>
          </div>
          @if (member() && statusFilter() !== 'PAUSED') {
            <div hlmEmptyContent>
              <a
                hlmBtn
                variant="outline"
                size="sm"
                [routerLink]="['/c', companyId, 'triggers', 'new']"
              >
                <ng-icon name="lucidePlus" aria-hidden="true" />
                Create a trigger
              </a>
            </div>
          }
        </div>
      } @else {
        <ul class="bg-card flex flex-col divide-y rounded-xl border" aria-label="Triggers">
          @for (t of triggers.items(); track t.id) {
            <li
              class="flex min-w-0 flex-col gap-2 px-4 py-3.5"
              [attr.data-testid]="'trigger-' + t.id"
            >
              <div class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                <ng-icon
                  class="text-muted-foreground shrink-0"
                  size="16"
                  aria-hidden="true"
                  [name]="
                    t.source === 'SCHEDULE'
                      ? 'lucideCalendarClock'
                      : t.source === 'CHANNEL'
                        ? 'lucideMessageSquare'
                        : t.source === 'WEBHOOK'
                          ? 'lucideWebhook'
                          : 'lucideZap'
                  "
                />
                <a
                  class="focus-visible:ring-ring/50 min-w-0 rounded-sm font-semibold break-words underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                  data-testid="trigger-link"
                  [routerLink]="['/c', companyId, 'triggers', t.id]"
                  >{{ t.name }}</a
                >
                <app-trigger-state-chips
                  [status]="t.status"
                  [pausedReason]="t.pausedReason ?? null"
                  [waitingReason]="t.waitingReason ?? null"
                />
              </div>
              <p class="text-sm text-pretty" data-testid="trigger-sentence">{{ t.sentence }}</p>
              <p class="text-muted-foreground text-xs break-words" data-testid="trigger-meta">
                {{ sourceText(t.source, t.systemName) }}
                · {{ t.agentName ?? 'an agent' }} · by {{ t.authorName ?? 'a former member' }}
                @if (t.teamName) {
                  · team {{ t.teamName }}
                } @else {
                  · no team
                }
                @if (t.payerName) {
                  · paid by {{ t.payerName }}
                }
              </p>
              @if (t.lastFiring; as f) {
                <p
                  class="text-muted-foreground flex flex-wrap items-center gap-1 text-xs"
                  data-testid="trigger-last-firing"
                >
                  <ng-icon name="lucideClock" size="12" aria-hidden="true" />
                  Last fired <app-time [value]="f.createdAt" />:
                  {{ outcomeLabels[f.outcome] }}
                  @if (f.subjectKey) {
                    on <span class="font-mono">{{ f.subjectKey }}</span> ({{ eventShort[f.event] }})
                  }
                </p>
              }
            </li>
          }
        </ul>

        @if (triggers.moreFailed()) {
          <app-error-state
            [error]="triggers.error()"
            heading="Couldn't load more"
            (retry)="triggers.retry()"
          />
        } @else if (triggers.hasMore()) {
          <div class="flex justify-center">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              [disabled]="triggers.loadingMore()"
              (click)="triggers.loadMore()"
            >
              @if (triggers.loadingMore()) {
                <hlm-spinner aria-hidden="true" />
              }
              Load more
            </button>
          </div>
        }
      }
    </div>
  `,
})
export class Triggers {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly current = inject(CurrentCompany);

  /** `?status=ACTIVE|PAUSED` filters the list. */
  readonly status = input<string | undefined>(undefined);

  protected readonly shownOptions = SHOWN;
  protected readonly outcomeLabels = OUTCOME_LABELS;
  protected readonly eventShort = EVENT_SHORT;
  protected readonly sourceText = sourceText;
  protected readonly statusFilter = computed<TriggerStatus | undefined>(() => {
    const s = this.status();
    return s === 'ACTIVE' || s === 'PAUSED' ? s : undefined;
  });
  /** A member who may work: a Viewer starts and edits nothing (ADR-0077). */
  protected readonly member = computed(() => mayWork(this.current.access()));
  protected readonly viewer = computed(() => isViewer(this.current.access()));

  protected readonly triggers = pagedResource<
    TriggerSummary,
    { companyId: string; status: TriggerStatus | undefined }
  >({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, status: this.statusFilter() } : undefined;
    },
    load: ({ companyId, status }, cursor) =>
      this.api.invoke(listTriggers, { companyId, status, limit: PAGE_SIZE, cursor }),
  });

  protected setShown(value: string): void {
    void this.router.navigate([], {
      queryParams: { status: value || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
