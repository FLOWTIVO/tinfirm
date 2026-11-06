import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { CompanySignIn, CompanySignInProvider } from '../../../api/generated/models';
import { fakeCurrentCompany, provideTestHttp, settle } from '../../../testing/test-helpers';
import { certificateEndsSoon, SignInProviders } from './sign-in-providers';

const SIGN_IN = '/api/companies/c1/sign-in';

const OIDC: CompanySignInProvider = {
  id: 'acmesso',
  name: 'Acme SSO',
  kind: 'OIDC',
  through: 'COMPANY',
  role: 'MEMBER',
  roleSource: 'APP',
  emailDomains: ['acme.example'],
  directLink: 'https://tinfirm.example.com/login?provider=acmesso',
};

/** A provider of another company that fills this one through its claim (ADR-0089). */
const THROUGH_CLAIM: CompanySignInProvider = {
  id: 'groupsso',
  name: 'Group SSO',
  kind: 'OIDC',
  through: 'CLAIM',
  role: 'MEMBER',
  roleSource: 'IDP',
  roleClaim: 'groups',
  roleMap: { admin: ['acme-admins'], member: ['acme-users', 'acme-engineers'], viewer: [] },
  emailDomains: [],
  directLink: 'https://tinfirm.example.com/login?provider=groupsso',
};

const SAML: CompanySignInProvider = {
  id: 'entra',
  name: 'Entra ID',
  kind: 'SAML',
  through: 'COMPANY',
  role: 'MEMBER',
  roleSource: 'IDP',
  roleClaim: 'http://schemas.microsoft.com/ws/2008/06/identity/claims/role',
  roleMap: { admin: ['Tinfirm.Admin'], member: ['Tinfirm.Member'], viewer: [] },
  emailDomains: [],
  directLink: 'https://tinfirm.example.com/login?provider=entra',
  saml: {
    spEntityId: 'https://tinfirm.example.com/saml2/service-provider-metadata/entra',
    acsUrl: 'https://tinfirm.example.com/login/saml2/sso/entra',
    spMetadataUrl: 'https://tinfirm.example.com/saml2/service-provider-metadata/entra',
    startUrl: 'https://tinfirm.example.com/saml2/authenticate/entra',
    idpEntityId: 'https://sts.windows.net/tenant/',
    metadataSource: 'login.microsoftonline.com',
    metadataReadAt: '2026-10-24T06:00:00Z',
    lastError: 'unreachable',
    lastErrorAt: '2026-10-24T07:00:00Z',
    idpCertificates: [
      {
        subject: 'CN=Microsoft Azure Federated SSO Certificate',
        sha256: 'ab'.repeat(32),
        notAfter: '2029-01-01T00:00:00Z',
      },
    ],
    spCertificates: [
      { subject: 'CN=tinfirm', sha256: 'cd'.repeat(32), notAfter: '2026-11-01T00:00:00Z' },
    ],
  },
};

describe('certificateEndsSoon', () => {
  it('is true within thirty days of the end', () => {
    const now = new Date('2026-10-24T00:00:00Z');
    expect(
      certificateEndsSoon({ subject: 'x', sha256: 'x', notAfter: '2026-11-01T00:00:00Z' }, now),
    ).toBe(true);
    expect(
      certificateEndsSoon({ subject: 'x', sha256: 'x', notAfter: '2027-11-01T00:00:00Z' }, now),
    ).toBe(false);
  });
});

describe('SignInProviders', () => {
  let fixture: ComponentFixture<SignInProviders>;
  let http: HttpTestingController;

  const el = () => fixture.nativeElement as HTMLElement;
  const text = (selector: string) =>
    el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(signIn: CompanySignIn) {
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), fakeCurrentCompany('ADMIN').provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SignInProviders);
    fixture.detectChanges();
    await settle();
    http.expectOne(SIGN_IN).flush(signIn);
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows each provider of the company with its link and roles', async () => {
    await create({ providers: [OIDC, SAML], samlServed: true });

    expect(text('[data-testid=sign-in-provider-acmesso]')).toContain('Acme SSO');
    expect(text('[data-testid=sign-in-provider-acmesso]')).toContain(
      'https://tinfirm.example.com/login?provider=acmesso',
    );
    expect(text('[data-testid=sign-in-roles-acmesso]')).toContain('New members are Members');
    expect(text('[data-testid=sign-in-roles-entra]')).toContain(
      'Your identity provider decides them',
    );
    expect(text('[data-testid=sign-in-roles-entra]')).toContain('Admin · Tinfirm.Admin');
    expect(el().querySelector('[data-testid=sign-in-through-entra]')).toBeNull();
    expect(el().querySelector('[data-testid=saml-not-licensed]')).toBeNull();
  });

  it('says when a provider fills the company through its claim, with the values of each role', async () => {
    await create({ providers: [OIDC, THROUGH_CLAIM], samlServed: false });

    expect(text('[data-testid=sign-in-through-groupsso]')).toContain('Through its claim');
    expect(text('[data-testid=sign-in-provider-groupsso]')).toContain('its claim “groups” names');
    expect(text('[data-testid=sign-in-roles-groupsso]')).toContain('Admin · acme-admins');
    expect(text('[data-testid=sign-in-roles-groupsso]')).toContain('Member · acme-users, acme-engineers');
    expect(text('[data-testid=sign-in-roles-groupsso]')).not.toContain('Viewer');
    expect(el().querySelector('[data-testid=sign-in-through-acmesso]')).toBeNull();
  });

  it('shows what the identity provider’s admin needs for SAML, and the metadata’s state', async () => {
    await create({ providers: [SAML], samlServed: true });

    const entra = text('[data-testid=sign-in-provider-entra]');
    expect(entra).toContain('https://tinfirm.example.com/login/saml2/sso/entra');
    expect(entra).toContain('https://tinfirm.example.com/saml2/authenticate/entra');
    expect(el().querySelector('[data-testid=saml-download-entra]')?.getAttribute('href')).toBe(
      'https://tinfirm.example.com/saml2/service-provider-metadata/entra',
    );
    expect(text('[data-testid=saml-idp-entra]')).toContain('login.microsoftonline.com');
    expect(text('[data-testid=saml-metadata-error-entra]')).toContain('could not be fetched');
    expect(text('[data-testid=saml-certificates-entra]')).toContain('Ends soon');
    expect(text('[data-testid=saml-guides]')).toContain('AD FS');
  });

  it('says SAML needs an Enterprise licence, and that no provider makes members', async () => {
    await create({ providers: [SAML], samlServed: false });
    expect(text('[data-testid=saml-not-licensed]')).toContain('Enterprise');

    fixture.destroy();
    TestBed.resetTestingModule();
    await create({ providers: [], samlServed: false });
    expect(text('[data-testid=sign-in-no-providers]')).toContain('No identity provider');
    expect(el().querySelector('[data-testid=saml-guides]')).toBeNull();
  });
});
