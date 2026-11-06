import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideUnlink } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { removeMemberChatAccount } from '../../../api/generated/functions';
import type { MemberAccount } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';

/**
 * An admin removes a member's account on a chat system, their link or their Slack sign-in (package
 * 5, open point 16): the kill switch for a compromised chat account, the one exception to "only the
 * member manages their accounts" (I4). The member's direct messages are refused until they link
 * again; their open direct-message conversations stay.
 */
@Component({
  selector: 'app-member-chat-account',
  imports: [NgIcon, HlmAlertImports, HlmAlertDialogImports, HlmButton, HlmSpinner],
  providers: [provideIcons({ lucideCircleAlert, lucideUnlink })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-flex' },
  template: `
    <button
      hlmBtn
      variant="ghost"
      size="xs"
      type="button"
      class="max-sm:h-10"
      aria-haspopup="dialog"
      data-testid="remove-member-chat-account"
      (click)="openRemove()"
    >
      <ng-icon name="lucideUnlink" aria-hidden="true" />
      Remove link<span class="sr-only"> on {{ account().systemName }}</span>
    </button>

    <hlm-alert-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove the {{ account().systemName }} link of {{ name() }}?</h2>
          <p hlmAlertDialogDescription>
            For a chat account that is not {{ name() }}'s any more, or was taken over. The bot
            refuses its direct messages until {{ name() }} links again; their open direct-message
            conversations stay, readable in the app. The audit log records that you removed it.
          </p>
        </hlm-alert-dialog-header>
        @if (error(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-remove-member-chat-account"
            [disabled]="busy()"
            (click)="remove()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove the link
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class MemberChatAccount {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  readonly userId = input.required<string>();
  readonly name = input.required<string>();
  readonly account = input.required<MemberAccount>();
  /** The link is gone: the list reads the member's accounts again. */
  readonly removed = output<void>();

  protected readonly open = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected openRemove(): void {
    this.error.set(null);
    this.open.set(true);
  }

  protected async remove(): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.invoke(removeMemberChatAccount, {
        companyId,
        userId: this.userId(),
        systemId: this.account().systemId,
      });
      this.open.set(false);
      this.removed.emit();
      this.notify.success(`The ${this.account().systemName} link of ${this.name()} is removed`);
    } catch (error) {
      this.error.set(
        problemMessage(error, {
          'status:404': `${this.name()} holds no link there any more.`,
          'status:403': 'Only owners and admins remove a member’s chat link.',
        }),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
