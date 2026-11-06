import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideCircleCheck,
  lucideEye,
  lucideLink2,
  lucideShieldAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { confirmChatLink, readChatLink } from '../../api/generated/functions';
import type { ChatLink } from '../../api/generated/models';
import { problemMessage, toProblem } from '../../api/problem';
import { Session } from '../../core/session';
import { Logo } from '../../shared/logo';
import { formatClock, formatRelative } from '../../shared/time';
import { chatLinkToken, forgetChatLinkToken } from './chat-link-token';

/** "Slack", "Teams". */
function platformOf(link: Pick<ChatLink, 'systemKind'>): string {
  return link.systemKind === 'TEAMS' ? 'Teams' : 'Slack';
}

/** Why a link cannot be confirmed, in the words of this page: a heading and what to do. */
export function linkRefusal(error: unknown): { heading: string; text: string } {
  const problem = toProblem(error);
  switch (problem.code) {
    case 'CHAT_LINK_EXPIRED':
      return {
        heading: 'This link has expired',
        text: 'A link works for 10 minutes. Send the Tinfirm bot a direct message again, and it sends you a new one.',
      };
    case 'CHAT_LINK_USED':
      return {
        heading: 'This link was already used',
        text: 'A link works once. If your chat account is not linked yet, send the Tinfirm bot a direct message again for a new one.',
      };
    case 'IDENTITY_IN_USE':
      return {
        heading: 'This chat user is someone else’s',
        text: 'Another member of this company is linked to this chat user. Nothing was linked; the link is used up.',
      };
    case 'ALREADY_LINKED':
      return {
        heading: 'You are linked to another chat user',
        text: 'Your account is already linked to another user of this chat. Unlink it under Accounts, then ask the bot for a new link. Nothing was linked; this link is used up.',
      };
    default:
      if (problem.status === 404) {
        return {
          heading: 'This link is not for this account',
          text: 'It is unknown, for another company, or you are not a member of the company it links to. Check that you are signed in as yourself.',
        };
      }
      return { heading: 'Couldn’t check this link', text: problemMessage(error) };
  }
}

/** The question the page asks: what it links, by what the chat user cannot choose (package 5, §5.4). */
export function linkQuestion(
  link: ChatLink,
  email: string,
  now: Date = new Date(),
): {
  title: string;
  who: string;
  when: string;
  name: string | null;
  live: string;
  waiting: string | null;
} {
  const platform = platformOf(link);
  const workspace = link.workspaceName
    ? ` of the workspace ${link.workspaceName}${link.workspaceId ? ` (${link.workspaceId})` : ''}`
    : link.workspaceId
      ? ` of the workspace ${link.workspaceId}`
      : '';
  const asked = new Date(link.requestedAt);
  return {
    title: `Link the ${platform} user ${link.chatUserId} to your account?`,
    who: `The ${platform} user ${link.chatUserId}${workspace}, on ${link.systemName}, to your account ${email}.`,
    when: `Asked for this link at ${formatClock(asked, now)}, ${formatRelative(asked, now)}.`,
    name: link.displayName ? `Its display name, ${link.displayName}, is one anyone can set.` : null,
    live: liveName(link, platform),
    waiting: waitingMessages(link.waitingMessages ?? 0),
  };
}

/**
 * Who the platform says the chat user is now, as the server asked it when the page loaded (security
 * review of 2026-10-27, L3): Slack's real name with the picture, or the Entra name on Teams.
 */
function liveName(link: ChatLink, platform: string): string {
  if (!link.platformChecked || !link.platformName) {
    return `Tinfirm could not ask ${platform} who this user is just now. Confirm only if you are sure it is you.`;
  }
  return link.systemKind === 'TEAMS'
    ? `Teams names this user ${link.platformName} (their Entra name).`
    : `Slack names this user ${link.platformName} right now.`;
}

/** The messages that waited for the link: never sent on confirmation, to send again. */
export function waitingMessages(count: number): string | null {
  if (count <= 0) return null;
  const which = count === 1 ? 'The message' : `The ${count} messages`;
  return `${which} this user wrote before linking will not be sent: send ${count === 1 ? 'it' : 'them'} again to the bot once linked.`;
}

/**
 * The chat sign-in link (package 5, §5.4; the security review of 2026-10-12, row 5):
 * `/link/{companyId}#{token}`, which the bot sends an unlinked member in a direct message. After
 * the Tinfirm sign-in, it names what it links by what the chat user cannot choose (their id, the
 * workspace, when they asked) and by who the platform says they are now (Slack's real name and
 * picture, the Entra name on Teams, read by the server as the page loads), warns that the display
 * name proves nothing, says who reads such conversations (row 18), and links on the person's
 * confirmation only. The messages that waited for the link are not sent once it is confirmed
 * (security review of 2026-10-27, L3): the page and the bot ask to send them again. Both sides are
 * told: the bot writes "Linked to …" in the direct message, and the person's inbox keeps the link
 * with its unlink for 7 days.
 */
