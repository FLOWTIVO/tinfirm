import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { Role, ScimStatus, ScimToken } from '../../../api/generated/models';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { ScimSettings, scimTokenActive } from './scim-settings';

const STATUS = '/api/companies/c1/scim';
const TOKENS = '/api/companies/c1/scim/tokens';

const ENABLED: ScimStatus = {
  enabled: true,
  licensed: true,
  baseUrl: 'https://tinfirm.example.com/scim/v2',
  defaultRole: 'MEMBER',
  roleSource: 'IDP',
};
const ENTRA: ScimToken = {
  id: 'k1',
  name: 'Entra ID',
  createdAt: '2026-10-01T10:00:00Z',
  createdByName: 'Olga Owner',
  lastUsedAt: '2026-10-22T09:00:00Z',
};
const OLD: ScimToken = {
  id: 'k0',
  name: 'Old Entra ID',
  createdAt: '2026-01-01T10:00:00Z',
  revokedAt: '2026-10-01T11:00:00Z',
};

describe('scimTokenActive', () => {
  it('is false once revoked or expired', () => {
    const now = new Date('2026-10-23T00:00:00Z');
    expect(scimTokenActive(ENTRA, now)).toBe(true);
    expect(scimTokenActive(OLD, now)).toBe(false);
    expect(scimTokenActive({ ...ENTRA, expiresAt: '2026-10-01T00:00:00Z' }, now)).toBe(false);
  });
});

describe('ScimSettings', () => {
  let fixture: ComponentFixture<ScimSettings>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <E extends Element = HTMLElement>(selector: string) =>
    el().querySelector<E>(selector) ?? document.querySelector<E>(selector);
  const text = (selector: string) => q(selector)?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(
    role: Role,
    status: ScimStatus = ENABLED,
    tokens: ScimToken[] = [ENTRA, OLD],
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), fakeCurrentCompany(role).provider, notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ScimSettings);
    fixture.detectChanges();
    await settle();
    http.expectOne(STATUS).flush(status);
    http.expectOne(TOKENS).flush({ items: tokens });
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    document.querySelector('.cdk-overlay-container')?.replaceChildren();
  });

  it('shows the base URL, the default role, who decides roles and the tokens', async () => {
    await create('ADMIN');
    expect(text('[data-testid=scim-base-url]')).toBe('https://tinfirm.example.com/scim/v2');
    expect(text('[data-testid=scim-default-role]')).toBe('Member');
    expect(text('[data-testid=scim-role-source]')).toContain('Your identity provider decides them');
    expect(text('[data-testid=scim-token-k1]')).toContain('Entra ID');
    expect(text('[data-testid=scim-token-k1] [data-testid=scim-token-meta]')).toContain(
      'Olga Owner',
    );
    expect(text('[data-testid=scim-token-k0]')).toContain('Revoked');
    expect(text('[data-testid=scim-guides]')).toContain('Microsoft Entra ID');
    // An Admin reads: no token is issued or revoked by them.
    expect(q('[data-testid=scim-new-token]')).toBeNull();
    expect(q('[aria-label="Revoke Entra ID"]')).toBeNull();
  });

  it('says what is missing when the deployment does not allow SCIM, or the licence does not serve it', async () => {
    await create('OWNER', { ...ENABLED, enabled: false }, []);
    expect(text('[data-testid=scim-not-enabled]')).toContain('companies[].scim.enabled');
    expect(q<HTMLButtonElement>('[data-testid=scim-new-token]')!.disabled).toBe(true);
    expect(text('[data-testid=scim-no-tokens]')).toBe('No token yet.');
  });

  it('refuses a third token while two work', async () => {
    await create('OWNER', { ...ENABLED, licensed: false }, [ENTRA]);
    expect(text('[data-testid=scim-not-licensed]')).toContain('Enterprise');
    expect(q<HTMLButtonElement>('[data-testid=scim-new-token]')!.disabled).toBe(true);
  });

  it('issues a token, shows it once, and revokes one', async () => {
    await create('OWNER', ENABLED, [ENTRA]);
    q<HTMLButtonElement>('[data-testid=scim-new-token]')!.click();
    await settle();
    fixture.detectChanges();
    typeInto(q('#scim-token-name'), 'Okta');
    fixture.detectChanges();
    submitForm(q('#scim-token-form'));
    const request = http.expectOne({ method: 'POST', url: TOKENS });
    expect(request.request.body).toEqual({ name: 'Okta', expiresAt: null });
    request.flush({
      token: { id: 'k2', name: 'Okta', createdAt: '2026-10-23T10:00:00Z' },
      secret: 'tfs_' + 'a'.repeat(43),
    });
    await settle();
    fixture.detectChanges();
    expect(text('[data-testid=scim-secret]')).toBe('tfs_' + 'a'.repeat(43));
    expect(text('[data-testid=scim-token-once]')).toContain('not shown again');

    q<HTMLButtonElement>('[aria-label="Revoke Entra ID"]')!.click();
    await settle();
    fixture.detectChanges();
    (q<HTMLButtonElement>('[hlmAlertDialogAction]') as HTMLButtonElement).click();
    http
      .expectOne({ method: 'DELETE', url: `${TOKENS}/k1` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    fixture.detectChanges();
    expect(notify.success).toHaveBeenCalledWith(
      'Entra ID revoked',
      'Requests with it are refused.',
    );
    expect(text('[data-testid=scim-token-k1]')).toContain('Revoked');
  });

  it('words a refused issue', async () => {
    await create('OWNER', ENABLED, []);
    q<HTMLButtonElement>('[data-testid=scim-new-token]')!.click();
    await settle();
    fixture.detectChanges();
    typeInto(q('#scim-token-name'), 'Third');
    fixture.detectChanges();
    submitForm(q('#scim-token-form'));
    http
      .expectOne({ method: 'POST', url: TOKENS })
      .flush(problem(409, 'SCIM_TOKEN_LIMIT'), { status: 409, statusText: 'Conflict' });
    await settle();
    fixture.detectChanges();
    expect(document.body.textContent).toContain('Two SCIM tokens work already');
  });
});
