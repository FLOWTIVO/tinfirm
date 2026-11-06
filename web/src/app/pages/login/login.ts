import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { email, form, FormField, maxLength, required, submit } from '@angular/forms/signals';
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideKeyRound, lucideMoon, lucideSun } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { getAuthProviders, getSystemInfo, login } from '../../api/generated/functions';
import { isStatus, mapSubmissionError } from '../../api/problem';
import { skipAuthRedirect } from '../../core/http-context';
import { BrowserNavigation } from '../../core/browser-navigation';
import { safeAuthorizationReturn, safeReturnUrl, takeSignInReturn } from '../../core/return-url';
import { Session } from '../../core/session';
import { ThemeService } from '../../core/theme';
import { ErrorState } from '../../shared/error-state';
import { firstError } from '../../shared/forms';
import { Logo } from '../../shared/logo';

/** An identity provider's registration id, as the deployment names it (CONVENTIONS §4). */
const REGISTRATION_ID = /^[a-z0-9]+$/;

const LOGIN_MESSAGES: Record<string, string> = {
  'status:401': 'Wrong email or password.',
  RATE_LIMITED: 'Too many sign-in attempts. Wait a minute, then try again.',
  'status:429': 'Too many sign-in attempts. Wait a minute, then try again.',
  'status:403': 'The sign-in form expired. Try again.',
};

@Component({
  selector: 'app-login',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmCardImports,
    HlmFieldImports,
    HlmInput,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    Logo,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideKeyRound, lucideMoon, lucideSun })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.html',
})
export class Login {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly session = inject(Session);
  private readonly navigation = inject(BrowserNavigation);
  protected readonly theme = inject(ThemeService);

  /** Where to go after signing in (`?returnUrl=`), bound from the query string. */
  readonly returnUrl = input<string>();

  /**
   * The OAuth authorization request this login interrupted (`?returnTo=/oauth2/authorize?...`,
   * ADR-0045), set by the server. After a local login the browser returns there; after single
   * sign-on the server does it.
   */
  readonly returnTo = input<string>();

  protected readonly authorizing = computed(
    () => safeAuthorizationReturn(this.returnTo()) !== null,
  );

  /**
   * One identity provider to sign in with (`?provider={registrationId}`), the direct link an
   * operator hands a company's people (ADR-0075). Only an id of a provider this instance offers is
   * taken; the page then offers that provider and the password form, nothing else. Any other value
   * leaves every provider on offer.
   */
  readonly provider = input<string>();

  /**
   * Set by the server when an OIDC login fails (`/login?error=EMAIL_IN_USE`). Only mapped to fixed
   * messages; the raw value is never shown.
   */
  readonly error = input<string>();

  protected readonly ssoError = computed(() => {
    const code = this.error();
    if (code === undefined) return null;
    if (code === 'EMAIL_IN_USE') {
      return {
        title: 'This email is already in use',
        message:
          'Another Tinfirm account already has the email your identity provider sent. Tinfirm never links accounts automatically. Sign in the way you did before, or ask an instance admin for help.',
      };
    }
    if (code === 'EMAIL_MISSING') {
      return {
        title: 'No email address from your identity provider',
        message:
          'Tinfirm needs an email address to create your account, and your identity provider did not send one. Ask an instance admin to check the email claims of this sign-in option.',
      };
    }
    if (code === 'EMAIL_NOT_VERIFIED') {
      return {
        title: 'Email address not confirmed',
        message:
          'Your identity provider did not confirm your email address. Ask an instance admin to check the email trust setting of this sign-in option.',
      };
    }
    if (code === 'ROLE_NOT_MAPPED') {
      return {
        title: 'No role for you in this sign-in',
        message:
          'Your identity provider gives you none of the roles it is set to give in Tinfirm. Your account and your work are kept. Ask your IT team to add you to the group or app role for Tinfirm, then sign in again.',
      };
    }
    if (code === 'EDITION_REQUIRED') {
      return {
        title: 'This sign-in needs the Enterprise edition',
        message:
          'This instance offers SAML sign-in under an Enterprise licence only. Use another method, or ask an instance admin about the licence.',
      };
    }
    if (code === 'EMAIL_DOMAIN_NOT_ALLOWED') {
      return {
        title: 'Email domain not allowed for this sign-in',
        message:
          'This sign-in option creates accounts only for the email domains it lists, and your email is not in one of them. Ask an instance admin to add your domain, or for an account.',
      };
    }
    return {
      title: 'Single sign-on failed',
      message:
        'The identity provider did not complete the sign-in. Try again, or use another method.',
    };
  });

  /** Also sets the XSRF-TOKEN cookie that `POST /api/auth/login` needs. */
  protected readonly providers = resource({
    loader: () => this.api.invoke(getAuthProviders, {}),
  });

  protected readonly systemInfo = resource({
    loader: () => this.api.invoke(getSystemInfo, {}),
  });

  protected readonly localEnabled = computed(
    () => this.providers.hasValue() && this.providers.value().localEnabled,
  );
  /** The identity providers to sign in with, OIDC and SAML alike (ADR-0086 decision 15). */
  protected readonly ssoProviders = computed(() => {
    if (!this.providers.hasValue()) return [];
    const all = this.providers.value().providers;
    const wanted = this.provider();
    const direct =
      wanted && REGISTRATION_ID.test(wanted) ? all.find((p) => p.id === wanted) : undefined;
    return direct ? [direct] : all;
  });

  /** Whether any button of an identity provider shows, for the separator above them. */
  protected readonly anySso = computed(() => this.ssoProviders().length > 0);

  protected readonly model = signal({ email: '', password: '' });
  protected readonly loginForm = form(this.model, (path) => {
    required(path.email, { message: 'Enter your email.' });
    email(path.email, { message: 'Enter a valid email address.' });
    maxLength(path.email, 320);
    required(path.password, { message: 'Enter your password.' });
    maxLength(path.password, 1024);
  });

  protected readonly formError = signal<string | null>(null);
  protected readonly firstError = firstError;

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (!this.providers.hasValue()) return;
    this.formError.set(null);
    void submit(this.loginForm, async () => {
      try {
        const user = await this.api.invoke(login, { body: this.model() }, skipAuthRedirect());
        this.session.setUser(user);
        this.model.update((m) => ({ ...m, password: '' }));
        const authorization = safeAuthorizationReturn(this.returnTo());
        if (authorization) {
          this.navigation.assign(authorization);
          return undefined;
        }
        // A page the app sent to sign in from wins once; it is forgotten either way.
        const back = takeSignInReturn();
        await this.router.navigateByUrl(safeReturnUrl(this.returnUrl()) ?? back ?? '/');
        return undefined;
      } catch (error) {
        if (isStatus(error, 403)) {
          // Most likely a stale or missing XSRF cookie: fetch a fresh one for the next attempt.
          this.providers.reload();
        }
        const failure = mapSubmissionError(this.loginForm, error, { overrides: LOGIN_MESSAGES });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
