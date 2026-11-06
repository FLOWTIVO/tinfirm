import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideMonitorSmartphone } from '@ng-icons/lucide';
import type { Conversation } from '../api/generated/models';

/**
 * The "AI client" badge (the package 6 contract, §5.7): a conversation its person started from an AI
 * client of theirs, through Tinfirm's MCP endpoint (`origin CLIENT`). It is a personal conversation
 * like one started in the app; the badge only says where it began. Nothing is drawn for any other
 * origin, so the conversation page (W4a) and the lists may put it beside every conversation.
 */
@Component({
  selector: 'app-client-badge',
  imports: [NgIcon],
  providers: [provideIcons({ lucideMonitorSmartphone })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (origin() === 'CLIENT') {
      <span
        class="text-muted-foreground inline-flex h-5 items-center gap-1 rounded-full border px-2 text-xs font-semibold whitespace-nowrap"
        title="Started from an AI client of its person’s, through Tinfirm’s MCP endpoint"
        data-testid="client-badge"
      >
        <ng-icon name="lucideMonitorSmartphone" class="size-3" aria-hidden="true" />
        AI client
      </span>
    }
  `,
})
export class ClientBadge {
  readonly origin = input.required<Conversation['origin'] | null | undefined>();
}
