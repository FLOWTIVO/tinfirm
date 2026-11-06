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
import { confirmGmailLink, readGmailLink } from '../../api/generated/functions';
import type { GmailLink, GmailLinkRequest } from '../../api/generated/models';
import { problemMessage, toProblem } from '../../api/problem';
import { Session } from '../../core/session';
import { Logo } from '../../shared/logo';
import { formatClock, formatRelative } from '../../shared/time';
import { forgetGmailLinkToken, gmailLinkToken } from './gmail-link-token';

/** Why a link cannot be confirmed, in the words of this page: a heading and what to do. */
export function gmailLinkRefusal(error: unknown): { heading: string; text: string } {
  const problem = toProblem(error);
  switch (problem.code) {
    case 'GMAIL_LINK_EXPIRED':
      return {
        heading: 'This link has expired',
        text: 'A link works for 10 minutes. Choose Sign in to Tinfirm in Gmail again for a new one.',
      };
    case 'GMAIL_LINK_USED':
      return {
        heading: 'This link was already used',
        text: 'A link works once. If Gmail still asks you to sign in, choose Sign in to Tinfirm there again.',
      };
    case 'IDENTITY_IN_USE':
      return {
        heading: 'This Google account is someone else’s',
        text: 'Another member of this company linked this Google account. Nothing was linked; the link is used up.',
      };
    default:
      if (problem.status === 404) {
        return {
          heading: 'This link is not for this account',
          text: 'It is unknown, replaced by a newer one, for another company, or you are not a member of the company it links to. Check that you are signed in as yourself.',
        };
      }
      return { heading: 'Couldn’t check this link', text: problemMessage(error) };
  }
}

/** What the page asks: which Google account, to which Tinfirm account, asked for when. */
export function gmailLinkQuestion(
  link: GmailLinkRequest,
  email: string,
  now: Date = new Date(),
): { who: string; when: string } {
  const account = link.googleEmail
    ? `The Google account ${link.googleEmail}`
    : 'This Google account';
  const asked = new Date(link.requestedAt);
  return {
    who: `${account}, in Gmail, to your Tinfirm account ${email}.`,
    when: `Asked for this link at ${formatClock(asked, now)}, ${formatRelative(asked, now)}.`,
  };
}

/**
 * The Gmail add-on's sign-in link (ADR-0083, decision 3): `/gmail/link/{companyId}#{token}`,
 * which the add-on opens in a window for a Google account that is not linked. After the Tinfirm
 * sign-in, it names the Google account (its email, as Google asserted it) and when the link was
 * asked for, warns to confirm only a link the person just asked for, says who reads such
 * conversations, and links on the person's confirmation only. Once linked, it tries to close its
 * window, which reloads the add-on in Gmail.
 */
@Component({
  selector: 'app-gmail-link',
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
          aria-label="Link your Google account"
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
                    {{ done.googleEmail ?? 'The Google account' }} is linked to your account. What
                    you ask agents in Gmail are your own conversations, with your own accounts and
                    budget.
                  </p>
                  <p class="text-muted-foreground text-pretty">
                    Close this window to go back to Gmail. You can disconnect the account on your
                    profile in Tinfirm at any time.
                  </p>
                </div>
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
                  Choose Sign in to Tinfirm in Gmail again, and sign in in the window it opens.
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
              <div class="flex flex-col gap-2 text-sm" data-testid="link-question">
                <p class="text-pretty" data-testid="link-who">{{ q.who }}</p>
                <p class="text-muted-foreground text-pretty" data-testid="link-when">
                  {{ q.when }}
                </p>
              </div>
              <div hlmAlert class="border-human" data-testid="link-warning">
                <ng-icon name="lucideShieldAlert" class="text-human" aria-hidden="true" />
                <h2 hlmAlertTitle>Confirm only if you just chose Sign in to Tinfirm in Gmail</h2>
                <p hlmAlertDescription>
                  Once linked, what this Google account asks in Gmail acts as you, with your
                  accounts and budget.
                </p>
              </div>
              <div hlmAlert data-testid="link-owner-notice">
                <ng-icon name="lucideEye" aria-hidden="true" />
                <h2 hlmAlertTitle>These conversations are personal</h2>
                <p hlmAlertDescription>{{ link.ownerNotice }}</p>
              </div>
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
export class GmailLinkPage {
  private readonly api = inject(Api);
  private readonly session = inject(Session);

  /** The route's `:companyId`. */
  readonly companyId = input.required<string>();

  /** Read once: the guard kept it from the fragment. */
  protected readonly token = computed(() => gmailLinkToken(this.companyId()));

  protected readonly linkRes = resource({
    params: () => {
      const token = this.token();
      return token ? { companyId: this.companyId(), token } : undefined;
    },
    loader: ({ params }) =>
      this.api.invoke(readGmailLink, {
        companyId: params.companyId,
        body: { token: params.token },
      }),
  });

  protected readonly linked = signal<GmailLink | null>(null);
  protected readonly declined = signal(false);
  protected readonly confirming = signal(false);
  protected readonly confirmError = signal<string | null>(null);
  /** A refusal of the confirmation, which used the link up. */
  private readonly confirmRefusal = signal<unknown>(null);

  private readonly refusalError = computed(
    () => this.confirmRefusal() ?? (this.linkRes.error() ? this.linkRes.error() : null),
  );
  protected readonly refusal = computed(() => {
    const error = this.refusalError();
    return error ? gmailLinkRefusal(error) : null;
  });
  protected readonly refusalCode = computed(() => toProblem(this.refusalError()).code ?? null);

  protected readonly question = computed(() =>
    this.linkRes.hasValue()
      ? gmailLinkQuestion(this.linkRes.value(), this.session.user()?.email ?? 'yours')
      : null,
  );
  protected readonly title = computed(() => {
    if (this.linked()) return 'Your Google account is linked';
    if (this.declined()) return 'Not linked';
    if (this.refusal()) return 'Link your Google account';
    return 'Link your Google account to Tinfirm?';
  });

  constructor() {
    // A link the server refuses to read (expired, used, not this caller's) is forgotten at once.
    effect(() => {
      const status = toProblem(this.linkRes.error()).status;
      if (this.linkRes.error() && status >= 400 && status < 500) {
        forgetGmailLinkToken(this.companyId());
      }
    });
  }

  protected async confirm(): Promise<void> {
    const token = this.token();
    if (!token || this.confirming()) return;
    this.confirming.set(true);
    this.confirmError.set(null);
    try {
      const link = await this.api.invoke(confirmGmailLink, {
        companyId: this.companyId(),
        body: { token },
      });
      forgetGmailLinkToken(this.companyId());
      this.linked.set(link);
      // Gmail opened this window: closing it reloads the add-on. A browser may refuse, and the
      // page says to close it.
      try {
        globalThis.close();
      } catch {
        // The page stays, and asks to be closed.
      }
    } catch (error) {
      const problem = toProblem(error);
      // Every refusal but a lost connection used the link up (the server consumes it whatever).
      if (problem.status >= 400 && problem.status < 500) {
        forgetGmailLinkToken(this.companyId());
        this.confirmRefusal.set(error);
      } else {
        this.confirmError.set(problemMessage(error));
      }
    } finally {
      this.confirming.set(false);
    }
  }

  protected decline(): void {
    forgetGmailLinkToken(this.companyId());
    this.declined.set(true);
  }
}
