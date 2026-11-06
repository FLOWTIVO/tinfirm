import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Role, SecretMeta, SecretPage } from '../../../api/generated/models';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { SettingsData } from '../settings/settings-data';
import { Secrets } from './secrets';

const GITHUB: SecretMeta = {
  id: 's1',
  name: 'GITHUB_BOT_TOKEN',
  description: 'PAT for the bot',
  version: 3,
  createdAt: '2026-09-01T10:00:00Z',
  updatedAt: '2026-09-20T10:00:00Z',
  createdBy: 'Ada Admin',
  lastAccessedAt: null,
  managed: false,
  managedBy: null,
};

const PLAINTEXT = 'ghp_super-secret-value-123';

describe('Secrets', () => {
  let fixture: ComponentFixture<Secrets>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  function setup(role: Role | null, instanceAdmin = false) {
    const current = fakeCurrentCompany(role, instanceAdmin);
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [Secrets],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        current.provider,
        notifier.provider,
        SettingsData,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Secrets);
    el = fixture.nativeElement;
  }

  async function create(role: Role | null, page: SecretPage) {
    setup(role);
    await settle();
    http.expectOne((r) => r.url === '/api/companies/c1/secrets').flush(page);
    // The settings' index reads the memory settings (owners and admins) from the same data.
    for (const req of http.match('/api/companies/c1/memory-settings')) {
      req.flush({ compactionModelAllowed: false });
    }
    await settle();
    await fixture.whenStable();
  }

  const button = (label: string) =>
    Array.from(document.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().includes(label),
    );
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists metadata and says that values are write-only', async () => {
    await create('OWNER', { items: [GITHUB] });

    expect(el.querySelector('[data-testid=write-only-note]')?.textContent).toContain(
      'Values are write-only',
    );
    const row = el.querySelector('tbody tr')!;
    expect(row.textContent).toContain('GITHUB_BOT_TOKEN');
    expect(row.textContent).toContain('v3');
    expect(row.textContent).toContain('Never');
  });

  it('marks a secret Tinfirm maintains, and offers no new value for it', async () => {
    const GRANT: SecretMeta = {
      ...GITHUB,
      id: 's2',
      name: 'SVC_READER_GITHUB',
      description: null,
      managed: true,
      managedBy: 'the account of svc-reader on GitHub',
    };
    await create('OWNER', { items: [GITHUB, GRANT] });

    const [plain, managed] = Array.from(el.querySelectorAll('tbody tr'));
    // An ordinary secret: no mark, a new value and a delete.
    expect(plain.querySelector('[data-testid=secret-managed]')).toBeNull();
    expect(plain.querySelector('[data-testid=secret-managed-by]')).toBeNull();
    expect(
      plain.querySelector('button[aria-label="Store a new value for GITHUB_BOT_TOKEN"]'),
    ).not.toBeNull();
    expect(plain.querySelector('button[aria-label="Delete GITHUB_BOT_TOKEN"]')).not.toBeNull();

    // A managed one: the mark names what maintains it; a pasted value would break it, so only delete.
    const badge = managed.querySelector('[data-testid=secret-managed]')!;
    expect(badge.textContent?.trim()).toBe('Managed');
    expect(badge.getAttribute('title')).toBe('the account of svc-reader on GitHub');
    expect(
      managed
        .querySelector('[data-testid=secret-managed-by]')
        ?.textContent?.replace(/\s+/g, ' ')
        .trim(),
    ).toBe(
      'Maintained by Tinfirm: the account of svc-reader on GitHub. Deleting it disconnects what used it.',
    );
    expect(managed.querySelector('button[aria-label^="Store a new value"]')).toBeNull();
    expect(managed.querySelector('button[aria-label="Delete SVC_READER_GITHUB"]')).not.toBeNull();
  });

  it('says a managed secret is Tinfirm’s even before the server names what maintains it', async () => {
    await create('ADMIN', {
      items: [{ ...GITHUB, name: 'SVC_READER_JIRA', managed: true, managedBy: null }],
    });
    const row = el.querySelector('tbody tr')!;
    expect(row.querySelector('[data-testid=secret-managed]')?.getAttribute('title')).toBe(
      'Maintained by Tinfirm',
    );
    expect(
      row
        .querySelector('[data-testid=secret-managed-by]')
        ?.textContent?.replace(/\s+/g, ' ')
        .trim(),
    ).toBe('Maintained by Tinfirm. Deleting it disconnects what used it.');
  });

  it('finds secrets by name or description, and filters the managed ones', async () => {
    const GRANT: SecretMeta = {
      ...GITHUB,
      id: 's2',
      name: 'SVC_READER_GITHUB',
      description: null,
      managed: true,
      managedBy: 'the account of svc-reader on GitHub',
    };
    const NPM: SecretMeta = { ...GITHUB, id: 's3', name: 'NPM_TOKEN', description: 'Publishing' };
    await create('OWNER', { items: [GITHUB, GRANT, NPM] });
    const names = () =>
      Array.from(el.querySelectorAll('tbody tr code')).map((c) => c.textContent?.trim());

    typeInto(el.querySelector('[data-testid=secret-search]'), 'github');
    await settle();
    await fixture.whenStable();
    expect(names()).toEqual(['GITHUB_BOT_TOKEN', 'SVC_READER_GITHUB']);

    typeInto(el.querySelector('[data-testid=secret-search]'), 'publish');
    await settle();
    await fixture.whenStable();
    expect(names()).toEqual(['NPM_TOKEN']);

    typeInto(el.querySelector('[data-testid=secret-search]'), '');
    el.querySelector<HTMLInputElement>('#secret-filter-managed')!.click();
    await settle();
    await fixture.whenStable();
    expect(names()).toEqual(['SVC_READER_GITHUB']);

    typeInto(el.querySelector('[data-testid=secret-search]'), 'npm');
    await settle();
    await fixture.whenStable();
    expect(names()).toEqual([]);
    expect(el.querySelector('[data-testid=secret-no-match]')?.textContent).toContain(
      'No secret matches “npm”.',
    );
  });

  it('loads the pages not loaded yet once a search narrows the list', async () => {
    const NPM: SecretMeta = { ...GITHUB, id: 's3', name: 'NPM_TOKEN', description: 'Publishing' };
    await create('OWNER', { items: [GITHUB], nextCursor: 'c2' });
    http.expectNone((r) => r.url === '/api/companies/c1/secrets');
    // A later page may hold a managed secret: the filter is offered.
    expect(el.querySelector('#secret-filter-all')).not.toBeNull();

    typeInto(el.querySelector('[data-testid=secret-search]'), 'npm');
    await settle();
    const next = http.expectOne(
      (r) => r.url === '/api/companies/c1/secrets' && r.params.get('cursor') === 'c2',
    );
    next.flush({ items: [NPM], nextCursor: null });
    await settle();
    await fixture.whenStable();

    expect(
      Array.from(el.querySelectorAll('tbody tr code')).map((c) => c.textContent?.trim()),
    ).toEqual(['NPM_TOKEN']);
  });

  it('offers no filter while Tinfirm maintains none of them', async () => {
    await create('OWNER', { items: [GITHUB] });
    expect(el.querySelector('#secret-filter-all')).toBeNull();
  });

  it('shows an empty state with a call to action', async () => {
    await create('ADMIN', { items: [] });
    expect(el.textContent).toContain('No secrets yet');
    expect(button('New secret')).toBeDefined();
  });

  it('creates a secret with a password field and never renders the value', async () => {
    await create('OWNER', { items: [] });

    button('New secret')!.click();
    await settle();
    await fixture.whenStable();

    const value = document.querySelector<HTMLInputElement>('#secret-value')!;
    expect(value.type).toBe('password');
    typeInto(document.querySelector('#secret-name'), 'GITHUB_BOT_TOKEN');
    typeInto(value, PLAINTEXT);
    submitForm(document.querySelector('#create-secret-form'));
    await settle();

    const req = http.expectOne({ method: 'POST', url: '/api/companies/c1/secrets' });
    // No description key when the field is empty.
    expect(req.request.body).toEqual({ name: 'GITHUB_BOT_TOKEN', value: PLAINTEXT });
    req.flush({ ...GITHUB, version: 1 }, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne((r) => r.url === '/api/companies/c1/secrets').flush({ items: [GITHUB] });
    await settle();
    await fixture.whenStable();

    expect(notify.success).toHaveBeenCalledWith(
      'Secret GITHUB_BOT_TOKEN stored',
      expect.any(String),
    );
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    expect(
      (
        fixture.componentInstance as unknown as { createModel: () => { value: string } }
      ).createModel().value,
    ).toBe('');
  });

  it('validates the secret name before calling the server', async () => {
    await create('OWNER', { items: [] });
    button('New secret')!.click();
    await settle();
    await fixture.whenStable();

    typeInto(document.querySelector('#secret-name'), '1-bad name');
    typeInto(document.querySelector('#secret-value'), 'x');
    submitForm(document.querySelector('#create-secret-form'));
    await settle();
    await fixture.whenStable();

    expect(overlay().textContent).toContain('Use letters, digits and underscores');
    http.expectNone({ method: 'POST', url: '/api/companies/c1/secrets' });
  });

  it('puts DUPLICATE_SECRET_NAME on the name field', async () => {
    await create('OWNER', { items: [GITHUB] });
    button('New secret')!.click();
    await settle();
    await fixture.whenStable();

    typeInto(document.querySelector('#secret-name'), 'GITHUB_BOT_TOKEN');
    typeInto(document.querySelector('#secret-value'), PLAINTEXT);
    submitForm(document.querySelector('#create-secret-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/companies/c1/secrets' })
      .flush(problem(409, 'DUPLICATE_SECRET_NAME'), { status: 409, statusText: 'Conflict' });
    await settle();
    await fixture.whenStable();

    expect(overlay().textContent).toContain('A secret with this name already exists.');
  });

  it('stores a new value for an existing secret', async () => {
    await create('ADMIN', { items: [GITHUB] });

    el.querySelector<HTMLButtonElement>(
      'button[aria-label="Store a new value for GITHUB_BOT_TOKEN"]',
    )!.click();
    await settle();
    await fixture.whenStable();
    expect(document.querySelector<HTMLInputElement>('#secret-new-value')!.type).toBe('password');

    typeInto(document.querySelector('#secret-new-value'), PLAINTEXT);
    submitForm(document.querySelector('#update-secret-form'));
    await settle();

    const req = http.expectOne({ method: 'PUT', url: '/api/companies/c1/secrets/s1' });
    // The unchanged description is not re-sent.
    expect(req.request.body).toEqual({ value: PLAINTEXT });
    req.flush({ ...GITHUB, version: 4 });
    await settle();
    await fixture.whenStable();

    expect(el.querySelector('tbody tr')?.textContent).toContain('v4');
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
  });

  it('deletes after confirmation', async () => {
    await create('OWNER', { items: [GITHUB] });

    el.querySelector<HTMLButtonElement>('button[aria-label="Delete GITHUB_BOT_TOKEN"]')!.click();
    await settle();
    await fixture.whenStable();
    expect(overlay().textContent).toContain('all its versions');

    button('Delete secret')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/secrets/s1' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    await fixture.whenStable();

    expect(notify.success).toHaveBeenCalledWith('Secret GITHUB_BOT_TOKEN deleted');
    expect(el.querySelector('tbody tr')).toBeNull();
    expect(el.textContent).toContain('No secrets yet');
  });

  it('does not even ask the server when a MEMBER opens it', async () => {
    setup('MEMBER');
    await settle();
    await fixture.whenStable();

    http.expectNone((r) => r.url.includes('/secrets'));
    expect(el.textContent).toContain('No access to secrets');
    expect(el.textContent).toContain('Secrets are visible to owners and admins.');
  });

  it('handles a 403 from the server', async () => {
    setup('ADMIN');
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/secrets')
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    http
      .expectOne('/api/companies/c1/memory-settings')
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await settle();
    await fixture.whenStable();

    expect(el.textContent).toContain("Couldn't load secrets");
    expect(el.textContent).toContain('Secrets are visible to owners and admins.');
  });
});
