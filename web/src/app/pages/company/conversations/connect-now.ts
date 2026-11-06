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
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideClipboardPaste, lucideLogIn } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { startMyAccountSignIn, storeMyAccountToken } from '../../../api/generated/functions';
import type { SystemKind } from '../../../api/generated/models';
import { BrowserNavigation } from '../../../core/browser-navigation';
import { signInReturnTo } from '../../../shared/sign-in-result';
import { accountProblemMessage, MAX_TOKEN_LENGTH, tokenHint } from '../../../shared/token-hints';

let nextId = 0;

/**
 * Connect one's own account on a system before a conversation starts (plan §4.2: "Connect now, or
 * when the agent first needs it"): sign in at the system, coming back to this page
 * (`startMyAccountSignIn`), or paste a token (`storeMyAccountToken`). Optional: a conversation
 * that needs the system asks for it with a Connect card anyway.
 */
@Component({
  selector: 'app-connect-now',
  imports: [NgIcon, HlmButton, HlmInput, HlmSpinner],
  providers: [provideIcons({ lucideClipboardPaste, lucideLogIn })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', '[attr.data-system]': 'systemId()', 'data-testid': 'connect-now' },
  template: `
    @if (tokenOpen()) {
      <form class="flex flex-col gap-2" novalidate (submit)="connect($event)">
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
            data-testid="connect-token"
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
          </div>
        </div>
        <p class="text-muted-foreground text-xs" [id]="ids.hint">{{ hint().hint }}</p>
      </form>
    } @else {
      <div class="flex flex-wrap gap-2">
        @if (signInAvailable()) {
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            data-testid="connect-sign-in"
            [disabled]="busy()"
            (click)="signIn()"
          >
            <ng-icon name="lucideLogIn" aria-hidden="true" />
            Sign in with {{ systemName() }}
          </button>
        }
        <button
          hlmBtn
          variant="outline"
          size="sm"
          type="button"
          data-testid="connect-paste-token"
          [disabled]="busy()"
          (click)="tokenOpen.set(true)"
        >
          <ng-icon name="lucideClipboardPaste" aria-hidden="true" />
          Paste a token
        </button>
      </div>
    }
    @if (error(); as message) {
      <p class="text-destructive mt-2 text-sm" role="alert">{{ message }}</p>
    }
  `,
})
export class ConnectNow {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly navigation = inject(BrowserNavigation);

  readonly companyId = input.required<string>();
  readonly systemId = input.required<string>();
  readonly systemName = input.required<string>();
  readonly systemKind = input<SystemKind | null>(null);
  readonly signInAvailable = input(false);
  /** The account is stored; the page reads the readiness again. */
  readonly connected = output<void>();

  protected readonly ids = { token: `connect-${nextId}-token`, hint: `connect-${nextId++}-hint` };
  protected readonly maxTokenLength = MAX_TOKEN_LENGTH;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly tokenOpen = signal(false);
  protected readonly token = signal('');
  protected readonly hint = computed(() => tokenHint(this.systemKind()));
  /** The token's field: it takes the focus when the form opens, so the keyboard goes on there. */
  private readonly tokenField = viewChild<ElementRef<HTMLInputElement>>('tokenInput');

  constructor() {
    effect(() => {
      if (this.tokenOpen()) this.tokenField()?.nativeElement.focus();
    });
  }

  protected async connect(event: Event): Promise<void> {
    event.preventDefault();
    const token = this.token().trim();
    if (!token || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.invoke(storeMyAccountToken, {
        companyId: this.companyId(),
        systemId: this.systemId(),
        body: { token },
      });
      this.token.set('');
      this.tokenOpen.set(false);
      this.connected.emit();
    } catch (error) {
      this.error.set(accountProblemMessage(error, this.systemName()));
    } finally {
      this.busy.set(false);
    }
  }

  protected async signIn(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const start = await this.api.invoke(startMyAccountSignIn, {
        companyId: this.companyId(),
        systemId: this.systemId(),
        body: { returnTo: signInReturnTo(this.router) },
      });
      this.navigation.assign(start.authorizationUrl);
    } catch (error) {
      this.error.set(accountProblemMessage(error, this.systemName()));
      this.busy.set(false);
    }
  }
}
