import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideExternalLink, lucideMessageSquareQuote, lucideWebhook } from '@ng-icons/lucide';
import type {
  Conversation,
  ConversationMessage,
  ConversationSubject,
} from '../../../../api/generated/models';
import { initials } from '../../../../shared/forms';
import { Markdown } from '../../../../shared/markdown';
import { Time } from '../../../../shared/time';

/**
 * A comment someone wrote on a trigger's conversation's issue (an `EXTERNAL` message; the package
 * 4 contract, §3.6 and §5.8), for the conversation page's message list (W4a): a bubble from the
 * left like other people's, headed by its author's name at the system and "on SHOP-241", its body
 * rendered as other people's words (sanitized Markdown, ADR-0047), and whether it answered the
 * open questions. A purged one shows its removal marker only. In a webhook trigger's conversation
 * (place `WEBHOOK`, package 5b) it is the delivery's text, without an author: "{system} sent about
 * {key}".
 *
 * Inputs: `message` (kind `EXTERNAL`), the conversation's `subject` (its key, system and link) and
 * `place`.
 */
@Component({
  selector: 'app-external-message',
  imports: [NgIcon, Markdown, Time],
  providers: [provideIcons({ lucideExternalLink, lucideMessageSquareQuote, lucideWebhook })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 gap-2.5', 'data-testid': 'external-message' },
  template: `
    @let m = message();
    <span
      class="bg-secondary text-secondary-foreground flex size-8 shrink-0 items-center justify-center rounded-full text-xs font-bold"
      aria-hidden="true"
    >
      @if (delivery()) {
        <ng-icon name="lucideWebhook" size="15" />
      } @else {
        {{ avatar() }}
      }
    </span>
    <div class="flex min-w-0 max-w-[min(42rem,100%)] flex-col gap-1">
      <p class="text-muted-foreground flex flex-wrap items-center gap-x-1.5 text-xs">
        @if (delivery()) {
          <span class="text-foreground font-semibold" data-testid="external-author">{{
            subject()?.systemName ?? 'The webhook'
          }}</span>
          <span>sent{{ about() }}</span>
        } @else {
          <span class="text-foreground font-semibold" data-testid="external-author">{{
            m.authorName ?? 'Someone'
          }}</span>
          <span class="inline-flex items-center gap-1">
            <ng-icon name="lucideMessageSquareQuote" size="12" aria-hidden="true" />
            commented{{ where() }}
          </span>
        }
        <span aria-hidden="true">·</span>
        <app-time [value]="m.createdAt" />
        @if (subject()?.url; as url) {
          <a
            class="text-primary inline-flex items-center gap-0.5 underline-offset-2 hover:underline"
            [href]="url"
            target="_blank"
            rel="noopener noreferrer"
            >{{ delivery() ? 'open ' + (subject()?.key ?? 'it') : 'on the issue' }}
            <ng-icon name="lucideExternalLink" size="11" aria-hidden="true"
          /></a>
        }
      </p>
      <div class="bg-card rounded-2xl rounded-tl-md border px-4 py-3 text-[0.9375rem]">
        @if (m.purgedAt) {
          <p class="text-muted-foreground italic" data-testid="external-purged">{{ m.body }}</p>
        } @else {
          <app-markdown [text]="m.body" />
        }
      </div>
      @if (m.answersOpenQuestions) {
        <p class="text-muted-foreground text-xs" data-testid="external-answers">
          This comment answered the open questions.
        </p>
      }
    </div>
  `,
})
export class ExternalMessage {
  readonly message = input.required<ConversationMessage>();
  readonly subject = input<ConversationSubject | null>(null);
  /** The conversation's place: on `WEBHOOK` the message is a delivery's text, without an author. */
  readonly place = input<Conversation['place'] | null>(null);

  protected readonly delivery = computed(() => this.place() === 'WEBHOOK');
  protected readonly about = computed(() => {
    const key = this.subject()?.key;
    return key ? ` about ${key}` : '';
  });

  protected readonly avatar = computed(() => initials(this.message().authorName ?? '?'));
  protected readonly where = computed(() => {
    const s = this.subject();
    if (!s) return '';
    return ` on ${s.systemName ? `${s.systemName} ` : ''}${s.key}`;
  });
}
