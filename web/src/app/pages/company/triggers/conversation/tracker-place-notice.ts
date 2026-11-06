import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideExternalLink, lucideInfo } from '@ng-icons/lucide';
import type { Conversation } from '../../../../api/generated/models';

/**
 * Where a trigger's conversation lives (the package 4 contract, §5.8), for the conversation page
 * above its composer (W4a): on a tracker issue (`TRACKER_ISSUE`) people talk on the issue, so the
 * composer takes only answers to open questions (`answersOpenQuestions`; a plain message is `409
 * PLACE_IS_EXTERNAL`) and is hidden while none is open; on a schedule (`SCHEDULE`) its team and
 * author write here, as on a webhook's delivery (`WEBHOOK`, package 5b). A personal conversation
 * shows nothing.
 *
 * Inputs: `conversation`, `openQuestions` (how many questions wait). What its composer takes
 * follows `Conversation.mayPost` and `mayAnswer`.
 */
@Component({
  selector: 'app-tracker-place-notice',
  imports: [NgIcon],
  providers: [provideIcons({ lucideExternalLink, lucideInfo })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Hidden when it says nothing: an empty block would still take its gap above the thread.
  host: { '[class.block]': '!!text()', '[class.hidden]': '!text()' },
  template: `
    @if (text(); as t) {
      <p
        class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-4 py-3 text-sm"
        data-testid="place-notice"
        [attr.data-place]="conversation().place"
      >
        <ng-icon name="lucideInfo" class="mt-0.5 shrink-0" aria-hidden="true" />
        <span class="min-w-0">
          {{ t }}
          @if (url(); as href) {
            <a
              class="text-primary inline-flex items-center gap-0.5 font-semibold underline-offset-2 hover:underline"
              [href]="href"
              target="_blank"
              rel="noopener noreferrer"
              >Open {{ key() }} <ng-icon name="lucideExternalLink" size="12" aria-hidden="true"
            /></a>
          }
        </span>
      </p>
    }
  `,
})
export class TrackerPlaceNotice {
  readonly conversation = input.required<Pick<Conversation, 'place' | 'subject' | 'trigger'>>();
  readonly openQuestions = input(0);

  protected readonly key = computed(() => this.conversation().subject?.key ?? 'the issue');
  protected readonly url = computed(() =>
    this.conversation().place === 'TRACKER_ISSUE' || this.conversation().place === 'WEBHOOK'
      ? (this.conversation().subject?.url ?? null)
      : null,
  );
  protected readonly text = computed(() => {
    const c = this.conversation();
    const trigger = c.trigger?.name ?? 'a trigger';
    if (c.place === 'TRACKER_ISSUE') {
      return this.openQuestions() > 0
        ? `Started by ${trigger} on ${this.key()}. People talk on the issue; here you can answer its open questions, and your answer is posted there.`
        : `Started by ${trigger} on ${this.key()}. People talk on the issue: comment there to tell the agent more.`;
    }
    if (c.place === 'SCHEDULE') {
      return `Started by the schedule ${trigger}. Its results stay in Tinfirm, with its team.`;
    }
    if (c.place === 'WEBHOOK') {
      const from = c.subject?.systemName ? ` from ${c.subject.systemName}` : '';
      const about = c.subject?.key ? ` about ${c.subject.key}` : '';
      return `Started by ${trigger} on a webhook’s delivery${from}${about}. Its results stay in Tinfirm, with its team.`;
    }
    return null;
  });
}
