import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { PersonalAccessToken } from '../../api/generated/models';
import { Session } from '../../core/session';
import { aCurrentUser, aMembership } from '../../testing/fixtures';
import { fakeNotify, problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { isExpired, PersonalTokens, tokenCompanies } from './personal-tokens';

const LAPTOP: PersonalAccessToken = {
  id: 't1',
  name: 'Laptop',
  scope: 'DECIDE',
  createdAt: '2026-10-01T10:00:00Z',
  expiresAt: '2099-01-01T00:00:00Z',
  lastUsedAt: '2026-10-07T09:00:00Z',
};
const CI: PersonalAccessToken = {
  id: 't2',
  name: 'CI',
  scope: 'READ',
  createdAt: '2026-09-01T10:00:00Z',
  expiresAt: '2026-09-02T10:00:00Z',
  lastUsedAt: null,
};
const SCRIPT: PersonalAccessToken = {
  id: 't3',
  name: 'Script',
  scope: 'WRITE',
  createdAt: '2026-09-01T10:00:00Z',
  expiresAt: null,
};

describe('isExpired', () => {
  it('is true once the expiry has passed, never without one', () => {
    const now = new Date('2026-10-07T00:00:00Z');
    expect(isExpired(CI, now)).toBe(true);
    expect(isExpired(LAPTOP, now)).toBe(false);
    expect(isExpired(SCRIPT, now)).toBe(false);
  });
});

const ACME = aMembership('MEMBER', { companyId: 'c1', companyName: 'Acme' });
const GLOBEX = aMembership('ADMIN', { companyId: 'c2', companyName: 'Globex' });

describe('tokenCompanies', () => {
  it('names all companies without a list, else those of the user it names', () => {
    expect(tokenCompanies({ companyIds: null }, [ACME, GLOBEX])).toBe('All your companies');
    expect(tokenCompanies({ companyIds: ['c2'] }, [ACME, GLOBEX])).toBe('Globex');
    expect(tokenCompanies({ companyIds: ['c9'] }, [ACME])).toBe('No company of yours');
  });
});

describe('PersonalTokens', () => {
  let fixture: ComponentFixture<PersonalTokens>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let el: HTMLElement;

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  async function create(tokens: PersonalAccessToken[] = [LAPTOP, CI, SCRIPT]) {
    const fake = fakeNotify();
    notify = fake.notify;
    TestBed.configureTestingModule({
      imports: [PersonalTokens],
      providers: [provideRouter([]), ...provideTestHttp(), fake.provider],
    });
    TestBed.inject(Session).setUser(aCurrentUser({ memberships: [ACME, GLOBEX] }));
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PersonalTokens);
    el = fixture.nativeElement as HTMLElement;
    await settle();
    http.expectOne({ method: 'GET', url: '/api/me/tokens' }).flush(tokens);
    await stable();
  }

  const row = (id: string) => el.querySelector(`[data-testid=token-${id}]`) as HTMLElement;
  const text = (node: Element | null) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    document.querySelector('.cdk-overlay-container')?.replaceChildren();
  });

  it('lists the tokens with scope, expiry and last use, never a token value', async () => {
    await create();
    expect(text(row('t1').querySelector('[data-testid=token-scope]'))).toBe('Decide');
    expect(text(row('t1').querySelector('[data-testid=token-meta]'))).toContain('expires');
    expect(text(row('t1').querySelector('[data-testid=token-meta]'))).toContain('last used');
    expect(text(row('t2'))).toContain('Expired');
    expect(text(row('t2').querySelector('[data-testid=token-meta]'))).toContain('never used');
    expect(text(row('t3').querySelector('[data-testid=token-meta]'))).toContain('never expires');
    expect(el.textContent).not.toContain('tfp_');
  });

  it('says which companies each token reaches (D9)', async () => {
    await create([
      { ...LAPTOP, companyIds: null },
      { ...CI, companyIds: ['c2'] },
    ]);
    expect(text(row('t1').querySelector('[data-testid=token-companies]'))).toBe(
      'Reaches: All your companies',
    );
    expect(text(row('t2').querySelector('[data-testid=token-companies]'))).toBe('Reaches: Globex');
  });

  it('renews a token for another 90 days, and offers no renewal for an expired one', async () => {
    await create();
    // An expired token is not renewed: it says to create a new one.
    expect(row('t2').querySelector('[data-testid=renew-token]')).toBeNull();
    expect(text(row('t2').querySelector('[data-testid=token-expired-note]'))).toContain(
      'create a new one',
    );
    row('t1').querySelector<HTMLButtonElement>('[data-testid=renew-token]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/me/tokens/t1/renew' })
      .flush({ ...LAPTOP, expiresAt: '2099-04-01T00:00:00Z' });
    await stable();
    expect(notify.success).toHaveBeenCalledWith('Laptop renewed', 'It works for another 90 days.');
  });

  it('says TOKEN_EXPIRED when a token expired before its renewal', async () => {
    await create();
    row('t1').querySelector<HTMLButtonElement>('[data-testid=renew-token]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/me/tokens/t1/renew' })
      .flush(problem(409, 'TOKEN_EXPIRED'), { status: 409, statusText: 'Conflict' });
    await stable();
    expect(text(row('t1').querySelector('[data-testid=renew-error]'))).toBe(
      'This token has expired, and an expired token is not renewed. Create a new one.',
    );
  });

  it('shows an empty state', async () => {
    await create([]);
    expect(el.querySelector('[data-testid=no-tokens]')).not.toBeNull();
  });

  it('revokes a token after confirmation', async () => {
    await create();
    el.querySelector<HTMLButtonElement>('button[aria-label="Revoke Laptop"]')!.click();
    await stable();
    const overlay = document.querySelector('.cdk-overlay-container')!;
    expect(overlay.textContent).toContain('Revoke Laptop?');
    http.expectNone({ method: 'DELETE' });
    Array.from(overlay.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Revoke token'))!
      .click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/me/tokens/t1' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(row('t1')).toBeNull();
    expect(row('t2')).not.toBeNull();
    expect(notify.success).toHaveBeenCalledWith('Laptop revoked', 'It no longer works.');
  });

  it('shows a failed revoke in the confirmation', async () => {
    await create();
    el.querySelector<HTMLButtonElement>('button[aria-label="Revoke CI"]')!.click();
    await stable();
    Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Revoke token'))!
      .click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/me/tokens/t2' })
      .flush(problem(404), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(document.querySelector('.cdk-overlay-container')?.textContent).toContain(
      'no longer exists',
    );
    expect(row('t2')).not.toBeNull();
  });

  it('adds a created token to the top of the list', async () => {
    await create([CI]);
    el.querySelector<HTMLButtonElement>('button')!.click();
    await stable();
    const overlay = document.querySelector('.cdk-overlay-container')!;
    expect(overlay.querySelector('#token-name')).not.toBeNull();
    const input = overlay.querySelector<HTMLInputElement>('#token-name')!;
    input.value = 'Laptop';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    const password = overlay.querySelector<HTMLInputElement>('#token-current-password')!;
    password.value = 'correct horse battery staple';
    password.dispatchEvent(new Event('input', { bubbles: true }));
    overlay
      .querySelector('#create-token-form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/me/tokens' })
      .flush(
        { token: 'tfp_x', personalAccessToken: LAPTOP, ownerNotice: { companies: [] } },
        { status: 201, statusText: 'Created' },
      );
    await stable();
    const ids = Array.from(el.querySelectorAll('[data-testid^=token-t]')).map((n) =>
      n.getAttribute('data-testid'),
    );
    expect(ids).toEqual(['token-t1', 'token-t2']);
  });
});
