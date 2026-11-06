import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideLogIn,
  lucideMessagesSquare,
  lucideUnlink,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { disconnectMyAccount } from '../../../api/generated/functions';
import type { MyAccount } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { SYSTEM_KIND_LABELS } from '../../../shared/labels';
import { Time } from '../../../shared/time';
import { MyChatAgent } from './my-chat-agent';

/** How one chat system's row reads: linked to whom, or how to link. */
export interface ChatLinkView {
  readonly key: string;
  readonly name: string;
  readonly kind: string;
  readonly linked: boolean;
  readonly chip: string;
  readonly says: string;
  /** The Slack sign-in it offers (`history`, open point 13), or null. */
  readonly signIn: string | null;
}

/**
 * A chat system's row on Accounts (package 5, §5.4): a link is made from the chat, or, on Slack
 * with its app, by signing in to Slack, whose account then lets agents read channels as the member
 * (`history`, open point 13) and is their link too.
 */
export function chatLinkView(item: MyAccount): ChatLinkView {
  const platform = item.system.kind === 'TEAMS' ? 'Teams' : 'Slack';
  const account = item.account;
  const slackApp = item.system.kind === 'SLACK' && item.oauthAvailable;
  const base = {
    key: item.system.id,
    name: item.system.name,
    kind: SYSTEM_KIND_LABELS[item.system.kind],
  };
  if (!account) {
    return {
      ...base,
      linked: false,
      chip: 'Not linked',
      says: slackApp
        ? 'Sign in to Slack, or send the Tinfirm bot a direct message in Slack: it answers with a link that you confirm here, once.'
        : `Send the Tinfirm bot a direct message in ${platform}: it answers with a link that you confirm here, once.`,
      signIn: slackApp ? 'Sign in to Slack' : null,
    };
  }
  const who = account.login ? `${platform} user ${account.login}` : `your ${platform} user`;
  const own =
    'What you write to the Tinfirm bot there are your own conversations, with your own accounts and budget.';
  if (account.kind === 'OAUTH') {
    const ok = account.status === 'OK';
    return {
      ...base,
      linked: true,
      chip: ok ? 'Signed in' : 'Sign in again',
      says: ok
        ? `Signed in as ${who}: agents read Slack channels as you where a conversation uses your account. ${own}`
        : `Slack no longer accepts your sign-in as ${who}: sign in again so that agents can read channels as you.`,
      signIn: ok || !item.oauthAvailable ? null : 'Sign in again',
    };
  }
  return {
    ...base,
    linked: true,
    chip: account.status === 'OK' ? 'Linked' : 'Linked, not working',
    says: `Linked to ${who}. ${own}${slackApp ? ' Sign in to Slack to let agents read channels as you.' : ''}`,
    signIn: slackApp ? 'Sign in to Slack' : null,
  };
}

/**
 * Accounts › Systems, the chats (package 5a): per Slack or Teams system, whether the member's chat
 * user is linked to them, and Unlink (`DELETE /me/accounts/{systemId}`). A link is made from the
 * chat, by the sign-in link the bot sends (or, on Teams, automatically). On a Slack system with its
 * app, the member may also sign in to Slack here (`signIn`, which the page starts): the account
 * lets agents read channels as them and is their link (open point 13).
 */
