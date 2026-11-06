import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  resource,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideEye, lucideShieldAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { firstValueFrom, map } from 'rxjs';
import { Api } from '../../api/generated/api';
import { getOAuthConsent } from '../../api/generated/functions';
import type { OAuthConsentRequest, OAuthScope, OAuthScopeName } from '../../api/generated/models';
import { problemMessage } from '../../api/problem';
import { Logo } from '../../shared/logo';
import { ownerNames } from '../../shared/owner-notice';
import { BrowserNavigation } from '../../core/browser-navigation';
import { skipAuthRedirect } from '../../core/http-context';

/** The scopes in order: each includes the ones before it (ADR-0045). */
export const SCOPE_ORDER: readonly OAuthScopeName[] = [
  'tinfirm:read',
  'tinfirm:write',
  'tinfirm:decide',
];

const LEVEL_LABELS: Record<OAuthScopeName, string> = {
  'tinfirm:read': 'Read',
  'tinfirm:write': 'Read and write',
  'tinfirm:decide': 'Read, write and decide',
};

/**
 * The level the page selects at first: the highest requested scope, below `decide`. A client that
 * asks for deciding gets write preselected, and a request that names no level gets read. Deciding is
 * never preselected: the person picks it on purpose (CONVENTIONS §14.11, separation of duties).
 */
export function defaultLevel(requested: readonly OAuthScopeName[]): OAuthScopeName {
  const highest = Math.max(0, ...requested.map((scope) => SCOPE_ORDER.indexOf(scope)));
  return SCOPE_ORDER[Math.min(highest, SCOPE_ORDER.indexOf('tinfirm:write'))];
}

/** The scopes up to and including the chosen level, lowest first, as the consent posts them. */
export function scopesUpTo(level: OAuthScopeName): OAuthScopeName[] {
  return SCOPE_ORDER.slice(0, SCOPE_ORDER.indexOf(level) + 1);
}

/** The answer of `POST /oauth2/authorize` to the page's request: where the browser goes next. */
interface AuthorizationAnswer {
  redirectUri: string;
}

/**
 * The consent page of Tinfirm's OAuth authorization server (ADR-0045), `/authorize?client_id=&
 * scope=&state=`, where the server sends the browser after the login. It names the client and the
 * domain of its metadata document, the company, the Owner's notice (who can read the conversations
 * the client starts; security review, row 18), and what each level lets the client do. All three
 * levels are offered whatever the client asked for, with the requested one preselected (never
 * deciding); the person allows a level or denies. The answer is posted to `/oauth2/authorize` with the CSRF header; the
 * server answers with the client's redirect URI, and the page navigates there (a form would be
 * stopped by the CSP's `form-action 'self'` when it redirects to the client's loopback address).
 */
