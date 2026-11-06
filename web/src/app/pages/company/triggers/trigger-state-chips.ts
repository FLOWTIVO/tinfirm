import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideClock, lucidePause } from '@ng-icons/lucide';
import type {
  TriggerPausedReason,
  TriggerStatus,
  TriggerWaitingReason,
} from '../../../api/generated/models';
import { PAUSED_REASON_LABELS, WAITING_REASON_LABELS } from './trigger-labels';

/**
 * A trigger's state as chips: "Paused · Its author left the company", "Waits for an admin to set
 * up model access". Neutral (DESIGN.md: whoever waits on someone else is not asked to act); an
 * active trigger that waits on nothing shows no chip.
 */
@Component({
  selector: 'app-trigger-state-chips',
  imports: [NgIcon],
  providers: [provideIcons({ lucideClock, lucidePause })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (status() === 'PAUSED') {
      <span
        class="bg-muted text-muted-foreground inline-flex min-h-6 max-w-full items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
        data-testid="trigger-paused"
        [attr.data-reason]="pausedReason()"
      >
        <ng-icon name="lucidePause" size="12" class="shrink-0" aria-hidden="true" />
        <span class="break-words">
          Paused{{ pausedReason() ? ': ' + pausedLabels[pausedReason()!] : '' }}
        </span>
      </span>
    }
    @if (waitingReason(); as reason) {
      <span
        class="bg-card text-muted-foreground inline-flex min-h-6 max-w-full items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
        data-testid="trigger-waiting"
        [attr.data-reason]="reason"
      >
        <ng-icon name="lucideClock" size="12" class="shrink-0" aria-hidden="true" />
        <span class="break-words">{{ waitingLabels[reason] }}</span>
      </span>
    }
  `,
})
export class TriggerStateChips {
  readonly status = input.required<TriggerStatus>();
  readonly pausedReason = input<TriggerPausedReason | null>(null);
  readonly waitingReason = input<TriggerWaitingReason | null>(null);

  protected readonly pausedLabels = PAUSED_REASON_LABELS;
  protected readonly waitingLabels = WAITING_REASON_LABELS;
}
