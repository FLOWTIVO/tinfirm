import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listTriggerFirings } from '../../../api/generated/functions';
import type { TriggerFiring } from '../../../api/generated/models';
import { pagedResource } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Time } from '../../../shared/time';
import { EVENT_LABELS, fired, OUTCOME_LABELS } from './trigger-labels';

const PAGE_SIZE = 20;

/**
 * A trigger's firings, newest first (the package 4 contract, §3.11): when, on which issue and by
 * which event, run now by whom, what came of it (started or continued a conversation, or why it
 * was skipped), with a link to the conversation and its turn.
 */
@Component({
  selector: 'app-trigger-firings',
  imports: [RouterLink, HlmButton, HlmSpinner, ErrorState, ListSkeleton, Time],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-3' },
  template: `
    @if (firings.firstLoad()) {
      <app-list-skeleton [rows]="2" />
    } @else if (firings.failed()) {
      <app-error-state
        [error]="firings.error()"
        heading="Couldn't load its firings"
        (retry)="firings.retry()"
      />
    } @else if (firings.empty()) {
      <p class="text-muted-foreground text-sm" data-testid="firings-empty">
        It has not fired yet. Changes made before it was created or last resumed never fire it.
      </p>
    } @else {
      <ul class="flex flex-col divide-y rounded-lg border" aria-label="Firings">
        @for (f of firings.items(); track f.id) {
          <li
            class="flex min-w-0 flex-col gap-1 px-3 py-2.5 text-sm sm:flex-row sm:items-baseline sm:gap-3"
            [attr.data-firing]="f.id"
            [attr.data-outcome]="f.outcome"
          >
            <span class="text-muted-foreground shrink-0 text-xs sm:w-28">
              <app-time [value]="f.createdAt" />
            </span>
            <span class="flex min-w-0 flex-1 flex-col gap-0.5">
              <span class="break-words">
                @if (f.subjectKey) {
                  <span class="font-mono text-xs font-semibold">{{ f.subjectKey }}</span> ·
                }
                {{ f.manualBy ? 'Run now' : eventLabels[f.event] }}
                @if (f.value) {
                  <span class="text-muted-foreground">({{ f.value }})</span>
                }
              </span>
              <span
                class="text-xs"
                [class]="
                  isFired(f)
                    ? 'text-success'
                    : f.outcome === 'FAILED'
                      ? 'text-destructive'
                      : 'text-muted-foreground'
                "
              >
                {{ outcomeLabels[f.outcome] }}{{ f.reason ? ': ' + f.reason : '' }}
                @if (f.chainDepth > 0) {
                  · chain {{ f.chainDepth }} of 3
                }
              </span>
            </span>
            @if (f.conversationId) {
              <a
                class="text-primary shrink-0 text-xs font-semibold underline-offset-2 hover:underline"
                [routerLink]="['/c', companyId(), 'conversations', f.conversationId]"
                >Conversation</a
              >
            }
          </li>
        }
      </ul>
      @if (firings.moreFailed()) {
        <app-error-state
          [error]="firings.error()"
          heading="Couldn't load more"
          (retry)="firings.retry()"
        />
      } @else if (firings.hasMore()) {
        <button
          hlmBtn
          variant="outline"
          size="sm"
          type="button"
          class="self-center"
          [disabled]="firings.loadingMore()"
          (click)="firings.loadMore()"
        >
          @if (firings.loadingMore()) {
            <hlm-spinner aria-hidden="true" />
          }
          Load more
        </button>
      }
    }
  `,
})
export class TriggerFirings {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly triggerId = input.required<string>();
  /** Changes on each firing the page knows of (run now), which reads the list again. */
  readonly revision = input(0);

  protected readonly eventLabels = EVENT_LABELS;
  protected readonly outcomeLabels = OUTCOME_LABELS;

  protected readonly firings = pagedResource<
    TriggerFiring,
    { companyId: string; triggerId: string; revision: number }
  >({
    params: () => ({
      companyId: this.companyId(),
      triggerId: this.triggerId(),
      revision: this.revision(),
    }),
    load: ({ companyId, triggerId }, cursor) =>
      this.api.invoke(listTriggerFirings, { companyId, triggerId, limit: PAGE_SIZE, cursor }),
  });

  protected isFired(f: TriggerFiring): boolean {
    return fired(f.outcome);
  }
}
