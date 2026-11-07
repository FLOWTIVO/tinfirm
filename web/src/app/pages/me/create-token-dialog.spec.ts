import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type {
  PersonalAccessTokenCreated,
  PersonalAccessTokenCreateRequest,
} from '../../api/generated/models';
import { Session } from '../../core/session';
import { aCurrentUser, aMembership } from '../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../testing/test-helpers';
import {
  CreateTokenDialog,
  EXPIRY_CHOICES,
  expiresAtFor,
  tokenExpiresAt,
} from './create-token-dialog';

const TOKEN = 'tfp_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG';

const CREATED: PersonalAccessTokenCreated = {
  token: TOKEN,
  personalAccessToken: {
    id: 't1',
    name: 'Laptop',
    scope: 'WRITE',
    createdAt: '2026-10-07T10:00:00Z',
    expiresAt: '2027-01-05T10:00:00Z',
    lastUsedAt: null,
  },
  ownerNotice: {
    companies: [
      { companyId: 'c1', companyName: 'Acme', owners: ['Ann Owner', 'Bob Owner'] },
      { companyId: 'c2', companyName: 'Globex', owners: ['Cleo Owner'] },
    ],
  },
};

describe('expiresAtFor', () => {
  it('adds whole days, or gives null for no expiry', () => {
    const now = new Date('2026-10-07T10:00:00Z');
    expect(expiresAtFor(30, now)).toBe('2026-11-06T10:00:00.000Z');
    expect(expiresAtFor(0, now)).toBeNull();
  });

  it('leaves a token’s longest expiry, 90 days, to the server’s clock', () => {
    const now = new Date('2026-10-07T10:00:00Z');
    expect(tokenExpiresAt(30, now)).toBe('2026-11-06T10:00:00.000Z');
    expect(tokenExpiresAt(90, now)).toBeNull();
    // No token lives longer than 90 days, and none without an expiry (D9).
    expect(EXPIRY_CHOICES.map((c) => c.days)).toEqual([7, 30, 90]);
  });
});

const ACME = aMembership('MEMBER', { companyId: 'c1', companyName: 'Acme' });
const GLOBEX = aMembership('ADMIN', { companyId: 'c2', companyName: 'Globex' });
const PASSWORD = 'correct horse battery staple';

