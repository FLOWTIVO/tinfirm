import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { Conversation, ConversationBinding } from '../../../api/generated/models';
import { BindingCard } from '../../../shared/binding-card';

/**
 * The Connect cards at the end of the thread (plan §4.1, Cards): what holds the conversation for an
 * account now, W1's `app-binding-card` by `reason`, orange only where the reader acts; a binding
 * that can no longer be used says so, with "Start a new conversation". The region is `#bindings`,
 * which a queued turn's "Connect the account" brings into view. While the reader is scrolled away
 * from them, W4b's `app-pinned-cards` repeats the ones they act on above the composer (the page
 * watches `[data-open-card]`).
 */
@Component({
  selector: 'app-conversation-cards',
  imports: [BindingCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @if (shown().length > 0) {
      <section
        id="bindings"
        tabindex="-1"
        class="flex scroll-mt-24 flex-col gap-3 outline-none"
        aria-label="Accounts this conversation waits for"
        data-testid="conversation-cards"
      >
        @for (b of shown(); track b.systemId) {
          <app-binding-card
            [binding]="b"
            [companyId]="companyId()"
            [conversationId]="conversation().id"
            [agentName]="conversation().agentName"
            [agentId]="conversation().agentId"
            [signInAvailable]="signIn().get(b.systemId) ?? false"
            [mine]="mine()"
            [personName]="conversation().personName ?? null"
            [attr.data-open-card]="mine() && b.state === 'WAITING' ? '' : null"
            (changed)="bindingChanged.emit($event)"
            (outdated)="outdated.emit()"
          />
        }
      </section>
    }
  `,
})
export class ConversationCards {
  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  /** Whether the reader is the conversation's person; the Owner only sees it wait. */
  readonly mine = input(false);
  /** Per system, whether its app lets the reader sign in (`MyAccount.oauthAvailable`). */
  readonly signIn = input<ReadonlyMap<string, boolean>>(new Map());
  /** A card's answer: the binding as it is now. */
  readonly bindingChanged = output<ConversationBinding>();
  /** A card's binding was settled in the meantime: the conversation is read again. */
  readonly outdated = output<void>();

  /** The bindings that wait or are gone; an ended conversation asks for nothing. */
  protected readonly shown = computed(() => {
    const c = this.conversation();
    if (c.state === 'ENDED') return [];
    return c.bindings.filter((b) => b.state !== 'BOUND');
  });
}
