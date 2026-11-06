import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { ExternalSystem } from '../../../api/generated/models';
import { aSystem, aSystemRepository, IDS } from '../../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { SystemRepositories } from './system-repositories';

const REPOSITORIES = `/api/companies/c1/systems/${IDS.github}/repositories`;

describe('SystemRepositories', () => {
  let fixture: ComponentFixture<SystemRepositories>;
  let http: HttpTestingController;

  async function create(canManage = true, system: ExternalSystem = aSystem()) {
    TestBed.configureTestingModule({
      imports: [SystemRepositories],
      providers: [...provideTestHttp(), fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SystemRepositories);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.componentRef.setInput('canManage', canManage);
    await settle();
    http.expectOne((r) => r.url === REPOSITORIES).flush({ items: [aSystemRepository()] });
    await settle();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists the repositories, with their address and default branch', async () => {
    await create();
    expect(text(el().querySelector('[data-testid="repository-acme/shop"]'))).toContain(
      'https://github.com/acme/shop · main',
    );
    expect(el().querySelector('button[aria-label="Remove acme/shop"]')).not.toBeNull();
  });

  it('is read-only for whoever cannot change it', async () => {
    await create(false);
    expect(el().querySelector('[data-testid=add-repository]')).toBeNull();
    expect(el().querySelector('button[aria-label="Remove acme/shop"]')).toBeNull();
  });

  it.each`
    url                                       | error
    ${'http://github.com/acme/web'}           | ${'An https address, without a password, a query or a fragment.'}
    ${'https://github.com/acme/web?ref=main'} | ${'An https address, without a password, a query or a fragment.'}
    ${''}                                     | ${'Enter the repository’s address.'}
  `('refuses "$url" before sending it', async ({ url, error }) => {
    await create();
    el().querySelector<HTMLButtonElement>('[data-testid=add-repository]')!.click();
    await settle();
    typeInto(overlay().querySelector('#repository-url'), url);
    submitForm(overlay().querySelector('#repository-form'));
    await settle();
    expect(text(overlay())).toContain(error);
    http.expectNone((r) => r.method === 'POST');
  });

  it('adds a repository, and puts a duplicate on its field', async () => {
    await create();
    el().querySelector<HTMLButtonElement>('[data-testid=add-repository]')!.click();
    await settle();
    typeInto(overlay().querySelector('#repository-url'), 'https://github.com/acme/shop');
    submitForm(overlay().querySelector('#repository-form'));
    await settle();
    http
      .expectOne((r) => r.url === REPOSITORIES && r.method === 'POST')
      .flush(problem(409, 'DUPLICATE_REPOSITORY'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(text(overlay().querySelector('#repository-url')!.closest('[hlmField]'))).toContain(
      'This system already lists this repository.',
    );

    typeInto(overlay().querySelector('#repository-url'), 'https://github.com/acme/web');
    typeInto(overlay().querySelector('#repository-branch'), 'develop');
    submitForm(overlay().querySelector('#repository-form'));
    await settle();
    const req = http.expectOne((r) => r.url === REPOSITORIES && r.method === 'POST');
    expect(req.request.body).toEqual({
      url: 'https://github.com/acme/web',
      defaultBranch: 'develop',
    });
    req.flush(aSystemRepository({ id: 'repo-web', name: 'acme/web' }));
    await settle();
    http
      .expectOne((r) => r.url === REPOSITORIES && r.method === 'GET')
      .flush({
        items: [aSystemRepository(), aSystemRepository({ id: 'repo-web', name: 'acme/web' })],
      });
    await settle();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="repository-acme/web"]')).not.toBeNull();
  });

  it('removes a repository after a confirmation', async () => {
    await create();
    el().querySelector<HTMLButtonElement>('button[aria-label="Remove acme/shop"]')!.click();
    await settle();
    expect(text(overlay())).toContain('Remove acme/shop?');
    Array.from(overlay().querySelectorAll('button'))
      .find((b) => text(b) === 'Remove repository')!
      .click();
    await settle();
    http.expectOne(`${REPOSITORIES}/${IDS.shopRepo}`).flush(null);
    await settle();
    fixture.detectChanges();
    expect(text(el().querySelector('[data-testid=repositories-empty]'))).toBe(
      'No repositories yet.',
    );
  });
});
