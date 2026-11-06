import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import type { MemberAccount } from '../../../api/generated/models';
import { ErrorState } from '../../../shared/error-state';
import { MemberChatAccount } from './member-chat-account';
import { MembersData } from './members-data';
import { type AccountTone, accountStatus, isChatAccount } from './members-view';

const TONE_CLASS: Record<AccountTone, string> = {
  ok: 'text-success bg-success/10',
  warning: 'text-warning bg-warning/10',
  destructive: 'text-destructive bg-destructive/10',
};

/**
 * Which company systems a member holds an account on, and its status (`listMemberAccounts`): the
 * package 1 contract's I4, never who they are there, and nothing of their personal MCP servers.
 * One row per system in the member's dialog; an admin may remove a chat link (package 5a).
 */
@Component({
  selector: 'app-member-accounts',
  imports: [HlmSkeleton, ErrorState, MemberChatAccount],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-3' },
  template: `
    @let state = accounts();
    @if (state?.error) {
      <app-error-state
        [error]="state?.error"
        [heading]="'Couldn’t load the accounts of ' + name()"
        (retry)="reload()"
      />
    } @else if (!state?.items) {
      <div class="flex flex-col gap-1.5" aria-busy="true">
        <span class="sr-only" role="status">Loading the accounts of {{ name() }}…</span>
        <hlm-skeleton class="h-12 w-full rounded-xl" />
      </div>
    } @else if (items().length) {
      <ul
        class="m-0 flex list-none flex-col divide-y rounded-xl border p-0"
        [attr.aria-label]="'Accounts of ' + name()"
      >
        @for (account of items(); track account.systemId) {
          @let status = statusOf(account);
          <li
            class="flex min-h-12 items-center gap-2.5 py-1.5 ps-3.5 pe-2"
            [attr.data-testid]="'member-account-' + account.systemName"
          >
            <span class="min-w-0 flex-1 truncate text-sm font-semibold">{{
              account.systemName
            }}</span>
            <span
              class="inline-flex h-[22px] shrink-0 items-center rounded-full px-2.5 text-xs leading-none font-semibold"
              [class]="toneClass[status.tone]"
              >{{ status.label }}</span
            >
            @if (isChat(account)) {
              <app-member-chat-account
                [userId]="userId()"
                [name]="name()"
                [account]="account"
                (removed)="reload()"
              />
            }
          </li>
        }
      </ul>
    } @else {
      <p
        class="text-muted-foreground m-0 rounded-xl border border-dashed px-3.5 py-3 text-[0.8125rem]"
        data-testid="no-member-accounts"
      >
        No account on a company system yet.
      </p>
    }
    @if (state?.items) {
      <p class="text-muted-foreground m-0 text-xs">
        Only {{ name() }} connects or disconnects these accounts.
        @if (hasChatLink()) {
          You can remove a chat link that is no longer theirs.
        }
      </p>
    }
  `,
})
export class MemberAccounts {
  private readonly data = inject(MembersData);

  readonly userId = input.required<string>();
  readonly name = input.required<string>();

  protected readonly toneClass = TONE_CLASS;
  protected readonly statusOf = accountStatus;
  protected readonly isChat = isChatAccount;

  protected readonly accounts = computed(() => this.data.accountsOf(this.userId()));
  protected readonly items = computed<readonly MemberAccount[]>(() => this.accounts()?.items ?? []);
  protected readonly hasChatLink = computed(() => this.items().some(isChatAccount));

  protected reload(): void {
    this.data.reloadAccounts(this.userId());
  }
}
