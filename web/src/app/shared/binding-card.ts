import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleSlash,
  lucideClipboardPaste,
  lucideClock,
  lucideLogIn,
  lucidePlug,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../api/generated/api';
import {
  chooseConversationBinding,
  continueConversationBinding,
  startMyAccountSignIn,
  storeMyAccountToken,
} from '../api/generated/functions';
import type {
  ConversationBinding,
  ConversationBindingChoice,
  ConversationBindingChooseRequest,
} from '../api/generated/models';
import { hasProblemCode, problemMessage } from '../api/problem';
import { BrowserNavigation } from '../core/browser-navigation';
import { ACCESS_LABELS } from './labels';
import { type SignInResult, signInReturnTo } from './sign-in-result';
import { accountProblemMessage, MAX_TOKEN_LENGTH, tokenHint } from './token-hints';

type Reason = NonNullable<ConversationBinding['reason']>;

/** Waiting bindings the conversation's person acts on: orange (DESIGN.md, the scarce orange). */
const PERSON_REASONS: ReadonlySet<Reason> = new Set<Reason>([
  'NONE',
  'RECONNECT_YOURS',
  'CHOOSE',
  'PICK_GRANT',
]);

/** Why a binding can no longer be used, after "(…)". */
function goneWhy(b: ConversationBinding): string | null {
  switch (b.reason) {
    case 'GRANT_REMOVED':
      return 'the grant was removed';
    case 'LEFT_GROUP':
      return b.groupName
        ? `you are no longer in ${b.groupName}`
        : 'you left the group of its grant';
    case 'IDENTITY_CHANGED':
      return 'the account now signs in as someone else';
    case 'HOLDER_LEFT':
      return `${b.holderName ?? 'the account’s holder'} is gone`;
    case 'SYSTEM_REMOVED':
      return 'the system was removed';
    case 'ACCOUNT_REMOVED':
      return 'the trigger’s accounts no longer include one on it';
    case 'SUSPENDED':
      return `${b.holderName ?? 'its service account'} is suspended`;
    default:
      return null;
  }
}

/** What a trigger's author may no longer use: a service account with its credential, an account. */
function notEntitledWhat(b: ConversationBinding): string {
  if (b.holderName && b.credentialName) return `${b.holderName} with ${b.credentialName}`;
  if (b.holderName) return `${b.holderName}’s account`;
  return b.credentialName ? `the credential ${b.credentialName}` : 'its account';
}

export interface BindingCardText {
  readonly title: string;
  readonly body: string;
  /** The reader acts on it: orange. */
  readonly actionable: boolean;
}

/**
 * What an admin must do to the lent credential a binding waits for, as its state says: enable it,
 * store a new token or new client credentials, or sign it in again; the usual two when the binding
 * names no credential.
 */
function adminFix(b: ConversationBinding): string {
  const credential = b.credentialName
    ? `the credential ${b.credentialName}`
    : 'the lent credential';
  if (b.credentialEnabled === false) return `An admin must enable ${credential}.`;
  if (b.credentialKind === 'TOKEN') return `An admin must store a new token for ${credential}.`;
  if (b.credentialKind === 'OAUTH') return `An admin must sign ${credential} in again.`;
  if (b.credentialKind === 'CLIENT') {
    return `An admin must store new client credentials for ${credential}.`;
  }
  return `An admin must sign ${credential} in again, or store a new token for it.`;
}