@Component({
  selector: 'app-authorize',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmCardImports, HlmSkeleton, HlmSpinner, Logo],
  providers: [provideIcons({ lucideCircleAlert, lucideEye, lucideShieldAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bg-background flex min-h-svh flex-col items-center justify-center px-4 py-10">
      <div class="w-full max-w-md">
        <div class="mb-6 flex flex-col items-center gap-3 text-center">
          <app-logo class="h-12" />
          <h1 class="text-2xl font-bold tracking-tight text-balance" data-testid="consent-title">
            @if (consent.hasValue()) {
              Allow {{ consent.value().client.name }} to use Tinfirm as you?
            } @else {
              Connect an app to Tinfirm
            }
          </h1>
        </div>

        <section
          hlmCard
          class="rounded-xl shadow-[0_12px_32px_-12px_rgb(0_0_0/0.15)] [--card-spacing:--spacing(6)]"
          aria-label="Authorize an app"
        >
          <div hlmCardContent class="flex flex-col gap-5">
            @if (consent.isLoading()) {
              <div class="flex flex-col gap-3" aria-busy="true">
                <span class="sr-only" role="status">Loading the request…</span>
                <hlm-skeleton class="h-4 w-2/3" />
                <hlm-skeleton class="h-4 w-1/2" />
                <hlm-skeleton class="h-16 w-full" />
              </div>
            } @else if (consent.error()) {
              <div hlmAlert variant="destructive" data-testid="consent-error">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <h2 hlmAlertTitle>This request is no longer valid</h2>
                <p hlmAlertDescription>
                  It expired, was already answered, or belongs to another account. Start again from
                  your app.
                </p>
              </div>
            } @else if (consent.hasValue()) {
              @let request = consent.value();
              <dl class="grid grid-cols-[6rem_1fr] gap-x-3 gap-y-2 text-sm">
                <dt class="text-muted-foreground">App</dt>
                <dd data-testid="consent-client">
                  <span class="font-medium">{{ request.client.name }}</span>
                  @if (request.client.domain; as domain) {
                    <span class="text-muted-foreground"> from </span>
                    <span class="font-mono text-xs" data-testid="consent-domain">{{ domain }}</span>
                  } @else {
                    <span class="text-muted-foreground"> (built into Tinfirm)</span>
                  }
                </dd>
                <dt class="text-muted-foreground">Company</dt>
                <dd class="font-medium" data-testid="consent-company">{{ request.companyName }}</dd>
              </dl>
              <p class="text-muted-foreground text-sm">
                The app acts as you, with your role in {{ request.companyName }}. It can never do
                more than you can in Tinfirm. Only allow apps you started yourself.
              </p>
              <div hlmAlert data-testid="owner-notice">
                <ng-icon name="lucideEye" aria-hidden="true" />
                <h2 hlmAlertTitle>Your conversations through this app are personal</h2>
                <p hlmAlertDescription>
                  Conversations you start through {{ request.client.name }} are personal: agents act
                  with your own accounts and budget. The Owners of
                  {{ request.ownerNotice.companyName }}
                  @if (request.ownerNotice.owners.length) {
                    ({{ ownerNames(request.ownerNotice.owners) }})
                  }
                  can read them in full; each read is recorded and shown to you.
                </p>
              </div>

              <fieldset class="flex flex-col gap-2">
                <legend class="mb-2 text-sm font-semibold">What may it do?</legend>
                @for (scope of request.offered; track scope.scope) {
                  <label
                    class="hover:bg-accent has-[:checked]:border-ring flex cursor-pointer items-start gap-3 rounded-lg border p-3"
                    [attr.data-testid]="'level-' + scope.scope"
                  >
                    <input
                      type="radio"
                      name="level"
                      class="mt-1"
                      [value]="scope.scope"
                      [checked]="level() === scope.scope"
                      (change)="level.set(scope.scope)"
                    />
                    <span class="flex flex-col gap-0.5">
                      <span class="text-sm font-medium">{{ levelLabel(scope) }}</span>
                      <span class="text-muted-foreground text-xs">{{ scope.description }}</span>
                    </span>
                  </label>
                }
              </fieldset>

              @if (level() === 'tinfirm:decide') {
                <div hlmAlert data-testid="decide-note">
                  <ng-icon name="lucideShieldAlert" aria-hidden="true" />
                  <p hlmAlertDescription>
                    Deciding lets the app answer questions and approve requests as you. Keep your
                    app asking you before each of these calls; never allow them without asking.
                  </p>
                </div>
              }

              @if (submitError(); as message) {
                <div hlmAlert variant="destructive" data-testid="submit-error">
                  <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                  <p hlmAlertDescription>{{ message }}</p>
                </div>
              }

              <div class="flex flex-wrap justify-end gap-2">
                <button
                  hlmBtn
                  variant="outline"
                  type="button"
                  [disabled]="busy() !== null"
                  (click)="answer(false)"
                >
                  @if (busy() === 'deny') {
                    <hlm-spinner aria-hidden="true" />
                  }
                  Deny
                </button>
                <button
                  hlmBtn
                  type="button"
                  [disabled]="busy() !== null || level() === null"
                  (click)="answer(true)"
                >
                  @if (busy() === 'allow') {
                    <hlm-spinner aria-hidden="true" />
                  }
                  Allow
                </button>
              </div>
            }
          </div>
        </section>
        <p class="text-muted-foreground mt-6 text-center text-xs">
          You can disconnect the app at any time under Profile, Connected apps.
        </p>
      </div>
    </div>
  `,
})
export class Authorize {
  private readonly api = inject(Api);
  private readonly http = inject(HttpClient);
  private readonly navigation = inject(BrowserNavigation);

  /** The query of the authorization server's redirect to this page (`client_id`, `scope`, `state`). */
  private readonly query = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(
      map((params) => ({
        clientId: params.get('client_id') ?? undefined,
        scope: params.get('scope') ?? undefined,
        state: params.get('state') ?? undefined,
      })),
    ),
    { initialValue: { clientId: undefined, scope: undefined, state: undefined } },
  );
  private readonly clientId = computed(() => this.query().clientId);
  private readonly state = computed(() => this.query().state);

  protected readonly consent = resource({
    params: () => this.query(),
    loader: ({ params }): Promise<OAuthConsentRequest> => {
      const { clientId, state, scope } = params;
      if (!clientId || !state) return Promise.reject(new Error('The request is incomplete'));
      return this.api.invoke(getOAuthConsent, { clientId, state, scope });
    },
  });

  /** The level chosen: at first the one the client asked for (never deciding), none while loading. */
  protected readonly level = linkedSignal<OAuthScopeName | null>(() =>
    this.consent.hasValue() ? defaultLevel(this.consent.value().requested) : null,
  );

  protected readonly busy = signal<'allow' | 'deny' | null>(null);
  protected readonly submitError = signal<string | null>(null);

  protected readonly ownerNames = ownerNames;

  protected levelLabel(scope: OAuthScope): string {
    return LEVEL_LABELS[scope.scope];
  }

  protected async answer(allow: boolean): Promise<void> {
    const clientId = this.clientId();
    const state = this.state();
    const level = this.level();
    if (!clientId || !state || this.busy() || (allow && !level)) return;
    this.busy.set(allow ? 'allow' : 'deny');
    this.submitError.set(null);
    let body = new HttpParams().set('client_id', clientId).set('state', state);
    if (allow && level) {
      for (const scope of scopesUpTo(level)) body = body.append('scope', scope);
    }
    try {
      const answer = await firstValueFrom(
        this.http.post<AuthorizationAnswer>('/oauth2/authorize', body.toString(), {
          headers: new HttpHeaders({
            'Content-Type': 'application/x-www-form-urlencoded',
            Accept: 'application/json',
          }),
          context: skipAuthRedirect(),
        }),
      );
      this.navigation.assign(answer.redirectUri);
    } catch (error) {
      this.busy.set(null);
      this.submitError.set(
        problemMessage(error, {
          'status:403': 'The page expired. Reload it and answer again.',
          'status:400': 'This request is no longer valid. Start again from your app.',
        }),
      );
    }
  }
}