@Component({
  selector: 'app-my-chat-links',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    Time,
    MyChatAgent,
  ],
  providers: [
    provideIcons({ lucideCircleAlert, lucideLogIn, lucideMessagesSquare, lucideUnlink }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-3' },
  template: `
    <div class="flex flex-col gap-0.5">
      <h2 id="chat-links-heading" class="text-base font-bold">Chats</h2>
      <p class="text-muted-foreground text-sm text-pretty">
        Talk to agents from a direct message with the Tinfirm bot. Each chat user is linked to one
        member, and only by them. Signing in to Slack also lets agents read channels as you.
      </p>
    </div>
    <ul class="flex flex-col gap-3" aria-labelledby="chat-links-heading">
      @for (row of rows(); track row.view.key) {
        @let v = row.view;
        <li
          class="bg-card focus-visible:ring-ring/50 flex flex-col gap-3 rounded-xl border p-4 outline-none focus-visible:ring-3 sm:flex-row sm:items-start"
          tabindex="-1"
          data-testid="chat-link"
          [id]="'chat-' + v.key"
          [attr.data-linked]="v.linked"
          [attr.aria-labelledby]="'chat-' + v.key + '-name'"
        >
          <span
            class="bg-muted text-muted-foreground hidden size-10 shrink-0 items-center justify-center rounded-xl sm:flex"
            aria-hidden="true"
          >
            <ng-icon name="lucideMessagesSquare" size="18" />
          </span>
          <div class="flex min-w-0 flex-1 flex-col gap-1">
            <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
              <h3 class="font-semibold break-words" [id]="'chat-' + v.key + '-name'">
                {{ v.name }}
              </h3>
              @if (v.kind !== v.name) {
                <span class="text-muted-foreground text-xs">{{ v.kind }}</span>
              }
              <span class="sr-only">: </span>
              <span
                class="inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
                data-testid="chat-link-chip"
                [class]="
                  v.linked
                    ? 'border-success/40 bg-success/10 text-success'
                    : 'bg-card text-muted-foreground'
                "
                >{{ v.chip }}</span
              >
            </div>
            <p class="text-sm text-pretty" data-testid="chat-link-says">{{ v.says }}</p>
            @if (row.item.account; as account) {
              <p class="text-muted-foreground text-xs">
                {{ account.kind === 'OAUTH' ? 'Signed in' : 'Linked' }}
                <app-time [value]="account.connectedAt" />
                @if (account.lastUsedAt; as used) {
                  · last message <app-time [value]="used" />
                }
              </p>
            }
          </div>
          @if (v.signIn; as label) {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="w-fit sm:shrink-0"
              data-testid="chat-sign-in"
              [disabled]="!!signingIn()"
              (click)="signIn.emit(row.item)"
            >
              <ng-icon name="lucideLogIn" aria-hidden="true" />
              {{ label }}
            </button>
          }
          @if (v.linked) {
            <button
              hlmBtn
              variant="ghost"
              size="sm"
              type="button"
              class="w-fit sm:shrink-0"
              data-testid="chat-unlink"
              [attr.aria-label]="'Unlink your ' + v.name + ' user'"
              (click)="askUnlink(row.item)"
            >
              <ng-icon name="lucideUnlink" aria-hidden="true" />
              Unlink
            </button>
          }
        </li>
      }
    </ul>

    @if (anyLinked()) {
      <app-my-chat-agent [companyId]="companyId()" />
    }

    <hlm-alert-dialog [state]="unlinkOpen() ? 'open' : 'closed'" (closed)="unlinkOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Unlink your {{ unlinking()?.system?.name }} user?</h2>
          <p hlmAlertDialogDescription>
            @if (unlinking()?.account?.kind === 'OAUTH') {
              The bot refuses your direct messages until you link again, and agents no longer read
              Slack as you.
            } @else {
              The bot refuses your direct messages until you link again.
            }
            Your direct-message conversations stay, readable here. If you did not link it yourself,
            unlink it now.
          </p>
        </hlm-alert-dialog-header>
        @if (unlinkError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="unlinkBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-chat-unlink"
            [disabled]="unlinkBusy()"
            (click)="confirmUnlink()"
          >
            @if (unlinkBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Unlink
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class MyChatLinks {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  /** The reader's accounts on the company's chat systems. */
  readonly items = input.required<readonly MyAccount[]>();
  /** The system whose sign-in the page is starting, if any. */
  readonly signingIn = input<string | null>(null);
  /** A link was removed: the page reads the accounts again. */
  readonly unlinked = output<string>();
  /** Sign in to Slack with the system's app; the page starts it and comes back here. */
  readonly signIn = output<MyAccount>();

  /** The default agent matters once a chat user is linked: unlinked, the bot answers nothing. */
  protected readonly anyLinked = computed(() => this.items().some((item) => !!item.account));
  protected readonly rows = computed(() =>
    this.items().map((item) => ({ item, view: chatLinkView(item) })),
  );

  protected readonly unlinking = signal<MyAccount | null>(null);
  protected readonly unlinkOpen = signal(false);
  protected readonly unlinkBusy = signal(false);
  protected readonly unlinkError = signal<string | null>(null);

  protected askUnlink(item: MyAccount): void {
    this.unlinking.set(item);
    this.unlinkError.set(null);
    this.unlinkOpen.set(true);
  }

  protected async confirmUnlink(): Promise<void> {
    const item = this.unlinking();
    if (!item || this.unlinkBusy()) return;
    this.unlinkBusy.set(true);
    this.unlinkError.set(null);
    try {
      await this.api.invoke(disconnectMyAccount, {
        companyId: this.companyId(),
        systemId: item.system.id,
      });
      this.unlinkOpen.set(false);
      this.unlinked.emit(item.system.id);
      this.notify.success(`Your ${item.system.name} user is unlinked`);
    } catch (error) {
      this.unlinkError.set(problemMessage(error));
    } finally {
      this.unlinkBusy.set(false);
    }
  }
}