/** What the card says, by `state` and `reason` (plan §4.1's table of binding cards). */
export function bindingCardText(
  b: ConversationBinding,
  context: { agentName: string; mine: boolean; personName?: string | null },
): BindingCardText | null {
  const system = b.systemName ?? 'This system';
  if (b.state === 'BOUND') return null;
  if (b.state === 'GONE') {
    const why = goneWhy(b);
    return {
      title: `${system} can no longer be used here`,
      body: `${system} can no longer be used in this conversation${why ? ` (${why})` : ''}.`,
      actionable: false,
    };
  }
  const reason = b.reason ?? 'NONE';
  if (PERSON_REASONS.has(reason) && !context.mine) {
    return {
      title: `Waiting for ${context.personName ?? 'its person'}`,
      body: `${context.personName ?? 'The conversation’s person'} connects ${system}, or chooses how to act on it.`,
      actionable: false,
    };
  }
  switch (reason) {
    case 'NONE':
      return {
        title: `Connect ${system} to continue`,
        body: `${context.agentName} asked to use ${system}. Connect your own account and the call goes on.`,
        actionable: true,
      };
    case 'RECONNECT_YOURS':
      return {
        title: `Sign in to ${system} again`,
        body: `Your ${system} account no longer works. Sign in again and the call goes on.`,
        actionable: true,
      };
    case 'PICK_GRANT':
      return {
        title: `Act on ${system} through:`,
        body: `${context.agentName} asked to use ${system}. Pick the grant it acts through in this conversation.`,
        actionable: true,
      };
    case 'CHOOSE': {
      const grant = b.choices.at(0);
      return {
        title: `Your ${system} account needs signing in again`,
        body: grant
          ? `Sign in again, or use the grant of ${grant.groupName ?? 'your group'} (${ACCESS_LABELS[grant.access]}).`
          : 'Sign in again, or use a grant of one of your groups.',
        actionable: true,
      };
    }
    case 'RECONNECT_COMPANY':
      return {
        title: `${system} waits for an admin`,
        body: `${adminFix(b)} Admins were told.`,
        actionable: false,
      };
    case 'SUSPENDED':
      return {
        title: `${system} waits for an admin`,
        body: `${b.holderName ?? 'The service account'} is suspended until an admin resumes it.`,
        actionable: false,
      };
    case 'SYSTEM_OFF':
      return {
        title: `${system} is switched off`,
        body: 'Personal MCP servers are switched off in this company.',
        actionable: false,
      };
    case 'NOT_ENTITLED':
      return {
        title: `${system} waits for the trigger’s author`,
        body: `The trigger’s author may no longer use ${notEntitledWhat(b)}. They or an admin change the trigger’s accounts.`,
        actionable: false,
      };
    default:
      return { title: b.text, body: '', actionable: false };
  }
}

let nextId = 0;

/**
 * A binding of a conversation that waits, or can no longer be used (plan §4.1): the Connect card.
 * It says what holds the conversation's use of a system and offers what the reader can do there:
 * sign in (returning to this page), paste a token, pick a grant, or connect their own instead. It
 * calls the API itself and hands the binding as it is now to `changed`. Orange only when the
 * reader acts; an admin's account to sign in again or a switched-off system stays neutral. A bound
 * binding draws nothing: the who-acts / who-pays panel says it.
 *
 * By `reason`: `NONE` and `RECONNECT_YOURS` connect (sign in when `signInAvailable`, or paste a
 * token, then `continueConversationBinding`); `PICK_GRANT` picks a grant or connects one's own
 * instead; `CHOOSE` signs in again or takes the grant; `RECONNECT_COMPANY`, `SUSPENDED`,
 * `SYSTEM_OFF` and `NOT_ENTITLED` wait for someone else; a `GONE` binding says why and offers a new
 * conversation. A sign-in comes back to `returnTo` (this page by default) with `?account=`, where
 * the page continues the binding with `continueBindingAfterSignIn`.
 */
