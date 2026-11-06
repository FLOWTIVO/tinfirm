import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideExternalLink, lucideMessageCircle } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { Conversation } from '../../api/generated/models';

/** What the conversation page needs of a conversation to place it in a chat. */
export type ChatPlaced = Pick<Conversation, 'place' | 'origin' | 'chat'>;

/** What the conversation page says of a conversation held in a chat (package 5, §5.6). */
export interface ChatPlaceView {
  /** The header's badge: "Direct message in Slack · Acme Slack". */
  readonly badge: string;
  /** The platform, for "Open in Slack". */
  readonly platform: string;
  /** Where "Open in Slack" goes (`chat.permalink`), or null when the chat named too little. */
  readonly openUrl: string | null;
  /**
   * What the composer says instead of taking a message: a chat thread's messages come from the
   * chat (`409 PLACE_IS_EXTERNAL`); its questions and approvals are still answered in the app.
   */
  readonly composer: string;
}

/** The chat place of a conversation (`place`, `origin`, `chat`), or null for one held elsewhere. */
export function chatPlaceView(conversation: ChatPlaced): ChatPlaceView | null {
  if (conversation.place !== 'CHAT_THREAD') return null;
  const chat = conversation.chat;
  const kind = chat?.systemKind ?? null;
  const platform = kind === 'TEAMS' ? 'Teams' : kind === 'SLACK' ? 'Slack' : 'the chat';
  const where = kind ? `in ${platform}` : 'in a chat';
  const system = chat?.systemName ? ` · ${chat.systemName}` : '';
  const openUrl = chat?.permalink ?? null;
  return conversation.origin === 'DIRECT_MESSAGE'
    ? {
        badge: `Direct message ${where}${system}`,
        platform,
        openUrl,
        composer: `This conversation lives in your direct message with the Tinfirm bot: write to it ${kind ? `in ${platform}` : 'there'}. Questions, approvals and Connect cards can be answered here too.`,
      }
    : {
        badge: `Chat thread ${where}${system}`,
        platform,
        openUrl,
        composer: `This conversation lives in a thread ${where}: reply there. Questions and approvals can be answered here too.`,
      };
}

/**
 * The conversation page's mark of a conversation held in a chat (package 5): a badge for the
 * header ("Direct message in Slack · Acme Slack") and "Open in Slack" to its thread, from
 * `Conversation.chat`. Neutral: nothing here waits on the reader. The conversation page's header
 * shows it beside the title, and its composer shows `chatPlaceView(…).composer` instead of the
 * message field.
 */
@Component({
  selector: 'app-chat-place',
  imports: [NgIcon, HlmButton],
  providers: [provideIcons({ lucideExternalLink, lucideMessageCircle })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-flex flex-wrap items-center gap-2' },
  template: `
    @if (view(); as v) {
      <span
        class="bg-card text-muted-foreground inline-flex h-6 items-center gap-1.5 rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
        data-testid="chat-place"
        [attr.data-origin]="conversation().origin"
      >
        <ng-icon name="lucideMessageCircle" aria-hidden="true" />
        {{ v.badge }}
      </span>
      @if (v.openUrl; as url) {
        <a
          hlmBtn
          variant="ghost"
          size="xs"
          target="_blank"
          rel="noopener noreferrer"
          data-testid="chat-place-open"
          [href]="url"
        >
          <ng-icon name="lucideExternalLink" aria-hidden="true" />
          Open in {{ v.platform }}
        </a>
      }
    }
  `,
})
export class ChatPlace {
  readonly conversation = input.required<ChatPlaced>();

  protected readonly view = computed(() => chatPlaceView(this.conversation()));
}
