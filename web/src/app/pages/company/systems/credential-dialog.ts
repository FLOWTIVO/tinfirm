import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideLogIn } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  addSystemCredential,
  addSystemCredentialClient,
  startSystemCredentialSignIn,
  startSystemCredentialSignInAgain,
  storeSystemCredentialClient,
  storeSystemCredentialToken,
} from '../../../api/generated/functions';
import type { ExternalSystem, SystemCredential } from '../../../api/generated/models';
import { toProblem } from '../../../api/problem';
import { BrowserNavigation } from '../../../core/browser-navigation';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { signInReturnTo } from '../../../shared/sign-in-result';
import { MAX_TOKEN_LENGTH } from '../../../shared/token-hints';
import { credentialProblem, takesClient, takesSignIn, takesToken } from './credential-labels';
import { systemKind } from './system-kinds';

/** The longest name of a credential (`SystemCredentialCreateRequest.name`). */
export const MAX_CREDENTIAL_NAME = 100;

/** The longest client id and client secret (`AccountClientRequest`). */
export const MAX_CLIENT_ID = 500;
export const MAX_CLIENT_SECRET = 2000;

type Method = 'token' | 'sign-in' | 'client';

const METHODS: readonly Method[] = ['token', 'sign-in', 'client'];

/**
 * Add a credential to a company system, or give one a new token or sign it in again (ADR-0072): a
 * name, unique on the system, then a token of the user the credential is at the system (checked
 * with the system before it is kept), a sign-in with the system's app as that user, which comes
 * back to this page (`?account=`), or, on Jira Cloud, an Atlassian service account's OAuth 2.0
 * credential (its client id and secret), which mints its own access tokens. A new credential is
 * lent to nobody yet. The tools section opens
 * it too, when the server asks for a credential none was given for.
 */