@Component({
  selector: 'app-binding-card',
  imports: [RouterLink, NgIcon, HlmButton, HlmInput, HlmSpinner],
  providers: [
    provideIcons({ lucideCircleSlash, lucideClipboardPaste, lucideClock, lucideLogIn, lucidePlug }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @if (text(); as t) {
      <section
        class="flex flex-col gap-3 text-sm"
        data-testid="binding-card"
        [class]="inline() ? '' : 'bg-card rounded-xl border px-3.5 py-3'"
        [class.border-human]="!inline() && t.actionable"
        [attr.id]="'binding-' + binding().systemId"
        [attr.data-state]="binding().state"
        [attr.data-reason]="binding().reason ?? null"
        [attr.data-appearance]="inline() ? 'inline' : null"
        [attr.aria-labelledby]="inline() ? null : ids.title"
      >
        @if (inline()) {
          @if (t.body) {
            <p class="text-pretty">{{ t.body }}</p>
          }
        } @else {
          <div class="flex items-start gap-2.5">
            <ng-icon
              class="mt-0.5 shrink-0"
              aria-hidden="true"
              [name]="icon()"
              [class]="t.actionable ? 'text-human' : 'text-muted-foreground'"
            />
            <div class="flex min-w-0 flex-col gap-0.5">
              <h3
                class="font-semibold text-pretty"
                [id]="ids.title"
                [class.text-human]="t.actionable"
              >
                {{ t.title }}
              </h3>
              @if (t.body) {
                <p class="text-muted-foreground text-pretty">{{ t.body }}</p>
              }
            </div>
          </div>
        }

        @if (t.actionable) {
          @if (offersGrants()) {
            <ul class="flex flex-col gap-1.5" aria-label="Grants to act through">
              @for (choice of binding().choices; track choice.grantId) {
                <li>
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    class="h-auto min-h-8 max-w-full justify-start py-1 text-start whitespace-normal"
                    data-testid="binding-grant"
                    [disabled]="busy()"
                    (click)="choose({ grantId: choice.grantId })"
                  >
                    {{ choiceLabel(choice) }}
                  </button>
                </li>
              }
            </ul>
          }

          @if (offersConnect()) {
            @if (inline() && signInAvailable()) {
              <div>
                <button
                  hlmBtn
                  size="sm"
                  type="button"
                  class="max-md:h-11"
                  data-testid="binding-sign-in"
                  [disabled]="busy()"
                  (click)="signIn()"
                >
                  <ng-icon name="lucideLogIn" aria-hidden="true" />
                  Sign in with {{ systemName() }}
                </button>
              </div>
            }
            @if (tokenOpen() || inline()) {
              <form class="flex flex-col gap-2" novalidate (submit)="connectWithToken($event)">
                <label class="text-xs font-semibold" [for]="ids.token"
                  >{{ hint().label }} for {{ systemName() }}</label
                >
                <div class="flex flex-col gap-2 sm:flex-row">
                  <input
                    #tokenInput
                    hlmInput
                    type="password"
                    autocomplete="off"
                    spellcheck="false"
                    class="min-w-0 flex-1 font-mono"
                    data-testid="binding-token"
                    [id]="ids.token"
                    [attr.maxlength]="maxTokenLength"
                    [attr.aria-describedby]="ids.hint"
                    [placeholder]="hint().placeholder"
                    [value]="token()"
                    [disabled]="busy()"
                    (input)="token.set(tokenInput.value)"
                  />
                  <div class="flex gap-2">
                    <button
                      hlmBtn
                      size="sm"
                      type="submit"
                      class="h-9"
                      [disabled]="busy() || !token().trim()"
                    >
                      @if (busy()) {
                        <hlm-spinner aria-hidden="true" />
                      }
                      Connect
                    </button>
                    @if (!inline()) {
                      <button
                        hlmBtn
                        variant="ghost"
                        size="sm"
                        type="button"
                        class="h-9"
                        [disabled]="busy()"
                        (click)="tokenOpen.set(false)"
                      >
                        Cancel
                      </button>
                    }
                  </div>
                </div>
                <p class="text-muted-foreground text-xs" [id]="ids.hint">
                  {{ hint().hint }} Tinfirm checks it with {{ systemName() }} and keeps it in its
                  vault; it is never shown again.
                </p>
              </form>
            } @else {
              <div class="flex flex-wrap gap-2">
                @if (signInAvailable()) {
                  <button
                    hlmBtn
                    size="sm"
                    type="button"
                    data-testid="binding-sign-in"
                    [disabled]="busy()"
                    (click)="signIn()"
                  >
                    <ng-icon name="lucideLogIn" aria-hidden="true" />
                    Sign in with {{ systemName() }}
                  </button>
                }
                <button
                  hlmBtn
                  size="sm"
                  type="button"
                  data-testid="binding-paste-token"
                  [variant]="signInAvailable() ? 'outline' : 'default'"
                  [disabled]="busy()"
                  (click)="tokenOpen.set(true)"
                >
                  <ng-icon name="lucideClipboardPaste" aria-hidden="true" />
                  Paste a token
                </button>
              </div>
            }
          }

          @if (binding().reason === 'PICK_GRANT' || binding().reason === 'CHOOSE') {
            <div>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                data-testid="binding-reconnect"
                [disabled]="busy()"
                (click)="choose({ reconnect: true })"
              >
                {{ binding().reason === 'CHOOSE' ? 'Sign in again' : 'Connect my own instead' }}
              </button>
            </div>
          }
        }

        @if (binding().state === 'GONE') {
          <div>
            <a
              hlmBtn
              variant="outline"
              size="sm"
              data-testid="binding-new-conversation"
              [routerLink]="['/c', companyId(), 'conversations', 'new']"
              [queryParams]="agentId() ? { agentId: agentId() } : {}"
              >Start a new conversation with {{ agentName() }}</a
            >
          </div>
        }

        @if (error(); as message) {
          <p class="text-destructive text-sm" role="alert" data-testid="binding-error">
            {{ message }}
          </p>
        }
      </section>
    }
  `,
})
export class BindingCard {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly navigation = inject(BrowserNavigation);

  readonly binding = input.required<ConversationBinding>();
  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();
  readonly agentName = input.required<string>();
  readonly agentId = input<string | null>(null);
  /**
   * Whether the system's app lets the person sign in (`MyAccount.oauthAvailable`): the binding
   * itself does not say it. Without it the card offers a token only.
   */
  readonly signInAvailable = input(false);
  /** Whether the reader is the conversation's person; the Owner reading it only sees it wait. */
  readonly mine = input(true);
  readonly personName = input<string | null>(null);
  /** The path a sign-in comes back to; the page the card is on by default. */
  readonly returnTo = input<string | null>(null);
  /**
   * `inline`: the body alone, without the frame and the title, the token's field open, for a
   * host that draws them (the inbox's open row).
   */
  readonly appearance = input<'card' | 'inline'>('card');
  protected readonly inline = computed(() => this.appearance() === 'inline');

  /** The binding as the server answered after an action of the card. */
  readonly changed = output<ConversationBinding>();
  /** The binding was settled in the meantime (`BINDING_NOT_WAITING`): the page reads it again. */
  readonly outdated = output<void>();

  protected readonly ids = {
    title: `binding-${nextId}-title`,
    token: `binding-${nextId}-token`,
    hint: `binding-${nextId++}-hint`,
  };
  protected readonly maxTokenLength = MAX_TOKEN_LENGTH;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly tokenOpen = signal(false);
  protected readonly token = signal('');
  /** The token's field: it takes the focus when the form opens, so the keyboard goes on there. */
  private readonly tokenField = viewChild<ElementRef<HTMLInputElement>>('tokenInput');

  constructor() {
    effect(() => {
      if (this.tokenOpen()) this.tokenField()?.nativeElement.focus();
    });
  }

  protected readonly text = computed(() =>
    bindingCardText(this.binding(), {
      agentName: this.agentName(),
      mine: this.mine(),
      personName: this.personName(),
    }),
  );
  protected readonly systemName = computed(() => this.binding().systemName ?? 'the system');
  protected readonly hint = computed(() => tokenHint(this.binding().systemKind));
  protected readonly offersConnect = computed(() => {
    const reason = this.binding().reason;
    return reason === 'NONE' || reason === 'RECONNECT_YOURS';
  });
  protected readonly offersGrants = computed(() => {
    const reason = this.binding().reason;
    return (reason === 'PICK_GRANT' || reason === 'CHOOSE') && this.binding().choices.length > 0;
  });
  protected readonly icon = computed(() => {
    if (this.binding().state === 'GONE') return 'lucideCircleSlash';
    return this.text()?.actionable ? 'lucidePlug' : 'lucideClock';
  });

  protected choiceLabel(choice: ConversationBindingChoice): string {
    const lent = choice.credentialName
      ? `the credential ${choice.credentialName}`
      : 'a lent credential';
    return `${choice.groupName ?? 'Your group'}: ${lent}, ${ACCESS_LABELS[choice.access]}`;
  }

  /** Pick a grant, or turn the binding into one that waits for the person's own account. */
  protected choose(body: ConversationBindingChooseRequest): Promise<void> {
    return this.run(() => this.api.invoke(chooseConversationBinding, { ...this.params(), body }));
  }

  /** Store the person's token, then resolve the binding again. */
  protected connectWithToken(event: Event): Promise<void> {
    event.preventDefault();
    const token = this.token().trim();
    if (!token) return Promise.resolve();
    return this.run(async () => {
      try {
        await this.api.invoke(storeMyAccountToken, {
          companyId: this.companyId(),
          systemId: this.binding().systemId,
          body: { token },
        });
      } catch (error) {
        throw new AccountProblem(accountProblemMessage(error, this.systemName()));
      }
      this.token.set('');
      this.tokenOpen.set(false);
      return this.api.invoke(continueConversationBinding, this.params());
    });
  }

  /**
   * Sign in at the system; its callback comes back to `returnTo` with `?account=connected&system=`,
   * where the page calls `continueConversationBinding` (plan §4.1).
   */
  protected async signIn(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const start = await this.api.invoke(startMyAccountSignIn, {
        companyId: this.companyId(),
        systemId: this.binding().systemId,
        body: { returnTo: this.returnTo() ?? signInReturnTo(this.router) },
      });
      this.navigation.assign(start.authorizationUrl);
    } catch (error) {
      this.error.set(accountProblemMessage(error, this.systemName()));
      this.busy.set(false);
    }
  }

  private params() {
    return {
      companyId: this.companyId(),
      conversationId: this.conversationId(),
      systemId: this.binding().systemId,
    };
  }

  private async run(action: () => Promise<ConversationBinding>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.changed.emit(await action());
    } catch (error) {
      if (hasProblemCode(error, 'BINDING_NOT_WAITING')) {
        // Settled in the meantime: the server continues a waiting binding itself when an account is connected
        // (package 1's events), so its own continue can come first. The page reads the conversation again.
        this.outdated.emit();
        return;
      }
      this.error.set(error instanceof AccountProblem ? error.message : problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}

/** A failed connect, already said in the card's words. */
class AccountProblem extends Error {}

/**
 * Back from a sign-in that a Connect card started (plan §4.1): when it connected the system of one
 * of the conversation's waiting bindings, continue that binding, and answer it as it is now; null
 * when the sign-in failed, named no waiting binding, or the server had continued it already. The page reads the outcome with
 * `signInReturn()`, says it, and replaces the binding with the answer.
 */
export async function continueBindingAfterSignIn(
  api: Api,
  where: { companyId: string; conversationId: string },
  result: SignInResult | null,
  bindings: readonly ConversationBinding[],
): Promise<ConversationBinding | null> {
  if (!result?.connected || !result.systemId) return null;
  const waiting = bindings.find((b) => b.systemId === result.systemId && b.state === 'WAITING');
  if (!waiting) return null;
  try {
    return await api.invoke(continueConversationBinding, { ...where, systemId: waiting.systemId });
  } catch (error) {
    // The server continued it itself when the account was connected: nothing more to do here.
    if (hasProblemCode(error, 'BINDING_NOT_WAITING')) return null;
    throw error;
  }
}
