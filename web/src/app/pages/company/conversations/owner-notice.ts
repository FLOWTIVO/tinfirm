import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideLock } from '@ng-icons/lucide';
import type { Conversation } from '../../../api/generated/models';

/**
 * The Owner reading someone else's conversation (plan §4.1, `OwnerNotice`; security review, row
 * 18): a neutral banner where the composer would be, saying whose conversation it is, that only
 * they write, decide and connect accounts here, and that the read is recorded, which the server
 * does (`conversation.read`, shown to the person behind their eye button). Cancel, retry and end
 * stay in the header's menu, as the API allows. Its person sees who read it instead
 * (`app-conversation-reads-button`).
 */
@Component({
  selector: 'app-owner-notice',
  imports: [NgIcon],
  providers: [provideIcons({ lucideLock })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div
      class="bg-muted flex items-start gap-2.5 rounded-[18px] border px-4 py-3 text-sm"
      role="note"
      data-testid="owner-banner"
    >
      <ng-icon name="lucideLock" class="text-muted-foreground mt-0.5 shrink-0" aria-hidden="true" />
      <p class="min-w-0 text-pretty">
        <span class="font-semibold">{{ person() }}’s conversation.</span>
        <span class="text-muted-foreground">
          Only {{ person() }} writes, decides and connects accounts here. Your read is
          recorded.</span
        >
      </p>
    </div>
  `,
})
export class OwnerNotice {
  readonly conversation = input.required<Conversation>();

  protected readonly person = computed(
    () => this.conversation().personName ?? this.conversation().trigger?.authorName ?? 'Its person',
  );
}