@Component({
  selector: 'app-chat-link',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmCardImports,
    HlmSkeleton,
    HlmSpinner,
    Logo,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideCircleCheck,
      lucideEye,
      lucideLink2,
      lucideShieldAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="bg-background flex min-h-svh flex-col items-center justify-center px-4 py-10">
      <div class="w-full max-w-md">
        <div class="mb-6 flex flex-col items-center gap-3 text-center">
          <app-logo class="h-12" />
          <h1 class="text-2xl font-bold tracking-tight text-balance" data-testid="link-title">
            {{ title() }}
          </h1>
        </div>

        <section
          hlmCard
          class="rounded-xl shadow-[0_12px_32px_-12px_rgb(0_0_0/0.15)] [--card-spacing:--spacing(6)]"
          aria-label="Link your chat account"
        >
          <div hlmCardContent class="flex flex-col gap-5">
            @if (linked(); as done) {
              <div class="flex items-start gap-3 text-sm" role="status" data-testid="link-done">
                <ng-icon
                  name="lucideCircleCheck"
                  class="text-success mt-px shrink-0"
                  size="18"
                  aria-hidden="true"
                />
                <div class="flex min-w-0 flex-col gap-2">
                  <p class="text-pretty">
                    The {{ platform() }} user
                    <code class="font-mono text-xs">{{ done.chatUserId }}</code> is linked to your
                    account. What you write to the Tinfirm bot there are your own conversations,
                    with your own accounts and budget.
                  </p>
                  <p class="text-muted-foreground text-pretty">
                    The bot said so in your direct message, and your inbox keeps this link for 7
                    days with an Unlink. Messages sent while the link waited are not answered: send
                    them to the bot again.
                  </p>
                </div>
              </div>
              <div class="flex flex-wrap gap-2">
                <a
                  hlmBtn
                  data-testid="link-accounts"
                  [routerLink]="['/c', companyId(), 'accounts']"
                  [queryParams]="{ tab: 'systems' }"
                  [fragment]="'chat-' + done.systemId"
                  >Your chat links</a
                >
              </div>
            } @else if (declined()) {
              <div class="flex flex-col gap-2 text-sm" role="status" data-testid="link-declined">
                <p class="font-semibold">Nothing was linked.</p>
                <p class="text-muted-foreground text-pretty">
                  The link expires on its own. If someone else sent it to you, nobody can use it to
                  act as you.
                </p>
              </div>
              <a hlmBtn variant="outline" class="w-fit" [routerLink]="['/c', companyId(), 'inbox']"
                >Go to Tinfirm</a
              >
            } @else if (refusal(); as r) {
              <div hlmAlert data-testid="link-refused" [attr.data-code]="refusalCode()">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <h2 hlmAlertTitle>{{ r.heading }}</h2>
                <p hlmAlertDescription>{{ r.text }}</p>
              </div>
              <a hlmBtn variant="outline" class="w-fit" [routerLink]="['/c', companyId(), 'inbox']"
                >Go to Tinfirm</a
              >
            } @else if (!token()) {
              <div hlmAlert data-testid="link-no-token">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <h2 hlmAlertTitle>This link is incomplete</h2>
                <p hlmAlertDescription>
                  Open it again from the Tinfirm bot's direct message, in the same browser tab you
                  sign in with.
                </p>
              </div>
            } @else if (linkRes.isLoading()) {
              <div class="flex flex-col gap-3" aria-busy="true">
                <span class="sr-only" role="status">Checking the link…</span>
                <hlm-skeleton class="h-4 w-2/3" />
                <hlm-skeleton class="h-4 w-1/2" />
                <hlm-skeleton class="h-16 w-full" />
              </div>
            } @else if (question(); as q) {
              @let link = linkRes.value()!;
              <div
                class="bg-muted/40 flex items-center gap-3 rounded-lg border p-3"
                data-testid="link-live"
                [attr.data-checked]="!!link.platformChecked"
              >
                @if (link.avatar) {
                  <img
                    class="size-10 shrink-0 rounded-md"
                    width="40"
                    height="40"
                    alt=""
                    data-testid="link-avatar"
                    [src]="link.avatar"
                  />
                }
                <p class="text-sm text-pretty" data-testid="link-live-name">{{ q.live }}</p>
              </div>
              <div class="flex flex-col gap-2 text-sm" data-testid="link-question">
                <p class="text-pretty" data-testid="link-who">{{ q.who }}</p>
                <p class="text-muted-foreground text-pretty" data-testid="link-when">
                  {{ q.when }}
                </p>
              </div>
              <div hlmAlert class="border-human" data-testid="link-warning">
                <ng-icon name="lucideShieldAlert" class="text-human" aria-hidden="true" />
                <h2 hlmAlertTitle>Confirm only if you just wrote to the bot yourself</h2>
                <p hlmAlertDescription>
                  @if (q.name) {
                    {{ q.name }}
                  }
                  Once linked, what this {{ platform() }} user writes to the bot acts as you, with
                  your accounts and budget.
                </p>
              </div>
              @if (q.waiting) {
                <p class="text-muted-foreground text-sm text-pretty" data-testid="link-waiting">
                  {{ q.waiting }}
                </p>
              }
              @if (link.ownerNotice) {
                <div hlmAlert data-testid="link-owner-notice">
                  <ng-icon name="lucideEye" aria-hidden="true" />
                  <h2 hlmAlertTitle>These conversations are personal</h2>
                  <p hlmAlertDescription>{{ link.ownerNotice }}</p>
                </div>
              }
              @if (confirmError(); as message) {
                <div hlmAlert variant="destructive" data-testid="link-error">
                  <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                  <p hlmAlertDescription>{{ message }}</p>
                </div>
              }
              <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                <button
                  hlmBtn
                  variant="outline"
                  type="button"
                  data-testid="link-decline"
                  [disabled]="confirming()"
                  (click)="decline()"
                >
                  It wasn't me
                </button>
                <button
                  hlmBtn
                  type="button"
                  data-testid="link-confirm"
                  [disabled]="confirming()"
                  (click)="confirm()"
                >
                  @if (confirming()) {
                    <hlm-spinner aria-hidden="true" />
                  } @else {
                    <ng-icon name="lucideLink2" aria-hidden="true" />
                  }
                  Link to my account
                </button>
              </div>
            }
          </div>
        </section>
      </div>
    </main>
  `,
})
export class ChatLinkPage {
  private readonly api = inject(Api);
  private readonly session = inject(Session);

  /** The route's `:companyId`. */
  readonly companyId = input.required<string>();

  /** Read once: the guard kept it from the fragment. */
  protected readonly token = computed(() => chatLinkToken(this.companyId()));

  protected readonly linkRes = resource({
    params: () => {
      const token = this.token();
      return token ? { companyId: this.companyId(), token } : undefined;
    },
    loader: ({ params }) =>
      this.api.invoke(readChatLink, {
        companyId: params.companyId,
        body: { token: params.token },
      }),
  });

  protected readonly linked = signal<ChatLink | null>(null);
  protected readonly declined = signal(false);
  protected readonly confirming = signal(false);
  protected readonly confirmError = signal<string | null>(null);
  /** A refusal of the read, or of the confirmation that used the link up. */
  private readonly confirmRefusal = signal<unknown>(null);

  private readonly refusalError = computed(
    () => this.confirmRefusal() ?? (this.linkRes.error() ? this.linkRes.error() : null),
  );
  protected readonly refusal = computed(() => {
    const error = this.refusalError();
    return error ? linkRefusal(error) : null;
  });
  protected readonly refusalCode = computed(() => toProblem(this.refusalError()).code ?? null);

  protected readonly platform = computed(() => {
    const link = this.linked() ?? (this.linkRes.hasValue() ? this.linkRes.value() : null);
    return link ? platformOf(link) : 'chat';
  });
  protected readonly question = computed(() =>
    this.linkRes.hasValue()
      ? linkQuestion(this.linkRes.value(), this.session.user()?.email ?? 'yours')
      : null,
  );
  protected readonly title = computed(() => {
    if (this.linked()) return 'Your chat account is linked';
    if (this.declined()) return 'Not linked';
    if (this.refusal()) return 'Link your chat account';
    return this.question()?.title ?? 'Link your chat account';
  });

  constructor() {
    // A link the server refuses to read (expired, used, not this caller's) is forgotten at once.
    effect(() => {
      const status = toProblem(this.linkRes.error()).status;
      if (this.linkRes.error() && status >= 400 && status < 500) {
        forgetChatLinkToken(this.companyId());
      }
    });
  }

  protected async confirm(): Promise<void> {
    const token = this.token();
    if (!token || this.confirming()) return;
    this.confirming.set(true);
    this.confirmError.set(null);
    try {
      const link = await this.api.invoke(confirmChatLink, {
        companyId: this.companyId(),
        body: { token },
      });
      forgetChatLinkToken(this.companyId());
      this.linked.set(link);
    } catch (error) {
      const problem = toProblem(error);
      // Every refusal but a lost connection used the link up (the server consumes it whatever).
      if (problem.status >= 400 && problem.status < 500) {
        forgetChatLinkToken(this.companyId());
        this.confirmRefusal.set(error);
      } else {
        this.confirmError.set(problemMessage(error));
      }
    } finally {
      this.confirming.set(false);
    }
  }

  protected decline(): void {
    forgetChatLinkToken(this.companyId());
    this.declined.set(true);
  }
}
