import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { AuthProviders } from '../../api/generated/models';
import { BrowserNavigation } from '../../core/browser-navigation';
import { Session } from '../../core/session';
import {
  currentUser,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../testing/test-helpers';
import { Login } from './login';

const PROVIDERS: AuthProviders = {
  localEnabled: true,
  providers: [
    { id: 'keycloak', name: 'Keycloak', kind: 'OIDC', loginUrl: '/oauth2/authorization/keycloak' },
  ],
};

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let http: HttpTestingController;
  let router: Router;
  let el: HTMLElement;
  const navigation = { assign: vi.fn() };

  async function create(inputs: Record<string, string> = {}, providers = PROVIDERS) {
    fixture = TestBed.createComponent(Login);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    el = fixture.nativeElement;
    await settle();
    http.expectOne('/api/system/info').flush({ version: '0.1.0', telemetryEnabled: false });
    if (providers) {
      http.expectOne({ method: 'GET', url: '/api/auth/providers' }).flush(providers);
    }
    await settle();
    await fixture.whenStable();
  }

  async function signIn(email: string, password: string) {
    typeInto(el.querySelector('#login-email'), email);
    typeInto(el.querySelector('#login-password'), password);
    submitForm(el.querySelector('form'));
    await settle();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    navigation.assign.mockClear();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    document.cookie = 'XSRF-TOKEN=test-xsrf-token; path=/';
  });

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; path=/; max-age=0';
  });

  it('asks for the login methods first, then shows the form and one button per OIDC provider', async () => {
    await create();

    expect(el.querySelector('#login-email')).not.toBeNull();
    expect(el.querySelector('#login-password')?.getAttribute('type')).toBe('password');
    const sso = el.querySelector<HTMLAnchorElement>('a[href="/oauth2/authorization/keycloak"]');
    expect(sso?.textContent).toContain('Continue with Keycloak');
  });

  it('hides the password form when local accounts are disabled', async () => {
    await create({}, { localEnabled: false, providers: PROVIDERS.providers });
    expect(el.querySelector('form')).toBeNull();
    expect(el.textContent).toContain('Continue with Keycloak');
  });

  it('shows client-side validation before calling the server', async () => {
    await create();
    submitForm(el.querySelector('form'));
    await settle();
    await fixture.whenStable();

    expect(el.textContent).toContain('Enter your email.');
    expect(el.textContent).toContain('Enter your password.');
    http.expectNone('/api/auth/login');
  });

  it('logs in with the XSRF header, stores the user and goes to the return URL', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    await create({ returnUrl: '/c/c1/secrets' });

    await signIn('me@example.com', 'correct horse battery');
    const req = http.expectOne({ method: 'POST', url: '/api/auth/login' });
    expect(req.request.body).toEqual({
      email: 'me@example.com',
      password: 'correct horse battery',
    });
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('test-xsrf-token');
    req.flush(currentUser());
    await settle();

    expect(TestBed.inject(Session).user()?.email).toBe('me@example.com');
    expect(navigate).toHaveBeenCalledWith('/c/c1/secrets');
  });

  it('never follows a return URL to another site', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    await create({ returnUrl: 'https://evil.example/' });

    await signIn('me@example.com', 'correct horse battery');
    http.expectOne('/api/auth/login').flush(currentUser());
    await settle();

    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('returns to an interrupted OAuth authorization request after a local login', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const authorize = '/oauth2/authorize?response_type=code&client_id=https%3A%2F%2Fclaude.ai%2Fx';
    await create({ returnTo: authorize });
    expect(el.querySelector('[data-testid=authorizing-note]')?.textContent).toContain(
      'An app asks to connect',
    );

    await signIn('me@example.com', 'correct horse battery');
    http.expectOne('/api/auth/login').flush(currentUser());
    await settle();

    expect(navigation.assign).toHaveBeenCalledWith(authorize);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('never returns to anything but the authorization endpoint', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    await create({ returnTo: '//evil.example/oauth2/authorize' });
    expect(el.querySelector('[data-testid=authorizing-note]')).toBeNull();

    await signIn('me@example.com', 'correct horse battery');
    http.expectOne('/api/auth/login').flush(currentUser());
    await settle();

    expect(navigation.assign).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('says so when the credentials are wrong', async () => {
    await create();
    await signIn('me@example.com', 'nope');
    http.expectOne('/api/auth/login').flush(problem(401, undefined, 'Bad credentials'), {
      status: 401,
      statusText: 'Unauthorized',
    });
    await settle();
    await fixture.whenStable();

    expect(el.querySelector('[data-slot=alert]')?.textContent).toContain(
      'Wrong email or password.',
    );
    expect(TestBed.inject(Session).user()).toBeNull();
  });

  it('shows a rate-limit message on 429', async () => {
    await create();
    await signIn('me@example.com', 'nope');
    http
      .expectOne('/api/auth/login')
      .flush(problem(429, 'RATE_LIMITED'), { status: 429, statusText: 'Too Many Requests' });
    await settle();
    await fixture.whenStable();

    expect(el.textContent).toContain('Too many sign-in attempts. Wait a minute, then try again.');
  });

  it('shows an error with retry when the login methods cannot be loaded', async () => {
    fixture = TestBed.createComponent(Login);
    el = fixture.nativeElement;
    await settle();
    http.expectOne('/api/system/info').flush({ version: '0.1.0', telemetryEnabled: false });
    http
      .expectOne('/api/auth/providers')
      .flush(problem(503), { status: 503, statusText: 'Unavailable' });
    await settle();
    await fixture.whenStable();

    expect(el.textContent).toContain("Can't load sign-in options");
    expect(el.querySelector('form')).toBeNull();

    const retry = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Try again'),
    );
    retry?.click();
    await settle();
    http.expectOne('/api/auth/providers').flush(PROVIDERS);
    await settle();
    await fixture.whenStable();
    expect(el.querySelector('form')).not.toBeNull();
  });

  it('explains an OIDC login that failed because the email is in use', async () => {
    await create({ error: 'EMAIL_IN_USE' });
    const alert = el.querySelector('[data-testid=sso-error]');
    expect(alert?.textContent).toContain('This email is already in use');
  });

  it('explains an OIDC login that failed because the identity provider sent no email', async () => {
    await create({ error: 'EMAIL_MISSING' });
    const alert = el.querySelector('[data-testid=sso-error]');
    expect(alert?.textContent).toContain('No email address from your identity provider');
    expect(alert?.textContent).not.toContain('Single sign-on failed');
  });

  it('explains an OIDC login that failed because the email was not confirmed', async () => {
    await create({ error: 'EMAIL_NOT_VERIFIED' });
    const alert = el.querySelector('[data-testid=sso-error]');
    expect(alert?.textContent).toContain('Email address not confirmed');
    expect(alert?.textContent).toContain('email trust setting');
    expect(alert?.textContent).not.toContain('Single sign-on failed');
  });

  it('explains a sign-in refused because the identity provider maps the person to no role', async () => {
    await create({ error: 'ROLE_NOT_MAPPED' });
    const alert = el.querySelector('[data-testid=sso-error]');
    expect(alert?.textContent).toContain('No role for you in this sign-in');
    expect(alert?.textContent).toContain('your work are kept');
    expect(alert?.textContent).not.toContain('Single sign-on failed');
  });

  it('explains an OIDC login that failed because the email domain is not one the provider allows', async () => {
    await create({ error: 'EMAIL_DOMAIN_NOT_ALLOWED' });
    const alert = el.querySelector('[data-testid=sso-error]');
    expect(alert?.textContent).toContain('Email domain not allowed for this sign-in');
    expect(alert?.textContent).toContain('instance admin');
    expect(alert?.textContent).not.toContain('Single sign-on failed');
  });

  it('offers a SAML provider as it offers an OIDC one', async () => {
    await create(
      {},
      {
        localEnabled: true,
        providers: [
          ...PROVIDERS.providers,
          { id: 'corp', name: 'Corp SAML', kind: 'SAML', loginUrl: '/saml2/authenticate/corp' },
        ],
      },
    );

    const saml = el.querySelector<HTMLAnchorElement>('a[href="/saml2/authenticate/corp"]');
    expect(saml?.textContent).toContain('Continue with Corp SAML');
  });

  it('names the page to come back to on every single sign-on start (Q44)', async () => {
    await create(
      { returnUrl: '/me?tab=tokens' },
      {
        localEnabled: true,
        providers: [
          ...PROVIDERS.providers,
          { id: 'corp', name: 'Corp SAML', kind: 'SAML', loginUrl: '/saml2/authenticate/corp' },
        ],
      },
    );

    const hrefs = Array.from(el.querySelectorAll<HTMLAnchorElement>('a[href^="/oauth2/"], a[href^="/saml2/"]')).map((a) =>
      a.getAttribute('href'),
    );
    expect(hrefs).toEqual([
      '/oauth2/authorization/keycloak?returnTo=%2Fme%3Ftab%3Dtokens',
      '/saml2/authenticate/corp?returnTo=%2Fme%3Ftab%3Dtokens',
    ]);
  });

  it.each(['https://evil.example/', '//evil.example', '/api/me', '/'])(
    'names no page for %s, which another site or the server would own',
    async (returnUrl) => {
      await create({ returnUrl });
      expect(el.querySelector('a[href="/oauth2/authorization/keycloak"]')).not.toBeNull();
    },
  );

  it('leaves an interrupted authorization request to the server on single sign-on', async () => {
    await create({ returnTo: '/oauth2/authorize?client_id=x&state=y' });
    expect(el.querySelector('a[href="/oauth2/authorization/keycloak"]')).not.toBeNull();
  });

  it('says a SAML sign-in needs the Enterprise edition', async () => {
    await create({ error: 'EDITION_REQUIRED' });
    expect(el.querySelector('[data-testid=sso-error]')?.textContent).toContain(
      'Enterprise edition',
    );
  });

  it('shows a generic message for other OIDC errors, never the raw value', async () => {
    await create({ error: '<script>x</script>' });
    const alert = el.querySelector('[data-testid=sso-error]');
    expect(alert?.textContent).toContain('Single sign-on failed');
    expect(el.innerHTML).not.toContain('<script>x</script>');
  });

  describe('the direct link to one provider (ADR-0075)', () => {
    const TWO: AuthProviders = {
      localEnabled: true,
      providers: [
        ...PROVIDERS.providers,
        { id: 'acme', name: 'Acme SSO', kind: 'OIDC', loginUrl: '/oauth2/authorization/acme' },
      ],
    };

    it('offers only the linked provider and the password form for ?provider=', async () => {
      await create({ provider: 'acme' }, TWO);

      const links = Array.from(el.querySelectorAll<HTMLAnchorElement>('a[href^="/oauth2/"]'));
      expect(links.map((a) => a.getAttribute('href'))).toEqual(['/oauth2/authorization/acme']);
      expect(links[0].textContent).toContain('Continue with Acme SSO');
      expect(el.querySelector('#login-email')).not.toBeNull();
    });

    it('offers every provider for an id the instance does not offer', async () => {
      await create({ provider: 'other' }, TWO);
      expect(el.querySelectorAll('a[href^="/oauth2/"]')).toHaveLength(2);

      fixture.destroy();
      await create({ provider: '../../admin' }, TWO);
      expect(el.querySelectorAll('a[href^="/oauth2/"]')).toHaveLength(2);
    });

    it('never offers a company lookup by email', async () => {
      await create();
      expect(el.textContent).not.toContain('Sign in with your company');
      expect(el.querySelector('#company-email')).toBeNull();
    });
  });
});