describe('CreateTokenDialog', () => {
  let fixture: ComponentFixture<CreateTokenDialog>;
  let http: HttpTestingController;
  let created: string[];

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;

  async function setOpen(open: boolean) {
    fixture.componentInstance.open.set(open);
    await settle();
    await fixture.whenStable();
  }

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  async function start(authSource: 'LOCAL' | 'OIDC' = 'LOCAL') {
    TestBed.configureTestingModule({
      imports: [CreateTokenDialog],
      providers: [provideRouter([]), ...provideTestHttp(), fakeNotify().provider],
    });
    TestBed.inject(Session).setUser(aCurrentUser({ authSource, memberships: [ACME, GLOBEX] }));
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreateTokenDialog);
    created = [];
    fixture.componentInstance.tokenCreated.subscribe((t) => created.push(t.id));
    await setOpen(true);
  }

  beforeEach(async () => {
    await start();
  });

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  function pickScope(value: string): void {
    const radio = overlay().querySelector<HTMLInputElement>(
      `input[name=token-scope][value=${value}]`,
    )!;
    radio.checked = true;
    radio.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function pickCompanies(value: 'all' | 'some'): void {
    const radio = overlay().querySelector<HTMLInputElement>(
      `input[name=token-companies][value=${value}]`,
    )!;
    radio.checked = true;
    radio.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function toggleCompany(companyId: string): void {
    const box = overlay().querySelector<HTMLInputElement>(`input[data-company=${companyId}]`)!;
    box.checked = !box.checked;
    box.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function pickExpiry(days: string): void {
    const select = overlay().querySelector<HTMLSelectElement>('#token-expiry')!;
    select.value = days;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }

  it('explains the three scopes in one line each, READ and 90 days by default', () => {
    const picker = overlay().querySelector('[data-testid=scope-picker]')!;
    const labels = Array.from(picker.querySelectorAll('label'));
    expect(labels.map((l) => l.querySelector('.font-medium')?.textContent?.trim())).toEqual([
      'Read',
      'Write',
      'Decide',
    ]);
    expect(picker.textContent).toContain('Never decide');
    expect(picker.textContent).toContain('approve or deny');
    expect(
      overlay().querySelector<HTMLInputElement>('input[name=token-scope][value=READ]')!.checked,
    ).toBe(true);
    expect(overlay().querySelector<HTMLSelectElement>('#token-expiry')!.value).toBe('90');
    expect(overlay().querySelector('[data-testid=token-expiry-date]')?.textContent).toContain(
      'unless you renew it; 90 days is the most',
    );
    // Every company of the user by default.
    expect(
      overlay().querySelector<HTMLInputElement>('input[name=token-companies][value=all]')!.checked,
    ).toBe(true);
  });

  it('creates a token and shows it once with a copy button', async () => {
    typeInto(overlay().querySelector('#token-name'), '  Laptop ');
    typeInto(overlay().querySelector('#token-current-password'), PASSWORD);
    pickScope('WRITE');
    await stable();
    submitForm(overlay().querySelector('#create-token-form'));
    await settle();
    const req = http.expectOne({ method: 'POST', url: '/api/me/tokens' });
    const body = req.request.body as PersonalAccessTokenCreateRequest;
    // 90 days, all of the user's companies: the server's defaults; the local user's password.
    expect(body).toEqual({
      name: 'Laptop',
      scope: 'WRITE',
      expiresAt: null,
      currentPassword: PASSWORD,
    });
    req.flush(CREATED, { status: 201, statusText: 'Created' });
    await stable();

    expect(created).toEqual(['t1']);
    expect(overlay().querySelector('[data-testid=new-token]')?.textContent?.trim()).toBe(TOKEN);
    expect(overlay().querySelector('[data-testid=token-once]')?.textContent).toContain(
      'not shown again',
    );
    expect(overlay().querySelector('button[aria-label="Copy token"]')).not.toBeNull();
    // A personal token sees no consent page: its creation tells who can read what it starts.
    const notice = overlay()
      .querySelector('[data-testid=owner-notice]')
      ?.textContent?.replace(/\s+/g, ' ');
    expect(notice).toContain(
      'Conversations you start with this token are personal: agents act with your own accounts and budget.',
    );
    expect(notice).toContain('can read them in full; each read is recorded and shown to you.');
    const companies = Array.from(
      overlay().querySelectorAll('[data-testid=owner-notice-companies] li'),
    ).map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(companies).toEqual(['Acme : Ann Owner and Bob Owner', 'Globex : Cleo Owner']);
  });

  it('sends a shorter expiry, and only the companies chosen', async () => {
    typeInto(overlay().querySelector('#token-name'), 'CI');
    typeInto(overlay().querySelector('#token-current-password'), PASSWORD);
    pickExpiry('7');
    pickCompanies('some');
    await stable();
    toggleCompany('c1');
    await stable();
    submitForm(overlay().querySelector('#create-token-form'));
    await settle();
    const req = http.expectOne({ method: 'POST', url: '/api/me/tokens' });
    const body = req.request.body as PersonalAccessTokenCreateRequest;
    expect(body.companyIds).toEqual(['c2']);
    const days = (new Date(body.expiresAt!).getTime() - Date.now()) / 86_400_000;
    expect(days).toBeGreaterThan(6.9);
    expect(days).toBeLessThan(7.1);
    req.flush(CREATED, { status: 201, statusText: 'Created' });
    await stable();
  });

  it('asks for a company when none is chosen, before calling the server', async () => {
    typeInto(overlay().querySelector('#token-name'), 'CI');
    typeInto(overlay().querySelector('#token-current-password'), PASSWORD);
    pickCompanies('some');
    await stable();
    toggleCompany('c1');
    toggleCompany('c2');
    await stable();
    submitForm(overlay().querySelector('#create-token-form'));
    await stable();
    expect(overlay().querySelector('[data-error=companies]')?.textContent).toContain(
      'Choose at least one company.',
    );
    http.expectNone({ method: 'POST' });
  });

  it('asks a local user for the current password, and places WRONG_PASSWORD on it', async () => {
    typeInto(overlay().querySelector('#token-name'), 'CI');
    submitForm(overlay().querySelector('#create-token-form'));
    await stable();
    expect(overlay().textContent).toContain('Enter your current password.');
    http.expectNone({ method: 'POST' });

    typeInto(overlay().querySelector('#token-current-password'), 'wrong');
    await stable();
    submitForm(overlay().querySelector('#create-token-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/me/tokens' })
      .flush(problem(400, 'WRONG_PASSWORD'), { status: 400, statusText: 'Bad Request' });
    await stable();
    expect(overlay().textContent).toContain('The current password is incorrect.');
  });

  it('forgets the token when the dialog closes', async () => {
    typeInto(overlay().querySelector('#token-name'), 'Laptop');
    typeInto(overlay().querySelector('#token-current-password'), PASSWORD);
    submitForm(overlay().querySelector('#create-token-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/me/tokens' })
      .flush(CREATED, { status: 201, statusText: 'Created' });
    await stable();
    Array.from(overlay().querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Done')!
      .click();
    await settle(5);
    await fixture.whenStable();
    expect(fixture.componentInstance.open()).toBe(false);
    expect(document.body.innerHTML).not.toContain(TOKEN);

    await setOpen(true);
    expect(overlay().querySelector('#token-name')).not.toBeNull();
    expect(document.body.innerHTML).not.toContain(TOKEN);
  });

  it('shows TOKEN_LIMIT', async () => {
    typeInto(overlay().querySelector('#token-name'), 'One too many');
    typeInto(overlay().querySelector('#token-current-password'), PASSWORD);
    submitForm(overlay().querySelector('#create-token-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/me/tokens' })
      .flush(problem(409, 'TOKEN_LIMIT'), { status: 409, statusText: 'Conflict' });
    await stable();
    expect(overlay().textContent).toContain('25 active tokens');
    expect(overlay().querySelector('[data-testid=new-token]')).toBeNull();
  });

  it('sends an identity provider’s user to sign in again, back to this page (RECENT_SIGN_IN_REQUIRED)', async () => {
    TestBed.resetTestingModule();
    overlay()?.replaceChildren();
    await start('OIDC');
    expect(overlay().querySelector('#token-current-password')).toBeNull();
    typeInto(overlay().querySelector('#token-name'), 'Laptop');
    await stable();
    submitForm(overlay().querySelector('#create-token-form'));
    await settle();
    const req = http.expectOne({ method: 'POST', url: '/api/me/tokens' });
    expect(req.request.body.currentPassword).toBeUndefined();
    req.flush(problem(403, 'RECENT_SIGN_IN_REQUIRED'), { status: 403, statusText: 'Forbidden' });
    await stable();
    expect(overlay().querySelector('[data-testid=sign-in-again]')?.textContent).toContain(
      'a sign-in of the last 10 minutes',
    );

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    overlay().querySelector<HTMLButtonElement>('[data-testid=sign-in-again-button]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/auth/logout' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    // The login page names /me to the server as a sign-in's `returnTo` (Q44); nothing kept in the tab.
    expect(navigate).toHaveBeenCalledWith('/login?returnUrl=%2Fme');
    expect(sessionStorage.getItem('tinfirm.signInReturn')).toBeNull();
  });

  it('requires a name before calling the server', async () => {
    typeInto(overlay().querySelector('#token-name'), '   ');
    submitForm(overlay().querySelector('#create-token-form'));
    await stable();
    expect(overlay().textContent).toContain('Enter a name.');
    http.expectNone({ method: 'POST' });
  });
});