@Component({
  selector: 'app-credential-dialog',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    Segmented,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideLogIn })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-md"
        data-testid="credential-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>{{ title() }}</h2>
          <p hlmDialogDescription>
            @if (credential(); as c) {
              {{ c.name }} keeps its name, the groups and service accounts it is lent to, and its
              id, so triggers that act with it go on.
            } @else {
              A credential is the company’s: whoever it is lent to acts as its user at
              {{ system().name }}. Use a user of the company’s own, such as a machine user. It is
              lent to nobody until you lend it.
            }
          </p>
        </hlm-dialog-header>

        <form
          id="credential-form"
          class="flex flex-col gap-4"
          novalidate
          (submit)="onSubmit($event)"
        >
          @if (error(); as message) {
            <div hlmAlert variant="destructive" data-testid="credential-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription class="break-words">{{ message }}</p>
            </div>
          }

          @if (!credential()) {
            <div hlmField>
              <label hlmFieldLabel for="credential-name">Name</label>
              <input
                hlmInput
                id="credential-name"
                autocomplete="off"
                data-testid="credential-name"
                [attr.maxlength]="maxName"
                [placeholder]="namePlaceholder()"
                [value]="name()"
                (input)="name.set($any($event.target).value)"
              />
              <p hlmFieldDescription>
                What it is for, as the system’s page and the trigger editor name it.
              </p>
              @if (nameError(); as message) {
                <hlm-field-error forceShow data-testid="credential-name-error">{{
                  message
                }}</hlm-field-error>
              }
            </div>
          }

          @if (methods().length > 1) {
            <div hlmField>
              <span hlmFieldLabel id="credential-method-label">How</span>
              <app-segmented
                inputId="credential-method"
                labelledBy="credential-method-label"
                [options]="methodOptions()"
                [value]="method()"
                (valueChange)="setMethod($event)"
              />
            </div>
          }

          @if (method() === 'token') {
            <div hlmField>
              <label hlmFieldLabel for="credential-token">Token</label>
              <input
                hlmInput
                id="credential-token"
                type="password"
                class="font-mono"
                autocomplete="off"
                spellcheck="false"
                data-testid="credential-token"
                [attr.maxlength]="maxToken"
                [value]="token()"
                (input)="token.set($any($event.target).value)"
              />
              <p hlmFieldDescription>
                {{ tokenHint() }} Tinfirm checks it with {{ system().name }} and keeps it in the
                vault; nobody sees it again.
              </p>
              @if (tokenError(); as message) {
                <hlm-field-error forceShow data-testid="credential-token-error">{{
                  message
                }}</hlm-field-error>
              }
            </div>
          } @else if (method() === 'client') {
            <div hlmField>
              <label hlmFieldLabel for="credential-client-id">Client ID</label>
              <input
                hlmInput
                id="credential-client-id"
                class="font-mono"
                autocomplete="off"
                spellcheck="false"
                data-testid="credential-client-id"
                [attr.maxlength]="maxClientId"
                [value]="clientId()"
                (input)="clientId.set($any($event.target).value)"
              />
              @if (clientIdError(); as message) {
                <hlm-field-error forceShow data-testid="credential-client-id-error">{{
                  message
                }}</hlm-field-error>
              }
            </div>
            <div hlmField>
              <label hlmFieldLabel for="credential-client-secret">Client secret</label>
              <input
                hlmInput
                id="credential-client-secret"
                type="password"
                class="font-mono"
                autocomplete="off"
                spellcheck="false"
                data-testid="credential-client-secret"
                [attr.maxlength]="maxClientSecret"
                [value]="clientSecret()"
                (input)="clientSecret.set($any($event.target).value)"
              />
              <p hlmFieldDescription>
                An Atlassian service account’s OAuth 2.0 credential, from Atlassian Administration,
                Directory, Service accounts. Tinfirm mints its access tokens itself and keeps the
                secret in the vault; nobody sees it again.
              </p>
              @if (clientSecretError(); as message) {
                <hlm-field-error forceShow data-testid="credential-client-secret-error">{{
                  message
                }}</hlm-field-error>
              }
            </div>
          } @else if (method() === 'sign-in') {
            <p class="text-muted-foreground text-sm text-pretty" data-testid="credential-sign-in">
              You sign in at {{ system().name }} as the user the credential should be, then come
              back to this page.
            </p>
          } @else {
            <p class="text-muted-foreground text-sm text-pretty" data-testid="credential-no-method">
              @if (system().kind === 'SLACK') {
                {{ system().name }} takes a credential by signing in only, once its sign-in app is
                set up.
              } @else {
                {{ system().name }} takes no credential here: a chat’s bot is set under Bot.
              }
            </p>
          }
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="credential-form"
            data-testid="credential-save"
            [disabled]="busy() || !method()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            } @else if (method() === 'sign-in') {
              <ng-icon name="lucideLogIn" aria-hidden="true" />
            }
            {{ saveLabel() }}
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class CredentialDialog {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly browser = inject(BrowserNavigation);

  readonly open = model(false);
  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  /** The credential to store a new token for or sign in again; null adds one. */
  readonly credential = input<SystemCredential | null>(null);
  /** The credential as the server keeps it now, after a token was taken. */
  readonly saved = output<SystemCredential>();

  protected readonly maxName = MAX_CREDENTIAL_NAME;
  protected readonly maxToken = MAX_TOKEN_LENGTH;
  protected readonly maxClientId = MAX_CLIENT_ID;
  protected readonly maxClientSecret = MAX_CLIENT_SECRET;
  protected readonly name = signal('');
  protected readonly token = signal('');
  protected readonly clientId = signal('');
  protected readonly clientSecret = signal('');
  protected readonly method = signal<Method | ''>('');
  protected readonly submitted = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** A refusal that names the field: a name taken, or a token the system refused. */
  private readonly nameTaken = signal<string | null>(null);

  /** The ways the system takes a credential: a token, a sign-in, client credentials. */
  protected readonly methods = computed<Method[]>(() => {
    const system = this.system();
    const methods: Method[] = [];
    if (takesToken(system)) methods.push('token');
    if (takesSignIn(system)) methods.push('sign-in');
    if (takesClient(system)) methods.push('client');
    return methods;
  });
  protected readonly methodOptions = computed<SegmentedOption[]>(() =>
    this.methods().map((m) => ({
      value: m,
      label:
        m === 'token'
          ? 'Paste a token'
          : m === 'client'
            ? 'Service account (OAuth 2.0)'
            : `Sign in to ${this.system().name}`,
    })),
  );
  protected readonly title = computed(() => {
    const credential = this.credential();
    if (!credential) return `Add a credential to ${this.system().name}`;
    switch (this.method()) {
      case 'sign-in':
        return `Sign ${credential.name} in again`;
      case 'client':
        return `Store new client credentials for ${credential.name}`;
      default:
        return `Store a new token for ${credential.name}`;
    }
  });
  protected readonly saveLabel = computed(() => {
    if (this.method() === 'sign-in') return `Sign in to ${this.system().name}`;
    return this.credential() ? 'Check and store' : 'Check and add';
  });
  protected readonly namePlaceholder = computed(
    () => `${this.system().kind === 'MCP' ? 'Server' : 'Bot'} token`,
  );
  protected readonly tokenHint = computed(() => {
    const system = this.system();
    const hint = systemKind(system.kind).tokenHint;
    return system.kind === 'MCP' && system.authHeader
      ? `${hint} It is sent in the ${system.authHeader} header.`
      : hint;
  });

  protected readonly nameError = computed(() => {
    if (this.nameTaken()) return this.nameTaken();
    if (!this.submitted()) return null;
    const name = this.name().trim();
    if (!name) return 'Name the credential.';
    return name.length > MAX_CREDENTIAL_NAME ? `At most ${MAX_CREDENTIAL_NAME} characters.` : null;
  });
  protected readonly tokenError = computed(() => {
    if (!this.submitted() || this.method() !== 'token') return null;
    return this.token().trim() ? null : 'Paste the token.';
  });
  protected readonly clientIdError = computed(() => {
    if (!this.submitted() || this.method() !== 'client') return null;
    return this.clientId().trim() ? null : 'Paste the client ID.';
  });
  protected readonly clientSecretError = computed(() => {
    if (!this.submitted() || this.method() !== 'client') return null;
    return this.clientSecret().trim() ? null : 'Paste the client secret.';
  });

  constructor() {
    effect(() => {
      if (!this.open()) return;
      untracked(() => {
        const credential = this.credential();
        const methods = this.methods();
        const preferred: Method =
          credential?.kind === 'OAUTH'
            ? 'sign-in'
            : credential?.kind === 'CLIENT'
              ? 'client'
              : 'token';
        this.method.set(methods.includes(preferred) ? preferred : (methods[0] ?? ''));
        this.name.set('');
        this.token.set('');
        this.clientId.set('');
        this.clientSecret.set('');
        this.submitted.set(false);
        this.busy.set(false);
        this.error.set(null);
        this.nameTaken.set(null);
      });
    });
  }

  protected setMethod(value: string): void {
    const method = METHODS.find((m) => m === value);
    if (method) this.method.set(method);
    this.error.set(null);
  }

  protected async onSubmit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.submitted.set(true);
    this.nameTaken.set(null);
    this.error.set(null);
    const credential = this.credential();
    if (
      (!credential && this.nameError()) ||
      this.tokenError() ||
      this.clientIdError() ||
      this.clientSecretError() ||
      !this.method()
    ) {
      return;
    }
    const key = { companyId: this.companyId(), systemId: this.system().id };
    const name = this.name().trim();
    this.busy.set(true);
    try {
      if (this.method() === 'sign-in') {
        const returnTo = signInReturnTo(this.router);
        const start = credential
          ? await this.api.invoke(startSystemCredentialSignInAgain, {
              ...key,
              credentialId: credential.id,
              body: { returnTo },
            })
          : await this.api.invoke(startSystemCredentialSignIn, {
              ...key,
              body: { name, returnTo },
            });
        this.browser.assign(start.authorizationUrl);
        return;
      }
      if (this.method() === 'client') {
        const client = {
          clientId: this.clientId().trim(),
          clientSecret: this.clientSecret().trim(),
        };
        const saved = credential
          ? await this.api.invoke(storeSystemCredentialClient, {
              ...key,
              credentialId: credential.id,
              body: client,
            })
          : await this.api.invoke(addSystemCredentialClient, {
              ...key,
              body: { name, ...client },
            });
        this.clientSecret.set('');
        this.open.set(false);
        this.saved.emit(saved);
        this.busy.set(false);
        return;
      }
      const token = this.token().trim();
      const saved = credential
        ? await this.api.invoke(storeSystemCredentialToken, {
            ...key,
            credentialId: credential.id,
            body: { token },
          })
        : await this.api.invoke(addSystemCredential, { ...key, body: { name, token } });
      this.token.set('');
      this.open.set(false);
      this.saved.emit(saved);
      this.busy.set(false);
    } catch (error) {
      const message = credentialProblem(error, this.system().name);
      if (toProblem(error).code === 'DUPLICATE_CREDENTIAL_NAME' && !credential) {
        this.nameTaken.set(message);
      } else {
        this.error.set(message);
      }
      this.busy.set(false);
    }
  }
}
